[CmdletBinding()]
param(
    [string]$Root = '',

    [string]$OutputDir = '',

    [int]$TimeoutSec = 10,

    [switch]$RequireProjectScope,

    [switch]$SkipNetworkProbe,

    [switch]$Help
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    @'
[AWX][supabase][readonly-smoke] usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke_supabase_readonly_snapshot.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke_supabase_readonly_snapshot.ps1 -Root <repo-root> -OutputDir var\codex-smoke\supabase-readonly-snapshot -RequireProjectScope

Required external evidence before this can close:
  SUPABASE_PROJECT_REF present
  SUPABASE_ACCESS_TOKEN present, or authenticated Supabase MCP/CLI session
  execute_sql and get_advisors available through a read-only project-scoped Supabase MCP/CLI path

Outputs:
  var\codex-smoke\supabase-readonly-snapshot\supabase-context-probe.json
  var\codex-smoke\supabase-readonly-snapshot\supabase-schema-snapshot.json
  var\codex-smoke\supabase-readonly-snapshot\supabase-readonly-snapshot.summary.json

Follow-up:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\supabase_apply_collected_evidence.ps1 -Root <repo-root>

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
    $json = $Payload | ConvertTo-Json -Depth 20 -Compress
    $raw = & $toolbox -Root $ProjectRoot -Tool $Tool -InputJson $json
    if ($LASTEXITCODE -ne 0) {
        throw "evidence_needed: toolbox failed tool=$Tool exit=$LASTEXITCODE"
    }
    return ($raw -join "`n") | ConvertFrom-Json -ErrorAction Stop
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

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $text = $Value | ConvertTo-Json -Depth 100
    Set-Content -LiteralPath $Path -Value $text -Encoding UTF8
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

function Get-SafeScalarList {
    param($Value)
    if ($null -eq $Value) {
        return @()
    }
    return @(@($Value) | ForEach-Object {
        $item = ([string]$_).Trim()
        if ($item -match '^[A-Za-z0-9_.:-]+$') {
            $item
        }
    })
}

function Add-SafeEvidenceNeededItem {
    param(
        [string[]]$Items,
        [AllowEmptyString()][string]$Text
    )
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return @($Items)
    }
    $safe = ($Text -replace '\s+', ' ').Trim()
    if ($safe -match 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|Bearer\s+|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|(?i)\bjdbc:[A-Za-z0-9_+.-]*://|[A-Za-z]:\\') {
        return @($Items)
    }
    if ($safe.Length -gt 240) {
        $safe = $safe.Substring(0, 240)
    }
    if (@($Items).Contains($safe) -or @($Items).Count -ge 24) {
        return @($Items)
    }
    return @($Items + $safe)
}

function Get-SafeDocRefIds {
    param($Value)
    if ($null -eq $Value) {
        return @()
    }
    return @(@($Value) | ForEach-Object {
        $id = ''
        if ($null -ne $_.id) {
            $id = ([string]$_.id).Trim()
        }
        if ($id -match '^[A-Za-z0-9_.:-]+$') {
            $id
        }
    })
}

$Root = Resolve-RepoRoot $Root
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $Root 'var\codex-smoke\supabase-readonly-snapshot'
}
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$snapshotPath = Join-Path $OutputDir 'supabase-schema-snapshot.json'
$contextPath = Join-Path $OutputDir 'supabase-context-probe.json'
$snapshotResultPath = Join-Path $OutputDir 'supabase-schema-snapshot.result.json'
$importResultPath = Join-Path $OutputDir 'supabase-schema-snapshot-import.result.json'
$summaryPath = Join-Path $OutputDir 'supabase-readonly-snapshot.summary.json'
$contextPayload = @{
    root = $Root
    timeout_sec = $TimeoutSec
}
if ($SkipNetworkProbe) {
    $contextPayload.skip_mcp_network_probe = $true
}
$context = Invoke-ToolboxJson -Tool 'supabase_context_probe' -Payload $contextPayload -ProjectRoot $Root
Write-JsonFile -Path $contextPath -Value $context

$snapshotPayload = @{
    root = $Root
    output_path = $snapshotPath
    timeout_sec = $TimeoutSec
}
if ($SkipNetworkProbe) {
    $snapshotPayload.skip_mcp_network_probe = $true
}
$snapshot = Invoke-ToolboxJson -Tool 'supabase_schema_snapshot' -Payload $snapshotPayload -ProjectRoot $Root
Write-JsonFile -Path $snapshotResultPath -Value $snapshot

$importPayload = @{
    root = $Root
    snapshot_path = $snapshotPath
}
$import = Invoke-ToolboxJson -Tool 'supabase_schema_snapshot_import' -Payload $importPayload -ProjectRoot $Root
Write-JsonFile -Path $importResultPath -Value $import

$artifactText = ''
if (Test-Path -LiteralPath $snapshotPath) {
    $artifactText = Get-Content -Raw -LiteralPath $snapshotPath
}
$combined = @(
    Get-Content -Raw -LiteralPath $contextPath
    Get-Content -Raw -LiteralPath $snapshotResultPath
    Get-Content -Raw -LiteralPath $importResultPath
    $artifactText
) -join "`n"

$highConfidenceSecretHits = Count-Pattern -Text $combined -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}'
$bearerPatternHits = Count-Pattern -Text $combined -Pattern 'Bearer\s+[A-Za-z0-9._-]+'
$rawJdbcUrlHits = Count-Pattern -Text $combined -Pattern '(?i)\bjdbc:[A-Za-z0-9_+.-]*://'
$windowsAbsPathHits = Count-Pattern -Text $combined -Pattern '[A-Za-z]:\\[^\r\n"]+'
$mutationAllowedTextHits = Count-Pattern -Text $combined -Pattern '"mutationAllowed"\s*:\s*true'

$projectStatus = ''
if ($null -ne $context.projectScope) {
    $projectStatus = [string]$context.projectScope.status
}
$envPresentNames = Get-SafeNameList $context.envPresent
$authEnvStatus = @()
if ($null -ne $context.authPlan -and $null -ne $context.authPlan.manualAuthEnvStatus) {
    $authEnvStatus = @($context.authPlan.manualAuthEnvStatus)
}
function Test-AuthEnvPresent {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        $StatusList,
        $FallbackNames
    )
    foreach ($item in @($StatusList)) {
        if ([string]$item.name -eq $Name) {
            return ($item.present -eq $true)
        }
    }
    return @($FallbackNames).Contains($Name)
}
$projectRefEnvPresent = Test-AuthEnvPresent -Name 'SUPABASE_PROJECT_REF' -StatusList $authEnvStatus -FallbackNames $envPresentNames
$accessTokenEnvPresent = Test-AuthEnvPresent -Name 'SUPABASE_ACCESS_TOKEN' -StatusList $authEnvStatus -FallbackNames $envPresentNames
$cliPresent = $context.cli.present -eq $true
$contextEvidenceNeededCount = 0
if ($null -ne $context.evidence_needed) {
    $contextEvidenceNeededCount = @($context.evidence_needed).Count
}
$snapshotDecision = [string]$snapshot.decision
$importDecision = [string]$import.decision
$missingResultNames = Get-SafeNameList $import.missingResultNames
$dataApiEvidenceNames = @(
    'data_api_role_grants',
    'exposed_tables_without_rls',
    'rls_and_table_flags'
)
$dataApiEvidenceMissing = @($missingResultNames | Where-Object { $dataApiEvidenceNames -contains $_ })
$docsRefIds = Get-SafeDocRefIds $snapshot.docsRefs
$securityContracts = Get-SafeScalarList $snapshot.securityContracts
$cliQueryMinVersion = ''
$cliAdvisorsMinVersion = ''
$cliQueryFallbackTool = ''
$cliAdvisorsFallbackTool = ''
if ($null -ne $snapshot.cliVersionRequirements) {
    $cliQueryMinVersion = [string]$snapshot.cliVersionRequirements.'supabase db query'
    $cliAdvisorsMinVersion = [string]$snapshot.cliVersionRequirements.'supabase db advisors'
}
if ($null -ne $snapshot.cliFallbackContract) {
    $cliQueryFallbackTool = [string]$snapshot.cliFallbackContract.'supabase db query'.mcpTool
    $cliAdvisorsFallbackTool = [string]$snapshot.cliFallbackContract.'supabase db advisors'.mcpTool
}
$dataApiGrantProofRequired = (
    @($dataApiEvidenceMissing).Contains('data_api_role_grants') -or
    @($securityContracts).Contains('data_api_grants_control_reachability') -or
    @($docsRefIds).Contains('data-api-grants-breaking-change')
)
$rlsPolicyProofRequired = (
    @($missingResultNames).Contains('rls_and_table_flags') -or
    @($missingResultNames).Contains('exposed_tables_without_rls') -or
    @($missingResultNames).Contains('rls_user_metadata_policies') -or
    @($securityContracts).Contains('rls_controls_visible_rows_after_grant')
)
$safeEvidenceNeeded = @()
if ($projectStatus -eq 'project_ref_missing') {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text 'project_ref_missing:set_SUPABASE_PROJECT_REF'
}
if (-not $projectRefEnvPresent) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text 'env_missing:SUPABASE_PROJECT_REF'
}
if (-not $accessTokenEnvPresent) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text 'env_missing:SUPABASE_ACCESS_TOKEN_or_authenticated_mcp_session'
}
if (-not $cliPresent) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text 'tool_missing_or_unseen:supabase_cli'
}
foreach ($item in @($context.evidence_needed)) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text ([string]$item)
}
foreach ($item in @($import.evidence_needed)) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text ([string]$item)
}
foreach ($name in @($missingResultNames)) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text "missing_result_set:$name"
}
foreach ($name in @($dataApiEvidenceMissing)) {
    $safeEvidenceNeeded = Add-SafeEvidenceNeededItem -Items $safeEvidenceNeeded -Text "data_api_evidence_missing:$name"
}

function Write-SmokeSummary {
    param(
        [Parameter(Mandatory = $true)][bool]$Ok,
        [Parameter(Mandatory = $true)][string]$Decision,
        [Parameter(Mandatory = $true)][int]$ExitCode,
        [string]$FailureClass = ''
    )

    $evidenceNeededCount = @($safeEvidenceNeeded).Count

    $importedResultCount = 0
    if ($null -ne $import.importedResultCount) {
        $importedResultCount = [int]$import.importedResultCount
    }

    $summary = [ordered]@{
        schemaVersion = 'awx.supabase.readonly_snapshot_smoke.summary.v1'
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
        ok = $Ok
        decision = $Decision
        exitCode = $ExitCode
        failureClass = $FailureClass
        contextDecision = [string]$context.decision
        projectScopeStatus = $projectStatus
        mcpReachable = $context.mcp.reachable -eq $true
        mcpProbeSkipped = $context.mcp.probeSkipped -eq $true
        mcpDecision = [string]$context.mcp.decision
        snapshotDecision = $snapshotDecision
        importDecision = $importDecision
        schemaSnapshotAvailable = $snapshot.schemaSnapshotAvailable -eq $true
        schemaSnapshotComplete = $import.schemaSnapshotComplete -eq $true
        resultSetComplete = $import.resultSetComplete -eq $true
        importedResultCount = $importedResultCount
        missingResultCount = @($missingResultNames).Count
        dataApiEvidenceMissingCount = @($dataApiEvidenceMissing).Count
        docsRefCount = @($docsRefIds).Count
        securityContractCount = @($securityContracts).Count
        cliQueryMinVersion = $cliQueryMinVersion
        cliAdvisorsMinVersion = $cliAdvisorsMinVersion
        cliQueryFallbackTool = $cliQueryFallbackTool
        cliAdvisorsFallbackTool = $cliAdvisorsFallbackTool
        dataApiGrantProofRequired = $dataApiGrantProofRequired
        rlsPolicyProofRequired = $rlsPolicyProofRequired
        apiKeysDocs = $snapshot.apiKeysDocs -eq $true
        secretKeysBackendOnly = ($snapshot.secretKeysBackendOnly -eq $true -or @($securityContracts).Contains('secret_keys_backend_only'))
        envPresentCount = @($envPresentNames).Count
        projectRefEnvPresent = $projectRefEnvPresent
        accessTokenEnvPresent = $accessTokenEnvPresent
        cliPresent = $cliPresent
        contextEvidenceNeededCount = $contextEvidenceNeededCount
        evidenceNeededCount = $evidenceNeededCount
        evidenceNeeded = @($safeEvidenceNeeded)
        mutationAllowed = ($snapshot.mutationAllowed -eq $true -or $import.mutationAllowed -eq $true -or $mutationAllowedTextHits -gt 0)
        secretHits = ($highConfidenceSecretHits + $bearerPatternHits)
        rawSecretPatternHits = ($highConfidenceSecretHits + $bearerPatternHits + $rawJdbcUrlHits + $windowsAbsPathHits)
        highConfidenceSecretHits = $highConfidenceSecretHits
        bearerPatternHits = $bearerPatternHits
        rawJdbcUrlHits = $rawJdbcUrlHits
        windowsAbsPathHits = $windowsAbsPathHits
        mutationAllowedTextHits = $mutationAllowedTextHits
    }
    Write-JsonFile -Path $summaryPath -Value $summary
}

Write-Host "[AWX][supabase][readonly-smoke] contextDecision=$($context.decision) projectScopeStatus=$projectStatus cliPresent=$($context.cli.present) mcpReachable=$($context.mcp.reachable) mcpProbeSkipped=$($context.mcp.probeSkipped) mcpDecision=$($context.mcp.decision)"
Write-Host "[AWX][supabase][readonly-smoke] snapshotDecision=$snapshotDecision schemaSnapshotAvailable=$($snapshot.schemaSnapshotAvailable) mutationAllowed=$($snapshot.mutationAllowed)"
Write-Host "[AWX][supabase][readonly-smoke] importDecision=$importDecision schemaSnapshotAvailable=$($import.schemaSnapshotAvailable) schemaSnapshotComplete=$($import.schemaSnapshotComplete) resultSetComplete=$($import.resultSetComplete) importedResultCount=$($import.importedResultCount)"
Write-Host "[AWX][supabase][readonly-smoke] envPresentCount=$(@($envPresentNames).Count) projectRefEnvPresent=$projectRefEnvPresent accessTokenEnvPresent=$accessTokenEnvPresent cliPresent=$cliPresent contextEvidenceNeededCount=$contextEvidenceNeededCount"
if (@($missingResultNames).Count -gt 0) {
    Write-Host "[AWX][supabase][readonly-smoke] missingResultNames=$($missingResultNames -join ',')"
}
if (@($dataApiEvidenceMissing).Count -gt 0) {
    Write-Host "[AWX][supabase][readonly-smoke] dataApiEvidenceMissing=$($dataApiEvidenceMissing -join ',')"
}
Write-Host "[AWX][supabase][readonly-smoke] highConfidenceSecretHits=$highConfidenceSecretHits bearerPatternHits=$bearerPatternHits rawJdbcUrlHits=$rawJdbcUrlHits windowsAbsPathHits=$windowsAbsPathHits mutationAllowedTextHits=$mutationAllowedTextHits"

if ($snapshot.mutationAllowed -eq $true -or $import.mutationAllowed -eq $true -or $mutationAllowedTextHits -gt 0) {
    Write-Host '[AWX][supabase][readonly-smoke] secret-leak-risk: mutationAllowed=true'
    Write-SmokeSummary -Ok $false -Decision 'secret-leak-risk' -ExitCode 4 -FailureClass 'secret-leak-risk'
    exit 4
}
if ($highConfidenceSecretHits -gt 0 -or $bearerPatternHits -gt 0 -or $rawJdbcUrlHits -gt 0 -or $windowsAbsPathHits -gt 0) {
    Write-Host '[AWX][supabase][readonly-smoke] secret-leak-risk: response contains forbidden raw pattern or path'
    Write-SmokeSummary -Ok $false -Decision 'secret-leak-risk' -ExitCode 4 -FailureClass 'secret-leak-risk'
    exit 4
}
if ($RequireProjectScope -and $projectStatus -eq 'project_ref_missing') {
    Write-Host '[AWX][supabase][readonly-smoke] evidence_needed: project_ref_missing'
    Write-SmokeSummary -Ok $false -Decision 'evidence_needed' -ExitCode 2 -FailureClass 'missing-external-key'
    exit 2
}
if ($snapshotDecision -ne 'supabase_schema_snapshot_evidence_needed') {
    Write-Host '[AWX][supabase][readonly-smoke] evidence_needed: unexpected snapshot decision'
    Write-SmokeSummary -Ok $false -Decision 'evidence_needed' -ExitCode 3 -FailureClass 'other'
    exit 3
}
if ($importDecision -ne 'supabase_schema_snapshot_import_evidence_needed') {
    Write-Host '[AWX][supabase][readonly-smoke] evidence_needed: unexpected import decision'
    Write-SmokeSummary -Ok $false -Decision 'evidence_needed' -ExitCode 3 -FailureClass 'other'
    exit 3
}
if ($projectStatus -eq 'project_ref_missing') {
    Write-Host '[AWX][supabase][readonly-smoke] evidence_needed: project_ref_missing'
}
if ($null -ne $import.evidence_needed -and @($import.evidence_needed).Count -gt 0) {
    Write-Host "[AWX][supabase][readonly-smoke] evidence_needed: importEvidenceCount=$(@($import.evidence_needed).Count)"
}

Write-SmokeSummary -Ok $true -Decision 'supabase_readonly_smoke_complete' -ExitCode 0
Write-Host '[AWX][supabase][readonly-smoke] ok=true'
exit 0
