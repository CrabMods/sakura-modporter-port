[CmdletBinding()]
param(
    [string]$TargetConfig = 'targets/constructionwand.properties',

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OriginalSourceRoot,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'ExpectedContentManifest.ps1')

function Read-BaselinePropertyFile([string]$PathValue) {
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

function Get-RegexGroupValuesFromContent(
    [string]$Content,
    [string]$Pattern
) {
    return @([regex]::Matches($Content, $Pattern) |
        ForEach-Object { $_.Groups[1].Value })
}

function Normalize-RepositoryUrl([string]$Value) {
    $normalized = $Value.Trim().TrimEnd('/')
    if ($normalized.EndsWith('.git',
            [StringComparison]::OrdinalIgnoreCase)) {
        $normalized = $normalized.Substring(0, $normalized.Length - 4)
    }
    return $normalized.ToLowerInvariant()
}

function Get-BaselineArtifact([string]$PathValue) {
    $resolved = (Resolve-Path -LiteralPath $PathValue).Path
    return [ordered]@{
        path = $resolved
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolved).
            Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $resolved).Length
    }
}

function Get-Utf8Sha256([string]$Value) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Value)
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Get-BytesSha256([byte[]]$Value) {
    return [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($Value)).ToLowerInvariant()
}

function New-ReadOnlyGitStartInfo([string[]]$Arguments) {
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'git'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Environment['GIT_OPTIONAL_LOCKS'] = '0'
    $startInfo.ArgumentList.Add('--no-optional-locks')
    foreach ($argument in $Arguments) {
        $startInfo.ArgumentList.Add($argument)
    }
    return $startInfo
}

function Invoke-ReadOnlyGitText(
    [string]$RepositoryRoot,
    [string[]]$Arguments
) {
    $allArguments = @('-C', $RepositoryRoot) + $Arguments
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = New-ReadOnlyGitStartInfo $allArguments
    try {
        if (-not $process.Start()) {
            throw 'Git process did not start'
        }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "Read-only git command failed (exit $($process.ExitCode)): git $($allArguments -join ' '): $($stderr.Trim())"
        }
        return $stdout.TrimEnd([char]13, [char]10)
    } finally {
        $process.Dispose()
    }
}

function Read-PinnedGitBlob(
    [string]$RepositoryRoot,
    [string]$BlobId
) {
    $allArguments = @('-C', $RepositoryRoot, 'cat-file', 'blob', $BlobId)
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = New-ReadOnlyGitStartInfo $allArguments
    $memory = [IO.MemoryStream]::new()
    try {
        if (-not $process.Start()) {
            throw 'Git blob process did not start'
        }
        $copyTask = $process.StandardOutput.BaseStream.CopyToAsync($memory)
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        [void]$copyTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) {
            throw "Read-only git blob read failed (exit $($process.ExitCode)): $($stderr.Trim())"
        }
        return ,$memory.ToArray()
    } finally {
        $memory.Dispose()
        $process.Dispose()
    }
}

function Write-BaselineJsonAtomic([string]$PathValue, [object]$Value) {
    if (-not [IO.Path]::IsPathRooted($PathValue)) {
        throw "OutputPath must be absolute: $PathValue"
    }
    $absolute = [IO.Path]::GetFullPath($PathValue)
    if ([IO.Path]::GetExtension($absolute) -cne '.json') {
        throw "OutputPath must end in .json: $absolute"
    }
    if (Test-Path -LiteralPath $absolute) {
        throw "Immutable original-source baseline already exists: $absolute"
    }
    $parent = Split-Path -Parent $absolute
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $temporary = "$absolute.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $Value | ConvertTo-Json -Depth 30 |
            Set-Content -LiteralPath $temporary -Encoding utf8
        [IO.File]::Move($temporary, $absolute, $false)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
    return Get-BaselineArtifact $absolute
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$configPath = if ([IO.Path]::IsPathRooted($TargetConfig)) {
    [IO.Path]::GetFullPath($TargetConfig)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectRoot $TargetConfig))
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Target config does not exist: $configPath"
}
$properties = Read-BaselinePropertyFile $configPath
$modId = Get-ManifestRequiredProperty $properties 'targetModId'
$manifestEvidence = Get-ModporterExpectedContentManifestEvidence `
    -Values $properties `
    -ProjectRoot $projectRoot `
    -ExpectedModId $modId
$manifest = Get-Content -Raw -LiteralPath $manifestEvidence.path |
    ConvertFrom-Json -AsHashtable
$expectedIds = @($manifest.expectedRegisteredItemIds)

if ([string]::IsNullOrWhiteSpace($OriginalSourceRoot)) {
    throw 'OriginalSourceRoot is required for accepted validation runs'
}
if (-not (Test-Path -LiteralPath $OriginalSourceRoot -PathType Container)) {
    throw "OriginalSourceRoot does not exist: $OriginalSourceRoot"
}
$sourceRoot = (Resolve-Path -LiteralPath $OriginalSourceRoot).Path
$repositoryTopLevel = Invoke-ReadOnlyGitText $sourceRoot @(
    'rev-parse', '--show-toplevel')
$repositoryTopLevel = (Resolve-Path -LiteralPath $repositoryTopLevel).Path
if ([IO.Path]::GetFullPath($repositoryTopLevel) -cne
        [IO.Path]::GetFullPath($sourceRoot)) {
    throw "OriginalSourceRoot must be the repository root: expected $repositoryTopLevel, observed $sourceRoot"
}

$pinnedCommit = ([string]$manifest.source.commit).ToLowerInvariant()
$head = (Invoke-ReadOnlyGitText $sourceRoot @(
    'rev-parse', '--verify', 'HEAD^{commit}')).ToLowerInvariant()
if ($head -cne $pinnedCommit) {
    throw "Original source commit mismatch: expected $pinnedCommit, observed $head"
}
$tree = (Invoke-ReadOnlyGitText $sourceRoot @(
    'rev-parse', '--verify', ($pinnedCommit + '^{tree}'))).
    ToLowerInvariant()
$headTree = (Invoke-ReadOnlyGitText $sourceRoot @(
    'rev-parse', '--verify', 'HEAD^{tree}')).ToLowerInvariant()
if ($headTree -cne $tree) {
    throw "Original source tree mismatch: expected $tree, observed $headTree"
}
$origin = Invoke-ReadOnlyGitText $sourceRoot @(
    'remote', 'get-url', 'origin')
if ((Normalize-RepositoryUrl $origin) -cne
        (Normalize-RepositoryUrl ([string]$manifest.source.repository))) {
    throw "Original source repository mismatch: expected $($manifest.source.repository), observed $origin"
}

$sourceFiles = @($manifest.source.files)
$gitPaths = @($sourceFiles | ForEach-Object {
    ([string]$_.path).Replace('\', '/')
})
$relevantStatus = Invoke-ReadOnlyGitText $sourceRoot (
    @('status', '--porcelain=v1', '--untracked-files=all', '--') +
        $gitPaths)
if (-not [string]::IsNullOrWhiteSpace($relevantStatus)) {
    throw "Relevant original-source derivation inputs are dirty or untracked:$([Environment]::NewLine)$relevantStatus"
}

$sourceFileEvidence = @()
$sourceContent = @{}
$strictUtf8 = [Text.UTF8Encoding]::new($false, $true)
foreach ($sourceFile in $sourceFiles) {
    $relativeSourcePath = ([string]$sourceFile.path).Replace('\', '/')
    if ([IO.Path]::IsPathRooted($relativeSourcePath) -or
            $relativeSourcePath.StartsWith('../') -or
            $relativeSourcePath.Contains('/../')) {
        throw "Pinned original source path escapes the repository: $relativeSourcePath"
    }
    $sourcePath = [IO.Path]::GetFullPath(
        (Join-Path -Path $sourceRoot -ChildPath $relativeSourcePath))
    Assert-ManifestChildPath $sourcePath $sourceRoot
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        throw "Pinned original source file is missing: $sourcePath"
    }

    $blobId = (Invoke-ReadOnlyGitText $sourceRoot @(
        'rev-parse', '--verify',
        ($pinnedCommit + ':' + $relativeSourcePath))).ToLowerInvariant()
    $indexBlobId = (Invoke-ReadOnlyGitText $sourceRoot @(
        'rev-parse', '--verify',
        (':' + $relativeSourcePath))).ToLowerInvariant()
    if ($indexBlobId -cne $blobId) {
        throw "Relevant original-source index entry differs from the pinned blob: $relativeSourcePath"
    }

    $blobBytes = Read-PinnedGitBlob $sourceRoot $blobId
    $blobSha256 = Get-BytesSha256 $blobBytes
    $worktreeBytes = [IO.File]::ReadAllBytes($sourcePath)
    $worktreeSha256 = Get-BytesSha256 $worktreeBytes
    $expectedSha256 = ([string]$sourceFile.sha256).ToLowerInvariant()
    if ($blobSha256 -cne $expectedSha256) {
        throw "Pinned git blob SHA-256 differs from the expected manifest: $relativeSourcePath"
    }
    if ($worktreeSha256 -cne $blobSha256 -or
            $worktreeBytes.Length -ne $blobBytes.Length) {
        throw "Relevant original-source worktree input differs from the pinned blob: $relativeSourcePath"
    }

    try {
        $sourceContent[$relativeSourcePath] =
            $strictUtf8.GetString($blobBytes)
    } catch {
        throw "Pinned source blob is not strict UTF-8: $relativeSourcePath"
    }
    $sourceFileEvidence += [ordered]@{
        path = $relativeSourcePath
        expectedSha256 = $expectedSha256
        pinnedBlobSha256 = $blobSha256
        worktreeSha256 = $worktreeSha256
        pinnedBlobId = $blobId
        indexBlobId = $indexBlobId
        bytes = $blobBytes.Length
        indexMatchesPinnedBlob = $true
        worktreeMatchesPinnedBlob = $true
    }
}

$derivedIds = @()
if ($modId -eq 'constructionwand') {
    $relative = 'src/main/java/thetadev/constructionwand/items/ModItems.java'
    $ids = Get-RegexGroupValuesFromContent $sourceContent[$relative] (
        'ITEMS\.register\("([a-z0-9_]+)"')
    $derivedIds = @($ids | ForEach-Object {
        '{0}:{1}' -f $modId, $_
    } | Sort-Object -CaseSensitive -Unique)
} elseif ($modId -eq 'sakura') {
    $directIds = @()
    $relative = 'src/main/java/cn/mcmod/sakura/item/ItemRegistry.java'
    $directIds += Get-RegexGroupValuesFromContent $sourceContent[$relative] (
        '\bregister\("([a-z0-9_]+)"')
    foreach ($relative in @(
            'src/main/java/cn/mcmod/sakura/block/BlockItemRegistry.java',
            'src/main/java/cn/mcmod/sakura/fluid/BucketItemRegistry.java')) {
        $directIds += Get-RegexGroupValuesFromContent $sourceContent[$relative] (
            'ITEMS\.register\("([a-z0-9_]+)"')
    }
    $relative = 'src/main/java/cn/mcmod/sakura/item/enums/SakuraNormalItemSet.java'
    $enumIds = Get-RegexGroupValuesFromContent $sourceContent[$relative] (
        '(?m)^\s*[A-Z0-9_]+\("([a-z0-9_]+)"\)')
    foreach ($relative in @(
            'src/main/java/cn/mcmod/sakura/item/enums/SakuraFoodSet.java',
            'src/main/java/cn/mcmod/sakura/item/enums/SakuraCuisineSet.java')) {
        $enumIds += Get-RegexGroupValuesFromContent $sourceContent[$relative] (
            '\.name\("([a-z0-9_]+)"\)')
    }
    $derivedIds = @(($directIds + $enumIds) |
        ForEach-Object { '{0}:{1}' -f $modId, $_ } |
        Sort-Object -CaseSensitive -Unique)
} else {
    throw "No independent source derivation is defined for targetModId $modId"
}

if ($derivedIds.Count -ne $expectedIds.Count) {
    throw "Source-derived item count mismatch: expected $($expectedIds.Count), derived $($derivedIds.Count)"
}
for ($index = 0; $index -lt $expectedIds.Count; $index++) {
    if ([string]$derivedIds[$index] -cne
            [string]$expectedIds[$index]) {
        throw "Source-derived registered item set differs at index $($index): expected $($expectedIds[$index]), derived $($derivedIds[$index])"
    }
}

$lineFeed = [char]10
$tab = [char]9
$derivedIdsCanonical = ($derivedIds -join $lineFeed) + $lineFeed
$derivationInputsCanonical = (($sourceFileEvidence | ForEach-Object {
    '{0}{1}{2}{1}{3}{1}{4}' -f $_.path, $tab, $_.pinnedBlobId,
        $_.pinnedBlobSha256, $_.bytes
}) -join $lineFeed) + $lineFeed
$configArtifact = Get-BaselineArtifact $configPath
$receipt = [ordered]@{
    schemaVersion = 2
    targetModId = $modId
    targetConfig = $configArtifact
    expectedContentManifest = $manifestEvidence
    expectedRegisteredItemCount = $expectedIds.Count
    iconEvidenceExceptionCount = $manifestEvidence.iconEvidenceExceptionCount
    originalSourceVerified = $true
    originalSource = [ordered]@{
        rootPath = $sourceRoot
        repository = [string]$manifest.source.repository
        observedOrigin = $origin
        commit = $pinnedCommit
        observedHead = $head
        tree = $tree
        observedHeadTree = $headTree
        derivation = [string]$manifest.source.derivation
        derivationInputMode = 'exact-pinned-git-blobs'
        gitOptionalLocksDisabled = $true
        relevantInputStatusClean = $true
        files = $sourceFileEvidence
        derivationInputsCanonicalSha256 =
            Get-Utf8Sha256 $derivationInputsCanonical
        derivedRegisteredItemIds = $derivedIds
        derivedRegisteredItemIdsCanonicalSha256 =
            Get-Utf8Sha256 $derivedIdsCanonical
    }
    capturedAtUtc = [DateTime]::UtcNow.ToString('o')
}
$receiptArtifact = Write-BaselineJsonAtomic -PathValue $OutputPath -Value $receipt

[pscustomobject][ordered]@{
    targetModId = $modId
    originalSourceVerified = $true
    sourceCommit = $pinnedCommit
    sourceTree = $tree
    sourceDerivationInputCount = $sourceFileEvidence.Count
    sourceDerivationInputsCanonicalSha256 =
        $receipt.originalSource.derivationInputsCanonicalSha256
    sourceDerivedRegisteredItemCount = $derivedIds.Count
    sourceDerivedRegisteredItemIdsCanonicalSha256 =
        $receipt.originalSource.derivedRegisteredItemIdsCanonicalSha256
    baselineReceipt = $receiptArtifact
}
