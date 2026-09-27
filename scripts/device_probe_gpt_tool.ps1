param(
    [Parameter(Mandatory = $false)]
    [ValidateSet("auto", "desktop", "notebook", "server")]
    [string]$Role = "auto",

    [Parameter(Mandatory = $false)]
    [string]$OutputRoot = "$env:USERPROFILE\gpt_device_probe",

    [Parameter(Mandatory = $false)]
    [string[]]$RepoPaths = @((Get-Location).Path),

    [Parameter(Mandatory = $false)]
    [int[]]$ProbePorts = @(22, 80, 443, 3306, 5432, 27017, 6379, 8080, 8081, 11434, 11435, 11436),

    [Parameter(Mandatory = $false)]
    [string[]]$DbServicePatterns = @("postgresql*", "postgres*", "mysqld*", "mariadb*", "redis*", "redis-server*", "mongodb*", "mongo*", "rabbitmq*", "sqlserver*", "elasticsearch*", "qdrant*", "weaviate*", "milvus*", "pgbouncer*", "valkey*"),

    [Parameter(Mandatory = $false)]
    [string[]]$ConfigFileGlobs = @(
        ".env*",
        "application*.yml",
        "application*.yaml",
        "application*.properties",
        "main\\resources\\application*.yml",
        "main\\resources\\application*.yaml",
        "main\\resources\\application*.properties"
    ),

    [Parameter(Mandatory = $false)]
    [string[]]$WatchProcessNames = @("java", "javaw", "ollama", "gradle", "gradlew", "node", "npm", "pnpm"),

    [Parameter(Mandatory = $false)]
    [string]$ShareRoot,

    [Parameter(Mandatory = $false)]
    [string]$WriterId = $env:COMPUTERNAME,

    [Parameter(Mandatory = $false)]
    [int]$ShareLockTtlMinutes = 8,

    [Parameter(Mandatory = $false)]
    [switch]$PublishToShare
)

$ErrorActionPreference = "Stop"

$script:DeviceProbeSchemaVersion = "device_probe_gpt_tool_v3"
$script:ShareSchemaVersion = "device_probe_share_v1"
$script:ShareLockSchemaVersion = "device_probe_share_lock_v2"

function Resolve-Role {
    param([Parameter(Mandatory)][string]$RoleInput)

    if ($RoleInput -ne "auto") {
        return $RoleInput
    }

    $computer = [Environment]::MachineName
    if ($computer -match "(?i)(nb|note|notebook|laptop)") {
        return "notebook"
    }
    return "desktop"
}

function Ensure-Directory {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function To-GB {
    param([double]$Bytes)
    if ($Bytes -le 0) { return 0.0 }
    return [Math]::Round($Bytes / 1GB, 2)
}

function New-Hash {
    param([Parameter(Mandatory)][string]$Text)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash([Text.Encoding]::UTF8.GetBytes($Text))
    return [BitConverter]::ToString($hash).Replace("-", "").ToLowerInvariant()
}

function New-AwxHash12 {
    param([Parameter(Mandatory)][string]$Text)
    $full = New-Hash -Text $Text
    return $full.Substring(0, 12)
}

function Is-SensitiveKey {
    param([Parameter(Mandatory)][string]$Name)
    return $Name -match "(?i)(api[_-]?key|token|secret|password|passwd|pwd|authorization|auth|bearer|credential|conn(ection)?string|jdbc|endpoint|private|client[_-]?secret|access[_-]?key|refresh[_-]?token|api[_-]?secret)"
}

function Redact-Value {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][AllowNull()][string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value)) { return "" }
    $label = if (Is-SensitiveKey -Name $Name) { "REDACTED" } else { "METADATA_ONLY" }
    return "<$label len=$($Value.Length) sha12=$((New-AwxHash12 -Text $Value))>"
}

function Get-FingerprintInfo {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][AllowNull()][AllowEmptyString()][string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value)) {
        return [ordered]@{
            exists = $false
            length = 0
            value_fingerprint = $null
            masked = $null
        }
    }

    $fingerprint = New-Hash -Text $Value
    return [ordered]@{
        exists = $true
        length = $Value.Length
        value_fingerprint = $fingerprint
        masked = Redact-Value -Name $Name -Value $Value
    }
}

function Write-AtomicText {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Text
    )

    $dir = [IO.Path]::GetDirectoryName($Path)
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        Ensure-Directory -Path $dir
    }

    $tmp = Join-Path $dir (".{0}.tmp.{1}" -f ([IO.Path]::GetFileName($Path), [guid]::NewGuid()))
    [IO.File]::WriteAllText($tmp, $Text, (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $tmp -Destination $Path -Force
}

function Write-AtomicJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Object,
        [Parameter(Mandatory)][int]$Depth
    )

    Write-AtomicText -Path $Path -Text ($Object | ConvertTo-Json -Depth $Depth)
}

function New-LockObject {
    param([string]$Owner, [string]$Purpose, [int]$TtlMinutes)
    $now = Get-Date
    $expires = $now.AddMinutes($TtlMinutes)

    return [ordered]@{
        owner = $Owner
        purpose = $Purpose
        created_at = $now.ToString("o")
        expires_at = $expires.ToString("o")
        schema_version = $script:ShareLockSchemaVersion
        pid = $PID
    }
}

function Read-LockObject {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }

    try {
        $raw = Get-Content -LiteralPath $Path -Raw
        return $raw | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Is-LockExpired {
    param($Lock)
    if ($null -eq $Lock -or -not $Lock.expires_at) { return $true }
    $exp = [datetime]::MinValue
    if ([datetime]::TryParse($Lock.expires_at, [ref]$exp)) {
        return ((Get-Date) -gt $exp)
    }
    return $true
}

function Remove-LockIfOwned {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Owner
    )

    $existing = Read-LockObject -Path $Path
    if ($null -eq $existing -or $existing.owner -ne $Owner) { return }
    Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
}

function Enter-ShareLock {
    param(
        [Parameter(Mandatory)][string]$ShareRoot,
        [Parameter(Mandatory)][string]$Owner,
        [Parameter(Mandatory)][string]$Purpose,
        [Parameter(Mandatory)][int]$TtlMinutes
    )

    $lockDir = Join-Path $ShareRoot "locks"
    Ensure-Directory -Path $lockDir
    $safePurpose = $Purpose -replace "[^A-Za-z0-9_-]", "_"
    $lockPath = Join-Path $lockDir "$safePurpose.lock.json"

    $active = Read-LockObject -Path $lockPath
    if ($null -ne $active -and -not (Is-LockExpired -Lock $active) -and $active.owner -ne $Owner) {
        throw "share-lock-active owner=$($active.owner) purpose=$($active.purpose)"
    }

    if ($null -ne $active -and (Is-LockExpired -Lock $active)) {
        $quarantine = Join-Path $lockDir ("stale.$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()).json")
        Move-Item -LiteralPath $lockPath -Destination $quarantine -ErrorAction SilentlyContinue
    }

    $new = New-LockObject -Owner $Owner -Purpose $Purpose -TtlMinutes $TtlMinutes
    Write-AtomicJson -Path $lockPath -Object $new -Depth 12
    return $lockPath
}

function Get-RepoBuildHealth {
    param([Parameter(Mandatory)][string]$Path)

    $buildRoot = Join-Path $Path "build"
    if (-not (Test-Path -LiteralPath $buildRoot)) {
        return [ordered]@{
            hasBuildDirectory = $false
            hasGradleArtifacts = $false
            latestArtifact = $null
            recentArtifactCount = 0
            latestBootJar = $null
            latestTestResult = $null
        }
    }

    try {
        $artifacts = @(
            Get-ChildItem -Path $buildRoot -Recurse -File -Filter *.jar -ErrorAction SilentlyContinue |
                Where-Object { $_.FullName -notmatch "\\gradle\\" }
        )
        $latestArtifact = $artifacts | Sort-Object LastWriteTime -Descending | Select-Object -First 1
        $bootJars = @($artifacts | Where-Object { $_.Name -match "boot|jar" })
        $latestBoot = $bootJars | Sort-Object LastWriteTime -Descending | Select-Object -First 1

        $testCandidates = @(
            Join-Path $Path "build\test-results\test"
            Join-Path $Path "build\reports\tests\test"
        )
        $testFiles = @()
        foreach ($candidate in $testCandidates) {
            if (Test-Path -LiteralPath $candidate) {
                $testFiles += Get-ChildItem -Path $candidate -Recurse -File -ErrorAction SilentlyContinue
            }
        }
        $latestTest = @($testFiles | Sort-Object LastWriteTime -Descending | Select-Object -First 1)

        return [ordered]@{
            hasBuildDirectory = $true
            hasGradleArtifacts = ($artifacts.Count -gt 0)
            recentArtifactCount = $artifacts.Count
            latestArtifact = if ($latestArtifact) {
                [ordered]@{
                    path = $latestArtifact.FullName
                    lastWriteTime = $latestArtifact.LastWriteTime.ToString("o")
                    size = $latestArtifact.Length
                }
            } else { $null }
            latestBootJar = if ($latestBoot) {
                [ordered]@{
                    path = $latestBoot.FullName
                    lastWriteTime = $latestBoot.LastWriteTime.ToString("o")
                }
            } else { $null }
            latestTestResult = if ($latestTest.Count -gt 0) {
                [ordered]@{
                    path = $latestTest[0].FullName
                    lastWriteTime = $latestTest[0].LastWriteTime.ToString("o")
                }
            } else { $null }
        }
    } catch {
        return [ordered]@{
            hasBuildDirectory = $true
            hasGradleArtifacts = $false
            latestArtifact = $null
            recentArtifactCount = 0
            latestBootJar = $null
            latestTestResult = $null
            collectError = $_.Exception.Message
        }
    }
}

function Get-RepoFacts {
    param([Parameter(Mandatory)][AllowNull()][string]$Path)

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return [ordered]@{
            path = $null
            inRepo = $false
            reason = "missing_path"
            gitStatusCode = 1
            branch = $null
            head = $null
            remote = $null
            aheadBehind = $null
            totalChanges = 0
            stagedOrModified = 0
            untracked = 0
            indexLock = $false
            gitDir = $null
            patchDropTopLevel = 0
            patchDropPending = 0
            build = Get-RepoBuildHealth -Path $Path
        }
    }

    if (-not (Test-Path -LiteralPath $Path)) {
        return [ordered]@{
            path = $Path
            inRepo = $false
            reason = "missing_path"
            build = Get-RepoBuildHealth -Path $Path
        }
    }

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        return [ordered]@{
            path = $Path
            inRepo = $false
            reason = "git_missing"
            build = Get-RepoBuildHealth -Path $Path
        }
    }

    if (-not (Test-Path -LiteralPath (Join-Path $Path ".git") -PathType Container)) {
        return [ordered]@{
            path = $Path
            inRepo = $false
            reason = "not_git_root"
            build = Get-RepoBuildHealth -Path $Path
        }
    }

    $status = git -C $Path status --short 2>$null
    if ($LASTEXITCODE -ne 0) { $status = @() }

    $branchRaw = (git -C $Path rev-parse --abbrev-ref HEAD 2>$null)
    $headRaw = (git -C $Path rev-parse --short HEAD 2>$null)
    $remoteRaw = (git -C $Path rev-parse --abbrev-ref "@{u}" 2>$null)

    $branch = if ($branchRaw) { [string]$branchRaw.Trim() } else { "" }
    $head = if ($headRaw) { [string]$headRaw.Trim() } else { "" }
    $remote = if ($remoteRaw) { [string]$remoteRaw.Trim() } else { "" }
    $aheadBehindRaw = if ($remote) {
        (git -C $Path rev-list --left-right --count "$remote...HEAD" 2>$null)
    } else { "" }
    $aheadBehind = if ($aheadBehindRaw) { [string]$aheadBehindRaw.Trim() } else { "" }

    $indexLock = Test-Path -LiteralPath (Join-Path $Path ".git\index.lock")
    $gitDir = (git -C $Path rev-parse --git-dir 2>$null).Trim()

    $stagedOrModified = 0
    $untracked = 0
    if ($status) {
        $stagedOrModified = @($status | Where-Object { $_ -notlike "??*" }).Count
        $untracked = @($status | Where-Object { $_ -like "??*" }).Count
    }

    $patchDrop = Join-Path $Path "__patch_drop__"
    $patchDropTopLevel = 0
    $patchDropPending = 0
    if (Test-Path -LiteralPath $patchDrop) {
        $patchDropTopLevel = (Get-ChildItem -LiteralPath $patchDrop -Filter "*.v3.patch" -ErrorAction SilentlyContinue).Count
        if (Test-Path -LiteralPath (Join-Path $patchDrop "orphan")) {
            $patchDropPending += (Get-ChildItem -LiteralPath (Join-Path $patchDrop "orphan") -Filter "*.patch" -Recurse -ErrorAction SilentlyContinue).Count
        }
        if (Test-Path -LiteralPath (Join-Path $patchDrop "pending")) {
            $patchDropPending += (Get-ChildItem -LiteralPath (Join-Path $patchDrop "pending") -Filter "*.patch" -Recurse -ErrorAction SilentlyContinue).Count
        }
    }

    return [ordered]@{
        path = $Path
        inRepo = $true
        gitStatusCode = $LASTEXITCODE
        branch = if ($branch) { $branch } else { "detached_or_unknown" }
        head = if ($head) { $head } else { "unknown" }
        remote = if ($remote) { $remote } else { $null }
        aheadBehind = if ($aheadBehind) { $aheadBehind } else { $null }
        totalChanges = if ($status) { $status.Count } else { 0 }
        stagedOrModified = $stagedOrModified
        untracked = $untracked
        indexLock = $indexLock
        gitDir = $gitDir
        patchDropTopLevel = $patchDropTopLevel
        patchDropPending = $patchDropPending
        build = Get-RepoBuildHealth -Path $Path
    }
}

function Get-ServiceFacts {
    param(
        [Parameter(Mandatory)][int[]]$ProbePorts,
        [Parameter(Mandatory)][string[]]$DbServicePatterns
    )

    $services = @()
    foreach ($pattern in $DbServicePatterns) {
        $services += Get-Service -Name $pattern -ErrorAction SilentlyContinue | ForEach-Object {
            [ordered]@{
                service = $_.Name
                display = $_.DisplayName
                status = $_.Status.ToString()
                startType = $_.StartType.ToString()
            }
        }
    }
    $services = @($services | Sort-Object service -Unique)

    $listen = @{}
    try {
        $all = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue
        foreach ($p in @($ProbePorts | Sort-Object -Unique)) {
            $listener = @($all | Where-Object { [int]$_.LocalPort -eq $p })
            $ownerPid = if ($listener.Count -gt 0 -and $listener[0].OwningProcess -gt 0) { [int]$listener[0].OwningProcess } else { 0 }
            $owner = if ($ownerPid -gt 0) { try { (Get-Process -Id $ownerPid -ErrorAction SilentlyContinue).ProcessName } catch { $null } } else { $null }

            $listen["$p"] = [ordered]@{
                port = $p
                open = ($listener.Count -gt 0)
                ownerPid = $ownerPid
                ownerProcess = $owner
                addressCount = @($listener | Where-Object { $_.LocalAddress }) | Select-Object -ExpandProperty LocalAddress -Unique | Measure-Object | Select-Object -ExpandProperty Count
            }
        }
    } catch {
        foreach ($p in @($ProbePorts | Sort-Object -Unique)) {
            $listen["$p"] = [ordered]@{
                port = $p
                open = $false
                ownerPid = 0
                ownerProcess = $null
                addressCount = 0
            }
        }
    }

    $endpointRoutes = @(
        "health",
        "actuator/health",
        "healthz"
    )
    $serverEndpoints = @()
    foreach ($entry in @($listen.Values)) {
        if (-not $entry.open) { continue }
        foreach ($route in @($endpointRoutes)) {
            $endpoint = "http://127.0.0.1:$($entry.port)/$route"
            $start = Get-Date
            try {
                $resp = Invoke-WebRequest -Uri $endpoint -UseBasicParsing -TimeoutSec 2 -MaximumRedirection 3
                $serverEndpoints += [ordered]@{
                    port = $entry.port
                    route = "/$route"
                    reachable = $true
                    statusCode = [int]$resp.StatusCode
                    statusPhrase = $resp.StatusDescription
                    elapsedMs = [int]((Get-Date)-$start).TotalMilliseconds
                }
            } catch {
                $serverEndpoints += [ordered]@{
                    port = $entry.port
                    route = "/$route"
                    reachable = $false
                    statusCode = 0
                    statusPhrase = $_.Exception.Message
                    elapsedMs = [int]((Get-Date)-$start).TotalMilliseconds
                }
            }
        }
    }

    return [ordered]@{
        dbServices = @($services)
        listeningPorts = @($listen.Values | Sort-Object port)
        endpoints = @($serverEndpoints)
        probePorts = @($ProbePorts | Sort-Object -Unique)
    }
}

function Get-ProcessFacts {
    param(
        [Parameter(Mandatory)][string[]]$WatchNames
    )

    $processes = @()
    foreach ($name in $WatchNames) {
        $match = Get-Process -Name $name -ErrorAction SilentlyContinue
        foreach ($p in $match) {
            $cpu = $null
            try { $cpu = [Math]::Round($p.CPU, 2) } catch {}
            try {
                $startTime = $p.StartTime.ToString("o")
            } catch {
                $startTime = $null
            }
            try {
                $procPath = $p.Path
            } catch {
                $procPath = $null
            }
            $wsGb = if ($p.WorkingSet64) { To-GB $p.WorkingSet64 } else { $null }
            $processes += [ordered]@{
                name = $p.ProcessName
                id = $p.Id
                cpuTime = $cpu
                workingSetGB = $wsGb
                startTime = $startTime
                path = $procPath
            }
        }
    }

    return @($processes | Sort-Object name, id -Unique)
}

function Get-HardwareFacts {
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $disks = Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" | ForEach-Object {
        [ordered]@{
            device = $_.DeviceID
            filesystem = $_.FileSystem
            size_gb = To-GB $_.Size
            free_gb = To-GB $_.FreeSpace
            free_pct = if ($_.Size -gt 0) { [Math]::Round(($_.FreeSpace / $_.Size) * 100, 2) } else { $null }
        }
    }

    $cpuLoad = $null
    try {
        $sample = Get-Counter '\\Processor(_Total)\\% Processor Time' -MaxSamples 1 -ErrorAction SilentlyContinue
        if ($sample) { $cpuLoad = [Math]::Round($sample.CounterSamples.CookedValue, 2) }
    } catch {}

    $gpus = @()
    foreach ($card in (Get-CimInstance Win32_VideoController)) {
        $gpus += [ordered]@{
            source = "WMI"
            name = $card.Name
            driverVersion = $card.DriverVersion
            videoProcessor = $card.VideoProcessor
            adapterRamMB = if ($card.AdapterRAM) { [Math]::Round([double]$card.AdapterRAM / 1MB, 2) } else { $null }
            status = $card.Status
        }
    }

    if (Get-Command nvidia-smi -ErrorAction SilentlyContinue) {
        try {
            $raw = & nvidia-smi --query-gpu=name,driver_version,temperature.gpu,utilization.gpu,utilization.memory,memory.total,memory.used,power.draw,power.limit --format=csv,noheader,nounits 2>$null
            $idx = 0
            foreach ($line in @($raw)) {
                if ([string]::IsNullOrWhiteSpace($line)) { continue }
                $parts = $line -split ',\s*'
                if ($parts.Count -lt 9) { continue }
                $temperatureC = try { [int]$parts[2] } catch { $null }
                $gpuUtilPct = try { [int]$parts[3] } catch { $null }
                $memUtilPct = try { [int]$parts[4] } catch { $null }
                $totalMemoryMB = try { [double]$parts[5] } catch { $null }
                $usedMemoryMB = try { [double]$parts[6] } catch { $null }
                $powerDrawW = try { [double]$parts[7] } catch { $null }
                $powerLimitW = try { [double]$parts[8] } catch { $null }
                $gpus += [ordered]@{
                    source = "nvidia-smi"
                    index = $idx
                    name = $parts[0]
                    driverVersion = $parts[1]
                    temperatureC = $temperatureC
                    gpuUtilPct = $gpuUtilPct
                    memUtilPct = $memUtilPct
                    totalMemoryMB = $totalMemoryMB
                    usedMemoryMB = $usedMemoryMB
                    powerDrawW = $powerDrawW
                    powerLimitW = $powerLimitW
                }
                $idx++
            }
        } catch {}
    }

    return [ordered]@{
        os = [ordered]@{
            caption = $os.Caption
            version = $os.Version
            build = $os.BuildNumber
            bootTimeUtc = $os.LastBootUpTime.ToString("o")
            bootUptimeMinutes = if ($os.LastBootUpTime) { [Math]::Round(((Get-Date) - $os.LastBootUpTime).TotalMinutes, 1) } else { $null }
        }
        cpu = [ordered]@{
            name = $cpu.Name
            cores = [int]$cpu.NumberOfCores
            logical = [int]$cpu.NumberOfLogicalProcessors
            maxClockMhz = $cpu.MaxClockSpeed
            currentLoadPercent = $cpuLoad
        }
        memory = [ordered]@{
            total_gb = To-GB ($os.TotalVisibleMemorySize * 1KB)
            free_gb = To-GB ($os.FreePhysicalMemory * 1KB)
            used_percent = if ($os.TotalVisibleMemorySize -and $os.FreePhysicalMemory) {
                [Math]::Round((($os.TotalVisibleMemorySize - $os.FreePhysicalMemory) / $os.TotalVisibleMemorySize) * 100, 2)
            } else { $null }
        }
        disks = @($disks)
        gpus = @($gpus)
    }
}

function Get-NetworkFacts {
    $dns = Get-DnsClientServerAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -ExpandProperty ServerAddresses
    $interfaces = Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred -ErrorAction SilentlyContinue | Select-Object InterfaceAlias, IPAddress, PrefixLength
    $gateways = Get-NetRoute -DestinationPrefix "0.0.0.0/0" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty NextHop
    $topProcesses = Get-Process | Sort-Object WS -Descending | Select-Object -First 6 -Property Name, Id, CPU, WS
    $topCpu = Get-Process | Sort-Object CPU -Descending | Select-Object -First 6 -Property Name, Id, CPU

    return [ordered]@{
        dnsServers = @($dns)
        interfaces = @($interfaces)
        gateways = @($gateways | Where-Object { $_ -and $_ -ne '0.0.0.0' })
        topCpuProcesses = @($topCpu)
        topMemoryProcesses = @($topProcesses)
        timeZone = (Get-TimeZone).Id
        culture = (Get-Culture).Name
        user = $env:USERNAME
        domain = $env:USERDOMAIN
        macAddresses = @((Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Select-Object -ExpandProperty MacAddress | Where-Object { $_ } | ForEach-Object { "sha12:$((New-AwxHash12 -Text ([string]$_)))" }))
        macAddressCount = @((Get-NetAdapter -Physical -ErrorAction SilentlyContinue | Select-Object -ExpandProperty MacAddress | Where-Object { $_ })).Count
    }
}

function Get-ConfigFacts {
    param(
        [Parameter(Mandatory)][string[]]$RepoPaths,
        [Parameter(Mandatory)][string[]]$Globs
    )

    $normalizedGlobs = @()
    foreach ($g in $Globs) {
        if ([string]::IsNullOrWhiteSpace($g)) { continue }
        if ($g.Trim().ToUpperInvariant() -eq "NONE") { continue }
        $normalizedGlobs += $g
    }
    if ($normalizedGlobs.Count -eq 0) {
        return [ordered]@{
            filesScanned = 0
            files = @()
            dbLikeTotal = 0
        }
    }

    $candidatePaths = New-Object System.Collections.Generic.HashSet[string]
    foreach ($repo in $RepoPaths) {
        if (-not (Test-Path -LiteralPath $repo)) { continue }
        foreach ($glob in $normalizedGlobs) {
            try {
                Get-ChildItem -Path $repo -Recurse -File -Filter $glob -ErrorAction SilentlyContinue |
                    ForEach-Object { [void]$candidatePaths.Add($_.FullName) }
            } catch {}
        }
    }

    $configs = @()
    foreach ($full in $candidatePaths) {
        if ([string]::IsNullOrWhiteSpace($full) -or -not (Test-Path -LiteralPath $full -PathType Leaf)) { continue }
        $lines = @()
        try {
            $lines = Get-Content -LiteralPath $full -ErrorAction Stop
        } catch {
            continue
        }

        $kv = @()
        foreach ($line in $lines) {
            if ($line -match '^\s*#' -or $line -match '^\s*$') { continue }
            if ($line -match '^\s*([A-Za-z0-9._-]+)\s*[:=]\s*(.*?)\s*$') {
                $name = $Matches[1]
                $raw = $Matches[2].Trim().Trim('"').Trim("'")
                $kv += [ordered]@{
                    key = $name
                    value = Redact-Value -Name $name -Value $raw
                    fingerprint = Get-FingerprintInfo -Name $name -Value $raw
                }
            }
        }

        $dbLike = @($kv | Where-Object {
            $_.key -match "(?i)(url|username|password|user|host|port|database|driver|datasource|connection|jdbc|mongo|redis|rabbit|qdrant|weaviate|milvus|elasticsearch|vector|secret|token|psql|mysql|maria|postgres|redis|ollama)"
        })
        $configs += [ordered]@{
            file = $full
            lines = $lines.Count
            dbLikeCount = $dbLike.Count
            kv = @($kv)
        }
    }

    return [ordered]@{
        filesScanned = $candidatePaths.Count
        files = @($configs)
        dbLikeTotal = (@($configs | ForEach-Object { $_.dbLikeCount } | Measure-Object -Sum).Sum)
    }
}

function Get-EnvironmentFacts {
    $items = Get-ChildItem Env: | Sort-Object Name | ForEach-Object {
        $info = Get-FingerprintInfo -Name $_.Name -Value ([string]$_.Value)
        [pscustomobject]@{
            name = $_.Name
            exists = $info.exists
            length = $info.length
            value_fingerprint = $info.value_fingerprint
            masked = $info.masked
            scope = "process"
            lastSeen = (Get-Date).ToString("o")
        }
    }

    $dbEnv = @($items | Where-Object { $_.name -match "(?i)(DB|DATABASE|MYSQL|POSTGRES|MARIADB|REDIS|MONGO|QDRANT|WEAVIATE|MILVUS|ELASTIC|VECTOR|OLLAMA|JAVA_HOME|SPRING|JDBC|DSN|AZURE_OPENAI|OPENAI)" })

    $sensitiveCount = @($items | Where-Object { (Is-SensitiveKey -Name $_.name) -or ($_.masked -like "<REDACTED*") }).Count
    return [ordered]@{
        items = @($items)
        allCount = $items.Count
        sensitiveLikeCount = $sensitiveCount
        dbLikeCount = $dbEnv.Count
        dbLikeNames = @($dbEnv | ForEach-Object { $_.name })
        lastScannedAt = (Get-Date).ToString("o")
        fingerprintCoverage = [ordered]@{
            total = $items.Count
            withFingerprint = @($items | Where-Object { $_.value_fingerprint }).Count
        }
    }
}

function Copy-ShareQuarantine {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$ShareRoot,
        [Parameter(Mandatory)][string]$Reason
    )

    if (-not (Test-Path -LiteralPath $Source)) { return }
    $quarantine = Join-Path $ShareRoot "quarantine"
    Ensure-Directory -Path $quarantine
    $target = Join-Path $quarantine ("{0}.{1}.json" -f $Reason, [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
    Move-Item -LiteralPath $Source -Destination $target -Force
}

function Get-SharePathState {
    param([Parameter(Mandatory)][string]$ShareRootPath)
    if (-not [string]::IsNullOrWhiteSpace($ShareRootPath) -and (Test-Path -LiteralPath $ShareRootPath)) {
        return @{ accessible = $true; path = (Resolve-Path $ShareRootPath).Path }
    }
    return @{ accessible = $false; path = $ShareRootPath }
}

$runId = Get-Date -Format "yyyyMMdd_HHmmss"
$runStart = (Get-Date).ToString("o")
$resolvedRole = Resolve-Role -RoleInput $Role
$hostname = $env:COMPUTERNAME
$machineGuid = [string](Get-ItemPropertyValue -Path "HKLM:\\SOFTWARE\\Microsoft\\Cryptography" -Name MachineGuid -ErrorAction SilentlyContinue)
$machineFingerprintHash = if ([string]::IsNullOrWhiteSpace($machineGuid)) { $null } else { New-Hash -Text $machineGuid }
$nodeId = if ($machineFingerprintHash) { "node-$($machineFingerprintHash.Substring(0, 16))" } else { "node-host-$((New-AwxHash12 -Text $hostname))" }
$outputRoot = [Environment]::ExpandEnvironmentVariables($OutputRoot)
Ensure-Directory -Path $outputRoot

$resolvedRepoFacts = @()
foreach ($repoPath in @($RepoPaths)) {
    if ([string]::IsNullOrWhiteSpace([string]$repoPath)) { continue }
    $resolvedRepoFacts += Get-RepoFacts -Path ([string]$repoPath)
}

$snapshot = [ordered]@{
    schemaVersion = $script:DeviceProbeSchemaVersion
    shareSchemaVersion = $script:ShareSchemaVersion
    collectedAt = $runStart
    runId = $runId
    role = $resolvedRole
    host = [ordered]@{
        hostname = $hostname
        machineGuid = $null
        machineGuidRedacted = $true
        machineFingerprintHash = $machineFingerprintHash
        nodeId = $nodeId
        user = $env:USERNAME
        domain = $env:USERDOMAIN
        isAdmin = (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
        powershellVersion = $PSVersionTable.PSVersion.ToString()
    }
    hardware = Get-HardwareFacts
    network = Get-NetworkFacts
    runtime = [ordered]@{
        processesTotal = (Get-Process | Measure-Object).Count
        processWatchTargets = $WatchProcessNames
        watchProcesses = @(
            Get-ProcessFacts -WatchNames $WatchProcessNames |
                Sort-Object name, id
        )
        servicesWatch = @('java', 'ollama', 'gradle', 'idea64', 'spring', 'node', 'python')
    }
    services = Get-ServiceFacts -ProbePorts $ProbePorts -DbServicePatterns $DbServicePatterns
    source = @{
        repoFacts = @($resolvedRepoFacts)
    }
    settings = [ordered]@{
        config = Get-ConfigFacts -RepoPaths $RepoPaths -Globs $ConfigFileGlobs
        environment = Get-EnvironmentFacts
    }
    sourceDeltaPolicy = [ordered]@{
        lastRefreshed = $runStart
        retentionMode = "latest_only"
        outputFormat = @("json_raw", "json_gpt", "markdown")
    }
}

$probeForGpt = [ordered]@{
    reportType = "gpt_device_probe"
    schemaVersion = $script:DeviceProbeSchemaVersion
    collectedAt = $snapshot.collectedAt
    runId = $runId
    phase = "PROBED"
    role = $snapshot.role
    host = $snapshot.host
    hardware = $snapshot.hardware
    network = $snapshot.network
    servicePorts = $snapshot.services.listeningPorts
    dbLikeServices = $snapshot.services.dbServices
    serverEndpoints = $snapshot.services.endpoints
    services = $snapshot.services
    runtime = $snapshot.runtime
    source = $snapshot.source
    settings = $snapshot.settings
}

$rawPath = Join-Path $outputRoot "probe-$resolvedRole-raw-$hostname-$runId.json"
$gptPath = Join-Path $outputRoot "probe-$resolvedRole-gpt-$hostname-$runId.json"
$latestRaw = Join-Path $outputRoot "latest-$resolvedRole-raw.json"
$latestGpt = Join-Path $outputRoot "latest-$resolvedRole-gpt.json"

$dbServiceLines = @($snapshot.services.dbServices | Sort-Object service | ForEach-Object {
    "- {0}: status={1}, start={2}" -f $_.service, $_.status, $_.startType
})
if (-not $dbServiceLines) { $dbServiceLines = @("- 탐지된 DB/캐시 서비스 없음") }

$endpointLines = @($snapshot.services.endpoints | ForEach-Object {
    $route = if ($_.route) { $_.route } else { "" }
    $statusPhrase = $_.statusPhrase
    "- http://127.0.0.1:{0}{1} reachable={2}, status={3}, msg={4}" -f @(
        $_.port,
        $route,
        $_.reachable,
        $_.statusCode,
        $statusPhrase
    )
})
if (-not $endpointLines) { $endpointLines = @("- 탐지된 health endpoint 없음") }

$openPorts = @($snapshot.services.listeningPorts | Where-Object { $_.open })
$gpuLines = @($snapshot.hardware.gpus | ForEach-Object {
    "- {0}: source={1}, status={2}, load={3}%, memUsed={4}%, temp={5}C, power={6}/{7}W" -f @(
        $_.name, $_.source, $_.status, $_.gpuUtilPct, $_.memUtilPct, $_.temperatureC, $_.powerDrawW, $_.powerLimitW
    )
})
if (-not $gpuLines) { $gpuLines = @("- 탐지된 GPU 정보 없음") }

$repoLines = @($snapshot.source.repoFacts | ForEach-Object {
    $buildArtifact = if ($_.build.latestArtifact) { $_.build.latestArtifact.path } else { "none" }
    "- {0}: inRepo={1}, branch={2}, head={3}, dirty={4}, untracked={5}, indexLock={6}, patchDropTopLevel={7}, patchDropPending={8}, buildArtifact={9}" -f @(
        $_.path, $_.inRepo, $_.branch, $_.head, $_.totalChanges, $_.untracked, $_.indexLock, $_.patchDropTopLevel, $_.patchDropPending, $buildArtifact
    )
})
if (-not $repoLines) { $repoLines = @("- repository fact 없음") }

$envDbLike = @($snapshot.settings.environment.dbLikeNames)
if ($envDbLike.Count -eq 0) { $envDbLike = @("없음") }

$buildStateLines = @($snapshot.source.repoFacts | ForEach-Object {
    $build = $_.build
    if (-not $build -or -not $build.hasBuildDirectory) {
        return "- $($_.path): build dir 없음"
    }
    return "- $($_.path): artifacts=$($build.recentArtifactCount), latest=$((if ($build.latestArtifact) { $build.latestArtifact.path } else { 'none' }))"
})

Write-AtomicJson -Path $rawPath -Object $snapshot -Depth 12
Write-AtomicJson -Path $gptPath -Object $probeForGpt -Depth 12
Write-AtomicJson -Path $latestRaw -Object $snapshot -Depth 12
Write-AtomicJson -Path $latestGpt -Object $probeForGpt -Depth 12

$mdPath = Join-Path $outputRoot "probe-$resolvedRole-gpt-ready-$hostname-$runId.md"
$md = @"
# GPT Pro Device Probe (ready-to-paste)

GeneratedAt: $runStart
Role: $resolvedRole
Host: $hostname
Schema: $($snapshot.schemaVersion)

## 핵심 요약
- 호스트명: $hostname
- OS: $($snapshot.hardware.os.caption)
- CPU: $($snapshot.hardware.cpu.name)
- RAM(GB): $($snapshot.hardware.memory.total_gb) total / $($snapshot.hardware.memory.free_gb) free
- 부팅시간: $($snapshot.hardware.os.bootTimeUtc)
- 업타임(분): $($snapshot.hardware.os.bootUptimeMinutes)
- 변경 파일 총계: $((@($snapshot.source.repoFacts | ForEach-Object { [int]$_.totalChanges }) | Measure-Object -Sum).Sum)
- 환경변수(총/민감 추정): $($snapshot.settings.environment.allCount) / $($snapshot.settings.environment.sensitiveLikeCount)
- DB 유사 환경변수(총): $($snapshot.settings.environment.dbLikeCount)
- 리포지토리 수: $($snapshot.source.repoFacts.Count)
- DB/설정 파일 스캔: $($snapshot.settings.config.filesScanned)
- DB/캐시 관련 서비스: $($snapshot.services.dbServices.Count)
- open 포트/health check: $($snapshot.services.endpoints.Count)
- 프로세스 감시 대상: $($snapshot.runtime.processWatchTargets -join ", ")
- 열린 포트: $($openPorts.Count)

## 포트/서비스 상태
$(($snapshot.services.listeningPorts | ForEach-Object {
    "- Port $($_.port): open=$($_.open), process=$($_.ownerProcess), addrs=$($_.addressCount)"
}) -join "`r`n")

### 데이터베이스/캐시 서비스
$($dbServiceLines -join "`r`n")

### Health endpoint 점검
$($endpointLines -join "`r`n")

## 빌드/테스트 상태
$($buildStateLines -join "`r`n")

## Git 변경 요약
$($repoLines -join "`r`n")

## GPU 상태
$($gpuLines -join "`r`n")

## 환경변수(DB 계열 키)
$($envDbLike -join ", ")

## GPT Pro 복붙 가이드
- 위 JSON(`$gptPath`) 내용을 그대로 붙이면 됩니다.
- 노트북/데스크톱 비교는 `scripts\device_probe_gpt_compare.ps1`에 동일/SMB 공유 경로를 지정해 실행하세요.
"@
Write-AtomicText -Path $mdPath -Text $md

$shareSummary = [ordered]@{
    shareRoot = $ShareRoot
    published = $false
    nodeLatestPath = $null
    nodeHistoryPath = $null
    lockUsed = $null
    notes = @()
}

if (-not [string]::IsNullOrWhiteSpace($ShareRoot)) {
    if ($PublishToShare) {
        try {
            $resolvedShareRoot = [Environment]::ExpandEnvironmentVariables($ShareRoot)
            $state = Get-SharePathState -ShareRootPath $resolvedShareRoot
            if (-not $state.accessible) {
                throw "share-root-not-accessible"
            }

            $nodeRoot = Join-Path $resolvedShareRoot "nodes\$resolvedRole"
            $latestNode = Join-Path $nodeRoot "latest"
            $historyNode = Join-Path $nodeRoot "history"
            Ensure-Directory -Path $latestNode
            Ensure-Directory -Path $historyNode

            $lockPath = Enter-ShareLock -ShareRoot $resolvedShareRoot -Owner $WriterId -Purpose "publish-$resolvedRole-node-probe" -TtlMinutes $ShareLockTtlMinutes
            try {
                $shareProbe = [ordered]@{
                    schema_version = $script:ShareSchemaVersion
                    role = $resolvedRole
                    host = $snapshot.host.hostname
                    node_id = $snapshot.host.nodeId
                    machine_fingerprint_hash = $snapshot.host.machineFingerprintHash
                    collected_at = $snapshot.collectedAt
                    published_at = (Get-Date).ToString("o")
                    source_file = $gptPath
                    payload = $probeForGpt
                    schema_version_for_payload = $script:DeviceProbeSchemaVersion
                }
                $latestNodeFile = Join-Path $latestNode "device-probe.json"
                $historyNodeFile = Join-Path $historyNode "device-probe-$runId.json"
                Write-AtomicJson -Path $latestNodeFile -Object $shareProbe -Depth 12
                Write-AtomicJson -Path $historyNodeFile -Object $shareProbe -Depth 12
                $shareSummary.published = $true
                $shareSummary.nodeLatestPath = $latestNodeFile
                $shareSummary.nodeHistoryPath = $historyNodeFile
                $shareSummary.lockUsed = $lockPath
            } finally {
                Remove-LockIfOwned -Path $lockPath -Owner $WriterId
            }
        } catch {
            $shareSummary.published = $false
            $shareSummary.notes += $_.Exception.Message
        }
    } else {
        $shareSummary.notes += "share publish disabled (set -PublishToShare)"
    }
} else {
    $shareSummary.notes += "no-share-root"
}

Write-Host "saved raw => $rawPath"
Write-Host "saved gpt => $gptPath"
Write-Host "saved latest raw => $latestRaw"
Write-Host "saved latest gpt => $latestGpt"
Write-Host "saved markdown => $mdPath"
if ($shareSummary.published) {
    Write-Host "share published => $($shareSummary.nodeLatestPath)"
} else {
    Write-Host "share publish skipped/failed => $($shareSummary.notes -join '; ')"
}
Write-Host "--- GPT READY START ---"
Get-Content -Path $gptPath
Write-Host "--- GPT READY END ---"
