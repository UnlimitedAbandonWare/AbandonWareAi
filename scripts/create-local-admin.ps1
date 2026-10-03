#requires -Version 5.1
<#
.SYNOPSIS
    Upsert a local admin account into the Start-RAG file H2 store.

.DESCRIPTION
    Writes (or updates) one row in `administrators` on the same file H2 that
    Start-RAG uses (profiles local,meta-display ->
    jdbc:h2:file:./var/meta-display-db/lmsdb). The raw password is hashed with
    Spring Security BCryptPasswordEncoder ($2a$...) and applied via
    `MERGE INTO administrators ... KEY(username)`: INSERT when absent,
    UPDATE password/role/name when present.

    Plaintext never enters this file, the SQL template, or logs - pass it via
    -Password or env LMS_LOCAL_ADMIN_PASSWORD. Only the BCrypt hash prefix
    ($2a$xx$) is reported back.

    The Spring JVM holds an exclusive lock on lmsdb.mv.db while Start-RAG is
    running: stop it first (Close-RAG.bat / scripts\stop_rag_stack.ps1
    -MetaDisplay) or point -DbPath at an unlocked copy. -VerifyOnly only runs
    the read-back SELECT.

.EXAMPLE
    Set-Item Env:LMS_LOCAL_ADMIN_PASSWORD '<local-dev-password>'
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\create-local-admin.ps1 -Username admin

.EXAMPLE
    .\scripts\create-local-admin.ps1 -Username admin -Password '<local-dev-password>'

.NOTES
    Why this exists: LmsApplication bootstrap -> AdminService.createIfAbsent
    never overwrites an existing admin password (EXISTING_ACCOUNT_CONFLICT),
    so a stale/wrong local password cannot be repaired by restart alone.
    This MERGE path updates it. No new security layer, no users table, no
    harden - local dev convenience only (PROTO_OPEN scope).
    Exit codes: 0 ok | 2 prerequisite | 3 db-locked | 4 bcrypt | 5 runscript | 6 verify
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [ValidatePattern('^[A-Za-z0-9_.\-]{1,50}$')]
    [string]$Username = 'admin',

    # Raw password for the account. Prefer env LMS_LOCAL_ADMIN_PASSWORD so the
    # value does not sit in the command line / shell history.
    [string]$Password,

    [string]$Name,

    [ValidatePattern('^ROLE_[A-Z0-9_]+$')]
    [string]$Role = 'ROLE_ADMIN',

    # File-H2 base path WITHOUT the .mv.db suffix.
    # Default: <repo>\var\meta-display-db\lmsdb (the Start-RAG store).
    [string]$DbPath,

    [string]$DbUser = 'sa',
    [string]$DbPassword = '',

    # Optional explicit jar overrides (else META_DISPLAY_H2_JAR /
    # SPRING_SECURITY_CRYPTO_JAR env / tools\ / Gradle caches).
    [string]$H2Jar,
    [string]$CryptoJar,

    [string]$PasswordEnv = 'LMS_LOCAL_ADMIN_PASSWORD',

    [switch]$VerifyOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:RagRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))

function Write-ResultAndExit {
    param([hashtable]$Result, [int]$Code)
    $Result['exitCode'] = $Code
    ($Result | ConvertTo-Json -Compress -Depth 4)
    exit $Code
}

function Resolve-AwxJar {
    param(
        [string]$Explicit,
        [string]$EnvName,
        [string]$GroupRelPath,   # e.g. com.h2database/h2
        [string]$FilePrefix      # e.g. h2-*.jar
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
    $candidates += @(Get-ChildItem -Path (Join-Path $script:RagRoot "tools\$FilePrefix") -File -ErrorAction SilentlyContinue |
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

# ---- resolve inputs ---------------------------------------------------------
$DbBase = if ([string]::IsNullOrWhiteSpace($DbPath)) {
    Join-Path $script:RagRoot 'var\meta-display-db\lmsdb'
} else { $DbPath }
if ($DbBase.EndsWith('.mv.db')) { $DbBase = $DbBase.Substring(0, $DbBase.Length - 6) }
$MvDb = "$DbBase.mv.db"

$RawPassword = $Password
if (-not $VerifyOnly -and [string]::IsNullOrEmpty($RawPassword)) {
    $RawPassword = [Environment]::GetEnvironmentVariable($PasswordEnv)
}
if (-not $VerifyOnly -and [string]::IsNullOrEmpty($RawPassword)) {
    try {
        $secure = Read-Host -AsSecureString "Local admin password for '$Username' (not echoed)"
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $RawPassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    } catch {
        Write-ResultAndExit @{ ok = $false; stage = 'input';
            reason = "password required: pass -Password or set env $PasswordEnv" } 2
    }
}
if (-not $VerifyOnly -and [string]::IsNullOrEmpty($RawPassword)) {
    Write-ResultAndExit @{ ok = $false; stage = 'input'; reason = 'empty password rejected' } 2
}
$DisplayName = if ([string]::IsNullOrWhiteSpace($Name)) { $Username } else { $Name }

# ---- prerequisite checks ----------------------------------------------------
$JavaExe = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
if (-not $JavaExe) {
    Write-ResultAndExit @{ ok = $false; stage = 'tools'; reason = 'java.exe not on PATH (JDK 17+ required)' } 2
}
if (-not (Test-Path $MvDb)) {
    Write-ResultAndExit @{ ok = $false; stage = 'db'; dbFile = $MvDb;
        reason = 'file H2 missing - start once with Start-RAG.bat, or pass -DbPath' } 2
}
if (-not [IO.Path]::IsPathRooted($DbBase)) { $DbBase = Join-Path $script:RagRoot $DbBase }
$DbBaseFull = [IO.Path]::GetFullPath($DbBase)

$ResolvedH2Jar = Resolve-AwxJar -Explicit $H2Jar -EnvName 'META_DISPLAY_H2_JAR' `
    -GroupRelPath 'com.h2database/h2' -FilePrefix 'h2-*.jar'
if (-not $ResolvedH2Jar) {
    Write-ResultAndExit @{ ok = $false; stage = 'tools';
        reason = 'h2 jar not found - set META_DISPLAY_H2_JAR or restore the Gradle cache' } 2
}

$DbUrl = "jdbc:h2:file:$($DbBaseFull.Replace('\','/'));MODE=MariaDB;DATABASE_TO_UPPER=false;IFEXISTS=TRUE"
$workDir = Join-Path $env:TEMP ("awx-local-admin-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $workDir -Force | Out-Null
$applied = $false

try {
    # ---- lock probe: the only reliable check is the real JDBC open. A running
    # Spring JVM denies the second embedded opener ("already in use"); an OS
    # open/close probe reports false-free because H2 shares write access. ----
    $probeFile = Join-Path $workDir 'probe.sql'
    Write-Utf8NoBom -Path $probeFile -Text 'SELECT 1 FROM DUAL;'
    $probeErr = Join-Path $workDir 'probe.err'
    $probeArgs = @('-cp', $ResolvedH2Jar, 'org.h2.tools.RunScript',
        '-url', $DbUrl, '-user', $DbUser, '-script', $probeFile)
    if (-not [string]::IsNullOrEmpty($DbPassword)) { $probeArgs += @('-password', $DbPassword) }
    & $JavaExe @probeArgs 2>$probeErr
    $probeExit = $LASTEXITCODE
    if ($probeExit -ne 0) {
        $probeTail = if (Test-Path $probeErr) { (Get-Content $probeErr -Raw -ErrorAction SilentlyContinue) } else { '' }
        $lockedLike = ($probeTail -match 'already in use|may be already in use|FileLocked|file is locked|access denied|AccessDenied')
        Write-ResultAndExit @{
            ok = $false; stage = 'db'; dbFile = $MvDb; locked = $lockedLike; javaExit = $probeExit
            reason = $(if ($lockedLike) {
                'lmsdb.mv.db in use (Start-RAG JVM holds it) - run Close-RAG.bat first, then re-run this script'
            } else { 'H2 open failed: ' + ($probeTail -replace "`r?`n", ' ').Trim().Substring(0, [Math]::Min(200, ($probeTail -replace "`r?`n", ' ').Trim().Length)) })
        } $(if ($lockedLike) { 3 } else { 5 })
    }

    # ---- bcrypt hash (single-file java launch; plaintext via child env only) --
    if (-not $VerifyOnly) {
        $ResolvedCryptoJar = Resolve-AwxJar -Explicit $CryptoJar -EnvName 'SPRING_SECURITY_CRYPTO_JAR' `
            -GroupRelPath 'org.springframework.security/spring-security-crypto' -FilePrefix 'spring-security-crypto-*.jar'
        $ResolvedJclJar = Resolve-AwxJar -Explicit '' -EnvName 'SPRING_JCL_JAR' `
            -GroupRelPath 'org.springframework/spring-jcl' -FilePrefix 'spring-jcl-*.jar'
        if (-not $ResolvedCryptoJar) {
            Write-ResultAndExit @{ ok = $false; stage = 'tools';
                reason = 'spring-security-crypto jar not found - set SPRING_SECURITY_CRYPTO_JAR' } 2
        }
        $cp = if ($ResolvedJclJar) { "$ResolvedCryptoJar;$ResolvedJclJar" } else { $ResolvedCryptoJar }
        $javaSrc = Join-Path $workDir 'AwxBcrypt.java'
        @'
public class AwxBcrypt {
    public static void main(String[] args) {
        String raw = System.getenv("AWX_BCRYPT_PLAINTEXT");
        if (raw == null || raw.isEmpty()) { System.err.println("missing-plaintext-env"); System.exit(2); }
        System.out.print(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(raw));
    }
}
'@ | Set-Content -Path $javaSrc -Encoding ASCII
        $env:AWX_BCRYPT_PLAINTEXT = $RawPassword
        try {
            $errFile = Join-Path $workDir 'bcrypt.err'
            $hashOut = & $JavaExe '-Dfile.encoding=UTF-8' '-cp' $cp $javaSrc 2>$errFile
            $hashExit = $LASTEXITCODE
        } finally {
            Remove-Item Env:\AWX_BCRYPT_PLAINTEXT -ErrorAction SilentlyContinue
        }
        $Hash = ($hashOut | Out-String).Trim()
        if ($hashExit -ne 0 -or $Hash -notmatch '^\$2[aby]\$\d{2}\$') {
            Write-ResultAndExit @{ ok = $false; stage = 'bcrypt'; javaExit = $hashExit;
                reason = 'BCryptPasswordEncoder launch failed (java 17+ and spring-security-crypto jar required)' } 4
        }

        # ---- fill template -> temp sql (stays outside the repo) ---------------
        $templatePath = Join-Path $script:RagRoot 'scripts\sql\upsert-local-admin.sql.template'
        if (-not (Test-Path $templatePath)) {
            Write-ResultAndExit @{ ok = $false; stage = 'template'; reason = "missing $templatePath" } 2
        }
        # Literal .Replace (NOT -replace): the bcrypt hash contains '$' chars
        # that a regex replacement would reinterpret as group references.
        $sql = [IO.File]::ReadAllText($templatePath)
        $sql = $sql.Replace('__USERNAME__', (ConvertTo-SqlLiteral $Username))
        $sql = $sql.Replace('__BCRYPT_HASH__', (ConvertTo-SqlLiteral $Hash))
        $sql = $sql.Replace('__ROLE__', (ConvertTo-SqlLiteral $Role))
        $sql = $sql.Replace('__NAME__', (ConvertTo-SqlLiteral $DisplayName))
        $sqlFile = Join-Path $workDir 'upsert.sql'
        Write-Utf8NoBom -Path $sqlFile -Text $sql

        if ($PSCmdlet.ShouldProcess($MvDb, "MERGE administrators username=$Username")) {
            $errFile = Join-Path $workDir 'runscript.err'
            $runArgs = @('-cp', $ResolvedH2Jar, 'org.h2.tools.RunScript',
                '-url', $DbUrl, '-user', $DbUser, '-script', $sqlFile)
            if (-not [string]::IsNullOrEmpty($DbPassword)) { $runArgs += @('-password', $DbPassword) }
            & $JavaExe @runArgs 2>$errFile
            $runExit = $LASTEXITCODE
            if ($runExit -ne 0) {
                Write-ResultAndExit @{ ok = $false; stage = 'runscript'; javaExit = $runExit;
                    reason = 'H2 RunScript failed (schema/lock mismatch)' } 5
            }
            $applied = $true
        } else {
            Write-ResultAndExit @{ ok = $true; mode = 'whatif'; username = $Username;
                role = $Role; dbFile = $MvDb; reason = 'no mutation (ShouldProcess declined)' } 0
        }
    }

    # ---- verify: read back hash prefix only ----------------------------------
    $csvOut = (Join-Path $workDir 'verify.csv').Replace('\', '/')
    $verifySql = "CALL CSVWRITE('$csvOut', 'SELECT username AS ""username"", role AS ""role"", " +
        'LEFT(password, 7) AS "hash_prefix" FROM administrators WHERE username = ' +
        ((ConvertTo-SqlLiteral $Username) -replace "'", "''") + "', 'charset=UTF-8');"
    $verifyFile = Join-Path $workDir 'verify.sql'
    Write-Utf8NoBom -Path $verifyFile -Text $verifySql
    $errFile = Join-Path $workDir 'verify.err'
    $verifyArgs = @('-cp', $ResolvedH2Jar, 'org.h2.tools.RunScript',
        '-url', $DbUrl, '-user', $DbUser, '-script', $verifyFile)
    if (-not [string]::IsNullOrEmpty($DbPassword)) { $verifyArgs += @('-password', $DbPassword) }
    & $JavaExe @verifyArgs 2>$errFile
    $verifyExit = $LASTEXITCODE
    $csvPath = Join-Path $workDir 'verify.csv'
    if ($verifyExit -ne 0 -or -not (Test-Path $csvPath)) {
        Write-ResultAndExit @{ ok = $false; stage = 'verify'; javaExit = $verifyExit;
            reason = 'read-back SELECT failed' } 6
    }
    $row = Import-Csv -Path $csvPath | Select-Object -First 1
    if (-not $row -or $row.hash_prefix -notmatch '^\$2[aby]\$\d{2}\$') {
        Write-ResultAndExit @{ ok = $false; stage = 'verify'; row = $row;
            reason = 'admin row absent or password column is not a bcrypt hash' } 6
    }
    Write-ResultAndExit @{
        ok = $true; mode = $(if ($VerifyOnly) { 'verify-only' } else { 'merged' })
        applied = $applied; username = $row.username; role = $row.role
        hashPrefix = $row.hash_prefix; dbFile = $MvDb
    } 0
} finally {
    # The temp sql embeds the bcrypt hash: always drop the work dir.
    Remove-Item -Path $workDir -Recurse -Force -ErrorAction SilentlyContinue
}
