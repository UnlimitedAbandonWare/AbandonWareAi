# Shared PreToolUse guard for write/edit/apply_patch/notebook_edit/search_replace tools.
# Wired into Devin (.devin/hooks.v1.json), Codex (.codex/hooks.json), Grok (.grok/hooks/).
# Stdin: hook event JSON — Claude-style {tool_name,tool_input} or Grok-style
# {toolName,toolInput}. Stdout: {"decision":"block",...} only on conflict; the
# same reason also goes to stderr (Grok exit-2 deny reads the first stderr line).
# Exit 2 = block/deny this write; exit 0 = allow; exit 1 = guard error (recorded,
# non-blocking — hooks fail open, so enforcement stays in codex_work_checkpoint.py).
# Reuses __patch_drop__/source_edit_lease_contract.ps1 conflict decision; takes no lease.
[CmdletBinding()]
param(
    [ValidateSet('guard', 'check')]
    [string]$Action = 'guard'
)

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

if ($Action -eq 'check') {
    $root = Get-ProjectRoot
    $patchDrop = Join-Path $root '__patch_drop__'
    $contract = Join-Path $patchDrop 'source_edit_lease_contract.ps1'
    $status = [ordered]@{
        schema          = 'awx.devin-pre-edit-guard.v1'
        action          = 'check'
        root            = $root
        contractPresent = (Test-Path -LiteralPath $contract -PathType Leaf)
        checks          = [ordered]@{
            cwdBoundary   = 'enforced: outside-root write targets blocked (P8)'
            preimageExist = 'enforced: modify verbs require an existing target; Add File requires absent (P1)'
            leaseConflict = 'delegated: Get-AwxSourceEditConflictDecision'
        }
    }
    [Console]::Out.Write(($status | ConvertTo-Json -Compress))
    exit 0
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
    $outside = [Collections.Generic.List[string]]::new()
    $verbs = @{}

    if ($tool -eq 'apply_patch') {
        $patchText = [string]$toolInput.patch
        if ([string]::IsNullOrEmpty($patchText)) { $patchText = ($toolInput | ConvertTo-Json -Depth 8 -Compress) }
        foreach ($match in [regex]::Matches($patchText, '(?m)^\*\*\* (Add File|Update File|Delete File|Move to): (.+)$')) {
            $raw = $match.Groups[2].Value.Trim()
            $rel = Resolve-RepoRelative -PathText $raw -Root $root
            if ($rel) { $targets.Add($rel); $verbs[$rel] = $match.Groups[1].Value }
            else { $outside.Add($raw) }
        }
    } else {
        foreach ($prop in @('file_path', 'path', 'notebook_path')) {
            if ($toolInput.PSObject.Properties[$prop]) {
                $raw = [string]$toolInput.$prop
                $rel = Resolve-RepoRelative -PathText $raw -Root $root
                if ($rel) { $targets.Add($rel); $verbs[$rel] = $tool }
                elseif (-not [string]::IsNullOrWhiteSpace($raw)) { $outside.Add($raw) }
            }
        }
    }

    # P8: a write/edit/patch target that resolves outside the project root is
    # blocked outright — it used to drop out of $targets silently.
    if ($outside.Count -gt 0) {
        $paths = ($outside | Select-Object -First 5) -join ', '
        $reason = "source-edit guard: edit-outside-cwd on [$paths] - write target resolves outside project root; keep edits under $root"
        [Console]::Error.Write($reason)
        [Console]::Out.Write((@{ decision = 'block'; reason = $reason } | ConvertTo-Json -Compress))
        exit 2
    }

    if ($targets.Count -eq 0) { exit 0 }

    # P1: create-vs-modify preimage check — a modify verb on a missing file (or
    # Add File on an existing one) can only fail with a context miss.
    $modifyVerbs = @('edit', 'search_replace', 'notebook_edit', 'Update File', 'Delete File')
    foreach ($rel in $targets) {
        $verb = [string]$verbs[$rel]
        $exists = Test-Path -LiteralPath (Join-Path $root $rel)
        if ($modifyVerbs -contains $verb -and -not $exists) {
            $reason = "source-edit guard: preimage-missing - '$verb' targets absent file [$rel]; create it first or list the real path"
            [Console]::Error.Write($reason)
            [Console]::Out.Write((@{ decision = 'block'; reason = $reason } | ConvertTo-Json -Compress))
            exit 2
        }
        if ($verb -eq 'Add File' -and $exists) {
            $reason = "source-edit guard: create-on-existing - 'Add File' targets existing [$rel]; the patch would fail a context check"
            [Console]::Error.Write($reason)
            [Console]::Out.Write((@{ decision = 'block'; reason = $reason } | ConvertTo-Json -Compress))
            exit 2
        }
    }

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
