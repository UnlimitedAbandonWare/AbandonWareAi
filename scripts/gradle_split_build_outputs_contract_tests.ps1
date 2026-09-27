$ErrorActionPreference = "Stop"

$buildFile = "build.gradle.kts"
$text = Get-Content -LiteralPath $buildFile -Raw
$failures = New-Object System.Collections.Generic.List[string]

function Require-Contains([string]$Needle, [string]$Message) {
    if (-not $text.Contains($Needle)) {
        $failures.Add($Message)
    }
}

Require-Contains "AWX_SPLIT_BUILD_OUTPUTS" "build.gradle.kts must read AWX_SPLIT_BUILD_OUTPUTS"
Require-Contains "AWX_BUILD_HOST_ID" "build.gradle.kts must read AWX_BUILD_HOST_ID"
Require-Contains "awx.splitBuildOutputs" "build.gradle.kts must support awx.splitBuildOutputs Gradle property"
Require-Contains "awx.buildHostId" "build.gradle.kts must support awx.buildHostId Gradle property"
Require-Contains "toAwxBuildHostId" "build.gradle.kts must sanitize host ids before using them in paths"
Require-Contains "layout.buildDirectory.set" "build.gradle.kts must redirect buildDirectory for split outputs"
Require-Contains "subprojects {" "build.gradle.kts must keep subproject outputs host-scoped too"

if ($failures.Count -gt 0) {
    Write-Host "[AWX][gradle][split-output] FAIL"
    foreach ($failure in $failures) {
        Write-Host " - $failure"
    }
    exit 1
}

Write-Host "[AWX][gradle][split-output] PASS"
