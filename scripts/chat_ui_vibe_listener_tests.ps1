$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Needle)
    if (-not $Text.Contains($Needle)) {
        throw "[FAIL] $Name missing: $Needle"
    }
}

function Assert-NotContains {
    param([string]$Name, [string]$Text, [string]$Needle)
    if ($Text.Contains($Needle)) {
        throw "[FAIL] $Name unexpected: $Needle"
    }
}

$scriptPath = Join-Path $PSScriptRoot "chat_ui_vibe_listener.ps1"
$source = Get-Content -Raw $scriptPath
$testStatePath = Join-Path ([IO.Path]::GetTempPath()) ('chat-ui-vibe-unused-state-' + [guid]::NewGuid().ToString('N') + '.json')

Assert-Contains "listener has explicit close switch" $source "[switch]`$CloseConflictingListener"
Assert-Contains "listener has plan-only switch" $source "[switch]`$PlanOnly"
Assert-Contains "listener loads lifecycle library" $source "chat_ui_vibe_lifecycle.ps1"
Assert-Contains "listener resolves protected ports" $source "Get-AwxProtectedPorts"
Assert-Contains "listener validates owned manifest" $source "Test-AwxOwnedRuntimeIdentity"
Assert-Contains "listener gates cleanup on verification" $source "Invoke-AwxVerifiedCleanup"
Assert-Contains "listener starts hidden bootRun" $source "-WindowStyle Hidden"
Assert-Contains "listener passes server port" $source "--server.port=`$ServerPort"
Assert-Contains "listener writes pid file" $source "chat-ui-vibe-listener-`$ServerPort.pid"
Assert-Contains "listener probes chat UI" $source "Invoke-WebRequest -Uri `$probeUrl"
Assert-Contains "listener emits JSON" $source "Write-Json"
Assert-NotContains "listener avoids PowerShell readonly PID shortcut" $source "`$PID"

$plan = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Port 18166 -StatePath $testStatePath -PlanOnly 2>$null
$json = $plan | ConvertFrom-Json
if ($json.schemaVersion -ne "awx.chat_ui_vibe_listener.v1") {
    throw "[FAIL] plan-only schema mismatch: $($json.schemaVersion)"
}
if ($json.mutationAllowed -ne $false) {
    throw "[FAIL] plan-only must not mutate"
}
if ([string]$json.status -ne "ready-to-restart") {
    throw "[FAIL] plan-only status unexpected: $($json.status)"
}

$durableOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-listener-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $durableOut | Out-Null
try {
    $durablePlan = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Port 18166 -StatePath $testStatePath -PlanOnly -OutDir $durableOut 2>$null
    $durableJson = $durablePlan | ConvertFrom-Json
    $durableResultPath = Join-Path $durableOut "chat-ui-vibe-listener-$([int]$durableJson.selectedPorts.server).result.json"
    if (-not (Test-Path -LiteralPath $durableResultPath)) {
        throw "[FAIL] plan-only should write durable result JSON: $durableResultPath"
    }
    $durableFileJson = Get-Content -Raw -LiteralPath $durableResultPath | ConvertFrom-Json
    if ($durableFileJson.schemaVersion -ne "awx.chat_ui_vibe_listener.v1") {
        throw "[FAIL] durable result schema mismatch: $($durableFileJson.schemaVersion)"
    }
    if ([string]$durableFileJson.status -ne [string]$durableJson.status) {
        throw "[FAIL] durable result should match stdout status: stdout=$($durableJson.status) file=$($durableFileJson.status)"
    }
} finally {
    Remove-Item -LiteralPath $durableOut -Recurse -Force -ErrorAction SilentlyContinue
}

$tcp = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$tcp.Start()
try {
    $conflictPort = [int]$tcp.LocalEndpoint.Port
    $conflictPlan = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -Port $conflictPort -StatePath $testStatePath -PlanOnly 2>$null
    $conflictJson = $conflictPlan | ConvertFrom-Json
    if ($conflictJson.status -ne "ready-to-restart") {
        throw "[FAIL] dynamic conflict status unexpected: $($conflictJson.status)"
    }
    if ([int]$conflictJson.selectedPorts.server -eq $conflictPort) {
        throw "[FAIL] foreign listener port must be replaced"
    }
    if ([int]$conflictJson.replacementCount -lt 1) {
        throw "[FAIL] dynamic conflict should report replacementCount"
    }
    if (-not $tcp.Server.IsBound) {
        throw "[FAIL] foreign listener must remain bound"
    }
} finally {
    $tcp.Stop()
}

$lifecyclePath = Join-Path $PSScriptRoot "chat_ui_vibe_lifecycle.ps1"
if (-not (Test-Path -LiteralPath $lifecyclePath -PathType Leaf)) {
    throw "[FAIL] missing lifecycle library: $lifecyclePath"
}
. $lifecyclePath

$cleanupEvidence = ConvertTo-AwxCleanupEvidence -Cleanup ([pscustomobject][ordered]@{
    status = "owned-runtime-stopped"
    stopAttemptCount = 3
    alreadyExitedCount = 1
    skippedDescendantCount = 2
    stoppedProcessCount = 2
    remainingProcessCount = 0
    portCount = 3
    reason = "must-not-propagate"
    launcherPid = 4242
})
$cleanupEvidenceNames = @($cleanupEvidence.PSObject.Properties.Name) -join ","
$expectedCleanupEvidenceNames = "status,stopAttemptCount,alreadyExitedCount,skippedDescendantCount,stoppedProcessCount,remainingProcessCount,portCount"
if ($cleanupEvidenceNames -cne $expectedCleanupEvidenceNames) {
    throw "[FAIL] cleanup evidence fields=$cleanupEvidenceNames expected=$expectedCleanupEvidenceNames"
}
if ([string]$cleanupEvidence.status -cne "owned-runtime-stopped" -or
    [int]$cleanupEvidence.stopAttemptCount -ne 3 -or
    [int]$cleanupEvidence.alreadyExitedCount -ne 1 -or
    [int]$cleanupEvidence.skippedDescendantCount -ne 2 -or
    [int]$cleanupEvidence.stoppedProcessCount -ne 2 -or
    [int]$cleanupEvidence.remainingProcessCount -ne 0 -or
    [int]$cleanupEvidence.portCount -ne 3) {
    throw "[FAIL] cleanup evidence changed literal count values"
}
$emptyCleanupEvidence = ConvertTo-AwxCleanupEvidence -Cleanup ([pscustomobject]@{
    status = "no-prior-runtime"
})
if ([int]$emptyCleanupEvidence.stopAttemptCount -ne 0 -or
    [int]$emptyCleanupEvidence.alreadyExitedCount -ne 0 -or
    [int]$emptyCleanupEvidence.skippedDescendantCount -ne 0 -or
    [int]$emptyCleanupEvidence.stoppedProcessCount -ne 0 -or
    [int]$emptyCleanupEvidence.remainingProcessCount -ne 0 -or
    [int]$emptyCleanupEvidence.portCount -ne 0) {
    throw "[FAIL] cleanup evidence must default absent counts to zero"
}

$manifestConsumeRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-runtime-manifest-consume-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $manifestConsumeRoot | Out-Null
try {
    $manifestConsumeState = Join-Path $manifestConsumeRoot "state.json"
    $manifestConsumeArchive = Join-Path $manifestConsumeRoot "stale"
    Write-AwxJsonAtomic -Path $manifestConsumeState -Data ([ordered]@{
        schemaVersion = "awx.chat_ui_owned_runtime.v1"
        runIdHash = "test-run-hash"
    })
    $manifestConsumeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $manifestConsumeState).Hash.ToLowerInvariant()

    $changedManifest = Move-AwxConsumedRuntimeManifest `
        -Path $manifestConsumeState `
        -ExpectedSha256 ("0" * 64) `
        -ArchiveDirectory $manifestConsumeArchive
    if ($changedManifest.ok -or $changedManifest.status -ne "manifest-changed-after-cleanup") {
        throw "[FAIL] changed manifest must fail closed: $($changedManifest.status)"
    }
    if (-not (Test-Path -LiteralPath $manifestConsumeState -PathType Leaf)) {
        throw "[FAIL] changed manifest must remain active"
    }

    $consumedManifest = Move-AwxConsumedRuntimeManifest `
        -Path $manifestConsumeState `
        -ExpectedSha256 $manifestConsumeHash `
        -ArchiveDirectory $manifestConsumeArchive
    if (-not $consumedManifest.ok -or $consumedManifest.status -ne "owned-runtime-manifest-consumed") {
        throw "[FAIL] verified manifest consume status=$($consumedManifest.status)"
    }
    if (Test-Path -LiteralPath $manifestConsumeState) {
        throw "[FAIL] consumed manifest remained active"
    }
    if (-not (Test-Path -LiteralPath $consumedManifest.archivePath -PathType Leaf)) {
        throw "[FAIL] consumed manifest archive missing"
    }
    $archivedManifestHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $consumedManifest.archivePath).Hash.ToLowerInvariant()
    if ($archivedManifestHash -cne $manifestConsumeHash) {
        throw "[FAIL] consumed manifest archive hash mismatch"
    }

    $absentManifest = Move-AwxConsumedRuntimeManifest `
        -Path $manifestConsumeState `
        -ExpectedSha256 "" `
        -ArchiveDirectory $manifestConsumeArchive
    if (-not $absentManifest.ok -or $absentManifest.status -ne "manifest-absent") {
        throw "[FAIL] absent manifest consume status=$($absentManifest.status)"
    }
} finally {
    Remove-Item -LiteralPath $manifestConsumeRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$extraProtected = 19001
$protected = @(Get-AwxProtectedPorts -AdditionalPorts @($extraProtected))
foreach ($required in @(11434, 11435, 11438, $extraProtected)) {
    if ($protected -notcontains $required) {
        throw "[FAIL] protected port missing: $required"
    }
}

$tcp = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$tcp.Start()
try {
    $foreignPort = [int]$tcp.LocalEndpoint.Port
    $auto = Resolve-AwxRuntimePorts -ServerPort $foreignPort -ManagementPort 0 -NettyPort 0 `
        -ProtectedPorts $protected -ReusableOwnedPorts @() -FixedPorts:$false
    if (-not $auto.ok) {
        throw "[FAIL] automatic plan should succeed: $($auto.status)"
    }
    if ($auto.serverPort -eq $foreignPort) {
        throw "[FAIL] foreign listener was reused"
    }
    $uniquePorts = @($auto.serverPort, $auto.managementPort, $auto.nettyPort) | Select-Object -Unique
    if (@($uniquePorts).Count -ne 3) {
        throw "[FAIL] launch ports must be distinct"
    }
    $fixed = Resolve-AwxRuntimePorts -ServerPort $foreignPort -ManagementPort 0 -NettyPort 0 `
        -ProtectedPorts $protected -ReusableOwnedPorts @() -FixedPorts
    if ($fixed.status -ne "foreign-port-owner") {
        throw "[FAIL] fixed conflict status=$($fixed.status)"
    }
} finally {
    $tcp.Stop()
}

$blocked = Resolve-AwxRuntimePorts -ServerPort 11435 -ManagementPort 0 -NettyPort 0 `
    -ProtectedPorts $protected -ReusableOwnedPorts @() -FixedPorts:$false
if ($blocked.status -ne "protected-port-conflict") {
    throw "[FAIL] protected status=$($blocked.status)"
}

$planTestRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-runtime-plan-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $planTestRoot | Out-Null
try {
    $planStatePath = Join-Path $planTestRoot "state.json"
    $planOut = Join-Path $planTestRoot "out"
    $planLine = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port 0 -ManagementPort 0 -NettyPort 0 -ProtectedPorts 19001 -PlanOnly `
        -StatePath $planStatePath -OutDir $planOut 2>$null | Select-Object -Last 1
    $planJson = $planLine | ConvertFrom-Json
    if (-not $planJson.ok -or $planJson.status -ne "ready-to-restart") {
        throw "[FAIL] plan status=$($planJson.status)"
    }
    $selected = @(
        [int]$planJson.selectedPorts.server,
        [int]$planJson.selectedPorts.management,
        [int]$planJson.selectedPorts.netty
    )
    if (@($selected | Select-Object -Unique).Count -ne 3) {
        throw "[FAIL] plan ports not distinct"
    }
    if (@($selected | Where-Object { $planJson.protectedPorts -contains $_ }).Count -ne 0) {
        throw "[FAIL] plan selected a protected port"
    }
    if (Test-Path -LiteralPath $planStatePath) {
        throw "[FAIL] plan-only must not publish runtime state"
    }
} finally {
    Remove-Item -LiteralPath $planTestRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$earlyFailureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-runtime-early-failure-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $earlyFailureRoot | Out-Null
try {
    $earlyState = Join-Path $earlyFailureRoot "invalid-state.json"
    $earlyOut = Join-Path $earlyFailureRoot "out"
    Set-Content -LiteralPath $earlyState -Value '{"schemaVersion":"invalid"}' -Encoding UTF8
    $earlyLine = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port 0 -PlanOnly -StatePath $earlyState -OutDir $earlyOut 2>$null | Select-Object -Last 1
    $earlyJson = $earlyLine | ConvertFrom-Json
    if ($earlyJson.status -ne "owned-runtime-attribution-failed") {
        throw "[FAIL] early failure status=$($earlyJson.status)"
    }
    $earlyResultPath = Join-Path $earlyOut "chat-ui-vibe-listener.result.json"
    if (-not (Test-Path -LiteralPath $earlyResultPath)) {
        throw "[FAIL] automatic-port early failure must write a durable result"
    }
} finally {
    Remove-Item -LiteralPath $earlyFailureRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$source = Get-Content -Raw -LiteralPath $scriptPath
$verifyIndex = $source.IndexOf("Invoke-AwxGradleVerification")
$cleanupIndex = $source.IndexOf("Invoke-AwxVerifiedCleanup")
$consumeIndex = $source.IndexOf("Move-AwxConsumedRuntimeManifest", $cleanupIndex)
$startIndex = $source.LastIndexOf("Start-ChatUiListener")
if ($verifyIndex -lt 0 -or $cleanupIndex -le $verifyIndex -or
    $consumeIndex -le $cleanupIndex -or $startIndex -le $consumeIndex) {
    throw "[FAIL] lifecycle order must be verify -> cleanup -> consume manifest -> start"
}
Assert-Contains "listener passes management port" $source "--management.server.port="
Assert-Contains "listener passes netty port" $source "--netty.port="
Assert-Contains "listener passes runtime run id" $source "--awx.runtime.run-id="
Assert-NotContains "listener avoids taskkill" $source "taskkill.exe"
Assert-NotContains "listener does not persist command line" $source "commandLine ="

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("awx-owned-runtime-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$hashProbePath = Join-Path $testRoot "empty-hash-probe.bin"
[System.IO.File]::WriteAllBytes($hashProbePath, [byte[]]@())
$hashProbeFailure = ""
$hashProbeActual = ""
function Get-FileHash {
    throw "external-hash-cmdlet-disabled"
}
try {
    $hashProbeActual = Get-AwxFileSha256Hex -Path $hashProbePath
} catch {
    $hashProbeFailure = [string]$_.Exception.Message
} finally {
    Remove-Item -LiteralPath "Function:\Get-FileHash" -ErrorAction SilentlyContinue
}
if (-not [string]::IsNullOrWhiteSpace($hashProbeFailure)) {
    throw "[FAIL] file hashing depends on external Get-FileHash: $hashProbeFailure"
}
if ($hashProbeActual -cne "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855") {
    throw "[FAIL] module-independent empty-file SHA-256 mismatch"
}
$manifestProbePath = Join-Path $testRoot "manifest-hash-probe.json"
$manifestArchiveDirectory = Join-Path $testRoot "manifest-archive"
[System.IO.File]::WriteAllBytes($manifestProbePath, [byte[]]@())
$manifestMoveFailure = ""
$manifestMoveResult = $null
function Get-FileHash {
    throw "external-hash-cmdlet-disabled"
}
try {
    $manifestMoveResult = Move-AwxConsumedRuntimeManifest `
        -Path $manifestProbePath `
        -ExpectedSha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" `
        -ArchiveDirectory $manifestArchiveDirectory
} catch {
    $manifestMoveFailure = [string]$_.Exception.Message
} finally {
    Remove-Item -LiteralPath "Function:\Get-FileHash" -ErrorAction SilentlyContinue
}
if (-not [string]::IsNullOrWhiteSpace($manifestMoveFailure)) {
    throw "[FAIL] manifest move depends on external Get-FileHash: $manifestMoveFailure"
}
if ($null -eq $manifestMoveResult -or -not $manifestMoveResult.ok -or
    (Test-Path -LiteralPath $manifestProbePath) -or
    -not (Test-Path -LiteralPath $manifestMoveResult.archivePath -PathType Leaf)) {
    throw "[FAIL] module-independent manifest move did not archive exactly one file"
}
$runId = "awx-test-" + [guid]::NewGuid().ToString("N")
$ownedPort = Find-AwxFreeLoopbackPort -ExcludedPorts @()
$foreignPort = Find-AwxFreeLoopbackPort -ExcludedPorts @($ownedPort)
$ownedReady = Join-Path $testRoot "owned.ready"
$foreignReady = Join-Path $testRoot "foreign.ready"
$ownedDescendantReady = Join-Path $testRoot "owned-descendant.ready"
$foreignDescendantReady = Join-Path $testRoot "foreign-descendant.ready"
$ownedDescendantPid = 0
$foreignDescendantPid = 0
$fixture = Join-Path $PSScriptRoot "tests\fixtures\owned_runtime_listener.ps1"
$owned = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
    "-NoProfile", "-File", $fixture, "-Port", "$ownedPort", "-RunId", $runId,
    "-ReadyPath", $ownedReady, "-DescendantReadyPath", $ownedDescendantReady,
    "-ForeignDescendantReadyPath", $foreignDescendantReady)
$foreign = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
    "-NoProfile", "-File", $fixture, "-Port", "$foreignPort", "-RunId", "foreign-run", "-ReadyPath", $foreignReady)
try {
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline -and
        (-not (Test-Path -LiteralPath $ownedReady) -or
            -not (Test-Path -LiteralPath $foreignReady) -or
            -not (Test-Path -LiteralPath $ownedDescendantReady) -or
            -not (Test-Path -LiteralPath $foreignDescendantReady))) {
        Start-Sleep -Milliseconds 100
    }
    if (-not (Test-Path -LiteralPath $ownedReady) -or
        -not (Test-Path -LiteralPath $foreignReady) -or
        -not (Test-Path -LiteralPath $ownedDescendantReady) -or
        -not (Test-Path -LiteralPath $foreignDescendantReady)) {
        throw "[FAIL] synthetic listeners did not become ready"
    }

    $ownedDescendantPidText = (Get-Content -LiteralPath $ownedDescendantReady -Raw).Trim()
    if (-not [int]::TryParse($ownedDescendantPidText, [ref]$ownedDescendantPid)) {
        throw "[FAIL] owned descendant pid was invalid"
    }
    $foreignDescendantPidText = (Get-Content -LiteralPath $foreignDescendantReady -Raw).Trim()
    if (-not [int]::TryParse($foreignDescendantPidText, [ref]$foreignDescendantPid)) {
        throw "[FAIL] foreign descendant pid was invalid"
    }
    $ownedDescendantIdentity = Get-AwxProcessIdentity -ProcessId $ownedDescendantPid
    $ownedDescendantProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$ownedDescendantPid" -ErrorAction SilentlyContinue
    if ($null -eq $ownedDescendantIdentity -or [int]$ownedDescendantIdentity.parentProcessId -ne $owned.Id -or
        $null -eq $ownedDescendantProcess -or
        ([string]$ownedDescendantProcess.CommandLine).IndexOf($runId, [System.StringComparison]::Ordinal) -lt 0) {
        throw "[FAIL] synthetic process was not a run-token-bearing owned-launcher descendant"
    }
    $foreignDescendantIdentity = Get-AwxProcessIdentity -ProcessId $foreignDescendantPid
    $foreignDescendantProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$foreignDescendantPid" -ErrorAction SilentlyContinue
    if ($null -eq $foreignDescendantIdentity -or [int]$foreignDescendantIdentity.parentProcessId -ne $owned.Id -or
        $null -eq $foreignDescendantProcess -or
        ([string]$foreignDescendantProcess.CommandLine).IndexOf($runId, [System.StringComparison]::Ordinal) -ge 0) {
        throw "[FAIL] synthetic process was not a tokenless foreign-launcher descendant"
    }

    $identity = Get-AwxProcessIdentity -ProcessId $owned.Id
    $ownedLineage = Get-AwxProcessLineage -ProcessId $owned.Id -StopProcessId $owned.Id
    if (@($ownedLineage.rows).Count -ne 1) {
        throw "[FAIL] bounded owned lineage must stop at launcher"
    }
    $manifest = [ordered]@{
        schemaVersion = "awx.chat_ui_owned_runtime.v1"
        rootHash = Get-AwxCanonicalRootHash -Root (Resolve-Path ".").Path
        runId = $runId
        runIdHash = Get-AwxSha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes($runId))
        launcher = $identity
        listener = $identity
        parentLineageHash = $ownedLineage.hash
        ports = [ordered]@{ server = $ownedPort; management = 0; netty = 0 }
    }

    $roundTrippedManifest = $manifest | ConvertTo-Json -Depth 8 | ConvertFrom-Json
    $roundTrippedManifest.launcher.creationDate = [datetime]$roundTrippedManifest.launcher.creationDate
    $roundTrippedManifest.listener.creationDate = [datetime]$roundTrippedManifest.listener.creationDate
    $roundTripValidation = Test-AwxOwnedRuntimeIdentity -Manifest $roundTrippedManifest `
        -Root (Resolve-Path ".").Path -AllowedProcessNames @("powershell.exe")
    if (-not $roundTripValidation.ok) {
        throw "[FAIL] JSON round-trip changed owned runtime identity: $($roundTripValidation.reason)"
    }

    $preserved = Invoke-AwxVerifiedCleanup -VerificationExitCode 1 -Manifest $manifest `
        -Root (Resolve-Path ".").Path -AllowedProcessNames @("powershell.exe")
    if ($preserved.status -ne "verification-failed") {
        throw "[FAIL] verification failure status=$($preserved.status)"
    }
    $owned.Refresh()
    if ($owned.HasExited) {
        throw "[FAIL] failed verification stopped the runtime"
    }

    foreach ($field in @("runId", "rootHash", "creationDate", "processId")) {
        $bad = $manifest | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        switch ($field) {
            "runId" { $bad.runId = "wrong-run" }
            "rootHash" { $bad.rootHash = "0" * 64 }
            "creationDate" { $bad.listener.creationDate = "1900-01-01T00:00:00.0000000Z" }
            "processId" { $bad.listener.processId = 4 }
        }
        $denied = Stop-AwxOwnedRuntime -Manifest $bad -Root (Resolve-Path ".").Path `
            -AllowedProcessNames @("powershell.exe") -TimeoutSeconds 2
        if ($denied.status -ne "owned-runtime-attribution-failed") {
            throw "[FAIL] mismatch accepted: $field"
        }
        $owned.Refresh()
        if ($owned.HasExited) {
            throw "[FAIL] mismatch stopped owned fixture: $field"
        }
        $foreign.Refresh()
        if ($foreign.HasExited) {
            throw "[FAIL] mismatch stopped foreign fixture: $field"
        }
    }

    # Reproduce a selected launcher exiting between the descendant stop and its own stop turn.
    # The cleanup must report only the one Stop-Process attempt it actually issued.
    $script:naturalExitRootPid = [int]$owned.Id
    $script:naturalExitInjected = $false
    function Stop-Process {
        [CmdletBinding()]
        param(
            [Parameter(Mandatory = $true)][int[]]$Id,
            [switch]$Force
        )

        Microsoft.PowerShell.Management\Stop-Process -Id $Id -Force:$Force -ErrorAction SilentlyContinue
        if (-not $script:naturalExitInjected) {
            $script:naturalExitInjected = $true
            Microsoft.PowerShell.Management\Stop-Process `
                -Id $script:naturalExitRootPid `
                -Force `
                -ErrorAction SilentlyContinue
            $exitDeadline = (Get-Date).AddSeconds(2)
            while ((Get-Date) -lt $exitDeadline -and
                $null -ne (Get-AwxProcessIdentity -ProcessId $script:naturalExitRootPid)) {
                Start-Sleep -Milliseconds 10
            }
        }
    }
    try {
        $result = Stop-AwxOwnedRuntime -Manifest $manifest -Root (Resolve-Path ".").Path `
            -AllowedProcessNames @("powershell.exe") -TimeoutSeconds 10
        $naturalExitWasInjected = [bool]$script:naturalExitInjected
    } finally {
        Remove-Item -LiteralPath "Function:\Stop-Process" -ErrorAction SilentlyContinue
        Remove-Variable -Name naturalExitRootPid -Scope Script -ErrorAction SilentlyContinue
        Remove-Variable -Name naturalExitInjected -Scope Script -ErrorAction SilentlyContinue
    }
    if (-not $result.ok) {
        throw "[FAIL] owned cleanup status=$($result.status)"
    }
    if (-not $naturalExitWasInjected) {
        throw "[FAIL] natural-exit cleanup scenario was not injected"
    }
    if ([int]$result.stoppedProcessCount -ne 1) {
        throw "[FAIL] natural-exit cleanup stoppedProcessCount=$($result.stoppedProcessCount) expected=1"
    }
    if ($null -eq $result.PSObject.Properties["stopAttemptCount"] -or
        [int]$result.stopAttemptCount -ne 1) {
        throw "[FAIL] natural-exit cleanup stopAttemptCount=$($result.stopAttemptCount) expected=1"
    }
    if ($null -eq $result.PSObject.Properties["alreadyExitedCount"] -or
        [int]$result.alreadyExitedCount -ne 1) {
        throw "[FAIL] natural-exit cleanup alreadyExitedCount=$($result.alreadyExitedCount) expected=1"
    }
    if ($null -eq $result.PSObject.Properties["skippedDescendantCount"] -or
        [int]$result.skippedDescendantCount -lt 1) {
        throw "[FAIL] tokenless sibling skippedDescendantCount=$($result.skippedDescendantCount) expected>=1"
    }
    if ([int]$result.remainingProcessCount -ne 0) {
        throw "[FAIL] natural-exit cleanup remainingProcessCount=$($result.remainingProcessCount) expected=0"
    }
    $owned.Refresh()
    if (-not $owned.HasExited) {
        throw "[FAIL] owned process survived"
    }
    $foreign.Refresh()
    if ($foreign.HasExited) {
        throw "[FAIL] foreign process was stopped"
    }
    if ($null -ne (Get-Process -Id $ownedDescendantPid -ErrorAction SilentlyContinue)) {
        throw "[FAIL] owned launcher descendant survived cleanup"
    }
    if ($null -eq (Get-Process -Id $foreignDescendantPid -ErrorAction SilentlyContinue)) {
        throw "[FAIL] tokenless foreign launcher descendant was stopped"
    }
} finally {
    $cleanupIds = @($owned.Id, $foreign.Id)
    if ($ownedDescendantPid -gt 0) {
        $cleanupIds += $ownedDescendantPid
    }
    if ($foreignDescendantPid -gt 0) {
        $cleanupIds += $foreignDescendantPid
    }
    Stop-Process -Id $cleanupIds -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "[PASS] chat_ui_vibe_listener_tests"
