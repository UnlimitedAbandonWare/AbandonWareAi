param(
    [string]$BaseUrl = $env:LLM_BASE_URL,
    [string]$Model = $env:LLM_CHAT_MODEL,
    [int]$TimeoutSeconds = 20,
    [string]$OutputDir = "verification\local-llm-smoke",
    [string]$ProbePrompt = "Reply with exactly AWX_OK.",
    [string]$ApiKey = $env:LLM_API_KEY,
    [switch]$NativeOllamaCompare,
    [switch]$NativeFirst,
    [switch]$StaticOnly
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$SmokeName = "local-llm-generation"
$ResolvedOutputDir = if ([System.IO.Path]::IsPathRooted($OutputDir)) {
    $OutputDir
} else {
    Join-Path $Root $OutputDir
}
$ReportPath = Join-Path $ResolvedOutputDir "$SmokeName.json"

function Is-Blank([string]$Value) {
    return [string]::IsNullOrWhiteSpace($Value)
}

function Has-UsableSecret([string]$Value) {
    if (Is-Blank $Value) { return $false }
    $v = $Value.Trim()
    $lower = $v.ToLowerInvariant()
    if ($v.StartsWith('${') -or $v.EndsWith('}')) { return $false }
    if (@("dummy", "test", "changeme", "change-me", "sk-local", "ollama", "__missing__") -contains $lower) { return $false }
    return $true
}

function Get-ShortHash([string]$Text) {
    if ($null -eq $Text) { $Text = "" }
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        $hash = $sha.ComputeHash($bytes)
        return -join ($hash | ForEach-Object { $_.ToString("x2") })[0..11]
    } finally {
        $sha.Dispose()
    }
}

function Normalize-OpenAiBaseUrl([string]$Url) {
    if (Is-Blank $Url) {
        $Url = "http://127.0.0.1:11434/v1"
    }
    $u = $Url.Trim().TrimEnd("/")
    if ($u.EndsWith("/chat/completions")) {
        $u = $u.Substring(0, $u.Length - "/chat/completions".Length)
    }
    if ($u.EndsWith("/models")) {
        $u = $u.Substring(0, $u.Length - "/models".Length)
    }
    return $u.TrimEnd("/")
}

function Get-EndpointHost([string]$Url) {
    try {
        $uri = [Uri]$Url
        if ($uri.IsDefaultPort) { return $uri.Host }
        return "$($uri.Host):$($uri.Port)"
    } catch {
        return "invalid-url"
    }
}

function Get-NativeOllamaBaseUrl([string]$OpenAiBaseUrl) {
    $u = $OpenAiBaseUrl.TrimEnd("/")
    if ($u.EndsWith("/v1")) {
        return $u.Substring(0, $u.Length - 3).TrimEnd("/")
    }
    return $u
}

function New-Headers {
    $headers = @{}
    if (Has-UsableSecret $ApiKey) {
        $headers["Authorization"] = "Bearer $ApiKey"
    }
    return $headers
}

function Get-JsonValue($Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $prop = $Object.PSObject.Properties[$Name]
    if ($null -eq $prop) { return $null }
    return $prop.Value
}

function Get-ResponseBodyFromException($Exception) {
    $response = $Exception.Response
    if ($null -eq $response) { return "" }
    try {
        $stream = $response.GetResponseStream()
        if ($null -eq $stream) { return "" }
        $reader = New-Object System.IO.StreamReader($stream)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } catch {
        return ""
    }
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory = $true)][string]$Uri,
        [Parameter(Mandatory = $true)][string]$Method,
        [string]$Body = ""
    )
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $request = @{
            Uri = $Uri
            Method = $Method
            UseBasicParsing = $true
            TimeoutSec = $TimeoutSeconds
        }
        $headers = New-Headers
        if ($headers.Count -gt 0) {
            $request.Headers = $headers
        }
        if (-not (Is-Blank $Body)) {
            $request.ContentType = "application/json"
            $request.Body = $Body
        }
        $resp = Invoke-WebRequest @request
        $sw.Stop()
        $raw = [string]$resp.Content
        $parsed = $null
        if (-not (Is-Blank $raw)) {
            try { $parsed = $raw | ConvertFrom-Json } catch { $parsed = $null }
        }
        return [ordered]@{
            ok = $true
            status = [int]$resp.StatusCode
            elapsedMs = [long]$sw.ElapsedMilliseconds
            bodyLength = $raw.Length
            bodyHash = Get-ShortHash $raw
            errorType = ""
            parsed = $parsed
        }
    } catch {
        $sw.Stop()
        $raw = Get-ResponseBodyFromException $_.Exception
        $status = -1
        if ($null -ne $_.Exception.Response) {
            try { $status = [int]$_.Exception.Response.StatusCode } catch { $status = -1 }
        }
        return [ordered]@{
            ok = $false
            status = $status
            elapsedMs = [long]$sw.ElapsedMilliseconds
            bodyLength = $raw.Length
            bodyHash = Get-ShortHash $raw
            errorType = $_.Exception.GetType().Name
            parsed = $null
        }
    }
}

function Summarize-Models($Result) {
    $count = 0
    if ($null -ne $Result.parsed) {
        $data = Get-JsonValue $Result.parsed "data"
        if ($null -ne $data) {
            $count = @($data).Count
        }
    }
    return [ordered]@{
        ok = $Result.ok
        status = $Result.status
        elapsedMs = $Result.elapsedMs
        bodyLength = $Result.bodyLength
        bodyHash = $Result.bodyHash
        modelCount = $count
        errorType = $Result.errorType
    }
}

function Summarize-OpenAiChat($Result) {
    $content = ""
    $thinking = ""
    $finishReason = ""
    if ($null -ne $Result.parsed) {
        $choices = Get-JsonValue $Result.parsed "choices"
        $first = @($choices | Select-Object -First 1)
        if ($first.Count -gt 0) {
            $message = Get-JsonValue $first[0] "message"
            $content = [string](Get-JsonValue $message "content")
            $thinking = [string](Get-JsonValue $message "thinking")
            if (Is-Blank $thinking) {
                $thinking = [string](Get-JsonValue $message "reasoning_content")
            }
            $finishReason = [string](Get-JsonValue $first[0] "finish_reason")
        }
    }
    return [ordered]@{
        ok = $Result.ok
        status = $Result.status
        elapsedMs = $Result.elapsedMs
        bodyLength = $Result.bodyLength
        bodyHash = $Result.bodyHash
        contentLength = $content.Length
        thinkingLength = $thinking.Length
        blankResponse = ($Result.ok -and [string]::IsNullOrWhiteSpace($content))
        doneReason = $finishReason
        errorType = $Result.errorType
    }
}

function Summarize-NativeChat($Result) {
    $content = ""
    $thinking = ""
    $doneReason = ""
    if ($null -ne $Result.parsed) {
        $message = Get-JsonValue $Result.parsed "message"
        $content = [string](Get-JsonValue $message "content")
        $thinking = [string](Get-JsonValue $message "thinking")
        $doneReason = [string](Get-JsonValue $Result.parsed "done_reason")
    }
    return [ordered]@{
        ok = $Result.ok
        status = $Result.status
        elapsedMs = $Result.elapsedMs
        bodyLength = $Result.bodyLength
        bodyHash = $Result.bodyHash
        contentLength = $content.Length
        thinkingLength = $thinking.Length
        blankResponse = ($Result.ok -and [string]::IsNullOrWhiteSpace($content))
        doneReason = $doneReason
        errorType = $Result.errorType
    }
}

function Get-AttemptScore {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        $Result
    )
    if ($null -eq $Result) {
        return [ordered]@{
            route = $Name
            score = 0
            verdict = "not_run"
            negativeSignal = $false
            negativeSignalCount = 0
            status = $null
            elapsedMs = $null
            contentLength = $null
            thinkingLength = $null
        }
    }

    $score = 0
    $verdict = "unusable"
    $negativeSignals = New-Object System.Collections.Generic.List[string]
    $contentLength = [int]$Result.contentLength
    $thinkingLength = [int]$Result.thinkingLength
    $elapsedMs = [long]$Result.elapsedMs

    if (-not $Result.ok) {
        $status = [int]$Result.status
        if ($status -eq -1) {
            $verdict = "transport_error"
        } elseif ($status -ge 500) {
            $verdict = "upstream_error"
        } elseif ($status -ge 400) {
            $verdict = "request_error"
        } else {
            $verdict = "error"
        }
        $negativeSignals.Add($verdict)
        $score = 0
    } elseif ($Result.blankResponse) {
        if ($thinkingLength -gt 0) {
            $verdict = "thinking_only"
            $score = 25
            $negativeSignals.Add("thinking_without_content")
        } else {
            $verdict = "blank_response"
            $score = 15
            $negativeSignals.Add("blank_response")
        }
    } elseif ($contentLength -gt 0) {
        $verdict = "usable"
        $score = 100
    }

    if ($elapsedMs -gt ($TimeoutSeconds * 850)) {
        $negativeSignals.Add("slow_response")
        $score = [Math]::Max(0, $score - 10)
    }

    return [ordered]@{
        route = $Name
        score = [int]([Math]::Max(0, [Math]::Min(100, $score)))
        verdict = $verdict
        negativeSignal = ($negativeSignals.Count -gt 0)
        negativeSignalCount = $negativeSignals.Count
        status = $Result.status
        elapsedMs = $Result.elapsedMs
        contentLength = $contentLength
        thinkingLength = $thinkingLength
    }
}

function Get-RecommendedRoute($OpenAiScore, $NativeScore) {
    $openScore = if ($null -eq $OpenAiScore) { 0 } else { [int]$OpenAiScore.score }
    $nativeScoreValue = if ($null -eq $NativeScore) { 0 } else { [int]$NativeScore.score }
    if ($nativeScoreValue -ge 50 -and $nativeScoreValue -gt $openScore) {
        return "native_ollama"
    }
    if ($openScore -ge 50) {
        return "openai_compatible"
    }
    if ($nativeScoreValue -ge 50) {
        return "native_ollama"
    }
    return "evidence_needed"
}

function New-SmokeHistoryRow {
    param(
        [Parameter(Mandatory = $true)]$OpenAiScore,
        $NativeScore,
        [Parameter(Mandatory = $true)][string]$RecommendedRoute,
        [Parameter(Mandatory = $true)][bool]$DebugTrigger,
        [Parameter(Mandatory = $true)][int]$NegativeSignalCount,
        [Parameter(Mandatory = $true)][int]$SecretPatternHits
    )
    return [ordered]@{
        observedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
        endpointHost = $endpointHost
        modelHash = $modelHash
        modelLength = $effectiveModel.Length
        promptHash = $promptHash
        promptLength = $ProbePrompt.Length
        openAiScore = [int]$OpenAiScore.score
        openAiVerdict = [string]$OpenAiScore.verdict
        nativeScore = if ($null -eq $NativeScore) { $null } else { [int]$NativeScore.score }
        nativeVerdict = if ($null -eq $NativeScore) { "not_run" } else { [string]$NativeScore.verdict }
        recommendedRoute = $RecommendedRoute
        debugTrigger = $DebugTrigger
        negativeSignalCount = $NegativeSignalCount
        secretPatternHits = $SecretPatternHits
    }
}

function Append-SmokeHistory {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Row
    )
    $line = $Row | ConvertTo-Json -Depth 8 -Compress
    Add-Content -LiteralPath $Path -Value $line -Encoding UTF8
}

function Get-CumulativeSignalStats {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Window = 12
    )
    $rows = @()
    if (Test-Path -LiteralPath $Path) {
        $rows = @(Get-Content -LiteralPath $Path -Tail $Window -ErrorAction SilentlyContinue |
            ForEach-Object {
                try { $_ | ConvertFrom-Json } catch { $null }
            } |
            Where-Object { $null -ne $_ })
    }
    $sampleCount = $rows.Count
    $negativeCount = 0
    $triggerCount = 0
    $openAiTotal = 0
    $nativeTotal = 0
    $nativeSamples = 0
    foreach ($row in $rows) {
        $negativeCount += [int]($row.negativeSignalCount)
        if ($row.debugTrigger -eq $true) { $triggerCount++ }
        $openAiTotal += [int]($row.openAiScore)
        if ($null -ne $row.nativeScore) {
            $nativeTotal += [int]($row.nativeScore)
            $nativeSamples++
        }
    }
    $avgOpenAi = if ($sampleCount -gt 0) { [Math]::Round($openAiTotal / $sampleCount, 3) } else { 0 }
    $avgNative = if ($nativeSamples -gt 0) { [Math]::Round($nativeTotal / $nativeSamples, 3) } else { $null }
    $pressure = if ($sampleCount -gt 0) { [Math]::Round($negativeCount / $sampleCount, 3) } else { 0 }
    return [ordered]@{
        sampleCount = $sampleCount
        window = $Window
        negativeSignalCount = $negativeCount
        debugTriggerCount = $triggerCount
        negativeSignalPressure = $pressure
        averageOpenAiScore = $avgOpenAi
        averageNativeScore = $avgNative
        thresholdExceeded = ($sampleCount -gt 0 -and ($pressure -ge 1.0 -or $triggerCount -ge [Math]::Min(3, $sampleCount)))
    }
}

function Invoke-NativeChat {
    $nativeBase = Get-NativeOllamaBaseUrl $effectiveBaseUrl
    $nativeBody = @{
        model = $effectiveModel
        messages = @(@{ role = "user"; content = $ProbePrompt })
        stream = $false
        think = $false
        options = @{
            temperature = 0
            num_predict = 32
        }
    } | ConvertTo-Json -Depth 8 -Compress
    return Summarize-NativeChat (Invoke-JsonRequest -Uri "$nativeBase/api/chat" -Method "POST" -Body $nativeBody)
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ReportPath) | Out-Null

if ($StaticOnly) {
    Write-Host "[AWX][local-llm-smoke] staticOnly=true script=$PSCommandPath"
    exit 0
}

$effectiveBaseUrl = Normalize-OpenAiBaseUrl $BaseUrl
$effectiveModel = if (Is-Blank $Model) { "qwen3:8b" } else { $Model.Trim() }
$endpointHost = Get-EndpointHost $effectiveBaseUrl
$modelHash = Get-ShortHash $effectiveModel
$promptHash = Get-ShortHash $ProbePrompt

$models = Summarize-Models (Invoke-JsonRequest -Uri "$effectiveBaseUrl/models" -Method "GET")

$chatBody = @{
    model = $effectiveModel
    messages = @(@{ role = "user"; content = $ProbePrompt })
    stream = $false
    temperature = 0
    max_tokens = 32
} | ConvertTo-Json -Depth 8 -Compress
$nativeChat = $null
if ($NativeOllamaCompare -and $NativeFirst) {
    $nativeChat = Invoke-NativeChat
}

$openAiChat = Summarize-OpenAiChat (Invoke-JsonRequest -Uri "$effectiveBaseUrl/chat/completions" -Method "POST" -Body $chatBody)

if ($NativeOllamaCompare -and -not $NativeFirst) {
    $nativeChat = Invoke-NativeChat
}

$secretPatternHits = 0
foreach ($result in @($models, $openAiChat, $nativeChat)) {
    if ($null -eq $result) { continue }
    foreach ($value in @($result.bodyHash, $result.errorType, $result.doneReason)) {
        if ($null -eq $value) { continue }
        $secretPatternHits += ([regex]::Matches([string]$value, 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}')).Count
    }
}

$nativeUsable = ($null -ne $nativeChat -and $nativeChat.ok -and (-not $nativeChat.blankResponse))
$openAiUsable = ($openAiChat.ok -and (-not $openAiChat.blankResponse))
$openAiAttemptScore = Get-AttemptScore -Name "openai_compatible" -Result $openAiChat
$nativeAttemptScore = if ($null -ne $nativeChat) { Get-AttemptScore -Name "native_ollama" -Result $nativeChat } else { $null }
$negativeSignalCount = @($openAiAttemptScore, $nativeAttemptScore | Where-Object { $null -ne $_ -and $_.negativeSignal }).Count
$recommendedRoute = Get-RecommendedRoute $openAiAttemptScore $nativeAttemptScore
$debugTrigger = ($negativeSignalCount -gt 0 -or -not ($openAiUsable -or $nativeUsable))
$HistoryPath = Join-Path $ResolvedOutputDir "$SmokeName.history.jsonl"
$historyRow = New-SmokeHistoryRow -OpenAiScore $openAiAttemptScore `
    -NativeScore $nativeAttemptScore `
    -RecommendedRoute $recommendedRoute `
    -DebugTrigger $debugTrigger `
    -NegativeSignalCount $negativeSignalCount `
    -SecretPatternHits $secretPatternHits
Append-SmokeHistory -Path $HistoryPath -Row $historyRow
$cumulativeSignals = Get-CumulativeSignalStats -Path $HistoryPath
$artifact = [ordered]@{
    ok = ($models.ok -and ($openAiUsable -or $nativeUsable))
    endpointHost = $endpointHost
    modelHash = $modelHash
    modelLength = $effectiveModel.Length
    promptHash = $promptHash
    promptLength = $ProbePrompt.Length
    timeoutSeconds = $TimeoutSeconds
    nativeFirst = [bool]$NativeFirst
    metadata = $models
    openAiCompatible = $openAiChat
    nativeOllama = $nativeChat
    attemptScores = [ordered]@{
        openAiCompatible = $openAiAttemptScore
        nativeOllama = $nativeAttemptScore
    }
    recommendedRoute = $recommendedRoute
    debugTrigger = $debugTrigger
    negativeSignalCount = $negativeSignalCount
    historyPath = $HistoryPath
    cumulativeSignals = $cumulativeSignals
    secretPatternHits = $secretPatternHits
}

$artifact | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $ReportPath -Encoding UTF8

$nativeSummary = ""
if ($null -ne $nativeChat) {
    $nativeSummary = " nativeStatus=$($nativeChat.status) nativeContentLength=$($nativeChat.contentLength) nativeThinkingLength=$($nativeChat.thinkingLength) nativeBlankResponse=$($nativeChat.blankResponse) nativeScore=$($nativeAttemptScore.score)"
}
Write-Host "[AWX][local-llm-smoke] endpointHost=$endpointHost modelHash=$modelHash modelLength=$($effectiveModel.Length) metadataStatus=$($models.status) metadataModelCount=$($models.modelCount) chatStatus=$($openAiChat.status) chatElapsedMs=$($openAiChat.elapsedMs) chatContentLength=$($openAiChat.contentLength) chatThinkingLength=$($openAiChat.thinkingLength) chatBlankResponse=$($openAiChat.blankResponse) openAiScore=$($openAiAttemptScore.score)$nativeSummary recommendedRoute=$recommendedRoute debugTrigger=$debugTrigger negativeSignalCount=$negativeSignalCount cumulativePressure=$($cumulativeSignals.negativeSignalPressure) thresholdExceeded=$($cumulativeSignals.thresholdExceeded) secretPatternHits=$secretPatternHits report=$ReportPath"

if (-not $artifact.ok) {
    exit 1
}
exit 0
