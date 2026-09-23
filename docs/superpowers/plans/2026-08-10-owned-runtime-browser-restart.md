# Owned Runtime Browser Restart Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a verification-first Desktop restart workflow that preserves Ollama and foreign listeners, cleans up only a manifest-attested prior demo-1 runtime, starts a provenance-checked replacement, and hands its exact URL to the in-app Browser.

**Architecture:** Keep `chat_ui_vibe_listener.ps1` as the only CLI lifecycle owner and extract its OS-facing logic into one dot-sourced PowerShell function library so destructive behavior can be tested against synthetic child processes. The listener verifies source before cleanup, selects non-protected ports, starts `bootRun` with a non-secret run ID, requires listener lineage plus served/source `chat.js` hash equality, then emits one Browser target consumed by the existing soak and the Codex Browser lane.

**Tech Stack:** Windows PowerShell 5.1, CIM and `Get-NetTCPConnection`, .NET `TcpListener`/`WebClient`, Gradle Wrapper, Spring Boot `bootRun`, raw PowerShell contract tests, in-app Browser.

## Global Constraints

- Canonical execution root is `C:\AbandonWare\demo-1\demo-1\src`; no alternate, UNC, Y-drive, or fallback write root is allowed.
- Do not modify Java, resources, `main/resources/static/js/chat.js`, `main/resources/templates/chat-ui.html`, Supabase state, or the PatchDrop queue.
- Default protected ports are exactly `11434`, `11435`, and `11438`; every active `ollama.exe` listener is additionally protected.
- Caller-provided protected ports are additive and can never remove defaults or discovered Ollama ports.
- Stop a process only after current-root hash, manifest run ID, PID creation identity, process name, listener ownership, and parent lineage all pass.
- Verification tasks run before cleanup and default to `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, and `compileJava`.
- Persist only hashes, counts, PIDs, ports, timestamps, relative log paths, and reason codes; never persist raw command lines, environment dumps, credentials, prompts, or responses.
- Keep Desktop Gradle outputs split with `AWX_SPLIT_BUILD_OUTPUTS=1`, a stable `AWX_BUILD_HOST_ID`, isolated `GRADLE_USER_HOME`, and an isolated `--project-cache-dir`.
- Use PowerShell process APIs end-to-end for validated PID cleanup. Do not enumerate in PowerShell and pass targets to `cmd.exe` or `taskkill.exe`.
- Preserve unrelated dirty changes. Never run `git add .`, `git clean`, reset, checkout-discard, or broad formatting.
- Freeze these approved preimages before the first implementation patch and HOLD if any changed: listener `ba711232c8dc1d214e49ebd7d3b61ed086477c1bf124768688d5c7088afb40f5`; listener tests `c373e2d27db0d2b39a5a232ad23d75fed8faf4de38c4c4e44c374078b2adb510`; soak `2a2c124886ec09d24e14687f650081f6d2df4fbe3232b385443d2fcec348d520`; soak tests `551549c89e79db9fb38482bde0c7c5de6c7e85995d8997505783d3d38cb033a0`.
- The four existing script targets are pre-existing untracked files. Review their whole-file secret scan and exact postimage before any path-specific staging; never infer staging authority from hashes alone.

---

## File Structure

- Create `scripts/chat_ui_vibe_lifecycle.ps1`: reusable hashing, listener discovery, protected-port resolution, owned-manifest validation, verified cleanup, Gradle verification, atomic JSON, and fresh-runtime provenance functions. It exposes no CLI entry point and performs no action when dot-sourced.
- Create `scripts/tests/fixtures/owned_runtime_listener.ps1`: synthetic loopback listener used only by destructive-safety contract tests.
- Modify `scripts/chat_ui_vibe_listener.ps1`: parameter parsing and ordered verify → cleanup → start → prove → publish orchestration.
- Modify `scripts/chat_ui_vibe_listener_tests.ps1`: focused behavioral tests for the lifecycle library and CLI plan contract.
- Modify `scripts/chat_ui_vibe_soak.ps1`: adopt the listener-selected effective port and Browser target before any HTTP probe.
- Modify `scripts/chat_ui_vibe_soak_tests.ps1`: lock selected-port propagation and backward-compatible result parsing.
- Read only `main/resources/static/js/chat.js` and `main/resources/templates/chat-ui.html`: source hash and Browser selector authority.

---

### Task 1: Protected Ports and Deterministic Port Planning

**Files:**
- Create: `scripts/chat_ui_vibe_lifecycle.ps1`
- Modify: `scripts/chat_ui_vibe_listener_tests.ps1`

**Interfaces:**
- Produces: `Get-AwxSha256Hex`, `Get-AwxListenerSnapshot`, `Get-AwxProtectedPorts`, `Find-AwxFreeLoopbackPort`, and `Resolve-AwxRuntimePorts`.
- `Resolve-AwxRuntimePorts` returns an object with `ok`, `status`, `serverPort`, `managementPort`, `nettyPort`, `protectedPorts`, `replacementCount`, and `replacementReasons`.

- [ ] **Step 1: Reconfirm the four frozen preimages and baseline test**

Run:

```powershell
$expected = @{
  'scripts\chat_ui_vibe_listener.ps1' = 'ba711232c8dc1d214e49ebd7d3b61ed086477c1bf124768688d5c7088afb40f5'
  'scripts\chat_ui_vibe_listener_tests.ps1' = 'c373e2d27db0d2b39a5a232ad23d75fed8faf4de38c4c4e44c374078b2adb510'
  'scripts\chat_ui_vibe_soak.ps1' = '2a2c124886ec09d24e14687f650081f6d2df4fbe3232b385443d2fcec348d520'
  'scripts\chat_ui_vibe_soak_tests.ps1' = '551549c89e79db9fb38482bde0c7c5de6c7e85995d8997505783d3d38cb033a0'
}
foreach ($path in $expected.Keys) {
  $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant()
  if ($actual -ne $expected[$path]) { throw "preimage-changed path=$path" }
}
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener_tests.ps1
```

Expected: all hashes match and baseline prints `[PASS] chat_ui_vibe_listener_tests`.

- [ ] **Step 2: Write RED tests for protected and occupied ports**

Append tests that dot-source the new library and assert default/additive protection, three distinct automatic ports, explicit protected-port rejection, foreign-listener replacement, and fixed-port failure:

```powershell
$lifecyclePath = Join-Path $PSScriptRoot 'chat_ui_vibe_lifecycle.ps1'
. $lifecyclePath

$extraProtected = 19001
$protected = @(Get-AwxProtectedPorts -AdditionalPorts @($extraProtected))
foreach ($required in @(11434, 11435, 11438, $extraProtected)) {
    if ($protected -notcontains $required) { throw "[FAIL] protected port missing: $required" }
}

$tcp = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
$tcp.Start()
try {
    $foreignPort = [int]$tcp.LocalEndpoint.Port
    $auto = Resolve-AwxRuntimePorts -ServerPort $foreignPort -ManagementPort 0 -NettyPort 0 `
        -ProtectedPorts $protected -ReusableOwnedPorts @() -FixedPorts:$false
    if (-not $auto.ok) { throw "[FAIL] automatic plan should succeed: $($auto.status)" }
    if ($auto.serverPort -eq $foreignPort) { throw '[FAIL] foreign listener was reused' }
    $uniquePorts = @($auto.serverPort,$auto.managementPort,$auto.nettyPort) | Select-Object -Unique
    if (@($uniquePorts).Count -ne 3) {
        throw '[FAIL] launch ports must be distinct'
    }
    $fixed = Resolve-AwxRuntimePorts -ServerPort $foreignPort -ManagementPort 0 -NettyPort 0 `
        -ProtectedPorts $protected -ReusableOwnedPorts @() -FixedPorts
    if ($fixed.status -ne 'foreign-port-owner') { throw "[FAIL] fixed conflict status=$($fixed.status)" }
} finally {
    $tcp.Stop()
}

$blocked = Resolve-AwxRuntimePorts -ServerPort 11435 -ManagementPort 0 -NettyPort 0 `
    -ProtectedPorts $protected -ReusableOwnedPorts @() -FixedPorts:$false
if ($blocked.status -ne 'protected-port-conflict') { throw "[FAIL] protected status=$($blocked.status)" }
```

- [ ] **Step 3: Run the RED test**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener_tests.ps1
```

Expected: FAIL because `scripts/chat_ui_vibe_lifecycle.ps1` or `Get-AwxProtectedPorts` does not exist.

- [ ] **Step 4: Implement minimal port-planning functions**

Create the library with side-effect-free definitions and these exact signatures:

```powershell
Set-StrictMode -Version Latest

function Get-AwxSha256Hex {
    param([byte[]]$Bytes)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes)) -replace '-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-AwxListenerSnapshot {
    param([int[]]$Ports = @())
    $rows = if ($Ports.Count -gt 0) {
        @(Get-NetTCPConnection -State Listen -LocalPort $Ports -ErrorAction SilentlyContinue)
    } else {
        @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue)
    }
    foreach ($row in $rows) {
        $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($row.OwningProcess)" -ErrorAction SilentlyContinue
        [pscustomobject]@{
            port = [int]$row.LocalPort
            processId = [int]$row.OwningProcess
            processName = if ($process) { [string]$process.Name } else { 'unknown' }
            commandHash = if ($process -and $process.CommandLine) {
                Get-AwxSha256Hex -Bytes ([Text.Encoding]::UTF8.GetBytes([string]$process.CommandLine))
            } else { '' }
        }
    }
}

function Get-AwxProtectedPorts {
    param([int[]]$AdditionalPorts = @())
    $ports = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($port in @(11434,11435,11438) + @($AdditionalPorts)) { if ($port -gt 0) { [void]$ports.Add($port) } }
    foreach ($listener in @(Get-AwxListenerSnapshot)) {
        if ($listener.processName -match '^(?i)ollama(?:\.exe)?$') { [void]$ports.Add([int]$listener.port) }
    }
    return @($ports | Sort-Object)
}

function Find-AwxFreeLoopbackPort {
    param([int[]]$ExcludedPorts = @())
    for ($attempt = 0; $attempt -lt 32; $attempt++) {
        $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
        try {
            $probe.Start()
            $candidate = [int]$probe.LocalEndpoint.Port
        } finally {
            $probe.Stop()
        }
        if ($ExcludedPorts -notcontains $candidate) { return $candidate }
    }
    throw 'free-loopback-port-not-found'
}

function Resolve-AwxRuntimePorts {
    param(
        [int]$ServerPort,
        [int]$ManagementPort,
        [int]$NettyPort,
        [int[]]$ProtectedPorts = @(),
        [int[]]$ReusableOwnedPorts = @(),
        [switch]$FixedPorts
    )
    $protected = [System.Collections.Generic.HashSet[int]]::new()
    $occupied = [System.Collections.Generic.HashSet[int]]::new()
    $reusable = [System.Collections.Generic.HashSet[int]]::new()
    $used = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($port in $ProtectedPorts) { if ($port -gt 0) { [void]$protected.Add($port) } }
    foreach ($port in $ReusableOwnedPorts) { if ($port -gt 0) { [void]$reusable.Add($port) } }
    foreach ($row in @(Get-AwxListenerSnapshot)) { [void]$occupied.Add([int]$row.port) }
    $requested = [ordered]@{ server=$ServerPort; management=$ManagementPort; netty=$NettyPort }
    foreach ($entry in $requested.GetEnumerator()) {
        if ([int]$entry.Value -gt 0 -and $protected.Contains([int]$entry.Value)) {
            return [pscustomobject]@{ok=$false;status='protected-port-conflict';serverPort=0;managementPort=0;nettyPort=0;protectedPorts=@($protected | Sort-Object);replacementCount=0;replacementReasons=@("$($entry.Key):protected")}
        }
    }
    $selected = [ordered]@{}
    $reasons = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $requested.GetEnumerator()) {
        $candidate = [int]$entry.Value
        $duplicate = $candidate -gt 0 -and $used.Contains($candidate)
        $foreign = $candidate -gt 0 -and $occupied.Contains($candidate) -and -not $reusable.Contains($candidate)
        if (($duplicate -or $foreign) -and $FixedPorts) {
            $conflictReason = if ($duplicate) { 'duplicate' } else { 'occupied' }
            return [pscustomobject]@{ok=$false;status='foreign-port-owner';serverPort=0;managementPort=0;nettyPort=0;protectedPorts=@($protected | Sort-Object);replacementCount=0;replacementReasons=@("$($entry.Key):$conflictReason")}
        }
        if ($candidate -le 0 -or $duplicate -or $foreign) {
            $excluded = @($protected | ForEach-Object { $_ }) + @($occupied | ForEach-Object { $_ }) + @($used | ForEach-Object { $_ })
            $candidate = Find-AwxFreeLoopbackPort -ExcludedPorts $excluded
            $reason = if ([int]$entry.Value -le 0) { 'automatic' } elseif ($duplicate) { 'duplicate' } else { 'occupied' }
            $reasons.Add("$($entry.Key):$reason") | Out-Null
        }
        [void]$used.Add($candidate)
        $selected[$entry.Key] = $candidate
    }
    return [pscustomobject]@{ok=$true;status='ready-to-restart';serverPort=[int]$selected.server;managementPort=[int]$selected.management;nettyPort=[int]$selected.netty;protectedPorts=@($protected | Sort-Object);replacementCount=$reasons.Count;replacementReasons=$reasons.ToArray()}
}
```

- [ ] **Step 5: Run the focused test and inspect the result schema**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener_tests.ps1
```

Expected: PASS; no process is stopped and the synthetic foreign listener remains bound until the test `finally` block.

- [ ] **Step 6: Commit the independently testable port planner**

Run only after a count-only secret scan reports zero and the staged set is exactly the two paths:

```powershell
git add -- scripts/chat_ui_vibe_lifecycle.ps1 scripts/chat_ui_vibe_listener_tests.ps1
git diff --cached --name-only
git commit -m "feat: plan protected chat runtime ports"
```

Expected staged paths: `scripts/chat_ui_vibe_lifecycle.ps1` and `scripts/chat_ui_vibe_listener_tests.ps1` only.

---

### Task 2: Manifest-Attested Cleanup Without Foreign Process Termination

**Files:**
- Create: `scripts/tests/fixtures/owned_runtime_listener.ps1`
- Modify: `scripts/chat_ui_vibe_lifecycle.ps1`
- Modify: `scripts/chat_ui_vibe_listener_tests.ps1`

**Interfaces:**
- Consumes: `Get-AwxSha256Hex`, `Get-AwxListenerSnapshot`.
- Produces: `Get-AwxCanonicalRootHash`, `Get-AwxProcessIdentity`, `Get-AwxProcessLineage`, `Read-AwxRuntimeManifest`, `Test-AwxOwnedRuntimeIdentity`, `Wait-AwxPortsReleased`, `Stop-AwxOwnedRuntime`, `Write-AwxJsonAtomic`, and `Invoke-AwxVerifiedCleanup`.
- Manifest schema: `awx.chat_ui_owned_runtime.v1` with `rootHash`, `runId`, `runIdHash`, `launcher`, `listener`, `parentLineageHash`, and `ports`.

- [ ] **Step 1: Create the synthetic listener fixture**

Create `scripts/tests/fixtures/owned_runtime_listener.ps1`:

```powershell
param(
    [Parameter(Mandatory = $true)][int]$Port,
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$ReadyPath
)
$ErrorActionPreference = 'Stop'
$listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
$listener.Start()
try {
    Set-Content -LiteralPath $ReadyPath -Value $RunId -Encoding ASCII
    while ($true) { Start-Sleep -Milliseconds 250 }
} finally {
    $listener.Stop()
}
```

- [ ] **Step 2: Write RED tests for exact identity and foreign survival**

The test must start two fixture processes with different run IDs, capture each CIM `CreationDate`, build a manifest for only the owned process, then call cleanup with `powershell.exe` allowed solely for the synthetic test:

```powershell
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('awx-owned-runtime-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
$runId = 'awx-test-' + [guid]::NewGuid().ToString('N')
$ownedPort = Find-AwxFreeLoopbackPort -ExcludedPorts @()
$foreignPort = Find-AwxFreeLoopbackPort -ExcludedPorts @($ownedPort)
$ownedReady = Join-Path $testRoot 'owned.ready'
$foreignReady = Join-Path $testRoot 'foreign.ready'
$fixture = Join-Path $PSScriptRoot 'tests\fixtures\owned_runtime_listener.ps1'
$owned = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
    '-NoProfile','-File',$fixture,'-Port',"$ownedPort",'-RunId',$runId,'-ReadyPath',$ownedReady)
$foreign = Start-Process powershell -PassThru -WindowStyle Hidden -ArgumentList @(
    '-NoProfile','-File',$fixture,'-Port',"$foreignPort",'-RunId','foreign-run','-ReadyPath',$foreignReady)
try {
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline -and (-not (Test-Path $ownedReady) -or -not (Test-Path $foreignReady))) {
        Start-Sleep -Milliseconds 100
    }
    $identity = Get-AwxProcessIdentity -ProcessId $owned.Id
    $manifest = [ordered]@{
        schemaVersion = 'awx.chat_ui_owned_runtime.v1'
        rootHash = Get-AwxCanonicalRootHash -Root (Resolve-Path '.').Path
        runId = $runId
        runIdHash = Get-AwxSha256Hex -Bytes ([Text.Encoding]::UTF8.GetBytes($runId))
        launcher = $identity
        listener = $identity
        parentLineageHash = (Get-AwxProcessLineage -ProcessId $owned.Id).hash
        ports = [ordered]@{ server = $ownedPort; management = 0; netty = 0 }
    }
    foreach ($field in @('runId','rootHash','creationDate','processId')) {
        $bad = $manifest | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        switch ($field) {
            'runId' { $bad.runId = 'wrong-run' }
            'rootHash' { $bad.rootHash = '0' * 64 }
            'creationDate' { $bad.listener.creationDate = '1900-01-01T00:00:00.0000000Z' }
            'processId' { $bad.listener.processId = 4 }
        }
        $denied = Stop-AwxOwnedRuntime -Manifest $bad -Root (Resolve-Path '.').Path `
            -AllowedProcessNames @('powershell.exe') -TimeoutSeconds 2
        if ($denied.status -ne 'owned-runtime-attribution-failed') { throw "[FAIL] mismatch accepted: $field" }
        $owned.Refresh(); if ($owned.HasExited) { throw "[FAIL] mismatch stopped owned fixture: $field" }
    }
    $result = Stop-AwxOwnedRuntime -Manifest $manifest -Root (Resolve-Path '.').Path `
        -AllowedProcessNames @('powershell.exe') -TimeoutSeconds 10
    if (-not $result.ok) { throw "[FAIL] owned cleanup status=$($result.status)" }
    if (-not $owned.HasExited) { $owned.Refresh(); if (-not $owned.HasExited) { throw '[FAIL] owned process survived' } }
    $foreign.Refresh(); if ($foreign.HasExited) { throw '[FAIL] foreign process was stopped' }
} finally {
    Stop-Process -Id $owned.Id,$foreign.Id -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
}
```

The four mismatch checks must run before the valid cleanup. Each returns `owned-runtime-attribution-failed` while both fixture processes remain alive.

- [ ] **Step 3: Run the RED test**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener_tests.ps1
```

Expected: FAIL because manifest identity and cleanup functions are undefined.

- [ ] **Step 4: Implement manifest identity and verified cleanup**

Use these exact public signatures:

```powershell
function Get-AwxCanonicalRootHash { param([string]$Root) }
function Get-AwxProcessIdentity { param([int]$ProcessId) }
function Get-AwxProcessLineage { param([int]$ProcessId) }
function Read-AwxRuntimeManifest { param([string]$Path) }
function Test-AwxOwnedRuntimeIdentity {
    param([object]$Manifest,[string]$Root,[string[]]$AllowedProcessNames=@('java.exe','java'))
}
function Wait-AwxPortsReleased { param([int[]]$Ports,[int]$TimeoutSeconds=15) }
function Stop-AwxOwnedRuntime {
    param([object]$Manifest,[string]$Root,[string[]]$AllowedProcessNames=@('java.exe','java'),[int]$TimeoutSeconds=15)
}
function Write-AwxJsonAtomic { param([string]$Path,[object]$Data) }
function Invoke-AwxVerifiedCleanup {
    param([int]$VerificationExitCode,[object]$Manifest,[string]$Root,[string[]]$AllowedProcessNames=@('java.exe','java'))
}
```

`Get-AwxProcessIdentity` returns exactly `processId`, `parentProcessId`, `processName`, `creationDate`, and `commandHash`; the raw command line stays internal to validation and is never returned. `Get-AwxProcessLineage` returns `rows` containing PID/creation tuples plus `hash`. `Test-AwxOwnedRuntimeIdentity` must read the live CIM row and listener table, compare the exact `creationDate`, require the command line to contain the manifest `runId`, require current root hash and `parentLineageHash` equality, and verify that the manifest server port is owned by the listener PID. It returns redacted evidence, not the command line. `Stop-AwxOwnedRuntime` snapshots the validated descendant PID set, stops descendants before ancestors using `Stop-Process`, waits for exit, then calls `Wait-AwxPortsReleased`. `Invoke-AwxVerifiedCleanup` returns `verification-failed` without calling `Stop-AwxOwnedRuntime` when `VerificationExitCode` is nonzero; when `Manifest` is null it returns `ok=true`, `status=no-prior-runtime`, and performs no process mutation.

`Write-AwxJsonAtomic` must create a same-directory temporary file and write UTF-8 JSON. If the target exists, replace it with `[System.IO.File]::Replace($temp,$target,$null)`; otherwise use one same-volume `Move-Item`. Delete the temporary file in `finally` when publication fails.

- [ ] **Step 5: Add and pass the verification-failure preservation test**

With the owned fixture still running, execute:

```powershell
$preserved = Invoke-AwxVerifiedCleanup -VerificationExitCode 1 -Manifest $manifest `
    -Root (Resolve-Path '.').Path -AllowedProcessNames @('powershell.exe')
if ($preserved.status -ne 'verification-failed') { throw "[FAIL] status=$($preserved.status)" }
$owned.Refresh(); if ($owned.HasExited) { throw '[FAIL] failed verification stopped the runtime' }
```

Run the full listener contract test again. Expected: PASS and every synthetic process is removed by its `finally` block.

- [ ] **Step 6: Commit the owned-cleanup unit**

```powershell
git add -- scripts/chat_ui_vibe_lifecycle.ps1 scripts/chat_ui_vibe_listener_tests.ps1 scripts/tests/fixtures/owned_runtime_listener.ps1
git diff --cached --name-only
git commit -m "feat: attest owned chat runtime cleanup"
```

Expected: exactly three staged paths and count-only secret hits `0`.

---

### Task 3: Verification-First Restart and Fresh Runtime Provenance

**Files:**
- Modify: `scripts/chat_ui_vibe_listener.ps1:1-228`
- Modify: `scripts/chat_ui_vibe_lifecycle.ps1`
- Modify: `scripts/chat_ui_vibe_listener_tests.ps1`

**Interfaces:**
- Consumes: port plan and cleanup functions from Tasks 1–2.
- Produces: `Invoke-AwxGradleVerification`, `Test-AwxFreshRuntimeProvenance`, listener result fields `selectedPorts`, `protectedPorts`, `runIdHash`, `browserTargetUrl`, `sourceAssetHash`, `servedAssetHash`, and `statePath`.

- [ ] **Step 1: Write RED CLI contract tests**

Add source-order assertions and a dynamic plan-only invocation:

```powershell
$source = Get-Content -Raw -Encoding utf8 $scriptPath
$verifyIndex = $source.IndexOf('Invoke-AwxGradleVerification')
$cleanupIndex = $source.IndexOf('Invoke-AwxVerifiedCleanup')
$startIndex = $source.IndexOf('Start-ChatUiListener')
if ($verifyIndex -lt 0 -or $cleanupIndex -le $verifyIndex -or $startIndex -le $cleanupIndex) {
    throw '[FAIL] lifecycle order must be verify -> cleanup -> start'
}

$planOut = Join-Path $testRoot 'plan'
$planJson = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
    -Port 0 -ManagementPort 0 -NettyPort 0 -ProtectedPorts 19001 -PlanOnly `
    -StatePath (Join-Path $testRoot 'state.json') -OutDir $planOut | Select-Object -Last 1 | ConvertFrom-Json
if (-not $planJson.ok -or $planJson.status -ne 'ready-to-restart') { throw "[FAIL] plan status=$($planJson.status)" }
$selected = @($planJson.selectedPorts.server,$planJson.selectedPorts.management,$planJson.selectedPorts.netty)
if (@($selected | Select-Object -Unique).Count -ne 3) { throw '[FAIL] plan ports not distinct' }
if (@($selected | Where-Object { $planJson.protectedPorts -contains $_ }).Count -ne 0) {
    throw '[FAIL] plan selected a protected port'
}
```

Assert the source contains `--management.server.port=`, `--netty.port=`, and `--awx.runtime.run-id=` and does not contain `taskkill.exe` or a persisted `commandLine` field.

Keep `[switch]$CloseConflictingListener` in the parameter block so existing soak callers continue to parse. Treat it only as a compatibility signal; all cleanup still requires the manifest identity gates and it never widens the allowed process set.

- [ ] **Step 2: Run the RED test**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener_tests.ps1
```

Expected: FAIL because the listener has not dot-sourced the lifecycle library or implemented the new parameters/order.

- [ ] **Step 3: Add verification and provenance functions**

Implement these exact signatures in the library:

```powershell
function Invoke-AwxGradleVerification {
    param([string]$Root,[string]$GradlePath,[string[]]$Tasks,[string]$ProjectCacheDir,[string]$OutLog,[string]$ErrLog)
}
function Test-AwxFreshRuntimeProvenance {
    param([string]$Root,[int]$LauncherPid,[datetime]$LaunchBoundaryUtc,[string]$RunId,[int]$ServerPort,[string]$SourceAssetPath)
}
```

`Invoke-AwxGradleVerification` starts the wrapper hidden, waits, and returns its exit code plus relative log paths. `Test-AwxFreshRuntimeProvenance` requires: server listener exists; listener descends from launcher; listener creation is after `LaunchBoundaryUtc`; listener command contains `RunId`; `/chat-ui` returns exactly 200; `/js/chat.js` downloads; and the downloaded bytes hash equals `Get-FileHash` for `main/resources/static/js/chat.js`.

- [ ] **Step 4: Refactor the listener into the approved ordered workflow**

At the top, dot-source the library and add parameters:

```powershell
[int[]]$ProtectedPorts = @(),
[switch]$FixedPorts,
[switch]$SkipVerification,
[string[]]$VerificationTasks = @('checkLangchain4jVersionPurity','checkSourceSetHygiene','compileJava'),
[string]$StatePath = 'var\codex-runtime\chat-ui-current.json'
```

Replace direct conflict killing with this order:

```powershell
$protected = @(Get-AwxProtectedPorts -AdditionalPorts $ProtectedPorts)
$prior = Read-AwxRuntimeManifest -Path $StatePath
$ownedIdentity = if ($prior) { Test-AwxOwnedRuntimeIdentity -Manifest $prior -Root $root } else { $null }
if ($prior -and -not $ownedIdentity.ok) {
    Write-Json @{schemaVersion='awx.chat_ui_vibe_listener.v1';ok=$false;status='owned-runtime-attribution-failed';mutationAllowed=$false}
    exit 3
}
$reusable = if ($ownedIdentity -and $ownedIdentity.ok) { @($prior.ports.server,$prior.ports.management,$prior.ports.netty) } else { @() }
$portPlan = Resolve-AwxRuntimePorts -ServerPort $Port -ManagementPort $ManagementPort -NettyPort $NettyPort `
    -ProtectedPorts $protected -ReusableOwnedPorts $reusable -FixedPorts:$FixedPorts
if (-not $portPlan.ok -or $PlanOnly) {
    Write-Json @{
        schemaVersion='awx.chat_ui_vibe_listener.v1'; ok=[bool]$portPlan.ok; status=[string]$portPlan.status
        mutationAllowed=$false; selectedPorts=@{server=$portPlan.serverPort;management=$portPlan.managementPort;netty=$portPlan.nettyPort}
        protectedPorts=$protected; replacementCount=[int]$portPlan.replacementCount
    }
    if ($portPlan.ok) { exit 0 } else { exit 2 }
}

$verifyOut = Join-Path $OutDir 'chat-ui-vibe-verification.out.log'
$verifyErr = Join-Path $OutDir 'chat-ui-vibe-verification.err.log'
$verification = if ($SkipVerification) { [pscustomobject]@{ exitCode=0; status='skipped-explicitly' } } else {
    Invoke-AwxGradleVerification -Root $root -GradlePath (Join-Path $root 'gradlew.bat') `
        -Tasks $VerificationTasks -ProjectCacheDir $ProjectCacheDir -OutLog $verifyOut -ErrLog $verifyErr
}
$cleanup = Invoke-AwxVerifiedCleanup -VerificationExitCode $verification.exitCode -Manifest $prior -Root $root
if (-not $cleanup.ok) {
    Write-Json @{schemaVersion='awx.chat_ui_vibe_listener.v1';ok=$false;status=[string]$cleanup.status;mutationAllowed=$false}
    exit 3
}
```

Generate `$runId = 'awx-chat-runtime-' + [guid]::NewGuid().ToString('N')`. Change `Start-ChatUiListener` to the exact signature `param([int]$ServerPort,[int]$ManagementPort,[int]$NettyPort,[string]$RunId,[string]$CacheDir,[string]$LogDir,[int]$TimeoutSeconds)` and start hidden `bootRun` with all three selected ports plus `--awx.runtime.run-id=$runId`. After HTTP readiness, call `Test-AwxFreshRuntimeProvenance`; on failure stop only the just-launched validated tree and emit `fresh-runtime-provenance-failed`.

On success, atomically write `awx.chat_ui_owned_runtime.v1` state and preserve result schema `awx.chat_ui_vibe_listener.v1` while adding:

```powershell
$listenerIdentity = Get-AwxProcessIdentity -ProcessId $provenance.listenerPid
$lineage = Get-AwxProcessLineage -ProcessId $provenance.listenerPid
$state = [ordered]@{
    schemaVersion = 'awx.chat_ui_owned_runtime.v1'
    rootHash = Get-AwxCanonicalRootHash -Root $root
    runId = $runId
    runIdHash = Get-AwxSha256Hex -Bytes ([Text.Encoding]::UTF8.GetBytes($runId))
    launcher = Get-AwxProcessIdentity -ProcessId $started.processId
    listener = $listenerIdentity
    parentLineageHash = $lineage.hash
    ports = [ordered]@{server=$portPlan.serverPort;management=$portPlan.managementPort;netty=$portPlan.nettyPort}
    sourceAssetHash = $provenance.sourceAssetHash
    servedAssetHash = $provenance.servedAssetHash
    startedAt = $provenance.startedAt
    readyAt = (Get-Date).ToUniversalTime().ToString('o')
}
Write-AwxJsonAtomic -Path $StatePath -Data $state

selectedPorts = @{ server=$portPlan.serverPort; management=$portPlan.managementPort; netty=$portPlan.nettyPort }
protectedPorts = $protected
runIdHash = Get-AwxSha256Hex -Bytes ([Text.Encoding]::UTF8.GetBytes($runId))
browserTargetUrl = "http://127.0.0.1:$($portPlan.serverPort)/chat-ui?awxRuntime=$runId"
sourceAssetHash = $provenance.sourceAssetHash
servedAssetHash = $provenance.servedAssetHash
statePath = $StatePath
```

- [ ] **Step 5: Run focused GREEN tests and plan-only smoke**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener.ps1 `
  -Port 0 -ManagementPort 0 -NettyPort 0 -PlanOnly -OutDir var\codex-smoke\owned-runtime-plan
```

Expected: tests PASS; plan status `ready-to-restart`; three distinct selected ports; protected list includes `11434`, `11435`, `11438`; no process is stopped or started.

- [ ] **Step 6: Commit the CLI restart unit**

```powershell
git add -- scripts/chat_ui_vibe_lifecycle.ps1 scripts/chat_ui_vibe_listener.ps1 scripts/chat_ui_vibe_listener_tests.ps1
git diff --cached --name-only
git commit -m "feat: restart only owned chat runtime"
```

Expected: exactly three staged paths and count-only secret hits `0`.

---

### Task 4: Downstream Effective-Port Handoff

**Files:**
- Modify: `scripts/chat_ui_vibe_soak.ps1:927-997,1768-1773`
- Modify: `scripts/chat_ui_vibe_soak_tests.ps1:270-310`

**Interfaces:**
- Consumes: listener result `parsed.selectedPorts.server` and `parsed.browserTargetUrl`.
- Produces: `$Port` rebound to the proven effective port before `Test-ChatUiContract`, `Invoke-StreamProbe`, usage snapshots, replay commands, progress, and final summary.

- [ ] **Step 1: Write RED source-contract tests for selected-port adoption**

Add exact assertions:

```powershell
Assert-Contains 'soak reads selected server port' $source '[int]$listener.parsed.selectedPorts.server'
Assert-Contains 'soak rejects missing selected server port' $source 'listener-selected-port-missing'
Assert-Contains 'soak adopts selected server port before UI contract' $source '$Port = $selectedServerPort'
Assert-Contains 'soak retains browser target hash' $source 'browserTargetUrlHash'
$adoptIndex = $source.IndexOf('$Port = $selectedServerPort')
$probeIndex = $source.IndexOf('$uiContract = Test-ChatUiContract')
if ($adoptIndex -lt 0 -or $probeIndex -le $adoptIndex) { throw '[FAIL] selected port must precede probes' }
```

- [ ] **Step 2: Run the RED soak contract test**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak_tests.ps1
```

Expected: FAIL because selected-port adoption is absent.

- [ ] **Step 3: Adopt the listener-selected port before every live probe**

Immediately after line 1768, add:

```powershell
$listener = Invoke-ListenerReady -RunDir $runDir
if (-not $listener.skipped) {
    $selectedServerPort = 0
    try { $selectedServerPort = [int]$listener.parsed.selectedPorts.server } catch { $selectedServerPort = 0 }
    if ($selectedServerPort -le 0) { throw 'listener-selected-port-missing' }
    $Port = $selectedServerPort
    $longRunCommand = New-LongRunCommand -Port $Port -OutDir $OutDir -RecentWindowSize $RecentWindowSize
}
$browserTargetUrl = ''
try { $browserTargetUrl = [string]$listener.parsed.browserTargetUrl } catch { $browserTargetUrl = '' }
$browserTargetUrlHash = if ([string]::IsNullOrWhiteSpace($browserTargetUrl)) { '' } else { Get-Hash12 $browserTargetUrl }
```

Add `browserTargetUrlHash` to listener/progress/final evidence without storing credentials or remote URLs. Keep dry-run and `-SkipListenerStart` behavior unchanged.

- [ ] **Step 4: Run listener and soak contract suites**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak_tests.ps1
```

Expected: both print PASS.

- [ ] **Step 5: Review and commit the downstream handoff**

Because both files were pre-existing untracked files, inspect their full path-specific staged diff and count-only secret scan before commit:

```powershell
git add -- scripts/chat_ui_vibe_soak.ps1 scripts/chat_ui_vibe_soak_tests.ps1
git diff --cached --name-only
git diff --cached --stat
git commit -m "feat: follow fresh chat runtime port"
```

Expected: exactly the two soak paths are staged; do not proceed if any other path appears.

---

### Task 5: Two-Restart Runtime Proof and In-App Browser Verification

**Files:**
- Verify: `scripts/chat_ui_vibe_lifecycle.ps1`
- Verify: `scripts/chat_ui_vibe_listener.ps1`
- Verify: `scripts/chat_ui_vibe_listener_tests.ps1`
- Verify: `scripts/chat_ui_vibe_soak.ps1`
- Verify: `scripts/chat_ui_vibe_soak_tests.ps1`
- Read only: `main/resources/static/js/chat.js`
- Runtime artifacts: `var/codex-runtime/` and `var/codex-smoke/owned-runtime-browser-restart/`

**Interfaces:**
- Consumes: `browserTargetUrl`, owned-runtime manifest, selected/protected port sets, process identities, source/served asset hashes.
- Produces: current command evidence for protected listener survival, previous owned runtime cleanup, replacement runtime provenance, and Browser-visible `/chat-ui` state.

- [ ] **Step 1: Run focused static and synthetic suites**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak_tests.ps1
```

Expected: both PASS with all synthetic child processes cleaned up.

- [ ] **Step 2: Freeze protected Ollama listener identities without raw command lines**

```powershell
$before = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object LocalPort -in 11434,11435,11438 | Select-Object LocalPort,OwningProcess)
$before | Sort-Object LocalPort | ConvertTo-Json -Compress
```

Expected: current rows are retained for after-check comparison; no command line or environment value is printed.

- [ ] **Step 3: Run the first verified restart**

```powershell
$out = 'var\codex-smoke\owned-runtime-browser-restart\first'
$statePath = 'var\codex-runtime\owned-runtime-browser-restart-current.json'
$firstLine = powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener.ps1 `
  -Port 0 -ManagementPort 0 -NettyPort 0 -StatePath $statePath `
  -OutDir $out -ReadyTimeoutSeconds 180 | Select-Object -Last 1
$first = $firstLine | ConvertFrom-Json
if (-not $first.ok -or $first.status -ne 'listener-ready') { throw "first restart failed: $($first.status)" }
```

Expected: verification succeeds; selected ports exclude protected ports; source and served asset hashes match; one state manifest is published.

- [ ] **Step 4: Run the second verified restart and prove cleanup**

Capture the first manifest PID/ports, then invoke the same command into `var\codex-smoke\owned-runtime-browser-restart\second`. Assert the first listener PID no longer exists, its owned ports have been released or reassigned only to the second attested listener, the second run ID hash differs, and the second result is `listener-ready`.

Use exact checks:

```powershell
$firstState = Get-Content -Raw -Encoding utf8 $first.statePath | ConvertFrom-Json
$firstPid = [int]$firstState.listener.processId
$firstPorts = @($firstState.ports.server,$firstState.ports.management,$firstState.ports.netty) | Where-Object { $_ -gt 0 }
$secondLine = powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_listener.ps1 `
  -Port 0 -ManagementPort 0 -NettyPort 0 `
  -StatePath $statePath `
  -OutDir 'var\codex-smoke\owned-runtime-browser-restart\second' -ReadyTimeoutSeconds 180 | Select-Object -Last 1
$second = $secondLine | ConvertFrom-Json
if (-not $second.ok) { throw "second restart failed: $($second.status)" }
if (Get-Process -Id $firstPid -ErrorAction SilentlyContinue) { throw 'prior owned listener still running' }
if ($first.runIdHash -eq $second.runIdHash) { throw 'replacement run id was not fresh' }
```

- [ ] **Step 5: Prove Ollama and foreign-process protection after both restarts**

Re-read only ports `11434`, `11435`, `11438` and compare port/PID pairs with `$before`. Expected: identical pairs. Also run the dynamic foreign-listener test once more and confirm its process/listener remains alive until its own `finally` cleanup.

- [ ] **Step 6: Open the exact second `browserTargetUrl` in the in-app Browser**

Use the Browser plugin, not Computer Use or standalone Playwright, to navigate exactly to `$second.browserTargetUrl`. Verify these visible/DOM conditions:

```text
HTTP document loaded from 127.0.0.1 on second.selectedPorts.server
[data-testid="chat-composer"] exists
[data-testid="chat-message-input"] is visible and enabled
[data-testid="chat-send-button"] is visible
[data-testid="chat-trace-status"] contains ready or a live post-load status
no page-level error document
console warning/error count = 0
```

Record only the target host/port, selector booleans, status text category, console warning/error counts, screenshot byte count or hash, and result run-ID hash. Do not store raw page text, cookies, local storage, prompts, or responses.

- [ ] **Step 7: Use Computer Use only if Browser cannot inspect required Windows UI state**

If the in-app Browser succeeds, record `computerUse=not_required`. If Browser control cannot prove the actual target window, initialize Computer Use, read its guidance/confirmation documentation, and collect count-only window evidence without raw titles. Computer evidence remains supporting and cannot replace Browser DOM proof.

- [ ] **Step 8: Run final Gradle and secret gates**

```powershell
$env:AWX_AGENT_HOST = 'desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop-owned-runtime-browser-restart'
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle-awx-desktop'
$pcd = Join-Path $env:USERPROFILE '.awx-gradle-project-cache\desktop-owned-runtime-browser-restart'
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava `
  --no-daemon --max-workers=1 --project-cache-dir $pcd
```

Run a count-only secret-pattern scan over the five implementation/test paths and fixture. Expected: Gradle tasks PASS and secret hits `0`.

- [ ] **Step 9: Audit the final changed-path set and commit any remaining intended files**

```powershell
git status --short -- `
  scripts/chat_ui_vibe_lifecycle.ps1 `
  scripts/chat_ui_vibe_listener.ps1 `
  scripts/chat_ui_vibe_listener_tests.ps1 `
  scripts/chat_ui_vibe_soak.ps1 `
  scripts/chat_ui_vibe_soak_tests.ps1 `
  scripts/tests/fixtures/owned_runtime_listener.ps1
git diff --stat HEAD -- `
  scripts/chat_ui_vibe_lifecycle.ps1 `
  scripts/chat_ui_vibe_listener.ps1 `
  scripts/chat_ui_vibe_listener_tests.ps1 `
  scripts/chat_ui_vibe_soak.ps1 `
  scripts/chat_ui_vibe_soak_tests.ps1 `
  scripts/tests/fixtures/owned_runtime_listener.ps1
```

Expected: only the six declared implementation/test paths differ from the implementation base; Java/resources, Supabase, unrelated dirty paths, and PatchDrop remain untouched. Commit only an exact remaining declared pathspec if prior task commits did not already contain it.

---

## Completion Audit

Before claiming completion, map every design criterion to current evidence:

| Requirement | Required proof |
| --- | --- |
| Ollama and required ports preserved | Before/after protected port/PID pairs unchanged |
| Foreign process preserved | Synthetic foreign listener remains alive after attempted restart/cleanup |
| Owned prior runtime cleaned | First PID absent; port ownership absent or transferred only to second attested runtime |
| Verification precedes cleanup | Behavioral failed-verification preservation test plus source-order contract |
| New server is fresh | Different run-ID hash, post-launch process creation, listener lineage, HTTP 200 |
| Served code is current | `sourceAssetHash == servedAssetHash` for `chat.js` |
| Browser uses replacement server | Browser target port equals second selected server port and required DOM selectors pass |
| Privacy maintained | Raw command/env/page/prompt/response storage absent; count-only secret hits zero |
| Scope preserved | Exact changed-path audit; no Java/resources/Supabase/PatchDrop mutation |

Do not mark the goal complete if any row is missing, indirect, stale, or contradicted.
