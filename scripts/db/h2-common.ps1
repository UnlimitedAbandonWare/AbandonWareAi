#requires -Version 5.1
<#
.SYNOPSIS
    Shared helpers for the demo-1 local-H2 agent kit (dot-source only, not a
    standalone entry point).

.DESCRIPTION
    Contract mirror of scripts/create-local-admin.ps1 helpers (jar resolution,
    SQL literal escaping, UTF8-no-BOM writes, RunScript invocation). Kept as a
    separate dot-source file so kit entry points (scripts/db-agent.ps1) reuse
    the exact same semantics without editing the verified upsert script.

    Kit-wide rules (P0):
      * Secrets via env/arg only; masked JSON results only (hashPrefix etc).
      * Lock detection = real JDBC open (org.h2.tools.RunScript SELECT 1);
        "OS open() succeeded" is NOT a free signal (H2 shares write access).
      * Every .sql fed to RunScript is written UTF8 with no BOM.
      * Java args always passed as a splatted array; never string-concat
        '-D' options into one command line (PowerShell re-splits them wrong).
      * Exit codes: 0 ok | 2 bad args/input | 3 locked/busy | 4 missing
        tools/jars/db file | 5 verify-or-run failed.
#>

function Write-AwxResultAndExit {
    param([hashtable]$Result, [int]$Code)
    $Result['exitCode'] = $Code
    ($Result | ConvertTo-Json -Compress -Depth 6)
    exit $Code
}

function Resolve-AwxJar {
    param(
        [string]$Explicit,
        [string]$EnvName,
        [string]$GroupRelPath,   # e.g. com.h2database/h2
        [string]$FilePrefix,     # e.g. h2-*.jar
        [string]$RagRoot
    )
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($Explicit)) { $candidates += $Explicit }
    $envVal = [Environment]::GetEnvironmentVariable($EnvName)
    if (-not [string]::IsNullOrWhiteSpace($envVal)) { $candidates += $envVal }
    foreach ($base in @(
            (Join-Path $HOME ".gradle\caches\modules-2\files-2.1\$GroupRelPath"),
            (Join-Path $HOME ".awx-gradle-user-home\caches\modules-2\files-2.1\$GroupRelPath"))) {
        if (Test-Path $base) {
            $candidates += @(Get-ChildItem -Path $base -Recurse -Filter $FilePrefix -File -ErrorAction SilentlyContinue |
                ForEach-Object { $_.FullName })
        }
    }
    $candidates += @(Get-ChildItem -Path (Join-Path $RagRoot "tools\$FilePrefix") -File -ErrorAction SilentlyContinue |
        ForEach-Object { $_.FullName })
    $versioned = $candidates | Where-Object { $_ -and (Test-Path $_) } | Sort-Object -Descending {
        if ($_ -match '-(\d+)\.(\d+)\.(\d+)(\.jar)?$') { [version]("{0}.{1}.{2}" -f $Matches[1], $Matches[2], $Matches[3]) } else { [version]'0.0.0' }
    }
    return @($versioned | Select-Object -First 1)[0]
}

function ConvertTo-SqlLiteral {
    param([string]$Value)
    return "'" + ($Value -replace "'", "''") + "'"
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Text)
    $enc = New-Object System.Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $Text, $enc)
}

function Resolve-AwxDbTarget {
    # Returns @{ dbBase, mvDb, dbUrl } for a -DbPath (default: Start-RAG store).
    # dbUrl defaults to read+write embedded file mode; -ReadOnly adds
    # ACCESS_MODE_DATA=r for read lanes (denylist is still enforced).
    param([string]$RagRoot, [string]$DbPath, [switch]$ReadOnly)
    $dbBase = if ([string]::IsNullOrWhiteSpace($DbPath)) {
        Join-Path $RagRoot 'var\meta-display-db\lmsdb'
    } else { $DbPath }
    if ($dbBase.EndsWith('.mv.db')) { $dbBase = $dbBase.Substring(0, $dbBase.Length - 6) }
    if (-not [IO.Path]::IsPathRooted($dbBase)) { $dbBase = Join-Path $RagRoot $dbBase }
    $dbBaseFull = [IO.Path]::GetFullPath($dbBase)
    $opts = 'MODE=MariaDB;DATABASE_TO_UPPER=false;IFEXISTS=TRUE'
    if ($ReadOnly) { $opts += ';ACCESS_MODE_DATA=r' }
    return @{
        DbBase = $dbBaseFull
        MvDb   = "$dbBaseFull.mv.db"
        DbUrl  = "jdbc:h2:file:$($dbBaseFull.Replace('\','/'));$opts"
    }
}

function Invoke-AwxH2Script {
    # Runs org.h2.tools.RunScript on a .sql file (written UTF8 no BOM upstream).
    # Args are splatted as an array so -Dfile.encoding stays one token.
    param(
        [string]$JavaExe, [string]$H2Jar, [string]$DbUrl,
        [string]$DbUser, [string]$DbPassword,
        [string]$ScriptFile, [string]$StdErrFile
    )
    $argList = @('-Dfile.encoding=UTF-8', '-cp', $H2Jar, 'org.h2.tools.RunScript',
        '-url', $DbUrl, '-user', $DbUser, '-script', $ScriptFile)
    if (-not [string]::IsNullOrEmpty($DbPassword)) { $argList += @('-password', $DbPassword) }
    # EAP=Stop trap: a native stderr line redirected with 2> becomes a
    # terminating NativeCommandError and would kill the caller with exit 1
    # instead of returning the real java exit code. Locally relax to Continue.
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $JavaExe @argList 2>$StdErrFile | Out-Null
        return $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prevEap
    }
}

function Get-AwxErrTail {
    # stderr capture from a redirected native run. PS5.1 quirk: Get-Content
    # -Raw on an EMPTY file yields $null/Object[] depending on context, so
    # always coerce through [string] before trimming.
    param([string]$Path, [int]$MaxChars = 2000)
    if (-not (Test-Path $Path)) { return '' }
    $raw = Get-Content $Path -Raw -ErrorAction SilentlyContinue
    $s = ([string]($raw -join ' ')) -replace "`r?`n", ' '
    $s = $s.Trim()
    if ($s.Length -gt $MaxChars) { $s = $s.Substring(0, $MaxChars) }
    return $s
}

function Test-AwxDbLock {
    # Honest lock probe: a real JDBC open of the SAME url a writer would use.
    # Never substitutes an OS open()+close - H2 grants shared file handles and
    # the probe lies "free" while the Spring JVM still owns the store.
    param(
        [string]$JavaExe, [string]$H2Jar, [string]$DbUrl,
        [string]$DbUser, [string]$DbPassword, [string]$WorkDir
    )
    $probeFile = Join-Path $WorkDir 'lockprobe.sql'
    Write-Utf8NoBom -Path $probeFile -Text 'SELECT 1 FROM DUAL;'
    $probeErr = Join-Path $WorkDir 'lockprobe.err'
    $exit = Invoke-AwxH2Script -JavaExe $JavaExe -H2Jar $H2Jar -DbUrl $DbUrl `
        -DbUser $DbUser -DbPassword $DbPassword -ScriptFile $probeFile -StdErrFile $probeErr
    $tail = Get-AwxErrTail -Path $probeErr
    $locked = ($exit -ne 0) -and ($tail -match 'already in use|may be already in use|FileLocked|file is locked|access denied|AccessDenied')
    return @{ Exit = $exit; Locked = $locked; Detail = $tail }
}

function Invoke-AwxSelectCsv {
    # Executes a SELECT through H2 CSVWRITE into <WorkDir>\result.csv and returns
    # the parsed CSV rows. The caller enforces the read-only denylist; this
    # helper only runs the statement.
    param(
        [string]$JavaExe, [string]$H2Jar, [string]$DbUrl,
        [string]$DbUser, [string]$DbPassword,
        [string]$SelectSql, [string]$WorkDir
    )
    $csvOut = (Join-Path $WorkDir 'result.csv').Replace('\', '/')
    $escaped = $SelectSql -replace "'", "''"
    $script = "CALL CSVWRITE('$csvOut', '$escaped', 'charset=UTF-8');"
    $sqlFile = Join-Path $WorkDir 'query.sql'
    Write-Utf8NoBom -Path $sqlFile -Text $script
    $errFile = Join-Path $WorkDir 'query.err'
    $exit = Invoke-AwxH2Script -JavaExe $JavaExe -H2Jar $H2Jar -DbUrl $DbUrl `
        -DbUser $DbUser -DbPassword $DbPassword -ScriptFile $sqlFile -StdErrFile $errFile
    $csvPath = Join-Path $WorkDir 'result.csv'
    $rows = @()
    if ($exit -eq 0 -and (Test-Path $csvPath)) {
        $rows = @(Import-Csv -Path $csvPath)
    }
    return @{ Exit = $exit; Rows = $rows; Detail = (Get-AwxErrTail -Path $errFile) }
}

function Protect-AwxRow {
    # Mask sensitive columns before they reach JSON/logs. Prefix-only, never
    # the full value (bcrypt hash, tokens, rrn). Returns a new ordered dict.
    param([psobject]$Row)
    $maskCols = 'password|passwd|secret|token|credential|api_key|apikey|rrn'
    $out = [ordered]@{}
    foreach ($prop in $Row.PSObject.Properties) {
        $v = [string]$prop.Value
        if ($prop.Name -match $maskCols -and $v.Length -gt 0) {
            $keep = [Math]::Min(7, $v.Length)
            $out[$prop.Name] = $v.Substring(0, $keep) + '...masked'
        } else {
            $out[$prop.Name] = $v
        }
    }
    return $out
}

function Test-AwxTcpPort {
    # Fast localhost TCP probe (avoids Test-NetConnection latency).
    param([string]$HostName = '127.0.0.1', [int]$Port, [int]$TimeoutMs = 700)
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $iar = $client.BeginConnect($HostName, $Port, $null, $null)
        $ok = $iar.AsyncWaitHandle.WaitOne($TimeoutMs, $false)
        if ($ok) { try { $client.EndConnect($iar) } catch { $ok = $false } }
        return [bool]$ok
    } catch { return $false } finally { $client.Close() }
}
