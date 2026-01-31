param(
  [string]$RepoRoot = ".",
  [int]$MaxAttempts = 3
)

$ErrorActionPreference = "Stop"

function Read-All($path) { return [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8) }
function Write-All($path, $text) { [IO.File]::WriteAllText($path, $text, [Text.Encoding]::UTF8); Write-Host "  - Patched: $path" }
function Replace-InFile($path, $pattern, $replacement) {
  if (-not (Test-Path $path)) { return $false }
  $src = Read-All $path
  $new = [Regex]::Replace($src, $pattern, $replacement, 'Singleline')
  if ($new -ne $src) { Write-All $path $new; return $true } else { return $false }
}

Write-Host "== Guard: Version purity (LangChain4j 1.0.1) ==" -ForegroundColor Cyan
$gradleFiles = @()
$gradleFiles += Get-ChildItem -Path $RepoRoot -Recurse -Include build.gradle,build.gradle.kts -ErrorAction SilentlyContinue
$txt = ($gradleFiles | ForEach-Object { Read-All $_.FullName }) -join "`n"
if ($txt -match 'langchain4j' -and ($txt -match '0\.[0-9]+' -or $txt -match 'beta' -or $txt -match '1\.0\.0')) {
  Write-Error "Version purity violation detected for langchain4j. Please normalize to 1.0.1. STOP."
  exit 2
}

Write-Host "== Apply minimal patches ==" -ForegroundColor Cyan
$changed = $false

$svc = Join-Path $RepoRoot 'src\main\java\com\example\lms\service\ChatHistoryServiceImpl.java'
$changed = (Replace-InFile $svc 'catch\s*\(\s*IllegalStateException\s*\|\s*RuntimeException\s*e\s*\)' 'catch (RuntimeException e)') -or $changed

$sec = Join-Path $RepoRoot 'src\main\java\com\example\lms\config\CustomSecurityConfig.java'
$changed = (Replace-InFile $sec 'new\s+AntPathRequestMatcher\("([^"]+)"\)' '"$1"') -or $changed

$ctl = Join-Path $RepoRoot 'src\main\java\com\example\lms\api\ChatApiController.java'
if (Test-Path $ctl) {
  $changed = (Replace-InFile $ctl '(?s)\.doOnSuccess\(\s*[^)]*\{\s*.*?\}\s*\)\s*' '' ) -or $changed
  $changed = (Replace-InFile $ctl '\.map\(\s*[a-zA-Z_]\w*\s*->' '.map(body ->' ) -or $changed
  $changed = (Replace-InFile $ctl '\bok\.body\(resp\)' 'ok.body(body)' ) -or $changed
  $changed = (Replace-InFile $ctl '\bresp\.getModelUsed\(\)' 'body.getModelUsed()' ) -or $changed
  $changed = (Replace-InFile $ctl '\bresp\.isRagUsed\(\)' 'body.isRagUsed()' ) -or $changed
  $changed = (Replace-InFile $ctl '\bfinalUsername\b' 'username' ) -or $changed
  $ensureAttach = 'if (req.getAttachmentIds() != null && !req.getAttachmentIds().isEmpty() && body != null && body.getSessionId() != null) { attachmentService.attachToSession(String.valueOf(body.getSessionId()), req.getAttachmentIds()); } return ok.body(body);'
  $changed = (Replace-InFile $ctl 'return\s+ok\.body\(\s*body\s*\);' $ensureAttach ) -or $changed
}

if ($changed) { Write-Host "== Patches applied. ==" -ForegroundColor Green } else { Write-Host "== No changes needed. ==" -ForegroundColor Yellow }

Write-Host "== Gradle compile loop ==" -ForegroundColor Cyan
$gradlew = Join-Path $RepoRoot 'gradlew.bat'
if (-not (Test-Path $gradlew)) { Write-Error "gradlew.bat not found in $RepoRoot"; exit 3 }

$attempt = 0
while ($attempt -lt $MaxAttempts) {
  $attempt++
  Write-Host "  -> Attempt $attempt / $MaxAttempts" -ForegroundColor White
  Push-Location $RepoRoot
  try {
    $p = Start-Process -FilePath $gradlew -ArgumentList 'compileJava','--no-daemon' -PassThru -Wait -NoNewWindow
    Pop-Location
    if ($p.ExitCode -eq 0) { Write-Host "== BUILD SUCCESS ==" -ForegroundColor Green; exit 0 }
    else { Write-Host "Gradle exit code: $($p.ExitCode)" -ForegroundColor Yellow }
  } catch {
    Pop-Location
    Write-Host "Gradle threw an exception, will retry if attempts left." -ForegroundColor Yellow
  }
  Start-Sleep -Seconds 1
}
Write-Error "BUILD FAILED after $MaxAttempts attempts. See build/reports/problems/problems-report.html if available."
exit 1