param(
    [int]$Port = 18088,
    [int]$ManagementPort = 18089,
    [int]$StartupTimeoutSeconds = 160,
    [int]$TimeoutSec = 90,
    [string]$BaseUrl = "",
    [string]$OutputDir = "verification\chat-debug-fx-sse",
    [string]$Message = "AWX debug fx local route probe",
    [string]$Model = $env:LLM_CHAT_MODEL,
    [switch]$AssumeRunning,
    [switch]$RequireQueryRewrite,
    [switch]$RequireTraceMemory,
    [switch]$StaticOnly
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
$SmokeName = "chat-debug-fx-sse"
$RequestId = "chat-debug-fx-" + [guid]::NewGuid().ToString("N")
$SessionKey = "sse-smoke-" + [guid]::NewGuid().ToString("N")
$QueryRewriteDiagnosticMessage = "AWX query rewrite RAG diagnostic: compare OpenAI and Supabase MCP security recommendations using official source evidence."
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
    foreach ($s in @($RequestId, $SessionKey, $Message, $QueryRewriteDiagnosticMessage, $effectiveMessage, $Model)) {
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
            throw "[AWX][chat-debug-fx-sse] bootRun exited before /chat-ui was reachable`n$out`n$err"
        }
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "[AWX][chat-debug-fx-sse] evidence_needed: startup timeout waiting for $Url"
}

function ConvertFrom-Sse([string]$Raw) {
    $events = New-Object System.Collections.Generic.List[object]
    if ([string]::IsNullOrWhiteSpace($Raw)) {
        return $events
    }
    $normalized = $Raw -replace "`r`n", "`n"
    foreach ($block in ($normalized -split "`n`n")) {
        if ([string]::IsNullOrWhiteSpace($block)) { continue }
        $eventName = ""
        $dataLines = New-Object System.Collections.Generic.List[string]
        foreach ($line in ($block -split "`n")) {
            if ($line.StartsWith("event:")) {
                $eventName = $line.Substring(6).Trim()
            } elseif ($line.StartsWith("data:")) {
                $dataLines.Add($line.Substring(5).TrimStart())
            }
        }
        $data = [string]::Join("`n", $dataLines)
        $events.Add([pscustomobject]@{
            event = $eventName
            data = $data
        })
    }
    return $events
}

function Get-JsonProp($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-Label($Labels, [string]$Name) {
    $v = Get-JsonProp $Labels $Name
    if ($null -eq $v) { return "" }
    return [string]$v
}

function Get-TransformerBlock($Payload, [string]$Id) {
    $blocks = Get-JsonProp $Payload "transformerBlocks"
    if ($null -eq $blocks) {
        $blocks = Get-JsonProp $Payload "blocks"
    }
    if ($null -eq $blocks) { return $null }
    foreach ($block in @($blocks)) {
        $blockId = Get-JsonProp $block "id"
        if ([string]$blockId -eq $Id) {
            return $block
        }
    }
    return $null
}

function Test-QueryRewriteBlock($Block) {
    if ($null -eq $Block) { return $false }
    $status = [string](Get-JsonProp $Block "status")
    $reason = [string](Get-JsonProp $Block "reason")
    if ($status -ne "done" -or [string]::IsNullOrWhiteSpace($reason)) {
        return $false
    }
    if ($reason.Contains("rawUserQuery") -or
        $reason.Contains("rawQuery") -or
        $reason.Contains("providerQueryRaw")) {
        return $false
    }
    return $reason.Contains("super-tokens:") -or
        $reason.Contains("sub-models:") -or
        $reason.Contains("stream_transformer") -or
        $reason.Contains("models:") -or
        $reason.Contains("supers:") -or
        $reason.Contains("branches:") -or
        $reason.Contains("axes:") -or
        $reason.Contains("lanes:") -or
        $reason.Contains("temp:") -or
        $reason.Contains("profile:")
}

function Test-QueryRewriteHeartbeatProof($QueryRewrite) {
    if ($null -eq $QueryRewrite) { return $false }
    $status = [string](Get-JsonProp $QueryRewrite "status")
    $reason = [string](Get-JsonProp $QueryRewrite "reason")
    $verificationLaneCount = [int](Get-JsonProp $QueryRewrite "verificationLaneCount")
    $explorationLaneCount = [int](Get-JsonProp $QueryRewrite "explorationLaneCount")
    $laneSummary = [string](Get-JsonProp $QueryRewrite "laneSummary")
    $temperatureProfile = [string](Get-JsonProp $QueryRewrite "temperatureProfile")
    $latestTraceEntryCount = [int](Get-JsonProp $QueryRewrite "latestTraceEntryCount")
    $hints = @(Get-JsonProp $QueryRewrite "variantLaneTemperatureHints")
    if ($status -ne "OK" -or
        $reason -ne "latest_query_rewrite_trace" -or
        $verificationLaneCount -le 0 -or
        $explorationLaneCount -le 0 -or
        $latestTraceEntryCount -le 0 -or
        [string]::IsNullOrWhiteSpace($laneSummary) -or
        [string]::IsNullOrWhiteSpace($temperatureProfile) -or
        $hints.Count -le 0) {
        return $false
    }
    if ($laneSummary.Contains("rawUserQuery") -or
        $laneSummary.Contains("rawQuery") -or
        $laneSummary.Contains("providerQueryRaw")) {
        return $false
    }
    foreach ($hint in $hints) {
        $text = [string]$hint
        if ($text -notmatch '^\d+:[A-Za-z0-9_.:-]+@[0-9]+(?:\.[0-9]+)?#[A-Fa-f0-9]{12}$') {
            return $false
        }
    }
    return $true
}

function Get-QueryRewriteHeartbeatProof([string]$BaseUrl, [int]$RequestTimeoutSec) {
    $result = [ordered]@{
        present = $false
        satisfied = $false
        httpStatus = 0
        status = ""
        reason = ""
        source = "heartbeat"
        variantLaneTemperatureHints = @()
        rawForScan = ""
        failReason = ""
    }
    try {
        $response = Invoke-WebRequest `
            -Uri "$BaseUrl/api/chat/ui-heartbeat" `
            -UseBasicParsing `
            -TimeoutSec $RequestTimeoutSec
        $result.httpStatus = [int]$response.StatusCode
        $content = [string]$response.Content
        $result.rawForScan = $content
        $json = $content | ConvertFrom-Json
        $candidate = Get-JsonProp $json "queryRewrite"
        if ($null -ne $candidate) {
            $result.present = $true
            $result.status = [string](Get-JsonProp $candidate "status")
            $result.reason = [string](Get-JsonProp $candidate "reason")
            $result.variantLaneTemperatureHints = @(Get-JsonProp $candidate "variantLaneTemperatureHints")
            $result.satisfied = Test-QueryRewriteHeartbeatProof $candidate
        }
    } catch {
        $result.failReason = $_.Exception.GetType().Name
    }
    return [pscustomobject]$result
}

function Test-TraceMemoryLabels($Labels) {
    if ($null -eq $Labels) { return $false }
    $routeDecision = Get-Label $Labels "traceMemoryRouteDecision"
    $virtualCheckpointKey = Get-Label $Labels "traceMemoryVirtualCheckpointKey"
    $virtualCheckpointStage = Get-Label $Labels "traceMemoryVirtualCheckpointStage"
    $virtualCheckpointPhase = Get-Label $Labels "traceMemoryVirtualCheckpointPhase"
    $cfvmOffered = Get-Label $Labels "traceMemoryCfvmOffered"
    $cfvmPatternId = Get-Label $Labels "traceMemoryCfvmPatternId"
    return -not [string]::IsNullOrWhiteSpace($routeDecision) -and
        -not [string]::IsNullOrWhiteSpace($virtualCheckpointKey) -and
        -not [string]::IsNullOrWhiteSpace($virtualCheckpointStage) -and
        -not [string]::IsNullOrWhiteSpace($virtualCheckpointPhase) -and
        (
            -not [string]::IsNullOrWhiteSpace($cfvmOffered) -or
            -not [string]::IsNullOrWhiteSpace($cfvmPatternId)
        )
}

function Test-DebugFxOperatorActionLabels($Labels) {
    if ($null -eq $Labels) { return $false }
    $triggerReason = Get-Label $Labels "localLlmTriggerReason"
    $nextAction = Get-Label $Labels "localLlmNextAction"
    return -not [string]::IsNullOrWhiteSpace($triggerReason) -or
        -not [string]::IsNullOrWhiteSpace($nextAction)
}

function Merge-DebugFxLabels($PrimaryLabels, $SecondaryLabels) {
    $merged = [ordered]@{}
    foreach ($source in @($PrimaryLabels, $SecondaryLabels)) {
        if ($null -eq $source) { continue }
        foreach ($prop in $source.PSObject.Properties) {
            if ($null -eq $prop -or [string]::IsNullOrWhiteSpace([string]$prop.Name)) { continue }
            $value = [string]$prop.Value
            if ([string]::IsNullOrWhiteSpace($value)) { continue }
            if (-not $merged.Contains($prop.Name) -or [string]::IsNullOrWhiteSpace([string]$merged[$prop.Name])) {
                $merged[$prop.Name] = $value
            }
        }
    }
    return [pscustomobject]$merged
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
    if (-not $UseAssumeRunning) {
        $uri = $null
        if (-not [Uri]::TryCreate($candidate, [UriKind]::Absolute, [ref]$uri) -or
            $uri.Scheme -ine 'http' -or -not $uri.IsLoopback -or $uri.Port -ne $RuntimePort) {
            throw '[AWX][chat-debug-fx-sse] evidence_needed: invalid-base-url reason=local-start-mismatch'
        }
    }
    return $candidate
}

if ($StaticOnly) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    Write-Host "[AWX][chat-debug-fx-sse] staticOnly=true script=$PSCommandPath"
    exit 0
}

if (-not (Test-Path $Gradle) -and -not $AssumeRunning) {
    throw "[AWX][chat-debug-fx-sse] evidence_needed: gradlew.bat missing under $Root"
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
    $SmokeBuildId = "desktop-chat-debug-fx-sse-$Port"
    $ProjectCacheDir = Join-Path $env:LOCALAPPDATA "awx-gradle-project-cache\$SmokeBuildId"
    $NettyPort = $Port + 2
    $env:AWX_AGENT_HOST = "desktop"
    $env:AWX_SPLIT_BUILD_OUTPUTS = "1"
    $env:AWX_BUILD_HOST_ID = $SmokeBuildId
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE ".gradle-awx-desktop"
    $env:AWX_PROJECT_CACHE_DIR = $ProjectCacheDir
    New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCacheDir | Out-Null

    $env:SPRING_APPLICATION_JSON = '{"naver":{"keys":"","client-id":"","client-secret":""}}'
    $env:SPRING_PROFILES_ACTIVE = "local"
    $env:SERVER_PORT = [string]$Port
    $env:MANAGEMENT_SERVER_PORT = [string]$ManagementPort
    $env:NETTY_PORT = [string]$NettyPort
    $env:SERVER_SSL_ENABLED = "false"
    $localHttpBaseUrl = "http://127.0.0.1:$Port"
    if (-not $AssumeRunning) {
        $env:APP_PUBLIC_BASE_URL = $localHttpBaseUrl
        $env:PUBLIC_BASE_URL = $localHttpBaseUrl
    }
    $env:SECURITY_FORCE_HTTPS = "false"
    if (-not $AssumeRunning) {
        Write-Host "[AWX][chat-debug-fx-sse] localHttpRollback=true baseScheme=http host=loopback port=$Port serverSsl=false forceHttps=false"
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
        foreach ($p in @($Port, $ManagementPort, $NettyPort)) {
            $inUse = @(Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue)
            if ($inUse.Count -gt 0) {
                throw "[AWX][chat-debug-fx-sse] port-conflict port=$p"
            }
        }
        $outLog = Join-Path (Split-Path -Parent $ReportPath) "$SmokeName.out.log"
        $errLog = Join-Path (Split-Path -Parent $ReportPath) "$SmokeName.err.log"
        $gradleArgs = @("bootRun", "--no-daemon", "-x", "test", "--project-cache-dir", $ProjectCacheDir)
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

    $traceMemorySeedStatus = 0
    $traceMemorySeedRouteDecision = ""
    if ($RequireTraceMemory) {
        try {
            $seedResponse = Invoke-WebRequest `
                -Uri "$effectiveBaseUrl/api/diagnostics/trace/memory/self-probe?scenario=silent_failure" `
                -UseBasicParsing `
                -TimeoutSec $TimeoutSec
            $traceMemorySeedStatus = [int]$seedResponse.StatusCode
            $seedJson = $seedResponse.Content | ConvertFrom-Json
            $traceMemorySeedRouteDecision = [string](Get-JsonProp $seedJson "recoveryRouteDecision")
            if ($traceMemorySeedStatus -ne 200 -or [string]::IsNullOrWhiteSpace($traceMemorySeedRouteDecision)) {
                throw "seedStatus=$traceMemorySeedStatus routeDecisionMissing"
            }
        } catch {
            throw "[AWX][chat-debug-fx-sse] evidence_needed: trace-memory self-probe seed failed errorType=$($_.Exception.GetType().Name)"
        }
    }

    $effectiveMessage = if ($RequireQueryRewrite -and $Message -eq "AWX debug fx local route probe") {
        $QueryRewriteDiagnosticMessage
    } else {
        $Message
    }
    $effectiveSearchMode = if ($RequireQueryRewrite) {
        "AUTO"
    } else {
        "OFF"
    }
    $bodyMap = [ordered]@{
        message = $effectiveMessage
        useRag = $false
        useWebSearch = $false
        searchMode = $effectiveSearchMode
        maxTokens = 96
        temperature = 0.1
    }
    if ($RequireQueryRewrite) {
        $bodyMap.useRag = [bool]$RequireQueryRewrite
    }
    if (-not [string]::IsNullOrWhiteSpace($Model)) {
        $bodyMap.model = $Model
    }
    $body = $bodyMap | ConvertTo-Json -Depth 8

    $headers = @{
        "X-Request-Id" = $RequestId
        "X-Session-Id" = $SessionKey
    }
    $httpClient = [System.Net.Http.HttpClient]::new()
    $httpClient.Timeout = [TimeSpan]::FromSeconds($TimeoutSec)
    $httpRequest = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::Post,
        "$effectiveBaseUrl/api/chat/stream"
    )
    foreach ($entry in $headers.GetEnumerator()) {
        [void]$httpRequest.Headers.TryAddWithoutValidation([string]$entry.Key, [string]$entry.Value)
    }
    $httpRequest.Content = [System.Net.Http.StringContent]::new($body, [System.Text.Encoding]::UTF8, "application/json")
    $response = $httpClient.SendAsync(
        $httpRequest,
        [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
    ).GetAwaiter().GetResult()
    if (-not $response.IsSuccessStatusCode) {
        throw "[AWX][chat-debug-fx-sse] stream status=$([int]$response.StatusCode)"
    }

    $eventsList = New-Object System.Collections.Generic.List[object]
    $scanBuffer = [System.Text.StringBuilder]::new()
    $lineBuffer = New-Object System.Collections.Generic.List[string]
    $debugFxSatisfied = $false
    $operatorActionRequired = -not ([bool]$RequireQueryRewrite -or [bool]$RequireTraceMemory)
    $queryRewriteSatisfied = -not [bool]$RequireQueryRewrite
    $traceMemorySatisfied = -not [bool]$RequireTraceMemory
    $queryRewritePresent = $false
    $queryRewriteStatus = ""
    $queryRewriteReason = ""
    $queryRewriteSource = ""
    $queryRewriteTemperatureHints = @()
    $queryRewriteHeartbeatStatus = 0
    $queryRewriteHeartbeatFailReason = ""
    $maxScanChars = 32768
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8)
    try {
        while ((Get-Date) -lt $deadline) {
            $readTask = $reader.ReadLineAsync()
            if (-not $readTask.Wait(2000)) {
                while (-not $readTask.Wait(2000)) {
                    if ((Get-Date) -ge $deadline) {
                        $seenTypes = New-Object System.Collections.Generic.List[string]
                        foreach ($seenEvent in $eventsList) {
                            $seenName = [string]$seenEvent.event
                            if (-not [string]::IsNullOrWhiteSpace($seenName) -and -not $seenTypes.Contains($seenName)) {
                                $seenTypes.Add($seenName)
                            }
                        }
                        throw "[AWX][chat-debug-fx-sse] evidence_needed: stream timeout waiting for SSE line eventTypes=$($seenTypes -join ',')"
                    }
                }
            }
            $line = $readTask.Result
            if ($null -eq $line) {
                break
            }
            if ($scanBuffer.Length -lt $maxScanChars) {
                $remaining = $maxScanChars - $scanBuffer.Length
                $slice = if ($line.Length -gt $remaining) { $line.Substring(0, $remaining) } else { $line }
                [void]$scanBuffer.AppendLine($slice)
            }
            if ([string]::IsNullOrWhiteSpace($line)) {
                if ($lineBuffer.Count -gt 0) {
                    $block = [string]::Join("`n", $lineBuffer)
                    $blockEvents = @(ConvertFrom-Sse $block)
                    foreach ($blockEvent in $blockEvents) {
                        $eventsList.Add($blockEvent)
                        if ($blockEvent.event -eq "debug_fx" -and -not [string]::IsNullOrWhiteSpace($blockEvent.data)) {
                            try {
                                $candidate = $blockEvent.data | ConvertFrom-Json
                                $candidateSignal = Get-JsonProp $candidate "debugFxSignal"
                                $candidateLabels = Get-JsonProp $candidateSignal "labels"
                                if (Test-DebugFxOperatorActionLabels $candidateLabels) {
                                    $debugFxSatisfied = $true
                                }
                                if (Test-TraceMemoryLabels $candidateLabels) {
                                    $traceMemorySatisfied = $true
                                }
                            } catch {
                                Write-Host "[AWX][chat-debug-fx-sse] evidence_needed: debug_fx json parse failed errorType=$($_.Exception.GetType().Name)"
                            }
                        } elseif ($blockEvent.event -eq "transformer" -and -not [string]::IsNullOrWhiteSpace($blockEvent.data)) {
                            try {
                                $candidate = $blockEvent.data | ConvertFrom-Json
                                $candidateRewrite = Get-TransformerBlock $candidate "rewrite"
                                if ($null -ne $candidateRewrite) {
                                    $queryRewritePresent = $true
                                    $queryRewriteStatus = [string](Get-JsonProp $candidateRewrite "status")
                                    $queryRewriteReason = [string](Get-JsonProp $candidateRewrite "reason")
                                    if (Test-QueryRewriteBlock $candidateRewrite) {
                                        $queryRewriteSatisfied = $true
                                        $queryRewriteSource = "stream_transformer"
                                    }
                                }
                            } catch {
                                Write-Host "[AWX][chat-debug-fx-sse] evidence_needed: transformer json parse failed errorType=$($_.Exception.GetType().Name)"
                            }
                        }
                    }
                    $lineBuffer.Clear()
                }
                if ((-not $operatorActionRequired -or $debugFxSatisfied) -and $queryRewriteSatisfied -and $traceMemorySatisfied) {
                    break
                }
                continue
            }
            $lineBuffer.Add($line)
        }
        if ($lineBuffer.Count -gt 0) {
            $block = [string]::Join("`n", $lineBuffer)
            foreach ($blockEvent in @(ConvertFrom-Sse $block)) {
                $eventsList.Add($blockEvent)
            }
        }
    } finally {
        $reader.Dispose()
        $response.Dispose()
        $httpRequest.Dispose()
        $httpClient.Dispose()
    }

    $raw = $scanBuffer.ToString()
    $heartbeatRawForScan = ""
    $events = @()
    foreach ($eventItem in $eventsList) {
        $events += $eventItem
    }
    $eventTypes = @($events | ForEach-Object { $_.event } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    $debugEvent = $null
    $firstDebugEvent = $null
    $traceMemoryDebugEvent = $null
    $operatorDebugEvent = $null
    $traceMemoryOperatorDebugEvent = $null
    foreach ($event in $events) {
        if ($event.event -ne "debug_fx" -or [string]::IsNullOrWhiteSpace($event.data)) {
            continue
        }
        try {
            $candidateDebugEvent = $event.data | ConvertFrom-Json
            if ($null -eq $firstDebugEvent) {
                $firstDebugEvent = $candidateDebugEvent
            }
            $candidateSignal = Get-JsonProp $candidateDebugEvent "debugFxSignal"
            $candidateLabels = Get-JsonProp $candidateSignal "labels"
            $candidateHasTraceMemory = Test-TraceMemoryLabels $candidateLabels
            $candidateHasOperatorAction = Test-DebugFxOperatorActionLabels $candidateLabels
            if ($candidateHasOperatorAction -and $null -eq $operatorDebugEvent) {
                $operatorDebugEvent = $candidateDebugEvent
            }
            if ($candidateHasTraceMemory -and $candidateHasOperatorAction) {
                $traceMemoryOperatorDebugEvent = $candidateDebugEvent
                break
            }
            if ($candidateHasTraceMemory -and $null -eq $traceMemoryDebugEvent) {
                $traceMemoryDebugEvent = $candidateDebugEvent
            }
        } catch {
            Write-Host "[AWX][chat-debug-fx-sse] evidence_needed: debug_fx json parse failed errorType=$($_.Exception.GetType().Name)"
        }
    }
    $debugEvent = if ($RequireTraceMemory -and $null -ne $traceMemoryOperatorDebugEvent) {
        $traceMemoryOperatorDebugEvent
    } elseif ($RequireTraceMemory -and $null -ne $traceMemoryDebugEvent) {
        $traceMemoryDebugEvent
    } elseif ($null -ne $operatorDebugEvent) {
        $operatorDebugEvent
    } else {
        $firstDebugEvent
    }
    foreach ($event in $events) {
        if ($event.event -ne "transformer" -or [string]::IsNullOrWhiteSpace($event.data)) {
            continue
        }
        try {
            $candidate = $event.data | ConvertFrom-Json
            $candidateRewrite = Get-TransformerBlock $candidate "rewrite"
            if ($null -ne $candidateRewrite) {
                $queryRewritePresent = $true
                $queryRewriteStatus = [string](Get-JsonProp $candidateRewrite "status")
                $queryRewriteReason = [string](Get-JsonProp $candidateRewrite "reason")
                if (Test-QueryRewriteBlock $candidateRewrite) {
                    $queryRewriteSatisfied = $true
                    $queryRewriteSource = "stream_transformer"
                }
            }
        } catch {
            Write-Host "[AWX][chat-debug-fx-sse] evidence_needed: transformer json parse failed errorType=$($_.Exception.GetType().Name)"
        }
    }
    if ($RequireQueryRewrite) {
        $heartbeatProof = Get-QueryRewriteHeartbeatProof $effectiveBaseUrl $TimeoutSec
        $queryRewriteHeartbeatStatus = [int]$heartbeatProof.httpStatus
        $queryRewriteHeartbeatFailReason = [string]$heartbeatProof.failReason
        $heartbeatRawForScan = [string]$heartbeatProof.rawForScan
        if ([bool]$heartbeatProof.present) {
            if (-not $queryRewritePresent -or -not $queryRewriteSatisfied) {
                $queryRewritePresent = $true
                $queryRewriteStatus = [string]$heartbeatProof.status
                $queryRewriteReason = [string]$heartbeatProof.reason
            }
            $queryRewriteTemperatureHints = @($heartbeatProof.variantLaneTemperatureHints)
        }
        if (-not $queryRewriteSatisfied -and [bool]$heartbeatProof.satisfied) {
            $queryRewriteSatisfied = $true
            $queryRewriteSource = "heartbeat"
        }
    }
    $rawForScan = $raw + [Environment]::NewLine + $heartbeatRawForScan

    $signal = Get-JsonProp $debugEvent "debugFxSignal"
    $labels = Get-JsonProp $signal "labels"
    if ($RequireTraceMemory -and $null -ne $traceMemoryDebugEvent -and $null -ne $operatorDebugEvent -and $null -eq $traceMemoryOperatorDebugEvent) {
        $traceMemorySignal = Get-JsonProp $traceMemoryDebugEvent "debugFxSignal"
        $traceMemoryLabels = Get-JsonProp $traceMemorySignal "labels"
        $operatorSignal = Get-JsonProp $operatorDebugEvent "debugFxSignal"
        $operatorLabels = Get-JsonProp $operatorSignal "labels"
        $labels = Merge-DebugFxLabels $traceMemoryLabels $operatorLabels
    }
    $triggerReason = Get-Label $labels "localLlmTriggerReason"
    $failureClass = Get-Label $labels "localLlmFailureClass"
    $nextAction = Get-Label $labels "localLlmNextAction"
    $actionScore = Get-Label $labels "localLlmActionScore"
    $scoreDelta = Get-Label $labels "localLlmScoreDelta"
    $traceMemoryRouteDecision = Get-Label $labels "traceMemoryRouteDecision"
    $traceMemoryVirtualCheckpointKey = Get-Label $labels "traceMemoryVirtualCheckpointKey"
    $traceMemoryVirtualCheckpointStage = Get-Label $labels "traceMemoryVirtualCheckpointStage"
    $traceMemoryVirtualCheckpointPhase = Get-Label $labels "traceMemoryVirtualCheckpointPhase"
    $traceMemoryCfvmOffered = Get-Label $labels "traceMemoryCfvmOffered"
    $traceMemoryCfvmPatternId = Get-Label $labels "traceMemoryCfvmPatternId"

    $secretPatternHits = ([regex]::Matches($rawForScan, 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}')).Count
    $rawPromptHits = if ($rawForScan.Contains($effectiveMessage)) { 1 } else { 0 }
    $rawModelHits = if ((-not [string]::IsNullOrWhiteSpace($Model)) -and $rawForScan.Contains($Model)) { 1 } else { 0 }
    $debugFxPresent = $null -ne $debugEvent
    $operatorActionPresent = -not [string]::IsNullOrWhiteSpace($triggerReason) -or -not [string]::IsNullOrWhiteSpace($nextAction)
    $traceMemoryPresent = Test-TraceMemoryLabels $labels

    if (-not $debugFxPresent) {
        throw "[AWX][chat-debug-fx-sse] evidence_needed: event: debug_fx missing eventTypes=$($eventTypes -join ',')"
    }
    if ($operatorActionRequired -and -not $operatorActionPresent) {
        throw "[AWX][chat-debug-fx-sse] evidence_needed: debug_fx missing localLlm operatorAction labels eventTypes=$($eventTypes -join ',')"
    }
    if ($RequireQueryRewrite -and -not $queryRewriteSatisfied) {
        throw "[AWX][chat-debug-fx-sse] evidence_needed: query rewrite proof missing eventTypes=$($eventTypes -join ',') heartbeatStatus=$queryRewriteHeartbeatStatus heartbeatFailReason=$queryRewriteHeartbeatFailReason"
    }
    if ($RequireTraceMemory -and -not $traceMemoryPresent) {
        throw "[AWX][chat-debug-fx-sse] evidence_needed: debug_fx missing trace-memory labels eventTypes=$($eventTypes -join ',') seedStatus=$traceMemorySeedStatus seedRouteDecision=$traceMemorySeedRouteDecision"
    }

    $summary = [ordered]@{
        ok = $true
        status = [int]$response.StatusCode
        debugFxPresent = $debugFxPresent
        operatorActionPresent = $operatorActionPresent
        operatorActionRequired = $operatorActionRequired
        localLlmTriggerReason = $triggerReason
        localLlmFailureClass = $failureClass
        localLlmNextAction = $nextAction
        localLlmActionScore = $actionScore
        localLlmScoreDelta = $scoreDelta
        traceMemoryPresent = $traceMemoryPresent
        traceMemoryRouteDecision = $traceMemoryRouteDecision
        traceMemoryVirtualCheckpointKey = $traceMemoryVirtualCheckpointKey
        traceMemoryVirtualCheckpointStage = $traceMemoryVirtualCheckpointStage
        traceMemoryVirtualCheckpointPhase = $traceMemoryVirtualCheckpointPhase
        traceMemoryCfvmOffered = $traceMemoryCfvmOffered
        traceMemoryCfvmPatternId = $traceMemoryCfvmPatternId
        traceMemoryRequired = [bool]$RequireTraceMemory
        traceMemorySeedStatus = $traceMemorySeedStatus
        traceMemorySeedRouteDecision = $traceMemorySeedRouteDecision
        queryRewriteRequired = [bool]$RequireQueryRewrite
        queryRewritePresent = $queryRewritePresent
        queryRewriteStatus = $queryRewriteStatus
        queryRewriteReason = $queryRewriteReason
        queryRewriteSource = $queryRewriteSource
        queryRewriteHeartbeatStatus = $queryRewriteHeartbeatStatus
        queryRewriteTemperatureHints = $queryRewriteTemperatureHints
        eventTypes = $eventTypes
        eventCount = $events.Count
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

    Write-Host "[AWX][chat-debug-fx-sse] status=$($summary.status) debugFxPresent=$($summary.debugFxPresent) operatorActionPresent=$($summary.operatorActionPresent) traceMemoryPresent=$traceMemoryPresent traceMemoryRequired=$([bool]$RequireTraceMemory) queryRewritePresent=$($summary.queryRewritePresent) queryRewriteStatus=$queryRewriteStatus triggerReason=$triggerReason failureClass=$failureClass nextAction=$nextAction traceMemoryRouteDecision=$traceMemoryRouteDecision traceMemoryVirtualCheckpointStage=$traceMemoryVirtualCheckpointStage traceMemoryVirtualCheckpointPhase=$traceMemoryVirtualCheckpointPhase traceMemoryCfvmOffered=$traceMemoryCfvmOffered traceMemoryCfvmPatternId=$traceMemoryCfvmPatternId secretPatternHits=$secretPatternHits rawPromptHits=$rawPromptHits rawModelHits=$rawModelHits report=$ReportPath"
} finally {
    if ($null -ne $app -and $null -ne $app.Process) {
        Stop-ProcessTree -ProcessId ([int]$app.Process.Id)
    }
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], "Process")
    }
}
