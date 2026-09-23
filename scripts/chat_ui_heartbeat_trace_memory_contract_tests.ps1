$ErrorActionPreference = "Stop"

$probePath = "main\java\com\example\lms\web\ChatUiCoreHeartbeatProbe.java"
$payloadPath = "main\java\com\example\lms\web\ChatUiHeartbeatPayload.java"
$controllerPath = "main\java\com\example\lms\api\TraceSnapshotsDiagnosticsController.java"
$snapshotStorePath = "main\java\com\example\lms\trace\TraceSnapshotStore.java"
$fingerprintProbePath = "main\java\com\example\lms\trace\TraceMemoryFingerprintProbe.java"
$builderPath = "main\java\com\example\lms\service\AgentVisibleDebugEvidenceBuilder.java"
$chatJsPath = "main\resources\static\js\chat.js"
$chatHtmlPath = "main\resources\templates\chat-ui.html"
$chatCssPath = "main\resources\static\css\chat-style.css"

$probe = Get-Content $probePath -Raw
$payload = Get-Content $payloadPath -Raw
$controller = Get-Content $controllerPath -Raw
$snapshotStore = Get-Content $snapshotStorePath -Raw
$fingerprintProbe = Get-Content $fingerprintProbePath -Raw
$builder = Get-Content $builderPath -Raw
$chatJs = Get-Content $chatJsPath -Raw
$chatHtml = Get-Content $chatHtmlPath -Raw
$chatCss = Get-Content $chatCssPath -Raw
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

Require-Contains $probe '"traceMemory"' "heartbeat must publish a redacted traceMemory block"
Require-Contains $probe "LATEST_TRACE_MEMORY_ROUTE" "heartbeat must expose a fixed latest trace-memory route"
Require-Contains $probe "LATEST_TRACE_MEMORY_CHECKPOINTS_ROUTE" "heartbeat must expose a fixed trace-memory checkpoint json route"
Require-Contains $probe "traceMemory(traceSnapshotStore, traceSnapshotSummaries)" "heartbeat must derive traceMemory from TraceSnapshotStore"
Require-Contains $probe "latestTraceMemoryTrace" "heartbeat must find latest trace-memory snapshot"
Require-Contains $probe "traceMemoryInputPresent" "debugAiMetrics must expose trace-memory input presence"
Require-Contains $probe "traceMemoryFailureClass" "debugAiMetrics must expose trace-memory failure class"
Require-Contains $probe "trace.memory.fingerprint" "debugAiMetrics must inspect trace-memory plan usage"
Require-Contains $probe "trace_memory" "debugAiMetrics must detect trace-memory failure classes"
Require-Contains $probe "inspect_trace_memory_trace" "trace-memory next action must point at trace diagnostics"
Require-Contains $probe "SafeRedactor.hashValue" "trace-memory trace ids must be hash-only"
Require-Contains $probe "traceMemory.delta.changed" "heartbeat must read the checksum delta changed boolean"
Require-Contains $probe "traceMemory.virtualCheckpoint.latestKey" "heartbeat must read virtual checkpoint latest key"
Require-Contains $probe "traceMemory.virtualCheckpoint.latestStage" "heartbeat must read virtual checkpoint latest stage"
Require-Contains $probe "traceMemory.virtualCheckpoint.latestPhase" "heartbeat must read virtual checkpoint latest phase"
Require-Contains $probe "latestCheckpointHistorySize" "heartbeat must expose virtual checkpoint history size"
Require-Contains $probe '"latestVirtualCheckpointKey"' "heartbeat must expose virtual checkpoint key"
Require-Contains $probe '"latestVirtualCheckpointStage"' "heartbeat must expose virtual checkpoint stage"
Require-Contains $probe '"latestVirtualCheckpointPhase"' "heartbeat must expose virtual checkpoint phase"
Require-Contains $probe '"latestCheckpointJsonRoute"' "heartbeat must expose checkpoint json route in traceMemory block"
Require-Contains $probe '"latestRecoveryRouteDecision"' "heartbeat must expose recovery route decision in traceMemory block"
Require-Contains $probe '"latestRecoveryPolicyMaxRounds"' "heartbeat must expose recovery policy max rounds in traceMemory block"
Require-Contains $probe '"latestCfvmPatternId"' "heartbeat must expose CFVM pattern id in traceMemory block"

Require-Contains $fingerprintProbe 'PREFIX + "delta.changed"' "fingerprint probe must publish checksum delta changed boolean"
Require-Contains $fingerprintProbe "delta.addedCount() > 0" "fingerprint probe changed boolean must include added delta"
Require-Contains $fingerprintProbe "delta.removedCount() > 0" "fingerprint probe changed boolean must include removed delta"
Require-Contains $fingerprintProbe "delta.changedCount() > 0" "fingerprint probe changed boolean must include changed delta"
Require-Contains $fingerprintProbe 'PREFIX + "checkpoint.phase"' "fingerprint probe must publish the virtual checkpoint phase"
Require-Contains $fingerprintProbe "checkpointPhase(rawMemory, source)" "fingerprint probe must derive checkpoint phase from raw snapshot metadata"
Require-Contains $fingerprintProbe "agent.visible.debug.evidence" "fingerprint probe must expose allowlisted internal phases without hashing"
Require-Contains $fingerprintProbe "trace.memory.self.probe" "fingerprint probe must expose self-probe phase without hashing"

Require-Contains $snapshotStore "isTraceMemoryLabelTraceKey" "snapshot store must preserve trace-memory checkpoint labels"
Require-Contains $snapshotStore '"traceMemory.checkpoint.phase"' "snapshot store must preserve checkpoint phase as a label"
$labelBranchStart = $snapshotStore.IndexOf('if (isTraceMemoryLabelTraceKey(key))')
$labelBranchEnd = $snapshotStore.IndexOf('} else if (isTraceMemoryStateTraceKey(key))', $labelBranchStart)
if ($labelBranchStart -lt 0 -or $labelBranchEnd -le $labelBranchStart) {
    $failures.Add("snapshot store trace-memory label branch must remain explicit")
    $labelBranch = ""
} else {
    $labelBranch = $snapshotStore.Substring($labelBranchStart, $labelBranchEnd - $labelBranchStart)
}
Require-Contains $labelBranch "SafeRedactor.traceLabelOrFallback(" "snapshot store trace-memory labels must stay redacted"
Require-Contains $labelBranch '"unknown"' "snapshot store trace-memory labels must keep a readable fallback"

Require-Contains $payload '"traceMemory"' "ChatUiHeartbeatPayload must allowlist traceMemory"

Require-Contains $controller "/snapshots/latest-trace-memory/html" "diagnostics controller must expose latest trace-memory html"
Require-Contains $controller '"traceMemory.checkpoint.historySize"' "diagnostics controller must expose checkpoint history size in trace-memory HTML"
Require-Contains $controller "/memory/self-probe" "diagnostics controller must expose a trace-memory self-probe"
Require-Contains $controller "traceMemorySelfProbe" "trace-memory self-probe must have a named handler"
Require-Contains $controller '@RequestParam(value = "scenario"' "trace-memory self-probe must accept a synthetic recovery scenario"
Require-Contains $controller "synthetic_context_contamination" "trace-memory self-probe must provide a synthetic context-contamination recovery trigger"
Require-Contains $controller "synthetic_loader_starvation" "trace-memory self-probe must provide a synthetic loader-starvation recovery trigger"
Require-Contains $controller "synthetic_after_filter_starvation" "trace-memory self-probe must provide a synthetic after-filter starvation recovery trigger"
Require-Contains $controller "synthetic_dropped_breadcrumb" "trace-memory self-probe must provide a synthetic dropped-breadcrumb recovery trigger"
Require-Contains $controller "synthetic_silent_failure" "trace-memory self-probe must provide a synthetic silent-failure recovery trigger"
Require-Contains $controller "history_context_contamination" "synthetic recovery probe must trigger the trace-memory contamination detector"
Require-Contains $controller "all_lines_dropped" "synthetic recovery probe must trigger loader-starvation via compressor reason"
Require-Contains $controller "context.candidates.afterFilter" "synthetic recovery probe must trigger after-filter starvation via count-only trace keys"
Require-Contains $controller "trace.snapshot.capture.skipped" "synthetic recovery probe must trigger dropped-breadcrumb via trace skip marker"
Require-Contains $controller "silent.failure" "synthetic recovery probe must trigger silent-failure via trace marker"
Require-Contains $controller "restoreSyntheticTraceValues" "synthetic recovery probe must restore temporary trace keys after capture"
Require-Contains $controller '"scenario"' "trace-memory self-probe response must expose only a normalized scenario label"
Require-Contains $controller '"quarantine"' "trace-memory self-probe response must expose recovery quarantine state"
Require-Contains $controller '"risk"' "trace-memory self-probe response must expose error-break risk"
Require-Contains $controller "TraceMemoryFingerprintProbe" "trace-memory self-probe must use the fingerprint probe"
Require-Contains $controller "captureCurrent(" "trace-memory self-probe must capture a TraceSnapshotStore checkpoint"
Require-Contains $controller "hasTraceMemoryTrace" "diagnostics controller must detect trace-memory snapshots"
Require-Contains $controller "traceMemoryFallbackMeta" "trace-memory html fallback must stay metadata-only"
Require-Contains $controller "traceMemory.trigger.reason" "trace-memory metadata must include reason"
Require-Contains $controller "traceMemory.recovery.action" "trace-memory metadata must include recovery action"
Require-Contains $controller "traceMemory.errorBreak.risk" "trace-memory metadata must include error-break risk"
Require-Contains $controller '"traceMemory.delta.changed"' "trace-memory metadata must include checksum delta changed boolean"
Require-Contains $controller "traceMemory.rawSnapshot.supabaseShadowCount" "trace-memory metadata must include count-only Supabase shadow"

Require-Contains $builder '"traceMemory.delta.changed"' "agent-visible debug evidence must include checksum delta changed boolean"
Require-Contains $builder "trace.memory.changed" "agent-visible heartbeat text must expose checksum delta changed boolean"
Require-Contains $builder '"phase"' "fallback trace-memory checkpoint must include a virtual checkpoint phase"

Require-Contains $chatHtml 'data-debug-heartbeat-field="traceMemory"' "chat UI must render a visible Trace Memory heartbeat card"
Require-Contains $chatHtml "<span>Trace Memory</span>" "chat UI Trace Memory card must have a visible label"
Require-Contains $chatCss '.debug-heartbeat-card[data-debug-heartbeat-field="traceMemory"]' "chat CSS compact heartbeat allowlist must display Trace Memory"
Require-Contains $chatJs "const traceMemory = data.traceMemory || {};" "chat UI must read redacted traceMemory heartbeat data"
Require-Contains $chatJs '["supabase", "browser", "computer", "harmony", "traceMemory"].includes(name)' "chat UI compact detail allowlist must include Trace Memory"
Require-Contains $chatJs "setDebugHeartbeatField('traceMemory'" "chat UI must update the Trace Memory heartbeat card"
Require-Contains $chatJs "traceMemory.latestTraceRoute" "chat UI must expose only the fixed trace-memory route"
Require-Contains $chatJs "traceMemory.latestCheckpointJsonRoute" "chat UI must expose the fixed trace-memory checkpoint json route"
Require-Contains $chatJs "traceMemory.latestRecoveryRouteDecision" "chat UI must expose trace-memory recovery route decision"
Require-Contains $chatJs "traceMemory.latestCfvmPatternId" "chat UI must expose trace-memory CFVM pattern id"
Require-Contains $chatJs "traceMemory.latestVirtualCheckpointStage" "chat UI Trace Memory card must prefer virtual checkpoint stage"
Require-Contains $chatJs "traceMemory.latestVirtualCheckpointPhase" "chat UI Trace Memory card must prefer virtual checkpoint phase"
Require-Contains $chatJs "traceMemory.latestVirtualCheckpointKey" "chat UI Trace Memory card must show virtual checkpoint key"
Require-Contains $chatJs "vkey:`${traceMemoryVirtualKey" "chat UI Trace Memory card must render virtual checkpoint key"
Require-Contains $chatJs "checkpoints:`${traceMemory.latestCheckpointHistorySize" "chat UI Trace Memory card must show virtual checkpoint history size"
Require-Contains $chatJs "changed:`${traceMemory.latestFingerprintChanged" "chat UI Trace Memory card must show checksum delta state"
Require-Contains $chatJs "recovery:`${traceMemory.latestRecoveryAction" "chat UI Trace Memory card must show recovery action when recovery mode is active"
Require-Contains $chatJs "decision:`${traceMemoryRecoveryDecision" "chat UI Trace Memory card must show recovery route decision"
Require-Contains $chatJs "cfvm:`${traceMemoryCfvmDetail" "chat UI Trace Memory card must show CFVM offer detail"
Require-Contains $chatJs "risk:`${traceMemoryRisk" "chat UI Trace Memory card must show trace-memory error-break risk"
Require-Contains $chatJs "quarantine:`${traceMemoryQuarantine" "chat UI Trace Memory card must show trace-memory quarantine state"

Require-NotContains $probe '"latestTraceId"' "heartbeat must not expose raw latest trace id"
Require-NotContains $probe '"memoryCtx"' "heartbeat must not expose raw memory context"
Require-NotContains $probe '"rawSnapshot.raw"' "heartbeat must not expose raw trace-memory snapshot"
Require-NotContains $controller '"memoryCtx"' "controller must not expose raw memory context"
Require-NotContains $controller '"rawSnapshot.raw"' "controller must not expose raw trace-memory snapshot"
Require-NotContains $chatJs "traceMemory.memoryCtx" "chat UI must not read raw memory context"
Require-NotContains $chatJs "traceMemory.rawSnapshot" "chat UI must not read raw snapshots"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][chat-ui-heartbeat][trace-memory] FAIL"
    foreach ($failure in $failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "[AWX][chat-ui-heartbeat][trace-memory] PASS"
