$ErrorActionPreference = "Stop"

$ScriptPath = Join-Path $PSScriptRoot "probe_search_repeat_trace_snapshot.ps1"
$source = Get-Content -Raw -LiteralPath $ScriptPath
$failures = New-Object System.Collections.Generic.List[string]

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Needle
    )
    if (-not $Text.Contains($Needle)) {
        $script:failures.Add("$Name missing <$Needle>") | Out-Null
    }
}

function Assert-NotContains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Needle
    )
    if ($Text.Contains($Needle)) {
        $script:failures.Add("$Name still contains <$Needle>") | Out-Null
    }
}

Assert-Contains "BaseUrl can be omitted for deployment env fallback" $source '[string]$BaseUrl = ""'
Assert-Contains "APP_PUBLIC_BASE_URL fallback is supported" $source '$env:APP_PUBLIC_BASE_URL'
Assert-Contains "PUBLIC_BASE_URL fallback is supported" $source '$env:PUBLIC_BASE_URL'
Assert-Contains "localhost remains final local fallback" $source '"http://localhost:8080"'
Assert-NotContains "BaseUrl should not hard-default to localhost before env fallback" $source '[string]$BaseUrl = "http://localhost:8080"'

if ($failures.Count -gt 0) {
    foreach ($failure in $failures) {
        Write-Host "[probe-repeat-test][FAIL] $failure"
    }
    Write-Host "[probe-repeat-test][SUMMARY] failed=$($failures.Count)"
    exit 1
}

Write-Host "[probe-repeat-test][SUMMARY] failed=0"
