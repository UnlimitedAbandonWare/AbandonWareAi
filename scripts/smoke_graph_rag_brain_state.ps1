param(
    [int]$Port = 18192,
    [int]$ManagementPort = 18193,
    [int]$StartupTimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
if (-not (Test-Path $Gradle)) {
    throw "gradlew.bat not found under repo root"
}

$env:AWX_AGENT_HOST = if ([string]::IsNullOrWhiteSpace($env:AWX_AGENT_HOST)) { "desktop" } else { $env:AWX_AGENT_HOST }
$env:AWX_SPLIT_BUILD_OUTPUTS = if ([string]::IsNullOrWhiteSpace($env:AWX_SPLIT_BUILD_OUTPUTS)) { "1" } else { $env:AWX_SPLIT_BUILD_OUTPUTS }
$env:AWX_BUILD_HOST_ID = if ([string]::IsNullOrWhiteSpace($env:AWX_BUILD_HOST_ID)) { "desktop" } else { $env:AWX_BUILD_HOST_ID }
$env:GRADLE_USER_HOME = if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
    Join-Path $env:USERPROFILE ".gradle-awx-desktop"
} else {
    $env:GRADLE_USER_HOME
}
$ProjectCacheDir = if ([string]::IsNullOrWhiteSpace($env:AWX_PROJECT_CACHE_DIR)) {
    Join-Path $env:LOCALAPPDATA "awx-gradle-project-cache\desktop"
} else {
    $env:AWX_PROJECT_CACHE_DIR
}
$env:AWX_PROJECT_CACHE_DIR = $ProjectCacheDir
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCacheDir | Out-Null

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-brain-state-smoke-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null

$AdminToken = "brain-smoke-" + [guid]::NewGuid().ToString("N")
$SessionId = "smoke-brain-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
$SmokeText = "Alpha GraphRAG smoke links Beta BrainState route through a redacted operator fixture."
$OriginalEnv = @{}

function Redact-Text([string]$Text) {
    if ($null -eq $Text) { return "" }
    $out = $Text
    foreach ($secret in @($AdminToken, $SmokeText, $TempRoot)) {
        if (-not [string]::IsNullOrWhiteSpace($secret)) {
            $out = $out -replace [regex]::Escape($secret), "<redacted>"
        }
    }
    return $out
}

function Set-SmokeEnv([string]$Name, [string]$Value) {
    if (-not $OriginalEnv.ContainsKey($Name)) {
        $OriginalEnv[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Restore-SmokeEnv {
    foreach ($entry in $OriginalEnv.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
}

function Configure-SmokeEnv {
    Set-SmokeEnv "SPRING_PROFILES_ACTIVE" "local,graph-rag"
    Set-SmokeEnv "SERVER_PORT" ([string]$Port)
    Set-SmokeEnv "MANAGEMENT_SERVER_PORT" ([string]$ManagementPort)
    Set-SmokeEnv "DOMAIN_ALLOWLIST_ADMIN_TOKEN" $AdminToken
    Set-SmokeEnv "DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED" "true"
    Set-SmokeEnv "DOMAIN_ALLOWLIST_ADMIN_TOKEN_ALLOW_QUERY" "false"
    Set-SmokeEnv "VECTOR_ADMIN_TOKEN" $AdminToken
    Set-SmokeEnv "RAG_BRAIN_STATE_ENABLED" "true"
    Set-SmokeEnv "RAG_BRAIN_STATE_INDEXING_ENABLED" "true"
    Set-SmokeEnv "RAG_BRAIN_STATE_CAPTURE_CHAT_WORKFLOW" "false"
    Set-SmokeEnv "RAG_BRAIN_STATE_MEANINGFUL_GATE_ENABLED" "false"
    Set-SmokeEnv "MANAGEMENT_HEALTH_NEO4J_ENABLED" "false"
    Set-SmokeEnv "RETRIEVAL_KG_NEO4J_ENABLED" "false"
    Set-SmokeEnv "VECTOR_UPSTASH_ENABLED" "false"
    Set-SmokeEnv "LMS_CORPUS_STARTUP_ENABLED" "false"
    Set-SmokeEnv "LOCAL_LLM_AUTOSTART" "false"
    Set-SmokeEnv "LOCAL_LLM_WARMUP_ENABLED" "false"
    Set-SmokeEnv "NER_LLM_ENABLED" "false"
}

function Start-SmokeApp {
    Configure-SmokeEnv
    $outLog = Join-Path $TempRoot "boot.out.log"
    $errLog = Join-Path $TempRoot "boot.err.log"
    $GradleArgs = @("bootRun", "--no-daemon", "-x", "test", "--project-cache-dir", $ProjectCacheDir)
    $proc = Start-Process -FilePath $Gradle `
        -ArgumentList $GradleArgs `
        -WorkingDirectory $Root `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden `
        -PassThru
    return @{ Process = $proc; OutLog = $outLog; ErrLog = $errLog }
}

function Stop-SmokeApp($App) {
    if ($null -eq $App -or $null -eq $App.Process) { return }
    $proc = $App.Process
    if (-not $proc.HasExited) {
        & taskkill.exe /PID $proc.Id /T /F | Out-Null
        $proc.WaitForExit(10000) | Out-Null
    }
}

function Tail-Log($Path) {
    if (-not (Test-Path $Path)) { return "" }
    return Redact-Text ((Get-Content -LiteralPath $Path -Tail 100 -ErrorAction SilentlyContinue) -join [Environment]::NewLine)
}

function Wait-ForHttp($App) {
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    $url = "http://127.0.0.1:$ManagementPort/actuator/health"
    while ((Get-Date) -lt $deadline) {
        if ($App.Process.HasExited) {
            throw "[AWX][brain-state-smoke] app exited during startup`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
        }
        try {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "[AWX][brain-state-smoke] startup timeout`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
}

function Wait-ForBrainStateEndpoint($App) {
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if ($App.Process.HasExited) {
            throw "[AWX][brain-state-smoke] app exited before brain-state endpoint readiness`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
        }
        $domains = Invoke-BrainStateApi "GET" "/api/diagnostics/rag/brain-state/domains" $null
        if ([int]$domains.Status -eq 200) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "[AWX][brain-state-smoke] brain-state endpoint readiness timeout`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
}

function Invoke-BrainStateApi([string]$Method, [string]$Path, $Body) {
    $headers = @{
        "X-Admin-Token" = $AdminToken
        "X-Vector-Admin-Token" = $AdminToken
    }
    $args = @{
        Uri = "http://127.0.0.1:$Port$Path"
        Method = $Method
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = 20
    }
    if ($null -ne $Body) {
        $args["ContentType"] = "application/json"
        $args["Body"] = ($Body | ConvertTo-Json -Depth 8 -Compress)
    }
    try {
        $resp = Invoke-WebRequest @args
        $parsed = $null
        if (-not [string]::IsNullOrWhiteSpace($resp.Content)) {
            $parsed = $resp.Content | ConvertFrom-Json
        }
        return @{ Status = [int]$resp.StatusCode; Body = $parsed; Raw = [string]$resp.Content }
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) { throw }
        $stream = $response.GetResponseStream()
        $reader = New-Object System.IO.StreamReader($stream)
        $content = $reader.ReadToEnd()
        $parsedBody = $null
        if (-not [string]::IsNullOrWhiteSpace($content)) {
            try { $parsedBody = $content | ConvertFrom-Json } catch { $parsedBody = $content }
        }
        return @{ Status = [int]$response.StatusCode; Body = $parsedBody; Raw = [string]$content }
    }
}

function Assert-Status($Actual, [int]$Expected, [string]$Label) {
    if ([int]$Actual.Status -ne $Expected) {
        throw "[AWX][brain-state-smoke] $Label expected=$Expected actual=$($Actual.Status) body=$(Redact-Text $Actual.Raw)"
    }
}

function Assert-NoRawLeak([string]$Raw, [string]$Label) {
    if (-not [string]::IsNullOrWhiteSpace($Raw) -and $Raw.Contains($SmokeText)) {
        throw "[AWX][brain-state-smoke] $Label leaked raw smoke text"
    }
    if (-not [string]::IsNullOrWhiteSpace($Raw) -and $Raw.Contains($AdminToken)) {
        throw "[AWX][brain-state-smoke] $Label leaked admin token"
    }
}

$app = $null
$Succeeded = $false
try {
    $app = Start-SmokeApp
    Wait-ForHttp $app
    Wait-ForBrainStateEndpoint $app

    $ingest = Invoke-BrainStateApi "POST" "/api/admin/vector/brain/ingest" @{
        sessionId = $SessionId
        text = $SmokeText
        domain = "UAW_THUMB"
    }
    Assert-Status $ingest 200 "ingest"
    Assert-NoRawLeak $ingest.Raw "ingest"
    if ($ingest.Body.status -ne "indexed" -or [int]$ingest.Body.chunkCount -lt 1) {
        throw "[AWX][brain-state-smoke] ingest did not index chunk status=$($ingest.Body.status) chunks=$($ingest.Body.chunkCount)"
    }

    $snapshot = Invoke-BrainStateApi "GET" "/api/diagnostics/rag/brain-state/snapshot/$SessionId" $null
    Assert-Status $snapshot 200 "snapshot"
    Assert-NoRawLeak $snapshot.Raw "snapshot"
    if ($snapshot.Body.sessionId -ne $SessionId -or [int]$snapshot.Body.totalChunks -lt 1) {
        throw "[AWX][brain-state-smoke] snapshot missing session chunks session=$($snapshot.Body.sessionId) chunks=$($snapshot.Body.totalChunks)"
    }

    $domains = Invoke-BrainStateApi "GET" "/api/diagnostics/rag/brain-state/domains" $null
    Assert-Status $domains 200 "domains"
    Assert-NoRawLeak $domains.Raw "domains"
    $hasDomain = $false
    foreach ($domain in @($domains.Body.domains)) {
        if ($domain.domain -eq "UAW_THUMB" -and [int]$domain.chunkCount -ge 1) {
            $hasDomain = $true
        }
    }
    if (-not $hasDomain) {
        throw "[AWX][brain-state-smoke] domains missing UAW_THUMB chunk"
    }

    $status = Invoke-BrainStateApi "GET" "/api/admin/vector/brain/status" $null
    Assert-Status $status 200 "status"
    Assert-NoRawLeak $status.Raw "status"

    Write-Host "[AWX][brain-state-smoke] OK ingest=200 snapshot=200 domains=200 status=200 session=$SessionId chunks=$($snapshot.Body.totalChunks) rawLeak=false"
    $Succeeded = $true
} finally {
    if (-not $Succeeded -and $null -ne $app) {
        Write-Host "[AWX][brain-state-smoke] logTail"
        Write-Host (Tail-Log $app.OutLog)
        Write-Host (Tail-Log $app.ErrLog)
        Write-Host "[AWX][brain-state-smoke] retainedLogDir=$TempRoot"
    }
    Stop-SmokeApp $app
    Restore-SmokeEnv
    if ($Succeeded) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
