param(
    [string]$Root = (Get-Location).Path
)

$ErrorActionPreference = "Stop"

$metricsPath = Join-Path $Root "main/java/com/example/lms/debug/ai/DebugAiMetricsService.java"
if (-not (Test-Path $metricsPath)) {
    throw "[AWX][debug-ai][harmony] missing DebugAiMetricsService.java"
}

$src = Get-Content -LiteralPath $metricsPath -Raw
$failures = New-Object System.Collections.Generic.List[string]

function Require-Contains([string]$needle, [string]$label) {
    if (-not $src.Contains($needle)) {
        $failures.Add("missing:$label")
    }
}

function Require-NotContains([string]$needle, [string]$label) {
    if ($src.Contains($needle)) {
        $failures.Add("forbidden:$label")
    }
}

Require-Contains "harmonySlotFromTraceStore(nowMs).ifPresent(slots::add)" "harmony-slot-added-to-snapshot"
Require-Contains "harmonySlotFromTraceStore(long nowMs)" "harmony-slot-helper"
Require-Contains "harmonySlotFromTraceSnapshots(nowMs).ifPresent(slots::add)" "snapshot-harmony-slot-added-to-snapshot"
Require-Contains "harmonySlotFromTraceSnapshots(long nowMs)" "snapshot-harmony-slot-helper"
Require-Contains "TraceSnapshotStore" "trace-snapshot-store-import"
Require-Contains "traceSnapshotStoreProvider" "trace-snapshot-store-provider"
Require-Contains "listSummaries(20)" "latest-trace-snapshot-scan"
Require-Contains "store.get(id)" "trace-snapshot-fetch"
Require-Contains "chat.harmony.postprocess.agentVisible" "agent-visible-harmony-key"
Require-Contains "chat.harmony.postprocess.decision" "harmony-decision-key"
Require-Contains "chat.harmony.postprocess.reason" "harmony-reason-key"
Require-Contains "chat.harmony.postprocess.weightedScore" "harmony-weighted-score-key"
Require-Contains "chat.harmony.postprocess.evidenceCount" "harmony-evidence-count-key"
Require-Contains '"trace-current-chat-harmony"' "redacted-trace-slot-id"
Require-Contains '"trace-snapshot-chat-harmony"' "redacted-snapshot-slot-id"
Require-Contains '"evidence.output"' "evidence-output-layer"
Require-Contains "chat_harmony." "harmony-failure-class-prefix"
Require-Contains "harmonySeverity(trace)" "harmony-severity-classifier"
Require-Contains "SafeRedactor.traceLabelOrFallback" "safe-label-redaction"

Require-NotContains "chat.harmony.postprocess.answerText" "raw-answer-text-trace-key"
Require-NotContains "chat.harmony.postprocess.rawAnswer" "raw-answer-trace-key"
Require-NotContains "chat.harmony.postprocess.rawQuery" "raw-query-trace-key"
Require-NotContains "chat.harmony.postprocess.rawPrompt" "raw-prompt-trace-key"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][debug-ai][harmony] FAIL $($failures -join ', ')"
    exit 1
}

Write-Host "[AWX][debug-ai][harmony] PASS"
