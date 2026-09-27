[CmdletBinding()]
param(
    [string]$Root = '',

    [string]$SnapshotPath = '',

    [string]$ResultsPath = '',

    [string]$AdvisorsPath = '',

    [string]$OutputDir = '',

    [switch]$OrchestratedByGoalNext,

    [switch]$Help
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    @'
[AWX][supabase][apply-collected] usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\supabase_apply_collected_evidence.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\supabase_apply_collected_evidence.ps1 -Root <repo-root> -ResultsPath data\db-gap-report\supabase-query-results.json -AdvisorsPath data\db-gap-report\supabase-advisors.json

Internal orchestration:
  -OrchestratedByGoalNext keeps import and DB-gap checks here, then defers source-health and completion-audit gates to goal_next_auto.
  Omit this switch for standalone/manual verification.

Required external evidence before this can close:
  SUPABASE_PROJECT_REF present
  SUPABASE_ACCESS_TOKEN present, or authenticated Supabase MCP/CLI session
  execute_sql result sets saved to data\db-gap-report\supabase-query-results.json
  get_advisors rows saved to data\db-gap-report\supabase-advisors.json

Safety:
  readOnly=true
  mutationAllowed=false
  do not print token values, Authorization headers, cookies, JDBC URLs, or raw secrets
  missing live DB evidence remains evidence_needed
'@ | Write-Host
    exit 0
}

function Resolve-RepoRoot {
    param([string]$Candidate)
    if (-not [string]::IsNullOrWhiteSpace($Candidate)) {
        return (Resolve-Path -LiteralPath $Candidate).Path
    }
    $scriptRoot = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
        $scriptRoot = Split-Path -Parent $PSCommandPath
    }
    return (Resolve-Path (Join-Path $scriptRoot '..')).Path
}

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$PathText
    )
    if ([System.IO.Path]::IsPathRooted($PathText)) {
        return $PathText
    }
    return (Join-Path $ProjectRoot $PathText)
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $text = $Value | ConvertTo-Json -Depth 100
    Set-Content -LiteralPath $Path -Value $text -Encoding UTF8
}

function Invoke-ToolboxJson {
    param(
        [Parameter(Mandatory = $true)][string]$Tool,
        [Parameter(Mandatory = $true)][hashtable]$Payload,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $toolbox = Join-Path $ProjectRoot 'scripts\awx_mcp_toolbox.ps1'
    if (-not (Test-Path -LiteralPath $toolbox)) {
        throw "evidence_needed: missing scripts\awx_mcp_toolbox.ps1"
    }
    $json = $Payload | ConvertTo-Json -Depth 40 -Compress
    $raw = & $toolbox -Root $ProjectRoot -Tool $Tool -InputJson $json
    if ($LASTEXITCODE -ne 0) {
        throw "evidence_needed: toolbox failed tool=$Tool exit=$LASTEXITCODE"
    }
    return ($raw -join "`n") | ConvertFrom-Json -ErrorAction Stop
}

function Invoke-PythonCapture {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogPath
    )
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $python) {
        throw 'evidence_needed: python missing'
    }
    $lines = & $python.Source $ScriptPath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    $exit = $LASTEXITCODE
    Set-Content -LiteralPath $LogPath -Value ($lines -join "`n") -Encoding UTF8
    return [pscustomobject]@{
        ExitCode = $exit
        Output = ($lines -join "`n")
    }
}

function Count-Pattern {
    param(
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern
    )
    if ([string]::IsNullOrEmpty($Text)) {
        return 0
    }
    return ([regex]::Matches($Text, $Pattern)).Count
}

function Get-SafeNameList {
    param($Value)
    $items = @()
    if ($null -eq $Value) {
        return $items
    }
    if ($Value -is [array]) {
        $items = @($Value)
    } else {
        $items = @(([string]$Value) -split ',')
    }
    return @($items | ForEach-Object {
        $name = ([string]$_).Trim()
        if ($name -match '^[A-Za-z0-9_-]+$') {
            $name
        }
    })
}

function Add-SafeEvidenceNeededItem {
    param(
        [string[]]$Items = @(),
        [AllowEmptyString()][string]$Source = '',
        [AllowEmptyString()][string]$Text = ''
    )
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return @($Items)
    }
    $safe = (([string]$Text) -replace '\s+', ' ').Trim()
    $unsafePattern = 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|Bearer\s+|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|(?i:jdbc:[A-Za-z0-9_+.-]*://)|[A-Za-z]:\\|\\\\'
    if ($safe -match $unsafePattern) {
        return @($Items)
    }
    if ($safe.Length -gt 260) {
        $safe = $safe.Substring(0, 260)
    }
    $safeSource = ([string]$Source).Trim()
    if ($safeSource -match '^[A-Za-z0-9_.:-]+$') {
        $safe = "${safeSource}:$safe"
    }
    if (@($Items).Contains($safe) -or @($Items).Count -ge 24) {
        return @($Items)
    }
    return @($Items + $safe)
}

function Sync-ReadOnlySnapshotBundle {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$SnapshotPath
    )
    $targetDir = Join-Path $ProjectRoot 'data\db-gap-report'
    $sourceDir = Split-Path -Parent $SnapshotPath
    $result = [ordered]@{
        Synced = $false
        CopiedCount = 0
        SecretHits = 0
        MutationAllowed = $false
        MutationAllowedTrueHits = 0
        SourcePresent = $false
        Safe = $false
        Reason = ''
    }
    if (-not (Test-Path -LiteralPath $SnapshotPath)) {
        $result.Reason = 'snapshot_missing'
        return [pscustomobject]$result
    }
    $result.SourcePresent = $true
    try {
        $resolvedSnapshot = (Resolve-Path -LiteralPath $SnapshotPath).Path
        $canonicalSnapshot = (Join-Path $targetDir 'supabase-schema-snapshot.json')
        if ((Test-Path -LiteralPath $canonicalSnapshot) -and ((Resolve-Path -LiteralPath $canonicalSnapshot).Path -eq $resolvedSnapshot)) {
            $result.Safe = $true
            $result.Reason = 'canonical_snapshot_path'
            return [pscustomobject]$result
        }
    } catch {
        $result.Reason = 'path_resolution_failed'
        return [pscustomobject]$result
    }
    try {
        $snapshotText = Get-Content -Raw -LiteralPath $SnapshotPath
        $snapshot = $snapshotText | ConvertFrom-Json -ErrorAction Stop
        $result.MutationAllowed = $snapshot.mutationAllowed -eq $true
        if ($snapshot.readOnly -ne $true -or $result.MutationAllowed) {
            $result.Reason = 'snapshot_not_readonly'
            return [pscustomobject]$result
        }
    } catch {
        $result.Reason = 'snapshot_unreadable'
        return [pscustomobject]$result
    }

    $bundleFiles = @(
        'supabase-schema-snapshot.json',
        'supabase-readonly-snapshot.sql',
        'supabase-query-results.template.json',
        'supabase-execute-sql-collection.packet.json',
        'supabase-readonly-snapshot.summary.json',
        'supabase-context-probe.json',
        'supabase-schema-snapshot.result.json',
        'supabase-schema-snapshot-import.result.json'
    )
    $existing = @()
    $combined = @()
    foreach ($name in $bundleFiles) {
        $sourceFile = Join-Path $sourceDir $name
        if (Test-Path -LiteralPath $sourceFile) {
            $existing += [pscustomobject]@{
                Source = $sourceFile
                Target = Join-Path $targetDir $name
            }
            $combined += Get-Content -Raw -LiteralPath $sourceFile
        }
    }
    $combinedText = $combined -join "`n"
    $result.SecretHits = Count-Pattern -Text $combinedText -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9._-]+|(?i:jdbc:[A-Za-z0-9_+.-]*://)'
    $result.MutationAllowedTrueHits = Count-Pattern -Text $combinedText -Pattern '"mutationAllowed"\s*:\s*true'
    if ($result.SecretHits -gt 0 -or $result.MutationAllowedTrueHits -gt 0) {
        $result.Reason = 'unsafe_bundle_content'
        return [pscustomobject]$result
    }
    New-Item -ItemType Directory -Force -Path $targetDir | Out-Null
    foreach ($file in $existing) {
        Copy-Item -LiteralPath $file.Source -Destination $file.Target -Force
        $result.CopiedCount++
    }
    $result.Synced = $result.CopiedCount -gt 0
    $result.Safe = $true
    $result.Reason = if ($result.Synced) { 'synced' } else { 'no_bundle_files' }
    return [pscustomobject]$result
}

function Get-CollectedEvidenceFileSafety {
    param([string[]]$Paths = @())
    $result = [ordered]@{
        FileCount = 0
        HighConfidenceSecretHits = 0
        BearerPatternHits = 0
        RawJdbcUrlHits = 0
        SecretHits = 0
        MutationAllowedTrueHits = 0
        Unsafe = $false
    }
    $seen = @{}
    foreach ($path in @($Paths)) {
        if ([string]::IsNullOrWhiteSpace($path) -or -not (Test-Path -LiteralPath $path)) {
            continue
        }
        try {
            $resolved = (Resolve-Path -LiteralPath $path).Path
        } catch {
            continue
        }
        if ($seen.ContainsKey($resolved)) {
            continue
        }
        $seen[$resolved] = $true
        $result.FileCount++
        $text = Get-Content -Raw -LiteralPath $resolved
        $result.HighConfidenceSecretHits += Count-Pattern -Text $text -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}'
        $result.BearerPatternHits += Count-Pattern -Text $text -Pattern 'Bearer\s+[A-Za-z0-9._-]+'
        $result.RawJdbcUrlHits += Count-Pattern -Text $text -Pattern '(?i:jdbc:[A-Za-z0-9_+.-]*://)'
        $result.MutationAllowedTrueHits += Count-Pattern -Text $text -Pattern '"mutationAllowed"\s*:\s*true'
    }
    $result.SecretHits = $result.HighConfidenceSecretHits + $result.BearerPatternHits + $result.RawJdbcUrlHits
    $result.Unsafe = $result.SecretHits -gt 0 -or $result.MutationAllowedTrueHits -gt 0
    return [pscustomobject]$result
}

$Root = Resolve-RepoRoot $Root
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $Root 'var\codex-smoke\supabase-apply-collected-evidence'
}
if ([string]::IsNullOrWhiteSpace($SnapshotPath)) {
    $SnapshotPath = 'data\db-gap-report\supabase-schema-snapshot.json'
}
if ([string]::IsNullOrWhiteSpace($ResultsPath)) {
    $ResultsPath = 'data\db-gap-report\supabase-query-results.json'
}
if ([string]::IsNullOrWhiteSpace($AdvisorsPath)) {
    $AdvisorsPath = 'data\db-gap-report\supabase-advisors.json'
}

$OutputDir = Resolve-RepoPath -ProjectRoot $Root -PathText $OutputDir
$SnapshotPath = Resolve-RepoPath -ProjectRoot $Root -PathText $SnapshotPath
$ResultsPath = Resolve-RepoPath -ProjectRoot $Root -PathText $ResultsPath
$AdvisorsPath = Resolve-RepoPath -ProjectRoot $Root -PathText $AdvisorsPath
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$collectedEvidenceSafety = Get-CollectedEvidenceFileSafety -Paths @($SnapshotPath, $ResultsPath, $AdvisorsPath)
if ($collectedEvidenceSafety.Unsafe) {
    $summaryPath = Join-Path $OutputDir 'supabase-apply-collected.summary.json'
    Write-JsonFile -Path $summaryPath -Value ([ordered]@{
        schemaVersion = 'awx.supabase.apply_collected_evidence.summary.v1'
        ok = $false
        decision = 'secret-leak-risk'
        importDecision = 'not_run_collected_evidence_unsafe'
        schemaSnapshotComplete = $false
        resultSetComplete = $false
        collectedEvidenceUnsafe = $true
        collectedEvidenceFileCount = $collectedEvidenceSafety.FileCount
        collectedEvidenceSecretHits = $collectedEvidenceSafety.SecretHits
        collectedEvidenceHighConfidenceSecretHits = $collectedEvidenceSafety.HighConfidenceSecretHits
        collectedEvidenceBearerPatternHits = $collectedEvidenceSafety.BearerPatternHits
        collectedEvidenceRawJdbcUrlHits = $collectedEvidenceSafety.RawJdbcUrlHits
        collectedEvidenceMutationAllowedTrueHits = $collectedEvidenceSafety.MutationAllowedTrueHits
        secretHits = $collectedEvidenceSafety.SecretHits
        rawSecretPatternHits = $collectedEvidenceSafety.SecretHits
        mutationAllowed = $collectedEvidenceSafety.MutationAllowedTrueHits -gt 0
        resultsPathPresent = Test-Path -LiteralPath $ResultsPath
        advisorsPathPresent = Test-Path -LiteralPath $AdvisorsPath
        nextActions = @('replace_collected_evidence_with_redacted_readonly_results','rerun_supabase_schema_snapshot_import')
        evidenceNeeded = @('collected_evidence_unsafe')
    })
    Write-Host "[AWX][supabase][apply-collected] collectedEvidenceUnsafe=True secretHits=$($collectedEvidenceSafety.SecretHits) mutationAllowedTrueHits=$($collectedEvidenceSafety.MutationAllowedTrueHits)"
    Write-Host '[AWX][supabase][apply-collected] secret-leak-risk'
    exit 4
}

$snapshotBundleSync = Sync-ReadOnlySnapshotBundle -ProjectRoot $Root -SnapshotPath $SnapshotPath

$payload = @{
    root = $Root
    snapshot_path = $SnapshotPath
    results_path = $ResultsPath
}
if (Test-Path -LiteralPath $AdvisorsPath) {
    $payload.advisors_path = $AdvisorsPath
}

$import = Invoke-ToolboxJson -Tool 'supabase_schema_snapshot_import' -Payload $payload -ProjectRoot $Root
$importPath = Join-Path $OutputDir 'supabase-schema-snapshot-import.result.json'
Write-JsonFile -Path $importPath -Value $import

$dbGapScript = Join-Path $Root 'scripts\db_gap_scanner.py'
$dbGapLog = Join-Path $OutputDir 'db-gap-scanner.log'
$dbGapRoot = Join-Path $Root 'main\java'
$dbGapOutput = Join-Path $Root 'data\db-gap-report'
$dbGap = Invoke-PythonCapture -ProjectRoot $Root -ScriptPath $dbGapScript -Arguments @('--root', $dbGapRoot, '--output', $dbGapOutput, '--format', 'both') -LogPath $dbGapLog

$derivedGateOwner = if ($OrchestratedByGoalNext.IsPresent) { 'goal_next_auto' } else { 'supabase_apply_collected_evidence' }
$sourceHealthStatus = if ($OrchestratedByGoalNext.IsPresent) { 'deferred_to_goal_next' } else { 'executed' }
$completionAuditStatus = if ($OrchestratedByGoalNext.IsPresent) { 'deferred_to_goal_next' } else { 'executed' }
$sourceHealthExit = $null
$completionAuditExit = $null

$scorecardScript = Join-Path $Root 'scripts\source_health_scorecard.py'
$scorecardLog = Join-Path $OutputDir 'source-health-scorecard.log'
$scorecardOutput = Join-Path $Root 'verification\source-health-scorecard.json'
$auditScript = Join-Path $Root 'scripts\awx_mcp_completion_audit.py'
$auditLog = Join-Path $OutputDir 'awx-mcp-completion-audit.log'
$auditPath = Join-Path $OutputDir 'awx-mcp-completion-audit.result.json'

if ($OrchestratedByGoalNext.IsPresent) {
    foreach ($staleDerivedArtifact in @($scorecardLog, $auditLog, $auditPath)) {
        Remove-Item -LiteralPath $staleDerivedArtifact -Force -ErrorAction SilentlyContinue
    }
} else {
    $scorecard = Invoke-PythonCapture -ProjectRoot $Root -ScriptPath $scorecardScript -Arguments @('--root', $Root, '--output', $scorecardOutput) -LogPath $scorecardLog
    $sourceHealthExit = [int]$scorecard.ExitCode

    $audit = Invoke-PythonCapture -ProjectRoot $Root -ScriptPath $auditScript -Arguments @('--root', $Root, '--output', $auditPath) -LogPath $auditLog
    $completionAuditExit = [int]$audit.ExitCode
    if (-not [string]::IsNullOrWhiteSpace($audit.Output)) {
        Set-Content -LiteralPath $auditPath -Value $audit.Output -Encoding UTF8
    }
}

$combined = @()
$combinedPaths = @($importPath, $dbGapLog)
if (-not $OrchestratedByGoalNext.IsPresent) {
    $combinedPaths += @($scorecardLog, $auditLog, $auditPath)
}
foreach ($p in $combinedPaths) {
    if (Test-Path -LiteralPath $p) {
        $combined += Get-Content -Raw -LiteralPath $p
    }
}
$combinedText = $combined -join "`n"
$secretHits = Count-Pattern -Text $combinedText -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9._-]+'
$secretHits += [int]$snapshotBundleSync.SecretHits

$importDecision = [string]$import.decision
$schemaComplete = $import.schemaSnapshotComplete -eq $true
$resultSetComplete = $import.resultSetComplete -eq $true
$mutationAllowed = ($import.mutationAllowed -eq $true) -or ($snapshotBundleSync.MutationAllowed -eq $true) -or ([int]$snapshotBundleSync.MutationAllowedTrueHits -gt 0)
$evidenceNeededCount = 0
if ($null -ne $import.evidence_needed) {
    $evidenceNeededCount = @($import.evidence_needed).Count
}
$missingResultNames = Get-SafeNameList $import.missingResultNames
$dataApiEvidenceNames = @(
    'data_api_role_grants',
    'exposed_tables_without_rls',
    'rls_and_table_flags'
)
$requiredResultNames = @(
    'schemas_and_tables',
    'rls_and_table_flags',
    'policies',
    'data_api_role_grants',
    'exposed_tables_without_rls',
    'rls_user_metadata_policies',
    'update_policies_without_select_policy',
    'storage_upsert_policy_gaps',
    'views',
    'views_missing_security_invoker',
    'exposed_security_definer_functions',
    'shadow_memory_candidate_tables',
    'shadow_memory_candidate_columns',
    'shadow_memory_metadata_fingerprints',
    'extensions'
)
$recommendedNextActions = @(
    'set_SUPABASE_PROJECT_REF',
    'authenticate_supabase_mcp_or_cli',
    'execute_each_query_once',
    'collect_get_advisors_rows',
    'run_supabase_schema_snapshot_import',
    'rerun_supabase_schema_snapshot_import',
    'rerun_db_gap_scanner'
)
$nextActions = @()
foreach ($name in @((Get-SafeNameList $import.nextActions) + $recommendedNextActions)) {
    if (-not [string]::IsNullOrWhiteSpace($name) -and -not @($nextActions).Contains($name) -and @($nextActions).Count -lt 24) {
        $nextActions += $name
    }
}
$dataApiEvidenceMissing = @($missingResultNames | Where-Object { $dataApiEvidenceNames -contains $_ })
$safeEvidenceNeeded = @()
foreach ($item in @($import.evidence_needed)) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Source 'import' -Text ([string]$item)
}
foreach ($name in @($missingResultNames)) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text "missing_result_set:$name"
}
foreach ($name in @($dataApiEvidenceMissing)) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text "data_api_evidence_missing:$name"
}
$resultsPathPresent = Test-Path -LiteralPath $ResultsPath
$advisorsPathPresent = Test-Path -LiteralPath $AdvisorsPath
$projectRefEnvPresent = -not [string]::IsNullOrWhiteSpace($env:SUPABASE_PROJECT_REF)
$accessTokenEnvPresent = -not [string]::IsNullOrWhiteSpace($env:SUPABASE_ACCESS_TOKEN)
$mcpOAuthSupported = $true
$supportedAuthModes = @('supabase_mcp_oauth_session', 'manual_SUPABASE_ACCESS_TOKEN')
$envPreflightStatus = if ($projectRefEnvPresent -and $accessTokenEnvPresent) {
    'project_ref_present_manual_token_present'
} elseif ($projectRefEnvPresent) {
    'project_ref_present_mcp_auth_pending'
} elseif ($accessTokenEnvPresent) {
    'missing_project_ref_manual_token_present'
} else {
    'missing_project_ref_and_mcp_auth'
}
$readOnlyMcpEndpointTemplate = 'https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs'
if (-not $resultsPathPresent) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text 'results_path_missing'
}
if (-not $advisorsPathPresent) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text 'advisors_path_missing'
}
$followUpVerifierFailed = $dbGap.ExitCode -ne 0
if (-not $OrchestratedByGoalNext.IsPresent -and $sourceHealthExit -ne 0) {
    $followUpVerifierFailed = $true
}
$completionAuditIncomplete = (-not $OrchestratedByGoalNext.IsPresent) -and ($completionAuditExit -ne 0)
if ($completionAuditIncomplete) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text 'completion_audit_incomplete'
}
$importIncomplete = $importDecision -ne 'supabase_schema_snapshot_imported' -or -not $schemaComplete -or -not $resultSetComplete -or $evidenceNeededCount -gt 0
$decision = 'ok'
if ($secretHits -gt 0 -or $mutationAllowed) {
    $decision = 'secret-leak-risk'
} elseif ($followUpVerifierFailed -or $completionAuditIncomplete -or $importIncomplete) {
    $decision = 'evidence_needed'
}
$summaryPath = Join-Path $OutputDir 'supabase-apply-collected.summary.json'
Write-JsonFile -Path $summaryPath -Value ([ordered]@{
    schemaVersion = 'awx.supabase.apply_collected_evidence.summary.v1'
    ok = ($decision -eq 'ok')
    decision = $decision
    importDecision = $importDecision
    schemaSnapshotComplete = $schemaComplete
    resultSetComplete = $resultSetComplete
    importedResultCount = $import.importedResultCount
    advisorRowCount = $import.advisorRowCount
    evidenceNeededCount = $evidenceNeededCount
    safeEvidenceNeededCount = @($safeEvidenceNeeded).Count
    evidenceNeeded = @($safeEvidenceNeeded)
    requiredResultCount = @($requiredResultNames).Count
    requiredResultNames = @($requiredResultNames)
    requiredMcpTools = @('execute_sql', 'get_advisors')
    nextActions = $(if ($decision -eq 'ok') { @() } else { @($nextActions) })
    resultPathRecommendation = 'data/db-gap-report/supabase-query-results.json'
    advisorResultPathRecommendation = 'data/db-gap-report/supabase-advisors.json'
    resultTemplatePathRecommendation = 'data/db-gap-report/supabase-query-results.template.json'
    collectionPacketPathRecommendation = 'data/db-gap-report/supabase-execute-sql-collection.packet.json'
    readOnlyMcpEndpointTemplate = $readOnlyMcpEndpointTemplate
    projectRefEnvPresent = $projectRefEnvPresent
    accessTokenEnvPresent = $accessTokenEnvPresent
    accessTokenManualFallbackEnvPresent = $accessTokenEnvPresent
    mcpOAuthSupported = $mcpOAuthSupported
    supportedAuthModes = @($supportedAuthModes)
    envPreflightStatus = $envPreflightStatus
    importTool = 'supabase_schema_snapshot_import'
    applyCollectedEvidenceCommand = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\supabase_apply_collected_evidence.ps1 -Root .'
    orchestratedByGoalNext = [bool]$OrchestratedByGoalNext
    derivedGateOwner = $derivedGateOwner
    dbGapExit = $dbGap.ExitCode
    sourceHealthStatus = $sourceHealthStatus
    sourceHealthExit = $sourceHealthExit
    completionAuditStatus = $completionAuditStatus
    completionAuditExit = $completionAuditExit
    secretHits = $secretHits
    rawSecretPatternHits = $secretHits
    mutationAllowed = $mutationAllowed
    missingResultCount = @($missingResultNames).Count
    missingResultNames = $missingResultNames
    dataApiEvidenceMissingCount = @($dataApiEvidenceMissing).Count
    dataApiEvidenceMissing = $dataApiEvidenceMissing
    resultsPathPresent = $resultsPathPresent
    advisorsPathPresent = $advisorsPathPresent
    snapshotBundleSynced = $snapshotBundleSync.Synced
    snapshotBundleCopiedCount = $snapshotBundleSync.CopiedCount
    snapshotBundleSecretHits = $snapshotBundleSync.SecretHits
    snapshotBundleMutationAllowedTrueHits = $snapshotBundleSync.MutationAllowedTrueHits
    snapshotBundleReason = $snapshotBundleSync.Reason
})

Write-Host "[AWX][supabase][apply-collected] importDecision=$importDecision schemaSnapshotComplete=$schemaComplete resultSetComplete=$resultSetComplete importedResultCount=$($import.importedResultCount) advisorRowCount=$($import.advisorRowCount) evidenceNeededCount=$evidenceNeededCount"
if (@($missingResultNames).Count -gt 0) {
    Write-Host "[AWX][supabase][apply-collected] missingResultNames=$($missingResultNames -join ',')"
}
if (@($dataApiEvidenceMissing).Count -gt 0) {
    Write-Host "[AWX][supabase][apply-collected] dataApiEvidenceMissing=$($dataApiEvidenceMissing -join ',')"
}
$sourceHealthExitText = if ($null -eq $sourceHealthExit) { 'n/a' } else { [string]$sourceHealthExit }
$completionAuditExitText = if ($null -eq $completionAuditExit) { 'n/a' } else { [string]$completionAuditExit }
Write-Host "[AWX][supabase][apply-collected] dbGapExit=$($dbGap.ExitCode) sourceHealthStatus=$sourceHealthStatus sourceHealthExit=$sourceHealthExitText completionAuditStatus=$completionAuditStatus completionAuditExit=$completionAuditExitText secretHits=$secretHits"
Write-Host "[AWX][supabase][apply-collected] resultsPathPresent=$resultsPathPresent advisorsPathPresent=$advisorsPathPresent"
Write-Host "[AWX][supabase][apply-collected] projectRefEnvPresent=$projectRefEnvPresent accessTokenEnvPresent=$accessTokenEnvPresent envPreflightStatus=$envPreflightStatus"
Write-Host "[AWX][supabase][apply-collected] snapshotBundleSynced=$($snapshotBundleSync.Synced) snapshotBundleCopiedCount=$($snapshotBundleSync.CopiedCount) snapshotBundleReason=$($snapshotBundleSync.Reason)"

if ($secretHits -gt 0 -or $mutationAllowed) {
    Write-Host '[AWX][supabase][apply-collected] secret-leak-risk'
    exit 4
}
if ($followUpVerifierFailed) {
    Write-Host '[AWX][supabase][apply-collected] evidence_needed: follow-up verifier failed'
    exit 3
}
if ($completionAuditIncomplete) {
    Write-Host '[AWX][supabase][apply-collected] evidence_needed: completion audit incomplete'
    exit 2
}
if ($importIncomplete) {
    Write-Host '[AWX][supabase][apply-collected] evidence_needed: imported Supabase evidence is incomplete'
    exit 2
}

Write-Host '[AWX][supabase][apply-collected] ok=true'
exit 0
