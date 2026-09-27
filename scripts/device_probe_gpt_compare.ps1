param(
    [Parameter(Position = 0)]
    [string]$DesktopProbe,

    [Parameter(Position = 1)]
    [string]$NotebookProbe,

    [ValidateSet("compare", "loop")]
    [string]$Mode = "compare",

    [string]$OutputDir = "$env:USERPROFILE\gpt_device_probe\compare",

    [string]$ShareRoot,

    [ValidateSet("auto", "desktop", "notebook")]
    [string]$SelfRole = "auto",

    [string]$ProbeFile,

    [int]$StaleMinutes = 45,

    [int]$ShareLockTtlMinutes = 8,

    [string]$WriterId = $env:COMPUTERNAME,

    [string]$TaskId = "probe-handoff",

    [switch]$CreateRequest,

    [switch]$AutoRespond,

    [switch]$Acknowledge,

    [switch]$IncludeRaw
)

$ErrorActionPreference = "Stop"

$script:CompareSchemaVersion = "device_probe_compare_v2"
$script:DashboardSchemaVersion = "device_probe_dashboard_v2"
$script:MessageSchemaVersion = "device_probe_message_v2"
$script:NodeSchemaVersion = "device_probe_gpt_tool_v3"
$script:ShareSchemaVersion = "device_probe_share_v1"
$script:StatusFlow = @("PUBLISHED", "DISCOVERED", "PROBED", "REQUESTED", "RESPONDED", "ACKNOWLEDGED", "RESOLVED")

function Ensure-Directory {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function As-Array {
    param([AllowNull()]$Items)
    if ($null -eq $Items) { return @() }
    return @($Items)
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

    $tmp = Join-Path $dir ("{0}.{1}.tmp" -f ([IO.Path]::GetFileName($Path), [guid]::NewGuid()))
    [IO.File]::WriteAllText($tmp, $Text, (New-Object System.Text.UTF8Encoding($false)))
    Move-Item -LiteralPath $tmp -Destination $Path -Force
}

function Write-AtomicJson {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)]$Object,
        [Parameter(Mandatory)][int]$Depth
    )

    Write-AtomicText -Path $Path -Text (ConvertTo-Json -InputObject $Object -Depth $Depth)
}

function Hash-Text {
    param([Parameter(Mandatory)][string]$Text)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return [BitConverter]::ToString($hash).Replace("-", "").ToLowerInvariant()
}

function Parse-Date {
    param([AllowNull()][string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    $dt = [datetime]::MinValue
    if ([datetime]::TryParse($Value, [ref]$dt)) { return $dt.ToUniversalTime() }
    return $null
}

function Get-AgeMinutes {
    param([AllowNull()][string]$CollectedAt)
    $ts = Parse-Date -Value $CollectedAt
    if ($null -eq $ts) { return $null }
    return [Math]::Round(([DateTime]::UtcNow - $ts).TotalMinutes, 2)
}

function Resolve-Role {
    param([Parameter(Mandatory)][string]$Role)
    if ($Role -ne "auto") {
        return $Role
    }
    $machine = [Environment]::MachineName.ToLowerInvariant()
    if ($machine -match "(?i)(nb|notebook|laptop)") {
        return "notebook"
    }
    return "desktop"
}

function New-LockObject {
    param(
        [string]$Owner,
        [string]$Purpose,
        [int]$TtlMinutes
    )

    $now = Get-Date
    return [ordered]@{
        owner = $Owner
        purpose = $Purpose
        created_at = $now.ToString("o")
        expires_at = $now.AddMinutes($TtlMinutes).ToString("o")
        schema_version = "device_probe_share_lock_v2"
        pid = $PID
    }
}

function Read-Lock {
    param([Parameter(Mandatory)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    } catch {
        return $null
    }
}

function Is-LockExpired {
    param($Lock)
    if ($null -eq $Lock -or -not $Lock.expires_at) { return $true }
    $exp = [datetime]::MinValue
    if (-not [datetime]::TryParse($Lock.expires_at, [ref]$exp)) { return $true }
    return ((Get-Date) -gt $exp)
}

function Enter-ShareLock {
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$Owner,
        [Parameter(Mandatory)][string]$Purpose,
        [Parameter(Mandatory)][int]$TtlMinutes
    )

    $lockDir = Join-Path $Root "locks"
    Ensure-Directory -Path $lockDir
    $safePurpose = ($Purpose -replace "[^A-Za-z0-9_-]", "_")
    $lockPath = Join-Path $lockDir "$safePurpose.lock.json"
    $existing = Read-Lock -Path $lockPath
    if ($null -ne $existing -and -not (Is-LockExpired -Lock $existing) -and $existing.owner -ne $Owner) {
        throw "share-lock-active owner=$($existing.owner) purpose=$($existing.purpose)"
    }
    if ($null -ne $existing -and (Is-LockExpired -Lock $existing)) {
        Move-Item -LiteralPath $lockPath -Destination (Join-Path $lockDir ("quarantine\stale.$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()).json")) -ErrorAction SilentlyContinue
    }
    Write-AtomicJson -Path $lockPath -Object (New-LockObject -Owner $Owner -Purpose $Purpose -TtlMinutes $TtlMinutes) -Depth 10
    return $lockPath
}

function Exit-ShareLock {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$Owner
    )
    $lock = Read-Lock -Path $Path
    if ($null -eq $lock -or $lock.owner -ne $Owner) { return }
    Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
}

function Move-To-Quarantine {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][string]$ShareRoot,
        [Parameter(Mandatory)][string]$Reason
    )
    $q = Join-Path $ShareRoot "quarantine"
    Ensure-Directory -Path $q
    $dest = Join-Path $q ("{0}.{1}.{2}" -f $Reason, [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(), [IO.Path]::GetFileName($Path))
    Move-Item -LiteralPath $Path -Destination $dest -Force
}

function Read-JsonSafe {
    param(
        [Parameter(Mandatory)][string]$Path,
        [string]$ShareRoot,
        [string]$Context
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "json-missing:$Path"
    }
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    } catch {
        if ($ShareRoot) {
            Move-To-Quarantine -Path $Path -ShareRoot $ShareRoot -Reason $Context
        }
        throw "json-parse:$Path"
    }
}

function Probe-Payload {
    param([Parameter(Mandatory)]$Obj)
    if ($Obj.PSObject.Properties.Match("payload").Count -gt 0 -and $Obj.payload) {
        return $Obj.payload
    }
    return $Obj
}

function Validate-Node {
    param([Parameter(Mandatory)]$Payload, [string]$RoleHint, [string]$SourceFile)
    $errors = @()

    if ($null -eq $Payload) {
        return [ordered]@{
            valid = $false
            path = $SourceFile
            role = $RoleHint
            errors = @("missing_payload")
            ageMinutes = $null
            payload = $null
        }
    }

    $schema = $Payload.schemaVersion
    if ([string]::IsNullOrWhiteSpace($schema)) { $schema = $Payload.schema_version }
    if ([string]::IsNullOrWhiteSpace($schema)) {
        $errors += "schema_missing"
    } elseif ($schema -notlike "device_probe_gpt_tool_v*") {
        $errors += "schema_unknown:$schema"
    }

    $required = @("collectedAt", "runId", "host", "hardware")
    foreach ($field in $required) {
        if (-not ($Payload.PSObject.Properties.Match($field)).Count) {
            $errors += "missing_$field"
        }
    }

    if (-not $Payload.host -or -not $Payload.host.hostname) {
        $errors += "missing_host.hostname"
    }

    $age = Get-AgeMinutes -CollectedAt $Payload.collectedAt
    $fingerprint = if ($Payload.host -and $Payload.host.machineFingerprintHash) { [string]$Payload.host.machineFingerprintHash } else { $null }
    if ([string]::IsNullOrWhiteSpace($fingerprint) -and $Payload.host -and $Payload.host.machineGuid) {
        $fingerprint = Hash-Text -Text ([string]$Payload.host.machineGuid)
    }
    $nodeId = if ($Payload.host -and $Payload.host.nodeId) {
        [string]$Payload.host.nodeId
    } elseif (-not [string]::IsNullOrWhiteSpace($fingerprint)) {
        "node-$($fingerprint.Substring(0, [Math]::Min(16, $fingerprint.Length)))"
    } else { $null }
    $status = "invalid"
    if ($errors.Count -eq 0) {
        if ($null -ne $age -and $age -le $StaleMinutes) { $status = "online" }
        elseif ($null -ne $age -and $age -le ($StaleMinutes * 2)) { $status = "stale" }
        else { $status = "offline" }
    }

    return [ordered]@{
        valid = ($errors.Count -eq 0)
        path = $SourceFile
        role = $Payload.role ? $Payload.role : $RoleHint
        host = if ($Payload.host) { $Payload.host.hostname } else { $null }
        nodeId = $nodeId
        machineFingerprintHash = $fingerprint
        collectedAt = $Payload.collectedAt
        ageMinutes = $age
        status = $status
        schemaVersion = $schema
        serviceVersion = $Payload.service_version
        runId = $Payload.runId
        payload = $Payload
        errors = @($errors)
    }
}

function Read-NodeProbe {
    param(
        [Parameter(Mandatory)][string]$Path,
        [string]$ShareRoot,
        [string]$Role
    )

    $raw = Read-JsonSafe -Path $Path -ShareRoot $ShareRoot -Context "node-probe"
    $payload = Probe-Payload -Obj $raw
    return Validate-Node -Payload $payload -RoleHint $Role -SourceFile $Path
}

function Get-EnvironmentMap {
    param([Parameter(Mandatory)]$Probe, [string]$Role)
    if ($null -eq $Probe -or -not $Probe.settings -or -not $Probe.settings.environment -or -not $Probe.settings.environment.items) { return @{} }
    $envItems = @{}
    foreach ($it in $Probe.settings.environment.items) {
        if ($null -eq $it.name) { continue }
        $envItems[$it.name.ToUpperInvariant()] = $it
    }
    return $envItems
}

function Build-NodeSummary {
    param(
        [Parameter(Mandatory)]$NodeValidation,
        [Parameter(Mandatory)][string]$Role
    )

    if (-not $NodeValidation.valid) {
        return [ordered]@{
            role = $Role
            sourceFile = $NodeValidation.path
            collectedAt = $null
            ageMinutes = $null
            status = "invalid"
            host = $null
            nodeId = $null
            machineFingerprintHash = $null
            os = @{}
            cpu = @{}
            memory = @{}
            gpus = @()
            ports = @()
            endpoints = @()
            repoFacts = @()
            envItems = @{}
            env = @()
            sourceSchemaVersion = $NodeValidation.schemaVersion
            errors = @($NodeValidation.errors)
        }
    }

    $p = $NodeValidation.payload
    return [ordered]@{
        role = $Role
        sourceFile = $NodeValidation.path
        collectedAt = $p.collectedAt
        ageMinutes = $NodeValidation.ageMinutes
        status = $NodeValidation.status
        host = if ($p.host) { $p.host.hostname } else { $null }
        nodeId = $NodeValidation.nodeId
        machineFingerprintHash = $NodeValidation.machineFingerprintHash
        os = if ($p.hardware -and $p.hardware.os) { $p.hardware.os } else { @{} }
        cpu = if ($p.hardware -and $p.hardware.cpu) { $p.hardware.cpu } else { @{} }
        memory = if ($p.hardware -and $p.hardware.memory) { $p.hardware.memory } else { @{} }
        gpus = if ($p.hardware -and $p.hardware.gpus) { $p.hardware.gpus } else { @() }
        disks = if ($p.hardware -and $p.hardware.disks) { $p.hardware.disks } else { @() }
        ports = if ($p.services -and $p.services.listeningPorts) { $p.services.listeningPorts } else { @() }
        endpoints = if ($p.services -and $p.services.endpoints) { $p.services.endpoints } else { @() }
        repoFacts = if ($p.source -and $p.source.repoFacts) { $p.source.repoFacts } else { @() }
        envItems = Get-EnvironmentMap -Probe $p -Role $Role
        env = if ($p.settings -and $p.settings.environment -and $p.settings.environment.items) { $p.settings.environment.items } else { @() }
        runtime = if ($p.runtime) { $p.runtime } else { @{} }
        sourceSchemaVersion = $NodeValidation.schemaVersion
        errors = @($NodeValidation.errors)
    }
}

function Get-NodePairIdentityStatus {
    param(
        [Parameter(Mandatory)]$Desktop,
        [Parameter(Mandatory)]$Notebook
    )

    $sameFingerprint = (
        -not [string]::IsNullOrWhiteSpace([string]$Desktop.machineFingerprintHash) -and
        -not [string]::IsNullOrWhiteSpace([string]$Notebook.machineFingerprintHash) -and
        ([string]$Desktop.machineFingerprintHash -eq [string]$Notebook.machineFingerprintHash)
    )
    $sameHostname = (
        -not [string]::IsNullOrWhiteSpace([string]$Desktop.host) -and
        -not [string]::IsNullOrWhiteSpace([string]$Notebook.host) -and
        ([string]$Desktop.host -ieq [string]$Notebook.host)
    )
    $reason = if ($sameFingerprint) { "same_fingerprint" } elseif ($sameHostname) { "same_hostname" } else { $null }
    return [ordered]@{
        invalidSameNode = ($sameFingerprint -or $sameHostname)
        reason = $reason
    }
}

function Write-InvalidSameNodeOutput {
    param(
        [Parameter(Mandatory)][string]$Directory,
        [Parameter(Mandatory)]$Desktop,
        [Parameter(Mandatory)]$Notebook,
        [Parameter(Mandatory)][string]$Reason,
        [switch]$LoopOutput
    )

    Ensure-Directory -Path $Directory
    $runId = Get-Date -Format "yyyyMMdd_HHmmss"
    $jsonPath = if ($LoopOutput) { Join-Path $Directory "latest-dashboard.json" } else { Join-Path $Directory ("compare_{0}.json" -f $runId) }
    $mdPath = if ($LoopOutput) { Join-Path $Directory "latest-context-pack.md" } else { Join-Path $Directory ("compare_{0}.md" -f $runId) }
    $obj = [ordered]@{
        schemaVersion = if ($LoopOutput) { $script:DashboardSchemaVersion } else { $script:CompareSchemaVersion }
        generatedAt = (Get-Date).ToString("o")
        status = "INVALID_SAME_NODE"
        reason = $Reason
        desktop = [ordered]@{ host = $Desktop.host; nodeId = $Desktop.nodeId; machineFingerprintHash = $Desktop.machineFingerprintHash; collectedAt = $Desktop.collectedAt }
        notebook = [ordered]@{ host = $Notebook.host; nodeId = $Notebook.nodeId; machineFingerprintHash = $Notebook.machineFingerprintHash; collectedAt = $Notebook.collectedAt }
        rows = @()
    }
    Write-AtomicJson -Path $jsonPath -Object $obj -Depth 10
    Write-AtomicText -Path $mdPath -Text ("# INVALID_SAME_NODE`r`n`r`n- reason: {0}`r`n- desktop: {1} / {2}`r`n- notebook: {3} / {4}`r`n" -f $Reason, $Desktop.host, $Desktop.nodeId, $Notebook.host, $Notebook.nodeId)
    return [ordered]@{ jsonPath = $jsonPath; markdownPath = $mdPath }
}

function Build-Row {
    param([string]$Metric, $Desktop, $Notebook)
    $delta = "-"
    if ($null -ne $Desktop -and $null -ne $Notebook) {
        try {
            $delta = [Math]::Round(([double]$Notebook) - ([double]$Desktop), 4)
        } catch {}
    }
    return [pscustomobject]@{
        metric = $Metric
        desktop = $Desktop
        notebook = $Notebook
        delta = $delta
    }
}

function Build-DiffRows {
    param(
        [Parameter(Mandatory)]$Desk,
        [Parameter(Mandatory)]$Note
    )

    $deskOpen = @($Desk.ports | Where-Object { $_.open } | ForEach-Object { [int]$_.port }) | Sort-Object -Unique
    $noteOpen = @($Note.ports | Where-Object { $_.open } | ForEach-Object { [int]$_.port }) | Sort-Object -Unique
    $allPorts = @($deskOpen + $noteOpen) | Sort-Object -Unique

    $deskRepoCount = if ($Desk.repoFacts) { @($Desk.repoFacts).Count } else { 0 }
    $noteRepoCount = if ($Note.repoFacts) { @($Note.repoFacts).Count } else { 0 }

    $deskIndexLocks = if ($Desk.repoFacts) { @($Desk.repoFacts | Where-Object { $_.indexLock }).Count } else { 0 }
    $noteIndexLocks = if ($Note.repoFacts) { @($Note.repoFacts | Where-Object { $_.indexLock }).Count } else { 0 }

    $rows = @(
        (Build-Row "역할" $Desk.role $Note.role),
        (Build-Row "수집 시각" $Desk.collectedAt $Note.collectedAt),
        (Build-Row "상태" $Desk.status $Note.status),
        (Build-Row "OS" ($Desk.os.caption) ($Note.os.caption)),
        (Build-Row "업타임(분)" $Desk.os.bootUptimeMinutes $Note.os.bootUptimeMinutes),
        (Build-Row "CPU(%)" $Desk.cpu.currentLoadPercent $Note.cpu.currentLoadPercent),
        (Build-Row "CPU 논리코어" ("{0}/{1}" -f $Desk.cpu.cores, $Desk.cpu.logical) ("{0}/{1}" -f $Note.cpu.cores, $Note.cpu.logical)),
        (Build-Row "RAM(GB)" ("{0} / {1}" -f $Desk.memory.total_gb, $Desk.memory.free_gb) ("{0} / {1}" -f $Note.memory.total_gb, $Note.memory.free_gb)),
        (Build-Row "메모리 사용률(%)" $Desk.memory.used_percent $Note.memory.used_percent),
        (Build-Row "열린 포트 수" $deskOpen.Count $noteOpen.Count),
        (Build-Row "저장소 수" $deskRepoCount $noteRepoCount),
        (Build-Row "index.lock 수" $deskIndexLocks $noteIndexLocks),
        (Build-Row "감시 중 프로세스" ($Desk.runtime.processesTotal) ($Note.runtime.processesTotal))
    )

    foreach ($p in @($allPorts)) {
        $d = @($Desk.ports | Where-Object { [int]$_.port -eq $p }) | Select-Object -First 1
        $n = @($Note.ports | Where-Object { [int]$_.port -eq $p }) | Select-Object -First 1
        $deskPortText = if ($d) { "open=$($d.open), proc=$($d.ownerProcess)" } else { "missing" }
        $notePortText = if ($n) { "open=$($n.open), proc=$($n.ownerProcess)" } else { "missing" }
        $rows += Build-Row ("포트 $p") $deskPortText $notePortText
    }

    return $rows
}

function Detect-Gaps {
    param(
        [Parameter(Mandatory)]$Desk,
        [Parameter(Mandatory)]$Note,
        [int]$StaleLimitMinutes,
        [hashtable]$Messages
    )

    $gaps = @()
    $trackEnv = @("JAVA_HOME", "OLLAMA_HOST", "SPRING_PROFILES_ACTIVE", "PSMODULEPATH", "OPENAI_API_KEY", "DB_URL", "DATABASE_URL", "REDIS_URL")
    $gapConfidenceBase = 0.85

    if (-not $Desk.collectedAt) {
        $gaps += [ordered]@{ type = "MISSING"; metric = "desktop.collectedAt"; source_device = "desktop"; source_file = $Desk.sourceFile; confidence = 0.99; reason = "collectedAt missing" }
    } elseif ($Desk.ageMinutes -gt $StaleLimitMinutes) {
        $gaps += [ordered]@{ type = "STALE"; metric = "desktop.collectedAt"; source_device = "desktop"; source_file = $Desk.sourceFile; confidence = 0.95; reason = ("age={0} min" -f [Math]::Round($Desk.ageMinutes,2)) }
    }
    if (-not $Note.collectedAt) {
        $gaps += [ordered]@{ type = "MISSING"; metric = "notebook.collectedAt"; source_device = "notebook"; source_file = $Note.sourceFile; confidence = 0.99; reason = "collectedAt missing" }
    } elseif ($Note.ageMinutes -gt $StaleLimitMinutes) {
        $gaps += [ordered]@{ type = "STALE"; metric = "notebook.collectedAt"; source_device = "notebook"; source_file = $Note.sourceFile; confidence = 0.95; reason = ("age={0} min" -f [Math]::Round($Note.ageMinutes,2)) }
    }

    if (-not $Desk.host) {
        $gaps += [ordered]@{ type = "MISSING"; metric = "desktop.host"; source_device = "desktop"; source_file = $Desk.sourceFile; confidence = 0.93; reason = "hostname is empty" }
    }
    if (-not $Note.host) {
        $gaps += [ordered]@{ type = "MISSING"; metric = "notebook.host"; source_device = "notebook"; source_file = $Note.sourceFile; confidence = 0.93; reason = "hostname is empty" }
    }

    if ($Desk.status -eq "invalid") {
        $gaps += [ordered]@{ type = "BLOCKED"; metric = "desktop.node_schema"; source_device = "desktop"; source_file = $Desk.sourceFile; confidence = 0.99; reason = "invalid schema" }
    }
    if ($Note.status -eq "invalid") {
        $gaps += [ordered]@{ type = "BLOCKED"; metric = "notebook.node_schema"; source_device = "notebook"; source_file = $Note.sourceFile; confidence = 0.99; reason = "invalid schema" }
    }

    if ($Desk.ageMinutes -and $Note.ageMinutes -and [Math]::Abs($Desk.ageMinutes - $Note.ageMinutes) -gt 30) {
        $gaps += [ordered]@{ type = "CONFLICT"; metric = "collectedAt"; source_device = "desktop/notebook"; source_file = ($Desk.sourceFile + "," + $Note.sourceFile); confidence = 0.7; reason = "probe interval mismatch" }
    }

    if ($Desk.repoFacts -and $Note.repoFacts) {
        $deskInRepo = @($Desk.repoFacts | Where-Object { $_.inRepo }) | Measure-Object | Select-Object -ExpandProperty Count
        $noteInRepo = @($Note.repoFacts | Where-Object { $_.inRepo }) | Measure-Object | Select-Object -ExpandProperty Count
        if ($deskInRepo -eq 0 -or $noteInRepo -eq 0) {
            $gaps += [ordered]@{ type = "CONFLICT"; metric = "repoFacts"; source_device = "shared"; source_file = ($Desk.sourceFile + "," + $Note.sourceFile); confidence = 0.7; reason = "repoFacts empty on one side" }
        }
    } else {
        $gaps += [ordered]@{ type = "MISSING"; metric = "repoFacts"; source_device = "shared"; source_file = ($Desk.sourceFile + "," + $Note.sourceFile); confidence = 0.7; reason = "repoFacts not collected" }
    }

    foreach ($r in @("desktop","notebook")) {
        $n = if ($r -eq "desktop") { $Desk } else { $Note }
        $repoFacts = @($n.repoFacts)
        foreach ($ri in $repoFacts) {
            if ($ri.indexLock) {
                $gaps += [ordered]@{
                    type = "BLOCKED"
                    metric = "index.lock"
                    source_device = $r
                    source_file = $ri.path
                    confidence = 0.92
                    reason = "index.lock exists"
                }
            }
        }
    }

    foreach ($r in @("desktop", "notebook")) {
        $repoFacts = if ($r -eq "desktop") { @($Desk.repoFacts) } else { @($Note.repoFacts) }
        foreach ($ri in $repoFacts) {
            if ($ri.patchDropPending -gt 0) {
                $gaps += [ordered]@{
                    type = "BLOCKED"
                    metric = "patchdrop/pending"
                    source_device = $r
                    source_file = $ri.path
                    confidence = 0.91
                    reason = ("pending patchdrop artifact exists: pending={0}" -f $ri.patchDropPending)
                }
            }
            if ($ri.patchDropTopLevel -eq 0) {
                $gaps += [ordered]@{
                    type = "EVIDENCE_NEEDED"
                    metric = "patchdrop/official_bundle"
                    source_device = $r
                    source_file = $ri.path
                    confidence = 0.78
                    reason = "no top-level patchdrop bundle in repo"
                }
            }
        }
    }

    foreach ($e in $trackEnv) {
        $ku = $e.ToUpperInvariant()
        $d = $Desk.envItems[$ku]
        $n = $Note.envItems[$ku]
        if (($null -eq $d) -and ($null -eq $n)) {
            continue
        }
        if (($null -eq $d) -or ($null -eq $n)) {
            $missingSide = if ($null -eq $d) { "desktop" } else { "notebook" }
            $gaps += [ordered]@{
                type = "MISSING"
                metric = ("env:{0}" -f $ku)
                source_device = $missingSide
                source_file = if ($missingSide -eq "desktop") { $Desk.sourceFile } else { $Note.sourceFile }
                confidence = 0.94
                reason = "env key not present"
            }
            continue
        }
        $dv = if ($d.value_fingerprint) { $d.value_fingerprint } else { $d.value }
        $nv = if ($n.value_fingerprint) { $n.value_fingerprint } else { $n.value }
        if ($dv -ne $nv) {
            $gaps += [ordered]@{
                type = "CONFLICT"
                metric = ("env:{0}" -f $ku)
                source_device = "desktop/notebook"
                source_file = ($Desk.sourceFile + "," + $Note.sourceFile)
                confidence = 0.96
                reason = ("desktop={0}, notebook={1}" -f $dv, $nv)
            }
        }
    }

    $openPorts = @()
    foreach ($dp in @($Desk.ports)) {
        if ($dp.open) { try { $openPorts += [int]$dp.port } catch {} }
    }
    foreach ($np in @($Note.ports)) {
        if ($np.open) { try { $openPorts += [int]$np.port } catch {} }
    }
    $openPorts = @($openPorts | Sort-Object -Unique)
    foreach ($p in $openPorts) {
        if (-not (@($Desk.endpoints | Where-Object { [int]$_.port -eq $p }).Count)) {
            $gaps += [ordered]@{ type = "EVIDENCE_NEEDED"; metric = ("endpoint/{0}" -f $p); source_device = "desktop"; source_file = $Desk.sourceFile; confidence = 0.8; reason = "open port without endpoint check" }
        }
        if (-not (@($Note.endpoints | Where-Object { [int]$_.port -eq $p }).Count)) {
            $gaps += [ordered]@{ type = "EVIDENCE_NEEDED"; metric = ("endpoint/{0}" -f $p); source_device = "notebook"; source_file = $Note.sourceFile; confidence = 0.8; reason = "open port without endpoint check" }
        }
    }

    foreach ($g in @($gaps)) {
        if (-not $g.confidence) { $g.confidence = $gapConfidenceBase }
    }

    if ($Messages -and $Messages.PSObject -and $Messages.PSObject.Properties.Match("requests").Count) {
        $requests = Get-MessageGroup -Messages $Messages -Group "requests"
        $responses = Get-MessageGroup -Messages $Messages -Group "responses"
        $acks = Get-MessageGroup -Messages $Messages -Group "acknowledgements"

        foreach ($req in @($requests)) {
            $reqSource = if ($req.source_file) { $req.source_file } else { $req._sourceFile }
            $rsp = Select-Latest -Items ($responses | Where-Object { $_.parent_message_id -eq $req.message_id -and $_.status -eq "RESPONDED" })
            if (-not $rsp) {
                $gaps += [ordered]@{
                    type = "UNACKNOWLEDGED"
                    metric = ("request:{0}" -f $req.message_id)
                    source_device = $req.source_device
                    source_file = $reqSource
                    confidence = 0.92
                    reason = ("요청에 대한 응답 없음: task={0}" -f $req.task_id)
                }
                continue
            }

            $ack = Select-Latest -Items ($acks | Where-Object { $_.parent_message_id -eq $rsp.message_id -and $_.source_device -eq $req.target_device })
            if (-not $ack) {
                $rspSource = if ($rsp.source_file) { $rsp.source_file } else { $rsp._sourceFile }
                $gaps += [ordered]@{
                    type = "UNACKNOWLEDGED"
                    metric = ("response:{0}" -f $rsp.message_id)
                    source_device = $rsp.source_device
                    source_file = $rspSource
                    confidence = 0.9
                    reason = ("응답 미확인: target={0}" -f $rsp.target_device)
                }
            }
        }
    }

    return $gaps
}

function Read-MessageFile {
    param(
        [Parameter(Mandatory)][string]$Path,
        [string]$ShareRoot,
        [string]$Status
    )

    if (-not (Test-Path -LiteralPath $Path)) { return $null }
    $obj = Read-JsonSafe -Path $Path -ShareRoot $ShareRoot -Context "message-json"
    return $obj
}

function Validate-Message {
    param(
        [Parameter(Mandatory)]$Message,
        [Parameter(Mandatory)]$ShareRoot,
        [string]$SourcePath
    )
    $errors = @()
    $status = if ($Message.schema_version) { $Message.schema_version } else { "device_probe_message_v1" }
    $required = @(
        "message_id",
        "parent_message_id",
        "source_device",
        "target_device",
        "task_id",
        "created_at",
        "expires_at",
        "schema_version",
        "status",
        "content_hash",
        "related_files",
        "requested_information",
        "response_summary",
        "missing_information",
        "confidence",
        "acknowledged_at"
    )
    foreach ($r in $required) {
        if (-not ($Message.PSObject.Properties.Match($r)).Count) {
            $errors += "missing_$r"
        }
    }
    if ($status -notlike "device_probe_message_v*") {
        $errors += "message_schema_unknown:$status"
    }
    if ($Message.status -and ($script:StatusFlow -notcontains $Message.status)) {
        $errors += "invalid_status:$($Message.status)"
    }
    $parsedCreated = Parse-Date -Value $Message.created_at
    if (-not $parsedCreated) { $errors += "invalid_created_at" }

    if ($Message.PSObject.Properties.Match("expires_at").Count -gt 0) {
        $parsedExp = Parse-Date -Value $Message.expires_at
        if (-not $parsedExp) { $errors += "invalid_expires_at" }
        if ($parsedExp -and $parsedExp -lt (Get-Date)) { $errors += "expired" }
    }

    if ($errors.Count -gt 0 -and $ShareRoot -and $SourcePath) {
        if (Test-Path -LiteralPath $SourcePath) {
            Move-To-Quarantine -Path $SourcePath -ShareRoot $ShareRoot -Reason "invalid-message"
        }
    }
    return $errors
}

function Load-MessageSet {
    param(
        [Parameter(Mandatory)][string]$Dir,
        [string]$ShareRoot,
        [string]$Status
    )

    if (-not (Test-Path -LiteralPath $Dir)) { return @() }
    $out = @()
    foreach ($f in Get-ChildItem -LiteralPath $Dir -File -Filter "*.json" -ErrorAction SilentlyContinue) {
        try {
            $obj = Read-MessageFile -Path $f.FullName -ShareRoot $ShareRoot -Status $Status
            if ($null -eq $obj) { continue }
            $errs = Validate-Message -Message $obj -ShareRoot $ShareRoot -SourcePath $f.FullName
            if ($errs.Count -gt 0) { continue }
            $obj | Add-Member -NotePropertyName "_sourceFile" -NotePropertyValue $f.FullName -Force
            if (-not ($obj.PSObject.Properties.Match("source_file").Count)) {
                $obj | Add-Member -NotePropertyName "source_file" -NotePropertyValue $f.FullName -Force
            } else {
                $obj.source_file = $f.FullName
            }
            $obj | Add-Member -NotePropertyName "_errors" -NotePropertyValue $errs -Force
            $out += $obj
        } catch {
            if ($ShareRoot) {
                Move-To-Quarantine -Path $f.FullName -ShareRoot $ShareRoot -Reason "message"
            }
        }
    }
    return @($out)
}

function Load-All-Messages {
    param([Parameter(Mandatory)][string]$ShareRoot)
    $requests = Load-MessageSet -Dir (Join-Path $ShareRoot "messages\requests") -ShareRoot $ShareRoot -Status "requests"
    $responses = Load-MessageSet -Dir (Join-Path $ShareRoot "messages\responses") -ShareRoot $ShareRoot -Status "responses"
    $acks = Load-MessageSet -Dir (Join-Path $ShareRoot "messages\acknowledgements") -ShareRoot $ShareRoot -Status "acknowledgements"

    if ($null -eq $requests) { $requests = @() }
    if ($null -eq $responses) { $responses = @() }
    if ($null -eq $acks) { $acks = @() }

    return (Normalize-MessageSet -Messages @{
        requests = $requests
        responses = $responses
        acknowledgements = $acks
    })
}

function Get-MessageGroup {
    param(
        [AllowNull()]$Messages,
        [Parameter(Mandatory)][string]$Group
    )

    if ($null -eq $Messages) { return @() }
    if ($Messages -is [System.Collections.IDictionary]) {
        if (-not $Messages.Contains($Group)) { return @() }
        return @(As-Array -Items $Messages[$Group])
    }
    if ($Messages.PSObject -and ($Messages.PSObject.Properties.Name -contains $Group)) {
        return @(As-Array -Items $Messages.$Group)
    }
    return @()
}

function Normalize-MessageSet {
    param([Parameter(Mandatory = $false)]$Messages = @{})
    $requests = @(Get-MessageGroup -Messages $Messages -Group "requests")
    $responses = @(Get-MessageGroup -Messages $Messages -Group "responses")
    $acks = @(Get-MessageGroup -Messages $Messages -Group "acknowledgements")
    $requestSet = Remove-Duplicate-Messages -Messages $requests
    $responseSet = Remove-Duplicate-Messages -Messages $responses
    $ackSet = Remove-Duplicate-Messages -Messages $acks
    return @{
        requests = $requestSet.items
        responses = $responseSet.items
        acknowledgements = $ackSet.items
        dedupe = [ordered]@{
            requests = $requestSet.duplicates
            responses = $responseSet.duplicates
            acknowledgements = $ackSet.duplicates
        }
    }
}

function Remove-Duplicate-Messages {
    param([AllowNull()][AllowEmptyCollection()]$Messages)
    $seen = @{}
    $dedup = @()
    $dupeCount = 0
    foreach ($msg in @($Messages)) {
        if (-not $msg.message_id) { continue }
        $key = $msg.message_id
        if ($seen.ContainsKey($key)) {
            $dupeCount++
            continue
        }
        $seen[$key] = $true
        $dedup += $msg
    }
    return [ordered]@{ items = $dedup; duplicates = $dupeCount }
}

function Select-Latest {
    param([AllowNull()][AllowEmptyCollection()]$Items)
    if (-not $Items) { return $null }
    $all = @($Items)
    $all = @($all | Sort-Object { if ($_.created_at) { Parse-Date -Value $_.created_at } else { [datetime]::MinValue } } -Descending)
    return $all[0]
}

function Get-PendingMessageState {
    param([AllowNull()]$Messages)

    $requests = @(Get-MessageGroup -Messages $Messages -Group "requests")
    $responses = @(Get-MessageGroup -Messages $Messages -Group "responses")
    $acks = @(Get-MessageGroup -Messages $Messages -Group "acknowledgements")
    $pendingRequests = @()
    $pendingResponses = @()
    $pendingAcks = @()

    foreach ($request in $requests) {
        $response = Select-Latest -Items @($responses | Where-Object { $_.parent_message_id -eq $request.message_id -and $_.status -eq "RESPONDED" })
        if (-not $response) {
            $pendingRequests += $request
            continue
        }
        $ack = Select-Latest -Items @($acks | Where-Object { $_.parent_message_id -eq $response.message_id -and $_.status -eq "ACKNOWLEDGED" })
        if (-not $ack) {
            $pendingResponses += $response
            continue
        }
        $resolved = Select-Latest -Items @($acks | Where-Object { $_.parent_message_id -eq $ack.message_id -and $_.status -eq "RESOLVED" })
        if (-not $resolved) {
            $pendingAcks += $ack
        }
    }

    return [ordered]@{
        requests = $requests
        responses = $responses
        acknowledgements = $acks
        pendingRequests = $pendingRequests
        pendingResponses = $pendingResponses
        pendingAcks = $pendingAcks
    }
}

function Has-Message {
    param([Parameter(Mandatory)]$Messages, [string]$Id)
    if ([string]::IsNullOrWhiteSpace($Id)) { return $false }
    foreach ($group in @("requests","responses","acknowledgements")) {
        $items = Get-MessageGroup -Messages $Messages -Group $group
        if (@($items | Where-Object { $_.message_id -eq $Id }).Count -gt 0) { return $true }
    }
    return $false
}

function Make-Message {
    param(
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Target,
        [Parameter(Mandatory)][string]$TaskId,
        [Parameter(Mandatory)][string]$Status,
        [string]$ParentMessageId,
        [string[]]$RelatedFiles = @(),
        [string[]]$RequestedInformation = @(),
        [string[]]$MissingInformation = @(),
        [string]$ResponseSummary = "",
        [double]$Confidence = 0.9
    )

    $payload = [ordered]@{
        source_device = $Source
        target_device = $Target
        task_id = $TaskId
        status = $Status
        requested_information = @($RequestedInformation)
        response_summary = $ResponseSummary
        missing_information = @($MissingInformation)
        related_files = @($RelatedFiles)
        confidence = $Confidence
    }
    $contentHash = Hash-Text -Text ($payload | ConvertTo-Json -Depth 10)
    $id = "msg-{0}" -f (Hash-Text -Text ("{0}|{1}|{2}|{3}|{4}" -f $Source, $Target, $TaskId, $Status, $contentHash)).Substring(0, 12)

    return [ordered]@{
        message_id = $id
        parent_message_id = $ParentMessageId
        created_at = (Get-Date).ToString("o")
        expires_at = (Get-Date).AddDays(1).ToString("o")
        schema_version = $script:MessageSchemaVersion
        source_device = $Source
        target_device = $Target
        task_id = $TaskId
        status = $Status
        content_hash = $contentHash
        source_file = $null
        related_files = @($RelatedFiles)
        requested_information = @($RequestedInformation)
        response_summary = $ResponseSummary
        missing_information = @($MissingInformation)
        confidence = $Confidence
        acknowledged_at = $null
    }
}

function Write-Message {
    param(
        [Parameter(Mandatory)]$Message,
        [Parameter(Mandatory)][string]$ShareRoot,
        [Parameter(Mandatory)][string]$Status
    )

    $dir = Join-Path $ShareRoot ("messages\" + $Status)
    Ensure-Directory -Path $dir
    $dest = Join-Path $dir ("{0}.json" -f $Message.message_id)
    if (Test-Path -LiteralPath $dest) {
        return [ordered]@{ wrote = $false; path = $dest }
    }
    Write-AtomicJson -Path $dest -Object $Message -Depth 10
    return [ordered]@{ wrote = $true; path = $dest }
}

function Append-Timeline {
    param(
        [Parameter(Mandatory)][string]$TimelinePath,
        [Parameter(Mandatory)][string]$Type,
        [Parameter(Mandatory)][string]$Source,
        [Parameter(Mandatory)][string]$Target,
        [Parameter(Mandatory)][string]$TaskId,
        [string]$CorrelationId,
        [string]$Result = "ok",
        [int]$DurationMs = 0,
        [AllowNull()][string]$ErrorCode,
        [hashtable]$Payload = @{}
    )

    if ([string]::IsNullOrWhiteSpace($CorrelationId)) { $CorrelationId = $TaskId }
    if (Test-Path -LiteralPath $TimelinePath) {
        foreach ($existingLine in Get-Content -LiteralPath $TimelinePath -ErrorAction SilentlyContinue) {
            try {
                $existing = $existingLine | ConvertFrom-Json -ErrorAction Stop
                if (
                    $existing.type -eq $Type -and
                    $existing.source_device -eq $Source -and
                    $existing.target_device -eq $Target -and
                    $existing.task_id -eq $TaskId -and
                    $existing.correlation_id -eq $CorrelationId
                ) { return }
            } catch {}
        }
    }

    $createdAt = (Get-Date).ToString("o")
    $event = [ordered]@{
        type = $Type
        source_device = $Source
        target_device = $Target
        task_id = $TaskId
        created_at = $createdAt
        timestamp = $createdAt
        role = $Source
        correlation_id = $CorrelationId
        result = $Result
        duration_ms = $DurationMs
        error_code = $ErrorCode
        payload = $Payload
    }
    Ensure-Directory -Path (Split-Path $TimelinePath)
    $line = ConvertTo-Json -InputObject $event -Depth 8 -Compress
    Add-Content -Path $TimelinePath -Value $line -Encoding UTF8
}

function Build-ContextGapCards {
    param([Parameter(Mandatory)]$Gaps)
    $cards = @()
    foreach ($g in $Gaps) {
        $action = switch ($g.type) {
            "MISSING" { "해당 항목 즉시 재수집" }
            "STALE" { "신선한 데이터로 재수집" }
            "CONFLICT" { "양쪽 값 비교 후 기준 적용" }
            "BLOCKED" { "블록 원인(락/의존성) 해제 후 재요청" }
            "UNACKNOWLEDGED" { "요청/응답 확인 처리 후 상태 정리" }
            "EVIDENCE_NEEDED" { "추가 근거(엔드포인트/실행 흔적) 수집" }
            default { "보조 근거 수집" }
        }
        $risk = switch ($g.type) {
            "BLOCKED" { "높음" }
            "CONFLICT" { "높음" }
            "MISSING" { "중요" }
            "STALE" { "주의" }
            "UNACKNOWLEDGED" { "주의" }
            default { "보통" }
        }
        $cards += [ordered]@{
            what_changed = $g.reason
            why_it_matters = "작업 재개/동기화 신뢰도에 영향"
            evidence = ("metric={0}, type={1}" -f $g.metric, $g.type)
            source_device = $g.source_device
            source_file = $g.source_file
            collected_at = $null
            missing_context = if ($g.type -in @("MISSING","STALE","EVIDENCE_NEEDED")) { "보완 필요" } else { "" }
            risk = $risk
            action = $action
            recommended_next_action = $action
            action_owner = if ($g.source_device) { $g.source_device } else { "shared" }
            confidence = [double]$g.confidence
            type = $g.type
            metric = $g.metric
        }
    }
    return $cards
}

function Build-DashboardJson {
    param(
        [Parameter(Mandatory)]$Desktop,
        [Parameter(Mandatory)]$Notebook,
        [Parameter(Mandatory)]$Rows,
        [Parameter(Mandatory)]$Gaps,
        [Parameter(Mandatory)]$Messages,
        [string]$TaskId
    )

    $chain = Get-PendingMessageState -Messages $Messages
    $requests = @($chain.requests)
    $responses = @($chain.responses)
    $acks = @($chain.acknowledgements)
    $cards = Build-ContextGapCards -Gaps $Gaps

    return [ordered]@{
        schemaVersion = $script:DashboardSchemaVersion
        generatedAt = (Get-Date).ToString("o")
        taskId = $TaskId
        statusFlow = $script:StatusFlow
        desktop = [ordered]@{
            host = $Desktop.host
            nodeId = $Desktop.nodeId
            machineFingerprintHash = $Desktop.machineFingerprintHash
            collectedAt = $Desktop.collectedAt
            status = $Desktop.status
            ageMinutes = $Desktop.ageMinutes
        }
        notebook = [ordered]@{
            host = $Notebook.host
            nodeId = $Notebook.nodeId
            machineFingerprintHash = $Notebook.machineFingerprintHash
            collectedAt = $Notebook.collectedAt
            status = $Notebook.status
            ageMinutes = $Notebook.ageMinutes
        }
        summary = [ordered]@{
            gap_count = @($Gaps).Count
            missing_count = @($Gaps | Where-Object { $_.type -eq "MISSING" }).Count
            stale_count = @($Gaps | Where-Object { $_.type -eq "STALE" }).Count
            conflict_count = @($Gaps | Where-Object { $_.type -eq "CONFLICT" }).Count
            blocked_count = @($Gaps | Where-Object { $_.type -eq "BLOCKED" }).Count
            evidence_needed_count = @($Gaps | Where-Object { $_.type -eq "EVIDENCE_NEEDED" }).Count
            unacknowledged_count = @($Gaps | Where-Object { $_.type -eq "UNACKNOWLEDGED" }).Count
            pending_requests = @($chain.pendingRequests).Count
            pending_responses = @($chain.pendingResponses).Count
            pending_acknowledgements = @($chain.pendingAcks).Count
            pending_acks = @($chain.pendingAcks).Count
        }
        rows = @($Rows)
        gaps = @($Gaps)
        context_gap_cards = @($cards)
        top_summary = [ordered]@{
            desktop_status = $Desktop.status
            notebook_status = $Notebook.status
            desktop_online = ($Desktop.status -ne "offline")
            notebook_online = ($Notebook.status -ne "offline")
            delta_collectedAt_minutes = if ($Desktop.ageMinutes -and $Notebook.ageMinutes) { [Math]::Round([Math]::Abs($Desktop.ageMinutes - $Notebook.ageMinutes), 2) } else { $null }
            resume_possible = (($requests.Count -eq 0) -and ($Gaps | Where-Object { $_.type -in @("MISSING","STALE","CONFLICT","BLOCKED","EVIDENCE_NEEDED","UNACKNOWLEDGED") }).Count -eq 0)
        }
        messages = [ordered]@{
            requests = @($requests | Select-Object message_id,status,source_device,target_device,task_id,created_at)
            responses = @($responses | Select-Object message_id,status,source_device,target_device,task_id,parent_message_id,created_at)
            acknowledgements = @($acks | Select-Object message_id,status,source_device,target_device,task_id,parent_message_id,created_at)
        }
        dedupe = $Messages.dedupe
    }
}

function Build-ContextPack {
    param(
        [Parameter(Mandatory)]$DesktopSummary,
        [Parameter(Mandatory)]$NotebookSummary,
        [Parameter(Mandatory)]$Rows,
        [Parameter(Mandatory)]$Gaps,
        [Parameter(Mandatory)]$Messages,
        [Parameter(Mandatory)]$TimelineRows
    )

    $chain = Get-PendingMessageState -Messages $Messages
    $requests = @($chain.requests)
    $responses = @($chain.pendingResponses)
    $unack = @($chain.pendingRequests)
    $desktopFiles = @($DesktopSummary.repoFacts | ForEach-Object { $_.path })
    $notebookFiles = @($NotebookSummary.repoFacts | ForEach-Object { $_.path })
    $onlyDesktop = @($desktopFiles | Where-Object { $_ -and ($notebookFiles -notcontains $_) }) | Select-Object -Unique
    $onlyNotebook = @($notebookFiles | Where-Object { $_ -and ($desktopFiles -notcontains $_) }) | Select-Object -Unique
    $unackLines = if (-not $unack) { @("- 미확인 요청 없음") } else { @("- 미확인 요청 개수: $($unack.Count)") + @($unack | ForEach-Object { "- $($_.message_id) task=$($_.task_id) requested=$($_.created_at)" }) }
    $onlyFilesLines = if (-not $onlyDesktop -and -not $onlyNotebook) {
        @("- 공통 repo만 사용 가능")
    } else {
        @()
        + @("- desktop-only: $($onlyDesktop.Count)") + @($onlyDesktop | ForEach-Object { "- $($_)" })
        + @("- notebook-only: $($onlyNotebook.Count)") + @($onlyNotebook | ForEach-Object { "- $($_)" })
    }

    $canResume = if (($requests.Count -eq 0) -and ($unack.Count -eq 0) -and (($Gaps | Where-Object { $_.type -in @("MISSING", "STALE", "CONFLICT", "BLOCKED", "EVIDENCE_NEEDED") }).Count -eq 0)) { "가능" } else { "보강 필요" }

    $cards = Build-ContextGapCards -Gaps $Gaps
    $acks = @($chain.pendingAcks)
    $missingOrStale = @($Gaps | Where-Object { $_.type -in @("MISSING","STALE","EVIDENCE_NEEDED") })
    $conflicts = @($Gaps | Where-Object { $_.type -eq "CONFLICT" })
    return @"
# 1) 전체 상황 요약
- desktop=$($DesktopSummary.status), notebook=$($NotebookSummary.status)
- 마지막 수집: desktop=$($DesktopSummary.collectedAt), notebook=$($NotebookSummary.collectedAt)
- 갱신 임계: $StaleMinutes 분
- 이어받기 가능성: $canResume
- 남은 지연시간: desktop=$($DesktopSummary.ageMinutes)min, notebook=$($NotebookSummary.ageMinutes)min
    - 미확인 요청: $(@($unack).Count) / 보류 응답: $(@($responses).Count) / 미확인 ACK: $(@($acks).Count)

# 2) 노트북 상태
- host=$($NotebookSummary.host)
- 운영체제: $($NotebookSummary.os.caption)
- 업타임(분): $($NotebookSummary.os.bootUptimeMinutes)
- CPU: $($NotebookSummary.cpu.name) (현재 $($NotebookSummary.cpu.currentLoadPercent)% / $($NotebookSummary.cpu.logical)논리코어)
- RAM: $($NotebookSummary.memory.total_gb)GB / 사용률 $($NotebookSummary.memory.used_percent)%
- 열림 포트: @($NotebookSummary.ports | Where-Object { $_.open }).Count
- 최근 빌드 파일: @($NotebookSummary.repoFacts | ForEach-Object { $_.build.latestArtifact.path } | Where-Object { $_ })

# 3) 데스크톱 상태
- host=$($DesktopSummary.host)
- 운영체제: $($DesktopSummary.os.caption)
- 업타임(분): $($DesktopSummary.os.bootUptimeMinutes)
- CPU: $($DesktopSummary.cpu.name) (현재 $($DesktopSummary.cpu.currentLoadPercent)% / $($DesktopSummary.cpu.logical)논리코어)
- RAM: $($DesktopSummary.memory.total_gb)GB / 사용률 $($DesktopSummary.memory.used_percent)%
- 열림 포트: @($DesktopSummary.ports | Where-Object { $_.open }).Count
- 최근 빌드 파일: @($DesktopSummary.repoFacts | ForEach-Object { $_.build.latestArtifact.path } | Where-Object { $_ })

# 4) 장비 간 차이
$(($Rows | Select-Object -First 10 | ForEach-Object { "- $($_.metric): D=$($_.desktop), N=$($_.notebook), Δ=$($_.delta)" }) -join "`n")

# 5) 최근 변경 타임라인
$(if ($TimelineRows.Count -eq 0) { "- 없음" } else { $TimelineRows | ForEach-Object { "- $($_.type) $($_.source_device)->$($_.target_device) $($_.task_id) $($_.created_at)" } })

# 6) 진행 중 요청과 응답
$(if (-not $requests) { "- 없음" } else { $requests | ForEach-Object { "- REQUESTED $($_.message_id) task=$($_.task_id) target=$($_.target_device) created=$($_.created_at)" } })
$(if (-not $unack) { "`n" + ($unackLines -join "`n") } else { "`n" + ($unackLines -join "`n") })

# 7) 부족하거나 오래된 정보
$(if (-not $missingOrStale) { "- 없음" } else { $missingOrStale | ForEach-Object { "- [$($_.type)] $($_.metric) : $($_.reason)" } })

# 8) 충돌 사항
$(if (-not $conflicts) { "- 없음" } else { $conflicts | ForEach-Object { "- $($_.metric) - $($_.reason)" } })

# 9) 이어서 작업할 때 읽어야 할 파일
$(if (-not $onlyDesktop -and -not $onlyNotebook) { "- 공통 repo만 사용 가능" } else { 
    ($onlyFilesLines -join "`n")
})

# 10) 다음 행동
$(if ($canResume -eq "가능") { "- 이어받아도 됨: runId 기준으로 2단계(갭 재검증) 실행" } else { "- 부족 정보/미확인 상태를 우선 정리 후 반복 실행" })

---
# 중요 갭 카드
$(if (-not $cards) { "- 없음" } else { $cards | ForEach-Object { "- [$($_.type)] $($_.metric) / $($_.source_device) / $($_.action)" } })
"@
}

function Build-ContextGapReport {
    param([Parameter(Mandatory)]$Gaps)
    $lines = @(
        "# 컨텍스트 갭 리포트",
        "| Type | Metric | Source | SourceFile | Confidence | Reason |",
        "|---|---|---|---|---:|---|"
    )
    foreach ($g in $Gaps) {
        $lines += ("| {0} | {1} | {2} | {3} | {4} | {5} |" -f $g.type, $g.metric, $g.source_device, $g.source_file, $g.confidence, $g.reason)
    }
    return $lines -join "`r`n"
}

function Build-PendingSummary {
    param([Parameter(Mandatory)]$Messages, [Parameter(Mandatory)]$TimelinePath)
    $chain = Get-PendingMessageState -Messages $Messages
    return [ordered]@{
        generated_at = (Get-Date).ToString("o")
        schema_version = "device_probe_pending_summary_v1"
        pending_requests = @($chain.pendingRequests).Count
        unacknowledged_requests = @(
            $chain.pendingRequests | ForEach-Object {
                [ordered]@{
                    message_id = $_.message_id
                    source_device = $_.source_device
                    target_device = $_.target_device
                    task_id = $_.task_id
                    created_at = $_.created_at
                    requested_information = $_.requested_information
                }
            }
        )
        pending_responses = @($chain.pendingResponses)
        pending_response_count = @($chain.pendingResponses).Count
        pending_acknowledgements = @($chain.pendingAcks)
        pending_acks = @($chain.pendingAcks).Count
        timeline_file = $TimelinePath
    }
}

function Compare-Mode-Output {
    param(
        [Parameter(Mandatory)]$DesktopSummary,
        [Parameter(Mandatory)]$NotebookSummary,
        [Parameter(Mandatory)]$Rows,
        [Parameter(Mandatory)]$RunId
    )

    Ensure-Directory -Path $OutputDir
    $csvPath = Join-Path $OutputDir ("compare_{0}.csv" -f $RunId)
    $mdPath = Join-Path $OutputDir ("compare_{0}.md" -f $RunId)
    $jsonPath = Join-Path $OutputDir ("compare_{0}.json" -f $RunId)

    $Rows | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8
    $rowsMd = @($Rows | ForEach-Object { "| $($_.metric) | $($_.desktop) | $($_.notebook) | $($_.delta) |" })
    Set-Content -Path $mdPath -Value @"
# Desktop vs Notebook 비교 요약

| 항목 | Desktop | Notebook | Δ |
|---|---|---|---|
$($rowsMd -join "`r`n")
"@ -Encoding UTF8

    $json = [ordered]@{
        comparedAt = (Get-Date).ToString("o")
        schemaVersion = $script:CompareSchemaVersion
        status = "OK"
        desktopHost = $DesktopSummary.host
        notebookHost = $NotebookSummary.host
        desktopNodeId = $DesktopSummary.nodeId
        notebookNodeId = $NotebookSummary.nodeId
        desktopMachineFingerprintHash = $DesktopSummary.machineFingerprintHash
        notebookMachineFingerprintHash = $NotebookSummary.machineFingerprintHash
        rows = @($Rows)
    }
    Write-AtomicJson -Path $jsonPath -Object $json -Depth 10

    Write-Host "compare csv => $csvPath"
    Write-Host "compare md => $mdPath"
    Write-Host "compare json => $jsonPath"
}

function Run-Loop-Outputs {
    param(
        [Parameter(Mandatory)]$DesktopSummary,
        [Parameter(Mandatory)]$NotebookSummary,
        [Parameter(Mandatory)]$Rows,
        [Parameter(Mandatory)]$Gaps,
        [Parameter(Mandatory)]$Messages,
        [Parameter(Mandatory)][string]$TimelinePath,
        [Parameter(Mandatory)]$DashboardRoot
    )

    Ensure-Directory -Path $DashboardRoot
    $timelineRows = @()
    if (Test-Path -LiteralPath $TimelinePath) {
        foreach ($line in Get-Content -LiteralPath $TimelinePath -ErrorAction SilentlyContinue) {
            try { $timelineRows += ($line | ConvertFrom-Json) } catch {}
        }
    }

    $dashboardJsonPath = Join-Path $DashboardRoot "latest-dashboard.json"
    $dashboardMdPath = Join-Path $DashboardRoot "latest-dashboard.md"
    $contextPackPath = Join-Path $DashboardRoot "latest-context-pack.md"
    $gapReportPath = Join-Path $DashboardRoot "context-gap-report.md"
    $deviceDiffPath = Join-Path $DashboardRoot "device-diff.md"
    $pendingSummaryPath = Join-Path $ShareRoot "messages\pending-summary.json"
    $timelineSummaryPath = Join-Path $DashboardRoot "timeline-latest.json"
    $gapCards = Build-ContextGapCards -Gaps $Gaps
    $messagesSummary = Build-PendingSummary -Messages $Messages -TimelinePath $TimelinePath

    $dashboardObj = Build-DashboardJson -Desktop $DesktopSummary -Notebook $NotebookSummary -Rows $Rows -Gaps $Gaps -Messages $Messages -TaskId $TaskId
    Write-AtomicJson -Path $dashboardJsonPath -Object $dashboardObj -Depth 12

    $dashText = @"
## Desktop
- 상태: $($dashboardObj.desktop.status)
- 수집시각: $($dashboardObj.desktop.collectedAt)
- 상태 점수: $($dashboardObj.summary.gap_count)
- 누락 수: $($dashboardObj.summary.missing_count)

## Notebook
- 상태: $($dashboardObj.notebook.status)
- 수집시각: $($dashboardObj.notebook.collectedAt)
- 상태 점수: $($dashboardObj.summary.gap_count)
- 누락 수: $($dashboardObj.summary.missing_count)

## 핵심 상태
- 미확인 요청: $($dashboardObj.summary.pending_requests)
- 미확인 응답: $($dashboardObj.summary.pending_responses)
- 갭 타입: MISSING=$($dashboardObj.summary.missing_count), STALE=$($dashboardObj.summary.stale_count), CONFLICT=$($dashboardObj.summary.conflict_count), BLOCKED=$($dashboardObj.summary.blocked_count), EVIDENCE_NEEDED=$($dashboardObj.summary.evidence_needed_count)
"@
    Set-Content -Path $dashboardMdPath -Value $dashText -Encoding UTF8

    $contextPack = Build-ContextPack -DesktopSummary $DesktopSummary -NotebookSummary $NotebookSummary -Rows $Rows -Gaps $Gaps -Messages $Messages -TimelineRows $timelineRows
    Set-Content -Path $contextPackPath -Value $contextPack -Encoding UTF8

    $gapText = Build-ContextGapReport -Gaps $Gaps
    Set-Content -Path $gapReportPath -Value $gapText -Encoding UTF8

    $diffText = @(
        "# Device Diff",
        "| 항목 | Desktop | Notebook | Δ |",
        "|---|---|---|---|",
        ($Rows | ForEach-Object { "| $($_.metric) | $($_.desktop) | $($_.notebook) | $($_.delta) |" })
    ) -join "`r`n"
    Set-Content -Path $deviceDiffPath -Value $diffText -Encoding UTF8

    Write-AtomicJson -Path $pendingSummaryPath -Object $messagesSummary -Depth 12
    Write-AtomicJson -Path $timelineSummaryPath -Object $timelineRows -Depth 10

    Write-Host "dashboard => $dashboardJsonPath"
    Write-Host "context pack => $contextPackPath"
    Write-Host "gap report => $gapReportPath"
    Write-Host "device diff => $deviceDiffPath"
    Write-Host "pending summary => $pendingSummaryPath"
    Write-Host "timeline count => $($timelineRows.Count)"
}

if ($Mode -eq "compare" -and ((-not $DesktopProbe) -or (-not $NotebookProbe))) {
    throw "compare mode needs -DesktopProbe -NotebookProbe"
}

if ($Mode -eq "loop" -and -not $ShareRoot) {
    throw "loop mode needs -ShareRoot"
}

$selfRole = Resolve-Role -Role $SelfRole
$shareLockPath = $null
if ($Mode -eq "loop") {
    $ShareRoot = [Environment]::ExpandEnvironmentVariables($ShareRoot)
    if (-not (Test-Path -LiteralPath $ShareRoot)) {
        throw "share root not found: $ShareRoot"
    }
    Ensure-Directory -Path (Join-Path $ShareRoot "nodes\desktop\latest")
    Ensure-Directory -Path (Join-Path $ShareRoot "nodes\desktop\history")
    Ensure-Directory -Path (Join-Path $ShareRoot "nodes\notebook\latest")
    Ensure-Directory -Path (Join-Path $ShareRoot "nodes\notebook\history")
    Ensure-Directory -Path (Join-Path $ShareRoot "messages\requests")
    Ensure-Directory -Path (Join-Path $ShareRoot "messages\responses")
    Ensure-Directory -Path (Join-Path $ShareRoot "messages\acknowledgements")
    Ensure-Directory -Path (Join-Path $ShareRoot "timeline")
    Ensure-Directory -Path (Join-Path $ShareRoot "dashboard")
    $shareLockPath = Enter-ShareLock -Root $ShareRoot -Owner $WriterId -Purpose "device-probe-loop" -TtlMinutes $ShareLockTtlMinutes
}

try {
    if ($Mode -eq "loop" -and $ProbeFile) {
        $probeRef = Read-NodeProbe -Path $ProbeFile -ShareRoot $ShareRoot -Role $selfRole
        $localPayload = if ($probeRef.valid) { $probeRef.payload } else { $null }
        if ($localPayload -ne $null) {
            $nodeLatest = Join-Path (Join-Path $ShareRoot ("nodes\" + $selfRole)) "latest"
            $nodeHistory = Join-Path (Join-Path $ShareRoot ("nodes\" + $selfRole)) "history"
            Ensure-Directory -Path $nodeLatest
            Ensure-Directory -Path $nodeHistory

            $wrapped = [ordered]@{
                schema_version = $script:ShareSchemaVersion
                role = $selfRole
                host = if ($localPayload.host) { $localPayload.host.hostname } else { $env:COMPUTERNAME }
                published_at = (Get-Date).ToString("o")
                source_file = $ProbeFile
                payload = $localPayload
            }
            $latestNodePath = Join-Path $nodeLatest "device-probe.json"
            $historyNodePath = Join-Path $nodeHistory ("device-probe-{0}.json" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
            Write-AtomicJson -Path $latestNodePath -Object $wrapped -Depth 12
            Write-AtomicJson -Path $historyNodePath -Object $wrapped -Depth 12
            Append-Timeline -TimelinePath (Join-Path $ShareRoot "timeline\events.ndjson") -Type "PUBLISHED" -Source $selfRole -Target "shared" -TaskId $TaskId -Payload @{ path = $ProbeFile }
        }
    }

    if ($Mode -eq "compare") {
        $deskRef = Read-NodeProbe -Path $DesktopProbe -ShareRoot "" -Role "desktop"
        $noteRef = Read-NodeProbe -Path $NotebookProbe -ShareRoot "" -Role "notebook"
    } else {
        $deskRef = Read-NodeProbe -Path (Join-Path (Join-Path $ShareRoot "nodes\desktop\latest") "device-probe.json") -ShareRoot $ShareRoot -Role "desktop"
        $noteRef = Read-NodeProbe -Path (Join-Path (Join-Path $ShareRoot "nodes\notebook\latest") "device-probe.json") -ShareRoot $ShareRoot -Role "notebook"
    }

    $desk = Build-NodeSummary -NodeValidation $deskRef -Role "desktop"
    $note = Build-NodeSummary -NodeValidation $noteRef -Role "notebook"
    $pairIdentity = Get-NodePairIdentityStatus -Desktop $desk -Notebook $note
    if ($pairIdentity.invalidSameNode) {
        $invalidDir = if ($Mode -eq "loop") { Join-Path $ShareRoot "dashboard" } else { $OutputDir }
        $invalidPaths = Write-InvalidSameNodeOutput -Directory $invalidDir -Desktop $desk -Notebook $note -Reason $pairIdentity.reason -LoopOutput:($Mode -eq "loop")
        throw "INVALID_SAME_NODE reason=$($pairIdentity.reason) json=$($invalidPaths.jsonPath)"
    }
    $rows = Build-DiffRows -Desk $desk -Note $note
    $gaps = Detect-Gaps -Desk $desk -Note $note -StaleLimitMinutes $StaleMinutes

    $messages = @{
        requests = @()
        responses = @()
        acknowledgements = @()
        dedupe = @{
            requests = 0
            responses = 0
            acknowledgements = 0
        }
    }

    if ($Mode -eq "loop") {
        try {
            $messages = Load-All-Messages -ShareRoot $ShareRoot
        } catch {
            $messages = @{
                requests = @()
                responses = @()
                acknowledgements = @()
                dedupe = @{
                    requests = 0
                    responses = 0
                acknowledgements = 0
            }
        }
        }
    }

    if ($Mode -eq "loop") {
        if (-not $messages) {
            $messages = @{
                requests = @()
                responses = @()
                acknowledgements = @()
                dedupe = @{
                    requests = 0
                    responses = 0
                    acknowledgements = 0
                }
            }
        } elseif (-not $(
            if ($messages -is [System.Collections.IDictionary]) {
                $messages.Contains("dedupe")
            } else {
                $messages.PSObject -and ($messages.PSObject.Properties.Name -contains "dedupe")
            }
        )) {
            $messages = Normalize-MessageSet $messages
        }
    }

    if ($Mode -eq "loop") {
        Append-Timeline -TimelinePath (Join-Path $ShareRoot "timeline\events.ndjson") -Type "DISCOVERED" -Source $selfRole -Target "shared" -TaskId $TaskId -Payload @{ message = "probe loaded" }
        if ($ProbeFile -and $localPayload) {
            Append-Timeline -TimelinePath (Join-Path $ShareRoot "timeline\events.ndjson") -Type "PROBED" -Source $selfRole -Target "shared" -TaskId $TaskId -Payload @{ source_file = $ProbeFile }
        }

        $requestMessages = Get-MessageGroup -Messages $messages -Group "requests"
        $responseMessages = Get-MessageGroup -Messages $messages -Group "responses"
        $ackMessages = Get-MessageGroup -Messages $messages -Group "acknowledgements"

        if ($CreateRequest) {
            $target = if ($selfRole -eq "desktop") { "notebook" } else { "desktop" }
            $reqItems = @()
            foreach ($g in $gaps) {
                if ($g.type -in @("MISSING","STALE","CONFLICT","BLOCKED","EVIDENCE_NEEDED")) {
                    $reqItems += ("{0}:{1}" -f $g.type, $g.metric)
                }
            }
            if ($reqItems.Count -gt 0) {
                $already = @($requestMessages | Where-Object { $_.task_id -eq $TaskId -and $_.status -eq "REQUESTED" -and $_.source_device -eq $selfRole -and $_.target_device -eq $target })
                if ($already.Count -eq 0) {
                    $msg = Make-Message -Source $selfRole -Target $target -TaskId $TaskId -Status "REQUESTED" -ParentMessageId $null -RelatedFiles @() -RequestedInformation $reqItems -MissingInformation @() -ResponseSummary "" -Confidence 0.95
                    $r = Write-Message -Message $msg -ShareRoot $ShareRoot -Status "requests"
                    if ($r.wrote) {
                        Append-Timeline -TimelinePath (Join-Path $ShareRoot "timeline\events.ndjson") -Type "REQUESTED" -Source $selfRole -Target $target -TaskId $TaskId -CorrelationId $msg.message_id -Payload @{ message_id = $msg.message_id }
                        $messages.requests += $msg
                        $requestMessages += $msg
                    }
                }
            }
        }

        if ($AutoRespond) {
            $pendingReq = @($requestMessages | Where-Object { $_.target_device -eq $selfRole -and $_.status -eq "REQUESTED" })
            foreach ($req in $pendingReq) {
                $already = @($responseMessages | Where-Object { $_.parent_message_id -eq $req.message_id })
                if ($already.Count -gt 0) { continue }
                $rsp = Make-Message -Source $selfRole -Target $req.source_device -TaskId $req.task_id -Status "RESPONDED" -ParentMessageId $req.message_id -RelatedFiles @("dashboard/latest-dashboard.json") -RequestedInformation @() -ResponseSummary ("요청 처리: desktop=$($desk.status), notebook=$($note.status)") -MissingInformation @() -Confidence 0.9
                $r = Write-Message -Message $rsp -ShareRoot $ShareRoot -Status "responses"
                if ($r.wrote) {
                    Append-Timeline -TimelinePath (Join-Path $ShareRoot "timeline\events.ndjson") -Type "RESPONDED" -Source $selfRole -Target $req.source_device -TaskId $req.task_id -CorrelationId $req.message_id -Payload @{ message_id = $rsp.message_id }
                    $messages.responses += $rsp
                    $responseMessages += $rsp
                }
            }
        }

        if ($Acknowledge) {
            foreach ($rsp in @($responseMessages | Where-Object { $_.target_device -eq $selfRole -and $_.status -eq "RESPONDED" })) {
                $already = @($ackMessages | Where-Object { $_.parent_message_id -eq $rsp.message_id })
                if ($already.Count -gt 0) { continue }
                $ack = Make-Message -Source $selfRole -Target $rsp.source_device -TaskId $rsp.task_id -Status "ACKNOWLEDGED" -ParentMessageId $rsp.message_id -RelatedFiles @() -RequestedInformation @() -ResponseSummary "acknowledged" -MissingInformation @() -Confidence 0.98
                $ack.acknowledged_at = (Get-Date).ToString("o")
                $r = Write-Message -Message $ack -ShareRoot $ShareRoot -Status "acknowledgements"
                if ($r.wrote) {
                    Append-Timeline -TimelinePath (Join-Path $ShareRoot "timeline\events.ndjson") -Type "ACKNOWLEDGED" -Source $selfRole -Target $rsp.source_device -TaskId $rsp.task_id -CorrelationId $rsp.parent_message_id -Payload @{ message_id = $ack.message_id }
                    $messages.acknowledgements = @($messages.acknowledgements) + @($ack)
                    $ackMessages = @($ackMessages) + @($ack)

                    $resolved = Make-Message -Source $selfRole -Target $rsp.source_device -TaskId $rsp.task_id -Status "RESOLVED" -ParentMessageId $ack.message_id -RelatedFiles @() -RequestedInformation @() -ResponseSummary "request resolved" -MissingInformation @() -Confidence 0.99
                    $resolvedWrite = Write-Message -Message $resolved -ShareRoot $ShareRoot -Status "acknowledgements"
                    if ($resolvedWrite.wrote) {
                        Append-Timeline -TimelinePath (Join-Path $ShareRoot "timeline\events.ndjson") -Type "RESOLVED" -Source $selfRole -Target $rsp.source_device -TaskId $rsp.task_id -CorrelationId $rsp.parent_message_id -Payload @{ message_id = $resolved.message_id }
                        $messages.acknowledgements = @($messages.acknowledgements) + @($resolved)
                        $ackMessages = @($ackMessages) + @($resolved)
                    }
                }
            }
        }

        $runId = Get-Date -Format "yyyyMMdd_HHmmss"
        if (-not $messages) {
            $messages = @{
                requests = @()
                responses = @()
                acknowledgements = @()
                dedupe = @{
                    requests = 0
                    responses = 0
                    acknowledgements = 0
                }
            }
        }
        $hasDedupe = if ($messages -is [System.Collections.IDictionary]) { $messages.Contains("dedupe") } else { $messages.PSObject -and ($messages.PSObject.Properties.Name -contains "dedupe") }
        $safeMessages = if ($hasDedupe) { $messages } else { Normalize-MessageSet -Messages $messages }
        $gaps = Detect-Gaps -Desk $desk -Note $note -StaleLimitMinutes $StaleMinutes -Messages $safeMessages
        Run-Loop-Outputs -DesktopSummary $desk -NotebookSummary $note -Rows $rows -Gaps $gaps -Messages $safeMessages -TimelinePath (Join-Path $ShareRoot "timeline\events.ndjson") -DashboardRoot (Join-Path $ShareRoot "dashboard")
        if ($IncludeRaw -and $deskRef.path -and $noteRef.path) {
            $rawRoot = Join-Path $ShareRoot "dashboard"
            Copy-Item -LiteralPath $deskRef.path -Destination (Join-Path $rawRoot ("desktop-source-{0}.json" -f $runId)) -Force
            Copy-Item -LiteralPath $noteRef.path -Destination (Join-Path $rawRoot ("notebook-source-{0}.json" -f $runId)) -Force
        }
    } else {
        Compare-Mode-Output -DesktopSummary $desk -NotebookSummary $note -Rows $rows -RunId (Get-Date -Format "yyyyMMdd_HHmmss")
    }
} finally {
    if ($shareLockPath) {
        Exit-ShareLock -Path $shareLockPath -Owner $WriterId
    }
}
