param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).ProviderPath
)

$ErrorActionPreference = 'Stop'
$sut = Join-Path $Root '.agents\skills\demo1-macsrc-guarded-patch-session\scripts\new_guard_session_plan.ps1'
if (-not (Test-Path -LiteralPath $sut -PathType Leaf)) {
    Write-Host '[FAIL] new_guard_session_plan.ps1 is missing'
    exit 1
}

$intake = Join-Path $Root '.agents\skills\demo1-macsrc-defect-intake\scripts\prepare_defect_intent.ps1'
$script:passed = 0
function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Name)
    if ($Actual -ne $Expected) { throw "$Name expected=<$Expected> actual=<$Actual>" }
    $script:passed++
    Write-Host "[PASS] $Name"
}
function Assert-True {
    param([bool]$Condition, [string]$Name)
    if (-not $Condition) { throw $Name }
    $script:passed++
    Write-Host "[PASS] $Name"
}
function Invoke-ExpectedFailure {
    param([scriptblock]$Action, [string]$Pattern, [string]$Name)
    $message = $null
    try { & $Action } catch { $message = $_.Exception.Message }
    Assert-True ($message -match $Pattern) $Name
}
function Write-Utf8Json {
    param([string]$Path, [hashtable]$Value)
    $parent = [IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    [IO.File]::WriteAllText(
        $Path,
        (($Value | ConvertTo-Json -Depth 10) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )
}
function Invoke-Plan {
    param([string]$FixtureRoot, [string]$RedEvidenceRel, [string]$OutputRel)
    & $sut -Root $FixtureRoot `
        -IntentFile 'data/agent-handoff/macsrc-defect-intake/session-red-001/intent.json' `
        -RedEvidenceFile $RedEvidenceRel -OutputPath $OutputRel | Out-Null
    return Get-Content -LiteralPath (Join-Path $FixtureRoot $OutputRel) -Encoding UTF8 -Raw | ConvertFrom-Json
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-session-test-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path (Join-Path $tempRoot 'main\java\example') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $tempRoot 'settings.gradle') -Encoding UTF8 -Value "rootProject.name = 'fixture'"
    Set-Content -LiteralPath (Join-Path $tempRoot 'main\java\example\Foo.java') -Encoding UTF8 -Value 'class Foo {}'
    & $intake -Root $tempRoot -RunId 'session-red-001' `
        -DefectClass 'silent-catch' -EvidenceIds 'E1' `
        -TargetFiles 'main/java/example/Foo.java' -WatchRoots 'main/java/example' `
        -BoundaryEvidenceFiles 'settings.gradle' -BoundaryProofType 'GradleSourceSet' `
        -RedCommand '.\gradlew.bat test --tests FooTest' `
        -ExpectedRedSignal 'expected failure' `
        -GreenCommand '.\gradlew.bat test --tests FooTest' | Out-Null

    $intentRel = 'data/agent-handoff/macsrc-defect-intake/session-red-001/intent.json'
    $intentPath = Join-Path $tempRoot $intentRel
    $intentSha = (Get-FileHash -LiteralPath $intentPath -Algorithm SHA256).Hash.ToLowerInvariant()
    $redRel = 'data/agent-handoff/macsrc-defect-intake/session-red-001/red-evidence.json'
    Write-Utf8Json (Join-Path $tempRoot $redRel) ([ordered]@{
        schemaVersion = 'awx.red-evidence.v1'
        runId = 'session-red-001'
        intentSha256 = $intentSha
        command = '.\gradlew.bat test --tests FooTest'
        exitCode = 1
        expectedSignalMatched = $true
        outputSha256 = ('a' * 64)
    })

    $readyRel = 'data/agent-handoff/macsrc-guarded-patch/session-red-001/plan.json'
    $plan = Invoke-Plan $tempRoot $redRel $readyRel
    Assert-Equal $plan.schemaVersion 'awx.guard-session-plan.v1' 'plan schema is stable'
    Assert-Equal $plan.verdict 'HOLD' 'nonproduction root cannot enter a MacSrc guard'
    Assert-True (@($plan.failureClasses) -contains 'macsrc-root-mismatch') 'temporary root mismatch is classified'
    Assert-Equal $plan.rootIdentity 'NONPRODUCTION' 'root identity is machine readable'
    Assert-Equal $plan.globalSafeDirectoryMutation $false 'planner never changes global Git trust'
    Assert-Equal $plan.mutationAllowed $false 'plan never mutates'
    Assert-Equal $plan.intentSha256 $intentSha 'plan binds intent hash'
    Assert-Equal $plan.nextAction 'RESOLVE_SOURCE_OWNERSHIP' 'root mismatch has one recovery action'
    Assert-Equal $plan.requiredGuardSkill 'demo1-macsrc-smb-direct-patch' 'existing guard stays authoritative'
    Assert-Equal ($plan.phaseSequence -join '>') 'Prepare>Verify>RED_CONFIRMED>APPLY_PATCH>GREEN>Complete|Abort' 'phase sequence is explicit'
    Assert-True (Test-Path -LiteralPath ((Join-Path $tempRoot $readyRel) + '.ready')) 'ready marker exists'

    Invoke-ExpectedFailure {
        Invoke-Plan $tempRoot $redRel 'main/java/example/injected-plan.json' | Out-Null
    } 'output-outside-handoff' 'session plan cannot target an active source directory'
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $tempRoot 'main\java\example\injected-plan.json'))) 'rejected plan output creates no source file'

    Invoke-ExpectedFailure {
        Invoke-Plan $tempRoot $redRel 'data/agent-handoff/macsrc-guarded-patch/session-red-001/ads-plan.json:rogue' | Out-Null
    } 'path-outside-root' 'alternate data stream output is rejected explicitly'

    $fakeGitDir = Join-Path $tempRoot 'fake-git-bin'
    New-Item -ItemType Directory -Path $fakeGitDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $fakeGitDir 'git.cmd') -Encoding ASCII -Value @(
        '@echo off',
        'echo fatal: detected dubious ownership in repository at fixture 1>&2',
        'exit /b 128'
    )
    $oldPath = $env:PATH
    try {
        $env:PATH = $fakeGitDir + [IO.Path]::PathSeparator + $oldPath
        $ownerMismatch = Invoke-Plan $tempRoot $redRel 'data/agent-handoff/macsrc-guarded-patch/session-red-001/owner-mismatch.json'
    } finally {
        $env:PATH = $oldPath
    }
    Assert-Equal $ownerMismatch.verdict 'HOLD' 'dubious ownership cannot override root mismatch'
    Assert-True (@($ownerMismatch.failureClasses) -contains 'macsrc-root-mismatch') 'root identity stays authoritative without global Git mutation'
    Assert-Equal $ownerMismatch.globalSafeDirectoryMutation $false 'dubious ownership does not mutate global Git config'

    $badCommandRel = 'data/agent-handoff/macsrc-defect-intake/session-red-001/red-command-mismatch.json'
    Write-Utf8Json (Join-Path $tempRoot $badCommandRel) ([ordered]@{
        schemaVersion = 'awx.red-evidence.v1'; runId = 'session-red-001'; intentSha256 = $intentSha
        command = 'different command'; exitCode = 1; expectedSignalMatched = $true; outputSha256 = ('b' * 64)
    })
    $badCommand = Invoke-Plan $tempRoot $badCommandRel 'data/agent-handoff/macsrc-guarded-patch/session-red-001/bad-command.json'
    Assert-Equal $badCommand.verdict 'HOLD' 'mismatched RED command holds'
    Assert-True (@($badCommand.failureClasses) -contains 'red-evidence-command-mismatch') 'command mismatch is classified'

    $redPassedRel = 'data/agent-handoff/macsrc-defect-intake/session-red-001/red-passed.json'
    Write-Utf8Json (Join-Path $tempRoot $redPassedRel) ([ordered]@{
        schemaVersion = 'awx.red-evidence.v1'; runId = 'session-red-001'; intentSha256 = $intentSha
        command = '.\gradlew.bat test --tests FooTest'; exitCode = 0; expectedSignalMatched = $false; outputSha256 = ('c' * 64)
    })
    $redPassed = Invoke-Plan $tempRoot $redPassedRel 'data/agent-handoff/macsrc-guarded-patch/session-red-001/red-passed-plan.json'
    Assert-Equal $redPassed.verdict 'HOLD' 'passing RED command holds'
    Assert-True (@($redPassed.failureClasses) -contains 'red-not-reproduced') 'missing RED is classified'

    New-Item -ItemType Directory -Path (Join-Path $tempRoot '__patch_drop__') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $tempRoot '__patch_drop__\pending-v3.patch') -Encoding UTF8 -Value 'diff --git a/main/java/example/Foo.java b/main/java/example/Foo.java'
    $pending = Invoke-Plan $tempRoot $redRel 'data/agent-handoff/macsrc-guarded-patch/session-red-001/pending.json'
    Assert-Equal $pending.verdict 'HOLD' 'pending PatchDrop holds'
    Assert-True (@($pending.failureClasses) -contains 'patch-drop-pending') 'pending queue is classified'
    Set-Content -LiteralPath (Join-Path $tempRoot '__patch_drop__\pending-v3.patch') -Encoding UTF8 -Value 'diff --git a/docs/other.md b/docs/other.md'
    $unrelated = Invoke-Plan $tempRoot $redRel 'data/agent-handoff/macsrc-guarded-patch/session-red-001/disjoint-pending.json'
    Assert-True (@($unrelated.failureClasses) -notcontains 'patch-drop-pending') 'unrelated pending patch does not block planning'
    Remove-Item -LiteralPath (Join-Path $tempRoot '__patch_drop__\pending-v3.patch') -Force

    $leaseDir = Join-Path $tempRoot '__patch_drop__\source-edit-locks\macsrc-other.lock'
    New-Item -ItemType Directory -Path $leaseDir -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $leaseDir 'lease.json') -Encoding UTF8 -Value '{}'
    $leased = Invoke-Plan $tempRoot $redRel 'data/agent-handoff/macsrc-guarded-patch/session-red-001/leased.json'
    Assert-Equal $leased.verdict 'HOLD' 'directory lease holds'
    Assert-True (@($leased.failureClasses) -contains 'source-lease-present') 'directory lease is classified'
    Write-Utf8Json (Join-Path $leaseDir 'lease.json') ([ordered]@{role='notebook';expiresAtUtc=[DateTimeOffset]::UtcNow.AddHours(1).ToString('o');targetPaths=@('main/java/example/Other.java')})
    $disjoint = Invoke-Plan $tempRoot $redRel 'data/agent-handoff/macsrc-guarded-patch/session-red-001/disjoint-lease.json'
    Assert-True (@($disjoint.failureClasses) -notcontains 'source-lease-present') 'disjoint source session does not block planning'
    Remove-Item -LiteralPath $leaseDir -Recurse -Force

    Set-Content -LiteralPath (Join-Path $tempRoot 'main\java\example\Foo.java') -Encoding UTF8 -Value 'class Foo { int changed; }'
    $changed = Invoke-Plan $tempRoot $redRel 'data/agent-handoff/macsrc-guarded-patch/session-red-001/changed.json'
    Assert-Equal $changed.verdict 'HOLD' 'changed preimage holds'
    Assert-True (@($changed.failureClasses) -contains 'guard-preimage-changed') 'preimage drift is classified'

    Write-Host "[SUMMARY] passed=$script:passed failed=0"
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
