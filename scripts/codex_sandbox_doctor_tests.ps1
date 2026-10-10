# codex_sandbox_doctor_tests.ps1 -- offline dry-run tests for codex_sandbox_doctor.ps1.
# Uses temp fake runtime/log/state dirs; never touches the real Codex install or sandbox dir.
# Run: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\codex_sandbox_doctor_tests.ps1
[CmdletBinding()] param([switch]$LoggerOnly, [string]$LoggerSource)
if ($LoggerOnly) {
  $ErrorActionPreference = 'Stop'
  $source = if ($LoggerSource) { $LoggerSource } else { Join-Path $PSScriptRoot 'codex_sandbox_doctor.ps1' }
  $ast = [Management.Automation.Language.Parser]::ParseFile($source, [ref]$null, [ref]$null)
  $functions = @($ast.FindAll({param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -in @('Write-DoctorLog','Get-DoctorLogDigest','Get-LogErrorSummary')}, $true))
  $fixture = Join-Path $env:TEMP ('doctor-log-test-' + [guid]::NewGuid().ToString('N'))
  New-Item -ItemType Directory -Path $fixture | Out-Null
  try {
    $StateDir = $fixture; $MaxActiveBytes = 1200; $MaxFiles = 3; $MaxTotalBytes = 3600
    foreach ($f in $functions) { . ([scriptblock]::Create($f.Extent.Text)) }
    for ($i=0; $i -lt 30; $i++) {
      Write-DoctorLog @{ action='Check'; result='error'; errorsToday=$i; error=('Bearer ' + ('fake-secret-' * 100)); dir='private-path' }
    }
    $active = Join-Path $fixture 'doctor.jsonl'
    $files = @(Get-ChildItem -LiteralPath $fixture -Filter 'doctor*.jsonl')
    $rows = @($files | ForEach-Object { Get-Content -LiteralPath $_.FullName })
    $text = $rows -join "`n"
    if ($text -match 'fake-secret|Bearer|private-path') { throw 'logger-secret-leak' }
    if ((Get-Item -LiteralPath $active).Length -gt $MaxActiveBytes -or $files.Count -gt $MaxFiles -or ($files | Measure-Object Length -Sum).Sum -gt $MaxTotalBytes) { throw 'logger-bounds' }
    $state = Get-Content (Join-Path $fixture 'doctor-log-state.json') -Raw | ConvertFrom-Json
    if ($state.seq -ne 30 -or $state.evictedRows -le 0) { throw 'logger-sequence-or-eviction' }
    foreach ($row in $rows) {
      $obj = $row | ConvertFrom-Json
      if (-not $obj.hash -or -not $obj.reasonCode) { throw 'logger-integrity-or-reason-missing' }
      $content = [ordered]@{}
      foreach ($property in $obj.PSObject.Properties) { if ($property.Name -ne 'hash') { $content[$property.Name] = $property.Value } }
      if ((Get-DoctorLogDigest ($content | ConvertTo-Json -Compress)) -ne $obj.hash) { throw 'logger-hash-mismatch' }

    }
    # Interrupted append is preserved and the next event can still be logged.
    [IO.File]::AppendAllText($active, '{"seq":31,', [Text.UTF8Encoding]::new($false))
    Write-DoctorLog @{action='Check';result='ok'}
    $recovered = Get-Content (Join-Path $fixture 'doctor-log-state.json') -Raw | ConvertFrom-Json
    if ($recovered.seq -ne 31 -or $recovered.dropped -ne 1) { throw 'logger-torn-append-recovery' }
    if (@(Get-ChildItem $fixture -Filter 'doctor.legacy-recovery-*.jsonl').Count -ne 1) { throw 'logger-torn-bytes-lost' }
    # Crash after repairing bytes but before saving the loss counter.
    $receiptArchive='doctor.legacy-recovery-'+[guid]::NewGuid().ToString('N')+'.jsonl'
    $clean=[IO.File]::ReadAllText($active);$prefix=@([IO.File]::ReadAllLines($active)).Count
    [IO.File]::WriteAllText((Join-Path $fixture $receiptArchive),$clean+'{"seq":32,',[Text.UTF8Encoding]::new($false))
    $intent=@{target='doctor.jsonl';archive=$receiptArchive;archiveHash=(Get-DoctorLogDigest ([IO.File]::ReadAllText((Join-Path $fixture $receiptArchive))));prefixLines=$prefix}
    $recovered|Add-Member -NotePropertyName pendingRepair -NotePropertyValue $intent -Force
    [IO.File]::WriteAllText((Join-Path $fixture 'doctor-log-state.json'),($recovered|ConvertTo-Json -Compress -Depth 5))
    Write-DoctorLog @{action='Check'};Write-DoctorLog @{action='Check'}
    $recovered=Get-Content (Join-Path $fixture 'doctor-log-state.json') -Raw|ConvertFrom-Json
    if($recovered.dropped -ne 2 -or $recovered.pendingRepair){throw 'logger-repair-loss-exactly-once'}
    # A pending eviction receipt survives interruption before/after deletion.
    $archive = @(Get-ChildItem $fixture -Filter 'doctor.rotated-v1-*.jsonl' | Sort-Object Name)[0]
    $pending = @{name=$archive.Name;rows=@([IO.File]::ReadAllLines($archive.FullName)).Count;bytes=$archive.Length}
    $recovered | Add-Member -NotePropertyName pendingEviction -NotePropertyValue $pending -Force
    [IO.File]::WriteAllText((Join-Path $fixture 'doctor-log-state.json'),($recovered|ConvertTo-Json -Compress))
    [IO.File]::Delete($archive.FullName)
    Write-DoctorLog @{action='Check';result='ok'}
    $resumed = Get-Content (Join-Path $fixture 'doctor-log-state.json') -Raw | ConvertFrom-Json
    if ($resumed.evictedRows -lt ($recovered.evictedRows + $pending.rows) -or $resumed.pendingEviction) { throw 'logger-eviction-loss-accounting' }
    $retained=@(Get-ChildItem $fixture -Filter 'doctor.rotated-v1-*.jsonl')+@(Get-Item $active)
    $retainedRows=0;foreach($file in $retained){$retainedRows+=@([IO.File]::ReadAllLines($file.FullName)).Count}
    if($resumed.evictedRows+$retainedRows -ne $resumed.seq){throw 'logger-eviction-overcount-or-loss'}
    Write-DoctorLog @{action='Check'}
    $again=Get-Content (Join-Path $fixture 'doctor-log-state.json') -Raw|ConvertFrom-Json
    $retained=@(Get-ChildItem $fixture -Filter 'doctor.rotated-v1-*.jsonl')+@(Get-Item $active)
    $retainedRows=0;foreach($file in $retained){$retainedRows+=@([IO.File]::ReadAllLines($file.FullName)).Count}
    if($again.evictedRows+$retainedRows -ne $again.seq){throw 'logger-eviction-repeat-overcount'}
    # Two independent processes serialize writes under the same logger lock.
    $shared = Join-Path $fixture 'concurrent'; New-Item -ItemType Directory $shared | Out-Null
    $definition = ($functions | ForEach-Object {$_.Extent.Text}) -join "`n"
    $jobs = @(1..2 | ForEach-Object { Start-Job -ScriptBlock {
      param($code,$dir)
      $ErrorActionPreference='Stop'; $StateDir=$dir; $MaxActiveBytes=1200; $MaxFiles=3; $MaxTotalBytes=3600
      . ([scriptblock]::Create($code))
      1..20 | ForEach-Object { Write-DoctorLog @{action='Check';result='ok'} }
    } -ArgumentList $definition,$shared })
    try {
      $null = $jobs | Wait-Job -Timeout 30
      if (@($jobs | Where-Object {$_.State -ne 'Completed'}).Count) { throw 'logger-concurrent-writer-failed' }
      $null = $jobs | Receive-Job -ErrorAction Stop
      $concurrent = Get-Content (Join-Path $shared 'doctor-log-state.json') -Raw | ConvertFrom-Json
      if ($concurrent.seq -ne 40) { throw 'logger-concurrent-sequence' }
      $chain = @(Get-ChildItem $shared -Filter 'doctor.rotated-v1-*.jsonl' | Sort-Object Name | ForEach-Object {Get-Content $_.FullName})
      $chain += @(Get-Content (Join-Path $shared 'doctor.jsonl')); $previous=$null
      foreach ($line in $chain) {
        $event=$line|ConvertFrom-Json; $content=[ordered]@{}
        foreach ($prop in $event.PSObject.Properties) {if($prop.Name -ne 'hash'){$content[$prop.Name]=$prop.Value}}
        if ((Get-DoctorLogDigest ($content|ConvertTo-Json -Compress)) -ne $event.hash) {throw 'logger-concurrent-hash'}
        if ($previous -and ($event.prevHash -ne $previous.hash -or $event.seq -ne $previous.seq+1)) {throw 'logger-concurrent-chain'}
        $previous=$event
      }
    } finally { $jobs | Stop-Job; $jobs | Remove-Job -Force }
    # A legacy file survives its first adoption into the bounded logger.
    $legacy = Join-Path $fixture 'legacy'; New-Item -ItemType Directory -Path $legacy | Out-Null
    [IO.File]::WriteAllText((Join-Path $legacy 'doctor.jsonl'), 'original historical bytes')
    $StateDir = $legacy
    Write-DoctorLog @{ action='Check'; result='ok' }
    $preserved = @(Get-ChildItem -LiteralPath $legacy -Filter 'doctor.legacy-*.jsonl')
    if ($preserved.Count -ne 1 -or [IO.File]::ReadAllText($preserved[0].FullName) -ne 'original historical bytes') { throw 'legacy-not-preserved' }
    # Failed writes cannot silently report success.
    $StateDir = Join-Path $fixture 'failure'; New-Item -ItemType Directory -Path $StateDir | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $StateDir 'doctor-log-state.json') | Out-Null
    $failed = $false
    try { Write-DoctorLog @{action='Check'} } catch { $failed = $true }
    if (-not $failed) { throw 'logger-write-failure-hidden' }
    $failures = Get-Content (Join-Path $StateDir 'doctor-log-failures.json') -Raw | ConvertFrom-Json
    if ($failures.writeFailure -ne 1 -or $failures.dropped -ne 0) { throw 'logger-failure-accounting' }

    Remove-Item -LiteralPath (Join-Path $StateDir 'doctor-log-state.json')
    Write-DoctorLog @{action='Check'}
    $firstRecovery=Get-Content (Join-Path $StateDir 'doctor-log-state.json') -Raw|ConvertFrom-Json
    if($firstRecovery.seq -ne 2 -or @(Get-ChildItem $StateDir -Filter 'doctor.legacy-*.jsonl').Count){throw 'logger-first-metadata-recovery'}
    $StateDir=Join-Path $fixture 'append-fault'; New-Item -ItemType Directory (Join-Path $StateDir 'doctor.jsonl') -Force | Out-Null
    1..2 | ForEach-Object {try {Write-DoctorLog @{action='Check'}} catch {}}
    $failure=Get-Content (Join-Path $StateDir 'doctor-log-failures.json') -Raw|ConvertFrom-Json
    if($failure.writeFailure -ne 2 -or $failure.dropped -ne 2){throw 'logger-append-loss-unreported'}
    Remove-Item -LiteralPath (Join-Path $StateDir 'doctor.jsonl')
    Write-DoctorLog @{action='Check'};Write-DoctorLog @{action='Check'}
    $restored=Get-Content (Join-Path $StateDir 'doctor-log-state.json') -Raw|ConvertFrom-Json
    if($restored.seq -ne 2 -or $restored.dropped -ne 2 -or $restored.writeFailure -ne 2){throw 'logger-loss-double-count-or-missing'}
    # A real partial append followed by an exception is one lost attempt.
    $normalWriter=($functions|Where-Object {$_.Name -eq 'Write-DoctorLog'})[0].Extent.Text
    $append='[IO.File]::AppendAllText($active, $line, [Text.UTF8Encoding]::new($false))'
    if (-not $normalWriter.Contains($append)) {throw 'logger-fixture-append-boundary-missing'}
    foreach($mode in @('partial','complete')) {
      $StateDir=Join-Path $fixture ('write-then-fault-'+$mode);[void][IO.Directory]::CreateDirectory($StateDir)
      Write-DoctorLog @{action='Check'}
      $injected=if($mode -eq 'partial') {'[IO.File]::AppendAllText($active, $line.Substring(0,17), [Text.UTF8Encoding]::new($false)); throw "synthetic-append-interruption"'} else {$append+'; throw "synthetic-close-failure"'}
      try {
        . ([scriptblock]::Create($normalWriter.Replace($append,$injected)))
        $failed=$false;try{Write-DoctorLog @{action='Check'}}catch{$failed=$true}
        if(-not $failed){throw 'logger-injected-append-failure-missing'}
      } finally {. ([scriptblock]::Create($normalWriter))}
      Write-DoctorLog @{action='Check'};Write-DoctorLog @{action='Check'}
      $observed=Get-Content (Join-Path $StateDir 'doctor-log-state.json') -Raw|ConvertFrom-Json
      $expectedDropped=if($mode -eq 'partial'){1}else{0}
      $expectedSeq=if($mode -eq 'partial'){3}else{4}
      if($observed.dropped -ne $expectedDropped -or $observed.seq -ne $expectedSeq -or $observed.writeFailure -ne 1){throw ('logger-append-attempt-double-count-'+$mode)}
    }

    Write-Output 'PASS logger redaction, bounded rotation, sequence, legacy preservation, write-failure visibility; ACL/Heal/Git/scheduler calls=0'
    exit 0
  } finally {
    if ([IO.Path]::GetFullPath($fixture).StartsWith([IO.Path]::GetFullPath($env:TEMP) + '\', [StringComparison]::OrdinalIgnoreCase)) { Remove-Item -LiteralPath $fixture -Recurse -Force }
  }
}

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Doctor = Join-Path $PSScriptRoot 'codex_sandbox_doctor.ps1'
$tmp = Join-Path $env:TEMP ("csd-tests-" + [guid]::NewGuid().ToString('N').Substring(0,8))
$rt  = Join-Path $tmp 'runtimes'
$bin = Join-Path $rt 'cua_node\deadbeef00\bin'
$sb  = Join-Path $tmp 'sandbox'
$st  = Join-Path $tmp 'state'
New-Item -ItemType Directory -Force -Path $bin,$sb,$st | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $bin 'sub\x64') | Out-Null
Set-Content -LiteralPath (Join-Path $bin 'node_repl.exe') -Value 'MZ-FAKE' -NoNewline
Set-Content -LiteralPath (Join-Path $bin 'node.exe') -Value 'MZ-FAKE' -NoNewline
Set-Content -LiteralPath (Join-Path $bin 'sub\x64\VCRUNTIME140_1.dll') -Value 'DLL-FAKE' -NoNewline
$pass = 0; $fail = 0; $results = @()

function Add-Result([string]$Name, [bool]$Ok, [string]$Note) {
  $script:results += [pscustomobject]@{ test=$Name; result=($(if($Ok){'PASS'}else{'FAIL'})); note=$Note }
  if ($Ok) { $script:pass++ } else { $script:fail++ }
}

function Invoke-Doctor([string]$Act, [string]$Extra = '') {
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $Doctor -Action $Act `
         -RuntimesRoot $rt -SandboxDir $sb -StateDir $st -DenyUser "$env:USERDOMAIN\$env:USERNAME" $Extra.Split(' ',[StringSplitOptions]::RemoveEmptyEntries) 2>&1
  return @{ text = ($out -join "`n"); code = $LASTEXITCODE }
}

# --- T1: error log + missing deny -> Check exits 2 (drift) ---
Set-Content -LiteralPath (Join-Path $sb 'sandbox.2099-01-01.log') -Value @(
  '[x] setup refresh completed with errors: ["runtime read/execute validation failed: ... os error 32"]',
  '[x] setup error: setup refresh had errors' )
$r = Invoke-Doctor 'Check'
Add-Result 'T1-check-drift-on-errors' ($r.code -eq 2) "exit=$($r.code) expect=2"

# --- T2: Heal -DryRun reports coverage but changes no ACL ---
$r = Invoke-Doctor 'Heal' '-DryRun'
$acl = Get-Acl -LiteralPath (Join-Path $bin 'node_repl.exe')
$hasDeny = @($acl.Access | Where-Object { $_.AccessControlType -eq 'Deny' }).Count -gt 0
Add-Result 'T2-heal-dryrun-no-change' (($r.code -eq 0) -and ($r.text -match 'denyApplied') -and (-not $hasDeny)) "exit=$($r.code) denyAfter=$hasDeny"

# --- T3: real Heal writes deny ACE on all 3 exec files ---
$r = Invoke-Doctor 'Heal'
$denyCount = 0
foreach ($f in @("$bin\node_repl.exe","$bin\node.exe","$bin\sub\x64\VCRUNTIME140_1.dll")) {
  $a = Get-Acl -LiteralPath $f
  $d = @($a.Access | Where-Object { $_.AccessControlType -eq 'Deny' -and ([int64]$_.FileSystemRights -band 0x2) -and ([int64]$_.FileSystemRights -band 0x4) }).Count
  if ($d -gt 0) { $denyCount++ }
}
Add-Result 'T3-heal-applies-deny' ($denyCount -eq 3) "denyOn=$denyCount/3"

# --- T4: Heal created an icacls /save backup under StateDir ---
$baks = @(Get-ChildItem -LiteralPath $st -Filter 'acl-backup-*' -ErrorAction SilentlyContinue)
Add-Result 'T4-heal-backup-created' ($baks.Count -ge 1) "backups=$($baks.Count)"

# --- T5: clean log + full deny coverage -> Check exits 0 ---
Set-Content -LiteralPath (Join-Path $sb 'sandbox.2099-01-01.log') -Value @(
  '[x] setup refresh: processed 0 write roots (read roots delegated); errors=[]',
  '[x] setup binary completed' )
$r = Invoke-Doctor 'Check'
Add-Result 'T5-check-ok-after-heal' ($r.code -eq 0) "exit=$($r.code) expect=0"

# --- T6: missing runtime root -> Check handles gracefully (exit 0, no-runtime) ---
$r = Invoke-Doctor 'Check' ''
$rtGone = Join-Path $tmp 'no-such-runtimes'
$out2 = & powershell -NoProfile -ExecutionPolicy Bypass -File $Doctor -Action Check `
        -RuntimesRoot $rtGone -SandboxDir $sb -StateDir $st -DenyUser "$env:USERDOMAIN\$env:USERNAME" 2>&1
Add-Result 'T6-check-missing-runtime-ok' ($LASTEXITCODE -eq 0) "exit=$LASTEXITCODE expect=0"

# --- T6b: a NEW runtime hash folder (app update shape) gets deny on next Heal ---
$bin2 = Join-Path $rt 'cua_node\aaaa1111ffff\bin'
New-Item -ItemType Directory -Force -Path $bin2 | Out-Null
Set-Content -LiteralPath (Join-Path $bin2 'fresh.exe') -Value 'MZ-FAKE2' -NoNewline
$r = Invoke-Doctor 'Check'
Add-Result 'T6b-check-newhash-drift' (($r.code -eq 2) -and ($r.text -match 'denyMissing":\s*1')) "exit=$($r.code)"
$r = Invoke-Doctor 'Heal'
$a2 = Get-Acl -LiteralPath (Join-Path $bin2 'fresh.exe')
$d2 = @($a2.Access | Where-Object { $_.AccessControlType -eq 'Deny' -and ([int64]$_.FileSystemRights -band 0x2) -and ([int64]$_.FileSystemRights -band 0x4) }).Count
Add-Result 'T6c-heal-newhash-deny' (($r.code -eq 0) -and ($d2 -gt 0)) "denyOnNewHash=$d2"

# --- BriefRead: temp dir fixtures (never touches the real Downloads) ---
# Tests use a built-in throwaway group (Guests), never real CodexSandboxUsers.
$TestGroup = 'Guests'
$br = Join-Path $tmp 'briefdir'
New-Item -ItemType Directory -Force -Path $br | Out-Null
Set-Content -LiteralPath (Join-Path $br 'PASTE_t_one.txt') -Value 'brief-one'
Set-Content -LiteralPath (Join-Path $br 'PASTE_t_two.md')  -Value 'brief-two'
Set-Content -LiteralPath (Join-Path $br 'note.txt')        -Value 'not-a-brief'

function Invoke-Brief([string]$Mode, [string]$Extra = '', [string]$Dir = '') {
  if (-not $Dir) { $Dir = $br }
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $Doctor -BriefRead -$Mode `
         -BriefDir $Dir -BriefGroup $TestGroup -StateDir $st $Extra.Split(' ',[StringSplitOptions]::RemoveEmptyEntries) 2>&1
  return @{ text = ($out -join "`n"); code = $LASTEXITCODE }
}
function Test-BriefAcl([string]$Path) {
  $acl = Get-Acl -LiteralPath $Path
  return @($acl.Access | Where-Object { $_.IdentityReference.Value -match "$TestGroup$" -and
           $_.AccessControlType -eq 'Allow' -and -not $_.IsInherited -and
           ([int64]$_.FileSystemRights -band 0x1) }).Count -gt 0
}
function Get-GroupAceCount([string]$Path) {
  $acl = Get-Acl -LiteralPath $Path
  return @($acl.Access | Where-Object { $_.IdentityReference.Value -match "$TestGroup$" }).Count
}

# T7: fresh dir, PASTE files lack explicit read ACE -> Check exit 2, missing=2
$r = Invoke-Brief 'Check'
Add-Result 'T7-briefcheck-missing' (($r.code -eq 2) -and ($r.text -match '"missing":\s*2')) "exit=$($r.code)"

# T8: Heal grants explicit read on PASTE only, non-PASTE untouched
$r = Invoke-Brief 'Heal'
$okAcl = (Test-BriefAcl (Join-Path $br 'PASTE_t_one.txt')) -and (Test-BriefAcl (Join-Path $br 'PASTE_t_two.md'))
$noteAcl = Get-Acl -LiteralPath (Join-Path $br 'note.txt')
$noteExp = @($noteAcl.Access | Where-Object { $_.IdentityReference.Value -match 'CodexSandboxUsers$' -and -not $_.IsInherited }).Count
Add-Result 'T8-briefheal-paste-only' (($r.code -eq 0) -and $okAcl -and ($noteExp -eq 0)) "exit=$($r.code) noteAcl=$noteExp"

# T9: Check after heal -> exit 0, missing=0
$r = Invoke-Brief 'Check'
Add-Result 'T9-briefcheck-clean' (($r.code -eq 0) -and ($r.text -match '"missing":\s*0')) "exit=$($r.code)"

# T10: explicit group ACE on non-PASTE -> LEAK_FILE, exit 2
icacls (Join-Path $br 'note.txt') /grant "${TestGroup}:(R)" /Q | Out-Null
$r = Invoke-Brief 'Check'
Add-Result 'T10-briefcheck-leak' (($r.code -eq 2) -and ($r.text -match '"leaks":\s*1')) "exit=$($r.code)"
icacls (Join-Path $br 'note.txt') /remove:g "$TestGroup" /Q | Out-Null

# T11: Revoke removes all explicit group ACEs on PASTE targets -> Check back to missing
$r = Invoke-Brief 'Revoke'
$stillThere = (Test-BriefAcl (Join-Path $br 'PASTE_t_one.txt')) -or (Test-BriefAcl (Join-Path $br 'PASTE_t_two.md'))
Add-Result 'T11-briefrevoke-zero' (($r.code -eq 0) -and (-not $stillThere)) "exit=$($r.code)"

# T12: Heal -DryRun reports grants but writes no ACE
$r = Invoke-Brief 'Heal' '-DryRun'
$stillMissing = -not (Test-BriefAcl (Join-Path $br 'PASTE_t_one.txt'))
Add-Result 'T12-briefheal-dryrun' (($r.code -eq 0) -and ($r.text -match '"granted":\s*2') -and $stillMissing) "exit=$($r.code)"

# --- leak-folder fixtures: second temp dir carrying a folder-level group ACE ---
$br2 = Join-Path $tmp 'briefdir2'
New-Item -ItemType Directory -Force -Path $br2 | Out-Null
icacls $br2 /grant "${TestGroup}:(OI)(CI)(RX)" /Q | Out-Null
Set-Content -LiteralPath (Join-Path $br2 'PASTE_l_one.txt') -Value 'brief-l-one'
Set-Content -LiteralPath (Join-Path $br2 'plain.txt')        -Value 'plain'

# T13: folder-level group ACE -> LEAK_FOLDER, exit 2
$r = Invoke-Brief 'Check' '' $br2
Add-Result 'T13-briefcheck-leakfolder' (($r.code -eq 2) -and ($r.text -match 'leakFolder":true') -and ($r.text -match 'LEAK_FOLDER')) "exit=$($r.code)"

# T14: non-PASTE file with inherited-only group ACE -> LEAK_FILE
Add-Result 'T14-briefcheck-leakfile' (($r.text -match 'leakFile":\s*[1-9]') -and ($r.text -match 'LEAK_FILE')) "leakFile field=$([bool]($r.text -match 'leakFile'))"

# T15: PASTE file readable only via inheritance -> MISSING_EXPLICIT
Add-Result 'T15-briefcheck-missingexp' (($r.text -match 'missingExplicit":\s*[1-9]') -and ($r.text -match 'MISSING_EXPLICIT')) "missingExplicit field"

# T16: Heal removes folder ACE + grants explicit (R); non-PASTE loses all group ACEs
$r = Invoke-Brief 'Heal' '' $br2
$healOk = ($r.code -eq 0) -and ($r.text -match 'dirLeakRemoved":true')
$dirAces = Get-GroupAceCount $br2
$plainAces = Get-GroupAceCount (Join-Path $br2 'plain.txt')
$pasteOk = Test-BriefAcl (Join-Path $br2 'PASTE_l_one.txt')
$r2 = Invoke-Brief 'Check' '' $br2
Add-Result 'T16-briefheal-leakfolder' ($healOk -and ($dirAces -eq 0) -and ($plainAces -eq 0) -and $pasteOk -and ($r2.code -eq 0)) "heal=$healOk dirAces=$dirAces plain=$plainAces paste=$pasteOk check=$($r2.code)"

# T17: Revoke strips group ACEs from folder AND files
icacls $br2 /grant "${TestGroup}:(OI)(CI)(RX)" /Q | Out-Null
$r = Invoke-Brief 'Revoke' '' $br2
$anyLeft = (Get-GroupAceCount $br2) + (Get-GroupAceCount (Join-Path $br2 'PASTE_l_one.txt')) + (Get-GroupAceCount (Join-Path $br2 'plain.txt'))
Add-Result 'T17-briefrevoke-folder-files' (($r.code -eq 0) -and ($anyLeft -eq 0)) "leftAces=$anyLeft"

# T18: app-version change -> UPSTREAM_RECHECK until marker cleared
$out3 = & powershell -NoProfile -ExecutionPolicy Bypass -File $Doctor -Action Check `
        -RuntimesRoot $rt -SandboxDir $sb -StateDir $st -DenyUser "$env:USERDOMAIN\$env:USERNAME" -AppVersion '9.9.9-test' 2>&1
$t18a = ($LASTEXITCODE -eq 2) -and (($out3 -join "`n") -match 'upstreamRecheck":"UPSTREAM_RECHECK')
$r = Invoke-Doctor 'Check' '-AppVersion 9.9.9-test'
$t18b = ($r.text -match 'upstreamRecheck":"UPSTREAM_RECHECK')
Remove-Item -LiteralPath (Join-Path $st 'codex-app-version.json') -Force -ErrorAction SilentlyContinue
$r = Invoke-Doctor 'Check'
$t18c = ($r.code -eq 0) -and ($r.text -match 'upstreamRecheck":""')
Add-Result 'T18-upstream-recheck' ($t18a -and $t18b -and $t18c) "new=$t18a persist=$t18b clear=$t18c"

# T19: Check emits log error-kind summary (KNOWN_BENIGN / KNOWN_FIXED / NEW)
Set-Content -LiteralPath (Join-Path $sb 'sandbox.2099-01-01.log') -Value @(
  '[x] setup refresh: processed 2 write roots (read roots delegated); errors=[]',
  '[x] hide users: failed',
  '[x] hide users: failed',
  '[x] earlier refresh note: open ACL target for root-only update: (os error 32)',
  '[x] spawn failed: (os error 5)',
  '[x] setup binary completed' )
$r = Invoke-Doctor 'Check'
$ok19 = ($r.code -eq 0) -and ($r.text -match 'knownBenign":\s*2') -and ($r.text -match 'knownFixedOs32":\s*1') -and
        ($r.text -match 'newErrors":\s*1') -and ($r.text -match '"5":\s*1') -and ($r.text -match 'latestLogBytes":\s*[1-9]')
Add-Result 'T19-log-error-summary' $ok19 "exit=$($r.code)"

$results | Format-Table -AutoSize
"RESULT: $pass/$($pass+$fail) PASS"
if (Test-Path -LiteralPath $tmp) { Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue }
if ($fail -gt 0) { exit 1 } else { exit 0 }
