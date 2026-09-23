#requires -Version 5.1
[CmdletBinding()]
param(
    [ValidateRange(0,65535)][int]$Port = 0,
    [ValidateRange(0,65535)][int]$OllamaPort = 0,
    [ValidateRange(10,1800)][int]$TimeoutSeconds = 600,
    [switch]$MetaDisplay,
    [switch]$Wear,
    [switch]$CheckOnly,
    [switch]$OpenBrowser,
    [switch]$ForceRestart,
    [switch]$DevWatch
)

$ErrorActionPreference = 'Stop'
$script:RagRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:RagListenerScript = Join-Path $PSScriptRoot 'chat_ui_vibe_listener.ps1'
. (Join-Path $PSScriptRoot 'chat_ui_vibe_lifecycle.ps1')
$script:RagStage = 'PREFLIGHT'
$script:RagLog = ''

function Write-RagStage {
    param([string]$Stage, [string]$Status, [string]$Message)
    $script:RagStage = $Stage
    $line = '{0} [{1}] {2} {3}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Stage, $Status, $Message
    Write-Host $line
    if ($script:RagLog) { Add-Content -LiteralPath $script:RagLog -Value $line -Encoding UTF8 }
}

function Get-RagHttp {
    param([string]$Url, [string]$Method = 'GET', [string]$Body = '', [hashtable]$Headers = @{})
    # Loopback health traffic must not be redirected to a login page or system proxy.
    $request = [Net.HttpWebRequest]::Create($Url)
    $request.Proxy = $null
    $request.AllowAutoRedirect = $false
    $request.Timeout = 3000
    $request.ReadWriteTimeout = 3000
    $request.Method = $Method
    foreach ($name in $Headers.Keys) { $request.Headers[$name] = [string]$Headers[$name] }
    $response = $null
    try {
        if ($Method -eq 'POST') {
            $request.ContentType = 'application/json'
            $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
            $request.ContentLength = $bytes.Length
            $stream = $request.GetRequestStream()
            try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
        }
        try { $response = $request.GetResponse() } catch [Net.WebException] { $response = $_.Exception.Response }
        if ($null -eq $response) { return @{status=0;content='';contentType=''} }
        $reader = [IO.StreamReader]::new($response.GetResponseStream())
        try {
            $buffer = New-Object char[] 524288
            $length = $reader.ReadBlock($buffer, 0, $buffer.Length)
            return @{status=[int]$response.StatusCode;content=[string]::new($buffer,0,$length);contentType=[string]$response.ContentType}
        } finally { $reader.Dispose() }
    } catch { return @{status=0;content='';contentType=''} }
    finally { if ($null -ne $response) { $response.Dispose() }; $request.Abort() }
}

function Get-RagPortOwner {
    param([int]$Port)
    $rows = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    $owners = @($rows | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($owners.Count -eq 0) { return $null }
    if ($owners.Count -ne 1) { throw 'multiple-port-owners' }
    $identity = Get-AwxProcessIdentity -ProcessId ([int]$owners[0])
    if ($null -eq $identity) { throw 'port-owner-identity-unavailable' }
    return $identity
}

function Test-RagOllama {
    param([int]$Port)
    $owner = Get-RagPortOwner -Port $Port
    if ($null -eq $owner -or $owner.processName -notmatch '^(?i:ollama)(?:\.exe)?$') { return $false }
    $version = Get-RagHttp "http://127.0.0.1:$Port/api/version"
    $tags = Get-RagHttp "http://127.0.0.1:$Port/api/tags"
    try {
        $v = $version.content | ConvertFrom-Json
        $t = $tags.content | ConvertFrom-Json
        return ($version.status -eq 200 -and $tags.status -eq 200 -and
            [string](Get-AwxObjectProperty $v 'version') -match '^\d+\.' -and
            $null -ne $t.PSObject.Properties['models'])
    } catch { return $false }
}

function Test-RagWeb {
    param([int]$Port, [switch]$MetaDisplay)
    $page = Get-RagHttp "http://127.0.0.1:$Port/chat-ui"
    if ($page.status -ne 200 -or $page.content -notmatch '/js/chat\.js|/assets/interview/app\.js') { return $false }
    if ($MetaDisplay) {
        $display = Get-RagHttp "http://127.0.0.1:$Port/assets/display/index.html"
        $lensPage = $display.content -match 'src="lens\.js(?:\?[^"<>\s]+)?"'
        $phonePage = $display.content -match 'data-fold6-test' -and $display.content -match 'id="microphone"' -and $display.content -match 'src="app\.js(?:\?[^"<>\s]+)?"'
        if ($display.status -ne 200 -or $display.content -notmatch 'display-core\.js' -or -not ($lensPage -or $phonePage)) { return $false }
        # Invalid client identity is rejected before session allocation or generation.
        $validation = Get-RagHttp "http://127.0.0.1:$Port/api/assist/display/bootstrap" 'POST' '{"clientId":""}' @{
            Origin = "http://127.0.0.1:$Port"; 'X-Display-Client' = '1'
        }
        return ($validation.status -eq 400 -and $validation.contentType -match 'application/json')
    }
    # The established sync controller rejects an empty message before retrieval/generation.
    # GET can return 404 for this POST-only route, so it is not a readiness test.
    $validation = Get-RagHttp "http://127.0.0.1:$Port/api/chat/sync" 'POST' '{"message":""}'
    return ($validation.status -eq 400 -and $validation.contentType -match 'application/json')
}

function Get-RagSpringCandidates {
    $prefix = $script:RagRoot.TrimEnd('\').Replace('/','\') + '\'
    foreach ($process in @(Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'")) {
        $command = [string]$process.CommandLine
        $normalized = $command.Replace('/','\')
        $fromRoot = $normalized.IndexOf($prefix, [StringComparison]::OrdinalIgnoreCase) -ge 0
        $mainClass = $command -match '(?:^|\s)com\.example\.lms\.LmsApplication(?:\s|$)'
        $jar = [regex]::Match($command, '(?:^|\s)-jar\s+(?:"([^"]+)"|(\S+))')
        $rootJar = $false
        if ($jar.Success) {
            $jarPath = if ($jar.Groups[1].Success) { $jar.Groups[1].Value } else { $jar.Groups[2].Value }
            if ([IO.Path]::IsPathRooted($jarPath)) {
                $rootJar = [IO.Path]::GetFullPath($jarPath).StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
            }
        }
        if (-not (($fromRoot -and $mainClass) -or $rootJar)) { continue }
        $ports = @(Get-NetTCPConnection -State Listen -OwningProcess $process.ProcessId -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty LocalPort -Unique)
        $portMatch = [regex]::Match($command, '(?:--|-D)server\.port=(\d+)')
        $serverPort = if ($portMatch.Success) { [int]$portMatch.Groups[1].Value } else { 0 }
        if (-not $serverPort) {
            foreach ($candidatePort in $ports) {
                if (Test-RagWeb -Port $candidatePort) { $serverPort = $candidatePort; break }
            }
        }
        # A starting/unhealthy canonical process still prevents a duplicate launch.
        $profile = [regex]::Match($command, '(?:--|-D)spring\.profiles\.active=([^\s"]+)')
        $isDisplay = $profile.Success -and (@($profile.Groups[1].Value.Split(',')) -contains 'meta-display')
        $roleMatch = [regex]::Match($command, '--awx\.runtime\.role=([^\s"]+)')
        $role = if ($roleMatch.Success) { $roleMatch.Groups[1].Value.ToLowerInvariant() } else { 'dev' }
        [pscustomobject]@{processId=[int]$process.ProcessId;port=$serverPort;metaDisplay=$isDisplay;role=$role}
    }
}

function Find-RagRuntimeManifest {
    param([int]$ProcessId)
    $base = Join-Path $script:RagRoot 'var\rag-launcher'
    if (-not (Test-Path -LiteralPath $base -PathType Container)) { return $null }
    $files = @(Get-ChildItem -LiteralPath $base -Filter 'spring-owned.json' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    foreach ($file in $files) {
        $manifest = Read-AwxRuntimeManifest -Path $file.FullName
        if ($null -eq $manifest) { continue }
        $listenerPid = [int](Get-AwxObjectProperty (Get-AwxObjectProperty $manifest 'listener') 'processId')
        $launcherPid = [int](Get-AwxObjectProperty (Get-AwxObjectProperty $manifest 'launcher') 'processId')
        if ($listenerPid -eq $ProcessId -or $launcherPid -eq $ProcessId) { return $manifest }
    }
    return $null
}

function Get-RagProcessRole {
    # Runtime role is proven by the live command line first; the ownership manifest
    # corroborates it when the argument is absent (fail-closed toward 'wear' protection).
    param([int]$ProcessId, [string]$CommandLine = '')
    if ($CommandLine -match '--awx\.runtime\.role=([^\s"]+)') { return $Matches[1].ToLowerInvariant() }
    $manifest = Find-RagRuntimeManifest -ProcessId $ProcessId
    $manifestRole = [string](Get-AwxObjectProperty $manifest 'runtimeRole')
    if (-not [string]::IsNullOrWhiteSpace($manifestRole)) { return $manifestRole.ToLowerInvariant() }
    return 'dev'
}

function Resolve-RagSpring {
    param([int]$RequestedPort, [switch]$MetaDisplay, [switch]$Wear)
    if ($MetaDisplay -and $RequestedPort -notin @(0,18180)) { throw 'meta-display-fixed-port-required' }
    $candidates = @(Get-RagSpringCandidates)
    if ($MetaDisplay) {
        $dedicated = @($candidates | Where-Object { [bool](Get-AwxObjectProperty $_ 'metaDisplay') })
        # The wear runtime is a distinct identity: a dev-meta launch must never adopt it,
        # and a wear launch must never adopt a dev-meta runtime on the same fixed ports.
        $wearCands = @($dedicated | Where-Object { (Get-AwxObjectProperty $_ 'role') -eq 'wear' })
        $peers = @($dedicated | Where-Object { ((Get-AwxObjectProperty $_ 'role') -eq 'wear') -eq [bool]$Wear })
        if ($Wear -and $wearCands.Count -gt 1) { throw 'multiple-wear-runtimes' }
        if (-not $Wear -and $peers.Count -gt 1) { throw 'multiple-meta-display-runtimes' }
        $selfPid = if ($peers.Count -eq 1) { [int]$peers[0].processId } else { 0 }
        # Check all fixed ports before starting dependencies, and again immediately before launch.
        foreach ($fixedPort in @(18180,18181,18182)) {
            $owner = Get-RagPortOwner -Port $fixedPort
            if ($null -ne $owner -and ($selfPid -eq 0 -or $owner.processId -ne $selfPid)) {
                Write-RagStage 'PREFLIGHT' 'CONFLICT' "port=$fixedPort pid=$($owner.processId) process=$($owner.processName)"
                $ownerIsWear = @($wearCands | Where-Object { [int]$_.processId -eq [int]$owner.processId }).Count -gt 0
                if ($ownerIsWear -and -not $Wear) { throw 'meta-display-wear-runtime-protected' }
                throw 'meta-display-port-conflict'
            }
        }
        if ($peers.Count -eq 1) {
            if ($peers[0].port -ne 18180) { throw 'existing-spring-port-mismatch' }
            if (-not (Test-RagWeb -Port 18180 -MetaDisplay)) { throw 'existing-spring-not-ready' }
            return @{port=18180;processId=$selfPid;reuse=$true}
        }
        return @{port=18180;processId=0;reuse=$false}
    }
    $candidates = @($candidates | Where-Object { -not [bool](Get-AwxObjectProperty $_ 'metaDisplay') })
    if ($candidates.Count -gt 0) {
        $eligible = @($candidates | Where-Object { $RequestedPort -eq 0 -or $_.port -eq $RequestedPort } | Sort-Object { [int]$_.port })
        if ($eligible.Count -eq 0) { throw 'existing-spring-port-mismatch' }
        foreach ($candidate in $eligible) {
            if ($candidate.port -gt 0 -and (Test-RagWeb -Port $candidate.port)) {
                return @{port=[int]$candidate.port;processId=[int]$candidate.processId;reuse=$true}
            }
        }
        throw 'existing-spring-not-ready'
    }
    $selected = if ($RequestedPort -gt 0) { $RequestedPort } else { 8080 }
    if ($null -ne (Get-RagPortOwner -Port $selected)) { throw 'spring-port-conflict' }
    return @{port=$selected;processId=0;reuse=$false}
}


function Stop-RagSpringForRestart {
    param([switch]$MetaDisplay, [switch]$Wear)
    $candidates = @(Get-RagSpringCandidates)
    if ($MetaDisplay) {
        $candidates = @($candidates | Where-Object {
            [bool](Get-AwxObjectProperty $_ 'metaDisplay') -and (((Get-AwxObjectProperty $_ 'role') -eq 'wear') -eq [bool]$Wear)
        })
    } else {
        # A generic development restart never targets the wear runtime.
        $candidates = @($candidates | Where-Object { (Get-AwxObjectProperty $_ 'role') -ne 'wear' })
    }
    foreach ($candidate in $candidates) {
        $procId = [int](Get-AwxObjectProperty $candidate 'processId')
        if ($procId -le 0) { continue }
        $identity = Get-AwxProcessIdentity -ProcessId $procId
        $command = ''
        if ($null -ne $identity) {
            $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$procId" -ErrorAction SilentlyContinue
            if ($null -ne $proc) { $command = [string]$proc.CommandLine }
        }
        $role = Get-RagProcessRole -ProcessId $procId -CommandLine $command
        if (($role -eq 'wear') -ne [bool]$Wear) {
            Write-RagStage 'SPRING' 'SKIP' "pid=$procId role=$role protected-by-runtime-role"
            continue
        }
        if ($null -eq $identity) {
            Write-RagStage 'SPRING' 'STOP' "pid=$procId (no-identity)"
            Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
            continue
        }
        Write-RagStage 'SPRING' 'STOP' "pid=$procId process=$($identity.processName) role=$role force-restart"
        Stop-AwxStartedProcessTree -LauncherIdentity $identity -TimeoutSeconds 60 | Out-Null
    }
    if ($MetaDisplay) {
        foreach ($fixedPort in @(18180,18181,18182)) {
            $owner = Get-RagPortOwner -Port $fixedPort
            if ($null -eq $owner) { continue }
            $ownerPid = [int]$owner.processId
            $ownerRole = Get-RagProcessRole -ProcessId $ownerPid
            if ($ownerRole -eq 'wear' -and -not $Wear) {
                Write-RagStage 'SPRING' 'SKIP' "port=$fixedPort leftover pid=$ownerPid role=$ownerRole protected-by-runtime-role"
                continue
            }
            Write-RagStage 'SPRING' 'STOP' "port=$fixedPort leftover pid=$ownerPid"
            $identity = Get-AwxProcessIdentity -ProcessId $ownerPid
            if ($null -ne $identity) {
                Stop-AwxStartedProcessTree -LauncherIdentity $identity -TimeoutSeconds 30 | Out-Null
            } else {
                Stop-Process -Id $ownerPid -Force -ErrorAction SilentlyContinue
            }
        }
    }
    Start-Sleep -Seconds 2
}

function Start-RagOllama {
    param([int]$Port, [string]$RunDirectory, [int]$TimeoutSeconds)
    if (Test-RagOllama -Port $Port) { return }
    if ($null -ne (Get-RagPortOwner -Port $Port)) { throw 'ollama-port-conflict' }
    $executable = Get-Command ollama.exe -ErrorAction SilentlyContinue
    $file = if ($executable) { $executable.Source } else { Join-Path $env:LOCALAPPDATA 'Programs\Ollama\ollama.exe' }
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw 'ollama-executable-missing' }
    $oldHost = $env:OLLAMA_HOST
    try {
        $env:OLLAMA_HOST = "127.0.0.1:$Port"
        $child = Start-Process -FilePath $file -ArgumentList 'serve' -WorkingDirectory $script:RagRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $RunDirectory 'ollama.out.log') -RedirectStandardError (Join-Path $RunDirectory 'ollama.err.log')
    } finally { $env:OLLAMA_HOST = $oldHost }
    $identity = Get-AwxProcessIdentity -ProcessId $child.Id
    $deadline = (Get-Date).AddSeconds([Math]::Min($TimeoutSeconds,60))
    try {
        do {
            $child.Refresh()
            if ($child.HasExited) { throw 'ollama-child-exited' }
            if (Test-RagOllama -Port $Port) {
                $owner = Get-RagPortOwner -Port $Port
                if ($owner.processId -ne $child.Id) { throw 'ollama-owner-changed' }
                Write-RagStage 'OLLAMA' 'STARTED' "port=$Port pid=$($child.Id)"
                return
            }
            Start-Sleep -Milliseconds 500
        } while ((Get-Date) -lt $deadline)
        throw 'ollama-readiness-timeout'
    } catch {
        if ($null -ne $identity) { Stop-AwxStartedProcessTree -LauncherIdentity $identity -TimeoutSeconds 5 | Out-Null }
        throw
    }
}

function Start-RagSpring {
    param([int]$Port, [string]$RunDirectory, [int]$TimeoutSeconds, [switch]$MetaDisplay, [switch]$Wear)
    # Recheck immediately before delegating. Never feed another task's state into the restart helper.
    $decision = Resolve-RagSpring -RequestedPort $Port -MetaDisplay:$MetaDisplay -Wear:$Wear
    if ($decision.reuse) { return $decision }
    $shell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $helper = $script:RagListenerScript
    $state = Join-Path $RunDirectory 'spring-owned.json'
    $cache = Join-Path $env:LOCALAPPDATA 'AWX\rag-launcher\project-cache'
    $gradleHome = Join-Path $env:USERPROFILE '.gradle-awx-rag-launcher'
    $buildHost = 'desktop-rag-launcher'
    if ($MetaDisplay) {
        $cache = Join-Path $env:LOCALAPPDATA 'AWX\meta-display\project-cache'
        $buildHost = 'desktop-meta-display'
    }
    if ($Wear) {
        # The wear runtime keeps its own split output so a dev-meta compile can never
        # rewrite classpath or static assets underneath the running glasses session.
        $cache = Join-Path $env:LOCALAPPDATA 'AWX\meta-display-wear\project-cache'
        $buildHost = 'desktop-meta-display-wear'
    }
    $role = if ($Wear) { 'wear' } else { 'dev' }
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -Port {1} -FixedPorts -StatePath "{2}" -BuildHostId {7} -ProjectCacheDir "{3}" -GradleUserHome "{4}" -OutDir "{5}" -ReadyTimeoutSeconds {6} -RuntimeRole {8}' -f $helper,$Port,$state,$cache,$gradleHome,$RunDirectory,$TimeoutSeconds,$buildHost,$role
    if ($MetaDisplay) { $arguments += ' -ManagementPort 18181 -NettyPort 18182 -UiSurface meta-display -SpringProfile local,meta-display' }
    # This launcher has already made Ollama healthy. The Java manager's automatic
    # GPU discovery can otherwise select another managed port and launch a second
    # Ollama. Delegate process startup to exactly one owner for this child only.
    $priorAutostart = $env:LOCAL_LLM_AUTOSTART
    try {
        $env:LOCAL_LLM_AUTOSTART = 'false'
        $child = Start-Process -FilePath $shell -ArgumentList $arguments -WorkingDirectory $script:RagRoot -WindowStyle Hidden -PassThru `
            -RedirectStandardOutput (Join-Path $RunDirectory 'spring-helper.out.log') -RedirectStandardError (Join-Path $RunDirectory 'spring-helper.err.log')
    } finally { $env:LOCAL_LLM_AUTOSTART = $priorAutostart }
    # Retain the native handle before a short-lived helper can disappear (PS 5.1).
    $null = $child.Handle
    $identity = Get-AwxProcessIdentity -ProcessId $child.Id
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $nextProgress = Get-Date
    try {
        while (-not $child.HasExited) {
            if ((Get-Date) -ge $deadline) { throw 'spring-start-timeout' }
            if ((Get-Date) -ge $nextProgress) {
                $phase = if (Test-Path (Join-Path $RunDirectory "chat-ui-vibe-listener-$Port.out.log")) { 'boot-and-readiness' } else { 'gradle-verification' }
                Write-RagStage 'SPRING' 'WAIT' "phase=$phase port=$Port logs=$RunDirectory"
                $nextProgress = (Get-Date).AddSeconds(10)
            }
            Start-Sleep -Milliseconds 500
            $child.Refresh()
        }
        $child.WaitForExit()
        $child.Refresh()
        if ($child.ExitCode -ne 0) {
            $resultPath = Join-Path $RunDirectory "chat-ui-vibe-listener-$Port.result.json"
            if (Test-Path -LiteralPath $resultPath) {
                $result = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
                $status = [string](Get-AwxObjectProperty $result 'status')
                if ($status -match '^[a-z-]{1,80}$') { Write-RagStage 'SPRING' 'FAILED' "reason=$status exit=$($child.ExitCode)" }
            }
            throw 'spring-start-failed'
        }
        $manifest = Read-AwxRuntimeManifest -Path $state
        $proof = Test-AwxOwnedRuntimeIdentity -Manifest $manifest -Root $script:RagRoot
        if (-not $proof.ok) { throw 'spring-identity-not-proven' }
        return @{port=$Port;processId=[int]$proof.listenerPid;reuse=$false}
    } catch {
        # On timeout this is our helper and descendants only; prior services are untouched.
        if (-not $child.HasExited -and $null -ne $identity) { Stop-AwxStartedProcessTree -LauncherIdentity $identity -TimeoutSeconds 10 | Out-Null }
        throw
    }
}

function Invoke-RagServices {
    param([int]$RequestedPort, [int]$OllamaPort, [string]$RunDirectory, [int]$TimeoutSeconds, [switch]$CheckOnly, [switch]$MetaDisplay, [switch]$ForceRestart, [switch]$Wear)
    Write-RagStage 'PREFLIGHT' 'CHECK' 'Checking existing Spring identity and port ownership.'
    if ($ForceRestart -and -not $CheckOnly) {
        Write-RagStage 'SPRING' 'RESTART' 'ForceRestart requested: stopping existing Spring before relaunch.'
        Stop-RagSpringForRestart -MetaDisplay:$MetaDisplay -Wear:$Wear
    }
    $spring = Resolve-RagSpring -RequestedPort $RequestedPort -MetaDisplay:$MetaDisplay -Wear:$Wear
    if ($spring.port -eq $OllamaPort -or ($MetaDisplay -and $OllamaPort -in @(18181,18182))) { throw 'service-ports-overlap' }
    Write-RagStage 'OLLAMA' 'CHECK' "http://127.0.0.1:$OllamaPort"
    if (Test-RagOllama -Port $OllamaPort) {
        Write-RagStage 'OLLAMA' 'REUSED' "port=$OllamaPort"
    } else {
        if ($CheckOnly) { throw 'ollama-not-ready' }
        Start-RagOllama -Port $OllamaPort -RunDirectory $RunDirectory -TimeoutSeconds $TimeoutSeconds
    }
    Write-RagStage 'SPRING' 'CHECK' "port=$($spring.port)"
    if ($spring.reuse) {
        Write-RagStage 'SPRING' 'REUSED' "port=$($spring.port) pid=$($spring.processId)"
    } else {
        if ($CheckOnly) { throw 'spring-not-running' }
        $spring = Start-RagSpring -Port $spring.port -RunDirectory $RunDirectory -TimeoutSeconds $TimeoutSeconds -MetaDisplay:$MetaDisplay -Wear:$Wear
    }
    Write-RagStage 'RAG' 'CHECK' 'Checking UI and input-validation API; no model-generation request.'
    if (-not (Test-RagWeb -Port $spring.port -MetaDisplay:$MetaDisplay)) { throw 'rag-web-not-ready' }
    if (-not (Test-RagOllama -Port $OllamaPort)) { throw 'ollama-lost-before-ready' }
    return $spring
}

function Get-RagDisplayRegistrationUrl {
    # Reuse the existing public-base setting; printing it does not verify the external gateway.
    $base = $env:APP_PUBLIC_BASE_URL
    if ([string]::IsNullOrWhiteSpace($base)) {
        $config = Get-Content -LiteralPath (Join-Path $script:RagRoot 'main/resources/application.yml') -Raw -Encoding UTF8
        $match = [regex]::Match($config, '(?m)^\s+public-base-url:\s*\$\{APP_PUBLIC_BASE_URL:(https://[^}\s]+)\}\s*$')
        if (-not $match.Success) { return '' }
        $base = $match.Groups[1].Value
    }
    $uri = $null
    if (-not [uri]::TryCreate($base, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -ne 'https' -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment -or [uri]::CheckHostName($uri.Host) -eq [UriHostNameType]::Unknown) {
        throw 'meta-display-public-url-invalid'
    }
    return $uri.AbsoluteUri.TrimEnd('/') + '/assets/display/index.html'
}

function Test-RagHttpsOptIn {
    $value = [Environment]::GetEnvironmentVariable('AWX_OPTIONAL_HTTPS_ENABLED', 'User')
    if ($null -eq $value) { $value = $env:AWX_OPTIONAL_HTTPS_ENABLED }
    return $null -ne $value -and $value.Trim().ToLowerInvariant() -in @('true','1','yes','y','on')
}

function Invoke-RagHttpsStart {
    param([uri]$PublicUrl, [int]$BackendPort)
    $priorModulePath = $env:PSModulePath
    try {
        $env:PSModulePath = ''
        & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File (Join-Path $script:RagRoot 'scripts/domain_optional_https.ps1') -Mode start -EnableHttps -Domain $PublicUrl.Host -HttpsPort $PublicUrl.Port -BackendAddress "127.0.0.1:$BackendPort"
        if ($LASTEXITCODE -ne 0) { throw 'optional-https-start-failed' }
    } finally { $env:PSModulePath = $priorModulePath }
}

function Ensure-RagDisplayHttps {
    param([string]$RegistrationUrl, [int]$BackendPort, [switch]$CheckOnly)
    if (-not (Test-RagHttpsOptIn)) { return 'not_enabled' }
    $public = $null
    if (-not [uri]::TryCreate($RegistrationUrl, [UriKind]::Absolute, [ref]$public) -or
        $public.Scheme -ne 'https' -or $public.UserInfo) { throw 'meta-display-public-url-invalid' }
    if (-not $CheckOnly) { Invoke-RagHttpsStart -PublicUrl $public -BackendPort $BackendPort | Out-Host }
    $local = Get-RagHttp "http://127.0.0.1:$BackendPort/assets/display/index.html"
    $remote = Get-RagHttp $RegistrationUrl
    if ($local.status -ne 200 -or $remote.status -ne 200 -or $remote.content -cne $local.content) {
        throw 'meta-display-public-https-not-ready'
    }
    return 'tls-and-current-page-verified'
}

function Get-RagDisplayBrowserUrl {
    param([string]$Base)
    # A desktop launcher must not claim the microphone from a connected Fold6.
    $view = Get-RagHttp "$Base/api/assist/display/relay/poll" 'POST' '{"clientId":"01a0accbf1f37a60b80e6d3d1ca00001"}' @{Origin=$Base;'X-Display-Client'='1'}
    if ($view.status -eq 200) {
        try {
            if (($view.content | ConvertFrom-Json).producerConnected -eq $false) { return "$Base/assets/display/index.html" }
        } catch { }
    }
    return "$Base/assets/display/meta/index.html"
}

function Read-RagMetaAsrDefaults {
    param([string]$Path = (Join-Path $script:RagRoot 'var\meta-display.local.properties'))
    $values = @{}
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        Write-RagStage 'ASR' 'INFO' 'Local ASR config absent; inherited settings and text input remain available.'
        return $values
    }
    try {
        if ((Get-Item -LiteralPath $Path).Length -gt 32768) { throw 'asr-config-size' }
        $allowed = @('CONVERSATE_ENABLED','CONVERSATE_ASR_ENABLED','CONVERSATE_ASR_PROVIDER',
            'CONVERSATE_ASR_PYTHON','CONVERSATE_ASR_SCRIPT','CONVERSATE_ASR_MODEL',
            'CONVERSATE_ASR_CPU_THREADS','CONVERSATE_ASR_DEVICE','CONVERSATE_ASR_GPU_UUID',
            'CONVERSATE_ASR_CPU_MODEL','CONVERSATE_ASR_NATIVE_LIB','CONVERSATE_ASR_CLOUD_ENABLED')
        foreach ($line in [IO.File]::ReadAllLines($Path)) {
            if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
            if ($line -notmatch '^\s*([A-Za-z][A-Za-z0-9_.-]*)\s*=(.*)$') { throw 'asr-config-line' }
            $name = $Matches[1].ToUpperInvariant() -replace '[.-]','_'
            $value = $Matches[2].Trim()
            if ($name -notin $allowed -or $values.ContainsKey($name) -or -not $value -or $value.Length -gt 4096) { throw 'asr-config-field' }
            $values[$name] = $value
        }
        foreach ($name in @('CONVERSATE_ENABLED','CONVERSATE_ASR_ENABLED','CONVERSATE_ASR_CLOUD_ENABLED')) {
            if ($values.ContainsKey($name) -and $values[$name] -notin @('true','false')) { throw 'asr-config-boolean' }
        }
        if ($values.ContainsKey('CONVERSATE_ASR_PROVIDER') -and $values.CONVERSATE_ASR_PROVIDER -ne 'local') { throw 'asr-config-provider' }
        if ($values.ContainsKey('CONVERSATE_ASR_DEVICE') -and $values.CONVERSATE_ASR_DEVICE -notin @('cpu','cuda')) { throw 'asr-config-device' }
        if ($values.ContainsKey('CONVERSATE_ASR_CPU_THREADS') -and $values.CONVERSATE_ASR_CPU_THREADS -notmatch '^[1-8]$') { throw 'asr-config-threads' }
        if ($values.CONVERSATE_ASR_ENABLED -eq 'true') {
            foreach ($name in @('CONVERSATE_ASR_PYTHON','CONVERSATE_ASR_SCRIPT','CONVERSATE_ASR_MODEL')) {
                $kind = if ($name -eq 'CONVERSATE_ASR_MODEL') { 'Container' } else { 'Leaf' }
                if (-not $values.ContainsKey($name) -or -not [IO.Path]::IsPathRooted($values[$name]) -or
                    -not (Test-Path -LiteralPath $values[$name] -PathType $kind)) { throw 'asr-config-path' }
            }
        }
        Write-RagStage 'ASR' 'CONFIG' ('Local defaults loaded: fields={0}. Effective capture readiness is checked on microphone start.' -f $values.Count)
        return $values
    } catch {
        Write-RagStage 'ASR' 'WARN' 'Local ASR config rejected; no file values applied. Inherited settings and text input remain available.'
        return @{}
    }
}


function Start-RagDevWatch {
    param([switch]$MetaDisplay, [int]$Port = 18180)
    $watch = Join-Path $PSScriptRoot 'dev_reload_watch.ps1'
    if (-not (Test-Path -LiteralPath $watch)) {
        Write-RagStage 'DEV-RELOAD' 'SKIP' 'dev_reload_watch.ps1 missing'
        return
    }
    if ($Port -lt 1) { $Port = 18180 }
    $logDir = Join-Path $script:RagRoot 'var\dev-reload'
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    $out = Join-Path $logDir 'watcher.out.log'
    $err = Join-Path $logDir 'watcher.err.log'
    $argList = New-Object System.Collections.Generic.List[string]
    [void]$argList.Add('-NoLogo')
    [void]$argList.Add('-NoProfile')
    [void]$argList.Add('-ExecutionPolicy')
    [void]$argList.Add('Bypass')
    [void]$argList.Add('-File')
    [void]$argList.Add($watch)
    [void]$argList.Add('-Port')
    [void]$argList.Add([string]$Port)
    if ($MetaDisplay) { [void]$argList.Add('-MetaDisplay') }
    $child = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') `
        -ArgumentList $argList.ToArray() -WorkingDirectory $script:RagRoot -WindowStyle Minimized -PassThru `
        -RedirectStandardOutput $out -RedirectStandardError $err
    Write-RagStage 'DEV-RELOAD' 'STARTED' "pid=$($child.Id) port=$Port meta=$([bool]$MetaDisplay) log=$logDir\dev-reload.log"
}

function Invoke-RagLauncher {
    $runDirectory = Join-Path $script:RagRoot ('var\rag-launcher\' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    $script:RagLog = Join-Path $runDirectory 'launcher.log'
    $mutex = [Threading.Mutex]::new($false, ('Local\AWX-RAG-' + (Get-AwxCanonicalRootHash -Root $script:RagRoot).Substring(0,20)))
    $acquired = $false
    $saved = @{}
    try {
        try { $acquired = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $acquired = $true }
        if (-not $acquired) { throw 'launcher-already-running' }
        Write-RagStage 'PREFLIGHT' 'START' "Logs: $runDirectory"
        $java = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($null -eq $java) { throw 'java-executable-missing' }
        # Java writes a successful version query to stderr. Windows PowerShell 5.1
        # otherwise treats that native stderr as a terminating NativeCommandError.
        $previousAction = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $javaVersion = (& $java.Source -version 2>&1 | Out-String)
            $javaExit = $LASTEXITCODE
        } finally { $ErrorActionPreference = $previousAction }
        if ($javaExit -ne 0 -or $javaVersion -notmatch 'version "17\.') { throw 'java-17-required' }
        if ($Wear -and -not $MetaDisplay) { $MetaDisplay = $true }
        if ($Wear -and $DevWatch) { throw 'wear-devwatch-conflict' }
        $chosenPort = $Port
        $displayRegistrationUrl = ''
        if ($MetaDisplay) {
            if ($chosenPort -notin @(0,18180)) { throw 'meta-display-fixed-port-required' }
            $chosenPort = 18180
            $displayRegistrationUrl = Get-RagDisplayRegistrationUrl
            $roleLabel = if ($Wear) { 'wear' } else { 'dev' }
            Write-RagStage 'PREFLIGHT' 'FIXED' "Meta Display profile=local,meta-display server=18180 management=18181 netty=18182 role=$roleLabel"
        } elseif ($chosenPort -eq 0 -and $env:SERVER_PORT) {
            if ($env:SERVER_PORT -notmatch '^\d+$') { throw 'invalid-server-port' }
            $chosenPort = [int]$env:SERVER_PORT
        }
        $chosenOllamaPort = $OllamaPort
        if ($env:OLLAMA_HOST) {
            $hostText = $env:OLLAMA_HOST -replace '^https?://',''
            if ($hostText -notmatch '^(?:127\.0\.0\.1|localhost):([0-9]{1,5})/?$') { throw 'ollama-loopback-host-required' }
            $configuredPort = [int]$Matches[1]
            if ($chosenOllamaPort -gt 0 -and $chosenOllamaPort -ne $configuredPort) { throw 'ollama-port-config-mismatch' }
            $chosenOllamaPort = $configuredPort
        }
        # Meta's primary model uses the existing 3090 lane, leaving 3060 capacity for STT.
        if (-not $chosenOllamaPort) { $chosenOllamaPort = if ($MetaDisplay) { 11434 } else { 11435 } }
        if ($chosenOllamaPort -lt 1 -or $chosenOllamaPort -gt 65535 -or $chosenPort -lt 0 -or $chosenPort -gt 65535) { throw 'invalid-service-port' }
        # Inherit model, GPU, secret, DB and provider settings. Set process-local startup defaults only.
        $defaults = @{LOCAL_LLM_ENABLED='true';LOCAL_LLM_AUTOSTART='true';OLLAMA_HOST="127.0.0.1:$chosenOllamaPort";
            LOCAL_LLM_HEALTH_CHECK_URL="http://127.0.0.1:$chosenOllamaPort/api/version";LOCAL_LLM_FAIL_FAST='true'}
        if ($MetaDisplay) {
            $asrDefaults = Read-RagMetaAsrDefaults
            foreach ($key in $asrDefaults.Keys) { $defaults[$key] = $asrDefaults[$key] }
        }
        if ($chosenOllamaPort -ne 11435) {
            $defaults['LLM_BASE_URL'] = "http://127.0.0.1:$chosenOllamaPort/v1"
            $defaults['EMBED_BASE_URL'] = "http://127.0.0.1:$chosenOllamaPort/api/embed"
        }
        foreach ($key in $defaults.Keys) {
            $previous = [Environment]::GetEnvironmentVariable($key,'Process')
            if ([string]::IsNullOrWhiteSpace($previous)) {
                $saved[$key] = $previous
                [Environment]::SetEnvironmentVariable($key,$defaults[$key],'Process')
            }
        }
        $spring = Invoke-RagServices -RequestedPort $chosenPort -OllamaPort $chosenOllamaPort -RunDirectory $runDirectory -TimeoutSeconds $TimeoutSeconds -CheckOnly:$CheckOnly -MetaDisplay:$MetaDisplay -ForceRestart:$ForceRestart -Wear:$Wear
        $base = "http://127.0.0.1:$($spring.port)"
        $managementUrl = ''
        $springProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($spring.processId)" -ErrorAction SilentlyContinue
        if ($springProcess) {
            $management = [regex]::Match([string]$springProcess.CommandLine, '(?:--|-D)management\.server\.port=(\d+)')
            if ($management.Success) {
                $healthUrl = "http://127.0.0.1:$($management.Groups[1].Value)/actuator/health"
                if ((Get-RagHttp $healthUrl).status -eq 200) { $managementUrl = $healthUrl }
            }
        }
        $summary = [ordered]@{ok=$true;status='ready';springPid=$spring.processId;springReused=$spring.reuse;ragUrl="$base/chat-ui";springUrl=$base;
            ollamaUrl="http://127.0.0.1:$chosenOllamaPort";ollamaApiUrl="http://127.0.0.1:$chosenOllamaPort/v1";managementHealthUrl=$managementUrl;generationProof='not_requested';logDirectory=$runDirectory}
        $browserUrl = "$base/chat-ui"
        if ($MetaDisplay) {
            $browserUrl = "$base/assets/display/index.html"
            $summary['displayOriginUrl'] = $browserUrl
            $summary['displayRegistrationUrl'] = $displayRegistrationUrl
            $summary['publicHttpsProof'] = Ensure-RagDisplayHttps -RegistrationUrl $displayRegistrationUrl -BackendPort $spring.port -CheckOnly:$CheckOnly
            $summary['springProfile'] = 'local,meta-display'
            $summary['runtimeRole'] = if ($Wear) { 'wear' } else { 'dev' }
            $summary['fixedPorts'] = @{server=18180;management=18181;netty=18182}
        }
        Write-AwxJsonAtomic -Path (Join-Path $runDirectory 'result.json') -Data $summary
        Write-RagStage 'READY' 'OK' "RAG web: $base/chat-ui"
        Write-RagStage 'READY' 'OK' "Spring server: $base"
        if ($MetaDisplay) {
            Write-RagStage 'READY' 'OK' "Meta Display fixed local origin: $browserUrl"
            if ($displayRegistrationUrl) { Write-RagStage 'READY' 'INFO' "Meta Display configured HTTPS URL: $displayRegistrationUrl" }
            Write-RagStage 'READY' 'INFO' ("Public HTTPS: " + $summary['publicHttpsProof'] + '; Fold6 microphone and glasses hardware require device confirmation.')
        }
        Write-RagStage 'READY' 'OK' "Ollama server: http://127.0.0.1:$chosenOllamaPort"
        Write-RagStage 'READY' 'OK' "Ollama API: http://127.0.0.1:$chosenOllamaPort/v1"
        if ($managementUrl) { Write-RagStage 'READY' 'OK' "Spring health: $managementUrl" }
        Write-RagStage 'READY' 'INFO' 'Core RAG runs inside Spring. Python MCP/HTTPS helpers are independent optional services.'
        if ($DevWatch -and -not $CheckOnly) {
            Start-RagDevWatch -MetaDisplay:$MetaDisplay -Port ([int]$spring.port)
        }
        if ($OpenBrowser -and -not $CheckOnly) {
            if ($MetaDisplay) { $browserUrl = Get-RagDisplayBrowserUrl -Base $base }
            try { Start-Process $browserUrl | Out-Null } catch { Write-RagStage 'READY' 'INFO' 'Open the printed web URL in your browser.' }
        }
        return 0
    } catch {
        $reason = $_.Exception.Message
        if ($reason -notmatch '^[a-z0-9-]{1,100}$') { $reason = 'launcher-unexpected-error' }
        $failedStage = $script:RagStage
        $errorType = $_.Exception.GetType().Name
        $lineNumber = $_.InvocationInfo.ScriptLineNumber
        Write-RagStage $failedStage 'FAILED' "reason=$reason type=$errorType line=$lineNumber log=$script:RagLog"
        Write-AwxJsonAtomic -Path (Join-Path $runDirectory 'result.json') -Data ([ordered]@{ok=$false;status='failed';stage=$failedStage;reason=$reason;logDirectory=$runDirectory})
        return 1
    } finally {
        foreach ($key in $saved.Keys) { [Environment]::SetEnvironmentVariable($key,$saved[$key],'Process') }
        if ($acquired) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
    }
}

if ($MyInvocation.InvocationName -ne '.') { exit (Invoke-RagLauncher) }
