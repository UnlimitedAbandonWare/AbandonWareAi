#requires -Version 5.1
<#
.SYNOPSIS
    Thin wrapper for scripts/codex_parallel_preflight.py (SSOT is Python).

.DESCRIPTION
    Read-only parallel-lane preflight: same-goal duplicates, role verdict,
    live writers, unclaimed edits, stale claims, quota, next commands.
    Exit 0 advisory; -Strict exits 7 on DUPLICATE_GOAL_LIVE / LANE_VIOLATION.

.EXAMPLE
    powershell -NoProfile -File .\scripts\codex_parallel_preflight.ps1 `
        -GoalKey 'DEMO1-X' -Lane 'plan-ab12cd34/A' -Scope 'a.java,b.java' -Agent codex-a
#>
[CmdletBinding()]
param(
    [string]$Root = '.',
    [string]$GoalKey = '',
    [string]$Lane = '',
    [string]$Scope = '',
    [string]$Agent = '',
    [string]$Task = '',
    [int]$LiveMinutes = 20,
    [int]$EditMinutes = 30,
    [int]$StaleHours = 24,
    [switch]$Strict
)
$py = Join-Path $PSScriptRoot 'codex_parallel_preflight.py'
$argv = @('-B', $py, '--root', $Root, '--json',
          '--live-minutes', $LiveMinutes, '--edit-minutes', $EditMinutes,
          '--stale-hours', $StaleHours)
if ($GoalKey) { $argv += @('--goal-key', $GoalKey) }
if ($Lane) { $argv += @('--lane', $Lane) }
if ($Scope) { $argv += @('--scope', $Scope) }
if ($Agent) { $argv += @('--agent', $Agent) }
if ($Task) { $argv += @('--task', $Task) }
if ($Strict) { $argv += '--strict' }
& python @argv
exit $LASTEXITCODE
