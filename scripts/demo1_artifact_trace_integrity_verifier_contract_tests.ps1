[CmdletBinding()]
param(
    [ValidateSet('Envelope', 'Compare', 'Publication', 'Harness', 'All')]
    [string]$Group = 'All'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$script:Passed = 0
$script:Failed = 0
$script:Skipped = 0
$script:Failures = New-Object System.Collections.Generic.List[string]
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$captureScript = Join-Path $repoRoot '.agents\skills\demo1-artifact-trace-curator\scripts\new_artifact_trace_manifest.ps1'
$verifyScript = Join-Path $repoRoot '.agents\skills\demo1-artifact-trace-curator\scripts\test_artifact_trace_manifest.ps1'
$harnessPath = Join-Path $repoRoot 'agent-prompts\agents\demo1_artifact_trace_integrity_harness\system_ko.md'
$harnessMetaPath = Join-Path $repoRoot 'agent-prompts\agents\demo1_artifact_trace_integrity_harness\meta.yaml'
$promptManifestPath = Join-Path $repoRoot 'agent-prompts\prompts.manifest.yaml'
$promptBuildPath = Join-Path $repoRoot 'agent-prompts\build.py'
$promptOutputPath = Join-Path $repoRoot 'agent-prompts\out\demo1_artifact_trace_integrity_harness.prompt'
$pressureEvidencePath = Join-Path $repoRoot 'docs\superpowers\evidence\2026-07-30-artifact-trace-integrity-harness-pressure-tests.md'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) (
    'awx-artifact-trace-verify-' + [guid]::NewGuid().ToString('N')
)
$utf8NoBom = New-Object Text.UTF8Encoding($false)

function Write-TestResult {
    param([string]$Name, [bool]$Passed, [string]$Reason)
    if ($Passed) {
        $script:Passed++
        Write-Host ('[artifact-trace-verify-test][PASS] ' + $Name + ' reason=ok')
        return
    }
    $script:Failed++
    $script:Failures.Add($Name + ':' + $Reason)
    Write-Host ('[artifact-trace-verify-test][FAIL] ' + $Name + ' reason=' + $Reason)
}

function Write-TestSkipped {
    param([string]$Name, [string]$Reason)
    $script:Skipped++
    Write-Host ('[artifact-trace-verify-test][SKIP] ' + $Name + ' reason=' + $Reason)
}

function Assert-True {
    param([string]$Name, [bool]$Condition, [string]$Reason)
    Write-TestResult -Name $Name -Passed $Condition -Reason $Reason
}

function Assert-Equal {
    param([string]$Name, $Actual, $Expected)
    Write-TestResult `
        -Name $Name `
        -Passed ([object]::Equals($Actual, $Expected)) `
        -Reason ('expected=' + [string]$Expected + ',actual=' + [string]$Actual)
}

function Assert-Contains {
    param([string]$Name, $Values, [string]$Expected)
    Write-TestResult `
        -Name $Name `
        -Passed (@($Values) -contains $Expected) `
        -Reason ('missing=' + $Expected)
}

function Write-TextFixture {
    param([string]$Path, [string]$Value)
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, $utf8NoBom)
}

function Get-ShaLower {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-ShaLowerText {
    param([string]$Value)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($utf8NoBom.GetBytes($Value)))).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Write-EnvelopeBinding {
    param([string]$TraceFull)
    $manifestPath = Join-Path $TraceFull 'manifest.json'
    $manifestSha = Get-ShaLower $manifestPath
    Write-TextFixture `
        -Path (Join-Path $TraceFull 'manifest.sha256') `
        -Value ($manifestSha + '  manifest.json' + [Environment]::NewLine)
    Write-TextFixture `
        -Path (Join-Path $TraceFull '.ready') `
        -Value (
            'schemaVersion=awx.artifact_trace_ready.v1' + [Environment]::NewLine +
            'manifestSha256=' + $manifestSha + [Environment]::NewLine
        )
    return $manifestSha
}

function Set-ManifestFixture {
    param($Fixture, [scriptblock]$Mutation)
    $manifestPath = Join-Path $Fixture.traceFull 'manifest.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    & $Mutation $manifest
    $json = $manifest | ConvertTo-Json -Depth 30
    [IO.File]::WriteAllText($manifestPath, $json, $utf8NoBom)
    return Write-EnvelopeBinding -TraceFull $Fixture.traceFull
}

function New-CapturedTrace {
    param(
        [string]$Case,
        [hashtable]$Files = @{ 'sample.txt' = 'alpha' }
    )
    $caseRoot = Join-Path $testRoot $Case
    New-Item -ItemType Directory -Path $caseRoot -Force | Out-Null
    $inputRelative = 'data\agent-handoff\fixture-' + $Case
    $inputFull = Join-Path $caseRoot $inputRelative
    New-Item -ItemType Directory -Path $inputFull -Force | Out-Null
    foreach ($name in $Files.Keys) {
        Write-TextFixture -Path (Join-Path $inputFull $name) -Value ([string]$Files[$name])
    }
    $traceId = ('trace-' + $Case).ToLowerInvariant() -replace '[^a-z0-9-]', '-'
    $captureJson = & $captureScript `
        -Root $caseRoot `
        -TraceId $traceId `
        -InputRoot $inputRelative
    $captureResult = $captureJson | ConvertFrom-Json
    $traceRelative = 'data\agent-handoff\artifact-trace\' + $traceId
    $traceFull = Join-Path $caseRoot $traceRelative
    return [pscustomobject]@{
        root = $caseRoot
        inputRelative = $inputRelative
        inputFull = $inputFull
        traceId = $traceId
        traceRelative = $traceRelative
        traceFull = $traceFull
        expectedManifestSha256 = [string]$captureResult.manifestSha256
    }
}

function Invoke-Verifier {
    param(
        $Fixture,
        [string]$VerificationId,
        [string]$ExpectedManifestSha256 = '',
        [hashtable]$Extra = @{}
    )
    if ([string]::IsNullOrWhiteSpace($ExpectedManifestSha256)) {
        $ExpectedManifestSha256 = $Fixture.expectedManifestSha256
    }
    $parameters = @{
        Root = $Fixture.root
        TraceDirectory = $Fixture.traceRelative
        ExpectedManifestSha256 = $ExpectedManifestSha256
        VerificationId = $VerificationId
    }
    foreach ($key in $Extra.Keys) { $parameters[$key] = $Extra[$key] }
    $resultJson = & $verifyScript @parameters
    $result = $resultJson | ConvertFrom-Json
    $packetPath = Join-Path $Fixture.root (
        'data\agent-handoff\artifact-trace-verification\' +
        $VerificationId + '\verification.json'
    )
    if (-not (Test-Path -LiteralPath $packetPath -PathType Leaf)) {
        throw 'verification-packet-missing'
    }
    return [pscustomobject]@{
        result = $result
        packetPath = $packetPath
        packet = Get-Content -LiteralPath $packetPath -Raw -Encoding UTF8 | ConvertFrom-Json
        outputDirectory = Split-Path -Parent $packetPath
    }
}

function Assert-ThrowsReason {
    param([string]$Name, [scriptblock]$Action, [string]$Expected)
    $reason = 'no-error'
    try { & $Action } catch { $reason = [string]$_.Exception.Message }
    Write-TestResult `
        -Name $Name `
        -Passed ($reason -like ('*' + $Expected + '*')) `
        -Reason ('expected=' + $Expected + ',actual=' + $reason)
}

function Get-DirectoryFingerprint {
    param([string]$Directory)
    $rows = @()
    foreach ($item in @(Get-ChildItem -LiteralPath $Directory -File -Force -Recurse | Sort-Object FullName)) {
        $relative = $item.FullName.Substring($Directory.Length).TrimStart('\', '/').Replace('\', '/')
        $rows += (
            $relative + "`t" + [string]$item.Length + "`t" +
            $item.LastWriteTimeUtc.Ticks + "`t" + (Get-ShaLower $item.FullName)
        )
    }
    $text = $rows -join "`n"
    $bytes = $utf8NoBom.GetBytes($text)
    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Invoke-VerifierFailure {
    param(
        $Fixture,
        [string]$VerificationId,
        [hashtable]$Extra = @{}
    )
    $arguments = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $verifyScript,
        '-Root', $Fixture.root,
        '-TraceDirectory', $Fixture.traceRelative,
        '-ExpectedManifestSha256', $Fixture.expectedManifestSha256,
        '-VerificationId', $VerificationId
    )
    foreach ($key in $Extra.Keys) {
        $arguments += '-' + $key
        $arguments += [string]$Extra[$key]
    }
    $priorErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & powershell @arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $priorErrorActionPreference
    }
    return [pscustomobject]@{ exitCode = $exitCode; output = $output }
}

function Assert-InvalidPacket {
    param($Verified, [string]$Name, [string]$Reason)
    Assert-Equal ($Name + ' verdict') $Verified.packet.integrityVerdict 'INVALID'
    Assert-Contains ($Name + ' reason') $Verified.packet.failureClassifications $Reason
    Assert-Equal ($Name + ' mutation false') $Verified.packet.mutationAllowed $false
    Assert-Equal ($Name + ' delete false') $Verified.packet.deleteAuthorized $false
    Assert-Equal ($Name + ' runtime hold') $Verified.packet.runtimeLineageVerdict 'HOLD'
    Assert-Equal ($Name + ' desktop proof needed') $Verified.packet.desktopFinalProof 'evidence_needed'
}

function Test-EnvelopeGroup {
    $fixture = New-CapturedTrace -Case 'envelope-valid'
    $verified = Invoke-Verifier `
        -Fixture $fixture `
        -VerificationId 'envelope-valid'
    Assert-True `
        -Name 'valid authenticated envelope is not invalid' `
        -Condition ($verified.packet.integrityVerdict -ne 'INVALID') `
        -Reason 'valid-envelope-invalid'
    Assert-Equal `
        -Name 'independent manifest anchor preserved' `
        -Actual $verified.packet.expectedManifestSha256 `
        -Expected $fixture.expectedManifestSha256

    $sidecar = New-CapturedTrace -Case 'envelope-sidecar'
    Write-TextFixture `
        -Path (Join-Path $sidecar.traceFull 'manifest.sha256') `
        -Value ((('0' * 64) + '  manifest.json') + [Environment]::NewLine)
    $sidecarVerified = Invoke-Verifier -Fixture $sidecar -VerificationId 'envelope-sidecar'
    Assert-InvalidPacket $sidecarVerified 'sidecar tamper' 'trace-verify-manifest-sha-mismatch'

    $readyMissing = New-CapturedTrace -Case 'envelope-ready-missing'
    Remove-Item -LiteralPath (Join-Path $readyMissing.traceFull '.ready') -Force
    $readyMissingVerified = Invoke-Verifier -Fixture $readyMissing -VerificationId 'envelope-ready-missing'
    Assert-InvalidPacket $readyMissingVerified 'missing ready' 'trace-verify-ready-binding-invalid'

    $readyAltered = New-CapturedTrace -Case 'envelope-ready-altered'
    Write-TextFixture `
        -Path (Join-Path $readyAltered.traceFull '.ready') `
        -Value (
            'schemaVersion=awx.artifact_trace_ready.v1' + [Environment]::NewLine +
            'manifestSha256=' + ('0' * 64) + [Environment]::NewLine
        )
    $readyAlteredVerified = Invoke-Verifier -Fixture $readyAltered -VerificationId 'envelope-ready-altered'
    Assert-InvalidPacket $readyAlteredVerified 'altered ready' 'trace-verify-ready-binding-invalid'

    $extra = New-CapturedTrace -Case 'envelope-extra'
    Write-TextFixture -Path (Join-Path $extra.traceFull 'note.txt') -Value 'not allowed'
    $extraVerified = Invoke-Verifier -Fixture $extra -VerificationId 'envelope-extra'
    Assert-InvalidPacket $extraVerified 'extra envelope member' 'trace-verify-envelope-invalid'

    $rebound = New-CapturedTrace -Case 'envelope-rebound'
    $originalExpected = $rebound.expectedManifestSha256
    [void](Set-ManifestFixture -Fixture $rebound -Mutation {
        param($Manifest)
        $Manifest.mutationAllowed = $true
    })
    $reboundVerified = Invoke-Verifier `
        -Fixture $rebound `
        -VerificationId 'envelope-rebound' `
        -ExpectedManifestSha256 $originalExpected
    Assert-InvalidPacket $reboundVerified 'fully rebound envelope' 'trace-verify-expected-manifest-sha-mismatch'

    $invariant = New-CapturedTrace -Case 'envelope-invariant'
    $reboundExpected = Set-ManifestFixture -Fixture $invariant -Mutation {
        param($Manifest)
        $Manifest.mutationAllowed = $true
    }
    $invariantVerified = Invoke-Verifier `
        -Fixture $invariant `
        -VerificationId 'envelope-invariant' `
        -ExpectedManifestSha256 $reboundExpected
    Assert-InvalidPacket $invariantVerified 'wrong manifest invariant' 'trace-verify-manifest-contract-invalid'

    $traversal = New-CapturedTrace -Case 'envelope-traversal'
    $traversalExpected = Set-ManifestFixture -Fixture $traversal -Mutation {
        param($Manifest)
        $Manifest.artifacts[0].path = '../outside.txt'
    }
    $traversalVerified = Invoke-Verifier `
        -Fixture $traversal `
        -VerificationId 'envelope-traversal' `
        -ExpectedManifestSha256 $traversalExpected
    Assert-InvalidPacket $traversalVerified 'traversal manifest path' 'trace-verify-manifest-contract-invalid'

    $aggregate = New-CapturedTrace -Case 'envelope-aggregate'
    $aggregateExpected = Set-ManifestFixture -Fixture $aggregate -Mutation {
        param($Manifest)
        $Manifest.inventory.fileCount = 99
    }
    $aggregateVerified = Invoke-Verifier `
        -Fixture $aggregate `
        -VerificationId 'envelope-aggregate' `
        -ExpectedManifestSha256 $aggregateExpected
    Assert-InvalidPacket $aggregateVerified 'aggregate mismatch' 'trace-verify-manifest-contract-invalid'

    $duplicate = New-CapturedTrace -Case 'envelope-duplicate-key'
    $duplicatePath = Join-Path $duplicate.traceFull 'manifest.json'
    $duplicateJson = Get-Content -LiteralPath $duplicatePath -Raw -Encoding UTF8
    $duplicateJson = $duplicateJson -replace '^\{', '{"SchemaVersion":"shadow",'
    [IO.File]::WriteAllText($duplicatePath, $duplicateJson, $utf8NoBom)
    $duplicateExpected = Write-EnvelopeBinding -TraceFull $duplicate.traceFull
    $duplicateVerified = Invoke-Verifier `
        -Fixture $duplicate `
        -VerificationId 'envelope-duplicate-key' `
        -ExpectedManifestSha256 $duplicateExpected
    Assert-InvalidPacket $duplicateVerified 'duplicate case-varied key' 'trace-verify-manifest-contract-invalid'

    foreach ($pathCase in @(
        [pscustomobject]@{ name = 'ads'; path = 'data/agent-handoff/fixture-envelope-ads/sample.txt:stream' },
        [pscustomobject]@{ name = 'dos'; path = 'data/agent-handoff/fixture-envelope-dos/CON' },
        [pscustomobject]@{ name = 'dot'; path = 'data/agent-handoff/fixture-envelope-dot/sample.txt.' }
    )) {
        $pathFixture = New-CapturedTrace -Case ('envelope-' + $pathCase.name)
        $reboundPathExpected = Set-ManifestFixture -Fixture $pathFixture -Mutation {
            param($Manifest)
            $Manifest.artifacts[0].path = $pathCase.path
        }
        $pathVerified = Invoke-Verifier `
            -Fixture $pathFixture `
            -VerificationId ('envelope-' + $pathCase.name) `
            -ExpectedManifestSha256 $reboundPathExpected
        Assert-InvalidPacket $pathVerified ('Windows ambiguous path ' + $pathCase.name) 'trace-verify-manifest-contract-invalid'
    }

    $caseCollision = New-CapturedTrace -Case 'envelope-case-collision'
    $caseCollisionExpected = Set-ManifestFixture -Fixture $caseCollision -Mutation {
        param($Manifest)
        $copy = $Manifest.artifacts[0] | ConvertTo-Json -Depth 20 | ConvertFrom-Json
        $copy.path = ([string]$copy.path).ToUpperInvariant()
        $Manifest.artifacts = @($Manifest.artifacts[0], $copy)
        $Manifest.inventory.fileCount = 2
        $Manifest.inventory.totalBytes = [long]$Manifest.inventory.totalBytes * 2
    }
    $caseCollisionVerified = Invoke-Verifier `
        -Fixture $caseCollision `
        -VerificationId 'envelope-case-collision' `
        -ExpectedManifestSha256 $caseCollisionExpected
    Assert-InvalidPacket $caseCollisionVerified 'case-fold path collision' 'trace-verify-manifest-contract-invalid'

    $stringBoolean = New-CapturedTrace -Case 'envelope-string-boolean'
    $stringBooleanExpected = Set-ManifestFixture -Fixture $stringBoolean -Mutation {
        param($Manifest)
        $Manifest.mutationAllowed = 'false'
    }
    $stringBooleanVerified = Invoke-Verifier `
        -Fixture $stringBoolean `
        -VerificationId 'envelope-string-boolean' `
        -ExpectedManifestSha256 $stringBooleanExpected
    Assert-InvalidPacket $stringBooleanVerified 'string boolean rejected' 'trace-verify-manifest-contract-invalid'

    $bom = New-CapturedTrace -Case 'envelope-bom'
    $bomPath = Join-Path $bom.traceFull 'manifest.json'
    $plainBytes = [IO.File]::ReadAllBytes($bomPath)
    $bomBytes = New-Object byte[] ($plainBytes.Length + 3)
    $bomBytes[0] = 0xEF; $bomBytes[1] = 0xBB; $bomBytes[2] = 0xBF
    [Array]::Copy($plainBytes, 0, $bomBytes, 3, $plainBytes.Length)
    [IO.File]::WriteAllBytes($bomPath, $bomBytes)
    $bomExpected = Write-EnvelopeBinding -TraceFull $bom.traceFull
    $bomVerified = Invoke-Verifier `
        -Fixture $bom `
        -VerificationId 'envelope-bom' `
        -ExpectedManifestSha256 $bomExpected
    Assert-InvalidPacket $bomVerified 'manifest BOM rejected' 'trace-verify-manifest-contract-invalid'

    $invalidUtf8 = New-CapturedTrace -Case 'envelope-invalid-utf8'
    $invalidUtf8Path = Join-Path $invalidUtf8.traceFull 'manifest.json'
    $invalidUtf8Bytes = [IO.File]::ReadAllBytes($invalidUtf8Path)
    $invalidUtf8Bytes[0] = 0xFF
    [IO.File]::WriteAllBytes($invalidUtf8Path, $invalidUtf8Bytes)
    $invalidUtf8Expected = Write-EnvelopeBinding -TraceFull $invalidUtf8.traceFull
    $invalidUtf8Verified = Invoke-Verifier `
        -Fixture $invalidUtf8 `
        -VerificationId 'envelope-invalid-utf8' `
        -ExpectedManifestSha256 $invalidUtf8Expected
    Assert-InvalidPacket $invalidUtf8Verified 'malformed UTF-8 rejected' 'trace-verify-manifest-contract-invalid'

    $nul = New-CapturedTrace -Case 'envelope-nul'
    $nulPath = Join-Path $nul.traceFull 'manifest.json'
    $nulBytes = [IO.File]::ReadAllBytes($nulPath)
    $nulExpanded = New-Object byte[] ($nulBytes.Length + 1)
    [Array]::Copy($nulBytes, $nulExpanded, $nulBytes.Length)
    $nulExpanded[$nulExpanded.Length - 1] = 0
    [IO.File]::WriteAllBytes($nulPath, $nulExpanded)
    $nulExpected = Write-EnvelopeBinding -TraceFull $nul.traceFull
    $nulVerified = Invoke-Verifier `
        -Fixture $nul `
        -VerificationId 'envelope-nul' `
        -ExpectedManifestSha256 $nulExpected
    Assert-InvalidPacket $nulVerified 'manifest NUL rejected' 'trace-verify-manifest-contract-invalid'

    $deep = New-CapturedTrace -Case 'envelope-deep-json'
    $deepPath = Join-Path $deep.traceFull 'manifest.json'
    $deepJson = ('{"a":' * 31) + '0' + ('}' * 31)
    [IO.File]::WriteAllText($deepPath, $deepJson, $utf8NoBom)
    $deepExpected = Write-EnvelopeBinding -TraceFull $deep.traceFull
    $deepVerified = Invoke-Verifier `
        -Fixture $deep `
        -VerificationId 'envelope-deep-json' `
        -ExpectedManifestSha256 $deepExpected
    Assert-InvalidPacket $deepVerified 'deep JSON rejected' 'trace-verify-manifest-contract-invalid'

    $extraSidecar = New-CapturedTrace -Case 'envelope-sidecar-extra'
    $extraSidecarPath = Join-Path $extraSidecar.traceFull 'manifest.sha256'
    [IO.File]::AppendAllText($extraSidecarPath, 'extra' + [Environment]::NewLine, $utf8NoBom)
    $extraSidecarVerified = Invoke-Verifier -Fixture $extraSidecar -VerificationId 'envelope-sidecar-extra'
    Assert-InvalidPacket $extraSidecarVerified 'extra sidecar line rejected' 'trace-verify-manifest-sha-mismatch'

    $contractMutations = @(
        [pscustomobject]@{ name = 'generated'; mutate = { param($Manifest) $Manifest.generatedAtUtc = 'not-a-time' } },
        [pscustomobject]@{ name = 'limits'; mutate = { param($Manifest) $Manifest.limits.maxFiles = '512' } },
        [pscustomobject]@{ name = 'proof'; mutate = { param($Manifest) $Manifest.artifacts[0].proofStatus = 'approved' } },
        [pscustomobject]@{ name = 'scope-denial'; mutate = { param($Manifest) $Manifest.artifacts[0].proofStatus = 'structurally_bound'; $Manifest.artifacts[0].proofScope = 'source_patch' } },
        [pscustomobject]@{ name = 'claims'; mutate = { param($Manifest) $Manifest.artifacts[0].allowedClaims = 'runtime_success' } },
        [pscustomobject]@{ name = 'claim-array'; mutate = { param($Manifest) $Manifest.artifacts[0].allowedClaims = @('runtime_success') } },
        [pscustomobject]@{ name = 'empty-denials'; mutate = { param($Manifest) $Manifest.artifacts[0].notProofOf = @() } },
        [pscustomobject]@{ name = 'source-sha'; mutate = { param($Manifest) $Manifest.artifacts[0].sourceVerdict.recordSha256 = ('0' * 64) } },
        [pscustomobject]@{ name = 'failures'; mutate = { param($Manifest) $Manifest.failureClassifications = @('forged-reason') } }
    )
    foreach ($contractCase in $contractMutations) {
        $contractFixture = New-CapturedTrace -Case ('field-' + $contractCase.name)
        $contractExpected = Set-ManifestFixture -Fixture $contractFixture -Mutation $contractCase.mutate
        $contractVerified = Invoke-Verifier `
            -Fixture $contractFixture `
            -VerificationId ('field-' + $contractCase.name) `
            -ExpectedManifestSha256 $contractExpected
        Assert-InvalidPacket $contractVerified ('manifest field ' + $contractCase.name) 'trace-verify-manifest-contract-invalid'
    }

    $oversizedSidecar = New-CapturedTrace -Case 'envelope-sidecar-oversized'
    Write-TextFixture `
        -Path (Join-Path $oversizedSidecar.traceFull 'manifest.sha256') `
        -Value ('x' * 8192)
    $oversizedFailure = Invoke-VerifierFailure `
        -Fixture $oversizedSidecar `
        -VerificationId 'envelope-sidecar-oversized'
    $oversizedCompact = $oversizedFailure.output -replace '\s', ''
    Assert-True 'oversized sidecar exits nonzero' ($oversizedFailure.exitCode -ne 0) 'oversized-sidecar-exit-zero'
    Assert-True 'oversized sidecar is budget failure' ($oversizedCompact -like '*trace-verify-budget-exceeded*') 'oversized-sidecar-budget-reason-missing'
    Assert-True `
        'oversized sidecar publishes no packet' `
        (-not (Test-Path -LiteralPath (Join-Path $oversizedSidecar.root 'data\agent-handoff\artifact-trace-verification\envelope-sidecar-oversized'))) `
        'oversized-sidecar-packet-present'

    $reparse = New-CapturedTrace -Case 'envelope-reparse'
    $reparseTarget = Join-Path $reparse.root 'outside-reparse-target'
    New-Item -ItemType Directory -Path $reparseTarget -Force | Out-Null
    Write-TextFixture -Path (Join-Path $reparseTarget 'outside.txt') -Value 'outside'
    $reparsePath = Join-Path $reparse.inputFull 'linked'
    try {
        New-Item -ItemType Junction -Path $reparsePath -Target $reparseTarget -ErrorAction Stop | Out-Null
        $reparseFailure = Invoke-VerifierFailure `
            -Fixture $reparse `
            -VerificationId 'envelope-reparse'
        $reparseCompact = $reparseFailure.output -replace '\s', ''
        Assert-True 'reparse traversal exits nonzero' ($reparseFailure.exitCode -ne 0) 'reparse-exit-zero'
        Assert-True 'reparse traversal reason' ($reparseCompact -like '*trace-verify-reparse-risk*') 'reparse-reason-missing'
        Assert-True `
            'reparse traversal publishes no packet' `
            (-not (Test-Path -LiteralPath (Join-Path $reparse.root 'data\agent-handoff\artifact-trace-verification\envelope-reparse'))) `
            'reparse-packet-present'
    } catch {
        Write-TestSkipped 'reparse traversal fixture' ('junction-unavailable:' + [string]$_.Exception.Message)
    }

    $envelopeReparse = New-CapturedTrace -Case 'envelope-member-reparse'
    $envelopeReparseTarget = Join-Path $envelopeReparse.root 'envelope-member-target'
    New-Item -ItemType Directory -Path $envelopeReparseTarget -Force | Out-Null
    Remove-Item -LiteralPath (Join-Path $envelopeReparse.traceFull '.ready') -Force
    try {
        New-Item `
            -ItemType Junction `
            -Path (Join-Path $envelopeReparse.traceFull '.ready') `
            -Target $envelopeReparseTarget `
            -ErrorAction Stop | Out-Null
        $envelopeReparseFailure = Invoke-VerifierFailure `
            -Fixture $envelopeReparse `
            -VerificationId 'envelope-member-reparse'
        $envelopeReparseCompact = $envelopeReparseFailure.output -replace '\s', ''
        Assert-True 'envelope member reparse exits nonzero' ($envelopeReparseFailure.exitCode -ne 0) 'envelope-reparse-exit-zero'
        Assert-True 'envelope member reparse reason' ($envelopeReparseCompact -like '*trace-verify-reparse-risk*') 'envelope-reparse-reason-missing'
        Assert-True `
            'envelope member reparse publishes no packet' `
            (-not (Test-Path -LiteralPath (Join-Path $envelopeReparse.root 'data\agent-handoff\artifact-trace-verification\envelope-member-reparse'))) `
            'envelope-reparse-packet-present'
    } catch {
        Write-TestSkipped 'envelope member reparse fixture' ('junction-unavailable:' + [string]$_.Exception.Message)
    }

    $outputInput = New-CapturedTrace -Case 'output-input'
    $verificationInput = Join-Path $outputInput.root 'data\agent-handoff\artifact-trace-verification'
    New-Item -ItemType Directory -Path $verificationInput -Force | Out-Null
    $outputInputExpected = Set-ManifestFixture -Fixture $outputInput -Mutation {
        param($Manifest)
        $Manifest.inputRoot = 'data/agent-handoff/artifact-trace-verification'
        $Manifest.inputRootHash = Get-ShaLowerText $Manifest.inputRoot
        $Manifest.inventory.fileCount = 0
        $Manifest.inventory.totalBytes = 0
        $Manifest.artifacts = @()
    }
    $outputInput.expectedManifestSha256 = $outputInputExpected
    $outputInputFailure = Invoke-VerifierFailure -Fixture $outputInput -VerificationId 'reject-output-input'
    $outputInputCompact = $outputInputFailure.output -replace '\s', ''
    Assert-True 'verification output root input exits nonzero' ($outputInputFailure.exitCode -ne 0) 'output-input-exit-zero'
    Assert-True 'verification output root input reason' ($outputInputCompact -like '*trace-verify-source-not-allowlisted*') 'output-input-reason-missing'
    Assert-True 'verification output root input publishes no packet' (-not (Test-Path -LiteralPath (Join-Path $verificationInput 'reject-output-input'))) 'output-input-packet-present'
}

function Assert-CountConservation {
    param($Packet, [string]$Name)
    $expectedEquation = (
        [int]$Packet.unchangedCount + [int]$Packet.changedCount +
        [int]$Packet.metadataOnlyChangedCount + [int]$Packet.missingCount
    )
    $currentEquation = (
        [int]$Packet.unchangedCount + [int]$Packet.changedCount +
        [int]$Packet.metadataOnlyChangedCount + [int]$Packet.addedCount
    )
    Assert-Equal ($Name + ' expected count equation') $expectedEquation ([int]$Packet.expectedFileCount)
    Assert-Equal ($Name + ' current count equation') $currentEquation ([int]$Packet.currentFileCount)
}

function Test-CompareGroup {
    $unchanged = New-CapturedTrace -Case 'compare-unchanged'
    $unchangedVerified = Invoke-Verifier -Fixture $unchanged -VerificationId 'compare-unchanged'
    Assert-Equal 'unchanged verdict' $unchangedVerified.packet.integrityVerdict 'UNCHANGED'
    Assert-Equal 'unchanged count' ([int]$unchangedVerified.packet.unchangedCount) 1
    Assert-Equal 'unchanged capture atomicity unproven' $unchangedVerified.packet.captureSnapshotAtomicity 'unproven'
    Assert-Contains 'unchanged bounded claim' $unchangedVerified.packet.allowedClaims 'bounded_recorded_row_set_match_observed'
    Assert-Contains 'unchanged denies freshness' $unchangedVerified.packet.notProofOf 'freshness'
    Assert-Contains 'unchanged denies capture atomicity' $unchangedVerified.packet.notProofOf 'capture_atomicity'
    Assert-Equal 'unchanged change array is empty' @($unchangedVerified.packet.changes).Count 0
    $unchangedPacketText = Get-Content -LiteralPath $unchangedVerified.packetPath -Raw -Encoding UTF8
    Assert-True 'unchanged changes JSON type is array' ($unchangedPacketText -match '"changes"\s*:\s*\[') 'changes-not-json-array'
    Assert-CountConservation $unchangedVerified.packet 'unchanged'

    $content = New-CapturedTrace -Case 'compare-content'
    Write-TextFixture -Path (Join-Path $content.inputFull 'sample.txt') -Value 'bravo'
    $contentVerified = Invoke-Verifier -Fixture $content -VerificationId 'compare-content'
    Assert-Equal 'content changed verdict' $contentVerified.packet.integrityVerdict 'CHANGED'
    Assert-Equal 'content changed count' ([int]$contentVerified.packet.changedCount) 1
    Assert-Equal 'content metadata count zero' ([int]$contentVerified.packet.metadataOnlyChangedCount) 0
    Assert-Equal 'content change kind' $contentVerified.packet.changes[0].changeKind 'content_changed'
    Assert-CountConservation $contentVerified.packet 'content changed'

    $aba = New-CapturedTrace -Case 'compare-aba'
    $abaPath = Join-Path $aba.inputFull 'sample.txt'
    $abaTime = (Get-Item -LiteralPath $abaPath).LastWriteTimeUtc
    Write-TextFixture -Path $abaPath -Value 'omega'
    [IO.File]::SetLastWriteTimeUtc($abaPath, $abaTime)
    $abaVerified = Invoke-Verifier -Fixture $aba -VerificationId 'compare-aba'
    Assert-Equal 'same-size restored-time verdict' $abaVerified.packet.integrityVerdict 'CHANGED'
    Assert-Equal 'same-size restored-time content count' ([int]$abaVerified.packet.changedCount) 1

    $metadata = New-CapturedTrace -Case 'compare-metadata'
    $metadataPath = Join-Path $metadata.inputFull 'sample.txt'
    $later = (Get-Item -LiteralPath $metadataPath).LastWriteTimeUtc.AddMinutes(5)
    [IO.File]::SetLastWriteTimeUtc($metadataPath, $later)
    $metadataVerified = Invoke-Verifier -Fixture $metadata -VerificationId 'compare-metadata'
    Assert-Equal 'metadata-only verdict' $metadataVerified.packet.integrityVerdict 'CHANGED'
    Assert-Equal 'metadata-only count' ([int]$metadataVerified.packet.metadataOnlyChangedCount) 1
    Assert-Equal 'metadata-only content count zero' ([int]$metadataVerified.packet.changedCount) 0
    Assert-Equal 'metadata-only kind' $metadataVerified.packet.changes[0].changeKind 'metadata_only'
    Assert-CountConservation $metadataVerified.packet 'metadata only'

    $missing = New-CapturedTrace -Case 'compare-missing'
    Remove-Item -LiteralPath (Join-Path $missing.inputFull 'sample.txt') -Force
    $missingVerified = Invoke-Verifier -Fixture $missing -VerificationId 'compare-missing'
    Assert-Equal 'missing verdict' $missingVerified.packet.integrityVerdict 'CHANGED'
    Assert-Equal 'missing count' ([int]$missingVerified.packet.missingCount) 1
    Assert-Equal 'missing kind' $missingVerified.packet.changes[0].changeKind 'missing'
    Assert-CountConservation $missingVerified.packet 'missing'

    $added = New-CapturedTrace -Case 'compare-added'
    Write-TextFixture -Path (Join-Path $added.inputFull 'second.txt') -Value 'second'
    $addedVerified = Invoke-Verifier -Fixture $added -VerificationId 'compare-added'
    Assert-Equal 'added verdict' $addedVerified.packet.integrityVerdict 'CHANGED'
    Assert-Equal 'added count' ([int]$addedVerified.packet.addedCount) 1
    Assert-Contains 'added kind present' @($addedVerified.packet.changes.changeKind) 'added'
    Assert-CountConservation $addedVerified.packet 'added'

    $mixed = New-CapturedTrace -Case 'compare-mixed' -Files @{
        'content.txt' = 'alpha'
        'missing.txt' = 'remove-me'
        'same.txt' = 'same'
    }
    Write-TextFixture -Path (Join-Path $mixed.inputFull 'content.txt') -Value 'bravo'
    Remove-Item -LiteralPath (Join-Path $mixed.inputFull 'missing.txt') -Force
    Write-TextFixture -Path (Join-Path $mixed.inputFull 'added.txt') -Value 'new'
    $mixedVerified = Invoke-Verifier -Fixture $mixed -VerificationId 'compare-mixed'
    Assert-Equal 'mixed verdict' $mixedVerified.packet.integrityVerdict 'CHANGED'
    Assert-Equal 'mixed unchanged count' ([int]$mixedVerified.packet.unchangedCount) 1
    Assert-Equal 'mixed content count' ([int]$mixedVerified.packet.changedCount) 1
    Assert-Equal 'mixed missing count' ([int]$mixedVerified.packet.missingCount) 1
    Assert-Equal 'mixed added count' ([int]$mixedVerified.packet.addedCount) 1
    Assert-CountConservation $mixedVerified.packet 'mixed'
    $mixedJson = Get-Content -LiteralPath $mixedVerified.packetPath -Raw -Encoding UTF8
    Assert-True 'change rows omit raw content' (-not $mixedJson.Contains('remove-me')) 'raw-content-leaked'

    $manyFiles = @{}
    for ($index = 0; $index -lt 140; $index++) {
        $manyFiles[('row-{0:d3}.txt' -f $index)] = 'before'
    }
    $many = New-CapturedTrace -Case 'compare-many-rows' -Files $manyFiles
    foreach ($path in @(Get-ChildItem -LiteralPath $many.inputFull -File)) {
        Write-TextFixture -Path $path.FullName -Value 'after!'
    }
    $manyVerified = Invoke-Verifier -Fixture $many -VerificationId 'compare-many-rows'
    Assert-Equal 'change rows are not fixed at 128' @($manyVerified.packet.changes).Count 140
    Assert-Equal 'all fitting change rows are retained' $manyVerified.packet.changesTruncated $false
    Assert-Equal 'all fitting change rows omit none' ([int]$manyVerified.packet.omittedChangeCount) 0

    $phase = New-CapturedTrace -Case 'compare-phase'
    $phaseScript = Join-Path $phase.root 'phase-verifier.ps1'
    $verifierSource = Get-Content -LiteralPath $verifyScript -Raw -Encoding UTF8
    $phaseMarker = '        Start-Sleep -Milliseconds 5'
    Assert-Equal 'phase barrier insertion point unique' ([regex]::Matches($verifierSource, [regex]::Escape($phaseMarker)).Count) 1
    $phaseReplacement = @'
        Start-Sleep -Milliseconds 5
        $phaseMutationPath = Join-Path $envelope.inputFull 'sample.txt'
        $phaseMutationTime = (Get-Item -LiteralPath $phaseMutationPath).LastWriteTimeUtc
        [IO.File]::WriteAllText($phaseMutationPath, 'omega', $utf8NoBom)
        [IO.File]::SetLastWriteTimeUtc($phaseMutationPath, $phaseMutationTime)
'@
    $phaseSource = $verifierSource.Replace($phaseMarker, $phaseReplacement.TrimEnd("`r", "`n"))
    [IO.File]::WriteAllText($phaseScript, $phaseSource, $utf8NoBom)
    $savedVerifierScript = $script:verifyScript
    try {
        $script:verifyScript = $phaseScript
        $phaseVerified = Invoke-Verifier -Fixture $phase -VerificationId 'compare-phase'
    } finally {
        $script:verifyScript = $savedVerifierScript
    }
    Assert-Equal 'deterministic between-pass ABA indeterminate' $phaseVerified.packet.integrityVerdict 'INDETERMINATE'
    Assert-Contains 'deterministic ABA unstable reason' $phaseVerified.packet.failureClassifications 'trace-verify-input-unstable'

    $unstable = New-CapturedTrace -Case 'compare-continuous-race' -Files @{
        'race.bin' = ('a' * 1048576)
        'padding-01.bin' = ('p' * 1048576)
        'padding-02.bin' = ('q' * 1048576)
        'padding-03.bin' = ('r' * 1048576)
    }
    $racePath = Join-Path $unstable.inputFull 'race.bin'
    $raceTime = (Get-Item -LiteralPath $racePath).LastWriteTimeUtc
    $raceMarker = Join-Path $unstable.root 'race-writer-started.marker'
    $worker = [PowerShell]::Create()
    [void]$worker.AddScript({
        param($Path, $Ticks, $Marker)
        $until = [DateTime]::UtcNow.AddSeconds(10)
        $counter = [long]0
        $bytes = New-Object byte[] 1048576
        for ($index = 0; $index -lt $bytes.Length; $index++) { $bytes[$index] = [byte][char]'a' }
        while ([DateTime]::UtcNow -lt $until) {
            try {
                [BitConverter]::GetBytes($counter).CopyTo($bytes, 0)
                [IO.File]::WriteAllBytes($Path, $bytes)
                [IO.File]::SetLastWriteTimeUtc($Path, [DateTime]::new($Ticks, [DateTimeKind]::Utc))
                if (-not [IO.File]::Exists($Marker)) { [IO.File]::WriteAllText($Marker, 'started') }
                $counter++
            } catch {}
        }
    }).AddArgument($racePath).AddArgument($raceTime.Ticks).AddArgument($raceMarker)
    $async = $worker.BeginInvoke()
    $raceWriterReady = $false
    for ($attempt = 0; $attempt -lt 250; $attempt++) {
        if (Test-Path -LiteralPath $raceMarker -PathType Leaf) { $raceWriterReady = $true; break }
        Start-Sleep -Milliseconds 20
    }
    Assert-True 'continuous race writer started' $raceWriterReady 'writer-start-timeout'
    try {
        if ($raceWriterReady) {
            $unstableVerified = Invoke-Verifier -Fixture $unstable -VerificationId 'compare-continuous-race'
            Assert-True `
                -Name 'continuous same-size race never unchanged' `
                -Condition ($unstableVerified.packet.integrityVerdict -in @('CHANGED', 'INDETERMINATE')) `
                -Reason ('actual=' + $unstableVerified.packet.integrityVerdict)
        }
    } finally {
        try { $worker.Stop() } catch {}
        $worker.Dispose()
    }
}

function Test-PublicationGroup {
    $fixture = New-CapturedTrace -Case 'publish-valid' -Files @{
        'one.txt' = 'one'
        'two.txt' = 'two'
    }
    $traceBefore = Get-DirectoryFingerprint $fixture.traceFull
    $inputBefore = Get-DirectoryFingerprint $fixture.inputFull
    $finalOutput = Join-Path $fixture.root 'data\agent-handoff\artifact-trace-verification\publish-valid'
    $watchSeen = Join-Path $fixture.root 'watch-seen.marker'
    $watchViolation = Join-Path $fixture.root 'watch-violation.marker'
    $watcher = [PowerShell]::Create()
    [void]$watcher.AddScript({
        param($Final, $Seen, $Violation)
        $until = [DateTime]::UtcNow.AddSeconds(5)
        while ([DateTime]::UtcNow -lt $until) {
            if ([IO.Directory]::Exists($Final)) {
                $names = @([IO.Directory]::GetFileSystemEntries($Final) | ForEach-Object { [IO.Path]::GetFileName($_) })
                if ($names.Count -ne 3 -or
                    -not ($names -ccontains 'verification.json') -or
                    -not ($names -ccontains 'verification.sha256') -or
                    -not ($names -ccontains '.ready')) {
                    [IO.File]::WriteAllText($Violation, ($names -join ','))
                }
                [IO.File]::WriteAllText($Seen, 'seen')
                return
            }
            Start-Sleep -Milliseconds 1
        }
    }).AddArgument($finalOutput).AddArgument($watchSeen).AddArgument($watchViolation)
    $watchAsync = $watcher.BeginInvoke()
    try {
        $verified = Invoke-Verifier -Fixture $fixture -VerificationId 'publish-valid'
        for ($attempt = 0; $attempt -lt 100; $attempt++) {
            if (Test-Path -LiteralPath $watchSeen -PathType Leaf) { break }
            Start-Sleep -Milliseconds 10
        }
    } finally {
        try { $watcher.Stop() } catch {}
        $watcher.Dispose()
    }

    Assert-True 'watcher observed final packet' (Test-Path -LiteralPath $watchSeen -PathType Leaf) 'watcher-timeout'
    Assert-True 'watcher never saw incomplete final packet' (-not (Test-Path -LiteralPath $watchViolation)) 'incomplete-final-visible'
    $members = @(Get-ChildItem -LiteralPath $verified.outputDirectory -Force)
    Assert-Equal 'verification packet exact member count' $members.Count 3
    foreach ($name in @('verification.json', 'verification.sha256', '.ready')) {
        Assert-Contains ('verification packet has ' + $name) @($members.Name) $name
    }
    $packetSha = Get-ShaLower $verified.packetPath
    Assert-Equal 'result sha binds packet' $verified.result.verificationSha256 $packetSha
    $sidecarText = Get-Content -LiteralPath (Join-Path $verified.outputDirectory 'verification.sha256') -Raw -Encoding UTF8
    Assert-Equal 'verification sidecar exact binding' $sidecarText ($packetSha + '  verification.json' + [Environment]::NewLine)
    $readyText = Get-Content -LiteralPath (Join-Path $verified.outputDirectory '.ready') -Raw -Encoding UTF8
    Assert-Equal 'verification ready exact binding' $readyText (
        'schemaVersion=awx.artifact_trace_verification_ready.v1' + [Environment]::NewLine +
        'verificationSha256=' + $packetSha + [Environment]::NewLine
    )
    $jsonTime = (Get-Item -LiteralPath $verified.packetPath).LastWriteTimeUtc
    $sidecarTime = (Get-Item -LiteralPath (Join-Path $verified.outputDirectory 'verification.sha256')).LastWriteTimeUtc
    $readyTime = (Get-Item -LiteralPath (Join-Path $verified.outputDirectory '.ready')).LastWriteTimeUtc
    Assert-True 'ready created last in staging' ($readyTime -ge $jsonTime -and $readyTime -ge $sidecarTime) 'ready-not-last'
    Assert-Equal 'trace envelope unchanged by verifier' (Get-DirectoryFingerprint $fixture.traceFull) $traceBefore
    Assert-Equal 'input unchanged by verifier' (Get-DirectoryFingerprint $fixture.inputFull) $inputBefore
    Assert-Equal 'publication overall verdict' $verified.packet.overallVerdict 'VERIFICATION_ONLY'
    Assert-Equal 'publication mutation false' $verified.packet.mutationAllowed $false
    Assert-Equal 'publication delete false' $verified.packet.deleteAuthorized $false

    $outputBeforeCollision = Get-DirectoryFingerprint $verified.outputDirectory
    $collision = Invoke-VerifierFailure -Fixture $fixture -VerificationId 'publish-valid'
    $collisionCompact = $collision.output -replace '\s', ''
    Assert-True 'output collision exits nonzero' ($collision.exitCode -ne 0) 'collision-exit-zero'
    Assert-True 'output collision reason' ($collisionCompact -like '*trace-verify-output-collision*') ('collision-reason-missing:' + $collisionCompact)
    Assert-Equal 'output collision preserves packet' (Get-DirectoryFingerprint $verified.outputDirectory) $outputBeforeCollision

    $concurrent = New-CapturedTrace -Case 'publish-concurrent'
    $concurrentId = 'publish-concurrent'
    $concurrentOutputParent = Join-Path $concurrent.root 'data\agent-handoff\artifact-trace-verification'
    New-Item -ItemType Directory -Path $concurrentOutputParent -Force | Out-Null
    $stdoutOne = Join-Path $concurrent.root 'concurrent-one.out'
    $stderrOne = Join-Path $concurrent.root 'concurrent-one.err'
    $stdoutTwo = Join-Path $concurrent.root 'concurrent-two.out'
    $stderrTwo = Join-Path $concurrent.root 'concurrent-two.err'
    $argumentText = @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $verifyScript + '"'),
        '-Root', ('"' + $concurrent.root + '"'),
        '-TraceDirectory', ('"' + $concurrent.traceRelative + '"'),
        '-ExpectedManifestSha256', $concurrent.expectedManifestSha256,
        '-VerificationId', $concurrentId
    ) -join ' '
    $powershellExe = (Get-Command powershell.exe).Source
    $processOne = Start-Process `
        -FilePath $powershellExe `
        -ArgumentList $argumentText `
        -RedirectStandardOutput $stdoutOne `
        -RedirectStandardError $stderrOne `
        -WindowStyle Hidden `
        -PassThru
    $processTwo = Start-Process `
        -FilePath $powershellExe `
        -ArgumentList $argumentText `
        -RedirectStandardOutput $stdoutTwo `
        -RedirectStandardError $stderrTwo `
        -WindowStyle Hidden `
        -PassThru
    $processOne.WaitForExit()
    $processTwo.WaitForExit()
    $concurrentOutputs = @(
        [string](Get-Content -LiteralPath $stdoutOne -Raw -ErrorAction SilentlyContinue),
        [string](Get-Content -LiteralPath $stdoutTwo -Raw -ErrorAction SilentlyContinue)
    )
    Assert-Equal `
        'concurrent same-ID has one winner' `
        @($concurrentOutputs | Where-Object { $_ -like '*awx.artifact_trace_verification_result.v1*' }).Count `
        1
    $concurrentErrors = (
        (Get-Content -LiteralPath $stderrOne -Raw -ErrorAction SilentlyContinue) +
        (Get-Content -LiteralPath $stderrTwo -Raw -ErrorAction SilentlyContinue)
    ) -replace '\s', ''
    Assert-Equal `
        'concurrent same-ID has one loser' `
        @(
            [string](Get-Content -LiteralPath $stderrOne -Raw -ErrorAction SilentlyContinue),
            [string](Get-Content -LiteralPath $stderrTwo -Raw -ErrorAction SilentlyContinue)
        ).Where({ $_ -like '*trace-verify-output-collision*' }).Count `
        1
    Assert-True 'concurrent same-ID loser reason' ($concurrentErrors -like '*trace-verify-output-collision*') 'concurrent-collision-reason-missing'
    $concurrentFinal = Join-Path $concurrentOutputParent $concurrentId
    Assert-Equal 'concurrent same-ID final member count' @(Get-ChildItem -LiteralPath $concurrentFinal -Force).Count 3
    $concurrentRemnants = @(Get-ChildItem -LiteralPath $concurrentOutputParent -Directory -Force | Where-Object { $_.Name -like '.*.tmp-*' })
    Assert-Equal 'concurrent same-ID leaves no staging remnant' $concurrentRemnants.Count 0
    $concurrentLockRemnants = @(Get-ChildItem -LiteralPath $concurrentOutputParent -File -Force | Where-Object { $_.Name -like '.*.publish.lock' })
    Assert-Equal 'concurrent same-ID leaves no publication lock' $concurrentLockRemnants.Count 0

    $publishPhase = New-CapturedTrace -Case 'publish-envelope-phase'
    $publishPhaseScript = Join-Path $publishPhase.root 'publish-phase-verifier.ps1'
    $publishVerifierSource = Get-Content -LiteralPath $verifyScript -Raw -Encoding UTF8
    $publishPhaseMarker = '    $stagedItems = @(Get-ChildItem -LiteralPath $script:temporaryOutput -Force)'
    Assert-Equal `
        'publish phase barrier insertion point unique' `
        ([regex]::Matches($publishVerifierSource, [regex]::Escape($publishPhaseMarker)).Count) `
        1
    $publishPhaseReplacement = @'
    $phaseReadyPath = Join-Path $TraceFull '.ready'
    [IO.File]::AppendAllText($phaseReadyPath, 'phase-change', $utf8NoBom)
    $stagedItems = @(Get-ChildItem -LiteralPath $script:temporaryOutput -Force)
'@
    $publishPhaseSource = $publishVerifierSource.Replace(
        $publishPhaseMarker,
        $publishPhaseReplacement.TrimEnd("`r", "`n")
    )
    [IO.File]::WriteAllText($publishPhaseScript, $publishPhaseSource, $utf8NoBom)
    $savedPublishVerifier = $script:verifyScript
    try {
        $script:verifyScript = $publishPhaseScript
        $publishPhaseVerified = Invoke-Verifier `
            -Fixture $publishPhase `
            -VerificationId 'publish-envelope-phase'
    } finally {
        $script:verifyScript = $savedPublishVerifier
    }
    Assert-Equal `
        'post-stage envelope mutation cannot publish terminal verdict' `
        $publishPhaseVerified.packet.integrityVerdict `
        'INDETERMINATE'
    Assert-Contains `
        'post-stage envelope mutation reason' `
        $publishPhaseVerified.packet.failureClassifications `
        'trace-verify-envelope-changed'

    $secret = New-CapturedTrace -Case 'publish-secret-risk'
    $secretScript = Join-Path $secret.root 'secret-verifier.ps1'
    $secretSource = Get-Content -LiteralPath $verifyScript -Raw -Encoding UTF8
    $secretMarker = '    $script:temporaryOutput = Join-Path $outputParent ('
    Assert-Equal 'secret phase insertion point unique' ([regex]::Matches($secretSource, [regex]::Escape($secretMarker)).Count) 1
    $secretReplacement = @'
    $Packet['allowedClaims'] = @(('sk' + '-' + '1234567890abcdefghijklmnop'))
    $script:temporaryOutput = Join-Path $outputParent (
'@
    [IO.File]::WriteAllText(
        $secretScript,
        $secretSource.Replace($secretMarker, $secretReplacement.TrimEnd("`r", "`n")),
        $utf8NoBom
    )
    $savedSecretVerifier = $script:verifyScript
    try {
        $script:verifyScript = $secretScript
        $secretFailure = Invoke-VerifierFailure `
            -Fixture $secret `
            -VerificationId 'publish-secret-risk'
    } finally {
        $script:verifyScript = $savedSecretVerifier
    }
    $secretCompact = $secretFailure.output -replace '\s', ''
    Assert-True 'secret-risk output exits nonzero' ($secretFailure.exitCode -ne 0) 'secret-exit-zero'
    Assert-True 'secret-risk output reason' ($secretCompact -like '*trace-verify-secret-risk*') 'secret-reason-missing'
    Assert-True `
        'secret-risk output publishes no packet' `
        (-not (Test-Path -LiteralPath (Join-Path $secret.root 'data\agent-handoff\artifact-trace-verification\publish-secret-risk'))) `
        'secret-packet-present'

    $budget = New-CapturedTrace -Case 'publish-budget'
    $budgetFailure = Invoke-VerifierFailure -Fixture $budget -VerificationId 'publish-budget' -Extra @{ MaxOutputMiB = 0 }
    $budgetCompact = $budgetFailure.output -replace '\s', ''
    Assert-True 'output budget exits nonzero' ($budgetFailure.exitCode -ne 0) 'budget-exit-zero'
    Assert-True 'output budget reason' ($budgetCompact -like '*trace-verify-budget-exceeded*') ('budget-reason-missing:' + $budgetCompact)
    $budgetFinal = Join-Path $budget.root 'data\agent-handoff\artifact-trace-verification\publish-budget'
    Assert-True 'output budget publishes no packet' (-not (Test-Path -LiteralPath $budgetFinal)) 'budget-packet-present'

    foreach ($boundedCase in @(
        [pscustomobject]@{ name = 'file'; extra = @{ MaxFiles = 0 } },
        [pscustomobject]@{ name = 'byte'; extra = @{ MaxTotalMiB = 0 } },
        [pscustomobject]@{ name = 'json'; extra = @{ MaxJsonMiB = 0 } },
        [pscustomobject]@{ name = 'timeout'; extra = @{ TimeoutSeconds = 0 } }
    )) {
        $bounded = New-CapturedTrace -Case ('publish-' + $boundedCase.name + '-budget')
        $boundedId = 'budget-' + $boundedCase.name
        $boundedFailure = Invoke-VerifierFailure `
            -Fixture $bounded `
            -VerificationId $boundedId `
            -Extra $boundedCase.extra
        $boundedCompact = $boundedFailure.output -replace '\s', ''
        Assert-True ($boundedCase.name + ' budget exits nonzero') ($boundedFailure.exitCode -ne 0) 'budget-exit-zero'
        Assert-True ($boundedCase.name + ' budget reason') ($boundedCompact -like '*trace-verify-budget-exceeded*') 'budget-reason-missing'
        $boundedFinal = Join-Path $bounded.root ('data\agent-handoff\artifact-trace-verification\' + $boundedId)
        Assert-True ($boundedCase.name + ' budget publishes no packet') (-not (Test-Path -LiteralPath $boundedFinal)) 'budget-packet-present'
    }

    $temporaryRemnants = @(Get-ChildItem -LiteralPath (Split-Path -Parent $finalOutput) -Directory -Force | Where-Object { $_.Name -like '.*.tmp-*' })
    Assert-Equal 'no verifier temporary remnants' $temporaryRemnants.Count 0
    $packetText = Get-Content -LiteralPath $verified.packetPath -Raw -Encoding UTF8
    Assert-True 'packet contains no raw fixture body' (-not $packetText.Contains('one')) 'raw-fixture-body-leaked'
    $verifierText = Get-Content -LiteralPath $verifyScript -Raw -Encoding UTF8
    Assert-True `
        'snapshot does not allocate complete stream length' `
        (-not $verifierText.Contains('New-Object byte[] ([int]$stream.Length)')) `
        'whole-stream-allocation-present'
    Assert-True `
        'snapshot accepts an explicit byte bound' `
        ($verifierText -match 'function Get-LockedSnapshot[\s\S]*?\[long\]\$MaxBytes') `
        'snapshot-maxbytes-missing'
    Assert-True `
        'filesystem enumeration is streaming' `
        (-not $verifierText.Contains('@(Get-ChildItem -LiteralPath $directory -Force)')) `
        'directory-enumeration-materialized'
    Assert-True `
        'temporary cleanup requires an ownership token' `
        ($verifierText.Contains('$temporaryOwnerToken') -and $verifierText.Contains("'.owner'")) `
        'temporary-owner-identity-missing'
    Assert-True `
        'cross-host publication uses an atomic delete-on-close lock' `
        ($verifierText.Contains('[IO.FileMode]::CreateNew') -and $verifierText.Contains('[IO.FileOptions]::DeleteOnClose')) `
        'cross-host-publication-lock-missing'

    $tokens = $null
    $parseErrors = $null
    $verifierAst = [Management.Automation.Language.Parser]::ParseFile(
        (Get-Item -LiteralPath $verifyScript).FullName,
        [ref]$tokens,
        [ref]$parseErrors
    )
    Assert-Equal 'verifier AST parse errors' $parseErrors.Count 0
    $mutationViolations = New-Object System.Collections.Generic.List[string]
    $mutationNodeCount = 0
    $commandNodes = @($verifierAst.FindAll({
        param($Node)
        $Node -is [Management.Automation.Language.CommandAst] -and
        $Node.GetCommandName() -in @('Remove-Item', 'Move-Item', 'Rename-Item', 'Set-Content', 'Out-File', 'New-Item')
    }, $true))
    foreach ($node in $commandNodes) {
        $mutationNodeCount++
        $name = $node.GetCommandName()
        $extent = $node.Extent.Text
        $allowed = (
            ($name -eq 'New-Item' -and $extent -match '\$(outputParent|script:temporaryOutput)\b') -or
            ($name -eq 'Remove-Item' -and $extent -match '\$(script:temporaryOutput|ownerPath)\b')
        )
        if (-not $allowed -or $extent -match '\$(traceFull|inputFull|artifact)\b') {
            $mutationViolations.Add($name + ':' + $extent)
        }
    }
    $memberNodes = @($verifierAst.FindAll({
        param($Node)
        $Node -is [Management.Automation.Language.InvokeMemberExpressionAst] -and
        [string]$Node.Member.Value -in @(
            'WriteAllBytes', 'WriteAllText', 'Delete', 'Move',
            'CreateDirectory', 'Write', 'Flush'
        )
    }, $true))
    foreach ($node in $memberNodes) {
        $mutationNodeCount++
        $name = [string]$node.Member.Value
        $extent = $node.Extent.Text
        $allowed = (
            ($name -in @('WriteAllBytes', 'WriteAllText') -and $extent -match '\$(packetPath|sidecarPath|readyPath)\b') -or
            ($name -eq 'Write' -and $extent -match '^\$(memory|stream)\.Write\(') -or
            ($name -eq 'Flush' -and $extent -ceq '$stream.Flush($true)') -or
            ($name -eq 'Move' -and $extent -match '\$script:temporaryOutput\b' -and $extent -match '\$finalOutput\b')
        )
        if (-not $allowed -or $extent -match '\$(traceFull|inputFull|artifact)\b') {
            $mutationViolations.Add($name + ':' + $extent)
        }
    }
    Assert-Equal 'verifier AST mutation node count' $mutationNodeCount 9
    Assert-Equal 'verifier AST mutation allowlist violations' $mutationViolations.Count 0
}

function Test-HarnessGroup {
    foreach ($requiredPath in @($harnessPath, $harnessMetaPath, $promptManifestPath, $promptBuildPath, $pressureEvidencePath)) {
        if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) { throw 'harness-missing' }
    }
    $strictUtf8 = New-Object Text.UTF8Encoding($false, $true)
    try {
        $harnessText = $strictUtf8.GetString([IO.File]::ReadAllBytes($harnessPath))
    } catch {
        throw 'harness-not-strict-utf8'
    }
    Assert-True 'harness has no replacement character' (-not $harnessText.Contains([char]0xfffd)) 'harness-mojibake'
    foreach ($token in @(
        '$demo1-artifact-trace-curator',
        'operation=capture|recheck',
        'new_artifact_trace_manifest.ps1',
        'test_artifact_trace_manifest.ps1',
        'ExpectedManifestSha256',
        'UNCHANGED', 'CHANGED', 'INDETERMINATE', 'INVALID',
        'VERIFICATION_ONLY', 'HOLD', 'evidence_needed',
        'captureSnapshotAtomicity=unproven',
        'allowedClaims', 'notProofOf',
        'Observation', 'Integrity', 'Proof Limits', 'Next'
    )) {
        Assert-True ('harness token ' + $token) ($harnessText.Contains($token)) ('missing=' + $token)
    }
    foreach ($semanticMarker in @(
        'AMBIGUOUS_TRACE_HOLD',
        'NO_TIMESTAMP_SELECTION',
        'NO_AUTHORITY_EXPANSION',
        'PRESERVE_EVIDENCE_AXES',
        'OWN_TEMP_CLEANUP_ONLY'
    )) {
        Assert-True ('harness semantic marker ' + $semanticMarker) ($harnessText.Contains($semanticMarker)) ('missing=' + $semanticMarker)
    }

    $metaText = Get-Content -LiteralPath $harnessMetaPath -Raw -Encoding UTF8
    foreach ($line in @(
        'id: demo1_artifact_trace_integrity_harness',
        'lang: ko',
        'role: system',
        'version: "1.0.0"'
    )) {
        Assert-True ('harness meta ' + $line) ($metaText.Contains($line)) ('missing=' + $line)
    }
    $manifestText = Get-Content -LiteralPath $promptManifestPath -Raw -Encoding UTF8
    Assert-Equal 'prompt manifest unique harness id' ([regex]::Matches($manifestText, '(?m)^\s*- id: demo1_artifact_trace_integrity_harness\s*$').Count) 1
    Assert-True 'prompt manifest system path matches' ($manifestText.Contains('system: agents/demo1_artifact_trace_integrity_harness/system_ko.md')) 'system-path-mismatch'
    Assert-True 'prompt manifest output path matches' ($manifestText.Contains('path: out/demo1_artifact_trace_integrity_harness.prompt')) 'output-path-mismatch'

    $priorOutputSha = if (Test-Path -LiteralPath $promptOutputPath) { Get-ShaLower $promptOutputPath } else { '' }
    $buildOutput = & python $promptBuildPath `
        --manifest $promptManifestPath `
        --agent demo1_artifact_trace_integrity_harness 2>&1 | Out-String
    Assert-Equal 'prompt build exit code' $LASTEXITCODE 0
    Assert-True 'prompt build produced output' (Test-Path -LiteralPath $promptOutputPath -PathType Leaf) ('build-output-missing:' + $buildOutput)
    if (Test-Path -LiteralPath $promptOutputPath -PathType Leaf) {
        $builtText = $strictUtf8.GetString([IO.File]::ReadAllBytes($promptOutputPath))
        $normalizedBuilt = $builtText.Replace("`r`n", "`n")
        $normalizedHarness = $harnessText.Replace("`r`n", "`n")
        Assert-Equal 'prompt build content matches system' $normalizedBuilt $normalizedHarness
        Assert-True 'prompt build output has no replacement character' (-not $builtText.Contains([char]0xfffd)) 'build-mojibake'
    }

    $pressureText = $strictUtf8.GetString([IO.File]::ReadAllBytes($pressureEvidencePath))
    foreach ($pressureMarker in @(
        'freshContexts=3', 'passed=3', 'failed=0',
        'newestTimestampSelection=false', 'desktopApplyUpgrade=false',
        'Remove-ItemInvoked=false', 'mutationCalls=0'
    )) {
        Assert-True `
            ('pressure evidence marker ' + $pressureMarker) `
            ($pressureText.Contains($pressureMarker)) `
            ('missing=' + $pressureMarker)
    }
}

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    if (-not (Test-Path -LiteralPath $captureScript -PathType Leaf)) {
        throw 'capture-generator-missing'
    }
    if (-not (Test-Path -LiteralPath $verifyScript -PathType Leaf)) {
        throw 'verifier-missing'
    }

    if ($Group -in @('Envelope', 'All')) { Test-EnvelopeGroup }
    if ($Group -in @('Compare', 'All')) { Test-CompareGroup }
    if ($Group -in @('Publication', 'All')) { Test-PublicationGroup }
    if ($Group -in @('Harness', 'All')) { Test-HarnessGroup }
} catch {
    $reason = [string]$_.Exception.Message
    $script:Failed++
    $script:Failures.Add('harness:' + $reason)
    Write-Host ('[artifact-trace-verify-test][FAIL] harness reason=' + $reason)
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$cleanupOk = -not (Test-Path -LiteralPath $testRoot)
Write-TestResult -Name 'temporary fixtures cleaned' -Passed $cleanupOk -Reason 'temp-root-remains'
Write-TestResult `
    -Name 'required fixture skip count' `
    -Passed ($script:Skipped -eq 0) `
    -Reason ('skipped=' + $script:Skipped)
Write-Host (
    '[artifact-trace-verify-test][SUMMARY] group=' + $Group +
    ' passed=' + $script:Passed +
    ' failed=' + $script:Failed +
    ' skipped=' + $script:Skipped
)
if ($script:Failures.Count -gt 0) {
    Write-Host ('[artifact-trace-verify-test][REASONS] ' + (($script:Failures | Sort-Object) -join ','))
}
if ($script:Failed -gt 0) { exit 1 }
exit 0
