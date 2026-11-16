[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('client', 'server')]
    [string]$Lane,

    [Parameter(Mandatory = $true)]
    [string]$LogPath,

    [Parameter(Mandatory = $true)]
    [string]$HarnessReceiptPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-JsonAtomic([string]$PathValue, [object]$Value) {
    if (Test-Path -LiteralPath $PathValue) {
        throw "Immutable log-audit receipt already exists: $PathValue"
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

function Test-KnownCondition([string]$Line, [object]$Condition) {
    $value = [string]$Condition.value
    switch ([string]$Condition.matchKind) {
        'suffix' {
            return $Line.EndsWith(
                $value, [StringComparison]::Ordinal)
        }
        'regex' {
            return [Text.RegularExpressions.Regex]::IsMatch(
                $Line,
                $value,
                [Text.RegularExpressions.RegexOptions]::CultureInvariant
            )
        }
        default {
            throw "Unsupported known-condition matchKind '$($Condition.matchKind)' for '$($Condition.id)'"
        }
    }
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$conditionsPath = Join-Path $projectRoot 'config\known-log-conditions.json'
if (Test-Path -LiteralPath $OutputPath) {
    throw "Immutable log-audit receipt already exists: $OutputPath"
}
if (-not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
    throw "Runtime log is missing: $LogPath"
}

$strictUtf8 = [Text.UTF8Encoding]::new($false, $true)
try {
    $text = $strictUtf8.GetString([IO.File]::ReadAllBytes($LogPath))
} catch {
    throw "Runtime log is not strict UTF-8: $($_.Exception.Message)"
}
$lines = $text -split '\r?\n'
$policy = Get-Content -Raw -LiteralPath $conditionsPath |
    ConvertFrom-Json
if ([int]$policy.schemaVersion -ne 2) {
    throw "Unsupported known-log-conditions schema: $($policy.schemaVersion)"
}
if ([string]$policy.context.minecraftVersion -ne '1.21.1' -or
        [string]$policy.context.neoForgeVersion -ne '21.1.230') {
    throw 'Known log conditions are not pinned to Minecraft 1.21.1 and NeoForge 21.1.230'
}
$conditions = @($policy.conditions)
$conditionIds = @{}
foreach ($condition in $conditions) {
    $id = [string]$condition.id
    if ([string]::IsNullOrWhiteSpace($id) -or
            $conditionIds.ContainsKey($id)) {
        throw "Blank or duplicate known log condition id: '$id'"
    }
    $conditionIds[$id] = $true
    if ([string]$condition.matchKind -notin @('suffix', 'regex') -or
            [string]::IsNullOrWhiteSpace([string]$condition.value) -or
            [string]::IsNullOrWhiteSpace(
                [string]$condition.provenance)) {
        throw "Incomplete known log condition: '$id'"
    }
    foreach ($conditionLane in @($condition.lanes)) {
        if ([string]$conditionLane -notin @('client', 'server')) {
            throw "Invalid lane '$conditionLane' in known log condition '$id'"
        }
    }
}

$fatalPatterns = @(
    '\[[^\]]*/(?:ERROR|FATAL)\]',
    '^\s*(?:ERROR|FATAL)\s',
    '---- Minecraft Crash Report ----',
    'Crash report saved',
    'Failed to start (?:the )?Minecraft server',
    'ModLoadingException',
    'Mod loading has failed',
    'has failed to load correctly',
    'encountered an error while dispatching',
    'Exception caught during firing event',
    '\[ExactJarValidation\] FAIL'
)
$warningPatterns = @(
    '\[[^\]]*/WARN\]',
    '^WARN\s'
)

$findings = [Collections.Generic.List[object]]::new()
$allowed = [Collections.Generic.List[object]]::new()
for ($index = 0; $index -lt $lines.Count; $index++) {
    $line = $lines[$index]
    $isFatal = @($fatalPatterns | Where-Object { $line -match $_ }).Count -gt 0
    $isWarning = @($warningPatterns | Where-Object { $line -match $_ }).Count -gt 0
    if (-not $isFatal -and -not $isWarning) {
        continue
    }

    $matchingCondition = $conditions | Where-Object {
        @($_.lanes) -contains $Lane -and
        (Test-KnownCondition $line $_)
    } | Select-Object -First 1
    if ($null -ne $matchingCondition -and -not $isFatal) {
        $allowed.Add([ordered]@{
            line = $index + 1
            conditionId = [string]$matchingCondition.id
            text = $line.Trim()
        })
    } else {
        $findings.Add([ordered]@{
            line = $index + 1
            severity = if ($isFatal) { 'fatal' } else { 'warning' }
            text = $line.Trim()
        })
    }
}

$requiredMarkers = if ($Lane -eq 'client') {
    @(
        '[ExactJarValidation] PASS target-jar-origin'
        '[ExactJarValidation] PASS client-world-ready'
        '[ExactJarValidation] PASS creative-inventory-rendered'
        '[ExactJarValidation] PASS mod-creative-tabs-all-rows-rendered'
        '[ExactJarValidation] client receipt sealed'
    )
} else {
    @(
        '[ExactJarValidation] PASS target-jar-origin'
        '[ExactJarValidation] dedicated server ready tick'
        '[ExactJarValidation] PASS dedicated-server-ready-save-stop'
        '[ExactJarValidation] server receipt sealed'
    )
}
foreach ($marker in $requiredMarkers) {
    if (-not $text.Contains($marker)) {
        $findings.Add([ordered]@{
            line = 0
            severity = 'missing-marker'
            text = $marker
        })
    }
}

if (-not (Test-Path -LiteralPath $HarnessReceiptPath -PathType Leaf)) {
    $findings.Add([ordered]@{
        line = 0
        severity = 'missing-receipt'
        text = $HarnessReceiptPath
    })
} else {
    $receipt = Get-Content -Raw -LiteralPath $HarnessReceiptPath |
        ConvertFrom-Json
    if (-not [bool]$receipt.allScenariosPassed) {
        $findings.Add([ordered]@{
            line = 0
            severity = 'receipt'
            text = 'allScenariosPassed is false'
        })
    }
    if (@($receipt.requestedScenarios).Count -ne
            @($receipt.observedScenarios).Count -or
            (@($receipt.requestedScenarios) -join "`n") -ne
            (@($receipt.observedScenarios) -join "`n")) {
        $findings.Add([ordered]@{
            line = 0
            severity = 'receipt'
            text = 'requestedScenarios and observedScenarios differ'
        })
    }
}

$audit = [ordered]@{
    schemaVersion = 1
    lane = $Lane
    policyContext = [ordered]@{
        minecraftVersion = [string]$policy.context.minecraftVersion
        neoForgeVersion = [string]$policy.context.neoForgeVersion
        evidenceRun = [string]$policy.context.evidenceRun
    }
    policySha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (
        $conditionsPath)).Hash.ToLowerInvariant()
    logPath = [IO.Path]::GetFullPath($LogPath)
    logSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $LogPath).
        Hash.ToLowerInvariant()
    harnessReceiptPath = [IO.Path]::GetFullPath($HarnessReceiptPath)
    harnessReceiptSha256 = if (Test-Path -LiteralPath (
            $HarnessReceiptPath) -PathType Leaf) {
        (Get-FileHash -Algorithm SHA256 -LiteralPath $HarnessReceiptPath).
            Hash.ToLowerInvariant()
    } else {
        $null
    }
    findingCount = $findings.Count
    allowedConditionCount = $allowed.Count
    findings = @($findings)
    allowedConditions = @($allowed)
    passed = $findings.Count -eq 0
    auditedAtUtc = [DateTime]::UtcNow.ToString('o')
}
Write-JsonAtomic $OutputPath $audit

if ($findings.Count -gt 0) {
    throw "Strict runtime log audit found $($findings.Count) issue(s)"
}

