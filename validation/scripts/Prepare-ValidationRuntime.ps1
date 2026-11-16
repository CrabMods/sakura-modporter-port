[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('client', 'server')]
    [string]$Lane,

    [Parameter(Mandatory = $true)]
    [string]$TargetConfig,

    [Parameter(Mandatory = $true)]
    [string]$HarnessReceipt,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$BaselineReceipt
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'ExpectedContentManifest.ps1')

function Read-PropertyFile([string]$PathValue) {
    $values = @{}
    foreach ($rawLine in [IO.File]::ReadAllLines($PathValue)) {
        $line = $rawLine.Trim()
        if ($line.Length -eq 0 -or $line.StartsWith('#') -or
                $line.StartsWith('!')) {
            continue
        }
        $separator = $line.IndexOf('=')
        if ($separator -lt 1) {
            throw "Invalid property line in ${PathValue}: $rawLine"
        }
        $key = $line.Substring(0, $separator).Trim()
        $value = $line.Substring($separator + 1).Trim()
        if ($values.ContainsKey($key)) {
            throw "Duplicate property '$key' in $PathValue"
        }
        $values[$key] = $value
    }
    return $values
}

function Require-Property([hashtable]$Values, [string]$Name) {
    if (-not $Values.ContainsKey($Name) -or
            [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        throw "Required target property '$Name' is blank"
    }
    return [string]$Values[$Name]
}

function Require-PortablePathProperty(
    [hashtable]$Values,
    [string]$Name
) {
    $value = Require-Property $Values $Name
    if ($value.Contains('\')) {
        throw "Target property '$Name' must use forward slashes so PowerShell and java.util.Properties read the same path"
    }
    return $value
}

function Resolve-ConfiguredPath(
    [string]$Value,
    [string]$ConfigDirectory
) {
    $candidate = if ([IO.Path]::IsPathRooted($Value)) {
        $Value
    } else {
        Join-Path $ConfigDirectory $Value
    }
    return [IO.Path]::GetFullPath($candidate)
}

function Write-JsonAtomic([string]$PathValue, [object]$Value) {
    if (Test-Path -LiteralPath $PathValue) {
        throw "Immutable staging receipt already exists: $PathValue"
    }
    $parent = Split-Path -Parent $PathValue
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $temporary = "$PathValue.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $Value | ConvertTo-Json -Depth 20 |
            Set-Content -LiteralPath $temporary -Encoding utf8
        [IO.File]::Move($temporary, $PathValue, $false)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

function Assert-ChildPath(
    [string]$Candidate,
    [string]$Parent,
    [string]$Label
) {
    $prefix = $Parent.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $Candidate.StartsWith(
            $prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label escaped expected root: $Candidate"
    }
}

function Get-RelativeSlashPath([string]$Root, [string]$PathValue) {
    return $PathValue.Substring($Root.Length + 1).Replace('\', '/')
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$evidenceParent = [IO.Path]::GetFullPath(
    (Join-Path $projectRoot 'build\validation-evidence'))
if ([string]::IsNullOrWhiteSpace($HarnessReceipt) -or
        -not [IO.Path]::IsPathRooted($HarnessReceipt)) {
    throw 'HarnessReceipt must be a non-blank absolute path'
}
$harnessReceiptPath = [IO.Path]::GetFullPath($HarnessReceipt)
Assert-ChildPath $harnessReceiptPath $evidenceParent 'Harness receipt'
if ([IO.Path]::GetExtension($harnessReceiptPath) -ne '.json') {
    throw "Harness receipt path must end in .json: $harnessReceiptPath"
}
if (Test-Path -LiteralPath $harnessReceiptPath) {
    throw "Immutable harness receipt already exists: $harnessReceiptPath"
}
$harnessReceiptParent = Split-Path -Parent $harnessReceiptPath
if (-not (Test-Path -LiteralPath $harnessReceiptParent -PathType Container)) {
    throw "Harness receipt parent does not exist: $harnessReceiptParent"
}
if ([string]::IsNullOrWhiteSpace($BaselineReceipt) -or
        -not [IO.Path]::IsPathRooted($BaselineReceipt)) {
    throw 'BaselineReceipt must be a non-blank absolute path'
}
$baselineReceiptPath = [IO.Path]::GetFullPath($BaselineReceipt)
if (-not [string]::Equals(
        (Split-Path -Parent $baselineReceiptPath),
        $harnessReceiptParent,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw "BaselineReceipt must be in the same immutable evidence directory as HarnessReceipt: $baselineReceiptPath"
}
if (-not (Test-Path -LiteralPath $baselineReceiptPath -PathType Leaf)) {
    throw "Original-source baseline receipt does not exist: $baselineReceiptPath"
}
$baselineReceiptArtifact = [ordered]@{
    path = (Resolve-Path -LiteralPath $baselineReceiptPath).Path
    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $baselineReceiptPath).Hash.ToLowerInvariant()
    bytes = (Get-Item -LiteralPath $baselineReceiptPath).Length
}
try {
    $baseline = Get-Content -Raw -LiteralPath $baselineReceiptPath |
        ConvertFrom-Json -AsHashtable
} catch {
    throw "Original-source baseline receipt is not valid JSON: $($_.Exception.Message)"
}

$configPath = if ([IO.Path]::IsPathRooted($TargetConfig)) {
    [IO.Path]::GetFullPath($TargetConfig)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectRoot $TargetConfig))
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Target config does not exist: $configPath"
}

$properties = Read-PropertyFile $configPath
$modId = Require-Property $properties 'targetModId'
if ($modId -notmatch '^[a-z][a-z0-9_]{1,63}$') {
    throw "Invalid targetModId '$modId'"
}

$expectedJarSha256 = (Require-Property $properties 'targetJarSha256').ToLowerInvariant()
if ($expectedJarSha256 -notmatch '^[0-9a-f]{64}$') {
    throw 'targetJarSha256 must be exactly 64 lowercase hexadecimal characters'
}

$configDirectory = Split-Path -Parent $configPath
$expectedContentManifestEvidence = Get-ModporterExpectedContentManifestEvidence `
    -Values $properties `
    -ProjectRoot $projectRoot `
    -ExpectedModId $modId
$expectedManifest = Get-Content -Raw -LiteralPath $expectedContentManifestEvidence.path |
    ConvertFrom-Json -AsHashtable
$configSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $configPath).Hash.ToLowerInvariant()
if ($baseline -isnot [Collections.IDictionary] -or
        -not $baseline.Contains('schemaVersion') -or
        [long]$baseline.schemaVersion -ne 2 -or
        [string]$baseline.targetModId -cne $modId -or
        $baseline.originalSourceVerified -ne $true) {
    throw 'Original-source baseline receipt lacks a verified schema-2 target binding'
}
if ([string]$baseline.targetConfig.path -cne (Resolve-Path -LiteralPath $configPath).Path -or
        [string]$baseline.targetConfig.sha256 -cne $configSha256) {
    throw 'Original-source baseline target-config binding does not match staging input'
}
if ([string]$baseline.expectedContentManifest.path -cne $expectedContentManifestEvidence.path -or
        [string]$baseline.expectedContentManifest.sha256 -cne $expectedContentManifestEvidence.sha256) {
    throw 'Original-source baseline expected-content manifest binding does not match staging input'
}
if ([string]$baseline.originalSource.repository -cne [string]$expectedManifest.source.repository -or
        [string]$baseline.originalSource.commit -cne [string]$expectedManifest.source.commit -or
        [string]$baseline.originalSource.observedHead -cne [string]$expectedManifest.source.commit -or
        [string]$baseline.originalSource.derivation -cne [string]$expectedManifest.source.derivation) {
    throw 'Original-source baseline repository, commit, or derivation does not match the pinned manifest'
}
if ([string]$baseline.originalSource.tree -notmatch '^[0-9a-f]{40}$' -or
        [string]$baseline.originalSource.observedHeadTree -cne [string]$baseline.originalSource.tree -or
        [string]$baseline.originalSource.derivationInputMode -cne 'exact-pinned-git-blobs' -or
        $baseline.originalSource.gitOptionalLocksDisabled -ne $true -or
        $baseline.originalSource.relevantInputStatusClean -ne $true) {
    throw 'Original-source baseline lacks an exact clean pinned-tree derivation binding'
}
$baselineFiles = @($baseline.originalSource.files)
$expectedSourceFiles = @($expectedManifest.source.files)
if ($baselineFiles.Count -ne $expectedSourceFiles.Count) {
    throw 'Original-source baseline source-file count does not match the pinned manifest'
}
for ($index = 0; $index -lt $expectedSourceFiles.Count; $index++) {
    $baselineFile = $baselineFiles[$index]
    $expectedFile = $expectedSourceFiles[$index]
    if ([string]$baselineFile.path -cne [string]$expectedFile.path -or
            [string]$baselineFile.expectedSha256 -cne [string]$expectedFile.sha256 -or
            [string]$baselineFile.pinnedBlobSha256 -cne [string]$expectedFile.sha256 -or
            [string]$baselineFile.worktreeSha256 -cne [string]$expectedFile.sha256 -or
            [string]$baselineFile.pinnedBlobId -notmatch '^[0-9a-f]{40}$' -or
            [string]$baselineFile.indexBlobId -cne [string]$baselineFile.pinnedBlobId -or
            [long]$baselineFile.bytes -le 0 -or
            $baselineFile.indexMatchesPinnedBlob -ne $true -or
            $baselineFile.worktreeMatchesPinnedBlob -ne $true) {
        throw "Original-source baseline pinned-blob binding differs at index $index"
    }
}
$lineFeed = [char]10
$tab = [char]9
$derivationInputsCanonical = (($baselineFiles | ForEach-Object {
    '{0}{1}{2}{1}{3}{1}{4}' -f $_.path, $tab, $_.pinnedBlobId,
        $_.pinnedBlobSha256, $_.bytes
}) -join $lineFeed) + $lineFeed
$derivationInputsBytes = [Text.Encoding]::UTF8.GetBytes(
    $derivationInputsCanonical)
$derivationInputsSha256 = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData($derivationInputsBytes)).
    ToLowerInvariant()
if ([string]$baseline.originalSource.derivationInputsCanonicalSha256 -cne
        $derivationInputsSha256) {
    throw 'Original-source baseline derivation-input digest is inconsistent'
}
$baselineIds = @($baseline.originalSource.derivedRegisteredItemIds)
$expectedIds = @($expectedManifest.expectedRegisteredItemIds)
if ($baselineIds.Count -ne $expectedIds.Count) {
    throw 'Original-source baseline derived item count does not match the pinned manifest'
}
for ($index = 0; $index -lt $expectedIds.Count; $index++) {
    if ([string]$baselineIds[$index] -cne [string]$expectedIds[$index]) {
        throw "Original-source baseline derived item order differs at index $index"
    }
}
$expectedIdsBytes = [Text.Encoding]::UTF8.GetBytes(
    ($expectedIds -join $lineFeed) + $lineFeed)
$expectedIdsSha256 = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData($expectedIdsBytes)).
    ToLowerInvariant()
if ([string]$baseline.originalSource.derivedRegisteredItemIdsCanonicalSha256 -cne
        $expectedIdsSha256) {
    throw 'Original-source baseline derived item digest does not match the pinned manifest'
}
$targetJar = Resolve-ConfiguredPath (
    Require-PortablePathProperty $properties 'targetJarPath') $configDirectory
if (-not (Test-Path -LiteralPath $targetJar -PathType Leaf)) {
    throw "Target JAR does not exist: $targetJar"
}
if ([IO.Path]::GetExtension($targetJar) -ne '.jar') {
    throw "Target path is not a .jar file: $targetJar"
}

$observedJarSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $targetJar).
    Hash.ToLowerInvariant()
if ($observedJarSha256 -ne $expectedJarSha256) {
    throw "Target JAR SHA-256 mismatch: expected $expectedJarSha256, observed $observedJarSha256"
}

$runtimeParent = [IO.Path]::GetFullPath(
    (Join-Path $projectRoot 'build\validation-runtime'))
$runtimeDirectory = [IO.Path]::GetFullPath(
    (Join-Path $runtimeParent $Lane))
Assert-ChildPath $runtimeDirectory $runtimeParent 'Runtime directory'
if ($targetJar.StartsWith(
        $runtimeParent.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'targetJarPath must not point inside a generated validation runtime'
}

if (Test-Path -LiteralPath $runtimeDirectory) {
    Remove-Item -LiteralPath $runtimeDirectory -Recurse -Force
}
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$modsDirectory = Join-Path $runtimeDirectory 'mods'
New-Item -ItemType Directory -Path $modsDirectory -Force | Out-Null
New-Item -ItemType Directory -Path (
    Join-Path $runtimeDirectory 'validation') -Force | Out-Null

$stagedJar = Join-Path $modsDirectory 'target-under-test.jar'
Copy-Item -LiteralPath $targetJar -Destination $stagedJar
$stagedJarSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $stagedJar).
    Hash.ToLowerInvariant()
if ($stagedJarSha256 -ne $expectedJarSha256) {
    throw "Staged target JAR SHA-256 mismatch: $stagedJarSha256"
}
$installedJars = @(Get-ChildItem -LiteralPath $modsDirectory -File |
    Where-Object { $_.Extension -eq '.jar' })
if ($installedJars.Count -ne 1 -or
        $installedJars[0].FullName -ne $stagedJar) {
    throw 'Clean runtime mods directory must contain only target-under-test.jar'
}

$worldEvidence = $null
if ($Lane -eq 'client') {
    $sourceWorld = Resolve-ConfiguredPath (
        Require-PortablePathProperty $properties 'pristineWorldPath') $configDirectory
    if (-not (Test-Path -LiteralPath $sourceWorld -PathType Container)) {
        throw "Pristine world directory does not exist: $sourceWorld"
    }
    if ($sourceWorld -match '(?i)[\\/]\.minecraft[\\/]saves[\\/]') {
        throw 'pristineWorldPath must not point into a real .minecraft/saves directory'
    }
    if ($sourceWorld.StartsWith(
            $runtimeParent.TrimEnd('\', '/') +
                [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw 'pristineWorldPath must not point inside a generated validation runtime'
    }
    $sourceWorldItem = Get-Item -LiteralPath $sourceWorld -Force
    if (($sourceWorldItem.Attributes -band
            [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Pristine world root must not be a reparse point: $sourceWorld"
    }
    $reparseEntries = @(Get-ChildItem -LiteralPath $sourceWorld -Recurse -Force |
        Where-Object {
            ($_.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0
        })
    if ($reparseEntries.Count -gt 0) {
        throw "Pristine world must not contain reparse points: $($reparseEntries[0].FullName)"
    }

    $manifestPath = Join-Path $projectRoot 'fixtures\client-world.manifest.json'
    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    if ([int]$manifest.schemaVersion -ne 1 -or
            [string]$manifest.minecraftVersion -ne '1.21.1') {
        throw "Unsupported pristine-world manifest: $manifestPath"
    }

    $expectedFiles = @{}
    foreach ($entry in $manifest.files) {
        $relative = ([string]$entry.path).Replace('\', '/')
        if ($relative.StartsWith('/') -or $relative.Contains('../')) {
            throw "Unsafe fixture path in manifest: $relative"
        }
        if ($expectedFiles.ContainsKey($relative)) {
            throw "Duplicate fixture path in manifest: $relative"
        }
        $expectedFiles[$relative] = $entry
    }

    $actualFiles = @{}
    foreach ($file in Get-ChildItem -LiteralPath $sourceWorld -Recurse -File -Force) {
        $relative = Get-RelativeSlashPath $sourceWorld $file.FullName
        if ($relative -eq 'session.lock') {
            continue
        }
        $actualFiles[$relative] = $file
    }
    $missing = @($expectedFiles.Keys | Where-Object {
        -not $actualFiles.ContainsKey($_)
    })
    $unexpected = @($actualFiles.Keys | Where-Object {
        -not $expectedFiles.ContainsKey($_)
    })
    if ($missing.Count -gt 0 -or $unexpected.Count -gt 0) {
        throw "Pristine world file set mismatch; missing=[$($missing -join ', ')], unexpected=[$($unexpected -join ', ')]"
    }

    $runtimeWorld = Join-Path $runtimeDirectory 'saves\exact_jar_validation'
    foreach ($relative in @($expectedFiles.Keys | Sort-Object)) {
        $sourceFile = $actualFiles[$relative]
        $expected = $expectedFiles[$relative]
        if ([long]$sourceFile.Length -ne [long]$expected.bytes) {
            throw "Pristine world size mismatch for $relative"
        }
        $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $sourceFile.FullName).
            Hash.ToLowerInvariant()
        if ($hash -ne [string]$expected.sha256) {
            throw "Pristine world SHA-256 mismatch for $relative"
        }
        $destination = Join-Path $runtimeWorld $relative
        New-Item -ItemType Directory -Path (
            Split-Path -Parent $destination) -Force | Out-Null
        Copy-Item -LiteralPath $sourceFile.FullName -Destination $destination
    }

    @(
        'onboardAccessibility:false'
        'skipMultiplayerWarning:true'
        'tutorialStep:none'
        'pauseOnLostFocus:false'
        'fullscreen:false'
        'soundCategory_master:0.0'
    ) | Set-Content -LiteralPath (
        Join-Path $runtimeDirectory 'options.txt') -Encoding utf8

    $fmlConfigDirectory = Join-Path $runtimeDirectory 'config'
    New-Item -ItemType Directory -Path $fmlConfigDirectory -Force |
        Out-Null
    @(
        'disableConfigWatcher = false'
        'earlyWindowControl = false'
        'maxThreads = -1'
        'versionCheck = false'
        'defaultConfigPath = "defaultconfigs"'
        'disableOptimizedDFU = true'
        'earlyWindowProvider = "fmlearlywindow"'
        'earlyWindowWidth = 854'
        'earlyWindowHeight = 480'
        'earlyWindowFBScale = 1'
        'earlyWindowMaximized = false'
        'earlyWindowSkipGLVersions = []'
        'earlyWindowSquir = false'
        ''
        '[dependencyOverrides]'
    ) | Set-Content -LiteralPath (
        Join-Path $fmlConfigDirectory 'fml.toml') -Encoding utf8

    $worldEvidence = [ordered]@{
        sourcePath = $sourceWorld
        sourceReadOnly = $true
        manifestPath = $manifestPath
        manifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $manifestPath).
            Hash.ToLowerInvariant()
        stagedPath = $runtimeWorld
        fileCount = $expectedFiles.Count
    }
} else {
    'eula=true' | Set-Content -LiteralPath (
        Join-Path $runtimeDirectory 'eula.txt') -Encoding ascii
    @(
        'online-mode=false'
        'level-name=exact_jar_validation_server'
        'gamemode=creative'
        'force-gamemode=true'
        'enable-status=false'
        'motd=Exact JAR validation'
        'view-distance=2'
        'simulation-distance=2'
        'spawn-protection=0'
    ) | Set-Content -LiteralPath (
        Join-Path $runtimeDirectory 'server.properties') -Encoding ascii
}

$stagingReceipt = Join-Path $harnessReceiptParent 'staging.json'
Write-JsonAtomic $stagingReceipt ([ordered]@{
    schemaVersion = 3
    lane = $Lane
    targetModId = $modId
    targetConfigPath = $configPath
    targetConfigSha256 = $configSha256
    expectedContentManifest = $expectedContentManifestEvidence
    originalSourceBaselineReceipt = $baselineReceiptArtifact
    originalSource = [ordered]@{
        repository = [string]$baseline.originalSource.repository
        commit = [string]$baseline.originalSource.commit
        tree = [string]$baseline.originalSource.tree
        derivation = [string]$baseline.originalSource.derivation
        derivationInputMode = [string]$baseline.originalSource.derivationInputMode
        relevantInputStatusClean = $true
        sourceFileCount = $baselineFiles.Count
        derivationInputsCanonicalSha256 = $derivationInputsSha256
        derivedRegisteredItemCount = $baselineIds.Count
        derivedRegisteredItemIdsCanonicalSha256 = $expectedIdsSha256
        verified = $true
    }
    harnessReceiptPath = $harnessReceiptPath
    sourceJarPath = $targetJar
    stagedJarPath = $stagedJar
    targetJarSha256 = $stagedJarSha256
    modsJarCount = $installedJars.Count
    pristineWorld = $worldEvidence
    stagedAtUtc = [DateTime]::UtcNow.ToString('o')
})

