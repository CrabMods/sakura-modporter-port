[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('client', 'server')]
    [string]$Lane,

    [string]$TargetConfig = 'targets/constructionwand.properties',

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$OriginalSourceRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-JsonImmutable([string]$PathValue, [object]$Value) {
    if (Test-Path -LiteralPath $PathValue) {
        throw "Immutable validation receipt already exists: $PathValue"
    }
    $parent = Split-Path -Parent $PathValue
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $temporary = "$PathValue.$([guid]::NewGuid().ToString('N')).tmp"
    try {
        $Value | ConvertTo-Json -Depth 30 |
            Set-Content -LiteralPath $temporary -Encoding utf8
        [IO.File]::Move($temporary, $PathValue, $false)
    } finally {
        if (Test-Path -LiteralPath $temporary) {
            Remove-Item -LiteralPath $temporary -Force
        }
    }
}

function Get-ArtifactOrNull([string]$PathValue) {
    if (-not (Test-Path -LiteralPath $PathValue -PathType Leaf)) {
        return $null
    }
    $resolved = (Resolve-Path -LiteralPath $PathValue).Path
    return [ordered]@{
        path = $resolved
        sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $resolved).
            Hash.ToLowerInvariant()
        bytes = (Get-Item -LiteralPath $resolved).Length
    }
}

function Get-PublicValidationSourceFiles([string]$Root) {
    $canonicalRoot = (Resolve-Path -LiteralPath $Root).Path
    $excludedRootDirectories = @(
        'build', '.gradle', '.minecraft-mod-testing', '.modporter',
        '.agents'
    )
    $pending = [Collections.Generic.Stack[IO.DirectoryInfo]]::new()
    $pending.Push([IO.DirectoryInfo]::new($canonicalRoot))
    while ($pending.Count -gt 0) {
        $directory = $pending.Pop()
        $isRoot = [string]::Equals(
            $directory.FullName,
            $canonicalRoot,
            [StringComparison]::OrdinalIgnoreCase)
        foreach ($entry in $directory.EnumerateFileSystemInfos()) {
            if ($entry -is [IO.DirectoryInfo]) {
                if ($isRoot -and $entry.Name -in $excludedRootDirectories) {
                    continue
                }
                if (($entry.Attributes -band
                        [IO.FileAttributes]::ReparsePoint) -ne 0) {
                    throw "Source manifest refuses a reparse-point directory: $($entry.FullName)"
                }
                $pending.Push([IO.DirectoryInfo]$entry)
                continue
            }
            $relative = [IO.Path]::GetRelativePath(
                $canonicalRoot, $entry.FullName).Replace('\', '/')
            if ($relative -notin @(
                    'AGENTS.md', 'mod-build.ps1', 'mod-build.cmd')) {
                Write-Output ([IO.FileInfo]$entry)
            }
        }
    }
}

function Get-ValidationSourceSnapshot([string]$Root) {
    $entries = @(
        Get-PublicValidationSourceFiles -Root $Root |
            ForEach-Object {
                $relative = [IO.Path]::GetRelativePath(
                    $Root, $_.FullName).Replace('\', '/')
                [pscustomobject][ordered]@{
                    path = $relative
                    bytes = $_.Length
                    sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (
                        $_.FullName)).Hash.ToLowerInvariant()
                }
            } |
            Sort-Object { $_.path }
    )
    $rows = @($entries | ForEach-Object {
        "$($_.path)|$($_.bytes)|$($_.sha256)"
    })
    $bytes = [Text.Encoding]::UTF8.GetBytes(
        ($rows -join "`n") + "`n")
    return [ordered]@{
        fileCount = $entries.Count
        totalBytes = [long]($entries |
            Measure-Object -Property bytes -Sum).Sum
        rootSha256 = [Convert]::ToHexString(
            [Security.Cryptography.SHA256]::HashData($bytes)).
            ToLowerInvariant()
        files = $entries
    }
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$runtimeSessionRunner = Join-Path $PSScriptRoot (
    'Invoke-ModporterRuntimeSession.ps1')
$runtimeInventory = Join-Path $PSScriptRoot (
    'runtime_process_inventory.ps1')
$requiredPublicFiles = @(
    $runtimeSessionRunner,
    $runtimeInventory,
    (Join-Path $projectRoot 'gradle/modporter-testing.gradle'),
    (Join-Path $PSScriptRoot 'run_gradle.py'),
    (Join-Path $PSScriptRoot 'modporter_build.py')
)
foreach ($requiredAdapter in $requiredPublicFiles) {
    if (-not (Test-Path -LiteralPath $requiredAdapter -PathType Leaf)) {
        throw "Public runtime adapter is missing: $requiredAdapter"
    }
}

$configPath = if ([IO.Path]::IsPathRooted($TargetConfig)) {
    [IO.Path]::GetFullPath($TargetConfig)
} else {
    [IO.Path]::GetFullPath((Join-Path $projectRoot $TargetConfig))
}
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    throw "Target config does not exist: $configPath"
}

$runId = '{0}-{1}-{2}' -f @(
    $Lane,
    [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffffffZ'),
    [guid]::NewGuid().ToString('N')
)
$evidenceParent = Join-Path $projectRoot 'build\validation-evidence'
$evidenceDirectory = Join-Path $evidenceParent $runId
$runtimeDirectory = Join-Path $projectRoot "build\validation-runtime\$Lane"
$runtimeLog = Join-Path $evidenceDirectory 'runtime.log'
$sessionReceipt = Join-Path $evidenceDirectory 'runtime-session.json'
$logAuditReceipt = Join-Path $evidenceDirectory 'log-audit.json'
$harnessReceipt = Join-Path $evidenceDirectory 'harness.json'
$stagingReceipt = Join-Path $evidenceDirectory 'staging.json'
$baselineReceipt = Join-Path $evidenceDirectory 'original-source-baseline.json'
$sourceManifestReceipt = Join-Path $evidenceDirectory (
    'harness-source-manifest.json')
$worldAuditReceipt = Join-Path $evidenceDirectory (
    'pristine-world-post-run.json')
$runReceipt = Join-Path $evidenceDirectory 'validation-run.json'
$expectedScenarioIds = if ($Lane -eq 'client') {
    @(
        'target-jar-origin',
        'client-world-ready',
        'creative-inventory-rendered',
        'mod-creative-tabs-all-rows-rendered'
    )
} else {
    @('target-jar-origin', 'dedicated-server-ready-save-stop')
}
$expectedScenarioCount = $expectedScenarioIds.Count
$pwsh = (Get-Command pwsh -ErrorAction Stop).Source
$hiddenAdapter = Join-Path $PSScriptRoot 'Run-HiddenValidationGradle.ps1'

if (Test-Path -LiteralPath $evidenceDirectory) {
    throw "Immutable validation output root already exists: $evidenceDirectory"
}
$outputPaths = @(
    $runtimeLog,
    $sessionReceipt,
    $logAuditReceipt,
    $harnessReceipt,
    $stagingReceipt,
    $baselineReceipt,
    $sourceManifestReceipt,
    $runReceipt
)
if ($Lane -eq 'client') {
    $outputPaths += $worldAuditReceipt
}
foreach ($outputPath in $outputPaths) {
    if (Test-Path -LiteralPath $outputPath) {
        throw "Immutable validation output already exists: $outputPath"
    }
}
New-Item -ItemType Directory -Path $evidenceDirectory | Out-Null
& (Join-Path $PSScriptRoot 'Test-ExpectedContentBaseline.ps1') -TargetConfig $configPath -OriginalSourceRoot $OriginalSourceRoot -OutputPath $baselineReceipt | Out-Null
$baselineArtifactBefore = Get-ArtifactOrNull $baselineReceipt
if ($null -eq $baselineArtifactBefore) {
    throw "Original-source baseline receipt was not created: $baselineReceipt"
}
$sourceSnapshotBefore = Get-ValidationSourceSnapshot $projectRoot
Write-JsonImmutable $sourceManifestReceipt ([ordered]@{
    schemaVersion = 1
    projectRoot = $projectRoot
    fileCount = $sourceSnapshotBefore.fileCount
    totalBytes = $sourceSnapshotBefore.totalBytes
    rootSha256 = $sourceSnapshotBefore.rootSha256
    files = $sourceSnapshotBefore.files
    capturedAtUtc = [DateTime]::UtcNow.ToString('o')
})

$oldLane = $env:EXACT_JAR_VALIDATION_LANE
$oldTargetConfig = $env:EXACT_JAR_VALIDATION_TARGET_CONFIG
$oldHarnessReceipt = $env:EXACT_JAR_VALIDATION_HARNESS_RECEIPT
$oldBaselineReceipt = $env:EXACT_JAR_VALIDATION_BASELINE_RECEIPT
try {
    $env:EXACT_JAR_VALIDATION_LANE = $Lane
    $env:EXACT_JAR_VALIDATION_TARGET_CONFIG = $configPath
    $env:EXACT_JAR_VALIDATION_HARNESS_RECEIPT = $harnessReceipt
    $env:EXACT_JAR_VALIDATION_BASELINE_RECEIPT = $baselineReceipt
    & $pwsh `
        -NoProfile `
        -File $runtimeSessionRunner `
        -ProjectRoot $projectRoot `
        -Executable $hiddenAdapter `
        -RuntimeKind $Lane `
        -RuntimeDirectory $runtimeDirectory `
        -ReceiptPath $sessionReceipt `
        -HarnessReceiptPath $harnessReceipt `
        -ExpectedScenarioCount $expectedScenarioCount `
        -ExpectedScenarioIdsCsv ($expectedScenarioIds -join '|') `
        -ExpectedProcessStartCount 1 `
        -ExpectedWorldLoadCount 1 *> $runtimeLog
    $runtimeExitCode = $LASTEXITCODE
} finally {
    $env:EXACT_JAR_VALIDATION_LANE = $oldLane
    $env:EXACT_JAR_VALIDATION_TARGET_CONFIG = $oldTargetConfig
    $env:EXACT_JAR_VALIDATION_HARNESS_RECEIPT = $oldHarnessReceipt
    $env:EXACT_JAR_VALIDATION_BASELINE_RECEIPT = $oldBaselineReceipt
}

$auditExitCode = 0
$auditFailure = ''
try {
    & (Join-Path $PSScriptRoot 'Test-ValidationLogs.ps1') `
        -Lane $Lane `
        -LogPath $runtimeLog `
        -HarnessReceiptPath $harnessReceipt `
        -OutputPath $logAuditReceipt
} catch {
    $auditExitCode = 1
    $auditFailure = $_.Exception.Message
}

$worldAuditExitCode = 0
$worldAuditFailure = ''
if ($Lane -eq 'client') {
    try {
        & (Join-Path $PSScriptRoot 'Test-PristineWorldUnchanged.ps1') `
            -TargetConfig $configPath `
            -OutputPath $worldAuditReceipt
    } catch {
        $worldAuditExitCode = 1
        $worldAuditFailure = $_.Exception.Message
    }
}

$sourceSnapshotAfter = Get-ValidationSourceSnapshot $projectRoot
$sourceAuditExitCode = if ($sourceSnapshotAfter.rootSha256 -eq
        $sourceSnapshotBefore.rootSha256) { 0 } else { 1 }
$sourceAuditFailure = if ($sourceAuditExitCode -eq 0) {
    ''
} else {
    "Validation source changed during the run: before=$($sourceSnapshotBefore.rootSha256), after=$($sourceSnapshotAfter.rootSha256)"
}
$baselineArtifactAfter = Get-ArtifactOrNull $baselineReceipt
$baselineAuditExitCode = if ($null -ne $baselineArtifactAfter -and
        $baselineArtifactBefore.sha256 -ceq $baselineArtifactAfter.sha256 -and
        [long]$baselineArtifactBefore.bytes -eq [long]$baselineArtifactAfter.bytes) { 0 } else { 1 }
$baselineAuditFailure = if ($baselineAuditExitCode -eq 0) {
    ''
} else {
    'Original-source baseline receipt changed or disappeared during validation'
}
$passed = $runtimeExitCode -eq 0 -and
    $auditExitCode -eq 0 -and
    $baselineAuditExitCode -eq 0 -and
    $worldAuditExitCode -eq 0 -and
    $sourceAuditExitCode -eq 0
Write-JsonImmutable $runReceipt ([ordered]@{
    schemaVersion = 1
    runId = $runId
    lane = $Lane
    runtimeDirectory = [IO.Path]::GetFullPath($runtimeDirectory)
    targetConfig = Get-ArtifactOrNull $configPath
    exitResults = [ordered]@{
        runtimeSession = $runtimeExitCode
        strictLogAudit = $auditExitCode
        originalSourceBaselineUnchanged = $baselineAuditExitCode
        harnessSourceUnchanged = $sourceAuditExitCode
        pristineWorldPostRun = if ($Lane -eq 'client') {
            $worldAuditExitCode
        } else {
            $null
        }
    }
    failures = [ordered]@{
        strictLogAudit = $auditFailure
        originalSourceBaselineUnchanged = $baselineAuditFailure
        harnessSourceUnchanged = $sourceAuditFailure
        pristineWorldPostRun = $worldAuditFailure
    }
    artifacts = [ordered]@{
        runtimeLog = Get-ArtifactOrNull $runtimeLog
        harnessSourceManifest = Get-ArtifactOrNull (
            $sourceManifestReceipt)
        stagingReceipt = Get-ArtifactOrNull $stagingReceipt
        originalSourceBaselineReceipt = $baselineArtifactAfter
        harnessReceipt = Get-ArtifactOrNull $harnessReceipt
        runtimeSessionReceipt = Get-ArtifactOrNull $sessionReceipt
        logAuditReceipt = Get-ArtifactOrNull $logAuditReceipt
        pristineWorldPostRunReceipt = if ($Lane -eq 'client') {
            Get-ArtifactOrNull $worldAuditReceipt
        } else {
            $null
        }
    }
    harnessSource = [ordered]@{
        beforeRootSha256 = $sourceSnapshotBefore.rootSha256
        afterRootSha256 = $sourceSnapshotAfter.rootSha256
        unchanged = $sourceAuditExitCode -eq 0
    }
    passed = $passed
    sealedAtUtc = [DateTime]::UtcNow.ToString('o')
})

if ($runtimeExitCode -ne 0) {
    throw "Runtime $Lane validation failed with exit code $runtimeExitCode; see $runtimeLog"
}
if ($auditExitCode -ne 0) {
    throw "Strict $Lane log audit failed: $auditFailure; see $logAuditReceipt"
}
if ($baselineAuditExitCode -ne 0) {
    throw $baselineAuditFailure
}
if ($sourceAuditExitCode -ne 0) {
    throw $sourceAuditFailure
}
if ($worldAuditExitCode -ne 0) {
    throw "Pristine client world post-run audit failed: $worldAuditFailure; see $worldAuditReceipt"
}

[pscustomobject]@{
    runId = $runId
    lane = $Lane
    evidenceDirectory = $evidenceDirectory
    harnessReceipt = $harnessReceipt
    stagingReceipt = $stagingReceipt
    originalSourceBaselineReceipt = $baselineReceipt
    harnessSourceManifest = $sourceManifestReceipt
    sessionReceipt = $sessionReceipt
    runtimeLog = $runtimeLog
    logAuditReceipt = $logAuditReceipt
    pristineWorldPostRunReceipt = if ($Lane -eq 'client') {
        $worldAuditReceipt
    } else {
        $null
    }
    runReceipt = $runReceipt
}





