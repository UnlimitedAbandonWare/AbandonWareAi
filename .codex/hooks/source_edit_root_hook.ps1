#requires -Version 5.1
<#
.SYNOPSIS
  UserPromptSubmit hook entry (Windows): resolve the project root from this
  file's own location (root/.codex/hooks/), verify the root markers, then run
  source_edit_triage.ps1. Replaces the former inline -Command payload whose
  $variables were interpolated away by the host's outer command layer.

.NOTES
  Contract: stdout may carry one hookSpecificOutput JSON object; diagnostics go
  to stderr; this gate is advisory, so every path exits 0.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try {
    $root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).ProviderPath
    $markers = @('.git', 'AGENTS.md', 'settings.gradle.kts', 'build.gradle.kts')
    $missing = @($markers | Where-Object { -not (Test-Path -LiteralPath (Join-Path $root $_)) })
    $triage = Join-Path $root '.codex\hooks\source_edit_triage.ps1'
    if ($missing.Count -eq 0 -and (Test-Path -LiteralPath $triage -PathType Leaf)) {
        & $triage
        if ($LASTEXITCODE -ne 0) {
            [Console]::Error.Write("source-edit-preflight-exit:$LASTEXITCODE")
        }
    } else {
        [Console]::Error.Write("source-edit-preflight-root-unresolved missing=$($missing -join ',')")
    }
} catch {
    [Console]::Error.Write("source-edit-preflight-error: $($_.Exception.Message)")
}
exit 0
