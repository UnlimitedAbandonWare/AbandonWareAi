# Session-local env for Codex MAX-PUSH focused Gradle.
# Dot-source it: . .\scripts\max_push_kit_env.ps1
# Does not start or restart Spring. Does not change the live Start-RAG host id
# desktop-meta-display. That launcher sets its own build host id.
param([switch]$PrintOnly)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$kitFile = Join-Path $root 'agent-prompts\clean-max-push-20260928\CURRENT_KIT.md'
$kit = 'A'
if (Test-Path -LiteralPath $kitFile) {
    $match = Select-String -LiteralPath $kitFile -Pattern '^kit:\s*([A-E])\s*$' | Select-Object -First 1
    if ($match) { $kit = $match.Matches[0].Groups[1].Value }
}
if (-not $PrintOnly) {
    $env:AWX_SPLIT_BUILD_OUTPUTS = '1'
    $env:AWX_BUILD_HOST_ID = 'codex-maxpush'
    $env:AWX_AGENT_SPEND_GUARD = '1'
}
Write-Output "[CLEAN-RAIL] kit=$kit ready"
Write-Output 'focused only; Stuff4=role-map; no admin-Browser Done; no full suite'
Write-Output 'R2/R3 preserve; no commit/push'
Write-Output 'AWX_SPLIT_BUILD_OUTPUTS=1 AWX_BUILD_HOST_ID=codex-maxpush AWX_AGENT_SPEND_GUARD=1'
