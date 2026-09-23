$ErrorActionPreference = "Stop"

$failures = New-Object System.Collections.Generic.List[string]
$scriptPath = Join-Path $PSScriptRoot "domain_public_https_preflight.ps1"

function Assert-True {
    param(
        [string]$Name,
        [bool]$Condition,
        [string]$Detail = ""
    )
    if (-not $Condition) {
        $suffix = if ([string]::IsNullOrWhiteSpace($Detail)) { "" } else { " :: $Detail" }
        $script:failures.Add("$Name$suffix") | Out-Null
    }
}

$scriptSource = Get-Content -Raw -LiteralPath $scriptPath
Assert-True "preflight certificate name check reads SAN extension" `
    ($scriptSource.Contains('"2.5.29.17"')) `
    "subjectAltName OID missing"
Assert-True "preflight certificate name check accepts DNS SAN values" `
    ($scriptSource -match "DNS Name\\s\*=") `
    "DNS SAN parsing missing"

$output = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
    -Domain localhost `
    -ConnectHost 127.0.0.1 `
    -HttpPort 0 `
    -HttpsPort 65534 `
    -RequireTrustedCertificate `
    -Json 2>&1 | ForEach-Object { $_.ToString() }

$text = ($output -join "`n")
$trimmed = $text.TrimStart()
Assert-True "preflight -Json starts with JSON object" `
    ($trimmed.StartsWith("{")) `
    "firstLine=$(@($output) | Select-Object -First 1)"
Assert-True "preflight -Json does not emit warning prefix" `
    (-not ($text -match "(?m)^WARNING:")) `
    "output=$text"

try {
    $json = $trimmed | ConvertFrom-Json
    Assert-True "preflight closed 443 classifies public listener" `
        ([string]$json.failureClassification -eq "public-listener-unreachable") `
        "json=$($json | ConvertTo-Json -Compress)"
    $tcp443 = @($json.checks) | Where-Object { $_.name -eq "tcp.65534" } | Select-Object -First 1
    Assert-True "preflight closed 443 marks TCP required for TLS proof" `
        ($null -ne $tcp443 -and [bool]$tcp443.detail.required -eq $true -and [bool]$tcp443.ok -eq $false) `
        "json=$($json | ConvertTo-Json -Compress)"
} catch {
    $failures.Add("preflight -Json parses as JSON :: $($_.Exception.Message)") | Out-Null
}

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Host "[domain-preflight-test][FAIL] $failure"
    }
    Write-Host "[domain-preflight-test][SUMMARY] failed=$($failures.Count)"
    exit 1
}

Write-Host "[domain-preflight-test][SUMMARY] failed=0"
