param(
    [int]$Port = 18092,
    [int]$ManagementPort = 18093,
    [int]$NettyPort = 18094,
    [int]$StartupTimeoutSeconds = 160,
    [int]$TimeoutSec = 60,
    [string]$BaseUrl = "",
    [string]$OutputDir = "verification\trace-memory-recovery-synthetic",
    [switch]$AssumeRunning,
    [switch]$StaticOnly
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
$SmokeName = "trace-memory-recovery-synthetic"
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
            throw "[AWX][trace-memory-recovery-smoke] bootRun exited before heartbeat was reachable`n$out`n$err"
        }
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "[AWX][trace-memory-recovery-smoke] evidence_needed: startup timeout waiting for $Url"
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
            throw '[AWX][trace-memory-recovery-smoke] evidence_needed: invalid-base-url reason=local-start-mismatch'
        }
    }
    return $candidate
}

function Get-JsonProp($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Invoke-JsonGet([string]$Url) {
    $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec $TimeoutSec
    $json = $response.Content | ConvertFrom-Json
    return [pscustomobject]@{
        Status = [int]$response.StatusCode
        Content = [string]$response.Content
        Json = $json
    }
}

function Test-RawLeak([string]$Text) {
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    return $Text.Contains("memoryCtx") -or
        $Text.Contains("rawSnapshot.raw") -or
        $Text.Contains("history_context_contamination") -or
        $Text.Contains("all_lines_dropped")
}

if ($StaticOnly) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    Write-Host "[AWX][trace-memory-recovery-smoke] staticOnly=true script=$PSCommandPath"
    exit 0
}

if (-not (Test-Path $Gradle) -and -not $AssumeRunning) {
    throw "[AWX][trace-memory-recovery-smoke] evidence_needed: gradlew.bat missing under $Root"
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null

$scenarioMatrix = @(
    [pscustomobject]@{ Scenario = 'synthetic_context_contamination'; ExpectedReason = 'context_contamination'; ExpectedRecovery = 'ESCALATE'; ExpectedRoute = 'quarantine_retry_failsoft'; ExpectedRisk = 'BREAK'; ExpectedQuarantine = $true },
    [pscustomobject]@{ Scenario = 'synthetic_loader_starvation'; ExpectedReason = 'loader_starvation'; ExpectedRecovery = 'FALLBACK'; ExpectedRoute = 'retry_failsoft'; ExpectedRisk = 'WARN'; ExpectedQuarantine = $false },
    [pscustomobject]@{ Scenario = 'synthetic_after_filter_starvation'; ExpectedReason = 'after_filter_starvation'; ExpectedRecovery = 'FALLBACK'; ExpectedRoute = 'retry_failsoft'; ExpectedRisk = 'WARN'; ExpectedQuarantine = $false },
    [pscustomobject]@{ Scenario = 'synthetic_dropped_breadcrumb'; ExpectedReason = 'dropped_breadcrumb'; ExpectedRecovery = 'FALLBACK'; ExpectedRoute = 'retry_failsoft'; ExpectedRisk = 'WARN'; ExpectedQuarantine = $false },
    [pscustomobject]@{ Scenario = 'synthetic_silent_failure'; ExpectedReason = 'silent_failure'; ExpectedRecovery = 'DEGRADE'; ExpectedRoute = 'retry_failsoft'; ExpectedRisk = 'WARN'; ExpectedQuarantine = $false }
)

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
    $ProjectCacheDir = Join-Path $env:LOCALAPPDATA "awx-gradle-project-cache\desktop-trace-memory-recovery"
    $env:AWX_AGENT_HOST = "desktop"
    $env:AWX_SPLIT_BUILD_OUTPUTS = "1"
    $env:AWX_BUILD_HOST_ID = "desktop-trace-memory-recovery"
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
        Write-Host "[AWX][trace-memory-recovery-smoke] localHttpRollback=true baseScheme=http host=loopback port=$Port serverSsl=false forceHttps=false"
    }
    $env:LOCAL_LLM_AUTOSTART = "false"
    $env:LOCAL_LLM_WARMUP_ENABLED = "false"
    $env:DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED = "false"
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
        $launchPorts = @($Port, $ManagementPort, $NettyPort)
        if ((@($launchPorts | Sort-Object -Unique)).Count -ne @($launchPorts).Count) {
            throw "[AWX][trace-memory-recovery-smoke] port-conflict reason=duplicate-launch-port ports=$($launchPorts -join ',')"
        }
        foreach ($p in @($Port, $ManagementPort, $NettyPort)) {
            $inUse = netstat -ano | Select-String ":$p "
            if ($inUse) {
                throw "[AWX][trace-memory-recovery-smoke] port-conflict port=$p"
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

    $results = New-Object System.Collections.Generic.List[object]
    $rawForScan = New-Object System.Text.StringBuilder
    $triggeredCount = 0
    foreach ($case in $scenarioMatrix) {
        $probe = Invoke-JsonGet "$effectiveBaseUrl/api/diagnostics/trace/memory/self-probe?scenario=$($case.Scenario)"
        [void]$rawForScan.AppendLine($probe.Content)
        $json = $probe.Json
        $triggered = [bool](Get-JsonProp $json "triggered")
        $reason = [string](Get-JsonProp $json "triggerReason")
        $recovery = [string](Get-JsonProp $json "recoveryAction")
        $recoveryRoute = [string](Get-JsonProp $json "recoveryRoute")
        $recoveryRouteDecision = [string](Get-JsonProp $json "recoveryRouteDecision")
        $recoveryPolicyMaxRounds = [int](Get-JsonProp $json "recoveryPolicyMaxRounds")
        $risk = [string](Get-JsonProp $json "risk")
        $quarantine = [bool](Get-JsonProp $json "quarantine")
        $suspectIsolated = [bool](Get-JsonProp $json "suspectPayloadIsolated")
        $cfvmOffered = [bool](Get-JsonProp $json "cfvmOffered")
        if ($triggered) {
            $triggeredCount++
        }
        if ($probe.Status -ne 200 -or
            [string](Get-JsonProp $json "scenario") -ne $case.Scenario -or
            $triggered -ne $true -or
            $reason -ne $case.ExpectedReason -or
            $recovery -ne $case.ExpectedRecovery -or
            $recoveryRoute -ne $case.ExpectedRoute -or
            [string]::IsNullOrWhiteSpace($recoveryRouteDecision) -or
            $recoveryRouteDecision -eq "none" -or
            $recoveryPolicyMaxRounds -le 0 -or
            $risk -ne $case.ExpectedRisk -or
            $quarantine -ne $case.ExpectedQuarantine -or
            $suspectIsolated -ne $true -or
            $cfvmOffered -ne $true) {
            throw "[AWX][trace-memory-recovery-smoke] recovery-matrix-mismatch scenario=$($case.Scenario) status=$($probe.Status) triggered=$triggered reason=$reason recovery=$recovery route=$recoveryRoute routeDecision=$recoveryRouteDecision policyMaxRounds=$recoveryPolicyMaxRounds risk=$risk quarantine=$quarantine isolated=$suspectIsolated cfvmOffered=$cfvmOffered"
        }
        $results.Add([ordered]@{
            scenario = $case.Scenario
            status = $probe.Status
            triggered = $triggered
            reason = $reason
            recoveryAction = $recovery
            recoveryRoute = $recoveryRoute
            recoveryRouteDecision = $recoveryRouteDecision
            recoveryPolicyMaxRounds = $recoveryPolicyMaxRounds
            risk = $risk
            quarantine = $quarantine
            suspectPayloadIsolated = $suspectIsolated
            cfvmOffered = $cfvmOffered
            changedCount = [int](Get-JsonProp $json "changedCount")
            droppedBreadcrumbCount = [int](Get-JsonProp $json "droppedBreadcrumbCount")
        })
    }

    $heartbeat = Invoke-JsonGet "$effectiveBaseUrl/api/chat/ui-heartbeat"
    [void]$rawForScan.AppendLine($heartbeat.Content)
    $traceMemory = Get-JsonProp $heartbeat.Json "traceMemory"
    $heartbeatStatus = [string](Get-JsonProp $traceMemory "status")
    $heartbeatReason = [string](Get-JsonProp $traceMemory "latestReason")
    $heartbeatRisk = [string](Get-JsonProp $traceMemory "latestRisk")
    $heartbeatRecovery = [string](Get-JsonProp $traceMemory "latestRecoveryAction")
    $heartbeatRecoveryRoute = [string](Get-JsonProp $traceMemory "latestRecoveryRoute")
    $heartbeatRecoveryRouteDecision = [string](Get-JsonProp $traceMemory "latestRecoveryRouteDecision")
    $heartbeatSuspectIsolated = [bool](Get-JsonProp $traceMemory "suspectPayloadIsolated")
    $heartbeatRoute = [string](Get-JsonProp $traceMemory "latestTraceRoute")
    if ($heartbeatStatus -ne "WARN" -or
        $heartbeatReason -ne "silent_failure" -or
        $heartbeatRecovery -ne "DEGRADE" -or
        $heartbeatRecoveryRoute -ne "retry_failsoft" -or
        [string]::IsNullOrWhiteSpace($heartbeatRecoveryRouteDecision) -or
        $heartbeatRecoveryRouteDecision -eq "none" -or
        $heartbeatSuspectIsolated -ne $true -or
        $heartbeatRisk -ne "WARN" -or
        $heartbeatRoute -ne "/api/diagnostics/trace/snapshots/latest-trace-memory/html") {
        throw "[AWX][trace-memory-recovery-smoke] heartbeat-mismatch status=$heartbeatStatus reason=$heartbeatReason recovery=$heartbeatRecovery recoveryRoute=$heartbeatRecoveryRoute routeDecision=$heartbeatRecoveryRouteDecision isolated=$heartbeatSuspectIsolated risk=$heartbeatRisk route=$heartbeatRoute"
    }

    $checkpointJson = Invoke-JsonGet "$effectiveBaseUrl/api/diagnostics/trace/snapshots/latest-trace-memory/checkpoints"
    [void]$rawForScan.AppendLine($checkpointJson.Content)
    $checkpointRecovery = Get-JsonProp $checkpointJson.Json "recovery"
    $checkpointCfvm = Get-JsonProp $checkpointJson.Json "cfvm"
    $checkpointRecoveryRouteDecision = [string](Get-JsonProp $checkpointRecovery "routeDecision")
    $checkpointPolicyMaxRounds = [int](Get-JsonProp $checkpointRecovery "policyMaxRounds")
    $checkpointCfvmOffered = [bool](Get-JsonProp $checkpointCfvm "offered")
    if ([int]$checkpointJson.Status -ne 200 -or
        [string]::IsNullOrWhiteSpace($checkpointRecoveryRouteDecision) -or
        $checkpointRecoveryRouteDecision -eq "none" -or
        $checkpointPolicyMaxRounds -le 0 -or
        $checkpointCfvmOffered -ne $true) {
        throw "[AWX][trace-memory-recovery-smoke] checkpoint-json-mismatch status=$($checkpointJson.Status) routeDecision=$checkpointRecoveryRouteDecision policyMaxRounds=$checkpointPolicyMaxRounds cfvmOffered=$checkpointCfvmOffered"
    }

    $debugAiCompact = Invoke-JsonGet "$effectiveBaseUrl/api/diagnostics/debug/ai/compact?limit=80&windowMs=300000"
    [void]$rawForScan.AppendLine($debugAiCompact.Content)
    $compactTraceMemory = Get-JsonProp $debugAiCompact.Json "traceMemoryDiagnostics"
    $compactRouteDecision = [string](Get-JsonProp $compactTraceMemory "routeDecision")
    $compactCfvmOffered = [string](Get-JsonProp $compactTraceMemory "cfvmOffered")
    $compactCfvmPatternId = [string](Get-JsonProp $compactTraceMemory "cfvmPatternId")
    if ([int]$debugAiCompact.Status -ne 200 -or
        [string]::IsNullOrWhiteSpace($compactRouteDecision) -or
        $compactRouteDecision -eq "unavailable" -or
        $compactCfvmOffered -ne "true" -or
        [string]::IsNullOrWhiteSpace($compactCfvmPatternId)) {
        throw "[AWX][trace-memory-recovery-smoke] debug-ai-compact-mismatch status=$($debugAiCompact.Status) routeDecision=$compactRouteDecision cfvmOffered=$compactCfvmOffered cfvmPatternId=$compactCfvmPatternId"
    }

    $html = Invoke-WebRequest -Uri "$effectiveBaseUrl/api/diagnostics/trace/snapshots/latest-trace-memory/html" -UseBasicParsing -TimeoutSec $TimeoutSec
    $htmlText = [string]$html.Content
    [void]$rawForScan.AppendLine($htmlText)
    $htmlRawLeak = Test-RawLeak $htmlText
    $secretPatternHits = ([regex]::Matches($rawForScan.ToString(), 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}')).Count
    if ($htmlRawLeak -or $secretPatternHits -gt 0) {
        throw "[AWX][trace-memory-recovery-smoke] secret-leak-risk htmlRawLeak=$htmlRawLeak secretPatternHits=$secretPatternHits"
    }

    $summary = [ordered]@{
        ok = $true
        scenarioCount = $scenarioMatrix.Count
        triggeredCount = $triggeredCount
        heartbeatStatus = $heartbeatStatus
        heartbeatReason = $heartbeatReason
        heartbeatRecoveryAction = $heartbeatRecovery
        heartbeatRecoveryRoute = $heartbeatRecoveryRoute
        heartbeatRecoveryRouteDecision = $heartbeatRecoveryRouteDecision
        heartbeatSuspectPayloadIsolated = $heartbeatSuspectIsolated
        heartbeatRisk = $heartbeatRisk
        checkpointRecoveryRouteDecision = $checkpointRecoveryRouteDecision
        checkpointPolicyMaxRounds = $checkpointPolicyMaxRounds
        checkpointCfvmOffered = $checkpointCfvmOffered
        compactRouteDecision = $compactRouteDecision
        compactCfvmOffered = $compactCfvmOffered
        compactCfvmPatternId = $compactCfvmPatternId
        latestTraceRoute = $heartbeatRoute
        htmlStatus = [int]$html.StatusCode
        htmlRawLeak = $htmlRawLeak
        secretPatternHits = $secretPatternHits
        report = $ReportPath
    }
    $artifact = [ordered]@{
        summary = $summary
        scenarios = $results
    }
    $artifact | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    ($summary.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) |
        Set-Content -LiteralPath $SummaryPath -Encoding UTF8

    Write-Host "[AWX][trace-memory-recovery-smoke] scenarioCount=$($summary.scenarioCount) triggeredCount=$triggeredCount heartbeatStatus=$heartbeatStatus heartbeatReason=$heartbeatReason heartbeatRecovery=$heartbeatRecovery heartbeatRecoveryRoute=$heartbeatRecoveryRoute heartbeatRecoveryRouteDecision=$heartbeatRecoveryRouteDecision checkpointRecoveryRouteDecision=$checkpointRecoveryRouteDecision checkpointCfvmOffered=$checkpointCfvmOffered compactRouteDecision=$compactRouteDecision compactCfvmOffered=$compactCfvmOffered compactCfvmPatternId=$compactCfvmPatternId heartbeatRisk=$heartbeatRisk heartbeatSuspectPayloadIsolated=$heartbeatSuspectIsolated htmlRawLeak=$htmlRawLeak secretPatternHits=$secretPatternHits report=$ReportPath"
} finally {
    if ($null -ne $app -and $null -ne $app.Process) {
        Stop-ProcessTree -ProcessId ([int]$app.Process.Id)
    }
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], "Process")
    }
}
