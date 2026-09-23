$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$probePath = Join-Path $root "main/java/com/example/lms/trace/TraceMemoryFingerprintProbe.java"
$controllerPath = Join-Path $root "main/java/com/example/lms/api/TraceSnapshotsDiagnosticsController.java"
$heartbeatPath = Join-Path $root "main/java/com/example/lms/web/ChatUiCoreHeartbeatProbe.java"
$debugAiPath = Join-Path $root "main/java/com/example/lms/debug/ai/DebugAiMetricsService.java"
$builderPath = Join-Path $root "main/java/com/example/lms/service/AgentVisibleDebugEvidenceBuilder.java"
$testPath = Join-Path $root "src/test/java/com/example/lms/trace/TraceMemoryFingerprintProbeTest.java"

function Read-Text([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "[AWX][trace-memory-routing] missing-file path=$Path"
    }
    return Get-Content -Raw -LiteralPath $Path
}

function Require-Contains([string]$Text, [string]$Needle, [string]$Label) {
    if (-not $Text.Contains($Needle)) {
        throw "[AWX][trace-memory-routing] missing $Label needle=$Needle"
    }
}

function Require-NotContains([string]$Text, [string]$Needle, [string]$Label) {
    if ($Text.Contains($Needle)) {
        throw "[AWX][trace-memory-routing] forbidden $Label needle=$Needle"
    }
}

$probe = Read-Text $probePath
$controller = Read-Text $controllerPath
$heartbeat = Read-Text $heartbeatPath
$debugAi = Read-Text $debugAiPath
$builder = Read-Text $builderPath
$test = Read-Text $testPath

Require-Contains $probe 'TraceStore.put(PREFIX + "recovery.route"' "recovery-route-trace"
Require-Contains $probe 'TraceStore.put(PREFIX + "recovery.routeDecision"' "route-decision-trace"
Require-Contains $probe 'TraceStore.put(PREFIX + "suspectPayload.isolated", true)' "suspect-payload-isolation"
Require-Contains $probe 'TraceStore.put(PREFIX + "suspectPayload.fingerprint"' "suspect-payload-fingerprint"
Require-Contains $probe 'TraceStore.put(PREFIX + "suspectPayload.route"' "suspect-payload-route"
Require-Contains $probe 'TraceStore.put(PREFIX + "suspectPayload.retry"' "suspect-payload-retry"
Require-Contains $probe 'TraceStore.put(PREFIX + "suspectPayload.quarantine"' "suspect-payload-quarantine"
Require-Contains $probe 'TraceStore.put(PREFIX + "recovery.policy.maxRounds"' "recovery-policy-max-rounds"
Require-Contains $probe 'TraceStore.put(PREFIX + "recovery.policy.minCitations"' "recovery-policy-min-citations"
Require-Contains $probe 'data.put("recoveryRoute"' "debug-event-route"
Require-Contains $probe 'data.put("suspectPayloadIsolated", true)' "debug-event-suspect-isolated"
Require-Contains $probe 'data.put("routeDecision"' "debug-event-route-decision"

Require-Contains $controller '"recoveryRoute"' "self-probe-recovery-route"
Require-Contains $controller '"recoveryRouteDecision"' "self-probe-recovery-route-decision"
Require-Contains $controller '"recoveryPolicyMaxRounds"' "self-probe-recovery-policy-max-rounds"
Require-Contains $controller '"cfvmOffered"' "self-probe-cfvm-offered"
Require-Contains $controller 'TraceStore.get("traceMemory.recovery.route")' "self-probe-route-read"
Require-Contains $controller 'TraceStore.get("traceMemory.recovery.routeDecision")' "self-probe-route-decision-read"
Require-Contains $controller 'putTraceMetaDefault(kv, trace, "traceMemory.recovery.route"' "html-route-meta"
Require-Contains $controller 'putTraceMetaDefault(kv, trace, "traceMemory.recovery.routeDecision"' "html-route-decision-meta"
Require-Contains $controller 'putTraceMetaDefault(kv, trace, "traceMemory.suspectPayload.isolated"' "html-suspect-isolated-meta"
Require-Contains $controller 'putTraceMetaDefault(kv, trace, "traceMemory.suspectPayload.route"' "html-suspect-route-meta"
Require-Contains $controller '"routeDecision"' "checkpoint-json-route-decision"
Require-Contains $controller '"policyMaxRounds"' "checkpoint-json-policy-max-rounds"
Require-Contains $controller '"cfvm"' "checkpoint-json-cfvm-block"
Require-Contains $controller '"offered"' "checkpoint-json-cfvm-offered"

Require-Contains $heartbeat '"latestRecoveryRoute"' "heartbeat-recovery-route-field"
Require-Contains $heartbeat '"latestRecoveryRouteDecision"' "heartbeat-recovery-route-decision-field"
Require-Contains $heartbeat '"latestRecoveryPolicyMaxRounds"' "heartbeat-recovery-policy-max-rounds-field"
Require-Contains $heartbeat 'traceMemory.recovery.route' "heartbeat-route-trace-key"
Require-Contains $heartbeat 'traceMemory.recovery.routeDecision' "heartbeat-route-decision-trace-key"
Require-Contains $heartbeat 'traceMemory.virtualCheckpoint.latestKey' "heartbeat-virtual-checkpoint-key"
Require-Contains $heartbeat '"latestVirtualCheckpointStage"' "heartbeat-virtual-checkpoint-stage-field"
Require-Contains $heartbeat '"suspectPayloadIsolated"' "heartbeat-suspect-isolated-field"
Require-Contains $heartbeat 'traceMemory.suspectPayload.isolated' "heartbeat-suspect-isolated-key"
Require-Contains $heartbeat '"latestCfvmPatternId"' "heartbeat-cfvm-pattern-id-field"

Require-Contains $debugAi 'traceMemory.recovery.route' "debug-ai-route-key"
Require-Contains $debugAi 'traceMemory.suspectPayload.isolated' "debug-ai-suspect-isolated-key"
Require-Contains $debugAi 'traceMemory.virtualCheckpoint.latestKey' "debug-ai-virtual-checkpoint-key"
Require-Contains $debugAi 'virtualCheckpointStage' "debug-ai-virtual-checkpoint-stage-field"
Require-Contains $debugAi 'recoveryRoute' "debug-ai-route-field"

Require-Contains $builder 'trace.memory.recoveryRoute' "agent-visible-recovery-route-line"
Require-Contains $builder 'traceMemory.get("recoveryRoute")' "agent-visible-recovery-route-value"

Require-Contains $test 'traceMemory.recovery.route' "unit-test-route-assertion"
Require-Contains $test 'traceMemory.suspectPayload.isolated' "unit-test-suspect-isolated-assertion"

Require-NotContains $probe 'TraceStore.put(PREFIX + "suspectPayload.raw"' "raw-suspect-payload-trace"
Require-NotContains $controller '"memoryCtx"' "controller-raw-memory-key"
Require-NotContains $heartbeat 'rawSnapshot.raw' "heartbeat-raw-snapshot"

Write-Host "[AWX][trace-memory-routing] PASS"
