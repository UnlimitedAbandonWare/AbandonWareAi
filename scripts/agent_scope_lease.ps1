# Thin PowerShell proxy for scripts/agent_scope_lease.py so PowerShell-native
# callers get the same work-scope coordinator without typing the interpreter.
# All semantics, state and exit codes live in the Python tool.
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = 'Stop'
$tool = Join-Path $PSScriptRoot 'agent_scope_lease.py'
if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
    Write-Host "[agent-scope-lease][tool-missing] $tool"
    exit 6
}
$root = Split-Path -Parent $PSScriptRoot
& python -B $tool --root $root @Rest
exit $LASTEXITCODE
