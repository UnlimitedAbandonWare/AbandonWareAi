$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$listenerPath = Join-Path $PSScriptRoot 'chat_ui_vibe_listener.ps1'
$tokens = $null; $parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($listenerPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw 'listener-parse-failed' }
# Execute the real command construction, stopping at the OS process boundary.
$start = $ast.Find({ param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Start-ChatUiListener' }, $true)
# This is a command-construction fixture: do not load live credentials.
$keyLoad = ". (Join-Path `$PSScriptRoot 'use_project_keys.ps1') -Root `$root -Runtime | Out-Null"
if (-not $start.Extent.Text.Contains($keyLoad)) { throw 'launch-key-boundary-changed' }
Invoke-Expression ($start.Extent.Text.Replace($keyLoad, '$null = $null'))
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('awx-display-launch-' + [guid]::NewGuid().ToString('N'))
function Start-Process {
    param($FilePath, $ArgumentList, $WorkingDirectory, [switch]$PassThru, $RedirectStandardOutput, $RedirectStandardError, $WindowStyle)
    $script:capturedArguments = $ArgumentList -join ' '
    throw 'captured-launch-boundary'
}
function Assert-Display($Condition, $Name) {
    if (-not $Condition) { throw ('FAIL: ' + $Name) }
    $script:passed++
}
$script:passed = 0
try {
    foreach ($profile in @('', 'local,meta-display')) {
        $SpringProfile = $profile
        try {
            Start-ChatUiListener -ServerPort 18180 -ManagementPort 18181 -NettyPort 18182 -RunId 'synthetic-fixed-port' -CacheDir $fixture -LogDir $fixture -TimeoutSeconds 1 | Out-Null
            throw 'unexpected-process-start'
        } catch { if ($_.Exception.Message -ne 'captured-launch-boundary') { throw } }
        Assert-Display ($script:capturedArguments.Contains('--server.port=18180')) 'fixed server command'
        Assert-Display ($script:capturedArguments.Contains('--management.server.port=18181')) 'isolated management command'
        Assert-Display ($script:capturedArguments.Contains('--netty.port=18182')) 'isolated Netty command'
        if ($profile) {
            Assert-Display ($script:capturedArguments.Contains('--spring.profiles.active=local,meta-display')) 'launcher profile reaches Spring bootRun'
        } else {
            Assert-Display (-not $script:capturedArguments.Contains('--spring.profiles.active')) 'legacy default profile untouched'
        }
    }
    . (Join-Path $PSScriptRoot 'start_rag_stack.ps1')
    $realCandidateFinder = ${function:Get-RagSpringCandidates}
    $realWebTest = ${function:Test-RagWeb}
    $script:stages = [Collections.Generic.List[string]]::new()
    function Write-RagStage { param($Stage,$Status,$Message) $script:stages.Add("$Stage $Status $Message") }
    function Get-RagSpringCandidates { return $script:candidates }
    function Get-RagPortOwner { param($Port) return $script:owners[$Port] }
    function Test-RagWeb { param($Port,[switch]$MetaDisplay) return $script:webReady }
    function Expect-DisplayFailure([scriptblock]$Action, [string]$Reason) {
        try { & $Action; throw 'unexpected-success' }
        catch { Assert-Display ($_.Exception.Message -eq $Reason) $Reason }
    }
    $script:webReady = $true
    $script:owners = @{}
    $script:candidates = @(@{processId=81;port=18176;metaDisplay=$false})
    $selected = Resolve-RagSpring -RequestedPort 0 -MetaDisplay
    Assert-Display ($selected.port -eq 18180 -and -not $selected.reuse) 'development runtime does not select Display port'
    $legacy = Resolve-RagSpring -RequestedPort 0
    Assert-Display ($legacy.port -eq 18176 -and $legacy.reuse) 'generic launcher retains development reuse'
    Expect-DisplayFailure { Resolve-RagSpring -RequestedPort 8080 -MetaDisplay } 'meta-display-fixed-port-required'
    $script:candidates += @{processId=82;port=18180;metaDisplay=$true}
    $script:owners = @{18180=@{processId=82;processName='java.exe'};18181=@{processId=82;processName='java.exe'};18182=@{processId=82;processName='java.exe'}}
    $selected = Resolve-RagSpring -RequestedPort 0 -MetaDisplay
    Assert-Display ($selected.port -eq 18180 -and $selected.reuse -and $selected.processId -eq 82) 'reuse dedicated profile beside development server'
    $script:webReady = $false
    Expect-DisplayFailure { Resolve-RagSpring -RequestedPort 0 -MetaDisplay } 'existing-spring-not-ready'
    $script:webReady = $true
    $script:candidates = @(@{processId=81;port=18176;metaDisplay=$false})
    foreach ($conflictPort in @(18180,18181,18182)) {
        $script:owners = @{}; $script:owners[$conflictPort] = @{processId=4242;processName='python.exe'}
        $script:stages.Clear()
        Expect-DisplayFailure { Resolve-RagSpring -RequestedPort 0 -MetaDisplay } 'meta-display-port-conflict'
        Assert-Display (($script:stages -join '\n') -match "port=$conflictPort pid=4242 process=python.exe") 'conflict identifies occupied port and process'
    }
    $script:candidates = @(@{processId=82;port=18180;metaDisplay=$false})
    $script:owners = @{18180=@{processId=82;processName='java.exe'}}
    Expect-DisplayFailure { Resolve-RagSpring -RequestedPort 0 -MetaDisplay } 'meta-display-port-conflict'
    $script:candidates = @(); $script:owners = @{}
    $previousAutostart = $env:LOCAL_LLM_AUTOSTART
    Expect-DisplayFailure { Start-RagSpring -Port 18180 -RunDirectory $fixture -TimeoutSeconds 1 -MetaDisplay } 'captured-launch-boundary'
    foreach ($required in @('-Port 18180','-ManagementPort 18181','-NettyPort 18182','-FixedPorts','-UiSurface meta-display','-SpringProfile local,meta-display','-BuildHostId desktop-meta-display')) {
        Assert-Display ($script:capturedArguments.Contains($required)) ('cold command forwards ' + $required)
    }
    Assert-Display ($env:LOCAL_LLM_AUTOSTART -eq $previousAutostart) 'cold failure restores caller environment'
    $previousPublicBase = $env:APP_PUBLIC_BASE_URL
    try {
        $env:APP_PUBLIC_BASE_URL = 'https://example.com/'
        Assert-Display ((Get-RagDisplayRegistrationUrl) -eq 'https://example.com/assets/display/index.html') 'configured public URL has stable Display path'
        foreach ($invalid in @('http://example.com','https://user:synthetic@example.com','https://example.com/?token=synthetic','https://example.com/#synthetic')) {
            $env:APP_PUBLIC_BASE_URL = $invalid
            Expect-DisplayFailure { Get-RagDisplayRegistrationUrl } 'meta-display-public-url-invalid'
        }
    } finally { $env:APP_PUBLIC_BASE_URL = $previousPublicBase }
    ${function:Get-RagSpringCandidates} = $realCandidateFinder
    function Get-CimInstance {
        [pscustomobject]@{ProcessId=123;CommandLine=('java -cp "' + $script:RagRoot + '\build\classes" com.example.lms.LmsApplication --server.port=18180 --spring.profiles.active=' + $script:syntheticProfiles)}
    }
    function Get-NetTCPConnection { [pscustomobject]@{LocalPort=18180} }
    $script:syntheticProfiles = 'local,meta-display'
    $actualCandidates = @(Get-RagSpringCandidates)
    Assert-Display ($actualCandidates.Count -eq 1 -and $actualCandidates[0].metaDisplay) 'exact profile identifies dedicated process'
    $script:syntheticProfiles = 'local,meta-display-other'
    $actualCandidates = @(Get-RagSpringCandidates)
    Assert-Display (-not $actualCandidates[0].metaDisplay) 'similar profile cannot impersonate dedicated process'
    ${function:Test-RagWeb} = $realWebTest
    function Get-RagHttp { param($Url,$Method,$Body)
        if ($Url.EndsWith('/chat-ui')) { return @{status=200;content='/assets/interview/app.js'} }
        if ($Url.EndsWith('/assets/display/index.html')) { return @{status=200;content=$script:displayPage} }
        return @{status=400;contentType='application/json'}
    }
    $script:displayPage = '<script src="display-core.js"></script><script src="lens.js"></script>'
    Assert-Display (Test-RagWeb -Port 18180 -MetaDisplay) 'dedicated readiness checks Display page and API'
    $script:displayPage = '<script src="display-core.js"></script><script src="lens.js?v=wear-20260915-2"></script>'
    Assert-Display (Test-RagWeb -Port 18180 -MetaDisplay) 'versioned Display asset is ready'
    $script:displayPage = '<body data-fold6-test><button id="microphone"></button><script src="display-core.js"></script><script src="app.js?v=fold6"></script>'
    Assert-Display (Test-RagWeb -Port 18180 -MetaDisplay) 'direct Fold6 entry is ready'
    $script:displayPage = '<script src="display-core.js"></script><script src="lens.js.bad"></script>'
    Assert-Display (-not (Test-RagWeb -Port 18180 -MetaDisplay)) 'different asset cannot satisfy readiness'
    $script:displayPage = '<html>unrelated login page</html>'
    Assert-Display (-not (Test-RagWeb -Port 18180 -MetaDisplay)) 'generic HTML is not Display readiness'
    # wear runtime role: listener arg, resolve/stop protection, launch isolation
    $SpringProfile = 'local,meta-display'
    try {
        Start-ChatUiListener -ServerPort 18180 -ManagementPort 18181 -NettyPort 18182 -RunId 'synthetic-wear' -RuntimeRole 'wear' -CacheDir $fixture -LogDir $fixture -TimeoutSeconds 1 | Out-Null
        throw 'unexpected-process-start'
    } catch { if ($_.Exception.Message -ne 'captured-launch-boundary') { throw } }
    Assert-Display ($script:capturedArguments.Contains('--awx.runtime.role=wear')) 'listener forwards wear runtime role to Spring'
    Assert-Display ($script:capturedArguments.Contains('--awx.runtime.run-id=synthetic-wear')) 'run id still forwarded beside role'
    try {
        Start-ChatUiListener -ServerPort 18180 -ManagementPort 18181 -NettyPort 18182 -RunId 'synthetic-dev' -RuntimeRole 'dev' -CacheDir $fixture -LogDir $fixture -TimeoutSeconds 1 | Out-Null
        throw 'unexpected-process-start'
    } catch { if ($_.Exception.Message -ne 'captured-launch-boundary') { throw } }
    Assert-Display ($script:capturedArguments.Contains('--awx.runtime.role=dev')) 'explicit dev role is recorded too'
    foreach ($fn in @('Resolve-RagSpring','Stop-RagSpringForRestart','Start-RagSpring','Invoke-RagServices')) {
        Assert-Display ((Get-Command $fn).Parameters.ContainsKey('Wear')) ($fn + ' accepts -Wear')
    }
    $script:roleByPid = @{91='wear';92='dev';93='dev'}
    function Get-CimInstance { param($ClassName,$Filter)
        $n = 0; if ($Filter -match 'ProcessId=(\d+)') { $n = [int]$Matches[1] }
        $r = if ($script:roleByPid.ContainsKey($n)) { $script:roleByPid[$n] } else { 'dev' }
        [pscustomobject]@{ProcessId=$n;CommandLine=('java com.example.lms.LmsApplication --spring.profiles.active=local,meta-display --awx.runtime.role=' + $r)}
    }
    function Get-AwxProcessIdentity { param($ProcessId) [pscustomobject]@{processId=[int]$ProcessId;parentProcessId=0;processName='java.exe';creationDate='';commandHash='x'} }
    $script:killed = @()
    function Stop-AwxStartedProcessTree { param($LauncherIdentity,$TimeoutSeconds) $script:killed += [int]$LauncherIdentity.processId; @{status='stopped'} }
    function Find-RagRuntimeManifest { param($ProcessId) $null }
    function Start-Sleep {}
    function Stop-Process { param($Id,[switch]$Force) $script:killed += [int]$Id }
    function Get-RagSpringCandidates { return $script:candidates }
    $script:displayPage = '<script src="display-core.js"></script><script src="lens.js"></script>'
    $script:candidates = @(@{processId=91;port=18180;metaDisplay=$true;role='wear'})
    $script:owners = @{18180=@{processId=91;processName='java.exe'};18181=@{processId=91;processName='java.exe'};18182=@{processId=91;processName='java.exe'}}
    Expect-DisplayFailure { Resolve-RagSpring -RequestedPort 0 -MetaDisplay } 'meta-display-wear-runtime-protected'
    $selected = Resolve-RagSpring -RequestedPort 0 -MetaDisplay -Wear
    Assert-Display ($selected.reuse -and $selected.processId -eq 91) 'wear launch reuses the wear runtime'
    $script:candidates = @(@{processId=93;port=18180;metaDisplay=$true;role='dev'})
    $script:owners = @{18180=@{processId=93;processName='java.exe'}}
    Expect-DisplayFailure { Resolve-RagSpring -RequestedPort 0 -MetaDisplay -Wear } 'meta-display-port-conflict'
    $script:candidates = @(@{processId=91;port=18180;metaDisplay=$true;role='wear'},@{processId=92;port=18176;metaDisplay=$false;role='dev'})
    $script:owners = @{}
    $script:killed = @()
    Stop-RagSpringForRestart
    Assert-Display (($script:killed -join ',') -eq '92') 'generic force-restart spares the wear pid'
    $script:candidates = @(@{processId=91;port=18180;metaDisplay=$true;role='wear'},@{processId=93;port=18180;metaDisplay=$true;role='dev'})
    $script:killed = @()
    Stop-RagSpringForRestart -MetaDisplay
    Assert-Display (($script:killed -join ',') -eq '93') 'dev-meta force-restart spares the wear pid'
    $script:killed = @()
    Stop-RagSpringForRestart -MetaDisplay -Wear
    Assert-Display (($script:killed -join ',') -eq '91') 'wear force-restart targets only the wear pid'
    Assert-Display ((Get-RagProcessRole -ProcessId 91 -CommandLine 'java app --awx.runtime.role=wear') -eq 'wear') 'command line proves wear role'
    function Find-RagRuntimeManifest { param($ProcessId) if ($ProcessId -eq 91) { [pscustomobject]@{runtimeRole='wear'} } else { $null } }
    Assert-Display ((Get-RagProcessRole -ProcessId 91 -CommandLine 'java app') -eq 'wear') 'ownership manifest corroborates wear role'
    Assert-Display ((Get-RagProcessRole -ProcessId 92 -CommandLine 'java app') -eq 'dev') 'absent role evidence stays dev'
    $script:candidates = @(); $script:owners = @{}
    Expect-DisplayFailure { Start-RagSpring -Port 18180 -RunDirectory $fixture -TimeoutSeconds 1 -MetaDisplay -Wear } 'captured-launch-boundary'
    Assert-Display ($script:capturedArguments.Contains('-RuntimeRole wear')) 'wear launch forwards runtime role to listener'
    Assert-Display ($script:capturedArguments.Contains('-BuildHostId desktop-meta-display-wear')) 'wear build host is isolated from dev-meta'
    Write-Output "PASS: $script:passed Display launcher checks"
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolvedFixture.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolvedFixture -Leaf).StartsWith('awx-display-launch-')) {
        Remove-Item -LiteralPath $resolvedFixture -Recurse -Force -ErrorAction SilentlyContinue
    }
}
