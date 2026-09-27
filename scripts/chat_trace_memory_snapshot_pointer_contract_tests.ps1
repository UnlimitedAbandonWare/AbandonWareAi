$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$path = Join-Path $root "main\java\com\example\lms\api\ChatTraceSnapshotPointerPersister.java"
$controllerPath = Join-Path $root "main\java\com\example\lms\api\TraceSnapshotsDiagnosticsController.java"
$snapshotStorePath = Join-Path $root "main\java\com\example\lms\trace\TraceSnapshotStore.java"

if (-not (Test-Path $path)) {
    throw "[AWX][chat-trace-memory-pointer] missing ChatTraceSnapshotPointerPersister.java"
}
if (-not (Test-Path $controllerPath)) {
    throw "[AWX][chat-trace-memory-pointer] missing TraceSnapshotsDiagnosticsController.java"
}
if (-not (Test-Path $snapshotStorePath)) {
    throw "[AWX][chat-trace-memory-pointer] missing TraceSnapshotStore.java"
}

$source = Get-Content -LiteralPath $path -Raw
$controller = Get-Content -LiteralPath $controllerPath -Raw
$snapshotStore = Get-Content -LiteralPath $snapshotStorePath -Raw
$failures = New-Object System.Collections.Generic.List[string]

function Require-Contains([string]$Text, [string]$Needle, [string]$Label) {
    if (-not $Text.Contains($Needle)) {
        $script:failures.Add("missing:$Label")
    }
}

function Require-NotContains([string]$Text, [string]$Needle, [string]$Label) {
    if ($Text.Contains($Needle)) {
        $script:failures.Add("forbidden:$Label")
    }
}

Require-Contains $source "boolean metadataOnlyTraceMemory = isTraceMemorySnapshot(traceMeta)" "trace-memory-metadata-flag"
Require-Contains $source "metadataOnlyTraceMemoryTraceHtml(traceMeta)" "trace-memory-metadata-html"
Require-Contains $source 'snapMeta.putIfAbsent("ui.traceHtml.synthetic", true);' "synthetic-html-meta"
Require-Contains $source 'snapMeta.putIfAbsent("ui.traceHtml.kind", metadataOnlyTraceMemory ? "traceMemoryMetadataOnly"' "trace-memory-kind"
Require-Contains $source 'traceMeta.get("traceMemory.checkpoint.stage")' "checkpoint-stage-rendered"
Require-Contains $source 'traceMeta.get("traceMemory.checkpoint.phase")' "checkpoint-phase-rendered"
Require-Contains $source 'traceMeta.get("traceMemory.checkpoint.historySize")' "checkpoint-history-rendered"
Require-Contains $source 'traceMeta.get("traceMemory.recovery.route")' "recovery-route-rendered"
Require-Contains $source 'traceMeta.get("traceMemory.suspectPayload.isolated")' "suspect-isolated-rendered"
Require-Contains $source 'traceMeta.get("traceMemory.rawSnapshot.supabaseShadowCount")' "supabase-shadow-count-rendered"

Require-NotContains $source 'traceMeta.get("traceMemory.rawSnapshot.raw")' "raw-snapshot-render"
Require-NotContains $source 'traceMeta.get("traceMemory.memoryCtx")' "raw-memory-context-render"
Require-Contains $controller 'return renderTraceMemorySnapshotHtml(snapshot.get());' "latest-trace-memory-renders-redacted-metadata"
$traceMemoryRendererStart = $controller.IndexOf('private static ResponseEntity<String> renderTraceMemorySnapshotHtml')
$traceMemoryRendererEnd = $controller.IndexOf('private static ResponseEntity<String> renderHarmonySnapshotHtml', $traceMemoryRendererStart)
if ($traceMemoryRendererStart -lt 0 -or $traceMemoryRendererEnd -le $traceMemoryRendererStart) {
    $failures.Add("missing:trace-memory-renderer-boundary")
    $traceMemoryRenderer = ""
} else {
    $traceMemoryRenderer = $controller.Substring(
        $traceMemoryRendererStart,
        $traceMemoryRendererEnd - $traceMemoryRendererStart)
}
Require-NotContains $traceMemoryRenderer 's.html()' "latest-trace-memory-stored-html-precedence"
Require-Contains $traceMemoryRenderer 'simpleHtml("Trace memory snapshot", traceMemoryFallbackMeta(s))' "latest-trace-memory-regenerates-html"
Require-Contains $traceMemoryRenderer 'return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);' "latest-trace-memory-returns-regenerated-html"
Require-Contains $controller '@GetMapping(value = "/snapshots/latest-trace-memory/checkpoints", produces = MediaType.APPLICATION_JSON_VALUE)' "latest-trace-memory-checkpoint-json-route"
Require-Contains $controller 'latestTraceMemoryCheckpoints()' "latest-trace-memory-checkpoint-json-handler"
Require-Contains $controller 'checkpointHistoryPayload(snapshot.get())' "latest-trace-memory-checkpoint-json-payload"
Require-Contains $controller '"traceMemory.checkpoint.history"' "checkpoint-history-source"
Require-Contains $controller '"checkpointHistorySize"' "checkpoint-history-size-json"
Require-Contains $controller '"checkpointHistory"' "checkpoint-history-json"
Require-Contains $controller '"latestTraceHtml"' "checkpoint-json-html-link"
Require-Contains $controller '"routeDecision"' "checkpoint-json-recovery-route-decision"
Require-Contains $controller '"policyMaxRounds"' "checkpoint-json-recovery-policy-max-rounds"
Require-Contains $controller '"policyMinCitations"' "checkpoint-json-recovery-policy-min-citations"
Require-Contains $controller '"cfvm"' "checkpoint-json-cfvm-block"
Require-Contains $controller '"patternId"' "checkpoint-json-cfvm-pattern-id"
Require-NotContains $controller '"traceMemory.rawSnapshot.raw"' "checkpoint-json-raw-snapshot-render"
Require-NotContains $controller '"traceMemory.memoryCtx"' "checkpoint-json-memory-context-render"
Require-Contains $snapshotStore "boolean generatedTraceMemoryMetadataHtml = isGeneratedTraceMemoryMetadataHtml(trimmedOverride, rawTrace, htmlOverride);" "snapshot-store-detects-trace-memory-generated-html"
Require-Contains $snapshotStore "? buildTraceMemoryMetadataHtml(rawTrace)" "snapshot-store-rebuilds-trace-memory-html"
Require-Contains $snapshotStore '"traceMemoryMetadataOnly".equals(firstString(rawTrace.get("ui.traceHtml.kind")))' "snapshot-store-trace-memory-kind-gate"
Require-Contains $snapshotStore 'trimmedOverride.startsWith("<section data-trace=\"trace-memory\" data-kind=\"metadata-only\">")' "snapshot-store-trace-memory-section-gate"
Require-Contains $snapshotStore 'hasPrefix(rawTrace, "traceMemory.")' "snapshot-store-trace-memory-prefix-gate"
Require-Contains $snapshotStore 'traceMemoryItem(sb, "Checkpoint history", traceMemoryMetaValue(trace, "traceMemory.checkpoint.historySize"))' "snapshot-store-checkpoint-history-render"
Require-NotContains $snapshotStore 'traceMemoryMetaValue(trace, "traceMemory.rawSnapshot.raw")' "snapshot-store-raw-snapshot-render"
Require-NotContains $snapshotStore 'traceMemoryMetaValue(trace, "traceMemory.memoryCtx")' "snapshot-store-memory-context-render"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][chat-trace-memory-pointer] FAIL"
    foreach ($failure in $failures) {
        Write-Host "[AWX][chat-trace-memory-pointer] $failure"
    }
    exit 1
}

Write-Host "[AWX][chat-trace-memory-pointer] PASS"
