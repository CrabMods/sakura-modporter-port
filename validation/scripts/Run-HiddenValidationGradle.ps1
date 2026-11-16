Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Lane = $env:EXACT_JAR_VALIDATION_LANE
$TargetConfig = $env:EXACT_JAR_VALIDATION_TARGET_CONFIG
$HarnessReceipt = $env:EXACT_JAR_VALIDATION_HARNESS_RECEIPT
$BaselineReceipt = $env:EXACT_JAR_VALIDATION_BASELINE_RECEIPT
if ($Lane -notin @('client', 'server')) {
    throw 'EXACT_JAR_VALIDATION_LANE must be client or server'
}
if ([string]::IsNullOrWhiteSpace($TargetConfig)) {
    throw 'EXACT_JAR_VALIDATION_TARGET_CONFIG is required'
}
if ([string]::IsNullOrWhiteSpace($HarnessReceipt)) {
    throw 'EXACT_JAR_VALIDATION_HARNESS_RECEIPT is required'
}
if ([string]::IsNullOrWhiteSpace($BaselineReceipt)) {
    throw 'EXACT_JAR_VALIDATION_BASELINE_RECEIPT is required'
}

$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$gradleRunner = Join-Path $PSScriptRoot 'run_gradle.py'
$hiddenDesktop = Join-Path $PSScriptRoot 'hidden_desktop.ps1'
if (-not (Test-Path -LiteralPath $gradleRunner -PathType Leaf)) {
    throw "Project Gradle runner is missing: $gradleRunner"
}
if (-not (Test-Path -LiteralPath $hiddenDesktop -PathType Leaf)) {
    throw "Hidden desktop launcher is missing: $hiddenDesktop"
}

$runtimeDirectory = Join-Path $projectRoot (
    "build\validation-runtime\$Lane")
$task = if ($Lane -eq 'client') {
    'runValidationClient'
} else {
    'runValidationServer'
}
$python = (Get-Command python -CommandType Application -ErrorAction Stop |
    Select-Object -First 1).Source
$arguments = @(
    $gradleRunner
    'gradle'
    '--'
    $task
    '--no-daemon'
    "-PvalidationTargetConfig=$TargetConfig"
    "-PvalidationHarnessReceipt=$HarnessReceipt"
    "-PvalidationBaselineReceipt=$BaselineReceipt"
    "-PmodporterRuntimeDirectory=$runtimeDirectory"
)

& $hiddenDesktop `
    -Executable $python `
    -ArgumentList $arguments `
    -DesktopName "exact-jar-validation-$Lane-$PID"
exit $LASTEXITCODE



