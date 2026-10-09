[CmdletBinding()]
param([string]$Root = '', [switch]$Runtime)

# Explicit manual use of the user-selected in-project settings file.
# This does not attest SMB security or alter persistent environment settings.
$updates = @{}
$previous = @{}
$appliedNames = @()
$sourceCounts = @{windowsUser=0;windowsMachine=0;projectStore=0}
$stage = 'read'
$stageSource = '-'
function Read-ProjectSettingsFile([string]$Path) {
    # Locked/transient I/O only: re-read up to 3 times at 300ms intervals.
    for ($attempt = 0; $attempt -le 3; $attempt++) {
        try { return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 -ErrorAction Stop }
        catch {
            if ($attempt -ge 3 -or $_.Exception -isnot [IO.IOException]) { throw }
            Start-Sleep -Milliseconds 300
        }
    }
}
try {
    if ([string]::IsNullOrWhiteSpace($Root)) { $Root = Split-Path -Parent $PSScriptRoot }
    $rootPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Root -ErrorAction Stop).ProviderPath)
    $storePath = Join-Path $rootPath '.secrets/providers.json'
    $catalogPath = Join-Path $rootPath 'config/project-resources.json'
    foreach ($path in @($storePath, $catalogPath)) {
        if ($Runtime -and $path -eq $storePath -and -not (Test-Path -LiteralPath $path)) { continue }
        $stageSource = Split-Path -Leaf $path
        $stage = 'read'
        $item = Get-Item -LiteralPath $path -Force -ErrorAction Stop
        $stage = 'validate'
        while ($null -ne $item) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'reparse-path' }
            $item = if ($item -is [IO.FileInfo]) { $item.Directory } else { $item.Parent }
        }
    }
    $stage = 'read'; $stageSource = 'project-resources.json'
    $catalogRaw = Read-ProjectSettingsFile $catalogPath
    $stage = 'parse'
    $catalog = $catalogRaw | ConvertFrom-Json -ErrorAction Stop
    $stage = 'read'; $stageSource = 'providers.json'
    $storeRaw = if ($Runtime -and -not (Test-Path -LiteralPath $storePath)) {
        '{"version":1,"values":{}}'
    } else { Read-ProjectSettingsFile $storePath }
    $stage = 'parse'
    $store = $storeRaw | ConvertFrom-Json -ErrorAction Stop
    $stage = 'validate'; $stageSource = 'project-resources.json'
    if ($catalog.schemaVersion -ne 'awx.project-resources.v1') { throw 'invalid-project-settings' }
    $stageSource = 'providers.json'
    if ($store.version -ne 1 -or $null -eq $store.values) { throw 'invalid-project-settings' }
    $stageSource = 'project-resources.json'
    $allowed = @($catalog.providers.PSObject.Properties | ForEach-Object { $_.Value })
    foreach ($name in $allowed) {
        if ($name -notmatch '^[A-Z][A-Z0-9_]{1,80}$' -or $name -match 'openssl|opnessl') { throw 'invalid-project-setting' }
    }
    $stageSource = 'providers.json'
    foreach ($entry in $store.values.PSObject.Properties) {
        if ($entry.Name -notmatch '^[A-Z][A-Z0-9_]{1,80}$' -or $entry.Name -match 'openssl|opnessl' -or
            $entry.Name -notin $allowed -or $entry.Value.value -isnot [string]) { throw 'invalid-project-setting' }
        $updates[$entry.Name] = $entry.Value.value
        $sourceCounts.projectStore++
        $previous[$entry.Name] = [Environment]::GetEnvironmentVariable($entry.Name, 'Process')
    }
    if ($Runtime) {
        # Normal launches refresh current Windows settings even from an old
        # terminal. Explicit manual use without -Runtime still selects the store.
        $stageSource = 'project-resources.json'
        $runtimeNames = @($catalog.runtimeEnvironmentNames)
        $publicRuntimeNames = @('NAVER_SEARCH_PROVIDER','NAVER_APIHUB_BASE_URL','NAVER_SEARCH_API_BASE_URL','NAVER_APIHUB_APP_NAME')
        foreach ($name in $runtimeNames) {
            if ($null -ne $name -and (($name -notmatch '^CONVERSATE_[A-Z0-9_]{1,69}$' -and $name -notin $publicRuntimeNames) -or $name -match 'openssl|opnessl')) { throw 'invalid-runtime-setting' }
        }
        foreach ($name in @($allowed + $runtimeNames | Where-Object { $null -ne $_ } | Select-Object -Unique)) {
            $value = [Environment]::GetEnvironmentVariable($name, 'User')
            $source = 'windowsUser'
            if ($null -eq $value) {
                $value = [Environment]::GetEnvironmentVariable($name, 'Machine')
                $source = 'windowsMachine'
            }
            if ($null -ne $value) {
                if ($updates.ContainsKey($name)) { $sourceCounts.projectStore-- }
                $updates[$name] = $value
                $sourceCounts[$source]++
                $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
            }
        }
    }
    # Validate the complete file before injecting any values.
    $stage = 'apply'; $stageSource = 'providers.json'
    foreach ($name in $updates.Keys) {
        [Environment]::SetEnvironmentVariable($name, $updates[$name], 'Process')
        $appliedNames += $name
    }
    # A child launched from this shell must preserve this explicitly loaded
    # snapshot instead of replacing it with a stale persistent User value.
    $previous['AWX_PROJECT_KEYS_SOURCE_ROOT'] = [Environment]::GetEnvironmentVariable('AWX_PROJECT_KEYS_SOURCE_ROOT', 'Process')
    $marker = if ($Runtime) { $null } else { $rootPath.TrimEnd('\') }
    [Environment]::SetEnvironmentVariable('AWX_PROJECT_KEYS_SOURCE_ROOT', $marker, 'Process')
    $appliedNames += 'AWX_PROJECT_KEYS_SOURCE_ROOT'
    [pscustomobject]@{status='loaded';loadedCount=$updates.Count;scope='current-process';secretRef='.secrets/providers.json';runtime=[bool]$Runtime;sourceCounts=$sourceCounts;rawValuesPrinted=0}
} catch {
    foreach ($name in $appliedNames) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    # Do not propagate input values or exception text to the terminal/history.
    if ($stage -eq 'parse') { $kind = 'JsonParse' }
    elseif ($stage -eq 'read') {
        $locked = $_.Exception -is [IO.IOException] -and (($_.Exception.HResult -band 0xFFFF) -in @(32, 33))
        $kind = if ($locked) { 'Locked' } else { 'IOException' }
    } else { $kind = 'MissingField' }
    throw "project-settings-load-failed:stage=$stage;source=$stageSource;kind=$kind"
} finally {
    $updates.Clear()
    $previous.Clear()
}
