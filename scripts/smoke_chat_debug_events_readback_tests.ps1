$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$SmokeScript = Join-Path $ScriptsRoot 'smoke_chat_debug_events_readback.ps1'
$PowerShellExe = $null
try {
    $PowerShellExe = (Get-Process -Id $PID).Path
} catch {
    $PowerShellExe = $null
}
if ([string]::IsNullOrWhiteSpace($PowerShellExe)) {
    $PowerShellExe = 'powershell'
}
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[chat-debug-events-readback-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[chat-debug-events-readback-test][FAIL] $Name :: $Message"
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = 'assertion failed'
    )
    if ($Condition) { Write-Pass $Name } else { Write-Fail $Name $Message }
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name ($Text.Contains($Needle)) "expected source to contain '$Needle'"
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name (-not $Text.Contains($Needle)) "expected source not to contain '$Needle'"
}

function Get-SmokeBaseUrlResolver {
    $tokens = $null
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $SmokeScript,
        [ref]$tokens,
        [ref]$parseErrors
    )
    if (@($parseErrors).Count -ne 0) {
        throw 'smoke resolver source parse failed'
    }
    $resolverAst = $ast.Find({
        param($node)
        $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
            $node.Name -eq 'Resolve-SmokeBaseUrl'
    }, $true)
    if ($null -eq $resolverAst) {
        throw 'Resolve-SmokeBaseUrl function missing'
    }
    return [scriptblock]::Create($resolverAst.Extent.Text)
}

function Invoke-SmokeBaseUrlResolver {
    param(
        [AllowEmptyString()][string]$ExplicitBaseUrl,
        [int]$RuntimePort,
        [bool]$UseAssumeRunning,
        [AllowNull()][string]$AppPublicBaseUrl,
        [AllowNull()][string]$PublicBaseUrl
    )
    $definition = Get-SmokeBaseUrlResolver
    return & {
        param($FunctionDefinition, $Explicit, $PortValue, $Assume, $AppUrl, $PublicUrl)
        . $FunctionDefinition
        $oldAppPublicBaseUrl = $env:APP_PUBLIC_BASE_URL
        $oldPublicBaseUrl = $env:PUBLIC_BASE_URL
        try {
            [Environment]::SetEnvironmentVariable('APP_PUBLIC_BASE_URL', $AppUrl, 'Process')
            [Environment]::SetEnvironmentVariable('PUBLIC_BASE_URL', $PublicUrl, 'Process')
            Resolve-SmokeBaseUrl $Explicit $PortValue $Assume
        } finally {
            [Environment]::SetEnvironmentVariable('APP_PUBLIC_BASE_URL', $oldAppPublicBaseUrl, 'Process')
            [Environment]::SetEnvironmentVariable('PUBLIC_BASE_URL', $oldPublicBaseUrl, 'Process')
        }
    } $definition $ExplicitBaseUrl $RuntimePort $UseAssumeRunning $AppPublicBaseUrl $PublicBaseUrl
}

function Start-ReadbackLoopbackFixture {
    param(
        [Parameter(Mandatory = $true)][string]$ReadyPath,
        [Parameter(Mandatory = $true)][string]$CapturePath
    )
    return Start-Job -ScriptBlock {
        param($ReadyFile, $CaptureFile)
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        try {
            $listener.Start()
            $port = ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
            [IO.File]::WriteAllText($ReadyFile, [string]$port, [Text.UTF8Encoding]::new($false))
            $requestDeadline = (Get-Date).AddSeconds(12)
            $requestIndex = 0
            while ($requestIndex -lt 2 -and (Get-Date) -lt $requestDeadline) {
                if (-not $listener.Pending()) {
                    Start-Sleep -Milliseconds 25
                    continue
                }
                $client = $listener.AcceptTcpClient()
                $requestIndex++
                try {
                    $stream = $client.GetStream()
                    $reader = [IO.StreamReader]::new($stream, [Text.Encoding]::ASCII, $false, 4096, $true)
                    $requestLine = $reader.ReadLine()
                    $contentLength = 0
                    while ($true) {
                        $headerLine = $reader.ReadLine()
                        if ([string]::IsNullOrEmpty($headerLine)) { break }
                        if ($headerLine -match '^Content-Length:\s*(\d+)$') {
                            $contentLength = [int]$Matches[1]
                        }
                    }
                    if ($contentLength -gt 0) {
                        $bodyChars = New-Object char[] $contentLength
                        $readCount = 0
                        while ($readCount -lt $contentLength) {
                            $next = $reader.Read($bodyChars, $readCount, $contentLength - $readCount)
                            if ($next -le 0) { break }
                            $readCount += $next
                        }
                    }
                    [IO.File]::AppendAllText($CaptureFile, $requestLine + "`n", [Text.UTF8Encoding]::new($false))
                    if ($requestLine.StartsWith('POST ')) {
                        $contentType = 'text/event-stream'
                        $body = "event: debug_fx`ndata: {}`n`n"
                    } else {
                        $contentType = 'application/json'
                        $body = '[{"tsMs":9999999999999,"probe":"MODEL_GUARD","fingerprint":"chat.localLlm.operatorAction:test","data":{"stage":"local_llm_operator_action","nextAction":"monitor_local_llm_route","failureClass":"model_unavailable"}}]'
                    }
                    $bodyBytes = [Text.Encoding]::UTF8.GetBytes($body)
                    $header = "HTTP/1.1 200 OK`r`nContent-Type: $contentType`r`nContent-Length: $($bodyBytes.Length)`r`nConnection: close`r`n`r`n"
                    $headerBytes = [Text.Encoding]::ASCII.GetBytes($header)
                    $stream.Write($headerBytes, 0, $headerBytes.Length)
                    $stream.Write($bodyBytes, 0, $bodyBytes.Length)
                    $stream.Flush()
                    $reader.Dispose()
                } finally {
                    $client.Dispose()
                }
            }
        } finally {
            $listener.Stop()
        }
    } -ArgumentList $ReadyPath, $CapturePath
}

try {
    Assert-True 'chat debug events readback smoke script exists' (Test-Path -LiteralPath $SmokeScript) 'missing scripts\smoke_chat_debug_events_readback.ps1'
    if (Test-Path -LiteralPath $SmokeScript) {
        $source = Get-Content -Raw -LiteralPath $SmokeScript
        Assert-Contains 'readback smoke can start bootRun' $source 'bootRun'
        Assert-Contains 'readback smoke supports assume-running mode' $source 'AssumeRunning'
        Assert-Contains 'readback smoke supports static-only mode' $source 'StaticOnly'
        Assert-Contains 'readback smoke exposes isolated Netty port' $source '[int]$NettyPort = 18091'
        Assert-Contains 'readback smoke restores Netty port env' $source '"NETTY_PORT"'
        Assert-Contains 'readback smoke sets Netty port env' $source '$env:NETTY_PORT = [string]$NettyPort'
        Assert-Contains 'readback smoke checks Netty port conflicts' $source '@($Port, $ManagementPort, $NettyPort)'
        Assert-Contains 'readback smoke passes explicit Netty port to bootRun' $source '--args=--netty.port=$NettyPort'
        Assert-Contains 'readback smoke posts stream trigger' $source '/api/chat/stream'
        Assert-Contains 'readback smoke reads debug events endpoint' $source '/api/diagnostics/debug/events?limit='
        Assert-Contains 'readback smoke preserves endpoint HTTP metadata' $source 'ReadbackStatus'
        Assert-Contains 'readback smoke preserves endpoint content type' $source 'ReadbackContentType'
        Assert-Contains 'readback smoke summarizes event count' $source 'readbackEventCount'
        Assert-Contains 'readback smoke flattens nested JSON array wrappers' $source 'Add-ParsedEvent'
        Assert-Contains 'readback smoke uses response headers first' $source 'ResponseHeadersRead'
        Assert-Contains 'readback smoke awaits a single pending stream read' $source '$readTask.GetAwaiter().GetResult()'
        Assert-Contains 'readback smoke checks model guard probe' $source 'MODEL_GUARD'
        Assert-Contains 'readback smoke checks local operator action stage' $source 'local_llm_operator_action'
        Assert-Contains 'readback smoke checks safe next action' $source 'prefer_native_ollama_route'
        Assert-Contains 'readback smoke accepts runtime capacity action' $source 'inspect_ollama_runtime_capacity'
        Assert-Contains 'readback smoke centralizes operator action allowlist' $source 'Test-SafeOperatorNextAction'
        Assert-Contains 'readback smoke summarizes operator DebugEvent proof' $source 'operatorDebugEventPresent'
        Assert-Contains 'readback smoke scans high confidence secret hits' $source 'secretPatternHits'
        Assert-Contains 'readback smoke scans raw prompt leakage' $source 'rawPromptHits'
        Assert-Contains 'readback smoke scans raw model leakage' $source 'rawModelHits'
        Assert-Contains 'readback smoke emits evidence needed' $source 'evidence_needed'
        Assert-Contains 'readback smoke supports APP_PUBLIC_BASE_URL fallback' $source '$env:APP_PUBLIC_BASE_URL'
        Assert-Contains 'readback smoke supports PUBLIC_BASE_URL fallback' $source '$env:PUBLIC_BASE_URL'
        Assert-Contains 'readback smoke saves APP_PUBLIC_BASE_URL env' $source '"APP_PUBLIC_BASE_URL"'
        Assert-Contains 'readback smoke saves PUBLIC_BASE_URL env' $source '"PUBLIC_BASE_URL"'
        Assert-Contains 'readback smoke saves SECURITY_FORCE_HTTPS env' $source '"SECURITY_FORCE_HTTPS"'
        Assert-Contains 'readback smoke defines local HTTP rollback base' $source '$localHttpBaseUrl = "http://127.0.0.1:$Port"'
        Assert-Contains 'readback smoke forces APP_PUBLIC_BASE_URL to local HTTP' $source '$env:APP_PUBLIC_BASE_URL = $localHttpBaseUrl'
        Assert-Contains 'readback smoke forces PUBLIC_BASE_URL to local HTTP' $source '$env:PUBLIC_BASE_URL = $localHttpBaseUrl'
        Assert-Contains 'readback smoke disables forced HTTPS for local run' $source '$env:SECURITY_FORCE_HTTPS = "false"'
        Assert-Contains 'readback smoke emits redacted local HTTP rollback log' $source 'localHttpRollback=true baseScheme=http host=loopback'
        $appPublicIndex = $source.IndexOf('$env:APP_PUBLIC_BASE_URL')
        $publicIndex = $source.IndexOf('$env:PUBLIC_BASE_URL')
        $localFallbackIndex = $source.IndexOf('"http://127.0.0.1:$Port"')
        Assert-True 'readback smoke checks APP_PUBLIC_BASE_URL before local fallback' ($appPublicIndex -ge 0 -and $localFallbackIndex -gt $appPublicIndex) 'public deployment base URL must precede local fallback'
        Assert-True 'readback smoke checks PUBLIC_BASE_URL before local fallback' ($publicIndex -ge 0 -and $localFallbackIndex -gt $publicIndex) 'public deployment base URL must precede local fallback'
        Assert-Contains 'readback smoke clears local api key env path' $source '$env:LLM_API_KEY = ""'
        Assert-Contains 'readback smoke sets naver keys override' $source '"keys":""'
        Assert-NotContains 'readback smoke does not set duplicate local api key value' $source '$env:LLM_API_KEY = "ollama"'
        Assert-NotContains 'readback smoke does not dump full environment' $source 'Get-ChildItem Env:'
        Assert-NotContains 'readback smoke does not print authorization headers' $source 'Authorization='
        Assert-NotContains 'readback smoke does not put raw message in query string' $source '?message='

        $tempOut = Join-Path ([IO.Path]::GetTempPath()) ('awx-chat-debug-events-readback-test-' + [guid]::NewGuid().ToString('N'))
        $lines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
            -StaticOnly `
            -OutputDir $tempOut 2>&1 |
            ForEach-Object { $_.ToString() }
        $invokeOutput = $lines -join "`n"
        Assert-Contains 'static readback smoke exits cleanly' $invokeOutput '[AWX][chat-debug-events-readback] staticOnly=true'
        Assert-Contains 'static readback smoke reports script path' $invokeOutput 'script='
        Assert-NotContains 'static readback smoke does not print raw prompt' $invokeOutput 'AWX debug fx local route probe'
        Assert-NotContains 'static readback smoke does not print raw model' $invokeOutput 'qwen3:8b'
        if (Test-Path -LiteralPath $tempOut) {
            Remove-Item -LiteralPath $tempOut -Recurse -Force -ErrorAction SilentlyContinue
        }

        $localWithoutAssume = Invoke-SmokeBaseUrlResolver `
            -ExplicitBaseUrl '' `
            -RuntimePort 18088 `
            -UseAssumeRunning $false `
            -AppPublicBaseUrl 'http://127.0.0.1:19991/' `
            -PublicBaseUrl 'http://127.0.0.1:19992/'
        Assert-True 'resolver ignores inherited URLs without assume-running' `
            ($localWithoutAssume -eq 'http://127.0.0.1:18088') `
            "expected local loopback base, got '$localWithoutAssume'"

        $explicitWithAssume = Invoke-SmokeBaseUrlResolver `
            -ExplicitBaseUrl 'http://127.0.0.1:19193/' `
            -RuntimePort 18088 `
            -UseAssumeRunning $true `
            -AppPublicBaseUrl 'http://127.0.0.1:19991/' `
            -PublicBaseUrl 'http://127.0.0.1:19992/'
        Assert-True 'resolver gives explicit BaseUrl precedence in assume-running mode' `
            ($explicitWithAssume -eq 'http://127.0.0.1:19193') `
            "expected explicit loopback base, got '$explicitWithAssume'"

        $localStartMismatchMessage = ''
        try {
            Invoke-SmokeBaseUrlResolver `
                -ExplicitBaseUrl 'http://127.0.0.1:19999' `
                -RuntimePort 18088 `
                -UseAssumeRunning $false `
                -AppPublicBaseUrl $null `
                -PublicBaseUrl $null | Out-Null
        } catch {
            $localStartMismatchMessage = $_.Exception.Message
        }
        Assert-Contains 'resolver rejects explicit BaseUrl that disagrees with local-start port' `
            $localStartMismatchMessage `
            'invalid-base-url reason=local-start-mismatch'

        $invalidSchemeMessage = ''
        try {
            Invoke-SmokeBaseUrlResolver `
                -ExplicitBaseUrl 'https://127.0.0.1:18088' `
                -RuntimePort 18088 `
                -UseAssumeRunning $true `
                -AppPublicBaseUrl $null `
                -PublicBaseUrl $null | Out-Null
        } catch {
            $invalidSchemeMessage = $_.Exception.Message
        }
        Assert-Contains 'resolver rejects non-http effective URL' $invalidSchemeMessage 'invalid-base-url reason=scheme'

        $nonLoopbackMessage = ''
        try {
            Invoke-SmokeBaseUrlResolver `
                -ExplicitBaseUrl 'http://example.invalid:18088' `
                -RuntimePort 18088 `
                -UseAssumeRunning $true `
                -AppPublicBaseUrl $null `
                -PublicBaseUrl $null | Out-Null
        } catch {
            $nonLoopbackMessage = $_.Exception.Message
        }
        Assert-Contains 'resolver rejects non-loopback effective URL' $nonLoopbackMessage 'invalid-base-url reason=non-loopback'

        $probeRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-chat-debug-events-readback-base-url-test-' + [guid]::NewGuid().ToString('N'))
        $readyPath = Join-Path $probeRoot 'ready.txt'
        $capturePath = Join-Path $probeRoot 'requests.txt'
        $probeOutput = Join-Path $probeRoot 'output'
        $oldAppPublicBaseUrl = $env:APP_PUBLIC_BASE_URL
        $oldPublicBaseUrl = $env:PUBLIC_BASE_URL
        $oldLlmChatModel = $env:LLM_CHAT_MODEL
        $serverJob = $null
        try {
            New-Item -ItemType Directory -Force -Path $probeRoot | Out-Null
            $serverJob = Start-ReadbackLoopbackFixture -ReadyPath $readyPath -CapturePath $capturePath
            $readyDeadline = (Get-Date).AddSeconds(5)
            while (-not (Test-Path -LiteralPath $readyPath) -and (Get-Date) -lt $readyDeadline) {
                Start-Sleep -Milliseconds 50
            }
            if (-not (Test-Path -LiteralPath $readyPath)) {
                throw 'loopback fixture did not become ready'
            }
            $fixturePort = [int](Get-Content -LiteralPath $readyPath -Raw)
            $env:APP_PUBLIC_BASE_URL = "http://127.0.0.1:$fixturePort/"
            $env:PUBLIC_BASE_URL = 'http://127.0.0.1:19992/'
            [Environment]::SetEnvironmentVariable('LLM_CHAT_MODEL', $null, 'Process')
            $probeLines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
                -AssumeRunning `
                -TimeoutSec 5 `
                -OutputDir $probeOutput 2>&1 |
                ForEach-Object { $_.ToString() }
            $probeExit = $LASTEXITCODE
            [void](Wait-Job -Job $serverJob -Timeout 5)
            $capturedRequests = if (Test-Path -LiteralPath $capturePath) {
                Get-Content -LiteralPath $capturePath -Raw
            } else {
                ''
            }
            Assert-True 'assume-running readback reaches both loopback HTTP boundaries' `
                ($probeExit -eq 0) `
                "exit=$probeExit output=$($probeLines -join ' | ')"
            Assert-Contains 'assume-running readback preserves inherited stream base URL' `
                $capturedRequests `
                'POST /api/chat/stream HTTP/1.1'
            Assert-Contains 'assume-running readback preserves inherited diagnostics base URL' `
                $capturedRequests `
                'GET /api/diagnostics/debug/events?limit=160 HTTP/1.1'
        } finally {
            [Environment]::SetEnvironmentVariable('APP_PUBLIC_BASE_URL', $oldAppPublicBaseUrl, 'Process')
            [Environment]::SetEnvironmentVariable('PUBLIC_BASE_URL', $oldPublicBaseUrl, 'Process')
            [Environment]::SetEnvironmentVariable('LLM_CHAT_MODEL', $oldLlmChatModel, 'Process')
            if ($null -ne $serverJob) {
                [void](Wait-Job -Job $serverJob -Timeout 15 -ErrorAction SilentlyContinue)
                Remove-Job -Job $serverJob -Force -ErrorAction SilentlyContinue
            }
            if (Test-Path -LiteralPath $probeRoot) {
                Remove-Item -LiteralPath $probeRoot -Recurse -Force -ErrorAction SilentlyContinue
            }
        }

    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
}

if ($Failures -gt 0) {
    Write-Host "[chat-debug-events-readback-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[chat-debug-events-readback-test][SUMMARY] failed=0'
