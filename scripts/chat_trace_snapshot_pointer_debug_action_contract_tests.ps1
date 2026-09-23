$ErrorActionPreference = "Stop"

$sourcePath = "main\java\com\example\lms\api\ChatTraceSnapshotPointerPersister.java"
$testPath = "src\test\java\com\example\lms\api\ChatTraceSnapshotPointerPersisterTest.java"

$source = Get-Content $sourcePath -Raw
$test = Get-Content $testPath -Raw
$failures = [System.Collections.Generic.List[string]]::new()

if ($source -notmatch 'metadataOnlyHarmonyTraceHtml') {
    $failures.Add("snapshot pointer must generate metadata-only harmony HTML")
}
if ($source -notmatch 'debug\.ai\.metrics\.nextAction') {
    $failures.Add("snapshot pointer HTML must include Debug AI nextAction")
}
if ($source -notmatch 'debug\.ai\.metrics\.nextReason') {
    $failures.Add("snapshot pointer HTML must include Debug AI nextReason")
}
if ($source -notmatch 'Next debug action') {
    $failures.Add("snapshot pointer HTML must label the next debug action")
}
if ($source -notmatch 'Next debug reason') {
    $failures.Add("snapshot pointer HTML must label the next debug reason")
}
if ($source -notmatch 'SafeRedactor\.traceLabelOrFallback') {
    $failures.Add("snapshot pointer metadata must be trace-label redacted")
}
if ($test -notmatch 'continue_observing_chat_harmony') {
    $failures.Add("unit test must assert next debug action in pointer HTML")
}
if ($source -match 'rawAnswer|rawUserQuery|rawPrompt|answerText') {
    $failures.Add("snapshot pointer must not add raw answer/query/prompt fields")
}

if ($failures.Count -gt 0) {
    Write-Host "[AWX][chat-trace-pointer][debug-action] FAIL"
    foreach ($failure in $failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "[AWX][chat-trace-pointer][debug-action] PASS"
