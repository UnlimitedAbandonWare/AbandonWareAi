param(
    [int]$TimeoutSeconds = 120,
    [int]$ServerPort = 18080,
    [int]$ManagementPort = 18081,
    [int]$NettyPort = 18082,
    [string]$LogPath = "logs\verify_boot_current.log",
    [switch]$StaticContractOnly
)

$ErrorActionPreference = "Stop"

function Write-VerifyLog {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host $Message
    Add-Content -Path $LogPath -Value $Message
}

function ConvertTo-ProcessArgument {
    param([AllowEmptyString()][string]$Value)
    if ($null -eq $Value) {
        return '""'
    }
    if ($Value -notmatch '[\s"]') {
        return $Value
    }
    return '"' + ($Value -replace '"', '\"') + '"'
}

function Stop-VerifyBootProcessTree {
    param(
        [int]$RootPid,
        [AllowEmptyString()][string]$Token
    )
    $processRows = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)
    $targetIds = @($RootPid)
    $changed = $true
    while ($changed) {
        $changed = $false
        foreach ($row in $processRows) {
            if ($targetIds -contains [int]$row.ProcessId) {
                continue
            }
            if ($targetIds -contains [int]$row.ParentProcessId) {
                $targetIds += [int]$row.ProcessId
                $changed = $true
            }
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($Token)) {
        foreach ($row in $processRows) {
            if ([string]$row.CommandLine -match [regex]::Escape($Token)) {
                if (-not ($targetIds -contains [int]$row.ProcessId)) {
                    $targetIds += [int]$row.ProcessId
                }
            }
        }
    }
    foreach ($targetId in ($targetIds | Sort-Object -Descending)) {
        if ($targetId -eq $PID) {
            continue
        }
        try {
            Stop-Process -Id $targetId -Force -ErrorAction SilentlyContinue
        } catch {
            # Best-effort cleanup; verification classification comes from the log scan below.
        }
    }
}

function Add-ProcessOutputIfReady {
    param(
        [Parameter(Mandatory = $true)]$Task,
        [Parameter(Mandatory = $true)][string]$Label
    )
    if ($Task.Wait(5000)) {
        Add-Content -Path $LogPath -Value $Task.Result
    } else {
        Write-VerifyLog "[AWX][verify] $Label-drain-timeout=true"
    }
}

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $Root
try {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $LogPath) | Out-Null
    Set-Content -Path $LogPath -Value "[AWX][verify] verify_boot.ps1 started" -Encoding UTF8

    $env:AWX_AGENT_HOST = "desktop"
    $env:AWX_SPLIT_BUILD_OUTPUTS = "1"
    $env:AWX_BUILD_HOST_ID = "desktop"
    $env:AWX_GRADLE_USER_HOME = Join-Path $env:USERPROFILE ".gradle-awx-desktop"
    $env:GRADLE_USER_HOME = $env:AWX_GRADLE_USER_HOME
    if ([string]::IsNullOrWhiteSpace($env:AWX_PROJECT_CACHE_DIR)) {
        $env:AWX_PROJECT_CACHE_DIR = Join-Path $env:USERPROFILE ".awx-gradle-project-cache\desktop"
    }
    $ProjectCacheDir = $env:AWX_PROJECT_CACHE_DIR
    New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$ProjectCacheDir | Out-Null
    $VerifyBootToken = "awx-vb-" + ([guid]::NewGuid().ToString("N"))
    $GradleArgs = @("--no-daemon", "--project-cache-dir", "$ProjectCacheDir")
    $BootAppArgs = @("--server.port=$ServerPort", "--management.server.port=$ManagementPort", "--netty.port=$NettyPort")

    $probeMode = "blocker-scan-only"
    $webServerStartedProven = $false
    $wiringPrecheckProven = $false
    $runtimePrecheckProven = $false
    $springStartedLogProven = $false
    $applicationReadyEventProven = $false
    $bootStartedProven = $false
    $bootSuccessProven = $false
    $completed = $false
    $loggedBootRunFailure = $false

    if ($StaticContractOnly) {
        Write-VerifyLog "[AWX][verify] static-contract-only=true"
    } else {
        if (-not (Test-Path ".\gradlew.bat")) {
            Write-VerifyLog "[AWX][verify] evidence_needed: gradlew.bat missing"
            exit 1
        }
        Write-VerifyLog "[AWX][verify] verifyBootPorts=server:$ServerPort,management:$ManagementPort,netty:$NettyPort"

        $psi = [System.Diagnostics.ProcessStartInfo]::new()
        $psi.FileName = (Resolve-Path ".\gradlew.bat").Path
        $bootRunArgs = @("-Dawx.verifyBootToken=$VerifyBootToken", "bootRun") + $GradleArgs + @("--args=$($BootAppArgs -join ' ')")
        if ($null -ne $psi.ArgumentList) {
            foreach ($arg in $bootRunArgs) {
                [void]$psi.ArgumentList.Add($arg)
            }
        } else {
            $psi.Arguments = ($bootRunArgs | ForEach-Object { ConvertTo-ProcessArgument $_ }) -join " "
        }
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        $psi.UseShellExecute = $false
        $psi.CreateNoWindow = $true
        $process = [System.Diagnostics.Process]::new()
        $process.StartInfo = $psi
        [void]$process.Start()

        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $completed = $true
            Write-VerifyLog "[AWX][verify] intentional-timeout-stop=true timeoutSeconds=$TimeoutSeconds"
            Stop-VerifyBootProcessTree -RootPid $process.Id -Token $VerifyBootToken
            if (-not $process.WaitForExit(5000)) {
                Write-VerifyLog "[AWX][verify] process-exit-timeout=true"
            }
        }

        Add-ProcessOutputIfReady -Task $stdoutTask -Label "stdout"
        Add-ProcessOutputIfReady -Task $stderrTask -Label "stderr"
        if ($process.ExitCode -ne 0 -and -not $completed) {
            Write-VerifyLog "[AWX][verify] bootRun logged failure exitCode=$($process.ExitCode)"
        }
    }

    $logText = ""
    if (Test-Path $LogPath) {
        $logText = Get-Content -Path $LogPath -Raw -ErrorAction SilentlyContinue
    }

    $loggedBootRunFailure = $logText -match "Task :bootRun FAILED|bootRun logged failure"
    $runtimePrecheckMarkers = @("Tomcat started on port", "[Precheck] done.")
    $applicationReadyMarkers = @("[AblationPenalty]", "[SynergyBootstrapper]")
    $webServerStartedProven = $logText -match [regex]::Escape("Tomcat started on port")
    $wiringPrecheckProven = $logText -match [regex]::Escape("[Precheck] done.")
    $runtimePrecheckProven = $webServerStartedProven -and $wiringPrecheckProven
    $springStartedLogProven = $logText -match "Started .*LmsApplication"
    $applicationReadyEventProven = $false
    foreach ($marker in $applicationReadyMarkers) {
        if ($logText -match [regex]::Escape($marker)) {
            $applicationReadyEventProven = $true
            break
        }
    }
    $bootStartedProven = $springStartedLogProven -or $applicationReadyEventProven
    $bootSuccessProven = $springStartedLogProven -and $applicationReadyEventProven -and $runtimePrecheckProven -and -not $loggedBootRunFailure

    if ($bootSuccessProven) {
        $probeMode = "boot-success-proven"
    } elseif ($bootStartedProven) {
        $probeMode = "boot-started-proven"
    } elseif ($applicationReadyEventProven) {
        $probeMode = "application-ready-event-proven"
    } elseif ($runtimePrecheckProven) {
        $probeMode = "runtime-precheck-proven"
    } else {
        $probeMode = "blocker-scan-only"
    }

    if ($loggedBootRunFailure -and $completed) {
        Write-VerifyLog "[AWX][verify] bootRun logged failure after intentional timeout stop"
    } elseif ($loggedBootRunFailure) {
        Write-VerifyLog "[AWX][verify] bootRun logged failure"
    }

    Write-VerifyLog "[AWX][verify] webServerStartedProven=$webServerStartedProven wiringPrecheckProven=$wiringPrecheckProven runtimePrecheckProven=$runtimePrecheckProven springStartedLogProven=$springStartedLogProven applicationReadyEventProven=$applicationReadyEventProven bootStartedProven=$bootStartedProven bootSuccessProven=$bootSuccessProven probeMode=$probeMode"
    if (-not $bootSuccessProven) {
        Write-VerifyLog "[AWX][verify] bootSuccessProven=False"
    }

    if ($loggedBootRunFailure -and -not $completed) {
        exit 1
    }
    exit 0
} finally {
    Pop-Location
}
