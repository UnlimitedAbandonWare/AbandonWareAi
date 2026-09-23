param(
    [int]$Port = 18196,
    [int]$ManagementPort = 18197,
    [int]$StartupTimeoutSeconds = 120,
    [switch]$NonDryRun,
    [switch]$ReadinessOnly,
    [switch]$SkipNonDryRunIfNoBackend,
    [switch]$DisabledOnly
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
if (-not (Test-Path $Gradle)) {
    throw "[AWX][graphdb-smoke] evidence_needed: gradlew.bat missing / verify repo root"
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

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-graphdb-smoke-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null

$OwnerToken = "graphdb-smoke-" + [guid]::NewGuid().ToString("N")
$SessionId = "smoke-graphdb-" + [guid]::NewGuid().ToString("N").Substring(0, 12)
$SmokeText = "GraphDB manual learning smoke links Alpha chunk to Beta evidence through an operator fixture."
$SmokeFileText = "GraphDB manual file learning smoke links Gamma upload to Delta evidence through an operator fixture."
$DryRun = -not $NonDryRun.IsPresent -and -not $ReadinessOnly.IsPresent
$OriginalEnv = @{}
$LiveWriteReadPhase = $false

function Redact-Text([string]$Text) {
    if ($null -eq $Text) { return "" }
    $out = $Text
    foreach ($secret in @($OwnerToken, $SessionId, $SmokeText, $SmokeFileText, $TempRoot)) {
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
    Set-SmokeEnv "DOMAIN_ALLOWLIST_ADMIN_TOKEN" $OwnerToken
    Set-SmokeEnv "LLM_OWNER_TOKEN" $OwnerToken
    Set-SmokeEnv "DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED" "true"
    Set-SmokeEnv "DOMAIN_ALLOWLIST_ADMIN_TOKEN_ALLOW_QUERY" "false"
    if ($DisabledOnly) {
        Set-SmokeEnv "GRAPHDB_MANUAL_LEARNING_ENABLED" "false"
    } else {
        Set-SmokeEnv "GRAPHDB_MANUAL_LEARNING_ENABLED" "true"
    }
    Set-SmokeEnv "GRAPHDB_MANUAL_LEARNING_DRY_RUN_DEFAULT" ([string]$DryRun).ToLowerInvariant()
    Set-SmokeEnv "GRAPHDB_MANUAL_LEARNING_VECTOR_ENABLED" "true"
    Set-SmokeEnv "GRAPHDB_MANUAL_LEARNING_NEO4J_ENABLED" "true"
    Set-SmokeEnv "GRAPHDB_MANUAL_LEARNING_BRAIN_STATE_MIRROR_ENABLED" "true"
    Set-SmokeEnv "RAG_BRAIN_STATE_ENABLED" "true"
    Set-SmokeEnv "RAG_BRAIN_STATE_INDEXING_ENABLED" "true"
    Set-SmokeEnv "RAG_BRAIN_STATE_MEANINGFUL_GATE_ENABLED" "false"
    Set-SmokeEnv "MANAGEMENT_HEALTH_NEO4J_ENABLED" "false"
    Set-SmokeEnv "LMS_CORPUS_STARTUP_ENABLED" "false"
    Set-SmokeEnv "LOCAL_LLM_AUTOSTART" "false"
    Set-SmokeEnv "LOCAL_LLM_WARMUP_ENABLED" "false"
    Set-SmokeEnv "NER_LLM_ENABLED" "false"
    if ($DryRun) {
        Set-SmokeEnv "RETRIEVAL_KG_NEO4J_ENABLED" "false"
        Set-SmokeEnv "VECTOR_UPSTASH_ENABLED" "false"
    } else {
        Set-SmokeEnv "RETRIEVAL_KG_NEO4J_ENABLED" "true"
    }
}

function Test-UnsafeSecretValue([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { return $true }
    $normalized = $Value.Trim().ToLowerInvariant()
    return $normalized.StartsWith('${') `
        -or $normalized -eq "dummy" `
        -or $normalized -eq "test" `
        -or $normalized -eq "changeme" `
        -or $normalized -eq "change-me" `
        -or $normalized -eq "null" `
        -or $normalized -eq "none" `
        -or $normalized -eq "password" `
        -or $normalized -eq "sk-local"
}

function Get-FirstSmokeEnvValue([string[]]$Names) {
    foreach ($name in $Names) {
        foreach ($scope in @("Process", "User", "Machine")) {
            $value = [Environment]::GetEnvironmentVariable($name, $scope)
            if (-not (Test-UnsafeSecretValue $value)) {
                return @{ Name = $name; Scope = $scope; Value = $value }
            }
        }
    }
    return @{ Name = ($Names -join " or "); Scope = ""; Value = "" }
}

function Test-CommandPresent([string]$Name) {
    return $null -ne (Get-Command $Name -ErrorAction SilentlyContinue)
}

function Get-Neo4jHostProbe($UriConfig) {
    $uriValue = [string]$UriConfig.Value
    if (Test-UnsafeSecretValue $uriValue) {
        return @{ Parseable = $false; Host = ""; Port = 0; Tcp = $false; Reason = "missing_uri" }
    }
    try {
        $parsed = [System.Uri]$uriValue
    } catch {
        return @{ Parseable = $false; Host = ""; Port = 0; Tcp = $false; Reason = "unparseable_uri" }
    }
    $hostName = $parsed.Host
    $portNumber = if ($parsed.Port -gt 0) { $parsed.Port } else { 7687 }
    if ([string]::IsNullOrWhiteSpace($hostName)) {
        return @{ Parseable = $false; Host = ""; Port = 0; Tcp = $false; Reason = "missing_host" }
    }
    $probe = Test-NetConnection -ComputerName $hostName -Port $portNumber -WarningAction SilentlyContinue
    return @{ Parseable = $true; Host = $hostName; Port = $portNumber; Tcp = [bool]$probe.TcpTestSucceeded; Reason = "" }
}

function Write-Neo4jReadiness($UriConfig, $UserConfig, $PasswordConfig, $HostProbe) {
    $neo4jServices = @(Get-Service -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'neo4j' -or $_.DisplayName -match 'Neo4j' } |
        Select-Object -First 5)
    $serviceSummary = if ($neo4jServices.Count -eq 0) {
        "none"
    } else {
        ($neo4jServices | ForEach-Object { "$($_.Name):$($_.Status)" }) -join ","
    }
    $dockerPresent = Test-CommandPresent "docker"
    $podmanPresent = Test-CommandPresent "podman"
    $neo4jCliPresent = Test-CommandPresent "neo4j"
    $neo4jAdminPresent = Test-CommandPresent "neo4j-admin"
    $uriPresent = -not (Test-UnsafeSecretValue ([string]$UriConfig.Value))
    $userPresent = -not (Test-UnsafeSecretValue ([string]$UserConfig.Value))
    $passwordPresent = -not (Test-UnsafeSecretValue ([string]$PasswordConfig.Value))
    $passwordUnsafeDefault = $userPresent -and $passwordPresent `
        -and ([string]$UserConfig.Value).Trim().ToLowerInvariant() -eq "neo4j" `
        -and ([string]$PasswordConfig.Value).Trim().ToLowerInvariant() -eq "neo4j"

    Write-Host ("[AWX][graphdb-readiness] uriPresent={0} uriSource={1}/{2} userPresent={3} userSource={4}/{5} passwordPresent={6} passwordSource={7}/{8} unsafeDefaultPassword={9} uriParseable={10} endpointHost={11} endpointPort={12} tcp={13} neo4jService={14} neo4jCli={15} neo4jAdmin={16} docker={17} podman={18}" -f `
        $uriPresent, $UriConfig.Name, $UriConfig.Scope, `
        $userPresent, $UserConfig.Name, $UserConfig.Scope, `
        $passwordPresent, $PasswordConfig.Name, $PasswordConfig.Scope, `
        $passwordUnsafeDefault, $HostProbe.Parseable, $HostProbe.Host, $HostProbe.Port, $HostProbe.Tcp, `
        $serviceSummary, $neo4jCliPresent, $neo4jAdminPresent, $dockerPresent, $podmanPresent)
}

function Complete-SkipNonDryRunNoBackend([string]$Reason, $UriConfig, $UserConfig, $PasswordConfig, $HostProbe) {
    if (-not ($NonDryRun.IsPresent -and $SkipNonDryRunIfNoBackend.IsPresent -and -not $ReadinessOnly.IsPresent)) {
        return $false
    }
    $uriPresent = -not (Test-UnsafeSecretValue ([string]$UriConfig.Value))
    $userPresent = -not (Test-UnsafeSecretValue ([string]$UserConfig.Value))
    $passwordPresent = -not (Test-UnsafeSecretValue ([string]$PasswordConfig.Value))
    Write-Host ("[AWX][graphdb-smoke] SKIPPED_NO_BACKEND reason={0} uriPresent={1} uriSource={2}/{3} userPresent={4} userSource={5}/{6} passwordPresent={7} passwordSource={8}/{9} uriParseable={10} endpointHost={11} endpointPort={12} tcp={13}" -f `
        $Reason, $uriPresent, $UriConfig.Name, $UriConfig.Scope, `
        $userPresent, $UserConfig.Name, $UserConfig.Scope, `
        $passwordPresent, $PasswordConfig.Name, $PasswordConfig.Scope, `
        $HostProbe.Parseable, $HostProbe.Host, $HostProbe.Port, $HostProbe.Tcp)
    return $true
}

function Assert-NonDryRunPreflight {
    if ($DryRun) { return $true }

    $neo4jUriConfig = Get-FirstSmokeEnvValue @("RETRIEVAL_KG_NEO4J_URI", "NEO4J_URI")
    $neo4jUserConfig = Get-FirstSmokeEnvValue @("RETRIEVAL_KG_NEO4J_USER", "NEO4J_USER")
    $neo4jPasswordConfig = Get-FirstSmokeEnvValue @("RETRIEVAL_KG_NEO4J_PASSWORD", "NEO4J_PASSWORD")
    $neo4jUri = [string]$neo4jUriConfig.Value
    $neo4jUser = [string]$neo4jUserConfig.Value
    $neo4jPassword = [string]$neo4jPasswordConfig.Value
    $hostProbe = Get-Neo4jHostProbe $neo4jUriConfig
    Write-Neo4jReadiness $neo4jUriConfig $neo4jUserConfig $neo4jPasswordConfig $hostProbe
    if (Test-UnsafeSecretValue $neo4jUri) {
        if (Complete-SkipNonDryRunNoBackend "missing_uri" $neo4jUriConfig $neo4jUserConfig $neo4jPasswordConfig $hostProbe) { return $false }
        throw "[AWX][graphdb-smoke] evidence_needed: -NonDryRun requires RETRIEVAL_KG_NEO4J_URI or NEO4J_URI / verify env presence without printing values"
    }
    if (Test-UnsafeSecretValue $neo4jUser) {
        if (Complete-SkipNonDryRunNoBackend "missing_user" $neo4jUriConfig $neo4jUserConfig $neo4jPasswordConfig $hostProbe) { return $false }
        throw "[AWX][graphdb-smoke] evidence_needed: -NonDryRun requires RETRIEVAL_KG_NEO4J_USER or NEO4J_USER / verify env presence without printing values"
    }
    if ((Test-UnsafeSecretValue $neo4jPassword) -or ($neo4jUser.Trim().ToLowerInvariant() -eq "neo4j" -and $neo4jPassword.Trim().ToLowerInvariant() -eq "neo4j")) {
        if (Complete-SkipNonDryRunNoBackend "missing_or_placeholder_password" $neo4jUriConfig $neo4jUserConfig $neo4jPasswordConfig $hostProbe) { return $false }
        throw "[AWX][graphdb-smoke] evidence_needed: -NonDryRun requires a non-placeholder RETRIEVAL_KG_NEO4J_PASSWORD or NEO4J_PASSWORD / verify secret presence without printing it"
    }
    if ($neo4jUriConfig.Scope -ne "Process") {
        Set-SmokeEnv "RETRIEVAL_KG_NEO4J_URI" $neo4jUri
    }
    if ($neo4jUserConfig.Scope -ne "Process") {
        Set-SmokeEnv "RETRIEVAL_KG_NEO4J_USER" $neo4jUser
    }
    if ($neo4jPasswordConfig.Scope -ne "Process") {
        Set-SmokeEnv "RETRIEVAL_KG_NEO4J_PASSWORD" $neo4jPassword
    }

    if (-not $hostProbe.Parseable -and $hostProbe.Reason -eq "unparseable_uri") {
        if (Complete-SkipNonDryRunNoBackend "unparseable_uri" $neo4jUriConfig $neo4jUserConfig $neo4jPasswordConfig $hostProbe) { return $false }
        throw "[AWX][graphdb-smoke] evidence_needed: Neo4j URI env must be parseable source=$($neo4jUriConfig.Name) / verify with [uri] cast without printing secrets"
    }
    if (-not $hostProbe.Parseable -and $hostProbe.Reason -eq "missing_host") {
        if (Complete-SkipNonDryRunNoBackend "missing_host" $neo4jUriConfig $neo4jUserConfig $neo4jPasswordConfig $hostProbe) { return $false }
        throw "[AWX][graphdb-smoke] evidence_needed: Neo4j URI must include host source=$($neo4jUriConfig.Name) / verify with [uri] cast without printing secrets"
    }
    if (-not $hostProbe.Tcp) {
        if (Complete-SkipNonDryRunNoBackend "bolt_unreachable" $neo4jUriConfig $neo4jUserConfig $neo4jPasswordConfig $hostProbe) { return $false }
        throw "[AWX][graphdb-smoke] evidence_needed: Neo4j Bolt endpoint unreachable host=$($hostProbe.Host) port=$($hostProbe.Port) / verify with Test-NetConnection -ComputerName $($hostProbe.Host) -Port $($hostProbe.Port)"
    }
    return $true
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
            throw "[AWX][graphdb-smoke] app exited during startup`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
        }
        try {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "[AWX][graphdb-smoke] startup timeout`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
}

function Wait-ForGraphDbEndpoint($App) {
    $deadline = (Get-Date).AddSeconds(30)
    while ((Get-Date) -lt $deadline) {
        if ($App.Process.HasExited) {
            throw "[AWX][graphdb-smoke] app exited before graphdb endpoint readiness`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
        }
        try {
            $status = Invoke-GraphDbApi "GET" "/api/admin/graph/learn-status" $null
            if ([int]$status.Status -eq 200) {
                return
            }
        } catch {
            # The management port can report healthy before the application connector accepts endpoint traffic.
        }
        Start-Sleep -Seconds 2
    }
    throw "[AWX][graphdb-smoke] graphdb endpoint readiness timeout`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
}

function Invoke-GraphDbApi([string]$Method, [string]$Path, $Body) {
    $headers = @{
        "X-Owner-Token" = $OwnerToken
    }
    $args = @{
        Uri = "http://127.0.0.1:$Port$Path"
        Method = $Method
        Headers = $headers
        UseBasicParsing = $true
        TimeoutSec = 25
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

function Invoke-GraphDbMultipartApi([string]$Path, [hashtable]$Fields, [string]$FileName, [string]$ContentType, [byte[]]$Bytes) {
    $client = [System.Net.Http.HttpClient]::new()
    $multipart = $null
    try {
        $client.Timeout = [TimeSpan]::FromSeconds(25)
        $client.DefaultRequestHeaders.Add("X-Owner-Token", $OwnerToken)
        $multipart = [System.Net.Http.MultipartFormDataContent]::new()
        foreach ($entry in $Fields.GetEnumerator()) {
            if ($null -ne $entry.Value) {
                $multipart.Add([System.Net.Http.StringContent]::new([string]$entry.Value), [string]$entry.Key)
            }
        }
        $fileContent = [System.Net.Http.ByteArrayContent]::new($Bytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse($ContentType)
        $multipart.Add($fileContent, "file", $FileName)

        $resp = $client.PostAsync("http://127.0.0.1:$Port$Path", $multipart).GetAwaiter().GetResult()
        $content = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $parsed = $null
        if (-not [string]::IsNullOrWhiteSpace($content)) {
            try { $parsed = $content | ConvertFrom-Json } catch { $parsed = $content }
        }
        return @{ Status = [int]$resp.StatusCode; Body = $parsed; Raw = [string]$content }
    } finally {
        if ($null -ne $multipart) {
            $multipart.Dispose()
        }
        $client.Dispose()
    }
}

function Has-Property($Object, [string]$Name) {
    return $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name]
}

function Assert-Status($Actual, [int]$Expected, [string]$Label) {
    if ([int]$Actual.Status -ne $Expected) {
        throw "[AWX][graphdb-smoke] $Label expected=$Expected actual=$($Actual.Status) body=$(Redact-Text $Actual.Raw)"
    }
}

function Assert-StatusAny($Actual, [int[]]$Expected, [string]$Label) {
    if ($Expected -notcontains [int]$Actual.Status) {
        throw "[AWX][graphdb-smoke] $Label expectedOneOf=$($Expected -join ',') actual=$($Actual.Status) body=$(Redact-Text $Actual.Raw)"
    }
}

function Assert-DisabledReport($Actual, [int]$Expected, [string]$Label) {
    Assert-Status $Actual $Expected $Label
    Assert-NoRawLeak $Actual.Raw $Label
    if ($Actual.Body.lane -ne "graphdb_manual_learning" `
            -or $Actual.Body.status -ne "disabled" `
            -or [string]$Actual.Body.disabledReason -ne "route_disabled") {
        throw "[AWX][graphdb-smoke] $Label must report disabled route_disabled"
    }
    if ($Actual.Body.manifest.routeStatus -ne "disabled" `
            -or [string]$Actual.Body.manifest.routeDisabledReason -ne "route_disabled") {
        throw "[AWX][graphdb-smoke] $Label manifest must report route disabled"
    }
    if ($Actual.Body.manifest.featureFlagProperty -ne "graphdb.manual-learning.enabled" `
            -or $Actual.Body.manifest.featureFlagEnv -ne "GRAPHDB_MANUAL_LEARNING_ENABLED") {
        throw "[AWX][graphdb-smoke] $Label missing feature flag manifest"
    }
    if ($Actual.Body.manifest.rawTextIncluded -ne $false `
            -or $Actual.Body.manifest.rawIdentifiersIncluded -ne $false `
            -or $Actual.Body.manifest.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] $Label disabled manifest must stay redacted"
    }
    if ($Actual.Body.graphDb.writeBoundary -ne "graphdb_manual_learning" `
            -or $Actual.Body.graphDb.readBoundary -ne "graphdb_manual_learning" `
            -or $Actual.Body.graphDb.rawTextIncluded -ne $false `
            -or $Actual.Body.graphDb.rawEntityValuesIncluded -ne $false `
            -or $Actual.Body.graphDb.rawIdentifiersIncluded -ne $false `
            -or $Actual.Body.graphDb.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] $Label disabled graphDb boundary must stay visible and redacted"
    }
}

function Assert-NoRawLeak([string]$Raw, [string]$Label) {
    if (-not [string]::IsNullOrWhiteSpace($Raw) -and $Raw.Contains($SmokeText)) {
        throw "[AWX][graphdb-smoke] $Label leaked raw smoke text"
    }
    if (-not [string]::IsNullOrWhiteSpace($Raw) -and $Raw.Contains($SmokeFileText)) {
        throw "[AWX][graphdb-smoke] $Label leaked raw smoke file text"
    }
    if (-not [string]::IsNullOrWhiteSpace($Raw) -and $Raw.Contains($OwnerToken)) {
        throw "[AWX][graphdb-smoke] $Label leaked owner token"
    }
    if (-not [string]::IsNullOrWhiteSpace($Raw) -and $Raw.Contains($SessionId)) {
        throw "[AWX][graphdb-smoke] $Label leaked raw session id"
    }
}

function Assert-HashToken([object]$Value, [string]$Label) {
    $raw = [string]$Value
    if ([string]::IsNullOrWhiteSpace($raw) -or -not ($raw -match '^[a-f0-9]{12,64}$')) {
        throw "[AWX][graphdb-smoke] $Label must be hash-only value=$raw"
    }
}

function Assert-CommunityId([object]$Value, [string]$Label) {
    $raw = [string]$Value
    if ([string]::IsNullOrWhiteSpace($raw) -or -not ($raw -match '^community:[a-f0-9]{12,64}$')) {
        throw "[AWX][graphdb-smoke] $Label must be hash-only community id value=$raw"
    }
}

function Assert-GraphDbReadRedaction($Evidence) {
    if ($null -eq $Evidence.Body.graphDb) {
        throw "[AWX][graphdb-smoke] evidence missing graphDb"
    }
    Assert-NoRawLeak $Evidence.Raw "evidence"
    if ($Evidence.Body.graphDb.writeBoundary -ne "graphdb_manual_learning" `
            -or $Evidence.Body.graphDb.readBoundary -ne "graphdb_manual_learning") {
        throw "[AWX][graphdb-smoke] evidence missing graphdb manual write/read boundary"
    }
    if ($Evidence.Body.graphDb.rawTextIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] evidence rawTextIncluded must be false"
    }
    if ($Evidence.Body.graphDb.rawEntityValuesIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] evidence rawEntityValuesIncluded must be false"
    }
    if ($Evidence.Body.graphDb.rawIdentifiersIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] evidence rawIdentifiersIncluded must be false"
    }
    if ($Evidence.Body.graphDb.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] evidence rawSecretsIncluded must be false"
    }
    foreach ($candidate in @($Evidence.Body.graphDb.candidates)) {
        if ($null -eq $candidate) { continue }
        if (Has-Property $candidate "entities") {
            throw "[AWX][graphdb-smoke] evidence leaked raw entities field"
        }
        if (Has-Property $candidate "chunkId") {
            throw "[AWX][graphdb-smoke] evidence leaked raw chunkId"
        }
        if (Has-Property $candidate "sessionId") {
            throw "[AWX][graphdb-smoke] evidence leaked raw sessionId"
        }
        if (-not (Has-Property $candidate "chunkHash")) {
            throw "[AWX][graphdb-smoke] evidence missing chunkHash"
        }
        Assert-HashToken $candidate.chunkHash "evidence chunkHash"
        if (-not (Has-Property $candidate "sessionHash")) {
            throw "[AWX][graphdb-smoke] evidence missing sessionHash"
        }
        Assert-HashToken $candidate.sessionHash "evidence sessionHash"
        if (Has-Property $candidate "textHash") {
            Assert-HashToken $candidate.textHash "evidence textHash"
        }
        if (([int]$candidate.entityCount -gt 0) -and -not (Has-Property $candidate "entityHashes")) {
            throw "[AWX][graphdb-smoke] evidence missing entityHashes for candidate"
        }
        foreach ($entityHash in @($candidate.entityHashes)) {
            Assert-HashToken $entityHash "evidence entityHash"
        }
        foreach ($hop in @($candidate.hops)) {
            if ($null -eq $hop) { continue }
            if (Has-Property $hop "target") {
                throw "[AWX][graphdb-smoke] evidence leaked raw hop target"
            }
            if (-not (Has-Property $hop "targetHash")) {
                throw "[AWX][graphdb-smoke] evidence missing hop targetHash"
            }
            Assert-HashToken $hop.targetHash "evidence hop targetHash"
            if (Has-Property $hop "pathHash") {
                Assert-HashToken $hop.pathHash "evidence hop pathHash"
            }
            if (Has-Property $hop "connectorHash") {
                Assert-HashToken $hop.connectorHash "evidence hop connectorHash"
            }
        }
    }
}

function Assert-ManualRelationSourcesFromEvidence($Evidence) {
    foreach ($candidate in @($Evidence.Body.graphDb.candidates)) {
        if ($null -eq $candidate) { continue }
        foreach ($hop in @($candidate.hops)) {
            if ($null -eq $hop) { continue }
            if (-not (Has-Property $hop "relationSource")) {
                throw "[AWX][graphdb-smoke] evidence hop missing relationSource"
            }
            if ([string]$hop.relationSource -ne "graphdb_manual_learning") {
                throw "[AWX][graphdb-smoke] evidence hop crossed lane relationSource=$($hop.relationSource)"
            }
        }
    }
}

function Assert-LiveEvidenceReadBack($Evidence) {
    if ([int]$Evidence.Body.graphDb.returnedCount -lt 1) {
        throw "[AWX][graphdb-smoke] nonDryRun expected evidence returnedCount>=1 actual=$($Evidence.Body.graphDb.returnedCount)"
    }
    $candidate = @($Evidence.Body.graphDb.candidates) | Select-Object -First 1
    if ($null -eq $candidate) {
        throw "[AWX][graphdb-smoke] nonDryRun evidence missing first candidate"
    }
    if ([string]$candidate.sourceTag -ne "GRAPHDB_MANUAL" `
            -or [string]$candidate.docType -ne "GRAPHDB_MANUAL_LEARNING" `
            -or [string]$candidate.ingestLane -ne "graphdb_manual_learning") {
        throw "[AWX][graphdb-smoke] nonDryRun evidence crossed manual lane sourceTag=$($candidate.sourceTag) docType=$($candidate.docType) ingestLane=$($candidate.ingestLane)"
    }
    if ([string]::IsNullOrWhiteSpace([string]$candidate.textHash) `
            -or [int]$candidate.textLength -lt 1) {
        throw "[AWX][graphdb-smoke] nonDryRun evidence missing text hash/length"
    }
    if ([int]$candidate.entityCount -gt 0 -and @($candidate.entityHashes).Count -lt 1) {
        throw "[AWX][graphdb-smoke] nonDryRun evidence missing entityHashes for entityCount=$($candidate.entityCount)"
    }
}

function Assert-ManualRelationSourcesFromSummary($Summary) {
    if ($null -eq $Summary.Body.graphDb) {
        throw "[AWX][graphdb-smoke] summary missing graphDb"
    }
    if ($Summary.Body.graphDb.writeBoundary -ne "graphdb_manual_learning" `
            -or $Summary.Body.graphDb.readBoundary -ne "graphdb_manual_learning" `
            -or $Summary.Body.graphDb.summaryBoundary -ne "graphdb_manual_learning_community_summary" `
            -or $Summary.Body.graphDb.multiHopBoundary -ne "graphdb_manual_learning_multi_hop_evidence" `
            -or $Summary.Body.graphDb.projectionSource -ne "Neo4jKgChunkWriter.readManualEvidence") {
        throw "[AWX][graphdb-smoke] summary missing graphdb manual projection boundary"
    }
    if ($Summary.Body.graphDb.brainStateCoupled -ne $false `
            -or $Summary.Body.graphDb.queryTimeRetrievalCoupled -ne $false `
            -or $Summary.Body.graphDb.queryTimeAnchorMapCoupled -ne $false) {
        throw "[AWX][graphdb-smoke] summary must not couple to BrainState fallback or query-time GraphRAG"
    }
    if ($Summary.Body.graphDb.rawTextIncluded -ne $false `
            -or $Summary.Body.graphDb.rawEntityValuesIncluded -ne $false `
            -or $Summary.Body.graphDb.rawIdentifiersIncluded -ne $false `
            -or $Summary.Body.graphDb.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] summary graphdb projection must stay redacted"
    }
    foreach ($item in @($Summary.Body.graphDb.multiHopEvidence)) {
        if ($null -eq $item) { continue }
        if (Has-Property $item "target") {
            throw "[AWX][graphdb-smoke] summary leaked raw multi-hop target"
        }
        if (-not (Has-Property $item "targetHash")) {
            throw "[AWX][graphdb-smoke] summary multi-hop missing targetHash"
        }
        Assert-HashToken $item.targetHash "summary multi-hop targetHash"
        if (-not (Has-Property $item "connectorHash")) {
            throw "[AWX][graphdb-smoke] summary multi-hop missing connectorHash"
        }
        Assert-HashToken $item.connectorHash "summary multi-hop connectorHash"
        if (Has-Property $item "pathHash") {
            Assert-HashToken $item.pathHash "summary multi-hop pathHash"
        }
        if (-not (Has-Property $item "relationSource")) {
            throw "[AWX][graphdb-smoke] summary multi-hop missing relationSource"
        }
        if ([string]$item.relationSource -ne "graphdb_manual_learning") {
            throw "[AWX][graphdb-smoke] summary multi-hop crossed lane relationSource=$($item.relationSource)"
        }
    }
}

function Assert-LiveSummaryReadBack($Summary) {
    if ([int]$Summary.Body.graphDb.communityCount -lt 1) {
        throw "[AWX][graphdb-smoke] nonDryRun summary expected communityCount>=1 actual=$($Summary.Body.graphDb.communityCount)"
    }
    if (@($Summary.Body.graphDb.communities).Count -lt 1) {
        throw "[AWX][graphdb-smoke] nonDryRun summary missing communities"
    }
    foreach ($community in @($Summary.Body.graphDb.communities)) {
        if ($null -eq $community) { continue }
        Assert-CommunityId $community.communityId "summary communityId"
        foreach ($chunkHash in @($community.chunkHashes)) {
            Assert-HashToken $chunkHash "summary community chunkHash"
        }
        foreach ($textHash in @($community.textHashes)) {
            Assert-HashToken $textHash "summary community textHash"
        }
        foreach ($entityHash in @($community.entityHashes)) {
            Assert-HashToken $entityHash "summary community entityHash"
        }
        foreach ($hopHash in @($community.hopHashes)) {
            Assert-HashToken $hopHash "summary community hopHash"
        }
    }
}

function Assert-SnapshotProjection($Snapshot) {
    if ($null -eq $Snapshot.Body.graphDb) {
        throw "[AWX][graphdb-smoke] snapshot missing graphDb"
    }
    Assert-NoRawLeak $Snapshot.Raw "learn-snapshot"
    if ($Snapshot.Body.graphDb.writeBoundary -ne "graphdb_manual_learning" `
            -or $Snapshot.Body.graphDb.readBoundary -ne "graphdb_manual_learning" `
            -or $Snapshot.Body.graphDb.summaryBoundary -ne "graphdb_manual_learning_community_summary" `
            -or $Snapshot.Body.graphDb.multiHopBoundary -ne "graphdb_manual_learning_multi_hop_evidence") {
        throw "[AWX][graphdb-smoke] snapshot missing graphdb manual projection boundaries"
    }
    if ($Snapshot.Body.graphDb.snapshotBoundary -ne "graphdb_manual_learning_brain_snapshot") {
        throw "[AWX][graphdb-smoke] snapshot missing graphdb manual boundary"
    }
    if ($Snapshot.Body.graphDb.brainStateCoupled -ne $false `
            -or $Snapshot.Body.graphDb.queryTimeRetrievalCoupled -ne $false `
            -or $Snapshot.Body.graphDb.queryTimeAnchorMapCoupled -ne $false) {
        throw "[AWX][graphdb-smoke] snapshot must not couple to BrainState fallback or query-time GraphRAG"
    }
    if ($Snapshot.Body.graphDb.rawTextIncluded -ne $false `
            -or $Snapshot.Body.graphDb.rawEntityValuesIncluded -ne $false `
            -or $Snapshot.Body.graphDb.rawIdentifiersIncluded -ne $false `
            -or $Snapshot.Body.graphDb.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] snapshot graphdb projection must stay redacted"
    }
    if ($null -eq $Snapshot.Body.graphDb.snapshot) {
        throw "[AWX][graphdb-smoke] snapshot missing projection payload"
    }
    if ($Snapshot.Body.graphDb.snapshot.writeBoundary -ne "graphdb_manual_learning" `
            -or $Snapshot.Body.graphDb.snapshot.readBoundary -ne "graphdb_manual_learning" `
            -or $Snapshot.Body.graphDb.snapshot.summaryBoundary -ne "graphdb_manual_learning_community_summary" `
            -or $Snapshot.Body.graphDb.snapshot.multiHopBoundary -ne "graphdb_manual_learning_multi_hop_evidence") {
        throw "[AWX][graphdb-smoke] snapshot payload missing graphdb manual projection boundaries"
    }
    if ($Snapshot.Body.graphDb.snapshot.brainStateCoupled -ne $false `
            -or $Snapshot.Body.graphDb.snapshot.queryTimeRetrievalCoupled -ne $false `
            -or $Snapshot.Body.graphDb.snapshot.queryTimeAnchorMapCoupled -ne $false) {
        throw "[AWX][graphdb-smoke] snapshot payload must not couple to BrainState fallback or query-time GraphRAG"
    }
    if ($Snapshot.Body.graphDb.snapshot.rawTextIncluded -ne $false `
            -or $Snapshot.Body.graphDb.snapshot.rawEntityValuesIncluded -ne $false `
            -or $Snapshot.Body.graphDb.snapshot.rawIdentifiersIncluded -ne $false `
            -or $Snapshot.Body.graphDb.snapshot.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] snapshot payload projection must stay redacted"
    }
    foreach ($source in @($Snapshot.Body.graphDb.snapshot.relationSources)) {
        if ($null -eq $source -or [string]::IsNullOrWhiteSpace([string]$source)) { continue }
        if ([string]$source -ne "graphdb_manual_learning") {
            throw "[AWX][graphdb-smoke] snapshot crossed lane relationSource=$source"
        }
    }
    if (@($Snapshot.Body.graphDb.snapshot.pathHashes).Count -gt 0 `
            -and @($Snapshot.Body.graphDb.snapshot.connectorHashes).Count -lt 1) {
        throw "[AWX][graphdb-smoke] snapshot payload missing connectorHashes for multi-hop projection"
    }
    foreach ($communityId in @($Snapshot.Body.graphDb.snapshot.communityIds)) {
        Assert-CommunityId $communityId "snapshot communityId"
    }
    foreach ($chunkHash in @($Snapshot.Body.graphDb.snapshot.chunkHashes)) {
        Assert-HashToken $chunkHash "snapshot chunkHash"
    }
    foreach ($textHash in @($Snapshot.Body.graphDb.snapshot.textHashes)) {
        Assert-HashToken $textHash "snapshot textHash"
    }
    foreach ($pathHash in @($Snapshot.Body.graphDb.snapshot.pathHashes)) {
        Assert-HashToken $pathHash "snapshot pathHash"
    }
    foreach ($connectorHash in @($Snapshot.Body.graphDb.snapshot.connectorHashes)) {
        Assert-HashToken $connectorHash "snapshot connectorHash"
    }
}

function Assert-LiveSnapshotReadBack($Snapshot) {
    if ([int]$Snapshot.Body.graphDb.snapshot.candidateCount -lt 1) {
        throw "[AWX][graphdb-smoke] nonDryRun snapshot expected candidateCount>=1 actual=$($Snapshot.Body.graphDb.snapshot.candidateCount)"
    }
}

$app = $null
$Succeeded = $false
try {
    if ($ReadinessOnly) {
        [void](Assert-NonDryRunPreflight)
        Write-Host "[AWX][graphdb-readiness] OK nonDryRunPreflight=true"
        $Succeeded = $true
        return
    }
    $preflightReady = Assert-NonDryRunPreflight
    if (-not $preflightReady) {
        $Succeeded = $true
        return
    }
    $app = Start-SmokeApp
    Wait-ForHttp $app
    Wait-ForGraphDbEndpoint $app

    if ($DisabledOnly) {
        $disabledStatus = Invoke-GraphDbApi "GET" "/api/admin/graph/learn-status" $null
        Assert-DisabledReport $disabledStatus 200 "disabled-status"

        $disabledText = Invoke-GraphDbApi "POST" "/api/admin/graph/learn-text" @{
            sessionId = $SessionId
            text = $SmokeText
            domain = "SMOKE_GRAPHDB"
            dryRun = $true
        }
        Assert-DisabledReport $disabledText 503 "disabled-learn-text"

        $disabledFile = Invoke-GraphDbMultipartApi "/api/admin/graph/learn-file" @{
            sessionId = "$SessionId-file"
            domain = "SMOKE_GRAPHDB"
            dryRun = "true"
        } "manual-smoke.txt" "text/plain" ([System.Text.Encoding]::UTF8.GetBytes($SmokeFileText))
        Assert-DisabledReport $disabledFile 503 "disabled-learn-file"

        $disabledEvidence = Invoke-GraphDbApi "GET" "/api/admin/graph/learn-evidence?domain=SMOKE_GRAPHDB&limit=5" $null
        Assert-DisabledReport $disabledEvidence 503 "disabled-learn-evidence"

        $disabledSummary = Invoke-GraphDbApi "GET" "/api/admin/graph/learn-summary?domain=SMOKE_GRAPHDB&limit=5" $null
        Assert-DisabledReport $disabledSummary 503 "disabled-learn-summary"

        $disabledSnapshot = Invoke-GraphDbApi "GET" "/api/admin/graph/learn-snapshot?domain=SMOKE_GRAPHDB&limit=5" $null
        Assert-DisabledReport $disabledSnapshot 503 "disabled-learn-snapshot"

        Write-Host "[AWX][graphdb-smoke] OK disabledOnly=true status=$($disabledStatus.Status) learnText=$($disabledText.Status) learnFile=$($disabledFile.Status) evidence=$($disabledEvidence.Status) summary=$($disabledSummary.Status) snapshot=$($disabledSnapshot.Status) rawLeak=false"
        $Succeeded = $true
        return
    }

    $status = Invoke-GraphDbApi "GET" "/api/admin/graph/learn-status" $null
    Assert-Status $status 200 "status"
    Assert-NoRawLeak $status.Raw "status"
    if ($DryRun) {
        if ($status.Body.status -ne "dry_run_ready" `
                -or $status.Body.manifest.routeStatus -ne "dry_run_ready" `
                -or [string]$status.Body.manifest.routeDisabledReason -ne "") {
            throw "[AWX][graphdb-smoke] dryRun status must report dry_run_ready status=$($status.Body.status) routeStatus=$($status.Body.manifest.routeStatus) reason=$($status.Body.manifest.routeDisabledReason)"
        }
    } else {
        if ($status.Body.status -ne "ready_unverified" `
                -or $status.Body.manifest.routeStatus -ne "ready_unverified" `
                -or [string]$status.Body.manifest.routeDisabledReason -ne "non_dry_run_live_proof_required") {
            throw "[AWX][graphdb-smoke] nonDryRun status must report ready_unverified until live proof completes status=$($status.Body.status) routeStatus=$($status.Body.manifest.routeStatus) reason=$($status.Body.manifest.routeDisabledReason)"
        }
    }
    if ($status.Body.manifest.adminBoundary -ne "/api/admin/graph") {
        throw "[AWX][graphdb-smoke] status missing adminBoundary"
    }
    if ($status.Body.manifest.authGuardBoundary -ne "AdminTokenGuardFilter+ROLE_ADMIN" `
            -or $status.Body.manifest.authTokenValuesIncluded -ne $false `
            -or $status.Body.manifest.adminTokenHeaderAccepted -ne $true `
            -or $status.Body.manifest.ownerTokenHeaderAccepted -ne $true) {
        throw "[AWX][graphdb-smoke] status missing redacted admin/owner-token guard contract"
    }
    if ($status.Body.manifest.featureFlagProperty -ne "graphdb.manual-learning.enabled" `
            -or $status.Body.manifest.featureFlagEnv -ne "GRAPHDB_MANUAL_LEARNING_ENABLED") {
        throw "[AWX][graphdb-smoke] status missing graphdb feature flag boundary"
    }
    if ($status.Body.manifest.backendConfigPrefix -ne "retrieval.kg.neo4j" `
            -or $status.Body.manifest.backendEnabledEnv -ne "RETRIEVAL_KG_NEO4J_ENABLED") {
        throw "[AWX][graphdb-smoke] status missing neo4j backend config boundary"
    }
    if (-not (@($status.Body.manifest.backendUriEnv) -contains "RETRIEVAL_KG_NEO4J_URI") `
            -or -not (@($status.Body.manifest.backendUriEnv) -contains "NEO4J_URI")) {
        throw "[AWX][graphdb-smoke] status missing neo4j uri env aliases"
    }
    if (-not (@($status.Body.manifest.backendPasswordEnv) -contains "RETRIEVAL_KG_NEO4J_PASSWORD") `
            -or -not (@($status.Body.manifest.backendPasswordEnv) -contains "NEO4J_PASSWORD")) {
        throw "[AWX][graphdb-smoke] status missing neo4j password env aliases"
    }
    if ($status.Body.manifest.nonDryRunRequiresReachableBolt -ne $true `
            -or $status.Body.manifest.nonDryRunRequiresNonPlaceholderCredentials -ne $true `
            -or $status.Body.manifest.liveWriteReadProofRequired -ne $true `
            -or $status.Body.manifest.operatorDiagnosticsRedacted -ne $true) {
        throw "[AWX][graphdb-smoke] status missing non-dry-run readiness contract"
    }
    if (-not ([string]$status.Body.manifest.nonDryRunReadinessCommand).Contains("smoke_graphdb_manual_learning.ps1 -ReadinessOnly") `
            -or -not ([string]$status.Body.manifest.nonDryRunSmokeCommand).Contains("smoke_graphdb_manual_learning.ps1 -NonDryRun")) {
        throw "[AWX][graphdb-smoke] status missing non-dry-run proof commands"
    }
    foreach ($field in @(
                "uriPresent",
                "uriSource",
                "userPresent",
                "userSource",
                "passwordPresent",
                "passwordSource",
                "unsafeDefaultPassword",
                "uriParseable",
                "endpointHost",
                "endpointPort",
                "tcp",
                "neo4jService",
                "neo4jCli",
                "neo4jAdmin",
                "docker",
                "podman")) {
        if (-not (@($status.Body.manifest.nonDryRunReadinessEvidenceFields) -contains $field)) {
            throw "[AWX][graphdb-smoke] status missing redacted readiness evidence field $field"
        }
    }
    foreach ($failureClass in @(
                "missing_uri",
                "missing_user",
                "missing_password",
                "unsafe_default_credentials",
                "unparseable_uri",
                "missing_host",
                "bolt_unreachable",
                "live_write_read_unverified")) {
        if (-not (@($status.Body.manifest.nonDryRunReadinessFailureClasses) -contains $failureClass)) {
            throw "[AWX][graphdb-smoke] status missing readiness failure class $failureClass"
        }
    }
    foreach ($stage in @(
                "readiness_env",
                "readiness_bolt_tcp",
                "boot_status",
                "learn_text_write",
                "learn_file_write",
                "learn_evidence_readback",
                "learn_summary_projection",
                "learn_snapshot_projection")) {
        if (-not (@($status.Body.manifest.nonDryRunLiveProofStages) -contains $stage)) {
            throw "[AWX][graphdb-smoke] status missing non-dry-run live proof stage $stage"
        }
    }
    if ($status.Body.manifest.nonDryRunReadinessRawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] status readiness proof must not include raw secrets"
    }
    if ($status.Body.manifest.writeBoundary -ne "graphdb_manual_learning" `
            -or $status.Body.manifest.readBoundary -ne "graphdb_manual_learning") {
        throw "[AWX][graphdb-smoke] status missing graphdb manual write/read boundary"
    }
    if ($status.Body.manifest.vectorWriteBoundary -ne "VectorStoreService.enqueue" `
            -or $status.Body.manifest.neo4jWriteBoundary -ne "Neo4jKgChunkWriter.writeChunks" `
            -or $status.Body.manifest.neo4jReadBoundary -ne "Neo4jKgChunkWriter.readManualEvidence") {
        throw "[AWX][graphdb-smoke] status missing vector/neo4j persistence boundaries"
    }
    if ($status.Body.manifest.neo4jChunkUpsertScope -ne "KgChunkNode.sessionHash_textHash_ingestLane" `
            -or $status.Body.manifest.neo4jManualEvidenceScope -ne "ingestLane=graphdb_manual_learning" `
            -or $status.Body.manifest.neo4jManualRelationScope -ne "RELATED_TO.source=graphdb_manual_learning") {
        throw "[AWX][graphdb-smoke] status missing graphdb manual Cypher scope contract"
    }
    if ($status.Body.manifest.neo4jRawTextPersisted -ne $false `
            -or $status.Body.manifest.neo4jRawSessionIdPersisted -ne $false `
            -or $status.Body.manifest.neo4jRawEntityValuesReturned -ne $false) {
        throw "[AWX][graphdb-smoke] status neo4j raw persistence/readback contract must stay redacted"
    }
    if ($status.Body.manifest.vectorSessionIdMode -ne "hash_only_namespace" `
            -or $status.Body.manifest.vectorRawSessionIdIncluded -ne $false `
            -or $status.Body.manifest.vectorPayloadRawTextRequired -ne $true `
            -or $status.Body.manifest.vectorRawTextMetadataIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] status missing graphdb vector redaction boundary"
    }
    if ($status.Body.manifest.brainStateFallbackCoupled -ne $false `
            -or $status.Body.manifest.queryTimeRetrievalCoupled -ne $false `
            -or $status.Body.manifest.queryTimeAnchorMapCoupled -ne $false) {
        throw "[AWX][graphdb-smoke] status must not couple manual GraphDB lane to BrainState fallback or query-time GraphRAG"
    }
    if ($status.Body.manifest.rawTextIncluded -ne $false `
            -or $status.Body.manifest.rawIdentifiersIncluded -ne $false `
            -or $status.Body.manifest.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] status manifest must stay redacted"
    }
    if ($status.Body.manifest.brainStateMirrorRequested -ne $true `
            -or $status.Body.manifest.brainStateMirrorEnabled -ne $false `
            -or [string]$status.Body.manifest.brainStateMirrorSuppressedReason -ne "graphdb_manual_lane_excludes_brain_state") {
        throw "[AWX][graphdb-smoke] status must suppress requested BrainState mirror for GraphDB manual lane"
    }
    if ($status.Body.manifest.simultaneousIngestRequired -ne $true `
            -or -not (@($status.Body.manifest.simultaneousIngestTargets) -contains "vector") `
            -or -not (@($status.Body.manifest.simultaneousIngestTargets) -contains "neo4j")) {
        throw "[AWX][graphdb-smoke] status missing simultaneous vector+neo4j ingest contract"
    }
    if ([string]$status.Body.manifest.simultaneousIngestMode -ne "same_request_vector_then_neo4j" `
            -or $status.Body.manifest.simultaneousIngestAtomic -ne $false) {
        throw "[AWX][graphdb-smoke] status must describe non-atomic same-request vector then neo4j ingest"
    }
    if (@($status.Body.manifest.simultaneousIngestExecutionOrder)[0] -ne "vector" `
            -or @($status.Body.manifest.simultaneousIngestExecutionOrder)[1] -ne "neo4j" `
            -or [string]$status.Body.manifest.simultaneousIngestFailureIsolation -ne "continue_remaining_targets" `
            -or [string]$status.Body.manifest.simultaneousIngestPartialStatus -ne "partial_indexed") {
        throw "[AWX][graphdb-smoke] status must expose vector-then-neo4j non-atomic partial ingest contract"
    }
    foreach ($field in @(
            "vectorStatus",
            "vectorQueuedCount",
            "neo4jStatus",
            "neo4jWriteCount",
            "requiredPersistenceSatisfied",
            "requiredPersistenceMissingReason",
            "persistenceAttemptedTargets",
            "persistenceSucceededTargets",
            "persistenceIncompleteTargets")) {
        if (-not (@($status.Body.manifest.simultaneousIngestProofFields) -contains $field)) {
            throw "[AWX][graphdb-smoke] status simultaneous proof fields missing $field"
        }
    }
    if ($status.Body.manifest.simultaneousIngestConfigured -ne $true `
            -or [string]$status.Body.manifest.simultaneousIngestDisabledReason -ne "") {
        throw "[AWX][graphdb-smoke] status expected simultaneous vector+neo4j configured"
    }
    if ($DryRun) {
        if ($status.Body.manifest.neo4jBackendEnabled -ne $false `
                -or $status.Body.manifest.simultaneousIngestBackendConfigured -ne $false `
                -or [string]$status.Body.manifest.nonDryRunLiveProofStatus -ne "blocked" `
                -or -not ([string]$status.Body.manifest.nonDryRunLiveProofBlockedReason).StartsWith("neo4j_backend_disabled")) {
            throw "[AWX][graphdb-smoke] dryRun status must keep live neo4j backend proof blocked"
        }
    } else {
        if ($status.Body.manifest.neo4jBackendEnabled -ne $true `
                -or $status.Body.manifest.simultaneousIngestBackendConfigured -ne $true `
                -or [string]$status.Body.manifest.nonDryRunLiveProofStatus -ne "required_unverified" `
                -or [string]$status.Body.manifest.nonDryRunLiveProofBlockedReason -ne "") {
            throw "[AWX][graphdb-smoke] nonDryRun status must require live write/read proof after backend config"
        }
    }
    if ($status.Body.graphDb.backend -ne "neo4j" `
            -or $status.Body.graphDb.writeBoundary -ne "graphdb_manual_learning" `
            -or $status.Body.graphDb.readBoundary -ne "graphdb_manual_learning") {
        throw "[AWX][graphdb-smoke] status missing GraphDbClient backend/write/read boundary"
    }
    if ($status.Body.graphDb.rawTextIncluded -ne $false `
            -or $status.Body.graphDb.rawEntityValuesIncluded -ne $false `
            -or $status.Body.graphDb.rawIdentifiersIncluded -ne $false `
            -or $status.Body.graphDb.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] status GraphDbClient boundary must stay redacted"
    }

    $ingest = Invoke-GraphDbApi "POST" "/api/admin/graph/learn-text" @{
        sessionId = $SessionId
        text = $SmokeText
        domain = "SMOKE_GRAPHDB"
        dryRun = $DryRun
    }
    Assert-Status $ingest 200 "learn-text"
    Assert-NoRawLeak $ingest.Raw "learn-text"
    if ($ingest.Body.lane -ne "graphdb_manual_learning") {
        throw "[AWX][graphdb-smoke] learn-text wrong lane=$($ingest.Body.lane)"
    }
    if ($ingest.Body.manifest.sourceTag -ne "GRAPHDB_MANUAL" `
            -or $ingest.Body.manifest.docType -ne "GRAPHDB_MANUAL_LEARNING" `
            -or $ingest.Body.manifest.origin -ne "MANUAL_GRAPHDB") {
        throw "[AWX][graphdb-smoke] learn-text missing graphdb manual manifest"
    }
    if ($ingest.Body.manifest.brainStateMirrorRequested -ne $true `
            -or $ingest.Body.manifest.brainStateMirrorEnabled -ne $false `
            -or [string]$ingest.Body.manifest.brainStateMirrorSuppressedReason -ne "graphdb_manual_lane_excludes_brain_state") {
        throw "[AWX][graphdb-smoke] learn-text must suppress requested BrainState mirror"
    }
    if ($ingest.Body.manifest.vectorWriteBoundary -ne "VectorStoreService.enqueue" `
            -or $ingest.Body.manifest.vectorWriteMode -ne "buffered_enqueue") {
        throw "[AWX][graphdb-smoke] learn-text missing vector write boundary"
    }
    if ($ingest.Body.manifest.vectorSessionIdMode -ne "hash_only_namespace" `
            -or $ingest.Body.manifest.vectorRawSessionIdIncluded -ne $false `
            -or $ingest.Body.manifest.vectorPayloadRawTextRequired -ne $true `
            -or $ingest.Body.manifest.vectorRawTextMetadataIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] learn-text missing graphdb vector redaction boundary"
    }
    if ([int]$ingest.Body.manifest.vectorFailureCount -lt 0 `
            -or [int]$ingest.Body.manifest.vectorAttemptCount -lt [int]$ingest.Body.manifest.vectorQueuedCount) {
        throw "[AWX][graphdb-smoke] learn-text invalid vector persistence counts"
    }
    if ($ingest.Body.manifest.neo4jWriteBoundary -ne "Neo4jKgChunkWriter.writeChunks" `
            -or $ingest.Body.manifest.neo4jReadBoundary -ne "Neo4jKgChunkWriter.readManualEvidence") {
        throw "[AWX][graphdb-smoke] learn-text missing neo4j write/read boundary"
    }
    if ($ingest.Body.manifest.neo4jChunkUpsertScope -ne "KgChunkNode.sessionHash_textHash_ingestLane" `
            -or $ingest.Body.manifest.neo4jManualEvidenceScope -ne "ingestLane=graphdb_manual_learning" `
            -or $ingest.Body.manifest.neo4jManualRelationScope -ne "RELATED_TO.source=graphdb_manual_learning") {
        throw "[AWX][graphdb-smoke] learn-text missing graphdb manual Cypher scope contract"
    }
    if ($ingest.Body.manifest.neo4jRawTextPersisted -ne $false `
            -or $ingest.Body.manifest.neo4jRawSessionIdPersisted -ne $false `
            -or $ingest.Body.manifest.neo4jRawEntityValuesReturned -ne $false) {
        throw "[AWX][graphdb-smoke] learn-text neo4j raw persistence/readback contract must stay redacted"
    }
    if ($ingest.Body.manifest.brainStateFallbackCoupled -ne $false `
            -or $ingest.Body.manifest.queryTimeRetrievalCoupled -ne $false `
            -or $ingest.Body.manifest.queryTimeAnchorMapCoupled -ne $false) {
        throw "[AWX][graphdb-smoke] graphdb lane must stay decoupled from BrainState fallback, query-time retrieval, and query-time anchor map"
    }
    if ($ingest.Body.manifest.anchorMapStatus -ne "disabled" `
            -or [string]$ingest.Body.manifest.anchorMapDisabledReason -ne "graphdb_manual_lane_excludes_query_time_anchor_map") {
        throw "[AWX][graphdb-smoke] graphdb lane must not seed query-time anchor map status=$($ingest.Body.manifest.anchorMapStatus) reason=$($ingest.Body.manifest.anchorMapDisabledReason)"
    }
    if ($ingest.Body.manifest.rawTextIncluded -ne $false `
            -or $ingest.Body.manifest.rawIdentifiersIncluded -ne $false `
            -or $ingest.Body.manifest.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] graphdb manifest must not include raw text or secrets"
    }
    if ($DryRun) {
        if ($ingest.Body.manifest.routeStatus -ne "dry_run_ready" `
                -or [string]$ingest.Body.manifest.routeDisabledReason -ne "") {
            throw "[AWX][graphdb-smoke] learn-text dryRun route status mismatch status=$($ingest.Body.manifest.routeStatus) reason=$($ingest.Body.manifest.routeDisabledReason)"
        }
    } else {
        if ($ingest.Body.manifest.routeStatus -ne "ready_unverified" `
                -or [string]$ingest.Body.manifest.routeDisabledReason -ne "non_dry_run_live_proof_required") {
            throw "[AWX][graphdb-smoke] learn-text nonDryRun route status must stay ready_unverified until smoke proof status=$($ingest.Body.manifest.routeStatus) reason=$($ingest.Body.manifest.routeDisabledReason)"
        }
    }

    $requiredTargets = @($ingest.Body.manifest.requiredPersistenceTargets)
    if ($ingest.Body.manifest.simultaneousIngestMode -ne "same_request_vector_then_neo4j" `
            -or $ingest.Body.manifest.simultaneousIngestAtomic -ne $false) {
        throw "[AWX][graphdb-smoke] learn-text must preserve same-request non-atomic ingest contract"
    }
    if (@($ingest.Body.manifest.simultaneousIngestExecutionOrder)[0] -ne "vector" `
            -or @($ingest.Body.manifest.simultaneousIngestExecutionOrder)[1] -ne "neo4j" `
            -or [string]$ingest.Body.manifest.simultaneousIngestFailureIsolation -ne "continue_remaining_targets" `
            -or [string]$ingest.Body.manifest.simultaneousIngestPartialStatus -ne "partial_indexed") {
        throw "[AWX][graphdb-smoke] learn-text must expose vector-then-neo4j non-atomic partial ingest contract"
    }
    if ($DryRun) {
        if ($ingest.Body.status -ne "dry_run") {
            throw "[AWX][graphdb-smoke] dryRun expected status=dry_run actual=$($ingest.Body.status)"
        }
        if ($requiredTargets.Count -ne 0 `
                -or $ingest.Body.manifest.requiredPersistenceSatisfied -ne $true `
                -or [string]$ingest.Body.manifest.requiredPersistenceMissingReason -ne "") {
            throw "[AWX][graphdb-smoke] dryRun must not require live persistence targets"
        }
        if ($ingest.Body.manifest.vectorStatus -ne "dry_run" -or $ingest.Body.manifest.neo4jStatus -ne "dry_run") {
            throw "[AWX][graphdb-smoke] dryRun must not persist vector or neo4j vector=$($ingest.Body.manifest.vectorStatus) neo4j=$($ingest.Body.manifest.neo4jStatus)"
        }
        if ([int]$ingest.Body.manifest.vectorAttemptCount -ne 0 `
                -or [int]$ingest.Body.manifest.vectorQueuedCount -ne 0 `
                -or [int]$ingest.Body.manifest.vectorFailureCount -ne 0 `
                -or [int]$ingest.Body.manifest.neo4jPortMappingCount -ne 0) {
            throw "[AWX][graphdb-smoke] dryRun must keep persistence counts at zero"
        }
        if (@($ingest.Body.manifest.persistenceAttemptedTargets).Count -ne 0 `
                -or @($ingest.Body.manifest.persistenceSucceededTargets).Count -ne 0 `
                -or @($ingest.Body.manifest.persistenceIncompleteTargets).Count -ne 0 `
                -or $ingest.Body.manifest.persistenceFailureIsolationApplied -ne $false) {
            throw "[AWX][graphdb-smoke] dryRun must not report live persistence target results"
        }
    } else {
        $LiveWriteReadPhase = $true
        if ($ingest.Body.status -ne "indexed") {
            throw "[AWX][graphdb-smoke] nonDryRun expected indexed status=$($ingest.Body.status) disabledReason=$($ingest.Body.disabledReason) failureClass=$($ingest.Body.manifest.failureClass)"
        }
        if (-not ($requiredTargets -contains "vector") -or -not ($requiredTargets -contains "neo4j") `
                -or $ingest.Body.manifest.requiredPersistenceSatisfied -ne $true `
                -or [string]$ingest.Body.manifest.requiredPersistenceMissingReason -ne "") {
            throw "[AWX][graphdb-smoke] nonDryRun must prove enabled vector and neo4j targets"
        }
        if ($ingest.Body.manifest.vectorStatus -ne "queued") {
            throw "[AWX][graphdb-smoke] nonDryRun expected vector queued actual=$($ingest.Body.manifest.vectorStatus)"
        }
        if ([int]$ingest.Body.manifest.vectorAttemptCount -lt 1 `
                -or [int]$ingest.Body.manifest.vectorQueuedCount -lt 1 `
                -or [int]$ingest.Body.manifest.vectorFailureCount -ne 0) {
            throw "[AWX][graphdb-smoke] nonDryRun expected vector enqueue counts attempts=$($ingest.Body.manifest.vectorAttemptCount) queued=$($ingest.Body.manifest.vectorQueuedCount) failures=$($ingest.Body.manifest.vectorFailureCount)"
        }
        if ($ingest.Body.manifest.neo4jStatus -ne "written" -or [int]$ingest.Body.neo4jWriteCount -lt 1) {
            throw "[AWX][graphdb-smoke] nonDryRun expected neo4j written status=$($ingest.Body.manifest.neo4jStatus) writes=$($ingest.Body.neo4jWriteCount)"
        }
        if (-not (@($ingest.Body.manifest.persistenceAttemptedTargets) -contains "vector") `
                -or -not (@($ingest.Body.manifest.persistenceAttemptedTargets) -contains "neo4j") `
                -or -not (@($ingest.Body.manifest.persistenceSucceededTargets) -contains "vector") `
                -or -not (@($ingest.Body.manifest.persistenceSucceededTargets) -contains "neo4j") `
                -or @($ingest.Body.manifest.persistenceIncompleteTargets).Count -ne 0 `
                -or $ingest.Body.manifest.persistenceFailureIsolationApplied -ne $false) {
            throw "[AWX][graphdb-smoke] nonDryRun learn-text must expose complete vector+neo4j persistence proof"
        }
        if ([int]$ingest.Body.manifest.neo4jPortMappingCount -lt 1) {
            throw "[AWX][graphdb-smoke] nonDryRun expected neo4j port mapping count actual=$($ingest.Body.manifest.neo4jPortMappingCount)"
        }
        if ($ingest.Body.manifest.brainStateStatus -ne "disabled") {
            throw "[AWX][graphdb-smoke] nonDryRun must keep BrainState mirror disabled actual=$($ingest.Body.manifest.brainStateStatus)"
        }
        $LiveWriteReadPhase = $false
    }

    $fileIngest = Invoke-GraphDbMultipartApi "/api/admin/graph/learn-file" @{
        sessionId = "$SessionId-file"
        domain = "SMOKE_GRAPHDB"
        dryRun = ([string]$DryRun).ToLowerInvariant()
    } "manual-smoke.txt" "text/plain" ([System.Text.Encoding]::UTF8.GetBytes($SmokeFileText))
    Assert-Status $fileIngest 200 "learn-file"
    Assert-NoRawLeak $fileIngest.Raw "learn-file"
    if ($fileIngest.Body.lane -ne "graphdb_manual_learning") {
        throw "[AWX][graphdb-smoke] learn-file wrong lane=$($fileIngest.Body.lane)"
    }
    if ($fileIngest.Body.manifest.inputKind -ne "file" `
            -or $fileIngest.Body.manifest.fileNamePresent -ne $true `
            -or $fileIngest.Body.manifest.mimeTypePresent -ne $true) {
        throw "[AWX][graphdb-smoke] learn-file missing file boundary manifest"
    }
    if ($fileIngest.Body.manifest.fileContentBoundary -ne "FileIngestionService.extractText" `
            -or [int]$fileIngest.Body.manifest.fileByteLength -ne ([System.Text.Encoding]::UTF8.GetByteCount($SmokeFileText)) `
            -or -not ([string]$fileIngest.Body.manifest.fileByteHash -match '^[a-f0-9]{12}$') `
            -or $fileIngest.Body.manifest.rawFileNameIncluded -ne $false `
            -or $fileIngest.Body.manifest.rawFileBytesIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] learn-file missing redacted file audit boundary"
    }
    if ($fileIngest.Body.manifest.sourceTag -ne "GRAPHDB_MANUAL" `
            -or $fileIngest.Body.manifest.docType -ne "GRAPHDB_MANUAL_LEARNING" `
            -or $fileIngest.Body.manifest.origin -ne "MANUAL_GRAPHDB") {
        throw "[AWX][graphdb-smoke] learn-file missing graphdb manual manifest"
    }
    if ($fileIngest.Body.manifest.vectorWriteBoundary -ne "VectorStoreService.enqueue" `
            -or $fileIngest.Body.manifest.neo4jWriteBoundary -ne "Neo4jKgChunkWriter.writeChunks" `
            -or $fileIngest.Body.manifest.neo4jReadBoundary -ne "Neo4jKgChunkWriter.readManualEvidence") {
        throw "[AWX][graphdb-smoke] learn-file missing vector/neo4j write/read boundaries"
    }
    if ($fileIngest.Body.manifest.neo4jChunkUpsertScope -ne "KgChunkNode.sessionHash_textHash_ingestLane" `
            -or $fileIngest.Body.manifest.neo4jManualEvidenceScope -ne "ingestLane=graphdb_manual_learning" `
            -or $fileIngest.Body.manifest.neo4jManualRelationScope -ne "RELATED_TO.source=graphdb_manual_learning") {
        throw "[AWX][graphdb-smoke] learn-file missing graphdb manual Cypher scope contract"
    }
    if ($fileIngest.Body.manifest.neo4jRawTextPersisted -ne $false `
            -or $fileIngest.Body.manifest.neo4jRawSessionIdPersisted -ne $false `
            -or $fileIngest.Body.manifest.neo4jRawEntityValuesReturned -ne $false) {
        throw "[AWX][graphdb-smoke] learn-file neo4j raw persistence/readback contract must stay redacted"
    }
    if ($fileIngest.Body.manifest.vectorSessionIdMode -ne "hash_only_namespace" `
            -or $fileIngest.Body.manifest.vectorRawSessionIdIncluded -ne $false `
            -or $fileIngest.Body.manifest.vectorPayloadRawTextRequired -ne $true `
            -or $fileIngest.Body.manifest.vectorRawTextMetadataIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] learn-file missing graphdb vector redaction boundary"
    }
    if ($fileIngest.Body.manifest.simultaneousIngestMode -ne "same_request_vector_then_neo4j" `
            -or $fileIngest.Body.manifest.simultaneousIngestAtomic -ne $false) {
        throw "[AWX][graphdb-smoke] learn-file must preserve same-request non-atomic ingest contract"
    }
    if (@($fileIngest.Body.manifest.simultaneousIngestExecutionOrder)[0] -ne "vector" `
            -or @($fileIngest.Body.manifest.simultaneousIngestExecutionOrder)[1] -ne "neo4j" `
            -or [string]$fileIngest.Body.manifest.simultaneousIngestFailureIsolation -ne "continue_remaining_targets" `
            -or [string]$fileIngest.Body.manifest.simultaneousIngestPartialStatus -ne "partial_indexed") {
        throw "[AWX][graphdb-smoke] learn-file must expose vector-then-neo4j non-atomic partial ingest contract"
    }
    if ($fileIngest.Body.manifest.brainStateFallbackCoupled -ne $false `
            -or $fileIngest.Body.manifest.queryTimeRetrievalCoupled -ne $false `
            -or $fileIngest.Body.manifest.queryTimeAnchorMapCoupled -ne $false) {
        throw "[AWX][graphdb-smoke] learn-file must stay decoupled from BrainState fallback and query-time GraphRAG"
    }
    if ($fileIngest.Body.manifest.brainStateMirrorRequested -ne $true `
            -or $fileIngest.Body.manifest.brainStateMirrorEnabled -ne $false `
            -or [string]$fileIngest.Body.manifest.brainStateMirrorSuppressedReason -ne "graphdb_manual_lane_excludes_brain_state") {
        throw "[AWX][graphdb-smoke] learn-file must suppress requested BrainState mirror"
    }
    if ($fileIngest.Body.manifest.rawTextIncluded -ne $false `
            -or $fileIngest.Body.manifest.rawIdentifiersIncluded -ne $false `
            -or $fileIngest.Body.manifest.rawSecretsIncluded -ne $false) {
        throw "[AWX][graphdb-smoke] learn-file manifest must not include raw text, identifiers, or secrets"
    }
    $fileRequiredTargets = @($fileIngest.Body.manifest.requiredPersistenceTargets)
    if ($DryRun) {
        if ($fileIngest.Body.status -ne "dry_run" `
                -or $fileRequiredTargets.Count -ne 0 `
                -or $fileIngest.Body.manifest.requiredPersistenceSatisfied -ne $true `
                -or [string]$fileIngest.Body.manifest.requiredPersistenceMissingReason -ne "" `
                -or $fileIngest.Body.manifest.vectorStatus -ne "dry_run" `
                -or $fileIngest.Body.manifest.neo4jStatus -ne "dry_run" `
                -or @($fileIngest.Body.manifest.persistenceAttemptedTargets).Count -ne 0 `
                -or @($fileIngest.Body.manifest.persistenceSucceededTargets).Count -ne 0 `
                -or @($fileIngest.Body.manifest.persistenceIncompleteTargets).Count -ne 0) {
            throw "[AWX][graphdb-smoke] dryRun learn-file must avoid live vector/neo4j persistence"
        }
    } else {
        if ($fileIngest.Body.status -ne "indexed" `
                -or -not ($fileRequiredTargets -contains "vector") `
                -or -not ($fileRequiredTargets -contains "neo4j") `
                -or $fileIngest.Body.manifest.requiredPersistenceSatisfied -ne $true `
                -or [string]$fileIngest.Body.manifest.requiredPersistenceMissingReason -ne "" `
                 -or $fileIngest.Body.manifest.vectorStatus -ne "queued" `
                 -or $fileIngest.Body.manifest.neo4jStatus -ne "written" `
                 -or [int]$fileIngest.Body.neo4jWriteCount -lt 1 `
                 -or -not (@($fileIngest.Body.manifest.persistenceAttemptedTargets) -contains "vector") `
                 -or -not (@($fileIngest.Body.manifest.persistenceAttemptedTargets) -contains "neo4j") `
                 -or -not (@($fileIngest.Body.manifest.persistenceSucceededTargets) -contains "vector") `
                 -or -not (@($fileIngest.Body.manifest.persistenceSucceededTargets) -contains "neo4j") `
                 -or @($fileIngest.Body.manifest.persistenceIncompleteTargets).Count -ne 0) {
            $LiveWriteReadPhase = $true
            throw "[AWX][graphdb-smoke] nonDryRun learn-file must prove vector queue and neo4j write"
        }
    }

    $evidence = Invoke-GraphDbApi "GET" "/api/admin/graph/learn-evidence?domain=SMOKE_GRAPHDB&limit=5" $null
    if ($DryRun) {
        Assert-StatusAny $evidence @(200, 503) "learn-evidence"
    } else {
        Assert-Status $evidence 200 "learn-evidence"
    }
    Assert-GraphDbReadRedaction $evidence
    Assert-ManualRelationSourcesFromEvidence $evidence
    if (-not $DryRun) {
        $LiveWriteReadPhase = $true
        Assert-LiveEvidenceReadBack $evidence
        $LiveWriteReadPhase = $false
    }

    $summary = Invoke-GraphDbApi "GET" "/api/admin/graph/learn-summary?domain=SMOKE_GRAPHDB&limit=5" $null
    if ($DryRun) {
        Assert-StatusAny $summary @(200, 503) "learn-summary"
    } else {
        Assert-Status $summary 200 "learn-summary"
    }
    Assert-NoRawLeak $summary.Raw "learn-summary"
    Assert-ManualRelationSourcesFromSummary $summary
    if (-not $DryRun) {
        $LiveWriteReadPhase = $true
        Assert-LiveSummaryReadBack $summary
        $LiveWriteReadPhase = $false
    }

    $snapshot = Invoke-GraphDbApi "GET" "/api/admin/graph/learn-snapshot?domain=SMOKE_GRAPHDB&limit=5" $null
    if ($DryRun) {
        Assert-StatusAny $snapshot @(200, 503) "learn-snapshot"
    } else {
        Assert-Status $snapshot 200 "learn-snapshot"
    }
    Assert-SnapshotProjection $snapshot
    if (-not $DryRun) {
        $LiveWriteReadPhase = $true
        Assert-LiveSnapshotReadBack $snapshot
        $LiveWriteReadPhase = $false
    }

    Write-Host "[AWX][graphdb-smoke] OK dryRun=$DryRun learnText=$($ingest.Status) learnFile=$($fileIngest.Status) evidence=$($evidence.Status) summary=$($summary.Status) snapshot=$($snapshot.Status) session=$SessionId chunks=$($ingest.Body.chunkCount) fileChunks=$($fileIngest.Body.chunkCount) vector=$($ingest.Body.manifest.vectorStatus) fileVector=$($fileIngest.Body.manifest.vectorStatus) neo4j=$($ingest.Body.manifest.neo4jStatus) fileNeo4j=$($fileIngest.Body.manifest.neo4jStatus) rawLeak=false"
    $Succeeded = $true
} catch {
    if ($LiveWriteReadPhase) {
        Write-Host (Redact-Text ([string]$_.Exception.Message))
        exit 2
    }
    throw
} finally {
    if (-not $Succeeded) {
        if ($null -ne $app) {
            Write-Host "[AWX][graphdb-smoke] logTail"
            Write-Host (Tail-Log $app.OutLog)
            Write-Host (Tail-Log $app.ErrLog)
            Write-Host "[AWX][graphdb-smoke] retainedLogDir=$TempRoot"
        } else {
            Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
    Stop-SmokeApp $app
    Restore-SmokeEnv
    if ($Succeeded) {
        Remove-Item -LiteralPath $TempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
