[CmdletBinding()]
param([switch]$KeepArtifacts)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$tool = Join-Path $PSScriptRoot "device_probe_gpt_tool.ps1"
$compare = Join-Path $PSScriptRoot "device_probe_gpt_compare.ps1"
$runAll = Join-Path $PSScriptRoot "device_probe_gpt_run_all.ps1"
$testRoot = Join-Path $env:TEMP ("awx-device-probe-tests-" + [guid]::NewGuid().ToString("N"))

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ASSERTION_FAILED: $Message" }
}

function New-TestProbe {
    param([string]$Role, [string]$Hostname, [string]$Fingerprint, [datetime]$CollectedAt = (Get-Date))
    return [ordered]@{
        schemaVersion = "device_probe_gpt_tool_v3"
        collectedAt = $CollectedAt.ToString("o")
        runId = "$Role-test"
        role = $Role
        host = [ordered]@{ hostname = $Hostname; machineFingerprintHash = $Fingerprint; nodeId = "node-$Fingerprint" }
        hardware = [ordered]@{ os = @{}; cpu = @{}; memory = @{}; gpus = @(); disks = @() }
        services = [ordered]@{ listeningPorts = @(); endpoints = @(); dbServices = @() }
        source = [ordered]@{ repoFacts = @() }
        settings = [ordered]@{ environment = [ordered]@{ items = @() } }
        runtime = [ordered]@{ processesTotal = 0 }
    }
}

function Write-TestJson {
    param([string]$Path, $Object)
    $dir = Split-Path -Parent $Path
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    [IO.File]::WriteAllText($Path, (ConvertTo-Json -InputObject $Object -Depth 12), [Text.UTF8Encoding]::new($false))
}

function Invoke-CompareScript {
    param([string[]]$Arguments)
    $output = @(& pwsh -NoProfile -File $compare @Arguments 2>&1)
    return [pscustomobject]@{ exitCode = $LASTEXITCODE; output = @($output | ForEach-Object { [string]$_ }) }
}

function Seed-ShareNodes {
    param([string]$ShareRoot, [string]$DesktopPath, [string]$NotebookPath)
    foreach ($row in @(@{ role = "desktop"; path = $DesktopPath }, @{ role = "notebook"; path = $NotebookPath })) {
        $dir = Join-Path $ShareRoot ("nodes\{0}\latest" -f $row.role)
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
        Copy-Item -LiteralPath $row.path -Destination (Join-Path $dir "device-probe.json") -Force
    }
}

function Get-LoopState {
    param([string]$ShareRoot)
    $pending = Get-Content -LiteralPath (Join-Path $ShareRoot "messages\pending-summary.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $dashboard = Get-Content -LiteralPath (Join-Path $ShareRoot "dashboard\latest-dashboard.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    $timelinePath = Join-Path $ShareRoot "timeline\events.ndjson"
    $events = @()
    $invalidLines = 0
    foreach ($line in Get-Content -LiteralPath $timelinePath -Encoding UTF8) {
        try { $events += ($line | ConvertFrom-Json -ErrorAction Stop) } catch { $invalidLines++ }
    }
    return [pscustomobject]@{
        messageCounts = @(
            @(Get-ChildItem -LiteralPath (Join-Path $ShareRoot "messages\requests") -File -Filter "*.json").Count,
            @(Get-ChildItem -LiteralPath (Join-Path $ShareRoot "messages\responses") -File -Filter "*.json").Count,
            @(Get-ChildItem -LiteralPath (Join-Path $ShareRoot "messages\acknowledgements") -File -Filter "*.json").Count
        )
        pending = @($pending.pending_requests, $pending.pending_response_count, $pending.pending_acks)
        dashboardPending = @($dashboard.summary.pending_requests, $dashboard.summary.pending_responses, $dashboard.summary.pending_acks)
        events = $events
        invalidLines = $invalidLines
    }
}

function Invoke-LoopCycle {
    param([string]$ShareRoot, [string]$DesktopPath, [string]$NotebookPath, [string]$TaskId)
    $steps = @(
        @{ role = "desktop"; probe = $DesktopPath; switchName = "CreateRequest" },
        @{ role = "notebook"; probe = $NotebookPath; switchName = "AutoRespond" },
        @{ role = "desktop"; probe = $DesktopPath; switchName = "Acknowledge" }
    )
    $codes = @()
    foreach ($step in $steps) {
        $args = @("-Mode", "loop", "-ShareRoot", $ShareRoot, "-SelfRole", $step.role, "-ProbeFile", $step.probe, "-TaskId", $TaskId, ("-" + $step.switchName))
        $result = Invoke-CompareScript -Arguments $args
        $codes += $result.exitCode
    }
    return ,$codes
}

New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
try {
    foreach ($scriptPath in @($tool, $compare, $runAll, $PSCommandPath)) {
        $tokens = $null
        $errors = $null
        [Management.Automation.Language.Parser]::ParseFile($scriptPath, [ref]$tokens, [ref]$errors) | Out-Null
        Assert-True ($errors.Count -eq 0) "parser errors in $scriptPath"
    }

    Assert-True (Test-Path -LiteralPath $runAll -PathType Leaf) "run-all wrapper missing: $runAll"

    foreach ($sameCase in @(
        @{ name = "same-hostname"; desktopHost = "SYNTH-SAME"; notebookHost = "SYNTH-SAME"; desktopFingerprint = "desktop0001"; notebookFingerprint = "notebook0001"; reason = "same_hostname" },
        @{ name = "same-fingerprint"; desktopHost = "SYNTH-DESKTOP"; notebookHost = "SYNTH-NOTEBOOK"; desktopFingerprint = "same0001"; notebookFingerprint = "same0001"; reason = "same_fingerprint" }
    )) {
        $sameRoot = Join-Path $testRoot $sameCase.name
        $sameDesktop = Join-Path $sameRoot "desktop.json"
        $sameNotebook = Join-Path $sameRoot "notebook.json"
        Write-TestJson -Path $sameDesktop -Object (New-TestProbe -Role desktop -Hostname $sameCase.desktopHost -Fingerprint $sameCase.desktopFingerprint)
        Write-TestJson -Path $sameNotebook -Object (New-TestProbe -Role notebook -Hostname $sameCase.notebookHost -Fingerprint $sameCase.notebookFingerprint)
        $sameOut = Join-Path $sameRoot "out"
        $sameResult = Invoke-CompareScript -Arguments @("-DesktopProbe", $sameDesktop, "-NotebookProbe", $sameNotebook, "-OutputDir", $sameOut)
        $sameJson = Get-ChildItem -LiteralPath $sameOut -File -Filter "compare_*.json" | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
        $sameObject = Get-Content -LiteralPath $sameJson.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
        Assert-True ($sameResult.exitCode -ne 0) "$($sameCase.name) must fail"
        Assert-True ($sameObject.status -eq "INVALID_SAME_NODE") "$($sameCase.name) status"
        Assert-True ($sameObject.reason -eq $sameCase.reason) "$($sameCase.name) reason"
        Assert-True (@($sameObject.rows).Count -eq 0) "$($sameCase.name) must not emit normal rows"
    }

    $staleRoot = Join-Path $testRoot "stale"
    $staleDesktop = Join-Path $staleRoot "desktop.json"
    $staleNotebook = Join-Path $staleRoot "notebook.json"
    $staleAt = (Get-Date).AddMinutes(-75)
    Write-TestJson -Path $staleDesktop -Object (New-TestProbe -Role desktop -Hostname "STALE-DESKTOP" -Fingerprint "staledesktop0001" -CollectedAt $staleAt)
    Write-TestJson -Path $staleNotebook -Object (New-TestProbe -Role notebook -Hostname "STALE-NOTEBOOK" -Fingerprint "stalenotebook0001" -CollectedAt $staleAt)
    $staleOut = Join-Path $staleRoot "out"
    $staleResult = Invoke-CompareScript -Arguments @("-DesktopProbe", $staleDesktop, "-NotebookProbe", $staleNotebook, "-OutputDir", $staleOut, "-StaleMinutes", "60")
    $staleJson = Get-ChildItem -LiteralPath $staleOut -File -Filter "compare_*.json" | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    $staleObject = Get-Content -LiteralPath $staleJson.FullName -Raw -Encoding UTF8 | ConvertFrom-Json
    $statusRow = @($staleObject.rows | Where-Object { $_.metric -eq "상태" }) | Select-Object -First 1
    Assert-True ($staleResult.exitCode -eq 0) "stale probes remain comparable"
    Assert-True ($statusRow.desktop -eq "stale" -and $statusRow.notebook -eq "stale") "stale classification"

    $loopRoot = Join-Path $testRoot "loop"
    $shareRoot = Join-Path $loopRoot "share"
    $desktop = Join-Path $loopRoot "desktop.json"
    $notebook = Join-Path $loopRoot "notebook.json"
    Write-TestJson -Path $desktop -Object (New-TestProbe -Role desktop -Hostname "SYNTH-DESKTOP" -Fingerprint "desktop0001")
    Write-TestJson -Path $notebook -Object (New-TestProbe -Role notebook -Hostname "SYNTH-NOTEBOOK" -Fingerprint "notebook0001")
    Seed-ShareNodes -ShareRoot $shareRoot -DesktopPath $desktop -NotebookPath $notebook
    $cycle1 = @(Invoke-LoopCycle -ShareRoot $shareRoot -DesktopPath $desktop -NotebookPath $notebook -TaskId "contract-loop")
    $state1 = Get-LoopState -ShareRoot $shareRoot
    $cycle2 = @(Invoke-LoopCycle -ShareRoot $shareRoot -DesktopPath $desktop -NotebookPath $notebook -TaskId "contract-loop")
    $state2 = Get-LoopState -ShareRoot $shareRoot
    Assert-True (($cycle1 | Where-Object { $_ -ne 0 }).Count -eq 0) "first loop cycle exit codes"
    Assert-True (($cycle2 | Where-Object { $_ -ne 0 }).Count -eq 0) "second loop cycle exit codes"
    Assert-True (($state1.pending -join ",") -eq "0,0,0") "first loop pending counts"
    Assert-True (($state2.pending -join ",") -eq "0,0,0") "second loop pending counts"
    Assert-True (($state1.messageCounts -join ",") -eq ($state2.messageCounts -join ",")) "message idempotency"
    Assert-True ($state1.events.Count -eq $state2.events.Count) "timeline idempotency"
    Assert-True ($state2.invalidLines -eq 0) "NDJSON validity"
    Assert-True (@($state2.events | Where-Object { -not $_.timestamp -or -not $_.role -or -not $_.correlation_id -or -not $_.result -or $null -eq $_.duration_ms }).Count -eq 0) "timeline metadata"
    $cursor = -1
    foreach ($required in @("PUBLISHED", "DISCOVERED", "PROBED", "REQUESTED", "RESPONDED", "ACKNOWLEDGED", "RESOLVED")) {
        $next = -1
        for ($i = $cursor + 1; $i -lt $state2.events.Count; $i++) { if ($state2.events[$i].type -eq $required) { $next = $i; break } }
        Assert-True ($next -gt $cursor) "timeline order missing $required"
        $cursor = $next
    }

    $badMessage = Join-Path $shareRoot "messages\requests\partial-write.json"
    [IO.File]::WriteAllText($badMessage, '{"message_id":', [Text.UTF8Encoding]::new($false))
    $malformedResult = Invoke-CompareScript -Arguments @("-Mode", "loop", "-ShareRoot", $shareRoot, "-SelfRole", "desktop", "-TaskId", "contract-loop")
    Assert-True ($malformedResult.exitCode -eq 0) "malformed message must be quarantined without stopping loop"
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $shareRoot "quarantine") -File -Filter "*partial-write.json" -ErrorAction SilentlyContinue).Count -eq 1) "malformed quarantine"

    $runAllRoot = Join-Path $testRoot "run-all"
    $runAllOutput = Join-Path $runAllRoot "output"
    $runAllShare = Join-Path $runAllRoot "share"
    $runAllLog = @(& pwsh -NoProfile -File $runAll -TestMode -DesktopProbe $desktop -NotebookProbe $notebook -OutputRoot $runAllOutput -ShareRoot $runAllShare -TaskId "contract-run-all" 2>&1)
    Assert-True ($LASTEXITCODE -eq 0) "run-all test mode exit code"
    $runAllSummaryPath = Join-Path $runAllOutput "device-probe-run-all-summary.json"
    $runAllSummary = Get-Content -LiteralPath $runAllSummaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-True ($runAllSummary.overallExitCode -eq 0) "run-all summary exit code"
    Assert-True (($runAllSummary.stages.name -join ",") -eq "collect,publish,compare,loop,summary") "run-all stage order"
    Assert-True (@($runAllSummary.stages | Where-Object { $_.exitCode -ne 0 }).Count -eq 0) "run-all stage results"
    $runAllState1 = Get-LoopState -ShareRoot $runAllShare
    $runAllLog2 = @(& pwsh -NoProfile -File $runAll -TestMode -DesktopProbe $desktop -NotebookProbe $notebook -OutputRoot $runAllOutput -ShareRoot $runAllShare -TaskId "contract-run-all" 2>&1)
    $runAllState2 = Get-LoopState -ShareRoot $runAllShare
    Assert-True ($LASTEXITCODE -eq 0) "run-all second execution exit code"
    Assert-True (($runAllState2.pending -join ",") -eq "0,0,0") "run-all second execution pending counts"
    Assert-True (($runAllState1.messageCounts -join ",") -eq ($runAllState2.messageCounts -join ",")) "run-all message idempotency"
    Assert-True ($runAllState1.events.Count -eq $runAllState2.events.Count) "run-all timeline idempotency"

    $disconnectedRoot = Join-Path $testRoot "share-path-is-file"
    [IO.File]::WriteAllText($disconnectedRoot, "not-a-directory", [Text.UTF8Encoding]::new($false))
    $disconnectedOutput = Join-Path $testRoot "disconnected-output"
    $disconnectedLog = @(& pwsh -NoProfile -File $runAll -TestMode -DesktopProbe $desktop -NotebookProbe $notebook -OutputRoot $disconnectedOutput -ShareRoot $disconnectedRoot -TaskId "contract-disconnected" 2>&1)
    $disconnectedSummary = Get-Content -LiteralPath (Join-Path $disconnectedOutput "device-probe-run-all-summary.json") -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-True ($LASTEXITCODE -ne 0) "unavailable share path must return nonzero"
    Assert-True ($disconnectedSummary.overallExitCode -ne 0) "unavailable share summary status"
    Assert-True (@($disconnectedSummary.stages | Where-Object { $_.exitCode -ne 0 }).Count -gt 0) "unavailable share must name failed stages"

    Write-Host ("[device-probe-tests] PASS root={0} loopEvents={1} runAllSummary={2}" -f $testRoot, $state2.events.Count, $runAllSummaryPath)
} finally {
    if (-not $KeepArtifacts -and (Test-Path -LiteralPath $testRoot)) {
        $tempPrefix = ([IO.Path]::GetFullPath($env:TEMP)).TrimEnd('\') + '\'
        $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
        if (-not $resolvedTestRoot.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase) -or
            -not (Split-Path -Leaf $resolvedTestRoot).StartsWith("awx-device-probe-tests-", [StringComparison]::Ordinal)) {
            throw "unsafe-test-cleanup-path"
        }
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
