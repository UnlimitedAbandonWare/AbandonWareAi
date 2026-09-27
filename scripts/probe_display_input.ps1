# probe_display_input.ps1 — bounded display-input round-trip probe.
#
# Real contract (DisplayConversateController):
#   bootstrap : Connection{assistId:null, epoch:0, clientId:[a-f0-9]{32}}
#               -> View{assistId, epoch, state, ...}
#   input     : Input{assistId, epoch, clientId, requestId:[a-f0-9-]{36},
#                     text(<=2000), eventOrder[<=12 of focus|activate|input|
#                     change|compositionstart|compositionend|escape|submit]}
#   poll      : Connection{assistId, epoch, clientId} -> View.card.requestId
#
# Correlate the card by OUR requestId only: a stale card inside its TTL is the
# event-query contract, not our answer.
#
# UTF-8 is explicit everywhere: PowerShell 5.x serialises JSON bodies with the
# system codepage unless given UTF-8 BYTES - that is what turned earlier Korean
# questions into mojibake (evidence digest mismatches). Never send a raw string
# body from a literal containing non-ASCII text.
#
# Usage:  powershell -NoProfile -File scripts\probe_display_input.ps1 -Question "한국 질문"
param(
    [string]$Question = "한국의 수도는 어디야?",
    [string]$BaseUrl = "http://127.0.0.1:18180",
    [int]$PollAttempts = 15,
    [int]$PollDelayMs = 1500
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

# Cookie container = session continuity (the assist binding lives behind it).
# -SessionVariable works on both PowerShell 5.1 and 7+; it is created by the
# first (script-level) call so the session escapes the function scope.
# owner() gate: X-Display-Client=1 + an Origin matching scheme/host/port.
$headers = @{ "X-Display-Client" = "1"; "Origin" = $BaseUrl }

function Send-Json([string]$Url, $Body) {
    $json = ($Body | ConvertTo-Json -Compress)
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)   # UTF-8 bytes, not PS default
    return Invoke-RestMethod -Uri $Url -Method Post -WebSession $session `
        -Headers $headers `
        -ContentType "application/json; charset=utf-8" -Body $bytes -TimeoutSec 15
}

$clientId  = ([Guid]::NewGuid().ToString("N"))      # [a-f0-9]{32}
$requestId = [Guid]::NewGuid().ToString()           # [a-f0-9-]{36}
Write-Output "clientId=$clientId"
Write-Output "requestId=$requestId"
Write-Output "question=$Question"

$bootJson = (@{ assistId = $null; epoch = 0; clientId = $clientId } | ConvertTo-Json -Compress)
$view = Invoke-RestMethod -Uri "$BaseUrl/api/assist/display/bootstrap" -Method Post `
    -SessionVariable session -Headers $headers -ContentType "application/json; charset=utf-8" `
    -Body ([Text.Encoding]::UTF8.GetBytes($bootJson)) -TimeoutSec 15
$assistId = $view.assistId
$epoch = $view.epoch
Write-Output ("bootstrap assistId=$assistId epoch=$epoch state=" + $view.state)

$view = Send-Json "$BaseUrl/api/assist/display/input" @{
    assistId = $assistId; epoch = $epoch; clientId = $clientId
    requestId = $requestId; text = $Question
    eventOrder = @("input", "submit")
}
$epoch = $view.epoch
Write-Output ("input state=" + $view.state + " reason=" + $view.reason)

for ($i = 0; $i -lt $PollAttempts; $i++) {
    Start-Sleep -Milliseconds $PollDelayMs
    $view = Send-Json "$BaseUrl/api/assist/display/poll" @{
        assistId = $assistId; epoch = $epoch; clientId = $clientId
    }
    $card = $view.card
    if ($card -and $card.requestId -eq $requestId) {
        Write-Output ("card matched requestId: " + ($card | ConvertTo-Json -Compress))
        exit 0
    }
    Write-Output ("poll[$i] state=" + $view.state + " reason=" + $view.reason +
        " cardRequestId=" + $(if ($card) { $card.requestId } else { "none" }))
}
Write-Output "NO_MATCHING_CARD_AFTER_POLLS"
exit 1
