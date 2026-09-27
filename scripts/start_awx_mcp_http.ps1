[CmdletBinding()]
param(
    [ValidateSet('Status','Configure','Start','Run','InstallStartup')]
    [string]$Action = 'Status',
    [string]$StateDirectory = (Join-Path $env:LOCALAPPDATA 'AwxMcpHttp'),
    [string]$Python = 'C:\Users\nninn\AppData\Local\hermes\hermes-agent\venv\Scripts\python.exe'
)
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$stateRoot = [IO.Path]::GetFullPath($StateDirectory)
$credentialPath = Join-Path $stateRoot 'owner-key.xml'
$serverPath = Join-Path $PSScriptRoot 'awx_mcp_http_server.py'
$listener = @(Get-NetTCPConnection -LocalPort 80 -State Listen -ErrorAction SilentlyContinue)
if ($Action -eq 'Status') {
    [ordered]@{ ownerKeyStored = (Test-Path -LiteralPath $credentialPath); port80Listeners = $listener.Count;
        oauthStateStored = (Test-Path -LiteralPath (Join-Path $stateRoot 'oauth-state.bin'));
        startupInstalled = (Test-Path -LiteralPath (Join-Path ([Environment]::GetFolderPath('Startup')) 'AWX MCP HTTP.lnk')) } | ConvertTo-Json
    exit 0
}
for ($checked = [IO.DirectoryInfo]::new($stateRoot); $null -ne $checked; $checked = $checked.Parent) {
    if ($checked.Exists -and ($checked.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'state_path_reparse_rejected' }
}
if ($Action -eq 'Configure') {
    if (Test-Path -LiteralPath $credentialPath) { Write-Output 'existing_owner_key_preserved'; exit 0 }
    New-Item -ItemType Directory -Path $stateRoot -Force | Out-Null
    $ownerSecret = Read-Host 'Existing AWX owner key (32-256 characters; never enter your ChatGPT password)' -AsSecureString
    $ownerCredential = [PSCredential]::new('awx-owner', $ownerSecret)
    $ownerValue = $ownerCredential.GetNetworkCredential().Password
    try {
        if ($ownerValue.Length -lt 32 -or $ownerValue.Length -gt 256 -or $ownerValue -match '[^\x21-\x7e]') { throw 'owner_key_invalid' }
        $ownerCredential | Export-Clixml -LiteralPath $credentialPath -Encoding UTF8
    } finally { $ownerValue = $null; $ownerSecret.Dispose() }
    Write-Output 'owner_key_saved_with_windows_current_user_dpapi'
    exit 0
}
if (-not (Test-Path -LiteralPath $credentialPath)) { throw 'owner_key_required_run_Configure_interactively' }
if (-not (Test-Path -LiteralPath $Python -PathType Leaf)) { throw 'python_unavailable' }
$powerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$arguments = '-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File "{0}" -Action Run -StateDirectory "{1}" -Python "{2}"' -f $PSCommandPath, $stateRoot, $Python
if ($Action -eq 'InstallStartup') {
    $startupPath = Join-Path ([Environment]::GetFolderPath('Startup')) 'AWX MCP HTTP.lnk'
    $shell = New-Object -ComObject WScript.Shell
    try {
        if (Test-Path -LiteralPath $startupPath) {
            $existing = $shell.CreateShortcut($startupPath)
            if ($existing.TargetPath -ne $powerShell -or $existing.Arguments -ne $arguments) { throw 'startup_owner_conflict' }
            Write-Output 'existing_startup_preserved'
        } else {
            $shortcut = $shell.CreateShortcut($startupPath)
            $shortcut.TargetPath = $powerShell
            $shortcut.Arguments = $arguments
            $shortcut.WorkingDirectory = $taskRoot
            $shortcut.WindowStyle = 7
            $shortcut.Save()
            Write-Output 'same_user_logon_startup_installed'
        }
    } finally { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($shell) }
    exit 0
}
if ($Action -eq 'Start') {
    if ($listener.Count) { Write-Output 'port80_already_owned_no_process_started'; exit 0 }
    $started = Start-Process -FilePath $powerShell -ArgumentList $arguments -WorkingDirectory $taskRoot -WindowStyle Hidden -PassThru
    [ordered]@{status='launched_verification_required';launcherPid=$started.Id} | ConvertTo-Json
    exit 0
}
$mutex = [Threading.Mutex]::new($false, 'Local\AwxMcpHttp-Port80')
$acquired = $false
try {
    $acquired = $mutex.WaitOne(0)
    if (-not $acquired -or $listener.Count) { throw 'port80_or_launcher_already_owned' }
    $ownerCredential = Import-Clixml -LiteralPath $credentialPath
    $env:AWX_MCP_OWNER_KEY = $ownerCredential.GetNetworkCredential().Password
    $env:AWX_MCP_PUBLIC_URL = 'https://abandonwareai.kro.kr'
    $env:AWX_MCP_OAUTH_STATE_PATH = Join-Path $stateRoot 'oauth-state.bin'
    # Preserve any explicit API key, tool allowlist and exact callback configuration.
    & $Python -B -X utf8 $serverPath --port 80
    if ($LASTEXITCODE -ne 0) { throw 'awx_http_process_failed' }
} finally {
    Remove-Item Env:AWX_MCP_OWNER_KEY -ErrorAction SilentlyContinue
    if ($acquired) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
