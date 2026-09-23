# Pre/PostToolUse wrapper for scripts/agent_work_guard.py.
# Stdin: hook event JSON. Exit 2 = block, 0 = allow, 1 = fail-open.
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$root = ''
function Write-WorkGuardTrace([string]$Root, [string]$Phase, $ExitCode, $ElapsedMs) {
    try {
        $dir = Join-Path $Root 'var\agent-work-guard'
        if (-not (Test-Path -LiteralPath $dir)) {
            New-Item -ItemType Directory -Path $dir -Force | Out-Null
        }
        $line = '{"at":"' + ([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ')) + '","phase":"' + $Phase + '","wrapper":"ps1"'
        if ($null -ne $ExitCode) { $line += ',"exit":' + $ExitCode }
        if ($null -ne $ElapsedMs) { $line += ',"elapsedMs":' + $ElapsedMs }
        $line += '}'
        Add-Content -LiteralPath (Join-Path $dir 'hook-trace.jsonl') -Value $line -Encoding utf8
    } catch { }
}
try {
    $root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\', '/')
    if (-not [string]::IsNullOrWhiteSpace($env:DEVIN_PROJECT_DIR) -and (Test-Path -LiteralPath $env:DEVIN_PROJECT_DIR)) {
        $root = [IO.Path]::GetFullPath($env:DEVIN_PROJECT_DIR).TrimEnd('\', '/')
    }
    Write-WorkGuardTrace $root 'enter' $null $null
    $started = [Diagnostics.Stopwatch]::StartNew()
    $py = Join-Path $root 'scripts\agent_work_guard.py'
    if (-not (Test-Path -LiteralPath $py -PathType Leaf)) { Write-WorkGuardTrace $root 'exit' 0 $started.ElapsedMilliseconds; exit 0 }
    $pinfo = [Diagnostics.ProcessStartInfo]::new()
    $pinfo.FileName = 'python'
    $pinfo.Arguments = '-B "' + $py + '" hook --root "' + $root + '"'
    $pinfo.UseShellExecute = $false
    $pinfo.RedirectStandardInput = $true
    $pinfo.RedirectStandardOutput = $true
    $pinfo.RedirectStandardError = $true
    $pinfo.CreateNoWindow = $true
    $p = [Diagnostics.Process]::new()
    $p.StartInfo = $pinfo
    [void]$p.Start()
    $stdin = [Console]::In.ReadToEnd()
    if ($stdin.Length -gt 65536) { $stdin = $stdin.Substring(0, 65536) }
    $p.StandardInput.Write($stdin)
    $p.StandardInput.Close()
    if (-not $p.WaitForExit(12000)) { $p.Kill(); exit 1 }
    $out = $p.StandardOutput.ReadToEnd()
    $err = $p.StandardError.ReadToEnd()
    if ($err) { [Console]::Error.Write($err) }
    if ($out) { [Console]::Out.Write($out) }
    Write-WorkGuardTrace $root 'exit' $p.ExitCode $started.ElapsedMilliseconds
    exit $p.ExitCode
} catch {
    [Console]::Error.Write("work-guard-error: $($_.Exception.Message)")
    if ($root) { Write-WorkGuardTrace $root 'exit' 1 $null }
    exit 1
}
