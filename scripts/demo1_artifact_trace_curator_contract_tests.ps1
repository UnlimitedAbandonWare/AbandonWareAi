[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$script:Passed = 0
$script:Failed = 0
$script:Skipped = 0
$script:Failures = New-Object System.Collections.Generic.List[string]
$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$generator = Join-Path $repoRoot '.agents\skills\demo1-artifact-trace-curator\scripts\new_artifact_trace_manifest.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('awx-artifact-trace-contract-' + [guid]::NewGuid().ToString('N'))

function Write-TestResult {
    param([string]$Name, [bool]$Passed, [string]$Reason)
    if ($Passed) {
        $script:Passed++
        Write-Host ('[artifact-trace-test][PASS] ' + $Name)
    } else {
        $script:Failed++
        $script:Failures.Add($Name + ':' + $Reason)
        Write-Host ('[artifact-trace-test][FAIL] ' + $Name + ' reason=' + $Reason)
    }
}

function Assert-True {
    param([string]$Name, [bool]$Condition, [string]$Reason)
    Write-TestResult -Name $Name -Passed $Condition -Reason $Reason
}

function Assert-Equal {
    param([string]$Name, $Actual, $Expected)
    $ok = [object]::Equals($Actual, $Expected)
    Write-TestResult -Name $Name -Passed $ok -Reason ('expected=' + [string]$Expected + ',actual=' + [string]$Actual)
}

function Assert-Contains {
    param([string]$Name, $Values, [string]$Expected)
    $ok = @($Values) -contains $Expected
    Write-TestResult -Name $Name -Passed $ok -Reason ('missing=' + $Expected)
}

function New-TestDirectory {
    param([string]$RelativePath)
    $path = Join-Path $testRoot $RelativePath
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    return $path
}

function Write-JsonFixture {
    param([string]$Path, $Value)
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    $json = $Value | ConvertTo-Json -Depth 20
    [IO.File]::WriteAllText($Path, $json, [Text.UTF8Encoding]::new($false))
}

function Write-TextFixture {
    param([string]$Path, [string]$Value)
    $parent = Split-Path -Parent $Path
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function Get-ShaLower {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Invoke-Curator {
    param(
        [string]$TraceId,
        [string]$InputRoot,
        [hashtable]$Extra = @{}
    )
    $parameters = @{
        Root = $testRoot
        TraceId = $TraceId
        InputRoot = $InputRoot
    }
    foreach ($key in $Extra.Keys) { $parameters[$key] = $Extra[$key] }
    & $generator @parameters | Out-Null
    $manifestPath = Join-Path $testRoot ('data\agent-handoff\artifact-trace\' + $TraceId + '\manifest.json')
    return [pscustomobject]@{
        directory = Split-Path -Parent $manifestPath
        manifestPath = $manifestPath
        manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    }
}

function Assert-ThrowsReason {
    param([string]$Name, [scriptblock]$Action, [string]$Expected)
    $reason = ''
    try {
        & $Action
        $reason = 'no-error'
    } catch {
        $reason = [string]$_.Exception.Message
    }
    Write-TestResult -Name $Name -Passed ($reason -like ('*' + $Expected + '*')) -Reason ('expected=' + $Expected + ',actual=' + $reason)
}

function New-BoundCompletionFixture {
    param([string]$RelativeRoot)
    $dir = New-TestDirectory $RelativeRoot
    $runId = 'trace-bind-001'
    $targetHash = ('a' * 64)
    $sessionPath = Join-Path $dir 'session.json'
    Write-JsonFixture $sessionPath ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_session.v2'
        runId = $runId
        createdAtUtc = '2026-07-30T00:00:00Z'
        targets = @([ordered]@{ relativePath = 'scripts/example.ps1'; preimageSha256 = ('b' * 64) })
        rawSecretPatternHits = 0
    })
    $sessionSha = Get-ShaLower $sessionPath
    $verificationPath = Join-Path $dir 'verification.json'
    Write-JsonFixture $verificationPath ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_verification.v1'
        runId = $runId
        sessionSha256 = $sessionSha
        command = 'focused-contract-test'
        exitCode = 0
        targetPostimages = @([ordered]@{ relativePath = 'scripts/example.ps1'; sha256 = $targetHash })
    })
    $verificationSha = Get-ShaLower $verificationPath
    Write-JsonFixture (Join-Path $dir 'completion.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_completion.v2'
        state = 'COMPLETE'
        runId = $runId
        sessionSha256 = $sessionSha
        verificationEvidenceSha256 = $verificationSha
        completedAtUtc = '2026-07-30T00:05:00Z'
        postimages = @([ordered]@{ relativePath = 'scripts/example.ps1'; postimageSha256 = $targetHash })
        undeclaredSourceChangeCount = 0
        rawSecretPatternHits = 0
    })
    return $dir
}

function New-PatchDropFixture {
    param([string]$RelativeRoot, [bool]$Complete)
    $dir = New-TestDirectory $RelativeRoot
    $bundle = 'sample-notebook-v3'
    Write-TextFixture (Join-Path $dir ($bundle + '.ready')) 'ready'
    if (-not $Complete) { return $dir }

    $patchName = $bundle + '.patch'
    $reportName = $bundle + '.report.md'
    $verifyName = $bundle + '.verify.log'
    $manifestName = $bundle + '.manifest.json'
    Write-TextFixture (Join-Path $dir $patchName) ('diff --git a/a.txt b/a.txt' + [Environment]::NewLine)
    Write-TextFixture (Join-Path $dir $reportName) ('sourceIsolation.guard=PASS' + [Environment]::NewLine)
    Write-TextFixture (Join-Path $dir $verifyName) ('focusedJUnit=PASS' + [Environment]::NewLine)
    Write-JsonFixture (Join-Path $dir $manifestName) ([ordered]@{
        schemaVersion = 'patchdrop-producer-v3'
        protocolVersion = 'patchdrop-v3'
        cumulative = $true
        activePatch = $patchName
        desktopFinalProof = 'evidence_needed'
        verification = [ordered]@{
            secretPatternHits = 0
        }
        sourceIsolation = [ordered]@{
            guard = 'PASS'
            sourceRootKind = 'local-worktree'
            sharedSourceRoot = $false
            desktopCanonicalSourceRoot = $false
            directCanonicalSourceEdit = $false
            gitRootPresent = $true
            gitRootMatchesSourceRoot = $true
            gitRootHash = ('c' * 64)
        }
    })
    $lines = @()
    foreach ($name in @($patchName, $reportName, $verifyName, $manifestName)) {
        $lines += (Get-ShaLower (Join-Path $dir $name)) + '  ' + $name
    }
    Write-TextFixture (Join-Path $dir ($bundle + '.sha256.txt')) (($lines -join [Environment]::NewLine) + [Environment]::NewLine)
    return $dir
}

try {
    New-Item -ItemType Directory -Path $testRoot -Force | Out-Null
    if (-not (Test-Path -LiteralPath $generator -PathType Leaf)) {
        throw 'generator-missing'
    }

    $tombstoneDir = New-TestDirectory 'data\agent-handoff\tombstone-case'
    Write-JsonFixture (Join-Path $tombstoneDir 'lease-cleanup.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_lease_cleanup.v1'
        state = 'DELETED'
        reason = 'stale-lease-force-cleanup'
        staleSinceUtc = '2026-01-01T00:00:00Z'
        deletedAtUtc = '2026-01-02T00:00:00Z'
        leaseSha256 = ('d' * 64)
    })
    $tombstone = Invoke-Curator -TraceId 'tombstone-case' -InputRoot 'data\agent-handoff\tombstone-case'
    $tombstoneRow = @($tombstone.manifest.artifacts | Where-Object { $_.artifactRole -eq 'tombstone' })[0]
    Assert-Equal 'tombstone proof scope' $tombstoneRow.proofScope 'cleanup_only'
    Assert-Equal 'tombstone delete authority stays false' $tombstoneRow.deleteAuthorized $false
    foreach ($claim in @('source_patch', 'runtime', 'deployment', 'approval', 'current_state')) {
        Assert-Contains ('tombstone denies ' + $claim) $tombstoneRow.notProofOf $claim
    }

    $incompleteDir = New-TestDirectory 'data\agent-handoff\completion-incomplete'
    Write-JsonFixture (Join-Path $incompleteDir 'completion.json') ([ordered]@{
        schemaVersion = 'awx.macsrc_smb_patch_completion.v2'
        state = 'COMPLETE'
        runId = 'missing-siblings'
        sessionSha256 = ('e' * 64)
        verificationEvidenceSha256 = ('f' * 64)
        postimages = @()
    })
    $incomplete = Invoke-Curator -TraceId 'completion-incomplete' -InputRoot 'data\agent-handoff\completion-incomplete'
    $incompleteRow = @($incomplete.manifest.artifacts | Where-Object { $_.artifactRole -eq 'completion' })[0]
    Assert-Equal 'incomplete completion invalid' $incompleteRow.proofStatus 'invalid'
    Assert-Contains 'incomplete completion reason' $incompleteRow.failureClassifications 'trace-binding-invalid'

    New-BoundCompletionFixture 'data\agent-handoff\completion-bound' | Out-Null
    $bound = Invoke-Curator -TraceId 'completion-bound' -InputRoot 'data\agent-handoff\completion-bound'
    $boundRow = @($bound.manifest.artifacts | Where-Object { $_.artifactRole -eq 'completion' })[0]
    Assert-Equal 'bound completion status' $boundRow.proofStatus 'structurally_bound'
    Assert-Equal 'bound completion scope' $boundRow.proofScope 'source_patch'
    Assert-Contains 'bound completion denies runtime' $boundRow.notProofOf 'runtime'
    Assert-Equal 'runtime lineage remains hold' $bound.manifest.runtimeLineageVerdict 'HOLD'
    Assert-True 'freshness separate from proof' ($null -ne $boundRow.freshnessState -and $boundRow.proofStatus -eq 'structurally_bound') 'freshness-conflated'

    $missingCountDir = New-BoundCompletionFixture 'data\agent-handoff\completion-missing-count'
    $missingCountPath = Join-Path $missingCountDir 'completion.json'
    $missingCountJson = Get-Content -LiteralPath $missingCountPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $missingCountJson.PSObject.Properties.Remove('rawSecretPatternHits')
    Write-JsonFixture $missingCountPath $missingCountJson
    $missingCount = Invoke-Curator -TraceId 'completion-missing-count' -InputRoot 'data\agent-handoff\completion-missing-count'
    $missingCountRow = @($missingCount.manifest.artifacts | Where-Object { $_.artifactRole -eq 'completion' })[0]
    Assert-Equal 'missing security count invalid' $missingCountRow.proofStatus 'invalid'

    New-PatchDropFixture '__patch_drop__\bundle-incomplete' $false | Out-Null
    $bundleIncomplete = Invoke-Curator -TraceId 'bundle-incomplete' -InputRoot '__patch_drop__\bundle-incomplete'
    $incompleteBundleRow = @($bundleIncomplete.manifest.artifacts | Where-Object { $_.artifactRole -eq 'patchdrop_ready' })[0]
    Assert-Equal 'incomplete patchdrop ready invalid' $incompleteBundleRow.proofStatus 'invalid'
    Assert-Contains 'incomplete patchdrop reason' $incompleteBundleRow.failureClassifications 'trace-binding-invalid'

    New-PatchDropFixture '__patch_drop__\bundle-complete' $true | Out-Null
    $bundleComplete = Invoke-Curator -TraceId 'bundle-complete' -InputRoot '__patch_drop__\bundle-complete'
    $completeBundleRow = @($bundleComplete.manifest.artifacts | Where-Object { $_.artifactRole -eq 'patchdrop_manifest' })[0]
    Assert-Equal 'complete bundle structurally bound' $completeBundleRow.proofStatus 'structurally_bound'
    Assert-Equal 'complete bundle limited scope' $completeBundleRow.proofScope 'patch_handoff'
    Assert-Contains 'complete bundle denies desktop apply' $completeBundleRow.notProofOf 'desktop_apply'

    $unknownDir = New-TestDirectory 'data\agent-handoff\unknown-case'
    $sentinel = 'RAW-CONTENT-MUST-NOT-APPEAR'
    Write-TextFixture (Join-Path $unknownDir 'diagnostic.bin') $sentinel
    $unknown = Invoke-Curator -TraceId 'unknown-case' -InputRoot 'data\agent-handoff\unknown-case'
    $unknownRow = @($unknown.manifest.artifacts | Where-Object { $_.artifactRole -eq 'diagnostic' })[0]
    Assert-Equal 'unknown proof scope none' $unknownRow.proofScope 'none'
    Assert-Equal 'unknown proof status none' $unknownRow.proofStatus 'none'
    $unknownManifestText = Get-Content -LiteralPath $unknown.manifestPath -Raw -Encoding UTF8
    Assert-True 'unknown raw body omitted' (-not $unknownManifestText.Contains($sentinel)) 'raw-content-leaked'

    $budgetDir = New-TestDirectory 'data\agent-handoff\budget-case'
    Write-TextFixture (Join-Path $budgetDir 'one.txt') '1'
    Write-TextFixture (Join-Path $budgetDir 'two.txt') '2'
    Assert-ThrowsReason 'file budget fails closed' { Invoke-Curator -TraceId 'budget-file' -InputRoot 'data\agent-handoff\budget-case' -Extra @{ MaxFiles = 1 } | Out-Null } 'trace-budget-exceeded'
    Assert-ThrowsReason 'byte budget fails closed' { Invoke-Curator -TraceId 'budget-byte' -InputRoot 'data\agent-handoff\budget-case' -Extra @{ MaxTotalMiB = 0 } | Out-Null } 'trace-budget-exceeded'
    Assert-ThrowsReason 'time budget fails closed' { Invoke-Curator -TraceId 'budget-time' -InputRoot 'data\agent-handoff\budget-case' -Extra @{ TimeoutSeconds = 0 } | Out-Null } 'trace-budget-exceeded'
    Assert-ThrowsReason 'output budget fails closed' { Invoke-Curator -TraceId 'budget-output' -InputRoot 'data\agent-handoff\budget-case' -Extra @{ MaxOutputMiB = 0 } | Out-Null } 'trace-budget-exceeded'

    $jsonBudgetDir = New-TestDirectory 'data\agent-handoff\json-budget-case'
    Write-JsonFixture (Join-Path $jsonBudgetDir 'record.json') ([ordered]@{ schemaVersion = 'unknown'; padding = ('x' * 1024) })
    Assert-ThrowsReason 'json budget fails closed' { Invoke-Curator -TraceId 'budget-json' -InputRoot 'data\agent-handoff\json-budget-case' -Extra @{ MaxJsonMiB = 0 } | Out-Null } 'trace-json-too-large'

    $secretDir = New-TestDirectory 'data\agent-handoff\secret-case'
    Write-JsonFixture (Join-Path $secretDir 'secret.json') ([ordered]@{ schemaVersion = 'unknown'; apiKey = ('sk-' + ('x' * 24)) })
    Assert-ThrowsReason 'secret risk fails closed' { Invoke-Curator -TraceId 'secret-case' -InputRoot 'data\agent-handoff\secret-case' | Out-Null } 'trace-secret-risk'

    $junctionTarget = New-TestDirectory 'outside-allowlist'
    Write-TextFixture (Join-Path $junctionTarget 'outside.txt') 'outside'
    $junctionParent = New-TestDirectory 'data\agent-handoff\reparse-parent'
    $junctionPath = Join-Path $junctionParent 'linked'
    try {
        New-Item -ItemType Junction -Path $junctionPath -Target $junctionTarget -Force | Out-Null
        Assert-ThrowsReason 'reparse traversal fails closed' { Invoke-Curator -TraceId 'reparse-case' -InputRoot 'data\agent-handoff\reparse-parent\linked' | Out-Null } 'trace-reparse-risk'
    } catch {
        $script:Skipped++
        Write-Host '[artifact-trace-test][SKIP] reparse traversal fixture unavailable'
    }

    $publishDir = New-TestDirectory 'data\agent-handoff\publish-case'
    Write-TextFixture (Join-Path $publishDir 'note.txt') 'metadata-only'
    $published = Invoke-Curator -TraceId 'publish-case' -InputRoot 'data\agent-handoff\publish-case'
    $shaPath = Join-Path $published.directory 'manifest.sha256'
    $readyPath = Join-Path $published.directory '.ready'
    Assert-True 'manifest published' (Test-Path -LiteralPath $published.manifestPath -PathType Leaf) 'manifest-missing'
    Assert-True 'sha sidecar published' (Test-Path -LiteralPath $shaPath -PathType Leaf) 'sha-missing'
    Assert-True 'ready marker published' (Test-Path -LiteralPath $readyPath -PathType Leaf) 'ready-missing'
    $expectedSidecar = (Get-ShaLower $published.manifestPath) + '  manifest.json'
    $actualSidecar = (Get-Content -LiteralPath $shaPath -Raw -Encoding UTF8).Trim()
    Assert-Equal 'manifest sha sidecar matches' $actualSidecar $expectedSidecar
    $manifestTime = (Get-Item -LiteralPath $published.manifestPath).LastWriteTimeUtc
    $shaTime = (Get-Item -LiteralPath $shaPath).LastWriteTimeUtc
    $readyTime = (Get-Item -LiteralPath $readyPath).LastWriteTimeUtc
    Assert-True 'ready marker published last' ($readyTime -ge $manifestTime -and $readyTime -ge $shaTime) 'ready-not-last'
    Assert-Equal 'overall verdict inventory only' $published.manifest.overallVerdict 'INVENTORY_ONLY'
    Assert-Equal 'mutation disallowed' $published.manifest.mutationAllowed $false
    Assert-Equal 'delete disallowed' $published.manifest.deleteAuthorized $false
    Assert-Equal 'desktop proof needed' $published.manifest.desktopFinalProof 'evidence_needed'
    Assert-ThrowsReason 'output collision fails closed' { Invoke-Curator -TraceId 'publish-case' -InputRoot 'data\agent-handoff\publish-case' | Out-Null } 'trace-output-collision'
    Assert-True 'output collision preserves prior output' (Test-Path -LiteralPath $readyPath -PathType Leaf) 'prior-output-removed'
    Assert-True 'input artifact preserved' (Test-Path -LiteralPath (Join-Path $publishDir 'note.txt') -PathType Leaf) 'input-mutated'

    Assert-ThrowsReason 'input root allowlist enforced' { Invoke-Curator -TraceId 'outside-case' -InputRoot 'outside-allowlist' | Out-Null } 'trace-input-not-allowlisted'
} catch {
    $script:Failed++
    $script:Failures.Add('harness:' + [string]$_.Exception.Message)
    Write-Host ('[artifact-trace-test][FAIL] harness reason=' + [string]$_.Exception.Message)
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$cleanupOk = -not (Test-Path -LiteralPath $testRoot)
Write-TestResult -Name 'temporary fixtures cleaned' -Passed $cleanupOk -Reason 'temp-root-remains'
Write-Host ('[artifact-trace-test][SUMMARY] passed=' + $script:Passed + ' failed=' + $script:Failed + ' skipped=' + $script:Skipped)
if ($script:Failures.Count -gt 0) {
    Write-Host ('[artifact-trace-test][REASONS] ' + (($script:Failures | Sort-Object) -join ','))
}
if ($script:Failed -gt 0) { exit 1 }
exit 0
