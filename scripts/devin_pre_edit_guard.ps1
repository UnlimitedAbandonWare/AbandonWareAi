# Shared PreToolUse guard for write/edit/apply_patch/notebook_edit/search_replace tools.
# Wired into Devin (.devin/hooks.v1.json), Codex (.codex/hooks.json), Grok (.grok/hooks/).
# Stdin: hook event JSON — Claude-style {tool_name,tool_input} or Grok-style
# {toolName,toolInput}. Stdout: {"decision":"block",...} only on conflict; the
# same reason also goes to stderr (Grok exit-2 deny reads the first stderr line).
# Exit 2 = block/deny this write; exit 0 = allow; exit 1 = guard error (recorded,
# non-blocking — hooks fail open, so enforcement stays in codex_work_checkpoint.py).
# Reuses __patch_drop__/source_edit_lease_contract.ps1 conflict decision; takes no lease.
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:MaxInputBytes = 65536

function Read-BoundedStdin {
    $buffer = [Array]::CreateInstance([byte], ($script:MaxInputBytes + 1))
    $stream = [Console]::OpenStandardInput()
    $total = 0
    while ($total -lt $buffer.Length) {
        $read = $stream.Read($buffer, $total, $buffer.Length - $total)
        if ($read -le 0) { break }
        $total += $read
    }
    if ($total -gt $script:MaxInputBytes) { return '' }
    return [Text.Encoding]::UTF8.GetString($buffer, 0, $total)
}

function Get-ProjectRoot {
    if (-not [string]::IsNullOrWhiteSpace($env:DEVIN_PROJECT_DIR) -and (Test-Path -LiteralPath $env:DEVIN_PROJECT_DIR)) {
        return [IO.Path]::GetFullPath($env:DEVIN_PROJECT_DIR).TrimEnd('\', '/')
    }
    return [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\', '/')
}

function Resolve-RepoRelative {
    param([string]$PathText, [string]$Root)
    if ([string]::IsNullOrWhiteSpace($PathText)) { return $null }
    $candidate = $PathText.Replace('/', '\')
    $full = if ([IO.Path]::IsPathRooted($candidate)) { [IO.Path]::GetFullPath($candidate) }
            else { [IO.Path]::GetFullPath((Join-Path $Root $candidate)) }
    $prefix = $Root + '\'
    if ($full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        return $full.Substring($prefix.Length)
    }
    return $null
}

try {
    $raw = Read-BoundedStdin
    if ([string]::IsNullOrWhiteSpace($raw)) { exit 0 }
    $event = $raw | ConvertFrom-Json
    $tool = if ($event.PSObject.Properties['tool_name']) { [string]$event.tool_name }
            elseif ($event.PSObject.Properties['toolName']) { [string]$event.toolName } else { '' }
    $toolInput = if ($event.PSObject.Properties['tool_input']) { $event.tool_input }
                 elseif ($event.PSObject.Properties['toolInput']) { $event.toolInput } else { $null }
    if ($null -eq $toolInput) { exit 0 }

    $root = Get-ProjectRoot
    $targets = [Collections.Generic.List[string]]::new()

    if ($tool -eq 'apply_patch') {
        $patchText = [string]$toolInput.patch
        if ([string]::IsNullOrEmpty($patchText)) { $patchText = ($toolInput | ConvertTo-Json -Depth 8 -Compress) }
        foreach ($match in [regex]::Matches($patchText, '(?m)^\*\*\* (?:Add File|Update File|Delete File|Move to): (.+)$')) {
            $rel = Resolve-RepoRelative -PathText $match.Groups[1].Value.Trim() -Root $root
            if ($rel) { $targets.Add($rel) }
        }
    } else {
        foreach ($prop in @('file_path', 'path', 'notebook_path')) {
            if ($toolInput.PSObject.Properties[$prop]) {
                $rel = Resolve-RepoRelative -PathText ([string]$toolInput.$prop) -Root $root
                if ($rel) { $targets.Add($rel) }
            }
        }
    }

    if ($targets.Count -eq 0) { exit 0 }

    $patchDrop = Join-Path $root '__patch_drop__'
    $contract = Join-Path $patchDrop 'source_edit_lease_contract.ps1'
    if (-not (Test-Path -LiteralPath $contract -PathType Leaf)) { exit 0 }
    . $contract

    $decision = Get-AwxSourceEditConflictDecision -PatchDropDir $patchDrop -TargetPaths @($targets)
    if (-not $decision.allowed) {
        $paths = @($decision.conflictingPaths) -join ', '
        $reason = "source-edit guard: $($decision.reason) on [$paths] - an active foreign source-edit lease covers this target; hold this file, continue unrelated work"
        [Console]::Error.Write($reason)
        [Console]::Out.Write((@{
            decision = 'block'
            reason = $reason
        } | ConvertTo-Json -Compress))
        exit 2
    }
    exit 0
} catch {
    [Console]::Error.Write("devin-pre-edit-guard-error: $($_.Exception.Message)")
    exit 1
}
