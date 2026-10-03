#Requires -Version 5.1
# api_first_live_check.ps1 -- Codex plan9 live-verification wrapper (V1-V5).
#
# DEFAULT IS DRY-RUN. It prints the composed command plan and validates that
# every referenced tool exists; it sends nothing. Pass -Live to actually run
# (Codex's step - it requires the local server and spends real API calls).
#
#   powershell -NoProfile -File scripts\api_first_live_check.ps1 -DryRun
#   powershell -NoProfile -File scripts\api_first_live_check.ps1 -Scenario V2 -DryRun
#   powershell -NoProfile -File scripts\api_first_live_check.ps1 -Scenario All -Live
#
# Hard rules baked in: loopback base URL only (public site is never a target
# here); every send is followed by an api_call_budget.py record; an observed
# provider of "local" without a fallbackReason is reported as
# SILENT_LOCAL_FALLBACK (fail); a 401/403/429 record makes the budget tool
# exit 4 (STOP_AND_ROOT_CAUSE) which aborts the remaining scenarios.
param(
    [ValidateSet('V1','V2','V3','V4','V5','All')]
    [string]$Scenario = 'All',
    [switch]$DryRun,
    [switch]$Live,
    [string]$BaseUrl  = 'http://127.0.0.1:18180',
    [string]$Ledger   = 'var\codex-assist-plan9-20261002\API_CALLS.md',
    [string]$OutDir   = 'var\codex-assist-plan9-20261002\live-check'
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# --- loopback-only guard ---------------------------------------------------
$u = [Uri]$BaseUrl
if (-not (@('127.0.0.1','localhost','::1') -contains $u.Host)) {
    Write-Host "FAIL base must be loopback (got $BaseUrl); the public site is out of scope."
    exit 2
}
$dry = -not $Live          # default dry-run unless -Live given

$modelsJson = Join-Path $OutDir 'models.json'
$budgetPy   = 'scripts\api_call_budget.py'

# Scenario plans: each step = @{ name; why; cmds = @(...) ; judge = scriptblock? }
$plans = [ordered]@{
    V1 = @{
        why = 'auto normal question -> api-first primary should be cloud A'
        cmds = @(
            "python -B scripts\test_model_policy.py resolve --purpose auto --prompt-file var\codex-assist-plan9-20261002\live-check\v1-prompt.txt --catalog $modelsJson --base $BaseUrl",
            "node scripts\chat_practice_browser.js --prompts var\codex-assist-plan9-20261002\live-check\v1-prompt.txt --base $BaseUrl --max-calls 1 --run plan9-v1",
            "python -B $budgetPy record --ledger $Ledger --route <observed-route> --http <observed-code> --result <ok|fail> --catalog $modelsJson"
        )
        prompt = '[plan9-v1] 한국어로 계절풍과 무역풍의 차이를 두 문장으로 설명해줘.'
    }
    V2 = @{
        why = 'auto + RAG canary question (은하수-7)'
        cmds = @(
            "node scripts\chat_practice_browser.js --prompt `"[plan9-v2] 은하수-7 문서에서 핵심 수치 하나만 인용해줘.`" --base $BaseUrl --max-calls 1 --run plan9-v2",
            "python -B $budgetPy record --ledger $Ledger --route <observed-route> --http <observed-code> --result <ok|fail> --catalog $modelsJson"
        )
    }
    V3 = @{
        why = 'explicit luna (chatgpt-oauth) selection - never an auto candidate'
        cmds = @(
            "node scripts\chat_rag_golden_browser.js --base $BaseUrl --model chatgpt-oauth:gpt-5.6-luna --max-sends 1",
            "python -B $budgetPy record --ledger $Ledger --route chatgpt-oauth:gpt-5.6-luna --http <observed-code> --result <ok|fail>"
        )
    }
    V4 = @{
        why = 'V1 repeated 3x (budget accounting + stable route choice)'
        cmds = @(
            "node scripts\chat_practice_browser.js --prompt `"[plan9-v4] 태풍의 눈이 고요한 이유를 한 문장으로.`" --base $BaseUrl --max-calls 3 --run plan9-v4",
            "python -B $budgetPy record --ledger $Ledger --route <observed-route> --http <observed-code> --result <ok|fail> --catalog $modelsJson --note v4-x3"
        )
    }
    V5 = @{
        why = 'model list must come from the catalog; no hardcoded ids; console errors 0'
        cmds = @(
            "node --check main\resources\static\js\model-strategy.js",
            "python -B scripts\model_name_guess_scan.py --baseline var\codex-assist-plan9-20261002\name-guess-baseline.json --root main --format json",
            "node scripts\chat_rag_golden_browser.js --base $BaseUrl --max-sends 0 --only modelList"
        )
    }
}

$sel = if ($Scenario -eq 'All') { @('V1','V2','V3','V4','V5') } else { @($Scenario) }
$missing = New-Object System.Collections.Generic.List[string]
$report  = New-Object System.Collections.Generic.List[object]
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# step 0 (shared): catalog snapshot used by every scenario
$catalogCmd = "Invoke-WebRequest -UseBasicParsing $BaseUrl/api/chat/models -OutFile $modelsJson"
if ($dry) {
    Write-Host "# api_first_live_check DRY-RUN ($($sel -join ',')) base=$BaseUrl"
    Write-Host "step0 catalog: $catalogCmd"
} else {
    Invoke-WebRequest -UseBasicParsing "$BaseUrl/api/chat/models" -OutFile $modelsJson
    & python -B $budgetPy init --ledger $Ledger --cap 25 --force | Out-Null
}

foreach ($s in $sel) {
    $plan = $plans[$s]
    Write-Host ""
    Write-Host "== $s : $($plan.why)"
    if ($plan.ContainsKey('prompt')) {
        $pf = Join-Path $OutDir "$($s.ToLower())-prompt.txt"
        if ($dry) { Write-Host "  write prompt -> $pf" }
        else { Set-Content -Encoding UTF8 $pf -Value $plan.prompt }
    }
    foreach ($c in $plan.cmds) {
        $tool = ($c -split ' ')[0..2] -join ' '
        $script = ($c -split ' ' | Where-Object { $_ -match '^scripts\\' } | Select-Object -First 1)
        if ($script -and -not (Test-Path $script)) { $missing.Add("$s : $script") }
        if ($dry) { Write-Host "  $c"; continue }
        Write-Host "  > $c"
        if ($c -match '<observed-') { Write-Host "    SKIP(placeholders need observed values - fill from the send step's JSON)"; continue }
        Invoke-Expression $c
        $report.Add([pscustomobject]@{ scenario=$s; cmd=$c; exit=$LASTEXITCODE })
        if ($LASTEXITCODE -eq 4) { Write-Host "STOP_AND_ROOT_CAUSE from budget ledger - aborting remaining scenarios"; break }
    }
}

# silent-local-fallback judge note (live only): observed provider local without
# fallbackReason is a FAIL by the Codex directive.
if (-not $dry) {
    Write-Host ""
    Write-Host "judge: for each send check observed provider - 'local' with no fallbackReason = SILENT_LOCAL_FALLBACK FAIL"
    $report | ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $OutDir 'report.json')
}

if ($missing.Count) {
    Write-Host ""
    Write-Host "MISSING tools (fix path or install before -Live):"
    $missing | Sort-Object -Unique | ForEach-Object { Write-Host "  $_" }
}
exit 0
