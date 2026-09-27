$ErrorActionPreference = 'Continue'

$ScriptsRoot = $PSScriptRoot
$SmokeScript = Join-Path $ScriptsRoot 'smoke_chat_debug_fx_sse.ps1'
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
    Write-Host "[chat-debug-fx-sse-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[chat-debug-fx-sse-test][FAIL] $Name :: $Message"
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
    Assert-True 'chat debug fx sse smoke script exists' (Test-Path -LiteralPath $SmokeScript) 'missing scripts\smoke_chat_debug_fx_sse.ps1'
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

        Assert-Contains 'smoke loads http client assembly' $source 'Add-Type -AssemblyName System.Net.Http'
        Assert-Contains 'smoke can start bootRun' $source 'bootRun'
        Assert-Contains 'smoke hides runtime window' $source '-WindowStyle Hidden'
        Assert-Contains 'smoke supports assume-running mode' $source 'AssumeRunning'
        Assert-Contains 'smoke supports query rewrite requirement mode' $source 'RequireQueryRewrite'
        Assert-Contains 'smoke supports trace-memory requirement mode' $source 'RequireTraceMemory'
        Assert-Contains 'smoke posts to stream endpoint' $source '/api/chat/stream'
        Assert-Contains 'smoke seeds trace-memory self-probe' $source '/api/diagnostics/trace/memory/self-probe?scenario=silent_failure'
        Assert-Contains 'smoke disables rag in payload' $source 'useRag = $false'
        Assert-Contains 'smoke enables rag only for query rewrite proof' $source 'useRag = [bool]$RequireQueryRewrite'
        Assert-Contains 'smoke disables web search in payload' $source 'useWebSearch = $false'
        Assert-Contains 'smoke keeps search mode auto for query rewrite proof' $source 'searchMode = $effectiveSearchMode'
        Assert-Contains 'smoke uses auto search policy for query rewrite proof' $source '$effectiveSearchMode = if ($RequireQueryRewrite'
        Assert-Contains 'smoke defines query rewrite diagnostic message' $source '$QueryRewriteDiagnosticMessage'
        Assert-Contains 'smoke uses effective message for query rewrite mode' $source '$effectiveMessage = if ($RequireQueryRewrite'
        Assert-Contains 'smoke posts effective diagnostic message' $source 'message = $effectiveMessage'
        Assert-Contains 'smoke redacts effective diagnostic message' $source '$effectiveMessage'
        Assert-Contains 'smoke parses debug fx event' $source 'event: debug_fx'
        Assert-Contains 'smoke parses transformer event' $source 'event -eq "transformer"'
        Assert-Contains 'smoke locates rewrite transformer block' $source 'Get-TransformerBlock $candidate "rewrite"'
        Assert-Contains 'smoke requires super-token rewrite reason' $source 'super-tokens:'
        Assert-Contains 'smoke accepts stream transformer query rewrite reason' $source 'stream_transformer'
        Assert-Contains 'smoke accepts count-only rewrite reason tokens' $source 'models:'
        Assert-Contains 'smoke accepts compact supers rewrite token' $source 'supers:'
        Assert-Contains 'smoke accepts verification/exploration lane rewrite token' $source 'lanes:'
        Assert-Contains 'smoke accepts validation/exploration temperature rewrite token' $source 'temp:'
        Assert-Contains 'smoke accepts query rewrite profile token' $source 'profile:'
        Assert-Contains 'smoke can fall back to heartbeat rewrite proof' $source '/api/chat/ui-heartbeat'
        Assert-Contains 'smoke validates heartbeat rewrite proof helper' $source 'Test-QueryRewriteHeartbeatProof'
        Assert-Contains 'smoke requires latest heartbeat query rewrite trace' $source 'latest_query_rewrite_trace'
        Assert-Contains 'smoke requires heartbeat variant lane temperature hints' $source 'variantLaneTemperatureHints'
        Assert-Contains 'smoke checks heartbeat latest trace entry count' $source 'latestTraceEntryCount'
        Assert-NotContains 'smoke does not reject heartbeat proof only because enabled flag is false' $source '-not [bool]$enabled'
        Assert-NotContains 'smoke does not reject heartbeat proof only because branch count is zero' $source '$branchCount -le 0'
        Assert-NotContains 'smoke does not reject heartbeat proof only because axis count is zero' $source '$axisCount -le 0'
        Assert-Contains 'smoke reports heartbeat rewrite proof source' $source 'queryRewriteSource'
        Assert-Contains 'smoke never treats raw query text as rewrite proof' $source 'rawUserQuery'
        Assert-Contains 'smoke summarizes query rewrite proof' $source 'queryRewritePresent'
        Assert-Contains 'smoke streams response headers first' $source 'ResponseHeadersRead'
        Assert-Contains 'smoke reads sse incrementally' $source 'ReadLineAsync'
        Assert-Contains 'smoke exits after required independent evidence' $source 'if ((-not $operatorActionRequired -or $debugFxSatisfied) -and $queryRewriteSatisfied -and $traceMemorySatisfied)'
        Assert-NotContains 'smoke does not wait for optional operator labels before exiting' $source 'if ($debugFxSatisfied -and $queryRewriteSatisfied -and $traceMemorySatisfied)'
        Assert-Contains 'smoke checks local trigger label' $source 'localLlmTriggerReason'
        Assert-Contains 'smoke checks local action label' $source 'localLlmNextAction'
        Assert-Contains 'smoke makes operator action optional for independent query-rewrite or trace-memory proof' $source '$operatorActionRequired = -not ([bool]$RequireQueryRewrite -or [bool]$RequireTraceMemory)'
        Assert-NotContains 'smoke does not couple trace-memory proof to local llm operator labels' $source '$operatorActionRequired = -not [bool]$RequireQueryRewrite -or [bool]$RequireTraceMemory'
        Assert-Contains 'smoke checks trace-memory route label' $source 'traceMemoryRouteDecision'
        Assert-Contains 'smoke checks trace-memory virtual checkpoint key label' $source 'traceMemoryVirtualCheckpointKey'
        Assert-Contains 'smoke checks trace-memory virtual checkpoint stage label' $source 'traceMemoryVirtualCheckpointStage'
        Assert-Contains 'smoke checks trace-memory virtual checkpoint phase label' $source 'traceMemoryVirtualCheckpointPhase'
        Assert-Contains 'smoke checks trace-memory cfvm offer label' $source 'traceMemoryCfvmOffered'
        Assert-Contains 'smoke checks trace-memory pattern label' $source 'traceMemoryCfvmPatternId'
        Assert-Contains 'smoke tracks operator debug fx event separately' $source '$operatorDebugEvent = $candidateDebugEvent'
        Assert-Contains 'smoke prefers debug fx event with both trace-memory and operator labels' $source '$traceMemoryOperatorDebugEvent = $candidateDebugEvent'
        Assert-Contains 'smoke can merge split debug fx label events' $source 'Merge-DebugFxLabels $traceMemoryLabels $operatorLabels'
        Assert-Contains 'smoke summarizes trace-memory proof' $source 'traceMemoryPresent'
        Assert-Contains 'smoke fails when required trace-memory is missing' $source 'debug_fx missing trace-memory labels'
        Assert-Contains 'smoke scans high confidence secret hits' $source 'secretPatternHits'
        Assert-Contains 'smoke scans raw prompt leakage' $source 'rawPromptHits'
        Assert-Contains 'smoke scans raw model leakage' $source 'rawModelHits'
        Assert-Contains 'smoke emits evidence needed' $source 'evidence_needed'
        Assert-Contains 'smoke supports APP_PUBLIC_BASE_URL fallback' $source '$env:APP_PUBLIC_BASE_URL'
        Assert-Contains 'smoke supports PUBLIC_BASE_URL fallback' $source '$env:PUBLIC_BASE_URL'
        Assert-Contains 'smoke saves APP_PUBLIC_BASE_URL env' $source '"APP_PUBLIC_BASE_URL"'
        Assert-Contains 'smoke saves PUBLIC_BASE_URL env' $source '"PUBLIC_BASE_URL"'
        Assert-Contains 'smoke saves SECURITY_FORCE_HTTPS env' $source '"SECURITY_FORCE_HTTPS"'
        Assert-Contains 'smoke derives port-scoped build id' $source '$SmokeBuildId = "desktop-chat-debug-fx-sse-$Port"'
        Assert-Contains 'smoke uses port-scoped project cache' $source 'awx-gradle-project-cache\$SmokeBuildId'
        Assert-Contains 'smoke checks only listening ports' $source 'Get-NetTCPConnection -State Listen -LocalPort $p'
        Assert-NotContains 'smoke does not classify time-wait sockets as port conflicts' $source 'netstat -ano | Select-String ":$p "'
        Assert-Contains 'smoke defines local HTTP rollback base' $source '$localHttpBaseUrl = "http://127.0.0.1:$Port"'
        Assert-Contains 'smoke forces APP_PUBLIC_BASE_URL to local HTTP' $source '$env:APP_PUBLIC_BASE_URL = $localHttpBaseUrl'
        Assert-Contains 'smoke forces PUBLIC_BASE_URL to local HTTP' $source '$env:PUBLIC_BASE_URL = $localHttpBaseUrl'
        Assert-Contains 'smoke disables forced HTTPS for local run' $source '$env:SECURITY_FORCE_HTTPS = "false"'
        Assert-Contains 'smoke emits redacted local HTTP rollback log' $source 'localHttpRollback=true baseScheme=http host=loopback'
        $appPublicIndex = $source.IndexOf('$env:APP_PUBLIC_BASE_URL')
        $publicIndex = $source.IndexOf('$env:PUBLIC_BASE_URL')
        $localFallbackIndex = $source.IndexOf('"http://127.0.0.1:$RuntimePort"')
        Assert-True 'smoke checks APP_PUBLIC_BASE_URL before local fallback' ($appPublicIndex -ge 0 -and $localFallbackIndex -gt $appPublicIndex) 'public deployment base URL must precede local fallback'
        Assert-True 'smoke checks PUBLIC_BASE_URL before local fallback' ($publicIndex -ge 0 -and $localFallbackIndex -gt $publicIndex) 'public deployment base URL must precede local fallback'
        Assert-Contains 'smoke clears duplicate local api key env path' $source '$env:LLM_API_KEY = ""'
        Assert-NotContains 'smoke does not set duplicate local api key value' $source '$env:LLM_API_KEY = "ollama"'
        Assert-Contains 'smoke overrides naver self placeholder for boot' $source 'SPRING_APPLICATION_JSON'
        Assert-Contains 'smoke sets naver keys override' $source '"keys":""'
        Assert-Contains 'smoke sets naver client id override' $source '"client-id":""'
        Assert-Contains 'smoke sets naver client secret override' $source '"client-secret":""'
        Assert-NotContains 'smoke does not dump full environment' $source 'Get-ChildItem Env:'
        Assert-NotContains 'smoke does not put data in query string' $source '?message='
        Assert-NotContains 'smoke does not print authorization headers' $source 'Authorization='

        $tempOut = Join-Path ([IO.Path]::GetTempPath()) ('awx-chat-debug-fx-sse-test-' + [guid]::NewGuid().ToString('N'))
        $lines = & $PowerShellExe -NoProfile -ExecutionPolicy Bypass -File $SmokeScript `
            -StaticOnly `
            -OutputDir $tempOut 2>&1 |
            ForEach-Object { $_.ToString() }
        $invokeOutput = $lines -join "`n"
        Assert-Contains 'static smoke exits cleanly' $invokeOutput '[AWX][chat-debug-fx-sse] staticOnly=true'
        Assert-Contains 'static smoke reports script path' $invokeOutput 'script='
        Assert-NotContains 'static smoke does not print raw prompt' $invokeOutput 'AWX debug fx local route probe'
        Assert-NotContains 'static smoke does not print raw model' $invokeOutput 'qwen3:8b'
        if (Test-Path -LiteralPath $tempOut) {
            Remove-Item -LiteralPath $tempOut -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
} catch {
    Write-Fail 'unexpected exception' $_.Exception.Message
}

if ($Failures -gt 0) {
    Write-Host "[chat-debug-fx-sse-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host '[chat-debug-fx-sse-test][SUMMARY] failed=0'
