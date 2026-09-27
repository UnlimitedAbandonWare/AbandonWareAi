[CmdletBinding()]
param(
    [string]$Root = ".",
    [string]$OutputDir = "var/codex-smoke/smb-decommission",
    [string]$BrowserProofPath = "var/codex-smoke/smb-decommission/browser-viewer-current-proof.json",
    [string]$ComputerProofPath = "var/codex-smoke/computer-use-smoke.json",
    [string]$AttachmentPath = "",
    [ValidateRange(0, 50)][int]$AttachmentSampleCount = 5,
    [ValidateRange(0, 50)][int]$SourceProbeSampleCount = 8,
    [string]$Topic = "smb-decommission",
    [switch]$ProbeSupabaseNetwork,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    Write-Host @"
Usage: powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smb_decommission_debug_probe.ps1 [-Root <repo>] [-OutputDir <dir>] [-Topic <topic>] [-ProbeSupabaseNetwork]

Runs a Desktop-only, mutation-free SMB decommission debug probe.

Collected tools:
- source_scan result reused from desktop_control_loop (no second source_scan process)
- desktop_control_loop with require_producer_bundles=false, require_supabase_live_proof=false, run_completion_audit=false, write_dispatch=false
- supabase_context_probe with skip_mcp_network_probe=true unless -ProbeSupabaseNetwork is explicit
- __patch_drop__\janitor_inventory.ps1 when present

Safety:
- write_dispatch=false
- write_producer_kit=false
- mutationAllowed=false
- runtime HTTP proof is not auto-probed; missing proof remains evidence_needed.
- Browser and Computer proof are optional supporting lanes; existing proof artifacts are ingested by status/count only.
- AttachmentPath is optional and is summarized as hash/count/line-number samples only; raw attachment text is never rendered.
- Source probe samples use attachment terms against active source/script roots and store hashes/counts only.
"@
    exit 0
}

function Resolve-RepoPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    if ([IO.Path]::IsPathRooted($Path)) {
        return [IO.Path]::GetFullPath($Path)
    }
    return [IO.Path]::GetFullPath((Join-Path (Get-Location) $Path))
}

function Resolve-RepoRelativePath {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$Path
    )
    if ([IO.Path]::IsPathRooted($Path)) {
        return [IO.Path]::GetFullPath($Path)
    }
    return [IO.Path]::GetFullPath((Join-Path $RepoRoot $Path))
}

function Resolve-LatestOptionalProofPath {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$OutputDir,
        [Parameter(Mandatory = $true)][string]$RequestedPath,
        [Parameter(Mandatory = $true)][string]$DefaultPath,
        [string[]]$OutputDirFileNames = @(),
        [string[]]$RepoRelativeCandidates = @()
    )

    $requestedResolved = Resolve-RepoRelativePath -RepoRoot $RepoRoot -Path $RequestedPath
    if (-not [string]::Equals($RequestedPath, $DefaultPath, [StringComparison]::OrdinalIgnoreCase)) {
        return $requestedResolved
    }

    $candidatePaths = New-Object 'System.Collections.Generic.List[string]'
    [void]$candidatePaths.Add($requestedResolved)
    foreach ($name in $OutputDirFileNames) {
        if (-not [string]::IsNullOrWhiteSpace($name)) {
            [void]$candidatePaths.Add((Join-Path $OutputDir $name))
        }
    }
    foreach ($relativePath in $RepoRelativeCandidates) {
        if (-not [string]::IsNullOrWhiteSpace($relativePath)) {
            [void]$candidatePaths.Add((Resolve-RepoRelativePath -RepoRoot $RepoRoot -Path $relativePath))
        }
    }

    $seen = @{}
    $existing = @()
    foreach ($candidatePath in $candidatePaths) {
        $fullPath = [IO.Path]::GetFullPath($candidatePath)
        if ($seen.ContainsKey($fullPath)) {
            continue
        }
        $seen[$fullPath] = $true
        if (Test-Path -LiteralPath $fullPath -PathType Leaf) {
            $existing += Get-Item -LiteralPath $fullPath
        }
    }
    if (@($existing).Count -eq 0) {
        return $requestedResolved
    }
    return (@($existing) | Sort-Object -Property LastWriteTimeUtc, FullName -Descending | Select-Object -First 1).FullName
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Value
    )
    $parent = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
}

function ConvertFrom-ToolJson {
    param(
        [Parameter(Mandatory = $true)][string]$ToolName,
        [AllowEmptyString()][string]$Text
    )
    $trimmed = ($Text -split "`r?`n" | Where-Object { $_.TrimStart().StartsWith('{') } | Select-Object -Last 1)
    if ([string]::IsNullOrWhiteSpace($trimmed)) {
        return [pscustomobject]@{
            ok = $false
            decision = 'tool_output_missing_json'
            failReason = "missing-json:$ToolName"
        }
    }
    try {
        return $trimmed | ConvertFrom-Json
    } catch {
        return [pscustomobject]@{
            ok = $false
            decision = 'tool_output_invalid_json'
            failReason = "invalid-json:$ToolName"
        }
    }
}

function Invoke-Toolbox {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$ToolName,
        [Parameter(Mandatory = $true)][hashtable]$Payload
    )
    $scriptPath = Join-Path $RepoRoot 'scripts\awx_mcp_toolbox.py'
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        return [pscustomobject]@{
            ExitCode = 127
            Json = [pscustomobject]@{
                ok = $false
                decision = 'tool_missing'
                failReason = "missing:scripts/awx_mcp_toolbox.py"
            }
            Raw = ''
            ElapsedMs = 0
        }
    }
    $payloadJson = $Payload | ConvertTo-Json -Depth 30 -Compress
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Push-Location $RepoRoot
    try {
        $lines = $payloadJson | python $scriptPath --input-json - $ToolName 2>&1 |
            ForEach-Object { $_.ToString() }
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
        $sw.Stop()
    }
    $raw = $lines -join "`n"
    return [pscustomobject]@{
        ExitCode = $exitCode
        Json = ConvertFrom-ToolJson -ToolName $ToolName -Text $raw
        Raw = $raw
        ElapsedMs = [int]$sw.ElapsedMilliseconds
    }
}

function Get-JsonBool {
    param(
        [object]$Value,
        [bool]$Default = $false
    )
    if ($null -eq $Value) { return $Default }
    if ($Value -is [bool]) { return $Value }
    $text = [string]$Value
    if ($text -match '^(?i:true|1|yes)$') { return $true }
    if ($text -match '^(?i:false|0|no)$') { return $false }
    return $Default
}

function Get-JsonInt {
    param(
        [object]$Value,
        [int]$Default = 0
    )
    try {
        if ($null -eq $Value) { return $Default }
        return [int]$Value
    } catch {
        return $Default
    }
}

function Get-NestedValue {
    param(
        [object]$Object,
        [Parameter(Mandatory = $true)][string[]]$Path,
        [object]$Default = $null
    )
    $current = $Object
    foreach ($part in $Path) {
        if ($null -eq $current) { return $Default }
        if ($current -is [System.Collections.IDictionary]) {
            if (-not $current.Contains($part)) { return $Default }
            $current = $current[$part]
            continue
        }
        $prop = $current.PSObject.Properties[$part]
        if ($null -eq $prop) { return $Default }
        $current = $prop.Value
    }
    if ($null -eq $current) { return $Default }
    return $current
}

function Count-SecretPatterns {
    param([AllowEmptyString()][string]$Text)
    $pattern = 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Authorization\s*:|Cookie\s*:|Bearer\s+[A-Za-z0-9._~+/-]+=*|SUPABASE_ACCESS_TOKEN\s*='
    return ([regex]::Matches([string]$Text, $pattern)).Count
}

function Count-HighConfidenceSecretPatterns {
    param([AllowEmptyString()][string]$Text)
    $pattern = 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Bearer\s+[A-Za-z0-9._~+/-]+=*|SUPABASE_ACCESS_TOKEN\s*=\S+'
    return ([regex]::Matches([string]$Text, $pattern)).Count
}

function ConvertTo-SafeProbeText {
    param(
        [AllowNull()][object]$Value,
        [int]$MaxLength = 160
    )
    if ($null -eq $Value) {
        return ''
    }
    $text = [string]$Value
    if ([string]::IsNullOrEmpty($text)) {
        return ''
    }
    if ((Count-SecretPatterns -Text $text) -gt 0) {
        return '<redacted-secret-like-value>'
    }
    if ($text -match '(?i)(?:[A-Z]:\\|\\\\)') {
        return '<redacted-path-like-value>'
    }
    if ($text.Length -gt $MaxLength) {
        return $text.Substring(0, $MaxLength) + '...'
    }
    return $text
}

function Get-Sha256Hex {
    param([AllowEmptyString()][string]$Text)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes([string]$Text)
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '')
    } finally {
        $sha.Dispose()
    }
}

function ConvertTo-HtmlText {
    param([AllowNull()][object]$Value)
    return [Net.WebUtility]::HtmlEncode([string]$Value)
}

function Get-ProbeKeywords {
    return @(
        'TraceStore',
        'MDC',
        'Soak',
        'Probe',
        'observability',
        'debug',
        'heartbeat',
        'Supabase',
        'Browser',
        'Computer',
        'Plan DSL',
        'CFVM',
        'Breadcrumb'
    )
}

function New-ProbeViewerHtml {
    param([Parameter(Mandatory = $true)][object]$Summary)

    $fields = @(
        @('Decision', $Summary.decision),
        @('Desktop local ready', $Summary.desktopControlLoop.localReady),
        @('Completion ready', $Summary.desktopControlLoop.completionReady),
        @('Source ownership', $Summary.patchDropManualDefault.sourceOwnership),
        @('PatchDrop queue', $Summary.patchDropManualDefault.queue),
        @('Top-level patches', $Summary.patchDropManualDefault.topLevelPatchCount),
        @('Pending producers', $Summary.patchDropManualDefault.pendingProducerCount),
        @('Producer evidence', $Summary.patchDropManualDefault.producerEvidence),
        @('Supabase status', $Summary.supabase.projectScopeStatus),
        @('Supabase read-only', $Summary.supabase.readOnly),
        @('Browser lane', $Summary.browser.status),
        @('Browser proof artifact', $Summary.browser.artifact),
        @('Computer lane', $Summary.computer.status),
        @('Computer proof artifact', $Summary.computer.artifact),
        @('Goal-next status', $Summary.goalNextStatus.statusDecision),
        @('Goal-next failure', $Summary.goalNextStatus.failureClassification),
        @('Trace-memory proof', $Summary.traceMemoryRuntimeProof.status),
        @('Trace-memory route', $Summary.traceMemoryRuntimeProof.traceMemoryRouteDecision),
        @('Trace-memory stale', $Summary.traceMemoryRuntimeProof.stale),
        @('Source-health requirement mode', $Summary.sourceHealthDebug.requirementEvidenceNeededDetailMode),
        @('Source-health omitted details', $Summary.sourceHealthDebug.requirementEvidenceNeededDetailsOmitted),
        @('Attachment probe', $Summary.attachmentProbe.status),
        @('Attachment samples', @($Summary.attachmentProbe.samples).Count),
        @('Attachment sample seed', $Summary.attachmentProbe.sampleSeedHash12),
        @('Source probe', $Summary.sourceProbe.status),
        @('Source engine', $Summary.sourceProbe.engine),
        @('Source samples', @($Summary.sourceProbe.samples).Count),
        @('Source sample seed', $Summary.sourceProbe.sampleSeedHash12),
        @('Secret hits', $Summary.rawSecretPatternHits)
    )

    $rows = ($fields | ForEach-Object {
        $label = ConvertTo-HtmlText $_[0]
        $value = ConvertTo-HtmlText $_[1]
        "        <tr><th scope=`"row`">$label</th><td>$value</td></tr>"
    }) -join "`n"

    $generatedAt = ConvertTo-HtmlText $Summary.generatedAt
    $summaryName = ConvertTo-HtmlText $Summary.artifacts.summary
    $eventsName = ConvertTo-HtmlText $Summary.artifacts.events
    $viewerName = ConvertTo-HtmlText $Summary.artifacts.viewer
    $evidenceNeededCount = ConvertTo-HtmlText (@($Summary.evidence_needed).Count)
    $supportingCount = ConvertTo-HtmlText (@($Summary.supportingEvidenceNeeded).Count)

    return @"
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>SMB Decommission Debug Probe</title>
  <style>
    :root { color-scheme: light dark; font-family: Arial, sans-serif; }
    body { margin: 0; padding: 24px; line-height: 1.45; }
    main { max-width: 920px; margin: 0 auto; }
    h1 { font-size: 24px; margin: 0 0 8px; }
    .meta { color: #666; margin: 0 0 20px; }
    table { width: 100%; border-collapse: collapse; border: 1px solid #bbb; }
    th, td { padding: 10px 12px; border-top: 1px solid #ccc; text-align: left; vertical-align: top; }
    th { width: 260px; background: rgba(127, 127, 127, 0.12); }
    .artifacts { margin-top: 20px; padding: 12px; border: 1px solid #bbb; }
    code { font-family: Consolas, monospace; }
  </style>
</head>
<body>
  <main data-awx-viewer="smb-decommission">
    <h1>SMB Decommission Debug Probe</h1>
    <p class="meta">Generated at <code>$generatedAt</code>. Browser-safe summary only; raw paths, raw URLs, headers, cookies, prompts, and tokens are not rendered.</p>
    <table aria-label="Probe status">
      <tbody>
$rows
        <tr><th scope="row">Evidence-needed count</th><td>$evidenceNeededCount</td></tr>
        <tr><th scope="row">Supporting-evidence count</th><td>$supportingCount</td></tr>
      </tbody>
    </table>
    <section class="artifacts" aria-label="Artifact names">
      <strong>Artifact names</strong>
      <p><code>$summaryName</code>, <code>$eventsName</code>, <code>$viewerName</code></p>
    </section>
  </main>
</body>
</html>
"@
}

function Invoke-JanitorInventory {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)
    $janitor = Join-Path $RepoRoot '__patch_drop__\janitor_inventory.ps1'
    if (-not (Test-Path -LiteralPath $janitor)) {
        return [pscustomobject]@{
            ran = $false
            exitCode = 127
            sourceEditLocks = -1
            activeTopLevelPatches = -1
            outputLineCount = 0
        }
    }
    Push-Location $RepoRoot
    try {
        $lines = & powershell -NoProfile -ExecutionPolicy Bypass -File $janitor 2>&1 |
            ForEach-Object { $_.ToString() }
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    $text = $lines -join "`n"
    $locks = -1
    if ($text -match 'source-edit-locks=(\d+)') {
        $locks = [int]$Matches[1]
    }
    $patches = -1
    if ($text -match 'active-top-level-patches=(\d+)') {
        $patches = [int]$Matches[1]
    }
    return [pscustomobject]@{
        ran = $true
        exitCode = $exitCode
        sourceEditLocks = $locks
        activeTopLevelPatches = $patches
        outputLineCount = @($lines).Count
    }
}

function Read-ProofJson {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$MissingReason
    )
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [pscustomobject]@{
            present = $false
            text = ''
            json = $null
            secretHits = 0
            defaultSummary = [ordered]@{
                status = 'not_run_optional'
                reason = $MissingReason
                mutationAllowed = $false
            }
        }
    }

    $text = Get-Content -Raw -LiteralPath $Path -Encoding UTF8
    $secretHits = Count-SecretPatterns -Text $text
    try {
        $json = $text | ConvertFrom-Json
    } catch {
        return [pscustomobject]@{
            present = $true
            text = $text
            json = $null
            secretHits = $secretHits
            defaultSummary = [ordered]@{
            status = 'proof_invalid_json'
            reason = 'proof artifact is present but not valid JSON'
            artifact = (Split-Path -Leaf $Path)
            rawSecretPatternHits = $secretHits
            mutationAllowed = $false
        }
        }
    }

    return [pscustomobject]@{
        present = $true
        text = $text
        json = $json
        secretHits = $secretHits
        defaultSummary = $null
    }
}

function New-BrowserProofSummary {
    param([Parameter(Mandatory = $true)][string]$Path)

    $proof = Read-ProofJson -Path $Path -MissingReason 'run only when UI proof is required'
    if (-not $proof.present -or $null -eq $proof.json) {
        return $proof.defaultSummary
    }

    $json = $proof.json
    $ok = Get-JsonBool (Get-NestedValue $json @('ok') $false)
    $rawUrlStoredValue = Get-NestedValue $json @('rawUrlStored') $null
    if ($null -eq $rawUrlStoredValue) {
        $rawUrlStoredValue = Get-NestedValue $json @('storesRawUrl') $true
    }
    $rawUrlStored = Get-JsonBool $rawUrlStoredValue $true
    $screenshotStoredValue = Get-NestedValue $json @('screenshotStored') $null
    if ($null -eq $screenshotStoredValue) {
        $screenshotStoredValue = Get-NestedValue $json @('storesScreenshotPath') $true
    }
    $screenshotStored = Get-JsonBool $screenshotStoredValue $true
    $publicDomain = Get-JsonBool (Get-NestedValue $json @('publicDomain') $false)
    $windowsAbsPathHits = Get-JsonInt (Get-NestedValue $json @('windowsAbsPathHits') 0)
    $rawSecretHits = $proof.secretHits + (Get-JsonInt (Get-NestedValue $json @('rawSecretPatternHits') 0))
    $safe = $ok -and (-not $rawUrlStored) -and (-not $screenshotStored) -and ($windowsAbsPathHits -eq 0) -and ($rawSecretHits -eq 0)

    return [ordered]@{
        status = if ($safe) { 'verified_supporting' } elseif ($ok) { 'proof_present_redaction_risk' } else { 'proof_present_not_ok' }
        artifact = ConvertTo-SafeProbeText (Split-Path -Leaf $Path)
        schemaVersion = ConvertTo-SafeProbeText (Get-NestedValue $json @('schemaVersion') '')
        decision = ConvertTo-SafeProbeText (Get-NestedValue $json @('decision') '')
        ok = $ok
        localhost = Get-JsonBool (Get-NestedValue $json @('localhost') $false)
        publicDomain = $publicDomain
        rawUrlStored = $rawUrlStored
        screenshotCaptured = Get-JsonBool (Get-NestedValue $json @('screenshotCaptured') $false)
        screenshotStored = $screenshotStored
        screenshotByteLength = Get-JsonInt (Get-NestedValue $json @('screenshotByteLength') (Get-NestedValue $json @('screenshotByteCount') 0))
        windowsAbsPathHits = $windowsAbsPathHits
        rawSecretPatternHits = $rawSecretHits
        mutationAllowed = $false
    }
}

function New-ComputerProofSummary {
    param([Parameter(Mandatory = $true)][string]$Path)

    $proof = Read-ProofJson -Path $Path -MissingReason 'run only when visible Windows GUI proof is required'
    if (-not $proof.present -or $null -eq $proof.json) {
        return $proof.defaultSummary
    }

    $json = $proof.json
    $ok = Get-JsonBool (Get-NestedValue $json @('ok') $false)
    $reachable = Get-JsonBool (Get-NestedValue $json @('reachable') $false)
    $helperCountOnly = (Get-JsonBool (Get-NestedValue $json @('helperCountOnly') $false)) -or
        (Get-JsonBool (Get-NestedValue $json @('countOnly') $false))
    $storesRawAppNames = Get-JsonBool (Get-NestedValue $json @('storesRawAppNames') $true) $true
    $storesWindowTitles = (Get-JsonBool (Get-NestedValue $json @('storesWindowTitles') $true) $true) -or
        (Get-JsonBool (Get-NestedValue $json @('rawWindowTitlesStored') $false))
    $safePendingProof = Get-JsonBool (Get-NestedValue $json @('safePendingProof') $false)
    $rawSecretHits = $proof.secretHits +
        (Get-JsonInt (Get-NestedValue $json @('rawSecretPatternHits') 0)) +
        (Get-JsonInt (Get-NestedValue $json @('helperSecretPatternHits') 0))
    $safe = $ok -and $reachable -and $helperCountOnly -and (-not $storesRawAppNames) -and (-not $storesWindowTitles) -and ($rawSecretHits -eq 0)
    $safePending = (-not $ok) -and $safePendingProof -and $reachable -and $helperCountOnly -and (-not $storesRawAppNames) -and (-not $storesWindowTitles) -and ($rawSecretHits -eq 0)

    return [ordered]@{
        status = if ($safe) { 'verified_supporting' } elseif ($safePending) { 'safe_pending_supporting' } elseif ($ok) { 'proof_present_redaction_risk' } else { 'proof_present_not_ok' }
        artifact = ConvertTo-SafeProbeText (Split-Path -Leaf $Path)
        schemaVersion = ConvertTo-SafeProbeText (Get-NestedValue $json @('schemaVersion') '')
        decision = ConvertTo-SafeProbeText (Get-NestedValue $json @('decision') '')
        ok = $ok
        reachable = $reachable
        safePendingProof = $safePendingProof
        evidenceNeeded = ConvertTo-SafeProbeText (Get-NestedValue $json @('evidenceNeeded') '')
        guiOnly = Get-JsonBool (Get-NestedValue $json @('guiOnly') $false)
        helperCountOnly = $helperCountOnly
        storesRawAppNames = $storesRawAppNames
        storesWindowTitles = $storesWindowTitles
        appCount = Get-JsonInt (Get-NestedValue $json @('appCount') 0)
        targetableWindowCount = Get-JsonInt (Get-NestedValue $json @('targetableWindowCount') 0)
        rawSecretPatternHits = $rawSecretHits
        mutationAllowed = $false
    }
}

function New-GoalNextStatusSummary {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $path = Join-Path $RepoRoot 'var\codex-smoke\goal-next-auto.status.json'
    $statusJson = Read-ProofJson -Path $path -MissingReason 'run scripts\goal_next_auto.ps1 -Root . -Status or -EnsureFresh'
    if (-not $statusJson.present -or $null -eq $statusJson.json) {
        return $statusJson.defaultSummary
    }

    $json = $statusJson.json
    $rawSecretHits = $statusJson.secretHits +
        (Get-JsonInt (Get-NestedValue $json @('secretHits') 0)) +
        (Get-JsonInt (Get-NestedValue $json @('rawSecretPatternHits') 0))
    $computerSecretHits = Get-JsonInt (Get-NestedValue $json @('computerUse','secretHits') 0)
    $browserSecretHits = Get-JsonInt (Get-NestedValue $json @('browserUse','secretHits') 0)
    $externalGateSecretHits = Get-JsonInt (Get-NestedValue $json @('externalInputGate','secretHits') 0)
    $safe = ($rawSecretHits + $computerSecretHits + $browserSecretHits + $externalGateSecretHits) -eq 0

    return [ordered]@{
        status = if ($safe) { 'observed' } else { 'proof_present_redaction_risk' }
        artifact = 'goal-next-auto.status.json'
        schemaVersion = ConvertTo-SafeProbeText (Get-NestedValue $json @('schemaVersion') '')
        statusDecision = ConvertTo-SafeProbeText (Get-NestedValue $json @('statusDecision') 'unknown')
        failureClassification = ConvertTo-SafeProbeText (Get-NestedValue $json @('failureClassification') '')
        staleLatest = Get-JsonBool (Get-NestedValue $json @('staleLatest') $true) $true
        firstAction = ConvertTo-SafeProbeText (Get-NestedValue $json @('firstAction') '')
        computerUseDecision = ConvertTo-SafeProbeText (Get-NestedValue $json @('computerUse','decision') '')
        computerUseSafePending = Get-JsonBool (Get-NestedValue $json @('computerUse','safePendingProof') $false)
        browserUseDecision = ConvertTo-SafeProbeText (Get-NestedValue $json @('browserUse','decision') '')
        browserUseSafePending = Get-JsonBool (Get-NestedValue $json @('browserUse','safePendingProof') $false)
        externalInputGateStatus = ConvertTo-SafeProbeText (Get-NestedValue $json @('externalInputGate','status') '')
        externalInputGateAction = ConvertTo-SafeProbeText (Get-NestedValue $json @('externalInputGate','action') '')
        mutationAllowed = Get-JsonBool (Get-NestedValue $json @('externalInputGate','mutationAllowed') $false)
        rawSecretPatternHits = $rawSecretHits + $computerSecretHits + $browserSecretHits + $externalGateSecretHits
    }
}

function New-TraceMemoryRuntimeProofSummary {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $path = Join-Path $RepoRoot 'var\codex-smoke\goal-next-auto.status.json'
    $statusJson = Read-ProofJson -Path $path -MissingReason 'run scripts\goal_next_auto.ps1 -Root . -Status or -EnsureFresh'
    if (-not $statusJson.present -or $null -eq $statusJson.json) {
        return [ordered]@{
            status = 'evidence_needed'
            artifact = 'goal-next-auto.status.json'
            decision = 'evidence_needed'
            present = $false
            parsed = $false
            evidenceNeeded = @('trace_memory_runtime_proof_status_missing')
            mutationAllowed = $false
            rawSecretPatternHits = Get-JsonInt (Get-NestedValue $statusJson.defaultSummary @('rawSecretPatternHits') 0)
        }
    }

    $proofNode = Get-NestedValue $statusJson.json @('traceMemoryRuntimeProof') $null
    if ($null -eq $proofNode) {
        return [ordered]@{
            status = 'evidence_needed'
            artifact = 'goal-next-auto.status.json'
            decision = 'evidence_needed'
            present = $false
            parsed = $false
            evidenceNeeded = @('trace_memory_runtime_proof_missing')
            mutationAllowed = $false
            rawSecretPatternHits = $statusJson.secretHits
        }
    }

    $decision = ConvertTo-SafeProbeText (Get-NestedValue $proofNode @('decision') '')
    $present = Get-JsonBool (Get-NestedValue $proofNode @('present') $false)
    $parsed = Get-JsonBool (Get-NestedValue $proofNode @('parsed') $false)
    $ok = Get-JsonBool (Get-NestedValue $proofNode @('ok') $false)
    $statusCode = Get-JsonInt (Get-NestedValue $proofNode @('status') 0)
    $stale = Get-JsonBool (Get-NestedValue $proofNode @('stale') $true) $true
    $requireTraceMemory = Get-JsonBool (Get-NestedValue $proofNode @('requireTraceMemory') $false)
    $seedsSelfProbe = Get-JsonBool (Get-NestedValue $proofNode @('seedsSelfProbe') $false)
    $traceMemoryPresent = Get-JsonBool (Get-NestedValue $proofNode @('traceMemoryPresent') $false)
    $routeDecision = ConvertTo-SafeProbeText (Get-NestedValue $proofNode @('traceMemoryRouteDecision') '')
    $cfvmOffered = ConvertTo-SafeProbeText (Get-NestedValue $proofNode @('traceMemoryCfvmOffered') '')
    $cfvmPatternId = ConvertTo-SafeProbeText (Get-NestedValue $proofNode @('traceMemoryCfvmPatternId') '')
    $seedStatus = Get-JsonInt (Get-NestedValue $proofNode @('traceMemorySeedStatus') 0)
    $mutationAllowed = Get-JsonBool (Get-NestedValue $proofNode @('mutationAllowed') $false)
    $storesRawPrompt = Get-JsonBool (Get-NestedValue $proofNode @('storesRawPrompt') $true) $true
    $storesRawModel = Get-JsonBool (Get-NestedValue $proofNode @('storesRawModel') $true) $true
    $storesRawSsePayload = Get-JsonBool (Get-NestedValue $proofNode @('storesRawSsePayload') $true) $true
    $rawPromptHits = Get-JsonInt (Get-NestedValue $proofNode @('rawPromptHits') 0)
    $rawModelHits = Get-JsonInt (Get-NestedValue $proofNode @('rawModelHits') 0)
    $rawSecretHits = $statusJson.secretHits +
        (Get-JsonInt (Get-NestedValue $proofNode @('secretHits') 0)) +
        (Get-JsonInt (Get-NestedValue $proofNode @('rawSecretPatternHits') 0))
    $evidenceNeeded = @(
        @(Get-NestedValue $proofNode @('evidenceNeeded') @()) |
            ForEach-Object { ConvertTo-SafeProbeText $_ } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    )

    $redactionSafe = (-not $mutationAllowed) -and
        (-not $storesRawPrompt) -and
        (-not $storesRawModel) -and
        (-not $storesRawSsePayload) -and
        ($rawSecretHits -eq 0) -and
        ($rawPromptHits -eq 0) -and
        ($rawModelHits -eq 0)
    $hasTraceMemoryShape = $present -and $parsed -and ($statusCode -eq 200) -and
        $requireTraceMemory -and
        $seedsSelfProbe -and
        $traceMemoryPresent -and
        (-not [string]::IsNullOrWhiteSpace($routeDecision)) -and
        ((-not [string]::IsNullOrWhiteSpace($cfvmOffered)) -or (-not [string]::IsNullOrWhiteSpace($cfvmPatternId))) -and
        ($seedStatus -eq 200)
    $onlyStaleEvidenceNeeded = (@($evidenceNeeded).Count -eq 0) -or
        ((@($evidenceNeeded) | Where-Object { $_ -ne 'trace_memory_runtime_proof_stale' }).Count -eq 0)
    $verified = $redactionSafe -and $hasTraceMemoryShape -and $ok -and (-not $stale) -and ($decision -eq 'ok')
    $safePending = $redactionSafe -and $hasTraceMemoryShape -and $stale -and $onlyStaleEvidenceNeeded
    $summaryStatus = if (-not $redactionSafe) {
        'proof_present_redaction_risk'
    } elseif ($verified) {
        'verified_runtime'
    } elseif ($safePending) {
        'safe_pending_runtime'
    } else {
        'evidence_needed'
    }

    return [ordered]@{
        status = $summaryStatus
        artifact = 'goal-next-auto.status.json'
        decision = $decision
        present = $present
        parsed = $parsed
        ok = $ok
        statusCode = $statusCode
        stale = $stale
        requireTraceMemory = $requireTraceMemory
        seedsSelfProbe = $seedsSelfProbe
        traceMemoryPresent = $traceMemoryPresent
        traceMemoryRouteDecision = $routeDecision
        traceMemoryCfvmOffered = $cfvmOffered
        traceMemoryCfvmPatternId = $cfvmPatternId
        traceMemorySeedStatus = $seedStatus
        evidenceNeeded = @($evidenceNeeded)
        mutationAllowed = $mutationAllowed
        storesRawPrompt = $storesRawPrompt
        storesRawModel = $storesRawModel
        storesRawSsePayload = $storesRawSsePayload
        rawPromptHits = $rawPromptHits
        rawModelHits = $rawModelHits
        rawSecretPatternHits = $rawSecretHits
    }
}

function New-SourceHealthDebugSummary {
    param([Parameter(Mandatory = $true)][string]$RepoRoot)

    $path = Join-Path $RepoRoot 'verification\source-health-scorecard.json'
    $healthJson = Read-ProofJson -Path $path -MissingReason 'run python scripts\source_health_scorecard.py --root . --output verification\source-health-scorecard.json'
    if (-not $healthJson.present -or $null -eq $healthJson.json) {
        return $healthJson.defaultSummary
    }

    $json = $healthJson.json
    $failurePatternKind = Get-NestedValue $json @('failurePatternKind') ''
    if ([string]::IsNullOrWhiteSpace([string]$failurePatternKind)) {
        $failurePatternKind = Get-NestedValue $json @('failurePatternPrediction','failurePatternKind') ''
    }
    $patternId = Get-NestedValue $json @('patternId') ''
    if ([string]::IsNullOrWhiteSpace([string]$patternId)) {
        $patternId = Get-NestedValue $json @('failurePatternPrediction','patternId') ''
    }

    $rawSecretHits = $healthJson.secretHits + (Get-JsonInt (Get-NestedValue $json @('rawSecretPatternHits') 0))
    $computerSecretHits = Get-JsonInt (Get-NestedValue $json @('localInteractionProof','computerUse','secretPatternHits') 0)
    $browserSecretHits = Get-JsonInt (Get-NestedValue $json @('localInteractionProof','browserUse','secretPatternHits') 0)
    $safe = ($rawSecretHits + $computerSecretHits + $browserSecretHits) -eq 0

    return [ordered]@{
        status = if ($safe) { 'observed' } else { 'proof_present_redaction_risk' }
        artifact = 'source-health-scorecard.json'
        schemaVersion = ConvertTo-SafeProbeText (Get-NestedValue $json @('schemaVersion') '')
        decision = ConvertTo-SafeProbeText (Get-NestedValue $json @('decision') '')
        strictEvidenceAdjustedScore = Get-NestedValue $json @('strictEvidenceAdjustedScore') 0
        riskCount = Get-JsonInt (Get-NestedValue $json @('riskCount') 0)
        activeRiskCount = Get-JsonInt (Get-NestedValue $json @('activeRiskCount') 0)
        evidenceNeededCount = Get-JsonInt (Get-NestedValue $json @('evidenceNeededCount') 0)
        failurePatternKind = ConvertTo-SafeProbeText $failurePatternKind
        patternId = ConvertTo-SafeProbeText $patternId
        requirementEvidenceNeededDetailMode = ConvertTo-SafeProbeText (Get-NestedValue $json @('completionAuditRequirementEvidenceNeededDetailMode') '')
        requirementEvidenceNeededCompactRowCount = Get-JsonInt (Get-NestedValue $json @('completionAuditRequirementEvidenceNeededCompactRowCount') 0)
        requirementEvidenceNeededDetailsOmitted = Get-JsonInt (Get-NestedValue $json @('completionAuditRequirementEvidenceNeededDetailsOmitted') 0)
        requirementEvidenceNeededMaxLength = Get-JsonInt (Get-NestedValue $json @('completionAuditRequirementEvidenceNeededMaxLength') 0)
        requirementEvidenceNeededDetailHintPresent = -not [string]::IsNullOrWhiteSpace([string](Get-NestedValue $json @('completionAuditRequirementEvidenceNeededDetailHint') ''))
        localInteractionComputerDecision = ConvertTo-SafeProbeText (Get-NestedValue $json @('localInteractionProof','computerUse','decision') '')
        localInteractionComputerSafePending = Get-JsonBool (Get-NestedValue $json @('localInteractionProof','computerUse','safePendingProof') $false)
        localInteractionBrowserDecision = ConvertTo-SafeProbeText (Get-NestedValue $json @('localInteractionProof','browserUse','decision') '')
        localInteractionBrowserSafePending = Get-JsonBool (Get-NestedValue $json @('localInteractionProof','browserUse','safePendingProof') $false)
        rawSecretPatternHits = $rawSecretHits + $computerSecretHits + $browserSecretHits
        mutationAllowed = $false
    }
}

function New-AttachmentProbeSummary {
    param(
        [AllowEmptyString()][string]$Path,
        [ValidateRange(0, 50)][int]$SampleCount = 5
    )

    $keywords = Get-ProbeKeywords

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return [ordered]@{
            status = 'not_configured'
            reason = 'pass -AttachmentPath to derive a hash-only probe map'
            mutationAllowed = $false
        }
    }

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return [ordered]@{
            status = 'evidence_needed'
            reason = 'attachment path missing'
            fileName = (Split-Path -Leaf $Path)
            pathHash12 = (Get-Sha256Hex $Path).Substring(0, 12)
            mutationAllowed = $false
        }
    }

    $text = Get-Content -Raw -LiteralPath $Path -Encoding UTF8
    $lines = $text -split "`r?`n"
    $keywordCounts = [ordered]@{}
    foreach ($keyword in $keywords) {
        $keywordCounts[$keyword] = ([regex]::Matches($text, [regex]::Escape($keyword))).Count
    }

    $fileSha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash
    $sampleSeedHash12 = (Get-Sha256Hex "attachment|$fileSha256|$SampleCount").Substring(0, 12)
    $lineHits = New-Object System.Collections.Generic.List[object]
    for ($idx = 0; $idx -lt $lines.Count; $idx++) {
        $line = [string]$lines[$idx]
        $terms = @($keywords | Where-Object { $line.Contains($_) })
        if (@($terms).Count -eq 0) {
            continue
        }
        $lineSha = Get-Sha256Hex $line
        $rank = Get-Sha256Hex "$fileSha256|$($idx + 1)|$lineSha"
        $lineHits.Add([pscustomobject]@{
            line = $idx + 1
            length = $line.Length
            sha12 = $lineSha.Substring(0, 12)
            terms = @($terms)
            rank = $rank
        }) | Out-Null
    }

    $samples = @(
        $lineHits |
            Sort-Object -Property rank |
            Select-Object -First $SampleCount |
            ForEach-Object {
                [ordered]@{
                    line = $_.line
                    length = $_.length
                    sha12 = $_.sha12
                    terms = @($_.terms)
                }
            }
    )

    $result = [ordered]@{}
    $result['status'] = 'sampled_hash_only'
    $result['fileName'] = (Split-Path -Leaf $Path)
    $result['pathHash12'] = (Get-Sha256Hex $Path).Substring(0, 12)
    $result['fileSha256'] = $fileSha256
    $result['charCount'] = $text.Length
    $result['lineCount'] = $lines.Count
    $result['keywordCounts'] = [pscustomobject]$keywordCounts
    $result['matchedLineCount'] = $lineHits.Count
    $result['sampleStrategy'] = 'deterministic_hash_shuffle'
    $result['sampleSeedHash12'] = $sampleSeedHash12
    $result['sampleLimit'] = $SampleCount
    $result['samples'] = @($samples)
    $result['rawSecretPatternHits'] = (Count-HighConfidenceSecretPatterns -Text $text)
    $result['mutationAllowed'] = $false
    return $result
}

function Get-ProbeKeywordCount {
    param(
        [AllowNull()][object]$KeywordCounts,
        [Parameter(Mandatory = $true)][string]$Keyword
    )
    if ($null -eq $KeywordCounts) { return 0 }
    if ($KeywordCounts -is [System.Collections.IDictionary] -and $KeywordCounts.Contains($Keyword)) {
        try { return [int]$KeywordCounts[$Keyword] } catch { return 0 }
    }
    $prop = $KeywordCounts.PSObject.Properties[$Keyword]
    if ($null -eq $prop) { return 0 }
    try { return [int]$prop.Value } catch { return 0 }
}

function Get-RepoRelativeText {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][string]$Path
    )
    $rootFull = [IO.Path]::GetFullPath($RepoRoot).TrimEnd('\', '/')
    $pathFull = [IO.Path]::GetFullPath($Path)
    if ($pathFull.StartsWith($rootFull, [StringComparison]::OrdinalIgnoreCase)) {
        return $pathFull.Substring($rootFull.Length).TrimStart('\', '/')
    }
    return Split-Path -Leaf $pathFull
}

function Test-SourceProbeFixturePath {
    param(
        [AllowEmptyString()][string]$RootKey,
        [AllowEmptyString()][string]$RelativePath
    )
    if ($RootKey -eq 'testJava' -or $RootKey -eq 'testResources') {
        return $true
    }
    $fileName = Split-Path -Leaf ([string]$RelativePath)
    return $fileName -match '(?i)(^test_.*\.py$|.*_test\.py$|.*_tests\.ps1$|.*\.test\.(js|mjs|ts)$|.*Test\.(java|kt|groovy)$)'
}

function New-SourceProbeSummary {
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [Parameter(Mandatory = $true)][object]$AttachmentProbe,
        [ValidateRange(0, 50)][int]$SampleCount = 8
    )

    if ([string](Get-NestedValue $AttachmentProbe @('status') '') -ne 'sampled_hash_only') {
        return [ordered]@{
            status = 'not_configured'
            reason = 'requires sampled_hash_only attachment probe'
            mutationAllowed = $false
        }
    }

    $keywordCounts = Get-NestedValue $AttachmentProbe @('keywordCounts') $null
    $keywords = @(Get-ProbeKeywords | Where-Object { (Get-ProbeKeywordCount -KeywordCounts $keywordCounts -Keyword $_) -gt 0 })
    if (@($keywords).Count -eq 0) {
        return [ordered]@{
            status = 'not_configured'
            reason = 'attachment has no tracked probe keywords'
            mutationAllowed = $false
        }
    }

    $roots = [ordered]@{
        mainJava = 'main\java'
        mainResources = 'main\resources'
        testJava = 'src\test\java'
        testResources = 'src\test\resources'
        appJavaClean = 'app\src\main\java_clean'
        appResources = 'app\src\main\resources'
        scripts = 'scripts'
    }
    $extensions = @('.java', '.kt', '.kts', '.groovy', '.ps1', '.py', '.js', '.html', '.json', '.yaml', '.yml', '.md', '.txt')
    $lineHits = New-Object System.Collections.Generic.List[object]
    $rootCounts = [ordered]@{}
    $filesByRoot = [ordered]@{}
    $existingRootArgs = New-Object System.Collections.Generic.List[string]
    $scannedFileCount = 0
    $matchedFileCount = 0
    $matchedLineCount = 0
    $secretHits = 0
    $testFixtureSecretHits = 0
    $attachmentHash = [string](Get-NestedValue $AttachmentProbe @('fileSha256') '')
    $keywordSeed = (@($keywords | Sort-Object) -join '|')
    $sampleSeedHash12 = (Get-Sha256Hex "source|$attachmentHash|$SampleCount|$keywordSeed").Substring(0, 12)
    $engine = 'powershell_fallback'
    $matchedFiles = @{}

    foreach ($rootKey in $roots.Keys) {
        $rootPath = Join-Path $RepoRoot $roots[$rootKey]
        if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) {
            $rootCounts[$rootKey] = [ordered]@{ exists = $false; scannedFileCount = 0; matchedFileCount = 0; matchedLineCount = 0 }
            $filesByRoot[$rootKey] = @()
            continue
        }

        $files = @(Get-ChildItem -LiteralPath $rootPath -Recurse -File -ErrorAction SilentlyContinue |
            Where-Object { $extensions -contains $_.Extension.ToLowerInvariant() })
        $filesByRoot[$rootKey] = @($files)
        $scannedFileCount += @($files).Count
        $existingRootArgs.Add($roots[$rootKey].Replace('\', '/')) | Out-Null
        $rootCounts[$rootKey] = [ordered]@{
            exists = $true
            scannedFileCount = @($files).Count
            matchedFileCount = 0
            matchedLineCount = 0
        }
    }

    $rgCommand = Get-Command rg -ErrorAction SilentlyContinue
    if ($null -ne $rgCommand -and $existingRootArgs.Count -gt 0) {
        $rgExe = if ([string]::IsNullOrWhiteSpace([string]$rgCommand.Source)) { [string]$rgCommand.Path } else { [string]$rgCommand.Source }
        $rgArgs = @('--no-heading', '--line-number', '--color', 'never', '--max-columns', '500')
        foreach ($keyword in $keywords) {
            $rgArgs += @('-F', '-e', $keyword)
        }
        $rgArgs += '--'
        foreach ($rootArg in $existingRootArgs) {
            $rgArgs += $rootArg
        }
        Push-Location $RepoRoot
        try {
            $rgLines = & $rgExe @rgArgs 2>$null | ForEach-Object { $_.ToString() }
            $rgExitCode = $LASTEXITCODE
        } finally {
            Pop-Location
        }
        if ($rgExitCode -le 1) {
            $engine = 'ripgrep'
            foreach ($hit in $rgLines) {
                if ($hit -notmatch '^(?<path>.+?):(?<line>\d+):(?<text>.*)$') {
                    continue
                }
                $relative = $Matches['path'].Replace('/', '\')
                $lineNumber = [int]$Matches['line']
                $line = [string]$Matches['text']
                $rootKeyForHit = ''
                foreach ($rootKey in $roots.Keys) {
                    $rootRel = $roots[$rootKey].TrimEnd('\')
                    if ($relative.Equals($rootRel, [StringComparison]::OrdinalIgnoreCase) -or
                        $relative.StartsWith($rootRel + '\', [StringComparison]::OrdinalIgnoreCase)) {
                        $rootKeyForHit = $rootKey
                        break
                    }
                }
                if ([string]::IsNullOrWhiteSpace($rootKeyForHit)) {
                    continue
                }
                $terms = @($keywords | Where-Object { $line.Contains($_) })
                if (@($terms).Count -eq 0) {
                    continue
                }
                $fileKey = "$rootKeyForHit|$relative"
                if (-not $matchedFiles.ContainsKey($fileKey)) {
                    $matchedFiles[$fileKey] = $true
                    $matchedFileCount += 1
                    $rootCounts[$rootKeyForHit]['matchedFileCount'] = [int]$rootCounts[$rootKeyForHit]['matchedFileCount'] + 1
                }
                $matchedLineCount += 1
                $rootCounts[$rootKeyForHit]['matchedLineCount'] = [int]$rootCounts[$rootKeyForHit]['matchedLineCount'] + 1
                $secretHitDelta = Count-HighConfidenceSecretPatterns -Text $line
                if (Test-SourceProbeFixturePath -RootKey $rootKeyForHit -RelativePath $relative) {
                    $testFixtureSecretHits += $secretHitDelta
                } else {
                    $secretHits += $secretHitDelta
                }
                $lineSha = Get-Sha256Hex $line
                $relativeSha = Get-Sha256Hex $relative
                $rank = Get-Sha256Hex "$attachmentHash|$relativeSha|$lineNumber|$lineSha"
                $lineHits.Add([pscustomobject]@{
                    root = $rootKeyForHit
                    fileName = Split-Path -Leaf $relative
                    relativePathHash12 = $relativeSha.Substring(0, 12)
                    relativePathLength = $relative.Length
                    line = $lineNumber
                    length = $line.Length
                    sha12 = $lineSha.Substring(0, 12)
                    terms = @($terms)
                    rank = $rank
                }) | Out-Null
            }
        }
    }

    foreach ($rootKey in $roots.Keys) {
        if ($engine -ne 'powershell_fallback') {
            continue
        }
        foreach ($file in @($filesByRoot[$rootKey])) {
            try {
                $lines = [IO.File]::ReadAllLines($file.FullName, [Text.Encoding]::UTF8)
            } catch {
                continue
            }
            $fileMatched = $false
            $relative = Get-RepoRelativeText -RepoRoot $RepoRoot -Path $file.FullName
            for ($idx = 0; $idx -lt $lines.Count; $idx++) {
                $line = [string]$lines[$idx]
                $terms = @($keywords | Where-Object { $line.Contains($_) })
                if (@($terms).Count -eq 0) {
                    continue
                }
                $fileMatched = $true
                $matchedLineCount += 1
                $rootCounts[$rootKey]['matchedLineCount'] = [int]$rootCounts[$rootKey]['matchedLineCount'] + 1
                $secretHitDelta = Count-HighConfidenceSecretPatterns -Text $line
                if (Test-SourceProbeFixturePath -RootKey $rootKey -RelativePath $relative) {
                    $testFixtureSecretHits += $secretHitDelta
                } else {
                    $secretHits += $secretHitDelta
                }
                $lineSha = Get-Sha256Hex $line
                $relativeSha = Get-Sha256Hex $relative
                $rank = Get-Sha256Hex "$attachmentHash|$relativeSha|$($idx + 1)|$lineSha"
                $lineHits.Add([pscustomobject]@{
                    root = $rootKey
                    fileName = $file.Name
                    relativePathHash12 = $relativeSha.Substring(0, 12)
                    relativePathLength = $relative.Length
                    line = $idx + 1
                    length = $line.Length
                    sha12 = $lineSha.Substring(0, 12)
                    terms = @($terms)
                    rank = $rank
                }) | Out-Null
            }
            if ($fileMatched) {
                $matchedFileCount += 1
                $rootCounts[$rootKey]['matchedFileCount'] = [int]$rootCounts[$rootKey]['matchedFileCount'] + 1
            }
        }
    }

    $samples = @(
        $lineHits |
            Sort-Object -Property rank |
            Select-Object -First $SampleCount |
            ForEach-Object {
                [ordered]@{
                    root = $_.root
                    fileName = $_.fileName
                    relativePathHash12 = $_.relativePathHash12
                    relativePathLength = $_.relativePathLength
                    line = $_.line
                    length = $_.length
                    sha12 = $_.sha12
                    terms = @($_.terms)
                }
            }
    )

    return [ordered]@{
        status = 'sampled_hash_only'
        engine = $engine
        activeKeywordCount = @($keywords).Count
        sampleStrategy = 'deterministic_attachment_keyword_hash'
        sampleSeedHash12 = $sampleSeedHash12
        sampleLimit = $SampleCount
        scannedFileCount = $scannedFileCount
        matchedFileCount = $matchedFileCount
        matchedLineCount = $matchedLineCount
        rootCounts = [pscustomobject]$rootCounts
        samples = @($samples)
        rawSecretPatternHits = $secretHits
        testFixtureSecretPatternHits = $testFixtureSecretHits
        mutationAllowed = $false
    }
}

$repoRoot = Resolve-RepoPath -Path $Root
if (-not (Test-Path -LiteralPath $repoRoot -PathType Container)) {
    Write-Error "[AWX][smb-debug-probe] root-missing: $repoRoot"
    exit 2
}

$outDir = if ([IO.Path]::IsPathRooted($OutputDir)) { $OutputDir } else { Join-Path $repoRoot $OutputDir }
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$resolvedBrowserProofPath = Resolve-LatestOptionalProofPath `
    -RepoRoot $repoRoot `
    -OutputDir $outDir `
    -RequestedPath $BrowserProofPath `
    -DefaultPath 'var/codex-smoke/smb-decommission/browser-viewer-current-proof.json' `
    -OutputDirFileNames @('browser-viewer-proof-continuation.json', 'browser-viewer-proof.json', 'browser-ui-smoke.json', 'browser-viewer-current-proof.json') `
    -RepoRelativeCandidates @('var/codex-smoke/smb-decommission-control-tower/browser-viewer-proof-continuation.json', 'var/codex-smoke/smb-decommission-control-tower/browser-viewer-proof.json')
$resolvedComputerProofPath = Resolve-LatestOptionalProofPath `
    -RepoRoot $repoRoot `
    -OutputDir $outDir `
    -RequestedPath $ComputerProofPath `
    -DefaultPath 'var/codex-smoke/computer-use-smoke.json' `
    -OutputDirFileNames @('computer-count-proof-continuation.json', 'computer-count-proof.json', 'computer-use-smoke.json') `
    -RepoRelativeCandidates @('var/codex-smoke/smb-decommission-control-tower/computer-count-proof-continuation.json', 'var/codex-smoke/smb-decommission-control-tower/computer-count-proof.json')
$resolvedAttachmentPath = if ([string]::IsNullOrWhiteSpace($AttachmentPath)) { '' } else { Resolve-RepoRelativePath -RepoRoot $repoRoot -Path $AttachmentPath }

$common = @{
    nodeRole = 'desktop'
    root = '.'
    requestId = 'smb-decommission-debug-probe'
    sessionId = 'smb-decommission'
}

$desktopLoopPayload = @{
    nodeRole = 'desktop'
    root = '.'
    topic = $Topic
    patchdrop_root = '__patch_drop__'
    write_dispatch = $false
    write_producer_kit = $false
    require_producer_bundles = $false
    require_supabase_live_proof = $false
    run_completion_audit = $false
    runtimeProof = @{
        traceStoreExportOk = $false
        agentDbSnapshotOk = $false
    }
    requestId = 'smb-decommission-debug-probe'
    sessionId = 'smb-decommission'
}
$desktopLoop = Invoke-Toolbox -RepoRoot $repoRoot -ToolName 'desktop_control_loop' -Payload $desktopLoopPayload
$supabasePayload = @{}
foreach ($entry in $common.GetEnumerator()) {
    $supabasePayload[$entry.Key] = $entry.Value
}
$supabasePayload.skip_mcp_network_probe = -not $ProbeSupabaseNetwork.IsPresent
$supabaseProbe = Invoke-Toolbox -RepoRoot $repoRoot -ToolName 'supabase_context_probe' -Payload $supabasePayload
$janitor = Invoke-JanitorInventory -RepoRoot $repoRoot

$desktopJson = $desktopLoop.Json
$sourceJson = Get-NestedValue -Object $desktopJson -Path @('sourceScan') -Default ([pscustomobject]@{})
$supabaseJson = $supabaseProbe.Json
$sourceScanExplicitOk = Get-NestedValue $sourceJson @('ok') $null
$sourceScanOk = if ($null -ne $sourceScanExplicitOk) {
    Get-JsonBool $sourceScanExplicitOk
} else {
    $desktopLoop.ExitCode -eq 0 -and
    [string](Get-NestedValue $sourceJson @('decision') '') -eq 'read_only_probe' -and
    (Get-JsonBool (Get-NestedValue $sourceJson @('activeSourceSets','mainJava','exists') $false)) -and
    (Get-JsonBool (Get-NestedValue $sourceJson @('activeSourceSets','mainResources','exists') $false)) -and
    (Get-JsonInt (Get-NestedValue $sourceJson @('secretPatternHits') -1)) -eq 0
}

$desktopEvidenceNeeded = @(Get-NestedValue -Object $desktopJson -Path @('evidence_needed') -Default @())
$desktopSupportingNeeded = @(Get-NestedValue -Object $desktopJson -Path @('supportingEvidenceNeeded') -Default @())
$desktopNextActions = @(Get-NestedValue -Object $desktopJson -Path @('nextActions') -Default @())
$desktopOptionalNextActions = @(Get-NestedValue -Object $desktopJson -Path @('optionalNextActions') -Default @())
$supabaseEvidenceNeeded = @(Get-NestedValue -Object $supabaseJson -Path @('evidence_needed') -Default @())
$patchDropTopLevelCount = Get-JsonInt (Get-NestedValue $sourceJson @('patchDrop','topLevelPatchCount') 0)
$patchDropPendingProducerCount = Get-JsonInt (Get-NestedValue $sourceJson @('patchDrop','pendingProducerCount') 0)
$sourceEditLockCount = Get-JsonInt $janitor.sourceEditLocks -1
$browserProofSummary = New-BrowserProofSummary -Path $resolvedBrowserProofPath
$computerProofSummary = New-ComputerProofSummary -Path $resolvedComputerProofPath
$goalNextStatusSummary = New-GoalNextStatusSummary -RepoRoot $repoRoot
$traceMemoryRuntimeProofSummary = New-TraceMemoryRuntimeProofSummary -RepoRoot $repoRoot
$sourceHealthDebugSummary = New-SourceHealthDebugSummary -RepoRoot $repoRoot
$attachmentProbeSummary = New-AttachmentProbeSummary -Path $resolvedAttachmentPath -SampleCount $AttachmentSampleCount
$sourceProbeSummary = New-SourceProbeSummary -RepoRoot $repoRoot -AttachmentProbe $attachmentProbeSummary -SampleCount $SourceProbeSampleCount
$inputSecretHits =
    (Get-JsonInt (Get-NestedValue $sourceJson @('secretPatternHits') 0)) +
    (Get-JsonInt (Get-NestedValue $browserProofSummary @('rawSecretPatternHits') 0)) +
    (Get-JsonInt (Get-NestedValue $computerProofSummary @('rawSecretPatternHits') 0)) +
    (Get-JsonInt (Get-NestedValue $goalNextStatusSummary @('rawSecretPatternHits') 0)) +
    (Get-JsonInt (Get-NestedValue $traceMemoryRuntimeProofSummary @('rawSecretPatternHits') 0)) +
    (Get-JsonInt (Get-NestedValue $sourceHealthDebugSummary @('rawSecretPatternHits') 0)) +
    (Get-JsonInt (Get-NestedValue $attachmentProbeSummary @('rawSecretPatternHits') 0)) +
    (Get-JsonInt (Get-NestedValue $sourceProbeSummary @('rawSecretPatternHits') 0))
$sourceOwnership = if ($sourceEditLockCount -gt 0) {
    'blocked_source_edit_lock'
} elseif ($patchDropTopLevelCount -gt 0) {
    'blocked_top_level_patch'
} elseif ($sourceEditLockCount -eq -1) {
    'evidence_needed'
} else {
    'desktop_unblocked'
}
$producerEvidence = if ($patchDropPendingProducerCount -gt 0) { 'supporting' } else { 'none' }

$summary = [ordered]@{
    schemaVersion = 'awx.smb_decommission_debug_probe.v1'
    generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    ok = $sourceScanOk -and (Get-JsonBool (Get-NestedValue $desktopJson @('localReady') $false))
    decision = 'desktop_only_probe'
    topic = $Topic
    mutationAllowed = $false
    writeDispatch = $false
    writeProducerKit = $false
    commands = [ordered]@{
        sourceScan = 'desktop_control_loop.sourceScan (reused; no second process)'
        desktopControlLoop = 'python scripts/awx_mcp_toolbox.py --input-json - desktop_control_loop'
        supabaseContextProbe = if ($ProbeSupabaseNetwork.IsPresent) { 'python scripts/awx_mcp_toolbox.py --input-json - supabase_context_probe' } else { 'python scripts/awx_mcp_toolbox.py --input-json - supabase_context_probe (skip_mcp_network_probe=true)' }
        janitorInventory = 'powershell -NoProfile -ExecutionPolicy Bypass -File __patch_drop__\janitor_inventory.ps1'
    }
    janitor = $janitor
    sourceScan = [ordered]@{
        evidenceSource = 'desktop_control_loop.sourceScan'
        exitCode = $desktopLoop.ExitCode
        ok = $sourceScanOk
        decision = [string](Get-NestedValue $sourceJson @('decision') '')
        activeSourceSets = Get-NestedValue $sourceJson @('activeSourceSets') ([pscustomobject]@{})
        patchDrop = Get-NestedValue $sourceJson @('patchDrop') ([pscustomobject]@{})
        secretPatternHits = Get-JsonInt (Get-NestedValue $sourceJson @('secretPatternHits') 0)
        elapsedMs = 0
        containerElapsedMs = $desktopLoop.ElapsedMs
    }
    patchDropManualDefault = [ordered]@{
        mode = 'manual'
        sourceOwnership = $sourceOwnership
        queue = if ($patchDropTopLevelCount -eq 0) { 'empty_top_level_ok' } else { 'top_level_patch_pending' }
        topLevelPatchCount = $patchDropTopLevelCount
        pendingProducerCount = $patchDropPendingProducerCount
        sourceEditLocks = $sourceEditLockCount
        producerEvidence = $producerEvidence
        nextAction = if ($sourceOwnership -eq 'desktop_unblocked') { 'none_for_desktop_only' } else { 'inspect_patchdrop_or_source_lease' }
    }
    desktopControlLoop = [ordered]@{
        exitCode = $desktopLoop.ExitCode
        ok = Get-JsonBool (Get-NestedValue $desktopJson @('ok') $false)
        localReady = Get-JsonBool (Get-NestedValue $desktopJson @('localReady') $false)
        completionReady = Get-JsonBool (Get-NestedValue $desktopJson @('completionReady') $false)
        desktopFinalProof = [string](Get-NestedValue $desktopJson @('desktopFinalProof') 'evidence_needed')
        externalEvidenceComplete = Get-JsonBool (Get-NestedValue $desktopJson @('externalEvidenceComplete') $false)
        producerBundlesRequired = Get-JsonBool (Get-NestedValue $desktopJson @('dispatch','producerBundlesRequired') $false)
        externalEvidenceMode = [string](Get-NestedValue $desktopJson @('dispatch','externalEvidenceMode') 'optional')
        decision = [string](Get-NestedValue $desktopJson @('decision') '')
        evidenceNeededCount = @($desktopEvidenceNeeded).Count
        supportingEvidenceNeededCount = @($desktopSupportingNeeded).Count
        nextActionCount = @($desktopNextActions).Count
        optionalNextActionCount = @($desktopOptionalNextActions).Count
        elapsedMs = $desktopLoop.ElapsedMs
    }
    supabase = [ordered]@{
        exitCode = $supabaseProbe.ExitCode
        networkProbeRequested = $ProbeSupabaseNetwork.IsPresent
        ok = Get-JsonBool (Get-NestedValue $supabaseJson @('ok') $false)
        decision = [string](Get-NestedValue $supabaseJson @('decision') '')
        projectScopeStatus = [string](Get-NestedValue $supabaseJson @('projectScope','status') 'unknown')
        readOnly = Get-JsonBool (Get-NestedValue $supabaseJson @('projectScope','readOnlyMode') $true) $true
        mutationAllowed = Get-JsonBool (Get-NestedValue $supabaseJson @('dbSnapshotPlan','mutationAllowed') $false)
        evidenceNeededCount = @($supabaseEvidenceNeeded).Count
        elapsedMs = $supabaseProbe.ElapsedMs
    }
    browser = $browserProofSummary
    computer = $computerProofSummary
    goalNextStatus = $goalNextStatusSummary
    traceMemoryRuntimeProof = $traceMemoryRuntimeProofSummary
    sourceHealthDebug = $sourceHealthDebugSummary
    attachmentProbe = $attachmentProbeSummary
    sourceProbe = $sourceProbeSummary
    nextActions = @($desktopNextActions)
    optionalNextActionCount = @($desktopOptionalNextActions).Count
    evidence_needed = @($desktopEvidenceNeeded + $supabaseEvidenceNeeded)
    supportingEvidenceNeeded = @($desktopSupportingNeeded)
    artifacts = [ordered]@{
        summary = 'smb-decommission-debug-probe.summary.json'
        events = 'smb-decommission-debug-probe.events.ndjson'
        viewer = 'smb-decommission-debug-probe.viewer.html'
    }
    inputSecretPatternHits = $inputSecretHits
    artifactSecretPatternHits = 0
    rawSecretPatternHits = 0
}

$summaryText = $summary | ConvertTo-Json -Depth 40
$viewerHtml = New-ProbeViewerHtml -Summary $summary
$artifactSecretHits = Count-SecretPatterns -Text ($summaryText + "`n" + $viewerHtml)
$summary.inputSecretPatternHits = $inputSecretHits
$summary.artifactSecretPatternHits = $artifactSecretHits
$summary.rawSecretPatternHits = $artifactSecretHits
$summaryText = $summary | ConvertTo-Json -Depth 40
$viewerHtml = New-ProbeViewerHtml -Summary $summary

$summaryPath = Join-Path $outDir 'smb-decommission-debug-probe.summary.json'
$eventsPath = Join-Path $outDir 'smb-decommission-debug-probe.events.ndjson'
$viewerPath = Join-Path $outDir 'smb-decommission-debug-probe.viewer.html'
Write-Utf8NoBom -Path $summaryPath -Value $summaryText
Write-Utf8NoBom -Path $viewerPath -Value $viewerHtml

$events = @(
    [ordered]@{ generatedAt = $summary.generatedAt; toolName = 'desktop_control_loop.sourceScan'; exitCode = $desktopLoop.ExitCode; ok = $summary.sourceScan.ok; decision = $summary.sourceScan.decision; elapsedMs = 0; evidenceSource = 'nested-reuse'; rawSecretPatternHits = 0 },
    [ordered]@{ generatedAt = $summary.generatedAt; toolName = 'desktop_control_loop'; exitCode = $desktopLoop.ExitCode; ok = $summary.desktopControlLoop.ok; decision = $summary.desktopControlLoop.decision; elapsedMs = $desktopLoop.ElapsedMs; rawSecretPatternHits = 0 },
    [ordered]@{ generatedAt = $summary.generatedAt; toolName = 'supabase_context_probe'; exitCode = $supabaseProbe.ExitCode; ok = $summary.supabase.ok; decision = $summary.supabase.decision; elapsedMs = $supabaseProbe.ElapsedMs; rawSecretPatternHits = 0 },
    [ordered]@{ generatedAt = $summary.generatedAt; toolName = 'janitor_inventory'; exitCode = $janitor.exitCode; ok = ($janitor.exitCode -eq 0); decision = 'janitor_inventory'; elapsedMs = 0; rawSecretPatternHits = 0 },
    [ordered]@{ generatedAt = $summary.generatedAt; toolName = 'goal_next_status'; exitCode = 0; ok = ($summary.goalNextStatus.status -eq 'observed'); decision = $summary.goalNextStatus.statusDecision; elapsedMs = 0; rawSecretPatternHits = $summary.goalNextStatus.rawSecretPatternHits },
    [ordered]@{ generatedAt = $summary.generatedAt; toolName = 'trace_memory_runtime_proof'; exitCode = 0; ok = (($summary.traceMemoryRuntimeProof.status -eq 'verified_runtime') -or ($summary.traceMemoryRuntimeProof.status -eq 'safe_pending_runtime')); decision = $summary.traceMemoryRuntimeProof.status; elapsedMs = 0; rawSecretPatternHits = $summary.traceMemoryRuntimeProof.rawSecretPatternHits },
    [ordered]@{ generatedAt = $summary.generatedAt; toolName = 'source_health_debug'; exitCode = 0; ok = ($summary.sourceHealthDebug.status -eq 'observed'); decision = $summary.sourceHealthDebug.requirementEvidenceNeededDetailMode; elapsedMs = 0; rawSecretPatternHits = $summary.sourceHealthDebug.rawSecretPatternHits },
    [ordered]@{ generatedAt = $summary.generatedAt; toolName = 'attachment_probe_map'; exitCode = 0; ok = ($summary.attachmentProbe.status -ne 'evidence_needed'); decision = $summary.attachmentProbe.status; elapsedMs = 0; rawSecretPatternHits = $summary.attachmentProbe.rawSecretPatternHits; sampleSeedHash12 = $summary.attachmentProbe.sampleSeedHash12 },
    [ordered]@{ generatedAt = $summary.generatedAt; toolName = 'source_probe_map'; exitCode = 0; ok = ($summary.sourceProbe.status -ne 'evidence_needed'); decision = $summary.sourceProbe.status; elapsedMs = 0; rawSecretPatternHits = $summary.sourceProbe.rawSecretPatternHits; testFixtureSecretPatternHits = $summary.sourceProbe.testFixtureSecretPatternHits; sampleSeedHash12 = $summary.sourceProbe.sampleSeedHash12 }
)
$eventText = ($events | ForEach-Object { $_ | ConvertTo-Json -Depth 10 -Compress }) -join "`n"
Write-Utf8NoBom -Path $eventsPath -Value ($eventText + "`n")

Write-Host "[AWX][smb-debug-probe] decision=$($summary.decision) localReady=$($summary.desktopControlLoop.localReady) completionReady=$($summary.desktopControlLoop.completionReady) producerBundlesRequired=$($summary.desktopControlLoop.producerBundlesRequired) supabase=$($summary.supabase.projectScopeStatus) inputSecretHits=$inputSecretHits artifactSecretHits=$artifactSecretHits summary=$summaryPath events=$eventsPath viewer=$viewerPath"
if ($artifactSecretHits -gt 0) {
    Write-Error "[AWX][smb-debug-probe] secret-leak-risk artifactSecretHits=$artifactSecretHits"
    exit 4
}
exit 0
