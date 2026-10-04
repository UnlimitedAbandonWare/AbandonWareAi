[CmdletBinding()]
param(
    [string]$TaskId = '',
    [string]$Agent = 'codex',
    [switch]$SelfTest
)
$entryClock = [Diagnostics.Stopwatch]::StartNew()
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = New-Object Text.UTF8Encoding($false) } catch { exit 0 }

function Quote-ContextLiteral {
    param([string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-ContextChild {
    param($Info, $Clock)
    $process = $null
    try {
        $remaining = 1500 - [int]$Clock.ElapsedMilliseconds
        if ($remaining -le 0) { return '' }
        $process = New-Object Diagnostics.Process
        $process.StartInfo = $Info
        [void]$process.Start()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $remaining = 1500 - [int]$Clock.ElapsedMilliseconds
        if ($remaining -le 0 -or -not $process.WaitForExit([Math]::Max(1, $remaining))) { return '' }
        $remaining = 1500 - [int]$Clock.ElapsedMilliseconds
        if ($remaining -le 0 -or -not [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]@($stdout, $stderr), $remaining)) { return '' }
        if ($process.ExitCode -ne 0 -or -not $stdout.IsCompleted -or $Clock.ElapsedMilliseconds -ge 1700) { return '' }
        if (-not $stderr.IsCompleted -or $stderr.GetAwaiter().GetResult()) { return '' }
        return $stdout.GetAwaiter().GetResult().TrimEnd("`r", "`n")
    } catch { if ($SelfTest) { throw }; return '' }
    finally {
        if ($process) {
            try { if (-not $process.HasExited) { $process.Kill() } } catch { }
            $process.Dispose()
        }
    }
}

function Read-ContextOutput {
    param([string]$ProjectRoot, [string]$JournalRoot, [string]$RequestedTask, [int]$DelayMs = 0, $Clock)
    try {
        if ($RequestedTask -and $RequestedTask -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$') { return '' }
        $python = Join-Path $ProjectRoot 'scripts/task_context.py'
        $info = New-Object Diagnostics.ProcessStartInfo
        $info.UseShellExecute = $false
        $info.CreateNoWindow = $true
        $info.RedirectStandardOutput = $true
        $info.RedirectStandardError = $true
        $info.StandardOutputEncoding = [Text.Encoding]::UTF8
        $info.StandardErrorEncoding = [Text.Encoding]::UTF8
        $info.EnvironmentVariables.Remove('PSModulePath')
        $header = ''
        $hasPythonContext = Test-Path -LiteralPath $python -PathType Leaf
        if ($hasPythonContext -and $RequestedTask) {
            $selected = $RequestedTask
            if (-not $selected -or $selected -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$') { return '' }
            $info.FileName = 'python.exe'
            $info.Arguments = '"' + $python + '" show --task ' + $selected
            $info.WorkingDirectory = $ProjectRoot
            $info.EnvironmentVariables['PYTHONUTF8'] = '1'
            $info.EnvironmentVariables['PYTHONDONTWRITEBYTECODE'] = '1'
            $header = '[이전 작업 문맥] ' + $selected + ' (출처: scripts/task_context.py)'
        } else {
            # A separate, owned process bounds slow enumeration/JSON parsing.
            $worker = @'
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = New-Object Text.UTF8Encoding($false)
if ($delay -gt 0) { Start-Sleep -Milliseconds $delay }
if (-not (Test-Path -LiteralPath $journalRoot -PathType Container)) { exit 0 }
$file = $null
if ($requested) {
    $candidate = Join-Path (Join-Path $journalRoot $requested) 'journal.json'
    if (Test-Path -LiteralPath $candidate -PathType Leaf) { $file = Get-Item -LiteralPath $candidate }
} else {
    $file = Get-ChildItem -LiteralPath $journalRoot -Directory | ForEach-Object {
        $candidate = Join-Path $_.FullName 'journal.json'
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { Get-Item -LiteralPath $candidate }
    } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
}
if (-not $file -or $file.Length -gt 65536) { exit 0 }
$id = $file.Directory.Name
if ($id -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,119}$') { exit 0 }
if ($selectOnly) { [Console]::Out.WriteLine($id); exit 0 }
$journal = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json
$relative = 'data/agent-handoff/codex-autonomy/' + $id + '/journal.json'
$lines = @('[이전 작업 문맥] ' + $id + ' (출처: ' + $relative + ')')
foreach ($event in @($journal.events | Select-Object -Last 3)) {
    $at = [DateTimeOffset]::Parse([string]$event.at).ToOffset([TimeSpan]::FromHours(9)).ToString('yyyy-MM-dd HH:mm:ss')
    $kind = [string]$event.kind
    if ($kind -notmatch '^[A-Za-z0-9_.-]{1,32}$') { $kind = 'unknown' }
    $count = 0
    if ($null -ne $event.refs) { $count = @($event.refs).Count }
    $lines += $at + ' KST | kind=' + $kind + ' | refs=' + $count
}
$lines | ForEach-Object { [Console]::Out.WriteLine($_) }
'@
            $prefix = '$journalRoot=' + (Quote-ContextLiteral $JournalRoot) + ';$requested=' + (Quote-ContextLiteral $RequestedTask) + ';$delay=' + $DelayMs + ';$selectOnly=$' + $hasPythonContext.ToString().ToLowerInvariant() + ';'
            $info.FileName = Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
            $info.Arguments = '-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($prefix + $worker))
        }
        $result = Invoke-ContextChild $info $Clock
        if (-not $result) { return '' }
        if ($hasPythonContext -and -not $RequestedTask) {
            # Selection and Python share the same clock, with no parent enumeration.
            return Read-ContextOutput $ProjectRoot $JournalRoot $result 0 $Clock
        }
        if ($header) { $result = $header + "`n" + (($result -split '\r?\n' | Select-Object -First 20) -join "`n") }
        return $result
    } catch { if ($SelfTest) { throw }; return '' }
}

if ($SelfTest) {
    $temp = Join-Path ([IO.Path]::GetTempPath()) ('awx-preamble-' + [guid]::NewGuid().ToString('N'))
    try {
        $journalRoot = Join-Path $temp 'journals'
        $fixture = Join-Path $journalRoot 'fixture-task'
        [void][IO.Directory]::CreateDirectory($fixture)
        $needle = 'PRIVATE-' + [guid]::NewGuid().ToString('N')
        $events = @()
        for ($i = 1; $i -le 4; $i++) { $events += @{ at = '2026-10-04T01:00:00Z'; kind = 'verify'; refs = @('opaque'); text = $needle } }
        [IO.File]::WriteAllText((Join-Path $fixture 'journal.json'), (@{ events = $events } | ConvertTo-Json -Depth 5), (New-Object Text.UTF8Encoding($false)))
        $clock = [Diagnostics.Stopwatch]::StartNew()
        $output = Read-ContextOutput $temp $journalRoot 'fixture-task' 0 $clock
        if (-not $output.StartsWith('[이전 작업 문맥] fixture-task (출처: ') -or ($output -split '\r?\n').Count -ne 4 -or $output.Contains($needle) -or $output -match 'text' -or $output -notmatch '2026-10-04 10:00:00 KST' -or $clock.ElapsedMilliseconds -gt 2000) { throw 'metadata fixture failed' }
        $clock.Restart()
        if ((Read-ContextOutput $temp $journalRoot 'fixture-task' 3000 $clock) -or $clock.ElapsedMilliseconds -gt 2000) { throw 'timeout fixture failed' }
        $timeoutMs = $clock.ElapsedMilliseconds
        [IO.File]::WriteAllText((Join-Path $fixture 'journal.json'), '{invalid')
        $clock.Restart()
        if ((Read-ContextOutput $temp $journalRoot 'fixture-task' 0 $clock)) { throw 'invalid JSON fixture failed' }
        $clock.Restart()
        if ((Read-ContextOutput $temp $journalRoot '../outside' 0 $clock)) { throw 'task path fixture failed' }
        [void][IO.Directory]::CreateDirectory((Join-Path $temp 'scripts'))
        $pythonFixture = Join-Path $temp 'scripts/task_context.py'
        [IO.File]::WriteAllText($pythonFixture, "for i in range(25): print('context-line-%d' % i)", (New-Object Text.UTF8Encoding($false)))
        $clock.Restart()
        $output = Read-ContextOutput $temp $journalRoot 'fixture-task' 0 $clock
        if (($output -split '\r?\n').Count -ne 21 -or $output -notmatch 'context-line-19' -or $output -match 'context-line-20' -or $clock.ElapsedMilliseconds -gt 2000) { throw 'Python first 20 lines fixture failed' }
        $clock.Restart()
        $output = Read-ContextOutput $temp $journalRoot '' 0 $clock
        if (-not $output.StartsWith('[이전 작업 문맥] fixture-task ') -or $clock.ElapsedMilliseconds -gt 2000) { throw 'Python bounded selection fixture failed' }
        $clock.Restart()
        if ((Read-ContextOutput $temp $journalRoot '' 3000 $clock) -or $clock.ElapsedMilliseconds -gt 2000) { throw 'selection timeout fixture failed' }
        [IO.File]::WriteAllText($pythonFixture, 'import sys; print("partial"); sys.stderr.write("error")')
        $clock.Restart()
        if ((Read-ContextOutput $temp $journalRoot 'fixture-task' 0 $clock)) { throw 'stderr fixture failed' }
        [IO.File]::WriteAllText($pythonFixture, 'import time; time.sleep(3); print("late")')
        $clock.Restart()
        if ((Read-ContextOutput $temp $journalRoot 'fixture-task' 0 $clock) -or $clock.ElapsedMilliseconds -gt 2000) { throw 'Python timeout fixture failed' }
        [ordered]@{ selfTest = 'PASS'; cases = 9; metadataTextLeaks = 0; timeoutMs = $timeoutMs; pythonTimeoutMs = $clock.ElapsedMilliseconds } | ConvertTo-Json -Compress
        exit 0
    } catch {
        [Console]::Error.WriteLine('preamble-self-test-failed: ' + $_.Exception.Message)
        exit 1
    } finally {
        if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
    }
}

try {
    $root = Split-Path -Parent $PSScriptRoot
    $journals = Join-Path $root 'data/agent-handoff/codex-autonomy'
    $output = Read-ContextOutput $root $journals $TaskId 0 $entryClock
    if ($output -and $entryClock.ElapsedMilliseconds -lt 1700) { [Console]::Out.WriteLine($output) }
} catch { }
exit 0
