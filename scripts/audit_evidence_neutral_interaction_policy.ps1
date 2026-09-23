[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Root
)

$ErrorActionPreference = 'Stop'

function Write-AuditResult {
    param(
        [string]$Status,
        [int]$ExitCode,
        [object[]]$Violations,
        [string[]]$CheckedFiles
    )
    $orderedViolations = @($Violations | Sort-Object { $_.id })
    $orderedChecked = @($CheckedFiles | Sort-Object -Unique)
    $result = [ordered]@{
        schemaVersion = 1
        status = $Status
        violations = $orderedViolations
        checkedFiles = $orderedChecked
    }
    Write-Output ($result | ConvertTo-Json -Depth 8 -Compress)
    exit $ExitCode
}

function Relative-Path {
    param([string]$Base, [string]$FullPath)
    $relative = $FullPath.Substring($Base.Length).TrimStart([char[]]@('\', '/'))
    return $relative.Replace('\', '/')
}

function Add-Violation {
    param(
        [System.Collections.Generic.List[object]]$Target,
        [string]$Id,
        [string[]]$Paths
    )
    $Target.Add([ordered]@{
        id = $Id
        paths = @($Paths | Where-Object { $_ } | Sort-Object -Unique)
    })
}

try {
    if ([string]::IsNullOrWhiteSpace($Root) -or -not (Test-Path -LiteralPath $Root -PathType Container)) {
        Write-AuditResult 'invalid' 2 @() @()
    }

    $ResolvedRoot = (Resolve-Path -LiteralPath $Root).Path.TrimEnd('\', '/')
    $Required = @(
        'main/java/com/example/lms/guard/InteractionEvidencePolicy.java',
        'main/java/com/example/lms/prompt/PromptContext.java',
        'main/java/com/example/lms/prompt/PromptBuilder.java',
        'main/java/com/example/lms/service/guard/GuardContext.java',
        'main/java/com/example/lms/service/ChatWorkflow.java',
        'main/java/com/example/lms/telemetry/MlaBreadcrumb.java'
    )

    foreach ($relative in $Required) {
        $candidate = Join-Path $ResolvedRoot $relative
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            throw 'required_file_is_directory'
        }
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            Write-AuditResult 'invalid' 2 @() @($Required)
        }
    }

    $Text = @{}
    foreach ($relative in $Required) {
        $Text[$relative] = [System.IO.File]::ReadAllText((Join-Path $ResolvedRoot $relative))
    }

    $RuntimeProducerFiles = @(
        'main/java/com/example/lms/service/AttachmentService.java',
        'main/java/com/example/lms/api/ChatSessionAccessGuard.java'
    )
    foreach ($relative in $RuntimeProducerFiles) {
        $candidate = Join-Path $ResolvedRoot $relative
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            $Text[$relative] = [System.IO.File]::ReadAllText($candidate)
        }
    }

    $Violations = [System.Collections.Generic.List[object]]::new()
    $Checked = [System.Collections.Generic.List[string]]::new()
    foreach ($relative in $Required) {
        $Checked.Add($relative)
    }
    foreach ($relative in $RuntimeProducerFiles) {
        if ($Text.ContainsKey($relative)) {
            $Checked.Add($relative)
        }
    }

    # ENI001: one canonical active policy owner; inactive mirrors may not duplicate it.
    $SearchRoots = @(
        'main/java',
        'app/src/main/java_clean',
        'project/src/main/java',
        'app/src/main/java',
        'demo-1',
        'lms-core'
    )
    $PolicyPaths = [System.Collections.Generic.List[string]]::new()
    foreach ($searchRoot in $SearchRoots) {
        $absoluteSearchRoot = Join-Path $ResolvedRoot $searchRoot
        if (-not (Test-Path -LiteralPath $absoluteSearchRoot -PathType Container)) {
            continue
        }
        foreach ($file in Get-ChildItem -LiteralPath $absoluteSearchRoot -Recurse -File -Filter 'InteractionEvidencePolicy.java') {
            $relative = Relative-Path $ResolvedRoot $file.FullName
            $PolicyPaths.Add($relative)
            $Checked.Add($relative)
        }
    }
    $ExpectedPolicy = 'main/java/com/example/lms/guard/InteractionEvidencePolicy.java'
    $UnexpectedPolicies = @($PolicyPaths | Where-Object { $_ -ne $ExpectedPolicy })
    if ($PolicyPaths.Count -ne 1 -or $UnexpectedPolicies.Count -gt 0) {
        Add-Violation $Violations 'ENI001' @($PolicyPaths)
    }

    # ENI002: social cues and evidence/provider outputs must not become factual weights.
    $CouplingPattern = '(?i)\b(politenessScore|trustScore|userReputation|retaliationLevel|userFrustrationScore|mannersScore|citationCount|rankingScore|providerSuccess|evidenceContent|evidenceText)\b'
    $CouplingPaths = [System.Collections.Generic.List[string]]::new()
    $CouplingScan = @(
        'main/java/com/example/lms/guard/InteractionEvidencePolicy.java',
        'main/java/com/example/lms/orchestration/OrchestrationSignals.java',
        'main/java/com/example/lms/service/ChatWorkflow.java',
        'main/java/com/example/lms/service/AttachmentService.java',
        'main/java/com/example/lms/api/ChatSessionAccessGuard.java'
    )
    foreach ($relative in $CouplingScan) {
        $candidate = Join-Path $ResolvedRoot $relative
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            continue
        }
        $Checked.Add($relative)
        $content = if ($Text.ContainsKey($relative)) { $Text[$relative] } else { [System.IO.File]::ReadAllText($candidate) }
        if ([regex]::IsMatch($content, $CouplingPattern)) {
            $CouplingPaths.Add($relative)
        }
    }
    if ($CouplingPaths.Count -gt 0) {
        Add-Violation $Violations 'ENI002' @($CouplingPaths)
    }

    # ENI003: typed context propagation and the canonical PromptBuilder boundary.
    $WiringPaths = [System.Collections.Generic.List[string]]::new()
    if (-not [regex]::IsMatch($Text['main/java/com/example/lms/prompt/PromptBuilder.java'], 'build\s*\(\s*PromptContext\b')) {
        $WiringPaths.Add('main/java/com/example/lms/prompt/PromptBuilder.java')
    }
    $workflow = $Text['main/java/com/example/lms/service/ChatWorkflow.java']
    if (-not [regex]::IsMatch($workflow, 'promptBuilder\.build\s*\(\s*ctx\s*\)') -or
            [regex]::IsMatch($workflow, 'new\s+StandardPromptBuilder\s*\(') -or
            -not $workflow.Contains('.interactionPolicyDecision(interactionPolicyDecision)') -or
            -not $workflow.Contains('getInteractionPolicyFacts()') -or
            -not $workflow.Contains('getInteractionSuspectEvidenceIds()') -or
            -not $workflow.Contains('filterSuspectPromptContents(')) {
        $WiringPaths.Add('main/java/com/example/lms/service/ChatWorkflow.java')
    }
    if (-not $Text['main/java/com/example/lms/prompt/PromptContext.java'].Contains('InteractionEvidencePolicy.Decision')) {
        $WiringPaths.Add('main/java/com/example/lms/prompt/PromptContext.java')
    }
    $guardContext = $Text['main/java/com/example/lms/service/guard/GuardContext.java']
    if (-not $guardContext.Contains('InteractionEvidencePolicy.Decision') -or
            -not [regex]::IsMatch($guardContext, 'copy\s*\(\)[\s\S]*interactionPolicyDecision') -or
            -not $guardContext.Contains('recordInteractionPolicyFact')) {
        $WiringPaths.Add('main/java/com/example/lms/service/guard/GuardContext.java')
    }
    $attachmentProducer = 'main/java/com/example/lms/service/AttachmentService.java'
    $sessionProducer = 'main/java/com/example/lms/api/ChatSessionAccessGuard.java'
    if ($Text.ContainsKey($attachmentProducer) -or $Text.ContainsKey($sessionProducer)) {
        if (-not $Text.ContainsKey($attachmentProducer) -or
                -not $Text[$attachmentProducer].Contains('observeInteractionEvidence') -or
                -not $Text[$attachmentProducer].Contains('DIGEST_MISMATCH') -or
                -not $Text[$attachmentProducer].Contains('PROVENANCE_MISMATCH')) {
            $WiringPaths.Add($attachmentProducer)
        }
        if (-not $Text.ContainsKey($sessionProducer) -or
                -not $Text[$sessionProducer].Contains('AUTHORIZATION_DENIED') -or
                -not $Text[$sessionProducer].Contains('recordInteractionPolicyFact')) {
            $WiringPaths.Add($sessionProducer)
        }
    }
    if ($WiringPaths.Count -gt 0) {
        Add-Violation $Violations 'ENI003' @($WiringPaths)
    }

    # ENI004: policy evaluation must be deterministic and dependency-free.
    $policy = $Text['main/java/com/example/lms/guard/InteractionEvidencePolicy.java']
    $UnstablePattern = '(?i)(System\.(?:currentTimeMillis|nanoTime|getenv|getProperties|getProperty)\s*\(|Instant\.now\s*\(|LocalDate\.now\s*\(|Math\.random\s*\(|\b(?:Clock|Random|UUID|HttpClient|WebClient|RestTemplate|Environment|HashMap|HashSet)\b|@Value\s*\(|parallelStream\s*\()'
    if ([regex]::IsMatch($policy, $UnstablePattern)) {
        Add-Violation $Violations 'ENI004' @('main/java/com/example/lms/guard/InteractionEvidencePolicy.java')
    }

    # ENI005: interaction-policy traces may contain bounded enums/counts only.
    $TracePaths = [System.Collections.Generic.List[string]]::new()
    $ForbiddenTracePattern = '(?i)\b(rawQuery|userQuery|finalQuery|queryHash|queryLength|matchedText|snippet|evidenceText|promptText|memoryText|secret|identity|sessionId|requestId|ownerToken|authorization|cookie)\b'
    $mla = $Text['main/java/com/example/lms/telemetry/MlaBreadcrumb.java']
    $method = [regex]::Match(
        $mla,
        'appendInteractionPolicyTransition[\s\S]*?(?=\r?\n\s*(?:private|public)\s+static)')
    if (-not $method.Success -or [regex]::IsMatch($method.Value, $ForbiddenTracePattern)) {
        $TracePaths.Add('main/java/com/example/lms/telemetry/MlaBreadcrumb.java')
    }
    foreach ($relative in @(
            'main/java/com/example/lms/telemetry/MlaBreadcrumb.java',
            'main/java/com/example/lms/service/ChatWorkflow.java',
            'main/java/com/example/lms/service/guard/EvidenceAwareGuard.java',
            'main/java/com/example/lms/service/AttachmentService.java',
            'main/java/com/example/lms/api/ChatSessionAccessGuard.java')) {
        $candidate = Join-Path $ResolvedRoot $relative
        if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
            continue
        }
        $Checked.Add($relative)
        $content = if ($Text.ContainsKey($relative)) { $Text[$relative] } else { [System.IO.File]::ReadAllText($candidate) }
        foreach ($line in ($content -split '\r?\n')) {
            if ($line -match 'interaction\.policy|interactionPolicy' -and
                    [regex]::IsMatch($line, $ForbiddenTracePattern)) {
                $TracePaths.Add($relative)
                break
            }
        }
    }
    if ($TracePaths.Count -gt 0) {
        Add-Violation $Violations 'ENI005' @($TracePaths)
    }

    $status = if ($Violations.Count -eq 0) { 'pass' } else { 'violation' }
    $code = if ($Violations.Count -eq 0) { 0 } else { 1 }
    Write-AuditResult $status $code @($Violations) @($Checked)
}
catch {
    Write-AuditResult 'error' 3 @() @()
}
