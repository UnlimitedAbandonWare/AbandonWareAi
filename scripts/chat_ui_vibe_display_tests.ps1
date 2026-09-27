$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'chat_ui_vibe_lifecycle.ps1')
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-display-provenance-' + [guid]::NewGuid().ToString('N'))
$fixtureServer = $null
try {
    New-Item -ItemType Directory -Force -Path (Join-Path $fixtureRoot 'assets/display'), (Join-Path $fixtureRoot 'assets/interview'), (Join-Path $fixtureRoot 'js') | Out-Null
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'assets/display/index.html') -Value 'synthetic display' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'assets/display/app.js') -Value 'synthetic display asset' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'chat-ui') -Value 'synthetic chat' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'js/chat.js') -Value 'synthetic chat asset' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'assets/interview/studio.html') -Value 'synthetic studio' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'assets/interview/studio.js') -Value 'synthetic studio asset' -Encoding ASCII
    $socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $socket.Start(); $fixturePort = $socket.LocalEndpoint.Port; $socket.Stop()
    $fixtureServer = Start-Process -FilePath (Get-Command python).Source -WindowStyle Hidden -PassThru `
        -ArgumentList @('-m', 'http.server', $fixturePort, '--bind', '127.0.0.1', '--directory', ('"' + $fixtureRoot + '"')) `
        -RedirectStandardOutput (Join-Path $fixtureRoot 'server.out') -RedirectStandardError (Join-Path $fixtureRoot 'server.err')
    $ready = $false
    for ($attempt = 0; $attempt -lt 20 -and -not $ready; $attempt++) {
        try { $ready = (Invoke-WebRequest "http://127.0.0.1:$fixturePort/chat-ui" -UseBasicParsing -TimeoutSec 1).StatusCode -eq 200 }
        catch { Start-Sleep -Milliseconds 100 }
    }
    if (-not $ready) { throw 'fixture-server-not-ready' }
    # OS identities are synthetic here; HTTP path and byte verification execute unchanged.
    function Get-NetTCPConnection { [pscustomobject]@{ OwningProcess = 42 } }
    function Get-AwxProcessIdentity { [pscustomobject]@{ creationDate = [datetimeoffset]::UtcNow.ToString('o') } }
    function Get-AwxProcessLineage { [pscustomobject]@{ rows = @([pscustomobject]@{processId=41}); hash='synthetic' } }
    function Get-CimInstance { [pscustomobject]@{ CommandLine='synthetic-run-id' } }
    $params = @{ Root=$fixtureRoot; LauncherPid=41; LaunchBoundaryUtc=[datetime]::UtcNow.AddMinutes(-1); RunId='synthetic-run-id'; ServerPort=$fixturePort }
    $display = Test-AwxFreshRuntimeProvenance @params -SourceAssetPath 'assets/display/app.js' -UiSurface 'meta-display'
    if (-not $display.ok) { throw ('display-asset-provenance-failed:' + $display.reason) }
    $legacy = Test-AwxFreshRuntimeProvenance @params -SourceAssetPath 'js/chat.js'
    if (-not $legacy.ok) { throw 'legacy-default-regression' }
    $studio = Test-AwxFreshRuntimeProvenance @params -SourceAssetPath 'assets/interview/studio.js' -UiSurface 'rag-studio'
    if (-not $studio.ok) { throw ('studio-asset-provenance-failed:' + $studio.reason) }
    # demo.interview.enabled=true forwards /chat-ui to the interview page; the
    # check must verify the asset that page actually loads.
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'chat-ui') -Value 'synthetic interview <script src="/assets/interview/app.js"></script>' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'assets/interview/app.js') -Value 'synthetic interview asset' -Encoding ASCII
    $interview = Test-AwxFreshRuntimeProvenance @params -SourceAssetPath 'js/chat.js'
    if (-not $interview.ok) { throw ('interview-asset-provenance-failed:' + $interview.reason) }
    Set-Content -LiteralPath (Join-Path $fixtureRoot 'chat-ui') -Value 'synthetic chat' -Encoding ASCII
    $studioMismatch = Test-AwxFreshRuntimeProvenance @params -SourceAssetPath 'assets/display/app.js' -UiSurface 'rag-studio'
    if ($studioMismatch.ok -or $studioMismatch.reason -ne 'served-asset-hash-mismatch') { throw 'studio-mismatched-asset-accepted' }
    $mismatch = Test-AwxFreshRuntimeProvenance @params -SourceAssetPath 'js/chat.js' -UiSurface 'meta-display'
    if ($mismatch.ok -or $mismatch.reason -ne 'served-asset-hash-mismatch') { throw 'mismatched-asset-accepted' }
    $invalidRejected = $false
    try { Test-AwxFreshRuntimeProvenance @params -SourceAssetPath 'js/chat.js' -UiSurface 'https://example.com' | Out-Null }
    catch { $invalidRejected = $true }
    if (-not $invalidRejected) { throw 'arbitrary-surface-accepted' }
    Write-Output 'PASS display-surface, studio-surface, legacy-default, interview-default, mismatched-bytes, studio-mismatched-bytes, invalid-surface; syntheticIdentity=true'
} finally {
    if ($null -ne $fixtureServer -and -not $fixtureServer.HasExited) { $fixtureServer.Kill(); $fixtureServer.WaitForExit() }
    $resolvedFixture = [IO.Path]::GetFullPath($fixtureRoot)
    $expectedParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolvedFixture.StartsWith($expectedParent, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path $resolvedFixture -Leaf).StartsWith('awx-display-provenance-')) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force -ErrorAction SilentlyContinue
    }
}
