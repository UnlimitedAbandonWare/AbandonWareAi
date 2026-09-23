param(
    [int]$Port = 18088,
    [int]$ManagementPort = 18089,
    [int]$NettyPort = 18091,
    [int]$StartupTimeoutSeconds = 160,
    [int]$TimeoutSec = 90,
    [string]$BaseUrl = "",
    [string]$OutputDir = "verification\chat-debug-events-readback",
    [string]$Message = "AWX debug fx local route probe",
    [string]$Model = $env:LLM_CHAT_MODEL,
    [switch]$AssumeRunning,
    [switch]$StaticOnly
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
$SmokeName = "chat-debug-events-readback"
$RequestId = "chat-debug-events-" + [guid]::NewGuid().ToString("N")
$SessionKey = "events-smoke-" + [guid]::NewGuid().ToString("N")
$StartedAtMs = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$ResolvedOutputDir = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir
} else {
    Join-Path $Root $OutputDir
}
$ReportPath = Join-Path $ResolvedOutputDir "$SmokeName.json"
$SummaryPath = Join-Path $ResolvedOutputDir "$SmokeName.summary.txt"

function Redact-Text([string]$Text) {
    if ($null -eq $Text) { return "" }
    $out = $Text
    foreach ($s in @($RequestId, $SessionKey, $Message, $Model)) {
        if (-not [string]::IsNullOrWhiteSpace($s)) {
            $out = $out -replace [regex]::Escape($s), "<redacted>"
        }
    }
    $out = $out -replace 'sk-[A-Za-z0-9_-]{20,}', '<redacted-openai-key>'
    $out = $out -replace 'AIza[0-9A-Za-z_-]{20,}', '<redacted-google-key>'
    $out = $out -replace 'gsk_[A-Za-z0-9]{20,}', '<redacted-groq-key>'
    $out = $out -replace 'pcsk_[A-Za-z0-9_-]{20,}', '<redacted-pinecone-key>'
    $out = $out -replace 'sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}', '<redacted-supabase-key>'
    $out = $out -replace 'sbp_[A-Za-z0-9_-]{10,}', '<redacted-supabase-token>'
    return $out
}

function Stop-ProcessTree {
    param([int]$ProcessId)
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-ProcessTree -ProcessId ([int]$child.ProcessId)
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Wait-ForApp([object]$App, [string]$Url) {
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -ne $App -and $null -ne $App.Process -and $App.Process.HasExited) {
            $out = if (Test-Path $App.OutLog) { Redact-Text ((Get-Content -LiteralPath $App.OutLog -Tail 120) -join [Environment]::NewLine) } else { "" }
            $err = if (Test-Path $App.ErrLog) { Redact-Text ((Get-Content -LiteralPath $App.ErrLog -Tail 120) -join [Environment]::NewLine) } else { "" }
            throw "[AWX][chat-debug-events-readback] bootRun exited before /chat-ui was reachable`n$out`n$err"
        }
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "[AWX][chat-debug-events-readback] evidence_needed: startup timeout waiting for $Url"
}

function Get-JsonProp($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Test-SafeOperatorNextAction([string]$NextAction) {
    if ([string]::IsNullOrWhiteSpace($NextAction)) {
        return $false
    }
    return @(
        "prefer_native_ollama_route",
        "inspect_ollama_runtime_capacity",
        "inspect_model_route_or_start_local_llm",
        "respect_ollama_retry_after",
        "inspect_ollama_request_contract",
        "inspect_ollama_http_failure",
        "monitor_local_llm_route"
    ) -contains $NextAction
}

function Test-OperatorDebugEvent($Event) {
    if ($null -eq $Event) { return $false }
    $probe = [string](Get-JsonProp $Event "probe")
    $fingerprint = [string](Get-JsonProp $Event "fingerprint")
    $data = Get-JsonProp $Event "data"
    $stage = [string](Get-JsonProp $data "stage")
    $nextAction = [string](Get-JsonProp $data "nextAction")
    $failureClass = [string](Get-JsonProp $data "failureClass")
    $tsMs = Get-JsonProp $Event "tsMs"
    $freshEnough = $true
    try {
        $freshEnough = ([int64]$tsMs) -ge ($StartedAtMs - 2000)
    } catch {
        $freshEnough = $true
    }
    return $freshEnough -and
        $probe -eq "MODEL_GUARD" -and
        $fingerprint.Contains("chat.localLlm.operatorAction") -and
        $stage -eq "local_llm_operator_action" -and
        (Test-SafeOperatorNextAction $nextAction) -and
        -not [string]::IsNullOrWhiteSpace($failureClass)
}

function Add-ParsedEvent([System.Collections.Generic.List[object]]$Target, $Item) {
    if ($null -eq $Item) {
        return
    }
    if ($Item -is [System.Array]) {
        foreach ($child in $Item) {
            Add-ParsedEvent $Target $child
        }
        return
    }
    $Target.Add($Item)
}

function Find-OperatorDebugEvent([string]$Base, [int]$Limit, [int]$WaitSeconds) {
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $lastEvents = @()
    $lastStatus = 0
    $lastContentType = ""
    $lastContentLength = 0
    while ((Get-Date) -lt $deadline) {
        try {
            $readbackResponse = Invoke-WebRequest -Uri "$Base/api/diagnostics/debug/events?limit=$Limit" -Method Get -UseBasicParsing -TimeoutSec 5
            $lastStatus = [int]$readbackResponse.StatusCode
            $lastContentType = [string]$readbackResponse.Headers["Content-Type"]
            $content = [string]$readbackResponse.Content
            $lastContentLength = $content.Length
            $events = @()
            if (-not [string]::IsNullOrWhiteSpace($content)) {
                try {
                    $parsed = $content | ConvertFrom-Json
                    $eventList = [System.Collections.Generic.List[object]]::new()
                    Add-ParsedEvent $eventList $parsed
                    $events = @($eventList)
                } catch {
                    Write-Host "[AWX][chat-debug-events-readback] evidence_needed: debug events json parse failed status=$lastStatus contentType=$lastContentType contentLength=$lastContentLength errorType=$($_.Exception.GetType().Name)"
                    $events = @()
                }
            }
            $lastEvents = $events
            foreach ($event in $events) {
                if (Test-OperatorDebugEvent $event) {
                    return [pscustomobject]@{
                        Found = $true
                        Event = $event
                        Events = $events
                        ReadbackStatus = $lastStatus
                        ReadbackContentType = $lastContentType
                        ReadbackContentLength = $lastContentLength
                    }
                }
            }
        } catch {
            Write-Host "[AWX][chat-debug-events-readback] evidence_needed: debug events read failed errorType=$($_.Exception.GetType().Name)"
        }
        Start-Sleep -Milliseconds 700
    }
    return [pscustomobject]@{
        Found = $false
        Event = $null
        Events = $lastEvents
        ReadbackStatus = $lastStatus
        ReadbackContentType = $lastContentType
        ReadbackContentLength = $lastContentLength
    }
}

function Resolve-SmokeBaseUrl([string]$ExplicitBaseUrl, [int]$RuntimePort, [bool]$UseAssumeRunning) {
    $candidate = if (-not [string]::IsNullOrWhiteSpace($ExplicitBaseUrl)) {
        $ExplicitBaseUrl.TrimEnd("/")
    } elseif ($UseAssumeRunning -and -not [string]::IsNullOrWhiteSpace($env:APP_PUBLIC_BASE_URL)) {
        $env:APP_PUBLIC_BASE_URL.TrimEnd("/")
    } elseif ($UseAssumeRunning -and -not [string]::IsNullOrWhiteSpace($env:PUBLIC_BASE_URL)) {
        $env:PUBLIC_BASE_URL.TrimEnd("/")
    } else {
        "http://127.0.0.1:$RuntimePort"
    }

    $uri = $null
    if (-not [Uri]::TryCreate($candidate, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ine 'http') {
        throw '[AWX][chat-debug-events-readback] evidence_needed: invalid-base-url reason=scheme'
    }
    if (-not $uri.IsLoopback) {
        throw '[AWX][chat-debug-events-readback] evidence_needed: invalid-base-url reason=non-loopback'
    }
    $localBaseUrl = "http://127.0.0.1:$RuntimePort"
    $normalizedEffectiveUrl = $uri.AbsoluteUri.TrimEnd('/')
    $normalizedLocalUrl = ([Uri]$localBaseUrl).AbsoluteUri.TrimEnd('/')
    if (-not $UseAssumeRunning -and $normalizedEffectiveUrl -ine $normalizedLocalUrl) {
        throw '[AWX][chat-debug-events-readback] evidence_needed: invalid-base-url reason=local-start-mismatch'
    }
    return $candidate
}

function Invoke-StreamTrigger([string]$Base) {
    $bodyMap = [ordered]@{
        message = $Message
        useRag = $false
        useWebSearch = $false
        searchMode = "OFF"
        maxTokens = 96
        temperature = 0.1
    }
    if (-not [string]::IsNullOrWhiteSpace($Model)) {
        $bodyMap.model = $Model
    }
    $body = $bodyMap | ConvertTo-Json -Depth 8
    $httpClient = [System.Net.Http.HttpClient]::new()
    $httpClient.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $httpRequest = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        "$Base/api/chat/stream"
    )
    [void]$httpRequest.Headers.TryAddWithoutValidation("X-Request-Id", $RequestId)
    [void]$httpRequest.Headers.TryAddWithoutValidation("X-Session-Id", $SessionKey)
    $httpRequest.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, "application/json")
    $response = $null
    $reader = $null
    $snippet = [System.Text.StringBuilder]::new()
    try {
        $response = $httpClient.SendAsync(
            $httpRequest,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()
        if (-not $response.IsSuccessStatusCode) {
            throw "[AWX][chat-debug-events-readback] stream status=$([int]$response.StatusCode)"
        }
        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
        $lineDeadline = (Get-Date).AddSeconds([Math]::Min(12, [Math]::Max(4, $TimeoutSec)))
        while ((Get-Date) -lt $lineDeadline) {
            $readTask = $reader.ReadLineAsync()
            while (-not $readTask.Wait(800)) {
                if ((Get-Date) -ge $lineDeadline) {
                    break
                }
            }
            if (-not $readTask.IsCompleted) {
                break
            }
            $line = $readTask.GetAwaiter().GetResult()
            if ($null -eq $line) { break }
            if ($snippet.Length -lt 4096) {
                [void]$snippet.AppendLine($line)
            }
            if ($line -like "event: debug_fx*" -or $line -like "event: transformer*") {
                break
            }
        }
        return [pscustomobject]@{
            Status = [int]$response.StatusCode
            Snippet = $snippet.ToString()
        }
    } finally {
        if ($null -ne $reader) { $reader.Dispose() }
        if ($null -ne $response) { $response.Dispose() }
        $httpRequest.Dispose()
        $httpClient.Dispose()
    }
}

if ($StaticOnly) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    Write-Host "[AWX][chat-debug-events-readback] staticOnly=true script=$PSCommandPath"
    exit 0
}

if (-not (Test-Path $Gradle) -and -not $AssumeRunning) {
    throw "[AWX][chat-debug-events-readback] evidence_needed: gradlew.bat missing under $Root"
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null

$envNames = @(
    "AWX_AGENT_HOST",
    "AWX_SPLIT_BUILD_OUTPUTS",
    "AWX_BUILD_HOST_ID",
    "GRADLE_USER_HOME",
    "AWX_PROJECT_CACHE_DIR",
    "SPRING_APPLICATION_JSON",
    "SPRING_PROFILES_ACTIVE",
    "SERVER_PORT",
    "MANAGEMENT_SERVER_PORT",
    "NETTY_PORT",
    "SERVER_SSL_ENABLED",
    "APP_PUBLIC_BASE_URL",
    "PUBLIC_BASE_URL",
    "SECURITY_FORCE_HTTPS",
    "LOCAL_LLM_AUTOSTART",
    "LOCAL_LLM_WARMUP_ENABLED",
    "DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED",
    "LLM_BASE_URL",
    "LLM_CHAT_MODEL",
    "LLM_API_KEY",
    "NAVER_KEYS",
    "NAVER_CLIENT_ID",
    "NAVER_CLIENT_SECRET",
    "BRAVE_API_KEY",
    "SERPAPI_API_KEY",
    "TAVILY_API_KEY",
    "OPENAI_API_KEY"
)
$previousEnv = @{}
foreach ($name in $envNames) {
    $previousEnv[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

$app = $null
try {
    $ProjectCacheDir = Join-Path $env:LOCALAPPDATA "awx-gradle-project-cache\desktop-chat-debug-events-readback"
    $env:AWX_AGENT_HOST = "desktop"
    $env:AWX_SPLIT_BUILD_OUTPUTS = "1"
    $env:AWX_BUILD_HOST_ID = "desktop-chat-debug-events-readback"
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE ".gradle-awx-desktop"
    $env:AWX_PROJECT_CACHE_DIR = $ProjectCacheDir
    New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCacheDir | Out-Null

    if (-not $AssumeRunning) {
        $env:SPRING_APPLICATION_JSON = '{"naver":{"keys":"","client-id":"","client-secret":""}}'
        $env:SPRING_PROFILES_ACTIVE = "local"
        $env:SERVER_PORT = [string]$Port
        $env:MANAGEMENT_SERVER_PORT = [string]$ManagementPort
        $env:NETTY_PORT = [string]$NettyPort
        $env:SERVER_SSL_ENABLED = "false"
        $localHttpBaseUrl = "http://127.0.0.1:$Port"
        $env:APP_PUBLIC_BASE_URL = $localHttpBaseUrl
        $env:PUBLIC_BASE_URL = $localHttpBaseUrl
        $env:SECURITY_FORCE_HTTPS = "false"
        Write-Host "[AWX][chat-debug-events-readback] localHttpRollback=true baseScheme=http host=loopback port=$Port serverSsl=false forceHttps=false"
    }
    $env:LOCAL_LLM_AUTOSTART = "false"
    $env:LOCAL_LLM_WARMUP_ENABLED = "false"
    $env:DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED = "false"
    $env:LLM_BASE_URL = "http://127.0.0.1:11434/v1"
    if (-not [string]::IsNullOrWhiteSpace($Model)) {
        $env:LLM_CHAT_MODEL = $Model
    }
    $env:LLM_API_KEY = ""
    $env:NAVER_KEYS = ""
    $env:NAVER_CLIENT_ID = ""
    $env:NAVER_CLIENT_SECRET = ""
    $env:BRAVE_API_KEY = ""
    $env:SERPAPI_API_KEY = ""
    $env:TAVILY_API_KEY = ""
    $env:OPENAI_API_KEY = ""

    $effectiveBaseUrl = Resolve-SmokeBaseUrl $BaseUrl $Port ([bool]$AssumeRunning)

    if (-not $AssumeRunning) {
        $launchPorts = @($Port, $ManagementPort, $NettyPort) | Where-Object { $_ -gt 0 }
        if ((@($launchPorts | Sort-Object -Unique)).Count -ne @($launchPorts).Count) {
            throw "[AWX][chat-debug-events-readback] port-conflict reason=duplicate-launch-port ports=$($launchPorts -join ',')"
        }
        foreach ($p in @($Port, $ManagementPort, $NettyPort)) {
            $inUse = netstat -ano | Select-String ":$p "
            if ($inUse) {
                throw "[AWX][chat-debug-events-readback] port-conflict port=$p"
            }
        }
        $outLog = Join-Path (Split-Path -Parent $ReportPath) "$SmokeName.out.log"
        $errLog = Join-Path (Split-Path -Parent $ReportPath) "$SmokeName.err.log"
        $gradleArgs = @("bootRun", "--no-daemon", "-x", "test", "--project-cache-dir", $ProjectCacheDir, "--args=--netty.port=$NettyPort")
        $proc = Start-Process -FilePath $Gradle `
            -ArgumentList $gradleArgs `
            -WorkingDirectory $Root `
            -RedirectStandardOutput $outLog `
            -RedirectStandardError $errLog `
            -WindowStyle Hidden `
            -PassThru
        $app = @{ Process = $proc; OutLog = $outLog; ErrLog = $errLog }
        Wait-ForApp $app "$effectiveBaseUrl/chat-ui"
    }

    $trigger = Invoke-StreamTrigger $effectiveBaseUrl
    $readback = Find-OperatorDebugEvent $effectiveBaseUrl 160 ([Math]::Min(35, [Math]::Max(8, $TimeoutSec)))
    $operatorEvent = $readback.Event
    $operatorData = Get-JsonProp $operatorEvent "data"
    $failureClass = [string](Get-JsonProp $operatorData "failureClass")
    $triggerReason = [string](Get-JsonProp $operatorData "triggerReason")
    $nextAction = [string](Get-JsonProp $operatorData "nextAction")
    $eventProbe = [string](Get-JsonProp $operatorEvent "probe")
    $eventFingerprint = [string](Get-JsonProp $operatorEvent "fingerprint")
    $eventStage = [string](Get-JsonProp $operatorData "stage")
    $readbackEventCount = @($readback.Events).Count

    $eventJson = if ($null -ne $operatorEvent) { $operatorEvent | ConvertTo-Json -Depth 12 -Compress } else { "" }
    $raw = [string]::Join("`n", @($trigger.Snippet, $eventJson))
    $secretPatternHits = ([regex]::Matches($raw, 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}')).Count
    $rawPromptHits = if ($raw.Contains($Message)) { 1 } else { 0 }
    $rawModelHits = if ((-not [string]::IsNullOrWhiteSpace($Model)) -and $raw.Contains($Model)) { 1 } else { 0 }

    if (-not $readback.Found) {
        $probes = @($readback.Events | ForEach-Object { [string](Get-JsonProp $_ "probe") } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
        throw "[AWX][chat-debug-events-readback] evidence_needed: operator DebugEvent missing readbackStatus=$($readback.ReadbackStatus) readbackContentType=$($readback.ReadbackContentType) readbackContentLength=$($readback.ReadbackContentLength) readbackEventCount=$readbackEventCount probes=$($probes -join ',')"
    }
    if ($secretPatternHits -gt 0 -or $rawPromptHits -gt 0 -or $rawModelHits -gt 0) {
        throw "[AWX][chat-debug-events-readback] secret-leak-risk secretPatternHits=$secretPatternHits rawPromptHits=$rawPromptHits rawModelHits=$rawModelHits"
    }

    $summary = [ordered]@{
        ok = $true
        streamStatus = [int]$trigger.Status
        operatorDebugEventPresent = [bool]$readback.Found
        readbackStatus = [int]$readback.ReadbackStatus
        readbackContentType = [string]$readback.ReadbackContentType
        readbackContentLength = [int]$readback.ReadbackContentLength
        readbackEventCount = [int]$readbackEventCount
        probe = $eventProbe
        fingerprint = $eventFingerprint
        stage = $eventStage
        failureClass = $failureClass
        triggerReason = $triggerReason
        nextAction = $nextAction
        secretPatternHits = $secretPatternHits
        rawPromptHits = $rawPromptHits
        rawModelHits = $rawModelHits
        report = $ReportPath
    }
    $artifact = [ordered]@{
        summary = $summary
    }
    $artifact | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    ($summary.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) |
        Set-Content -LiteralPath $SummaryPath -Encoding UTF8

    Write-Host "[AWX][chat-debug-events-readback] streamStatus=$($summary.streamStatus) operatorDebugEventPresent=$($summary.operatorDebugEventPresent) probe=$eventProbe stage=$eventStage triggerReason=$triggerReason failureClass=$failureClass nextAction=$nextAction secretPatternHits=$secretPatternHits rawPromptHits=$rawPromptHits rawModelHits=$rawModelHits report=$ReportPath"
} finally {
    if ($null -ne $app -and $null -ne $app.Process) {
        Stop-ProcessTree -ProcessId ([int]$app.Process.Id)
    }
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], "Process")
    }
}
