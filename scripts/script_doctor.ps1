#requires -Version 5.1
<#
.SYNOPSIS
  Strict-mode facade for scripts/script_doctor.py.
.DESCRIPTION
  Read-only. -WhatIf prints the plan and does not scan or write a report.
#>
[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'Low')]
param(
    [string]$ReportPath = '',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Remaining
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$Root = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ReportPath)) {
    $ReportPath = Join-Path $Root 'var\diagnostics\script_doctor_report.json'
}
$Doctor = Join-Path $PSScriptRoot 'script_doctor.py'
if (-not (Test-Path -LiteralPath $Doctor -PathType Leaf)) {
    Write-Error 'script_doctor.py missing'
    exit 1
}
if (-not $PSCmdlet.ShouldProcess($Root, 'Run script doctor scan')) {
    Write-Output 'WhatIf: script doctor scan skipped'
    exit 0
}
$pyArgs = @('--summary', '--json', $ReportPath)
if ($Remaining -and $Remaining.Count -gt 0) {
    $pyArgs = @($Remaining)
}
& python -B $Doctor @pyArgs
$code = $LASTEXITCODE
if ($null -eq $code) { $code = 1 }
exit $code
