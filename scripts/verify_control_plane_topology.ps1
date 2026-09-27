param(
    [switch]$SkipGradle,
    [switch]$SkipSmokes,
    [int]$BasePort = 18160,
    [int]$StartupTimeoutSeconds = 120,
    [int]$CollisionGraceSeconds = 15
)

$ErrorActionPreference = "Stop"

$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Gradle = Join-Path $Root "gradlew.bat"
$CollectorSmoke = Join-Path $PSScriptRoot "smoke_learning_ops_collector.ps1"
$GpuSmoke = Join-Path $PSScriptRoot "smoke_gpu_gateway_preflight.ps1"
$env:AWX_AGENT_HOST = if ([string]::IsNullOrWhiteSpace($env:AWX_AGENT_HOST)) { "desktop" } else { $env:AWX_AGENT_HOST }
$env:AWX_SPLIT_BUILD_OUTPUTS = if ([string]::IsNullOrWhiteSpace($env:AWX_SPLIT_BUILD_OUTPUTS)) { "1" } else { $env:AWX_SPLIT_BUILD_OUTPUTS }
$env:AWX_BUILD_HOST_ID = if ([string]::IsNullOrWhiteSpace($env:AWX_BUILD_HOST_ID)) { "desktop" } else { $env:AWX_BUILD_HOST_ID }
$env:GRADLE_USER_HOME = if ([string]::IsNullOrWhiteSpace($env:AWX_GRADLE_USER_HOME)) {
    Join-Path $env:USERPROFILE ".gradle-awx-desktop"
} else {
    $env:AWX_GRADLE_USER_HOME
}
$ProjectCacheDir = if ([string]::IsNullOrWhiteSpace($env:AWX_PROJECT_CACHE_DIR)) {
    Join-Path $env:LOCALAPPDATA "awx-gradle-project-cache\desktop"
} else {
    $env:AWX_PROJECT_CACHE_DIR
}
$env:AWX_PROJECT_CACHE_DIR = $ProjectCacheDir
$FocusedTestClasses = @(
    "com.example.lms.learning.ops.RagLearningOpsDashboardServiceTest",
    "com.example.lms.learning.ops.RagLearningOpsCurationCollectorTest",
    "com.example.lms.web.LearningDataTemplateTest",
    "com.example.lms.manifest.LocalModelConfigYamlTest"
)
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME, $ProjectCacheDir | Out-Null

if (-not (Test-Path $Gradle)) {
    throw "[AWX][topology-verify] evidence_needed: gradlew.bat missing / verify repo root"
}
if (-not (Test-Path $CollectorSmoke)) {
    throw "[AWX][topology-verify] evidence_needed: collector smoke missing / verify scripts\smoke_learning_ops_collector.ps1"
}
if (-not (Test-Path $GpuSmoke)) {
    throw "[AWX][topology-verify] evidence_needed: GPU gateway smoke missing / verify scripts\smoke_gpu_gateway_preflight.ps1"
}

function Invoke-Step([string]$Name, [scriptblock]$Block) {
    Write-Host "[AWX][topology-verify] START $Name"
    & $Block
    Write-Host "[AWX][topology-verify] OK $Name"
}

function Invoke-Native([string]$Label, [string]$FilePath, [string[]]$Arguments) {
    & $FilePath @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "[AWX][topology-verify] $Label failed exit=$LASTEXITCODE"
    }
}

function Get-FocusedTestResultRoot {
    $hostId = [regex]::Replace($env:AWX_BUILD_HOST_ID, "[^A-Za-z0-9._-]+", "-").Trim([char[]]".-_")
    if ([string]::IsNullOrWhiteSpace($hostId)) {
        $hostId = "host"
    }

    if ([string]::IsNullOrWhiteSpace($env:AWX_BUILD_ROOT_DIR)) {
        $buildDir = Join-Path $Root ("build\{0}" -f $hostId)
    } else {
        $externalBuildRoot = $env:AWX_BUILD_ROOT_DIR.Trim()
        if (-not [IO.Path]::IsPathRooted($externalBuildRoot)) {
            $externalBuildRoot = Join-Path $Root $externalBuildRoot
        }
        $buildDir = Join-Path ([IO.Path]::GetFullPath($externalBuildRoot)) ("{0}\root" -f $hostId)
    }
    return (Join-Path $buildDir "test-results\test")
}

function Assert-FocusedTestReports([string[]]$ClassNames, [DateTime]$StartedAtUtc) {
    $resultRoot = Get-FocusedTestResultRoot
    foreach ($className in $ClassNames) {
        $reportPath = Join-Path $resultRoot "TEST-$className.xml"
        if (-not (Test-Path -LiteralPath $reportPath -PathType Leaf)) {
            throw "[AWX][topology-verify] focused-test-evidence-missing class=$className"
        }
        $report = Get-Item -LiteralPath $reportPath
        if ($report.LastWriteTimeUtc -lt $StartedAtUtc) {
            throw "[AWX][topology-verify] focused-test-evidence-stale class=$className"
        }
        try {
            [xml]$xml = Get-Content -LiteralPath $reportPath -Raw
            $tests = [int]$xml.testsuite.tests
            $failures = [int]$xml.testsuite.failures
            $errors = [int]$xml.testsuite.errors
            $skipped = [int]$xml.testsuite.skipped
            $executed = $tests - $skipped
        } catch {
            throw "[AWX][topology-verify] focused-test-evidence-invalid class=$className"
        }
        if ($tests -lt 1 -or $executed -lt 1 -or $failures -gt 0 -or $errors -gt 0) {
            throw "[AWX][topology-verify] focused-test-evidence-failed class=$className tests=$tests executed=$executed failures=$failures errors=$errors skipped=$skipped"
        }
        Write-Host "[AWX][topology-verify] EVIDENCE class=$className tests=$tests executed=$executed failures=$failures errors=$errors skipped=$skipped"
    }
}

function Get-GradleCollisionConflicts {
    $rootPattern = [regex]::Escape($Root)
    return @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
            Where-Object {
                $_.ProcessId -ne $PID -and
                $_.CommandLine -match $rootPattern -and
                ($_.CommandLine -match 'gradlew\.bat|GradleWrapperMain|GradleMain|bootRun' -or
                 $_.CommandLine -match 'build\\classes\\java\\main.*com\.example\.lms\.LmsApplication')
            } |
            Select-Object -First 5 -Property ProcessId, Name, CommandLine)
}

function Format-GradleCollisionSummary($Conflicts) {
    return ($Conflicts | ForEach-Object { "pid=$($_.ProcessId) name=$($_.Name)" }) -join "; "
}

function Wait-ForNoGradleCollision([string]$Stage) {
    $deadline = (Get-Date).AddSeconds($CollisionGraceSeconds)
    $reportedWait = $false
    do {
        $conflicts = @(Get-GradleCollisionConflicts)
        if ($conflicts.Count -eq 0) {
            return
        }
        if (-not $reportedWait) {
            Write-Host "[AWX][topology-verify] WAIT gradle-cache-collision-clear stage=$Stage conflictingProcesses=$(Format-GradleCollisionSummary $conflicts)"
            $reportedWait = $true
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)

    $conflicts = @(Get-GradleCollisionConflicts)
    if ($conflicts.Count -gt 0) {
        $summary = Format-GradleCollisionSummary $conflicts
        throw "[AWX][topology-verify] gradle-cache-collision stage=$Stage conflictingProcesses=$summary"
    }
}

function Assert-NoGradleCollision([string]$Stage) {
    Wait-ForNoGradleCollision $Stage
}

Push-Location $Root
try {
    if (-not $SkipGradle) {
        Assert-NoGradleCollision "before-focused-tests"
        Invoke-Step "focused-tests" {
            $FocusedTestsStartedAtUtc = [DateTime]::UtcNow
            $focusedTestArguments = @("test")
            foreach ($className in $FocusedTestClasses) {
                $focusedTestArguments += @("--tests", $className)
            }
            $focusedTestArguments += @(
                "--rerun-tasks",
                "--no-daemon",
                "--project-cache-dir", $ProjectCacheDir
            )
            Invoke-Native "focused-tests" $Gradle $focusedTestArguments
            Assert-FocusedTestReports $FocusedTestClasses $FocusedTestsStartedAtUtc
        }
        Assert-NoGradleCollision "before-build-surface"
        Invoke-Step "build-surface" {
            Invoke-Native "build-surface" $Gradle @(
                "checkLangchain4jVersionPurity",
                "checkSourceSetHygiene",
                "compileJava",
                ":app:classes",
                "bootJar",
                "--rerun-tasks",
                "--no-daemon",
                "--project-cache-dir", $ProjectCacheDir,
                "-x", "test"
            )
        }
    } else {
        Write-Host "[AWX][topology-verify] SKIP gradle"
    }

    if (-not $SkipSmokes) {
        # These smokes run bootRun against the shared build/classes output. Keep them sequential.
        Assert-NoGradleCollision "before-macmini-learning-ops-collector"
        Invoke-Step "macmini-learning-ops-collector" {
            Invoke-Native "macmini-learning-ops-collector" "powershell" @(
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", $CollectorSmoke,
                "-Port", [string]$BasePort,
                "-ManagementPort", [string]($BasePort + 1),
                "-StartupTimeoutSeconds", [string]$StartupTimeoutSeconds,
                "-CollectorTimeoutSeconds", "60"
            )
        }
        Assert-NoGradleCollision "before-desktop-gpu-node"
        Invoke-Step "desktop-gpu-node" {
            Invoke-Native "desktop-gpu-node" "powershell" @(
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", $GpuSmoke,
                "-Mode", "desktop",
                "-Port", [string]($BasePort + 10),
                "-ManagementPort", [string]($BasePort + 11),
                "-StartupTimeoutSeconds", [string]$StartupTimeoutSeconds
            )
        }
        Assert-NoGradleCollision "before-macmini-gpu-gateway-simulated"
        Invoke-Step "macmini-gpu-gateway-simulated" {
            Invoke-Native "macmini-gpu-gateway-simulated" "powershell" @(
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-File", $GpuSmoke,
                "-Mode", "simulated",
                "-Port", [string]($BasePort + 20),
                "-ManagementPort", [string]($BasePort + 21),
                "-StartupTimeoutSeconds", [string]$StartupTimeoutSeconds
            )
        }
    } else {
        Write-Host "[AWX][topology-verify] SKIP smokes"
    }

    Write-Host "[AWX][topology-verify] OK controlPlane=macmini-control-plane gpuExecutor=desktop-rtx3090-rtx3060 collector=read_only_curation queue=verified"
} finally {
    Pop-Location
}
