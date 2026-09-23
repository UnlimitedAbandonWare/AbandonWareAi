# Thin PowerShell proxy for scripts/lease_conflict_autoflow.py so PowerShell-native
# callers get the same lease-conflict autoflow without typing the interpreter.
# All semantics, state and exit codes live in the Python tool.
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = 'Stop'
$tool = Join-Path $PSScriptRoot 'lease_conflict_autoflow.py'
if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
    Write-Host "[lease-conflict-autoflow][tool-missing] $tool"
    exit 6
}
$root = Split-Path -Parent $PSScriptRoot
& python -B $tool --root $root @Rest
exit $LASTEXITCODE
