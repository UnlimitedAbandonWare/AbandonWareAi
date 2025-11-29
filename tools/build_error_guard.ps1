\
Param()
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$patFile = Join-Path $root "tools\build_error_patterns.txt"
$logCandidates = @(
  (Join-Path $root "build\logs\build.log"),
  (Join-Path $root "build\reports\tests\test\index.html"),
  (Join-Path $root "build\tmp\compileJava\previous-compilation-data.bin")
)
Write-Host "[guard] scanning for known bad patterns..."
$rc = 0
if (Test-Path $patFile) {
  $patterns = Get-Content $patFile | Where-Object { $_ -and ($_ -notmatch "^\s*#") }
  foreach ($log in $logCandidates) {
    if (Test-Path $log) {
      foreach ($pat in $patterns) {
        $match = Select-String -Path $log -Pattern $pat -SimpleMatch -ErrorAction SilentlyContinue
        if ($match) {
          Write-Host "::error file=$log:: matched pattern: $pat"
          $rc = 1
        }
      }
    }
  }
}
# banned tokens in sources
$srcDir = Join-Path $root "src"
if (Test-Path $srcDir) {
  $match = Select-String -Path (Join-Path $srcDir "*") -Recurse -Pattern "\{스터프3\}" -ErrorAction SilentlyContinue
  if ($match) {
    Write-Host "::error file=src:: Found banned token {스터프3} in sources"
    $rc = 1
  }
}
exit $rc
