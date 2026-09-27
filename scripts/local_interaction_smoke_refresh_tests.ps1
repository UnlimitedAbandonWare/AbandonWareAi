param()

$ErrorActionPreference = 'Stop'
$failed = 0

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Details = ''
    )
    if ($Condition) {
        Write-Host "[local-smoke-refresh-test][PASS] $Name"
        return
    }
    $script:failed += 1
    Write-Host "[local-smoke-refresh-test][FAIL] $Name :: $Details"
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$scriptPath = Join-Path $repoRoot 'scripts\refresh_local_interaction_smokes.ps1'
$root = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-refresh-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $root | Out-Null

try {
    $probeDir = Join-Path $root 'probe'
    New-Item -ItemType Directory -Force -Path $probeDir | Out-Null
    $computerProbePath = Join-Path $probeDir 'computer-counts.json'
    $browserProbePath = Join-Path $probeDir 'browser-proof.json'
    @{
        schemaVersion = 'awx.local.computer_use_count_probe.v1'
        reachable = $true
        helperCountOnly = $true
        appCount = 7
        runningCount = 5
        windowCount = 3
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $computerProbePath -Encoding UTF8
    @{
        reachable = $true
        localhost = $true
        publicDomain = $false
        targetHost = 'localhost'
        screenshotCaptured = $true
        targetContentVisible = $true
        statusClass = 'ui_visible'
        browserSurface = 'iab'
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $browserProbePath -Encoding UTF8

    $completed = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $root -ComputerProbePath $computerProbePath -BrowserProbePath $browserProbePath
    $exitCode = $LASTEXITCODE
    Assert-True 'refresh script exits zero with safe count-only probes' ($exitCode -eq 0) "exit=$exitCode output=$completed"

    $computerPath = Join-Path $root 'var\codex-smoke\computer-use-smoke.json'
    $browserPath = Join-Path $root 'var\codex-smoke\browser-ui-smoke.json'
    $summaryPath = Join-Path $root 'var\codex-smoke\local-interaction-smoke-refresh.summary.json'
    Assert-True 'refresh script writes computer smoke' (Test-Path $computerPath) "missing $computerPath"
    Assert-True 'refresh script writes browser smoke' (Test-Path $browserPath) "missing $browserPath"
    Assert-True 'refresh script writes summary' (Test-Path $summaryPath) "missing $summaryPath"

    $computer = Get-Content -Raw -LiteralPath $computerPath | ConvertFrom-Json
    $browser = Get-Content -Raw -LiteralPath $browserPath | ConvertFrom-Json
    $summary = Get-Content -Raw -LiteralPath $summaryPath | ConvertFrom-Json

    Assert-True 'computer smoke is current and count-only' (
        $computer.ok -eq $true -and
        $computer.reachable -eq $true -and
        [int]$computer.appCount -eq 7 -and
        [int]$computer.runningCount -eq 5 -and
        [int]$computer.windowCount -eq 3 -and
        [int]$computer.targetableWindowCount -eq 3 -and
        $computer.guiOnly -eq $true -and
        $computer.noTerminalAutomation -eq $true -and
        $computer.supportingOnly -eq $true -and
        $computer.countOnly -eq $true -and
        $computer.helperCountOnly -eq $true -and
        [string]$computer.probeSchemaVersion -eq 'awx.local.computer_use_count_probe.v1' -and
        $computer.storesRawAppNames -eq $false -and
        $computer.storesAppNames -eq $false -and
        $computer.storesWindowTitles -eq $false -and
        [int]$computer.ageMinutes -eq 0 -and
        [int]$computer.staleAfterMinutes -eq 60 -and
        $computer.stale -eq $false -and
        [int]$computer.rawSecretPatternHits -eq 0 -and
        -not [string]::IsNullOrWhiteSpace([string]$computer.generatedAt)
    ) "computer=$($computer | ConvertTo-Json -Compress)"
    Assert-True 'summary preserves Computer Use helper count-only proof metadata' (
        $summary.computerUse.helperCountOnly -eq $true -and
        [string]$summary.computerUse.probeSchemaVersion -eq 'awx.local.computer_use_count_probe.v1'
    ) "summary=$($summary | ConvertTo-Json -Compress)"

    Assert-True 'browser smoke is current and path-free' (
        $browser.ok -eq $true -and
        $browser.reachable -eq $true -and
        $browser.localhost -eq $true -and
        $browser.screenshotCaptured -eq $true -and
        $browser.targetContentVisible -eq $true -and
        $browser.storesRawUrl -eq $false -and
        $browser.storesScreenshotPath -eq $false -and
        [int]$browser.ageMinutes -eq 0 -and
        [int]$browser.staleAfterMinutes -eq 60 -and
        $browser.stale -eq $false -and
        [string]$browser.targetHost -eq 'localhost' -and
        [int]$browser.rawSecretPatternHits -eq 0 -and
        -not [string]::IsNullOrWhiteSpace([string]$browser.generatedAt)
    ) "browser=$($browser | ConvertTo-Json -Compress)"

    $combined = @(
        Get-Content -Raw -LiteralPath $computerPath
        Get-Content -Raw -LiteralPath $browserPath
        Get-Content -Raw -LiteralPath $summaryPath
    ) -join "`n"
    Assert-True 'local smoke artifacts do not store raw paths urls or secrets' (
        -not ($combined -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}')
    ) $combined
    Assert-True 'summary reports both smokes ok' ($summary.ok -eq $true -and $summary.computerUse.ok -eq $true -and $summary.browserUse.ok -eq $true) "summary=$($summary | ConvertTo-Json -Compress)"

    $zeroCountRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-zero-count-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $zeroCountRoot | Out-Null
    $zeroCountProbeDir = Join-Path $zeroCountRoot 'probe'
    New-Item -ItemType Directory -Force -Path $zeroCountProbeDir | Out-Null
    $zeroComputerProbePath = Join-Path $zeroCountProbeDir 'computer-counts.json'
    $zeroBrowserProbePath = Join-Path $zeroCountProbeDir 'browser-proof.json'
    @{
        schemaVersion = 'awx.local.computer_use_count_probe.v1'
        reachable = $true
        helperCountOnly = $true
        appCount = 0
        runningCount = 0
        windowCount = 0
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $zeroComputerProbePath -Encoding UTF8
    @{
        reachable = $true
        localhost = $true
        publicDomain = $false
        targetHost = 'localhost'
        screenshotCaptured = $true
        targetContentVisible = $true
        statusClass = 'ui_visible'
        browserSurface = 'iab'
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $zeroBrowserProbePath -Encoding UTF8

    $zeroCountCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $zeroCountRoot -ComputerProbePath $zeroComputerProbePath -BrowserProbePath $zeroBrowserProbePath
    $zeroCountExitCode = $LASTEXITCODE
    Assert-True 'refresh script keeps zero-count Computer helper proof as safe pending' ($zeroCountExitCode -eq 2) "exit=$zeroCountExitCode output=$zeroCountCompleted"
    $zeroCountSummary = Get-Content -Raw -LiteralPath (Join-Path $zeroCountRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json') | ConvertFrom-Json
    Assert-True 'zero-count Computer proof is supporting evidence, not unsafe' (
        $zeroCountSummary.computerUse.ok -eq $false -and
        $zeroCountSummary.computerUse.reachable -eq $true -and
        $zeroCountSummary.computerUse.safePendingProof -eq $true -and
        [int]$zeroCountSummary.computerUse.targetableWindowCount -eq 0 -and
        [string]$zeroCountSummary.computerUse.evidenceNeeded -eq 'computer_use_zero_count_supporting_evidence_missing' -and
        [int]$zeroCountSummary.rawSecretPatternHits -eq 0
    ) "zeroCountSummary=$($zeroCountSummary | ConvertTo-Json -Compress)"
    if (Test-Path -LiteralPath $zeroCountRoot) {
        Remove-Item -LiteralPath $zeroCountRoot -Recurse -Force
    }

    $defaultRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-default-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $defaultRoot 'var\codex-smoke') | Out-Null
    @{
        reachable = $true
        appCount = 8
        runningCount = 6
        windowCount = 4
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $defaultRoot 'var\codex-smoke\computer-use-count-probe.json') -Encoding UTF8
    @{
        reachable = $true
        localhost = $true
        publicDomain = $false
        targetHost = 'localhost'
        screenshotCaptured = $true
        targetContentVisible = $true
        statusClass = 'ui_visible'
        browserSurface = 'iab'
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $defaultRoot 'var\codex-smoke\browser-ui-current-probe.json') -Encoding UTF8

    $defaultCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $defaultRoot
    $defaultExitCode = $LASTEXITCODE
    Assert-True 'refresh script auto-loads default local probe artifacts' ($defaultExitCode -eq 0) "exit=$defaultExitCode output=$defaultCompleted"
    $defaultSummary = Get-Content -Raw -LiteralPath (Join-Path $defaultRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json') | ConvertFrom-Json
    Assert-True 'default local probe artifacts preserve browser and computer proof' (
        $defaultSummary.ok -eq $true -and
        $defaultSummary.computerUse.ok -eq $true -and
        [int]$defaultSummary.computerUse.appCount -eq 8 -and
        [int]$defaultSummary.computerUse.targetableWindowCount -eq 4 -and
        $defaultSummary.computerUse.guiOnly -eq $true -and
        $defaultSummary.computerUse.noTerminalAutomation -eq $true -and
        $defaultSummary.computerUse.supportingOnly -eq $true -and
        $defaultSummary.browserUse.ok -eq $true -and
        $defaultSummary.browserUse.targetAccepted -eq $true -and
        [int]$defaultSummary.rawSecretPatternHits -eq 0
    ) "defaultSummary=$($defaultSummary | ConvertTo-Json -Compress)"
    if (Test-Path -LiteralPath $defaultRoot) {
        Remove-Item -LiteralPath $defaultRoot -Recurse -Force
    }

    $windowOnlyRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-window-only-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $windowOnlyRoot 'var\codex-smoke') | Out-Null
    @{
        reachable = $true
        appCount = 0
        runningCount = 0
        targetableWindowCount = 9
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $windowOnlyRoot 'var\codex-smoke\computer-use-count-probe.json') -Encoding UTF8
    @{
        reachable = $true
        localhost = $true
        publicDomain = $false
        targetHost = 'localhost'
        screenshotCaptured = $true
        targetContentVisible = $true
        statusClass = 'ui_visible'
        browserSurface = 'iab'
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $windowOnlyRoot 'var\codex-smoke\browser-ui-current-probe.json') -Encoding UTF8

    $windowOnlyCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $windowOnlyRoot
    $windowOnlyExitCode = $LASTEXITCODE
    Assert-True 'refresh script accepts Computer Use window-count proof when app list is empty' ($windowOnlyExitCode -eq 0) "exit=$windowOnlyExitCode output=$windowOnlyCompleted"
    $windowOnlySummary = Get-Content -Raw -LiteralPath (Join-Path $windowOnlyRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json') | ConvertFrom-Json
    Assert-True 'window-count-only Computer proof remains count-only and redacted' (
        $windowOnlySummary.ok -eq $true -and
        $windowOnlySummary.computerUse.ok -eq $true -and
        [int]$windowOnlySummary.computerUse.appCount -eq 0 -and
        [int]$windowOnlySummary.computerUse.targetableWindowCount -eq 9 -and
        $windowOnlySummary.computerUse.storesRawAppNames -eq $false -and
        $windowOnlySummary.computerUse.storesWindowTitles -eq $false -and
        [int]$windowOnlySummary.rawSecretPatternHits -eq 0
    ) "windowOnlySummary=$($windowOnlySummary | ConvertTo-Json -Compress)"
    if (Test-Path -LiteralPath $windowOnlyRoot) {
        Remove-Item -LiteralPath $windowOnlyRoot -Recurse -Force
    }

    $staleProbeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-stale-probe-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $staleProbeRoot 'var\codex-smoke') | Out-Null
    $staleComputerProbePath = Join-Path $staleProbeRoot 'var\codex-smoke\computer-use-count-probe.json'
    $staleBrowserProbePath = Join-Path $staleProbeRoot 'var\codex-smoke\browser-ui-current-probe.json'
    @{
        reachable = $true
        appCount = 8
        runningCount = 6
        windowCount = 4
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $staleComputerProbePath -Encoding UTF8
    @{
        reachable = $true
        localhost = $true
        publicDomain = $false
        targetHost = 'localhost'
        screenshotCaptured = $true
        targetContentVisible = $true
        statusClass = 'ui_visible'
        browserSurface = 'iab'
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $staleBrowserProbePath -Encoding UTF8
    $oldProbeTime = [DateTime]::UtcNow.AddMinutes(-125)
    [System.IO.File]::SetLastWriteTimeUtc($staleComputerProbePath, $oldProbeTime)
    [System.IO.File]::SetLastWriteTimeUtc($staleBrowserProbePath, $oldProbeTime)

    $staleProbeCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $staleProbeRoot
    $staleProbeExitCode = $LASTEXITCODE
    Assert-True 'refresh script rejects stale default local probe artifacts' ($staleProbeExitCode -eq 2) "exit=$staleProbeExitCode output=$staleProbeCompleted"
    $staleProbeSummary = Get-Content -Raw -LiteralPath (Join-Path $staleProbeRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json') | ConvertFrom-Json
    Assert-True 'stale default local probe artifacts are not promoted to ok' (
        $staleProbeSummary.ok -eq $false -and
        $staleProbeSummary.decision -eq 'evidence_needed' -and
        $staleProbeSummary.computerUse.ok -eq $false -and
        $staleProbeSummary.browserUse.ok -eq $false -and
        [string]$staleProbeSummary.computerUse.evidenceNeeded -eq 'computer_use_probe_stale' -and
        [string]$staleProbeSummary.browserUse.evidenceNeeded -eq 'browser_ui_probe_stale' -and
        [int]$staleProbeSummary.rawSecretPatternHits -eq 0
    ) "staleProbeSummary=$($staleProbeSummary | ConvertTo-Json -Compress)"
    if (Test-Path -LiteralPath $staleProbeRoot) {
        Remove-Item -LiteralPath $staleProbeRoot -Recurse -Force
    }

    $browserPluginRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-browser-plugin-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $browserPluginRoot 'var\codex-smoke') | Out-Null
    @{
        reachable = $true
        appCount = 8
        runningCount = 6
        windowCount = 4
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $browserPluginRoot 'var\codex-smoke\computer-use-count-probe.json') -Encoding UTF8
    @{
        reachable = $true
        targetHost = '127.0.0.1:8080'
        targetPathKind = 'root'
        bodyTextLength = 1489
        buttonCount = 3
        hasChatCue = $true
        hasErrorCue = $false
        inputCount = 2
        linkCount = 6
        statusCue = 'page_loaded'
        titleLength = 14
        screenshotByteCount = 52350
        screenshotStored = $false
        rawUrlStored = $false
        rawTextStored = $false
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $browserPluginRoot 'var\codex-smoke\browser-ui-current-probe.json') -Encoding UTF8

    $browserPluginCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $browserPluginRoot
    $browserPluginExitCode = $LASTEXITCODE
    Assert-True 'refresh script accepts browser plugin count-only localhost proof' ($browserPluginExitCode -eq 0) "exit=$browserPluginExitCode output=$browserPluginCompleted"
    $browserPluginSummary = Get-Content -Raw -LiteralPath (Join-Path $browserPluginRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json') | ConvertFrom-Json
    Assert-True 'browser plugin proof is normalized without raw url text or screenshot path' (
        $browserPluginSummary.ok -eq $true -and
        $browserPluginSummary.browserUse.ok -eq $true -and
        $browserPluginSummary.browserUse.localhost -eq $true -and
        $browserPluginSummary.browserUse.targetAccepted -eq $true -and
        $browserPluginSummary.browserUse.screenshotCaptured -eq $true -and
        $browserPluginSummary.browserUse.targetContentVisible -eq $true -and
        [string]$browserPluginSummary.browserUse.browserSurface -eq 'iab' -and
        [string]$browserPluginSummary.browserUse.statusClass -eq 'page_loaded' -and
        $browserPluginSummary.browserUse.storesRawUrl -eq $false -and
        $browserPluginSummary.browserUse.storesScreenshotPath -eq $false -and
        [int]$browserPluginSummary.rawSecretPatternHits -eq 0
    ) "browserPluginSummary=$($browserPluginSummary | ConvertTo-Json -Compress)"
    $browserPluginCombined = @(
        Get-Content -Raw -LiteralPath (Join-Path $browserPluginRoot 'var\codex-smoke\browser-ui-smoke.json')
        Get-Content -Raw -LiteralPath (Join-Path $browserPluginRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json')
    ) -join "`n"
    Assert-True 'browser plugin normalized proof remains path-url-secret safe' (
        -not ($browserPluginCombined -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}')
    ) $browserPluginCombined
    if (Test-Path -LiteralPath $browserPluginRoot) {
        Remove-Item -LiteralPath $browserPluginRoot -Recurse -Force
    }

    $noScreenshotRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-no-screenshot-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $noScreenshotRoot 'var\codex-smoke') | Out-Null
    @{
        reachable = $true
        appCount = 8
        runningCount = 6
        windowCount = 4
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $noScreenshotRoot 'var\codex-smoke\computer-use-count-probe.json') -Encoding UTF8
    @{
        reachable = $true
        targetHost = '127.0.0.1:8080'
        hasChatCue = $true
        hasErrorCue = $false
        screenshotCaptured = $false
        screenshotByteCount = 0
        rawUrlStored = $false
        rawTextStored = $false
        screenshotPathStored = $false
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $noScreenshotRoot 'var\codex-smoke\browser-ui-current-probe.json') -Encoding UTF8

    $noScreenshotCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $noScreenshotRoot
    $noScreenshotExitCode = $LASTEXITCODE
    Assert-True 'refresh script does not promote browser proof without a screenshot' ($noScreenshotExitCode -eq 2) "exit=$noScreenshotExitCode output=$noScreenshotCompleted"
    $noScreenshotSummary = Get-Content -Raw -LiteralPath (Join-Path $noScreenshotRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json') | ConvertFrom-Json
    Assert-True 'browser proof without screenshot remains evidence-needed' (
        $noScreenshotSummary.ok -eq $false -and
        $noScreenshotSummary.browserUse.ok -eq $false -and
        $noScreenshotSummary.browserUse.safePendingProof -eq $true -and
        [string]$noScreenshotSummary.browserUse.evidenceNeeded -eq 'browser_ui_screenshot_missing' -and
        [int]$noScreenshotSummary.rawSecretPatternHits -eq 0
    ) "noScreenshotSummary=$($noScreenshotSummary | ConvertTo-Json -Compress)"
    if (Test-Path -LiteralPath $noScreenshotRoot) {
        Remove-Item -LiteralPath $noScreenshotRoot -Recurse -Force
    }

    $unsafeBrowserRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-unsafe-browser-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $unsafeBrowserRoot 'var\codex-smoke') | Out-Null
    @{
        reachable = $true
        appCount = 8
        runningCount = 6
        windowCount = 4
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $unsafeBrowserRoot 'var\codex-smoke\computer-use-count-probe.json') -Encoding UTF8
    @{
        reachable = $true
        targetHost = '127.0.0.1:8080'
        hasChatCue = $true
        hasErrorCue = $false
        screenshotByteCount = 52350
        rawUrlStored = $true
        rawTextStored = $true
        screenshotPathStored = $true
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $unsafeBrowserRoot 'var\codex-smoke\browser-ui-current-probe.json') -Encoding UTF8

    $unsafeBrowserCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $unsafeBrowserRoot
    $unsafeBrowserExitCode = $LASTEXITCODE
    Assert-True 'refresh script rejects browser proof that stored raw artifacts' ($unsafeBrowserExitCode -eq 4) "exit=$unsafeBrowserExitCode output=$unsafeBrowserCompleted"
    $unsafeBrowserSummary = Get-Content -Raw -LiteralPath (Join-Path $unsafeBrowserRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json') | ConvertFrom-Json
    Assert-True 'unsafe browser proof is not promoted to ok' (
        $unsafeBrowserSummary.ok -eq $false -and
        $unsafeBrowserSummary.decision -eq 'secret-leak-risk' -and
        $unsafeBrowserSummary.browserUse.ok -eq $false -and
        $unsafeBrowserSummary.browserUse.safePendingProof -eq $false -and
        [string]$unsafeBrowserSummary.browserUse.evidenceNeeded -eq 'browser_ui_raw_artifact_storage_present' -and
        [int]$unsafeBrowserSummary.rawSecretPatternHits -eq 0
    ) "unsafeBrowserSummary=$($unsafeBrowserSummary | ConvertTo-Json -Compress)"
    $unsafeBrowserCombined = @(
        Get-Content -Raw -LiteralPath (Join-Path $unsafeBrowserRoot 'var\codex-smoke\browser-ui-smoke.json')
        Get-Content -Raw -LiteralPath (Join-Path $unsafeBrowserRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json')
    ) -join "`n"
    Assert-True 'unsafe browser output still masks raw artifacts' (
        -not ($unsafeBrowserCombined -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}')
    ) $unsafeBrowserCombined
    if (Test-Path -LiteralPath $unsafeBrowserRoot) {
        Remove-Item -LiteralPath $unsafeBrowserRoot -Recurse -Force
    }

    $unsafeComputerRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-unsafe-computer-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $unsafeComputerRoot 'var\codex-smoke') | Out-Null
    @{
        reachable = $true
        appCount = 8
        runningCount = 6
        windowCount = 4
        rawAppNamesStored = $false
        rawWindowTitlesStored = $true
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $unsafeComputerRoot 'var\codex-smoke\computer-use-count-probe.json') -Encoding UTF8
    @{
        reachable = $true
        localhost = $true
        publicDomain = $false
        targetHost = 'localhost'
        screenshotCaptured = $true
        targetContentVisible = $true
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $unsafeComputerRoot 'var\codex-smoke\browser-ui-current-probe.json') -Encoding UTF8

    $unsafeComputerCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $unsafeComputerRoot
    $unsafeComputerExitCode = $LASTEXITCODE
    Assert-True 'refresh script rejects computer proof that stored raw artifacts' ($unsafeComputerExitCode -eq 4) "exit=$unsafeComputerExitCode output=$unsafeComputerCompleted"
    $unsafeComputerSummary = Get-Content -Raw -LiteralPath (Join-Path $unsafeComputerRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json') | ConvertFrom-Json
    Assert-True 'unsafe computer proof is not promoted to ok' (
        $unsafeComputerSummary.ok -eq $false -and
        $unsafeComputerSummary.decision -eq 'secret-leak-risk' -and
        $unsafeComputerSummary.computerUse.ok -eq $false -and
        [string]$unsafeComputerSummary.computerUse.evidenceNeeded -eq 'computer_use_raw_artifact_storage_present' -and
        [int]$unsafeComputerSummary.rawSecretPatternHits -eq 0
    ) "unsafeComputerSummary=$($unsafeComputerSummary | ConvertTo-Json -Compress)"
    $unsafeComputerCombined = @(
        Get-Content -Raw -LiteralPath (Join-Path $unsafeComputerRoot 'var\codex-smoke\computer-use-smoke.json')
        Get-Content -Raw -LiteralPath (Join-Path $unsafeComputerRoot 'var\codex-smoke\local-interaction-smoke-refresh.summary.json')
    ) -join "`n"
    Assert-True 'unsafe computer output still masks raw artifacts' (
        -not ($unsafeComputerCombined -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}')
    ) $unsafeComputerCombined
    if (Test-Path -LiteralPath $unsafeComputerRoot) {
        Remove-Item -LiteralPath $unsafeComputerRoot -Recurse -Force
    }

    $pendingRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-pending-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $pendingRoot | Out-Null
    $pendingProbeDir = Join-Path $pendingRoot 'probe'
    New-Item -ItemType Directory -Force -Path $pendingProbeDir | Out-Null
    $pendingComputerProbePath = Join-Path $pendingProbeDir 'computer-counts.json'
    $pendingBrowserProbePath = Join-Path $pendingProbeDir 'browser-proof.json'
    @{
        reachable = $true
        appCount = 4
        runningCount = 2
        windowCount = 1
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $pendingComputerProbePath -Encoding UTF8
    @{
        reachable = $false
        localhost = $true
        publicDomain = $false
        targetHost = 'localhost'
        screenshotCaptured = $false
        targetContentVisible = $false
        statusClass = 'browser_plugin_bootstrap_failed'
        browserSurface = 'iab'
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $pendingBrowserProbePath -Encoding UTF8

    $pendingCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $pendingRoot -ComputerProbePath $pendingComputerProbePath -BrowserProbePath $pendingBrowserProbePath
    $pendingExitCode = $LASTEXITCODE
    Assert-True 'refresh script returns evidence-needed for unavailable localhost browser proof' ($pendingExitCode -eq 2) "exit=$pendingExitCode output=$pendingCompleted"

    $pendingBrowserPath = Join-Path $pendingRoot 'var\codex-smoke\browser-ui-smoke.json'
    $pendingBrowser = Get-Content -Raw -LiteralPath $pendingBrowserPath | ConvertFrom-Json
    Assert-True 'browser unavailable smoke is fresh safe-pending proof' (
        $pendingBrowser.ok -eq $false -and
        $pendingBrowser.decision -eq 'evidence_needed' -and
        $pendingBrowser.safePendingProof -eq $true -and
        $pendingBrowser.artifactPresent -eq $true -and
        $pendingBrowser.artifactGeneratedAt -eq $true -and
        $pendingBrowser.artifactFresh -eq $true -and
        [string]$pendingBrowser.artifactPath -eq 'var/codex-smoke/browser-ui-smoke.json' -and
        $pendingBrowser.storesRawUrl -eq $false -and
        $pendingBrowser.storesScreenshotPath -eq $false -and
        [string]$pendingBrowser.evidenceNeeded -eq 'browser_plugin_bootstrap_failed' -and
        [string]$pendingBrowser.nextAction -eq 'reload_bundled_browser_plugin' -and
        [int]$pendingBrowser.rawSecretPatternHits -eq 0
    ) "pendingBrowser=$($pendingBrowser | ConvertTo-Json -Compress)"

    $pendingCombined = Get-Content -Raw -LiteralPath $pendingBrowserPath
    Assert-True 'browser pending proof remains path-url-secret safe' (
        -not ($pendingCombined -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}')
    ) $pendingCombined

    if (Test-Path -LiteralPath $pendingRoot) {
        Remove-Item -LiteralPath $pendingRoot -Recurse -Force
    }

    $staleBootstrapRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-stale-bootstrap-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $staleBootstrapRoot 'var\codex-smoke') | Out-Null
    @{
        reachable = $true
        appCount = 4
        runningCount = 2
        windowCount = 1
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $staleBootstrapRoot 'var\codex-smoke\computer-use-count-probe.json') -Encoding UTF8
    $staleBootstrapProbePath = Join-Path $staleBootstrapRoot 'var\codex-smoke\browser-ui-current-probe.json'
    @{
        reachable = $false
        localhost = $true
        publicDomain = $false
        targetHost = 'localhost'
        screenshotCaptured = $false
        targetContentVisible = $false
        statusClass = 'browser_plugin_bootstrap_failed'
        browserSurface = 'iab'
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $staleBootstrapProbePath -Encoding UTF8
    [System.IO.File]::SetLastWriteTimeUtc($staleBootstrapProbePath, (Get-Date).ToUniversalTime().AddMinutes(-61))

    $staleBootstrapCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $staleBootstrapRoot
    $staleBootstrapExitCode = $LASTEXITCODE
    Assert-True 'stale browser bootstrap probe remains evidence-needed' ($staleBootstrapExitCode -eq 2) "exit=$staleBootstrapExitCode output=$staleBootstrapCompleted"
    $staleBootstrapBrowser = Get-Content -Raw -LiteralPath (Join-Path $staleBootstrapRoot 'var\codex-smoke\browser-ui-smoke.json') | ConvertFrom-Json
    Assert-True 'stale browser bootstrap probe does not select plugin reload' (
        [string]$staleBootstrapBrowser.evidenceNeeded -eq 'browser_ui_probe_stale' -and
        [string]$staleBootstrapBrowser.nextAction -eq 'rerun_browser_local_ui_smoke'
    ) "staleBootstrapBrowser=$($staleBootstrapBrowser | ConvertTo-Json -Compress)"
    if (Test-Path -LiteralPath $staleBootstrapRoot) {
        Remove-Item -LiteralPath $staleBootstrapRoot -Recurse -Force
    }

    $cueOnlyRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-cue-only-bootstrap-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $cueOnlyRoot 'var\codex-smoke') | Out-Null
    @{
        reachable = $true
        appCount = 4
        runningCount = 2
        windowCount = 1
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $cueOnlyRoot 'var\codex-smoke\computer-use-count-probe.json') -Encoding UTF8
    @{
        reachable = $false
        localhost = $true
        publicDomain = $false
        targetHost = 'localhost'
        screenshotCaptured = $false
        targetContentVisible = $false
        statusCue = 'browser_plugin_bootstrap_failed'
        browserSurface = 'iab'
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath (Join-Path $cueOnlyRoot 'var\codex-smoke\browser-ui-current-probe.json') -Encoding UTF8

    $cueOnlyCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $cueOnlyRoot
    $cueOnlyExitCode = $LASTEXITCODE
    Assert-True 'cue-only browser bootstrap probe remains evidence-needed' ($cueOnlyExitCode -eq 2) "exit=$cueOnlyExitCode output=$cueOnlyCompleted"
    $cueOnlyBrowser = Get-Content -Raw -LiteralPath (Join-Path $cueOnlyRoot 'var\codex-smoke\browser-ui-smoke.json') | ConvertFrom-Json
    Assert-True 'cue-only browser bootstrap probe does not select trusted plugin classification' (
        [string]$cueOnlyBrowser.statusClass -eq 'browser_plugin_bootstrap_failed' -and
        [string]$cueOnlyBrowser.evidenceNeeded -eq 'browser_ui_screenshot_missing' -and
        [string]$cueOnlyBrowser.nextAction -eq 'rerun_browser_local_ui_smoke'
    ) "cueOnlyBrowser=$($cueOnlyBrowser | ConvertTo-Json -Compress)"
    if (Test-Path -LiteralPath $cueOnlyRoot) {
        Remove-Item -LiteralPath $cueOnlyRoot -Recurse -Force
    }

    $unfocusedRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-local-smoke-unfocused-" + [System.Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $unfocusedRoot | Out-Null
    $unfocusedProbeDir = Join-Path $unfocusedRoot 'probe'
    New-Item -ItemType Directory -Force -Path $unfocusedProbeDir | Out-Null
    $unfocusedComputerProbePath = Join-Path $unfocusedProbeDir 'computer-counts.json'
    $unfocusedBrowserProbePath = Join-Path $unfocusedProbeDir 'browser-proof.json'
    @{
        reachable = $true
        appCount = 4
        runningCount = 2
        windowCount = 1
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $unfocusedComputerProbePath -Encoding UTF8
    @{
        reachable = $true
        localhost = $false
        publicDomain = $false
        targetAccepted = $false
        targetHost = 'unknown'
        screenshotCaptured = $true
        targetContentVisible = $false
        statusClass = 'page'
        browserSurface = 'in_app_browser'
        storesRawUrl = $false
        storesScreenshotPath = $false
        rawSecretPatternHits = 0
    } | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $unfocusedBrowserProbePath -Encoding UTF8

    $unfocusedCompleted = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Root $unfocusedRoot -ComputerProbePath $unfocusedComputerProbePath -BrowserProbePath $unfocusedBrowserProbePath
    $unfocusedExitCode = $LASTEXITCODE
    Assert-True 'refresh script returns evidence-needed when browser is on unrelated current tab' ($unfocusedExitCode -eq 2) "exit=$unfocusedExitCode output=$unfocusedCompleted"

    $unfocusedBrowserPath = Join-Path $unfocusedRoot 'var\codex-smoke\browser-ui-smoke.json'
    $unfocusedBrowser = Get-Content -Raw -LiteralPath $unfocusedBrowserPath | ConvertFrom-Json
    Assert-True 'unrelated current browser tab is fresh safe-pending proof' (
        $unfocusedBrowser.ok -eq $false -and
        $unfocusedBrowser.decision -eq 'evidence_needed' -and
        $unfocusedBrowser.safePendingProof -eq $true -and
        $unfocusedBrowser.reachable -eq $true -and
        $unfocusedBrowser.targetAccepted -eq $false -and
        [string]$unfocusedBrowser.evidenceNeeded -eq 'browser_ui_target_not_current' -and
        [string]$unfocusedBrowser.nextAction -eq 'open_local_or_public_ui_target_then_rerun_browser_smoke' -and
        $unfocusedBrowser.storesRawUrl -eq $false -and
        $unfocusedBrowser.storesScreenshotPath -eq $false -and
        [int]$unfocusedBrowser.rawSecretPatternHits -eq 0
    ) "unfocusedBrowser=$($unfocusedBrowser | ConvertTo-Json -Compress)"

    $unfocusedCombined = Get-Content -Raw -LiteralPath $unfocusedBrowserPath
    Assert-True 'unfocused browser pending proof remains path-url-secret safe' (
        -not ($unfocusedCombined -match 'http://|https://|[A-Za-z]:\\|Bearer\s+[A-Za-z0-9._~+/-]+=*|sk-[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}')
    ) $unfocusedCombined

    if (Test-Path -LiteralPath $unfocusedRoot) {
        Remove-Item -LiteralPath $unfocusedRoot -Recurse -Force
    }
} finally {
    if (Test-Path -LiteralPath $root) {
        Remove-Item -LiteralPath $root -Recurse -Force
    }
}

Write-Host "[local-smoke-refresh-test][SUMMARY] failed=$failed"
if ($failed -gt 0) {
    exit 1
}
exit 0
