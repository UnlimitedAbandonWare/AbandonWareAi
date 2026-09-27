param(
    [int]$Port = 18166,
    [int]$Iterations = 3,
    [int]$DurationMinutes = 0,
    [int]$RequestBudgetMs = 15000,
    [int]$CurlWindowSeconds = 45,
    [int]$ListenerTimeoutSeconds = 240,
    [int]$MaxTokens = 96,
    [int]$Seed = 0,
    [int]$RecentWindowSize = 5,
    [string]$ProbeId = "",
    [string]$ModelOverride = "",
    [string]$ReplayFromSummaryPath = "",
    [string]$RuntimeLogPath = "",
    [int]$MaxProofTailBytes = 1048576,
    [string]$OutDir = "var\codex-smoke\chat-ui-vibe-soak",
    [switch]$CloseConflictingListener,
    [switch]$StopOnRecentBelowTarget,
    [switch]$SkipListenerStart,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($env:AWX_AGENT_HOST)) { $env:AWX_AGENT_HOST = 'desktop' }
. (Join-Path $PSScriptRoot 'agent_api_spend_guard.ps1')
if (-not [string]::IsNullOrWhiteSpace($ModelOverride)) {
  if (-not (Assert-AgentSpendAllow -Purpose 'soak' -Provider 'llm' -Model $ModelOverride -Caller 'chat_ui_vibe_soak.ps1' -ProbeId $ProbeId -ExplicitPaid:([bool]($env:AWX_AGENT_ALLOW_PAID_MODELS -match '^(1|true)
$PSNativeCommandUseErrorActionPreference = $false
Add-Type -AssemblyName System.Net.Http

function Resolve-OutputDir {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Get-Location).Path $Path)
}

function Get-Hash12 {
    param([string]$Value)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$Value)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace("-", "").ToLowerInvariant()).Substring(0, 12)
    } finally {
        $sha.Dispose()
    }
}

function Get-RedactedRequestHash {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "hash:unknown" }
    return "hash:" + (Get-Hash12 ([string]$Value).Trim())
}

function Resolve-RuntimeProofLogPath {
    param(
        [string]$ExplicitPath,
        [object]$ListenerResult
    )
    $candidate = $ExplicitPath
    if ([string]::IsNullOrWhiteSpace($candidate) -and $null -ne $ListenerResult) {
        try { $candidate = [string]$ListenerResult.parsed.listener.outLog } catch { $candidate = "" }
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) { return "" }
    if ([System.IO.Path]::IsPathRooted($candidate)) { return $candidate }
    return (Join-Path (Get-Location).Path $candidate)
}

function Get-RuntimeLogLength {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return -1L
    }
    try { return [long](Get-Item -LiteralPath $Path -ErrorAction Stop).Length } catch { return -1L }
}

function Get-ProofToken {
    param([string]$Line, [string]$Name)
    $match = [regex]::Match($Line, "(?:^|\s)" + [regex]::Escape($Name) + "=([^\s]+)")
    if ($match.Success) { return [string]$match.Groups[1].Value }
    return ""
}

function Convert-ProofBoolean {
    param([string]$Value)
    if ([string]$Value -eq "true") { return $true }
    if ([string]$Value -eq "false") { return $false }
    return $null
}

function Get-RequestProofSummary {
    param(
        [string]$LogPath,
        [long]$Offset,
        [string]$RequestHash,
        [int]$MaxTailBytes = 1048576
    )

    $empty = [ordered]@{
        requestHash = $RequestHash
        proofRowCount = 0
        attemptTotal = 0
        attemptDropped = 0
        distinctPromptHashCount = 0
        distinctOptionsHashCount = 0
        responseHashObservedCount = 0
        modelAdapterAttemptObservedCount = 0
        clientHttpExchangeObservedCount = 0
        clientHttpResponseObservedCount = 0
        providerAttemptObservedCount = 0
        wireAttemptObservedCount = 0
        responseObservedCount = 0
        correlationStatus = "evidence_needed"
        rawRequestIdStored = $false
        rawProofLinesStored = $false
    }
    if ([string]::IsNullOrWhiteSpace($LogPath) -or $Offset -lt 0L -or
        -not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
        return $empty
    }

    $tailText = ""
    try {
        $stream = [System.IO.File]::Open($LogPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete)
        try {
            if ($stream.Length -lt $Offset) { return $empty }
            $tailLength = [long]$stream.Length - $Offset
            if ($tailLength -gt [Math]::Max(1, $MaxTailBytes)) { return $empty }
            if ($tailLength -le 0L) { return $empty }
            [void]$stream.Seek($Offset, [System.IO.SeekOrigin]::Begin)
            $buffer = New-Object byte[] ([int]$tailLength)
            $readTotal = 0
            while ($readTotal -lt $buffer.Length) {
                $readNow = $stream.Read($buffer, $readTotal, $buffer.Length - $readTotal)
                if ($readNow -le 0) { break }
                $readTotal += $readNow
            }
            $tailText = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $readTotal)
        } finally {
            $stream.Dispose()
        }
    } catch {
        return $empty
    }

    $rows = New-Object System.Collections.Generic.List[object]
    $matchingMalformed = 0
    $reportedAttemptTotal = 0
    $reportedAttemptDropped = 0
    $appendOrderInvalid = $false
    $perRowObservationInvalid = $false
    $hasPreviousAttemptCounts = $false
    $previousAttemptTotal = 0
    $previousAttemptDropped = 0
    $hasPreviousAcceptedSequence = $false
    $previousAcceptedSequence = 0
    foreach ($line in ($tailText -split "`r?`n")) {
        if (-not $line.Contains("[LLM_REQUEST_PROOF]")) { continue }
        $lineRequestHash = Get-ProofToken -Line $line -Name "requestHash"
        if ($lineRequestHash -ne $RequestHash) { continue }
        $sequenceText = Get-ProofToken -Line $line -Name "sequence"
        $attemptTotalText = Get-ProofToken -Line $line -Name "attemptTotal"
        $attemptDroppedText = Get-ProofToken -Line $line -Name "attemptDropped"
        $promptHash = Get-ProofToken -Line $line -Name "promptHash"
        $optionsHash = Get-ProofToken -Line $line -Name "optionsHash"
        $responseHash = Get-ProofToken -Line $line -Name "responseHash"
        $sequence = 0
        $attemptTotal = 0
        $attemptDropped = 0
        $rowAcceptedText = Get-ProofToken -Line $line -Name "rowAccepted"
        $rowAccepted = if ([string]::IsNullOrWhiteSpace($rowAcceptedText)) {
            $true
        } else {
            Convert-ProofBoolean $rowAcceptedText
        }
        $validAttemptCounts = [int]::TryParse($attemptTotalText, [ref]$attemptTotal) -and
            $attemptTotal -ge 0 -and
            [int]::TryParse($attemptDroppedText, [ref]$attemptDropped) -and
            $attemptDropped -ge 0
        if ($null -eq $rowAccepted -or -not $validAttemptCounts) {
            $matchingMalformed += 1
            continue
        }
        if ($hasPreviousAttemptCounts -and
            ($attemptTotal -lt $previousAttemptTotal -or $attemptDropped -lt $previousAttemptDropped)) {
            $appendOrderInvalid = $true
        }
        $previousAttemptTotal = $attemptTotal
        $previousAttemptDropped = $attemptDropped
        $hasPreviousAttemptCounts = $true
        $reportedAttemptTotal = [Math]::Max($reportedAttemptTotal, $attemptTotal)
        $reportedAttemptDropped = [Math]::Max($reportedAttemptDropped, $attemptDropped)
        if ($rowAccepted -eq $false) {
            continue
        }
        $adapterAttempt = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "adapterAttempt")
        $clientHttpExchange = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "clientHttpExchange")
        $clientHttpResponse = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "clientHttpResponse")
        $providerAttempt = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "providerAttempt")
        $wireAttempt = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "wireAttempt")
        $responseObserved = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "responseObserved")
        $hashPattern = '^(?:hash:[0-9a-f]{12}|sha256:[0-9a-f]{64})$'
        $valid = [int]::TryParse($sequenceText, [ref]$sequence) -and $sequence -gt 0 -and
            $promptHash -match $hashPattern -and $optionsHash -match $hashPattern -and
            ($responseHash -match $hashPattern -or $responseHash -eq "hash:unknown") -and
            $null -ne $adapterAttempt -and $null -ne $clientHttpExchange -and
            $null -ne $clientHttpResponse -and $null -ne $providerAttempt -and
            $null -ne $wireAttempt -and $null -ne $responseObserved
        if (-not $valid) {
            $matchingMalformed += 1
            continue
        }
        if ($hasPreviousAcceptedSequence -and $sequence -le $previousAcceptedSequence) {
            $appendOrderInvalid = $true
        }
        $previousAcceptedSequence = $sequence
        $hasPreviousAcceptedSequence = $true
        $responseHashObserved = $responseHash -ne "hash:unknown"
        if (($clientHttpResponse -and -not $clientHttpExchange) -or
            ($clientHttpExchange -and -not $adapterAttempt) -or
            ([bool]$responseObserved -ne $responseHashObserved)) {
            $perRowObservationInvalid = $true
        }
        $rows.Add([pscustomobject]@{
            sequence = $sequence
            attemptTotal = $attemptTotal
            attemptDropped = $attemptDropped
            promptHash = $promptHash
            optionsHash = $optionsHash
            responseHash = $responseHash
            adapterAttempt = [bool]$adapterAttempt
            clientHttpExchange = [bool]$clientHttpExchange
            clientHttpResponse = [bool]$clientHttpResponse
            providerAttempt = [bool]$providerAttempt
            wireAttempt = [bool]$wireAttempt
            responseObserved = [bool]$responseObserved
        }) | Out-Null
    }

    $validRows = @($rows.ToArray())
    $empty.proofRowCount = $validRows.Count
    $empty.attemptTotal = $reportedAttemptTotal
    $empty.attemptDropped = $reportedAttemptDropped
    if ($validRows.Count -gt 0) {
        $empty.distinctPromptHashCount = @($validRows.promptHash | Sort-Object -Unique).Count
        $empty.distinctOptionsHashCount = @($validRows.optionsHash | Sort-Object -Unique).Count
        $empty.responseHashObservedCount = @($validRows | Where-Object { $_.responseHash -ne "hash:unknown" }).Count
        $empty.modelAdapterAttemptObservedCount = @($validRows | Where-Object { $_.adapterAttempt }).Count
        $empty.clientHttpExchangeObservedCount = @($validRows | Where-Object { $_.clientHttpExchange }).Count
        $empty.clientHttpResponseObservedCount = @($validRows | Where-Object { $_.clientHttpResponse }).Count
        $empty.providerAttemptObservedCount = @($validRows | Where-Object { $_.providerAttempt }).Count
        $empty.wireAttemptObservedCount = @($validRows | Where-Object { $_.wireAttempt }).Count
        $empty.responseObservedCount = @($validRows | Where-Object { $_.responseObserved }).Count
    }

    $uniqueSequenceCount = @($validRows.sequence | Sort-Object -Unique).Count
    $countAgreement = $validRows.Count -gt 0 -and
        $empty.attemptTotal -eq ($validRows.Count + $empty.attemptDropped)
    $monotonicCounts = $empty.clientHttpResponseObservedCount -le $empty.clientHttpExchangeObservedCount -and
        $empty.clientHttpExchangeObservedCount -le $empty.modelAdapterAttemptObservedCount
    $hasUnknownResponse = @($validRows | Where-Object { $_.responseHash -eq "hash:unknown" }).Count -gt 0
    $empty.correlationStatus = if ($validRows.Count -eq 0) {
        if ($matchingMalformed -gt 0) { "partial" } else { "evidence_needed" }
    } elseif ($uniqueSequenceCount -ne $validRows.Count -or -not $monotonicCounts -or
        $appendOrderInvalid -or $perRowObservationInvalid) {
        "ambiguous"
    } elseif ($matchingMalformed -gt 0 -or -not $countAgreement -or $hasUnknownResponse -or
        $empty.attemptDropped -gt 0) {
        "partial"
    } else {
        "verified"
    }
    return $empty
}

function Get-SafeSignalValue {
    param([object]$Value, [int]$MaxLength = 80)
    if ($null -eq $Value) { return "" }
    $safe = [regex]::Replace(([string]$Value).Trim(), "[^A-Za-z0-9._:/-]+", "_")
    if ($safe.Length -gt $MaxLength) { return $safe.Substring(0, $MaxLength) }
    return $safe
}

function Get-CountField {
    param([object]$Value, [string]$Name)
    if ($null -eq $Value -or -not ($Value.PSObject.Properties.Name -contains $Name)) { return 0L }
    try { return [Math]::Max(0L, [long]$Value.$Name) } catch { return 0L }
}

function Get-ChatUsageSnapshot {
    param([int]$Port)

    $empty = [ordered]@{
        observed = $false
        counterEpochStartedAtMs = 0L
        counterScope = "unavailable"
        wireAttemptCoverage = "unavailable"
        attempts = 0L
        responseReceived = 0L
        failedBeforeResponse = 0L
        timedOut = 0L
        cancelled = 0L
        providerUsageObserved = 0L
        providerUsageMissing = 0L
    }
    try {
        $heartbeat = Invoke-RestMethod `
            -Uri "http://127.0.0.1:$Port/api/chat/ui-heartbeat" `
            -Method Get `
            -TimeoutSec 2 `
            -ErrorAction Stop
        $usage = $heartbeat.debugAiMetrics.chatUsage
        if ($null -eq $usage -or $null -eq $usage.modelInvocations) { return $empty }
        $model = $usage.modelInvocations
        return [ordered]@{
            observed = ($usage.observed -eq $true)
            counterEpochStartedAtMs = Get-CountField -Value $usage -Name "counterEpochStartedAtMs"
            counterScope = Get-SafeSignalValue -Value $usage.counterScope
            wireAttemptCoverage = Get-SafeSignalValue -Value $usage.wireAttemptCoverage
            attempts = Get-CountField -Value $model -Name "attempts"
            responseReceived = Get-CountField -Value $model -Name "responseReceived"
            failedBeforeResponse = Get-CountField -Value $model -Name "failedBeforeResponse"
            timedOut = Get-CountField -Value $model -Name "timedOut"
            cancelled = Get-CountField -Value $model -Name "cancelled"
            providerUsageObserved = Get-CountField -Value $model -Name "providerUsageObservedAttemptCount"
            providerUsageMissing = Get-CountField -Value $model -Name "providerUsageMissingAttemptCount"
        }
    } catch {
        return $empty
    }
}

function Test-ProbeDirectDebugShortCircuitRisk {
    param([string]$Message)

    $lower = if ($null -eq $Message) { "" } else { $Message.ToLowerInvariant() }
    $debugSubject = @("debug", "trace", "heartbeat", "browser", "computer", "supabase", "matrix", "evidence_needed") |
        Where-Object { $lower.Contains($_) } |
        Select-Object -First 1
    $statusIntent = @("status", "state", "health", "diagnostic", "inspect", "report", "check", "show", "tell", "current", "stale", "operator", "why") |
        Where-Object { $lower.Contains($_) } |
        Select-Object -First 1
    $suppressed = @("without debug", "no debug", "debug-free") |
        Where-Object { $lower.Contains($_) } |
        Select-Object -First 1
    return $null -ne $debugSubject -and $null -ne $statusIntent -and $null -eq $suppressed
}

function Write-JsonFile {
    param([string]$Path, [hashtable]$Data)
    $Data | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Write-NdjsonEvent {
    param([string]$Path, [hashtable]$Data)
    ($Data | ConvertTo-Json -Depth 8 -Compress) | Add-Content -LiteralPath $Path -Encoding UTF8
}

function Get-NextProbeFromCoverageBag {
    param(
        [object[]]$ProbeDeck,
        [System.Random]$Random,
        [ref]$CoverageBag
    )
    $bag = @($CoverageBag.Value)
    if ($bag.Count -eq 0) {
        $bag = @($ProbeDeck)
    }
    $index = $Random.Next($bag.Count)
    $selected = $bag[$index]
    $remaining = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $bag.Count; $i++) {
        if ($i -ne $index) {
            $remaining.Add($bag[$i]) | Out-Null
        }
    }
    $CoverageBag.Value = $remaining.ToArray()
    return $selected
}

function Add-ProbeCoverage {
    param([hashtable]$Coverage, [string]$ProbeId)
    if ([string]::IsNullOrWhiteSpace($ProbeId)) { return }
    if (-not $Coverage.ContainsKey($ProbeId)) { $Coverage[$ProbeId] = 0 }
    $Coverage[$ProbeId] = [int]$Coverage[$ProbeId] + 1
}

function New-ProbeCoverageSummary {
    param([hashtable]$Coverage, [int]$DeckSize)
    return @{
        deckSize = $DeckSize
        uniqueProbeCount = $Coverage.Keys.Count
        counts = $Coverage
    }
}

function New-ReliabilitySummary {
    param(
        [int]$IterationsCompleted,
        [int]$TerminalSeenCount,
        [int]$TargetReliabilityPct = 90
    )
    if ($IterationsCompleted -le 0) {
        return @{
            status = "not-measured"
            reliabilityScorePct = 0
            targetReliabilityPct = 90
            meetsNineXTarget = $false
        }
    }
    $score = [Math]::Round(($TerminalSeenCount * 100.0) / $IterationsCompleted, 2)
    return @{
        status = if ($score -ge $TargetReliabilityPct) { "meets-target" } else { "below-target" }
        reliabilityScorePct = $score
        targetReliabilityPct = 90
        meetsNineXTarget = ($score -ge $TargetReliabilityPct)
    }
}

function New-PerProbeReliabilitySummary {
    param(
        [object[]]$ProbeEvents,
        [object[]]$ProbeDeck,
        [int]$TargetReliabilityPct = 90
    )
    $result = @{}
    foreach ($probe in $ProbeDeck) {
        $probeId = [string]$probe.id
        $eventsForProbe = @($ProbeEvents | Where-Object { [string]$_.probeId -eq $probeId })
        $attempts = $eventsForProbe.Count
        $terminalSeen = @($eventsForProbe | Where-Object { $_.terminalSeen -eq $true }).Count
        $reliableSuccess = @($eventsForProbe | Where-Object { Test-ProbeEventReliable -Event $_ }).Count
        $summary = New-ReliabilitySummary -IterationsCompleted $attempts -TerminalSeenCount $reliableSuccess -TargetReliabilityPct $TargetReliabilityPct
        $summary["probeId"] = $probeId
        $summary["attemptCount"] = $attempts
        $summary["terminalSeenCount"] = $terminalSeen
        $summary["reliableSuccessCount"] = $reliableSuccess
        $result[$probeId] = $summary
    }
    return $result
}

function Get-EventField {
    param(
        [object]$Event,
        [string]$Name
    )
    if ($null -eq $Event) { return $null }
    if ($Event -is [System.Collections.IDictionary]) { return $Event[$Name] }
    return $Event.$Name
}

function Test-ProbeEventReliable {
    param([object]$Event)
    if ((Get-EventField -Event $Event -Name "terminalSeen") -ne $true) { return $false }
    if ([string](Get-EventField -Event $Event -Name "transportCode") -ne "success") { return $false }
    if ([int](Get-EventField -Event $Event -Name "statusCode") -ge 400) { return $false }
    if ([string](Get-EventField -Event $Event -Name "classification") -eq "terminal_error_seen") { return $false }
    if (@(Get-EventField -Event $Event -Name "eventTypes") -contains "error") { return $false }
    if (@(Get-EventField -Event $Event -Name "payloadTypes") -contains "error") { return $false }
    $constraintRequired = (Get-EventField -Event $Event -Name "constraintIntegrityRequired") -eq $true
    if ($constraintRequired) {
        return [string](Get-EventField -Event $Event -Name "constraintIntegrityStatus") -eq "pass"
    }
    return $true
}

function New-ModelProofSummary {
    param([object[]]$ProbeEvents)

    $events = @($ProbeEvents)
    $modelObservedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "modelFinalObserved") -eq $true
    }).Count
    $providerAttemptObservedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "providerAttemptObserved") -eq $true
    }).Count
    $modelAttemptObservedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "modelAttemptObserved") -eq $true
    }).Count
    $providerUsageObservedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "providerUsageObserved") -eq $true
    }).Count
    $fallbackDetectedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "fallbackDetected") -eq $true
    }).Count
    $verifiedCount = @($events | Where-Object {
        [string](Get-EventField -Event $_ -Name "modelProofStatus") -eq "model-and-provider-observed"
    }).Count
    return @{
        observedCount = $events.Count
        modelObservedCount = $modelObservedCount
        modelAttemptObservedCount = $modelAttemptObservedCount
        providerAttemptObservedCount = $providerAttemptObservedCount
        providerUsageObservedCount = $providerUsageObservedCount
        fallbackDetectedCount = $fallbackDetectedCount
        verifiedCount = $verifiedCount
        status = if ($events.Count -eq 0) {
            "not-measured"
        } elseif ($verifiedCount -eq $events.Count) {
            "verified"
        } else {
            "evidence-needed"
        }
    }
}

function New-RecentWindowReliabilitySummary {
    param(
        [object[]]$ProbeEvents,
        [int]$WindowSize = 5,
        [int]$TargetReliabilityPct = 90
    )

    $events = @($ProbeEvents | Where-Object { $null -ne $_ })
    if ($events.Count -le 0) {
        return @{
            status = "not-measured"
            windowSize = $WindowSize
            observedCount = 0
            terminalSeenCount = 0
            reliabilityScorePct = 0
            targetReliabilityPct = 90
            meetsNineXTarget = $false
            probeIds = @()
            classificationCounts = @{}
            worstRecentProbeId = ""
            nextRecentFocus = "run_live_soak"
        }
    }

    $effectiveWindowSize = if ($WindowSize -le 0) { $events.Count } else { $WindowSize }
    $windowEvents = if ($events.Count -gt $effectiveWindowSize) {
        @($events | Select-Object -Last $effectiveWindowSize)
    } else {
        @($events)
    }
    $observedCount = @($windowEvents).Count
    $terminalSeen = @($windowEvents | Where-Object { (Get-EventField -Event $_ -Name "terminalSeen") -eq $true }).Count
    $reliableSuccess = @($windowEvents | Where-Object { Test-ProbeEventReliable -Event $_ }).Count
    $summary = New-ReliabilitySummary -IterationsCompleted $observedCount -TerminalSeenCount $reliableSuccess -TargetReliabilityPct $TargetReliabilityPct
    $classificationCounts = @{}
    foreach ($event in $windowEvents) {
        $classification = [string](Get-EventField -Event $event -Name "classification")
        if ([string]::IsNullOrWhiteSpace($classification)) { $classification = "unknown" }
        if (-not $classificationCounts.ContainsKey($classification)) { $classificationCounts[$classification] = 0 }
        $classificationCounts[$classification] = [int]$classificationCounts[$classification] + 1
    }
    $badEvents = @($windowEvents | Where-Object { -not (Test-ProbeEventReliable -Event $_) })
    $worstRecentProbeId = if ($badEvents.Count -gt 0) {
        [string](Get-EventField -Event ($badEvents | Select-Object -Last 1) -Name "probeId")
    } else {
        ""
    }

    return @{
        status = [string]$summary.status
        windowSize = $effectiveWindowSize
        observedCount = $observedCount
        terminalSeenCount = $terminalSeen
        reliableSuccessCount = $reliableSuccess
        reliabilityScorePct = [double]$summary.reliabilityScorePct
        targetReliabilityPct = 90
        meetsNineXTarget = [bool]$summary.meetsNineXTarget
        probeIds = @($windowEvents | ForEach-Object { [string](Get-EventField -Event $_ -Name "probeId") })
        classificationCounts = $classificationCounts
        worstRecentProbeId = $worstRecentProbeId
        nextRecentFocus = if ($summary.meetsNineXTarget -eq $true) { "continue_long_soak" } else { "replay_recent_worst_probe" }
    }
}

function New-FailureFocusSummary {
    param([hashtable]$PerProbeReliability)

    $measured = @()
    foreach ($probeId in $PerProbeReliability.Keys) {
        $item = $PerProbeReliability[$probeId]
        if ([int]$item["attemptCount"] -gt 0) {
            $measured += $item
        }
    }

    if ($measured.Count -eq 0) {
        return @{
            status = "no-measured-probes"
            belowTargetProbeIds = @()
            worstProbeId = ""
            worstReliabilityScorePct = 0
            nextFailureFocus = "run_live_soak"
        }
    }

    $belowTarget = @($measured | Where-Object { $_["meetsNineXTarget"] -ne $true })
    $worst = $measured |
        Sort-Object @{ Expression = { [double]$_["reliabilityScorePct"] }; Ascending = $true },
                    @{ Expression = { [string]$_["probeId"] }; Ascending = $true } |
        Select-Object -First 1
    $worstProbeId = if ($null -ne $worst) { [string]$worst["probeId"] } else { "" }

    return @{
        status = if ($belowTarget.Count -gt 0) { "below-target" } else { "no-failing-probes" }
        belowTargetProbeIds = @($belowTarget | ForEach-Object { [string]$_["probeId"] })
        worstProbeId = $worstProbeId
        worstReliabilityScorePct = if ($null -ne $worst) { [double]$worst["reliabilityScorePct"] } else { 0 }
        nextFailureFocus = if ($belowTarget.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($worstProbeId)) {
            "inspect_events_for_$worstProbeId"
        } else {
            "continue_long_soak"
        }
    }
}

function New-RagSignalSummary {
    param(
        [string]$Raw,
        [bool]$RequiresOfficialEvidence = $false
    )
    $text = if ($null -eq $Raw) { "" } else { [string]$Raw }
    $lower = $text.ToLowerInvariant()
    $officialDomainMentionCount = ([regex]::Matches($lower, "developers\.openai\.com|platform\.openai\.com|openai\.com/changelog|supabase\.com/docs|supabase\.com/changelog")).Count
    $offDomainCommunityMentionCount = ([regex]::Matches($lower, "github\.com|medium\.com|reddit|velog|tistory|kongju\.ac\.kr|hrd4u\.or\.kr")).Count
    $fakeCommandMentionCount = ([regex]::Matches($lower, "run_browser_local_ui_smoke|run_computer_use_lightweight_smoke|inspect_supabase_shadow_snapshot")).Count
    $containsEvidenceNeeded = $lower.Contains("evidence_needed")
    $officialEvidenceNeeded = $RequiresOfficialEvidence -and
        $officialDomainMentionCount -le 0 -and
        $containsEvidenceNeeded
    $officialEvidenceMissing = $RequiresOfficialEvidence -and
        $officialDomainMentionCount -le 0 -and
        -not $containsEvidenceNeeded
    $externalLaneSplitSeen = $lower.Contains("supporting_evidence_missing") -or
        $lower.Contains("project_ref_missing") -or
        ($lower.Contains("browser") -and $lower.Contains("computer") -and $lower.Contains("supabase"))
    $status = if ($fakeCommandMentionCount -gt 0) {
        "fake-command-risk"
    } elseif ($offDomainCommunityMentionCount -gt 0) {
        "off-domain-community-risk"
    } elseif ($officialEvidenceMissing) {
        "official-evidence-missing"
    } elseif ($officialEvidenceNeeded) {
        "official-evidence-needed"
    } elseif ($containsEvidenceNeeded -or $externalLaneSplitSeen -or $officialDomainMentionCount -gt 0) {
        "rag-signal-observed"
    } else {
        "no-rag-signal"
    }
    return @{
        status = $status
        containsEvidenceNeeded = $containsEvidenceNeeded
        officialDomainMentionCount = $officialDomainMentionCount
        offDomainCommunityMentionCount = $offDomainCommunityMentionCount
        fakeCommandMentionCount = $fakeCommandMentionCount
        officialEvidenceNeeded = $officialEvidenceNeeded
        officialEvidenceMissing = $officialEvidenceMissing
        externalLaneSplitSeen = $externalLaneSplitSeen
    }
}

function New-RagSignalAggregateSummary {
    param([object[]]$ProbeEvents)
    $events = @($ProbeEvents | Where-Object { $null -ne $_ })
    if ($events.Count -le 0) {
        return @{
            status = "not-measured"
            observedCount = 0
            evidenceNeededSeenCount = 0
            externalLaneSplitSeenCount = 0
            officialDomainMentionCount = 0
            offDomainCommunityMentionCount = 0
            fakeCommandMentionCount = 0
            officialEvidenceNeededCount = 0
            officialEvidenceMissingCount = 0
            nextRagSignalFocus = "run_live_soak"
        }
    }
    $evidenceNeededSeenCount = 0
    $externalLaneSplitSeenCount = 0
    $officialDomainMentionCount = 0
    $offDomainCommunityMentionCount = 0
    $fakeCommandMentionCount = 0
    $officialEvidenceNeededCount = 0
    $officialEvidenceMissingCount = 0
    foreach ($event in $events) {
        if ((Get-EventField -Event $event -Name "containsEvidenceNeeded") -eq $true) { $evidenceNeededSeenCount += 1 }
        if ((Get-EventField -Event $event -Name "externalLaneSplitSeen") -eq $true) { $externalLaneSplitSeenCount += 1 }
        if ((Get-EventField -Event $event -Name "officialEvidenceNeeded") -eq $true) { $officialEvidenceNeededCount += 1 }
        if ((Get-EventField -Event $event -Name "officialEvidenceMissing") -eq $true) { $officialEvidenceMissingCount += 1 }
        $officialDomainMentionCount += [int](Get-EventField -Event $event -Name "officialDomainMentionCount")
        $offDomainCommunityMentionCount += [int](Get-EventField -Event $event -Name "offDomainCommunityMentionCount")
        $fakeCommandMentionCount += [int](Get-EventField -Event $event -Name "fakeCommandMentionCount")
    }
    $status = if ($fakeCommandMentionCount -gt 0) {
        "fake-command-risk"
    } elseif ($offDomainCommunityMentionCount -gt 0) {
        "off-domain-community-risk"
    } elseif ($officialEvidenceMissingCount -gt 0) {
        "official-evidence-missing"
    } elseif ($officialEvidenceNeededCount -gt 0) {
        "official-evidence-needed"
    } elseif ($evidenceNeededSeenCount -gt 0 -or $externalLaneSplitSeenCount -gt 0 -or $officialDomainMentionCount -gt 0) {
        "rag-signal-observed"
    } else {
        "no-rag-signal"
    }
    $nextFocus = if ($fakeCommandMentionCount -gt 0) {
        "replay_skill_lane_external_split"
    } elseif ($offDomainCommunityMentionCount -gt 0) {
        "replay_official_changelog_evidence"
    } elseif ($officialEvidenceMissingCount -gt 0) {
        "replay_official_changelog_evidence"
    } elseif ($officialEvidenceNeededCount -gt 0) {
        "inspect_official_search_quality_gate"
    } elseif ($status -eq "no-rag-signal") {
        "run_official_or_skill_probe"
    } else {
        "continue_long_soak"
    }
    return @{
        status = $status
        observedCount = $events.Count
        evidenceNeededSeenCount = $evidenceNeededSeenCount
        externalLaneSplitSeenCount = $externalLaneSplitSeenCount
        officialDomainMentionCount = $officialDomainMentionCount
        offDomainCommunityMentionCount = $offDomainCommunityMentionCount
        fakeCommandMentionCount = $fakeCommandMentionCount
        officialEvidenceNeededCount = $officialEvidenceNeededCount
        officialEvidenceMissingCount = $officialEvidenceMissingCount
        nextRagSignalFocus = $nextFocus
    }
}

function New-ReplayFailureCommand {
    param(
        [int]$Port,
        [string]$OutDir,
        [string]$ProbeId,
        [int]$Iterations = 5
    )
    $targetProbeId = if ([string]::IsNullOrWhiteSpace($ProbeId)) { "<probe_id>" } else { $ProbeId }
    return "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak.ps1 -Port $Port -Iterations $Iterations -DurationMinutes 5 -ProbeId $targetProbeId -CloseConflictingListener -OutDir $OutDir"
}

function New-LongRunCommand {
    param(
        [int]$Port,
        [string]$OutDir,
        [int]$RecentWindowSize
    )
    return "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak.ps1 -Port $Port -DurationMinutes 540 -Iterations 56 -RecentWindowSize $RecentWindowSize -StopOnRecentBelowTarget -CloseConflictingListener -OutDir $OutDir"
}

function Resolve-ReplayProbeIdFromSummary {
    param([string]$SummaryPath)

    if ([string]::IsNullOrWhiteSpace($SummaryPath)) { return "" }
    $resolvedSummaryPath = if ([System.IO.Path]::IsPathRooted($SummaryPath)) {
        $SummaryPath
    } else {
        Join-Path (Get-Location).Path $SummaryPath
    }
    if (-not (Test-Path -LiteralPath $resolvedSummaryPath)) {
        throw "ReplayFromSummaryPath not found: $resolvedSummaryPath"
    }

    $summary = Get-Content -Raw -LiteralPath $resolvedSummaryPath | ConvertFrom-Json
    $belowTarget = @($summary.failureFocus.belowTargetProbeIds | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    if ($belowTarget.Count -gt 0) { return [string]$belowTarget[0] }

    $worstProbeId = [string]$summary.failureFocus.worstProbeId
    if (-not [string]::IsNullOrWhiteSpace($worstProbeId)) { return $worstProbeId }

    $selectedProbeId = [string]$summary.selectedProbeId
    if (-not [string]::IsNullOrWhiteSpace($selectedProbeId)) { return $selectedProbeId }

    return ""
}

function New-AgentNextArtifact {
    param([hashtable]$Summary)

    $hasMeasuredFailure = [string]$Summary.failureFocus.status -eq "below-target" -or
        [string]$Summary.recentWindowReliability.status -eq "below-target"
    $recommendedCommand = if ($hasMeasuredFailure -and -not [string]::IsNullOrWhiteSpace([string]$Summary.replayFailureCommand)) {
        [string]$Summary.replayFailureCommand
    } elseif (-not [string]::IsNullOrWhiteSpace([string]$Summary.nextLongRunCommand)) {
        [string]$Summary.nextLongRunCommand
    } else {
        "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak.ps1 -Port $($Summary.port) -DurationMinutes 540 -Iterations 56 -CloseConflictingListener"
    }

    return @{
        schemaVersion = "awx.chat_ui_vibe_soak.agent_next.v1"
        generatedAt = (Get-Date).ToString("o")
        status = [string]$Summary.status
        ok = [bool]$Summary.ok
        port = [int]$Summary.port
        summaryPath = [string]$Summary.summaryPath
        eventsPath = [string]$Summary.eventsPath
        progressPath = [string]$Summary.progressPath
        stopReason = [string]$Summary.stopReason
        selectedProbeId = [string]$Summary.selectedProbeId
        replaySourcePath = [string]$Summary.replaySourcePath
        replaySourceProbeId = [string]$Summary.replaySourceProbeId
        nextHandoffAction = [string]$Summary.nextHandoffAction
        nextFailureFocus = [string]$Summary.failureFocus.nextFailureFocus
        failureFocusStatus = [string]$Summary.failureFocus.status
        worstProbeId = [string]$Summary.failureFocus.worstProbeId
        belowTargetProbeIds = @($Summary.failureFocus.belowTargetProbeIds | ForEach-Object { [string]$_ })
        recentWindowStatus = [string]$Summary.recentWindowReliability.status
        recentWindowObservedCount = [int]$Summary.recentWindowReliability.observedCount
        recentWindowWorstProbeId = [string]$Summary.recentWindowReliability.worstRecentProbeId
        recentWindowNextFocus = [string]$Summary.recentWindowReliability.nextRecentFocus
        ragSignalStatus = [string]$Summary.ragSignalSummary.status
        ragSignalObservedCount = [int]$Summary.ragSignalSummary.observedCount
        ragSignalEvidenceNeededSeenCount = [int]$Summary.ragSignalSummary.evidenceNeededSeenCount
        ragSignalOffDomainCommunityMentionCount = [int]$Summary.ragSignalSummary.offDomainCommunityMentionCount
        ragSignalFakeCommandMentionCount = [int]$Summary.ragSignalSummary.fakeCommandMentionCount
        ragSignalOfficialEvidenceNeededCount = [int]$Summary.ragSignalSummary.officialEvidenceNeededCount
        ragSignalOfficialEvidenceMissingCount = [int]$Summary.ragSignalSummary.officialEvidenceMissingCount
        nextRagSignalFocus = [string]$Summary.ragSignalSummary.nextRagSignalFocus
        replayFailureCommand = [string]$Summary.replayFailureCommand
        nextLongRunCommand = [string]$Summary.nextLongRunCommand
        recommendedCommand = $recommendedCommand
        rawPromptStored = [bool]$Summary.rawPromptStored
        rawStreamStored = [bool]$Summary.rawStreamStored
    }
}

function Write-AgentNextArtifact {
    param(
        [hashtable]$Summary,
        [string]$AgentNextPath,
        [string]$LatestAgentNextPath
    )

    $agentNext = New-AgentNextArtifact -Summary $Summary
    $agentNext["agentNextPath"] = $AgentNextPath
    $agentNext["latestAgentNextPath"] = $LatestAgentNextPath
    Write-JsonFile -Path $AgentNextPath -Data $agentNext
    Write-JsonFile -Path $LatestAgentNextPath -Data $agentNext
}

function Write-ProgressCheckpoint {
    param(
        [hashtable]$Progress,
        [string]$ProgressPath,
        [string]$LatestProgressPath
    )
    $Progress["progressPath"] = $ProgressPath
    $Progress["latestProgressPath"] = $LatestProgressPath
    Write-JsonFile -Path $ProgressPath -Data $Progress
    Write-JsonFile -Path $LatestProgressPath -Data $Progress
}

function Write-HandoffArtifacts {
    param(
        [hashtable]$Summary,
        [string]$LatestSummaryPath,
        [string]$HandoffPath
    )
    $Summary["latestSummaryPath"] = $LatestSummaryPath
    $Summary["handoffPath"] = $HandoffPath
    if (-not $Summary.ContainsKey("nextHandoffAction")) {
        $Summary["nextHandoffAction"] = if ([string]$Summary["status"] -eq "all-terminal") {
            "continue_long_soak"
        } elseif ([string]$Summary["status"] -eq "dry-run") {
            "run_live_soak"
        } else {
            "inspect_events_then_patch_smallest_failure"
        }
    }
    Write-JsonFile -Path $LatestSummaryPath -Data $Summary
    $lines = @(
        "# chat-ui vibe soak handoff",
        "",
        "- schemaVersion: $($Summary.schemaVersion)",
        "- status: $($Summary.status)",
        "- ok: $($Summary.ok)",
        "- port: $($Summary.port)",
        "- seed: $($Summary.seed)",
        "- iterationsCompleted: $($Summary.iterationsCompleted)",
        "- terminalSeenCount: $($Summary.terminalSeenCount)",
        "- timeoutCount: $($Summary.timeoutCount)",
        "- rawPromptStored: $($Summary.rawPromptStored)",
        "- rawStreamStored: $($Summary.rawStreamStored)",
        "- eventsPath: $($Summary.eventsPath)",
        "- summaryPath: $($Summary.summaryPath)",
        "- latestSummaryPath: $LatestSummaryPath",
        "- nextHandoffAction: $($Summary.nextHandoffAction)",
        "- nextFailureFocus: $($Summary.failureFocus.nextFailureFocus)",
        "- ragSignalStatus: $($Summary.ragSignalSummary.status)",
        "- nextRagSignalFocus: $($Summary.ragSignalSummary.nextRagSignalFocus)",
        "- ragSignalFakeCommandMentionCount: $($Summary.ragSignalSummary.fakeCommandMentionCount)",
        "- ragSignalOffDomainCommunityMentionCount: $($Summary.ragSignalSummary.offDomainCommunityMentionCount)",
        "- ragSignalOfficialEvidenceNeededCount: $($Summary.ragSignalSummary.officialEvidenceNeededCount)",
        "- ragSignalOfficialEvidenceMissingCount: $($Summary.ragSignalSummary.officialEvidenceMissingCount)",
        "- replayFailureCommand: $($Summary.replayFailureCommand)",
        "",
        "Run continuation:",
        "",
        '```powershell',
        "$($Summary.nextLongRunCommand)",
        '```'
    )
    Set-Content -LiteralPath $HandoffPath -Value $lines -Encoding UTF8
}

function ConvertTo-EventSummary {
    param([string]$Raw)
    $events = New-Object System.Collections.Generic.List[object]
    $currentEvent = $null
    foreach ($line in ($Raw -split "`r?`n")) {
        if ($line.StartsWith("event:")) {
            $currentEvent = $line.Substring(6).Trim()
        } elseif ($line.StartsWith("data:")) {
            $data = $line.Substring(5).Trim()
            $payloadType = $null
            try { $payloadType = ((ConvertFrom-Json $data -ErrorAction Stop).type) } catch { $payloadType = $null }
            $events.Add([pscustomobject]@{
                event = $currentEvent
                payloadType = $payloadType
                dataHash = Get-Hash12 $data
                dataLength = $data.Length
            }) | Out-Null
        }
    }
    return $events.ToArray()
}

function Invoke-ListenerReady {
    param([string]$RunDir)
    if ($SkipListenerStart -or $DryRun) {
        return @{
            skipped = $true
            status = if ($DryRun) { "dry-run" } else { "skip-listener-start" }
        }
    }
    $listenerScript = Join-Path $PSScriptRoot "chat_ui_vibe_listener.ps1"
    $args = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", $listenerScript,
        "-Port", ([string]$Port),
        "-OutDir", $RunDir,
        "-ReadyTimeoutSeconds", "180"
    )
    if ($CloseConflictingListener) {
        $args += "-CloseConflictingListener"
    }
    $stdoutPath = Join-Path $RunDir "chat-ui-vibe-soak-listener.stdout.log"
    $stderrPath = Join-Path $RunDir "chat-ui-vibe-soak-listener.stderr.log"
    $proc = Start-Process -FilePath "powershell" `
        -ArgumentList $args `
        -PassThru `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden
    if (-not $proc.WaitForExit($ListenerTimeoutSeconds * 1000)) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        return @{
            skipped = $false
            ok = $false
            status = "listener-timeout"
            exitCode = $null
            timeoutSeconds = $ListenerTimeoutSeconds
            stdoutPath = $stdoutPath
            stderrPath = $stderrPath
        }
    }
    $proc.Refresh()
    $exitCode = $proc.ExitCode
    $output = @()
    if (Test-Path -LiteralPath $stdoutPath) {
        $output += Get-Content -LiteralPath $stdoutPath -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $stderrPath) {
        $output += Get-Content -LiteralPath $stderrPath -ErrorAction SilentlyContinue
    }
    $jsonLine = @($output | Where-Object { [string]$_ -match "^\s*\{" } | Select-Object -Last 1)
    $parsed = $null
    if ($jsonLine) {
        try { $parsed = ($jsonLine | ConvertFrom-Json) } catch { $parsed = $null }
    }
    $parsedOk = $parsed -and $parsed.ok -eq $true
    if ($exitCode -ne 0 -and -not $parsedOk) {
        return @{
            skipped = $false
            ok = $false
            status = "listener-failed"
            exitCode = $exitCode
            parsed = $parsed
        }
    }
    return @{
        skipped = $false
        ok = $parsedOk
        status = if ($parsedOk) { "listener-ready" } else { "listener-unknown" }
        exitCode = $exitCode
        parsed = $parsed
    }
}

function Test-ChatUiContract {
    $url = "http://127.0.0.1:$Port/chat-ui?codexSmoke=vibe-soak"
    try {
        $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        $html = [string]$response.Content
        return @{
            ok = $true
            statusCode = [int]$response.StatusCode
            authModeDisabled = $html.Contains('data-auth-mode="disabled-test"')
            featurePoolOpen = $html.Contains('data-vibe-feature-pool="open"')
            urlHash = Get-Hash12 $url
            htmlLength = $html.Length
        }
    } catch {
        return @{
            ok = $false
            statusCode = 0
            authModeDisabled = $false
            featurePoolOpen = $false
            errorHash = Get-Hash12 ([string]$_)
            errorLength = ([string]$_).Length
        }
    }
}

function Invoke-StreamProbe {
    param(
        [hashtable]$Probe,
        [int]$Attempt,
        [string]$RunDir
    )
    $url = "http://127.0.0.1:$Port/api/chat/stream"
    $requestId = "codex-vibe-soak-$runId-$Attempt"
    $isolatedSession = $false
    if ($Probe.ContainsKey("isolatedSession")) {
        $isolatedSession = [bool]$Probe.isolatedSession
    }
    $requiresConstraintIntegrity = $Probe.ContainsKey("requiresConstraintIntegrity") -and
        [bool]$Probe.requiresConstraintIntegrity
    $constraintText = [System.Text.StringBuilder]::new()
    $probeIdToken = ([string]$Probe.id) -replace '[^A-Za-z0-9_-]', '_'
    $sessionId = if ($isolatedSession) {
        "codex-vibe-soak-session-$runId-$Attempt-$probeIdToken"
    } else {
        "codex-vibe-soak-session-$runId"
    }
    $body = [ordered]@{
        message = [string]$Probe.message
        sessionId = $sessionId
        useRag = [bool]$Probe.useRag
        useWebSearch = [bool]$Probe.useWebSearch
        searchMode = [string]$Probe.searchMode
        maxTokens = $MaxTokens
    }
    if (-not [string]::IsNullOrWhiteSpace($ModelOverride)) {
        $body["model"] = Get-SafeSignalValue -Value $ModelOverride -MaxLength 120
    }
    $bodyJson = $body | ConvertTo-Json -Depth 6 -Compress
    $tempResiduePattern = "awx-chat-vibe-soak-*-$runId-$Attempt.*"
    $tempResidueBefore = @(
        Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Filter $tempResiduePattern -File -ErrorAction SilentlyContinue |
            ForEach-Object { $_.FullName }
    )
    $usageBefore = if ($CurlWindowSeconds -gt 1) {
        Get-ChatUsageSnapshot -Port $Port
    } else {
        $null
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $curlExit = 0
    $transportCode = "success"
    $transportErrorType = ""
    $statusCode = $null
    $events = New-Object System.Collections.Generic.List[object]
    $ragSignalRows = New-Object System.Collections.Generic.List[object]
    $answerContractHold = $false
    $answerContractReason = "none"
    $observedModelUsed = ""
    $observedAnswerMode = ""
    $observedRoute = ""
    $observedPipelineFailureClass = ""
    $observedPipelineDisabledReason = ""
    $observedDebugFxCode = ""
    $observedDebugFxFailureClass = ""
    $observedDebugFxTriggerReason = ""
    $providerAttemptObserved = $false
    $currentEvent = $null
    $httpClient = $null
    $request = $null
    $response = $null
    $content = $null
    $runtimeLogOffsetBeforeRequest = -1L
    $timeoutCts = [System.Threading.CancellationTokenSource]::new()
    $timeoutCts.CancelAfter([TimeSpan]::FromSeconds([Math]::Max(1, $CurlWindowSeconds)))
    try {
        $httpClient = [System.Net.Http.HttpClient]::new()
        $httpClient.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $url)
        $request.Headers.TryAddWithoutValidation("X-Request-Id", $requestId) | Out-Null
        $request.Headers.TryAddWithoutValidation("X-Budget-Ms", [string]$RequestBudgetMs) | Out-Null
        $content = [System.Net.Http.StringContent]::new(
            $bodyJson,
            [System.Text.Encoding]::UTF8,
            "application/json")
        $request.Content = $content
        $runtimeLogOffsetBeforeRequest = Get-RuntimeLogLength -Path $ownedRuntimeLogPath
        $response = $httpClient.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
            $timeoutCts.Token).GetAwaiter().GetResult()
        $statusCode = [int]$response.StatusCode

        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true, 4096, $false)
        try {
            while ($true) {
                $lineTask = $reader.ReadLineAsync()
                $remainingMs = [Math]::Max(1, ($CurlWindowSeconds * 1000) - [int]$sw.ElapsedMilliseconds)
                if (-not $lineTask.Wait($remainingMs)) {
                    $timeoutCts.Cancel()
                    throw [System.TimeoutException]::new("stream deadline elapsed")
                }
                $line = $lineTask.GetAwaiter().GetResult()
                if ($null -eq $line) { break }
                if ($line.StartsWith("event:")) {
                    $currentEvent = $line.Substring(6).Trim()
                    continue
                }
                if (-not $line.StartsWith("data:")) { continue }

                $data = $line.Substring(5).Trim()
                $payloadType = $null
                $payload = $null
                try {
                    $payload = ConvertFrom-Json $data -ErrorAction Stop
                    $payloadType = $payload.type
                } catch {
                    $payloadType = $null
                }
                if ($null -ne $payload) {
                    if ($payload.PSObject.Properties.Name -contains "modelUsed") {
                        $candidateModel = Get-SafeSignalValue -Value $payload.modelUsed
                        if (-not [string]::IsNullOrWhiteSpace($candidateModel)) {
                            $observedModelUsed = $candidateModel
                        }
                    }
                    if ($payload.PSObject.Properties.Name -contains "answerMode") {
                        $candidateAnswerMode = Get-SafeSignalValue -Value $payload.answerMode
                        if (-not [string]::IsNullOrWhiteSpace($candidateAnswerMode)) {
                            $observedAnswerMode = $candidateAnswerMode
                        }
                    }
                    if ($payload.PSObject.Properties.Name -contains "pipelineSnapshot" -and
                        $null -ne $payload.pipelineSnapshot) {
                        $pipelineSnapshot = $payload.pipelineSnapshot
                        foreach ($pipelineField in @(
                            @{ name = "route"; target = "route" },
                            @{ name = "failureClass"; target = "failure" },
                            @{ name = "disabledReason"; target = "disabled" }
                        )) {
                            if ($pipelineSnapshot.PSObject.Properties.Name -contains $pipelineField.name) {
                                $candidatePipelineValue = Get-SafeSignalValue -Value $pipelineSnapshot.($pipelineField.name)
                                if (-not [string]::IsNullOrWhiteSpace($candidatePipelineValue)) {
                                    switch ($pipelineField.target) {
                                        "route" { $observedRoute = $candidatePipelineValue }
                                        "failure" { $observedPipelineFailureClass = $candidatePipelineValue }
                                        "disabled" { $observedPipelineDisabledReason = $candidatePipelineValue }
                                    }
                                }
                            }
                        }
                    }
                    if ($payload.PSObject.Properties.Name -contains "debugFxSignal" -and
                        $null -ne $payload.debugFxSignal) {
                        $debugFxSignal = $payload.debugFxSignal
                        if ($debugFxSignal.PSObject.Properties.Name -contains "code") {
                            $observedDebugFxCode = Get-SafeSignalValue -Value $debugFxSignal.code
                        }
                        if ($debugFxSignal.PSObject.Properties.Name -contains "labels" -and
                            $null -ne $debugFxSignal.labels) {
                            foreach ($debugLabel in @(
                                @{ name = "localLlmFailureClass"; target = "failure" },
                                @{ name = "localLlmTriggerReason"; target = "trigger" }
                            )) {
                                if ($debugFxSignal.labels.PSObject.Properties.Name -contains $debugLabel.name) {
                                    $candidateDebugValue = Get-SafeSignalValue -Value $debugFxSignal.labels.($debugLabel.name)
                                    if (-not [string]::IsNullOrWhiteSpace($candidateDebugValue)) {
                                        switch ($debugLabel.target) {
                                            "failure" { $observedDebugFxFailureClass = $candidateDebugValue }
                                            "trigger" { $observedDebugFxTriggerReason = $candidateDebugValue }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    foreach ($providerField in @("providerAttemptObserved", "providerAttempted")) {
                        if ($payload.PSObject.Properties.Name -contains $providerField -and
                            $payload.$providerField -is [bool]) {
                            $providerAttemptObserved = [bool]$payload.$providerField
                        }
                    }
                }
                if ($requiresConstraintIntegrity -and $null -ne $payload) {
                    foreach ($field in @("data", "content", "token", "text", "message")) {
                        if ($payload.PSObject.Properties.Name -contains $field) {
                            $fragment = $payload.$field
                            if ($fragment -is [string]) {
                                [void]$constraintText.Append([string]$fragment)
                                $isAnswerEvent = $currentEvent -in @("token", "final") -or
                                    $payloadType -in @("token", "final")
                                $answerFragment = ([string]$fragment).TrimStart()
                                $hasHoldPrefix = $answerFragment.StartsWith(
                                    "HOLD",
                                    [System.StringComparison]::OrdinalIgnoreCase)
                                $limitationIndex = $answerFragment.IndexOf(
                                    "limitation:",
                                    [System.StringComparison]::OrdinalIgnoreCase)
                                $koLimitation = ([char]0xD55C).ToString() + [char]0xACC4
                                $hasLimitation = $answerFragment.Contains($koLimitation + ":") -or
                                    $answerFragment.Contains($koLimitation + [char]0xFF1A) -or
                                    $limitationIndex -ge 0
                                if ($isAnswerEvent -and $hasHoldPrefix -and $hasLimitation) {
                                    $answerContractHold = $true
                                    $answerContractReason = "explicit_hold"
                                }
                            }
                        }
                    }
                }
                $events.Add([pscustomobject]@{
                    event = $currentEvent
                    payloadType = $payloadType
                    dataHash = Get-Hash12 $data
                    dataLength = $data.Length
                }) | Out-Null
                $ragSignalRows.Add([pscustomobject](New-RagSignalSummary -Raw $data)) | Out-Null

                if ($currentEvent -in @("done", "complete", "error", "final") -or
                    $payloadType -in @("done", "complete", "error", "final")) {
                    break
                }
            }
        } finally {
            $reader.Dispose()
        }
        if ($statusCode -ge 400) {
            $transportCode = "http_error"
        }
    } catch [System.TimeoutException] {
        $curlExit = 28
        $transportCode = "timeout"
        $transportFailure = $_.Exception
        while ($null -ne $transportFailure.InnerException) { $transportFailure = $transportFailure.InnerException }
        $transportErrorType = $transportFailure.GetType().FullName
    } catch [System.OperationCanceledException] {
        $curlExit = 28
        $transportCode = "timeout"
        $transportFailure = $_.Exception
        while ($null -ne $transportFailure.InnerException) { $transportFailure = $transportFailure.InnerException }
        $transportErrorType = $transportFailure.GetType().FullName
    } catch {
        $curlExit = -1
        $transportCode = "transport_error"
        $transportFailure = $_.Exception
        while ($null -ne $transportFailure.InnerException) { $transportFailure = $transportFailure.InnerException }
        $transportErrorType = $transportFailure.GetType().FullName
    } finally {
        if ($response) { $response.Dispose() }
        if ($request) { $request.Dispose() }
        if ($content) { $content.Dispose() }
        if ($httpClient) { $httpClient.Dispose() }
        $timeoutCts.Dispose()
    }
    $sw.Stop()
    $requestProof = Get-RequestProofSummary `
        -LogPath $ownedRuntimeLogPath `
        -Offset $runtimeLogOffsetBeforeRequest `
        -RequestHash (Get-RedactedRequestHash $requestId) `
        -MaxTailBytes $MaxProofTailBytes
    $usageAfter = if ($null -ne $usageBefore -and $transportCode -eq "success") {
        Get-ChatUsageSnapshot -Port $Port
    } else {
        $null
    }
    $usageWindowObserved = $null -ne $usageBefore -and
        $usageBefore.observed -eq $true -and
        $null -ne $usageAfter -and
        $usageAfter.observed -eq $true -and
        [long]$usageBefore.counterEpochStartedAtMs -gt 0 -and
        [long]$usageBefore.counterEpochStartedAtMs -eq [long]$usageAfter.counterEpochStartedAtMs
    $modelAttemptDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.attempts - [long]$usageBefore.attempts)
    } else { 0L }
    $modelResponseReceivedDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.responseReceived - [long]$usageBefore.responseReceived)
    } else { 0L }
    $modelFailedBeforeResponseDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.failedBeforeResponse - [long]$usageBefore.failedBeforeResponse)
    } else { 0L }
    $modelTimedOutDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.timedOut - [long]$usageBefore.timedOut)
    } else { 0L }
    $modelCancelledDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.cancelled - [long]$usageBefore.cancelled)
    } else { 0L }
    $providerUsageObservedDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.providerUsageObserved - [long]$usageBefore.providerUsageObserved)
    } else { 0L }
    $providerUsageMissingDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.providerUsageMissing - [long]$usageBefore.providerUsageMissing)
    } else { 0L }
    $tempResidueAfter = @(
        Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Filter $tempResiduePattern -File -ErrorAction SilentlyContinue |
            ForEach-Object { $_.FullName }
    )
    $newTempResidues = @($tempResidueAfter | Where-Object { $tempResidueBefore -notcontains $_ })

    $eventRows = @($events.ToArray())
    $errorTerminalSeen = @($eventRows | Where-Object {
        $_.event -eq "error" -or $_.payloadType -eq "error"
    }).Count -gt 0
    $terminalSeen = @($eventRows | Where-Object {
        $_.event -in @("done", "complete", "error", "final") -or $_.payloadType -in @("done", "complete", "error", "final")
    }).Count -gt 0
    $classification = if ($errorTerminalSeen) {
        "terminal_error_seen"
    } elseif ($terminalSeen) {
        "terminal_event_seen"
    } elseif ($curlExit -eq 28) {
        "curl_timeout_without_terminal_event"
    } elseif ($statusCode -ge 400) {
        "http_error_without_terminal_event"
    } else {
        "no_terminal_event"
    }
    $requiresOfficialEvidence = $Probe.ContainsKey("requiresOfficialEvidence") -and [bool]$Probe.requiresOfficialEvidence
    $ragAggregate = New-RagSignalAggregateSummary -ProbeEvents @($ragSignalRows.ToArray())
    $containsEvidenceNeeded = [int]$ragAggregate.evidenceNeededSeenCount -gt 0
    $officialDomainMentionCount = [int]$ragAggregate.officialDomainMentionCount
    $offDomainCommunityMentionCount = [int]$ragAggregate.offDomainCommunityMentionCount
    $fakeCommandMentionCount = [int]$ragAggregate.fakeCommandMentionCount
    $externalLaneSplitSeen = [int]$ragAggregate.externalLaneSplitSeenCount -gt 0
    $constraintRaw = $constraintText.ToString().ToLowerInvariant()
    $constraintComparable = [regex]::Replace($constraintRaw, "\s+", "")
    $observedConstraintBuilder = [System.Text.StringBuilder]::new()
    $inferenceConstraintBuilder = [System.Text.StringBuilder]::new()
    $orderedSectionPairCount = 0
    $constraintLabelMatches = [regex]::Matches(
        $constraintComparable,
        "(?<observed>observed_constraints:|\uAD00\uCC30\uC0AC\uC2E4:)|(?<inference>inference:|\uCD94\uB860:)")
    for ($labelIndex = 0; $labelIndex -lt $constraintLabelMatches.Count; $labelIndex += 1) {
        $labelMatch = $constraintLabelMatches[$labelIndex]
        $sectionStart = $labelMatch.Index + $labelMatch.Length
        $sectionEnd = if ($labelIndex + 1 -lt $constraintLabelMatches.Count) {
            $constraintLabelMatches[$labelIndex + 1].Index
        } else {
            $constraintComparable.Length
        }
        if ($sectionEnd -gt $sectionStart) {
            $sectionText = $constraintComparable.Substring($sectionStart, $sectionEnd - $sectionStart)
            if ($labelMatch.Groups["observed"].Success) {
                [void]$observedConstraintBuilder.Append($sectionText)
            } else {
                [void]$inferenceConstraintBuilder.Append($sectionText)
            }
        }
        if ($labelMatch.Groups["observed"].Success -and
            $labelIndex + 1 -lt $constraintLabelMatches.Count -and
            $constraintLabelMatches[$labelIndex + 1].Groups["inference"].Success) {
            $orderedSectionPairCount += 1
        }
    }
    $observedConstraintComparable = $observedConstraintBuilder.ToString()
    $inferenceConstraintComparable = $inferenceConstraintBuilder.ToString()
    $requiredConstraintGroups = @(
        @{ id = "debt_present"; patterns = @("debt=present", "\uBD80\uCC44(?:\uAC00)?(?:\uC788|\uC874\uC7AC)") },
        @{ id = "cashflow_tight"; patterns = @("cashflow=tight", "\uD604\uAE08\uD750\uB984(?:\uC774)?(?:\uBE60\uB4EF|\uC81C\uC57D|\uC81C\uD55C|\uBD80\uC871)") },
        @{ id = "spending_limit_restricted"; patterns = @("spendinglimit=restricted", "\uC9C0\uCD9C\uD55C\uB3C4(?:\uAC00)?(?:\uC81C\uD55C|\uC5C4\uACA9|\uC788|\uB0AE)") },
        @{ id = "risk_tolerance_low"; patterns = @("risktolerance=low", "(?:\uC704\uD5D8\uD5C8\uC6A9(?:\uB3C4)?|\uC704\uD5D8\uAC10\uC218\uB3C4)(?:\uAC00)?(?:\uB0AE|\uC801)") },
        @{ id = "purchase_cost_high"; patterns = @("purchasecost=high", "\uACE0(?:\uAC00|\uBE44\uC6A9)(?:\uC758)?\uAD6C\uB9E4") }
    )
    $preservedConstraintCount = 0
    $constraintResultCodes = New-Object System.Collections.Generic.List[string]
    if ($requiresConstraintIntegrity) {
        foreach ($requiredConstraintGroup in $requiredConstraintGroups) {
            $groupPreserved = $false
            $groupNegated = $false
            foreach ($constraintPattern in @($requiredConstraintGroup.patterns)) {
                foreach ($constraintMatch in [regex]::Matches($observedConstraintComparable, $constraintPattern)) {
                    $prefixStart = [Math]::Max(0, $constraintMatch.Index - 8)
                    $constraintPrefix = $observedConstraintComparable.Substring(
                        $prefixStart,
                        $constraintMatch.Index - $prefixStart)
                    $suffixStart = $constraintMatch.Index + $constraintMatch.Length
                    $constraintSuffix = $observedConstraintComparable.Substring(
                        $suffixStart,
                        [Math]::Min(12, $observedConstraintComparable.Length - $suffixStart))
                    $prefixNegated = [regex]::IsMatch($constraintPrefix, "(?:not|no)$")
                    $suffixNegated = [regex]::IsMatch(
                        $constraintSuffix,
                        "^(?:\uAC00|\uC774|\uC740|\uB294|\uC744|\uB97C)?(?:\uD558\uC9C0\uC54A|\uB418\uC9C0\uC54A|\uC9C0\uC54A|\uC544\uB2D8|\uC5C6|\uBD80\uC7AC|=?false|not)")
                    if (-not $prefixNegated -and -not $suffixNegated) {
                        $groupPreserved = $true
                        break
                    }
                    $groupNegated = $true
                }
                if ($groupPreserved) { break }
            }
            $constraintReasonCode = if ($groupPreserved) {
                $preservedConstraintCount += 1
                "preserved"
            } elseif ($groupNegated) {
                "negated"
            } else {
                "missing"
            }
            $constraintResultCodes.Add("$($requiredConstraintGroup.id):$constraintReasonCode") | Out-Null
        }
    }
    $requiredConstraintCount = if ($requiresConstraintIntegrity) { $requiredConstraintGroups.Count } else { 0 }
    $droppedConstraintCount = [Math]::Max(0, $requiredConstraintCount - $preservedConstraintCount)
    $contextCompleteness = if ($requiredConstraintCount -gt 0) {
        [Math]::Round(([double]$preservedConstraintCount / [double]$requiredConstraintCount), 3)
    } else { 1.0 }
    $observationInferenceSeparated = -not $requiresConstraintIntegrity -or $orderedSectionPairCount -gt 0
    $unsupportedInferenceCount = if ($requiresConstraintIntegrity) {
        ([regex]::Matches(
            $inferenceConstraintComparable,
            "discretionarybudget=sufficient|\uAC00\uCC98\uBD84\uC608\uC0B0=\uCDA9\uBD84|\uC608\uC0B0\uC774\uCDA9\uBD84|(?:\uAD6C\uB9E4|\uC9C0\uCD9C|\uC7AC\uC815)\uC5EC\uB825(?:\uC774)?\uCDA9\uBD84")).Count
    } else { 0 }
    $constraintIntegrityStatus = if (-not $requiresConstraintIntegrity) {
        "not-required"
    } elseif ($answerContractHold) {
        "hold"
    } elseif ($droppedConstraintCount -eq 0 -and $observationInferenceSeparated -and $unsupportedInferenceCount -eq 0) {
        "pass"
    } else {
        "constraint-integrity-missing"
    }
    $constraintFailureReasonCodes = New-Object System.Collections.Generic.List[string]
    if ($requiresConstraintIntegrity) {
        if ($answerContractHold) {
            $constraintFailureReasonCodes.Add("answer_contract_explicit_hold") | Out-Null
        }
        if ($droppedConstraintCount -gt 0) {
            $constraintFailureReasonCodes.Add("required_constraints_missing") | Out-Null
        }
        if (-not $observationInferenceSeparated) {
            $constraintFailureReasonCodes.Add("observation_inference_sections_missing") | Out-Null
        }
        if ($unsupportedInferenceCount -gt 0) {
            $constraintFailureReasonCodes.Add("unsupported_inference_present") | Out-Null
        }
    }
    if ($requiresConstraintIntegrity -and $constraintIntegrityStatus -eq "hold") {
        $classification = "constraint_integrity_hold"
    } elseif ($requiresConstraintIntegrity -and $constraintIntegrityStatus -ne "pass") {
        $classification = "constraint_integrity_missing"
    }
    $fallbackDetected = [regex]::IsMatch(
        "$observedModelUsed $observedAnswerMode $observedRoute",
        "fallback|local_safe|evidence_only",
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $fallbackApplicability = if (-not $fallbackDetected) {
        "not-fallback"
    } elseif ($requiresConstraintIntegrity) {
        "constraint-contract-applies"
    } else {
        "constraint-contract-not-required"
    }
    $constraintContractScope = if ($requiresConstraintIntegrity) {
        "visible-terminal-answer"
    } else {
        "not-required"
    }
    $providerAttributionSource = if ($providerAttemptObserved) {
        "stream_payload"
    } elseif (-not [string]::IsNullOrWhiteSpace($observedPipelineFailureClass) -or
        -not [string]::IsNullOrWhiteSpace($observedPipelineDisabledReason)) {
        "pipeline_snapshot"
    } elseif (-not [string]::IsNullOrWhiteSpace($observedDebugFxCode) -or
        -not [string]::IsNullOrWhiteSpace($observedDebugFxFailureClass) -or
        -not [string]::IsNullOrWhiteSpace($observedDebugFxTriggerReason)) {
        "debug_fx"
    } else {
        "not_observed"
    }
    $modelFinalObserved = $terminalSeen -and -not [string]::IsNullOrWhiteSpace($observedModelUsed)
    $modelProofStatus = if ($transportCode -ne "success" -or $statusCode -ge 400) {
        "transport-failed"
    } elseif (-not $terminalSeen) {
        "terminal-missing"
    } elseif ($fallbackDetected) {
        "fallback-detected"
    } elseif (-not $modelFinalObserved) {
        "model-metadata-missing"
    } elseif ($providerAttemptObserved) {
        "model-and-provider-observed"
    } else {
        "model-observed-provider-not-observed"
    }
    $officialEvidenceNeeded = $requiresOfficialEvidence -and $officialDomainMentionCount -le 0 -and $containsEvidenceNeeded
    $officialEvidenceMissing = $requiresOfficialEvidence -and $officialDomainMentionCount -le 0 -and -not $containsEvidenceNeeded
    $ragSignalStatus = if ($fakeCommandMentionCount -gt 0) {
        "fake-command-risk"
    } elseif ($offDomainCommunityMentionCount -gt 0) {
        "off-domain-community-risk"
    } elseif ($officialEvidenceMissing) {
        "official-evidence-missing"
    } elseif ($officialEvidenceNeeded) {
        "official-evidence-needed"
    } elseif ($containsEvidenceNeeded -or $externalLaneSplitSeen -or $officialDomainMentionCount -gt 0) {
        "rag-signal-observed"
    } else {
        "no-rag-signal"
    }
    return @{
        type = "stream_probe"
        attempt = $Attempt
        probeId = [string]$Probe.id
        promptHash = Get-Hash12 ([string]$Probe.message)
        promptLength = ([string]$Probe.message).Length
        rawPromptStored = $false
        rawStreamStored = $false
        rawRequestIdStored = [bool]$requestProof.rawRequestIdStored
        rawProofLinesStored = [bool]$requestProof.rawProofLinesStored
        requestHash = [string]$requestProof.requestHash
        proofRowCount = [int]$requestProof.proofRowCount
        attemptTotal = [int]$requestProof.attemptTotal
        attemptDropped = [int]$requestProof.attemptDropped
        distinctPromptHashCount = [int]$requestProof.distinctPromptHashCount
        distinctOptionsHashCount = [int]$requestProof.distinctOptionsHashCount
        responseHashObservedCount = [int]$requestProof.responseHashObservedCount
        modelAdapterAttemptObservedCount = [int]$requestProof.modelAdapterAttemptObservedCount
        clientHttpExchangeObservedCount = [int]$requestProof.clientHttpExchangeObservedCount
        clientHttpResponseObservedCount = [int]$requestProof.clientHttpResponseObservedCount
        providerAttemptObservedCount = [int]$requestProof.providerAttemptObservedCount
        wireAttemptObservedCount = [int]$requestProof.wireAttemptObservedCount
        responseObservedCount = [int]$requestProof.responseObservedCount
        correlationStatus = [string]$requestProof.correlationStatus
        sessionScope = if ($isolatedSession) { "isolated-probe" } else { "run-shared" }
        useRag = [bool]$Probe.useRag
        useWebSearch = [bool]$Probe.useWebSearch
        searchMode = [string]$Probe.searchMode
        requestBudgetMs = $RequestBudgetMs
        curlWindowSeconds = $CurlWindowSeconds
        curlExit = $curlExit
        transport = "httpclient"
        transportCode = $transportCode
        transportErrorType = $transportErrorType
        tempResidueDelta = $newTempResidues.Count
        tempResidueCount = $tempResidueAfter.Count
        statusCode = $statusCode
        elapsedMs = [int]$sw.ElapsedMilliseconds
        terminalSeen = $terminalSeen
        eventCount = $eventRows.Count
        eventTypes = @($eventRows | ForEach-Object { $_.event } | Where-Object { $_ } | Select-Object -Unique)
        payloadTypes = @($eventRows | ForEach-Object { $_.payloadType } | Where-Object { $_ } | Select-Object -Unique)
        classification = $classification
        ragSignalStatus = $ragSignalStatus
        containsEvidenceNeeded = $containsEvidenceNeeded
        officialDomainMentionCount = $officialDomainMentionCount
        offDomainCommunityMentionCount = $offDomainCommunityMentionCount
        fakeCommandMentionCount = $fakeCommandMentionCount
        officialEvidenceNeeded = $officialEvidenceNeeded
        officialEvidenceMissing = $officialEvidenceMissing
        externalLaneSplitSeen = $externalLaneSplitSeen
        constraintIntegrityRequired = $requiresConstraintIntegrity
        requiredConstraintCount = $requiredConstraintCount
        preservedConstraintCount = $preservedConstraintCount
        contextCompleteness = $contextCompleteness
        droppedConstraintCount = $droppedConstraintCount
        unsupportedInferenceCount = $unsupportedInferenceCount
        observationInferenceSeparated = $observationInferenceSeparated
        constraintIntegrityStatus = $constraintIntegrityStatus
        constraintResultCodes = @($constraintResultCodes)
        constraintFailureReasonCodes = @($constraintFailureReasonCodes)
        constraintContractScope = $constraintContractScope
        answerContractHold = $answerContractHold
        answerContractReason = $answerContractReason
        modelUsed = $observedModelUsed
        answerMode = $observedAnswerMode
        route = $observedRoute
        pipelineFailureClass = $observedPipelineFailureClass
        pipelineDisabledReason = $observedPipelineDisabledReason
        debugFxCode = $observedDebugFxCode
        debugFxFailureClass = $observedDebugFxFailureClass
        debugFxTriggerReason = $observedDebugFxTriggerReason
        providerAttributionSource = $providerAttributionSource
        modelFinalObserved = $modelFinalObserved
        modelAttemptObserved = ($modelAttemptDelta -gt 0)
        modelAttemptDelta = $modelAttemptDelta
        modelResponseReceivedDelta = $modelResponseReceivedDelta
        modelFailedBeforeResponseDelta = $modelFailedBeforeResponseDelta
        modelTimedOutDelta = $modelTimedOutDelta
        modelCancelledDelta = $modelCancelledDelta
        modelAttemptCorrelationScope = if ($usageWindowObserved) { "process-window" } else { "unavailable" }
        providerAttemptObserved = $providerAttemptObserved
        providerUsageObserved = ($providerUsageObservedDelta -gt 0)
        providerUsageObservedDelta = $providerUsageObservedDelta
        providerUsageMissingDelta = $providerUsageMissingDelta
        wireAttemptCoverage = if ($usageWindowObserved) { [string]$usageAfter.wireAttemptCoverage } else { "unavailable" }
        fallbackDetected = $fallbackDetected
        fallbackApplicability = $fallbackApplicability
        modelProofStatus = $modelProofStatus
    }
}

$runId = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$rootOut = Resolve-OutputDir -Path $OutDir
$runDir = if ($DryRun) {
    Join-Path $rootOut "dry-run-$runId"
} else {
    Join-Path $rootOut "run-$runId"
}
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$eventsPath = Join-Path $runDir "chat-ui-vibe-soak.events.ndjson"
$summaryPath = Join-Path $runDir "chat-ui-vibe-soak.summary.json"
$latestSummaryPath = Join-Path $rootOut "chat-ui-vibe-soak.latest.json"
$agentNextPath = Join-Path $runDir "chat-ui-vibe-soak.agent-next.json"
$latestAgentNextPath = Join-Path $rootOut "chat-ui-vibe-soak.latest-agent-next.json"
$progressPath = Join-Path $runDir "chat-ui-vibe-soak.progress.json"
$latestProgressPath = Join-Path $rootOut "chat-ui-vibe-soak.latest-progress.json"
$handoffPath = Join-Path $runDir "chat-ui-vibe-soak.handoff.md"

if ($Seed -eq 0) {
    $Seed = [Math]::Abs([int]((Get-Date).Ticks % [int]::MaxValue))
}
$random = [System.Random]::new($Seed)
$deadline = if ($DurationMinutes -gt 0) { (Get-Date).AddMinutes($DurationMinutes) } else { [DateTime]::MaxValue }
$probeDeck = @(
    @{ id = "rag_web_budget"; message = "Find current evidence for a RAG web-search answer, cite constraints, and keep the answer concise."; useRag = $true; useWebSearch = $true; searchMode = "FORCE_DEEP" },
    @{ id = "rag_only_memory"; message = "Use retrieved local context only and explain which evidence was missing if the answer is incomplete."; useRag = $true; useWebSearch = $false; searchMode = "OFF" },
    @{ id = "web_light_status"; message = "Search lightly for recent platform signals and summarize the reliability tradeoffs."; useRag = $false; useWebSearch = $true; searchMode = "FORCE_LIGHT" },
    @{ id = "direct_off"; message = "Answer directly without retrieval, then state whether retrieval would be needed for confidence."; useRag = $false; useWebSearch = $false; searchMode = "OFF" },
    @{ id = "failure_learning"; message = "Diagnose why a stream can end without a final event and list the safest next probe."; useRag = $true; useWebSearch = $true; searchMode = "AUTO" },
    @{ id = "official_changelog_evidence"; message = "RAG web-search verification: answer only from official OpenAI and Supabase docs/changelog evidence; if official evidence is missing say evidence_needed."; useRag = $true; useWebSearch = $true; searchMode = "FORCE_DEEP"; isolatedSession = $true; requiresOfficialEvidence = $true },
    @{ id = "skill_lane_external_split"; message = "Using demo1-demand-driven-external-proof, demo1-superpowers-repo-evidence-guard, and the Supabase skill, split Browser/Computer/Supabase local proof from external evidence; name only repo commands that exist, otherwise say evidence_needed."; useRag = $true; useWebSearch = $false; searchMode = "OFF" },
    @{ id = "decision_context_integrity"; message = "Fictional budgeting scenario. Return exactly two labeled lines. OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;riskTolerance=low;purchaseCost=high. INFERENCE: discretionaryBudget=unknown. Do not invent or repeat any exact financial amount."; useRag = $false; useWebSearch = $false; searchMode = "OFF"; isolatedSession = $true; requiresConstraintIntegrity = $true }
)
$selectedProbeId = ""
$replaySourcePath = ""
$replaySourceProbeId = ""
if ([string]::IsNullOrWhiteSpace($ProbeId) -and -not [string]::IsNullOrWhiteSpace($ReplayFromSummaryPath)) {
    $ProbeId = Resolve-ReplayProbeIdFromSummary -SummaryPath $ReplayFromSummaryPath
    $replaySourceProbeId = $ProbeId
    $replaySourcePath = if ([System.IO.Path]::IsPathRooted($ReplayFromSummaryPath)) {
        $ReplayFromSummaryPath
    } else {
        Join-Path (Get-Location).Path $ReplayFromSummaryPath
    }
}
if (-not [string]::IsNullOrWhiteSpace($ProbeId)) {
    $matchingProbes = @($probeDeck | Where-Object { [string]$_.id -eq $ProbeId })
    if ($matchingProbes.Count -ne 1) {
        $validProbeIds = @($probeDeck | ForEach-Object { [string]$_.id }) -join ","
        throw "Unknown ProbeId '$ProbeId'. Valid probes: $validProbeIds"
    }
    $probeDeck = @($matchingProbes)
    $selectedProbeId = $ProbeId
}

if ($Iterations -lt 0) { $Iterations = 0 }
$plannedCount = if ($Iterations -gt 0) { $Iterations } else { $probeDeck.Count }
$longRunCommand = New-LongRunCommand -Port $Port -OutDir $OutDir -RecentWindowSize $RecentWindowSize

if ($DryRun) {
    $coverageBag = @()
    $dryRunProbeCoverage = @{}
    $dryRunDirectDebugShortCircuitRiskCount = 0
    for ($i = 1; $i -le $plannedCount; $i++) {
        $probe = Get-NextProbeFromCoverageBag -ProbeDeck $probeDeck -Random $random -CoverageBag ([ref]$coverageBag)
        Add-ProbeCoverage -Coverage $dryRunProbeCoverage -ProbeId ([string]$probe.id)
        $directDebugShortCircuitRisk = Test-ProbeDirectDebugShortCircuitRisk -Message ([string]$probe.message)
        if ($directDebugShortCircuitRisk) { $dryRunDirectDebugShortCircuitRiskCount += 1 }
        Write-NdjsonEvent -Path $eventsPath -Data @{
            type = "planned_probe"
            attempt = $i
            probeId = [string]$probe.id
            promptHash = Get-Hash12 ([string]$probe.message)
            promptLength = ([string]$probe.message).Length
            rawPromptStored = $false
            rawStreamStored = $false
            sessionScope = if ($probe.ContainsKey("isolatedSession") -and [bool]$probe.isolatedSession) { "isolated-probe" } else { "run-shared" }
            constraintIntegrityRequired = $probe.ContainsKey("requiresConstraintIntegrity") -and [bool]$probe.requiresConstraintIntegrity
            directDebugShortCircuitRisk = $directDebugShortCircuitRisk
            useRag = [bool]$probe.useRag
            useWebSearch = [bool]$probe.useWebSearch
            searchMode = [string]$probe.searchMode
        }
    }
    $dryRunPerProbeReliability = New-PerProbeReliabilitySummary -ProbeEvents @() -ProbeDeck $probeDeck
    $dryRunFailureFocus = New-FailureFocusSummary -PerProbeReliability $dryRunPerProbeReliability
    $dryRunRecentWindowReliability = New-RecentWindowReliabilitySummary -ProbeEvents @() -WindowSize $RecentWindowSize
    $dryRunRagSignalSummary = New-RagSignalAggregateSummary -ProbeEvents @()
    $dryRunModelProof = New-ModelProofSummary -ProbeEvents @()
    $dryRunReplayProbeId = if (-not [string]::IsNullOrWhiteSpace($selectedProbeId)) { $selectedProbeId } else { [string]$dryRunFailureFocus.worstProbeId }
    $dryRunReplayFailureCommand = New-ReplayFailureCommand -Port $Port -OutDir $OutDir -ProbeId $dryRunReplayProbeId
    $summary = @{
        schemaVersion = "awx.chat_ui_vibe_soak.v1"
        ok = $true
        status = "dry-run"
        mutationAllowed = $false
        port = $Port
        seed = $Seed
        selectedProbeId = $selectedProbeId
        targetedProbeMode = (-not [string]::IsNullOrWhiteSpace($selectedProbeId))
        replaySourcePath = $replaySourcePath
        replaySourceProbeId = $replaySourceProbeId
        plannedCount = $plannedCount
        stopReason = ""
        rawPromptStored = $false
        rawStreamStored = $false
        directDebugShortCircuitRiskCount = $dryRunDirectDebugShortCircuitRiskCount
        eventsPath = $eventsPath
        summaryPath = $summaryPath
        agentNextPath = $agentNextPath
        latestAgentNextPath = $latestAgentNextPath
        progressPath = $progressPath
        latestProgressPath = $latestProgressPath
        probeCoverage = New-ProbeCoverageSummary -Coverage $dryRunProbeCoverage -DeckSize $probeDeck.Count
        reliability = New-ReliabilitySummary -IterationsCompleted 0 -TerminalSeenCount 0
        recentWindowReliability = $dryRunRecentWindowReliability
        perProbeReliability = $dryRunPerProbeReliability
        failureFocus = $dryRunFailureFocus
        ragSignalSummary = $dryRunRagSignalSummary
        modelProof = $dryRunModelProof
        replayFailureCommand = $dryRunReplayFailureCommand
        nextLongRunCommand = $longRunCommand
        nextHandoffAction = "run_live_soak"
        nextAction = "rerun_without_DryRun"
    }
    Write-ProgressCheckpoint -Progress @{
        schemaVersion = "awx.chat_ui_vibe_soak.progress.v1"
        ok = $true
        status = "dry-run-planned"
        mutationAllowed = $false
        port = $Port
        seed = $Seed
        selectedProbeId = $selectedProbeId
        targetedProbeMode = (-not [string]::IsNullOrWhiteSpace($selectedProbeId))
        replaySourcePath = $replaySourcePath
        replaySourceProbeId = $replaySourceProbeId
        plannedCount = $plannedCount
        iterationsCompleted = $plannedCount
        stopReason = ""
        rawPromptStored = $false
        rawStreamStored = $false
        probeCoverage = New-ProbeCoverageSummary -Coverage $dryRunProbeCoverage -DeckSize $probeDeck.Count
        reliability = New-ReliabilitySummary -IterationsCompleted 0 -TerminalSeenCount 0
        recentWindowReliability = $dryRunRecentWindowReliability
        perProbeReliability = $dryRunPerProbeReliability
        failureFocus = $dryRunFailureFocus
        ragSignalSummary = $dryRunRagSignalSummary
        modelProof = $dryRunModelProof
        replayFailureCommand = $dryRunReplayFailureCommand
        nextLongRunCommand = $longRunCommand
        eventsPath = $eventsPath
        nextHandoffAction = "run_live_soak"
    } -ProgressPath $progressPath -LatestProgressPath $latestProgressPath
    Write-HandoffArtifacts -Summary $summary -LatestSummaryPath $latestSummaryPath -HandoffPath $handoffPath
    Write-AgentNextArtifact -Summary $summary -AgentNextPath $agentNextPath -LatestAgentNextPath $latestAgentNextPath
    Write-JsonFile -Path $summaryPath -Data $summary
    [Console]::Out.WriteLine(($summary | ConvertTo-Json -Depth 8 -Compress))
    exit 0
}

$listener = Invoke-ListenerReady -RunDir $runDir
$selectedServerPort = $Port
if (-not $listener.skipped) {
    $selectedServerPort = 0
    try {
        $selectedServerPort = [int]$listener.parsed.selectedPorts.server
    } catch {
        $selectedServerPort = 0
    }
    if ($selectedServerPort -le 0) {
        throw "listener-selected-port-missing"
    }
    $Port = $selectedServerPort
    $longRunCommand = New-LongRunCommand -Port $Port -OutDir $OutDir -RecentWindowSize $RecentWindowSize
}
$browserTargetUrl = ""
try {
    $browserTargetUrl = [string]$listener.parsed.browserTargetUrl
} catch {
    $browserTargetUrl = ""
}
$browserTargetUrlHash = if ([string]::IsNullOrWhiteSpace($browserTargetUrl)) {
    ""
} else {
    Get-Hash12 $browserTargetUrl
}
$listenerRunIdHash = ""
try { $listenerRunIdHash = [string]$listener.parsed.runIdHash } catch { $listenerRunIdHash = "" }
$listenerEvidence = @{
    skipped = [bool]$listener.skipped
    ok = if ($listener.ContainsKey("ok")) { [bool]$listener.ok } else { $null }
    status = [string]$listener.status
    exitCode = if ($listener.ContainsKey("exitCode")) { $listener.exitCode } else { $null }
    selectedServerPort = [int]$selectedServerPort
    browserTargetUrlHash = $browserTargetUrlHash
    runIdHash = $listenerRunIdHash
}
$ownedRuntimeLogPath = Resolve-RuntimeProofLogPath -ExplicitPath $RuntimeLogPath -ListenerResult $listener
$uiContract = Test-ChatUiContract
$listenerHasOk = $listener.ContainsKey("ok")
$listenerOk = if ($listenerHasOk) { [bool]$listener.ok } else { $null }
Write-NdjsonEvent -Path $eventsPath -Data @{
    type = "listener"
    listenerStatus = [string]$listener.status
    listenerSkipped = [bool]$listener.skipped
    listenerOk = $listenerOk
    uiOk = [bool]$uiContract.ok
    authModeDisabled = [bool]$uiContract.authModeDisabled
    featurePoolOpen = [bool]$uiContract.featurePoolOpen
    selectedServerPort = [int]$selectedServerPort
    browserTargetUrlHash = $browserTargetUrlHash
    runIdHash = $listenerRunIdHash
}

$completed = 0
$runningTerminalCount = 0
$runningReliableCount = 0
$runningTimeoutCount = 0
$runningClassificationCounts = @{}
$runningProbeCoverage = @{}
$runningProbeEvents = New-Object System.Collections.Generic.List[object]
$coverageBag = @()
$stopReason = ""
while (($Iterations -le 0 -or $completed -lt $Iterations) -and (Get-Date) -lt $deadline) {
    $completed += 1
    $probe = Get-NextProbeFromCoverageBag -ProbeDeck $probeDeck -Random $random -CoverageBag ([ref]$coverageBag)
    Add-ProbeCoverage -Coverage $runningProbeCoverage -ProbeId ([string]$probe.id)
    $event = Invoke-StreamProbe -Probe $probe -Attempt $completed -RunDir $runDir
    $runningProbeEvents.Add($event) | Out-Null
    Write-NdjsonEvent -Path $eventsPath -Data $event
    $classification = [string]$event.classification
    if (-not $runningClassificationCounts.ContainsKey($classification)) { $runningClassificationCounts[$classification] = 0 }
    $runningClassificationCounts[$classification] = [int]$runningClassificationCounts[$classification] + 1
    if ($event.terminalSeen -eq $true) { $runningTerminalCount += 1 }
    if (Test-ProbeEventReliable -Event $event) { $runningReliableCount += 1 }
    if ($classification -eq "curl_timeout_without_terminal_event") { $runningTimeoutCount += 1 }
    $runningPerProbeReliability = New-PerProbeReliabilitySummary -ProbeEvents $runningProbeEvents.ToArray() -ProbeDeck $probeDeck
    $runningFailureFocus = New-FailureFocusSummary -PerProbeReliability $runningPerProbeReliability
    $runningRecentWindowReliability = New-RecentWindowReliabilitySummary -ProbeEvents $runningProbeEvents.ToArray() -WindowSize $RecentWindowSize
    $runningRagSignalSummary = New-RagSignalAggregateSummary -ProbeEvents $runningProbeEvents.ToArray()
    $runningModelProof = New-ModelProofSummary -ProbeEvents $runningProbeEvents.ToArray()
    $runningReliability = New-ReliabilitySummary -IterationsCompleted $completed -TerminalSeenCount $runningReliableCount
    $runningTransportSafetyOk = [bool]$runningReliability.meetsNineXTarget
    $runningModelSuccessOk = $runningTransportSafetyOk -and [string]$runningModelProof.status -eq "verified"
    $recentWindowReadyToStop = [int]$runningRecentWindowReliability["observedCount"] -ge [Math]::Max(1, [int]$runningRecentWindowReliability["windowSize"])
    $shouldStopForRecentWindow = $StopOnRecentBelowTarget -and
        $recentWindowReadyToStop -and
        [string]$runningRecentWindowReliability["status"] -eq "below-target"
    $runningStopReason = if ($shouldStopForRecentWindow) { "recent-window-below-target" } else { "" }
    $runningReplayProbeId = if (-not [string]::IsNullOrWhiteSpace($selectedProbeId)) {
        $selectedProbeId
    } elseif (-not [string]::IsNullOrWhiteSpace([string]$runningRecentWindowReliability.worstRecentProbeId)) {
        [string]$runningRecentWindowReliability.worstRecentProbeId
    } else {
        [string]$runningFailureFocus.worstProbeId
    }
    $runningReplayFailureCommand = New-ReplayFailureCommand -Port $Port -OutDir $OutDir -ProbeId $runningReplayProbeId
    Write-ProgressCheckpoint -Progress @{
        schemaVersion = "awx.chat_ui_vibe_soak.progress.v1"
        ok = $runningTransportSafetyOk
        okScope = "transport-safety-only"
        transportSafetyOk = $runningTransportSafetyOk
        modelSuccessOk = $runningModelSuccessOk
        status = if ($runningReliableCount -eq $completed) { "running-all-terminal" } else { "running-partial" }
        mutationAllowed = [bool]$CloseConflictingListener
        port = $Port
        browserTargetUrlHash = $browserTargetUrlHash
        seed = $Seed
        selectedProbeId = $selectedProbeId
        targetedProbeMode = (-not [string]::IsNullOrWhiteSpace($selectedProbeId))
        replaySourcePath = $replaySourcePath
        replaySourceProbeId = $replaySourceProbeId
        iterationsRequested = $Iterations
        iterationsCompleted = $completed
        stopReason = $runningStopReason
        durationMinutes = $DurationMinutes
        requestBudgetMs = $RequestBudgetMs
        curlWindowSeconds = $CurlWindowSeconds
        rawPromptStored = $false
        rawStreamStored = $false
        lastProbeId = [string]$probe.id
        lastClassification = $classification
        lastTerminalSeen = [bool]$event.terminalSeen
        terminalSeenCount = $runningTerminalCount
        reliableSuccessCount = $runningReliableCount
        timeoutCount = $runningTimeoutCount
        classificationCounts = $runningClassificationCounts
        probeCoverage = New-ProbeCoverageSummary -Coverage $runningProbeCoverage -DeckSize $probeDeck.Count
        reliability = $runningReliability
        recentWindowReliability = $runningRecentWindowReliability
        perProbeReliability = $runningPerProbeReliability
        failureFocus = $runningFailureFocus
        ragSignalSummary = $runningRagSignalSummary
        modelProof = $runningModelProof
        replayFailureCommand = $runningReplayFailureCommand
        eventsPath = $eventsPath
        nextHandoffAction = if ($runningReliableCount -eq $completed) { "continue_long_soak" } else { "inspect_events_then_patch_smallest_failure" }
    } -ProgressPath $progressPath -LatestProgressPath $latestProgressPath
    if ($shouldStopForRecentWindow) {
        Write-NdjsonEvent -Path $eventsPath -Data @{
            type = "stop"
            stopReason = $runningStopReason
            attempt = $completed
            recentWindowStatus = [string]$runningRecentWindowReliability["status"]
            recentWindowObservedCount = [int]$runningRecentWindowReliability["observedCount"]
            recentWindowWorstProbeId = [string]$runningRecentWindowReliability["worstRecentProbeId"]
            ragSignalStatus = [string]$runningRagSignalSummary["status"]
            nextRagSignalFocus = [string]$runningRagSignalSummary["nextRagSignalFocus"]
            nextHandoffAction = "inspect_events_then_patch_smallest_failure"
            rawPromptStored = $false
            rawStreamStored = $false
        }
        $stopReason = $runningStopReason
        break
    }
}

$eventLines = @(Get-Content -LiteralPath $eventsPath -ErrorAction SilentlyContinue)
$probeEvents = @($eventLines | ForEach-Object {
    try { $_ | ConvertFrom-Json -ErrorAction Stop } catch { $null }
} | Where-Object { $_ -and $_.type -eq "stream_probe" })
$terminalCount = @($probeEvents | Where-Object { $_.terminalSeen -eq $true }).Count
$reliableCount = @($probeEvents | Where-Object { Test-ProbeEventReliable -Event $_ }).Count
$timeoutCount = @($probeEvents | Where-Object { $_.classification -eq "curl_timeout_without_terminal_event" }).Count
$classificationCounts = @{}
foreach ($event in $probeEvents) {
    $key = [string]$event.classification
    if (-not $classificationCounts.ContainsKey($key)) { $classificationCounts[$key] = 0 }
    $classificationCounts[$key] = [int]$classificationCounts[$key] + 1
}
$probeCoverage = @{}
foreach ($event in $probeEvents) {
    Add-ProbeCoverage -Coverage $probeCoverage -ProbeId ([string]$event.probeId)
}
$finalPerProbeReliability = New-PerProbeReliabilitySummary -ProbeEvents $probeEvents -ProbeDeck $probeDeck
$finalFailureFocus = New-FailureFocusSummary -PerProbeReliability $finalPerProbeReliability
$finalRecentWindowReliability = New-RecentWindowReliabilitySummary -ProbeEvents $probeEvents -WindowSize $RecentWindowSize
$finalRagSignalSummary = New-RagSignalAggregateSummary -ProbeEvents $probeEvents
$finalModelProof = New-ModelProofSummary -ProbeEvents $probeEvents
$finalReliability = New-ReliabilitySummary -IterationsCompleted $completed -TerminalSeenCount $reliableCount
$finalTransportSafetyOk = [bool]$finalReliability.meetsNineXTarget
$finalModelSuccessOk = $finalTransportSafetyOk -and [string]$finalModelProof.status -eq "verified"
$finalReplayProbeId = if (-not [string]::IsNullOrWhiteSpace($selectedProbeId)) {
    $selectedProbeId
} elseif (-not [string]::IsNullOrWhiteSpace([string]$finalRecentWindowReliability.worstRecentProbeId)) {
    [string]$finalRecentWindowReliability.worstRecentProbeId
} else {
    [string]$finalFailureFocus.worstProbeId
}
$finalReplayFailureCommand = New-ReplayFailureCommand -Port $Port -OutDir $OutDir -ProbeId $finalReplayProbeId

$summary = @{
    schemaVersion = "awx.chat_ui_vibe_soak.v1"
    ok = $finalTransportSafetyOk
    okScope = "transport-safety-only"
    transportSafetyOk = $finalTransportSafetyOk
    modelSuccessOk = $finalModelSuccessOk
    status = if ($completed -gt 0 -and $reliableCount -eq $completed) { "all-terminal" } elseif ($completed -gt 0) { "partial" } else { "no-probes" }
    mutationAllowed = [bool]$CloseConflictingListener
    port = $Port
    seed = $Seed
    selectedProbeId = $selectedProbeId
    targetedProbeMode = (-not [string]::IsNullOrWhiteSpace($selectedProbeId))
    replaySourcePath = $replaySourcePath
    replaySourceProbeId = $replaySourceProbeId
    iterationsRequested = $Iterations
    iterationsCompleted = $completed
    stopReason = $stopReason
    durationMinutes = $DurationMinutes
    requestBudgetMs = $RequestBudgetMs
    curlWindowSeconds = $CurlWindowSeconds
    rawPromptStored = $false
    rawStreamStored = $false
    listener = $listenerEvidence
    browserTargetUrlHash = $browserTargetUrlHash
    uiContract = $uiContract
    terminalSeenCount = $terminalCount
    reliableSuccessCount = $reliableCount
    timeoutCount = $timeoutCount
    classificationCounts = $classificationCounts
    probeCoverage = New-ProbeCoverageSummary -Coverage $probeCoverage -DeckSize $probeDeck.Count
    reliability = $finalReliability
    recentWindowReliability = $finalRecentWindowReliability
    perProbeReliability = $finalPerProbeReliability
    failureFocus = $finalFailureFocus
    ragSignalSummary = $finalRagSignalSummary
    modelProof = $finalModelProof
    replayFailureCommand = $finalReplayFailureCommand
    eventsPath = $eventsPath
    summaryPath = $summaryPath
    agentNextPath = $agentNextPath
    latestAgentNextPath = $latestAgentNextPath
    progressPath = $progressPath
    latestProgressPath = $latestProgressPath
    nextHandoffAction = if (-not [string]::IsNullOrWhiteSpace($stopReason)) { "inspect_events_then_patch_smallest_failure" } elseif ($completed -gt 0 -and $reliableCount -eq $completed) { "continue_long_soak" } elseif ($completed -gt 0) { "inspect_events_then_patch_smallest_failure" } else { "restart_listener_and_probe" }
    nextLongRunCommand = $longRunCommand
}
Write-HandoffArtifacts -Summary $summary -LatestSummaryPath $latestSummaryPath -HandoffPath $handoffPath
Write-AgentNextArtifact -Summary $summary -AgentNextPath $agentNextPath -LatestAgentNextPath $latestAgentNextPath
Write-JsonFile -Path $summaryPath -Data $summary
[Console]::Out.WriteLine(($summary | ConvertTo-Json -Depth 10 -Compress))
)))) {
    Write-Warning '[AWX][api-spend] ModelOverride blocked in agent mode; falling back to local default. Set AWX_AGENT_ALLOW_PAID_MODELS=1 to force.'
    $ModelOverride = ''
  }
}
$PSNativeCommandUseErrorActionPreference = $false
Add-Type -AssemblyName System.Net.Http

function Resolve-OutputDir {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Get-Location).Path $Path)
}

function Get-Hash12 {
    param([string]$Value)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes([string]$Value)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace("-", "").ToLowerInvariant()).Substring(0, 12)
    } finally {
        $sha.Dispose()
    }
}

function Get-RedactedRequestHash {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return "hash:unknown" }
    return "hash:" + (Get-Hash12 ([string]$Value).Trim())
}

function Resolve-RuntimeProofLogPath {
    param(
        [string]$ExplicitPath,
        [object]$ListenerResult
    )
    $candidate = $ExplicitPath
    if ([string]::IsNullOrWhiteSpace($candidate) -and $null -ne $ListenerResult) {
        try { $candidate = [string]$ListenerResult.parsed.listener.outLog } catch { $candidate = "" }
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) { return "" }
    if ([System.IO.Path]::IsPathRooted($candidate)) { return $candidate }
    return (Join-Path (Get-Location).Path $candidate)
}

function Get-RuntimeLogLength {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return -1L
    }
    try { return [long](Get-Item -LiteralPath $Path -ErrorAction Stop).Length } catch { return -1L }
}

function Get-ProofToken {
    param([string]$Line, [string]$Name)
    $match = [regex]::Match($Line, "(?:^|\s)" + [regex]::Escape($Name) + "=([^\s]+)")
    if ($match.Success) { return [string]$match.Groups[1].Value }
    return ""
}

function Convert-ProofBoolean {
    param([string]$Value)
    if ([string]$Value -eq "true") { return $true }
    if ([string]$Value -eq "false") { return $false }
    return $null
}

function Get-RequestProofSummary {
    param(
        [string]$LogPath,
        [long]$Offset,
        [string]$RequestHash,
        [int]$MaxTailBytes = 1048576
    )

    $empty = [ordered]@{
        requestHash = $RequestHash
        proofRowCount = 0
        attemptTotal = 0
        attemptDropped = 0
        distinctPromptHashCount = 0
        distinctOptionsHashCount = 0
        responseHashObservedCount = 0
        modelAdapterAttemptObservedCount = 0
        clientHttpExchangeObservedCount = 0
        clientHttpResponseObservedCount = 0
        providerAttemptObservedCount = 0
        wireAttemptObservedCount = 0
        responseObservedCount = 0
        correlationStatus = "evidence_needed"
        rawRequestIdStored = $false
        rawProofLinesStored = $false
    }
    if ([string]::IsNullOrWhiteSpace($LogPath) -or $Offset -lt 0L -or
        -not (Test-Path -LiteralPath $LogPath -PathType Leaf)) {
        return $empty
    }

    $tailText = ""
    try {
        $stream = [System.IO.File]::Open($LogPath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read,
            [System.IO.FileShare]::ReadWrite -bor [System.IO.FileShare]::Delete)
        try {
            if ($stream.Length -lt $Offset) { return $empty }
            $tailLength = [long]$stream.Length - $Offset
            if ($tailLength -gt [Math]::Max(1, $MaxTailBytes)) { return $empty }
            if ($tailLength -le 0L) { return $empty }
            [void]$stream.Seek($Offset, [System.IO.SeekOrigin]::Begin)
            $buffer = New-Object byte[] ([int]$tailLength)
            $readTotal = 0
            while ($readTotal -lt $buffer.Length) {
                $readNow = $stream.Read($buffer, $readTotal, $buffer.Length - $readTotal)
                if ($readNow -le 0) { break }
                $readTotal += $readNow
            }
            $tailText = [System.Text.Encoding]::UTF8.GetString($buffer, 0, $readTotal)
        } finally {
            $stream.Dispose()
        }
    } catch {
        return $empty
    }

    $rows = New-Object System.Collections.Generic.List[object]
    $matchingMalformed = 0
    $reportedAttemptTotal = 0
    $reportedAttemptDropped = 0
    $appendOrderInvalid = $false
    $perRowObservationInvalid = $false
    $hasPreviousAttemptCounts = $false
    $previousAttemptTotal = 0
    $previousAttemptDropped = 0
    $hasPreviousAcceptedSequence = $false
    $previousAcceptedSequence = 0
    foreach ($line in ($tailText -split "`r?`n")) {
        if (-not $line.Contains("[LLM_REQUEST_PROOF]")) { continue }
        $lineRequestHash = Get-ProofToken -Line $line -Name "requestHash"
        if ($lineRequestHash -ne $RequestHash) { continue }
        $sequenceText = Get-ProofToken -Line $line -Name "sequence"
        $attemptTotalText = Get-ProofToken -Line $line -Name "attemptTotal"
        $attemptDroppedText = Get-ProofToken -Line $line -Name "attemptDropped"
        $promptHash = Get-ProofToken -Line $line -Name "promptHash"
        $optionsHash = Get-ProofToken -Line $line -Name "optionsHash"
        $responseHash = Get-ProofToken -Line $line -Name "responseHash"
        $sequence = 0
        $attemptTotal = 0
        $attemptDropped = 0
        $rowAcceptedText = Get-ProofToken -Line $line -Name "rowAccepted"
        $rowAccepted = if ([string]::IsNullOrWhiteSpace($rowAcceptedText)) {
            $true
        } else {
            Convert-ProofBoolean $rowAcceptedText
        }
        $validAttemptCounts = [int]::TryParse($attemptTotalText, [ref]$attemptTotal) -and
            $attemptTotal -ge 0 -and
            [int]::TryParse($attemptDroppedText, [ref]$attemptDropped) -and
            $attemptDropped -ge 0
        if ($null -eq $rowAccepted -or -not $validAttemptCounts) {
            $matchingMalformed += 1
            continue
        }
        if ($hasPreviousAttemptCounts -and
            ($attemptTotal -lt $previousAttemptTotal -or $attemptDropped -lt $previousAttemptDropped)) {
            $appendOrderInvalid = $true
        }
        $previousAttemptTotal = $attemptTotal
        $previousAttemptDropped = $attemptDropped
        $hasPreviousAttemptCounts = $true
        $reportedAttemptTotal = [Math]::Max($reportedAttemptTotal, $attemptTotal)
        $reportedAttemptDropped = [Math]::Max($reportedAttemptDropped, $attemptDropped)
        if ($rowAccepted -eq $false) {
            continue
        }
        $adapterAttempt = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "adapterAttempt")
        $clientHttpExchange = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "clientHttpExchange")
        $clientHttpResponse = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "clientHttpResponse")
        $providerAttempt = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "providerAttempt")
        $wireAttempt = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "wireAttempt")
        $responseObserved = Convert-ProofBoolean (Get-ProofToken -Line $line -Name "responseObserved")
        $hashPattern = '^(?:hash:[0-9a-f]{12}|sha256:[0-9a-f]{64})$'
        $valid = [int]::TryParse($sequenceText, [ref]$sequence) -and $sequence -gt 0 -and
            $promptHash -match $hashPattern -and $optionsHash -match $hashPattern -and
            ($responseHash -match $hashPattern -or $responseHash -eq "hash:unknown") -and
            $null -ne $adapterAttempt -and $null -ne $clientHttpExchange -and
            $null -ne $clientHttpResponse -and $null -ne $providerAttempt -and
            $null -ne $wireAttempt -and $null -ne $responseObserved
        if (-not $valid) {
            $matchingMalformed += 1
            continue
        }
        if ($hasPreviousAcceptedSequence -and $sequence -le $previousAcceptedSequence) {
            $appendOrderInvalid = $true
        }
        $previousAcceptedSequence = $sequence
        $hasPreviousAcceptedSequence = $true
        $responseHashObserved = $responseHash -ne "hash:unknown"
        if (($clientHttpResponse -and -not $clientHttpExchange) -or
            ($clientHttpExchange -and -not $adapterAttempt) -or
            ([bool]$responseObserved -ne $responseHashObserved)) {
            $perRowObservationInvalid = $true
        }
        $rows.Add([pscustomobject]@{
            sequence = $sequence
            attemptTotal = $attemptTotal
            attemptDropped = $attemptDropped
            promptHash = $promptHash
            optionsHash = $optionsHash
            responseHash = $responseHash
            adapterAttempt = [bool]$adapterAttempt
            clientHttpExchange = [bool]$clientHttpExchange
            clientHttpResponse = [bool]$clientHttpResponse
            providerAttempt = [bool]$providerAttempt
            wireAttempt = [bool]$wireAttempt
            responseObserved = [bool]$responseObserved
        }) | Out-Null
    }

    $validRows = @($rows.ToArray())
    $empty.proofRowCount = $validRows.Count
    $empty.attemptTotal = $reportedAttemptTotal
    $empty.attemptDropped = $reportedAttemptDropped
    if ($validRows.Count -gt 0) {
        $empty.distinctPromptHashCount = @($validRows.promptHash | Sort-Object -Unique).Count
        $empty.distinctOptionsHashCount = @($validRows.optionsHash | Sort-Object -Unique).Count
        $empty.responseHashObservedCount = @($validRows | Where-Object { $_.responseHash -ne "hash:unknown" }).Count
        $empty.modelAdapterAttemptObservedCount = @($validRows | Where-Object { $_.adapterAttempt }).Count
        $empty.clientHttpExchangeObservedCount = @($validRows | Where-Object { $_.clientHttpExchange }).Count
        $empty.clientHttpResponseObservedCount = @($validRows | Where-Object { $_.clientHttpResponse }).Count
        $empty.providerAttemptObservedCount = @($validRows | Where-Object { $_.providerAttempt }).Count
        $empty.wireAttemptObservedCount = @($validRows | Where-Object { $_.wireAttempt }).Count
        $empty.responseObservedCount = @($validRows | Where-Object { $_.responseObserved }).Count
    }

    $uniqueSequenceCount = @($validRows.sequence | Sort-Object -Unique).Count
    $countAgreement = $validRows.Count -gt 0 -and
        $empty.attemptTotal -eq ($validRows.Count + $empty.attemptDropped)
    $monotonicCounts = $empty.clientHttpResponseObservedCount -le $empty.clientHttpExchangeObservedCount -and
        $empty.clientHttpExchangeObservedCount -le $empty.modelAdapterAttemptObservedCount
    $hasUnknownResponse = @($validRows | Where-Object { $_.responseHash -eq "hash:unknown" }).Count -gt 0
    $empty.correlationStatus = if ($validRows.Count -eq 0) {
        if ($matchingMalformed -gt 0) { "partial" } else { "evidence_needed" }
    } elseif ($uniqueSequenceCount -ne $validRows.Count -or -not $monotonicCounts -or
        $appendOrderInvalid -or $perRowObservationInvalid) {
        "ambiguous"
    } elseif ($matchingMalformed -gt 0 -or -not $countAgreement -or $hasUnknownResponse -or
        $empty.attemptDropped -gt 0) {
        "partial"
    } else {
        "verified"
    }
    return $empty
}

function Get-SafeSignalValue {
    param([object]$Value, [int]$MaxLength = 80)
    if ($null -eq $Value) { return "" }
    $safe = [regex]::Replace(([string]$Value).Trim(), "[^A-Za-z0-9._:/-]+", "_")
    if ($safe.Length -gt $MaxLength) { return $safe.Substring(0, $MaxLength) }
    return $safe
}

function Get-CountField {
    param([object]$Value, [string]$Name)
    if ($null -eq $Value -or -not ($Value.PSObject.Properties.Name -contains $Name)) { return 0L }
    try { return [Math]::Max(0L, [long]$Value.$Name) } catch { return 0L }
}

function Get-ChatUsageSnapshot {
    param([int]$Port)

    $empty = [ordered]@{
        observed = $false
        counterEpochStartedAtMs = 0L
        counterScope = "unavailable"
        wireAttemptCoverage = "unavailable"
        attempts = 0L
        responseReceived = 0L
        failedBeforeResponse = 0L
        timedOut = 0L
        cancelled = 0L
        providerUsageObserved = 0L
        providerUsageMissing = 0L
    }
    try {
        $heartbeat = Invoke-RestMethod `
            -Uri "http://127.0.0.1:$Port/api/chat/ui-heartbeat" `
            -Method Get `
            -TimeoutSec 2 `
            -ErrorAction Stop
        $usage = $heartbeat.debugAiMetrics.chatUsage
        if ($null -eq $usage -or $null -eq $usage.modelInvocations) { return $empty }
        $model = $usage.modelInvocations
        return [ordered]@{
            observed = ($usage.observed -eq $true)
            counterEpochStartedAtMs = Get-CountField -Value $usage -Name "counterEpochStartedAtMs"
            counterScope = Get-SafeSignalValue -Value $usage.counterScope
            wireAttemptCoverage = Get-SafeSignalValue -Value $usage.wireAttemptCoverage
            attempts = Get-CountField -Value $model -Name "attempts"
            responseReceived = Get-CountField -Value $model -Name "responseReceived"
            failedBeforeResponse = Get-CountField -Value $model -Name "failedBeforeResponse"
            timedOut = Get-CountField -Value $model -Name "timedOut"
            cancelled = Get-CountField -Value $model -Name "cancelled"
            providerUsageObserved = Get-CountField -Value $model -Name "providerUsageObservedAttemptCount"
            providerUsageMissing = Get-CountField -Value $model -Name "providerUsageMissingAttemptCount"
        }
    } catch {
        return $empty
    }
}

function Test-ProbeDirectDebugShortCircuitRisk {
    param([string]$Message)

    $lower = if ($null -eq $Message) { "" } else { $Message.ToLowerInvariant() }
    $debugSubject = @("debug", "trace", "heartbeat", "browser", "computer", "supabase", "matrix", "evidence_needed") |
        Where-Object { $lower.Contains($_) } |
        Select-Object -First 1
    $statusIntent = @("status", "state", "health", "diagnostic", "inspect", "report", "check", "show", "tell", "current", "stale", "operator", "why") |
        Where-Object { $lower.Contains($_) } |
        Select-Object -First 1
    $suppressed = @("without debug", "no debug", "debug-free") |
        Where-Object { $lower.Contains($_) } |
        Select-Object -First 1
    return $null -ne $debugSubject -and $null -ne $statusIntent -and $null -eq $suppressed
}

function Write-JsonFile {
    param([string]$Path, [hashtable]$Data)
    $Data | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Write-NdjsonEvent {
    param([string]$Path, [hashtable]$Data)
    ($Data | ConvertTo-Json -Depth 8 -Compress) | Add-Content -LiteralPath $Path -Encoding UTF8
}

function Get-NextProbeFromCoverageBag {
    param(
        [object[]]$ProbeDeck,
        [System.Random]$Random,
        [ref]$CoverageBag
    )
    $bag = @($CoverageBag.Value)
    if ($bag.Count -eq 0) {
        $bag = @($ProbeDeck)
    }
    $index = $Random.Next($bag.Count)
    $selected = $bag[$index]
    $remaining = New-Object System.Collections.Generic.List[object]
    for ($i = 0; $i -lt $bag.Count; $i++) {
        if ($i -ne $index) {
            $remaining.Add($bag[$i]) | Out-Null
        }
    }
    $CoverageBag.Value = $remaining.ToArray()
    return $selected
}

function Add-ProbeCoverage {
    param([hashtable]$Coverage, [string]$ProbeId)
    if ([string]::IsNullOrWhiteSpace($ProbeId)) { return }
    if (-not $Coverage.ContainsKey($ProbeId)) { $Coverage[$ProbeId] = 0 }
    $Coverage[$ProbeId] = [int]$Coverage[$ProbeId] + 1
}

function New-ProbeCoverageSummary {
    param([hashtable]$Coverage, [int]$DeckSize)
    return @{
        deckSize = $DeckSize
        uniqueProbeCount = $Coverage.Keys.Count
        counts = $Coverage
    }
}

function New-ReliabilitySummary {
    param(
        [int]$IterationsCompleted,
        [int]$TerminalSeenCount,
        [int]$TargetReliabilityPct = 90
    )
    if ($IterationsCompleted -le 0) {
        return @{
            status = "not-measured"
            reliabilityScorePct = 0
            targetReliabilityPct = 90
            meetsNineXTarget = $false
        }
    }
    $score = [Math]::Round(($TerminalSeenCount * 100.0) / $IterationsCompleted, 2)
    return @{
        status = if ($score -ge $TargetReliabilityPct) { "meets-target" } else { "below-target" }
        reliabilityScorePct = $score
        targetReliabilityPct = 90
        meetsNineXTarget = ($score -ge $TargetReliabilityPct)
    }
}

function New-PerProbeReliabilitySummary {
    param(
        [object[]]$ProbeEvents,
        [object[]]$ProbeDeck,
        [int]$TargetReliabilityPct = 90
    )
    $result = @{}
    foreach ($probe in $ProbeDeck) {
        $probeId = [string]$probe.id
        $eventsForProbe = @($ProbeEvents | Where-Object { [string]$_.probeId -eq $probeId })
        $attempts = $eventsForProbe.Count
        $terminalSeen = @($eventsForProbe | Where-Object { $_.terminalSeen -eq $true }).Count
        $reliableSuccess = @($eventsForProbe | Where-Object { Test-ProbeEventReliable -Event $_ }).Count
        $summary = New-ReliabilitySummary -IterationsCompleted $attempts -TerminalSeenCount $reliableSuccess -TargetReliabilityPct $TargetReliabilityPct
        $summary["probeId"] = $probeId
        $summary["attemptCount"] = $attempts
        $summary["terminalSeenCount"] = $terminalSeen
        $summary["reliableSuccessCount"] = $reliableSuccess
        $result[$probeId] = $summary
    }
    return $result
}

function Get-EventField {
    param(
        [object]$Event,
        [string]$Name
    )
    if ($null -eq $Event) { return $null }
    if ($Event -is [System.Collections.IDictionary]) { return $Event[$Name] }
    return $Event.$Name
}

function Test-ProbeEventReliable {
    param([object]$Event)
    if ((Get-EventField -Event $Event -Name "terminalSeen") -ne $true) { return $false }
    if ([string](Get-EventField -Event $Event -Name "transportCode") -ne "success") { return $false }
    if ([int](Get-EventField -Event $Event -Name "statusCode") -ge 400) { return $false }
    if ([string](Get-EventField -Event $Event -Name "classification") -eq "terminal_error_seen") { return $false }
    if (@(Get-EventField -Event $Event -Name "eventTypes") -contains "error") { return $false }
    if (@(Get-EventField -Event $Event -Name "payloadTypes") -contains "error") { return $false }
    $constraintRequired = (Get-EventField -Event $Event -Name "constraintIntegrityRequired") -eq $true
    if ($constraintRequired) {
        return [string](Get-EventField -Event $Event -Name "constraintIntegrityStatus") -eq "pass"
    }
    return $true
}

function New-ModelProofSummary {
    param([object[]]$ProbeEvents)

    $events = @($ProbeEvents)
    $modelObservedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "modelFinalObserved") -eq $true
    }).Count
    $providerAttemptObservedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "providerAttemptObserved") -eq $true
    }).Count
    $modelAttemptObservedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "modelAttemptObserved") -eq $true
    }).Count
    $providerUsageObservedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "providerUsageObserved") -eq $true
    }).Count
    $fallbackDetectedCount = @($events | Where-Object {
        (Get-EventField -Event $_ -Name "fallbackDetected") -eq $true
    }).Count
    $verifiedCount = @($events | Where-Object {
        [string](Get-EventField -Event $_ -Name "modelProofStatus") -eq "model-and-provider-observed"
    }).Count
    return @{
        observedCount = $events.Count
        modelObservedCount = $modelObservedCount
        modelAttemptObservedCount = $modelAttemptObservedCount
        providerAttemptObservedCount = $providerAttemptObservedCount
        providerUsageObservedCount = $providerUsageObservedCount
        fallbackDetectedCount = $fallbackDetectedCount
        verifiedCount = $verifiedCount
        status = if ($events.Count -eq 0) {
            "not-measured"
        } elseif ($verifiedCount -eq $events.Count) {
            "verified"
        } else {
            "evidence-needed"
        }
    }
}

function New-RecentWindowReliabilitySummary {
    param(
        [object[]]$ProbeEvents,
        [int]$WindowSize = 5,
        [int]$TargetReliabilityPct = 90
    )

    $events = @($ProbeEvents | Where-Object { $null -ne $_ })
    if ($events.Count -le 0) {
        return @{
            status = "not-measured"
            windowSize = $WindowSize
            observedCount = 0
            terminalSeenCount = 0
            reliabilityScorePct = 0
            targetReliabilityPct = 90
            meetsNineXTarget = $false
            probeIds = @()
            classificationCounts = @{}
            worstRecentProbeId = ""
            nextRecentFocus = "run_live_soak"
        }
    }

    $effectiveWindowSize = if ($WindowSize -le 0) { $events.Count } else { $WindowSize }
    $windowEvents = if ($events.Count -gt $effectiveWindowSize) {
        @($events | Select-Object -Last $effectiveWindowSize)
    } else {
        @($events)
    }
    $observedCount = @($windowEvents).Count
    $terminalSeen = @($windowEvents | Where-Object { (Get-EventField -Event $_ -Name "terminalSeen") -eq $true }).Count
    $reliableSuccess = @($windowEvents | Where-Object { Test-ProbeEventReliable -Event $_ }).Count
    $summary = New-ReliabilitySummary -IterationsCompleted $observedCount -TerminalSeenCount $reliableSuccess -TargetReliabilityPct $TargetReliabilityPct
    $classificationCounts = @{}
    foreach ($event in $windowEvents) {
        $classification = [string](Get-EventField -Event $event -Name "classification")
        if ([string]::IsNullOrWhiteSpace($classification)) { $classification = "unknown" }
        if (-not $classificationCounts.ContainsKey($classification)) { $classificationCounts[$classification] = 0 }
        $classificationCounts[$classification] = [int]$classificationCounts[$classification] + 1
    }
    $badEvents = @($windowEvents | Where-Object { -not (Test-ProbeEventReliable -Event $_) })
    $worstRecentProbeId = if ($badEvents.Count -gt 0) {
        [string](Get-EventField -Event ($badEvents | Select-Object -Last 1) -Name "probeId")
    } else {
        ""
    }

    return @{
        status = [string]$summary.status
        windowSize = $effectiveWindowSize
        observedCount = $observedCount
        terminalSeenCount = $terminalSeen
        reliableSuccessCount = $reliableSuccess
        reliabilityScorePct = [double]$summary.reliabilityScorePct
        targetReliabilityPct = 90
        meetsNineXTarget = [bool]$summary.meetsNineXTarget
        probeIds = @($windowEvents | ForEach-Object { [string](Get-EventField -Event $_ -Name "probeId") })
        classificationCounts = $classificationCounts
        worstRecentProbeId = $worstRecentProbeId
        nextRecentFocus = if ($summary.meetsNineXTarget -eq $true) { "continue_long_soak" } else { "replay_recent_worst_probe" }
    }
}

function New-FailureFocusSummary {
    param([hashtable]$PerProbeReliability)

    $measured = @()
    foreach ($probeId in $PerProbeReliability.Keys) {
        $item = $PerProbeReliability[$probeId]
        if ([int]$item["attemptCount"] -gt 0) {
            $measured += $item
        }
    }

    if ($measured.Count -eq 0) {
        return @{
            status = "no-measured-probes"
            belowTargetProbeIds = @()
            worstProbeId = ""
            worstReliabilityScorePct = 0
            nextFailureFocus = "run_live_soak"
        }
    }

    $belowTarget = @($measured | Where-Object { $_["meetsNineXTarget"] -ne $true })
    $worst = $measured |
        Sort-Object @{ Expression = { [double]$_["reliabilityScorePct"] }; Ascending = $true },
                    @{ Expression = { [string]$_["probeId"] }; Ascending = $true } |
        Select-Object -First 1
    $worstProbeId = if ($null -ne $worst) { [string]$worst["probeId"] } else { "" }

    return @{
        status = if ($belowTarget.Count -gt 0) { "below-target" } else { "no-failing-probes" }
        belowTargetProbeIds = @($belowTarget | ForEach-Object { [string]$_["probeId"] })
        worstProbeId = $worstProbeId
        worstReliabilityScorePct = if ($null -ne $worst) { [double]$worst["reliabilityScorePct"] } else { 0 }
        nextFailureFocus = if ($belowTarget.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($worstProbeId)) {
            "inspect_events_for_$worstProbeId"
        } else {
            "continue_long_soak"
        }
    }
}

function New-RagSignalSummary {
    param(
        [string]$Raw,
        [bool]$RequiresOfficialEvidence = $false
    )
    $text = if ($null -eq $Raw) { "" } else { [string]$Raw }
    $lower = $text.ToLowerInvariant()
    $officialDomainMentionCount = ([regex]::Matches($lower, "developers\.openai\.com|platform\.openai\.com|openai\.com/changelog|supabase\.com/docs|supabase\.com/changelog")).Count
    $offDomainCommunityMentionCount = ([regex]::Matches($lower, "github\.com|medium\.com|reddit|velog|tistory|kongju\.ac\.kr|hrd4u\.or\.kr")).Count
    $fakeCommandMentionCount = ([regex]::Matches($lower, "run_browser_local_ui_smoke|run_computer_use_lightweight_smoke|inspect_supabase_shadow_snapshot")).Count
    $containsEvidenceNeeded = $lower.Contains("evidence_needed")
    $officialEvidenceNeeded = $RequiresOfficialEvidence -and
        $officialDomainMentionCount -le 0 -and
        $containsEvidenceNeeded
    $officialEvidenceMissing = $RequiresOfficialEvidence -and
        $officialDomainMentionCount -le 0 -and
        -not $containsEvidenceNeeded
    $externalLaneSplitSeen = $lower.Contains("supporting_evidence_missing") -or
        $lower.Contains("project_ref_missing") -or
        ($lower.Contains("browser") -and $lower.Contains("computer") -and $lower.Contains("supabase"))
    $status = if ($fakeCommandMentionCount -gt 0) {
        "fake-command-risk"
    } elseif ($offDomainCommunityMentionCount -gt 0) {
        "off-domain-community-risk"
    } elseif ($officialEvidenceMissing) {
        "official-evidence-missing"
    } elseif ($officialEvidenceNeeded) {
        "official-evidence-needed"
    } elseif ($containsEvidenceNeeded -or $externalLaneSplitSeen -or $officialDomainMentionCount -gt 0) {
        "rag-signal-observed"
    } else {
        "no-rag-signal"
    }
    return @{
        status = $status
        containsEvidenceNeeded = $containsEvidenceNeeded
        officialDomainMentionCount = $officialDomainMentionCount
        offDomainCommunityMentionCount = $offDomainCommunityMentionCount
        fakeCommandMentionCount = $fakeCommandMentionCount
        officialEvidenceNeeded = $officialEvidenceNeeded
        officialEvidenceMissing = $officialEvidenceMissing
        externalLaneSplitSeen = $externalLaneSplitSeen
    }
}

function New-RagSignalAggregateSummary {
    param([object[]]$ProbeEvents)
    $events = @($ProbeEvents | Where-Object { $null -ne $_ })
    if ($events.Count -le 0) {
        return @{
            status = "not-measured"
            observedCount = 0
            evidenceNeededSeenCount = 0
            externalLaneSplitSeenCount = 0
            officialDomainMentionCount = 0
            offDomainCommunityMentionCount = 0
            fakeCommandMentionCount = 0
            officialEvidenceNeededCount = 0
            officialEvidenceMissingCount = 0
            nextRagSignalFocus = "run_live_soak"
        }
    }
    $evidenceNeededSeenCount = 0
    $externalLaneSplitSeenCount = 0
    $officialDomainMentionCount = 0
    $offDomainCommunityMentionCount = 0
    $fakeCommandMentionCount = 0
    $officialEvidenceNeededCount = 0
    $officialEvidenceMissingCount = 0
    foreach ($event in $events) {
        if ((Get-EventField -Event $event -Name "containsEvidenceNeeded") -eq $true) { $evidenceNeededSeenCount += 1 }
        if ((Get-EventField -Event $event -Name "externalLaneSplitSeen") -eq $true) { $externalLaneSplitSeenCount += 1 }
        if ((Get-EventField -Event $event -Name "officialEvidenceNeeded") -eq $true) { $officialEvidenceNeededCount += 1 }
        if ((Get-EventField -Event $event -Name "officialEvidenceMissing") -eq $true) { $officialEvidenceMissingCount += 1 }
        $officialDomainMentionCount += [int](Get-EventField -Event $event -Name "officialDomainMentionCount")
        $offDomainCommunityMentionCount += [int](Get-EventField -Event $event -Name "offDomainCommunityMentionCount")
        $fakeCommandMentionCount += [int](Get-EventField -Event $event -Name "fakeCommandMentionCount")
    }
    $status = if ($fakeCommandMentionCount -gt 0) {
        "fake-command-risk"
    } elseif ($offDomainCommunityMentionCount -gt 0) {
        "off-domain-community-risk"
    } elseif ($officialEvidenceMissingCount -gt 0) {
        "official-evidence-missing"
    } elseif ($officialEvidenceNeededCount -gt 0) {
        "official-evidence-needed"
    } elseif ($evidenceNeededSeenCount -gt 0 -or $externalLaneSplitSeenCount -gt 0 -or $officialDomainMentionCount -gt 0) {
        "rag-signal-observed"
    } else {
        "no-rag-signal"
    }
    $nextFocus = if ($fakeCommandMentionCount -gt 0) {
        "replay_skill_lane_external_split"
    } elseif ($offDomainCommunityMentionCount -gt 0) {
        "replay_official_changelog_evidence"
    } elseif ($officialEvidenceMissingCount -gt 0) {
        "replay_official_changelog_evidence"
    } elseif ($officialEvidenceNeededCount -gt 0) {
        "inspect_official_search_quality_gate"
    } elseif ($status -eq "no-rag-signal") {
        "run_official_or_skill_probe"
    } else {
        "continue_long_soak"
    }
    return @{
        status = $status
        observedCount = $events.Count
        evidenceNeededSeenCount = $evidenceNeededSeenCount
        externalLaneSplitSeenCount = $externalLaneSplitSeenCount
        officialDomainMentionCount = $officialDomainMentionCount
        offDomainCommunityMentionCount = $offDomainCommunityMentionCount
        fakeCommandMentionCount = $fakeCommandMentionCount
        officialEvidenceNeededCount = $officialEvidenceNeededCount
        officialEvidenceMissingCount = $officialEvidenceMissingCount
        nextRagSignalFocus = $nextFocus
    }
}

function New-ReplayFailureCommand {
    param(
        [int]$Port,
        [string]$OutDir,
        [string]$ProbeId,
        [int]$Iterations = 5
    )
    $targetProbeId = if ([string]::IsNullOrWhiteSpace($ProbeId)) { "<probe_id>" } else { $ProbeId }
    return "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak.ps1 -Port $Port -Iterations $Iterations -DurationMinutes 5 -ProbeId $targetProbeId -CloseConflictingListener -OutDir $OutDir"
}

function New-LongRunCommand {
    param(
        [int]$Port,
        [string]$OutDir,
        [int]$RecentWindowSize
    )
    return "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak.ps1 -Port $Port -DurationMinutes 540 -Iterations 56 -RecentWindowSize $RecentWindowSize -StopOnRecentBelowTarget -CloseConflictingListener -OutDir $OutDir"
}

function Resolve-ReplayProbeIdFromSummary {
    param([string]$SummaryPath)

    if ([string]::IsNullOrWhiteSpace($SummaryPath)) { return "" }
    $resolvedSummaryPath = if ([System.IO.Path]::IsPathRooted($SummaryPath)) {
        $SummaryPath
    } else {
        Join-Path (Get-Location).Path $SummaryPath
    }
    if (-not (Test-Path -LiteralPath $resolvedSummaryPath)) {
        throw "ReplayFromSummaryPath not found: $resolvedSummaryPath"
    }

    $summary = Get-Content -Raw -LiteralPath $resolvedSummaryPath | ConvertFrom-Json
    $belowTarget = @($summary.failureFocus.belowTargetProbeIds | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    if ($belowTarget.Count -gt 0) { return [string]$belowTarget[0] }

    $worstProbeId = [string]$summary.failureFocus.worstProbeId
    if (-not [string]::IsNullOrWhiteSpace($worstProbeId)) { return $worstProbeId }

    $selectedProbeId = [string]$summary.selectedProbeId
    if (-not [string]::IsNullOrWhiteSpace($selectedProbeId)) { return $selectedProbeId }

    return ""
}

function New-AgentNextArtifact {
    param([hashtable]$Summary)

    $hasMeasuredFailure = [string]$Summary.failureFocus.status -eq "below-target" -or
        [string]$Summary.recentWindowReliability.status -eq "below-target"
    $recommendedCommand = if ($hasMeasuredFailure -and -not [string]::IsNullOrWhiteSpace([string]$Summary.replayFailureCommand)) {
        [string]$Summary.replayFailureCommand
    } elseif (-not [string]::IsNullOrWhiteSpace([string]$Summary.nextLongRunCommand)) {
        [string]$Summary.nextLongRunCommand
    } else {
        "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak.ps1 -Port $($Summary.port) -DurationMinutes 540 -Iterations 56 -CloseConflictingListener"
    }

    return @{
        schemaVersion = "awx.chat_ui_vibe_soak.agent_next.v1"
        generatedAt = (Get-Date).ToString("o")
        status = [string]$Summary.status
        ok = [bool]$Summary.ok
        port = [int]$Summary.port
        summaryPath = [string]$Summary.summaryPath
        eventsPath = [string]$Summary.eventsPath
        progressPath = [string]$Summary.progressPath
        stopReason = [string]$Summary.stopReason
        selectedProbeId = [string]$Summary.selectedProbeId
        replaySourcePath = [string]$Summary.replaySourcePath
        replaySourceProbeId = [string]$Summary.replaySourceProbeId
        nextHandoffAction = [string]$Summary.nextHandoffAction
        nextFailureFocus = [string]$Summary.failureFocus.nextFailureFocus
        failureFocusStatus = [string]$Summary.failureFocus.status
        worstProbeId = [string]$Summary.failureFocus.worstProbeId
        belowTargetProbeIds = @($Summary.failureFocus.belowTargetProbeIds | ForEach-Object { [string]$_ })
        recentWindowStatus = [string]$Summary.recentWindowReliability.status
        recentWindowObservedCount = [int]$Summary.recentWindowReliability.observedCount
        recentWindowWorstProbeId = [string]$Summary.recentWindowReliability.worstRecentProbeId
        recentWindowNextFocus = [string]$Summary.recentWindowReliability.nextRecentFocus
        ragSignalStatus = [string]$Summary.ragSignalSummary.status
        ragSignalObservedCount = [int]$Summary.ragSignalSummary.observedCount
        ragSignalEvidenceNeededSeenCount = [int]$Summary.ragSignalSummary.evidenceNeededSeenCount
        ragSignalOffDomainCommunityMentionCount = [int]$Summary.ragSignalSummary.offDomainCommunityMentionCount
        ragSignalFakeCommandMentionCount = [int]$Summary.ragSignalSummary.fakeCommandMentionCount
        ragSignalOfficialEvidenceNeededCount = [int]$Summary.ragSignalSummary.officialEvidenceNeededCount
        ragSignalOfficialEvidenceMissingCount = [int]$Summary.ragSignalSummary.officialEvidenceMissingCount
        nextRagSignalFocus = [string]$Summary.ragSignalSummary.nextRagSignalFocus
        replayFailureCommand = [string]$Summary.replayFailureCommand
        nextLongRunCommand = [string]$Summary.nextLongRunCommand
        recommendedCommand = $recommendedCommand
        rawPromptStored = [bool]$Summary.rawPromptStored
        rawStreamStored = [bool]$Summary.rawStreamStored
    }
}

function Write-AgentNextArtifact {
    param(
        [hashtable]$Summary,
        [string]$AgentNextPath,
        [string]$LatestAgentNextPath
    )

    $agentNext = New-AgentNextArtifact -Summary $Summary
    $agentNext["agentNextPath"] = $AgentNextPath
    $agentNext["latestAgentNextPath"] = $LatestAgentNextPath
    Write-JsonFile -Path $AgentNextPath -Data $agentNext
    Write-JsonFile -Path $LatestAgentNextPath -Data $agentNext
}

function Write-ProgressCheckpoint {
    param(
        [hashtable]$Progress,
        [string]$ProgressPath,
        [string]$LatestProgressPath
    )
    $Progress["progressPath"] = $ProgressPath
    $Progress["latestProgressPath"] = $LatestProgressPath
    Write-JsonFile -Path $ProgressPath -Data $Progress
    Write-JsonFile -Path $LatestProgressPath -Data $Progress
}

function Write-HandoffArtifacts {
    param(
        [hashtable]$Summary,
        [string]$LatestSummaryPath,
        [string]$HandoffPath
    )
    $Summary["latestSummaryPath"] = $LatestSummaryPath
    $Summary["handoffPath"] = $HandoffPath
    if (-not $Summary.ContainsKey("nextHandoffAction")) {
        $Summary["nextHandoffAction"] = if ([string]$Summary["status"] -eq "all-terminal") {
            "continue_long_soak"
        } elseif ([string]$Summary["status"] -eq "dry-run") {
            "run_live_soak"
        } else {
            "inspect_events_then_patch_smallest_failure"
        }
    }
    Write-JsonFile -Path $LatestSummaryPath -Data $Summary
    $lines = @(
        "# chat-ui vibe soak handoff",
        "",
        "- schemaVersion: $($Summary.schemaVersion)",
        "- status: $($Summary.status)",
        "- ok: $($Summary.ok)",
        "- port: $($Summary.port)",
        "- seed: $($Summary.seed)",
        "- iterationsCompleted: $($Summary.iterationsCompleted)",
        "- terminalSeenCount: $($Summary.terminalSeenCount)",
        "- timeoutCount: $($Summary.timeoutCount)",
        "- rawPromptStored: $($Summary.rawPromptStored)",
        "- rawStreamStored: $($Summary.rawStreamStored)",
        "- eventsPath: $($Summary.eventsPath)",
        "- summaryPath: $($Summary.summaryPath)",
        "- latestSummaryPath: $LatestSummaryPath",
        "- nextHandoffAction: $($Summary.nextHandoffAction)",
        "- nextFailureFocus: $($Summary.failureFocus.nextFailureFocus)",
        "- ragSignalStatus: $($Summary.ragSignalSummary.status)",
        "- nextRagSignalFocus: $($Summary.ragSignalSummary.nextRagSignalFocus)",
        "- ragSignalFakeCommandMentionCount: $($Summary.ragSignalSummary.fakeCommandMentionCount)",
        "- ragSignalOffDomainCommunityMentionCount: $($Summary.ragSignalSummary.offDomainCommunityMentionCount)",
        "- ragSignalOfficialEvidenceNeededCount: $($Summary.ragSignalSummary.officialEvidenceNeededCount)",
        "- ragSignalOfficialEvidenceMissingCount: $($Summary.ragSignalSummary.officialEvidenceMissingCount)",
        "- replayFailureCommand: $($Summary.replayFailureCommand)",
        "",
        "Run continuation:",
        "",
        '```powershell',
        "$($Summary.nextLongRunCommand)",
        '```'
    )
    Set-Content -LiteralPath $HandoffPath -Value $lines -Encoding UTF8
}

function ConvertTo-EventSummary {
    param([string]$Raw)
    $events = New-Object System.Collections.Generic.List[object]
    $currentEvent = $null
    foreach ($line in ($Raw -split "`r?`n")) {
        if ($line.StartsWith("event:")) {
            $currentEvent = $line.Substring(6).Trim()
        } elseif ($line.StartsWith("data:")) {
            $data = $line.Substring(5).Trim()
            $payloadType = $null
            try { $payloadType = ((ConvertFrom-Json $data -ErrorAction Stop).type) } catch { $payloadType = $null }
            $events.Add([pscustomobject]@{
                event = $currentEvent
                payloadType = $payloadType
                dataHash = Get-Hash12 $data
                dataLength = $data.Length
            }) | Out-Null
        }
    }
    return $events.ToArray()
}

function Invoke-ListenerReady {
    param([string]$RunDir)
    if ($SkipListenerStart -or $DryRun) {
        return @{
            skipped = $true
            status = if ($DryRun) { "dry-run" } else { "skip-listener-start" }
        }
    }
    $listenerScript = Join-Path $PSScriptRoot "chat_ui_vibe_listener.ps1"
    $args = @(
        "-NoProfile", "-ExecutionPolicy", "Bypass",
        "-File", $listenerScript,
        "-Port", ([string]$Port),
        "-OutDir", $RunDir,
        "-ReadyTimeoutSeconds", "180"
    )
    if ($CloseConflictingListener) {
        $args += "-CloseConflictingListener"
    }
    $stdoutPath = Join-Path $RunDir "chat-ui-vibe-soak-listener.stdout.log"
    $stderrPath = Join-Path $RunDir "chat-ui-vibe-soak-listener.stderr.log"
    $proc = Start-Process -FilePath "powershell" `
        -ArgumentList $args `
        -PassThru `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -WindowStyle Hidden
    if (-not $proc.WaitForExit($ListenerTimeoutSeconds * 1000)) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        return @{
            skipped = $false
            ok = $false
            status = "listener-timeout"
            exitCode = $null
            timeoutSeconds = $ListenerTimeoutSeconds
            stdoutPath = $stdoutPath
            stderrPath = $stderrPath
        }
    }
    $proc.Refresh()
    $exitCode = $proc.ExitCode
    $output = @()
    if (Test-Path -LiteralPath $stdoutPath) {
        $output += Get-Content -LiteralPath $stdoutPath -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $stderrPath) {
        $output += Get-Content -LiteralPath $stderrPath -ErrorAction SilentlyContinue
    }
    $jsonLine = @($output | Where-Object { [string]$_ -match "^\s*\{" } | Select-Object -Last 1)
    $parsed = $null
    if ($jsonLine) {
        try { $parsed = ($jsonLine | ConvertFrom-Json) } catch { $parsed = $null }
    }
    $parsedOk = $parsed -and $parsed.ok -eq $true
    if ($exitCode -ne 0 -and -not $parsedOk) {
        return @{
            skipped = $false
            ok = $false
            status = "listener-failed"
            exitCode = $exitCode
            parsed = $parsed
        }
    }
    return @{
        skipped = $false
        ok = $parsedOk
        status = if ($parsedOk) { "listener-ready" } else { "listener-unknown" }
        exitCode = $exitCode
        parsed = $parsed
    }
}

function Test-ChatUiContract {
    $url = "http://127.0.0.1:$Port/chat-ui?codexSmoke=vibe-soak"
    try {
        $response = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        $html = [string]$response.Content
        return @{
            ok = $true
            statusCode = [int]$response.StatusCode
            authModeDisabled = $html.Contains('data-auth-mode="disabled-test"')
            featurePoolOpen = $html.Contains('data-vibe-feature-pool="open"')
            urlHash = Get-Hash12 $url
            htmlLength = $html.Length
        }
    } catch {
        return @{
            ok = $false
            statusCode = 0
            authModeDisabled = $false
            featurePoolOpen = $false
            errorHash = Get-Hash12 ([string]$_)
            errorLength = ([string]$_).Length
        }
    }
}

function Invoke-StreamProbe {
    param(
        [hashtable]$Probe,
        [int]$Attempt,
        [string]$RunDir
    )
    $url = "http://127.0.0.1:$Port/api/chat/stream"
    $requestId = "codex-vibe-soak-$runId-$Attempt"
    $isolatedSession = $false
    if ($Probe.ContainsKey("isolatedSession")) {
        $isolatedSession = [bool]$Probe.isolatedSession
    }
    $requiresConstraintIntegrity = $Probe.ContainsKey("requiresConstraintIntegrity") -and
        [bool]$Probe.requiresConstraintIntegrity
    $constraintText = [System.Text.StringBuilder]::new()
    $probeIdToken = ([string]$Probe.id) -replace '[^A-Za-z0-9_-]', '_'
    $sessionId = if ($isolatedSession) {
        "codex-vibe-soak-session-$runId-$Attempt-$probeIdToken"
    } else {
        "codex-vibe-soak-session-$runId"
    }
    $body = [ordered]@{
        message = [string]$Probe.message
        sessionId = $sessionId
        useRag = [bool]$Probe.useRag
        useWebSearch = [bool]$Probe.useWebSearch
        searchMode = [string]$Probe.searchMode
        maxTokens = $MaxTokens
    }
    if (-not [string]::IsNullOrWhiteSpace($ModelOverride)) {
        $body["model"] = Get-SafeSignalValue -Value $ModelOverride -MaxLength 120
    }
    $bodyJson = $body | ConvertTo-Json -Depth 6 -Compress
    $tempResiduePattern = "awx-chat-vibe-soak-*-$runId-$Attempt.*"
    $tempResidueBefore = @(
        Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Filter $tempResiduePattern -File -ErrorAction SilentlyContinue |
            ForEach-Object { $_.FullName }
    )
    $usageBefore = if ($CurlWindowSeconds -gt 1) {
        Get-ChatUsageSnapshot -Port $Port
    } else {
        $null
    }
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $curlExit = 0
    $transportCode = "success"
    $transportErrorType = ""
    $statusCode = $null
    $events = New-Object System.Collections.Generic.List[object]
    $ragSignalRows = New-Object System.Collections.Generic.List[object]
    $answerContractHold = $false
    $answerContractReason = "none"
    $observedModelUsed = ""
    $observedAnswerMode = ""
    $observedRoute = ""
    $observedPipelineFailureClass = ""
    $observedPipelineDisabledReason = ""
    $observedDebugFxCode = ""
    $observedDebugFxFailureClass = ""
    $observedDebugFxTriggerReason = ""
    $providerAttemptObserved = $false
    $currentEvent = $null
    $httpClient = $null
    $request = $null
    $response = $null
    $content = $null
    $runtimeLogOffsetBeforeRequest = -1L
    $timeoutCts = [System.Threading.CancellationTokenSource]::new()
    $timeoutCts.CancelAfter([TimeSpan]::FromSeconds([Math]::Max(1, $CurlWindowSeconds)))
    try {
        $httpClient = [System.Net.Http.HttpClient]::new()
        $httpClient.Timeout = [System.Threading.Timeout]::InfiniteTimeSpan
        $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, $url)
        $request.Headers.TryAddWithoutValidation("X-Request-Id", $requestId) | Out-Null
        $request.Headers.TryAddWithoutValidation("X-Budget-Ms", [string]$RequestBudgetMs) | Out-Null
        $content = [System.Net.Http.StringContent]::new(
            $bodyJson,
            [System.Text.Encoding]::UTF8,
            "application/json")
        $request.Content = $content
        $runtimeLogOffsetBeforeRequest = Get-RuntimeLogLength -Path $ownedRuntimeLogPath
        $response = $httpClient.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead,
            $timeoutCts.Token).GetAwaiter().GetResult()
        $statusCode = [int]$response.StatusCode

        $stream = $response.Content.ReadAsStreamAsync().GetAwaiter().GetResult()
        $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::UTF8, $true, 4096, $false)
        try {
            while ($true) {
                $lineTask = $reader.ReadLineAsync()
                $remainingMs = [Math]::Max(1, ($CurlWindowSeconds * 1000) - [int]$sw.ElapsedMilliseconds)
                if (-not $lineTask.Wait($remainingMs)) {
                    $timeoutCts.Cancel()
                    throw [System.TimeoutException]::new("stream deadline elapsed")
                }
                $line = $lineTask.GetAwaiter().GetResult()
                if ($null -eq $line) { break }
                if ($line.StartsWith("event:")) {
                    $currentEvent = $line.Substring(6).Trim()
                    continue
                }
                if (-not $line.StartsWith("data:")) { continue }

                $data = $line.Substring(5).Trim()
                $payloadType = $null
                $payload = $null
                try {
                    $payload = ConvertFrom-Json $data -ErrorAction Stop
                    $payloadType = $payload.type
                } catch {
                    $payloadType = $null
                }
                if ($null -ne $payload) {
                    if ($payload.PSObject.Properties.Name -contains "modelUsed") {
                        $candidateModel = Get-SafeSignalValue -Value $payload.modelUsed
                        if (-not [string]::IsNullOrWhiteSpace($candidateModel)) {
                            $observedModelUsed = $candidateModel
                        }
                    }
                    if ($payload.PSObject.Properties.Name -contains "answerMode") {
                        $candidateAnswerMode = Get-SafeSignalValue -Value $payload.answerMode
                        if (-not [string]::IsNullOrWhiteSpace($candidateAnswerMode)) {
                            $observedAnswerMode = $candidateAnswerMode
                        }
                    }
                    if ($payload.PSObject.Properties.Name -contains "pipelineSnapshot" -and
                        $null -ne $payload.pipelineSnapshot) {
                        $pipelineSnapshot = $payload.pipelineSnapshot
                        foreach ($pipelineField in @(
                            @{ name = "route"; target = "route" },
                            @{ name = "failureClass"; target = "failure" },
                            @{ name = "disabledReason"; target = "disabled" }
                        )) {
                            if ($pipelineSnapshot.PSObject.Properties.Name -contains $pipelineField.name) {
                                $candidatePipelineValue = Get-SafeSignalValue -Value $pipelineSnapshot.($pipelineField.name)
                                if (-not [string]::IsNullOrWhiteSpace($candidatePipelineValue)) {
                                    switch ($pipelineField.target) {
                                        "route" { $observedRoute = $candidatePipelineValue }
                                        "failure" { $observedPipelineFailureClass = $candidatePipelineValue }
                                        "disabled" { $observedPipelineDisabledReason = $candidatePipelineValue }
                                    }
                                }
                            }
                        }
                    }
                    if ($payload.PSObject.Properties.Name -contains "debugFxSignal" -and
                        $null -ne $payload.debugFxSignal) {
                        $debugFxSignal = $payload.debugFxSignal
                        if ($debugFxSignal.PSObject.Properties.Name -contains "code") {
                            $observedDebugFxCode = Get-SafeSignalValue -Value $debugFxSignal.code
                        }
                        if ($debugFxSignal.PSObject.Properties.Name -contains "labels" -and
                            $null -ne $debugFxSignal.labels) {
                            foreach ($debugLabel in @(
                                @{ name = "localLlmFailureClass"; target = "failure" },
                                @{ name = "localLlmTriggerReason"; target = "trigger" }
                            )) {
                                if ($debugFxSignal.labels.PSObject.Properties.Name -contains $debugLabel.name) {
                                    $candidateDebugValue = Get-SafeSignalValue -Value $debugFxSignal.labels.($debugLabel.name)
                                    if (-not [string]::IsNullOrWhiteSpace($candidateDebugValue)) {
                                        switch ($debugLabel.target) {
                                            "failure" { $observedDebugFxFailureClass = $candidateDebugValue }
                                            "trigger" { $observedDebugFxTriggerReason = $candidateDebugValue }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    foreach ($providerField in @("providerAttemptObserved", "providerAttempted")) {
                        if ($payload.PSObject.Properties.Name -contains $providerField -and
                            $payload.$providerField -is [bool]) {
                            $providerAttemptObserved = [bool]$payload.$providerField
                        }
                    }
                }
                if ($requiresConstraintIntegrity -and $null -ne $payload) {
                    foreach ($field in @("data", "content", "token", "text", "message")) {
                        if ($payload.PSObject.Properties.Name -contains $field) {
                            $fragment = $payload.$field
                            if ($fragment -is [string]) {
                                [void]$constraintText.Append([string]$fragment)
                                $isAnswerEvent = $currentEvent -in @("token", "final") -or
                                    $payloadType -in @("token", "final")
                                $answerFragment = ([string]$fragment).TrimStart()
                                $hasHoldPrefix = $answerFragment.StartsWith(
                                    "HOLD",
                                    [System.StringComparison]::OrdinalIgnoreCase)
                                $limitationIndex = $answerFragment.IndexOf(
                                    "limitation:",
                                    [System.StringComparison]::OrdinalIgnoreCase)
                                $koLimitation = ([char]0xD55C).ToString() + [char]0xACC4
                                $hasLimitation = $answerFragment.Contains($koLimitation + ":") -or
                                    $answerFragment.Contains($koLimitation + [char]0xFF1A) -or
                                    $limitationIndex -ge 0
                                if ($isAnswerEvent -and $hasHoldPrefix -and $hasLimitation) {
                                    $answerContractHold = $true
                                    $answerContractReason = "explicit_hold"
                                }
                            }
                        }
                    }
                }
                $events.Add([pscustomobject]@{
                    event = $currentEvent
                    payloadType = $payloadType
                    dataHash = Get-Hash12 $data
                    dataLength = $data.Length
                }) | Out-Null
                $ragSignalRows.Add([pscustomobject](New-RagSignalSummary -Raw $data)) | Out-Null

                if ($currentEvent -in @("done", "complete", "error", "final") -or
                    $payloadType -in @("done", "complete", "error", "final")) {
                    break
                }
            }
        } finally {
            $reader.Dispose()
        }
        if ($statusCode -ge 400) {
            $transportCode = "http_error"
        }
    } catch [System.TimeoutException] {
        $curlExit = 28
        $transportCode = "timeout"
        $transportFailure = $_.Exception
        while ($null -ne $transportFailure.InnerException) { $transportFailure = $transportFailure.InnerException }
        $transportErrorType = $transportFailure.GetType().FullName
    } catch [System.OperationCanceledException] {
        $curlExit = 28
        $transportCode = "timeout"
        $transportFailure = $_.Exception
        while ($null -ne $transportFailure.InnerException) { $transportFailure = $transportFailure.InnerException }
        $transportErrorType = $transportFailure.GetType().FullName
    } catch {
        $curlExit = -1
        $transportCode = "transport_error"
        $transportFailure = $_.Exception
        while ($null -ne $transportFailure.InnerException) { $transportFailure = $transportFailure.InnerException }
        $transportErrorType = $transportFailure.GetType().FullName
    } finally {
        if ($response) { $response.Dispose() }
        if ($request) { $request.Dispose() }
        if ($content) { $content.Dispose() }
        if ($httpClient) { $httpClient.Dispose() }
        $timeoutCts.Dispose()
    }
    $sw.Stop()
    $requestProof = Get-RequestProofSummary `
        -LogPath $ownedRuntimeLogPath `
        -Offset $runtimeLogOffsetBeforeRequest `
        -RequestHash (Get-RedactedRequestHash $requestId) `
        -MaxTailBytes $MaxProofTailBytes
    $usageAfter = if ($null -ne $usageBefore -and $transportCode -eq "success") {
        Get-ChatUsageSnapshot -Port $Port
    } else {
        $null
    }
    $usageWindowObserved = $null -ne $usageBefore -and
        $usageBefore.observed -eq $true -and
        $null -ne $usageAfter -and
        $usageAfter.observed -eq $true -and
        [long]$usageBefore.counterEpochStartedAtMs -gt 0 -and
        [long]$usageBefore.counterEpochStartedAtMs -eq [long]$usageAfter.counterEpochStartedAtMs
    $modelAttemptDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.attempts - [long]$usageBefore.attempts)
    } else { 0L }
    $modelResponseReceivedDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.responseReceived - [long]$usageBefore.responseReceived)
    } else { 0L }
    $modelFailedBeforeResponseDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.failedBeforeResponse - [long]$usageBefore.failedBeforeResponse)
    } else { 0L }
    $modelTimedOutDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.timedOut - [long]$usageBefore.timedOut)
    } else { 0L }
    $modelCancelledDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.cancelled - [long]$usageBefore.cancelled)
    } else { 0L }
    $providerUsageObservedDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.providerUsageObserved - [long]$usageBefore.providerUsageObserved)
    } else { 0L }
    $providerUsageMissingDelta = if ($usageWindowObserved) {
        [Math]::Max(0L, [long]$usageAfter.providerUsageMissing - [long]$usageBefore.providerUsageMissing)
    } else { 0L }
    $tempResidueAfter = @(
        Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) -Filter $tempResiduePattern -File -ErrorAction SilentlyContinue |
            ForEach-Object { $_.FullName }
    )
    $newTempResidues = @($tempResidueAfter | Where-Object { $tempResidueBefore -notcontains $_ })

    $eventRows = @($events.ToArray())
    $errorTerminalSeen = @($eventRows | Where-Object {
        $_.event -eq "error" -or $_.payloadType -eq "error"
    }).Count -gt 0
    $terminalSeen = @($eventRows | Where-Object {
        $_.event -in @("done", "complete", "error", "final") -or $_.payloadType -in @("done", "complete", "error", "final")
    }).Count -gt 0
    $classification = if ($errorTerminalSeen) {
        "terminal_error_seen"
    } elseif ($terminalSeen) {
        "terminal_event_seen"
    } elseif ($curlExit -eq 28) {
        "curl_timeout_without_terminal_event"
    } elseif ($statusCode -ge 400) {
        "http_error_without_terminal_event"
    } else {
        "no_terminal_event"
    }
    $requiresOfficialEvidence = $Probe.ContainsKey("requiresOfficialEvidence") -and [bool]$Probe.requiresOfficialEvidence
    $ragAggregate = New-RagSignalAggregateSummary -ProbeEvents @($ragSignalRows.ToArray())
    $containsEvidenceNeeded = [int]$ragAggregate.evidenceNeededSeenCount -gt 0
    $officialDomainMentionCount = [int]$ragAggregate.officialDomainMentionCount
    $offDomainCommunityMentionCount = [int]$ragAggregate.offDomainCommunityMentionCount
    $fakeCommandMentionCount = [int]$ragAggregate.fakeCommandMentionCount
    $externalLaneSplitSeen = [int]$ragAggregate.externalLaneSplitSeenCount -gt 0
    $constraintRaw = $constraintText.ToString().ToLowerInvariant()
    $constraintComparable = [regex]::Replace($constraintRaw, "\s+", "")
    $observedConstraintBuilder = [System.Text.StringBuilder]::new()
    $inferenceConstraintBuilder = [System.Text.StringBuilder]::new()
    $orderedSectionPairCount = 0
    $constraintLabelMatches = [regex]::Matches(
        $constraintComparable,
        "(?<observed>observed_constraints:|\uAD00\uCC30\uC0AC\uC2E4:)|(?<inference>inference:|\uCD94\uB860:)")
    for ($labelIndex = 0; $labelIndex -lt $constraintLabelMatches.Count; $labelIndex += 1) {
        $labelMatch = $constraintLabelMatches[$labelIndex]
        $sectionStart = $labelMatch.Index + $labelMatch.Length
        $sectionEnd = if ($labelIndex + 1 -lt $constraintLabelMatches.Count) {
            $constraintLabelMatches[$labelIndex + 1].Index
        } else {
            $constraintComparable.Length
        }
        if ($sectionEnd -gt $sectionStart) {
            $sectionText = $constraintComparable.Substring($sectionStart, $sectionEnd - $sectionStart)
            if ($labelMatch.Groups["observed"].Success) {
                [void]$observedConstraintBuilder.Append($sectionText)
            } else {
                [void]$inferenceConstraintBuilder.Append($sectionText)
            }
        }
        if ($labelMatch.Groups["observed"].Success -and
            $labelIndex + 1 -lt $constraintLabelMatches.Count -and
            $constraintLabelMatches[$labelIndex + 1].Groups["inference"].Success) {
            $orderedSectionPairCount += 1
        }
    }
    $observedConstraintComparable = $observedConstraintBuilder.ToString()
    $inferenceConstraintComparable = $inferenceConstraintBuilder.ToString()
    $requiredConstraintGroups = @(
        @{ id = "debt_present"; patterns = @("debt=present", "\uBD80\uCC44(?:\uAC00)?(?:\uC788|\uC874\uC7AC)") },
        @{ id = "cashflow_tight"; patterns = @("cashflow=tight", "\uD604\uAE08\uD750\uB984(?:\uC774)?(?:\uBE60\uB4EF|\uC81C\uC57D|\uC81C\uD55C|\uBD80\uC871)") },
        @{ id = "spending_limit_restricted"; patterns = @("spendinglimit=restricted", "\uC9C0\uCD9C\uD55C\uB3C4(?:\uAC00)?(?:\uC81C\uD55C|\uC5C4\uACA9|\uC788|\uB0AE)") },
        @{ id = "risk_tolerance_low"; patterns = @("risktolerance=low", "(?:\uC704\uD5D8\uD5C8\uC6A9(?:\uB3C4)?|\uC704\uD5D8\uAC10\uC218\uB3C4)(?:\uAC00)?(?:\uB0AE|\uC801)") },
        @{ id = "purchase_cost_high"; patterns = @("purchasecost=high", "\uACE0(?:\uAC00|\uBE44\uC6A9)(?:\uC758)?\uAD6C\uB9E4") }
    )
    $preservedConstraintCount = 0
    $constraintResultCodes = New-Object System.Collections.Generic.List[string]
    if ($requiresConstraintIntegrity) {
        foreach ($requiredConstraintGroup in $requiredConstraintGroups) {
            $groupPreserved = $false
            $groupNegated = $false
            foreach ($constraintPattern in @($requiredConstraintGroup.patterns)) {
                foreach ($constraintMatch in [regex]::Matches($observedConstraintComparable, $constraintPattern)) {
                    $prefixStart = [Math]::Max(0, $constraintMatch.Index - 8)
                    $constraintPrefix = $observedConstraintComparable.Substring(
                        $prefixStart,
                        $constraintMatch.Index - $prefixStart)
                    $suffixStart = $constraintMatch.Index + $constraintMatch.Length
                    $constraintSuffix = $observedConstraintComparable.Substring(
                        $suffixStart,
                        [Math]::Min(12, $observedConstraintComparable.Length - $suffixStart))
                    $prefixNegated = [regex]::IsMatch($constraintPrefix, "(?:not|no)$")
                    $suffixNegated = [regex]::IsMatch(
                        $constraintSuffix,
                        "^(?:\uAC00|\uC774|\uC740|\uB294|\uC744|\uB97C)?(?:\uD558\uC9C0\uC54A|\uB418\uC9C0\uC54A|\uC9C0\uC54A|\uC544\uB2D8|\uC5C6|\uBD80\uC7AC|=?false|not)")
                    if (-not $prefixNegated -and -not $suffixNegated) {
                        $groupPreserved = $true
                        break
                    }
                    $groupNegated = $true
                }
                if ($groupPreserved) { break }
            }
            $constraintReasonCode = if ($groupPreserved) {
                $preservedConstraintCount += 1
                "preserved"
            } elseif ($groupNegated) {
                "negated"
            } else {
                "missing"
            }
            $constraintResultCodes.Add("$($requiredConstraintGroup.id):$constraintReasonCode") | Out-Null
        }
    }
    $requiredConstraintCount = if ($requiresConstraintIntegrity) { $requiredConstraintGroups.Count } else { 0 }
    $droppedConstraintCount = [Math]::Max(0, $requiredConstraintCount - $preservedConstraintCount)
    $contextCompleteness = if ($requiredConstraintCount -gt 0) {
        [Math]::Round(([double]$preservedConstraintCount / [double]$requiredConstraintCount), 3)
    } else { 1.0 }
    $observationInferenceSeparated = -not $requiresConstraintIntegrity -or $orderedSectionPairCount -gt 0
    $unsupportedInferenceCount = if ($requiresConstraintIntegrity) {
        ([regex]::Matches(
            $inferenceConstraintComparable,
            "discretionarybudget=sufficient|\uAC00\uCC98\uBD84\uC608\uC0B0=\uCDA9\uBD84|\uC608\uC0B0\uC774\uCDA9\uBD84|(?:\uAD6C\uB9E4|\uC9C0\uCD9C|\uC7AC\uC815)\uC5EC\uB825(?:\uC774)?\uCDA9\uBD84")).Count
    } else { 0 }
    $constraintIntegrityStatus = if (-not $requiresConstraintIntegrity) {
        "not-required"
    } elseif ($answerContractHold) {
        "hold"
    } elseif ($droppedConstraintCount -eq 0 -and $observationInferenceSeparated -and $unsupportedInferenceCount -eq 0) {
        "pass"
    } else {
        "constraint-integrity-missing"
    }
    $constraintFailureReasonCodes = New-Object System.Collections.Generic.List[string]
    if ($requiresConstraintIntegrity) {
        if ($answerContractHold) {
            $constraintFailureReasonCodes.Add("answer_contract_explicit_hold") | Out-Null
        }
        if ($droppedConstraintCount -gt 0) {
            $constraintFailureReasonCodes.Add("required_constraints_missing") | Out-Null
        }
        if (-not $observationInferenceSeparated) {
            $constraintFailureReasonCodes.Add("observation_inference_sections_missing") | Out-Null
        }
        if ($unsupportedInferenceCount -gt 0) {
            $constraintFailureReasonCodes.Add("unsupported_inference_present") | Out-Null
        }
    }
    if ($requiresConstraintIntegrity -and $constraintIntegrityStatus -eq "hold") {
        $classification = "constraint_integrity_hold"
    } elseif ($requiresConstraintIntegrity -and $constraintIntegrityStatus -ne "pass") {
        $classification = "constraint_integrity_missing"
    }
    $fallbackDetected = [regex]::IsMatch(
        "$observedModelUsed $observedAnswerMode $observedRoute",
        "fallback|local_safe|evidence_only",
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $fallbackApplicability = if (-not $fallbackDetected) {
        "not-fallback"
    } elseif ($requiresConstraintIntegrity) {
        "constraint-contract-applies"
    } else {
        "constraint-contract-not-required"
    }
    $constraintContractScope = if ($requiresConstraintIntegrity) {
        "visible-terminal-answer"
    } else {
        "not-required"
    }
    $providerAttributionSource = if ($providerAttemptObserved) {
        "stream_payload"
    } elseif (-not [string]::IsNullOrWhiteSpace($observedPipelineFailureClass) -or
        -not [string]::IsNullOrWhiteSpace($observedPipelineDisabledReason)) {
        "pipeline_snapshot"
    } elseif (-not [string]::IsNullOrWhiteSpace($observedDebugFxCode) -or
        -not [string]::IsNullOrWhiteSpace($observedDebugFxFailureClass) -or
        -not [string]::IsNullOrWhiteSpace($observedDebugFxTriggerReason)) {
        "debug_fx"
    } else {
        "not_observed"
    }
    $modelFinalObserved = $terminalSeen -and -not [string]::IsNullOrWhiteSpace($observedModelUsed)
    $modelProofStatus = if ($transportCode -ne "success" -or $statusCode -ge 400) {
        "transport-failed"
    } elseif (-not $terminalSeen) {
        "terminal-missing"
    } elseif ($fallbackDetected) {
        "fallback-detected"
    } elseif (-not $modelFinalObserved) {
        "model-metadata-missing"
    } elseif ($providerAttemptObserved) {
        "model-and-provider-observed"
    } else {
        "model-observed-provider-not-observed"
    }
    $officialEvidenceNeeded = $requiresOfficialEvidence -and $officialDomainMentionCount -le 0 -and $containsEvidenceNeeded
    $officialEvidenceMissing = $requiresOfficialEvidence -and $officialDomainMentionCount -le 0 -and -not $containsEvidenceNeeded
    $ragSignalStatus = if ($fakeCommandMentionCount -gt 0) {
        "fake-command-risk"
    } elseif ($offDomainCommunityMentionCount -gt 0) {
        "off-domain-community-risk"
    } elseif ($officialEvidenceMissing) {
        "official-evidence-missing"
    } elseif ($officialEvidenceNeeded) {
        "official-evidence-needed"
    } elseif ($containsEvidenceNeeded -or $externalLaneSplitSeen -or $officialDomainMentionCount -gt 0) {
        "rag-signal-observed"
    } else {
        "no-rag-signal"
    }
    return @{
        type = "stream_probe"
        attempt = $Attempt
        probeId = [string]$Probe.id
        promptHash = Get-Hash12 ([string]$Probe.message)
        promptLength = ([string]$Probe.message).Length
        rawPromptStored = $false
        rawStreamStored = $false
        rawRequestIdStored = [bool]$requestProof.rawRequestIdStored
        rawProofLinesStored = [bool]$requestProof.rawProofLinesStored
        requestHash = [string]$requestProof.requestHash
        proofRowCount = [int]$requestProof.proofRowCount
        attemptTotal = [int]$requestProof.attemptTotal
        attemptDropped = [int]$requestProof.attemptDropped
        distinctPromptHashCount = [int]$requestProof.distinctPromptHashCount
        distinctOptionsHashCount = [int]$requestProof.distinctOptionsHashCount
        responseHashObservedCount = [int]$requestProof.responseHashObservedCount
        modelAdapterAttemptObservedCount = [int]$requestProof.modelAdapterAttemptObservedCount
        clientHttpExchangeObservedCount = [int]$requestProof.clientHttpExchangeObservedCount
        clientHttpResponseObservedCount = [int]$requestProof.clientHttpResponseObservedCount
        providerAttemptObservedCount = [int]$requestProof.providerAttemptObservedCount
        wireAttemptObservedCount = [int]$requestProof.wireAttemptObservedCount
        responseObservedCount = [int]$requestProof.responseObservedCount
        correlationStatus = [string]$requestProof.correlationStatus
        sessionScope = if ($isolatedSession) { "isolated-probe" } else { "run-shared" }
        useRag = [bool]$Probe.useRag
        useWebSearch = [bool]$Probe.useWebSearch
        searchMode = [string]$Probe.searchMode
        requestBudgetMs = $RequestBudgetMs
        curlWindowSeconds = $CurlWindowSeconds
        curlExit = $curlExit
        transport = "httpclient"
        transportCode = $transportCode
        transportErrorType = $transportErrorType
        tempResidueDelta = $newTempResidues.Count
        tempResidueCount = $tempResidueAfter.Count
        statusCode = $statusCode
        elapsedMs = [int]$sw.ElapsedMilliseconds
        terminalSeen = $terminalSeen
        eventCount = $eventRows.Count
        eventTypes = @($eventRows | ForEach-Object { $_.event } | Where-Object { $_ } | Select-Object -Unique)
        payloadTypes = @($eventRows | ForEach-Object { $_.payloadType } | Where-Object { $_ } | Select-Object -Unique)
        classification = $classification
        ragSignalStatus = $ragSignalStatus
        containsEvidenceNeeded = $containsEvidenceNeeded
        officialDomainMentionCount = $officialDomainMentionCount
        offDomainCommunityMentionCount = $offDomainCommunityMentionCount
        fakeCommandMentionCount = $fakeCommandMentionCount
        officialEvidenceNeeded = $officialEvidenceNeeded
        officialEvidenceMissing = $officialEvidenceMissing
        externalLaneSplitSeen = $externalLaneSplitSeen
        constraintIntegrityRequired = $requiresConstraintIntegrity
        requiredConstraintCount = $requiredConstraintCount
        preservedConstraintCount = $preservedConstraintCount
        contextCompleteness = $contextCompleteness
        droppedConstraintCount = $droppedConstraintCount
        unsupportedInferenceCount = $unsupportedInferenceCount
        observationInferenceSeparated = $observationInferenceSeparated
        constraintIntegrityStatus = $constraintIntegrityStatus
        constraintResultCodes = @($constraintResultCodes)
        constraintFailureReasonCodes = @($constraintFailureReasonCodes)
        constraintContractScope = $constraintContractScope
        answerContractHold = $answerContractHold
        answerContractReason = $answerContractReason
        modelUsed = $observedModelUsed
        answerMode = $observedAnswerMode
        route = $observedRoute
        pipelineFailureClass = $observedPipelineFailureClass
        pipelineDisabledReason = $observedPipelineDisabledReason
        debugFxCode = $observedDebugFxCode
        debugFxFailureClass = $observedDebugFxFailureClass
        debugFxTriggerReason = $observedDebugFxTriggerReason
        providerAttributionSource = $providerAttributionSource
        modelFinalObserved = $modelFinalObserved
        modelAttemptObserved = ($modelAttemptDelta -gt 0)
        modelAttemptDelta = $modelAttemptDelta
        modelResponseReceivedDelta = $modelResponseReceivedDelta
        modelFailedBeforeResponseDelta = $modelFailedBeforeResponseDelta
        modelTimedOutDelta = $modelTimedOutDelta
        modelCancelledDelta = $modelCancelledDelta
        modelAttemptCorrelationScope = if ($usageWindowObserved) { "process-window" } else { "unavailable" }
        providerAttemptObserved = $providerAttemptObserved
        providerUsageObserved = ($providerUsageObservedDelta -gt 0)
        providerUsageObservedDelta = $providerUsageObservedDelta
        providerUsageMissingDelta = $providerUsageMissingDelta
        wireAttemptCoverage = if ($usageWindowObserved) { [string]$usageAfter.wireAttemptCoverage } else { "unavailable" }
        fallbackDetected = $fallbackDetected
        fallbackApplicability = $fallbackApplicability
        modelProofStatus = $modelProofStatus
    }
}

$runId = (Get-Date).ToUniversalTime().ToString("yyyyMMdd-HHmmss")
$rootOut = Resolve-OutputDir -Path $OutDir
$runDir = if ($DryRun) {
    Join-Path $rootOut "dry-run-$runId"
} else {
    Join-Path $rootOut "run-$runId"
}
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$eventsPath = Join-Path $runDir "chat-ui-vibe-soak.events.ndjson"
$summaryPath = Join-Path $runDir "chat-ui-vibe-soak.summary.json"
$latestSummaryPath = Join-Path $rootOut "chat-ui-vibe-soak.latest.json"
$agentNextPath = Join-Path $runDir "chat-ui-vibe-soak.agent-next.json"
$latestAgentNextPath = Join-Path $rootOut "chat-ui-vibe-soak.latest-agent-next.json"
$progressPath = Join-Path $runDir "chat-ui-vibe-soak.progress.json"
$latestProgressPath = Join-Path $rootOut "chat-ui-vibe-soak.latest-progress.json"
$handoffPath = Join-Path $runDir "chat-ui-vibe-soak.handoff.md"

if ($Seed -eq 0) {
    $Seed = [Math]::Abs([int]((Get-Date).Ticks % [int]::MaxValue))
}
$random = [System.Random]::new($Seed)
$deadline = if ($DurationMinutes -gt 0) { (Get-Date).AddMinutes($DurationMinutes) } else { [DateTime]::MaxValue }
$probeDeck = @(
    @{ id = "rag_web_budget"; message = "Find current evidence for a RAG web-search answer, cite constraints, and keep the answer concise."; useRag = $true; useWebSearch = $true; searchMode = "FORCE_DEEP" },
    @{ id = "rag_only_memory"; message = "Use retrieved local context only and explain which evidence was missing if the answer is incomplete."; useRag = $true; useWebSearch = $false; searchMode = "OFF" },
    @{ id = "web_light_status"; message = "Search lightly for recent platform signals and summarize the reliability tradeoffs."; useRag = $false; useWebSearch = $true; searchMode = "FORCE_LIGHT" },
    @{ id = "direct_off"; message = "Answer directly without retrieval, then state whether retrieval would be needed for confidence."; useRag = $false; useWebSearch = $false; searchMode = "OFF" },
    @{ id = "failure_learning"; message = "Diagnose why a stream can end without a final event and list the safest next probe."; useRag = $true; useWebSearch = $true; searchMode = "AUTO" },
    @{ id = "official_changelog_evidence"; message = "RAG web-search verification: answer only from official OpenAI and Supabase docs/changelog evidence; if official evidence is missing say evidence_needed."; useRag = $true; useWebSearch = $true; searchMode = "FORCE_DEEP"; isolatedSession = $true; requiresOfficialEvidence = $true },
    @{ id = "skill_lane_external_split"; message = "Using demo1-demand-driven-external-proof, demo1-superpowers-repo-evidence-guard, and the Supabase skill, split Browser/Computer/Supabase local proof from external evidence; name only repo commands that exist, otherwise say evidence_needed."; useRag = $true; useWebSearch = $false; searchMode = "OFF" },
    @{ id = "decision_context_integrity"; message = "Fictional budgeting scenario. Return exactly two labeled lines. OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;riskTolerance=low;purchaseCost=high. INFERENCE: discretionaryBudget=unknown. Do not invent or repeat any exact financial amount."; useRag = $false; useWebSearch = $false; searchMode = "OFF"; isolatedSession = $true; requiresConstraintIntegrity = $true }
)
$selectedProbeId = ""
$replaySourcePath = ""
$replaySourceProbeId = ""
if ([string]::IsNullOrWhiteSpace($ProbeId) -and -not [string]::IsNullOrWhiteSpace($ReplayFromSummaryPath)) {
    $ProbeId = Resolve-ReplayProbeIdFromSummary -SummaryPath $ReplayFromSummaryPath
    $replaySourceProbeId = $ProbeId
    $replaySourcePath = if ([System.IO.Path]::IsPathRooted($ReplayFromSummaryPath)) {
        $ReplayFromSummaryPath
    } else {
        Join-Path (Get-Location).Path $ReplayFromSummaryPath
    }
}
if (-not [string]::IsNullOrWhiteSpace($ProbeId)) {
    $matchingProbes = @($probeDeck | Where-Object { [string]$_.id -eq $ProbeId })
    if ($matchingProbes.Count -ne 1) {
        $validProbeIds = @($probeDeck | ForEach-Object { [string]$_.id }) -join ","
        throw "Unknown ProbeId '$ProbeId'. Valid probes: $validProbeIds"
    }
    $probeDeck = @($matchingProbes)
    $selectedProbeId = $ProbeId
}

if ($Iterations -lt 0) { $Iterations = 0 }
$plannedCount = if ($Iterations -gt 0) { $Iterations } else { $probeDeck.Count }
$longRunCommand = New-LongRunCommand -Port $Port -OutDir $OutDir -RecentWindowSize $RecentWindowSize

if ($DryRun) {
    $coverageBag = @()
    $dryRunProbeCoverage = @{}
    $dryRunDirectDebugShortCircuitRiskCount = 0
    for ($i = 1; $i -le $plannedCount; $i++) {
        $probe = Get-NextProbeFromCoverageBag -ProbeDeck $probeDeck -Random $random -CoverageBag ([ref]$coverageBag)
        Add-ProbeCoverage -Coverage $dryRunProbeCoverage -ProbeId ([string]$probe.id)
        $directDebugShortCircuitRisk = Test-ProbeDirectDebugShortCircuitRisk -Message ([string]$probe.message)
        if ($directDebugShortCircuitRisk) { $dryRunDirectDebugShortCircuitRiskCount += 1 }
        Write-NdjsonEvent -Path $eventsPath -Data @{
            type = "planned_probe"
            attempt = $i
            probeId = [string]$probe.id
            promptHash = Get-Hash12 ([string]$probe.message)
            promptLength = ([string]$probe.message).Length
            rawPromptStored = $false
            rawStreamStored = $false
            sessionScope = if ($probe.ContainsKey("isolatedSession") -and [bool]$probe.isolatedSession) { "isolated-probe" } else { "run-shared" }
            constraintIntegrityRequired = $probe.ContainsKey("requiresConstraintIntegrity") -and [bool]$probe.requiresConstraintIntegrity
            directDebugShortCircuitRisk = $directDebugShortCircuitRisk
            useRag = [bool]$probe.useRag
            useWebSearch = [bool]$probe.useWebSearch
            searchMode = [string]$probe.searchMode
        }
    }
    $dryRunPerProbeReliability = New-PerProbeReliabilitySummary -ProbeEvents @() -ProbeDeck $probeDeck
    $dryRunFailureFocus = New-FailureFocusSummary -PerProbeReliability $dryRunPerProbeReliability
    $dryRunRecentWindowReliability = New-RecentWindowReliabilitySummary -ProbeEvents @() -WindowSize $RecentWindowSize
    $dryRunRagSignalSummary = New-RagSignalAggregateSummary -ProbeEvents @()
    $dryRunModelProof = New-ModelProofSummary -ProbeEvents @()
    $dryRunReplayProbeId = if (-not [string]::IsNullOrWhiteSpace($selectedProbeId)) { $selectedProbeId } else { [string]$dryRunFailureFocus.worstProbeId }
    $dryRunReplayFailureCommand = New-ReplayFailureCommand -Port $Port -OutDir $OutDir -ProbeId $dryRunReplayProbeId
    $summary = @{
        schemaVersion = "awx.chat_ui_vibe_soak.v1"
        ok = $true
        status = "dry-run"
        mutationAllowed = $false
        port = $Port
        seed = $Seed
        selectedProbeId = $selectedProbeId
        targetedProbeMode = (-not [string]::IsNullOrWhiteSpace($selectedProbeId))
        replaySourcePath = $replaySourcePath
        replaySourceProbeId = $replaySourceProbeId
        plannedCount = $plannedCount
        stopReason = ""
        rawPromptStored = $false
        rawStreamStored = $false
        directDebugShortCircuitRiskCount = $dryRunDirectDebugShortCircuitRiskCount
        eventsPath = $eventsPath
        summaryPath = $summaryPath
        agentNextPath = $agentNextPath
        latestAgentNextPath = $latestAgentNextPath
        progressPath = $progressPath
        latestProgressPath = $latestProgressPath
        probeCoverage = New-ProbeCoverageSummary -Coverage $dryRunProbeCoverage -DeckSize $probeDeck.Count
        reliability = New-ReliabilitySummary -IterationsCompleted 0 -TerminalSeenCount 0
        recentWindowReliability = $dryRunRecentWindowReliability
        perProbeReliability = $dryRunPerProbeReliability
        failureFocus = $dryRunFailureFocus
        ragSignalSummary = $dryRunRagSignalSummary
        modelProof = $dryRunModelProof
        replayFailureCommand = $dryRunReplayFailureCommand
        nextLongRunCommand = $longRunCommand
        nextHandoffAction = "run_live_soak"
        nextAction = "rerun_without_DryRun"
    }
    Write-ProgressCheckpoint -Progress @{
        schemaVersion = "awx.chat_ui_vibe_soak.progress.v1"
        ok = $true
        status = "dry-run-planned"
        mutationAllowed = $false
        port = $Port
        seed = $Seed
        selectedProbeId = $selectedProbeId
        targetedProbeMode = (-not [string]::IsNullOrWhiteSpace($selectedProbeId))
        replaySourcePath = $replaySourcePath
        replaySourceProbeId = $replaySourceProbeId
        plannedCount = $plannedCount
        iterationsCompleted = $plannedCount
        stopReason = ""
        rawPromptStored = $false
        rawStreamStored = $false
        probeCoverage = New-ProbeCoverageSummary -Coverage $dryRunProbeCoverage -DeckSize $probeDeck.Count
        reliability = New-ReliabilitySummary -IterationsCompleted 0 -TerminalSeenCount 0
        recentWindowReliability = $dryRunRecentWindowReliability
        perProbeReliability = $dryRunPerProbeReliability
        failureFocus = $dryRunFailureFocus
        ragSignalSummary = $dryRunRagSignalSummary
        modelProof = $dryRunModelProof
        replayFailureCommand = $dryRunReplayFailureCommand
        nextLongRunCommand = $longRunCommand
        eventsPath = $eventsPath
        nextHandoffAction = "run_live_soak"
    } -ProgressPath $progressPath -LatestProgressPath $latestProgressPath
    Write-HandoffArtifacts -Summary $summary -LatestSummaryPath $latestSummaryPath -HandoffPath $handoffPath
    Write-AgentNextArtifact -Summary $summary -AgentNextPath $agentNextPath -LatestAgentNextPath $latestAgentNextPath
    Write-JsonFile -Path $summaryPath -Data $summary
    [Console]::Out.WriteLine(($summary | ConvertTo-Json -Depth 8 -Compress))
    exit 0
}

$listener = Invoke-ListenerReady -RunDir $runDir
$selectedServerPort = $Port
if (-not $listener.skipped) {
    $selectedServerPort = 0
    try {
        $selectedServerPort = [int]$listener.parsed.selectedPorts.server
    } catch {
        $selectedServerPort = 0
    }
    if ($selectedServerPort -le 0) {
        throw "listener-selected-port-missing"
    }
    $Port = $selectedServerPort
    $longRunCommand = New-LongRunCommand -Port $Port -OutDir $OutDir -RecentWindowSize $RecentWindowSize
}
$browserTargetUrl = ""
try {
    $browserTargetUrl = [string]$listener.parsed.browserTargetUrl
} catch {
    $browserTargetUrl = ""
}
$browserTargetUrlHash = if ([string]::IsNullOrWhiteSpace($browserTargetUrl)) {
    ""
} else {
    Get-Hash12 $browserTargetUrl
}
$listenerRunIdHash = ""
try { $listenerRunIdHash = [string]$listener.parsed.runIdHash } catch { $listenerRunIdHash = "" }
$listenerEvidence = @{
    skipped = [bool]$listener.skipped
    ok = if ($listener.ContainsKey("ok")) { [bool]$listener.ok } else { $null }
    status = [string]$listener.status
    exitCode = if ($listener.ContainsKey("exitCode")) { $listener.exitCode } else { $null }
    selectedServerPort = [int]$selectedServerPort
    browserTargetUrlHash = $browserTargetUrlHash
    runIdHash = $listenerRunIdHash
}
$ownedRuntimeLogPath = Resolve-RuntimeProofLogPath -ExplicitPath $RuntimeLogPath -ListenerResult $listener
$uiContract = Test-ChatUiContract
$listenerHasOk = $listener.ContainsKey("ok")
$listenerOk = if ($listenerHasOk) { [bool]$listener.ok } else { $null }
Write-NdjsonEvent -Path $eventsPath -Data @{
    type = "listener"
    listenerStatus = [string]$listener.status
    listenerSkipped = [bool]$listener.skipped
    listenerOk = $listenerOk
    uiOk = [bool]$uiContract.ok
    authModeDisabled = [bool]$uiContract.authModeDisabled
    featurePoolOpen = [bool]$uiContract.featurePoolOpen
    selectedServerPort = [int]$selectedServerPort
    browserTargetUrlHash = $browserTargetUrlHash
    runIdHash = $listenerRunIdHash
}

$completed = 0
$runningTerminalCount = 0
$runningReliableCount = 0
$runningTimeoutCount = 0
$runningClassificationCounts = @{}
$runningProbeCoverage = @{}
$runningProbeEvents = New-Object System.Collections.Generic.List[object]
$coverageBag = @()
$stopReason = ""
while (($Iterations -le 0 -or $completed -lt $Iterations) -and (Get-Date) -lt $deadline) {
    $completed += 1
    $probe = Get-NextProbeFromCoverageBag -ProbeDeck $probeDeck -Random $random -CoverageBag ([ref]$coverageBag)
    Add-ProbeCoverage -Coverage $runningProbeCoverage -ProbeId ([string]$probe.id)
    $event = Invoke-StreamProbe -Probe $probe -Attempt $completed -RunDir $runDir
    $runningProbeEvents.Add($event) | Out-Null
    Write-NdjsonEvent -Path $eventsPath -Data $event
    $classification = [string]$event.classification
    if (-not $runningClassificationCounts.ContainsKey($classification)) { $runningClassificationCounts[$classification] = 0 }
    $runningClassificationCounts[$classification] = [int]$runningClassificationCounts[$classification] + 1
    if ($event.terminalSeen -eq $true) { $runningTerminalCount += 1 }
    if (Test-ProbeEventReliable -Event $event) { $runningReliableCount += 1 }
    if ($classification -eq "curl_timeout_without_terminal_event") { $runningTimeoutCount += 1 }
    $runningPerProbeReliability = New-PerProbeReliabilitySummary -ProbeEvents $runningProbeEvents.ToArray() -ProbeDeck $probeDeck
    $runningFailureFocus = New-FailureFocusSummary -PerProbeReliability $runningPerProbeReliability
    $runningRecentWindowReliability = New-RecentWindowReliabilitySummary -ProbeEvents $runningProbeEvents.ToArray() -WindowSize $RecentWindowSize
    $runningRagSignalSummary = New-RagSignalAggregateSummary -ProbeEvents $runningProbeEvents.ToArray()
    $runningModelProof = New-ModelProofSummary -ProbeEvents $runningProbeEvents.ToArray()
    $runningReliability = New-ReliabilitySummary -IterationsCompleted $completed -TerminalSeenCount $runningReliableCount
    $runningTransportSafetyOk = [bool]$runningReliability.meetsNineXTarget
    $runningModelSuccessOk = $runningTransportSafetyOk -and [string]$runningModelProof.status -eq "verified"
    $recentWindowReadyToStop = [int]$runningRecentWindowReliability["observedCount"] -ge [Math]::Max(1, [int]$runningRecentWindowReliability["windowSize"])
    $shouldStopForRecentWindow = $StopOnRecentBelowTarget -and
        $recentWindowReadyToStop -and
        [string]$runningRecentWindowReliability["status"] -eq "below-target"
    $runningStopReason = if ($shouldStopForRecentWindow) { "recent-window-below-target" } else { "" }
    $runningReplayProbeId = if (-not [string]::IsNullOrWhiteSpace($selectedProbeId)) {
        $selectedProbeId
    } elseif (-not [string]::IsNullOrWhiteSpace([string]$runningRecentWindowReliability.worstRecentProbeId)) {
        [string]$runningRecentWindowReliability.worstRecentProbeId
    } else {
        [string]$runningFailureFocus.worstProbeId
    }
    $runningReplayFailureCommand = New-ReplayFailureCommand -Port $Port -OutDir $OutDir -ProbeId $runningReplayProbeId
    Write-ProgressCheckpoint -Progress @{
        schemaVersion = "awx.chat_ui_vibe_soak.progress.v1"
        ok = $runningTransportSafetyOk
        okScope = "transport-safety-only"
        transportSafetyOk = $runningTransportSafetyOk
        modelSuccessOk = $runningModelSuccessOk
        status = if ($runningReliableCount -eq $completed) { "running-all-terminal" } else { "running-partial" }
        mutationAllowed = [bool]$CloseConflictingListener
        port = $Port
        browserTargetUrlHash = $browserTargetUrlHash
        seed = $Seed
        selectedProbeId = $selectedProbeId
        targetedProbeMode = (-not [string]::IsNullOrWhiteSpace($selectedProbeId))
        replaySourcePath = $replaySourcePath
        replaySourceProbeId = $replaySourceProbeId
        iterationsRequested = $Iterations
        iterationsCompleted = $completed
        stopReason = $runningStopReason
        durationMinutes = $DurationMinutes
        requestBudgetMs = $RequestBudgetMs
        curlWindowSeconds = $CurlWindowSeconds
        rawPromptStored = $false
        rawStreamStored = $false
        lastProbeId = [string]$probe.id
        lastClassification = $classification
        lastTerminalSeen = [bool]$event.terminalSeen
        terminalSeenCount = $runningTerminalCount
        reliableSuccessCount = $runningReliableCount
        timeoutCount = $runningTimeoutCount
        classificationCounts = $runningClassificationCounts
        probeCoverage = New-ProbeCoverageSummary -Coverage $runningProbeCoverage -DeckSize $probeDeck.Count
        reliability = $runningReliability
        recentWindowReliability = $runningRecentWindowReliability
        perProbeReliability = $runningPerProbeReliability
        failureFocus = $runningFailureFocus
        ragSignalSummary = $runningRagSignalSummary
        modelProof = $runningModelProof
        replayFailureCommand = $runningReplayFailureCommand
        eventsPath = $eventsPath
        nextHandoffAction = if ($runningReliableCount -eq $completed) { "continue_long_soak" } else { "inspect_events_then_patch_smallest_failure" }
    } -ProgressPath $progressPath -LatestProgressPath $latestProgressPath
    if ($shouldStopForRecentWindow) {
        Write-NdjsonEvent -Path $eventsPath -Data @{
            type = "stop"
            stopReason = $runningStopReason
            attempt = $completed
            recentWindowStatus = [string]$runningRecentWindowReliability["status"]
            recentWindowObservedCount = [int]$runningRecentWindowReliability["observedCount"]
            recentWindowWorstProbeId = [string]$runningRecentWindowReliability["worstRecentProbeId"]
            ragSignalStatus = [string]$runningRagSignalSummary["status"]
            nextRagSignalFocus = [string]$runningRagSignalSummary["nextRagSignalFocus"]
            nextHandoffAction = "inspect_events_then_patch_smallest_failure"
            rawPromptStored = $false
            rawStreamStored = $false
        }
        $stopReason = $runningStopReason
        break
    }
}

$eventLines = @(Get-Content -LiteralPath $eventsPath -ErrorAction SilentlyContinue)
$probeEvents = @($eventLines | ForEach-Object {
    try { $_ | ConvertFrom-Json -ErrorAction Stop } catch { $null }
} | Where-Object { $_ -and $_.type -eq "stream_probe" })
$terminalCount = @($probeEvents | Where-Object { $_.terminalSeen -eq $true }).Count
$reliableCount = @($probeEvents | Where-Object { Test-ProbeEventReliable -Event $_ }).Count
$timeoutCount = @($probeEvents | Where-Object { $_.classification -eq "curl_timeout_without_terminal_event" }).Count
$classificationCounts = @{}
foreach ($event in $probeEvents) {
    $key = [string]$event.classification
    if (-not $classificationCounts.ContainsKey($key)) { $classificationCounts[$key] = 0 }
    $classificationCounts[$key] = [int]$classificationCounts[$key] + 1
}
$probeCoverage = @{}
foreach ($event in $probeEvents) {
    Add-ProbeCoverage -Coverage $probeCoverage -ProbeId ([string]$event.probeId)
}
$finalPerProbeReliability = New-PerProbeReliabilitySummary -ProbeEvents $probeEvents -ProbeDeck $probeDeck
$finalFailureFocus = New-FailureFocusSummary -PerProbeReliability $finalPerProbeReliability
$finalRecentWindowReliability = New-RecentWindowReliabilitySummary -ProbeEvents $probeEvents -WindowSize $RecentWindowSize
$finalRagSignalSummary = New-RagSignalAggregateSummary -ProbeEvents $probeEvents
$finalModelProof = New-ModelProofSummary -ProbeEvents $probeEvents
$finalReliability = New-ReliabilitySummary -IterationsCompleted $completed -TerminalSeenCount $reliableCount
$finalTransportSafetyOk = [bool]$finalReliability.meetsNineXTarget
$finalModelSuccessOk = $finalTransportSafetyOk -and [string]$finalModelProof.status -eq "verified"
$finalReplayProbeId = if (-not [string]::IsNullOrWhiteSpace($selectedProbeId)) {
    $selectedProbeId
} elseif (-not [string]::IsNullOrWhiteSpace([string]$finalRecentWindowReliability.worstRecentProbeId)) {
    [string]$finalRecentWindowReliability.worstRecentProbeId
} else {
    [string]$finalFailureFocus.worstProbeId
}
$finalReplayFailureCommand = New-ReplayFailureCommand -Port $Port -OutDir $OutDir -ProbeId $finalReplayProbeId

$summary = @{
    schemaVersion = "awx.chat_ui_vibe_soak.v1"
    ok = $finalTransportSafetyOk
    okScope = "transport-safety-only"
    transportSafetyOk = $finalTransportSafetyOk
    modelSuccessOk = $finalModelSuccessOk
    status = if ($completed -gt 0 -and $reliableCount -eq $completed) { "all-terminal" } elseif ($completed -gt 0) { "partial" } else { "no-probes" }
    mutationAllowed = [bool]$CloseConflictingListener
    port = $Port
    seed = $Seed
    selectedProbeId = $selectedProbeId
    targetedProbeMode = (-not [string]::IsNullOrWhiteSpace($selectedProbeId))
    replaySourcePath = $replaySourcePath
    replaySourceProbeId = $replaySourceProbeId
    iterationsRequested = $Iterations
    iterationsCompleted = $completed
    stopReason = $stopReason
    durationMinutes = $DurationMinutes
    requestBudgetMs = $RequestBudgetMs
    curlWindowSeconds = $CurlWindowSeconds
    rawPromptStored = $false
    rawStreamStored = $false
    listener = $listenerEvidence
    browserTargetUrlHash = $browserTargetUrlHash
    uiContract = $uiContract
    terminalSeenCount = $terminalCount
    reliableSuccessCount = $reliableCount
    timeoutCount = $timeoutCount
    classificationCounts = $classificationCounts
    probeCoverage = New-ProbeCoverageSummary -Coverage $probeCoverage -DeckSize $probeDeck.Count
    reliability = $finalReliability
    recentWindowReliability = $finalRecentWindowReliability
    perProbeReliability = $finalPerProbeReliability
    failureFocus = $finalFailureFocus
    ragSignalSummary = $finalRagSignalSummary
    modelProof = $finalModelProof
    replayFailureCommand = $finalReplayFailureCommand
    eventsPath = $eventsPath
    summaryPath = $summaryPath
    agentNextPath = $agentNextPath
    latestAgentNextPath = $latestAgentNextPath
    progressPath = $progressPath
    latestProgressPath = $latestProgressPath
    nextHandoffAction = if (-not [string]::IsNullOrWhiteSpace($stopReason)) { "inspect_events_then_patch_smallest_failure" } elseif ($completed -gt 0 -and $reliableCount -eq $completed) { "continue_long_soak" } elseif ($completed -gt 0) { "inspect_events_then_patch_smallest_failure" } else { "restart_listener_and_probe" }
    nextLongRunCommand = $longRunCommand
}
Write-HandoffArtifacts -Summary $summary -LatestSummaryPath $latestSummaryPath -HandoffPath $handoffPath
Write-AgentNextArtifact -Summary $summary -AgentNextPath $agentNextPath -LatestAgentNextPath $latestAgentNextPath
Write-JsonFile -Path $summaryPath -Data $summary
[Console]::Out.WriteLine(($summary | ConvertTo-Json -Depth 10 -Compress))

