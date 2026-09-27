$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$SmokeScript = Join-Path $ScriptsRoot 'trace_memory_recovery_synthetic_smoke.ps1'
$PowerShellExe = $null
try {
    $PowerShellExe = (Get-Process -Id $PID).Path
} catch {
    $PowerShellExe = $null
}
if ([string]::IsNullOrWhiteSpace($PowerShellExe)) {
    $PowerShellExe = 'powershell'
}
$Failures = 0

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[trace-memory-recovery-smoke-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[trace-memory-recovery-smoke-test][FAIL] $Name :: $Message"
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = 'assertion failed'
    )
    if ($Condition) { Write-Pass $Name } else { Write-Fail $Name $Message }
}

function Assert-Contains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name ($Text.Contains($Needle)) "expected source to contain '$Needle'"
}

function Assert-NotContains {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Needle
    )
    Assert-True $Name (-not $Text.Contains($Needle)) "expected source not to contain '$Needle'"
}

try {
    Assert-True 'trace-memory recovery smoke script exists' (Test-Path -LiteralPath $SmokeScript) 'missing scripts\trace_memory_recovery_synthetic_smoke.ps1'
    if (Test-Path -LiteralPath $SmokeScript) {
        $source = Get-Content -Raw -LiteralPath $SmokeScript

        # Execute only the real resolver and its call-site expression; never boot or send HTTP.
        $tokens = $null
        $parseErrors = $null
        $ast = [Management.Automation.Language.Parser]::ParseInput($source, [ref]$tokens, [ref]$parseErrors)
        $resolver = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Resolve-SmokeBaseUrl'
        }, $true)
        $callSite = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.AssignmentStatementAst] -and
                $node.Left.Extent.Text -eq '$effectiveBaseUrl'
        }, $true)
        Assert-True 'resolver and call site parse' ($parseErrors.Count -eq 0 -and $null -ne $resolver -and $null -ne $callSite)
        $savedAppPublic = $env:APP_PUBLIC_BASE_URL
        $savedPublic = $env:PUBLIC_BASE_URL
        try {
            . ([scriptblock]::Create($resolver.Extent.Text))
            $cases = @(
                @{ Name = 'local boot ignores both inherited public URLs'; Explicit = ''; App = 'https://example.invalid/app'; Public = 'https://example.invalid/public'; Assume = $false; Want = 'http://127.0.0.1:18085' },
                @{ Name = 'local boot ignores PUBLIC_BASE_URL alone'; Explicit = ''; App = ''; Public = 'https://example.invalid/public'; Assume = $false; Want = 'http://127.0.0.1:18085' },
                @{ Name = 'empty environment uses local port'; Explicit = ''; App = ''; Public = ''; Assume = $false; Want = 'http://127.0.0.1:18085' },
                @{ Name = 'assume-running prefers inherited app URL'; Explicit = ''; App = 'https://example.invalid/app/'; Public = 'https://example.invalid/public'; Assume = $true; Want = 'https://example.invalid/app' },
                @{ Name = 'assume-running uses inherited public fallback'; Explicit = ''; App = ''; Public = 'https://example.invalid/public/'; Assume = $true; Want = 'https://example.invalid/public' },
                @{ Name = 'explicit URL wins in assume-running mode'; Explicit = 'https://example.invalid/explicit/'; App = 'https://example.invalid/app'; Public = ''; Assume = $true; Want = 'https://example.invalid/explicit' },
                @{ Name = 'explicit local URL wins during local boot'; Explicit = 'http://127.0.0.1:18085/'; App = 'https://example.invalid/app'; Public = ''; Assume = $false; Want = 'http://127.0.0.1:18085' },
                @{ Name = 'local-start rejects HTTPS'; Explicit = 'https://127.0.0.1:18085'; App = ''; Public = ''; Assume = $false; Reject = $true },
                @{ Name = 'local-start rejects remote host'; Explicit = 'http://example.invalid:18085'; App = ''; Public = ''; Assume = $false; Reject = $true },
                @{ Name = 'local-start rejects different port'; Explicit = 'http://127.0.0.1:18086'; App = ''; Public = ''; Assume = $false; Reject = $true },
                @{ Name = 'local-start rejects malformed URL'; Explicit = 'not-a-url'; App = ''; Public = ''; Assume = $false; Reject = $true }
            )
            foreach ($case in $cases) {
                $env:APP_PUBLIC_BASE_URL = $case.App
                $env:PUBLIC_BASE_URL = $case.Public
                $BaseUrl = $case.Explicit
                $Port = 18085
                $AssumeRunning = [bool]$case.Assume
                $rejected = $false
                $result = $null
                try { $result = & ([scriptblock]::Create($callSite.Right.Extent.Text)) }
                catch { $rejected = $true }
                if ($case.Reject) {
                    Assert-True $case.Name $rejected 'unsafe local-start URL was accepted'
                } else {
                    Assert-True $case.Name ((-not $rejected) -and $result -ceq $case.Want) 'resolver returned the wrong execution target'
                }
                # Run the actual URL setup statements as well as the resolver expression.
                $setup = @($callSite.Parent.Statements | Where-Object {
                    ($_ -is [Management.Automation.Language.AssignmentStatementAst] -and
                        $_.Left.Extent.Text -in @('$localHttpBaseUrl', '$env:APP_PUBLIC_BASE_URL', '$env:PUBLIC_BASE_URL')) -or
                    ($_ -is [Management.Automation.Language.IfStatementAst] -and
                        $_.Extent.Text.Contains('$env:APP_PUBLIC_BASE_URL = $localHttpBaseUrl'))
                })
                . ([scriptblock]::Create(($setup | ForEach-Object { $_.Extent.Text }) -join "`n"))
                if ($AssumeRunning) {
                    Assert-True ($case.Name + ' preserves inherited environment') (
                        [string]$env:APP_PUBLIC_BASE_URL -ceq $case.App -and [string]$env:PUBLIC_BASE_URL -ceq $case.Public
                    ) 'assume-running setup replaced the requested environment'
                }
            }
        } finally {
            $env:APP_PUBLIC_BASE_URL = $savedAppPublic
            $env:PUBLIC_BASE_URL = $savedPublic
        }

        Assert-Contains 'smoke can start bootRun' $source 'bootRun'
        Assert-Contains 'smoke supports assume-running mode' $source 'AssumeRunning'
        Assert-Contains 'smoke supports static-only mode' $source 'StaticOnly'
        Assert-Contains 'smoke forces desktop Gradle home' $source '.gradle-awx-desktop'
        Assert-Contains 'smoke uses host-specific project cache' $source 'awx-gradle-project-cache\desktop-trace-memory-recovery'
        Assert-Contains 'smoke exposes isolated Netty port' $source '[int]$NettyPort = 18094'
        Assert-Contains 'smoke restores Netty port env' $source '"NETTY_PORT"'
        Assert-Contains 'smoke sets Netty port env' $source '$env:NETTY_PORT = [string]$NettyPort'
        Assert-Contains 'smoke checks Netty port conflicts' $source '@($Port, $ManagementPort, $NettyPort)'
        Assert-Contains 'smoke passes explicit Netty port to bootRun' $source '--args=--netty.port=$NettyPort'
        Assert-Contains 'smoke waits on ui heartbeat' $source '/api/chat/ui-heartbeat'
        Assert-Contains 'smoke calls trace-memory self-probe' $source '/api/diagnostics/trace/memory/self-probe?scenario='
        Assert-Contains 'smoke checks latest trace-memory html' $source '/api/diagnostics/trace/snapshots/latest-trace-memory/html'
        Assert-Contains 'smoke checks context contamination scenario' $source 'synthetic_context_contamination'
        Assert-Contains 'smoke checks loader starvation scenario' $source 'synthetic_loader_starvation'
        Assert-Contains 'smoke checks after-filter starvation scenario' $source 'synthetic_after_filter_starvation'
        Assert-Contains 'smoke checks dropped breadcrumb scenario' $source 'synthetic_dropped_breadcrumb'
        Assert-Contains 'smoke checks silent failure scenario' $source 'synthetic_silent_failure'
        Assert-Contains 'smoke expects context contamination reason' $source 'context_contamination'
        Assert-Contains 'smoke expects loader starvation reason' $source 'loader_starvation'
        Assert-Contains 'smoke expects after-filter starvation reason' $source 'after_filter_starvation'
        Assert-Contains 'smoke expects dropped breadcrumb reason' $source 'dropped_breadcrumb'
        Assert-Contains 'smoke expects silent failure reason' $source 'silent_failure'
        Assert-Contains 'smoke expects BREAK risk' $source "ExpectedRisk = 'BREAK'"
        Assert-Contains 'smoke expects WARN risk' $source "ExpectedRisk = 'WARN'"
        Assert-Contains 'smoke expects quarantine route' $source "ExpectedRoute = 'quarantine_retry_failsoft'"
        Assert-Contains 'smoke expects retry route' $source "ExpectedRoute = 'retry_failsoft'"
        Assert-Contains 'smoke checks self-probe recovery route' $source '"recoveryRoute"'
        Assert-Contains 'smoke checks self-probe recovery route decision' $source '"recoveryRouteDecision"'
        Assert-Contains 'smoke checks self-probe recovery policy max rounds' $source '"recoveryPolicyMaxRounds"'
        Assert-Contains 'smoke checks suspect payload isolation' $source '"suspectPayloadIsolated"'
        Assert-Contains 'smoke checks heartbeat recovery route' $source '"latestRecoveryRoute"'
        Assert-Contains 'smoke checks heartbeat recovery route decision' $source '"latestRecoveryRouteDecision"'
        Assert-Contains 'smoke checks checkpoint json recovery route decision' $source '$checkpointRecoveryRouteDecision'
        Assert-Contains 'smoke checks checkpoint json cfvm offer' $source '$checkpointCfvmOffered'
        Assert-Contains 'smoke checks debug ai compact endpoint' $source '/api/diagnostics/debug/ai/compact'
        Assert-Contains 'smoke checks debug ai compact route decision' $source '$compactRouteDecision'
        Assert-Contains 'smoke checks debug ai compact cfvm offer' $source '$compactCfvmOffered'
        Assert-Contains 'smoke checks debug ai compact cfvm pattern' $source '$compactCfvmPatternId'
        Assert-Contains 'smoke checks quarantine true path' $source 'ExpectedQuarantine = $true'
        Assert-Contains 'smoke checks quarantine false path' $source 'ExpectedQuarantine = $false'
        Assert-Contains 'smoke checks all scenarios triggered' $source 'triggeredCount'
        Assert-Contains 'smoke checks html raw leak flag' $source 'htmlRawLeak'
        Assert-Contains 'smoke forbids raw memory context' $source 'memoryCtx'
        Assert-Contains 'smoke forbids raw snapshot payload' $source 'rawSnapshot.raw'
        Assert-Contains 'smoke forbids synthetic raw contamination marker' $source 'history_context_contamination'
        Assert-Contains 'smoke scans high confidence secrets' $source 'secretPatternHits'
        Assert-Contains 'smoke writes JSON report' $source 'ConvertTo-Json'
        Assert-Contains 'smoke restores environment' $source 'SetEnvironmentVariable'
        Assert-Contains 'smoke supports APP_PUBLIC_BASE_URL fallback' $source '$env:APP_PUBLIC_BASE_URL'
        Assert-Contains 'smoke supports PUBLIC_BASE_URL fallback' $source '$env:PUBLIC_BASE_URL'
        Assert-Contains 'smoke saves APP_PUBLIC_BASE_URL env' $source '"APP_PUBLIC_BASE_URL"'
        Assert-Contains 'smoke saves PUBLIC_BASE_URL env' $source '"PUBLIC_BASE_URL"'
        Assert-Contains 'smoke saves SECURITY_FORCE_HTTPS env' $source '"SECURITY_FORCE_HTTPS"'
        Assert-Contains 'smoke defines local HTTP rollback base' $source '$localHttpBaseUrl = "http://127.0.0.1:$Port"'
        Assert-Contains 'smoke forces APP_PUBLIC_BASE_URL to local HTTP' $source '$env:APP_PUBLIC_BASE_URL = $localHttpBaseUrl'
        Assert-Contains 'smoke forces PUBLIC_BASE_URL to local HTTP' $source '$env:PUBLIC_BASE_URL = $localHttpBaseUrl'
        Assert-Contains 'smoke disables forced HTTPS for local run' $source '$env:SECURITY_FORCE_HTTPS = "false"'
        Assert-Contains 'smoke emits redacted local HTTP rollback log' $source 'localHttpRollback=true baseScheme=http host=loopback'
        Assert-Contains 'smoke stops process tree' $source 'Stop-ProcessTree'
        Assert-Contains 'smoke emits evidence needed' $source 'evidence_needed'
        Assert-NotContains 'smoke does not dump full environment' $source 'Get-ChildItem Env:'
        Assert-NotContains 'smoke does not print authorization headers' $source 'Authorization='

        $tempOut = Join-Path ([IO.Path]::GetTempPath()) ('awx-trace-memory-recovery-smoke-test-' + [guid]::NewGuid().ToString('N'))
        $lines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
            -StaticOnly `
            -OutputDir $tempOut 2>&1 |
            ForEach-Object { $_.ToString() }
        $invokeOutput = $lines -join "`n"
        Assert-Contains 'static trace-memory smoke exits cleanly' $invokeOutput '[AWX][trace-memory-recovery-smoke] staticOnly=true'
        Assert-Contains 'static trace-memory smoke reports script path' $invokeOutput 'script='
        Assert-NotContains 'static trace-memory smoke does not print synthetic raw marker' $invokeOutput 'history_context_contamination'
        if (Test-Path -LiteralPath $tempOut) {
            Remove-Item -LiteralPath $tempOut -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
}

if ($Failures -gt 0) {
    Write-Host "[trace-memory-recovery-smoke-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[trace-memory-recovery-smoke-test][SUMMARY] failed=0'
exit 0
