#requires -Version 5.1
<#
.SYNOPSIS
  Fixture tests for read_rag_debug_trail.ps1. Builds fake roots under TEMP —
  never touches the real var\ tree and never starts/stops any process.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$script:RepoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$script:TrailScript = Join-Path $PSScriptRoot 'read_rag_debug_trail.ps1'
$script:PsExe = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$script:FixtureRoot = Join-Path $env:TEMP ('awx-trail-test-' + [guid]::NewGuid().ToString('N').Substring(0,8))
$script:Passed = 0; $script:Failed = 0

function Invoke-Trail {
    param([string]$Root, [string[]]$ExtraArgs = @())
    $out = & $script:PsExe -NoLogo -NoProfile -ExecutionPolicy Bypass -File $script:TrailScript -Root $Root @ExtraArgs 2>&1
    return @{ code = [int]$LASTEXITCODE; text = ($out | Out-String) }
}

function Assert-Trail {
    param([bool]$Cond, [string]$Name)
    if ($Cond) { $script:Passed++; Write-Host "[PASS] $Name" }
    else { $script:Failed++; Write-Host "[FAIL] $Name" }
}

function New-FixtureRoot {
    param([string]$Name)
    $root = Join-Path $script:FixtureRoot $Name
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'var\rag-launcher') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $root 'var\debug') | Out-Null
    return $root
}

try {
    # --- Fixture 1: LATEST.json failure summary + err.log --------------------
    $r1 = New-FixtureRoot 'fail-latest'
    $run1 = Join-Path $r1 'var\rag-launcher\20260924-110007-9089dcab'
    New-Item -ItemType Directory -Force -Path $run1 | Out-Null
    $err1 = Join-Path $run1 'chat-ui-vibe-listener-18180.err.log'
    @(
        'gradle daemon started',
        'C:\src\main\java\com\example\lms\llm\gateway\FallbackAwareChatModel.java:453: error: an enum switch case label must be the unqualified name of an enumeration constant',
        '            case HEALTH_DOWN, GPU_DEVICE_LOST, MODEL_MISSING,',
        '                                                              ^',
        "BRAVE_API_KEY=should-never-print-this-token-value",
        'Execution failed for task '':compileJava''.'
    ) | Set-Content -LiteralPath $err1 -Encoding UTF8
    @{
        schemaVersion='awx.rag_launcher_latest.v1'; ok=$false; status='failed'; stage='SPRING';
        reason='runtime-start-or-readiness-failed'; failurePoint='compile';
        listenerStatus='fresh-runtime-provenance-failed'; listenerReason='runtime-start-or-readiness-failed';
        nextAction='inspect_redacted_runtime_logs'; ports=@{server=18180;management=18181;netty=18182};
        runId='20260924-110007-9089dcab'; runDirectory=$run1; logDirectory=$run1;
        resultPath='var\rag-launcher\20260924-110007-9089dcab\result.json';
        evidencePaths=@('var\rag-launcher\20260924-110007-9089dcab\chat-ui-vibe-listener-18180.err.log')
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $r1 'var\rag-launcher\LATEST.json') -Encoding UTF8

    $t1 = Invoke-Trail -Root $r1
    Assert-Trail ($t1.code -eq 4) 'fail-latest: exit 4'
    Assert-Trail ($t1.text -match 'failurePoint=compile') 'fail-latest: failurePoint in stdout'
    Assert-Trail ($t1.text -match 'chat-ui-vibe-listener-18180\.err\.log') 'fail-latest: evidencePaths in stdout'
    Assert-Trail ($t1.text -match 'enum switch case label') 'fail-latest: error excerpt in stdout'
    Assert-Trail ($t1.text -notmatch 'should-never-print-this-token-value') 'fail-latest: secret line redacted'
    Assert-Trail ($t1.text -match 'nextCommand|NEXT.*Debug-RAG\.bat -Action verify') 'fail-latest: nextCommand hint printed'

    # --- Fixture 2: missing LATEST.json -> run-dir fallback ------------------
    $r2 = New-FixtureRoot 'fallback'
    $run2 = Join-Path $r2 'var\rag-launcher\20260924-120000-deadbeef'
    New-Item -ItemType Directory -Force -Path $run2 | Out-Null
    Set-Content -LiteralPath (Join-Path $run2 'launcher.log') -Value 'launcher line1', 'ERROR boot exploded' -Encoding UTF8
    @{ ok=$false; status='failed'; stage='OLLAMA'; reason='ollama-not-ready'; failurePoint='ollama'; logDirectory=$run2 } |
        ConvertTo-Json | Set-Content -LiteralPath (Join-Path $run2 'result.json') -Encoding UTF8

    $t2 = Invoke-Trail -Root $r2
    Assert-Trail ($t2.code -ne 0) 'fallback: nonzero exit'
    Assert-Trail ($t2.text -match '(?i)warn.*LATEST\.json') 'fallback: warning emitted'
    Assert-Trail ($t2.text -match 'ollama-not-ready') 'fallback: run-dir result.json surfaced'
    Assert-Trail ($t2.text -match 'boot exploded') 'fallback: launcher.log excerpt'

    # --- Fixture 3: nothing at all -> exit 3 ---------------------------------
    $r3 = New-FixtureRoot 'empty'
    $t3 = Invoke-Trail -Root $r3
    Assert-Trail ($t3.code -eq 3) 'empty: exit 3'

    # --- Fixture 4: ready LATEST.json -> exit 0 -------------------------------
    $r4 = New-FixtureRoot 'ready'
    $run4 = Join-Path $r4 'var\rag-launcher\20260924-130000-cafe'
    New-Item -ItemType Directory -Force -Path $run4 | Out-Null
    @{ schemaVersion='awx.rag_launcher_latest.v1'; ok=$true; status='ready'; stage='READY'; failurePoint=$null;
       runId='20260924-130000-cafe'; runDirectory=$run4; ports=@{server=18180;management=18181;netty=18182} } |
        ConvertTo-Json -Depth 6 | Set-Content -LiteralPath (Join-Path $r4 'var\rag-launcher\LATEST.json') -Encoding UTF8
    $t4 = Invoke-Trail -Root $r4
    Assert-Trail ($t4.code -eq 0) 'ready: exit 0'
    Assert-Trail ($t4.text -match 'status=ready') 'ready: status in stdout'

    # --- Fixture 5: -JsonPath output ------------------------------------------
    $jsonOut = Join-Path $r1 'trail-out.json'
    $t5 = Invoke-Trail -Root $r1 -ExtraArgs @('-JsonPath', $jsonOut)
    Assert-Trail ((Test-Path -LiteralPath $jsonOut) -and ((Get-Content -Raw $jsonOut | ConvertFrom-Json).latest.failurePoint -eq 'compile')) 'jsonpath: failurePoint in JSON file'

    Write-Host ""
    Write-Host "[TRAIL-TESTS] passed=$($script:Passed) failed=$($script:Failed)"
    if ($script:Failed -gt 0) { exit 1 }
    exit 0
} finally {
    if (Test-Path -LiteralPath $script:FixtureRoot) { Remove-Item -LiteralPath $script:FixtureRoot -Recurse -Force -ErrorAction SilentlyContinue }
}
