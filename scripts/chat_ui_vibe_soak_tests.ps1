$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Needle)
    if (-not $Text.Contains($Needle)) {
        throw "[FAIL] $Name missing: $Needle"
    }
}

function Assert-NotContains {
    param([string]$Name, [string]$Text, [string]$Needle)
    if ($Text.Contains($Needle)) {
        throw "[FAIL] $Name unexpected: $Needle"
    }
}

function Get-UnusedLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try {
        return [int]$listener.LocalEndpoint.Port
    } finally {
        $listener.Stop()
    }
}

function Start-SyntheticSoakServer {
    param(
        [int]$Port,
        [ValidateSet("success", "model-override", "fallback-success", "terminal-error", "constraint-success", "constraint-success-ko", "constraint-natural-ko", "constraint-negated-ko", "constraint-wrong-section-ko", "constraint-unsafe-synonym-ko", "constraint-failure", "constraint-hold", "constraint-fallback-hold", "http-error", "read-timeout")]
        [string]$Mode,
        [string]$ProofLogPath = "",
        [ValidateSet("", "joined", "malformed", "overflow", "duplicate", "reversed", "regressing-total", "regressing-dropped", "observation-inversion", "response-inversion")]
        [string]$ProofFixtureMode = ""
    )
    return Start-Job -ArgumentList $Port,$Mode,$ProofLogPath,$ProofFixtureMode -ScriptBlock {
        param([int]$Port, [string]$Mode, [string]$ProofLogPath, [string]$ProofFixtureMode)
        function Get-TestRequestHash {
            param([string]$Value)
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try {
                $bytes = [System.Text.Encoding]::UTF8.GetBytes(([string]$Value).Trim())
                $hex = [System.BitConverter]::ToString($sha.ComputeHash($bytes)).Replace("-", "").ToLowerInvariant()
                return "hash:" + $hex.Substring(0, 12)
            } finally {
                $sha.Dispose()
            }
        }
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        try {
            for ($requestIndex = 0; $requestIndex -lt 4; $requestIndex += 1) {
                $client = $listener.AcceptTcpClient()
                try {
                    $stream = $client.GetStream()
                    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
                    $requestLine = $reader.ReadLine()
                    $requestPath = if ($requestLine -match '^[A-Z]+\s+([^\s]+)') { [string]$Matches[1] } else { "" }
                    $contentLength = 0
                    $requestIdHeader = ""
                    while ($true) {
                        $line = $reader.ReadLine()
                        if ($null -eq $line -or $line.Length -eq 0) { break }
                        if ($line -match '^Content-Length:\s*(\d+)\s*$') {
                            $contentLength = [int]$Matches[1]
                        }
                        if ($line -match '^X-Request-Id:\s*(.+?)\s*$') {
                            $requestIdHeader = [string]$Matches[1]
                        }
                    }
                    $requestPayload = $null
                    if ($contentLength -gt 0) {
                        $requestBody = New-Object char[] $contentLength
                        $readTotal = 0
                        while ($readTotal -lt $contentLength) {
                            $readNow = $reader.Read($requestBody, $readTotal, $contentLength - $readTotal)
                            if ($readNow -le 0) { break }
                            $readTotal += $readNow
                        }
                        try { $requestPayload = (-join $requestBody) | ConvertFrom-Json } catch { $requestPayload = $null }
                    }
                    if ($requestPath -eq "/chat-ui") {
                        $body = '<html data-auth-mode="disabled-test" data-vibe-feature-pool="open"></html>'
                        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
                        $header = "HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n"
                        $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                        $stream.Write($headerBytes, 0, $headerBytes.Length)
                        $stream.Write($bytes, 0, $bytes.Length)
                        $stream.Flush()
                        continue
                    }
                    if ($requestPath -eq "/api/chat/ui-heartbeat") {
                        $afterStream = $requestIndex -ge 2
                        $attempts = if ($afterStream) { 1 } else { 0 }
                        $heartbeat = [ordered]@{
                            debugAiMetrics = [ordered]@{
                                chatUsage = [ordered]@{
                                    schemaVersion = "awx.chat-usage.v1"
                                    observed = $true
                                    counterEpochStartedAtMs = 1000
                                    wireAttemptCoverage = "not_observed"
                                    modelInvocations = [ordered]@{
                                        attempts = $attempts
                                        responseReceived = $attempts
                                        failedBeforeResponse = 0
                                        timedOut = 0
                                        cancelled = 0
                                        providerUsageObservedAttemptCount = 0
                                        providerUsageMissingAttemptCount = $attempts
                                    }
                                }
                            }
                        }
                        $body = $heartbeat | ConvertTo-Json -Depth 6 -Compress
                        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
                        $header = "HTTP/1.1 200 OK`r`nContent-Type: application/json; charset=utf-8`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n"
                        $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                        $stream.Write($headerBytes, 0, $headerBytes.Length)
                        $stream.Write($bytes, 0, $bytes.Length)
                        $stream.Flush()
                        continue
                    }
                    if ($Mode -eq "http-error") {
                        $header = "HTTP/1.1 503 Service Unavailable`r`nContent-Length: 0`r`nConnection: close`r`n`r`n"
                        $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                        $stream.Write($headerBytes, 0, $headerBytes.Length)
                        $stream.Flush()
                        continue
                    }
                    if (-not [string]::IsNullOrWhiteSpace($ProofLogPath) -and
                        -not [string]::IsNullOrWhiteSpace($ProofFixtureMode)) {
                        $requestHash = Get-TestRequestHash -Value $requestIdHeader
                        $otherHash = "hash:000000000000"
                        $prefix = "[LLM_REQUEST_PROOF]"
                        $lines = New-Object System.Collections.Generic.List[string]
                        $lines.Add("$prefix requestHash=$otherHash sequence=91 attemptTotal=1 attemptDropped=0 promptHash=hash:aaaaaaaaaaaa optionsHash=hash:bbbbbbbbbbbb responseHash=hash:cccccccccccc adapterAttempt=true clientHttpExchange=true clientHttpResponse=true providerAttempt=true wireAttempt=true responseObserved=true rawPayload=UNRELATED_RAW_TRAP") | Out-Null
                        if ($ProofFixtureMode -eq "malformed") {
                            $lines.Add("$prefix requestHash=$requestHash sequence=not-a-number attemptTotal=1 rawPayload=MATCHING_RAW_TRAP") | Out-Null
                        } else {
                            $sequenceValues = switch ($ProofFixtureMode) {
                                "duplicate" { @(1, 1) }
                                "reversed" { @(2, 1) }
                                "overflow" { @(1, 2, 3, 4, 5, 6, 7, 8) }
                                default { @(1, 2) }
                            }
                            for ($rowIndex = 0; $rowIndex -lt $sequenceValues.Count; $rowIndex += 1) {
                                $sequence = [int]$sequenceValues[$rowIndex]
                                $optionsHash = if ((($rowIndex + 1) % 2) -eq 0) { "hash:222222222222" } else { "hash:111111111111" }
                                $responseHash = "hash:333333333333"
                                $responseObserved = if ($ProofFixtureMode -eq "response-inversion" -and $rowIndex -eq 0) { "false" } else { "true" }
                                $attemptTotal = if ($ProofFixtureMode -eq "regressing-total") {
                                    @(2, 1)[$rowIndex]
                                } elseif ($ProofFixtureMode -eq "regressing-dropped") {
                                    3
                                } else {
                                    $rowIndex + 1
                                }
                                $attemptDropped = if ($ProofFixtureMode -eq "regressing-dropped") {
                                    @(1, 0)[$rowIndex]
                                } else {
                                    0
                                }
                                $adapterAttempt = if ($ProofFixtureMode -eq "observation-inversion" -and $rowIndex -eq 0) { "false" } else { "true" }
                                $clientHttpExchange = if ($ProofFixtureMode -eq "observation-inversion") {
                                    if ($rowIndex -eq 0) { "true" } else { "false" }
                                } elseif ($rowIndex -eq 0) {
                                    "false"
                                } else {
                                    "true"
                                }
                                $clientHttpResponse = $clientHttpExchange
                                $lines.Add("$prefix requestHash=$requestHash rowAccepted=true sequence=$sequence attemptTotal=$attemptTotal attemptDropped=$attemptDropped promptHash=hash:aaaaaaaaaaaa optionsHash=$optionsHash responseHash=$responseHash adapterAttempt=$adapterAttempt clientHttpExchange=$clientHttpExchange clientHttpResponse=$clientHttpResponse providerAttempt=false wireAttempt=false responseObserved=$responseObserved rawPayload=MATCHING_RAW_TRAP") | Out-Null
                            }
                            if ($ProofFixtureMode -eq "overflow") {
                                $lines.Add("$prefix requestHash=$requestHash rowAccepted=false attemptTotal=10 attemptDropped=2 rawPayload=OVERFLOW_RAW_TRAP") | Out-Null
                            }
                        }
                        Add-Content -LiteralPath $ProofLogPath -Value $lines.ToArray() -Encoding UTF8
                    }
                    $header = "HTTP/1.1 200 OK`r`nContent-Type: text/event-stream`r`nCache-Control: no-cache`r`nConnection: close`r`n`r`n"
                    $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                    $stream.Write($headerBytes, 0, $headerBytes.Length)
                    if ($Mode -eq "terminal-error") {
                        $eventBytes = [System.Text.Encoding]::UTF8.GetBytes("event: error`ndata: {`"type`":`"error`",`"data`":`"synthetic_failure`"}`n`n")
                        $stream.Write($eventBytes, 0, $eventBytes.Length)
                        $stream.Flush()
                    } elseif ($Mode -in @("success", "model-override", "fallback-success", "constraint-success", "constraint-success-ko", "constraint-natural-ko", "constraint-negated-ko", "constraint-wrong-section-ko", "constraint-unsafe-synonym-ko", "constraint-failure", "constraint-hold", "constraint-fallback-hold")) {
                        if ($Mode -eq "constraint-fallback-hold") {
                            $debugPayload = [ordered]@{
                                type = "debug_fx"
                                debugFxSignal = [ordered]@{
                                    phase = "pipeline"
                                    code = "model_unavailable"
                                    labels = [ordered]@{
                                        localLlmFailureClass = "model_upstream_5xx"
                                        localLlmTriggerReason = "llm_fast_bail"
                                    }
                                }
                            }
                            $debugJson = $debugPayload | ConvertTo-Json -Depth 5 -Compress
                            $debugBytes = [System.Text.Encoding]::UTF8.GetBytes("event: debug_fx`ndata: $debugJson`n`n")
                            $stream.Write($debugBytes, 0, $debugBytes.Length)
                            $stream.Flush()
                        }
                        $eventData = if ($Mode -eq "constraint-success") {
                            "OBSERVED_CONSTRAINTS: debt=present;cashflow=tight;spendingLimit=restricted;riskTolerance=low;purchaseCost=high INFERENCE: discretionaryBudget=evidence_needed"
                        } elseif ($Mode -eq "constraint-success-ko") {
                            [regex]::Unescape("\uAD00\uCC30 \uC0AC\uC2E4: \uBD80\uCC44 \uC788\uC74C; \uD604\uAE08\uD750\uB984 \uBE60\uB4EF; \uC9C0\uCD9C \uD55C\uB3C4 \uC81C\uD55C; \uC704\uD5D8 \uD5C8\uC6A9 \uB0AE\uC74C; \uACE0\uAC00 \uAD6C\uB9E4 \uCD94\uB860: \uAC00\uCC98\uBD84 \uC608\uC0B0=evidence_needed")
                        } elseif ($Mode -eq "constraint-natural-ko") {
                            [regex]::Unescape("\uAD00\uCC30 \uC0AC\uC2E4: \uBD80\uCC44\uAC00 \uC874\uC7AC; \uD604\uAE08\uD750\uB984\uC774 \uC81C\uC57D; \uC9C0\uCD9C \uD55C\uB3C4\uAC00 \uC5C4\uACA9; \uC704\uD5D8 \uAC10\uC218\uB3C4\uAC00 \uB0AE\uC74C; \uACE0\uBE44\uC6A9 \uAD6C\uB9E4 \uCD94\uB860: \uAC00\uCC98\uBD84 \uC608\uC0B0=\uC54C \uC218 \uC5C6\uC74C")
                        } elseif ($Mode -eq "constraint-negated-ko") {
                            [regex]::Unescape("\uAD00\uCC30 \uC0AC\uC2E4: \uBD80\uCC44\uAC00 \uC874\uC7AC\uD558\uC9C0 \uC54A\uC74C; \uD604\uAE08\uD750\uB984\uC774 \uBD80\uC871\uD558\uC9C0 \uC54A\uC74C; \uC9C0\uCD9C \uD55C\uB3C4\uAC00 \uC81C\uD55C\uB418\uC9C0 \uC54A\uC74C; \uC704\uD5D8 \uAC10\uC218\uB3C4\uAC00 \uB0AE\uC9C0 \uC54A\uC74C; \uACE0\uBE44\uC6A9 \uAD6C\uB9E4\uAC00 \uC544\uB2D8 \uCD94\uB860: \uAC00\uCC98\uBD84 \uC608\uC0B0=\uC54C \uC218 \uC5C6\uC74C")
                        } elseif ($Mode -eq "constraint-wrong-section-ko") {
                            [regex]::Unescape("\uAD00\uCC30 \uC0AC\uC2E4: \uD574\uB2F9 \uC5C6\uC74C \uCD94\uB860: \uBD80\uCC44\uAC00 \uC874\uC7AC; \uD604\uAE08\uD750\uB984\uC774 \uC81C\uC57D; \uC9C0\uCD9C \uD55C\uB3C4\uAC00 \uC5C4\uACA9; \uC704\uD5D8 \uAC10\uC218\uB3C4\uAC00 \uB0AE\uC74C; \uACE0\uBE44\uC6A9 \uAD6C\uB9E4; \uAC00\uCC98\uBD84 \uC608\uC0B0=\uC54C \uC218 \uC5C6\uC74C")
                        } elseif ($Mode -eq "constraint-unsafe-synonym-ko") {
                            [regex]::Unescape("\uAD00\uCC30 \uC0AC\uC2E4: \uBD80\uCC44\uAC00 \uC874\uC7AC; \uD604\uAE08\uD750\uB984\uC774 \uC81C\uC57D; \uC9C0\uCD9C \uD55C\uB3C4\uAC00 \uC5C4\uACA9; \uC704\uD5D8 \uAC10\uC218\uB3C4\uAC00 \uB0AE\uC74C; \uACE0\uBE44\uC6A9 \uAD6C\uB9E4 \uCD94\uB860: \uAD6C\uB9E4 \uC5EC\uB825\uC774 \uCDA9\uBD84\uD558\uB2E4")
                        } elseif ($Mode -eq "constraint-failure") {
                            "OBSERVED_CONSTRAINTS: debt=present INFERENCE: discretionaryBudget=sufficient"
                        } elseif ($Mode -in @("constraint-hold", "constraint-fallback-hold")) {
                            "HOLD\n" + ([char]0xD55C).ToString() + [char]0xACC4 + ": synthetic contract incomplete"
                        } else {
                            "synthetic_done"
                        }
                        $eventPayload = [ordered]@{
                            type = "final"
                            data = $eventData
                        }
                        if ($Mode -in @("success", "model-override", "fallback-success", "constraint-fallback-hold")) {
                            $eventPayload.modelUsed = if ($Mode -eq "model-override") {
                                [string]$requestPayload.model
                            } elseif ($Mode -in @("fallback-success", "constraint-fallback-hold")) {
                                "qwen3:8b:fallback:local"
                            } else {
                                "qwen3:8b"
                            }
                            $eventPayload.answerMode = if ($Mode -in @("fallback-success", "constraint-fallback-hold")) {
                                "FALLBACK_LOCAL"
                            } else {
                                "MODEL"
                            }
                            $eventPayload.pipelineSnapshot = [ordered]@{
                                route = if ($Mode -in @("fallback-success", "constraint-fallback-hold")) { "fallback" } else { "local-model" }
                                failureClass = if ($Mode -eq "constraint-fallback-hold") { "model_unavailable" } else { $null }
                                disabledReason = if ($Mode -eq "constraint-fallback-hold") { "credential_missing" } else { $null }
                            }
                        }
                        $eventJson = $eventPayload | ConvertTo-Json -Depth 4 -Compress
                        $eventBytes = [System.Text.Encoding]::UTF8.GetBytes("event: final`ndata: $eventJson`n`n")
                        $stream.Write($eventBytes, 0, $eventBytes.Length)
                        $stream.Flush()
                    } else {
                        $eventBytes = [System.Text.Encoding]::UTF8.GetBytes("event: token`ndata: {`"type`":`"token`",`"data`":`"synthetic_partial`"}`n`n")
                        $stream.Write($eventBytes, 0, $eventBytes.Length)
                        $stream.Flush()
                        Start-Sleep -Seconds 5
                    }
                } finally {
                    $client.Dispose()
                }
            }
        } finally {
            $listener.Stop()
        }
    }
}

$scriptPath = Join-Path $PSScriptRoot "chat_ui_vibe_soak.ps1"
if (-not (Test-Path -LiteralPath $scriptPath)) {
    throw "[FAIL] missing soak runner: $scriptPath"
}

$source = Get-Content -Raw -LiteralPath $scriptPath
Assert-Contains "soak has iteration control" $source "[int]`$Iterations"
Assert-Contains "soak has duration control" $source "[int]`$DurationMinutes"
Assert-Contains "soak can delegate safe port close" $source "[switch]`$CloseConflictingListener"
Assert-Contains "soak can stop early on recent window failure" $source "[switch]`$StopOnRecentBelowTarget"
Assert-Contains "soak has dry-run mode" $source "[switch]`$DryRun"
Assert-Contains "soak has deterministic seed" $source "[int]`$Seed"
Assert-Contains "soak can target a single probe" $source "[string]`$ProbeId"
Assert-Contains "soak can replay from a summary artifact" $source "[string]`$ReplayFromSummaryPath"
Assert-Contains "soak bounds listener startup" $source "[int]`$ListenerTimeoutSeconds"
Assert-Contains "soak waits for listener with timeout" $source "WaitForExit"
Assert-Contains "soak uses listener helper" $source "chat_ui_vibe_listener.ps1"
Assert-Contains "soak trusts parsed listener result" $source "`$parsedOk"
Assert-Contains "soak writes event ledger" $source "events.ndjson"
Assert-Contains "soak writes summary" $source "summary.json"
Assert-Contains "soak writes latest pointer" $source "chat-ui-vibe-soak.latest.json"
Assert-Contains "soak writes handoff markdown" $source "chat-ui-vibe-soak.handoff.md"
Assert-Contains "soak writes agent next artifact" $source "chat-ui-vibe-soak.agent-next.json"
Assert-Contains "soak writes latest agent next pointer" $source "chat-ui-vibe-soak.latest-agent-next.json"
Assert-Contains "soak writes per-run progress checkpoint" $source "chat-ui-vibe-soak.progress.json"
Assert-Contains "soak writes latest progress pointer" $source "chat-ui-vibe-soak.latest-progress.json"
Assert-Contains "soak records next handoff action" $source "nextHandoffAction"
Assert-Contains "soak records skipped listener state" $source "listenerSkipped"
Assert-Contains "soak preserves unknown listener ok" $source 'ContainsKey("ok")'
Assert-Contains "soak reads selected server port" $source '[int]$listener.parsed.selectedPorts.server'
Assert-Contains "soak rejects missing selected server port" $source 'listener-selected-port-missing'
Assert-Contains "soak adopts selected server port before UI contract" $source '$Port = $selectedServerPort'
Assert-Contains "soak retains browser target hash" $source 'browserTargetUrlHash'
$adoptIndex = $source.IndexOf('$Port = $selectedServerPort')
$probeIndex = $source.IndexOf('$uiContract = Test-ChatUiContract')
if ($adoptIndex -lt 0 -or $probeIndex -le $adoptIndex) {
    throw '[FAIL] selected port must precede probes'
}
Assert-Contains "soak prints summary JSON" $source "[Console]::Out.WriteLine"
Assert-Contains "soak hashes prompts" $source "promptHash"
Assert-Contains "soak avoids raw prompt artifacts" $source "rawPromptStored"
Assert-Contains "soak avoids raw stream artifacts" $source "rawStreamStored"
Assert-Contains "soak accepts an owned runtime proof log" $source "[string]`$RuntimeLogPath"
Assert-Contains "soak records the pre-request byte offset" $source "runtimeLogOffsetBeforeRequest"
Assert-Contains "soak derives the app request hash contract" $source "Get-RedactedRequestHash"
Assert-Contains "soak parses request-scoped proof rows" $source "Get-RequestProofSummary"
Assert-Contains "soak records count-only correlation" $source "correlationStatus"
Assert-Contains "soak uses coverage-aware random probe selection" $source "Get-NextProbeFromCoverageBag"
Assert-Contains "soak tracks probe coverage" $source "probeCoverage"
Assert-Contains "soak computes 9X reliability summary" $source "New-ReliabilitySummary"
Assert-Contains "soak computes per-probe reliability summary" $source "New-PerProbeReliabilitySummary"
Assert-Contains "soak computes failure focus summary" $source "New-FailureFocusSummary"
Assert-Contains "progress transport safety follows the 9X reliability target" $source '$runningTransportSafetyOk = [bool]$runningReliability.meetsNineXTarget'
Assert-Contains "final transport safety follows the 9X reliability target" $source '$finalTransportSafetyOk = [bool]$finalReliability.meetsNineXTarget'
Assert-Contains "soak has recent window reliability control" $source "[int]`$RecentWindowSize"
Assert-Contains "soak computes recent window reliability" $source "New-RecentWindowReliabilitySummary"
Assert-Contains "soak records 90 percent target" $source "targetReliabilityPct = 90"
Assert-Contains "soak records whether 9X target is met" $source "meetsNineXTarget"
Assert-Contains "soak records lane-level 9X target state" $source "perProbeReliability"
Assert-Contains "soak records recent window reliability" $source "recentWindowReliability"
Assert-Contains "soak records stop reason" $source "stopReason"
Assert-Contains "soak records recent window early stop" $source "recent-window-below-target"
Assert-Contains "soak writes stop event to event ledger" $source 'type = "stop"'
Assert-Contains "soak stop event records recent status" $source "recentWindowStatus"
Assert-Contains "soak stop event records recent observed count" $source "recentWindowObservedCount"
Assert-Contains "soak stop event records recent worst probe" $source "recentWindowWorstProbeId"
Assert-Contains "soak preserves singleton recent window arrays" $source "@(`$events)"
Assert-Contains "soak reads recent observed count by key" $source '[int]$runningRecentWindowReliability["observedCount"]'
Assert-Contains "soak reads recent status by key" $source '[string]$runningRecentWindowReliability["status"]'
Assert-Contains "soak records below-target probe ids" $source "belowTargetProbeIds"
Assert-Contains "soak records worst probe id" $source "worstProbeId"
Assert-Contains "soak records worst recent probe id" $source "worstRecentProbeId"
Assert-Contains "soak records next failure focus" $source "nextFailureFocus"
Assert-Contains "soak records replay failure command" $source "replayFailureCommand"
Assert-Contains "soak computes RAG signal summary" $source "New-RagSignalSummary"
Assert-Contains "soak computes aggregate RAG signal summary" $source "New-RagSignalAggregateSummary"
Assert-Contains "soak has official changelog probe" $source "official_changelog_evidence"
Assert-Contains "official changelog probe uses isolated session" $source "isolatedSession = `$true"
Assert-Contains "official changelog probe requires official evidence" $source "requiresOfficialEvidence = `$true"
Assert-Contains "soak records session scope without raw session id" $source "sessionScope"
Assert-Contains "soak records missing official evidence signal" $source "officialEvidenceMissing"
Assert-Contains "soak aggregates missing official evidence signal" $source "officialEvidenceMissingCount"
Assert-Contains "soak records official evidence-needed signal" $source "officialEvidenceNeeded"
Assert-Contains "soak aggregates official evidence-needed signal" $source "officialEvidenceNeededCount"
Assert-Contains "soak prioritizes official evidence-needed follow-up" $source "inspect_official_search_quality_gate"
Assert-Contains "soak has skill lane external split probe" $source "skill_lane_external_split"
Assert-Contains "soak has decision context integrity probe" $source "decision_context_integrity"
Assert-Contains "decision context probe requires bounded integrity metrics" $source "requiresConstraintIntegrity"
Assert-Contains "soak tracks evidence_needed signal" $source "containsEvidenceNeeded"
Assert-Contains "soak tracks fake command risk" $source "fakeCommandMentionCount"
Assert-Contains "soak tracks off-domain community risk" $source "offDomainCommunityMentionCount"
Assert-Contains "soak resolves replay probe from summary" $source "Resolve-ReplayProbeIdFromSummary"
Assert-Contains "soak records replay source path" $source "replaySourcePath"
Assert-Contains "soak builds compact agent next artifact" $source "New-AgentNextArtifact"
Assert-Contains "soak classifies terminal events" $source "terminal_event_seen"
Assert-NotContains "soak avoids readonly PID shortcut" $source "`$PID"

$proofFixtureCases = @(
    @{ name = "joined"; expectedStatus = "verified"; expectedRows = 2; expectedTotal = 2; expectedDropped = 0; expectedOptions = 2; expectedResponses = 2; expectedAdapter = 2; expectedClientHttp = 1; expectedResponseObserved = 2 },
    @{ name = "malformed"; expectedStatus = "partial"; expectedRows = 0; expectedTotal = 0; expectedDropped = 0; expectedOptions = 0; expectedResponses = 0; expectedAdapter = 0; expectedClientHttp = 0; expectedResponseObserved = 0 },
    @{ name = "duplicate"; expectedStatus = "ambiguous"; expectedRows = 2; expectedTotal = 2; expectedDropped = 0; expectedOptions = 2; expectedResponses = 2; expectedAdapter = 2; expectedClientHttp = 1; expectedResponseObserved = 2 },
    @{ name = "reversed"; expectedStatus = "ambiguous"; expectedRows = 2; expectedTotal = 2; expectedDropped = 0; expectedOptions = 2; expectedResponses = 2; expectedAdapter = 2; expectedClientHttp = 1; expectedResponseObserved = 2 },
    @{ name = "regressing-total"; expectedStatus = "ambiguous"; expectedRows = 2; expectedTotal = 2; expectedDropped = 0; expectedOptions = 2; expectedResponses = 2; expectedAdapter = 2; expectedClientHttp = 1; expectedResponseObserved = 2 },
    @{ name = "regressing-dropped"; expectedStatus = "ambiguous"; expectedRows = 2; expectedTotal = 3; expectedDropped = 1; expectedOptions = 2; expectedResponses = 2; expectedAdapter = 2; expectedClientHttp = 1; expectedResponseObserved = 2 },
    @{ name = "observation-inversion"; expectedStatus = "ambiguous"; expectedRows = 2; expectedTotal = 2; expectedDropped = 0; expectedOptions = 2; expectedResponses = 2; expectedAdapter = 1; expectedClientHttp = 1; expectedResponseObserved = 2 },
    @{ name = "response-inversion"; expectedStatus = "ambiguous"; expectedRows = 2; expectedTotal = 2; expectedDropped = 0; expectedOptions = 2; expectedResponses = 2; expectedAdapter = 2; expectedClientHttp = 1; expectedResponseObserved = 1 },
    @{ name = "overflow"; expectedStatus = "partial"; expectedRows = 8; expectedTotal = 10; expectedDropped = 2; expectedOptions = 2; expectedResponses = 8; expectedAdapter = 8; expectedClientHttp = 7; expectedResponseObserved = 8 }
)
foreach ($proofFixtureCase in $proofFixtureCases) {
    $proofOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-proof-$($proofFixtureCase.name)-" + [guid]::NewGuid().ToString("N"))
    $proofLogPath = Join-Path $proofOut "owned-runtime.log"
    $proofJob = $null
    New-Item -ItemType Directory -Force -Path $proofOut | Out-Null
    Set-Content -LiteralPath $proofLogPath -Value "[LLM_REQUEST_PROOF] requestHash=hash:preexisting00 sequence=77 attemptTotal=1 attemptDropped=0 rawPayload=PREOFFSET_RAW_TRAP" -Encoding UTF8
    try {
        $proofPort = Get-UnusedLoopbackPort
        $proofJob = Start-SyntheticSoakServer -Port $proofPort -Mode "success" -ProofLogPath $proofLogPath -ProofFixtureMode $proofFixtureCase.name
        Start-Sleep -Milliseconds 750
        $proofRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -Port $proofPort -Iterations 1 -DurationMinutes 1 -Seed 20260728 -ProbeId direct_off `
            -SkipListenerStart -CurlWindowSeconds 2 -RuntimeLogPath $proofLogPath -OutDir $proofOut 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "[FAIL] $($proofFixtureCase.name) request-proof fixture should fail soft: exit=$LASTEXITCODE"
        }
        $proofJson = $proofRun | ConvertFrom-Json
        $proofEvent = @(Get-Content -LiteralPath $proofJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
            Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
        if ($proofEvent.Count -ne 1 -or
            [string]$proofEvent[0].correlationStatus -ne [string]$proofFixtureCase.expectedStatus -or
            [int]$proofEvent[0].proofRowCount -ne [int]$proofFixtureCase.expectedRows -or
            [int]$proofEvent[0].attemptTotal -ne [int]$proofFixtureCase.expectedTotal -or
            [int]$proofEvent[0].attemptDropped -ne [int]$proofFixtureCase.expectedDropped -or
            [int]$proofEvent[0].distinctPromptHashCount -ne $(if ($proofFixtureCase.expectedRows -gt 0) { 1 } else { 0 }) -or
            [int]$proofEvent[0].distinctOptionsHashCount -ne [int]$proofFixtureCase.expectedOptions -or
            [int]$proofEvent[0].responseHashObservedCount -ne [int]$proofFixtureCase.expectedResponses -or
            [int]$proofEvent[0].modelAdapterAttemptObservedCount -ne [int]$proofFixtureCase.expectedAdapter -or
            [int]$proofEvent[0].clientHttpExchangeObservedCount -ne [int]$proofFixtureCase.expectedClientHttp -or
            [int]$proofEvent[0].clientHttpResponseObservedCount -ne [int]$proofFixtureCase.expectedClientHttp -or
            [int]$proofEvent[0].providerAttemptObservedCount -ne 0 -or
            [int]$proofEvent[0].wireAttemptObservedCount -ne 0 -or
            [int]$proofEvent[0].responseObservedCount -ne [int]$proofFixtureCase.expectedResponseObserved) {
            $proofLogLength = if (Test-Path -LiteralPath $proofLogPath) { [long](Get-Item -LiteralPath $proofLogPath).Length } else { -1L }
            $proofTaggedLineCount = if (Test-Path -LiteralPath $proofLogPath) { @((Get-Content -LiteralPath $proofLogPath) | Where-Object { $_.Contains("[LLM_REQUEST_PROOF]") }).Count } else { 0 }
            $proofLogHashes = if (Test-Path -LiteralPath $proofLogPath) { @((Get-Content -LiteralPath $proofLogPath) | ForEach-Object { if ($_ -match 'requestHash=(hash:[0-9a-z]+)') { [string]$Matches[1] } } | Sort-Object -Unique) -join ',' } else { "" }
            throw "[FAIL] $($proofFixtureCase.name) request-proof summary mismatch: status=$($proofEvent[0].correlationStatus) rows=$($proofEvent[0].proofRowCount) total=$($proofEvent[0].attemptTotal) dropped=$($proofEvent[0].attemptDropped) logBytes=$proofLogLength taggedLines=$proofTaggedLineCount eventHash=$($proofEvent[0].requestHash) logHashes=$proofLogHashes"
        }
        $serializedEvent = $proofEvent[0] | ConvertTo-Json -Depth 8 -Compress
        if ($serializedEvent -match "RAW_TRAP|X-Request-Id|codex-vibe-soak-") {
            throw "[FAIL] $($proofFixtureCase.name) serialized request proof retained raw proof/request data"
        }
    } finally {
        if ($proofJob) {
            Stop-Job -Job $proofJob -ErrorAction SilentlyContinue
            Remove-Job -Job $proofJob -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $proofOut -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$durableOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $durableOut | Out-Null
try {
    $dryRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port 18166 -Iterations 3 -Seed 7 -DryRun -OutDir $durableOut 2>$null
    $json = $dryRun | ConvertFrom-Json
    if ($json.schemaVersion -ne "awx.chat_ui_vibe_soak.v1") {
        throw "[FAIL] dry-run schema mismatch: $($json.schemaVersion)"
    }
    if ($json.status -ne "dry-run") {
        throw "[FAIL] dry-run status mismatch: $($json.status)"
    }
    if ($json.mutationAllowed -ne $false) {
        throw "[FAIL] dry-run must not mutate"
    }
    if ($json.rawPromptStored -ne $false -or $json.rawStreamStored -ne $false) {
        throw "[FAIL] dry-run must mark raw prompt/stream storage disabled"
    }
    if (-not $json.reliability -or [int]$json.reliability.targetReliabilityPct -ne 90 -or [string]$json.reliability.status -ne "not-measured") {
        throw "[FAIL] dry-run summary should include not-measured 9X reliability target"
    }
    if (-not $json.recentWindowReliability -or [string]$json.recentWindowReliability.status -ne "not-measured" -or [int]$json.recentWindowReliability.observedCount -ne 0) {
        throw "[FAIL] dry-run summary should include not-measured recent window reliability"
    }
    if (-not $json.perProbeReliability -or [string]$json.perProbeReliability.rag_web_budget.status -ne "not-measured") {
        throw "[FAIL] dry-run summary should include lane-level not-measured reliability"
    }
    if (-not $json.failureFocus -or [string]$json.failureFocus.status -ne "no-measured-probes") {
        throw "[FAIL] dry-run summary should include no-measured-probes failure focus"
    }
    if (-not $json.ragSignalSummary -or [string]$json.ragSignalSummary.status -ne "not-measured" -or [int]$json.ragSignalSummary.observedCount -ne 0) {
        throw "[FAIL] dry-run summary should include not-measured RAG signal summary"
    }
    if ([string]$json.failureFocus.nextFailureFocus -ne "run_live_soak") {
        throw "[FAIL] dry-run summary should point nextFailureFocus at live soak"
    }
    if ([int]$json.plannedCount -ne 3) {
        throw "[FAIL] dry-run plannedCount mismatch: $($json.plannedCount)"
    }
    if (-not (Test-Path -LiteralPath $json.summaryPath)) {
        throw "[FAIL] dry-run summary file missing: $($json.summaryPath)"
    }
    if (-not (Test-Path -LiteralPath $json.latestSummaryPath)) {
        throw "[FAIL] dry-run latest summary pointer missing: $($json.latestSummaryPath)"
    }
    if (-not (Test-Path -LiteralPath $json.handoffPath)) {
        throw "[FAIL] dry-run handoff file missing: $($json.handoffPath)"
    }
    if (-not (Test-Path -LiteralPath $json.agentNextPath)) {
        throw "[FAIL] dry-run agent-next file missing: $($json.agentNextPath)"
    }
    if (-not (Test-Path -LiteralPath $json.latestAgentNextPath)) {
        throw "[FAIL] dry-run latest agent-next pointer missing: $($json.latestAgentNextPath)"
    }
    if (-not (Test-Path -LiteralPath $json.progressPath)) {
        throw "[FAIL] dry-run progress checkpoint missing: $($json.progressPath)"
    }
    if (-not (Test-Path -LiteralPath $json.latestProgressPath)) {
        throw "[FAIL] dry-run latest progress pointer missing: $($json.latestProgressPath)"
    }
    $latestJson = Get-Content -Raw -LiteralPath $json.latestSummaryPath | ConvertFrom-Json
    if ([string]$latestJson.summaryPath -ne [string]$json.summaryPath) {
        throw "[FAIL] latest summary pointer should reference current summary: latest=$($latestJson.summaryPath) current=$($json.summaryPath)"
    }
    $agentNextJson = Get-Content -Raw -LiteralPath $json.agentNextPath | ConvertFrom-Json
    if ([string]$agentNextJson.schemaVersion -ne "awx.chat_ui_vibe_soak.agent_next.v1") {
        throw "[FAIL] agent-next schema mismatch: $($agentNextJson.schemaVersion)"
    }
    if ([string]$agentNextJson.summaryPath -ne [string]$json.summaryPath) {
        throw "[FAIL] agent-next should point at current summary"
    }
    if (-not ([string]$agentNextJson.replayFailureCommand).Contains("-ProbeId")) {
        throw "[FAIL] agent-next should include replayFailureCommand"
    }
    if ([string]::IsNullOrWhiteSpace([string]$agentNextJson.recommendedCommand)) {
        throw "[FAIL] agent-next should include recommendedCommand"
    }
    if ([string]$agentNextJson.recommendedCommand -match "-ProbeId\s+<probe_id>") {
        throw "[FAIL] agent-next should not recommend placeholder probe replay when no measured probe failed"
    }
    if (-not ([string]$agentNextJson.nextLongRunCommand).Contains("-StopOnRecentBelowTarget")) {
        throw "[FAIL] agent-next long-run command should preserve recent-window early-stop mode"
    }
    if (-not ([string]$agentNextJson.nextLongRunCommand).Contains("-DurationMinutes 540") -or
        ([string]$agentNextJson.nextLongRunCommand).Contains("-DurationMinutes 1380")) {
        throw "[FAIL] agent-next long-run command must use the bounded 540-minute contract"
    }
    if (-not ([string]$agentNextJson.nextLongRunCommand).Contains("-Iterations 56") -or
        ([string]$agentNextJson.nextLongRunCommand).Contains("-Iterations 0")) {
        throw "[FAIL] agent-next long-run command must cap the seven eight-probe epochs at 56 attempts"
    }
    if ($agentNextJson.rawPromptStored -ne $false -or $agentNextJson.rawStreamStored -ne $false) {
        throw "[FAIL] agent-next must mark raw prompt/stream storage disabled"
    }
    if ([string]$agentNextJson.recentWindowStatus -ne "not-measured") {
        throw "[FAIL] agent-next should include recentWindowStatus"
    }
    if ([string]$agentNextJson.ragSignalStatus -ne "not-measured" -or [int]$agentNextJson.ragSignalObservedCount -ne 0) {
        throw "[FAIL] agent-next should include RAG signal summary fields"
    }
    $latestAgentNextJson = Get-Content -Raw -LiteralPath $json.latestAgentNextPath | ConvertFrom-Json
    if ([string]$latestAgentNextJson.agentNextPath -ne [string]$json.agentNextPath) {
        throw "[FAIL] latest agent-next pointer should reference current agent-next"
    }
    $progressJson = Get-Content -Raw -LiteralPath $json.progressPath | ConvertFrom-Json
    if ([int]$progressJson.iterationsCompleted -ne [int]$json.plannedCount -or [string]$progressJson.status -ne "dry-run-planned") {
        throw "[FAIL] progress checkpoint should preserve dry-run planning state"
    }
    if (-not $progressJson.reliability -or [int]$progressJson.reliability.targetReliabilityPct -ne 90 -or [string]$progressJson.reliability.status -ne "not-measured") {
        throw "[FAIL] dry-run progress checkpoint should include not-measured 9X reliability target"
    }
    if (-not $progressJson.recentWindowReliability -or [string]$progressJson.recentWindowReliability.status -ne "not-measured" -or [int]$progressJson.recentWindowReliability.observedCount -ne 0) {
        throw "[FAIL] dry-run progress checkpoint should include not-measured recent window reliability"
    }
    if (-not $progressJson.perProbeReliability -or [string]$progressJson.perProbeReliability.rag_web_budget.status -ne "not-measured") {
        throw "[FAIL] dry-run progress checkpoint should include lane-level not-measured reliability"
    }
    if (-not $progressJson.failureFocus -or [string]$progressJson.failureFocus.status -ne "no-measured-probes") {
        throw "[FAIL] dry-run progress checkpoint should include no-measured-probes failure focus"
    }
    if (-not $progressJson.ragSignalSummary -or [string]$progressJson.ragSignalSummary.status -ne "not-measured") {
        throw "[FAIL] dry-run progress checkpoint should include not-measured RAG signal summary"
    }
    $latestProgressJson = Get-Content -Raw -LiteralPath $json.latestProgressPath | ConvertFrom-Json
    if ([string]$latestProgressJson.progressPath -ne [string]$json.progressPath) {
        throw "[FAIL] latest progress pointer should reference current progress checkpoint"
    }
    $handoffText = Get-Content -Raw -LiteralPath $json.handoffPath
    if (-not $handoffText.Contains("nextHandoffAction") -or -not $handoffText.Contains("chat-ui-vibe-soak.events.ndjson")) {
        throw "[FAIL] handoff should contain nextHandoffAction and event ledger path"
    }
    if (-not (Test-Path -LiteralPath $json.eventsPath)) {
        throw "[FAIL] dry-run events file missing: $($json.eventsPath)"
    }
    $events = Get-Content -LiteralPath $json.eventsPath
    if ($events.Count -ne 3) {
        throw "[FAIL] dry-run should write one event per planned probe: $($events.Count)"
    }
    foreach ($line in $events) {
        $event = $line | ConvertFrom-Json
        if ([string]::IsNullOrWhiteSpace([string]$event.promptHash)) {
            throw "[FAIL] dry-run event missing promptHash: $line"
        }
        if ($line -match "redacted|Find current evidence|Explain why") {
            throw "[FAIL] dry-run event appears to include raw prompt text: $line"
        }
    }
} finally {
    Remove-Item -LiteralPath $durableOut -Recurse -Force -ErrorAction SilentlyContinue
}

$coverageOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-coverage-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $coverageOut | Out-Null
try {
    $coverageDryRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port 18166 -Iterations 8 -Seed 83 -DryRun -OutDir $coverageOut 2>$null
    $coverageJson = $coverageDryRun | ConvertFrom-Json
    $coverageEvents = @(Get-Content -LiteralPath $coverageJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json })
    $probeIds = @($coverageEvents | ForEach-Object { [string]$_.probeId })
    $uniqueProbeIds = @($probeIds | Sort-Object -Unique)
    $expectedProbeIds = @("decision_context_integrity", "direct_off", "failure_learning", "official_changelog_evidence", "rag_only_memory", "rag_web_budget", "skill_lane_external_split", "web_light_status")
    if ($uniqueProbeIds.Count -ne $expectedProbeIds.Count) {
        throw "[FAIL] dry-run first deck pass should cover every probe once: $($probeIds -join ',')"
    }
    foreach ($expected in $expectedProbeIds) {
        if ($uniqueProbeIds -notcontains $expected) {
            throw "[FAIL] dry-run coverage missing probe: $expected from $($probeIds -join ',')"
        }
    }
    $officialEvent = @($coverageEvents | Where-Object { [string]$_.probeId -eq "official_changelog_evidence" } | Select-Object -First 1)
    if ($officialEvent.Count -ne 1 -or [string]$officialEvent[0].sessionScope -ne "isolated-probe") {
        throw "[FAIL] official changelog probe should be isolated from prior probe history"
    }
    $constraintEvent = @($coverageEvents | Where-Object { [string]$_.probeId -eq "decision_context_integrity" } | Select-Object -First 1)
    if ($constraintEvent.Count -ne 1 -or
        [string]$constraintEvent[0].sessionScope -ne "isolated-probe" -or
        $constraintEvent[0].constraintIntegrityRequired -ne $true -or
        $constraintEvent[0].directDebugShortCircuitRisk -ne $false -or
        $constraintEvent[0].rawPromptStored -ne $false -or
        $constraintEvent[0].PSObject.Properties.Name -contains "message") {
        throw "[FAIL] decision context probe must reach the model path without direct-debug short-circuit risk"
    }
    if ([int]$coverageJson.directDebugShortCircuitRiskCount -ne 0) {
        throw "[FAIL] coverage deck contains a direct-debug short-circuit risk"
    }
    $sharedEvents = @($coverageEvents | Where-Object { [string]$_.probeId -notin @("official_changelog_evidence", "decision_context_integrity") })
    if (@($sharedEvents | Where-Object { [string]$_.sessionScope -ne "run-shared" }).Count -ne 0) {
        throw "[FAIL] non-official probes should keep shared run session scope"
    }
    if (-not $coverageJson.probeCoverage -or [int]$coverageJson.probeCoverage.uniqueProbeCount -ne $expectedProbeIds.Count) {
        throw "[FAIL] dry-run summary should include probeCoverage unique count"
    }
    foreach ($expected in $expectedProbeIds) {
        $laneReliability = $coverageJson.perProbeReliability.$expected
        if (-not $laneReliability -or [int]$laneReliability.targetReliabilityPct -ne 90) {
            throw "[FAIL] dry-run per-probe reliability missing target for: $expected"
        }
    }
} finally {
    Remove-Item -LiteralPath $coverageOut -Recurse -Force -ErrorAction SilentlyContinue
}

$targetOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-target-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $targetOut | Out-Null
try {
    $targetDryRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port 18166 -Iterations 3 -Seed 91 -ProbeId failure_learning -DryRun -OutDir $targetOut 2>$null
    $targetJson = $targetDryRun | ConvertFrom-Json
    if ([string]$targetJson.selectedProbeId -ne "failure_learning") {
        throw "[FAIL] targeted dry-run should record selectedProbeId"
    }
    if (-not ([string]$targetJson.replayFailureCommand).Contains("-ProbeId failure_learning")) {
        throw "[FAIL] targeted dry-run should record a replay command for the selected probe"
    }
    $targetEvents = @(Get-Content -LiteralPath $targetJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json })
    $targetProbeIds = @($targetEvents | ForEach-Object { [string]$_.probeId } | Sort-Object -Unique)
    if ($targetProbeIds.Count -ne 1 -or $targetProbeIds[0] -ne "failure_learning") {
        throw "[FAIL] targeted dry-run should only emit the selected probe: $($targetProbeIds -join ',')"
    }
    if ([int]$targetJson.probeCoverage.uniqueProbeCount -ne 1) {
        throw "[FAIL] targeted dry-run should only count one covered probe"
    }
    $targetHandoff = Get-Content -Raw -LiteralPath $targetJson.handoffPath
    if (-not $targetHandoff.Contains("replayFailureCommand") -or -not $targetHandoff.Contains("-ProbeId failure_learning")) {
        throw "[FAIL] targeted handoff should include replayFailureCommand"
    }
} finally {
    Remove-Item -LiteralPath $targetOut -Recurse -Force -ErrorAction SilentlyContinue
}

$replayOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-replay-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $replayOut | Out-Null
try {
    $seedSummaryPath = Join-Path $replayOut "seed-summary.json"
    @{
        schemaVersion = "awx.chat_ui_vibe_soak.v1"
        failureFocus = @{
            belowTargetProbeIds = @("web_light_status")
            worstProbeId = "failure_learning"
        }
    } | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $seedSummaryPath -Encoding UTF8

    $replayDryRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port 18166 -Iterations 2 -Seed 92 -ReplayFromSummaryPath $seedSummaryPath -DryRun -OutDir $replayOut 2>$null
    $replayJson = $replayDryRun | ConvertFrom-Json
    if ([string]$replayJson.selectedProbeId -ne "web_light_status") {
        throw "[FAIL] replay dry-run should select below-target probe from summary"
    }
    if ([string]$replayJson.replaySourceProbeId -ne "web_light_status") {
        throw "[FAIL] replay dry-run should record replaySourceProbeId"
    }
    if ([string]::IsNullOrWhiteSpace([string]$replayJson.replaySourcePath)) {
        throw "[FAIL] replay dry-run should record replaySourcePath"
    }
    if (-not ([string]$replayJson.replayFailureCommand).Contains("-ProbeId web_light_status")) {
        throw "[FAIL] replay dry-run should emit replay command for resolved probe"
    }
    $replayEvents = @(Get-Content -LiteralPath $replayJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json })
    $replayProbeIds = @($replayEvents | ForEach-Object { [string]$_.probeId } | Sort-Object -Unique)
    if ($replayProbeIds.Count -ne 1 -or $replayProbeIds[0] -ne "web_light_status") {
        throw "[FAIL] replay dry-run should only emit resolved probe: $($replayProbeIds -join ',')"
    }
} finally {
    Remove-Item -LiteralPath $replayOut -Recurse -Force -ErrorAction SilentlyContinue
}

$failureOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-failure-test-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $failureOut | Out-Null
try {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Parse("127.0.0.1"), 0)
    $listener.Start()
    $unusedPort = [int]$listener.LocalEndpoint.Port
    $listener.Stop()

    $failureRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port $unusedPort -Iterations 5 -DurationMinutes 1 -Seed 11 -RecentWindowSize 2 `
        -StopOnRecentBelowTarget -SkipListenerStart -CurlWindowSeconds 1 -OutDir $failureOut 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] failure-path soak should fail soft instead of exiting non-zero: exit=$LASTEXITCODE"
    }
    $failureJson = $failureRun | ConvertFrom-Json
    if ([string]$failureJson.stopReason -ne "recent-window-below-target") {
        throw "[FAIL] failure-path soak should stop on recent window: $($failureJson.stopReason)"
    }
    if ([int]$failureJson.iterationsCompleted -ne 2) {
        throw "[FAIL] failure-path soak should stop after recent window is full: $($failureJson.iterationsCompleted)"
    }
    $failureEvents = @(Get-Content -LiteralPath $failureJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json })
    $streamEvents = @($failureEvents | Where-Object { [string]$_.type -eq "stream_probe" })
    $stopEvents = @($failureEvents | Where-Object { [string]$_.type -eq "stop" })
    if ($streamEvents.Count -ne 2) {
        throw "[FAIL] failure-path soak should record two stream probe events: $($streamEvents.Count)"
    }
    if ($stopEvents.Count -ne 1) {
        throw "[FAIL] failure-path soak should record one stop event: $($stopEvents.Count)"
    }
    if ([string]$stopEvents[0].recentWindowStatus -ne "below-target" -or [int]$stopEvents[0].recentWindowObservedCount -ne 2) {
        throw "[FAIL] stop event should include recent window details"
    }
    if ([string]::IsNullOrWhiteSpace([string]$streamEvents[0].ragSignalStatus)) {
        throw "[FAIL] stream probe event should include ragSignalStatus"
    }
    if ([int]$streamEvents[0].fakeCommandMentionCount -ne 0 -or [int]$streamEvents[0].offDomainCommunityMentionCount -ne 0) {
        throw "[FAIL] empty failure stream should not report fake commands or off-domain community mentions"
    }
    if ($streamEvents[0].officialEvidenceMissing -eq $true) {
        throw "[FAIL] empty failure stream should not report missing official evidence"
    }
    foreach ($event in $streamEvents) {
        if ([string]$event.transport -ne "httpclient") {
            throw "[FAIL] stream probe should report fileless httpclient transport: $($event.transport)"
        }
        if ([string]::IsNullOrWhiteSpace([string]$event.transportCode)) {
            throw "[FAIL] stream probe should report a normalized transportCode"
        }
        if ([int]$event.tempResidueDelta -ne 0) {
            throw "[FAIL] stream probe must report zero raw temp residue: $($event.tempResidueDelta)"
        }
    }
    if (-not $failureJson.ragSignalSummary -or [int]$failureJson.ragSignalSummary.observedCount -ne 2) {
        throw "[FAIL] failure-path summary should include RAG signal aggregate for stream probes"
    }
    if ([string]::IsNullOrWhiteSpace([string]$stopEvents[0].ragSignalStatus)) {
        throw "[FAIL] stop event should include ragSignalStatus"
    }
    foreach ($line in (Get-Content -LiteralPath $failureJson.eventsPath)) {
        if ($line -match "Find current evidence|Explain why|Authorization|Bearer|sk-") {
            throw "[FAIL] failure-path event ledger appears to include raw prompt or secret-bearing text"
        }
    }
} finally {
    Remove-Item -LiteralPath $failureOut -Recurse -Force -ErrorAction SilentlyContinue
}

$transportCases = @(
    @{ name = "success"; expectedCode = "success"; expectedStatus = 200; expectedCurlExit = 0 },
    @{ name = "fallback-success"; expectedCode = "success"; expectedStatus = 200; expectedCurlExit = 0 },
    @{ name = "terminal-error"; expectedCode = "success"; expectedStatus = 200; expectedCurlExit = 0 },
    @{ name = "http-error"; expectedCode = "http_error"; expectedStatus = 503; expectedCurlExit = 0 },
    @{ name = "read-timeout"; expectedCode = "timeout"; expectedStatus = 200; expectedCurlExit = 28 }
)
foreach ($case in $transportCases) {
    $caseOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-$($case.name)-test-" + [guid]::NewGuid().ToString("N"))
    $caseJob = $null
    New-Item -ItemType Directory -Force -Path $caseOut | Out-Null
    try {
        $casePort = Get-UnusedLoopbackPort
        $caseJob = Start-SyntheticSoakServer -Port $casePort -Mode $case.name
        Start-Sleep -Milliseconds 750
        $caseWatch = [System.Diagnostics.Stopwatch]::StartNew()
        $caseWindowSeconds = if ($case.name -eq "read-timeout") { 1 } else { 2 }
        $caseRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -Port $casePort -Iterations 1 -DurationMinutes 1 -Seed 37 -ProbeId direct_off `
            -SkipListenerStart -CurlWindowSeconds $caseWindowSeconds -OutDir $caseOut 2>$null
        $caseWatch.Stop()
        if ($LASTEXITCODE -ne 0) {
            throw "[FAIL] $($case.name) soak should fail soft: exit=$LASTEXITCODE"
        }
        $caseJson = $caseRun | ConvertFrom-Json
        $caseEvent = @(Get-Content -LiteralPath $caseJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
            Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
        if ($caseEvent.Count -ne 1) {
            throw "[FAIL] $($case.name) should emit exactly one stream probe"
        }
        if ([string]$caseEvent[0].transport -ne "httpclient" -or
            [string]$caseEvent[0].transportCode -ne [string]$case.expectedCode -or
            [int]$caseEvent[0].statusCode -ne [int]$case.expectedStatus -or
            [int]$caseEvent[0].curlExit -ne [int]$case.expectedCurlExit) {
            throw "[FAIL] $($case.name) transport mismatch: code=$($caseEvent[0].transportCode) status=$($caseEvent[0].statusCode) exit=$($caseEvent[0].curlExit) errorType=$($caseEvent[0].transportErrorType)"
        }
        if ([int]$caseEvent[0].tempResidueDelta -ne 0) {
            throw "[FAIL] $($case.name) should leave zero run-scoped raw temp residue"
        }
        if ($case.name -eq "success" -and $caseEvent[0].terminalSeen -ne $true) {
            throw "[FAIL] successful synthetic SSE should record a terminal event"
        }
        if ($case.name -eq "success" -and
            ([string]$caseEvent[0].modelUsed -ne "qwen3:8b" -or
             [string]$caseEvent[0].answerMode -ne "MODEL" -or
             [string]$caseEvent[0].route -ne "local-model" -or
             $caseEvent[0].fallbackDetected -ne $false -or
             $caseEvent[0].providerAttemptObserved -ne $false -or
             [string]$caseEvent[0].modelProofStatus -ne "model-observed-provider-not-observed")) {
            throw "[FAIL] successful synthetic SSE should separate model-final proof from unobserved provider usage"
        }
        if ($case.name -eq "success" -and
            ($caseEvent[0].modelAttemptObserved -ne $true -or
             [int]$caseEvent[0].modelAttemptDelta -ne 1 -or
             [int]$caseEvent[0].modelResponseReceivedDelta -ne 1 -or
             [string]$caseEvent[0].modelAttemptCorrelationScope -ne "process-window" -or
             $caseEvent[0].providerUsageObserved -ne $false -or
             [int]$caseEvent[0].providerUsageObservedDelta -ne 0 -or
             [string]$caseEvent[0].wireAttemptCoverage -ne "not_observed")) {
            throw "[FAIL] heartbeat deltas should prove only the model-attempt window, not provider usage"
        }
        if ($case.name -eq "success" -and
            (-not $caseJson.modelProof -or
             [int]$caseJson.modelProof.modelObservedCount -ne 1 -or
             [int]$caseJson.modelProof.providerAttemptObservedCount -ne 0 -or
             [string]$caseJson.modelProof.status -ne "evidence-needed")) {
            throw "[FAIL] summary must keep transport success separate from provider proof"
        }
        if ($case.name -eq "success" -and
            ($caseJson.ok -ne $true -or
             [string]$caseJson.okScope -ne "transport-safety-only" -or
             $caseJson.transportSafetyOk -ne $true -or
             $caseJson.modelSuccessOk -ne $false)) {
            throw "[FAIL] legacy ok must be explicitly scoped to transport safety while model success stays false without provider proof"
        }
        if ($case.name -eq "fallback-success" -and
            ($caseEvent[0].fallbackDetected -ne $true -or
             $caseJson.modelSuccessOk -ne $false -or
             [string]$caseEvent[0].modelProofStatus -ne "fallback-detected")) {
            throw "[FAIL] fallback model metadata must never count as model success"
        }
        if ($case.name -eq "terminal-error" -and
            ($caseEvent[0].terminalSeen -ne $true -or
             [string]$caseEvent[0].classification -ne "terminal_error_seen" -or
             [double]$caseJson.reliability.reliabilityScorePct -ne 0)) {
            throw "[FAIL] SSE error terminal must remain terminal but never count as a reliable success"
        }
        if ($case.name -eq "read-timeout" -and [int]$caseEvent[0].elapsedMs -ge 3500) {
            throw "[FAIL] partial SSE must honor the one-second stream deadline: streamElapsedMs=$($caseEvent[0].elapsedMs)"
        }
        if ($case.name -eq "read-timeout" -and $caseWatch.ElapsedMilliseconds -ge 12000) {
            throw "[FAIL] partial SSE wrapper must remain bounded: wallElapsedMs=$($caseWatch.ElapsedMilliseconds)"
        }
    } finally {
        if ($caseJob) {
            Stop-Job -Job $caseJob -ErrorAction SilentlyContinue
            Remove-Job -Job $caseJob -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $caseOut -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$constraintOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-constraint-test-" + [guid]::NewGuid().ToString("N"))
$constraintJob = $null
New-Item -ItemType Directory -Force -Path $constraintOut | Out-Null
try {
    $constraintPort = Get-UnusedLoopbackPort
    $constraintJob = Start-SyntheticSoakServer -Port $constraintPort -Mode "constraint-success"
    Start-Sleep -Milliseconds 750
    $constraintRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port $constraintPort -Iterations 1 -DurationMinutes 1 -Seed 20260728 `
        -ProbeId decision_context_integrity -SkipListenerStart -CurlWindowSeconds 2 `
        -OutDir $constraintOut 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] constraint integrity soak should succeed: exit=$LASTEXITCODE"
    }
    $constraintJson = $constraintRun | ConvertFrom-Json
    $constraintEvent = @(Get-Content -LiteralPath $constraintJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
    if ($constraintEvent.Count -ne 1 -or
        [double]$constraintEvent[0].contextCompleteness -ne 1.0 -or
        [int]$constraintEvent[0].droppedConstraintCount -ne 0 -or
        [int]$constraintEvent[0].unsupportedInferenceCount -ne 0 -or
        [string]$constraintEvent[0].constraintIntegrityStatus -ne "pass") {
        throw "[FAIL] constraint integrity metrics were not derived from the synthetic response"
    }
    foreach ($line in (Get-Content -LiteralPath $constraintJson.eventsPath)) {
        if ($line -match "OBSERVED_CONSTRAINTS|debt=present|discretionaryBudget") {
            throw "[FAIL] constraint event ledger must not persist raw synthetic response text"
        }
    }
} finally {
    if ($constraintJob) {
        Stop-Job -Job $constraintJob -ErrorAction SilentlyContinue
        Remove-Job -Job $constraintJob -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $constraintOut -Recurse -Force -ErrorAction SilentlyContinue
}

$constraintKoOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-constraint-ko-test-" + [guid]::NewGuid().ToString("N"))
$constraintKoJob = $null
New-Item -ItemType Directory -Force -Path $constraintKoOut | Out-Null
try {
    $constraintKoPort = Get-UnusedLoopbackPort
    $constraintKoJob = Start-SyntheticSoakServer -Port $constraintKoPort -Mode "constraint-success-ko"
    Start-Sleep -Milliseconds 750
    $constraintKoRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port $constraintKoPort -Iterations 1 -DurationMinutes 1 -Seed 20260728 `
        -ProbeId decision_context_integrity -SkipListenerStart -CurlWindowSeconds 2 `
        -OutDir $constraintKoOut 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] Korean constraint integrity soak should succeed: exit=$LASTEXITCODE"
    }
    $constraintKoJson = $constraintKoRun | ConvertFrom-Json
    $constraintKoEvent = @(Get-Content -LiteralPath $constraintKoJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
    if ($constraintKoEvent.Count -ne 1 -or
        [double]$constraintKoEvent[0].contextCompleteness -ne 1.0 -or
        [int]$constraintKoEvent[0].droppedConstraintCount -ne 0 -or
        [int]$constraintKoEvent[0].unsupportedInferenceCount -ne 0 -or
        [string]$constraintKoEvent[0].constraintIntegrityStatus -ne "pass" -or
        [double]$constraintKoJson.reliability.reliabilityScorePct -ne 100) {
        throw "[FAIL] Korean semantic-equivalent constraints must satisfy the integrity contract: completeness=$($constraintKoEvent[0].contextCompleteness) dropped=$($constraintKoEvent[0].droppedConstraintCount) unsupported=$($constraintKoEvent[0].unsupportedInferenceCount) status=$($constraintKoEvent[0].constraintIntegrityStatus) reliability=$($constraintKoJson.reliability.reliabilityScorePct) hold=$($constraintKoEvent[0].answerContractHold) terminal=$($constraintKoEvent[0].terminalSeen) transport=$($constraintKoEvent[0].transportCode) http=$($constraintKoEvent[0].statusCode) classification=$($constraintKoEvent[0].classification) eventTypes=$(@($constraintKoEvent[0].eventTypes) -join ',') payloadTypes=$(@($constraintKoEvent[0].payloadTypes) -join ',')"
    }
} finally {
    if ($constraintKoJob) {
        Stop-Job -Job $constraintKoJob -ErrorAction SilentlyContinue
        Remove-Job -Job $constraintKoJob -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $constraintKoOut -Recurse -Force -ErrorAction SilentlyContinue
}

$constraintNaturalKoOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-constraint-natural-ko-test-" + [guid]::NewGuid().ToString("N"))
$constraintNaturalKoJob = $null
New-Item -ItemType Directory -Force -Path $constraintNaturalKoOut | Out-Null
try {
    $constraintNaturalKoPort = Get-UnusedLoopbackPort
    $constraintNaturalKoJob = Start-SyntheticSoakServer -Port $constraintNaturalKoPort -Mode "constraint-natural-ko"
    Start-Sleep -Milliseconds 750
    $constraintNaturalKoRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port $constraintNaturalKoPort -Iterations 1 -DurationMinutes 1 -Seed 20260728 `
        -ProbeId decision_context_integrity -SkipListenerStart -CurlWindowSeconds 2 `
        -OutDir $constraintNaturalKoOut 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] natural Korean constraint integrity soak should succeed: exit=$LASTEXITCODE"
    }
    $constraintNaturalKoJson = $constraintNaturalKoRun | ConvertFrom-Json
    $constraintNaturalKoEvent = @(Get-Content -LiteralPath $constraintNaturalKoJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
    if ($constraintNaturalKoEvent.Count -ne 1 -or
        [double]$constraintNaturalKoEvent[0].contextCompleteness -ne 1.0 -or
        [int]$constraintNaturalKoEvent[0].droppedConstraintCount -ne 0 -or
        [int]$constraintNaturalKoEvent[0].unsupportedInferenceCount -ne 0 -or
        [string]$constraintNaturalKoEvent[0].constraintIntegrityStatus -ne "pass") {
        throw "[FAIL] natural Korean constraint synonyms must preserve all five categories: completeness=$($constraintNaturalKoEvent[0].contextCompleteness) dropped=$($constraintNaturalKoEvent[0].droppedConstraintCount) unsupported=$($constraintNaturalKoEvent[0].unsupportedInferenceCount) status=$($constraintNaturalKoEvent[0].constraintIntegrityStatus) separated=$($constraintNaturalKoEvent[0].observationInferenceSeparated)"
    }
} finally {
    if ($constraintNaturalKoJob) {
        Stop-Job -Job $constraintNaturalKoJob -ErrorAction SilentlyContinue
        Remove-Job -Job $constraintNaturalKoJob -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $constraintNaturalKoOut -Recurse -Force -ErrorAction SilentlyContinue
}

$constraintAdversarialCases = @(
    @{ name = "unsafe sufficient synonym"; mode = "constraint-unsafe-synonym-ko"; expectedPreserved = 5; expectedUnsupported = 1 },
    @{ name = "constraints in inference only"; mode = "constraint-wrong-section-ko"; expectedPreserved = 0; expectedUnsupported = 0 },
    @{ name = "negated categories"; mode = "constraint-negated-ko"; expectedPreserved = 0; expectedUnsupported = 0 }
)
foreach ($constraintAdversarialCase in $constraintAdversarialCases) {
    $constraintAdversarialOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-constraint-adversarial-test-" + [guid]::NewGuid().ToString("N"))
    $constraintAdversarialJob = $null
    New-Item -ItemType Directory -Force -Path $constraintAdversarialOut | Out-Null
    try {
        $constraintAdversarialPort = Get-UnusedLoopbackPort
        $constraintAdversarialJob = Start-SyntheticSoakServer -Port $constraintAdversarialPort -Mode $constraintAdversarialCase.mode
        Start-Sleep -Milliseconds 750
        $constraintAdversarialRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -Port $constraintAdversarialPort -Iterations 1 -DurationMinutes 1 -Seed 20260728 `
            -ProbeId decision_context_integrity -SkipListenerStart -CurlWindowSeconds 2 `
            -OutDir $constraintAdversarialOut 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "[FAIL] $($constraintAdversarialCase.name) probe should fail soft: exit=$LASTEXITCODE"
        }
        $constraintAdversarialJson = $constraintAdversarialRun | ConvertFrom-Json
        $constraintAdversarialEvent = @(Get-Content -LiteralPath $constraintAdversarialJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
            Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
        $constraintAdversarialLane = $constraintAdversarialJson.perProbeReliability.decision_context_integrity
        if ($constraintAdversarialEvent.Count -ne 1 -or
            [int]$constraintAdversarialEvent[0].preservedConstraintCount -ne [int]$constraintAdversarialCase.expectedPreserved -or
            [int]$constraintAdversarialEvent[0].unsupportedInferenceCount -ne [int]$constraintAdversarialCase.expectedUnsupported -or
            [string]$constraintAdversarialEvent[0].constraintIntegrityStatus -ne "constraint-integrity-missing" -or
            [double]$constraintAdversarialLane.reliabilityScorePct -ne 0) {
            throw "[FAIL] $($constraintAdversarialCase.name) must remain a semantic RED: preserved=$($constraintAdversarialEvent[0].preservedConstraintCount) unsupported=$($constraintAdversarialEvent[0].unsupportedInferenceCount) status=$($constraintAdversarialEvent[0].constraintIntegrityStatus)"
        }
    } finally {
        if ($constraintAdversarialJob) {
            Stop-Job -Job $constraintAdversarialJob -ErrorAction SilentlyContinue
            Remove-Job -Job $constraintAdversarialJob -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $constraintAdversarialOut -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$constraintFailureOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-constraint-failure-test-" + [guid]::NewGuid().ToString("N"))
$constraintFailureJob = $null
New-Item -ItemType Directory -Force -Path $constraintFailureOut | Out-Null
try {
    $constraintFailurePort = Get-UnusedLoopbackPort
    $constraintFailureJob = Start-SyntheticSoakServer -Port $constraintFailurePort -Mode "constraint-failure"
    Start-Sleep -Milliseconds 750
    $constraintFailureRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port $constraintFailurePort -Iterations 1 -DurationMinutes 1 -Seed 20260728 `
        -ProbeId decision_context_integrity -SkipListenerStart -CurlWindowSeconds 2 `
        -OutDir $constraintFailureOut 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] constraint failure probe should fail soft: exit=$LASTEXITCODE"
    }
    $constraintFailureJson = $constraintFailureRun | ConvertFrom-Json
    $constraintFailureEvent = @(Get-Content -LiteralPath $constraintFailureJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
    $constraintLane = $constraintFailureJson.perProbeReliability.decision_context_integrity
    if ($constraintFailureEvent.Count -ne 1 -or
        $constraintFailureEvent[0].terminalSeen -ne $true -or
        [string]$constraintFailureEvent[0].constraintIntegrityStatus -ne "constraint-integrity-missing" -or
        [double]$constraintLane.reliabilityScorePct -ne 0 -or
        [string]$constraintLane.status -ne "below-target" -or
        [double]$constraintFailureJson.reliability.reliabilityScorePct -ne 0) {
        throw "[FAIL] semantic constraint failure must not be counted as a reliable terminal success"
    }
} finally {
    if ($constraintFailureJob) {
        Stop-Job -Job $constraintFailureJob -ErrorAction SilentlyContinue
        Remove-Job -Job $constraintFailureJob -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $constraintFailureOut -Recurse -Force -ErrorAction SilentlyContinue
}

$constraintHoldOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-constraint-hold-test-" + [guid]::NewGuid().ToString("N"))
$constraintHoldJob = $null
New-Item -ItemType Directory -Force -Path $constraintHoldOut | Out-Null
try {
    $constraintHoldPort = Get-UnusedLoopbackPort
    $constraintHoldJob = Start-SyntheticSoakServer -Port $constraintHoldPort -Mode "constraint-hold"
    Start-Sleep -Milliseconds 750
    $constraintHoldRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port $constraintHoldPort -Iterations 1 -DurationMinutes 1 -Seed 20260728 `
        -ProbeId decision_context_integrity -SkipListenerStart -CurlWindowSeconds 2 `
        -OutDir $constraintHoldOut 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] constraint HOLD probe should fail soft: exit=$LASTEXITCODE"
    }
    $constraintHoldJson = $constraintHoldRun | ConvertFrom-Json
    $constraintHoldEvent = @(Get-Content -LiteralPath $constraintHoldJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
    $constraintHoldLane = $constraintHoldJson.perProbeReliability.decision_context_integrity
    if ($constraintHoldEvent.Count -ne 1 -or
        $constraintHoldEvent[0].answerContractHold -ne $true -or
        [string]$constraintHoldEvent[0].answerContractReason -ne "explicit_hold" -or
        [string]$constraintHoldEvent[0].constraintIntegrityStatus -ne "hold" -or
        [string]$constraintHoldEvent[0].classification -ne "constraint_integrity_hold" -or
        [double]$constraintHoldLane.reliabilityScorePct -ne 0) {
        throw "[FAIL] explicit HOLD must be distinguished from both semantic pass and constraint loss"
    }
} finally {
    if ($constraintHoldJob) {
        Stop-Job -Job $constraintHoldJob -ErrorAction SilentlyContinue
        Remove-Job -Job $constraintHoldJob -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $constraintHoldOut -Recurse -Force -ErrorAction SilentlyContinue
}

$constraintFallbackHoldOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-constraint-fallback-hold-test-" + [guid]::NewGuid().ToString("N"))
$constraintFallbackHoldJob = $null
New-Item -ItemType Directory -Force -Path $constraintFallbackHoldOut | Out-Null
try {
    $constraintFallbackHoldPort = Get-UnusedLoopbackPort
    $constraintFallbackHoldJob = Start-SyntheticSoakServer -Port $constraintFallbackHoldPort -Mode "constraint-fallback-hold"
    Start-Sleep -Milliseconds 750
    $constraintFallbackHoldRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port $constraintFallbackHoldPort -Iterations 1 -DurationMinutes 1 -Seed 20260728 `
        -ProbeId decision_context_integrity -SkipListenerStart -CurlWindowSeconds 2 `
        -OutDir $constraintFallbackHoldOut 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] constraint fallback HOLD probe should fail soft: exit=$LASTEXITCODE"
    }
    $constraintFallbackHoldJson = $constraintFallbackHoldRun | ConvertFrom-Json
    $constraintFallbackHoldEvent = @(Get-Content -LiteralPath $constraintFallbackHoldJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
    $expectedConstraintResultCodes = @(
        "debt_present:missing",
        "cashflow_tight:missing",
        "spending_limit_restricted:missing",
        "risk_tolerance_low:missing",
        "purchase_cost_high:missing"
    )
    $observedConstraintResultCodes = @($constraintFallbackHoldEvent[0].constraintResultCodes)
    $observedConstraintFailureCodes = @($constraintFallbackHoldEvent[0].constraintFailureReasonCodes)
    if ($constraintFallbackHoldEvent.Count -ne 1 -or
        $constraintFallbackHoldEvent[0].fallbackDetected -ne $true -or
        [string]$constraintFallbackHoldEvent[0].fallbackApplicability -ne "constraint-contract-applies" -or
        [string]$constraintFallbackHoldEvent[0].constraintContractScope -ne "visible-terminal-answer" -or
        [string]$constraintFallbackHoldEvent[0].pipelineFailureClass -ne "model_unavailable" -or
        [string]$constraintFallbackHoldEvent[0].pipelineDisabledReason -ne "credential_missing" -or
        [string]$constraintFallbackHoldEvent[0].debugFxCode -ne "model_unavailable" -or
        [string]$constraintFallbackHoldEvent[0].debugFxFailureClass -ne "model_upstream_5xx" -or
        [string]$constraintFallbackHoldEvent[0].debugFxTriggerReason -ne "llm_fast_bail" -or
        [string]$constraintFallbackHoldEvent[0].providerAttributionSource -ne "pipeline_snapshot" -or
        (($observedConstraintResultCodes -join ",") -ne ($expectedConstraintResultCodes -join ",")) -or
        $observedConstraintFailureCodes -notcontains "answer_contract_explicit_hold" -or
        $observedConstraintFailureCodes -notcontains "required_constraints_missing" -or
        $observedConstraintFailureCodes -notcontains "observation_inference_sections_missing") {
        throw "[FAIL] fallback HOLD attribution must retain safe pipeline and debug reason codes"
    }
} finally {
    if ($constraintFallbackHoldJob) {
        Stop-Job -Job $constraintFallbackHoldJob -ErrorAction SilentlyContinue
        Remove-Job -Job $constraintFallbackHoldJob -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $constraintFallbackHoldOut -Recurse -Force -ErrorAction SilentlyContinue
}

$modelOverrideOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-model-override-test-" + [guid]::NewGuid().ToString("N"))
$modelOverrideJob = $null
New-Item -ItemType Directory -Force -Path $modelOverrideOut | Out-Null
try {
    $modelOverridePort = Get-UnusedLoopbackPort
    $modelOverrideJob = Start-SyntheticSoakServer -Port $modelOverridePort -Mode "model-override"
    Start-Sleep -Milliseconds 750
    $modelOverrideRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port $modelOverridePort -Iterations 1 -DurationMinutes 1 -Seed 20260728 `
        -ProbeId decision_context_integrity -ModelOverride "qwen3:8b" -SkipListenerStart -CurlWindowSeconds 2 `
        -OutDir $modelOverrideOut 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] model override probe should complete: exit=$LASTEXITCODE"
    }
    $modelOverrideJson = $modelOverrideRun | ConvertFrom-Json
    $modelOverrideEvent = @(Get-Content -LiteralPath $modelOverrideJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
    if ($modelOverrideEvent.Count -ne 1 -or [string]$modelOverrideEvent[0].modelUsed -ne "qwen3:8b") {
        throw "[FAIL] model override must be sent in the live request body"
    }
} finally {
    if ($modelOverrideJob) {
        Stop-Job -Job $modelOverrideJob -ErrorAction SilentlyContinue
        Remove-Job -Job $modelOverrideJob -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $modelOverrideOut -Recurse -Force -ErrorAction SilentlyContinue
}

$modelDefaultOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-model-default-test-" + [guid]::NewGuid().ToString("N"))
$modelDefaultJob = $null
New-Item -ItemType Directory -Force -Path $modelDefaultOut | Out-Null
try {
    $modelDefaultPort = Get-UnusedLoopbackPort
    $modelDefaultJob = Start-SyntheticSoakServer -Port $modelDefaultPort -Mode "model-override"
    Start-Sleep -Milliseconds 750
    $modelDefaultRun = & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -Port $modelDefaultPort -Iterations 1 -DurationMinutes 1 -Seed 20260728 `
        -ProbeId decision_context_integrity -SkipListenerStart -CurlWindowSeconds 2 `
        -OutDir $modelDefaultOut 2>$null
    if ($LASTEXITCODE -ne 0) {
        throw "[FAIL] default model probe should complete: exit=$LASTEXITCODE"
    }
    $modelDefaultJson = $modelDefaultRun | ConvertFrom-Json
    $modelDefaultEvent = @(Get-Content -LiteralPath $modelDefaultJson.eventsPath | ForEach-Object { $_ | ConvertFrom-Json } |
        Where-Object { [string]$_.type -eq "stream_probe" } | Select-Object -First 1)
    if ($modelDefaultEvent.Count -ne 1 -or -not [string]::IsNullOrWhiteSpace([string]$modelDefaultEvent[0].modelUsed)) {
        throw "[FAIL] omitted model override must not add a model to the live request body"
    }
} finally {
    if ($modelDefaultJob) {
        Stop-Job -Job $modelDefaultJob -ErrorAction SilentlyContinue
        Remove-Job -Job $modelDefaultJob -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $modelDefaultOut -Recurse -Force -ErrorAction SilentlyContinue
}

$interruptOut = Join-Path ([System.IO.Path]::GetTempPath()) ("chat-ui-vibe-soak-interrupt-test-" + [guid]::NewGuid().ToString("N"))
$preexistingRawTemp = @(Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) `
    -Filter "awx-chat-vibe-soak-*" -File -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
$interruptResidue = @()
$serverJob = $null
$clientProcess = $null
New-Item -ItemType Directory -Force -Path $interruptOut | Out-Null
try {
    $portProbe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $portProbe.Start()
    $interruptPort = [int]$portProbe.LocalEndpoint.Port
    $portProbe.Stop()

    $serverJob = Start-Job -ArgumentList $interruptPort -ScriptBlock {
        param([int]$Port)
        $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $Port)
        $listener.Start()
        try {
            for ($requestIndex = 0; $requestIndex -lt 2; $requestIndex += 1) {
                $client = $listener.AcceptTcpClient()
                try {
                    $stream = $client.GetStream()
                    $reader = [System.IO.StreamReader]::new($stream, [System.Text.Encoding]::ASCII, $false, 1024, $true)
                    while ($true) {
                        $line = $reader.ReadLine()
                        if ($null -eq $line -or $line.Length -eq 0) { break }
                    }
                    if ($requestIndex -eq 0) {
                        $body = '<html data-auth-mode="disabled-test" data-vibe-feature-pool="open"></html>'
                        $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
                        $header = "HTTP/1.1 200 OK`r`nContent-Type: text/html; charset=utf-8`r`nContent-Length: $($bytes.Length)`r`nConnection: close`r`n`r`n"
                        $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                        $stream.Write($headerBytes, 0, $headerBytes.Length)
                        $stream.Write($bytes, 0, $bytes.Length)
                        $stream.Flush()
                    } else {
                        $header = "HTTP/1.1 200 OK`r`nContent-Type: text/event-stream`r`nCache-Control: no-cache`r`nConnection: keep-alive`r`n`r`n"
                        $headerBytes = [System.Text.Encoding]::ASCII.GetBytes($header)
                        $stream.Write($headerBytes, 0, $headerBytes.Length)
                        $eventBytes = [System.Text.Encoding]::UTF8.GetBytes("event: token`ndata: {`"type`":`"token`",`"data`":`"synthetic`"}`n`n")
                        $stream.Write($eventBytes, 0, $eventBytes.Length)
                        $stream.Flush()
                        Start-Sleep -Seconds 30
                    }
                } finally {
                    $client.Dispose()
                }
            }
        } finally {
            $listener.Stop()
        }
    }
    Start-Sleep -Milliseconds 400

    $clientArgs = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $scriptPath,
        '-Port', [string]$interruptPort, '-Iterations', '1', '-DurationMinutes', '1',
        '-Seed', '29', '-SkipListenerStart', '-CurlWindowSeconds', '30', '-OutDir', $interruptOut
    )
    $clientProcess = Start-Process -FilePath 'powershell' -ArgumentList $clientArgs -PassThru -WindowStyle Hidden
    Start-Sleep -Seconds 2
    if (-not $clientProcess.HasExited) {
        Stop-Process -Id $clientProcess.Id -Force -ErrorAction SilentlyContinue
        $clientProcess.WaitForExit()
    }
    Start-Sleep -Milliseconds 250

    $interruptResidue = @(Get-ChildItem -LiteralPath ([System.IO.Path]::GetTempPath()) `
        -Filter "awx-chat-vibe-soak-*" -File -ErrorAction SilentlyContinue |
        Where-Object { $preexistingRawTemp -notcontains $_.FullName })
} finally {
    if ($clientProcess -and -not $clientProcess.HasExited) {
        Stop-Process -Id $clientProcess.Id -Force -ErrorAction SilentlyContinue
    }
    if ($serverJob) {
        Stop-Job -Job $serverJob -ErrorAction SilentlyContinue
        Remove-Job -Job $serverJob -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $interruptOut -Recurse -Force -ErrorAction SilentlyContinue
    foreach ($file in $interruptResidue) {
        Remove-Item -LiteralPath $file.FullName -Force -ErrorAction SilentlyContinue
    }
}
if ($interruptResidue.Count -ne 0) {
    throw "[FAIL] interrupted soak left raw request/SSE temp residue: count=$($interruptResidue.Count)"
}

Write-Host "[PASS] chat_ui_vibe_soak_tests"
