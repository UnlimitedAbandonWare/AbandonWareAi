Set-StrictMode -Version Latest

$script:AwxSnapshotHeadings = @(
    '## Snapshot Status',
    '## Java and Build',
    '## Windows and Hardware',
    '## Local Runtime Services',
    '## Repository and SMB',
    '## MariaDB Metadata',
    '## Agent DB Context',
    '## Evidence Needed',
    '## Integrity and Refresh'
)

class AwxDesktopDbSettings {
    [bool] $Resolved
    [string] $Reason
    hidden [string] $Url
    hidden [string] $Username
    hidden [string] $Password
    hidden [string] $Driver

    AwxDesktopDbSettings([bool]$resolved, [string]$reason, [string]$url, [string]$username, [string]$password, [string]$driver) {
        $this.Resolved = $resolved
        $this.Reason = $reason
        $this.Url = $url
        $this.Username = $username
        $this.Password = $password
        $this.Driver = $driver
    }

    [string] ToString() {
        return '[desktop-db-settings:redacted]'
    }
}

function ConvertTo-AwxProcessArgument {
    param([Parameter(Mandatory)][string]$Value)

    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Invoke-AwxBoundedProcess {
    param(
        [Parameter(Mandatory)][string]$FileName,
        [Parameter(Mandatory)][string[]]$ArgumentList,
        [Parameter(Mandatory)][ValidateRange(1, 120)][int]$TimeoutSeconds,
        [Parameter(Mandatory)][string]$WorkingDirectory
    )

    $process = $null
    $timedOut = $false
    $stdoutTask = $null
    $stderrTask = $null
    try {
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.WorkingDirectory = $WorkingDirectory
        if ([IO.Path]::GetExtension($FileName) -in @('.bat', '.cmd')) {
            $command = ((@($FileName) + $ArgumentList) | ForEach-Object { ConvertTo-AwxProcessArgument ([string]$_) }) -join ' '
            $startInfo.FileName = $env:ComSpec
            $startInfo.Arguments = '/d /s /c "' + $command + '"'
        } else {
            $startInfo.FileName = $FileName
            $startInfo.Arguments = ($ArgumentList | ForEach-Object { ConvertTo-AwxProcessArgument ([string]$_) }) -join ' '
        }

        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        if (-not $process.Start()) { throw 'child-process-start-failed' }
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        $exited = $process.WaitForExit($TimeoutSeconds * 1000)
        if (-not $exited) {
            $timedOut = $true
            try { $process.Kill() } catch { }
            $exited = $process.WaitForExit(2000)
        }
        $stdoutComplete = $stdoutTask.Wait(2000)
        $stderrComplete = $stderrTask.Wait(2000)
        $stdout = if ($stdoutComplete) { [string]$stdoutTask.Result } else { '' }
        $stderr = if ($stderrComplete) { [string]$stderrTask.Result } else { '' }
        if ($stdout.Length -gt 1MB) { $stdout = '' }
        if ($stderr.Length -gt 1MB) { $stderr = '' }
        return [pscustomobject][ordered]@{
            TimedOut = $timedOut
            ExitCode = if ($process.HasExited) { $process.ExitCode } else { -1 }
            Stdout = $stdout
            Stderr = $stderr
            ChildProcessExited = $process.HasExited
        }
    } catch {
        return [pscustomobject][ordered]@{
            TimedOut = $false
            ExitCode = -1
            Stdout = ''
            Stderr = ''
            ChildProcessExited = if ($null -ne $process) { $process.HasExited } else { $true }
        }
    } finally {
        if ($null -ne $process) { $process.Dispose() }
    }
}

function ConvertTo-AwxValue {
    param([AllowNull()]$Value)

    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        $ordered = [ordered]@{}
        foreach ($key in $Value.Keys) {
            $ordered[[string]$key] = ConvertTo-AwxValue $Value[$key]
        }
        return $ordered
    }
    if ($Value -is [pscustomobject]) {
        $ordered = [ordered]@{}
        foreach ($property in $Value.PSObject.Properties) {
            if ($property.MemberType -notin @('NoteProperty', 'Property', 'AliasProperty', 'ScriptProperty')) { continue }
            $ordered[$property.Name] = ConvertTo-AwxValue $property.Value
        }
        return $ordered
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [string]) {
        $items = @()
        foreach ($item in $Value) {
            $items += ,(ConvertTo-AwxValue $item)
        }
        return $items
    }
    return $Value
}

function Resolve-AwxDesktopDbSettings {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][string]$ProfileConfig
    )

    $rootFull = [IO.Path]::GetFullPath($RepoRoot).TrimEnd('\', '/')
    $resourcesRoot = [IO.Path]::GetFullPath((Join-Path $rootFull 'main\resources')).TrimEnd('\', '/')
    $profileFull = if ([IO.Path]::IsPathRooted($ProfileConfig)) {
        [IO.Path]::GetFullPath($ProfileConfig)
    } else {
        [IO.Path]::GetFullPath((Join-Path $rootFull $ProfileConfig))
    }
    if (-not $profileFull.StartsWith($resourcesRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'profile-config-outside-main-resources'
    }
    if (-not (Test-Path -LiteralPath $profileFull -PathType Leaf)) {
        return [AwxDesktopDbSettings]::new($false, 'db-credentials-unresolved', $null, $null, $null, $null)
    }
    Test-AwxSnapshotPathForReparsePoint -Path $profileFull

    $expectedEnvironment = [ordered]@{
        url = 'DESKTOP_DB_URL'
        username = 'DESKTOP_DB_USERNAME'
        password = 'DESKTOP_DB_PASSWORD'
        'driver-class-name' = 'DESKTOP_DB_DRIVER'
    }
    $placeholders = @{}
    $inSpring = $false
    $inDatasource = $false
    $invalid = $false
    foreach ($line in [IO.File]::ReadAllLines($profileFull, [Text.Encoding]::UTF8)) {
        if ($line -match '^spring:\s*(?:#.*)?$') {
            $inSpring = $true
            $inDatasource = $false
            continue
        }
        if ($line -match '^\S') {
            $inSpring = $false
            $inDatasource = $false
            continue
        }
        if ($inSpring -and $line -match '^  datasource:\s*(?:#.*)?$') {
            $inDatasource = $true
            continue
        }
        if (-not $inDatasource) { continue }
        if ($line -match '^  \S') {
            $inDatasource = $false
            continue
        }
        if ([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*#') { continue }
        if ($line -match '^    (?<key>[a-z-]+):\s*(?<value>.*?)\s*$') {
            $key = $Matches.key
            if (-not $expectedEnvironment.Contains($key) -or $placeholders.ContainsKey($key)) {
                $invalid = $true
                continue
            }
            $placeholders[$key] = $Matches.value
            continue
        }
        if ($line -match '^      ') { $invalid = $true }
    }

    $resolvedValues = @{}
    foreach ($key in $expectedEnvironment.Keys) {
        if ($invalid -or -not $placeholders.ContainsKey($key)) {
            return [AwxDesktopDbSettings]::new($false, 'db-credentials-unresolved', $null, $null, $null, $null)
        }
        $match = [regex]::Match([string]$placeholders[$key], '^\$\{(?<name>[A-Z][A-Z0-9_]*)(?::(?<fallback>[^{}]*))?\}$')
        if (-not $match.Success -or $match.Groups['name'].Value -ne $expectedEnvironment[$key]) {
            return [AwxDesktopDbSettings]::new($false, 'db-credentials-unresolved', $null, $null, $null, $null)
        }
        $processValue = [Environment]::GetEnvironmentVariable($expectedEnvironment[$key], 'Process')
        $value = if (-not [string]::IsNullOrWhiteSpace($processValue)) {
            $processValue
        } elseif ($match.Groups['fallback'].Success) {
            $match.Groups['fallback'].Value
        } else {
            $null
        }
        if ([string]::IsNullOrWhiteSpace($value) -or $value.Contains('${') -or $value.Contains('}')) {
            return [AwxDesktopDbSettings]::new($false, 'db-credentials-unresolved', $null, $null, $null, $null)
        }
        $resolvedValues[$key] = $value
    }
    return [AwxDesktopDbSettings]::new(
        $true,
        'ok',
        $resolvedValues.url,
        $resolvedValues.username,
        $resolvedValues.password,
        $resolvedValues['driver-class-name']
    )
}

function Get-AwxMysqlConnectorInitScriptText {
    return @'
gradle.projectsEvaluated {
    def rootRuntime = rootProject.configurations.findByName('runtimeClasspath')
    rootProject.tasks.register('awxPrintMysqlConnectorPath') {
        doLast {
            if (rootRuntime == null) {
                throw new GradleException('runtimeClasspath-unavailable')
            }
            def matches = rootRuntime.resolvedConfiguration.resolvedArtifacts.findAll {
                it.moduleVersion.id.group == 'com.mysql' && it.name == 'mysql-connector-j'
            }.collect { it.file.canonicalFile }.unique()
            if (matches.size() != 1) {
                throw new GradleException('mysql-connector-cardinality-invalid')
            }
            println(matches[0].absolutePath)
        }
    }
}
'@
}

function Resolve-AwxMysqlConnectorJar {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][ValidateRange(1, 120)][int]$TimeoutSeconds
    )

    $rootFull = [IO.Path]::GetFullPath($RepoRoot)
    $gradleWrapper = Join-Path $rootFull 'gradlew.bat'
    if (-not (Test-Path -LiteralPath $gradleWrapper -PathType Leaf)) { throw 'mysql-connector-unavailable' }
    $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-mysql-connector-' + [guid]::NewGuid().ToString('N'))
    $initScript = Join-Path $temporaryRoot 'connector.init.gradle'
    $projectCache = Join-Path $temporaryRoot 'project-cache'
    try {
        New-Item -ItemType Directory -Path $temporaryRoot,$projectCache -Force | Out-Null
        [IO.File]::WriteAllText($initScript, (Get-AwxMysqlConnectorInitScriptText), [Text.UTF8Encoding]::new($false))
        $result = Invoke-AwxBoundedProcess -FileName $gradleWrapper -ArgumentList @(
            '-I', $initScript, '-q', 'awxPrintMysqlConnectorPath', '--no-daemon', '--project-cache-dir', $projectCache
        ) -TimeoutSeconds $TimeoutSeconds -WorkingDirectory $rootFull
        if (-not $result.ChildProcessExited -or $result.TimedOut -or $result.ExitCode -ne 0) { throw 'mysql-connector-unavailable' }
        $matches = @()
        foreach ($line in @($result.Stdout -split '\r?\n')) {
            $candidate = $line.Trim()
            if ([string]::IsNullOrWhiteSpace($candidate) -or -not [IO.Path]::IsPathRooted($candidate)) { continue }
            $full = [IO.Path]::GetFullPath($candidate)
            if ((Split-Path -Leaf $full) -match '(?i)^mysql-connector-j-[^\\/]+\.jar$' -and (Test-Path -LiteralPath $full -PathType Leaf)) {
                $matches += $full
            }
        }
        $matches = @($matches | Select-Object -Unique)
        if ($matches.Count -ne 1) { throw 'mysql-connector-unavailable' }
        return $matches[0]
    } catch {
        throw 'mysql-connector-unavailable'
    } finally {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Get-AwxBuildFacts {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot
    )

    try {
        $rootFull = [IO.Path]::GetFullPath($RepoRoot)
        $gradleWrapper = Join-Path $rootFull 'gradlew.bat'
        $wrapperText = if (Test-Path -LiteralPath $gradleWrapper -PathType Leaf) {
            [IO.File]::ReadAllText($gradleWrapper, [Text.UTF8Encoding]::new($false))
        } else {
            ''
        }
        $rootBuild = Join-Path $rootFull 'build.gradle.kts'
        $buildText = if (Test-Path -LiteralPath $rootBuild -PathType Leaf) {
            [IO.File]::ReadAllText($rootBuild, [Text.UTF8Encoding]::new($false))
        } else {
            ''
        }
        $sourceSetCount = @(
            'main\java',
            'main\resources',
            'app\src\main\java_clean',
            'app\src\main\resources'
        ).Where({ Test-Path -LiteralPath (Join-Path $rootFull $_) }).Count
        return [ordered]@{
            status = 'ok'
            gradleWrapperVersion = if ($wrapperText -match 'Gradle ([0-9][0-9A-Za-z.\-+]*)') { $Matches[1] } else { 'unknown' }
            gradleVersion = if ($buildText -match 'gradleVersion\s*=\s*"([^"]+)"') { $Matches[1] } else { 'unknown' }
            rootDependencyCount = ([regex]::Matches($buildText, '(?m)^\s*(implementation|runtimeOnly|api|compileOnly|testImplementation)\s*\(')).Count
            sourceSetCount = $sourceSetCount
        }
    } catch {
        return [ordered]@{
            status = 'unavailable'
            gradleWrapperVersion = $null
            gradleVersion = $null
            rootDependencyCount = 0
            sourceSetCount = 0
        }
    }
}

function Get-AwxJavaFacts {
    [CmdletBinding()]
    param(
        [ValidateRange(1, 120)][int]$TimeoutSeconds = 5
    )

    $javaHome = [Environment]::GetEnvironmentVariable('JAVA_HOME', 'Process')
    $javaCommand = if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
        Join-Path $javaHome 'bin\java.exe'
    } else {
        'java'
    }
    try {
        $workingDirectory = if (-not [string]::IsNullOrWhiteSpace($PWD.ProviderPath)) { $PWD.ProviderPath } else { [Environment]::CurrentDirectory }
        $result = Invoke-AwxBoundedProcess -FileName $javaCommand -ArgumentList @('-XshowSettings:properties', '-version') -TimeoutSeconds $TimeoutSeconds -WorkingDirectory $workingDirectory
        $javaHomeValue = $null
        $versionValue = $null
        $vendorValue = $null
        foreach ($line in @($result.Stderr -split '\r?\n')) {
            if ($line -match '^\s*java\.home\s*=\s*(.+)$') { $javaHomeValue = $Matches[1].Trim() }
            elseif ($line -match '^\s*java\.vendor\s*=\s*(.+)$') { $vendorValue = $Matches[1].Trim() }
            elseif ($line -match '^\s*java\.version\s*=\s*(.+)$') { $versionValue = $Matches[1].Trim() }
        }
        return [ordered]@{
            status = if ($result.ExitCode -eq 0) { 'ok' } else { 'unavailable' }
            javaHome = if (-not [string]::IsNullOrWhiteSpace($javaHome)) { $javaHome } else { $javaHomeValue }
            'java.home' = $javaHomeValue
            version = $versionValue
            vendor = $vendorValue
            executableAvailable = ($result.ExitCode -eq 0 -and -not $result.TimedOut)
        }
    } catch {
        return [ordered]@{
            status = 'unavailable'
            javaHome = $javaHome
            'java.home' = $null
            version = $null
            vendor = $null
            executableAvailable = $false
        }
    }
}

function Get-AwxWindowsFacts {
    [CmdletBinding()]
    param()

    try {
        $os = Get-CimInstance -ClassName Win32_OperatingSystem -ErrorAction Stop
        return [ordered]@{
            status = 'ok'
            osCaption = $os.Caption
            build = $os.BuildNumber
            powerShellVersion = $PSVersionTable.PSVersion.ToString()
        }
    } catch {
        return [ordered]@{
            status = 'unavailable'
            osCaption = $null
            build = $null
            powerShellVersion = $PSVersionTable.PSVersion.ToString()
        }
    }
}

function Get-AwxHardwareFacts {
    [CmdletBinding()]
    param(
        [ValidateRange(1, 120)][int]$TimeoutSeconds = 5
    )

    $facts = [ordered]@{
        status = 'ok'
        cpu = $null
        logicalProcessors = 0
        physicalMemoryBytes = 0
        gpuName = $null
        gpuDriverVersion = $null
        gpuTotalMemoryBytes = 0
        gpuUsedMemoryBytes = 0
        gpuProbeReason = 'unavailable'
        drives = @()
    }
    try {
        $cpu = Get-CimInstance -ClassName Win32_Processor -ErrorAction Stop | Select-Object -First 1
        $computer = Get-CimInstance -ClassName Win32_ComputerSystem -ErrorAction Stop
        $drives = Get-CimInstance -ClassName Win32_LogicalDisk -Filter "DriveType=3" -ErrorAction Stop
        $facts.cpu = $cpu.Name
        $facts.logicalProcessors = [int]$cpu.NumberOfLogicalProcessors
        $facts.physicalMemoryBytes = [int64]$computer.TotalPhysicalMemory
        $facts.drives = @(
            foreach ($drive in $drives) {
                [ordered]@{
                    name = $drive.DeviceID
                    totalBytes = [int64]$drive.Size
                    freeBytes = [int64]$drive.FreeSpace
                }
            }
        )
    } catch {
        $facts.status = 'unavailable'
    }
    try {
        $workingDirectory = if (-not [string]::IsNullOrWhiteSpace($PWD.ProviderPath)) { $PWD.ProviderPath } else { [Environment]::CurrentDirectory }
        $gpuResult = Invoke-AwxBoundedProcess -FileName 'nvidia-smi' -ArgumentList @('--query-gpu=name,driver_version,memory.total,memory.used', '--format=csv,noheader,nounits') -TimeoutSeconds $TimeoutSeconds -WorkingDirectory $workingDirectory
        if ($gpuResult.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($gpuResult.Stdout)) {
            $line = @($gpuResult.Stdout -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) | Select-Object -First 1
            if ($null -ne $line) {
                $parts = @($line -split '\s*,\s*')
                if ($parts.Count -ge 4) {
                    $facts.gpuName = $parts[0]
                    $facts.gpuDriverVersion = $parts[1]
                    $facts.gpuTotalMemoryBytes = [int64]$parts[2] * 1MB
                    $facts.gpuUsedMemoryBytes = [int64]$parts[3] * 1MB
                    $facts.gpuProbeReason = 'ok'
                }
            }
        }
    } catch {
    }
    return $facts
}

function Get-AwxServiceFacts {
    [CmdletBinding()]
    param(
        [ValidateRange(1, 120)][int]$TimeoutSeconds = 3
    )

    $ports = @(3306, 8080, 8081, 11434, 11435, 11436)
    $facts = @()
    foreach ($port in $ports) {
        $bindingClass = 'unavailable'
        $ownerProcessName = $null
        $status = 'unavailable'
        try {
            $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
            if ($listeners.Count -gt 0) {
                $status = 'ok'
                $addresses = @($listeners | ForEach-Object { $_.LocalAddress } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
                if ($addresses.Count -eq 0 -or @($addresses | Where-Object { $_ -notin @('127.0.0.1', '::1') }).Count -gt 0) {
                    $bindingClass = 'public'
                } else {
                    $bindingClass = 'loopback'
                }
                $processId = ($listeners | Select-Object -First 1).OwningProcess
                if ($processId -gt 0) {
                    try {
                        $ownerProcessName = (Get-Process -Id $processId -ErrorAction Stop).ProcessName
                    } catch {
                        $ownerProcessName = $null
                    }
                }
            }
        } catch {
        }
        $facts += [pscustomobject][ordered]@{
            port = $port
            bindingClass = $bindingClass
            ownerProcessName = $ownerProcessName
            status = $status
        }
    }
    return @($facts)
}

function Get-AwxRepositoryFacts {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [ValidateRange(1, 120)][int]$TimeoutSeconds = 5
    )

    $rootFull = [IO.Path]::GetFullPath($RepoRoot)
    $gitDirResult = Invoke-AwxBoundedProcess -FileName 'git' -ArgumentList @('rev-parse', '--git-dir') -TimeoutSeconds $TimeoutSeconds -WorkingDirectory $rootFull
    $gitDir = if ($gitDirResult.ExitCode -eq 0) {
        $rawGitDir = ($gitDirResult.Stdout -split '\r?\n' | Select-Object -First 1).Trim()
        if ([IO.Path]::IsPathRooted($rawGitDir)) { [IO.Path]::GetFullPath($rawGitDir) } else { [IO.Path]::GetFullPath((Join-Path $rootFull $rawGitDir)) }
    } else {
        Join-Path $rootFull '.git'
    }
    $branchResult = Invoke-AwxBoundedProcess -FileName 'git' -ArgumentList @('branch', '--show-current') -TimeoutSeconds $TimeoutSeconds -WorkingDirectory $rootFull
    $headResult = Invoke-AwxBoundedProcess -FileName 'git' -ArgumentList @('rev-parse', '--short', 'HEAD') -TimeoutSeconds $TimeoutSeconds -WorkingDirectory $rootFull
    $statusResult = Invoke-AwxBoundedProcess -FileName 'git' -ArgumentList @('status', '--porcelain') -TimeoutSeconds $TimeoutSeconds -WorkingDirectory $rootFull
    $patchDropRoot = Join-Path $rootFull '__patch_drop__'
    $leaseRoot = Join-Path $rootFull 'data\source-edit-locks'
    return [ordered]@{
        status = 'ok'
        canonicalRoot = $rootFull
        branch = (($branchResult.Stdout -split '\r?\n' | Select-Object -First 1).Trim())
        head = (($headResult.Stdout -split '\r?\n' | Select-Object -First 1).Trim())
        dirtyCount = @($statusResult.Stdout -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }).Count
        indexLock = (Test-Path -LiteralPath (Join-Path $gitDir 'index.lock'))
        sourceLeaseCount = if (Test-Path -LiteralPath $leaseRoot) { (Get-ChildItem -LiteralPath $leaseRoot -File -ErrorAction SilentlyContinue | Measure-Object).Count } else { 0 }
        patchDropTopLevelCount = if (Test-Path -LiteralPath $patchDropRoot) { (Get-ChildItem -LiteralPath $patchDropRoot -File -Filter '*-v3.patch' -ErrorAction SilentlyContinue | Measure-Object).Count } else { 0 }
        patchDropPendingCount = if (Test-Path -LiteralPath $patchDropRoot) { (Get-ChildItem -LiteralPath $patchDropRoot -Recurse -File -Filter '*.patch' -ErrorAction SilentlyContinue | Measure-Object).Count } else { 0 }
        activeSourceSetCount = @(
            'main\java',
            'main\resources',
            'app\src\main\java_clean',
            'app\src\main\resources'
        ).Where({ Test-Path -LiteralPath (Join-Path $rootFull $_) }).Count
    }
}

function Get-AwxSmbFacts {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [ValidateRange(1, 120)][int]$TimeoutSeconds = 5
    )

    $probe = Join-Path ([IO.Path]::GetFullPath($RepoRoot)) 'scripts\verify_ydrive_backing_identity.ps1'
    if (-not (Test-Path -LiteralPath $probe -PathType Leaf)) {
        return [ordered]@{
            canonicalWorkspace = 'Y:\'
            backingShareIdentityVerified = $false
            backingShareIdentityReason = 'evidence-needed'
        }
    }
    $result = Invoke-AwxBoundedProcess -FileName 'powershell' -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $probe, '-ExpectedSha256', '30239E454C37CEFC507B305B4E828BB2AC291620C552BEC314D28909C189F8E9'
    ) -TimeoutSeconds $TimeoutSeconds -WorkingDirectory ([IO.Path]::GetFullPath($RepoRoot))
    if ($result.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($result.Stdout)) {
        try {
            return ConvertTo-AwxValue ($result.Stdout | ConvertFrom-Json -ErrorAction Stop)
        } catch {
        }
    }
    return [ordered]@{
        canonicalWorkspace = 'Y:\'
        backingShareIdentityVerified = $false
        backingShareIdentityReason = 'evidence-needed'
    }
}

function Get-AwxOllamaFacts {
    [CmdletBinding()]
    param(
        [ValidateRange(1, 120)][int]$TimeoutSeconds = 3,
        [AllowNull()][int[]]$ListeningPortsOverride = $null,
        [AllowNull()][scriptblock]$OllamaInvoker = $null
    )

    $ports = if ($null -ne $ListeningPortsOverride) { @($ListeningPortsOverride) } else { @(11435, 11434, 11436) }
    foreach ($port in $ports) {
        try {
            if ($null -eq $ListeningPortsOverride -and @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue).Count -eq 0) { continue }
            $response = if ($null -ne $OllamaInvoker) {
                & $OllamaInvoker $port
            } else {
                Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/api/tags" -f $port) -Method Get -TimeoutSec $TimeoutSeconds -ErrorAction Stop
            }
            $models = @($response.models)
            $modelName = $null
            if ($models.Count -gt 0) {
                $firstModel = $models | Select-Object -First 1
                $modelName = [string]($firstModel.name)
            }
            return [ordered]@{
                status = 'ok'
                port = $port
                bindingClass = 'loopback'
                ownerProcessName = 'ollama'
                endpointStatus = 'ok'
                modelCount = $models.Count
                modelIdentifier = $modelName
            }
        } catch {
        }
    }
    return [ordered]@{
        status = 'unavailable'
        port = if ($ports.Count -gt 0) { $ports[0] } else { 11435 }
        bindingClass = 'unavailable'
        ownerProcessName = 'ollama'
        endpointStatus = 'unavailable'
        modelCount = 0
        modelIdentifier = $null
    }
}

function Get-AwxSafeIntegerValue {
    param([AllowNull()]$Value)

    $parsed = 0
    if ([int]::TryParse([string]$Value, [ref]$parsed) -and $parsed -ge 0) { return $parsed }
    return 0
}

function Get-AwxSafeHashValue {
    param([AllowNull()]$Value)

    $candidate = [string]$Value
    if ($candidate -match '^[A-Fa-f0-9]{12,128}$') { return $candidate }
    return $null
}

function Test-AwxAllowedEvidenceBase {
    param([Parameter(Mandatory)][string]$Base)

    $allowed = @(
        'agent-db-runtime-unavailable',
        'db-auth-failed',
        'db-credentials-unresolved',
        'db-metadata-limit-exceeded',
        'db-metadata-skipped',
        'db-query-timeout',
        'db-readonly-contract-failed',
        'database-cleanup-failed',
        'database-connect-or-auth-failed',
        'database-limit-invalid',
        'database-url-scheme-unsupported',
        'internal-contract-failure',
        'json-serialization-failed',
        'mariadb-listener-unavailable',
        'metadata-changed-during-snapshot',
        'metadata-query-failed',
        'metadata-row-limit-reached',
        'mysql-connector-unavailable',
        'read-only-session-not-observed',
        'system-catalog-forbidden',
        'unsupported-arguments'
    )
    return $allowed -contains $Base
}

function Get-AwxSafeEvidenceReason {
    param([AllowNull()]$Value)

    $candidate = [string]$Value
    if ($candidate -match '^(?<base>[a-z][a-z0-9-]{1,127})(?::(?<suffix>[A-Za-z][A-Za-z0-9_]{0,63}))?$' -and (Test-AwxAllowedEvidenceBase $Matches['base'])) {
        return $candidate
    }
    return $null
}

function Invoke-AwxAgentDbSnapshotSeam {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [Parameter(Mandatory)][System.Collections.IDictionary]$Payload,
        [ValidateRange(1, 120)][int]$TimeoutSeconds = 5
    )

    $rootFull = [IO.Path]::GetFullPath($RepoRoot)
    $temporaryRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-agent-db-' + [guid]::NewGuid().ToString('N'))
    $runnerPath = Join-Path $temporaryRoot 'invoke_agent_db_snapshot.py'
    try {
        New-Item -ItemType Directory -Path $temporaryRoot -Force | Out-Null
        [IO.File]::WriteAllText($runnerPath, @'
import json
import os
import sys

repo_root = os.environ["AWX_AGENT_DB_REPO_ROOT"]
payload = json.loads(os.environ["AWX_AGENT_DB_SNAPSHOT_PAYLOAD"])
sys.path.insert(0, os.path.join(repo_root, "scripts"))
import awx_mcp_toolbox as toolbox
print(json.dumps(toolbox.agent_db_snapshot(payload)))
'@, [Text.UTF8Encoding]::new($false))
        $payloadJson = ($Payload | ConvertTo-Json -Compress -Depth 12)
        $repoEnv = [Environment]::GetEnvironmentVariable('AWX_AGENT_DB_REPO_ROOT', 'Process')
        $payloadEnv = [Environment]::GetEnvironmentVariable('AWX_AGENT_DB_SNAPSHOT_PAYLOAD', 'Process')
        try {
            [Environment]::SetEnvironmentVariable('AWX_AGENT_DB_REPO_ROOT', $rootFull, 'Process')
            [Environment]::SetEnvironmentVariable('AWX_AGENT_DB_SNAPSHOT_PAYLOAD', $payloadJson, 'Process')
            $result = Invoke-AwxBoundedProcess -FileName 'python' -ArgumentList @($runnerPath) -TimeoutSeconds $TimeoutSeconds -WorkingDirectory $rootFull
        } finally {
            [Environment]::SetEnvironmentVariable('AWX_AGENT_DB_REPO_ROOT', $repoEnv, 'Process')
            [Environment]::SetEnvironmentVariable('AWX_AGENT_DB_SNAPSHOT_PAYLOAD', $payloadEnv, 'Process')
        }
        if ($result.ExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($result.Stdout)) {
            throw 'agent-db-seam-unavailable'
        }
        return ConvertTo-AwxValue ($result.Stdout | ConvertFrom-Json -ErrorAction Stop)
    } finally {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function ConvertTo-AwxSafeAgentDbFacts {
    [CmdletBinding()]
    param([AllowNull()][System.Collections.IDictionary]$Result)

    $loaded = $false
    if ($null -ne $Result -and $Result.Contains('decision') -and [string]$Result.decision -eq 'agent_db_snapshot_loaded' -and $Result.Contains('ok') -and $Result.ok -eq $true) {
        $loaded = $true
    }
    $snapshot = if ($loaded -and $Result.Contains('snapshot') -and $Result.snapshot -is [System.Collections.IDictionary]) {
        $Result.snapshot
    } else {
        $null
    }
    $fallback = if ($null -ne $Result -and $Result.Contains('localFallback') -and $Result.localFallback -is [System.Collections.IDictionary]) {
        $Result.localFallback
    } else {
        $null
    }

    $memoryStatusCount = if ($null -ne $snapshot -and $snapshot.Contains('memoryStatusCount')) {
        Get-AwxSafeIntegerValue $snapshot.memoryStatusCount
    } elseif ($null -ne $fallback -and $fallback.Contains('failurePatternMemory') -and $fallback.failurePatternMemory -is [System.Collections.IDictionary] -and $fallback.failurePatternMemory.Contains('rowCountScanned')) {
        Get-AwxSafeIntegerValue $fallback.failurePatternMemory.rowCountScanned
    } else {
        0
    }
    $recentFailureReason = if ($null -ne $snapshot -and $snapshot.Contains('recentFailureReason')) {
        Get-AwxSafeEvidenceReason $snapshot.recentFailureReason
    } elseif ($null -ne $fallback -and $fallback.Contains('failurePatternMemory') -and $fallback.failurePatternMemory -is [System.Collections.IDictionary] -and $fallback.failurePatternMemory.Contains('lastRow') -and $fallback.failurePatternMemory.lastRow -is [System.Collections.IDictionary] -and $fallback.failurePatternMemory.lastRow.Contains('failureClass')) {
        Get-AwxSafeEvidenceReason $fallback.failurePatternMemory.lastRow.failureClass
    } else {
        $null
    }
    if ([string]::IsNullOrWhiteSpace($recentFailureReason)) { $recentFailureReason = 'agent-db-runtime-unavailable' }

    $recentFailureHash = if ($null -ne $snapshot -and $snapshot.Contains('recentFailureHash')) {
        Get-AwxSafeHashValue $snapshot.recentFailureHash
    } elseif ($null -ne $fallback -and $fallback.Contains('failurePatternMemory') -and $fallback.failurePatternMemory -is [System.Collections.IDictionary] -and $fallback.failurePatternMemory.Contains('lastRow') -and $fallback.failurePatternMemory.lastRow -is [System.Collections.IDictionary]) {
        if ($fallback.failurePatternMemory.lastRow.Contains('evidenceHash12')) {
            Get-AwxSafeHashValue $fallback.failurePatternMemory.lastRow.evidenceHash12
        } elseif ($fallback.failurePatternMemory.lastRow.Contains('intentHash12')) {
            Get-AwxSafeHashValue $fallback.failurePatternMemory.lastRow.intentHash12
        } else {
            $null
        }
    } else {
        $null
    }

    $strategyAggregateCount = if ($null -ne $snapshot -and $snapshot.Contains('strategyAggregateCount')) {
        Get-AwxSafeIntegerValue $snapshot.strategyAggregateCount
    } elseif ($null -ne $fallback -and $fallback.Contains('subsystemPersistence') -and $fallback.subsystemPersistence -is [System.Collections.IDictionary] -and $fallback.subsystemPersistence.Contains('strategy') -and $fallback.subsystemPersistence.strategy -is [System.Collections.IDictionary] -and $fallback.subsystemPersistence.strategy.Contains('entityCount')) {
        Get-AwxSafeIntegerValue $fallback.subsystemPersistence.strategy.entityCount
    } else {
        0
    }

    return [ordered]@{
        status = if ($loaded) { 'ok' } else { 'unavailable' }
        decision = if ($loaded) { 'agent_db_snapshot_loaded' } else { 'agent-db-runtime-unavailable' }
        memoryStatusCount = $memoryStatusCount
        recentFailureReason = $recentFailureReason
        recentFailureHash = $recentFailureHash
        strategyAggregateCount = $strategyAggregateCount
        evidenceNeeded = if ($loaded) { @() } else { @('agent-db-runtime-unavailable') }
    }
}

function Get-AwxAgentDbFacts {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [ValidateRange(1, 120)][int]$TimeoutSeconds = 5,
        [AllowNull()][int[]]$ListeningPortsOverride = $null,
        [AllowNull()][scriptblock]$AgentDbInvoker = $null
    )

    $runtimePorts = @(8080, 8081)
    $listeningPorts = @()
    if ($null -ne $ListeningPortsOverride) {
        $listeningPorts = @($ListeningPortsOverride | Where-Object { $runtimePorts -contains $_ } | Select-Object -Unique)
    } else {
        foreach ($port in $runtimePorts) {
            if (@(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue).Count -gt 0) {
                $listeningPorts += $port
            }
        }
    }
    if ($listeningPorts.Count -eq 0) {
        return [ordered]@{
            status = 'unavailable'
            decision = 'agent-db-runtime-unavailable'
            memoryStatusCount = 0
            recentFailureReason = 'agent-db-runtime-unavailable'
            recentFailureHash = $null
            strategyAggregateCount = 0
            evidenceNeeded = @('agent-db-runtime-unavailable')
        }
    }
    $payload = [ordered]@{
        root = $RepoRoot
        endpoint = 'snapshot'
        base_url = "http://127.0.0.1:$($listeningPorts[0])"
        timeout_sec = $TimeoutSeconds
    }
    try {
        $result = if ($null -ne $AgentDbInvoker) {
            ConvertTo-AwxValue (& $AgentDbInvoker $payload)
        } else {
            Invoke-AwxAgentDbSnapshotSeam -RepoRoot $RepoRoot -Payload $payload -TimeoutSeconds $TimeoutSeconds
        }
        return ConvertTo-AwxSafeAgentDbFacts -Result $result
    } catch {
        return [ordered]@{
            status = 'unavailable'
            decision = 'agent-db-runtime-unavailable'
            memoryStatusCount = 0
            recentFailureReason = 'agent-db-runtime-unavailable'
            recentFailureHash = $null
            strategyAggregateCount = 0
            evidenceNeeded = @('agent-db-runtime-unavailable')
        }
    }
}

function Invoke-AwxMariaDbMetadataHelper {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][AwxDesktopDbSettings]$Settings,
        [Parameter(Mandatory)][string]$ConnectorJar,
        [Parameter(Mandatory)][ValidateRange(1, 120)][int]$TimeoutSeconds,
        [Parameter(Mandatory)][ValidateRange(1, 10000)][int]$MaxRows
    )

    $javaSource = Join-Path (Split-Path -Parent $PSScriptRoot) 'DesktopMariaDbMetadataSnapshot.java'
    if (-not $Settings.Resolved) {
        return [ordered]@{
            status = 'evidence_needed'
            decision = 'query_failure'
            childProcessExited = $true
            rawDbRowStored = $false
            jdbcUrlStored = $false
            evidenceNeeded = @('db-credentials-unresolved:ConfigFailure')
        }
    }
    if (-not (Test-Path -LiteralPath $javaSource -PathType Leaf) -or -not (Test-Path -LiteralPath $ConnectorJar -PathType Leaf)) {
        return [ordered]@{
            status = 'evidence_needed'
            decision = 'query_failure'
            childProcessExited = $true
            rawDbRowStored = $false
            jdbcUrlStored = $false
            evidenceNeeded = @('mysql-connector-unavailable:InvalidOperationException')
        }
    }

    $process = $null
    $stdoutTask = $null
    $stderrTask = $null
    $timedOut = $false
    $settingsRef = $Settings
    $cleanupWaitMs = 1500
    $outerDeadlineMs = $TimeoutSeconds * 1000
    $stopwatch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $startInfo = [Diagnostics.ProcessStartInfo]::new()
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.FileName = 'java'
        $startInfo.WorkingDirectory = Split-Path -Parent $javaSource
        $startInfo.Arguments = (@('-cp', $ConnectorJar, $javaSource) | ForEach-Object { ConvertTo-AwxProcessArgument ([string]$_) }) -join ' '
        $startInfo.EnvironmentVariables['AWX_SNAPSHOT_DB_URL'] = $settingsRef.Url
        $startInfo.EnvironmentVariables['AWX_SNAPSHOT_DB_USERNAME'] = $settingsRef.Username
        $startInfo.EnvironmentVariables['AWX_SNAPSHOT_DB_PASSWORD'] = $settingsRef.Password
        $startInfo.EnvironmentVariables['AWX_SNAPSHOT_DB_DRIVER'] = $settingsRef.Driver
        $startInfo.EnvironmentVariables['AWX_SNAPSHOT_DB_MAX_ROWS'] = [string]$MaxRows
        $startInfo.EnvironmentVariables['AWX_SNAPSHOT_DB_TIMEOUT_SECONDS'] = [string]$TimeoutSeconds

        $process = [Diagnostics.Process]::new()
        $process.StartInfo = $startInfo
        if (-not $process.Start()) { throw [TimeoutException]::new('helper-start-failed') }

        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        while (-not $process.HasExited) {
            $remainingOuterMs = $outerDeadlineMs - [int]$stopwatch.ElapsedMilliseconds
            if ($remainingOuterMs -le 0) {
                $timedOut = $true
                break
            }
            [void]$process.WaitForExit([Math]::Min(200, $remainingOuterMs))
        }
        if ($timedOut) {
            if (-not $process.HasExited) {
                try { $process.Kill() } catch { }
            }
            $cleanupDeadlineMs = [int]$stopwatch.ElapsedMilliseconds + $cleanupWaitMs
            while (-not $process.HasExited -and [int]$stopwatch.ElapsedMilliseconds -lt $cleanupDeadlineMs) {
                $remainingCleanupMs = $cleanupDeadlineMs - [int]$stopwatch.ElapsedMilliseconds
                [void]$process.WaitForExit([Math]::Min(100, [Math]::Max($remainingCleanupMs, 1)))
            }
            if ($null -ne $stdoutTask) {
                $remainingCleanupMs = $cleanupDeadlineMs - [int]$stopwatch.ElapsedMilliseconds
                if ($remainingCleanupMs -gt 0) { [void]$stdoutTask.Wait($remainingCleanupMs) }
            }
            if ($null -ne $stderrTask) {
                $remainingCleanupMs = $cleanupDeadlineMs - [int]$stopwatch.ElapsedMilliseconds
                if ($remainingCleanupMs -gt 0) { [void]$stderrTask.Wait($remainingCleanupMs) }
            }
            return [ordered]@{
                status = 'evidence_needed'
                decision = 'query_failure'
                childProcessExited = $process.HasExited
                rawDbRowStored = $false
                jdbcUrlStored = $false
                evidenceNeeded = @('db-query-timeout:TimeoutException')
            }
        }

        $stdoutComplete = if ($null -ne $stdoutTask) { $stdoutTask.Wait($cleanupWaitMs) } else { $true }
        if ($null -ne $stderrTask) { [void]$stderrTask.Wait($cleanupWaitMs) }
        $stdout = if ($null -ne $stdoutTask -and $stdoutTask.IsCompleted) { [string]$stdoutTask.Result } else { '' }
        $parsed = ConvertTo-AwxValue ($stdout | ConvertFrom-Json -ErrorAction Stop)
        $parsed['childProcessExited'] = $process.HasExited
        if (-not $parsed.Contains('rawDbRowStored')) { $parsed['rawDbRowStored'] = $false }
        if (-not $parsed.Contains('jdbcUrlStored')) { $parsed['jdbcUrlStored'] = $false }
        if (-not $parsed.Contains('status')) {
            $parsed['status'] = if ($parsed.decision -eq 'connected') { 'ok' } else { 'evidence_needed' }
        }
        if (-not $parsed.Contains('connectionDecision') -and $parsed.Contains('decision')) {
            $parsed['connectionDecision'] = $parsed.decision
        }
        if ($parsed.Contains('summary') -and $parsed.summary -is [System.Collections.IDictionary]) {
            foreach ($countField in @('catalogCount', 'schemaCount', 'tableCount', 'columnCount')) {
                if ($parsed.summary.Contains($countField) -and -not $parsed.Contains($countField)) {
                    $parsed[$countField] = $parsed.summary[$countField]
                }
            }
        }
        return $parsed
    } catch {
        return [ordered]@{
            status = 'evidence_needed'
            decision = 'query_failure'
            childProcessExited = if ($null -ne $process) { $process.HasExited } else { $true }
            rawDbRowStored = $false
            jdbcUrlStored = $false
            evidenceNeeded = @("metadata-query-failed:$($_.Exception.GetType().Name)")
        }
    } finally {
        if ($null -ne $stopwatch) { $stopwatch.Stop() }
        if ($null -ne $process) { $process.Dispose() }
        if ($null -ne $startInfo) {
            foreach ($name in @('AWX_SNAPSHOT_DB_URL', 'AWX_SNAPSHOT_DB_USERNAME', 'AWX_SNAPSHOT_DB_PASSWORD', 'AWX_SNAPSHOT_DB_DRIVER', 'AWX_SNAPSHOT_DB_MAX_ROWS', 'AWX_SNAPSHOT_DB_TIMEOUT_SECONDS')) {
                try { $startInfo.EnvironmentVariables.Remove($name) | Out-Null } catch { }
            }
        }
        $settingsRef = $null
        $Settings = $null
    }
}

function ConvertTo-AwxMarkdownScalar {
    param($Value)

    if ($null -eq $Value) { return '' }
    $text = [string]$Value
    $text = $text -replace '[\r\n|]', ' '
    $text = $text -replace '[\x00-\x1F\x7F]', ''
    if ($text.Length -gt 500) { return $text.Substring(0, 500) }
    return $text
}

function ConvertTo-AwxSnapshotCategory {
    param([AllowNull()]$Value)

    $allowed = @(
        'ok', 'unavailable', 'evidence_needed', 'not_observed', 'complete', 'partial', 'failed', 'connected',
        'evidence-needed', 'java-runtime-unavailable', 'mysql-connector-unavailable', 'db-credentials-unresolved',
        'mariadb-listener-unavailable', 'db-auth-failed', 'db-query-timeout', 'db-metadata-limit-exceeded',
        'db-readonly-contract-failed', 'db-metadata-skipped', 'agent-db-runtime-unavailable', 'redaction-failed',
        'snapshot-integrity-failed', 'snapshot-publish-failed', 'snapshot-sidecar-publish-failed', 'query_failure',
        'input_failure', 'connect_or_auth_failure', 'internal_contract_failure', 'loopback', 'public'
    )
    $candidate = [string]$Value
    if ($allowed -contains $candidate) { return $candidate }
    return 'redacted'
}

function ConvertTo-AwxSnapshotEvidence {
    param([AllowNull()]$Value)

    $candidate = [string]$Value
    if ($candidate -match '^(?<base>[a-z][a-z0-9-]{1,127})(?::(?<suffix>[A-Za-z][A-Za-z0-9_]{0,63}))?$' -and (Test-AwxAllowedEvidenceBase $Matches['base'])) {
        return $candidate
    }
    return 'redacted'
}

function ConvertTo-AwxSnapshotInteger {
    param([AllowNull()]$Value)

    $parsed = 0L
    if ([Int64]::TryParse([string]$Value, [ref]$parsed) -and $parsed -ge 0) { return [string]$parsed }
    return 'redacted'
}

function ConvertTo-AwxSnapshotBoolean {
    param([AllowNull()]$Value)

    if ($Value -is [bool]) { return $Value.ToString().ToLowerInvariant() }
    return 'redacted'
}

function ConvertTo-AwxSnapshotIdentifier {
    param([AllowNull()]$Value)

    $candidate = [string]$Value
    if ($candidate -match '^[A-Za-z0-9][A-Za-z0-9._:\/-]{0,127}$') { return $candidate }
    return 'redacted'
}

function ConvertTo-AwxSnapshotHash {
    param([AllowNull()]$Value)

    $candidate = [string]$Value
    if ($candidate -match '^[A-Fa-f0-9]{12,128}$') { return $candidate }
    return 'redacted'
}

function Add-AwxAllowedSnapshotFields {
    param(
        [Parameter(Mandatory)][object]$Lines,
        [AllowNull()][object]$Values,
        [Parameter(Mandatory)][hashtable]$Allowed,
        [string[]]$CategoryKeys = @(),
        [string[]]$NumericKeys = @(),
        [string[]]$BooleanKeys = @(),
        [string[]]$IdentifierKeys = @(),
        [string[]]$HashKeys = @()
    )

    if ($null -eq $Values) { return }
    if ($Values -isnot [System.Collections.IDictionary]) {
        $Values = ConvertTo-AwxValue $Values
    }
    foreach ($key in $Allowed.Keys) {
        if ($Values.Contains($key) -and $null -ne $Values[$key] -and $Values[$key] -isnot [System.Collections.IDictionary] -and ($Values[$key] -is [string] -or $Values[$key] -isnot [System.Collections.IEnumerable])) {
            $rendered = if ($CategoryKeys -contains $key) { ConvertTo-AwxSnapshotCategory $Values[$key] }
                elseif ($NumericKeys -contains $key) { ConvertTo-AwxSnapshotInteger $Values[$key] }
                elseif ($BooleanKeys -contains $key) { ConvertTo-AwxSnapshotBoolean $Values[$key] }
                elseif ($IdentifierKeys -contains $key) { ConvertTo-AwxSnapshotIdentifier $Values[$key] }
                elseif ($HashKeys -contains $key) { ConvertTo-AwxSnapshotHash $Values[$key] }
                else { ConvertTo-AwxMarkdownScalar $Values[$key] }
            $Lines.Add("- $($Allowed[$key]): $rendered")
        }
    }
}

function Add-AwxBoundedObjectCounts {
    param(
        [Parameter(Mandatory)][object]$Lines,
        [AllowNull()][System.Collections.IDictionary]$Counts
    )

    if ($null -eq $Counts) { return }
    $added = 0
    foreach ($name in @($Counts.Keys | Sort-Object)) {
        if ($added -ge 32 -or $name -notmatch '^[A-Za-z][A-Za-z0-9_.-]{0,127}$') { continue }
        $count = 0L
        if (-not [Int64]::TryParse([string]$Counts[$name], [ref]$count) -or $count -lt 0) { continue }
        $Lines.Add("- ${name}: $count")
        $added++
    }
}

function Test-AwxSnapshotPathForReparsePoint {
    param([Parameter(Mandatory)][string]$Path)

    $currentPath = [IO.Path]::GetFullPath($Path)
    while (-not [string]::IsNullOrWhiteSpace($currentPath)) {
        $item = Get-Item -LiteralPath $currentPath -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'snapshot-output-reparse-forbidden'
        }
        $parentPath = Split-Path -Parent $currentPath
        if ([string]::IsNullOrWhiteSpace($parentPath) -or $parentPath -eq $currentPath) { break }
        $currentPath = $parentPath
    }
}

function Resolve-AwxSnapshotOutputDirectory {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Root,
        [Parameter(Mandatory)][string]$RelativePath
    )

    if ([System.IO.Path]::IsPathRooted($RelativePath) -or $RelativePath -match '^[\\/]{2}') {
        throw 'snapshot-output-unc-forbidden'
    }
    if ($RelativePath.Contains(':')) {
        throw 'snapshot-output-ads-forbidden'
    }
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $rootFull $RelativePath))
    $handoff = [System.IO.Path]::GetFullPath((Join-Path $rootFull 'data\agent-handoff')).TrimEnd('\', '/')
    if (-not $candidate.StartsWith($handoff + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'snapshot-output-outside-handoff'
    }

    Test-AwxSnapshotPathForReparsePoint -Path $rootFull
    $cursorPath = $rootFull
    $relativeCanonical = $candidate.Substring($rootFull.Length).TrimStart([char[]]@('\', '/'))
    foreach ($segment in @($relativeCanonical -split '[\\/]')) {
        if ([string]::IsNullOrWhiteSpace($segment)) { continue }
        $cursorPath = Join-Path $cursorPath $segment
        if (Test-Path -LiteralPath $cursorPath) {
            $item = Get-Item -LiteralPath $cursorPath -Force
        } else {
            $item = New-Item -ItemType Directory -Path $cursorPath -Force
        }
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'snapshot-output-reparse-forbidden'
        }
    }
    return Get-Item -LiteralPath $candidate -Force
}

function Test-AwxSnapshotText {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$Text)

    $patterns = @(
        '(?i)(password|passwd|pwd)\s*[:=]\s*[^\s|]+',
        '(?i)(api[-_]?key|token|authorization|cookie|client[-_]?secret)\s*[:=]\s*[^\s|]+',
        '(?i)jdbc:(mysql|mariadb):',
        '(?i)bearer\s+[a-z0-9._~+/-]+'
    )
    $reasons = @($patterns | Where-Object { [regex]::IsMatch($Text, $_) })
    return [pscustomobject]@{ Safe = $reasons.Count -eq 0; HitCount = $reasons.Count; Reasons = $reasons }
}

function ConvertTo-AwxDesktopContextMarkdown {
    [CmdletBinding()]
    param([Parameter(Mandatory)][System.Collections.IDictionary]$Snapshot)

    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('# Desktop Context Snapshot')
    $lines.Add('')
    $lines.Add('## Snapshot Status')
    Add-AwxAllowedSnapshotFields -Lines $lines -Values $Snapshot -Allowed @{ generatedAt = 'Generated at'; snapshotDecision = 'Decision' } -CategoryKeys @('snapshotDecision')
    $lines.Add('')
    $lines.Add('## Java and Build')
    Add-AwxAllowedSnapshotFields -Lines $lines -Values $Snapshot.java -Allowed @{ status = 'Java status'; javaHome = 'JAVA_HOME'; 'java.home' = 'java.home'; version = 'Java version'; vendor = 'Java vendor'; executableAvailable = 'Java executable available' } -CategoryKeys @('status') -BooleanKeys @('executableAvailable')
    Add-AwxAllowedSnapshotFields -Lines $lines -Values $Snapshot.build -Allowed @{ status = 'Build status'; gradleWrapperVersion = 'Gradle wrapper version'; gradleVersion = 'Gradle version'; rootDependencyCount = 'Root dependency count'; sourceSetCount = 'Source-set count' } -CategoryKeys @('status') -NumericKeys @('rootDependencyCount', 'sourceSetCount')
    $lines.Add('')
    $lines.Add('## Windows and Hardware')
    Add-AwxAllowedSnapshotFields -Lines $lines -Values $Snapshot.windows -Allowed @{ status = 'Windows status'; osCaption = 'OS caption'; os = 'OS caption'; build = 'OS build'; powerShellVersion = 'PowerShell version' } -CategoryKeys @('status')
    Add-AwxAllowedSnapshotFields -Lines $lines -Values $Snapshot.hardware -Allowed @{ status = 'Hardware status'; cpu = 'CPU'; logicalProcessors = 'Logical processors'; physicalMemoryBytes = 'Physical memory bytes'; gpuName = 'GPU name'; gpuDriverVersion = 'GPU driver'; gpuTotalMemoryBytes = 'GPU total memory bytes'; gpuUsedMemoryBytes = 'GPU used memory bytes'; gpuProbeReason = 'GPU probe reason' } -CategoryKeys @('status', 'gpuProbeReason') -NumericKeys @('logicalProcessors', 'physicalMemoryBytes', 'gpuTotalMemoryBytes', 'gpuUsedMemoryBytes')
    $lines.Add('')
    $lines.Add('## Local Runtime Services')
    foreach ($service in @($Snapshot.services | Select-Object -First 16)) {
        Add-AwxAllowedSnapshotFields -Lines $lines -Values $service -Allowed @{ port = 'Port'; bindingClass = 'Binding class'; ownerProcessName = 'Owner process'; status = 'Status'; endpointStatus = 'Endpoint status'; modelCount = 'Model count'; modelIdentifier = 'Model identifier' } -CategoryKeys @('bindingClass', 'status', 'endpointStatus') -NumericKeys @('port', 'modelCount') -IdentifierKeys @('ownerProcessName', 'modelIdentifier')
    }
    $lines.Add('')
    $lines.Add('## Repository and SMB')
    Add-AwxAllowedSnapshotFields -Lines $lines -Values $Snapshot.repository -Allowed @{ status = 'Repository status'; canonicalRoot = 'Canonical root'; branch = 'Branch'; head = 'Short HEAD'; dirtyCount = 'Dirty count'; indexLock = 'Index lock'; sourceLeaseCount = 'Source lease count'; patchDropTopLevelCount = 'PatchDrop top-level count'; patchDropPendingCount = 'PatchDrop pending count'; activeSourceSetCount = 'Active source-set count' } -CategoryKeys @('status') -NumericKeys @('dirtyCount', 'sourceLeaseCount', 'patchDropTopLevelCount', 'patchDropPendingCount', 'activeSourceSetCount') -BooleanKeys @('indexLock') -IdentifierKeys @('branch', 'head')
    Add-AwxAllowedSnapshotFields -Lines $lines -Values $Snapshot.smb -Allowed @{ canonicalWorkspace = 'Canonical workspace'; backingShareIdentityVerified = 'Backing share identity verified'; backingShareIdentityReason = 'Backing share identity reason' } -CategoryKeys @('backingShareIdentityReason') -BooleanKeys @('backingShareIdentityVerified')
    $lines.Add('')
    $lines.Add('## MariaDB Metadata')
    Add-AwxAllowedSnapshotFields -Lines $lines -Values $Snapshot.mariadb -Allowed @{ status = 'Status'; decision = 'Decision'; connectionDecision = 'Connection decision'; catalogCount = 'Catalog count'; schemaCount = 'Schema count'; tableCount = 'Table count'; columnCount = 'Column count' } -CategoryKeys @('status', 'decision', 'connectionDecision') -NumericKeys @('catalogCount', 'schemaCount', 'tableCount', 'columnCount')
    if ($Snapshot.mariadb -is [System.Collections.IDictionary] -and $Snapshot.mariadb.Contains('objectCounts')) { Add-AwxBoundedObjectCounts -Lines $lines -Counts $Snapshot.mariadb.objectCounts }
    $lines.Add('')
    $lines.Add('## Agent DB Context')
    Add-AwxAllowedSnapshotFields -Lines $lines -Values $Snapshot.agentDb -Allowed @{ status = 'Status'; decision = 'Decision'; memoryStatusCount = 'Memory status count'; recentFailureReason = 'Recent failure reason'; recentFailureHash = 'Recent failure hash'; strategyAggregateCount = 'Strategy aggregate count' } -CategoryKeys @('status', 'decision', 'recentFailureReason') -NumericKeys @('memoryStatusCount', 'strategyAggregateCount') -HashKeys @('recentFailureHash')
    $lines.Add('')
    $lines.Add('## Evidence Needed')
    $evidenceAdded = 0
    foreach ($entry in @($Snapshot.evidenceNeeded | Select-Object -First 32)) {
        if ($null -ne $entry) { $lines.Add("- $(ConvertTo-AwxSnapshotEvidence $entry)"); $evidenceAdded++ }
    }
    if ($evidenceAdded -eq 0) { $lines.Add('- none') }
    $lines.Add('')
    $lines.Add('## Integrity and Refresh')
    $lines.Add('- SHA-256: written at publication time')
    $lines.Add('- Refresh: rerun the Desktop snapshot command when stale')
    return ($lines -join "`n") + "`n"
}

function Publish-AwxDesktopContextSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$Markdown,
        [Parameter(Mandatory)][System.IO.DirectoryInfo]$OutputDirectory
    )

    $volumeRoot = [System.IO.Path]::GetPathRoot($OutputDirectory.FullName)
    if ($OutputDirectory.FullName.Substring($volumeRoot.Length).Contains(':')) { throw 'snapshot-output-ads-forbidden' }
    if ($OutputDirectory.FullName -notmatch '(?i)[\\/]data[\\/]agent-handoff([\\/]|$)') { throw 'snapshot-output-outside-handoff' }
    Test-AwxSnapshotPathForReparsePoint -Path $OutputDirectory.FullName
    if ([System.Text.Encoding]::UTF8.GetByteCount($Markdown) -gt 1MB) { throw 'snapshot-markdown-too-large' }
    foreach ($heading in $script:AwxSnapshotHeadings) {
        if (-not $Markdown.Contains($heading)) { throw 'snapshot-markdown-heading-missing' }
    }
    $safety = Test-AwxSnapshotText -Text $Markdown
    if (-not $safety.Safe) { throw 'snapshot-markdown-secret-detected' }

    $reportPath = Join-Path $OutputDirectory.FullName 'latest.md'
    $hashPath = Join-Path $OutputDirectory.FullName 'latest.sha256.txt'
    $temporaryPath = Join-Path $OutputDirectory.FullName (([guid]::NewGuid().ToString('N')) + '.tmp')
    $hashTemporaryPath = Join-Path $OutputDirectory.FullName (([guid]::NewGuid().ToString('N')) + '.tmp')
    $backupPath = Join-Path $OutputDirectory.FullName (([guid]::NewGuid().ToString('N')) + '.bak')
    $hashBackupPath = Join-Path $OutputDirectory.FullName (([guid]::NewGuid().ToString('N')) + '.bak')
    $hadExistingReport = Test-Path -LiteralPath $reportPath
    try {
        [System.IO.File]::WriteAllText($temporaryPath, $Markdown, [System.Text.UTF8Encoding]::new($false))
        $sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $temporaryPath).Hash
        if ($hadExistingReport) {
            [System.IO.File]::Replace($temporaryPath, $reportPath, $backupPath)
        } else {
            [System.IO.File]::Move($temporaryPath, $reportPath)
        }
        try {
            [System.IO.File]::WriteAllText($hashTemporaryPath, "$sha256  latest.md`n", [System.Text.UTF8Encoding]::new($false))
            if (Test-Path -LiteralPath $hashPath) {
                [System.IO.File]::Replace($hashTemporaryPath, $hashPath, $hashBackupPath)
            } else {
                [System.IO.File]::Move($hashTemporaryPath, $hashPath)
            }
        } catch {
            if ($hadExistingReport -and (Test-Path -LiteralPath $backupPath)) {
                [System.IO.File]::Copy($backupPath, $reportPath, $true)
            } elseif (-not $hadExistingReport) {
                Remove-Item -LiteralPath $reportPath -Force -ErrorAction SilentlyContinue
            }
            throw 'snapshot-sidecar-publish-failed'
        }
        return [pscustomobject]@{ ReportPath = $reportPath; HashPath = $hashPath; Sha256 = $sha256; Decision = 'published' }
    } catch {
        if ($_.Exception.Message -eq 'snapshot-sidecar-publish-failed') { throw }
        throw 'snapshot-publish-failed'
    } finally {
        foreach ($path in @($temporaryPath, $hashTemporaryPath, $backupPath, $hashBackupPath)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function ConvertTo-AwxReportRelativePath {
    param(
        [Parameter(Mandatory)][string]$OutputDirectory
    )

    $normalized = ($OutputDirectory -replace '^[.\\/]+', '').TrimEnd('\', '/')
    return (($normalized -replace '\\', '/') + '/latest.md')
}

function Invoke-AwxDesktopNotebookContextSnapshot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$RepoRoot,
        [string]$OutputDirectory = 'data/agent-handoff/desktop-context',
        [ValidateSet('Metadata', 'Skip')][string]$DatabaseMode = 'Metadata',
        [string]$ProfileConfig = 'main/resources/application-desktop-gpu-node.yml',
        [ValidateRange(1, 120)][int]$DatabaseTimeoutSeconds = 5,
        [ValidateRange(1, 10000)][int]$MaxMetadataRows = 10000,
        [AllowNull()][System.Collections.IDictionary]$ProbeResults = $null
    )

    $rootFull = [IO.Path]::GetFullPath($RepoRoot)
    $resolvedOutput = Resolve-AwxSnapshotOutputDirectory -Root $rootFull -RelativePath $OutputDirectory
    $reportRelativePath = ConvertTo-AwxReportRelativePath -OutputDirectory $OutputDirectory

    $services = @(
        if ($null -ne $ProbeResults -and $ProbeResults.Contains('services')) {
            ConvertTo-AwxValue $ProbeResults.services
        } else {
            Get-AwxServiceFacts -TimeoutSeconds $DatabaseTimeoutSeconds
        }
    )
    $ollama = if ($null -ne $ProbeResults -and $ProbeResults.Contains('ollama')) {
        ConvertTo-AwxValue $ProbeResults.ollama
    } else {
        Get-AwxOllamaFacts -TimeoutSeconds $DatabaseTimeoutSeconds
    }
    if ($null -ne $ollama -and $ollama -is [System.Collections.IDictionary]) {
        $services += [pscustomobject][ordered]@{
            port = if ($ollama.Contains('port')) { $ollama.port } else { 11435 }
            bindingClass = if ($ollama.Contains('bindingClass')) { $ollama.bindingClass } else { 'loopback' }
            ownerProcessName = if ($ollama.Contains('ownerProcessName')) { $ollama.ownerProcessName } else { 'ollama' }
            status = if ($ollama.Contains('status')) { $ollama.status } else { 'unavailable' }
            endpointStatus = if ($ollama.Contains('endpointStatus')) { $ollama.endpointStatus } else { 'unavailable' }
            modelCount = if ($ollama.Contains('modelCount')) { $ollama.modelCount } else { 0 }
            modelIdentifier = if ($ollama.Contains('modelIdentifier')) { $ollama.modelIdentifier } else { $null }
        }
    }
    $snapshot = [ordered]@{
        generatedAt = [DateTimeOffset]::Now.ToString('o')
        snapshotDecision = 'partial'
        java = if ($null -ne $ProbeResults -and $ProbeResults.Contains('java')) { ConvertTo-AwxValue $ProbeResults.java } else { Get-AwxJavaFacts -TimeoutSeconds $DatabaseTimeoutSeconds }
        build = if ($null -ne $ProbeResults -and $ProbeResults.Contains('build')) { ConvertTo-AwxValue $ProbeResults.build } else { Get-AwxBuildFacts -RepoRoot $rootFull }
        windows = if ($null -ne $ProbeResults -and $ProbeResults.Contains('windows')) { ConvertTo-AwxValue $ProbeResults.windows } else { Get-AwxWindowsFacts }
        hardware = if ($null -ne $ProbeResults -and $ProbeResults.Contains('hardware')) { ConvertTo-AwxValue $ProbeResults.hardware } else { Get-AwxHardwareFacts -TimeoutSeconds $DatabaseTimeoutSeconds }
        services = $services
        repository = if ($null -ne $ProbeResults -and $ProbeResults.Contains('repository')) { ConvertTo-AwxValue $ProbeResults.repository } else { Get-AwxRepositoryFacts -RepoRoot $rootFull -TimeoutSeconds $DatabaseTimeoutSeconds }
        smb = if ($null -ne $ProbeResults -and $ProbeResults.Contains('smb')) { ConvertTo-AwxValue $ProbeResults.smb } else { Get-AwxSmbFacts -RepoRoot $rootFull -TimeoutSeconds $DatabaseTimeoutSeconds }
        agentDb = if ($null -ne $ProbeResults -and $ProbeResults.Contains('agentDb')) { ConvertTo-AwxValue $ProbeResults.agentDb } else { Get-AwxAgentDbFacts -RepoRoot $rootFull -TimeoutSeconds $DatabaseTimeoutSeconds }
        evidenceNeeded = @()
    }
    $mariadb = $null
    $evidenceNeeded = New-Object System.Collections.Generic.List[string]
    if ($DatabaseMode -eq 'Skip') {
        $mariadb = [ordered]@{
            status = 'not_observed'
            decision = 'not_observed'
            connectionDecision = 'not_observed'
            rawDbRowStored = $false
            jdbcUrlStored = $false
        }
        [void]$evidenceNeeded.Add('db-metadata-skipped')
    } elseif ($null -ne $ProbeResults -and $ProbeResults.Contains('mariadb')) {
        $mariadb = ConvertTo-AwxValue $ProbeResults.mariadb
    } else {
        $dbSettings = Resolve-AwxDesktopDbSettings -RepoRoot $rootFull -ProfileConfig $ProfileConfig
        if (-not $dbSettings.Resolved) {
            $mariadb = [ordered]@{
                status = 'evidence_needed'
                decision = 'query_failure'
                connectionDecision = 'query_failure'
                rawDbRowStored = $false
                jdbcUrlStored = $false
                evidenceNeeded = @("$($dbSettings.Reason):ConfigFailure")
            }
        } elseif (@($services | Where-Object { $_.port -eq 3306 -and $_.status -eq 'ok' }).Count -eq 0) {
            $mariadb = [ordered]@{
                status = 'evidence_needed'
                decision = 'query_failure'
                connectionDecision = 'query_failure'
                rawDbRowStored = $false
                jdbcUrlStored = $false
                evidenceNeeded = @('mariadb-listener-unavailable:SocketException')
            }
        } else {
            try {
                $connectorJar = Resolve-AwxMysqlConnectorJar -RepoRoot $rootFull -TimeoutSeconds $DatabaseTimeoutSeconds
                $mariadb = Invoke-AwxMariaDbMetadataHelper -Settings $dbSettings -ConnectorJar $connectorJar -TimeoutSeconds $DatabaseTimeoutSeconds -MaxRows $MaxMetadataRows
            } catch {
                $mariadb = [ordered]@{
                    status = 'evidence_needed'
                    decision = 'query_failure'
                    connectionDecision = 'query_failure'
                    rawDbRowStored = $false
                    jdbcUrlStored = $false
                    evidenceNeeded = @("mysql-connector-unavailable:$($_.Exception.GetType().Name)")
                }
            }
        }
        $dbSettings = $null
    }
    if ($null -eq $mariadb) {
        $mariadb = [ordered]@{
            status = 'not_observed'
            decision = 'not_observed'
            connectionDecision = 'not_observed'
            rawDbRowStored = $false
            jdbcUrlStored = $false
        }
    }
    $snapshot.mariadb = $mariadb

    if ($snapshot.agentDb -is [System.Collections.IDictionary] -and $snapshot.agentDb.Contains('evidenceNeeded')) {
        foreach ($item in @($snapshot.agentDb.evidenceNeeded)) {
            if (-not [string]::IsNullOrWhiteSpace([string]$item)) { [void]$evidenceNeeded.Add([string]$item) }
        }
    }
    if ($mariadb -is [System.Collections.IDictionary] -and $mariadb.Contains('evidenceNeeded')) {
        foreach ($item in @($mariadb.evidenceNeeded)) {
            if (-not [string]::IsNullOrWhiteSpace([string]$item)) { [void]$evidenceNeeded.Add([string]$item) }
        }
    }
    $snapshot.evidenceNeeded = @($evidenceNeeded | Select-Object -Unique)
    $snapshot.snapshotDecision = if ($DatabaseMode -eq 'Metadata' -and $mariadb.decision -eq 'connected' -and $snapshot.evidenceNeeded.Count -eq 0) { 'complete' } else { 'partial' }
    $markdown = ConvertTo-AwxDesktopContextMarkdown -Snapshot $snapshot

    try {
        $published = Publish-AwxDesktopContextSnapshot -Markdown $markdown -OutputDirectory $resolvedOutput
        return [pscustomobject][ordered]@{
            Decision = $snapshot.snapshotDecision
            ReportRelativePath = $reportRelativePath
            Sha256 = $published.Sha256
            MariaDbDecision = [string]$mariadb.decision
            EvidenceNeededCount = @($snapshot.evidenceNeeded).Count
        }
    } catch {
        return [pscustomobject][ordered]@{
            Decision = 'failed'
            ReportRelativePath = $reportRelativePath
            Sha256 = ''
            MariaDbDecision = [string]$mariadb.decision
            EvidenceNeededCount = @($snapshot.evidenceNeeded + @([string]$_.Exception.Message) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique).Count
        }
    }
}

Export-ModuleMember -Function Resolve-AwxSnapshotOutputDirectory, Test-AwxSnapshotText, ConvertTo-AwxDesktopContextMarkdown, Publish-AwxDesktopContextSnapshot, Get-AwxJavaFacts, Get-AwxWindowsFacts, Get-AwxHardwareFacts, Get-AwxServiceFacts, Get-AwxRepositoryFacts, Get-AwxSmbFacts, Get-AwxOllamaFacts, Get-AwxAgentDbFacts, Resolve-AwxDesktopDbSettings, Resolve-AwxMysqlConnectorJar, Invoke-AwxMariaDbMetadataHelper, Invoke-AwxDesktopNotebookContextSnapshot
