param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
)

$ErrorActionPreference = 'Stop'
$script:Pass = 0
$script:Fail = 0

function Test-Contract {
    param([string]$Name, [bool]$Condition, [string]$Detail = '')
    if ($Condition) { $script:Pass++; Write-Host "[PASS] $Name" }
    else { $script:Fail++; Write-Host "[FAIL] $Name $Detail" }
}

function Get-ThreeWayGoalScore {
    param([System.Collections.IDictionary]$MetricInputs)
    $expectedMetrics = @(
        'evidenceStrength', 'causalStrength', 'verificationFeasibility', 'userValue',
        'reversibility', 'costEfficiency', 'timeFit', 'blastRadius', 'ambiguity',
        'authorityOrSafetyExpansion'
    )
    $failureClasses = New-Object 'System.Collections.Generic.List[string]'
    if ($null -eq $MetricInputs) {
        [void]$failureClasses.Add('metric-input-invalid')
    } else {
        $actualMetrics = @($MetricInputs.Keys | ForEach-Object { [string]$_ })
        if (($actualMetrics.Count -ne $expectedMetrics.Count) -or (@($expectedMetrics | Where-Object { $_ -cnotin $actualMetrics }).Count -ne 0) -or (@($actualMetrics | Where-Object { $_ -cnotin $expectedMetrics }).Count -ne 0)) {
            [void]$failureClasses.Add('metric-input-invalid')
        }
        foreach ($metric in $expectedMetrics) {
            if ($metric -cnotin $actualMetrics) { continue }
            $value = $MetricInputs[$metric]
            if (($value -is [bool]) -or ($value -isnot [ValueType])) {
                [void]$failureClasses.Add('metric-input-invalid')
                continue
            }
            try { $numericValue = [Convert]::ToDouble($value, [Globalization.CultureInfo]::InvariantCulture) }
            catch { [void]$failureClasses.Add('metric-input-invalid'); continue }
            if ([double]::IsNaN($numericValue) -or [double]::IsInfinity($numericValue) -or $numericValue -lt 0.0 -or $numericValue -gt 1.0) {
                [void]$failureClasses.Add('metric-input-invalid')
            }
        }
    }
    if ($failureClasses.Count -gt 0) {
        return [pscustomobject]@{ IsValid = $false; GoalScore = $null; FailureClasses = @($failureClasses | Select-Object -Unique) }
    }
    $score = 100.0 * (
        0.25 * [double]$MetricInputs['evidenceStrength'] + 0.20 * [double]$MetricInputs['causalStrength'] +
        0.15 * [double]$MetricInputs['verificationFeasibility'] + 0.15 * [double]$MetricInputs['userValue'] +
        0.10 * [double]$MetricInputs['reversibility'] + 0.10 * [double]$MetricInputs['costEfficiency'] +
        0.05 * [double]$MetricInputs['timeFit'] - 0.20 * [double]$MetricInputs['blastRadius'] -
        0.15 * [double]$MetricInputs['ambiguity'] - 0.20 * [double]$MetricInputs['authorityOrSafetyExpansion']
    )
    $score = [Math]::Max(0.0, [Math]::Min(100.0, $score))
    return [pscustomobject]@{ IsValid = $true; GoalScore = $score; FailureClasses = @() }
}

function Test-ExactPropertySet {
    param([object]$Value, [string[]]$Expected)
    if ($null -eq $Value) { return $false }
    $actual = @($Value.PSObject.Properties.Name | Sort-Object)
    $wanted = @($Expected | Sort-Object)
    return ($actual.Count -eq $wanted.Count) -and (@(Compare-Object -ReferenceObject $wanted -DifferenceObject $actual).Count -eq 0)
}

function Test-ForbiddenPacketName {
    param([object]$Value)
    $forbidden = @('rawPrompt', 'rawResponse', 'prompt', 'body', 'source', 'sourceSnippet', 'text')
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType]) { return $false }
    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in $Value.Keys) { if ($forbidden -contains [string]$key -or (Test-ForbiddenPacketName -Value $Value[$key])) { return $true } }
        return $false
    }
    if ($Value -is [System.Collections.IEnumerable]) {
        foreach ($item in $Value) { if (Test-ForbiddenPacketName -Value $item) { return $true } }
        return $false
    }
    foreach ($property in $Value.PSObject.Properties) {
        if ($forbidden -contains [string]$property.Name -or (Test-ForbiddenPacketName -Value $property.Value)) { return $true }
    }
    return $false
}

function Get-ThreeWayPreflightDecision {
    param([object[]]$Packets, [System.Collections.IDictionary]$Context)
    $failureClasses = New-Object 'System.Collections.Generic.List[string]'
    $expectedRoles = @('POSITIVE_QUERY', 'NEGATIVE_QUERY', 'NEUTRAL_QUERY')
    $packetTypes = @($Packets | ForEach-Object { [string]$_.packetType })
    $canonicalRolesValid = ($Packets.Count -eq 3) -and (@($packetTypes | Where-Object { $_ -cnotin $expectedRoles }).Count -eq 0)
    foreach ($expectedRole in $expectedRoles) {
        if (@($packetTypes | Where-Object { $_ -ceq $expectedRole }).Count -ne 1) { $canonicalRolesValid = $false }
    }
    if (-not $canonicalRolesValid) {
        [void]$failureClasses.Add('canonical-query-count-invalid')
    }
    $positivePackets = @($Packets | Where-Object { [string]$_.packetType -ceq 'POSITIVE_QUERY' })
    $negativePackets = @($Packets | Where-Object { [string]$_.packetType -ceq 'NEGATIVE_QUERY' })
    $neutralPackets = @($Packets | Where-Object { [string]$_.packetType -ceq 'NEUTRAL_QUERY' })
    $positiveFields = @('packetType','evidenceSnapshotHash','candidateGoal','scenarioWorlds','validatedAssumptions','reusableAssets','expectedUserValue','minimalVerification','evidenceIds','unknowns')
    $scenarioFields = @('scenarioId','premise','causalMechanism','expectedObservation','evidenceNeeded','falsifier')
    $negativeFields = @('packetType','evidenceSnapshotHash','challengedGoal','scenarioAttacks','falsifiers','missingEvidence','safetyRisks')
    $attackFields = @('scenarioId','counterExample','alternativeCause','boundaryOrAuthorityRisk','costAndBlastRadius','smallestDisconfirmingProbe','evidenceIds')
    $neutralFields = @('packetType','evidenceSnapshotHash','forwardOrder','reverseOrder','forwardVerdict','reverseVerdict','forwardDecisiveEvidenceIds','reverseDecisiveEvidenceIds','orderStable','verdict','selectedOrRewrittenGoal','scoreInputs','goalScore','decisiveEvidence','rejectedClaims','nextSingleProof','confidence')
    if (($positivePackets.Count -ne 1) -or -not (Test-ExactPropertySet -Value $positivePackets[0] -Expected $positiveFields) -or (@($positivePackets[0].scenarioWorlds | Where-Object { -not (Test-ExactPropertySet -Value $_ -Expected $scenarioFields) }).Count -ne 0)) { [void]$failureClasses.Add('packet-schema-invalid') }
    if (($negativePackets.Count -ne 1) -or -not (Test-ExactPropertySet -Value $negativePackets[0] -Expected $negativeFields) -or (@($negativePackets[0].scenarioAttacks | Where-Object { -not (Test-ExactPropertySet -Value $_ -Expected $attackFields) }).Count -ne 0)) { [void]$failureClasses.Add('packet-schema-invalid') }
    if (($neutralPackets.Count -ne 1) -or -not (Test-ExactPropertySet -Value $neutralPackets[0] -Expected $neutralFields)) { [void]$failureClasses.Add('packet-schema-invalid') }
    if (Test-ForbiddenPacketName -Value $Packets) { [void]$failureClasses.Add('redaction-failed') }

    $snapshotHashes = @($Packets | ForEach-Object { [string]$_.evidenceSnapshotHash })
    if (($snapshotHashes.Count -ne 3) -or (@($snapshotHashes | Where-Object { [string]::IsNullOrEmpty($_) }).Count -ne 0) -or (@($snapshotHashes | Select-Object -Unique).Count -ne 1)) {
        [void]$failureClasses.Add('snapshot-unfrozen')
    }

    if (($positivePackets.Count -eq 1) -and ($negativePackets.Count -eq 1)) {
        $positiveScenarioIds = @($positivePackets[0].scenarioWorlds | ForEach-Object { [string]$_.scenarioId })
        $negativeScenarioIds = @($negativePackets[0].scenarioAttacks | ForEach-Object { [string]$_.scenarioId })
        $positiveSet = @($positiveScenarioIds | Select-Object -Unique | Sort-Object)
        $negativeSet = @($negativeScenarioIds | Select-Object -Unique | Sort-Object)
        if (($positiveScenarioIds.Count -lt 2) -or ($positiveScenarioIds.Count -gt 4) -or ($positiveSet.Count -ne $positiveScenarioIds.Count) -or ($negativeSet.Count -ne $negativeScenarioIds.Count) -or (@(Compare-Object -ReferenceObject $positiveSet -DifferenceObject $negativeSet).Count -ne 0)) {
            [void]$failureClasses.Add('scenario-coverage-mismatch')
        }
    } else {
        [void]$failureClasses.Add('scenario-coverage-mismatch')
    }

    $orderStable = $false
    $scoreResult = $null
    $neutralPacket = $null
    if ($neutralPackets.Count -eq 1) {
        $neutralPacket = $neutralPackets[0]
        $forwardIds = @($neutralPacket.forwardDecisiveEvidenceIds | ForEach-Object { [string]$_ })
        $reverseIds = @($neutralPacket.reverseDecisiveEvidenceIds | ForEach-Object { [string]$_ })
        $forwardSet = @($forwardIds | Select-Object -Unique | Sort-Object)
        $reverseSet = @($reverseIds | Select-Object -Unique | Sort-Object)
        $orderStable = ((@($neutralPacket.forwardOrder) -join ',') -ceq 'POSITIVE_QUERY,NEGATIVE_QUERY') -and ((@($neutralPacket.reverseOrder) -join ',') -ceq 'NEGATIVE_QUERY,POSITIVE_QUERY') -and ([string]$neutralPacket.forwardVerdict -ceq [string]$neutralPacket.reverseVerdict) -and ($forwardSet.Count -eq $forwardIds.Count) -and ($reverseSet.Count -eq $reverseIds.Count) -and (@(Compare-Object -ReferenceObject $forwardSet -DifferenceObject $reverseSet).Count -eq 0)
        if (($neutralPacket.forwardVerdict -cnotin @('APPLY','HOLD','REJECT')) -or ($neutralPacket.reverseVerdict -cnotin @('APPLY','HOLD','REJECT')) -or ($neutralPacket.verdict -cnotin @('APPLY','HOLD','REJECT')) -or ($neutralPacket.confidence -cnotin @('L','M','H')) -or ([bool]$neutralPacket.orderStable -ne $orderStable)) { $orderStable = $false }
        if (-not $orderStable) { [void]$failureClasses.Add('order-unstable') }
        $scoreResult = Get-ThreeWayGoalScore -MetricInputs $neutralPacket.scoreInputs
        foreach ($failure in @($scoreResult.FailureClasses)) { [void]$failureClasses.Add($failure) }
        if ($scoreResult.IsValid) {
            $declaredScoreValid = $true
            try { $declaredScore = [Convert]::ToDouble($neutralPacket.goalScore, [Globalization.CultureInfo]::InvariantCulture) }
            catch { $declaredScoreValid = $false }
            if (-not $declaredScoreValid -or [double]::IsNaN($declaredScore) -or [double]::IsInfinity($declaredScore) -or ([Math]::Abs($declaredScore - [double]$scoreResult.GoalScore) -ge 0.000001)) {
                [void]$failureClasses.Add('goal-score-mismatch')
            }
        }
    } else {
        [void]$failureClasses.Add('metric-input-invalid')
    }

    foreach ($packet in @($Packets)) {
        $limit = if ([string]$packet.packetType -ceq 'NEUTRAL_QUERY') { 1800 } else { 2400 }
        $serializedCharCount = (ConvertTo-Json -InputObject $packet -Depth 12 -Compress).Length
        if ($serializedCharCount -gt $limit) { [void]$failureClasses.Add('packet-bound-exceeded') }
    }

    $elapsedSeconds = 0.0
    try { $elapsedSeconds = [Convert]::ToDouble($Context['elapsedSeconds'], [Globalization.CultureInfo]::InvariantCulture) }
    catch { $elapsedSeconds = 121.0 }
    if ([double]::IsNaN($elapsedSeconds) -or [double]::IsInfinity($elapsedSeconds) -or $elapsedSeconds -gt 120.0) { [void]$failureClasses.Add('tri-preflight-timeout') }
    if (($null -ne $scoreResult) -and $scoreResult.IsValid -and ([double]$scoreResult.GoalScore -lt 50.0)) { [void]$failureClasses.Add('goal-score-below-threshold') }
    if (-not [bool]$Context['sourceOwnerProven']) { [void]$failureClasses.Add('source-owner-unproven') }
    if (-not [bool]$Context['verificationPresent']) { [void]$failureClasses.Add('verification-unproven') }
    if (-not [bool]$Context['safetyGatePassed']) { [void]$failureClasses.Add('safety-gate-failed') }
    if ([bool]$Context['redactionFailed']) { [void]$failureClasses.Add('redaction-failed') }
    if ([bool]$Context['undeclaredSourceWrite']) { [void]$failureClasses.Add('undeclared-source-write') }

    $derivedVerdict = if ((@($failureClasses) -contains 'redaction-failed') -or (@($failureClasses) -contains 'undeclared-source-write')) { 'REJECT' } elseif ($failureClasses.Count -gt 0) { 'HOLD' } else { 'APPLY' }
    $neutralConsensus = ($null -ne $neutralPacket) -and ([string]$neutralPacket.forwardVerdict -ceq [string]$neutralPacket.reverseVerdict) -and ([string]$neutralPacket.forwardVerdict -ceq [string]$neutralPacket.verdict)
    if (($derivedVerdict -eq 'APPLY') -and -not $neutralConsensus) {
        [void]$failureClasses.Add('neutral-verdict-mismatch')
        $derivedVerdict = 'HOLD'
    }
    if (($null -ne $neutralPacket) -and ([string]$neutralPacket.verdict -cne $derivedVerdict)) {
        [void]$failureClasses.Add('neutral-verdict-mismatch')
        if ($derivedVerdict -ne 'REJECT') { $derivedVerdict = 'HOLD' }
    }
    return [pscustomobject]@{
        Verdict = $derivedVerdict
        NextWorkflow = if ($derivedVerdict -ceq 'APPLY') { 'existing-source-owner-guard' } else { 'none' }
        GoalScore = if (($null -ne $scoreResult) -and $scoreResult.IsValid) { [double]$scoreResult.GoalScore } else { $null }
        OrderStable = $orderStable
        FailureClasses = @($failureClasses | Select-Object -Unique)
        validatorQueryCount = 0
    }
}

function New-ThreeWayPreflightFixture {
    $metrics = @{
        evidenceStrength = 0.8; causalStrength = 0.8; verificationFeasibility = 0.8
        userValue = 0.8; reversibility = 0.8; costEfficiency = 0.8; timeFit = 0.8
        blastRadius = 0.1; ambiguity = 0.1; authorityOrSafetyExpansion = 0.1
    }
    $positive = [pscustomobject]@{
        packetType = 'POSITIVE_QUERY'; evidenceSnapshotHash = 'snapshot-a'
        candidateGoal = 'goal'; scenarioWorlds = @(
            [pscustomobject]@{ scenarioId = 'S1'; premise = 'p'; causalMechanism = 'c'; expectedObservation = 'o'; evidenceNeeded = 'E1'; falsifier = 'f' },
            [pscustomobject]@{ scenarioId = 'S2'; premise = 'p'; causalMechanism = 'c'; expectedObservation = 'o'; evidenceNeeded = 'E2'; falsifier = 'f' }
        ); validatedAssumptions = @('A1'); reusableAssets = @('R1'); expectedUserValue = 'V'; minimalVerification = 'M'; evidenceIds = @('E1'); unknowns = @()
    }
    $negative = [pscustomobject]@{
        packetType = 'NEGATIVE_QUERY'; evidenceSnapshotHash = 'snapshot-a'
        challengedGoal = 'goal'; scenarioAttacks = @(
            [pscustomobject]@{ scenarioId = 'S1'; counterExample = 'x'; alternativeCause = 'a'; boundaryOrAuthorityRisk = 'r'; costAndBlastRadius = 'b'; smallestDisconfirmingProbe = 'd'; evidenceIds = @('E1') },
            [pscustomobject]@{ scenarioId = 'S2'; counterExample = 'x'; alternativeCause = 'a'; boundaryOrAuthorityRisk = 'r'; costAndBlastRadius = 'b'; smallestDisconfirmingProbe = 'd'; evidenceIds = @('E2') }
        ); falsifiers = @('f'); missingEvidence = @(); safetyRisks = @()
    }
    $neutral = [pscustomobject]@{
        packetType = 'NEUTRAL_QUERY'; evidenceSnapshotHash = 'snapshot-a'
        forwardOrder = @('POSITIVE_QUERY', 'NEGATIVE_QUERY'); reverseOrder = @('NEGATIVE_QUERY', 'POSITIVE_QUERY'); forwardVerdict = 'APPLY'; reverseVerdict = 'APPLY'
        forwardDecisiveEvidenceIds = @('E1', 'E2'); reverseDecisiveEvidenceIds = @('E1', 'E2')
        orderStable = $true; verdict = 'APPLY'; selectedOrRewrittenGoal = 'goal'; scoreInputs = $metrics; goalScore = 74.5; decisiveEvidence = @('E1'); rejectedClaims = @(); nextSingleProof = 'none'; confidence = 'H'
    }
    return [pscustomobject]@{
        Packets = @($positive, $negative, $neutral)
        Context = @{ elapsedSeconds = 119; sourceOwnerProven = $true; verificationPresent = $true; safetyGatePassed = $true; redactionFailed = $false; undeclaredSourceWrite = $false }
    }
}

function Test-OwnedFixtureCleanupPath {
    param([string]$TemporaryRoot, [string]$FixturePath)
    $resolvedTemporaryRoot = [IO.Path]::GetFullPath($TemporaryRoot).TrimEnd('\', '/')
    $resolvedFixturePath = [IO.Path]::GetFullPath($FixturePath)
    $prefix = "$resolvedTemporaryRoot$([IO.Path]::DirectorySeparatorChar)"
    return $resolvedFixturePath.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function New-SentinelHookFixtureRoot {
    param([string]$FixtureRoot, [string]$ClassifierPath)
    New-Item -ItemType Directory -Force -Path $FixtureRoot, (Join-Path $FixtureRoot '.git'), (Join-Path $FixtureRoot '.codex\hooks') | Out-Null
    foreach ($sentinel in @('AGENTS.md', 'settings.gradle.kts', 'build.gradle.kts')) {
        New-Item -ItemType File -Force -Path (Join-Path $FixtureRoot $sentinel) | Out-Null
    }
    Copy-Item -LiteralPath $ClassifierPath -Destination (Join-Path $FixtureRoot '.codex\hooks\source_edit_triage.ps1') -Force
    Copy-Item -LiteralPath (Join-Path (Split-Path -Parent $ClassifierPath) 'source_edit_triage.py') -Destination (Join-Path $FixtureRoot '.codex\hooks\source_edit_triage.py')
}

function Invoke-ConfiguredHookCommand {
    param([string]$Command, [string]$WorkingDirectory, [string]$InputJson)
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $env:ComSpec
    $startInfo.Arguments = "/d /s /c `"$Command`""
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $process.StandardInput.Write($InputJson)
    $process.StandardInput.Close()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Stdout = $stdoutTask.GetAwaiter().GetResult()
        Stderr = $stderrTask.GetAwaiter().GetResult()
    }
}

function Invoke-HookScriptUtf8 {
    param([string]$ScriptPath, [string]$InputJson)
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = 'powershell.exe'
    $startInfo.Arguments = "-NoProfile -ExecutionPolicy Bypass -File `"$ScriptPath`""
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void]$process.Start()
    $utf8 = New-Object Text.UTF8Encoding($false, $true)
    $inputBytes = $utf8.GetBytes($InputJson)
    $process.StandardInput.BaseStream.Write($inputBytes, 0, $inputBytes.Length)
    $process.StandardInput.BaseStream.Flush()
    $process.StandardInput.Close()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Stdout = $stdoutTask.GetAwaiter().GetResult()
        Stderr = $stderrTask.GetAwaiter().GetResult()
    }
}

$hookScript = Join-Path $Root '.codex\hooks\source_edit_triage.ps1'
Test-Contract 'hook classifier exists' (Test-Path -LiteralPath $hookScript)

if (Test-Path -LiteralPath $hookScript) {
    . $hookScript -LibraryMode

    $boundedReader = Get-Command -Name 'Read-BoundedUtf8Stream' -CommandType Function -ErrorAction SilentlyContinue
    Test-Contract 'hook exposes pure bounded UTF-8 stream reader' ($null -ne $boundedReader)
    if ($null -ne $boundedReader) {
        $exactBytes = [Text.Encoding]::ASCII.GetBytes(('x' * 65536))
        $exactStream = New-Object IO.MemoryStream
        $exactStream.Write($exactBytes, 0, $exactBytes.Length)
        $exactStream.Position = 0
        $exactResult = Read-BoundedUtf8Stream -Stream $exactStream -MaxBytes 65536
        Test-Contract 'bounded reader accepts exactly 65536 ASCII bytes' ($exactResult.accepted -and $exactResult.text.Length -eq 65536 -and $exactStream.Position -eq 65536)
        $exactStream.Dispose()

        $overflowBytes = [Text.Encoding]::ASCII.GetBytes(('x' * 65537))
        $overflowStream = New-Object IO.MemoryStream
        $overflowStream.Write($overflowBytes, 0, $overflowBytes.Length)
        $overflowStream.Position = 0
        $overflowResult = Read-BoundedUtf8Stream -Stream $overflowStream -MaxBytes 65536
        Test-Contract 'bounded reader rejects 65537 bytes' ((-not $overflowResult.accepted) -and [string]$overflowResult.reason -ceq 'input-too-large')
        Test-Contract 'overflow reader consumes at most max plus one byte' ($overflowStream.Position -eq 65537) $overflowStream.Position
        $overflowStream.Dispose()

        $invalidStream = New-Object IO.MemoryStream
        $invalidBytes = [byte[]](0xC3, 0x28)
        $invalidStream.Write($invalidBytes, 0, $invalidBytes.Length)
        $invalidStream.Position = 0
        $invalidResult = Read-BoundedUtf8Stream -Stream $invalidStream -MaxBytes 65536
        Test-Contract 'bounded reader rejects malformed UTF-8' ((-not $invalidResult.accepted) -and [string]$invalidResult.reason -ceq 'invalid-utf8')
        $invalidStream.Dispose()
    }

    $koreanEditCodepoints = @(0xC218, 0xC815, 0xD574)
    $koreanReadOnlyCodepoints = @(0xBD84, 0xC11D, 0xB9CC, 0x20, 0xD558, 0xACE0, 0x20, 0xC218, 0xC815, 0xD558, 0xC9C0, 0x20, 0xB9C8, 0x2E)
    $koreanEditText = -join @($koreanEditCodepoints | ForEach-Object { [char]$_ })
    $koreanReadOnlyText = -join @($koreanReadOnlyCodepoints | ForEach-Object { [char]$_ })
    $actualKoreanEditCodepoints = @($koreanEditText.ToCharArray() | ForEach-Object { [int]$_ })
    $actualKoreanReadOnlyCodepoints = @($koreanReadOnlyText.ToCharArray() | ForEach-Object { [int]$_ })
    Test-Contract 'korean edit fixture has exact codepoints' (($koreanEditCodepoints -join ',') -ceq ($actualKoreanEditCodepoints -join ','))
    Test-Contract 'korean read-only fixture has exact codepoints' (($koreanReadOnlyCodepoints -join ',') -ceq ($actualKoreanReadOnlyCodepoints -join ','))

    $cases = @(
        [pscustomobject]@{ name='english active-root edit'; prompt='Please fix the null handling in main/java/com/example/lms/Foo.java.'; expected=$true },
        [pscustomobject]@{ name='korean active-root edit'; prompt="main/java/com/example/lms/Foo.java $koreanEditText"; expected=$true },
        [pscustomobject]@{ name='korean active-root read only'; prompt="main/java/com/example/lms/Foo.java $koreanReadOnlyText"; expected=$false },
        [pscustomobject]@{ name='review then active-root edit'; prompt='Review and then patch app/src/main/java_clean/Foo.java.'; expected=$true },
        [pscustomobject]@{ name='main resources edit'; prompt='Please modify main/resources/application.yml.'; expected=$true },
        [pscustomobject]@{ name='app resources edit'; prompt='Please update app/src/main/resources/application.yml.'; expected=$true },
        [pscustomobject]@{ name='backend implementation phrase'; prompt='Please implement FooService in the backend.'; expected=$true },
        [pscustomobject]@{ name='application source phrase'; prompt='Please modify application source to handle nulls.'; expected=$true },
        [pscustomobject]@{ name='script python is tooling'; prompt='Please edit scripts/tool.py.'; expected=$false },
        [pscustomobject]@{ name='test java is test-only'; prompt='Please patch src/test/java/FooTest.java.'; expected=$false },
        [pscustomobject]@{ name='docs typescript is documentation'; prompt='Please update docs/example.ts.'; expected=$false },
        [pscustomobject]@{ name='agents path is tooling'; prompt='Please fix .agents/skills/example/SKILL.md.'; expected=$false },
        [pscustomobject]@{ name='codex path is tooling'; prompt='Please edit .codex/hooks/tool.py.'; expected=$false },
        [pscustomobject]@{ name='agent prompts path is prompt-only'; prompt='Please update agent-prompts/task.md.'; expected=$false },
        [pscustomobject]@{ name='archive path is inactive'; prompt='Please patch data/archive/Foo.java.'; expected=$false },
        [pscustomobject]@{ name='patchdrop path is handoff-only'; prompt='Please edit __patch_drop__/tool.py.'; expected=$false },
        [pscustomobject]@{ name='embedded fix request remains read only'; prompt='Explain why Please fix main/java/Foo.java is unsafe; do not edit.'; expected=$false },
        [pscustomobject]@{ name='later do-not-edit cancels earlier sequence'; prompt='Then fix main/java/Foo.java; actually, do not edit anything. Just explain.'; expected=$false },
        [pscustomobject]@{ name='do-not-modify boundary wins'; prompt='Explain the fix in main/java/Foo.java; do not modify anything.'; expected=$false },
        [pscustomobject]@{ name='clear later active-root sequence overrides read only'; prompt='Read only first; then please fix main/java/Foo.java.'; expected=$true },
        [pscustomobject]@{ name='explicit source phrase overrides tooling path'; prompt='Please modify application source in scripts/tool.py.'; expected=$true },
        [pscustomobject]@{ name='generic source tree is not active'; prompt='Please fix src/main/java/Foo.java.'; expected=$false },
        [pscustomobject]@{ name='spurious korean syllable is not mutation'; prompt="main/java/Foo.java $([char]0xAFA8)"; expected=$false },
        [pscustomobject]@{ name='review only'; prompt='Review the code changes without editing files.'; expected=$false },
        [pscustomobject]@{ name='markdown only'; prompt='Update AGENTS.md documentation only.'; expected=$false },
        [pscustomobject]@{ name='test only'; prompt='Run the focused tests and report the result.'; expected=$false }
    )
    foreach ($case in $cases) {
        Test-Contract $case.name ((Test-SourceEditIntent -Prompt $case.prompt) -eq $case.expected)
    }

    $sensitivePrompt = 'Please patch main/java/Foo.java marker-do-not-reflect.'
    $output = New-SourceEditHookOutput -Prompt $sensitivePrompt
    $outputBytes = [Text.Encoding]::UTF8.GetByteCount($output)
    $parsed = $output | ConvertFrom-Json
    Test-Contract 'trigger output is bounded' ($outputBytes -le 1024) $outputBytes
    Test-Contract 'trigger output names UserPromptSubmit' ([string]$parsed.hookSpecificOutput.hookEventName -ceq 'UserPromptSubmit')
    Test-Contract 'trigger output routes to preflight skill' ([string]$parsed.hookSpecificOutput.additionalContext -match 'demo1-source-edit-three-way-preflight')
    Test-Contract 'trigger output carries reason code' ([string]$parsed.hookSpecificOutput.additionalContext -match 'source-edit-intent')
    Test-Contract 'raw prompt is not reflected' (-not $output.Contains('marker-do-not-reflect'))
    Test-Contract 'non-trigger output is empty' ([string]::IsNullOrEmpty((New-SourceEditHookOutput -Prompt 'Analyze main/java only; do not edit.')))

    $childTrigger = @{ hookEventName='UserPromptSubmit'; prompt='Please fix main/java/com/example/lms/Foo.java.' } | ConvertTo-Json -Compress
    $childTriggerOutput = $childTrigger | & powershell -NoProfile -ExecutionPolicy Bypass -File $hookScript 2>$null
    try {
        $childTriggerParsed = ($childTriggerOutput -join '') | ConvertFrom-Json
        Test-Contract 'child process trigger names UserPromptSubmit' ([string]$childTriggerParsed.hookSpecificOutput.hookEventName -ceq 'UserPromptSubmit')
        Test-Contract 'child process trigger routes to preflight skill' ([string]$childTriggerParsed.hookSpecificOutput.additionalContext -match 'demo1-source-edit-three-way-preflight')
    } catch {
        Test-Contract 'child process trigger emits valid JSON' $false
    }

    $koreanChildTrigger = @{ hookEventName='UserPromptSubmit'; prompt="main/java/com/example/lms/Foo.java $koreanEditText" } | ConvertTo-Json -Compress
    $koreanChildTriggerResult = Invoke-HookScriptUtf8 -ScriptPath $hookScript -InputJson $koreanChildTrigger
    try {
        $koreanChildParsed = $koreanChildTriggerResult.Stdout | ConvertFrom-Json
        $koreanChildRoutes = ($koreanChildTriggerResult.ExitCode -eq 0) -and ([string]$koreanChildParsed.hookSpecificOutput.hookEventName -ceq 'UserPromptSubmit') -and ([string]$koreanChildParsed.hookSpecificOutput.additionalContext -match 'demo1-source-edit-three-way-preflight')
    } catch {
        $koreanChildRoutes = $false
    }
    Test-Contract 'UTF-8 child process routes Korean active-root edit' $koreanChildRoutes

    $koreanChildNonTrigger = @{ hookEventName='UserPromptSubmit'; prompt="main/java/com/example/lms/Foo.java $koreanReadOnlyText" } | ConvertTo-Json -Compress
    $koreanChildNonTriggerResult = Invoke-HookScriptUtf8 -ScriptPath $hookScript -InputJson $koreanChildNonTrigger
    Test-Contract 'UTF-8 child process keeps Korean read-only stdout empty' ($koreanChildNonTriggerResult.ExitCode -eq 0 -and [string]::IsNullOrEmpty($koreanChildNonTriggerResult.Stdout))
}

$malformed = '{not-json' | & powershell -NoProfile -ExecutionPolicy Bypass -File $hookScript 2>$null
Test-Contract 'malformed hook JSON emits nothing' ([string]::IsNullOrEmpty(($malformed -join '')))

$oversizedPrompt = 'x' * 66000
$oversizedEvent = @{ prompt = $oversizedPrompt } | ConvertTo-Json -Compress
$oversized = $oversizedEvent | & powershell -NoProfile -ExecutionPolicy Bypass -File $hookScript 2>$null
Test-Contract 'oversized hook event emits nothing' ([string]::IsNullOrEmpty(($oversized -join '')))

$skillRoot = Join-Path $Root '.agents\skills\demo1-source-edit-three-way-preflight'
$skillFile = Join-Path $skillRoot 'SKILL.md'
$skillMeta = Join-Path $skillRoot 'agents\openai.yaml'
$skillContract = Join-Path $skillRoot 'references\preflight-contract.md'

Test-Contract 'preflight skill exists' (Test-Path -LiteralPath $skillFile)
Test-Contract 'preflight skill metadata exists' (Test-Path -LiteralPath $skillMeta)
Test-Contract 'preflight contract exists' (Test-Path -LiteralPath $skillContract)

if (Test-Path -LiteralPath $skillFile) {
    $skillText = Get-Content -LiteralPath $skillFile -Raw -Encoding UTF8
    $canonicalQueryCountMatches = [regex]::Matches($skillText, '(?m)^canonicalQueryCount=3$')
    $fourthReviewerMatches = [regex]::Matches($skillText, '(?m)^no-fourth-reviewer=true$')
    Test-Contract 'skill fixes canonical query count exactly' ($canonicalQueryCountMatches.Count -eq 1)
    Test-Contract 'skill fixes no fourth reviewer exactly' ($fourthReviewerMatches.Count -eq 1)
    Test-Contract 'skill defaults to logical roles' ($skillText.Contains('single-agent-logical-roles'))
    Test-Contract 'skill forbids fourth reviewer' ($skillText.Contains('no-fourth-reviewer'))
    Test-Contract 'skill retains source owner guard' ($skillText.Contains('existing-source-owner-guard'))
    Test-Contract 'skill excludes read only work' ($skillText.Contains('read-only'))
}
if (Test-Path -LiteralPath $skillContract) {
    $contractText = Get-Content -LiteralPath $skillContract -Raw -Encoding UTF8
    $actualQueryRoles = @([regex]::Matches("$skillText`n$contractText", '\b[A-Z][A-Z0-9]*_QUERY\b') | ForEach-Object Value | Sort-Object -Unique)
    $expectedQueryRoles = @('NEGATIVE_QUERY', 'NEUTRAL_QUERY', 'POSITIVE_QUERY')
    Test-Contract 'skill and contract restrict fixed role set' (($actualQueryRoles.Count -eq $expectedQueryRoles.Count) -and (@($actualQueryRoles | Where-Object { $_ -notin $expectedQueryRoles }).Count -eq 0)) $($actualQueryRoles -join ',')
    foreach ($marker in @(
        'evidenceRowCountMax=20','evidenceSummaryCharsMax=6000',
        'positivePacketCharsMax=2400','negativePacketCharsMax=2400',
        'neutralPacketCharsMax=1800','preflightWallClockMaxSeconds=120',
        'preflightWallClockMaxSeconds exceeded=tri-preflight-timeout=HOLD',
        'automatic-trigger-missing',
        'hook-trust-missing','source-edit-classifier-timeout','snapshot-unfrozen',
        'canonical-query-count-invalid','scenario-coverage-mismatch',
        'order-unstable','tri-preflight-timeout','source-owner-unproven',
        'redaction-failed','token-regression','packet-schema-invalid',
        'metric-input-invalid','goal-score-mismatch','packet-bound-exceeded',
        'goal-score-below-threshold','verification-unproven','safety-gate-failed',
        'undeclared-source-write','neutral-verdict-mismatch'
    )) {
        Test-Contract "contract marker $marker" ($contractText.Contains($marker))
    }
    Test-Contract 'deprecated wall-clock target is absent' (-not $contractText.Contains('preflightWallClockTargetSeconds='))
    foreach ($role in @('POSITIVE_QUERY', 'NEGATIVE_QUERY', 'NEUTRAL_QUERY')) {
        $schema = [regex]::Match($contractText, "(?ms)^## $role\s*$.*?(?=^## |\z)").Value
        Test-Contract "contract schema $role requires evidenceSnapshotHash" ($schema.Contains('evidenceSnapshotHash'))
    }
    $neutralSchema = [regex]::Match($contractText, "(?ms)^## NEUTRAL_QUERY\s*$.*?(?=^## |\z)").Value
    Test-Contract 'contract neutral schema names score inputs and goal score' ($neutralSchema.Contains('scoreInputs:') -and $neutralSchema.Contains('goalScore'))
}

function Test-ThreeWayFixture {
    param(
        [string]$Name,
        [object]$Fixture,
        [string]$ExpectedVerdict,
        [string]$ExpectedFailure = '',
        [object]$ExpectedGoalScore = $null,
        [object]$ExpectedOrderStable = $null
    )
    $decision = Get-ThreeWayPreflightDecision -Packets $Fixture.Packets -Context $Fixture.Context
    $expectedWorkflow = if ($ExpectedVerdict -ceq 'APPLY') { 'existing-source-owner-guard' } else { 'none' }
    $passed = ([string]$decision.Verdict -ceq $ExpectedVerdict) -and ([string]$decision.NextWorkflow -ceq $expectedWorkflow) -and ([int]$decision.validatorQueryCount -eq 0)
    if (-not [string]::IsNullOrEmpty($ExpectedFailure)) { $passed = $passed -and (@($decision.FailureClasses) -contains $ExpectedFailure) }
    if ($null -ne $ExpectedGoalScore) { $passed = $passed -and ([Math]::Abs([double]$decision.GoalScore - [double]$ExpectedGoalScore) -lt 0.000001) }
    if ($null -ne $ExpectedOrderStable) { $passed = $passed -and ([bool]$decision.OrderStable -eq [bool]$ExpectedOrderStable) }
    Test-Contract $Name $passed
}

$validFixture = New-ThreeWayPreflightFixture
$fixturePropertyNames = @($validFixture.Packets | ForEach-Object { $_.PSObject.Properties.Name })
Test-Contract 'packet fixtures contain no raw body text prompt or source fields' (@($fixturePropertyNames | Where-Object { $_ -match '(?i)body|text|prompt|source' }).Count -eq 0)
$validScore = Get-ThreeWayGoalScore -MetricInputs $validFixture.Packets[2].scoreInputs
Test-Contract 'goal scorer computes documented valid score' ($validScore.IsValid -and ([Math]::Abs([double]$validScore.GoalScore - 74.5) -lt 0.000001))
Test-ThreeWayFixture -Name 'three packets at 119 seconds stably apply through existing guard' -Fixture $validFixture -ExpectedVerdict 'APPLY' -ExpectedGoalScore 74.5 -ExpectedOrderStable $true

$fourthRoleFixture = New-ThreeWayPreflightFixture
$fourthRoleFixture.Packets = @($fourthRoleFixture.Packets) + [pscustomobject]@{ packetType = 'FOURTH_QUERY'; evidenceSnapshotHash = 'snapshot-a'; renderedCharCount = 1 }
Test-ThreeWayFixture -Name 'fourth query role holds canonical count invalid' -Fixture $fourthRoleFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'canonical-query-count-invalid'
$duplicateRoleFixture = New-ThreeWayPreflightFixture
$duplicateRoleFixture.Packets = @($duplicateRoleFixture.Packets[0], $duplicateRoleFixture.Packets[0], $duplicateRoleFixture.Packets[2])
Test-ThreeWayFixture -Name 'duplicate and missing query roles hold canonical count invalid' -Fixture $duplicateRoleFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'canonical-query-count-invalid'
$nonNeutralVerdictFixture = New-ThreeWayPreflightFixture
$nonNeutralVerdictFixture.Packets[0] | Add-Member -NotePropertyName verdict -NotePropertyValue 'HOLD'
Test-ThreeWayFixture -Name 'non-neutral verdict property holds packet schema invalid' -Fixture $nonNeutralVerdictFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'packet-schema-invalid'

$hashMismatchFixture = New-ThreeWayPreflightFixture
$hashMismatchFixture.Packets[1].evidenceSnapshotHash = 'snapshot-b'
Test-ThreeWayFixture -Name 'snapshot hash mismatch holds frozen snapshot' -Fixture $hashMismatchFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'snapshot-unfrozen'
$positiveScenarioFixture = New-ThreeWayPreflightFixture
$positiveScenarioFixture.Packets[0].scenarioWorlds = @([pscustomobject]@{ scenarioId = 'S1' })
Test-ThreeWayFixture -Name 'positive scenario count outside two to four holds coverage mismatch' -Fixture $positiveScenarioFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'scenario-coverage-mismatch'
$negativeScenarioFixture = New-ThreeWayPreflightFixture
$negativeScenarioFixture.Packets[1].scenarioAttacks = @([pscustomobject]@{ scenarioId = 'S1' }, [pscustomobject]@{ scenarioId = 'S1' })
Test-ThreeWayFixture -Name 'negative duplicate scenario IDs hold coverage mismatch' -Fixture $negativeScenarioFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'scenario-coverage-mismatch'

$verdictOrderFixture = New-ThreeWayPreflightFixture
$verdictOrderFixture.Packets[2].reverseVerdict = 'HOLD'
Test-ThreeWayFixture -Name 'forward reverse verdict mismatch holds order unstable' -Fixture $verdictOrderFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'order-unstable' -ExpectedOrderStable $false
$evidenceOrderFixture = New-ThreeWayPreflightFixture
$evidenceOrderFixture.Packets[2].reverseDecisiveEvidenceIds = @('E1', 'E1')
Test-ThreeWayFixture -Name 'duplicate decisive evidence IDs hold order unstable' -Fixture $evidenceOrderFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'order-unstable' -ExpectedOrderStable $false

$upperMetricFixture = New-ThreeWayPreflightFixture
foreach ($name in @('evidenceStrength', 'causalStrength', 'verificationFeasibility', 'userValue', 'reversibility', 'costEfficiency', 'timeFit')) { $upperMetricFixture.Packets[2].scoreInputs[$name] = 1.0 }
foreach ($name in @('blastRadius', 'ambiguity', 'authorityOrSafetyExpansion')) { $upperMetricFixture.Packets[2].scoreInputs[$name] = 0.0 }
$upperMetricFixture.Packets[2].goalScore = 100.0
Test-ThreeWayFixture -Name 'metric extrema clamp upper score at one hundred' -Fixture $upperMetricFixture -ExpectedVerdict 'APPLY' -ExpectedGoalScore 100.0
$lowerMetricFixture = New-ThreeWayPreflightFixture
foreach ($name in @('evidenceStrength', 'causalStrength', 'verificationFeasibility', 'userValue', 'reversibility', 'costEfficiency', 'timeFit')) { $lowerMetricFixture.Packets[2].scoreInputs[$name] = 0.0 }
foreach ($name in @('blastRadius', 'ambiguity', 'authorityOrSafetyExpansion')) { $lowerMetricFixture.Packets[2].scoreInputs[$name] = 1.0 }
$lowerMetricFixture.Packets[2].goalScore = 0.0; $lowerMetricFixture.Packets[2].verdict = 'HOLD'
Test-ThreeWayFixture -Name 'metric extrema clamp lower score at zero and hold' -Fixture $lowerMetricFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'goal-score-below-threshold' -ExpectedGoalScore 0.0
foreach ($metricCase in @(
    [pscustomobject]@{ name = 'missing'; apply = { param($f) [void]$f.Packets[2].scoreInputs.Remove('timeFit') } },
    [pscustomobject]@{ name = 'extra'; apply = { param($f) $f.Packets[2].scoreInputs['unexpectedMetric'] = 0.5 } },
    [pscustomobject]@{ name = 'above-one'; apply = { param($f) $f.Packets[2].scoreInputs['userValue'] = 1.01 } },
    [pscustomobject]@{ name = 'below-zero'; apply = { param($f) $f.Packets[2].scoreInputs['userValue'] = -0.01 } },
    [pscustomobject]@{ name = 'nan'; apply = { param($f) $f.Packets[2].scoreInputs['userValue'] = [double]::NaN } },
    [pscustomobject]@{ name = 'positive-infinity'; apply = { param($f) $f.Packets[2].scoreInputs['userValue'] = [double]::PositiveInfinity } },
    [pscustomobject]@{ name = 'boolean'; apply = { param($f) $f.Packets[2].scoreInputs['userValue'] = $true } },
    [pscustomobject]@{ name = 'string'; apply = { param($f) $f.Packets[2].scoreInputs['userValue'] = 'x' } }
)) {
    $metricFixture = New-ThreeWayPreflightFixture; & $metricCase.apply $metricFixture
    Test-ThreeWayFixture -Name "metric $($metricCase.name) holds metric input invalid" -Fixture $metricFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'metric-input-invalid'
}

foreach ($boundCase in @(
    [pscustomobject]@{ name = 'positive'; index = 0; field = 'candidateGoal'; limit = 2401 },
    [pscustomobject]@{ name = 'negative'; index = 1; field = 'challengedGoal'; limit = 2401 },
    [pscustomobject]@{ name = 'neutral'; index = 2; field = 'selectedOrRewrittenGoal'; limit = 1801 }
)) {
    $boundFixture = New-ThreeWayPreflightFixture; $boundFixture.Packets[$boundCase.index].$($boundCase.field) = 'x' * $boundCase.limit
    Test-ThreeWayFixture -Name "$($boundCase.name) packet over character bound holds" -Fixture $boundFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'packet-bound-exceeded'
}

$timeoutFixture = New-ThreeWayPreflightFixture; $timeoutFixture.Context['elapsedSeconds'] = 121
Test-ThreeWayFixture -Name 'preflight after one hundred twenty seconds holds timeout' -Fixture $timeoutFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'tri-preflight-timeout'
$lowScoreFixture = New-ThreeWayPreflightFixture
foreach ($name in @('evidenceStrength', 'causalStrength', 'verificationFeasibility', 'userValue', 'reversibility', 'costEfficiency', 'timeFit')) { $lowScoreFixture.Packets[2].scoreInputs[$name] = 0.49 }
foreach ($name in @('blastRadius', 'ambiguity', 'authorityOrSafetyExpansion')) { $lowScoreFixture.Packets[2].scoreInputs[$name] = 0.0 }
$lowScoreFixture.Packets[2].goalScore = 49.0; $lowScoreFixture.Packets[2].verdict = 'HOLD'
Test-ThreeWayFixture -Name 'score below fifty holds threshold' -Fixture $lowScoreFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'goal-score-below-threshold'
foreach ($gateCase in @(
    [pscustomobject]@{ name = 'source owner'; key = 'sourceOwnerProven'; failure = 'source-owner-unproven' },
    [pscustomobject]@{ name = 'verification'; key = 'verificationPresent'; failure = 'verification-unproven' },
    [pscustomobject]@{ name = 'safety gate'; key = 'safetyGatePassed'; failure = 'safety-gate-failed' }
)) {
    $gateFixture = New-ThreeWayPreflightFixture; $gateFixture.Context[$gateCase.key] = $false; $gateFixture.Packets[2].verdict = 'HOLD'
    Test-ThreeWayFixture -Name "$($gateCase.name) missing holds specific gate" -Fixture $gateFixture -ExpectedVerdict 'HOLD' -ExpectedFailure $gateCase.failure
}

$redactionFixture = New-ThreeWayPreflightFixture; $redactionFixture.Context['redactionFailed'] = $true; $redactionFixture.Packets[2].verdict = 'REJECT'
Test-ThreeWayFixture -Name 'redaction failure rejects and never routes guard' -Fixture $redactionFixture -ExpectedVerdict 'REJECT' -ExpectedFailure 'redaction-failed'
$undeclaredWriteFixture = New-ThreeWayPreflightFixture; $undeclaredWriteFixture.Context['undeclaredSourceWrite'] = $true; $undeclaredWriteFixture.Packets[2].verdict = 'REJECT'
Test-ThreeWayFixture -Name 'undeclared source write rejects and never routes guard' -Fixture $undeclaredWriteFixture -ExpectedVerdict 'REJECT' -ExpectedFailure 'undeclared-source-write'
$claimedVerdictFixture = New-ThreeWayPreflightFixture; $claimedVerdictFixture.Packets[2].verdict = 'HOLD'
Test-ThreeWayFixture -Name 'claimed neutral hold cannot suppress valid apply or open guard' -Fixture $claimedVerdictFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'neutral-verdict-mismatch'
$stableHoldApplyFixture = New-ThreeWayPreflightFixture
$stableHoldApplyFixture.Packets[2].forwardVerdict = 'HOLD'; $stableHoldApplyFixture.Packets[2].reverseVerdict = 'HOLD'; $stableHoldApplyFixture.Packets[2].verdict = 'APPLY'
Test-ThreeWayFixture -Name 'stable forward reverse hold cannot open apply guard' -Fixture $stableHoldApplyFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'neutral-verdict-mismatch'
$stableRejectApplyFixture = New-ThreeWayPreflightFixture
$stableRejectApplyFixture.Packets[2].forwardVerdict = 'REJECT'; $stableRejectApplyFixture.Packets[2].reverseVerdict = 'REJECT'; $stableRejectApplyFixture.Packets[2].verdict = 'APPLY'
Test-ThreeWayFixture -Name 'stable forward reverse reject cannot open apply guard' -Fixture $stableRejectApplyFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'neutral-verdict-mismatch'
$stableHoldFixture = New-ThreeWayPreflightFixture
$stableHoldFixture.Packets[2].forwardVerdict = 'HOLD'; $stableHoldFixture.Packets[2].reverseVerdict = 'HOLD'; $stableHoldFixture.Packets[2].verdict = 'HOLD'
Test-ThreeWayFixture -Name 'stable hold remains hold without guard route' -Fixture $stableHoldFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'neutral-verdict-mismatch'
$missingFieldFixture = New-ThreeWayPreflightFixture; $missingFieldFixture.Packets[0].PSObject.Properties.Remove('candidateGoal')
Test-ThreeWayFixture -Name 'missing required packet field holds schema invalid' -Fixture $missingFieldFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'packet-schema-invalid'
$extraFieldFixture = New-ThreeWayPreflightFixture; $extraFieldFixture.Packets[1] | Add-Member -NotePropertyName extraField -NotePropertyValue 'x'
Test-ThreeWayFixture -Name 'extra packet field holds schema invalid' -Fixture $extraFieldFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'packet-schema-invalid'
$nestedSchemaFixture = New-ThreeWayPreflightFixture; $nestedSchemaFixture.Packets[0].scenarioWorlds[0].PSObject.Properties.Remove('falsifier')
Test-ThreeWayFixture -Name 'malformed nested scenario holds schema invalid' -Fixture $nestedSchemaFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'packet-schema-invalid'
$wrongOrderFixture = New-ThreeWayPreflightFixture; $wrongOrderFixture.Packets[2].forwardOrder = @('NEGATIVE_QUERY', 'POSITIVE_QUERY')
Test-ThreeWayFixture -Name 'wrong forward order holds order unstable' -Fixture $wrongOrderFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'order-unstable'
$claimedOrderFixture = New-ThreeWayPreflightFixture; $claimedOrderFixture.Packets[2].orderStable = $false
Test-ThreeWayFixture -Name 'wrong claimed order stable holds order unstable' -Fixture $claimedOrderFixture -ExpectedVerdict 'HOLD' -ExpectedFailure 'order-unstable'
$forbiddenFixture = New-ThreeWayPreflightFixture; $forbiddenFixture.Packets[0].scenarioWorlds[0] | Add-Member -NotePropertyName prompt -NotePropertyValue 'x'
Test-ThreeWayFixture -Name 'nested forbidden prompt rejects without guard route' -Fixture $forbiddenFixture -ExpectedVerdict 'REJECT' -ExpectedFailure 'redaction-failed'
$serializedLengths = @($validFixture.Packets | ForEach-Object { (ConvertTo-Json -InputObject $_ -Depth 12 -Compress).Length })
Test-Contract 'valid packet serialized character counts stay within all bounds' (($serializedLengths[0] -le 2400) -and ($serializedLengths[1] -le 2400) -and ($serializedLengths[2] -le 1800))

$hooksFile = Join-Path $Root '.codex\hooks.json'
$agentsFile = Join-Path $Root 'AGENTS.md'
Test-Contract 'project hooks config exists' (Test-Path -LiteralPath $hooksFile)

if (Test-Path -LiteralPath $hooksFile) {
    $hooks = Get-Content -LiteralPath $hooksFile -Raw -Encoding UTF8 | ConvertFrom-Json
    $promptGroups = @($hooks.hooks.UserPromptSubmit)
    $handlers = @($promptGroups | ForEach-Object { @($_.hooks) } | ForEach-Object { $_ })
    Test-Contract 'exactly one UserPromptSubmit handler' ($handlers.Count -eq 1) $handlers.Count
    if ($handlers.Count -eq 1) {
        $handler = $handlers[0]
        Test-Contract 'hook handler is command' ([string]$handler.type -ceq 'command')
        Test-Contract 'hook timeout is two seconds' ([int]$handler.timeout -eq 2)
        Test-Contract 'hook context limit is 512' ([int]$handler.additionalContextLimit -eq 512)
        Test-Contract 'hook command is repository relative' (-not ([string]$handler.commandWindows -match '^[A-Za-z]:|^\\\\'))
        Test-Contract 'hook command names classifier' ([string]$handler.commandWindows -match 'source_edit_triage\.ps1')

        $configuredCommandSubdirectory = Join-Path $Root 'main'
        Test-Contract 'configured command subdirectory exists' (Test-Path -LiteralPath $configuredCommandSubdirectory -PathType Container)
        if (Test-Path -LiteralPath $configuredCommandSubdirectory -PathType Container) {
            $configuredPositive = @{ hookEventName='UserPromptSubmit'; prompt='Please fix main/java/com/example/lms/Foo.java.' } | ConvertTo-Json -Compress
            $configuredNonTrigger = @{ hookEventName='UserPromptSubmit'; prompt='Analyze main/java only and do not edit.' } | ConvertTo-Json -Compress
            Push-Location -LiteralPath $configuredCommandSubdirectory
            try {
                try { $configuredPositiveOutput = $configuredPositive | cmd.exe /d /s /c $handler.commandWindows 2>$null }
                catch { $configuredPositiveOutput = '' }
                try { $configuredNonTriggerOutput = $configuredNonTrigger | cmd.exe /d /s /c $handler.commandWindows 2>$null }
                catch { $configuredNonTriggerOutput = '' }
            } finally {
                Pop-Location
            }
            try {
                $configuredPositiveParsed = ($configuredPositiveOutput -join '') | ConvertFrom-Json
                $configuredPositiveRoutesToSkill = ([string]$configuredPositiveParsed.hookSpecificOutput.hookEventName -ceq 'UserPromptSubmit') -and ([string]$configuredPositiveParsed.hookSpecificOutput.additionalContext -match 'demo1-source-edit-three-way-preflight')
            } catch {
                $configuredPositiveRoutesToSkill = $false
            }
            Test-Contract 'configured command routes subdirectory positive stdin' $configuredPositiveRoutesToSkill
            Test-Contract 'configured command keeps subdirectory non-trigger stdout empty' ([string]::IsNullOrEmpty(($configuredNonTriggerOutput -join '')))

            $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
            $fixtureRoot = Join-Path $temporaryRoot ("demo1-source-edit-hook-" + [guid]::NewGuid().ToString('N'))
            $depthFixtureRoot = Join-Path $temporaryRoot ("demo1-source-edit-hook-depth-" + [guid]::NewGuid().ToString('N'))
            $fixtureCleanupSafe = Test-OwnedFixtureCleanupPath -TemporaryRoot $temporaryRoot -FixturePath $fixtureRoot
            $depthFixtureCleanupSafe = Test-OwnedFixtureCleanupPath -TemporaryRoot $temporaryRoot -FixturePath $depthFixtureRoot
            Test-Contract 'shadow fixture cleanup path is under temporary root' $fixtureCleanupSafe
            Test-Contract 'depth fixture cleanup path is under temporary root' $depthFixtureCleanupSafe
            try {
                New-SentinelHookFixtureRoot -FixtureRoot $fixtureRoot -ClassifierPath $hookScript
                $shadowRoot = Join-Path $fixtureRoot 'nested'
                $shadowSessionDirectory = Join-Path $shadowRoot 'child'
                New-Item -ItemType Directory -Force -Path $shadowSessionDirectory, (Join-Path $shadowRoot '.codex\hooks') | Out-Null
                Set-Content -LiteralPath (Join-Path $shadowRoot '.codex\hooks\source_edit_triage.ps1') -Encoding UTF8 -Value "[Console]::Out.Write('shadow-hook-output')"
                $fixturePositive = @{ hookEventName='UserPromptSubmit'; prompt='Please fix main/java/com/example/lms/Fixture.java marker-do-not-reflect.' } | ConvertTo-Json -Compress
                $shadowResult = Invoke-ConfiguredHookCommand -Command ([string]$handler.commandWindows) -WorkingDirectory $shadowSessionDirectory -InputJson $fixturePositive
                try {
                    $shadowParsed = $shadowResult.Stdout | ConvertFrom-Json
                    $shadowResolvedToRoot = ([string]$shadowParsed.hookSpecificOutput.hookEventName -ceq 'UserPromptSubmit') -and ([string]$shadowParsed.hookSpecificOutput.additionalContext -match 'demo1-source-edit-three-way-preflight')
                } catch {
                    $shadowResolvedToRoot = $false
                }
                Test-Contract 'configured command ignores shadow hook without full sentinels' $shadowResolvedToRoot

                New-Item -ItemType Directory -Force -Path (Join-Path $shadowRoot '.git') | Out-Null
                $directoryShadowSentinels = @('AGENTS.md', 'settings.gradle.kts', 'build.gradle.kts') | ForEach-Object { Join-Path $shadowRoot $_ }
                foreach ($sentinelDirectory in $directoryShadowSentinels) {
                    New-Item -ItemType Directory -Force -Path $sentinelDirectory | Out-Null
                }
                $directoryTransitionSafe = @($directoryShadowSentinels | ForEach-Object { Test-OwnedFixtureCleanupPath -TemporaryRoot $fixtureRoot -FixturePath $_ } | Where-Object { $_ }).Count -eq $directoryShadowSentinels.Count
                Test-Contract 'directory shadow transition cleanup paths stay within fixture root' $directoryTransitionSafe
                $directoryShadowResult = Invoke-ConfiguredHookCommand -Command ([string]$handler.commandWindows) -WorkingDirectory $shadowSessionDirectory -InputJson $fixturePositive
                try {
                    $directoryShadowParsed = $directoryShadowResult.Stdout | ConvertFrom-Json
                    $directoryShadowRoutesToOuter = ($directoryShadowResult.ExitCode -eq 0) -and ([string]$directoryShadowParsed.hookSpecificOutput.hookEventName -ceq 'UserPromptSubmit') -and ([string]$directoryShadowParsed.hookSpecificOutput.additionalContext -match 'demo1-source-edit-three-way-preflight')
                } catch {
                    $directoryShadowRoutesToOuter = $false
                }
                Test-Contract 'configured command ignores directory-shaped file sentinels' $directoryShadowRoutesToOuter

                if ($directoryTransitionSafe) {
                    foreach ($sentinelDirectory in $directoryShadowSentinels) {
                        if (Test-Path -LiteralPath $sentinelDirectory) { Remove-Item -LiteralPath $sentinelDirectory -Recurse -Force }
                    }
                }
                New-Item -ItemType Directory -Force -Path (Join-Path $shadowRoot '.git') | Out-Null
                foreach ($sentinel in @('AGENTS.md', 'settings.gradle.kts', 'build.gradle.kts')) {
                    New-Item -ItemType File -Force -Path (Join-Path $shadowRoot $sentinel) | Out-Null
                }
                Copy-Item -LiteralPath $hookScript -Destination (Join-Path $shadowRoot '.codex\hooks\source_edit_triage.ps1') -Force
                $ambiguityResult = Invoke-ConfiguredHookCommand -Command ([string]$handler.commandWindows) -WorkingDirectory $shadowSessionDirectory -InputJson $fixturePositive
                Test-Contract 'configured command rejects ambiguous sentinel roots with exit two' ($ambiguityResult.ExitCode -eq 2) $ambiguityResult.ExitCode
                Test-Contract 'configured command emits static ambiguous reason' ($ambiguityResult.Stderr.Trim() -ceq 'source-edit-preflight-root-ambiguous')
                Test-Contract 'configured command emits no ambiguous stdout or raw prompt' ([string]::IsNullOrEmpty($ambiguityResult.Stdout) -and -not (($ambiguityResult.Stdout + $ambiguityResult.Stderr).Contains('marker-do-not-reflect')))

                New-SentinelHookFixtureRoot -FixtureRoot $depthFixtureRoot -ClassifierPath $hookScript
                $depthSessionDirectory = $depthFixtureRoot
                foreach ($index in 1..33) {
                    $depthSessionDirectory = Join-Path $depthSessionDirectory "d$index"
                }
                New-Item -ItemType Directory -Force -Path $depthSessionDirectory | Out-Null
                $depthResult = Invoke-ConfiguredHookCommand -Command ([string]$handler.commandWindows) -WorkingDirectory $depthSessionDirectory -InputJson $fixturePositive
                Test-Contract 'configured command rejects depth exhaustion with exit two' ($depthResult.ExitCode -eq 2) $depthResult.ExitCode
                Test-Contract 'configured command emits static unresolved reason' ($depthResult.Stderr.Trim() -ceq 'source-edit-preflight-root-unresolved')
                Test-Contract 'configured command emits no unresolved stdout or raw prompt' ([string]::IsNullOrEmpty($depthResult.Stdout) -and -not (($depthResult.Stdout + $depthResult.Stderr).Contains('marker-do-not-reflect')))
            } finally {
                if ($fixtureCleanupSafe -and (Test-Path -LiteralPath $fixtureRoot)) { Remove-Item -LiteralPath $fixtureRoot -Recurse -Force }
                if ($depthFixtureCleanupSafe -and (Test-Path -LiteralPath $depthFixtureRoot)) { Remove-Item -LiteralPath $depthFixtureRoot -Recurse -Force }
            }
        }
    }
    Test-Contract 'UserPromptSubmit has no matcher' (-not ($promptGroups[0].PSObject.Properties.Name -contains 'matcher'))
}

if (Test-Path -LiteralPath $agentsFile) {
    $agentsText = Get-Content -LiteralPath $agentsFile -Raw -Encoding UTF8
    $routingLines = @($agentsText -split "\r?\n" | Where-Object {
        $_ -match '\$demo1-source-edit-three-way-preflight' -and
        $_ -match 'application-source mutation'
    })
    Test-Contract 'AGENTS contains one preflight routing rule' ($routingLines.Count -eq 1) $routingLines.Count
}

$testSourceText = [IO.File]::ReadAllText($PSCommandPath, (New-Object Text.UTF8Encoding($false, $true)))
$nonAsciiTestSourceCount = @($testSourceText.ToCharArray() | Where-Object { [int]$_ -gt 127 }).Count
Test-Contract 'contract test source contains no mojibake or literal replacement characters' ($nonAsciiTestSourceCount -eq 0) $nonAsciiTestSourceCount

Write-Host "passed=$script:Pass failed=$script:Fail"
if ($script:Fail -gt 0) { exit 1 }
exit 0
