$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$SmokeScript = Join-Path $ScriptsRoot 'smoke_local_llm_generation.ps1'
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
    Write-Host "[local-llm-smoke-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[local-llm-smoke-test][FAIL] $Name :: $Message"
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
    Assert-True 'local llm smoke script exists' (Test-Path -LiteralPath $SmokeScript) 'missing scripts\smoke_local_llm_generation.ps1'
    if (Test-Path -LiteralPath $SmokeScript) {
        $source = Get-Content -Raw -LiteralPath $SmokeScript
        Assert-Contains 'smoke supports static mode' $source 'StaticOnly'
        Assert-Contains 'smoke emits awx prefix' $source '[AWX][local-llm-smoke]'
        Assert-Contains 'smoke checks metadata route' $source '/models'
        Assert-Contains 'smoke checks OpenAI chat route' $source '/chat/completions'
        Assert-Contains 'smoke can compare native ollama route' $source '/api/chat'
        Assert-Contains 'smoke disables qwen thinking in native route' $source 'think = $false'
        Assert-Contains 'smoke records body hash only' $source 'bodyHash'
        Assert-Contains 'smoke records content length' $source 'contentLength'
        Assert-Contains 'smoke records thinking length' $source 'thinkingLength'
        Assert-Contains 'smoke records blank response' $source 'blankResponse'
        Assert-Contains 'smoke records endpoint host only' $source 'endpointHost'
        Assert-Contains 'smoke hashes model name' $source 'modelHash'
        Assert-Contains 'smoke hashes prompt' $source 'promptHash'
        Assert-Contains 'smoke supports native first mode' $source 'NativeFirst'
        Assert-Contains 'smoke can run native before OpenAI route' $source 'Invoke-NativeChat'
        Assert-Contains 'smoke scores route attempts' $source 'Get-AttemptScore'
        Assert-Contains 'smoke records attempt scores' $source 'attemptScores'
        Assert-Contains 'smoke recommends a safe route' $source 'recommendedRoute'
        Assert-Contains 'smoke exposes debug trigger' $source 'debugTrigger'
        Assert-Contains 'smoke records negative signal count' $source 'negativeSignalCount'
        Assert-Contains 'smoke appends redacted history' $source 'Append-SmokeHistory'
        Assert-Contains 'smoke computes cumulative signal stats' $source 'Get-CumulativeSignalStats'
        Assert-Contains 'smoke records cumulative signals' $source 'cumulativeSignals'
        Assert-Contains 'smoke records history path' $source 'historyPath'
        Assert-Contains 'smoke records threshold exceeded flag' $source 'thresholdExceeded'
        Assert-Contains 'smoke scans high confidence secret hits' $source 'secretPatternHits'
        Assert-Contains 'smoke never prints auth value by design' $source 'Authorization'
        Assert-NotContains 'smoke does not dump full environment' $source 'Get-ChildItem Env:'
        Assert-NotContains 'smoke does not print raw response body' $source 'Write-Host $raw'
        Assert-NotContains 'smoke does not return large base64' $source 'base64'

        $tempOut = Join-Path ([IO.Path]::GetTempPath()) ('awx-local-llm-smoke-test-' + [guid]::NewGuid().ToString('N'))
        $lines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
            -StaticOnly `
            -OutputDir $tempOut 2>&1 |
            ForEach-Object { $_.ToString() }
        $invokeOutput = $lines -join "`n"
        Assert-Contains 'static smoke exits cleanly' $invokeOutput '[AWX][local-llm-smoke] staticOnly=true'
        Assert-Contains 'static smoke reports script path' $invokeOutput 'script='
        Assert-NotContains 'static smoke does not print raw model' $invokeOutput 'qwen3:8b'
        Assert-NotContains 'static smoke does not print raw prompt' $invokeOutput 'AWX_OK'
        if (Test-Path -LiteralPath $tempOut) {
            Remove-Item -LiteralPath $tempOut -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
}

if ($Failures -gt 0) {
    Write-Host "[local-llm-smoke-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[local-llm-smoke-test][SUMMARY] failed=0'
exit 0
