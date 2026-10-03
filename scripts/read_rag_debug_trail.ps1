#requires -Version 5.1
<#
.SYNOPSIS
  Read-only RAG/LLM launch + debug trail summary for agents (no skills needed).

.DESCRIPTION
  Prints (and optionally writes) the single latest launcher/debug summary an
  agent should read first:
    1) var\rag-launcher\LATEST.json  (SSOT pointer written by start_rag_stack.ps1;
       if missing, falls back to the newest var\rag-launcher\<run>\result.json
       and emits a warning)
    2) The newest var\debug\*-status.json / *verify*.json digest, if any
    3) A re-read 3-8 line error excerpt from evidencePaths (*.err.log,
       launcher.log, listener result files) so failurePoint claims stay
       verifiable from disk
    4) A nextCommand hint (printed only, never executed)

  This script is strictly read-only: it never starts, stops, kills, restarts,
  or probes a server, never calls a provider/model, and never prints secrets
  (credential-looking lines are masked). The only write is the optional
  -JsonPath output file.

  Exit codes: 0 ready/healthy trail, 3 not-running / no usable latest summary
  (or LATEST.json missing so only a weaker fallback exists), 4 degraded or
  failure summary, 1 tool error.

  failureClass taxonomy (frozen names; decision order is fixed):
    1) no/unparseable LATEST            -> not_running
    2) ok=true or status=ready          -> ready (benign excerpt noise is ignored)
    3) no pointer, run-dir summary only -> stale_pointer
    4) writer failurePoint              -> compile|verification|port-conflict|
       ollama|provenance|spring-start|launcher mapped 1:1 to
       compile_or_build | degraded_verify | port_conflict | ollama_dependency |
       provenance_fail | launcher_stage_fail
    5) runtimeRole/role = wear          -> meta_wear overlay
    6) legacy run dirs                  -> excerpt/stage/reason inference
    7) nothing matched                  -> unknown
  failureClass only selects the printed nextCommand matrix; the exit code stays
  owned by the LATEST verdict (0/3/4/1 unchanged):
    ready               -> Debug-RAG.bat -Action status
    not_running         -> Status-RAG.bat
    stale_pointer       -> Status-RAG.bat (re-read trail as alt)
    degraded_verify     -> Debug-RAG.bat -Action verify
    compile_or_build    -> Verify-RAG.bat (+ opt-in offline assist report)
    port_conflict       -> Status-RAG.bat (who owns the fixed ports)
    ollama_dependency   -> Status-RAG.bat
    provenance_fail     -> Debug-RAG.bat -Action verify
    launcher_stage_fail -> Read-RAG-Debug.bat (re-read trail + evidencePaths)
    meta_wear           -> Debug-Meta-Display.bat
    unknown             -> Debug-RAG.bat -Action verify

  -AiAssist is opt-in and scoped to failureClass=compile_or_build: it runs the
  existing offline tools/ai_debug_assist.py once (--mcp off --ai off, so no model
  call and no Java/Spring source read) into a NEW var\debug\assist-<runId>-<ts>.json
  with a 60s cap, and prints its path plus observed error-class counts. Every
  failure is fail-soft and never changes the trail verdict or exit code, and
  nothing the assist proposes is ever executed here. No server is ever started,
  stopped or restarted by this script.
#>
[CmdletBinding()]
param(
    [string]$Root = '',
    [string]$JsonPath = '',
    [switch]$JsonStdout,
    [switch]$AiAssist,
    [ValidateRange(3,12)][int]$ExcerptLines = 8
)

$ErrorActionPreference = 'Stop'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
$script:TrailRoot = $Root
if ([string]::IsNullOrWhiteSpace($script:TrailRoot)) {
    $script:TrailRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
} else {
    $script:TrailRoot = [IO.Path]::GetFullPath($script:TrailRoot)
}
$script:TrailExcerptLines = [int]$ExcerptLines

function Get-TrailProp {
    param($Obj, [string]$Name)
    if ($null -eq $Obj) { return $null }
    if ($Obj -is [System.Collections.IDictionary]) {
        if ($Obj.Contains($Name)) { return $Obj[$Name] }
        return $null
    }
    $p = $Obj.PSObject.Properties[$Name]
    if ($null -ne $p) { return $p.Value }
    return $null
}

function Protect-TrailText {
    # Excerpts land in agent contexts and JSON files: secret-looking assignments
    # and bearer material are masked before any line leaves the log file.
    param([string]$Text)
    $t = [string]$Text
    $t = [regex]::Replace($t, '(?i)((?:api[-_.]?key|token|secret|password|passwd|authorization|credential)[\w.-]*\s*[:=]\s*)[^\s,;'']+', '$1<redacted>')
    $t = [regex]::Replace($t, '(?i)(bearer\s+)[A-Za-z0-9._~+/=-]{8,}', '$1<redacted>')
    if ($t.Length -gt 220) { $t = $t.Substring(0, 220) }
    return $t
}

function Resolve-TrailPath {
    # Accepts absolute or root-relative evidence paths; refuses anything that
    # resolves outside the root so a crafted summary cannot exfiltrate files.
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $null }
    $candidate = $Path
    if (-not [IO.Path]::IsPathRooted($candidate)) { $candidate = Join-Path $script:TrailRoot $candidate }
    try { $candidate = [IO.Path]::GetFullPath($candidate) } catch { return $null }
    $rootPrefix = $script:TrailRoot.TrimEnd('\') + '\'
    if (-not $candidate.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { return $null }
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { return $null }
    return $candidate
}

function Get-TrailExcerpt {
    # First error-ish line in the bounded tail of one log, then up to
    # ExcerptLines lines of context (3-8 per the debug-trail contract).
    # Secret-looking lines are dropped entirely, never just masked mid-line.
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path) -or -not (Test-Path -LiteralPath $Path -PathType Leaf)) { return @() }
    $lines = @()
    try {
        $fs = [IO.FileStream]::new($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try {
            if ($fs.Length -gt 524288) { [void]$fs.Seek(-524288, [IO.SeekOrigin]::End) }
            $reader = [IO.StreamReader]::new($fs, [Text.Encoding]::UTF8, $true)
            try { $lines = @($reader.ReadToEnd() -split "`r?`n") } finally { $reader.Dispose() }
        } finally { $fs.Dispose() }
    } catch { return @() }
    $secretRe = '(?i)(api[_-]?key|secret|password|passwd|credential|authorization\s*:|bearer\s+[A-Za-z0-9]|private[_-]?key|[a-z_.-]*token\s*=)'
    $errorRe = '(?i)(\.(java|kt|groovy|scala|ps1|js|ts):\d+|\berror\b|\bfailed\b|failure:|exception|compilation failed|APPLICATION FAILED TO START|BUILD FAILED)'
    $noiseRe = '^\s*(Note:|> |\* )'
    $hit = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match $secretRe) { continue }
        if ($lines[$i] -match $errorRe -and $lines[$i] -notmatch $noiseRe) { $hit = $i; break }
    }
    if ($hit -lt 0) { return @() }
    $out = [System.Collections.Generic.List[string]]::new()
    for ($i = $hit; $i -le [Math]::Min($hit + $script:TrailExcerptLines - 1, $lines.Count - 1); $i++) {
        if ($lines[$i] -match $secretRe) { continue }
        if ($lines[$i] -match $noiseRe -and $i -ne $hit) { continue }
        $out.Add((Protect-TrailText ([string]$lines[$i]).TrimEnd())) | Out-Null
    }
    return @($out.ToArray())
}

function Read-TrailJson {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $null }
    try { return (Get-Content -Raw -LiteralPath $Path -Encoding UTF8 | ConvertFrom-Json) } catch { return $null }
}

function Write-TrailJsonFile {
    param([string]$Path, $Data)
    $dir = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($dir) -and -not (Test-Path -LiteralPath $dir -PathType Container)) {
        New-Item -ItemType Directory -Force -Path $dir | Out-Null
    }
    $tmp = "$Path.tmp-$PID"
    ($Data | ConvertTo-Json -Depth 10) | Set-Content -LiteralPath $tmp -Encoding UTF8
    Move-Item -LiteralPath $tmp -Destination $Path -Force
}

# --- failureClass taxonomy (frozen names, frozen decision order; nothing runs) ---
# 1) no/unparseable LATEST -> not_running  2) ok/ready -> ready (excerpt noise is
# ignored: live runs coexist with benign DDL excerpts)  3) no LATEST pointer but a
# run-dir summary -> stale_pointer  4) writer failurePoint -> direct map
# 5) role=wear -> meta_wear overlay  6) excerpt/stage/reason inference for legacy
# run dirs  7) unknown. Exit codes stay owned by the LATEST verdict (0/3/4/1).
$script:TrailFailurePointMap = [ordered]@{
    'compile'       = 'compile_or_build'
    'verification'  = 'degraded_verify'
    'port-conflict' = 'port_conflict'
    'ollama'        = 'ollama_dependency'
    'provenance'    = 'provenance_fail'
    'spring-start'  = 'launcher_stage_fail'
    'launcher'      = 'launcher_stage_fail'
}
$script:TrailCompileRe = '(?i)(compile|\.java:\d+|cannot find symbol|compilation failed|BUILD FAILED|Execution failed for task|build\.gradle|processresources|GradleWrapperMain|error:)'
$script:TrailPortRe = '(?i)(port-conflict|port-mismatch|ports-overlap|multiple-\w+-runtimes|foreign-port-owner|protected-port-conflict|fixed-port|already in use|eaddrinuse)'
$script:TrailOllamaRe = '(?i)(ollama)'
$script:TrailProvenanceRe = '(?i)(provenance|identity|manifest|attribution)'
$script:TrailLauncherRe = '(?i)(spring-start|existing-spring|rag-web-not-ready|bootrun|listener-|readiness|runtime-start|preflight|dev-reload|reload|asr|launcher|result-json-missing|timeout|killed|staged-restart)'
$script:TrailDegradedRe = '(?i)(verification|degraded|not_observed|not-proven|unverified|stale)'

function Get-TrailTextProp {
    # Same lookup as Get-TrailProp, flattened to a matchable string (arrays joined).
    param($Obj, [string]$Name)
    $value = Get-TrailProp -Obj $Obj -Name $Name
    if ($null -eq $value) { return '' }
    if ($value -is [string]) { return $value }
    if ($value -is [System.Collections.IEnumerable]) {
        return (($value | ForEach-Object { [string]$_ }) -join ' ')
    }
    return [string]$value
}

function Resolve-TrailFailureClass {
    # Pure classification over what the trail already read (summary fields, debug
    # digest, re-read excerpt). Never executes anything.
    param(
        $Latest,
        $DebugInfo,
        [string]$Source,
        [int]$ExitCode,
        [string]$Excerpt = '',
        [string]$WarningText = ''
    )
    if ($null -eq $Latest) { return 'not_running' }
    if ($ExitCode -eq 0) { return 'ready' }
    if ($Source -eq 'run-dir-fallback') { return 'stale_pointer' }
    $failurePoint = (Get-TrailTextProp $Latest 'failurePoint').Trim().ToLowerInvariant()
    if ($script:TrailFailurePointMap.Contains($failurePoint)) {
        return [string]$script:TrailFailurePointMap[$failurePoint]
    }
    $role = (Get-TrailTextProp $Latest 'runtimeRole').Trim().ToLowerInvariant()
    if (-not $role) { $role = (Get-TrailTextProp $Latest 'role').Trim().ToLowerInvariant() }
    if ($role -eq 'wear') { return 'meta_wear' }
    # Legacy run dirs carry no writer failurePoint: infer from stage/reason/excerpt.
    $parts = @($Source, $Excerpt, $WarningText)
    foreach ($name in @('failurePoint','stage','reason','status','listenerStatus','listenerReason',
                        'nextAction','resultPath','springFallbackReason')) {
        $parts += (Get-TrailTextProp $Latest $name)
    }
    foreach ($name in @('action','status','note','file','webReady')) {
        $parts += (Get-TrailTextProp $DebugInfo $name)
    }
    $hay = ($parts -join ' ')
    if ($hay -match '(?i)(\bwear\b|meta[-_.]?display)') { return 'meta_wear' }
    if ($hay -match $script:TrailCompileRe) { return 'compile_or_build' }
    if ($hay -match $script:TrailPortRe) { return 'port_conflict' }
    if ($hay -match $script:TrailOllamaRe) { return 'ollama_dependency' }
    if ($hay -match $script:TrailProvenanceRe) { return 'provenance_fail' }
    if ($hay -match $script:TrailLauncherRe) { return 'launcher_stage_fail' }
    if ($hay -match $script:TrailDegradedRe) { return 'degraded_verify' }
    return 'unknown'
}

function Get-TrailNextCommandHint {
    # Deterministic failureClass -> ranked printable next step matrix. Hints stay
    # printed (never executed) and every row names an existing read-only entry.
    param([string]$FailureClass, [string]$FailurePoint = '', [string]$WriterHint = '')
    $row = switch ($FailureClass) {
        'ready'               { @{ primary = 'Debug-RAG.bat -Action status'; alts = @(); why = 'latest run reports a ready runtime; status read only' } }
        'not_running'         { @{ primary = 'Status-RAG.bat'; alts = @('Debug-RAG.bat -Action status'); why = 'no LATEST pointer and no readable run summary; read status before any start decision' } }
        'stale_pointer'       { @{ primary = 'Status-RAG.bat'; alts = @('Read-RAG-Debug.bat', 'Debug-RAG.bat -Action status'); why = 'LATEST pointer missing; only a weaker run-dir summary was read' } }
        'degraded_verify'     { @{ primary = 'Debug-RAG.bat -Action verify'; alts = @('Debug-RAG.bat -Action tail'); why = 'a run exists but verification or freshness stays unproven' } }
        'compile_or_build'    { @{ primary = 'Verify-RAG.bat'; alts = @('Debug-RAG.bat -Action verify'); why = 'compile or build evidence dominates; assist stays an opt-in hypothesis' } }
        'port_conflict'       { @{ primary = 'Status-RAG.bat'; alts = @('Debug-RAG.bat -Action status'); why = 'fixed-port or runtime-owner conflict; check who owns the ports first' } }
        'ollama_dependency'   { @{ primary = 'Status-RAG.bat'; alts = @('Debug-RAG.bat -Action tail'); why = 'launcher stopped on the local Ollama dependency' } }
        'provenance_fail'     { @{ primary = 'Debug-RAG.bat -Action verify'; alts = @('Verify-RAG.bat'); why = 'fresh-runtime provenance or runtime attribution failed' } }
        'launcher_stage_fail' { @{ primary = 'Read-RAG-Debug.bat'; alts = @('Debug-RAG.bat -Action tail'); why = 'launcher stopped in a stage; re-read this trail and open the evidence paths by hand' } }
        'meta_wear'           { @{ primary = 'Debug-Meta-Display.bat'; alts = @('Verify-Meta-Display.bat'); why = 'Meta Display/wear runtime or fixed-port identity conflict; keep wear and dev roles separate' } }
        default               { @{ primary = 'Debug-RAG.bat -Action verify'; alts = @('Debug-RAG.bat -Action status'); why = 'unclassified failure trail; verification is the safest next read' } }
    }
    $why = $row.why
    if ($FailurePoint) {
        $why += "; writer failurePoint=$FailurePoint"
        if ($WriterHint) { $why += " writerHint=$WriterHint" }
    }
    return [ordered]@{ nextCommand = $row.primary; nextCommandAlts = @($row.alts); rationale = $why }
}

function Invoke-TrailAiAssist {
    # Opt-in offline assist bridge (THE_ONE): only a compile_or_build trail spawns
    # it, always with --mcp off --ai off (local miner classes only: no model call,
    # no source read), always into a NEW report path, with a 60s cap. Whatever the
    # assist proposes stays unexecuted, and any failure is fail-soft: the trail
    # verdict and exit code are never changed by this path.
    param($Latest, [string]$RunDirectory, [string]$FailureClass)
    $plan = [ordered]@{
        aiAssist        = 'disabled'
        assistPath      = ''
        assistClasses   = 0
        aiAssistCommand = ''
        note            = 'pass -AiAssist to consult the offline assist'
    }
    if (-not $AiAssist) { return $plan }
    if ($FailureClass -ne 'compile_or_build') {
        $plan.aiAssist = 'not-eligible'
        $plan.note = "assist is scoped to failureClass=compile_or_build (this trail classified as $FailureClass)"
        return $plan
    }
    $log = $null
    foreach ($pattern in @('(?i)\.err\.log$', '(?i)launcher\.log$')) {
        foreach ($p in @($script:TrailEvidencePaths)) {
            if ($p -match $pattern) { $log = Resolve-TrailPath -Path $p; if ($null -ne $log) { break } }
        }
        if ($null -ne $log) { break }
    }
    if ($null -eq $log -and -not [string]::IsNullOrWhiteSpace($RunDirectory)) {
        $log = Resolve-TrailPath -Path (Join-Path $RunDirectory 'launcher.log')
    }
    if ($null -eq $log) {
        $plan.aiAssist = 'skipped'
        $plan.note = 'no readable evidence log in this trail; the assist needs a real --log path'
        return $plan
    }
    $runId = [string](Get-TrailProp $Latest 'runId')
    if ([string]::IsNullOrWhiteSpace($runId) -and -not [string]::IsNullOrWhiteSpace($RunDirectory)) { $runId = Split-Path -Leaf $RunDirectory }
    if ([string]::IsNullOrWhiteSpace($runId)) { $runId = 'unknown-run' }
    $runId = ($runId -replace '[^A-Za-z0-9._-]', '-')
    $out = Join-Path $script:TrailRoot ('var\debug\assist-{0}-{1}.json' -f $runId, (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss'))
    $relLog = $log.Substring($script:TrailRoot.Length).TrimStart('\','/')
    $relOut = $out.Substring($script:TrailRoot.Length).TrimStart('\','/')
    $plan.aiAssistCommand = "python -B tools/ai_debug_assist.py --log `"$relLog`" --out `"$relOut`" --mcp off --ai off"
    if (-not (Test-Path -LiteralPath (Split-Path -Parent $out) -PathType Container)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $out) | Out-Null
    }
    $stdout = Join-Path $env:TEMP "awx-trail-assist-$PID-out.txt"
    $stderr = Join-Path $env:TEMP "awx-trail-assist-$PID-err.txt"
    try {
        $proc = Start-Process -FilePath 'python' -WorkingDirectory $script:TrailRoot -NoNewWindow -PassThru `
            -RedirectStandardOutput $stdout -RedirectStandardError $stderr `
            -ArgumentList @('-B', 'tools/ai_debug_assist.py', '--log', '"' + $log + '"', '--out', '"' + $out + '"', '--mcp', 'off', '--ai', 'off')
        if (-not $proc.WaitForExit(60000)) {
            try { $proc.Kill() } catch { }
            $plan.aiAssist = 'failed-soft'
            $plan.note = 'assist exceeded the 60s cap and was stopped; trail verdict unchanged'
            return $plan
        }
        $code = [int]$proc.ExitCode
        if ($code -eq 0 -and (Test-Path -LiteralPath $out -PathType Leaf)) {
            $plan.aiAssist = 'ran'
            $plan.assistPath = $relOut
            $classes = Get-TrailProp (Get-TrailProp (Read-TrailJson -Path $out) 'snapshot') 'classes'
            if ($classes -is [System.Collections.IDictionary]) { $plan.assistClasses = [int]$classes.Count }
            elseif ($null -ne $classes) { $plan.assistClasses = @($classes.PSObject.Properties).Count }
            $plan.note = 'observed error classes only; nothing the assist proposes is executed'
        } else {
            $plan.aiAssist = 'failed-soft'
            $detail = ''
            if (Test-Path -LiteralPath $stdout -PathType Leaf) { $detail = (Get-Content -Raw -LiteralPath $stdout).Trim() }
            $plan.note = "assist exit=$code (fail-soft; trail verdict unchanged) $(Protect-TrailText $detail)"
        }
    } catch {
        $plan.aiAssist = 'failed-soft'
        $plan.note = "assist could not start: $(Protect-TrailText $_.Exception.Message) (trail verdict unchanged)"
    } finally {
        Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
    }
    return $plan
}

function Invoke-ReadRagDebugTrail {
    $report = [ordered]@{
        schemaVersion = 'awx.rag_debug_trail.v1'
        root = $script:TrailRoot
        generatedAtUtc = (Get-Date).ToUniversalTime().ToString('o')
        source = 'none'; warnings = @()
        latest = $null; debug = $null
        evidence = [ordered]@{ paths = @(); excerpts = @(); firstErrorExcerpt = @() }
        nextCommand = ''; exitCode = 3; verdict = 'no-usable-trail'
        failureClass = ''; nextCommandAlts = @(); rationale = ''
        aiAssist = 'disabled'; assistPath = ''; assistClasses = 0; aiAssistCommand = ''; assistNote = ''
    }
    $warnings = [System.Collections.Generic.List[string]]::new()
    if (-not (Test-Path -LiteralPath $script:TrailRoot -PathType Container)) {
        Write-Host "[TRAIL ERROR] root not found: $($script:TrailRoot)"
        $report.verdict = 'tool-error'; $report.exitCode = 1
        return $report
    }

    # --- 1) LATEST.json (SSOT) or newest run-dir fallback --------------------
    $launcherDir = Join-Path $script:TrailRoot 'var\rag-launcher'
    $latestPath = Join-Path $launcherDir 'LATEST.json'
    $latest = $null
    $runDir = ''
    if (Test-Path -LiteralPath $latestPath -PathType Leaf) {
        $latest = Read-TrailJson -Path $latestPath
        if ($null -eq $latest) { $warnings.Add("LATEST.json exists but is not parseable: $latestPath") | Out-Null }
        else { $report.source = 'latest-pointer' }
        $runDir = [string](Get-TrailProp $latest 'runDirectory')
        if ([string]::IsNullOrWhiteSpace($runDir)) { $runDir = [string](Get-TrailProp $latest 'logDirectory') }
    }
    if ($null -eq $latest) {
        $warnings.Add('var\rag-launcher\LATEST.json missing or unreadable; falling back to newest run directory') | Out-Null
        $runDirs = @()
        if (Test-Path -LiteralPath $launcherDir -PathType Container) {
            $runDirs = @(Get-ChildItem -LiteralPath $launcherDir -Directory -ErrorAction SilentlyContinue | Sort-Object Name -Descending)
        }
        foreach ($d in $runDirs) {
            $candidate = Read-TrailJson -Path (Join-Path $d.FullName 'result.json')
            if ($null -ne $candidate) { $latest = $candidate; $runDir = $d.FullName; break }
            if ([string]::IsNullOrWhiteSpace($runDir)) { $runDir = $d.FullName }
        }
        if ($null -ne $latest) {
            $report.source = 'run-dir-fallback'
        } elseif (-not [string]::IsNullOrWhiteSpace($runDir)) {
            $latest = [ordered]@{ ok = $false; status = 'unknown'; stage = ''; reason = 'result-json-missing'; runDirectory = $runDir; logDirectory = $runDir }
            $report.source = 'run-dir-no-result'
            $warnings.Add("newest run directory has no parseable result.json: $runDir") | Out-Null
        }
    }

    # --- 2) newest var\debug *-status|verify json digest ---------------------
    $debugDir = Join-Path $script:TrailRoot 'var\debug'
    if (Test-Path -LiteralPath $debugDir -PathType Container) {
        $dbgFile = @(Get-ChildItem -LiteralPath $debugDir -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(?i)(-status|verify[^.]*)\.json$' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1)
        if ($dbgFile.Count -gt 0) {
            $dbg = Read-TrailJson -Path $dbgFile[0].FullName
            if ($null -ne $dbg) {
                $report.debug = [ordered]@{
                    found = $true
                    file = $dbgFile[0].Name
                    action = [string](Get-TrailProp $dbg 'action')
                    ok = (Get-TrailProp $dbg 'ok')
                    status = [string](Get-TrailProp $dbg 'status')
                    exitCode = (Get-TrailProp $dbg 'exitCode')
                    role = [string](Get-TrailProp $dbg 'role')
                    webReady = [string](Get-TrailProp (Get-TrailProp $dbg 'readiness') 'webReady')
                    sourcesNewer = [string](Get-TrailProp (Get-TrailProp $dbg 'freshness') 'sourcesNewer')
                    ageMinutes = [int][Math]::Round(((Get-Date) - $dbgFile[0].LastWriteTime).TotalMinutes)
                }
            }
        }
    }
    if ($null -eq $report.debug) { $report.debug = [ordered]@{ found = $false; note = 'no var\debug status/verify json observed' } }

    # --- 3) classify + collect evidence --------------------------------------
    $exitCode = 3; $verdict = 'not-running-or-no-summary'
    if ($null -ne $latest) {
        $ok = (Get-TrailProp $latest 'ok')
        $status = [string](Get-TrailProp $latest 'status')
        if ($ok -eq $true -or $status -eq 'ready') { $exitCode = 0; $verdict = 'ready' }
        else { $exitCode = 4; $verdict = 'degraded-or-failed' }
    }
    if ($report.source -ne 'latest-pointer' -and $exitCode -lt 3) { $exitCode = 3; $verdict = 'no-latest-pointer' }
    if ($report.source -eq 'run-dir-fallback' -or $report.source -eq 'run-dir-no-result') { $exitCode = [Math]::Max($exitCode, 3) }

    if ($null -ne $latest) {
        $report.latest = [ordered]@{
            runId = [string](Get-TrailProp $latest 'runId')
            ok = (Get-TrailProp $latest 'ok')
            status = [string](Get-TrailProp $latest 'status')
            stage = [string](Get-TrailProp $latest 'stage')
            reason = [string](Get-TrailProp $latest 'reason')
            failurePoint = [string](Get-TrailProp $latest 'failurePoint')
            listenerStatus = [string](Get-TrailProp $latest 'listenerStatus')
            listenerReason = [string](Get-TrailProp $latest 'listenerReason')
            nextAction = [string](Get-TrailProp $latest 'nextAction')
            ports = (Get-TrailProp $latest 'ports')
            ollamaPort = (Get-TrailProp $latest 'ollamaPort')
            runDirectory = $runDir
            resultPath = [string](Get-TrailProp $latest 'resultPath')
            completedAtUtc = [string](Get-TrailProp $latest 'completedAtUtc')
        }
        if ([string]::IsNullOrWhiteSpace($report.latest.runId) -and -not [string]::IsNullOrWhiteSpace($runDir)) {
            $report.latest.runId = Split-Path -Leaf $runDir
        }
    }

    # evidence paths: summary-declared first, then on-disk scan of the run dir
    $seenPaths = [System.Collections.Generic.HashSet[string]]::new()
    $evidencePaths = [System.Collections.Generic.List[string]]::new()
    $addPath = {
        param([string]$p)
        $resolved = Resolve-TrailPath -Path $p
        if ($null -ne $resolved -and $seenPaths.Add($resolved)) { $evidencePaths.Add($resolved) | Out-Null }
    }
    foreach ($p in @((Get-TrailProp $latest 'evidencePaths'))) { & $addPath ([string]$p) }
    & $addPath ([string](Get-TrailProp $latest 'resultPath'))
    if (-not [string]::IsNullOrWhiteSpace($runDir) -and (Test-Path -LiteralPath $runDir -PathType Container)) {
        foreach ($f in @(Get-ChildItem -LiteralPath $runDir -Filter '*.err.log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) { & $addPath $f.FullName }
        & $addPath (Join-Path $runDir 'launcher.log')
        foreach ($f in @(Get-ChildItem -LiteralPath $runDir -Filter 'chat-ui-vibe-listener*.result.json' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending)) { & $addPath $f.FullName }
        foreach ($f in @(Get-ChildItem -LiteralPath $runDir -Filter '*.out.log' -File -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 3)) { & $addPath $f.FullName }
    }
    $script:TrailEvidencePaths = @($evidencePaths | ForEach-Object { $_.Substring($script:TrailRoot.Length).TrimStart('\','/') })
    $report.evidence.paths = @($script:TrailEvidencePaths)

    # re-read excerpts: *.err.log first (deepest failure), launcher.log next
    $ordered = @($evidencePaths | Where-Object { $_ -match '(?i)\.err\.log$' }) +
               @($evidencePaths | Where-Object { $_ -match '(?i)\\launcher\.log$' }) +
               @($evidencePaths | Where-Object { $_ -notmatch '(?i)\.err\.log$|\\launcher\.log$' })
    $excerpts = [System.Collections.Generic.List[object]]::new()
    $firstExcerpt = @((Get-TrailProp $latest 'firstErrorExcerpt') | ForEach-Object { Protect-TrailText ([string]$_) } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    foreach ($p in $ordered) {
        if ($excerpts.Count -ge 3) { break }
        $ex = @(Get-TrailExcerpt -Path $p)
        if ($ex.Count -gt 0) {
            $excerpts.Add([ordered]@{ path = $p.Substring($script:TrailRoot.Length).TrimStart('\','/'); lines = $ex }) | Out-Null
            if ($firstExcerpt.Count -eq 0) { $firstExcerpt = $ex }
        }
    }
    $report.evidence.excerpts = @($excerpts.ToArray())
    $report.evidence.firstErrorExcerpt = [string[]]$firstExcerpt

    # failureClass -> ranked nextCommand matrix. Exit-code authority stays with the
    # LATEST verdict computed above, so the 0/3/4/1 contract is unchanged.
    $failurePoint = (Get-TrailTextProp $latest 'failurePoint').Trim()
    $writerHint = (Get-TrailTextProp $latest 'nextAction').Trim()
    $failureClass = Resolve-TrailFailureClass -Latest $latest -DebugInfo $report.debug `
        -Source $report.source -ExitCode $exitCode `
        -Excerpt (@($firstExcerpt) -join ' ') -WarningText (@($warnings) -join ' ')
    $hint = Get-TrailNextCommandHint -FailureClass $failureClass -FailurePoint $failurePoint -WriterHint $writerHint
    $assist = Invoke-TrailAiAssist -Latest $latest -RunDirectory $runDir -FailureClass $failureClass
    $report.failureClass = $failureClass
    $report.nextCommand = $hint.nextCommand
    $report.nextCommandAlts = @($hint.nextCommandAlts)
    $report.rationale = $hint.rationale
    $report.aiAssist = $assist.aiAssist
    $report.assistPath = $assist.assistPath
    $report.assistClasses = [int]$assist.assistClasses
    $report.aiAssistCommand = $assist.aiAssistCommand
    $report.assistNote = $assist.note
    $report.warnings = @($warnings.ToArray())
    $report.exitCode = $exitCode
    $report.verdict = $verdict
    return $report
}

function Write-TrailReport {
    param($Report)
    Write-Host "[TRAIL ROOT] $($script:TrailRoot)"
    foreach ($w in @($Report.warnings)) { Write-Host "[TRAIL WARN] $w" }
    if ($null -ne $Report.latest) {
        $l = $Report.latest
        Write-Host "[TRAIL LATEST] source=$($Report.source) runId=$($l.runId) ok=$($l.ok) status=$($l.status) stage=$($l.stage) reason=$($l.reason) failurePoint=$($l.failurePoint)"
        if ($l.listenerStatus -or $l.listenerReason) { Write-Host "[TRAIL LISTENER] status=$($l.listenerStatus) reason=$($l.listenerReason) nextAction=$($l.nextAction)" }
        if ($null -ne $l.ports) { Write-Host "[TRAIL PORTS] $($l.ports | ConvertTo-Json -Compress) ollamaPort=$($l.ollamaPort)" }
        Write-Host "[TRAIL RESULT] $($l.resultPath)  runDir=$($l.runDirectory)"
    } else {
        Write-Host "[TRAIL LATEST] none found (source=$($Report.source))"
    }
    if ($null -eq $Report.debug -or $Report.debug.found -ne $true) {
        $note = if ($null -ne $Report.debug) { $Report.debug.note } else { 'no var\debug status/verify json observed' }
        Write-Host "[TRAIL DEBUG] $note"
    } else {
        $d = $Report.debug
        Write-Host "[TRAIL DEBUG] $($d.file) action=$($d.action) ok=$($d.ok) status=$($d.status) exit=$($d.exitCode) webReady=$($d.webReady) sourcesNewer=$($d.sourcesNewer) age=$($d.ageMinutes)m"
    }
    if ($Report.evidence.paths.Count -gt 0) {
        Write-Host "[TRAIL EVIDENCE-PATHS]"
        foreach ($p in @($Report.evidence.paths)) { Write-Host "  - $p" }
    }
    if ($Report.evidence.firstErrorExcerpt.Count -gt 0) {
        Write-Host "[TRAIL FIRST-ERROR]"
        foreach ($line in @($Report.evidence.firstErrorExcerpt)) { Write-Host "  | $line" }
    }
    foreach ($ex in @($Report.evidence.excerpts)) {
        Write-Host "[TRAIL EXCERPT] $($ex.path)"
        foreach ($line in @($ex.lines)) { Write-Host "  | $line" }
    }
    Write-Host "[TRAIL CLASS] $($Report.failureClass)"
    Write-Host "[TRAIL NEXT] $($Report.nextCommand)   (hint only - not executed)"
    foreach ($alt in @($Report.nextCommandAlts)) {
        if ($alt) { Write-Host "[TRAIL NEXT-ALT] $alt   (hint only - not executed)" }
    }
    if ($Report.rationale) { Write-Host "[TRAIL RATIONALE] $($Report.rationale)" }
    if ($Report.aiAssist -ne 'disabled') {
        Write-Host "[TRAIL AI-ASSIST] status=$($Report.aiAssist) classes=$($Report.assistClasses)"
        if ($Report.assistPath) { Write-Host "[TRAIL AI-ASSIST-REPORT] $($Report.assistPath)" }
        if ($Report.aiAssistCommand) {
            Write-Host "[TRAIL AI-ASSIST-CMD] $($Report.aiAssistCommand)   (offline miner only; nothing the assist proposes is executed)"
        }
        if ($Report.assistNote) { Write-Host "[TRAIL AI-ASSIST-NOTE] $($Report.assistNote)" }
    }
    Write-Host "[TRAIL EXIT] $($Report.exitCode) ($($Report.verdict))"
}

try {
    $trailReport = Invoke-ReadRagDebugTrail
    if (-not [string]::IsNullOrWhiteSpace($JsonPath)) {
        $jsonOut = $JsonPath
        if (-not [IO.Path]::IsPathRooted($jsonOut)) { $jsonOut = Join-Path $script:TrailRoot $jsonOut }
        Write-TrailJsonFile -Path $jsonOut -Data $trailReport
        Write-Host "[TRAIL JSON] $jsonOut"
    }
    if ($JsonStdout) {
        [Console]::Out.Write(($trailReport | ConvertTo-Json -Depth 10 -Compress))
        [Console]::Out.Write([Environment]::NewLine)
    } else {
        Write-TrailReport -Report $trailReport
    }
    if ($MyInvocation.InvocationName -ne '.') { exit [int]$trailReport.exitCode }
} catch {
    Write-Host "[TRAIL ERROR] $($_.Exception.Message)"
    if ($MyInvocation.InvocationName -ne '.') { exit 1 } else { throw }
}
