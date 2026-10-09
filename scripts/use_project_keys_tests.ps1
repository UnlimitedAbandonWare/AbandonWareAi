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
    '{"schemaVersion":"awx.project-resources.v1","providers":{"fixture":["AWX_TEST_SETTING"]},"runtimeEnvironmentNames":["CONVERSATE_ASR_CLOUD_VERIFICATION_USD","NAVER_SEARCH_PROVIDER","NAVER_APIHUB_BASE_URL","NAVER_SEARCH_API_BASE_URL","NAVER_APIHUB_APP_NAME"]}' |
        Set-Content (Join-Path $fixture 'config/project-resources.json') -Encoding UTF8
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

    # Staged reason codes: broken store fixtures must fail with a safe
    # project-settings-load-failed:stage/source/kind code and zero secret bytes.
    $badRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-keys-bad-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path (Join-Path $badRoot 'config') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $badRoot '.secrets') -Force | Out-Null
    $badCatalog = Join-Path $badRoot 'config/project-resources.json'
    $badStore = Join-Path $badRoot '.secrets/providers.json'
    '{"schemaVersion":"awx.project-resources.v1","providers":{"fixture":["AWX_TEST_SETTING"]},"runtimeEnvironmentNames":[]}' |
        Set-Content $badCatalog -Encoding UTF8
    $reasons = @{}
    # invalid JSON
    '{"version":1,"values":{"AWX_TEST_SETTING":{"value":"fixture-broken-secret"}}' | Set-Content $badStore -Encoding UTF8
    try { & $loader -Root $badRoot } catch { $reasons['invalidJson'] = $_.Exception.Message }
    # empty file
    '' | Set-Content $badStore -Encoding UTF8 -NoNewline
    try { & $loader -Root $badRoot } catch { $reasons['emptyFile'] = $_.Exception.Message }
    # exclusive-locked file (sharing violation -> bounded retry -> Locked)
    '{"version":1,"values":{"AWX_TEST_SETTING":{"value":"fixture-broken-secret"}}}' | Set-Content $badStore -Encoding UTF8
    $lock = [IO.File]::Open($badStore, 'Open', 'ReadWrite', 'None')
    try { & $loader -Root $badRoot } catch { $reasons['locked'] = $_.Exception.Message }
    $lock.Dispose()
    # validate-stage schema mismatch in the catalog
    '{"schemaVersion":"awx.project-resources.v0","providers":{"fixture":["AWX_TEST_SETTING"]},"runtimeEnvironmentNames":[]}' |
        Set-Content $badCatalog -Encoding UTF8
    try { & $loader -Root $badRoot } catch { $reasons['badSchema'] = $_.Exception.Message }

    $shape = '^project-settings-load-failed:stage=(read|parse|validate|apply);source=[A-Za-z0-9._-]+;kind=(IOException|JsonParse|MissingField|Locked)$'
    if ($reasons['invalidJson'] -notmatch 'stage=parse;source=providers\.json;kind=JsonParse') { throw 'reason-invalid-json' }
    if ($reasons['emptyFile'] -notmatch 'stage=validate;source=providers\.json;kind=MissingField') { throw 'reason-empty-file' }
    if ($reasons['locked'] -notmatch 'stage=read;source=providers\.json;kind=Locked') { throw 'reason-locked' }
    if ($reasons['badSchema'] -notmatch 'stage=validate;source=project-resources\.json;kind=MissingField') { throw 'reason-schema' }
    foreach ($r in $reasons.Values) { if ($r -notmatch $shape -or $r -match 'fixture-broken-secret') { throw 'reason-shape-or-secret-leak' } }
    Write-Output 'PASS: manual selection, runtime rotation, current Windows budget, optional store, protected settings, redacted receipts, staged reason codes'
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    $resolved = [IO.Path]::GetFullPath($fixture)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if ($resolved.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase) -and (Split-Path $resolved -Leaf) -match '^awx-keys-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
    if ($badRoot -and (Split-Path $badRoot -Leaf) -match '^awx-keys-bad-[0-9a-f]{32}$') {
        Remove-Item -LiteralPath $badRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
