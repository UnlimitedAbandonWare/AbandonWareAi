<#
.SYNOPSIS
  Back up Sticky Notes safely, register user startup, and ensure the app runs.
.DESCRIPTION
  Run with Windows PowerShell 5.1 and an existing Python 3 installation.
  Backups stay in the current user's Sticky Notes LocalState, never in Git.
  The live SQLite DB is read-only; closed individual notes are not forced open.
  -CheckOnly inspects the package/process/shortcut without changing anything.
#>
[CmdletBinding()]
param([switch]$CheckOnly)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$appTarget = 'shell:AppsFolder\Microsoft.MicrosoftStickyNotes_8wekyb3d8bbwe!App'
$dbPath = Join-Path $env:LOCALAPPDATA 'Packages\Microsoft.MicrosoftStickyNotes_8wekyb3d8bbwe\LocalState\plum.sqlite'
$shortcutPath = Join-Path ([Environment]::GetFolderPath('Startup')) 'StickyNotes.lnk'
$explorerPath = Join-Path $env:WINDIR 'explorer.exe'

try {
    $package = Get-AppxPackage -Name Microsoft.MicrosoftStickyNotes
    if (-not $package) { throw 'sticky-notes-package-missing' }
    if (-not (Test-Path -LiteralPath $dbPath -PathType Leaf)) { throw 'sticky-notes-db-missing' }

    $shell = New-Object -ComObject WScript.Shell
    $shortcutExists = Test-Path -LiteralPath $shortcutPath -PathType Leaf
    if ($shortcutExists) {
        $shortcut = $shell.CreateShortcut($shortcutPath)
        if (($shortcut.TargetPath -ine $explorerPath) -or ($shortcut.Arguments -cne $appTarget)) {
            throw 'existing-sticky-notes-shortcut-conflict'
        }
    }
    if ($CheckOnly) {
        [pscustomobject]@{
            mode = 'check'; packageVersion = [string]$package.Version
            databaseExists = $true; shortcutValid = $shortcutExists
            processRunning = [bool](Get-Process -Name Microsoft.Notes -ErrorAction SilentlyContinue)
        } | ConvertTo-Json -Compress
        exit 0
    }

    $python = Get-Command python.exe -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $python) { throw 'python3-required-for-consistent-sqlite-backup' }
    $backupCode = @'
import hashlib, json, os, sqlite3, sys, time, uuid
from datetime import datetime, timezone
from pathlib import Path

source_path = Path(sys.argv[1]).resolve(strict=True)
expected = (Path(os.environ['LOCALAPPDATA']) /
    'Packages/Microsoft.MicrosoftStickyNotes_8wekyb3d8bbwe/LocalState/plum.sqlite').resolve(strict=True)
if source_path != expected:
    raise RuntimeError('unexpected-sticky-notes-db-path')
destination = source_path.with_name('plum.sqlite.bak')
if destination.exists():
    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    destination = source_path.with_name('plum.sqlite.' + stamp + '.bak')
temporary = destination.with_name(destination.name + '.' + uuid.uuid4().hex + '.partial')
deadline = time.monotonic() + 15
def progress(status, remaining, total):
    if time.monotonic() > deadline:
        raise TimeoutError('sticky-notes-backup-timeout')

try:
    with temporary.open('xb'):
        pass
    source = sqlite3.connect(source_path.as_uri() + '?mode=ro', uri=True, timeout=3)
    try:
        target = sqlite3.connect(str(temporary), timeout=3)
        try:
            source.backup(target, pages=128, progress=progress, sleep=0.05)
            if target.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
                raise RuntimeError('sticky-notes-backup-integrity-failed')
            notes = []
            for label, needle in [('yellow', '401, 403, 429'), ('purple', '@objective-executor')]:
                rows = target.execute('SELECT IsOpen, Theme, DeletedAt FROM Note WHERE instr(Text, ?) > 0', (needle,)).fetchall()
                notes.append({'target': label, 'matches': len(rows),
                    'openCount': sum(bool(row[0]) and row[2] is None for row in rows)})
        finally:
            target.close()
    finally:
        source.close()
    # On Windows rename refuses to replace an existing destination.
    os.rename(str(temporary), str(destination))
    data = destination.read_bytes()
    print(json.dumps({'backupPath': str(destination), 'backupBytes': len(data),
        'backupSha12': hashlib.sha256(data).hexdigest()[:12], 'quickCheck': 'ok', 'notes': notes}))
except Exception as error:
    print('backup_failed=' + type(error).__name__, file=sys.stderr)
    sys.exit(2)
finally:
    if temporary.exists():
        temporary.unlink()
'@
    $backupOutput = $backupCode | & $python.Source -B - $dbPath
    if ($LASTEXITCODE -ne 0) { throw 'sticky-notes-backup-failed' }
    Write-Output $backupOutput

    if (-not $shortcutExists) {
        $temporaryShortcut = Join-Path ([IO.Path]::GetDirectoryName($shortcutPath)) ([guid]::NewGuid().ToString('N') + '.lnk')
        try {
            $shortcut = $shell.CreateShortcut($temporaryShortcut)
            $shortcut.TargetPath = $explorerPath
            $shortcut.Arguments = $appTarget
            $shortcut.Description = 'Open Sticky Notes at Windows sign-in'
            $shortcut.Save()
            # Move refuses an existing destination, including a concurrent writer.
            [IO.File]::Move($temporaryShortcut, $shortcutPath)
        } finally {
            if (Test-Path -LiteralPath $temporaryShortcut) {
                Remove-Item -LiteralPath $temporaryShortcut
            }
        }
    }

    $process = Get-Process -Name Microsoft.Notes -ErrorAction SilentlyContinue
    $launched = -not [bool]$process
    if ($launched) {
        Start-Process -FilePath $explorerPath -ArgumentList $appTarget -WindowStyle Hidden
        $until = (Get-Date).AddSeconds(15)
        do {
            Start-Sleep -Milliseconds 500
            $process = Get-Process -Name Microsoft.Notes -ErrorAction SilentlyContinue
        } while ((-not $process) -and ((Get-Date) -lt $until))
    }
    if (-not $process) { throw 'sticky-notes-process-not-observed' }
    [pscustomobject]@{
        shortcutPath = $shortcutPath; shortcutExists = (Test-Path -LiteralPath $shortcutPath)
        processRunning = $true; launched = $launched; processIds = @($process.Id)
        individualNoteRestore = 'app-managed-previously-open-notes'
    } | ConvertTo-Json -Compress
    exit 0
} catch {
    Write-Error ('ensure_sticky_notes failed: ' + $_.Exception.Message) -ErrorAction Continue
    exit 1
}
