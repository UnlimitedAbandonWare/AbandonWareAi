$ErrorActionPreference = "Stop"

$builderPath = "main\java\com\example\lms\api\ChatStreamSignalBuilder.java"
$testPath = "src\test\java\com\example\lms\api\ChatStreamSignalBuilderTest.java"
$controllerPath = "main\java\com\example\lms\api\ChatApiController.java"
$controllerTestPath = "src\test\java\com\example\lms\api\ChatApiControllerTraceMetaTest.java"

$builder = Get-Content $builderPath -Raw
$test = Get-Content $testPath -Raw
$controller = Get-Content $controllerPath -Raw
$controllerTest = Get-Content $controllerTestPath -Raw
$failures = New-Object System.Collections.Generic.List[string]

function Require-Contains([string]$Text, [string]$Needle, [string]$Label) {
    if (-not $Text.Contains($Needle)) {
        $failures.Add($Label)
    }
}

function Require-NotContains([string]$Text, [string]$Needle, [string]$Label) {
    if ($Text.Contains($Needle)) {
        $failures.Add($Label)
    }
}

Require-Contains $builder '"chatHarmonyInputPresent"' "DebugFx labels must expose chat harmony input presence"
Require-Contains $builder '"chatHarmonyDegraded"' "DebugFx labels must expose chat harmony degraded state"
Require-Contains $builder '"debugAiNextAction"' "DebugFx labels must expose the next debug action"
Require-Contains $builder '"debugAiNextReason"' "DebugFx labels must expose the redacted next debug reason"
Require-Contains $builder '"traceMemoryRouteDecision"' "DebugFx labels must expose trace-memory recovery routeDecision"
Require-Contains $builder '"traceMemoryVirtualCheckpointKey"' "DebugFx labels must expose trace-memory virtual checkpoint key"
Require-Contains $builder '"traceMemoryVirtualCheckpointStage"' "DebugFx labels must expose trace-memory virtual checkpoint stage"
Require-Contains $builder '"traceMemoryVirtualCheckpointPhase"' "DebugFx labels must expose trace-memory virtual checkpoint phase"
Require-Contains $builder '"traceMemoryCfvmOffered"' "DebugFx labels must expose trace-memory CFVM offer state"
Require-Contains $builder '"traceMemoryCfvmPatternId"' "DebugFx labels must expose trace-memory CFVM pattern id"
Require-Contains $builder '"ollamaNativeGpuMode"' "DebugFx labels must expose native Ollama GPU mode"
Require-Contains $builder '"ollamaNativeNumGpu"' "DebugFx labels must expose native Ollama numGpu without raw model or prompt"
Require-Contains $builder "matrixActionFromDecision" "stream signal builder must derive actions from virtual matrix decisions"
Require-Contains $builder "inspect_chat_harmony_trace" "chat harmony degradation must route agents to the harmony trace"
Require-Contains $builder "chatHarmonyNeedsInspection" "stream signal builder must decide when harmony needs inspection"
Require-Contains $builder "debugAiNextReason(" "next reason must be derived by a helper, not raw metadata passthrough"
Require-Contains $builder "SafeRedactor.traceLabelOrFallback" "debug action reasons must be trace-label redacted"
Require-Contains $controller 'attachDebugAiMatrixTrace(preLlmMeta, "stream.preLlm")' "pre-LLM debug_fx must include the 300-matrix weighted summary before chatService blocks"
Require-Contains $controller "mirrorDebugAiTraceMemoryCompact(meta, compact)" "pre/final debug_fx must mirror trace-memory compact diagnostics into stream metadata"
Require-Contains $controller '"traceMemoryDiagnostics"' "ChatApiController must read Debug AI trace-memory diagnostics"

Require-Contains $controllerTest "traceMemoryCompactMirrorCarriesVirtualCheckpointIntoDebugFxLabels" "controller behavior contract must cover allowlisted trace-memory labels end to end"
Require-Contains $controllerTest 'event.debugFxSignal().labels().get("traceMemoryRouteDecision")' "controller behavior contract must assert trace-memory routeDecision label"
Require-Contains $controllerTest 'event.debugFxSignal().labels().get("traceMemoryCfvmOffered")' "controller behavior contract must assert trace-memory CFVM offer label"
Require-Contains $controllerTest 'event.debugFxSignal().labels().get("traceMemoryCfvmPatternId")' "controller behavior contract must assert trace-memory CFVM pattern label"
Require-Contains $test "chatHarmonyInspectionOverridesMatrixObserveActionInDebugFxWithoutRawPayloads" "unit contract must cover harmony override action"
Require-Contains $test 'debugFx.labels().get("chatHarmonyDegraded")' "unit contract must assert degraded harmony label"
Require-Contains $test "inspect_chat_harmony_trace" "unit contract must assert the harmony inspection action"
Require-Contains $test "chat_harmony.fallback_evidence" "unit contract must assert redacted harmony reason"
Require-Contains $test "investigate_debug_ai_hot_chunk" "unit contract must assert matrix-derived action"
Require-Contains $test 'debugFx.labels().get("traceMemoryRouteDecision")' "unit contract must assert trace-memory routeDecision label"
Require-Contains $test 'debugFx.labels().get("traceMemoryVirtualCheckpointKey")' "unit contract must assert trace-memory virtual checkpoint key label"
Require-Contains $test 'debugFx.labels().get("traceMemoryVirtualCheckpointStage")' "unit contract must assert trace-memory virtual checkpoint stage label"
Require-Contains $test 'debugFx.labels().get("traceMemoryVirtualCheckpointPhase")' "unit contract must assert trace-memory virtual checkpoint phase label"
Require-Contains $test 'debugFx.labels().get("traceMemoryCfvmPatternId")' "unit contract must assert trace-memory CFVM pattern label"

$chatJs = Get-Content "main\resources\static\js\chat.js" -Raw
Require-Contains $chatJs 'debugFxLabel(labels, "traceMemoryRouteDecision")' "chat UI debug_fx summary must read trace-memory routeDecision"
Require-Contains $chatJs 'debugFxLabel(labels, "traceMemoryVirtualCheckpointKey")' "chat UI debug_fx summary must read trace-memory virtual checkpoint key"
Require-Contains $chatJs 'debugFxLabel(labels, "traceMemoryVirtualCheckpointStage")' "chat UI debug_fx summary must read trace-memory virtual checkpoint stage"
Require-Contains $chatJs 'debugFxLabel(labels, "traceMemoryVirtualCheckpointPhase")' "chat UI debug_fx summary must read trace-memory virtual checkpoint phase"
Require-Contains $chatJs 'pushDebugFxPart(parts, "Trace Memory"' "chat UI debug_fx summary must render trace-memory operator axis"

Require-NotContains $builder '"rawAnswer"' "stream signal builder must not publish raw answer labels"
Require-NotContains $builder '"rawUserQuery"' "stream signal builder must not publish raw user query labels"
Require-NotContains $builder '"rawPrompt"' "stream signal builder must not publish raw prompt labels"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][chat-stream][debug-action] FAIL"
    foreach ($failure in $failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "[AWX][chat-stream][debug-action] PASS"
