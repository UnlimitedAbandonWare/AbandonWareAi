$ErrorActionPreference = "Stop"

$storePath = "main\java\com\example\lms\trace\TraceSnapshotStore.java"
$testPath = "src\test\java\com\example\lms\trace\TraceSnapshotRedactionTest.java"

$store = Get-Content $storePath -Raw
$test = Get-Content $testPath -Raw
$failures = [System.Collections.Generic.List[string]]::new()

if ($store -notmatch 'buildHarmonyMetadataHtml') {
    $failures.Add("TraceSnapshotStore must render metadata-only harmony HTML")
}
if ($store -notmatch 'debug\.ai\.metrics\.nextAction') {
    $failures.Add("harmony snapshot HTML must include Debug AI nextAction")
}
if ($store -notmatch 'debug\.ai\.metrics\.nextReason') {
    $failures.Add("harmony snapshot HTML must include Debug AI nextReason")
}
if ($store -notmatch 'Next debug action') {
    $failures.Add("harmony snapshot HTML must label the next debug action")
}
if ($store -notmatch 'Next debug reason') {
    $failures.Add("harmony snapshot HTML must label the next debug reason")
}
if ($store -notmatch 'SafeRedactor\.traceLabelOrFallback') {
    $failures.Add("harmony snapshot values must be trace-label redacted")
}
if ($test -notmatch 'continue_observing_chat_harmony') {
    $failures.Add("redaction test must assert the safe next debug action is visible")
}
if ($test -notmatch 'debug\.ai\.metrics\.nextReason') {
    $failures.Add("redaction test must include next reason input")
}
if ($store -match 'rawAnswer|rawUserQuery|rawPrompt|answerText') {
    $failures.Add("TraceSnapshotStore must not add raw answer/query/prompt fields to harmony metadata")
}

if ($failures.Count -gt 0) {
    Write-Host "[AWX][trace-snapshot][harmony-debug-action] FAIL"
    foreach ($failure in $failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "[AWX][trace-snapshot][harmony-debug-action] PASS"
