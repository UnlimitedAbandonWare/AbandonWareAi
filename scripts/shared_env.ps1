[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [switch]$Apply,
    [ValidateSet('DotEnv','Shared')][string]$Source = 'DotEnv',
    [string]$ExpectedDotEnvSha256 = '',
    [string]$ExpectedSharedSha256 = ''
)
Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
$rootPath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Root).ProviderPath).TrimEnd('\','/')
$dotenv = Join-Path $rootPath '.env'
$shared = Join-Path $rootPath 'shared.env'
function Get-HashOrMissing([string]$Path) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
    return 'missing'
}
function Get-LinkedState {
    if (-not (Test-Path -LiteralPath $dotenv -PathType Leaf) -or -not (Test-Path -LiteralPath $shared -PathType Leaf)) { return $false }
    $links = @(& fsutil hardlink list $dotenv 2>$null)
    if ($LASTEXITCODE -ne 0) { throw 'hardlink-identity-evidence-needed: run on the Desktop local NTFS root' }
    $wanted = $shared.Substring([IO.Path]::GetPathRoot($shared).Length - 1)
    return (@($links | Where-Object { $_.Trim().Equals($wanted, [StringComparison]::OrdinalIgnoreCase) }).Count -eq 1)
}
foreach ($path in @($dotenv,$shared)) {
    if (Test-Path -LiteralPath $path) {
        $item = Get-Item -LiteralPath $path -Force
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'unexpected-env-path-type' }
    }
}
$dotHash = Get-HashOrMissing $dotenv
$sharedHash = Get-HashOrMissing $shared
$linked = Get-LinkedState
if ($Apply -and -not $linked) {
    if ($rootPath.StartsWith('\\')) { throw 'repair-requires-desktop-local-root' }
    $drive = Get-PSDrive -Name ([IO.Path]::GetPathRoot($rootPath).TrimEnd('\',':'))
    if ($drive.DisplayRoot) { throw 'repair-requires-desktop-local-root' }
    $parent = Get-Item -LiteralPath $rootPath -Force
    while ($null -ne $parent) {
        if ($parent.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'reparse-parent-refused' }
        $parent = $parent.Parent
    }
    if ($ExpectedDotEnvSha256 -ne $dotHash -or $ExpectedSharedSha256 -ne $sharedHash) { throw 'preimage-mismatch: supply both hashes from status; use missing for an absent file' }
    $sourcePath = if ($Source -eq 'Shared') { $shared } else { $dotenv }
    $destination = if ($Source -eq 'Shared') { $dotenv } else { $shared }
    if (-not (Test-Path -LiteralPath $sourcePath -PathType Leaf)) { throw 'selected-source-missing' }
    # An exclusive source handle prevents edits while its second name is replaced.
    $handle = [IO.File]::Open($sourcePath,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
    $backupPath = $null
    try {
        if ((Get-HashOrMissing $dotenv) -ne $dotHash -or (Get-HashOrMissing $shared) -ne $sharedHash) { throw 'preimage-changed' }
        $oldBytes = if (Test-Path -LiteralPath $destination) { [IO.File]::ReadAllBytes($destination) } else { $null }
        if ($null -ne $oldBytes -and $dotHash -ne $sharedHash) {
            Add-Type -AssemblyName System.Security
            $encrypted = [Security.Cryptography.ProtectedData]::Protect($oldBytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
            $backupPath = Join-Path $rootPath ('.env.recovery-' + [guid]::NewGuid().ToString('N') + '.dpapi')
            [IO.File]::WriteAllBytes($backupPath,$encrypted)
        }
        # Only the exact second filename is removed, after its bytes have been retained.
        if (Test-Path -LiteralPath $destination) { Remove-Item -LiteralPath $destination -Force }
        try { New-Item -ItemType HardLink -Path $destination -Target $sourcePath -ErrorAction Stop | Out-Null }
        catch {
            if ($null -ne $oldBytes -and -not (Test-Path -LiteralPath $destination)) { [IO.File]::WriteAllBytes($destination,$oldBytes) }
            throw 'hardlink-create-failed; original destination restored where possible'
        }
    } finally { $handle.Dispose() }
    $linked = Get-LinkedState
    $dotHash = Get-HashOrMissing $dotenv
    $sharedHash = Get-HashOrMissing $shared
    if (-not $linked -or $dotHash -ne $sharedHash) { throw 'hardlink-verification-failed' }
}
[pscustomobject]@{
    linked = $linked
    contentEqual = ($dotHash -ne 'missing' -and $dotHash -eq $sharedHash)
    dotEnvSha256 = $dotHash
    sharedSha256 = $sharedHash
    status = $(if ($linked) { 'linked' } else { 'repair-needed' })
    direction = 'bidirectional-in-place; explicit-source-repair-after-replacement-save'
} | ConvertTo-Json -Compress
