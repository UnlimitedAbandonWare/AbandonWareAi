[CmdletBinding()]
param([switch]$RaceOnly, [switch]$IndexLockOnly, [switch]$CandidateTamperOnly)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:utf8 = New-Object Text.UTF8Encoding($false, $true)
$script:assertions = 0
$script:fixtureRoots = New-Object 'Collections.Generic.List[string]'
$script:workerProcesses = New-Object 'Collections.Generic.List[object]'
$script:sourceController = Join-Path $PSScriptRoot '..\scripts\invoke_consolidated_program.ps1'
$script:sourceCore = Join-Path $PSScriptRoot '..\scripts\consolidated_program_core.psm1'
$script:repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..\..'))
$script:sourceSession = Join-Path $script:repoRoot '__patch_drop__\source_edit_session.ps1'
$script:sourceLeaseContract = Join-Path $script:repoRoot '__patch_drop__\source_edit_lease_contract.ps1'
$script:pwsh = (Get-Command powershell.exe -ErrorAction Stop).Source
$script:sourceHashes = @{
    Controller = (Get-FileHash -LiteralPath $script:sourceController -Algorithm SHA256).Hash
    Core = (Get-FileHash -LiteralPath $script:sourceCore -Algorithm SHA256).Hash
    SourceSession = (Get-FileHash -LiteralPath $script:sourceSession -Algorithm SHA256).Hash
    LeaseContract = (Get-FileHash -LiteralPath $script:sourceLeaseContract -Algorithm SHA256).Hash
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "ASSERTION FAILED: $Message" }
    $script:assertions++
}

function Assert-Equal {
    param($Actual, $Expected, [string]$Message)
    if ($Actual -cne $Expected) { throw "ASSERTION FAILED: $Message (actual=$Actual expected=$Expected)" }
    $script:assertions++
}

function Write-Utf8 {
    param([string]$Path, [string]$Text)
    $parent = Split-Path $Path -Parent
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force -ErrorAction Stop | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Text, $script:utf8)
}

function Get-Hash {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
}

function Copy-WithCanonicalRoot {
    param([string]$Source, [string]$Destination, [string]$Assignment, [switch]$AllowGenericManifestForTempTest)
    $text = [IO.File]::ReadAllText($Source, $script:utf8)
    $matches = [regex]::Matches($text, '(?m)^' + [regex]::Escape($Assignment) + '$')
    if ($matches.Count -ne 1) { throw 'unexpected-canonical-root-assignment' }
    $escapedRoot = $script:fixtureRoot.Replace("'", "''")
    $replacement = if ($Assignment.StartsWith('$script:', [StringComparison]::Ordinal)) {
        "`$script:CanonicalRoot = '$escapedRoot'"
    } else {
        "`$CanonicalRoot = '$escapedRoot'"
    }
    $copyText = $text.Replace($Assignment, $replacement)
    if ($AllowGenericManifestForTempTest) {
        $gateLine = '    Assert-CanonicalManifest $manifest $programFile.text.Substring(0, $beginMatches[0].Index)'
        $gateMatches = [regex]::Matches($copyText, '(?m)^' + [regex]::Escape($gateLine) + '\r?$')
        if ($gateMatches.Count -ne 1) { throw 'unexpected-canonical-gate-shape' }
        $conditionalGate = "    if (Test-HasProperty `$manifest 'programId') {`r`n        Assert-CanonicalManifest `$manifest `$programFile.text.Substring(0, `$beginMatches[0].Index)`r`n    }"
        $copyText = $copyText.Replace($gateLine, $conditionalGate)
        $conditionalLine = '    if (Test-HasProperty $manifest ''programId'') {'
        $nestedGateLine = '        Assert-CanonicalManifest $manifest $programFile.text.Substring(0, $beginMatches[0].Index)'
        if ([regex]::Matches($copyText, '(?m)^' + [regex]::Escape($gateLine) + '\r?$').Count -ne 0 -or
            [regex]::Matches($copyText, '(?m)^' + [regex]::Escape($conditionalLine) + '\r?$').Count -ne 1 -or
            [regex]::Matches($copyText, '(?m)^' + [regex]::Escape($nestedGateLine) + '\r?$').Count -ne 1) {
            throw 'unexpected-canonical-gate-shape'
        }
    }
    Write-Utf8 $Destination $copyText
}

function Invoke-Controller {
    param([string[]]$Arguments)
    $prior = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $script:fixtureController @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prior
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Text = ($lines -join "`n") }
}

function Invoke-ProductionController {
    param([string[]]$Arguments)
    $prior = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $script:sourceController @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $prior
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Text = ($lines -join "`n") }
}

function Start-ControllerJob {
    param([string[]]$ControllerArguments)

    $workerId = [guid]::NewGuid().ToString('N')
    $workerPath = Join-Path $script:fixtureRoot ('.controller-worker-' + $workerId + '.ps1')
    $argumentsPath = Join-Path $script:fixtureRoot ('.controller-worker-' + $workerId + '.arguments.json')
    $readyPath = Join-Path $script:fixtureRoot ('.controller-worker-' + $workerId + '.ready.json')
    $controllerExitPath = Join-Path $script:fixtureRoot ('.controller-worker-' + $workerId + '.controller-exit.txt')
    $stdoutPath = Join-Path $script:fixtureRoot ('.controller-worker-' + $workerId + '.stdout.txt')
    $stderrPath = Join-Path $script:fixtureRoot ('.controller-worker-' + $workerId + '.stderr.txt')
    Write-Utf8 $argumentsPath (ConvertTo-Json -InputObject ([string[]]$ControllerArguments) -Compress)
    $workerText = @'
[CmdletBinding()]
param([string]$PowerShellPath, [string]$ControllerPath, [string]$ArgumentsPath, [string]$ReadyPath, [string]$ControllerExitPath)
$ErrorActionPreference = 'Continue'
$utf8 = New-Object Text.UTF8Encoding($false, $true)
$invocationArguments = [string[]]([IO.File]::ReadAllText($ArgumentsPath, $utf8) | ConvertFrom-Json)
[IO.File]::WriteAllText($ReadyPath, (ConvertTo-Json -InputObject $invocationArguments -Compress), $utf8)
$lines = @(& $PowerShellPath -NoProfile -ExecutionPolicy Bypass -File $ControllerPath @invocationArguments 2>&1)
$exitCode = [int]$LASTEXITCODE
[IO.File]::WriteAllText($ControllerExitPath, ([string]$exitCode), $utf8)
$lines | ForEach-Object { Write-Output $_ }
exit 0
'@
    Write-Utf8 $workerPath $workerText
    $argumentText = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -PowerShellPath "{1}" -ControllerPath "{2}" -ArgumentsPath "{3}" -ReadyPath "{4}" -ControllerExitPath "{5}"' -f `
        $workerPath, $script:pwsh, $script:fixtureController, $argumentsPath, $readyPath, $controllerExitPath
    $process = Start-Process -FilePath $script:pwsh -ArgumentList $argumentText -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $script:workerProcesses.Add($process)
    return [pscustomobject]@{
        Process = $process; Arguments = @($ControllerArguments); ReadyPath = $readyPath; ControllerExitPath = $controllerExitPath
        StdoutPath = $stdoutPath; StderrPath = $stderrPath
    }
}

function Receive-ControllerJob {
    param($Job)

    if (-not $Job.Process.WaitForExit(30000)) { throw 'controller-job-timeout' }
    $Job.Process.Refresh()
    $stdout = if (Test-Path -LiteralPath $Job.StdoutPath) { [IO.File]::ReadAllText($Job.StdoutPath, $script:utf8).Trim() } else { '' }
    $stderr = if (Test-Path -LiteralPath $Job.StderrPath) { [IO.File]::ReadAllText($Job.StderrPath, $script:utf8).Trim() } else { '' }
    if (-not (Test-Path -LiteralPath $Job.ControllerExitPath -PathType Leaf)) { throw 'controller-job-exit-missing' }
    $controllerExitText = [IO.File]::ReadAllText($Job.ControllerExitPath, $script:utf8)
    if ($controllerExitText -notmatch '^[0-9]+$') { throw 'controller-job-exit-invalid' }
    return [pscustomobject]@{ ExitCode = [int]$controllerExitText; Text = $stdout; Diagnostic = $stderr; WorkerExitCode = [int]$Job.Process.ExitCode }
}

function Read-Result {
    param($Invocation)
    try { return ($Invocation.Text | ConvertFrom-Json -ErrorAction Stop) }
    catch { throw "controller-output-not-json: $($Invocation.Text)" }
}

function Rewrite-ProgramManifest {
    param([string]$Path, [scriptblock]$Mutation)
    $text = [IO.File]::ReadAllText($Path, $script:utf8)
    $begin = '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-BEGIN -->'
    $end = '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-END -->'
    $pattern = '(?ms)^' + [regex]::Escape($begin) + '\r?\n```json\r?\n(?<json>.*?)\r?\n```\r?\n' + [regex]::Escape($end) + '\r?$'
    $match = [regex]::Match($text, $pattern)
    if (-not $match.Success) { throw 'fixture-manifest-malformed' }
    $manifest = $match.Groups['json'].Value | ConvertFrom-Json
    & $Mutation $manifest
    $replacement = $manifest | ConvertTo-Json -Depth 30
    $rewritten = $text.Substring(0, $match.Groups['json'].Index) + $replacement + $text.Substring($match.Groups['json'].Index + $match.Groups['json'].Length)
    Write-Utf8 $Path $rewritten
    return $manifest
}

function Get-FileEvidence {
    param([string]$Path)
    return [pscustomobject][ordered]@{ sha256 = Get-Hash $Path; bytes = [int64][IO.File]::ReadAllBytes($Path).Length }
}

function Get-ImageRow {
    param([string]$Root, [string]$RelativePath)
    $full = Join-Path $Root ($RelativePath.Replace('/', '\'))
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
        return [pscustomobject][ordered]@{
            path = $RelativePath; exists = $false; bytes = [int64]0; sha256 = $null
            resolvedPathContained = $true; ancestorNonReparse = $true; reparseTraversal = $false; leafKind = 'missing'
        }
    }
    $file = Get-FileEvidence $full
    return [pscustomobject][ordered]@{
        path = $RelativePath; exists = $true; bytes = $file.bytes; sha256 = $file.sha256
        resolvedPathContained = $true; ancestorNonReparse = $true; reparseTraversal = $false; leafKind = 'leaf'
    }
}

function Write-GreenEvidence {
    param($Fixture, $RunningState, [string]$RunId = 'compose-a')
    $redLogRelative = 'verification/red-a.log'
    $greenLogRelative = 'verification/green-a.log'
    $redLogPath = Join-Path $Fixture.Root ($redLogRelative.Replace('/', '\'))
    $greenLogPath = Join-Path $Fixture.Root ($greenLogRelative.Replace('/', '\'))
    Write-Utf8 $redLogPath 'expected red'
    Write-Utf8 $greenLogPath 'focused green'
    $redLog = Get-FileEvidence $redLogPath
    $greenLog = Get-FileEvidence $greenLogPath
    $unit = @($RunningState.workUnits | Where-Object workUnitId -CEQ 'WU-COMPOSE-A')[0]
    $evidence = [ordered]@{
        schemaVersion = 'awx.notebook.directive.work-unit-evidence.v1'; owner = 'desktop'; runId = $RunId
        workUnitId = 'WU-COMPOSE-A'; canonicalMarkdownSha256 = [string]$RunningState.canonicalMarkdownSha256; outcome = 'Green'
        targetPreimages = @($unit.execution.targetPreimages)
        targetPostimages = @(Get-ImageRow $Fixture.Root 'main/java/B.java')
        redCommand = [ordered]@{
            commandId = 'red:sha256:c61fb6fb450244997487755fdaf2c907e3a10acefbff8a636f746c2b8c19e50b'
            exitCode = 1; logPath = $redLogRelative; logSha256 = $redLog.sha256; logBytes = $redLog.bytes
        }
        greenCommands = @([ordered]@{
            commandId = 'green:sha256:c61fb6fb450244997487755fdaf2c907e3a10acefbff8a636f746c2b8c19e50b'
            exitCode = 0; logPath = $greenLogRelative; logSha256 = $greenLog.sha256; logBytes = $greenLog.bytes; characterization = $false
        })
        secretScan = [ordered]@{ mode = 'count-only'; hitCount = 0 }
        rollback = [ordered]@{ status = 'not_required'; actualTargets = @() }
        desktopFinalProof = 'verified'
    }
    $relative = 'verification/evidence-a.json'
    Write-Utf8 (Join-Path $Fixture.Root ($relative.Replace('/', '\'))) ($evidence | ConvertTo-Json -Depth 20 -Compress)
    return $relative
}

function Write-NoPatchEvidence {
    param($Fixture, $RunningState)
    $greenLogRelative = 'verification/characterize-b.log'
    $greenLogPath = Join-Path $Fixture.Root ($greenLogRelative.Replace('/', '\'))
    Write-Utf8 $greenLogPath 'bounded characterization'
    $greenLog = Get-FileEvidence $greenLogPath
    $unit = @($RunningState.workUnits | Where-Object workUnitId -CEQ 'WU-COMPOSE-B')[0]
    $evidence = [ordered]@{
        schemaVersion = 'awx.notebook.directive.work-unit-evidence.v1'; owner = 'desktop'; runId = 'compose-b'
        workUnitId = 'WU-COMPOSE-B'; canonicalMarkdownSha256 = [string]$RunningState.canonicalMarkdownSha256; outcome = 'NoPatchNeeded'
        targetPreimages = @($unit.execution.targetPreimages)
        targetPostimages = @(Get-ImageRow $Fixture.Root 'main/java/C.java')
        redCommand = $null
        greenCommands = @([ordered]@{
            commandId = 'green:sha256:748f4519943f10c4eb8c04446ca0591fb22727e83a5539039a113f33926d3b65'
            exitCode = 0; logPath = $greenLogRelative; logSha256 = $greenLog.sha256; logBytes = $greenLog.bytes; characterization = $true
        })
        secretScan = [ordered]@{ mode = 'count-only'; hitCount = 0 }
        rollback = [ordered]@{ status = 'not_required'; actualTargets = @() }
        desktopFinalProof = 'verified'
    }
    $relative = 'verification/evidence-b.json'
    Write-Utf8 (Join-Path $Fixture.Root ($relative.Replace('/', '\'))) ($evidence | ConvertTo-Json -Depth 20 -Compress)
    return $relative
}

function Assert-OriginalHashesUnchanged {
    Assert-Equal (Get-Hash $script:sourceController) $script:sourceHashes.Controller 'original controller hash changed'
    Assert-Equal (Get-Hash $script:sourceCore) $script:sourceHashes.Core 'original core hash changed'
    Assert-Equal (Get-Hash $script:sourceSession) $script:sourceHashes.SourceSession 'original source-session hash changed'
    Assert-Equal (Get-Hash $script:sourceLeaseContract) $script:sourceHashes.LeaseContract 'original lease-contract hash changed'
}

function Remove-SafeFixture {
    param([string]$FixtureRoot)
    if ([string]::IsNullOrWhiteSpace($FixtureRoot)) { return }
    $temp = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
    $full = [IO.Path]::GetFullPath($FixtureRoot).TrimEnd('\')
    $leaf = Split-Path $full -Leaf
    if (-not $full.StartsWith($temp + '\', [StringComparison]::OrdinalIgnoreCase) -or
        -not $leaf.StartsWith('awx-public-composition-', [StringComparison]::Ordinal)) {
        throw 'unsafe-temp-cleanup-target'
    }
    if (Test-Path -LiteralPath $full) { Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop }
}

function New-PublicCompositionFixture {
    param(
        [switch]$OversizedTarget,
        [ValidateSet('tracked','untracked','ignored')][string]$TargetGitMode = 'tracked',
        [AllowNull()][scriptblock]$MutateManifest = $null
    )
    $script:fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-public-composition-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $script:fixtureRoot -Force -ErrorAction Stop | Out-Null
    $script:fixtureRoots.Add($script:fixtureRoot)

    $scripts = Join-Path $script:fixtureRoot '.agents\skills\demo1-consolidating-notebook-directives\scripts'
    $patchDrop = Join-Path $script:fixtureRoot '__patch_drop__'
    New-Item -ItemType Directory -Path $scripts, $patchDrop -Force -ErrorAction Stop | Out-Null
    $script:fixtureController = Join-Path $scripts 'invoke_consolidated_program.ps1'
    $fixtureCore = Join-Path $scripts 'consolidated_program_core.psm1'
    Copy-WithCanonicalRoot $script:sourceController $script:fixtureController '$CanonicalRoot = ''C:\AbandonWare\demo-1\demo-1\src''' -AllowGenericManifestForTempTest
    Copy-WithCanonicalRoot $script:sourceCore $fixtureCore '$script:CanonicalRoot = ''C:\AbandonWare\demo-1\demo-1\src'''
    Copy-Item -LiteralPath $script:sourceSession -Destination (Join-Path $patchDrop 'source_edit_session.ps1')
    Copy-Item -LiteralPath $script:sourceLeaseContract -Destination (Join-Path $patchDrop 'source_edit_lease_contract.ps1')
    Assert-Equal (Get-Hash (Join-Path $patchDrop 'source_edit_session.ps1')) $script:sourceHashes.SourceSession 'temp source-session copy changed'
    Assert-Equal (Get-Hash (Join-Path $patchDrop 'source_edit_lease_contract.ps1')) $script:sourceHashes.LeaseContract 'temp lease-contract copy changed'

    $candidateRelative = 'data/agent-handoff/notebook/input.md'
    $candidatePath = Join-Path $script:fixtureRoot ($candidateRelative.Replace('/', '\'))
    Write-Utf8 $candidatePath 'fixture directive'
    $targetRelative = 'main/java/B.java'
    $targetPath = Join-Path $script:fixtureRoot ($targetRelative.Replace('/', '\'))
    $secondTargetRelative = 'main/java/C.java'
    $secondTargetPath = Join-Path $script:fixtureRoot ($secondTargetRelative.Replace('/', '\'))
    if ($OversizedTarget) {
        $targetParent = Split-Path $targetPath -Parent
        New-Item -ItemType Directory -Path $targetParent -Force -ErrorAction Stop | Out-Null
        [IO.File]::WriteAllBytes($targetPath, [byte[]]::new(16MB + 1))
    } else {
        Write-Utf8 $targetPath 'final class B {}'
    }
    Write-Utf8 $secondTargetPath 'final class C {}'

    & git -C $script:fixtureRoot init --quiet 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'fixture-git-init-failed' }
    $gitSeedRelative = switch ($TargetGitMode) {
        'tracked' { @($targetRelative, $secondTargetRelative) }
        'untracked' {
            Write-Utf8 (Join-Path $script:fixtureRoot '.fixture-sentinel') 'fixture repository seed'
            @('.fixture-sentinel', $secondTargetRelative)
        }
        'ignored' {
            Write-Utf8 (Join-Path $script:fixtureRoot '.gitignore') ($targetRelative + "`n")
            @('.gitignore', $secondTargetRelative)
        }
    }
    & git -C $script:fixtureRoot -c core.autocrlf=false add -- $gitSeedRelative 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'fixture-git-add-failed' }
    & git -C $script:fixtureRoot -c user.name=awx-test -c user.email=awx-test.invalid commit --quiet -m fixture 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'fixture-git-commit-failed' }

    $programRelative = 'agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260806.md'
    $programPath = Join-Path $script:fixtureRoot ($programRelative.Replace('/', '\'))
    $candidateHash = Get-Hash $candidatePath
    $candidateBytes = [IO.File]::ReadAllBytes($candidatePath).Length
    $commandA = 'powershell -NoProfile -ExecutionPolicy Bypass -File verification/red-green-a.ps1'
    $commandB = 'powershell -NoProfile -ExecutionPolicy Bypass -File verification/characterize-b.ps1'
    $inventory = [ordered]@{
        schemaVersion = 'demo1.notebook-directive-inventory.v1'
        canonicalExecutionRoot = $script:fixtureRoot
        candidateRoots = @('data/agent-handoff/notebook','__patch_drop__/notebook','agent-prompts')
        candidates = @([ordered]@{
            path = $candidateRelative; sha256 = $candidateHash; bytes = [int64]$candidateBytes; gitTracking = 'untracked'
            provenance = 'notebook'; format = 'markdown'; directiveIds = @(); targetFiles = @(); inclusionReason = 'standalone-notebook-directive'
        })
        excluded = @([ordered]@{ path = $programRelative; reason = 'reusable-prompt' })
    }
    $units = @(
        [ordered]@{
            workUnitId = 'WU-COMPOSE-A'; status = 'pending'; dependencies = @(); required = $true; kind = 'verification'
            targetFiles = @($targetRelative); redCommands = @($commandA); greenCommands = @($commandA); retirementCoverage = @($candidateRelative)
        },
        [ordered]@{
            workUnitId = 'WU-COMPOSE-B'; status = 'pending'; dependencies = @('WU-COMPOSE-A'); required = $true; kind = 'verification'
            targetFiles = @($secondTargetRelative); redCommands = @(); greenCommands = @($commandB); retirementCoverage = @()
        }
    )
    $retirement = [ordered]@{
        schemaVersion = 'demo1.notebook-directive-retirement.v1'; deleteAuthorized = $false
        canonicalDirectivePath = $programRelative; canonicalDirectiveSha256Evidence = 'external-final-output'
        allRequiredWorkUnitsGreen = $false; desktopFinalProof = 'evidence_needed'; status = 'hold'
        items = @([ordered]@{
            path = $candidateRelative; sha256 = $candidateHash; bytes = [int64]$candidateBytes; gitTracking = 'untracked'
            coverage = @('WU-COMPOSE-A'); eligibility = 'hold'; holdReason = 'desktop-work-unit-proof-pending'
            deletionResult = 'not_run'; status = 'hold'
        })
    }
    $manifest = [ordered]@{
        schemaVersion = 'awx.notebook.directive.controller-manifest.v1'
        directiveInventory = $inventory
        workUnits = $units
        retirement = $retirement
    }
    if ($null -ne $MutateManifest) { & $MutateManifest $manifest }
    $manifestJson = $manifest | ConvertTo-Json -Depth 30
    $programText = 'fixture' + "`n" +
        '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-BEGIN -->' + "`n" +
        '```json' + "`n" + $manifestJson + "`n" + '```' + "`n" +
        '<!-- AWX-CONSOLIDATED-CONTROLLER-MANIFEST-END -->'
    Write-Utf8 $programPath $programText

    return [pscustomobject]@{
        Root = $script:fixtureRoot
        Program = $programPath
        State = Join-Path $script:fixtureRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\program-state.json'
        Target = $targetPath
        Candidate = $candidatePath
        Events = Join-Path $script:fixtureRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\events.jsonl'
        EventLock = Join-Path $script:fixtureRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\events.jsonl.lock'
        Releases = Join-Path $script:fixtureRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\releases'
        Archive = Join-Path $script:fixtureRoot 'data\agent-handoff\notebook\consolidated\awx-desktop-notebook-consolidated-source-20260806\archive'
    }
}

function Get-ExpectedLeaseDirectory {
    param($Fixture, [string]$WorkUnitId)
    $stateValue = [IO.File]::ReadAllText($Fixture.State, $script:utf8) | ConvertFrom-Json
    $topic = 'consolidated-' + ([string]$stateValue.canonicalMarkdownSha256).Substring(0, 12).ToLowerInvariant() + '-' + $WorkUnitId
    return Join-Path $Fixture.Root ('__patch_drop__\source-edit-locks\' + $topic + '.lock')
}

function Set-PendingBeginTransition {
    param($Fixture, [ValidateSet('begin-intent','begin-recovery-required')][string]$Phase, [string]$RunId)
    $stateValue = [IO.File]::ReadAllText($Fixture.State, $script:utf8) | ConvertFrom-Json
    $unit = @($stateValue.workUnits | Where-Object workUnitId -CEQ 'WU-COMPOSE-A')[0]
    $topic = 'consolidated-' + ([string]$stateValue.canonicalMarkdownSha256).Substring(0, 12).ToLowerInvariant() + '-WU-COMPOSE-A'
    $transition = [ordered]@{ phase = $Phase; runId = $RunId; owner = 'desktop'; leaseTopic = $topic }
    if ($Phase -ceq 'begin-intent') { $transition['canonicalMarkdownSha256'] = [string]$stateValue.canonicalMarkdownSha256 }
    $unit | Add-Member transition ([pscustomobject]$transition)
    Write-Utf8 $Fixture.State ($stateValue | ConvertTo-Json -Depth 40 -Compress)
    return $topic
}

function Start-FixtureLease {
    param($Fixture, [string]$Topic, [string]$RunId)
    $sessionPath = Join-Path $Fixture.Root '__patch_drop__\source_edit_session.ps1'
    $prior = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $null = @(& $script:pwsh -NoProfile -ExecutionPolicy Bypass -File $sessionPath -Action begin -Role desktop -Root $Fixture.Root -Topic $Topic -OwnerId $RunId -TtlMinutes 180 2>&1)
        $exitCode = $LASTEXITCODE
    } finally { $ErrorActionPreference = $prior }
    if ($exitCode -ne 0) { throw 'fixture-lease-begin-failed' }
    return Join-Path $Fixture.Root ('__patch_drop__\source-edit-locks\' + $Topic + '.lock\lease.json')
}

function Assert-RejectedBeginCleanup {
    param($Fixture, $Invocation, [string]$ExpectedReason, [string]$Label)
    $value = Read-Result $Invocation
    Assert-True ($Invocation.ExitCode -ne 0) "$Label Begin did not fail closed"
    Assert-Equal $value.reason $ExpectedReason "$Label Begin returned the wrong reason"
    Assert-True (-not (Test-Path -LiteralPath (Get-ExpectedLeaseDirectory $Fixture 'WU-COMPOSE-A'))) "$Label Begin left the exact lease directory"
    $stateValue = [IO.File]::ReadAllText($Fixture.State, $script:utf8) | ConvertFrom-Json
    $unit = @($stateValue.workUnits | Where-Object workUnitId -CEQ 'WU-COMPOSE-A')[0]
    Assert-Equal $unit.status 'pending' "$Label Begin did not restore pending state"
    Assert-True ($null -eq $unit.PSObject.Properties['transition'] -and $null -eq $unit.PSObject.Properties['execution']) "$Label Begin left mutable phase state"
    $reload = Invoke-Controller @('-Action','Status','-Root',$Fixture.Root)
    $reloadValue = Read-Result $reload
    Assert-Equal $reload.ExitCode 0 "$Label restored state is not publicly readable: $($reloadValue.reason)"
}

function Assert-TamperedStateRejected {
    param([scriptblock]$Mutation, [string]$ExpectedReason, [string]$Label)
    $tamperFixture = New-PublicCompositionFixture
    $snapshot = Invoke-Controller @('-Action','Snapshot','-Root',$tamperFixture.Root)
    Assert-Equal $snapshot.ExitCode 0 "$Label fixture Snapshot failed"
    $stateValue = [IO.File]::ReadAllText($tamperFixture.State, $script:utf8) | ConvertFrom-Json
    & $Mutation $stateValue
    Write-Utf8 $tamperFixture.State ($stateValue | ConvertTo-Json -Depth 40 -Compress)
    $status = Invoke-Controller @('-Action','Status','-Root',$tamperFixture.Root)
    $result = Read-Result $status
    Assert-True ($status.ExitCode -ne 0) "$Label tampering was accepted"
    Assert-Equal $result.reason $ExpectedReason "$Label tampering returned the wrong reason"
}

function Invoke-IndexLockGateCase {
    $indexLockFixture = New-PublicCompositionFixture
    $indexLockSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$indexLockFixture.Root)
    Assert-Equal $indexLockSnapshot.ExitCode 0 'post-acquire index-lock fixture Snapshot failed'
    $indexLockPath = Join-Path $indexLockFixture.Root '.git\index.lock'
    $leaseDirectory = Get-ExpectedLeaseDirectory $indexLockFixture 'WU-COMPOSE-A'
    $watcherId = [guid]::NewGuid().ToString('N')
    $watcherPath = Join-Path $indexLockFixture.Root ('.index-lock-watcher-' + $watcherId + '.ps1')
    $signalPath = Join-Path $indexLockFixture.Root ('.index-lock-watcher-' + $watcherId + '.ready')
    $stdoutPath = Join-Path $indexLockFixture.Root ('.index-lock-watcher-' + $watcherId + '.stdout.txt')
    $stderrPath = Join-Path $indexLockFixture.Root ('.index-lock-watcher-' + $watcherId + '.stderr.txt')
    $watcherText = @'
[CmdletBinding()]
param([string]$LeaseDirectory, [string]$IndexLockPath, [string]$SignalPath)
$deadline = [DateTime]::UtcNow.AddSeconds(20)
while (-not [IO.Directory]::Exists($LeaseDirectory) -and [DateTime]::UtcNow -lt $deadline) { [Threading.Thread]::Sleep(1) }
if (-not [IO.Directory]::Exists($LeaseDirectory)) { exit 2 }
[IO.File]::WriteAllBytes($IndexLockPath, [byte[]]@())
[IO.File]::WriteAllText($SignalPath, 'created', [Text.UTF8Encoding]::new($false))
exit 0
'@
    Write-Utf8 $watcherPath $watcherText
    $watcherArguments = '-NoProfile -ExecutionPolicy Bypass -File "{0}" -LeaseDirectory "{1}" -IndexLockPath "{2}" -SignalPath "{3}"' -f $watcherPath,$leaseDirectory,$indexLockPath,$signalPath
    $watcher = Start-Process -FilePath $script:pwsh -ArgumentList $watcherArguments -WindowStyle Hidden -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath -PassThru
    $script:workerProcesses.Add($watcher)
    try {
        $indexLockBegin = Invoke-Controller @('-Action','Begin','-Root',$indexLockFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','index-lock-after-acquire')
        if (-not $watcher.WaitForExit(10000)) { throw 'index-lock-watcher-timeout' }
        $watcher.WaitForExit()
        $watcher.Refresh()
        $watcherDiagnostic = if (Test-Path -LiteralPath $stderrPath) { [IO.File]::ReadAllText($stderrPath, $script:utf8).Trim() } else { '' }
        Assert-Equal ([int]$watcher.ExitCode) 0 "index-lock watcher did not observe the acquired lease: $watcherDiagnostic"
        Assert-True ((Test-Path -LiteralPath $signalPath -PathType Leaf) -and (Test-Path -LiteralPath $indexLockPath -PathType Leaf)) 'index lock was not injected after lease acquisition'
        Assert-RejectedBeginCleanup $indexLockFixture $indexLockBegin 'index-lock-present' 'post-acquire index-lock'
        Assert-True (Test-Path -LiteralPath $indexLockPath -PathType Leaf) 'Begin mutated the post-acquire repository index lock'
        foreach ($redirectPath in @($stdoutPath,$stderrPath)) {
            $redirectProbe = [IO.FileStream]::new($redirectPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            $redirectProbe.Dispose()
            Assert-True $true 'index-lock watcher redirect handle closed before fixture cleanup'
        }
    } finally {
        if (-not $watcher.HasExited) { $watcher.Kill(); $null = $watcher.WaitForExit(5000) }
    }
}

function Invoke-StateCasRace {
    $raceFixture = New-PublicCompositionFixture
    $raceSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$raceFixture.Root)
    Assert-Equal $raceSnapshot.ExitCode 0 'CAS-race fixture Snapshot failed'
    $raceStateHash = Get-Hash $raceFixture.State
    $raceLockPath = $raceFixture.State + '.cas.lock'
    $raceLockParent = Split-Path $raceLockPath -Parent
    if (-not (Test-Path -LiteralPath $raceLockParent)) { New-Item -ItemType Directory -Path $raceLockParent -Force -ErrorAction Stop | Out-Null }
    $heldRaceLock = $null
    $raceJobs = @()
    try {
        $heldRaceLock = [IO.FileStream]::new($raceLockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        Assert-Equal $heldRaceLock.Length ([int64]0) 'CAS lock artifact is not zero-byte'
        $raceJobs = @(
            Start-ControllerJob @('-Action','Begin','-Root',$raceFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','race-a')
            Start-ControllerJob @('-Action','Begin','-Root',$raceFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','race-b')
        )
        $readyDeadline = [DateTime]::UtcNow.AddSeconds(15)
        while (@($raceJobs | Where-Object { -not (Test-Path -LiteralPath $_.ReadyPath -PathType Leaf) }).Count -gt 0 -and [DateTime]::UtcNow -lt $readyDeadline) {
            Start-Sleep -Milliseconds 50
        }
        Assert-Equal @($raceJobs | Where-Object { Test-Path -LiteralPath $_.ReadyPath -PathType Leaf }).Count 2 'two Begin workers did not publish their received arguments'
        foreach ($raceJob in $raceJobs) {
            $receivedArguments = [string[]]([IO.File]::ReadAllText($raceJob.ReadyPath, $script:utf8) | ConvertFrom-Json)
            Assert-Equal ($receivedArguments -join "`0") (@($raceJob.Arguments) -join "`0") 'Begin worker did not receive the exact controller arguments'
            $rootIndex = [Array]::IndexOf($receivedArguments, '-Root')
            Assert-True ($rootIndex -ge 0 -and [string]$receivedArguments[$rootIndex + 1] -ceq $raceFixture.Root) 'Begin worker was not bound to the exact temp root'
        }
        $candidateDirectory = Split-Path $raceFixture.State -Parent
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $candidateCount = @(Get-ChildItem -LiteralPath $candidateDirectory -File -Filter '.state-*.tmp' -Force -ErrorAction SilentlyContinue).Count
            if ($candidateCount -ge 2 -or @($raceJobs | Where-Object { $_.Process.HasExited }).Count -gt 0) { break }
            Start-Sleep -Milliseconds 50
        } while ([DateTime]::UtcNow -lt $deadline)
        $raceDiagnostic = @($raceJobs | ForEach-Object { "pid=$($_.Process.Id); exited=$($_.Process.HasExited)" }) -join '; '
        Assert-Equal $candidateCount 2 "two Begin processes did not block at the same durable state CAS lock ($raceDiagnostic)"
        Assert-True (@($raceJobs | Where-Object { -not $_.Process.HasExited }).Count -eq 2) 'a Begin process escaped the parent-held CAS lock'
        Assert-Equal (Get-Hash $raceFixture.State) $raceStateHash 'parent-held CAS lock allowed a state mutation'
        Assert-True (-not (Test-Path -LiteralPath (Get-ExpectedLeaseDirectory $raceFixture 'WU-COMPOSE-A'))) 'parent-held CAS lock allowed a lease mutation'
    } finally {
        if ($null -ne $heldRaceLock) { $heldRaceLock.Dispose() }
        foreach ($raceJob in @($raceJobs)) {
            if ($null -ne $raceJob -and -not $raceJob.Process.HasExited) { $null = $raceJob.Process.WaitForExit(30000) }
        }
    }
    try {
        $raceResults = @($raceJobs | ForEach-Object { Receive-ControllerJob $_ })
    } finally {
        foreach ($job in @($raceJobs)) {
            if ($null -ne $job -and -not $job.Process.HasExited) { $job.Process.Kill() }
        }
    }
    $raceValues = @($raceResults | ForEach-Object { Read-Result $_ })
    $raceResultDiagnostic = @($raceResults | ForEach-Object { "exit=$($_.ExitCode); text=$($_.Text); stderr=$($_.Diagnostic)" }) -join '; '
    Assert-Equal @($raceResults | Where-Object WorkerExitCode -EQ 0).Count 2 'controller worker wrapper did not close cleanly'
    Assert-Equal @($raceResults | Where-Object ExitCode -EQ 0).Count 1 "state CAS race did not produce exactly one winner ($raceResultDiagnostic)"
    Assert-Equal @($raceValues | Where-Object reason -CEQ 'work-unit-begun').Count 1 'state CAS race winner returned the wrong result'
    Assert-Equal @($raceValues | Where-Object reason -CEQ 'state-hash-changed').Count 1 'state CAS race loser did not fail the expected hash gate'
    $raceState = [IO.File]::ReadAllText($raceFixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal $raceState.workUnits[0].status 'running' 'state CAS race lost the winning running transition'
    Assert-True ([string]$raceState.workUnits[0].execution.runId -cin @('race-a','race-b')) 'state CAS race persisted an unknown winner identity'
    $raceLeaseRoot = Join-Path $raceFixture.Root '__patch_drop__\source-edit-locks'
    Assert-Equal @(Get-ChildItem -LiteralPath $raceLeaseRoot -Directory -Filter '*.lock' -Force -ErrorAction Stop).Count 1 'state CAS race did not retain exactly one winner lease'
    Assert-True ((Test-Path -LiteralPath $raceLockPath -PathType Leaf) -and ([IO.FileInfo]$raceLockPath).Length -eq 0) 'persistent CAS lock artifact is missing or nonzero'
    Assert-Equal @(Get-ChildItem -LiteralPath (Split-Path $raceFixture.State -Parent) -File -Filter '.state-*.tmp' -Force -ErrorAction SilentlyContinue).Count 0 'state CAS race left temporary candidates'
    foreach ($raceJob in $raceJobs) {
        Assert-True $raceJob.Process.HasExited 'race worker was still running before redirect cleanup'
        foreach ($redirectPath in @($raceJob.StdoutPath, $raceJob.StderrPath)) {
            $redirectProbe = [IO.FileStream]::new($redirectPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            $redirectProbe.Dispose()
            Assert-True $true 'race worker redirect handle closed before fixture cleanup'
        }
    }
    $lockProbe = [IO.FileStream]::new($raceLockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $lockProbe.Dispose()
    Assert-True $true 'persistent CAS lock can be reopened exclusively after the race'
}

function Invoke-CandidateTamperCase {
    $tamperFixture = New-PublicCompositionFixture
    $tamperSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$tamperFixture.Root)
    Assert-Equal $tamperSnapshot.ExitCode 0 'candidate-tamper fixture Snapshot failed'
    $originalStateHash = Get-Hash $tamperFixture.State
    $tamperLockPath = $tamperFixture.State + '.cas.lock'
    $heldTamperLock = $null
    $tamperJob = $null
    try {
        $heldTamperLock = [IO.FileStream]::new($tamperLockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $tamperJob = Start-ControllerJob @('-Action','Begin','-Root',$tamperFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','candidate-tamper')
        $readyDeadline = [DateTime]::UtcNow.AddSeconds(15)
        while (-not (Test-Path -LiteralPath $tamperJob.ReadyPath -PathType Leaf) -and [DateTime]::UtcNow -lt $readyDeadline) { Start-Sleep -Milliseconds 50 }
        Assert-True (Test-Path -LiteralPath $tamperJob.ReadyPath -PathType Leaf) 'candidate-tamper worker did not publish its received arguments'
        $candidateDirectory = Split-Path $tamperFixture.State -Parent
        $candidateDeadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $candidates = @(Get-ChildItem -LiteralPath $candidateDirectory -File -Filter '.state-*.tmp' -Force -ErrorAction SilentlyContinue)
            if ($candidates.Count -eq 1 -or $tamperJob.Process.HasExited) { break }
            Start-Sleep -Milliseconds 50
        } while ([DateTime]::UtcNow -lt $candidateDeadline)
        Assert-Equal $candidates.Count 1 'candidate-tamper worker did not block with one closed state candidate'
        Assert-True (-not $tamperJob.Process.HasExited) 'candidate-tamper worker escaped the parent-held CAS lock'
        $candidateOverwritten = $false
        $overwriteDeadline = [DateTime]::UtcNow.AddSeconds(5)
        do {
            $candidateStream = $null
            try {
                $candidateStream = [IO.FileStream]::new($candidates[0].FullName, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
                $candidateStream.SetLength(0)
                $tamperedBytes = $script:utf8.GetBytes('x')
                $candidateStream.Write($tamperedBytes, 0, $tamperedBytes.Length)
                $candidateStream.Flush($true)
                $candidateOverwritten = $true
            } catch [IO.IOException] {
                $nativeCode = $_.Exception.HResult -band 0xFFFF
                if ($nativeCode -notin @(32, 33)) { throw }
                Start-Sleep -Milliseconds 10
            } finally {
                if ($null -ne $candidateStream) { $candidateStream.Dispose() }
            }
        } while (-not $candidateOverwritten -and [DateTime]::UtcNow -lt $overwriteDeadline)
        Assert-True $candidateOverwritten 'candidate-tamper worker did not close its temporary writer within the bounded deadline'
        Assert-Equal (Get-Hash $tamperFixture.State) $originalStateHash 'candidate overwrite changed state before lock release'
        Assert-True (-not (Test-Path -LiteralPath (Get-ExpectedLeaseDirectory $tamperFixture 'WU-COMPOSE-A'))) 'candidate overwrite created a lease before lock release'
    } finally {
        if ($null -ne $heldTamperLock) { $heldTamperLock.Dispose() }
        if ($null -ne $tamperJob -and -not $tamperJob.Process.HasExited) { $null = $tamperJob.Process.WaitForExit(30000) }
    }
    try { $tamperResult = Receive-ControllerJob $tamperJob } finally { if ($null -ne $tamperJob -and -not $tamperJob.Process.HasExited) { $tamperJob.Process.Kill() } }
    $tamperValue = Read-Result $tamperResult
    Assert-True ($tamperResult.ExitCode -ne 0) 'tampered state candidate unexpectedly published'
    Assert-Equal $tamperValue.reason 'state-candidate-changed' 'tampered state candidate returned the wrong reason'
    Assert-Equal (Get-Hash $tamperFixture.State) $originalStateHash 'tampered state candidate changed durable state'
    $restoredState = [IO.File]::ReadAllText($tamperFixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal $restoredState.workUnits[0].status 'pending' 'candidate tamper did not preserve pending state'
    Assert-True (-not (Test-Path -LiteralPath (Get-ExpectedLeaseDirectory $tamperFixture 'WU-COMPOSE-A'))) 'candidate tamper left a lease directory'
    $stateDirectory = Split-Path $tamperFixture.State -Parent
    Assert-Equal @(Get-ChildItem -LiteralPath $stateDirectory -File -Filter '.state-*.tmp' -Force -ErrorAction SilentlyContinue).Count 0 'candidate tamper left a temporary candidate'
    Assert-Equal @(Get-ChildItem -LiteralPath $stateDirectory -File -Filter '*.cas-backup-*' -Force -ErrorAction SilentlyContinue).Count 0 'candidate tamper left a CAS backup'
    Assert-True $tamperJob.Process.HasExited 'candidate-tamper worker remained live before fixture cleanup'
    foreach ($redirectPath in @($tamperJob.StdoutPath, $tamperJob.StderrPath)) {
        $redirectProbe = [IO.FileStream]::new($redirectPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $redirectProbe.Dispose()
        Assert-True $true 'candidate-tamper redirect handle closed before fixture cleanup'
    }
    $lockProbe = [IO.FileStream]::new($tamperLockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $lockProbe.Dispose()
    Assert-True $true 'candidate-tamper CAS lock reopens exclusively'
}

function Invoke-RecoveryClaimRace {
    $cleanupFixture = New-PublicCompositionFixture
    $cleanupSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$cleanupFixture.Root)
    Assert-Equal $cleanupSnapshot.ExitCode 0 'recovery-claim race fixture Snapshot failed'
    $cleanupTopic = Set-PendingBeginTransition $cleanupFixture 'begin-recovery-required' 'cleanup-race'
    $cleanupLeasePath = Start-FixtureLease $cleanupFixture $cleanupTopic 'cleanup-race'
    $cleanupLeaseHash = Get-Hash $cleanupLeasePath
    $cleanupStateHash = Get-Hash $cleanupFixture.State
    $cleanupLockPath = $cleanupFixture.State + '.cas.lock'
    $heldCleanupLock = $null
    $cleanupJobs = @()
    try {
        $heldCleanupLock = [IO.FileStream]::new($cleanupLockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
        $cleanupJobs = @(
            Start-ControllerJob @('-Action','Begin','-Root',$cleanupFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','cleanup-race')
            Start-ControllerJob @('-Action','Begin','-Root',$cleanupFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','cleanup-race')
        )
        $readyDeadline = [DateTime]::UtcNow.AddSeconds(15)
        while (@($cleanupJobs | Where-Object { -not (Test-Path -LiteralPath $_.ReadyPath -PathType Leaf) }).Count -gt 0 -and [DateTime]::UtcNow -lt $readyDeadline) {
            Start-Sleep -Milliseconds 50
        }
        Assert-Equal @($cleanupJobs | Where-Object { Test-Path -LiteralPath $_.ReadyPath -PathType Leaf }).Count 2 'recovery-claim workers did not publish their received arguments'
        foreach ($cleanupJob in $cleanupJobs) {
            $receivedArguments = [string[]]([IO.File]::ReadAllText($cleanupJob.ReadyPath, $script:utf8) | ConvertFrom-Json)
            Assert-Equal ($receivedArguments -join "`0") (@($cleanupJob.Arguments) -join "`0") 'recovery-claim worker did not receive exact arguments'
        }
        $candidateDirectory = Split-Path $cleanupFixture.State -Parent
        $deadline = [DateTime]::UtcNow.AddSeconds(15)
        do {
            $candidateCount = @(Get-ChildItem -LiteralPath $candidateDirectory -File -Filter '.state-*.tmp' -Force -ErrorAction SilentlyContinue).Count
            if ($candidateCount -ge 2 -or @($cleanupJobs | Where-Object { $_.Process.HasExited }).Count -gt 0) { break }
            Start-Sleep -Milliseconds 50
        } while ([DateTime]::UtcNow -lt $deadline)
        Assert-Equal $candidateCount 2 'two cleanup claimants did not block before Release'
        Assert-True (@($cleanupJobs | Where-Object { -not $_.Process.HasExited }).Count -eq 2) 'a cleanup claimant escaped the parent-held lock'
        Assert-Equal (Get-Hash $cleanupFixture.State) $cleanupStateHash 'blocked cleanup claimant changed state'
        Assert-Equal (Get-Hash $cleanupLeasePath) $cleanupLeaseHash 'blocked cleanup claimant released or rewrote the lease'
    } finally {
        if ($null -ne $heldCleanupLock) { $heldCleanupLock.Dispose() }
        foreach ($cleanupJob in @($cleanupJobs)) {
            if ($null -ne $cleanupJob -and -not $cleanupJob.Process.HasExited) { $null = $cleanupJob.Process.WaitForExit(30000) }
        }
    }
    try {
        $cleanupResults = @($cleanupJobs | ForEach-Object { Receive-ControllerJob $_ })
    } finally {
        foreach ($cleanupJob in @($cleanupJobs)) {
            if ($null -ne $cleanupJob -and -not $cleanupJob.Process.HasExited) { $cleanupJob.Process.Kill() }
        }
    }
    $cleanupValues = @($cleanupResults | ForEach-Object { Read-Result $_ })
    Assert-Equal @($cleanupResults | Where-Object ExitCode -EQ 0).Count 1 'recovery-claim race did not produce exactly one winner'
    Assert-Equal @($cleanupValues | Where-Object reason -CEQ 'work-unit-begun').Count 1 'recovery-claim winner returned the wrong result'
    Assert-Equal @($cleanupValues | Where-Object reason -CEQ 'state-hash-changed').Count 1 'recovery-claim loser did not fail before Release'
    $cleanupState = [IO.File]::ReadAllText($cleanupFixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal $cleanupState.workUnits[0].status 'running' 'recovery-claim race did not reach running'
    Assert-Equal $cleanupState.workUnits[0].execution.runId 'cleanup-race' 'recovery-claim race changed the bound run identity'
    Assert-True ((Test-Path -LiteralPath $cleanupLeasePath -PathType Leaf) -and (Get-Hash $cleanupLeasePath) -cne $cleanupLeaseHash) 'recovery-claim winner did not replace the prior lease'
    $cleanupLease = [IO.File]::ReadAllText($cleanupLeasePath, $script:utf8) | ConvertFrom-Json
    Assert-Equal $cleanupLease.topic $cleanupTopic 'recovery-claim winner changed the deterministic lease topic'
    Assert-Equal $cleanupLease.ownerId 'cleanup-race' 'recovery-claim winner changed the lease owner'
    Assert-Equal @(Get-ChildItem -LiteralPath (Split-Path $cleanupFixture.State -Parent) -File -Filter '.state-*.tmp' -Force -ErrorAction SilentlyContinue).Count 0 'recovery-claim race left temporary candidates'
    foreach ($cleanupJob in $cleanupJobs) {
        Assert-True $cleanupJob.Process.HasExited 'recovery-claim worker remained live before fixture cleanup'
        foreach ($redirectPath in @($cleanupJob.StdoutPath, $cleanupJob.StderrPath)) {
            $redirectProbe = [IO.FileStream]::new($redirectPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            $redirectProbe.Dispose()
            Assert-True $true 'recovery-claim redirect handle closed before fixture cleanup'
        }
    }
    $lockProbe = [IO.FileStream]::new($cleanupLockPath, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    $lockProbe.Dispose()
    Assert-True $true 'recovery-claim CAS lock reopens exclusively after recovery'
}

try {
    $sourceControllerText = [IO.File]::ReadAllText($script:sourceController, $script:utf8)
    Assert-Equal ([regex]::Matches($sourceControllerText, 'safe\.directory').Count) 0 'production controller must not override Git safe.directory'
    Assert-Equal ([regex]::Matches($sourceControllerText, 'Assert-PublicGitIndexUnlocked').Count) 2 'resolved index-lock proof must stay inside the target-cleanliness helper'
    Assert-Equal ([regex]::Matches($sourceControllerText, 'rev-parse --git-path index\.lock').Count) 1 'target cleanliness must resolve the actual Git index-lock path'
    Assert-Equal ([regex]::Matches($sourceControllerText, "if \(\[string\]\`$Request\.mode -ceq 'preimage'\) \{ Assert-PublicTargetGitClean \`$relativePaths \}").Count) 2 'preimage target cleanliness must remain a before-and-after capture gate'
    if ($RaceOnly) {
        Invoke-StateCasRace
        Invoke-RecoveryClaimRace
        Assert-OriginalHashesUnchanged
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($IndexLockOnly) {
        Invoke-IndexLockGateCase
        Assert-OriginalHashesUnchanged
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    if ($CandidateTamperOnly) {
        Invoke-CandidateTamperCase
        Assert-OriginalHashesUnchanged
        Write-Output "PASS: $script:assertions assertions"
        return
    }
    $fixture = New-PublicCompositionFixture
    $snapshot = Invoke-Controller @('-Action','Snapshot','-Root',$fixture.Root)
    $snapshotResult = Read-Result $snapshot
    Assert-Equal $snapshot.ExitCode 0 'temp Snapshot failed'
    Assert-Equal $snapshotResult.reason 'snapshot-created' 'temp Snapshot did not create state'

    $begin = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','compose-a')
    $beginResult = Read-Result $begin
    Assert-Equal $begin.ExitCode 0 'public Begin failed before reload'
    Assert-Equal $beginResult.reason 'work-unit-begun' 'public Begin did not persist running state'

    $reload = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
    $reloadResult = Read-Result $reload
    Assert-Equal $reload.ExitCode 0 "new-process Status cannot reload public Begin state: $($reloadResult.reason)"
    Assert-Equal $reloadResult.reason 'validated-state' 'new-process Status did not validate running state'

    $runningState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
    Write-Utf8 $fixture.Target 'final class B { int applied; }'
    $evidencePath = Write-GreenEvidence $fixture $runningState
    $record = Invoke-Controller @(
        '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','compose-a',
        '-Outcome','Green','-EvidencePath',$evidencePath
    )
    $recordResult = Read-Result $record
    Assert-Equal $record.ExitCode 0 "public Green Record failed: $($recordResult.reason)"
    Assert-Equal $recordResult.reason 'work-unit-recorded' 'public Green Record did not reach terminal state'

    $afterFirst = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
    $firstUnit = @($afterFirst.workUnits | Where-Object workUnitId -CEQ 'WU-COMPOSE-A')[0]
    Assert-Equal $firstUnit.status 'green' 'first unit is not terminal green'
    Assert-Equal @($firstUnit.record.PSObject.Properties).Count 9 'terminal record is not the exact core shape'
    Assert-True ($null -eq $firstUnit.PSObject.Properties['execution'] -and $null -eq $firstUnit.PSObject.Properties['transition']) 'terminal unit retained mutable phase fields'
    Assert-Equal $afterFirst.desktopFinalProof 'evidence_needed' 'first terminal unit prematurely verified the whole program'
    Assert-Equal $afterFirst.retirement.desktopFinalProof 'evidence_needed' 'nested proof changed while another required unit was pending'
    Assert-True (-not [bool]$afterFirst.retirement.allRequiredWorkUnitsGreen) 'all-required-green became true before the second unit'
    $firstLeaseDirectory = Join-Path $fixture.Root ('__patch_drop__\source-edit-locks\' + [string]$beginResult.leaseTopic + '.lock')
    Assert-True (-not (Test-Path -LiteralPath $firstLeaseDirectory)) 'first Record left the exact lease directory behind'
    $firstReload = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
    Assert-Equal $firstReload.ExitCode 0 'new-process Status cannot reload the first terminal record'

    $beginB = Invoke-Controller @('-Action','Begin','-Root',$fixture.Root,'-WorkUnitId','WU-COMPOSE-B','-RunId','compose-b')
    $beginBResult = Read-Result $beginB
    Assert-Equal $beginB.ExitCode 0 "second public Begin failed: $($beginBResult.reason)"
    $runningBReload = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
    Assert-Equal $runningBReload.ExitCode 0 'new-process Status cannot reload the second running unit'
    $runningBState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
    $evidenceBPath = Write-NoPatchEvidence $fixture $runningBState
    $recordB = Invoke-Controller @(
        '-Action','Record','-Root',$fixture.Root,'-WorkUnitId','WU-COMPOSE-B','-RunId','compose-b',
        '-Outcome','NoPatchNeeded','-EvidencePath',$evidenceBPath
    )
    $recordBResult = Read-Result $recordB
    Assert-Equal $recordB.ExitCode 0 "public NoPatchNeeded Record failed: $($recordBResult.reason)"

    $finalState = [IO.File]::ReadAllText($fixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal $finalState.desktopFinalProof 'verified' 'top-level final proof is not verified'
    Assert-True ([bool]$finalState.retirement.allRequiredWorkUnitsGreen) 'nested retirement all-required-green was not derived'
    Assert-Equal $finalState.retirement.desktopFinalProof 'verified' 'nested retirement proof was not synchronized'
    Assert-True (-not [bool]$finalState.deleteAuthorized -and -not [bool]$finalState.retirement.deleteAuthorized) 'Record authorized deletion'
    Assert-Equal $finalState.retirement.status 'hold' 'Record changed retirement status'
    Assert-True (@($finalState.retirement.items | Where-Object { $_.eligibility -cne 'hold' -or $_.deletionResult -cne 'not_run' -or $_.status -cne 'hold' }).Count -eq 0) 'Record changed retirement item HOLD fields'
    $finalRecordText = $finalState.workUnits[0].record | ConvertTo-Json -Depth 20 -Compress
    Assert-True (-not $finalRecordText.Contains('powershell -NoProfile -ExecutionPolicy Bypass -File verification/red-green-a.ps1')) 'terminal record leaked a raw command declaration'
    $secondLeaseDirectory = Join-Path $fixture.Root ('__patch_drop__\source-edit-locks\' + [string]$beginBResult.leaseTopic + '.lock')
    Assert-True (-not (Test-Path -LiteralPath $secondLeaseDirectory)) 'second Record left the exact lease directory behind'
    $finalReload = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
    $finalReloadResult = Read-Result $finalReload
    Assert-Equal $finalReload.ExitCode 0 "new-process Status cannot reload the final projection: $($finalReloadResult.reason)"

    $beforeProgramHash = Get-Hash $fixture.Program
    $beforeStateHash = Get-Hash $fixture.State
    $beforeCandidateHash = Get-Hash $fixture.Candidate
    $beforeEventHash = Get-Hash $fixture.Events
    $beforeEventLockHash = Get-Hash $fixture.EventLock
    $compositionEventText = [IO.File]::ReadAllText($fixture.Events, $script:utf8)
    $compositionEventRows = @($compositionEventText.Substring(0, $compositionEventText.Length - 1).Split([char]0x0A) | ForEach-Object { $_ | ConvertFrom-Json -ErrorAction Stop })
    Assert-Equal $compositionEventRows.Count 5 'completed composition did not publish exactly five lifecycle rows'
    Assert-Equal (@($compositionEventRows | ForEach-Object { [string]$_.action }) -join ',') 'Snapshot,Begin,Record,Begin,Record' 'completed composition event order changed'
    Assert-Equal ([string]$finalState.eventCheckpoint.eventSha256) ([string]$compositionEventRows[4].eventSha256) 'final state checkpoint does not equal the ledger tail'
    foreach ($disabledAction in @('Release','Retire')) {
        $arguments = @('-Action',$disabledAction,'-Root',$fixture.Root)
        if ($disabledAction -ceq 'Retire') { $arguments += '-ConfirmRetirement' }
        $disabled = Invoke-Controller $arguments
        $disabledResult = Read-Result $disabled
        Assert-True ($disabled.ExitCode -ne 0) "$disabledAction unexpectedly succeeded"
        Assert-Equal $disabledResult.reason 'action-not-implemented' "$disabledAction is not quarantined"
    }
    Assert-Equal (Get-Hash $fixture.Program) $beforeProgramHash 'disabled lifecycle action changed the canonical fixture'
    Assert-Equal (Get-Hash $fixture.State) $beforeStateHash 'disabled lifecycle action changed state'
    Assert-Equal (Get-Hash $fixture.Candidate) $beforeCandidateHash 'disabled lifecycle action changed the original directive'
    Assert-Equal (Get-Hash $fixture.Events) $beforeEventHash 'disabled lifecycle action changed the event ledger'
    Assert-Equal (Get-Hash $fixture.EventLock) $beforeEventLockHash 'disabled lifecycle action changed the event lock'
    Assert-True (-not (Test-Path -LiteralPath $fixture.Releases) -and -not (Test-Path -LiteralPath $fixture.Archive)) 'disabled lifecycle action created release or archive artifacts'

    $validFinalStateText = [IO.File]::ReadAllText($fixture.State, $script:utf8)
    $tamperedNoPatchState = $validFinalStateText | ConvertFrom-Json
    $tamperedNoPatchState.workUnits[1].record.targetPostimages[0].sha256 = 'F' * 64
    Write-Utf8 $fixture.State ($tamperedNoPatchState | ConvertTo-Json -Depth 40 -Compress)
    $tamperedNoPatchStatus = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
    $tamperedNoPatchResult = Read-Result $tamperedNoPatchStatus
    Assert-True ($tamperedNoPatchStatus.ExitCode -ne 0) 'NoPatchNeeded record with changed postimage was accepted on reload'
    Assert-Equal $tamperedNoPatchResult.reason 'invalid-work-unit-contract' 'NoPatchNeeded image tamper returned the wrong reason'
    Write-Utf8 $fixture.State $validFinalStateText
    Assert-Equal (Get-Hash $fixture.State) $beforeStateHash 'NoPatchNeeded tamper test did not restore exact state bytes'

    $tamperedGreenState = $validFinalStateText | ConvertFrom-Json
    $tamperedGreenState.workUnits[0].record.targetPostimages = @($tamperedGreenState.workUnits[0].record.targetPreimages)
    Write-Utf8 $fixture.State ($tamperedGreenState | ConvertTo-Json -Depth 40 -Compress)
    $tamperedGreenStatus = Invoke-Controller @('-Action','Status','-Root',$fixture.Root)
    $tamperedGreenResult = Read-Result $tamperedGreenStatus
    Assert-True ($tamperedGreenStatus.ExitCode -ne 0) 'Green record with unchanged declared targets was accepted on reload'
    Assert-Equal $tamperedGreenResult.reason 'invalid-work-unit-contract' 'unchanged Green record returned the wrong reason'
    Write-Utf8 $fixture.State $validFinalStateText
    Assert-Equal (Get-Hash $fixture.State) $beforeStateHash 'Green image tamper test did not restore exact state bytes'

    $dirtyFixture = New-PublicCompositionFixture
    $dirtySnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$dirtyFixture.Root)
    Assert-Equal $dirtySnapshot.ExitCode 0 'dirty-target fixture Snapshot failed'
    Write-Utf8 $dirtyFixture.Target 'final class B { int dirty; }'
    $dirtyBegin = Invoke-Controller @('-Action','Begin','-Root',$dirtyFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','dirty-a')
    Assert-RejectedBeginCleanup $dirtyFixture $dirtyBegin 'dirty-target-overlap' 'dirty-target'

    foreach ($gitMode in @('untracked','ignored')) {
        $untrackedFixture = New-PublicCompositionFixture -TargetGitMode $gitMode
        $untrackedSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$untrackedFixture.Root)
        Assert-Equal $untrackedSnapshot.ExitCode 0 "$gitMode-target fixture Snapshot failed"
        $untrackedBegin = Invoke-Controller @('-Action','Begin','-Root',$untrackedFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId',("$gitMode-a"))
        Assert-RejectedBeginCleanup $untrackedFixture $untrackedBegin 'dirty-target-overlap' "$gitMode-target"
    }
    Invoke-IndexLockGateCase

    $captureFixture = New-PublicCompositionFixture -OversizedTarget
    $captureSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$captureFixture.Root)
    Assert-Equal $captureSnapshot.ExitCode 0 'capture-failure fixture Snapshot failed'
    $captureBegin = Invoke-Controller @('-Action','Begin','-Root',$captureFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','capture-a')
    Assert-RejectedBeginCleanup $captureFixture $captureBegin 'target-too-large' 'capture-failure'

    $residualFixture = New-PublicCompositionFixture
    $residualSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$residualFixture.Root)
    Assert-Equal $residualSnapshot.ExitCode 0 'residual-lock fixture Snapshot failed'
    $residualBegin = Invoke-Controller @('-Action','Begin','-Root',$residualFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','residual-a')
    $residualBeginResult = Read-Result $residualBegin
    Assert-Equal $residualBegin.ExitCode 0 "residual-lock Begin failed: $($residualBeginResult.reason)"
    $residualRunning = [IO.File]::ReadAllText($residualFixture.State, $script:utf8) | ConvertFrom-Json
    $residualEvidencePath = Write-GreenEvidence $residualFixture $residualRunning -RunId 'residual-a'
    $residualLeaseDirectory = Join-Path $residualFixture.Root ('__patch_drop__\source-edit-locks\' + [string]$residualBeginResult.leaseTopic + '.lock')
    $residualLeaseLeaf = Join-Path $residualLeaseDirectory 'lease.json'
    Assert-True (Test-Path -LiteralPath $residualLeaseLeaf -PathType Leaf) 'residual-lock fixture did not create a lease leaf'
    Remove-Item -LiteralPath $residualLeaseLeaf -Force -ErrorAction Stop
    Assert-True ((Test-Path -LiteralPath $residualLeaseDirectory -PathType Container) -and -not (Test-Path -LiteralPath $residualLeaseLeaf)) 'residual-lock fixture did not preserve only the corrupt directory'
    $residualRecord = Invoke-Controller @(
        '-Action','Record','-Root',$residualFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','residual-a',
        '-Outcome','Green','-EvidencePath',$residualEvidencePath
    )
    $residualRecordResult = Read-Result $residualRecord
    Assert-True ($residualRecord.ExitCode -ne 0) 'Record advanced despite a residual lock directory'
    Assert-Equal $residualRecordResult.reason 'lease-not-active' 'residual lock directory returned the wrong failure reason'
    $residualState = [IO.File]::ReadAllText($residualFixture.State, $script:utf8) | ConvertFrom-Json
    $residualUnit = @($residualState.workUnits | Where-Object workUnitId -CEQ 'WU-COMPOSE-A')[0]
    Assert-Equal $residualUnit.status 'running' 'residual lock failure advanced the unit to terminal'
    Assert-True ($null -ne $residualUnit.PSObject.Properties['execution'] -and $null -eq $residualUnit.PSObject.Properties['transition'] -and $null -eq $residualUnit.PSObject.Properties['record']) 'residual lock failure created a transition or terminal record'
    $residualReload = Invoke-Controller @('-Action','Status','-Root',$residualFixture.Root)
    $residualReloadResult = Read-Result $residualReload
    Assert-Equal $residualReload.ExitCode 0 "awaiting-release state is not publicly readable: $($residualReloadResult.reason)"

    $expiredFixture = New-PublicCompositionFixture
    $expiredSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$expiredFixture.Root)
    Assert-Equal $expiredSnapshot.ExitCode 0 'expired-lease fixture Snapshot failed'
    $expiredBegin = Invoke-Controller @('-Action','Begin','-Root',$expiredFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','expired-a')
    $expiredBeginResult = Read-Result $expiredBegin
    Assert-Equal $expiredBegin.ExitCode 0 "expired-lease initial Begin failed: $($expiredBeginResult.reason)"
    $expiredLeasePath = Join-Path $expiredFixture.Root ('__patch_drop__\source-edit-locks\' + [string]$expiredBeginResult.leaseTopic + '.lock\lease.json')
    $expiredLease = [IO.File]::ReadAllText($expiredLeasePath, $script:utf8) | ConvertFrom-Json
    $expiredLease.expiresAtUtc = '2000-01-01T00:00:00.0000000+00:00'
    $expiredLease.expiresAt = '2000-01-01T00:00:00.0000000+00:00'
    Write-Utf8 $expiredLeasePath ($expiredLease | ConvertTo-Json -Depth 10 -Compress)
    $expiredReplay = Invoke-Controller @('-Action','Begin','-Root',$expiredFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','expired-a')
    $expiredReplayResult = Read-Result $expiredReplay
    Assert-True ($expiredReplay.ExitCode -ne 0) 'expired lease was fabricated as active on Begin replay'
    Assert-Equal $expiredReplayResult.reason 'lease-not-active' 'expired lease returned the wrong public reason'
    $expiredState = [IO.File]::ReadAllText($expiredFixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal $expiredState.workUnits[0].status 'running' 'expired lease replay mutated running state'

    $mismatchedExpiryFixture = New-PublicCompositionFixture
    $mismatchedExpirySnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$mismatchedExpiryFixture.Root)
    Assert-Equal $mismatchedExpirySnapshot.ExitCode 0 'mismatched-expiry fixture Snapshot failed'
    $mismatchedExpiryBegin = Invoke-Controller @('-Action','Begin','-Root',$mismatchedExpiryFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','expiry-mismatch-a')
    $mismatchedExpiryBeginResult = Read-Result $mismatchedExpiryBegin
    Assert-Equal $mismatchedExpiryBegin.ExitCode 0 "mismatched-expiry initial Begin failed: $($mismatchedExpiryBeginResult.reason)"
    $mismatchedExpiryLeasePath = Join-Path $mismatchedExpiryFixture.Root ('__patch_drop__\source-edit-locks\' + [string]$mismatchedExpiryBeginResult.leaseTopic + '.lock\lease.json')
    $mismatchedExpiryLease = [IO.File]::ReadAllText($mismatchedExpiryLeasePath, $script:utf8) | ConvertFrom-Json
    $mismatchedExpiryLease.expiresAt = '2099-01-01T00:00:00.0000000+00:00'
    Write-Utf8 $mismatchedExpiryLeasePath ($mismatchedExpiryLease | ConvertTo-Json -Depth 10 -Compress)
    $mismatchedExpiryReplay = Invoke-Controller @('-Action','Begin','-Root',$mismatchedExpiryFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','expiry-mismatch-a')
    $mismatchedExpiryResult = Read-Result $mismatchedExpiryReplay
    Assert-True ($mismatchedExpiryReplay.ExitCode -ne 0) 'mismatched lease expiry instants were accepted'
    Assert-Equal $mismatchedExpiryResult.reason 'invalid-lease-schema' 'mismatched lease expiry returned the wrong reason'

    $mutableManifestFixture = New-PublicCompositionFixture -MutateManifest {
        param($manifest)
        $manifest.workUnits[0]['transition'] = [ordered]@{ phase = 'begin-recovery-required'; runId = 'seed'; owner = 'desktop'; leaseTopic = 'seed' }
    }
    $mutableManifestSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$mutableManifestFixture.Root)
    $mutableManifestResult = Read-Result $mutableManifestSnapshot
    Assert-True ($mutableManifestSnapshot.ExitCode -ne 0) 'manifest seeded with mutable transition was accepted'
    Assert-Equal $mutableManifestResult.reason 'invalid-work-unit-contract' 'mutable manifest seed returned the wrong reason'
    Assert-True (-not (Test-Path -LiteralPath $mutableManifestFixture.State)) 'rejected mutable manifest created state'

    foreach ($separatorCase in @(
        @{ label = 'line'; value = [char]0x2028 },
        @{ label = 'paragraph'; value = [char]0x2029 }
    )) {
        $separator = $separatorCase.value
        $unicodeManifestFixture = New-PublicCompositionFixture -MutateManifest {
            param($manifest)
            $manifest.workUnits[0].redCommands[0] = 'command' + $separator + 'next'
        }
        $unicodeManifestSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$unicodeManifestFixture.Root)
        $unicodeManifestResult = Read-Result $unicodeManifestSnapshot
        Assert-True ($unicodeManifestSnapshot.ExitCode -ne 0) "Unicode $($separatorCase.label)-separator command was accepted by the controller"
        Assert-Equal $unicodeManifestResult.reason 'invalid-command-contract' "Unicode $($separatorCase.label)-separator command returned the wrong controller reason"
        Assert-True (-not (Test-Path -LiteralPath $unicodeManifestFixture.State)) "Unicode $($separatorCase.label)-separator rejection created state"
    }

    $zeroRequiredFixture = New-PublicCompositionFixture -MutateManifest {
        param($manifest)
        foreach ($unit in @($manifest.workUnits)) { $unit.required = $false }
        $manifest.retirement.allRequiredWorkUnitsGreen = $false
    }
    $zeroRequiredSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$zeroRequiredFixture.Root)
    Assert-Equal $zeroRequiredSnapshot.ExitCode 0 'zero-required fixture Snapshot failed'
    $zeroRequiredStatus = Invoke-Controller @('-Action','Status','-Root',$zeroRequiredFixture.Root)
    $zeroRequiredStatusResult = Read-Result $zeroRequiredStatus
    Assert-Equal $zeroRequiredStatus.ExitCode 0 "zero-required Snapshot was not readable by Status: $($zeroRequiredStatusResult.reason)"
    $zeroRequiredState = [IO.File]::ReadAllText($zeroRequiredFixture.State, $script:utf8) | ConvertFrom-Json
    Assert-True (-not [bool]$zeroRequiredState.retirement.allRequiredWorkUnitsGreen) 'zero-required Snapshot derived vacuous all-green'
    Assert-Equal $zeroRequiredState.desktopFinalProof 'evidence_needed' 'zero-required Snapshot derived verified proof'

    $vacuousManifestFixture = New-PublicCompositionFixture -MutateManifest {
        param($manifest)
        foreach ($unit in @($manifest.workUnits)) { $unit.required = $false }
        $manifest.retirement.allRequiredWorkUnitsGreen = $true
    }
    $vacuousManifestSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$vacuousManifestFixture.Root)
    $vacuousManifestResult = Read-Result $vacuousManifestSnapshot
    Assert-True ($vacuousManifestSnapshot.ExitCode -ne 0) 'zero-required manifest claimed vacuous all-green'
    Assert-Equal $vacuousManifestResult.reason 'invalid-retirement-schema' 'vacuous manifest all-green returned the wrong reason'
    Assert-True (-not (Test-Path -LiteralPath $vacuousManifestFixture.State)) 'vacuous manifest rejection published unreadable state'

    $approvalFixture = New-PublicCompositionFixture -MutateManifest {
        param($manifest)
        $manifest.workUnits[0].status = 'hold'
        $manifest.workUnits[0]['approvalGate'] = [ordered]@{
            schemaVersion = 'awx.notebook.directive.approval-gate.v1'; requiredForBegin = $true
            acceptAny = @('noNewPublicApi','explicitApproval')
        }
    }
    $approvalSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$approvalFixture.Root)
    Assert-Equal $approvalSnapshot.ExitCode 0 'approval-gated fixture Snapshot failed'
    $approvalState = [IO.File]::ReadAllText($approvalFixture.State, $script:utf8) | ConvertFrom-Json
    $approvalState.workUnits[0].status = 'pending'
    Write-Utf8 $approvalFixture.State ($approvalState | ConvertTo-Json -Depth 40 -Compress)
    $approvalStatus = Invoke-Controller @('-Action','Status','-Root',$approvalFixture.Root)
    $approvalStatusResult = Read-Result $approvalStatus
    Assert-True ($approvalStatus.ExitCode -ne 0) 'approval-gated HOLD was bypassed by a state-only pending status'
    Assert-Equal $approvalStatusResult.reason 'frozen-projection-mismatch' 'approval-gate bypass returned the wrong reason'

    $intentAbsentFixture = New-PublicCompositionFixture
    $intentAbsentSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$intentAbsentFixture.Root)
    Assert-Equal $intentAbsentSnapshot.ExitCode 0 'intent-absent fixture Snapshot failed'
    $intentAbsentTopic = Set-PendingBeginTransition $intentAbsentFixture 'begin-intent' 'intent-absent-a'
    $intentAbsentBegin = Invoke-Controller @('-Action','Begin','-Root',$intentAbsentFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','intent-absent-a')
    $intentAbsentResult = Read-Result $intentAbsentBegin
    Assert-Equal $intentAbsentBegin.ExitCode 0 "new-process begin-intent absent resume failed: $($intentAbsentResult.reason)"
    Assert-Equal $intentAbsentResult.leaseTopic $intentAbsentTopic 'begin-intent absent resume changed the deterministic topic'
    $intentAbsentState = [IO.File]::ReadAllText($intentAbsentFixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal $intentAbsentState.workUnits[0].status 'running' 'begin-intent absent resume did not reach running'

    $intentActiveFixture = New-PublicCompositionFixture
    $intentActiveSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$intentActiveFixture.Root)
    Assert-Equal $intentActiveSnapshot.ExitCode 0 'intent-active fixture Snapshot failed'
    $intentActiveTopic = Set-PendingBeginTransition $intentActiveFixture 'begin-intent' 'intent-active-a'
    $intentActiveLease = Start-FixtureLease $intentActiveFixture $intentActiveTopic 'intent-active-a'
    $intentActiveLeaseHash = Get-Hash $intentActiveLease
    $intentActiveBegin = Invoke-Controller @('-Action','Begin','-Root',$intentActiveFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','intent-active-a')
    $intentActiveResult = Read-Result $intentActiveBegin
    Assert-Equal $intentActiveBegin.ExitCode 0 "new-process begin-intent active resume failed: $($intentActiveResult.reason)"
    Assert-Equal (Get-Hash $intentActiveLease) $intentActiveLeaseHash 'begin-intent active resume rewrote the exact lease'

    $intentExpiringFixture = New-PublicCompositionFixture
    $intentExpiringSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$intentExpiringFixture.Root)
    Assert-Equal $intentExpiringSnapshot.ExitCode 0 'intent-expiring fixture Snapshot failed'
    $intentExpiringTopic = Set-PendingBeginTransition $intentExpiringFixture 'begin-intent' 'intent-expiring-a'
    $intentExpiringLease = Start-FixtureLease $intentExpiringFixture $intentExpiringTopic 'intent-expiring-a'
    $intentExpiringLeaseValue = [IO.File]::ReadAllText($intentExpiringLease, $script:utf8) | ConvertFrom-Json
    $nearExpiry = [DateTimeOffset]::UtcNow.AddMilliseconds(700).ToString('o', [Globalization.CultureInfo]::InvariantCulture)
    $intentExpiringLeaseValue.expiresAtUtc = $nearExpiry
    $intentExpiringLeaseValue.expiresAt = $nearExpiry
    Write-Utf8 $intentExpiringLease ($intentExpiringLeaseValue | ConvertTo-Json -Depth 10 -Compress)
    $intentExpiringLeaseHash = Get-Hash $intentExpiringLease
    $intentExpiringBegin = Invoke-Controller @('-Action','Begin','-Root',$intentExpiringFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','intent-expiring-a')
    $intentExpiringResult = Read-Result $intentExpiringBegin
    Assert-Equal $intentExpiringBegin.ExitCode 0 "new-process begin-intent expiring resume failed: $($intentExpiringResult.reason)"
    Assert-True ((Get-Hash $intentExpiringLease) -cne $intentExpiringLeaseHash) 'expiring lease was reused instead of replaced before capture'
    $intentExpiringState = [IO.File]::ReadAllText($intentExpiringFixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal $intentExpiringState.workUnits[0].status 'running' 'expiring begin-intent resume did not reach running under a fresh lease'

    $recoveryExpiredFixture = New-PublicCompositionFixture
    $recoveryExpiredSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$recoveryExpiredFixture.Root)
    Assert-Equal $recoveryExpiredSnapshot.ExitCode 0 'recovery-expired fixture Snapshot failed'
    $recoveryExpiredTopic = Set-PendingBeginTransition $recoveryExpiredFixture 'begin-recovery-required' 'recovery-expired-a'
    $recoveryExpiredLease = Start-FixtureLease $recoveryExpiredFixture $recoveryExpiredTopic 'recovery-expired-a'
    $recoveryExpiredLeaseValue = [IO.File]::ReadAllText($recoveryExpiredLease, $script:utf8) | ConvertFrom-Json
    $recoveryExpiredLeaseValue.expiresAtUtc = '2000-01-01T00:00:00.0000000+00:00'
    $recoveryExpiredLeaseValue.expiresAt = '2000-01-01T00:00:00.0000000+00:00'
    Write-Utf8 $recoveryExpiredLease ($recoveryExpiredLeaseValue | ConvertTo-Json -Depth 10 -Compress)
    $expiredRecoveryHash = Get-Hash $recoveryExpiredLease
    $recoveryExpiredBegin = Invoke-Controller @('-Action','Begin','-Root',$recoveryExpiredFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','recovery-expired-a')
    $recoveryExpiredResult = Read-Result $recoveryExpiredBegin
    Assert-Equal $recoveryExpiredBegin.ExitCode 0 "new-process recovery expired resume failed: $($recoveryExpiredResult.reason)"
    Assert-True ((Get-Hash $recoveryExpiredLease) -cne $expiredRecoveryHash) 'recovery expired resume did not issue a new lease'
    $recoveryExpiredState = [IO.File]::ReadAllText($recoveryExpiredFixture.State, $script:utf8) | ConvertFrom-Json
    Assert-Equal $recoveryExpiredState.workUnits[0].status 'running' 'recovery expired resume did not reach running'

    $residualResumeFixture = New-PublicCompositionFixture
    $residualResumeSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$residualResumeFixture.Root)
    Assert-Equal $residualResumeSnapshot.ExitCode 0 'residual-resume fixture Snapshot failed'
    $residualResumeTopic = Set-PendingBeginTransition $residualResumeFixture 'begin-intent' 'residual-resume-a'
    $residualResumeStateHash = Get-Hash $residualResumeFixture.State
    $residualResumeDirectory = Join-Path $residualResumeFixture.Root ('__patch_drop__\source-edit-locks\' + $residualResumeTopic + '.lock')
    New-Item -ItemType Directory -Path $residualResumeDirectory -Force -ErrorAction Stop | Out-Null
    $residualResumeBegin = Invoke-Controller @('-Action','Begin','-Root',$residualResumeFixture.Root,'-WorkUnitId','WU-COMPOSE-A','-RunId','residual-resume-a')
    $residualResumeResult = Read-Result $residualResumeBegin
    Assert-True ($residualResumeBegin.ExitCode -ne 0) 'residual lock directory was treated as an absent resumable lease'
    Assert-Equal $residualResumeResult.reason 'lease-not-active' 'residual resume directory returned the wrong reason'
    Assert-Equal (Get-Hash $residualResumeFixture.State) $residualResumeStateHash 'residual resume failure mutated durable state'

    Invoke-StateCasRace
    Invoke-CandidateTamperCase
    Invoke-RecoveryClaimRace

    $productionOmissionFixture = New-PublicCompositionFixture
    $productionOmissionSnapshot = Invoke-Controller @('-Action','Snapshot','-Root',$productionOmissionFixture.Root)
    Assert-Equal $productionOmissionSnapshot.ExitCode 0 'programId-omission fixture Snapshot failed'
    $productionManifest = Rewrite-ProgramManifest $productionOmissionFixture.Program {
        param($manifest)
        $manifest.directiveInventory.canonicalExecutionRoot = $script:repoRoot
    }
    $productionState = [IO.File]::ReadAllText($productionOmissionFixture.State, $script:utf8) | ConvertFrom-Json
    $productionState.controllerManifest = $productionManifest
    $productionState.directiveInventory = $productionManifest.directiveInventory
    $productionState.canonicalMarkdownSha256 = Get-Hash $productionOmissionFixture.Program
    Write-Utf8 $productionOmissionFixture.State ($productionState | ConvertTo-Json -Depth 40 -Compress)
    $productionOmissionStatus = Invoke-ProductionController @('-Action','Status','-Root',$productionOmissionFixture.Root)
    $productionOmissionResult = Read-Result $productionOmissionStatus
    Assert-True ($productionOmissionStatus.ExitCode -ne 0) 'untouched production controller accepted a manifest with omitted programId'
    Assert-Equal $productionOmissionResult.reason 'canonical-manifest-schema' 'production programId omission returned the wrong reason'

    $canonicalWhatIf = Invoke-ProductionController @('-Action','Snapshot','-Root',$script:repoRoot,'-WhatIf')
    $canonicalWhatIfResult = Read-Result $canonicalWhatIf
    Assert-Equal $canonicalWhatIf.ExitCode 0 "production canonical Snapshot -WhatIf failed: $($canonicalWhatIfResult.reason)"
    Assert-Equal $canonicalWhatIfResult.reason 'whatif' 'production canonical Snapshot did not remain read-only'

    Assert-TamperedStateRejected { param($s) $s.retirement.canonicalDirectiveSha256Evidence = 'tampered' } 'invalid-retirement-schema' 'retirement identity'
    Assert-TamperedStateRejected { param($s) $s.retirement.desktopFinalProof = 'verified' } 'invalid-retirement-projection' 'nested retirement proof'
    Assert-TamperedStateRejected { param($s) $s.desktopFinalProof = 'verified' } 'invalid-state-proof' 'top-level proof'
    Assert-TamperedStateRejected { param($s) $s.retirement.deleteAuthorized = $true } 'frozen-retirement-projection-mismatch' 'nested delete authorization'
    Assert-TamperedStateRejected { param($s) $s.deleteAuthorized = $true } 'invalid-state-schema' 'top-level delete authorization'
    Assert-TamperedStateRejected { param($s) $s.retirement.items[0].status = 'pending' } 'frozen-retirement-projection-mismatch' 'retirement item status'
    Assert-TamperedStateRejected {
        param($s)
        $topic = 'consolidated-' + ([string]$s.canonicalMarkdownSha256).Substring(0, 12).ToLowerInvariant() + '-WU-COMPOSE-A'
        $s.workUnits[0] | Add-Member transition ([pscustomobject][ordered]@{
            phase = 'begin-cleanup-owned'; runId = 'legacy-cleanup'; owner = 'desktop'; leaseTopic = $topic
            canonicalMarkdownSha256 = [string]$s.canonicalMarkdownSha256; claimId = 'c' * 32
        })
    } 'invalid-work-unit-contract' 'non-contract begin phase'
    Assert-TamperedStateRejected {
        param($s)
        $s.workUnits[0].status = 'hold'
        $s.workUnits[0] | Add-Member approvalGate ([pscustomobject][ordered]@{
            schemaVersion = 'awx.notebook.directive.approval-gate.v1'; requiredForBegin = $true; acceptAny = @('explicitApproval')
        })
    } 'frozen-projection-mismatch' 'state-only approval gate'
    Assert-OriginalHashesUnchanged
    Write-Output "PASS: $script:assertions assertions"
} finally {
    try { Assert-OriginalHashesUnchanged } finally {
        try {
            foreach ($workerProcess in $script:workerProcesses) {
                try {
                    if (-not $workerProcess.HasExited) { $workerProcess.Kill() }
                    $null = $workerProcess.WaitForExit(5000)
                } catch {} finally { try { $workerProcess.Dispose() } catch {} }
            }
        } finally {
            for ($index = $script:fixtureRoots.Count - 1; $index -ge 0; $index--) {
                Remove-SafeFixture $script:fixtureRoots[$index]
            }
        }
    }
}
