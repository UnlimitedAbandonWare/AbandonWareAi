#requires -Version 5.1
<#
.SYNOPSIS
  RTX 3090 health watch (DESKTOP-M5NOV6K) - read-only anomaly detector.

.DESCRIPTION
  Collects whatever is available each run:
    - nvidia-smi query (temp, power.draw/limit, pstate, clocks, util, fan, ecc, clock event reasons)
    - Ollama /api/version latency + success on ports 11434/11435
    - System event-log summary for nvlddmkm / Display / Kernel-Power(41)

  Writes var/debug/rtx3090-watch/latest.json every run.
  On anomaly also writes var/debug/rtx3090-watch/alert-<timestamp>.json and a
  short data/agent-handoff/rtx3090-watch/LATEST.md so any agent sees it.

  Anomaly signals: smi failure, lost/reset/Xid text, hw_slowdown or
  hw_power_brake event reason Active, new driver-error events,
  Ollama consecutive timeouts.

  Cause is UNCONFIRMED. Hypotheses are emitted with confidence=low only:
  power_peak_or_limit / psu_or_wiring / driver / unknown. Never assert one.
  Context: user is running MSI Afterburner power limit ~90% experiment
  (power_limit_first_then_consider_psu). This script NEVER changes power
  limit, clocks, PSU anything, and never blocks or reduces GPU load.

  Exit codes: 0 = ok, 3 = anomaly observed (alert written or suppressed-repeat),
  1 = script error, 2 = usage.
#>
[CmdletBinding()]
param(
  [string]$Root = '',
  [int]$IntervalSeconds = 0,          # 0 = single pass; >0 = loop forever
  [string]$OllamaPorts = '11434,11435',   # comma/space separated
  [int]$OllamaTimeoutSec = 2,
  [int]$OllamaFailThreshold = 2,      # consecutive unhealthy probes -> anomaly
  [int]$EventLookbackHours = 24,
  [int]$AlertCooldownMinutes = 60,    # same anomaly signature -> suppress new alert file
  [string]$TargetGpuMatch = '3090'
)

$ErrorActionPreference = 'Continue'
$script:SchemaVersion = 'awx.rtx3090-watch.v1'

$RootResolved = $Root
if ([string]::IsNullOrWhiteSpace($RootResolved)) {
  if ($PSScriptRoot) { $RootResolved = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path }
  else { $RootResolved = 'C:\AbandonWare\demo-1\demo-1\src' }
}
if (-not (Test-Path -LiteralPath (Join-Path $RootResolved 'AGENTS.md'))) {
  $cand = 'C:\AbandonWare\demo-1\demo-1\src'
  if (Test-Path -LiteralPath (Join-Path $cand 'AGENTS.md')) { $RootResolved = $cand }
}

$WatchDir   = Join-Path $RootResolved 'var\debug\rtx3090-watch'
$HandoffDir = Join-Path $RootResolved 'data\agent-handoff\rtx3090-watch'
$LatestPath = Join-Path $WatchDir 'latest.json'
$HandoffMd  = Join-Path $HandoffDir 'LATEST.md'

function UtcNow { return (Get-Date).ToUniversalTime() }
function Iso([datetime]$dt) { return $dt.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ') }
function Cut([string]$s, [int]$n) { if ($null -eq $s) { return $null }; if ($s.Length -le $n) { return $s }; return $s.Substring(0, $n) }

function Write-Utf8Json([string]$Path, $Obj) {
  $dir = Split-Path -Parent $Path
  if (-not (Test-Path -LiteralPath $dir)) { [void][IO.Directory]::CreateDirectory($dir) }
  $json = $Obj | ConvertTo-Json -Depth 10
  $tmp = "$Path.tmp-$([guid]::NewGuid().ToString('n').Substring(0,8))"
  [IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))
  Move-Item -LiteralPath $tmp -Destination $Path -Force
}

function Write-Utf8Text([string]$Path, [string]$Text) {
  $dir = Split-Path -Parent $Path
  if (-not (Test-Path -LiteralPath $dir)) { [void][IO.Directory]::CreateDirectory($dir) }
  [IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Read-PrevLatest {
  if (-not (Test-Path -LiteralPath $LatestPath)) { return $null }
  try { return (Get-Content -LiteralPath $LatestPath -Raw -Encoding UTF8 | ConvertFrom-Json) }
  catch { return $null }
}

function Invoke-NvidiaSmi([string[]]$QueryFields) {
  $queryArg = '--query-gpu=' + ($QueryFields -join ',')
  $out = & nvidia-smi $queryArg '--format=csv,noheader,nounits' 2>&1
  $code = $LASTEXITCODE
  return @{ ok = ($code -eq 0); text = ($out | Out-String); exitCode = $code }
}

function Get-GpuSection {
  $full = @(
    'index','name','driver_version','temperature.gpu','temperature.memory',
    'power.draw','power.limit','power.max_limit','enforced.power.limit',
    'pstate','clocks.sm','clocks.mem','clocks.gr',
    'utilization.gpu','utilization.memory','memory.used','memory.total','fan.speed',
    'clocks_event_reasons.active','clocks_event_reasons.gpu_idle',
    'clocks_event_reasons.applications_clocks_setting','clocks_event_reasons.sw_power_cap',
    'clocks_event_reasons.hw_slowdown','clocks_event_reasons.hw_thermal_slowdown',
    'clocks_event_reasons.hw_power_brake_slowdown','clocks_event_reasons.sw_thermal_slowdown',
    'clocks_event_reasons.sync_boost'
  )
  $basic = @(
    'index','name','driver_version','temperature.gpu','power.draw','power.limit',
    'pstate','clocks.sm','clocks.mem','utilization.gpu','memory.used','memory.total','fan.speed'
  )

  $smiPresent = [bool](Get-Command nvidia-smi -ErrorAction SilentlyContinue)
  if (-not $smiPresent) {
    return @{ smiPresent = $false; smiOk = $false; fieldSet = 'none'
              error = 'nvidia-smi not on PATH'; gpus = @(); rawText = '' }
  }
  $r = Invoke-NvidiaSmi $full
  $fieldSet = 'full'
  if (-not $r.ok) { $r = Invoke-NvidiaSmi $basic; $fieldSet = 'basic' }
  if (-not $r.ok) {
    return @{ smiPresent = $smiPresent; smiOk = $false; fieldSet = $fieldSet
              error = (Cut (($r.text -replace '\s+', ' ').Trim()) 200); gpus = @(); rawText = $r.text }
  }

  # ECC query is optional (GeForce reports N/A); kept separate so a reject never kills the main query.
  $ecc = Invoke-NvidiaSmi @('index','ecc.errors.uncorrected.aggregate')
  $eccMap = @{}
  if ($ecc.ok) {
    foreach ($line in ($ecc.text -split "`r?`n")) {
      $parts = $line -split ',\s*'
      if ($parts.Count -ge 2) { $eccMap[$parts[0].Trim()] = $parts[1].Trim() }
    }
  }

  $fields = if ($fieldSet -eq 'full') { $full } else { $basic }
  $gpus = @()
  foreach ($line in ($r.text -split "`r?`n")) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $v = $line -split ',\s*'
    $g = [ordered]@{}
    for ($i = 0; $i -lt $fields.Count -and $i -lt $v.Count; $i++) { $g[$fields[$i]] = $v[$i].Trim() }
    $ix = [string]$g['index']
    $g['ecc.errors.uncorrected.aggregate'] = if ($eccMap.ContainsKey($ix)) { $eccMap[$ix] } else { 'not_observed' }
    $gpus += $g
  }
  return @{ smiPresent = $true; smiOk = $true; fieldSet = $fieldSet
            error = $null; gpus = $gpus; rawText = $r.text }
}

function Get-OllamaSection([int[]]$Ports, $PrevPorts) {
  $prevFail = @{}
  if ($PrevPorts) { foreach ($p in @($PrevPorts)) { $prevFail[[string]$p.port] = [int]$p.consecutiveFailures } }
  $rows = @()
  foreach ($port in $Ports) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $row = [ordered]@{ port = $port; ok = $false; latencyMs = $null; version = $null
                       error = $null; errorKind = $null; consecutiveFailures = 0 }
    try {
      $resp = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$port/api/version" -TimeoutSec $OllamaTimeoutSec
      $sw.Stop()
      $row.ok = $true; $row.latencyMs = [int]$sw.ElapsedMilliseconds
      if ($resp -and $resp.version) { $row.version = [string]$resp.version }
    } catch {
      $sw.Stop()
      $row.latencyMs = [int]$sw.ElapsedMilliseconds
      $msg = $_.Exception.Message
      $row.error = (Cut ($msg -replace '\s+', ' ').Trim() 160)
      if ($msg -match 'timed out|timeout') { $row.errorKind = 'timeout' }
      elseif ($msg -match 'refused|connect') { $row.errorKind = 'connect_failed' }
      else { $row.errorKind = 'http_or_other' }
    }
    $key = [string]$port
    $row.consecutiveFailures = if ($row.ok) { 0 } elseif ($prevFail.ContainsKey($key)) { [int]$prevFail[$key] + 1 } else { 1 }
    $rows += $row
  }
  return $rows
}

function Get-EventSection([datetime]$since, [int64]$PrevNewestTicks) {
  $section = [ordered]@{ lookbackHours = $EventLookbackHours; providers = @(); kernelPower41Count = 0
                         newestSeenUtc = $null; newestSeenTicks = [int64]0; newErrorCount = 0; newErrors = @(); status = 'ok' }


  $all = @()
  foreach ($prov in @('nvlddmkm', 'Display')) {
    try {
      $ev = @(Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = $prov; StartTime = $since } -MaxEvents 200 -ErrorAction Stop)
      $all += $ev
    } catch {
      # "No events found" is the common case; any failure is informational only.
      if ($_.Exception.Message -notmatch 'No events were found') {
        $section.providers += @{ provider = $prov; queryNote = (Cut ($_.Exception.Message -replace '\s+', ' ').Trim() 140) }
      }
    }
  }
  # Kernel-Power 41 (unexpected power loss) folds into the same new-error path.
  try {
    $kp = @(Get-WinEvent -FilterHashtable @{ LogName = 'System'; ProviderName = 'Microsoft-Windows-Kernel-Power'; Id = 41; StartTime = $since } -MaxEvents 20 -ErrorAction Stop)
    $section.kernelPower41Count = $kp.Count
    $all += $kp
  } catch { $section.kernelPower41Count = 0 }
  $all = @($all | Where-Object { $_.Level -le 3 } | Sort-Object TimeCreated)

  $byProv = @{}
  foreach ($e in $all) {
    $k = $e.ProviderName + '|' + $e.LevelDisplayName
    if (-not $byProv.ContainsKey($k)) { $byProv[$k] = @{ provider = $e.ProviderName; level = $e.LevelDisplayName; count = 0; newestUtc = $null; ids = @() } }
    $byProv[$k].count++
    $byProv[$k].ids += $e.Id
    $et = $e.TimeCreated.ToUniversalTime()
    if (-not $byProv[$k].newestUtc -or $et -gt [datetime]::Parse($byProv[$k].newestUtc).ToUniversalTime()) { $byProv[$k].newestUtc = (Iso $et) }
    if ($et.Ticks -gt $section.newestSeenTicks) { $section.newestSeenTicks = $et.Ticks; $section.newestSeenUtc = (Iso $et) }
    $isErr = ($e.Level -le 2) -or ($e.ProviderName -in @('nvlddmkm','Display'))

    $isNew = ($PrevNewestTicks -eq 0) -or ($et.Ticks -gt $PrevNewestTicks)
    if ($isErr -and $isNew) {
      $section.newErrorCount++
      if ($section.newErrors.Count -lt 10) {
        $section.newErrors += @{ provider = $e.ProviderName; id = $e.Id; level = $e.LevelDisplayName
                                 timeUtc = (Iso $e.TimeCreated); message = (Cut ($e.Message -replace '\s+', ' ').Trim() 160) }
      }
    }
  }
  foreach ($k in $byProv.Keys) { $section.providers += $byProv[$k] }
  return $section
}

function Get-Hypotheses($anoms, $gpu, $events) {
  $h = @{}
  $ids = @($anoms | ForEach-Object { $_.id })
  if ($ids -contains 'hw_power_brake_slowdown_active' -or $ids -contains 'hw_slowdown_active' -or $ids -contains 'sw_power_cap_active') {
    $h['power_peak_or_limit'] = 'clock event reason shows power/hw brake or cap active; peak draw may exceed what PSU/limit absorbs'
  }
  if ($ids -contains 'smi_error_text' -or $ids -contains 'new_error_events' -or $ids -contains 'smi_failed' -or $ids -contains 'smi_missing') {
    $h['psu_or_wiring'] = 'gpu lost/reset/Xid-style signal can follow marginal power delivery or cabling'
    $h['driver'] = 'driver stack errors or smi failure can also come from driver/firmware state'
  }
  if ($events -and $events.kernelPower41Count -gt 0) {
    $h['psu_or_wiring'] = 'Kernel-Power 41 (unexpected power loss) in lookback window strengthens power-side suspicion'
  }
  if ($ids -contains 'ollama_timeout_streak' -and $h.Count -eq 0) {
    $h['unknown'] = 'only Ollama timeouts observed; may be service-level, not GPU hardware'
  }
  if ($h.Count -eq 0) { $h['unknown'] = 'signal does not map cleanly to a single subsystem' }
  $list = @()
  foreach ($k in @('power_peak_or_limit','psu_or_wiring','driver','unknown')) {
    if ($h.ContainsKey($k)) { $list += [ordered]@{ id = $k; confidence = 'low'; rationale = $h[$k] } }
  }
  return $list
}

function Write-HandoffLatest($report) {
  $lines = @()
  $lines += '# rtx3090-watch - LATEST ALERT'
  $lines += ''
  $lines += "- atUtc: $($report.generatedAtUtc)  machine: $($report.machine)"
  $lines += "- anomalies: " + (@($report.anomalies | ForEach-Object { $_.id }) -join ', ')
  if ($report.gpu.smiOk -and $report.gpu.target) {
    $t = $report.gpu.target
    $lines += "- gpu: $($t.name) idx=$($t.index) driver=$($t.driver_version) temp=$($t.'temperature.gpu')C power=$($t.'power.draw')W/limit=$($t.'power.limit')W pstate=$($t.pstate) sm=$($t.'clocks.sm')MHz fan=$($t.'fan.speed')%"
    if ($t.'clocks_event_reasons.active') { $lines += "- clockEventReasons.active: $($t.'clocks_event_reasons.active')" }
  } else {
    $lines += "- gpu: smi unavailable (see alert json)"
  }
  $o = @($report.ollama.ports | ForEach-Object { if ($_.ok) { "$($_.port) ok $($_.latencyMs)ms" } else { "$($_.port) fail x$($_.consecutiveFailures) ($($_.errorKind))" } })
  $lines += "- ollama: " + ($o -join ' / ')
  $lines += "- hypotheses (confidence=low, cause UNCONFIRMED):"
  foreach ($h in @($report.hypotheses)) { $lines += "  - $($h.id): $($h.rationale)" }
  $lines += ''
  $lines += "- context: userPowerLimitPercent=$($report.notes.userPowerLimitPercent), experiment=$($report.notes.experiment)"
  $lines += "- rules: no automatic PL/clock/PSU change; do not block normal LLM/bench load; do not assert a cause."
  $lines += "- evidence: var/debug/rtx3090-watch/$($report.alertFile) and latest.json"
  Write-Utf8Text -Path $HandoffMd -Text ($lines -join "`r`n")
}

function Invoke-WatchPass {
  $now = UtcNow
  $prev = Read-PrevLatest
  $prevPorts = $null; $prevTicks = [int64]0; $prevSignature = ''; $prevAlertAt = $null; $runCount = 0
  if ($prev) {
    $prevPorts = $prev.ollama.ports
    if ($prev.events) { if ($prev.events.newestSeenTicks) { $prevTicks = [int64]$prev.events.newestSeenTicks } elseif ($prev.events.newestSeenUtc) { try { $prevTicks = [datetime]::Parse([string]$prev.events.newestSeenUtc).ToUniversalTime().Ticks } catch { $prevTicks = 0 } } }
    if ($prev.alertSignature) { $prevSignature = [string]$prev.alertSignature }
    if ($prev.lastAlertAtUtc) { try { $prevAlertAt = [datetime]::Parse([string]$prev.lastAlertAtUtc).ToUniversalTime() } catch { $prevAlertAt = $null } }
    if ($prev.runCount) { $runCount = [int]$prev.runCount }
  }

  $gpu = Get-GpuSection
  $portList = @($OllamaPorts -split '[,\s]+' | Where-Object { $_ -match '^\d+$' } | ForEach-Object { [int]$_ })
  if ($portList.Count -eq 0) { $portList = @(11434, 11435) }
  $ollamaPorts = Get-OllamaSection -Ports $portList -PrevPorts $prevPorts
  $events = Get-EventSection -since ($now.AddHours(-$EventLookbackHours)) -PrevNewestTicks $prevTicks

  $target = $null
  foreach ($g in @($gpu.gpus)) { if ([string]$g.name -match $TargetGpuMatch) { $target = $g; break } }
  if (-not $target -and $gpu.gpus.Count -gt 0) { $target = $gpu.gpus[0] }
  $gpu['target'] = $target

  $anoms = @()
  if (-not $gpu.smiPresent) { $anoms += @{ id = 'smi_missing'; detail = 'nvidia-smi binary not found' } }
  elseif (-not $gpu.smiOk) { $anoms += @{ id = 'smi_failed'; detail = $gpu.error } }
  if ($gpu.rawText -match '(?i)(lost|has fallen off|xid|ERR!|reset)') {
    $anoms += @{ id = 'smi_error_text'; detail = (Cut (($gpu.rawText -replace '\s+', ' ').Trim()) 160) }
  }
  if ($target) {
    foreach ($r in @('hw_slowdown','hw_power_brake_slowdown','hw_thermal_slowdown','sw_thermal_slowdown')) {
      $key = "clocks_event_reasons.$r"
      if ($target.Contains($key) -and $target[$key] -ieq 'Active') {
        $anoms += @{ id = "$($r)_active"; detail = "$key=$($target[$key])" }
      }
    }
  }
  if ($events.newErrorCount -gt 0) {
    $anoms += @{ id = 'new_error_events'; detail = "new Level<=2 system events (nvlddmkm/Display/Kernel-Power41): $($events.newErrorCount)" }
  }
  $streakPorts = @($ollamaPorts | Where-Object { -not $_.ok -and $_.consecutiveFailures -ge $OllamaFailThreshold })
  if ($streakPorts.Count -gt 0) {
    $anoms += @{ id = 'ollama_timeout_streak'; detail = (@($streakPorts | ForEach-Object { "$($_.port):x$($_.consecutiveFailures)($($_.errorKind))" }) -join ' ') }
  }

  $signature = (@($anoms | ForEach-Object { $_.id }) | Sort-Object) -join '|'
  $isAlert = ($anoms.Count -gt 0)
  $suppressed = $false; $alertFile = $null; $lastAlertAt = $null
  if ($isAlert) {
    $recentSame = ($signature -eq $prevSignature -and $prevAlertAt -and ((UtcNow) - $prevAlertAt).TotalMinutes -lt $AlertCooldownMinutes)
    if ($recentSame) { $suppressed = $true; $lastAlertAt = (Iso $prevAlertAt) }
    else {
      $stamp = $now.ToString('yyyyMMdd-HHmmss')
      $alertFile = "alert-$stamp.json"
      $lastAlertAt = (Iso $now)
    }
  }

  $report = [ordered]@{
    schemaVersion   = $script:SchemaVersion
    machine         = $env:COMPUTERNAME
    generatedAtUtc  = (Iso $now)
    runCount        = ($runCount + 1)
    status          = if ($isAlert) { 'alert' } else { 'ok' }
    gpu             = $gpu
    ollama          = @{ ports = $ollamaPorts; timeoutSec = $OllamaTimeoutSec; failThreshold = $OllamaFailThreshold }
    events          = $events
    anomalies       = $anoms
    hypotheses      = (Get-Hypotheses $anoms $gpu $events)
    alertSignature  = $signature
    alertFile       = $alertFile
    alertSuppressed = $suppressed
    lastAlertAtUtc  = $lastAlertAt
    notes           = [ordered]@{
      cause                 = 'unconfirmed'
      userPowerLimitPercent = 90
      experiment            = 'power_limit_first_then_consider_psu'
      guidance              = 'hypotheses only; never change PL/clocks/PSU automatically; never block normal GPU load'
    }
    paths           = @{ latest = 'var/debug/rtx3090-watch/latest.json'; alerts = 'var/debug/rtx3090-watch/'; handoff = 'data/agent-handoff/rtx3090-watch/LATEST.md' }
  }

  Write-Utf8Json -Path $LatestPath -Obj $report
  if ($isAlert -and -not $suppressed) {
    Write-Utf8Json -Path (Join-Path $WatchDir $alertFile) -Obj $report
    Write-HandoffLatest $report
  }
  return $report
}

# ---- main ----
try {
  [void][IO.Directory]::CreateDirectory($WatchDir)
  [void][IO.Directory]::CreateDirectory($HandoffDir)
  do {
    $rep = Invoke-WatchPass
    $tag = if ($rep.status -eq 'alert') { 'ALERT' } else { 'OK' }
    $gpuLine = 'smi=unavailable'
    if ($rep.gpu.smiOk -and $rep.gpu.target) {
      $t = $rep.gpu.target
      $gpuLine = "$($t.name) temp=$($t.'temperature.gpu')C power=$($t.'power.draw')/$($t.'power.limit')W $($t.pstate)"
    }
    Write-Host ("[rtx3090-watch][{0}] {1} | {2} | anomalies={3} | latest={4}" -f $tag, $rep.generatedAtUtc, $gpuLine, (@($rep.anomalies | ForEach-Object { $_.id }) -join ','), $LatestPath)
    if ($rep.alertFile) { Write-Host "[rtx3090-watch] alert written: $WatchDir\$($rep.alertFile) + $HandoffMd" }
    elseif ($rep.alertSuppressed) { Write-Host "[rtx3090-watch] alert suppressed (same signature within cooldown)" }
    if ($IntervalSeconds -le 0) { break }
    Start-Sleep -Seconds $IntervalSeconds
  } while ($true)
  exit ($(if ($rep.status -eq 'alert') { 3 } else { 0 }))
} catch {
  Write-Host "[rtx3090-watch][ERROR] $($_.Exception.Message)"
  exit 1
}
