$ErrorActionPreference = 'Stop'
$launcher = Join-Path $PSScriptRoot 'start_rag_stack.ps1'
if (-not (Test-Path -LiteralPath $launcher)) { throw 'FAIL: one-click launcher is missing' }
. $launcher
$realStartSpring = ${function:Start-RagSpring}
$script:passed = 0
function Assert-RagTest($Condition, $Name) {
    if (-not $Condition) { throw "FAIL: $Name" }
    $script:passed++
}
function Expect-RagFailure([scriptblock]$Action, [string]$Reason) {
    try { & $Action; throw 'unexpected-success' } catch {
        Assert-RagTest ($_.Exception.Message -eq $Reason) $Reason
    }
}

# OS and network boundaries are replaced; the orchestration and decisions are real.
function Get-RagPortOwner { param($Port) return $script:owner }
function Get-RagHttp { param($Url, $Method = 'GET', $Body = '')
    if ($Url.EndsWith('/api/version')) { return @{status=200;content='{"version":"0.32.13"}';contentType='application/json'} }
    if ($Url.EndsWith('/api/tags')) { return @{status=200;content='{"models":[]}';contentType='application/json'} }
    if ($Url.EndsWith('/chat-ui')) { return @{status=200;content=$script:page;contentType='text/html'} }
    return @{status=$script:validationStatus;content='{}';contentType='application/json'}
}
$script:owner = @{processId=42;processName='ollama.exe'}
Assert-RagTest (Test-RagOllama -Port 11435) 'healthy Ollama identity accepted'
$script:owner = @{processId=42;processName='python.exe'}
Assert-RagTest (-not (Test-RagOllama -Port 11435)) 'generic HTTP service rejected as Ollama'
$script:page = '<script src="/assets/interview/app.js"></script>'
$script:validationStatus = 400
Assert-RagTest (Test-RagWeb -Port 8080) 'current RAG Display UI and validation accepted'
$script:page = '<script src="/js/chat.js?v=1"></script>'
Assert-RagTest (Test-RagWeb -Port 8080) 'legacy RAG UI and validation accepted'
$script:validationStatus = 404
Assert-RagTest (-not (Test-RagWeb -Port 8080)) 'page without RAG API rejected'
$script:page = '<html>login</html>'
$script:validationStatus = 400
Assert-RagTest (-not (Test-RagWeb -Port 8080)) 'generic login page rejected'

function Get-RagSpringCandidates { return $script:candidates }
function Test-RagWeb { param($Port) return $script:webReady }
$script:webReady = $true
$script:candidates = @(@{processId=81;port=18176})
$selected = Resolve-RagSpring -RequestedPort 0
Assert-RagTest ($selected.reuse -and $selected.port -eq 18176) 'reuse existing nondefault port'
Expect-RagFailure { Resolve-RagSpring -RequestedPort 8080 } 'existing-spring-port-mismatch'
$script:webReady = $false
Expect-RagFailure { Resolve-RagSpring -RequestedPort 0 } 'existing-spring-not-ready'
$script:candidates = @(@{processId=82;port=18177}, @{processId=81;port=18176})
$script:webReady = $true
$selected = Resolve-RagSpring -RequestedPort 0
Assert-RagTest ($selected.reuse -and $selected.port -eq 18176) 'multiple healthy servers reuse stable lowest port'
$selected = Resolve-RagSpring -RequestedPort 18177
Assert-RagTest ($selected.reuse -and $selected.port -eq 18177) 'explicit existing port wins'
$script:candidates = @()
$script:owner = @{processId=42;processName='python.exe'}
Expect-RagFailure { Resolve-RagSpring -RequestedPort 8080 } 'spring-port-conflict'
$script:owner = $null
$selected = Resolve-RagSpring -RequestedPort 0
Assert-RagTest (-not $selected.reuse -and $selected.port -eq 8080) 'cold default is 8080'

function Write-RagStage { param($Stage,$Status,$Message) }
function Test-RagOllama { param($Port) return $script:ollamaReady }
function Start-RagOllama { param($Port,$RunDirectory,$TimeoutSeconds) $script:events.Add('ollama-start'); $script:ollamaReady=$true }
function Start-RagSpring { param($Port,$RunDirectory,$TimeoutSeconds) $script:events.Add('spring-start'); return @{port=$Port;processId=100;reuse=$false} }
function Test-RagWeb { param($Port) return $true }
function Resolve-RagSpring { param($RequestedPort) return @{port=8080;processId=0;reuse=$script:reuse} }
$script:events = [Collections.Generic.List[string]]::new()
$script:reuse = $false; $script:ollamaReady = $false
$result = Invoke-RagServices -RequestedPort 0 -OllamaPort 11435 -RunDirectory 'unused' -TimeoutSeconds 5
Assert-RagTest (($script:events -join ',') -eq 'ollama-start,spring-start') 'cold dependency order'
Assert-RagTest ($result.port -eq 8080) 'cold result port'
$script:events.Clear(); $script:reuse = $true
$result = Invoke-RagServices -RequestedPort 0 -OllamaPort 11435 -RunDirectory 'unused' -TimeoutSeconds 5
Assert-RagTest ($script:events.Count -eq 0) 'warm rerun starts no process'
$script:ollamaReady = $false; $script:reuse = $false
function Start-RagOllama { param($Port,$RunDirectory,$TimeoutSeconds) throw 'ollama-child-exited' }
Expect-RagFailure { Invoke-RagServices -RequestedPort 0 -OllamaPort 11435 -RunDirectory 'unused' -TimeoutSeconds 5 } 'ollama-child-exited'
Assert-RagTest ($script:events.Count -eq 0) 'Ollama failure prevents Spring launch'
$script:ollamaReady = $true
function Start-RagSpring { param($Port,$RunDirectory,$TimeoutSeconds) throw 'spring-start-failed' }
Expect-RagFailure { Invoke-RagServices -RequestedPort 0 -OllamaPort 11435 -RunDirectory 'unused' -TimeoutSeconds 5 } 'spring-start-failed'
$script:ollamaReady = $false
Expect-RagFailure { Invoke-RagServices -RequestedPort 0 -OllamaPort 11435 -RunDirectory 'unused' -TimeoutSeconds 5 -CheckOnly } 'ollama-not-ready'
# Exercise the real Windows child-process bridge without starting another user service.
# The tiny helper stands in for Gradle/Spring only; quoting, environment inheritance,
# wait/exit handling, per-run paths and helper failure propagation are executed.
${function:Start-RagSpring} = $realStartSpring
function Get-RagSpringCandidates { return @() }
function Get-RagPortOwner { param($Port) return $null }
function Resolve-RagSpring { param($RequestedPort) return @{port=$RequestedPort;processId=0;reuse=$false} }
function Test-AwxOwnedRuntimeIdentity { param($Manifest,$Root) return @{ok=$true;listenerPid=101} }
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('rag launcher test ' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
$priorHelper = $script:RagListenerScript
$priorAutostart = $env:LOCAL_LLM_AUTOSTART
try {
    $script:RagListenerScript = Join-Path $fixture 'fake listener.ps1'
    @'
param($Port,[switch]$FixedPorts,$StatePath,$BuildHostId,$ProjectCacheDir,$GradleUserHome,$OutDir,$ReadyTimeoutSeconds)
@{ports=@{server=[int]$Port};autostart=$env:LOCAL_LLM_AUTOSTART;fixedPorts=[bool]$FixedPorts;hostId=$BuildHostId} | ConvertTo-Json | Set-Content -LiteralPath $StatePath
exit 0
'@ | Set-Content -LiteralPath $script:RagListenerScript -Encoding UTF8
    $env:LOCAL_LLM_AUTOSTART = 'true'
    $started = Start-RagSpring -Port 18080 -RunDirectory $fixture -TimeoutSeconds 10
    $childProof = Get-Content (Join-Path $fixture 'spring-owned.json') -Raw | ConvertFrom-Json
    Assert-RagTest ($started.processId -eq 101) 'real child bridge completes'
    Assert-RagTest ($childProof.autostart -eq 'false') 'child has one Ollama startup owner'
    Assert-RagTest ($env:LOCAL_LLM_AUTOSTART -eq 'true') 'parent startup environment restored'
    Assert-RagTest ($childProof.fixedPorts -and $childProof.hostId -eq 'desktop-rag-launcher') 'existing helper receives fixed port and isolated build host'
    @'
param($Port,[switch]$FixedPorts,$StatePath,$BuildHostId,$ProjectCacheDir,$GradleUserHome,$OutDir,$ReadyTimeoutSeconds)
@{status='verification-failed'} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $OutDir "chat-ui-vibe-listener-$Port.result.json")
exit 7
'@ | Set-Content -LiteralPath $script:RagListenerScript -Encoding UTF8
    Expect-RagFailure { Start-RagSpring -Port 18080 -RunDirectory $fixture -TimeoutSeconds 10 } 'spring-start-failed'
    @'
param($Port,[switch]$FixedPorts,$StatePath,$BuildHostId,$ProjectCacheDir,$GradleUserHome,$OutDir,$ReadyTimeoutSeconds)
Start-Sleep -Seconds 30
'@ | Set-Content -LiteralPath $script:RagListenerScript -Encoding UTF8
    Expect-RagFailure { Start-RagSpring -Port 18080 -RunDirectory $fixture -TimeoutSeconds 2 } 'spring-start-timeout'
    $remaining = @(Get-CimInstance Win32_Process -Filter "Name='powershell.exe'" | Where-Object { ([string]$_.CommandLine).Contains($fixture) })
    Assert-RagTest ($remaining.Count -eq 0) 'timed-out test child cleaned up'
} finally {
    $script:RagListenerScript = $priorHelper
    $env:LOCAL_LLM_AUTOSTART = $priorAutostart
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolvedFixture.StartsWith($tempPrefix,[StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolvedFixture -Leaf).StartsWith('rag launcher test ')) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
    }
}
# HTTPS remains opt-in, validates the served page, and CheckOnly never starts it.
$script:httpsEnabled = $false
$script:httpsStarts = 0
$script:remotePage = '<html>current-display</html>'
function Test-RagHttpsOptIn { return $script:httpsEnabled }
function Invoke-RagHttpsStart { param($PublicUrl,$BackendPort) $script:httpsStarts++ }
function Get-RagHttp { param($Url,$Method='GET',$Body='',$Headers=@{})
    if ($Url.EndsWith('/relay/poll')) {
        if ($Headers.Origin -ne 'http://127.0.0.1:18180' -or $Headers['X-Display-Client'] -ne '1') { return @{status=403;content='{}'} }
        if (($Body | ConvertFrom-Json).clientId -notmatch '^[a-f0-9]{32}$') { return @{status=400;content='{}'} }
        return @{status=200;content=(@{producerConnected=$script:producerConnected}|ConvertTo-Json)}
    }
    return @{status=200;content=$(if($Url.StartsWith('https:')){$script:remotePage}else{'<html>current-display</html>'})}
}
Assert-RagTest ((Ensure-RagDisplayHttps 'https://display.test/assets/display/index.html' 18180) -eq 'not_enabled') 'HTTPS disabled without opt-in'
Assert-RagTest ($script:httpsStarts -eq 0) 'no HTTPS start without opt-in'
$script:httpsEnabled = $true
Assert-RagTest ((Ensure-RagDisplayHttps 'https://display.test/assets/display/index.html' 18180 -CheckOnly) -eq 'tls-and-current-page-verified') 'HTTPS check verifies current page'
Assert-RagTest ($script:httpsStarts -eq 0) 'CheckOnly starts no HTTPS process'
$null = Ensure-RagDisplayHttps 'https://display.test/assets/display/index.html' 18180
Assert-RagTest ($script:httpsStarts -eq 1) 'opt-in starts existing HTTPS helper once'
$script:remotePage = '<html>stale-display</html>'
Expect-RagFailure { Ensure-RagDisplayHttps 'https://display.test/assets/display/index.html' 18180 -CheckOnly } 'meta-display-public-https-not-ready'
$script:producerConnected = $true
Assert-RagTest ((Get-RagDisplayBrowserUrl 'http://127.0.0.1:18180').EndsWith('/meta/index.html')) 'existing Fold6 producer keeps ownership'
$script:producerConnected = $false
Assert-RagTest ((Get-RagDisplayBrowserUrl 'http://127.0.0.1:18180').EndsWith('/display/index.html')) 'unclaimed control remains available'
Write-Output "PASS: $script:passed launcher behavior checks"
