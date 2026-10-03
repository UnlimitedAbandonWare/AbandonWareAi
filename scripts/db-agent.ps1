#requires -Version 5.1
<#
.SYNOPSIS
    Agent-facing entry point for the demo-1 local file-H2 store.

.DESCRIPTION
    THIN WRAPPER. The single implementation (SSOT) is scripts/db_agent.py:
    lock probe, read-only SQL gate, masking, live HTTP fallback and exit codes
    all live there. This file only keeps the kit's -Action surface stable and
    remaps reasons onto the fixed kit exit contract:

      -Action LockProbe     -> db_agent.py status       (locked=3, missing=4)
      -Action VerifyAdmin   -> db_agent.py verify-admin (absent/non-bcrypt=5)
      -Action UpsertAdmin   -> db_agent.py upsert-admin (-WhatIf -> --dry-run;
                             auto lane: locked store -> guarded live upsert)
      -Action Query         -> db_agent.py query        (masked JSON rows)
      -Action TableExists   -> db_agent.py query        (INFORMATION_SCHEMA)
      -Action Count         -> db_agent.py query        (COUNT(*))

    Contract: DEMO1-DB-AGENT-KIT-20260928-R1 (exit codes unchanged)
    Docs:     docs/DB_AGENT_CHEATSHEET.md,
              docs/diagnostics/db-agent-kit-20260928/,
              docs/diagnostics/query-agent-auto-20260928/

    Exit codes (kit-wide, fixed):
      0 ok | 2 bad args/input | 3 locked/busy | 4 missing tools/jars/db file |
      5 verify-or-run failed

    Rules:
      * Secrets via -Password / $env:LMS_LOCAL_ADMIN_PASSWORD only; results are
        masked JSON (hashPrefix, never the full hash or plaintext).
      * "File openable" is not "free" - only a real JDBC open answers lock.
      * While Start-RAG is running, reads auto-fallback to the live HTTP lane
        (AWX_DB_VIA=auto default; --via file forces JDBC-only).
      * Never kill a foreign track's server to get a lock; a locked result is
        an answer, not a blocker to force.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\db-agent.ps1 -Action LockProbe

.EXAMPLE
    .\scripts\db-agent.ps1 -Action VerifyAdmin -Username admin

.EXAMPLE
    Set-Item Env:LMS_LOCAL_ADMIN_PASSWORD '<local-dev-password>'
    .\scripts\db-agent.ps1 -Action UpsertAdmin -Username admin
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('LockProbe', 'VerifyAdmin', 'UpsertAdmin', 'Query', 'TableExists', 'Count')]
    [string]$Action,

    [ValidatePattern('^[A-Za-z0-9_.\-]{1,50}$')]
    [string]$Username = 'admin',

    # Forwarded via env to db_agent.py. Prefer env LMS_LOCAL_ADMIN_PASSWORD so
    # the value stays off the command line entirely.
    [string]$Password,
    [string]$Name,

    [ValidatePattern('^ROLE_[A-Z0-9_]+$')]
    [string]$Role = 'ROLE_ADMIN',

    # File-H2 base path WITHOUT the .mv.db suffix (default: Start-RAG store).
    [string]$DbPath,
    [string]$DbUser = 'sa',
    [string]$DbPassword = '',
    [string]$H2Jar,

    # Query lane inputs
    [string]$Sql,          # arbitrary read-only SELECT (denylist enforced)
    [string]$Table,        # table for Query/Count/TableExists
    [int]$MaxRows = 50,    # emitted-row cap for Query (hard cap 500)
    [switch]$WhatIf        # UpsertAdmin -> --dry-run (plan only, no mutation)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:RagRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $PSScriptRoot 'db\h2-common.ps1')

# ---- python + SSOT resolution ------------------------------------------------
$script:PyExe = $null
$script:PyPrefix = @()
foreach ($cand in @(@('python.exe'), @('python3.exe'), @('py.exe', '-3'))) {
    $exe = $cand[0]
    $found = Get-Command $exe -ErrorAction SilentlyContinue
    if ($found) { $script:PyExe = $found.Source; $script:PyPrefix = @($cand | Select-Object -Skip 1); break }
}
$agentPy = Join-Path $script:RagRoot 'scripts\db_agent.py'
if (-not $script:PyExe -or -not (Test-Path $agentPy)) {
    Write-AwxResultAndExit @{ ok = $false; action = $Action; stage = 'tools';
        reason = 'python (or py -3) / scripts\db_agent.py missing - the kit SSOT is python; install Python 3.10+' } 4
}

function Invoke-DbAgent {
    # Runs db_agent.py, captures its single JSON line + exit code.
    # -> @{ Code, Payload, RawTail }
    param([string[]]$Argv)
    $envSet = @()
    try {
        if (-not [string]::IsNullOrWhiteSpace($DbPath)) { $Argv += @('--db-path', $DbPath) }
        if ($DbUser -ne 'sa') { $env:LMS_DB_USER = $DbUser; $envSet += 'LMS_DB_USER' }
        if (-not [string]::IsNullOrEmpty($DbPassword)) { $env:LMS_DB_PASSWORD = $DbPassword; $envSet += 'LMS_DB_PASSWORD' }
        if (-not [string]::IsNullOrWhiteSpace($H2Jar)) { $env:META_DISPLAY_H2_JAR = $H2Jar; $envSet += 'META_DISPLAY_H2_JAR' }
        if (-not [string]::IsNullOrWhiteSpace($Password)) { $env:LMS_LOCAL_ADMIN_PASSWORD = $Password; $envSet += 'LMS_LOCAL_ADMIN_PASSWORD' }
        $errFile = Join-Path $env:TEMP ("awx-dbagent-err-" + [guid]::NewGuid().ToString('N') + '.log')
        try {
            $prevEap = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            $out = & $script:PyExe @($script:PyPrefix) -B $agentPy @Argv 2>$errFile | Out-String
            $code = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $prevEap
        }
        $payload = $null
        foreach ($line in @($out -split "`r?`n" | Where-Object { $_.Trim() } | Select-Object -Last 3)) {
            try { $payload = $line.Trim() | ConvertFrom-Json } catch { }
        }
        $tail = (Get-AwxErrTail -Path $errFile -MaxChars 300)
        Remove-Item -Path $errFile -Force -ErrorAction SilentlyContinue
        return @{ Code = $code; Payload = $payload; RawTail = $tail }
    } finally {
        foreach ($n in $envSet) { Remove-Item -Path "Env:$n" -ErrorAction SilentlyContinue }
    }
}

function Convert-AwxExit {
    # python 2(reason db-file-missing) -> kit 4; everything else passes through.
    param([int]$Code, $Payload)
    if ($Code -eq 2 -and $Payload -and "$($Payload.reason)" -eq 'db-file-missing') { return 4 }
    if ($Code -in @(0, 2, 3, 4, 5)) { return $Code }
    return 5
}

function Out-AwxAgentResult {
    # Re-emit the python payload as kit JSON: add action, drop internals, exit mapped.
    param([string]$ActionName, [hashtable]$Run)
    $p = $Run.Payload
    if ($null -eq $p) {
        Write-AwxResultAndExit @{ ok = $false; action = $ActionName;
            reason = 'agent-output-unparsed'; detail = $Run.RawTail } 5
    }
    $hash = @{}
    foreach ($prop in $p.PSObject.Properties) { $hash[$prop.Name] = $prop.Value }
    $hash.Remove('cmd'); $hash.Remove('exitCode'); $hash.Remove('ok'); $hash.Remove('_exit')
    $hash['action'] = $ActionName
    Write-AwxResultAndExit $hash (Convert-AwxExit -Code $Run.Code -Payload $p)
}

function Assert-AwxTableName {
    param([string]$TableName, [string]$ActionName)
    if ([string]::IsNullOrWhiteSpace($TableName) -or $TableName -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,127}$') {
        Write-AwxResultAndExit @{ ok = $false; action = $ActionName; stage = 'args';
            reason = 'invalid or missing -Table name' } 2
    }
}

switch ($Action) {

    'LockProbe' {
        $r = Invoke-DbAgent @('status')
        $p = $r.Payload
        $livePorts = [ordered]@{
            http_18180 = (Test-AwxTcpPort -Port 18180)
            mgmt_18181 = (Test-AwxTcpPort -Port 18181)
        }
        if ($null -eq $p) {
            Write-AwxResultAndExit @{ ok = $false; action = 'LockProbe';
                reason = 'agent-output-unparsed'; detail = $r.RawTail } 5
        }
        if ($r.Code -eq 0) {
            Write-AwxResultAndExit @{ ok = $true; action = 'LockProbe'; locked = $false;
                dbFile = $p.dbFile; livePorts = $livePorts } 0
        }
        if ($p.reason -eq 'locked' -or $r.Code -eq 3) {
            Write-AwxResultAndExit @{
                ok = $false; action = 'LockProbe'; locked = $true; dbFile = $p.dbFile
                livePorts = $livePorts; live = $p.live
                reason = 'lmsdb.mv.db held by the Start-RAG JVM - reads auto-fallback to the live HTTP lane; never kill a foreign runtime' } 3
        }
        if ($p.reason -eq 'db-file-missing') {
            Write-AwxResultAndExit @{ ok = $false; action = 'LockProbe'; stage = 'db';
                dbFile = $p.dbFile; livePorts = $livePorts;
                reason = 'file H2 missing - start once with Start-RAG.bat, or pass -DbPath' } 4
        }
        Write-AwxResultAndExit @{ ok = $false; action = 'LockProbe'; dbFile = $p.dbFile;
            livePorts = $livePorts; reason = "$($p.reason)"; detail = "$($p.detail)" } 5
    }

    'VerifyAdmin' {
        Out-AwxAgentResult -ActionName 'VerifyAdmin' `
            -Run (Invoke-DbAgent @('verify-admin', '--username', $Username))
    }

    'UpsertAdmin' {
        $argvList = @('upsert-admin', '--username', $Username, '--role', $Role)
        if (-not [string]::IsNullOrWhiteSpace($Name)) { $argvList += @('--name', $Name) }
        if ($WhatIf) { $argvList += '--dry-run' }
        Out-AwxAgentResult -ActionName 'UpsertAdmin' -Run (Invoke-DbAgent $argvList)
    }

    'TableExists' {
        Assert-AwxTableName -TableName $Table -ActionName 'TableExists'
        $sel = 'SELECT COUNT(*) AS "n" FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ' +
               (ConvertTo-SqlLiteral $Table)
        $r = Invoke-DbAgent @('query', '--sql', $sel)
        if ($r.Code -eq 0 -and $r.Payload -and $r.Payload.rows) {
            $n = [int64]($r.Payload.rows[0][0])
            Write-AwxResultAndExit @{ ok = $true; action = 'TableExists'; table = $Table;
                exists = ($n -gt 0) } 0
        }
        Out-AwxAgentResult -ActionName 'TableExists' -Run $r
    }

    'Count' {
        Assert-AwxTableName -TableName $Table -ActionName 'Count'
        $sel = 'SELECT COUNT(*) AS "row_count" FROM "' + $Table + '"'
        $r = Invoke-DbAgent @('query', '--sql', $sel)
        if ($r.Code -eq 0 -and $r.Payload -and $r.Payload.rows) {
            Write-AwxResultAndExit @{ ok = $true; action = 'Count'; table = $Table;
                rowCount = [int64]($r.Payload.rows[0][0]) } 0
        }
        Out-AwxAgentResult -ActionName 'Count' -Run $r
    }

    'Query' {
        $select = $null
        if (-not [string]::IsNullOrWhiteSpace($Sql)) {
            $select = $Sql.Trim()
        } elseif (-not [string]::IsNullOrWhiteSpace($Table)) {
            Assert-AwxTableName -TableName $Table -ActionName 'Query'
            $lim = [Math]::Min([Math]::Max(1, $MaxRows), 500)
            $select = 'SELECT * FROM "' + $Table + '" LIMIT ' + $lim
        } else {
            Write-AwxResultAndExit @{ ok = $false; action = 'Query'; stage = 'args';
                reason = 'pass -Sql <select> or -Table <name>' } 2
        }
        Out-AwxAgentResult -ActionName 'Query' `
            -Run (Invoke-DbAgent @('query', '--sql', $select, '--max-rows',
                                   ([string]([Math]::Min([Math]::Max(1, $MaxRows), 500)))))
    }
}
