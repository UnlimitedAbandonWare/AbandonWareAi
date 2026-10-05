<#
.SYNOPSIS
  One deep side-question to agy at Flash's top effort (L3 helper).

.DESCRIPTION
  Resolves model via scripts\agy_model_latest.ps1 -Pick and effort via
  -FlashMaxEffort (highest tier the flash family actually offers - 'high'
  today; xhigh/max are Pro-only). Runs a single `agy --print` with the
  question, writes the answer to var\agy-depth\deep-<KST>.md, and appends
  the call to var\agy-depth\calls.jsonl. Session/day cap comes from
  configs\agy-depth.json (l3.deepCallsPerSession, default 3).

  If nested agy execution fails (auth/lock), this tool is unused for the
  session and the depth router falls back to in-thread passes.
#>
param(
    [Parameter(Mandatory = $true)][string]$Question,
    [string]$Files = "",
    [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$depthDir = Join-Path $root 'var\agy-depth'
$callsLog = Join-Path $depthDir 'calls.jsonl'
$cfgPath = Join-Path $root 'configs\agy-depth.json'

$cap = 3
if (Test-Path -LiteralPath $cfgPath) {
    try {
        $cfg = Get-Content -LiteralPath $cfgPath -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($cfg.l3.deepCallsPerSession) { $cap = [int]$cfg.l3.deepCallsPerSession }
    } catch { }
}

# Per-day cap: count calls already logged for today (KST date prefix).
$kst = [TimeZoneInfo]::ConvertTimeBySystemTimeZoneId((Get-Date).ToUniversalTime(), 'Korea Standard Time')
$today = $kst.ToString('yyyy-MM-dd')
$used = 0
if (Test-Path -LiteralPath $callsLog) {
    $used = @(Get-Content -LiteralPath $callsLog -Encoding UTF8 |
              Where-Object { $_ -match ('"ts":"' + $today) }).Count
}
if ($used -ge $cap) {
    Write-Output "[agy-deep] cap reached ($used/$cap today) - answer in-thread instead"
    exit 2
}

$picker = Join-Path $root 'scripts\agy_model_latest.ps1'
$model = (& powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $picker -Pick)
if (-not $model) { $model = $env:AWX_AGY_MODEL }
$effort = (& powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $picker -FlashMaxEffort)
if (-not $effort) { $effort = 'high' }

$agy = $env:AGY_EXE
if (-not $agy -or -not (Test-Path -LiteralPath $agy)) {
    $agy = Join-Path $env:LOCALAPPDATA 'agy\bin\agy.exe'
}
if (-not (Test-Path -LiteralPath $agy)) {
    Write-Output "[agy-deep] agy.exe not found"
    exit 3
}

$prompt = $Question
if ($Files -and $Files.Trim()) {
    $prompt = ("Read these files first (bounded reads): " + $Files.Trim() +
               "`n`nQuestion: " + $Question)
}

New-Item -ItemType Directory -Force $depthDir | Out-Null
$stamp = $kst.ToString('yyyyMMdd-HHmmss')
$outFile = Join-Path $depthDir "deep-$stamp.md"

$t0 = Get-Date
$prev = Get-Location
try {
    Set-Location -LiteralPath $root
    $result = & $agy --output-format stream-json --model $model --effort $effort --print $prompt 2>&1
    $exitCode = $LASTEXITCODE
} finally {
    Set-Location $prev
}
$elapsed = [math]::Round(((Get-Date) - $t0).TotalSeconds, 1)

# Keep the readable response + final usage in the .md; raw events go to .jsonl.
$result | Out-File -LiteralPath "$outFile.jsonl" -Encoding utf8
$final = $null
foreach ($ln in $result) {
    if ($ln -match '^\{') {
        try { $j = $ln | ConvertFrom-Json; if ($j.event -eq 'result') { $final = $j } } catch { }
    }
}
$body = @()
$body += "# agy-deep $stamp"
$body += "model=$model effort=$effort exit=$exitCode elapsed_s=$elapsed"
if ($final) {
    $u = $final.result.usage
    $body += "usage_total=$($u.total_tokens) in=$($u.input_tokens) out=$($u.output_tokens) cache_read=$($u.cache_read_tokens) status=$($final.result.status)"
}
$body += ""
$body += "## question"
$body += $Question
$body += ""
$body += "## response"
if ($final -and $final.result.response) { $body += [string]$final.result.response }
else { $body += ($result -join "`n") }
[IO.File]::WriteAllText($outFile, ($body -join "`n"), (New-Object Text.UTF8Encoding($false)))

$rec = @{ ts = $kst.ToString('yyyy-MM-ddTHH:mm:ssKST'); tool = 'agy_deep';
          model = $model; effort = $effort; exitCode = $exitCode;
          elapsed_s = $elapsed; out = ($outFile -replace [regex]::Escape($root + '\'), '');
          qSha = (([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($Question)))).Replace('-', '').Substring(0, 12)) }
($rec | ConvertTo-Json -Compress) | Out-File -LiteralPath $callsLog -Append -Encoding utf8

Write-Output "[agy-deep] model=$model effort=$effort exit=$exitCode elapsed=${elapsed}s out=$outFile"
exit $exitCode
