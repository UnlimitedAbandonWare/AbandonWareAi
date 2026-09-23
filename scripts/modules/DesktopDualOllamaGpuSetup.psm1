#requires -Version 5.1

Set-StrictMode -Version Latest

$script:LaneDefinitions = @(
    [pscustomobject]@{
        Lane = 'fast'
        GpuName = 'NVIDIA GeForce RTX 3060'
        Port = 11435
        Host = '127.0.0.1:11435'
        Endpoint = 'http://127.0.0.1:11435'
        StoreKind = 'default-user-store'
        Models = @(
            [pscustomobject]@{ Name = 'qwen3:8b'; Digest = '500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41' }
            [pscustomobject]@{ Name = 'qwen3.5:9b'; Digest = '6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7' }
            [pscustomobject]@{ Name = 'gemma4:12b'; Digest = '4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c' }
        )
    }
    [pscustomobject]@{
        Lane = 'main'
        GpuName = 'NVIDIA GeForce RTX 3090'
        Port = 11434
        Host = '127.0.0.1:11434'
        Endpoint = 'http://127.0.0.1:11434'
        StoreKind = 'large-volume-store'
        Models = @(
            [pscustomobject]@{ Name = 'gemma4:26b'; Digest = '5571076f3d70050487b26b341705799e0ab29b808164f90d20d4cf84f699d251' }
            [pscustomobject]@{ Name = 'qwen3.6:27b'; Digest = 'a50eda8ed977ab48a12431878896b27ffd5cef552c17af3317d9623b939a7f1e' }
            [pscustomobject]@{ Name = 'gemma4:31b'; Digest = '6316f0629137b426c9d9b853ffc4c8209589f30ee39aebede6285096c0ff47e7' }
        )
    }
)

function Get-Sha256Hex {
    param([AllowEmptyString()][string]$Value)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$Value)
        $hash = $sha.ComputeHash($bytes)
        return ([System.BitConverter]::ToString($hash)).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Test-CanonicalGpuUuid {
    param([AllowNull()][AllowEmptyString()][string]$Value)

    return [bool]($Value -match '^GPU-[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$')
}

function ConvertTo-ReasonToken {
    param([Parameter(Mandatory)][string]$Value)

    return (($Value.ToLowerInvariant() -replace '[:.]', '_') -replace '[^a-z0-9_]', '_')
}

function ConvertFrom-NvidiaSmiCsv {
    [CmdletBinding()]
    param([Parameter(Mandatory)][AllowEmptyCollection()][AllowEmptyString()][string[]]$Lines)

    $items = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $Lines) {
        $parts = @([string]$line -split '\s*,\s*', 4)
        if ($parts.Count -ne 4 -or $parts[1] -notmatch '^GPU-') {
            continue
        }

        $memoryMiB = 0
        if (-not [int]::TryParse($parts[2], [ref]$memoryMiB) -or $memoryMiB -le 0) {
            $memoryMiB = $null
        }
        $items.Add([pscustomobject]@{
            Name = $parts[0].Trim()
            Uuid = $parts[1].Trim()
            MemoryMiB = $memoryMiB
            DriverVersion = $parts[3].Trim()
        })
    }

    if ($items.Count -eq 0) {
        throw 'gpu_inventory_unavailable'
    }

    return @($items)
}

function Invoke-GpuInventoryProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string]$Arguments,
        [ValidateRange(1, 60000)][int]$TimeoutMilliseconds = 10000
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FilePath
    $startInfo.Arguments = $Arguments
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true

    $process = [System.Diagnostics.Process]::Start($startInfo)
    if ($null -eq $process) {
        throw 'gpu_inventory_unavailable'
    }

    $timedOut = $false
    $exitCode = -1
    $stdout = ''
    try {
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $exited = $process.WaitForExit($TimeoutMilliseconds)
        if (-not $exited) {
            $timedOut = $true
            try { $process.Kill() } catch { }
            try { $exited = $process.WaitForExit(2000) } catch { $exited = $false }
        }
        if ($exited) {
            try { $exitCode = $process.ExitCode } catch { $exitCode = -1 }
            $stdoutCompleted = $false
            $stderrCompleted = $false
            try { $stdoutCompleted = $stdoutTask.Wait(1000) } catch { $stdoutCompleted = $false }
            try { $stderrCompleted = $stderrTask.Wait(1000) } catch { $stderrCompleted = $false }
            if ($stdoutCompleted -and -not $stdoutTask.IsFaulted -and -not $stdoutTask.IsCanceled) {
                $stdout = $stdoutTask.GetAwaiter().GetResult()
            }
            if (-not $stdoutCompleted -or -not $stderrCompleted) {
                $timedOut = $true
            }
        }
    }
    finally {
        $process.Dispose()
    }

    $items = @()
    $parseSucceeded = $false
    try {
        $items = @(ConvertFrom-NvidiaSmiCsv -Lines @($stdout -split '\r?\n'))
        $parseSucceeded = $true
    }
    catch {
        $items = @()
    }

    return [pscustomobject]@{
        Items = $items
        CommandSucceeded = [bool](-not $timedOut -and $exitCode -eq 0 -and $parseSucceeded)
        TimedOut = [bool]$timedOut
    }
}

function Get-DefaultGpuInventory {
    [CmdletBinding()]
    param()

    $nvidiaSmi = Get-Command 'nvidia-smi.exe' -ErrorAction SilentlyContinue
    if ($null -eq $nvidiaSmi) {
        $nvidiaSmi = Get-Command 'nvidia-smi' -ErrorAction Stop
    }

    return Invoke-GpuInventoryProcess `
        -FilePath $nvidiaSmi.Path `
        -Arguments '--query-gpu=name,uuid,memory.total,driver_version --format=csv,noheader,nounits' `
        -TimeoutMilliseconds 10000
}

function Get-DefaultPortState {
    [CmdletBinding()]
    param([Parameter(Mandatory)][int[]]$Ports)

    $listeners = @([System.Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners())
    return @($Ports | ForEach-Object {
        $port = [int]$_
        [pscustomobject]@{
            Port = $port
            InUse = [bool]($listeners | Where-Object { $_.Port -eq $port } | Select-Object -First 1)
        }
    })
}

function Test-DefaultStorePath {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Path)

    return (Test-Path -LiteralPath $Path -PathType Container)
}

function Get-DefaultTagInventory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Lane,
        [Parameter(Mandatory)][string]$Endpoint
    )

    $response = Invoke-RestMethod -Method Get -Uri ($Endpoint.TrimEnd('/') + '/api/tags') -TimeoutSec 5 -ErrorAction Stop
    return @($response.models | ForEach-Object {
        [pscustomobject]@{
            Name = [string]$_.name
            Digest = [string]$_.digest
        }
    })
}

function Test-DefaultLaneHealth {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Lane,
        [Parameter(Mandatory)][string]$Endpoint
    )

    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $null = Invoke-RestMethod -Method Get -Uri ($Endpoint.TrimEnd('/') + '/api/version') -TimeoutSec 2 -ErrorAction Stop
            return $true
        }
        catch {
            Start-Sleep -Milliseconds 500
        }
    }

    return $false
}

function Get-ExecutableIdentityHash {
    param([Parameter(Mandatory)][string]$Path)

    $normalized = [System.IO.Path]::GetFullPath($Path).Trim().ToLowerInvariant()
    return Get-Sha256Hex -Value $normalized
}

function New-NullRoutedProcessStartInfo {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string]$Arguments,
        [Parameter(Mandatory)][hashtable]$Environment
    )

    if ($FilePath -match '[\r\n"]' -or $Arguments -match '[\r\n"]') {
        throw 'unsafe_process_argument'
    }

    $commandProcessor = $env:ComSpec
    if ([string]::IsNullOrWhiteSpace($commandProcessor)) {
        $commandProcessor = Join-Path $env:SystemRoot 'System32\cmd.exe'
    }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $commandProcessor
    $startInfo.Arguments = ('/d /s /c ""{0}" {1} 1>NUL 2>NUL"' -f $FilePath, $Arguments)
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    foreach ($entry in $Environment.GetEnumerator()) {
        $startInfo.EnvironmentVariables[[string]$entry.Key] = [string]$entry.Value
    }

    return $startInfo
}

function Start-DefaultIsolatedProcess {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [Parameter(Mandatory)][string]$Arguments,
        [Parameter(Mandatory)][hashtable]$Environment
    )

    if ($FilePath -match '[\r\n"]' -or $Arguments -match '[\r\n"]') {
        throw 'unsafe_process_argument'
    }

    $previousValues = @{}
    try {
        foreach ($entry in $Environment.GetEnumerator()) {
            $name = [string]$entry.Key
            if ([string]::IsNullOrWhiteSpace($name) -or $name -match '[=\r\n]') {
                throw 'unsafe_environment_name'
            }
            $previousValues[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process)
            [Environment]::SetEnvironmentVariable($name, [string]$entry.Value, [EnvironmentVariableTarget]::Process)
        }

        $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $FilePath
        $startInfo.Arguments = $Arguments
        $startInfo.UseShellExecute = $true
        $startInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
        return [System.Diagnostics.Process]::Start($startInfo)
    }
    finally {
        foreach ($entry in $previousValues.GetEnumerator()) {
            [Environment]::SetEnvironmentVariable([string]$entry.Key, $entry.Value, [EnvironmentVariableTarget]::Process)
        }
    }
}

function Get-ProcessIdentity {
    param([Parameter(Mandatory)][int]$ProcessId)

    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        return $null
    }

    try {
        return [pscustomobject]@{
            ProcessId = [int]$process.Id
            StartTimeUtcTicks = [long]$process.StartTime.ToUniversalTime().Ticks
            ExecutableIdentityHash = Get-ExecutableIdentityHash -Path ([string]$process.Path)
        }
    }
    catch {
        return $null
    }
    finally {
        $process.Dispose()
    }
}

function Start-DefaultOllamaProcess {
    [CmdletBinding()]
    param([Parameter(Mandatory)][pscustomobject]$Spec)

    $ollamaCommand = Get-Command 'ollama.exe' -ErrorAction SilentlyContinue
    if ($null -eq $ollamaCommand) {
        $ollamaCommand = Get-Command 'ollama' -ErrorAction Stop
    }

    $ollamaPath = if ([string]::IsNullOrWhiteSpace([string]$ollamaCommand.Path)) { [string]$ollamaCommand.Source } else { [string]$ollamaCommand.Path }
    $supervisorExecutableIdentityHash = Get-ExecutableIdentityHash -Path $ollamaPath
    $childExecutableIdentityHash = Get-ExecutableIdentityHash -Path $ollamaPath

    $process = Start-DefaultIsolatedProcess -FilePath $ollamaPath -Arguments 'serve' -Environment $Spec.Environment
    if ($null -eq $process) {
        throw 'ollama_start_failed'
    }

    try {
        $supervisorStartTimeUtcTicks = [long]$process.StartTime.ToUniversalTime().Ticks
        return [pscustomobject]@{
            ProcessId = [int]$process.Id
            SupervisorProcessId = [int]$process.Id
            SupervisorStartTimeUtcTicks = $supervisorStartTimeUtcTicks
            SupervisorExecutableIdentityHash = $supervisorExecutableIdentityHash
            ExpectedChildExecutableIdentityHash = $childExecutableIdentityHash
            Lane = [string]$Spec.Lane
            Port = [int]$Spec.Port
        }
    }
    catch {
        $localCleanupSucceeded = $true
        $childIdentities = [System.Collections.Generic.List[object]]::new()
        try {
            $childRecords = @(Get-CimInstance -ClassName Win32_Process -Filter ("ParentProcessId = {0}" -f [int]$process.Id) -ErrorAction Stop)
            foreach ($childRecord in $childRecords) {
                $identity = Get-ProcessIdentity -ProcessId ([int]$childRecord.ProcessId)
                if ($null -ne $identity -and $identity.ExecutableIdentityHash -ceq $childExecutableIdentityHash) {
                    $childIdentities.Add($identity)
                    Stop-Process -Id $identity.ProcessId -Force -ErrorAction Stop
                }
            }
        }
        catch {
            $localCleanupSucceeded = $false
        }
        try {
            if (-not $process.HasExited) {
                $process.Kill()
                [void]$process.WaitForExit(2000)
            }
        }
        catch {
            $localCleanupSucceeded = $false
        }
        foreach ($identity in $childIdentities) {
            $remaining = Get-ProcessIdentity -ProcessId $identity.ProcessId
            if ($null -ne $remaining -and $remaining.StartTimeUtcTicks -eq $identity.StartTimeUtcTicks -and
                $remaining.ExecutableIdentityHash -ceq $identity.ExecutableIdentityHash) {
                $localCleanupSucceeded = $false
            }
        }
        if (-not $process.HasExited) { $localCleanupSucceeded = $false }
        if (-not $localCleanupSucceeded) { throw 'ollama_start_cleanup_failed' }
        throw
    }
    finally {
        $process.Dispose()
    }
}

function Get-DefaultProcessOwnership {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][pscustomobject]$Ownership,
        [Parameter(Mandatory)][int]$Port
    )

    $requiredLaunchProperties = @(
        'SupervisorProcessId',
        'SupervisorStartTimeUtcTicks',
        'SupervisorExecutableIdentityHash',
        'ExpectedChildExecutableIdentityHash'
    )
    foreach ($propertyName in $requiredLaunchProperties) {
        if ($null -eq $Ownership.PSObject.Properties[$propertyName]) {
            return [pscustomobject]@{ Verified = $false }
        }
    }

    $supervisorIdentity = Get-ProcessIdentity -ProcessId ([int]$Ownership.SupervisorProcessId)
    if ($null -eq $supervisorIdentity -or
        $supervisorIdentity.StartTimeUtcTicks -ne [long]$Ownership.SupervisorStartTimeUtcTicks -or
        $supervisorIdentity.ExecutableIdentityHash -cne [string]$Ownership.SupervisorExecutableIdentityHash) {
        return [pscustomobject]@{ Verified = $false }
    }

    try {
        $connections = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction Stop)
    }
    catch {
        return [pscustomobject]@{ Verified = $false }
    }
    $listenerPids = @($connections | Select-Object -ExpandProperty OwningProcess -Unique)
    if ($listenerPids.Count -ne 1) {
        return [pscustomobject]@{ Verified = $false }
    }

    $listenerPid = [int]$listenerPids[0]
    $listenerIdentity = Get-ProcessIdentity -ProcessId $listenerPid
    if ($null -eq $listenerIdentity -or
        $listenerIdentity.ExecutableIdentityHash -cne [string]$Ownership.ExpectedChildExecutableIdentityHash) {
        return [pscustomobject]@{ Verified = $false }
    }

    $parentMatches = $listenerPid -eq [int]$Ownership.SupervisorProcessId
    if (-not $parentMatches) {
        try {
            $processRecord = Get-CimInstance -ClassName Win32_Process -Filter ("ProcessId = {0}" -f $listenerPid) -ErrorAction Stop
            $parentMatches = $null -ne $processRecord -and [int]$processRecord.ParentProcessId -eq [int]$Ownership.SupervisorProcessId
        }
        catch {
            $parentMatches = $false
        }
    }
    if (-not $parentMatches) {
        return [pscustomobject]@{ Verified = $false }
    }

    return [pscustomobject]@{
        Verified = $true
        ListenerProcessId = $listenerIdentity.ProcessId
        ListenerStartTimeUtcTicks = $listenerIdentity.StartTimeUtcTicks
        ExecutableIdentityHash = $listenerIdentity.ExecutableIdentityHash
    }
}

function Test-SameListenerOwnership {
    param(
        [Parameter(Mandatory)][pscustomobject]$First,
        [Parameter(Mandatory)][pscustomobject]$Second
    )

    $required = @('Verified', 'ListenerProcessId', 'ListenerStartTimeUtcTicks', 'ExecutableIdentityHash')
    foreach ($propertyName in $required) {
        if ($null -eq $First.PSObject.Properties[$propertyName] -or $null -eq $Second.PSObject.Properties[$propertyName]) {
            return $false
        }
    }

    return [bool]($First.Verified -and $Second.Verified -and
        [int]$First.ListenerProcessId -eq [int]$Second.ListenerProcessId -and
        [long]$First.ListenerStartTimeUtcTicks -eq [long]$Second.ListenerStartTimeUtcTicks -and
        [string]$First.ExecutableIdentityHash -ceq [string]$Second.ExecutableIdentityHash)
}

function Stop-DefaultOwnedProcess {
    [CmdletBinding()]
    param([Parameter(Mandatory)][pscustomobject]$Ownership)

    $cleanupSucceeded = $true
    $expectedChildHash = if ($null -ne $Ownership.PSObject.Properties['ExpectedChildExecutableIdentityHash']) {
        [string]$Ownership.ExpectedChildExecutableIdentityHash
    }
    else { $null }

    $candidateChildren = [System.Collections.Generic.List[object]]::new()
    if ($null -ne $Ownership.PSObject.Properties['ListenerProcessId'] -and
        $null -ne $Ownership.PSObject.Properties['ListenerStartTimeUtcTicks'] -and
        $null -ne $Ownership.PSObject.Properties['ListenerExecutableIdentityHash']) {
        $candidateChildren.Add([pscustomobject]@{
            ProcessId = [int]$Ownership.ListenerProcessId
            StartTimeUtcTicks = [long]$Ownership.ListenerStartTimeUtcTicks
            ExecutableIdentityHash = [string]$Ownership.ListenerExecutableIdentityHash
        })
    }
    elseif ($null -ne $Ownership.PSObject.Properties['SupervisorProcessId'] -and -not [string]::IsNullOrWhiteSpace($expectedChildHash)) {
        try {
            $childRecords = @(Get-CimInstance -ClassName Win32_Process -Filter ("ParentProcessId = {0}" -f [int]$Ownership.SupervisorProcessId) -ErrorAction Stop)
            foreach ($childRecord in $childRecords) {
                $identity = Get-ProcessIdentity -ProcessId ([int]$childRecord.ProcessId)
                if ($null -ne $identity -and $identity.ExecutableIdentityHash -ceq $expectedChildHash) {
                    $candidateChildren.Add($identity)
                }
            }
        }
        catch {
            $cleanupSucceeded = $false
        }
    }

    foreach ($candidate in $candidateChildren) {
        $current = Get-ProcessIdentity -ProcessId ([int]$candidate.ProcessId)
        if ($null -eq $current) { continue }
        if ($current.StartTimeUtcTicks -ne [long]$candidate.StartTimeUtcTicks -or
            $current.ExecutableIdentityHash -cne [string]$candidate.ExecutableIdentityHash) {
            $cleanupSucceeded = $false
            continue
        }
        try {
            Stop-Process -Id $current.ProcessId -Force -ErrorAction Stop
            Wait-Process -Id $current.ProcessId -Timeout 5 -ErrorAction SilentlyContinue
        }
        catch {
            $cleanupSucceeded = $false
        }
        $remaining = Get-ProcessIdentity -ProcessId $current.ProcessId
        if ($null -ne $remaining -and $remaining.StartTimeUtcTicks -eq $current.StartTimeUtcTicks -and
            $remaining.ExecutableIdentityHash -ceq $current.ExecutableIdentityHash) {
            $cleanupSucceeded = $false
        }
    }

    if ($null -ne $Ownership.PSObject.Properties['SupervisorProcessId'] -and
        $null -ne $Ownership.PSObject.Properties['SupervisorStartTimeUtcTicks'] -and
        $null -ne $Ownership.PSObject.Properties['SupervisorExecutableIdentityHash']) {
        $supervisor = Get-ProcessIdentity -ProcessId ([int]$Ownership.SupervisorProcessId)
        if ($null -ne $supervisor) {
            if ($supervisor.StartTimeUtcTicks -ne [long]$Ownership.SupervisorStartTimeUtcTicks -or
                $supervisor.ExecutableIdentityHash -cne [string]$Ownership.SupervisorExecutableIdentityHash) {
                $cleanupSucceeded = $false
            }
            else {
                try {
                    Stop-Process -Id $supervisor.ProcessId -Force -ErrorAction Stop
                    Wait-Process -Id $supervisor.ProcessId -Timeout 5 -ErrorAction SilentlyContinue
                }
                catch {
                    $cleanupSucceeded = $false
                }
                $remainingSupervisor = Get-ProcessIdentity -ProcessId $supervisor.ProcessId
                if ($null -ne $remainingSupervisor -and
                    $remainingSupervisor.StartTimeUtcTicks -eq $supervisor.StartTimeUtcTicks -and
                    $remainingSupervisor.ExecutableIdentityHash -ceq $supervisor.ExecutableIdentityHash) {
                    $cleanupSucceeded = $false
                }
            }
        }
    }

    if ($null -ne $Ownership.PSObject.Properties['Port']) {
        for ($attempt = 0; $attempt -lt 20; $attempt++) {
            $portState = @(Get-DefaultPortState -Ports @([int]$Ownership.Port))[0]
            if (-not $portState.InUse) { break }
            Start-Sleep -Milliseconds 100
        }
        if ($portState.InUse) { $cleanupSucceeded = $false }
    }

    return [bool]$cleanupSucceeded
}

function Get-ModelChecks {
    param(
        [Parameter(Mandatory)][pscustomobject]$LaneDefinition,
        [Parameter(Mandatory)][object[]]$ObservedTags
    )

    $checks = [System.Collections.Generic.List[object]]::new()
    $reasons = [System.Collections.Generic.List[string]]::new()

    foreach ($expected in $LaneDefinition.Models) {
        $observed = $ObservedTags | Where-Object { [string]$_.Name -ceq [string]$expected.Name } | Select-Object -First 1
        $present = $null -ne $observed
        $digestMatch = $present -and ([string]$observed.Digest -ceq [string]$expected.Digest)
        $modelToken = ConvertTo-ReasonToken -Value ([string]$expected.Name)

        if (-not $present) {
            $reasons.Add(('model_missing_{0}_{1}' -f $LaneDefinition.Lane, $modelToken))
        }
        elseif (-not $digestMatch) {
            $reasons.Add(('model_digest_mismatch_{0}_{1}' -f $LaneDefinition.Lane, $modelToken))
        }

        $checks.Add([pscustomobject]@{
            lane = [string]$LaneDefinition.Lane
            model = [string]$expected.Name
            present = [bool]$present
            digestMatch = [bool]$digestMatch
        })
    }

    return [pscustomobject]@{
        Checks = @($checks)
        Reasons = @($reasons)
    }
}

function New-DesktopDualOllamaContext {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)]
        [ValidateSet('ValidateOnly', 'Start')]
        [string]$Mode,

        [Parameter(Mandatory)][string]$FastModelsPath,
        [Parameter(Mandatory)][string]$MainModelsPath,
        [Parameter(Mandatory)][scriptblock]$GpuInventoryProvider,
        [Parameter(Mandatory)][scriptblock]$PortStateProvider,
        [Parameter(Mandatory)][scriptblock]$StoreProbeProvider,
        [Parameter(Mandatory)][scriptblock]$TagInventoryProvider
    )

    $reasons = [System.Collections.Generic.List[string]]::new()
    $publicGpuLanes = [System.Collections.Generic.List[object]]::new()
    $rawLanes = [System.Collections.Generic.List[object]]::new()
    $publicModels = [System.Collections.Generic.List[object]]::new()

    try {
        $gpuProviderOutput = @(& $GpuInventoryProvider)
        if ($gpuProviderOutput.Count -eq 1 -and $null -ne $gpuProviderOutput[0].PSObject.Properties['Items']) {
            $providerEnvelope = $gpuProviderOutput[0]
            $gpuItems = @($providerEnvelope.Items)
            $timedOutProperty = $providerEnvelope.PSObject.Properties['TimedOut']
            $commandSucceededProperty = $providerEnvelope.PSObject.Properties['CommandSucceeded']
            if ($null -ne $timedOutProperty -and [bool]$timedOutProperty.Value) {
                $reasons.Add('gpu_inventory_timeout')
            }
            if ($null -ne $commandSucceededProperty -and -not [bool]$commandSucceededProperty.Value) {
                $reasons.Add('gpu_inventory_command_failed')
            }
        }
        else {
            $gpuItems = @($gpuProviderOutput)
        }
    }
    catch {
        $gpuItems = @()
        $reasons.Add('gpu_inventory_failed')
    }

    $pathsByLane = @{
        fast = $FastModelsPath
        main = $MainModelsPath
    }

    foreach ($definition in $script:LaneDefinitions) {
        $matches = @($gpuItems | Where-Object { [string]$_.Name -eq [string]$definition.GpuName })
        if ($matches.Count -eq 0) {
            $reasons.Add(('gpu_missing_{0}' -f $definition.Lane))
            $publicGpuLanes.Add([pscustomobject]@{
                lane = [string]$definition.Lane
                expectedName = [string]$definition.GpuName
                present = $false
                uuidHash = $null
                memoryMiB = $null
                driverVersion = $null
            })
            continue
        }
        if ($matches.Count -ne 1) {
            $reasons.Add(('gpu_ambiguous_{0}' -f $definition.Lane))
            continue
        }

        $gpu = $matches[0]
        $uuid = [string]$gpu.Uuid
        $uuidValid = Test-CanonicalGpuUuid -Value $uuid
        if (-not $uuidValid) {
            $reasons.Add(('gpu_uuid_invalid_{0}' -f $definition.Lane))
        }
        if ($null -eq $gpu.MemoryMiB -or $gpu.MemoryMiB -le 0) {
            $reasons.Add(('gpu_memory_unavailable_{0}' -f $definition.Lane))
        }

        $publicGpuLanes.Add([pscustomobject]@{
            lane = [string]$definition.Lane
            expectedName = [string]$definition.GpuName
            present = $true
            uuidHash = if ([string]::IsNullOrWhiteSpace($uuid)) { $null } else { Get-Sha256Hex -Value $uuid }
            memoryMiB = $gpu.MemoryMiB
            driverVersion = [string]$gpu.DriverVersion
        })

        $rawLanes.Add([pscustomobject]@{
            Definition = $definition
            GpuUuid = $uuid
            GpuUuidValid = $uuidValid
            ModelsPath = [string]$pathsByLane[[string]$definition.Lane]
        })
    }

    $validGpuUuids = @($rawLanes | Where-Object GpuUuidValid | ForEach-Object { [string]$_.GpuUuid })
    if ($validGpuUuids.Count -eq $script:LaneDefinitions.Count -and @($validGpuUuids | Select-Object -Unique).Count -ne $validGpuUuids.Count) {
        $reasons.Add('gpu_uuid_duplicate')
    }

    try {
        $portStates = @(& $PortStateProvider @($script:LaneDefinitions.Port))
    }
    catch {
        $portStates = @()
        $reasons.Add('port_inventory_failed')
    }

    $publicPorts = [System.Collections.Generic.List[object]]::new()
    foreach ($definition in $script:LaneDefinitions) {
        $matchingStates = @($portStates | Where-Object {
            $portProperty = $_.PSObject.Properties['Port']
            $null -ne $portProperty -and [int]$portProperty.Value -eq [int]$definition.Port
        })
        $evidenceComplete = $matchingStates.Count -eq 1 -and $null -ne $matchingStates[0].PSObject.Properties['InUse']
        if (-not $evidenceComplete) {
            $reasons.Add(('port_inventory_incomplete_{0}' -f $definition.Lane))
        }
        $inUse = $evidenceComplete -and [bool]$matchingStates[0].PSObject.Properties['InUse'].Value
        $publicPorts.Add([pscustomobject]@{
            lane = [string]$definition.Lane
            port = [int]$definition.Port
            inUse = [bool]$inUse
            evidenceComplete = [bool]$evidenceComplete
        })

        if ($Mode -eq 'Start' -and $inUse) {
            $reasons.Add(('port_in_use_{0}' -f $definition.Lane))
        }
        if ($Mode -eq 'ValidateOnly' -and -not $inUse) {
            $reasons.Add(('endpoint_unavailable_{0}' -f $definition.Lane))
        }
    }

    $publicStores = [System.Collections.Generic.List[object]]::new()
    foreach ($definition in $script:LaneDefinitions) {
        $modelsPath = [string]$pathsByLane[[string]$definition.Lane]
        try {
            $storeExists = [bool](& $StoreProbeProvider $modelsPath)
        }
        catch {
            $storeExists = $false
        }

        $publicStores.Add([pscustomobject]@{
            lane = [string]$definition.Lane
            storeKind = [string]$definition.StoreKind
            exists = [bool]$storeExists
        })
        if (-not $storeExists) {
            $reasons.Add(('model_store_missing_{0}' -f $definition.Lane))
        }
    }

    if ($Mode -eq 'ValidateOnly') {
        foreach ($definition in $script:LaneDefinitions) {
            $state = $publicPorts | Where-Object { $_.lane -eq $definition.Lane } | Select-Object -First 1
            if ($null -eq $state -or -not $state.inUse) {
                continue
            }

            try {
                $observedTags = @(& $TagInventoryProvider $definition.Lane $definition.Endpoint)
                $modelResult = Get-ModelChecks -LaneDefinition $definition -ObservedTags $observedTags
                foreach ($check in $modelResult.Checks) { $publicModels.Add($check) }
                foreach ($reason in $modelResult.Reasons) { $reasons.Add($reason) }
            }
            catch {
                $reasons.Add(('endpoint_query_failed_{0}' -f $definition.Lane))
            }
        }

        if (@($publicPorts | Where-Object inUse).Count -gt 0) {
            $reasons.Add('existing_daemon_provenance_unverified')
        }
    }

    $uniqueReasons = @($reasons | Select-Object -Unique)
    $public = [pscustomobject]@{
        schemaVersion = 1
        mode = $Mode
        verdict = if ($uniqueReasons.Count -eq 0) { 'PASS' } else { 'HOLD' }
        reasonCodes = $uniqueReasons
        gpuLanes = @($publicGpuLanes)
        ports = @($publicPorts)
        stores = @($publicStores)
        models = @($publicModels)
    }

    return [pscustomobject]@{
        Public = $public
        RawLanes = @($rawLanes)
        Definitions = @($script:LaneDefinitions)
    }
}

function Test-DesktopDualOllamaPreflight {
    [CmdletBinding()]
    param(
        [ValidateSet('ValidateOnly', 'Start')]
        [string]$Mode = 'ValidateOnly',
        [string]$FastModelsPath = $(if ([string]::IsNullOrWhiteSpace($env:OLLAMA_MODELS)) { Join-Path $env:USERPROFILE '.ollama\models' } else { $env:OLLAMA_MODELS }),
        [string]$MainModelsPath = 'E:\models',
        [scriptblock]$GpuInventoryProvider = { Get-DefaultGpuInventory },
        [scriptblock]$PortStateProvider = { param($Ports) Get-DefaultPortState -Ports $Ports },
        [scriptblock]$StoreProbeProvider = { param($Path) Test-DefaultStorePath -Path $Path },
        [scriptblock]$TagInventoryProvider = { param($Lane, $Endpoint) Get-DefaultTagInventory -Lane $Lane -Endpoint $Endpoint }
    )

    $context = New-DesktopDualOllamaContext `
        -Mode $Mode `
        -FastModelsPath $FastModelsPath `
        -MainModelsPath $MainModelsPath `
        -GpuInventoryProvider $GpuInventoryProvider `
        -PortStateProvider $PortStateProvider `
        -StoreProbeProvider $StoreProbeProvider `
        -TagInventoryProvider $TagInventoryProvider

    return $context.Public
}

function Get-SinglePortEvidence {
    param(
        [Parameter(Mandatory)][scriptblock]$PortStateProvider,
        [Parameter(Mandatory)][int]$Port
    )

    try {
        $states = @(& $PortStateProvider @($Port))
    }
    catch {
        return [pscustomobject]@{ Complete = $false; InUse = $false }
    }

    $matches = @($states | Where-Object {
        $portProperty = $_.PSObject.Properties['Port']
        $null -ne $portProperty -and [int]$portProperty.Value -eq $Port
    })
    $complete = $matches.Count -eq 1 -and $null -ne $matches[0].PSObject.Properties['InUse']
    return [pscustomobject]@{
        Complete = [bool]$complete
        InUse = [bool]($complete -and [bool]$matches[0].PSObject.Properties['InUse'].Value)
    }
}

function Invoke-DesktopDualOllamaSetup {
    [CmdletBinding()]
    param(
        [ValidateSet('ValidateOnly', 'Start')]
        [string]$Mode = 'ValidateOnly',
        [string]$FastModelsPath = $(if ([string]::IsNullOrWhiteSpace($env:OLLAMA_MODELS)) { Join-Path $env:USERPROFILE '.ollama\models' } else { $env:OLLAMA_MODELS }),
        [string]$MainModelsPath = 'E:\models',
        [scriptblock]$GpuInventoryProvider = { Get-DefaultGpuInventory },
        [scriptblock]$PortStateProvider = { param($Ports) Get-DefaultPortState -Ports $Ports },
        [scriptblock]$StoreProbeProvider = { param($Path) Test-DefaultStorePath -Path $Path },
        [scriptblock]$TagInventoryProvider = { param($Lane, $Endpoint) Get-DefaultTagInventory -Lane $Lane -Endpoint $Endpoint },
        [scriptblock]$Launcher = { param($Spec) Start-DefaultOllamaProcess -Spec $Spec },
        [scriptblock]$HealthProvider = { param($Lane, $Endpoint) Test-DefaultLaneHealth -Lane $Lane -Endpoint $Endpoint },
        [scriptblock]$ProcessOwnershipProvider = { param($Ownership, $Port) Get-DefaultProcessOwnership -Ownership $Ownership -Port $Port },
        [scriptblock]$Stopper = { param($Ownership) Stop-DefaultOwnedProcess -Ownership $Ownership }
    )

    $context = New-DesktopDualOllamaContext `
        -Mode $Mode `
        -FastModelsPath $FastModelsPath `
        -MainModelsPath $MainModelsPath `
        -GpuInventoryProvider $GpuInventoryProvider `
        -PortStateProvider $PortStateProvider `
        -StoreProbeProvider $StoreProbeProvider `
        -TagInventoryProvider $TagInventoryProvider

    if ($Mode -eq 'ValidateOnly') {
        $context.Public | Add-Member -NotePropertyName startedCount -NotePropertyValue 0
        $context.Public | Add-Member -NotePropertyName startedLanes -NotePropertyValue @()
        return $context.Public
    }

    $result = [pscustomobject]@{
        schemaVersion = 1
        mode = $Mode
        verdict = [string]$context.Public.verdict
        reasonCodes = @($context.Public.reasonCodes)
        gpuLanes = @($context.Public.gpuLanes)
        ports = @($context.Public.ports)
        stores = @($context.Public.stores)
        models = @()
        startedCount = 0
        startedLanes = @()
        portEvidencePhase = 'preflight'
        listenerOwnership = @()
    }
    if ($result.verdict -ne 'PASS') {
        return $result
    }

    $ownedProcesses = [System.Collections.Generic.List[object]]::new()
    $startedLanes = [System.Collections.Generic.List[string]]::new()
    $modelChecks = [System.Collections.Generic.List[object]]::new()
    $listenerOwnership = [System.Collections.Generic.List[object]]::new()
    $runtimeReasons = [System.Collections.Generic.List[string]]::new()
    $acceptedOwnershipByLane = @{}
    $launchComplete = $false

    try {
        foreach ($definition in $context.Definitions) {
            $rawLane = $context.RawLanes | Where-Object { $_.Definition.Lane -eq $definition.Lane } | Select-Object -First 1
            if ($null -eq $rawLane -or -not $rawLane.GpuUuidValid) {
                $runtimeReasons.Add(('gpu_context_invalid_{0}' -f $definition.Lane))
                break
            }

            $portEvidence = Get-SinglePortEvidence -PortStateProvider $PortStateProvider -Port ([int]$definition.Port)
            if (-not $portEvidence.Complete) {
                $runtimeReasons.Add(('port_recheck_incomplete_{0}' -f $definition.Lane))
                break
            }
            if ($portEvidence.InUse) {
                $runtimeReasons.Add(('port_race_{0}' -f $definition.Lane))
                break
            }

            $spec = [pscustomobject]@{
                Lane = [string]$definition.Lane
                Port = [int]$definition.Port
                Environment = @{
                    OLLAMA_HOST = [string]$definition.Host
                    OLLAMA_MODELS = [string]$rawLane.ModelsPath
                    CUDA_VISIBLE_DEVICES = [string]$rawLane.GpuUuid
                    OLLAMA_VULKAN = '0'
                }
            }

            try {
                $launchResult = & $Launcher $spec
                $processId = [int]$launchResult.ProcessId
                if ($processId -le 0) { throw 'invalid_process_id' }
                if ($null -eq $launchResult.PSObject.Properties['Lane']) {
                    $launchResult | Add-Member -NotePropertyName Lane -NotePropertyValue ([string]$definition.Lane)
                }
                if ($null -eq $launchResult.PSObject.Properties['Port']) {
                    $launchResult | Add-Member -NotePropertyName Port -NotePropertyValue ([int]$definition.Port)
                }
                $ownedProcesses.Add($launchResult)
                $startedLanes.Add([string]$definition.Lane)
            }
            catch {
                $runtimeReasons.Add(('lane_start_failed_{0}' -f $definition.Lane))
                break
            }

            $healthy = $false
            try { $healthy = [bool](& $HealthProvider $definition.Lane $definition.Endpoint) } catch { $healthy = $false }
            if (-not $healthy) {
                $runtimeReasons.Add(('lane_health_failed_{0}' -f $definition.Lane))
                break
            }

            try { $firstOwnership = & $ProcessOwnershipProvider $launchResult ([int]$definition.Port) } catch { $firstOwnership = $null }
            if ($null -eq $firstOwnership -or
                $null -eq $firstOwnership.PSObject.Properties['Verified'] -or
                -not [bool]$firstOwnership.Verified) {
                $runtimeReasons.Add(('listener_ownership_unverified_{0}' -f $definition.Lane))
                break
            }

            foreach ($propertyName in @('ListenerProcessId', 'ListenerStartTimeUtcTicks', 'ExecutableIdentityHash')) {
                if ($null -ne $firstOwnership.PSObject.Properties[$propertyName]) {
                    $targetName = if ($propertyName -eq 'ExecutableIdentityHash') { 'ListenerExecutableIdentityHash' } else { $propertyName }
                    $launchResult | Add-Member -NotePropertyName $targetName -NotePropertyValue $firstOwnership.$propertyName -Force
                }
            }

            try {
                $observedTags = @(& $TagInventoryProvider $definition.Lane $definition.Endpoint)
                $modelResult = Get-ModelChecks -LaneDefinition $definition -ObservedTags $observedTags
                foreach ($check in $modelResult.Checks) { $modelChecks.Add($check) }
                foreach ($reason in $modelResult.Reasons) { $runtimeReasons.Add($reason) }
            }
            catch {
                $runtimeReasons.Add(('endpoint_query_failed_{0}' -f $definition.Lane))
            }
            if ($runtimeReasons.Count -gt 0) { break }

            try { $secondOwnership = & $ProcessOwnershipProvider $launchResult ([int]$definition.Port) } catch { $secondOwnership = $null }
            if ($null -eq $secondOwnership -or -not (Test-SameListenerOwnership -First $firstOwnership -Second $secondOwnership)) {
                $runtimeReasons.Add(('listener_ownership_changed_{0}' -f $definition.Lane))
                break
            }

            $listenerOwnership.Add([pscustomobject]@{
                lane = [string]$definition.Lane
                verified = $true
                executableIdentityHash = [string]$secondOwnership.ExecutableIdentityHash
            })
            $acceptedOwnershipByLane[[string]$definition.Lane] = $secondOwnership
        }

        if ($runtimeReasons.Count -eq 0 -and $startedLanes.Count -eq $context.Definitions.Count) {
            foreach ($definition in $context.Definitions) {
                $ownedProcess = $ownedProcesses | Where-Object { [string]$_.Lane -eq [string]$definition.Lane } | Select-Object -First 1
                $acceptedOwnership = $acceptedOwnershipByLane[[string]$definition.Lane]
                $finalHealthy = $false
                try { $finalHealthy = [bool](& $HealthProvider $definition.Lane $definition.Endpoint) } catch { $finalHealthy = $false }
                if (-not $finalHealthy) {
                    $runtimeReasons.Add(('final_lane_health_failed_{0}' -f $definition.Lane))
                    break
                }

                try { $finalOwnership = & $ProcessOwnershipProvider $ownedProcess ([int]$definition.Port) } catch { $finalOwnership = $null }
                if ($null -eq $acceptedOwnership -or $null -eq $finalOwnership -or
                    -not (Test-SameListenerOwnership -First $acceptedOwnership -Second $finalOwnership)) {
                    $runtimeReasons.Add(('final_listener_ownership_changed_{0}' -f $definition.Lane))
                    break
                }
            }
        }

        $launchComplete = $runtimeReasons.Count -eq 0 -and $startedLanes.Count -eq $context.Definitions.Count
    }
    catch {
        $runtimeReasons.Add('unexpected_setup_failure')
    }
    finally {
        if (-not $launchComplete) {
            $cleanupSucceeded = $true
            for ($index = $ownedProcesses.Count - 1; $index -ge 0; $index--) {
                try {
                    if (-not [bool](& $Stopper $ownedProcesses[$index])) { $cleanupSucceeded = $false }
                }
                catch {
                    $cleanupSucceeded = $false
                }
            }
            if (-not $cleanupSucceeded) {
                $runtimeReasons.Add('owned_process_cleanup_failed')
            }
        }
    }

    if (-not $launchComplete) { $result.verdict = 'HOLD' }

    $result.reasonCodes = @($runtimeReasons | Select-Object -Unique)
    $result.models = @($modelChecks)
    $result.startedCount = $startedLanes.Count
    $result.startedLanes = @($startedLanes)
    $result.listenerOwnership = @($listenerOwnership)
    return $result
}

Export-ModuleMember -Function @(
    'Test-DesktopDualOllamaPreflight',
    'Invoke-DesktopDualOllamaSetup'
)
