<#
.SYNOPSIS
  agy latest-model probe for Start-Agy-CLI.bat (Mode B, 2026-10-04).

.DESCRIPTION
  Runs `agy models` with a bounded timeout, reads the user's current model
  override label from the newest cli.log line
  `Propagating selected model override to backend: label="..."`, and prints
  exactly one status line:

    [agy-model] LATEST_OK <current-id> update=<OK|STALE|UNKNOWN>
    [agy-model] NEWER_AVAILABLE <newer-id> (current: <label>) update=<...>
    [agy-model] CHECK_SKIPPED <reason> update=<...>

  Same family + same tier only: current gemini-X.Y-flash-high compares only
  against other gemini-*.flash-high ids. Version compare is numeric
  (3.10 > 3.9 > 3.8), never string sort.

  Modes:
    (default)  print the status line
    -Pick      print ONLY the best same-family+tier id (for launcher capture);
               empty output on any failure
    -SelfTest  fixed-sample logic checks; exit 0 pass / 1 fail

  Never blocks the launcher: every failure path prints CHECK_SKIPPED (or
  nothing under -Pick) and exits 0.
#>
param(
    [switch]$Pick,
    [switch]$SelfTest,
    [int]$TimeoutSeconds = 5
)

$ErrorActionPreference = 'Stop'

function Get-AgyExePath {
    if ($env:AGY_EXE -and (Test-Path -LiteralPath $env:AGY_EXE -PathType Leaf)) { return $env:AGY_EXE }
    $local = Join-Path $env:LOCALAPPDATA 'agy\bin\agy.exe'
    if (Test-Path -LiteralPath $local -PathType Leaf) { return $local }
    $cmd = Get-Command agy.exe -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    return $null
}

function Get-AgyUpdateState {
    # Built-in updater evidence -> OK | STALE | UNKNOWN (never throws).
    try {
        $base = Join-Path $env:USERPROFILE '.gemini\antigravity-cli'
        $statusFile = Join-Path $base 'updater\update_status.json'
        $checkFile = Join-Path $base 'last_check.timestamp'
        if (-not (Test-Path -LiteralPath $statusFile -PathType Leaf)) { return 'UNKNOWN' }
        if (-not (Test-Path -LiteralPath $checkFile -PathType Leaf)) { return 'UNKNOWN' }
        $age = (Get-Date) - (Get-Item -LiteralPath $checkFile).LastWriteTime
        if ($age.TotalHours -gt 24) { return 'STALE' }
        $j = Get-Content -LiteralPath $statusFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($j.success -eq $true) { return 'OK' }
        return 'STALE'
    } catch { return 'UNKNOWN' }
}

function ConvertFrom-ModelListText {
    param([string]$Text)
    $rows = @()
    if ([string]::IsNullOrWhiteSpace($Text)) { return ,$rows }
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -match '^(?<id>\S+)\t(?<label>.+?)\s*$') {
            $rows += @{ id = $Matches['id']; label = $Matches['label'].Trim() }
        }
    }
    return ,$rows
}

function Get-ModelRows {
    param([string]$Exe, [int]$TimeoutSec)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Exe
    $psi.Arguments = 'models'
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $p = $null
    try {
        $p = [System.Diagnostics.Process]::Start($psi)
        $outTask = $p.StandardOutput.ReadToEndAsync()
        [void]$p.StandardError.ReadToEndAsync()   # drain stderr so agy never blocks
        if (-not $p.WaitForExit($TimeoutSec * 1000)) {
            try { $p.Kill() } catch {}
            return $null
        }
        if ($p.ExitCode -ne 0) { return $null }
        $rows = ConvertFrom-ModelListText -Text $outTask.Result
        if ($rows.Count -eq 0) { return $null }
        return ,$rows
    } catch { return $null }
    finally { if ($p) { $p.Dispose() } }
}

function Get-CurrentModelLabel {
    # Last "selected model override" label from the newest cli log; $null = UNKNOWN.
    try {
        $base = Join-Path $env:USERPROFILE '.gemini\antigravity-cli'
        $candidates = @()
        $cli = Join-Path $base 'cli.log'
        if (Test-Path -LiteralPath $cli -PathType Leaf) { $candidates += (Get-Item -LiteralPath $cli) }
        $logDir = Join-Path $base 'log'
        if (Test-Path -LiteralPath $logDir -PathType Container) {
            $candidates += Get-ChildItem -LiteralPath $logDir -File -Filter '*.log' -ErrorAction SilentlyContinue
        }
        if ($candidates.Count -eq 0) { return $null }
        $newest = $candidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $hit = Select-String -LiteralPath $newest.FullName `
            -Pattern 'selected model override to backend: label="([^"]+)"' -ErrorAction SilentlyContinue |
            Select-Object -Last 1
        if ($hit -and $hit.Matches.Count -gt 0) { return $hit.Matches[0].Groups[1].Value }
        return $null
    } catch { return $null }
}

function ConvertTo-ModelInfo {
    param([string]$Id)
    # gemini-3.8-flash-high -> family=gemini tier=flash-high maj=3 min=8
    # ids without an M.m version (claude-sonnet-4-6, gpt-oss-120b-medium) -> $null
    if ($Id -match '^(?<family>[a-z][a-z0-9]*)-(?<maj>\d+)\.(?<min>\d+)-(?<tier>.+)$') {
        return @{ family = $Matches['family']; maj = [int]$Matches['maj']; min = [int]$Matches['min']; tier = $Matches['tier'] }
    }
    return $null
}

function Get-BestSameTier {
    param([array]$Rows, [hashtable]$Cur)
    $best = $null
    foreach ($r in $Rows) {
        $info = ConvertTo-ModelInfo -Id $r.id
        if ($null -eq $info) { continue }
        if ($info.family -ne $Cur.family -or $info.tier -ne $Cur.tier) { continue }
        $ver = ($info.maj * 1000) + $info.min
        if ($null -eq $best -or $ver -gt $best.ver) {
            $best = @{ id = $r.id; label = $r.label; ver = $ver }
        }
    }
    return $best
}

function Resolve-ModelStatus {
    param([array]$Rows, [string]$CurrentLabel)
    if (-not $Rows -or $Rows.Count -eq 0) {
        return @{ kind = 'CHECK_SKIPPED'; reason = 'model-list-empty' }
    }
    $curId = $null
    if ($CurrentLabel) {
        $hit = $Rows | Where-Object { $_.label -eq $CurrentLabel } | Select-Object -First 1
        if ($hit) { $curId = $hit.id }
    }
    if (-not $curId) {
        return @{ kind = 'CHECK_SKIPPED'; reason = 'current-model-unknown' }
    }
    $info = ConvertTo-ModelInfo -Id $curId
    if ($null -eq $info) {
        return @{ kind = 'CHECK_SKIPPED'; reason = "non-versioned-model $curId" }
    }
    $best = Get-BestSameTier -Rows $Rows -Cur $info
    if ($null -eq $best) {
        return @{ kind = 'CHECK_SKIPPED'; reason = 'no-same-tier-rows' }
    }
    $curVer = ($info.maj * 1000) + $info.min
    if ($best.ver -gt $curVer) {
        return @{ kind = 'NEWER_AVAILABLE'; id = $best.id; label = $best.label; currentLabel = $CurrentLabel }
    }
    return @{ kind = 'LATEST_OK'; id = $curId; label = $CurrentLabel }
}

function Invoke-SelfTest {
    $pass = 0; $fail = 0
    $sampleText = @"
gemini-3.8-flash-high`tGemini 3.8 Flash (High)
gemini-3.9-flash-high`tGemini 3.9 Flash (High)
gemini-3.10-flash-high`tGemini 3.10 Flash (High)
gemini-3.9-flash-low`tGemini 3.9 Flash (Low)
gemini-3.1-pro-high`tGemini 3.1 Pro (High)
claude-sonnet-4-6`tClaude Sonnet 4.6 (Thinking)
gpt-oss-120b-medium`tGPT-OSS 120B (Medium)
"@
    $rows = ConvertFrom-ModelListText -Text $sampleText

    $t = 'parse-7-rows'
    if ($rows.Count -eq 7) { $pass++ } else { $fail++; Write-Output "[agy-model][selftest] FAIL $t got=$($rows.Count)" }

    $t = 'numeric-compare-3.10>3.9>3.8'
    $info = ConvertTo-ModelInfo -Id 'gemini-3.10-flash-high'
    if ($info -and $info.maj -eq 3 -and $info.min -eq 10 -and $info.tier -eq 'flash-high') { $pass++ } else { $fail++; Write-Output "[agy-model][selftest] FAIL $t" }

    $t = 'pick-newest-same-tier'
    $r = Resolve-ModelStatus -Rows $rows -CurrentLabel 'Gemini 3.8 Flash (High)'
    if ($r.kind -eq 'NEWER_AVAILABLE' -and $r.id -eq 'gemini-3.10-flash-high') { $pass++ } else { $fail++; Write-Output "[agy-model][selftest] FAIL $t kind=$($r.kind) id=$($r.id)" }

    $t = 'tier-filter-flash-low-excluded'
    $cur = ConvertTo-ModelInfo -Id 'gemini-3.8-flash-high'
    $best = Get-BestSameTier -Rows $rows -Cur $cur
    if ($best -and $best.id -eq 'gemini-3.10-flash-high') { $pass++ } else { $fail++; Write-Output "[agy-model][selftest] FAIL $t id=$($best.id)" }

    $t = 'latest-ok-when-current-is-top'
    $r = Resolve-ModelStatus -Rows $rows -CurrentLabel 'Gemini 3.10 Flash (High)'
    if ($r.kind -eq 'LATEST_OK' -and $r.id -eq 'gemini-3.10-flash-high') { $pass++ } else { $fail++; Write-Output "[agy-model][selftest] FAIL $t kind=$($r.kind)" }

    $t = 'empty-list-check-skipped'
    $r = Resolve-ModelStatus -Rows @() -CurrentLabel 'Gemini 3.8 Flash (High)'
    if ($r.kind -eq 'CHECK_SKIPPED') { $pass++ } else { $fail++; Write-Output "[agy-model][selftest] FAIL $t" }

    $t = 'non-versioned-current-skipped'
    $r = Resolve-ModelStatus -Rows $rows -CurrentLabel 'Claude Sonnet 4.6 (Thinking)'
    if ($r.kind -eq 'CHECK_SKIPPED' -and $r.reason -match 'non-versioned') { $pass++ } else { $fail++; Write-Output "[agy-model][selftest] FAIL $t kind=$($r.kind)" }

    $t = 'unknown-label-skipped'
    $r = Resolve-ModelStatus -Rows $rows -CurrentLabel 'No Such Model'
    if ($r.kind -eq 'CHECK_SKIPPED' -and $r.reason -eq 'current-model-unknown') { $pass++ } else { $fail++; Write-Output "[agy-model][selftest] FAIL $t" }

    $t = 'update-state-shape'
    $u = Get-AgyUpdateState
    if ($u -in @('OK', 'STALE', 'UNKNOWN')) { $pass++ } else { $fail++; Write-Output "[agy-model][selftest] FAIL $t" }

    Write-Output "[agy-model][selftest] pass=$pass fail=$fail"
    if ($fail -eq 0) { exit 0 } else { exit 1 }
}

if ($SelfTest) { Invoke-SelfTest }

$update = Get-AgyUpdateState
$exe = Get-AgyExePath
if (-not $exe) {
    if (-not $Pick) { Write-Output "[agy-model] CHECK_SKIPPED agy.exe-not-found update=$update" }
    exit 0
}
$rows = Get-ModelRows -Exe $exe -TimeoutSec $TimeoutSeconds
if ($null -eq $rows) {
    if (-not $Pick) { Write-Output "[agy-model] CHECK_SKIPPED agy-models-timeout-or-failed update=$update" }
    exit 0
}
$label = Get-CurrentModelLabel
$res = Resolve-ModelStatus -Rows $rows -CurrentLabel $label

if ($Pick) {
    if ($res.kind -ne 'CHECK_SKIPPED') { Write-Output $res.id }
    exit 0
}

$curTag = [string]([char]0xD604) + [string]([char]0xC7AC)   # "현재"
switch ($res.kind) {
    'LATEST_OK'       { Write-Output "[agy-model] LATEST_OK $($res.id) update=$update" }
    'NEWER_AVAILABLE' { Write-Output "[agy-model] NEWER_AVAILABLE $($res.id) ($curTag $($res.currentLabel)) update=$update" }
    default           { Write-Output "[agy-model] CHECK_SKIPPED $($res.reason) update=$update" }
}
exit 0