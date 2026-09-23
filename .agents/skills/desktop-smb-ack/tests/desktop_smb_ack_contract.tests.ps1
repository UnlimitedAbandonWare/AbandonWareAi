[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:failureRows = [Collections.Generic.List[string]]::new()
$script:assertionCount = 0
$skillPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\SKILL.md')).Path

function Assert-AckContract {
    param([bool]$Condition, [string]$Message)

    $script:assertionCount++
    if (-not $Condition) {
        $script:failureRows.Add($Message)
    }
}

function Test-ParagraphContainsAll {
    param(
        [string[]]$Paragraphs,
        [string[]]$Patterns
    )

    foreach ($paragraph in $Paragraphs) {
        $matched = $true
        foreach ($pattern in $Patterns) {
            if ($paragraph -notmatch $pattern) {
                $matched = $false
                break
            }
        }
        if ($matched) {
            return $true
        }
    }
    return $false
}

try {
    $utf8 = [Text.UTF8Encoding]::new($false, $true)
    $skillText = [IO.File]::ReadAllText($skillPath, $utf8)
    $normalizedText = $skillText -replace "`r`n", "`n"
    $paragraphs = @($normalizedText -split "`n\s*`n" | ForEach-Object {
        (($_ -replace "`n", ' ') -replace '\s+', ' ').Trim()
    } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

    $canonicalLine = '{"ackStatus":"done","completionScope":"handoff-only","sourcePatchCompletion":"evidence_needed"}'
    $jsonFenceMatches = @([regex]::Matches($normalizedText, '(?ms)```json\s*\n(?<body>.*?)\n```'))
    $jsonLines = [Collections.Generic.List[string]]::new()
    foreach ($match in $jsonFenceMatches) {
        $bodyLines = @($match.Groups['body'].Value -split "`n" | ForEach-Object { $_.Trim() } | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        })
        if ($bodyLines.Count -eq 1) {
            $jsonLines.Add($bodyLines[0])
        }
    }

    $canonicalRows = @($jsonLines | Where-Object { $_ -ceq $canonicalLine })
    $canonicalObject = $null
    if ($canonicalRows.Count -gt 0) {
        try {
            $canonicalObject = $canonicalRows[0] | ConvertFrom-Json -ErrorAction Stop
        } catch {
            $canonicalObject = $null
        }
    }

    $propertyNames = @()
    $ackStatus = $null
    $completionScope = $null
    $sourcePatchCompletion = $null
    if ($null -ne $canonicalObject) {
        $propertyNames = @($canonicalObject.PSObject.Properties.Name)
        $ackStatus = [string]$canonicalObject.ackStatus
        $completionScope = [string]$canonicalObject.completionScope
        $sourcePatchCompletion = [string]$canonicalObject.sourcePatchCompletion
    }

    $legacyOnlyRows = @($jsonLines | Where-Object {
        try {
            $candidate = $_ | ConvertFrom-Json -ErrorAction Stop
            $names = @($candidate.PSObject.Properties.Name)
            $names.Count -eq 1 -and $names[0] -ceq 'status' -and [string]$candidate.status -ceq 'done'
        } catch {
            $false
        }
    })

    $completionStatement = Test-ParagraphContainsAll -Paragraphs $paragraphs -Patterns @(
        '(?i)ACK\s+Done',
        '(?i)(never\s+proves|does\s+not\s+prove)',
        '(?i)source.{0,40}(?:applied|application)',
        '(?i)(verified|verification)',
        '(?i)runtime',
        '(?i)commit',
        '(?i)deploy'
    )

    $sourceRoute = Test-ParagraphContainsAll -Paragraphs $paragraphs -Patterns @(
        '(?i)source',
        '(?i)(packet|directive)',
        'demo1-desktop-canonical-goal-intake',
        'demo1-source-edit-three-way-preflight'
    )
    $patchDropRoute = Test-ParagraphContainsAll -Paragraphs $paragraphs -Patterns @(
        '(?i)PatchDrop',
        'patchdrop-safe-patch-orchestrator'
    )
    $canaryRoute = Test-ParagraphContainsAll -Paragraphs $paragraphs -Patterns @(
        '(?i)Canary',
        'demo1-notebook-targeted-directive-canary'
    )
    $guardedRouteNotCompletion = Test-ParagraphContainsAll -Paragraphs $paragraphs -Patterns @(
        '(?i)(source|PatchDrop|Canary)',
        '(?i)guarded\s+workflow',
        '(?i)instead\s+of.{0,30}completion\s+ACK'
    )

    Assert-AckContract (Test-Path -LiteralPath $skillPath -PathType Leaf) 'fixture: desktop-smb-ack SKILL.md is missing'
    Assert-AckContract ($skillText.IndexOf([char]0xFFFD) -lt 0) 'fixture: SKILL.md contains UTF-8 replacement characters'
    Assert-AckContract ($jsonFenceMatches.Count -gt 0) 'payload: no executable JSON fence is present'
    Assert-AckContract ($canonicalRows.Count -ge 1) 'payload: canonical handoff-only ACK JSON line is missing'
    Assert-AckContract ($null -ne $canonicalObject) 'payload: canonical ACK line is not valid JSON'
    Assert-AckContract ($propertyNames.Count -eq 3) "payload: canonical ACK must expose exactly three fields (observed $($propertyNames.Count))"
    Assert-AckContract ($ackStatus -ceq 'done') "payload: ackStatus must be done (observed '$ackStatus')"
    Assert-AckContract ($completionScope -ceq 'handoff-only') "payload: completionScope must be handoff-only (observed '$completionScope')"
    Assert-AckContract ($sourcePatchCompletion -ceq 'evidence_needed') "payload: sourcePatchCompletion must be evidence_needed (observed '$sourcePatchCompletion')"
    Assert-AckContract ($legacyOnlyRows.Count -eq 0) 'payload: sole {"status":"done"} ACK remains completion-capable'
    Assert-AckContract $completionStatement 'semantics: one explicit statement must say ACK Done never proves source applied, verified, runtime, commit, or deploy completion'
    Assert-AckContract $sourceRoute 'routing: source packets must route through demo1-desktop-canonical-goal-intake and demo1-source-edit-three-way-preflight'
    Assert-AckContract $patchDropRoute 'routing: PatchDrop packets must route through patchdrop-safe-patch-orchestrator'
    Assert-AckContract $CanaryRoute 'routing: Canary packets must route through demo1-notebook-targeted-directive-canary'
    Assert-AckContract $guardedRouteNotCompletion 'routing: guarded workflows must be used instead of a completion ACK'
} catch {
    $script:failureRows.Add(('harness-error: ' + [string]$_.Exception.Message))
}

if ($script:failureRows.Count -gt 0) {
    Write-Output ([ordered]@{
        status = 'RED'
        firstFailure = [string]$script:failureRows[0]
        failureCount = $script:failureRows.Count
        assertionCount = $script:assertionCount
    } | ConvertTo-Json -Compress)
    exit 1
}

Write-Output ([ordered]@{
    status = 'PASS'
    firstFailure = $null
    failureCount = 0
    assertionCount = $script:assertionCount
} | ConvertTo-Json -Compress)
exit 0
