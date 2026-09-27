[CmdletBinding()]
param(
    [ValidateSet("auto", "desktop", "notebook")]
    [string]$Role = "auto",
    [string]$OutputRoot = "$env:USERPROFILE\gpt_device_probe\run-all",
    [Parameter(Mandatory)][string]$ShareRoot,
    [string]$TaskId = "probe-handoff",
    [int]$StaleMinutes = 45,
    [switch]$CreateRequest,
    [switch]$AutoRespond,
    [switch]$Acknowledge,
    [switch]$TestMode,
    [string]$DesktopProbe,
    [string]$NotebookProbe
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:AWX_AGENT_HOST)) { $env:AWX_AGENT_HOST = 'desktop' }
. (Join-Path $PSScriptRoot 'agent_api_spend_guard.ps1')
Write-AgentSpendLog -Purpose 'probe' -Provider 'openai' -Model 'multi' -Tier 'paid_quality' -Why 'compare_fanout' -Caller 'device_probe_gpt_run_all.ps1' -Cache 'miss' -EstCostClass 'llm_paid'
if ((Test-AgentSpendMode) -and -not $TestMode -and -not ($env:AWX_AGENT_ALLOW_PAID_MODELS -match '^(1|true)
$tool = Join-Path $PSScriptRoot "device_probe_gpt_tool.ps1"
$compare = Join-Path $PSScriptRoot "device_probe_gpt_compare.ps1"
$script:stages = @()

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { New-Item -ItemType Directory -Path $Path -Force | Out-Null }
}

function Resolve-LocalRole {
    param([string]$RequestedRole)
    if ($RequestedRole -ne "auto") { return $RequestedRole }
    if ([Environment]::MachineName -match "(?i)(nb|note|notebook|laptop)") { return "notebook" }
    return "desktop"
}

function Get-FullPathText {
    param([string]$Path)
    return [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($Path))
}

function Assert-TempPath {
    param([string]$Path, [string]$Label)
    $tempRoot = (Get-FullPathText -Path $env:TEMP).TrimEnd('\') + '\'
    $candidate = Get-FullPathText -Path $Path
    if (-not $candidate.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "test-mode-path-outside-temp label=$Label"
    }
    return $candidate
}

function Write-AtomicText {
    param([string]$Path, [string]$Text)
    Ensure-Directory -Path (Split-Path -Parent $Path)
    $temp = Join-Path (Split-Path -Parent $Path) (".{0}.{1}.tmp" -f (Split-Path -Leaf $Path), [guid]::NewGuid())
    [IO.File]::WriteAllText($temp, $Text, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination $Path -Force
}

function Write-AtomicJson {
    param([string]$Path, $Object)
    Write-AtomicText -Path $Path -Text (ConvertTo-Json -InputObject $Object -Depth 12)
}

function Add-Stage {
    param([string]$Name, [int]$ExitCode, [string[]]$Artifacts = @(), [string]$Reason = "")
    $row = [pscustomobject][ordered]@{
        name = $Name
        exitCode = $ExitCode
        artifacts = @($Artifacts | Where-Object { $_ })
        reason = $Reason
    }
    $script:stages += $row
    Write-Host ("[device-probe-run-all] stage={0} exitCode={1} artifacts={2} reason={3}" -f $Name, $ExitCode, ($row.artifacts -join ";"), $Reason)
}

function Invoke-ChildScript {
    param([string]$Path, [string[]]$Arguments)
    $captured = @(& pwsh -NoProfile -File $Path @Arguments 2>&1)
    return [pscustomobject]@{ exitCode = $LASTEXITCODE; outputLineCount = $captured.Count }
}

function Publish-TestProbe {
    param([string]$RoleName, [string]$ProbePath, [string]$Root)
    $payload = Get-Content -LiteralPath $ProbePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($payload.schemaVersion -notlike "device_probe_gpt_tool_v*") { throw "test-probe-schema-invalid role=$RoleName" }
    $target = Join-Path $Root ("nodes\{0}\latest\device-probe.json" -f $RoleName)
    Write-AtomicText -Path $target -Text (Get-Content -LiteralPath $ProbePath -Raw -Encoding UTF8)
    return $target
}

foreach ($required in @($tool, $compare)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "required-script-missing path=$required" }
}

$resolvedRole = Resolve-LocalRole -RequestedRole $Role
$resolvedOutput = Get-FullPathText -Path $OutputRoot
$resolvedShare = Get-FullPathText -Path $ShareRoot
if ($TestMode) {
    $resolvedOutput = Assert-TempPath -Path $resolvedOutput -Label "OutputRoot"
    $resolvedShare = Assert-TempPath -Path $resolvedShare -Label "ShareRoot"
    if (-not $DesktopProbe -or -not $NotebookProbe) { throw "test-mode-needs-desktop-and-notebook-probes" }
    $DesktopProbe = Assert-TempPath -Path $DesktopProbe -Label "DesktopProbe"
    $NotebookProbe = Assert-TempPath -Path $NotebookProbe -Label "NotebookProbe"
}
Ensure-Directory -Path $resolvedOutput
Ensure-Directory -Path $resolvedShare

$localProbe = $null
$collectExit = 0
$collectArtifacts = @()
try {
    if ($TestMode) {
        foreach ($probePath in @($DesktopProbe, $NotebookProbe)) {
            $probe = Get-Content -LiteralPath $probePath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($probe.schemaVersion -notlike "device_probe_gpt_tool_v*") { throw "probe-schema-invalid" }
        }
        $collectArtifacts = @($DesktopProbe, $NotebookProbe)
    } else {
        $collect = Invoke-ChildScript -Path $tool -Arguments @("-Role", $resolvedRole, "-OutputRoot", $resolvedOutput, "-ShareRoot", $resolvedShare, "-PublishToShare")
        $collectExit = $collect.exitCode
        $localProbe = Join-Path $resolvedOutput ("latest-{0}-gpt.json" -f $resolvedRole)
        if ($collectExit -eq 0 -and -not (Test-Path -LiteralPath $localProbe)) { $collectExit = 2 }
        $collectArtifacts = @($localProbe)
    }
} catch {
    $collectExit = 1
}
Add-Stage -Name "collect" -ExitCode $collectExit -Artifacts $collectArtifacts -Reason $(if ($collectExit -eq 0) { "ok" } else { "collect-failed" })

$publishExit = 0
$published = @()
try {
    if ($TestMode) {
        $published += Publish-TestProbe -RoleName "desktop" -ProbePath $DesktopProbe -Root $resolvedShare
        $published += Publish-TestProbe -RoleName "notebook" -ProbePath $NotebookProbe -Root $resolvedShare
    } else {
        $published += Join-Path $resolvedShare ("nodes\{0}\latest\device-probe.json" -f $resolvedRole)
        if ($collectExit -ne 0 -or -not (Test-Path -LiteralPath $published[0])) { throw "published-node-missing" }
    }
} catch {
    $publishExit = 1
}
Add-Stage -Name "publish" -ExitCode $publishExit -Artifacts $published -Reason $(if ($publishExit -eq 0) { "ok" } else { "publish-failed" })

$desktopInput = if ($TestMode) { $DesktopProbe } else { Join-Path $resolvedShare "nodes\desktop\latest\device-probe.json" }
$notebookInput = if ($TestMode) { $NotebookProbe } else { Join-Path $resolvedShare "nodes\notebook\latest\device-probe.json" }
$compareDir = Join-Path $resolvedOutput "compare"
$compareExit = 0
try {
    $comparison = Invoke-ChildScript -Path $compare -Arguments @("-DesktopProbe", $desktopInput, "-NotebookProbe", $notebookInput, "-OutputDir", $compareDir, "-StaleMinutes", "$StaleMinutes")
    $compareExit = $comparison.exitCode
} catch {
    $compareExit = 1
}
$compareArtifacts = @(Get-ChildItem -LiteralPath $compareDir -File -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -ExpandProperty FullName)
Add-Stage -Name "compare" -ExitCode $compareExit -Artifacts $compareArtifacts -Reason $(if ($compareExit -eq 0) { "ok" } else { "compare-failed" })

$loopExit = 0
try {
    if ($TestMode) {
        foreach ($step in @(
            @{ role = "desktop"; probe = $DesktopProbe; switchName = "CreateRequest" },
            @{ role = "notebook"; probe = $NotebookProbe; switchName = "AutoRespond" },
            @{ role = "desktop"; probe = $DesktopProbe; switchName = "Acknowledge" }
        )) {
            $loop = Invoke-ChildScript -Path $compare -Arguments @("-Mode", "loop", "-ShareRoot", $resolvedShare, "-SelfRole", $step.role, "-ProbeFile", $step.probe, "-TaskId", $TaskId, "-StaleMinutes", "$StaleMinutes", ("-" + $step.switchName))
            if ($loop.exitCode -ne 0) { $loopExit = $loop.exitCode; break }
        }
    } else {
        $loopArgs = @("-Mode", "loop", "-ShareRoot", $resolvedShare, "-SelfRole", $resolvedRole, "-ProbeFile", $localProbe, "-TaskId", $TaskId, "-StaleMinutes", "$StaleMinutes")
        $explicitAction = $CreateRequest -or $AutoRespond -or $Acknowledge
        if ($explicitAction) {
            if ($CreateRequest) { $loopArgs += "-CreateRequest" }
            if ($AutoRespond) { $loopArgs += "-AutoRespond" }
            if ($Acknowledge) { $loopArgs += "-Acknowledge" }
        } elseif ($resolvedRole -eq "notebook") {
            $loopArgs += "-AutoRespond"
        } else {
            $loopArgs += @("-CreateRequest", "-Acknowledge")
        }
        $loop = Invoke-ChildScript -Path $compare -Arguments $loopArgs
        $loopExit = $loop.exitCode
    }
} catch {
    $loopExit = 1
}
$dashboardRoot = Join-Path $resolvedShare "dashboard"
$loopArtifacts = @(
    (Join-Path $dashboardRoot "latest-dashboard.json"),
    (Join-Path $dashboardRoot "latest-context-pack.md"),
    (Join-Path $dashboardRoot "context-gap-report.md"),
    (Join-Path $dashboardRoot "device-diff.md"),
    (Join-Path $resolvedShare "messages\pending-summary.json"),
    (Join-Path $resolvedShare "timeline\events.ndjson")
)
Add-Stage -Name "loop" -ExitCode $loopExit -Artifacts @($loopArtifacts | Where-Object { Test-Path -LiteralPath $_ }) -Reason $(if ($loopExit -eq 0) { "ok" } else { "loop-failed" })

$summaryExit = 0
try {
    foreach ($requiredArtifact in $loopArtifacts) {
        if (-not (Test-Path -LiteralPath $requiredArtifact -PathType Leaf)) { throw "summary-artifact-missing" }
    }
    Get-Content -LiteralPath (Join-Path $dashboardRoot "latest-dashboard.json") -Raw -Encoding UTF8 | ConvertFrom-Json | Out-Null
    Get-Content -LiteralPath (Join-Path $resolvedShare "messages\pending-summary.json") -Raw -Encoding UTF8 | ConvertFrom-Json | Out-Null
    foreach ($line in Get-Content -LiteralPath (Join-Path $resolvedShare "timeline\events.ndjson") -Encoding UTF8) {
        $line | ConvertFrom-Json -ErrorAction Stop | Out-Null
    }
} catch {
    $summaryExit = 1
}
Add-Stage -Name "summary" -ExitCode $summaryExit -Artifacts @($loopArtifacts | Where-Object { Test-Path -LiteralPath $_ }) -Reason $(if ($summaryExit -eq 0) { "ok" } else { "summary-failed" })

$overallExitCode = if (@($script:stages | Where-Object { $_.exitCode -ne 0 }).Count -eq 0) { 0 } else { 1 }
$summaryPath = Join-Path $resolvedOutput "device-probe-run-all-summary.json"
$summary = [ordered]@{
    schemaVersion = "device_probe_run_all_v1"
    generatedAt = (Get-Date).ToString("o")
    testMode = [bool]$TestMode
    role = $resolvedRole
    taskId = $TaskId
    overallExitCode = $overallExitCode
    stages = @($script:stages)
    summaryPath = $summaryPath
}
Write-AtomicJson -Path $summaryPath -Object $summary
Write-Host ("[device-probe-run-all] overallExitCode={0} summary={1}" -f $overallExitCode, $summaryPath)
exit $overallExitCode
)) {
  throw '[AWX][api-spend] device_probe_gpt_run_all blocked in agent mode to protect credits. Re-run with -TestMode or set AWX_AGENT_ALLOW_PAID_MODELS=1 for an intentional paid fanout.'
}
$tool = Join-Path $PSScriptRoot "device_probe_gpt_tool.ps1"
$compare = Join-Path $PSScriptRoot "device_probe_gpt_compare.ps1"
$script:stages = @()

function Ensure-Directory {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { New-Item -ItemType Directory -Path $Path -Force | Out-Null }
}

function Resolve-LocalRole {
    param([string]$RequestedRole)
    if ($RequestedRole -ne "auto") { return $RequestedRole }
    if ([Environment]::MachineName -match "(?i)(nb|note|notebook|laptop)") { return "notebook" }
    return "desktop"
}

function Get-FullPathText {
    param([string]$Path)
    return [IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($Path))
}

function Assert-TempPath {
    param([string]$Path, [string]$Label)
    $tempRoot = (Get-FullPathText -Path $env:TEMP).TrimEnd('\') + '\'
    $candidate = Get-FullPathText -Path $Path
    if (-not $candidate.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "test-mode-path-outside-temp label=$Label"
    }
    return $candidate
}

function Write-AtomicText {
    param([string]$Path, [string]$Text)
    Ensure-Directory -Path (Split-Path -Parent $Path)
    $temp = Join-Path (Split-Path -Parent $Path) (".{0}.{1}.tmp" -f (Split-Path -Leaf $Path), [guid]::NewGuid())
    [IO.File]::WriteAllText($temp, $Text, [Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temp -Destination $Path -Force
}

function Write-AtomicJson {
    param([string]$Path, $Object)
    Write-AtomicText -Path $Path -Text (ConvertTo-Json -InputObject $Object -Depth 12)
}

function Add-Stage {
    param([string]$Name, [int]$ExitCode, [string[]]$Artifacts = @(), [string]$Reason = "")
    $row = [pscustomobject][ordered]@{
        name = $Name
        exitCode = $ExitCode
        artifacts = @($Artifacts | Where-Object { $_ })
        reason = $Reason
    }
    $script:stages += $row
    Write-Host ("[device-probe-run-all] stage={0} exitCode={1} artifacts={2} reason={3}" -f $Name, $ExitCode, ($row.artifacts -join ";"), $Reason)
}

function Invoke-ChildScript {
    param([string]$Path, [string[]]$Arguments)
    $captured = @(& pwsh -NoProfile -File $Path @Arguments 2>&1)
    return [pscustomobject]@{ exitCode = $LASTEXITCODE; outputLineCount = $captured.Count }
}

function Publish-TestProbe {
    param([string]$RoleName, [string]$ProbePath, [string]$Root)
    $payload = Get-Content -LiteralPath $ProbePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($payload.schemaVersion -notlike "device_probe_gpt_tool_v*") { throw "test-probe-schema-invalid role=$RoleName" }
    $target = Join-Path $Root ("nodes\{0}\latest\device-probe.json" -f $RoleName)
    Write-AtomicText -Path $target -Text (Get-Content -LiteralPath $ProbePath -Raw -Encoding UTF8)
    return $target
}

foreach ($required in @($tool, $compare)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "required-script-missing path=$required" }
}

$resolvedRole = Resolve-LocalRole -RequestedRole $Role
$resolvedOutput = Get-FullPathText -Path $OutputRoot
$resolvedShare = Get-FullPathText -Path $ShareRoot
if ($TestMode) {
    $resolvedOutput = Assert-TempPath -Path $resolvedOutput -Label "OutputRoot"
    $resolvedShare = Assert-TempPath -Path $resolvedShare -Label "ShareRoot"
    if (-not $DesktopProbe -or -not $NotebookProbe) { throw "test-mode-needs-desktop-and-notebook-probes" }
    $DesktopProbe = Assert-TempPath -Path $DesktopProbe -Label "DesktopProbe"
    $NotebookProbe = Assert-TempPath -Path $NotebookProbe -Label "NotebookProbe"
}
Ensure-Directory -Path $resolvedOutput
Ensure-Directory -Path $resolvedShare

$localProbe = $null
$collectExit = 0
$collectArtifacts = @()
try {
    if ($TestMode) {
        foreach ($probePath in @($DesktopProbe, $NotebookProbe)) {
            $probe = Get-Content -LiteralPath $probePath -Raw -Encoding UTF8 | ConvertFrom-Json
            if ($probe.schemaVersion -notlike "device_probe_gpt_tool_v*") { throw "probe-schema-invalid" }
        }
        $collectArtifacts = @($DesktopProbe, $NotebookProbe)
    } else {
        $collect = Invoke-ChildScript -Path $tool -Arguments @("-Role", $resolvedRole, "-OutputRoot", $resolvedOutput, "-ShareRoot", $resolvedShare, "-PublishToShare")
        $collectExit = $collect.exitCode
        $localProbe = Join-Path $resolvedOutput ("latest-{0}-gpt.json" -f $resolvedRole)
        if ($collectExit -eq 0 -and -not (Test-Path -LiteralPath $localProbe)) { $collectExit = 2 }
        $collectArtifacts = @($localProbe)
    }
} catch {
    $collectExit = 1
}
Add-Stage -Name "collect" -ExitCode $collectExit -Artifacts $collectArtifacts -Reason $(if ($collectExit -eq 0) { "ok" } else { "collect-failed" })

$publishExit = 0
$published = @()
try {
    if ($TestMode) {
        $published += Publish-TestProbe -RoleName "desktop" -ProbePath $DesktopProbe -Root $resolvedShare
        $published += Publish-TestProbe -RoleName "notebook" -ProbePath $NotebookProbe -Root $resolvedShare
    } else {
        $published += Join-Path $resolvedShare ("nodes\{0}\latest\device-probe.json" -f $resolvedRole)
        if ($collectExit -ne 0 -or -not (Test-Path -LiteralPath $published[0])) { throw "published-node-missing" }
    }
} catch {
    $publishExit = 1
}
Add-Stage -Name "publish" -ExitCode $publishExit -Artifacts $published -Reason $(if ($publishExit -eq 0) { "ok" } else { "publish-failed" })

$desktopInput = if ($TestMode) { $DesktopProbe } else { Join-Path $resolvedShare "nodes\desktop\latest\device-probe.json" }
$notebookInput = if ($TestMode) { $NotebookProbe } else { Join-Path $resolvedShare "nodes\notebook\latest\device-probe.json" }
$compareDir = Join-Path $resolvedOutput "compare"
$compareExit = 0
try {
    $comparison = Invoke-ChildScript -Path $compare -Arguments @("-DesktopProbe", $desktopInput, "-NotebookProbe", $notebookInput, "-OutputDir", $compareDir, "-StaleMinutes", "$StaleMinutes")
    $compareExit = $comparison.exitCode
} catch {
    $compareExit = 1
}
$compareArtifacts = @(Get-ChildItem -LiteralPath $compareDir -File -ErrorAction SilentlyContinue | Sort-Object Name | Select-Object -ExpandProperty FullName)
Add-Stage -Name "compare" -ExitCode $compareExit -Artifacts $compareArtifacts -Reason $(if ($compareExit -eq 0) { "ok" } else { "compare-failed" })

$loopExit = 0
try {
    if ($TestMode) {
        foreach ($step in @(
            @{ role = "desktop"; probe = $DesktopProbe; switchName = "CreateRequest" },
            @{ role = "notebook"; probe = $NotebookProbe; switchName = "AutoRespond" },
            @{ role = "desktop"; probe = $DesktopProbe; switchName = "Acknowledge" }
        )) {
            $loop = Invoke-ChildScript -Path $compare -Arguments @("-Mode", "loop", "-ShareRoot", $resolvedShare, "-SelfRole", $step.role, "-ProbeFile", $step.probe, "-TaskId", $TaskId, "-StaleMinutes", "$StaleMinutes", ("-" + $step.switchName))
            if ($loop.exitCode -ne 0) { $loopExit = $loop.exitCode; break }
        }
    } else {
        $loopArgs = @("-Mode", "loop", "-ShareRoot", $resolvedShare, "-SelfRole", $resolvedRole, "-ProbeFile", $localProbe, "-TaskId", $TaskId, "-StaleMinutes", "$StaleMinutes")
        $explicitAction = $CreateRequest -or $AutoRespond -or $Acknowledge
        if ($explicitAction) {
            if ($CreateRequest) { $loopArgs += "-CreateRequest" }
            if ($AutoRespond) { $loopArgs += "-AutoRespond" }
            if ($Acknowledge) { $loopArgs += "-Acknowledge" }
        } elseif ($resolvedRole -eq "notebook") {
            $loopArgs += "-AutoRespond"
        } else {
            $loopArgs += @("-CreateRequest", "-Acknowledge")
        }
        $loop = Invoke-ChildScript -Path $compare -Arguments $loopArgs
        $loopExit = $loop.exitCode
    }
} catch {
    $loopExit = 1
}
$dashboardRoot = Join-Path $resolvedShare "dashboard"
$loopArtifacts = @(
    (Join-Path $dashboardRoot "latest-dashboard.json"),
    (Join-Path $dashboardRoot "latest-context-pack.md"),
    (Join-Path $dashboardRoot "context-gap-report.md"),
    (Join-Path $dashboardRoot "device-diff.md"),
    (Join-Path $resolvedShare "messages\pending-summary.json"),
    (Join-Path $resolvedShare "timeline\events.ndjson")
)
Add-Stage -Name "loop" -ExitCode $loopExit -Artifacts @($loopArtifacts | Where-Object { Test-Path -LiteralPath $_ }) -Reason $(if ($loopExit -eq 0) { "ok" } else { "loop-failed" })

$summaryExit = 0
try {
    foreach ($requiredArtifact in $loopArtifacts) {
        if (-not (Test-Path -LiteralPath $requiredArtifact -PathType Leaf)) { throw "summary-artifact-missing" }
    }
    Get-Content -LiteralPath (Join-Path $dashboardRoot "latest-dashboard.json") -Raw -Encoding UTF8 | ConvertFrom-Json | Out-Null
    Get-Content -LiteralPath (Join-Path $resolvedShare "messages\pending-summary.json") -Raw -Encoding UTF8 | ConvertFrom-Json | Out-Null
    foreach ($line in Get-Content -LiteralPath (Join-Path $resolvedShare "timeline\events.ndjson") -Encoding UTF8) {
        $line | ConvertFrom-Json -ErrorAction Stop | Out-Null
    }
} catch {
    $summaryExit = 1
}
Add-Stage -Name "summary" -ExitCode $summaryExit -Artifacts @($loopArtifacts | Where-Object { Test-Path -LiteralPath $_ }) -Reason $(if ($summaryExit -eq 0) { "ok" } else { "summary-failed" })

$overallExitCode = if (@($script:stages | Where-Object { $_.exitCode -ne 0 }).Count -eq 0) { 0 } else { 1 }
$summaryPath = Join-Path $resolvedOutput "device-probe-run-all-summary.json"
$summary = [ordered]@{
    schemaVersion = "device_probe_run_all_v1"
    generatedAt = (Get-Date).ToString("o")
    testMode = [bool]$TestMode
    role = $resolvedRole
    taskId = $TaskId
    overallExitCode = $overallExitCode
    stages = @($script:stages)
    summaryPath = $summaryPath
}
Write-AtomicJson -Path $summaryPath -Object $summary
Write-Host ("[device-probe-run-all] overallExitCode={0} summary={1}" -f $overallExitCode, $summaryPath)
exit $overallExitCode

