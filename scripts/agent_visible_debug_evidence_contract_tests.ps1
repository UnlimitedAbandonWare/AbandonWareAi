$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$builderPath = Join-Path $root "main\java\com\example\lms\service\AgentVisibleDebugEvidenceBuilder.java"
$workflowPath = Join-Path $root "main\java\com\example\lms\service\ChatWorkflow.java"

$builder = Get-Content $builderPath -Raw
$workflow = Get-Content $workflowPath -Raw

$failures = New-Object System.Collections.Generic.List[string]

if ($builder -match "static List<Document> buildLocalDocs[\s\S]*?if \(!isDebugEvidenceQuery\(query\)\) \{\s*return List\.of\(\);\s*\}") {
    $failures.Add("buildLocalDocs still suppresses ordinary chat turns")
}

if ($builder -match "private static String buildEvidenceText[\s\S]*?if \(!isDebugEvidenceQuery\(query\)\) \{\s*return `"`";\s*\}") {
    $failures.Add("buildEvidenceText still returns an empty heartbeat for ordinary chat turns")
}

if ($builder -notmatch "AGENT_VISIBLE_DEBUG_HEARTBEAT") {
    $failures.Add("missing agent-visible heartbeat marker")
}

if ($builder -notmatch "debug\.ai\.agentDebugEvidence\.chatHarmony\.") {
    $failures.Add("chat harmony evidence must mirror into debug.ai agent-visible namespace")
}

if ($builder -notmatch "TraceMemoryFingerprintProbe") {
    $failures.Add("agent-visible fallback must use TraceMemoryFingerprintProbe for trace-memory checkpoints")
}

if ($builder -notmatch "SUPABASE_SCHEMA_SNAPSHOT_PATH" -or
    $builder -notmatch "schemaSnapshotAvailable" -or
    $builder -notmatch "evidenceNeededCount" -or
    $builder -notmatch "projectScopeStatus") {
    $failures.Add("Supabase shadow evidence must include the redacted schema snapshot artifact in trace-memory fingerprints")
}

if ($builder -notmatch "ensureTraceMemoryCheckpoint") {
    $failures.Add("missing trace-memory checkpoint hook on the fallback debug evidence path")
}

if ($builder -notmatch "trace\.memory\.routeDecision" -or
    $builder -notmatch 'TraceStore\.get\("traceMemory\.recovery\.routeDecision"\)') {
    $failures.Add("agent-visible trace-memory evidence must surface recovery routeDecision")
}

if ($builder -notmatch "trace\.memory\.cfvmPatternId" -or
    $builder -notmatch 'TraceStore\.get\("traceMemory\.cfvm\.patternId"\)') {
    $failures.Add("agent-visible trace-memory evidence must surface CFVM pattern id")
}

if ($builder -notmatch 'checkpoint\("raw_snapshot", "AgentVisibleDebugEvidenceBuilder", raw\)' -or
    $builder -notmatch 'checkpoint\("load", "AgentVisibleDebugEvidenceBuilder", raw\)') {
    $failures.Add("fallback debug evidence path must emit raw_snapshot and load virtual checkpoints")
}

if ($builder -notmatch "queryHash" -or $builder -notmatch "queryLength") {
    $failures.Add("fallback trace-memory checkpoint must hash/count the query instead of storing raw text")
}

if ($builder -match 'raw\.put\("query", query\)') {
    $failures.Add("fallback trace-memory checkpoint must not store raw query text")
}

if ($builder -notmatch "prompt\.agentDebugEvidence\.chatHarmony\." -and $builder -notmatch 'prefix = "prompt\.agentDebugEvidence\.chatHarmony\."') {
    $failures.Add("chat harmony degraded state must be reinjected into prompt agent-visible namespace")
}

if ($builder -notmatch "normalizeTraceKey\(entry\.getKey\(\)\)") {
    $failures.Add("chat harmony evidence must normalize dynamic keys before tracing")
}

if ($builder -notmatch "static boolean isDirectDebugAnswerQuery\(String query\)") {
    $failures.Add("missing narrow direct debug-answer predicate")
}

if ($builder -notmatch "DIRECT_DEBUG_SUBJECT_MARKERS" -or $builder -notmatch "DIRECT_DEBUG_STATUS_MARKERS") {
    $failures.Add("direct debug-answer predicate must require both subject and status/inspection intent markers")
}

if ($builder -match "Use it in the user-facing answer only when the user asks about current chatbot, browser, UI, debugging, matrix") {
    $failures.Add("agent-visible debug breadcrumb instruction is too broad and can dominate ordinary chat answers")
}

if ($builder -notmatch "explicitly asks for current debug/status/health/report") {
    $failures.Add("agent-visible debug breadcrumb instruction must keep debug evidence out of ordinary user answers")
}

if ($workflow -match "AgentVisibleDebugEvidenceBuilder\.isDebugEvidenceQuery\(userQuery\)") {
    $failures.Add("direct chat answer must not use broad debug-evidence breadcrumb predicate")
}

if ($workflow -notmatch "AgentVisibleDebugEvidenceBuilder\.isDirectDebugAnswerQuery\(userQuery\)") {
    $failures.Add("ChatWorkflow must gate direct debug answers with the narrow direct predicate")
}

if ($workflow -notmatch "AgentVisibleDebugEvidenceBuilder\.isDirectDebugAnswerQuery\(query\)") {
    $failures.Add("evidence fallback must gate agent-debug fallback docs with the narrow direct predicate")
}

if ($workflow -notmatch "chat\.agentDebugEvidence\.directAnswer") {
    $failures.Add("missing direct-answer trace key for explicit debug status questions")
}

if ($failures.Count -gt 0) {
    Write-Host "[AWX][agent-debug-contract] FAIL"
    foreach ($failure in $failures) {
        Write-Host "[AWX][agent-debug-contract] $failure"
    }
    exit 1
}

Write-Host "[AWX][agent-debug-contract] PASS"
