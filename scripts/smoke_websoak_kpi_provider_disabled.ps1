param(
    [int]$Port = 18086,
    [int]$ManagementPort = 18087,
    [int]$NettyPort = 18090,
    [int]$StartupTimeoutSeconds = 150,
    [int]$TimeoutSec = 25,
    [int]$Iterations = 1,
    [string]$Query = "RAG evidence starvation debug provider disabled smoke",
    [string]$BaseUrl = "",
    [string]$OutputDir = "verification\websoak-kpi-smoke",
    [switch]$AssumeRunning,
    [switch]$StaticOnly
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
$SmokeName = "websoak-kpi-provider-disabled"
$ProbeKey = "websoak-smoke-" + [guid]::NewGuid().ToString("N")
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
    foreach ($s in @($ProbeKey, $Query)) {
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

function Get-JsonValue($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-BoolJsonValue($Object, [string]$Name) {
    $v = Get-JsonValue $Object $Name
    if ($null -eq $v) { return $false }
    if ($v -is [bool]) { return $v }
    return [string]$v -eq "true"
}

function Get-LongJsonValue($Object, [string]$Name) {
    $v = Get-JsonValue $Object $Name
    if ($null -eq $v) { return 0L }
    try {
        return [long]$v
    } catch [System.FormatException] {
        Write-Host "[AWX][websoak-smoke] numeric parse fallback stage=Get-LongJsonValue errorType=invalid_number"
        return 0L
    }
}

function Wait-ForProbeUi($App, [string]$Url) {
    $deadline = (Get-Date).AddSeconds($StartupTimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        if ($null -ne $App -and $null -ne $App.Process -and $App.Process.HasExited) {
            $out = if (Test-Path $App.OutLog) { Redact-Text ((Get-Content -LiteralPath $App.OutLog -Tail 120) -join [Environment]::NewLine) } else { "" }
            $err = if (Test-Path $App.ErrLog) { Redact-Text ((Get-Content -LiteralPath $App.ErrLog -Tail 120) -join [Environment]::NewLine) } else { "" }
            throw "[AWX][websoak-smoke] bootRun exited before probe UI was reachable`n$out`n$err"
        }
        try {
            Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "[AWX][websoak-smoke] evidence_needed: startup timeout waiting for $Url"
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
        throw '[AWX][websoak-smoke] evidence_needed: invalid-base-url reason=scheme'
    }
    if (-not $uri.IsLoopback) {
        throw '[AWX][websoak-smoke] evidence_needed: invalid-base-url reason=non-loopback'
    }
    $localBaseUrl = "http://127.0.0.1:$RuntimePort"
    $normalizedEffectiveUrl = $uri.AbsoluteUri.TrimEnd('/')
    $normalizedLocalUrl = ([Uri]$localBaseUrl).AbsoluteUri.TrimEnd('/')
    if (-not $UseAssumeRunning -and $normalizedEffectiveUrl -ine $normalizedLocalUrl) {
        throw '[AWX][websoak-smoke] evidence_needed: invalid-base-url reason=local-start-mismatch'
    }
    return $candidate
}

if ($StaticOnly) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null
    Write-Host "[AWX][websoak-smoke] staticOnly=true script=$PSCommandPath"
    exit 0
}

if (-not (Test-Path $Gradle) -and -not $AssumeRunning) {
    throw "[AWX][websoak-smoke] evidence_needed: gradlew.bat missing under $Root"
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null

$envNames = @(
    "AWX_AGENT_HOST",
    "AWX_SPLIT_BUILD_OUTPUTS",
    "AWX_BUILD_HOST_ID",
    "GRADLE_USER_HOME",
    "AWX_PROJECT_CACHE_DIR",
    "SPRING_PROFILES_ACTIVE",
    "SERVER_PORT",
    "MANAGEMENT_SERVER_PORT",
    "NETTY_PORT",
    "SERVER_SSL_ENABLED",
    "APP_PUBLIC_BASE_URL",
    "PUBLIC_BASE_URL",
    "SECURITY_FORCE_HTTPS",
    "PROBE_WEBSOAK_KPI_ENABLED",
    "PROBE_WEBSOAK_KPI_REQUIRE_KEY",
    "PROBE_WEBSOAK_KPI_ALLOW_QUERY_PARAM_KEY",
    "PROBE_WEBSOAK_KPI_KEY",
    "LOCAL_LLM_AUTOSTART",
    "LOCAL_LLM_WARMUP_ENABLED",
    "DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED",
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
    $ProjectCacheDir = Join-Path $env:LOCALAPPDATA "awx-gradle-project-cache\desktop"
    $env:AWX_AGENT_HOST = "desktop"
    $env:AWX_SPLIT_BUILD_OUTPUTS = "1"
    $env:AWX_BUILD_HOST_ID = "desktop"
    $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE ".gradle-awx-desktop"
    $env:AWX_PROJECT_CACHE_DIR = $ProjectCacheDir
    New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCacheDir | Out-Null

    if (-not $AssumeRunning) {
        $env:SPRING_PROFILES_ACTIVE = "local"
        $env:SERVER_PORT = [string]$Port
        $env:MANAGEMENT_SERVER_PORT = [string]$ManagementPort
        $env:NETTY_PORT = [string]$NettyPort
        $env:SERVER_SSL_ENABLED = "false"
        $localHttpBaseUrl = "http://127.0.0.1:$Port"
        $env:APP_PUBLIC_BASE_URL = $localHttpBaseUrl
        $env:PUBLIC_BASE_URL = $localHttpBaseUrl
        $env:SECURITY_FORCE_HTTPS = "false"
        Write-Host "[AWX][websoak-smoke] localHttpRollback=true baseScheme=http host=loopback port=$Port serverSsl=false forceHttps=false"
    }
    $env:PROBE_WEBSOAK_KPI_ENABLED = "true"
    $env:PROBE_WEBSOAK_KPI_REQUIRE_KEY = "true"
    $env:PROBE_WEBSOAK_KPI_ALLOW_QUERY_PARAM_KEY = "false"
    $env:PROBE_WEBSOAK_KPI_KEY = $ProbeKey
    $env:LOCAL_LLM_AUTOSTART = "false"
    $env:LOCAL_LLM_WARMUP_ENABLED = "false"
    $env:DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED = "false"
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
            throw "[AWX][websoak-smoke] port-conflict reason=duplicate-launch-port ports=$($launchPorts -join ',')"
        }
        foreach ($p in @($Port, $ManagementPort, $NettyPort)) {
            $inUse = netstat -ano | Select-String ":$p "
            if ($inUse) {
                throw "[AWX][websoak-smoke] port-conflict port=$p"
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
        Wait-ForProbeUi $app "$effectiveBaseUrl/internal/probe/websoak-kpi/ui"
    }

    $body = @{
        iterations = $Iterations
        topK = 5
        sleepMsBetween = 0
        dbgSearch = $true
        useBaselineQueries = $false
        baselineFile = ""
        queries = @($Query)
    } | ConvertTo-Json -Depth 8

    $headers = @{ "X-Probe-Key" = $ProbeKey }
    $response = Invoke-WebRequest `
        -Uri "$effectiveBaseUrl/internal/probe/websoak-kpi/run" `
        -Method POST `
        -Headers $headers `
        -ContentType "application/json" `
        -Body $body `
        -UseBasicParsing `
        -TimeoutSec $TimeoutSec

    $raw = [string]$response.Content
    $parsed = $raw | ConvertFrom-Json
    $sample = @($parsed.samples)[0]
    if ($null -eq $sample) {
        throw "[AWX][websoak-smoke] evidence_needed: response has no samples"
    }
    $kpi = $sample.kpi
    if ($null -eq $kpi) {
        throw "[AWX][websoak-smoke] evidence_needed: sample has no kpi"
    }

    $providerDisabledCount = 0
    foreach ($name in @("web.naver.providerDisabled", "web.brave.providerDisabled", "web.serpapi.providerDisabled", "web.tavily.providerDisabled")) {
        if (Get-BoolJsonValue $kpi $name) { $providerDisabledCount++ }
    }
    $providerStates = @(
        [string](Get-JsonValue $kpi "provider.naver"),
        [string](Get-JsonValue $kpi "provider.brave"),
        [string](Get-JsonValue $kpi "provider.serpapi"),
        [string](Get-JsonValue $kpi "provider.tavily")
    )
    $providerDisabledOrSkipped = $providerDisabledCount -gt 0 -or (($providerStates -join "|") -match "skipped|disabled|cache_only")
    $cacheOnlyMergedCount = Get-LongJsonValue $kpi "cacheOnly.merged.count"
    $rescueMergeUsed = Get-BoolJsonValue $kpi "rescueMerge.used"
    $starvationTrigger = [string](Get-JsonValue $kpi "starvationFallback.trigger")
    $secretPatternHits = ([regex]::Matches($raw, 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}')).Count
    $rawQueryHits = if ($raw.Contains($Query)) { 1 } else { 0 }
    $probeKeyHits = if ($raw.Contains($ProbeKey)) { 1 } else { 0 }

    $summary = [ordered]@{
        ok = $true
        status = [int]$response.StatusCode
        providerDisabledOrSkipped = $providerDisabledOrSkipped
        providerDisabledCount = $providerDisabledCount
        providerStates = $providerStates
        outCount = Get-LongJsonValue $kpi "outCount"
        rawInputCount = Get-LongJsonValue $kpi "rawInputCount"
        cacheOnlyMergedCount = $cacheOnlyMergedCount
        rescueMergeUsed = $rescueMergeUsed
        starvationTrigger = $starvationTrigger
        secretPatternHits = $secretPatternHits
        rawQueryHits = $rawQueryHits
        probeKeyHits = $probeKeyHits
        report = $ReportPath
    }
    $artifact = [ordered]@{
        summary = $summary
        response = $parsed
    }
    $artifact | ConvertTo-Json -Depth 30 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
    ($summary.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) |
        Set-Content -LiteralPath $SummaryPath -Encoding UTF8

    if (-not $providerDisabledOrSkipped) {
        throw "[AWX][websoak-smoke] evidence_needed: provider disabled/skipped/cache_only state not observed"
    }
    if ($secretPatternHits -ne 0 -or $rawQueryHits -ne 0 -or $probeKeyHits -ne 0) {
        throw "[AWX][websoak-smoke] secret-leak-risk secretPatternHits=$secretPatternHits rawQueryHits=$rawQueryHits probeKeyHits=$probeKeyHits"
    }

    Write-Host "[AWX][websoak-smoke] ok=true status=$($summary.status) providerDisabledOrSkipped=$providerDisabledOrSkipped providerDisabledCount=$providerDisabledCount cacheOnly.merged.count=$cacheOnlyMergedCount rescueMerge.used=$rescueMergeUsed starvationFallback.trigger=$starvationTrigger secretPatternHits=$secretPatternHits rawQueryHits=$rawQueryHits report=$ReportPath"
    exit 0
} finally {
    if ($null -ne $app -and $null -ne $app.Process -and -not $app.Process.HasExited) {
        Stop-ProcessTree -ProcessId ([int]$app.Process.Id)
    }
    foreach ($name in $envNames) {
        [Environment]::SetEnvironmentVariable($name, $previousEnv[$name], "Process")
    }
}
