Set-StrictMode -Version Latest

function Get-ManifestRequiredProperty(
    [hashtable]$Values,
    [string]$Name
) {
    if (-not $Values.ContainsKey($Name) -or
            [string]::IsNullOrWhiteSpace([string]$Values[$Name])) {
        throw "Required target property '$Name' is blank"
    }
    return [string]$Values[$Name]
}

function Get-ManifestPortableProperty(
    [hashtable]$Values,
    [string]$Name
) {
    $value = Get-ManifestRequiredProperty $Values $Name
    if ($value.Contains('\')) {
        throw "Target property '$Name' must use forward slashes"
    }
    return $value
}

function Get-ManifestJsonString(
    [Collections.IDictionary]$Object,
    [string]$Name,
    [string]$Context
) {
    if (-not $Object.Contains($Name) -or
            $Object[$Name] -isnot [string] -or
            [string]::IsNullOrWhiteSpace([string]$Object[$Name])) {
        throw "$Context field '$Name' must be a nonblank string"
    }
    return [string]$Object[$Name]
}

function Assert-ManifestChildPath(
    [string]$Candidate,
    [string]$Parent
) {
    $prefix = $Parent.TrimEnd('\', '/') +
        [IO.Path]::DirectorySeparatorChar
    if (-not $Candidate.StartsWith(
            $prefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Expected-content manifest escaped validation project: $Candidate"
    }
}

function Get-ModporterExpectedContentManifestEvidence(
    [hashtable]$Values,
    [string]$ProjectRoot,
    [string]$ExpectedModId
) {
    $configuredPath = Get-ManifestPortableProperty $Values (
        'expectedContentManifestPath')
    if ([IO.Path]::IsPathRooted($configuredPath)) {
        throw 'expectedContentManifestPath must be relative to the validation project'
    }
    $manifestPath = [IO.Path]::GetFullPath((
        Join-Path $ProjectRoot $configuredPath))
    Assert-ManifestChildPath $manifestPath $ProjectRoot
    if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
        throw "Expected-content manifest does not exist: $manifestPath"
    }

    $expectedSha256 = (Get-ManifestRequiredProperty $Values (
        'expectedContentManifestSha256')).ToLowerInvariant()
    if ($expectedSha256 -notmatch '^[0-9a-f]{64}$') {
        throw 'expectedContentManifestSha256 must be exactly 64 lowercase hexadecimal characters'
    }
    $observedSha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath (
        $manifestPath)).Hash.ToLowerInvariant()
    if ($observedSha256 -ne $expectedSha256) {
        throw "Expected-content manifest SHA-256 mismatch: expected $expectedSha256, observed $observedSha256"
    }

    try {
        $manifest = Get-Content -Raw -LiteralPath $manifestPath |
            ConvertFrom-Json -AsHashtable
    } catch {
        throw "Expected-content manifest is not valid JSON: $($_.Exception.Message)"
    }
    if ($manifest -isnot [Collections.IDictionary]) {
        throw 'Expected-content manifest root must be an object'
    }
    $schemaVersion = if ($manifest.Contains('schemaVersion')) {
        $manifest.schemaVersion
    } else {
        $null
    }
    if ((($schemaVersion -isnot [int]) -and
            ($schemaVersion -isnot [long])) -or
            [long]$schemaVersion -ne 1) {
        throw 'Expected-content manifest schemaVersion must be integer 1'
    }
    $manifestModId = Get-ManifestJsonString $manifest 'targetModId' (
        'Expected-content manifest')
    if ($manifestModId -cne $ExpectedModId) {
        throw "Expected-content manifest targetModId mismatch: $manifestModId != $ExpectedModId"
    }
    if (-not $manifest.Contains('source') -or
            $manifest.source -isnot [Collections.IDictionary]) {
        throw 'Expected-content manifest source must be an object'
    }
    $source = $manifest.source
    $repository = Get-ManifestJsonString $source 'repository' (
        'Expected-content source')
    $commit = (Get-ManifestJsonString $source 'commit' (
        'Expected-content source')).ToLowerInvariant()
    if ($commit -notmatch '^[0-9a-f]{40}$') {
        throw 'Expected-content source commit must be 40 hexadecimal characters'
    }
    $derivation = Get-ManifestJsonString $source 'derivation' (
        'Expected-content source')
    if (-not $source.Contains('files')) {
        throw 'Expected-content source files are missing'
    }
    $sourceFiles = @($source.files)
    if ($sourceFiles.Count -eq 0) {
        throw 'Expected-content source files must not be empty'
    }
    $sourcePaths = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    $sourceHashes = @{}
    foreach ($sourceFile in $sourceFiles) {
        if ($sourceFile -isnot [Collections.IDictionary]) {
            throw 'Expected-content source file entry must be an object'
        }
        $sourcePath = Get-ManifestJsonString $sourceFile 'path' (
            'Expected-content source file')
        if ($sourcePath.Contains('\') -or $sourcePath.StartsWith('/') -or
                [IO.Path]::IsPathRooted($sourcePath)) {
            throw "Unsafe expected-content source path: $sourcePath"
        }
        foreach ($segment in $sourcePath.Split('/')) {
            if ([string]::IsNullOrEmpty($segment) -or
                    $segment -eq '.' -or $segment -eq '..') {
                throw "Unsafe expected-content source path: $sourcePath"
            }
        }
        $sourceSha256 = (Get-ManifestJsonString $sourceFile 'sha256' (
            'Expected-content source file')).ToLowerInvariant()
        if ($sourceSha256 -notmatch '^[0-9a-f]{64}$') {
            throw "Invalid expected-content source SHA-256: $sourcePath"
        }
        if (-not $sourcePaths.Add($sourcePath)) {
            throw "Duplicate expected-content source path: $sourcePath"
        }
        $sourceHashes[$sourcePath] = $sourceSha256
    }

    if (-not $manifest.Contains('expectedRegisteredItemIds')) {
        throw 'Expected-content registered item IDs are missing'
    }
    $expectedIds = @($manifest.expectedRegisteredItemIds)
    if ($expectedIds.Count -eq 0) {
        throw 'Expected-content registered item IDs must not be empty'
    }
    $uniqueIds = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    $namespacePattern = '^' + [regex]::Escape($ExpectedModId) +
        ':[a-z0-9/._-]+$'
    foreach ($itemId in $expectedIds) {
        if ($itemId -isnot [string] -or $itemId -cnotmatch $namespacePattern) {
            throw "Invalid expected registered item ID: $itemId"
        }
        if (-not $uniqueIds.Add([string]$itemId)) {
            throw "Duplicate expected registered item ID: $itemId"
        }
    }
    $sortedIds = @($expectedIds | Sort-Object -CaseSensitive)
    for ($index = 0; $index -lt $expectedIds.Count; $index++) {
        if ([string]$expectedIds[$index] -cne [string]$sortedIds[$index]) {
            throw 'Expected registered item IDs must be sorted'
        }
    }

    if (-not $manifest.Contains('iconEvidenceExceptions')) {
        throw 'Expected-content iconEvidenceExceptions are missing'
    }
    $exceptions = @($manifest.iconEvidenceExceptions)
    $exceptionIds = [Collections.Generic.HashSet[string]]::new(
        [StringComparer]::Ordinal)
    foreach ($exception in $exceptions) {
        if ($exception -isnot [Collections.IDictionary]) {
            throw 'Icon-evidence exception must be an object'
        }
        $itemId = Get-ManifestJsonString $exception 'itemId' (
            'Icon-evidence exception')
        $null = Get-ManifestJsonString $exception 'reason' (
            'Icon-evidence exception')
        $sourcePath = Get-ManifestJsonString $exception 'sourcePath' (
            'Icon-evidence exception')
        $sourceSha256 = (Get-ManifestJsonString $exception 'sourceSha256' (
            'Icon-evidence exception')).ToLowerInvariant()
        if (-not $uniqueIds.Contains($itemId)) {
            throw "Icon-evidence exception references unexpected item: $itemId"
        }
        if (-not $exceptionIds.Add($itemId)) {
            throw "Duplicate icon-evidence exception: $itemId"
        }
        if (-not $sourceHashes.ContainsKey($sourcePath) -or
                [string]$sourceHashes[$sourcePath] -cne $sourceSha256) {
            throw "Icon-evidence exception lacks a pinned source hash: $itemId"
        }
    }

    return [ordered]@{
        path = (Resolve-Path -LiteralPath $manifestPath).Path
        sha256 = $observedSha256
        bytes = (Get-Item -LiteralPath $manifestPath).Length
        sourceRepository = $repository
        sourceCommit = $commit
        sourceDerivation = $derivation
        sourceFiles = $sourceFiles
        expectedRegisteredItemCount = $expectedIds.Count
        iconEvidenceExceptionCount = $exceptions.Count
    }
}
