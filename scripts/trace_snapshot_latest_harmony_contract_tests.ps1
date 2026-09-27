$ErrorActionPreference = "Stop"

$controllerPath = "main\java\com\example\lms\api\TraceSnapshotsDiagnosticsController.java"
$controller = Get-Content $controllerPath -Raw
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

Require-Contains $controller '"/snapshots/latest-harmony/html"' "diagnostics must expose a fixed latest harmony HTML endpoint"
Require-Contains $controller "latestHarmonyHtml" "controller must implement latestHarmonyHtml"
Require-Contains $controller "listSummaries(50)" "latest harmony endpoint must inspect recent summaries without requiring a raw id from clients"
Require-Contains $controller "hasHarmonyTrace" "latest harmony endpoint must filter to harmony traces"
Require-Contains $controller "renderSnapshotHtml" "snapshot HTML rendering must be shared with id-based endpoint"
Require-Contains $controller "chat.harmony.postprocess.decision" "harmony trace detection must include decision breadcrumb"
Require-Contains $controller "chat.harmony.postprocess.agentVisible" "harmony trace detection must include agent-visible breadcrumb"
Require-NotContains $controller "latestHarmonyHtml(@PathVariable" "latest harmony endpoint must not accept raw snapshot id"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][trace-snapshot][latest-harmony] FAIL"
    foreach ($failure in $failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "[AWX][trace-snapshot][latest-harmony] PASS"
