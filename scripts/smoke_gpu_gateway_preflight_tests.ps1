$ErrorActionPreference = "Stop"

$SmokeScript = Join-Path $PSScriptRoot "smoke_gpu_gateway_preflight.ps1"
$Failures = 0
$AdminToken = "gpu-gateway-test-admin-" + [guid]::NewGuid().ToString("N")
$PrivateBody = "gpu-gateway-private-body-" + [guid]::NewGuid().ToString("N")
$DatasetKey = "gpu-gateway-test-dataset-" + [guid]::NewGuid().ToString("N")
$DatasetFile = Join-Path ([System.IO.Path]::GetTempPath()) "gpu-gateway-test-dataset.jsonl"
$TempRoot = [System.IO.Path]::GetTempPath()
$Port = 51991
$ManagementPort = 51992
$OriginalEnv = @{}

function Write-Pass([string]$Name) {
    Write-Host "[gpu-gateway-preflight-test][PASS] $Name"
}

function Write-Fail([string]$Name, [string]$Reason) {
    $script:Failures++
    Write-Host "[gpu-gateway-preflight-test][FAIL] $Name :: $Reason"
}

function Assert-True([string]$Name, [bool]$Condition, [string]$Reason = "assertion failed") {
    if ($Condition) {
        Write-Pass $Name
    } else {
        Write-Fail $Name $Reason
    }
}

function Get-FreeTestPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    } finally {
        $listener.Stop()
    }
}

function Start-OneShotHtmlListener([int]$ListenerPort, [string]$ReadyFile, [string]$Body) {
    return Start-Job -ArgumentList $ListenerPort, $ReadyFile, $Body -ScriptBlock {
        param([int]$Port, [string]$ReadyPath, [string]$ResponseBody)
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        try {
            $listener.Start()
            [System.IO.File]::WriteAllText($ReadyPath, "ready", [System.Text.UTF8Encoding]::new($false))
            $client = $listener.AcceptTcpClient()
            try {
                $stream = $client.GetStream()
                $buffer = New-Object byte[] 4096
                [void]$stream.Read($buffer, 0, $buffer.Length)
                $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($ResponseBody)
                $header = "HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=UTF-8`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
                $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                $stream.Write($headerBytes, 0, $headerBytes.Length)
                $stream.Write($bodyBytes, 0, $bodyBytes.Length)
                $stream.Flush()
            } finally {
                $client.Dispose()
            }
        } finally {
            $listener.Stop()
        }
    }
}

function Assert-DiagnosticsFailure(
        [string]$Name,
        [string]$ContentType,
        [AllowEmptyString()][string]$Body,
        [string]$ExpectedMarker) {
    $script:FakeResponse = [pscustomobject]@{
        StatusCode = 200
        Content = $Body
        Headers = @{ "Content-Type" = $ContentType }
    }
    try {
        $null = Invoke-Diagnostics "/test"
        Write-Fail $Name "unexpected success"
    } catch {
        $message = [string]$_.Exception.Message
        Assert-True "$Name categorical" $message.Contains($ExpectedMarker) "missing categorical failure"
        Assert-True "$Name body private" (-not $message.Contains($PrivateBody)) "exception exposed response body"
        Assert-True "$Name token private" (-not $message.Contains($AdminToken)) "exception exposed admin token"
    }
}

$managedNames = @(
    "SPRING_PROFILES_ACTIVE",
    "SERVER_PORT",
    "MANAGEMENT_SERVER_PORT",
    "DOMAIN_ALLOWLIST_ADMIN_TOKEN",
    "DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED",
    "MACMINI_DATASET_API_ENABLED",
    "MACMINI_DESKTOP_GPU_3090_BASE_URL",
    "LLM_3090_BASE_URL"
)
$environmentBeforeTest = @{}
foreach ($name in $managedNames) {
    $environmentBeforeTest[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    Assert-True "smoke script exists" (Test-Path -LiteralPath $SmokeScript) "target script missing"
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile($SmokeScript, [ref]$tokens, [ref]$parseErrors)
    Assert-True "smoke script parses" ($parseErrors.Count -eq 0) "target script parse error"

    $functions = @{}
    foreach ($node in $ast.FindAll({
                param($candidate)
                $candidate -is [System.Management.Automation.Language.FunctionDefinitionAst]
            }, $true)) {
        if (-not $functions.ContainsKey($node.Name)) {
            $functions[$node.Name] = $node.Extent.Text
        }
    }

    $requiredFunctions = @(
        "Has-UsableSecret",
        "Redact-Text",
        "Set-SmokeEnvValue",
        "Clear-SmokeEnv",
        "Set-BaseSmokeEnv",
        "Set-BlockedGatewayEnv",
        "Set-DesktopGpuNodeEnv",
        "New-DiagnosticsHeaders",
        "Get-SafeContentType",
        "Get-NonJsonBodyClass",
        "Invoke-Diagnostics"
    )
    foreach ($name in $requiredFunctions) {
        if ($functions.ContainsKey($name)) {
            Invoke-Expression $functions[$name]
        } else {
            Write-Fail "function $name exists" "missing behavior seam"
        }
    }

    $sentinels = @{
        "SPRING_PROFILES_ACTIVE" = "sentinel-profile"
        "SERVER_PORT" = "sentinel-port"
        "MANAGEMENT_SERVER_PORT" = "sentinel-management-port"
        "DOMAIN_ALLOWLIST_ADMIN_TOKEN" = "sentinel-admin"
        "DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED" = "sentinel-required"
        "MACMINI_DESKTOP_GPU_3090_BASE_URL" = "sentinel-macmini-url"
        "LLM_3090_BASE_URL" = "sentinel-desktop-url"
    }
    foreach ($entry in $sentinels.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
    [Environment]::SetEnvironmentVariable("MACMINI_DATASET_API_ENABLED", $null, "Process")

    if (Get-Command Set-DesktopGpuNodeEnv -CommandType Function -ErrorAction SilentlyContinue) {
        Set-DesktopGpuNodeEnv 43210
        Assert-True "desktop env installs ephemeral admin token" ($env:DOMAIN_ALLOWLIST_ADMIN_TOKEN -eq $AdminToken) "generated token not configured"
        Assert-True "desktop env requires admin token" ($env:DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED -eq "true") "admin token requirement not enabled"

        $headers = New-DiagnosticsHeaders
        Assert-True "diagnostics sends one admin header" (($headers.Count -eq 1) -and $headers.ContainsKey("X-Admin-Token")) "unexpected diagnostics header set"
        Assert-True "diagnostics sends generated token" ($headers["X-Admin-Token"] -eq $AdminToken) "header does not use generated token"

        $script:FakeResponse = $null
        $script:CapturedHeaders = $null
        function Invoke-WebRequest {
            param($Uri, $Method, $UseBasicParsing, $TimeoutSec, $Headers)
            $script:CapturedHeaders = $Headers
            return $script:FakeResponse
        }

        $script:FakeResponse = [pscustomobject]@{
            StatusCode = 200
            Content = '{"status":"ok"}'
            Headers = @{ "Content-Type" = "application/problem+json; charset=utf-8" }
        }
        try {
            $jsonResult = Invoke-Diagnostics "/test"
            Assert-True "structured JSON content type accepted" (($jsonResult.Status -eq 200) -and ($jsonResult.Body.status -eq "ok")) "valid JSON response rejected"
            Assert-True "request carries only generated admin token" (($script:CapturedHeaders.Count -eq 1) -and ($script:CapturedHeaders["X-Admin-Token"] -eq $AdminToken)) "request header mismatch"
        } catch {
            Write-Fail "structured JSON content type accepted" "valid JSON response threw"
        }

        Assert-DiagnosticsFailure "login HTML response" "text/html;charset=UTF-8" ("<!doctype html><html><form action='/login'>" + $PrivateBody + "</form></html>") "bodyClass=login_html"
        Assert-DiagnosticsFailure "generic HTML response" "text/html" ("<html><body>" + $PrivateBody + "</body></html>") "bodyClass=html"
        Assert-DiagnosticsFailure "plain text response" "text/plain" $PrivateBody "bodyClass=plain_text"
        Assert-DiagnosticsFailure "empty JSON response" "application/json" "" "diagnostics-json-empty"
        Assert-DiagnosticsFailure "malformed JSON response" "application/json" ("{`"private`":`"" + $PrivateBody) "diagnostics-json-invalid"

        Remove-Item -LiteralPath Function:\Invoke-WebRequest -ErrorAction SilentlyContinue

        $wrongListenerPort = Get-FreeTestPort
        $readyFile = Join-Path ([System.IO.Path]::GetTempPath()) ("gpu-gateway-wrong-listener-" + [guid]::NewGuid().ToString("N"))
        $wrongListener = Start-OneShotHtmlListener $wrongListenerPort $readyFile ("<html><body>" + $PrivateBody + "</body></html>")
        try {
            $deadline = (Get-Date).AddSeconds(10)
            while (-not (Test-Path -LiteralPath $readyFile) -and (Get-Date) -lt $deadline) {
                Start-Sleep -Milliseconds 25
            }
            $Port = $wrongListenerPort
            try {
                $null = Invoke-Diagnostics "/test"
                Write-Fail "wrong HTML listener fails closed" "unexpected success"
            } catch {
                $message = [string]$_.Exception.Message
                Assert-True "wrong HTML listener fails closed" $message.Contains("bodyClass=html") "wrong listener was not categorized"
                Assert-True "wrong HTML listener body private" (-not $message.Contains($PrivateBody)) "wrong listener body exposed"
            }
        } finally {
            Stop-Job -Job $wrongListener -ErrorAction SilentlyContinue | Out-Null
            Remove-Job -Job $wrongListener -Force -ErrorAction SilentlyContinue | Out-Null
            Remove-Item -LiteralPath $readyFile -Force -ErrorAction SilentlyContinue
        }

        $Port = Get-FreeTestPort
        try {
            $null = Invoke-Diagnostics "/test"
            Write-Fail "closed port fails closed" "unexpected success"
        } catch {
            $message = [string]$_.Exception.Message
            Assert-True "closed port categorical" $message.Contains("diagnostics-connection-failure") "connection failure was not categorized"
            Assert-True "closed port token private" (-not $message.Contains($AdminToken)) "connection failure exposed token"
        }

        Set-BlockedGatewayEnv 43211
        Clear-SmokeEnv
        foreach ($entry in $sentinels.GetEnumerator()) {
            Assert-True "restore env $($entry.Key)" ([Environment]::GetEnvironmentVariable($entry.Key, "Process") -eq $entry.Value) "original process value not restored"
        }
        Assert-True "restore absent env MACMINI_DATASET_API_ENABLED" ($null -eq [Environment]::GetEnvironmentVariable("MACMINI_DATASET_API_ENABLED", "Process")) "original absence not restored"
        Assert-True "environment snapshot clears after restore" ($OriginalEnv.Count -eq 0) "environment snapshot retained values"
    }
} catch {
    Write-Fail "test harness completed" ("unexpected exception type=" + $_.Exception.GetType().FullName)
} finally {
    Remove-Item -LiteralPath Function:\Invoke-WebRequest -ErrorAction SilentlyContinue
    foreach ($entry in $environmentBeforeTest.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
}

if ($Failures -gt 0) {
    Write-Host "[gpu-gateway-preflight-test][SUMMARY] failures=$Failures"
    exit 1
}

Write-Host "[gpu-gateway-preflight-test][SUMMARY] failures=0"
exit 0
