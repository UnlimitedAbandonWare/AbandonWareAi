[CmdletBinding()]
param(
    [string]$Root = '',

    [string]$OutputDir = '',

    [string]$Topic = 'mcp-control-loop',

    [switch]$Status,

    [switch]$ExternalDispatch,

    [switch]$RequireSupabaseProof,

    [switch]$RefreshWebProbe,

    [switch]$IndependentWorkAvailable,

    [string]$RecoveryEvidencePath = '',

    [switch]$Help
)

$ErrorActionPreference = 'Stop'

$autoScript = Join-Path $PSScriptRoot 'goal_next_auto.ps1'

if ($Help) {
    @'
[AWX][goal-next-wrapper] usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1 -Root <repo-root> -Topic mcp-control-loop
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1 -Root <repo-root> -Status
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1 -Root <repo-root> -ExternalDispatch
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1 -Root <repo-root> -RequireSupabaseProof
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1 -Root <repo-root> -RefreshWebProbe
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next.ps1 -Root <repo-root> -IndependentWorkAvailable -RecoveryEvidencePath <redacted-proof.json>

Purpose:
  Short "next" entrypoint for goal_next_auto.ps1.
  Default behavior runs goal_next_auto.ps1 -EnsureFresh.
  Use -Status to read the current latest/status artifacts without refreshing.
  Use -ExternalDispatch only when Mac mini/Notebook producer dispatch files and producer kit export are explicitly required.
  Use -RequireSupabaseProof only when project-scoped read-only Supabase proof is explicitly required.
  Use -RefreshWebProbe only when a fresh network-backed official-source probe is explicitly required.
  Use -IndependentWorkAvailable to resume independent work when the same blocker persists.
  Use -RecoveryEvidencePath to include new redacted recovery evidence in reassessment.

Safety:
  Delegates secret redaction and evidence_needed handling to goal_next_auto.ps1.
  Does not print token values, Authorization headers, cookies, JDBC URLs, or raw secrets.
'@ | Write-Host
    exit 0
}

$runnerArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $autoScript)
if (-not [string]::IsNullOrWhiteSpace($Root)) {
    $runnerArgs += @('-Root', $Root)
}
if (-not [string]::IsNullOrWhiteSpace($OutputDir)) {
    $runnerArgs += @('-OutputDir', $OutputDir)
}
if (-not [string]::IsNullOrWhiteSpace($Topic)) {
    $runnerArgs += @('-Topic', $Topic)
}
if ($Status) {
    $runnerArgs += '-Status'
} else {
    $runnerArgs += '-EnsureFresh'
}
if ($ExternalDispatch) {
    $runnerArgs += '-ExternalDispatch'
}
if ($RequireSupabaseProof) {
    $runnerArgs += '-RequireSupabaseProof'
}
if ($RefreshWebProbe) {
    $runnerArgs += '-RefreshWebProbe'
}
if ($IndependentWorkAvailable) { $runnerArgs += '-IndependentWorkAvailable' }
if ($RecoveryEvidencePath) { $runnerArgs += @('-RecoveryEvidencePath', $RecoveryEvidencePath) }

& powershell @runnerArgs
exit $LASTEXITCODE
