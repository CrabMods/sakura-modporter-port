[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$TargetConfig,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

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

function Write-JsonImmutable([string]$PathValue, [object]$Value) {
    if (Test-Path -LiteralPath $PathValue) {
        throw "Immutable pristine-world audit already exists: $PathValue"
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

if (Test-Path -LiteralPath $OutputPath) {
    throw "Immutable pristine-world audit already exists: $OutputPath"
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

$properties = Read-PropertyFile $configPath
if (-not $properties.ContainsKey('pristineWorldPath') -or
        [string]::IsNullOrWhiteSpace(
            [string]$properties['pristineWorldPath'])) {
    throw "Required target property 'pristineWorldPath' is blank"
}
$configuredWorld = [string]$properties['pristineWorldPath']
if ($configuredWorld.Contains('\')) {
    throw 'pristineWorldPath must use forward slashes'
}
$configDirectory = Split-Path -Parent $configPath
$sourceWorld = [IO.Path]::GetFullPath(
    $(if ([IO.Path]::IsPathRooted($configuredWorld)) {
        $configuredWorld
    } else {
        Join-Path $configDirectory $configuredWorld
    })
)
if (-not (Test-Path -LiteralPath $sourceWorld -PathType Container)) {
    throw "Pristine world directory does not exist: $sourceWorld"
}
if ($sourceWorld -match '(?i)[\\/]\.minecraft[\\/]saves[\\/]') {
    throw 'pristineWorldPath must not point into a real .minecraft/saves directory'
}
$runtimeParent = [IO.Path]::GetFullPath(
    (Join-Path $projectRoot 'build\validation-runtime'))
$runtimePrefix = $runtimeParent.TrimEnd('\', '/') +
    [IO.Path]::DirectorySeparatorChar
if ($sourceWorld.StartsWith(
        $runtimePrefix, [StringComparison]::OrdinalIgnoreCase)) {
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
    if ($relative.StartsWith('/') -or $relative.Contains('../') -or
            [IO.Path]::IsPathRooted($relative)) {
        throw "Unsafe fixture path in manifest: $relative"
    }
    if ($expectedFiles.ContainsKey($relative)) {
        throw "Duplicate fixture path in manifest: $relative"
    }
    $expectedFiles[$relative] = $entry
}

$actualFiles = @{}
foreach ($file in Get-ChildItem -LiteralPath $sourceWorld -Recurse -File -Force) {
    $relative = [IO.Path]::GetRelativePath(
        $sourceWorld, $file.FullName).Replace('\', '/')
    if ($relative -eq 'session.lock') {
        continue
    }
    if ($actualFiles.ContainsKey($relative)) {
        throw "Duplicate pristine-world path: $relative"
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
    throw "Pristine world file set mismatch after runtime; missing=[$($missing -join ', ')], unexpected=[$($unexpected -join ', ')]"
}

$snapshotRows = [Collections.Generic.List[string]]::new()
$totalBytes = 0L
foreach ($relative in @($expectedFiles.Keys | Sort-Object)) {
    $file = $actualFiles[$relative]
    $expected = $expectedFiles[$relative]
    if ([long]$file.Length -ne [long]$expected.bytes) {
        throw "Pristine world size mismatch after runtime for $relative"
    }
    $hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).
        Hash.ToLowerInvariant()
    if ($hash -ne [string]$expected.sha256) {
        throw "Pristine world SHA-256 mismatch after runtime for $relative"
    }
    $totalBytes += [long]$file.Length
    $snapshotRows.Add("$relative|$($file.Length)|$hash")
}
$snapshotBytes = [Text.Encoding]::UTF8.GetBytes(
    ($snapshotRows -join "`n") + "`n")
$snapshotSha256 = [Convert]::ToHexString(
    [Security.Cryptography.SHA256]::HashData($snapshotBytes)).
    ToLowerInvariant()

Write-JsonImmutable $OutputPath ([ordered]@{
    schemaVersion = 1
    sourcePath = $sourceWorld
    sourceReadOnly = $true
    targetConfigPath = $configPath
    targetConfigSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (
        $configPath)).Hash.ToLowerInvariant()
    manifestPath = $manifestPath
    manifestSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (
        $manifestPath)).Hash.ToLowerInvariant()
    fileCount = $expectedFiles.Count
    totalBytes = $totalBytes
    sourceSnapshotSha256 = $snapshotSha256
    sessionLockExcluded = $true
    passed = $true
    checkedAtUtc = [DateTime]::UtcNow.ToString('o')
})
