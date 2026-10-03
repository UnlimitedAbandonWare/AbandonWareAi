#requires -Version 5.1
<#
.SYNOPSIS
    Thin wrapper for scripts/codex_lane_plan.py (SSOT is the Python file).

.DESCRIPTION
    Codex parallel-lane planner: splits one goal brief into lanes whose write
    scopes never overlap, writes plan.json + lane-<id>.txt + PLAN_KO.md under
    data/agent-handoff/parallel-lanes/<planId>/. Exit codes pass through
    (0 ok, 2 usage, 5 SERIAL_REQUIRED-only).

.EXAMPLE
    powershell -NoProfile -File .\scripts\codex_lane_plan.ps1 -Brief agent-prompts\x\BRIEF.txt
    powershell -NoProfile -File .\scripts\codex_lane_plan.ps1 -Wp "failover=a.java,b.java" -Wp "warmup=c.java"
#>
[CmdletBinding()]
param(
    [string]$Root = '.',
    [string]$Brief,
    [string[]]$Wp,
    [int]$Lanes = 0,
    [string]$GoalKey = ''
)
$py = Join-Path $PSScriptRoot 'codex_lane_plan.py'
$argv = @('-B', $py, '--root', $Root)
if ($Brief) { $argv += @('--brief', $Brief) }
foreach ($w in @($Wp)) { $argv += @('--wp', $w) }
if ($Lanes -gt 0) { $argv += @('--lanes', $Lanes) }
if ($GoalKey) { $argv += @('--goal-key', $GoalKey) }
& python @argv
exit $LASTEXITCODE
