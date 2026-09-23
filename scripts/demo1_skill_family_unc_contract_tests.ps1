param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).ProviderPath
)

$ErrorActionPreference = "Stop"
$validator = Join-Path $Root ".agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1"
$previousPreference = $ErrorActionPreference
$ErrorActionPreference = "Continue"
try {
    $output = @(& powershell -NoProfile -ExecutionPolicy Bypass -File $validator `
        -Root $Root -DiscoverPrefix demo1- -SkipQuickValidate -SummaryJson 2>&1)
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
}
$text = $output -join "`n"
$json = $null
try { $json = $text | ConvertFrom-Json } catch { }

if ($null -eq $json) {
    Write-Host "[FAIL] validator accepts an UNC MacSrc root exit=$exitCode"
    exit 1
}
if ($json.reportFormat -ne "summary") {
    Write-Host "[FAIL] validator emits summary JSON reportFormat=$($json.reportFormat)"
    exit 1
}
Write-Host "[PASS] validator accepts an UNC MacSrc root"
Write-Host "[PASS] validator emits summary JSON"
