$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$SmokeScript = Join-Path $ScriptsRoot 'smoke_db_evidence_scan_runtime.ps1'
$PowerShellExe = $null
try {
    $PowerShellExe = (Get-Process -Id $PID).Path
} catch {
    $PowerShellExe = $null
}
if ([string]::IsNullOrWhiteSpace($PowerShellExe)) {
    $PowerShellExe = 'powershell'
}
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[db-evidence-runtime-smoke-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[db-evidence-runtime-smoke-test][FAIL] $Name :: $Message"
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
    Assert-True $Name ($Text.Contains($Needle)) "expected source to contain '$Needle'"
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name (-not $Text.Contains($Needle)) "expected source not to contain '$Needle'"
}

try {
    Assert-True 'runtime smoke script exists' (Test-Path -LiteralPath $SmokeScript) 'missing scripts\smoke_db_evidence_scan_runtime.ps1'
    if (Test-Path -LiteralPath $SmokeScript) {
        $source = Get-Content -Raw -LiteralPath $SmokeScript

        Assert-Contains 'runtime smoke builds bootJar before launch' $source 'bootJar'
        Assert-Contains 'runtime smoke captures native stderr without terminating' $source 'Invoke-NativeCapture'
        Assert-Contains 'runtime smoke locates java launcher' $source 'Get-Command java'
        Assert-Contains 'runtime smoke starts runtime safely' $source 'Start-Process'
        Assert-Contains 'runtime smoke hides runtime window' $source '-WindowStyle Hidden'
        Assert-Contains 'runtime smoke stops launched runtime' $source 'Stop-Process'
        Assert-Contains 'runtime smoke finds child listeners for cleanup' $source 'Get-NetTCPConnection'
        Assert-Contains 'runtime smoke avoids reserved PID cleanup variable' $source 'owningProcessId'
        Assert-Contains 'runtime smoke uses internal agent tools endpoint' $source '/internal/agent/tools'
        Assert-Contains 'runtime smoke invokes db evidence scan' $source 'db_evidence_scan:invoke'
        Assert-Contains 'runtime smoke invokes repo scan for path redaction proof' $source 'repo.scan:invoke'
        Assert-Contains 'runtime smoke invokes ops snapshot for manifest redaction proof' $source 'ops.snapshot:invoke'
        Assert-Contains 'runtime smoke invokes verify contract for report path redaction proof' $source 'verify.contract:invoke'
        Assert-Contains 'runtime smoke invokes agent db snapshot for live db-context proof' $source 'agent_db_snapshot'
        Assert-Contains 'runtime smoke invokes trace snapshot probe for live trace proof' $source 'trace_snapshot_probe'
        Assert-Contains 'runtime smoke sets toolbox admin token for db-context proof' $source 'AWX_ADMIN_TOKEN'
        Assert-Contains 'runtime smoke sets db-context base url for toolbox proof' $source 'AWX_AGENT_DB_CONTEXT_BASE_URL'
        Assert-Contains 'runtime smoke sets trace snapshot base url for toolbox proof' $source 'AWX_TRACE_SNAPSHOT_BASE_URL'
        Assert-Contains 'runtime smoke enables agent tools by env' $source 'AGENT_TOOLS_API_ENABLED'
        Assert-Contains 'runtime smoke records Supabase evidence gap' $source 'remoteSqlEvidenceNeeded'
        Assert-Contains 'runtime smoke scans response for secret patterns' $source 'secretPatternHits'
        Assert-Contains 'runtime smoke scans Supabase sb key prefixes' $source 'sb_(?:secret|publishable)_'
        Assert-Contains 'runtime smoke scans Supabase access token prefixes' $source 'sbp_[A-Za-z0-9_-]{10,}'
        Assert-Contains 'runtime smoke scans response for raw paths' $source 'windowsAbsPathHits'
        Assert-Contains 'runtime smoke scans response for raw URLs' $source 'rawUrlHits'
        Assert-Contains 'runtime smoke scans repo response for raw path keys' $source 'rawPathKeyHits'
        Assert-Contains 'runtime smoke scans artifact response for raw path keys' $source 'artifactPathKeyHits'
        Assert-Contains 'runtime smoke scans manifest response for raw path keys' $source 'resourcePathKeyHits'
        Assert-Contains 'runtime smoke scans ops snapshot for raw manifest resource keys' $source 'manifestResourceKeyHits'
        Assert-Contains 'runtime smoke scans verify contract for raw report path keys' $source 'reportPathKeyHits'
        Assert-Contains 'runtime smoke scans agent db snapshot for raw path keys' $source 'agentDbSnapshotPathKeyHits'
        Assert-Contains 'runtime smoke scans trace snapshot for raw path keys' $source 'traceSnapshotPathKeyHits'
        Assert-Contains 'runtime smoke emits evidence-needed states' $source 'evidence_needed'
        Assert-NotContains 'runtime smoke avoids reserved PID loop variable' $source '$pid'

        Assert-NotContains 'runtime smoke does not print admin token value' $source 'Write-Host $AdminToken'
        Assert-NotContains 'runtime smoke does not dump full environment' $source 'Get-ChildItem Env:'
        Assert-NotContains 'runtime smoke does not use broad base64 artifact output' $source 'ToBase64String'

        $tempOut = Join-Path ([IO.Path]::GetTempPath()) ('awx-db-evidence-runtime-test-' + [guid]::NewGuid().ToString('N'))
        $lines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
            -AssumeRunning `
            -BaseUrl 'http://127.0.0.1:1' `
            -BootTimeoutSec 1 `
            -TimeoutSec 1 `
            -OutputDir $tempOut 2>&1 |
            ForEach-Object { $_.ToString() }
        $invokeOutput = $lines -join "`n"
        Assert-Contains 'runtime smoke binds default root before endpoint probe' $invokeOutput 'assumeRunning=true'
        Assert-NotContains 'runtime smoke default root binding avoids Join-Path empty error' $invokeOutput 'Cannot bind argument to parameter'
        if (Test-Path -LiteralPath $tempOut) {
            Remove-Item -LiteralPath $tempOut -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
}

if ($Failures -gt 0) {
    Write-Host "[db-evidence-runtime-smoke-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[db-evidence-runtime-smoke-test][SUMMARY] failed=0'
exit 0
