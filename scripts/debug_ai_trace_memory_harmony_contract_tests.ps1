$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$metricsPath = Join-Path $root "main\java\com\example\lms\debug\ai\DebugAiMetricsService.java"
$builderPath = Join-Path $root "main\java\com\example\lms\service\AgentVisibleDebugEvidenceBuilder.java"

if (-not (Test-Path $metricsPath)) {
    throw "[AWX][debug-ai][trace-memory] missing DebugAiMetricsService.java"
}
if (-not (Test-Path $builderPath)) {
    throw "[AWX][debug-ai][trace-memory] missing AgentVisibleDebugEvidenceBuilder.java"
}

$metrics = Get-Content -LiteralPath $metricsPath -Raw
$builder = Get-Content -LiteralPath $builderPath -Raw
$failures = New-Object System.Collections.Generic.List[string]

function Require-Contains([string]$source, [string]$needle, [string]$label) {
    if (-not $source.Contains($needle)) {
        $script:failures.Add("missing:$label")
    }
}

function Require-NotContains([string]$source, [string]$needle, [string]$label) {
    if ($source.Contains($needle)) {
        $script:failures.Add("forbidden:$label")
    }
}

Require-Contains $metrics "traceMemorySlotFromTraceStore(nowMs).ifPresent(slots::add)" "trace-memory-slot-added-to-snapshot"
Require-Contains $metrics "traceMemorySlotFromTraceStore(long nowMs)" "trace-memory-slot-helper"
Require-Contains $metrics "traceMemoryTraceMap()" "trace-memory-latest-snapshot-fallback-helper"
Require-Contains $metrics "hasTraceMemorySignal" "trace-memory-snapshot-signal-detector"
Require-Contains $metrics '"trace-current-trace-memory"' "trace-memory-slot-id"
Require-Contains $metrics '"TRACE_MEMORY"' "trace-memory-probe-name"
Require-Contains $metrics '"memory.postprocess"' "trace-memory-layer"
Require-Contains $metrics '"trace.memory.fingerprint"' "trace-memory-plan-id"
Require-Contains $metrics "traceMemory.triggered" "trace-memory-triggered-key"
Require-Contains $metrics "traceMemory.trigger.reason" "trace-memory-reason-key"
Require-Contains $metrics "traceMemory.recovery.action" "trace-memory-recovery-action-key"
Require-Contains $metrics "traceMemory.recovery.routeDecision" "trace-memory-recovery-route-decision-key"
Require-Contains $metrics "traceMemory.recovery.quarantine" "trace-memory-quarantine-key"
Require-Contains $metrics "traceMemory.errorBreak.risk" "trace-memory-error-break-key"
Require-Contains $metrics "traceMemory.fingerprint.current" "trace-memory-fingerprint-key"
Require-Contains $metrics "traceMemory.cfvm.offered" "trace-memory-cfvm-offered-key"
Require-Contains $metrics "traceMemory.cfvm.patternId" "trace-memory-cfvm-pattern-key"
Require-Contains $metrics "traceMemory.checkpoint.phase" "trace-memory-checkpoint-phase-key"
Require-Contains $metrics "traceMemory.virtualCheckpoint.latestKey" "trace-memory-virtual-checkpoint-key"
Require-Contains $metrics "traceMemory.virtualCheckpoint.latestStage" "trace-memory-virtual-checkpoint-stage"
Require-Contains $metrics "traceMemory.virtualCheckpoint.latestPhase" "trace-memory-virtual-checkpoint-phase"
Require-Contains $metrics 'TRACE_MEMORY_CHECKPOINTS_ROUTE' "trace-memory-checkpoint-json-route-constant"
Require-Contains $metrics '"traceMemoryDiagnostics"' "trace-memory-diagnostics-compact-map"
Require-Contains $metrics '"checkpointJsonRoute"' "trace-memory-checkpoint-json-compact-field"
Require-Contains $metrics '"routeDecision"' "trace-memory-route-decision-compact-field"
Require-Contains $metrics '"cfvmOffered"' "trace-memory-cfvm-offered-compact-field"
Require-Contains $metrics '"cfvmPatternId"' "trace-memory-cfvm-pattern-compact-field"
Require-Contains $metrics "checkpointPhase" "trace-memory-checkpoint-phase-axis"
Require-Contains $metrics "virtualCheckpointKey" "trace-memory-virtual-checkpoint-key-axis"
Require-Contains $metrics "virtualCheckpointStage" "trace-memory-virtual-checkpoint-stage-axis"
Require-Contains $metrics "virtualCheckpointPhase" "trace-memory-virtual-checkpoint-phase-axis"
Require-Contains $metrics "traceMemoryVirtualPlanId" "trace-memory-virtual-plan-axis-helper"
Require-Contains $metrics "trace.memory.virtual." "trace-memory-virtual-plan-prefix"
Require-Contains $builder "checkpointHistorySize" "trace-memory-checkpoint-history-size-axis"
Require-Contains $metrics "trace_memory." "trace-memory-failure-class-prefix"
Require-Contains $metrics "traceMemorySeverity(trace)" "trace-memory-severity-classifier"
Require-Contains $metrics "case TRACE_MEMORY -> `"memory.postprocess`"" "trace-memory-layer-mapping"

Require-Contains $builder "Map<String, Object> traceMemory = traceMemoryEvidence();" "agent-visible-trace-memory-map"
Require-Contains $builder "traceAgentVisibleTraceMemory(traceMemory)" "trace-memory-agent-visible-trace"
Require-Contains $builder "traceMemoryEvidence()" "trace-memory-evidence-helper"
Require-Contains $builder "trace.memory.triggered" "trace-memory-heartbeat-triggered"
Require-Contains $builder "trace.memory.reason" "trace-memory-heartbeat-reason"
Require-Contains $builder "trace.memory.recoveryAction" "trace-memory-heartbeat-recovery-action"
Require-Contains $builder "trace.memory.routeDecision" "trace-memory-heartbeat-route-decision"
Require-Contains $builder "trace.memory.failureClass" "trace-memory-heartbeat-failure-class"
Require-Contains $builder "trace.memory.cfvmOffered" "trace-memory-heartbeat-cfvm-offered"
Require-Contains $builder "trace.memory.cfvmPatternId" "trace-memory-heartbeat-cfvm-pattern"
Require-Contains $builder "trace.memory.checkpointPhase" "trace-memory-heartbeat-checkpoint-phase"
Require-Contains $builder "trace.memory.checkpointHistorySize" "trace-memory-heartbeat-checkpoint-history-size"
Require-Contains $builder "trace.memory.checkpointJsonRoute" "trace-memory-heartbeat-checkpoint-json-route"
Require-Contains $builder "trace.memory.nextAction" "trace-memory-heartbeat-next-action"
Require-Contains $builder "prompt.agentDebugEvidence.traceMemory." "trace-memory-prompt-trace-prefix"
Require-Contains $builder "debug.ai.agentDebugEvidence.traceMemory." "trace-memory-debug-trace-prefix"

Require-NotContains $metrics "traceMemory.rawSnapshot.raw" "raw-trace-memory-snapshot"
Require-NotContains $metrics "traceMemory.memoryCtx" "raw-memory-context"
Require-NotContains $builder "trace.memory.raw" "raw-memory-heartbeat"
Require-NotContains $builder "trace.memory.memoryCtx" "raw-memory-context-heartbeat"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][debug-ai][trace-memory] FAIL"
    foreach ($failure in $failures) {
        Write-Host "[AWX][debug-ai][trace-memory] $failure"
    }
    exit 1
}

Write-Host "[AWX][debug-ai][trace-memory] PASS"
