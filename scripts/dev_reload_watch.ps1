#requires -Version 5.1
[CmdletBinding()]
param(
    [switch]$MetaDisplay,
    [ValidateRange(1,65535)][int]$Port = 18180,
    [ValidateRange(500,60000)][int]$DebounceMs = 2500,
    [ValidateRange(10,600)][int]$RestartCooldownSeconds = 45
)

$ErrorActionPreference = 'Stop'
$script:WatchMetaDisplay = [bool]$MetaDisplay
$script:WatchPort = if ($Port -lt 1) { 18180 } else { [int]$Port }
# Shared runtime detection (role/port/manifest) lives in the launcher; dot-source
# it so the watcher classifies a protected wear runtime exactly like the restart
# path does. Its own param() block resets same-named variables, so re-assert ours.
. (Join-Path $PSScriptRoot 'start_rag_stack.ps1')
$Port = $script:WatchPort
$MetaDisplay = $script:WatchMetaDisplay

$script:RagRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:LogDir = Join-Path $script:RagRoot 'var\dev-reload'
$script:LogFile = Join-Path $script:LogDir 'dev-reload.log'
$script:StateFile = Join-Path $script:LogDir 'watch.state.json'
New-Item -ItemType Directory -Force -Path $script:LogDir | Out-Null

function Write-DevReload([string]$Message) {
    $line = '{0} [DEV-RELOAD] {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $Message
    Write-Host $line
    Add-Content -LiteralPath $script:LogFile -Value $line -Encoding UTF8
}

function Save-DevReloadState([hashtable]$Data) {
    $Data['updatedAt'] = (Get-Date).ToString('o')
    $Data['pid'] = $PID
    [IO.File]::WriteAllText($script:StateFile, ($Data | ConvertTo-Json -Compress), (New-Object System.Text.UTF8Encoding $false))
}

$script:Pending = New-Object 'System.Collections.Generic.HashSet[string]'
$script:LastRestartUtc = [datetime]::MinValue
$script:FailStreak = 0
$script:LastEventUtc = [datetime]::MinValue
# Launcher refusal reasons that mean another runtime owns the lifecycle. These
# defer the restart instead of burning the fail streak on a doomed retry loop.
$script:DeferredRestartReasons = @('meta-display-wear-runtime-protected', 'launcher-already-running')

function Test-IgnoredPath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { return $true }
    $n = $Path.Replace('/','\').ToLowerInvariant()
    if ($n -match '\\(build|\.git|var\\|out\\|node_modules|\.gradle|project-cache)\\') { return $true }
    if ($n -match '\.(bak|tmp|swp|class|jar)$') { return $true }
    if ($n -match '\.bak-') { return $true }
    return $false
}

function Get-ReloadTier([string[]]$Paths) {
    $hasJava = $false; $hasConfig = $false; $hasStatic = $false; $hasOther = $false
    foreach ($p in $Paths) {
        if (Test-IgnoredPath $p) { continue }
        $ext = [IO.Path]::GetExtension($p).ToLowerInvariant()
        $norm = $p.Replace('/','\').ToLowerInvariant()
        if ($ext -eq '.java') { $hasJava = $true; continue }
        if ($ext -in '.yml','.yaml','.properties') { $hasConfig = $true; continue }
        if (($norm -match '\\static\\') -and ($ext -in '.js','.css','.html','.htm','.svg','.png','.jpg','.jpeg','.webp','.woff','.woff2','.map')) {
            $hasStatic = $true; continue
        }
        if ($ext) { $hasOther = $true }
    }
    if ($hasJava -or $hasConfig -or $hasOther) { return 'SPRING' }
    if ($hasStatic) { return 'STATIC' }
    return 'NONE'
}

function Invoke-DevCompile {
    Write-DevReload 'rebuild → gradle :compileJava :processResources'
    $gradlew = Join-Path $script:RagRoot 'gradlew.bat'
    $cache = Join-Path $env:LOCALAPPDATA 'AWX\meta-display\project-cache'
    $gradleHome = Join-Path $env:USERPROFILE '.gradle-awx-rag-launcher'
    $argList = @(
        ':compileJava',':processResources','--no-daemon','-x','test',
        "--project-cache-dir=$cache","-g=$gradleHome"
    )
    $p = Start-Process -FilePath $gradlew -ArgumentList $argList -WorkingDirectory $script:RagRoot -Wait -PassThru -NoNewWindow `
        -RedirectStandardOutput (Join-Path $script:LogDir 'last-compile.out.log') `
        -RedirectStandardError (Join-Path $script:LogDir 'last-compile.err.log')
    if ($p.ExitCode -ne 0) { throw "compile-failed exit=$($p.ExitCode)" }
}

function Test-DevWearRuntime {
    # The restart target is always the Meta Display runtime on the fixed ports.
    # Reuse the launcher's own evidence: meta-display candidates under this root
    # (role from the live command line) plus fixed-port owners with the runtime
    # manifest as fallback — a wear start-up window or a quoted command line must
    # not slip past and burn the fail streak on a ForceRestart the launcher will
    # refuse anyway.
    try {
        foreach ($candidate in @(Get-RagSpringCandidates)) {
            if (-not [bool](Get-AwxObjectProperty $candidate 'metaDisplay')) { continue }
            if ([string](Get-AwxObjectProperty $candidate 'role') -eq 'wear') { return $true }
        }
        foreach ($fixedPort in @(18180, 18181, 18182)) {
            $owner = $null
            try { $owner = Get-RagPortOwner -Port $fixedPort } catch { $owner = $null }
            if ($null -eq $owner) { continue }
            $ownerCommand = ''
            $ownerProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$owner.processId)" -ErrorAction SilentlyContinue
            if ($null -ne $ownerProcess) { $ownerCommand = [string]$ownerProcess.CommandLine }
            if ((Get-RagProcessRole -ProcessId ([int]$owner.processId) -CommandLine $ownerCommand) -eq 'wear') { return $true }
        }
        return $false
    } catch { return $false }
}

function Get-DevLatestLauncherReason {
    # The launcher writes var/rag-launcher/<run>/result.json with stage/reason on
    # every attempt. Attach the freshest reason (a restart just ran) so a refused
    # exit code carries its real cause instead of an opaque exit number.
    try {
        $base = Join-Path $script:RagRoot 'var\rag-launcher'
        if (-not (Test-Path -LiteralPath $base -PathType Container)) { return $null }
        $latest = @(Get-ChildItem -LiteralPath $base -Filter 'result.json' -Recurse -ErrorAction SilentlyContinue |
            Where-Object { $_.LastWriteTime -gt (Get-Date).AddMinutes(-2) } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1)
        if ($latest.Count -eq 0) { return $null }
        return (Get-Content -LiteralPath $latest[0].FullName -Raw -Encoding UTF8 | ConvertFrom-Json)
    } catch { return $null }
}

function Invoke-DevSpringRestart {
    Write-DevReload 'Spring restart → start_rag_stack -MetaDisplay -ForceRestart'
    $env:AWX_RAG_NO_PAUSE = '1'
    $stack = Join-Path $PSScriptRoot 'start_rag_stack.ps1'
    $p = Start-Process -FilePath (Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe') `
        -ArgumentList @('-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',$stack,'-MetaDisplay','-ForceRestart') `
        -WorkingDirectory $script:RagRoot -Wait -PassThru -NoNewWindow `
        -RedirectStandardOutput (Join-Path $script:LogDir 'last-restart.out.log') `
        -RedirectStandardError (Join-Path $script:LogDir 'last-restart.err.log')
    if ($p.ExitCode -ne 0) {
        $info = Get-DevLatestLauncherReason
        $detail = ''
        if ($null -ne $info -and -not [string]::IsNullOrWhiteSpace([string](Get-AwxObjectProperty $info 'reason'))) {
            $detail = " reason=$($info.reason) stage=$($info.stage)"
        }
        throw "spring-restart-failed exit=$($p.ExitCode)$detail"
    }
}

function Test-DevSocketReady {
    try {
        $req = [Net.HttpWebRequest]::Create("http://127.0.0.1:$($script:WatchPort)/assets/display/index.html")
        $req.Proxy = $null; $req.Timeout = 3000; $req.AllowAutoRedirect = $false
        $resp = $req.GetResponse(); $code = [int]$resp.StatusCode; $resp.Close(); return ($code -eq 200)
    } catch { return $false }
}

function Invoke-DevReloadCycle([string[]]$Paths) {
    $tier = Get-ReloadTier $Paths
    $sample = (@($Paths | Select-Object -First 5) -join '; ')
    if ($tier -eq 'NONE') {
        Write-DevReload "ignored noise ($($Paths.Count) paths)"
        return
    }
    if ($tier -eq 'STATIC') {
        Write-DevReload "source changed (static) → no Spring restart → client refresh/reconnect on next poll :: $sample"
        Save-DevReloadState @{ lastTier = 'STATIC'; status = 'static-only'; sample = $sample }
        return
    }
    $since = ([datetime]::UtcNow - $script:LastRestartUtc).TotalSeconds
    if ($script:LastRestartUtc -ne [datetime]::MinValue -and $since -lt $RestartCooldownSeconds) {
        Write-DevReload ("cooldown skip ({0:N0}s < {1}s) :: {2}" -f $since, $RestartCooldownSeconds, $sample)
        Save-DevReloadState @{ lastTier = 'SPRING'; status = 'cooldown-skip'; sample = $sample }
        return
    }
    Write-DevReload "source changed → rebuild → Spring restart :: $sample"
    Save-DevReloadState @{ lastTier = 'SPRING'; status = 'running'; sample = $sample }
    try {
        Invoke-DevCompile
        if (Test-DevWearRuntime) {
            Write-DevReload 'wear runtime holds 18180 → Spring restart skipped (runtime role protection)'
            Save-DevReloadState @{ lastTier = 'SPRING'; status = 'wear-protected-skip'; sample = $sample }
            return
        }
        Invoke-DevSpringRestart
        $ready = $false
        for ($i = 0; $i -lt 90; $i++) {
            Start-Sleep -Seconds 2
            if (Test-DevSocketReady) { $ready = $true; break }
        }
        if (-not $ready) { throw 'socket-not-ready-after-restart' }
        $script:LastRestartUtc = [datetime]::UtcNow
        $script:FailStreak = 0
        Write-DevReload 'socket ready → client reconnected (display-conversate auto RECONNECTING)'
        Save-DevReloadState @{ lastTier = 'SPRING'; status = 'ready'; sample = $sample }
    } catch {
        $message = [string]$_.Exception.Message
        $deferred = ''
        if ($message -match 'reason=([a-z0-9-]+)' -and $script:DeferredRestartReasons -contains $Matches[1]) {
            $deferred = $Matches[1]
        }
        if ($deferred) {
            Write-DevReload ("restart deferred ({0}) — will retry on next change" -f $deferred)
            Save-DevReloadState @{ lastTier = 'SPRING'; status = 'deferred'; reason = $deferred; sample = $sample }
            return
        }
        $script:FailStreak++
        Write-DevReload ("FAILED: {0} (failStreak={1})" -f $message, $script:FailStreak)
        Save-DevReloadState @{ lastTier = 'SPRING'; status = 'failed'; error = $message }
        if ($script:FailStreak -ge 3) {
            Write-DevReload 'fail streak >= 3 — pause 5 minutes'
            Start-Sleep -Seconds 300
            $script:FailStreak = 0
        }
    }
}

function Invoke-DevReloadWatch {
    $mutexName = if ($MetaDisplay) { 'Global\AWX-DevReload-MetaDisplay' } else { 'Global\AWX-DevReload-RAG' }
    $mutex = New-Object System.Threading.Mutex($false, $mutexName)
    if (-not $mutex.WaitOne(0)) {
        Write-DevReload 'watcher already running — exit (single-instance)'
        $mutex.Dispose()
        return 0
    }

    $watchRoots = @(
        (Join-Path $script:RagRoot 'main\java'),
        (Join-Path $script:RagRoot 'main\resources'),
        (Join-Path $script:RagRoot 'app\src\main\java_clean'),
        (Join-Path $script:RagRoot 'app\src\main\resources')
    ) | Where-Object { Test-Path -LiteralPath $_ }

    if ($watchRoots.Count -eq 0) {
        Write-DevReload 'no watch roots found — exit'
        $mutex.ReleaseMutex(); $mutex.Dispose()
        return 1
    }

    $watchers = @()
    $subscribers = @()
    foreach ($root in $watchRoots) {
        $w = New-Object System.IO.FileSystemWatcher $root, '*.*'
        $w.IncludeSubdirectories = $true
        $w.NotifyFilter = [IO.NotifyFilters]::FileName -bor [IO.NotifyFilters]::LastWrite -bor [IO.NotifyFilters]::CreationTime -bor [IO.NotifyFilters]::DirectoryName
        $w.EnableRaisingEvents = $true
        foreach ($ev in @('Changed','Created','Deleted','Renamed')) {
            $subscribers += Register-ObjectEvent -InputObject $w -EventName $ev -SourceIdentifier ("AWX.DevReload.$ev.$([guid]::NewGuid().ToString('N'))")
        }
        $watchers += $w
        Write-DevReload ("watching {0}" -f $root)
    }

    Write-DevReload ("armed MetaDisplay={0} port={1} debounceMs={2} cooldownSec={3}" -f $script:WatchMetaDisplay, $script:WatchPort, $DebounceMs, $RestartCooldownSeconds)
    Save-DevReloadState @{ status = 'armed'; roots = @($watchRoots); port = $script:WatchPort; meta = $script:WatchMetaDisplay }

    try {
        while ($true) {
            $evt = Wait-Event -Timeout 1
            while ($null -ne $evt) {
                try {
                    $argsObj = $evt.SourceEventArgs
                    if ($null -ne $argsObj) {
                        if ($argsObj -is [IO.RenamedEventArgs]) {
                            [void]$script:Pending.Add([string]$argsObj.OldFullPath)
                        }
                        [void]$script:Pending.Add([string]$argsObj.FullPath)
                        $script:LastEventUtc = [datetime]::UtcNow
                    }
                } finally {
                    Remove-Event -EventIdentifier $evt.EventIdentifier -ErrorAction SilentlyContinue
                }
                $evt = Wait-Event -Timeout 0
            }
            if ($script:Pending.Count -gt 0 -and $script:LastEventUtc -ne [datetime]::MinValue) {
                $idleMs = ([datetime]::UtcNow - $script:LastEventUtc).TotalMilliseconds
                if ($idleMs -ge $DebounceMs) {
                    $batch = @($script:Pending)
                    $script:Pending.Clear()
                    $script:LastEventUtc = [datetime]::MinValue
                    Invoke-DevReloadCycle $batch
                }
            }
        }
    } finally {
        foreach ($s in $subscribers) { Unregister-Event -SubscriptionId $s.Id -Force -ErrorAction SilentlyContinue }
        foreach ($w in $watchers) { try { $w.EnableRaisingEvents = $false; $w.Dispose() } catch {} }
        try { $mutex.ReleaseMutex() } catch {}
        $mutex.Dispose()
        Write-DevReload 'watcher stopped'
    }
    return 0
}

if ($MyInvocation.InvocationName -ne '.') { exit (Invoke-DevReloadWatch) }
