param(
    [ValidateSet("All", "ProducerKit", "SecretPattern", "ArchiveLoop", "CompletionAudit", "ExternalEvidence", "AgentDb", "Harmony", "Prompt")]
    [string]$Suite = "All"
)

$ErrorActionPreference = "Continue"

$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$Toolbox = Join-Path $Root "scripts\awx_mcp_toolbox.py"
$Launcher = Join-Path $Root "scripts\awx_mcp_toolbox.ps1"
$NodeSmoke = Join-Path $Root "scripts\awx_mcp_node_smoke.py"
$ProducerHandoff = Join-Path $Root "scripts\awx_mcp_producer_handoff.py"
$CompletionAudit = Join-Path $Root "scripts\awx_mcp_completion_audit.py"
$McpStdioServer = Join-Path $Root "scripts\awx_mcp_stdio_server.py"
$McpNodeSetup = Join-Path $Root "scripts\awx_mcp_node_setup.py"
$Manifest = Join-Path $Root "main\resources\mcp\awx-control-tower-tools.json"
$Failures = 0
$CompletionAuditIncludeTestDispatchEnv = "AWX_COMPLETION_AUDIT_INCLUDE_TEST_DISPATCH"
$SecretLikePattern = "(?<![A-Za-z0-9_])sk-[A-Za-z0-9_-]{20,}|AIza[A-Za-z0-9_-]{20,}|gsk_[A-Za-z0-9_-]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}"

function Write-Pass {
    param([Parameter(Mandatory = $true)][string]$Name)
    Write-Host "[awx-mcp-toolbox-test][PASS] $Name"
}

function Write-Fail {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Message
    )
    $script:Failures++
    Write-Host "[awx-mcp-toolbox-test][FAIL] $Name :: $Message"
}

function Assert-True {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Condition,
        [string]$Message = "assertion failed"
    )
    if ($Condition) { Write-Pass $Name } else { Write-Fail $Name $Message }
}

function Test-HasSecretLikeValue {
    param([AllowNull()][string]$Text)
    return [string]$Text -match $script:SecretLikePattern
}

function Test-SecretLikePatternIncludesSupabaseKeys {
    $openAiLike = "sk-" + ("D" * 20)
    $secretKey = "sb_secret_" + ("A" * 10)
    $publishableKey = "sb_publishable_" + ("B" * 10)
    $accessToken = "sbp_" + ("C" * 10)
    Assert-True "PowerShell secret-like pattern catches standalone sk token" (Test-HasSecretLikeValue $openAiLike) "standalone sk token prefix was not matched"
    Assert-True "PowerShell secret-like pattern ignores risk prose" (-not (Test-HasSecretLikeValue "find-duplicate-risk-or-decisive-counter-evidence")) "safe risk prose was matched as a secret"
    Assert-True "PowerShell secret-like pattern catches Supabase sb keys" ((Test-HasSecretLikeValue $secretKey) -and (Test-HasSecretLikeValue $publishableKey) -and (Test-HasSecretLikeValue $accessToken)) "Supabase key prefixes were not matched by the shared test pattern"

    Assert-True "completion audit script exists for secret pattern parity" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not ((Test-Path -LiteralPath $CompletionAudit) -and (Get-Command python -ErrorAction SilentlyContinue))) {
        return
    }

    $testScript = Join-Path ([IO.Path]::GetTempPath()) ("awx-completion-audit-secret-pattern-" + [guid]::NewGuid().ToString("N") + ".py")
    @'
import importlib.util
import pathlib
import sys

spec = importlib.util.spec_from_file_location("awx_completion_audit", pathlib.Path(sys.argv[1]))
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)
assert module.SECRET_PATTERN.search("sk-" + ("D" * 20)) is not None
assert module.SECRET_PATTERN.search("find-duplicate-risk-or-decisive-counter-evidence") is None
'@ | Set-Content -LiteralPath $testScript -Encoding UTF8

    try {
        $lines = & python $testScript $CompletionAudit 2>&1 | ForEach-Object { $_.ToString() }
        Assert-True "completion audit secret-like pattern ignores risk prose" ($LASTEXITCODE -eq 0) "output=$($lines -join "`n")"
    } finally {
        Remove-Item -LiteralPath $testScript -Force -ErrorAction SilentlyContinue
    }
}

function Test-ReadableFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    try {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf -ErrorAction Stop)) { return $false }
        $null = Get-Content -LiteralPath $Path -TotalCount 1 -ErrorAction Stop
        return $true
    } catch {
        return $false
    }
}

function Get-SafeRawContent {
    param([Parameter(Mandatory = $true)][string]$Path)
    try {
        if (-not (Test-Path -LiteralPath $Path -PathType Leaf -ErrorAction Stop)) { return "" }
        return Get-Content -LiteralPath $Path -Raw -ErrorAction Stop
    } catch {
        return ""
    }
}

function Get-ToolInvocationCommands {
    param(
        [Parameter(Mandatory = $true)]$Commands,
        [Parameter(Mandatory = $true)][string]$ScriptName
    )
    $escaped = [regex]::Escape($ScriptName)
    return @($Commands | Where-Object { [string]$_ -match "^\s*python3?\s+.*$escaped\b" })
}

function Get-ToolInvocationIndex {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$ScriptName
    )
    $escaped = [regex]::Escape($ScriptName)
    $match = [regex]::Match($Text, "(?m)^\s*python3?\s+.*$escaped\b")
    if ($match.Success) { return $match.Index }
    return -1
}

function Get-SmokeJsonCheckIndex {
    param([Parameter(Mandatory = $true)][string]$Text)
    $match = [regex]::Match($Text, "(?m)^\s*python3?\s+-c\s+.*json\.load")
    if ($match.Success) { return $match.Index }
    return -1
}

function Invoke-ToolboxJson {
    param(
        [Parameter(Mandatory = $true)][string]$ToolName,
        [Parameter(Mandatory = $true)]$Payload
    )
    $json = $Payload | ConvertTo-Json -Depth 20 -Compress
    $lines = $json | python $script:Toolbox $ToolName 2>&1 | ForEach-Object { $_.ToString() }
    $exitCode = $LASTEXITCODE
    return [pscustomobject]@{
        ExitCode = $exitCode
        Raw = ($lines -join "`n")
        Json = if ($exitCode -eq 0) { ($lines -join "`n") | ConvertFrom-Json } else { $null }
    }
}

function Invoke-LauncherJson {
    param(
        [Parameter(Mandatory = $true)][string]$ToolName,
        [Parameter(Mandatory = $true)]$Payload
    )
    $json = $Payload | ConvertTo-Json -Depth 20 -Compress
    $lines = $json | powershell -NoProfile -ExecutionPolicy Bypass -File $script:Launcher -Tool $ToolName -Root $script:Root 2>&1 | ForEach-Object { $_.ToString() }
    $exitCode = $LASTEXITCODE
    return [pscustomobject]@{
        ExitCode = $exitCode
        Raw = ($lines -join "`n")
        Json = if ($exitCode -eq 0) { ($lines -join "`n") | ConvertFrom-Json } else { $null }
    }
}

function New-ValidNodeSmokeSteps {
    $steps = @(
        @{ toolName = "source_scan"; ok = $true; decision = "read_only_probe"; failReason = ""; outputCount = 1 },
        @{ toolName = "agent_db_snapshot"; ok = $false; decision = "agent_db_snapshot_unavailable_with_local_fallback"; failReason = "URLError"; outputCount = 0; localFallbackPresent = $true },
        @{ toolName = "trace_snapshot_probe"; ok = $false; decision = "trace_snapshot_unavailable_with_local_fallback"; failReason = "URLError"; outputCount = 0; localFallbackPresent = $true },
        @{ toolName = "supabase_context_probe"; ok = $true; decision = "supabase_context_probe"; failReason = ""; outputCount = 0 },
        @{ toolName = "supabase_schema_snapshot"; ok = $true; decision = "supabase_schema_snapshot_evidence_needed"; failReason = ""; outputCount = 0 },
        @{ toolName = "archive_search"; ok = $true; decision = "archive_search"; failReason = ""; outputCount = 1 },
        @{ toolName = "patch_plan"; ok = $true; decision = "plan_only"; failReason = ""; outputCount = 1 },
        @{ toolName = "patch_render"; ok = $true; decision = "render_bundle_contract"; failReason = ""; outputCount = 1 },
        @{ toolName = "boot_verify"; ok = $true; decision = "commands_only"; failReason = ""; outputCount = 5 },
        @{ toolName = "build_error_mine"; ok = $true; decision = "build_log_mined"; failReason = ""; outputCount = 1 },
        @{ toolName = "run_pipeline"; ok = $true; decision = "pipeline_probe"; failReason = ""; outputCount = 1 },
        @{ toolName = "archive_restore"; ok = $false; decision = "restore_target_blocked"; failReason = "smb-conflict-risk"; outputCount = 0 }
    )
    foreach ($step in $steps) {
        $step.exitCode = 0
        $step.elapsedMs = 0
        if (-not $step.ContainsKey("localFallbackPresent")) {
            $step.localFallbackPresent = $false
        }
        $step.evidence_needed = ""
    }
    return $steps
}

function Invoke-NodeSmokeJson {
    param(
        [Parameter(Mandatory = $true)][string]$NodeRole,
        [Parameter(Mandatory = $true)][string]$RootPath,
        [string]$CanonicalRootPath = $script:Root
    )
    $lines = python $script:NodeSmoke --root $RootPath --canonical-root $CanonicalRootPath --node-role $NodeRole --query "mcp smoke safe patch" 2>&1 | ForEach-Object { $_.ToString() }
    $exitCode = $LASTEXITCODE
    $raw = ($lines -join "`n")
    return [pscustomobject]@{
        ExitCode = $exitCode
        Raw = $raw
        Json = try { $raw | ConvertFrom-Json } catch { $null }
    }
}

function Invoke-McpNodeSetupJson {
    param(
        [Parameter(Mandatory = $true)][string]$NodeRole,
        [Parameter(Mandatory = $true)][string]$SourceRoot,
        [Parameter(Mandatory = $true)][string]$CanonicalRoot,
        [Parameter(Mandatory = $true)][string]$OutputPath,
        [string]$AuditLog = ""
    )
    $args = @(
        $script:McpNodeSetup,
        "--node-role", $NodeRole,
        "--source-root", $SourceRoot,
        "--canonical-root", $CanonicalRoot,
        "--output", $OutputPath
    )
    if ($AuditLog) {
        $args += @("--audit-log", $AuditLog)
    }
    $lines = & python @args 2>&1 | ForEach-Object { $_.ToString() }
    $exitCode = $LASTEXITCODE
    $raw = ($lines -join "`n")
    return [pscustomobject]@{
        ExitCode = $exitCode
        Raw = $raw
        Json = try { $raw | ConvertFrom-Json } catch { $null }
    }
}

function Invoke-ProducerHandoffJson {
    param(
        [Parameter(Mandatory = $true)][string]$SourceRoot,
        [Parameter(Mandatory = $true)][string]$PatchDropRoot,
        [Parameter(Mandatory = $true)][string]$PathSpec,
        [string]$Topic = "mcp-handoff-test",
        [string]$AuditLog = "",
        [string]$ProducerScriptPath = "",
        [string]$ProducerCommandHash = ""
    )
    $producerScript = if ([string]::IsNullOrWhiteSpace($ProducerScriptPath)) {
        $localProducerScript = Join-Path $SourceRoot "__patch_drop__\producer_bundle.py"
        $sourceRootLooksUnc = $SourceRoot.TrimStart().StartsWith("\\")
        if ((-not $sourceRootLooksUnc) -and (Test-Path -LiteralPath $SourceRoot -PathType Container -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath $localProducerScript -ErrorAction SilentlyContinue)) {
            New-Item -ItemType Directory -Force -Path (Split-Path -Parent $localProducerScript) | Out-Null
            Copy-Item -LiteralPath (Join-Path $script:Root "__patch_drop__\producer_bundle.py") -Destination $localProducerScript -Force
        }
        $localProducerScript
    } else {
        $ProducerScriptPath
    }
    $args = @(
        $script:ProducerHandoff,
        "--source-root", $SourceRoot,
        "--canonical-root", $script:Root,
        "--patchdrop-root", $PatchDropRoot,
        "--producer-script", $producerScript,
        "--node-role", "macmini",
        "--topic", $Topic,
        "--pathspec", $PathSpec
    )
    if ($AuditLog) {
        $args += @("--audit-log", $AuditLog)
    }
    if ($ProducerCommandHash) {
        $args += @("--producer-command-hash", $ProducerCommandHash)
    }
    $lines = & python @args 2>&1 | ForEach-Object { $_.ToString() }
    $exitCode = $LASTEXITCODE
    $raw = ($lines -join "`n")
    return [pscustomobject]@{
        ExitCode = $exitCode
        Raw = $raw
        Json = try { $raw | ConvertFrom-Json } catch { $null }
    }
}

function Set-CompletionAuditIncludeTestDispatch {
    param([AllowNull()][string]$PreviousValue)
    if ($null -eq $PreviousValue) {
        Remove-Item -LiteralPath "Env:\$script:CompletionAuditIncludeTestDispatchEnv" -ErrorAction SilentlyContinue
    } else {
        Set-Item -LiteralPath "Env:\$script:CompletionAuditIncludeTestDispatchEnv" -Value $PreviousValue
    }
}

function Invoke-WithCompletionAuditTestDispatch {
    param([Parameter(Mandatory = $true)][scriptblock]$Script)
    $previousValue = [Environment]::GetEnvironmentVariable($script:CompletionAuditIncludeTestDispatchEnv, "Process")
    try {
        Set-Item -LiteralPath "Env:\$script:CompletionAuditIncludeTestDispatchEnv" -Value "1"
        & $Script
    } finally {
        Set-CompletionAuditIncludeTestDispatch $previousValue
    }
}

function Test-IsLiveDispatchFixtureSlug {
    param([AllowNull()][string]$Slug)
    if ([string]::IsNullOrWhiteSpace($Slug)) { return $false }
    foreach ($prefix in @(
        "external-proof-test-",
        "handoff-hash-test-",
        "janitor-test-",
        "missing-handoff-test-",
        "stale-node-smoke-"
    )) {
        if ($Slug.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $true
        }
    }
    return $false
}

function Remove-LiveDispatchFixtureArtifacts {
    param([string]$TopicSlug = "")
    $dispatchDir = Join-Path $script:Root "__patch_drop__\dispatch"
    if (-not (Test-Path -LiteralPath $dispatchDir -PathType Container -ErrorAction SilentlyContinue)) {
        return
    }

    if (-not [string]::IsNullOrWhiteSpace($TopicSlug)) {
        if (-not (Test-IsLiveDispatchFixtureSlug $TopicSlug)) { return }
        Get-ChildItem -LiteralPath $dispatchDir -File -Filter "$TopicSlug-*" -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
        return
    }

    Get-ChildItem -LiteralPath $dispatchDir -File -ErrorAction SilentlyContinue |
        Where-Object {
            $name = $_.Name
            Test-IsLiveDispatchFixtureSlug (($name -replace "-desktop-dispatch\.json$", "") -replace "-macmini\.commands\.txt$", "" -replace "-notebook\.commands\.txt$", "" -replace "-desktop-intake\.ps1$", "" -replace "-handoff\.md$", "")
        } |
        Remove-Item -Force -ErrorAction SilentlyContinue
}

function Assert-NoLiveDispatchFixtureArtifacts {
    $dispatchDir = Join-Path $script:Root "__patch_drop__\dispatch"
    if (-not (Test-Path -LiteralPath $dispatchDir -PathType Container -ErrorAction SilentlyContinue)) {
        Assert-True "live dispatch has no test fixture artifacts" $true "dispatch dir missing"
        return
    }
    $leftovers = @(
        Get-ChildItem -LiteralPath $dispatchDir -File -ErrorAction SilentlyContinue |
            Where-Object {
                $base = $_.Name -replace "-desktop-dispatch\.json$", ""
                $base = $base -replace "-macmini\.commands\.txt$", ""
                $base = $base -replace "-notebook\.commands\.txt$", ""
                $base = $base -replace "-desktop-intake\.ps1$", ""
                $base = $base -replace "-handoff\.md$", ""
                Test-IsLiveDispatchFixtureSlug $base
            }
    )
    Assert-True "live dispatch has no test fixture artifacts" ($leftovers.Count -eq 0) "leftovers=$($leftovers.Name -join ',')"
}

function Invoke-CompletionAuditJson {
    param([switch]$IncludeTestDispatch)
    $previousValue = [Environment]::GetEnvironmentVariable($script:CompletionAuditIncludeTestDispatchEnv, "Process")
    try {
        if ($IncludeTestDispatch) {
            Set-Item -LiteralPath "Env:\$script:CompletionAuditIncludeTestDispatchEnv" -Value "1"
        } else {
            Remove-Item -LiteralPath "Env:\$script:CompletionAuditIncludeTestDispatchEnv" -ErrorAction SilentlyContinue
        }
        $lines = python $script:CompletionAudit --root $script:Root 2>&1 | ForEach-Object { $_.ToString() }
        $exitCode = $LASTEXITCODE
    } finally {
        Set-CompletionAuditIncludeTestDispatch $previousValue
    }
    return [pscustomobject]@{
        ExitCode = $exitCode
        Raw = ($lines -join "`n")
        Json = if ($exitCode -eq 0) { ($lines -join "`n") | ConvertFrom-Json } else { $null }
    }
}

function Get-DispatchPacketCountFromEvidence {
    param([AllowNull()][string]$Evidence)
    if ([string]::IsNullOrWhiteSpace($Evidence)) { return $null }
    $match = [regex]::Match($Evidence, "dispatchPacketCount=(\d+)")
    if (-not $match.Success) { return $null }
    return [int]$match.Groups[1].Value
}

function Invoke-McpStdioJson {
    param([Parameter(Mandatory = $true)]$Requests)
    $inputText = ($Requests | ForEach-Object { $_ | ConvertTo-Json -Depth 20 -Compress }) -join "`n"
    $lines = $inputText | python $script:McpStdioServer 2>&1 | ForEach-Object { $_.ToString() }
    $parsed = @()
    foreach ($line in $lines) {
        if ($line.Trim()) {
            try {
                $parsed += @($line | ConvertFrom-Json)
            } catch {
                $parsed += @([pscustomobject]@{ parseError = $_.Exception.Message; raw = $line })
            }
        }
    }
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Raw = ($lines -join "`n")
        Json = $parsed
    }
}

function Test-McpStdioServerListsToolsResourcesAndPrompts {
    Assert-True "mcp stdio server exists" (Test-Path -LiteralPath $McpStdioServer) "missing $McpStdioServer"
    if (-not (Test-Path -LiteralPath $McpStdioServer)) { return }

    $session = Invoke-McpStdioJson @(
        @{ jsonrpc = "2.0"; id = 1; method = "initialize"; params = @{} },
        @{ jsonrpc = "2.0"; id = 2; method = "tools/list"; params = @{} },
        @{ jsonrpc = "2.0"; id = 3; method = "resources/list"; params = @{} },
        @{ jsonrpc = "2.0"; id = 4; method = "prompts/list"; params = @{} },
        @{ jsonrpc = "2.0"; id = 5; method = "prompts/get"; params = @{ name = "macmini_patch_producer" } },
        @{ jsonrpc = "2.0"; id = 6; method = "prompts/get"; params = @{ name = "desktop_final_verifier" } }
    )
    Assert-True "mcp stdio list session exits zero" ($session.ExitCode -eq 0) "output=$($session.Raw)"
    if ($session.Json.Count -ge 6) {
        $init = @($session.Json | Where-Object { $_.id -eq 1 } | Select-Object -First 1)
        $tools = @($session.Json | Where-Object { $_.id -eq 2 } | Select-Object -First 1)
        $resources = @($session.Json | Where-Object { $_.id -eq 3 } | Select-Object -First 1)
        $prompts = @($session.Json | Where-Object { $_.id -eq 4 } | Select-Object -First 1)
        $macminiPrompt = @($session.Json | Where-Object { $_.id -eq 5 } | Select-Object -First 1)
        $desktopPrompt = @($session.Json | Where-Object { $_.id -eq 6 } | Select-Object -First 1)
        Assert-True "mcp stdio initialize names server" ($init.result.serverInfo.name -eq "awx-control-tower") "init=$($init | ConvertTo-Json -Depth 10 -Compress)"
        Assert-True "mcp stdio tools list source_scan" (@($tools.result.tools | ForEach-Object { $_.name }) -contains "source_scan") "tools=$($tools | ConvertTo-Json -Depth 10 -Compress)"
        Assert-True "mcp stdio tools list desktop_dispatch_packet" (@($tools.result.tools | ForEach-Object { $_.name }) -contains "desktop_dispatch_packet") "tools=$($tools | ConvertTo-Json -Depth 10 -Compress)"
        Assert-True "mcp stdio tools list desktop_control_loop" (@($tools.result.tools | ForEach-Object { $_.name }) -contains "desktop_control_loop") "tools=$($tools | ConvertTo-Json -Depth 10 -Compress)"
        Assert-True "mcp stdio tools list producer_kit_export" (@($tools.result.tools | ForEach-Object { $_.name }) -contains "producer_kit_export") "tools=$($tools | ConvertTo-Json -Depth 10 -Compress)"
        Assert-True "mcp stdio resources list manifest" (@($resources.result.resources | ForEach-Object { $_.name }) -contains "tool_manifest") "resources=$($resources | ConvertTo-Json -Depth 10 -Compress)"
        Assert-True "mcp stdio prompts list desktop verifier" (@($prompts.result.prompts | ForEach-Object { $_.name }) -contains "desktop_final_verifier") "prompts=$($prompts | ConvertTo-Json -Depth 10 -Compress)"
        $promptText = $macminiPrompt.result.messages[0].content.text
        Assert-True "mcp stdio prompt get gives producer-safe instructions" ([bool]($promptText -match "producer-local" -and $promptText -match "PatchDrop" -and $promptText -match "awx-control-tower-mcp-client.sample.json" -and $promptText -match "Desktop final")) "prompt=$promptText"
        $desktopPromptListEntry = @($prompts.result.prompts | Where-Object { $_.name -eq "desktop_final_verifier" } | Select-Object -First 1)
        Assert-True "mcp stdio desktop verifier list description is local-first" (
            $desktopPromptListEntry.Count -eq 1 -and
            [string]$desktopPromptListEntry[0].description -eq "source_scan -> patch_plan -> boot_verify"
        ) "promptEntry=$($desktopPromptListEntry | ConvertTo-Json -Depth 10 -Compress)"
        $desktopPromptText = [string]$desktopPrompt.result.messages[0].content.text
        $desktopPromptLines = @($desktopPromptText -split "`r?`n")
        $defaultLocalFlowLine = @($desktopPromptLines | Where-Object { $_.StartsWith("Default local flow:") } | Select-Object -First 1)
        $buildFailureFlowLine = @($desktopPromptLines | Where-Object { $_.StartsWith("Optional flow [build_failure_diagnostics]") } | Select-Object -First 1)
        $deepAnalysisFlowLine = @($desktopPromptLines | Where-Object { $_.StartsWith("Optional flow [deep_analysis]") } | Select-Object -First 1)
        $externalProducerFlowLine = @($desktopPromptLines | Where-Object { $_.StartsWith("Optional flow [external_producer_handoff]") } | Select-Object -First 1)
        Assert-True "mcp stdio desktop verifier renders compact default local flow" (
            $defaultLocalFlowLine.Count -eq 1 -and
            [string]$defaultLocalFlowLine[0] -eq "Default local flow: source_scan -> patch_plan -> boot_verify" -and
            [string]$defaultLocalFlowLine[0] -notmatch "build_error_mine|desktop_dispatch_packet|external_evidence_|peer_evidence_bus"
        ) "prompt=$desktopPromptText"
        Assert-True "mcp stdio desktop verifier mines build errors only after failed verification with a log" (
            $buildFailureFlowLine.Count -eq 1 -and
            [string]$buildFailureFlowLine[0] -eq "Optional flow [build_failure_diagnostics] when [verification_failed_with_log_path]: build_error_mine"
        ) "prompt=$desktopPromptText"
        Assert-True "mcp stdio desktop verifier keeps deep analysis demand-driven" (
            $deepAnalysisFlowLine.Count -eq 1 -and
            [string]$deepAnalysisFlowLine[0] -eq "Optional flow [deep_analysis] when [cross_subsystem_or_harmony_evidence_needed]: harmony_scan -> peer_evidence_bus"
        ) "prompt=$desktopPromptText"
        Assert-True "mcp stdio desktop verifier keeps producer handoff explicitly optional" (
            $externalProducerFlowLine.Count -eq 1 -and
            [string]$externalProducerFlowLine[0] -eq "Optional flow [external_producer_handoff] when [explicit_external_producer_handoff]: desktop_dispatch_packet -> external_evidence_intake -> external_evidence_audit"
        ) "prompt=$desktopPromptText"
        Assert-True "mcp stdio desktop verifier separates local and external completion proof" (
            $desktopPromptText.Contains("Desktop-only proof: run the local flow plus Desktop Gradle/source-governance; optional producer proof does not block completion.") -and
            $desktopPromptText.Contains("External producer proof: explicit handoff only; remains evidence_needed through dispatch/intake/audit, PatchDrop diff/secret/git-apply review, and Desktop Gradle/source-governance before applied.")
        ) "prompt=$desktopPromptText"
        $previousPromptTestRoot = $env:AWX_MCP_PROMPT_TEST_ROOT
        try {
            $env:AWX_MCP_PROMPT_TEST_ROOT = $script:Root
            $normalizationProbeScript = @'
import json
import os
import sys
from pathlib import Path

root = Path(os.environ["AWX_MCP_PROMPT_TEST_ROOT"])
sys.path.insert(0, str(root / "scripts"))
import awx_mcp_stdio_server as server

result = server.normalize_optional_flows([
    {"name": "null_flow", "when": "never", "flow": None},
    {"name": "string_flow", "when": "never", "flow": "ab"},
    {"name": "valid_flow", "when": "needed", "flow": ["source_scan", ""]},
])
print(json.dumps(result, sort_keys=True))
'@
            $normalizationProbeLines = $normalizationProbeScript | python - 2>&1 | ForEach-Object { $_.ToString() }
            $normalizationProbeExit = $LASTEXITCODE
        } finally {
            if ($null -eq $previousPromptTestRoot) {
                Remove-Item Env:\AWX_MCP_PROMPT_TEST_ROOT -ErrorAction SilentlyContinue
            } else {
                $env:AWX_MCP_PROMPT_TEST_ROOT = $previousPromptTestRoot
            }
        }
        $normalizedFlows = if ($normalizationProbeExit -eq 0) { @(($normalizationProbeLines -join "`n") | ConvertFrom-Json) } else { @() }
        Assert-True "mcp stdio optional flow normalization ignores malformed inner flow types" (
            $normalizationProbeExit -eq 0 -and
            $normalizedFlows.Count -eq 1 -and
            [string]$normalizedFlows[0].name -eq "valid_flow" -and
            @($normalizedFlows[0].flow).Count -eq 1 -and
            [string]$normalizedFlows[0].flow[0] -eq "source_scan"
        ) "exit=$normalizationProbeExit output=$($normalizationProbeLines -join ';')"
        Assert-True "mcp stdio list output redacts secrets" (-not (Test-HasSecretLikeValue $session.Raw)) "raw=$($session.Raw)"
    }
}

function Test-McpStdioServerCallsTool {
    Assert-True "mcp stdio server exists for tool call" (Test-Path -LiteralPath $McpStdioServer) "missing $McpStdioServer"
    if (-not (Test-Path -LiteralPath $McpStdioServer)) { return }

    $session = Invoke-McpStdioJson @(
        @{
            jsonrpc = "2.0"
            id = 10
            method = "tools/call"
            params = @{
                name = "source_scan"
                arguments = @{
                    requestId = "test-mcp-call"
                    sessionId = "session-a"
                    nodeRole = "desktop"
                    root = $Root
                }
            }
        },
        @{
            jsonrpc = "2.0"
            id = 11
            method = "tools/call"
            params = @{
                name = "desktop.dispatch_packet"
                arguments = @{
                    nodeRole = "desktop"
                    topic = "mcp stdio dispatch"
                    patchdrop_root = ".\__patch_drop__"
                    producer_roots = @{
                        macmini = "C:\AbandonWare\worktrees\awx-macmini"
                        notebook = "C:\AbandonWare\worktrees\awx-notebook"
                    }
                    role_pathspec = @{
                        macmini = @("main/java/example/MacChanged.java")
                        notebook = @("main/java/example/NotebookChanged.java")
                    }
                }
            }
        }
    )
    Assert-True "mcp stdio tools/call exits zero" ($session.ExitCode -eq 0) "output=$($session.Raw)"
    if ($session.Json.Count -ge 2) {
        $scan = @($session.Json | Where-Object { $_.id -eq 10 } | Select-Object -First 1)
        $dispatch = @($session.Json | Where-Object { $_.id -eq 11 } | Select-Object -First 1)
        Assert-True "mcp stdio source_scan returns content" (@($scan.result.content).Count -eq 1 -and $scan.result.content[0].type -eq "text") "scan=$($scan | ConvertTo-Json -Depth 10 -Compress)"
        Assert-True "mcp stdio source_scan json has activeSourceSets" ($scan.result.structuredContent.activeSourceSets.mainJava.exists -eq $true) "scan=$($scan | ConvertTo-Json -Depth 20 -Compress)"
        Assert-True "mcp stdio alias tool call works" ($dispatch.result.structuredContent.toolName -eq "desktop_dispatch_packet") "dispatch=$($dispatch | ConvertTo-Json -Depth 20 -Compress)"
        Assert-True "mcp stdio dispatch keeps Desktop proof pending" ($dispatch.result.structuredContent.desktopFinalProof -eq "evidence_needed") "dispatch=$($dispatch | ConvertTo-Json -Depth 20 -Compress)"
        Assert-True "mcp stdio tool call output redacts secrets" (-not (Test-HasSecretLikeValue $session.Raw)) "raw=$($session.Raw)"
    }
}

function Test-McpNodeSetupRendersProducerLocalConfig {
    Assert-True "mcp node setup runner exists" (Test-Path -LiteralPath $McpNodeSetup) "missing $McpNodeSetup"
    if (-not (Test-Path -LiteralPath $McpNodeSetup)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-node-setup-" + [guid]::NewGuid().ToString("N"))
    $sourceRoot = Join-Path $tmp "macmini-worktree"
    $output = Join-Path $tmp "awx-control-tower.mcp.json"
    $audit = Join-Path $tmp "node-setup.audit.jsonl"
    New-Item -ItemType Directory -Force -Path $sourceRoot | Out-Null
    try {
        git -C $sourceRoot init -q 2>$null
        $setup = Invoke-McpNodeSetupJson "macmini" $sourceRoot $Root $output $audit
        Assert-True "mcp node setup exits zero for producer-local root" ($setup.ExitCode -eq 0) "output=$($setup.Raw)"
        Assert-True "mcp node setup writes config" (Test-Path -LiteralPath $output) "missing $output"
        if ($setup.Json -and (Test-Path -LiteralPath $output)) {
            $configText = Get-Content -LiteralPath $output -Raw
            $config = $configText | ConvertFrom-Json
            $server = $config.mcpServers.'awx-control-tower'
            Assert-True "mcp node setup records source isolation pass" ($setup.Json.sourceIsolation.guard -eq "PASS" -and $setup.Json.sourceIsolation.sourceRootKind -eq "local-worktree") "json=$($setup.Raw)"
            Assert-True "mcp node setup config uses source root cwd" ([IO.Path]::GetFullPath($server.cwd) -eq [IO.Path]::GetFullPath($sourceRoot)) "cwd=$($server.cwd) sourceRoot=$sourceRoot"
            Assert-True "mcp node setup config points to stdio server" ((@($server.args) -join " ") -match "scripts[/\\]awx_mcp_stdio_server.py") "args=$(@($server.args) -join ' ')"
            Assert-True "mcp node setup keeps env names only" (@($config.allowedEnvRefs) -contains "NAVER_KEYS" -and @($config.allowedEnvRefs) -contains "NAVER_CLIENT_ID" -and @($config.allowedEnvRefs) -contains "NAVER_CLIENT_SECRET") "allowedEnvRefs=$($config.allowedEnvRefs -join ',')"
            Assert-True "mcp node setup output has no secret-like values" (-not (Test-HasSecretLikeValue ($setup.Raw + $configText))) "raw=$($setup.Raw)"
            Assert-True "mcp node setup writes audit log" (Test-Path -LiteralPath $audit) "missing $audit"
            if (Test-Path -LiteralPath $audit) {
                $auditRow = Get-Content -LiteralPath $audit | Select-Object -First 1 | ConvertFrom-Json
                $allowedAuditFields = @("requestId","sessionId","nodeRole","toolName","inputHash","outputCount","elapsedMs","decision","failReason")
                $extraFields = @($auditRow.PSObject.Properties.Name | Where-Object { $allowedAuditFields -notcontains $_ })
                Assert-True "mcp node setup audit has allowlisted fields" ($extraFields.Count -eq 0) "extraFields=$($extraFields -join ',') audit=$(Get-Content -LiteralPath $audit -Raw)"
                Assert-True "mcp node setup audit records safe status" ($auditRow.toolName -eq "mcp_node_setup" -and $auditRow.nodeRole -eq "macmini" -and $auditRow.decision -eq "mcp_node_setup" -and [int]$auditRow.outputCount -eq 1) "audit=$(Get-Content -LiteralPath $audit -Raw)"
                Assert-True "mcp node setup audit has no secret-like values" (-not (Test-HasSecretLikeValue (Get-Content -LiteralPath $audit -Raw))) "audit=$(Get-Content -LiteralPath $audit -Raw)"
            }
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-McpNodeSetupRejectsNonGitProducerRoot {
    Assert-True "mcp node setup runner exists for non-git guard" (Test-Path -LiteralPath $McpNodeSetup) "missing $McpNodeSetup"
    if (-not (Test-Path -LiteralPath $McpNodeSetup)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-node-setup-nongit-" + [guid]::NewGuid().ToString("N"))
    $sourceRoot = Join-Path $tmp "plain-folder"
    $output = Join-Path $tmp "blocked.mcp.json"
    $audit = Join-Path $tmp "node-setup-nongit.audit.jsonl"
    New-Item -ItemType Directory -Force -Path $sourceRoot | Out-Null
    try {
        $setup = Invoke-McpNodeSetupJson "notebook" $sourceRoot $Root $output $audit
        Assert-True "mcp node setup rejects non-git producer root" ($setup.ExitCode -ne 0) "output=$($setup.Raw)"
        Assert-True "mcp node setup writes no config for non-git root" (-not (Test-Path -LiteralPath $output)) "unexpected output $output"
        if ($setup.Json) {
            Assert-True "mcp node setup non-git failure classified" ($setup.Json.sourceIsolation.guard -eq "FAIL" -and $setup.Json.sourceIsolation.sourceRootKind -eq "not-git-root" -and $setup.Json.failReason -match "source-isolation") "json=$($setup.Raw)"
            Assert-True "mcp node setup non-git failure writes audit" (Test-Path -LiteralPath $audit) "missing $audit"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-McpNodeSetupRejectsCanonicalSourceRoot {
    Assert-True "mcp node setup runner exists for canonical guard" (Test-Path -LiteralPath $McpNodeSetup) "missing $McpNodeSetup"
    if (-not (Test-Path -LiteralPath $McpNodeSetup)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-node-setup-canonical-" + [guid]::NewGuid().ToString("N"))
    $output = Join-Path $tmp "blocked.mcp.json"
    $audit = Join-Path $tmp "node-setup-blocked.audit.jsonl"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        $setup = Invoke-McpNodeSetupJson "macmini" $Root $Root $output $audit
        Assert-True "mcp node setup rejects canonical root" ($setup.ExitCode -ne 0) "output=$($setup.Raw)"
        Assert-True "mcp node setup writes no config for canonical root" (-not (Test-Path -LiteralPath $output)) "unexpected output $output"
        if ($setup.Json) {
            Assert-True "mcp node setup canonical failure classified" ($setup.Json.sourceIsolation.guard -eq "FAIL" -and $setup.Json.sourceIsolation.directCanonicalSourceEdit -eq $true -and $setup.Json.failReason -match "source-isolation") "json=$($setup.Raw)"
            Assert-True "mcp node setup canonical failure writes audit" (Test-Path -LiteralPath $audit) "missing $audit"
            if (Test-Path -LiteralPath $audit) {
                $auditRow = Get-Content -LiteralPath $audit | Select-Object -First 1 | ConvertFrom-Json
                Assert-True "mcp node setup canonical audit classifies failure" ($auditRow.toolName -eq "mcp_node_setup" -and $auditRow.decision -eq "mcp_node_setup_failed" -and $auditRow.failReason -match "source-isolation") "audit=$(Get-Content -LiteralPath $audit -Raw)"
            }
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-McpNodeSetupRejectsPatchDropSourceRoot {
    Assert-True "mcp node setup runner exists for PatchDrop guard" (Test-Path -LiteralPath $McpNodeSetup) "missing $McpNodeSetup"
    if (-not (Test-Path -LiteralPath $McpNodeSetup)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-node-setup-patchdrop-" + [guid]::NewGuid().ToString("N"))
    $output = Join-Path $tmp "blocked.mcp.json"
    $audit = Join-Path $tmp "node-setup-patchdrop.audit.jsonl"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        $setup = Invoke-McpNodeSetupJson "notebook" "Z:\PatchDrop" $Root $output $audit
        Assert-True "mcp node setup rejects PatchDrop source root" ($setup.ExitCode -ne 0) "output=$($setup.Raw)"
        Assert-True "mcp node setup writes no config for PatchDrop root" (-not (Test-Path -LiteralPath $output)) "unexpected output $output"
        if ($setup.Json) {
            Assert-True "mcp node setup PatchDrop failure classified" ($setup.Json.sourceIsolation.guard -eq "FAIL" -and $setup.Json.sourceIsolation.sharedSourceRoot -eq $true -and $setup.Json.failReason -match "source-isolation") "json=$($setup.Raw)"
            Assert-True "mcp node setup PatchDrop failure writes audit" (Test-Path -LiteralPath $audit) "missing $audit"
            if (Test-Path -LiteralPath $audit) {
                $auditRow = Get-Content -LiteralPath $audit | Select-Object -First 1 | ConvertFrom-Json
                Assert-True "mcp node setup PatchDrop audit classifies failure" ($auditRow.toolName -eq "mcp_node_setup" -and $auditRow.decision -eq "mcp_node_setup_failed" -and $auditRow.failReason -match "source-isolation") "audit=$(Get-Content -LiteralPath $audit -Raw)"
            }
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ManifestDeclaresRequiredToolSchemas {
    Assert-True "manifest exists" (Test-Path -LiteralPath $Manifest) "missing $Manifest"
    if (-not (Test-Path -LiteralPath $Manifest)) { return }

    $doc = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
    Assert-True "manifest schema version" ($doc.schemaVersion -eq "awx.mcp.tools.v1") "schemaVersion=$($doc.schemaVersion)"
    $names = @($doc.tools | ForEach-Object { $_.name })
    foreach ($name in @(
        "source_scan",
        "patch_plan",
        "patch_render",
        "archive_search",
        "archive_restore",
        "boot_verify",
        "build_error_mine",
        "run_pipeline",
        "external_evidence_intake",
        "external_evidence_audit",
        "producer_command_plan",
        "desktop_dispatch_packet",
        "desktop_control_loop",
        "producer_kit_export"
    )) {
        Assert-True "manifest declares $name" ($names -contains $name) "tool names=$($names -join ',')"
        $tool = $doc.tools | Where-Object { $_.name -eq $name } | Select-Object -First 1
        Assert-True "$name has input schema" ($null -ne $tool.input_schema -and $tool.input_schema.type -eq "object") "input schema missing"
        Assert-True "$name has output schema" ($null -ne $tool.output_schema -and $tool.output_schema.type -eq "object") "output schema missing"
    }
    $aliasMap = @{
        "archive_search" = "archive.search"
        "archive_restore" = "archive.restore"
        "boot_verify" = "verify_boot"
        "build_error_mine" = "build_error_miner"
        "external_evidence_intake" = "external.evidence_intake"
        "external_evidence_audit" = "external.evidence_audit"
        "producer_command_plan" = "producer.command_plan"
        "desktop_dispatch_packet" = "desktop.dispatch_packet"
        "desktop_control_loop" = "desktop.control_loop"
        "producer_kit_export" = "producer.kit_export"
    }
    foreach ($entry in $aliasMap.GetEnumerator()) {
        $tool = $doc.tools | Where-Object { $_.name -eq $entry.Key } | Select-Object -First 1
        Assert-True "manifest declares alias $($entry.Value)" (@($tool.aliases) -contains $entry.Value) "aliases=$($tool.aliases -join ',')"
    }
    $desktopControlLoop = $doc.tools | Where-Object { $_.name -eq "desktop_control_loop" } | Select-Object -First 1
    Assert-True "desktop_control_loop schema exposes local readiness" (@($desktopControlLoop.output_schema.properties.PSObject.Properties.Name) -contains "localReady") "output=$($desktopControlLoop.output_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "desktop_control_loop schema exposes completion readiness" (@($desktopControlLoop.output_schema.properties.PSObject.Properties.Name) -contains "completionReady") "output=$($desktopControlLoop.output_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "desktop_control_loop schema requires local readiness" (@($desktopControlLoop.output_schema.required) -contains "localReady") "required=$($desktopControlLoop.output_schema.required -join ',')"
    Assert-True "desktop_control_loop schema requires completion readiness" (@($desktopControlLoop.output_schema.required) -contains "completionReady") "required=$($desktopControlLoop.output_schema.required -join ',')"
    Assert-True "desktop_control_loop schema exposes dispatch integrity" (@($desktopControlLoop.output_schema.properties.PSObject.Properties.Name) -contains "dispatchIntegrity") "output=$($desktopControlLoop.output_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "desktop_control_loop schema requires dispatch integrity" (@($desktopControlLoop.output_schema.required) -contains "dispatchIntegrity") "required=$($desktopControlLoop.output_schema.required -join ',')"
    Assert-True "desktop_control_loop schema exposes unrelated PatchDrop evidence" (@($desktopControlLoop.output_schema.properties.PSObject.Properties.Name) -contains "unrelatedPatchDropEvidence") "output=$($desktopControlLoop.output_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "desktop_control_loop schema requires unrelated PatchDrop evidence" (@($desktopControlLoop.output_schema.required) -contains "unrelatedPatchDropEvidence") "required=$($desktopControlLoop.output_schema.required -join ',')"
    foreach ($field in @("ok", "sidecarValid", "sidecarLineCount", "coveredArtifactCount", "sidecarHash", "failReason")) {
        Assert-True "desktop_control_loop dispatch integrity schema exposes $field" (@($desktopControlLoop.output_schema.properties.dispatchIntegrity.properties.PSObject.Properties.Name) -contains $field) "dispatchIntegrityFields=$($desktopControlLoop.output_schema.properties.dispatchIntegrity.properties.PSObject.Properties.Name -join ',')"
    }
    $archiveRestore = $doc.tools | Where-Object { $_.name -eq "archive_restore" } | Select-Object -First 1
    Assert-True "archive_restore input exposes verify_log" (@($archiveRestore.input_schema.properties.PSObject.Properties.Name) -contains "verify_log") "input=$($archiveRestore.input_schema.properties.PSObject.Properties.Name -join ',')"
    foreach ($field in @("ok", "failReason", "decision", "preReview", "skipped", "verifyLog")) {
        Assert-True "archive_restore schema exposes $field" (@($archiveRestore.output_schema.properties.PSObject.Properties.Name) -contains $field) "output=$($archiveRestore.output_schema.properties.PSObject.Properties.Name -join ',')"
    }
    foreach ($field in @("ok", "failReason", "decision", "restored", "outputCount")) {
        Assert-True "archive_restore schema requires $field" (@($archiveRestore.output_schema.required) -contains $field) "required=$($archiveRestore.output_schema.required -join ',')"
    }
    $patchRender = $doc.tools | Where-Object { $_.name -eq "patch_render" } | Select-Object -First 1
    foreach ($field in @("filemodeLineCount", "allowedNewFileCount", "filemodeViolationCount", "binaryPatchMarkerCount")) {
        Assert-True "patch_render schema exposes $field" (@($patchRender.output_schema.properties.PSObject.Properties.Name) -contains $field) "output=$($patchRender.output_schema.properties.PSObject.Properties.Name -join ',')"
    }
    $externalAudit = $doc.tools | Where-Object { $_.name -eq "external_evidence_audit" } | Select-Object -First 1
    Assert-True "external_evidence_audit schema requires Desktop final proof status" (@($externalAudit.output_schema.required) -contains "desktopFinalProof") "required=$($externalAudit.output_schema.required -join ',')"
    Assert-True "external_evidence_audit schema requires nextActions" (@($externalAudit.output_schema.required) -contains "nextActions") "required=$($externalAudit.output_schema.required -join ',')"
    Assert-True "external_evidence_audit schema requires optionalNextActions" (@($externalAudit.output_schema.required) -contains "optionalNextActions") "required=$($externalAudit.output_schema.required -join ',')"
    Assert-True "external_evidence_audit input exposes producer bundle gate" (@($externalAudit.input_schema.properties.PSObject.Properties.Name) -contains "require_producer_bundles") "input=$($externalAudit.input_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_audit input exposes producer bundle topic" (@($externalAudit.input_schema.properties.PSObject.Properties.Name) -contains "topic") "input=$($externalAudit.input_schema.properties.PSObject.Properties.Name -join ',')"
    foreach ($field in @("producerHandoffs", "producerBundles", "producerBundleTopic", "producerBundlesRequired")) {
        Assert-True "external_evidence_audit schema exposes $field" (@($externalAudit.output_schema.properties.PSObject.Properties.Name) -contains $field) "output=$($externalAudit.output_schema.properties.PSObject.Properties.Name -join ',')"
        Assert-True "external_evidence_audit schema requires $field" (@($externalAudit.output_schema.required) -contains $field) "required=$($externalAudit.output_schema.required -join ',')"
    }
    Assert-True "external_evidence_audit schema exposes dispatch integrity" (@($externalAudit.output_schema.properties.PSObject.Properties.Name) -contains "dispatchIntegrity") "output=$($externalAudit.output_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_audit schema requires dispatch integrity" (@($externalAudit.output_schema.required) -contains "dispatchIntegrity") "required=$($externalAudit.output_schema.required -join ',')"
    Assert-True "external_evidence_audit schema exposes unrelated PatchDrop evidence" (@($externalAudit.output_schema.properties.PSObject.Properties.Name) -contains "unrelatedPatchDropEvidence") "output=$($externalAudit.output_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_audit schema requires unrelated PatchDrop evidence" (@($externalAudit.output_schema.required) -contains "unrelatedPatchDropEvidence") "required=$($externalAudit.output_schema.required -join ',')"
    foreach ($field in @("ok", "sidecarValid", "sidecarLineCount", "coveredArtifactCount", "sidecarHash", "failReason")) {
        Assert-True "external_evidence_audit dispatch integrity schema exposes $field" (@($externalAudit.output_schema.properties.dispatchIntegrity.properties.PSObject.Properties.Name) -contains $field) "dispatchIntegrityFields=$($externalAudit.output_schema.properties.dispatchIntegrity.properties.PSObject.Properties.Name -join ',')"
    }
    Assert-True "external_evidence_audit producer bundle schema exposes filemode count" (@($externalAudit.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name) -contains "filemodeLineCount") "producerBundleFields=$($externalAudit.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name -join ',')"
    foreach ($field in @("allowedNewFileCount", "filemodeViolationCount", "binaryPatchMarkerCount")) {
        Assert-True "external_evidence_audit producer bundle schema exposes $field" (@($externalAudit.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name) -contains $field) "producerBundleFields=$($externalAudit.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name -join ',')"
    }
    Assert-True "external_evidence_audit producer bundle schema exposes forbidden path count" (@($externalAudit.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name) -contains "forbiddenPathCount") "producerBundleFields=$($externalAudit.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_audit producer bundle schema exposes diff header count" (@($externalAudit.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name) -contains "diffHeaderCount") "producerBundleFields=$($externalAudit.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_audit producer handoff schema exposes source root input hash" (@($externalAudit.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "sourceRootInputHash") "producerHandoffFields=$($externalAudit.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_audit producer handoff schema exposes diff header count" (@($externalAudit.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "diffHeaderCount") "producerHandoffFields=$($externalAudit.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_audit producer handoff schema exposes patch hash" (@($externalAudit.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "patchHash") "producerHandoffFields=$($externalAudit.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_audit producer handoff schema exposes producer command hashes" ((@($externalAudit.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "producerCommandHash") -and (@($externalAudit.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "expectedProducerCommandHash")) "producerHandoffFields=$($externalAudit.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_audit description names producer bundles" ($externalAudit.description -match "producer.*bundle") "description=$($externalAudit.description)"
    Assert-True "external_evidence_audit description names Desktop janitor apply gate" ($externalAudit.description -match "janitor.*apply gate") "description=$($externalAudit.description)"
    $auditNextActionFields = @($externalAudit.output_schema.properties.nextActions.items.properties.PSObject.Properties.Name)
    foreach ($field in @("action", "proofOnlyIntake", "oneAcceptedBundleAtATime", "activeTopLevelGuard", "inventoryCommand", "promoteCommands", "leaseBeginCommand", "applyCommand", "leaseEndCommand", "desktopFinalProof")) {
        Assert-True "external_evidence_audit nextActions schema exposes $field" ($auditNextActionFields -contains $field) "nextActionFields=$($auditNextActionFields -join ',')"
    }
    $externalIntake = $doc.tools | Where-Object { $_.name -eq "external_evidence_intake" } | Select-Object -First 1
    Assert-True "external_evidence_intake input exposes producer bundle gate" (@($externalIntake.input_schema.properties.PSObject.Properties.Name) -contains "require_producer_bundles") "input=$($externalIntake.input_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_intake input exposes producer bundle topic" (@($externalIntake.input_schema.properties.PSObject.Properties.Name) -contains "topic") "input=$($externalIntake.input_schema.properties.PSObject.Properties.Name -join ',')"
    foreach ($field in @("intakeSummary", "copiedHandoffs", "rejectedHandoffs", "producerHandoffs", "producerBundles", "producerBundleTopic", "producerBundlesRequired", "unrelatedPatchDropEvidence")) {
        Assert-True "external_evidence_intake schema exposes $field" (@($externalIntake.output_schema.properties.PSObject.Properties.Name) -contains $field) "output=$($externalIntake.output_schema.properties.PSObject.Properties.Name -join ',')"
        Assert-True "external_evidence_intake schema requires $field" (@($externalIntake.output_schema.required) -contains $field) "required=$($externalIntake.output_schema.required -join ',')"
    }
    Assert-True "external_evidence_intake schema exposes dispatch integrity" (@($externalIntake.output_schema.properties.PSObject.Properties.Name) -contains "dispatchIntegrity") "output=$($externalIntake.output_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_intake schema requires dispatch integrity" (@($externalIntake.output_schema.required) -contains "dispatchIntegrity") "required=$($externalIntake.output_schema.required -join ',')"
    foreach ($field in @("requiredRoles", "copiedEvidenceCount", "copiedHandoffCount", "rejectedEvidenceCount", "rejectedHandoffCount", "rawSecretPatternHits")) {
        Assert-True "external_evidence_intake summary schema exposes $field" (@($externalIntake.output_schema.properties.intakeSummary.properties.PSObject.Properties.Name) -contains $field) "summary=$($externalIntake.output_schema.properties.intakeSummary.properties.PSObject.Properties.Name -join ',')"
    }
    Assert-True "external_evidence_intake producer bundle schema exposes filemode count" (@($externalIntake.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name) -contains "filemodeLineCount") "producerBundleFields=$($externalIntake.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name -join ',')"
    foreach ($field in @("allowedNewFileCount", "filemodeViolationCount", "binaryPatchMarkerCount")) {
        Assert-True "external_evidence_intake producer bundle schema exposes $field" (@($externalIntake.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name) -contains $field) "producerBundleFields=$($externalIntake.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name -join ',')"
    }
    Assert-True "external_evidence_intake producer bundle schema exposes forbidden path count" (@($externalIntake.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name) -contains "forbiddenPathCount") "producerBundleFields=$($externalIntake.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_intake producer bundle schema exposes diff header count" (@($externalIntake.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name) -contains "diffHeaderCount") "producerBundleFields=$($externalIntake.output_schema.properties.producerBundles.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_intake producer handoff schema exposes source root input hash" (@($externalIntake.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "sourceRootInputHash") "producerHandoffFields=$($externalIntake.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_intake producer handoff schema exposes diff header count" (@($externalIntake.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "diffHeaderCount") "producerHandoffFields=$($externalIntake.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_intake producer handoff schema exposes patch hash" (@($externalIntake.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "patchHash") "producerHandoffFields=$($externalIntake.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name -join ',')"
    Assert-True "external_evidence_intake producer handoff schema exposes producer command hashes" ((@($externalIntake.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "producerCommandHash") -and (@($externalIntake.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name) -contains "expectedProducerCommandHash")) "producerHandoffFields=$($externalIntake.output_schema.properties.producerHandoffs.items.properties.PSObject.Properties.Name -join ',')"
    foreach ($field in @("nextActions", "optionalNextActions")) {
        Assert-True "external_evidence_intake schema exposes $field" (@($externalIntake.output_schema.properties.PSObject.Properties.Name) -contains $field) "output=$($externalIntake.output_schema.properties.PSObject.Properties.Name -join ',')"
        Assert-True "external_evidence_intake schema requires $field" (@($externalIntake.output_schema.required) -contains $field) "required=$($externalIntake.output_schema.required -join ',')"
    }
    $intakeNextActionFields = @($externalIntake.output_schema.properties.nextActions.items.properties.PSObject.Properties.Name)
    foreach ($field in @("action", "proofOnlyIntake", "oneAcceptedBundleAtATime", "activeTopLevelGuard", "inventoryCommand", "promoteCommands", "leaseBeginCommand", "applyCommand", "leaseEndCommand", "desktopFinalProof")) {
        Assert-True "external_evidence_intake nextActions schema exposes $field" ($intakeNextActionFields -contains $field) "nextActionFields=$($intakeNextActionFields -join ',')"
    }
    $resourceNames = @($doc.resources | ForEach-Object { $_.name })
    Assert-True "manifest declares node smoke runner resource" ($resourceNames -contains "node_smoke_runner") "resources=$($resourceNames -join ',')"
    Assert-True "manifest declares mcp stdio server resource" ($resourceNames -contains "mcp_stdio_server") "resources=$($resourceNames -join ',')"
    Assert-True "manifest declares mcp client config resource" ($resourceNames -contains "mcp_client_config") "resources=$($resourceNames -join ',')"
    Assert-True "manifest declares mcp node setup runner resource" ($resourceNames -contains "mcp_node_setup_runner") "resources=$($resourceNames -join ',')"
    Assert-True "manifest declares tool manifest resource" ($resourceNames -contains "tool_manifest") "resources=$($resourceNames -join ',')"
    Assert-True "manifest declares producer handoff runner resource" ($resourceNames -contains "producer_handoff_runner") "resources=$($resourceNames -join ',')"
    Assert-True "manifest declares completion audit runner resource" ($resourceNames -contains "completion_audit_runner") "resources=$($resourceNames -join ',')"
    Assert-True "manifest declares external evidence resource" ($resourceNames -contains "external_evidence_dir") "resources=$($resourceNames -join ',')"
    Assert-True "manifest declares producer kit resource" ($resourceNames -contains "producer_kit_dir") "resources=$($resourceNames -join ',')"
    $handoffResource = $doc.resources | Where-Object { $_.name -eq "producer_handoff_runner" } | Select-Object -First 1
    Assert-True "manifest producer handoff runner documents audit log" ($handoffResource.usage -match "--audit-log" -and $handoffResource.usage -match "awx-control-tower.audit.jsonl") "usage=$($handoffResource.usage)"
    Assert-True "manifest producer handoff runner documents local bundle helper" ($handoffResource.usage -match "producer-local" -and $handoffResource.usage -match "__patch_drop__/producer_bundle.py") "usage=$($handoffResource.usage)"
    $producerPlan = $doc.tools | Where-Object { $_.name -eq "producer_command_plan" } | Select-Object -First 1
    Assert-True "producer_command_plan schema exposes handoff proof path" (@($producerPlan.output_schema.properties.PSObject.Properties.Name) -contains "handoffProofPath" -and @($producerPlan.output_schema.required) -contains "handoffProofPath") "output=$($producerPlan.output_schema.properties.PSObject.Properties.Name -join ',') required=$($producerPlan.output_schema.required -join ',')"
    $clientConfigResource = $doc.resources | Where-Object { $_.name -eq "mcp_client_config" } | Select-Object -First 1
    $clientConfigPath = Join-Path $Root $clientConfigResource.path
    Assert-True "mcp client config file exists" (Test-Path -LiteralPath $clientConfigPath) "missing $clientConfigPath"
    if (Test-Path -LiteralPath $clientConfigPath) {
        $clientConfig = Get-Content -LiteralPath $clientConfigPath -Raw | ConvertFrom-Json
        $server = $clientConfig.mcpServers.'awx-control-tower'
        Assert-True "mcp client config uses python command" ($server.command -match "python") "config=$($clientConfig | ConvertTo-Json -Depth 10 -Compress)"
        Assert-True "mcp client config points to stdio server" ((@($server.args) -join ' ') -match "scripts[/\\]awx_mcp_stdio_server.py") "args=$(@($server.args) -join ' ')"
        Assert-True "mcp client config has no secret-like values" (-not (Test-HasSecretLikeValue (Get-Content -LiteralPath $clientConfigPath -Raw))) "config=$clientConfigPath"
    }
    $promptNames = @($doc.prompts | ForEach-Object { $_.name })
    Assert-True "manifest declares notebook reviewer prompt" ($promptNames -contains "notebook_support_reviewer") "prompts=$($promptNames -join ',')"
}

function Test-ProducerKitExportWritesPatchDropKit {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-producer-kit-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "PatchDrop"
    $audit = Join-Path $tmp "producer-kit.audit.jsonl"
    New-Item -ItemType Directory -Force -Path $patchDrop | Out-Null
    try {
        $kit = Invoke-ToolboxJson "producer_kit_export" @{
            requestId = "test-producer-kit"
            sessionId = "session-a"
            nodeRole = "desktop"
            root = $Root
            topic = "mcp producer kit"
            patchdrop_root = $patchDrop
            audit_log = $audit
        }
        Assert-True "producer_kit_export exits zero" ($kit.ExitCode -eq 0) "output=$($kit.Raw)"
        if ($kit.Json) {
            $kitDir = [string]$kit.Json.kitDir
            $manifestPath = [string]$kit.Json.manifestPath
            Assert-True "producer_kit_export records desktop role" ($kit.Json.nodeRole -eq "desktop") "json=$($kit.Raw)"
            Assert-True "producer_kit_export keeps Desktop proof pending" ($kit.Json.desktopFinalProof -eq "evidence_needed") "json=$($kit.Raw)"
            Assert-True "producer_kit_export writes kit under PatchDrop" ($kitDir.StartsWith($patchDrop, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $kitDir)) "kitDir=$kitDir patchDrop=$patchDrop"
            Assert-True "producer_kit_export writes kit manifest" ((Test-Path -LiteralPath $manifestPath) -and $manifestPath.StartsWith($kitDir, [System.StringComparison]::OrdinalIgnoreCase)) "manifestPath=$manifestPath kitDir=$kitDir"
            $kitFileNames = @($kit.Json.files | ForEach-Object { [string]$_.pathName })
            Assert-True "producer_kit_export includes runner scripts" (($kitFileNames -contains "awx_mcp_node_setup.py") -and ($kitFileNames -contains "awx_mcp_node_smoke.py") -and ($kitFileNames -contains "awx_mcp_producer_handoff.py") -and ($kitFileNames -contains "awx_mcp_stdio_server.py")) "files=$($kit.Json.files | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "producer_kit_export includes completion audit runner" ($kitFileNames -contains "awx_mcp_completion_audit.py") "files=$($kit.Json.files | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "producer_kit_export includes producer bundle helpers" (($kitFileNames -contains "producer_bundle.py") -and ($kitFileNames -contains "producer_bundle.ps1")) "files=$($kit.Json.files | ConvertTo-Json -Depth 10 -Compress)"
            $hasToolManifest = $kitFileNames -contains "awx-control-tower-tools.json"
            $hasSkillDoc = Test-Path -LiteralPath (Join-Path $kitDir ".agents\skills\demo1-mcp-control-tower\SKILL.md")
            $hasHandoffSkillDoc = Test-Path -LiteralPath (Join-Path $kitDir "data\agent-handoff\mcp-control-tower\skills\demo1-mcp-control-tower\SKILL.md")
            $hasHandoffPrompt = Test-Path -LiteralPath (Join-Path $kitDir "data\agent-handoff\mcp-control-tower\demo1_mcp_control_tower.prompt")
            $skillDocEvidenceNeeded = (@($kit.Json.evidence_needed) -join " ") -match "demo1-mcp-control-tower/SKILL.md"
            Assert-True "producer_kit_export includes manifest and accounts for skill docs" ($hasToolManifest -and ($hasSkillDoc -or $hasHandoffSkillDoc -or $skillDocEvidenceNeeded)) "files=$($kit.Json.files | ConvertTo-Json -Depth 10 -Compress) evidence_needed=$(@($kit.Json.evidence_needed) -join ';')"
            Assert-True "producer_kit_export includes handoff-local control tower docs" ($hasHandoffSkillDoc -and $hasHandoffPrompt) "files=$($kit.Json.files | ConvertTo-Json -Depth 10 -Compress)"
            $kitEvidenceNeeded = @($kit.Json.evidence_needed) -join "|"
            Assert-True "producer_kit_export does not require protected docs when handoff fallback is packaged" (-not ($kitEvidenceNeeded -match "\.agents/skills|agent-prompts/.+demo1_mcp_control_tower")) "evidence_needed=$kitEvidenceNeeded"
            Assert-True "producer_kit_export includes install helpers" ((Test-Path -LiteralPath (Join-Path $kitDir "INSTALL.macmini.sh")) -and (Test-Path -LiteralPath (Join-Path $kitDir "INSTALL.notebook.ps1"))) "kitDir=$kitDir"
            $installText = (Get-Content -LiteralPath (Join-Path $kitDir "INSTALL.macmini.sh") -Raw) + (Get-Content -LiteralPath (Join-Path $kitDir "INSTALL.notebook.ps1") -Raw)
            Assert-True "producer_kit_export installers delegate to single safe owner" ($installText -match 'awx_mcp_safe_install.py' -and (Test-Path (Join-Path $kitDir 'scripts/awx_shared_state.py'))) 'safe installer dependency missing'
            Assert-True "producer_kit_export installers contain no force copy" (-not ($installText -match 'Copy-Item|\bcp\s|--force')) 'unsafe copy loop present'
            $producerKitActionCommands = (@($kit.Json.nextActions) | ForEach-Object { [string]$_.command }) -join "`n"
            Assert-True "producer_kit_export nextActions use producer-visible PatchDrop placeholders" (
                $producerKitActionCommands -match "<producer-visible-patchdrop>" -and
                -not $producerKitActionCommands.Contains($kitDir)
            ) "nextActions=$($kit.Json.nextActions | ConvertTo-Json -Depth 10 -Compress) kitDir=$kitDir"
            $readmeText = Get-Content -LiteralPath (Join-Path $kitDir "README.producer-kit.md") -Raw
            Assert-True "producer_kit_export readme points producers at dispatch command files" ($readmeText -match "dispatch/.+macmini.commands.txt" -and $readmeText -match "dispatch/.+notebook.commands.txt") "readme=$readmeText"
            Assert-True "producer_kit_export readme explains proof validation and Desktop intake" ($readmeText -match "queryHash" -and $readmeText -match "\.sha256\.txt" -and $readmeText -match "desktop-intake\.ps1") "readme=$readmeText"
            Assert-True "producer_kit_export readme explains fail-closed stale kit handling" ($readmeText -match "producer-kit-installer-missing" -and $readmeText -match "Desktop-rendered command file" -and $readmeText -match "Do not\s+continue from an older local copy") "readme=$readmeText"
            $blockedInstall = powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $kitDir "INSTALL.notebook.ps1") -ProducerRoot "Z:\PatchDrop" -NodeRole notebook 2>&1 | ForEach-Object { $_.ToString() }
            $blockedRaw = $blockedInstall -join "`n"
            $blockedExit = $LASTEXITCODE
            Assert-True "producer_kit_export notebook installer rejects PatchDrop source root" ($blockedExit -ne 0 -and $blockedRaw -match "producer-source-isolation-violation") "exit=$blockedExit raw=$blockedRaw"
            $nonGitRoot = Join-Path $tmp "notebook-nongit-root"
            New-Item -ItemType Directory -Force -Path $nonGitRoot | Out-Null
            $blockedNonGitInstall = powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $kitDir "INSTALL.notebook.ps1") -ProducerRoot $nonGitRoot -NodeRole notebook 2>&1 | ForEach-Object { $_.ToString() }
            $blockedNonGitRaw = $blockedNonGitInstall -join "`n"
            $blockedNonGitExit = $LASTEXITCODE
            Assert-True "producer_kit_export notebook installer rejects non-git producer root before copy" ($blockedNonGitExit -ne 0 -and $blockedNonGitRaw -match "producer-git-root-invalid" -and -not (Test-Path -LiteralPath (Join-Path $nonGitRoot "scripts\awx_mcp_node_setup.py"))) "exit=$blockedNonGitExit raw=$blockedNonGitRaw"
            $validProducerRoot = Join-Path $tmp "notebook-valid-root"
            New-Item -ItemType Directory -Force -Path $validProducerRoot | Out-Null
            git -C $validProducerRoot init | Out-Null
            $localState = Join-Path $tmp 'host-state'
            $validInstall = powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $kitDir "INSTALL.notebook.ps1") -ProducerRoot $validProducerRoot -NodeRole notebook -StateRoot $localState 2>&1 | ForEach-Object { $_.ToString() }
            $validInstallRaw = $validInstall -join "`n"
            $validInstallExit = $LASTEXITCODE
            Assert-True "producer_kit_export notebook installer succeeds for valid producer git root" ($validInstallExit -eq 0 -and (Test-Path -LiteralPath (Join-Path $validProducerRoot "scripts\awx_mcp_node_setup.py")) -and (Test-Path -LiteralPath (Join-Path $validProducerRoot "__patch_drop__\producer_bundle.py"))) "exit=$validInstallExit raw=$validInstallRaw"
            Assert-True "producer_kit_export writes config outside shared source" ((Test-Path (Join-Path $localState 'awx-control-tower.mcp.json')) -and -not (Test-Path (Join-Path $validProducerRoot '.codex/awx-control-tower.mcp.json'))) 'host isolation failed'
            $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
            Assert-True "producer_kit_export manifest has checksums" (@($manifest.files | Where-Object { $_.sha256 -match "^[a-f0-9]{64}$" }).Count -eq @($manifest.files).Count) "manifest=$(Get-Content -LiteralPath $manifestPath -Raw)"
            Assert-True "producer_kit_export writes allowlisted audit" (Test-Path -LiteralPath $audit) "missing $audit"
            if (Test-Path -LiteralPath $audit) {
                $auditRow = Get-Content -LiteralPath $audit | Select-Object -First 1 | ConvertFrom-Json
                $allowedAuditFields = @("requestId","sessionId","nodeRole","toolName","inputHash","outputCount","elapsedMs","decision","failReason")
                $extraFields = @($auditRow.PSObject.Properties.Name | Where-Object { $allowedAuditFields -notcontains $_ })
                Assert-True "producer_kit_export audit has allowlisted fields" ($extraFields.Count -eq 0 -and $auditRow.toolName -eq "producer_kit_export") "audit=$(Get-Content -LiteralPath $audit -Raw)"
            }
            Assert-True "producer_kit_export output has no secret-like values" (-not (Test-HasSecretLikeValue ($kit.Raw + $installText + (Get-Content -LiteralPath $manifestPath -Raw)))) "raw=$($kit.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-LauncherSupportsTaskAliases {
    Assert-True "launcher exists" (Test-Path -LiteralPath $Launcher) "missing $Launcher"
    if (-not (Test-Path -LiteralPath $Launcher)) { return }

    $schema = Invoke-LauncherJson "schema" @{
        requestId = "test-launcher-schema"
        sessionId = "session-a"
        nodeRole = "desktop"
        manifest_path = (Join-Path $Root "main\resources\mcp\awx-control-tower-tools.json")
    }
    Assert-True "launcher schema exits zero" ($schema.ExitCode -eq 0) "output=$($schema.Raw)"
    if ($schema.Json) {
        Assert-True "launcher schema returns tool names" (@($schema.Json.toolNames) -contains "archive_search") "toolNames=$($schema.Json.toolNames -join ',')"
    }

    $verify = Invoke-LauncherJson "verify_boot" @{
        requestId = "test-launcher-verify"
        sessionId = "session-a"
        nodeRole = "desktop"
        root = $Root
    }
    Assert-True "launcher maps verify_boot alias" ($verify.ExitCode -eq 0) "output=$($verify.Raw)"
    if ($verify.Json) {
        Assert-True "verify_boot alias calls boot_verify" ($verify.Json.toolName -eq "boot_verify") "toolName=$($verify.Json.toolName)"
    }
}

function Test-NodeSmokeRunnerMacminiContract {
    Assert-True "node smoke script exists" (Test-Path -LiteralPath $NodeSmoke) "missing $NodeSmoke"
    if (-not (Test-Path -LiteralPath $NodeSmoke)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-node-smoke-local-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        git -C $tmp init -q 2>$null
        $smoke = Invoke-NodeSmokeJson "macmini" $tmp $Root
        Assert-True "node smoke exits zero from producer-local root" ($smoke.ExitCode -eq 0) "output=$($smoke.Raw)"
        if ($smoke.Json) {
            Assert-True "node smoke marks macmini role" ($smoke.Json.nodeRole -eq "macmini") "nodeRole=$($smoke.Json.nodeRole)"
            Assert-True "node smoke records source isolation pass" ($smoke.Json.sourceIsolation.guard -eq "PASS" -and $smoke.Json.sourceIsolation.sourceRootKind -eq "local-worktree") "sourceIsolation=$($smoke.Json.sourceIsolation | ConvertTo-Json -Compress)"
            Assert-True "node smoke source root differs from canonical root" ($smoke.Json.rootHash -ne $smoke.Json.canonicalRootHash) "rootHash=$($smoke.Json.rootHash) canonicalRootHash=$($smoke.Json.canonicalRootHash)"
            Assert-True "node smoke runs source_scan" (@($smoke.Json.steps | ForEach-Object { $_.toolName }) -contains "source_scan") "steps=$($smoke.Raw)"
            Assert-True "node smoke runs archive_search" (@($smoke.Json.steps | ForEach-Object { $_.toolName }) -contains "archive_search") "steps=$($smoke.Raw)"
            Assert-True "node smoke runs patch_render" (@($smoke.Json.steps | ForEach-Object { $_.toolName }) -contains "patch_render") "steps=$($smoke.Raw)"
            Assert-True "node smoke runs archive_restore block" (@($smoke.Json.steps | ForEach-Object { $_.toolName }) -contains "archive_restore") "steps=$($smoke.Raw)"
            $restore = @($smoke.Json.steps | Where-Object { $_.toolName -eq "archive_restore" } | Select-Object -First 1)
            Assert-True "node smoke proves canonical restore blocked" ($restore.decision -eq "restore_target_blocked" -and $restore.failReason -eq "smb-conflict-risk") "restore=$($restore | ConvertTo-Json -Compress)"
            Assert-True "node smoke leaves Desktop proof as evidence_needed" (@($smoke.Json.evidence_needed) -contains "Desktop final proof until commands run on canonical root") "evidence=$($smoke.Json.evidence_needed -join ',')"
            Assert-True "node smoke reports no raw secret leak" ([int]$smoke.Json.rawSecretPatternHits -eq 0) "rawSecretPatternHits=$($smoke.Json.rawSecretPatternHits)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-NodeSmokeRejectsNonGitProducerRoot {
    Assert-True "node smoke script exists for non-git guard" (Test-Path -LiteralPath $NodeSmoke) "missing $NodeSmoke"
    if (-not (Test-Path -LiteralPath $NodeSmoke)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-node-smoke-nongit-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        $smoke = Invoke-NodeSmokeJson "notebook" $tmp $Root
        Assert-True "node smoke rejects non-git producer root" ($smoke.ExitCode -ne 0) "expected nonzero; output=$($smoke.Raw)"
        Assert-True "node smoke non-git rejection emits JSON" ($null -ne $smoke.Json) "output=$($smoke.Raw)"
        if ($smoke.Json) {
            Assert-True "node smoke non-git failure classified" ($smoke.Json.sourceIsolation.guard -eq "FAIL" -and $smoke.Json.sourceIsolation.sourceRootKind -eq "not-git-root" -and (($smoke.Json.failReason -split ',') -contains "source-isolation-violation")) "json=$($smoke.Raw)"
            Assert-True "node smoke non-git proof runs no tool steps" (@($smoke.Json.steps).Count -eq 0) "steps=$($smoke.Json.steps | ConvertTo-Json -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-NodeSmokeRejectsCanonicalSourceRoot {
    Assert-True "node smoke script exists for canonical source guard" (Test-Path -LiteralPath $NodeSmoke) "missing $NodeSmoke"
    if (-not (Test-Path -LiteralPath $NodeSmoke)) { return }

    $smoke = Invoke-NodeSmokeJson "macmini" $Root $Root
    Assert-True "node smoke rejects canonical source root for macmini" ($smoke.ExitCode -ne 0) "expected nonzero; output=$($smoke.Raw)"
    Assert-True "node smoke canonical source rejection emits JSON" ($null -ne $smoke.Json) "output=$($smoke.Raw)"
    if ($smoke.Json) {
        Assert-True "node smoke records source isolation failure" ($smoke.Json.sourceIsolation.guard -eq "FAIL" -and $smoke.Json.sourceIsolation.directCanonicalSourceEdit -eq $true) "sourceIsolation=$($smoke.Json.sourceIsolation | ConvertTo-Json -Compress)"
        Assert-True "node smoke canonical source failure is classified" (($smoke.Json.failReason -split ',') -contains "source-isolation-violation") "failReason=$($smoke.Json.failReason)"
        Assert-True "node smoke canonical root hashes match on rejected proof" ($smoke.Json.rootHash -eq $smoke.Json.canonicalRootHash) "rootHash=$($smoke.Json.rootHash) canonicalRootHash=$($smoke.Json.canonicalRootHash)"
    }
}

function Test-NodeSmokeUsesArchiveIndexEnvFallback {
    Assert-True "node smoke script exists for archive env fallback" (Test-Path -LiteralPath $NodeSmoke) "missing $NodeSmoke"
    if (-not (Test-Path -LiteralPath $NodeSmoke)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-node-smoke-archive-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $oldArchiveIndex = $env:ARCHIVE_INDEX
    try {
        git -C $tmp init -q 2>$null
        $index = Join-Path $tmp "index.jsonl"
        (@{ path = "snapshots/mcp-smoke.md"; title = "mcp smoke safe patch"; tags = @("mcp"); summary = "mcp smoke safe patch" } | ConvertTo-Json -Compress) |
            Set-Content -LiteralPath $index -Encoding UTF8
        $env:ARCHIVE_INDEX = $index
        $smoke = Invoke-NodeSmokeJson "macmini" $tmp
        Assert-True "node smoke archive env fallback exits zero" ($smoke.ExitCode -eq 0) "output=$($smoke.Raw)"
        if ($smoke.Json) {
            $archive = @($smoke.Json.steps | Where-Object { $_.toolName -eq "archive_search" } | Select-Object -First 1)
            Assert-True "node smoke archive env fallback searches real index" ($archive.decision -eq "archive_search") "archive=$($archive | ConvertTo-Json -Compress)"
            Assert-True "node smoke archive env fallback finds result" ([int]$archive.outputCount -ge 1) "archive=$($archive | ConvertTo-Json -Compress)"
        }
    } finally {
        if ($null -eq $oldArchiveIndex) { Remove-Item Env:ARCHIVE_INDEX -ErrorAction SilentlyContinue } else { $env:ARCHIVE_INDEX = $oldArchiveIndex }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerHandoffRunsSmokeBeforeBundle {
    Assert-True "producer handoff script exists" (Test-Path -LiteralPath $ProducerHandoff) "missing $ProducerHandoff"
    if (-not (Test-Path -LiteralPath $ProducerHandoff)) { return }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer handoff skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-handoff-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $patchDrop = Join-Path $tmp "patchdrop"
    $audit = Join-Path $tmp "producer-handoff.audit.jsonl"
    New-Item -ItemType Directory -Force -Path $repo, $patchDrop | Out-Null
    try {
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $repo "src") | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\smoke.txt") -Encoding UTF8 -Value "before"
        git -C $repo add src/smoke.txt | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\smoke.txt") -Encoding UTF8 -Value "after"

        $expectedProducerCommandHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        $handoff = Invoke-ProducerHandoffJson $repo $patchDrop "src/smoke.txt" "mcp-handoff-test" $audit -ProducerCommandHash $expectedProducerCommandHash
        Assert-True "producer handoff exits zero" ($handoff.ExitCode -eq 0) "output=$($handoff.Raw)"
        if ($handoff.Json) {
            Assert-True "producer handoff records smoke pass" ($handoff.Json.smoke.ok -eq $true) "json=$($handoff.Raw)"
            Assert-True "producer handoff records bundle pass" ($handoff.Json.bundle.ok -eq $true) "json=$($handoff.Raw)"
            Assert-True "producer handoff records source isolation pass" ($handoff.Json.bundle.sourceIsolation.guard -eq "PASS") "bundle=$($handoff.Json.bundle | ConvertTo-Json -Compress)"
            Assert-True "producer handoff records local worktree source" ($handoff.Json.bundle.sourceIsolation.sourceRootKind -eq "local-worktree") "sourceIsolation=$($handoff.Json.bundle.sourceIsolation | ConvertTo-Json -Compress)"
            Assert-True "producer handoff records source root input hash" (-not [string]::IsNullOrWhiteSpace([string]$handoff.Json.sourceRootInputHash)) "json=$($handoff.Raw)"
            Assert-True "producer handoff records producer command hash" ($handoff.Json.producerCommandHash -eq $expectedProducerCommandHash) "json=$($handoff.Raw)"
            Assert-True "producer handoff marks promotion ready" ($handoff.Json.bundle.promotionReady -eq $true) "bundle=$($handoff.Json.bundle | ConvertTo-Json -Compress)"
            Assert-True "producer handoff records unified diff header count" ([int]$handoff.Json.bundle.diffHeaderCount -gt 0) "bundle=$($handoff.Json.bundle | ConvertTo-Json -Compress)"
            Assert-True "producer handoff records desktop proof pending in bundle" ($handoff.Json.bundle.desktopFinalProof -eq "evidence_needed") "bundle=$($handoff.Json.bundle | ConvertTo-Json -Compress)"
            Assert-True "producer handoff keeps desktop proof pending" ($handoff.Json.desktopFinalProof -eq "evidence_needed") "desktopFinalProof=$($handoff.Json.desktopFinalProof)"
            Assert-True "producer handoff reports no raw secret leak" ([int]$handoff.Json.rawSecretPatternHits -eq 0) "rawSecretPatternHits=$($handoff.Json.rawSecretPatternHits)"
            Assert-True "producer handoff writes audit log" (Test-Path -LiteralPath $audit) "missing $audit"
            if (Test-Path -LiteralPath $audit) {
                $auditRow = Get-Content -LiteralPath $audit | Select-Object -First 1 | ConvertFrom-Json
                $allowedAuditFields = @("requestId","sessionId","nodeRole","toolName","inputHash","outputCount","elapsedMs","decision","failReason")
                $extraFields = @($auditRow.PSObject.Properties.Name | Where-Object { $allowedAuditFields -notcontains $_ })
                Assert-True "producer handoff audit has allowlisted fields" ($extraFields.Count -eq 0) "extraFields=$($extraFields -join ',') audit=$(Get-Content -LiteralPath $audit -Raw)"
                Assert-True "producer handoff audit records handoff status" ($auditRow.toolName -eq "producer_handoff" -and $auditRow.nodeRole -eq "macmini" -and $auditRow.decision -eq "producer_handoff" -and [int]$auditRow.outputCount -eq 1) "audit=$(Get-Content -LiteralPath $audit -Raw)"
                Assert-True "producer handoff audit has no secret-like values" (-not (Test-HasSecretLikeValue (Get-Content -LiteralPath $audit -Raw))) "audit=$(Get-Content -LiteralPath $audit -Raw)"
            }
        }
        $bundle = Join-Path $patchDrop "macmini\mcp-handoff-test-macmini-v3.patch"
        Assert-True "producer handoff writes nested patch" (Test-Path -LiteralPath $bundle) "$bundle missing"
        if ((Test-Path -LiteralPath $bundle) -and $handoff.Json) {
            $expectedPatchHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $bundle).Hash.ToLowerInvariant()
            Assert-True "producer handoff patch hash matches patch contents" ($handoff.Json.bundle.patchHash -eq $expectedPatchHash) "expected=$expectedPatchHash bundle=$($handoff.Json.bundle | ConvertTo-Json -Compress)"
        }
        Assert-True "producer handoff writes pending notice" (Test-Path -LiteralPath (Join-Path $patchDrop "mcp-handoff-test.macmini-pending.md")) "pending notice missing"
        if (Test-Path -LiteralPath (Join-Path $patchDrop "macmini\mcp-handoff-test-macmini-v3.sha256.txt")) {
            $shaText = Get-Content -LiteralPath (Join-Path $patchDrop "macmini\mcp-handoff-test-macmini-v3.sha256.txt") -Raw
            Assert-True "producer handoff sha sidecar covers pending notice" ($shaText -match "\.\./mcp-handoff-test\.macmini-pending\.md") "sha=$shaText"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerHandoffRejectsProducerShaMismatch {
    Assert-True "producer handoff script exists for producer sha mismatch" (Test-Path -LiteralPath $ProducerHandoff) "missing $ProducerHandoff"
    if (-not (Test-Path -LiteralPath $ProducerHandoff)) { return }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer handoff sha mismatch skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-handoff-sha-mismatch-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $patchDrop = Join-Path $tmp "patchdrop"
    $fakeProducer = Join-Path $repo "__patch_drop__\fake_producer_bundle.py"
    New-Item -ItemType Directory -Force -Path $repo, $patchDrop, (Join-Path $repo "__patch_drop__") | Out-Null
    try {
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $repo "src") | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\smoke.txt") -Encoding UTF8 -Value "before"
        git -C $repo add src/smoke.txt | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\smoke.txt") -Encoding UTF8 -Value "after"

        @'
#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
from pathlib import Path

def slugify(value):
    return re.sub(r"[^a-z0-9._-]+", "-", value.strip().lower()).strip("-")

def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

parser = argparse.ArgumentParser()
parser.add_argument("--topic", required=True)
parser.add_argument("--node", required=True)
parser.add_argument("--source-root", required=True)
parser.add_argument("--patchdrop-root", required=True)
parser.add_argument("--pathspec", action="append", nargs="+", required=True)
args = parser.parse_args()

slug = slugify(args.topic)
bundle = f"{slug}-{args.node}-v3"
patchdrop = Path(args.patchdrop_root)
node_dir = patchdrop / args.node
patch = node_dir / f"{bundle}.patch"
report = node_dir / f"{bundle}.report.md"
verify = node_dir / f"{bundle}.verify.log"
manifest = node_dir / f"{bundle}.manifest.json"
sha_sidecar = node_dir / f"{bundle}.sha256.txt"
pending = patchdrop / f"{slug}.{args.node}-pending.md"

write(patch, "diff --git a/src/smoke.txt b/src/smoke.txt\n--- a/src/smoke.txt\n+++ b/src/smoke.txt\n@@ -1 +1 @@\n-before\n+after\n")
write(report, "Desktop final proof: evidence_needed\n")
write(verify, "secretPatternHits=0\nDesktop final proof: evidence_needed\n")
write(manifest, json.dumps({
    "schemaVersion": "patchdrop-producer-v3",
    "node": args.node,
    "topic": slug,
    "bundle": bundle,
    "activePatch": f"{bundle}.patch",
    "sourceIsolation": {
        "guard": "PASS",
        "sourceRootKind": "local-worktree",
        "sharedSourceRoot": False,
        "desktopCanonicalSourceRoot": False,
        "directCanonicalSourceEdit": False
    },
    "desktopFinalProof": "evidence_needed",
    "verification": {"diffHeaderCount": 1, "secretPatternHits": 0}
}, indent=2) + "\n")
write(pending, "Desktop final proof: evidence_needed\n")
write(sha_sidecar, "\n".join([
    "0" * 64 + f"  {bundle}.patch",
    sha(report) + f"  {bundle}.report.md",
    sha(verify) + f"  {bundle}.verify.log",
    sha(manifest) + f"  {bundle}.manifest.json",
    sha(pending) + f"  ../{slug}.{args.node}-pending.md",
]) + "\n")
print(f"[producer-bundle][wrote] node={args.node} topic={slug} patch={args.node}\\{bundle}.patch secretPatternHits=0 desktopFinalProof=evidence_needed")
'@ | Set-Content -LiteralPath $fakeProducer -Encoding UTF8

        $handoff = Invoke-ProducerHandoffJson $repo $patchDrop "src/smoke.txt" "mcp-handoff-sha-mismatch" "" $fakeProducer
        Assert-True "producer handoff rejects producer sha mismatch" ($handoff.ExitCode -ne 0) "output=$($handoff.Raw)"
        Assert-True "producer handoff emits JSON for producer sha mismatch" ($null -ne $handoff.Json) "output=$($handoff.Raw)"
        if ($handoff.Json) {
            Assert-True "producer handoff keeps sha mismatch promotion blocked" ($handoff.Json.bundle.promotionReady -eq $false -and $handoff.Json.bundle.ok -eq $false) "bundle=$($handoff.Json.bundle | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "producer handoff reports sha mismatch failure class" ($handoff.Json.failReason -match "producer-sha-mismatch" -and $handoff.Json.failReason -match "patch") "failReason=$($handoff.Json.failReason) json=$($handoff.Raw)"
            Assert-True "producer handoff records sha verification failure" ($handoff.Json.bundle.shaVerified -eq $false) "bundle=$($handoff.Json.bundle | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerBundleHelpersRejectForbiddenPatchTargets {
    Assert-True "producer bundle helpers exist for forbidden target guard" ((Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.py")) -and (Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.ps1"))) "producer bundle helpers missing"
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer bundle forbidden target guard skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-producer-forbidden-target-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $pyPatchDrop = Join-Path $tmp "patchdrop-py"
    $psPatchDrop = Join-Path $tmp "patchdrop-ps"
    New-Item -ItemType Directory -Force -Path $repo, $pyPatchDrop, $psPatchDrop, (Join-Path $repo "pages\api") | Out-Null
    try {
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "pages\api\unsafe.ts") -Encoding UTF8 -Value "export const before = true;"
        git -C $repo add pages/api/unsafe.ts | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "pages\api\unsafe.ts") -Encoding UTF8 -Value "export const after = true;"

        $pyLines = & python (Join-Path $Root "__patch_drop__\producer_bundle.py") --topic "producer forbidden target" --node macmini --source-root $repo --patchdrop-root $pyPatchDrop --pathspec "pages/api/unsafe.ts" 2>&1 | ForEach-Object { $_.ToString() }
        $pyExit = $LASTEXITCODE
        $pyRaw = $pyLines -join "`n"
        Assert-True "python producer bundle rejects forbidden patch target" ($pyExit -ne 0 -and $pyRaw -match "forbidden-path") "exit=$pyExit output=$pyRaw"
        $pyPatchCount = (Get-ChildItem -LiteralPath $pyPatchDrop -Recurse -Filter "*.patch" -ErrorAction SilentlyContinue | Measure-Object).Count
        Assert-True "python producer bundle writes no forbidden patch sidecar" ($pyPatchCount -eq 0) "patchCount=$pyPatchCount output=$pyRaw"

        $psLines = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "__patch_drop__\producer_bundle.ps1") -Topic "producer forbidden target" -Node macmini -SourceRoot $repo -PatchDropRoot $psPatchDrop -PathSpec "pages/api/unsafe.ts" 2>&1 | ForEach-Object { $_.ToString() }
        $psExit = $LASTEXITCODE
        $psRaw = $psLines -join "`n"
        Assert-True "PowerShell producer bundle rejects forbidden patch target" ($psExit -ne 0 -and $psRaw -match "forbidden-path") "exit=$psExit output=$psRaw"
        $psPatchCount = (Get-ChildItem -LiteralPath $psPatchDrop -Recurse -Filter "*.patch" -ErrorAction SilentlyContinue | Measure-Object).Count
        Assert-True "PowerShell producer bundle writes no forbidden patch sidecar" ($psPatchCount -eq 0) "patchCount=$psPatchCount output=$psRaw"
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerBundleHelpersIncludeStagedChanges {
    Assert-True "producer bundle helpers exist for staged change guard" ((Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.py")) -and (Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.ps1"))) "producer bundle helpers missing"
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer bundle staged change guard skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-producer-staged-change-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $pyPatchDrop = Join-Path $tmp "patchdrop-py"
    $psPatchDrop = Join-Path $tmp "patchdrop-ps"
    New-Item -ItemType Directory -Force -Path $repo, $pyPatchDrop, $psPatchDrop, (Join-Path $repo "src") | Out-Null
    try {
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        $stagedPath = Join-Path $repo "src\staged.txt"
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText($stagedPath, "before`n", $utf8NoBom)
        git -C $repo add src/staged.txt | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        [System.IO.File]::WriteAllText($stagedPath, "after`n", $utf8NoBom)
        git -C $repo add src/staged.txt | Out-Null

        $pyLines = & python (Join-Path $Root "__patch_drop__\producer_bundle.py") --topic "producer staged changes" --node macmini --source-root $repo --patchdrop-root $pyPatchDrop --pathspec "src/staged.txt" 2>&1 | ForEach-Object { $_.ToString() }
        $pyExit = $LASTEXITCODE
        $pyRaw = $pyLines -join "`n"
        Assert-True "python producer bundle includes staged changes" ($pyExit -eq 0) "exit=$pyExit output=$pyRaw"
        $pyPatch = Join-Path $pyPatchDrop "macmini\producer-staged-changes-macmini-v3.patch"
        Assert-True "python producer bundle writes staged patch" (Test-Path -LiteralPath $pyPatch) "patch=$pyPatch output=$pyRaw"
        if (Test-Path -LiteralPath $pyPatch) {
            $pyPatchText = Get-Content -LiteralPath $pyPatch -Raw
            Assert-True "python staged patch contains staged hunk" ($pyPatchText -match "(?m)^\+after$" -and $pyPatchText -match "(?m)^-before$") "patch=$pyPatchText"
        }

        $psLines = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "__patch_drop__\producer_bundle.ps1") -Topic "producer staged changes" -Node macmini -SourceRoot $repo -PatchDropRoot $psPatchDrop -PathSpec "src/staged.txt" 2>&1 | ForEach-Object { $_.ToString() }
        $psExit = $LASTEXITCODE
        $psRaw = $psLines -join "`n"
        Assert-True "PowerShell producer bundle includes staged changes" ($psExit -eq 0) "exit=$psExit output=$psRaw"
        $psPatch = Join-Path $psPatchDrop "macmini\producer-staged-changes-macmini-v3.patch"
        Assert-True "PowerShell producer bundle writes staged patch" (Test-Path -LiteralPath $psPatch) "patch=$psPatch output=$psRaw"
        if (Test-Path -LiteralPath $psPatch) {
            $psPatchText = Get-Content -LiteralPath $psPatch -Raw
            Assert-True "PowerShell staged patch contains staged hunk" ($psPatchText -match "(?m)^\+after$" -and $psPatchText -match "(?m)^-before$") "patch=$psPatchText"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-PeerEvidenceBusTriPerspectiveQueryPacket {
    $result = Invoke-ToolboxJson "peer_evidence_bus" @{
        nodeRole = "desktop"
        root = $script:Root
        targetMetric = "tri-perspective-harvest"
    }

    Assert-True `
        "peer_evidence_bus tri-perspective exits zero" `
        ($result.ExitCode -eq 0) `
        "output=$($result.Raw)"

    if ($result.ExitCode -ne 0 -or $null -eq $result.Json) {
        return
    }

    $packet = $result.Json.triPerspectiveQueryPacket
    $roles = @($packet.roles | ForEach-Object { $_.name })

    Assert-True `
        "tri-perspective packet exists" `
        ($null -ne $packet) `
        "json=$($result.Raw)"

    Assert-True `
        "tri-perspective roles are SUPPORT FALSIFY HOLD" `
        (($roles -join ",") -eq "SUPPORT,FALSIFY,HOLD") `
        "roles=$($roles -join ',')"

    Assert-True `
        "tri-perspective limits candidates and calls" `
        ([int]$packet.candidateLimit -eq 3 -and
         [int]$packet.activeCandidateLimit -eq 1 -and
         [int]$packet.modelCallLimitPerCandidate -eq 3) `
        "packet=$($packet | ConvertTo-Json -Depth 20 -Compress)"

    Assert-True `
        "tri-perspective promotion gate matches Java runtime" `
        ([double]$packet.promotionGate.minimumGrounding -eq 0.70 -and
         [double]$packet.promotionGate.minimumScoreGap -eq 0.05 -and
         $packet.promotionGate.safetyGate -eq "PASS" -and
         $packet.promotionGate.evidenceStatus -eq "SUFFICIENT" -and
         $packet.promotionGate.advisoryOnly -eq $true) `
        "gate=$($packet.promotionGate | ConvertTo-Json -Compress)"

    Assert-True `
        "tri-perspective packet stores no raw prompt or query" `
        ($packet.evidenceContract.rawPromptStored -eq $false -and
         $packet.evidenceContract.rawQueryStored -eq $false -and
         -not (Test-HasSecretLikeValue $result.Raw)) `
        "raw=$($result.Raw)"
}

function Test-ProducerBundleHelpersRejectDesktopCanonicalRoot {
    Assert-True "producer bundle helpers exist for Desktop canonical root guard" ((Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.py")) -and (Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.ps1"))) "producer bundle helpers missing"
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer bundle Desktop canonical root guard skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-producer-canonical-root-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $pyPatchDrop = Join-Path $tmp "patchdrop-py"
    $psPatchDrop = Join-Path $tmp "patchdrop-ps"
    $poisonedPyPatchDrop = Join-Path $tmp "patchdrop-poisoned-py"
    $poisonedPsPatchDrop = Join-Path $tmp "patchdrop-poisoned-ps"
    $previousCanonicalRoot = $env:AWX_DESKTOP_CANONICAL_ROOT
    New-Item -ItemType Directory -Force -Path $repo, $pyPatchDrop, $psPatchDrop, $poisonedPyPatchDrop, $poisonedPsPatchDrop, (Join-Path $repo "src") | Out-Null
    try {
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        [IO.File]::WriteAllText((Join-Path $repo "src\tracked.txt"), "before`n", [Text.UTF8Encoding]::new($false))
        git -C $repo add src/tracked.txt | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        [IO.File]::WriteAllText((Join-Path $repo "src\tracked.txt"), "after`n", [Text.UTF8Encoding]::new($false))
        $env:AWX_DESKTOP_CANONICAL_ROOT = $repo

        $pyLines = & python (Join-Path $Root "__patch_drop__\producer_bundle.py") --topic "producer canonical root" --node macmini --source-root $repo --patchdrop-root $pyPatchDrop --pathspec "src/tracked.txt" 2>&1 | ForEach-Object { $_.ToString() }
        $pyExit = $LASTEXITCODE
        $pyRaw = $pyLines -join "`n"
        Assert-True "python producer bundle rejects Desktop canonical root" ($pyExit -ne 0 -and $pyRaw -match "source-isolation-violation") "exit=$pyExit output=$pyRaw"
        Assert-True "python producer bundle writes no canonical-root patch" ((Get-ChildItem -LiteralPath $pyPatchDrop -Recurse -Filter "*.patch" -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0) "output=$pyRaw"

        $psLines = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "__patch_drop__\producer_bundle.ps1") -Topic "producer canonical root" -Node macmini -SourceRoot $repo -PatchDropRoot $psPatchDrop -PathSpec "src/tracked.txt" 2>&1 | ForEach-Object { $_.ToString() }
        $psExit = $LASTEXITCODE
        $psRaw = $psLines -join "`n"
        Assert-True "PowerShell producer bundle rejects Desktop canonical root" ($psExit -ne 0 -and $psRaw -match "source-isolation-violation") "exit=$psExit output=$psRaw"
        Assert-True "PowerShell producer bundle writes no canonical-root patch" ((Get-ChildItem -LiteralPath $psPatchDrop -Recurse -Filter "*.patch" -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0) "output=$psRaw"

        $env:AWX_DESKTOP_CANONICAL_ROOT = $repo
        $poisonedPyLines = & python (Join-Path $Root "__patch_drop__\producer_bundle.py") --topic "producer poisoned canonical root" --node macmini --source-root $Root --patchdrop-root $poisonedPyPatchDrop --pathspec "scripts/awx_mcp_toolbox.py" 2>&1 | ForEach-Object { $_.ToString() }
        $poisonedPyExit = $LASTEXITCODE
        $poisonedPyRaw = $poisonedPyLines -join "`n"
        Assert-True "python producer bundle rejects fixed Desktop root despite poisoned override" ($poisonedPyExit -ne 0 -and $poisonedPyRaw -match "source-isolation-violation") "exit=$poisonedPyExit output=$poisonedPyRaw"
        Assert-True "python poisoned override writes no canonical-root patch" ((Get-ChildItem -LiteralPath $poisonedPyPatchDrop -Recurse -Filter "*.patch" -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0) "output=$poisonedPyRaw"

        $poisonedPsLines = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "__patch_drop__\producer_bundle.ps1") -Topic "producer poisoned canonical root" -Node macmini -SourceRoot $Root -PatchDropRoot $poisonedPsPatchDrop -PathSpec "scripts/awx_mcp_toolbox.py" 2>&1 | ForEach-Object { $_.ToString() }
        $poisonedPsExit = $LASTEXITCODE
        $poisonedPsRaw = $poisonedPsLines -join "`n"
        Assert-True "PowerShell producer bundle rejects fixed Desktop root despite poisoned override" ($poisonedPsExit -ne 0 -and $poisonedPsRaw -match "source-isolation-violation") "exit=$poisonedPsExit output=$poisonedPsRaw"
        Assert-True "PowerShell poisoned override writes no canonical-root patch" ((Get-ChildItem -LiteralPath $poisonedPsPatchDrop -Recurse -Filter "*.patch" -ErrorAction SilentlyContinue | Measure-Object).Count -eq 0) "output=$poisonedPsRaw"
    } finally {
        if ($null -eq $previousCanonicalRoot) {
            Remove-Item Env:\AWX_DESKTOP_CANONICAL_ROOT -ErrorAction SilentlyContinue
        } else {
            $env:AWX_DESKTOP_CANONICAL_ROOT = $previousCanonicalRoot
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerHandoffRejectsUnsafeFilemodeBundle {
    Assert-True "producer handoff script exists for unsafe filemode bundle" (Test-Path -LiteralPath $ProducerHandoff) "missing $ProducerHandoff"
    if (-not (Test-Path -LiteralPath $ProducerHandoff)) { return }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer handoff unsafe filemode bundle skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-handoff-filemode-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $patchDrop = Join-Path $tmp "patchdrop"
    $fakeProducer = Join-Path $repo "__patch_drop__\fake_unsafe_producer.py"
    New-Item -ItemType Directory -Force -Path $repo, $patchDrop, (Join-Path $repo "__patch_drop__"), (Join-Path $repo "src") | Out-Null
    try {
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\smoke.txt") -Encoding UTF8 -Value "before"
        git -C $repo add src/smoke.txt | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\smoke.txt") -Encoding UTF8 -Value "after"

        @'
#!/usr/bin/env python3
import argparse
import hashlib
import json
import re
from pathlib import Path

def slugify(value):
    return re.sub(r"[^a-z0-9._-]+", "-", value.strip().lower()).strip("-")

def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

parser = argparse.ArgumentParser()
parser.add_argument("--topic", required=True)
parser.add_argument("--node", required=True)
parser.add_argument("--source-root", required=True)
parser.add_argument("--patchdrop-root", required=True)
parser.add_argument("--pathspec", action="append", nargs="+", required=True)
args = parser.parse_args()

slug = slugify(args.topic)
bundle = f"{slug}-{args.node}-v3"
patchdrop = Path(args.patchdrop_root)
node_dir = patchdrop / args.node
patch = node_dir / f"{bundle}.patch"
report = node_dir / f"{bundle}.report.md"
verify = node_dir / f"{bundle}.verify.log"
manifest = node_dir / f"{bundle}.manifest.json"
sha_sidecar = node_dir / f"{bundle}.sha256.txt"
pending = patchdrop / f"{slug}.{args.node}-pending.md"

write(patch, "diff --git a/src/new-file.txt b/src/new-file.txt\nnew file mode 100755\nindex 0000000..1111111\n--- /dev/null\n+++ b/src/new-file.txt\n@@ -0,0 +1 @@\n+created\n")
write(report, "Desktop final proof: evidence_needed\n")
write(verify, "secretPatternHits=0\nDesktop final proof: evidence_needed\n")
write(manifest, json.dumps({
    "schemaVersion": "patchdrop-producer-v3",
    "node": args.node,
    "topic": slug,
    "activePatch": f"{bundle}.patch",
    "sourceIsolation": {
        "guard": "PASS",
        "sourceRootKind": "local-worktree",
        "sharedSourceRoot": False,
        "desktopCanonicalSourceRoot": False,
        "directCanonicalSourceEdit": False,
        "gitRootPresent": True,
        "gitRootMatchesSourceRoot": True,
        "gitRootHash": "fixture-git-root"
    },
    "desktopFinalProof": "evidence_needed",
    "verification": {
        "diffHeaderCount": 1,
        "filemodeLineCount": 1,
        "allowedNewFileCount": 1,
        "filemodeViolationCount": 0,
        "secretPatternHits": 0
    }
}, indent=2) + "\n")
write(pending, "Desktop final proof: evidence_needed\n")
write(sha_sidecar, "\n".join([
    sha(patch) + f"  {bundle}.patch",
    sha(report) + f"  {bundle}.report.md",
    sha(verify) + f"  {bundle}.verify.log",
    sha(manifest) + f"  {bundle}.manifest.json",
    sha(pending) + f"  ../{slug}.{args.node}-pending.md",
]) + "\n")
print(f"[producer-bundle][wrote] node={args.node} topic={slug} patch={args.node}\\{bundle}.patch secretPatternHits=0 desktopFinalProof=evidence_needed")
'@ | Set-Content -LiteralPath $fakeProducer -Encoding UTF8

        $handoff = Invoke-ProducerHandoffJson $repo $patchDrop "src/smoke.txt" "mcp-handoff-filemode" "" $fakeProducer
        Assert-True "producer handoff rejects SHA-valid unsafe filemode bundle" ($handoff.ExitCode -ne 0) "output=$($handoff.Raw)"
        Assert-True "producer handoff unsafe filemode emits JSON" ($null -ne $handoff.Json) "output=$($handoff.Raw)"
        if ($handoff.Json) {
            Assert-True "producer handoff unsafe filemode blocks promotion" ($handoff.Json.bundle.promotionReady -eq $false -and $handoff.Json.bundle.ok -eq $false) "bundle=$($handoff.Json.bundle | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "producer handoff unsafe filemode preserves failure class" ($handoff.Json.failReason -match "filemode-blocked" -and $handoff.Json.bundle.failReason -match "filemode-blocked") "json=$($handoff.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerBundleHelpersIncludeCumulativeTrackedState {
    Assert-True "producer bundle helpers exist for cumulative tracked state" ((Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.py")) -and (Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.ps1"))) "producer bundle helpers missing"
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer bundle cumulative tracked state skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-producer-cumulative-tracked-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $pyPatchDrop = Join-Path $tmp "patchdrop-py"
    $psPatchDrop = Join-Path $tmp "patchdrop-ps"
    New-Item -ItemType Directory -Force -Path $repo, $pyPatchDrop, $psPatchDrop, (Join-Path $repo "src") | Out-Null
    try {
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        $trackedPath = Join-Path $repo "src\tracked.txt"
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText($trackedPath, "before`n", $utf8NoBom)
        git -C $repo add src/tracked.txt | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        [System.IO.File]::WriteAllText($trackedPath, "staged-intermediate`n", $utf8NoBom)
        git -C $repo add src/tracked.txt | Out-Null
        [System.IO.File]::WriteAllText($trackedPath, "worktree-final`n", $utf8NoBom)
        $indexHashBefore = (Get-FileHash -LiteralPath (Join-Path $repo ".git\index") -Algorithm SHA256).Hash

        $pyLines = & python (Join-Path $Root "__patch_drop__\producer_bundle.py") --topic "producer cumulative tracked" --node macmini --source-root $repo --patchdrop-root $pyPatchDrop --pathspec "src/tracked.txt" 2>&1 | ForEach-Object { $_.ToString() }
        $pyExit = $LASTEXITCODE
        $pyRaw = $pyLines -join "`n"
        Assert-True "python producer bundle writes cumulative tracked state" ($pyExit -eq 0) "exit=$pyExit output=$pyRaw"
        $pyPatch = Join-Path $pyPatchDrop "macmini\producer-cumulative-tracked-macmini-v3.patch"
        Assert-True "python cumulative tracked patch exists" (Test-Path -LiteralPath $pyPatch) "patch=$pyPatch output=$pyRaw"
        if (Test-Path -LiteralPath $pyPatch) {
            $pyPatchText = Get-Content -LiteralPath $pyPatch -Raw
            Assert-True "python cumulative tracked patch uses final worktree bytes" ($pyPatchText -match "(?m)^\+worktree-final$" -and $pyPatchText -notmatch "(?m)^\+staged-intermediate$") "patch=$pyPatchText"
        }
        Assert-True "python cumulative tracked producer leaves real index unchanged" ((Get-FileHash -LiteralPath (Join-Path $repo ".git\index") -Algorithm SHA256).Hash -eq $indexHashBefore) "real index changed"

        $psLines = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "__patch_drop__\producer_bundle.ps1") -Topic "producer cumulative tracked" -Node macmini -SourceRoot $repo -PatchDropRoot $psPatchDrop -PathSpec "src/tracked.txt" 2>&1 | ForEach-Object { $_.ToString() }
        $psExit = $LASTEXITCODE
        $psRaw = $psLines -join "`n"
        Assert-True "PowerShell producer bundle writes cumulative tracked state" ($psExit -eq 0) "exit=$psExit output=$psRaw"
        $psPatch = Join-Path $psPatchDrop "macmini\producer-cumulative-tracked-macmini-v3.patch"
        Assert-True "PowerShell cumulative tracked patch exists" (Test-Path -LiteralPath $psPatch) "patch=$psPatch output=$psRaw"
        if (Test-Path -LiteralPath $psPatch) {
            $psPatchText = Get-Content -LiteralPath $psPatch -Raw
            Assert-True "PowerShell cumulative tracked patch uses final worktree bytes" ($psPatchText -match "(?m)^\+worktree-final$" -and $psPatchText -notmatch "(?m)^\+staged-intermediate$") "patch=$psPatchText"
        }
        Assert-True "PowerShell cumulative tracked producer leaves real index unchanged" ((Get-FileHash -LiteralPath (Join-Path $repo ".git\index") -Algorithm SHA256).Hash -eq $indexHashBefore) "real index changed"
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerBundleHelpersIncludeExplicitUntrackedNewFile {
    Assert-True "producer bundle helpers exist for explicit untracked new file" ((Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.py")) -and (Test-Path -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.ps1"))) "producer bundle helpers missing"
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer bundle explicit untracked new file skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-producer-untracked-new-file-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $pyPatchDrop = Join-Path $tmp "patchdrop-py"
    $psPatchDrop = Join-Path $tmp "patchdrop-ps"
    New-Item -ItemType Directory -Force -Path $repo, $pyPatchDrop, $psPatchDrop, (Join-Path $repo "src") | Out-Null
    try {
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText((Join-Path $repo "README.md"), "baseline`n", $utf8NoBom)
        git -C $repo add README.md | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        [System.IO.File]::WriteAllText((Join-Path $repo "src\new-file.txt"), "created`n", $utf8NoBom)
        $statusBefore = ((git -C $repo status --porcelain=v1 --untracked-files=all) | ForEach-Object { $_.ToString() }) -join "`n"
        $indexHashBefore = (Get-FileHash -LiteralPath (Join-Path $repo ".git\index") -Algorithm SHA256).Hash

        $pyLines = & python (Join-Path $Root "__patch_drop__\producer_bundle.py") --topic "producer untracked new file" --node macmini --source-root $repo --patchdrop-root $pyPatchDrop --pathspec "src/new-file.txt" 2>&1 | ForEach-Object { $_.ToString() }
        $pyExit = $LASTEXITCODE
        $pyRaw = $pyLines -join "`n"
        Assert-True "python producer bundle includes explicit untracked new file" ($pyExit -eq 0) "exit=$pyExit output=$pyRaw"
        $pyPatch = Join-Path $pyPatchDrop "macmini\producer-untracked-new-file-macmini-v3.patch"
        Assert-True "python explicit untracked patch exists" (Test-Path -LiteralPath $pyPatch) "patch=$pyPatch output=$pyRaw"
        if (Test-Path -LiteralPath $pyPatch) {
            $pyPatchText = Get-Content -LiteralPath $pyPatch -Raw
            Assert-True "python explicit untracked patch has canonical creation block" (
                $pyPatchText -match "(?m)^new file mode 100644$" -and
                $pyPatchText -match "(?m)^--- /dev/null$" -and
                $pyPatchText -match "(?m)^\+\+\+ b/src/new-file\.txt$" -and
                $pyPatchText -match "(?m)^\+created$"
            ) "patch=$pyPatchText"
        }
        $statusAfterPython = ((git -C $repo status --porcelain=v1 --untracked-files=all) | ForEach-Object { $_.ToString() }) -join "`n"
        Assert-True "python explicit untracked producer leaves worktree status unchanged" ($statusAfterPython -eq $statusBefore) "before=$statusBefore after=$statusAfterPython"
        Assert-True "python explicit untracked producer leaves real index unchanged" ((Get-FileHash -LiteralPath (Join-Path $repo ".git\index") -Algorithm SHA256).Hash -eq $indexHashBefore) "real index changed"

        $psLines = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $Root "__patch_drop__\producer_bundle.ps1") -Topic "producer untracked new file" -Node macmini -SourceRoot $repo -PatchDropRoot $psPatchDrop -PathSpec "src/new-file.txt" 2>&1 | ForEach-Object { $_.ToString() }
        $psExit = $LASTEXITCODE
        $psRaw = $psLines -join "`n"
        Assert-True "PowerShell producer bundle includes explicit untracked new file" ($psExit -eq 0) "exit=$psExit output=$psRaw"
        $psPatch = Join-Path $psPatchDrop "macmini\producer-untracked-new-file-macmini-v3.patch"
        Assert-True "PowerShell explicit untracked patch exists" (Test-Path -LiteralPath $psPatch) "patch=$psPatch output=$psRaw"
        if (Test-Path -LiteralPath $psPatch) {
            $psPatchText = Get-Content -LiteralPath $psPatch -Raw
            Assert-True "PowerShell explicit untracked patch has canonical creation block" (
                $psPatchText -match "(?m)^new file mode 100644$" -and
                $psPatchText -match "(?m)^--- /dev/null$" -and
                $psPatchText -match "(?m)^\+\+\+ b/src/new-file\.txt$" -and
                $psPatchText -match "(?m)^\+created$"
            ) "patch=$psPatchText"
        }
        $statusAfterPowerShell = ((git -C $repo status --porcelain=v1 --untracked-files=all) | ForEach-Object { $_.ToString() }) -join "`n"
        Assert-True "PowerShell explicit untracked producer leaves worktree status unchanged" ($statusAfterPowerShell -eq $statusBefore) "before=$statusBefore after=$statusAfterPowerShell"
        Assert-True "PowerShell explicit untracked producer leaves real index unchanged" ((Get-FileHash -LiteralPath (Join-Path $repo ".git\index") -Algorithm SHA256).Hash -eq $indexHashBefore) "real index changed"
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerPowerShellParserEnforcesExactTrackedHunks {
    $producerPath = Join-Path $Root '__patch_drop__\producer_bundle.ps1'
    $source = Get-Content -LiteralPath $producerPath -Raw
    $start = $source.IndexOf('function Get-DiffHeaderCount', [StringComparison]::Ordinal)
    $end = $source.IndexOf('$ResolvedSourceRoot =', [StringComparison]::Ordinal)
    Assert-True 'producer PowerShell exact parser definitions found' ($start -ge 0 -and $end -gt $start) "start=$start end=$end"
    if ($start -lt 0 -or $end -le $start) { return }
    $definitions = $source.Substring($start, $end - $start)
    $probe = [scriptblock]::Create($definitions + "`nGet-PatchStructureViolationCount -PatchText (`$args[0])")
    $pathProbe = [scriptblock]::Create($definitions + "`n@((Get-PatchTargetPaths -PatchText (`$args[0]))) -join '|'")
    $script:ForbiddenPatchPathPatterns = @()
    $envelope = @(
        'diff --git a/f.txt b/f.txt',
        '--- a/f.txt',
        '+++ b/f.txt'
    )
    $valid = ($envelope + @(
        '@@ -1,4 +1,4 @@', '-l1', '+L1', ' l2', ' l3', ' l4',
        '@@ -9,4 +9,4 @@ l8', ' l9', ' l10', ' l11', '-l12', '+L12', ''
    )) -join "`n"
    $validHeaderPayload = ($envelope + @('@@ -1 +1 @@', '--- old-content', '+++ new-content', '')) -join "`n"
    $validUnsafeLookingPayload = ($envelope + @('@@ -1 +1 @@', '--- ../../outside.txt', '+++ pages/api/unsafe.ts', '')) -join "`n"
    $invalid = @{
        deficit = ($envelope + @('@@ -1,2 +1,2 @@', '-l1', '+L1', '')) -join "`n"
        overflow = ($envelope + @('@@ -1 +1 @@', '-l1', '+L1', '+extra', '')) -join "`n"
        marker = ($envelope + @('@@ -1 +1 @@', '\ No newline at end of file', '-l1', '+L1', '')) -join "`n"
        trailing = ($envelope + @('@@ -1 +1 @@', '-l1', '+L1', 'TRAILER', '')) -join "`n"
        oldMarkerBeforeOldPayload = ($envelope + @('@@ -1,2 +1,2 @@', '-l1', '\ No newline at end of file', '-l2', '+L1', '+L2', '')) -join "`n"
        newMarkerBeforeNewPayload = ($envelope + @('@@ -1 +1,2 @@', '-l1', '+L1', '\ No newline at end of file', '+L2', '')) -join "`n"
        markerBeforeLaterHunk = ($envelope + @('@@ -1 +1 @@', '-l1', '+L1', '\ No newline at end of file', '@@ -3 +3 @@', '-l3', '+L3', '')) -join "`n"
        contextMarkerBeforeContext = ($envelope + @('@@ -1,2 +1,2 @@', ' l1', '\ No newline at end of file', ' l2', '')) -join "`n"
    }
    Assert-True 'producer PowerShell exact parser accepts valid multi-hunk' ([int](& $probe $valid) -eq 0) 'valid multi-hunk rejected'
    Assert-True 'producer PowerShell exact parser accepts header-like payload' ([int](& $probe $validHeaderPayload) -eq 0) 'valid header-like payload rejected'
    Assert-True 'producer PowerShell path scan ignores header-like payload' (([string](& $pathProbe $validUnsafeLookingPayload)) -eq 'f.txt') "paths=$(& $pathProbe $validUnsafeLookingPayload)"
    foreach ($entry in $invalid.GetEnumerator()) {
        Assert-True "producer PowerShell exact parser rejects $($entry.Key)" ([int](& $probe $entry.Value) -gt 0) 'malformed tracked hunk accepted'
    }
}

function Test-ProducerHandoffPropagatesProducerBundleFailureClass {
    Assert-True "producer handoff script exists for bundle failure propagation" (Test-Path -LiteralPath $ProducerHandoff) "missing $ProducerHandoff"
    if (-not (Test-Path -LiteralPath $ProducerHandoff)) { return }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer handoff bundle failure propagation skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-handoff-forbidden-target-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $patchDrop = Join-Path $tmp "patchdrop"
    New-Item -ItemType Directory -Force -Path $repo, $patchDrop, (Join-Path $repo "pages\api") | Out-Null
    try {
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "pages\api\unsafe.ts") -Encoding UTF8 -Value "export const before = true;"
        git -C $repo add pages/api/unsafe.ts | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "pages\api\unsafe.ts") -Encoding UTF8 -Value "export const after = true;"

        $handoff = Invoke-ProducerHandoffJson $repo $patchDrop "pages/api/unsafe.ts" "mcp-handoff-forbidden-target"
        Assert-True "producer handoff rejects forbidden bundle target" ($handoff.ExitCode -ne 0) "output=$($handoff.Raw)"
        Assert-True "producer handoff emits JSON for forbidden bundle target" ($null -ne $handoff.Json) "output=$($handoff.Raw)"
        if ($handoff.Json) {
            Assert-True "producer handoff preserves producer bundle failure class" ($handoff.Json.failReason -match "producer-bundle-failed" -and $handoff.Json.failReason -match "forbidden-path") "failReason=$($handoff.Json.failReason) json=$($handoff.Raw)"
            Assert-True "producer handoff records bundle failReason" ($handoff.Json.bundle.failReason -match "forbidden-path") "bundle=$($handoff.Json.bundle | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "producer handoff keeps promotion blocked" ($handoff.Json.bundle.promotionReady -eq $false) "bundle=$($handoff.Json.bundle | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "producer handoff reports no raw secret leak on forbidden bundle target" ([int]$handoff.Json.rawSecretPatternHits -eq 0) "rawSecretPatternHits=$($handoff.Json.rawSecretPatternHits)"
        }
        $patchCount = (Get-ChildItem -LiteralPath $patchDrop -Recurse -Filter "*.patch" -ErrorAction SilentlyContinue | Measure-Object).Count
        Assert-True "producer handoff writes no forbidden patch sidecar" ($patchCount -eq 0) "patchCount=$patchCount output=$($handoff.Raw)"
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerHandoffDefaultsToProducerLocalBundleHelper {
    Assert-True "producer handoff script exists for local helper default" (Test-Path -LiteralPath $ProducerHandoff) "missing $ProducerHandoff"
    if (-not (Test-Path -LiteralPath $ProducerHandoff)) { return }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer handoff local-helper default skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-handoff-local-helper-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $patchDrop = Join-Path $tmp "patchdrop"
    New-Item -ItemType Directory -Force -Path $repo, $patchDrop, (Join-Path $repo "__patch_drop__") | Out-Null
    try {
        Copy-Item -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.py") -Destination (Join-Path $repo "__patch_drop__\producer_bundle.py") -Force
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $repo "src") | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\default-helper.txt") -Encoding UTF8 -Value "before"
        git -C $repo add src/default-helper.txt | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\default-helper.txt") -Encoding UTF8 -Value "after"

        $args = @(
            $ProducerHandoff,
            "--source-root", $repo,
            "--canonical-root", $Root,
            "--patchdrop-root", $patchDrop,
            "--node-role", "macmini",
            "--topic", "mcp-handoff-local-helper-test",
            "--pathspec", "src/default-helper.txt"
        )
        $lines = & python @args 2>&1 | ForEach-Object { $_.ToString() }
        $exitCode = $LASTEXITCODE
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }

        Assert-True "producer handoff default uses producer-local helper" ($exitCode -eq 0) "output=$raw"
        if ($json) {
            Assert-True "producer handoff default bundle pass" ($json.bundle.ok -eq $true -and $json.bundle.sourceIsolation.sourceRootKind -eq "local-worktree") "json=$raw"
        }
        Assert-True "producer handoff default writes PatchDrop output" (Test-Path -LiteralPath (Join-Path $patchDrop "macmini\mcp-handoff-local-helper-test-macmini-v3.patch")) "patch missing"
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerHandoffRejectsSharedProducerScript {
    Assert-True "producer handoff script exists for shared helper guard" (Test-Path -LiteralPath $ProducerHandoff) "missing $ProducerHandoff"
    if (-not (Test-Path -LiteralPath $ProducerHandoff)) { return }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        Write-Pass "producer handoff shared-helper guard skipped because git is unavailable"
        return
    }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-handoff-shared-helper-" + [guid]::NewGuid().ToString("N"))
    $repo = Join-Path $tmp "repo"
    $patchDrop = Join-Path $tmp "patchdrop"
    $sharedProducer = Join-Path $patchDrop "producer_bundle.py"
    New-Item -ItemType Directory -Force -Path $repo, $patchDrop | Out-Null
    try {
        Copy-Item -LiteralPath (Join-Path $Root "__patch_drop__\producer_bundle.py") -Destination $sharedProducer -Force
        git -C $repo init 2>$null | Out-Null
        git -C $repo config user.email "awx@example.invalid" | Out-Null
        git -C $repo config user.name "AWX Test" | Out-Null
        New-Item -ItemType Directory -Force -Path (Join-Path $repo "src") | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\shared-helper.txt") -Encoding UTF8 -Value "before"
        git -C $repo add src/shared-helper.txt | Out-Null
        git -C $repo commit -m "baseline" 2>$null | Out-Null
        Set-Content -LiteralPath (Join-Path $repo "src\shared-helper.txt") -Encoding UTF8 -Value "after"

        $handoff = Invoke-ProducerHandoffJson $repo $patchDrop "src/shared-helper.txt" "mcp-handoff-shared-helper-test" "" $sharedProducer
        Assert-True "producer handoff rejects shared producer helper" ($handoff.ExitCode -ne 0) "output=$($handoff.Raw)"
        Assert-True "producer handoff emits JSON for shared producer helper" ($null -ne $handoff.Json) "output=$($handoff.Raw)"
        if ($handoff.Json) {
            Assert-True "producer handoff reports shared helper failure class" ($handoff.Json.failReason -match "producer-helper-shared") "failReason=$($handoff.Json.failReason) json=$($handoff.Raw)"
            Assert-True "producer handoff stops before smoke for shared helper" ($handoff.Json.smoke.decision -eq "not_run" -and $null -eq $handoff.Json.bundle.exitCode) "json=$($handoff.Raw)"
        }
        $patchCount = (Get-ChildItem -LiteralPath $patchDrop -Recurse -Filter "*.patch" -ErrorAction SilentlyContinue | Measure-Object).Count
        Assert-True "producer handoff writes no shared-helper patch sidecar" ($patchCount -eq 0) "patchCount=$patchCount output=$($handoff.Raw)"
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerDocsUseProducerLocalBundleHelpers {
    $docPaths = @(
        "__patch_drop__\README.md",
        "data\agent-handoff\mcp-control-tower\README.md",
        "agent-prompts\agents\demo1_three_node_smb_codex\system_ko.md",
        "agent-prompts\out\demo1_three_node_smb_codex.prompt",
        "agent-prompts\agents\demo1_mcp_control_tower\system.md",
        "agent-prompts\out\demo1_mcp_control_tower.prompt",
        ".agents\skills\demo1-mcp-control-tower\SKILL.md"
    )
    $forbidden = @(
        "/Volumes/NAS-WinSrc/__patch_drop__/producer_bundle.py",
        "/Volumes/NAS-WinSrc/__patch_drop__/producer_bundle.ps1",
        "/Volumes/WinSrc/demo-1/demo-1/src/__patch_drop__/producer_bundle.py",
        "/Volumes/WinSrc/demo-1/demo-1/src/__patch_drop__/producer_bundle.ps1",
        "Y:\__patch_drop__\producer_bundle.ps1",
        "Y:\__patch_drop__\producer_bundle.py",
        "Z:\PatchDrop\producer_bundle.py",
        "Z:\PatchDrop\producer_bundle.ps1"
    )
    $hits = @()
    foreach ($relativePath in $docPaths) {
        $path = Join-Path $Root $relativePath
        $text = Get-SafeRawContent $path
        if ([string]::IsNullOrWhiteSpace($text)) { continue }
        foreach ($bad in $forbidden) {
            if ($text.Contains($bad)) {
                $hits += "$relativePath contains $bad"
            }
        }
    }
    Assert-True "producer docs use producer-local bundle helpers" ($hits.Count -eq 0) "stale shared helper refs=$($hits -join '; ')"
}

function Test-ProducerHandoffRejectsUncSourceRootWithJson {
    Assert-True "producer handoff script exists for UNC guard" (Test-Path -LiteralPath $ProducerHandoff) "missing $ProducerHandoff"
    if (-not (Test-Path -LiteralPath $ProducerHandoff)) { return }

    $patchDrop = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-handoff-unc-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $patchDrop | Out-Null
    try {
        $handoff = Invoke-ProducerHandoffJson "\\awx-fake-host\WinSrc\demo-1\src" $patchDrop "README.md" "mcp-handoff-unc-test"
        Assert-True "producer handoff rejects UNC source root" ($handoff.ExitCode -ne 0) "expected nonzero exit; output=$($handoff.Raw)"
        Assert-True "producer handoff emits parseable JSON on UNC source root" ($null -ne $handoff.Json) "output=$($handoff.Raw)"
        if ($handoff.Json) {
            Assert-True "producer handoff classifies UNC source root as smb" (($handoff.Json.failReason -split ",") -contains "smb-direct-edit") "failReason=$($handoff.Json.failReason)"
            Assert-True "producer handoff keeps desktop proof pending on UNC failure" ($handoff.Json.desktopFinalProof -eq "evidence_needed") "desktopFinalProof=$($handoff.Json.desktopFinalProof)"
        }
        Assert-True "producer handoff does not print traceback for UNC source root" (-not ($handoff.Raw -match "Traceback|NotADirectoryError")) "output=$($handoff.Raw)"
    } finally {
        Remove-Item -LiteralPath $patchDrop -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerHandoffTreatsMappedDriveAsSharedRoot {
    Assert-True "producer handoff script exists for mapped-drive guard" (Test-Path -LiteralPath $ProducerHandoff) "missing $ProducerHandoff"
    if (-not ((Test-Path -LiteralPath $ProducerHandoff) -and (Get-Command python -ErrorAction SilentlyContinue))) {
        return
    }

    $testScript = Join-Path ([IO.Path]::GetTempPath()) ("awx-producer-handoff-mapped-drive-" + [guid]::NewGuid().ToString("N") + ".py")
    @'
import importlib.util
import pathlib
import sys

spec = importlib.util.spec_from_file_location("producer_handoff", pathlib.Path(sys.argv[1]))
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)
module.windows_mapped_drive_root = lambda raw_path: r"\\awx-host\WinSrc" if str(raw_path).lower().startswith("z:") else ""
assert module.is_shared_source_root(r"Z:\WinSrc\demo-1\src") is True
assert module.is_shared_source_root(r"C:\awx-local\worktree") is False
'@ | Set-Content -LiteralPath $testScript -Encoding UTF8

    try {
        $lines = & python $testScript $ProducerHandoff 2>&1 | ForEach-Object { $_.ToString() }
        Assert-True "producer handoff treats mapped drive as shared source root" ($LASTEXITCODE -eq 0) "output=$($lines -join "`n")"
    } finally {
        Remove-Item -LiteralPath $testScript -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerHandoffRejectsCanonicalSourceRootWithJson {
    Assert-True "producer handoff script exists for canonical root guard" (Test-Path -LiteralPath $ProducerHandoff) "missing $ProducerHandoff"
    if (-not (Test-Path -LiteralPath $ProducerHandoff)) { return }

    $cases = @(
        @{ Name = "exact"; SourceRoot = $Root; Topic = "mcp-handoff-canonical-exact-test" },
        @{ Name = "child"; SourceRoot = (Join-Path $Root "main"); Topic = "mcp-handoff-canonical-child-test" }
    )
    foreach ($case in $cases) {
        $patchDrop = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-handoff-canonical-" + $case.Name + "-" + [guid]::NewGuid().ToString("N"))
        New-Item -ItemType Directory -Force -Path $patchDrop | Out-Null
        try {
            $handoff = Invoke-ProducerHandoffJson $case.SourceRoot $patchDrop "README.md" $case.Topic
            Assert-True "producer handoff rejects canonical $($case.Name) source root" ($handoff.ExitCode -ne 0) "expected nonzero exit; output=$($handoff.Raw)"
            Assert-True "producer handoff emits parseable JSON on canonical $($case.Name) source root" ($null -ne $handoff.Json) "output=$($handoff.Raw)"
            if ($handoff.Json) {
                Assert-True "producer handoff classifies canonical $($case.Name) source root as smb" (($handoff.Json.failReason -split ",") -contains "smb-direct-edit") "failReason=$($handoff.Json.failReason)"
                Assert-True "producer handoff skips smoke for canonical $($case.Name) source root" ($handoff.Json.smoke.decision -eq "not_run") "smoke=$($handoff.Json.smoke | ConvertTo-Json -Compress)"
                Assert-True "producer handoff skips bundle for canonical $($case.Name) source root" ($null -eq $handoff.Json.bundle.exitCode) "bundle=$($handoff.Json.bundle | ConvertTo-Json -Compress)"
                Assert-True "producer handoff keeps desktop proof pending on canonical $($case.Name) failure" ($handoff.Json.desktopFinalProof -eq "evidence_needed") "desktopFinalProof=$($handoff.Json.desktopFinalProof)"
            }
            Assert-True "producer handoff does not print traceback for canonical $($case.Name) source root" (-not ($handoff.Raw -match "Traceback|NotADirectoryError")) "output=$($handoff.Raw)"
            $sidecarCount = (Get-ChildItem -LiteralPath $patchDrop -Recurse -File -ErrorAction SilentlyContinue | Measure-Object).Count
            Assert-True "producer handoff writes no sidecars for canonical $($case.Name) source root" ($sidecarCount -eq 0) "sidecarCount=$sidecarCount"
        } finally {
            Remove-Item -LiteralPath $patchDrop -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditCoversControlTowerObjective {
    Assert-True "completion audit script exists" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $audit = Invoke-CompletionAuditJson
    Assert-True "completion audit exits zero" ($audit.ExitCode -eq 0) "output=$($audit.Raw)"
    if ($audit.Json) {
        Assert-True "completion audit schema version" ($audit.Json.schemaVersion -eq "awx.mcp.completion_audit.v1") "schemaVersion=$($audit.Json.schemaVersion)"
        Assert-True "completion audit local contract ok" ($audit.Json.ok -eq $true) "json=$($audit.Raw)"
        Assert-True "completion audit has no local failures" (@($audit.Json.failures).Count -eq 0) "failures=$($audit.Json.failures | ConvertTo-Json -Compress)"
        $checked = @($audit.Json.checked | ForEach-Object { $_.id })
        foreach ($id in @(
            "roles.desktop-macmini-notebook",
            "tools.required-json-schemas",
            "tools.audit-log-inputs",
            "skills.task-launchers",
            "audit.redacted-fields",
            "toolbox.generic-audit-log",
            "pipeline.mcp-control-tower-runner",
            "janitor.regression-tests",
            "three-node.local-smoke",
            "safe-delete.path-presence",
            "source-governance-stability-proof",
            "source-edit-session-guard",
            "skills.safe-patch-flow",
            "archive.index-path-resolution",
            "archive.restore-pre-review-checksum",
            "producer.smoke-before-bundle",
            "producer.canonical-source-guard",
            "producer.handoff-promotion-ready-json",
            "producer.handoff-audit-log",
            "producer.handoff-bundle-failure-classification",
            "producer.command-plan-dispatch",
            "producer.bundle-filemode-guard",
            "producer.bundle-unified-diff-guard",
            "producer.bundle-forbidden-path-guard",
            "producer.bundle-unsafe-path-guard",
            "producer.helpers-path-safety-guard",
            "desktop.dispatch-packet",
            "desktop.dispatch-artifacts",
            "desktop.dispatch-handoff-proof",
            "mcp.client-config",
            "mcp.node-setup-runner",
            "mcp.node-setup-audit-log",
            "mcp.node-smoke-runner",
            "mcp.stdio-bridge",
            "mcp.prompt-get-role-briefs",
            "external.node-evidence-auditor",
            "external.producer-handoff-proof",
            "external.apply-gate-next-action",
            "notebook.support-reviewer-prompt",
            "nextjs.no-pages-api",
            "java.langchain4j-1.0.1",
            "java.mdc-trace-session",
            "chat-debug-events.readback-runtime-smoke"
        )) {
            Assert-True "completion audit checks $id" ($checked -contains $id) "checked=$($checked -join ',')"
        }
        $requirements = @($audit.Json.requirements)
        $requirementIds = @($requirements | ForEach-Object { $_.id })
        foreach ($id in @(
            "desktop-source-owner-final-verifier",
            "macmini-readonly-patch-producer",
            "notebook-support-reviewer",
            "mcp-tools-resources-prompts",
            "archive-search-two-pass-index",
            "archive-restore-audit-checksum",
            "producer-external-proof",
            "secret-safe-audit-log",
            "nextjs-app-router-only",
            "java-stack-purity",
            "python-external-tooling-layer",
            "mdc-trace-continuity",
            "janitor-regression-probes",
            "three-node-local-smoke",
            "safe-delete-readonly-gate",
            "source-governance-stability-proof"
        )) {
            Assert-True "completion audit requirement matrix includes $id" ($requirementIds -contains $id) "requirements=$($requirementIds -join ',')"
        }
        $externalReq = @($requirements | Where-Object { $_.id -eq "producer-external-proof" } | Select-Object -First 1)
        Assert-True "completion audit requirement matrix keeps external proof as evidence_needed" ($externalReq.status -eq "evidence_needed" -and @($externalReq.evidenceNeeded).Count -gt 0) "externalReq=$($externalReq | ConvertTo-Json -Depth 10 -Compress)"
        $desktopLoopCheck = @($audit.Json.checked | Where-Object { $_.id -eq "desktop.control-loop" } | Select-Object -First 1)
        Assert-True "completion audit records desktop readiness split" ($desktopLoopCheck.evidence -match "localReady" -and $desktopLoopCheck.evidence -match "completionReady") "desktopLoopCheck=$($desktopLoopCheck | ConvertTo-Json -Depth 10 -Compress)"
        $archiveReq = @($requirements | Where-Object { $_.id -eq "archive-search-two-pass-index" } | Select-Object -First 1)
        Assert-True "completion audit requirement matrix reports archive index evidence state" ($archiveReq.status -in @("satisfied", "evidence_needed")) "archiveReq=$($archiveReq | ConvertTo-Json -Depth 10 -Compress)"
        $janitorReq = @($requirements | Where-Object { $_.id -eq "janitor-regression-probes" } | Select-Object -First 1)
        Assert-True "completion audit requirement matrix covers janitor probes" ($janitorReq.status -eq "satisfied" -and $janitorReq.evidence -match "MISSING_META" -and $janitorReq.evidence -match "filemode-blocked" -and $janitorReq.evidence -match "sha mismatch" -and $janitorReq.evidence -match "report-only") "janitorReq=$($janitorReq | ConvertTo-Json -Depth 10 -Compress)"
        $threeNodeReq = @($requirements | Where-Object { $_.id -eq "three-node-local-smoke" } | Select-Object -First 1)
        Assert-True "completion audit tracks local three-node smoke without satisfying external proof" ($threeNodeReq.status -eq "satisfied" -and $threeNodeReq.evidence -match "three_node_patchdrop_smoke.ps1" -and $threeNodeReq.evidence -match "simulated") "threeNodeReq=$($threeNodeReq | ConvertTo-Json -Depth 10 -Compress)"
        $safeDeleteReq = @($requirements | Where-Object { $_.id -eq "safe-delete-readonly-gate" } | Select-Object -First 1)
        Assert-True "completion audit tracks safe delete readonly gate" ($safeDeleteReq.status -in @("satisfied", "incomplete") -and $safeDeleteReq.evidence -match "mutationAllowed=False" -and $safeDeleteReq.evidence -match "HOLD_SECRET_PATH" -and $safeDeleteReq.evidence -match "REVIEW_REPORT_OUTPUT") "safeDeleteReq=$($safeDeleteReq | ConvertTo-Json -Depth 10 -Compress)"
        $safeDeleteCheck = @($audit.Json.checked | Where-Object { $_.id -eq "safe-delete.path-presence" } | Select-Object -First 1)
        Assert-True "completion audit verifies safe delete artifact is non-mutating" ($safeDeleteCheck.evidence -match "mutationAllowed=False" -and $safeDeleteCheck.evidence -match "deleteCommandEmitted=False" -and $safeDeleteCheck.evidence -match "rawSecretPatternHits=0") "safeDeleteEvidence=$($safeDeleteCheck.evidence)"
        $sourceGovernanceReq = @($requirements | Where-Object { $_.id -eq "source-governance-stability-proof" } | Select-Object -First 1)
        Assert-True "completion audit requirement matrix covers source governance stability" ($sourceGovernanceReq.status -eq "satisfied" -and $sourceGovernanceReq.evidence -match "large-active-source-growth" -and $sourceGovernanceReq.evidence -match "source-changed-during-proof") "sourceGovernanceReq=$($sourceGovernanceReq | ConvertTo-Json -Depth 10 -Compress)"
        $sourceGovernanceCheck = @($audit.Json.checked | Where-Object { $_.id -eq "source-governance-stability-proof" } | Select-Object -First 1)
        Assert-True "completion audit verifies source governance stability proof" ($sourceGovernanceCheck.evidence -match "source_governance_proof_chain_v4.ps1" -and $sourceGovernanceCheck.evidence -match "large-active-source-growth" -and $sourceGovernanceCheck.evidence -match "source-changed-during-proof") "sourceGovernanceEvidence=$($sourceGovernanceCheck.evidence)"
        $sourceEditCheck = @($audit.Json.checked | Where-Object { $_.id -eq "source-edit-session-guard" } | Select-Object -First 1)
        Assert-True "completion audit verifies source edit session guard" ($sourceEditCheck.evidence -match "source_edit_session.ps1" -and $sourceEditCheck.evidence -match "smb-direct-edit" -and $sourceEditCheck.evidence -match "source-edit-locks") "sourceEditEvidence=$($sourceEditCheck.evidence)"
        Assert-True "completion audit verifies mapped drive source guard" ($sourceEditCheck.evidence -match "mapped.*PSDrive|PSDrive.*mapped|DisplayRoot") "sourceEditEvidence=$($sourceEditCheck.evidence)"
        $chatDebugReadbackCheck = @($audit.Json.checked | Where-Object { $_.id -eq "chat-debug-events.readback-runtime-smoke" } | Select-Object -First 1)
        Assert-True "completion audit verifies chat DebugEvent readback smoke" ($chatDebugReadbackCheck.evidence -match "operatorDebugEventPresent=True" -and $chatDebugReadbackCheck.evidence -match "MODEL_GUARD" -and $chatDebugReadbackCheck.evidence -match "local_llm_operator_action" -and $chatDebugReadbackCheck.evidence -match "rawPromptHits=0" -and $chatDebugReadbackCheck.evidence -match "rawModelHits=0") "chatDebugReadbackEvidence=$($chatDebugReadbackCheck.evidence)"
        $aliasCheck = @($audit.Json.checked | Where-Object { $_.id -eq "tools.task-aliases" } | Select-Object -First 1)
        Assert-True "completion audit checks producer command alias" ($aliasCheck.evidence -match "producer.command_plan") "aliasEvidence=$($aliasCheck.evidence)"
        $restoreCheck = @($audit.Json.checked | Where-Object { $_.id -eq "archive.restore-pre-review-checksum" } | Select-Object -First 1)
        Assert-True "completion audit verifies archive restore verify log" ($restoreCheck.evidence -match "verify_log") "restoreEvidence=$($restoreCheck.evidence)"
        $nodeSetupCheck = @($audit.Json.checked | Where-Object { $_.id -eq "mcp.node-setup-runner" } | Select-Object -First 1)
        Assert-True "completion audit verifies node setup git-root guard" ($nodeSetupCheck.evidence -match "git-root guard") "nodeSetupEvidence=$($nodeSetupCheck.evidence)"
        $nodeSmokeCheck = @($audit.Json.checked | Where-Object { $_.id -eq "mcp.node-smoke-runner" } | Select-Object -First 1)
        Assert-True "completion audit verifies node smoke git-root guard" ($nodeSmokeCheck.evidence -match "git-root guard") "nodeSmokeEvidence=$($nodeSmokeCheck.evidence)"
        $helperCheck = @($audit.Json.checked | Where-Object { $_.id -eq "producer.helpers-path-safety-guard" } | Select-Object -First 1)
        Assert-True "completion audit verifies producer helpers reject PatchDrop source roots" ($helperCheck.evidence -match "PatchDrop source roots") "helperEvidence=$($helperCheck.evidence)"
        $kitExportCheck = @($audit.Json.checked | Where-Object { $_.id -eq "producer.kit-export" } | Select-Object -First 1)
        Assert-True "completion audit verifies producer kit installer git-root guard" ($kitExportCheck.evidence -match "git-root preflight") "kitExportEvidence=$($kitExportCheck.evidence)"
        Assert-True "completion audit verifies producer kit manifest guard" ($kitExportCheck.evidence -match "manifest") "kitExportEvidence=$($kitExportCheck.evidence)"
        $dispatchCheck = @($audit.Json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
        Assert-True "completion audit verifies dispatch artifacts plus SHA sidecar" ($dispatchCheck.evidence -match "artifactCount=6" -and $dispatchCheck.evidence -match "DispatchShaSidecar=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies dispatch producer kit manifest hash pin" ($dispatchCheck.evidence -match "ProducerKitManifestHash=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies dispatch producer kit manifest hash matches file" ($dispatchCheck.evidence -match "ProducerKitManifestHashActual=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies producer command file guard" ($dispatchCheck.evidence -match "ProducerCommandFileGuard=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies producer dispatch sidecar guard" ($dispatchCheck.evidence -match "ProducerDispatchSidecarGuard=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies producer-visible command files" ($dispatchCheck.evidence -match "ProducerVisibleCommandFiles=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies macmini source-root prologue" ($dispatchCheck.evidence -match "macminiSourceRootPrologue=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies notebook source-root prologue" ($dispatchCheck.evidence -match "notebookSourceRootPrologue=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies producer source-root arguments" ($dispatchCheck.evidence -match "macminiSourceRootArgs=True" -and $dispatchCheck.evidence -match "notebookSourceRootArgs=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies producer git-root preflight" ($dispatchCheck.evidence -match "macminiGitRootPreflight=True" -and $dispatchCheck.evidence -match "notebookGitRootPreflight=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies macmini setup audit log" ($dispatchCheck.evidence -match "macminiSetupAuditLog=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies notebook setup audit log" ($dispatchCheck.evidence -match "notebookSetupAuditLog=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies producer node setup exit guards" ($dispatchCheck.evidence -match "macminiNodeSetupExitGuard=True" -and $dispatchCheck.evidence -match "notebookNodeSetupExitGuard=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies macmini handoff audit log" ($dispatchCheck.evidence -match "macminiHandoffAuditLog=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies notebook handoff audit log" ($dispatchCheck.evidence -match "notebookHandoffAuditLog=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies producer kit bootstrap" ($dispatchCheck.evidence -match "ProducerKitBootstrap=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies producer PatchDrop roots" ($dispatchCheck.evidence -match "macminiPatchdropRoots=True" -and $dispatchCheck.evidence -match "notebookPatchdropRoots=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies Desktop topic pin" ($dispatchCheck.evidence -match "DesktopTopicPinned=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies producer bundle gate" ($dispatchCheck.evidence -match "DesktopProducerBundlesRequired=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies Desktop intake source lease gate" ($dispatchCheck.evidence -match "DesktopIntakeSourceLeaseGate=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies Desktop intake source lease fail-closed" ($dispatchCheck.evidence -match "DesktopIntakeSourceLeaseFailClosed=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies Desktop intake command fail-closed" ($dispatchCheck.evidence -match "DesktopIntakeCommandFailClosed=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies Desktop intake JSON semantic fail-closed" ($dispatchCheck.evidence -match "DesktopIntakeJsonSemantics=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit verifies Desktop intake dispatch SHA preflight" ($dispatchCheck.evidence -match "DesktopIntakeDispatchShaPreflight=True") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit reports Desktop producer-root visibility" ($dispatchCheck.evidence -match "macminiDesktopSourceRootExists=" -and $dispatchCheck.evidence -match "notebookDesktopSourceRootExists=") "dispatchEvidence=$($dispatchCheck.evidence)"
        Assert-True "completion audit reports producer pathspec overlap" ($dispatchCheck.evidence -match "PathspecOverlapCount=" -and $dispatchCheck.evidence -match "macminiPathspecCount=" -and $dispatchCheck.evidence -match "notebookPathspecCount=") "dispatchEvidence=$($dispatchCheck.evidence)"
        $producerVisibilityNeeded = @($audit.Json.supportingEvidenceNeeded) -join "|"
        $producerVisibilityDetails = @($audit.Json.supportingEvidenceNeededDetails) -join "|"
        Assert-True "completion audit compacts producer-root visibility supporting evidence" ($producerVisibilityNeeded -match "producer-root-not-visible" -and $producerVisibilityNeeded -match "producer_patchdrop_roots" -and [string]$audit.Json.supportingEvidenceNeededDetailMode -eq "compact") "supportingEvidenceNeeded=$producerVisibilityNeeded mode=$($audit.Json.supportingEvidenceNeededDetailMode)"
        Assert-True "completion audit keeps producer-root visibility details opt-in" (@($audit.Json.supportingEvidenceNeededDetails).Count -eq 0 -and [int]$audit.Json.supportingEvidenceNeededDetailsOmitted -gt 0 -and [string]$audit.Json.supportingEvidenceNeededDetailHint -match "--include-supporting-next-actions") "supportingEvidenceNeededDetails=$producerVisibilityDetails omitted=$($audit.Json.supportingEvidenceNeededDetailsOmitted) hint=$($audit.Json.supportingEvidenceNeededDetailHint)"
        Assert-True "completion audit has repo-local skill docs" (-not ((@($audit.Json.evidence_needed) -join "|") -match "repo-local task skill docs")) "evidence_needed=$(@($audit.Json.evidence_needed) -join '|')"
        Assert-True "completion audit has control tower prompt pack" (-not ((@($audit.Json.evidence_needed) -join "|") -match "repo-local control tower prompt pack")) "evidence_needed=$(@($audit.Json.evidence_needed) -join '|')"
        Assert-True "completion audit reports remaining external evidence" (@($audit.Json.evidence_needed).Count -ge 1) "evidence_needed missing"
        Assert-True "completion audit reports no raw secret leak" ([int]$audit.Json.rawSecretPatternHits -eq 0) "rawSecretPatternHits=$($audit.Json.rawSecretPatternHits)"
    }
}

function Test-CompletionAuditProofFindersFailSoftOnUnreadablePaths {
    Assert-True "completion audit script exists for unreadable proof path guard" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $testScript = Join-Path ([IO.Path]::GetTempPath()) ("awx-completion-audit-unreadable-proof-" + [guid]::NewGuid().ToString("N") + ".py")
    @'
import importlib.util
import sys
from pathlib import Path

script = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location("awx_completion_audit", script)
module = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(module)

class DeniedPath:
    def exists(self):
        raise PermissionError("denied")
    def __truediv__(self, _other):
        return self
    def is_file(self):
        raise PermissionError("denied")
    def glob(self, _pattern):
        raise PermissionError("denied")

class DeniedReadPath:
    def exists(self):
        return True
    def read_text(self, **_kwargs):
        raise PermissionError("denied")

assert module.find_external_node_smoke_proof(DeniedPath(), "macmini") is None
assert module.find_external_producer_handoff_proof(DeniedPath(), "macmini") is None
text, reason = module.read_required_text(DeniedReadPath(), "source_edit_session")
assert text == ""
assert reason == "source_edit_session=unreadable"
'@ | Set-Content -LiteralPath $testScript -Encoding UTF8

    try {
        $lines = & python $testScript $CompletionAudit 2>&1 | ForEach-Object { $_.ToString() }
        Assert-True "completion audit proof finders fail-soft on unreadable paths" ($LASTEXITCODE -eq 0) "output=$($lines -join "`n")"
    } finally {
        Remove-Item -LiteralPath $testScript -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRejectsDispatchWithoutGitRootPreflight {
    Assert-True "completion audit script exists for git-root preflight check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-missing-git-root-dispatch-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "janitor test missing git root preflight " + [guid]::NewGuid().ToString("N")
    $slug = ($topic.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-missing-git-root-preflight"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop dispatch fixture exits zero for git-root preflight check" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        foreach ($commandPath in @(
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt")
        )) {
            $text = Get-Content -LiteralPath $commandPath -Raw
            $text = $text -replace '(?ms)^.*producer-git-root.*\r?\n', ''
            $text = $text -replace '(?ms)^.*GitRoot.*\r?\n', ''
            Set-Content -LiteralPath $commandPath -Value $text -Encoding UTF8
        }

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $auditExit = $LASTEXITCODE
        $raw = $lines -join "`n"
        $json = $null
        try { $json = $raw | ConvertFrom-Json } catch { }
        Assert-True "completion audit rejects dispatch without git-root preflight" ($auditExit -ne 0 -and $json -and $json.ok -eq $false) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing git-root preflight" ($dispatchCheck.ok -eq $false -and $dispatchCheck.evidence -match "GitRootPreflight=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
        Get-ChildItem -LiteralPath $dispatchDir -Filter "$slug-*" -File -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRejectsDispatchWithCanonicalSourceRootArgs {
    Assert-True "completion audit script exists for source-root arg check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-canonical-source-arg-dispatch-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "janitor test canonical source arg " + [guid]::NewGuid().ToString("N")
    $slug = ($topic.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-canonical-source-arg"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop dispatch fixture exits zero for source-root arg check" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        foreach ($commandPath in @(
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt")
        )) {
            $text = Get-Content -LiteralPath $commandPath -Raw
            $text = $text -replace "--source-root\s+(?:'[^']+'|`"[^`"]+`"|\S+)", ("--source-root '" + $Root + "'")
            Set-Content -LiteralPath $commandPath -Value $text -Encoding UTF8
        }

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch with canonical source-root args" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for canonical source-root args" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names bad source-root args" ($dispatchCheck.evidence -match "SourceRootArgs=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @(
            (Join-Path $dispatchDir "$slug-desktop-dispatch.json"),
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt"),
            (Join-Path $dispatchDir "$slug-desktop-intake.ps1")
        )) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRejectsDispatchWithoutDesktopIntakeJsonSemantics {
    Assert-True "completion audit script exists for Desktop intake semantic check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-no-intake-semantics-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $sourceJsonFile = Get-ChildItem -LiteralPath $dispatchDir -Filter "*-desktop-dispatch.json" -File |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        Assert-True "completion audit has source dispatch packet for Desktop intake semantic fixture" ($null -ne $sourceJsonFile) "dispatchDir=$dispatchDir"
        if ($null -eq $sourceJsonFile) { return }
        $sourceSlug = $sourceJsonFile.Name -replace "-desktop-dispatch\.json$", ""
        $sourceMac = Join-Path $dispatchDir "$sourceSlug-macmini.commands.txt"
        $sourceNotebook = Join-Path $dispatchDir "$sourceSlug-notebook.commands.txt"
        Assert-True "completion audit has source macmini command packet" (Test-Path -LiteralPath $sourceMac) "missing $sourceMac"
        Assert-True "completion audit has source notebook command packet" (Test-Path -LiteralPath $sourceNotebook) "missing $sourceNotebook"
        if (-not ((Test-Path -LiteralPath $sourceMac) -and (Test-Path -LiteralPath $sourceNotebook))) { return }

        $dispatchPacket = Get-Content -LiteralPath $sourceJsonFile.FullName -Raw | ConvertFrom-Json
        $sourceTopic = if ([string]::IsNullOrWhiteSpace([string]$dispatchPacket.topic)) { $sourceSlug } else { [string]$dispatchPacket.topic }
        if (@($dispatchPacket.PSObject.Properties.Name) -contains "topic") {
            $dispatchPacket.topic = $sourceTopic
        } else {
            $dispatchPacket | Add-Member -NotePropertyName "topic" -NotePropertyValue $sourceTopic
        }
        $dispatchPacket.dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
        $dispatchPacket | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
        Copy-Item -LiteralPath $sourceMac -Destination $macCommands -Force
        Copy-Item -LiteralPath $sourceNotebook -Destination $notebookCommands -Force
        @"
# Run on Desktop canonical root after Mac mini and Notebook proof files arrive.
`$DesktopLeaseOwner = if (`$Env:COMPUTERNAME) { `$Env:COMPUTERNAME } else { 'desktop-codex' }
# source-edit-locks gate: before any final Desktop apply, acquire a desktop-consumer lease and release it after verification.
# powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action begin -Role desktop-consumer -Root . -Topic '$sourceTopic' -OwnerId `$DesktopLeaseOwner -TtlMinutes 180
# powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action end -Role desktop-consumer -Root . -Topic '$sourceTopic' -OwnerId `$DesktopLeaseOwner
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 -Action status -Role desktop-consumer -Root . -Topic '$sourceTopic' -OwnerId `$DesktopLeaseOwner
`$LeaseStatusExit = `$LASTEXITCODE
if (`$LeaseStatusExit -ne 0) { Write-Error "[AWX][desktop] source-lease-active-or-invalid exitCode=`$LeaseStatusExit"; exit `$LeaseStatusExit }
@{ nodeRole = 'desktop'; patchdrop_root = '__patch_drop__'; evidence_dir = 'data/agent-handoff/mcp-control-tower'; required_roles = @('macmini','notebook'); topic = '$sourceTopic'; require_producer_bundles = `$true } | ConvertTo-Json -Depth 20 -Compress | python .\scripts\awx_mcp_toolbox.py external_evidence_intake
`$IntakeExit = `$LASTEXITCODE
if (`$IntakeExit -ne 0) { Write-Error "[AWX][desktop] external-evidence-intake-failed exitCode=`$IntakeExit"; exit `$IntakeExit }
@{ nodeRole = 'desktop'; patchdrop_root = '__patch_drop__'; evidence_dir = 'data/agent-handoff/mcp-control-tower'; required_roles = @('macmini','notebook'); topic = '$sourceTopic'; require_producer_bundles = `$true } | ConvertTo-Json -Depth 20 -Compress | python .\scripts\awx_mcp_toolbox.py external_evidence_audit
`$AuditExit = `$LASTEXITCODE
if (`$AuditExit -ne 0) { Write-Error "[AWX][desktop] external-evidence-audit-failed exitCode=`$AuditExit"; exit `$AuditExit }
python .\scripts\awx_mcp_completion_audit.py --root .
`$CompletionAuditExit = `$LASTEXITCODE
if (`$CompletionAuditExit -ne 0) { Write-Error "[AWX][desktop] completion-audit-failed exitCode=`$CompletionAuditExit"; exit `$CompletionAuditExit }
"@ | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without Desktop intake JSON semantics" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing Desktop intake semantics" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing Desktop intake JSON semantics" ($dispatchCheck.evidence -match "DesktopIntakeJsonSemantics=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsAmbiguousDispatchPackets {
    Assert-True "completion audit script exists for ambiguous dispatch check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-stale-dispatch-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $dispatchPacket = @{
            nodeRole = "desktop"
            topic = $slug
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-macmini"
                },
                @{
                    nodeRole = "notebook"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-notebook"
                }
            )
        }
        $dispatchPacket | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
        "# Missing cd prologue on purpose." | Set-Content -LiteralPath $macCommands -Encoding UTF8
        "# Missing Push-Location prologue on purpose." | Set-Content -LiteralPath $notebookCommands -Encoding UTF8
        "# Desktop intake placeholder." | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects stale extra dispatch packet" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for stale dispatch rejection" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit marks dispatch artifacts incomplete when any packet is stale" ($dispatchCheck.ok -eq $false) "dispatchCheck=$($dispatchCheck | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "completion audit names missing source-root prologue" ($dispatchCheck.evidence -match "SourceRootPrologue=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditIgnoresJanitorFixtureDispatchWhenOperationalPacketExists {
    Assert-True "completion audit script exists for ignored test fixture check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-ignored-dispatch-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $dispatchPacket = @{
            nodeRole = "desktop"
            topic = $slug
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-macmini"
                    desktopSourceRootExists = $false
                },
                @{
                    nodeRole = "notebook"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-notebook"
                    desktopSourceRootExists = $false
                }
            )
        }
        $dispatchPacket | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
        "# Missing required producer command content on purpose." | Set-Content -LiteralPath $macCommands -Encoding UTF8
        "# Missing required producer command content on purpose." | Set-Content -LiteralPath $notebookCommands -Encoding UTF8
        "# Desktop intake placeholder." | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $baselineAudit = Invoke-CompletionAuditJson
        Assert-True "completion audit baseline succeeds before janitor fixture check" ($baselineAudit.ExitCode -eq 0) "output=$($baselineAudit.Raw)"
        $baselineDispatchCheck = @($baselineAudit.Json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
        $baselineDispatchCount = Get-DispatchPacketCountFromEvidence $baselineDispatchCheck.evidence
        Assert-True "completion audit baseline reports dispatch packet count" ($null -ne $baselineDispatchCount) "dispatchEvidence=$($baselineDispatchCheck.evidence)"

        $audit = Invoke-CompletionAuditJson
        Assert-True "completion audit ignores janitor fixture with operational packet" ($audit.ExitCode -eq 0) "output=$($audit.Raw)"
        if ($audit.Json) {
            $dispatchCheck = @($audit.Json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            $dispatchCount = Get-DispatchPacketCountFromEvidence $dispatchCheck.evidence
            Assert-True "completion audit excludes janitor fixture from dispatch count" (($dispatchCount -eq $baselineDispatchCount) -and -not ($dispatchCheck.evidence -match $slug)) "dispatchEvidence=$($dispatchCheck.evidence);baselineCount=$baselineDispatchCount"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsDispatchWithoutProducerRootGuard {
    Assert-True "completion audit script exists for dispatch root guard check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-unguarded-dispatch-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $dispatchPacket = [pscustomobject]@{
            nodeRole = "desktop"
            topic = $slug
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
            packets = @(
                [pscustomobject]@{
                    nodeRole = "macmini"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-macmini"
                    desktopSourceRootExists = $false
                },
                [pscustomobject]@{
                    nodeRole = "notebook"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-notebook"
                    desktopSourceRootExists = $false
                }
            )
        }
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($jsonPath, (ConvertTo-Json -InputObject $dispatchPacket -Depth 20), $utf8NoBom)
        @"
# Source-root prologue exists, but fail-closed root guard is missing on purpose.
cd 'C:/AbandonWare/worktrees/awx-macmini'
python3 scripts/awx_mcp_node_smoke.py --node-role macmini
"@ | Set-Content -LiteralPath $macCommands -Encoding UTF8
        @"
# Source-root prologue exists, but fail-closed root guard is missing on purpose.
Push-Location 'C:/AbandonWare/worktrees/awx-notebook'
python scripts\awx_mcp_node_smoke.py --node-role notebook
Pop-Location
"@ | Set-Content -LiteralPath $notebookCommands -Encoding UTF8
        "# Desktop intake placeholder." | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without producer-root guard" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing producer-root guard" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing producer-root guard" ($dispatchCheck.evidence -match "SourceRootGuard=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsDispatchWithoutNodeSetup {
    Assert-True "completion audit script exists for dispatch setup ordering check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-no-setup-dispatch-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $dispatchPacket = [pscustomobject]@{
            nodeRole = "desktop"
            topic = $slug
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
            packets = @(
                [pscustomobject]@{
                    nodeRole = "macmini"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-macmini"
                    desktopSourceRootExists = $false
                },
                [pscustomobject]@{
                    nodeRole = "notebook"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-notebook"
                    desktopSourceRootExists = $false
                }
            )
        }
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($jsonPath, (ConvertTo-Json -InputObject $dispatchPacket -Depth 20), $utf8NoBom)
        @"
set -euo pipefail
ProducerRoot='C:/AbandonWare/worktrees/awx-macmini'
[ -d "`$ProducerRoot" ] || { echo "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot" >&2; exit 1; }
cd 'C:/AbandonWare/worktrees/awx-macmini'
python3 scripts/awx_mcp_node_smoke.py --node-role macmini
"@ | Set-Content -LiteralPath $macCommands -Encoding UTF8
        @"
`$ErrorActionPreference = 'Stop'
`$ProducerRoot = 'C:/AbandonWare/worktrees/awx-notebook'
if (-not (Test-Path -LiteralPath `$ProducerRoot -PathType Container)) { Write-Error "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot"; exit 1 }
Push-Location 'C:/AbandonWare/worktrees/awx-notebook'
python scripts\awx_mcp_node_smoke.py --node-role notebook
Pop-Location
"@ | Set-Content -LiteralPath $notebookCommands -Encoding UTF8
        "# Desktop intake placeholder." | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without node setup" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing node setup" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing node setup" ($dispatchCheck.evidence -match "NodeSetupBeforeSmoke=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsDispatchWithoutSetupAuditLog {
    Assert-True "completion audit script exists for dispatch setup audit log check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-no-audit-log-dispatch-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $dispatchPacket = [pscustomobject]@{
            nodeRole = "desktop"
            topic = $slug
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
            packets = @(
                [pscustomobject]@{
                    nodeRole = "macmini"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-macmini"
                    desktopSourceRootExists = $false
                },
                [pscustomobject]@{
                    nodeRole = "notebook"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-notebook"
                    desktopSourceRootExists = $false
                }
            )
        }
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($jsonPath, (ConvertTo-Json -InputObject $dispatchPacket -Depth 20), $utf8NoBom)
        @"
set -euo pipefail
ProducerRoot='C:/AbandonWare/worktrees/awx-macmini'
[ -d "`$ProducerRoot" ] || { echo "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot" >&2; exit 1; }
cd 'C:/AbandonWare/worktrees/awx-macmini'
python3 scripts/awx_mcp_node_setup.py --node-role macmini --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex/awx-control-tower.mcp.json
python3 scripts/awx_mcp_node_smoke.py --node-role macmini
"@ | Set-Content -LiteralPath $macCommands -Encoding UTF8
        @"
`$ErrorActionPreference = 'Stop'
`$ProducerRoot = 'C:/AbandonWare/worktrees/awx-notebook'
if (-not (Test-Path -LiteralPath `$ProducerRoot -PathType Container)) { Write-Error "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot"; exit 1 }
Push-Location 'C:/AbandonWare/worktrees/awx-notebook'
python scripts\awx_mcp_node_setup.py --node-role notebook --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex\awx-control-tower.mcp.json
python scripts\awx_mcp_node_smoke.py --node-role notebook
Pop-Location
"@ | Set-Content -LiteralPath $notebookCommands -Encoding UTF8
        "# Desktop intake placeholder." | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without setup audit log" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing setup audit log" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing setup audit log" ($dispatchCheck.evidence -match "SetupAuditLog=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsDispatchWithoutHandoffAuditLog {
    Assert-True "completion audit script exists for dispatch handoff audit log check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-no-handoff-audit-log-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $dispatchPacket = [pscustomobject]@{
            nodeRole = "desktop"
            topic = $slug
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
            packets = @(
                [pscustomobject]@{
                    nodeRole = "macmini"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-macmini"
                    desktopSourceRootExists = $false
                    producerPatchdropRoot = "/Volumes/WinSrc/demo-1/src/__patch_drop__"
                    desktopPatchdropRoot = "C:/AbandonWare/demo-1/demo-1/src/__patch_drop__"
                },
                [pscustomobject]@{
                    nodeRole = "notebook"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-notebook"
                    desktopSourceRootExists = $false
                    producerPatchdropRoot = "Z:\PatchDrop"
                    desktopPatchdropRoot = "C:/AbandonWare/demo-1/demo-1/src/__patch_drop__"
                }
            )
        }
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($jsonPath, (ConvertTo-Json -InputObject $dispatchPacket -Depth 20), $utf8NoBom)
        @"
set -euo pipefail
ProducerRoot='C:/AbandonWare/worktrees/awx-macmini'
[ -d "`$ProducerRoot" ] || { echo "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot" >&2; exit 1; }
cd 'C:/AbandonWare/worktrees/awx-macmini'
python3 scripts/awx_mcp_node_setup.py --node-role macmini --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex/awx-control-tower.mcp.json --audit-log .codex/awx-control-tower.audit.jsonl
python3 scripts/awx_mcp_node_smoke.py --node-role macmini
python3 scripts/awx_mcp_producer_handoff.py --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --patchdrop-root '/Volumes/WinSrc/demo-1/src/__patch_drop__' --producer-script '/Volumes/WinSrc/demo-1/src/__patch_drop__/producer_bundle.py' --node-role macmini --topic '$slug' --pathspec scripts/awx_mcp_toolbox.py
"@ | Set-Content -LiteralPath $macCommands -Encoding UTF8
        @"
`$ErrorActionPreference = 'Stop'
`$ProducerRoot = 'C:/AbandonWare/worktrees/awx-notebook'
if (-not (Test-Path -LiteralPath `$ProducerRoot -PathType Container)) { Write-Error "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot"; exit 1 }
Push-Location 'C:/AbandonWare/worktrees/awx-notebook'
python scripts\awx_mcp_node_setup.py --node-role notebook --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex\awx-control-tower.mcp.json --audit-log .codex\awx-control-tower.audit.jsonl
python scripts\awx_mcp_node_smoke.py --node-role notebook
python scripts\awx_mcp_producer_handoff.py --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --patchdrop-root 'Z:\PatchDrop' --producer-script 'Z:\PatchDrop\producer_bundle.py' --node-role notebook --topic '$slug' --pathspec scripts/awx_mcp_toolbox.py
Pop-Location
"@ | Set-Content -LiteralPath $notebookCommands -Encoding UTF8
        "# Desktop intake placeholder." | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without handoff audit log" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing handoff audit log" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing handoff audit log" ($dispatchCheck.evidence -match "HandoffAuditLog=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsDispatchWithoutSmokeJsonValidation {
    Assert-True "completion audit script exists for dispatch smoke validation check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-no-smoke-json-validation-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $dispatchPacket = [pscustomobject]@{
            nodeRole = "desktop"
            topic = $slug
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
            packets = @(
                [pscustomobject]@{
                    nodeRole = "macmini"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-macmini"
                    desktopSourceRootExists = $false
                    producerPatchdropRoot = "/Volumes/WinSrc/demo-1/src/__patch_drop__"
                    desktopPatchdropRoot = "C:/AbandonWare/demo-1/demo-1/src/__patch_drop__"
                },
                [pscustomobject]@{
                    nodeRole = "notebook"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-notebook"
                    desktopSourceRootExists = $false
                    producerPatchdropRoot = "Z:\PatchDrop"
                    desktopPatchdropRoot = "C:/AbandonWare/demo-1/demo-1/src/__patch_drop__"
                }
            )
        }
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($jsonPath, (ConvertTo-Json -InputObject $dispatchPacket -Depth 20), $utf8NoBom)
        @"
set -euo pipefail
ProducerRoot='C:/AbandonWare/worktrees/awx-macmini'
[ -d "`$ProducerRoot" ] || { echo "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot" >&2; exit 1; }
cd 'C:/AbandonWare/worktrees/awx-macmini'
python3 scripts/awx_mcp_node_setup.py --node-role macmini --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex/awx-control-tower.mcp.json --audit-log .codex/awx-control-tower.audit.jsonl
python3 scripts/awx_mcp_node_smoke.py --node-role macmini > /Volumes/WinSrc/demo-1/src/__patch_drop__/external-node-proof/macmini-node-smoke.json
python3 scripts/awx_mcp_producer_handoff.py --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --patchdrop-root '/Volumes/WinSrc/demo-1/src/__patch_drop__' --producer-script '/Volumes/WinSrc/demo-1/src/__patch_drop__/producer_bundle.py' --node-role macmini --topic '$slug' --pathspec scripts/awx_mcp_toolbox.py --audit-log .codex/awx-control-tower.audit.jsonl
"@ | Set-Content -LiteralPath $macCommands -Encoding UTF8
        @"
`$ErrorActionPreference = 'Stop'
`$ProducerRoot = 'C:/AbandonWare/worktrees/awx-notebook'
if (-not (Test-Path -LiteralPath `$ProducerRoot -PathType Container)) { Write-Error "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot"; exit 1 }
Push-Location 'C:/AbandonWare/worktrees/awx-notebook'
python scripts\awx_mcp_node_setup.py --node-role notebook --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex\awx-control-tower.mcp.json --audit-log .codex\awx-control-tower.audit.jsonl
python scripts\awx_mcp_node_smoke.py --node-role notebook 1> Z:\PatchDrop\external-node-proof\notebook-node-smoke.json
`$SmokeExit = `$LASTEXITCODE
if (`$SmokeExit -ne 0) { Write-Error "[AWX][producer] node-smoke-failed"; exit `$SmokeExit }
python scripts\awx_mcp_producer_handoff.py --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --patchdrop-root 'Z:\PatchDrop' --producer-script 'Z:\PatchDrop\producer_bundle.py' --node-role notebook --topic '$slug' --pathspec scripts/awx_mcp_toolbox.py --audit-log .codex\awx-control-tower.audit.jsonl
Pop-Location
"@ | Set-Content -LiteralPath $notebookCommands -Encoding UTF8
        "# Desktop intake placeholder." | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without smoke json validation" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing smoke json validation" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing smoke json validation" ($dispatchCheck.evidence -match "SmokeJsonValidation=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsDispatchWithoutSmokeSemanticValidation {
    Assert-True "completion audit script exists for dispatch smoke semantic check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-no-smoke-semantic-validation-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $dispatchPacket = [pscustomobject]@{
            nodeRole = "desktop"
            topic = $slug
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
            packets = @(
                [pscustomobject]@{
                    nodeRole = "macmini"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-macmini"
                    desktopSourceRootExists = $false
                    producerPatchdropRoot = "/Volumes/WinSrc/demo-1/src/__patch_drop__"
                    desktopPatchdropRoot = "C:/AbandonWare/demo-1/demo-1/src/__patch_drop__"
                },
                [pscustomobject]@{
                    nodeRole = "notebook"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-notebook"
                    desktopSourceRootExists = $false
                    producerPatchdropRoot = "Z:\PatchDrop"
                    desktopPatchdropRoot = "C:/AbandonWare/demo-1/demo-1/src/__patch_drop__"
                }
            )
        }
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($jsonPath, (ConvertTo-Json -InputObject $dispatchPacket -Depth 20), $utf8NoBom)
        @"
set -euo pipefail
ProducerRoot='C:/AbandonWare/worktrees/awx-macmini'
[ -d "`$ProducerRoot" ] || { echo "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot" >&2; exit 1; }
cd 'C:/AbandonWare/worktrees/awx-macmini'
python3 scripts/awx_mcp_node_setup.py --node-role macmini --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex/awx-control-tower.mcp.json --audit-log .codex/awx-control-tower.audit.jsonl
python3 scripts/awx_mcp_node_smoke.py --node-role macmini > /Volumes/WinSrc/demo-1/src/__patch_drop__/external-node-proof/macmini-node-smoke.json
python3 -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8-sig"))' /Volumes/WinSrc/demo-1/src/__patch_drop__/external-node-proof/macmini-node-smoke.json
python3 scripts/awx_mcp_producer_handoff.py --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --patchdrop-root '/Volumes/WinSrc/demo-1/src/__patch_drop__' --producer-script '/Volumes/WinSrc/demo-1/src/__patch_drop__/producer_bundle.py' --node-role macmini --topic '$slug' --pathspec scripts/awx_mcp_toolbox.py --audit-log .codex/awx-control-tower.audit.jsonl
"@ | Set-Content -LiteralPath $macCommands -Encoding UTF8
        @"
`$ErrorActionPreference = 'Stop'
`$ProducerRoot = 'C:/AbandonWare/worktrees/awx-notebook'
if (-not (Test-Path -LiteralPath `$ProducerRoot -PathType Container)) { Write-Error "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot"; exit 1 }
Push-Location 'C:/AbandonWare/worktrees/awx-notebook'
python scripts\awx_mcp_node_setup.py --node-role notebook --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex\awx-control-tower.mcp.json --audit-log .codex\awx-control-tower.audit.jsonl
`$ProofPath = 'Z:\PatchDrop\external-node-proof\notebook-node-smoke.json'
python scripts\awx_mcp_node_smoke.py --node-role notebook 1> `$ProofPath
`$SmokeExit = `$LASTEXITCODE
if (`$SmokeExit -ne 0) { Write-Error "[AWX][producer] node-smoke-failed"; exit `$SmokeExit }
python -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8-sig"))' `$ProofPath
`$JsonExit = `$LASTEXITCODE
if (`$JsonExit -ne 0) { Write-Error "[AWX][producer] node-smoke-invalid-json"; exit `$JsonExit }
python scripts\awx_mcp_producer_handoff.py --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --patchdrop-root 'Z:\PatchDrop' --producer-script 'Z:\PatchDrop\producer_bundle.py' --node-role notebook --topic '$slug' --pathspec scripts/awx_mcp_toolbox.py --audit-log .codex\awx-control-tower.audit.jsonl
Pop-Location
"@ | Set-Content -LiteralPath $notebookCommands -Encoding UTF8
        "# Desktop intake placeholder." | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without smoke semantic validation" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing smoke semantic validation" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing smoke semantic validation" ($dispatchCheck.evidence -match "SmokeSemanticValidation=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsDispatchWithoutPatchDropRoots {
    Assert-True "completion audit script exists for dispatch PatchDrop root check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $dispatchDir = Join-Path $Root "__patch_drop__\dispatch"
    $slug = "janitor-test-no-patchdrop-roots-" + [guid]::NewGuid().ToString("N")
    $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
    $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
    $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
    $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    try {
        $dispatchPacket = [pscustomobject]@{
            nodeRole = "desktop"
            topic = $slug
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)
            packets = @(
                [pscustomobject]@{
                    nodeRole = "macmini"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-macmini"
                    desktopSourceRootExists = $false
                },
                [pscustomobject]@{
                    nodeRole = "notebook"
                    sourceRoot = "C:/AbandonWare/worktrees/awx-notebook"
                    desktopSourceRootExists = $false
                }
            )
        }
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::WriteAllText($jsonPath, (ConvertTo-Json -InputObject $dispatchPacket -Depth 20), $utf8NoBom)
        @"
set -euo pipefail
ProducerRoot='C:/AbandonWare/worktrees/awx-macmini'
[ -d "`$ProducerRoot" ] || { echo "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot" >&2; exit 1; }
cd 'C:/AbandonWare/worktrees/awx-macmini'
python3 scripts/awx_mcp_node_setup.py --node-role macmini --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex/awx-control-tower.mcp.json --audit-log .codex/awx-control-tower.audit.jsonl
python3 scripts/awx_mcp_node_smoke.py --node-role macmini
"@ | Set-Content -LiteralPath $macCommands -Encoding UTF8
        @"
`$ErrorActionPreference = 'Stop'
`$ProducerRoot = 'C:/AbandonWare/worktrees/awx-notebook'
if (-not (Test-Path -LiteralPath `$ProducerRoot -PathType Container)) { Write-Error "[AWX][producer] evidence_needed: producer source root missing: `$ProducerRoot"; exit 1 }
Push-Location 'C:/AbandonWare/worktrees/awx-notebook'
python scripts\awx_mcp_node_setup.py --node-role notebook --source-root . --canonical-root 'C:/AbandonWare/demo-1/demo-1/src' --output .codex\awx-control-tower.mcp.json --audit-log .codex\awx-control-tower.audit.jsonl
python scripts\awx_mcp_node_smoke.py --node-role notebook
Pop-Location
"@ | Set-Content -LiteralPath $notebookCommands -Encoding UTF8
        "# Desktop intake placeholder." | Set-Content -LiteralPath $desktopIntake -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without PatchDrop roots" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing PatchDrop roots" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing PatchDrop roots" ($dispatchCheck.evidence -match "PatchdropRoots=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @($jsonPath, $macCommands, $notebookCommands, $desktopIntake)) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsDispatchWithoutPinnedDesktopTopic {
    Assert-True "completion audit script exists for dispatch topic pin check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-no-topic-dispatch-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "janitor test no desktop topic " + [guid]::NewGuid().ToString("N")
    $slug = ($topic.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-no-desktop-topic"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop dispatch fixture exits zero for topic pin check" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
        $text = Get-Content -LiteralPath $desktopIntake -Raw
        $text = $text -replace "; topic = '[^']+'", ""
        Set-Content -LiteralPath $desktopIntake -Value $text -Encoding UTF8

        $lines = python $CompletionAudit --root $Root --topic $topic 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without pinned Desktop topic" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing Desktop topic" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit scopes Desktop topic pin check to requested dispatch" ($dispatchCheck.evidence -match "dispatchPacketCount=1;" -and $dispatchCheck.evidence -notmatch "\|topic=") "dispatchEvidence=$($dispatchCheck.evidence)"
            Assert-True "completion audit names missing Desktop topic pin" ($dispatchCheck.evidence -match "DesktopTopicPinned=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @(
            (Join-Path $dispatchDir "$slug-desktop-dispatch.json"),
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt"),
            (Join-Path $dispatchDir "$slug-desktop-intake.ps1")
        )) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRejectsDispatchWithoutProducerBundleGate {
    Assert-True "completion audit script exists for dispatch producer bundle gate check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-no-bundle-gate-dispatch-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "janitor test no producer bundle gate " + [guid]::NewGuid().ToString("N")
    $slug = ($topic.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-no-producer-bundle-gate"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop dispatch fixture exits zero for producer bundle gate check" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        $desktopIntake = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
        $text = Get-Content -LiteralPath $desktopIntake -Raw
        $text = $text -replace "; require_producer_bundles = [`$]true", ""
        Set-Content -LiteralPath $desktopIntake -Value $text -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without producer bundle gate" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing producer bundle gate" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing producer bundle gate" ($dispatchCheck.evidence -match "DesktopProducerBundlesRequired=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @(
            (Join-Path $dispatchDir "$slug-desktop-dispatch.json"),
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt"),
            (Join-Path $dispatchDir "$slug-desktop-intake.ps1")
        )) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRejectsDispatchWithoutRequiredProducerPathspecs {
    Assert-True "completion audit script exists for dispatch producer pathspec check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-no-pathspec-dispatch-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "janitor test no producer pathspec " + [guid]::NewGuid().ToString("N")
    $slug = ($topic.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-no-producer-pathspec"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            require_producer_bundles = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop dispatch fixture exits zero for producer pathspec check" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        foreach ($commandPath in @(
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt")
        )) {
            $text = Get-Content -LiteralPath $commandPath -Raw
            $text = $text -replace '\s+--pathspec\s+(?:''[^'']*''|"[^"]*"|\S+)', ''
            Set-Content -LiteralPath $commandPath -Value $text -Encoding UTF8
        }

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch without required producer pathspecs" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for missing producer pathspecs" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names missing producer pathspecs" ($dispatchCheck.evidence -match "ProducerPathspecsRequired=True" -and $dispatchCheck.evidence -match "ProducerPathspecsOk=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @(
            (Join-Path $dispatchDir "$slug-desktop-dispatch.json"),
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt"),
            (Join-Path $dispatchDir "$slug-desktop-intake.ps1")
        )) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRejectsDispatchWithDotCanonicalRoot {
    Assert-True "completion audit script exists for dispatch canonical root check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-dot-canonical-dispatch-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "janitor test dot canonical root " + [guid]::NewGuid().ToString("N")
    $slug = ($topic.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-dot-canonical-root"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop dispatch fixture exits zero for canonical root check" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        foreach ($commandPath in @(
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt")
        )) {
            $text = Get-Content -LiteralPath $commandPath -Raw
            $text = $text -replace "--canonical-root\s+'[^']+'", "--canonical-root '.'"
            Set-Content -LiteralPath $commandPath -Value $text -Encoding UTF8
        }

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch with dot canonical root" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for dot canonical root" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names unsafe canonical root" ($dispatchCheck.evidence -match "SafeCanonicalRoot=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @(
            (Join-Path $dispatchDir "$slug-desktop-dispatch.json"),
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt"),
            (Join-Path $dispatchDir "$slug-desktop-intake.ps1")
        )) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRejectsDispatchWithSharedProducerHelper {
    Assert-True "completion audit script exists for shared producer helper check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-shared-helper-dispatch-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "janitor test shared producer helper " + [guid]::NewGuid().ToString("N")
    $slug = ($topic.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-shared-producer-helper"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            producer_patchdrop_roots = @{
                macmini = "/Volumes/WinSrc/demo-1/src/__patch_drop__"
                notebook = "Z:\PatchDrop"
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop dispatch fixture exits zero for shared producer helper check" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        $mutations = @(
            @{ Path = (Join-Path $dispatchDir "$slug-macmini.commands.txt"); Shared = "/Volumes/WinSrc/demo-1/src/__patch_drop__/producer_bundle.py" },
            @{ Path = (Join-Path $dispatchDir "$slug-notebook.commands.txt"); Shared = "Z:\PatchDrop\producer_bundle.py" }
        )
        foreach ($mutation in $mutations) {
            $text = Get-Content -LiteralPath $mutation.Path -Raw
            $text = $text -replace "--producer-script\s+'[^']*producer_bundle.py'", ("--producer-script '" + $mutation.Shared + "'")
            Set-Content -LiteralPath $mutation.Path -Value $text -Encoding UTF8
        }

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects dispatch with shared producer helper" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for shared producer helper" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names shared producer helper" ($dispatchCheck.evidence -match "ProducerLocalHelper=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @(
            (Join-Path $dispatchDir "$slug-desktop-dispatch.json"),
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt"),
            (Join-Path $dispatchDir "$slug-desktop-intake.ps1")
        )) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRecognizesExternalNodeProof {
    Assert-True "completion audit script exists for external proof" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $proofDir = Join-Path $Root "__patch_drop__\external-node-proof"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $runId = [guid]::NewGuid().ToString("N")
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-proof-dispatch-" + $runId)
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "external proof test $runId"
    $expectedTopicSlug = ($topic.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    $producerKitDir = Join-Path $patchDrop "producer-kit\$expectedTopicSlug-producer-kit"
    $producerKitManifest = Join-Path $producerKitDir "producer-kit.manifest.json"
    $topicSlug = ""
    $macProof = Join-Path $proofDir "macmini-node-smoke-$runId.json"
    $notebookProof = Join-Path $proofDir "notebook-node-smoke-$runId.json"
    New-Item -ItemType Directory -Force -Path $proofDir, $macRoot, $notebookRoot, $dispatchDir, $producerKitDir | Out-Null
    Set-Content -LiteralPath $producerKitManifest -Encoding UTF8 -Value '{"schemaVersion":"awx.test.producer-kit.v1","files":[]}'
    try {
        $dispatch = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-external-proof-dispatch"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "completion audit external proof dispatch exits zero" ($dispatch.ExitCode -eq 0) "output=$($dispatch.Raw)"
        if ($dispatch.Json) {
            $topicSlug = [string]$dispatch.Json.topic
        }
        $macPacket = @($dispatch.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
        $notebookPacket = @($dispatch.Json.packets | Where-Object { $_.nodeRole -eq "notebook" } | Select-Object -First 1)
        foreach ($entry in @(
            @{ Path = $macProof; Role = "macmini"; SourceRootInputHash = [string]$macPacket.sourceRootInputHash },
            @{ Path = $notebookProof; Role = "notebook"; SourceRootInputHash = [string]$notebookPacket.sourceRootInputHash }
        )) {
            $steps = New-ValidNodeSmokeSteps
            @{
                schemaVersion = "awx.mcp.node_smoke.v1"
                ok = $true
                requestId = "external-proof-test"
                sessionId = "session-a"
                nodeRole = $entry.Role
                sourceRootInputHash = $entry.SourceRootInputHash
                rootHash = ("root-" + $entry.Role)
                canonicalRootHash = "desktop-canonical"
                sourceIsolation = @{
                    guard = "PASS"
                    sourceRootKind = "local-worktree"
                    sharedSourceRoot = $false
                    desktopCanonicalSourceRoot = $false
                    directCanonicalSourceEdit = $false
                    gitRootPresent = $true
                    gitRootMatchesSourceRoot = $true
                    gitRootHash = "git-root-hash-$($entry.Role)"
                }
                rawSecretPatternHits = 0
                decision = "node_smoke"
                failReason = ""
                steps = $steps
                evidence_needed = @("Desktop final proof until commands run on canonical root")
            } | ConvertTo-Json -Depth 10 -Compress | Set-Content -LiteralPath $entry.Path -Encoding UTF8
        }
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $topicSlug -SourceRoot ([string]$macPacket.sourceRoot) -SourceRootInputHash ([string]$macPacket.sourceRootInputHash)
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "notebook" -Topic $topicSlug -SourceRoot ([string]$notebookPacket.sourceRoot) -SourceRootInputHash ([string]$notebookPacket.sourceRootInputHash)

        $audit = Invoke-CompletionAuditJson
        Assert-True "completion audit with external proof exits zero" ($audit.ExitCode -eq 0) "output=$($audit.Raw)"
        if ($audit.Json) {
            $checked = @($audit.Json.checked | ForEach-Object { $_.id })
            Assert-True "completion audit checks external macmini proof" ($checked -contains "external.macmini-node-proof") "checked=$($checked -join ',')"
            Assert-True "completion audit checks external notebook proof" ($checked -contains "external.notebook-node-proof") "checked=$($checked -join ',')"
            Assert-True "completion audit checks external macmini handoff" ($checked -contains "external.macmini-producer-handoff") "checked=$($checked -join ',')"
            Assert-True "completion audit checks external notebook handoff" ($checked -contains "external.notebook-producer-handoff") "checked=$($checked -join ',')"
            Assert-True "completion audit checks external macmini bundle" ($checked -contains "external.macmini-producer-bundle") "checked=$($checked -join ',')"
            Assert-True "completion audit checks external notebook bundle" ($checked -contains "external.notebook-producer-bundle") "checked=$($checked -join ',')"
            $externalEvidence = @($audit.Json.evidence_needed | Where-Object { $_ -match "external Mac mini/Notebook host smoke output" })
            Assert-True "completion audit clears external proof evidence_needed" ($externalEvidence.Count -eq 0) "evidence_needed=$($audit.Json.evidence_needed -join '|')"
        }
    } finally {
        Remove-Item -LiteralPath $macProof -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $notebookProof -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $producerKitDir -Recurse -Force -ErrorAction SilentlyContinue
        if ($topicSlug) {
            Remove-ProducerBundleEvidence $patchDrop "macmini" $topicSlug
            Remove-ProducerBundleEvidence $patchDrop "notebook" $topicSlug
            foreach ($path in @(
                (Join-Path $dispatchDir "$topicSlug-desktop-dispatch.json"),
                (Join-Path $dispatchDir "$topicSlug-macmini.commands.txt"),
                (Join-Path $dispatchDir "$topicSlug-notebook.commands.txt"),
                (Join-Path $dispatchDir "$topicSlug-desktop-intake.ps1")
            )) {
                Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            }
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
        if ((Test-Path -LiteralPath $proofDir) -and -not (Get-ChildItem -LiteralPath $proofDir -Force -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $proofDir -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsExternalNodeProofWithoutGitRootEvidence {
    Assert-True "completion audit script exists for stale node proof git-root check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $patchDrop = Join-Path $Root "__patch_drop__"
    $proofDir = Join-Path $patchDrop "external-node-proof"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $runId = [guid]::NewGuid().ToString("N")
    $topic = "stale node smoke git root $runId"
    $topicSlug = ""
    $macRoot = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-stale-git-proof-" + $runId)
    $proofPath = Join-Path $proofDir "macmini-node-smoke-$runId.json"
    New-Item -ItemType Directory -Force -Path $proofDir, $dispatchDir, $macRoot | Out-Null
    try {
        $dispatch = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-stale-node-git-root-dispatch"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            target_roles = @("macmini")
            producer_roots = @{
                macmini = $macRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
            }
        }
        Assert-True "completion audit stale node proof dispatch exits zero" ($dispatch.ExitCode -eq 0) "output=$($dispatch.Raw)"
        if ($dispatch.Json) {
            $topicSlug = [string]$dispatch.Json.topic
            $macPacket = @($dispatch.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Write-NodeSmokeEvidence $proofDir "macmini"
            Move-Item -LiteralPath (Join-Path $proofDir "macmini-node-smoke.json") -Destination $proofPath -Force
            $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
            $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue ([string]$macPacket.sourceRootInputHash) -Force
            $proof.sourceIsolation.PSObject.Properties.Remove("gitRootPresent")
            $proof.sourceIsolation.PSObject.Properties.Remove("gitRootMatchesSourceRoot")
            $proof.sourceIsolation.PSObject.Properties.Remove("gitRootHash")
            $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8
            Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $topicSlug -SourceRoot ([string]$macPacket.sourceRoot) -SourceRootInputHash ([string]$macPacket.sourceRootInputHash)
        }

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects external node proof without git-root evidence" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for stale node git-root proof" ($null -ne $json) "output=$raw"
        if ($json) {
            $nodeCheck = @($json.checked | Where-Object { $_.id -eq "external.macmini-node-proof" } | Select-Object -First 1)
            Assert-True "completion audit names missing node git-root evidence" ($nodeCheck.ok -eq $false -and $nodeCheck.failReason -match "git-root") "nodeCheck=$($nodeCheck | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $proofPath -Force -ErrorAction SilentlyContinue
        if ($topicSlug) {
            Remove-ProducerBundleEvidence $patchDrop "macmini" $topicSlug
            foreach ($path in @(
                (Join-Path $dispatchDir "$topicSlug-desktop-dispatch.json"),
                (Join-Path $dispatchDir "$topicSlug-macmini.commands.txt"),
                (Join-Path $dispatchDir "$topicSlug-notebook.commands.txt"),
                (Join-Path $dispatchDir "$topicSlug-desktop-intake.ps1")
            )) {
                Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            }
        }
        Remove-Item -LiteralPath $macRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRejectsDispatchWithConditionalProducerKitBootstrap {
    Assert-True "completion audit script exists for conditional producer kit bootstrap check" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-conditional-kit-bootstrap-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "janitor test conditional producer kit bootstrap " + [guid]::NewGuid().ToString("N")
    $slug = ($topic.ToLowerInvariant() -replace '[^a-z0-9]+','-').Trim('-')
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-conditional-producer-kit-bootstrap"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop dispatch fixture exits zero for conditional producer kit bootstrap check" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
        $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
        $macText = Get-Content -LiteralPath $macCommands -Raw
        $macText = "# stale conditional bootstrap marker: MissingRequiredFile`n$macText"
        Set-Content -LiteralPath $macCommands -Value $macText -Encoding UTF8
        $notebookText = Get-Content -LiteralPath $notebookCommands -Raw
        $notebookText = "# stale conditional bootstrap marker: BootstrapRequiredFiles `$MissingRequiredFile`n$notebookText"
        Set-Content -LiteralPath $notebookCommands -Value $notebookText -Encoding UTF8

        $lines = python $CompletionAudit --root $Root 2>&1 | ForEach-Object { $_.ToString() }
        $raw = $lines -join "`n"
        $json = try { $raw | ConvertFrom-Json } catch { $null }
        Assert-True "completion audit rejects conditional producer kit bootstrap" ($LASTEXITCODE -ne 0) "expected nonzero exit; output=$raw"
        Assert-True "completion audit emits JSON for conditional producer kit bootstrap" ($null -ne $json) "output=$raw"
        if ($json) {
            $dispatchCheck = @($json.checked | Where-Object { $_.id -eq "desktop.dispatch-artifacts" } | Select-Object -First 1)
            Assert-True "completion audit names conditional producer kit bootstrap" ($dispatchCheck.evidence -match "ProducerKitBootstrap=False") "dispatchEvidence=$($dispatchCheck.evidence)"
        }
    } finally {
        foreach ($path in @(
            (Join-Path $dispatchDir "$slug-desktop-dispatch.json"),
            (Join-Path $dispatchDir "$slug-macmini.commands.txt"),
            (Join-Path $dispatchDir "$slug-notebook.commands.txt"),
            (Join-Path $dispatchDir "$slug-desktop-intake.ps1")
        )) {
            Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-CompletionAuditRejectsMissingExternalProducerHandoffProof {
    Assert-True "completion audit script exists for missing handoff proof" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $proofDir = Join-Path $Root "__patch_drop__\external-node-proof"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $runId = [guid]::NewGuid().ToString("N")
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-missing-handoff-dispatch-" + $runId)
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "missing handoff test $runId"
    $topicSlug = ""
    $macProof = Join-Path $proofDir "macmini-node-smoke-$runId.json"
    $notebookProof = Join-Path $proofDir "notebook-node-smoke-$runId.json"
    New-Item -ItemType Directory -Force -Path $proofDir, $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $dispatch = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-missing-handoff-dispatch"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "completion audit missing handoff dispatch exits zero" ($dispatch.ExitCode -eq 0) "output=$($dispatch.Raw)"
        if ($dispatch.Json) {
            $topicSlug = [string]$dispatch.Json.topic
        }
        $macPacket = @($dispatch.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
        $notebookPacket = @($dispatch.Json.packets | Where-Object { $_.nodeRole -eq "notebook" } | Select-Object -First 1)
        foreach ($entry in @(
            @{ Path = $macProof; Role = "macmini"; SourceRootInputHash = [string]$macPacket.sourceRootInputHash },
            @{ Path = $notebookProof; Role = "notebook"; SourceRootInputHash = [string]$notebookPacket.sourceRootInputHash }
        )) {
            $steps = New-ValidNodeSmokeSteps
            @{
                schemaVersion = "awx.mcp.node_smoke.v1"
                ok = $true
                requestId = "missing-handoff-test"
                sessionId = "session-a"
                nodeRole = $entry.Role
                sourceRootInputHash = $entry.SourceRootInputHash
                rootHash = ("root-" + $entry.Role)
                canonicalRootHash = "desktop-canonical"
                sourceIsolation = @{
                    guard = "PASS"
                    sourceRootKind = "local-worktree"
                    sharedSourceRoot = $false
                    desktopCanonicalSourceRoot = $false
                    directCanonicalSourceEdit = $false
                }
                rawSecretPatternHits = 0
                decision = "node_smoke"
                failReason = ""
                steps = $steps
                evidence_needed = @("Desktop final proof until commands run on canonical root")
            } | ConvertTo-Json -Depth 10 -Compress | Set-Content -LiteralPath $entry.Path -Encoding UTF8
        }
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $topicSlug -SourceRoot ([string]$macPacket.sourceRoot) -SourceRootInputHash ([string]$macPacket.sourceRootInputHash)
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "notebook" -Topic $topicSlug -SourceRoot ([string]$notebookPacket.sourceRoot) -SourceRootInputHash ([string]$notebookPacket.sourceRootInputHash)
        Remove-Item -LiteralPath (Join-Path $proofDir "macmini-producer-handoff.json") -Force -ErrorAction SilentlyContinue

        $audit = Invoke-CompletionAuditJson
        Assert-True "completion audit missing external handoff exits nonzero" ($audit.ExitCode -ne 0) "output=$($audit.Raw)"
        if ($audit.Json) {
            $handoffCheck = @($audit.Json.checked | Where-Object { $_.id -eq "external.macmini-producer-handoff" } | Select-Object -First 1)
            Assert-True "completion audit rejects missing macmini handoff" ($handoffCheck.ok -eq $false -and $handoffCheck.failReason -match "missing") "checked=$($audit.Json.checked | ConvertTo-Json -Depth 10 -Compress)"
            $externalEvidence = @($audit.Json.evidence_needed | Where-Object { $_ -match "producer handoff" -or $_ -match "external Mac mini/Notebook host smoke output" })
            Assert-True "completion audit keeps evidence_needed without producer handoff" ($externalEvidence.Count -gt 0) "evidence_needed=$($audit.Json.evidence_needed -join '|')"
        }
    } finally {
        Remove-Item -LiteralPath $macProof -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $notebookProof -Force -ErrorAction SilentlyContinue
        if ($topicSlug) {
            Remove-ProducerBundleEvidence $patchDrop "macmini" $topicSlug
            Remove-ProducerBundleEvidence $patchDrop "notebook" $topicSlug
            foreach ($path in @(
                (Join-Path $dispatchDir "$topicSlug-desktop-dispatch.json"),
                (Join-Path $dispatchDir "$topicSlug-macmini.commands.txt"),
                (Join-Path $dispatchDir "$topicSlug-notebook.commands.txt"),
                (Join-Path $dispatchDir "$topicSlug-desktop-intake.ps1")
            )) {
                Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            }
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
        if ((Test-Path -LiteralPath $proofDir) -and -not (Get-ChildItem -LiteralPath $proofDir -Force -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $proofDir -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsExternalProducerHandoffPatchHashMismatch {
    Assert-True "completion audit script exists for handoff patch hash mismatch" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $proofDir = Join-Path $Root "__patch_drop__\external-node-proof"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $runId = [guid]::NewGuid().ToString("N")
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-handoff-hash-dispatch-" + $runId)
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "handoff hash test $runId"
    $topicSlug = ""
    $macProof = Join-Path $proofDir "macmini-node-smoke-$runId.json"
    $notebookProof = Join-Path $proofDir "notebook-node-smoke-$runId.json"
    New-Item -ItemType Directory -Force -Path $proofDir, $macRoot, $notebookRoot, $dispatchDir | Out-Null
    try {
        $dispatch = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-handoff-hash-dispatch"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = $topic
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "completion audit handoff hash dispatch exits zero" ($dispatch.ExitCode -eq 0) "output=$($dispatch.Raw)"
        if ($dispatch.Json) {
            $topicSlug = [string]$dispatch.Json.topic
        }
        $macPacket = @($dispatch.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
        $notebookPacket = @($dispatch.Json.packets | Where-Object { $_.nodeRole -eq "notebook" } | Select-Object -First 1)
        foreach ($entry in @(
            @{ Path = $macProof; Role = "macmini"; SourceRootInputHash = [string]$macPacket.sourceRootInputHash },
            @{ Path = $notebookProof; Role = "notebook"; SourceRootInputHash = [string]$notebookPacket.sourceRootInputHash }
        )) {
            $steps = New-ValidNodeSmokeSteps
            @{
                schemaVersion = "awx.mcp.node_smoke.v1"
                ok = $true
                requestId = "handoff-hash-test"
                sessionId = "session-a"
                nodeRole = $entry.Role
                sourceRootInputHash = $entry.SourceRootInputHash
                rootHash = ("root-" + $entry.Role)
                canonicalRootHash = "desktop-canonical"
                sourceIsolation = @{
                    guard = "PASS"
                    sourceRootKind = "local-worktree"
                    sharedSourceRoot = $false
                    desktopCanonicalSourceRoot = $false
                    directCanonicalSourceEdit = $false
                }
                rawSecretPatternHits = 0
                decision = "node_smoke"
                failReason = ""
                steps = $steps
                evidence_needed = @("Desktop final proof until commands run on canonical root")
            } | ConvertTo-Json -Depth 10 -Compress | Set-Content -LiteralPath $entry.Path -Encoding UTF8
        }
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $topicSlug -SourceRoot ([string]$macPacket.sourceRoot) -SourceRootInputHash ([string]$macPacket.sourceRootInputHash)
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "notebook" -Topic $topicSlug -SourceRoot ([string]$notebookPacket.sourceRoot) -SourceRootInputHash ([string]$notebookPacket.sourceRootInputHash)

        $handoffPath = Join-Path $proofDir "macmini-producer-handoff.json"
        $handoff = Get-Content -LiteralPath $handoffPath -Raw | ConvertFrom-Json
        $handoff.bundle.patchHash = "0000000000000000000000000000000000000000000000000000000000000000"
        $handoff | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $handoffPath -Encoding UTF8

        $audit = Invoke-CompletionAuditJson
        Assert-True "completion audit handoff patch hash mismatch exits nonzero" ($audit.ExitCode -ne 0) "output=$($audit.Raw)"
        if ($audit.Json) {
            $handoffCheck = @($audit.Json.checked | Where-Object { $_.id -eq "external.macmini-producer-handoff" } | Select-Object -First 1)
            Assert-True "completion audit rejects handoff patch hash mismatch" ($handoffCheck.ok -eq $false -and $handoffCheck.failReason -match "patch-hash-mismatch") "checked=$($audit.Json.checked | ConvertTo-Json -Depth 10 -Compress)"
            $bundleCheck = @($audit.Json.checked | Where-Object { $_.id -eq "external.macmini-producer-bundle" } | Select-Object -First 1)
            Assert-True "completion audit keeps matching bundle sidecars independently valid" ($bundleCheck.ok -eq $true) "checked=$($audit.Json.checked | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $macProof -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $notebookProof -Force -ErrorAction SilentlyContinue
        if ($topicSlug) {
            Remove-ProducerBundleEvidence $patchDrop "macmini" $topicSlug
            Remove-ProducerBundleEvidence $patchDrop "notebook" $topicSlug
            foreach ($path in @(
                (Join-Path $dispatchDir "$topicSlug-desktop-dispatch.json"),
                (Join-Path $dispatchDir "$topicSlug-macmini.commands.txt"),
                (Join-Path $dispatchDir "$topicSlug-notebook.commands.txt"),
                (Join-Path $dispatchDir "$topicSlug-desktop-intake.ps1")
            )) {
                Remove-Item -LiteralPath $path -Force -ErrorAction SilentlyContinue
            }
        }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
        if ((Test-Path -LiteralPath $proofDir) -and -not (Get-ChildItem -LiteralPath $proofDir -Force -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $proofDir -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-CompletionAuditRejectsSmokeOnlyExternalProof {
    Assert-True "completion audit script exists for smoke-only proof" (Test-Path -LiteralPath $CompletionAudit) "missing $CompletionAudit"
    if (-not (Test-Path -LiteralPath $CompletionAudit)) { return }

    $proofDir = Join-Path $Root "__patch_drop__\external-node-proof"
    $patchDrop = Join-Path $Root "__patch_drop__"
    $runId = [guid]::NewGuid().ToString("N")
    $macProof = Join-Path $proofDir "macmini-node-smoke-$runId.json"
    $notebookProof = Join-Path $proofDir "notebook-node-smoke-$runId.json"
    New-Item -ItemType Directory -Force -Path $proofDir | Out-Null
    try {
        Remove-ProducerBundleEvidence $patchDrop "macmini"
        Remove-ProducerBundleEvidence $patchDrop "notebook"
        foreach ($entry in @(
            @{ Path = $macProof; Role = "macmini" },
            @{ Path = $notebookProof; Role = "notebook" }
        )) {
            $steps = New-ValidNodeSmokeSteps
            @{
                schemaVersion = "awx.mcp.node_smoke.v1"
                ok = $true
                requestId = "external-smoke-only-test"
                sessionId = "session-a"
                nodeRole = $entry.Role
                rootHash = ("root-" + $entry.Role)
                canonicalRootHash = "desktop-canonical"
                sourceIsolation = @{
                    guard = "PASS"
                    sourceRootKind = "local-worktree"
                    sharedSourceRoot = $false
                    desktopCanonicalSourceRoot = $false
                    directCanonicalSourceEdit = $false
                }
                rawSecretPatternHits = 0
                decision = "node_smoke"
                failReason = ""
                steps = $steps
                evidence_needed = @("Desktop final proof until commands run on canonical root")
            } | ConvertTo-Json -Depth 10 -Compress | Set-Content -LiteralPath $entry.Path -Encoding UTF8
        }

        $audit = Invoke-CompletionAuditJson
        Assert-True "completion audit smoke-only proof exits nonzero" ($audit.ExitCode -ne 0) "output=$($audit.Raw)"
        if ($audit.Json) {
            $externalEvidence = @($audit.Json.evidence_needed | Where-Object { $_ -match "producer bundle" })
            Assert-True "completion audit keeps external evidence_needed without producer bundles" ($externalEvidence.Count -gt 0) "evidence_needed=$($audit.Json.evidence_needed -join '|')"
        }
    } finally {
        Remove-Item -LiteralPath $macProof -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $notebookProof -Force -ErrorAction SilentlyContinue
        Remove-ProducerBundleEvidence $patchDrop "macmini"
        Remove-ProducerBundleEvidence $patchDrop "notebook"
        if ((Test-Path -LiteralPath $proofDir) -and -not (Get-ChildItem -LiteralPath $proofDir -Force -ErrorAction SilentlyContinue)) {
            Remove-Item -LiteralPath $proofDir -Force -ErrorAction SilentlyContinue
        }
    }
}

function Test-TaskSkillsExistAndPointAtLauncher {
    $expected = @{
        "archive-search" = "archive.search"
        "archive-restore" = "archive.restore"
        "verify-boot" = "verify_boot"
        "build-error-miner" = "build_error_miner"
        "run-pipeline" = "run_pipeline"
    }
    foreach ($entry in $expected.GetEnumerator()) {
        $skillPath = Join-Path $Root (".agents\skills\" + $entry.Key + "\SKILL.md")
        $text = Get-SafeRawContent $skillPath
        if ([string]::IsNullOrWhiteSpace($text)) {
            Assert-True "skill doc soft evidence_needed $($entry.Value)" $true "unreadable $skillPath"
        } else {
            Assert-True "skill name $($entry.Value)" ($text.Contains("name: $($entry.Value)")) "frontmatter name missing"
            Assert-True "skill uses launcher $($entry.Value)" ($text.Contains("scripts\awx_mcp_toolbox.ps1")) "launcher reference missing"
        }
    }
}

function Test-ArchiveSearchTwoPassAndEvidenceNeeded {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-toolbox-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    $oldArchiveIndex = $env:ARCHIVE_INDEX
    $oldNasArchiveRoot = $env:NAS_ARCHIVE_ROOT
    try {
        $index = Join-Path $tmp "index.jsonl"
        @(
            (@{ path = "snapshots/search-provider.log"; title = "Search provider timeout"; tags = @("search", "timeout"); summary = "provider timeout fail soft" } | ConvertTo-Json -Compress),
            (@{ path = "patches/naver-bridge.patch"; title = "Naver credential bridge"; tags = @("naver", "credential"); summary = "NAVER_KEYS bridge patch" } | ConvertTo-Json -Compress)
        ) | Set-Content -LiteralPath $index -Encoding UTF8

        $hit = Invoke-ToolboxJson "archive_search" @{
            requestId = "test-archive-hit"
            sessionId = "session-a"
            nodeRole = "macmini"
            index_path = $index
            q = "naver bridge"
            filters = @{ tags = "naver" }
            top_k = 2
        }
        Assert-True "archive_search exits zero on hit" ($hit.ExitCode -eq 0) "output=$($hit.Raw)"
        if ($hit.Json) {
            Assert-True "archive_search uses at least two passes" ([int]$hit.Json.passCount -ge 2) "passCount=$($hit.Json.passCount)"
            Assert-True "archive_search returns one hit" ([int]$hit.Json.outputCount -eq 1) "outputCount=$($hit.Json.outputCount)"
            Assert-True "archive_search result is redacted path evidence" (($hit.Json.results[0].pathName -eq "naver-bridge.patch") -and ($null -eq $hit.Json.results[0].path)) "result=$($hit.Json.results[0] | ConvertTo-Json -Compress)"
        }

        $env:ARCHIVE_INDEX = $index
        $envHit = Invoke-ToolboxJson "archive_search" @{
            requestId = "test-archive-env-hit"
            sessionId = "session-a"
            nodeRole = "macmini"
            q = "naver bridge"
            filters = @{ tags = "naver" }
            top_k = 2
        }
        Assert-True "archive_search uses ARCHIVE_INDEX fallback" ($envHit.ExitCode -eq 0) "output=$($envHit.Raw)"
        if ($envHit.Json) {
            Assert-True "archive_search reports ARCHIVE_INDEX source" ($envHit.Json.indexPathSource -eq "env.ARCHIVE_INDEX") "source=$($envHit.Json.indexPathSource)"
            Assert-True "archive_search env fallback returns hit" ([int]$envHit.Json.outputCount -eq 1) "outputCount=$($envHit.Json.outputCount)"
        }

        Remove-Item Env:ARCHIVE_INDEX -ErrorAction SilentlyContinue
        $nasRoot = Join-Path $tmp "BackupsXS"
        New-Item -ItemType Directory -Force -Path $nasRoot | Out-Null
        Copy-Item -LiteralPath $index -Destination (Join-Path $nasRoot "index.jsonl") -Force
        $env:NAS_ARCHIVE_ROOT = $nasRoot
        $nasHit = Invoke-ToolboxJson "archive_search" @{
            requestId = "test-archive-nas-hit"
            sessionId = "session-a"
            nodeRole = "macmini"
            q = "naver bridge"
            filters = @{ tags = "naver" }
            top_k = 2
        }
        Assert-True "archive_search uses NAS_ARCHIVE_ROOT fallback" ($nasHit.ExitCode -eq 0) "output=$($nasHit.Raw)"
        if ($nasHit.Json) {
            Assert-True "archive_search reports NAS_ARCHIVE_ROOT source" ($nasHit.Json.indexPathSource -eq "env.NAS_ARCHIVE_ROOT") "source=$($nasHit.Json.indexPathSource)"
            Assert-True "archive_search NAS fallback returns hit" ([int]$nasHit.Json.outputCount -eq 1) "outputCount=$($nasHit.Json.outputCount)"
        }

        $miss = Invoke-ToolboxJson "archive_search" @{
            requestId = "test-archive-miss"
            sessionId = "session-a"
            nodeRole = "macmini"
            index_path = $index
            q = "graphdb missing whale"
            filters = @{}
            top_k = 2
        }
        Assert-True "archive_search exits zero on miss" ($miss.ExitCode -eq 0) "output=$($miss.Raw)"
        if ($miss.Json) {
            Assert-True "archive_search miss records evidence_needed" ([string]$miss.Json.evidence_needed -ne "") "evidence_needed missing"
            Assert-True "archive_search miss expands queries" (@($miss.Json.expandedQueries).Count -ge 2) "expanded=$($miss.Json.expandedQueries -join ',')"
        }
    } finally {
        if ($null -eq $oldArchiveIndex) { Remove-Item Env:ARCHIVE_INDEX -ErrorAction SilentlyContinue } else { $env:ARCHIVE_INDEX = $oldArchiveIndex }
        if ($null -eq $oldNasArchiveRoot) { Remove-Item Env:NAS_ARCHIVE_ROOT -ErrorAction SilentlyContinue } else { $env:NAS_ARCHIVE_ROOT = $oldNasArchiveRoot }
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ArchiveRestoreWritesAuditAndChecksums {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-restore-" + [guid]::NewGuid().ToString("N"))
    $archive = Join-Path $tmp "BackupsXS"
    $target = Join-Path $tmp "restore-target"
    $audit = Join-Path $tmp "audit.ndjson"
    $verifyLog = Join-Path $tmp "restore.verify.ndjson"
    New-Item -ItemType Directory -Force -Path (Join-Path $archive "snapshots") | Out-Null
    try {
        Set-Content -LiteralPath (Join-Path $archive "snapshots\safe.txt") -Encoding UTF8 -Value "safe restore content"
        $restore = Invoke-ToolboxJson "archive_restore" @{
            requestId = "test-restore"
            sessionId = "session-a"
            nodeRole = "macmini"
            mode = "restore"
            archive_root = $archive
            glob = "snapshots/*.txt"
            target_dir = $target
            audit_log = $audit
            verify_log = $verifyLog
        }
        Assert-True "archive_restore exits zero" ($restore.ExitCode -eq 0) "output=$($restore.Raw)"
        Assert-True "archive_restore copies file" (Test-Path -LiteralPath (Join-Path $target "snapshots\safe.txt")) "restored file missing"
        Assert-True "archive_restore writes audit log" (Test-Path -LiteralPath $audit) "audit log missing"
        Assert-True "archive_restore writes verify log" (Test-Path -LiteralPath $verifyLog) "verify log missing"
        if ($restore.Json) {
            Assert-True "archive_restore reports pre-review" ($restore.Json.preReview.performed -eq $true -and [int]$restore.Json.preReview.candidateCount -eq 1) "preReview=$($restore.Json.preReview | ConvertTo-Json -Compress)"
            Assert-True "archive_restore reports checksum" ([string]$restore.Json.restored[0].sha256 -ne "") "checksum missing"
            Assert-True "archive_restore verifies checksum" ($restore.Json.restored[0].checksumVerified -eq $true) "checksumVerified=$($restore.Json.restored[0].checksumVerified)"
            Assert-True "archive_restore reports outputCount" ([int]$restore.Json.outputCount -eq 1) "outputCount=$($restore.Json.outputCount)"
            Assert-True "archive_restore reports verify log path" ([string]$restore.Json.verifyLog -ne "") "verifyLog=$($restore.Json.verifyLog)"
        }
        $auditText = Get-Content -LiteralPath $audit -Raw
        Assert-True "audit log contains toolName only" ($auditText.Contains('"toolName":"archive_restore"')) "audit=$auditText"
        Assert-True "audit log avoids restored content" (-not $auditText.Contains("safe restore content")) "audit leaked file content"
        if (Test-Path -LiteralPath $verifyLog) {
            $verifyText = Get-Content -LiteralPath $verifyLog -Raw
            Assert-True "verify log records checksum evidence" ($verifyText.Contains('"checksumVerified":true') -and $verifyText.Contains('"preReview"')) "verifyLog=$verifyText"
            Assert-True "verify log contains input hash" ($verifyText.Contains('"inputHash"')) "verifyLog=$verifyText"
            Assert-True "verify log avoids restored content" (-not $verifyText.Contains("safe restore content")) "verify log leaked file content"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ArchiveRestoreBlocksProducerCanonicalTarget {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-restore-block-" + [guid]::NewGuid().ToString("N"))
    $archive = Join-Path $tmp "BackupsXS"
    $canonical = Join-Path $tmp "canonical-root"
    $audit = Join-Path $tmp "audit.ndjson"
    New-Item -ItemType Directory -Force -Path (Join-Path $archive "snapshots"), $canonical | Out-Null
    try {
        Set-Content -LiteralPath (Join-Path $archive "snapshots\blocked.txt") -Encoding UTF8 -Value "blocked restore content"
        $restore = Invoke-ToolboxJson "archive_restore" @{
            requestId = "test-restore-block"
            sessionId = "session-a"
            nodeRole = "macmini"
            mode = "restore"
            archive_root = $archive
            glob = "snapshots/*.txt"
            target_dir = $canonical
            canonical_root = $canonical
            audit_log = $audit
        }
        Assert-True "archive_restore canonical block exits zero" ($restore.ExitCode -eq 0) "output=$($restore.Raw)"
        if ($restore.Json) {
            Assert-True "archive_restore blocks producer canonical restore" ($restore.Json.ok -eq $false -and $restore.Json.decision -eq "restore_target_blocked") "json=$($restore.Raw)"
            Assert-True "archive_restore block uses smb-conflict-risk" ($restore.Json.failReason -eq "smb-conflict-risk") "failReason=$($restore.Json.failReason)"
            Assert-True "archive_restore block still reports pre-review" ($restore.Json.preReview.performed -eq $true -and [int]$restore.Json.preReview.candidateCount -eq 1) "preReview=$($restore.Json.preReview | ConvertTo-Json -Compress)"
        }
        Assert-True "archive_restore block does not copy file" (-not (Test-Path -LiteralPath (Join-Path $canonical "snapshots\blocked.txt"))) "blocked file was copied"
        Assert-True "archive_restore block writes audit" (Test-Path -LiteralPath $audit) "audit log missing"
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ArchiveRestoreRequiresAuditLogForRestoreMode {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-restore-no-audit-" + [guid]::NewGuid().ToString("N"))
    $archive = Join-Path $tmp "BackupsXS"
    $target = Join-Path $tmp "restore-target"
    New-Item -ItemType Directory -Force -Path (Join-Path $archive "snapshots") | Out-Null
    try {
        Set-Content -LiteralPath (Join-Path $archive "snapshots\safe.txt") -Encoding UTF8 -Value "safe restore content"
        $restore = Invoke-ToolboxJson "archive_restore" @{
            requestId = "test-restore-no-audit"
            sessionId = "session-a"
            nodeRole = "macmini"
            mode = "restore"
            archive_root = $archive
            glob = "snapshots/*.txt"
            target_dir = $target
        }
        Assert-True "archive_restore no-audit exits zero" ($restore.ExitCode -eq 0) "output=$($restore.Raw)"
        if ($restore.Json) {
            Assert-True "archive_restore no-audit blocks restore" ($restore.Json.ok -eq $false -and $restore.Json.decision -eq "restore_audit_log_missing") "json=$($restore.Raw)"
            Assert-True "archive_restore no-audit classifies missing audit" ($restore.Json.failReason -eq "missing-audit-log") "failReason=$($restore.Json.failReason)"
            Assert-True "archive_restore no-audit still reports pre-review" ($restore.Json.preReview.performed -eq $true -and [int]$restore.Json.preReview.candidateCount -eq 1) "preReview=$($restore.Json.preReview | ConvertTo-Json -Compress)"
        }
        Assert-True "archive_restore no-audit does not copy file" (-not (Test-Path -LiteralPath (Join-Path $target "snapshots\safe.txt"))) "restored file exists without audit log"
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Write-NodeSmokeEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$Dir,
        [Parameter(Mandatory = $true)][string]$Role
    )
    $steps = New-ValidNodeSmokeSteps
    $smoke = @{
        schemaVersion = "awx.mcp.node_smoke.v1"
        ok = $true
        requestId = "smoke-node"
        sessionId = "external-evidence-test"
        nodeRole = $Role
        rootHash = "hash-$Role"
        canonicalRootHash = "hash-canonical"
        sourceIsolation = @{
            guard = "PASS"
            sourceRootKind = "local-worktree"
            sharedSourceRoot = $false
            desktopCanonicalSourceRoot = $false
            directCanonicalSourceEdit = $false
            gitRootPresent = $true
            gitRootMatchesSourceRoot = $true
            gitRootHash = "git-root-hash-$Role"
        }
        steps = $steps
        evidence_needed = @("Desktop final proof until commands run on canonical root")
        rawSecretPatternHits = 0
        decision = "node_smoke"
        failReason = ""
    }
    $path = Join-Path $Dir "$Role-node-smoke.json"
    $smoke | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $path -Encoding UTF8
}

function Get-TestStableHash {
    param([Parameter(Mandatory = $true)][string]$Value)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
}

function Write-TestDispatchShaSidecar {
    param(
        [Parameter(Mandatory = $true)][string]$DispatchDir,
        [Parameter(Mandatory = $true)][string]$Topic,
        [Parameter(Mandatory = $true)][string[]]$FileNames
    )
    $lines = foreach ($name in $FileNames) {
        $path = Join-Path $DispatchDir $name
        $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $name"
    }
    Set-Content -LiteralPath (Join-Path $DispatchDir "$Topic-dispatch.sha256.txt") -Encoding UTF8 -Value ($lines -join "`n")
}

function Write-TestDispatchPacketEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$PatchDrop,
        [string]$Topic = "mcp-stdio-bridge-verification",
        [string[]]$Roles = @("macmini", "notebook"),
        [string]$EvidenceDir = ""
    )
    $dispatchDir = Join-Path $PatchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $dispatchDir | Out-Null
    $covered = @("$Topic-desktop-dispatch.json")
    $dispatchArtifacts = @((Join-Path $dispatchDir "$Topic-desktop-dispatch.json"))
    $packets = @()
    foreach ($role in $Roles) {
        $commandName = "$Topic-$role.commands.txt"
        $commandText = if ($role -eq "macmini") { "python3 scripts/awx_mcp_node_smoke.py --node-role macmini" } else { "python scripts\awx_mcp_node_smoke.py --node-role notebook" }
        Set-Content -LiteralPath (Join-Path $dispatchDir $commandName) -Encoding UTF8 -Value $commandText
        $covered += $commandName
        $dispatchArtifacts += (Join-Path $dispatchDir $commandName)
        $sourceRoot = "C:\agent\$role\worktree"
        $sourceRootHash = Get-TestStableHash $sourceRoot
        $packets += @{
            nodeRole = $role
            sourceRoot = $sourceRoot
            sourceRootInputHash = $sourceRootHash
            producerPatchdropRoot = $PatchDrop
            desktopPatchdropRoot = $PatchDrop
            desktopSourceRootExists = $false
        }
        if (-not [string]::IsNullOrWhiteSpace($EvidenceDir)) {
            $proofPath = Join-Path $EvidenceDir "$role-node-smoke.json"
            if (Test-Path -LiteralPath $proofPath -PathType Leaf) {
                $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
                $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue $sourceRootHash -Force
                $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8
            }
        }
    }
    $desktopIntakeName = "$Topic-desktop-intake.ps1"
    Set-Content -LiteralPath (Join-Path $dispatchDir $desktopIntakeName) -Encoding UTF8 -Value "python .\scripts\awx_mcp_toolbox.py external_evidence_intake"
    $covered += $desktopIntakeName
    $dispatchArtifacts += (Join-Path $dispatchDir $desktopIntakeName)
    @{
        topic = $Topic
        desktopFinalProof = "evidence_needed"
        dispatchArtifacts = $dispatchArtifacts
        dispatchArtifactIndex = @{
            dispatchSha256Sidecar = (Join-Path $dispatchDir "$Topic-dispatch.sha256.txt")
            sha256CoveredArtifacts = $covered
        }
        packets = $packets
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "$Topic-desktop-dispatch.json") -Encoding UTF8
    Write-TestDispatchShaSidecar $dispatchDir $Topic $covered
}

function Write-ProducerBundleEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$PatchDrop,
        [Parameter(Mandatory = $true)][string]$Role,
        [string]$Topic = "mcp-stdio-bridge-verification",
        [string]$SourceRoot = "",
        [string]$SourceRootInputHash = "",
        [string]$ProducerCommandHash = ""
    )
    $bundle = "$Topic-$Role-v3"
    $nodeDir = Join-Path $PatchDrop $Role
    New-Item -ItemType Directory -Force -Path $nodeDir | Out-Null

    if ([string]::IsNullOrWhiteSpace($SourceRoot)) {
        $SourceRoot = "C:\agent\$Role\worktree"
    }
    if ([string]::IsNullOrWhiteSpace($SourceRootInputHash)) {
        $SourceRootInputHash = Get-TestStableHash $SourceRoot
    }
    if ([string]::IsNullOrWhiteSpace($ProducerCommandHash)) {
        $commandPath = Join-Path $PatchDrop "dispatch\$Topic-$Role.commands.txt"
        if (Test-Path -LiteralPath $commandPath -PathType Leaf) {
            $ProducerCommandHash = (Get-FileHash -LiteralPath $commandPath -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $gitRootHash = Get-TestStableHash $SourceRoot

    $patchName = "$bundle.patch"
    $reportName = "$bundle.report.md"
    $verifyName = "$bundle.verify.log"
    $manifestName = "$bundle.manifest.json"
    $shaName = "$bundle.sha256.txt"

    $patchText = @(
        "diff --git a/scripts/fixture.txt b/scripts/fixture.txt",
        "index 1111111..2222222 100644",
        "--- a/scripts/fixture.txt",
        "+++ b/scripts/fixture.txt",
        "@@ -1 +1 @@",
        "-before",
        "+after"
    ) -join "`n"
    Set-Content -LiteralPath (Join-Path $nodeDir $patchName) -Encoding UTF8 -Value $patchText
    $patchHash = (Get-FileHash -LiteralPath (Join-Path $nodeDir $patchName) -Algorithm SHA256).Hash.ToLowerInvariant()
    $reportText = @(
        "## Producer Bundle Report",
        "- node: $Role",
        "- topic: $Topic",
        "- Desktop final proof: evidence_needed",
        "- sourceIsolation.guard: PASS"
    ) -join "`n"
    Set-Content -LiteralPath (Join-Path $nodeDir $reportName) -Encoding UTF8 -Value $reportText
    $verifyText = @(
        "## producer-bundle",
        "node=$Role",
        "topic=$Topic",
        "sourceIsolation.guard=PASS",
        "sourceIsolation.sourceRootKind=local-worktree",
        "sourceIsolation.directCanonicalSourceEdit=False",
        "sourceIsolation.gitRootPresent=True",
        "sourceIsolation.gitRootMatchesSourceRoot=True",
        "## secret scan",
        "secretPatternHits=0",
        "Desktop final proof: evidence_needed"
    ) -join "`n"
    Set-Content -LiteralPath (Join-Path $nodeDir $verifyName) -Encoding UTF8 -Value $verifyText
    @{
        schemaVersion = "patchdrop-producer-v3"
        node = $Role
        topic = $Topic
        slug = $Topic
        bundle = $bundle
        status = "PENDING_DESKTOP_CONSUMPTION"
        activePatch = $patchName
        sourceRoot = $SourceRoot
        sourceRootInputHash = $SourceRootInputHash
        sourceRootHash = $gitRootHash
        patchDropRoot = $PatchDrop
        sourceIsolation = @{
            guard = "PASS"
            sourceRootKind = "local-worktree"
            sharedSourceRoot = $false
            desktopCanonicalSourceRoot = $false
            directCanonicalSourceEdit = $false
            gitRootPresent = $true
            gitRootMatchesSourceRoot = $true
            gitRootHash = $gitRootHash
        }
        desktopFinalProof = "evidence_needed"
        verification = @{
            diffHeaderCount = 1
            filemodeLineCount = 0
            allowedNewFileCount = 0
            filemodeViolationCount = 0
            forbiddenPathCount = 0
            secretPatternHits = 0
            rawSecretPatternHits = 0
        }
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $nodeDir $manifestName) -Encoding UTF8

    $pendingText = @(
        "## Producer Bundle Pending: $Topic",
        "- status: PENDING_DESKTOP_CONSUMPTION",
        "- node: $Role",
        "- producer_bundle: ``__patch_drop__\$Role\$patchName``"
    ) -join "`n"
    $pendingPath = Join-Path $PatchDrop "$Topic.$Role-pending.md"
    Set-Content -LiteralPath $pendingPath -Encoding UTF8 -Value $pendingText

    $shaEntries = @(
        @{ Name = $patchName; Path = (Join-Path $nodeDir $patchName) },
        @{ Name = $reportName; Path = (Join-Path $nodeDir $reportName) },
        @{ Name = $verifyName; Path = (Join-Path $nodeDir $verifyName) },
        @{ Name = $manifestName; Path = (Join-Path $nodeDir $manifestName) },
        @{ Name = "../$Topic.$Role-pending.md"; Path = $pendingPath }
    )
    $shaLines = foreach ($entry in $shaEntries) {
        $hash = (Get-FileHash -LiteralPath $entry.Path -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($entry.Name)"
    }
    Set-Content -LiteralPath (Join-Path $nodeDir $shaName) -Encoding UTF8 -Value ($shaLines -join "`n")

    $proofDir = Join-Path $PatchDrop "external-node-proof"
    New-Item -ItemType Directory -Force -Path $proofDir | Out-Null
    @{
        schemaVersion = "awx.mcp.producer_handoff.v1"
        ok = $true
        requestId = "producer-handoff"
        sessionId = "external-evidence-test"
        nodeRole = $Role
        toolName = "producer_handoff"
        inputHash = "input-hash-$Role"
        outputCount = 1
        topic = $Topic
        producerCommandHash = $ProducerCommandHash
        sourceRootInputHash = $SourceRootInputHash
        sourceRootHash = $SourceRootInputHash
        canonicalRootHash = "canonical-root"
        patchDropHash = "patchdrop-root"
        smoke = @{
            ok = $true
            exitCode = 0
            decision = "node_smoke"
            evidence_needed = @("Desktop final proof until commands run on canonical root")
        }
        bundle = @{
            ok = $true
            exitCode = 0
            sidecarsComplete = $true
            sourceIsolation = @{
                guard = "PASS"
                sourceRootKind = "local-worktree"
                sharedSourceRoot = $false
                desktopCanonicalSourceRoot = $false
                directCanonicalSourceEdit = $false
                gitRootPresent = $true
                gitRootMatchesSourceRoot = $true
                gitRootHash = $gitRootHash
            }
            desktopFinalProof = "evidence_needed"
            promotionReady = $true
            diffHeaderCount = 1
            patchHash = $patchHash
            outputHash = "output-hash-$Role"
            outputLineCount = 1
            failReason = ""
        }
        desktopFinalProof = "evidence_needed"
        rawSecretPatternHits = 0
        elapsedMs = 1
        decision = "producer_handoff"
        failReason = ""
    } | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath (Join-Path $proofDir "$Role-producer-handoff.json") -Encoding UTF8
}

function Write-ReportOnlyManifestEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$PatchDrop,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Topic
    )
    $nodeDir = Join-Path $PatchDrop $Role
    New-Item -ItemType Directory -Force -Path $nodeDir | Out-Null
    @{
        schemaVersion = "awx.notebook.report_bundle.v1"
        nodeRole = $Role
        topic = $Topic
        bundleType = "report-only"
        sourcePatchIncluded = $false
        sourceRoot = @{
            sourceRootKind = "shared-root"
            sharedSourceRoot = $true
            directCanonicalSourceEdit = $false
        }
        desktopFinalProof = "PENDING"
        status = "SUPPORTING_EVIDENCE"
        artifacts = @("$Topic-$Role-v3.report.md", "$Topic-$Role-v3.verify.log")
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $nodeDir "$Topic-$Role-v3.manifest.json") -Encoding UTF8
}

function Write-LegacyProducerManifestEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$PatchDrop,
        [Parameter(Mandatory = $true)][string]$Role,
        [Parameter(Mandatory = $true)][string]$Slug
    )
    $nodeDir = Join-Path $PatchDrop $Role
    New-Item -ItemType Directory -Force -Path $nodeDir | Out-Null
    @{
        schemaVersion = "patchdrop-v3"
        slug = $Slug
        node = $Role
        status = "ACTIVE"
        activePatch = "$Slug-$Role-v3.patch"
        desktopFinalProof = "evidence_needed"
    } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $nodeDir "$Slug-$Role-v3.manifest.json") -Encoding UTF8
}

function Update-ProducerBundleShaSidecar {
    param(
        [Parameter(Mandatory = $true)][string]$PatchDrop,
        [Parameter(Mandatory = $true)][string]$Role,
        [string]$Topic = "mcp-stdio-bridge-verification"
    )
    $bundle = "$Topic-$Role-v3"
    $nodeDir = Join-Path $PatchDrop $Role
    $pendingPath = Join-Path $PatchDrop "$Topic.$Role-pending.md"
    $patchPath = Join-Path $nodeDir "$bundle.patch"
    $manifestPath = Join-Path $nodeDir "$bundle.manifest.json"
    $patchText = Get-Content -LiteralPath $patchPath -Raw
    $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
    $modeLines = @([regex]::Matches($patchText, '(?m)^(old mode|new mode|deleted file mode|new file mode)(?:\s+.*)?$') | ForEach-Object { $_.Value })
    $allowedNewFiles = 0
    $filemodeViolations = 0
    if ($patchText -match '(?m)^\+\+\+ /dev/null(?:\t.*)?$') {
        $filemodeViolations = [Math]::Max(1, $modeLines.Count)
    } elseif ($patchText -match '(?m)^--- /dev/null(?:\t.*)?$') {
        $newFileHeader = [regex]::Match($patchText, '(?m)^@@ -0,0 \+1(?:,([0-9]+))? @@(?: .*)?$')
        $declared = if ($newFileHeader.Success -and $newFileHeader.Groups[1].Success) { [int]$newFileHeader.Groups[1].Value } elseif ($newFileHeader.Success) { 1 } else { 0 }
        $payloadCount = @($patchText -split "`r?`n" | Where-Object { $_.StartsWith('+') -and -not $_.StartsWith('+++ ') }).Count
        if ($modeLines.Count -eq 1 -and $modeLines[0] -ceq 'new file mode 100644' -and $declared -gt 0 -and $payloadCount -eq $declared) {
            $allowedNewFiles = 1
        } else {
            $filemodeViolations = [Math]::Max(1, $modeLines.Count)
        }
    } elseif ($modeLines.Count -gt 0) {
        $filemodeViolations = $modeLines.Count
    }
    $verification = [ordered]@{
        diffHeaderCount = [regex]::Matches($patchText.TrimStart([char]0xfeff), '(?m)^diff --git ').Count
        filemodeLineCount = $modeLines.Count
        allowedNewFileCount = $allowedNewFiles
        filemodeViolationCount = $filemodeViolations
        forbiddenPathCount = 0
        secretPatternHits = 0
        rawSecretPatternHits = 0
    }
    $manifest | Add-Member -NotePropertyName verification -NotePropertyValue $verification -Force
    $manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $entries = @(
        @{ Name = "$bundle.patch"; Path = $patchPath },
        @{ Name = "$bundle.report.md"; Path = (Join-Path $nodeDir "$bundle.report.md") },
        @{ Name = "$bundle.verify.log"; Path = (Join-Path $nodeDir "$bundle.verify.log") },
        @{ Name = "$bundle.manifest.json"; Path = $manifestPath },
        @{ Name = "../$Topic.$Role-pending.md"; Path = $pendingPath }
    )
    $shaLines = foreach ($entry in $entries) {
        $hash = (Get-FileHash -LiteralPath $entry.Path -Algorithm SHA256).Hash.ToLowerInvariant()
        "$hash  $($entry.Name)"
    }
    Set-Content -LiteralPath (Join-Path $nodeDir "$bundle.sha256.txt") -Encoding UTF8 -Value ($shaLines -join "`n")
}

function Remove-ProducerBundleEvidence {
    param(
        [Parameter(Mandatory = $true)][string]$PatchDrop,
        [Parameter(Mandatory = $true)][string]$Role,
        [string]$Topic = "mcp-stdio-bridge-verification"
    )
    $bundle = "$Topic-$Role-v3"
    $nodeDir = Join-Path $PatchDrop $Role
    foreach ($suffix in @(".patch", ".report.md", ".verify.log", ".sha256.txt", ".manifest.json")) {
        Remove-Item -LiteralPath (Join-Path $nodeDir "$bundle$suffix") -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath (Join-Path $PatchDrop "$Topic.$Role-pending.md") -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath (Join-Path $PatchDrop "external-node-proof\$Role-producer-handoff.json") -Force -ErrorAction SilentlyContinue
}

function Test-ExternalEvidenceAuditValidatesNodeSmokeOutputs {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-evidence-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $audit = Join-Path $tmp "audit.ndjson"
    $index = Join-Path $tmp "index.jsonl"
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $tmp "macmini"
        Write-NodeSmokeEvidence $tmp "notebook"
        Write-TestDispatchPacketEvidence $patchDrop -EvidenceDir $tmp
        Write-ProducerBundleEvidence $patchDrop "macmini"
        Write-ProducerBundleEvidence $patchDrop "notebook"
        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-evidence"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $tmp
            patchdrop_root = $patchDrop
            required_roles = @("macmini", "notebook")
            archive_index = $index
            audit_log = $audit
        }
        Assert-True "external_evidence_audit exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit completes with both roles" ($external.Json.ok -eq $true -and $external.Json.externalEvidenceComplete -eq $true) "json=$($external.Raw)"
            Assert-True "external_evidence_audit validates two roles" ([int]$external.Json.outputCount -eq 2) "outputCount=$($external.Json.outputCount)"
            Assert-True "external_evidence_audit validates two producer handoffs" ([int](@($external.Json.producerHandoffs | Where-Object { $_.valid -eq $true }).Count) -eq 2) "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit validates two producer bundles" ([int](@($external.Json.producerBundles | Where-Object { $_.valid -eq $true }).Count) -eq 2) "producerBundles=$($external.Json.producerBundles | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit sees archive index" ($external.Json.archiveIndex.exists -eq $true) "archiveIndex=$($external.Json.archiveIndex | ConvertTo-Json -Compress)"
            Assert-True "external_evidence_audit reports no raw secret leak" ([int]$external.Json.rawSecretPatternHits -eq 0) "rawSecretPatternHits=$($external.Json.rawSecretPatternHits)"
            $applyGate = @($external.Json.nextActions | Where-Object { $_.action -eq "run-desktop-janitor-apply-gate" } | Select-Object -First 1)
            $applyGateText = $applyGate | ConvertTo-Json -Depth 20 -Compress
            Assert-True "external_evidence_audit complete exposes Desktop janitor apply gate" ($applyGate.Count -eq 1) "nextActions=$($external.Json.nextActions | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "external_evidence_audit apply gate runs inventory first" ($applyGate.inventoryCommand -match "janitor_inventory\.ps1") "applyGate=$applyGateText"
            Assert-True "external_evidence_audit apply gate promotes one bundle at a time" ($applyGate.oneAcceptedBundleAtATime -eq $true -and $applyGate.activeTopLevelGuard -eq "active-top-level-exists") "applyGate=$applyGateText"
            Assert-True "external_evidence_audit apply gate includes both producer promote commands" ((@($applyGate.promoteCommands) -join "`n") -match "macmini" -and (@($applyGate.promoteCommands) -join "`n") -match "notebook") "applyGate=$applyGateText"
            Assert-True "external_evidence_audit apply gate uses janitor apply helper" ($applyGate.applyCommand -match "janitor_apply_one\.ps1" -and $applyGate.applyCommand -match "mcp-stdio-bridge-verification-v3\.patch") "applyGate=$applyGateText"
        }
        Assert-True "external_evidence_audit writes audit" (Test-Path -LiteralPath $audit) "audit missing"
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditReportsUnrelatedPatchDropEvidence {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-unrelated-patchdrop-evidence-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $evidenceDir = Join-Path $tmp "evidence"
    $currentTopic = "mcp-current-topic"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-NodeSmokeEvidence $evidenceDir "notebook"
        Write-TestDispatchPacketEvidence $patchDrop -Topic $currentTopic -EvidenceDir $evidenceDir
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $currentTopic
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "notebook" -Topic $currentTopic
        Write-ReportOnlyManifestEvidence -PatchDrop $patchDrop -Role "notebook" -Topic "tailscale-smb-load-shed"
        Write-LegacyProducerManifestEvidence -PatchDrop $patchDrop -Role "notebook" -Slug "constitutional-scorecard-predecision"

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-unrelated-patchdrop-evidence"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini", "notebook")
            topic = $currentTopic
        }
        Assert-True "external_evidence_audit unrelated PatchDrop exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit completes current topic despite unrelated evidence" ($external.Json.ok -eq $true -and $external.Json.externalEvidenceComplete -eq $true) "json=$($external.Raw)"
            Assert-True "external_evidence_audit reports unrelated evidence summary" ([int]$external.Json.unrelatedPatchDropEvidence.total -eq 2 -and [int]$external.Json.unrelatedPatchDropEvidence.reportOnlyCount -eq 1) "unrelated=$($external.Json.unrelatedPatchDropEvidence | ConvertTo-Json -Depth 20 -Compress)"
            $reportOnlyItem = @($external.Json.unrelatedPatchDropEvidence.items | Where-Object { $_.topic -eq "tailscale-smb-load-shed" } | Select-Object -First 1)
            Assert-True "external_evidence_audit marks unrelated evidence pending/supporting" ([int]$external.Json.unrelatedPatchDropEvidence.pendingCount -eq 2 -and $reportOnlyItem.Count -eq 1 -and $reportOnlyItem[0].pendingDesktopProof -eq $true -and $reportOnlyItem[0].kind -eq "report-only") "unrelated=$($external.Json.unrelatedPatchDropEvidence | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "external_evidence_audit derives old manifest topic from slug" ((@($external.Json.unrelatedPatchDropEvidence.items | ForEach-Object { $_.topic }) -contains "constitutional-scorecard-predecision") -and -not ((@($external.Json.unrelatedPatchDropEvidence.items | ForEach-Object { $_.topic }) -contains "tool"))) "unrelated=$($external.Json.unrelatedPatchDropEvidence | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "external_evidence_audit keeps unrelated evidence out of current producer bundle rows" (-not ((@($external.Json.producerBundles | ForEach-Object { $_.bundle }) -join " ") -match "tailscale-smb-load-shed")) "producerBundles=$($external.Json.producerBundles | ConvertTo-Json -Depth 20 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditCompletesWithoutArchiveIndex {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-evidence-no-archive-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $missingIndex = Join-Path $tmp "BackupsXS\index.jsonl"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-NodeSmokeEvidence $evidenceDir "notebook"
        Write-TestDispatchPacketEvidence $patchDrop -EvidenceDir $evidenceDir
        Write-ProducerBundleEvidence $patchDrop "macmini"
        Write-ProducerBundleEvidence $patchDrop "notebook"

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-evidence-no-archive"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini", "notebook")
            archive_index = $missingIndex
        }

            Assert-True "external_evidence_audit no archive exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit no archive completes with producer proofs" ($external.Json.ok -eq $true -and $external.Json.externalEvidenceComplete -eq $true) "json=$($external.Raw)"
            Assert-True "external_evidence_audit no archive validates producer handoffs" ([int](@($external.Json.producerHandoffs | Where-Object { $_.valid -eq $true }).Count) -eq 2) "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit no archive marks archive optional" ($external.Json.archiveIndex.exists -eq $false -and $external.Json.archiveIndex.required -eq $false) "archiveIndex=$($external.Json.archiveIndex | ConvertTo-Json -Compress)"
            Assert-True "external_evidence_audit no archive keeps required evidence clear" (-not ((@($external.Json.evidence_needed) -join ' ') -match "archive index")) "evidence_needed=$(@($external.Json.evidence_needed) -join ';')"
            Assert-True "external_evidence_audit no archive reports optional evidence" ((@($external.Json.optional_evidence_needed) -join ' ') -match "archive index") "optional=$(@($external.Json.optional_evidence_needed) -join ';')"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditKeepsDesktopFinalProofPending {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-proof-pending-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-NodeSmokeEvidence $evidenceDir "notebook"
        Write-ProducerBundleEvidence $patchDrop "macmini"
        Write-ProducerBundleEvidence $patchDrop "notebook"

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-proof-pending"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini", "notebook")
        }

        Assert-True "external_evidence_audit proof-pending exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit keeps Desktop final proof pending top-level" ($external.Json.desktopFinalProof -eq "evidence_needed") "json=$($external.Raw)"
            $allRowsPending = @($external.Json.nodeEvidence | Where-Object { $_.desktopFinalProof -ne "evidence_needed" }).Count -eq 0
            Assert-True "external_evidence_audit keeps Desktop final proof pending per node" $allRowsPending "nodeEvidence=$($external.Json.nodeEvidence | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsSmokeOnlyProof {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-smoke-only-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    New-Item -ItemType Directory -Force -Path $evidenceDir, $patchDrop | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-NodeSmokeEvidence $evidenceDir "notebook"

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-evidence-smoke-only"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini", "notebook")
        }

        Assert-True "external_evidence_audit smoke-only exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects smoke-only producer proof" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            Assert-True "external_evidence_audit names missing producer bundle" ((@($external.Json.evidence_needed) -join ' ') -match "producer bundle") "evidence_needed=$(@($external.Json.evidence_needed) -join ';')"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsMissingProducerHandoff {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-missing-handoff-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini"
        Remove-Item -LiteralPath (Join-Path $patchDrop "external-node-proof\macmini-producer-handoff.json") -Force -ErrorAction SilentlyContinue

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-missing-handoff"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
        }

        Assert-True "external_evidence_audit missing handoff exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects missing handoff" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $handoffRow = @($external.Json.producerHandoffs | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit reports missing handoff row" ($handoffRow.valid -eq $false -and $handoffRow.failReason -match "producer-handoff-missing") "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit names missing producer handoff" ((@($external.Json.evidence_needed) -join ' ') -match "producer handoff") "evidence_needed=$(@($external.Json.evidence_needed) -join ';')"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsProducerHandoffPatchHashMismatch {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-handoff-patch-hash-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini"
        $handoffPath = Join-Path $patchDrop "external-node-proof\macmini-producer-handoff.json"
        $handoff = Get-Content -LiteralPath $handoffPath -Raw | ConvertFrom-Json
        $handoff.bundle.patchHash = "0000000000000000000000000000000000000000000000000000000000000000"
        $handoff | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $handoffPath -Encoding UTF8

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-handoff-patch-hash-mismatch"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
        }

        Assert-True "external_evidence_audit patch-hash mismatch exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects handoff patch hash mismatch" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $handoffRow = @($external.Json.producerHandoffs | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit reports handoff patch hash mismatch" ($handoffRow.valid -eq $false -and $handoffRow.failReason -match "patch-hash-mismatch") "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit keeps sidecar bundle independently valid" ($bundleRow.valid -eq $true) "producerBundles=$($external.Json.producerBundles | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsProducerBundleWithoutGitRootProof {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-bundle-git-root-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $topic = "mcp-bundle-git-root-proof"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini" $topic
        $bundle = "$topic-macmini-v3"
        $manifestPath = Join-Path $patchDrop "macmini\$bundle.manifest.json"
        $manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
        $manifest.sourceIsolation.PSObject.Properties.Remove("gitRootPresent")
        $manifest.sourceIsolation.PSObject.Properties.Remove("gitRootMatchesSourceRoot")
        $manifest.sourceIsolation.PSObject.Properties.Remove("gitRootHash")
        $manifest | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $manifestPath -Encoding UTF8
        Update-ProducerBundleShaSidecar -PatchDrop $patchDrop -Role "macmini" -Topic $topic

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-bundle-git-root"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
        }

        Assert-True "external_evidence_audit bundle without git-root proof exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects bundle without git-root proof" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit names bundle git-root proof gap" ($bundleRow.valid -eq $false -and $bundleRow.failReason -match "producer-git-root") "producerBundles=$($external.Json.producerBundles | ConvertTo-Json -Depth 10 -Compress)"
            $handoffRow = @($external.Json.producerHandoffs | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit keeps handoff independently valid" ($handoffRow.valid -eq $true) "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsProducerHandoffWithoutGitRootProof {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-handoff-git-root-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $topic = "mcp-handoff-git-root-proof"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini" $topic
        $handoffPath = Join-Path $patchDrop "external-node-proof\macmini-producer-handoff.json"
        $handoff = Get-Content -LiteralPath $handoffPath -Raw | ConvertFrom-Json
        $handoff.bundle.sourceIsolation.PSObject.Properties.Remove("gitRootPresent")
        $handoff.bundle.sourceIsolation.PSObject.Properties.Remove("gitRootMatchesSourceRoot")
        $handoff.bundle.sourceIsolation.PSObject.Properties.Remove("gitRootHash")
        $handoff | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $handoffPath -Encoding UTF8

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-handoff-git-root"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
        }

        Assert-True "external_evidence_audit handoff without git-root proof exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects handoff without git-root proof" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit keeps bundle independently valid" ($bundleRow.valid -eq $true) "producerBundles=$($external.Json.producerBundles | ConvertTo-Json -Depth 10 -Compress)"
            $handoffRow = @($external.Json.producerHandoffs | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit names handoff git-root proof gap" ($handoffRow.valid -eq $false -and $handoffRow.failReason -match "producer-git-root") "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsPendingNoticeMissingShaEntry {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-pending-sha-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $topic = "mcp-pending-sha-proof"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini" $topic
        $bundle = "$topic-macmini-v3"
        $shaPath = Join-Path $patchDrop "macmini\$bundle.sha256.txt"
        $shaLines = @(Get-Content -LiteralPath $shaPath | Where-Object { $_ -notmatch "\.\./$topic\.macmini-pending\.md" })
        Set-Content -LiteralPath $shaPath -Encoding UTF8 -Value ($shaLines -join "`n")

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-pending-sha"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
        }

        Assert-True "external_evidence_audit missing pending sha exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects pending notice without sha entry" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit reports missing pending sha entry" ($bundleRow.valid -eq $false -and $bundleRow.failReason -match "producer-sha-entry-missing" -and $bundleRow.failReason -match "pending") "producerBundles=$($external.Json.producerBundles | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsProducerFilemodePatch {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-filemode-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $index = Join-Path $tmp "index.jsonl"
    $topic = "mcp-filemode-proof"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini" $topic

        $bundle = "$topic-macmini-v3"
        $nodeDir = Join-Path $patchDrop "macmini"
        $patchName = "$bundle.patch"
        $patchText = @(
            "diff --git a/scripts/fixture.txt b/scripts/fixture.txt",
            "old mode 100644",
            "new mode 100755",
            "index 1111111..2222222",
            "--- a/scripts/fixture.txt",
            "+++ b/scripts/fixture.txt",
            "@@ -1 +1 @@",
            "-before",
            "+after"
        ) -join "`n"
        Set-Content -LiteralPath (Join-Path $nodeDir $patchName) -Encoding UTF8 -Value $patchText

        $shaLines = foreach ($fileName in @($patchName, "$bundle.report.md", "$bundle.verify.log", "$bundle.manifest.json")) {
            $hash = (Get-FileHash -LiteralPath (Join-Path $nodeDir $fileName) -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $fileName"
        }
        Set-Content -LiteralPath (Join-Path $nodeDir "$bundle.sha256.txt") -Encoding UTF8 -Value ($shaLines -join "`n")

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-filemode"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
            archive_index = $index
        }

        Assert-True "external_evidence_audit filemode exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects filemode producer patch" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit marks filemode bundle invalid" ($bundleRow.valid -eq $false -and $bundleRow.failReason -match "filemode-blocked") "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit reports filemode count" ([int]$bundleRow.filemodeLineCount -eq 2) "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit filemode keeps Desktop proof pending" ($external.Json.desktopFinalProof -eq "evidence_needed") "json=$($external.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditAcceptsCanonicalNewFilePatch {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-new-file-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $index = Join-Path $tmp "index.jsonl"
    $topic = "mcp-new-file-proof"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini" $topic

        $bundle = "$topic-macmini-v3"
        $nodeDir = Join-Path $patchDrop "macmini"
        $patchName = "$bundle.patch"
        $patchPath = Join-Path $nodeDir $patchName
        $patchText = @(
            "diff --git a/scripts/new-fixture.txt b/scripts/new-fixture.txt",
            "new file mode 100644",
            "index 0000000..2222222",
            "--- /dev/null",
            "+++ b/scripts/new-fixture.txt",
            "@@ -0,0 +1 @@",
            "+created"
        ) -join "`n"
        Set-Content -LiteralPath $patchPath -Encoding UTF8 -Value $patchText
        Update-ProducerBundleShaSidecar -PatchDrop $patchDrop -Role "macmini" -Topic $topic

        $handoffPath = Join-Path $patchDrop "external-node-proof\macmini-producer-handoff.json"
        $handoff = Get-Content -LiteralPath $handoffPath -Raw | ConvertFrom-Json
        $handoff.bundle.patchHash = (Get-FileHash -LiteralPath $patchPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $handoff | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $handoffPath -Encoding UTF8

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-new-file"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
            archive_index = $index
        }

        Assert-True "external_evidence_audit canonical new-file exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit accepts canonical new-file bundle" ($bundleRow.valid -eq $true -and $bundleRow.failReason -notmatch "filemode-blocked") "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit reports canonical new-file metrics" (
                [int]$bundleRow.filemodeLineCount -eq 1 -and
                [int]$bundleRow.allowedNewFileCount -eq 1 -and
                [int]$bundleRow.filemodeViolationCount -eq 0
            ) "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit canonical new-file keeps Desktop proof pending" ($external.Json.desktopFinalProof -eq "evidence_needed") "json=$($external.Raw)"
        }

        Set-Content -LiteralPath $patchPath -Encoding UTF8 -Value $patchText.Replace('@@ -0,0 +1 @@', '@@ -0,0 +1,999 @@')
        Update-ProducerBundleShaSidecar -PatchDrop $patchDrop -Role "macmini" -Topic $topic
        $handoff = Get-Content -LiteralPath $handoffPath -Raw | ConvertFrom-Json
        $handoff.bundle.patchHash = (Get-FileHash -LiteralPath $patchPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $handoff | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $handoffPath -Encoding UTF8
        $malformed = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-malformed-new-file"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
            archive_index = $index
        }
        Assert-True "external_evidence_audit malformed new-file exits zero" ($malformed.ExitCode -eq 0) "output=$($malformed.Raw)"
        if ($malformed.Json) {
            $malformedRow = @($malformed.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit rejects malformed new-file hunk counts" ($malformedRow.valid -eq $false -and $malformedRow.failReason -match 'filemode-blocked') "bundle=$($malformedRow | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsProducerNonUnifiedPatch {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-non-unified-patch-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $index = Join-Path $tmp "index.jsonl"
    $topic = "mcp-non-unified-proof"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini" $topic

        $bundle = "$topic-macmini-v3"
        $nodeDir = Join-Path $patchDrop "macmini"
        $patchName = "$bundle.patch"
        Set-Content -LiteralPath (Join-Path $nodeDir $patchName) -Encoding UTF8 -Value "this is not a unified diff"

        $shaLines = foreach ($fileName in @($patchName, "$bundle.report.md", "$bundle.verify.log", "$bundle.manifest.json")) {
            $hash = (Get-FileHash -LiteralPath (Join-Path $nodeDir $fileName) -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $fileName"
        }
        Set-Content -LiteralPath (Join-Path $nodeDir "$bundle.sha256.txt") -Encoding UTF8 -Value ($shaLines -join "`n")

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-non-unified-patch"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
            archive_index = $index
        }

        Assert-True "external_evidence_audit non-unified patch exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects non-unified producer patch" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit marks non-unified bundle invalid" ($bundleRow.valid -eq $false -and $bundleRow.failReason -match "producer-patch-not-unified-diff") "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit reports zero diff headers" ([int]$bundleRow.diffHeaderCount -eq 0) "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit non-unified patch keeps Desktop proof pending" ($external.Json.desktopFinalProof -eq "evidence_needed") "json=$($external.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsProducerForbiddenPathPatch {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-forbidden-path-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $index = Join-Path $tmp "index.jsonl"
    $topic = "mcp-forbidden-path-proof"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini" $topic

        $bundle = "$topic-macmini-v3"
        $nodeDir = Join-Path $patchDrop "macmini"
        $patchName = "$bundle.patch"
        $patchText = @(
            "diff --git a/pages/api/unsafe.ts b/pages/api/unsafe.ts",
            "new file mode 100644",
            "index 0000000..2222222",
            "--- /dev/null",
            "+++ b/pages/api/unsafe.ts",
            "@@ -0,0 +1 @@",
            "+export default function handler() {}"
        ) -join "`n"
        Set-Content -LiteralPath (Join-Path $nodeDir $patchName) -Encoding UTF8 -Value $patchText

        $shaLines = foreach ($fileName in @($patchName, "$bundle.report.md", "$bundle.verify.log", "$bundle.manifest.json")) {
            $hash = (Get-FileHash -LiteralPath (Join-Path $nodeDir $fileName) -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $fileName"
        }
        Set-Content -LiteralPath (Join-Path $nodeDir "$bundle.sha256.txt") -Encoding UTF8 -Value ($shaLines -join "`n")

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-forbidden-path"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
            archive_index = $index
        }

        Assert-True "external_evidence_audit forbidden path exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects forbidden path producer patch" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit marks forbidden path bundle invalid" ($bundleRow.valid -eq $false -and $bundleRow.failReason -match "forbidden-path") "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit reports forbidden path count" ([int]$bundleRow.forbiddenPathCount -ge 1) "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit forbidden path keeps Desktop proof pending" ($external.Json.desktopFinalProof -eq "evidence_needed") "json=$($external.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsProducerUnsafePathPatch {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-unsafe-path-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $index = Join-Path $tmp "index.jsonl"
    $topic = "mcp-unsafe-path-proof"
    New-Item -ItemType Directory -Force -Path $evidenceDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-ProducerBundleEvidence $patchDrop "macmini" $topic

        $bundle = "$topic-macmini-v3"
        $nodeDir = Join-Path $patchDrop "macmini"
        $patchName = "$bundle.patch"
        $patchText = @(
            "diff --git a/../main/java/Escape.java b/../main/java/Escape.java",
            "index 0000000..2222222",
            "--- /dev/null",
            "+++ b/../main/java/Escape.java",
            "@@ -0,0 +1 @@",
            "+class Escape {}"
        ) -join "`n"
        Set-Content -LiteralPath (Join-Path $nodeDir $patchName) -Encoding UTF8 -Value $patchText

        $shaLines = foreach ($fileName in @($patchName, "$bundle.report.md", "$bundle.verify.log", "$bundle.manifest.json")) {
            $hash = (Get-FileHash -LiteralPath (Join-Path $nodeDir $fileName) -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $fileName"
        }
        Set-Content -LiteralPath (Join-Path $nodeDir "$bundle.sha256.txt") -Encoding UTF8 -Value ($shaLines -join "`n")

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-unsafe-path"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
            archive_index = $index
        }

        Assert-True "external_evidence_audit unsafe path exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects unsafe path producer patch" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit marks unsafe path bundle invalid" ($bundleRow.valid -eq $false -and $bundleRow.failReason -match "unsafe-path") "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit reports unsafe path count" ([int]$bundleRow.forbiddenPathCount -ge 1) "bundle=$($bundleRow | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit unsafe path keeps Desktop proof pending" ($external.Json.desktopFinalProof -eq "evidence_needed") "json=$($external.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceRejectsCanonicalNodeSmokeProof {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-canonical-proof-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "desktop-evidence"
    $proofDir = Join-Path $tmp "external-node-proof"
    $index = Join-Path $tmp "index.jsonl"
    New-Item -ItemType Directory -Force -Path $proofDir, $evidenceDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $proofDir "macmini"
        $badProofPath = Join-Path $proofDir "macmini-node-smoke.json"
        $badProof = Get-Content -LiteralPath $badProofPath -Raw | ConvertFrom-Json
        $badProof.rootHash = "desktop-canonical"
        $badProof.canonicalRootHash = "desktop-canonical"
        $badProof.sourceIsolation.guard = "FAIL"
        $badProof.sourceIsolation.sourceRootKind = "desktop-canonical"
        $badProof.sourceIsolation.desktopCanonicalSourceRoot = $true
        $badProof.sourceIsolation.directCanonicalSourceEdit = $true
        $badProof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $badProofPath -Encoding UTF8

        $audit = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-canonical-audit"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $proofDir
            required_roles = @("macmini")
            archive_index = $index
        }
        Assert-True "external_evidence_audit canonical proof exits zero" ($audit.ExitCode -eq 0) "output=$($audit.Raw)"
        if ($audit.Json) {
            Assert-True "external_evidence_audit rejects canonical node proof" ($audit.Json.ok -eq $false -and $audit.Json.externalEvidenceComplete -eq $false) "json=$($audit.Raw)"
            Assert-True "external_evidence_audit reports source isolation violation" (($audit.Json.evidence_needed -join ' ') -match "source-isolation") "evidence_needed=$($audit.Json.evidence_needed -join ';')"
        }

        $staleTarget = Join-Path $evidenceDir "macmini-node-smoke.json"
        Set-Content -LiteralPath $staleTarget -Encoding UTF8 -Value '{"stale":true}'
        $intake = Invoke-ToolboxJson "external_evidence_intake" @{
            requestId = "test-external-canonical-intake"
            sessionId = "session-a"
            nodeRole = "desktop"
            source_evidence_dir = $proofDir
            evidence_dir = $evidenceDir
            required_roles = @("macmini")
            archive_index = $index
        }
        Assert-True "external_evidence_intake canonical proof exits zero" ($intake.ExitCode -eq 0) "output=$($intake.Raw)"
        if ($intake.Json) {
            Assert-True "external_evidence_intake rejects canonical node proof" ($intake.Json.ok -eq $false -and $intake.Json.externalEvidenceComplete -eq $false) "json=$($intake.Raw)"
            $rejectedText = ($intake.Json.rejectedEvidence | ConvertTo-Json -Depth 10 -Compress)
            Assert-True "external_evidence_intake reports source isolation rejection" ([string]$rejectedText -match "source-isolation") "rejected=$rejectedText"
            Assert-True "external_evidence_intake summary counts rejected proof" ([int]$intake.Json.intakeSummary.copiedEvidenceCount -eq 0 -and [int]$intake.Json.intakeSummary.rejectedEvidenceCount -eq 1) "summary=$($intake.Json.intakeSummary | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_intake clears stale canonical target" (-not (Test-Path -LiteralPath $staleTarget)) "stale target remains: $staleTarget"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceRejectsNodeSmokeWithoutGitRootProof {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-missing-git-proof-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "desktop-evidence"
    $proofDir = Join-Path $tmp "external-node-proof"
    $index = Join-Path $tmp "index.jsonl"
    New-Item -ItemType Directory -Force -Path $proofDir, $evidenceDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $proofDir "macmini"
        $badProofPath = Join-Path $proofDir "macmini-node-smoke.json"
        $badProof = Get-Content -LiteralPath $badProofPath -Raw | ConvertFrom-Json
        $badProof.sourceIsolation.PSObject.Properties.Remove("gitRootPresent")
        $badProof.sourceIsolation.PSObject.Properties.Remove("gitRootMatchesSourceRoot")
        $badProof.sourceIsolation.PSObject.Properties.Remove("gitRootHash")
        $badProof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $badProofPath -Encoding UTF8

        $audit = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-missing-git-root-audit"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $proofDir
            required_roles = @("macmini")
            archive_index = $index
        }
        Assert-True "external_evidence_audit missing git-root proof exits zero" ($audit.ExitCode -eq 0) "output=$($audit.Raw)"
        if ($audit.Json) {
            Assert-True "external_evidence_audit rejects missing git-root node proof" ($audit.Json.ok -eq $false -and $audit.Json.externalEvidenceComplete -eq $false) "json=$($audit.Raw)"
            $nodeRow = @($audit.Json.nodeEvidence | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit reports missing git-root proof" ($nodeRow.valid -eq $false -and $nodeRow.failReason -match "git-root") "nodeEvidence=$($audit.Json.nodeEvidence | ConvertTo-Json -Depth 10 -Compress)"
        }

        $staleTarget = Join-Path $evidenceDir "macmini-node-smoke.json"
        Set-Content -LiteralPath $staleTarget -Encoding UTF8 -Value '{"stale":true}'
        $intake = Invoke-ToolboxJson "external_evidence_intake" @{
            requestId = "test-external-missing-git-root-intake"
            sessionId = "session-a"
            nodeRole = "desktop"
            source_evidence_dir = $proofDir
            evidence_dir = $evidenceDir
            required_roles = @("macmini")
            archive_index = $index
        }
        Assert-True "external_evidence_intake missing git-root proof exits zero" ($intake.ExitCode -eq 0) "output=$($intake.Raw)"
        if ($intake.Json) {
            Assert-True "external_evidence_intake rejects missing git-root node proof" ($intake.Json.ok -eq $false -and $intake.Json.externalEvidenceComplete -eq $false) "json=$($intake.Raw)"
            $rejectedText = ($intake.Json.rejectedEvidence | ConvertTo-Json -Depth 10 -Compress)
            Assert-True "external_evidence_intake reports missing git-root proof" ([string]$rejectedText -match "git-root") "rejected=$rejectedText"
            Assert-True "external_evidence_intake clears stale missing-git target" (-not (Test-Path -LiteralPath $staleTarget)) "stale target remains: $staleTarget"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsWrongProducerRootSmokeProof {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-wrong-root-proof-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $evidenceDir, $dispatchDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        $proofPath = Join-Path $evidenceDir "macmini-node-smoke.json"
        $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
        $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue "wrong-source-root-hash"
        $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8

        @{
            topic = "mcp-wrong-root-proof"
            desktopFinalProof = "evidence_needed"
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = "C:/expected/macmini-worktree"
                    producerPatchdropRoot = "C:/expected/macmini-worktree/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-wrong-root-proof-desktop-dispatch.json") -Encoding UTF8

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-wrong-root-proof"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            require_producer_bundles = $false
        }

        Assert-True "external_evidence_audit wrong-root exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects smoke proof from wrong producer root" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $nodeRow = @($external.Json.nodeEvidence | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit names source-root mismatch" ($nodeRow.valid -eq $false -and $nodeRow.failReason -match "source-root") "nodeEvidence=$($external.Json.nodeEvidence | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsWrongNodeSmokeQueryHash {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-wrong-query-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $index = Join-Path $tmp "index.jsonl"
    $topic = "mcp-wrong-query-proof"
    $expectedRoot = "C:/expected/macmini-worktree"
    $expectedHash = Get-TestStableHash $expectedRoot
    $expectedQueryHash = Get-TestStableHash "$topic external node proof"
    New-Item -ItemType Directory -Force -Path $evidenceDir, $dispatchDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        $proofPath = Join-Path $evidenceDir "macmini-node-smoke.json"
        $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
        $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue $expectedHash -Force
        $proof | Add-Member -NotePropertyName "queryHash" -NotePropertyValue "wrong-query-hash" -Force
        $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8

        @{
            topic = $topic
            desktopFinalProof = "evidence_needed"
            packets = @(
                @{
                    nodeRole = "macmini"
                    topic = $topic
                    sourceRoot = $expectedRoot
                    sourceRootInputHash = $expectedHash
                    nodeSmokeQueryHash = $expectedQueryHash
                    producerPatchdropRoot = "$expectedRoot/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "$topic-desktop-dispatch.json") -Encoding UTF8
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $topic -SourceRoot $expectedRoot -SourceRootInputHash $expectedHash

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-wrong-query"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            archive_index = $index
        }

        Assert-True "external_evidence_audit wrong-query exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit rejects node proof from wrong query" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $nodeRow = @($external.Json.nodeEvidence | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit names node smoke query mismatch" ($nodeRow.valid -eq $false -and $nodeRow.failReason -match "query") "nodeEvidence=$($external.Json.nodeEvidence | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsWrongProducerRootBundle {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-wrong-root-bundle-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $index = Join-Path $tmp "index.jsonl"
    $topic = "mcp-wrong-root-bundle"
    $expectedRoot = "C:/expected/macmini-worktree"
    $wrongRoot = "C:\wrong\macmini-worktree"
    $expectedHash = Get-TestStableHash $expectedRoot
    $wrongHash = Get-TestStableHash $wrongRoot
    New-Item -ItemType Directory -Force -Path $evidenceDir, $dispatchDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        $proofPath = Join-Path $evidenceDir "macmini-node-smoke.json"
        $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
        $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue $expectedHash
        $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8

        @{
            topic = $topic
            desktopFinalProof = "evidence_needed"
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = $expectedRoot
                    sourceRootInputHash = $expectedHash
                    producerPatchdropRoot = "$expectedRoot/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "$topic-desktop-dispatch.json") -Encoding UTF8
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $topic -SourceRoot $wrongRoot -SourceRootInputHash $wrongHash

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-wrong-root-bundle"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            archive_index = $index
        }

        Assert-True "external_evidence_audit wrong-root bundle exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit keeps matching node proof valid" (@($external.Json.nodeEvidence | Where-Object { $_.nodeRole -eq "macmini" -and $_.valid -eq $true }).Count -eq 1) "nodeEvidence=$($external.Json.nodeEvidence | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit rejects bundle from wrong producer root" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $bundleRow = @($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit names producer source-root mismatch" ($bundleRow.valid -eq $false -and $bundleRow.failReason -match "producer-source-root") "producerBundles=$($external.Json.producerBundles | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsWrongProducerRootHandoff {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-wrong-root-handoff-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $index = Join-Path $tmp "index.jsonl"
    $topic = "mcp-wrong-root-handoff"
    $expectedRoot = "C:/expected/macmini-worktree"
    $expectedHash = Get-TestStableHash $expectedRoot
    New-Item -ItemType Directory -Force -Path $evidenceDir, $dispatchDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        $proofPath = Join-Path $evidenceDir "macmini-node-smoke.json"
        $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
        $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue $expectedHash
        $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8

        @{
            topic = $topic
            desktopFinalProof = "evidence_needed"
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = $expectedRoot
                    sourceRootInputHash = $expectedHash
                    producerPatchdropRoot = "$expectedRoot/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "$topic-desktop-dispatch.json") -Encoding UTF8
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $topic -SourceRoot $expectedRoot -SourceRootInputHash $expectedHash

        $handoffPath = Join-Path $patchDrop "external-node-proof\macmini-producer-handoff.json"
        $handoff = Get-Content -LiteralPath $handoffPath -Raw | ConvertFrom-Json
        $handoff.PSObject.Properties.Remove("sourceRootInputHash")
        $handoff.sourceRootHash = "wrong-source-root-hash"
        $handoff | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $handoffPath -Encoding UTF8

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-wrong-root-handoff"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            archive_index = $index
        }

        Assert-True "external_evidence_audit wrong-root handoff exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit keeps matching node proof valid for handoff mismatch" (@($external.Json.nodeEvidence | Where-Object { $_.nodeRole -eq "macmini" -and $_.valid -eq $true }).Count -eq 1) "nodeEvidence=$($external.Json.nodeEvidence | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit keeps matching bundle valid for handoff mismatch" (@($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" -and $_.valid -eq $true }).Count -eq 1) "producerBundles=$($external.Json.producerBundles | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit rejects handoff from wrong producer root" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $handoffRow = @($external.Json.producerHandoffs | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit names producer handoff source-root mismatch" ($handoffRow.valid -eq $false -and $handoffRow.failReason -match "producer-source-root") "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsWrongProducerRootHandoffInputHash {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-wrong-root-handoff-input-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $index = Join-Path $tmp "index.jsonl"
    $topic = "mcp-wrong-root-handoff-input"
    $expectedRoot = "C:/expected/macmini-worktree"
    $expectedHash = Get-TestStableHash $expectedRoot
    New-Item -ItemType Directory -Force -Path $evidenceDir, $dispatchDir | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        $proofPath = Join-Path $evidenceDir "macmini-node-smoke.json"
        $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
        $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue $expectedHash -Force
        $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8

        @{
            topic = $topic
            desktopFinalProof = "evidence_needed"
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = $expectedRoot
                    sourceRootInputHash = $expectedHash
                    producerPatchdropRoot = "$expectedRoot/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "$topic-desktop-dispatch.json") -Encoding UTF8
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $topic -SourceRoot $expectedRoot -SourceRootInputHash $expectedHash

        $handoffPath = Join-Path $patchDrop "external-node-proof\macmini-producer-handoff.json"
        $handoff = Get-Content -LiteralPath $handoffPath -Raw | ConvertFrom-Json
        $handoff.sourceRootInputHash = "wrong-source-root-input-hash"
        $handoff | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $handoffPath -Encoding UTF8

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-wrong-root-handoff-input"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            archive_index = $index
        }

        Assert-True "external_evidence_audit wrong-root handoff input exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit keeps matching node proof valid for handoff input mismatch" (@($external.Json.nodeEvidence | Where-Object { $_.nodeRole -eq "macmini" -and $_.valid -eq $true }).Count -eq 1) "nodeEvidence=$($external.Json.nodeEvidence | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit keeps matching bundle valid for handoff input mismatch" (@($external.Json.producerBundles | Where-Object { $_.nodeRole -eq "macmini" -and $_.valid -eq $true }).Count -eq 1) "producerBundles=$($external.Json.producerBundles | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit rejects handoff input from wrong producer root" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            $handoffRow = @($external.Json.producerHandoffs | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit names producer handoff input source-root mismatch" ($handoffRow.valid -eq $false -and $handoffRow.failReason -match "producer-source-root") "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditReportsMissingNotebook {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-evidence-missing-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        Write-NodeSmokeEvidence $tmp "macmini"
        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-evidence-missing"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $tmp
            required_roles = @("macmini", "notebook")
        }
        Assert-True "external_evidence_audit missing role exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            Assert-True "external_evidence_audit missing role stays incomplete" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            Assert-True "external_evidence_audit names missing notebook" (($external.Json.evidence_needed -join ' ') -match "notebook") "evidence_needed=$($external.Json.evidence_needed -join ';')"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditRejectsStaleProducerCommandHash {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-stale-command-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $topic = "mcp-stale-command"
    $expectedRoot = "/Users/example/awx-macmini"
    $expectedHash = Get-TestStableHash $expectedRoot
    New-Item -ItemType Directory -Force -Path $evidenceDir, $dispatchDir | Out-Null
    try {
        Set-Content -LiteralPath (Join-Path $dispatchDir "$topic-macmini.commands.txt") -Encoding UTF8 -Value "current Desktop-rendered producer command"
        @{
            topic = $topic
            desktopFinalProof = "evidence_needed"
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = $expectedRoot
                    sourceRootInputHash = $expectedHash
                    producerPatchdropRoot = "$expectedRoot/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "$topic-desktop-dispatch.json") -Encoding UTF8

        Write-NodeSmokeEvidence $evidenceDir "macmini"
        $proofPath = Join-Path $evidenceDir "macmini-node-smoke.json"
        $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
        $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue $expectedHash -Force
        $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8
        Write-ProducerBundleEvidence -PatchDrop $patchDrop -Role "macmini" -Topic $topic -SourceRoot $expectedRoot -SourceRootInputHash $expectedHash -ProducerCommandHash "0000000000000000000000000000000000000000000000000000000000000000"

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-stale-command-hash"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $topic
        }
        Assert-True "external_evidence_audit stale command hash exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            $handoffRow = @($external.Json.producerHandoffs | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit rejects stale producer command hash" ($external.Json.ok -eq $false -and $handoffRow.valid -eq $false -and $handoffRow.failReason -match "producer-command-hash") "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit reports expected and actual producer command hash" (-not [string]::IsNullOrWhiteSpace([string]$handoffRow.expectedProducerCommandHash) -and [string]$handoffRow.producerCommandHash -match "^0{64}$") "producerHandoffs=$($external.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditReportsNextActionsFromDispatch {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-evidence-next-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $evidenceDir, $dispatchDir | Out-Null
    try {
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-next-macmini.commands.txt") -Encoding UTF8 -Value "python3 scripts/awx_mcp_node_smoke.py --node-role macmini"
        Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-next-notebook.commands.txt") -Encoding UTF8 -Value "python scripts\awx_mcp_node_smoke.py --node-role notebook"
        Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-next-desktop-intake.ps1") -Encoding UTF8 -Value "python .\scripts\awx_mcp_toolbox.py external_evidence_intake"
        @{
            topic = "mcp-next"
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @(
                (Join-Path $dispatchDir "mcp-next-desktop-dispatch.json"),
                (Join-Path $dispatchDir "mcp-next-macmini.commands.txt"),
                (Join-Path $dispatchDir "mcp-next-notebook.commands.txt"),
                (Join-Path $dispatchDir "mcp-next-desktop-intake.ps1")
            )
            dispatchArtifactIndex = @{
                dispatchSha256Sidecar = (Join-Path $dispatchDir "mcp-next-dispatch.sha256.txt")
                sha256CoveredArtifacts = @(
                    "mcp-next-desktop-dispatch.json",
                    "mcp-next-macmini.commands.txt",
                    "mcp-next-notebook.commands.txt",
                    "mcp-next-desktop-intake.ps1"
                )
            }
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = "/Users/example/awx-macmini"
                    producerPatchdropRoot = "/Volumes/WinSrc/demo-1/src/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                },
                @{
                    nodeRole = "notebook"
                    sourceRoot = "C:\AbandonWare\worktrees\awx-notebook"
                    producerPatchdropRoot = "Z:\PatchDrop"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-next-desktop-dispatch.json") -Encoding UTF8
        Write-TestDispatchShaSidecar $dispatchDir "mcp-next" @(
            "mcp-next-desktop-dispatch.json",
            "mcp-next-macmini.commands.txt",
            "mcp-next-notebook.commands.txt",
            "mcp-next-desktop-intake.ps1"
        )

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-evidence-next"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini", "notebook")
        }
        Assert-True "external_evidence_audit nextActions exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            $actionsText = $external.Json.nextActions | ConvertTo-Json -Depth 20 -Compress
            Assert-True "external_evidence_audit nextActions stay incomplete" ($external.Json.ok -eq $false -and $external.Json.externalEvidenceComplete -eq $false) "json=$($external.Raw)"
            Assert-True "external_evidence_audit validates dispatch SHA sidecar" ($external.Json.dispatchIntegrity.ok -eq $true -and $external.Json.dispatchIntegrity.sidecarValid -eq $true -and [int]$external.Json.dispatchIntegrity.coveredArtifactCount -eq 4) "dispatchIntegrity=$($external.Json.dispatchIntegrity | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_audit nextActions includes notebook command file" ([bool]($actionsText -match "mcp-next-notebook.commands.txt")) "nextActions=$actionsText"
            $macCommandActions = @($external.Json.nextActions | Where-Object { $_.action -eq "run-producer-command-file" -and $_.targetRole -eq "macmini" })
            $notebookCommandActions = @($external.Json.nextActions | Where-Object { $_.action -eq "run-producer-command-file" -and $_.targetRole -eq "notebook" })
            Assert-True "external_evidence_audit nextActions dedupes macmini command file" ($macCommandActions.Count -eq 1) "nextActions=$actionsText"
            Assert-True "external_evidence_audit nextActions dedupes notebook command file" ($notebookCommandActions.Count -eq 1) "nextActions=$actionsText"
            Assert-True "external_evidence_audit nextActions exposes macmini producer-visible command file" ($macCommandActions[0].producerCommandFile -eq "/Volumes/WinSrc/demo-1/src/__patch_drop__/dispatch/mcp-next-macmini.commands.txt") "nextActions=$actionsText"
            Assert-True "external_evidence_audit nextActions exposes notebook producer-visible command file" ($notebookCommandActions[0].producerCommandFile -eq "Z:\PatchDrop\dispatch\mcp-next-notebook.commands.txt") "nextActions=$actionsText"
            $notebookAction = @($external.Json.nextActions | Where-Object { $_.action -eq "run-producer-command-file" -and $_.targetRole -eq "notebook" } | Select-Object -First 1)
            Assert-True "external_evidence_audit nextActions carries notebook source root" ($notebookAction.sourceRoot -eq "C:\AbandonWare\worktrees\awx-notebook") "nextActions=$actionsText"
            Assert-True "external_evidence_audit nextActions carries producer PatchDrop root" ($notebookAction.producerPatchdropRoot -eq "Z:\PatchDrop" -and $notebookAction.desktopPatchdropRoot -eq $patchDrop) "nextActions=$actionsText"
            Assert-True "external_evidence_audit nextActions carries source-root visibility" ($notebookAction.desktopSourceRootExists -eq $false) "nextActions=$actionsText"
            $setupAction = @($external.Json.nextActions | Where-Object { $_.action -eq "run-mcp-node-setup" -and $_.targetRole -eq "notebook" } | Select-Object -First 1)
            Assert-True "external_evidence_audit nextActions includes notebook setup runner" ($setupAction.Count -eq 1 -and $setupAction.command -match "awx_mcp_node_setup.py") "nextActions=$actionsText"
            Assert-True "external_evidence_audit setup runner carries notebook source root" ($setupAction.sourceRoot -eq "C:\AbandonWare\worktrees\awx-notebook" -and $setupAction.desktopSourceRootExists -eq $false) "setupAction=$($setupAction | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "external_evidence_audit setup runner writes audit log" ($setupAction.auditLog -match "\.codex.*awx-control-tower\.audit\.jsonl" -and $setupAction.command -match "--audit-log") "setupAction=$($setupAction | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "external_evidence_audit setup runner keeps Desktop proof pending" ($setupAction.desktopFinalProof -eq "evidence_needed") "setupAction=$($setupAction | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "external_evidence_audit nextActions includes Desktop intake script" ([bool]($actionsText -match "mcp-next-desktop-intake.ps1")) "nextActions=$actionsText"
            Assert-True "external_evidence_audit nextActions includes mcp client config" ([bool]($actionsText -match "awx-control-tower-mcp-client.sample.json")) "nextActions=$actionsText"
            $optionalActions = @($external.Json.optionalNextActions)
            $optionalActionsText = $optionalActions | ConvertTo-Json -Depth 20 -Compress
            if ($optionalActions.Count -gt 0) {
                Assert-True "external_evidence_audit optional archive hint is redacted" ([bool]($optionalActionsText -match "archive_index_path_hash|expectedPathHash|ARCHIVE_INDEX|NAS_ARCHIVE_ROOT|BackupsXS")) "optionalNextActions=$optionalActionsText"
                Assert-True "external_evidence_audit optional archive hint omits raw commands" (-not ($optionalActionsText -match "Test-Path -LiteralPath|expectedPath`"")) "optionalNextActions=$optionalActionsText"
            }
            Assert-True "external_evidence_audit nextActions does not emit secret-like values" (-not (Test-HasSecretLikeValue $external.Raw)) "raw=$($external.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-RunPipelineReportsMcpControlTowerRunner {
    $runner = Join-Path $Root "scripts\awx_mcp_control_tower_pipeline.py"
    $pipeline = Invoke-ToolboxJson "run_pipeline" @{
        requestId = "test-run-pipeline"
        sessionId = "session-a"
        nodeRole = "desktop"
        root = $Root
    }
    Assert-True "run_pipeline exits zero" ($pipeline.ExitCode -eq 0) "output=$($pipeline.Raw)"
    $doc = Get-Content -Raw -LiteralPath $Manifest | ConvertFrom-Json
    $runPipeline = $doc.tools | Where-Object { $_.name -eq "run_pipeline" } | Select-Object -First 1
    Assert-True "run_pipeline schema exposes pipeline plan" (@($runPipeline.output_schema.properties.PSObject.Properties.Name) -contains "pipelinePlan") "fields=$($runPipeline.output_schema.properties.PSObject.Properties.Name -join ',')"
    Assert-True "run_pipeline schema requires pipeline plan" (@($runPipeline.output_schema.required) -contains "pipelinePlan") "required=$($runPipeline.output_schema.required -join ',')"
    if ($pipeline.Json) {
        Assert-True "run_pipeline reports MCP control tower runner available" ($pipeline.Json.tools.mcp_control_tower_pipeline -eq $true -and (Test-Path -LiteralPath $runner)) "tools=$($pipeline.Json.tools | ConvertTo-Json -Compress)"
        Assert-True "run_pipeline returns MCP control tower command" ([string]$pipeline.Json.commands.mcp_control_tower -match "awx_mcp_control_tower_pipeline.py") "commands=$($pipeline.Json.commands | ConvertTo-Json -Compress)"
        Assert-True "run_pipeline keeps legacy shell pipeline optional when MCP runner exists" (-not (([string]$pipeline.Json.evidence_needed).Split(',') -contains "run_pipeline")) "evidence_needed=$($pipeline.Json.evidence_needed)"
        Assert-True "run_pipeline executes MCP control tower plan probe" ($pipeline.Json.pipelinePlan.schemaVersion -eq "awx.mcp.control_tower_pipeline.v1") "pipelinePlan=$($pipeline.Json.pipelinePlan | ConvertTo-Json -Depth 10 -Compress)"
        Assert-True "run_pipeline keeps Desktop final proof pending in plan" ($pipeline.Json.pipelinePlan.desktopFinalProof -eq "evidence_needed") "pipelinePlan=$($pipeline.Json.pipelinePlan | ConvertTo-Json -Depth 10 -Compress)"
    }
}

function Test-ExternalEvidenceIntakeCopiesValidPatchDropProof {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-intake-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $proofDir = Join-Path $patchDrop "external-node-proof"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $evidenceDir = Join-Path $tmp "data\agent-handoff\mcp-control-tower"
    $index = Join-Path $tmp "BackupsXS\index.jsonl"
    New-Item -ItemType Directory -Force -Path $proofDir, $dispatchDir, (Split-Path -Parent $index) | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $proofDir "macmini"
        Write-NodeSmokeEvidence $proofDir "notebook"
        foreach ($role in @("macmini", "notebook")) {
            $proofPath = Join-Path $proofDir "$role-node-smoke.json"
            $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
            $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue (Get-TestStableHash "C:\agent\$role\worktree") -Force
            $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8
        }
        Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-stdio-bridge-verification-macmini.commands.txt") -Encoding UTF8 -Value "python3 scripts/awx_mcp_node_smoke.py --node-role macmini"
        Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-stdio-bridge-verification-notebook.commands.txt") -Encoding UTF8 -Value "python scripts\awx_mcp_node_smoke.py --node-role notebook"
        Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-stdio-bridge-verification-desktop-intake.ps1") -Encoding UTF8 -Value "python .\scripts\awx_mcp_toolbox.py external_evidence_intake"
        @{
            topic = "mcp-stdio-bridge-verification"
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @(
                (Join-Path $dispatchDir "mcp-stdio-bridge-verification-desktop-dispatch.json"),
                (Join-Path $dispatchDir "mcp-stdio-bridge-verification-macmini.commands.txt"),
                (Join-Path $dispatchDir "mcp-stdio-bridge-verification-notebook.commands.txt"),
                (Join-Path $dispatchDir "mcp-stdio-bridge-verification-desktop-intake.ps1")
            )
            dispatchArtifactIndex = @{
                dispatchSha256Sidecar = (Join-Path $dispatchDir "mcp-stdio-bridge-verification-dispatch.sha256.txt")
                sha256CoveredArtifacts = @(
                    "mcp-stdio-bridge-verification-desktop-dispatch.json",
                    "mcp-stdio-bridge-verification-macmini.commands.txt",
                    "mcp-stdio-bridge-verification-notebook.commands.txt",
                    "mcp-stdio-bridge-verification-desktop-intake.ps1"
                )
            }
            packets = @(
                @{ nodeRole = "macmini"; sourceRoot = "C:\agent\macmini\worktree"; producerPatchdropRoot = $patchDrop; desktopPatchdropRoot = $patchDrop; desktopSourceRootExists = $false },
                @{ nodeRole = "notebook"; sourceRoot = "C:\agent\notebook\worktree"; producerPatchdropRoot = $patchDrop; desktopPatchdropRoot = $patchDrop; desktopSourceRootExists = $false }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-stdio-bridge-verification-desktop-dispatch.json") -Encoding UTF8
        Write-TestDispatchShaSidecar $dispatchDir "mcp-stdio-bridge-verification" @(
            "mcp-stdio-bridge-verification-desktop-dispatch.json",
            "mcp-stdio-bridge-verification-macmini.commands.txt",
            "mcp-stdio-bridge-verification-notebook.commands.txt",
            "mcp-stdio-bridge-verification-desktop-intake.ps1"
        )
        Write-ProducerBundleEvidence $patchDrop "macmini"
        Write-ProducerBundleEvidence $patchDrop "notebook"

        $intake = Invoke-ToolboxJson "external_evidence_intake" @{
            requestId = "test-external-intake"
            sessionId = "session-a"
            nodeRole = "desktop"
            patchdrop_root = $patchDrop
            evidence_dir = $evidenceDir
            required_roles = @("macmini", "notebook")
            archive_index = $index
        }

        Assert-True "external_evidence_intake exits zero" ($intake.ExitCode -eq 0) "output=$($intake.Raw)"
        if ($intake.Json) {
            Assert-True "external_evidence_intake completes with both roles" ($intake.Json.ok -eq $true -and $intake.Json.externalEvidenceComplete -eq $true) "json=$($intake.Raw)"
            Assert-True "external_evidence_intake copies two proofs" ([int]$intake.Json.outputCount -eq 2) "outputCount=$($intake.Json.outputCount)"
            Assert-True "external_evidence_intake copies two handoffs" ([int](@($intake.Json.copiedHandoffs).Count) -eq 2) "copiedHandoffs=$($intake.Json.copiedHandoffs | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_intake summary counts copied proofs and handoffs" ([int]$intake.Json.intakeSummary.copiedEvidenceCount -eq 2 -and [int]$intake.Json.intakeSummary.copiedHandoffCount -eq 2 -and [int]$intake.Json.intakeSummary.rejectedEvidenceCount -eq 0 -and [int]$intake.Json.intakeSummary.rejectedHandoffCount -eq 0) "summary=$($intake.Json.intakeSummary | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_intake validates two producer handoffs" ([int](@($intake.Json.producerHandoffs | Where-Object { $_.valid -eq $true }).Count) -eq 2) "producerHandoffs=$($intake.Json.producerHandoffs | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_intake validates two producer bundles" ([int](@($intake.Json.producerBundles | Where-Object { $_.valid -eq $true }).Count) -eq 2) "producerBundles=$($intake.Json.producerBundles | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_intake validates dispatch integrity for complete proof" ($intake.Json.dispatchIntegrity.ok -eq $true -and $intake.Json.dispatchIntegrity.sidecarValid -eq $true -and [int]$intake.Json.dispatchIntegrity.coveredArtifactCount -eq 4) "dispatchIntegrity=$($intake.Json.dispatchIntegrity | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_intake carries unrelated PatchDrop evidence summary" ($null -ne $intake.Json.unrelatedPatchDropEvidence -and [int]$intake.Json.unrelatedPatchDropEvidence.total -eq 0) "unrelated=$($intake.Json.unrelatedPatchDropEvidence | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "external_evidence_intake records Desktop proof pending" ($intake.Json.desktopFinalProof -eq "evidence_needed") "desktopFinalProof=$($intake.Json.desktopFinalProof)"
            Assert-True "external_evidence_intake reports no raw secret leak" ([int]$intake.Json.rawSecretPatternHits -eq 0) "rawSecretPatternHits=$($intake.Json.rawSecretPatternHits)"
        }

        foreach ($role in @("macmini", "notebook")) {
            $copied = Join-Path $evidenceDir "$role-node-smoke.json"
            Assert-True "external_evidence_intake writes $role Desktop evidence" (Test-Path -LiteralPath $copied) "missing $copied"
            $copiedHandoff = Join-Path $evidenceDir "$role-producer-handoff.json"
            Assert-True "external_evidence_intake writes $role handoff evidence" (Test-Path -LiteralPath $copiedHandoff) "missing $copiedHandoff"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceIntakeRejectsWrongProducerRootProof {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-intake-wrong-root-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $proofDir = Join-Path $patchDrop "external-node-proof"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $evidenceDir = Join-Path $tmp "data\agent-handoff\mcp-control-tower"
    New-Item -ItemType Directory -Force -Path $proofDir, $dispatchDir, $evidenceDir | Out-Null
    try {
        Write-NodeSmokeEvidence $proofDir "macmini"
        $proofPath = Join-Path $proofDir "macmini-node-smoke.json"
        $proof = Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
        $proof | Add-Member -NotePropertyName "sourceRootInputHash" -NotePropertyValue "wrong-source-root-hash"
        $proof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $proofPath -Encoding UTF8

        $staleTarget = Join-Path $evidenceDir "macmini-node-smoke.json"
        Set-Content -LiteralPath $staleTarget -Encoding UTF8 -Value '{"stale":true}'

        @{
            topic = "mcp-intake-wrong-root-proof"
            desktopFinalProof = "evidence_needed"
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = "C:/expected/macmini-worktree"
                    producerPatchdropRoot = "C:/expected/macmini-worktree/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-intake-wrong-root-proof-desktop-dispatch.json") -Encoding UTF8

        $intake = Invoke-ToolboxJson "external_evidence_intake" @{
            requestId = "test-external-intake-wrong-root"
            sessionId = "session-a"
            nodeRole = "desktop"
            patchdrop_root = $patchDrop
            evidence_dir = $evidenceDir
            required_roles = @("macmini")
            require_producer_bundles = $false
        }

        Assert-True "external_evidence_intake wrong-root exits zero" ($intake.ExitCode -eq 0) "output=$($intake.Raw)"
        if ($intake.Json) {
            Assert-True "external_evidence_intake rejects smoke proof from wrong producer root" ($intake.Json.ok -eq $false -and $intake.Json.externalEvidenceComplete -eq $false) "json=$($intake.Raw)"
            $rejectedText = ($intake.Json.rejectedEvidence | ConvertTo-Json -Depth 10 -Compress)
            Assert-True "external_evidence_intake names source-root mismatch" ([string]$rejectedText -match "source-root") "rejected=$rejectedText"
            Assert-True "external_evidence_intake clears stale wrong-root target" (-not (Test-Path -LiteralPath $staleTarget)) "stale target remains: $staleTarget"
            Assert-True "external_evidence_intake does not copy wrong-root proof" ([int]$intake.Json.outputCount -eq 0) "copied=$($intake.Json.copiedEvidence | ConvertTo-Json -Depth 10 -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceIntakeRejectsSecretProofAndClearsStaleTargets {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-intake-secret-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $proofDir = Join-Path $patchDrop "external-node-proof"
    $evidenceDir = Join-Path $tmp "data\agent-handoff\mcp-control-tower"
    $index = Join-Path $tmp "BackupsXS\index.jsonl"
    New-Item -ItemType Directory -Force -Path $proofDir, $evidenceDir, (Split-Path -Parent $index) | Out-Null
    try {
        Set-Content -LiteralPath $index -Encoding UTF8 -Value '{"path":"snapshots/mcp.md","title":"MCP evidence","summary":"safe patch"}'
        Write-NodeSmokeEvidence $proofDir "macmini"
        Write-NodeSmokeEvidence $proofDir "notebook"
        Write-NodeSmokeEvidence $evidenceDir "macmini"
        Write-NodeSmokeEvidence $evidenceDir "notebook"
        Write-ProducerBundleEvidence $patchDrop "macmini"
        Write-ProducerBundleEvidence $patchDrop "notebook"

        $secretLike = "sk-" + ("A" * 24)
        $notebookProofPath = Join-Path $proofDir "notebook-node-smoke.json"
        $notebookProof = Get-Content -LiteralPath $notebookProofPath -Raw | ConvertFrom-Json
        $notebookProof | Add-Member -NotePropertyName leakedDiagnostic -NotePropertyValue $secretLike
        $notebookProof | ConvertTo-Json -Depth 20 -Compress | Set-Content -LiteralPath $notebookProofPath -Encoding UTF8

        $intake = Invoke-ToolboxJson "external_evidence_intake" @{
            requestId = "test-external-intake-secret"
            sessionId = "session-a"
            nodeRole = "desktop"
            patchdrop_root = $patchDrop
            evidence_dir = $evidenceDir
            required_roles = @("macmini", "notebook")
            archive_index = $index
        }

        Assert-True "external_evidence_intake secret proof exits zero" ($intake.ExitCode -eq 0) "output=$($intake.Raw)"
        if ($intake.Json) {
            Assert-True "external_evidence_intake secret proof stays incomplete" ($intake.Json.ok -eq $false -and $intake.Json.externalEvidenceComplete -eq $false) "json=$($intake.Raw)"
            Assert-True "external_evidence_intake secret proof reports hit count" ([int]$intake.Json.rawSecretPatternHits -gt 0) "rawSecretPatternHits=$($intake.Json.rawSecretPatternHits)"
            Assert-True "external_evidence_intake secret proof rejects notebook" (($intake.Json.rejectedEvidence | ConvertTo-Json -Depth 10 -Compress) -match "secret-leak-risk") "rejected=$($intake.Json.rejectedEvidence | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_intake secret proof does not echo secret" (-not $intake.Raw.Contains($secretLike)) "raw=$($intake.Raw)"
        }

        foreach ($role in @("macmini", "notebook")) {
            $copied = Join-Path $evidenceDir "$role-node-smoke.json"
            Assert-True "external_evidence_intake clears stale $role target on rejection" (-not (Test-Path -LiteralPath $copied)) "stale target remains: $copied"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceAuditPinsNextActionsToTopic {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-topic-next-" + [guid]::NewGuid().ToString("N"))
    $evidenceDir = Join-Path $tmp "evidence"
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $evidenceDir, $dispatchDir | Out-Null
    try {
        $desiredTopic = "desired-topic"
        $distractorTopic = "distractor-topic"
        Set-Content -LiteralPath (Join-Path $dispatchDir "$desiredTopic-macmini.commands.txt") -Encoding UTF8 -Value "python3 desired.py"
        @{
            topic = $desiredTopic
            desktopFinalProof = "evidence_needed"
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = "/Users/example/desired-macmini"
                    producerPatchdropRoot = "/Volumes/Desired/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "$desiredTopic-desktop-dispatch.json") -Encoding UTF8
        Start-Sleep -Milliseconds 50
        Set-Content -LiteralPath (Join-Path $dispatchDir "$distractorTopic-macmini.commands.txt") -Encoding UTF8 -Value "python3 distractor.py"
        @{
            topic = $distractorTopic
            desktopFinalProof = "evidence_needed"
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = "/Users/example/distractor-macmini"
                    producerPatchdropRoot = "/Volumes/Distractor/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "$distractorTopic-desktop-dispatch.json") -Encoding UTF8

        $external = Invoke-ToolboxJson "external_evidence_audit" @{
            requestId = "test-external-topic-next"
            sessionId = "session-a"
            nodeRole = "desktop"
            evidence_dir = $evidenceDir
            patchdrop_root = $patchDrop
            required_roles = @("macmini")
            topic = $desiredTopic
        }
        Assert-True "external_evidence_audit topic-pinned nextActions exits zero" ($external.ExitCode -eq 0) "output=$($external.Raw)"
        if ($external.Json) {
            $actionsText = $external.Json.nextActions | ConvertTo-Json -Depth 20 -Compress
            $commandAction = @($external.Json.nextActions | Where-Object { $_.action -eq "run-producer-command-file" -and $_.targetRole -eq "macmini" } | Select-Object -First 1)
            Assert-True "external_evidence_audit topic-pinned nextActions uses requested command file" ($commandAction.commandFile -match "$desiredTopic-macmini\.commands\.txt") "nextActions=$actionsText"
            Assert-True "external_evidence_audit topic-pinned nextActions uses requested source root" ($commandAction.sourceRoot -eq "/Users/example/desired-macmini") "nextActions=$actionsText"
            Assert-True "external_evidence_audit topic-pinned nextActions uses requested producer-visible command file" ($commandAction.producerCommandFile -eq "/Volumes/Desired/__patch_drop__/dispatch/$desiredTopic-macmini.commands.txt") "nextActions=$actionsText"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ExternalEvidenceIntakeCarriesNextActionsWhenIncomplete {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-external-intake-next-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "__patch_drop__"
    $proofDir = Join-Path $patchDrop "external-node-proof"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $evidenceDir = Join-Path $tmp "data\agent-handoff\mcp-control-tower"
    New-Item -ItemType Directory -Force -Path $proofDir, $dispatchDir | Out-Null
    try {
        Write-NodeSmokeEvidence $proofDir "macmini"
        Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-intake-next-macmini.commands.txt") -Encoding UTF8 -Value "python3 scripts/awx_mcp_node_smoke.py --node-role macmini"
        Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-intake-next-notebook.commands.txt") -Encoding UTF8 -Value "python scripts\awx_mcp_node_smoke.py --node-role notebook"
        Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-intake-next-desktop-intake.ps1") -Encoding UTF8 -Value "python .\scripts\awx_mcp_toolbox.py external_evidence_intake"
        @{
            topic = "mcp-intake-next"
            desktopFinalProof = "evidence_needed"
            dispatchArtifacts = @(
                (Join-Path $dispatchDir "mcp-intake-next-desktop-dispatch.json"),
                (Join-Path $dispatchDir "mcp-intake-next-macmini.commands.txt"),
                (Join-Path $dispatchDir "mcp-intake-next-notebook.commands.txt"),
                (Join-Path $dispatchDir "mcp-intake-next-desktop-intake.ps1")
            )
            dispatchArtifactIndex = @{
                dispatchSha256Sidecar = (Join-Path $dispatchDir "mcp-intake-next-dispatch.sha256.txt")
                sha256CoveredArtifacts = @(
                    "mcp-intake-next-desktop-dispatch.json",
                    "mcp-intake-next-macmini.commands.txt",
                    "mcp-intake-next-notebook.commands.txt",
                    "mcp-intake-next-desktop-intake.ps1"
                )
            }
            packets = @(
                @{
                    nodeRole = "macmini"
                    sourceRoot = "/Users/example/awx-macmini"
                    producerPatchdropRoot = "/Volumes/WinSrc/demo-1/src/__patch_drop__"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                },
                @{
                    nodeRole = "notebook"
                    sourceRoot = "C:\AbandonWare\worktrees\awx-notebook"
                    producerPatchdropRoot = "Z:\PatchDrop"
                    desktopPatchdropRoot = $patchDrop
                    desktopSourceRootExists = $false
                }
            )
        } | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath (Join-Path $dispatchDir "mcp-intake-next-desktop-dispatch.json") -Encoding UTF8
        Write-TestDispatchShaSidecar $dispatchDir "mcp-intake-next" @(
            "mcp-intake-next-desktop-dispatch.json",
            "mcp-intake-next-macmini.commands.txt",
            "mcp-intake-next-notebook.commands.txt",
            "mcp-intake-next-desktop-intake.ps1"
        )
        Write-ReportOnlyManifestEvidence -PatchDrop $patchDrop -Role "notebook" -Topic "tailscale-smb-load-shed"

        $intake = Invoke-ToolboxJson "external_evidence_intake" @{
            requestId = "test-external-intake-next"
            sessionId = "session-a"
            nodeRole = "desktop"
            patchdrop_root = $patchDrop
            evidence_dir = $evidenceDir
            required_roles = @("macmini", "notebook")
        }

        Assert-True "external_evidence_intake nextActions exits zero" ($intake.ExitCode -eq 0) "output=$($intake.Raw)"
        if ($intake.Json) {
            $actionsText = $intake.Json.nextActions | ConvertTo-Json -Depth 20 -Compress
            Assert-True "external_evidence_intake nextActions stay incomplete" ($intake.Json.ok -eq $false -and $intake.Json.externalEvidenceComplete -eq $false) "json=$($intake.Raw)"
            Assert-True "external_evidence_intake carries dispatch SHA sidecar validation" ($intake.Json.dispatchIntegrity.ok -eq $true -and $intake.Json.dispatchIntegrity.sidecarValid -eq $true -and [int]$intake.Json.dispatchIntegrity.coveredArtifactCount -eq 4) "dispatchIntegrity=$($intake.Json.dispatchIntegrity | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "external_evidence_intake carries producer command actions" ([bool]($actionsText -match "mcp-intake-next-notebook.commands.txt")) "nextActions=$actionsText"
            Assert-True "external_evidence_intake carries Desktop intake action" ([bool]($actionsText -match "mcp-intake-next-desktop-intake.ps1")) "nextActions=$actionsText"
            Assert-True "external_evidence_intake carries unrelated evidence while incomplete" ([int]$intake.Json.unrelatedPatchDropEvidence.total -eq 1 -and [int]$intake.Json.unrelatedPatchDropEvidence.reportOnlyCount -eq 1) "unrelated=$($intake.Json.unrelatedPatchDropEvidence | ConvertTo-Json -Depth 20 -Compress)"
            $optionalActions = @($intake.Json.optionalNextActions)
            $optionalActionsText = $optionalActions | ConvertTo-Json -Depth 20 -Compress
            if ($optionalActions.Count -gt 0) {
                Assert-True "external_evidence_intake optional archive action is redacted" ([bool]($optionalActionsText -match "archive_index_path_hash|expectedPathHash|ARCHIVE_INDEX|NAS_ARCHIVE_ROOT|BackupsXS")) "optionalNextActions=$optionalActionsText"
                Assert-True "external_evidence_intake optional archive action omits raw commands" (-not ($optionalActionsText -match "Test-Path -LiteralPath|expectedPath`"")) "optionalNextActions=$optionalActionsText"
            }
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerCommandPlanRejectsPatchDropSourceRoot {
    $plan = Invoke-ToolboxJson "producer_command_plan" @{
        requestId = "test-producer-command-plan-patchdrop-source"
        sessionId = "session-a"
        nodeRole = "notebook"
        topic = "mcp patchdrop rejected source"
        source_root = "Z:\PatchDrop"
        patchdrop_root = "Z:\PatchDrop"
        canonical_root = "C:\AbandonWare\demo-1\demo-1\src"
        pathspec = @("scripts/awx_mcp_toolbox.py")
    }
    $json = try { $plan.Raw | ConvertFrom-Json } catch { $null }
    Assert-True "producer_command_plan rejects PatchDrop source root" ($plan.ExitCode -eq 0 -and $json -and $json.ok -eq $false) "output=$($plan.Raw)"
    if ($json) {
        Assert-True "producer_command_plan PatchDrop source root classified" ($json.sourceIsolation.guard -eq "FAIL" -and $json.sourceIsolation.sharedSourceRoot -eq $true -and $json.sourceIsolation.sourceRootKind -eq "shared-root") "json=$($plan.Raw)"
        Assert-True "producer_command_plan emits no commands for PatchDrop source root" (@($json.commands).Count -eq 0 -and $json.failReason -eq "smb-direct-edit") "json=$($plan.Raw)"
    }
}

function Test-ProducerCommandPlanRendersExternalNodeCommands {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-command-plan-" + [guid]::NewGuid().ToString("N"))
    $sourceRoot = Join-Path $tmp "macmini-local-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    New-Item -ItemType Directory -Force -Path $sourceRoot, $patchDrop | Out-Null
    try {
        $plan = Invoke-ToolboxJson "producer_command_plan" @{
            requestId = "test-producer-command-plan"
            sessionId = "session-a"
            nodeRole = "macmini"
            topic = "mcp command handoff"
            source_root = $sourceRoot
            patchdrop_root = $patchDrop
            canonical_root = "Y:\"
            pathspec = @("main/java/example/Changed.java", "src/test/java/example/ChangedTest.java")
        }
        Assert-True "producer_command_plan exits zero" ($plan.ExitCode -eq 0) "output=$($plan.Raw)"
        if ($plan.Json) {
            Assert-True "producer_command_plan records macmini role" ($plan.Json.nodeRole -eq "macmini") "nodeRole=$($plan.Json.nodeRole)"
            Assert-True "producer_command_plan marks Desktop proof pending" ($plan.Json.desktopFinalProof -eq "evidence_needed") "desktopFinalProof=$($plan.Json.desktopFinalProof)"
            Assert-True "producer_command_plan writes external proof path" ($plan.Json.proofPath -match "external-node-proof.*macmini-node-smoke.json") "proofPath=$($plan.Json.proofPath)"
            Assert-True "producer_command_plan writes producer handoff proof path" ($plan.Json.handoffProofPath -match "external-node-proof.*macmini-producer-handoff.json") "handoffProofPath=$($plan.Json.handoffProofPath)"
            Assert-True "producer_command_plan writes Desktop evidence handoff path" ($plan.Json.desktopEvidencePath -match "data/agent-handoff/mcp-control-tower/macmini-node-smoke.json") "desktopEvidencePath=$($plan.Json.desktopEvidencePath)"
            Assert-True "producer_command_plan exposes node smoke query hash" ($plan.Json.nodeSmokeQueryHash -match "^[a-f0-9]{64}$") "nodeSmokeQueryHash=$($plan.Json.nodeSmokeQueryHash)"
            Assert-True "producer_command_plan includes env-name-only hints" (@($plan.Json.allowedEnvRefs) -contains "NAVER_KEYS" -and @($plan.Json.allowedEnvRefs) -contains "NAVER_CLIENT_ID" -and @($plan.Json.allowedEnvRefs) -contains "NAVER_CLIENT_SECRET") "allowedEnvRefs=$($plan.Json.allowedEnvRefs -join ',')"
            Assert-True "producer_command_plan does not emit secret-like values" (-not (Test-HasSecretLikeValue $plan.Raw)) "raw=$($plan.Raw)"
            $commandText = $plan.Json.commands -join "`n"
            $setupIndex = Get-ToolInvocationIndex $commandText "awx_mcp_node_setup.py"
            $smokeIndex = Get-ToolInvocationIndex $commandText "awx_mcp_node_smoke.py"
            $handoffIndex = Get-ToolInvocationIndex $commandText "awx_mcp_producer_handoff.py"
            $setupAuditIndex = $commandText.IndexOf("--audit-log", $setupIndex)
            $handoffAuditIndex = $commandText.LastIndexOf("--audit-log")
            Assert-True "producer_command_plan includes node setup command" ($setupIndex -ge 0) "commands=$($plan.Json.commands -join '|')"
            Assert-True "producer_command_plan setup command writes audit log" ($setupIndex -ge 0 -and $setupAuditIndex -gt $setupIndex -and $setupAuditIndex -lt $smokeIndex) "commands=$($plan.Json.commands -join '|')"
            Assert-True "producer_command_plan includes node smoke command" ($smokeIndex -ge 0) "commands=$($plan.Json.commands -join '|')"
            Assert-True "producer_command_plan runs setup before smoke" ($setupIndex -ge 0 -and $smokeIndex -ge 0 -and $setupIndex -lt $smokeIndex) "commands=$($plan.Json.commands -join '|')"
            Assert-True "producer_command_plan validates node smoke query hash" ($commandText.Contains("queryHash") -and $commandText.Contains([string]$plan.Json.nodeSmokeQueryHash)) "commands=$commandText"
            Assert-True "producer_command_plan includes producer handoff command" ($handoffIndex -ge 0) "commands=$($plan.Json.commands -join '|')"
            Assert-True "producer_command_plan handoff writes audit log" ($handoffIndex -ge 0 -and $handoffAuditIndex -gt $handoffIndex) "commands=$($plan.Json.commands -join '|')"
            Assert-True "producer_command_plan includes both pathspecs" (($plan.Json.commands -join ' ') -match "Changed.java" -and ($plan.Json.commands -join ' ') -match "ChangedTest.java") "commands=$($plan.Json.commands -join '|')"
            $producerLocalScript = (($sourceRoot -replace '\\', '/') + "/__patch_drop__/producer_bundle.py")
            $patchDropScript = (($patchDrop -replace '\\', '/') + "/producer_bundle.py")
            Assert-True "producer_command_plan uses producer-local bundle helper" ($commandText.Contains("--producer-script '$producerLocalScript'") -and -not $commandText.Contains("--producer-script '$patchDropScript'")) "commands=$commandText"
            Assert-True "producer_command_plan counts rendered commands" ([int]$plan.Json.outputCount -eq @($plan.Json.commands).Count) "outputCount=$($plan.Json.outputCount) commands=$(@($plan.Json.commands).Count)"
            Assert-True "producer_command_plan keeps canonical source read-only" ($plan.Json.sourceIsolation.directCanonicalSourceEdit -eq $false -and $plan.Json.sourceIsolation.sourceRootKind -eq "local-worktree") "sourceIsolation=$($plan.Json.sourceIsolation | ConvertTo-Json -Compress)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-ProducerCommandPlanPreservesProducerHostPaths {
    $plan = Invoke-ToolboxJson "producer_command_plan" @{
        requestId = "test-producer-command-host-paths"
        sessionId = "session-a"
        nodeRole = "macmini"
        topic = "mcp command host paths"
        source_root = "/Users/awx/agent/macmini/mcp"
        patchdrop_root = "/Volumes/WinSrc/demo-1/src/__patch_drop__"
        canonical_root = "C:/AbandonWare/demo-1/demo-1/src"
        shared_root = "/Users/awx/agent/macmini/mcp"
        pathspec = @("scripts/awx_mcp_toolbox.py")
    }
    Assert-True "producer_command_plan host paths exits zero" ($plan.ExitCode -eq 0) "output=$($plan.Raw)"
    if ($plan.Json) {
        $commands = @($plan.Json.commands) -join "`n"
        Assert-True "producer_command_plan preserves macmini source root" ($commands.Contains("/Users/awx/agent/macmini/mcp/scripts/awx_mcp_node_setup.py") -and $commands.Contains("--source-root '/Users/awx/agent/macmini/mcp'")) "commands=$commands"
        Assert-True "producer_command_plan preserves macmini PatchDrop mount" ($commands.Contains("--patchdrop-root '/Volumes/WinSrc/demo-1/src/__patch_drop__'") -and $plan.Json.proofPath -eq "/Volumes/WinSrc/demo-1/src/__patch_drop__/external-node-proof/macmini-node-smoke.json") "json=$($plan.Raw)"
        Assert-True "producer_command_plan keeps producer helper local while writing to PatchDrop" ($commands.Contains("--producer-script '/Users/awx/agent/macmini/mcp/__patch_drop__/producer_bundle.py'") -and -not $commands.Contains("--producer-script '/Volumes/WinSrc/demo-1/src/__patch_drop__/producer_bundle.py'")) "commands=$commands"
        Assert-True "producer_command_plan does not rewrite mac paths to Desktop drive" (-not ($commands -match "C:/Users|C:/Volumes|C:\\Users|C:\\Volumes")) "commands=$commands"
    }
}

function Test-DesktopDispatchPacketRendersBothProducerPackets {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-dispatch-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $patchDrop | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("main/java/example/MacChanged.java")
                notebook = @("main/java/example/NotebookChanged.java")
            }
        }
        Assert-True "desktop_dispatch_packet exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            Assert-True "desktop_dispatch_packet records desktop role" ($packet.Json.nodeRole -eq "desktop") "nodeRole=$($packet.Json.nodeRole)"
            Assert-True "desktop_dispatch_packet returns two packets" (@($packet.Json.packets).Count -eq 2) "packets=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet counts packets" ([int]$packet.Json.outputCount -eq 2) "outputCount=$($packet.Json.outputCount)"
            Assert-True "desktop_dispatch_packet includes macmini handoff path" (($packet.Json.packets | ConvertTo-Json -Depth 10 -Compress) -match "data/agent-handoff/mcp-control-tower/macmini-node-smoke.json") "packets=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet includes notebook handoff path" (($packet.Json.packets | ConvertTo-Json -Depth 10 -Compress) -match "data/agent-handoff/mcp-control-tower/notebook-node-smoke.json") "packets=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet includes Desktop audit command" ($packet.Json.desktopAuditCommand -match "external_evidence_audit") "desktopAuditCommand=$($packet.Json.desktopAuditCommand)"
            Assert-True "desktop_dispatch_packet audit keeps patchdrop root" ($packet.Json.desktopAuditCommand -match "patchdrop_root") "desktopAuditCommand=$($packet.Json.desktopAuditCommand)"
            Assert-True "desktop_dispatch_packet audit pins producer topic" ($packet.Json.desktopAuditCommand -match "topic" -and $packet.Json.desktopAuditCommand -match "mcp-desktop-dispatch") "desktopAuditCommand=$($packet.Json.desktopAuditCommand)"
            Assert-True "desktop_dispatch_packet audit requires producer bundles" ($packet.Json.desktopAuditCommand -match 'require_producer_bundles\s*=\s*\$true') "desktopAuditCommand=$($packet.Json.desktopAuditCommand)"
            Assert-True "desktop_dispatch_packet intake pins producer topic" ($packet.Json.desktopIntakeCommand -match "topic" -and $packet.Json.desktopIntakeCommand -match "mcp-desktop-dispatch") "desktopIntakeCommand=$($packet.Json.desktopIntakeCommand)"
            Assert-True "desktop_dispatch_packet intake requires producer bundles" ($packet.Json.desktopIntakeCommand -match 'require_producer_bundles\s*=\s*\$true') "desktopIntakeCommand=$($packet.Json.desktopIntakeCommand)"
            Assert-True "desktop_dispatch_packet keeps Desktop proof pending" ($packet.Json.desktopFinalProof -eq "evidence_needed") "desktopFinalProof=$($packet.Json.desktopFinalProof)"
            Assert-True "desktop_dispatch_packet records visible macmini source root" (($packet.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1).desktopSourceRootExists -eq $true) "packets=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet records visible notebook source root" (($packet.Json.packets | Where-Object { $_.nodeRole -eq "notebook" } | Select-Object -First 1).desktopSourceRootExists -eq $true) "packets=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet emits env names only" (@($packet.Json.allowedEnvRefs) -contains "NAVER_KEYS" -and @($packet.Json.allowedEnvRefs) -contains "NAVER_CLIENT_ID" -and @($packet.Json.allowedEnvRefs) -contains "NAVER_CLIENT_SECRET") "allowedEnvRefs=$($packet.Json.allowedEnvRefs -join ',')"
            Assert-True "desktop_dispatch_packet does not emit secret-like values" (-not (Test-HasSecretLikeValue $packet.Raw)) "raw=$($packet.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopDispatchPacketSupportsProducerPatchDropOverrides {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-dispatch-patchdrop-override-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "DesktopPatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $patchDrop | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch-patchdrop-overrides"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch patchdrop overrides"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = "/Users/awx/agent/macmini/mcp"
                notebook = "D:\awx-notebook-worktree"
            }
            producer_patchdrop_roots = @{
                macmini = "/Volumes/WinSrc/demo-1/src/__patch_drop__"
                notebook = "Z:\PatchDrop"
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop_dispatch_packet patchdrop override exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            $slug = "mcp-desktop-dispatch-patchdrop-overrides"
            $macPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            $notebookPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "notebook" } | Select-Object -First 1)
            Assert-True "desktop_dispatch_packet records macmini producer PatchDrop root" ($macPacket.producerPatchdropRoot -eq "/Volumes/WinSrc/demo-1/src/__patch_drop__" -and $macPacket.desktopPatchdropRoot -eq $patchDrop) "packet=$($macPacket | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet records notebook producer PatchDrop root" ($notebookPacket.producerPatchdropRoot -eq "Z:\PatchDrop" -and $notebookPacket.desktopPatchdropRoot -eq $patchDrop) "packet=$($notebookPacket | ConvertTo-Json -Depth 20 -Compress)"

            $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
            $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
            $macText = Get-Content -LiteralPath $macCommands -Raw
            $notebookText = Get-Content -LiteralPath $notebookCommands -Raw
            Assert-True "desktop_dispatch_packet macmini command uses producer PatchDrop mount" ($macText.Contains("--patchdrop-root '/Volumes/WinSrc/demo-1/src/__patch_drop__'") -and $macText.Contains("> '/Volumes/WinSrc/demo-1/src/__patch_drop__/external-node-proof/macmini-node-smoke.json'")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet notebook command uses producer PatchDrop mount" ($notebookText.Contains("--patchdrop-root 'Z:\PatchDrop'") -and $notebookText.Contains("Z:\PatchDrop\external-node-proof\notebook-node-smoke.json")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet macmini command uses producer-local bundle helper" ($macText.Contains("--producer-script '/Users/awx/agent/macmini/mcp/__patch_drop__/producer_bundle.py'") -and -not $macText.Contains("--producer-script '/Volumes/WinSrc/demo-1/src/__patch_drop__/producer_bundle.py'")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet notebook command uses producer-local bundle helper" ($notebookText.Contains("--producer-script 'D:\awx-notebook-worktree\__patch_drop__\producer_bundle.py'") -and -not $notebookText.Contains("--producer-script 'Z:\PatchDrop\producer_bundle.py'")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet Desktop intake keeps Desktop PatchDrop root" ($packet.Json.desktopAuditCommand.Contains($patchDrop) -and -not $packet.Json.desktopAuditCommand.Contains("/Volumes/WinSrc")) "desktopAuditCommand=$($packet.Json.desktopAuditCommand)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopDispatchPacketUsesHostShapedDefaults {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-dispatch-host-defaults-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "DesktopPatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $patchDrop | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch-host-defaults"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch host defaults"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop_dispatch_packet host defaults exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            $slug = "mcp-desktop-dispatch-host-defaults"
            $macPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            $notebookPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "notebook" } | Select-Object -First 1)
            $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
            $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
            $macText = Get-Content -LiteralPath $macCommands -Raw
            $notebookText = Get-Content -LiteralPath $notebookCommands -Raw

            Assert-True "desktop_dispatch_packet default macmini root is POSIX host path" ($macPacket.sourceRoot -match "^/Users/" -and $macText -match "ProducerRoot='/Users/") "packet=$($macPacket | ConvertTo-Json -Depth 20 -Compress) commands=$macText"
            Assert-True "desktop_dispatch_packet default macmini PatchDrop is POSIX host path" ($macPacket.producerPatchdropRoot -match "^/Volumes/" -and $macText.Contains("--patchdrop-root '/Volumes/")) "packet=$($macPacket | ConvertTo-Json -Depth 20 -Compress) commands=$macText"
            Assert-True "desktop_dispatch_packet default macmini command avoids Desktop drive paths" (-not ($macText -match "C:/AbandonWare/worktrees/awx-macmini|C:\\AbandonWare\\worktrees\\awx-macmini|C:/AbandonWare/demo-1/demo-1/src/__patch_drop__|C:\\AbandonWare\\demo-1\\demo-1\\src\\__patch_drop__")) "commands=$macText"
            Assert-True "desktop_dispatch_packet default notebook remains Windows local worktree" ($notebookPacket.sourceRoot -eq "C:/AbandonWare/worktrees/awx-notebook" -and $notebookText -match "C:/AbandonWare/worktrees/awx-notebook") "packet=$($notebookPacket | ConvertTo-Json -Depth 20 -Compress) commands=$notebookText"
            Assert-True "desktop_dispatch_packet default notebook PatchDrop uses mapped exchange path" ($notebookPacket.producerPatchdropRoot -eq "Z:\PatchDrop" -and $notebookText.Contains("--patchdrop-root 'Z:\PatchDrop'")) "packet=$($notebookPacket | ConvertTo-Json -Depth 20 -Compress) commands=$notebookText"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopDispatchPacketWritesPatchDropArtifacts {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-dispatch-write-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $producerKitDir = Join-Path $patchDrop "producer-kit\mcp-desktop-dispatch-write-producer-kit"
    $producerKitManifest = Join-Path $producerKitDir "producer-kit.manifest.json"
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $patchDrop, $producerKitDir | Out-Null
    Set-Content -LiteralPath $producerKitManifest -Encoding UTF8 -Value '{"schemaVersion":"awx.test.producer-kit.v1","files":[]}'
    $producerKitManifestHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $producerKitManifest).Hash.ToLowerInvariant()
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch-write"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch write"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("main/java/example/MacChanged.java")
                notebook = @("main/java/example/NotebookChanged.java")
            }
        }
        Assert-True "desktop_dispatch_packet write exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            $slug = "mcp-desktop-dispatch-write"
            $jsonPath = Join-Path $dispatchDir "$slug-desktop-dispatch.json"
            $macCommands = Join-Path $dispatchDir "$slug-macmini.commands.txt"
            $notebookCommands = Join-Path $dispatchDir "$slug-notebook.commands.txt"
            $desktopCommands = Join-Path $dispatchDir "$slug-desktop-intake.ps1"
            $handoffSummary = Join-Path $dispatchDir "$slug-handoff.md"
            $shaSidecar = Join-Path $dispatchDir "$slug-dispatch.sha256.txt"
            Assert-True "desktop_dispatch_packet write reports artifacts plus SHA sidecar" (@($packet.Json.dispatchArtifacts).Count -eq 6) "artifacts=$($packet.Json.dispatchArtifacts | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "desktop_dispatch_packet write creates dispatch json" (Test-Path -LiteralPath $jsonPath) "missing $jsonPath"
            Assert-True "desktop_dispatch_packet write creates macmini command file" (Test-Path -LiteralPath $macCommands) "missing $macCommands"
            Assert-True "desktop_dispatch_packet write creates notebook command file" (Test-Path -LiteralPath $notebookCommands) "missing $notebookCommands"
            Assert-True "desktop_dispatch_packet write creates Desktop intake file" (Test-Path -LiteralPath $desktopCommands) "missing $desktopCommands"
            Assert-True "desktop_dispatch_packet write creates handoff summary" (Test-Path -LiteralPath $handoffSummary) "missing $handoffSummary"
            Assert-True "desktop_dispatch_packet write creates dispatch SHA sidecar" (Test-Path -LiteralPath $shaSidecar) "missing $shaSidecar"
            $shaSidecarText = if (Test-Path -LiteralPath $shaSidecar) { Get-Content -LiteralPath $shaSidecar -Raw } else { "" }
            Assert-True "desktop_dispatch_packet write sidecar covers dispatch artifacts" (
                $shaSidecarText.Contains("$slug-desktop-dispatch.json") -and
                $shaSidecarText.Contains("$slug-macmini.commands.txt") -and
                $shaSidecarText.Contains("$slug-notebook.commands.txt") -and
                $shaSidecarText.Contains("$slug-desktop-intake.ps1") -and
                $shaSidecarText.Contains("$slug-handoff.md") -and
                -not $shaSidecarText.Contains("$slug-dispatch.sha256.txt")
            ) "sidecar=$shaSidecarText"
            $macText = Get-Content -LiteralPath $macCommands -Raw
            $notebookText = Get-Content -LiteralPath $notebookCommands -Raw
            $desktopText = Get-Content -LiteralPath $desktopCommands -Raw
            $handoffText = if (Test-Path -LiteralPath $handoffSummary) { Get-Content -LiteralPath $handoffSummary -Raw } else { "" }
            Assert-True "desktop_dispatch_packet write handoff summary names producer proof contract" (
                $handoffText.Contains("macmini-node-smoke.json") -and
                $handoffText.Contains("notebook-node-smoke.json") -and
                $handoffText.Contains("producer-handoff.json") -and
                $handoffText.Contains(".patch") -and
                $handoffText.Contains(".report.md") -and
                $handoffText.Contains(".verify.log") -and
                $handoffText.Contains(".sha256.txt") -and
                $handoffText.Contains(".manifest.json") -and
                $handoffText.Contains("desktop-intake.ps1")
            ) "handoff=$handoffText"
            Assert-True "desktop_dispatch_packet write handoff summary names producer-visible command files" (
                $handoffText.Contains("/Volumes/WinSrc/demo-1/demo-1/src/__patch_drop__/dispatch/$slug-macmini.commands.txt") -and
                $handoffText.Contains("Z:\PatchDrop\dispatch\$slug-notebook.commands.txt")
            ) "handoff=$handoffText"
            Assert-True "desktop_dispatch_packet write handoff summary names command hashes and pathspec scope" (
                $handoffText.Contains('command SHA256:') -and
                $handoffText.Contains('pathspec count: `1`') -and
                $handoffText.Contains('main/java/example/MacChanged.java') -and
                $handoffText.Contains('main/java/example/NotebookChanged.java')
            ) "handoff=$handoffText"
            Assert-True "desktop_dispatch_packet write handoff summary names dispatch SHA sidecar guard" (
                $handoffText.Contains("$slug-dispatch.sha256.txt") -and
                $handoffText.Contains("dispatch-command-sha-mismatch") -and
                $handoffText.Contains("producer command file must match the dispatch sidecar")
            ) "handoff=$handoffText"
            Assert-True "desktop_dispatch_packet write handoff summary marks Desktop intake proof-only with janitor apply gate" (
                $handoffText.Contains("proof-only") -and
                $handoffText.Contains("janitor_promote_producer_pending.ps1") -and
                $handoffText.Contains("janitor_apply_one.ps1") -and
                $handoffText.Contains("one accepted bundle at a time") -and
                $handoffText.Contains("active-top-level-exists")
            ) "handoff=$handoffText"
            Assert-True "desktop_dispatch_packet write handoff summary explains readiness split" (
                $handoffText.Contains("localReady") -and
                $handoffText.Contains("completionReady") -and
                $handoffText.Contains("externalEvidenceComplete") -and
                $handoffText.Contains("Desktop-local readiness only") -and
                $handoffText.Contains("false until both producer proof sets pass")
            ) "handoff=$handoffText"
            Assert-True "desktop_dispatch_packet write handoff summary isolates unrelated PatchDrop evidence" (
                $handoffText.Contains("Unrelated PatchDrop evidence") -and
                $handoffText.Contains("supporting-only") -and
                $handoffText.Contains("must not satisfy this topic")
            ) "handoff=$handoffText"
            Assert-True "desktop_dispatch_packet write macmini commands enter source root" ($macText -match ("cd " + [regex]::Escape("'" + ($macRoot -replace '\\', '/') + "'"))) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook commands enter source root" ($notebookText -match ("Push-Location " + [regex]::Escape("'" + $notebookRoot + "'"))) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write macmini commands state local worktree prerequisite" ($macText.Contains("Create or sync a role-local worktree or clone first") -and $macText.Contains("This command file will not create ProducerRoot")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook commands state local worktree prerequisite" ($notebookText.Contains("Create or sync a role-local worktree or clone first") -and $notebookText.Contains("This command file will not create ProducerRoot")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write macmini commands fail closed on missing source root" ($macText.Contains("set -euo pipefail") -and $macText.Contains('[ -d "$ProducerRoot" ]')) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook commands fail closed on missing source root" ($notebookText.Contains('$ErrorActionPreference = ''Stop''') -and $notebookText.Contains('Test-Path -LiteralPath $ProducerRoot -PathType Container')) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write macmini commands can install producer kit before file preflight" ($macText.Contains("INSTALL.macmini.sh") -and $macText.IndexOf("INSTALL.macmini.sh") -lt $macText.IndexOf("for RequiredFile in")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook commands can install producer kit before file preflight" ($notebookText.Contains("INSTALL.notebook.ps1") -and $notebookText.IndexOf("INSTALL.notebook.ps1") -lt $notebookText.IndexOf('$RequiredFiles = @(')) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write macmini commands fail closed on missing producer kit installer" ($macText.Contains("producer-kit-installer-missing") -and $macText.Contains('[ -f "$KitInstall" ] ||') -and -not $macText.Contains('if [ -f "$KitInstall" ]; then')) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook commands fail closed on missing producer kit installer" ($notebookText.Contains("producer-kit-installer-missing") -and $notebookText.Contains('if (-not (Test-Path -LiteralPath $KitInstall -PathType Leaf))') -and -not $notebookText.Contains('if (Test-Path -LiteralPath $KitInstall -PathType Leaf) { powershell')) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write macmini pins producer kit manifest hash" ($macText.Contains("producer-kit-manifest-sha-mismatch") -and $macText.Contains($producerKitManifestHash)) "hash=$producerKitManifestHash macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook pins producer kit manifest hash" ($notebookText.Contains("producer-kit-manifest-sha-mismatch") -and $notebookText.Contains($producerKitManifestHash)) "hash=$producerKitManifestHash notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write macmini refreshes existing producer kit when installer exists" (-not $macText.Contains("MissingRequiredFile")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook refreshes existing producer kit when installer exists" (-not $notebookText.Contains("BootstrapRequiredFiles") -and -not $notebookText.Contains('$MissingRequiredFile')) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write macmini commands preflight runner files" ($macText.Contains("for RequiredFile in") -and $macText.Contains("required tool file missing") -and $macText.Contains("producer_bundle.py")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook commands preflight runner files" ($notebookText.Contains('$RequiredFiles = @(') -and $notebookText.Contains("required tool file missing") -and $notebookText.Contains("producer_bundle.py")) "notebookCommands=$notebookText"
            $macSetupIndex = Get-ToolInvocationIndex $macText "awx_mcp_node_setup.py"
            $macSmokeIndex = Get-ToolInvocationIndex $macText "awx_mcp_node_smoke.py"
            $macJsonIndex = Get-SmokeJsonCheckIndex $macText
            $macHandoffIndex = Get-ToolInvocationIndex $macText "awx_mcp_producer_handoff.py"
            $notebookSetupIndex = Get-ToolInvocationIndex $notebookText "awx_mcp_node_setup.py"
            $notebookSmokeIndex = Get-ToolInvocationIndex $notebookText "awx_mcp_node_smoke.py"
            $notebookJsonIndex = Get-SmokeJsonCheckIndex $notebookText
            $notebookHandoffIndex = Get-ToolInvocationIndex $notebookText "awx_mcp_producer_handoff.py"
            Assert-True "desktop_dispatch_packet write macmini commands run node setup" ($macSetupIndex -ge 0 -and $macSetupIndex -lt $macSmokeIndex) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook commands run node setup" ($notebookSetupIndex -ge 0 -and $notebookSetupIndex -lt $notebookSmokeIndex) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write macmini setup writes audit log" ($macSetupIndex -ge 0 -and $macText -match "--audit-log") "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook setup writes audit log" ($notebookSetupIndex -ge 0 -and $notebookText -match "--audit-log") "notebookCommands=$notebookText"
            $notebookSetupExitIndex = $notebookText.IndexOf('$SetupExit = $LASTEXITCODE', $notebookSetupIndex)
            Assert-True "desktop_dispatch_packet write notebook checks node setup exit before smoke" ($notebookSetupExitIndex -gt $notebookSetupIndex -and $notebookSetupExitIndex -lt $notebookSmokeIndex -and $notebookText.Contains('node-setup-failed') -and $notebookText.Contains('exit $SetupExit')) "notebookCommands=$notebookText"
            $macCanonicalRoot = $Root -replace '\\', '/'
            Assert-True "desktop_dispatch_packet write macmini commands pin Desktop canonical root" (-not ($macText -match "--canonical-root\s+'\.'") -and $macText.Contains("--canonical-root '$macCanonicalRoot'")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook commands pin Desktop canonical root" (-not ($notebookText -match "--canonical-root\s+'\.'") -and $notebookText.Contains("--canonical-root '$Root'")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write macmini validates smoke proof json before handoff" ($macSmokeIndex -ge 0 -and $macJsonIndex -gt $macSmokeIndex -and $macJsonIndex -lt $macHandoffIndex) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write macmini validates smoke proof semantics before handoff" ($macText -match "sourceIsolation" -and $macText -match "directCanonicalSourceEdit" -and $macText -match "gitRootMatchesSourceRoot" -and $macText -match "rawSecretPatternHits" -and $macJsonIndex -ge 0 -and $macJsonIndex -lt $macHandoffIndex) "macCommands=$macText"
            $notebookExitIndex = $notebookText.IndexOf('$LASTEXITCODE', $notebookSmokeIndex)
            Assert-True "desktop_dispatch_packet write notebook checks smoke exit before handoff" ($notebookText.Contains('$LASTEXITCODE') -and $notebookSmokeIndex -ge 0 -and $notebookExitIndex -gt $notebookSmokeIndex -and $notebookExitIndex -lt $notebookHandoffIndex) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook avoids unchecked native pipeline" (-not ($notebookText -match "\|\s*Set-Content")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook validates smoke proof json before handoff" ($notebookSmokeIndex -ge 0 -and $notebookJsonIndex -gt $notebookSmokeIndex -and $notebookJsonIndex -lt $notebookHandoffIndex) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook validates smoke proof semantics before handoff" ($notebookText -match "sourceIsolation" -and $notebookText -match "directCanonicalSourceEdit" -and $notebookText -match "gitRootMatchesSourceRoot" -and $notebookText -match "rawSecretPatternHits" -and $notebookJsonIndex -ge 0 -and $notebookJsonIndex -lt $notebookHandoffIndex) "notebookCommands=$notebookText"
            $macHandoffCheckIndex = $macText.IndexOf("producer-handoff-invalid-proof", $macHandoffIndex)
            $notebookHandoffExitIndex = $notebookText.IndexOf('$HandoffExit', $notebookHandoffIndex)
            $notebookHandoffCheckIndex = $notebookText.IndexOf("producer-handoff-invalid-proof", $notebookHandoffIndex)
            $macHandoffSegment = if ($macHandoffIndex -ge 0) { $macText.Substring($macHandoffIndex) } else { "" }
            $notebookHandoffSegment = if ($notebookHandoffIndex -ge 0) { $notebookText.Substring($notebookHandoffIndex) } else { "" }
            Assert-True "desktop_dispatch_packet write macmini captures handoff proof json" ($macText.Contains("macmini-producer-handoff.json") -and $macHandoffCheckIndex -gt $macHandoffIndex) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write macmini validates handoff proof semantics" ($macHandoffCheckIndex -gt $macHandoffIndex -and $macText.Contains("promotionReady") -and $macText.Contains("rawSecretPatternHits") -and $macText.Contains("diffHeaderCount")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write macmini validates handoff proof source root hash" ($macHandoffSegment.Contains("sourceRootInputHash") -and $macHandoffSegment.Contains("sys.argv[3]")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write macmini passes producer command hash into handoff" ($macText.Contains("ProducerCommandFile=") -and $macText.Contains("ProducerCommandHash=") -and $macHandoffSegment.Contains("--producer-command-hash") -and $macHandoffSegment.Contains('$ProducerCommandHash')) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write macmini fails closed on missing producer command file before hash" ($macText.Contains("producer-command-file-missing") -and $macText.IndexOf("producer-command-file-missing") -gt $macText.IndexOf("ProducerCommandFile=") -and $macText.IndexOf("producer-command-file-missing") -lt $macText.IndexOf("ProducerCommandHash=")) "macCommands=$macText"
            $macDispatchSidecarIndex = $macText.IndexOf("dispatch-sha-sidecar-missing")
            $macDispatchMismatchIndex = $macText.IndexOf("dispatch-command-sha-mismatch")
            Assert-True "desktop_dispatch_packet write macmini validates producer command hash against dispatch sidecar before handoff" ($macDispatchSidecarIndex -gt $macText.IndexOf("ProducerCommandHash=") -and $macDispatchMismatchIndex -gt $macDispatchSidecarIndex -and $macDispatchMismatchIndex -lt $macHandoffIndex -and $macText.Contains("$slug-dispatch.sha256.txt")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write macmini validates handoff producer command hash" ($macHandoffSegment.Contains("producerCommandHash") -and $macHandoffSegment.Contains("sys.argv[4]")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write macmini validates handoff sidecar completeness" ($macHandoffSegment.Contains("sidecarsComplete")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write macmini validates handoff sha verification" ($macHandoffSegment.Contains("shaVerified")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet write notebook captures handoff proof json" ($notebookText.Contains("notebook-producer-handoff.json") -and $notebookHandoffCheckIndex -gt $notebookHandoffIndex) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook checks handoff exit" ($notebookHandoffExitIndex -gt $notebookHandoffIndex -and $notebookHandoffExitIndex -lt $notebookHandoffCheckIndex) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook validates handoff proof semantics" ($notebookHandoffCheckIndex -gt $notebookHandoffIndex -and $notebookText.Contains("promotionReady") -and $notebookText.Contains("rawSecretPatternHits") -and $notebookText.Contains("diffHeaderCount")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook validates handoff proof source root hash" ($notebookHandoffSegment.Contains("sourceRootInputHash") -and $notebookHandoffSegment.Contains("sys.argv[3]")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook passes producer command hash into handoff" ($notebookText.Contains('$ProducerCommandFile =') -and $notebookText.Contains('$ProducerCommandHash =') -and $notebookHandoffSegment.Contains("--producer-command-hash") -and $notebookHandoffSegment.Contains('$ProducerCommandHash')) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook fails closed on missing producer command file before hash" ($notebookText.Contains("producer-command-file-missing") -and $notebookText.IndexOf("producer-command-file-missing") -gt $notebookText.IndexOf('$ProducerCommandFile =') -and $notebookText.IndexOf("producer-command-file-missing") -lt $notebookText.IndexOf('$ProducerCommandHash =')) "notebookCommands=$notebookText"
            $notebookDispatchSidecarIndex = $notebookText.IndexOf("dispatch-sha-sidecar-missing")
            $notebookDispatchMismatchIndex = $notebookText.IndexOf("dispatch-command-sha-mismatch")
            Assert-True "desktop_dispatch_packet write notebook validates producer command hash against dispatch sidecar before handoff" ($notebookDispatchSidecarIndex -gt $notebookText.IndexOf('$ProducerCommandHash =') -and $notebookDispatchMismatchIndex -gt $notebookDispatchSidecarIndex -and $notebookDispatchMismatchIndex -lt $notebookHandoffIndex -and $notebookText.Contains("$slug-dispatch.sha256.txt")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook validates handoff producer command hash" ($notebookHandoffSegment.Contains("producerCommandHash") -and $notebookHandoffSegment.Contains("sys.argv[4]")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook validates handoff sidecar completeness" ($notebookHandoffSegment.Contains("sidecarsComplete")) "notebookCommands=$notebookText"
            Assert-True "desktop_dispatch_packet write notebook validates handoff sha verification" ($notebookHandoffSegment.Contains("shaVerified")) "notebookCommands=$notebookText"
            $desktopAuditLine = @($desktopText -split "`r?`n" | Where-Object { $_ -match "external_evidence_audit" } | Select-Object -First 1)
            Assert-True "desktop_dispatch_packet write audit keeps patchdrop root" ($desktopAuditLine.Count -eq 1 -and $desktopAuditLine[0] -match "patchdrop_root") "desktopCommands=$desktopText"
            Assert-True "desktop_dispatch_packet write audit pins topic" ($desktopAuditLine.Count -eq 1 -and $desktopAuditLine[0] -match "topic" -and $desktopAuditLine[0] -match $slug) "desktopCommands=$desktopText"
            Assert-True "desktop_dispatch_packet write audit requires producer bundles" ($desktopAuditLine.Count -eq 1 -and $desktopAuditLine[0] -match 'require_producer_bundles\s*=\s*\$true') "desktopCommands=$desktopText"
            $desktopRuntimeText = (($desktopText -split "`r?`n" | Where-Object { -not $_.TrimStart().StartsWith("#") }) -join "`n")
            $intakeCommandIndex = $desktopText.IndexOf('external_evidence_intake')
            $intakeExitIndex = $desktopText.IndexOf('$IntakeExit = $LASTEXITCODE')
            $auditCommandIndex = $desktopText.IndexOf('external_evidence_audit')
            $auditExitIndex = $desktopText.IndexOf('$AuditExit = $LASTEXITCODE')
            $completionCommandIndex = $desktopText.IndexOf('awx_mcp_completion_audit.py')
            $completionExitIndex = $desktopText.IndexOf('$CompletionAuditExit = $LASTEXITCODE')
            $intakeSummaryIndex = $desktopText.IndexOf('[AWX][desktop][intake] externalEvidenceComplete=')
            $intakeIncompleteIndex = $desktopText.IndexOf('external-evidence-intake-incomplete')
            $leaseBeginIndex = $desktopRuntimeText.IndexOf('-Action begin')
            $leaseEndIndex = $desktopRuntimeText.IndexOf('-Action end')
            $runtimeIntakeIndex = $desktopRuntimeText.IndexOf('external_evidence_intake')
            $runtimeCompletionIndex = $desktopRuntimeText.IndexOf('awx_mcp_completion_audit.py')
            $desktopDispatchSidecarIndex = $desktopRuntimeText.IndexOf('desktop-dispatch-sha-sidecar-missing')
            $desktopDispatchMismatchIndex = $desktopRuntimeText.IndexOf('desktop-dispatch-sha-mismatch')
            Assert-True "Desktop evidence intake requires no application-source lease" (-not $desktopRuntimeText.Contains('source_edit_session.ps1') -and $leaseBeginIndex -eq -1 -and $leaseEndIndex -eq -1) "source lease leaked into artifact-only wrapper"
            Assert-True "Desktop evidence intake coordinates its selected artifact directory" ($desktopRuntimeText.Contains('.evidence-intake.lock') -and $desktopRuntimeText.Contains('$EvidenceHandle.Dispose()') -and $desktopRuntimeText.Contains('evidence-intake-lock-busy')) "artifact coordination missing"
            Assert-True "Desktop completion binds the selected evidence and topic" ($desktopRuntimeText.Contains('--evidence-dir $EvidenceDir --topic')) "completion evidence path missing"
            Assert-True "desktop_dispatch_packet write Desktop intake validates dispatch SHA sidecar before external intake" ($desktopText.Contains('$DispatchShaSidecar =') -and $desktopText.Contains('$DispatchCoveredFiles = @(') -and $desktopDispatchSidecarIndex -gt $leaseBeginIndex -and $desktopDispatchMismatchIndex -gt $desktopDispatchSidecarIndex -and $desktopDispatchMismatchIndex -lt $runtimeIntakeIndex) "desktopCommands=$desktopText"
            Assert-True "desktop_dispatch_packet write Desktop intake fails closed after evidence intake" ($intakeCommandIndex -ge 0 -and $intakeExitIndex -gt $intakeCommandIndex -and $intakeExitIndex -lt $auditCommandIndex -and $desktopText.Contains('external-evidence-intake-failed') -and $desktopText.Contains('exit $IntakeExit')) "desktopCommands=$desktopText"
            Assert-True "desktop_dispatch_packet write Desktop intake validates intake JSON semantics" ($desktopText.Contains('$IntakeRaw =') -and $desktopText.Contains('$IntakeJson = $IntakeRaw | ConvertFrom-Json') -and $desktopText.Contains('external-evidence-intake-incomplete') -and $desktopText.Contains('externalEvidenceComplete')) "desktopCommands=$desktopText"
            Assert-True "desktop_dispatch_packet write Desktop intake reports evidence summary before incomplete exit" ($intakeSummaryIndex -gt $intakeCommandIndex -and $intakeSummaryIndex -lt $intakeIncompleteIndex -and $desktopText.Contains('producerBundleTopic=') -and $desktopText.Contains('unrelatedPatchDropEvidence.total=')) "desktopCommands=$desktopText"
            Assert-True "desktop_dispatch_packet write Desktop intake fails closed after evidence audit" ($auditCommandIndex -ge 0 -and $auditExitIndex -gt $auditCommandIndex -and $auditExitIndex -lt $completionCommandIndex -and $desktopText.Contains('external-evidence-audit-failed') -and $desktopText.Contains('exit $AuditExit')) "desktopCommands=$desktopText"
            Assert-True "desktop_dispatch_packet write Desktop intake validates audit JSON semantics" ($desktopText.Contains('$AuditRaw =') -and $desktopText.Contains('$AuditJson = $AuditRaw | ConvertFrom-Json') -and $desktopText.Contains('external-evidence-audit-incomplete') -and $desktopText.Contains('externalEvidenceComplete')) "desktopCommands=$desktopText"
            Assert-True "desktop_dispatch_packet write Desktop intake fails closed after completion audit" ($completionCommandIndex -ge 0 -and $completionExitIndex -gt $completionCommandIndex -and $desktopText.Contains('completion-audit-failed') -and $desktopText.Contains('exit $CompletionAuditExit')) "desktopCommands=$desktopText"
            $dispatchJson = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json
            Assert-True "desktop_dispatch_packet write json has schema version" ($dispatchJson.schemaVersion -eq "awx.mcp.desktop_dispatch_packet.v1") "json=$($dispatchJson | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet write json counts artifacts plus SHA sidecar" ([int]$dispatchJson.artifactCount -eq 6) "json=$($dispatchJson | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet write json indexes dispatch SHA sidecar" (
                $dispatchJson.dispatchArtifactIndex.dispatchSha256Sidecar -match "dispatch\.sha256\.txt" -and
                @($dispatchJson.dispatchArtifactIndex.sha256CoveredArtifacts).Count -eq 5
            ) "json=$($dispatchJson | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet write json indexes producer command files" (@($dispatchJson.dispatchArtifactIndex.producerCommands).Count -eq 2 -and @($dispatchJson.dispatchArtifactIndex.producerCommands | Where-Object { $_.nodeRole -eq "macmini" -and $_.commandFile -match "macmini.commands.txt" }).Count -eq 1 -and @($dispatchJson.dispatchArtifactIndex.producerCommands | Where-Object { $_.nodeRole -eq "notebook" -and $_.commandFile -match "notebook.commands.txt" }).Count -eq 1) "json=$($dispatchJson | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet write json indexes producer kit manifest hash" (@($dispatchJson.dispatchArtifactIndex.producerCommands | Where-Object { $_.producerKitManifestHash -eq $producerKitManifestHash }).Count -eq 2) "hash=$producerKitManifestHash json=$($dispatchJson | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet write json exposes producer-visible command files" (
                @($dispatchJson.nextActions | Where-Object { $_.nodeRole -eq "macmini" -and $_.producerCommandFile -eq "/Volumes/WinSrc/demo-1/demo-1/src/__patch_drop__/dispatch/$slug-macmini.commands.txt" }).Count -eq 1 -and
                @($dispatchJson.nextActions | Where-Object { $_.nodeRole -eq "notebook" -and $_.producerCommandFile -eq "Z:\PatchDrop\dispatch\$slug-notebook.commands.txt" }).Count -eq 1
            ) "json=$($dispatchJson | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet write json exposes next actions" (@($dispatchJson.nextActions | Where-Object { $_.action -eq "run-producer-command-file" }).Count -eq 2 -and @($dispatchJson.nextActions | Where-Object { $_.action -eq "run-desktop-intake-after-producer-proof" }).Count -eq 1) "json=$($dispatchJson | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet write response exposes next actions" (@($packet.Json.nextActions | Where-Object { $_.action -eq "run-producer-command-file" }).Count -eq 2 -and @($packet.Json.nextActions | Where-Object { $_.action -eq "run-desktop-intake-after-producer-proof" }).Count -eq 1) "output=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet write json keeps Desktop proof pending" ($dispatchJson.desktopFinalProof -eq "evidence_needed") "json=$($dispatchJson | ConvertTo-Json -Depth 20 -Compress)"
            $outsideDispatch = @($packet.Json.dispatchArtifacts | Where-Object { -not ([string]$_).StartsWith($dispatchDir, [System.StringComparison]::OrdinalIgnoreCase) })
            Assert-True "desktop_dispatch_packet write keeps artifacts under PatchDrop" ($outsideDispatch.Count -eq 0) "outside=$($outsideDispatch -join ',') artifacts=$($packet.Json.dispatchArtifacts | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "desktop_dispatch_packet write does not emit secret-like values" (-not (Test-HasSecretLikeValue $packet.Raw)) "raw=$($packet.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopDispatchPacketSupportsRoleSpecificPathspecs {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-dispatch-role-pathspec-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $patchDrop | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch-role-pathspec"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch role pathspec"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py", "main/resources/mcp/awx-control-tower-tools.json")
                notebook = @("scripts/awx_mcp_toolbox_tests.ps1", "scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop_dispatch_packet role pathspec exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            $slug = "mcp-desktop-dispatch-role-pathspec"
            $macPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            $notebookPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "notebook" } | Select-Object -First 1)
            $macText = Get-Content -LiteralPath (Join-Path $dispatchDir "$slug-macmini.commands.txt") -Raw
            $notebookText = Get-Content -LiteralPath (Join-Path $dispatchDir "$slug-notebook.commands.txt") -Raw
            Assert-True "desktop_dispatch_packet role pathspec counts macmini paths" ([int]$macPacket.pathspecCount -eq 2) "packet=$($macPacket | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet role pathspec counts notebook paths" ([int]$notebookPacket.pathspecCount -eq 2) "packet=$($notebookPacket | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_dispatch_packet role pathspec sends macmini-only files" ($macText.Contains("scripts/awx_mcp_toolbox.py") -and $macText.Contains("main/resources/mcp/awx-control-tower-tools.json") -and -not $macText.Contains("scripts/awx_mcp_toolbox_tests.ps1")) "macCommands=$macText"
            Assert-True "desktop_dispatch_packet role pathspec sends notebook-only files" ($notebookText.Contains("scripts/awx_mcp_toolbox_tests.ps1") -and $notebookText.Contains("scripts/awx_mcp_completion_audit.py") -and -not $notebookText.Contains("main/resources/mcp/awx-control-tower-tools.json")) "notebookCommands=$notebookText"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopDispatchPacketRejectsOverlappingRolePathspecs {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-dispatch-overlap-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $patchDrop | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch-reject-overlap"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch reject overlap"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py", "main/resources/mcp/awx-control-tower-tools.json")
                notebook = @("scripts/awx_mcp_toolbox.py", "scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop_dispatch_packet rejects overlapping role pathspec exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            $dispatchFiles = @()
            if (Test-Path -LiteralPath $dispatchDir) {
                $dispatchFiles = @(Get-ChildItem -LiteralPath $dispatchDir -File -ErrorAction SilentlyContinue)
            }
            Assert-True "desktop_dispatch_packet rejects overlapping role pathspec ok false" ($packet.Json.ok -eq $false -and $packet.Json.failReason -eq "pathspec-overlap") "packet=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet names overlapping role pathspec" ((@($packet.Json.pathspecOverlap) -contains "scripts/awx_mcp_toolbox.py") -and ((@($packet.Json.evidence_needed) -join "|") -match "pathspec-overlap")) "packet=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet does not write overlap dispatch artifacts" ($dispatchFiles.Count -eq 0) "files=$($dispatchFiles.Name -join ',') packet=$($packet.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopDispatchPacketRejectsSharedPathspecForMultipleRoles {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-dispatch-shared-pathspec-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $patchDrop | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch-reject-shared-pathspec"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch reject shared pathspec"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            target_roles = @("macmini", "notebook")
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            pathspec = @("scripts/awx_mcp_toolbox.py")
        }
        Assert-True "desktop_dispatch_packet rejects shared pathspec exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            $dispatchFiles = @()
            if (Test-Path -LiteralPath $dispatchDir) {
                $dispatchFiles = @(Get-ChildItem -LiteralPath $dispatchDir -File -ErrorAction SilentlyContinue)
            }
            Assert-True "desktop_dispatch_packet rejects shared pathspec ok false" ($packet.Json.ok -eq $false -and $packet.Json.failReason -eq "pathspec-overlap") "packet=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet reports shared pathspec overlap" ((@($packet.Json.pathspecOverlap) -contains "scripts/awx_mcp_toolbox.py") -and ((@($packet.Json.evidence_needed) -join "|") -match "role_pathspec")) "packet=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet does not write shared pathspec artifacts" ($dispatchFiles.Count -eq 0) "files=$($dispatchFiles.Name -join ',') packet=$($packet.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopDispatchPacketRequiresRolePathspecsForProducerBundles {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-dispatch-require-pathspec-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $patchDrop | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch-require-pathspec"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch require pathspec"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            require_producer_bundles = $true
            target_roles = @("macmini", "notebook")
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
        }
        Assert-True "desktop_dispatch_packet missing role pathspec exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            $dispatchFiles = @()
            if (Test-Path -LiteralPath $dispatchDir) {
                $dispatchFiles = @(Get-ChildItem -LiteralPath $dispatchDir -File -ErrorAction SilentlyContinue)
            }
            Assert-True "desktop_dispatch_packet missing role pathspec ok false" ($packet.Json.ok -eq $false -and $packet.Json.failReason -eq "pathspec-required") "packet=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet names missing role pathspecs" ((@($packet.Json.evidence_needed) -join "|") -match "role_pathspec" -and (@($packet.Json.missingPathspecRoles) -contains "macmini") -and (@($packet.Json.missingPathspecRoles) -contains "notebook")) "packet=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet missing role pathspec writes no artifacts" ($dispatchFiles.Count -eq 0) "files=$($dispatchFiles.Name -join ',') packet=$($packet.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopDispatchPacketPublishesPatchOnlyContract {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-dispatch-patch-contract-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $patchDrop | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch-patch-only-contract"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch patch only contract"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            write_dispatch = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_toolbox_tests.ps1")
            }
        }
        Assert-True "desktop_dispatch_packet patch-only contract exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            $macPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            $notebookPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "notebook" } | Select-Object -First 1)
            foreach ($producerPacket in @($macPacket, $notebookPacket)) {
                $contract = $producerPacket.handoffContract
                $required = @($contract.requiredArtifacts)
                Assert-True "desktop_dispatch_packet patch-only contract exists for $($producerPacket.nodeRole)" ($null -ne $contract) "packet=$($producerPacket | ConvertTo-Json -Depth 20 -Compress)"
                Assert-True "desktop_dispatch_packet patch-only contract forbids canonical writes for $($producerPacket.nodeRole)" ($contract.directCanonicalSourceEditAllowed -eq $false -and $contract.desktopFinalProof -eq "evidence_needed") "contract=$($contract | ConvertTo-Json -Depth 20 -Compress)"
                Assert-True "desktop_dispatch_packet patch-only contract requires unified diff sidecars for $($producerPacket.nodeRole)" ($contract.outputMode -eq "patchdrop-unified-diff-sidecars" -and $required -contains ".patch" -and $required -contains ".report.md" -and $required -contains ".verify.log" -and $required -contains ".sha256.txt" -and $required -contains ".manifest.json" -and $required -contains "pendingNotice") "contract=$($contract | ConvertTo-Json -Depth 20 -Compress)"
            }
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopDispatchPacketFlagsMissingDefaultProducerRoots {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-dispatch-missing-root-" + [guid]::NewGuid().ToString("N"))
    $patchDrop = Join-Path $tmp "PatchDrop"
    New-Item -ItemType Directory -Force -Path $patchDrop | Out-Null
    try {
        $packet = Invoke-ToolboxJson "desktop_dispatch_packet" @{
            requestId = "test-desktop-dispatch-missing-roots"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop dispatch missing roots"
            canonical_root = $Root
            patchdrop_root = $patchDrop
            target_roles = @("macmini", "notebook")
            role_pathspec = @{
                macmini = @("main/java/example/MacChanged.java")
                notebook = @("main/java/example/NotebookChanged.java")
            }
        }
        Assert-True "desktop_dispatch_packet missing-root test exits zero" ($packet.ExitCode -eq 0) "output=$($packet.Raw)"
        if ($packet.Json) {
            $macPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "macmini" } | Select-Object -First 1)
            $notebookPacket = @($packet.Json.packets | Where-Object { $_.nodeRole -eq "notebook" } | Select-Object -First 1)
            Assert-True "desktop_dispatch_packet flags missing default macmini root" ($macPacket.desktopSourceRootExists -eq $false) "packets=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet flags missing default notebook root" ($notebookPacket.desktopSourceRootExists -eq $false) "packets=$($packet.Raw)"
            Assert-True "desktop_dispatch_packet evidence names missing producer roots" ((@($packet.Json.evidence_needed) -join "|") -match "producer source root not visible on Desktop") "evidence_needed=$(@($packet.Json.evidence_needed) -join '|')"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopControlLoopCombinesDispatchAndExternalAudit {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-loop-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $notebookRoot = Join-Path $tmp "notebook-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $evidenceDir = Join-Path $tmp "evidence"
    New-Item -ItemType Directory -Force -Path $macRoot, $notebookRoot, $patchDrop, $evidenceDir | Out-Null
    try {
        $loop = Invoke-ToolboxJson "desktop_control_loop" @{
            requestId = "test-desktop-control-loop"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop control loop"
            root = $Root
            canonical_root = $Root
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            evidence_dir = $evidenceDir
            write_dispatch = $true
            write_producer_kit = $true
            producer_roots = @{
                macmini = $macRoot
                notebook = $notebookRoot
            }
            role_pathspec = @{
                macmini = @("scripts/awx_mcp_toolbox.py")
                notebook = @("scripts/awx_mcp_completion_audit.py")
            }
        }
        Assert-True "desktop_control_loop exits zero" ($loop.ExitCode -eq 0) "output=$($loop.Raw)"
        if ($loop.Json) {
            Assert-True "desktop_control_loop records desktop role" ($loop.Json.nodeRole -eq "desktop") "json=$($loop.Raw)"
            Assert-True "desktop_control_loop marks Desktop local readiness separately" ($loop.Json.localReady -eq $true) "json=$($loop.Raw)"
            Assert-True "desktop_control_loop keeps distributed completion pending" ($loop.Json.completionReady -eq $false) "json=$($loop.Raw)"
            Assert-True "desktop_control_loop keeps Desktop proof pending" ($loop.Json.desktopFinalProof -eq "evidence_needed") "json=$($loop.Raw)"
            Assert-True "desktop_control_loop runs source scan" ($loop.Json.sourceScan.secretPatternHits -ge 0 -and $loop.Json.sourceScan.activeSourceSets) "sourceScan=$($loop.Json.sourceScan | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "desktop_control_loop writes dispatch artifacts plus SHA sidecar" (@($loop.Json.dispatch.dispatchArtifacts).Count -eq 6 -and (Test-Path -LiteralPath (Join-Path $dispatchDir "mcp-desktop-control-loop-desktop-dispatch.json")) -and (Test-Path -LiteralPath (Join-Path $dispatchDir "mcp-desktop-control-loop-dispatch.sha256.txt"))) "dispatch=$($loop.Json.dispatch | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "desktop_control_loop writes producer kit when requested" ($loop.Json.producerKit.ok -eq $true -and (Test-Path -LiteralPath $loop.Json.producerKit.manifestPath)) "producerKit=$($loop.Json.producerKit | ConvertTo-Json -Depth 10 -Compress)"
            $producerKitManifestHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $loop.Json.producerKit.manifestPath).Hash.ToLowerInvariant()
            $producerCommandKitHashes = @($loop.Json.dispatch.dispatchArtifactIndex.producerCommands | ForEach-Object { $_.producerKitManifestHash })
            Assert-True "desktop_control_loop dispatch references current producer kit manifest hash" ($producerCommandKitHashes.Count -eq 2 -and (($producerCommandKitHashes | Where-Object { $_ -eq $producerKitManifestHash }).Count -eq 2)) "actual=$producerKitManifestHash commandHashes=$($producerCommandKitHashes -join ',')"
            Assert-True "desktop_control_loop audits external evidence" ($loop.Json.externalEvidence.externalEvidenceComplete -eq $false -and (($loop.Json.externalEvidence.evidence_needed -join ' ') -match "node smoke|producer bundle")) "external=$($loop.Json.externalEvidence | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_control_loop surfaces dispatch integrity top-level" ($loop.Json.dispatchIntegrity.ok -eq $true -and $loop.Json.dispatchIntegrity.sidecarValid -eq $true -and [int]$loop.Json.dispatchIntegrity.coveredArtifactCount -eq 5) "dispatchIntegrity=$($loop.Json.dispatchIntegrity | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "desktop_control_loop surfaces unrelated PatchDrop evidence top-level" ($null -ne $loop.Json.unrelatedPatchDropEvidence -and [int]$loop.Json.unrelatedPatchDropEvidence.total -eq 0) "unrelated=$($loop.Json.unrelatedPatchDropEvidence | ConvertTo-Json -Depth 10 -Compress)"
            Assert-True "desktop_control_loop returns producer next actions" ((@($loop.Json.nextActions | Where-Object { $_.action -eq "run-producer-command-file" }).Count) -eq 2) "nextActions=$($loop.Json.nextActions | ConvertTo-Json -Depth 20 -Compress)"
            Assert-True "desktop_control_loop does not emit secret-like values" (-not (Test-HasSecretLikeValue $loop.Raw)) "raw=$($loop.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-DesktopControlLoopResolvesRelativeCanonicalRootForDispatch {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-desktop-loop-relative-root-" + [guid]::NewGuid().ToString("N"))
    $macRoot = Join-Path $tmp "macmini-worktree"
    $patchDrop = Join-Path $tmp "PatchDrop"
    $dispatchDir = Join-Path $patchDrop "dispatch"
    $evidenceDir = Join-Path $tmp "evidence"
    New-Item -ItemType Directory -Force -Path $macRoot, $patchDrop, $evidenceDir | Out-Null
    try {
        $loop = Invoke-ToolboxJson "desktop_control_loop" @{
            requestId = "test-desktop-control-loop-relative-root"
            sessionId = "session-a"
            nodeRole = "desktop"
            topic = "mcp desktop control loop relative root"
            root = "."
            patchdrop_root = $patchDrop
            dispatch_dir = $dispatchDir
            evidence_dir = $evidenceDir
            write_dispatch = $true
            target_roles = @("macmini")
            producer_roots = @{
                macmini = $macRoot
            }
            pathspec = @("scripts/awx_mcp_toolbox.py")
        }
        Assert-True "desktop_control_loop relative-root exits zero" ($loop.ExitCode -eq 0) "output=$($loop.Raw)"
        if ($loop.Json) {
            $commandsPath = Join-Path $dispatchDir "mcp-desktop-control-loop-relative-root-macmini.commands.txt"
            $commands = Get-Content -LiteralPath $commandsPath -Raw
            Assert-True "desktop_control_loop relative-root avoids dot canonical root" (-not ($commands -match "--canonical-root '\.'")) "commands=$commands"
            Assert-True "desktop_control_loop relative-root resolves Desktop canonical root" ($commands -match "C:/AbandonWare/demo-1/demo-1/src|C:\\AbandonWare\\demo-1\\demo-1\\src") "commands=$commands"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-SourceScanRedactsSecretLikeContent {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ("awx-mcp-source-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path (Join-Path $tmp "main\java\a"), (Join-Path $tmp "main\resources") | Out-Null
    try {
        Set-Content -LiteralPath (Join-Path $tmp "main\java\a\App.java") -Encoding UTF8 -Value "package a; public class App {}"
        Set-Content -LiteralPath (Join-Path $tmp "main\resources\application.yml") -Encoding UTF8 -Value "naver:`n  keys: `${NAVER_KEYS:}`n"
        Set-Content -LiteralPath (Join-Path $tmp "apikey.ps1") -Encoding UTF8 -Value @'
$env:NAVER_CLIENT_ID = "local-client-id-value"
$env:NAVER_CLIENT_SECRET = "local-client-secret-value"
'@
        Set-Content -LiteralPath (Join-Path $tmp "apikey.txt") -Encoding UTF8 -Value @'
NAVER_KEYS=local-naver-keys-value
'@
        $scan = Invoke-ToolboxJson "source_scan" @{
            requestId = "test-source"
            sessionId = "session-a"
            nodeRole = "macmini"
            root = $tmp
        }
        Assert-True "source_scan exits zero" ($scan.ExitCode -eq 0) "output=$($scan.Raw)"
        if ($scan.Json) {
            Assert-True "source_scan counts java files" ([int]$scan.Json.activeSourceSets.mainJava.fileCount -eq 1) "scan=$($scan.Raw)"
            Assert-True "source_scan reports env names only" (@($scan.Json.secretEnvRefs) -contains "NAVER_KEYS") "envRefs=$($scan.Json.secretEnvRefs -join ',')"
            Assert-True "source_scan does not leak yaml content" (-not $scan.Raw.Contains('${NAVER_KEYS:}')) "raw=$($scan.Raw)"
            Assert-True "source_scan reports apikey env names only" (@($scan.Json.apikeyEnvRefs) -contains "NAVER_CLIENT_ID" -and @($scan.Json.apikeyEnvRefs) -contains "NAVER_CLIENT_SECRET" -and @($scan.Json.apikeyEnvRefs) -contains "NAVER_KEYS") "apikeyEnvRefs=$($scan.Json.apikeyEnvRefs -join ',')"
            Assert-True "source_scan does not leak apikey values" (-not $scan.Raw.Contains("local-client-id-value") -and -not $scan.Raw.Contains("local-client-secret-value") -and -not $scan.Raw.Contains("local-naver-keys-value")) "raw=$($scan.Raw)"
        }
    } finally {
        Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Test-AgentDbSnapshotReportsSubsystemPersistenceGaps {
    Assert-True "agent_db_snapshot toolbox exists" (Test-Path -LiteralPath $Toolbox) "missing $Toolbox"
    if (-not (Test-Path -LiteralPath $Toolbox)) { return }

    $snapshot = Invoke-ToolboxJson "agent_db_snapshot" @{
        nodeRole = "desktop"
        root = $Root
        base_url = "http://127.0.0.1:9"
        timeout_sec = 1
    }

    Assert-True "agent_db_snapshot fallback exits zero" ($snapshot.ExitCode -eq 0) "raw=$($snapshot.Raw)"
    if ($snapshot.ExitCode -ne 0 -or $null -eq $snapshot.Json) { return }

    $persistence = $snapshot.Json.localFallback.subsystemPersistence
    Assert-True "agent_db_snapshot reports subsystem persistence" ($null -ne $persistence) "localFallback=$($snapshot.Json.localFallback | ConvertTo-Json -Depth 20 -Compress)"
    if ($null -eq $persistence) { return }

    Assert-True "agent_db_snapshot reports CFVM snapshot-backed ring buffer" (
        $persistence.cfvmRawMatrixBuffer.storageMode -eq "process_local_ring_buffer_with_snapshot_restore" -and
        $persistence.cfvmRawMatrixBuffer.durable -eq $false -and
        $persistence.cfvmRawMatrixBuffer.repositoryBacked -eq $true -and
        $persistence.cfvmRawMatrixBuffer.durableCheckpoint -eq $true -and
        $persistence.cfvmRawMatrixBuffer.gapClass -eq "snapshot-backed-process-buffer"
    ) "cfvm=$($persistence.cfvmRawMatrixBuffer | ConvertTo-Json -Compress)"
    Assert-True "agent_db_snapshot reports TraceStore request-local storage" (
        $persistence.traceStore.storageMode -eq "thread_local_request_trace" -and
        $persistence.traceStore.durable -eq $false
    ) "traceStore=$($persistence.traceStore | ConvertTo-Json -Compress)"
    Assert-True "agent_db_snapshot reports ArtPlate evolver DB log seam" (
        $persistence.artPlateEvolver.storageMode -eq "repository_backed_evolution_log" -and
        $persistence.artPlateEvolver.repositoryBacked -eq $true -and
        $persistence.artPlateEvolver.durableCheckpoint -eq $true -and
        $persistence.artPlateEvolver.gapClass -eq "db-backed-evolution-log"
    ) "artPlate=$($persistence.artPlateEvolver | ConvertTo-Json -Compress)"
    Assert-True "agent_db_snapshot reports canonical ExtremeZ surface" (
        [int]$persistence.extremeZ.aliasCount -ge 1 -and
        $persistence.extremeZ.canonicalPresent -eq $true -and
        @($persistence.extremeZ.paths) -contains "main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java" -and
        @("single-canonical", "multi-package-alias-surface") -contains $persistence.extremeZ.gapClass
    ) "extremeZ=$($persistence.extremeZ | ConvertTo-Json -Depth 10 -Compress)"
}

function Test-HarmonyScanRecognizesContextPropagationBreadcrumb {
    Assert-True "harmony_scan toolbox exists" (Test-Path -LiteralPath $Toolbox) "missing $Toolbox"
    if (-not (Test-Path -LiteralPath $Toolbox)) { return }

    $scan = Invoke-ToolboxJson "harmony_scan" @{
        nodeRole = "desktop"
        root = $Root
    }

    Assert-True "harmony_scan exits zero" ($scan.ExitCode -eq 0) "raw=$($scan.Raw)"
    if ($scan.ExitCode -ne 0 -or $null -eq $scan.Json) { return }

    $contextSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/boot/exec/ContextPropagatingExecutorService.java"
    })
    Assert-True "harmony_scan recognizes context propagation breadcrumb helper" (
        $contextSamples.Count -eq 0
    ) "samples=$($contextSamples | ConvertTo-Json -Depth 10 -Compress)"

    $postProcessorSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/boot/exec/ExecutorServiceContextPropagationPostProcessor.java"
    })
    Assert-True "harmony_scan recognizes executor post-processor breadcrumb helpers" (
        $postProcessorSamples.Count -eq 0
    ) "samples=$($postProcessorSamples | ConvertTo-Json -Depth 10 -Compress)"

    $debugPortSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/autoconfig/NovaDebugPortAutoConfiguration.java"
    })
    Assert-True "harmony_scan recognizes nova debug port breadcrumb helpers" (
        $debugPortSamples.Count -eq 0
    ) "samples=$($debugPortSamples | ConvertTo-Json -Depth 10 -Compress)"

    $cancelShieldSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/boot/exec/CancelShieldExecutorService.java"
    })
    Assert-True "harmony_scan recognizes cancel shield rethrow aggregation and telemetry breadcrumbs" (
        $cancelShieldSamples.Count -eq 0
    ) "samples=$($cancelShieldSamples | ConvertTo-Json -Depth 10 -Compress)"

    $zombieSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/boot/exec/zombie/ZombieBreederContainmentAspect.java"
    })
    Assert-True "harmony_scan recognizes zombie containment redacted aspect error helper" (
        $zombieSamples.Count -eq 0
    ) "samples=$($zombieSamples | ConvertTo-Json -Depth 10 -Compress)"

    $noiseFilterSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/boot/log/NovaNoiseTurboFilter.java"
    })
    Assert-True "harmony_scan recognizes nova noise filter fallback breadcrumbs" (
        $noiseFilterSamples.Count -eq 0
    ) "samples=$($noiseFilterSamples | ConvertTo-Json -Depth 10 -Compress)"

    $reactorDroppedSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/boot/reactor/NovaReactorDroppedErrorHook.java"
    })
    Assert-True "harmony_scan recognizes reactor dropped-error breadcrumbs" (
        $reactorDroppedSamples.Count -eq 0
    ) "samples=$($reactorDroppedSamples | ConvertTo-Json -Depth 10 -Compress)"

    $novaAnalyzeSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/adapters/NovaAnalyzeWebSearchRetriever.java"
    })
    Assert-True "harmony_scan recognizes nova analyze retriever fail-soft breadcrumbs" (
        $novaAnalyzeSamples.Count -eq 0
    ) "samples=$($novaAnalyzeSamples | ConvertTo-Json -Depth 10 -Compress)"

    $anchorNarrowerSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/anchor/AnchorNarrower.java"
    })
    Assert-True "harmony_scan recognizes anchor narrower trace fallback breadcrumbs" (
        $anchorNarrowerSamples.Count -eq 0
    ) "samples=$($anchorNarrowerSamples | ConvertTo-Json -Depth 10 -Compress)"

    $fastBailSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/aop/ChatWorkflowFastBailoutMinHitsPostProcessor.java"
    })
    Assert-True "harmony_scan recognizes chat workflow fast-bail post-processor breadcrumbs" (
        $fastBailSamples.Count -eq 0
    ) "samples=$($fastBailSamples | ConvertTo-Json -Depth 10 -Compress)"

    $extremeZTraceFailureSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java" -and
        @(345, 353, 363, 404, 509, 542, 564, 853, 1073, 1085, 1320) -contains [int]$_.line
    })
    Assert-True "harmony_scan recognizes ExtremeZ traceFailure breadcrumb helper" (
        $extremeZTraceFailureSamples.Count -eq 0
    ) "samples=$($extremeZTraceFailureSamples | ConvertTo-Json -Depth 10 -Compress)"

    $hybridSource = Join-Path $Root "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java"
    $hybridHelperLines = @{}
    if (Test-Path -LiteralPath $hybridSource) {
        $lineNo = 0
        Get-Content -LiteralPath $hybridSource | ForEach-Object {
            $lineNo += 1
            if ($_ -match "WebFailSoftTraceSuppressions\.trace") {
                $hybridHelperLines[[int]$lineNo] = $true
            }
        }
    }
    $hybridHelperSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java" -and
        $hybridHelperLines.ContainsKey([int]$_.line)
    })
    Assert-True "harmony_scan recognizes WebFailSoftTraceSuppressions breadcrumb helper" (
        $hybridHelperSamples.Count -eq 0
    ) "samples=$($hybridHelperSamples | ConvertTo-Json -Depth 10 -Compress)"

    $chunkEnvelopeSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/chunk/ChunkEnvelope.java"
    })
    Assert-True "harmony_scan recognizes chunk envelope parse-skipped helper" (
        $chunkEnvelopeSamples.Count -eq 0
    ) "samples=$($chunkEnvelopeSamples | ConvertTo-Json -Depth 10 -Compress)"

    $webSoakRunFailureSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeController.java" -and
        [int]$_.line -ge 95 -and [int]$_.line -le 120
    })
    Assert-True "harmony_scan recognizes WebSoak KPI controller run failure breadcrumb helper" (
        $webSoakRunFailureSamples.Count -eq 0
    ) "samples=$($webSoakRunFailureSamples | ConvertTo-Json -Depth 10 -Compress)"

    $webSoakUiSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeController.java" -and
        [int]$_.line -ge 640 -and [int]$_.line -le 700
    })
    Assert-True "harmony_scan recognizes embedded WebSoak UI console debug breadcrumbs" (
        $webSoakUiSamples.Count -eq 0
    ) "samples=$($webSoakUiSamples | ConvertTo-Json -Depth 10 -Compress)"

    $webSoakRunOnceSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/probe/WebSoakKpiProbeService.java" -and
        [int]$_.line -ge 175 -and [int]$_.line -le 190
    })
    Assert-True "harmony_scan recognizes WebSoak KPI service runOnce failure breadcrumb helper" (
        $webSoakRunOnceSamples.Count -eq 0
    ) "samples=$($webSoakRunOnceSamples | ConvertTo-Json -Depth 10 -Compress)"

    $degradedStorageSource = Join-Path $Root "main/java/ai/abandonware/nova/orch/storage/FileDegradedStorage.java"
    $degradedStorageHelperLines = @{}
    if (Test-Path -LiteralPath $degradedStorageSource) {
        $lineNo = 0
        Get-Content -LiteralPath $degradedStorageSource | ForEach-Object {
            $lineNo += 1
            if ($_ -match "DegradedStorageTraceSuppressions\.trace") {
                $degradedStorageHelperLines[[int]$lineNo] = $true
            }
        }
    }
    $degradedStorageHelperSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/storage/FileDegradedStorage.java" -and
        $degradedStorageHelperLines.ContainsKey([int]$_.line)
    })
    Assert-True "harmony_scan recognizes degraded storage suppression helper" (
        $degradedStorageHelperSamples.Count -eq 0
    ) "samples=$($degradedStorageHelperSamples | ConvertTo-Json -Depth 10 -Compress)"

    $webFailSoftFailureSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java" -and
        @(202, 689) -contains [int]$_.line
    })
    Assert-True "harmony_scan recognizes WebFailSoftFailureTrace breadcrumbs" (
        $webFailSoftFailureSamples.Count -eq 0
    ) "samples=$($webFailSoftFailureSamples | ConvertTo-Json -Depth 10 -Compress)"

    $acmeRankingSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java" -and
        [int]$_.line -eq 51
    })
    Assert-True "harmony_scan recognizes local traceRankingSuppressed breadcrumb helper" (
        $acmeRankingSamples.Count -eq 0
    ) "samples=$($acmeRankingSamples | ConvertTo-Json -Depth 10 -Compress)"

    $riskNumberSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/com/example/lms/risk/TopKShrinkerAspect.java" -and
        [int]$_.line -eq 112
    })
    Assert-True "harmony_scan recognizes local traceInvalidRiskNumber breadcrumb helper" (
        $riskNumberSamples.Count -eq 0
    ) "samples=$($riskNumberSamples | ConvertTo-Json -Depth 10 -Compress)"

    $orchestratorSuppressorSamples = @($scan.Json.samples.catchWithoutBreadcrumb | Where-Object {
        $_.path -eq "main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java" -and
        @(2024, 2033, 2595, 3443) -contains [int]$_.line
    })
    Assert-True "harmony_scan recognizes orchestrator invalid-number suppressor breadcrumb helper" (
        $orchestratorSuppressorSamples.Count -eq 0
    ) "samples=$($orchestratorSuppressorSamples | ConvertTo-Json -Depth 10 -Compress)"
}

try {
    if ($Suite -ne 'ProducerKit') { Remove-LiveDispatchFixtureArtifacts }
    Test-SecretLikePatternIncludesSupabaseKeys
    Assert-True "toolbox script exists" (Test-Path -LiteralPath $Toolbox) "missing $Toolbox"
    if ($Suite -eq 'ProducerKit') {
        Test-ProducerKitExportWritesPatchDropKit
    } elseif ($Suite -eq "SecretPattern") {
        # The shared secret-pattern test above is the complete focused suite.
    } elseif ($Suite -eq "ArchiveLoop") {
        if (Test-Path -LiteralPath $Toolbox) {
            Test-ArchiveSearchTwoPassAndEvidenceNeeded
            Test-ArchiveRestoreWritesAuditAndChecksums
            Test-ArchiveRestoreBlocksProducerCanonicalTarget
            Test-ArchiveRestoreRequiresAuditLogForRestoreMode
        }
    } elseif ($Suite -eq "CompletionAudit") {
        Test-CompletionAuditCoversControlTowerObjective
        Test-CompletionAuditProofFindersFailSoftOnUnreadablePaths
        Invoke-WithCompletionAuditTestDispatch { Test-CompletionAuditRejectsAmbiguousDispatchPackets }
        Test-CompletionAuditIgnoresJanitorFixtureDispatchWhenOperationalPacketExists
        Invoke-WithCompletionAuditTestDispatch {
            Test-CompletionAuditRejectsDispatchWithoutGitRootPreflight
            Test-CompletionAuditRejectsDispatchWithoutDesktopIntakeJsonSemantics
            Test-CompletionAuditRejectsDispatchWithoutProducerRootGuard
            Test-CompletionAuditRejectsDispatchWithoutHandoffAuditLog
            Test-CompletionAuditRejectsDispatchWithoutPinnedDesktopTopic
            Test-CompletionAuditRejectsDispatchWithoutProducerBundleGate
            Test-CompletionAuditRejectsDispatchWithoutRequiredProducerPathspecs
        }
        Test-CompletionAuditRecognizesExternalNodeProof
        Test-CompletionAuditRejectsMissingExternalProducerHandoffProof
        Test-CompletionAuditRejectsExternalProducerHandoffPatchHashMismatch
        Test-CompletionAuditRejectsSmokeOnlyExternalProof
    } elseif ($Suite -eq "ExternalEvidence") {
        Test-ManifestDeclaresRequiredToolSchemas
        Test-PeerEvidenceBusTriPerspectiveQueryPacket
        Test-ProducerPowerShellParserEnforcesExactTrackedHunks
        Test-ProducerHandoffRejectsUnsafeFilemodeBundle
        Test-ProducerHandoffDefaultsToProducerLocalBundleHelper
        Test-ProducerBundleHelpersRejectDesktopCanonicalRoot
        Test-ProducerBundleHelpersIncludeCumulativeTrackedState
        Test-ProducerBundleHelpersIncludeExplicitUntrackedNewFile
        if (Test-Path -LiteralPath $Toolbox) {
            Test-ExternalEvidenceAuditKeepsDesktopFinalProofPending
            Test-ExternalEvidenceAuditRejectsSmokeOnlyProof
            Test-ExternalEvidenceAuditRejectsMissingProducerHandoff
            Test-ExternalEvidenceAuditRejectsProducerFilemodePatch
            Test-ExternalEvidenceAuditAcceptsCanonicalNewFilePatch
            Test-ExternalEvidenceAuditReportsUnrelatedPatchDropEvidence
            Test-ExternalEvidenceIntakeCopiesValidPatchDropProof
            Test-ExternalEvidenceIntakeCarriesNextActionsWhenIncomplete
            Test-ExternalEvidenceAuditReportsNextActionsFromDispatch
            Test-ExternalEvidenceAuditPinsNextActionsToTopic
            Test-DesktopControlLoopCombinesDispatchAndExternalAudit
            Test-DesktopControlLoopResolvesRelativeCanonicalRootForDispatch
        }
    } elseif ($Suite -eq "AgentDb") {
        Test-AgentDbSnapshotReportsSubsystemPersistenceGaps
    } elseif ($Suite -eq "Harmony") {
        Test-HarmonyScanRecognizesContextPropagationBreadcrumb
    } elseif ($Suite -eq "Prompt") {
        Test-McpStdioServerListsToolsResourcesAndPrompts
    } else {
        Test-LauncherSupportsTaskAliases
        Test-NodeSmokeRunnerMacminiContract
        Test-NodeSmokeRejectsNonGitProducerRoot
        Test-NodeSmokeRejectsCanonicalSourceRoot
        Test-NodeSmokeUsesArchiveIndexEnvFallback
    Test-ProducerHandoffRunsSmokeBeforeBundle
    Test-ProducerHandoffRejectsProducerShaMismatch
    Test-ProducerHandoffRejectsUnsafeFilemodeBundle
    Test-ProducerHandoffDefaultsToProducerLocalBundleHelper
    Test-ProducerHandoffRejectsSharedProducerScript
    Test-ProducerDocsUseProducerLocalBundleHelpers
    Test-ProducerHandoffRejectsUncSourceRootWithJson
    Test-ProducerHandoffTreatsMappedDriveAsSharedRoot
    Test-ProducerHandoffRejectsCanonicalSourceRootWithJson
    Test-ProducerBundleHelpersRejectForbiddenPatchTargets
    Test-ProducerBundleHelpersRejectDesktopCanonicalRoot
    Test-ProducerBundleHelpersIncludeStagedChanges
    Test-ProducerBundleHelpersIncludeCumulativeTrackedState
    Test-ProducerBundleHelpersIncludeExplicitUntrackedNewFile
    Test-ProducerHandoffPropagatesProducerBundleFailureClass
    Test-CompletionAuditCoversControlTowerObjective
    Test-CompletionAuditProofFindersFailSoftOnUnreadablePaths
    Invoke-WithCompletionAuditTestDispatch { Test-CompletionAuditRejectsAmbiguousDispatchPackets }
    Test-CompletionAuditIgnoresJanitorFixtureDispatchWhenOperationalPacketExists
    Invoke-WithCompletionAuditTestDispatch {
        Test-CompletionAuditRejectsDispatchWithoutGitRootPreflight
        Test-CompletionAuditRejectsDispatchWithCanonicalSourceRootArgs
        Test-CompletionAuditRejectsDispatchWithoutDesktopIntakeJsonSemantics
        Test-CompletionAuditRejectsDispatchWithoutProducerRootGuard
        Test-CompletionAuditRejectsDispatchWithoutNodeSetup
        Test-CompletionAuditRejectsDispatchWithoutSetupAuditLog
        Test-CompletionAuditRejectsDispatchWithoutHandoffAuditLog
        Test-CompletionAuditRejectsDispatchWithoutSmokeJsonValidation
        Test-CompletionAuditRejectsDispatchWithoutSmokeSemanticValidation
        Test-CompletionAuditRejectsDispatchWithoutPatchDropRoots
        Test-CompletionAuditRejectsDispatchWithoutPinnedDesktopTopic
        Test-CompletionAuditRejectsDispatchWithoutProducerBundleGate
        Test-CompletionAuditRejectsDispatchWithoutRequiredProducerPathspecs
        Test-CompletionAuditRejectsDispatchWithDotCanonicalRoot
        Test-CompletionAuditRejectsDispatchWithSharedProducerHelper
        Test-CompletionAuditRejectsDispatchWithConditionalProducerKitBootstrap
    }
    Test-CompletionAuditRecognizesExternalNodeProof
    Test-CompletionAuditRejectsExternalNodeProofWithoutGitRootEvidence
    Test-CompletionAuditRejectsMissingExternalProducerHandoffProof
    Test-CompletionAuditRejectsExternalProducerHandoffPatchHashMismatch
    Test-CompletionAuditRejectsSmokeOnlyExternalProof
    Test-TaskSkillsExistAndPointAtLauncher
    Test-ManifestDeclaresRequiredToolSchemas
    Test-PeerEvidenceBusTriPerspectiveQueryPacket
    if (Test-Path -LiteralPath $Toolbox) {
        Test-ArchiveSearchTwoPassAndEvidenceNeeded
        Test-ArchiveRestoreWritesAuditAndChecksums
        Test-ArchiveRestoreBlocksProducerCanonicalTarget
        Test-ArchiveRestoreRequiresAuditLogForRestoreMode
        Test-RunPipelineReportsMcpControlTowerRunner
        Test-ExternalEvidenceIntakeCopiesValidPatchDropProof
        Test-ExternalEvidenceIntakeRejectsWrongProducerRootProof
        Test-ExternalEvidenceIntakeRejectsSecretProofAndClearsStaleTargets
        Test-ExternalEvidenceIntakeCarriesNextActionsWhenIncomplete
        Test-ExternalEvidenceAuditValidatesNodeSmokeOutputs
        Test-ExternalEvidenceAuditReportsUnrelatedPatchDropEvidence
        Test-ExternalEvidenceAuditCompletesWithoutArchiveIndex
        Test-ExternalEvidenceAuditKeepsDesktopFinalProofPending
        Test-ExternalEvidenceAuditRejectsSmokeOnlyProof
        Test-ExternalEvidenceAuditRejectsMissingProducerHandoff
        Test-ExternalEvidenceAuditRejectsProducerHandoffPatchHashMismatch
        Test-ExternalEvidenceAuditRejectsProducerBundleWithoutGitRootProof
        Test-ExternalEvidenceAuditRejectsProducerHandoffWithoutGitRootProof
        Test-ExternalEvidenceAuditRejectsPendingNoticeMissingShaEntry
        Test-ExternalEvidenceAuditRejectsProducerFilemodePatch
        Test-ExternalEvidenceAuditAcceptsCanonicalNewFilePatch
        Test-ExternalEvidenceAuditRejectsProducerNonUnifiedPatch
        Test-ExternalEvidenceAuditRejectsProducerForbiddenPathPatch
        Test-ExternalEvidenceAuditRejectsProducerUnsafePathPatch
        Test-ExternalEvidenceRejectsCanonicalNodeSmokeProof
        Test-ExternalEvidenceRejectsNodeSmokeWithoutGitRootProof
        Test-ExternalEvidenceAuditRejectsWrongProducerRootSmokeProof
        Test-ExternalEvidenceAuditRejectsWrongNodeSmokeQueryHash
        Test-ExternalEvidenceAuditRejectsWrongProducerRootBundle
        Test-ExternalEvidenceAuditRejectsWrongProducerRootHandoff
        Test-ExternalEvidenceAuditRejectsWrongProducerRootHandoffInputHash
        Test-ExternalEvidenceAuditReportsMissingNotebook
        Test-ExternalEvidenceAuditReportsNextActionsFromDispatch
        Test-ExternalEvidenceAuditPinsNextActionsToTopic
        Test-ProducerCommandPlanRejectsPatchDropSourceRoot
        Test-ProducerCommandPlanRendersExternalNodeCommands
        Test-ProducerCommandPlanPreservesProducerHostPaths
        Test-DesktopDispatchPacketRendersBothProducerPackets
        Test-DesktopDispatchPacketSupportsProducerPatchDropOverrides
        Test-DesktopDispatchPacketUsesHostShapedDefaults
        Test-DesktopDispatchPacketWritesPatchDropArtifacts
        Test-DesktopDispatchPacketSupportsRoleSpecificPathspecs
        Test-DesktopDispatchPacketRejectsOverlappingRolePathspecs
        Test-DesktopDispatchPacketRejectsSharedPathspecForMultipleRoles
        Test-DesktopDispatchPacketRequiresRolePathspecsForProducerBundles
        Test-DesktopDispatchPacketPublishesPatchOnlyContract
        Test-DesktopDispatchPacketFlagsMissingDefaultProducerRoots
        Test-DesktopControlLoopCombinesDispatchAndExternalAudit
        Test-DesktopControlLoopResolvesRelativeCanonicalRootForDispatch
        Test-ProducerKitExportWritesPatchDropKit
        Test-McpStdioServerListsToolsResourcesAndPrompts
        Test-McpStdioServerCallsTool
        Test-McpNodeSetupRendersProducerLocalConfig
        Test-McpNodeSetupRejectsNonGitProducerRoot
        Test-McpNodeSetupRejectsCanonicalSourceRoot
        Test-McpNodeSetupRejectsPatchDropSourceRoot
        Test-SourceScanRedactsSecretLikeContent
        Test-AgentDbSnapshotReportsSubsystemPersistenceGaps
        }
    }
} catch {
    Write-Fail "unexpected exception" $_.Exception.Message
}

if ($Suite -ne 'ProducerKit') {
    Remove-LiveDispatchFixtureArtifacts
    Assert-NoLiveDispatchFixtureArtifacts
}

if ($Failures -gt 0) {
    Write-Host "[awx-mcp-toolbox-test][SUMMARY] failed=$Failures"
    exit 1
}

Write-Host "[awx-mcp-toolbox-test][SUMMARY] failed=0"
exit 0
