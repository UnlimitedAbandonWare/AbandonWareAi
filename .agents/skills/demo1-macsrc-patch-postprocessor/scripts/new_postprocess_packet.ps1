[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{2,80}$')][string]$RunId,
    [Parameter(Mandatory = $true)][string]$IntentFile,
    [Parameter(Mandatory = $true)][string]$SessionFile,
    [Parameter(Mandatory = $true)][string]$OutcomeFile,
    [Parameter(Mandatory = $true)][string]$VerificationFile,
    [Parameter(Mandatory = $true)][string]$BeforeIntegrityFile,
    [Parameter(Mandatory = $true)][string]$AfterIntegrityFile,
    [Parameter(Mandatory = $true)][string]$TriQueryDecisionFile,
    [string]$SupabaseEvidenceFile,
    [string]$AutograderEvidenceFile,
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$SecretPattern = [regex]'(?:sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,})'
$goalScoreContract = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..\scripts\awx_goal_score_contract.ps1'))
. $goalScoreContract

function Throw-Classified {
    param([string]$Class, [string]$Detail)
    throw "$Class`: $Detail"
}
function Get-Sha256Lower {
    param([string]$Path)
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-TextSha256Lower {
    param([string]$Value)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
}
function Get-CanonicalRoot {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { Throw-Classified 'root-not-found' $Path }
    $resolved = Resolve-Path -LiteralPath $Path
    $providerPath = [string]$resolved.ProviderPath
    if ([string]::IsNullOrWhiteSpace($providerPath)) { $providerPath = [string]$resolved.Path }
    if ($providerPath -match '^([A-Za-z]):\\') {
        $drive = Get-PSDrive -Name $Matches[1] -ErrorAction SilentlyContinue
        if ($drive -and -not [string]::IsNullOrWhiteSpace([string]$drive.DisplayRoot)) {
            $driveRoot = [IO.Path]::GetPathRoot($providerPath)
            $suffix = $providerPath.Substring($driveRoot.Length)
            $providerPath = if ([string]::IsNullOrWhiteSpace($suffix)) { [string]$drive.DisplayRoot } else { Join-Path ([string]$drive.DisplayRoot) $suffix }
        }
    }
    $providerPath.TrimEnd('\', '/')
}
function Resolve-RootFile {
    param([string]$CanonicalRoot, [string]$RelativePath, [bool]$MustExist)
    if ([string]::IsNullOrWhiteSpace($RelativePath) -or [IO.Path]::IsPathRooted($RelativePath)) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    $candidate = [IO.Path]::GetFullPath((Join-Path $CanonicalRoot $RelativePath))
    $prefix = $CanonicalRoot + [IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        Throw-Classified 'path-outside-root' $RelativePath
    }
    if ($MustExist -and -not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        Throw-Classified 'evidence-file-missing' $RelativePath
    }
    $cursor = if (Test-Path -LiteralPath $candidate) { $candidate } else { [IO.Path]::GetDirectoryName($candidate) }
    while ($cursor.StartsWith($CanonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
                Throw-Classified 'reparse-path-rejected' $RelativePath
            }
        }
        if ($cursor -eq $CanonicalRoot) { break }
        $cursor = [IO.Directory]::GetParent($cursor).FullName
    }
    [pscustomobject]@{
        FullPath = $candidate
        Relative = $candidate.Substring($prefix.Length).Replace('\', '/')
    }
}
function Read-Json {
    param([string]$Path, [string]$Class)
    try { Get-Content -LiteralPath $Path -Encoding UTF8 -Raw | ConvertFrom-Json }
    catch { Throw-Classified $Class 'invalid-json' }
}
function Test-Fields {
    param([object]$Value, [string[]]$Fields)
    if ($null -eq $Value) { return $false }
    $names = @($Value.PSObject.Properties.Name)
    foreach ($field in $Fields) { if ($names -notcontains $field) { return $false } }
    return $true
}
function Test-ReadyPublication {
    param([string]$Path, [string]$ExpectedSha)
    try {
        $sidecarPath = $Path + '.sha256'
        $readyPath = $Path + '.ready'
        if (-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf) -or
            -not (Test-Path -LiteralPath $readyPath -PathType Leaf)) { return $false }
        $sidecar = (Get-Content -LiteralPath $sidecarPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
        $ready = (Get-Content -LiteralPath $readyPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant()
        return $ExpectedSha -eq $sidecar -and $ExpectedSha -eq $ready
    } catch { return $false }
}
function Normalize-Relative {
    param([string]$Value)
    $Value.Replace('\', '/').TrimStart('./')
}
function Add-Failure {
    param([Collections.Generic.List[string]]$List, [string]$Class)
    if (-not $List.Contains($Class)) { $List.Add($Class) }
}
function Get-Delta {
    param([object]$Before, [object]$After)
    if ($null -eq $Before -or $null -eq $After) { return $null }
    [Math]::Round(([double]$After - [double]$Before), 10)
}
function Test-HashMapMatches {
    param([object]$Candidate, [System.Collections.IDictionary]$Expected)
    if ($null -eq $Candidate) { return $false }
    $candidateNames = @($Candidate.PSObject.Properties.Name)
    if ($candidateNames.Count -ne $Expected.Count) { return $false }
    foreach ($key in $Expected.Keys) {
        if ($candidateNames -notcontains [string]$key) { return $false }
        if ([string]$Candidate.$key -ne [string]$Expected[$key]) { return $false }
    }
    return $true
}
function Test-GoalScoreEvidenceBinding {
    param([object]$Candidate, [System.Collections.IDictionary]$ExpectedInputs)
    $componentNames = @(
        'evidenceStrength', 'causalStrength', 'verificationFeasibility',
        'userValue', 'reversibility', 'costEfficiency', 'timeFit',
        'blastRadius', 'ambiguity', 'authorityOrSafetyExpansion'
    )
    if ($null -eq $Candidate) { return $false }
    $candidateNames = @($Candidate.PSObject.Properties.Name)
    if ($candidateNames.Count -ne $componentNames.Count -or
        @($componentNames | Where-Object { $candidateNames -notcontains $_ }).Count -ne 0) {
        return $false
    }
    $allowedIds = @($ExpectedInputs.Keys | ForEach-Object { [string]$_ })
    foreach ($name in $componentNames) {
        $ids = @($Candidate.$name)
        if ($ids.Count -eq 0) { return $false }
        foreach ($id in $ids) {
            if ([string]::IsNullOrWhiteSpace([string]$id) -or $allowedIds -notcontains [string]$id) { return $false }
        }
    }
    return $true
}
function Test-GoalScoreEvidenceMapsEqual {
    param([object]$Left, [object]$Right)
    $componentNames = @(
        'evidenceStrength', 'causalStrength', 'verificationFeasibility',
        'userValue', 'reversibility', 'costEfficiency', 'timeFit',
        'blastRadius', 'ambiguity', 'authorityOrSafetyExpansion'
    )
    foreach ($name in $componentNames) {
        $leftIds = @($Left.$name | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        $rightIds = @($Right.$name | ForEach-Object { [string]$_ } | Sort-Object -Unique)
        if (($leftIds -join '|') -cne ($rightIds -join '|')) { return $false }
    }
    return $true
}
function Publish-Record {
    param([System.Collections.IDictionary]$Record, [string]$Path)
    $shaPath = $Path + '.sha256'
    $readyPath = $Path + '.ready'
    if ((Test-Path -LiteralPath $Path) -or (Test-Path -LiteralPath $shaPath) -or (Test-Path -LiteralPath $readyPath)) {
        Throw-Classified 'postprocess-already-exists' $Path
    }
    $parent = [IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    $token = [guid]::NewGuid().ToString('N')
    $temps = @(
        (Join-Path $parent ".post-$token.tmp"),
        (Join-Path $parent ".post-sha-$token.tmp"),
        (Join-Path $parent ".post-ready-$token.tmp")
    )
    try {
        $utf8 = New-Object Text.UTF8Encoding($false)
        [IO.File]::WriteAllText($temps[0], (($Record | ConvertTo-Json -Depth 14) + [Environment]::NewLine), $utf8)
        $sha = Get-Sha256Lower $temps[0]
        [IO.File]::WriteAllText($temps[1], $sha + [Environment]::NewLine, $utf8)
        [IO.File]::WriteAllText($temps[2], $sha + [Environment]::NewLine, $utf8)
        Move-Item -LiteralPath $temps[0] -Destination $Path
        Move-Item -LiteralPath $temps[1] -Destination $shaPath
        Move-Item -LiteralPath $temps[2] -Destination $readyPath
        return $sha
    } finally {
        foreach ($temp in $temps) { if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Force } }
    }
}

$canonicalRoot = Get-CanonicalRoot $Root
$members = [ordered]@{
    intent = Resolve-RootFile $canonicalRoot $IntentFile $true
    session = Resolve-RootFile $canonicalRoot $SessionFile $true
    outcome = Resolve-RootFile $canonicalRoot $OutcomeFile $true
    verification = Resolve-RootFile $canonicalRoot $VerificationFile $true
    beforeIntegrity = Resolve-RootFile $canonicalRoot $BeforeIntegrityFile $true
    afterIntegrity = Resolve-RootFile $canonicalRoot $AfterIntegrityFile $true
    triQuery = Resolve-RootFile $canonicalRoot $TriQueryDecisionFile $true
}
if (-not [string]::IsNullOrWhiteSpace($SupabaseEvidenceFile)) {
    $members.supabase = Resolve-RootFile $canonicalRoot $SupabaseEvidenceFile $true
}
if (-not [string]::IsNullOrWhiteSpace($AutograderEvidenceFile)) {
    $members.autograder = Resolve-RootFile $canonicalRoot $AutograderEvidenceFile $true
}
$inputHashes = [ordered]@{}
$subjectInputHashes = [ordered]@{}
foreach ($key in $members.Keys) {
    $hash = Get-Sha256Lower $members[$key].FullPath
    $inputHashes[$key] = $hash
    if ($key -ne 'triQuery') { $subjectInputHashes[$key] = $hash }
}

$intentSha = Get-Sha256Lower $members.intent.FullPath
foreach ($suffix in @('.sha256', '.ready')) {
    $path = $members.intent.FullPath + $suffix
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
        (Get-Content -LiteralPath $path -Encoding UTF8 -Raw).Trim().ToLowerInvariant() -ne $intentSha) {
        Throw-Classified 'intent-checksum-mismatch' $members.intent.Relative
    }
}

$intent = Read-Json $members.intent.FullPath 'intent-invalid'
$session = Read-Json $members.session.FullPath 'session-invalid'
$outcome = Read-Json $members.outcome.FullPath 'terminal-outcome-invalid'
$verification = Read-Json $members.verification.FullPath 'verification-evidence-invalid'
$before = Read-Json $members.beforeIntegrity.FullPath 'before-integrity-invalid'
$after = Read-Json $members.afterIntegrity.FullPath 'after-integrity-invalid'
$tri = Read-Json $members.triQuery.FullPath 'tri-query-invalid'
$sessionSha = Get-Sha256Lower $members.session.FullPath
$verificationSha = Get-Sha256Lower $members.verification.FullPath
$failures = New-Object Collections.Generic.List[string]

if ($intent.schemaVersion -ne 'awx.patch-intent.v1' -or $intent.runId -ne $RunId -or $intent.mutationAllowed -ne $false) {
    Throw-Classified 'intent-invalid' 'schema-run-mutation'
}
if ($session.schemaVersion -ne 'awx.macsrc_smb_patch_session.v2' -or $session.runId -ne $RunId) {
    Throw-Classified 'session-invalid' 'schema-run'
}
if (-not ([string]$intent.sourceRoot).Equals($canonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
    Add-Failure $failures 'intent-root-mismatch'
}
if (-not ([string]$session.canonicalRoot).Equals($canonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
    Add-Failure $failures 'session-root-mismatch'
}
if ([string]$session.sourceWriteMode -ne 'MACSRC_SMB_DIRECT') {
    Add-Failure $failures 'session-source-write-mode-invalid'
}

$outcomeState = 'UNKNOWN'
$rollbackRequired = $false
if ($outcome.schemaVersion -eq 'awx.macsrc_smb_patch_completion.v2' -and $outcome.state -eq 'COMPLETE') {
    $outcomeState = 'COMPLETE'
    if ($outcome.runId -ne $RunId -or $outcome.sessionSha256 -ne $sessionSha) { Add-Failure $failures 'completion-session-mismatch' }
    if ([string]$outcome.verificationEvidenceSha256 -ne $verificationSha) { Add-Failure $failures 'verification-evidence-hash-mismatch' }
    if ([int]$outcome.undeclaredSourceChangeCount -ne 0) { Add-Failure $failures 'undeclared-source-change' }
    if ([int]$outcome.rawSecretPatternHits -ne 0) { Add-Failure $failures 'secret-leak-risk' }
} elseif ($outcome.schemaVersion -eq 'awx.macsrc_smb_patch_abort.v2' -and $outcome.state -eq 'ABORTED') {
    $outcomeState = 'ABORTED'
    $rollbackRequired = [bool]$outcome.rollbackRequired
    if ($outcome.runId -ne $RunId -or $outcome.sessionSha256 -ne $sessionSha) { Add-Failure $failures 'abort-session-mismatch' }
} else {
    Add-Failure $failures 'terminal-outcome-invalid'
}

if ($verification.schemaVersion -ne 'awx.macsrc_smb_patch_verification.v1' -or
    $verification.runId -ne $RunId -or $verification.sessionSha256 -ne $sessionSha) {
    Add-Failure $failures 'verification-evidence-invalid'
}
if ($verification.command -ne $intent.green.command -or [int]$verification.exitCode -ne 0) {
    Add-Failure $failures 'green-failed'
}
$verificationMap = @{}
foreach ($row in @($verification.targetPostimages)) {
    $verificationMap[(Normalize-Relative ([string]$row.relativePath)).ToLowerInvariant()] = ([string]$row.sha256).ToLowerInvariant()
}
$completionMap = @{}
foreach ($row in @($outcome.postimages)) {
    $completionMap[(Normalize-Relative ([string]$row.relativePath)).ToLowerInvariant()] = ([string]$row.postimageSha256).ToLowerInvariant()
}
$preimageMap = @{}
foreach ($row in @($intent.targetPreimages)) {
    $preimageMap[(Normalize-Relative ([string]$row.path)).ToLowerInvariant()] = ([string]$row.sha256).ToLowerInvariant()
}
$actualChangedCount = 0
foreach ($target in @($intent.targetFiles)) {
    $normalized = (Normalize-Relative ([string]$target)).ToLowerInvariant()
    $targetMember = Resolve-RootFile $canonicalRoot ([string]$target) $true
    $currentSha = Get-Sha256Lower $targetMember.FullPath
    if (-not $preimageMap.ContainsKey($normalized) -or $preimageMap[$normalized] -ne $currentSha) {
        $actualChangedCount++
    }
    if (-not $verificationMap.ContainsKey($normalized) -or $verificationMap[$normalized] -ne $currentSha) {
        Add-Failure $failures 'verification-target-hash-mismatch'
    }
    if ($outcomeState -eq 'COMPLETE' -and (-not $completionMap.ContainsKey($normalized) -or $completionMap[$normalized] -ne $currentSha)) {
        Add-Failure $failures 'completion-target-hash-mismatch'
    }
}
if ($outcomeState -eq 'COMPLETE') {
    if ($actualChangedCount -eq 0 -or [int]$outcome.changedFileCount -eq 0) { Add-Failure $failures 'no-source-change' }
    if ([int]$outcome.changedFileCount -ne $actualChangedCount) { Add-Failure $failures 'changed-file-count-mismatch' }
}

if ($before.schemaVersion -ne 'awx.memory_integrity.feedback.v1' -or
    $after.schemaVersion -ne 'awx.memory_integrity.feedback.v1' -or
    $before.mutationAllowed -ne $false -or $after.mutationAllowed -ne $false) {
    Add-Failure $failures 'integrity-evidence-invalid'
}
if (-not ([string]$before.root).Equals($canonicalRoot, [StringComparison]::OrdinalIgnoreCase) -or
    -not ([string]$after.root).Equals($canonicalRoot, [StringComparison]::OrdinalIgnoreCase)) {
    Add-Failure $failures 'integrity-root-mismatch'
}
if ([string]$before.checksumProbe.seed -ne [string]$after.checksumProbe.seed) {
    Add-Failure $failures 'paired-seed-mismatch'
}
if ([int]$before.checksumProbe.populationCount -ne [int]$after.checksumProbe.populationCount -or
    [int]$before.checksumProbe.sampleCount -ne [int]$after.checksumProbe.sampleCount) {
    Add-Failure $failures 'paired-population-mismatch'
}
$metricDeltas = [ordered]@{
    checksumMismatchRate = Get-Delta $before.observedMetrics.checksumMismatchRate $after.observedMetrics.checksumMismatchRate
    contextContaminationRate = Get-Delta $before.observedMetrics.contextContaminationRate $after.observedMetrics.contextContaminationRate
    desktopErrorRate = Get-Delta $before.observedMetrics.desktopErrorRate $after.observedMetrics.desktopErrorRate
    pairedIntegrityContribution = Get-Delta $before.observedMetrics.pairedIntegrityContribution $after.observedMetrics.pairedIntegrityContribution
}

$hashPattern = '^[a-fA-F0-9]{64}$'
$preparedManifestSha = $null
$preparedRequestHashes = [ordered]@{}
$preparedTriValid = $true
try {
    $preparedRelativeRoot = "data/agent-handoff/macsrc-patch-postprocess/$RunId/tri-query"
    $preparedManifestRelative = "$preparedRelativeRoot/subject.manifest.json"
    $preparedManifestMember = Resolve-RootFile $canonicalRoot $preparedManifestRelative $true
    $preparedManifestSha = Get-Sha256Lower $preparedManifestMember.FullPath
    foreach ($suffix in @('.sha256', '.ready')) {
        $markerPath = $preparedManifestMember.FullPath + $suffix
        if (-not (Test-Path -LiteralPath $markerPath -PathType Leaf) -or
            (Get-Content -LiteralPath $markerPath -Encoding UTF8 -Raw).Trim().ToLowerInvariant() -ne $preparedManifestSha) {
            throw 'prepared-manifest-marker-invalid'
        }
    }
    $preparedManifest = Read-Json $preparedManifestMember.FullPath 'tri-query-invalid'
    if ($preparedManifest.schemaVersion -ne 'awx.patch-tri-query.subject.v1' -or
        $preparedManifest.subjectRunId -ne $RunId -or
        $preparedManifest.mutationAllowed -ne $false -or
        -not (Test-HashMapMatches $preparedManifest.inputHashes $subjectInputHashes)) {
        throw 'prepared-manifest-invalid'
    }

    $requestDefinitions = [ordered]@{
        positive = [ordered]@{ file = 'positive.request.json'; packetType = 'POSITIVE_QUERY' }
        negative = [ordered]@{ file = 'negative.request.json'; packetType = 'NEGATIVE_QUERY' }
        neutral = [ordered]@{ file = 'neutral.request.json'; packetType = 'NEUTRAL_QUERY' }
    }
    foreach ($role in $requestDefinitions.Keys) {
        $definition = $requestDefinitions[$role]
        $requestMember = Resolve-RootFile $canonicalRoot "$preparedRelativeRoot/$($definition.file)" $true
        $requestSha = Get-Sha256Lower $requestMember.FullPath
        $manifestRequestSha = [string]$preparedManifest.requestHashes.PSObject.Properties[$role].Value
        $request = Read-Json $requestMember.FullPath 'tri-query-invalid'
        if ($requestSha -ne $manifestRequestSha -or
            $request.schemaVersion -ne 'awx.patch-tri-query.request.v1' -or
            $request.packetType -ne $definition.packetType -or
            $request.subjectRunId -ne $RunId -or
            $request.mutationAllowed -ne $false -or
            ([string]$request.subjectManifestFile).Replace('\', '/') -cne $preparedManifestRelative -or
            -not (Test-HashMapMatches $request.subjectInputHashes $subjectInputHashes)) {
            throw "prepared-$role-request-invalid"
        }
        $preparedRequestHashes[$role] = $requestSha
    }
    $dispatchMember = Resolve-RootFile $canonicalRoot "$preparedRelativeRoot/dispatch-plan.json" $true
    if ((Get-Sha256Lower $dispatchMember.FullPath) -ne [string]$preparedManifest.requestHashes.dispatch) {
        throw 'prepared-dispatch-invalid'
    }
} catch {
    $preparedTriValid = $false
    Add-Failure $failures 'tri-query-invalid'
}

$triScoreResult = Measure-AwxGoalScore -Components $tri.goalScoreComponents -ProvidedScore $tri.goalScore
$triScore = if ($null -eq $triScoreResult.computedScore) { 0.0 } else { [double]$triScoreResult.computedScore }
$triScoreValid = $null -ne $triScoreResult.computedScore
if (-not $triScoreResult.valid) { Add-Failure $failures ([string]$triScoreResult.failureClassification) }
$triScoreEvidenceValid = Test-GoalScoreEvidenceBinding $tri.goalScoreEvidenceIds $subjectInputHashes
if (-not $triScoreEvidenceValid) { Add-Failure $failures 'goal-score-evidence-unbound' }
$triSubjectManifestSha = if (Test-Fields $tri @('subjectManifestSha256')) {
    [string]$tri.subjectManifestSha256
} else {
    ''
}
if ($tri.schemaVersion -ne 'awx.patch-tri-query.final.v1' -or $tri.subjectRunId -ne $RunId -or
    -not $preparedTriValid -or
    $triSubjectManifestSha -notmatch $hashPattern -or
    $triSubjectManifestSha -ne [string]$preparedManifestSha -or
    [string]$tri.positivePacketSha256 -notmatch $hashPattern -or
    [string]$tri.negativePacketSha256 -notmatch $hashPattern -or
    [string]$tri.neutralPacketSha256 -notmatch $hashPattern -or
    (@($tri.forwardOrder) -join '>') -ne 'POSITIVE_QUERY>NEGATIVE_QUERY' -or
    $tri.reverseOrderChecked -ne $true -or $tri.mutationAllowed -ne $false -or
    [int]$tri.secretPatternHits -ne 0 -or $tri.verdict -ne 'APPLY' -or
    -not $triScoreValid -or $triScore -lt 50.0 -or $triScore -gt 100.0 -or
    -not (Test-HashMapMatches $tri.subjectInputHashes $subjectInputHashes)) {
    Add-Failure $failures 'tri-query-invalid'
}
if ($tri.orderStable -ne $true) { Add-Failure $failures 'postprocess-order-unstable' }

$triPacketMembers = [ordered]@{}
try {
    $triPacketMembers.positive = Resolve-RootFile $canonicalRoot ([string]$tri.positivePacketFile) $true
    $triPacketMembers.negative = Resolve-RootFile $canonicalRoot ([string]$tri.negativePacketFile) $true
    $triPacketMembers.neutral = Resolve-RootFile $canonicalRoot ([string]$tri.neutralPacketFile) $true
    $triPacketRoot = [IO.Path]::GetFullPath((Join-Path $canonicalRoot "data\agent-handoff\macsrc-patch-postprocess\$RunId\tri-query")).TrimEnd('\', '/')
    $triPacketPrefix = $triPacketRoot + [IO.Path]::DirectorySeparatorChar
    foreach ($member in $triPacketMembers.Values) {
        if (-not $member.FullPath.StartsWith($triPacketPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            (Get-Item -LiteralPath $member.FullPath).Length -gt 262144) {
            throw 'tri-packet-boundary-invalid'
        }
    }
    $positivePacket = Read-Json $triPacketMembers.positive.FullPath 'tri-query-invalid'
    $negativePacket = Read-Json $triPacketMembers.negative.FullPath 'tri-query-invalid'
    $neutralPacket = Read-Json $triPacketMembers.neutral.FullPath 'tri-query-invalid'
    $positiveSha = Get-Sha256Lower $triPacketMembers.positive.FullPath
    $negativeSha = Get-Sha256Lower $triPacketMembers.negative.FullPath
    $neutralSha = Get-Sha256Lower $triPacketMembers.neutral.FullPath
    $positiveRequestBinding = if (Test-Fields $positivePacket @('requestSha256')) { [string]$positivePacket.requestSha256 } else { '' }
    $negativeRequestBinding = if (Test-Fields $negativePacket @('requestSha256')) { [string]$negativePacket.requestSha256 } else { '' }
    $neutralRequestBinding = if (Test-Fields $neutralPacket @('requestSha256')) { [string]$neutralPacket.requestSha256 } else { '' }
    $neutralScoreResult = Measure-AwxGoalScore -Components $neutralPacket.goalScoreComponents -ProvidedScore $neutralPacket.goalScore
    if (-not $neutralScoreResult.valid) { Add-Failure $failures ([string]$neutralScoreResult.failureClassification) }
    $neutralScoreEvidenceValid = Test-GoalScoreEvidenceBinding $neutralPacket.goalScoreEvidenceIds $subjectInputHashes
    if (-not $neutralScoreEvidenceValid -or
        ($triScoreEvidenceValid -and -not (Test-GoalScoreEvidenceMapsEqual $tri.goalScoreEvidenceIds $neutralPacket.goalScoreEvidenceIds))) {
        Add-Failure $failures 'goal-score-evidence-unbound'
    }
    if ($positiveSha -ne [string]$tri.positivePacketSha256 -or
        $negativeSha -ne [string]$tri.negativePacketSha256 -or
        $neutralSha -ne [string]$tri.neutralPacketSha256 -or
        $positivePacket.schemaVersion -ne 'awx.patch-tri-query.positive.v1' -or
        $positivePacket.packetType -ne 'POSITIVE_QUERY' -or
        $positivePacket.subjectRunId -ne $RunId -or $positivePacket.mutationAllowed -ne $false -or
        $positiveRequestBinding -ne [string]$preparedRequestHashes.positive -or
        $negativePacket.schemaVersion -ne 'awx.patch-tri-query.negative.v1' -or
        $negativePacket.packetType -ne 'NEGATIVE_QUERY' -or
        $negativePacket.subjectRunId -ne $RunId -or $negativePacket.mutationAllowed -ne $false -or
        $negativeRequestBinding -ne [string]$preparedRequestHashes.negative -or
        $neutralPacket.schemaVersion -ne 'awx.patch-tri-query.neutral.v1' -or
        $neutralPacket.packetType -ne 'NEUTRAL_QUERY' -or
        $neutralPacket.subjectRunId -ne $RunId -or $neutralPacket.mutationAllowed -ne $false -or
        $neutralRequestBinding -ne [string]$preparedRequestHashes.neutral -or
        -not (Test-HashMapMatches $positivePacket.subjectInputHashes $subjectInputHashes) -or
        -not (Test-HashMapMatches $negativePacket.subjectInputHashes $subjectInputHashes) -or
        -not (Test-HashMapMatches $neutralPacket.subjectInputHashes $subjectInputHashes) -or
        [string]$neutralPacket.positivePacketSha256 -ne $positiveSha -or
        [string]$neutralPacket.negativePacketSha256 -ne $negativeSha -or
        $neutralPacket.orderABVerdict -ne 'APPLY' -or $neutralPacket.orderBAVerdict -ne 'APPLY' -or
        $neutralPacket.verdict -ne 'APPLY' -or $neutralPacket.orderStable -ne $true -or
        $null -eq $neutralScoreResult.computedScore -or
        [double]$neutralScoreResult.computedScore -ne $triScore -or
        [int]$neutralPacket.secretPatternHits -ne 0) {
        Add-Failure $failures 'tri-query-invalid'
    }
} catch {
    Add-Failure $failures 'tri-query-invalid'
}

$autograderPassRatio = $null
$autograderDelta = $null
$adjustedGoalScore = $triScore
$dynamicGrade = [ordered]@{
    mode = 'NOT_PROVIDED'
    evidenceSha256 = $null
    jobSha256 = $null
    recomputedPassed = $null
    recomputedPassRatio = $null
    delta = $null
    orderApplied = 'NONE'
    failureClasses = @()
}
if ($members.Contains('autograder')) {
    $gradeFailures = New-Object Collections.Generic.List[string]
    $gradeMember = $members.autograder
    $gradeSha = Get-Sha256Lower $gradeMember.FullPath
    $dynamicGrade.mode = 'INVALID'
    $dynamicGrade.evidenceSha256 = $gradeSha
    $expectedGradeDirectory = "data/agent-handoff/docker-autograder/$RunId"
    $actualGradeDirectory = ([IO.Path]::GetDirectoryName($gradeMember.Relative)).Replace('\', '/')
    if ($actualGradeDirectory -cne $expectedGradeDirectory) {
        Add-Failure $gradeFailures 'autograder-path-invalid'
    }
    if (-not (Test-ReadyPublication $gradeMember.FullPath $gradeSha)) {
        Add-Failure $gradeFailures 'autograder-result-checksum-mismatch'
    }
    $grade = $null
    $gradeText = ''
    try {
        $gradeText = Get-Content -LiteralPath $gradeMember.FullPath -Encoding UTF8 -Raw
        $grade = $gradeText | ConvertFrom-Json
    } catch {
        Add-Failure $gradeFailures 'autograder-result-invalid'
    }
    $requiredGradeFields = @(
        'schemaVersion', 'runId', 'purpose', 'jobSha256', 'inputManifestSha256',
        'executionStatus', 'failureClass', 'exitCode', 'timedOut', 'testVerdict',
        'tests', 'expectedSignalMatched', 'stdout', 'stderr', 'junitArchive', 'combinedOutputSha256',
        'isolation', 'secretPatternHits', 'mutationAllowed', 'sourceMutationPerformed'
    )
    if ($null -eq $grade -or -not (Test-Fields $grade $requiredGradeFields)) {
        Add-Failure $gradeFailures 'autograder-result-invalid'
    } else {
        if ($grade.schemaVersion -ne 'awx.docker-autograder.result.v1' -or
            $grade.runId -ne $RunId -or $grade.purpose -ne 'GREEN_VERIFICATION') {
            Add-Failure $gradeFailures 'autograder-result-identity-mismatch'
        }
        if ($grade.executionStatus -ne 'COMPLETE' -or $grade.failureClass -ne 'none' -or
            $grade.timedOut -ne $false -or $null -eq $grade.exitCode) {
            Add-Failure $gradeFailures 'autograder-execution-incomplete'
        }
        if ([string]$grade.jobSha256 -notmatch $hashPattern -or
            [string]$grade.inputManifestSha256 -notmatch $hashPattern -or
            [string]$grade.combinedOutputSha256 -notmatch $hashPattern) {
            Add-Failure $gradeFailures 'autograder-result-hash-invalid'
        }
        $logMetadataValid = $true
        foreach ($logName in @('stdout', 'stderr')) {
            $log = $grade.$logName
            if (-not (Test-Fields $log @('bytes', 'persistedBytes', 'sha256', 'truncated')) -or
                [string]$log.bytes -notmatch '^\d+$' -or [string]$log.persistedBytes -notmatch '^\d+$' -or
                ([string]$log.bytes).Length -gt 18 -or ([string]$log.persistedBytes).Length -gt 18 -or
                [string]$log.sha256 -notmatch $hashPattern -or $log.truncated -isnot [bool]) {
                $logMetadataValid = $false
            }
        }
        if ($logMetadataValid) {
            $expectedCombinedOutputSha = Get-TextSha256Lower (([string]$grade.stdout.sha256).ToLowerInvariant() + ':' + ([string]$grade.stderr.sha256).ToLowerInvariant())
            if ($expectedCombinedOutputSha -ne ([string]$grade.combinedOutputSha256).ToLowerInvariant()) {
                $logMetadataValid = $false
            }
        }
        if (-not $logMetadataValid) { Add-Failure $gradeFailures 'autograder-log-metadata-invalid' }
        $archiveMetadataValid = (
            (Test-Fields $grade.junitArchive @('transport', 'bytes', 'persistedBytes', 'sha256', 'truncated')) -and
            $grade.junitArchive.transport -eq 'container-tmpfs-tar' -and
            [string]$grade.junitArchive.bytes -match '^\d+$' -and
            [string]$grade.junitArchive.persistedBytes -match '^\d+$' -and
            ([string]$grade.junitArchive.bytes).Length -le 18 -and
            ([string]$grade.junitArchive.persistedBytes).Length -le 18 -and
            [string]$grade.junitArchive.sha256 -match $hashPattern -and
            $grade.junitArchive.truncated -eq $false
        )
        if (-not $archiveMetadataValid) { Add-Failure $gradeFailures 'autograder-junit-transport-invalid' }
        $requiredIsolationFields = @(
            'sourceMode', 'inputMountReadOnly', 'rootFilesystemReadOnly', 'networkMode',
            'pullPolicy', 'capDropAll', 'noNewPrivileges', 'nonRootUser', 'resourceLimitsApplied', 'resultMode'
        )
        if (-not (Test-Fields $grade.isolation $requiredIsolationFields) -or
            $grade.isolation.sourceMode -ne 'local-temp-copy' -or
            $grade.isolation.inputMountReadOnly -ne $true -or
            $grade.isolation.rootFilesystemReadOnly -ne $true -or
            $grade.isolation.networkMode -ne 'none' -or $grade.isolation.pullPolicy -ne 'never' -or
            $grade.isolation.capDropAll -ne $true -or $grade.isolation.noNewPrivileges -ne $true -or
            $grade.isolation.nonRootUser -ne $true -or $grade.isolation.resourceLimitsApplied -ne $true -or
            $grade.isolation.resultMode -ne 'container-tmpfs-tar' -or
            $grade.mutationAllowed -ne $false -or $grade.sourceMutationPerformed -ne $false) {
            Add-Failure $gradeFailures 'autograder-isolation-invalid'
        }
        if ([int]$grade.secretPatternHits -ne 0 -or $SecretPattern.Matches($gradeText).Count -gt 0) {
            Add-Failure $gradeFailures 'secret-leak-risk'
        }

        $countsValid = $true
        $countNames = @('total', 'passed', 'failed', 'errored', 'skipped')
        $parsedCounts = @{}
        if (-not (Test-Fields $grade.tests ($countNames + @('passRatio')))) {
            $countsValid = $false
        } else {
            foreach ($countName in $countNames) {
                $parsedCount = [long]0
                if (-not [long]::TryParse(
                        [string]$grade.tests.$countName,
                        [Globalization.NumberStyles]::Integer,
                        [Globalization.CultureInfo]::InvariantCulture,
                        [ref]$parsedCount
                    ) -or $parsedCount -lt 0 -or $parsedCount -gt 1000000) {
                    $countsValid = $false
                } else {
                    $parsedCounts[$countName] = $parsedCount
                }
            }
        }
        if (-not $countsValid) {
            Add-Failure $gradeFailures 'autograder-test-counts-invalid'
        } else {
            $total = $parsedCounts.total
            $failed = $parsedCounts.failed
            $errored = $parsedCounts.errored
            $skipped = $parsedCounts.skipped
            $submittedPassed = $parsedCounts.passed
            $recomputedPassed = $total - $failed - $errored - $skipped
            $dynamicGrade.recomputedPassed = $recomputedPassed
            if ($total -le 0) {
                Add-Failure $gradeFailures 'autograder-zero-tests'
            } elseif ($recomputedPassed -lt 0 -or $submittedPassed -ne $recomputedPassed) {
                Add-Failure $gradeFailures 'autograder-test-counts-invalid'
            } else {
                $recomputedRatio = [Math]::Round(([double]$recomputedPassed / [double]$total), 10)
                $dynamicGrade.recomputedPassRatio = $recomputedRatio
                $submittedRatioValid = $true
                try { $submittedRatio = [double]$grade.tests.passRatio } catch { $submittedRatioValid = $false; $submittedRatio = 0.0 }
                if (-not $submittedRatioValid -or [Math]::Abs($submittedRatio - $recomputedRatio) -gt 0.0000000001) {
                    Add-Failure $gradeFailures 'autograder-pass-ratio-mismatch'
                }
                $expectedTestVerdict = if ([int]$grade.exitCode -eq 0 -and $failed -eq 0 -and $errored -eq 0 -and $skipped -eq 0) { 'PASS' } else { 'FAIL' }
                if ($grade.testVerdict -ne $expectedTestVerdict) {
                    Add-Failure $gradeFailures 'autograder-test-verdict-mismatch'
                }
            }
        }

        $jobRelative = "$expectedGradeDirectory/job.json"
        try {
            $jobMember = Resolve-RootFile $canonicalRoot $jobRelative $true
            $jobSha = Get-Sha256Lower $jobMember.FullPath
            $dynamicGrade.jobSha256 = $jobSha
            if (-not (Test-ReadyPublication $jobMember.FullPath $jobSha) -or $grade.jobSha256 -ne $jobSha) {
                Add-Failure $gradeFailures 'autograder-job-checksum-mismatch'
            }
            $jobText = Get-Content -LiteralPath $jobMember.FullPath -Encoding UTF8 -Raw
            $job = $jobText | ConvertFrom-Json
            $requiredJobFields = @(
                'schemaVersion', 'runId', 'purpose', 'includePaths', 'declaredTestCommand',
                'decisionSha256', 'intentSpecSha256', 'limits', 'networkMode', 'pullPolicy', 'mutationAllowed'
            )
            if (-not (Test-Fields $job $requiredJobFields) -or
                $job.schemaVersion -ne 'awx.docker-autograder.job.v1' -or $job.runId -ne $RunId -or
                $job.purpose -ne 'GREEN_VERIFICATION' -or $job.declaredTestCommand -ne $intent.green.command -or
                $job.decisionSha256 -ne $verificationSha -or $job.intentSpecSha256 -ne $intentSha -or
                $job.networkMode -ne 'none' -or $job.pullPolicy -ne 'never' -or $job.mutationAllowed -ne $false -or
                $SecretPattern.Matches($jobText).Count -gt 0) {
                Add-Failure $gradeFailures 'autograder-job-invalid'
            }
            if (-not (Test-Fields $job.limits @('maxLogBytes')) -or [string]$job.limits.maxLogBytes -notmatch '^\d+$' -or
                ([string]$job.limits.maxLogBytes).Length -gt 18) {
                Add-Failure $gradeFailures 'autograder-job-invalid'
            } elseif ($logMetadataValid) {
                $maxLogBytes = [long]$job.limits.maxLogBytes
                foreach ($logName in @('stdout', 'stderr')) {
                    $log = $grade.$logName
                    $totalLogBytes = [long]$log.bytes
                    $persistedLogBytes = [long]$log.persistedBytes
                    $expectedTruncated = $totalLogBytes -gt $maxLogBytes
                    if ($persistedLogBytes -gt $maxLogBytes -or $persistedLogBytes -gt $totalLogBytes -or
                        [bool]$log.truncated -ne $expectedTruncated) {
                        Add-Failure $gradeFailures 'autograder-log-metadata-invalid'
                    }
                }
            }
            if (-not (Test-Fields $job.limits @('maxXmlBytes', 'maxFiles')) -or
                [string]$job.limits.maxXmlBytes -notmatch '^\d+$' -or [string]$job.limits.maxFiles -notmatch '^\d+$' -or
                ([string]$job.limits.maxXmlBytes).Length -gt 18 -or ([string]$job.limits.maxFiles).Length -gt 18) {
                Add-Failure $gradeFailures 'autograder-job-invalid'
            } elseif ($archiveMetadataValid) {
                $archiveBytes = [long]$grade.junitArchive.bytes
                $archivePersistedBytes = [long]$grade.junitArchive.persistedBytes
                $archiveLimit = [long]$job.limits.maxXmlBytes + [long]$job.limits.maxFiles * 1024 + 10240
                if ($archiveBytes -ne $archivePersistedBytes -or $archiveBytes -gt $archiveLimit) {
                    Add-Failure $gradeFailures 'autograder-junit-transport-invalid'
                }
            }
            $jobIncludes = @($job.includePaths | ForEach-Object { (Normalize-Relative ([string]$_)).ToLowerInvariant() })
            foreach ($target in @($intent.targetFiles)) {
                if ($jobIncludes -notcontains (Normalize-Relative ([string]$target)).ToLowerInvariant()) {
                    Add-Failure $gradeFailures 'autograder-target-not-included'
                }
            }
        } catch {
            Add-Failure $gradeFailures 'autograder-job-invalid'
        }
    }

    if ($gradeFailures.Count -eq 0) {
        $autograderPassRatio = [double]$dynamicGrade.recomputedPassRatio
        $dynamicScore = Measure-AwxDynamicGoalScore -BaseScore $triScore -PassRatio $autograderPassRatio
        if (-not $dynamicScore.valid) {
            Add-Failure $gradeFailures ([string]$dynamicScore.failureClassification)
        } else {
            $autograderDelta = [double]$dynamicScore.delta
            $adjustedGoalScore = [double]$dynamicScore.adjustedScore
            $dynamicGrade.mode = 'APPLIED'
            $dynamicGrade.delta = $autograderDelta
            $dynamicGrade.orderApplied = 'A-B_AND_B-A'
            if ($grade.testVerdict -ne 'PASS') { Add-Failure $failures 'autograder-green-failed' }
        }
    }
    if ($gradeFailures.Count -gt 0) {
        $autograderPassRatio = $null
        $autograderDelta = $null
        $adjustedGoalScore = $triScore
        $dynamicGrade.mode = 'INVALID'
        $dynamicGrade.delta = $null
        $dynamicGrade.orderApplied = 'NONE'
        foreach ($gradeFailure in $gradeFailures) { Add-Failure $failures $gradeFailure }
    }
    $dynamicGrade.failureClasses = @($gradeFailures | Sort-Object -Unique)
}

$supabase = [ordered]@{ mode = 'NOT_APPLICABLE'; authPresent = $false; projectRefPresent = $false; mutationPerformed = $false }
if ($intent.supabase.mode -eq 'REQUIRED_READ_ONLY') {
    $supabase.mode = 'EVIDENCE_NEEDED'
    if (-not $members.Contains('supabase')) {
        Add-Failure $failures 'supabase-auth-missing'
        Add-Failure $failures 'supabase-project-ref-missing'
    } else {
        $supabaseEvidence = Read-Json $members.supabase.FullPath 'supabase-evidence-invalid'
        $supabase.authPresent = [bool]$supabaseEvidence.authPresent
        $supabase.projectRefPresent = [bool]$supabaseEvidence.projectRefPresent
        $supabase.mutationPerformed = [bool]$supabaseEvidence.mutationPerformed
        if ($supabaseEvidence.schemaVersion -ne 'awx.supabase.readonly-evidence.v1') { Add-Failure $failures 'supabase-evidence-invalid' }
        if (-not $supabase.authPresent) { Add-Failure $failures 'supabase-auth-missing' }
        if (-not $supabase.projectRefPresent -or [string]$supabaseEvidence.projectRefHash -notmatch $hashPattern) {
            Add-Failure $failures 'supabase-project-ref-missing'
        }
        if ($supabaseEvidence.readOnly -ne $true -or $supabase.mutationPerformed) { Add-Failure $failures 'supabase-readonly-violation' }
        $allowedGroups = @('database', 'debugging', 'docs')
        foreach ($group in @($supabaseEvidence.featureGroups)) {
            if ([string]$group -notin $allowedGroups) { Add-Failure $failures 'supabase-feature-scope-too-broad' }
        }
        if (@($failures | Where-Object { $_ -like 'supabase-*' }).Count -eq 0) {
            $supabase.mode = 'CALLER_ASSERTED_PROJECT_SCOPED_READ_ONLY'
            $supabase.provenance = 'UNATTESTED_METADATA'
        }
    }
}

$failureClasses = @($failures | Sort-Object -Unique)
$verdict = if ($rollbackRequired) { 'ROLLBACK_REQUIRED' } elseif ($failureClasses.Count -gt 0 -or $outcomeState -ne 'COMPLETE') { 'HOLD' } else { 'COMPLETE' }
$nextAction = if ($verdict -eq 'ROLLBACK_REQUIRED') {
    'RESTORE_WATCH_ROOTS'
} elseif (@($failureClasses | Where-Object { $_ -in @('verification-target-hash-mismatch', 'completion-target-hash-mismatch', 'verification-evidence-invalid', 'verification-evidence-hash-mismatch', 'green-failed') }).Count -gt 0) {
    'REPAIR_VERIFICATION_EVIDENCE'
} elseif (@($failureClasses | Where-Object { $_ -like 'autograder-*' }).Count -gt 0) {
    'RERUN_DOCKER_AUTOGRADER'
} elseif (@($failureClasses | Where-Object { $_ -in @('paired-seed-mismatch', 'paired-population-mismatch') }).Count -gt 0) {
    'RERUN_INTEGRITY_WITH_SAME_SEED'
} elseif (@($failureClasses | Where-Object { $_ -like 'supabase-*' }).Count -gt 0) {
    'COLLECT_SUPABASE_READ_ONLY_EVIDENCE'
} elseif (@($failureClasses | Where-Object { $_ -like '*tri-query*' -or $_ -like 'goal-score-*' -or $_ -eq 'postprocess-order-unstable' }).Count -gt 0) {
    'RERUN_TRI_QUERY'
} elseif ($verdict -eq 'COMPLETE') {
    'STOP'
} else {
    'REPAIR_EVIDENCE'
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = "data/agent-handoff/macsrc-patch-postprocess/$RunId/postprocess.json"
}
$outputMember = Resolve-RootFile $canonicalRoot $OutputPath $false
$handoffRoot = [IO.Path]::GetFullPath((Join-Path $canonicalRoot 'data\agent-handoff\macsrc-patch-postprocess')).TrimEnd('\', '/')
$handoffPrefix = $handoffRoot + [IO.Path]::DirectorySeparatorChar
if (-not $outputMember.FullPath.StartsWith($handoffPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    Throw-Classified 'output-outside-handoff' $outputMember.Relative
}
$record = [ordered]@{
    schemaVersion = 'awx.patch-postprocess.v1'
    runId = $RunId
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    verdict = $verdict
    outcomeState = $outcomeState
    failureClasses = $failureClasses
    inputHashes = $inputHashes
    metricDeltas = $metricDeltas
    reviewRoles = @('POSITIVE_QUERY', 'NEGATIVE_QUERY', 'NEUTRAL_QUERY')
    orderStable = [bool]$tri.orderStable
    goalScoreBase = $triScore
    autograderPassRatio = $autograderPassRatio
    autograderDelta = $autograderDelta
    dynamicGrade = $dynamicGrade
    goalScore = $adjustedGoalScore
    supabase = $supabase
    nextAction = $nextAction
    nextMutationAllowed = $false
    runtimeLineageVerdict = 'HOLD'
    desktopFinalProof = 'evidence_needed'
}
$recordSha = Publish-Record $record $outputMember.FullPath
[pscustomobject]@{
    verdict = $verdict
    outputPath = $outputMember.FullPath
    recordSha256 = $recordSha
    nextAction = $nextAction
    nextMutationAllowed = $false
} | ConvertTo-Json -Compress
