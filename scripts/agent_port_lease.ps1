#requires -Version 5.1
<#
.SYNOPSIS
  Named-parameter entry for the agent port lease. Python is the contract engine.
.DESCRIPTION
  Forwards to scripts\agent_port_lease.py. Stop and release only the owner/session
  lease. Does not accept a pid, image name, or port-kill switch.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('acquire', 'release', 'start', 'stop', 'close', 'health', 'trace', 'diagnose', 'run', 'status', 'verify', 'heartbeat', 'reap', 'contract')]
    [string]$Action,
    [string]$Owner = '',
    [string]$Session = '',
    [string]$Lease = '',
    [string]$Service = 'agent-server',
    [string]$Range = '',
    [int]$Port = 0,
    [int]$Attempts = 3,
    [int]$TtlSeconds = 1800,
    [double]$HealthTimeout = 5,
    [double]$FollowSeconds = 0,
    [string]$HealthUrl = '',
    [string]$TraceId = '',
    [string]$TraceAction = '',
    [string]$DiagnoseAction = '',
    [string]$Cause = '',
    [string]$Root = '',
    [switch]$Keep,
    [string[]]$ServerArg
)

$ErrorActionPreference = 'Stop'
$engine = Join-Path $PSScriptRoot 'agent_port_lease.py'
if (-not (Test-Path -LiteralPath $engine -PathType Leaf)) {
    Write-Error "agent_port_lease.py missing: $engine"
    exit 1
}
$mutating = @('acquire', 'release', 'start', 'stop', 'close', 'health', 'run', 'verify', 'heartbeat', 'reap')
if ($mutating -contains $Action -and ([string]::IsNullOrWhiteSpace($Owner) -or [string]::IsNullOrWhiteSpace($Session))) {
    Write-Error "owner and session are required for $Action"
    exit 2
}

$argv = @('-B', $engine, $Action)
if ($Root) { $argv += @('--root', $Root) }
if ($Owner) { $argv += @('--owner', $Owner) }
if ($Session) { $argv += @('--session', $Session) }
if ($Lease) { $argv += @('--lease', $Lease) }
if ($Action -eq 'acquire' -or $Action -eq 'run') {
    $argv += @('--service', $Service)
    if ($Range) { $argv += @('--range', $Range) }
    $argv += @('--ttl-seconds', [string]$TtlSeconds)
}
if ($Action -eq 'acquire' -and $Port -gt 0) { $argv += @('--port', [string]$Port) }
if ($Action -eq 'run') {
    $argv += @('--attempts', [string]$Attempts, '--health-timeout', [string]$HealthTimeout)
    if ($Keep) { $argv += '--keep' }
}
if ($HealthUrl -and ($Action -eq 'start' -or $Action -eq 'run' -or $Action -eq 'health' -or $Action -eq 'verify')) {
    $argv += @('--health-url', $HealthUrl)
}
if ($Action -eq 'start' -or $Action -eq 'run') {
    $argv += @('--health-timeout', [string]$HealthTimeout)
}
if ($Action -eq 'trace') {
    if (-not $TraceAction) { Write-Error 'trace requires -TraceAction add|query'; exit 2 }
    $argv = @('-B', $engine, 'trace', $TraceAction) + $(if ($Root) { @('--root', $Root) } else { @() })
    if ($Owner) { $argv += @('--owner', $Owner) }
    if ($Session) { $argv += @('--session', $Session) }
    if ($Lease) { $argv += @('--lease', $Lease) }
    if ($TraceId) { $argv += @('--trace-id', $TraceId) }
    if ($Cause) { $argv += @('--cause', $Cause) }
}
if ($Action -eq 'diagnose') {
    if (-not $DiagnoseAction) { Write-Error 'diagnose requires -DiagnoseAction tail|trace|around'; exit 2 }
    $argv = @('-B', $engine, 'diagnose', $DiagnoseAction) + $(if ($Root) { @('--root', $Root) } else { @() })
    if ($Owner) { $argv += @('--owner', $Owner) }
    if ($Session) { $argv += @('--session', $Session) }
    if ($Lease) { $argv += @('--lease', $Lease) }
    if ($TraceId) { $argv += @('--trace-id', $TraceId) }
    if ($FollowSeconds -gt 0) { $argv += @('--follow-seconds', [string]$FollowSeconds) }
}
if ($ServerArg -and $ServerArg.Count -gt 0 -and ($Action -eq 'start' -or $Action -eq 'run' -or $Action -eq 'verify')) {
    $argv += '--'
    $argv += $ServerArg
}

& python @argv
exit $LASTEXITCODE
