[CmdletBinding()]
param(
    [string]$Root = '.',
    [string]$Topic = '',
    [string]$Node = 'notebook',
    [string]$VerificationProfile = 'powershell-tooling',
    [string]$RequestId = '',
    [string]$TtlMinutes = '180'
)

$ErrorActionPreference = 'Stop'
try {
    $parsedTtl = 0
    if (-not [int]::TryParse($TtlMinutes, [ref]$parsedTtl) -or $parsedTtl -lt 5 -or $parsedTtl -gt 1440) { throw '[autoconsume][ttl-invalid]' }
    if ([string]::IsNullOrWhiteSpace($Topic)) { throw '[autoconsume][topic-invalid]' }
    if ($Node -notin @('notebook', 'macmini')) { throw '[autoconsume][node-invalid]' }
    Import-Module (Join-Path $PSScriptRoot 'modules\PatchDropAutoconsume.psm1') -Force
    $result = Publish-AwxAutoconsumeRequest -Root $Root -Topic $Topic -Node $Node -VerificationProfile $VerificationProfile -RequestId $RequestId -TtlMinutes $parsedTtl
    $result | ConvertTo-Json -Compress
} catch {
    $reasonCode = 'publication-rejected'
    if ($_.Exception.Message -match '^\[autoconsume\]\[([a-z0-9-]+)\]$') { $reasonCode = $Matches[1] }
    [ordered]@{ ok = $false; reasonCode = $reasonCode } | ConvertTo-Json -Compress
    exit 1
}
