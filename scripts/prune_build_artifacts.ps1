# scripts/prune_build_artifacts.ps1
# Auto-prune old build experiment dirs under build\ that are older than -Days.
# These are disposable Gradle/verification outputs; the real build regenerates
# only what it needs. Safe by default: -DryRun prints what would be deleted.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\prune_build_artifacts.ps1 -DryRun
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\prune_build_artifacts.ps1 -Days 7
#
# Schedule (optional, run once as the user):
#   schtasks /Create /TN "AbandonWare-Build-Prune" /SC DAILY /ST 03:30 /F `
#     /TR "powershell -NoProfile -ExecutionPolicy Bypass -File \"C:\AbandonWare\demo-1\demo-1\src\scripts\prune_build_artifacts.ps1\" -Days 7"
#
param(
    [int]$Days = 7,
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot   # C:\AbandonWare\demo-1\demo-1\src
$buildDir = Join-Path $root 'build'

if (-not (Test-Path -LiteralPath $buildDir -PathType Container)) {
    Write-Output "[prune] build dir not found: $buildDir"
    exit 0
}

$cutoff = (Get-Date).AddDays(-$Days)
$dirs = Get-ChildItem -LiteralPath $buildDir -Directory -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -lt $cutoff }

if (-not $dirs -or $dirs.Count -eq 0) {
    Write-Output ("[prune] nothing older than {0} days under {1}" -f $Days, $buildDir)
    exit 0
}

$totalBytes = 0
$skipped = 0
foreach ($d in $dirs) {
    # Never touch a dir that contains a lock file or a running-process marker
    if (Test-Path -LiteralPath (Join-Path $d.FullName '.operation.lock') -ErrorAction SilentlyContinue) {
        $skipped++
        continue
    }
    try {
        $bytes = (Get-ChildItem -LiteralPath $d.FullName -Recurse -File -Force -ErrorAction SilentlyContinue |
                  Measure-Object -Property Length -Sum).Sum
        $totalBytes += [long]$bytes
        if ($DryRun) {
            Write-Output ("[dry-run] would delete: {0}  ({1:N1} MB, {2})" -f $d.Name, ($bytes/1MB), $d.LastWriteTime)
        } else {
            Remove-Item -LiteralPath $d.FullName -Recurse -Force -ErrorAction Stop
            Write-Output ("[prune] deleted: {0}  ({1:N1} MB)" -f $d.Name, ($bytes/1MB))
        }
    } catch {
        Write-Warning ("[prune] failed to delete {0}: {1}" -f $d.Name, $_.Exception.Message)
    }
}

$mode = if ($DryRun) { 'DRY-RUN' } else { 'APPLIED' }
Write-Output ("[prune] {0}: {1} dirs, {2:N1} MB, cutoff={3:yyyy-MM-dd}, skipped={4}" -f
    $mode, $dirs.Count, ($totalBytes/1MB), $cutoff, $skipped)
exit 0
