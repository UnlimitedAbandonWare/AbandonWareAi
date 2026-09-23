param(
    [int]$Port = 18084,
    [int]$ManagementPort = 18085,
    [int]$StartupTimeoutSeconds = 120,
    [ValidateSet("simulated", "real", "desktop", "both", "all")]
    [string]$Mode = "simulated",
    [string]$RealPrimaryChatBaseUrl = $env:MACMINI_DESKTOP_GPU_3090_BASE_URL,
    [string]$RealFastBaseUrl = $env:MACMINI_DESKTOP_GPU_3060_BASE_URL,
    [string]$RealEmbeddingBaseUrl = $env:MACMINI_DESKTOP_GPU_EMBED_BASE_URL,
    [string]$RealAllowedHosts = $env:MACMINI_DESKTOP_GPU_ALLOWED_HOSTS,
    [string]$RealRequireAuthForRemote = $env:MACMINI_DESKTOP_GPU_REQUIRE_AUTH_FOR_REMOTE,
    [int]$CollisionGraceSeconds = 15
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
if (-not (Test-Path $Gradle)) {
    throw "gradlew.bat not found under repo root"
}

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-gpu-gateway-smoke-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null
$DatasetFile = Join-Path $TempRoot "train_rag_curated.jsonl"
$DatasetKey = "gpu-gateway-smoke-" + [guid]::NewGuid().ToString("N")
$AdminToken = "gpu-gateway-smoke-admin-" + [guid]::NewGuid().ToString("N")
$OriginalEnv = @{}

function Redact-Text([string]$Text) {
    if ($null -eq $Text) { return "" }
    $out = $Text
    foreach ($secret in @(
            $DatasetKey,
            $DatasetFile,
            $TempRoot,
            $AdminToken,
            $env:MACMINI_DATASET_API_KEY,
            $env:LLM_OWNER_TOKEN,
            $env:LLM_API_KEY,
            $env:DOMAIN_ALLOWLIST_ADMIN_TOKEN
        )) {
        if (-not [string]::IsNullOrWhiteSpace($secret) -and (Has-UsableSecret $secret)) {
            $out = $out -replace [regex]::Escape($secret), "<redacted>"
        }
    }
    return $out
}

function Is-Blank([string]$Value) {
    return [string]::IsNullOrWhiteSpace($Value)
}

function Has-UsableSecret([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $false }
    $v = $Value.Trim()
    $lower = $v.ToLowerInvariant()
    if ($v.StartsWith('${') -or $v.EndsWith('}')) { return $false }
    if (@("dummy", "test", "changeme", "sk-local", "ollama", "__missing__") -contains $lower) { return $false }
    return $true
}

function New-DiagnosticsHeaders {
    return @{ "X-Admin-Token" = $AdminToken }
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse("127.0.0.1"), 0)
    try {
        $listener.Start()
        return [int]$listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
}

function Start-FakeGpuGateway([int]$GatewayPort) {
    $job = Start-Job -ArgumentList $GatewayPort -ScriptBlock {
        param([int]$Port)
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse("127.0.0.1"), $Port)
        $listener.Start()
        try {
            while ($true) {
                if (-not $listener.Pending()) {
                    Start-Sleep -Milliseconds 50
                    continue
                }
                $client = $listener.AcceptTcpClient()
                try {
                    $stream = $client.GetStream()
                    $buffer = New-Object byte[] 2048
                    $read = $stream.Read($buffer, 0, $buffer.Length)
                    $request = [System.Text.Encoding]::ASCII.GetString($buffer, 0, [Math]::Max(0, $read))
                    $path = "/"
                    if ($request -match "^[A-Z]+\s+([^\s]+)") {
                        $path = $Matches[1]
                    }
                    if ($path -eq "/v1/models") {
                        $body = '{"object":"list","data":[{"id":"smoke-rtx3090","object":"model"}]}'
                    } elseif ($path -eq "/api/tags") {
                        $body = '{"models":[{"name":"smoke-embed-rtx3060"}]}'
                    } else {
                        $body = '{"ok":true}'
                    }
                    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
                    $header = "HTTP/1.1 200 OK`r`nContent-Type: application/json`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n"
                    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                    $stream.Write($headerBytes, 0, $headerBytes.Length)
                    $stream.Write($bytes, 0, $bytes.Length)
                } finally {
                    $client.Close()
                }
            }
        } finally {
            $listener.Stop()
        }
    }
    Start-Sleep -Milliseconds 500
    return $job
}

function Stop-FakeGpuGateway($Job) {
    if ($null -eq $Job) { return }
    Stop-Job -Job $Job -ErrorAction SilentlyContinue | Out-Null
    Remove-Job -Job $Job -Force -ErrorAction SilentlyContinue | Out-Null
}

function Set-SmokeEnvValue([string]$Name, [string]$Value) {
    if (-not $OriginalEnv.ContainsKey($Name)) {
        $OriginalEnv[$Name] = [Environment]::GetEnvironmentVariable($Name, "Process")
    }
    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Clear-SmokeEnv {
    foreach ($entry in $OriginalEnv.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
    $OriginalEnv.Clear()
}

function Get-SmokeGradleArguments {
    $args = @("bootRun", "--rerun-tasks", "--no-daemon", "-x", "test")
    if (-not [string]::IsNullOrWhiteSpace($env:AWX_PROJECT_CACHE_DIR)) {
        $args += @("--project-cache-dir", $env:AWX_PROJECT_CACHE_DIR)
    }
    return $args
}

function Get-GradleCollisionConflicts {
    $rootPattern = [regex]::Escape($Root)
    return @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.ProcessId -ne $PID -and
                $_.CommandLine -match $rootPattern -and
                ($_.CommandLine -match 'gradlew\.bat|GradleWrapperMain|GradleMain|bootRun' -or
                 $_.CommandLine -match 'build\\classes\\java\\main.*com\.example\.lms\.LmsApplication')
            } |
            Select-Object -First 5 -Property ProcessId, Name, CommandLine)
}

function Format-GradleCollisionSummary($Conflicts) {
    return ($Conflicts | ForEach-Object { "pid=$($_.ProcessId) name=$($_.Name)" }) -join "; "
}

function Wait-ForNoGradleCollision([string]$Stage) {
    $deadline = (Get-Date).AddSeconds($CollisionGraceSeconds)
    $reportedWait = $false
    do {
        $conflicts = @(Get-GradleCollisionConflicts)
        if ($conflicts.Count -eq 0) {
            return
        }
        if (-not $reportedWait) {
            Write-Host "[AWX][gpu-gateway-smoke] WAIT gradle-cache-collision-clear stage=$Stage conflictingProcesses=$(Format-GradleCollisionSummary $conflicts)"
            $reportedWait = $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $conflicts = @(Get-GradleCollisionConflicts)
    if ($conflicts.Count -gt 0) {
        $summary = Format-GradleCollisionSummary $conflicts
        throw "[AWX][gpu-gateway-smoke] gradle-cache-collision stage=$Stage conflictingProcesses=$summary"
    }
}

function Assert-NoGradleCollision([string]$Stage) {
    Wait-ForNoGradleCollision $Stage
}

function Set-BaseSmokeEnv {
    Set-SmokeEnvValue "SPRING_PROFILES_ACTIVE" "macmini-control-plane"
    Set-SmokeEnvValue "SERVER_PORT" ([string]$Port)
    Set-SmokeEnvValue "MANAGEMENT_SERVER_PORT" ([string]$ManagementPort)
    Set-SmokeEnvValue "SERVER_SSL_ENABLED" "false"
    Set-SmokeEnvValue "MACMINI_AUTOLEARN_ENABLED" "false"
    Set-SmokeEnvValue "MACMINI_AUTOLEARN_IDLE_TRIGGER_ENABLED" "false"
    Set-SmokeEnvValue "MACMINI_AUTOLEARN_DATASET_PATH" $DatasetFile
    Set-SmokeEnvValue "MACMINI_AUTOLEARN_DATASET_NAME" "gpu-gateway-smoke-curated-rag"
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_GATEWAY_ENABLED" "true"
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_PREFLIGHT_ADMISSION_ENABLED" "true"
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_PREFLIGHT_TIMEOUT_MS" "750"
    Set-SmokeEnvValue "LOCAL_LLM_AUTOSTART" "false"
    Set-SmokeEnvValue "LOCAL_LLM_WARMUP_ENABLED" "false"
    Set-SmokeEnvValue "DOMAIN_ALLOWLIST_ADMIN_TOKEN" $AdminToken
    Set-SmokeEnvValue "DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED" "true"
}

function Set-BlockedGatewayEnv([int]$ClosedGatewayPort) {
    Set-BaseSmokeEnv
    Set-SmokeEnvValue "MACMINI_DATASET_API_ENABLED" "false"
    Set-SmokeEnvValue "MACMINI_DATASET_API_KEY" ""
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_3090_BASE_URL" "http://127.0.0.1:$ClosedGatewayPort/v1"
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_3060_BASE_URL" ""
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_EMBED_BASE_URL" ""
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_ALLOWED_HOSTS" "127.0.0.1:$ClosedGatewayPort"
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_REQUIRE_AUTH_FOR_REMOTE" "true"
}

function Set-ReachableGatewayEnv([int]$GatewayPort) {
    Set-BaseSmokeEnv
    Set-SmokeEnvValue "MACMINI_DATASET_API_ENABLED" "true"
    Set-SmokeEnvValue "MACMINI_DATASET_API_KEY" $DatasetKey
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_3090_BASE_URL" "http://127.0.0.1:$GatewayPort/v1"
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_3060_BASE_URL" "http://127.0.0.1:$GatewayPort/v1"
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_EMBED_BASE_URL" "http://127.0.0.1:$GatewayPort/api/embed"
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_ALLOWED_HOSTS" "127.0.0.1:$GatewayPort"
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_REQUIRE_AUTH_FOR_REMOTE" "true"
}

function Set-DesktopGpuNodeEnv([int]$GatewayPort) {
    Set-SmokeEnvValue "SPRING_PROFILES_ACTIVE" "desktop-gpu-node"
    Set-SmokeEnvValue "SERVER_PORT" ([string]$Port)
    Set-SmokeEnvValue "MANAGEMENT_SERVER_PORT" ([string]$ManagementPort)
    Set-SmokeEnvValue "SERVER_SSL_ENABLED" "false"
    Set-SmokeEnvValue "DESKTOP_RAG_OPS_LEDGER_ENABLED" "false"
    Set-SmokeEnvValue "DESKTOP_AUTOLEARN_ENABLED" "false"
    Set-SmokeEnvValue "DESKTOP_AUTOLEARN_IDLE_TRIGGER_ENABLED" "false"
    Set-SmokeEnvValue "DESKTOP_AUTOLEARN_RETRAIN_ENABLED" "false"
    Set-SmokeEnvValue "LLM_3090_BASE_URL" "http://127.0.0.1:$GatewayPort/v1"
    Set-SmokeEnvValue "LLM_3060_BASE_URL" "http://127.0.0.1:$GatewayPort/v1"
    Set-SmokeEnvValue "EMBED_3060_BASE_URL" "http://127.0.0.1:$GatewayPort/api/embed"
    Set-SmokeEnvValue "LLM_PROVIDER_GUARD_ALLOWED_HOSTS" "127.0.0.1:$GatewayPort"
    Set-SmokeEnvValue "LLM_PROVIDER_GUARD_REQUIRE_AUTH_FOR_REMOTE" "true"
    Set-SmokeEnvValue "LOCAL_LLM_AUTOSTART" "false"
    Set-SmokeEnvValue "LOCAL_LLM_WARMUP_ENABLED" "false"
    Set-SmokeEnvValue "ONNX_ENABLED" "false"
    Set-SmokeEnvValue "EMBED_CROSS_GPU_FALLBACK_ENABLED" "false"
    Set-SmokeEnvValue "EMBED_FALLBACK_ENABLED" "false"
    Set-SmokeEnvValue "DOMAIN_ALLOWLIST_ADMIN_TOKEN" $AdminToken
    Set-SmokeEnvValue "DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED" "true"
}

function Assert-RealGatewayConfig {
    if (Is-Blank $RealPrimaryChatBaseUrl) {
        throw "[AWX][gpu-gateway-smoke] evidence_needed: real primary 3090 endpoint missing / set MACMINI_DESKTOP_GPU_3090_BASE_URL"
    }
    if (Is-Blank $RealFastBaseUrl) {
        throw "[AWX][gpu-gateway-smoke] evidence_needed: real fast 3060 endpoint missing / set MACMINI_DESKTOP_GPU_3060_BASE_URL"
    }
    if (Is-Blank $RealEmbeddingBaseUrl) {
        throw "[AWX][gpu-gateway-smoke] evidence_needed: real embedding 3060 endpoint missing / set MACMINI_DESKTOP_GPU_EMBED_BASE_URL"
    }
    if (Is-Blank $RealAllowedHosts) {
        throw "[AWX][gpu-gateway-smoke] evidence_needed: real GPU gateway allowlist missing / set MACMINI_DESKTOP_GPU_ALLOWED_HOSTS"
    }

    $requireAuth = $true
    if (-not (Is-Blank $RealRequireAuthForRemote)) {
        $requireAuth = [bool]::Parse($RealRequireAuthForRemote)
    }
    if ($requireAuth -and -not ((Has-UsableSecret $env:LLM_OWNER_TOKEN) -or (Has-UsableSecret $env:LLM_API_KEY))) {
        throw "[AWX][gpu-gateway-smoke] evidence_needed: real GPU gateway auth missing / set LLM_OWNER_TOKEN or LLM_API_KEY"
    }
}

function Set-RealGatewayEnv {
    Assert-RealGatewayConfig
    Set-BaseSmokeEnv
    Set-SmokeEnvValue "MACMINI_DATASET_API_ENABLED" "true"
    Set-SmokeEnvValue "MACMINI_DATASET_API_KEY" $DatasetKey
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_3090_BASE_URL" $RealPrimaryChatBaseUrl
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_3060_BASE_URL" $RealFastBaseUrl
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_EMBED_BASE_URL" $RealEmbeddingBaseUrl
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_ALLOWED_HOSTS" $RealAllowedHosts
    Set-SmokeEnvValue "MACMINI_DESKTOP_GPU_REQUIRE_AUTH_FOR_REMOTE" $(if (Is-Blank $RealRequireAuthForRemote) { "true" } else { $RealRequireAuthForRemote })
}

function Start-SmokeApp([string]$Name) {
    Assert-NoGradleCollision "before-bootRun-$Name"
    $outLog = Join-Path $TempRoot "$Name.out.log"
    $errLog = Join-Path $TempRoot "$Name.err.log"
    $proc = Start-Process -FilePath $Gradle `
        -ArgumentList (Get-SmokeGradleArguments) `
        -WorkingDirectory $Root `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden `
        -PassThru
    return @{ Process = $proc; OutLog = $outLog; ErrLog = $errLog; Name = $Name }
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
    return Redact-Text ((Get-Content -LiteralPath $Path -Tail 80 -ErrorAction SilentlyContinue) -join [Environment]::NewLine)
}

function Wait-ForHttp($App) {
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    $url = "http://127.0.0.1:$ManagementPort/actuator/health"
    while ((Get-Date) -lt $deadline) {
        if ($App.Process.HasExited) {
            throw "[AWX][gpu-gateway-smoke] app exited during $($App.Name)`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
        }
        try {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "[AWX][gpu-gateway-smoke] startup timeout for $($App.Name)`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
}

function Get-SafeContentType([string]$ContentType) {
    if ([string]::IsNullOrWhiteSpace($ContentType)) { return "missing" }
    return ($ContentType -replace '[^A-Za-z0-9/;=.+_-]', '_')
}

function Get-NonJsonBodyClass([string]$Content) {
    if ([string]::IsNullOrWhiteSpace($Content)) { return "empty" }
    if ($Content -match '(?is)^\s*(?:<!doctype\s+html|<html)') {
        if ($Content -match '(?is)(?:action\s*=\s*["'']?/?login|name\s*=\s*["'']?(?:username|password)|type\s*=\s*["'']?password)') {
            return "login_html"
        }
        return "html"
    }
    return "plain_text"
}

function Invoke-Diagnostics([string]$Path) {
    $request = @{
        Uri = "http://127.0.0.1:$Port$Path"
        Method = "GET"
        UseBasicParsing = $true
        TimeoutSec = 15
        Headers = New-DiagnosticsHeaders
    }
    try {
        $resp = Invoke-WebRequest @request
    } catch {
        $response = $_.Exception.Response
        if ($null -eq $response) {
            throw "[AWX][gpu-gateway-smoke] diagnostics-connection-failure category=request_failed adminTokenPresent=true"
        }
        $responseContentType = Get-SafeContentType ([string]$response.ContentType)
        throw "[AWX][gpu-gateway-smoke] diagnostics-http-error status=$([int]$response.StatusCode) contentType=$responseContentType contentLength=$($response.ContentLength) adminTokenPresent=true"
    }

    $content = [string]$resp.Content
    $contentType = [string]$resp.Headers["Content-Type"]
    $safeContentType = Get-SafeContentType $contentType
    $isJson = $contentType -match '(?i)^\s*application/(?:[^;\s]+\+)?json(?:\s*;|$)'
    if (-not $isJson) {
        $bodyClass = Get-NonJsonBodyClass $content
        throw "[AWX][gpu-gateway-smoke] diagnostics-non-json status=$([int]$resp.StatusCode) contentType=$safeContentType contentLength=$($content.Length) bodyClass=$bodyClass adminTokenPresent=true"
    }
    if ([string]::IsNullOrWhiteSpace($content)) {
        throw "[AWX][gpu-gateway-smoke] diagnostics-json-empty status=$([int]$resp.StatusCode) contentType=$safeContentType contentLength=0 adminTokenPresent=true"
    }

    try {
        $body = $content | ConvertFrom-Json
    } catch {
        throw "[AWX][gpu-gateway-smoke] diagnostics-json-invalid status=$([int]$resp.StatusCode) contentType=$safeContentType contentLength=$($content.Length) adminTokenPresent=true"
    }
    return @{ Status = [int]$resp.StatusCode; Body = $body }
}

function Assert-NoSmokeSecretLeak([string]$Payload, $App) {
    $surface = [string]$Payload
    if ($null -ne $App) {
        foreach ($path in @($App.OutLog, $App.ErrLog)) {
            if (Test-Path -LiteralPath $path) {
                $surface += [System.IO.File]::ReadAllText($path)
            }
        }
    }
    if ($surface.Contains($AdminToken)) {
        throw "[AWX][gpu-gateway-smoke] leaked admin token"
    }
    if ($surface -match '(?i)X-Admin-Token\s*:') {
        throw "[AWX][gpu-gateway-smoke] leaked admin-token header"
    }
}

function Assert-Status($Actual, [int]$Expected, [string]$Label) {
    if ([int]$Actual.Status -ne $Expected) {
        throw "[AWX][gpu-gateway-smoke] $Label expected=$Expected actual=$($Actual.Status) body=$(Redact-Text ($Actual.Body | ConvertTo-Json -Depth 12 -Compress))"
    }
}

function Assert-Eq($Actual, $Expected, [string]$Label) {
    if ([string]$Actual -ne [string]$Expected) {
        throw "[AWX][gpu-gateway-smoke] $Label expected=$Expected actual=$Actual"
    }
}

function Test-SimulatedGateway {
    $closedGatewayPort = Get-FreePort
    Set-BlockedGatewayEnv $closedGatewayPort
    $blocked = Start-SmokeApp "blocked-unreachable"
    $script:apps += $blocked
    Wait-ForHttp $blocked
    $blockedPreflight = Invoke-Diagnostics "/api/diagnostics/uaw/autolearn/gpu-gateway/preflight"
    Assert-Status $blockedPreflight 200 "blocked preflight"
    Assert-Eq $blockedPreflight.Body.nodeProfile.node.role "macmini-control-plane" "blocked node role"
    Assert-Eq $blockedPreflight.Body.nodeProfile.node.heavyWorkloadsAllowed $false "blocked heavy workloads"
    Assert-Eq $blockedPreflight.Body.preflight.status "unreachable" "blocked preflight status"
    Assert-Eq $blockedPreflight.Body.preflight.endpoints.primaryChat.status "connection_failed" "blocked endpoint status"
    Stop-SmokeApp $blocked
    Assert-NoSmokeSecretLeak ($blockedPreflight.Body | ConvertTo-Json -Depth 16 -Compress) $blocked

    $gatewayPort = Get-FreePort
    $script:fakeGateway = Start-FakeGpuGateway $gatewayPort
    Set-ReachableGatewayEnv $gatewayPort
    $reachable = Start-SmokeApp "reachable-loopback"
    $script:apps += $reachable
    Wait-ForHttp $reachable
    $reachablePreflight = Invoke-Diagnostics "/api/diagnostics/uaw/autolearn/gpu-gateway/preflight"
    Assert-Status $reachablePreflight 200 "reachable preflight"
    Assert-Eq $reachablePreflight.Body.preflight.status "ok" "reachable preflight status"
    Assert-Eq $reachablePreflight.Body.preflight.configuredCount 3 "reachable configured count"
    Assert-Eq $reachablePreflight.Body.preflight.reachableCount 3 "reachable count"
    Assert-Eq $reachablePreflight.Body.preflight.endpoints.primaryChat.status "ok" "primary status"
    Assert-Eq $reachablePreflight.Body.preflight.endpoints.embedding.status "ok" "embedding status"

    $loop = Invoke-Diagnostics "/api/diagnostics/uaw/autolearn/loop"
    Assert-Status $loop 200 "loop diagnostics"
    Assert-Eq $loop.Body.nodeProfile.node.role "macmini-control-plane" "loop node role"
    Assert-Eq $loop.Body.nodeProfile.node.workloadPolicy "control_plane_curate_observe_schedule" "loop workload policy"
    Assert-Eq $loop.Body.nodeProfile.learningLoop.retrainAllowed $false "loop retrain allowed"
    Assert-Eq $loop.Body.nodeProfile.datasetApi.curationAllowed $true "loop dataset curation"
    Assert-Eq $loop.Body.nodeProfile.gpuGateway.routePolicy "handoff_to_desktop_gpu" "loop gpu route policy"
    Assert-Eq $loop.Body.nodeProfile.selfLearningBridge.gpuGateway.primaryStatus "ok" "loop bridge primary"
    Assert-Eq $loop.Body.nodeProfile.selfLearningBridge.gpuGateway.embeddingStatus "ok" "loop bridge embedding"

    Stop-SmokeApp $reachable
    $stored = ($reachablePreflight.Body | ConvertTo-Json -Depth 16 -Compress) + ($loop.Body | ConvertTo-Json -Depth 16 -Compress)
    Assert-NoSmokeSecretLeak $stored $reachable
    if ($stored.Contains($DatasetKey) -or $stored.Contains($DatasetFile) -or $stored.Contains($TempRoot)) {
        throw "[AWX][gpu-gateway-smoke] diagnostic payload leaked a smoke secret or raw dataset path"
    }

    Stop-FakeGpuGateway $script:fakeGateway
    $script:fakeGateway = $null
    Write-Host "[AWX][gpu-gateway-smoke] OK blockedUnreachable=unreachable reachable=ok node=macmini-control-plane route=handoff_to_desktop_gpu datasetCuration=true retrainAllowed=false"
}

function Test-RealGateway {
    Set-RealGatewayEnv
    $real = Start-SmokeApp "real-gateway"
    $script:apps += $real
    Wait-ForHttp $real

    $realPreflight = Invoke-Diagnostics "/api/diagnostics/uaw/autolearn/gpu-gateway/preflight"
    Assert-Status $realPreflight 200 "real preflight"
    Assert-Eq $realPreflight.Body.nodeProfile.node.role "macmini-control-plane" "real node role"
    Assert-Eq $realPreflight.Body.nodeProfile.node.heavyWorkloadsAllowed $false "real heavy workloads"
    Assert-Eq $realPreflight.Body.nodeProfile.gpuGateway.routePolicy "handoff_to_desktop_gpu" "real route policy"
    Assert-Eq $realPreflight.Body.preflight.status "ok" "real preflight status"
    Assert-Eq $realPreflight.Body.preflight.configuredCount 3 "real configured count"
    Assert-Eq $realPreflight.Body.preflight.reachableCount 3 "real reachable count"
    Assert-Eq $realPreflight.Body.preflight.endpoints.primaryChat.status "ok" "real primary status"
    Assert-Eq $realPreflight.Body.preflight.endpoints.fastHelper.status "ok" "real fast status"
    Assert-Eq $realPreflight.Body.preflight.endpoints.embedding.status "ok" "real embedding status"

    $loop = Invoke-Diagnostics "/api/diagnostics/uaw/autolearn/loop"
    Assert-Status $loop 200 "real loop diagnostics"
    Assert-Eq $loop.Body.nodeProfile.node.workloadPolicy "control_plane_curate_observe_schedule" "real workload policy"
    Assert-Eq $loop.Body.nodeProfile.learningLoop.retrainAllowed $false "real retrain allowed"
    Assert-Eq $loop.Body.nodeProfile.datasetApi.curationAllowed $true "real dataset curation"
    Assert-Eq $loop.Body.nodeProfile.selfLearningBridge.gpuGateway.primaryStatus "ok" "real bridge primary"
    Assert-Eq $loop.Body.nodeProfile.selfLearningBridge.gpuGateway.fastStatus "ok" "real bridge fast"
    Assert-Eq $loop.Body.nodeProfile.selfLearningBridge.gpuGateway.embeddingStatus "ok" "real bridge embedding"

    Stop-SmokeApp $real
    $stored = ($realPreflight.Body | ConvertTo-Json -Depth 16 -Compress) + ($loop.Body | ConvertTo-Json -Depth 16 -Compress)
    Assert-NoSmokeSecretLeak $stored $real
    if ($stored.Contains($DatasetKey) -or $stored.Contains($DatasetFile) -or $stored.Contains($TempRoot)) {
        throw "[AWX][gpu-gateway-smoke] real diagnostic payload leaked a smoke secret or raw dataset path"
    }

    Write-Host "[AWX][gpu-gateway-smoke] OK realGateway=ok configured=3 reachable=3 node=macmini-control-plane route=handoff_to_desktop_gpu datasetCuration=true retrainAllowed=false"
}

function Test-DesktopGpuNode {
    $gatewayPort = Get-FreePort
    $script:fakeGateway = Start-FakeGpuGateway $gatewayPort
    Set-DesktopGpuNodeEnv $gatewayPort
    $desktop = Start-SmokeApp "desktop-gpu-node"
    $script:apps += $desktop
    Wait-ForHttp $desktop

    $preflight = Invoke-Diagnostics "/api/diagnostics/uaw/autolearn/gpu-gateway/preflight"
    Assert-Status $preflight 200 "desktop preflight"
    Assert-Eq $preflight.Body.nodeProfile.node.role "desktop-gpu-executor" "desktop node role"
    Assert-Eq $preflight.Body.nodeProfile.node.executionNode "desktop-rtx3090-rtx3060" "desktop execution node"
    Assert-Eq $preflight.Body.nodeProfile.node.heavyWorkloadsAllowed $true "desktop heavy workloads"
    Assert-Eq $preflight.Body.nodeProfile.node.workloadPolicy "gpu_executor_heavy_workloads" "desktop workload policy"
    Assert-Eq $preflight.Body.nodeProfile.gpuGateway.routePolicy "execute_on_this_node" "desktop gpu route policy"
    Assert-Eq $preflight.Body.preflight.status "ok" "desktop preflight status"
    Assert-Eq $preflight.Body.preflight.configuredCount 3 "desktop configured count"
    Assert-Eq $preflight.Body.preflight.reachableCount 3 "desktop reachable count"
    Assert-Eq $preflight.Body.preflight.endpoints.primaryChat.device "rtx3090" "desktop primary device"
    Assert-Eq $preflight.Body.preflight.endpoints.fastHelper.device "rtx3060" "desktop fast device"
    Assert-Eq $preflight.Body.preflight.endpoints.embedding.device "rtx3060" "desktop embedding device"
    Assert-Eq $preflight.Body.preflight.endpoints.primaryChat.status "ok" "desktop primary status"
    Assert-Eq $preflight.Body.preflight.endpoints.fastHelper.status "ok" "desktop fast status"
    Assert-Eq $preflight.Body.preflight.endpoints.embedding.status "ok" "desktop embedding status"

    $loop = Invoke-Diagnostics "/api/diagnostics/uaw/autolearn/loop"
    Assert-Status $loop 200 "desktop loop diagnostics"
    Assert-Eq $loop.Body.nodeProfile.node.workloadPolicy "gpu_executor_heavy_workloads" "desktop loop workload policy"
    Assert-Eq $loop.Body.nodeProfile.learningLoop.autolearnEnabled $false "desktop autolearn disabled by default"
    Assert-Eq $loop.Body.nodeProfile.learningLoop.retrainAllowed $false "desktop retrain disabled by default"
    Assert-Eq $loop.Body.nodeProfile.selfLearningBridge.gpuGateway.routePolicy "execute_on_this_node" "desktop bridge route"
    Assert-Eq $loop.Body.nodeProfile.selfLearningBridge.gpuGateway.primaryStatus "ok" "desktop bridge primary"
    Assert-Eq $loop.Body.nodeProfile.selfLearningBridge.gpuGateway.fastStatus "ok" "desktop bridge fast"
    Assert-Eq $loop.Body.nodeProfile.selfLearningBridge.gpuGateway.embeddingStatus "ok" "desktop bridge embedding"

    Stop-SmokeApp $desktop
    $stored = ($preflight.Body | ConvertTo-Json -Depth 16 -Compress) + ($loop.Body | ConvertTo-Json -Depth 16 -Compress)
    Assert-NoSmokeSecretLeak $stored $desktop
    if ($stored.Contains($TempRoot)) {
        throw "[AWX][gpu-gateway-smoke] desktop diagnostic payload leaked a smoke temp path"
    }

    Stop-FakeGpuGateway $script:fakeGateway
    $script:fakeGateway = $null
    Write-Host "[AWX][gpu-gateway-smoke] OK desktopGpuNode=ok configured=3 reachable=3 node=desktop-rtx3090-rtx3060 route=execute_on_this_node heavyWorkloads=true autolearnDefault=false"
}

$apps = @()
$fakeGateway = $null
try {
    if ($Mode -eq "simulated" -or $Mode -eq "both" -or $Mode -eq "all") {
        Test-SimulatedGateway
    }
    if ($Mode -eq "desktop" -or $Mode -eq "all") {
        Test-DesktopGpuNode
    }
    if ($Mode -eq "real" -or $Mode -eq "both" -or $Mode -eq "all") {
        Test-RealGateway
    }
} finally {
    foreach ($app in $apps) {
        Stop-SmokeApp $app
    }
    Stop-FakeGpuGateway $fakeGateway
    Clear-SmokeEnv
    Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
