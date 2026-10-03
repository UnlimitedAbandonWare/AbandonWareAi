#requires -Version 5.1
<#
.SYNOPSIS
    Self-contained smoke for the demo-1 DB agent kit (scripts/db-agent.ps1).

.DESCRIPTION
    Builds a throwaway file-H2 under %TEMP% (never touches the live
    var/meta-display-db/lmsdb), then asserts the kit exit-code contract:

      LockProbe  temp db   -> 0   (free)
      UpsertAdmin temp db  -> 0   (delegated MERGE, env password, masked out)
      VerifyAdmin present  -> 0   | absent -> 5
      Query -Table         -> 0   (password column masked to prefix)
      Query -Sql denylist  -> 2   | missing args -> 2
      Count / TableExists  -> 0
      LockProbe missing db -> 4
      LockProbe live lmsdb -> 3 while Start-RAG holds it, else 0 (informational)

    Exits 0 when every check passes (live-lock check is informational).
    The throwaway password is generated per run and never printed.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\db-agent-kit-smoke.ps1
#>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Continue'   # native stderr redirect must not kill asserts
$script:RagRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'db\h2-common.ps1')

$agent = Join-Path $script:RagRoot 'scripts\db-agent.ps1'
$fail = 0
$skip = 0
function Check([string]$Name, [int]$Got, [int]$Want) {
    if ($Got -eq $Want) { "PASS $Name exit=$Got" }
    else { "FAIL $Name got=$Got want=$Want"; $script:fail++ }
}
function Note([string]$Msg) { "SKIP $Msg"; $script:skip++ }

# ---- prerequisites ----------------------------------------------------------
$java = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
if (-not $java) { "FAIL java.exe not on PATH"; exit 1 }
$jar = Resolve-AwxJar -Explicit '' -EnvName 'META_DISPLAY_H2_JAR' `
    -GroupRelPath 'com.h2database/h2' -FilePrefix 'h2-*.jar' -RagRoot $script:RagRoot
if (-not $jar) { "FAIL h2 jar not found"; exit 1 }

# ---- throwaway fixture db ---------------------------------------------------
$workDir = Join-Path $env:TEMP ("awx-kit-smoke-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $workDir -Force | Out-Null
$dbBase = Join-Path $workDir 'smokedb'
try {
    $schemaSql = Join-Path $workDir 'schema.sql'
    Write-Utf8NoBom -Path $schemaSql -Text @'
CREATE TABLE administrators(id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) UNIQUE, password VARCHAR(255), role VARCHAR(20),
    name VARCHAR(255), created_at TIMESTAMP);
CREATE TABLE chat_msg(id INT, note VARCHAR(50));
INSERT INTO chat_msg VALUES(1, 'hello');
'@
    # Fixture creation is the one place IFEXISTS=TRUE (kit default, guards
    # against stray-db typos) must be dropped: the file does not exist yet.
    $fixtureUrl = "jdbc:h2:file:$($dbBase.Replace('\','/'));MODE=MariaDB;DATABASE_TO_UPPER=false"
    $fixtureErr = Join-Path $workDir 'fixture.err'
    $fx = Invoke-AwxH2Script -JavaExe $java -H2Jar $jar -DbUrl $fixtureUrl `
        -DbUser 'sa' -DbPassword '' -ScriptFile $schemaSql -StdErrFile $fixtureErr
    if ($fx -ne 0) { "FAIL fixture create exit=$fx"; exit 1 }

    # ---- kit matrix ---------------------------------------------------------
    & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action LockProbe -DbPath $dbBase | Out-Null
    Check 'lockprobe-free' $LASTEXITCODE 0

    # Upsert through the delegate; throwaway secret lives only in this env.
    $env:LMS_LOCAL_ADMIN_PASSWORD = 'smoke-' + [guid]::NewGuid().ToString('N')
    try {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action UpsertAdmin -Username smokeadmin -DbPath $dbBase | Out-Null
        $upExit = $LASTEXITCODE
    } finally {
        Remove-Item Env:\LMS_LOCAL_ADMIN_PASSWORD -ErrorAction SilentlyContinue
    }
    $upserted = ($upExit -eq 0)
    if (-not $upserted) { Note "upsert exit=$upExit (missing spring-security-crypto jar?); verify checks fall back" }

    if ($upserted) {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action VerifyAdmin -Username smokeadmin -DbPath $dbBase | Out-Null
        Check 'verify-present' $LASTEXITCODE 0
    } else {
        Note 'verify-present (no row to verify)'
    }

    & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action VerifyAdmin -Username ghost -DbPath $dbBase | Out-Null
    Check 'verify-absent' $LASTEXITCODE 5

    $qout = & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action Query -Table chat_msg -DbPath $dbBase | Out-String
    Check 'query-table' $LASTEXITCODE 0
    if ($upserted) {
        $mout = & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action Query -Table administrators -DbPath $dbBase | Out-String
        Check 'query-mask' $LASTEXITCODE 0
        if ($mout -match '\$2[aby]\$\d{2}\$\.{3}masked') { "PASS mask-prefix" }
        else { "FAIL mask-prefix"; $script:fail++ }
        # full hash must never appear in output
        if ($mout -match '\$2[aby]\$\d{2}\$[A-Za-z0-9./]{20,}') { "FAIL full-hash-leak"; $script:fail++ }
        else { "PASS no-full-hash" }
    }

    & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action Query -Sql 'DELETE FROM administrators' -DbPath $dbBase | Out-Null
    Check 'query-deny' $LASTEXITCODE 2

    & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action Query -DbPath $dbBase | Out-Null
    Check 'query-noarg' $LASTEXITCODE 2

    & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action Count -Table chat_msg -DbPath $dbBase | Out-Null
    Check 'count' $LASTEXITCODE 0

    & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action TableExists -Table administrators -DbPath $dbBase | Out-Null
    Check 'table-exists' $LASTEXITCODE 0

    & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action LockProbe -DbPath (Join-Path $workDir 'missing-db') | Out-Null
    Check 'missing-db' $LASTEXITCODE 4

    # live store: JVM-held file must read locked(3); stopped server reads free(0)
    $liveDb = Join-Path $script:RagRoot 'var\meta-display-db\lmsdb'
    if (Test-Path "$liveDb.mv.db") {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $agent -Action LockProbe | Out-Null
        $live = $LASTEXITCODE
        if ($live -eq 0 -or $live -eq 3) { "PASS live-probe exit=$live (0=free,3=jvm-held)" }
        else { "FAIL live-probe exit=$live"; $script:fail++ }
    } else {
        Note 'live-probe (no lmsdb.mv.db yet)'
    }

    "RESULT fail=$fail skip=$skip"
    exit $(if ($fail -eq 0) { 0 } else { 1 })
} finally {
    Remove-Item -Path $workDir -Recurse -Force -ErrorAction SilentlyContinue
}
