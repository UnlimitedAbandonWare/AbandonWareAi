# test_tavily_serpapi_keys.ps1
# One-shot live smoke test for TAVILY_API_KEY / SERPAPI_API_KEY (User env scope).
# Prints only status / counts / reason codes. NEVER prints key values.
[CmdletBinding()]
param(
    [string]$Query = "OpenAI",
    [int]$TimeoutSec = 15
)
$ErrorActionPreference = 'Stop'

function Get-Key([string]$name) {
    $v = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ([string]::IsNullOrWhiteSpace($v)) { $v = [Environment]::GetEnvironmentVariable($name, 'User') }
    if ([string]::IsNullOrWhiteSpace($v)) { $v = [Environment]::GetEnvironmentVariable($name, 'Machine') }
    return $v
}

function Report([string]$provider, [bool]$hasKey, [string]$status, [string]$detail) {
    [pscustomobject]@{
        provider = $provider
        hasKey   = $hasKey
        status   = $status     # ok | http_error | error | skipped_no_key
        detail   = $detail     # counts / status codes / short reason only
    }
}

$results = @()

# ---------- Tavily: POST https://api.tavily.com/search ----------
$tavilyKey = Get-Key 'TAVILY_API_KEY'
if ([string]::IsNullOrWhiteSpace($tavilyKey)) {
    $results += Report 'tavily' $false 'skipped_no_key' 'TAVILY_API_KEY not set in Process/User/Machine'
} else {
    try {
        $body = @{ query = $Query; max_results = 1; search_depth = 'basic' } | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Method Post -Uri 'https://api.tavily.com/search' `
            -Headers @{ 'Authorization' = "Bearer $tavilyKey"; 'Content-Type' = 'application/json' } `
            -Body $body -TimeoutSec $TimeoutSec
        $count = 0
        if ($null -ne $resp.results) { $count = @($resp.results).Count }
        $results += Report 'tavily' $true 'ok' ("results={0}; answer_present={1}" -f $count, ($null -ne $resp.answer))
    } catch {
        $code = $null
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        $results += Report 'tavily' $true 'http_error' ("statusCode={0}; msg={1}" -f $code, ($_.Exception.Message -replace '[A-Za-z0-9_\-]{20,}', '[redacted]'))
    }
}

# ---------- SerpAPI: GET https://serpapi.com/search.json?engine=google&q=..&api_key=.. ----------
$serpKey = Get-Key 'SERPAPI_API_KEY'
if ([string]::IsNullOrWhiteSpace($serpKey)) {
    $results += Report 'serpapi' $false 'skipped_no_key' 'SERPAPI_API_KEY not set in Process/User/Machine'
} else {
    try {
        $uri = 'https://serpapi.com/search.json?engine=google&num=1&q={0}&api_key={1}' -f `
            [uri]::EscapeDataString($Query), [uri]::EscapeDataString($serpKey)
        $resp = Invoke-RestMethod -Method Get -Uri $uri -TimeoutSec $TimeoutSec
        $count = 0
        if ($null -ne $resp.organic_results) { $count = @($resp.organic_results).Count }
        $err = if ($resp.error) { " api_error_field=$($resp.error -replace '[A-Za-z0-9_\-]{20,}','[redacted]')" } else { '' }
        $results += Report 'serpapi' $true 'ok' ("organic_results={0}{1}" -f $count, $err)
    } catch {
        $code = $null
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
        $results += Report 'serpapi' $true 'http_error' ("statusCode={0}; msg={1}" -f $code, ($_.Exception.Message -replace '[A-Za-z0-9_\-]{20,}', '[redacted]'))
    }
}

$results | Format-Table -AutoSize | Out-String -Width 160 | Write-Output
$failed = @($results | Where-Object { $_.status -ne 'ok' })
if ($failed.Count -gt 0) { exit 1 }
exit 0
