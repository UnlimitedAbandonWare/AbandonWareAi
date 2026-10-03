#Requires -Version 5.1
# settings_defaults_assist.ps1 -- bundle runner for the Devin
# settings-defaults assist tools (all read-only, new files only).
#
#   powershell -NoProfile -File scripts\settings_defaults_assist.ps1 -Mode baseline
#   powershell -NoProfile -File scripts\settings_defaults_assist.ps1 -Mode snapshot -Label pre-codex
#   powershell -NoProfile -File scripts\settings_defaults_assist.ps1 -Mode static
#   powershell -NoProfile -File scripts\settings_defaults_assist.ps1 -Mode grade -AnswerDir <dir>
#   powershell -NoProfile -File scripts\settings_defaults_assist.ps1 -Mode budget
#   powershell -NoProfile -File scripts\settings_defaults_assist.ps1 -Mode post
#
# Modes:
#   baseline  D0 baseline.json (HEAD, git status, file digests, chat.js hash)
#   snapshot  db snapshot --label <Label> (default: timestamped label)
#   static    S1..S12 check against baseline.json
#   grade     answer_grade grade-all over -AnswerDir (QG/QR/QW/QS files)
#   budget    Codex-ledger audit report (read-only; UNKNOWN stays UNKNOWN)
#   post      snapshot post-codex -> diff pre/post -> static -> budget
#             -> grade (only when a Codex answers dir is found)
#
# Each run logs command + exit code + output file to runs-index.ndjson under
# -OutDir and to -LedgerDir\runs.ndjson when that dir exists. Exit code is
# the last step's; per-step exits live in the log either way.
param(
    [ValidateSet('baseline','snapshot','static','grade','budget','post')]
    [string]$Mode = 'baseline',
    [string]$Label = '',
    [string]$AnswerDir = '',
    [string]$Question = '',
    [string]$OutDir = 'var\settings-defaults-assist',
    [string]$LedgerDir = '',
    [string]$Baseline = ''
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$py = 'python'
$snap = 'scripts\settings_defaults_db_snapshot.py'
$stat = 'scripts\settings_defaults_static_check.py'
$grade = 'scripts\settings_defaults_answer_grade.py'
$budget = 'scripts\settings_defaults_budget_meter.py'
$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
if (-not $Label) { $Label = "snap-$stamp" }
if (-not $Baseline) { $Baseline = Join-Path $OutDir 'baseline.json' }
if (-not $LedgerDir) {
    $cand = Get-ChildItem -Directory 'data\agent-handoff' -Filter 'devin-settings-defaults-assist-*' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cand) { $LedgerDir = $cand.FullName }
}
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $OutDir 'runs') | Out-Null

$script:results = New-Object System.Collections.Generic.List[object]
function Invoke-Step {
    param([string]$Name, [string]$CmdLine, [string]$OutFile = '')
    $log = Join-Path $OutDir ("runs\{0}-{1}.log" -f $Name, $stamp)
    Write-Host "== $Name : $CmdLine"
    $output = & cmd /c $CmdLine 2>&1
    $code = $LASTEXITCODE
    $output | Out-File -Encoding UTF8 $log
    $rec = [ordered]@{ tsUtc = (Get-Date).ToUniversalTime().ToString('o')
        mode = $Mode; step = $Name; cmd = $CmdLine; exit = $code
        log = $log; out = $OutFile }
    $script:results.Add([pscustomobject]$rec)
    $line = ($rec | ConvertTo-Json -Compress)
    Add-Content -Encoding UTF8 (Join-Path $OutDir 'runs-index.ndjson') $line
    if ($LedgerDir -and (Test-Path $LedgerDir)) {
        Add-Content -Encoding UTF8 (Join-Path $LedgerDir 'runs.ndjson') $line
    }
    Write-Host "   exit=$code log=$log"
    return $code
}

$lastExit = 0
switch ($Mode) {
    'baseline' {
        $lastExit = Invoke-Step 'baseline' "$py -B $stat baseline --out `"$Baseline`" --root ." $Baseline
    }
    'snapshot' {
        $f = Join-Path $OutDir "snapshot-$Label.json"
        $lastExit = Invoke-Step 'snapshot' "$py -B $snap snapshot --label `"$Label`" --out-dir `"$OutDir`"" $f
    }
    'static' {
        $f = Join-Path $OutDir "static-$stamp.json"
        $lastExit = Invoke-Step 'static' "$py -B $stat check --baseline `"$Baseline`" --root . --out `"$f`"" $f
    }
    'grade' {
        if (-not $AnswerDir) { Write-Host 'grade mode needs -AnswerDir'; exit 2 }
        $f = Join-Path $OutDir "grade-$stamp.json"
        if ($Question) {
            $ans = Get-ChildItem $AnswerDir -Filter "$Question*" | Select-Object -First 1
            if (-not $ans) { Write-Host "no answer file for $Question in $AnswerDir"; exit 2 }
            $lastExit = Invoke-Step 'grade' "$py -B $grade grade --question $Question --answer-file `"$($ans.FullName)`"" $f
        } else {
            $lastExit = Invoke-Step 'grade' "$py -B $grade grade-all --answers-dir `"$AnswerDir`" --out `"$f`"" $f
        }
    }
    'budget' {
        $f = Join-Path $OutDir "budget-$stamp.json"
        $lastExit = Invoke-Step 'budget' "$py -B $budget report --out `"$f`"" $f
    }
    'post' {
        $pre = Join-Path $OutDir 'snapshot-pre-codex.json'
        $postf = Join-Path $OutDir 'snapshot-post-codex.json'
        Invoke-Step 'snapshot' "$py -B $snap snapshot --label post-codex --out-dir `"$OutDir`"" $postf | Out-Null
        if ((Test-Path $pre) -and (Test-Path $postf)) {
            $diff = Join-Path $OutDir 'diff-pre-post.json'
            Invoke-Step 'diff' "$py -B $snap diff `"$pre`" `"$postf`" --out `"$diff`"" $diff | Out-Null
        } else {
            Write-Host '   diff skipped: snapshot file(s) missing'
        }
        $sf = Join-Path $OutDir 'static-post-codex.json'
        Invoke-Step 'static' "$py -B $stat check --baseline `"$Baseline`" --root . --out `"$sf`"" $sf | Out-Null
        $bf = Join-Path $OutDir 'budget-post-codex.json'
        Invoke-Step 'budget' "$py -B $budget report --out `"$bf`"" $bf | Out-Null
        # grade only when a Codex answers dir exists - never invent one
        $answers = Get-ChildItem -Directory 'data\agent-handoff' -Recurse -Filter 'answers' -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match 'codex-settings-defaults' } | Select-Object -First 1
        if (-not $answers) {
            $answers = Get-ChildItem 'data\agent-handoff\codex-settings-defaults-*','data\agent-handoff\codex-autonomy\codex-settings-defaults-*' -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { Join-Path $_.FullName 'experiment-results' } |
                Where-Object { Test-Path $_ } | Select-Object -First 1
        }
        if ($answers -and (Get-ChildItem $answers.FullName -Filter '*.txt' -ErrorAction SilentlyContinue)) {
            $gf = Join-Path $OutDir 'grade-post-codex.json'
            Invoke-Step 'grade' "$py -B $grade grade-all --answers-dir `"$($answers.FullName)`" --out `"$gf`"" $gf | Out-Null
        } else {
            Write-Host '   grade skipped: no Codex answers dir (NOT_RUN)'
            $rec = [pscustomobject]@{ tsUtc = (Get-Date).ToUniversalTime().ToString('o')
                mode = 'post'; step = 'grade'; cmd = ''; exit = 'NOT_RUN'
                log = ''; out = '' }
            $script:results.Add($rec)
            $line = ($rec | ConvertTo-Json -Compress)
            Add-Content -Encoding UTF8 (Join-Path $OutDir 'runs-index.ndjson') $line
            if ($LedgerDir -and (Test-Path $LedgerDir)) {
                Add-Content -Encoding UTF8 (Join-Path $LedgerDir 'runs.ndjson') $line
            }
        }
        $lastExit = 0
    }
}

$summaryLine = ($script:results | ForEach-Object { "$($_.step)=$($_.exit)" }) -join ' '
Write-Host "SUMMARY $summaryLine"
exit $lastExit
