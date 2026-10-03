#requires -Version 5.1
<#
.SYNOPSIS
  UserPromptSubmit hook entry (Windows): run scripts/awx_device_bus.py hook
  against the project root. Replaces the former inline -Command payload, whose
  $variables were interpolated away by the host's outer command layer, which
  executed mangled text and failed every run.

.NOTES
  Contract: stdout may carry one hookSpecificOutput JSON object; diagnostics go
  to stderr; this gate never blocks a prompt, so every path exits 0.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
try {
    $root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).ProviderPath
    $script = Join-Path $root 'scripts\awx_device_bus.py'
    $config = Join-Path $root 'config\project-resources.json'
    if ((Test-Path -LiteralPath $script -PathType Leaf) -and (Test-Path -LiteralPath $config -PathType Leaf)) {
        & python -B $script hook --root $root
        if ($LASTEXITCODE -ne 0) {
            [Console]::Error.Write("capabilities-hook-exit:$LASTEXITCODE")
        }
    } else {
        [Console]::Error.Write('capabilities-hook-inputs-missing')
    }
} catch {
    [Console]::Error.Write("capabilities-hook-error: $($_.Exception.Message)")
}
exit 0
