Param()
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")
Write-Host "[fix_build] Applying build matrix fixes..."
try {
  python3 scripts/apply_matrix_fixes.py
} catch {
  python scripts/apply_matrix_fixes.py
}
if (!(Test-Path "gradle/wrapper/gradle-wrapper.jar")) {
  Write-Error "[fix_build] gradle/wrapper/gradle-wrapper.jar not found"
}
Write-Host "[fix_build] OK"
