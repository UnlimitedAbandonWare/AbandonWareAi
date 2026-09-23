Set-StrictMode -Version Latest

function Get-AwxSha256Hex {
    param([byte[]]$Bytes)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        return ([System.BitConverter]::ToString($sha.ComputeHash($Bytes)) -replace "-", "").ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
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
            processName = if ($process) { [string]$process.Name } else { "unknown" }
            commandHash = if ($process -and $process.CommandLine) {
                Get-AwxSha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes([string]$process.CommandLine))
            } else {
                ""
            }
        }
    }
}

function Get-AwxProtectedPorts {
    param([int[]]$AdditionalPorts = @())

    $ports = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($port in @(11434, 11435, 11438) + @($AdditionalPorts)) {
        if ($port -gt 0) {
            [void]$ports.Add($port)
        }
    }
    foreach ($listener in @(Get-AwxListenerSnapshot)) {
        if ($listener.processName -match "(?i)^ollama(?:\.exe)?$") {
            [void]$ports.Add([int]$listener.port)
        }
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
        if ($ExcludedPorts -notcontains $candidate) {
            return $candidate
        }
    }
    throw "free-loopback-port-not-found"
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
    foreach ($port in $ProtectedPorts) {
        if ($port -gt 0) {
            [void]$protected.Add($port)
        }
    }
    foreach ($port in $ReusableOwnedPorts) {
        if ($port -gt 0) {
            [void]$reusable.Add($port)
        }
    }
    foreach ($row in @(Get-AwxListenerSnapshot)) {
        [void]$occupied.Add([int]$row.port)
    }

    $requested = [ordered]@{
        server = $ServerPort
        management = $ManagementPort
        netty = $NettyPort
    }
    foreach ($entry in $requested.GetEnumerator()) {
        if ([int]$entry.Value -gt 0 -and $protected.Contains([int]$entry.Value)) {
            return [pscustomobject]@{
                ok = $false
                status = "protected-port-conflict"
                serverPort = 0
                managementPort = 0
                nettyPort = 0
                protectedPorts = @($protected | Sort-Object)
                replacementCount = 0
                replacementReasons = @("$($entry.Key):protected")
            }
        }
    }

    $selected = [ordered]@{}
    $reasons = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $requested.GetEnumerator()) {
        $candidate = [int]$entry.Value
        $duplicate = $candidate -gt 0 -and $used.Contains($candidate)
        $foreign = $candidate -gt 0 -and $occupied.Contains($candidate) -and -not $reusable.Contains($candidate)
        if (($duplicate -or $foreign) -and $FixedPorts) {
            $conflictReason = if ($duplicate) { "duplicate" } else { "occupied" }
            return [pscustomobject]@{
                ok = $false
                status = "foreign-port-owner"
                serverPort = 0
                managementPort = 0
                nettyPort = 0
                protectedPorts = @($protected | Sort-Object)
                replacementCount = 0
                replacementReasons = @("$($entry.Key):$conflictReason")
            }
        }
        if ($candidate -le 0 -or $duplicate -or $foreign) {
            $excluded = @($protected | ForEach-Object { $_ }) +
                @($occupied | ForEach-Object { $_ }) +
                @($used | ForEach-Object { $_ })
            $candidate = Find-AwxFreeLoopbackPort -ExcludedPorts $excluded
            $reason = if ([int]$entry.Value -le 0) {
                "automatic"
            } elseif ($duplicate) {
                "duplicate"
            } else {
                "occupied"
            }
            $reasons.Add("$($entry.Key):$reason") | Out-Null
        }
        [void]$used.Add($candidate)
        $selected[$entry.Key] = $candidate
    }

    return [pscustomobject]@{
        ok = $true
        status = "ready-to-restart"
        serverPort = [int]$selected.server
        managementPort = [int]$selected.management
        nettyPort = [int]$selected.netty
        protectedPorts = @($protected | Sort-Object)
        replacementCount = $reasons.Count
        replacementReasons = $reasons.ToArray()
    }
}

function Get-AwxObjectProperty {
    param(
        [object]$Object,
        [string]$Name
    )

    if ($null -eq $Object) {
        return $null
    }
    if ($Object -is [System.Collections.IDictionary]) {
        if ($Object.Contains($Name)) {
            return $Object[$Name]
        }
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function ConvertTo-AwxCleanupEvidence {
    param([object]$Cleanup)

    return [pscustomobject][ordered]@{
        status = [string](Get-AwxObjectProperty -Object $Cleanup -Name "status")
        stopAttemptCount = [int](Get-AwxObjectProperty -Object $Cleanup -Name "stopAttemptCount")
        alreadyExitedCount = [int](Get-AwxObjectProperty -Object $Cleanup -Name "alreadyExitedCount")
        skippedDescendantCount = [int](Get-AwxObjectProperty -Object $Cleanup -Name "skippedDescendantCount")
        stoppedProcessCount = [int](Get-AwxObjectProperty -Object $Cleanup -Name "stoppedProcessCount")
        remainingProcessCount = [int](Get-AwxObjectProperty -Object $Cleanup -Name "remainingProcessCount")
        portCount = [int](Get-AwxObjectProperty -Object $Cleanup -Name "portCount")
    }
}

function ConvertTo-AwxUtcIso8601 {
    param([object]$Value)

    if ($null -eq $Value) {
        return ""
    }
    if ($Value -is [datetime]) {
        return ([datetime]$Value).ToUniversalTime().ToString("o")
    }
    $parsed = [datetimeoffset]::MinValue
    $styles = [System.Globalization.DateTimeStyles]::AssumeUniversal -bor
        [System.Globalization.DateTimeStyles]::AdjustToUniversal
    if ([datetimeoffset]::TryParse(
        [string]$Value,
        [System.Globalization.CultureInfo]::InvariantCulture,
        $styles,
        [ref]$parsed)) {
        return $parsed.ToUniversalTime().ToString("o")
    }
    try {
        return ([System.Management.ManagementDateTimeConverter]::ToDateTime([string]$Value)).ToUniversalTime().ToString("o")
    } catch {
        return ""
    }
}

function Test-AwxSameCreationDate {
    param(
        [object]$Left,
        [object]$Right
    )

    $leftCanonical = ConvertTo-AwxUtcIso8601 -Value $Left
    $rightCanonical = ConvertTo-AwxUtcIso8601 -Value $Right
    if ([string]::IsNullOrWhiteSpace($leftCanonical) -or
        [string]::IsNullOrWhiteSpace($rightCanonical)) {
        return $false
    }

    $leftInstant = [datetimeoffset]::MinValue
    $rightInstant = [datetimeoffset]::MinValue
    $styles = [System.Globalization.DateTimeStyles]::AssumeUniversal -bor
        [System.Globalization.DateTimeStyles]::AdjustToUniversal
    if (-not [datetimeoffset]::TryParse(
            $leftCanonical,
            [System.Globalization.CultureInfo]::InvariantCulture,
            $styles,
            [ref]$leftInstant) -or
        -not [datetimeoffset]::TryParse(
            $rightCanonical,
            [System.Globalization.CultureInfo]::InvariantCulture,
            $styles,
            [ref]$rightInstant)) {
        return $false
    }
    return $leftInstant.UtcTicks -eq $rightInstant.UtcTicks
}

function Get-AwxCanonicalRootHash {
    param([string]$Root)

    $resolved = (Resolve-Path -LiteralPath $Root -ErrorAction Stop).Path
    $normalized = $resolved.Trim().TrimEnd("\", "/").ToLowerInvariant()
    return Get-AwxSha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes($normalized))
}

function Get-AwxProcessIdentity {
    param([int]$ProcessId)

    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return $null
    }
    $commandLine = [string]$process.CommandLine
    return [pscustomobject][ordered]@{
        processId = [int]$process.ProcessId
        parentProcessId = [int]$process.ParentProcessId
        processName = [string]$process.Name
        creationDate = ConvertTo-AwxUtcIso8601 -Value $process.CreationDate
        commandHash = if ([string]::IsNullOrWhiteSpace($commandLine)) {
            ""
        } else {
            Get-AwxSha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes($commandLine))
        }
    }
}

function Get-AwxProcessLineage {
    param(
        [int]$ProcessId,
        [int]$StopProcessId = 0
    )

    $rows = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[int]]::new()
    $currentId = $ProcessId
    for ($depth = 0; $depth -lt 64 -and $currentId -gt 0; $depth++) {
        if (-not $seen.Add($currentId)) {
            break
        }
        $identity = Get-AwxProcessIdentity -ProcessId $currentId
        if ($null -eq $identity) {
            break
        }
        $rows.Add([pscustomobject][ordered]@{
            processId = [int]$identity.processId
            parentProcessId = [int]$identity.parentProcessId
            processName = [string]$identity.processName
            creationDate = [string]$identity.creationDate
        }) | Out-Null
        if ($StopProcessId -gt 0 -and [int]$identity.processId -eq $StopProcessId) {
            break
        }
        $currentId = [int]$identity.parentProcessId
    }
    $lineageRows = $rows.ToArray()
    $lineageJson = $lineageRows | ConvertTo-Json -Depth 4 -Compress
    return [pscustomobject][ordered]@{
        rows = $lineageRows
        hash = Get-AwxSha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes([string]$lineageJson))
    }
}

function Read-AwxRuntimeManifest {
    param([string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    try {
        return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return [pscustomobject][ordered]@{
            schemaVersion = "invalid"
            parseStatus = "manifest-invalid"
        }
    }
}

function Test-AwxOwnedRuntimeIdentity {
    param(
        [object]$Manifest,
        [string]$Root,
        [string[]]$AllowedProcessNames = @("java.exe", "java")
    )

    $denied = {
        param([string]$Reason)
        return [pscustomobject][ordered]@{
            ok = $false
            status = "owned-runtime-attribution-failed"
            reason = $Reason
            listenerPid = 0
            launcherPid = 0
            portCount = 0
        }
    }
    if ($null -eq $Manifest) {
        return & $denied "manifest-missing"
    }
    if ([string](Get-AwxObjectProperty -Object $Manifest -Name "schemaVersion") -cne "awx.chat_ui_owned_runtime.v1") {
        return & $denied "schema-mismatch"
    }

    $rootHash = [string](Get-AwxObjectProperty -Object $Manifest -Name "rootHash")
    if ([string]::IsNullOrWhiteSpace($rootHash) -or $rootHash -cne (Get-AwxCanonicalRootHash -Root $Root)) {
        return & $denied "root-mismatch"
    }
    $runId = [string](Get-AwxObjectProperty -Object $Manifest -Name "runId")
    $runIdHash = [string](Get-AwxObjectProperty -Object $Manifest -Name "runIdHash")
    if ([string]::IsNullOrWhiteSpace($runId) -or [string]::IsNullOrWhiteSpace($runIdHash)) {
        return & $denied "run-id-missing"
    }
    $computedRunIdHash = Get-AwxSha256Hex -Bytes ([System.Text.Encoding]::UTF8.GetBytes($runId))
    if ($runIdHash -cne $computedRunIdHash) {
        return & $denied "run-id-hash-mismatch"
    }

    $manifestLauncher = Get-AwxObjectProperty -Object $Manifest -Name "launcher"
    $manifestListener = Get-AwxObjectProperty -Object $Manifest -Name "listener"
    if ($null -eq $manifestLauncher -or $null -eq $manifestListener) {
        return & $denied "process-identity-missing"
    }
    $launcherPid = [int](Get-AwxObjectProperty -Object $manifestLauncher -Name "processId")
    $listenerPid = [int](Get-AwxObjectProperty -Object $manifestListener -Name "processId")
    $liveLauncher = Get-AwxProcessIdentity -ProcessId $launcherPid
    $liveListener = Get-AwxProcessIdentity -ProcessId $listenerPid
    if ($null -eq $liveLauncher -or $null -eq $liveListener) {
        return & $denied "process-missing"
    }

    $allowed = @($AllowedProcessNames | ForEach-Object { ([string]$_).ToLowerInvariant() })
    foreach ($pair in @(
        [pscustomobject]@{ expected = $manifestLauncher; live = $liveLauncher; role = "launcher" },
        [pscustomobject]@{ expected = $manifestListener; live = $liveListener; role = "listener" }
    )) {
        $expectedPid = [int](Get-AwxObjectProperty -Object $pair.expected -Name "processId")
        $expectedCreation = Get-AwxObjectProperty -Object $pair.expected -Name "creationDate"
        $expectedName = [string](Get-AwxObjectProperty -Object $pair.expected -Name "processName")
        $expectedCommandHash = [string](Get-AwxObjectProperty -Object $pair.expected -Name "commandHash")
        if ($expectedPid -ne [int]$pair.live.processId -or
            -not (Test-AwxSameCreationDate -Left $expectedCreation -Right $pair.live.creationDate) -or
            $expectedName -cne [string]$pair.live.processName -or
            $expectedCommandHash -cne [string]$pair.live.commandHash) {
            return & $denied "$($pair.role)-identity-mismatch"
        }
        if ($pair.role -eq "listener" -and
            $allowed -notcontains ([string]$pair.live.processName).ToLowerInvariant()) {
            return & $denied "$($pair.role)-process-name-denied"
        }
        $liveProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$expectedPid" -ErrorAction SilentlyContinue
        if ($null -eq $liveProcess -or
            [string]::IsNullOrWhiteSpace([string]$liveProcess.CommandLine) -or
            ([string]$liveProcess.CommandLine).IndexOf($runId, [System.StringComparison]::Ordinal) -lt 0) {
            return & $denied "$($pair.role)-run-id-not-observed"
        }
    }

    $lineage = Get-AwxProcessLineage -ProcessId $listenerPid -StopProcessId $launcherPid
    $manifestLineageHash = [string](Get-AwxObjectProperty -Object $Manifest -Name "parentLineageHash")
    if ([string]::IsNullOrWhiteSpace($manifestLineageHash) -or $manifestLineageHash -cne [string]$lineage.hash) {
        return & $denied "lineage-hash-mismatch"
    }
    if (@($lineage.rows | Where-Object { [int]$_.processId -eq $launcherPid }).Count -ne 1) {
        return & $denied "launcher-not-in-lineage"
    }

    $ports = Get-AwxObjectProperty -Object $Manifest -Name "ports"
    $serverPort = [int](Get-AwxObjectProperty -Object $ports -Name "server")
    if ($serverPort -le 0) {
        return & $denied "server-port-missing"
    }
    $serverListeners = @(Get-NetTCPConnection -State Listen -LocalPort $serverPort -ErrorAction SilentlyContinue)
    if (@($serverListeners | Where-Object { [int]$_.OwningProcess -eq $listenerPid }).Count -ne 1) {
        return & $denied "listener-port-owner-mismatch"
    }
    $allPorts = @(
        [int](Get-AwxObjectProperty -Object $ports -Name "server"),
        [int](Get-AwxObjectProperty -Object $ports -Name "management"),
        [int](Get-AwxObjectProperty -Object $ports -Name "netty")
    ) | Where-Object { $_ -gt 0 } | Select-Object -Unique

    return [pscustomobject][ordered]@{
        ok = $true
        status = "owned-runtime-attributed"
        reason = "identity-match"
        listenerPid = $listenerPid
        launcherPid = $launcherPid
        portCount = @($allPorts).Count
        ports = @($allPorts)
    }
}

function Wait-AwxPortsReleased {
    param(
        [int[]]$Ports,
        [int]$TimeoutSeconds = 15
    )

    $positivePorts = @($Ports | Where-Object { $_ -gt 0 } | Select-Object -Unique)
    $deadline = (Get-Date).AddSeconds([Math]::Max(1, $TimeoutSeconds))
    do {
        $remaining = @(Get-NetTCPConnection -State Listen -LocalPort $positivePorts -ErrorAction SilentlyContinue)
        if ($remaining.Count -eq 0) {
            return $true
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    return $false
}

function Get-AwxDescendantProcessRows {
    param([int]$RootProcessId)

    $processes = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
    $childrenByParent = @{}
    foreach ($process in $processes) {
        $parent = [int]$process.ParentProcessId
        if (-not $childrenByParent.ContainsKey($parent)) {
            $childrenByParent[$parent] = [System.Collections.Generic.List[object]]::new()
        }
        $childrenByParent[$parent].Add($process) | Out-Null
    }
    $rows = [System.Collections.Generic.List[object]]::new()
    $queue = [System.Collections.Generic.Queue[object]]::new()
    $queue.Enqueue([pscustomobject]@{ processId = $RootProcessId; depth = 0 })
    $seen = [System.Collections.Generic.HashSet[int]]::new()
    while ($queue.Count -gt 0) {
        $item = $queue.Dequeue()
        if (-not $seen.Add([int]$item.processId)) {
            continue
        }
        $identity = Get-AwxProcessIdentity -ProcessId ([int]$item.processId)
        if ($null -ne $identity) {
            $rows.Add([pscustomobject][ordered]@{
                processId = [int]$identity.processId
                creationDate = [string]$identity.creationDate
                depth = [int]$item.depth
            }) | Out-Null
        }
        if ($childrenByParent.ContainsKey([int]$item.processId)) {
            foreach ($child in $childrenByParent[[int]$item.processId]) {
                $queue.Enqueue([pscustomobject]@{
                    processId = [int]$child.ProcessId
                    depth = [int]$item.depth + 1
                })
            }
        }
    }
    return @($rows.ToArray() | Sort-Object depth -Descending)
}

function Stop-AwxOwnedRuntime {
    param(
        [object]$Manifest,
        [string]$Root,
        [string[]]$AllowedProcessNames = @("java.exe", "java"),
        [int]$TimeoutSeconds = 15
    )

    $validation = Test-AwxOwnedRuntimeIdentity -Manifest $Manifest -Root $Root -AllowedProcessNames $AllowedProcessNames
    if (-not $validation.ok) {
        $validation | Add-Member -NotePropertyName stopAttemptCount -NotePropertyValue 0 -Force
        $validation | Add-Member -NotePropertyName alreadyExitedCount -NotePropertyValue 0 -Force
        $validation | Add-Member -NotePropertyName skippedDescendantCount -NotePropertyValue 0 -Force
        $validation | Add-Member -NotePropertyName stoppedProcessCount -NotePropertyValue 0 -Force
        $validation | Add-Member -NotePropertyName remainingProcessCount -NotePropertyValue 0 -Force
        return $validation
    }
    $manifestTargets = @(
        [pscustomobject]@{
            role = "listener"
            identity = Get-AwxObjectProperty -Object $Manifest -Name "listener"
        },
        [pscustomobject]@{
            role = "launcher"
            identity = Get-AwxObjectProperty -Object $Manifest -Name "launcher"
        }
    )
    $seenProcessIds = [System.Collections.Generic.HashSet[int]]::new()
    $validatedManifestRows = [System.Collections.Generic.List[object]]::new()
    foreach ($target in $manifestTargets) {
        $expected = $target.identity
        $processId = [int](Get-AwxObjectProperty -Object $expected -Name "processId")
        if (-not $seenProcessIds.Add($processId)) {
            continue
        }
        $live = Get-AwxProcessIdentity -ProcessId $processId
        if ($null -eq $live -or
            -not (Test-AwxSameCreationDate `
                -Left (Get-AwxObjectProperty -Object $expected -Name "creationDate") `
                -Right $live.creationDate) -or
            [string](Get-AwxObjectProperty -Object $expected -Name "processName") -cne [string]$live.processName -or
            [string](Get-AwxObjectProperty -Object $expected -Name "commandHash") -cne [string]$live.commandHash) {
            return [pscustomobject][ordered]@{
                ok = $false
                status = "owned-runtime-attribution-failed"
                reason = "$($target.role)-identity-changed-before-stop"
                stopAttemptCount = 0
                alreadyExitedCount = 0
                skippedDescendantCount = 0
                stoppedProcessCount = 0
                remainingProcessCount = 0
                portCount = @($validation.ports).Count
            }
        }
        $validatedManifestRows.Add([pscustomobject][ordered]@{
            processId = $processId
            creationDate = [string]$live.creationDate
            role = [string]$target.role
        }) | Out-Null
    }
    $validatedManifestRows = @($validatedManifestRows.ToArray())
    $manifestLauncher = Get-AwxObjectProperty -Object $Manifest -Name "launcher"
    $launcherPid = [int](Get-AwxObjectProperty -Object $manifestLauncher -Name "processId")
    $treeRows = @(Get-AwxDescendantProcessRows -RootProcessId $launcherPid)
    foreach ($manifestRow in $validatedManifestRows) {
        $snapshotMatches = @($treeRows | Where-Object {
            [int]$_.processId -eq [int]$manifestRow.processId -and
                (Test-AwxSameCreationDate -Left $_.creationDate -Right $manifestRow.creationDate)
        })
        if ($snapshotMatches.Count -ne 1) {
            return [pscustomobject][ordered]@{
                ok = $false
                status = "owned-runtime-attribution-failed"
                reason = "$($manifestRow.role)-not-in-owned-tree-before-stop"
                stopAttemptCount = 0
                alreadyExitedCount = 0
                skippedDescendantCount = 0
                stoppedProcessCount = 0
                remainingProcessCount = 0
                portCount = @($validation.ports).Count
            }
        }
    }
    $manifestProcessIds = [System.Collections.Generic.HashSet[int]]::new()
    foreach ($manifestRow in $validatedManifestRows) {
        $manifestProcessIds.Add([int]$manifestRow.processId) | Out-Null
    }
    $allowedProcessNames = @($AllowedProcessNames | ForEach-Object { ([string]$_).ToLowerInvariant() })
    $runId = [string](Get-AwxObjectProperty -Object $Manifest -Name "runId")
    $processRows = @($treeRows | Where-Object {
        $processId = [int]$_.processId
        if ($manifestProcessIds.Contains($processId)) {
            return $true
        }
        $live = Get-AwxProcessIdentity -ProcessId $processId
        if ($null -eq $live -or
            -not (Test-AwxSameCreationDate -Left $live.creationDate -Right $_.creationDate) -or
            $allowedProcessNames -notcontains ([string]$live.processName).ToLowerInvariant()) {
            return $false
        }
        $liveProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
        return $null -ne $liveProcess -and
            -not [string]::IsNullOrWhiteSpace([string]$liveProcess.CommandLine) -and
            ([string]$liveProcess.CommandLine).IndexOf($runId, [System.StringComparison]::Ordinal) -ge 0
    })
    $skippedDescendantCount = [Math]::Max(0, $treeRows.Count - $processRows.Count)
    $stopAttemptRows = [System.Collections.Generic.List[object]]::new()
    $alreadyExitedCount = 0
    foreach ($row in $processRows) {
        $live = Get-AwxProcessIdentity -ProcessId ([int]$row.processId)
        if ($null -eq $live -or
            -not (Test-AwxSameCreationDate -Left $live.creationDate -Right $row.creationDate)) {
            $alreadyExitedCount++
            continue
        }
        $stopAttemptRows.Add($row) | Out-Null
        Stop-Process -Id ([int]$row.processId) -Force -ErrorAction SilentlyContinue
    }

    $deadline = (Get-Date).AddSeconds([Math]::Max(1, $TimeoutSeconds))
    do {
        $remaining = @($processRows | Where-Object {
            $live = Get-AwxProcessIdentity -ProcessId ([int]$_.processId)
            $null -ne $live -and
                (Test-AwxSameCreationDate -Left $live.creationDate -Right $_.creationDate)
        })
        if ($remaining.Count -eq 0) {
            break
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    $remainingAttemptCount = @($stopAttemptRows.ToArray() | Where-Object {
        $live = Get-AwxProcessIdentity -ProcessId ([int]$_.processId)
        $null -ne $live -and
            (Test-AwxSameCreationDate -Left $live.creationDate -Right $_.creationDate)
    }).Count
    $stoppedProcessCount = [Math]::Max(0, $stopAttemptRows.Count - $remainingAttemptCount)
    if ($remaining.Count -ne 0 -or
        -not (Wait-AwxPortsReleased -Ports @($validation.ports) -TimeoutSeconds $TimeoutSeconds)) {
        return [pscustomobject][ordered]@{
            ok = $false
            status = "owned-runtime-stop-timeout"
            stopAttemptCount = $stopAttemptRows.Count
            alreadyExitedCount = $alreadyExitedCount
            skippedDescendantCount = $skippedDescendantCount
            stoppedProcessCount = $stoppedProcessCount
            remainingProcessCount = $remaining.Count
            portCount = @($validation.ports).Count
        }
    }
    return [pscustomobject][ordered]@{
        ok = $true
        status = "owned-runtime-stopped"
        stopAttemptCount = $stopAttemptRows.Count
        alreadyExitedCount = $alreadyExitedCount
        skippedDescendantCount = $skippedDescendantCount
        stoppedProcessCount = $stoppedProcessCount
        remainingProcessCount = 0
        portCount = @($validation.ports).Count
    }
}

function Write-AwxJsonAtomic {
    param(
        [string]$Path,
        [object]$Data
    )

    $target = [System.IO.Path]::GetFullPath($Path)
    $directory = Split-Path -Parent $target
    if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    $temp = Join-Path $directory ((Split-Path -Leaf $target) + "." + [guid]::NewGuid().ToString("N") + ".tmp")
    $backup = Join-Path $directory ((Split-Path -Leaf $target) + "." + [guid]::NewGuid().ToString("N") + ".bak")
    try {
        $json = ($Data | ConvertTo-Json -Depth 12) + [Environment]::NewLine
        [System.IO.File]::WriteAllText($temp, $json, [System.Text.UTF8Encoding]::new($false))
        if (Test-Path -LiteralPath $target -PathType Leaf) {
            [System.IO.File]::Replace($temp, $target, $backup)
        } else {
            [System.IO.File]::Move($temp, $target)
        }
    } finally {
        if (Test-Path -LiteralPath $temp) {
            [System.IO.File]::Delete($temp)
        }
        if (Test-Path -LiteralPath $backup) {
            [System.IO.File]::Delete($backup)
        }
    }
}

function Move-AwxConsumedRuntimeManifest {
    param(
        [string]$Path,
        [string]$ExpectedSha256,
        [string]$ArchiveDirectory = ""
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return [pscustomobject][ordered]@{
            ok = $false
            status = "manifest-path-missing"
            mutationAllowed = $false
            archivePath = ""
        }
    }
    $target = [System.IO.Path]::GetFullPath($Path)
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        return [pscustomobject][ordered]@{
            ok = $true
            status = "manifest-absent"
            mutationAllowed = $false
            archivePath = ""
        }
    }

    $actualSha256 = Get-AwxFileSha256Hex -Path $target
    if ([string]::IsNullOrWhiteSpace($ExpectedSha256) -or
        $actualSha256 -cne $ExpectedSha256.ToLowerInvariant()) {
        return [pscustomobject][ordered]@{
            ok = $false
            status = "manifest-changed-after-cleanup"
            mutationAllowed = $false
            archivePath = ""
        }
    }

    $archiveRoot = if ([string]::IsNullOrWhiteSpace($ArchiveDirectory)) {
        Join-Path (Split-Path -Parent $target) "stale"
    } else {
        [System.IO.Path]::GetFullPath($ArchiveDirectory)
    }
    New-Item -ItemType Directory -Force -Path $archiveRoot | Out-Null
    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($target)
    $extension = [System.IO.Path]::GetExtension($target)
    if ([string]::IsNullOrWhiteSpace($extension)) {
        $extension = ".json"
    }
    $timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssfffZ")
    $archiveName = "$baseName.consumed-$timestamp-$($actualSha256.Substring(0, 12))$extension"
    $archivePath = Join-Path $archiveRoot $archiveName
    try {
        [System.IO.File]::Move($target, $archivePath)
    } catch {
        return [pscustomobject][ordered]@{
            ok = $false
            status = "manifest-consume-failed"
            mutationAllowed = $false
            archivePath = ""
        }
    }
    return [pscustomobject][ordered]@{
        ok = $true
        status = "owned-runtime-manifest-consumed"
        mutationAllowed = $true
        archivePath = $archivePath
    }
}

function Invoke-AwxVerifiedCleanup {
    param(
        [int]$VerificationExitCode,
        [object]$Manifest,
        [string]$Root,
        [string[]]$AllowedProcessNames = @("java.exe", "java")
    )

    if ($VerificationExitCode -ne 0) {
        return [pscustomobject][ordered]@{
            ok = $false
            status = "verification-failed"
            mutationAllowed = $false
            stopAttemptCount = 0
            alreadyExitedCount = 0
            skippedDescendantCount = 0
            stoppedProcessCount = 0
            remainingProcessCount = 0
            portCount = 0
        }
    }
    if ($null -eq $Manifest) {
        return [pscustomobject][ordered]@{
            ok = $true
            status = "no-prior-runtime"
            mutationAllowed = $false
            stopAttemptCount = 0
            alreadyExitedCount = 0
            skippedDescendantCount = 0
            stoppedProcessCount = 0
            remainingProcessCount = 0
            portCount = 0
        }
    }
    return Stop-AwxOwnedRuntime -Manifest $Manifest -Root $Root -AllowedProcessNames $AllowedProcessNames
}

function ConvertTo-AwxRelativePath {
    param(
        [string]$Root,
        [string]$Path
    )

    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd("\", "/") + [System.IO.Path]::DirectorySeparatorChar
    $pathFull = [System.IO.Path]::GetFullPath($Path)
    $rootUri = [uri]$rootFull
    $pathUri = [uri]$pathFull
    if ($rootUri.IsBaseOf($pathUri)) {
        return [uri]::UnescapeDataString($rootUri.MakeRelativeUri($pathUri).ToString()).Replace("/", "\")
    }
    return "external-path"
}

function Invoke-AwxGradleVerification {
    param(
        [string]$Root,
        [string]$GradlePath,
        [string[]]$Tasks,
        [string]$ProjectCacheDir,
        [string]$OutLog,
        [string]$ErrLog
    )

    $taskList = @($Tasks | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) })
    if ($taskList.Count -eq 0) {
        return [pscustomobject][ordered]@{
            exitCode = 2
            status = "verification-task-missing"
            taskCount = 0
            outLog = ""
            errLog = ""
        }
    }
    foreach ($directory in @(
        (Split-Path -Parent ([System.IO.Path]::GetFullPath($OutLog))),
        (Split-Path -Parent ([System.IO.Path]::GetFullPath($ErrLog))),
        ([System.IO.Path]::GetFullPath($ProjectCacheDir))
    ) | Select-Object -Unique) {
        if (-not (Test-Path -LiteralPath $directory -PathType Container)) {
            New-Item -ItemType Directory -Force -Path $directory | Out-Null
        }
    }
    $arguments = @($taskList) + @(
        "--no-daemon",
        "--max-workers=1",
        "--project-cache-dir",
        $ProjectCacheDir
    )
    try {
        $process = Start-Process -FilePath $GradlePath `
            -ArgumentList $arguments `
            -WorkingDirectory $Root `
            -PassThru `
            -RedirectStandardOutput $OutLog `
            -RedirectStandardError $ErrLog `
            -WindowStyle Hidden
        $process.WaitForExit()
        $process.Refresh()
        $exitCode = [int]$process.ExitCode
        return [pscustomobject][ordered]@{
            exitCode = $exitCode
            status = if ($exitCode -eq 0) { "verification-passed" } else { "verification-failed" }
            taskCount = $taskList.Count
            outLog = ConvertTo-AwxRelativePath -Root $Root -Path $OutLog
            errLog = ConvertTo-AwxRelativePath -Root $Root -Path $ErrLog
        }
    } catch {
        return [pscustomobject][ordered]@{
            exitCode = 3
            status = "verification-launch-failed"
            taskCount = $taskList.Count
            outLog = ConvertTo-AwxRelativePath -Root $Root -Path $OutLog
            errLog = ConvertTo-AwxRelativePath -Root $Root -Path $ErrLog
        }
    }
}

function Get-AwxFileSha256Hex {
    param([string]$Path)

    $stream = $null
    $sha = $null
    try {
        $stream = [System.IO.File]::Open(
            $Path,
            [System.IO.FileMode]::Open,
            [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::Read
        )
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $hash = $sha.ComputeHash($stream)
        return ([System.BitConverter]::ToString($hash) -replace "-", "").ToLowerInvariant()
    } finally {
        if ($null -ne $sha) {
            $sha.Dispose()
        }
        if ($null -ne $stream) {
            $stream.Dispose()
        }
    }
}

function Test-AwxFreshRuntimeProvenance {
    param(
        [string]$Root,
        [int]$LauncherPid,
        [datetime]$LaunchBoundaryUtc,
        [string]$RunId,
        [int]$ServerPort,
        [string]$SourceAssetPath,
        [ValidateSet('chat-ui', 'meta-display', 'rag-studio')]
        [string]$UiSurface = 'chat-ui'
    )

    $failed = {
        param([string]$Reason)
        return [pscustomobject][ordered]@{
            ok = $false
            status = "fresh-runtime-provenance-failed"
            reason = $Reason
            listenerPid = 0
            statusCode = 0
            sourceAssetHash = ""
            servedAssetHash = ""
            startedAt = ""
        }
    }
    if ($ServerPort -le 0 -or [string]::IsNullOrWhiteSpace($RunId)) {
        return & $failed "launch-identity-missing"
    }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $ServerPort -ErrorAction SilentlyContinue)
    if ($listeners.Count -ne 1) {
        return & $failed "server-listener-count-mismatch"
    }
    $listenerPid = [int]$listeners[0].OwningProcess
    $identity = Get-AwxProcessIdentity -ProcessId $listenerPid
    if ($null -eq $identity) {
        return & $failed "server-listener-process-missing"
    }
    $lineage = Get-AwxProcessLineage -ProcessId $listenerPid
    if (@($lineage.rows | Where-Object { [int]$_.processId -eq $LauncherPid }).Count -ne 1) {
        return & $failed "listener-lineage-mismatch"
    }
    $startedAt = [datetimeoffset]::MinValue
    if (-not [datetimeoffset]::TryParse([string]$identity.creationDate, [ref]$startedAt) -or
        $startedAt.ToUniversalTime() -lt ([datetimeoffset]$LaunchBoundaryUtc.ToUniversalTime())) {
        return & $failed "listener-start-boundary-mismatch"
    }
    $listenerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$listenerPid" -ErrorAction SilentlyContinue
    if ($null -eq $listenerProcess -or
        [string]::IsNullOrWhiteSpace([string]$listenerProcess.CommandLine) -or
        ([string]$listenerProcess.CommandLine).IndexOf($RunId, [System.StringComparison]::Ordinal) -lt 0) {
        return & $failed "run-id-not-observed"
    }

    $pagePath = if ($UiSurface -eq 'meta-display') { '/assets/display/index.html' } elseif ($UiSurface -eq 'rag-studio') { '/assets/interview/studio.html' } else { '/chat-ui' }
    $assetPath = if ($UiSurface -eq 'meta-display') { '/assets/display/app.js' } elseif ($UiSurface -eq 'rag-studio') { '/assets/interview/studio.js' } else { '/js/chat.js' }
    $chatUrl = "http://127.0.0.1:$ServerPort$pagePath"
    try {
        $chatResponse = Invoke-WebRequest -Uri $chatUrl -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
    } catch {
        return & $failed "chat-ui-http-failed"
    }
    if ([int]$chatResponse.StatusCode -ne 200) {
        return & $failed "chat-ui-status-mismatch"
    }

    if ($UiSurface -eq 'chat-ui' -and [string]$chatResponse.Content -match '/assets/interview/') {
        # demo.interview.enabled=true forwards /chat-ui to the interview page;
        # /js/chat.js is intentionally filtered there, so verify the asset the
        # served page actually loads.
        $assetPath = '/assets/interview/app.js'
        $legacySource = if ([System.IO.Path]::IsPathRooted($SourceAssetPath)) { $SourceAssetPath } else { Join-Path $Root $SourceAssetPath }
        $SourceAssetPath = Join-Path (Split-Path -Parent (Split-Path -Parent $legacySource)) 'assets\interview\app.js'
    }

    $sourcePath = if ([System.IO.Path]::IsPathRooted($SourceAssetPath)) {
        $SourceAssetPath
    } else {
        Join-Path $Root $SourceAssetPath
    }
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) {
        return & $failed "source-asset-missing"
    }
    try {
        $webClient = [System.Net.WebClient]::new()
        try {
            $servedBytes = $webClient.DownloadData("http://127.0.0.1:$ServerPort$assetPath")
        } finally {
            $webClient.Dispose()
        }
    } catch {
        return & $failed "served-asset-download-failed"
    }
    $sourceHash = Get-AwxFileSha256Hex -Path $sourcePath
    $servedHash = Get-AwxSha256Hex -Bytes $servedBytes
    if ($sourceHash -cne $servedHash) {
        return & $failed "served-asset-hash-mismatch"
    }
    return [pscustomobject][ordered]@{
        ok = $true
        status = "fresh-runtime-proven"
        reason = "lineage-http-asset-match"
        listenerPid = $listenerPid
        statusCode = [int]$chatResponse.StatusCode
        sourceAssetHash = $sourceHash
        servedAssetHash = $servedHash
        startedAt = $startedAt.ToUniversalTime().ToString("o")
        lineageHash = [string]$lineage.hash
    }
}

function Stop-AwxStartedProcessTree {
    param(
        [object]$LauncherIdentity,
        [int]$TimeoutSeconds = 15
    )

    if ($null -eq $LauncherIdentity) {
        return [pscustomobject]@{ ok = $false; status = "launcher-identity-missing" }
    }
    $launcherPid = [int](Get-AwxObjectProperty -Object $LauncherIdentity -Name "processId")
    $live = Get-AwxProcessIdentity -ProcessId $launcherPid
    if ($null -eq $live -or
        -not (Test-AwxSameCreationDate -Left $live.creationDate `
            -Right (Get-AwxObjectProperty -Object $LauncherIdentity -Name "creationDate")) -or
        [string]$live.commandHash -cne [string](Get-AwxObjectProperty -Object $LauncherIdentity -Name "commandHash")) {
        return [pscustomobject]@{ ok = $false; status = "launcher-identity-mismatch" }
    }
    $rows = @(Get-AwxDescendantProcessRows -RootProcessId $launcherPid)
    foreach ($row in $rows) {
        $current = Get-AwxProcessIdentity -ProcessId ([int]$row.processId)
        if ($null -ne $current -and
            (Test-AwxSameCreationDate -Left $current.creationDate -Right $row.creationDate)) {
            Stop-Process -Id ([int]$row.processId) -Force -ErrorAction SilentlyContinue
        }
    }
    $deadline = (Get-Date).AddSeconds([Math]::Max(1, $TimeoutSeconds))
    do {
        $launcherStillMatches = Get-AwxProcessIdentity -ProcessId $launcherPid
        if ($null -eq $launcherStillMatches -or
            -not (Test-AwxSameCreationDate -Left $launcherStillMatches.creationDate `
                -Right (Get-AwxObjectProperty -Object $LauncherIdentity -Name "creationDate"))) {
            return [pscustomobject]@{ ok = $true; status = "started-process-tree-stopped" }
        }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $deadline)
    return [pscustomobject]@{ ok = $false; status = "started-process-tree-stop-timeout" }
}
