param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot '..')).ProviderPath
)

$ErrorActionPreference = 'Stop'
$sut = Join-Path $Root '.agents\skills\demo1-docker-autograder\scripts\invoke_docker_autograder.ps1'
$jobBuilder = Join-Path $Root '.agents\skills\demo1-docker-autograder\scripts\new_docker_autograder_job.ps1'
if (-not (Test-Path -LiteralPath $sut -PathType Leaf)) {
    Write-Host '[FAIL] invoke_docker_autograder.ps1 is missing'
    exit 1
}
if (-not (Test-Path -LiteralPath $jobBuilder -PathType Leaf)) {
    Write-Host '[FAIL] new_docker_autograder_job.ps1 is missing'
    exit 1
}

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
function Write-ReadyJson {
    param([string]$Path, [System.Collections.IDictionary]$Value)
    $parent = [IO.Path]::GetDirectoryName($Path)
    New-Item -ItemType Directory -Path $parent -Force | Out-Null
    [IO.File]::WriteAllText(
        $Path,
        (($Value | ConvertTo-Json -Depth 12) + [Environment]::NewLine),
        (New-Object Text.UTF8Encoding($false))
    )
    $sha = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($Path + '.sha256', $sha + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText($Path + '.ready', $sha + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
    return $sha
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-docker-wrapper-test-' + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path (Join-Path $tempRoot 'tests') -Force | Out-Null
    [IO.File]::WriteAllText((Join-Path $tempRoot 'app.py'), 'def answer(): return 42' + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))
    [IO.File]::WriteAllText((Join-Path $tempRoot 'tests\test_app.py'), 'def test_answer(): assert True' + [Environment]::NewLine, (New-Object Text.UTF8Encoding($false)))

    $fakeDockerPy = Join-Path $tempRoot 'fake_docker.py'
    $fakeDockerCmd = Join-Path $tempRoot 'docker.cmd'
    [IO.File]::WriteAllText($fakeDockerPy, @'
import io
import sys
import tarfile

args = sys.argv[1:]
if args and args[0] == "kill":
    raise SystemExit(0)
xml = b'<testsuite tests="2" failures="1" errors="0" skipped="0"/>'
payload = io.BytesIO()
with tarfile.open(fileobj=payload, mode="w") as archive:
    member = tarfile.TarInfo("junit.xml")
    member.size = len(xml)
    archive.addfile(member, io.BytesIO(xml))
print("expected failure signal", file=sys.stderr)
sys.stdout.buffer.write(payload.getvalue())
raise SystemExit(1)
'@, (New-Object Text.UTF8Encoding($false)))
    $python = (Get-Command python -ErrorAction Stop).Source
    [IO.File]::WriteAllText($fakeDockerCmd, "@echo off`r`n`"$python`" `"$fakeDockerPy`" %*`r`n", [Text.Encoding]::ASCII)

    $runId = 'contract-docker-red-001'
    $decisionRel = 'data/upstream/decision.json'
    $intentSpecRel = 'data/upstream/intent-spec.json'
    $decisionSha = Write-ReadyJson (Join-Path $tempRoot $decisionRel) ([ordered]@{ schemaVersion = 'fixture.decision.v1'; runId = $runId })
    $intentSpecSha = Write-ReadyJson (Join-Path $tempRoot $intentSpecRel) ([ordered]@{ schemaVersion = 'fixture.intent-spec.v1'; runId = $runId })
    $builtJobRel = "data/agent-handoff/docker-autograder/$runId/built-job.json"
    $built = & $jobBuilder -Root $tempRoot -RunId $runId -Purpose RED_PROBE -Profile pytest `
        -ImageRef ('example.invalid/awx-pytest@sha256:' + ('a' * 64)) `
        -IncludePaths @('app.py', 'tests/test_app.py') -TestSelectors @('tests/test_app.py') `
        -DeclaredTestCommand 'python -m pytest -q tests/test_app.py' `
        -ExpectedSignal 'expected failure signal' -DecisionFile $decisionRel -IntentSpecFile $intentSpecRel `
        -OutputPath $builtJobRel -ContractTest | ConvertFrom-Json
    $builtJobPath = Join-Path $tempRoot $builtJobRel
    $builtJob = Get-Content -LiteralPath $builtJobPath -Encoding UTF8 -Raw | ConvertFrom-Json
    Assert-Equal $built.status 'PREPARED' 'job builder returns a machine status'
    Assert-Equal $builtJob.decisionSha256 $decisionSha 'job builder binds the first upstream bytes'
    Assert-Equal $builtJob.intentSpecSha256 $intentSpecSha 'job builder binds the second upstream bytes'
    Assert-Equal $builtJob.networkMode 'none' 'job builder fixes network none'
    Assert-Equal $builtJob.pullPolicy 'never' 'job builder fixes pull never'
    Assert-Equal $builtJob.mutationAllowed $false 'job builder cannot authorize mutation'
    Assert-True (Test-Path -LiteralPath ($builtJobPath + '.ready')) 'job builder publishes ready last'

    $jobRel = "data/agent-handoff/docker-autograder/$runId/job.json"
    $resultRel = "data/agent-handoff/docker-autograder/$runId/result.json"
    $jobPath = Join-Path $tempRoot $jobRel
    $job = [ordered]@{
        schemaVersion = 'awx.docker-autograder.job.v1'
        runId = $runId
        purpose = 'RED_PROBE'
        profile = 'pytest'
        imageRef = 'example.invalid/awx-pytest@sha256:' + ('a' * 64)
        includePaths = @('app.py', 'tests/test_app.py')
        testSelectors = @('tests/test_app.py')
        declaredTestCommand = 'python -m pytest -q tests/test_app.py'
        expectedSignal = 'expected failure signal'
        decisionSha256 = ('b' * 64)
        intentSpecSha256 = ('c' * 64)
        limits = [ordered]@{
            cpus = 0.5; memoryMb = 256; pids = 64; timeoutSeconds = 5
            maxFiles = 16; maxInputBytes = 1048576; maxLogBytes = 65536
            maxXmlBytes = 1048576; maxTestCases = 1000
        }
        networkMode = 'none'
        pullPolicy = 'never'
        mutationAllowed = $false
    }
    $jobSha = Write-ReadyJson $jobPath $job
    $machine = & $sut -Root $tempRoot -RunId $runId -JobFile $jobRel `
        -OutputPath $resultRel -DockerBin $fakeDockerCmd -ContractTest | ConvertFrom-Json
    $resultPath = Join-Path $tempRoot $resultRel
    $result = Get-Content -LiteralPath $resultPath -Encoding UTF8 -Raw | ConvertFrom-Json

    Assert-Equal $machine.status 'COMPLETE' 'wrapper returns the controller status'
    Assert-Equal $result.schemaVersion 'awx.docker-autograder.result.v1' 'result schema is stable'
    Assert-Equal $result.jobSha256 $jobSha 'result binds immutable job bytes'
    Assert-Equal $result.tests.total 2 'wrapper executes the real controller process'
    Assert-Equal $result.tests.passed 1 'JUnit pass count is preserved'
    Assert-Equal $result.tests.passRatio 0.5 'pass ratio is deterministic'
    Assert-True ($result.combinedOutputSha256 -match '^[a-f0-9]{64}$') 'combined output hash is present'
    Assert-Equal $result.isolation.networkMode 'none' 'network is disabled'
    Assert-Equal $result.mutationAllowed $false 'wrapper cannot authorize mutation'
    Assert-True (Test-Path -LiteralPath ($resultPath + '.sha256')) 'result hash sidecar exists'
    Assert-True (Test-Path -LiteralPath ($resultPath + '.ready')) 'result ready marker exists'

    Invoke-ExpectedFailure {
        & $sut -Root $tempRoot -RunId $runId -JobFile $jobRel `
            -OutputPath 'main/java/injected-result.json' -DockerBin $fakeDockerCmd -ContractTest | Out-Null
    } 'output-outside-handoff' 'wrapper rejects an application-source output path'

    $badJobRel = "data/agent-handoff/docker-autograder/$runId/bad-job.json"
    $badJobPath = Join-Path $tempRoot $badJobRel
    Write-ReadyJson $badJobPath $job | Out-Null
    [IO.File]::AppendAllText($badJobPath, ' ', (New-Object Text.UTF8Encoding($false)))
    Invoke-ExpectedFailure {
        & $sut -Root $tempRoot -RunId $runId -JobFile $badJobRel `
            -OutputPath "data/agent-handoff/docker-autograder/$runId/bad-result.json" `
            -DockerBin $fakeDockerCmd -ContractTest | Out-Null
    } 'job-checksum-mismatch' 'wrapper rejects changed job bytes'

    # Model share availability without querying a real share. Keep the actual
    # wrapper's canonical-root checks; reject any unexpected filesystem access.
    & {
        $syntheticRoot = '\\awx-fixture.invalid\share'
        function Test-Path {
            param([string]$LiteralPath, [string]$PathType)
            if ($LiteralPath -ceq $syntheticRoot -and $PathType -eq 'Container') { return $rootAvailable }
            throw 'fixture-unexpected-filesystem-access'
        }
        function Resolve-Path {
            param([string]$LiteralPath)
            if ($LiteralPath -ceq $syntheticRoot) {
                return [pscustomobject]@{ ProviderPath = $syntheticRoot; Path = $syntheticRoot }
            }
            throw 'fixture-unexpected-filesystem-access'
        }
        $rootAvailable = $false
        Invoke-ExpectedFailure {
            & $sut -Root $syntheticRoot -RunId $runId -JobFile $jobRel `
                -OutputPath $resultRel -DockerBin $fakeDockerCmd -ContractTest | Out-Null
        } '^root-not-found:' 'wrapper rejects an unavailable synthetic share'
        $rootAvailable = $true
        Invoke-ExpectedFailure {
            & $sut -Root $syntheticRoot -RunId $runId -JobFile $jobRel `
                -OutputPath $resultRel -DockerBin $fakeDockerCmd -ContractTest | Out-Null
        } '^contract-root-must-be-local:' 'fixture bypass rejects a canonical synthetic share'
    }

    Write-Host "[SUMMARY] passed=$script:passed failed=0"
} finally {
    if (Test-Path -LiteralPath $tempRoot) { Remove-Item -LiteralPath $tempRoot -Recurse -Force }
}
