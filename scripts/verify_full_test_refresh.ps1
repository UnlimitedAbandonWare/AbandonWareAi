param(
    [string]$ProjectCacheDir = "$env:LOCALAPPDATA\awx-gradle-project-cache\desktop",
    [switch]$SkipFinalUpToDateCheck
)

$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $PSScriptRoot
Push-Location $Root
try {
    if (-not (Test-Path ".\gradlew.bat")) {
        Write-Error "[AWX][test-refresh] evidence_needed: gradlew.bat missing"
        exit 1
    }

    $env:AWX_AGENT_HOST = "desktop"
    $env:AWX_SPLIT_BUILD_OUTPUTS = "1"
    $env:AWX_BUILD_HOST_ID = "desktop"
    $env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
    New-Item -ItemType Directory -Force -Path $ProjectCacheDir,$env:GRADLE_USER_HOME | Out-Null

    Write-Host "[AWX][test-refresh] reason=stale-class-output-recovery symptom=NoClassDefFoundError"
    Write-Host "[AWX][test-refresh] step=rerun-tasks-fail-fast"
    & .\gradlew.bat test --fail-fast --rerun-tasks --no-daemon --project-cache-dir $ProjectCacheDir
    if ($LASTEXITCODE -ne 0) {
        Write-Error "[AWX][test-refresh] fail-fast rerun failed exit=$LASTEXITCODE"
        exit $LASTEXITCODE
    }

    if (-not $SkipFinalUpToDateCheck) {
        Write-Host "[AWX][test-refresh] step=normal-test-after-refresh"
        & .\gradlew.bat test --no-daemon --project-cache-dir $ProjectCacheDir
        if ($LASTEXITCODE -ne 0) {
            Write-Error "[AWX][test-refresh] normal test failed after refresh exit=$LASTEXITCODE"
            exit $LASTEXITCODE
        }
    }

    Write-Host "[AWX][test-refresh] PASS"
} finally {
    Pop-Location
}
