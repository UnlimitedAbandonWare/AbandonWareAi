$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$PowerShellExe = $null
try {
    $PowerShellExe = (Get-Process -Id $PID).Path
} catch {
    $PowerShellExe = $null
}
if ([string]::IsNullOrWhiteSpace($PowerShellExe)) {
    $PowerShellExe = 'powershell'
}

$FakeRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-supabase-smoke-tests-' + [guid]::NewGuid().ToString('N'))
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[supabase-smoke-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[supabase-smoke-test][FAIL] $Name :: $Message"
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = 'assertion failed'
    )
    if ($Condition) { Write-Pass $Name } else { Write-Fail $Name $Message }
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name ($Text.Contains($Needle)) "expected output to contain '$Needle'; output=$Text"
}

function Set-TestFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Value
    )
    $parent = Split-Path -Parent $Path
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Invoke-Smoke {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$OutputDir,
        [string]$ProjectStatus = 'project_ref_ready',
        [bool]$InjectJdbcDiagnostic = $true
    )
    $scriptPath = Join-Path $script:ScriptsRoot 'smoke_supabase_readonly_snapshot.ps1'
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $scriptPath,
        '-Root',
        $Root,
        '-OutputDir',
        $OutputDir,
        '-RequireProjectScope',
        '-SkipNetworkProbe'
    )
    $previousStatus = $env:AWX_FAKE_SUPABASE_PROJECT_STATUS
    $previousJdbc = $env:AWX_FAKE_SUPABASE_JDBC_DIAGNOSTIC
    $env:AWX_FAKE_SUPABASE_PROJECT_STATUS = $ProjectStatus
    $env:AWX_FAKE_SUPABASE_JDBC_DIAGNOSTIC = if ($InjectJdbcDiagnostic) { '1' } else { '0' }
    try {
        $lines = & $script:PowerShellExe @arguments 2>&1 |
            ForEach-Object { $_.ToString() }
    } finally {
        if ($null -eq $previousStatus) {
            Remove-Item Env:\AWX_FAKE_SUPABASE_PROJECT_STATUS -ErrorAction SilentlyContinue
        } else {
            $env:AWX_FAKE_SUPABASE_PROJECT_STATUS = $previousStatus
        }
        if ($null -eq $previousJdbc) {
            Remove-Item Env:\AWX_FAKE_SUPABASE_JDBC_DIAGNOSTIC -ErrorAction SilentlyContinue
        } else {
            $env:AWX_FAKE_SUPABASE_JDBC_DIAGNOSTIC = $previousJdbc
        }
    }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
    }
}

function Invoke-SmokeHelp {
    $scriptPath = Join-Path $script:ScriptsRoot 'smoke_supabase_readonly_snapshot.ps1'
    $arguments = @(
        '-NoProfile',
        '-ExecutionPolicy',
        'Bypass',
        '-File',
        $scriptPath,
        '-Help'
    )
    $lines = & $script:PowerShellExe @arguments 2>&1 |
        ForEach-Object { $_.ToString() }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = ($lines -join "`n")
    }
}

try {
    $help = Invoke-SmokeHelp
    Assert-True 'supabase smoke help exits zero' ($help.ExitCode -eq 0) "expected exit 0; output=$($help.Output)"
    Assert-Contains 'supabase smoke help names project ref env' $help.Output 'SUPABASE_PROJECT_REF'
    Assert-Contains 'supabase smoke help names access token env without value' $help.Output 'SUPABASE_ACCESS_TOKEN'
    Assert-Contains 'supabase smoke help names output dir' $help.Output 'var\codex-smoke\supabase-readonly-snapshot'
    Assert-Contains 'supabase smoke help names apply follow-up' $help.Output 'supabase_apply_collected_evidence.ps1'
    Assert-True 'supabase smoke help prints no raw auth header' (-not ($help.Output -match 'Bearer\s+[A-Za-z0-9._~+/-]+=*')) "output=$($help.Output)"

    $fakeScripts = Join-Path $FakeRoot 'scripts'
    New-Item -ItemType Directory -Force -Path $fakeScripts | Out-Null
    Set-TestFile (Join-Path $fakeScripts 'awx_mcp_toolbox.ps1') @'
param(
    [string]$Root,
    [string]$Tool,
    [string]$InputJson
)

$payload = $InputJson | ConvertFrom-Json
switch ($Tool) {
    'supabase_context_probe' {
        $status = $env:AWX_FAKE_SUPABASE_PROJECT_STATUS
        if ([string]::IsNullOrWhiteSpace($status)) {
            $status = 'project_ref_ready'
        }
        $probeSkipped = $payload.skip_mcp_network_probe -eq $true
        @{
            decision = 'supabase_context_probe'
            envPresent = @('SUPABASE_PROJECT_REF')
            cli = @{ present = $false }
            mcp = @{
                reachable = -not $probeSkipped
                probeSkipped = $probeSkipped
                decision = if ($probeSkipped) { 'mcp_endpoint_probe_skipped' } else { 'mcp_endpoint_auth_required' }
            }
            projectScope = @{ status = $status }
            authPlan = @{
                manualAuthEnvStatus = @(
                    @{ name = 'SUPABASE_PROJECT_REF'; present = $true; sensitive = $false },
                    @{ name = 'SUPABASE_ACCESS_TOKEN'; present = $false; sensitive = $true }
                )
            }
            evidence_needed = @(
                'Supabase MCP endpoint probe skipped / rerun without skip_mcp_network_probe before claiming endpoint reachability',
                'Supabase access token missing / authenticate Supabase MCP or CLI'
            )
        } | ConvertTo-Json -Depth 20 -Compress
        exit 0
    }
    'supabase_schema_snapshot' {
        $diagnostic = ''
        if ($env:AWX_FAKE_SUPABASE_JDBC_DIAGNOSTIC -eq '1') {
            $diagnostic = 'jdbc:postgresql://db.example.internal/postgres'
        }
        $snapshot = @{
            schemaVersion = 'awx.mcp.supabase_schema_snapshot.v1'
            readOnly = $true
            mutationAllowed = $false
            diagnostic = $diagnostic
        }
        $snapshot | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $payload.output_path -Encoding UTF8
        @{
            decision = 'supabase_schema_snapshot_evidence_needed'
            schemaSnapshotAvailable = $false
            mutationAllowed = $false
            docsRefs = @(
                @{ id = 'data-api-grants-breaking-change'; url = 'https://supabase.com/changelog/45329-breaking-change-tables-not-exposed-to-data-and-graphql-api-automatically' },
                @{ id = 'securing-data-api'; url = 'https://supabase.com/docs/guides/api/securing-your-api' }
            )
            securityContracts = @(
                'data_api_grants_control_reachability',
                'rls_controls_visible_rows_after_grant',
                'secret_keys_backend_only'
            )
            cliVersionRequirements = @{
                'supabase db query' = '>=2.79.0'
                'supabase db advisors' = '>=2.81.3'
            }
            cliFallbackContract = @{
                'supabase db query' = @{ mcpTool = 'execute_sql'; when = 'cli_missing_or_below_minimum'; readOnly = $true }
                'supabase db advisors' = @{ mcpTool = 'get_advisors'; when = 'cli_missing_or_below_minimum'; readOnly = $true }
            }
            apiKeysDocs = $true
            secretKeysBackendOnly = $true
        } | ConvertTo-Json -Depth 20 -Compress
        exit 0
    }
    'supabase_schema_snapshot_import' {
        @{
            decision = 'supabase_schema_snapshot_import_evidence_needed'
            schemaSnapshotAvailable = $false
            schemaSnapshotComplete = $false
            resultSetComplete = $false
            mutationAllowed = $false
            importedResultCount = 0
            missingResultNames = @(
                'data_api_role_grants',
                'exposed_tables_without_rls',
                'rls_and_table_flags',
                'rls_user_metadata_policies'
            )
            evidence_needed = @(
                'missing expected Supabase result sets: data_api_role_grants, exposed_tables_without_rls, rls_and_table_flags'
            )
        } | ConvertTo-Json -Depth 20 -Compress
        exit 0
    }
}
throw "unexpected tool=$Tool"
'@

    $outputDir = Join-Path $FakeRoot 'out'
    $result = Invoke-Smoke -Root $FakeRoot -OutputDir $outputDir

    Assert-True 'supabase smoke blocks standard jdbc urls' ($result.ExitCode -eq 4) "expected exit 4; output=$($result.Output)"
    Assert-Contains 'supabase smoke reports jdbc hit count' $result.Output 'rawJdbcUrlHits=1'
    Assert-Contains 'supabase smoke classifies jdbc as secret risk' $result.Output 'secret-leak-risk'
    $jdbcSummaryPath = Join-Path $FakeRoot 'out\supabase-readonly-snapshot.summary.json'
    Assert-True 'supabase smoke jdbc summary artifact exists' (Test-Path -LiteralPath $jdbcSummaryPath) "missing summary artifact at $jdbcSummaryPath"
    if (Test-Path -LiteralPath $jdbcSummaryPath) {
        $jdbcSummary = Get-Content -Raw -LiteralPath $jdbcSummaryPath | ConvertFrom-Json
        Assert-True 'supabase smoke jdbc summary records raw secret pattern hit' ([int]$jdbcSummary.rawSecretPatternHits -gt 0) "summary=$($jdbcSummary | ConvertTo-Json -Compress)"
    }

    $missingProjectResult = Invoke-Smoke -Root $FakeRoot -OutputDir (Join-Path $FakeRoot 'out-missing-project') -ProjectStatus 'project_ref_missing'

    Assert-True 'supabase smoke prioritizes jdbc leak over missing project ref' ($missingProjectResult.ExitCode -eq 4) "expected exit 4; output=$($missingProjectResult.Output)"
    Assert-Contains 'supabase smoke still reports missing project ref context' $missingProjectResult.Output 'projectScopeStatus=project_ref_missing'
    Assert-Contains 'supabase smoke classifies missing-project jdbc as secret risk' $missingProjectResult.Output 'secret-leak-risk'

    $missingDataApiResult = Invoke-Smoke -Root $FakeRoot -OutputDir (Join-Path $FakeRoot 'out-data-api') -InjectJdbcDiagnostic $false

    Assert-True 'supabase smoke allows evidence-needed exit without leaks' ($missingDataApiResult.ExitCode -eq 0) "expected exit 0; output=$($missingDataApiResult.Output)"
    Assert-Contains 'supabase smoke records skipped MCP probe' $missingDataApiResult.Output 'mcpProbeSkipped=True'
    Assert-Contains 'supabase smoke records skipped MCP decision' $missingDataApiResult.Output 'mcpDecision=mcp_endpoint_probe_skipped'
    Assert-Contains 'supabase smoke reports incomplete schema snapshot' $missingDataApiResult.Output 'schemaSnapshotComplete=False'
    Assert-Contains 'supabase smoke reports incomplete result set' $missingDataApiResult.Output 'resultSetComplete=False'
    Assert-Contains 'supabase smoke reports missing result names' $missingDataApiResult.Output 'missingResultNames=data_api_role_grants,exposed_tables_without_rls,rls_and_table_flags,rls_user_metadata_policies'
    Assert-Contains 'supabase smoke reports data api evidence names' $missingDataApiResult.Output 'dataApiEvidenceMissing=data_api_role_grants,exposed_tables_without_rls,rls_and_table_flags'
    Assert-Contains 'supabase smoke reports env preflight names only' $missingDataApiResult.Output 'envPresentCount=1 projectRefEnvPresent=True accessTokenEnvPresent=False cliPresent=False contextEvidenceNeededCount=2'

    $summaryPath = Join-Path $FakeRoot 'out-data-api\supabase-readonly-snapshot.summary.json'
    Assert-True 'supabase smoke writes summary artifact' (Test-Path -LiteralPath $summaryPath) "missing summary artifact at $summaryPath"
    if (Test-Path -LiteralPath $summaryPath) {
        $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json
        Assert-True 'supabase smoke summary records ok path' ($summary.ok -eq $true) "unexpected ok=$($summary.ok)"
        Assert-True 'supabase smoke summary records generatedAt' (-not [string]::IsNullOrWhiteSpace([string]$summary.generatedAt)) 'missing generatedAt'
        Assert-True 'supabase smoke summary records import decision' ($summary.importDecision -eq 'supabase_schema_snapshot_import_evidence_needed') "unexpected importDecision=$($summary.importDecision)"
        Assert-True 'supabase smoke summary records missing result count' ($summary.missingResultCount -eq 4) "unexpected missingResultCount=$($summary.missingResultCount)"
        Assert-True 'supabase smoke summary records top-level secret counters' ([int]$summary.secretHits -eq 0 -and [int]$summary.rawSecretPatternHits -eq 0) "unexpected top-level secret counters"
        Assert-True 'supabase smoke summary records no secret hits' ($summary.highConfidenceSecretHits -eq 0 -and $summary.rawJdbcUrlHits -eq 0) "unexpected secret counters"
        Assert-True 'supabase smoke summary records docs refs' ($summary.docsRefCount -ge 2) "unexpected docsRefCount=$($summary.docsRefCount)"
        Assert-True 'supabase smoke summary records security contracts' ($summary.securityContractCount -ge 3) "unexpected securityContractCount=$($summary.securityContractCount)"
        Assert-True 'supabase smoke summary records CLI query minimum version' ($summary.cliQueryMinVersion -eq '>=2.79.0') "unexpected cliQueryMinVersion=$($summary.cliQueryMinVersion)"
        Assert-True 'supabase smoke summary records CLI advisors minimum version' ($summary.cliAdvisorsMinVersion -eq '>=2.81.3') "unexpected cliAdvisorsMinVersion=$($summary.cliAdvisorsMinVersion)"
        Assert-True 'supabase smoke summary records query MCP fallback' ($summary.cliQueryFallbackTool -eq 'execute_sql') "unexpected cliQueryFallbackTool=$($summary.cliQueryFallbackTool)"
        Assert-True 'supabase smoke summary records advisors MCP fallback' ($summary.cliAdvisorsFallbackTool -eq 'get_advisors') "unexpected cliAdvisorsFallbackTool=$($summary.cliAdvisorsFallbackTool)"
        Assert-True 'supabase smoke summary records data api grant contract' ($summary.dataApiGrantProofRequired -eq $true) "unexpected dataApiGrantProofRequired=$($summary.dataApiGrantProofRequired)"
        Assert-True 'supabase smoke summary records rls policy contract' ($summary.rlsPolicyProofRequired -eq $true) "unexpected rlsPolicyProofRequired=$($summary.rlsPolicyProofRequired)"
        Assert-True 'supabase smoke summary records secret key backend-only docs' ($summary.secretKeysBackendOnly -eq $true) "unexpected secretKeysBackendOnly=$($summary.secretKeysBackendOnly)"
        Assert-True 'supabase smoke summary records env present count' ($summary.envPresentCount -eq 1) "unexpected envPresentCount=$($summary.envPresentCount)"
        Assert-True 'supabase smoke summary records project ref env presence' ($summary.projectRefEnvPresent -eq $true) "unexpected projectRefEnvPresent=$($summary.projectRefEnvPresent)"
        Assert-True 'supabase smoke summary records access token env absence' ($summary.accessTokenEnvPresent -eq $false) "unexpected accessTokenEnvPresent=$($summary.accessTokenEnvPresent)"
        Assert-True 'supabase smoke summary records cli absence' ($summary.cliPresent -eq $false) "unexpected cliPresent=$($summary.cliPresent)"
        Assert-True 'supabase smoke summary records context evidence-needed count' ($summary.contextEvidenceNeededCount -eq 2) "unexpected contextEvidenceNeededCount=$($summary.contextEvidenceNeededCount)"
        $summaryText = $summary | ConvertTo-Json -Depth 20 -Compress
        Assert-True 'supabase smoke summary records bounded evidence-needed list' (@($summary.evidenceNeeded).Count -ge 6) "summary=$summaryText"
        Assert-Contains 'supabase smoke summary records access-token next action' $summaryText 'env_missing:SUPABASE_ACCESS_TOKEN_or_authenticated_mcp_session'
        Assert-Contains 'supabase smoke summary records missing data-api grant result' $summaryText 'missing_result_set:data_api_role_grants'
        Assert-Contains 'supabase smoke summary records rls result gap' $summaryText 'missing_result_set:rls_and_table_flags'
        Assert-Contains 'supabase smoke summary records data-api evidence gap' $summaryText 'data_api_evidence_missing:exposed_tables_without_rls'
        Assert-True 'supabase smoke summary evidence list is path-safe' (-not ($summaryText -match '[A-Za-z]:\\\\')) "summary=$summaryText"
        Assert-True 'supabase smoke summary evidence list is secret-safe' (-not ($summaryText -match 'Bearer\s+|jdbc:|sb_(?:secret|publishable)_|sbp_|sk-[A-Za-z0-9_-]{20,}')) "summary=$summaryText"
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
} finally {
    if (Test-Path $FakeRoot) {
        Remove-Item -LiteralPath $FakeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($Failures -gt 0) {
    Write-Host "[supabase-smoke-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[supabase-smoke-test][SUMMARY] failed=0'
exit 0
