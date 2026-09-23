$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$script:Passed = 0
$script:Failed = 0

function Test-Contract {
    param([string]$Name, [bool]$Condition)
    if ($Condition) {
        $script:Passed++
        Write-Host "[PASS] $Name"
    } else {
        $script:Failed++
        Write-Host "[FAIL] $Name"
    }
}

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$helper = Join-Path $repoRoot 'scripts\awx_goal_score_contract.ps1'
if (-not (Test-Path -LiteralPath $helper -PathType Leaf)) {
    Write-Host '[FAIL] shared goal-score helper exists'
    Write-Host '[SUMMARY] passed=0 failed=1'
    exit 1
}

. $helper

$components = [ordered]@{
    evidenceStrength = 0.8
    causalStrength = 0.6
    verificationFeasibility = 0.9
    userValue = 0.8
    reversibility = 0.9
    costEfficiency = 0.8
    timeFit = 0.8
    blastRadius = 0.1
    ambiguity = 0.2
    authorityOrSafetyExpansion = 0.0
}

$valid = Measure-AwxGoalScore -Components $components -ProvidedScore 73.5
Test-Contract 'official fixture is valid' ($valid.valid -eq $true)
Test-Contract 'official fixture computes hand-derived 73.5' ([double]$valid.computedScore -eq 73.5)
Test-Contract 'valid fixture has no failure' ($valid.failureClassification -eq 'none')

$mismatch = Measure-AwxGoalScore -Components $components -ProvidedScore 70
Test-Contract 'submitted score mismatch fails closed' ($mismatch.valid -eq $false)
Test-Contract 'submitted score mismatch is classified' ($mismatch.failureClassification -eq 'goal-score-mismatch')
Test-Contract 'mismatch still reports authoritative computed score' ([double]$mismatch.computedScore -eq 73.5)

$invalidComponents = [ordered]@{} + $components
$invalidComponents.blastRadius = 1.2
$invalid = Measure-AwxGoalScore -Components $invalidComponents -ProvidedScore 73.5
Test-Contract 'out-of-range component fails closed' ($invalid.valid -eq $false)
Test-Contract 'out-of-range component is classified' ($invalid.failureClassification -eq 'goal-score-invalid')

$missingComponents = [ordered]@{} + $components
$missingComponents.Remove('timeFit')
$missing = Measure-AwxGoalScore -Components $missingComponents -ProvidedScore 73.5
Test-Contract 'missing component fails closed' ($missing.valid -eq $false)
Test-Contract 'missing component is classified' ($missing.failureClassification -eq 'goal-score-invalid')

$dynamicHigh = Measure-AwxDynamicGoalScore -BaseScore 95 -PassRatio 1.0
Test-Contract 'all-pass dynamic delta is capped at positive ten' ([double]$dynamicHigh.delta -eq 10.0)
Test-Contract 'dynamic score clamps at one hundred' ([double]$dynamicHigh.adjustedScore -eq 100.0)

$dynamicLow = Measure-AwxDynamicGoalScore -BaseScore 5 -PassRatio 0.0
Test-Contract 'all-fail dynamic delta is capped at negative ten' ([double]$dynamicLow.delta -eq -10.0)
Test-Contract 'dynamic score clamps at zero' ([double]$dynamicLow.adjustedScore -eq 0.0)

$dynamicHalf = Measure-AwxDynamicGoalScore -BaseScore 73.5 -PassRatio 0.5
Test-Contract 'half-pass dynamic delta is zero' ([double]$dynamicHalf.delta -eq 0.0)
Test-Contract 'half-pass preserves the base score' ([double]$dynamicHalf.adjustedScore -eq 73.5)

$dynamicInvalid = Measure-AwxDynamicGoalScore -BaseScore 73.5 -PassRatio 1.1
Test-Contract 'out-of-range pass ratio fails closed' ($dynamicInvalid.valid -eq $false)
Test-Contract 'invalid dynamic input is classified' ($dynamicInvalid.failureClassification -eq 'dynamic-goal-score-invalid')

Write-Host "[SUMMARY] passed=$script:Passed failed=$script:Failed"
if ($script:Failed -gt 0) { exit 1 }
