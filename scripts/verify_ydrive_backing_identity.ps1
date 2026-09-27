param(
    [string]$CanonicalWorkspace = 'Y:\',
    [Parameter(Mandatory = $true)]
    [string]$ExpectedSha256,
    [Parameter(DontShow = $true)]
    [scriptblock]$MappingResolver = {
        param([string]$DriveName)
        (Get-PSDrive -Name $DriveName -ErrorAction Stop).DisplayRoot
    }
)

$verified = $false
$reason = 'evidence-needed'

try {
    if ($CanonicalWorkspace -ne 'Y:\' -or $ExpectedSha256 -notmatch '^[A-Fa-f0-9]{64}$') {
        throw 'invalid-probe-input'
    }
    $driveName = $CanonicalWorkspace.TrimEnd('\').TrimEnd(':')
    $resolverRecords = @(& $MappingResolver $driveName *>&1)
    $auxiliaryRecordTypes = @(
        [System.Management.Automation.ErrorRecord],
        [System.Management.Automation.WarningRecord],
        [System.Management.Automation.VerboseRecord],
        [System.Management.Automation.DebugRecord],
        [System.Management.Automation.InformationRecord]
    )
    $successValues = @()
    $auxiliaryRecordFound = $false
    foreach ($record in $resolverRecords) {
        $isAuxiliary = $false
        foreach ($recordType in $auxiliaryRecordTypes) {
            if ($record -is $recordType) {
                $isAuxiliary = $true
                break
            }
        }
        if ($isAuxiliary) {
            $auxiliaryRecordFound = $true
        } else {
            $successValues += $record
        }
    }
    if ($auxiliaryRecordFound -or $successValues.Count -ne 1 -or $successValues[0] -isnot [string]) {
        throw 'resolver-output-invalid'
    }
    $mapping = $successValues[0]
    if ([string]::IsNullOrWhiteSpace($mapping)) { throw 'mapping-unavailable' }
    $normalized = $mapping.Trim().TrimEnd('\').ToLowerInvariant()
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $actual = ([BitConverter]::ToString(
            $sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($normalized))
        )).Replace('-', '').ToUpperInvariant()
    } finally {
        $sha.Dispose()
    }
    $verified = $actual -eq $ExpectedSha256.ToUpperInvariant()
    $reason = if ($verified) { 'match' } else { 'mismatch' }
} catch {
    $verified = $false
    $reason = 'evidence-needed'
}

[pscustomobject][ordered]@{
    canonicalWorkspace = 'Y:\'
    backingShareIdentityVerified = $verified
    backingShareIdentityReason = $reason
} | ConvertTo-Json -Compress
