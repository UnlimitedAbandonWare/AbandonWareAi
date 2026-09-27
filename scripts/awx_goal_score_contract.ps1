Set-StrictMode -Version Latest

function Measure-AwxGoalScore {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Components,
        [object]$ProvidedScore
    )

    $componentNames = @(
        'evidenceStrength',
        'causalStrength',
        'verificationFeasibility',
        'userValue',
        'reversibility',
        'costEfficiency',
        'timeFit',
        'blastRadius',
        'ambiguity',
        'authorityOrSafetyExpansion'
    )

    if ($Components -is [System.Collections.IDictionary]) {
        $actualNames = @($Components.Keys | ForEach-Object { [string]$_ })
    } else {
        $actualNames = @($Components.PSObject.Properties.Name)
    }
    if ($actualNames.Count -ne $componentNames.Count -or
        @($componentNames | Where-Object { $actualNames -notcontains $_ }).Count -ne 0) {
        return [pscustomobject]@{
            valid = $false
            computedScore = $null
            failureClassification = 'goal-score-invalid'
        }
    }

    $values = @{}
    foreach ($name in $componentNames) {
        $raw = if ($Components -is [System.Collections.IDictionary]) {
            $Components[$name]
        } else {
            $Components.PSObject.Properties[$name].Value
        }
        $parsed = 0.0
        if (-not [double]::TryParse(
                [string]$raw,
                [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$parsed
            ) -or $parsed -lt 0.0 -or $parsed -gt 1.0) {
            return [pscustomobject]@{
                valid = $false
                computedScore = $null
                failureClassification = 'goal-score-invalid'
            }
        }
        $values[$name] = $parsed
    }

    $computed = 100.0 * (
        0.25 * $values.evidenceStrength +
        0.20 * $values.causalStrength +
        0.15 * $values.verificationFeasibility +
        0.15 * $values.userValue +
        0.10 * $values.reversibility +
        0.10 * $values.costEfficiency +
        0.05 * $values.timeFit -
        0.20 * $values.blastRadius -
        0.15 * $values.ambiguity -
        0.20 * $values.authorityOrSafetyExpansion
    )
    $score = [math]::Round(
        [math]::Max(0.0, [math]::Min(100.0, $computed)),
        4
    )

    if ($PSBoundParameters.ContainsKey('ProvidedScore')) {
        $submitted = 0.0
        if (-not [double]::TryParse(
                [string]$ProvidedScore,
                [Globalization.NumberStyles]::Float,
                [Globalization.CultureInfo]::InvariantCulture,
                [ref]$submitted
            )) {
            return [pscustomobject]@{
                valid = $false
                computedScore = $score
                failureClassification = 'goal-score-invalid'
            }
        }
        if ([math]::Abs($submitted - $score) -gt 0.0001) {
            return [pscustomobject]@{
                valid = $false
                computedScore = $score
                failureClassification = 'goal-score-mismatch'
            }
        }
    }

    [pscustomobject]@{
        valid = $true
        computedScore = $score
        failureClassification = 'none'
    }
}

function Measure-AwxDynamicGoalScore {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$BaseScore,
        [Parameter(Mandatory = $true)][object]$PassRatio
    )

    $base = 0.0
    $ratio = 0.0
    $baseValid = [double]::TryParse(
        [string]$BaseScore,
        [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture,
        [ref]$base
    )
    $ratioValid = [double]::TryParse(
        [string]$PassRatio,
        [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture,
        [ref]$ratio
    )
    if (-not $baseValid -or -not $ratioValid -or
        $base -lt 0.0 -or $base -gt 100.0 -or $ratio -lt 0.0 -or $ratio -gt 1.0) {
        return [pscustomobject]@{
            valid = $false
            baseScore = $null
            passRatio = $null
            delta = $null
            adjustedScore = $null
            failureClassification = 'dynamic-goal-score-invalid'
        }
    }

    $delta = [math]::Round(20.0 * ($ratio - 0.5), 4)
    $delta = [math]::Min(10.0, [math]::Max(-10.0, $delta))
    $adjusted = [math]::Round(
        [math]::Min(100.0, [math]::Max(0.0, $base + $delta)),
        4
    )
    [pscustomobject]@{
        valid = $true
        baseScore = [math]::Round($base, 4)
        passRatio = [math]::Round($ratio, 10)
        delta = $delta
        adjustedScore = $adjusted
        failureClassification = 'none'
    }
}
