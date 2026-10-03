#requires -Version 5.1
<#
.SYNOPSIS
    Thin wrapper for scripts/agent_machine_context.py (the SSOT).

.DESCRIPTION
    Emits exactly one JSON document on stdout: project root, install paths,
    tool locations/versions, env NAMES (secret values are never emitted),
    DB lane-A status via db_agent.py, GPU summary, git policy, skills index.

    Exit codes: 0 report emitted | 2 usage/probe failure | 4 python missing.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent_machine_context.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\agent_machine_context.ps1 -Section tools -Pretty
#>
[CmdletBinding()]
param(
    [ValidateSet('all', 'paths', 'env', 'tools', 'db', 'gpu', 'git', 'skills')]
    [string]$Section = 'all',
    [switch]$Pretty,
    [string]$Root = '.',
    [string]$WriteReport
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:RagRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

$pyExe = $null; $pyPrefix = @()
foreach ($cand in @(@('python.exe'), @('python3.exe'), @('py.exe', '-3'))) {
    $found = Get-Command $cand[0] -ErrorAction SilentlyContinue
    if ($found) { $pyExe = $found.Source; $pyPrefix = @($cand | Select-Object -Skip 1); break }
}
$agentPy = Join-Path $script:RagRoot 'scripts\agent_machine_context.py'
if (-not $pyExe -or -not (Test-Path -LiteralPath $agentPy -PathType Leaf)) {
    '{"schemaVersion":"awx.agent-machine-context.v1","status":"error","reason":"python-or-script-missing"}'
    exit 4
}
$argvList = @('-B', $agentPy, '--root', $script:RagRoot, '--section', $Section)
if ($Pretty) { $argvList += '--pretty' }
if (-not [string]::IsNullOrWhiteSpace($WriteReport)) { $argvList += @('--write-report', $WriteReport) }
& $pyExe @($pyPrefix) $argvList
exit $LASTEXITCODE
