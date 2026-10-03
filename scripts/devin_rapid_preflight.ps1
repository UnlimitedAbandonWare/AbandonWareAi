# Devin rapid-fire preflight. Advisory only: exit 0 when all three checks ran.
# A check that cannot run is NOT_RUN and makes this script exit 1.
#Requires -Version 5.1
$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $scriptDir
Set-Location -LiteralPath $root
$sw = [Diagnostics.Stopwatch]::StartNew()

function Start-CapturedPy {
    param([string[]]$ArgList)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = (Get-Command python -ErrorAction Stop).Source
    $quoted = foreach ($a in $ArgList) {
        if ($a -match '[\s"]') { '"' + ($a -replace '"', '\"') + '"' } else { $a }
    }
    $psi.Arguments = ($quoted -join " ")
    $psi.WorkingDirectory = $root
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.StandardOutputEncoding = [Text.UTF8Encoding]::new($false)
    $psi.StandardErrorEncoding = [Text.UTF8Encoding]::new($false)
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()
    return [pscustomobject]@{
        Proc = $proc
        Out = $proc.StandardOutput.ReadToEndAsync()
        Err = $proc.StandardError.ReadToEndAsync()
    }
}

function Wait-Captured {
    param($Job, [string]$Name)
    if (-not $Job.Proc.WaitForExit(8000)) {
        try { $Job.Proc.Kill() } catch { }
        return [pscustomobject]@{ Name = $Name; Ok = $false; Reason = "timeout"; Exit = 1; Text = "" }
    }
    $text = ""
    $err = ""
    try { $text = [string]$Job.Out.Result } catch { $text = "" }
    try { $err = [string]$Job.Err.Result } catch { $err = "" }
    $ok = ($Job.Proc.ExitCode -eq 0) -and -not [string]::IsNullOrWhiteSpace($text)
    return [pscustomobject]@{
        Name = $Name
        Ok = [bool]$ok
        Reason = $(if ($ok) { "" } elseif ($err) { $err.Trim() } else { "empty-or-nonzero" })
        Exit = [int]$Job.Proc.ExitCode
        Text = $text
    }
}

$jobs = @(
    (Start-CapturedPy -ArgList @("-B", "scripts\devin_session_guard.py", "status", "--json")),
    (Start-CapturedPy -ArgList @("-B", "scripts\work_journal.py", "list", "--active")),
    (Start-CapturedPy -ArgList @("-B", "scripts\agent_scope_lease.py", "--root", ".", "who"))
)
$statusRun = Wait-Captured -Job $jobs[0] -Name "status"
$journalRun = Wait-Captured -Job $jobs[1] -Name "journal"
$leaseRun = Wait-Captured -Job $jobs[2] -Name "lease"

$failed = $false
$warnings = New-Object System.Collections.Generic.List[string]

if (-not $statusRun.Ok) {
    $failed = $true
    Write-Output ("NOT_RUN status: exit={0} reason={1}" -f $statusRun.Exit, $statusRun.Reason)
} else {
    $status = $statusRun.Text | ConvertFrom-Json
    $locks = [int]$status.lockCount
    $procs = [int]$status.processCount
    $orphans = [int]$status.orphanCount
    $dead = [int]$status.deadLockCount
    Write-Output ("locks={0} deadLocks={1} processes={2} orphans={3} sessionsDbBytes={4}" -f $locks, $dead, $procs, $orphans, $status.sessionsDbBytes)
    if ($locks -gt 20 -or $dead -gt 0) {
        $warnings.Add("locks=$locks dead=$dead -> python -B scripts/devin_session_guard.py clean-locks --force")
    }
    if ($procs -gt 12 -or $orphans -gt 0) {
        $warnings.Add("processes=$procs orphans=$orphans -> python -B scripts/devin_session_guard.py reap-zombies")
    }
}

if (-not $journalRun.Ok) {
    $failed = $true
    Write-Output ("NOT_RUN journal: exit={0} reason={1}" -f $journalRun.Exit, $journalRun.Reason)
} else {
    $journal = $journalRun.Text | ConvertFrom-Json
    $devinJournals = @($journal.tasks | Where-Object { [string]$_.agent -match "devin" })
    Write-Output ("devinJournals={0}" -f $devinJournals.Count)
    if ($devinJournals.Count -gt 0) {
        $ids = ($devinJournals | ForEach-Object { $_.taskId }) -join ","
        $warnings.Add("devin journals in_progress=$($devinJournals.Count) ids=$ids")
    }
}

if (-not $leaseRun.Ok) {
    $failed = $true
    Write-Output ("NOT_RUN lease: exit={0} reason={1}" -f $leaseRun.Exit, $leaseRun.Reason)
} else {
    $who = $leaseRun.Text | ConvertFrom-Json
    $devinLeases = @($who.leases | Where-Object {
        [string]$_.status -eq "active" -and [string]$_.topic -match "devin"
    })
    Write-Output ("devinLeases={0}" -f $devinLeases.Count)
    if ($devinLeases.Count -gt 0) {
        $topics = ($devinLeases | ForEach-Object { $_.topic }) -join ","
        $warnings.Add("devin leases active=$($devinLeases.Count) topics=$topics")
    }
}

$sw.Stop()
Write-Output ("elapsedMs={0}" -f [int]$sw.ElapsedMilliseconds)
if ($failed) {
    Write-Host "[DEVIN-BLOCKED]" -ForegroundColor Red
    exit 1
}
if ($warnings.Count -eq 0) {
    Write-Host "[DEVIN-READY]" -ForegroundColor Green
    exit 0
}
Write-Host "[DEVIN-ATTENTION]" -ForegroundColor Yellow
foreach ($line in $warnings) { Write-Output $line }
exit 0
