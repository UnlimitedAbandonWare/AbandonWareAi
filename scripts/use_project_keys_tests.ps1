$ErrorActionPreference = 'Stop'
$loader = Join-Path $PSScriptRoot 'use_project_keys.ps1'
$fixture = Join-Path ([IO.Path]::GetTempPath()) ('awx-keys-' + [guid]::NewGuid().ToString('N'))
$names = @('AWX_TEST_SETTING', 'AWX_PROJECT_KEYS_SOURCE_ROOT', 'CONVERSATE_ASR_CLOUD_VERIFICATION_USD', 'openssl', 'opnessl')
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    New-Item -ItemType Directory -Path (Join-Path $fixture 'config') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $fixture '.secrets') -Force | Out-Null
    '{"schemaVersion":"awx.project-resources.v1","providers":{"fixture":["AWX_TEST_SETTING"]},"runtimeEnvironmentNames":["CONVERSATE_ASR_CLOUD_VERIFICATION_USD"]}' |
        Set-Content (Join-Path $fixture 'config/project-resources.json') -Encoding UTF8
    $store = Join-Path $fixture '.secrets/providers.json'
    '{"version":1,"values":{"AWX_TEST_SETTING":{"value":"fixture-current"}}}' | Set-Content $store -Encoding UTF8
    $env:AWX_TEST_SETTING = 'fixture-stale'
    $env:openssl = 'fixture-protected-a'
    $env:opnessl = 'fixture-protected-b'
    $manual = & $loader -Root $fixture
    if ($env:AWX_TEST_SETTING -ne 'fixture-current' -or $env:AWX_PROJECT_KEYS_SOURCE_ROOT -ne $fixture) { throw 'manual-load-failed' }
    '{"version":1,"values":{"AWX_TEST_SETTING":{"value":"fixture-rotated"}}}' | Set-Content $store -Encoding UTF8
    $runtime = & $loader -Root $fixture -Runtime
    if ($env:AWX_TEST_SETTING -ne 'fixture-rotated' -or $env:AWX_PROJECT_KEYS_SOURCE_ROOT) { throw 'runtime-refresh-failed' }
    $currentBudget = [Environment]::GetEnvironmentVariable('CONVERSATE_ASR_CLOUD_VERIFICATION_USD', 'User')
    if ($null -ne $currentBudget) {
        $env:CONVERSATE_ASR_CLOUD_VERIFICATION_USD = 'fixture-stale'
        $null = & $loader -Root $fixture -Runtime
        if ($env:CONVERSATE_ASR_CLOUD_VERIFICATION_USD -cne $currentBudget) { throw 'runtime-budget-refresh-failed' }
    }
    if ($env:openssl -ne 'fixture-protected-a' -or $env:opnessl -ne 'fixture-protected-b') { throw 'protected-setting-changed' }
    $output = @($manual,$runtime) | ConvertTo-Json -Depth 4
    if ($output -match 'fixture-current|fixture-rotated|fixture-protected') { throw 'secret-output' }
    Remove-Item -LiteralPath $store
    $absent = & $loader -Root $fixture -Runtime
    if ($absent.status -ne 'loaded' -or $env:AWX_TEST_SETTING -ne 'fixture-rotated') { throw 'optional-store-required' }
    Write-Output 'PASS: manual selection, runtime rotation, current Windows budget, optional store, protected settings, redacted receipts'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf) -match '^awx-keys-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
