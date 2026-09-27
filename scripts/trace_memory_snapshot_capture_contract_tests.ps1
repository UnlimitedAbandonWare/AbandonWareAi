param()

$ErrorActionPreference = "Stop"

$traceFilterPath = "main\java\com\example\lms\web\TraceFilter.java"
$snapshotStorePath = "main\java\com\example\lms\trace\TraceSnapshotStore.java"
foreach ($path in @($traceFilterPath, $snapshotStorePath)) {
    if (-not (Test-Path $path)) {
        throw "[AWX][trace-memory-snapshot-capture] missing $path"
    }
}

$traceFilter = Get-Content $traceFilterPath -Raw
$snapshotStore = Get-Content $snapshotStorePath -Raw
$failures = New-Object System.Collections.Generic.List[string]

function Require-Contains([string]$Text, [string]$Needle, [string]$Label) {
    if (-not $Text.Contains($Needle)) {
        $script:failures.Add("missing:$Label")
    }
}

Require-Contains $traceFilter 'boolean hasTraceMemory = false;' "trace-filter-has-trace-memory-flag"
Require-Contains $traceFilter 'k.startsWith("traceMemory.")' "trace-filter-detects-trace-memory-prefix"
Require-Contains $traceFilter 'dbgSearch || hasMl || hasTraceMemory || status >= 400 || failure != null' "trace-filter-captures-trace-memory-http"
Require-Contains $snapshotStore 'boolean hasTraceMemory = hasPrefix(rawTrace, "traceMemory.");' "snapshot-store-detects-trace-memory-prefix"
Require-Contains $snapshotStore 'hasTraceMemory,' "snapshot-store-passes-trace-memory-capture-policy"
Require-Contains $snapshotStore 'if (hasTraceMemory) should = true;' "snapshot-store-captures-trace-memory-http"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][trace-memory-snapshot-capture] FAIL"
    foreach ($failure in $failures) {
        Write-Host "[AWX][trace-memory-snapshot-capture] $failure"
    }
    exit 1
}

Write-Host "[AWX][trace-memory-snapshot-capture] PASS"
