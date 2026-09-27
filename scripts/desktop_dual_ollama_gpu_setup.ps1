#requires -Version 5.1

[CmdletBinding()]
param(
    [ValidateSet('ValidateOnly', 'Start')]
    [string]$Mode = 'ValidateOnly',

    [string]$FastModelsPath = $(if ([string]::IsNullOrWhiteSpace($env:OLLAMA_MODELS)) { Join-Path $env:USERPROFILE '.ollama\models' } else { $env:OLLAMA_MODELS }),
    [string]$MainModelsPath = 'E:\models',

    [switch]$AcknowledgeColdRebootGate
)

$ErrorActionPreference = 'Stop'
$modulePath = Join-Path $PSScriptRoot 'modules\DesktopDualOllamaGpuSetup.psm1'
Import-Module $modulePath -Force -ErrorAction Stop

if ($Mode -eq 'Start' -and -not $AcknowledgeColdRebootGate) {
    [pscustomobject]@{
        schemaVersion = 1
        mode = $Mode
        verdict = 'HOLD'
        reasonCodes = @('cold_reboot_gate_not_acknowledged')
        gpuLanes = @()
        ports = @()
        stores = @()
        models = @()
        startedCount = 0
        startedLanes = @()
    } | ConvertTo-Json -Depth 10
    exit 2
}

$result = Invoke-DesktopDualOllamaSetup `
    -Mode $Mode `
    -FastModelsPath $FastModelsPath `
    -MainModelsPath $MainModelsPath

$result | ConvertTo-Json -Depth 10
if ($result.verdict -ne 'PASS') {
    exit 2
}
