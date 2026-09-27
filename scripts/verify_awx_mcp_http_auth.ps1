[CmdletBinding()]
param(
    [string]$Python = 'python',
    [string]$OutputDirectory = '',
    [string]$Url = '',
    [string]$TokenEnvironmentVariable = 'AWX_MCP_API_KEY'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputDirectory) {
    $OutputDirectory = Join-Path $repoRoot 'build/awx-mcp-http-verification'
}
$outputPath = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null
$junit = Join-Path $outputPath 'junit.xml'
$testPath = Join-Path $PSScriptRoot 'test_awx_mcp_http_server.py'
& $Python -X utf8 -m pytest $testPath -q --tb=short "--junitxml=$junit"
$testExit = $LASTEXITCODE
$passed = $false
$counts = @{ tests = 0; failures = 0; errors = 0; skipped = 0 }
if (Test-Path -LiteralPath $junit) {
    [xml]$xml = Get-Content -LiteralPath $junit -Raw -Encoding UTF8
    foreach ($suite in $xml.testsuites.testsuite) {
        foreach ($name in @('tests', 'failures', 'errors', 'skipped')) {
            $counts[$name] += [int]$suite.GetAttribute($name)
        }
    }
    $passed = $testExit -eq 0 -and $counts.tests -gt 0 -and $counts.failures -eq 0 -and $counts.errors -eq 0 -and $counts.skipped -eq 0
}
$probe = 'not_requested'
if ($Url) {
    Add-Type -AssemblyName System.Net.Http
    $uri = [Uri]$Url
    if (-not $uri.IsAbsoluteUri -or $uri.UserInfo -or $uri.Query -or $uri.Fragment -or
        ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $uri.IsLoopback))) {
        throw 'Probe URL must use HTTPS or loopback HTTP, with no credentials/query/fragment.'
    }
    $token = [Environment]::GetEnvironmentVariable($TokenEnvironmentVariable, 'Process')
    if (-not $token) { throw 'Probe token environment variable is missing.' }
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(20)
    $results = @()
    try {
        foreach ($case in @('missing', 'wrong', 'valid')) {
            $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $uri)
            try {
                $request.Headers.Accept.ParseAdd('application/json')
                $request.Headers.Accept.ParseAdd('text/event-stream')
                if ($case -ne 'missing') {
                    $value = if ($case -eq 'valid') { $token } else { 'invalid-fixture-token' }
                    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer', $value)
                }
                $body = '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"awx-auth-probe","version":"1"}}}'
                $request.Content = [System.Net.Http.StringContent]::new($body, [Text.Encoding]::UTF8, 'application/json')
                $response = $client.SendAsync($request).GetAwaiter().GetResult()
                try {
                    $status = [int]$response.StatusCode
                    $valid = $status -eq $(if ($case -eq 'valid') { 200 } else { 401 })
                    if ($case -eq 'valid' -and $valid) {
                        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
                        $data = ($text -split "`n" | Where-Object { $_.StartsWith('data: ') } | Select-Object -First 1)
                        if ($data) { $text = $data.Substring(6) }
                        $parsed = $text | ConvertFrom-Json
                        $valid = $parsed.result.serverInfo.name -eq 'awx-remote'
                    }
                    $results += @{ case = $case; status = $status; passed = $valid }
                } finally { $response.Dispose() }
            } finally { $request.Dispose() }
        }
        $probe = $results
        $passed = $passed -and @($results | Where-Object { -not $_.passed }).Count -eq 0
    } finally { $client.Dispose(); $handler.Dispose(); $token = $null }
}
$report = @{ schema = 'awx.remote-mcp.local-autograder.v1'; passed = $passed; counts = $counts; exitCode = $testExit; junit = $junit; endpointProbe = $probe; dockerExecution = 'not_observed' }
$json = $report | ConvertTo-Json -Depth 5
[IO.File]::WriteAllText((Join-Path $outputPath 'result.json'), $json + "`n", [Text.UTF8Encoding]::new($false))
Write-Output $json
if (-not $passed) { exit 1 }
exit 0
