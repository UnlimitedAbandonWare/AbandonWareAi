param(
    [int]$Port = 18082,
    [int]$ManagementPort = 18083,
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

$TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-dataset-api-smoke-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null
$DatasetFile = Join-Path $TempRoot "train_rag.jsonl"
$OtherDatasetFile = Join-Path $TempRoot "other.jsonl"
$TestKey = "dataset-smoke-" + [guid]::NewGuid().ToString("N")

function Redact-Text([string]$Text) {
    if ($null -eq $Text) { return "" }
    $out = $Text
    foreach ($secret in @($TestKey, $DatasetFile, $OtherDatasetFile, $TempRoot)) {
        if (-not [string]::IsNullOrWhiteSpace($secret)) {
            $out = $out -replace [regex]::Escape($secret), "<redacted>"
        }
    }
    return $out
}

function Set-SmokeEnv([bool]$DatasetApiEnabled, [string]$DatasetApiKey) {
    $env:SPRING_PROFILES_ACTIVE = "macmini-control-plane"
    $env:SERVER_PORT = [string]$Port
    $env:MANAGEMENT_SERVER_PORT = [string]$ManagementPort
    $env:MACMINI_DATASET_API_ENABLED = if ($DatasetApiEnabled) { "true" } else { "false" }
    $env:MACMINI_DATASET_API_KEY = $DatasetApiKey
    $env:MACMINI_AUTOLEARN_ENABLED = "false"
    $env:MACMINI_AUTOLEARN_IDLE_TRIGGER_ENABLED = "false"
    $env:MACMINI_AUTOLEARN_DATASET_PATH = $DatasetFile
    $env:MACMINI_AUTOLEARN_DATASET_NAME = "smoke-curated-rag"
    $env:MACMINI_DESKTOP_GPU_GATEWAY_ENABLED = "false"
    $env:LOCAL_LLM_AUTOSTART = "false"
    $env:LOCAL_LLM_WARMUP_ENABLED = "false"
    $env:DOMAIN_ALLOWLIST_ADMIN_TOKEN_REQUIRED = "false"
}

function Start-SmokeApp([string]$Name, [bool]$DatasetApiEnabled, [string]$DatasetApiKey) {
    Set-SmokeEnv $DatasetApiEnabled $DatasetApiKey
    $outLog = Join-Path $TempRoot "$Name.out.log"
    $errLog = Join-Path $TempRoot "$Name.err.log"
    $GradleArgs = @("bootRun", "--no-daemon", "-x", "test", "--project-cache-dir", $ProjectCacheDir)
    $proc = Start-Process -FilePath $Gradle `
        -ArgumentList $GradleArgs `
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
            $out = Tail-Log $App.OutLog
            $err = Tail-Log $App.ErrLog
            throw "[AWX][dataset-api-smoke] app exited during $($App.Name)`n$out`n$err"
        }
        try {
            Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 2 | Out-Null
            return
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    throw "[AWX][dataset-api-smoke] startup timeout for $($App.Name)`n$(Tail-Log $App.OutLog)`n$(Tail-Log $App.ErrLog)"
}

function Invoke-DatasetApi([hashtable]$Body, [string]$HeaderKey) {
    $headers = @{}
    if (-not [string]::IsNullOrWhiteSpace($HeaderKey)) {
        $headers["X-Internal-Key"] = $HeaderKey
    }
    $json = $Body | ConvertTo-Json -Depth 8
    try {
        $resp = Invoke-WebRequest `
            -Uri "http://127.0.0.1:$Port/internal/dataset/rag" `
            -Method POST `
            -Headers $headers `
            -ContentType "application/json" `
            -Body $json `
            -UseBasicParsing `
            -TimeoutSec 15
        return @{ Status = [int]$resp.StatusCode; Body = ($resp.Content | ConvertFrom-Json) }
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
        return @{ Status = [int]$response.StatusCode; Body = $parsedBody }
    }
}

function Assert-Status($Actual, [int]$Expected, [string]$Label) {
    if ([int]$Actual.Status -ne $Expected) {
        throw "[AWX][dataset-api-smoke] $Label expected=$Expected actual=$($Actual.Status) body=$(Redact-Text ($Actual.Body | ConvertTo-Json -Depth 8 -Compress))"
    }
}

function Assert-Reason($Actual, [string]$Expected, [string]$Label) {
    $reason = ""
    if ($null -ne $Actual.Body -and $null -ne $Actual.Body.disabledReason) {
        $reason = [string]$Actual.Body.disabledReason
    }
    if ($reason -ne $Expected) {
        throw "[AWX][dataset-api-smoke] $Label expectedReason=$Expected actualReason=$reason"
    }
}

$acceptedBody = @{
    question = "What is the dataset API smoke policy?"
    answer = "Accepted smoke samples require validation metadata and the configured dataset path."
    evidenceCount = 5
    afterFilterCount = 5
    contextDiversity = 1.0
    finalGate = $true
    model = "smoke-model"
    provider = "smoke"
    sessionId = "smoke-session"
}

$apps = @()
try {
    $disabled = Start-SmokeApp "disabled" $false ""
    $apps += $disabled
    Wait-ForHttp $disabled
    $disabledResp = Invoke-DatasetApi $acceptedBody $TestKey
    Assert-Status $disabledResp 404 "disabled route"
    Stop-SmokeApp $disabled

    $missing = Start-SmokeApp "missing-key" $true ""
    $apps += $missing
    Wait-ForHttp $missing
    $missingResp = Invoke-DatasetApi $acceptedBody ""
    Assert-Status $missingResp 503 "missing key"
    Assert-Reason $missingResp "missing_internal_key" "missing key"
    Stop-SmokeApp $missing

    $enabled = Start-SmokeApp "enabled" $true $TestKey
    $apps += $enabled
    Wait-ForHttp $enabled

    $wrong = Invoke-DatasetApi $acceptedBody "wrong-key"
    Assert-Status $wrong 401 "wrong key"
    Assert-Reason $wrong "unauthorized" "wrong key"

    $invalid = Invoke-DatasetApi @{ question = ""; answer = "answer" } $TestKey
    Assert-Status $invalid 400 "invalid payload"
    Assert-Reason $invalid "invalid_request" "invalid payload"

    $validationReject = Invoke-DatasetApi @{
        question = "What is the reject smoke policy?"
        answer = "This should reject through the validation gate."
        evidenceCount = 0
        afterFilterCount = 0
        contextDiversity = 0.0
        finalGate = $false
    } $TestKey
    Assert-Status $validationReject 422 "validation reject"

    $accepted = Invoke-DatasetApi $acceptedBody $TestKey
    Assert-Status $accepted 200 "accepted"
    if ($accepted.Body.accepted -ne $true -or $accepted.Body.status -ne "accepted") {
        throw "[AWX][dataset-api-smoke] accepted body did not report accepted"
    }

    $redirect = $acceptedBody.Clone()
    $redirect["datasetPath"] = $OtherDatasetFile
    $disallowed = Invoke-DatasetApi $redirect $TestKey
    Assert-Status $disallowed 400 "disallowed datasetPath"
    Assert-Reason $disallowed "dataset_path_not_allowed" "disallowed datasetPath"

    Write-Host "[AWX][dataset-api-smoke] OK disabled=404 missingKey=503 wrongKey=401 invalid=400 validationReject=422 accepted=200 disallowedPath=400"
} finally {
    foreach ($app in $apps) {
        Stop-SmokeApp $app
    }
    Remove-Item Env:\MACMINI_DATASET_API_KEY -ErrorAction SilentlyContinue
}
