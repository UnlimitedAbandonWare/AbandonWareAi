[CmdletBinding()]
param(
    # Live server under test. Default is the Start-RAG port.
    [string]$BaseUrl = $env:APP_PUBLIC_BASE_URL,

    # Explicit model route id, e.g. "chatgpt-oauth:gpt-5.5".
    # When empty the first selectable OAuth catalog row is used.
    [string]$Model = "",

    # Tiny prompt for the single live generation call.
    [string]$Prompt = "Reply with the word OK only.",

    [int]$MaxTokens = 64,

    # 답 대기 상한: chat.run.max-duration-seconds + 20s (env 없으면 620초).
    [int]$TimeoutSec = $(if ($env:CHAT_RUN_MAX_DURATION_SECONDS -match '^\d+$') { [int]$env:CHAT_RUN_MAX_DURATION_SECONDS + 20 } else { 620 }),

    # Probe the model catalog only; never send a generation request.
    [switch]$CatalogOnly
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    $BaseUrl = "http://127.0.0.1:18180"
}
$BaseUrl = $BaseUrl.TrimEnd("/")

function Get-SafeToken {
    param([object]$Value, [int]$Max = 80)
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) { return "" }
    $trimmed = $text.Trim()
    if ($trimmed.Length -gt $Max) { $trimmed = $trimmed.Substring(0, $Max) }
    if ($trimmed -match '^[A-Za-z0-9_.:/*-]+$') { return $trimmed }
    return "redacted"
}

# ---------- 1) catalog ----------
$catalogUrl = "$BaseUrl/api/chat/models"
$choices = $null
try {
    $choices = Invoke-RestMethod -Method Get -Uri $catalogUrl -TimeoutSec ([Math]::Min($TimeoutSec, 20))
} catch {
    $status = 0
    if ($_.Exception.Response -ne $null) { $status = [int]$_.Exception.Response.StatusCode }
    Write-Host "[AWX][chatgpt-oauth][catalog] reachable=false url=$catalogUrl httpStatus=$status errorType=$($_.Exception.GetType().Name)"
    Write-Host "[AWX][chatgpt-oauth] verdict=catalog_unreachable (server down or port mismatch; expected 18180)"
    exit 3
}

$all = @($choices)
$oauthRows = @($all | Where-Object { $_.id -like "chatgpt-oauth:*" })
$selectable = @($oauthRows | Where-Object { $_.selectable -eq $true })
$oauthIds = ($oauthRows | ForEach-Object { $_.id }) -join ","

Write-Host "[AWX][chatgpt-oauth][catalog] reachable=true totalRows=$($all.Count) oauthRows=$($oauthRows.Count) oauthSelectable=$($selectable.Count) oauthIds=$oauthIds"

if ($oauthRows.Count -eq 0) {
    Write-Host "[AWX][chatgpt-oauth] verdict=catalog_absent (running JVM may predate OAuth registration, chatgpt.oauth disabled, or models.json unsynced)"
    exit 3
}
if ($selectable.Count -eq 0) {
    Write-Host "[AWX][chatgpt-oauth] verdict=none_selectable (rows present but not selectable - check credentials/models sync)"
    exit 3
}

$modelId = $Model
if ([string]::IsNullOrWhiteSpace($modelId)) {
    $modelId = $selectable[0].id
}
if ($modelId -notlike "chatgpt-oauth:*") {
    Write-Host "[AWX][chatgpt-oauth] evidence_needed: -Model must be a chatgpt-oauth:* route, got '$(Get-SafeToken $modelId 40)'"
    exit 2
}
if (-not ($selectable.id -contains $modelId)) {
    Write-Host "[AWX][chatgpt-oauth] evidence_needed: requested model '$(Get-SafeToken $modelId 40)' is not a selectable catalog row"
    exit 3
}
Write-Host "[AWX][chatgpt-oauth][pick] model=$modelId"

if ($CatalogOnly) {
    Write-Host "[AWX][chatgpt-oauth] verdict=catalog_only_pass"
    exit 0
}

# ---------- 2) one live generation call ----------
$syncUrl = "$BaseUrl/api/chat/sync"
$body = [ordered]@{
    message              = $Prompt
    model                = $modelId
    strictModelSelection = $true
    useRag               = $false
    useWebSearch         = $false
    searchMode           = "OFF"
    maxTokens            = $MaxTokens
} | ConvertTo-Json -Depth 4 -Compress

$httpStatus = 0
$xModelUsed = ""
$response = $null
try {
    $response = Invoke-WebRequest -Method Post -Uri $syncUrl -Body $body `
        -ContentType "application/json" -TimeoutSec $TimeoutSec -ErrorAction Stop
    $httpStatus = [int]$response.StatusCode
    $xModelUsed = Get-SafeToken ($response.Headers["X-Model-Used"] -join ",") 80
} catch {
    $errResp = $_.Exception.Response
    if ($errResp -ne $null) { $httpStatus = [int]$errResp.StatusCode }
    $errBody = ""
    try {
        if ($errResp -ne $null) {
            $reader = [System.IO.StreamReader]::new($errResp.GetResponseStream())
            $errBody = $reader.ReadToEnd()
            $reader.Close()
        }
    } catch { $errBody = "" }
    $reason = "http_error"
    if ($errBody) {
        try {
            $parsed = $errBody | ConvertFrom-Json -ErrorAction Stop
            $candidate = @($parsed.reasonCode, $parsed.content, $parsed.modelUsed) |
                Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Select-Object -First 1
            $reason = Get-SafeToken $candidate 80
            if ([string]::IsNullOrWhiteSpace($reason) -or $reason -eq "redacted") { $reason = "http_status_$httpStatus" }
        } catch {
            $reason = "http_status_$httpStatus"
        }
    } else {
        $reason = "http_status_$httpStatus"
    }
    Write-Host "[AWX][chatgpt-oauth][sync] ok=false httpStatus=$httpStatus model=$modelId reason=$reason"
    if ($httpStatus -eq 409) {
        Write-Host "[AWX][chatgpt-oauth] verdict=run_active (another chat run holds the lane; retry when idle)"
    } elseif ($httpStatus -in @(401, 403)) {
        Write-Host "[AWX][chatgpt-oauth] verdict=auth_blocked (endpoint requires auth in this profile)"
    } elseif ($reason -match "oauth|unauthorized|not_configured|refresh|credential") {
        Write-Host "[AWX][chatgpt-oauth] verdict=oauth_config (OAuth credential/dispatch gate rejected the call)"
    } else {
        Write-Host "[AWX][chatgpt-oauth] verdict=generation_failed (dispatch or shared transport not serving)"
    }
    exit 4
}

$dto = $null
try { $dto = $response.Content | ConvertFrom-Json -ErrorAction Stop } catch { $dto = $null }
$answer = ""
$answerMode = ""
$bodyModelUsed = ""
if ($dto -ne $null) {
    $answer = [string]$dto.content
    $answerMode = [string]$dto.answerMode
    $bodyModelUsed = [string]$dto.modelUsed
}
$answerPreview = ""
if (-not [string]::IsNullOrWhiteSpace($answer)) {
    $answerPreview = $answer.Trim()
    if ($answerPreview.Length -gt 60) { $answerPreview = $answerPreview.Substring(0, 60) }
}

Write-Host "[AWX][chatgpt-oauth][sync] ok=true httpStatus=$httpStatus model=$modelId xModelUsed=$xModelUsed bodyModelUsed=$(Get-SafeToken $bodyModelUsed 80) answerMode=$(Get-SafeToken $answerMode 40) answerPreview='$answerPreview'"

if ([string]::IsNullOrWhiteSpace($answer)) {
    Write-Host "[AWX][chatgpt-oauth] verdict=empty_answer (HTTP 200 but no content - not proof of generation)"
    exit 4
}

Write-Host "[AWX][chatgpt-oauth] verdict=pass"
exit 0
