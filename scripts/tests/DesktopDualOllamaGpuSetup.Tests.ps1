#requires -Version 5.1

BeforeDiscovery {
    $discoveryRepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $discoveryModulePath = Join-Path $discoveryRepoRoot 'scripts\modules\DesktopDualOllamaGpuSetup.psm1'
    Import-Module $discoveryModulePath -Force -ErrorAction Stop
}

BeforeAll {
    $script:RepoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    $script:ModulePath = Join-Path $script:RepoRoot 'scripts\modules\DesktopDualOllamaGpuSetup.psm1'
    Import-Module $script:ModulePath -Force -ErrorAction Stop

    $script:FastUuid = 'GPU-11111111-2222-3333-4444-555555555555'
    $script:MainUuid = 'GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'
    $script:FastStore = 'C:\Users\example\.ollama\models'
    $script:MainStore = 'E:\models'

    function New-HealthyGpuInventory {
        @(
            [pscustomobject]@{
                Name = 'NVIDIA GeForce RTX 3060'
                Uuid = $script:FastUuid
                MemoryMiB = 12288
                DriverVersion = '581.80'
            }
            [pscustomobject]@{
                Name = 'NVIDIA GeForce RTX 3090'
                Uuid = $script:MainUuid
                MemoryMiB = 24576
                DriverVersion = '581.80'
            }
        )
    }

    function New-ExpectedTags {
        param([string]$Lane)

        if ($Lane -eq 'fast') {
            return @(
                [pscustomobject]@{ Name = 'qwen3:8b'; Digest = '500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41' }
                [pscustomobject]@{ Name = 'qwen3.5:9b'; Digest = '6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7' }
                [pscustomobject]@{ Name = 'gemma4:12b'; Digest = '4eb23ef187e2c5462566d6a1d3bbbc2f1346d0b4327cbb66d58fffbcc9b2b05c' }
            )
        }

        return @(
            [pscustomobject]@{ Name = 'gemma4:26b'; Digest = '5571076f3d70050487b26b341705799e0ab29b808164f90d20d4cf84f699d251' }
            [pscustomobject]@{ Name = 'qwen3.6:27b'; Digest = 'a50eda8ed977ab48a12431878896b27ffd5cef552c17af3317d9623b939a7f1e' }
            [pscustomobject]@{ Name = 'gemma4:31b'; Digest = '6316f0629137b426c9d9b853ffc4c8209589f30ee39aebede6285096c0ff47e7' }
        )
    }

    function Invoke-PreflightWithHealthyFakes {
        param(
            [ValidateSet('ValidateOnly', 'Start')]
            [string]$Mode = 'Start',
            [scriptblock]$GpuInventoryProvider = { New-HealthyGpuInventory },
            [scriptblock]$PortStateProvider = { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) },
            [scriptblock]$StoreProbeProvider = { param($Path) $true },
            [scriptblock]$TagInventoryProvider = { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane }
        )

        Test-DesktopDualOllamaPreflight `
            -Mode $Mode `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider $GpuInventoryProvider `
            -PortStateProvider $PortStateProvider `
            -StoreProbeProvider $StoreProbeProvider `
            -TagInventoryProvider $TagInventoryProvider
    }
}

Describe 'Desktop dual Ollama GPU preflight' {
    It 'passes Start preflight only when both exact GPU lanes, stores, and free ports exist' {
        $result = Invoke-PreflightWithHealthyFakes

        $result.verdict | Should -Be 'PASS'
        $result.reasonCodes | Should -HaveCount 0
        $result.gpuLanes | Should -HaveCount 2
        $result.ports | Where-Object InUse | Should -BeNullOrEmpty
    }

    It 'holds when the RTX 3090 lane is missing' {
        $result = Invoke-PreflightWithHealthyFakes -GpuInventoryProvider {
            @([pscustomobject]@{
                Name = 'NVIDIA GeForce RTX 3060'
                Uuid = $script:FastUuid
                MemoryMiB = 12288
                DriverVersion = '581.80'
            })
        }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'gpu_missing_main'
    }

    It 'holds on a blank GPU UUID' {
        $result = Invoke-PreflightWithHealthyFakes -GpuInventoryProvider {
            $items = New-HealthyGpuInventory
            $items[0].Uuid = ''
            $items
        }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'gpu_uuid_invalid_fast'
    }

    It 'holds on a non-canonical or multiple GPU selector' {
        $result = Invoke-PreflightWithHealthyFakes -GpuInventoryProvider {
            $items = New-HealthyGpuInventory
            $items[1].Uuid = 'GPU-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee,GPU-other'
            $items
        }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'gpu_uuid_invalid_main'
    }

    It 'holds when both named lanes resolve to the same canonical UUID' {
        $result = Invoke-PreflightWithHealthyFakes -GpuInventoryProvider {
            $items = New-HealthyGpuInventory
            $items[1].Uuid = $items[0].Uuid
            $items
        }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'gpu_uuid_duplicate'
    }

    It 'holds Start mode when the GPU inventory command was incomplete' {
        $result = Invoke-PreflightWithHealthyFakes -GpuInventoryProvider {
            [pscustomobject]@{
                Items = @(New-HealthyGpuInventory)
                CommandSucceeded = $false
                TimedOut = $false
            }
        }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'gpu_inventory_command_failed'
    }

    It 'holds Start mode when either intended port is already occupied' {
        $result = Invoke-PreflightWithHealthyFakes -PortStateProvider {
            param($Ports)
            @(
                [pscustomobject]@{ Port = 11435; InUse = $false }
                [pscustomobject]@{ Port = 11434; InUse = $true }
            )
        }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'port_in_use_main'
    }

    It 'holds Start mode when port inventory omits an intended port' {
        $result = Invoke-PreflightWithHealthyFakes -PortStateProvider {
            param($Ports)
            @([pscustomobject]@{ Port = 11435; InUse = $false })
        }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'port_inventory_incomplete_main'
    }

    It 'holds Start mode when port inventory duplicates an intended port' {
        $result = Invoke-PreflightWithHealthyFakes -PortStateProvider {
            param($Ports)
            @(
                [pscustomobject]@{ Port = 11435; InUse = $false }
                [pscustomobject]@{ Port = 11435; InUse = $false }
                [pscustomobject]@{ Port = 11434; InUse = $false }
            )
        }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'port_inventory_incomplete_fast'
    }

    It 'holds when either process-scoped model store is missing' {
        $result = Invoke-PreflightWithHealthyFakes -StoreProbeProvider {
            param($Path)
            return $Path -ne $script:MainStore
        }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'model_store_missing_main'
    }

    It 'never exposes a raw GPU UUID or model-store path in public output' {
        $result = Invoke-PreflightWithHealthyFakes
        $json = $result | ConvertTo-Json -Depth 10 -Compress

        $json | Should -Not -Match 'GPU-[0-9A-Fa-f-]{36}'
        $json | Should -Not -Match [regex]::Escape($script:FastStore)
        $json | Should -Not -Match [regex]::Escape($script:MainStore)
        $result.gpuLanes[0].uuidHash | Should -Match '^[0-9a-f]{64}$'
    }

    It 'keeps ValidateOnly on HOLD for already-running daemons with unproven launch provenance' {
        $result = Invoke-PreflightWithHealthyFakes `
            -Mode ValidateOnly `
            -PortStateProvider {
                param($Ports)
                @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $true } })
            }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'existing_daemon_provenance_unverified'
        $result.models | Should -HaveCount 6
        ($result.models | Where-Object { -not $_.digestMatch }) | Should -BeNullOrEmpty
    }

    It 'keeps ValidateOnly structured when exactly one intended daemon is running' {
        $result = Invoke-PreflightWithHealthyFakes `
            -Mode ValidateOnly `
            -PortStateProvider {
                param($Ports)
                @(
                    [pscustomobject]@{ Port = 11435; InUse = $true }
                    [pscustomobject]@{ Port = 11434; InUse = $false }
                )
            }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'endpoint_unavailable_main'
        $result.reasonCodes | Should -Contain 'existing_daemon_provenance_unverified'
        $result.models | Should -HaveCount 3
    }

    It 'reports an exact digest mismatch without returning the observed digest' {
        $result = Invoke-PreflightWithHealthyFakes `
            -Mode ValidateOnly `
            -PortStateProvider {
                param($Ports)
                @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $true } })
            } `
            -TagInventoryProvider {
                param($Lane, $Endpoint)
                $tags = @(New-ExpectedTags -Lane $Lane)
                if ($Lane -eq 'main') { $tags[1].Digest = ('0' * 64) }
                $tags
            }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'model_digest_mismatch_main_qwen3_6_27b'
        ($result | ConvertTo-Json -Depth 10 -Compress) | Should -Not -Match ('0' * 64)
    }
}

Describe 'nvidia-smi inventory parsing' {
    InModuleScope DesktopDualOllamaGpuSetup {
        It 'preserves unsupported memory as unknown instead of zero' {
            $result = ConvertFrom-NvidiaSmiCsv -Lines @(
                'NVIDIA GeForce RTX 3060, GPU-11111111-2222-3333-4444-555555555555, N/A, 616.64'
            )
            $result[0].MemoryMiB | Should -BeNullOrEmpty
        }
        It 'keeps a valid GPU row when nvidia-smi also reports another lost GPU out of band' {
            $result = ConvertFrom-NvidiaSmiCsv -Lines @(
                'NVIDIA GeForce RTX 3060, GPU-11111111-2222-3333-4444-555555555555, 12288, 581.80',
                ''
            )

            $result | Should -HaveCount 1
            $result[0].Name | Should -Be 'NVIDIA GeForce RTX 3060'
            $result[0].Uuid | Should -Be 'GPU-11111111-2222-3333-4444-555555555555'
        }

        It 'rejects output with no valid canonical GPU row' {
            { ConvertFrom-NvidiaSmiCsv -Lines @('GPU is lost') } | Should -Throw 'gpu_inventory_unavailable'
        }

        It 'bounds a hung inventory command without blocking on stream tasks' {
            $powershellPath = (Get-Command powershell.exe -ErrorAction Stop).Path
            $stopwatch = [Diagnostics.Stopwatch]::StartNew()
            $result = Invoke-GpuInventoryProcess `
                -FilePath $powershellPath `
                -Arguments '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 30"' `
                -TimeoutMilliseconds 100
            $stopwatch.Stop()

            $result.TimedOut | Should -BeTrue
            $result.CommandSucceeded | Should -BeFalse
            $stopwatch.Elapsed.TotalSeconds | Should -BeLessThan 5
        }
    }
}

Describe 'Desktop dual Ollama serial launcher' {
    BeforeEach {
        $script:CapturedSpecs = [System.Collections.Generic.List[object]]::new()
        $script:StoppedProcessIds = [System.Collections.Generic.List[int]]::new()
        $script:NextPid = 4100
        $script:PortProviderCallCount = 0
        $script:OwnershipCallCount = 0
    }

    It 'launches fast then main with isolated process environments' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { New-HealthyGpuInventory } `
            -PortStateProvider { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher {
                param($Spec)
                $script:CapturedSpecs.Add($Spec)
                $script:NextPid += 1
                [pscustomobject]@{ ProcessId = $script:NextPid }
            } `
            -HealthProvider { param($Lane, $Endpoint) $true } `
            -ProcessOwnershipProvider {
                param($Ownership, $Port)
                [pscustomobject]@{
                    Verified = $true
                    ListenerProcessId = [int]$Ownership.ProcessId
                    ListenerStartTimeUtcTicks = 100
                    ExecutableIdentityHash = ('a' * 64)
                }
            } `
            -TagInventoryProvider { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane } `
            -Stopper { param($Ownership) $script:StoppedProcessIds.Add([int]$Ownership.ProcessId); $true }

        $result.verdict | Should -Be 'PASS'
        $result.startedLanes | Should -Be @('fast', 'main')
        $script:CapturedSpecs[0].Environment.OLLAMA_HOST | Should -Be '127.0.0.1:11435'
        $script:CapturedSpecs[0].Environment.CUDA_VISIBLE_DEVICES | Should -Be $script:FastUuid
        $script:CapturedSpecs[0].Environment.OLLAMA_MODELS | Should -Be $script:FastStore
        $script:CapturedSpecs[0].Environment.OLLAMA_VULKAN | Should -Be '0'
        $script:CapturedSpecs[1].Environment.OLLAMA_HOST | Should -Be '127.0.0.1:11434'
        $script:CapturedSpecs[1].Environment.CUDA_VISIBLE_DEVICES | Should -Be $script:MainUuid
        $script:CapturedSpecs[1].Environment.OLLAMA_MODELS | Should -Be $script:MainStore
        $script:CapturedSpecs[1].Environment.OLLAMA_VULKAN | Should -Be '0'
        $script:StoppedProcessIds | Should -HaveCount 0
    }

    It 'launches nothing when preflight is HOLD' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { @((New-HealthyGpuInventory)[0]) } `
            -PortStateProvider { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher { param($Spec) throw 'must not launch' } `
            -HealthProvider { param($Lane, $Endpoint) $true } `
            -TagInventoryProvider { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane }

        $result.verdict | Should -Be 'HOLD'
        $result.startedCount | Should -Be 0
    }

    It 'stops immediately and does not launch main when fast health fails' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { New-HealthyGpuInventory } `
            -PortStateProvider { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher {
                param($Spec)
                $script:CapturedSpecs.Add($Spec)
                [pscustomobject]@{ ProcessId = 4201 }
            } `
            -HealthProvider { param($Lane, $Endpoint) $false } `
            -TagInventoryProvider { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane } `
            -Stopper { param($Ownership) $script:StoppedProcessIds.Add([int]$Ownership.ProcessId); $true }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'lane_health_failed_fast'
        $script:CapturedSpecs | Should -HaveCount 1
        $script:StoppedProcessIds | Should -Be @(4201)
    }

    It 'stops every owned process when the main digest verification fails' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { New-HealthyGpuInventory } `
            -PortStateProvider { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher {
                param($Spec)
                $script:CapturedSpecs.Add($Spec)
                $script:NextPid += 1
                [pscustomobject]@{ ProcessId = $script:NextPid }
            } `
            -HealthProvider { param($Lane, $Endpoint) $true } `
            -ProcessOwnershipProvider {
                param($Ownership, $Port)
                [pscustomobject]@{
                    Verified = $true
                    ListenerProcessId = [int]$Ownership.ProcessId
                    ListenerStartTimeUtcTicks = 100
                    ExecutableIdentityHash = ('b' * 64)
                }
            } `
            -TagInventoryProvider {
                param($Lane, $Endpoint)
                $tags = @(New-ExpectedTags -Lane $Lane)
                if ($Lane -eq 'main') { $tags[1].Digest = ('f' * 64) }
                $tags
            } `
            -Stopper { param($Ownership) $script:StoppedProcessIds.Add([int]$Ownership.ProcessId); $true }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'model_digest_mismatch_main_qwen3_6_27b'
        $script:CapturedSpecs | Should -HaveCount 2
        $script:StoppedProcessIds | Should -Contain 4101
        $script:StoppedProcessIds | Should -Contain 4102
    }

    It 'redacts UUIDs and store paths from the public launch result' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { New-HealthyGpuInventory } `
            -PortStateProvider { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher { param($Spec) [pscustomobject]@{ ProcessId = 4300 + $Spec.Port } } `
            -HealthProvider { param($Lane, $Endpoint) $true } `
            -ProcessOwnershipProvider {
                param($Ownership, $Port)
                [pscustomobject]@{
                    Verified = $true
                    ListenerProcessId = [int]$Ownership.ProcessId
                    ListenerStartTimeUtcTicks = 100
                    ExecutableIdentityHash = ('c' * 64)
                }
            } `
            -TagInventoryProvider { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane }

        $json = $result | ConvertTo-Json -Depth 10 -Compress
        $json | Should -Not -Match 'GPU-[0-9A-Fa-f-]{36}'
        $json | Should -Not -Match [regex]::Escape($script:FastStore)
        $json | Should -Not -Match [regex]::Escape($script:MainStore)
    }

    It 'rechecks the lane port immediately before launch and holds on a race' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { New-HealthyGpuInventory } `
            -PortStateProvider {
                param($Ports)
                $script:PortProviderCallCount += 1
                if ($script:PortProviderCallCount -eq 1) {
                    return @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } })
                }
                return @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $true } })
            } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher { param($Spec) $script:CapturedSpecs.Add($Spec); [pscustomobject]@{ ProcessId = 4401 } } `
            -HealthProvider { param($Lane, $Endpoint) $true } `
            -TagInventoryProvider { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'port_race_fast'
        $script:CapturedSpecs | Should -HaveCount 0
    }

    It 'requires the launched process to own the expected listener' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { New-HealthyGpuInventory } `
            -PortStateProvider { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher { param($Spec) $script:CapturedSpecs.Add($Spec); [pscustomobject]@{ ProcessId = 4501 } } `
            -HealthProvider { param($Lane, $Endpoint) $true } `
            -ProcessOwnershipProvider { param($Ownership, $Port) [pscustomobject]@{ Verified = $false } } `
            -TagInventoryProvider { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane } `
            -Stopper { param($Ownership) $script:StoppedProcessIds.Add([int]$Ownership.ProcessId); $true }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'listener_ownership_unverified_fast'
        $script:CapturedSpecs | Should -HaveCount 1
        $script:StoppedProcessIds | Should -Contain 4501
    }

    It 'holds when listener ownership changes during digest verification' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { New-HealthyGpuInventory } `
            -PortStateProvider { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher { param($Spec) $script:CapturedSpecs.Add($Spec); [pscustomobject]@{ ProcessId = 4601 } } `
            -HealthProvider { param($Lane, $Endpoint) $true } `
            -ProcessOwnershipProvider {
                param($Ownership, $Port)
                $script:OwnershipCallCount += 1
                [pscustomobject]@{
                    Verified = $true
                    ListenerProcessId = if ($script:OwnershipCallCount -eq 1) { 4602 } else { 4603 }
                    ListenerStartTimeUtcTicks = 100
                    ExecutableIdentityHash = ('d' * 64)
                }
            } `
            -TagInventoryProvider { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane } `
            -Stopper { param($Ownership) $script:StoppedProcessIds.Add([int]$Ownership.ProcessId); $true }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'listener_ownership_changed_fast'
        $script:StoppedProcessIds | Should -Contain 4601
    }

    It 'reports cleanup failure instead of suppressing it' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { New-HealthyGpuInventory } `
            -PortStateProvider { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher { param($Spec) [pscustomobject]@{ ProcessId = 4701 } } `
            -HealthProvider { param($Lane, $Endpoint) $false } `
            -TagInventoryProvider { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane } `
            -Stopper { param($Ownership) $false }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'lane_health_failed_fast'
        $result.reasonCodes | Should -Contain 'owned_process_cleanup_failed'
    }

    It 'revalidates every accepted lane immediately before final PASS' {
        $result = Invoke-DesktopDualOllamaSetup `
            -Mode Start `
            -FastModelsPath $script:FastStore `
            -MainModelsPath $script:MainStore `
            -GpuInventoryProvider { New-HealthyGpuInventory } `
            -PortStateProvider { param($Ports) @($Ports | ForEach-Object { [pscustomobject]@{ Port = $_; InUse = $false } }) } `
            -StoreProbeProvider { param($Path) $true } `
            -Launcher {
                param($Spec)
                $script:CapturedSpecs.Add($Spec)
                $script:NextPid += 1
                [pscustomobject]@{ ProcessId = $script:NextPid }
            } `
            -HealthProvider { param($Lane, $Endpoint) $true } `
            -ProcessOwnershipProvider {
                param($Ownership, $Port)
                $script:OwnershipCallCount += 1
                $listenerPid = [int]$Ownership.ProcessId
                if ($script:OwnershipCallCount -eq 5) { $listenerPid += 100 }
                [pscustomobject]@{
                    Verified = $true
                    ListenerProcessId = $listenerPid
                    ListenerStartTimeUtcTicks = 100
                    ExecutableIdentityHash = ('e' * 64)
                }
            } `
            -TagInventoryProvider { param($Lane, $Endpoint) New-ExpectedTags -Lane $Lane } `
            -Stopper { param($Ownership) $script:StoppedProcessIds.Add([int]$Ownership.ProcessId); $true }

        $result.verdict | Should -Be 'HOLD'
        $result.reasonCodes | Should -Contain 'final_listener_ownership_changed_fast'
        $script:CapturedSpecs | Should -HaveCount 2
        $script:StoppedProcessIds | Should -Contain 4101
        $script:StoppedProcessIds | Should -Contain 4102
    }
}

Describe 'Ollama child output containment' {
    InModuleScope DesktopDualOllamaGpuSetup {
        It 'routes both child streams to NUL without placing environment values in command arguments' {
            $startInfo = New-NullRoutedProcessStartInfo `
                -FilePath 'C:\Program Files\Ollama\ollama.exe' `
                -Arguments 'serve' `
                -Environment @{ CUDA_VISIBLE_DEVICES = 'GPU-11111111-2222-3333-4444-555555555555'; OLLAMA_MODELS = 'E:\models' }

            $startInfo.UseShellExecute | Should -BeFalse
            $startInfo.CreateNoWindow | Should -BeTrue
            $startInfo.Arguments | Should -Match '1>NUL'
            $startInfo.Arguments | Should -Match '2>NUL'
            $startInfo.Arguments | Should -Not -Match 'GPU-11111111'
            $startInfo.Arguments | Should -Not -Match [regex]::Escape('E:\models')
        }
    }

    It 'emits one clean JSON document when a helper writes environment sentinels to both child streams' {
        $helperPath = Join-Path $TestDrive 'emit-sensitive-env.cmd'
        $driverPath = Join-Path $TestDrive 'run-null-routed-helper.ps1'
        Set-Content -LiteralPath $helperPath -Encoding Ascii -Value @(
            '@echo off',
            'echo %CUDA_VISIBLE_DEVICES%',
            'echo %OLLAMA_MODELS% 1>&2'
        )
        Set-Content -LiteralPath $driverPath -Encoding UTF8 -Value @'
$module = Import-Module $env:AWX_TEST_MODULE_PATH -Force -PassThru
$startInfo = & $module {
    param($helperPath)
    New-NullRoutedProcessStartInfo `
        -FilePath $helperPath `
        -Arguments 'ignored' `
        -Environment @{
            CUDA_VISIBLE_DEVICES = 'GPU-11111111-2222-3333-4444-555555555555'
            OLLAMA_MODELS = 'E:\models'
        }
} $env:AWX_TEST_HELPER_PATH
$process = [Diagnostics.Process]::Start($startInfo)
if ($null -eq $process -or -not $process.WaitForExit(5000)) { throw 'helper_timeout' }
$process.Dispose()
'{"verdict":"PASS"}'
'@

        $powershellPath = (Get-Command powershell.exe -ErrorAction Stop).Path
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $powershellPath
        $startInfo.Arguments = ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}"' -f $driverPath)
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.EnvironmentVariables['AWX_TEST_MODULE_PATH'] = $script:ModulePath
        $startInfo.EnvironmentVariables['AWX_TEST_HELPER_PATH'] = $helperPath
        $process = [Diagnostics.Process]::Start($startInfo)
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit(10000) | Should -BeTrue
        $output = $stdoutTask.GetAwaiter().GetResult() + $stderrTask.GetAwaiter().GetResult()
        $process.ExitCode | Should -Be 0
        $process.Dispose()

        $output | Should -Not -Match 'GPU-11111111-2222'
        $output | Should -Not -Match [regex]::Escape('E:\models')
        ($output.Trim() | ConvertFrom-Json).verdict | Should -Be 'PASS'
    }

    It 'closes the CLI output stream after its launcher exits while the supervised child remains alive' {
        $helperPath = Join-Path $env:SystemRoot 'System32\ping.exe'
        $driverPath = Join-Path $TestDrive 'run-long-lived-null-routed-helper.ps1'
        $pidPath = Join-Path $TestDrive 'long-running-supervisor.pid'
        Set-Content -LiteralPath $driverPath -Encoding UTF8 -Value @'
$module = Import-Module $env:AWX_TEST_MODULE_PATH -Force -PassThru
$process = & $module {
    param($helperPath)
    Start-DefaultIsolatedProcess -FilePath $helperPath -Arguments '127.0.0.1 -n 16' -Environment @{}
} $env:AWX_TEST_HELPER_PATH
if ($null -eq $process) { throw 'helper_start_failed' }
Set-Content -LiteralPath $env:AWX_TEST_PID_PATH -Encoding Ascii -Value ([string]$process.Id)
$process.Dispose()
'{"verdict":"PASS"}'
'@

        $powershellPath = (Get-Command powershell.exe -ErrorAction Stop).Path
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.FileName = $powershellPath
        $startInfo.Arguments = ('-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "{0}"' -f $driverPath)
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.EnvironmentVariables['AWX_TEST_MODULE_PATH'] = $script:ModulePath
        $startInfo.EnvironmentVariables['AWX_TEST_HELPER_PATH'] = $helperPath
        $startInfo.EnvironmentVariables['AWX_TEST_PID_PATH'] = $pidPath
        $process = [Diagnostics.Process]::Start($startInfo)
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        try {
            $process.WaitForExit(5000) | Should -BeTrue
            $stdoutTask.Wait(3000) | Should -BeTrue
            $stderrTask.Wait(3000) | Should -BeTrue
            (($stdoutTask.GetAwaiter().GetResult() + $stderrTask.GetAwaiter().GetResult()).Trim() | ConvertFrom-Json).verdict | Should -Be 'PASS'
        }
        finally {
            if (Test-Path -LiteralPath $pidPath) {
                $supervisorPid = [int](Get-Content -LiteralPath $pidPath -Raw)
                $childPids = @(Get-CimInstance Win32_Process -Filter ("ParentProcessId = {0}" -f $supervisorPid) -ErrorAction SilentlyContinue | Select-Object -ExpandProperty ProcessId)
                foreach ($childPid in $childPids) { Stop-Process -Id $childPid -Force -ErrorAction SilentlyContinue }
                Stop-Process -Id $supervisorPid -Force -ErrorAction SilentlyContinue
            }
            if (-not $process.HasExited) { $process.Kill() }
            $process.Dispose()
        }
    }
}

Describe 'owned process cleanup identity' {
    InModuleScope DesktopDualOllamaGpuSetup {
        It 'stops the exact process identity it owns' {
            $startInfo = [Diagnostics.ProcessStartInfo]::new()
            $startInfo.FileName = (Get-Command powershell.exe -ErrorAction Stop).Path
            $startInfo.Arguments = '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 30"'
            $startInfo.UseShellExecute = $false
            $startInfo.CreateNoWindow = $true
            $process = [Diagnostics.Process]::Start($startInfo)
            try {
                $identity = Get-ProcessIdentity -ProcessId $process.Id
                $ownership = [pscustomobject]@{
                    SupervisorProcessId = $identity.ProcessId
                    SupervisorStartTimeUtcTicks = $identity.StartTimeUtcTicks
                    SupervisorExecutableIdentityHash = $identity.ExecutableIdentityHash
                }

                (Stop-DefaultOwnedProcess -Ownership $ownership) | Should -BeTrue
                (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) | Should -BeNullOrEmpty
            }
            finally {
                if (-not $process.HasExited) { $process.Kill() }
                $process.Dispose()
            }
        }

        It 'refuses to stop a PID when the recorded start time does not match' {
            $startInfo = [Diagnostics.ProcessStartInfo]::new()
            $startInfo.FileName = (Get-Command powershell.exe -ErrorAction Stop).Path
            $startInfo.Arguments = '-NoProfile -NonInteractive -Command "Start-Sleep -Seconds 30"'
            $startInfo.UseShellExecute = $false
            $startInfo.CreateNoWindow = $true
            $process = [Diagnostics.Process]::Start($startInfo)
            try {
                $identity = Get-ProcessIdentity -ProcessId $process.Id
                $ownership = [pscustomobject]@{
                    SupervisorProcessId = $identity.ProcessId
                    SupervisorStartTimeUtcTicks = $identity.StartTimeUtcTicks + 1
                    SupervisorExecutableIdentityHash = $identity.ExecutableIdentityHash
                }

                (Stop-DefaultOwnedProcess -Ownership $ownership) | Should -BeFalse
                (Get-Process -Id $process.Id -ErrorAction SilentlyContinue) | Should -Not -BeNullOrEmpty
            }
            finally {
                if (-not $process.HasExited) { $process.Kill() }
                $process.Dispose()
            }
        }
    }
}

Describe 'CLI safety gate' {
    It 'requires explicit cold-reboot acknowledgement before Start' {
        $cliPath = Join-Path $script:RepoRoot 'scripts\desktop_dual_ollama_gpu_setup.ps1'
        $raw = & powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -File $cliPath -Mode Start 2>&1
        $exitCode = $LASTEXITCODE
        $report = (($raw | Out-String).Trim()) | ConvertFrom-Json

        $exitCode | Should -Be 2
        $report.verdict | Should -Be 'HOLD'
        $report.reasonCodes | Should -Contain 'cold_reboot_gate_not_acknowledged'
        $report.startedCount | Should -Be 0
    }
}
