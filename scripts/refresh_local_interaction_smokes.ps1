param(
    [string]$Root = ".",
    [string]$ComputerProbePath = "",
    [string]$BrowserProbePath = ""
)

$ErrorActionPreference = 'Stop'
$DefaultProbeStaleAfterMinutes = 60

function Count-SecretPatterns {
    param([AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrEmpty($Text)) {
        return 0
    }
    return ([regex]::Matches($Text, 'Bearer\s+[A-Za-z0-9._~+/-]+=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}')).Count
}

function Get-Count {
    param($Value)
    if ($null -eq $Value) {
        return 0
    }
    $parsed = 0
    if ([int]::TryParse([string]$Value, [ref]$parsed)) {
        return [Math]::Max(0, $parsed)
    }
    return 0
}

function Get-Flag {
    param($Value)
    if ($null -eq $Value) {
        return $false
    }
    if ($Value -is [bool]) {
        return [bool]$Value
    }
    return ([string]$Value).Trim().ToLowerInvariant() -in @('true', '1', 'yes', 'y')
}

function Get-SafeToken {
    param(
        [AllowEmptyString()][string]$Text,
        [string]$Fallback = "unknown"
    )
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $Fallback
    }
    $clean = $Text.Trim()
    $clean = [regex]::Replace($clean, 'https?://', '')
    $clean = [regex]::Replace($clean, '[^A-Za-z0-9_.:-]', '_')
    if ($clean.Length -gt 80) {
        $clean = $clean.Substring(0, 80)
    }
    if ([string]::IsNullOrWhiteSpace($clean)) {
        return $Fallback
    }
    return $clean
}

function Test-LocalBrowserHost {
    param([AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $false
    }
    $hostText = $Text.Trim().ToLowerInvariant()
    $hostText = [regex]::Replace($hostText, '^https?://', '')
    $hostText = ($hostText -split '[/?#]', 2)[0]
    return $hostText -match '^(localhost|127(?:\.\d{1,3}){3}|\[?::1\]?)(:\d+)?$'
}

function Read-ProbeJson {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{
            raw = ""
            data = $null
            secretHits = 0
            present = $false
            parsed = $false
            fresh = $false
            ageMinutes = -1
            freshnessStatus = "missing"
        }
    }
    $lastWriteUtc = [System.IO.File]::GetLastWriteTimeUtc($Path)
    $ageMinutes = [Math]::Max(0, [int][Math]::Floor(((Get-Date).ToUniversalTime() - $lastWriteUtc).TotalMinutes))
    $fresh = $ageMinutes -lt $DefaultProbeStaleAfterMinutes
    $freshnessStatus = if ($fresh) { "current" } else { "stale" }
    $raw = Get-Content -Raw -LiteralPath $Path
    $secretHits = Count-SecretPatterns -Text $raw
    try {
        $data = $raw | ConvertFrom-Json
        return [pscustomobject]@{
            raw = $raw
            data = $data
            secretHits = $secretHits
            present = $true
            parsed = $true
            fresh = $fresh
            ageMinutes = $ageMinutes
            freshnessStatus = $freshnessStatus
        }
    } catch {
        return [pscustomobject]@{
            raw = $raw
            data = $null
            secretHits = $secretHits
            present = $true
            parsed = $false
            fresh = $fresh
            ageMinutes = $ageMinutes
            freshnessStatus = $freshnessStatus
        }
    }
}

function Write-Json {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $dir = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    $Value | ConvertTo-Json -Depth 40 | Set-Content -LiteralPath $Path -Encoding UTF8
}

$resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
$smokeRoot = Join-Path $resolvedRoot 'var\codex-smoke'
New-Item -ItemType Directory -Force -Path $smokeRoot | Out-Null
if ([string]::IsNullOrWhiteSpace($ComputerProbePath)) {
    $ComputerProbePath = Join-Path $smokeRoot 'computer-use-count-probe.json'
}
if ([string]::IsNullOrWhiteSpace($BrowserProbePath)) {
    $BrowserProbePath = Join-Path $smokeRoot 'browser-ui-current-probe.json'
}

$generatedAt = (Get-Date).ToUniversalTime().ToString('o')
$freshAgeMinutes = 0
$defaultStaleAfterMinutes = 60
$computerProbe = Read-ProbeJson -Path $ComputerProbePath
$browserProbe = Read-ProbeJson -Path $BrowserProbePath

$computer = $computerProbe.data
$computerSecretHits = $computerProbe.secretHits + (Get-Count $computer.rawSecretPatternHits)
$computerRawArtifactStoragePresent = (Get-Flag $computer.rawAppNamesStored) -or
    (Get-Flag $computer.storesRawAppNames) -or
    (Get-Flag $computer.appNamesStored) -or
    (Get-Flag $computer.storesAppNames) -or
    (Get-Flag $computer.rawWindowTitlesStored) -or
    (Get-Flag $computer.storesRawWindowTitles) -or
    (Get-Flag $computer.windowTitlesStored) -or
    (Get-Flag $computer.storesWindowTitles)
$computerReachable = $computerProbe.parsed -and (Get-Flag $computer.reachable)
$computerAppCount = Get-Count $computer.appCount
$computerRunningCount = Get-Count $computer.runningCount
$computerWindowCount = Get-Count $computer.targetableWindowCount
if ($computerWindowCount -le 0) {
    $computerWindowCount = Get-Count $computer.windowCount
}
$computerProbeSchemaVersion = Get-SafeToken -Text ([string]$computer.schemaVersion) -Fallback 'unknown'
$computerHelperCountOnly = ((Get-Flag $computer.helperCountOnly) -or (Get-Flag $computer.countOnly)) -and -not $computerRawArtifactStoragePresent
$computerProbeFresh = [bool]$computerProbe.fresh
$computerCountObserved = ($computerAppCount -gt 0) -or ($computerWindowCount -gt 0)
$computerOk = $computerProbeFresh -and $computerReachable -and $computerCountObserved -and $computerSecretHits -eq 0 -and -not $computerRawArtifactStoragePresent
$computerSafePending = (-not $computerOk) -and $computerProbeFresh -and $computerReachable -and $computerHelperCountOnly -and $computerSecretHits -eq 0 -and -not $computerRawArtifactStoragePresent
$computerEvidenceNeeded = if ($computerOk) {
    ''
} elseif ($computerRawArtifactStoragePresent) {
    'computer_use_raw_artifact_storage_present'
} elseif (-not $computerProbeFresh) {
    'computer_use_probe_stale'
} elseif ($computerSafePending) {
    'computer_use_zero_count_supporting_evidence_missing'
} else {
    'computer_use_smoke_evidence_needed'
}
$computerSmoke = [ordered]@{
    schemaVersion = 'awx.local.computer_use_smoke.v1'
    generatedAt = $generatedAt
    ok = $computerOk
    decision = if ($computerOk) { 'ok' } else { 'evidence_needed' }
    safePendingProof = $computerSafePending
    reachable = $computerReachable
    guiOnly = $true
    noTerminalAutomation = $true
    supportingOnly = $true
    countOnly = $true
    helperCountOnly = $computerHelperCountOnly
    probeSchemaVersion = $computerProbeSchemaVersion
    appCount = $computerAppCount
    runningCount = $computerRunningCount
    windowCount = $computerWindowCount
    targetableWindowCount = $computerWindowCount
    probeFresh = $computerProbeFresh
    probeAgeMinutes = [int]$computerProbe.ageMinutes
    probeFreshnessStatus = [string]$computerProbe.freshnessStatus
    storesRawAppNames = $false
    storesAppNames = $false
    storesWindowTitles = $false
    evidenceNeeded = $computerEvidenceNeeded
    nextAction = if ($computerOk) { '' } elseif (-not $computerProbeFresh) { 'rerun_computer_use_lightweight_smoke' } else { 'rerun_computer_use_lightweight_smoke' }
    ageMinutes = $freshAgeMinutes
    staleAfterMinutes = $defaultStaleAfterMinutes
    stale = -not $computerProbeFresh
    secretHits = $computerSecretHits
    rawSecretPatternHits = $computerSecretHits
}

$browser = $browserProbe.data
$browserSecretHits = $browserProbe.secretHits + (Get-Count $browser.rawSecretPatternHits)
$browserRawArtifactStoragePresent = (Get-Flag $browser.rawUrlStored) -or
    (Get-Flag $browser.storesRawUrl) -or
    (Get-Flag $browser.rawTextStored) -or
    (Get-Flag $browser.storesRawText) -or
    (Get-Flag $browser.screenshotPathStored) -or
    (Get-Flag $browser.storesScreenshotPath)
$browserReachable = $browserProbe.parsed -and (Get-Flag $browser.reachable)
$browserLocalhost = Get-Flag $browser.localhost
$browserPublicDomain = Get-Flag $browser.publicDomain
$browserTargetAccepted = $browserLocalhost -or $browserPublicDomain
$browserTargetHost = Get-SafeToken -Text ([string]$browser.targetHost) -Fallback 'unknown'
$browserTargetHostIsLocal = Test-LocalBrowserHost -Text ([string]$browser.targetHost)
if (-not $browserTargetHostIsLocal -and $browserTargetHost -ne 'unknown') {
    $browserTargetHostIsLocal = Test-LocalBrowserHost -Text $browserTargetHost
}
if (-not $browserLocalhost -and $browserTargetHostIsLocal) {
    $browserLocalhost = $true
    $browserTargetAccepted = $true
}
$browserScreenshotCaptured = Get-Flag $browser.screenshotCaptured
if (-not $browserScreenshotCaptured -and (Get-Count $browser.screenshotByteCount) -gt 0) {
    $browserScreenshotCaptured = $true
}
$browserTargetContentVisible = Get-Flag $browser.targetContentVisible
$browserHasChatCue = Get-Flag $browser.hasChatCue
$browserHasErrorCue = Get-Flag $browser.hasErrorCue
if (-not $browserTargetContentVisible -and $browserReachable -and $browserTargetAccepted -and $browserHasChatCue -and -not $browserHasErrorCue) {
    $browserTargetContentVisible = $true
}
$browserExplicitStatusClass = Get-SafeToken -Text ([string]$browser.statusClass) -Fallback ''
$browserStatusClass = $browserExplicitStatusClass
if ([string]::IsNullOrWhiteSpace($browserStatusClass)) {
    $browserStatusClass = Get-SafeToken -Text ([string]$browser.statusCue) -Fallback 'unknown'
}
$browserSurface = Get-SafeToken -Text ([string]$browser.browserSurface) -Fallback ''
if ([string]::IsNullOrWhiteSpace($browserSurface)) {
    $browserSurface = Get-SafeToken -Text ([string]$browser.source) -Fallback 'unknown'
}
if ($browserSurface -eq 'unknown' -and $browserReachable -and $browserTargetAccepted) {
    $browserSurface = 'iab'
}
$browserProbeFresh = [bool]$browserProbe.fresh
$browserPluginBootstrapFailed = $browserProbeFresh -and -not $browserReachable -and $browserExplicitStatusClass -eq 'browser_plugin_bootstrap_failed'
$browserOk = $browserProbeFresh -and $browserReachable -and $browserTargetAccepted -and $browserScreenshotCaptured -and $browserTargetContentVisible -and $browserSecretHits -eq 0 -and -not $browserRawArtifactStoragePresent
$browserTargetNotCurrent = $browserProbeFresh -and $browserReachable -and -not $browserTargetAccepted -and $browserSecretHits -eq 0 -and -not $browserRawArtifactStoragePresent
$browserSafePending = (-not $browserOk) -and $browserProbeFresh -and ($browserTargetAccepted -or $browserTargetNotCurrent) -and $browserSecretHits -eq 0 -and -not $browserRawArtifactStoragePresent
$browserEvidenceNeeded = if ($browserOk) {
    ''
} elseif ($browserRawArtifactStoragePresent) {
    'browser_ui_raw_artifact_storage_present'
} elseif (-not $browserProbeFresh) {
    'browser_ui_probe_stale'
} elseif ($browserTargetNotCurrent) {
    'browser_ui_target_not_current'
} elseif ($browserPluginBootstrapFailed) {
    'browser_plugin_bootstrap_failed'
} elseif (-not $browserScreenshotCaptured) {
    'browser_ui_screenshot_missing'
} else {
    'browser_ui_smoke_evidence_needed'
}
$browserNextAction = if ($browserOk) {
    ''
} elseif ($browserPluginBootstrapFailed) {
    'reload_bundled_browser_plugin'
} elseif ($browserTargetNotCurrent) {
    'open_local_or_public_ui_target_then_rerun_browser_smoke'
} elseif ($browserPublicDomain -and -not $browserLocalhost) {
    'rerun_browser_public_domain_ui_smoke'
} else {
    'rerun_browser_local_ui_smoke'
}
$browserSmoke = [ordered]@{
    schemaVersion = 'awx.local.browser_ui_smoke.v1'
    generatedAt = $generatedAt
    ok = $browserOk
    decision = if ($browserOk) { 'ok' } else { 'evidence_needed' }
    safePendingProof = $browserSafePending
    artifactPresent = $true
    artifactGeneratedAt = $true
    artifactFresh = $true
    artifactPath = 'var/codex-smoke/browser-ui-smoke.json'
    reachable = $browserReachable
    localhost = $browserLocalhost
    publicDomain = $browserPublicDomain
    targetAccepted = $browserTargetAccepted
    targetHost = $browserTargetHost
    probeFresh = $browserProbeFresh
    probeAgeMinutes = [int]$browserProbe.ageMinutes
    probeFreshnessStatus = [string]$browserProbe.freshnessStatus
    screenshotCaptured = $browserScreenshotCaptured
    statusClass = $browserStatusClass
    targetContentVisible = $browserTargetContentVisible
    browserSurface = $browserSurface
    storesRawUrl = $false
    storesScreenshotPath = $false
    evidenceNeeded = $browserEvidenceNeeded
    nextAction = $browserNextAction
    ageMinutes = $freshAgeMinutes
    staleAfterMinutes = $defaultStaleAfterMinutes
    stale = -not $browserProbeFresh
    secretHits = $browserSecretHits
    rawSecretPatternHits = $browserSecretHits
}

$computerPath = Join-Path $smokeRoot 'computer-use-smoke.json'
$browserPath = Join-Path $smokeRoot 'browser-ui-smoke.json'
$summaryPath = Join-Path $smokeRoot 'local-interaction-smoke-refresh.summary.json'
Write-Json -Path $computerPath -Value $computerSmoke
Write-Json -Path $browserPath -Value $browserSmoke
$summary = [ordered]@{
    schemaVersion = 'awx.local.interaction_smoke_refresh.v1'
    generatedAt = $generatedAt
    ok = ($computerOk -and $browserOk)
    decision = if ($computerOk -and $browserOk) { 'ok' } elseif ($computerSecretHits -gt 0 -or $browserSecretHits -gt 0 -or $computerRawArtifactStoragePresent -or $browserRawArtifactStoragePresent) { 'secret-leak-risk' } else { 'evidence_needed' }
    computerUse = $computerSmoke
    browserUse = $browserSmoke
    mutationAllowed = $false
    rawSecretPatternHits = $computerSecretHits + $browserSecretHits
}
Write-Json -Path $summaryPath -Value $summary

Write-Host "[AWX][local-smoke-refresh] decision=$($summary.decision) computerOk=$computerOk browserOk=$browserOk secretHits=$($summary.rawSecretPatternHits)"
if ($summary.decision -eq 'secret-leak-risk') {
    exit 4
}
if ($summary.decision -ne 'ok') {
    exit 2
}
exit 0
