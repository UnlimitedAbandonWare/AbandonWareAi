$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'start_rag_stack.ps1')
$taskPrior = $env:AWX_OPTIONAL_HTTPS_ENABLED
$taskUserBefore = [Environment]::GetEnvironmentVariable('AWX_OPTIONAL_HTTPS_ENABLED', 'User')
try {
    $env:AWX_OPTIONAL_HTTPS_ENABLED = 'false'
    if (Test-RagHttpsOptIn) { throw 'FAIL: explicit process false must suppress user opt-in' }
    $env:AWX_OPTIONAL_HTTPS_ENABLED = 'true'
    if (-not (Test-RagHttpsOptIn)) { throw 'FAIL: explicit process true must win' }
    Remove-Item Env:AWX_OPTIONAL_HTTPS_ENABLED
    $taskExpected = $null -ne $taskUserBefore -and $taskUserBefore.Trim().ToLowerInvariant() -in @('true','1','yes','y','on')
    if ((Test-RagHttpsOptIn) -ne $taskExpected) { throw 'FAIL: absent process value must inherit user value' }
    if ([Environment]::GetEnvironmentVariable('AWX_OPTIONAL_HTTPS_ENABLED', 'User') -cne $taskUserBefore) {
        throw 'FAIL: user environment changed'
    }
    Write-Output 'PASS: HTTPS opt-in focused assertions=4; network calls=0; user env mutations=0'
} finally {
    if ($null -eq $taskPrior) { Remove-Item Env:AWX_OPTIONAL_HTTPS_ENABLED -ErrorAction SilentlyContinue }
    else { $env:AWX_OPTIONAL_HTTPS_ENABLED = $taskPrior }
}
