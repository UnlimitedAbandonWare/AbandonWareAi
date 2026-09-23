param(
    [switch]$SkipScan
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$Root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
Push-Location $Root
try {
    $oldErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $gitRoot = (& git rev-parse --show-toplevel 2>$null | Select-Object -First 1)
    $ErrorActionPreference = $oldErrorActionPreference
    if ([string]::IsNullOrWhiteSpace($gitRoot)) {
        Write-Host "[AWX][git-guard][install] evidence_needed: this folder is not a usable Git repository yet."
        Write-Host "[AWX][git-guard][install] After GitHub Desktop locates or initializes the repo, run:"
        Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\install_git_publish_guard.ps1"
        exit 1
    }

    git config core.hooksPath .githooks
    if ($LASTEXITCODE -ne 0) {
        throw "git config core.hooksPath failed"
    }

    Write-Host "[AWX][git-guard][install] hooksPath=.githooks"
    if (-not $SkipScan) {
        & (Join-Path $Root "scripts\git_secret_guard.ps1") -Mode manual -ScanAll
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    }
    Write-Host "[AWX][git-guard][install] PASS"
} finally {
    Pop-Location
}
