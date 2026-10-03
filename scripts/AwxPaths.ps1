# AWX path registry resolver for PowerShell tools.
# Dot-source this file, then: Resolve-AwxPath -Key <name> [-MustExist]
# Same rules as scripts/awx_paths.py: env override -> registry path ->
# first existing old_paths entry (alias note on stderr). git.exe falls back
# to PATH after the registry path. Root = parent dir of this script's dir.
$script:AwxRoot = Split-Path -Parent $PSScriptRoot
$script:AwxRegistry = Join-Path $script:AwxRoot 'configs\agent-paths.yaml'
$script:AwxRegistryCache = $null

function Get-AwxRegistry {
    if ($null -ne $script:AwxRegistryCache) { return $script:AwxRegistryCache }
    $entries = @{}
    $inPaths = $false; $current = $null
    foreach ($raw in [System.IO.File]::ReadAllLines($script:AwxRegistry)) {
        if ($raw -match '^\s*#' -or $raw.Trim() -eq '') { continue }
        if ($raw -notmatch '^ ') {
            $inPaths = ($raw.Trim() -eq 'paths:'); $current = $null; continue
        }
        if (-not $inPaths) { continue }
        if ($raw -match '^  ([\w.\-]+):\s*$') {
            $current = $Matches[1]; $entries[$current] = @{}; continue
        }
        if ($raw -match '^    (\w+):\s*(.*)$' -and $null -ne $current) {
            $v = $Matches[2].Trim()
            if ($v -eq '' -or $v -eq 'null' -or $v -eq '~') { $v = $null }
            elseif ($v -match '^\[(.*)\]$') {
                $inner = $Matches[1].Trim()
                $v = @()
                if ($inner -ne '') {
                    $v = @($inner.Split(',') | ForEach-Object { $_.Trim().Trim('"').Trim("'") })
                }
            }
            elseif ($v.Length -ge 2 -and $v.StartsWith('"') -and $v.EndsWith('"')) {
                $v = $v.Substring(1, $v.Length - 2)
            }
            $entries[$current][$Matches[1]] = $v
        }
    }
    $script:AwxRegistryCache = $entries
    return $entries
}

function Expand-AwxPath {
    param([string]$PathText)
    if ($null -eq $PathText) { return $null }
    $p = $PathText -replace '%USERPROFILE%', $env:USERPROFILE
    $p = [System.Environment]::ExpandEnvironmentVariables($p)
    if (-not [System.IO.Path]::IsPathRooted($p)) {
        $p = Join-Path $script:AwxRoot $p
    }
    return $p
}

function Resolve-AwxPath {
    param(
        [Parameter(Mandatory = $true)][string]$Key,
        [switch]$MustExist
    )
    if ($Key -eq 'repo.root') {
        if ($env:AWX_ROOT) { return $env:AWX_ROOT }
        return $script:AwxRoot
    }
    $reg = Get-AwxRegistry
    if (-not $reg.ContainsKey($Key)) {
        throw "unknown path key: $Key"
    }
    $entry = $reg[$Key]
    $envName = $entry['env']
    if ($envName -and [Environment]::GetEnvironmentVariable($envName)) {
        return [Environment]::GetEnvironmentVariable($envName)
    }
    $regPath = Expand-AwxPath $entry['path']
    if ($null -ne $regPath -and (Test-Path -LiteralPath $regPath)) {
        return $regPath
    }
    foreach ($old in @($entry['old_paths'])) {
        if ($null -eq $old) { continue }
        $cand = Expand-AwxPath $old
        if ($null -ne $cand -and (Test-Path -LiteralPath $cand)) {
            [Console]::Error.WriteLine("[AWX][path-alias] key=$Key old=$old")
            return $cand
        }
    }
    if ($Key -eq 'git.exe') {
        $g = Get-Command git -ErrorAction SilentlyContinue
        if ($g) { return $g.Source }
    }
    if ($MustExist -and ($null -eq $regPath -or -not (Test-Path -LiteralPath $regPath))) {
        throw "$Key not found: $regPath"
    }
    return $regPath
}
