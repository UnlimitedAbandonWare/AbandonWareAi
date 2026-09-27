[CmdletBinding()]
param(
    [string]$Root = '',

    [string]$OutputDir = '',

    [string]$Topic = 'mcp-control-loop',

    [switch]$Status,

    [switch]$EnsureFresh,

    [switch]$ExternalDispatch,

    [switch]$RequireSupabaseProof,

    [switch]$RefreshWebProbe,

    [switch]$RequireUiProof,

    [switch]$IndependentWorkAvailable,

    [string]$RecoveryEvidencePath = '',

    [switch]$Help
)

$ErrorActionPreference = 'Stop'

if ($Help) {
    @'
[AWX][goal-next] usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -Topic mcp-control-loop
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -Status
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -EnsureFresh
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -ExternalDispatch
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -RequireSupabaseProof
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -RefreshWebProbe
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -RequireUiProof
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto.ps1 -Root <repo-root> -IndependentWorkAvailable -RecoveryEvidencePath <redacted-proof.json>

Purpose:
  Runs the next safe continuation gates in order and fails closed:
    1. [-RequireSupabaseProof] smoke_supabase_readonly_snapshot.ps1
    2. [-RequireSupabaseProof] supabase_apply_collected_evidence.ps1
    3. [-ExternalDispatch] external_apply_collected_evidence.ps1
    4. awx_mcp_toolbox.py desktop_control_loop
    5. [-RefreshWebProbe] awx_mcp_toolbox.py web_probe_refresh
    6. source_health_scorecard.py
    7. awx_mcp_completion_audit.py

Required external evidence before Supabase can close:
  SUPABASE_PROJECT_REF present
  SUPABASE_ACCESS_TOKEN present, or authenticated read-only Supabase MCP/CLI session
  execute_sql rows saved to data\db-gap-report\supabase-query-results.json
  get_advisors rows saved to data\db-gap-report\supabase-advisors.json

Safety:
  readOnly=true where applicable
  mutationAllowed=false where applicable
  child logs are redacted before writing
  do not print token values, Authorization headers, cookies, JDBC URLs, or raw secrets
  missing live DB or producer evidence remains evidence_needed
  Supabase live DB proof is opt-in via -RequireSupabaseProof; default runs keep it as supporting collection evidence
  external producer dispatch files, producer kit export, and required producer bundles are opt-in via -ExternalDispatch
  official-source network refresh is opt-in via -RefreshWebProbe; default runs retain only its safe manual contract
  Browser/Computer proof lanes are opt-in via -RequireUiProof; default runs keep them as supporting collection evidence

Status:
  -Status reads var\codex-smoke\goal-next-auto.latest.json without rerunning child gates.
  It reports stale-latest when the latest pointer is older than its stale threshold.
  -EnsureFresh reuses a fresh latest pointer, or refreshes stale/missing latest evidence by running gates.
  An unchanged blocker skips the full audit without refreshing success timestamps.
  -IndependentWorkAvailable selects independent-work; it does not grant write authority.
  -RecoveryEvidencePath adds redacted recovery evidence to the input fingerprint.
  Changed blocker inputs invalidate a fresh latest pointer; audit clocks alone do not.
'@ | Write-Host
    exit 0
}

$requestMode = [ordered]@{
    requireSupabaseProof = [bool]$RequireSupabaseProof
    externalDispatch = [bool]$ExternalDispatch
    refreshWebProbe = [bool]$RefreshWebProbe
    requireUiProof = [bool]$RequireUiProof
}

$LeaseContractPath = Join-Path $PSScriptRoot '..\__patch_drop__\source_edit_lease_contract.ps1'
if (-not (Test-Path -LiteralPath $LeaseContractPath -PathType Leaf)) {
    throw "source edit lease contract missing: $LeaseContractPath"
}
. $LeaseContractPath

function Resolve-RepoRoot {
    param([string]$Candidate)
    if (-not [string]::IsNullOrWhiteSpace($Candidate)) {
        return (Resolve-Path -LiteralPath $Candidate).Path
    }
    $scriptRoot = $PSScriptRoot
    if ([string]::IsNullOrWhiteSpace($scriptRoot)) {
        $scriptRoot = Split-Path -Parent $PSCommandPath
    }
    return (Resolve-Path (Join-Path $scriptRoot '..')).Path
}

function Resolve-RepoPath {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$PathText
    )
    if ([System.IO.Path]::IsPathRooted($PathText)) {
        return $PathText
    }
    return (Join-Path $ProjectRoot $PathText)
}

function Count-Pattern {
    param(
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$Pattern
    )
    if ([string]::IsNullOrEmpty($Text)) {
        return 0
    }
    return ([regex]::Matches($Text, $Pattern)).Count
}

function Count-SecretPatternHits {
    param([AllowEmptyString()][string]$Text)
    return Count-Pattern -Text $Text -Pattern '(?<![A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|Bearer\s+(?!\$\{)[A-Za-z0-9._~+/-]{16,}=*|(?i)\bjdbc:[A-Za-z0-9_+.-]*://[^\s''"]+'
}

function Count-WindowsAbsPathHits {
    param([AllowEmptyString()][string]$Text)
    return Count-Pattern -Text $Text -Pattern '(?<![A-Za-z])[A-Za-z]:[\\/][^\r\n"'']+'
}

function Get-SafeCountValue {
    param($Value)
    if ($null -eq $Value) {
        return 0
    }
    if ($Value -is [System.Array]) {
        return @($Value).Count
    }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return 0
    }
    $parsed = 0
    if ([int]::TryParse($text, [ref]$parsed)) {
        return [math]::Max(0, $parsed)
    }
    return 0
}

function Get-SafeBoolValue {
    param(
        $Value,
        [bool]$Default = $false
    )
    if ($null -eq $Value) {
        return $Default
    }
    if ($Value -is [bool]) {
        return [bool]$Value
    }
    $text = [string]$Value
    if ([string]::IsNullOrWhiteSpace($text)) {
        return $Default
    }
    $parsed = $false
    if ([bool]::TryParse($text, [ref]$parsed)) {
        return $parsed
    }
    return $Default
}

function Redact-SensitiveText {
    param([AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrEmpty($Text)) {
        return ''
    }
    $redacted = $Text
    $redacted = [regex]::Replace($redacted, 'Bearer\s+[A-Za-z0-9._~+/-]+=*', 'Bearer [REDACTED]')
    $redacted = [regex]::Replace($redacted, 'sk-[A-Za-z0-9_-]{20,}', 'sk-[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'AIza[0-9A-Za-z_-]{20,}', 'AIza[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'gsk_[A-Za-z0-9]{20,}', 'gsk_[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'pcsk_[A-Za-z0-9_-]{20,}', 'pcsk_[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}', 'sb_[REDACTED]')
    $redacted = [regex]::Replace($redacted, 'sbp_[A-Za-z0-9_-]{10,}', 'sbp_[REDACTED]')
    $redacted = [regex]::Replace($redacted, '(?i)\bjdbc:[A-Za-z0-9_+.-]*://[^\s''"]+', 'jdbc:[REDACTED]')
    return $redacted
}

function Write-JsonFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $text = $Value | ConvertTo-Json -Depth 80 -Compress
    Set-Content -LiteralPath $Path -Value $text -Encoding UTF8
}

function Write-TextFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [AllowEmptyString()][string]$Value
    )
    Set-Content -LiteralPath $Path -Value $Value -Encoding UTF8
}

function Append-JsonLineFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Value
    )
    $dir = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($dir)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    $line = $Value | ConvertTo-Json -Depth 80 -Compress
    Add-Content -LiteralPath $Path -Value $line -Encoding UTF8
}

function Read-LastJsonLine {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        $last = Get-Content -LiteralPath $Path -Tail 1 -ErrorAction Stop
        if ([string]::IsNullOrWhiteSpace([string]$last)) {
            return $null
        }
        return ([string]$last | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Read-JsonObjectFromText {
    param([AllowEmptyString()][string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $null
    }
    $lines = $Text -split "\r?\n"
    for ($i = $lines.Count - 1; $i -ge 0; $i--) {
        $candidate = $lines[$i].Trim()
        if (-not $candidate.StartsWith('{')) {
            continue
        }
        try {
            return ($candidate | ConvertFrom-Json)
        } catch {
            continue
        }
    }
    return $null
}

function Read-JsonLineFileTail {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$MaxRows = 25
    )
    if (-not (Test-Path -LiteralPath $Path)) {
        return @()
    }
    try {
        $lines = if ($MaxRows -gt 0) {
            @(Get-Content -LiteralPath $Path -Tail $MaxRows -ErrorAction Stop)
        } else {
            @(Get-Content -LiteralPath $Path -ErrorAction Stop)
        }
    } catch {
        return @()
    }
    $items = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace([string]$line)) {
            continue
        }
        try {
            $items.Add(($line | ConvertFrom-Json)) | Out-Null
        } catch {
            continue
        }
    }
    return @($items)
}

function Read-JsonObjectFromFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        return $null
    }
    try {
        return (Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Add-StringNextActionEntries {
    param(
        [Parameter(Mandatory = $true)]$Entries,
        [Parameter(Mandatory = $true)][string]$Source,
        $Actions
    )
    foreach ($action in @($Actions)) {
        $actionText = [string]$action
        if ([string]::IsNullOrWhiteSpace($actionText)) {
            continue
        }
        $Entries.Add([ordered]@{
            source = $Source
            action = $actionText
            decision = 'evidence_needed'
        }) | Out-Null
    }
}

function Add-ObjectNextActionEntries {
    param(
        [Parameter(Mandatory = $true)]$Entries,
        [Parameter(Mandatory = $true)][string]$Source,
        $Actions
    )
    foreach ($action in @($Actions)) {
        if ($null -eq $action -or $null -eq $action.action) {
            continue
        }
        $entry = [ordered]@{
            source = $Source
            action = [string]$action.action
            decision = if ($null -ne $action.decision) { [string]$action.decision } else { 'evidence_needed' }
        }
        foreach ($name in @('nodeRole', 'targetRole', 'hint', 'scope', 'sourceContract')) {
            if ($null -ne $action.$name -and -not [string]::IsNullOrWhiteSpace([string]$action.$name)) {
                $entry[$name] = [string]$action.$name
            }
        }
        foreach ($name in @('targetService', 'topic', 'applyCollectedEvidenceCommand', 'resultPathRecommendation', 'advisorResultPathRecommendation', 'indexPathRecommendation', 'archiveRootRecommendation', 'importTool', 'mcpEndpointTemplate')) {
            if ($null -ne $action.$name -and -not [string]::IsNullOrWhiteSpace([string]$action.$name)) {
                $entry[$name] = [string]$action.$name
            }
        }
        foreach ($name in @('readOnly', 'mutationAllowed', 'mcpOAuthSupported', 'localPatchJustified')) {
            if ($null -ne $action.$name) {
                $entry[$name] = [bool]$action.$name
            }
        }
        foreach ($name in @('requiredMcpTools', 'requiredSidecars', 'requiredResultNames', 'artifactPaths', 'docsRefs', 'nextActions', 'producerCommandTemplates', 'supportedAuthModes', 'manualAuthSensitiveEnvRefs', 'officialContractSignals', 'evidenceNeeded', 'commands')) {
            if ($null -ne $action.$name) {
                $items = @($action.$name | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
                if ($items.Count -gt 0) {
                    $entry[$name] = $items
                }
            }
        }
        if ($null -ne $action.requiredEnv) {
            $envNames = @($action.requiredEnv | ForEach-Object { [string]$_.name } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            if ($envNames.Count -gt 0) {
                $entry['requiredEnvNames'] = $envNames
            }
        } elseif ($null -ne $action.requiredEnvNames) {
            $envNames = @($action.requiredEnvNames | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            if ($envNames.Count -gt 0) {
                $entry['requiredEnvNames'] = $envNames
            }
        }
        if ($null -ne $action.requiredSourceIsolation) {
            $entry['requiredSourceIsolation'] = [ordered]@{
                guard = [string]$action.requiredSourceIsolation.guard
                sourceRootKind = [string]$action.requiredSourceIsolation.sourceRootKind
                directCanonicalSourceEdit = [bool]$action.requiredSourceIsolation.directCanonicalSourceEdit
                desktopFinalProof = [string]$action.requiredSourceIsolation.desktopFinalProof
                rawSecretPatternHits = if ($null -ne $action.requiredSourceIsolation.rawSecretPatternHits) { [int]$action.requiredSourceIsolation.rawSecretPatternHits } else { 0 }
            }
        }
        if ($null -ne $action.collectionGuards) {
            $collectionGuards = New-SafeSupabaseCollectionGuards -Value $action.collectionGuards
            if ($collectionGuards.Count -gt 0) {
                $entry['collectionGuards'] = $collectionGuards
            }
        }
        $Entries.Add($entry) | Out-Null
    }
}

function Get-CompletionAuditBootstrapDecision {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [int]$MaxAgeSeconds = 86400,
        [int]$FutureToleranceSeconds = 300
    )

    $relativePath = 'var/codex-smoke/awx-mcp-completion-audit-current.json'
    $path = Join-Path $ProjectRoot 'var\codex-smoke\awx-mcp-completion-audit-current.json'
    $result = [ordered]@{
        mode = 'cold'
        reuseAllowed = $false
        reason = 'canonical_missing'
        preflightRequired = $true
        preflightExecuted = $false
        canonicalArtifact = $relativePath
        canonicalPresent = $false
        canonicalParsed = $false
        generatedAtPresent = $false
        freshnessStatus = 'missing'
        ageSeconds = -1
        maxAgeSeconds = $MaxAgeSeconds
        futureToleranceSeconds = $FutureToleranceSeconds
        canonicalSecretHits = 0
        canonicalRawSecretPatternHits = 0
        secretHits = 0
        secretCountersSafe = $true
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return $result
    }

    $result['canonicalPresent'] = $true
    try {
        $raw = Get-Content -Raw -LiteralPath $path -ErrorAction Stop
    } catch {
        $result['reason'] = 'canonical_malformed'
        $result['freshnessStatus'] = 'malformed'
        return $result
    }
    $artifact = Read-JsonObjectFromFile -Path $path
    if ($null -eq $artifact -or $artifact -isnot [System.Management.Automation.PSCustomObject]) {
        $result['reason'] = 'canonical_malformed'
        $result['freshnessStatus'] = 'malformed'
        return $result
    }
    $result['canonicalParsed'] = $true

    $declaredSecretHits = 0
    $secretCountersValid = $true
    foreach ($counterName in @('rawSecretPatternHits', 'secretPatternHits', 'secretHits')) {
        $counterProperty = $artifact.PSObject.Properties[$counterName]
        if ($null -eq $counterProperty) {
            if ($counterName -eq 'rawSecretPatternHits') {
                $secretCountersValid = $false
            }
            continue
        }
        $parsedCounter = 0
        if (
            $counterProperty.Value -is [bool] -or
            -not [int]::TryParse(
                [string]$counterProperty.Value,
                [System.Globalization.NumberStyles]::Integer,
                [System.Globalization.CultureInfo]::InvariantCulture,
                [ref]$parsedCounter) -or
            $parsedCounter -lt 0
        ) {
            $secretCountersValid = $false
            continue
        }
        $declaredSecretHits += $parsedCounter
    }
    $rawSecretPatternHits = Count-SecretPatternHits -Text $raw
    $result['canonicalSecretHits'] = $declaredSecretHits
    $result['canonicalRawSecretPatternHits'] = $rawSecretPatternHits
    $result['secretHits'] = $declaredSecretHits + $rawSecretPatternHits
    $result['secretCountersSafe'] = ($secretCountersValid -and $result.secretHits -eq 0)
    if (-not $secretCountersValid) {
        $result['reason'] = 'canonical_secret_counters_invalid'
        $result['freshnessStatus'] = 'unsafe'
        return $result
    }
    if (-not $result.secretCountersSafe) {
        $result['reason'] = 'canonical_secret_hits'
        $result['freshnessStatus'] = 'unsafe'
        return $result
    }

    $generatedAtText = [string]$artifact.generatedAt
    if ([string]::IsNullOrWhiteSpace($generatedAtText)) {
        $result['reason'] = 'canonical_generated_at_missing'
        $result['freshnessStatus'] = 'missing_generated_at'
        return $result
    }
    $result['generatedAtPresent'] = $true
    try {
        $generatedAt = [DateTimeOffset]::Parse(
            $generatedAtText,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::AssumeUniversal).ToUniversalTime()
    } catch {
        $result['reason'] = 'canonical_generated_at_invalid'
        $result['freshnessStatus'] = 'invalid_generated_at'
        return $result
    }

    $ageSecondsRaw = ([DateTimeOffset]::UtcNow - $generatedAt).TotalSeconds
    $result['ageSeconds'] = [math]::Round($ageSecondsRaw, 3)
    if ($ageSecondsRaw -lt (-1 * $FutureToleranceSeconds)) {
        $result['reason'] = 'canonical_future_generated_at'
        $result['freshnessStatus'] = 'future_generated_at'
        return $result
    }
    if ($ageSecondsRaw -gt $MaxAgeSeconds) {
        $result['reason'] = 'canonical_stale'
        $result['freshnessStatus'] = 'stale'
        return $result
    }

    $result['mode'] = 'warm'
    $result['reuseAllowed'] = $true
    $result['reason'] = 'fresh-safe-canonical-reuse'
    $result['preflightRequired'] = $false
    $result['freshnessStatus'] = 'current'
    return $result
}

function Get-EntryValue {
    param(
        $Entry,
        [Parameter(Mandatory = $true)][string]$Name
    )
    if ($null -eq $Entry) {
        return $null
    }
    if ($Entry -is [System.Collections.IDictionary]) {
        if ($Entry.Contains($Name)) {
            return $Entry[$Name]
        }
        return $null
    }
    try {
        return $Entry.$Name
    } catch {
        return $null
    }
}

function Test-SourceHealthPrimaryNextAction {
    param(
        $Action,
        [bool]$ExternalDispatchRequested = $false,
        [bool]$SupabaseProofRequested = $false
    )

    $actionName = [string](Get-EntryValue -Entry $Action -Name 'action')
    if ([string]::IsNullOrWhiteSpace($actionName)) {
        return $false
    }

    $proofState = [string](Get-EntryValue -Entry $Action -Name 'proofState')
    if ($actionName -eq 'source-runtime-proof-current' -and $proofState -eq 'current') {
        return $false
    }

    if (-not $SupabaseProofRequested) {
        $targetService = [string](Get-EntryValue -Entry $Action -Name 'targetService')
        $evidenceText = @(
            Get-EntryValue -Entry $Action -Name 'evidenceNeeded'
            Get-EntryValue -Entry $Action -Name 'requiredEnvNames'
            Get-EntryValue -Entry $Action -Name 'requiredMcpTools'
        ) -join ','
        if ($actionName -eq 'collect-supabase-live-proof' -or
                $targetService -eq 'supabase' -or
                $evidenceText -match 'SUPABASE|supabase') {
            return $false
        }
    }

    if (-not $ExternalDispatchRequested) {
        if ($actionName -eq 'collect-external-evidence-files') {
            return $false
        }
        $scope = [string](Get-EntryValue -Entry $Action -Name 'scope')
        $sourceContract = [string](Get-EntryValue -Entry $Action -Name 'sourceContract')
        if ($actionName -eq 'no-local-source-action' -and ($scope -match 'external' -or $sourceContract -match 'external_evidence')) {
            return $false
        }
    }

    return $true
}

function New-SafeSupabaseCollectionGuards {
    param($Value)
    $guard = [ordered]@{}
    foreach ($name in @('mutationAllowed', 'storeRawRows', 'requireProjectScope', 'requireAdvisors')) {
        $raw = Get-EntryValue -Entry $Value -Name $name
        if ($null -ne $raw) {
            $guard[$name] = [bool]$raw
        }
    }
    return $guard
}

function Get-SafeCommandText {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $safe = Redact-SensitiveText -Text $Command
    if (-not [string]::IsNullOrWhiteSpace($ProjectRoot)) {
        $safe = [regex]::Replace($safe, [regex]::Escape($ProjectRoot), '<desktop-canonical-root>', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    }
    return $safe
}

function Get-CommandRoleFromTemplate {
    param(
        [AllowEmptyString()][string]$Command,
        [AllowEmptyString()][string]$FallbackRole
    )
    $match = [regex]::Match($Command, '--node-role\s+([A-Za-z0-9_-]+)')
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    if (-not [string]::IsNullOrWhiteSpace($FallbackRole)) {
        return $FallbackRole
    }
    return 'desktop'
}

function Get-CommandLane {
    param(
        [AllowEmptyString()][string]$Source,
        [AllowEmptyString()][string]$TargetService,
        [AllowEmptyString()][string]$Action,
        [bool]$HasExternalContract,
        [bool]$ProducerTemplate
    )
    if ($ProducerTemplate) {
        return 'external_producer'
    }
    if ($TargetService -eq 'supabase' -or $Source -match 'supabase') {
        return 'supabase'
    }
    if ($TargetService -eq 'archive' -or $Action -match 'archive' -or $Source -match 'archive') {
        return 'archive'
    }
    if ($HasExternalContract -or $Action -match 'external' -or $Source -match 'external') {
        return 'external_desktop'
    }
    if ($Source -match 'desktop') {
        return 'desktop'
    }
    return 'other'
}

function New-LocalInteractionSmokeRefreshContract {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $command = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\refresh_local_interaction_smokes.ps1 -Root . -ComputerProbePath <computer-counts-json> -BrowserProbePath <browser-proof-json>'
    return [ordered]@{
        scriptPath = 'scripts/refresh_local_interaction_smokes.ps1'
        command = Get-SafePacketString -Text $command -ProjectRoot $ProjectRoot
        computerProbePath = '<computer-counts-json>'
        browserProbePath = '<browser-proof-json>'
        outputPaths = @(
            'var/codex-smoke/computer-use-smoke.json',
            'var/codex-smoke/browser-ui-smoke.json',
            'var/codex-smoke/local-interaction-smoke-refresh.summary.json'
        )
        storesRawProbePayloads = $false
        storesRawAppNames = $false
        storesWindowTitles = $false
        storesRawUrl = $false
        storesScreenshotPath = $false
        mutationAllowed = $false
        requiredEnvNames = @()
        secretHits = 0
    }
}

function New-SmbDecommissionDebugProbeContract {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $outputDir = 'var/codex-smoke/smb-decommission-control-tower'
    $summaryArtifact = "$outputDir/smb-decommission-debug-probe.summary.json"
    $eventsArtifact = "$outputDir/smb-decommission-debug-probe.events.ndjson"
    $viewerArtifact = "$outputDir/smb-decommission-debug-probe.viewer.html"
    $browserProofArtifact = "$outputDir/browser-viewer-proof.json"
    $computerProofArtifact = "$outputDir/computer-count-proof.json"
    $summaryPath = Resolve-RepoPath -ProjectRoot $ProjectRoot -PathText ($summaryArtifact -replace '/', '\')
    $eventsPath = Resolve-RepoPath -ProjectRoot $ProjectRoot -PathText ($eventsArtifact -replace '/', '\')
    $viewerPath = Resolve-RepoPath -ProjectRoot $ProjectRoot -PathText ($viewerArtifact -replace '/', '\')
    $browserProofPath = Resolve-RepoPath -ProjectRoot $ProjectRoot -PathText ($browserProofArtifact -replace '/', '\')
    $computerProofPath = Resolve-RepoPath -ProjectRoot $ProjectRoot -PathText ($computerProofArtifact -replace '/', '\')
    $scriptPath = Resolve-RepoPath -ProjectRoot $ProjectRoot -PathText 'scripts\smb_decommission_debug_probe.ps1'
    $artifactText = ''
    foreach ($path in @($summaryPath, $eventsPath, $viewerPath, $browserProofPath, $computerProofPath)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            try {
                $artifactText += "`n" + (Get-Content -Raw -LiteralPath $path -ErrorAction Stop)
            } catch {
                $artifactText += "`n"
            }
        }
    }
    $summary = Read-JsonObjectFromFile -Path $summaryPath
    $desktopControlLoop = Get-EntryValue -Entry $summary -Name 'desktopControlLoop'
    $browser = Get-EntryValue -Entry $summary -Name 'browser'
    $computer = Get-EntryValue -Entry $summary -Name 'computer'
    $supabase = Get-EntryValue -Entry $summary -Name 'supabase'
    $present = Test-Path -LiteralPath $summaryPath -PathType Leaf
    $eventsPresent = Test-Path -LiteralPath $eventsPath -PathType Leaf
    $viewerPresent = Test-Path -LiteralPath $viewerPath -PathType Leaf
    $parsed = $null -ne $summary
    $ageMinutes = -1.0
    $stale = $true
    if ($present) {
        try {
            $item = Get-Item -LiteralPath $summaryPath -ErrorAction Stop
            $ageMinutes = [math]::Round(((Get-Date).ToUniversalTime() - $item.LastWriteTimeUtc).TotalMinutes, 3)
            $stale = $ageMinutes -gt (24 * 60)
        } catch {
            $ageMinutes = -1.0
            $stale = $true
        }
    }
    $artifactSecretHits = Count-SecretPatternHits -Text $artifactText
    $windowsAbsPathHits = Count-WindowsAbsPathHits -Text $artifactText
    $summarySecretHits = Get-SafeCountValue -Value (Get-EntryValue -Entry $summary -Name 'rawSecretPatternHits')
    $summaryArtifactSecretHits = Get-SafeCountValue -Value (Get-EntryValue -Entry $summary -Name 'artifactSecretPatternHits')
    $secretHits = $artifactSecretHits + $summarySecretHits + $summaryArtifactSecretHits
    $summaryOk = Get-SafeBoolValue -Value (Get-EntryValue -Entry $summary -Name 'ok') -Default $false
    $schemaVersion = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $summary -Name 'schemaVersion')) -ProjectRoot $ProjectRoot
    $summaryDecision = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $summary -Name 'decision')) -ProjectRoot $ProjectRoot
    $browserStatus = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $browser -Name 'status')) -ProjectRoot $ProjectRoot
    $computerStatus = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $computer -Name 'status')) -ProjectRoot $ProjectRoot
    $supabaseProjectScope = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $supabase -Name 'projectScopeStatus')) -ProjectRoot $ProjectRoot
    $ok = (
        $present -and
        $eventsPresent -and
        $viewerPresent -and
        $parsed -and
        -not $stale -and
        $summaryOk -and
        $schemaVersion -eq 'awx.smb_decommission_debug_probe.v1' -and
        $summaryDecision -eq 'desktop_only_probe' -and
        (Get-SafeBoolValue -Value (Get-EntryValue -Entry $summary -Name 'mutationAllowed') -Default $true) -eq $false -and
        (Get-SafeBoolValue -Value (Get-EntryValue -Entry $summary -Name 'writeDispatch') -Default $true) -eq $false -and
        (Get-SafeBoolValue -Value (Get-EntryValue -Entry $summary -Name 'writeProducerKit') -Default $true) -eq $false -and
        (Get-SafeBoolValue -Value (Get-EntryValue -Entry $desktopControlLoop -Name 'producerBundlesRequired') -Default $true) -eq $false -and
        $secretHits -eq 0 -and
        $windowsAbsPathHits -eq 0
    )
    $attachmentPathPlaceholder = '<attachment-path>'
    $jsonPayload = '{"nodeRole":"desktop","root":".","output_dir":"var/codex-smoke/smb-decommission-control-tower","browserProofPath":"var/codex-smoke/smb-decommission-control-tower/browser-viewer-proof.json","computerProofPath":"var/codex-smoke/smb-decommission-control-tower/computer-count-proof.json"}'
    $jsonPayloadWithAttachment = '{"nodeRole":"desktop","root":".","output_dir":"var/codex-smoke/smb-decommission-control-tower","browserProofPath":"var/codex-smoke/smb-decommission-control-tower/browser-viewer-proof.json","computerProofPath":"var/codex-smoke/smb-decommission-control-tower/computer-count-proof.json","attachmentPath":"<attachment-path>"}'
    $command = Get-SafePacketString -Text ("'$jsonPayload' | python scripts\awx_mcp_toolbox.py --input-json - smb_decommission_debug_probe") -ProjectRoot $ProjectRoot
    $commandWithAttachment = Get-SafePacketString -Text ("'$jsonPayloadWithAttachment' | python scripts\awx_mcp_toolbox.py --input-json - smb_decommission_debug_probe") -ProjectRoot $ProjectRoot
    return [ordered]@{
        decision = if ($ok) { 'ok' } else { 'evidence_needed' }
        tool = 'smb_decommission_debug_probe'
        scriptPath = 'scripts/smb_decommission_debug_probe.ps1'
        scriptPresent = (Test-Path -LiteralPath $scriptPath -PathType Leaf)
        command = $command
        commandWithAttachment = $commandWithAttachment
        attachmentPathPlaceholder = $attachmentPathPlaceholder
        mode = 'desktop-only-debug-probe'
        outputDir = $outputDir
        summaryArtifact = $summaryArtifact
        eventsArtifact = $eventsArtifact
        viewerArtifact = $viewerArtifact
        browserProofArtifact = $browserProofArtifact
        computerProofArtifact = $computerProofArtifact
        present = $present
        parsed = $parsed
        eventsPresent = $eventsPresent
        viewerPresent = $viewerPresent
        ok = $ok
        stale = $stale
        ageMinutes = $ageMinutes
        schemaVersion = $schemaVersion
        summaryDecision = $summaryDecision
        mutationAllowed = $false
        writeDispatch = $false
        writeProducerKit = $false
        requireProducerBundles = $false
        requireSupabaseLiveProof = $false
        supabaseReadOnly = $true
        browserStatus = $browserStatus
        computerStatus = $computerStatus
        supabaseProjectScope = $supabaseProjectScope
        supportingEvidenceOnly = $true
        requiredEnvNames = @()
        secretHits = $secretHits
        windowsAbsPathHits = $windowsAbsPathHits
        nextAction = if ($ok) { '' } else { 'run-smb-decommission-debug-probe' }
    }
}

function Get-TraceMemoryRuntimeProofArtifact {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $outputPath = Join-Path $ProjectRoot 'verification\chat-debug-fx-sse-trace-memory-required\chat-debug-fx-sse.json'
    $summaryPath = Join-Path $ProjectRoot 'verification\chat-debug-fx-sse-trace-memory-required\chat-debug-fx-sse.summary.txt'
    $staleAfterMinutes = 120
    $evidenceNeeded = [System.Collections.Generic.List[string]]::new()
    $present = Test-Path -LiteralPath $outputPath
    $summaryPresent = Test-Path -LiteralPath $summaryPath
    $parsed = $false
    $ageMinutes = -1.0
    $stale = $true
    $rawSecretHits = 0
    $row = $null

    if ($present) {
        try {
            $item = Get-Item -LiteralPath $outputPath -ErrorAction Stop
            $ageMinutes = [math]::Round(((Get-Date).ToUniversalTime() - $item.LastWriteTimeUtc).TotalMinutes, 3)
            $stale = $ageMinutes -gt $staleAfterMinutes
            $rawText = Get-Content -Raw -LiteralPath $outputPath -ErrorAction Stop
            $rawSecretHits = Count-SecretPatternHits -Text $rawText
        } catch {
            $evidenceNeeded.Add('trace_memory_runtime_proof_read_failed') | Out-Null
        }
        $json = Read-JsonObjectFromFile -Path $outputPath
        if ($null -ne $json) {
            $parsed = $true
            $summaryNode = Get-EntryValue -Entry $json -Name 'summary'
            $row = if ($null -ne $summaryNode) { $summaryNode } else { $json }
        }
    }

    if (-not $present) {
        $evidenceNeeded.Add('trace_memory_runtime_proof_missing') | Out-Null
    } elseif (-not $parsed) {
        $evidenceNeeded.Add('trace_memory_runtime_proof_parse_failed') | Out-Null
    }
    if ($present -and $stale) {
        $evidenceNeeded.Add('trace_memory_runtime_proof_stale') | Out-Null
    }

    $ok = $false
    $status = 0
    $debugFxPresent = $false
    $operatorActionPresent = $false
    $traceMemoryRequired = $false
    $traceMemoryPresent = $false
    $traceMemoryRouteDecision = ''
    $traceMemoryCfvmOffered = ''
    $traceMemoryCfvmPatternId = ''
    $traceMemoryVirtualCheckpointKey = ''
    $traceMemoryVirtualCheckpointStage = ''
    $traceMemoryVirtualCheckpointPhase = ''
    $traceMemorySeedStatus = 0
    $traceMemorySeedRouteDecision = ''
    $eventCount = 0
    $eventTypes = @()
    $artifactSecretHits = 0
    $rawPromptHits = 0
    $rawModelHits = 0

    if ($parsed -and $null -ne $row) {
        $ok = Get-SafeBoolValue -Value (Get-EntryValue -Entry $row -Name 'ok')
        $status = Get-SafeCountValue -Value (Get-EntryValue -Entry $row -Name 'status')
        $debugFxPresent = Get-SafeBoolValue -Value (Get-EntryValue -Entry $row -Name 'debugFxPresent')
        $operatorActionPresent = Get-SafeBoolValue -Value (Get-EntryValue -Entry $row -Name 'operatorActionPresent')
        $traceMemoryRequired = Get-SafeBoolValue -Value (Get-EntryValue -Entry $row -Name 'traceMemoryRequired')
        $traceMemoryPresent = Get-SafeBoolValue -Value (Get-EntryValue -Entry $row -Name 'traceMemoryPresent')
        $traceMemoryRouteDecision = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $row -Name 'traceMemoryRouteDecision')) -ProjectRoot $ProjectRoot
        $traceMemoryCfvmOffered = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $row -Name 'traceMemoryCfvmOffered')) -ProjectRoot $ProjectRoot
        $traceMemoryCfvmPatternId = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $row -Name 'traceMemoryCfvmPatternId')) -ProjectRoot $ProjectRoot
        $traceMemoryVirtualCheckpointKey = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $row -Name 'traceMemoryVirtualCheckpointKey')) -ProjectRoot $ProjectRoot
        $traceMemoryVirtualCheckpointStage = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $row -Name 'traceMemoryVirtualCheckpointStage')) -ProjectRoot $ProjectRoot
        $traceMemoryVirtualCheckpointPhase = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $row -Name 'traceMemoryVirtualCheckpointPhase')) -ProjectRoot $ProjectRoot
        $traceMemorySeedStatus = Get-SafeCountValue -Value (Get-EntryValue -Entry $row -Name 'traceMemorySeedStatus')
        $traceMemorySeedRouteDecision = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $row -Name 'traceMemorySeedRouteDecision')) -ProjectRoot $ProjectRoot
        $eventCount = Get-SafeCountValue -Value (Get-EntryValue -Entry $row -Name 'eventCount')
        $eventTypes = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $row -Name 'eventTypes') -ProjectRoot $ProjectRoot
        $artifactSecretHits = Get-SafeCountValue -Value (Get-EntryValue -Entry $row -Name 'secretPatternHits')
        $rawPromptHits = Get-SafeCountValue -Value (Get-EntryValue -Entry $row -Name 'rawPromptHits')
        $rawModelHits = Get-SafeCountValue -Value (Get-EntryValue -Entry $row -Name 'rawModelHits')

        if (-not $ok) {
            $evidenceNeeded.Add('trace_memory_runtime_proof_not_ok') | Out-Null
        }
        if ($status -ne 200) {
            $evidenceNeeded.Add('trace_memory_runtime_proof_status_not_200') | Out-Null
        }
        if (-not $debugFxPresent) {
            $evidenceNeeded.Add('trace_memory_debug_fx_missing') | Out-Null
        }
        if (-not $operatorActionPresent) {
            $evidenceNeeded.Add('trace_memory_operator_action_missing') | Out-Null
        }
        if (-not $traceMemoryRequired -or -not $traceMemoryPresent) {
            $evidenceNeeded.Add('trace_memory_debug_fx_absent') | Out-Null
        }
        if ([string]::IsNullOrWhiteSpace($traceMemoryRouteDecision)) {
            $evidenceNeeded.Add('trace_memory_route_decision_missing') | Out-Null
        }
        if ([string]::IsNullOrWhiteSpace($traceMemoryCfvmOffered) -and [string]::IsNullOrWhiteSpace($traceMemoryCfvmPatternId)) {
            $evidenceNeeded.Add('trace_memory_cfvm_checkpoint_missing') | Out-Null
        }
        if ([string]::IsNullOrWhiteSpace($traceMemoryVirtualCheckpointKey)) {
            $evidenceNeeded.Add('trace_memory_virtual_checkpoint_key_missing') | Out-Null
        }
        if ([string]::IsNullOrWhiteSpace($traceMemoryVirtualCheckpointStage)) {
            $evidenceNeeded.Add('trace_memory_virtual_checkpoint_stage_missing') | Out-Null
        }
        if ([string]::IsNullOrWhiteSpace($traceMemoryVirtualCheckpointPhase)) {
            $evidenceNeeded.Add('trace_memory_virtual_checkpoint_phase_missing') | Out-Null
        }
        if ($traceMemorySeedStatus -ne 200) {
            $evidenceNeeded.Add('trace_memory_seed_status_not_200') | Out-Null
        }
        if ([string]::IsNullOrWhiteSpace($traceMemorySeedRouteDecision)) {
            $evidenceNeeded.Add('trace_memory_seed_route_decision_missing') | Out-Null
        }
    }

    $secretHits = [int]$rawSecretHits + [int]$artifactSecretHits
    if ($secretHits -gt 0) {
        $evidenceNeeded.Add('trace_memory_runtime_proof_secret_hits') | Out-Null
    }
    if ($rawPromptHits -gt 0) {
        $evidenceNeeded.Add('trace_memory_runtime_proof_raw_prompt_hits') | Out-Null
    }
    if ($rawModelHits -gt 0) {
        $evidenceNeeded.Add('trace_memory_runtime_proof_raw_model_hits') | Out-Null
    }

    $decision = if ($evidenceNeeded.Count -eq 0) { 'ok' } else { 'evidence_needed' }
    return [ordered]@{
        present = $present
        parsed = $parsed
        summaryPresent = $summaryPresent
        decision = $decision
        ok = ($decision -eq 'ok')
        status = $status
        stale = $stale
        staleAfterMinutes = $staleAfterMinutes
        ageMinutes = $ageMinutes
        debugFxPresent = $debugFxPresent
        operatorActionPresent = $operatorActionPresent
        traceMemoryRequired = $traceMemoryRequired
        traceMemoryPresent = $traceMemoryPresent
        traceMemoryRouteDecision = $traceMemoryRouteDecision
        traceMemoryCfvmOffered = $traceMemoryCfvmOffered
        traceMemoryCfvmPatternId = $traceMemoryCfvmPatternId
        traceMemoryVirtualCheckpointKey = $traceMemoryVirtualCheckpointKey
        traceMemoryVirtualCheckpointStage = $traceMemoryVirtualCheckpointStage
        traceMemoryVirtualCheckpointPhase = $traceMemoryVirtualCheckpointPhase
        traceMemorySeedStatus = $traceMemorySeedStatus
        traceMemorySeedRouteDecision = $traceMemorySeedRouteDecision
        eventCount = $eventCount
        eventTypes = @($eventTypes)
        secretHits = $secretHits
        rawPromptHits = $rawPromptHits
        rawModelHits = $rawModelHits
        evidenceNeeded = @($evidenceNeeded)
        nextAction = if ($decision -eq 'ok') { '' } else { 'run_trace_memory_debug_fx_sse_smoke' }
    }
}

function New-TraceMemoryRuntimeProofContract {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $command = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smoke_chat_debug_fx_sse.ps1 -Port 18394 -ManagementPort 18395 -StartupTimeoutSeconds 180 -TimeoutSec 90 -RequireTraceMemory -OutputDir verification\chat-debug-fx-sse-trace-memory-required'
    $artifact = Get-TraceMemoryRuntimeProofArtifact -ProjectRoot $ProjectRoot
    return [ordered]@{
        decision = [string]$artifact.decision
        scriptPath = 'scripts/smoke_chat_debug_fx_sse.ps1'
        command = Get-SafePacketString -Text $command -ProjectRoot $ProjectRoot
        outputPath = 'verification/chat-debug-fx-sse-trace-memory-required/chat-debug-fx-sse.json'
        summaryPath = 'verification/chat-debug-fx-sse-trace-memory-required/chat-debug-fx-sse.summary.txt'
        present = [bool]$artifact.present
        parsed = [bool]$artifact.parsed
        summaryPresent = [bool]$artifact.summaryPresent
        ok = [bool]$artifact.ok
        status = [int]$artifact.status
        stale = [bool]$artifact.stale
        staleAfterMinutes = [int]$artifact.staleAfterMinutes
        ageMinutes = [double]$artifact.ageMinutes
        debugFxPresent = [bool]$artifact.debugFxPresent
        operatorActionPresent = [bool]$artifact.operatorActionPresent
        requireTraceMemory = $true
        seedsSelfProbe = $true
        checkpointScenario = 'silent_failure'
        traceMemoryPresent = [bool]$artifact.traceMemoryPresent
        traceMemoryRouteDecision = [string]$artifact.traceMemoryRouteDecision
        traceMemoryCfvmOffered = [string]$artifact.traceMemoryCfvmOffered
        traceMemoryCfvmPatternId = [string]$artifact.traceMemoryCfvmPatternId
        traceMemoryVirtualCheckpointKey = [string]$artifact.traceMemoryVirtualCheckpointKey
        traceMemoryVirtualCheckpointStage = [string]$artifact.traceMemoryVirtualCheckpointStage
        traceMemoryVirtualCheckpointPhase = [string]$artifact.traceMemoryVirtualCheckpointPhase
        traceMemorySeedStatus = [int]$artifact.traceMemorySeedStatus
        traceMemorySeedRouteDecision = [string]$artifact.traceMemorySeedRouteDecision
        eventCount = [int]$artifact.eventCount
        eventTypes = @($artifact.eventTypes)
        expectedDebugFxLabels = @(
            'traceMemoryRouteDecision',
            'traceMemoryCfvmOffered',
            'traceMemoryCfvmPatternId',
            'traceMemoryVirtualCheckpointKey',
            'traceMemoryVirtualCheckpointStage',
            'traceMemoryVirtualCheckpointPhase'
        )
        mutationAllowed = $false
        storesRawPrompt = $false
        storesRawModel = $false
        storesRawSsePayload = $false
        requiredEnvNames = @()
        secretHits = [int]$artifact.secretHits
        rawPromptHits = [int]$artifact.rawPromptHits
        rawModelHits = [int]$artifact.rawModelHits
        evidenceNeeded = @($artifact.evidenceNeeded)
        nextAction = [string]$artifact.nextAction
    }
}

function ConvertTo-GoalPacketRelativePath {
    param(
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $safe = Get-SafePacketString -Text $Text -ProjectRoot $ProjectRoot
    $safe = $safe -replace '\\', '/'
    $safe = $safe -replace '^<desktop-canonical-root>/?', ''
    $safe = $safe -replace '^\./', ''
    return $safe
}

function New-SafeDesktopDispatchSummary {
    param(
        $DesktopControlLoopJson,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$Topic,
        [bool]$DispatchWriteRequested,
        [bool]$ProducerKitWriteRequested,
        [bool]$ProducerKitOk,
        [int]$DispatchArtifactCount,
        [bool]$DispatchIntegrityOk,
        [AllowEmptyString()][string]$DispatchFailReason
    )

    $topicSlug = Get-SafePacketString -Text $Topic -ProjectRoot $ProjectRoot
    $producerCommandFiles = [System.Collections.Generic.List[object]]::new()
    $rawProducerCommands = @()
    if ($null -ne $DesktopControlLoopJson `
            -and $null -ne $DesktopControlLoopJson.dispatch `
            -and $null -ne $DesktopControlLoopJson.dispatch.dispatchArtifactIndex `
            -and $null -ne $DesktopControlLoopJson.dispatch.dispatchArtifactIndex.producerCommands) {
        $rawProducerCommands = @($DesktopControlLoopJson.dispatch.dispatchArtifactIndex.producerCommands)
    }
    if ($rawProducerCommands.Count -eq 0) {
        $rawProducerCommands = @(
            [pscustomobject]@{ nodeRole = 'macmini' },
            [pscustomobject]@{ nodeRole = 'notebook' }
        )
    }

    foreach ($command in $rawProducerCommands) {
        $role = Get-SafePacketString -Text ([string]$command.nodeRole) -ProjectRoot $ProjectRoot
        if ([string]::IsNullOrWhiteSpace($role)) {
            continue
        }
        $desktopCommandFile = ConvertTo-GoalPacketRelativePath -Text ([string]$command.commandFile) -ProjectRoot $ProjectRoot
        if ([string]::IsNullOrWhiteSpace($desktopCommandFile)) {
            $desktopCommandFile = "__patch_drop__/dispatch/$topicSlug-$role.commands.txt"
        }
        $desktopCommandFile = $desktopCommandFile -replace '\\', '/'
        $producerCommandFiles.Add([ordered]@{
            nodeRole = $role
            desktopCommandFile = $desktopCommandFile
            producerVisibleCommandFile = "<producer-visible-patchdrop>/dispatch/$topicSlug-$role.commands.txt"
            fileHashPresent = -not [string]::IsNullOrWhiteSpace([string]$command.fileHash)
            producerKitManifestHashPresent = -not [string]::IsNullOrWhiteSpace([string]$command.producerKitManifestHash)
        }) | Out-Null
    }

    return [ordered]@{
        decision = 'evidence_needed'
        topic = $topicSlug
        writeRequested = $DispatchWriteRequested
        producerKitWriteRequested = $ProducerKitWriteRequested
        producerKitOk = $ProducerKitOk
        dispatchArtifactCount = $DispatchArtifactCount
        dispatchIntegrityOk = $DispatchIntegrityOk
        dispatchFailReason = Get-SafePacketString -Text $DispatchFailReason -ProjectRoot $ProjectRoot
        dispatchDir = '__patch_drop__/dispatch'
        producerKitDir = "__patch_drop__/producer-kit/$topicSlug-producer-kit"
        dispatchSha256Sidecar = "__patch_drop__/dispatch/$topicSlug-dispatch.sha256.txt"
        desktopIntake = "__patch_drop__/dispatch/$topicSlug-desktop-intake.ps1"
        producerCommandFiles = @($producerCommandFiles)
        requiredSourceIsolation = [ordered]@{
            guard = 'PASS'
            sourceRootKind = 'local-worktree'
            directCanonicalSourceEdit = $false
            desktopFinalProof = 'evidence_needed'
            rawSecretPatternHits = 0
        }
        secretHits = 0
    }
}

function New-CommandPacket {
    param(
        [Parameter(Mandatory = $true)]$Entries,
        $SupabaseApplySummary,
        $ComputerUseSummary,
        $BrowserUseSummary,
        $WebProbeRefreshSummary,
        $DesktopDispatchSummary,
        $SourceHealthProducerQueue,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$Topic,
        [Parameter(Mandatory = $true)][string]$Decision,
        [Parameter(Mandatory = $true)][string]$GeneratedAt,
        [bool]$IncludeSupabaseCommand = $false
    )

    $commands = [System.Collections.Generic.List[object]]::new()
    $seen = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($entry in @($Entries)) {
        $source = [string](Get-EntryValue -Entry $entry -Name 'source')
        $action = [string](Get-EntryValue -Entry $entry -Name 'action')
        $entryDecision = [string](Get-EntryValue -Entry $entry -Name 'decision')
        if ([string]::IsNullOrWhiteSpace($entryDecision)) {
            $entryDecision = 'evidence_needed'
        }
        $targetService = [string](Get-EntryValue -Entry $entry -Name 'targetService')
        $hasExternalContract = ($null -ne (Get-EntryValue -Entry $entry -Name 'requiredSourceIsolation')) -or ($null -ne (Get-EntryValue -Entry $entry -Name 'requiredSidecars'))
        $role = [string](Get-EntryValue -Entry $entry -Name 'nodeRole')
        if ([string]::IsNullOrWhiteSpace($role)) {
            $role = [string](Get-EntryValue -Entry $entry -Name 'targetRole')
        }
        $requiredEnvNames = @((Get-EntryValue -Entry $entry -Name 'requiredEnvNames') | ForEach-Object { [string]$_ } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })

        if ($source -eq 'computer_use') {
            $key = "computer_use|$source|$action"
            if ($seen.Add($key)) {
                $commands.Add([ordered]@{
                    lane = 'computer_use'
                    role = 'desktop'
                    source = $source
                    action = $action
                    decision = $entryDecision
                    tool = 'mcp__node_repl.js'
                    command = 'refresh_computer_use_lightweight_smoke'
                    outputPath = 'var/codex-smoke/computer-use-smoke.json'
                    storesRawAppNames = $false
                    requiredEnvNames = @()
                }) | Out-Null
            }
            continue
        }

        if ($source -eq 'browser_use') {
            $key = "browser_use|$source|$action"
            if ($seen.Add($key)) {
                $publicListenerRequired = $action -eq 'open_public_80_443_then_rerun_browser_public_domain_ui_smoke'
                $browserRefreshCommand = if ($publicListenerRequired) {
                    'open_public_80_443_then_refresh_browser_public_domain_ui_smoke'
                } elseif ($action -match 'browser_public_domain_ui') {
                    'refresh_browser_public_domain_ui_smoke'
                } elseif ($action -eq 'open_local_or_public_ui_target_then_rerun_browser_smoke') {
                    'open_local_or_public_ui_target_then_rerun_browser_smoke'
                } else {
                    'refresh_browser_local_ui_smoke'
                }
                $browserCommandEntry = [ordered]@{
                    lane = 'browser_use'
                    role = 'desktop'
                    source = $source
                    action = $action
                    decision = $entryDecision
                    tool = 'browser.control-in-app-browser'
                    command = $browserRefreshCommand
                    outputPath = 'var/codex-smoke/browser-ui-smoke.json'
                    storesRawUrl = $false
                    storesScreenshotPath = $false
                    safePendingProof = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.safePendingProof) { [bool]$BrowserUseSummary.safePendingProof } else { $false }
                    evidenceNeeded = if ($null -ne $BrowserUseSummary) { Get-SafePacketString -Text ([string]$BrowserUseSummary.evidenceNeeded) -ProjectRoot $ProjectRoot } else { '' }
                    requiredEnvNames = @()
                }
                if ($publicListenerRequired) {
                    $browserCommandEntry['externalListenerRequired'] = $true
                    $browserCommandEntry['preflightCommand'] = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\domain_public_https_preflight.ps1 -Domain abandonwareai.kro.kr -TlsMode offload -RequireRunning -RequireTrustedCertificate -Json'
                }
                $commands.Add($browserCommandEntry) | Out-Null
            }
            continue
        }

        $inlineCommands = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $entry -Name 'commands') -ProjectRoot $ProjectRoot
        if ($action -eq 'no-local-source-action' -and $inlineCommands.Count -gt 0) {
            $evidenceNeeded = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $entry -Name 'evidenceNeeded') -ProjectRoot $ProjectRoot
            $scope = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $entry -Name 'scope')) -ProjectRoot $ProjectRoot
            $sourceContract = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $entry -Name 'sourceContract')) -ProjectRoot $ProjectRoot
            foreach ($inlineCommand in @($inlineCommands)) {
                $safeCommand = Get-SafeCommandText -Command ([string]$inlineCommand) -ProjectRoot $ProjectRoot
                if ([string]::IsNullOrWhiteSpace($safeCommand)) {
                    continue
                }
                $key = "no_local|$source|$action|$safeCommand"
                if ($seen.Add($key)) {
                    $commands.Add([ordered]@{
                        lane = 'external_evidence'
                        role = if (-not [string]::IsNullOrWhiteSpace($role)) { $role } else { 'desktop' }
                        source = $source
                        action = $action
                        decision = $entryDecision
                        command = $safeCommand
                        requiredEnvNames = @($requiredEnvNames)
                        evidenceNeeded = @($evidenceNeeded)
                        scope = $scope
                        sourceContract = $sourceContract
                        mutationAllowed = $false
                        localPatchJustified = $false
                    }) | Out-Null
                }
            }
            continue
        }

        $applyCommand = [string](Get-EntryValue -Entry $entry -Name 'applyCollectedEvidenceCommand')
        if (-not [string]::IsNullOrWhiteSpace($applyCommand)) {
            $safeCommand = Get-SafeCommandText -Command $applyCommand -ProjectRoot $ProjectRoot
            $key = "apply|$source|$action|$safeCommand"
            if ($seen.Add($key)) {
                $lane = Get-CommandLane -Source $source -TargetService $targetService -Action $action -HasExternalContract $hasExternalContract -ProducerTemplate $false
                $commandEntry = [ordered]@{
                    lane = $lane
                    role = if (-not [string]::IsNullOrWhiteSpace($role)) { $role } else { 'desktop' }
                    source = $source
                    action = $action
                    decision = $entryDecision
                    command = $safeCommand
                    requiredEnvNames = @($requiredEnvNames)
                }
                if ($lane -eq 'supabase') {
                    $readOnlyValue = Get-EntryValue -Entry $entry -Name 'readOnly'
                    $mutationAllowedValue = Get-EntryValue -Entry $entry -Name 'mutationAllowed'
                    $endpointTemplate = [string](Get-EntryValue -Entry $entry -Name 'mcpEndpointTemplate')
                    $commandEntry['readOnly'] = if ($null -ne $readOnlyValue) { [bool]$readOnlyValue } else { $true }
                    $commandEntry['mutationAllowed'] = if ($null -ne $mutationAllowedValue) { [bool]$mutationAllowedValue } else { $false }
                    if ([string]::IsNullOrWhiteSpace($endpointTemplate)) {
                        $endpointTemplate = 'https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs'
                    }
                    if (-not [string]::IsNullOrWhiteSpace($endpointTemplate)) {
                        $commandEntry['mcpEndpointTemplate'] = Get-SafePacketString -Text $endpointTemplate -ProjectRoot $ProjectRoot
                    }
                    $docsRefs = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $entry -Name 'docsRefs') -ProjectRoot $ProjectRoot
                    if ($docsRefs.Count -eq 0) {
                        $docsRefs = @(
                            'https://supabase.com/docs/guides/ai-tools/mcp',
                            'https://supabase.com/docs/guides/api/securing-your-api',
                            'https://supabase.com/docs/guides/security/product-security'
                        )
                    }
                    if ($docsRefs.Count -gt 0) {
                        $commandEntry['docsRefs'] = @($docsRefs)
                    }
                    $officialContractSignals = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $entry -Name 'officialContractSignals') -ProjectRoot $ProjectRoot
                    if ($officialContractSignals.Count -gt 0) {
                        $commandEntry['officialContractSignals'] = @($officialContractSignals)
                    }
                    $collectionGuards = New-SafeSupabaseCollectionGuards -Value (Get-EntryValue -Entry $entry -Name 'collectionGuards')
                    if ($collectionGuards.Count -gt 0) {
                        $commandEntry['collectionGuards'] = $collectionGuards
                    }
                    $supportedAuthModes = Get-SafeUniqueStrings -Values @(
                        (Get-EntryValue -Entry $entry -Name 'supportedAuthModes')
                        if ($null -ne $SupabaseApplySummary) { $SupabaseApplySummary.supportedAuthModes }
                    ) -ProjectRoot $ProjectRoot
                    if ($supportedAuthModes.Count -gt 0) {
                        $commandEntry['supportedAuthModes'] = @($supportedAuthModes)
                    }
                    $manualAuthSensitiveEnvRefs = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $entry -Name 'manualAuthSensitiveEnvRefs') -ProjectRoot $ProjectRoot
                    if ($manualAuthSensitiveEnvRefs.Count -gt 0) {
                        $commandEntry['manualAuthSensitiveEnvRefs'] = @($manualAuthSensitiveEnvRefs)
                    }
                    $mcpOAuthValue = Get-EntryValue -Entry $entry -Name 'mcpOAuthSupported'
                    if ($null -eq $mcpOAuthValue -and $null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.mcpOAuthSupported) {
                        $mcpOAuthValue = $SupabaseApplySummary.mcpOAuthSupported
                    }
                    if ($null -ne $mcpOAuthValue) {
                        $commandEntry['mcpOAuthSupported'] = [bool]$mcpOAuthValue
                    }
                }
                if ($lane -eq 'archive') {
                    $readOnlyValue = Get-EntryValue -Entry $entry -Name 'readOnly'
                    $mutationAllowedValue = Get-EntryValue -Entry $entry -Name 'mutationAllowed'
                    $commandEntry['readOnly'] = if ($null -ne $readOnlyValue) { [bool]$readOnlyValue } else { $true }
                    $commandEntry['mutationAllowed'] = if ($null -ne $mutationAllowedValue) { [bool]$mutationAllowedValue } else { $false }
                }
                foreach ($field in @('resultPathRecommendation', 'advisorResultPathRecommendation', 'indexPathRecommendation', 'archiveRootRecommendation', 'importTool')) {
                    $fieldValue = [string](Get-EntryValue -Entry $entry -Name $field)
                    if ([string]::IsNullOrWhiteSpace($fieldValue) -and $lane -eq 'supabase' -and $null -ne $SupabaseApplySummary) {
                        $fieldValue = [string]$SupabaseApplySummary.$field
                    }
                    if (-not [string]::IsNullOrWhiteSpace($fieldValue)) {
                        $commandEntry[$field] = Get-SafePacketString -Text $fieldValue -ProjectRoot $ProjectRoot
                    }
                }
                foreach ($field in @('requiredMcpTools', 'artifactPaths')) {
                    $rawFieldValues = @(Get-EntryValue -Entry $entry -Name $field)
                    if ($lane -eq 'supabase' -and $field -eq 'requiredMcpTools' -and $null -ne $SupabaseApplySummary) {
                        $rawFieldValues += @($SupabaseApplySummary.requiredMcpTools)
                    }
                    if ($lane -eq 'supabase' -and $field -eq 'artifactPaths') {
                        $rawFieldValues += @(
                            'data/db-gap-report/supabase-execute-sql-collection.packet.json',
                            'data/db-gap-report/supabase-query-results.template.json',
                            'data/db-gap-report/supabase-readonly-snapshot.sql',
                            'data/db-gap-report/supabase-schema-snapshot.json'
                        )
                    }
                    $fieldValues = Get-SafeUniqueStrings -Values $rawFieldValues -ProjectRoot $ProjectRoot
                    if ($fieldValues.Count -gt 0) {
                        $commandEntry[$field] = @($fieldValues)
                    }
                }
                $commands.Add($commandEntry) | Out-Null
            }
        }

        foreach ($template in @((Get-EntryValue -Entry $entry -Name 'producerCommandTemplates'))) {
            $templateText = [string]$template
            if ([string]::IsNullOrWhiteSpace($templateText)) {
                continue
            }
            $safeTemplate = Get-SafeCommandText -Command $templateText -ProjectRoot $ProjectRoot
            $key = "producer|$source|$action|$safeTemplate"
            if ($seen.Add($key)) {
                $lane = Get-CommandLane -Source $source -TargetService $targetService -Action $action -HasExternalContract $hasExternalContract -ProducerTemplate $true
                $commands.Add([ordered]@{
                    lane = $lane
                    role = Get-CommandRoleFromTemplate -Command $safeTemplate -FallbackRole $role
                    source = $source
                    action = $action
                    decision = $entryDecision
                    command = $safeTemplate
                    requiredEnvNames = @()
                }) | Out-Null
            }
        }
    }

    $peerBusKey = 'peer_evidence_bus|desktop|collect-peer-evidence-bus-lanes'
    if ($seen.Add($peerBusKey)) {
        $commands.Add([ordered]@{
            lane = 'peer_evidence_bus'
            role = 'desktop'
            source = 'peer_evidence_bus'
            action = 'collect-peer-evidence-bus-lanes'
            decision = 'evidence_needed'
            command = 'python scripts/awx_mcp_toolbox.py peer_evidence_bus --input-json ''{"nodeRole":"desktop","root":".","targetMetric":"harmony"}'''
            requiredEnvNames = @()
            targetMetric = 'harmony'
            outputPath = 'var/codex-smoke/peer-evidence-bus.json'
            storesRawPaths = $false
            storesRawSecrets = $false
        }) | Out-Null
    }

    $webProbeKey = 'web_probe_refresh|desktop|refresh-official-source-probe'
    if ($seen.Add($webProbeKey)) {
        $webProbeDecision = if ($null -ne $WebProbeRefreshSummary -and [bool]$WebProbeRefreshSummary.ok) { 'ok' } else { 'evidence_needed' }
        $commands.Add([ordered]@{
            lane = 'web_probe_refresh'
            role = 'desktop'
            source = 'web_probe_refresh'
            action = 'refresh-official-source-probe'
            decision = $webProbeDecision
            command = 'python scripts/awx_mcp_toolbox.py web_probe_refresh --input-json ''{"nodeRole":"desktop","root":".","targetMetric":"harmony","output_path":"var/codex-smoke/web-probe-refresh.json"}'''
            outputPath = 'var/codex-smoke/web-probe-refresh.json'
            targetMetric = 'harmony'
            mode = 'read-only-official-sources'
            rawContentStored = $false
            rawQueryStored = $false
            mutationAllowed = $false
            requiredEnvNames = @()
        }) | Out-Null
    }

    $smbDebugProbe = New-SmbDecommissionDebugProbeContract -ProjectRoot $ProjectRoot
    $smbDebugProbeKey = 'smb_decommission_debug_probe|desktop|run-desktop-only-debug-probe'
    if ($seen.Add($smbDebugProbeKey)) {
        $commands.Add([ordered]@{
            lane = 'smb_decommission_debug_probe'
            role = 'desktop'
            source = 'smb_decommission_debug_probe'
            action = 'run-desktop-only-debug-probe'
            decision = [string]$smbDebugProbe.decision
            tool = [string]$smbDebugProbe.tool
            command = [string]$smbDebugProbe.command
            commandWithAttachment = [string]$smbDebugProbe.commandWithAttachment
            attachmentPathPlaceholder = [string]$smbDebugProbe.attachmentPathPlaceholder
            outputPath = [string]$smbDebugProbe.summaryArtifact
            eventsArtifact = [string]$smbDebugProbe.eventsArtifact
            viewerArtifact = [string]$smbDebugProbe.viewerArtifact
            mode = [string]$smbDebugProbe.mode
            mutationAllowed = $false
            writeDispatch = $false
            writeProducerKit = $false
            requireProducerBundles = $false
            requireSupabaseLiveProof = $false
            supportingEvidenceOnly = $true
            requiredEnvNames = @()
        }) | Out-Null
    }

    $traceMemoryRuntimeProof = New-TraceMemoryRuntimeProofContract -ProjectRoot $ProjectRoot
    $traceMemoryKey = 'trace_memory_runtime_proof|desktop|verify-trace-memory-debug-fx-sse'
    if ($seen.Add($traceMemoryKey)) {
        $commands.Add([ordered]@{
            lane = 'trace_memory_runtime_proof'
            role = 'desktop'
            source = 'trace_memory_runtime_proof'
            action = 'verify-trace-memory-debug-fx-sse'
            decision = [string]$traceMemoryRuntimeProof.decision
            command = [string]$traceMemoryRuntimeProof.command
            outputPath = [string]$traceMemoryRuntimeProof.outputPath
            summaryPath = [string]$traceMemoryRuntimeProof.summaryPath
            requireTraceMemory = [bool]$traceMemoryRuntimeProof.requireTraceMemory
            seedsSelfProbe = [bool]$traceMemoryRuntimeProof.seedsSelfProbe
            checkpointScenario = [string]$traceMemoryRuntimeProof.checkpointScenario
            expectedDebugFxLabels = @($traceMemoryRuntimeProof.expectedDebugFxLabels)
            mutationAllowed = $false
            storesRawPrompt = $false
            storesRawModel = $false
            storesRawSsePayload = $false
            requiredEnvNames = @()
        }) | Out-Null
    }

    if ($null -ne $DesktopDispatchSummary -and $DesktopDispatchSummary.writeRequested -eq $true) {
        foreach ($producerCommand in @($DesktopDispatchSummary.producerCommandFiles)) {
            $role = Get-SafePacketString -Text ([string]$producerCommand.nodeRole) -ProjectRoot $ProjectRoot
            $commandFile = ConvertTo-GoalPacketRelativePath -Text ([string]$producerCommand.desktopCommandFile) -ProjectRoot $ProjectRoot
            if ([string]::IsNullOrWhiteSpace($role) -or [string]::IsNullOrWhiteSpace($commandFile)) {
                continue
            }
            $dispatchKey = "external_dispatch|$role|$commandFile"
            if ($seen.Add($dispatchKey)) {
                $commands.Add([ordered]@{
                    lane = 'external_dispatch'
                    role = $role
                    source = 'desktop_control_loop'
                    action = 'run-producer-command-file'
                    decision = 'evidence_needed'
                    command = "run-producer-command-file $commandFile"
                    commandFile = $commandFile
                    producerVisibleCommandFile = Get-SafePacketString -Text ([string]$producerCommand.producerVisibleCommandFile) -ProjectRoot $ProjectRoot
                    producerKitManifestHashPresent = [bool]$producerCommand.producerKitManifestHashPresent
                    dispatchSha256Sidecar = [string]$DesktopDispatchSummary.dispatchSha256Sidecar
                    requiredEnvNames = @()
                }) | Out-Null
            }
        }
    }

    $hasSupabaseCommand = @($commands | Where-Object { [string]$_.lane -eq 'supabase' }).Count -gt 0
    if ($IncludeSupabaseCommand -and -not $hasSupabaseCommand -and $null -ne $SupabaseApplySummary -and [string]$SupabaseApplySummary.decision -eq 'evidence_needed') {
        $supabaseEnvNames = Get-SafeUniqueStrings -Values $SupabaseApplySummary.requiredEnvNames -ProjectRoot $ProjectRoot
        if ($supabaseEnvNames.Count -eq 0) {
            $supabaseEnvNames = @('SUPABASE_PROJECT_REF')
        }
        $supabaseTools = Get-SafeUniqueStrings -Values $SupabaseApplySummary.requiredMcpTools -ProjectRoot $ProjectRoot
        if ($supabaseTools.Count -eq 0) {
            $supabaseTools = @('execute_sql', 'get_advisors')
        }
        $supabaseAuthModes = Get-SafeUniqueStrings -Values $SupabaseApplySummary.supportedAuthModes -ProjectRoot $ProjectRoot
        if ($supabaseAuthModes.Count -eq 0) {
            $supabaseAuthModes = @('supabase_mcp_oauth_session', 'manual_SUPABASE_ACCESS_TOKEN')
        }
        $supabaseManualAuthRefs = Get-SafeUniqueStrings -Values $SupabaseApplySummary.manualAuthSensitiveEnvRefs -ProjectRoot $ProjectRoot
        if ($supabaseManualAuthRefs.Count -eq 0 -and ($supabaseAuthModes -contains 'manual_SUPABASE_ACCESS_TOKEN')) {
            $supabaseManualAuthRefs = @('SUPABASE_ACCESS_TOKEN')
        }
        $supabaseEndpointTemplate = [string]$SupabaseApplySummary.readOnlyMcpEndpointTemplate
        if ([string]::IsNullOrWhiteSpace($supabaseEndpointTemplate)) {
            $supabaseEndpointTemplate = 'https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs'
        }
        $supabaseDocsRefs = @(
            'https://supabase.com/docs/guides/ai-tools/mcp',
            'https://supabase.com/docs/guides/api/securing-your-api',
            'https://supabase.com/docs/guides/security/product-security'
        )
        $supabaseOfficialContractSignals = @(
            'mcp_project_scoped_read_only',
            'data_api_grants_required',
            'rls_policy_required',
            'secret_keys_backend_only',
            'advisors_required_before_schema_claim'
        )
        $supabaseCollectionGuards = [ordered]@{
            mutationAllowed = $false
            storeRawRows = $false
            requireProjectScope = $true
            requireAdvisors = $true
        }
        if ($seen.Add('supabase|desktop|collect-supabase-live-proof|external-input-gate')) {
            $commands.Add([ordered]@{
                lane = 'supabase'
                role = 'desktop'
                source = 'supabase_apply'
                action = 'collect-supabase-live-proof'
                decision = 'evidence_needed'
                command = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\supabase_apply_collected_evidence.ps1 -Root .'
                requiredEnvNames = @($supabaseEnvNames)
                readOnly = $true
                mutationAllowed = $false
                mcpEndpointTemplate = Get-SafePacketString -Text $supabaseEndpointTemplate -ProjectRoot $ProjectRoot
                docsRefs = @($supabaseDocsRefs)
                officialContractSignals = @($supabaseOfficialContractSignals)
                collectionGuards = $supabaseCollectionGuards
                supportedAuthModes = @($supabaseAuthModes)
                manualAuthSensitiveEnvRefs = @($supabaseManualAuthRefs)
                mcpOAuthSupported = if ($null -ne $SupabaseApplySummary.mcpOAuthSupported) { [bool]$SupabaseApplySummary.mcpOAuthSupported } else { $true }
                requiredMcpTools = @($supabaseTools)
                resultPathRecommendation = if (-not [string]::IsNullOrWhiteSpace([string]$SupabaseApplySummary.resultPathRecommendation)) { Get-SafePacketString -Text ([string]$SupabaseApplySummary.resultPathRecommendation) -ProjectRoot $ProjectRoot } else { 'data/db-gap-report/supabase-query-results.json' }
                advisorResultPathRecommendation = if (-not [string]::IsNullOrWhiteSpace([string]$SupabaseApplySummary.advisorResultPathRecommendation)) { Get-SafePacketString -Text ([string]$SupabaseApplySummary.advisorResultPathRecommendation) -ProjectRoot $ProjectRoot } else { 'data/db-gap-report/supabase-advisors.json' }
                artifactPaths = @(
                    'data/db-gap-report/supabase-execute-sql-collection.packet.json',
                    'data/db-gap-report/supabase-query-results.template.json',
                    'data/db-gap-report/supabase-readonly-snapshot.sql',
                    'data/db-gap-report/supabase-schema-snapshot.json'
                )
            }) | Out-Null
        }
    }

    $lanes = @($commands | ForEach-Object { [string]$_.lane } | Select-Object -Unique)
    $nextActionSources = @($Entries | ForEach-Object {
        Get-SafePacketString -Text ([string](Get-EntryValue -Entry $_ -Name 'source')) -ProjectRoot $ProjectRoot
    } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
    $topActions = @($Entries | Select-Object -First 8 | ForEach-Object {
        [ordered]@{
            source = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $_ -Name 'source')) -ProjectRoot $ProjectRoot
            action = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $_ -Name 'action')) -ProjectRoot $ProjectRoot
            decision = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $_ -Name 'decision')) -ProjectRoot $ProjectRoot
        }
    })
    $firstActionSource = if ($topActions.Count -gt 0) { [string]$topActions[0].source } else { '' }
    $firstActionName = if ($topActions.Count -gt 0) { [string]$topActions[0].action } else { '' }
    $externalInputSources = @('supabase_apply', 'external_apply', 'archive_search')
    $isExternalInput = $externalInputSources -contains $firstActionSource
    $externalInputEvidenceNeeded = @()
    if ($isExternalInput) {
        if ($firstActionSource -eq 'supabase_apply') {
            $externalInputEvidenceNeeded = @('SUPABASE_PROJECT_REF', 'read_only_supabase_mcp_or_cli_auth', 'execute_sql_results', 'get_advisors_results')
        } elseif ($firstActionSource -eq 'external_apply') {
            $externalInputEvidenceNeeded = @('macmini_node_smoke_json', 'notebook_node_smoke_json', 'producer_handoff_json', 'patchdrop_v3_sidecars')
        } else {
            $externalInputEvidenceNeeded = @('archive_index_jsonl')
        }
    }
    $externalInputGate = [ordered]@{
        schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
        status = if ($isExternalInput) { 'external_input_needed' } elseif ($Entries.Count -gt 0) { 'local_or_unknown' } else { 'empty' }
        source = $firstActionSource
        action = $firstActionName
        localPatchJustified = -not $isExternalInput
        mutationAllowed = $false
        evidenceNeeded = @($externalInputEvidenceNeeded)
    }
    $externalInputGateSafetyText = $externalInputGate | ConvertTo-Json -Depth 20 -Compress
    $externalInputGate.secretHits = Count-SecretPatternHits -Text $externalInputGateSafetyText
    $externalInputGate.windowsAbsPathHits = Count-WindowsAbsPathHits -Text $externalInputGateSafetyText
    $localInteractionSmokeRefresh = New-LocalInteractionSmokeRefreshContract -ProjectRoot $ProjectRoot
    $safeComputerUseSummary = [ordered]@{
        present = $false
        parsed = $false
        ok = $false
        decision = 'evidence_needed'
        reachable = $false
        stale = $false
        appCount = 0
        runningCount = 0
        windowCount = 0
        nextAction = ''
        outputPath = 'var/codex-smoke/computer-use-smoke.json'
        storesRawAppNames = $false
        storesWindowTitles = $false
        helperCountOnly = $false
        probeSchemaVersion = ''
        secretHits = 0
    }
    if ($null -ne $ComputerUseSummary) {
        $safeComputerUseSummary.present = [bool]$ComputerUseSummary.present
        $safeComputerUseSummary.parsed = [bool]$ComputerUseSummary.parsed
        $safeComputerUseSummary.ok = [bool]$ComputerUseSummary.ok
        $safeComputerUseSummary.decision = Get-SafePacketString -Text ([string]$ComputerUseSummary.decision) -ProjectRoot $ProjectRoot
        $safeComputerUseSummary.reachable = [bool]$ComputerUseSummary.reachable
        $safeComputerUseSummary.stale = [bool]$ComputerUseSummary.stale
        $safeComputerUseSummary.appCount = [int]$ComputerUseSummary.appCount
        $safeComputerUseSummary.runningCount = [int]$ComputerUseSummary.runningCount
        $safeComputerUseSummary.windowCount = [int]$ComputerUseSummary.windowCount
        $safeComputerUseSummary.nextAction = Get-SafePacketString -Text ([string]$ComputerUseSummary.nextAction) -ProjectRoot $ProjectRoot
        $safeComputerUseSummary.helperCountOnly = if ($null -ne $ComputerUseSummary.helperCountOnly) { [bool]$ComputerUseSummary.helperCountOnly } else { $false }
        $safeComputerUseSummary.probeSchemaVersion = if ($null -ne $ComputerUseSummary.probeSchemaVersion) { Get-SafePacketString -Text ([string]$ComputerUseSummary.probeSchemaVersion) -ProjectRoot $ProjectRoot } else { '' }
        $safeComputerUseSummary.secretHits = [int]$ComputerUseSummary.secretHits
    }
    $safeBrowserUseSummary = [ordered]@{
        present = $false
        parsed = $false
        ok = $false
        decision = 'evidence_needed'
        reachable = $false
        localhost = $false
        publicDomain = $false
        targetAccepted = $false
        targetHost = 'unknown'
        safePendingProof = $false
        stale = $false
        screenshotCaptured = $false
        statusClass = 'unknown'
        targetContentVisible = $false
        browserSurface = 'unknown'
        evidenceNeeded = ''
        nextAction = ''
        outputPath = 'var/codex-smoke/browser-ui-smoke.json'
        storesRawUrl = $false
        storesScreenshotPath = $false
        secretHits = 0
    }
    if ($null -ne $BrowserUseSummary) {
        $safeBrowserUseSummary.present = [bool]$BrowserUseSummary.present
        $safeBrowserUseSummary.parsed = [bool]$BrowserUseSummary.parsed
        $safeBrowserUseSummary.ok = [bool]$BrowserUseSummary.ok
        $safeBrowserUseSummary.decision = Get-SafePacketString -Text ([string]$BrowserUseSummary.decision) -ProjectRoot $ProjectRoot
        $safeBrowserUseSummary.reachable = [bool]$BrowserUseSummary.reachable
        $safeBrowserUseSummary.localhost = [bool]$BrowserUseSummary.localhost
        $safeBrowserUseSummary.publicDomain = if ($null -ne $BrowserUseSummary.publicDomain) { [bool]$BrowserUseSummary.publicDomain } else { $false }
        $safeBrowserUseSummary.targetAccepted = if ($null -ne $BrowserUseSummary.targetAccepted) { [bool]$BrowserUseSummary.targetAccepted } else { ([bool]$BrowserUseSummary.localhost -or ($null -ne $BrowserUseSummary.publicDomain -and [bool]$BrowserUseSummary.publicDomain)) }
        $safeBrowserUseSummary.targetHost = if ($null -ne $BrowserUseSummary.targetHost) { Get-SafePacketString -Text ([string]$BrowserUseSummary.targetHost) -ProjectRoot $ProjectRoot } else { 'unknown' }
        $safeBrowserUseSummary.safePendingProof = if ($null -ne $BrowserUseSummary.safePendingProof) { [bool]$BrowserUseSummary.safePendingProof } else { $false }
        $safeBrowserUseSummary.stale = [bool]$BrowserUseSummary.stale
        $safeBrowserUseSummary.screenshotCaptured = [bool]$BrowserUseSummary.screenshotCaptured
        $safeBrowserUseSummary.statusClass = Get-SafePacketString -Text ([string]$BrowserUseSummary.statusClass) -ProjectRoot $ProjectRoot
        $safeBrowserUseSummary.targetContentVisible = [bool]$BrowserUseSummary.targetContentVisible
        $safeBrowserUseSummary.browserSurface = Get-SafePacketString -Text ([string]$BrowserUseSummary.browserSurface) -ProjectRoot $ProjectRoot
        $safeBrowserUseSummary.evidenceNeeded = Get-SafePacketString -Text ([string]$BrowserUseSummary.evidenceNeeded) -ProjectRoot $ProjectRoot
        $safeBrowserUseSummary.nextAction = Get-SafePacketString -Text ([string]$BrowserUseSummary.nextAction) -ProjectRoot $ProjectRoot
        $safeBrowserUseSummary.secretHits = [int]$BrowserUseSummary.secretHits
    }
    return [ordered]@{
        schemaVersion = 'awx.goal_next_auto.command_packet.v1'
        generatedAt = $GeneratedAt
        decision = $Decision
        topic = $Topic
        root = $ProjectRoot
        secretSafe = $true
        commandCount = $commands.Count
        nextActionCount = @($Entries).Count
        nextActionSources = @($nextActionSources)
        topActions = @($topActions)
        externalInputGate = $externalInputGate
        desktopDispatch = $DesktopDispatchSummary
        lanes = $lanes
        commands = @($commands)
        localInteractionSmokeRefresh = $localInteractionSmokeRefresh
        smbDecommissionDebugProbe = $smbDebugProbe
        traceMemoryRuntimeProof = $traceMemoryRuntimeProof
        computerUse = $safeComputerUseSummary
        browserUse = $safeBrowserUseSummary
        sourceHealthProducerQueue = if ($null -ne $SourceHealthProducerQueue) { $SourceHealthProducerQueue } else { New-SafeSourceHealthProducerQueue -SourceHealthJson $null -ProjectRoot $ProjectRoot }
    }
}

function Get-SafePacketString {
    param(
        [AllowEmptyString()][string]$Text,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $safe = Redact-SensitiveText -Text ([string]$Text)
    if (-not [string]::IsNullOrWhiteSpace($ProjectRoot)) {
        $safe = [regex]::Replace($safe, [regex]::Escape($ProjectRoot), '<desktop-canonical-root>', [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    }
    return $safe
}

function Get-SafeUniqueStrings {
    param(
        $Values,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $items = [System.Collections.Generic.List[string]]::new()
    foreach ($value in @($Values)) {
        $text = Get-SafePacketString -Text ([string]$value) -ProjectRoot $ProjectRoot
        if ([string]::IsNullOrWhiteSpace($text)) {
            continue
        }
        if (-not $items.Contains($text)) {
            $items.Add($text) | Out-Null
        }
    }
    return @($items)
}

function New-SafeSourceHealthProducerQueue {
    param(
        $SourceHealthJson,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $empty = [ordered]@{
        schema = ''
        method = ''
        maxDurationHours = 0
        runtimeProductBehavior = $false
        assignmentCount = 0
        producerRoles = @()
        externalEvidenceOnlyComponentIds = @()
        componentScoreInputs = @()
        assignments = @()
    }
    if ($null -eq $SourceHealthJson -or $null -eq $SourceHealthJson.failurePatternPrediction) {
        return $empty
    }
    $queue = $SourceHealthJson.failurePatternPrediction.producerValidationQueue
    if ($null -eq $queue) {
        return $empty
    }

    $externalOnlyIds = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $queue -Name 'externalEvidenceOnlyComponentIds') -ProjectRoot $ProjectRoot
    $componentInputs = [System.Collections.Generic.List[object]]::new()
    foreach ($input in @((Get-EntryValue -Entry $queue -Name 'componentScoreInputs'))) {
        $componentId = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $input -Name 'componentId')) -ProjectRoot $ProjectRoot
        if ([string]::IsNullOrWhiteSpace($componentId)) {
            continue
        }
        $componentInputs.Add([ordered]@{
            componentId = $componentId
            normalized = if ($null -ne (Get-EntryValue -Entry $input -Name 'normalized')) { [double](Get-EntryValue -Entry $input -Name 'normalized') } else { 0.0 }
            confidence = if ($null -ne (Get-EntryValue -Entry $input -Name 'confidence')) { [double](Get-EntryValue -Entry $input -Name 'confidence') } else { 0.0 }
            weightedPoints = if ($null -ne (Get-EntryValue -Entry $input -Name 'weightedPoints')) { [double](Get-EntryValue -Entry $input -Name 'weightedPoints') } else { 0.0 }
            pressureScore = if ($null -ne (Get-EntryValue -Entry $input -Name 'pressureScore')) { [double](Get-EntryValue -Entry $input -Name 'pressureScore') } else { 0.0 }
        }) | Out-Null
    }

    $assignments = [System.Collections.Generic.List[object]]::new()
    $defaultEvidenceSinks = @('TraceStore', 'DebugEventStore', 'CFVM Failure Pattern')
    foreach ($assignment in @((Get-EntryValue -Entry $queue -Name 'assignments'))) {
        $role = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $assignment -Name 'producerRole')) -ProjectRoot $ProjectRoot
        $refs = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $assignment -Name 'componentScoreRefs') -ProjectRoot $ProjectRoot
        $safeRefs = @($refs | Where-Object { $externalOnlyIds -notcontains $_ })
        $requiredEvidenceSinks = @(Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $assignment -Name 'requiredEvidenceSinks') -ProjectRoot $ProjectRoot)
        foreach ($sink in $defaultEvidenceSinks) {
            if ($requiredEvidenceSinks -notcontains $sink) {
                $requiredEvidenceSinks += $sink
            }
        }
        $assignments.Add([ordered]@{
            queueRank = if ($null -ne (Get-EntryValue -Entry $assignment -Name 'queueRank')) { [int](Get-EntryValue -Entry $assignment -Name 'queueRank') } else { 0 }
            producerRole = $role
            sourceRootKind = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $assignment -Name 'sourceRootKind')) -ProjectRoot $ProjectRoot
            directCanonicalSourceEdit = Get-SafeBoolValue -Value (Get-EntryValue -Entry $assignment -Name 'directCanonicalSourceEdit') -Default $false
            evidenceOnly = Get-SafeBoolValue -Value (Get-EntryValue -Entry $assignment -Name 'evidenceOnly') -Default $true
            sourceRiskId = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $assignment -Name 'sourceRiskId')) -ProjectRoot $ProjectRoot
            failurePatternKind = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $assignment -Name 'failurePatternKind')) -ProjectRoot $ProjectRoot
            patternId = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $assignment -Name 'patternId')) -ProjectRoot $ProjectRoot
            riskScore = if ($null -ne (Get-EntryValue -Entry $assignment -Name 'riskScore')) { [double](Get-EntryValue -Entry $assignment -Name 'riskScore') } else { 0.0 }
            amplifiedSignalScore = if ($null -ne (Get-EntryValue -Entry $assignment -Name 'amplifiedSignalScore')) { [double](Get-EntryValue -Entry $assignment -Name 'amplifiedSignalScore') } else { 0.0 }
            componentScoreRefs = @($safeRefs)
            requiredGates = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $assignment -Name 'requiredGates') -ProjectRoot $ProjectRoot
            requiredEvidenceSinks = @($requiredEvidenceSinks)
            requiredEvidenceArtifacts = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $assignment -Name 'requiredEvidenceArtifacts') -ProjectRoot $ProjectRoot
            requiredTraceStoreKeys = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $assignment -Name 'requiredTraceStoreKeys') -ProjectRoot $ProjectRoot
            amplifierTraceKeys = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $assignment -Name 'amplifierTraceKeys') -ProjectRoot $ProjectRoot
            debugEventNdjsonPath = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $assignment -Name 'debugEventNdjsonPath')) -ProjectRoot $ProjectRoot
            patchDropManifestPath = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $assignment -Name 'patchDropManifestPath')) -ProjectRoot $ProjectRoot
            stopConditions = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $assignment -Name 'stopConditions') -ProjectRoot $ProjectRoot
        }) | Out-Null
    }
    $producerRoles = Get-SafeUniqueStrings -Values @($assignments | ForEach-Object { $_.producerRole }) -ProjectRoot $ProjectRoot

    return [ordered]@{
        schema = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $queue -Name 'schema')) -ProjectRoot $ProjectRoot
        method = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $queue -Name 'method')) -ProjectRoot $ProjectRoot
        maxDurationHours = if ($null -ne (Get-EntryValue -Entry $queue -Name 'maxDurationHours')) { [int](Get-EntryValue -Entry $queue -Name 'maxDurationHours') } else { 0 }
        runtimeProductBehavior = Get-SafeBoolValue -Value (Get-EntryValue -Entry $queue -Name 'runtimeProductBehavior') -Default $false
        assignmentCount = $assignments.Count
        producerRoles = @($producerRoles)
        externalEvidenceOnlyComponentIds = @($externalOnlyIds)
        componentScoreInputs = @($componentInputs.ToArray())
        assignments = @($assignments.ToArray())
    }
}

function New-SourceHealthProducerQueueValidationBlock {
    param(
        [Parameter(Mandatory = $true)][string]$NodeRole,
        [Parameter(Mandatory = $true)][string]$QueueFileName,
        [Parameter(Mandatory = $true)]$Assignment,
        [Parameter(Mandatory = $true)][string]$ExpectedQueueHash,
        [switch]$PowerShell
    )
    $patternId = Redact-SensitiveText -Text ([string]$Assignment.patternId)
    $failurePatternKind = Redact-SensitiveText -Text ([string]$Assignment.failurePatternKind)
    $safeExpectedQueueHash = Redact-SensitiveText -Text $ExpectedQueueHash
    $rawAmplifiedSignalScore = Get-EntryValue -Entry $Assignment -Name 'amplifiedSignalScore'
    $amplifiedSignalScore = if ($null -ne $rawAmplifiedSignalScore) { [double]$rawAmplifiedSignalScore } else { 0.0 }
    $safeAmplifiedSignalScore = [string]::Format([System.Globalization.CultureInfo]::InvariantCulture, '{0:0.####}', $amplifiedSignalScore)
    $amplifierTraceKeys = @(
        Get-EntryValue -Entry $Assignment -Name 'amplifierTraceKeys' |
            ForEach-Object { Redact-SensitiveText -Text ([string]$_) } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Select-Object -Unique
    )
    $requiredAmplifierTraceKeys = if ($amplifierTraceKeys.Count -gt 0) { $amplifierTraceKeys -join ',' } else { 'evidence_needed' }
    $python = 'import json,sys; d=json.load(open(sys.argv[1],encoding="utf-8-sig")); q=d.get("sourceHealthProducerQueue") or {}; role=sys.argv[2]; expectedKind=sys.argv[3]; expectedPatternId=sys.argv[4]; a=[x for x in q.get("assignments",[]) if x.get("producerRole")==role and x.get("failurePatternKind")==expectedKind and x.get("patternId")==expectedPatternId]; fail=[]; fail += [] if q.get("schema")=="producer_validation_queue.v1" else ["schema"]; fail += [] if int(q.get("maxDurationHours") or 0) >= 9 else ["duration"]; fail += [] if a else ["assignment-pattern"]; ass=a[0] if a else {}; sinks=",".join(ass.get("requiredEvidenceSinks") or []); arts=",".join(ass.get("requiredEvidenceArtifacts") or []); keys=",".join(ass.get("requiredTraceStoreKeys") or []); amp=",".join(ass.get("amplifierTraceKeys") or []); rawScore=ass.get("amplifiedSignalScore") or 0; score=float(rawScore) if str(rawScore).replace(".","",1).isdigit() else 0.0; requiredSinks=["TraceStore","DebugEventStore","CFVM Failure Pattern"]; requiredAmp=["hypernova.twpmP","hypernova.cvarPhi","hypernova.riskKAlloc","hypernova.clampApplied","sourceHealth.amplifiedSignalScore"]; fail += [] if all(s in sinks for s in requiredSinks) else ["evidence-sinks"]; fail += [] if "DebugEvent NDJSON" in arts and "PatchDrop manifest" in arts else ["artifacts"]; fail += [] if "sourceHealth.failurePatternKind" in keys and "sourceHealth.patternId" in keys else ["trace"]; fail += [] if all(k in amp for k in requiredAmp) else ["amplifier-trace"]; fail += [] if score > 0 else ["amplified-score"]; fail += [] if ass.get("sourceRootKind")=="local-worktree" else ["source-root-kind"]; fail += [] if ass.get("directCanonicalSourceEdit") is False and ass.get("evidenceOnly") is True else ["isolation"]; fail and print("[AWX][producer][source-health] invalid " + ",".join(fail), file=sys.stderr); sys.exit(1 if fail else 0)'
    $lines = [System.Collections.Generic.List[string]]::new()
    $lines.Add('# [AWX][source-health] producer validation queue') | Out-Null
    $lines.Add("# sourceHealthProducerQueue.schema=producer_validation_queue.v1 nodeRole=$NodeRole") | Out-Null
    $lines.Add("# sourceHealth.failurePatternKind=$failurePatternKind sourceHealth.patternId=$patternId") | Out-Null
    $lines.Add("# sourceHealth.amplifiedSignalScore=$safeAmplifiedSignalScore") | Out-Null
    $lines.Add("# sourceHealthProducerQueue.sha256=$safeExpectedQueueHash") | Out-Null
    $lines.Add('# requiredEvidenceArtifacts=riskLedger,componentScores,TraceStore keys,DebugEvent NDJSON,PatchDrop manifest') | Out-Null
    $lines.Add('# requiredEvidenceSinks=TraceStore,DebugEventStore,CFVM Failure Pattern') | Out-Null
    $lines.Add('# requiredTraceStoreKeys=sourceHealth.failurePatternKind,sourceHealth.patternId') | Out-Null
    $lines.Add('# requiredSourceRootKind=local-worktree') | Out-Null
    $lines.Add("# requiredAmplifierTraceKeys=$requiredAmplifierTraceKeys") | Out-Null
    if ($PowerShell) {
        $lines.Add("`$SourceHealthQueueFile = Join-Path (Split-Path -Parent `$ProducerCommandFile) '$QueueFileName'") | Out-Null
        $lines.Add("if (-not (Test-Path -LiteralPath `$SourceHealthQueueFile -PathType Leaf)) { Write-Error `"[AWX][producer][source-health] evidence_needed: source-health producer queue missing: `$SourceHealthQueueFile`"; exit 1 }") | Out-Null
        $lines.Add("`$ExpectedSourceHealthQueueHash = '$safeExpectedQueueHash'") | Out-Null
        $lines.Add("`$ActualSourceHealthQueueHash = (Get-FileHash -Algorithm SHA256 -LiteralPath `$SourceHealthQueueFile).Hash.ToLowerInvariant()") | Out-Null
        $lines.Add("if (`$ActualSourceHealthQueueHash -ne `$ExpectedSourceHealthQueueHash) { Write-Error `"[AWX][producer][source-health] producer-queue-hash-mismatch`"; exit 1 }") | Out-Null
        $lines.Add("python -c '$python' `$SourceHealthQueueFile '$NodeRole' '$failurePatternKind' '$patternId'") | Out-Null
        $lines.Add("`$SourceHealthQueueExit = `$LASTEXITCODE") | Out-Null
        $lines.Add("if (`$SourceHealthQueueExit -ne 0) { Write-Error `"[AWX][producer][source-health] producer-queue-invalid`"; exit `$SourceHealthQueueExit }") | Out-Null
    } else {
        $lines.Add('SourceHealthQueueFile="$(dirname "$ProducerCommandFile")/' + $QueueFileName + '"') | Out-Null
        $lines.Add("[ -f `"`$SourceHealthQueueFile`" ] || { echo `"[AWX][producer][source-health] evidence_needed: source-health producer queue missing: `$SourceHealthQueueFile`" >&2; exit 1; }") | Out-Null
        $lines.Add("ExpectedSourceHealthQueueHash='$safeExpectedQueueHash'") | Out-Null
        $lines.Add('ActualSourceHealthQueueHash="$(python3 -c ''import hashlib,sys; print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())'' "$SourceHealthQueueFile")"') | Out-Null
        $lines.Add('[ "$ActualSourceHealthQueueHash" = "$ExpectedSourceHealthQueueHash" ] || { echo "[AWX][producer][source-health] producer-queue-hash-mismatch" >&2; exit 1; }') | Out-Null
        $lines.Add("python3 -c '$python' `"`$SourceHealthQueueFile`" '$NodeRole' '$failurePatternKind' '$patternId'") | Out-Null
    }
    return ($lines -join "`n")
}

function Add-SourceHealthProducerBlockToCommandText {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Block
    )
    $newline = if ($Text -match "`r`n") { "`r`n" } else { "`n" }
    $lines = [System.Collections.Generic.List[string]]::new()
    $skippingSourceHealthBlock = $false
    foreach ($line in ($Text -split '\r?\n', -1)) {
        if ($line -match '\[AWX\]\[source-health\] producer validation queue') {
            $skippingSourceHealthBlock = $true
            continue
        }
        if ($skippingSourceHealthBlock) {
            if ($line -match '^\s*python3?\s+[''"].*awx_mcp_producer_handoff\.py[''"].*--source-root') {
                $skippingSourceHealthBlock = $false
            } else {
                continue
            }
        }
        $lines.Add($line) | Out-Null
    }
    $insertAt = $lines.Count
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*python3?\s+[''"].*awx_mcp_producer_handoff\.py[''"].*--source-root') {
            $insertAt = $i
            break
        }
    }
    $out = [System.Collections.Generic.List[string]]::new()
    for ($i = 0; $i -lt $insertAt; $i++) {
        $out.Add($lines[$i]) | Out-Null
    }
    foreach ($blockLine in ($Block -split '\r?\n', -1)) {
        $out.Add($blockLine) | Out-Null
    }
    for ($i = $insertAt; $i -lt $lines.Count; $i++) {
        $out.Add($lines[$i]) | Out-Null
    }
    return ($out -join $newline)
}

function Get-FirstCommandArgumentValue {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $pattern = '(?m)(?:^|\s)' + [regex]::Escape($Name) + '\s+(?:''([^'']*)''|"([^"]*)"|([^\s]+))'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        return ''
    }
    foreach ($index in 1..3) {
        if ($match.Groups[$index].Success -and -not [string]::IsNullOrWhiteSpace($match.Groups[$index].Value)) {
            return [string]$match.Groups[$index].Value
        }
    }
    return ''
}

function Get-ProducerVisibleCanonicalRootFromCommandText {
    param(
        [Parameter(Mandatory = $true)][string]$Text
    )
    $patchdropRoot = Get-FirstCommandArgumentValue -Text $Text -Name '--patchdrop-root'
    if ([string]::IsNullOrWhiteSpace($patchdropRoot)) {
        return ''
    }
    $usesBackslash = $patchdropRoot.Contains('\')
    $normalized = $patchdropRoot.Trim() -replace '\\', '/'
    $normalized = $normalized.TrimEnd('/')
    if ($normalized -notmatch '/(?:__patch_drop__|PatchDrop)$') {
        return ''
    }
    $parent = $normalized -replace '/(?:__patch_drop__|PatchDrop)$', ''
    if ([string]::IsNullOrWhiteSpace($parent)) {
        return ''
    }
    if ($usesBackslash) {
        $parent = $parent -replace '/', '\'
        if ($parent -match '^[A-Za-z]:$') {
            $parent = $parent + '\'
        }
    }
    return $parent
}

function Update-DispatchSha256Sidecar {
    param(
        [Parameter(Mandatory = $true)][string]$SidecarPath,
        [Parameter(Mandatory = $true)][string[]]$FilePaths
    )
    $entries = [ordered]@{}
    foreach ($filePath in @($FilePaths)) {
        if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
            continue
        }
        $entries[(Split-Path -Leaf $filePath)] = (Get-FileHash -Algorithm SHA256 -LiteralPath $filePath).Hash.ToLowerInvariant()
    }
    $lines = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $entries.GetEnumerator()) {
        $lines.Add("$($entry.Value)  $($entry.Key)") | Out-Null
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $SidecarPath) | Out-Null
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($SidecarPath, (($lines -join "`n") + "`n"), $utf8NoBom)
}

function Update-HandoffCommandHashes {
    param(
        [Parameter(Mandatory = $true)][string]$HandoffPath,
        [Parameter(Mandatory = $true)]$CommandHashByName
    )
    if (-not (Test-Path -LiteralPath $HandoffPath -PathType Leaf)) {
        return $false
    }
    $raw = Get-Content -Raw -LiteralPath $HandoffPath
    $newline = if ($raw -match "`r`n") { "`r`n" } else { "`n" }
    $lines = [System.Collections.Generic.List[string]]::new()
    $currentCommandName = ''
    $insideGeneratedHashBlock = $false
    $changed = $false
    foreach ($line in ($raw -split '\r?\n', -1)) {
        if ($line -eq '<!-- awx:producer-command-hashes:start -->') {
            $insideGeneratedHashBlock = $true
            $changed = $true
            continue
        }
        if ($insideGeneratedHashBlock) {
            if ($line -eq '<!-- awx:producer-command-hashes:end -->') {
                $insideGeneratedHashBlock = $false
            }
            continue
        }
        $outLine = $line
        $commandMatch = [regex]::Match($line, '^- [A-Za-z0-9_-]+:\s+`([^`]+\.commands\.txt)`')
        if ($commandMatch.Success) {
            $currentCommandName = Split-Path -Leaf ([string]$commandMatch.Groups[1].Value)
        }
        if (-not [string]::IsNullOrWhiteSpace($currentCommandName) -and $line -match '^\s+- command SHA256:\s+`[A-Fa-f0-9]{64}`') {
            if ($CommandHashByName.ContainsKey($currentCommandName)) {
                $hashValue = [string]$CommandHashByName[$currentCommandName]
                $replacement = "  - command SHA256: ``$hashValue``"
                if ($outLine -ne $replacement) {
                    $outLine = $replacement
                    $changed = $true
                }
            }
        }
        $lines.Add($outLine) | Out-Null
    }
    $postUpdateText = $lines -join $newline
    $missingGeneratedHash = $false
    foreach ($commandName in @($CommandHashByName.Keys | Sort-Object)) {
        $hashValue = [string]$CommandHashByName[$commandName]
        if (-not $postUpdateText.Contains($hashValue)) {
            $missingGeneratedHash = $true
            break
        }
    }
    if ($missingGeneratedHash) {
        if ($lines.Count -gt 0 -and -not [string]::IsNullOrWhiteSpace($lines[$lines.Count - 1])) {
            $lines.Add('') | Out-Null
        }
        $lines.Add('<!-- awx:producer-command-hashes:start -->') | Out-Null
        $lines.Add('## Producer Command Hashes') | Out-Null
        foreach ($commandName in @($CommandHashByName.Keys | Sort-Object)) {
            $hashValue = [string]$CommandHashByName[$commandName]
            $lines.Add("- ${commandName}: ``$hashValue``") | Out-Null
        }
        $lines.Add('<!-- awx:producer-command-hashes:end -->') | Out-Null
        $changed = $true
    }
    if ($changed) {
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($HandoffPath, ($lines -join $newline), $utf8NoBom)
    }
    return $changed
}

function Write-SourceHealthProducerDispatchOverlay {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$Topic,
        $DesktopDispatchSummary,
        $SourceHealthProducerQueue
    )
    $topicSlug = Get-SafePacketString -Text $Topic -ProjectRoot $ProjectRoot
    $summary = [ordered]@{
        schemaVersion = 'awx.goal_next_auto.source_health_dispatch_overlay.v1'
        ok = $false
        sidecarPath = "__patch_drop__/dispatch/$topicSlug-source-health-producer-queue.json"
        sidecarHash = ''
        commandFileCount = 0
        updatedCommandFileCount = 0
        shaSidecarUpdated = $false
        skippedReason = ''
    }
    if ($null -eq $SourceHealthProducerQueue -or [int]$SourceHealthProducerQueue.assignmentCount -le 0) {
        $summary.skippedReason = 'producer-queue-empty'
        return $summary
    }
    if ($null -eq $DesktopDispatchSummary -or $DesktopDispatchSummary.writeRequested -ne $true) {
        $summary.skippedReason = 'dispatch-not-written'
        return $summary
    }
    $dispatchDir = Join-Path $ProjectRoot '__patch_drop__\dispatch'
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    $queueFileName = "$topicSlug-source-health-producer-queue.json"
    $queuePath = Join-Path $dispatchDir $queueFileName
    Write-JsonFile -Path $queuePath -Value ([ordered]@{
        schemaVersion = 'awx.goal_next_auto.source_health_producer_queue.v1'
        topic = $topicSlug
        desktopFinalProof = 'evidence_needed'
        directCanonicalSourceEditAllowed = $false
        sourceHealthProducerQueue = $SourceHealthProducerQueue
    })
    $queueHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $queuePath).Hash.ToLowerInvariant()
    $summary.sidecarHash = $queueHash

    $updatedFiles = [System.Collections.Generic.List[string]]::new()
    $commandFilePaths = [System.Collections.Generic.List[string]]::new()
    foreach ($command in @($DesktopDispatchSummary.producerCommandFiles)) {
        $role = Get-SafePacketString -Text ([string]$command.nodeRole) -ProjectRoot $ProjectRoot
        $relativeCommandFile = [string]$command.desktopCommandFile
        if ([string]::IsNullOrWhiteSpace($role) -or [string]::IsNullOrWhiteSpace($relativeCommandFile)) {
            continue
        }
        $commandPath = Join-Path $ProjectRoot ($relativeCommandFile -replace '/', '\')
        if (-not (Test-Path -LiteralPath $commandPath -PathType Leaf)) {
            continue
        }
        $commandFilePaths.Add($commandPath) | Out-Null
        $assignment = @($SourceHealthProducerQueue.assignments | Where-Object { [string]$_.producerRole -eq $role } | Select-Object -First 1)
        if ($assignment.Count -eq 0) {
            continue
        }
        $text = Get-Content -Raw -LiteralPath $commandPath
        $producerVisibleCanonicalRoot = Get-ProducerVisibleCanonicalRootFromCommandText -Text $text
        if (-not [string]::IsNullOrWhiteSpace($producerVisibleCanonicalRoot)) {
            $text = $text.Replace('<desktop-canonical-root>', $producerVisibleCanonicalRoot)
        }
        $isPowerShell = ($text -match '\$ErrorActionPreference') -or ($commandPath -match '\.ps1$') -or ($role -eq 'notebook')
        $block = New-SourceHealthProducerQueueValidationBlock -NodeRole $role -QueueFileName $queueFileName -Assignment $assignment[0] -ExpectedQueueHash $queueHash -PowerShell:$isPowerShell
        $updatedText = Add-SourceHealthProducerBlockToCommandText -Text $text -Block $block
        if ($updatedText -ne $text) {
            $utf8NoBom = New-Object System.Text.UTF8Encoding $false
            [System.IO.File]::WriteAllText($commandPath, $updatedText, $utf8NoBom)
            $updatedFiles.Add($commandPath) | Out-Null
        }
    }
    $dispatchJsonPath = Join-Path $dispatchDir "$topicSlug-desktop-dispatch.json"
    $commandHashByName = @{}
    foreach ($commandPath in @($commandFilePaths.ToArray())) {
        if (Test-Path -LiteralPath $commandPath -PathType Leaf) {
            $commandHashByName[(Split-Path -Leaf $commandPath)] = (Get-FileHash -Algorithm SHA256 -LiteralPath $commandPath).Hash.ToLowerInvariant()
        }
    }
    if (Test-Path -LiteralPath $dispatchJsonPath -PathType Leaf) {
        $dispatchJson = Read-JsonObjectFromFile -Path $dispatchJsonPath
        if ($null -ne $dispatchJson) {
            if ($null -ne $dispatchJson.dispatchArtifactIndex -and $null -ne $dispatchJson.dispatchArtifactIndex.producerCommands) {
                foreach ($producerCommand in @($dispatchJson.dispatchArtifactIndex.producerCommands)) {
                    if ($null -eq $producerCommand -or $null -eq $producerCommand.PSObject -or $null -eq $producerCommand.PSObject.Properties['commandFile']) {
                        continue
                    }
                    $commandName = Split-Path -Leaf ([string]$producerCommand.commandFile)
                    if ($commandHashByName.ContainsKey($commandName)) {
                        if ($null -ne $producerCommand.PSObject.Properties['fileHash']) {
                            $producerCommand.fileHash = [string]$commandHashByName[$commandName]
                        } else {
                            Add-Member -InputObject $producerCommand -NotePropertyName 'fileHash' -NotePropertyValue ([string]$commandHashByName[$commandName])
                        }
                    }
                }
            }
            if ($null -ne $dispatchJson.nextActions) {
                foreach ($action in @($dispatchJson.nextActions)) {
                    if ($null -eq $action -or $null -eq $action.PSObject -or $null -eq $action.PSObject.Properties['commandFile']) {
                        continue
                    }
                    $commandName = Split-Path -Leaf ([string]$action.commandFile)
                    if ($commandHashByName.ContainsKey($commandName)) {
                        if ($null -ne $action.PSObject.Properties['fileHash']) {
                            $action.fileHash = [string]$commandHashByName[$commandName]
                        } else {
                            Add-Member -InputObject $action -NotePropertyName 'fileHash' -NotePropertyValue ([string]$commandHashByName[$commandName])
                        }
                    }
                }
            }
            if ($null -ne $dispatchJson.PSObject.Properties['sourceHealthProducerQueueSidecar']) {
                $dispatchJson.sourceHealthProducerQueueSidecar = $queuePath
            } else {
                Add-Member -InputObject $dispatchJson -NotePropertyName 'sourceHealthProducerQueueSidecar' -NotePropertyValue $queuePath
            }
            $queueHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $queuePath).Hash.ToLowerInvariant()
            if ($null -ne $dispatchJson.PSObject.Properties['sourceHealthProducerQueueSidecarHash']) {
                $dispatchJson.sourceHealthProducerQueueSidecarHash = $queueHash
            } else {
                Add-Member -InputObject $dispatchJson -NotePropertyName 'sourceHealthProducerQueueSidecarHash' -NotePropertyValue $queueHash
            }
            if ($null -ne $dispatchJson.dispatchArtifactIndex) {
                if ($null -ne $dispatchJson.dispatchArtifactIndex.PSObject.Properties['sourceHealthProducerQueue']) {
                    $dispatchJson.dispatchArtifactIndex.sourceHealthProducerQueue = $queuePath
                } else {
                    Add-Member -InputObject $dispatchJson.dispatchArtifactIndex -NotePropertyName 'sourceHealthProducerQueue' -NotePropertyValue $queuePath
                }
                $covered = @($dispatchJson.dispatchArtifactIndex.sha256CoveredArtifacts)
                $coveredNames = @($covered | ForEach-Object { Split-Path -Leaf ([string]$_) })
                if ($coveredNames -notcontains $queueFileName) {
                    if ($covered.Count -gt 0) {
                        $covered = @($covered[0]) + @($queuePath) + @($covered | Select-Object -Skip 1)
                    } else {
                        $covered = @($queuePath)
                    }
                    $dispatchJson.dispatchArtifactIndex.sha256CoveredArtifacts = @($covered)
                }
            }
            Write-JsonFile -Path $dispatchJsonPath -Value $dispatchJson
        }
    }
    $handoffPath = Join-Path $dispatchDir "$topicSlug-handoff.md"
    [void](Update-HandoffCommandHashes -HandoffPath $handoffPath -CommandHashByName $commandHashByName)
    $sidecarPath = Join-Path $dispatchDir "$topicSlug-dispatch.sha256.txt"
    $hashFiles = [System.Collections.Generic.List[string]]::new()
    foreach ($name in @(
        "$topicSlug-desktop-dispatch.json",
        "$topicSlug-source-health-producer-queue.json",
        "$topicSlug-macmini.commands.txt",
        "$topicSlug-notebook.commands.txt",
        "$topicSlug-desktop-intake.ps1",
        "$topicSlug-handoff.md"
    )) {
        $path = Join-Path $dispatchDir $name
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            $hashFiles.Add($path) | Out-Null
        }
    }
    Update-DispatchSha256Sidecar -SidecarPath $sidecarPath -FilePaths $hashFiles.ToArray()
    $summary.ok = $true
    $summary.commandFileCount = @($DesktopDispatchSummary.producerCommandFiles).Count
    $summary.updatedCommandFileCount = $updatedFiles.Count
    $summary.shaSidecarUpdated = Test-Path -LiteralPath $sidecarPath
    return $summary
}

function New-SafeExternalInputGateStatus {
    param(
        $Gate,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    $schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
    $status = 'evidence_needed'
    $source = ''
    $action = ''
    $repeated = $false
    $repeatCount = 0
    $localPatchJustified = $false
    $mutationAllowed = $false
    $evidenceNeeded = @()
    $gateSecretHits = 0
    $gateWindowsAbsPathHits = 0

    if ($null -ne $Gate) {
        if (-not [string]::IsNullOrWhiteSpace([string]$Gate.schemaVersion)) {
            $schemaVersion = Get-SafePacketString -Text ([string]$Gate.schemaVersion) -ProjectRoot $ProjectRoot
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$Gate.status)) {
            $status = Get-SafePacketString -Text ([string]$Gate.status) -ProjectRoot $ProjectRoot
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$Gate.source)) {
            $source = Get-SafePacketString -Text ([string]$Gate.source) -ProjectRoot $ProjectRoot
        }
        if (-not [string]::IsNullOrWhiteSpace([string]$Gate.action)) {
            $action = Get-SafePacketString -Text ([string]$Gate.action) -ProjectRoot $ProjectRoot
        }
        $repeated = Get-SafeBoolValue -Value $Gate.repeated -Default $false
        $repeatCount = Get-SafeCountValue -Value $Gate.repeatCount
        $localPatchJustified = Get-SafeBoolValue -Value $Gate.localPatchJustified -Default $false
        $mutationAllowed = Get-SafeBoolValue -Value $Gate.mutationAllowed -Default $false
        if ($null -ne $Gate.evidenceNeeded) {
            $evidenceNeeded = Get-SafeUniqueStrings -Values $Gate.evidenceNeeded -ProjectRoot $ProjectRoot
        }
        $gateSecretHits = Get-SafeCountValue -Value $Gate.secretHits
        $gateWindowsAbsPathHits = Get-SafeCountValue -Value $Gate.windowsAbsPathHits
    }

    $safeGate = [ordered]@{
        schemaVersion = $schemaVersion
        status = $status
        source = $source
        action = $action
        repeated = $repeated
        repeatCount = $repeatCount
        localPatchJustified = $localPatchJustified
        mutationAllowed = $mutationAllowed
        evidenceNeeded = @($evidenceNeeded)
        secretHits = 0
        windowsAbsPathHits = 0
    }
    $safeGateSafetyText = $safeGate | ConvertTo-Json -Depth 20 -Compress
    $safeGate.secretHits = [math]::Max($gateSecretHits, (Count-SecretPatternHits -Text $safeGateSafetyText))
    $safeGate.windowsAbsPathHits = [math]::Max($gateWindowsAbsPathHits, (Count-WindowsAbsPathHits -Text $safeGateSafetyText))
    return $safeGate
}

function New-SupabaseExternalInputGate {
    param(
        $SupabaseApplySummary,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )
    if ($null -eq $SupabaseApplySummary) {
        return $null
    }
    if ([string]$SupabaseApplySummary.decision -eq 'ok') {
        return $null
    }
    $requiredEnvNames = Get-SafeUniqueStrings -Values $SupabaseApplySummary.requiredEnvNames -ProjectRoot $ProjectRoot
    $nextActions = Get-SafeUniqueStrings -Values $SupabaseApplySummary.nextActions -ProjectRoot $ProjectRoot
    $needsProjectRef = (
        $requiredEnvNames -contains 'SUPABASE_PROJECT_REF' -or
        $nextActions -contains 'set_SUPABASE_PROJECT_REF' -or
        [string]$SupabaseApplySummary.envPreflightStatus -match 'project_ref'
    )
    if (-not $needsProjectRef) {
        return $null
    }
    $safeGate = [ordered]@{
        schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
        status = 'external_input_needed'
        source = 'supabase_apply'
        action = 'set_SUPABASE_PROJECT_REF'
        repeated = $false
        repeatCount = 1
        localPatchJustified = $false
        mutationAllowed = $false
        evidenceNeeded = @('SUPABASE_PROJECT_REF', 'read_only_supabase_mcp_or_cli_auth', 'execute_sql_results', 'get_advisors_results')
        secretHits = 0
        windowsAbsPathHits = 0
    }
    $safeGateSafetyText = $safeGate | ConvertTo-Json -Depth 20 -Compress
    $summarySecretHits = Get-SafeCountValue -Value $SupabaseApplySummary.secretHits
    $safeGate.secretHits = [math]::Max($summarySecretHits, (Count-SecretPatternHits -Text $safeGateSafetyText))
    $safeGate.windowsAbsPathHits = Count-WindowsAbsPathHits -Text $safeGateSafetyText
    return $safeGate
}

function New-PreviousBreadcrumbSummary {
    param($Previous)
    if ($null -eq $Previous) {
        return [ordered]@{
            present = $false
            generatedAt = ''
            decision = ''
            failureClassification = ''
            firstAction = ''
            firstActionSource = ''
            toolStepCount = 0
            secretHits = 0
        }
    }
    return [ordered]@{
        present = $true
        generatedAt = [string]$Previous.generatedAt
        decision = [string]$Previous.decision
        failureClassification = [string]$Previous.failureClassification
        firstAction = [string]$Previous.firstAction
        firstActionSource = [string]$Previous.firstActionSource
        toolStepCount = if ($null -ne $Previous.toolSteps) { @($Previous.toolSteps).Count } else { 0 }
        secretHits = if ($null -ne $Previous.secretHits) { [int]$Previous.secretHits } else { 0 }
    }
}

function New-BreadcrumbFusionSummary {
    param(
        $Rows,
        [Parameter(Mandatory = $true)][string]$GeneratedAtText,
        [int]$TailLimit = 25
    )
    $rowItems = @($Rows)
    $firstActionCounts = @{}
    $failureCounts = @{}
    $bottleneckCounts = @{}
    foreach ($row in $rowItems) {
        $firstAction = [string]$row.firstAction
        if (-not [string]::IsNullOrWhiteSpace($firstAction)) {
            if (-not $firstActionCounts.ContainsKey($firstAction)) { $firstActionCounts[$firstAction] = 0 }
            $firstActionCounts[$firstAction] = [int]$firstActionCounts[$firstAction] + 1
        }
        $failure = [string]$row.failureClassification
        if (-not [string]::IsNullOrWhiteSpace($failure)) {
            if (-not $failureCounts.ContainsKey($failure)) { $failureCounts[$failure] = 0 }
            $failureCounts[$failure] = [int]$failureCounts[$failure] + 1
        }
        $bottleneck = if ($null -ne $row.rawTile) { [string]$row.rawTile.bottleneck } else { [string]$row.firstActionSource }
        if (-not [string]::IsNullOrWhiteSpace($bottleneck)) {
            if (-not $bottleneckCounts.ContainsKey($bottleneck)) { $bottleneckCounts[$bottleneck] = 0 }
            $bottleneckCounts[$bottleneck] = [int]$bottleneckCounts[$bottleneck] + 1
        }
    }

    $countRows = {
        param($Map, [string]$NameKey)
        $out = foreach ($key in $Map.Keys) {
            $item = [ordered]@{}
            $item[$NameKey] = [string]$key
            $item['count'] = [int]$Map[$key]
            [pscustomobject]$item
        }
        return @($out | Sort-Object -Property @{ Expression = 'count'; Descending = $true }, @{ Expression = $NameKey; Ascending = $true } | Select-Object -First 8)
    }

    $latest = if ($rowItems.Count -gt 0) { $rowItems[$rowItems.Count - 1] } else { $null }
    $latestRawTile = [ordered]@{}
    if ($null -ne $latest -and $null -ne $latest.rawTile) {
        $latestRawTile = [ordered]@{
            schemaVersion = [string]$latest.rawTile.schemaVersion
            tileKind = [string]$latest.rawTile.tileKind
            status = [string]$latest.rawTile.status
            blocker = [string]$latest.rawTile.blocker
            bottleneck = [string]$latest.rawTile.bottleneck
            nextAction = [string]$latest.rawTile.nextAction
            reuseSource = [string]$latest.rawTile.reuseSource
            previousFirstAction = [string]$latest.rawTile.previousFirstAction
        }
    }

    $repeatedFirstActions = & $countRows $firstActionCounts 'action'
    $topAction = if (@($repeatedFirstActions).Count -gt 0) { $repeatedFirstActions[0] } else { $null }
    $reusablePattern = if ($null -ne $topAction -and [int]$topAction.count -gt 1) {
        'repeat:firstAction'
    } elseif ($rowItems.Count -gt 0) {
        'single:firstAction'
    } else {
        'empty'
    }
    $latestBottleneck = if ($latestRawTile.Contains('bottleneck')) { [string]$latestRawTile.bottleneck } elseif ($null -ne $latest) { [string]$latest.firstActionSource } else { '' }
    $latestAction = if ($latestRawTile.Contains('nextAction')) { [string]$latestRawTile.nextAction } elseif ($null -ne $latest) { [string]$latest.firstAction } else { '' }
    $externalInputSources = @('supabase_apply', 'external_apply', 'archive_search')
    $isExternalInput = ($externalInputSources -contains $latestBottleneck)
    $externalInputGate = [ordered]@{
        schemaVersion = 'awx.goal_next_auto.external_input_gate.v1'
        status = if ($isExternalInput) { 'external_input_needed' } elseif ($rowItems.Count -gt 0) { 'local_or_unknown' } else { 'empty' }
        source = $latestBottleneck
        action = $latestAction
        repeated = ($null -ne $topAction -and [string]$topAction.action -eq $latestAction -and [int]$topAction.count -gt 1)
        repeatCount = if ($null -ne $topAction -and [string]$topAction.action -eq $latestAction) { [int]$topAction.count } elseif (-not [string]::IsNullOrWhiteSpace($latestAction)) { 1 } else { 0 }
        localPatchJustified = -not $isExternalInput
        mutationAllowed = $false
        evidenceNeeded = if ($isExternalInput) {
            if ($latestBottleneck -eq 'supabase_apply') {
                @('SUPABASE_PROJECT_REF', 'read_only_supabase_mcp_or_cli_auth', 'execute_sql_results', 'get_advisors_results')
            } elseif ($latestBottleneck -eq 'external_apply') {
                @('macmini_node_smoke_json', 'notebook_node_smoke_json', 'producer_handoff_json', 'patchdrop_v3_sidecars')
            } else {
                @('archive_index_jsonl')
            }
        } else {
            @()
        }
    }
    $externalInputGateSafetyText = $externalInputGate | ConvertTo-Json -Depth 20 -Compress
    $externalInputGate.secretHits = Count-SecretPatternHits -Text $externalInputGateSafetyText
    $externalInputGate.windowsAbsPathHits = Count-WindowsAbsPathHits -Text $externalInputGateSafetyText

    return [ordered]@{
        schemaVersion = 'awx.goal_next_auto.breadcrumb_fusion.v1'
        generatedAt = $GeneratedAtText
        root = '<desktop-canonical-root>'
        tailLimit = $TailLimit
        timelineRowsRead = $rowItems.Count
        latestGeneratedAt = if ($null -ne $latest) { [string]$latest.generatedAt } else { '' }
        latestDecision = if ($null -ne $latest) { [string]$latest.decision } else { '' }
        latestFirstAction = if ($null -ne $latest) { [string]$latest.firstAction } else { '' }
        latestRawTile = $latestRawTile
        latestTraceKeys = if ($null -ne $latest -and $null -ne $latest.trace) { @($latest.trace.keys) } else { @() }
        latestMdc = if ($null -ne $latest -and $null -ne $latest.mdc) {
            [ordered]@{
                nodeRole = [string]$latest.mdc.nodeRole
                topic = [string]$latest.mdc.topic
                root = [string]$latest.mdc.root
            }
        } else {
            [ordered]@{}
        }
        reusablePattern = $reusablePattern
        externalInputGate = $externalInputGate
        repeatedFirstActions = @($repeatedFirstActions)
        repeatedFailureClassifications = @((& $countRows $failureCounts 'failureClassification'))
        repeatedBottlenecks = @((& $countRows $bottleneckCounts 'bottleneck'))
    }
}

function Get-SupabaseMcpConfigSummary {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $configPath = Join-Path $ProjectRoot '.mcp.json'
    if (-not (Test-Path -LiteralPath $configPath)) {
        return [ordered]@{
            present = $false
            parsed = $false
            readOnly = $false
            projectRefSource = 'missing'
            tokenStored = $false
            serverHost = ''
            features = @()
        }
    }

    $raw = ''
    try {
        $raw = Get-Content -Raw -LiteralPath $configPath
        $config = $raw | ConvertFrom-Json
    } catch {
        return [ordered]@{
            present = $true
            parsed = $false
            readOnly = $false
            projectRefSource = 'unknown'
            tokenStored = $false
            serverHost = ''
            features = @()
        }
    }

    $urlText = ''
    try {
        if ($null -ne $config.mcpServers -and $null -ne $config.mcpServers.supabase) {
            $urlText = [string]$config.mcpServers.supabase.url
        }
    } catch {
        $urlText = ''
    }

    $serverHost = ''
    $hostMatch = [regex]::Match($urlText, '^(?:https?://)?([^/?#]+)')
    if ($hostMatch.Success) {
        $serverHost = ($hostMatch.Groups[1].Value.ToLowerInvariant() -replace '[^a-z0-9.-]', '')
    }

    $query = ''
    $queryIndex = $urlText.IndexOf('?')
    if ($queryIndex -ge 0 -and $queryIndex -lt ($urlText.Length - 1)) {
        $query = $urlText.Substring($queryIndex + 1)
    }
    $queryMap = @{}
    foreach ($pair in @($query -split '&')) {
        if ([string]::IsNullOrWhiteSpace($pair)) {
            continue
        }
        $parts = $pair -split '=', 2
        $name = $parts[0].Trim().ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($name)) {
            continue
        }
        $value = if ($parts.Count -gt 1) { $parts[1].Trim() } else { '' }
        try {
            $value = [System.Uri]::UnescapeDataString($value)
        } catch {
            $value = ''
        }
        $queryMap[$name] = $value
    }

    $projectRefSource = 'missing'
    if ($queryMap.ContainsKey('project_ref')) {
        $projectRefValue = [string]$queryMap['project_ref']
        if ($projectRefValue -eq '${SUPABASE_PROJECT_REF}') {
            $projectRefSource = 'SUPABASE_PROJECT_REF'
        } elseif ($projectRefValue -match '^\$\{[A-Za-z_][A-Za-z0-9_]*\}$') {
            $projectRefSource = 'env'
        } elseif (-not [string]::IsNullOrWhiteSpace($projectRefValue)) {
            $projectRefSource = 'literal_redacted'
        }
    }

    $features = @()
    if ($queryMap.ContainsKey('features')) {
        $featureItems = [System.Collections.Generic.List[string]]::new()
        foreach ($rawFeature in @([string]$queryMap['features'] -split ',')) {
            $feature = $rawFeature.Trim()
            if ($feature -match '^[A-Za-z0-9_-]+$') {
                $featureItems.Add($feature) | Out-Null
            }
        }
        $features = @($featureItems)
    }

    $secretPattern = 'Bearer\s+(?!\$\{)[A-Za-z0-9._~+/-]{16,}=*|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}|sk-[A-Za-z0-9_-]{20,}|(?i)(?:access_token|api_key|apikey|token)=((?!\$\{)[^&\s"'']{16,})'
    $authLiteral = [regex]::IsMatch($raw, '(?i)"Authorization"\s*:\s*"Bearer\s+(?!\$\{)[^"]{16,}"')
    $tokenStored = ((Count-Pattern -Text $raw -Pattern $secretPattern) -gt 0) -or $authLiteral

    return [ordered]@{
        present = $true
        parsed = $true
        readOnly = ($queryMap.ContainsKey('read_only') -and ([string]$queryMap['read_only']).ToLowerInvariant() -eq 'true')
        projectRefSource = $projectRefSource
        tokenStored = [bool]$tokenStored
        serverHost = $serverHost
        features = $features
    }
}

function New-DefaultSupabaseSmokeSummary {
    param(
        [AllowEmptyString()][string]$SummaryPath = '',
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [int]$SecretHits = 0
    )

    $safeSummaryPath = ''
    if (-not [string]::IsNullOrWhiteSpace($SummaryPath)) {
        $safeSummaryPath = Get-SafePacketString -Text $SummaryPath -ProjectRoot $ProjectRoot
    }

    return [ordered]@{
        parsed = $false
        summaryPath = $safeSummaryPath
        processExecuted = $false
        ok = $false
        decision = 'evidence_needed'
        projectScopeStatus = 'evidence_needed'
        mcpReachable = $false
        mcpProbeSkipped = $true
        mcpDecision = 'evidence_needed'
        mcpEndpointReachabilityEvidence = 'evidence_needed'
        evidenceNeededCount = 0
        secretHits = $SecretHits
        rawSecretPatternHits = $SecretHits
    }
}

function New-OptionalSupabaseSmokeContract {
    return [ordered]@{
        schemaVersion = 'awx.supabase.readonly_snapshot_smoke.summary.v1'
        generatedAt = (Get-Date).ToUniversalTime().ToString('o')
        processExecuted = $false
        evidenceSource = 'static_optional_contract'
        ok = $false
        decision = 'evidence_needed'
        projectScopeStatus = 'project_scope_probe_not_requested'
        mcpReachable = $false
        mcpProbeSkipped = $true
        mcpDecision = 'mcp_endpoint_probe_skipped'
        mcpEndpointReachabilityEvidence = 'probe_skipped'
        readOnly = $true
        mutationAllowed = $false
        docsRefCount = 6
        securityContractCount = 7
        cliQueryMinVersion = '>=2.79.0'
        cliAdvisorsMinVersion = '>=2.81.3'
        cliQueryFallbackTool = 'execute_sql'
        cliAdvisorsFallbackTool = 'get_advisors'
        dataApiGrantProofRequired = $true
        rlsPolicyProofRequired = $true
        apiKeysDocs = $true
        secretKeysBackendOnly = $true
        envPresentCount = 0
        projectRefEnvPresent = $false
        accessTokenEnvPresent = $false
        cliPresent = $false
        contextEvidenceNeededCount = 1
        evidenceNeededCount = 1
        evidenceNeeded = @('project_scoped_read_only_proof_not_requested')
        secretHits = 0
        rawSecretPatternHits = 0
        highConfidenceSecretHits = 0
        bearerPatternHits = 0
        rawJdbcUrlHits = 0
        windowsAbsPathHits = 0
        mutationAllowedTextHits = 0
    }
}

function Get-SupabaseReadOnlyRequiredResultNames {
    return @(
        'schemas_and_tables',
        'rls_and_table_flags',
        'policies',
        'data_api_role_grants',
        'exposed_tables_without_rls',
        'rls_user_metadata_policies',
        'update_policies_without_select_policy',
        'storage_upsert_policy_gaps',
        'views',
        'views_missing_security_invoker',
        'exposed_security_definer_functions',
        'shadow_memory_candidate_tables',
        'shadow_memory_candidate_columns',
        'shadow_memory_metadata_fingerprints',
        'extensions'
    )
}

function New-OptionalSupabaseApplyContract {
    $projectRefEnvPresent = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('SUPABASE_PROJECT_REF'))
    $accessTokenEnvPresent = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('SUPABASE_ACCESS_TOKEN'))
    return [ordered]@{
        schemaVersion = 'awx.supabase.apply_collected_evidence.summary.v1'
        orchestratedByGoalNext = $false
        processExecuted = $false
        evidenceSource = 'static_optional_contract'
        ok = $false
        decision = 'evidence_needed'
        readOnly = $true
        mutationAllowed = $false
        evidenceNeeded = @('project_scoped_read_only_proof_not_requested')
        requiredEnvNames = @('SUPABASE_PROJECT_REF')
        requiredMcpTools = @('execute_sql', 'get_advisors')
        requiredResultNames = @(Get-SupabaseReadOnlyRequiredResultNames)
        nextActions = @()
        resultPathRecommendation = 'data/db-gap-report/supabase-query-results.json'
        advisorResultPathRecommendation = 'data/db-gap-report/supabase-advisors.json'
        resultTemplatePathRecommendation = 'data/db-gap-report/supabase-query-results.template.json'
        collectionPacketPathRecommendation = 'data/db-gap-report/supabase-execute-sql-collection.packet.json'
        readOnlyMcpEndpointTemplate = 'https://mcp.supabase.com/mcp?project_ref=${SUPABASE_PROJECT_REF}&read_only=true&features=database,debugging,docs'
        projectRefEnvPresent = $projectRefEnvPresent
        accessTokenEnvPresent = $accessTokenEnvPresent
        accessTokenManualFallbackEnvPresent = $accessTokenEnvPresent
        mcpOAuthSupported = $true
        supportedAuthModes = @('supabase_mcp_oauth_session', 'manual_SUPABASE_ACCESS_TOKEN')
        envPreflightStatus = 'proof_not_requested'
        applyCollectedEvidenceCommand = 'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\supabase_apply_collected_evidence.ps1 -Root .'
        secretHits = 0
        rawSecretPatternHits = 0
    }
}

function New-OptionalExternalApplyContract {
    param([Parameter(Mandatory = $true)][string]$Topic)

    $supportingActions = @(
        'run_macmini_external_node_smoke',
        'collect_macmini_producer_handoff_json',
        'submit_macmini_patchdrop_v3_bundle_sidecars',
        'run_notebook_external_node_smoke',
        'collect_notebook_producer_handoff_json',
        'submit_notebook_patchdrop_v3_bundle_sidecars'
    )
    $supportingEvidence = @(
        'intake:external node smoke missing role=macmini',
        'audit:external node smoke missing role=notebook'
    )
    return [ordered]@{
        schemaVersion = 'awx.external.apply_collected_evidence.summary.v1'
        orchestratedByGoalNext = $false
        processExecuted = $false
        evidenceSource = 'static_optional_contract'
        ok = $true
        decision = 'supporting_evidence_missing'
        topic = $Topic
        producerBundlesRequired = $false
        externalEvidenceMode = 'optional'
        requiredRoles = @('macmini', 'notebook')
        requiredProducerEvidenceFiles = @('macmini-node-smoke.json', 'macmini-producer-handoff.json', 'notebook-node-smoke.json', 'notebook-producer-handoff.json')
        requiredPatchDropSidecars = @('pendingNotice', '.manifest.json', '.patch', '.report.md', '.verify.log', '.sha256.txt')
        requiredSourceIsolation = [ordered]@{
            guard = 'PASS'
            sourceRootKind = 'local-worktree'
            directCanonicalSourceEdit = $false
            desktopFinalProof = 'evidence_needed'
            rawSecretPatternHits = 0
        }
        nextActions = @()
        supportingEvidenceNextActions = @($supportingActions)
        applyCollectedEvidenceCommand = "powershell -NoProfile -ExecutionPolicy Bypass -File scripts\external_apply_collected_evidence.ps1 -Root . -Topic $Topic -NoRequireProducerBundles"
        evidenceNeeded = @()
        supportingEvidenceNeeded = @($supportingEvidence)
        copiedEvidenceCount = 0
        copiedHandoffCount = 0
        secretHits = 0
        rawSecretPatternHits = 0
    }
}

function New-OptionalWebProbeRefreshContract {
    return [ordered]@{
        schemaVersion = 'awx.web_probe.refresh.optional_contract.v1'
        evidenceSource = 'static_optional_contract'
        executionMode = 'manual-opt-in'
        refreshRequested = $false
        processExecuted = $false
        contractReady = $true
        proofReady = $false
        supportingEvidenceOnly = $true
        present = $false
        parsed = $false
        ok = $false
        decision = 'supporting_evidence_missing'
        targetMetric = 'harmony'
        targetCount = 5
        sourceCount = 0
        fetchedCount = 0
        failedCount = 0
        skippedCount = 0
        missingSignalCount = 0
        rawContentStored = $false
        rawQueryStored = $false
        mutationAllowed = $false
        generatedAt = ''
        fresh = $false
        staleAfterMinutes = 1440
        ageMinutes = $null
        path = 'var/codex-smoke/web-probe-refresh.json'
        outputPath = 'var/codex-smoke/web-probe-refresh.json'
        artifactHash = ''
        nextAction = 'run-web-probe-refresh'
        secretHits = 0
        rawSecretPatternHits = 0
    }
}

function Get-SupabaseSmokeSummary {
    param(
        [Parameter(Mandatory = $true)][string]$SummaryPath,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )

    $safeSummaryPath = Get-SafePacketString -Text $SummaryPath -ProjectRoot $ProjectRoot
    $rawSecretHits = 0
    if (Test-Path -LiteralPath $SummaryPath) {
        try {
            $rawText = Get-Content -Raw -LiteralPath $SummaryPath
            $rawSecretHits = Count-SecretPatternHits -Text $rawText
        } catch {
            $rawSecretHits = 0
        }
    }

    $raw = Read-JsonObjectFromFile -Path $SummaryPath
    if ($null -eq $raw) {
        return New-DefaultSupabaseSmokeSummary -SummaryPath $SummaryPath -ProjectRoot $ProjectRoot -SecretHits $rawSecretHits
    }

    $decision = if ([string]::IsNullOrWhiteSpace([string]$raw.decision)) { 'evidence_needed' } else { [string]$raw.decision }
    $projectScopeStatus = if ([string]::IsNullOrWhiteSpace([string]$raw.projectScopeStatus)) { 'evidence_needed' } else { [string]$raw.projectScopeStatus }
    $mcpDecision = if ([string]::IsNullOrWhiteSpace([string]$raw.mcpDecision)) { 'evidence_needed' } else { [string]$raw.mcpDecision }
    $mcpEndpointReachabilityEvidence = if (-not [string]::IsNullOrWhiteSpace([string]$raw.mcpEndpointReachabilityEvidence)) {
        [string]$raw.mcpEndpointReachabilityEvidence
    } elseif ($null -ne $raw.mcpReachable -and [bool]$raw.mcpReachable) {
        'reachable'
    } elseif ($null -ne $raw.mcpProbeSkipped -and [bool]$raw.mcpProbeSkipped) {
        'probe_skipped'
    } else {
        'evidence_needed'
    }

    $summarySecretHits = $rawSecretHits
    if ($null -ne $raw.secretHits) {
        $summarySecretHits += [int]$raw.secretHits
    }
    $summaryRawSecretHits = $rawSecretHits
    if ($null -ne $raw.rawSecretPatternHits) {
        $summaryRawSecretHits += [int]$raw.rawSecretPatternHits
    }

    return [ordered]@{
        parsed = $true
        summaryPath = $safeSummaryPath
        processExecuted = if ($null -ne $raw.processExecuted) { [bool]$raw.processExecuted } else { $true }
        ok = if ($null -ne $raw.ok) { [bool]$raw.ok } else { $false }
        decision = $decision
        projectScopeStatus = $projectScopeStatus
        mcpReachable = if ($null -ne $raw.mcpReachable) { [bool]$raw.mcpReachable } else { $false }
        mcpProbeSkipped = if ($null -ne $raw.mcpProbeSkipped) { [bool]$raw.mcpProbeSkipped } else { $true }
        mcpDecision = $mcpDecision
        mcpEndpointReachabilityEvidence = $mcpEndpointReachabilityEvidence
        evidenceNeededCount = if ($null -ne $raw.evidenceNeeded) { @($raw.evidenceNeeded).Count } else { 0 }
        secretHits = $summarySecretHits
        rawSecretPatternHits = $summaryRawSecretHits
    }
}

function Get-ComputerUseSmokeSummary {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $smokePath = Join-Path $ProjectRoot 'var\codex-smoke\computer-use-smoke.json'
    $safePath = Get-SafePacketString -Text $smokePath -ProjectRoot $ProjectRoot
    $staleAfterMinutes = 60
    if (-not (Test-Path -LiteralPath $smokePath)) {
        return [ordered]@{
            present = $false
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            reachable = $false
            appCount = 0
            runningCount = 0
            windowCount = 0
            generatedAt = ''
            ageMinutes = $null
            staleAfterMinutes = $staleAfterMinutes
            stale = $true
            path = $safePath
            nextAction = 'run_computer_use_lightweight_smoke'
            helperCountOnly = $false
            safePendingProof = $false
            probeSchemaVersion = ''
            secretHits = 0
            rawSecretPatternHits = 0
        }
    }

    $rawText = Get-Content -Raw -LiteralPath $smokePath
    $rawSecretHits = Count-SecretPatternHits -Text $rawText
    try {
        $smoke = $rawText | ConvertFrom-Json
    } catch {
        return [ordered]@{
            present = $true
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            reachable = $false
            appCount = 0
            runningCount = 0
            windowCount = 0
            generatedAt = ''
            ageMinutes = $null
            staleAfterMinutes = $staleAfterMinutes
            stale = $true
            path = $safePath
            nextAction = 'regenerate_computer_use_smoke_json'
            helperCountOnly = $false
            safePendingProof = $false
            probeSchemaVersion = ''
            secretHits = $rawSecretHits
            rawSecretPatternHits = $rawSecretHits
        }
    }

    $ok = if ($null -ne $smoke.ok) { [bool]$smoke.ok } else { $false }
    $smokeDecision = [string]$smoke.decision
    $reachable = if ($null -ne $smoke.reachable) {
        [bool]$smoke.reachable
    } elseif ($ok -and ([string]::IsNullOrWhiteSpace($smokeDecision) -or $smokeDecision -eq 'ok')) {
        $true
    } else {
        $false
    }
    $generatedAtText = [string]$smoke.generatedAt
    if ([string]::IsNullOrWhiteSpace($generatedAtText)) {
        $generatedAtText = [string]$smoke.checkedAt
    }
    $ageMinutes = $null
    $stale = $true
    try {
        $generatedAt = [datetimeoffset]::Parse($generatedAtText, [System.Globalization.CultureInfo]::InvariantCulture)
        $ageMinutes = [math]::Round(((Get-Date).ToUniversalTime() - $generatedAt.UtcDateTime).TotalMinutes, 3)
        $stale = ($ageMinutes -gt $staleAfterMinutes)
    } catch {
        $stale = $true
    }
    $decision = if ($rawSecretHits -gt 0) {
        'secret-leak-risk'
    } elseif ($stale) {
        'evidence_needed'
    } elseif (-not [string]::IsNullOrWhiteSpace([string]$smoke.decision)) {
        [string]$smoke.decision
    } elseif ($ok -and $reachable) {
        'ok'
    } else {
        'evidence_needed'
    }
    $appCount = if ($null -ne $smoke.appCount) { [int]$smoke.appCount } else { 0 }
    $runningCount = if ($null -ne $smoke.runningCount) { [int]$smoke.runningCount } else { 0 }
    $windowCount = if ($null -ne $smoke.windowCount) { [int]$smoke.windowCount } else { 0 }
    $helperCountOnly = if ($null -ne $smoke.helperCountOnly) { [bool]$smoke.helperCountOnly } else { $false }
    $nextAction = if ($ok -and $reachable -and -not $stale) { '' } else { 'rerun_computer_use_lightweight_smoke' }
    $safePendingProof = if ($null -ne $smoke.safePendingProof) {
        [bool]$smoke.safePendingProof
    } else {
        (-not ($ok -and $reachable -and -not $stale)) -and
            $reachable -and
            $helperCountOnly -and
            -not $stale -and
            $rawSecretHits -eq 0 -and
            $decision -eq 'evidence_needed' -and
            -not [string]::IsNullOrWhiteSpace($nextAction)
    }

    return [ordered]@{
        present = $true
        parsed = $true
        ok = ($ok -and $reachable -and -not $stale -and $rawSecretHits -eq 0)
        decision = $decision
        reachable = $reachable
        appCount = $appCount
        runningCount = $runningCount
        windowCount = $windowCount
        generatedAt = Get-SafePacketString -Text $generatedAtText -ProjectRoot $ProjectRoot
        ageMinutes = $ageMinutes
        staleAfterMinutes = $staleAfterMinutes
        stale = $stale
        path = $safePath
        nextAction = $nextAction
        helperCountOnly = $helperCountOnly
        safePendingProof = $safePendingProof
        probeSchemaVersion = if ($null -ne $smoke.probeSchemaVersion) { Get-SafePacketString -Text ([string]$smoke.probeSchemaVersion) -ProjectRoot $ProjectRoot } else { '' }
        secretHits = (Get-SafeCountValue $smoke.secretHits) + $rawSecretHits
        rawSecretPatternHits = (Get-SafeCountValue $smoke.rawSecretPatternHits) + $rawSecretHits
    }
}

function Get-SafeBrowserTargetHost {
    param($Smoke)

    foreach ($field in @('targetHost', 'host')) {
        if ($null -ne $Smoke.$field) {
            $safeHost = ConvertTo-SafeBrowserHost -HostText ([string]$Smoke.$field)
            if ($safeHost -ne 'unknown') {
                return $safeHost
            }
        }
    }

    foreach ($field in @('targetUrl', 'url', 'rawUrl')) {
        if ($null -eq $Smoke.$field) {
            continue
        }
        $uri = $null
        if ([System.Uri]::TryCreate([string]$Smoke.$field, [System.UriKind]::Absolute, [ref]$uri)) {
            $safeHost = ConvertTo-SafeBrowserHost -HostText $uri.Host
            if ($safeHost -ne 'unknown') {
                return $safeHost
            }
        }
    }

    return 'unknown'
}

function ConvertTo-SafeBrowserHost {
    param([AllowEmptyString()][string]$HostText)

    if ([string]::IsNullOrWhiteSpace($HostText)) {
        return 'unknown'
    }
    $normalized = $HostText.Trim().ToLowerInvariant()
    if ($normalized -eq 'localhost' -or $normalized -eq '127.0.0.1' -or $normalized -eq '::1' -or $normalized -eq '[::1]') {
        return 'localhost'
    }
    if ($normalized -eq 'abandonwareai.kro.kr') {
        return 'abandonwareai.kro.kr'
    }
    return 'unknown'
}

function ConvertTo-SafeBrowserTargetHost {
    param([AllowEmptyString()][string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return 'unknown'
    }
    $uri = $null
    if ([System.Uri]::TryCreate($Text, [System.UriKind]::Absolute, [ref]$uri)) {
        $safeHost = ConvertTo-SafeBrowserHost -HostText $uri.Host
        if ($safeHost -ne 'unknown') {
            return $safeHost
        }
    }
    return ConvertTo-SafeBrowserHost -HostText $Text
}

function Get-ConfiguredBrowserTargetHost {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    foreach ($candidate in @($env:APP_PUBLIC_BASE_URL, $env:PUBLIC_BASE_URL)) {
        $safeHost = ConvertTo-SafeBrowserTargetHost -Text ([string]$candidate)
        if ($safeHost -ne 'unknown') {
            return $safeHost
        }
    }

    $applicationYaml = Join-Path $ProjectRoot 'main\resources\application.yml'
    if (-not (Test-Path -LiteralPath $applicationYaml)) {
        return 'unknown'
    }

    $text = Get-Content -Raw -LiteralPath $applicationYaml
    $match = [regex]::Match($text, '(?m)^\s*public-base-url\s*:\s*(.+?)\s*$')
    if (-not $match.Success) {
        return 'unknown'
    }
    $configuredValue = $match.Groups[1].Value.Trim().Trim('"').Trim("'")
    foreach ($urlMatch in [regex]::Matches($configuredValue, 'https?://[^}\s"]+')) {
        $safeHost = ConvertTo-SafeBrowserTargetHost -Text $urlMatch.Value
        if ($safeHost -ne 'unknown') {
            return $safeHost
        }
    }
    return ConvertTo-SafeBrowserTargetHost -Text $configuredValue
}

function Get-BrowserUseSmokeSummary {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $smokePath = Join-Path $ProjectRoot 'var\codex-smoke\browser-ui-smoke.json'
    $safePath = Get-SafePacketString -Text $smokePath -ProjectRoot $ProjectRoot
    $staleAfterMinutes = 60
    if (-not (Test-Path -LiteralPath $smokePath)) {
        $configuredTargetHost = Get-ConfiguredBrowserTargetHost -ProjectRoot $ProjectRoot
        $configuredPublicDomain = ($configuredTargetHost -eq 'abandonwareai.kro.kr')
        return [ordered]@{
            present = $false
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            reachable = $false
            localhost = $false
            publicDomain = $configuredPublicDomain
            targetAccepted = $configuredPublicDomain
            targetHost = $configuredTargetHost
            screenshotCaptured = $false
            statusClass = 'missing'
            targetContentVisible = $false
            browserSurface = 'unknown'
            evidenceNeeded = 'browser_ui_smoke_missing'
            generatedAt = ''
            ageMinutes = $null
            staleAfterMinutes = $staleAfterMinutes
            stale = $true
            path = $safePath
            nextAction = if ($configuredPublicDomain) { 'run_browser_public_domain_ui_smoke' } else { 'run_browser_local_ui_smoke' }
            secretHits = 0
            rawSecretPatternHits = 0
        }
    }

    $rawText = Get-Content -Raw -LiteralPath $smokePath
    $rawSecretHits = Count-SecretPatternHits -Text $rawText
    try {
        $smoke = $rawText | ConvertFrom-Json
    } catch {
        return [ordered]@{
            present = $true
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            reachable = $false
            localhost = $false
            publicDomain = $false
            targetAccepted = $false
            targetHost = 'unknown'
            screenshotCaptured = $false
            statusClass = 'unreadable'
            targetContentVisible = $false
            browserSurface = 'unknown'
            evidenceNeeded = 'browser_ui_smoke_unreadable'
            generatedAt = ''
            ageMinutes = $null
            staleAfterMinutes = $staleAfterMinutes
            stale = $true
            path = $safePath
            nextAction = 'regenerate_browser_ui_smoke_json'
            secretHits = $rawSecretHits
            rawSecretPatternHits = $rawSecretHits
        }
    }

    $proofNames = @(([string]$smoke.proofNames -split ',') | ForEach-Object { $_.Trim() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $requiredProofNames = @('local', 'browser', 'computer', 'supabase', 'producer', 'action')
    $hasRequiredProofNames = $true
    foreach ($requiredProofName in $requiredProofNames) {
        if ($proofNames -notcontains $requiredProofName) {
            $hasRequiredProofNames = $false
            break
        }
    }
    $proofCellCount = if ($null -ne $smoke.proofCellCount) {
        Get-SafeCountValue $smoke.proofCellCount
    } elseif ($null -ne $smoke.proofTexts) {
        @($smoke.proofTexts).Count
    } else {
        0
    }
    $proofStripReady = ($smoke.proofRootPresent -eq $true -and $proofCellCount -ge 6 -and $hasRequiredProofNames)
    $ok = if ($null -ne $smoke.ok) { [bool]$smoke.ok } else { $proofStripReady }
    $reachable = if ($null -ne $smoke.reachable) { [bool]$smoke.reachable } else { ($ok -or $proofStripReady) }
    $localhost = if ($null -ne $smoke.localhost) { [bool]$smoke.localhost } else { $proofStripReady }
    $targetHost = Get-SafeBrowserTargetHost -Smoke $smoke
    $publicDomain = ($targetHost -eq 'abandonwareai.kro.kr')
    if ($null -ne $smoke.publicDomain -and [bool]$smoke.publicDomain) {
        $publicDomain = $true
    }
    if ($null -ne $smoke.targetPublicDomain -and [bool]$smoke.targetPublicDomain) {
        $publicDomain = $true
    }
    $targetAccepted = ($localhost -or $publicDomain)
    $screenshotCaptured = if ($null -ne $smoke.screenshotCaptured) { [bool]$smoke.screenshotCaptured } else { $proofStripReady }
    $targetContentVisible = if ($null -ne $smoke.targetContentVisible) { [bool]$smoke.targetContentVisible } else { $proofStripReady }
    $statusClass = Get-SafePacketString -Text ([string]$smoke.statusClass) -ProjectRoot $ProjectRoot
    if ([string]::IsNullOrWhiteSpace($statusClass)) {
        $statusClass = if ($proofStripReady) { 'proof_strip_visible' } else { 'unknown' }
    }
    $browserSurface = Get-SafePacketString -Text ([string]$smoke.browserSurface) -ProjectRoot $ProjectRoot
    if ([string]::IsNullOrWhiteSpace($browserSurface)) {
        $browserSurface = if ($proofStripReady) { 'iab' } else { 'unknown' }
    }
    $publicListenerUnreachable = ($publicDomain -and -not $localhost -and -not $reachable -and
        ($statusClass -match 'connection_refused|tcp_unreachable|ERR_CONNECTION_REFUSED'))
    $reportedEvidenceNeeded = Get-SafePacketString -Text ([string]$smoke.evidenceNeeded) -ProjectRoot $ProjectRoot
    $evidenceNeeded = if ($rawSecretHits -gt 0) {
        'browser_smoke_secret_pattern_hits'
    } elseif ($publicListenerUnreachable) {
        'public-listener-unreachable'
    } elseif (-not [string]::IsNullOrWhiteSpace($reportedEvidenceNeeded)) {
        $reportedEvidenceNeeded
    } else {
        ''
    }
    $safePendingProof = if ($null -ne $smoke.safePendingProof) { [bool]$smoke.safePendingProof } else { $false }
    $reportedNextAction = Get-SafePacketString -Text ([string]$smoke.nextAction) -ProjectRoot $ProjectRoot
    $generatedAtText = [string]$smoke.generatedAt
    if ([string]::IsNullOrWhiteSpace($generatedAtText)) {
        $generatedAtText = [string]$smoke.checkedAt
    }
    $ageMinutes = $null
    $stale = $true
    try {
        $generatedAt = [datetimeoffset]::Parse($generatedAtText, [System.Globalization.CultureInfo]::InvariantCulture)
        $ageMinutes = [math]::Round(((Get-Date).ToUniversalTime() - $generatedAt.UtcDateTime).TotalMinutes, 3)
        $stale = ($ageMinutes -gt $staleAfterMinutes)
    } catch {
        $stale = $true
    }
    $decision = if ($rawSecretHits -gt 0) {
        'secret-leak-risk'
    } elseif ($stale) {
        'evidence_needed'
    } elseif ($ok -and $reachable -and $targetAccepted) {
        'ok'
    } elseif (-not [string]::IsNullOrWhiteSpace([string]$smoke.decision) -and [string]$smoke.decision -ne 'ok') {
        [string]$smoke.decision
    } else {
        'evidence_needed'
    }

    return [ordered]@{
        present = $true
        parsed = $true
        ok = ($ok -and $reachable -and $targetAccepted -and -not $stale -and $rawSecretHits -eq 0)
        decision = $decision
        reachable = $reachable
        localhost = $localhost
        publicDomain = $publicDomain
        targetAccepted = $targetAccepted
        targetHost = $targetHost
        safePendingProof = $safePendingProof
        screenshotCaptured = $screenshotCaptured
        statusClass = $statusClass
        targetContentVisible = $targetContentVisible
        browserSurface = $browserSurface
        evidenceNeeded = $evidenceNeeded
        generatedAt = Get-SafePacketString -Text $generatedAtText -ProjectRoot $ProjectRoot
        ageMinutes = $ageMinutes
        staleAfterMinutes = $staleAfterMinutes
        stale = $stale
        path = $safePath
        nextAction = if ($ok -and $reachable -and $targetAccepted -and -not $stale) {
            ''
        } elseif ($publicListenerUnreachable) {
            'open_public_80_443_then_rerun_browser_public_domain_ui_smoke'
        } elseif (-not $stale -and $rawSecretHits -eq 0 -and -not [string]::IsNullOrWhiteSpace($reportedNextAction)) {
            $reportedNextAction
        } elseif ($publicDomain -and -not $localhost) {
            'rerun_browser_public_domain_ui_smoke'
        } else {
            'rerun_browser_local_ui_smoke'
        }
        secretHits = (Get-SafeCountValue $smoke.secretHits) + $rawSecretHits
        rawSecretPatternHits = (Get-SafeCountValue $smoke.rawSecretPatternHits) + $rawSecretHits
    }
}

function Get-PeerEvidenceBusSummary {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $artifactPath = Join-Path $ProjectRoot 'var\codex-smoke\peer-evidence-bus.json'
    $safePath = Get-SafePacketString -Text $artifactPath -ProjectRoot $ProjectRoot
    if (-not (Test-Path -LiteralPath $artifactPath)) {
        return [ordered]@{
            present = $false
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            targetMetric = 'harmony'
            nodeRole = 'desktop'
            outputCount = 0
            promptPackPresent = $false
            missingSignalCount = 0
            laneCount = 0
            path = $safePath
            nextAction = 'collect-peer-evidence-bus-lanes'
            secretHits = 0
            rawSecretPatternHits = 0
        }
    }

    $rawText = Get-Content -Raw -LiteralPath $artifactPath
    $rawSecretHits = Count-SecretPatternHits -Text $rawText
    try {
        $artifact = $rawText | ConvertFrom-Json
    } catch {
        return [ordered]@{
            present = $true
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            targetMetric = 'harmony'
            nodeRole = 'desktop'
            outputCount = 0
            promptPackPresent = $false
            missingSignalCount = 0
            laneCount = 0
            path = $safePath
            nextAction = 'regenerate_peer_evidence_bus_json'
            secretHits = $rawSecretHits
            rawSecretPatternHits = $rawSecretHits
        }
    }

    $promptPack = $artifact.promptPack
    $missingSignals = @()
    if ($null -ne $promptPack -and $null -ne $promptPack.missingSignals) {
        $missingSignals = @($promptPack.missingSignals)
    }
    $outputCount = Get-SafeCountValue $artifact.outputCount
    if ($outputCount -eq 0 -and $null -ne $artifact.evidenceLanes) {
        $outputCount = @($artifact.evidenceLanes).Count
    }
    $artifactSecretHits = (Get-SafeCountValue $artifact.rawSecretPatternHits) + $rawSecretHits
    $targetMetric = Get-SafePacketString -Text ([string]$artifact.targetMetric) -ProjectRoot $ProjectRoot
    if ([string]::IsNullOrWhiteSpace($targetMetric)) {
        $targetMetric = 'harmony'
    }
    $nodeRole = Get-SafePacketString -Text ([string]$artifact.nodeRole) -ProjectRoot $ProjectRoot
    if ([string]::IsNullOrWhiteSpace($nodeRole)) {
        $nodeRole = 'desktop'
    }
    $ok = (
        $artifact.ok -eq $true -and
        [string]$artifact.schemaVersion -eq 'awx.mcp.peer_evidence_bus.v1' -and
        [string]$artifact.decision -eq 'peer_evidence_bus' -and
        $targetMetric -eq 'harmony' -and
        $nodeRole -eq 'desktop' -and
        $outputCount -ge 7 -and
        $null -ne $promptPack -and
        $promptPack.present -eq $true -and
        $missingSignals.Count -eq 0 -and
        $artifactSecretHits -eq 0
    )

    return [ordered]@{
        present = $true
        parsed = $true
        ok = $ok
        decision = if ($ok) { 'ok' } else { 'evidence_needed' }
        targetMetric = $targetMetric
        nodeRole = $nodeRole
        outputCount = $outputCount
        promptPackPresent = if ($null -ne $promptPack) { [bool]$promptPack.present } else { $false }
        missingSignalCount = $missingSignals.Count
        laneCount = if ($null -ne $artifact.evidenceLanes) { @($artifact.evidenceLanes).Count } else { 0 }
        path = $safePath
        nextAction = if ($ok) { '' } else { 'collect-peer-evidence-bus-lanes' }
        secretHits = $artifactSecretHits
        rawSecretPatternHits = $artifactSecretHits
    }
}

function Test-PeerEvidenceBusReuseCandidate {
    param(
        [object]$Artifact,
        [Parameter(Mandatory = $true)][string]$ProjectRoot
    )

    if ($null -eq $Artifact) {
        return $false
    }
    $hasProperty = {
        param($Object, [string]$Name)
        return ($null -ne $Object -and @($Object.PSObject.Properties.Name) -contains $Name)
    }
    $tryNonNegativeInteger = {
        param($Value)
        $parsed = 0
        return ($null -ne $Value -and [int]::TryParse([string]$Value, [ref]$parsed) -and $parsed -ge 0)
    }
    $promptPack = $Artifact.promptPack
    $promptPackContractReady = (
        $null -ne $promptPack -and
        @($promptPack.PSObject.Properties.Name) -contains 'present' -and
        @($promptPack.PSObject.Properties.Name) -contains 'missingSignals'
    )
    $protocol = $Artifact.claudePeersProtocol
    $requiredReferenceTools = @('list_peers', 'resolve_peer', 'send_message', 'close_conversation', 'set_summary', 'check_messages')
    $referenceTools = if ($null -ne $protocol -and $null -ne $protocol.referenceTools) { @($protocol.referenceTools) } else { @() }
    $protocolReady = (
        $null -ne $protocol -and
        @($requiredReferenceTools | Where-Object { $_ -notin $referenceTools }).Count -eq 0 -and
        [int]$protocol.brokerContract.defaultPort -eq 7899 -and
        [string]$protocol.codexDeliveryPolicy.mode -eq 'manual-queue-preserving' -and
        [string]$protocol.codexDeliveryPolicy.manualCheckTool -eq 'check_messages' -and
        $protocol.conversationClosure.requiresMutualClose -eq $true -and
        $protocol.conversationClosure.reopenRequiresExplicitFlag -eq $true -and
        $protocol.messageSafety.redactedSummariesOnly -eq $true -and
        $protocol.messageSafety.rawMessageBodiesStored -eq $false -and
        $protocol.messageSafety.directCanonicalSourceEdit -eq $false -and
        $protocol.messageSafety.dispatchViaPatchDropOrCommandPacket -eq $true
    )
    $safeIdentity = $Artifact.safePeerIdentityContract
    $requiredIdentitySourceFields = @('id', 'logical_name', 'cwd', 'repo_name', 'repo_root', 'branch', 'model', 'summary')
    $requiredIdentitySafeFields = @('peerIdHash', 'logicalName', 'repoName', 'branch', 'summaryHash', 'summaryLength', 'logicalNameCollisionCount', 'cwdHash', 'repoRootHash')
    $requiredIdentityForbiddenFields = @('rawCwd', 'rawRepoRoot', 'rawMessageText', 'rawSummary')
    $safeIdentityReady = (
        $null -ne $safeIdentity -and
        [string]$safeIdentity.redactionMode -eq 'hash-count-and-allowlisted-labels-only' -and
        @($requiredIdentitySourceFields | Where-Object { $_ -notin @($safeIdentity.sourceFields) }).Count -eq 0 -and
        @($requiredIdentitySafeFields | Where-Object { $_ -notin @($safeIdentity.safeFields) }).Count -eq 0 -and
        @($requiredIdentityForbiddenFields | Where-Object { $_ -notin @($safeIdentity.forbiddenFields) }).Count -eq 0 -and
        $safeIdentity.identityResolution.logicalNamePreferred -eq $true -and
        $safeIdentity.identityResolution.resolvePeerBeforeSend -eq $true -and
        $safeIdentity.identityResolution.duplicateLogicalNameRequiresPeerId -eq $true -and
        [string]$safeIdentity.identityResolution.ambiguousNameResolution -eq 'fail-closed' -and
        $safeIdentity.messageEnvelope.storeRawText -eq $false -and
        $safeIdentity.messageEnvelope.storeMessageHash -eq $true -and
        $safeIdentity.messageEnvelope.storeMessageLength -eq $true
    )
    $peerIdentity = $Artifact.peerIdentitySummary
    $peerCountReady = (& $hasProperty $peerIdentity 'peerCount') -and (& $tryNonNegativeInteger $peerIdentity.peerCount)
    $peerCollisionCountReady = (& $hasProperty $peerIdentity 'logicalNameCollisionCount') -and (& $tryNonNegativeInteger $peerIdentity.logicalNameCollisionCount)
    $peerIdentityReady = (
        $null -ne $peerIdentity -and
        $peerCountReady -and
        $peerCollisionCountReady -and
        [string]$peerIdentity.ambiguousNameResolution -in @('fail-closed', 'not-applicable') -and
        $peerIdentity.rawIdentityStored -eq $false -and
        $peerIdentity.rawPathStored -eq $false -and
        $peerIdentity.rawSummaryStored -eq $false
    )
    $webProbeLedger = $Artifact.webProbeLedger
    $webProbeSources = if ($null -ne $webProbeLedger -and $null -ne $webProbeLedger.sources) { @($webProbeLedger.sources) } else { @() }
    $webProbeAllowedDomains = if ($null -ne $webProbeLedger -and $null -ne $webProbeLedger.allowedDomains) {
        @($webProbeLedger.allowedDomains | ForEach-Object { ([string]$_).Trim().ToLowerInvariant() } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    } else {
        @()
    }
    $webProbeSourceUrls = @($webProbeSources | ForEach-Object { [string]$_.sourceUrl } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $requiredWebProbeUrls = @(
        'https://modelcontextprotocol.io/docs/concepts/tools',
        'https://supabase.com/docs/guides/ai-tools/mcp',
        'https://supabase.com/docs/guides/api/securing-your-api'
    )
    $webProbeSourcesSafe = (
        $webProbeSources.Count -ge 4 -and
        @($webProbeSources | Where-Object {
            $null -eq $_ -or
            $_.rawContentStored -ne $false -or
            $_.fullArticleStored -ne $false -or
            ([string]$_.contractImpact) -notin @('allow', 'block', 'require_gate', 'evidence_only')
        }).Count -eq 0
    )
    $webProbeSourceCountReady = (
        (& $hasProperty $webProbeLedger 'sourceCount') -and
        (& $tryNonNegativeInteger $webProbeLedger.sourceCount) -and
        [int]$webProbeLedger.sourceCount -eq $webProbeSources.Count
    )
    $webProbeLedgerReady = (
        $null -ne $webProbeLedger -and
        [string]$webProbeLedger.mode -eq 'web-probe-first' -and
        $webProbeSourceCountReady -and
        [int]$webProbeLedger.sourceCount -ge 4 -and
        $webProbeLedger.rawContentStored -eq $false -and
        $webProbeLedger.rawQueryStored -eq $false -and
        $webProbeLedger.requiresRefreshBeforePatch -eq $true -and
        @(@('supabase.com', 'modelcontextprotocol.io') | Where-Object { $_ -notin $webProbeAllowedDomains }).Count -eq 0 -and
        @($requiredWebProbeUrls | Where-Object { $_ -notin $webProbeSourceUrls }).Count -eq 0 -and
        $webProbeSourcesSafe
    )
    $webProbeRefresh = $Artifact.webProbeRefreshPacket
    $webProbeRefreshTargets = if ($null -ne $webProbeRefresh -and $null -ne $webProbeRefresh.fetchTargets) { @($webProbeRefresh.fetchTargets) } else { @() }
    $webProbeRefreshUrls = @($webProbeRefreshTargets | ForEach-Object { [string]$_.sourceUrl } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $webProbeRefreshTargetsSafe = (
        $webProbeRefreshTargets.Count -ge 4 -and
        @($webProbeRefreshTargets | Where-Object {
            $null -eq $_ -or
            $_.rawContentStored -ne $false -or
            $_.fullArticleStored -ne $false -or
            ([string]$_.method) -notin @('markdown-fetch', 'browser-or-markdown-fetch') -or
            -not ([string]$_.markdownUrl).StartsWith('https://', [System.StringComparison]::OrdinalIgnoreCase)
        }).Count -eq 0
    )
    $webProbeRefreshCountReady = (
        (& $hasProperty $webProbeRefresh 'officialSourceCount') -and
        (& $tryNonNegativeInteger $webProbeRefresh.officialSourceCount) -and
        [int]$webProbeRefresh.officialSourceCount -eq $webProbeRefreshTargets.Count
    )
    $supabaseMcpGate = if ($null -ne $webProbeRefresh) { $webProbeRefresh.supabaseMcpGate } else { $null }
    $supabaseMcpGateReady = (
        $null -ne $supabaseMcpGate -and
        $supabaseMcpGate.readOnly -eq $true -and
        $supabaseMcpGate.mutationAllowed -eq $false -and
        'SUPABASE_PROJECT_REF' -in @($supabaseMcpGate.requiredEnv) -and
        ([string]$supabaseMcpGate.endpointTemplate).Contains('read_only=true') -and
        'database' -in @($supabaseMcpGate.featureGroups) -and
        $supabaseMcpGate.storeAccessToken -eq $false
    )
    $browserProbeGate = if ($null -ne $webProbeRefresh) { $webProbeRefresh.browserProbeGate } else { $null }
    $browserProbeGateReady = (
        $null -ne $browserProbeGate -and
        $browserProbeGate.storeRawUrl -eq $false -and
        $browserProbeGate.storeScreenshotPath -eq $false -and
        $browserProbeGate.storeDomSnapshot -eq $false
    )
    $importContract = if ($null -ne $webProbeRefresh) { $webProbeRefresh.importContract } else { $null }
    $requiredImpacts = @('allow', 'block', 'require_gate', 'evidence_only')
    $importContractReady = (
        $null -ne $importContract -and
        $importContract.storeExtractsOnly -eq $true -and
        (& $hasProperty $importContract 'maxExtractChars') -and
        (& $tryNonNegativeInteger $importContract.maxExtractChars) -and
        [int]$importContract.maxExtractChars -le 240 -and
        @($requiredImpacts | Where-Object { $_ -notin @($importContract.allowedImpacts) }).Count -eq 0 -and
        $importContract.requiresSourceHash -eq $true
    )
    $webProbeRefreshReady = (
        $null -ne $webProbeRefresh -and
        [string]$webProbeRefresh.schemaVersion -eq 'awx.web_probe.refresh_packet.v1' -and
        [string]$webProbeRefresh.mode -eq 'read-only-official-sources' -and
        $webProbeRefreshCountReady -and
        [int]$webProbeRefresh.officialSourceCount -ge 4 -and
        $webProbeRefresh.mutationAllowed -eq $false -and
        $webProbeRefresh.rawContentStored -eq $false -and
        $webProbeRefresh.rawQueryStored -eq $false -and
        @($webProbeSourceUrls | Where-Object { $_ -notin $webProbeRefreshUrls }).Count -eq 0 -and
        $webProbeRefreshTargetsSafe -and
        $supabaseMcpGateReady -and
        $browserProbeGateReady -and
        $importContractReady
    )
    $evidenceLanes = if ($null -ne $Artifact.evidenceLanes) { @($Artifact.evidenceLanes) } else { @() }
    $outputCountReady = (
        (& $hasProperty $Artifact 'outputCount') -and
        (& $tryNonNegativeInteger $Artifact.outputCount) -and
        [int]$Artifact.outputCount -ge 7 -and
        [int]$Artifact.outputCount -eq $evidenceLanes.Count
    )
    $serialized = $Artifact | ConvertTo-Json -Depth 100 -Compress
    $serializedSecretHits = Count-SecretPatternHits -Text $serialized
    $reportedSecretCountReady = (
        (& $hasProperty $Artifact 'rawSecretPatternHits') -and
        (& $tryNonNegativeInteger $Artifact.rawSecretPatternHits) -and
        [int]$Artifact.rawSecretPatternHits -eq 0
    )
    $targetMetric = Get-SafePacketString -Text ([string]$Artifact.targetMetric) -ProjectRoot $ProjectRoot
    $nodeRole = Get-SafePacketString -Text ([string]$Artifact.nodeRole) -ProjectRoot $ProjectRoot
    return (
        $Artifact.ok -eq $true -and
        [string]$Artifact.schemaVersion -eq 'awx.mcp.peer_evidence_bus.v1' -and
        [string]$Artifact.decision -eq 'peer_evidence_bus' -and
        $targetMetric -eq 'harmony' -and
        $nodeRole -eq 'desktop' -and
        $outputCountReady -and
        $promptPackContractReady -and
        $protocolReady -and
        $safeIdentityReady -and
        $peerIdentityReady -and
        $webProbeLedgerReady -and
        $webProbeRefreshReady -and
        $reportedSecretCountReady -and
        $serializedSecretHits -eq 0
    )
}

function Get-WebProbeRefreshSummary {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $artifactPath = Join-Path $ProjectRoot 'var\codex-smoke\web-probe-refresh.json'
    $safePath = Get-SafePacketString -Text $artifactPath -ProjectRoot $ProjectRoot
    $staleAfterMinutes = 1440
    if (-not (Test-Path -LiteralPath $artifactPath)) {
        return [ordered]@{
            present = $false
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            targetMetric = 'harmony'
            targetCount = 0
            sourceCount = 0
            fetchedCount = 0
            failedCount = 0
            skippedCount = 0
            missingSignalCount = 0
            rawContentStored = $false
            rawQueryStored = $false
            mutationAllowed = $false
            generatedAt = ''
            fresh = $false
            staleAfterMinutes = $staleAfterMinutes
            ageMinutes = $null
            path = $safePath
            outputPath = 'var/codex-smoke/web-probe-refresh.json'
            artifactHash = ''
            nextAction = 'run-web-probe-refresh'
            secretHits = 0
            rawSecretPatternHits = 0
        }
    }

    $rawText = Get-Content -Raw -LiteralPath $artifactPath
    $artifactHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $artifactPath).Hash.ToLowerInvariant()
    $rawSecretHits = Count-SecretPatternHits -Text $rawText
    try {
        $artifact = $rawText | ConvertFrom-Json
    } catch {
        return [ordered]@{
            present = $true
            parsed = $false
            ok = $false
            decision = 'evidence_needed'
            targetMetric = 'harmony'
            targetCount = 0
            sourceCount = 0
            fetchedCount = 0
            failedCount = 0
            skippedCount = 0
            missingSignalCount = 0
            rawContentStored = $false
            rawQueryStored = $false
            mutationAllowed = $false
            generatedAt = ''
            fresh = $false
            staleAfterMinutes = $staleAfterMinutes
            ageMinutes = $null
            path = $safePath
            outputPath = 'var/codex-smoke/web-probe-refresh.json'
            artifactHash = $artifactHash
            nextAction = 'rerun-web-probe-refresh'
            secretHits = $rawSecretHits
            rawSecretPatternHits = $rawSecretHits
        }
    }

    $targetMetric = Get-SafePacketString -Text ([string]$artifact.targetMetric) -ProjectRoot $ProjectRoot
    if ([string]::IsNullOrWhiteSpace($targetMetric)) {
        $targetMetric = 'harmony'
    }
    $sourceCount = Get-SafeCountValue $artifact.sourceCount
    $fetchedCount = Get-SafeCountValue $artifact.fetchedCount
    $failedCount = Get-SafeCountValue $artifact.failedCount
    $skippedCount = Get-SafeCountValue $artifact.skippedCount
    $missingSignalCount = Get-SafeCountValue $artifact.missingSignalCount
    $generatedAtText = Get-SafePacketString -Text ([string]$artifact.generatedAt) -ProjectRoot $ProjectRoot
    $ageMinutes = $null
    $fresh = $false
    if (-not [string]::IsNullOrWhiteSpace($generatedAtText)) {
        try {
            $generatedAt = [datetimeoffset]::Parse($generatedAtText, [System.Globalization.CultureInfo]::InvariantCulture)
            $ageMinutes = [math]::Round(((Get-Date).ToUniversalTime() - $generatedAt.UtcDateTime).TotalMinutes, 3)
            $fresh = ($ageMinutes -ge -5 -and $ageMinutes -le $staleAfterMinutes)
        } catch {
            $ageMinutes = $null
            $fresh = $false
        }
    }
    $artifactSecretHits = (Get-SafeCountValue $artifact.rawSecretPatternHits) + $rawSecretHits
    $rawContentStored = Get-SafeBoolValue -Value $artifact.rawContentStored -Default $true
    $rawQueryStored = Get-SafeBoolValue -Value $artifact.rawQueryStored -Default $true
    $mutationAllowed = Get-SafeBoolValue -Value $artifact.mutationAllowed -Default $true
    $ok = (
        $artifact.ok -eq $true -and
        [string]$artifact.schemaVersion -eq 'awx.web_probe.refresh.v1' -and
        [string]$artifact.decision -eq 'web_probe_refresh' -and
        [string]$artifact.mode -eq 'read-only-official-sources' -and
        $targetMetric -eq 'harmony' -and
        $sourceCount -ge 4 -and
        $fetchedCount -ge 4 -and
        $failedCount -eq 0 -and
        $skippedCount -eq 0 -and
        $missingSignalCount -eq 0 -and
        -not $rawContentStored -and
        -not $rawQueryStored -and
        -not $mutationAllowed -and
        $fresh -and
        $artifactSecretHits -eq 0
    )

    return [ordered]@{
        present = $true
        parsed = $true
        ok = $ok
        decision = if ($ok) { 'ok' } else { 'evidence_needed' }
        targetMetric = $targetMetric
        targetCount = $sourceCount
        sourceCount = $sourceCount
        fetchedCount = $fetchedCount
        failedCount = $failedCount
        skippedCount = $skippedCount
        missingSignalCount = $missingSignalCount
        rawContentStored = $rawContentStored
        rawQueryStored = $rawQueryStored
        mutationAllowed = $mutationAllowed
        generatedAt = $generatedAtText
        fresh = $fresh
        staleAfterMinutes = $staleAfterMinutes
        ageMinutes = $ageMinutes
        path = $safePath
        outputPath = 'var/codex-smoke/web-probe-refresh.json'
        artifactHash = $artifactHash
        nextAction = if ($ok) { '' } else { 'rerun-web-probe-refresh' }
        secretHits = $artifactSecretHits
        rawSecretPatternHits = $artifactSecretHits
    }
}

function New-CollectionPacket {
    param(
        [Parameter(Mandatory = $true)]$Entries,
        $SupabaseApplySummary,
        $ExternalApplySummary,
        $ComputerUseSummary,
        $BrowserUseSummary,
        $WebProbeRefreshSummary,
        $DesktopDispatchSummary,
        $SourceHealthProducerQueue,
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$Topic,
        [Parameter(Mandatory = $true)][string]$Decision,
        [Parameter(Mandatory = $true)][string]$GeneratedAt
    )

    $supabaseEntries = @($Entries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'targetService')) -eq 'supabase' -or
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -match 'supabase' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'supabase'
    })
    $supabaseDetail = @($supabaseEntries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'targetService')) -eq 'supabase'
    } | Select-Object -First 1)
    if ($supabaseDetail.Count -gt 0) {
        $supabaseDetail = $supabaseDetail[0]
    } else {
        $supabaseDetail = $null
    }

    $externalDetails = @($Entries | Where-Object {
        $null -ne (Get-EntryValue -Entry $_ -Name 'requiredSourceIsolation') -or
        $null -ne (Get-EntryValue -Entry $_ -Name 'requiredSidecars') -or
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -match 'external' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'external'
    })
    $externalDetailObjects = @($externalDetails | Where-Object {
        $null -ne (Get-EntryValue -Entry $_ -Name 'requiredSourceIsolation') -or
        $null -ne (Get-EntryValue -Entry $_ -Name 'requiredSidecars')
    })

    $supabaseRequiredEnv = Get-SafeUniqueStrings -Values @(
        (Get-EntryValue -Entry $supabaseDetail -Name 'requiredEnvNames')
        if ($null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.requiredEnvNames) { $SupabaseApplySummary.requiredEnvNames }
    ) -ProjectRoot $ProjectRoot
    if ($supabaseRequiredEnv.Count -eq 0) {
        $supabaseRequiredEnv = @('SUPABASE_PROJECT_REF')
    }
    $supabaseSupportedAuthModes = Get-SafeUniqueStrings -Values @(
        (Get-EntryValue -Entry $supabaseDetail -Name 'supportedAuthModes')
        if ($null -ne $SupabaseApplySummary) { $SupabaseApplySummary.supportedAuthModes }
    ) -ProjectRoot $ProjectRoot
    $supabaseManualAuthSensitiveEnvRefs = Get-SafeUniqueStrings -Values @(
        (Get-EntryValue -Entry $supabaseDetail -Name 'manualAuthSensitiveEnvRefs')
        if ($null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.manualAuthSensitiveEnvRefs) { $SupabaseApplySummary.manualAuthSensitiveEnvRefs }
    ) -ProjectRoot $ProjectRoot
    if ($supabaseManualAuthSensitiveEnvRefs.Count -eq 0 -and ($supabaseSupportedAuthModes -contains 'manual_SUPABASE_ACCESS_TOKEN')) {
        $supabaseManualAuthSensitiveEnvRefs = @('SUPABASE_ACCESS_TOKEN')
    }
    $supabaseMcpOAuthSupported = if ($null -ne (Get-EntryValue -Entry $supabaseDetail -Name 'mcpOAuthSupported')) {
        [bool](Get-EntryValue -Entry $supabaseDetail -Name 'mcpOAuthSupported')
    } elseif ($null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.mcpOAuthSupported) {
        [bool]$SupabaseApplySummary.mcpOAuthSupported
    } else {
        $false
    }
    $supabaseRequiredTools = Get-SafeUniqueStrings -Values @(
        (Get-EntryValue -Entry $supabaseDetail -Name 'requiredMcpTools')
        if ($null -ne $SupabaseApplySummary) { $SupabaseApplySummary.requiredMcpTools }
    ) -ProjectRoot $ProjectRoot
    $supabaseRequiredResults = Get-SafeUniqueStrings -Values @(
        (Get-SupabaseReadOnlyRequiredResultNames)
        (Get-EntryValue -Entry $supabaseDetail -Name 'requiredResultNames')
        if ($null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.requiredResultNames) { $SupabaseApplySummary.requiredResultNames }
    ) -ProjectRoot $ProjectRoot
    $supabaseArtifacts = Get-SafeUniqueStrings -Values @(
        (Get-EntryValue -Entry $supabaseDetail -Name 'artifactPaths')
        if ($null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.artifactPaths) { $SupabaseApplySummary.artifactPaths }
    ) -ProjectRoot $ProjectRoot
    if ($supabaseArtifacts.Count -eq 0) {
        $supabaseArtifacts = @(
            'data/db-gap-report/supabase-execute-sql-collection.packet.json',
            'data/db-gap-report/supabase-query-results.template.json',
            'data/db-gap-report/supabase-readonly-snapshot.sql',
            'data/db-gap-report/supabase-schema-snapshot.json'
        )
    }
    $supabaseDocs = Get-SafeUniqueStrings -Values @(
        (Get-EntryValue -Entry $supabaseDetail -Name 'docsRefs')
        if ($null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.docsRefs) { $SupabaseApplySummary.docsRefs }
    ) -ProjectRoot $ProjectRoot
    if ($supabaseDocs.Count -eq 0) {
        $supabaseDocs = @(
            'https://supabase.com/docs/guides/ai-tools/mcp',
            'https://supabase.com/docs/guides/api/securing-your-api',
            'https://supabase.com/docs/guides/security/product-security'
        )
    }
    $supabaseOfficialContractSignals = Get-SafeUniqueStrings -Values @(
        (Get-EntryValue -Entry $supabaseDetail -Name 'officialContractSignals')
        if ($null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.officialContractSignals) { $SupabaseApplySummary.officialContractSignals }
    ) -ProjectRoot $ProjectRoot
    if ($supabaseOfficialContractSignals.Count -eq 0) {
        $supabaseOfficialContractSignals = @(
            'mcp_project_scoped_read_only',
            'data_api_grants_required',
            'rls_policy_required',
            'secret_keys_backend_only',
            'advisors_required_before_schema_claim'
        )
    }
    $supabaseCollectionGuards = New-SafeSupabaseCollectionGuards -Value (Get-EntryValue -Entry $supabaseDetail -Name 'collectionGuards')
    if ($supabaseCollectionGuards.Count -eq 0) {
        $supabaseCollectionGuards = [ordered]@{
            mutationAllowed = $false
            storeRawRows = $false
            requireProjectScope = $true
            requireAdvisors = $true
        }
    }
    $supabaseNextActions = Get-SafeUniqueStrings -Values @(
        if ($null -ne $SupabaseApplySummary) { $SupabaseApplySummary.nextActions }
        (Get-EntryValue -Entry $supabaseDetail -Name 'nextActions')
    ) -ProjectRoot $ProjectRoot
    $supabaseResultPath = [string](Get-EntryValue -Entry $supabaseDetail -Name 'resultPathRecommendation')
    if ([string]::IsNullOrWhiteSpace($supabaseResultPath) -and $null -ne $SupabaseApplySummary) {
        $supabaseResultPath = [string]$SupabaseApplySummary.resultPathRecommendation
    }
    $supabaseAdvisorPath = [string](Get-EntryValue -Entry $supabaseDetail -Name 'advisorResultPathRecommendation')
    if ([string]::IsNullOrWhiteSpace($supabaseAdvisorPath) -and $null -ne $SupabaseApplySummary) {
        $supabaseAdvisorPath = [string]$SupabaseApplySummary.advisorResultPathRecommendation
    }
    $supabaseImportTool = [string](Get-EntryValue -Entry $supabaseDetail -Name 'importTool')
    if ([string]::IsNullOrWhiteSpace($supabaseImportTool) -and $null -ne $SupabaseApplySummary) {
        $supabaseImportTool = [string]$SupabaseApplySummary.importTool
    }
    $supabaseMcpConfig = Get-SupabaseMcpConfigSummary -ProjectRoot $ProjectRoot

    $externalRoles = Get-SafeUniqueStrings -Values @(
        if ($null -ne $ExternalApplySummary) { $ExternalApplySummary.requiredRoles }
        $externalDetailObjects | ForEach-Object { Get-EntryValue -Entry $_ -Name 'targetRole' }
    ) -ProjectRoot $ProjectRoot
    $externalProducerFiles = Get-SafeUniqueStrings -Values @(
        if ($null -ne $ExternalApplySummary) { $ExternalApplySummary.requiredProducerEvidenceFiles }
    ) -ProjectRoot $ProjectRoot
    $externalSidecars = Get-SafeUniqueStrings -Values @(
        if ($null -ne $ExternalApplySummary) { $ExternalApplySummary.requiredPatchDropSidecars }
        $externalDetailObjects | ForEach-Object { Get-EntryValue -Entry $_ -Name 'requiredSidecars' }
    ) -ProjectRoot $ProjectRoot
    $externalNextActions = Get-SafeUniqueStrings -Values @(
        if ($null -ne $ExternalApplySummary) { $ExternalApplySummary.nextActions }
        $externalDetailObjects | ForEach-Object { Get-EntryValue -Entry $_ -Name 'nextActions' }
    ) -ProjectRoot $ProjectRoot
    $computerUseEntries = @($Entries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -eq 'computer_use' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'computer_use'
    })
    $computerUseNextActions = Get-SafeUniqueStrings -Values @(
        $computerUseEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'action' }
        $computerUseEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'nextActions' }
        if ($null -ne $ComputerUseSummary) { $ComputerUseSummary.nextAction }
    ) -ProjectRoot $ProjectRoot
    $computerUseDecision = if ($computerUseNextActions.Count -gt 0) { 'evidence_needed' } else { 'ok' }
    $computerUseNextActionList = [System.Collections.Generic.List[string]]::new()
    foreach ($action in @($computerUseNextActions)) {
        $actionText = [string]$action
        if (-not [string]::IsNullOrWhiteSpace($actionText)) {
            $computerUseNextActionList.Add($actionText) | Out-Null
        }
    }

    $browserUseEntries = @($Entries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -eq 'browser_use' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'browser_use|browser_local_ui|browser_public_domain_ui'
    })
    $browserUseNextActions = Get-SafeUniqueStrings -Values @(
        $browserUseEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'action' }
        $browserUseEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'nextActions' }
        if ($null -ne $BrowserUseSummary) { $BrowserUseSummary.nextAction }
    ) -ProjectRoot $ProjectRoot
    $browserUseDecision = if ($browserUseNextActions.Count -gt 0) {
        'evidence_needed'
    } elseif ($null -ne $BrowserUseSummary) {
        [string]$BrowserUseSummary.decision
    } else {
        'ok'
    }
    $browserUseNextActionList = [System.Collections.Generic.List[string]]::new()
    foreach ($action in @($browserUseNextActions)) {
        $actionText = [string]$action
        if (-not [string]::IsNullOrWhiteSpace($actionText)) {
            $browserUseNextActionList.Add($actionText) | Out-Null
        }
    }
    $browserUseSummaryNextAction = if ($null -ne $BrowserUseSummary) { [string]$BrowserUseSummary.nextAction } else { '' }
    $browserUseExternalListenerRequired = (@($browserUseNextActionList.ToArray()) -contains 'open_public_80_443_then_rerun_browser_public_domain_ui_smoke') -or
        ($browserUseSummaryNextAction -eq 'open_public_80_443_then_rerun_browser_public_domain_ui_smoke')
    $browserUsePreflightCommand = if ($browserUseExternalListenerRequired) {
        'powershell -NoProfile -ExecutionPolicy Bypass -File scripts\domain_public_https_preflight.ps1 -Domain abandonwareai.kro.kr -TlsMode offload -RequireRunning -RequireTrustedCertificate -Json'
    } else {
        ''
    }
    $localInteractionSmokeRefresh = New-LocalInteractionSmokeRefreshContract -ProjectRoot $ProjectRoot
    $smbDebugProbe = New-SmbDecommissionDebugProbeContract -ProjectRoot $ProjectRoot
    $traceMemoryRuntimeProof = New-TraceMemoryRuntimeProofContract -ProjectRoot $ProjectRoot

    $archiveEntries = @($Entries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'targetService')) -eq 'archive' -or
        ([string](Get-EntryValue -Entry $_ -Name 'source')) -match 'archive' -or
        ([string](Get-EntryValue -Entry $_ -Name 'action')) -match 'archive'
    })
    $archiveDetail = @($archiveEntries | Where-Object {
        ([string](Get-EntryValue -Entry $_ -Name 'targetService')) -eq 'archive'
    } | Select-Object -First 1)
    if ($archiveDetail.Count -gt 0) {
        $archiveDetail = $archiveDetail[0]
    } else {
        $archiveDetail = $null
    }
    $archiveRequiredEnv = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $archiveDetail -Name 'requiredEnvNames') -ProjectRoot $ProjectRoot
    $archiveRequiredTools = Get-SafeUniqueStrings -Values (Get-EntryValue -Entry $archiveDetail -Name 'requiredMcpTools') -ProjectRoot $ProjectRoot
    $archiveNextActions = Get-SafeUniqueStrings -Values @(
        $archiveEntries | ForEach-Object { Get-EntryValue -Entry $_ -Name 'nextActions' }
    ) -ProjectRoot $ProjectRoot
    $archiveIndexPath = [string](Get-EntryValue -Entry $archiveDetail -Name 'indexPathRecommendation')
    if ([string]::IsNullOrWhiteSpace($archiveIndexPath)) {
        $archiveIndexPath = 'BackupsXS/index.jsonl'
    }
    $archiveRootPath = [string](Get-EntryValue -Entry $archiveDetail -Name 'archiveRootRecommendation')
    if ([string]::IsNullOrWhiteSpace($archiveRootPath)) {
        $archiveRootPath = 'BackupsXS'
    }

    $sourceIsolation = $null
    if ($null -ne $ExternalApplySummary -and $null -ne $ExternalApplySummary.requiredSourceIsolation) {
        $sourceIsolation = $ExternalApplySummary.requiredSourceIsolation
    } elseif ($externalDetailObjects.Count -gt 0) {
        $sourceIsolation = Get-EntryValue -Entry $externalDetailObjects[0] -Name 'requiredSourceIsolation'
    }
    $safeSourceIsolation = [ordered]@{}
    if ($null -ne $sourceIsolation) {
        $safeSourceIsolation = [ordered]@{
            guard = Get-SafePacketString -Text ([string]$sourceIsolation.guard) -ProjectRoot $ProjectRoot
            sourceRootKind = Get-SafePacketString -Text ([string]$sourceIsolation.sourceRootKind) -ProjectRoot $ProjectRoot
            directCanonicalSourceEdit = [bool]$sourceIsolation.directCanonicalSourceEdit
            desktopFinalProof = Get-SafePacketString -Text ([string]$sourceIsolation.desktopFinalProof) -ProjectRoot $ProjectRoot
            rawSecretPatternHits = if ($null -ne $sourceIsolation.rawSecretPatternHits) { [int]$sourceIsolation.rawSecretPatternHits } else { 0 }
        }
    }

    return [ordered]@{
        schemaVersion = 'awx.goal_next_auto.collection_packet.v1'
        generatedAt = $GeneratedAt
        decision = $Decision
        topic = $Topic
        secretSafe = $true
        supabase = [ordered]@{
            decision = if ($null -ne $SupabaseApplySummary) { [string]$SupabaseApplySummary.decision } else { 'evidence_needed' }
            readOnly = if ($null -ne (Get-EntryValue -Entry $supabaseDetail -Name 'readOnly')) { [bool](Get-EntryValue -Entry $supabaseDetail -Name 'readOnly') } else { $true }
            mutationAllowed = if ($null -ne (Get-EntryValue -Entry $supabaseDetail -Name 'mutationAllowed')) { [bool](Get-EntryValue -Entry $supabaseDetail -Name 'mutationAllowed') } else { $false }
            requiredEnvNames = @($supabaseRequiredEnv)
            supportedAuthModes = @($supabaseSupportedAuthModes)
            manualAuthSensitiveEnvRefs = @($supabaseManualAuthSensitiveEnvRefs)
            mcpOAuthSupported = $supabaseMcpOAuthSupported
            requiredMcpTools = @($supabaseRequiredTools)
            requiredResultNames = @($supabaseRequiredResults)
            artifactPaths = @($supabaseArtifacts)
            docsRefs = @($supabaseDocs)
            officialContractSignals = @($supabaseOfficialContractSignals)
            collectionGuards = $supabaseCollectionGuards
            nextActions = @($supabaseNextActions)
            resultPathRecommendation = Get-SafePacketString -Text $supabaseResultPath -ProjectRoot $ProjectRoot
            advisorResultPathRecommendation = Get-SafePacketString -Text $supabaseAdvisorPath -ProjectRoot $ProjectRoot
            importTool = Get-SafePacketString -Text $supabaseImportTool -ProjectRoot $ProjectRoot
            readOnlyMcpEndpointTemplate = if ($null -ne $SupabaseApplySummary) { Get-SafePacketString -Text ([string]$SupabaseApplySummary.readOnlyMcpEndpointTemplate) -ProjectRoot $ProjectRoot } else { '' }
            projectRefEnvPresent = if ($null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.projectRefEnvPresent) { [bool]$SupabaseApplySummary.projectRefEnvPresent } else { $false }
            accessTokenEnvPresent = if ($null -ne $SupabaseApplySummary -and $null -ne $SupabaseApplySummary.accessTokenEnvPresent) { [bool]$SupabaseApplySummary.accessTokenEnvPresent } else { $false }
            envPreflightStatus = if ($null -ne $SupabaseApplySummary) { Get-SafePacketString -Text ([string]$SupabaseApplySummary.envPreflightStatus) -ProjectRoot $ProjectRoot } else { '' }
            applyCollectedEvidenceCommand = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $supabaseDetail -Name 'applyCollectedEvidenceCommand')) -ProjectRoot $ProjectRoot
            mcpConfig = $supabaseMcpConfig
        }
        external = [ordered]@{
            decision = if ($null -ne $ExternalApplySummary) { [string]$ExternalApplySummary.decision } else { 'evidence_needed' }
            requiredRoles = $externalRoles
            requiredProducerEvidenceFiles = $externalProducerFiles
            requiredSidecars = $externalSidecars
            requiredSourceIsolation = $safeSourceIsolation
            nextActions = $externalNextActions
            applyCollectedEvidenceCommand = if ($null -ne $ExternalApplySummary) { Get-SafePacketString -Text ([string]$ExternalApplySummary.applyCollectedEvidenceCommand) -ProjectRoot $ProjectRoot } else { '' }
        }
        sourceHealthProducerQueue = if ($null -ne $SourceHealthProducerQueue) { $SourceHealthProducerQueue } else { New-SafeSourceHealthProducerQueue -SourceHealthJson $null -ProjectRoot $ProjectRoot }
        desktopDispatch = $DesktopDispatchSummary
        webProbeRefresh = [ordered]@{
            decision = if ($null -ne $WebProbeRefreshSummary) { [string]$WebProbeRefreshSummary.decision } else { 'evidence_needed' }
            evidenceSource = if ($null -ne $WebProbeRefreshSummary) { Get-SafePacketString -Text ([string]$WebProbeRefreshSummary.evidenceSource) -ProjectRoot $ProjectRoot } else { '' }
            executionMode = if ($null -ne $WebProbeRefreshSummary) { Get-SafePacketString -Text ([string]$WebProbeRefreshSummary.executionMode) -ProjectRoot $ProjectRoot } else { 'manual-opt-in' }
            refreshRequested = if ($null -ne $WebProbeRefreshSummary) { [bool]$WebProbeRefreshSummary.refreshRequested } else { $false }
            processExecuted = if ($null -ne $WebProbeRefreshSummary) { [bool]$WebProbeRefreshSummary.processExecuted } else { $false }
            contractReady = if ($null -ne $WebProbeRefreshSummary) { [bool]$WebProbeRefreshSummary.contractReady } else { $false }
            proofReady = if ($null -ne $WebProbeRefreshSummary) { [bool]$WebProbeRefreshSummary.proofReady } else { $false }
            supportingEvidenceOnly = if ($null -ne $WebProbeRefreshSummary) { [bool]$WebProbeRefreshSummary.supportingEvidenceOnly } else { $true }
            present = if ($null -ne $WebProbeRefreshSummary) { [bool]$WebProbeRefreshSummary.present } else { $false }
            parsed = if ($null -ne $WebProbeRefreshSummary) { [bool]$WebProbeRefreshSummary.parsed } else { $false }
            ok = if ($null -ne $WebProbeRefreshSummary) { [bool]$WebProbeRefreshSummary.ok } else { $false }
            tool = 'web_probe_refresh'
            outputPath = 'var/codex-smoke/web-probe-refresh.json'
            artifactHash = if ($null -ne $WebProbeRefreshSummary) { Get-SafePacketString -Text ([string]$WebProbeRefreshSummary.artifactHash) -ProjectRoot $ProjectRoot } else { '' }
            targetMetric = if ($null -ne $WebProbeRefreshSummary) { Get-SafePacketString -Text ([string]$WebProbeRefreshSummary.targetMetric) -ProjectRoot $ProjectRoot } else { 'harmony' }
            targetCount = if ($null -ne $WebProbeRefreshSummary) { [int]$WebProbeRefreshSummary.targetCount } else { 0 }
            sourceCount = if ($null -ne $WebProbeRefreshSummary) { [int]$WebProbeRefreshSummary.sourceCount } else { 0 }
            fetchedCount = if ($null -ne $WebProbeRefreshSummary) { [int]$WebProbeRefreshSummary.fetchedCount } else { 0 }
            failedCount = if ($null -ne $WebProbeRefreshSummary) { [int]$WebProbeRefreshSummary.failedCount } else { 0 }
            skippedCount = if ($null -ne $WebProbeRefreshSummary) { [int]$WebProbeRefreshSummary.skippedCount } else { 0 }
            missingSignalCount = if ($null -ne $WebProbeRefreshSummary) { [int]$WebProbeRefreshSummary.missingSignalCount } else { 0 }
            rawContentStored = $false
            rawQueryStored = $false
            mutationAllowed = $false
            secretHits = if ($null -ne $WebProbeRefreshSummary) { [int]$WebProbeRefreshSummary.secretHits } else { 0 }
            requiredEnvNames = @()
            nextActions = if ($null -ne $WebProbeRefreshSummary -and -not [string]::IsNullOrWhiteSpace([string]$WebProbeRefreshSummary.nextAction)) { @([string]$WebProbeRefreshSummary.nextAction) } else { @() }
        }
        localInteractionSmokeRefresh = $localInteractionSmokeRefresh
        smbDecommissionDebugProbe = $smbDebugProbe
        traceMemoryRuntimeProof = $traceMemoryRuntimeProof
        archive = [ordered]@{
            decision = if ($archiveEntries.Count -gt 0) { 'evidence_needed' } else { 'ok' }
            readOnly = if ($null -ne (Get-EntryValue -Entry $archiveDetail -Name 'readOnly')) { [bool](Get-EntryValue -Entry $archiveDetail -Name 'readOnly') } else { $true }
            mutationAllowed = if ($null -ne (Get-EntryValue -Entry $archiveDetail -Name 'mutationAllowed')) { [bool](Get-EntryValue -Entry $archiveDetail -Name 'mutationAllowed') } else { $false }
            requiredEnvNames = $archiveRequiredEnv
            requiredMcpTools = $archiveRequiredTools
            indexPathRecommendation = Get-SafePacketString -Text $archiveIndexPath -ProjectRoot $ProjectRoot
            archiveRootRecommendation = Get-SafePacketString -Text $archiveRootPath -ProjectRoot $ProjectRoot
            nextActions = $archiveNextActions
            applyCollectedEvidenceCommand = Get-SafePacketString -Text ([string](Get-EntryValue -Entry $archiveDetail -Name 'applyCollectedEvidenceCommand')) -ProjectRoot $ProjectRoot
        }
        computerUse = [ordered]@{
            decision = $computerUseDecision
            tool = 'mcp__node_repl.js'
            outputPath = 'var/codex-smoke/computer-use-smoke.json'
            storesRawAppNames = $false
            storesWindowTitles = $false
            appCount = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.appCount) { [int]$ComputerUseSummary.appCount } else { 0 }
            runningCount = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.runningCount) { [int]$ComputerUseSummary.runningCount } else { 0 }
            windowCount = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.windowCount) { [int]$ComputerUseSummary.windowCount } else { 0 }
            helperCountOnly = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.helperCountOnly) { [bool]$ComputerUseSummary.helperCountOnly } else { $false }
            probeSchemaVersion = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.probeSchemaVersion) { Get-SafePacketString -Text ([string]$ComputerUseSummary.probeSchemaVersion) -ProjectRoot $ProjectRoot } else { '' }
            secretHits = if ($null -ne $ComputerUseSummary -and $null -ne $ComputerUseSummary.secretHits) { [int]$ComputerUseSummary.secretHits } else { 0 }
            requiredEnvNames = @()
            nextActions = $computerUseNextActionList.ToArray()
        }
        browserUse = [ordered]@{
            decision = $browserUseDecision
            tool = 'browser.control-in-app-browser'
            outputPath = 'var/codex-smoke/browser-ui-smoke.json'
            storesRawUrl = $false
            storesScreenshotPath = $false
            externalListenerRequired = $browserUseExternalListenerRequired
            preflightCommand = $browserUsePreflightCommand
            reachable = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.reachable) { [bool]$BrowserUseSummary.reachable } else { $false }
            localhost = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.localhost) { [bool]$BrowserUseSummary.localhost } else { $false }
            publicDomain = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.publicDomain) { [bool]$BrowserUseSummary.publicDomain } else { $false }
            targetAccepted = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.targetAccepted) { [bool]$BrowserUseSummary.targetAccepted } else { $false }
            targetHost = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.targetHost) { Get-SafePacketString -Text ([string]$BrowserUseSummary.targetHost) -ProjectRoot $ProjectRoot } else { 'unknown' }
            safePendingProof = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.safePendingProof) { [bool]$BrowserUseSummary.safePendingProof } else { $false }
            screenshotCaptured = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.screenshotCaptured) { [bool]$BrowserUseSummary.screenshotCaptured } else { $false }
            statusClass = if ($null -ne $BrowserUseSummary) { Get-SafePacketString -Text ([string]$BrowserUseSummary.statusClass) -ProjectRoot $ProjectRoot } else { 'unknown' }
            targetContentVisible = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.targetContentVisible) { [bool]$BrowserUseSummary.targetContentVisible } else { $false }
            browserSurface = if ($null -ne $BrowserUseSummary) { Get-SafePacketString -Text ([string]$BrowserUseSummary.browserSurface) -ProjectRoot $ProjectRoot } else { 'unknown' }
            evidenceNeeded = if ($null -ne $BrowserUseSummary) { Get-SafePacketString -Text ([string]$BrowserUseSummary.evidenceNeeded) -ProjectRoot $ProjectRoot } else { '' }
            secretHits = if ($null -ne $BrowserUseSummary -and $null -ne $BrowserUseSummary.secretHits) { [int]$BrowserUseSummary.secretHits } else { 0 }
            requiredEnvNames = @()
            nextActions = $browserUseNextActionList.ToArray()
        }
    }
}

function Get-SourceEditLeaseSummary {
    param([Parameter(Mandatory = $true)][string]$PatchDropDir)
    return Get-AwxSourceEditLeaseSummary -PatchDropDir $PatchDropDir
}

function Get-DesktopPreflight {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    [string]$gitRoot = ''
    $gitRootAvailable = $false
    $git = Get-Command git -ErrorAction SilentlyContinue
    if ($null -ne $git) {
        $previousErrorActionPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $gitRootOutput = (& git -C $ProjectRoot rev-parse --show-toplevel 2>$null | Select-Object -First 1)
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($null -ne $gitRootOutput -and -not [string]::IsNullOrWhiteSpace([string]$gitRootOutput)) {
            $gitRootAvailable = $true
            $gitRoot = [string]$gitRootOutput
        }
    }

    $gitOperationEvidence = Get-AwxGitOperationEvidence -ProjectRoot $ProjectRoot
    $indexLockPresent = $gitOperationEvidence.indexLockPresent
    $patchDropDir = Join-Path $ProjectRoot '__patch_drop__'
    $pendingPatches = @()
    if (Test-Path -LiteralPath $patchDropDir) {
        $pendingPatches = @(Get-ChildItem -LiteralPath $patchDropDir -File -Filter '*.patch' -ErrorAction SilentlyContinue | Sort-Object Name)
    }
    $pendingPatchNames = @($pendingPatches | ForEach-Object { $_.Name })
    $sourceLeaseSummary = if (Test-Path -LiteralPath $patchDropDir) {
        Get-SourceEditLeaseSummary -PatchDropDir $patchDropDir
    } else {
        [ordered]@{
            sourceLeaseDirPresent = $false
            sourceLeaseActiveCount = 0
            sourceLeaseCorruptCount = 0
            sourceLeaseExpiredCount = 0
            sourceLeaseActiveTopics = @()
            sourceLeaseCorruptTopics = @()
            sourceLeaseExpiredTopics = @()
        }
    }

    $failureClassification = ''
    if ($indexLockPresent) {
        $failureClassification = 'index-lock-conflict'
    } elseif ($pendingPatchNames.Count -gt 0) {
        $failureClassification = 'patch-drop-pending'
    } elseif ([int]$sourceLeaseSummary.sourceLeaseActiveCount -gt 0) {
        $failureClassification = 'source-edit-lease-held'
    } elseif ([int]$sourceLeaseSummary.sourceLeaseCorruptCount -gt 0) {
        $failureClassification = 'source-edit-lease-corrupt'
    }

    return [ordered]@{
        gitRootAvailable = $gitRootAvailable
        gitRoot = $gitRoot
        indexLockPresent = $indexLockPresent
        patchDropDirPresent = (Test-Path -LiteralPath $patchDropDir)
        patchDropPendingPatchCount = $pendingPatchNames.Count
        patchDropPendingPatchNames = $pendingPatchNames
        sourceLeaseDirPresent = $sourceLeaseSummary.sourceLeaseDirPresent
        sourceLeaseActiveCount = $sourceLeaseSummary.sourceLeaseActiveCount
        sourceLeaseCorruptCount = $sourceLeaseSummary.sourceLeaseCorruptCount
        sourceLeaseExpiredCount = $sourceLeaseSummary.sourceLeaseExpiredCount
        sourceLeaseActiveTopics = $sourceLeaseSummary.sourceLeaseActiveTopics
        sourceLeaseCorruptTopics = $sourceLeaseSummary.sourceLeaseCorruptTopics
        sourceLeaseExpiredTopics = $sourceLeaseSummary.sourceLeaseExpiredTopics
        failureClassification = $failureClassification
        operationDecision = (Get-AwxScopedOperationDecision -Operation read-only -GitEvidence $gitOperationEvidence)
        holdScope = if ($indexLockPresent) { @('git-index-write','unscoped-source-write') } elseif ($pendingPatchNames.Count -gt 0 -or [int]$sourceLeaseSummary.sourceLeaseActiveCount -gt 0 -or [int]$sourceLeaseSummary.sourceLeaseCorruptCount -gt 0) { @('source-write') } else { @() }
        firstBlockingRule = $failureClassification
        blockingEvidence = $failureClassification
        independentWorkCompleted = @('read-only-preflight')
        repositoryWideHold = $false
    }
}

function Get-GoalNextBlockerIdentity {
    param([string]$ProjectRoot, [object]$Mode, [string]$RecoveryEvidencePath = '')
    # Hash actual inputs, not audit timestamps or output counters. Hashing these
    # bounded active roots is cheaper than rerunning all child audits.
    $rows = [Collections.Generic.List[string]]::new()
    foreach ($relative in @('main/java','main/resources','app/src/main/java_clean','app/src/main/resources','scripts')) {
        $dir = Join-Path $ProjectRoot $relative
        if (-not (Test-Path -LiteralPath $dir -PathType Container)) { continue }
        foreach ($file in @(Get-ChildItem -LiteralPath $dir -Recurse -File -ErrorAction Stop | Where-Object { $_.Extension -in @('.java','.py','.ps1','.json','.yml','.yaml','.properties','.html','.js') -and $_.FullName -notmatch '[\\/](__pycache__|node_modules|build|\.gradle)[\\/]' } | Sort-Object FullName)) {
            $rows.Add($file.FullName.Substring($ProjectRoot.Length) + ':' + (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) | Out-Null
        }
    }
    foreach ($relative in @('AGENTS.md','build.gradle.kts','settings.gradle','app/build.gradle.kts','__patch_drop__/source_edit_session.ps1','__patch_drop__/source_edit_lease_contract.ps1','.agents/skills/scoped-blocker-recovery/SKILL.md','.agents/skills/scoped-blocker-recovery/references/decision-table.md','.agents/skills/scoped-blocker-recovery/references/review-prompts.md','.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md','.agents/skills/patchdrop-safe-patch-orchestrator/SKILL.md','var/codex-smoke/computer-use-smoke.json','var/codex-smoke/browser-ui-smoke.json','data/db-gap-report/supabase-query-results.json','data/db-gap-report/supabase-advisors.json')) {
        $path = Join-Path $ProjectRoot $relative
        if (Test-Path -LiteralPath $path -PathType Leaf) { $rows.Add($relative + ':' + (Get-AwxEvidenceFileHash -Path $path)) | Out-Null }
    }
    $gitEvidence = Get-AwxGitOperationEvidence -ProjectRoot $ProjectRoot
    $resources = [Collections.Generic.List[string]]::new()
    # This schedules a read-only audit. Transient Git processes do not change
    # its blockers; each source/index write still performs a fresh writer check.
    $resources.Add(([ordered]@{ ok = $gitEvidence.ok; reason = $gitEvidence.reason; indexLockPath = $gitEvidence.indexLockPath; indexLockPresent = $gitEvidence.indexLockPresent; activeOperation = $gitEvidence.activeOperation } | ConvertTo-Json -Compress)) | Out-Null
    if ($gitEvidence.indexLockPresent) {
        $lock = Get-Item -LiteralPath $gitEvidence.indexLockPath -Force
        $resources.Add("index:$($lock.CreationTimeUtc.Ticks):$($lock.Length):$((Get-FileHash -LiteralPath $lock.FullName -Algorithm SHA256).Hash)") | Out-Null
    }
    $patchDrop = Join-Path $ProjectRoot '__patch_drop__'
    if (Test-Path -LiteralPath $patchDrop) {
        $leaseState = Get-AwxSourceEditLeaseSummary -PatchDropDir $patchDrop
        $resources.Add("lease-status:$($leaseState.sourceLeaseActiveCount):$($leaseState.sourceLeaseCorruptCount):$($leaseState.sourceLeaseExpiredCount)") | Out-Null
        $resourceFiles = @(Get-ChildItem -LiteralPath $patchDrop -File -Filter '*.patch')
        $locks = Join-Path $patchDrop 'source-edit-locks'
        if (Test-Path -LiteralPath $locks) { $resourceFiles += @(Get-ChildItem -LiteralPath $locks -Recurse -File) }
        foreach ($file in @($resourceFiles | Sort-Object FullName)) { $resources.Add($file.Name + ':' + (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash) | Out-Null }
    }
    if ($RecoveryEvidencePath) {
        $proof = Resolve-RepoPath -ProjectRoot $ProjectRoot -PathText $RecoveryEvidencePath
        if (-not (Test-Path -LiteralPath $proof -PathType Leaf)) { throw 'recovery-evidence-missing' }
        $resources.Add('recovery:' + (Get-AwxEvidenceFileHash -Path $proof)) | Out-Null
    }
    $stableMode = [ordered]@{}
    foreach ($name in @('requireSupabaseProof','externalDispatch','refreshWebProbe','requireUiProof')) { $stableMode[$name] = [bool]$Mode[$name] }
    Write-Verbose "[AWX][goal-next-resume] sourceCount=$($rows.Count) gitOk=$($gitEvidence.ok) activeOperation=$($gitEvidence.activeOperation) writerCheckAvailable=$($gitEvidence.writerCheckAvailable) writerCount=$($gitEvidence.writerCount) resourceCount=$($resources.Count)"
    return Get-AwxBlockerFingerprint -Reasons @('goal-next-inputs') -Scopes @('requested-audit') -SourceIdentity ($rows -join "`n") -ResourceIdentity (($resources -join "`n") + ($stableMode | ConvertTo-Json -Compress))
}

function New-SkippedProcessCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [Parameter(Mandatory = $true)][string]$Reason
    )

    $safeReason = Redact-SensitiveText $Reason
    $message = "[AWX][goal-next] lane=$Name process=skipped reason=$safeReason"
    Set-Content -LiteralPath $LogPath -Value $message -Encoding UTF8
    return [pscustomobject]@{
        Name = $Name
        ExitCode = 0
        SecretHits = 0
        Output = $message
        LogPath = $LogPath
        ProcessExecuted = $false
        EvidenceSource = 'static_optional_contract'
    }
}

function Write-CompactArtifactLog {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [string]$ArtifactPath = '',
        [string]$ArtifactLabel = '',
        [int]$ExitCode = 0,
        [int]$SecretHits = 0,
        $ArtifactBeforeState = $null
    )

    $artifactPresent = -not [string]::IsNullOrWhiteSpace($ArtifactPath) -and (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)
    $artifactHash = ''
    $artifactBytes = 0L
    $artifactLastWriteTicks = 0L
    if ($artifactPresent) {
        $artifactItem = Get-Item -LiteralPath $ArtifactPath
        $artifactHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ArtifactPath).Hash.ToLowerInvariant()
        $artifactBytes = [int64]$artifactItem.Length
        $artifactLastWriteTicks = [int64]$artifactItem.LastWriteTimeUtc.Ticks
    }
    $artifactProducedThisRun = $artifactPresent -and (
        $null -eq $ArtifactBeforeState -or
        -not [bool]$ArtifactBeforeState.Present -or
        [string]$ArtifactBeforeState.Hash -ne $artifactHash -or
        [int64]$ArtifactBeforeState.Bytes -ne $artifactBytes -or
        [int64]$ArtifactBeforeState.LastWriteTimeUtcTicks -ne $artifactLastWriteTicks)
    $safeLabel = Redact-SensitiveText $ArtifactLabel
    if ([string]::IsNullOrWhiteSpace($safeLabel) -and -not [string]::IsNullOrWhiteSpace($ArtifactPath)) {
        $safeLabel = Split-Path -Leaf $ArtifactPath
    }
    if ($safeLabel -match '^[A-Za-z]:[\\/]' -or $safeLabel.StartsWith('\\')) {
        $safeLabel = Split-Path -Leaf $safeLabel
    }
    Write-JsonFile -Path $LogPath -Value ([ordered]@{
        schemaVersion = 'awx.goal_next_auto.compact_artifact_log.v1'
        name = $Name
        exitCode = $ExitCode
        artifactPath = $safeLabel
        artifactPresent = $artifactPresent
        artifactProducedThisRun = $artifactProducedThisRun
        artifactHash = $artifactHash
        artifactBytes = $artifactBytes
        rawOutputStored = $false
        secretHits = $SecretHits
    })
}

function Invoke-ProcessCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [switch]$CompactLog,
        [string]$ArtifactPath = '',
        [string]$ArtifactLabel = ''
    )
    if (-not (Test-Path -LiteralPath $FilePath)) {
        $missing = "evidence_needed: missing $FilePath"
        Set-Content -LiteralPath $LogPath -Value $missing -Encoding UTF8
        return [pscustomobject]@{
            Name = $Name
            ExitCode = 127
            SecretHits = 0
            Output = $missing
            LogPath = $LogPath
        }
    }

    $artifactBeforeState = $null
    if ($CompactLog) {
        $artifactBeforePresent = -not [string]::IsNullOrWhiteSpace($ArtifactPath) -and
            (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)
        $artifactBeforeHash = ''
        $artifactBeforeBytes = 0L
        $artifactBeforeLastWriteTicks = 0L
        if ($artifactBeforePresent) {
            $artifactBeforeItem = Get-Item -LiteralPath $ArtifactPath
            $artifactBeforeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ArtifactPath).Hash.ToLowerInvariant()
            $artifactBeforeBytes = [int64]$artifactBeforeItem.Length
            $artifactBeforeLastWriteTicks = [int64]$artifactBeforeItem.LastWriteTimeUtc.Ticks
        }
        $artifactBeforeState = [pscustomobject]@{
            Present = $artifactBeforePresent
            Hash = $artifactBeforeHash
            Bytes = $artifactBeforeBytes
            LastWriteTimeUtcTicks = $artifactBeforeLastWriteTicks
        }
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        if ([System.IO.Path]::GetExtension($FilePath).Equals('.ps1', [System.StringComparison]::OrdinalIgnoreCase)) {
            $lines = & powershell -NoProfile -ExecutionPolicy Bypass -File $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        } else {
            $lines = & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
        }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $exit = $LASTEXITCODE
    $raw = $lines -join "`n"
    $secretHits = Count-SecretPatternHits -Text $raw
    $safe = Redact-SensitiveText $raw
    if ($CompactLog) {
        Write-CompactArtifactLog -Name $Name -LogPath $LogPath -ArtifactPath $ArtifactPath -ArtifactLabel $ArtifactLabel -ExitCode $exit -SecretHits $secretHits -ArtifactBeforeState $artifactBeforeState
    } else {
        Set-Content -LiteralPath $LogPath -Value $safe -Encoding UTF8
    }

    return [pscustomobject]@{
        Name = $Name
        ExitCode = $exit
        SecretHits = $secretHits
        Output = $safe
        LogPath = $LogPath
        LogCompacted = [bool]$CompactLog
    }
}

function Invoke-ProcessCaptureWithInput {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$FilePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [AllowEmptyString()][string]$InputText,
        [Parameter(Mandatory = $true)][string]$LogPath
    )
    if (-not (Test-Path -LiteralPath $FilePath)) {
        $missing = "evidence_needed: missing $FilePath"
        Set-Content -LiteralPath $LogPath -Value $missing -Encoding UTF8
        return [pscustomobject]@{
            Name = $Name
            ExitCode = 127
            SecretHits = 0
            Output = $missing
            LogPath = $LogPath
        }
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = $InputText | & $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() }
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $exit = $LASTEXITCODE
    $raw = $lines -join "`n"
    $secretHits = Count-SecretPatternHits -Text $raw
    $safe = Redact-SensitiveText $raw
    Set-Content -LiteralPath $LogPath -Value $safe -Encoding UTF8

    return [pscustomobject]@{
        Name = $Name
        ExitCode = $exit
        SecretHits = $secretHits
        Output = $safe
        LogPath = $LogPath
    }
}

function Invoke-PythonCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][string]$LogPath,
        [switch]$CompactLog,
        [string]$ArtifactPath = '',
        [string]$ArtifactLabel = ''
    )
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $python) {
        $missing = 'evidence_needed: python missing'
        Set-Content -LiteralPath $LogPath -Value $missing -Encoding UTF8
        return [pscustomobject]@{
            Name = $Name
            ExitCode = 127
            SecretHits = 0
            Output = $missing
            LogPath = $LogPath
        }
    }
    $pythonArgs = @($ScriptPath) + $Arguments
    return Invoke-ProcessCapture -Name $Name -FilePath $python.Source -Arguments $pythonArgs -LogPath $LogPath -CompactLog:$CompactLog -ArtifactPath $ArtifactPath -ArtifactLabel $ArtifactLabel
}

function Invoke-PythonStdinCapture {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ScriptPath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [AllowEmptyString()][string]$InputText,
        [Parameter(Mandatory = $true)][string]$LogPath
    )
    $python = Get-Command python -ErrorAction SilentlyContinue
    if ($null -eq $python) {
        $missing = 'evidence_needed: python missing'
        Set-Content -LiteralPath $LogPath -Value $missing -Encoding UTF8
        return [pscustomobject]@{
            Name = $Name
            ExitCode = 127
            SecretHits = 0
            Output = $missing
            LogPath = $LogPath
        }
    }
    $pythonArgs = @($ScriptPath) + $Arguments
    return Invoke-ProcessCaptureWithInput -Name $Name -FilePath $python.Source -Arguments $pythonArgs -InputText $InputText -LogPath $LogPath
}

function Get-LatestGoalNextDependency {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $candidatePaths = [System.Collections.Generic.List[string]]::new()
    $localScriptRoot = if (-not [string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        $PSScriptRoot
    } else {
        Split-Path -Parent $PSCommandPath
    }
    $rootScriptsDir = Join-Path $ProjectRoot 'scripts'
    foreach ($scriptDir in @($localScriptRoot, $rootScriptsDir)) {
        if ([string]::IsNullOrWhiteSpace($scriptDir)) {
            continue
        }
        foreach ($name in @(
            'goal_next.ps1',
            'goal_next_auto.ps1',
            'domain_public_https_preflight.ps1',
            'smoke_supabase_readonly_snapshot.ps1',
            'supabase_apply_collected_evidence.ps1',
            'external_apply_collected_evidence.ps1',
            'source_health_scorecard.py',
            'awx_mcp_toolbox.py',
            'awx_mcp_completion_audit.py',
            'smb_decommission_debug_probe.ps1'
        )) {
            $candidatePaths.Add((Join-Path $scriptDir $name)) | Out-Null
        }
    }
    $candidatePaths.Add((Join-Path (Join-Path $ProjectRoot 'var\codex-smoke') 'computer-use-smoke.json')) | Out-Null
    $candidatePaths.Add((Join-Path (Join-Path $ProjectRoot 'var\codex-smoke') 'browser-ui-smoke.json')) | Out-Null
    $candidatePaths.Add((Join-Path (Join-Path $ProjectRoot 'var\codex-smoke\smb-decommission-control-tower') 'smb-decommission-debug-probe.summary.json')) | Out-Null
    $candidatePaths.Add((Join-Path $ProjectRoot 'verification\chat-debug-fx-sse-trace-memory-required\chat-debug-fx-sse.json')) | Out-Null
    $candidatePaths.Add((Join-Path $ProjectRoot 'data\db-gap-report\gap_matrix.json')) | Out-Null

    $seenPaths = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
    $latest = $null
    foreach ($path in $candidatePaths) {
        if (-not $seenPaths.Add($path)) {
            continue
        }
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            continue
        }
        $item = Get-Item -LiteralPath $path
        if ($null -eq $latest -or $item.LastWriteTimeUtc -gt $latest.LastWriteTimeUtc) {
            $latest = $item
        }
    }

    if ($null -eq $latest) {
        return $null
    }

    return [pscustomobject]@{
        Name = $latest.Name
        LastWriteTimeUtc = $latest.LastWriteTimeUtc
        LastWriteTimeUtcText = $latest.LastWriteTimeUtc.ToString('o')
    }
}

# A repeated blocker is a scheduling decision, never a completion verdict.
# Status stays cheap; a requested audit hashes its actual source/resource inputs.
$blockerInputIdentity = ''
$blockerStatePath = ''
$blockerInputsChanged = $false
if (-not $Status) {
    $Root = Resolve-RepoRoot $Root
    $blockerStatePath = Join-Path $Root 'var\codex-smoke\goal-next-auto.blocker-state.json'
    $blockerInputIdentity = Get-GoalNextBlockerIdentity -ProjectRoot $Root -Mode $requestMode -RecoveryEvidencePath $RecoveryEvidencePath
    Write-Verbose "[AWX][goal-next-resume] inputIdentity=$blockerInputIdentity"
    $previousBlocker = Read-JsonObjectFromFile -Path $blockerStatePath
    if ($null -ne $previousBlocker -and [string]$previousBlocker.decision -eq 'evidence_needed' -and @($previousBlocker.reasons).Count -gt 0) {
        $fingerprint = Get-AwxBlockerFingerprint -Reasons @($previousBlocker.reasons) -Scopes @($previousBlocker.holdScope) -SourceIdentity $blockerInputIdentity -ResourceIdentity 'goal-next'
        $resume = Get-AwxBlockerResumeDecision -Fingerprint $fingerprint -Previous $previousBlocker -HasIndependentWork ([bool]$IndependentWorkAvailable)
        $blockerInputsChanged = $fingerprint -ne [string]$previousBlocker.fingerprint
        if ($resume.skipFullAudit) {
            $previousBlocker.unchangedCount = $resume.unchangedCount
            $previousBlocker.lastObservedAt = [DateTimeOffset]::UtcNow.ToString('o')
            Write-JsonFile -Path $blockerStatePath -Value $previousBlocker
            Write-Host "[AWX][goal-next-resume] decision=evidence_needed action=$($resume.action) skipFullAudit=true unchangedCount=$($resume.unchangedCount) repositoryWideHold=false fingerprint=$fingerprint"
            Write-Host "[AWX][goal-next-resume] nextObservation=$($resume.nextObservation) acceptance=not_observed"
            exit 2
        }
    }
}

if ($Status -or $EnsureFresh) {
    $Root = Resolve-RepoRoot $Root
    $defaultSmokeRoot = Resolve-RepoPath -ProjectRoot $Root -PathText 'var\codex-smoke'
    New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
    $latestPointerPath = Join-Path $defaultSmokeRoot 'goal-next-auto.latest.json'
    $statusPath = Join-Path $defaultSmokeRoot 'goal-next-auto.status.json'
    $nowUtc = (Get-Date).ToUniversalTime()
    $generatedAtText = $nowUtc.ToString('o')
    $statusDecision = 'evidence_needed'
    $failureClassification = 'missing-latest'
    $latestDecision = 'evidence_needed'
    $latestGeneratedAtText = ''
    $latestGeneratedAtAgeMinutes = $null
    $latestStaleAfterMinutes = 60
    $staleLatest = $true
    $firstAction = ''
    $firstActionSource = ''
    $statusNextActionCount = 0
    $statusNextActionSources = @()
    $statusTopActions = @()
    $secretHits = 0
    $computerUseOk = $false
    $computerUseReachable = $false
    $computerUseAppCount = 0
    $computerUseDecision = 'evidence_needed'
    $browserUseOk = $false
    $browserUseReachable = $false
    $browserUseLocalhost = $false
    $browserUsePublicDomain = $false
    $browserUseTargetAccepted = $false
    $browserUseTargetHost = 'unknown'
    $browserUseDecision = 'evidence_needed'
    $latestDependencyName = ''
    $latestDependencyWriteTime = ''
    $currentComputerUseSummary = Get-ComputerUseSmokeSummary -ProjectRoot $Root
    $computerUseSummary = $currentComputerUseSummary
    $currentBrowserUseSummary = Get-BrowserUseSmokeSummary -ProjectRoot $Root
    $browserUseSummary = $currentBrowserUseSummary
    $currentPeerEvidenceBusSummary = Get-PeerEvidenceBusSummary -ProjectRoot $Root
    $peerEvidenceBusSummary = $currentPeerEvidenceBusSummary
    $currentWebProbeRefreshSummary = $null
    $webProbeRefreshSummary = New-OptionalWebProbeRefreshContract
    $supabaseSmokeSummary = New-DefaultSupabaseSmokeSummary -ProjectRoot $Root
    $supabaseMcpConfig = Get-SupabaseMcpConfigSummary -ProjectRoot $Root
    $statusExternalInputGate = New-SafeExternalInputGateStatus -Gate $null -ProjectRoot $Root
    $latest = Read-JsonObjectFromFile -Path $latestPointerPath
    $latestRequestMode = [ordered]@{}
    $latestModeMatches = $false

    if ($null -ne $latest) {
        if ($null -ne $latest.requestMode) {
            $latestRequestMode = $latest.requestMode
            $latestModeFields = @($latestRequestMode.PSObject.Properties.Name)
            $latestModeMatches = (
                @('requireSupabaseProof', 'externalDispatch', 'refreshWebProbe', 'requireUiProof' | Where-Object { $_ -notin $latestModeFields }).Count -eq 0 -and
                [bool]$latestRequestMode.requireSupabaseProof -eq [bool]$requestMode.requireSupabaseProof -and
                [bool]$latestRequestMode.externalDispatch -eq [bool]$requestMode.externalDispatch -and
                [bool]$latestRequestMode.refreshWebProbe -eq [bool]$requestMode.refreshWebProbe -and
                [bool]$latestRequestMode.requireUiProof -eq [bool]$requestMode.requireUiProof
            )
        }
        $latestSummary = $null
        if (-not [string]::IsNullOrWhiteSpace([string]$latest.summaryPath)) {
            $latestSummary = Read-JsonObjectFromFile -Path ([string]$latest.summaryPath)
        }
        $latestDecision = if ([string]::IsNullOrWhiteSpace([string]$latest.decision)) { 'evidence_needed' } else { [string]$latest.decision }
        $statusDecision = $latestDecision
        $failureClassification = if ($latestDecision -eq 'ok') { '' } else { $latestDecision }
        $firstAction = Get-SafePacketString -Text ([string]$latest.firstAction) -ProjectRoot $Root
        $firstActionSource = Get-SafePacketString -Text ([string]$latest.firstActionSource) -ProjectRoot $Root
        if ($null -ne $latestSummary) {
            if ($null -ne $latestSummary.nextActionCount) { $statusNextActionCount = [int]$latestSummary.nextActionCount }
            if ($null -ne $latestSummary.nextActionSources) { $statusNextActionSources = @($latestSummary.nextActionSources) }
            if ($null -ne $latestSummary.topActions) { $statusTopActions = @($latestSummary.topActions) }
        } elseif ($null -ne $latest.nextActionEntryCount) {
            $statusNextActionCount = [int]$latest.nextActionEntryCount
        }
        if ($null -ne $latest.computerUseOk) { $computerUseOk = [bool]$latest.computerUseOk }
        if ($null -ne $latest.computerUseReachable) { $computerUseReachable = [bool]$latest.computerUseReachable }
        if ($null -ne $latest.computerUseAppCount) { $computerUseAppCount = [int]$latest.computerUseAppCount }
        if (-not [string]::IsNullOrWhiteSpace([string]$latest.computerUseDecision)) { $computerUseDecision = [string]$latest.computerUseDecision }
        if ($null -ne $latest.computerUse) { $computerUseSummary = $latest.computerUse }
        if ($null -ne $latest.browserUseOk) { $browserUseOk = [bool]$latest.browserUseOk }
        if ($null -ne $latest.browserUseReachable) { $browserUseReachable = [bool]$latest.browserUseReachable }
        if ($null -ne $latest.browserUseLocalhost) { $browserUseLocalhost = [bool]$latest.browserUseLocalhost }
        if ($null -ne $latest.browserUsePublicDomain) { $browserUsePublicDomain = [bool]$latest.browserUsePublicDomain }
        if ($null -ne $latest.browserUseTargetAccepted) { $browserUseTargetAccepted = [bool]$latest.browserUseTargetAccepted }
        if (-not [string]::IsNullOrWhiteSpace([string]$latest.browserUseTargetHost)) { $browserUseTargetHost = Get-SafePacketString -Text ([string]$latest.browserUseTargetHost) -ProjectRoot $Root }
        if (-not [string]::IsNullOrWhiteSpace([string]$latest.browserUseDecision)) { $browserUseDecision = [string]$latest.browserUseDecision }
        if ($null -ne $latest.browserUse) { $browserUseSummary = $latest.browserUse }
        if ($null -ne $latest.peerEvidenceBus) { $peerEvidenceBusSummary = $latest.peerEvidenceBus }
        if ($null -ne $latest.webProbeRefresh) { $webProbeRefreshSummary = $latest.webProbeRefresh }
        if ($null -ne $latest.supabaseMcpConfig) { $supabaseMcpConfig = $latest.supabaseMcpConfig }
        if ($null -ne $latest.externalInputGate) {
            $statusExternalInputGate = New-SafeExternalInputGateStatus -Gate $latest.externalInputGate -ProjectRoot $Root
        } elseif ($null -ne $latestSummary -and $null -ne $latestSummary.externalInputGate) {
            $statusExternalInputGate = New-SafeExternalInputGateStatus -Gate $latestSummary.externalInputGate -ProjectRoot $Root
        }
        if ($null -ne $latest.supabaseSmoke) {
            $supabaseSmokeSummary = $latest.supabaseSmoke
        } elseif ($null -ne $latestSummary) {
            if ($null -ne $latestSummary -and $null -ne $latestSummary.supabaseSmoke) {
                $supabaseSmokeSummary = $latestSummary.supabaseSmoke
            }
        }
        if ($null -ne $currentComputerUseSummary) {
            $computerUseOk = [bool]$currentComputerUseSummary.ok
            $computerUseReachable = [bool]$currentComputerUseSummary.reachable
            $computerUseAppCount = [int]$currentComputerUseSummary.appCount
            $computerUseDecision = [string]$currentComputerUseSummary.decision
            $computerUseSummary = $currentComputerUseSummary
            $secretHits += [int]$currentComputerUseSummary.secretHits
        }
        if ($null -ne $currentBrowserUseSummary) {
            $browserUseOk = [bool]$currentBrowserUseSummary.ok
            $browserUseReachable = [bool]$currentBrowserUseSummary.reachable
            $browserUseLocalhost = [bool]$currentBrowserUseSummary.localhost
            $browserUsePublicDomain = if ($null -ne $currentBrowserUseSummary.publicDomain) { [bool]$currentBrowserUseSummary.publicDomain } else { $false }
            $browserUseTargetAccepted = if ($null -ne $currentBrowserUseSummary.targetAccepted) { [bool]$currentBrowserUseSummary.targetAccepted } else { $browserUseLocalhost -or $browserUsePublicDomain }
            $browserUseTargetHost = if (-not [string]::IsNullOrWhiteSpace([string]$currentBrowserUseSummary.targetHost)) { Get-SafePacketString -Text ([string]$currentBrowserUseSummary.targetHost) -ProjectRoot $Root } else { 'unknown' }
            $browserUseDecision = [string]$currentBrowserUseSummary.decision
            $browserUseSummary = $currentBrowserUseSummary
            $secretHits += [int]$currentBrowserUseSummary.secretHits
        }
        if ($null -ne $currentPeerEvidenceBusSummary) {
            $peerEvidenceBusSummary = $currentPeerEvidenceBusSummary
            $secretHits += [int]$currentPeerEvidenceBusSummary.secretHits
        }
        $latestGeneratedAtText = [string]$latest.generatedAt
        [int]$parsedThreshold = 0
        if ([int]::TryParse([string]$latest.latestStaleAfterMinutes, [ref]$parsedThreshold) -and $parsedThreshold -gt 0) {
            $latestStaleAfterMinutes = $parsedThreshold
        }
        [int]$parsedSecretHits = 0
        if ([int]::TryParse([string]$latest.secretHits, [ref]$parsedSecretHits) -and $parsedSecretHits -gt 0) {
            $secretHits = $parsedSecretHits
        }
        try {
            $latestGeneratedAt = [datetimeoffset]::Parse($latestGeneratedAtText, [System.Globalization.CultureInfo]::InvariantCulture)
            $latestGeneratedAtAgeMinutes = [math]::Round(($nowUtc - $latestGeneratedAt.UtcDateTime).TotalMinutes, 3)
            $staleLatest = ($latestGeneratedAtAgeMinutes -gt $latestStaleAfterMinutes)
            if ($staleLatest) {
                $failureClassification = 'stale-latest'
            }
            $latestDependency = Get-LatestGoalNextDependency -ProjectRoot $Root
            if ($null -ne $latestDependency) {
                $latestDependencyName = [string]$latestDependency.Name
                $latestDependencyWriteTime = [string]$latestDependency.LastWriteTimeUtcText
                $dependencyFreshnessSkewSeconds = 2
                $dependencyIsOptionalUiSmoke = ([string]$latestDependency.Name -eq 'computer-use-smoke.json') -or ([string]$latestDependency.Name -eq 'browser-ui-smoke.json')
                if ($latestDependency.LastWriteTimeUtc -gt $latestGeneratedAt.UtcDateTime.AddSeconds($dependencyFreshnessSkewSeconds) -and ($RequireUiProof -or -not $dependencyIsOptionalUiSmoke)) {
                    $staleLatest = $true
                    if ($failureClassification -ne 'stale-latest') {
                        if ([string]$latestDependency.Name -eq 'computer-use-smoke.json') {
                            $failureClassification = 'computer-use-smoke-newer-than-latest'
                        } elseif ([string]$latestDependency.Name -eq 'browser-ui-smoke.json') {
                            $failureClassification = 'browser-ui-smoke-newer-than-latest'
                        } elseif ([string]$latestDependency.Name -eq 'chat-debug-fx-sse.json') {
                            $failureClassification = 'trace-memory-runtime-proof-newer-than-latest'
                        } elseif ([string]$latestDependency.Name -eq 'gap_matrix.json') {
                            $failureClassification = 'db-gap-report-newer-than-latest'
                        } else {
                            $failureClassification = 'script-newer-than-latest'
                        }
                    }
                }
            }
            if ($failureClassification -ne 'stale-latest' -and $null -ne $currentComputerUseSummary) {
                if ([int]$currentComputerUseSummary.secretHits -gt 0) {
                    $staleLatest = $true
                    $failureClassification = 'secret-leak-risk'
                    $statusDecision = 'secret-leak-risk'
                } elseif ($RequireUiProof -and [bool]$currentComputerUseSummary.stale) {
                    $staleLatest = $true
                    $failureClassification = 'computer-use-smoke-stale'
                    if ($statusDecision -eq 'ok') {
                        $statusDecision = 'evidence_needed'
                    }
                }
            }
            if ($failureClassification -ne 'stale-latest' -and $null -ne $currentBrowserUseSummary) {
                if ([int]$currentBrowserUseSummary.secretHits -gt 0) {
                    $staleLatest = $true
                    $failureClassification = 'secret-leak-risk'
                    $statusDecision = 'secret-leak-risk'
                } elseif ($RequireUiProof -and [bool]$currentBrowserUseSummary.stale) {
                    $staleLatest = $true
                    $failureClassification = 'browser-ui-smoke-stale'
                    if ($statusDecision -eq 'ok') {
                        $statusDecision = 'evidence_needed'
                    }
                }
            }
        } catch {
            $staleLatest = $true
            $failureClassification = 'invalid-latest-generated-at'
        }
    }
    if ($EnsureFresh -and $null -ne $latest -and -not $staleLatest -and -not $latestModeMatches) {
        $staleLatest = $true
        $failureClassification = 'latest-mode-mismatch'
    }

    $desktopStatusLocalReady = $false
    if ($null -ne $latestSummary -and $null -ne $latestSummary.desktopControlLoop) {
        $desktopStatusLocalReady = Get-SafeBoolValue -Value $latestSummary.desktopControlLoop.localReady
    } elseif ($null -ne $latest -and $null -ne $latest.desktopControlLoop) {
        $desktopStatusLocalReady = Get-SafeBoolValue -Value $latest.desktopControlLoop.localReady
    }
    $statusHasNoPrimaryAction =
        [string]::IsNullOrWhiteSpace([string]$firstAction) -and
        [string]::IsNullOrWhiteSpace([string]$firstActionSource) -and
        [int]$statusNextActionCount -eq 0
    $statusGateIsDesktopLocal =
        [string]$statusExternalInputGate.status -eq 'local_or_unknown' -and
        [string]::IsNullOrWhiteSpace([string]$statusExternalInputGate.source) -and
        [string]::IsNullOrWhiteSpace([string]$statusExternalInputGate.action) -and
        (Get-SafeBoolValue -Value $statusExternalInputGate.localPatchJustified)
    $statusComputerProofReady =
        (Get-SafeBoolValue -Value $computerUseSummary.ok) -or
        ((-not $RequireUiProof) -and (Get-SafeBoolValue -Value $computerUseSummary.safePendingProof))
    $statusBrowserProofReady =
        (Get-SafeBoolValue -Value $browserUseSummary.ok) -or
        ((-not $RequireUiProof) -and (Get-SafeBoolValue -Value $browserUseSummary.safePendingProof))
    $statusLocalProofReady =
        $desktopStatusLocalReady -and
        $statusComputerProofReady -and
        $statusBrowserProofReady
    if (-not $RequireSupabaseProof -and -not $ExternalDispatch -and -not $RequireUiProof -and
            $statusDecision -eq 'evidence_needed' -and -not $staleLatest -and $secretHits -eq 0 -and
            $statusHasNoPrimaryAction -and $statusGateIsDesktopLocal -and $statusLocalProofReady) {
        $statusDecision = 'desktop_only_ready'
        $failureClassification = ''
    }

    Write-JsonFile -Path $statusPath -Value ([ordered]@{
        schemaVersion = 'awx.goal_next_auto.status.v1'
        generatedAt = $generatedAtText
        root = $Root
        latestPath = $latestPointerPath
        statusPath = $statusPath
        latestDecision = $latestDecision
        statusDecision = $statusDecision
        failureClassification = $failureClassification
        latestGeneratedAt = $latestGeneratedAtText
        latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
        latestStaleAfterMinutes = $latestStaleAfterMinutes
        staleLatest = $staleLatest
        requestMode = $requestMode
        latestRequestMode = $latestRequestMode
        latestModeMatches = $latestModeMatches
        uiProofRequired = [bool]$RequireUiProof
        webProbeRefreshRequested = [bool]$RefreshWebProbe
        summaryPath = if ($null -ne $latest) { [string]$latest.summaryPath } else { '' }
        nextActionsPath = if ($null -ne $latest) { [string]$latest.nextActionsPath } else { '' }
        commandPacketPath = if ($null -ne $latest) { [string]$latest.commandPacketPath } else { '' }
        commandPacketMarkdownPath = if ($null -ne $latest) { [string]$latest.commandPacketMarkdownPath } else { '' }
        collectionPacketPath = if ($null -ne $latest) { [string]$latest.collectionPacketPath } else { '' }
        collectionPacketMarkdownPath = if ($null -ne $latest) { [string]$latest.collectionPacketMarkdownPath } else { '' }
        digestPath = if ($null -ne $latest) { [string]$latest.digestPath } else { '' }
        digestMarkdownPath = if ($null -ne $latest) { [string]$latest.digestMarkdownPath } else { '' }
        nextActionEntryCount = if ($null -ne $latest) { [int]$latest.nextActionEntryCount } else { 0 }
        nextActionCount = $statusNextActionCount
        nextActionSources = @($statusNextActionSources)
        topActions = @($statusTopActions)
        firstAction = $firstAction
        firstActionSource = $firstActionSource
        computerUseOk = $computerUseOk
        computerUseReachable = $computerUseReachable
        computerUseAppCount = $computerUseAppCount
        computerUseDecision = $computerUseDecision
        computerUse = $computerUseSummary
        browserUseOk = $browserUseOk
        browserUseReachable = $browserUseReachable
        browserUseLocalhost = $browserUseLocalhost
        browserUsePublicDomain = $browserUsePublicDomain
        browserUseTargetAccepted = $browserUseTargetAccepted
        browserUseTargetHost = $browserUseTargetHost
        browserUseDecision = $browserUseDecision
        browserUse = $browserUseSummary
        peerEvidenceBus = $peerEvidenceBusSummary
        webProbeRefresh = $webProbeRefreshSummary
        traceMemoryRuntimeProof = if ($null -ne $latest -and $null -ne $latest.traceMemoryRuntimeProof) { $latest.traceMemoryRuntimeProof } else { [ordered]@{} }
        supabaseSmoke = $supabaseSmokeSummary
        supabaseMcpConfig = $supabaseMcpConfig
        externalInputGate = $statusExternalInputGate
        supabaseApply = if ($null -ne $latest -and $null -ne $latest.supabaseApply) { $latest.supabaseApply } else { [ordered]@{} }
        externalApply = if ($null -ne $latest -and $null -ne $latest.externalApply) { $latest.externalApply } else { [ordered]@{} }
        latestDependencyName = $latestDependencyName
        latestDependencyWriteTime = $latestDependencyWriteTime
        secretHits = $secretHits
    })

    $ageText = if ($null -ne $latestGeneratedAtAgeMinutes) { [string]$latestGeneratedAtAgeMinutes } else { 'evidence_needed' }
    $failureText = if ([string]::IsNullOrWhiteSpace($failureClassification)) { 'none' } else { $failureClassification }
    Write-Host "[AWX][goal-next-status] statusDecision=$statusDecision staleLatest=$($staleLatest.ToString().ToLowerInvariant()) latestGeneratedAtAgeMinutes=$ageText firstAction=$firstAction failureClassification=$failureText secretHits=$secretHits externalInputGateStatus=$($statusExternalInputGate.status) externalInputGateAction=$($statusExternalInputGate.action) externalInputGateSecretHits=$($statusExternalInputGate.secretHits) externalInputGateWindowsAbsPathHits=$($statusExternalInputGate.windowsAbsPathHits) status=$statusPath"

    if ($EnsureFresh) {
        if ($secretHits -gt 0 -or $latestDecision -eq 'secret-leak-risk') {
            Write-Host "[AWX][goal-next-ensure] action=stop reason=secret-leak-risk status=$statusPath"
            exit 4
        }
        if (-not $staleLatest -and -not $RefreshWebProbe -and -not $blockerInputsChanged) {
            Write-Host "[AWX][goal-next-ensure] action=status reason=fresh-latest statusDecision=$statusDecision status=$statusPath"
            if ($statusDecision -ne 'ok' -and $statusDecision -ne 'desktop_only_ready') {
                exit 2
            }
            exit 0
        }
        $refreshReason = if ($RefreshWebProbe) { 'web-probe-refresh-requested' } elseif ($staleLatest) { $failureText } elseif ($blockerInputsChanged) { 'blocker-inputs-changed' } else { $failureText }
        Write-Host "[AWX][goal-next-ensure] action=refresh reason=$refreshReason outputDir=$OutputDir"
    } else {
    if ($secretHits -gt 0 -or $latestDecision -eq 'secret-leak-risk') {
        exit 4
    }
    if ($staleLatest -or ($statusDecision -ne 'ok' -and $statusDecision -ne 'desktop_only_ready')) {
        exit 2
    }
    exit 0
    }
}

$Root = Resolve-RepoRoot $Root
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = 'var\codex-smoke\goal-next-auto'
}
$defaultSmokeRoot = Resolve-RepoPath -ProjectRoot $Root -PathText 'var\codex-smoke'
$completionAuditCanonicalPath = Join-Path $defaultSmokeRoot 'awx-mcp-completion-audit-current.json'
$OutputDir = Resolve-RepoPath -ProjectRoot $Root -PathText $OutputDir
New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
$breadcrumbTimelinePath = Join-Path $defaultSmokeRoot 'goal-next-auto.breadcrumbs.jsonl'
$breadcrumbFusionPath = Join-Path $OutputDir 'goal-next-auto.breadcrumb-fusion.json'
$previousBreadcrumb = Read-LastJsonLine -Path $breadcrumbTimelinePath
$previousBreadcrumbSummary = New-PreviousBreadcrumbSummary -Previous $previousBreadcrumb
$preflight = Get-DesktopPreflight -ProjectRoot $Root

$scriptsDir = Join-Path $Root 'scripts'
$supabaseSmokeDir = Join-Path $OutputDir 'supabase-readonly-smoke'
$supabaseApplyDir = Join-Path $OutputDir 'supabase-apply'
$externalApplyDir = Join-Path $OutputDir 'external-apply'
$desktopControlLoopPath = Join-Path $OutputDir 'desktop-control-loop.result.json'
$peerEvidenceBusPath = Join-Path $defaultSmokeRoot 'peer-evidence-bus.json'

$supabaseSmokeSummaryPath = Join-Path $supabaseSmokeDir 'supabase-readonly-snapshot.summary.json'
$supabaseSmokeLogPath = Join-Path $OutputDir 'supabase-smoke.log'
if ($RequireSupabaseProof) {
    $supabaseSmoke = Invoke-ProcessCapture `
        -Name 'supabase_smoke' `
        -FilePath (Join-Path $scriptsDir 'smoke_supabase_readonly_snapshot.ps1') `
        -Arguments @('-Root', $Root, '-OutputDir', $supabaseSmokeDir, '-RequireProjectScope') `
        -LogPath $supabaseSmokeLogPath
} else {
    New-Item -ItemType Directory -Force -Path $supabaseSmokeDir | Out-Null
    Write-JsonFile -Path $supabaseSmokeSummaryPath -Value (New-OptionalSupabaseSmokeContract)
    $supabaseSmoke = New-SkippedProcessCapture -Name 'supabase_smoke' -LogPath $supabaseSmokeLogPath -Reason 'supabase-proof-not-requested'
}
$supabaseSmokeSummary = Get-SupabaseSmokeSummary -SummaryPath $supabaseSmokeSummaryPath -ProjectRoot $Root
$defaultSupabaseSmokeDir = Join-Path $defaultSmokeRoot 'supabase-readonly-snapshot'
if ($RequireSupabaseProof -and (Test-Path -LiteralPath $supabaseSmokeDir -PathType Container)) {
    New-Item -ItemType Directory -Force -Path $defaultSupabaseSmokeDir | Out-Null
    Get-ChildItem -LiteralPath $supabaseSmokeDir -File -ErrorAction SilentlyContinue | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $defaultSupabaseSmokeDir $_.Name) -Force
    }
}

$supabaseApplySummaryPath = Join-Path $supabaseApplyDir 'supabase-apply-collected.summary.json'
$supabaseApplyLogPath = Join-Path $OutputDir 'supabase-apply.log'
if ($RequireSupabaseProof) {
    $supabaseApply = Invoke-ProcessCapture `
        -Name 'supabase_apply' `
        -FilePath (Join-Path $scriptsDir 'supabase_apply_collected_evidence.ps1') `
        -Arguments @('-Root', $Root, '-OutputDir', $supabaseApplyDir, '-OrchestratedByGoalNext') `
        -LogPath $supabaseApplyLogPath
} else {
    New-Item -ItemType Directory -Force -Path $supabaseApplyDir | Out-Null
    Write-JsonFile -Path $supabaseApplySummaryPath -Value (New-OptionalSupabaseApplyContract)
    $supabaseApply = New-SkippedProcessCapture -Name 'supabase_apply' -LogPath $supabaseApplyLogPath -Reason 'supabase-proof-not-requested'
}
$supabaseApplySummary = Read-JsonObjectFromFile -Path $supabaseApplySummaryPath
$supabaseExternalInputGate = if ($RequireSupabaseProof) {
    New-SupabaseExternalInputGate -SupabaseApplySummary $supabaseApplySummary -ProjectRoot $Root
} else {
    $null
}

$externalApplySummaryPath = Join-Path $externalApplyDir 'external-apply-collected.summary.json'
$externalApplyLogPath = Join-Path $OutputDir 'external-apply.log'
if ($ExternalDispatch) {
    $externalApplyArgs = @('-Root', $Root, '-OutputDir', $externalApplyDir, '-Topic', $Topic, '-OrchestratedByGoalNext')
    $externalApply = Invoke-ProcessCapture `
        -Name 'external_apply' `
        -FilePath (Join-Path $scriptsDir 'external_apply_collected_evidence.ps1') `
        -Arguments $externalApplyArgs `
        -LogPath $externalApplyLogPath
} else {
    New-Item -ItemType Directory -Force -Path $externalApplyDir | Out-Null
    Write-JsonFile -Path $externalApplySummaryPath -Value (New-OptionalExternalApplyContract -Topic $Topic)
    $externalApply = New-SkippedProcessCapture -Name 'external_apply' -LogPath $externalApplyLogPath -Reason 'external-dispatch-not-requested'
}
$externalApplySummary = Read-JsonObjectFromFile -Path $externalApplySummaryPath

$desktopControlLoopRequestsDispatch = [bool]$ExternalDispatch
$desktopControlLoopRequestsProducerKit = [bool]$ExternalDispatch
$desktopControlLoopPayloadObject = [ordered]@{
    nodeRole = 'desktop'
    root = $Root
    canonical_root = $Root
    patchdrop_root = '__patch_drop__'
    topic = $Topic
    require_producer_bundles = [bool]$ExternalDispatch
    require_supabase_live_proof = [bool]$RequireSupabaseProof
    run_completion_audit = [bool]($ExternalDispatch -or $RequireSupabaseProof)
    write_dispatch = $desktopControlLoopRequestsDispatch
    write_producer_kit = $desktopControlLoopRequestsProducerKit
}
if ($ExternalDispatch.IsPresent) {
    $desktopControlLoopPayloadObject['role_pathspec'] = [ordered]@{
        macmini = @('scripts/goal_next_auto.ps1')
        notebook = @('scripts/goal_next_auto_tests.ps1')
    }
}
$desktopControlLoopPayload = ($desktopControlLoopPayloadObject | ConvertTo-Json -Depth 20 -Compress)

$desktopControlLoop = Invoke-PythonStdinCapture `
    -Name 'desktop_control_loop' `
    -ScriptPath (Join-Path $scriptsDir 'awx_mcp_toolbox.py') `
    -Arguments @('desktop_control_loop') `
    -InputText $desktopControlLoopPayload `
    -LogPath (Join-Path $OutputDir 'desktop-control-loop.log')

$desktopControlLoopJson = Read-JsonObjectFromText $desktopControlLoop.Output
if ($null -ne $desktopControlLoopJson) {
    $desktopControlLoopCanonical = $desktopControlLoopJson | ConvertTo-Json -Depth 100 -Compress
    $desktopControlLoopDecodedSecretHits = Count-SecretPatternHits -Text $desktopControlLoopCanonical
    $desktopControlLoopSafeCanonical = Redact-SensitiveText $desktopControlLoopCanonical
    $desktopControlLoop.SecretHits = [int]$desktopControlLoop.SecretHits + $desktopControlLoopDecodedSecretHits
    $desktopControlLoop.Output = $desktopControlLoopSafeCanonical
    Set-Content -LiteralPath $desktopControlLoopPath -Value $desktopControlLoopSafeCanonical -Encoding UTF8
    Write-CompactArtifactLog -Name 'desktop_control_loop' -LogPath $desktopControlLoop.LogPath -ArtifactPath $desktopControlLoopPath -ArtifactLabel 'desktop-control-loop.result.json' -ExitCode $desktopControlLoop.ExitCode -SecretHits $desktopControlLoop.SecretHits
    $desktopControlLoopJson = Read-JsonObjectFromText $desktopControlLoopSafeCanonical
} else {
    Set-Content -LiteralPath $desktopControlLoopPath -Value $desktopControlLoop.Output -Encoding UTF8
}
$desktopControlLoopNextActionCount = 0
if ($null -ne $desktopControlLoopJson -and $null -ne $desktopControlLoopJson.nextActions) {
    $desktopControlLoopNextActionCount = @($desktopControlLoopJson.nextActions).Count
}
$desktopDispatchArtifactCount = 0
if ($null -ne $desktopControlLoopJson `
        -and $null -ne $desktopControlLoopJson.dispatch `
        -and $null -ne $desktopControlLoopJson.dispatch.dispatchArtifacts) {
    $desktopDispatchArtifactCount = @($desktopControlLoopJson.dispatch.dispatchArtifacts).Count
}
$desktopDispatchIntegrityOk = $false
if ($null -ne $desktopControlLoopJson `
        -and $null -ne $desktopControlLoopJson.dispatchIntegrity `
        -and $null -ne $desktopControlLoopJson.dispatchIntegrity.ok) {
    $desktopDispatchIntegrityOk = [bool]$desktopControlLoopJson.dispatchIntegrity.ok
}
$desktopDispatchFailReason = ''
if ($null -ne $desktopControlLoopJson `
        -and $null -ne $desktopControlLoopJson.dispatchIntegrity `
        -and $null -ne $desktopControlLoopJson.dispatchIntegrity.failReason) {
    $desktopDispatchFailReason = Get-SafePacketString -Text ([string]$desktopControlLoopJson.dispatchIntegrity.failReason) -ProjectRoot $Root
}
$desktopProducerKitOk = $false
if ($null -ne $desktopControlLoopJson `
        -and $null -ne $desktopControlLoopJson.producerKit `
        -and $null -ne $desktopControlLoopJson.producerKit.ok) {
    $desktopProducerKitOk = [bool]$desktopControlLoopJson.producerKit.ok
}
$desktopDispatchSummary = New-SafeDesktopDispatchSummary `
    -DesktopControlLoopJson $desktopControlLoopJson `
    -ProjectRoot $Root `
    -Topic $Topic `
    -DispatchWriteRequested $desktopControlLoopRequestsDispatch `
    -ProducerKitWriteRequested $desktopControlLoopRequestsProducerKit `
    -ProducerKitOk $desktopProducerKitOk `
    -DispatchArtifactCount $desktopDispatchArtifactCount `
    -DispatchIntegrityOk $desktopDispatchIntegrityOk `
    -DispatchFailReason $desktopDispatchFailReason

$peerEvidenceBusLogPath = Join-Path $OutputDir 'peer-evidence-bus.log'
$peerEvidenceBus = $null
$embeddedPeerEvidenceBus = if ($null -ne $desktopControlLoopJson) { $desktopControlLoopJson.peerEvidenceBus } else { $null }
if ([int]$desktopControlLoop.SecretHits -eq 0 -and (Test-PeerEvidenceBusReuseCandidate -Artifact $embeddedPeerEvidenceBus -ProjectRoot $Root)) {
    $embeddedPeerEvidenceBusOutput = $embeddedPeerEvidenceBus | ConvertTo-Json -Depth 100 -Compress
    Set-Content -LiteralPath $peerEvidenceBusPath -Value $embeddedPeerEvidenceBusOutput -Encoding UTF8
    Set-Content -LiteralPath $peerEvidenceBusLogPath -Value '[AWX][peer-evidence-bus] source=desktop_control_loop_embedded' -Encoding UTF8
    $peerEvidenceBus = [pscustomobject]@{
        Name = 'peer_evidence_bus'
        ExitCode = 0
        SecretHits = 0
        Output = $embeddedPeerEvidenceBusOutput
        LogPath = $peerEvidenceBusLogPath
    }
}
if ($null -eq $peerEvidenceBus) {
    $peerEvidenceBusPayload = ([ordered]@{
        nodeRole = 'desktop'
        root = $Root
        targetMetric = 'harmony'
    } | ConvertTo-Json -Depth 10 -Compress)
    $peerEvidenceBus = Invoke-PythonStdinCapture `
        -Name 'peer_evidence_bus' `
        -ScriptPath (Join-Path $scriptsDir 'awx_mcp_toolbox.py') `
        -Arguments @('peer_evidence_bus') `
        -InputText $peerEvidenceBusPayload `
        -LogPath $peerEvidenceBusLogPath
    Set-Content -LiteralPath $peerEvidenceBusPath -Value $peerEvidenceBus.Output -Encoding UTF8
}

$webProbeRefreshPath = Join-Path $defaultSmokeRoot 'web-probe-refresh.json'
$webProbeRefreshLogPath = Join-Path $OutputDir 'web-probe-refresh.log'
$webProbeRefreshBeforePresent = $false
$webProbeRefreshBeforeHash = ''
$webProbeRefreshBeforeBytes = 0L
$webProbeRefreshBeforeLastWriteTicks = 0L
if ($RefreshWebProbe -and (Test-Path -LiteralPath $webProbeRefreshPath -PathType Leaf)) {
    $webProbeRefreshBeforeItem = Get-Item -LiteralPath $webProbeRefreshPath
    $webProbeRefreshBeforePresent = $true
    $webProbeRefreshBeforeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $webProbeRefreshPath).Hash.ToLowerInvariant()
    $webProbeRefreshBeforeBytes = [int64]$webProbeRefreshBeforeItem.Length
    $webProbeRefreshBeforeLastWriteTicks = [int64]$webProbeRefreshBeforeItem.LastWriteTimeUtc.Ticks
}
if ($RefreshWebProbe) {
    $webProbeRefreshPayload = ([ordered]@{
        nodeRole = 'desktop'
        root = $Root
        targetMetric = 'harmony'
        output_path = 'var/codex-smoke/web-probe-refresh.json'
    } | ConvertTo-Json -Depth 10 -Compress)
    $webProbeRefresh = Invoke-PythonStdinCapture `
        -Name 'web_probe_refresh' `
        -ScriptPath (Join-Path $scriptsDir 'awx_mcp_toolbox.py') `
        -Arguments @('web_probe_refresh') `
        -InputText $webProbeRefreshPayload `
        -LogPath $webProbeRefreshLogPath
    if (
        $webProbeRefresh.ExitCode -eq 0 -and
        -not (Test-Path -LiteralPath $webProbeRefreshPath) -and
        -not [string]::IsNullOrWhiteSpace($webProbeRefresh.Output) -and
        $webProbeRefresh.Output.TrimStart().StartsWith('{')
    ) {
        Set-Content -LiteralPath $webProbeRefreshPath -Value $webProbeRefresh.Output -Encoding UTF8
    }
} else {
    $webProbeRefresh = New-SkippedProcessCapture -Name 'web_probe_refresh' -LogPath $webProbeRefreshLogPath -Reason 'web-probe-refresh-not-requested'
}
$webProbeRefreshArtifactProducedThisRun = $false
if ($RefreshWebProbe -and (Test-Path -LiteralPath $webProbeRefreshPath -PathType Leaf)) {
    $webProbeRefreshAfterItem = Get-Item -LiteralPath $webProbeRefreshPath
    $webProbeRefreshAfterHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $webProbeRefreshPath).Hash.ToLowerInvariant()
    $webProbeRefreshArtifactProducedThisRun = (
        -not $webProbeRefreshBeforePresent -or
        $webProbeRefreshBeforeHash -ne $webProbeRefreshAfterHash -or
        $webProbeRefreshBeforeBytes -ne [int64]$webProbeRefreshAfterItem.Length -or
        $webProbeRefreshBeforeLastWriteTicks -ne [int64]$webProbeRefreshAfterItem.LastWriteTimeUtc.Ticks)
}

$completionAuditPreflightOutputPath = Join-Path $OutputDir 'awx-mcp-completion-audit.preflight.result.json'
$completionAuditPreflightArgs = @('--root', $Root, '--output', $completionAuditPreflightOutputPath)
if ($RequireSupabaseProof) {
    $completionAuditPreflightArgs += '--require-supabase-proof'
}
$completionAuditBootstrap = Get-CompletionAuditBootstrapDecision -ProjectRoot $Root
if ($completionAuditBootstrap.preflightRequired) {
    $completionAuditPreflight = Invoke-PythonCapture `
        -Name 'completion_audit_preflight' `
        -ScriptPath (Join-Path $scriptsDir 'awx_mcp_completion_audit.py') `
        -Arguments $completionAuditPreflightArgs `
        -LogPath (Join-Path $OutputDir 'awx-mcp-completion-audit.preflight.log') `
        -CompactLog `
        -ArtifactPath $completionAuditPreflightOutputPath `
        -ArtifactLabel 'awx-mcp-completion-audit.preflight.result.json'
    $completionAuditBootstrap['preflightExecuted'] = $true
    if (Test-Path -LiteralPath $completionAuditPreflightOutputPath) {
        New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
        Copy-Item -LiteralPath $completionAuditPreflightOutputPath -Destination $completionAuditCanonicalPath -Force
    }
} else {
    $completionAuditPreflight = [pscustomobject]@{
        Name = 'completion_audit_preflight'
        ExitCode = 0
        SecretHits = 0
        Output = '[AWX][goal-next] lane=completion_audit_preflight process=skipped reason=fresh-safe-canonical-reuse'
        LogPath = ''
        ProcessExecuted = $false
        EvidenceSource = 'canonical_current_safe'
    }
}

$sourceHealth = Invoke-PythonCapture `
    -Name 'source_health' `
    -ScriptPath (Join-Path $scriptsDir 'source_health_scorecard.py') `
    -Arguments @('--root', $Root, '--output', (Join-Path $OutputDir 'source-health-scorecard.json')) `
    -LogPath (Join-Path $OutputDir 'source-health-scorecard.log')
$sourceHealthJson = Read-JsonObjectFromFile -Path (Join-Path $OutputDir 'source-health-scorecard.json')
$sourceHealthProducerQueue = New-SafeSourceHealthProducerQueue -SourceHealthJson $sourceHealthJson -ProjectRoot $Root
$sourceHealthDispatchOverlay = Write-SourceHealthProducerDispatchOverlay -ProjectRoot $Root -Topic $Topic -DesktopDispatchSummary $desktopDispatchSummary -SourceHealthProducerQueue $sourceHealthProducerQueue

$runs = @($supabaseSmoke, $supabaseApply, $externalApply, $desktopControlLoop, $peerEvidenceBus, $webProbeRefresh, $sourceHealth)
$secretHits = 0
foreach ($run in $runs) {
    $secretHits += [int]$run.SecretHits
}
$secretHits += [int]$completionAuditPreflight.SecretHits
$secretHits += [int]$supabaseSmokeSummary.secretHits

$envProjectRefPresent = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('SUPABASE_PROJECT_REF'))
$envAccessTokenPresent = -not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable('SUPABASE_ACCESS_TOKEN'))
$computerUseSummary = Get-ComputerUseSmokeSummary -ProjectRoot $Root
$secretHits += [int]$computerUseSummary.secretHits
$browserUseSummary = Get-BrowserUseSmokeSummary -ProjectRoot $Root
$secretHits += [int]$browserUseSummary.secretHits
$peerEvidenceBusSummary = Get-PeerEvidenceBusSummary -ProjectRoot $Root
$secretHits += [int]$peerEvidenceBusSummary.secretHits
$webProbeRefreshSummary = if ($RefreshWebProbe) {
    Get-WebProbeRefreshSummary -ProjectRoot $Root
} else {
    New-OptionalWebProbeRefreshContract
}
$webProbeRefreshSummary['refreshRequested'] = [bool]$RefreshWebProbe
$webProbeRefreshSummary['processExecuted'] = [bool]$RefreshWebProbe
$webProbeRefreshSummary['contractReady'] = $true
$webProbeRefreshSummary['processExitCode'] = [int]$webProbeRefresh.ExitCode
$webProbeRefreshSummary['artifactProducedThisRun'] = [bool]$webProbeRefreshArtifactProducedThisRun
$webProbeRefreshSummary['proofReady'] = (
    [bool]$RefreshWebProbe -and
    [int]$webProbeRefresh.ExitCode -eq 0 -and
    [bool]$webProbeRefreshArtifactProducedThisRun -and
    [bool]$webProbeRefreshSummary.ok -and
    [bool]$webProbeRefreshSummary.fresh)
if ($RefreshWebProbe -and -not [bool]$webProbeRefreshSummary.proofReady) {
    $webProbeRefreshSummary['ok'] = $false
    $webProbeRefreshSummary['decision'] = 'evidence_needed'
    if ([string]::IsNullOrWhiteSpace([string]$webProbeRefreshSummary.nextAction)) {
        $webProbeRefreshSummary['nextAction'] = 'run_web_probe_refresh'
    }
}
$webProbeRefreshSummary['supportingEvidenceOnly'] = $true
$webProbeRefreshSummary['executionMode'] = if ($RefreshWebProbe) { 'explicit-refresh' } else { 'manual-opt-in' }
$secretHits += [int]$webProbeRefreshSummary.secretHits

$decision = 'ok'
if ($secretHits -gt 0 -or $runs.ExitCode -contains 4 -or $completionAuditPreflight.ExitCode -eq 4) {
    $decision = 'secret-leak-risk'
} elseif (-not [string]::IsNullOrWhiteSpace([string]$preflight.failureClassification)) {
    $decision = 'evidence_needed'
} elseif ($RequireUiProof -and ([string]$computerUseSummary.decision -ne 'ok' -or [string]$browserUseSummary.decision -ne 'ok')) {
    $decision = 'evidence_needed'
} elseif ($RefreshWebProbe -and [string]$webProbeRefreshSummary.decision -ne 'ok') {
    $decision = 'evidence_needed'
} elseif (($supabaseApply.ExitCode -ne 0) -or ($externalApply.ExitCode -ne 0) -or ($desktopControlLoop.ExitCode -ne 0) -or ($webProbeRefresh.ExitCode -ne 0) -or ($sourceHealth.ExitCode -ne 0)) {
    $decision = 'evidence_needed'
}

$nextActionsPath = Join-Path $OutputDir 'goal-next-auto.next-actions.json'
$nextActionEntries = [System.Collections.Generic.List[object]]::new()
if (-not [string]::IsNullOrWhiteSpace([string]$preflight.failureClassification)) {
    $nextActionEntries.Add([ordered]@{
        source = 'preflight'
        action = 'resolve_' + [string]$preflight.failureClassification
        decision = 'evidence_needed'
        failureClassification = [string]$preflight.failureClassification
    }) | Out-Null
}
$browserUseActionPrioritized = $false
if ($RequireUiProof `
        -and [string]$browserUseSummary.decision -ne 'ok' `
        -and -not [string]::IsNullOrWhiteSpace([string]$browserUseSummary.nextAction) `
        -and $browserUseSummary.publicDomain -eq $true) {
    $nextActionEntries.Add([ordered]@{
        source = 'browser_use'
        action = [string]$browserUseSummary.nextAction
        decision = [string]$browserUseSummary.decision
    }) | Out-Null
    $browserUseActionPrioritized = $true
}
if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.nextActions) {
    if ($RequireSupabaseProof) {
        Add-StringNextActionEntries -Entries $nextActionEntries -Source 'supabase_apply' -Actions $supabaseApplySummary.nextActions
    }
}
if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.nextActions) {
    Add-StringNextActionEntries -Entries $nextActionEntries -Source 'external_apply' -Actions $externalApplySummary.nextActions
}
if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.nextActionDetails) {
    $sourceHealthPrimaryNextActionDetails = @($sourceHealthJson.nextActionDetails | Where-Object {
        Test-SourceHealthPrimaryNextAction -Action $_ -ExternalDispatchRequested ([bool]$ExternalDispatch) -SupabaseProofRequested ([bool]$RequireSupabaseProof)
    })
    Add-ObjectNextActionEntries -Entries $nextActionEntries -Source 'source_health_scorecard' -Actions $sourceHealthPrimaryNextActionDetails
}
if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.nextSourceActionDetails) {
    $sourceHealthPrimaryNextSourceActionDetails = @($sourceHealthJson.nextSourceActionDetails | Where-Object {
        Test-SourceHealthPrimaryNextAction -Action $_ -ExternalDispatchRequested ([bool]$ExternalDispatch) -SupabaseProofRequested ([bool]$RequireSupabaseProof)
    })
    Add-ObjectNextActionEntries -Entries $nextActionEntries -Source 'source_health_scorecard' -Actions $sourceHealthPrimaryNextSourceActionDetails
}
if ($null -ne $desktopControlLoopJson -and $null -ne $desktopControlLoopJson.nextActions) {
    Add-ObjectNextActionEntries -Entries $nextActionEntries -Source 'desktop_control_loop' -Actions $desktopControlLoopJson.nextActions
}
if ($RequireUiProof -and [string]$computerUseSummary.decision -ne 'ok' -and -not [string]::IsNullOrWhiteSpace([string]$computerUseSummary.nextAction)) {
    $nextActionEntries.Add([ordered]@{
        source = 'computer_use'
        action = [string]$computerUseSummary.nextAction
        decision = [string]$computerUseSummary.decision
    }) | Out-Null
}
if ($RequireUiProof -and -not $browserUseActionPrioritized -and [string]$browserUseSummary.decision -ne 'ok' -and -not [string]::IsNullOrWhiteSpace([string]$browserUseSummary.nextAction)) {
    $nextActionEntries.Add([ordered]@{
        source = 'browser_use'
        action = [string]$browserUseSummary.nextAction
        decision = [string]$browserUseSummary.decision
    }) | Out-Null
}
if ([string]$peerEvidenceBusSummary.decision -ne 'ok' -and -not [string]::IsNullOrWhiteSpace([string]$peerEvidenceBusSummary.nextAction)) {
    $nextActionEntries.Add([ordered]@{
        source = 'peer_evidence_bus'
        action = [string]$peerEvidenceBusSummary.nextAction
        decision = [string]$peerEvidenceBusSummary.decision
    }) | Out-Null
}
if ($RefreshWebProbe -and [string]$webProbeRefreshSummary.decision -ne 'ok' -and -not [string]::IsNullOrWhiteSpace([string]$webProbeRefreshSummary.nextAction)) {
    $nextActionEntries.Add([ordered]@{
        source = 'web_probe_refresh'
        action = [string]$webProbeRefreshSummary.nextAction
        decision = [string]$webProbeRefreshSummary.decision
    }) | Out-Null
}
$nextActionSources = @($nextActionEntries | ForEach-Object { $_.source } | Select-Object -Unique)
$generatedAtUtc = (Get-Date).ToUniversalTime()
$generatedAtText = $generatedAtUtc.ToString('o')
$latestGeneratedAtAgeMinutes = 0.0
$latestStaleAfterMinutes = 60
$latestExpiresAt = $generatedAtUtc.AddMinutes($latestStaleAfterMinutes).ToString('o')
$staleLatest = $false
Write-JsonFile -Path $nextActionsPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.next_actions.v1'
    generatedAt = $generatedAtText
    decision = $decision
    topic = $Topic
    root = $Root
    entryCount = $nextActionEntries.Count
    sources = $nextActionSources
    actions = @($nextActionEntries)
})

$summaryPath = Join-Path $OutputDir 'goal-next-auto.summary.json'
$digestPath = Join-Path $OutputDir 'goal-next-auto.digest.json'
$digestMarkdownPath = Join-Path $OutputDir 'goal-next-auto.digest.md'
$commandPacketPath = Join-Path $OutputDir 'goal-next-auto.command-packet.json'
$commandPacketMarkdownPath = Join-Path $OutputDir 'goal-next-auto.command-packet.md'
$collectionPacketPath = Join-Path $OutputDir 'goal-next-auto.collection-packet.json'
$collectionPacketMarkdownPath = Join-Path $OutputDir 'goal-next-auto.collection-packet.md'
$commandPacket = New-CommandPacket -Entries $nextActionEntries -SupabaseApplySummary $supabaseApplySummary -ComputerUseSummary $computerUseSummary -BrowserUseSummary $browserUseSummary -WebProbeRefreshSummary $webProbeRefreshSummary -DesktopDispatchSummary $desktopDispatchSummary -SourceHealthProducerQueue $sourceHealthProducerQueue -ProjectRoot $Root -Topic $Topic -Decision $decision -GeneratedAt $generatedAtText -IncludeSupabaseCommand ([bool]$RequireSupabaseProof)
if ($RequireSupabaseProof -and $null -ne $supabaseExternalInputGate -and [string]$commandPacket.externalInputGate.status -ne 'external_input_needed') {
    $commandPacket['externalInputGate'] = $supabaseExternalInputGate
}
Write-JsonFile -Path $commandPacketPath -Value $commandPacket
$commandPacketMarkdownLines = [System.Collections.Generic.List[string]]::new()
$commandPacketMarkdownLines.Add('# goal-next-auto command packet') | Out-Null
$commandPacketMarkdownLines.Add('') | Out-Null
$commandPacketMarkdownLines.Add("- decision=$decision") | Out-Null
$commandPacketMarkdownLines.Add("- topic=$Topic") | Out-Null
$commandPacketMarkdownLines.Add("- commandCount=$($commandPacket.commandCount)") | Out-Null
$commandPacketMarkdownLines.Add("- desktopDispatch.writeRequested=$($commandPacket.desktopDispatch.writeRequested) desktopDispatch.artifactCount=$($commandPacket.desktopDispatch.dispatchArtifactCount) desktopDispatch.integrityOk=$($commandPacket.desktopDispatch.dispatchIntegrityOk) desktopDispatch.producerKitOk=$($commandPacket.desktopDispatch.producerKitOk)") | Out-Null
$commandPacketMarkdownLines.Add("- externalInputGate.status=$($commandPacket.externalInputGate.status) source=$($commandPacket.externalInputGate.source) action=$($commandPacket.externalInputGate.action) localPatchJustified=$($commandPacket.externalInputGate.localPatchJustified) mutationAllowed=$($commandPacket.externalInputGate.mutationAllowed) evidenceNeeded=$(@($commandPacket.externalInputGate.evidenceNeeded) -join ',') secretHits=$($commandPacket.externalInputGate.secretHits) windowsAbsPathHits=$($commandPacket.externalInputGate.windowsAbsPathHits)") | Out-Null
$commandPacketMarkdownLines.Add("- computerUse.decision=$($commandPacket.computerUse.decision) reachable=$($commandPacket.computerUse.reachable) stale=$($commandPacket.computerUse.stale) appCount=$($commandPacket.computerUse.appCount) runningCount=$($commandPacket.computerUse.runningCount) windowCount=$($commandPacket.computerUse.windowCount) storesRawAppNames=$($commandPacket.computerUse.storesRawAppNames) storesWindowTitles=$($commandPacket.computerUse.storesWindowTitles) secretHits=$($commandPacket.computerUse.secretHits) outputPath=$($commandPacket.computerUse.outputPath) helperCountOnly=$($commandPacket.computerUse.helperCountOnly) probeSchemaVersion=$($commandPacket.computerUse.probeSchemaVersion)") | Out-Null
$commandPacketMarkdownLines.Add("- browserUse.decision=$($commandPacket.browserUse.decision) reachable=$($commandPacket.browserUse.reachable) localhost=$($commandPacket.browserUse.localhost) stale=$($commandPacket.browserUse.stale) screenshotCaptured=$($commandPacket.browserUse.screenshotCaptured) storesRawUrl=$($commandPacket.browserUse.storesRawUrl) storesScreenshotPath=$($commandPacket.browserUse.storesScreenshotPath) secretHits=$($commandPacket.browserUse.secretHits) outputPath=$($commandPacket.browserUse.outputPath) publicDomain=$($commandPacket.browserUse.publicDomain) targetAccepted=$($commandPacket.browserUse.targetAccepted) targetHost=$($commandPacket.browserUse.targetHost)") | Out-Null
$commandPacketMarkdownLines.Add("- localInteractionSmokeRefresh.script=$($commandPacket.localInteractionSmokeRefresh.scriptPath) outputs=$(@($commandPacket.localInteractionSmokeRefresh.outputPaths) -join ',') storesRawProbePayloads=$($commandPacket.localInteractionSmokeRefresh.storesRawProbePayloads) mutationAllowed=$($commandPacket.localInteractionSmokeRefresh.mutationAllowed)") | Out-Null
$commandPacketMarkdownLines.Add("- smbDecommissionDebugProbe.tool=$($commandPacket.smbDecommissionDebugProbe.tool) decision=$($commandPacket.smbDecommissionDebugProbe.decision) outputPath=$($commandPacket.smbDecommissionDebugProbe.summaryArtifact) viewerArtifact=$($commandPacket.smbDecommissionDebugProbe.viewerArtifact) attachmentPath=$($commandPacket.smbDecommissionDebugProbe.attachmentPathPlaceholder) mutationAllowed=$($commandPacket.smbDecommissionDebugProbe.mutationAllowed) writeDispatch=$($commandPacket.smbDecommissionDebugProbe.writeDispatch) writeProducerKit=$($commandPacket.smbDecommissionDebugProbe.writeProducerKit) requireProducerBundles=$($commandPacket.smbDecommissionDebugProbe.requireProducerBundles) supportingEvidenceOnly=$($commandPacket.smbDecommissionDebugProbe.supportingEvidenceOnly) secretHits=$($commandPacket.smbDecommissionDebugProbe.secretHits) windowsAbsPathHits=$($commandPacket.smbDecommissionDebugProbe.windowsAbsPathHits)") | Out-Null
$commandPacketMarkdownLines.Add("- traceMemoryRuntimeProof=$($commandPacket.traceMemoryRuntimeProof.decision) requireTraceMemory=$($commandPacket.traceMemoryRuntimeProof.requireTraceMemory) seedsSelfProbe=$($commandPacket.traceMemoryRuntimeProof.seedsSelfProbe) outputPath=$($commandPacket.traceMemoryRuntimeProof.outputPath) secretHits=$($commandPacket.traceMemoryRuntimeProof.secretHits)") | Out-Null
$commandPacketMarkdownLines.Add("- sourceHealthProducerQueue.schema=$($commandPacket.sourceHealthProducerQueue.schema) roles=$(@($commandPacket.sourceHealthProducerQueue.producerRoles) -join ',') assignmentCount=$($commandPacket.sourceHealthProducerQueue.assignmentCount) maxDurationHours=$($commandPacket.sourceHealthProducerQueue.maxDurationHours)") | Out-Null
foreach ($cmd in @($commandPacket.commands)) {
    $envNames = if ($null -ne $cmd.requiredEnvNames -and @($cmd.requiredEnvNames).Count -gt 0) { @($cmd.requiredEnvNames) -join ',' } else { 'none' }
    $resultPath = if ($null -ne $cmd.resultPathRecommendation -and -not [string]::IsNullOrWhiteSpace([string]$cmd.resultPathRecommendation)) { [string]$cmd.resultPathRecommendation } else { 'none' }
    $advisorPath = if ($null -ne $cmd.advisorResultPathRecommendation -and -not [string]::IsNullOrWhiteSpace([string]$cmd.advisorResultPathRecommendation)) { [string]$cmd.advisorResultPathRecommendation } else { 'none' }
    $indexPath = if ($null -ne $cmd.indexPathRecommendation -and -not [string]::IsNullOrWhiteSpace([string]$cmd.indexPathRecommendation)) { [string]$cmd.indexPathRecommendation } else { 'none' }
    $mcpTools = if ($null -ne $cmd.requiredMcpTools -and @($cmd.requiredMcpTools).Count -gt 0) { @($cmd.requiredMcpTools) -join ',' } else { 'none' }
    $commandText = Redact-SensitiveText ([string]$cmd.command)
    $optionalFields = [System.Collections.Generic.List[string]]::new()
    foreach ($field in @('tool', 'outputPath', 'eventsArtifact', 'viewerArtifact', 'attachmentPathPlaceholder', 'commandWithAttachment', 'storesRawAppNames', 'storesRawUrl', 'storesScreenshotPath', 'safePendingProof', 'evidenceNeeded', 'externalListenerRequired', 'preflightCommand', 'targetMetric', 'mode', 'rawContentStored', 'rawQueryStored', 'mutationAllowed', 'writeDispatch', 'writeProducerKit', 'requireProducerBundles', 'requireSupabaseLiveProof', 'supportingEvidenceOnly', 'commandFile', 'producerVisibleCommandFile', 'producerKitManifestHashPresent', 'dispatchSha256Sidecar', 'requireTraceMemory', 'seedsSelfProbe', 'checkpointScenario', 'storesRawPrompt', 'storesRawModel', 'storesRawSsePayload')) {
        if ($null -ne $cmd.$field -and -not [string]::IsNullOrWhiteSpace([string]$cmd.$field)) {
            $optionalFields.Add("$field=$($cmd.$field)") | Out-Null
        }
    }
    $optionalSuffix = if ($optionalFields.Count -gt 0) { ' ' + (($optionalFields | ForEach-Object { Redact-SensitiveText ([string]$_) }) -join ' ') } else { '' }
    $commandPacketMarkdownLines.Add("- lane=$($cmd.lane) role=$($cmd.role) source=$($cmd.source) action=$($cmd.action) env=$envNames results=$resultPath advisors=$advisorPath index=$indexPath mcpTools=$mcpTools command=$commandText$optionalSuffix") | Out-Null
}
Write-TextFile -Path $commandPacketMarkdownPath -Value (($commandPacketMarkdownLines | ForEach-Object { Redact-SensitiveText ([string]$_) }) -join "`n")
$collectionPacket = New-CollectionPacket -Entries $nextActionEntries -SupabaseApplySummary $supabaseApplySummary -ExternalApplySummary $externalApplySummary -ComputerUseSummary $computerUseSummary -BrowserUseSummary $browserUseSummary -WebProbeRefreshSummary $webProbeRefreshSummary -DesktopDispatchSummary $desktopDispatchSummary -SourceHealthProducerQueue $sourceHealthProducerQueue -ProjectRoot $Root -Topic $Topic -Decision $decision -GeneratedAt $generatedAtText
Write-JsonFile -Path $collectionPacketPath -Value $collectionPacket
$traceMemoryRuntimeProof = $commandPacket.traceMemoryRuntimeProof
$collectionPacketMarkdownLines = [System.Collections.Generic.List[string]]::new()
$collectionPacketMarkdownLines.Add('# goal-next-auto collection packet') | Out-Null
$collectionPacketMarkdownLines.Add('') | Out-Null
$collectionPacketMarkdownLines.Add("- decision=$decision") | Out-Null
$collectionPacketMarkdownLines.Add("- topic=$Topic") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.requiredEnvNames=$(@($collectionPacket.supabase.requiredEnvNames) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.requiredMcpTools=$(@($collectionPacket.supabase.requiredMcpTools) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.requiredResultNames=$(@($collectionPacket.supabase.requiredResultNames) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.resultPathRecommendation=$($collectionPacket.supabase.resultPathRecommendation)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.advisorResultPathRecommendation=$($collectionPacket.supabase.advisorResultPathRecommendation)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.present=$($collectionPacket.supabase.mcpConfig.present)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.readOnly=$($collectionPacket.supabase.mcpConfig.readOnly)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.projectRefSource=$($collectionPacket.supabase.mcpConfig.projectRefSource)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.serverHost=$($collectionPacket.supabase.mcpConfig.serverHost)") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.features=$(@($collectionPacket.supabase.mcpConfig.features) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- supabase.mcpConfig.tokenStored=$($collectionPacket.supabase.mcpConfig.tokenStored)") | Out-Null
$collectionPacketMarkdownLines.Add("- external.requiredRoles=$(@($collectionPacket.external.requiredRoles) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- external.requiredSidecars=$(@($collectionPacket.external.requiredSidecars) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- external.sourceIsolation.guard=$($collectionPacket.external.requiredSourceIsolation.guard)") | Out-Null
$collectionPacketMarkdownLines.Add("- sourceHealthProducerQueue.schema=$($collectionPacket.sourceHealthProducerQueue.schema) roles=$(@($collectionPacket.sourceHealthProducerQueue.producerRoles) -join ',') assignmentCount=$($collectionPacket.sourceHealthProducerQueue.assignmentCount) maxDurationHours=$($collectionPacket.sourceHealthProducerQueue.maxDurationHours)") | Out-Null
$collectionPacketMarkdownLines.Add("- desktopDispatch.writeRequested=$($collectionPacket.desktopDispatch.writeRequested) desktopDispatch.artifactCount=$($collectionPacket.desktopDispatch.dispatchArtifactCount) desktopDispatch.integrityOk=$($collectionPacket.desktopDispatch.dispatchIntegrityOk) desktopDispatch.producerKitOk=$($collectionPacket.desktopDispatch.producerKitOk)") | Out-Null
$collectionPacketMarkdownLines.Add("- desktopDispatch.producerCommandFiles=$(@($collectionPacket.desktopDispatch.producerCommandFiles | ForEach-Object { $_.desktopCommandFile }) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.requiredEnvNames=$(@($collectionPacket.archive.requiredEnvNames) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.requiredMcpTools=$(@($collectionPacket.archive.requiredMcpTools) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.indexPathRecommendation=$($collectionPacket.archive.indexPathRecommendation)") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.archiveRootRecommendation=$($collectionPacket.archive.archiveRootRecommendation)") | Out-Null
$collectionPacketMarkdownLines.Add("- archive.nextActions=$(@($collectionPacket.archive.nextActions) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.decision=$($collectionPacket.computerUse.decision)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.tool=$($collectionPacket.computerUse.tool)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.outputPath=$($collectionPacket.computerUse.outputPath)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.storesRawAppNames=$($collectionPacket.computerUse.storesRawAppNames)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.storesWindowTitles=$($collectionPacket.computerUse.storesWindowTitles)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.helperCountOnly=$($collectionPacket.computerUse.helperCountOnly)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.probeSchemaVersion=$($collectionPacket.computerUse.probeSchemaVersion)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.appCount=$($collectionPacket.computerUse.appCount)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.runningCount=$($collectionPacket.computerUse.runningCount)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.windowCount=$($collectionPacket.computerUse.windowCount)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.secretHits=$($collectionPacket.computerUse.secretHits)") | Out-Null
$collectionPacketMarkdownLines.Add("- computerUse.nextActions=$(@($collectionPacket.computerUse.nextActions) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.decision=$($collectionPacket.browserUse.decision)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.tool=$($collectionPacket.browserUse.tool)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.outputPath=$($collectionPacket.browserUse.outputPath)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.storesRawUrl=$($collectionPacket.browserUse.storesRawUrl)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.storesScreenshotPath=$($collectionPacket.browserUse.storesScreenshotPath)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.externalListenerRequired=$($collectionPacket.browserUse.externalListenerRequired)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.preflightCommand=$($collectionPacket.browserUse.preflightCommand)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.reachable=$($collectionPacket.browserUse.reachable)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.localhost=$($collectionPacket.browserUse.localhost)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.publicDomain=$($collectionPacket.browserUse.publicDomain)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.targetAccepted=$($collectionPacket.browserUse.targetAccepted)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.targetHost=$($collectionPacket.browserUse.targetHost)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.safePendingProof=$($collectionPacket.browserUse.safePendingProof)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.screenshotCaptured=$($collectionPacket.browserUse.screenshotCaptured)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.statusClass=$($collectionPacket.browserUse.statusClass)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.targetContentVisible=$($collectionPacket.browserUse.targetContentVisible)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.browserSurface=$($collectionPacket.browserUse.browserSurface)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.evidenceNeeded=$($collectionPacket.browserUse.evidenceNeeded)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.secretHits=$($collectionPacket.browserUse.secretHits)") | Out-Null
$collectionPacketMarkdownLines.Add("- browserUse.nextActions=$(@($collectionPacket.browserUse.nextActions) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- webProbeRefresh.decision=$($collectionPacket.webProbeRefresh.decision)") | Out-Null
$collectionPacketMarkdownLines.Add("- webProbeRefresh.tool=$($collectionPacket.webProbeRefresh.tool)") | Out-Null
$collectionPacketMarkdownLines.Add("- webProbeRefresh.outputPath=$($collectionPacket.webProbeRefresh.outputPath)") | Out-Null
$collectionPacketMarkdownLines.Add("- webProbeRefresh.sourceCount=$($collectionPacket.webProbeRefresh.sourceCount)") | Out-Null
$collectionPacketMarkdownLines.Add("- webProbeRefresh.fetchedCount=$($collectionPacket.webProbeRefresh.fetchedCount)") | Out-Null
$collectionPacketMarkdownLines.Add("- webProbeRefresh.rawContentStored=$($collectionPacket.webProbeRefresh.rawContentStored)") | Out-Null
$collectionPacketMarkdownLines.Add("- webProbeRefresh.rawQueryStored=$($collectionPacket.webProbeRefresh.rawQueryStored)") | Out-Null
$collectionPacketMarkdownLines.Add("- webProbeRefresh.mutationAllowed=$($collectionPacket.webProbeRefresh.mutationAllowed)") | Out-Null
$collectionPacketMarkdownLines.Add("- webProbeRefresh.secretHits=$($collectionPacket.webProbeRefresh.secretHits)") | Out-Null
$collectionPacketMarkdownLines.Add("- localInteractionSmokeRefresh.script=$($collectionPacket.localInteractionSmokeRefresh.scriptPath)") | Out-Null
$collectionPacketMarkdownLines.Add("- localInteractionSmokeRefresh.outputs=$(@($collectionPacket.localInteractionSmokeRefresh.outputPaths) -join ',')") | Out-Null
$collectionPacketMarkdownLines.Add("- localInteractionSmokeRefresh.storesRawProbePayloads=$($collectionPacket.localInteractionSmokeRefresh.storesRawProbePayloads)") | Out-Null
$collectionPacketMarkdownLines.Add("- smbDecommissionDebugProbe.tool=$($collectionPacket.smbDecommissionDebugProbe.tool)") | Out-Null
$collectionPacketMarkdownLines.Add("- smbDecommissionDebugProbe.summaryArtifact=$($collectionPacket.smbDecommissionDebugProbe.summaryArtifact)") | Out-Null
$collectionPacketMarkdownLines.Add("- smbDecommissionDebugProbe.viewerArtifact=$($collectionPacket.smbDecommissionDebugProbe.viewerArtifact)") | Out-Null
$collectionPacketMarkdownLines.Add("- smbDecommissionDebugProbe.mutationAllowed=$($collectionPacket.smbDecommissionDebugProbe.mutationAllowed)") | Out-Null
$collectionPacketMarkdownLines.Add("- smbDecommissionDebugProbe.writeDispatch=$($collectionPacket.smbDecommissionDebugProbe.writeDispatch)") | Out-Null
$collectionPacketMarkdownLines.Add("- smbDecommissionDebugProbe.writeProducerKit=$($collectionPacket.smbDecommissionDebugProbe.writeProducerKit)") | Out-Null
$collectionPacketMarkdownLines.Add("- smbDecommissionDebugProbe.requireProducerBundles=$($collectionPacket.smbDecommissionDebugProbe.requireProducerBundles)") | Out-Null
$collectionPacketMarkdownLines.Add("- smbDecommissionDebugProbe.supportingEvidenceOnly=$($collectionPacket.smbDecommissionDebugProbe.supportingEvidenceOnly)") | Out-Null
$collectionPacketMarkdownLines.Add("- smbDecommissionDebugProbe.secretHits=$($collectionPacket.smbDecommissionDebugProbe.secretHits)") | Out-Null
$collectionPacketMarkdownLines.Add("- traceMemoryRuntimeProof.script=$($collectionPacket.traceMemoryRuntimeProof.scriptPath)") | Out-Null
$collectionPacketMarkdownLines.Add("- traceMemoryRuntimeProof.requireTraceMemory=$($collectionPacket.traceMemoryRuntimeProof.requireTraceMemory)") | Out-Null
$collectionPacketMarkdownLines.Add("- traceMemoryRuntimeProof.outputPath=$($collectionPacket.traceMemoryRuntimeProof.outputPath)") | Out-Null
Write-TextFile -Path $collectionPacketMarkdownPath -Value (($collectionPacketMarkdownLines | ForEach-Object { Redact-SensitiveText ([string]$_) }) -join "`n")
$supabaseMcpConfig = $collectionPacket.supabase.mcpConfig
$sourceActionCounts = [ordered]@{}
foreach ($entry in $nextActionEntries) {
    $source = [string]$entry.source
    if ([string]::IsNullOrWhiteSpace($source)) {
        $source = 'unknown'
    }
    if (-not $sourceActionCounts.Contains($source)) {
        $sourceActionCounts[$source] = 0
    }
    $sourceActionCounts[$source] = [int]$sourceActionCounts[$source] + 1
}
$topActionEntries = @($nextActionEntries | Select-Object -First 8 | ForEach-Object {
    [ordered]@{
        source = [string]$_.source
        action = [string]$_.action
        decision = [string]$_.decision
    }
})
$firstAction = if ($nextActionEntries.Count -gt 0) { [string]$nextActionEntries[0].action } else { '' }
$firstActionSource = if ($nextActionEntries.Count -gt 0) { [string]$nextActionEntries[0].source } else { '' }
if ($RequireSupabaseProof -and $null -ne $supabaseExternalInputGate -and [string]$supabaseExternalInputGate.status -eq 'external_input_needed') {
    $firstAction = [string]$supabaseExternalInputGate.action
    $firstActionSource = [string]$supabaseExternalInputGate.source
}
$latestPointerPath = Join-Path $defaultSmokeRoot 'goal-next-auto.latest.json'
New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
Write-JsonFile -Path $latestPointerPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.latest.v1'
    generatedAt = $generatedAtText
    decision = $decision
    requestMode = $requestMode
    topic = $Topic
    root = $Root
    outputDir = $OutputDir
    latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
    latestStaleAfterMinutes = $latestStaleAfterMinutes
    latestExpiresAt = $latestExpiresAt
    staleLatest = $staleLatest
    summaryPath = $summaryPath
    nextActionsPath = $nextActionsPath
    commandPacketPath = $commandPacketPath
    commandPacketMarkdownPath = $commandPacketMarkdownPath
    collectionPacketPath = $collectionPacketPath
    collectionPacketMarkdownPath = $collectionPacketMarkdownPath
    digestPath = $digestPath
    digestMarkdownPath = $digestMarkdownPath
    breadcrumbTimelinePath = $breadcrumbTimelinePath
    breadcrumbFusionPath = $breadcrumbFusionPath
    nextActionEntryCount = $nextActionEntries.Count
    commandPacketCommandCount = $commandPacket.commandCount
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousBreadcrumb = $previousBreadcrumbSummary
    computerUseOk = [bool]$computerUseSummary.ok
    computerUseReachable = [bool]$computerUseSummary.reachable
    computerUseAppCount = [int]$computerUseSummary.appCount
    computerUseDecision = [string]$computerUseSummary.decision
    computerUse = $computerUseSummary
    browserUseOk = [bool]$browserUseSummary.ok
    browserUseReachable = [bool]$browserUseSummary.reachable
    browserUseLocalhost = [bool]$browserUseSummary.localhost
    browserUsePublicDomain = if ($null -ne $browserUseSummary.publicDomain) { [bool]$browserUseSummary.publicDomain } else { $false }
    browserUseTargetAccepted = if ($null -ne $browserUseSummary.targetAccepted) { [bool]$browserUseSummary.targetAccepted } else { $false }
    browserUseTargetHost = if (-not [string]::IsNullOrWhiteSpace([string]$browserUseSummary.targetHost)) { Get-SafePacketString -Text ([string]$browserUseSummary.targetHost) -ProjectRoot $Root } else { 'unknown' }
    browserUseDecision = [string]$browserUseSummary.decision
    browserUse = $browserUseSummary
    peerEvidenceBus = $peerEvidenceBusSummary
    webProbeRefresh = $webProbeRefreshSummary
    traceMemoryRuntimeProof = $traceMemoryRuntimeProof
    supabaseSmoke = $supabaseSmokeSummary
    supabaseMcpConfig = $supabaseMcpConfig
    secretHits = $secretHits
})
$completionAuditPacketOutputPath = Join-Path $OutputDir 'awx-mcp-completion-audit.packet.result.json'
$completionAuditPacketArgs = @('--root', $Root, '--output', $completionAuditPacketOutputPath)
if ($RequireSupabaseProof) {
    $completionAuditPacketArgs += '--require-supabase-proof'
}
$completionAuditPacket = Invoke-PythonCapture `
    -Name 'completion_audit_packet' `
    -ScriptPath (Join-Path $scriptsDir 'awx_mcp_completion_audit.py') `
    -Arguments $completionAuditPacketArgs `
    -LogPath (Join-Path $OutputDir 'awx-mcp-completion-audit.packet.log') `
    -CompactLog `
    -ArtifactPath $completionAuditPacketOutputPath `
    -ArtifactLabel 'awx-mcp-completion-audit.packet.result.json'
if (Test-Path -LiteralPath $completionAuditPacketOutputPath) {
    Copy-Item -LiteralPath $completionAuditPacketOutputPath -Destination $completionAuditCanonicalPath -Force
}
$secretHits += [int]$completionAuditPacket.SecretHits
if ($secretHits -gt 0 -or $completionAuditPacket.ExitCode -eq 4) {
    $decision = 'secret-leak-risk'
}

$sourceHealthFinal = Invoke-PythonCapture `
    -Name 'source_health_final' `
    -ScriptPath (Join-Path $scriptsDir 'source_health_scorecard.py') `
    -Arguments @('--root', $Root, '--output', (Join-Path $OutputDir 'source-health-scorecard.json')) `
    -LogPath (Join-Path $OutputDir 'source-health-scorecard.final.log')
$sourceHealth = $sourceHealthFinal
$sourceHealthJson = Read-JsonObjectFromFile -Path (Join-Path $OutputDir 'source-health-scorecard.json')
$sourceHealthProducerQueue = New-SafeSourceHealthProducerQueue -SourceHealthJson $sourceHealthJson -ProjectRoot $Root
$sourceHealthDispatchOverlay = Write-SourceHealthProducerDispatchOverlay -ProjectRoot $Root -Topic $Topic -DesktopDispatchSummary $desktopDispatchSummary -SourceHealthProducerQueue $sourceHealthProducerQueue
$secretHits += [int]$sourceHealth.SecretHits
if ($secretHits -gt 0 -or $sourceHealth.ExitCode -eq 4) {
    $decision = 'secret-leak-risk'
} elseif ($decision -eq 'ok' -and $sourceHealth.ExitCode -ne 0) {
    $decision = 'evidence_needed'
}

$completionAuditOutputPath = Join-Path $OutputDir 'awx-mcp-completion-audit.result.json'
$completionAuditArgs = @('--root', $Root, '--output', $completionAuditOutputPath)
if ($RequireSupabaseProof) {
    $completionAuditArgs += '--require-supabase-proof'
}
$completionAudit = Invoke-PythonCapture `
    -Name 'completion_audit' `
    -ScriptPath (Join-Path $scriptsDir 'awx_mcp_completion_audit.py') `
    -Arguments $completionAuditArgs `
    -LogPath (Join-Path $OutputDir 'awx-mcp-completion-audit.log') `
    -CompactLog `
    -ArtifactPath $completionAuditOutputPath `
    -ArtifactLabel 'awx-mcp-completion-audit.result.json'
if (Test-Path -LiteralPath $completionAuditOutputPath) {
    Copy-Item -LiteralPath $completionAuditOutputPath -Destination $completionAuditCanonicalPath -Force
}
$secretHits += [int]$completionAudit.SecretHits
if ($secretHits -gt 0 -or $completionAudit.ExitCode -eq 4) {
    $decision = 'secret-leak-risk'
} elseif ($decision -eq 'ok' -and $completionAudit.ExitCode -ne 0) {
    $decision = 'evidence_needed'
}
$summaryFailureClassification = if ($decision -eq 'ok') {
    ''
} elseif (-not [string]::IsNullOrWhiteSpace([string]$preflight.failureClassification)) {
    [string]$preflight.failureClassification
} else {
    $decision
}
$sourceActionCountText = if ($sourceActionCounts.Count -gt 0) {
    @($sourceActionCounts.Keys | ForEach-Object { "$_=$($sourceActionCounts[$_])" }) -join ', '
} else {
    'none'
}
$digestMarkdownLines = [System.Collections.Generic.List[string]]::new()
$digestMarkdownLines.Add('# goal-next-auto digest') | Out-Null
$digestMarkdownLines.Add('') | Out-Null
$digestMarkdownLines.Add("- decision=$decision") | Out-Null
$digestMarkdownLines.Add("- topic=$Topic") | Out-Null
$digestMarkdownLines.Add("- latestGeneratedAtAgeMinutes=$latestGeneratedAtAgeMinutes") | Out-Null
$digestMarkdownLines.Add("- latestStaleAfterMinutes=$latestStaleAfterMinutes") | Out-Null
$completionAuditInvocationCount = if ($completionAuditBootstrap.preflightExecuted) { 3 } else { 2 }
$digestMarkdownLines.Add("- completionAuditBootstrap.mode=$($completionAuditBootstrap.mode) reason=$($completionAuditBootstrap.reason) preflightExecuted=$($completionAuditBootstrap.preflightExecuted) invocationCount=$completionAuditInvocationCount") | Out-Null
$digestMarkdownLines.Add("- latestExpiresAt=$latestExpiresAt") | Out-Null
$digestMarkdownLines.Add("- staleLatest=$($staleLatest.ToString().ToLowerInvariant())") | Out-Null
$digestMarkdownLines.Add("- firstAction=$firstAction") | Out-Null
$digestMarkdownLines.Add("- firstActionSource=$firstActionSource") | Out-Null
$digestMarkdownLines.Add("- previousBreadcrumb.present=$($previousBreadcrumbSummary.present) decision=$($previousBreadcrumbSummary.decision) firstAction=$($previousBreadcrumbSummary.firstAction) toolStepCount=$($previousBreadcrumbSummary.toolStepCount)") | Out-Null
$digestMarkdownLines.Add("- sourceActionCounts=$sourceActionCountText") | Out-Null
$digestMarkdownLines.Add("- supabaseDecision=$(if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' })") | Out-Null
$digestMarkdownLines.Add("- supabaseSmoke.mcpDecision=$($supabaseSmokeSummary.mcpDecision) supabaseSmoke.mcpReachable=$($supabaseSmokeSummary.mcpReachable) supabaseSmoke.mcpProbeSkipped=$($supabaseSmokeSummary.mcpProbeSkipped) supabaseSmoke.reachability=$($supabaseSmokeSummary.mcpEndpointReachabilityEvidence)") | Out-Null
$digestMarkdownLines.Add("- supabaseMcp.present=$($supabaseMcpConfig.present) supabaseMcp.readOnly=$($supabaseMcpConfig.readOnly) supabaseMcp.projectRefSource=$($supabaseMcpConfig.projectRefSource) supabaseMcp.serverHost=$($supabaseMcpConfig.serverHost) supabaseMcp.tokenStored=$($supabaseMcpConfig.tokenStored)") | Out-Null
if ($null -ne $collectionPacket -and $null -ne $collectionPacket.supabase) {
    $digestMarkdownLines.Add("- supabase.requiredEnvNames=$(@($collectionPacket.supabase.requiredEnvNames) -join ',')") | Out-Null
    $digestMarkdownLines.Add("- supabase.requiredMcpTools=$(@($collectionPacket.supabase.requiredMcpTools) -join ',')") | Out-Null
    $digestMarkdownLines.Add("- supabase.envPreflightStatus=$($collectionPacket.supabase.envPreflightStatus)") | Out-Null
    $digestMarkdownLines.Add("- supabase.resultPathRecommendation=$($collectionPacket.supabase.resultPathRecommendation)") | Out-Null
    $digestMarkdownLines.Add("- supabase.advisorResultPathRecommendation=$($collectionPacket.supabase.advisorResultPathRecommendation)") | Out-Null
}
$digestMarkdownLines.Add("- externalDecision=$(if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' })") | Out-Null
if ($null -ne $externalApplySummary) {
    $digestMarkdownLines.Add("- external.requiredRoles=$(@($externalApplySummary.requiredRoles) -join ',')") | Out-Null
    $digestMarkdownLines.Add("- external.requiredSidecars=$(@($externalApplySummary.requiredPatchDropSidecars) -join ',')") | Out-Null
    $digestMarkdownLines.Add("- external.sourceIsolation.guard=$($externalApplySummary.requiredSourceIsolation.guard)") | Out-Null
}
$digestMarkdownLines.Add("- sourceHealthProducerQueue.schema=$($sourceHealthProducerQueue.schema) roles=$(@($sourceHealthProducerQueue.producerRoles) -join ',') assignmentCount=$($sourceHealthProducerQueue.assignmentCount) maxDurationHours=$($sourceHealthProducerQueue.maxDurationHours)") | Out-Null
$digestMarkdownLines.Add("- sourceHealthDispatchOverlay=$(if ([bool]$sourceHealthDispatchOverlay.ok) { 'ok' } else { 'evidence_needed' }) sidecar=$($sourceHealthDispatchOverlay.sidecarPath) sidecarHash=$($sourceHealthDispatchOverlay.sidecarHash) shaSidecarUpdated=$($sourceHealthDispatchOverlay.shaSidecarUpdated)") | Out-Null
$digestMarkdownLines.Add("- sourceHealthDispatchOverlay.sidecarHash=$($sourceHealthDispatchOverlay.sidecarHash)") | Out-Null
$digestMarkdownLines.Add("- desktopFinalProof=$(if ($null -ne $desktopControlLoopJson) { [string]$desktopControlLoopJson.desktopFinalProof } else { 'evidence_needed' })") | Out-Null
$digestMarkdownLines.Add("- desktopDispatch.writeRequested=$desktopControlLoopRequestsDispatch artifactCount=$desktopDispatchArtifactCount integrityOk=$desktopDispatchIntegrityOk failReason=$desktopDispatchFailReason") | Out-Null
$digestMarkdownLines.Add("- desktopProducerKit.writeRequested=$desktopControlLoopRequestsProducerKit ok=$desktopProducerKitOk") | Out-Null
$digestMarkdownLines.Add("- computerUse=$(if ([bool]$computerUseSummary.ok) { 'ok' } else { [string]$computerUseSummary.decision }) reachable=$($computerUseSummary.reachable) stale=$($computerUseSummary.stale) appCount=$($computerUseSummary.appCount) runningCount=$($computerUseSummary.runningCount) windowCount=$($computerUseSummary.windowCount) helperCountOnly=$($computerUseSummary.helperCountOnly) probeSchemaVersion=$($computerUseSummary.probeSchemaVersion) secretHits=$($computerUseSummary.secretHits)") | Out-Null
$digestMarkdownLines.Add("- browserUse=$(if ([bool]$browserUseSummary.ok) { 'ok' } else { [string]$browserUseSummary.decision }) reachable=$($browserUseSummary.reachable) localhost=$($browserUseSummary.localhost) stale=$($browserUseSummary.stale) screenshotCaptured=$($browserUseSummary.screenshotCaptured) secretHits=$($browserUseSummary.secretHits) publicDomain=$($browserUseSummary.publicDomain) targetAccepted=$($browserUseSummary.targetAccepted) targetHost=$($browserUseSummary.targetHost)") | Out-Null
$digestMarkdownLines.Add("- peerEvidenceBus=$(if ([bool]$peerEvidenceBusSummary.ok) { 'ok' } else { [string]$peerEvidenceBusSummary.decision }) targetMetric=$($peerEvidenceBusSummary.targetMetric) outputCount=$($peerEvidenceBusSummary.outputCount) promptPackPresent=$($peerEvidenceBusSummary.promptPackPresent) secretHits=$($peerEvidenceBusSummary.secretHits)") | Out-Null
$digestMarkdownLines.Add("- webProbeRefresh=$(if ([bool]$webProbeRefreshSummary.ok) { 'ok' } else { [string]$webProbeRefreshSummary.decision }) targetMetric=$($webProbeRefreshSummary.targetMetric) sourceCount=$($webProbeRefreshSummary.sourceCount) fetchedCount=$($webProbeRefreshSummary.fetchedCount) rawContentStored=$($webProbeRefreshSummary.rawContentStored) rawQueryStored=$($webProbeRefreshSummary.rawQueryStored) mutationAllowed=$($webProbeRefreshSummary.mutationAllowed) secretHits=$($webProbeRefreshSummary.secretHits)") | Out-Null
$digestMarkdownLines.Add("- smbDecommissionDebugProbe=$($commandPacket.smbDecommissionDebugProbe.decision) tool=$($commandPacket.smbDecommissionDebugProbe.tool) outputPath=$($commandPacket.smbDecommissionDebugProbe.summaryArtifact) viewerArtifact=$($commandPacket.smbDecommissionDebugProbe.viewerArtifact) mutationAllowed=$($commandPacket.smbDecommissionDebugProbe.mutationAllowed) writeDispatch=$($commandPacket.smbDecommissionDebugProbe.writeDispatch) writeProducerKit=$($commandPacket.smbDecommissionDebugProbe.writeProducerKit) requireProducerBundles=$($commandPacket.smbDecommissionDebugProbe.requireProducerBundles) supportingEvidenceOnly=$($commandPacket.smbDecommissionDebugProbe.supportingEvidenceOnly) secretHits=$($commandPacket.smbDecommissionDebugProbe.secretHits)") | Out-Null
$digestMarkdownLines.Add("- traceMemoryRuntimeProof=$($traceMemoryRuntimeProof.decision) requireTraceMemory=$($traceMemoryRuntimeProof.requireTraceMemory) seedsSelfProbe=$($traceMemoryRuntimeProof.seedsSelfProbe) outputPath=$($traceMemoryRuntimeProof.outputPath) secretHits=$($traceMemoryRuntimeProof.secretHits)") | Out-Null
$digestMarkdownLines.Add("- secretHits=$secretHits") | Out-Null
if ($topActionEntries.Count -gt 0) {
    $digestMarkdownLines.Add('') | Out-Null
    $digestMarkdownLines.Add('## topActions') | Out-Null
    foreach ($entry in $topActionEntries) {
        $digestMarkdownLines.Add("- source=$($entry.source) action=$($entry.action) decision=$($entry.decision)") | Out-Null
    }
}

$supabaseApplyArtifactSummary = [ordered]@{
    parsed = ($null -ne $supabaseApplySummary)
    summaryPath = $supabaseApplySummaryPath
    processExecuted = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.processExecuted) { [bool]$supabaseApplySummary.processExecuted } else { [bool]$RequireSupabaseProof }
    ok = if ($null -ne $supabaseApplySummary) { [bool]$supabaseApplySummary.ok } else { $false }
    decision = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' }
    evidenceNeededCount = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.evidenceNeeded) { @($supabaseApplySummary.evidenceNeeded).Count } else { 0 }
    requiredEnvNames = if ($null -ne $collectionPacket -and $null -ne $collectionPacket.supabase -and $null -ne $collectionPacket.supabase.requiredEnvNames) { @($collectionPacket.supabase.requiredEnvNames) } else { @() }
    requiredMcpTools = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.requiredMcpTools) { @($supabaseApplySummary.requiredMcpTools) } else { @() }
    requiredResultNames = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.requiredResultNames) { @($supabaseApplySummary.requiredResultNames) } else { @() }
    nextActions = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.nextActions) { @($supabaseApplySummary.nextActions) } else { @() }
    resultPathRecommendation = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.resultPathRecommendation } else { '' }
    advisorResultPathRecommendation = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.advisorResultPathRecommendation } else { '' }
    readOnlyMcpEndpointTemplate = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.readOnlyMcpEndpointTemplate } else { '' }
    projectRefEnvPresent = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.projectRefEnvPresent) { [bool]$supabaseApplySummary.projectRefEnvPresent } else { $false }
    accessTokenEnvPresent = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.accessTokenEnvPresent) { [bool]$supabaseApplySummary.accessTokenEnvPresent } else { $false }
    accessTokenManualFallbackEnvPresent = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.accessTokenManualFallbackEnvPresent) { [bool]$supabaseApplySummary.accessTokenManualFallbackEnvPresent } elseif ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.accessTokenEnvPresent) { [bool]$supabaseApplySummary.accessTokenEnvPresent } else { $false }
    mcpOAuthSupported = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.mcpOAuthSupported) { [bool]$supabaseApplySummary.mcpOAuthSupported } else { $false }
    supportedAuthModes = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.supportedAuthModes) { @($supabaseApplySummary.supportedAuthModes | ForEach-Object { Get-SafePacketString -Text ([string]$_) -ProjectRoot $Root }) } else { @() }
    envPreflightStatus = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.envPreflightStatus } else { '' }
    secretHits = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.secretHits) { [int]$supabaseApplySummary.secretHits } else { 0 }
    rawSecretPatternHits = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.rawSecretPatternHits) { [int]$supabaseApplySummary.rawSecretPatternHits } else { 0 }
}

$externalApplyEvidenceNeededItems = [object[]]@()
$externalApplySupportingEvidenceNeededItems = [object[]]@()
$externalApplyNextActionItems = [object[]]@()
$externalApplySupportingEvidenceNextActionItems = [object[]]@()
if ($null -ne $externalApplySummary) {
    if ($null -ne $externalApplySummary.evidenceNeeded) {
        $externalApplyEvidenceNeededItems = [object[]]@(Get-SafeUniqueStrings -Values $externalApplySummary.evidenceNeeded -ProjectRoot $Root)
    }
    if ($null -ne $externalApplySummary.supportingEvidenceNeeded) {
        $externalApplySupportingEvidenceNeededItems = [object[]]@(Get-SafeUniqueStrings -Values $externalApplySummary.supportingEvidenceNeeded -ProjectRoot $Root)
    }
    if ($null -ne $externalApplySummary.nextActions) {
        $externalApplyNextActionItems = [object[]]@(Get-SafeUniqueStrings -Values $externalApplySummary.nextActions -ProjectRoot $Root)
    }
    if ($null -ne $externalApplySummary.supportingEvidenceNextActions) {
        $externalApplySupportingEvidenceNextActionItems = [object[]]@(Get-SafeUniqueStrings -Values $externalApplySummary.supportingEvidenceNextActions -ProjectRoot $Root)
    }
}

$externalApplyArtifactSummary = [ordered]@{
    parsed = ($null -ne $externalApplySummary)
    summaryPath = $externalApplySummaryPath
    processExecuted = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.processExecuted) { [bool]$externalApplySummary.processExecuted } else { [bool]$ExternalDispatch }
    ok = if ($null -ne $externalApplySummary) { [bool]$externalApplySummary.ok } else { $false }
    decision = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' }
    producerBundlesRequired = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.producerBundlesRequired) { [bool]$externalApplySummary.producerBundlesRequired } else { $true }
    externalEvidenceMode = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.externalEvidenceMode) { [string]$externalApplySummary.externalEvidenceMode } else { 'required' }
    evidenceNeededCount = @($externalApplyEvidenceNeededItems).Count
    supportingEvidenceNeededCount = @($externalApplySupportingEvidenceNeededItems).Count
    requiredRoles = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.requiredRoles) { @($externalApplySummary.requiredRoles) } else { @() }
    requiredProducerEvidenceFiles = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.requiredProducerEvidenceFiles) { @($externalApplySummary.requiredProducerEvidenceFiles) } else { @() }
    requiredPatchDropSidecars = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.requiredPatchDropSidecars) { @($externalApplySummary.requiredPatchDropSidecars) } else { @() }
    requiredSourceIsolation = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.requiredSourceIsolation) { $externalApplySummary.requiredSourceIsolation } else { [ordered]@{} }
    nextActions = [object[]]$externalApplyNextActionItems
    nextActionCount = @($externalApplyNextActionItems).Count
    supportingEvidenceNextActions = [object[]]$externalApplySupportingEvidenceNextActionItems
    supportingEvidenceNextActionCount = @($externalApplySupportingEvidenceNextActionItems).Count
    applyCollectedEvidenceCommand = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.applyCollectedEvidenceCommand } else { '' }
    copiedEvidenceCount = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.copiedEvidenceCount) { [int]$externalApplySummary.copiedEvidenceCount } else { 0 }
    copiedHandoffCount = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.copiedHandoffCount) { [int]$externalApplySummary.copiedHandoffCount } else { 0 }
    secretHits = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.secretHits) { [int]$externalApplySummary.secretHits } else { 0 }
    rawSecretPatternHits = if ($null -ne $externalApplySummary -and $null -ne $externalApplySummary.rawSecretPatternHits) { [int]$externalApplySummary.rawSecretPatternHits } else { 0 }
}

$sourceHealthArtifactSummary = [ordered]@{
    parsed = ($null -ne $sourceHealthJson)
    exitCode = [int]$sourceHealth.ExitCode
    strictEvidenceAdjustedScore = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.strictEvidenceAdjustedScore) { [double]$sourceHealthJson.strictEvidenceAdjustedScore } else { 0.0 }
    riskCount = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.riskCount) { [int]$sourceHealthJson.riskCount } else { 0 }
    activeRiskCount = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.activeRiskCount) { [int]$sourceHealthJson.activeRiskCount } else { 0 }
    evidenceNeededCount = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.evidenceNeededCount) { [int]$sourceHealthJson.evidenceNeededCount } else { 0 }
    failurePatternKind = if ($null -ne $sourceHealthJson -and -not [string]::IsNullOrWhiteSpace([string]$sourceHealthJson.failurePatternKind)) {
        Get-SafePacketString -Text ([string]$sourceHealthJson.failurePatternKind) -ProjectRoot $Root
    } elseif ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.failurePatternPrediction -and -not [string]::IsNullOrWhiteSpace([string]$sourceHealthJson.failurePatternPrediction.failurePatternKind)) {
        Get-SafePacketString -Text ([string]$sourceHealthJson.failurePatternPrediction.failurePatternKind) -ProjectRoot $Root
    } else {
        ''
    }
    patternId = if ($null -ne $sourceHealthJson -and -not [string]::IsNullOrWhiteSpace([string]$sourceHealthJson.patternId)) {
        Get-SafePacketString -Text ([string]$sourceHealthJson.patternId) -ProjectRoot $Root
    } elseif ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.failurePatternPrediction -and -not [string]::IsNullOrWhiteSpace([string]$sourceHealthJson.failurePatternPrediction.patternId)) {
        Get-SafePacketString -Text ([string]$sourceHealthJson.failurePatternPrediction.patternId) -ProjectRoot $Root
    } else {
        ''
    }
    amplifiedSignalScore = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.amplifiedSignalScore) {
        [double]$sourceHealthJson.amplifiedSignalScore
    } elseif ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.failurePatternPrediction -and $null -ne $sourceHealthJson.failurePatternPrediction.amplifiedSignalScore) {
        [double]$sourceHealthJson.failurePatternPrediction.amplifiedSignalScore
    } else {
        0.0
    }
    nextSingleAction = if ($null -ne $sourceHealthJson) { [string]$sourceHealthJson.nextSingleAction } else { '' }
    externalEvidenceNextAction = if ($null -ne $sourceHealthJson) { [string]$sourceHealthJson.externalEvidenceNextAction } else { '' }
    nextSourceAction = if ($null -ne $sourceHealthJson) { [string]$sourceHealthJson.nextSourceAction } else { '' }
    nextActionDetailCount = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.nextActionDetails) { @($sourceHealthJson.nextActionDetails).Count } else { 0 }
    nextSourceActionDetailCount = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.nextSourceActionDetails) { @($sourceHealthJson.nextSourceActionDetails).Count } else { 0 }
    producerValidationQueue = $sourceHealthProducerQueue
    dispatchOverlay = $sourceHealthDispatchOverlay
    secretHits = [int]$sourceHealth.SecretHits
}

$breadcrumbToolSteps = @(
    [ordered]@{
        name = 'preflight'
        decision = if ([string]::IsNullOrWhiteSpace([string]$preflight.failureClassification)) { 'ok' } else { 'evidence_needed' }
        failureClassification = [string]$preflight.failureClassification
        patchDropPendingPatchCount = [int]$preflight.patchDropPendingPatchCount
        sourceLeaseActiveCount = [int]$preflight.sourceLeaseActiveCount
        sourceLeaseCorruptCount = [int]$preflight.sourceLeaseCorruptCount
    },
    [ordered]@{
        name = 'supabase_smoke'
        exitCode = [int]$supabaseSmoke.ExitCode
        decision = [string]$supabaseSmokeSummary.decision
        projectScopeStatus = [string]$supabaseSmokeSummary.projectScopeStatus
        mcpDecision = [string]$supabaseSmokeSummary.mcpDecision
        secretHits = ([int]$supabaseSmoke.SecretHits + [int]$supabaseSmokeSummary.secretHits)
    },
    [ordered]@{
        name = 'supabase_apply'
        exitCode = [int]$supabaseApply.ExitCode
        decision = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' }
        evidenceNeededCount = if ($null -ne $supabaseApplySummary -and $null -ne $supabaseApplySummary.evidenceNeeded) { @($supabaseApplySummary.evidenceNeeded).Count } else { 0 }
        secretHits = [int]$supabaseApply.SecretHits
    },
    [ordered]@{
        name = 'external_apply'
        exitCode = [int]$externalApply.ExitCode
        decision = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' }
        evidenceNeededCount = @($externalApplyEvidenceNeededItems).Count
        secretHits = [int]$externalApply.SecretHits
    },
    [ordered]@{
        name = 'desktop_control_loop'
        exitCode = [int]$desktopControlLoop.ExitCode
        decision = if ($null -ne $desktopControlLoopJson) { [string]$desktopControlLoopJson.decision } else { 'evidence_needed' }
        localReady = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.localReady } else { $false }
        completionReady = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.completionReady } else { $false }
        externalEvidenceComplete = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.externalEvidenceComplete } else { $false }
        nextActionCount = $desktopControlLoopNextActionCount
        secretHits = [int]$desktopControlLoop.SecretHits
    },
    [ordered]@{
        name = 'web_probe_refresh'
        exitCode = [int]$webProbeRefresh.ExitCode
        decision = [string]$webProbeRefreshSummary.decision
        sourceCount = [int]$webProbeRefreshSummary.sourceCount
        fetchedCount = [int]$webProbeRefreshSummary.fetchedCount
        missingSignalCount = [int]$webProbeRefreshSummary.missingSignalCount
        secretHits = ([int]$webProbeRefresh.SecretHits + [int]$webProbeRefreshSummary.secretHits)
    },
    [ordered]@{
        name = 'trace_memory_runtime_proof'
        decision = [string]$traceMemoryRuntimeProof.decision
        requireTraceMemory = [bool]$traceMemoryRuntimeProof.requireTraceMemory
        seedsSelfProbe = [bool]$traceMemoryRuntimeProof.seedsSelfProbe
        checkpointScenario = [string]$traceMemoryRuntimeProof.checkpointScenario
        secretHits = [int]$traceMemoryRuntimeProof.secretHits
    },
    [ordered]@{
        name = 'completion_audit_preflight'
        exitCode = [int]$completionAuditPreflight.ExitCode
        secretHits = [int]$completionAuditPreflight.SecretHits
        processExecuted = [bool]$completionAuditBootstrap.preflightExecuted
        evidenceSource = if ($completionAuditBootstrap.preflightExecuted) { 'fresh_preflight' } else { 'canonical_current_safe' }
        reason = [string]$completionAuditBootstrap.reason
    },
    [ordered]@{
        name = 'source_health'
        exitCode = [int]$sourceHealth.ExitCode
        strictEvidenceAdjustedScore = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.strictEvidenceAdjustedScore) { [double]$sourceHealthJson.strictEvidenceAdjustedScore } else { 0.0 }
        activeRiskCount = if ($null -ne $sourceHealthJson -and $null -ne $sourceHealthJson.activeRiskCount) { [int]$sourceHealthJson.activeRiskCount } else { 0 }
        nextSingleAction = if ($null -ne $sourceHealthJson) { [string]$sourceHealthJson.nextSingleAction } else { '' }
        secretHits = [int]$sourceHealth.SecretHits
    },
    [ordered]@{
        name = 'completion_audit'
        exitCode = [int]$completionAudit.ExitCode
        secretHits = [int]$completionAudit.SecretHits
    },
    [ordered]@{
        name = 'final_decision'
        decision = $decision
        failureClassification = $summaryFailureClassification
        firstAction = $firstAction
        firstActionSource = $firstActionSource
        nextActionCount = $nextActionEntries.Count
        secretHits = $secretHits
    }
)

$breadcrumbTraceKeys = @(
    'goalNext.decision',
    'goalNext.failureClassification',
    'goalNext.firstAction',
    'goalNext.firstActionSource',
    'goalNext.previousBreadcrumb.present',
    'goalNext.toolSteps.count',
    'goalNext.traceMemoryRuntimeProof.requireTraceMemory',
    'goalNext.secretHits'
)
$breadcrumbMdc = [ordered]@{
    schemaVersion = 'awx.goal_next_auto.mdc.v1'
    nodeRole = 'desktop'
    topic = $Topic
    root = '<desktop-canonical-root>'
    decision = $decision
    failureClassification = $summaryFailureClassification
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousDecision = [string]$previousBreadcrumbSummary.decision
    previousFirstAction = [string]$previousBreadcrumbSummary.firstAction
}
$breadcrumbTrace = [ordered]@{
    schemaVersion = 'awx.goal_next_auto.trace.v1'
    keys = @($breadcrumbTraceKeys)
    mdcKeys = @('nodeRole', 'topic', 'root', 'decision', 'failureClassification', 'firstAction', 'firstActionSource')
    toolStepCount = @($breadcrumbToolSteps).Count
    nextActionCount = $nextActionEntries.Count
}
$breadcrumbRawTile = [ordered]@{
    schemaVersion = 'awx.goal_next_auto.raw_tile.v1'
    tileKind = 'goal_next_decision_breadcrumb'
    status = $decision
    blocker = $summaryFailureClassification
    bottleneck = $firstActionSource
    nextAction = $firstAction
    reuseSource = if ($previousBreadcrumbSummary.present) { 'previous_breadcrumb' } else { 'seed_timeline' }
    previousFirstAction = [string]$previousBreadcrumbSummary.firstAction
    evidence = [ordered]@{
        toolStepCount = @($breadcrumbToolSteps).Count
        nextActionCount = $nextActionEntries.Count
        secretHits = $secretHits
        sourceHealthExit = [int]$sourceHealth.ExitCode
        completionAuditExit = [int]$completionAudit.ExitCode
        supabaseDecision = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' }
        externalDecision = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' }
        computerUseDecision = if ($null -ne $computerUseSummary) { [string]$computerUseSummary.decision } else { 'missing' }
        browserUseDecision = if ($null -ne $browserUseSummary) { [string]$browserUseSummary.decision } else { 'missing' }
        traceMemoryRuntimeProofRequired = [bool]$traceMemoryRuntimeProof.requireTraceMemory
    }
}
$currentBreadcrumb = [ordered]@{
    schemaVersion = 'awx.goal_next_auto.breadcrumb.v1'
    generatedAt = $generatedAtText
    topic = $Topic
    root = '<desktop-canonical-root>'
    outputDir = Get-SafePacketString -Text $OutputDir -ProjectRoot $Root
    decision = $decision
    failureClassification = $summaryFailureClassification
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    uiProofRequired = [bool]$RequireUiProof
    supabaseProofRequired = [bool]$RequireSupabaseProof
    externalDispatchRequested = [bool]$ExternalDispatch
    webProbeRefreshRequested = [bool]$RefreshWebProbe
    previousBreadcrumb = $previousBreadcrumbSummary
    nextActionCount = $nextActionEntries.Count
    nextActionSources = @($nextActionSources)
    topActions = @($topActionEntries)
    toolSteps = @($breadcrumbToolSteps)
    trace = $breadcrumbTrace
    mdc = $breadcrumbMdc
    rawTile = $breadcrumbRawTile
    artifacts = [ordered]@{
        summary = Get-SafePacketString -Text $summaryPath -ProjectRoot $Root
        digest = Get-SafePacketString -Text $digestPath -ProjectRoot $Root
        commandPacket = Get-SafePacketString -Text $commandPacketPath -ProjectRoot $Root
        collectionPacket = Get-SafePacketString -Text $collectionPacketPath -ProjectRoot $Root
        nextActions = Get-SafePacketString -Text $nextActionsPath -ProjectRoot $Root
        fusion = Get-SafePacketString -Text $breadcrumbFusionPath -ProjectRoot $Root
    }
    secretHits = $secretHits
    rawSecretPatternHits = $secretHits
}
Append-JsonLineFile -Path $breadcrumbTimelinePath -Value $currentBreadcrumb
$breadcrumbFusion = New-BreadcrumbFusionSummary `
    -Rows (Read-JsonLineFileTail -Path $breadcrumbTimelinePath -MaxRows 25) `
    -GeneratedAtText $generatedAtText `
    -TailLimit 25
if ($RequireSupabaseProof -and $null -ne $supabaseExternalInputGate -and [string]$breadcrumbFusion.externalInputGate.status -ne 'external_input_needed') {
    $breadcrumbFusion['externalInputGate'] = $supabaseExternalInputGate
}
Write-JsonFile -Path $breadcrumbFusionPath -Value $breadcrumbFusion
$digestMarkdownLines.Add("- breadcrumbFusion.timelineRowsRead=$($breadcrumbFusion.timelineRowsRead) reusablePattern=$($breadcrumbFusion.reusablePattern) latestFirstAction=$($breadcrumbFusion.latestFirstAction)") | Out-Null
$digestMarkdownLines.Add("- externalInputGate.status=$($breadcrumbFusion.externalInputGate.status) source=$($breadcrumbFusion.externalInputGate.source) action=$($breadcrumbFusion.externalInputGate.action) repeated=$($breadcrumbFusion.externalInputGate.repeated) repeatCount=$($breadcrumbFusion.externalInputGate.repeatCount) localPatchJustified=$($breadcrumbFusion.externalInputGate.localPatchJustified) mutationAllowed=$($breadcrumbFusion.externalInputGate.mutationAllowed) secretHits=$($breadcrumbFusion.externalInputGate.secretHits) windowsAbsPathHits=$($breadcrumbFusion.externalInputGate.windowsAbsPathHits)") | Out-Null

Write-JsonFile -Path $summaryPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.summary.v1'
    generatedAt = $generatedAtText
    ok = ($decision -eq 'ok')
    decision = $decision
    failureClassification = $summaryFailureClassification
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    uiProofRequired = [bool]$RequireUiProof
    supabaseProofRequired = [bool]$RequireSupabaseProof
    externalDispatchRequested = [bool]$ExternalDispatch
    webProbeRefreshRequested = [bool]$RefreshWebProbe
    previousBreadcrumb = $previousBreadcrumbSummary
    externalInputGate = $breadcrumbFusion.externalInputGate
    nextActionCount = $nextActionEntries.Count
    nextActionSources = @($nextActionSources)
    topActions = @($topActionEntries)
    topic = $Topic
    root = $Root
    preflight = $preflight
    completionAuditInvocationCount = $completionAuditInvocationCount
    completionAuditPreflight = [ordered]@{
        executed = [bool]$completionAuditBootstrap.preflightExecuted
        reuseAllowed = [bool]$completionAuditBootstrap.reuseAllowed
        reason = [string]$completionAuditBootstrap.reason
        mode = [string]$completionAuditBootstrap.mode
        freshnessStatus = [string]$completionAuditBootstrap.freshnessStatus
        ageSeconds = [int]$completionAuditBootstrap.ageSeconds
        maxAgeSeconds = [int]$completionAuditBootstrap.maxAgeSeconds
        secretHits = [int]$completionAuditBootstrap.secretHits
    }
    env = [ordered]@{
        SUPABASE_PROJECT_REF_present = $envProjectRefPresent
        SUPABASE_ACCESS_TOKEN_present = $envAccessTokenPresent
    }
    supabaseSmokeExit = $supabaseSmoke.ExitCode
    supabaseSmoke = $supabaseSmokeSummary
    supabaseApplyExit = $supabaseApply.ExitCode
    externalApplyExit = $externalApply.ExitCode
    desktopControlLoopExit = $desktopControlLoop.ExitCode
    desktopControlLoop = [ordered]@{
        parsed = ($null -ne $desktopControlLoopJson)
        ok = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.ok } else { $false }
        localReady = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.localReady } else { $false }
        completionReady = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.completionReady } else { $false }
        desktopFinalProof = if ($null -ne $desktopControlLoopJson) { [string]$desktopControlLoopJson.desktopFinalProof } else { 'evidence_needed' }
        externalEvidenceComplete = if ($null -ne $desktopControlLoopJson) { [bool]$desktopControlLoopJson.externalEvidenceComplete } else { $false }
        nextActionCount = $desktopControlLoopNextActionCount
        dispatchWriteRequested = $desktopControlLoopRequestsDispatch
        producerKitWriteRequested = $desktopControlLoopRequestsProducerKit
        producerKitOk = $desktopProducerKitOk
        completionAuditRunRequested = if ($null -ne $desktopControlLoopJson -and $null -ne $desktopControlLoopJson.completionAuditRunRequested) { [bool]$desktopControlLoopJson.completionAuditRunRequested } else { $true }
        completionAuditExecuted = if ($null -ne $desktopControlLoopJson -and $null -ne $desktopControlLoopJson.completionAuditExecuted) { [bool]$desktopControlLoopJson.completionAuditExecuted } else { $true }
        completionAuditEvidenceSource = if ($null -ne $desktopControlLoopJson) { Get-SafePacketString -Text ([string]$desktopControlLoopJson.completionAuditEvidenceSource) -ProjectRoot $Root } else { '' }
        dispatchArtifactCount = $desktopDispatchArtifactCount
        dispatchIntegrityOk = $desktopDispatchIntegrityOk
        dispatchFailReason = $desktopDispatchFailReason
    }
    computerUse = $computerUseSummary
    browserUse = $browserUseSummary
    peerEvidenceBus = $peerEvidenceBusSummary
    webProbeRefresh = $webProbeRefreshSummary
    traceMemoryRuntimeProof = $traceMemoryRuntimeProof
    supabaseApply = $supabaseApplyArtifactSummary
    externalApply = $externalApplyArtifactSummary
    sourceHealth = $sourceHealthArtifactSummary
    sourceHealthExit = $sourceHealth.ExitCode
    completionAuditExit = $completionAudit.ExitCode
    secretHits = $secretHits
    rawSecretPatternHits = $secretHits
    artifacts = [ordered]@{
        supabaseApplySummary = $supabaseApplySummaryPath
        externalApplySummary = $externalApplySummaryPath
        nextActions = $nextActionsPath
        commandPacket = $commandPacketPath
        commandPacketMarkdown = $commandPacketMarkdownPath
        collectionPacket = $collectionPacketPath
        collectionPacketMarkdown = $collectionPacketMarkdownPath
        digest = $digestPath
        digestMarkdown = $digestMarkdownPath
        breadcrumbTimeline = $breadcrumbTimelinePath
        breadcrumbFusion = $breadcrumbFusionPath
        desktopControlLoop = $desktopControlLoopPath
        webProbeRefresh = $webProbeRefreshPath
        sourceHealth = Join-Path $OutputDir 'source-health-scorecard.json'
        completionAuditPreflight = if ($completionAuditBootstrap.preflightExecuted) { $completionAuditPreflightOutputPath } else { '' }
        completionAuditPacket = $completionAuditPacketOutputPath
        completionAudit = Join-Path $OutputDir 'awx-mcp-completion-audit.result.json'
    }
    logs = [ordered]@{
        supabaseSmoke = $supabaseSmoke.LogPath
        supabaseApply = $supabaseApply.LogPath
        externalApply = $externalApply.LogPath
        desktopControlLoop = $desktopControlLoop.LogPath
        webProbeRefresh = $webProbeRefresh.LogPath
        sourceHealth = $sourceHealth.LogPath
        completionAudit = $completionAudit.LogPath
    }
})

Write-JsonFile -Path $digestPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.digest.v1'
    generatedAt = $generatedAtText
    decision = $decision
    topic = $Topic
    root = $Root
    latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
    latestStaleAfterMinutes = $latestStaleAfterMinutes
    latestExpiresAt = $latestExpiresAt
    staleLatest = $staleLatest
    summaryPath = $summaryPath
    nextActionsPath = $nextActionsPath
    commandPacketPath = $commandPacketPath
    commandPacketMarkdownPath = $commandPacketMarkdownPath
    collectionPacketPath = $collectionPacketPath
    collectionPacketMarkdownPath = $collectionPacketMarkdownPath
    digestMarkdownPath = $digestMarkdownPath
    nextActionEntryCount = $nextActionEntries.Count
    commandPacketCommandCount = $commandPacket.commandCount
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousBreadcrumb = $previousBreadcrumbSummary
    externalInputGate = $breadcrumbFusion.externalInputGate
    sourceActionCounts = $sourceActionCounts
    topActions = $topActionEntries
    supabaseDecision = if ($null -ne $supabaseApplySummary) { [string]$supabaseApplySummary.decision } else { 'evidence_needed' }
    supabaseSmoke = $supabaseSmokeSummary
    supabaseMcpConfig = $supabaseMcpConfig
    supabaseApply = $supabaseApplyArtifactSummary
    externalDecision = if ($null -ne $externalApplySummary) { [string]$externalApplySummary.decision } else { 'evidence_needed' }
    externalApply = $externalApplyArtifactSummary
    sourceHealth = $sourceHealthArtifactSummary
    sourceHealthProducerQueue = $sourceHealthProducerQueue
    desktopFinalProof = if ($null -ne $desktopControlLoopJson) { [string]$desktopControlLoopJson.desktopFinalProof } else { 'evidence_needed' }
    desktopDispatchWriteRequested = $desktopControlLoopRequestsDispatch
    desktopDispatchArtifactCount = $desktopDispatchArtifactCount
    desktopDispatchIntegrityOk = $desktopDispatchIntegrityOk
    desktopDispatchFailReason = $desktopDispatchFailReason
    desktopProducerKitWriteRequested = $desktopControlLoopRequestsProducerKit
    desktopProducerKitOk = $desktopProducerKitOk
    computerUseOk = [bool]$computerUseSummary.ok
    computerUseReachable = [bool]$computerUseSummary.reachable
    computerUseAppCount = [int]$computerUseSummary.appCount
    computerUseDecision = [string]$computerUseSummary.decision
    computerUse = $computerUseSummary
    browserUseOk = [bool]$browserUseSummary.ok
    browserUseReachable = [bool]$browserUseSummary.reachable
    browserUseLocalhost = [bool]$browserUseSummary.localhost
    browserUsePublicDomain = if ($null -ne $browserUseSummary.publicDomain) { [bool]$browserUseSummary.publicDomain } else { $false }
    browserUseTargetAccepted = if ($null -ne $browserUseSummary.targetAccepted) { [bool]$browserUseSummary.targetAccepted } else { $false }
    browserUseTargetHost = if (-not [string]::IsNullOrWhiteSpace([string]$browserUseSummary.targetHost)) { Get-SafePacketString -Text ([string]$browserUseSummary.targetHost) -ProjectRoot $Root } else { 'unknown' }
    browserUseDecision = [string]$browserUseSummary.decision
    browserUse = $browserUseSummary
    peerEvidenceBus = $peerEvidenceBusSummary
    webProbeRefresh = $webProbeRefreshSummary
    traceMemoryRuntimeProof = $traceMemoryRuntimeProof
    secretHits = $secretHits
    breadcrumbTimelinePath = $breadcrumbTimelinePath
    breadcrumbFusionPath = $breadcrumbFusionPath
    breadcrumbFusion = $breadcrumbFusion
})
Write-TextFile -Path $digestMarkdownPath -Value (($digestMarkdownLines | ForEach-Object { Redact-SensitiveText ([string]$_) }) -join "`n")

New-Item -ItemType Directory -Force -Path $defaultSmokeRoot | Out-Null
$latestPointerPath = Join-Path $defaultSmokeRoot 'goal-next-auto.latest.json'
Write-JsonFile -Path $latestPointerPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.latest.v1'
    generatedAt = $generatedAtText
    decision = $decision
    requestMode = $requestMode
    topic = $Topic
    root = $Root
    outputDir = $OutputDir
    latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
    latestStaleAfterMinutes = $latestStaleAfterMinutes
    latestExpiresAt = $latestExpiresAt
    staleLatest = $staleLatest
    summaryPath = $summaryPath
    nextActionsPath = $nextActionsPath
    commandPacketPath = $commandPacketPath
    commandPacketMarkdownPath = $commandPacketMarkdownPath
    collectionPacketPath = $collectionPacketPath
    collectionPacketMarkdownPath = $collectionPacketMarkdownPath
    digestPath = $digestPath
    digestMarkdownPath = $digestMarkdownPath
    breadcrumbTimelinePath = $breadcrumbTimelinePath
    breadcrumbFusionPath = $breadcrumbFusionPath
    nextActionEntryCount = $nextActionEntries.Count
    commandPacketCommandCount = $commandPacket.commandCount
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousBreadcrumb = $previousBreadcrumbSummary
    externalInputGate = $breadcrumbFusion.externalInputGate
    computerUseOk = [bool]$computerUseSummary.ok
    computerUseReachable = [bool]$computerUseSummary.reachable
    computerUseAppCount = [int]$computerUseSummary.appCount
    computerUseDecision = [string]$computerUseSummary.decision
    computerUse = $computerUseSummary
    browserUseOk = [bool]$browserUseSummary.ok
    browserUseReachable = [bool]$browserUseSummary.reachable
    browserUseLocalhost = [bool]$browserUseSummary.localhost
    browserUsePublicDomain = if ($null -ne $browserUseSummary.publicDomain) { [bool]$browserUseSummary.publicDomain } else { $false }
    browserUseTargetAccepted = if ($null -ne $browserUseSummary.targetAccepted) { [bool]$browserUseSummary.targetAccepted } else { $false }
    browserUseTargetHost = if (-not [string]::IsNullOrWhiteSpace([string]$browserUseSummary.targetHost)) { Get-SafePacketString -Text ([string]$browserUseSummary.targetHost) -ProjectRoot $Root } else { 'unknown' }
    browserUseDecision = [string]$browserUseSummary.decision
    browserUse = $browserUseSummary
    peerEvidenceBus = $peerEvidenceBusSummary
    webProbeRefresh = $webProbeRefreshSummary
    traceMemoryRuntimeProof = $traceMemoryRuntimeProof
    sourceHealthProducerQueue = $sourceHealthProducerQueue
    supabaseSmoke = $supabaseSmokeSummary
    supabaseMcpConfig = $supabaseMcpConfig
    supabaseApply = $supabaseApplyArtifactSummary
    externalApply = $externalApplyArtifactSummary
    sourceHealth = $sourceHealthArtifactSummary
    secretHits = $secretHits
})

$statusPath = Join-Path $defaultSmokeRoot 'goal-next-auto.status.json'
Write-JsonFile -Path $statusPath -Value ([ordered]@{
    schemaVersion = 'awx.goal_next_auto.status.v1'
    generatedAt = $generatedAtText
    root = $Root
    latestPath = $latestPointerPath
    statusPath = $statusPath
    latestDecision = $decision
    statusDecision = $decision
    failureClassification = if ($decision -eq 'ok') { '' } else { $decision }
    latestGeneratedAt = $generatedAtText
    latestGeneratedAtAgeMinutes = $latestGeneratedAtAgeMinutes
    latestStaleAfterMinutes = $latestStaleAfterMinutes
    staleLatest = $staleLatest
    requestMode = $requestMode
    latestRequestMode = $requestMode
    latestModeMatches = $true
    summaryPath = $summaryPath
    nextActionsPath = $nextActionsPath
    commandPacketPath = $commandPacketPath
    commandPacketMarkdownPath = $commandPacketMarkdownPath
    collectionPacketPath = $collectionPacketPath
    collectionPacketMarkdownPath = $collectionPacketMarkdownPath
    digestPath = $digestPath
    digestMarkdownPath = $digestMarkdownPath
    breadcrumbTimelinePath = $breadcrumbTimelinePath
    nextActionEntryCount = $nextActionEntries.Count
    nextActionCount = $nextActionEntries.Count
    nextActionSources = @($nextActionSources)
    topActions = @($topActionEntries)
    firstAction = $firstAction
    firstActionSource = $firstActionSource
    previousBreadcrumb = $previousBreadcrumbSummary
    externalInputGate = $breadcrumbFusion.externalInputGate
    computerUseOk = [bool]$computerUseSummary.ok
    computerUseReachable = [bool]$computerUseSummary.reachable
    computerUseAppCount = [int]$computerUseSummary.appCount
    computerUseDecision = [string]$computerUseSummary.decision
    computerUse = $computerUseSummary
    browserUseOk = [bool]$browserUseSummary.ok
    browserUseReachable = [bool]$browserUseSummary.reachable
    browserUseLocalhost = [bool]$browserUseSummary.localhost
    browserUsePublicDomain = if ($null -ne $browserUseSummary.publicDomain) { [bool]$browserUseSummary.publicDomain } else { $false }
    browserUseTargetAccepted = if ($null -ne $browserUseSummary.targetAccepted) { [bool]$browserUseSummary.targetAccepted } else { $false }
    browserUseTargetHost = if (-not [string]::IsNullOrWhiteSpace([string]$browserUseSummary.targetHost)) { Get-SafePacketString -Text ([string]$browserUseSummary.targetHost) -ProjectRoot $Root } else { 'unknown' }
    browserUseDecision = [string]$browserUseSummary.decision
    browserUse = $browserUseSummary
    peerEvidenceBus = $peerEvidenceBusSummary
    webProbeRefresh = $webProbeRefreshSummary
    traceMemoryRuntimeProof = $traceMemoryRuntimeProof
    supabaseSmoke = $supabaseSmokeSummary
    supabaseMcpConfig = $supabaseMcpConfig
    supabaseApply = $supabaseApplyArtifactSummary
    externalApply = $externalApplyArtifactSummary
    sourceHealth = $sourceHealthArtifactSummary
    secretHits = $secretHits
})

if ($blockerStatePath -and $decision -ne 'secret-leak-risk') {
    $blockerReasons = @($decision, [string]$preflight.failureClassification, (Get-SafePacketString -Text ([string]$firstAction) -ProjectRoot $Root)) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $blockerScopes = @($preflight.holdScope)
    if ($blockerScopes.Count -eq 0 -and $decision -eq 'evidence_needed') { $blockerScopes = @('unmet-acceptance-evidence') }
    Write-JsonFile -Path $blockerStatePath -Value ([ordered]@{
        schemaVersion = 'awx.goal_next_auto.blocker_state.v1'
        decision = $decision
        inputIdentity = $blockerInputIdentity
        fingerprint = (Get-AwxBlockerFingerprint -Reasons $blockerReasons -Scopes $blockerScopes -SourceIdentity $blockerInputIdentity -ResourceIdentity 'goal-next')
        reasons = @($blockerReasons)
        holdScope = $blockerScopes
        firstBlockingRule = if ($preflight.failureClassification) { $preflight.failureClassification } else { $decision }
        blockingEvidence = @($blockerReasons)
        independentWorkCompleted = @('read-only-preflight','requested-audit')
        repositoryWideHold = $false
        unchangedCount = 0
        lastObservedAt = [DateTimeOffset]::UtcNow.ToString('o')
    })
}
Write-Host "[AWX][goal-next] decision=$decision supabaseSmokeExit=$($supabaseSmoke.ExitCode) supabaseApplyExit=$($supabaseApply.ExitCode) externalApplyExit=$($externalApply.ExitCode) desktopControlLoopExit=$($desktopControlLoop.ExitCode) webProbeRefreshExit=$($webProbeRefresh.ExitCode) sourceHealthExit=$($sourceHealth.ExitCode) completionAuditExit=$($completionAudit.ExitCode) secretHits=$secretHits summary=$summaryPath"

if ($decision -eq 'secret-leak-risk') {
    exit 4
}
if ($decision -eq 'evidence_needed') {
    exit 2
}

exit 0
