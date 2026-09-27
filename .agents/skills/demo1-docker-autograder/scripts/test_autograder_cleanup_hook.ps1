param([string]$Root = 'Y:\')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$script:assertions = 0
function Assert-Equal($Actual, $Expected, [string]$Name) {
    if ($Actual -ne $Expected) { throw "assertion-failed: $Name" }
    $script:assertions++
}
function Write-Json([string]$Path, $Value) {
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($Path)) | Out-Null
    [IO.File]::WriteAllText($Path, ($Value | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
}
function Write-ReadyJson([string]$Path, $Value) {
    Write-Json $Path $Value
    $sha = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($Path + '.sha256', $sha)
    [IO.File]::WriteAllText($Path + '.ready', $sha)
}

$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$tempRoot = Join-Path $tempParent ('awx-autograder-cleanup-test-' + [guid]::NewGuid().ToString('N'))
try {
    $relativeScripts = '.agents/skills/demo1-docker-autograder/scripts'
    $fixtureScripts = Join-Path $tempRoot $relativeScripts
    [IO.Directory]::CreateDirectory($fixtureScripts) | Out-Null
    foreach ($name in @('invoke_docker_autograder.ps1', 'docker_autograder.py')) {
        Copy-Item -LiteralPath (Join-Path (Join-Path $Root $relativeScripts) $name) -Destination (Join-Path $fixtureScripts $name)
    }
    $sut = Join-Path $fixtureScripts 'invoke_docker_autograder.ps1'
    $helperDirectory = Join-Path $tempRoot '.agents/skills/demo1-completed-directive-cleanup/scripts'
    [IO.Directory]::CreateDirectory($helperDirectory) | Out-Null
    # Fixture tests hook behavior only. The production cleanup helper has its own evidence tests.
    [IO.File]::WriteAllText((Join-Path $helperDirectory 'cleanup_completed_directives.ps1'), @'
param([string]$Root, [string]$RequestPath, [string]$LogDirectory, [switch]$Apply)
$ErrorActionPreference = 'Stop'
$request = Get-Content -LiteralPath $RequestPath -Raw | ConvertFrom-Json
[IO.File]::AppendAllText((Join-Path $Root 'helper-invocations.txt'), "called`n")
if (-not $Apply) { throw 'fixture-apply-required' }
if ($request.behavior -eq 'throw') { throw 'fixture-helper-failure' }
if ($request.behavior -eq 'invalid') { '{"unexpected":true}'; exit 0 }
$status = if ($request.behavior -eq 'hold') { 'hold' } else { 'complete' }
$count = if ($status -eq 'complete') { 1 } else { 0 }
[pscustomobject]@{
    schemaVersion = 'awx.completed-directive-cleanup.result.v1'; mode = 'apply'
    status = $status; reason = 'fixture-result'; plannedCount = 1
    deletedCount = $count; alreadyAbsentCount = 0; heldCount = (1 - $count)
} | ConvertTo-Json -Compress
if ($request.behavior -eq 'bad-exit') { exit 3 }
if ($status -eq 'hold') { exit 2 }
exit 0
'@, [Text.UTF8Encoding]::new($false))
    [IO.Directory]::CreateDirectory((Join-Path $tempRoot 'tests')) | Out-Null
    [IO.File]::WriteAllText((Join-Path $tempRoot 'app.py'), 'def answer(): return 42')
    [IO.File]::WriteAllText((Join-Path $tempRoot 'tests/test_app.py'), 'def test_answer(): assert True')
    $fakeDockerPy = Join-Path $tempRoot 'fake_docker.py'
    [IO.File]::WriteAllText($fakeDockerPy, @'
import io, pathlib, sys, tarfile
root = pathlib.Path(__file__).parent
with (root / 'docker-invocations.txt').open('a') as stream:
    stream.write('called\n')
mode = (root / 'docker-mode.txt').read_text()
if mode == 'hold':
    print('malformed archive')
    raise SystemExit(0)
failed = 1 if mode == 'fail' else 0
skipped = 1 if mode == 'skipped' else 0
xml = f'<testsuite tests="2" failures="{failed}" errors="0" skipped="{skipped}"/>'.encode()
payload = io.BytesIO()
with tarfile.open(fileobj=payload, mode='w') as archive:
    member = tarfile.TarInfo('junit.xml')
    member.size = len(xml)
    archive.addfile(member, io.BytesIO(xml))
sys.stdout.buffer.write(payload.getvalue())
raise SystemExit(failed)
'@, [Text.UTF8Encoding]::new($false))
    $fakeDockerCmd = Join-Path $tempRoot 'docker.cmd'
    $python = (Get-Command python -ErrorAction Stop).Source
    [IO.File]::WriteAllText($fakeDockerCmd, "@echo off`r`n`"$python`" `"$fakeDockerPy`" %*`r`n", [Text.Encoding]::ASCII)
    $runId = 'cleanup-hook-contract-001'
    $jobRel = "data/agent-handoff/docker-autograder/$runId/job.json"
    $resultRel = "data/agent-handoff/docker-autograder/$runId/result.json"
    $requestPath = Join-Path $tempRoot 'request.json'
    $logDirectory = Join-Path $tempRoot 'cleanup-logs'
    $job = [ordered]@{
        schemaVersion = 'awx.docker-autograder.job.v1'; runId = $runId
        purpose = 'GREEN_VERIFICATION'; profile = 'pytest'
        imageRef = 'example.invalid/awx-pytest@sha256:' + ('a' * 64)
        includePaths = @('app.py', 'tests/test_app.py'); testSelectors = @('tests/test_app.py')
        declaredTestCommand = 'python -m pytest -q tests/test_app.py'; expectedSignal = 'fixture'
        decisionSha256 = ('b' * 64); intentSpecSha256 = ('c' * 64)
        limits = @{ cpus = 0.5; memoryMb = 256; pids = 64; timeoutSeconds = 5
            maxFiles = 16; maxInputBytes = 1048576; maxLogBytes = 65536
            maxXmlBytes = 1048576; maxTestCases = 1000 }
        networkMode = 'none'; pullPolicy = 'never'; mutationAllowed = $false
    }
    $common = @{ Root = $tempRoot; RunId = $runId; JobFile = $jobRel; OutputPath = $resultRel
        DockerBin = $fakeDockerCmd; ContractTest = $true }
    Write-ReadyJson (Join-Path $tempRoot $jobRel) $job
    Write-Json $requestPath @{ behavior = 'complete' }
    [IO.File]::WriteAllText((Join-Path $tempRoot 'docker-mode.txt'), 'pass')
    foreach ($partial in @(
        @{ CompletedDirectiveRequest = $requestPath }, @{ CleanupLogDirectory = $logDirectory },
        @{ CompletedDirectiveRequest = ''; CleanupLogDirectory = '' },
        @{ CompletedDirectiveRequest = ' '; CleanupLogDirectory = $logDirectory }
    )) {
        $message = ''
        try { & $sut @common @partial | Out-Null } catch { $message = $_.Exception.Message }
        Assert-Equal ($message -match '^cleanup-options-incomplete:') $true 'paired options required before grader'
        Assert-Equal (Test-Path -LiteralPath (Join-Path $tempRoot 'docker-invocations.txt')) $false 'missing option never starts Docker'
    }
    $noOpt = & $sut @common | ConvertFrom-Json
    Assert-Equal $noOpt.status 'COMPLETE' 'unopted grader unchanged'
    Assert-Equal $noOpt.artifactCleanup.status 'not-requested' 'unopted cleanup disabled'
    Assert-Equal (Test-Path -LiteralPath (Join-Path $tempRoot 'helper-invocations.txt')) $false 'unopted helper not invoked'

    $cases = @(
        @{ docker = 'pass'; purpose = 'GREEN_VERIFICATION'; helper = 'complete'; expected = 'complete'; calls = 1 },
        @{ docker = 'fail'; purpose = 'GREEN_VERIFICATION'; helper = 'complete'; expected = 'not-eligible'; calls = 0 },
        @{ docker = 'hold'; purpose = 'GREEN_VERIFICATION'; helper = 'complete'; expected = 'not-eligible'; calls = 0 },
        @{ docker = 'skipped'; purpose = 'GREEN_VERIFICATION'; helper = 'complete'; expected = 'not-eligible'; calls = 0 },
        @{ docker = 'pass'; purpose = 'RED_PROBE'; helper = 'complete'; expected = 'not-eligible'; calls = 0 },
        @{ docker = 'pass'; purpose = 'GREEN_VERIFICATION'; helper = 'hold'; expected = 'held'; calls = 1 },
        @{ docker = 'pass'; purpose = 'GREEN_VERIFICATION'; helper = 'throw'; expected = 'held'; calls = 1 },
        @{ docker = 'pass'; purpose = 'GREEN_VERIFICATION'; helper = 'invalid'; expected = 'held'; calls = 1 },
        @{ docker = 'pass'; purpose = 'GREEN_VERIFICATION'; helper = 'bad-exit'; expected = 'held'; calls = 1 }
    )
    $totalCalls = 0
    $caseIndex = 0
    foreach ($case in $cases) {
        $caseIndex++
        $resultRel = "data/agent-handoff/docker-autograder/$runId/result-$caseIndex.json"
        $common.OutputPath = $resultRel
        $job.purpose = $case.purpose
        Write-ReadyJson (Join-Path $tempRoot $jobRel) $job
        Write-Json $requestPath @{ behavior = $case.helper }
        [IO.File]::WriteAllText((Join-Path $tempRoot 'docker-mode.txt'), $case.docker)
        $machine = & $sut @common -CompletedDirectiveRequest $requestPath -CleanupLogDirectory $logDirectory | ConvertFrom-Json
        $totalCalls += $case.calls
        $observedCalls = @(Get-Content -LiteralPath (Join-Path $tempRoot 'helper-invocations.txt')).Count
        Assert-Equal $observedCalls $totalCalls 'eligible cleanup invoked once without retry'
        Assert-Equal $machine.artifactCleanup.status $case.expected 'cleanup status classified'
        Assert-Equal $machine.mutationAllowed $false 'source mutation remains forbidden'
        $grade = Get-Content -LiteralPath (Join-Path $tempRoot $resultRel) -Raw | ConvertFrom-Json
        Assert-Equal $machine.status $grade.executionStatus 'grade evidence preserved regardless of cleanup'
        Assert-Equal $machine.resultSha256 ((Get-FileHash -LiteralPath (Join-Path $tempRoot $resultRel) -Algorithm SHA256).Hash.ToLowerInvariant()) 'grade result not rewritten'
    }
    # Exercise the real sibling validator and native deletion only against this
    # host-local temporary queue. This proof includes the entire wrapper boundary.
    $actualHelperRelative = '.agents/skills/demo1-completed-directive-cleanup/scripts/cleanup_completed_directives.ps1'
    Copy-Item -LiteralPath (Join-Path $Root $actualHelperRelative) -Destination (Join-Path $tempRoot $actualHelperRelative) -Force
    $directiveRel = 'queue/queued.md'
    $directivePath = Join-Path $tempRoot $directiveRel
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($directivePath)) | Out-Null
    [IO.File]::WriteAllText($directivePath, 'Fixture directive: return 42 after focused verification.', [Text.UTF8Encoding]::new($false))
    $directiveSha = (Get-FileHash -LiteralPath $directivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $sourcePath = Join-Path $tempRoot 'app.py'
    $sourceSha = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()
    $reportRel = 'completion/report.json'
    $reportPath = Join-Path $tempRoot $reportRel
    Write-Json $reportPath @{
        sourcePatchCompletion = 'verified'; desktopFinalProof = 'verified'; directive = $directiveRel
        postimages = @(@{ path = 'app.py'; sha256 = $sourceSha })
    }
    $reportSha = (Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Json $requestPath @{
        schemaVersion = 'awx.completed-directive-cleanup.v1'; deleteAuthorized = $true
        allRequiredWorkComplete = $true; completionFlag = $true
        directive = @{ path = $directiveRel; sha256 = $directiveSha }
        completionReport = @{ path = $reportRel; sha256 = $reportSha }
        postimages = @(@{ path = 'app.py'; sha256 = $sourceSha }); queueRoots = @('queue')
    }
    $job.purpose = 'GREEN_VERIFICATION'
    Write-ReadyJson (Join-Path $tempRoot $jobRel) $job
    [IO.File]::WriteAllText((Join-Path $tempRoot 'docker-mode.txt'), 'pass')
    $resultRel = "data/agent-handoff/docker-autograder/$runId/result-actual-helper.json"
    $common.OutputPath = $resultRel
    $actual = & $sut @common -CompletedDirectiveRequest $requestPath -CleanupLogDirectory $logDirectory | ConvertFrom-Json
    Assert-Equal $actual.status 'COMPLETE' 'actual helper preserves GREEN completion'
    Assert-Equal $actual.artifactCleanup.status 'complete' 'actual helper completion propagated'
    Assert-Equal $actual.artifactCleanup.invoked $true 'actual helper invoked'
    Assert-Equal $actual.artifactCleanup.deletedCount 1 'one verified directive deleted'
    Assert-Equal $actual.artifactCleanup.heldCount 0 'actual helper has no held items'
    Assert-Equal (Test-Path -LiteralPath $directivePath) $false 'temporary queue no longer contains directive'
    $journals = @(Get-ChildItem -LiteralPath $logDirectory -Recurse -File -Filter 'journal.jsonl')
    $recoveries = @(Get-ChildItem -LiteralPath $logDirectory -Recurse -File -Filter '*.bin')
    Assert-Equal $journals.Count 1 'one cleanup invocation journal without retry'
    Assert-Equal $recoveries.Count 1 'one recovery artifact retained'
    Assert-Equal ((Get-FileHash -LiteralPath $recoveries[0].FullName -Algorithm SHA256).Hash.ToLowerInvariant()) $directiveSha 'recovery matches removed directive'
    $events = @(Get-Content -LiteralPath $journals[0].FullName | ForEach-Object { ($_ | ConvertFrom-Json).event })
    Assert-Equal ($events[0]) 'planned' 'exact target plan precedes cleanup'
    $markIndex=[Array]::IndexOf($events,'completion-recorded')
    $deletePlanIndex=[Array]::IndexOf($events,'delete-planned')
    $deletedIndex=[Array]::IndexOf($events,'deleted')
    Assert-Equal ($markIndex -gt 0 -and $deletePlanIndex -gt $markIndex -and $deletedIndex -gt $deletePlanIndex) $true 'completion and deletion plan are durable before deletion'
    $receipt=Get-Content -LiteralPath (Join-Path $journals[0].DirectoryName 'completion-status.json') -Raw|ConvertFrom-Json
    Assert-Equal $receipt.directive.sha256 $directiveSha 'completion marker binds original directive'
    Assert-Equal $receipt.doNotReapply $true 'completion marker prevents unchanged directive replay'
    Assert-Equal ((Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash.ToLowerInvariant()) $sourceSha 'source postimage unchanged'
    Assert-Equal ((Get-FileHash -LiteralPath $reportPath -Algorithm SHA256).Hash.ToLowerInvariant()) $reportSha 'completion report retained unchanged'
    Assert-Equal $actual.resultSha256 ((Get-FileHash -LiteralPath (Join-Path $tempRoot $resultRel) -Algorithm SHA256).Hash.ToLowerInvariant()) 'actual helper leaves grader proof immutable'
    [pscustomobject]@{ status = 'PASS'; assertions = $script:assertions; behavioralCases = $cases.Count
        realController = $true; docker = 'fixture'; cleanupHelper = 'fixture-and-real'
        actualHelperCases = 1; productionCleanup = $false } | ConvertTo-Json -Compress
} finally {
    if (Test-Path -LiteralPath $tempRoot) {
        $resolved = (Resolve-Path -LiteralPath $tempRoot).ProviderPath
        if ([IO.Path]::GetDirectoryName($resolved) -ne $tempParent -or
            [IO.Path]::GetFileName($resolved) -notmatch '^awx-autograder-cleanup-test-[a-f0-9]{32}$') {
            throw 'fixture-cleanup-boundary-rejected'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
