param(
    [int]$Port = 18214,
    [int]$ManagementPort = 18215,
    [int]$NettyPort = 18216,
    [int]$StartupTimeoutSeconds = 160,
    [int]$TimeoutSec = 80,
    [string]$BaseUrl = "",
    [string]$OutputDir = "verification\chat-trace-memory-snapshot-pointer",
    [switch]$AssumeRunning,
    [switch]$StaticOnly
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
$SmokeName = "chat-trace-memory-snapshot-pointer"
$ResolvedOutputDir = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir
} else {
    Join-Path $Root $OutputDir
}
$ReportPath = Join-Path $ResolvedOutputDir "$SmokeName.json"

function Redact-Text([string]$Text) {
    if ($null -eq $Text) { return "" }
    $out = $Text
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
            throw "[AWX][chat-trace-memory-pointer-smoke] bootRun exited before heartbeat was reachable`n$out`n$err"
        }
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "[AWX][chat-trace-memory-pointer-smoke] evidence_needed: startup timeout waiting for $Url"
}

function Resolve-SmokeBaseUrl([string]$ExplicitBaseUrl, [int]$RuntimePort) {
    if (-not [string]::IsNullOrWhiteSpace($ExplicitBaseUrl)) {
        return $ExplicitBaseUrl.TrimEnd("/")
    }
    return "http://127.0.0.1:$RuntimePort"
}

function Invoke-JsonGet([string]$Url) {
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
    return [pscustomobject]@{
        Status = [int]$response.StatusCode
        Content = [string]$response.Content
        Json = ($response.Content | ConvertFrom-Json)
    }
}

function Test-RawLeak([string]$Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    return $Text.Contains("memoryCtx") -or
        $Text.Contains("rawSnapshot.raw") -or
        $Text.Contains("traceMemory.memoryCtx")
}

if ($StaticOnly) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    Write-Host "[AWX][chat-trace-memory-pointer-smoke] staticOnly=true script=$PSCommandPath"
    exit 0
}

if (-not (Test-Path $Gradle) -and -not $AssumeRunning) {
    throw "[AWX][chat-trace-memory-pointer-smoke] evidence_needed: gradlew.bat missing under $Root"
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
    "LOCAL_LLM_AUTOSTART",
    "LOCAL_LLM_WARMUP_ENABLED",
    "DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED",
    "LLM_TIMEOUT_SECONDS",
    "LLM_REQUESTED_MODEL_TIMEOUT_SECONDS",
    "OPENAI_RETRY_MAX_ATTEMPTS",
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
    $ProjectCacheDir = Join-Path $env:LOCALAPPDATA "awx-gradle-project-cache\desktop-chat-trace-memory"
    $env:AWX_AGENT_HOST = "desktop"
    $env:AWX_SPLIT_BUILD_OUTPUTS = "1"
    $env:AWX_BUILD_HOST_ID = "desktop-chat-trace-memory"
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE ".gradle-awx-desktop"
    $env:AWX_PROJECT_CACHE_DIR = $ProjectCacheDir
    New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCacheDir | Out-Null

    $env:SPRING_APPLICATION_JSON = '{"naver":{"keys":"","client-id":"","client-secret":""}}'
    $env:SPRING_PROFILES_ACTIVE = "local"
    $env:SERVER_PORT = [string]$Port
    $env:MANAGEMENT_SERVER_PORT = [string]$ManagementPort
    $env:NETTY_PORT = [string]$NettyPort
    $env:SERVER_SSL_ENABLED = "false"
    $env:LOCAL_LLM_AUTOSTART = "false"
    $env:LOCAL_LLM_WARMUP_ENABLED = "false"
    $env:DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED = "false"
    $env:LLM_TIMEOUT_SECONDS = "1"
    $env:LLM_REQUESTED_MODEL_TIMEOUT_SECONDS = "1"
    $env:OPENAI_RETRY_MAX_ATTEMPTS = "0"
    $env:LLM_API_KEY = ""
    $env:NAVER_KEYS = ""
    $env:NAVER_CLIENT_ID = ""
    $env:NAVER_CLIENT_SECRET = ""
    $env:BRAVE_API_KEY = ""
    $env:SERPAPI_API_KEY = ""
    $env:TAVILY_API_KEY = ""
    $env:OPENAI_API_KEY = ""

    $effectiveBaseUrl = Resolve-SmokeBaseUrl $BaseUrl $Port

    if (-not $AssumeRunning) {
        $launchPorts = @($Port, $ManagementPort, $NettyPort)
        if ((@($launchPorts | Sort-Object -Unique)).Count -ne @($launchPorts).Count) {
            throw "[AWX][chat-trace-memory-pointer-smoke] port-conflict reason=duplicate-launch-port ports=$($launchPorts -join ',')"
        }
        foreach ($p in @($Port, $ManagementPort, $NettyPort)) {
            $inUse = netstat -ano | Select-String ":$p "
            if ($inUse) {
                throw "[AWX][chat-trace-memory-pointer-smoke] port-conflict port=$p"
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
        Wait-ForApp $app "$effectiveBaseUrl/api/chat/ui-heartbeat"
    }

    $headers = @{
        "Content-Type" = "application/json"
        "X-Request-Id" = "chat-trace-memory-pointer-smoke"
        "X-Session-Id" = "chat-424242"
    }
    $chatBody = @{
        message = "trace memory actual chat smoke"
        useRag = $false
        useWebSearch = $false
        searchMode = "OFF"
        memoryMode = "full"
        maxTokens = 32
    } | ConvertTo-Json -Compress

    $chat = Invoke-WebRequest `
        -Uri "$effectiveBaseUrl/api/chat" `
        -Method Post `
        -Headers $headers `
        -Body $chatBody `
        -UseBasicParsing `
        -TimeoutSec $TimeoutSec

    $heartbeat = Invoke-JsonGet "$effectiveBaseUrl/api/chat/ui-heartbeat"
    $html = Invoke-WebRequest -Uri "$effectiveBaseUrl/api/diagnostics/trace/snapshots/latest-trace-memory/html" -UseBasicParsing -TimeoutSec $TimeoutSec
    $checkpointJson = Invoke-JsonGet "$effectiveBaseUrl/api/diagnostics/trace/snapshots/latest-trace-memory/checkpoints"
    $htmlText = [string]$html.Content
    $checkpointJsonText = [string]$checkpointJson.Content
    $chatPath = Join-Path $ResolvedOutputDir "$SmokeName.chat.json"
    $heartbeatPath = Join-Path $ResolvedOutputDir "$SmokeName.heartbeat.json"
    $htmlPath = Join-Path $ResolvedOutputDir "$SmokeName.latest-trace-memory.html"
    $checkpointJsonPath = Join-Path $ResolvedOutputDir "$SmokeName.latest-trace-memory-checkpoints.json"
    Set-Content -LiteralPath $chatPath -Value (Redact-Text ([string]$chat.Content)) -Encoding UTF8
    Set-Content -LiteralPath $heartbeatPath -Value (Redact-Text ([string]$heartbeat.Content)) -Encoding UTF8
    Set-Content -LiteralPath $htmlPath -Value (Redact-Text $htmlText) -Encoding UTF8
    Set-Content -LiteralPath $checkpointJsonPath -Value (Redact-Text $checkpointJsonText) -Encoding UTF8

    $rawForScan = [string]$chat.Content + [Environment]::NewLine + [string]$heartbeat.Content + [Environment]::NewLine + $htmlText + [Environment]::NewLine + $checkpointJsonText
    $htmlRawLeak = Test-RawLeak $htmlText
    $checkpointJsonRawLeak = Test-RawLeak $checkpointJsonText
    $secretPatternHits = ([regex]::Matches($rawForScan, 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}')).Count
    $hasTraceMemoryMetadataHtml = $htmlText.Contains('data-trace="trace-memory"') -and
        $htmlText.Contains('Trace Memory Checkpoint') -and
        $htmlText.Contains('Checkpoint history')
    $hasTraceMemoryCard = [string]$heartbeat.Content -match '"traceMemory"'
    $hasHeartbeatCheckpointJsonRoute = [string]$heartbeat.Json.traceMemory.latestCheckpointJsonRoute -eq "/api/diagnostics/trace/snapshots/latest-trace-memory/checkpoints"
    $checkpointHistoryEntries = if ($null -eq $checkpointJson.Json.checkpointHistory) { @() } else { @($checkpointJson.Json.checkpointHistory) }
    $checkpointHistorySize = [int]$checkpointJson.Json.checkpointHistorySize
    $checkpointHistoryCount = [int]$checkpointHistoryEntries.Count
    $hasCheckpointJson = [int]$checkpointJson.Status -eq 200 -and
        $checkpointHistorySize -ge 4 -and
        $checkpointHistoryCount -ge 4 -and
        [string]$checkpointJson.Json.links.latestTraceHtml -eq "/api/diagnostics/trace/snapshots/latest-trace-memory/html"

    if ([int]$chat.StatusCode -ne 200 -or -not $hasTraceMemoryMetadataHtml -or -not $hasTraceMemoryCard -or -not $hasHeartbeatCheckpointJsonRoute -or -not $hasCheckpointJson -or $htmlRawLeak -or $checkpointJsonRawLeak -or $secretPatternHits -gt 0) {
        throw "[AWX][chat-trace-memory-pointer-smoke] mismatch chatStatus=$([int]$chat.StatusCode) traceMemoryHtml=$hasTraceMemoryMetadataHtml heartbeatCard=$hasTraceMemoryCard heartbeatCheckpointJsonRoute=$hasHeartbeatCheckpointJsonRoute checkpointJson=$hasCheckpointJson checkpointHistorySize=$checkpointHistorySize checkpointHistoryCount=$checkpointHistoryCount htmlRawLeak=$htmlRawLeak checkpointJsonRawLeak=$checkpointJsonRawLeak secretPatternHits=$secretPatternHits"
    }

    $summary = [ordered]@{
        ok = $true
        chatStatus = [int]$chat.StatusCode
        heartbeatStatus = [int]$heartbeat.Status
        traceMemoryHtmlStatus = [int]$html.StatusCode
        traceMemoryCheckpointJsonStatus = [int]$checkpointJson.Status
        hasTraceMemoryMetadataHtml = $hasTraceMemoryMetadataHtml
        hasTraceMemoryCard = $hasTraceMemoryCard
        hasHeartbeatCheckpointJsonRoute = $hasHeartbeatCheckpointJsonRoute
        hasCheckpointJson = $hasCheckpointJson
        checkpointHistorySize = $checkpointHistorySize
        checkpointHistoryCount = $checkpointHistoryCount
        htmlRawLeak = $htmlRawLeak
        checkpointJsonRawLeak = $checkpointJsonRawLeak
        secretPatternHits = $secretPatternHits
        chatPath = $chatPath
        heartbeatPath = $heartbeatPath
        htmlPath = $htmlPath
        checkpointJsonPath = $checkpointJsonPath
        report = $ReportPath
    }
    $summary | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    Write-Host "[AWX][chat-trace-memory-pointer-smoke] chatStatus=$($summary.chatStatus) traceMemoryHtml=$($summary.hasTraceMemoryMetadataHtml) heartbeatCard=$($summary.hasTraceMemoryCard) heartbeatCheckpointJsonRoute=$($summary.hasHeartbeatCheckpointJsonRoute) checkpointJson=$($summary.hasCheckpointJson) checkpointHistorySize=$($summary.checkpointHistorySize) checkpointHistoryCount=$($summary.checkpointHistoryCount) htmlRawLeak=$($summary.htmlRawLeak) checkpointJsonRawLeak=$($summary.checkpointJsonRawLeak) secretPatternHits=$($summary.secretPatternHits) report=$ReportPath"
} finally {
    if ($null -ne $app -and $null -ne $app.Process -and -not $app.Process.HasExited) {
        Stop-ProcessTree -ProcessId ([int]$app.Process.Id)
    }
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], "Process")
    }
}
