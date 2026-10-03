#requires -Version 5.1
<#
.SYNOPSIS
  devin_cwd_doctor.ps1 - diagnose and repair Devin Local sessions that start
  in $HOME instead of the demo-1 Project Root.

  Checks:
    C1  %APPDATA%\Devin\Workspaces\*\workspace.json folders resolving to $HOME or empty
    C2  %APPDATA%\devin\User\settings.json has terminal.integrated.cwd = <SrcRoot>
    C3  global_rules.md contains the DEMO1-CWD-GUARD block
    C4  demo1-src.code-workspace exists with folders = <SrcRoot>
    C5  current $PWD + PS version (informational)

  Exit codes: 0 = all OK, 1 = runtime/usage error, 2 = issues found (BAD/STAGED/CORRUPT).
  A workspace whose workspace.json mtime is inside -ActiveWindowSeconds, or which is
  the newest workspace overall, is treated as possibly-open and reported STAGED:
  -Check flags it, -Fix skips it unless -IncludeActive is given.
  Out-of-repo files are always backed up as <name>.bak-yyyyMMdd-HHmm before -Fix.
  No key/token values are ever printed. PowerShell 5.1 syntax only.
#>
param(
  [switch]$Check,
  [switch]$Fix,
  [switch]$IncludeActive,
  [string]$Restore,
  [string]$SrcRoot = $(if ($env:AWX_ROOT) { $env:AWX_ROOT } elseif ($PSScriptRoot) { (Resolve-Path (Join-Path $PSScriptRoot '..')).Path } else { '' }),
  [string]$AppData = $env:APPDATA,
  [string]$HomeDir = $HOME,
  [string]$UserSettingsPath,
  [string]$GlobalRulesPath,
  [string]$CodeWorkspacePath = $(if ($SrcRoot) { Join-Path (Split-Path -Parent $SrcRoot) 'demo1-src.code-workspace' } else { '' }),
  [string]$WorkspacesRoot,
  [string]$WorkspaceStorageRoot,
  [int]$ActiveWindowSeconds = 120,
  [switch]$Json
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if (-not $UserSettingsPath) { $UserSettingsPath = Join-Path $AppData 'devin\User\settings.json' }
if (-not $GlobalRulesPath) { $GlobalRulesPath = Join-Path $HomeDir '.codeium\windsurf\memories\global_rules.md' }
if (-not $WorkspacesRoot)  { $WorkspacesRoot  = Join-Path $AppData 'Devin\Workspaces' }
if (-not $WorkspaceStorageRoot) { $WorkspaceStorageRoot = Join-Path $AppData 'Devin\User\workspaceStorage' }

$GuardMarker = 'DEMO1-CWD-GUARD'
$srcRootNorm = [System.IO.Path]::GetFullPath($SrcRoot).TrimEnd('\')
$srcRootFwd  = $srcRootNorm -replace '\\','/'
$homeNorm    = [System.IO.Path]::GetFullPath($HomeDir).TrimEnd('\')

function Get-Sha12([string]$Path) {
  if (-not (Test-Path -LiteralPath $Path)) { return $null }
  return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.Substring(0,12).ToLower()
}

function Backup-File([string]$Path) {
  $stamp = Get-Date -Format 'yyyyMMdd-HHmm'
  $bak = "$Path.bak-$stamp"
  $n = 1
  while (Test-Path -LiteralPath $bak) { $bak = "$Path.bak-$stamp-$n"; $n++ }
  Copy-Item -LiteralPath $Path -Destination $bak -Force
  return [PSCustomObject]@{ path = $Path; bak = $bak; shaBefore = (Get-Sha12 $Path) }
}

function Write-TextNoBom([string]$Path, [string]$Text) {
  $enc = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($Path, $Text, $enc)
}

function Append-TextNoBom([string]$Path, [string]$Text) {
  $enc = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::AppendAllText($Path, $Text, $enc)
}

function Read-JsonFile([string]$Path) {
  try { return (Get-Content -LiteralPath $Path -Raw -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop), $null }
  catch { return $null, $_.Exception.Message }
}

function Resolve-Folder([string]$BaseDir, [string]$P) {
  if ([string]::IsNullOrWhiteSpace($P)) { return $null }
  try {
    if ([System.IO.Path]::IsPathRooted($P)) { return [System.IO.Path]::GetFullPath($P) }
    return [System.IO.Path]::GetFullPath((Join-Path $BaseDir $P))
  } catch { return $null }
}

function Test-IsHome([string]$Resolved) {
  if (-not $Resolved) { return $false }
  return ($Resolved.TrimEnd('\') -ieq $homeNorm)
}

function Get-GuardBlockText() {
  $nl = "`r`n"
  return ($nl + '<!-- DEMO1-CWD-GUARD -->' + $nl +
    '- demo-1/AbandonWare task or no root given and cwd is ' + $homeNorm + ': run' + $nl +
    "  ``Set-Location -LiteralPath '$srcRootNorm'`` first; keep that prefix on later commands." + $nl +
    '- Shell is Windows PowerShell 5.1: no `&&`/`||`/bash; use `;` and `if ($LASTEXITCODE -eq 0) { }`.' + $nl +
    "- Repo rules (auto-load once inside the repo): $srcRootNorm\.windsurf\rules" + $nl +
    '<!-- /DEMO1-CWD-GUARD -->' + $nl)
}

function Get-WorkspaceObject() {
  $o = [PSCustomObject]@{
    folders  = @([PSCustomObject]@{ name = 'src'; path = $srcRootFwd })
    settings = [PSCustomObject]@{
      'terminal.integrated.cwd' = $srcRootNorm
      'terminal.integrated.defaultProfile.windows' = 'Windows PowerShell'
    }
  }
  return $o
}

function Get-OpenWorkspaceIds() {
  # workspaceStorage\<hash>\workspace.json holds the window->workspace mapping;
  # a hash dir written inside the active window seconds means that workspace is
  # open right now - a stronger signal than workspace.json mtime.
  $open = @{}
  if (-not (Test-Path -LiteralPath $WorkspaceStorageRoot)) { return $open }
  $now = Get-Date
  foreach ($d in @(Get-ChildItem -LiteralPath $WorkspaceStorageRoot -Directory -ErrorAction SilentlyContinue)) {
    if (($now - $d.LastWriteTime).TotalSeconds -ge $ActiveWindowSeconds) { continue }
    $wj = Join-Path $d.FullName 'workspace.json'
    if (-not (Test-Path -LiteralPath $wj)) { continue }
    $doc, $err = Read-JsonFile $wj
    if ($err) { continue }
    $ref = $null
    if ($doc.PSObject.Properties['workspace']) { $ref = [string]$doc.workspace }
    elseif ($doc.PSObject.Properties['folder']) { $ref = [string]$doc.folder }
    if ($ref -match 'Workspaces/([^/\\]+)/workspace\.json') { $open[$Matches[1]] = $true }
  }
  return $open
}

function Get-C1Items() {
  $items = @()
  if (-not (Test-Path -LiteralPath $WorkspacesRoot)) {
    return @([PSCustomObject]@{ id = '(none)'; status = 'INFO'; detail = "Workspaces root absent: $WorkspacesRoot"; folders = @(); wsMtime = $null })
  }
  $openIds = Get-OpenWorkspaceIds
  $dirs = @(Get-ChildItem -LiteralPath $WorkspacesRoot -Directory -ErrorAction SilentlyContinue)
  $wsFiles = @()
  foreach ($d in $dirs) {
    $wj = Join-Path $d.FullName 'workspace.json'
    if (Test-Path -LiteralPath $wj) { $wsFiles += Get-Item -LiteralPath $wj }
  }
  $newest = $null
  foreach ($f in $wsFiles) { if (-not $newest -or $f.LastWriteTime -gt $newest.LastWriteTime) { $newest = $f } }
  $now = Get-Date
  foreach ($f in $wsFiles) {
    $id = $f.Directory.Name
    $ageSec = ($now - $f.LastWriteTime).TotalSeconds
    $stagedReason = $null
    if ($openIds.ContainsKey($id)) { $stagedReason = 'open-window' }
    elseif ($ageSec -lt $ActiveWindowSeconds) { $stagedReason = 'recent-mtime' }
    elseif ($newest -and $f.FullName -eq $newest.FullName) { $stagedReason = 'newest-workspace' }
    $doc, $err = Read-JsonFile $f.FullName
    if ($err) {
      $items += [PSCustomObject]@{ id = $id; status = 'CORRUPT'; detail = "workspace.json parse failed"; folders = @(); wsMtime = $f.LastWriteTime.ToString('s'); stagedReason = $stagedReason }
      continue
    }
    $resolved = @(); $bad = @()
    $foldersProp = $doc.PSObject.Properties['folders']
    if (-not $foldersProp -or -not $foldersProp.Value -or @($foldersProp.Value).Count -eq 0) {
      $bad += '(empty folders)'
    } else {
      foreach ($fo in @($foldersProp.Value)) {
        $p = $null
        if ($fo -and $fo.PSObject.Properties['path']) { $p = $fo.path }
        $r = Resolve-Folder $f.Directory.FullName $p
        $resolved += $r
        if (Test-IsHome $r) { $bad += $r }
        elseif (-not $r) { $bad += '(missing path)' }
      }
    }
    $status = 'OK'
    $detail = 'folders ok'
    if ($bad.Count -gt 0) {
      $status = 'BAD'; $detail = 'resolves to $HOME or empty: ' + ($bad -join ' | ')
      if ($stagedReason) { $status = 'STAGED'; $detail += " [deferred: $stagedReason]" }
    }
    $items += [PSCustomObject]@{ id = $id; status = $status; detail = $detail; folders = $resolved; wsMtime = $f.LastWriteTime.ToString('s'); stagedReason = $stagedReason; file = $f.FullName; badFolders = $bad }
  }
  return $items
}

function Invoke-CheckC2() {
  if (-not (Test-Path -LiteralPath $UserSettingsPath)) {
    return [PSCustomObject]@{ id = 'C2'; status = 'BAD'; detail = "settings.json missing: $UserSettingsPath"; items = @() }
  }
  $doc, $err = Read-JsonFile $UserSettingsPath
  if ($err) { return [PSCustomObject]@{ id = 'C2'; status = 'CORRUPT'; detail = "settings.json parse failed"; items = @() } }
  $prop = $doc.PSObject.Properties['terminal.integrated.cwd']
  $resolved = $null
  if ($prop -and -not [string]::IsNullOrWhiteSpace([string]$prop.Value)) {
    try { $resolved = [System.IO.Path]::GetFullPath([string]$prop.Value).TrimEnd('\') } catch { $resolved = $null }
  }
  if ($resolved -and ($resolved -ieq $srcRootNorm)) {
    return [PSCustomObject]@{ id = 'C2'; status = 'OK'; detail = 'terminal.integrated.cwd = src'; items = @() }
  }
  return [PSCustomObject]@{ id = 'C2'; status = 'BAD'; detail = 'terminal.integrated.cwd absent or not src'; items = @() }
}

function Invoke-CheckC3() {
  if (-not (Test-Path -LiteralPath $GlobalRulesPath)) {
    return [PSCustomObject]@{ id = 'C3'; status = 'BAD'; detail = "global_rules.md missing: $GlobalRulesPath"; items = @() }
  }
  $txt = [System.IO.File]::ReadAllText($GlobalRulesPath)
  if ($txt.Contains("<!-- $GuardMarker -->")) {
    return [PSCustomObject]@{ id = 'C3'; status = 'OK'; detail = 'DEMO1-CWD-GUARD block present'; items = @() }
  }
  return [PSCustomObject]@{ id = 'C3'; status = 'BAD'; detail = 'DEMO1-CWD-GUARD block absent'; items = @() }
}

function Invoke-CheckC4() {
  if (-not (Test-Path -LiteralPath $CodeWorkspacePath)) {
    return [PSCustomObject]@{ id = 'C4'; status = 'BAD'; detail = "code-workspace missing: $CodeWorkspacePath"; items = @() }
  }
  $doc, $err = Read-JsonFile $CodeWorkspacePath
  if ($err) { return [PSCustomObject]@{ id = 'C4'; status = 'CORRUPT'; detail = 'code-workspace parse failed'; items = @() } }
  $cwDir = Split-Path -Parent $CodeWorkspacePath
  $foldersProp = $doc.PSObject.Properties['folders']
  $ok = $false
  if ($foldersProp -and $foldersProp.Value -and @($foldersProp.Value).Count -eq 1) {
    $r = Resolve-Folder $cwDir ([string]@($foldersProp.Value)[0].path)
    if ($r -and ($r.TrimEnd('\') -ieq $srcRootNorm)) { $ok = $true }
  }
  if ($ok) { return [PSCustomObject]@{ id = 'C4'; status = 'OK'; detail = 'workspace folders = src'; items = @() } }
  return [PSCustomObject]@{ id = 'C4'; status = 'BAD'; detail = 'folders do not resolve to exactly src'; items = @() }
}

function Invoke-Checks() {
  $c1items = Get-C1Items
  $bad1 = @($c1items | Where-Object { $_.status -in @('BAD','STAGED','CORRUPT') })
  $c1 = [PSCustomObject]@{ id = 'C1'; status = $(if ($bad1.Count -eq 0) { 'OK' } else { 'BAD' }); detail = "$($bad1.Count) workspace(s) need attention"; items = $c1items }
  $checks = @($c1, (Invoke-CheckC2), (Invoke-CheckC3), (Invoke-CheckC4),
    [PSCustomObject]@{ id = 'C5'; status = 'INFO'; detail = "cwd=$($PWD.Path) PS=$($PSVersionTable.PSVersion)"; items = @() })
  return $checks
}

function Save-JsonPreserving([string]$Path, $Obj) {
  Write-TextNoBom $Path ($Obj | ConvertTo-Json -Depth 32)
  $null, $err = Read-JsonFile $Path
  if ($err) { throw "post-write JSON parse failed for $Path : $err" }
}

$backups = @()
$fixed = @()
$staged = @()

try {
  if ($Restore) {
    if ($Restore -notmatch '\.bak-\d{8}-\d{4}(-\d+)?$') { throw "not a doctor backup name: $Restore" }
    if (-not (Test-Path -LiteralPath $Restore)) { throw "backup not found: $Restore" }
    $orig = $Restore -replace '\.bak-\d{8}-\d{4}(-\d+)?$',''
    $shaBak = Get-Sha12 $Restore; $shaBefore = Get-Sha12 $orig
    Copy-Item -LiteralPath $Restore -Destination $orig -Force
    $shaAfter = Get-Sha12 $orig
    $result = [PSCustomObject]@{ schemaVersion = 'devin-cwd-doctor.v1'; action = 'restore'; restored = $orig; from = $Restore; shaBackup = $shaBak; shaBefore = $shaBefore; shaAfter = $shaAfter; exitCode = 0 }
    if ($Json) { $result | ConvertTo-Json -Depth 8 } else { "RESTORED $orig  sha=$shaBefore -> $shaAfter (backup sha=$shaBak)" }
    exit 0
  }

  $action = if ($Fix) { 'fix' } else { 'check' }
  $checks = Invoke-Checks

  if ($Fix) {
    $c1 = $checks[0]
    foreach ($it in $c1.items) {
      if ($it.status -ne 'BAD' -and $it.status -ne 'STAGED') { continue }
      if ($it.status -eq 'CORRUPT' -or -not $it.file) { continue }
      if ($it.stagedReason -and -not $IncludeActive) { $staged += $it.id; continue }
      $bak = Backup-File $it.file
      $doc, $err = Read-JsonFile $it.file
      if ($err) { continue }
      $newFolders = @([PSCustomObject]@{ name = 'src'; path = $srcRootFwd })
      $doc | Add-Member -MemberType NoteProperty -Name 'folders' -Value $newFolders -Force
      Save-JsonPreserving $it.file $doc
      $bak | Add-Member -MemberType NoteProperty -Name 'shaAfter' -Value (Get-Sha12 $it.file)
      $backups += $bak; $fixed += "workspace/$($it.id)"
    }
    if ((Invoke-CheckC2).status -eq 'BAD') {
      if (Test-Path -LiteralPath $UserSettingsPath) {
        $bak = Backup-File $UserSettingsPath
        $doc, $err = Read-JsonFile $UserSettingsPath
        if (-not $err) {
          $doc | Add-Member -MemberType NoteProperty -Name 'terminal.integrated.cwd' -Value $srcRootNorm -Force
          Save-JsonPreserving $UserSettingsPath $doc
          $bak | Add-Member -MemberType NoteProperty -Name 'shaAfter' -Value (Get-Sha12 $UserSettingsPath)
          $backups += $bak; $fixed += 'settings.json'
        }
      } else {
        $dir = Split-Path -Parent $UserSettingsPath
        New-Item -ItemType Directory -Force $dir | Out-Null
        Save-JsonPreserving $UserSettingsPath ([PSCustomObject]@{ 'terminal.integrated.cwd' = $srcRootNorm })
        $fixed += 'settings.json(created)'
      }
    }
    if ((Invoke-CheckC3).status -eq 'BAD') {
      $dir = Split-Path -Parent $GlobalRulesPath
      New-Item -ItemType Directory -Force $dir | Out-Null
      if (Test-Path -LiteralPath $GlobalRulesPath) {
        $bak = Backup-File $GlobalRulesPath
        Append-TextNoBom $GlobalRulesPath (Get-GuardBlockText)
        $bak | Add-Member -MemberType NoteProperty -Name 'shaAfter' -Value (Get-Sha12 $GlobalRulesPath)
        $backups += $bak; $fixed += 'global_rules.md'
      } else {
        Write-TextNoBom $GlobalRulesPath (Get-GuardBlockText)
        $fixed += 'global_rules.md(created)'
      }
    }
    if ((Invoke-CheckC4).status -eq 'BAD') {
      $dir = Split-Path -Parent $CodeWorkspacePath
      New-Item -ItemType Directory -Force $dir | Out-Null
      if (Test-Path -LiteralPath $CodeWorkspacePath) {
        $bak = Backup-File $CodeWorkspacePath
        Save-JsonPreserving $CodeWorkspacePath (Get-WorkspaceObject)
        $bak | Add-Member -MemberType NoteProperty -Name 'shaAfter' -Value (Get-Sha12 $CodeWorkspacePath)
        $backups += $bak; $fixed += 'code-workspace'
      } else {
        Save-JsonPreserving $CodeWorkspacePath (Get-WorkspaceObject)
        $fixed += 'code-workspace(created)'
      }
    }
    $checks = Invoke-Checks
    # Workspaces deferred this run keep the STAGED marker in the report; mtime
    # changes on the just-fixed ones can shift the newest-workspace heuristic.
    if ($staged.Count -gt 0) {
      foreach ($it in $checks[0].items) {
        if ($staged -contains $it.id) {
          $it.status = 'STAGED'
          if ($it.detail -notmatch 'deferred') { $it.detail += ' [deferred: active-window]' }
        }
      }
    }
  }

  $anyBad = @($checks | Where-Object { $_.status -in @('BAD','CORRUPT') }).Count -gt 0
  $anyBadC1 = @($checks[0].items | Where-Object { $_.status -in @('BAD','STAGED','CORRUPT') }).Count -gt 0
  $exitCode = if ($anyBad -or $anyBadC1) { 2 } else { 0 }

  $result = [PSCustomObject]@{
    schemaVersion = 'devin-cwd-doctor.v1'; action = $action
    srcRoot = $srcRootNorm; homeDir = $homeNorm; appData = $AppData
    psVersion = $PSVersionTable.PSVersion.ToString(); cwd = $PWD.Path
    checks = $checks; fixed = $fixed; staged = $staged; backups = $backups
    includeActive = [bool]$IncludeActive; exitCode = $exitCode
  }

  if ($Json) {
    $result | ConvertTo-Json -Depth 8
  } else {
    "== devin-cwd-doctor $action =="
    foreach ($c in $checks) {
      "{0}  {1,-7} {2}" -f $c.id, $c.status, $c.detail
      if ($c.items) { foreach ($i in $c.items) { "      - {0}  {1,-7} {2}  mtime={3}" -f $i.id, $i.status, $i.detail, $i.wsMtime } }
    }
    if ($fixed.Count)  { "fixed : $($fixed -join ', ')" }
    if ($staged.Count) { "staged: $($staged -join ', ')  (open window? re-run with -Fix -IncludeActive after closing it)" }
    foreach ($b in $backups) { "backup: $($b.bak)  sha $($b.shaBefore) -> $($b.shaAfter)" }
    "exit=$exitCode"
  }
  exit $exitCode
} catch {
  if ($Json) { [PSCustomObject]@{ schemaVersion = 'devin-cwd-doctor.v1'; error = $_.Exception.Message; type = $_.Exception.GetType().FullName; trace = $_.ScriptStackTrace; target = ([string]$_.TargetObject); fullyQualified = $_.FullyQualifiedErrorId; exitCode = 1 } | ConvertTo-Json }
  else { Write-Error $_ }
  exit 1
}

