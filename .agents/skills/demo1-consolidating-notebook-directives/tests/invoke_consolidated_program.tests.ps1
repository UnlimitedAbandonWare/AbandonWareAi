[CmdletBinding()]
param([switch]$CandidateOnly)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:assertions = 0
$script:failures = New-Object 'Collections.Generic.List[string]'
$script:tempRoots = New-Object 'Collections.Generic.List[string]'
$script:testPath = [IO.Path]::GetFullPath($MyInvocation.MyCommand.Path)
$script:skillRoot = Split-Path (Split-Path $script:testPath -Parent) -Parent
$script:controller = Join-Path $script:skillRoot 'scripts\invoke_consolidated_program.ps1'
$script:productionControllerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $script:controller).Hash
$script:core = Join-Path $script:skillRoot 'scripts\consolidated_program_core.psm1'
$script:productionCoreHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $script:core).Hash
$script:genericController = $null
$script:repoRoot = 'C:\AbandonWare\demo-1\demo-1\src'
$script:canonicalProgram = Join-Path $script:repoRoot 'agent-prompts\awx_desktop_notebook_consolidated_source_directive_20260806.md'
$script:canonicalState = Join-Path $script:repoRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\program-state.json'
$script:canonicalEvents = Join-Path (Split-Path $script:canonicalState -Parent) 'events.jsonl'
$script:canonicalReleases = Join-Path (Split-Path $script:canonicalState -Parent) 'releases'
$script:canonicalStateLock = $script:canonicalState + '.cas.lock'
$script:canonicalEventLock = $script:canonicalEvents + '.lock'
$script:utf8 = New-Object Text.UTF8Encoding($false, $true)
$script:pwsh = (Get-Command powershell.exe -ErrorAction Stop).Source

function Assert-True {
    param([bool]$Condition, [string]$Message)
    $script:assertions++
    if (-not $Condition) { $script:failures.Add($Message) }
}

function Write-Utf8 {
    param([string]$Path, [string]$Text)
    $parent = Split-Path $Path -Parent
    if (-not (Test-Path -LiteralPath $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    [IO.File]::WriteAllText($Path, $Text, $script:utf8)
}

function Get-Hash { param([string]$Path) (Get-FileHash -Algorithm SHA256 -LiteralPath $Path).Hash }

function New-GenericControllerCopy {
    param([string]$FixtureRoot)

    $copyRoot = Join-Path $FixtureRoot '.controller-copy'
    New-Item -ItemType Directory -Path $copyRoot -Force | Out-Null
    $destination = Join-Path $copyRoot 'invoke_consolidated_program.ps1'
    $text = [IO.File]::ReadAllText($script:controller, $script:utf8)
    $rootLine = '$CanonicalRoot = ''C:\AbandonWare\demo-1\demo-1\src'''
    if ([regex]::Matches($text, '(?m)^' + [regex]::Escape($rootLine) + '\r?$').Count -ne 1) { throw 'unexpected-canonical-root-shape' }
    $escapedFixtureRoot = $FixtureRoot.Replace("'", "''")
    $text = $text.Replace($rootLine, "`$CanonicalRoot = '$escapedFixtureRoot'")
    $gateLine = '    Assert-CanonicalManifest $manifest $programFile.text.Substring(0, $beginMatches[0].Index)'
    if ([regex]::Matches($text, '(?m)^' + [regex]::Escape($gateLine) + '\r?$').Count -ne 1) { throw 'unexpected-canonical-gate-shape' }
    $conditionalGate = "    if (Test-HasProperty `$manifest 'programId') {`r`n        Assert-CanonicalManifest `$manifest `$programFile.text.Substring(0, `$beginMatches[0].Index)`r`n    }"
    $copyText = $text.Replace($gateLine, $conditionalGate)
    $conditionalLine = '    if (Test-HasProperty $manifest ''programId'') {'
    $nestedGateLine = '        Assert-CanonicalManifest $manifest $programFile.text.Substring(0, $beginMatches[0].Index)'
    if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($gateLine) + '\r?$').Count -ne 0 -or
        [regex]::Matches($copyText, '(?m)^' + [regex]::Escape($conditionalLine) + '\r?$').Count -ne 1 -or
        [regex]::Matches($copyText, '(?m)^' + [regex]::Escape($nestedGateLine) + '\r?$').Count -ne 1) { throw 'unexpected-canonical-gate-shape' }
    Write-Utf8 $destination $copyText
    $coreDestination = Join-Path $copyRoot 'consolidated_program_core.psm1'
    Copy-Item -LiteralPath $script:core -Destination $coreDestination
    if ((Get-Hash $coreDestination) -cne $script:productionCoreHash) { throw 'temp-core-copy-hash-mismatch' }
    return $destination
}

function Invoke-Controller {
    param([string[]]$Arguments, [switch]$Production)
    $controllerPath = if ($Production) { $script:controller } else { $script:genericController }
    $old = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { $lines = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $controllerPath @Arguments 2>&1); $exit = $LASTEXITCODE }
    finally { $ErrorActionPreference = $old }
    [pscustomobject]@{ ExitCode = $exit; Text = ($lines -join "`n") }
}

function Read-Result {
    param($Invocation)
    try { return ($Invocation.Text | ConvertFrom-Json -ErrorAction Stop) }
    catch { return [pscustomobject]@{ status = 'parse-error'; reason = $Invocation.Text } }
}

function Read-CanonicalManifest {
    $text = [IO.File]::ReadAllText($script:canonicalProgram, $script:utf8)
    $pattern = '(?ms)^<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-BEGIN -->\r?\n```json\r?\n(?<json>.*?)\r?\n```\r?\n<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-END -->\r?$'
    $match = [regex]::Match($text, $pattern)
    if (-not $match.Success) { throw 'canonical manifest block missing' }
    return [pscustomobject]@{ Text = $text; Manifest = ($match.Groups['json'].Value | ConvertFrom-Json -ErrorAction Stop) }
}

function New-Fixture {
    param([scriptblock]$MutateManifest, [scriptblock]$MutateState)
    $root = Join-Path ([IO.Path]::GetTempPath()) ('awx-controller-readonly-' + [guid]::NewGuid().ToString('N'))
    $resolvedTemp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    if (-not ([IO.Path]::GetFullPath($root).StartsWith($resolvedTemp + '\', [StringComparison]::OrdinalIgnoreCase))) { throw 'unsafe-temp-root' }
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    $script:tempRoots.Add($root)
    $script:genericController = New-GenericControllerCopy $root
    & git -C $root init --quiet 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'fixture-git-init-failed' }

    $candidatePath = 'data/agent-handoff/notebook/input.md'
    $candidateFull = Join-Path $root ($candidatePath.Replace('/', '\'))
    Write-Utf8 $candidateFull 'fixture directive'
    $candidateHash = Get-Hash $candidateFull
    $candidateBytes = [IO.File]::ReadAllBytes($candidateFull).Length
    $programRelative = 'agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md'
    $program = Join-Path $root ($programRelative.Replace('/', '\'))
    $state = Join-Path $root 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\program-state.json'

    $inventory = [ordered]@{
        schemaVersion = 'demo1.notebook-directive-inventory.v1'
        canonicalExecutionRoot = $root
        candidateRoots = @('data/agent-handoff/notebook','__patch_drop__/notebook','agent-prompts')
        candidates = @([ordered]@{
            path = $candidatePath; sha256 = $candidateHash; bytes = [int64]$candidateBytes; gitTracking = 'untracked'
            provenance = 'notebook'; format = 'markdown'; directiveIds = @(); targetFiles = @(); inclusionReason = 'standalone-notebook-directive'
        })
        excluded = @([ordered]@{ path = $programRelative; reason = 'reusable-prompt' })
    }
    $units = @(
        [ordered]@{ workUnitId='WU-001'; status='green'; dependencies=@(); required=$true; kind='verification'; targetFiles=@(); retirementCoverage=@($candidatePath) },
        [ordered]@{ workUnitId='WU-002'; status='pending'; dependencies=@('WU-001'); required=$true; kind='test'; targetFiles=@(); retirementCoverage=@() },
        [ordered]@{ workUnitId='WU-HOLD'; status='hold'; dependencies=@(); required=$true; kind='safety'; targetFiles=@(); retirementCoverage=@() },
        [ordered]@{ workUnitId='WU-FINAL'; status='pending'; dependencies=@('WU-002'); required=$true; kind='retirement'; targetFiles=@(); retirementCoverage=@('all-listed-originals') }
    )
    $retirement = [ordered]@{
        schemaVersion = 'demo1.notebook-directive-retirement.v1'
        deleteAuthorized = $false
        canonicalDirectivePath = 'agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md'
        canonicalDirectiveSha256Evidence = 'external-final-output'
        allRequiredWorkUnitsGreen = $false
        desktopFinalProof = 'evidence_needed'
        status = 'hold'
        items = @([ordered]@{
            path=$candidatePath; sha256=$candidateHash; bytes=[int64]$candidateBytes; gitTracking='untracked'; coverage=@('WU-001')
            eligibility='hold'; holdReason='desktop-work-unit-proof-pending'; deletionResult='not_run'; status='hold'
        })
    }
    $manifest = [ordered]@{ schemaVersion='awx.notebook.directive.controller-manifest.v1'; directiveInventory=$inventory; workUnits=$units; retirement=$retirement }
    if ($null -ne $MutateManifest) { & $MutateManifest $manifest }
    $json = $manifest | ConvertTo-Json -Depth 32
    $programText = 'fixture' + "`n" + '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-BEGIN -->' + "`n" + '```json' + "`n" + $json + "`n" + '```' + "`n" + '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-END -->'
    Write-Utf8 $program $programText
    $stateObject = [ordered]@{
        schemaVersion='awx.notebook.directive.program.v2'; canonicalMarkdownPath=$programRelative; canonicalMarkdownSha256=(Get-Hash $program)
        sourceOwner='desktop'; activeSourceSets=@('main/java','main/resources','app/src/main/java_clean','app/src/main/resources')
        controllerManifest=$manifest; directiveInventory=$manifest.directiveInventory; workUnits=$manifest.workUnits; retirement=$manifest.retirement
        desktopFinalProof='evidence_needed'; runtimeLineageVerdict='HOLD'; deleteAuthorized=$false
        eventCheckpoint=[ordered]@{
            schemaVersion='awx.notebook.directive.event.v2'; sequence=[int64]1; previousEventSha256=('0'*64)
            eventId=('A'*64); eventSha256=('B'*64); programId='awx_desktop_notebook_consolidated_source_directive_20260806'
            canonicalMarkdownSha256=(Get-Hash $program); action='Snapshot'; workUnitId=$null; runIdSha256=$null
            result='created'; stateProjectionSha256=('C'*64); evidenceSha256=$null; recordSha256=$null
        }
    }
    if ($null -ne $MutateState) { & $MutateState $stateObject }
    $snapshot = Invoke-Controller @('-Action','Snapshot','-Root',$root,'-ProgramPath',$program,'-StatePath',$state)
    if ($snapshot.ExitCode -ne 0 -and $null -eq $MutateManifest -and $null -eq $MutateState) {
        throw ('valid-fixture-snapshot-failed-' + [string](Read-Result $snapshot).reason)
    }
    if ($snapshot.ExitCode -ne 0) {
        Write-Utf8 $state ($stateObject | ConvertTo-Json -Depth 32)
    } elseif ($null -ne $MutateState) {
        $snapshotState = [IO.File]::ReadAllText($state, $script:utf8) | ConvertFrom-Json -ErrorAction Stop
        & $MutateState $snapshotState
        Write-Utf8 $state ($snapshotState | ConvertTo-Json -Depth 32)
    }
    return [pscustomobject]@{
        Root=$root; Program=$program; State=$state; Candidate=$candidateFull
        Events=(Join-Path (Split-Path $state -Parent) 'events.jsonl')
        EventLock=(Join-Path (Split-Path $state -Parent) 'events.jsonl.lock')
        Releases=(Join-Path (Split-Path $state -Parent) 'releases')
    }
}

function Invoke-FixtureAction {
    param($Fixture, [string]$Action)
    Invoke-Controller @('-Action',$Action,'-Root',$Fixture.Root,'-ProgramPath',$Fixture.Program,'-StatePath',$Fixture.State)
}

function Assert-Rejected {
    param([scriptblock]$Mutation, [string]$ExpectedReason, [string]$Label)
    $fixture = New-Fixture $Mutation $null
    $result = Invoke-FixtureAction $fixture 'Status'
    $value = Read-Result $result
    Assert-True ($result.ExitCode -ne 0) "$Label is rejected"
    Assert-True ($value.reason -ceq $ExpectedReason) "$Label reason is $ExpectedReason (observed $($value.reason))"
}

function Get-PathSnapshot {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return 'absent' }
    if (Test-Path -LiteralPath $Path -PathType Leaf) { return 'leaf:' + (Get-Hash $Path) }
    $rows = @(Get-ChildItem -LiteralPath $Path -File -Recurse -Force | Sort-Object FullName | ForEach-Object { $_.FullName.Substring($Path.Length).Replace('\','/') + ':' + (Get-Hash $_.FullName) })
    return 'dir:' + ($rows -join '|')
}

try {
    # Baseline read-only Status and Next behavior.
    $fixture = New-Fixture
    $stateBefore = Get-Hash $fixture.State
    $eventsBefore = Get-Hash $fixture.Events
    $eventLockBefore = Get-Hash $fixture.EventLock
    $status = Invoke-FixtureAction $fixture 'Status'; $statusValue = Read-Result $status
    if ($status.ExitCode -ne 0) { Write-Output ("BASELINE DIAGNOSTIC: " + $status.Text) }
    Assert-True ($status.ExitCode -eq 0) 'Status accepts a valid full retirement envelope'
    Assert-True ($statusValue.reason -ceq 'validated-state') 'Status reports validated-state'
    Assert-True ((Get-Hash $fixture.State) -ceq $stateBefore) 'Status leaves state byte-identical'
    Assert-True ((Get-Hash $fixture.Events) -ceq $eventsBefore) 'Status leaves the v2 ledger byte-identical'
    Assert-True ((Get-Hash $fixture.EventLock) -ceq $eventLockBefore) 'Status leaves the event lock byte-identical'
    Assert-True (-not (Test-Path -LiteralPath $fixture.Releases)) 'Status creates no release root'

    $next = Invoke-FixtureAction $fixture 'Next'; $nextValue = Read-Result $next
    Assert-True ($next.ExitCode -eq 0) 'Next accepts valid state'
    Assert-True ($nextValue.workUnitId -ceq 'WU-002') 'Next preserves manifest order and satisfied dependencies'
    Assert-True ($nextValue.reason -ceq 'runnable-work-unit') 'Next reports a runnable unit'
    Assert-True ((Get-Hash $fixture.State) -ceq $stateBefore) 'Next leaves state byte-identical'
    Assert-True ((Get-Hash $fixture.Events) -ceq $eventsBefore) 'Next leaves the v2 ledger byte-identical'
    Assert-True ((Get-Hash $fixture.EventLock) -ceq $eventLockBefore) 'Next leaves the event lock byte-identical'

    # Known lightweight Notebook handoff packets are not controller directives.
    $handoff = New-Fixture
    $handoffStateBefore = Get-Hash $handoff.State
    $handoffEventsBefore = Get-Hash $handoff.Events
    $handoffLockBefore = Get-Hash $handoff.EventLock
    $handoffJson = [ordered]@{
        from='notebook'; to='desktop'; currentTask='Repeat the frozen Desktop verification'
        instructions=@('Run the named verification'); dataPaths=@('Y:\\data\\agent-handoff\\notebook'); notes=@('No source mutation')
    } | ConvertTo-Json -Depth 8
    foreach ($name in @('g0-desktop-proof-20260811.json','g0-desktop-verification-20260811.json')) {
        Write-Utf8 (Join-Path $handoff.Root ('data\agent-handoff\notebook\' + $name)) $handoffJson
    }
    $handoffStatus = Invoke-FixtureAction $handoff 'Status'; $handoffStatusValue = Read-Result $handoffStatus
    Assert-True ($handoffStatus.ExitCode -eq 0) "Status rejects known non-directive handoff JSON: $($handoffStatusValue.reason)"
    Assert-True ($handoffStatusValue.reason -ceq 'validated-state') 'known handoff JSON changes Status classification'
    $handoffNext = Invoke-FixtureAction $handoff 'Next'; $handoffNextValue = Read-Result $handoffNext
    Assert-True ($handoffNext.ExitCode -eq 0 -and $handoffNextValue.workUnitId -ceq 'WU-002') 'known handoff JSON changes deterministic Next ordering'
    Assert-True ((Get-Hash $handoff.State) -ceq $handoffStateBefore) 'handoff classification changes state bytes'
    Assert-True ((Get-Hash $handoff.Events) -ceq $handoffEventsBefore) 'handoff classification changes event bytes'
    Assert-True ((Get-Hash $handoff.EventLock) -ceq $handoffLockBefore) 'handoff classification changes event-lock bytes'

    $malformedJson = New-Fixture
    Write-Utf8 (Join-Path $malformedJson.Root 'data\agent-handoff\notebook\malformed-directive.json') '{'
    $malformedStatus = Invoke-FixtureAction $malformedJson 'Status'; $malformedStatusValue = Read-Result $malformedStatus
    Assert-True ($malformedStatus.ExitCode -ne 0 -and $malformedStatusValue.reason -ceq 'malformed-candidate-json') 'malformed directive-like JSON does not fail closed'

    $canonicalPacket = New-Fixture
    Write-Utf8 (Join-Path $canonicalPacket.Root 'data\agent-handoff\notebook\canonical-directive.json') '{"schemaVersion":"1.0","processMode":"single-agent-logical-roles","packets":[]}'
    $canonicalPacketStatus = Invoke-FixtureAction $canonicalPacket 'Status'; $canonicalPacketValue = Read-Result $canonicalPacketStatus
    Assert-True ($canonicalPacketStatus.ExitCode -ne 0 -and $canonicalPacketValue.reason -ceq 'candidate-inventory-mismatch') 'well-formed directive packet is incorrectly dropped as a non-directive artifact'
    if ($CandidateOnly) {
        if ($script:failures.Count -gt 0) {
            foreach ($failure in @($script:failures)) { Write-Output ("FAILURE: " + $failure) }
            Write-Output ([ordered]@{ status='RED'; firstFailure=$script:failures[0]; failureCount=$script:failures.Count; assertionCount=$script:assertions } | ConvertTo-Json -Compress)
            exit 1
        }
        Write-Output ([ordered]@{ status='PASS'; assertionCount=$script:assertions } | ConvertTo-Json -Compress)
        exit 0
    }

    $none = New-Fixture { param($m) $m.workUnits[1].status='hold' }
    $noneResult = Invoke-FixtureAction $none 'Next'; $noneValue = Read-Result $noneResult
    Assert-True ($noneResult.ExitCode -eq 0) 'Next accepts HOLD-blocked state'
    Assert-True ($noneValue.reason -ceq 'no-runnable-work-unit' -and $null -eq $noneValue.workUnitId) 'Next returns no runnable unit when pending dependencies are blocked'

    # Strict JSON, inventory equality, candidate drift, and exclusion validation.
    $duplicateState = New-Fixture
    $rawState = [IO.File]::ReadAllText($duplicateState.State, $script:utf8)
    Write-Utf8 $duplicateState.State ($rawState -replace '^\{', '{"schemaVersion":"duplicate",')
    $r = Invoke-FixtureAction $duplicateState 'Status'; Assert-True ($r.ExitCode -ne 0 -and (Read-Result $r).reason -ceq 'duplicate-json-key') 'duplicate state JSON key fails closed'

    $duplicateProgram = New-Fixture
    $rawProgram = [IO.File]::ReadAllText($duplicateProgram.Program, $script:utf8)
    Write-Utf8 $duplicateProgram.Program ($rawProgram -replace '(?m)^\{\s*$', '{"schemaVersion":"duplicate",')
    $duplicateProgramState = Get-Content -Raw -LiteralPath $duplicateProgram.State | ConvertFrom-Json
    $duplicateProgramState.canonicalMarkdownSha256 = Get-Hash $duplicateProgram.Program
    Write-Utf8 $duplicateProgram.State ($duplicateProgramState | ConvertTo-Json -Depth 32)
    $r = Invoke-FixtureAction $duplicateProgram 'Status'; Assert-True ($r.ExitCode -ne 0 -and (Read-Result $r).reason -ceq 'duplicate-json-key') 'duplicate manifest JSON key fails closed'

    Assert-Rejected { param($m) $m.directiveInventory.candidates[0].sha256=('A'*64) } 'candidate-inventory-mismatch' 'candidate hash drift'
    Assert-Rejected { param($m) $m.directiveInventory.candidates[0].bytes++ } 'candidate-inventory-mismatch' 'candidate byte drift'
    Assert-Rejected { param($m) $m.directiveInventory.excluded[0].reason='unsupported' } 'candidate-inventory-mismatch' 'exclusion drift'
    Assert-Rejected { param($m) $m.directiveInventory.candidates='scalar' } 'invalid-candidates' 'scalar candidates'
    Assert-Rejected { param($m) $m.directiveInventory.excluded='scalar' } 'invalid-exclusions' 'scalar exclusions'

    # Work-unit graph and status validation.
    Assert-Rejected { param($m) $m.workUnits[1].workUnitId='WU-001' } 'duplicate-work-unit-id' 'duplicate WU id'
    Assert-Rejected { param($m) $m.workUnits[1].dependencies=@('WU-002') } 'self-dependency-id' 'self dependency'
    Assert-Rejected { param($m) $m.workUnits[1].dependencies=@('WU-MISSING') } 'unknown-dependency-id' 'unknown dependency'
    Assert-Rejected { param($m) $m.workUnits[0].status='pending'; $m.workUnits[0].dependencies=@('WU-002') } 'dependency-cycle' 'dependency cycle'
    Assert-Rejected { param($m) $m.workUnits[1].dependencies=@('WU-001','WU-001') } 'duplicate-dependency-id' 'duplicate dependency'
    Assert-Rejected { param($m) $m.workUnits[1].status='done' } 'invalid-work-unit-status' 'done work-unit status'
    Assert-Rejected { param($m) $m.workUnits[1].status='success' } 'invalid-work-unit-status' 'success work-unit status'

    # Full retirement envelope and item validation.
    $retirementCases = @(
        @{ label='missing envelope field'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.Remove('status')} },
        @{ label='extra envelope field'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.extra='x'} },
        @{ label='scalar retirement'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement='scalar'} },
        @{ label='wrong retirement schema'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.schemaVersion='v0'} },
        @{ label='deleteAuthorized scalar'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.deleteAuthorized='false'} },
        @{ label='allRequiredWorkUnitsGreen scalar'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.allRequiredWorkUnitsGreen='false'} },
        @{ label='wrong canonical directive path'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.canonicalDirectivePath='agent-prompts/other.md'} },
        @{ label='wrong canonical evidence'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.canonicalDirectiveSha256Evidence='embedded'} },
        @{ label='invalid desktop proof'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.desktopFinalProof='done'} },
        @{ label='done retirement status'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.status='done'} },
        @{ label='success retirement status'; reason='invalid-retirement-schema'; mutate={param($m)$m.retirement.status='success'} },
        @{ label='scalar retirement items'; reason='invalid-retirement-items'; mutate={param($m)$m.retirement.items='scalar'} },
        @{ label='missing item field'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].Remove('bytes')} },
        @{ label='extra item field'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].extra='x'} },
        @{ label='item bytes boolean'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].bytes=$true} },
        @{ label='item bytes negative'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].bytes=-1} },
        @{ label='item hash lowercase'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].sha256=$m.retirement.items[0].sha256.ToLowerInvariant()} },
        @{ label='item tracking unknown'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].gitTracking='unknown'} },
        @{ label='scalar item coverage'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].coverage='WU-001'} },
        @{ label='empty item coverage'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].coverage=@()} },
        @{ label='duplicate item coverage'; reason='invalid-retirement-coverage'; mutate={param($m)$m.retirement.items[0].coverage=@('WU-001','WU-001')} },
        @{ label='unknown coverage WU'; reason='unknown-retirement-coverage'; mutate={param($m)$m.retirement.items[0].coverage=@('WU-MISSING')} },
        @{ label='invalid item eligibility'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].eligibility='ready'} },
        @{ label='invalid deletion result'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].deletionResult='done'} },
        @{ label='done item status'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].status='done'} },
        @{ label='success item status'; reason='invalid-retirement-item'; mutate={param($m)$m.retirement.items[0].status='success'} },
        @{ label='duplicate item path'; reason='duplicate-retirement-path'; mutate={param($m)$m.retirement.items=@($m.retirement.items[0],$m.retirement.items[0])} },
        @{ label='candidate item hash mismatch'; reason='retirement-candidate-mismatch'; mutate={param($m)$m.retirement.items[0].sha256=('B'*64)} },
        @{ label='candidate item bytes mismatch'; reason='retirement-candidate-mismatch'; mutate={param($m)$m.retirement.items[0].bytes++} },
        @{ label='candidate item tracking mismatch'; reason='retirement-candidate-mismatch'; mutate={param($m)$m.retirement.items[0].gitTracking='tracked'} },
        @{ label='item absent from WU coverage'; reason='retirement-coverage-mismatch'; mutate={param($m)$m.workUnits[0].retirementCoverage=@()} },
        @{ label='WU path absent from items'; reason='retirement-coverage-mismatch'; mutate={param($m)$m.workUnits[1].retirementCoverage=@('data/agent-handoff/notebook/other.md')} }
    )
    foreach ($case in $retirementCases) { Assert-Rejected $case.mutate $case.reason $case.label }

    # Read-only outside-state and reparse rejection.
    $outside = New-Fixture
    $outsideState = Join-Path ([IO.Path]::GetTempPath()) ('outside-state-' + [guid]::NewGuid().ToString('N') + '.json')
    try {
        Copy-Item -LiteralPath $outside.State -Destination $outsideState
        $r = Invoke-Controller @('-Action','Status','-Root',$outside.Root,'-ProgramPath',$outside.Program,'-StatePath',$outsideState)
        Assert-True ($r.ExitCode -ne 0 -and (Read-Result $r).reason -ceq 'state-path-outside-root') 'outside state path is rejected read-only'
    } finally { if (Test-Path -LiteralPath $outsideState) { Remove-Item -LiteralPath $outsideState -Force } }

    $junctionFixture = New-Fixture
    $junctionTarget = Join-Path $junctionFixture.Root 'junction-target'
    New-Item -ItemType Directory -Path $junctionTarget | Out-Null
    Copy-Item -LiteralPath $junctionFixture.State -Destination (Join-Path $junctionTarget 'state.json')
    $junction = Join-Path $junctionFixture.Root 'junction-state'
    $junctionSupported = $true
    try { New-Item -ItemType Junction -Path $junction -Target $junctionTarget -ErrorAction Stop | Out-Null }
    catch { $junctionSupported = $false }
    if ($junctionSupported) {
        $r = Invoke-Controller @('-Action','Status','-Root',$junctionFixture.Root,'-ProgramPath',$junctionFixture.Program,'-StatePath',(Join-Path $junction 'state.json'))
        Assert-True ($r.ExitCode -ne 0 -and (Read-Result $r).reason -ceq 'reparse-traversal') 'in-root junction traversal is rejected read-only'
    }

    $omittedProgramId = New-Fixture
    $omittedProgramIdBefore = Get-Hash $omittedProgramId.State
    $omittedProgramIdEventsBefore = Get-PathSnapshot $omittedProgramId.Events
    $omittedProgramIdLockBefore = Get-PathSnapshot $omittedProgramId.EventLock
    $omittedProgramIdReleasesBefore = Get-PathSnapshot $omittedProgramId.Releases
    $omittedProgramIdResult = Invoke-Controller -Production -Arguments @('-Action','Status','-Root',$omittedProgramId.Root,'-ProgramPath',$omittedProgramId.Program,'-StatePath',$omittedProgramId.State)
    $omittedProgramIdValue = Read-Result $omittedProgramIdResult
    Assert-True ($omittedProgramIdResult.ExitCode -ne 0) 'production controller rejects a manifest with omitted programId'
    Assert-True ($omittedProgramIdValue.reason -ceq 'canonical-manifest-schema') 'production programId omission returns canonical-manifest-schema'
    Assert-True ((Get-Hash $omittedProgramId.State) -ceq $omittedProgramIdBefore) 'production programId rejection leaves state byte-identical'
    Assert-True ((Get-PathSnapshot $omittedProgramId.Events) -ceq $omittedProgramIdEventsBefore -and
        (Get-PathSnapshot $omittedProgramId.EventLock) -ceq $omittedProgramIdLockBefore -and
        (Get-PathSnapshot $omittedProgramId.Releases) -ceq $omittedProgramIdReleasesBefore) 'production programId rejection changes lifecycle artifacts'

    # Canonical structure and C-root Snapshot -WhatIf with no lifecycle writes.
    $canonical = Read-CanonicalManifest
    $manifest = $canonical.Manifest
    Assert-True (@($manifest.workUnits).Count -eq 14) 'canonical graph has 14 authoritative work units'
    Assert-True (@($manifest.directiveInventory.candidates).Count -eq 10) 'canonical inventory has 10 candidates'
    Assert-True (@($manifest.directiveInventory.excluded).Count -eq 34) 'canonical inventory has 34 exclusions'
    Assert-True (@($manifest.retirement.items).Count -eq 10) 'canonical retirement has 10 items'
    Assert-True (@($manifest.retirement.items | Where-Object eligibility -ceq 'hold').Count -eq 10) 'all canonical retirement items are held'
    Assert-True (@($manifest.retirement.items | Where-Object eligibility -ceq 'eligible').Count -eq 0) 'no canonical retirement item is eligible'
    Assert-True (@($manifest.retirement.items | Where-Object { $null -ne $_.PSObject.Properties['deletionResult'] -and $_.deletionResult -ceq 'not_run' }).Count -eq 10) 'all canonical deletion results are not_run'
    Assert-True ($canonical.Text -match 'eight-unit-projection-reference-only') 'eight-unit projection is reference-only'
    Assert-True ($canonical.Text -match 'authoritativeControllerWorkUnitCount:\s*14') 'fourteen-unit projection is authoritative'

    $canonicalStateDirectory = Split-Path $script:canonicalState -Parent
    $canonicalLifecyclePaths = @(
        $script:canonicalState, $script:canonicalStateLock, $script:canonicalEvents, $script:canonicalEventLock,
        $script:canonicalReleases, (Join-Path $canonicalStateDirectory 'archive'),
        (Join-Path $canonicalStateDirectory 'release-manifest.json'), (Join-Path $canonicalStateDirectory 'retirement-manifest.json'),
        (Join-Path $canonicalStateDirectory 'CHANGELOG.md'), (Join-Path (Split-Path $canonicalStateDirectory -Parent) 'releases'),
        (Join-Path (Split-Path $canonicalStateDirectory -Parent) 'archive')
    )
    $canonicalLifecycleBefore = @{}
    foreach ($livePath in $canonicalLifecyclePaths) {
        $canonicalLifecycleBefore[$livePath] = Get-PathSnapshot $livePath
    }
    $canonicalDebrisBefore = @(Get-ChildItem -LiteralPath $canonicalStateDirectory -Force -File -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -like '.state-*.tmp' -or $_.Name -like '.publish-*.tmp' -or $_.Name -like '*.cas-backup-*'
    })
    Assert-True ($canonicalDebrisBefore.Count -eq 0) 'canonical lifecycle temp/backup debris is absent before Snapshot WhatIf'
    $snapshot = Invoke-Controller -Production -Arguments @('-Action','Snapshot','-Root',$script:repoRoot,'-WhatIf'); $snapshotValue = Read-Result $snapshot
    Assert-True ($snapshot.ExitCode -eq 0) 'canonical Snapshot -WhatIf succeeds'
    Assert-True ($snapshotValue.reason -ceq 'whatif') 'canonical Snapshot reports whatif'
    Assert-True ([int]$snapshotValue.workUnitCount -eq 14) 'canonical Snapshot reports 14 work units'
    foreach ($livePath in $canonicalLifecyclePaths) {
        Assert-True ((Get-PathSnapshot $livePath) -ceq [string]$canonicalLifecycleBefore[$livePath]) "Snapshot -WhatIf changes canonical lifecycle path: $livePath"
    }
    $canonicalDebrisAfter = @(Get-ChildItem -LiteralPath $canonicalStateDirectory -Force -File -ErrorAction SilentlyContinue | Where-Object {
        $_.Name -like '.state-*.tmp' -or $_.Name -like '.publish-*.tmp' -or $_.Name -like '*.cas-backup-*'
    })
    Assert-True ($canonicalDebrisAfter.Count -eq 0) 'Snapshot -WhatIf leaves canonical lifecycle temp/backup debris absent'
}
finally {
    Assert-True ((Get-Hash $script:controller) -ceq $script:productionControllerHash) 'production controller changed during broad read-only tests'
    Assert-True ((Get-Hash $script:core) -ceq $script:productionCoreHash) 'production core changed during broad read-only tests'
    foreach ($root in @($script:tempRoots)) {
        $full = [IO.Path]::GetFullPath($root)
        $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
        if ($full.StartsWith($temp + '\awx-controller-readonly-', [StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $full)) {
            Remove-Item -LiteralPath $full -Recurse -Force
        }
    }
}

if ($script:failures.Count -gt 0) {
    foreach ($failure in @($script:failures)) { Write-Output ("FAILURE: " + $failure) }
    Write-Output ([ordered]@{ status='RED'; firstFailure=$script:failures[0]; failureCount=$script:failures.Count; assertionCount=$script:assertions } | ConvertTo-Json -Compress)
    exit 1
}
Write-Output ([ordered]@{ status='PASS'; assertionCount=$script:assertions } | ConvertTo-Json -Compress)
