$ErrorActionPreference = 'Stop'
$Probe = Join-Path $PSScriptRoot 'verify_ydrive_backing_identity.ps1'
$ExpectedFields = 'backingShareIdentityReason,backingShareIdentityVerified,canonicalWorkspace'

function Get-TestSha256([string]$Value) {
    $normalized = $Value.Trim().TrimEnd('\').ToLowerInvariant()
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString(
            $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalized))
        )).Replace('-', '').ToUpperInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Invoke-TestProbe(
    [scriptblock]$Resolver,
    [string]$Expected,
    [string]$ExpectedReason,
    [bool]$ExpectedVerified,
    [string[]]$ForbiddenSentinels = @()
) {
    $records = @(& $Probe -CanonicalWorkspace 'Y:\' -ExpectedSha256 $Expected -MappingResolver $Resolver *>&1)
    Assert-True ($records.Count -eq 1) "probe-record-count:$($records.Count)"
    $raw = [string]$records[0]
    Assert-True ($raw.StartsWith('{') -and $raw.EndsWith('}')) 'probe-output-not-one-json-object'
    foreach ($sentinel in $ForbiddenSentinels) {
        Assert-True (-not $raw.Contains($sentinel)) "resolver-sentinel-leaked:$sentinel"
    }
    $value = $raw | ConvertFrom-Json
    $keys = @($value.PSObject.Properties.Name | Sort-Object)
    Assert-True (($keys -join ',') -eq $ExpectedFields) 'unexpected-output-fields'
    Assert-True ($value.canonicalWorkspace -eq 'Y:\') 'wrong-canonical-workspace'
    Assert-True ($value.backingShareIdentityVerified -eq $ExpectedVerified) 'wrong-verification-boolean'
    Assert-True ($value.backingShareIdentityReason -eq $ExpectedReason) 'wrong-verification-reason'
    return [pscustomobject]@{ Raw = $raw; Value = $value }
}

$mapping = 'SyntheticBackingRoot\'
$expected = Get-TestSha256 $mapping

$matchResolver = { param([string]$DriveName) $mapping }.GetNewClosure()
$match = Invoke-TestProbe $matchResolver $expected 'match' $true @($mapping, $expected)

$mismatchResolver = { param([string]$DriveName) $mapping }.GetNewClosure()
Invoke-TestProbe $mismatchResolver ('0' * 64) 'mismatch' $false @($mapping) | Out-Null

$missingResolver = { param([string]$DriveName) '' }
Invoke-TestProbe $missingResolver $expected 'evidence-needed' $false | Out-Null

$streamCases = @(
    [pscustomobject]@{ Name = 'warning'; Sentinel = 'SENTINEL_WARNING'; Resolver = { param([string]$DriveName) Write-Warning 'SENTINEL_WARNING'; 'SyntheticBackingRoot\' } },
    [pscustomobject]@{ Name = 'information'; Sentinel = 'SENTINEL_INFORMATION'; Resolver = { param([string]$DriveName) Write-Information 'SENTINEL_INFORMATION' -InformationAction Continue; 'SyntheticBackingRoot\' } },
    [pscustomobject]@{ Name = 'host'; Sentinel = 'SENTINEL_HOST'; Resolver = { param([string]$DriveName) Write-Host 'SENTINEL_HOST'; 'SyntheticBackingRoot\' } },
    [pscustomobject]@{ Name = 'verbose'; Sentinel = 'SENTINEL_VERBOSE'; Resolver = { param([string]$DriveName) Write-Verbose 'SENTINEL_VERBOSE' -Verbose; 'SyntheticBackingRoot\' } },
    [pscustomobject]@{ Name = 'debug'; Sentinel = 'SENTINEL_DEBUG'; Resolver = { param([string]$DriveName) Write-Debug 'SENTINEL_DEBUG' -Debug; 'SyntheticBackingRoot\' } },
    [pscustomobject]@{ Name = 'nonterminating-error'; Sentinel = 'SENTINEL_ERROR'; Resolver = { param([string]$DriveName) Write-Error 'SENTINEL_ERROR' -ErrorAction Continue; 'SyntheticBackingRoot\' } }
)
foreach ($case in $streamCases) {
    try {
        Invoke-TestProbe $case.Resolver $expected 'evidence-needed' $false @($case.Sentinel, $mapping) | Out-Null
    } catch {
        throw "stream-case-failed:$($case.Name):$($_.Exception.Message)"
    }
}

$multipleResolver = { param([string]$DriveName) 'SyntheticBackingRoot\'; 'SecondSuccessValue\' }
Invoke-TestProbe $multipleResolver $expected 'evidence-needed' $false @('SyntheticBackingRoot', 'SecondSuccessValue') | Out-Null

$throwingResolver = { param([string]$DriveName) throw 'SENTINEL_THROW' }
Invoke-TestProbe $throwingResolver $expected 'evidence-needed' $false @('SENTINEL_THROW') | Out-Null

$source = Get-Content -Raw -LiteralPath $Probe
Assert-True (-not $source.Contains('SHA256]::HashData')) 'unsupported-hashdata-api'
Assert-True ($source.Contains('SHA256]::Create')) 'compatible-hash-api-missing'
Write-Host "PASS verify_ydrive_backing_identity_contract_tests failures=0 cases=$($streamCases.Count + 5)"
