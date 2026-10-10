# codex_sandbox_doctor.ps1 -- watch + heal Codex Windows sandbox setup-refresh failures.
#
# Symptom : dot/app commands die pre-spawn with "helper_unknown_error: setup refresh had errors".
# Cause   : codex-windows-sandbox-setup.exe opens every runtime exec file with MAXIMUM_ALLOWED,
#           which resolves to FILE_WRITE_DATA -> os error 32 while images/DLLs are loaded
#           (upstream: https://github.com/openai/codex/issues/51613).
# Remedy  : non-inheriting deny ACE for the current user on (WD,AD) under each
#           runtimes\<family>\<hash>\bin\*.exe|dll|node|com|bat|cmd|ps1 -- MAXIMUM_ALLOWED then
#           excludes write-data, the open succeeds, and WRITE_DAC (the real ACL update) still works.
#           Deny only on files, never dirs: zip-style runtime updates delete+create, not in-place.
#
# Usage   : powershell -NoProfile -ExecutionPolicy Bypass -File scripts\codex_sandbox_doctor.ps1 -Action Check
#           -Action Heal [-DryRun] [-WithBrief]  -Action InstallSchedule  -Action UninstallSchedule
#           -BriefRead -Check | -BriefRead -Heal [-DryRun] | -BriefRead -Revoke
#           -BriefRead -Install | -BriefRead -Uninstall
# BriefRead: Codex sandbox commands run as CodexSandboxOffline|Online (group
#           CodexSandboxUsers). Only PASTE_* brief files may carry the group ACE,
#           as an explicit (R) -- the app's own read-acl pass once put
#           CodexSandboxUsers:(OI)(CI)(RX) on Downloads (and other profile dirs),
#           which made every inheriting file readable (LEAK_FOLDER / LEAK_FILE).
#           Check flags: LEAK_FOLDER = any group ACE on the folder itself,
#           LEAK_FILE = group ACE (explicit or inherited) on a non-PASTE file,
#           MISSING_EXPLICIT = PASTE file readable only via inheritance, exit 2.
#           Heal: backup -> remove folder ACE -> strip group ACEs on non-PASTE
#           files -> grant explicit (R) on PASTE_*.txt/.md. -Revoke removes the
#           group ACE from folder and files. -WithBrief appends a brief heal to
#           -Action Heal (one scheduled run).
# Exit    : 0 ok/action-done, 2 drift-or-heal-needed, 1 internal error.
[CmdletBinding()]
param(
  [ValidateSet('Check','Heal','InstallSchedule','UninstallSchedule')]
  [string]$Action = 'Check',
  [string]$RuntimesRoot = (Join-Path $env:LOCALAPPDATA 'OpenAI\Codex\runtimes'),
  [string]$SandboxDir   = (Join-Path $env:USERPROFILE '.codex\.sandbox'),
  [string]$StateDir     = '',
  [string]$DenyUser     = "$env:USERDOMAIN\$env:USERNAME",
  [switch]$DryRun,
  [switch]$BriefRead,
  [ValidateSet('Check','Heal','Revoke','Install','Uninstall')]
  [string]$BriefMode = '',
  [switch]$Check, [switch]$Heal, [switch]$Revoke, [switch]$Install, [switch]$Uninstall,
  [switch]$WithBrief,
  [string]$BriefDir    = (Join-Path $env:USERPROFILE 'Downloads'),
  [string]$BriefPrefix = 'PASTE_',
  [string[]]$BriefExt  = @('.txt','.md'),
  [string]$BriefGroup  = 'CodexSandboxUsers',
  [string]$AppVersion  = '',
  [ValidateRange(1024,104857600)] [long]$MaxActiveBytes = 1000000,
  [ValidateRange(2,100)] [int]$MaxFiles = 5,
  [ValidateRange(2048,1048576000)] [long]$MaxTotalBytes = 4000000,
  [Parameter(ValueFromRemainingArguments=$true)]
  [string[]]$BriefArgs = @()
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ExecExt = @('.exe','.dll','.node','.com','.bat','.cmd','.ps1')
$TaskName = 'AWX-CodexSandboxDoctor'
$BriefTaskName = 'AWX-CodexBriefRead'
$scriptRoot = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent ([IO.Path]::GetFullPath($MyInvocation.MyCommand.Path)) }
if (-not $StateDir) { $StateDir = Join-Path (Split-Path $scriptRoot -Parent) 'var\codex-sandbox-doctor' }

function Get-DoctorLogDigest([string]$Text) {
  $sha = [Security.Cryptography.SHA256]::Create()
  try { return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Text)))).Replace('-','').ToLowerInvariant() }
  finally { $sha.Dispose() }
}

function Write-DoctorLog([System.Collections.IDictionary]$Entry) {
  # Managed retention: active <=1 MB, <=5 files, <=4 MB. Legacy bytes are
  # preserved separately; only files created by this logger may be evicted.
  if ($MaxTotalBytes -lt $MaxActiveBytes) { throw 'doctor-log-invalid-budget' }
  [void][IO.Directory]::CreateDirectory($StateDir)
  $active = Join-Path $StateDir 'doctor.jsonl'
  $statePath = Join-Path $StateDir 'doctor-log-state.json'
  $save = $null
  $eventAppended = $false; $appendStarted = $false; $appendBeforeBytes = 0
  $lock = $null; $clock = [Diagnostics.Stopwatch]::StartNew()
  try {
    while (-not $lock) {
      try { $lock = [IO.File]::Open((Join-Path $StateDir 'doctor-log.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
      catch [IO.IOException] { if ($clock.ElapsedMilliseconds -ge 5000) { throw 'doctor-log-writer-timeout' }; Start-Sleep -Milliseconds 20 }
    }
    $save = {
      param($Destination,$Value)
      $temporary = $Destination + '.' + [guid]::NewGuid().ToString('N') + '.tmp'
      try {
        [IO.File]::WriteAllText($temporary, ($Value | ConvertTo-Json -Compress -Depth 5), [Text.UTF8Encoding]::new($false))
        if ([IO.File]::Exists($Destination)) { [IO.File]::Replace($temporary,$Destination,$Destination+'.bak') } else { [IO.File]::Move($temporary,$Destination) }
      } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
        if ([IO.File]::Exists($Destination+'.bak')) { [IO.File]::Delete($Destination+'.bak') }
      }
    }
    $state = @{ version=1;pendingEviction=$null;pendingRepair=$null;failureDroppedSeen=0; seq=0; lastHash=''; evictedRows=0; evictedBytes=0; writeFailure=0; dropped=0 }
    if (Test-Path -LiteralPath $statePath -PathType Leaf) {
      $saved = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
      if ($saved.version -ne 1) { throw 'doctor-log-state-version' }
      foreach ($key in @('seq','lastHash','evictedRows','evictedBytes','writeFailure','dropped')) { $state[$key] = $saved.$key }
      if ($saved.PSObject.Properties['failureDroppedSeen']) { $state.failureDroppedSeen = $saved.failureDroppedSeen }
      if ($saved.PSObject.Properties['pendingRepair']) { $state.pendingRepair = $saved.pendingRepair }
      if ($saved.PSObject.Properties['pendingEviction']) { $state.pendingEviction = $saved.pendingEviction }

    } elseif (Test-Path -LiteralPath $active -PathType Leaf) {
      # A first append may precede a failed metadata save. Recover our v1
      # records through the normal hash/sequence path instead of resetting.
      $firstLine = Get-Content -LiteralPath $active -TotalCount 1
      if ($firstLine -notmatch '^\s*\{\s*"schemaVersion"\s*:\s*"doctor.log.v1"') {
        [IO.File]::Move($active, (Join-Path $StateDir ('doctor.legacy-' + [guid]::NewGuid().ToString('N') + '.jsonl')))
      }

    }
    $countedPartialHash = ''
    $failurePath = Join-Path $StateDir 'doctor-log-failures.json'
    if ([IO.File]::Exists($failurePath)) {
      $failures = [IO.File]::ReadAllText($failurePath) | ConvertFrom-Json
      $state.writeFailure = [Math]::Max([long]$state.writeFailure,[long]$failures.writeFailure)
      $state.dropped += [Math]::Max(0,([long]$failures.dropped-[long]$state.failureDroppedSeen))
      $state.failureDroppedSeen = [long]$failures.dropped
      if ($failures.PSObject.Properties['partialAppendHash']) { $countedPartialHash = [string]$failures.partialAppendHash }
    }
    $recoverRepair = {
      if ($state.pendingRepair) {
        $pending = $state.pendingRepair
        if ($pending.target -notmatch '^(doctor\.jsonl|doctor\.rotated-v1-[0-9]{20}-[a-f0-9]{32}\.jsonl)$' -or $pending.archive -notmatch '^doctor\.legacy-recovery-[a-f0-9]{32}\.jsonl$') { throw 'doctor-log-repair-scope' }
        $archive=Join-Path $StateDir $pending.archive
        if ((Get-DoctorLogDigest ([IO.File]::ReadAllText($archive))) -ne $pending.archiveHash) {throw 'doctor-log-repair-preimage'}
        $lines=@([IO.File]::ReadAllLines($archive))
        if($pending.prefixLines -lt 0 -or $pending.prefixLines -gt $lines.Count){throw 'doctor-log-repair-range'}
        $prefix=@($lines|Select-Object -First $pending.prefixLines)
        [IO.File]::WriteAllText((Join-Path $StateDir $pending.target),($prefix -join "`n")+$(if($prefix.Count){"`n"}else{''}),[Text.UTF8Encoding]::new($false))
        if (-not ($pending.PSObject.Properties['lossAlreadyCounted'] -and $pending.lossAlreadyCounted)) { $state.dropped++ }
        $state.pendingRepair=$null
        & $save $statePath $state
      }
    }
    & $recoverRepair
    # Persist eviction intent before deletion; recover exactly once even when
    # interruption occurs between deleting the file and updating counters.
    $recoverEviction = {
      if ($state.pendingEviction) {
        $pending = $state.pendingEviction
        if ($pending.name -notmatch '^doctor\.rotated-v1-[0-9]{20}-[a-f0-9]{32}\.jsonl$') { throw 'doctor-log-eviction-scope' }
        [IO.File]::Delete((Join-Path $StateDir $pending.name))
        $state.evictedRows += [long]$pending.rows; $state.evictedBytes += [long]$pending.bytes
        $state.pendingEviction = $null
        & $save $statePath $state
      }
    }
    & $recoverEviction
    # Recover an append completed before the metadata replace (including after
    # a process interruption). Archive order carries the last sequence number.
    $tailFile = $active
    if (-not (Test-Path -LiteralPath $tailFile -PathType Leaf)) {
      $archives = @(Get-ChildItem -LiteralPath $StateDir -Filter 'doctor.rotated-v1-*.jsonl' | Sort-Object Name)
      if ($archives.Count) { $tailFile = $archives[-1].FullName }
    }
    if (Test-Path -LiteralPath $tailFile -PathType Leaf) {
      $lines = @([IO.File]::ReadAllLines($tailFile)); $valid = @(); $tail = $null
      for ($i=0;$i -lt $lines.Count;$i++) {
        try { $candidate = $lines[$i] | ConvertFrom-Json } catch {
          if ($i -ne $lines.Count-1) { throw 'doctor-log-corrupt-record' }
          # Preserve original bytes before repairing a single torn final line.
          $archiveName='doctor.legacy-recovery-'+[guid]::NewGuid().ToString('N')+'.jsonl'
          $archive=Join-Path $StateDir $archiveName
          [IO.File]::Copy($tailFile,$archive)
          $archiveHash=Get-DoctorLogDigest ([IO.File]::ReadAllText($archive))
          $state.pendingRepair=[PSCustomObject]@{target=[IO.Path]::GetFileName($tailFile);archive=$archiveName;archiveHash=$archiveHash;prefixLines=$valid.Count;lossAlreadyCounted=($countedPartialHash -eq $archiveHash)}
          & $save $statePath $state
          & $recoverRepair
          break
        }
        $content = [ordered]@{}
        foreach ($property in $candidate.PSObject.Properties) { if ($property.Name -ne 'hash') { $content[$property.Name] = $property.Value } }
        if ((Get-DoctorLogDigest ($content | ConvertTo-Json -Compress)) -ne $candidate.hash) { throw 'doctor-log-corrupt-hash' }
        if ($tail -and ($candidate.prevHash -ne $tail.hash -or $candidate.seq -ne $tail.seq+1)) { throw 'doctor-log-corrupt-chain' }
        $valid += $lines[$i]; $tail = $candidate
      }
      if ($tail -and $tail.seq -gt $state.seq) { $state.seq = $tail.seq; $state.lastHash = $tail.hash }

    }
    $safe = [ordered]@{ schemaVersion='doctor.log.v1'; ts=[DateTime]::UtcNow.ToString('o'); seq=([long]$state.seq+1); prevHash=$state.lastHash }
    foreach ($key in @('action','result','verdict','lastRefresh')) {
      $value = [string]$Entry[$key]
      if ($value -in @('Check','Heal','InstallSchedule','UninstallSchedule','BriefCheck','BriefHeal','BriefRevoke','BriefInstall','BriefUninstall','ok','error','registered','exists-skip','DRIFT','OK','failed','success','none','healed','dry-run')) { $safe[$key] = $value }
    }
    foreach ($key in @('errorsToday','runtimeBins','execCovered','denyMissing','knownBenign','knownFixedOs32','newErrors','setupErrors','latestLogBytes','totalLogBytes7d','pasteTotal','covered','missing','leaks','denied','leakFile','missingExplicit','nonTargetInheritedRx','denyApplied','removed','tasks')) {
      if ($Entry.Contains($key) -and $Entry[$key] -is [ValueType] -and $Entry[$key] -isnot [bool]) { $safe[$key] = [long]$Entry[$key] }
    }
    $safe.reasonCode = if ($Entry.Contains('error')) { 'doctor-action-error' } elseif ($Entry['verdict'] -eq 'DRIFT') { 'doctor-drift' } else { 'doctor-observation' }
    $safe.evictedRows = [long]$state.evictedRows; $safe.evictedBytes = [long]$state.evictedBytes
    $safe.hash = Get-DoctorLogDigest ($safe | ConvertTo-Json -Compress)
    $line = ($safe | ConvertTo-Json -Compress) + "`n"
    $bytes = [Text.Encoding]::UTF8.GetByteCount($line)
    if ($bytes -gt $MaxActiveBytes) { throw 'doctor-log-event-oversize' }
    if ((Test-Path -LiteralPath $active -PathType Leaf) -and ((Get-Item -LiteralPath $active).Length + $bytes -gt $MaxActiveBytes)) {
      $archiveName = 'doctor.rotated-v1-{0:D20}-{1}.jsonl' -f [long]$state.seq, [guid]::NewGuid().ToString('N')
      [IO.File]::Move($active, (Join-Path $StateDir $archiveName))
    }
    $appendBeforeBytes = if ([IO.File]::Exists($active)) { (Get-Item -LiteralPath $active).Length } else { 0 }
    $appendStarted = $true
    [IO.File]::AppendAllText($active, $line, [Text.UTF8Encoding]::new($false))
    $eventAppended = $true
    $state.seq = $safe.seq; $state.lastHash = $safe.hash
    $archives = @(Get-ChildItem -LiteralPath $StateDir -Filter 'doctor.rotated-v1-*.jsonl' | Sort-Object Name)
    $total = (Get-Item -LiteralPath $active).Length
    foreach ($file in $archives) { $total += $file.Length }
    while ($archives.Count -gt ($MaxFiles-1) -or $total -gt $MaxTotalBytes) {
      $oldest = $archives[0]
      if (-not $oldest -or $oldest.DirectoryName -ne [IO.Path]::GetFullPath($StateDir).TrimEnd('\')) { throw 'doctor-log-eviction-scope' }
      $state.pendingEviction = @{name=$oldest.Name;rows=@([IO.File]::ReadAllLines($oldest.FullName)).Count;bytes=$oldest.Length}
      & $save $statePath $state
      $total -= $oldest.Length
      & $recoverEviction
      $archives = @($archives | Select-Object -Skip 1)
    }
    & $save $statePath $state
  } catch {
    # Keep a small loss receipt even if the main metadata write fails. If the
    # storage itself is unavailable, stderr remains the observable failure.
    if ($lock -and $save) {
      try {
        $failurePath = Join-Path $StateDir 'doctor-log-failures.json'
        $failure = @{writeFailure=0;dropped=0;partialAppendHash=''}
        if ([IO.File]::Exists($failurePath)) { $f=[IO.File]::ReadAllText($failurePath)|ConvertFrom-Json; $failure.writeFailure=$f.writeFailure; $failure.dropped=$f.dropped; if ($f.PSObject.Properties['partialAppendHash']) { $failure.partialAppendHash=$f.partialAppendHash } }
        $failure.writeFailure++
        if (-not $eventAppended) {
          # Bind the loss receipt to this exact torn tail; unrelated failed
          # attempts remain counted. A fully persisted line is recoverable.
          if ($appendStarted -and [IO.File]::Exists($active)) {
            try {
              $written = [IO.File]::ReadAllText($active)
              if ([Text.Encoding]::UTF8.GetByteCount($written) -eq ($appendBeforeBytes+$bytes) -and $written.EndsWith($line)) { $eventAppended=$true }
              elseif ((Get-Item -LiteralPath $active).Length -gt $appendBeforeBytes) { $failure.partialAppendHash=Get-DoctorLogDigest $written }
            } catch { [Console]::Error.WriteLine('doctor-log-append-evidence-unavailable') }
          }
          if (-not $eventAppended) { $failure.dropped++ }
        }
        & $save $failurePath $failure
      } catch { [Console]::Error.WriteLine('doctor-log-loss-receipt-unavailable') }
    }
    [Console]::Error.WriteLine('doctor-log-write-failed')
    throw 'doctor-log-write-failed'
  } finally { if ($lock) { $lock.Dispose() } }
}

function Get-RuntimeBins {
  if (-not (Test-Path -LiteralPath $RuntimesRoot)) { return @() }
  Get-ChildItem -LiteralPath $RuntimesRoot -Directory -ErrorAction SilentlyContinue |
    ForEach-Object { Get-ChildItem -LiteralPath $_.FullName -Directory -ErrorAction SilentlyContinue } |
    ForEach-Object { Join-Path $_.FullName 'bin' } |
    Where-Object { Test-Path -LiteralPath $_ }
}

function Get-ExecFiles([string]$BinDir) {
  Get-ChildItem -LiteralPath $BinDir -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { $script:ExecExt -contains $_.Extension.ToLowerInvariant() }
}

function Test-DenyAce([string]$Path) {
  # true when a Deny ACE for $DenyUser covers FILE_WRITE_DATA(0x2)+FILE_APPEND_DATA(0x4)
  try { $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop } catch { return $false }
  foreach ($ace in $acl.Access) {
    if ($ace.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Deny -and
        $ace.IdentityReference.Value -ieq $DenyUser) {
      $mask = [int64]$ace.FileSystemRights
      if (($mask -band 0x2) -and ($mask -band 0x4)) { return $true }
    }
  }
  return $false
}

function Get-SandboxState {
  $latest = Get-ChildItem -LiteralPath $SandboxDir -Filter 'sandbox.*.log' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
  if (-not $latest) { return [ordered]@{ log = $null; lastRefresh = 'no-log'; errorsToday = 0; lastErrorTarget = '' } }
  $today = (Get-Date).ToString('yyyy-MM-dd')
  $tail = @(Get-Content -LiteralPath $latest.FullName -Tail 400 -ErrorAction SilentlyContinue)
  $errToday = @($tail | Select-String 'setup error: setup refresh had errors').Count
  $result = 'unknown'
  for ($i = $tail.Count - 1; $i -ge 0; $i--) {
    $l = $tail[$i]
    if ($l -match 'setup binary completed' -or $l -match 'errors=\[\]') { $result = 'completed'; break }
    if ($l -match 'setup refresh completed with errors' -or $l -match 'runtime read/execute validation failed' -or
        $l -match 'setup error: setup refresh had errors') { $result = 'failed'; break }
  }
  $lastErr = ($tail | Select-String 'validate runtime read/execute access on (.*?): open ACL target' |
              Select-Object -Last 1)
  $target = ''
  if ($lastErr -and $lastErr.Matches.Count -gt 0) { $target = $lastErr.Matches[0].Groups[1].Value }
  [ordered]@{ log = $latest.FullName; lastRefresh = $result; errorsToday = $errToday; lastErrorTarget = $target }
}

function Get-LogErrorSummary {
  # classify latest-log error kinds: KNOWN_BENIGN(hide users: failed),
  # KNOWN_FIXED(os error 32 -> deny ACE fix deployed), NEW(other os error codes
  # / other failures). Also reports latest + 7-day log sizes (read-only).
  $latest = Get-ChildItem -LiteralPath $SandboxDir -Filter 'sandbox.*.log' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
  $sum = [ordered]@{ knownBenign=0; knownFixedOs32=0; setupErrors=0;
                     newErrorKinds=[ordered]@{}; latestLogBytes=0; totalLogBytes7d=0 }
  if ($latest) {
    $sum.latestLogBytes = $latest.Length
    foreach ($l in @(Get-Content -LiteralPath $latest.FullName -ErrorAction SilentlyContinue)) {
      if ($l -match 'hide users: failed') { $sum.knownBenign++; continue }
      if ($l -match 'os error (\d+)') {
        $c = $Matches[1]
        if ($c -eq '32') { $sum.knownFixedOs32++ } else { $sum.newErrorKinds[$c] = 1 + [int]$sum.newErrorKinds[$c] }
        continue
      }
      if ($l -match 'setup error|refresh had errors|helper_unknown_error') { $sum.setupErrors++ }
    }
  }
  $cut = (Get-Date).AddDays(-7)
  $sum.totalLogBytes7d = [int64](Get-ChildItem -LiteralPath $SandboxDir -Filter 'sandbox.*.log' -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -ge $cut } | Measure-Object Length -Sum).Sum
  $newSum = ($sum.newErrorKinds.Values | Measure-Object -Sum).Sum
  $sum['newErrors'] = [int]($newSum -as [int])
  return $sum
}

function Get-CodexAppVersion {
  if ($AppVersion) { return $AppVersion }
  try { $p = Get-AppxPackage -Name 'OpenAI.Codex' -ErrorAction Stop; if ($p) { return [string]$p.Version } } catch {}
  return ''
}

function Get-UpstreamRecheck {
  # App-version marker: first sight seeds baseline silently; a new version sets
  # pendingReview and surfaces UPSTREAM_RECHECK until the marker file is removed
  # (delete $StateDir\codex-app-version.json after checking issue #51613).
  param([string]$Ver)
  $r = @{ pending = $false; text = ''; version = $Ver }
  if (-not $Ver) { return $r }
  $marker = Join-Path $StateDir 'codex-app-version.json'
  $m = $null
  if (Test-Path -LiteralPath $marker) {
    try { $m = Get-Content -LiteralPath $marker -Raw | ConvertFrom-Json } catch { $m = $null }
  }
  if (-not $m) {
    if (-not (Test-Path -LiteralPath $StateDir)) { New-Item -ItemType Directory -Force -Path $StateDir | Out-Null }
    [IO.File]::WriteAllText($marker, (@{ version=$Ver; pendingReview=$false; since=(Get-Date).ToString('s') } | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    return $r
  }
  if ($m.version -ne $Ver) {
    $m = @{ version=$Ver; pendingReview=$true; since=(Get-Date).ToString('s'); previous=$m.version }
    [IO.File]::WriteAllText($marker, ($m | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
  }
  if ($m.pendingReview) {
    $r.pending = $true
    $r.text = "UPSTREAM_RECHECK: OpenAI.Codex $Ver -- verify github.com/openai/codex/issues/51613 fixed before removing DENY ACEs; clear by deleting $marker"
  }
  return $r
}

function Invoke-Check {
  $sb = Get-SandboxState
  $missing = @(); $covered = 0; $bins = @(Get-RuntimeBins)
  foreach ($b in $bins) {
    foreach ($f in @(Get-ExecFiles $b)) {
      if (Test-DenyAce $f.FullName) { $covered++ } else { $missing += $f.FullName }
    }
  }
  $lg = Get-LogErrorSummary
  $ur = Get-UpstreamRecheck (Get-CodexAppVersion)
  $drift = ($sb.lastRefresh -eq 'failed') -or ($missing.Count -gt 0) -or $ur.pending
  $out = [ordered]@{
    action='Check'; lastRefresh=$sb.lastRefresh; errorsToday=$sb.errorsToday;
    lastErrorTarget=$sb.lastErrorTarget; runtimeBins=$bins.Count;
    execCovered=$covered; denyMissing=$missing.Count;
    knownBenign=$lg.knownBenign; knownFixedOs32=$lg.knownFixedOs32;
    newErrors=$lg.newErrors; newErrorKinds=$lg.newErrorKinds; setupErrors=$lg.setupErrors;
    latestLogBytes=$lg.latestLogBytes; totalLogBytes7d=$lg.totalLogBytes7d;
    appVersion=$ur.version; upstreamRecheck=$ur.text;
    verdict=($(if($drift){'DRIFT'}else{'OK'}))
  }
  Write-DoctorLog $out
  $out | ConvertTo-Json -Compress
  if ($missing.Count -gt 0) { $missing | Select-Object -First 10 }
  if ($ur.pending) { $ur.text }
  if ($drift) { exit 2 } else { exit 0 }
}

function Invoke-Heal {
  $applied = 0; $skipped = 0; $bins = @(Get-RuntimeBins); $backups = @()
  foreach ($b in $bins) {
    $need = @(Get-ExecFiles $b | Where-Object { -not (Test-DenyAce $_.FullName) })
    if ($need.Count -eq 0) { $skipped++; continue }
    if ($DryRun) { $applied += $need.Count; continue }
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $hashName = (Split-Path $b -Parent | Split-Path -Leaf)
    if (-not (Test-Path -LiteralPath $StateDir)) { New-Item -ItemType Directory -Force -Path $StateDir | Out-Null }
    $bak = Join-Path $StateDir "acl-backup-$stamp-$hashName.txt"
    icacls $b /save $bak /T /C | Out-Null
    if (Test-Path -LiteralPath $bak) { $backups += $bak }
    foreach ($f in $need) {
      icacls $f.FullName /deny "${DenyUser}:(WD,AD)" | Out-Null
      if ($LASTEXITCODE -eq 0) { $applied++ }
    }
  }
  $ur = Get-UpstreamRecheck (Get-CodexAppVersion)
  $out = [ordered]@{ action='Heal'; dryRun=[bool]$DryRun; denyApplied=$applied; binsSkipped=$skipped;
                    backups=$backups; runtimeBins=$bins.Count;
                    appVersion=$ur.version; upstreamRecheck=$ur.text }
  Write-DoctorLog $out
  $out | ConvertTo-Json -Compress
  if ($ur.pending) { $ur.text }
  if ($WithBrief) { Invoke-BriefHeal }  # exits with brief-heal status
  exit 0
}

function Get-TaskRunLine([string]$Name) {
  $xml = schtasks /query /tn $Name /xml 2>$null
  if (-not $xml) { return '' }
  return ($xml -join ' ')
}

function Invoke-InstallSchedule {
  # schtasks CLI path: Register-ScheduledTask is access-denied for this user on this
  # machine; one schtasks task carries one schedule, so use the 10-minute task plus a
  # '<TaskName>-Logon' sibling for the logon trigger. Existing names are never overwritten.
  # keep /tr <= 261 chars (schtasks limit): defaults resolve all paths from the script location
  $self = (Resolve-Path -LiteralPath $PSCommandPath).Path
  $arg = 'powershell -NoProfile -ExecutionPolicy Bypass -File \"' + $self + '\" -Action Heal -WithBrief'
  $made = @()
  if (Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue) {
    # converge the existing task: attach -WithBrief so one schedule covers both heals
    if ((Get-TaskRunLine $TaskName) -notmatch '-WithBrief') {
      schtasks /change /tn $TaskName /tr "$arg" | Out-Null
      if ($LASTEXITCODE -eq 0) { $made += "$TaskName(+WithBrief)"; "UPDATED: $TaskName (attached -WithBrief)" }
      else { "EXISTS: $TaskName (attach -WithBrief failed rc=$LASTEXITCODE)" }
    } else { "EXISTS: $TaskName (already -WithBrief)" }
  } else {
    schtasks /create /tn $TaskName /sc minute /mo 10 /tr "$arg" | Out-Null
    if ($LASTEXITCODE -eq 0) { $made += $TaskName; "REGISTERED: $TaskName (every 10 min)" }
  }
  $logon = "$TaskName-Logon"
  if (Get-ScheduledTask -TaskName $logon -ErrorAction SilentlyContinue) {
    if ((Get-TaskRunLine $logon) -notmatch '-WithBrief') {
      schtasks /change /tn $logon /tr "$arg" | Out-Null
      if ($LASTEXITCODE -eq 0) { $made += "$logon(+WithBrief)"; "UPDATED: $logon (attached -WithBrief)" }
      else { "EXISTS: $logon (attach -WithBrief failed rc=$LASTEXITCODE)" }
    } else { "EXISTS: $logon (already -WithBrief)" }
  } else {
    schtasks /create /tn $logon /sc onlogon /tr "$arg" | Out-Null
    if ($LASTEXITCODE -eq 0) { $made += $logon; "REGISTERED: $logon (at logon)" }
    else {
      # /sc onlogon needs elevation -> per-user Startup .cmd fallback (same coverage, reversible)
      $cmdPath = Join-Path ([Environment]::GetFolderPath('Startup')) "$TaskName.cmd"
      if (Test-Path -LiteralPath $cmdPath) {
        if ((Get-Content -LiteralPath $cmdPath -Raw) -notmatch '-WithBrief') {
          $cmd = "@echo off`r`npowershell -NoProfile -ExecutionPolicy Bypass -File `"$self`" -Action Heal -WithBrief`r`n"
          [IO.File]::WriteAllText($cmdPath, $cmd, [Text.Encoding]::ASCII)
          $made += "$cmdPath(+WithBrief)"; "UPDATED: $cmdPath (attached -WithBrief)"
        } else { "EXISTS: $cmdPath" }
      }
      else {
        $cmd = "@echo off`r`npowershell -NoProfile -ExecutionPolicy Bypass -File `"$self`" -Action Heal -WithBrief`r`n"
        [IO.File]::WriteAllText($cmdPath, $cmd, [Text.Encoding]::ASCII)
        $made += $cmdPath; "REGISTERED: $cmdPath (startup .cmd fallback)"
      }
    }
  }
  Write-DoctorLog @{ action='InstallSchedule'; result=($(if($made){'registered'}else{'exists-skip'})); tasks=$made }
  exit 0
}

function Invoke-UninstallSchedule {
  $removed = @()
  foreach ($n in @($TaskName, "$TaskName-Logon")) {
    if (Get-ScheduledTask -TaskName $n -ErrorAction SilentlyContinue) {
      schtasks /delete /tn $n /f | Out-Null
      if ($LASTEXITCODE -eq 0) { $removed += $n; "REMOVED: $n" }
    } else { "ABSENT: $n" }
  }
  $cmdPath = Join-Path ([Environment]::GetFolderPath('Startup')) "$TaskName.cmd"
  if (Test-Path -LiteralPath $cmdPath) {
    Remove-Item -LiteralPath $cmdPath -Force
    $removed += $cmdPath; "REMOVED: $cmdPath"
  }
  Write-DoctorLog @{ action='UninstallSchedule'; removed=$removed }
  exit 0
}

# --- BriefRead: explicit (R) grant of $BriefDir\PASTE_*.txt|.md to the sandbox group ---

function Test-BriefTarget([System.IO.FileSystemInfo]$F) {
  ($F.Name.StartsWith($BriefPrefix)) -and ($BriefExt -contains $F.Extension.ToLowerInvariant())
}

function Get-BriefAceInfo([string]$Path) {
  $o = [ordered]@{ ExplicitAny=$false; ExplicitRead=$false; InheritedRead=$false; InheritedAny=$false; Deny=$false }
  try { $acl = Get-Acl -LiteralPath $Path -ErrorAction Stop } catch { return [pscustomobject]$o }
  $pat = '(^|\\)' + [regex]::Escape($BriefGroup) + '$'
  foreach ($a in $acl.Access) {
    if ($a.IdentityReference.Value -notmatch $pat) { continue }
    if ($a.AccessControlType -eq [System.Security.AccessControl.AccessControlType]::Deny) { $o.Deny = $true; continue }
    if ($a.IsInherited) { $o.InheritedAny = $true } else { $o.ExplicitAny = $true }
    if ([int64]$a.FileSystemRights -band 0x1) {   # FILE_READ_DATA present -> readable
      if ($a.IsInherited) { $o.InheritedRead = $true } else { $o.ExplicitRead = $true }
    }
  }
  [pscustomobject]$o
}

function Get-BriefScan {
  $targets = @(); $missing = @(); $denied = @(); $covered = 0; $leaks = @(); $inheritedRx = 0
  foreach ($f in @(Get-ChildItem -LiteralPath $BriefDir -File -ErrorAction SilentlyContinue)) {
    $i = Get-BriefAceInfo $f.FullName
    if (Test-BriefTarget $f) {
      $targets += $f.FullName
      if ($i.Deny) { $denied += $f.FullName }
      if ($i.ExplicitRead) { $covered++ } else { $missing += $f.FullName }   # MISSING_EXPLICIT
    } else {
      if ($i.ExplicitAny -or $i.InheritedAny) { $leaks += $f.FullName }       # LEAK_FILE: any group ACE
      elseif ($i.InheritedRead) { $inheritedRx++ }
    }
  }
  $dirInfo = Get-BriefAceInfo $BriefDir
  [ordered]@{ targets=$targets; missing=$missing; denied=$denied; covered=$covered;
              leaks=$leaks; nonTargetInheritedRx=$inheritedRx;
              dirExplicit=$dirInfo.ExplicitAny; dirLeak=($dirInfo.ExplicitAny -or $dirInfo.InheritedAny) }
}

function Invoke-BriefCheck {
  $s = Get-BriefScan
  $drift = $s.dirLeak -or ($s.leaks.Count -gt 0) -or ($s.missing.Count -gt 0) -or ($s.denied.Count -gt 0)
  $out = [ordered]@{ action='BriefCheck'; dir=$BriefDir; pasteTotal=$s.targets.Count;
    covered=$s.covered; missing=$s.missing.Count; leaks=$s.leaks.Count; denied=$s.denied.Count;
    leakFolder=[bool]$s.dirLeak; leakFile=$s.leaks.Count; missingExplicit=$s.missing.Count;
    nonTargetInheritedRx=$s.nonTargetInheritedRx; dirAceExplicit=$s.dirExplicit;
    verdict=($(if($drift){'DRIFT'}else{'OK'})) }
  Write-DoctorLog $out
  $out | ConvertTo-Json -Compress
  if ($s.dirLeak)            { "LEAK_FOLDER: $BriefDir (group ACE on folder -- Heal removes it)" }
  if ($s.leaks.Count -gt 0)  { "LEAK_FILE:";   $s.leaks   | Select-Object -First 10 }
  if ($s.missing.Count -gt 0){ "MISSING_EXPLICIT:"; $s.missing | Select-Object -First 10 }
  if ($s.denied.Count -gt 0) { "DENIED:";      $s.denied  | Select-Object -First 10 }
  if ($drift) { exit 2 } else { exit 0 }
}

function Invoke-BriefHeal {
  $s = Get-BriefScan
  $applied = 0; $failed = @(); $leaksRemoved = 0; $backup = ''; $dirRemoved = $false
  if ((-not $DryRun) -and ($s.dirLeak -or $s.leaks.Count -gt 0)) {
    if (-not (Test-Path -LiteralPath $StateDir)) { New-Item -ItemType Directory -Force -Path $StateDir | Out-Null }
    $backup = Join-Path $StateDir ("acl-briefdir-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".txt")
    icacls $BriefDir /save $backup /T /C | Out-Null
    if (-not (Test-Path -LiteralPath $backup)) { $backup = '' }
  }
  if ($s.dirLeak) {
    if ($DryRun) { $dirRemoved = $true }
    else {
      icacls $BriefDir /remove:g "${BriefGroup}" /Q | Out-Null
      if ($LASTEXITCODE -eq 0) { $dirRemoved = $true } else { $failed += $BriefDir }
    }
  }
  foreach ($p in $s.leaks) {
    $i = Get-BriefAceInfo $p
    if (-not $i.ExplicitAny) { continue }   # inherited-only entries clear via folder fix
    if ($DryRun) { $leaksRemoved++; continue }
    icacls "$p" /remove:g "${BriefGroup}" /Q | Out-Null
    if ($LASTEXITCODE -eq 0) { $leaksRemoved++ } else { $failed += $p }
  }
  foreach ($p in $s.missing) {
    if ($DryRun) { $applied++; continue }
    icacls "$p" /grant "${BriefGroup}:(R)" /Q | Out-Null
    if ($LASTEXITCODE -eq 0) { $applied++ } else { $failed += $p }
  }
  $out = [ordered]@{ action='BriefHeal'; dryRun=[bool]$DryRun; dir=$BriefDir;
    pasteTotal=$s.targets.Count; granted=$applied; failed=$failed.Count; leaks=$s.leaks.Count;
    dirLeakRemoved=$dirRemoved; leaksRemoved=$leaksRemoved; backup=$backup }
  Write-DoctorLog $out
  $out | ConvertTo-Json -Compress
  if ($failed.Count -gt 0) { "FAILED:"; $failed | Select-Object -First 10; exit 2 }
  exit 0
}

function Invoke-BriefRevoke {
  # rollback: remove the group ACE from the folder AND from every file under it.
  $removed = 0; $failed = @()
  $dirInfo = Get-BriefAceInfo $BriefDir
  if ($dirInfo.ExplicitAny -or $dirInfo.InheritedAny) {
    if ($DryRun) { $removed++ }
    else {
      icacls $BriefDir /remove:g "${BriefGroup}" /Q | Out-Null
      if ($LASTEXITCODE -eq 0) { $removed++ } else { $failed += $BriefDir }
    }
  }
  foreach ($f in @(Get-ChildItem -LiteralPath $BriefDir -File -ErrorAction SilentlyContinue)) {
    $i = Get-BriefAceInfo $f.FullName
    if (-not $i.ExplicitAny) { continue }
    if ($DryRun) { $removed++; continue }
    icacls "$($f.FullName)" /remove:g "${BriefGroup}" /Q | Out-Null
    if ($LASTEXITCODE -eq 0) { $removed++ } else { $failed += $f.FullName }
  }
  $out = [ordered]@{ action='BriefRevoke'; dryRun=[bool]$DryRun; dir=$BriefDir; removed=$removed; failed=$failed.Count }
  Write-DoctorLog $out
  $out | ConvertTo-Json -Compress
  if ($failed.Count -gt 0) { "FAILED:"; $failed | Select-Object -First 10; exit 2 }
  exit 0
}

function Invoke-BriefInstall {
  $self = (Resolve-Path -LiteralPath $PSCommandPath).Path
  $arg = 'powershell -NoProfile -ExecutionPolicy Bypass -File \"' + $self + '\" -BriefRead -Heal'
  $made = @()
  if (Get-ScheduledTask -TaskName $BriefTaskName -ErrorAction SilentlyContinue) {
    "EXISTS: $BriefTaskName"
  } else {
    schtasks /create /tn $BriefTaskName /sc minute /mo 10 /tr "$arg" | Out-Null
    if ($LASTEXITCODE -eq 0) { $made += $BriefTaskName; "REGISTERED: $BriefTaskName (every 10 min)" }
  }
  $logon = "$BriefTaskName-Logon"
  if (Get-ScheduledTask -TaskName $logon -ErrorAction SilentlyContinue) {
    "EXISTS: $logon"
  } else {
    schtasks /create /tn $logon /sc onlogon /tr "$arg" | Out-Null
    if ($LASTEXITCODE -eq 0) { $made += $logon; "REGISTERED: $logon (at logon)" }
    else {
      $cmdPath = Join-Path ([Environment]::GetFolderPath('Startup')) "$BriefTaskName.cmd"
      if (Test-Path -LiteralPath $cmdPath) { "EXISTS: $cmdPath" }
      else {
        $cmd = "@echo off`r`npowershell -NoProfile -ExecutionPolicy Bypass -File `"$self`" -BriefRead -Heal`r`n"
        [IO.File]::WriteAllText($cmdPath, $cmd, [Text.Encoding]::ASCII)
        $made += $cmdPath; "REGISTERED: $cmdPath (startup .cmd fallback)"
      }
    }
  }
  Write-DoctorLog @{ action='BriefInstall'; result=($(if($made){'registered'}else{'exists-skip'})); tasks=$made }
  exit 0
}

function Invoke-BriefUninstall {
  $removed = @()
  foreach ($n in @($BriefTaskName, "$BriefTaskName-Logon")) {
    if (Get-ScheduledTask -TaskName $n -ErrorAction SilentlyContinue) {
      schtasks /delete /tn $n /f | Out-Null
      if ($LASTEXITCODE -eq 0) { $removed += $n; "REMOVED: $n" }
    } else { "ABSENT: $n" }
  }
  $cmdPath = Join-Path ([Environment]::GetFolderPath('Startup')) "$BriefTaskName.cmd"
  if (Test-Path -LiteralPath $cmdPath) {
    Remove-Item -LiteralPath $cmdPath -Force
    $removed += $cmdPath; "REMOVED: $cmdPath"
  }
  Write-DoctorLog @{ action='BriefUninstall'; removed=$removed }
  exit 0
}

$bm = ''
if     ($Check)     { $bm = 'Check' }
elseif ($Heal)      { $bm = 'Heal' }
elseif ($Revoke)    { $bm = 'Revoke' }
elseif ($Install)   { $bm = 'Install' }
elseif ($Uninstall) { $bm = 'Uninstall' }
elseif ($BriefMode) { $bm = $BriefMode }
elseif ($BriefArgs -and $BriefArgs[0] -match '^(Check|Heal|Revoke|Install|Uninstall)$') { $bm = $Matches[1] }
if ($BriefRead -or $bm) {
  if (-not $BriefRead -and $bm) { "ERROR: -$bm requires -BriefRead"; exit 1 }
  if (-not $bm) { $bm = 'Check' }
  try {
    switch ($bm) {
      'Check'     { Invoke-BriefCheck }
      'Heal'      { Invoke-BriefHeal }
      'Revoke'    { Invoke-BriefRevoke }
      'Install'   { Invoke-BriefInstall }
      'Uninstall' { Invoke-BriefUninstall }
    }
  } catch {
    Write-DoctorLog @{ action="Brief$bm"; result='error'; error=$_.Exception.Message }
    "ERROR: $($_.Exception.Message)"; exit 1
  }
}
if ($WithBrief -and $Action -ne 'Heal') { "ERROR: -WithBrief applies only to -Action Heal"; exit 1 }
try {
  switch ($Action) {
    'Check'             { Invoke-Check }
    'Heal'              { Invoke-Heal }
    'InstallSchedule'   { Invoke-InstallSchedule }
    'UninstallSchedule' { Invoke-UninstallSchedule }
  }
} catch {
  Write-DoctorLog @{ action=$Action; result='error'; error=$_.Exception.Message }
  "ERROR: $($_.Exception.Message)"; exit 1
}
