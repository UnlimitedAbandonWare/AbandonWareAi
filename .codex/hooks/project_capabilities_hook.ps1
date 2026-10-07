#requires -Version 5.1
<#
.SYNOPSIS
  UserPromptSubmit hook entry (Windows): run scripts/awx_device_bus.py hook
  against the project root. Replaces the former inline -Command payload, whose
  $variables were interpolated away by the host's outer command layer, which
  executed mangled text and failed every run.

  2026-10-07 add-only: when the incoming prompt carries the plugin-role
  boilerplate block (>=2 numbered sections + bullets + admin/login wording)
  and configs/vibe-open.yaml is enabled, one VIBE_OPEN DEFERRED_SECURITY
  context line is merged into additionalContext so Codex does not ask for
  URLs/accounts or turn boilerplate security lines into BLOCKED.

.NOTES
  Contract: stdout may carry one hookSpecificOutput JSON object; diagnostics go
  to stderr; this gate never blocks a prompt, so every path exits 0.
#>
[CmdletBinding()]
param()
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$VIBE_LINE = 'VIBE_OPEN: 상용구 admin 로그인·차단 줄 = TEMPLATE_BOILERPLATE → DEFERRED_SECURITY(HTTP status만 기록). URL·계정 묻지 말 것, 이 항목만으로 BLOCKED 금지. 근거 docs/security/VIBE_OPEN.md'
function Get-VibeInjection {
    param([string]$Root)
    if (-not [Console]::IsInputRedirected) { return '' }
    $raw = ''
    try { $raw = [Console]::In.ReadToEnd() } catch { return '' }
    if (-not $raw) { return '' }
    $prompt = ''
    try {
        $ev = $raw | ConvertFrom-Json -ErrorAction Stop
        foreach ($k in 'prompt', 'user_prompt', 'text', 'message') {
            $prop = $ev.PSObject.Properties[$k]
            if ($prop -and $prop.Value) { $prompt = [string]$prop.Value; break }
        }
    } catch { return '' }
    if (-not $prompt) { return '' }
    if ($prompt -notmatch '(?i)admin|관리자|로그인|차단') { return '' }
    $roles = [regex]::Matches($prompt, '(?m)^\s*\d+\.\s+\S').Count
    $bullets = [regex]::Matches($prompt, '(?m)^\s*[-•·*]\s+\S').Count
    if ($roles -lt 2 -or $bullets -lt 2) { return '' }
    $classifier = Join-Path $Root 'scripts\codex_question_classifier.py'
    if (-not (Test-Path -LiteralPath $classifier -PathType Leaf)) { return '' }
    try {
        $env:VIBE_HOOK_PROMPT = $prompt
        try {
            $out = (& python -B $classifier --objective env 2>$null | Out-String)
        } finally {
            Remove-Item Env:VIBE_HOOK_PROMPT -ErrorAction SilentlyContinue
        }
        if ($LASTEXITCODE -ne 0 -or -not $out.Trim()) { return '' }
        $oj = $out | ConvertFrom-Json -ErrorAction Stop
        if ($oj.boilerplate -and $oj.vibe_open) { return $VIBE_LINE }
    } catch { return '' }
    return ''
}
function Merge-HookContext {
    param([string]$BusOut, [string]$Inject)
    $ctx = ''
    $eventName = 'UserPromptSubmit'
    if ($BusOut) {
        try {
            $obj = $BusOut | ConvertFrom-Json -ErrorAction Stop
            $hso = $obj.hookSpecificOutput
            if ($hso -and $hso.additionalContext) {
                $ctx = [string]$hso.additionalContext
            }
            if ($hso -and $hso.hookEventName) {
                $eventName = [string]$hso.hookEventName
            }
        } catch { $ctx = '' }
    }
    if ($Inject) {
        $ctx = $(if ($ctx) { "$ctx | $Inject" } else { $Inject })
    }
    if (-not $ctx) { return $BusOut }
    if ($ctx.Length -gt 512) { $ctx = $ctx.Substring(0, 512) }
    return (@{
        hookSpecificOutput = @{
            hookEventName    = $eventName
            additionalContext = $ctx
        }
    } | ConvertTo-Json -Compress -Depth 4)
}
try {
    $root = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).ProviderPath
    $script = Join-Path $root 'scripts\awx_device_bus.py'
    $config = Join-Path $root 'config\project-resources.json'
    $inject = ''
    try { $inject = Get-VibeInjection -Root $root } catch { $inject = '' }
    if ((Test-Path -LiteralPath $script -PathType Leaf) -and (Test-Path -LiteralPath $config -PathType Leaf)) {
        $busOut = (& python -B $script hook --root $root 2>$null | Out-String)
        if ($LASTEXITCODE -ne 0) {
            [Console]::Error.Write("capabilities-hook-exit:$LASTEXITCODE")
        }
        $merged = Merge-HookContext -BusOut $busOut -Inject $inject
        if ($merged) { [Console]::Out.Write($merged) }
    } else {
        [Console]::Error.Write('capabilities-hook-inputs-missing')
        if ($inject) {
            $line = (@{
                hookSpecificOutput = @{
                    hookEventName    = 'UserPromptSubmit'
                    additionalContext = $(if ($inject.Length -gt 512) { $inject.Substring(0, 512) } else { $inject })
                }
            } | ConvertTo-Json -Compress -Depth 4)
            [Console]::Out.Write($line)
        }
    }
} catch {
    [Console]::Error.Write("capabilities-hook-error: $($_.Exception.Message)")
}
exit 0
