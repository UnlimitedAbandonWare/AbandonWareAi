param(
    [int]$Port = 18166,
    [ValidateSet('chat-ui', 'meta-display', 'rag-studio')]
    [string]$UiSurface = 'chat-ui',
    [ValidatePattern('^(?:[A-Za-z0-9][A-Za-z0-9._-]*(?:,[A-Za-z0-9][A-Za-z0-9._-]*)*)?$')]
    [string]$SpringProfile = '',
    [ValidatePattern('^[a-z][a-z0-9-]{0,31}$')]
    [string]$RuntimeRole = 'dev',
    [int]$ManagementPort = 0,
    [int]$NettyPort = 0,
    [int[]]$ProtectedPorts = @(),
    [switch]$CloseConflictingListener,
    [switch]$FixedPorts,
    [switch]$PlanOnly,
    [switch]$SkipVerification,
    [string[]]$VerificationTasks = @("checkLangchain4jVersionPurity", "checkSourceSetHygiene", "compileJava"),
    [string]$StatePath = "var\codex-runtime\chat-ui-current.json",
    [string]$BuildHostId = "desktop-vibe",
    [string]$ProjectCacheDir = "",
    [string]$GradleUserHome = "",
    [string]$OutDir = "build\codex-smoke",
    [int]$ReadyTimeoutSeconds = 90
)

$ErrorActionPreference = "Stop"
$lifecyclePath = Join-Path $PSScriptRoot "chat_ui_vibe_lifecycle.ps1"
if (-not (Test-Path -LiteralPath $lifecyclePath -PathType Leaf)) {
    throw "lifecycle-library-missing"
}
. $lifecyclePath

$root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$currentRoot = (Resolve-Path -LiteralPath (Get-Location).Path).Path
if (-not $currentRoot.Equals($root, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "canonical-root-mismatch"
}

function Resolve-AwxRootedPath {
    param([string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $root $Path))
}

$resolvedOutDir = Resolve-AwxRootedPath -Path $OutDir
$resolvedStatePath = Resolve-AwxRootedPath -Path $StatePath
$pagePath = if ($UiSurface -eq 'meta-display') { '/assets/display/index.html' } elseif ($UiSurface -eq 'rag-studio') { '/assets/interview/studio.html' } else { '/chat-ui' }
$sourceAssetPath = if ($UiSurface -eq 'meta-display') { 'main\resources\static\assets\display\app.js' } elseif ($UiSurface -eq 'rag-studio') { 'main\resources\static\assets\interview\studio.js' } else { 'main\resources\static\js\chat.js' }

function Write-Json {
    param([System.Collections.IDictionary]$Data)

    $Data['uiSurface'] = $UiSurface
    $Data['springProfile'] = $SpringProfile
    $json = $Data | ConvertTo-Json -Depth 8 -Compress
    $resultPort = 0
    if ($Data.Contains("selectedPorts")) {
        $selectedPortsValue = $Data["selectedPorts"]
        $serverValue = Get-AwxObjectProperty -Object $selectedPortsValue -Name "server"
        if ($null -ne $serverValue) {
            $resultPort = [int]$serverValue
        }
    }
    if ($resultPort -le 0 -and $Data.Contains("port")) {
        $resultPort = [int]$Data["port"]
    }
    if ($resultPort -le 0) {
        $resultPort = [int]$Port
    }
    if (-not [string]::IsNullOrWhiteSpace($resolvedOutDir)) {
        New-Item -ItemType Directory -Force -Path $resolvedOutDir | Out-Null
        $resultFileName = if ($resultPort -gt 0) {
            "chat-ui-vibe-listener-$resultPort.result.json"
        } else {
            "chat-ui-vibe-listener.result.json"
        }
        Write-AwxJsonAtomic `
            -Path (Join-Path $resolvedOutDir $resultFileName) `
            -Data $Data
    }
    [Console]::Out.WriteLine($json)
}

function Start-ChatUiListener {
    param(
        [int]$ServerPort,
        [int]$ManagementPort,
        [int]$NettyPort,
        [string]$RunId,
        [string]$RuntimeRole,
        [string]$CacheDir,
        [string]$LogDir,
        [int]$TimeoutSeconds
    )

    # Refresh at the actual launch boundary; recovery from a long-lived shell
    # must use current Windows settings instead of its inherited credentials.
    . (Join-Path $PSScriptRoot 'use_project_keys.ps1') -Root $root -Runtime | Out-Null
    New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
    $outLog = Join-Path $LogDir "chat-ui-vibe-listener-$ServerPort.out.log"
    $errLog = Join-Path $LogDir "chat-ui-vibe-listener-$ServerPort.err.log"
    $pidFile = Join-Path $LogDir "chat-ui-vibe-listener-$ServerPort.pid"
    $applicationArgs = "--server.port=$ServerPort --management.server.port=$ManagementPort --netty.port=$NettyPort --awx.runtime.run-id=$RunId --awx.runtime.role=$RuntimeRole"
    if ($SpringProfile) {
        $applicationArgs += " --spring.profiles.active=$SpringProfile"
    }
    # Per-launch debug loggers only (process-env, set by debug_rag_stack.ps1 /
    # Debug-*.bat, never persisted). Entries become visible --logging.level.*
    # JVM args so diagnostics can prove whether verbose logging was applied.
    foreach ($entry in @($env:AWX_RAG_DEBUG_LOGGERS -split '[;,]' | Select-Object -First 8)) {
        if ([string]::IsNullOrWhiteSpace($entry)) { continue }
        if ($entry.Trim() -match '^([A-Za-z0-9][A-Za-z0-9._-]{0,119})=(TRACE|DEBUG|INFO|WARN|ERROR)$') {
            $applicationArgs += " --logging.level.$($Matches[1])=$($Matches[2])"
        }
    }
    $args = @(
        "bootRun",
        "--no-daemon",
        "-x",
        "test",
        "--project-cache-dir",
        $CacheDir,
        "--args=`"$applicationArgs`""
    )
    $launchBoundaryUtc = (Get-Date).ToUniversalTime()
    $proc = Start-Process -FilePath (Join-Path $root "gradlew.bat") `
        -ArgumentList $args `
        -WorkingDirectory $root `
        -PassThru `
        -RedirectStandardOutput $outLog `
        -RedirectStandardError $errLog `
        -WindowStyle Hidden
    $launcherIdentity = $null
    $identityDeadline = (Get-Date).AddSeconds(3)
    while ((Get-Date) -lt $identityDeadline -and $null -eq $launcherIdentity) {
        $launcherIdentity = Get-AwxProcessIdentity -ProcessId $proc.Id
        if ($null -eq $launcherIdentity) {
            Start-Sleep -Milliseconds 50
        }
    }
    if ($null -eq $launcherIdentity) {
        if (-not $proc.HasExited) {
            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
        throw "launcher-identity-missing"
    }
    Set-Content -LiteralPath $pidFile -Value ([string]$proc.Id) -Encoding ASCII
    try {
        $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
        $probeUrl = "http://127.0.0.1:$ServerPort${pagePath}?codexSmoke=vibe-listener"
        while ((Get-Date) -lt $deadline) {
            $proc.Refresh()
            if ($proc.HasExited) {
                throw "bootRun-exited-before-ready"
            }
            try {
                $response = Invoke-WebRequest -Uri $probeUrl -UseBasicParsing -TimeoutSec 3 -ErrorAction Stop
                if ([int]$response.StatusCode -ge 200 -and [int]$response.StatusCode -lt 500) {
                    return [pscustomobject][ordered]@{
                        processId = [int]$proc.Id
                        launcherIdentity = $launcherIdentity
                        launchBoundaryUtc = $launchBoundaryUtc.ToString("o")
                        url = $probeUrl
                        outLog = ConvertTo-AwxRelativePath -Root $root -Path $outLog
                        errLog = ConvertTo-AwxRelativePath -Root $root -Path $errLog
                        pidFile = ConvertTo-AwxRelativePath -Root $root -Path $pidFile
                        statusCode = [int]$response.StatusCode
                    }
                }
            } catch {
                Start-Sleep -Milliseconds 750
            }
        }
        throw "chat-ui-listener-readiness-timeout"
    } catch {
        Stop-AwxStartedProcessTree -LauncherIdentity $launcherIdentity -TimeoutSeconds 15 | Out-Null
        throw
    }
}

function Get-AwxManifestFingerprint {
    param([object]$Manifest)

    if ($null -eq $Manifest) {
        return "absent"
    }
    $json = $Manifest | ConvertTo-Json -Depth 12 -Compress
    return Get-AwxSha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes([string]$json))
}

$protected = @(Get-AwxProtectedPorts -AdditionalPorts $ProtectedPorts)
$prior = Read-AwxRuntimeManifest -Path $resolvedStatePath
$priorFingerprint = Get-AwxManifestFingerprint -Manifest $prior
$ownedIdentity = if ($null -ne $prior) {
    Test-AwxOwnedRuntimeIdentity -Manifest $prior -Root $root
} else {
    $null
}
if ($null -ne $prior -and -not $ownedIdentity.ok) {
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = $false
        status = "owned-runtime-attribution-failed"
        mutationAllowed = $false
        reason = [string]$ownedIdentity.reason
        port = [int]$Port
        priorManifestPresent = $true
        nextAction = "remove_or_repair_owned_manifest_after_manual_owner_verification"
    }
    exit 3
}

$reusable = if ($null -ne $ownedIdentity -and $ownedIdentity.ok) {
    @(
        [int](Get-AwxObjectProperty -Object $prior.ports -Name "server"),
        [int](Get-AwxObjectProperty -Object $prior.ports -Name "management"),
        [int](Get-AwxObjectProperty -Object $prior.ports -Name "netty")
    ) | Where-Object { $_ -gt 0 }
} else {
    @()
}
$portPlan = Resolve-AwxRuntimePorts `
    -ServerPort $Port `
    -ManagementPort $ManagementPort `
    -NettyPort $NettyPort `
    -ProtectedPorts $protected `
    -ReusableOwnedPorts $reusable `
    -FixedPorts:$FixedPorts

if (-not $portPlan.ok -or $PlanOnly) {
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = [bool]$portPlan.ok
        status = [string]$portPlan.status
        mutationAllowed = $false
        compatibilityCloseRequested = [bool]$CloseConflictingListener
        selectedPorts = [ordered]@{
            server = [int]$portPlan.serverPort
            management = [int]$portPlan.managementPort
            netty = [int]$portPlan.nettyPort
        }
        protectedPorts = @($protected)
        replacementCount = [int]$portPlan.replacementCount
        replacementReasons = @($portPlan.replacementReasons)
        priorManifestPresent = $null -ne $prior
        statePath = ConvertTo-AwxRelativePath -Root $root -Path $resolvedStatePath
        nextAction = if ($portPlan.ok) { "run_verified_restart" } else { "resolve_port_or_owner_conflict" }
    }
    if ($portPlan.ok) { exit 0 } else { exit 2 }
}

if (-not $GradleUserHome) {
    $GradleUserHome = Join-Path $env:USERPROFILE ".gradle-awx-desktop"
}
if (-not $ProjectCacheDir) {
    $ProjectCacheDir = Join-Path $env:USERPROFILE ".awx-gradle-project-cache\$BuildHostId"
}
New-Item -ItemType Directory -Force -Path $GradleUserHome, $ProjectCacheDir, $resolvedOutDir | Out-Null
$env:AWX_AGENT_HOST = "desktop"
$env:AWX_SPLIT_BUILD_OUTPUTS = "1"
$env:AWX_BUILD_HOST_ID = $BuildHostId
$env:GRADLE_USER_HOME = $GradleUserHome

$verifyOut = Join-Path $resolvedOutDir "chat-ui-vibe-verification.out.log"
$verifyErr = Join-Path $resolvedOutDir "chat-ui-vibe-verification.err.log"
$verification = if ($SkipVerification) {
    [pscustomobject][ordered]@{
        exitCode = 0
        status = "skipped-explicitly"
        taskCount = 0
        outLog = ""
        errLog = ""
    }
} else {
    Invoke-AwxGradleVerification `
        -Root $root `
        -GradlePath (Join-Path $root "gradlew.bat") `
        -Tasks $VerificationTasks `
        -ProjectCacheDir $ProjectCacheDir `
        -OutLog $verifyOut `
        -ErrLog $verifyErr
}
if ([int]$verification.exitCode -ne 0) {
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = $false
        status = "verification-failed"
        mutationAllowed = $false
        verificationStatus = [string]$verification.status
        verificationTaskCount = [int]$verification.taskCount
        selectedPorts = [ordered]@{
            server = [int]$portPlan.serverPort
            management = [int]$portPlan.managementPort
            netty = [int]$portPlan.nettyPort
        }
        protectedPorts = @($protected)
        nextAction = "fix_verification_before_runtime_cleanup"
    }
    exit 3
}

$priorAfterVerification = Read-AwxRuntimeManifest -Path $resolvedStatePath
if ((Get-AwxManifestFingerprint -Manifest $priorAfterVerification) -cne $priorFingerprint) {
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = $false
        status = "owned-runtime-attribution-failed"
        mutationAllowed = $false
        reason = "manifest-changed-after-verification"
        selectedPorts = [ordered]@{
            server = [int]$portPlan.serverPort
            management = [int]$portPlan.managementPort
            netty = [int]$portPlan.nettyPort
        }
        protectedPorts = @($protected)
        nextAction = "re-run_after_manifest_owner_is_stable"
    }
    exit 3
}
$priorStateSha256 = if (Test-Path -LiteralPath $resolvedStatePath -PathType Leaf) {
    Get-AwxFileSha256Hex -Path $resolvedStatePath
} else {
    ""
}

$protected = @(Get-AwxProtectedPorts -AdditionalPorts $ProtectedPorts)
$ownedIdentityAfterVerification = if ($null -ne $priorAfterVerification) {
    Test-AwxOwnedRuntimeIdentity -Manifest $priorAfterVerification -Root $root
} else {
    $null
}
if ($null -ne $priorAfterVerification -and -not $ownedIdentityAfterVerification.ok) {
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = $false
        status = "owned-runtime-attribution-failed"
        mutationAllowed = $false
        reason = [string]$ownedIdentityAfterVerification.reason
        selectedPorts = [ordered]@{
            server = [int]$portPlan.serverPort
            management = [int]$portPlan.managementPort
            netty = [int]$portPlan.nettyPort
        }
        protectedPorts = @($protected)
        nextAction = "re-run_after_manifest_owner_is_stable"
    }
    exit 3
}
$reusableAfterVerification = if ($null -ne $ownedIdentityAfterVerification -and $ownedIdentityAfterVerification.ok) {
    @($ownedIdentityAfterVerification.ports)
} else {
    @()
}
$portPlan = Resolve-AwxRuntimePorts `
    -ServerPort $Port `
    -ManagementPort $ManagementPort `
    -NettyPort $NettyPort `
    -ProtectedPorts $protected `
    -ReusableOwnedPorts $reusableAfterVerification `
    -FixedPorts:$FixedPorts
if (-not $portPlan.ok) {
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = $false
        status = [string]$portPlan.status
        mutationAllowed = $false
        selectedPorts = [ordered]@{
            server = [int]$portPlan.serverPort
            management = [int]$portPlan.managementPort
            netty = [int]$portPlan.nettyPort
        }
        protectedPorts = @($protected)
        nextAction = "resolve_port_conflict_after_verification"
    }
    exit 2
}

$cleanup = Invoke-AwxVerifiedCleanup `
    -VerificationExitCode ([int]$verification.exitCode) `
    -Manifest $priorAfterVerification `
    -Root $root
$priorCleanup = ConvertTo-AwxCleanupEvidence -Cleanup $cleanup
if (-not $cleanup.ok) {
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = $false
        status = [string]$cleanup.status
        mutationAllowed = $false
        priorCleanup = $priorCleanup
        selectedPorts = [ordered]@{
            server = [int]$portPlan.serverPort
            management = [int]$portPlan.managementPort
            netty = [int]$portPlan.nettyPort
        }
        protectedPorts = @($protected)
        nextAction = "inspect_owned_runtime_cleanup_evidence"
    }
    exit 3
}

$manifestConsumption = Move-AwxConsumedRuntimeManifest `
    -Path $resolvedStatePath `
    -ExpectedSha256 $priorStateSha256
if (-not $manifestConsumption.ok) {
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = $false
        status = [string]$manifestConsumption.status
        mutationAllowed = [string]$cleanup.status -eq "owned-runtime-stopped"
        priorRuntimeStatus = [string]$cleanup.status
        priorCleanup = $priorCleanup
        selectedPorts = [ordered]@{
            server = [int]$portPlan.serverPort
            management = [int]$portPlan.managementPort
            netty = [int]$portPlan.nettyPort
        }
        protectedPorts = @($protected)
        nextAction = "verify_runtime_manifest_owner_before_retry"
    }
    exit 3
}

$runId = "awx-chat-runtime-" + [guid]::NewGuid().ToString("N")
$started = $null
try {
    $started = Start-ChatUiListener `
        -ServerPort ([int]$portPlan.serverPort) `
        -ManagementPort ([int]$portPlan.managementPort) `
        -NettyPort ([int]$portPlan.nettyPort) `
        -RunId $runId `
        -RuntimeRole $RuntimeRole `
        -CacheDir $ProjectCacheDir `
        -LogDir $resolvedOutDir `
        -TimeoutSeconds $ReadyTimeoutSeconds
} catch {
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = $false
        status = "fresh-runtime-provenance-failed"
        mutationAllowed = $true
        reason = "runtime-start-or-readiness-failed"
        priorCleanup = $priorCleanup
        selectedPorts = [ordered]@{
            server = [int]$portPlan.serverPort
            management = [int]$portPlan.managementPort
            netty = [int]$portPlan.nettyPort
        }
        protectedPorts = @($protected)
        nextAction = "inspect_redacted_runtime_logs"
    }
    exit 4
}

$provenance = Test-AwxFreshRuntimeProvenance `
    -Root $root `
    -LauncherPid ([int]$started.processId) `
    -LaunchBoundaryUtc ([datetime]$started.launchBoundaryUtc) `
    -RunId $runId `
    -ServerPort ([int]$portPlan.serverPort) `
    -SourceAssetPath $sourceAssetPath `
    -UiSurface $UiSurface
if (-not $provenance.ok) {
    $newRuntimeCleanup = Stop-AwxStartedProcessTree -LauncherIdentity $started.launcherIdentity -TimeoutSeconds 15
    Write-Json @{
        schemaVersion = "awx.chat_ui_vibe_listener.v1"
        ok = $false
        status = "fresh-runtime-provenance-failed"
        mutationAllowed = $true
        reason = [string]$provenance.reason
        newRuntimeCleanupStatus = [string]$newRuntimeCleanup.status
        priorCleanup = $priorCleanup
        selectedPorts = [ordered]@{
            server = [int]$portPlan.serverPort
            management = [int]$portPlan.managementPort
            netty = [int]$portPlan.nettyPort
        }
        protectedPorts = @($protected)
        nextAction = "inspect_fresh_runtime_provenance"
    }
    exit 4
}

$listenerIdentity = Get-AwxProcessIdentity -ProcessId ([int]$provenance.listenerPid)
$lineage = Get-AwxProcessLineage `
    -ProcessId ([int]$provenance.listenerPid) `
    -StopProcessId ([int]$started.processId)
$runIdHash = Get-AwxSha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes($runId))
$state = [ordered]@{
    schemaVersion = "awx.chat_ui_owned_runtime.v1"
    rootHash = Get-AwxCanonicalRootHash -Root $root
    runId = $runId
    runIdHash = $runIdHash
    launcher = $started.launcherIdentity
    listener = $listenerIdentity
    parentLineageHash = [string]$lineage.hash
    springProfile = $SpringProfile
    runtimeRole = $RuntimeRole
    ports = [ordered]@{
        server = [int]$portPlan.serverPort
        management = [int]$portPlan.managementPort
        netty = [int]$portPlan.nettyPort
    }
    sourceAssetHash = [string]$provenance.sourceAssetHash
    servedAssetHash = [string]$provenance.servedAssetHash
    startedAt = [string]$provenance.startedAt
    readyAt = (Get-Date).ToUniversalTime().ToString("o")
    logs = [ordered]@{
        out = [string]$started.outLog
        err = [string]$started.errLog
        pid = [string]$started.pidFile
    }
}
Write-AwxJsonAtomic -Path $resolvedStatePath -Data $state

$browserTargetUrl = "http://127.0.0.1:$([int]$portPlan.serverPort)${pagePath}?awxRuntime=$runId"
Write-Json @{
    schemaVersion = "awx.chat_ui_vibe_listener.v1"
    ok = $true
    status = "listener-ready"
    mutationAllowed = $true
    compatibilityCloseRequested = [bool]$CloseConflictingListener
    priorRuntimeStatus = [string]$cleanup.status
    priorCleanup = $priorCleanup
    verificationStatus = [string]$verification.status
    verificationTaskCount = [int]$verification.taskCount
    selectedPorts = [ordered]@{
        server = [int]$portPlan.serverPort
        management = [int]$portPlan.managementPort
        netty = [int]$portPlan.nettyPort
    }
    protectedPorts = @($protected)
    runIdHash = $runIdHash
    browserTargetUrl = $browserTargetUrl
    sourceAssetHash = [string]$provenance.sourceAssetHash
    servedAssetHash = [string]$provenance.servedAssetHash
    statePath = ConvertTo-AwxRelativePath -Root $root -Path $resolvedStatePath
    port = [int]$portPlan.serverPort
    listener = $started
    nextAction = "open_browser_target_url"
}
