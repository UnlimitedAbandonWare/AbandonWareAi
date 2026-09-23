# verify_codex_instructions.ps1 — read-only guard for Codex instruction health.
# Checks: AGENTS.md size/markers, semantic catalog validity, SKILL.md frontmatter,
# required repo skills, stale Meta Display contract values in live rules.
# Read-only: no source edits, no Gradle, no network. Exit 0 pass / 1 fail.
param(
    [int]$WarnBytes = 61440,      # 60KB recommended ceiling
    [int]$MaxBytes = 65536,       # project_doc_max_bytes — never exceed
    [switch]$Quiet
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Agents = Join-Path $Root "AGENTS.md"
$SkillsDir = Join-Path $Root ".agents\skills"
$CatalogPy = Join-Path $SkillsDir "demo1-adaptive-rule-lab\scripts\catalog.py"
$fail = 0; $warn = 0

function Fail($m) { $script:fail++; Write-Host "FAIL  $m" }
function Warn($m) { $script:warn++; Write-Host "WARN  $m" }
function Ok($m) { if (-not $Quiet) { Write-Host "ok    $m" } }

# 1. AGENTS.md size
if (-not (Test-Path $Agents)) { Fail "AGENTS.md missing at $Agents" }
else {
    $bytes = (Get-Item $Agents).Length
    if ($bytes -gt $MaxBytes) { Fail "AGENTS.md $bytes bytes > limit $MaxBytes (Codex truncates)" }
    elseif ($bytes -gt $WarnBytes) { Warn "AGENTS.md $bytes bytes > recommended $WarnBytes (under $MaxBytes)" }
    else { Ok "AGENTS.md $bytes bytes" }

    # 2. Required binding markers/rules in project AGENTS.md
    $body = [IO.File]::ReadAllText($Agents)
    $required = @(
        'DEMO1-LOCAL-FIRST-RAG', 'gradlew.bat', 'verify_full_test_refresh.ps1',
        'verify_control_plane_topology.ps1', 'bootRun', 'PromptBuilder',
        'source_edit_session.ps1', 'guarded_source_edit.js', 'demo.interview.enabled',
        'hintHoldUntil', 'AWX_AGENT_ALLOW_PAID_MODELS', 'start_rag_stack.ps1',
        'demo1-goal-complete-stop', 'demo1-core-request-router'
    )
    # A binding rule may live in the canonical file AGENTS.md delegates to (the
    # Meta Display contract skill is declared canonical in AGENTS.md itself), so
    # declare that home per marker instead of forcing the text into AGENTS.md.
    $markerHomes = @{
        'hintHoldUntil' = '.agents\skills\demo1-meta-display-simple-caption\SKILL.md'
    }
    foreach ($r in $required) {
        if ($body.Contains($r)) { Ok "marker present: $r"; continue }
        $markerFile = $markerHomes[$r]
        if ($markerFile -and (Test-Path (Join-Path $Root $markerFile) -PathType Leaf) -and
            [IO.File]::ReadAllText((Join-Path $Root $markerFile)).Contains($r)) {
            Ok ("marker present: {0} (canonical: {1})" -f $r, $markerFile)
        } else { Fail "missing binding rule: $r" }
    }
    $begins = ([regex]::Matches($body, '<!--\s*BEGIN ')).Count
    $ends = ([regex]::Matches($body, '<!--\s*END ')).Count
    if ($begins -ne $ends) { Fail "BEGIN/END marker imbalance: $begins vs $ends" }
    else { Ok "BEGIN/END markers balanced: $begins" }

    # 6. Stale Meta Display contract values in live rules (AGENTS.md + skills)
    $history = 'historically|older|prior|was\b|previous|legacy|old '
    $stalePatterns = @(
        @{ Name = 'max-output-tokens 320 (now 640)'; Rx = 'max-output-tokens[^\n]{0,40}\b320\b' },
        @{ Name = 'Hangul target ~320 chars (now 480-540)'; Rx = '(target|about|generate)[^\n]{0,30}\b320\b[^\n]{0,20}(Hangul|char)' },
        @{ Name = 'hard cap 360 (now 612)'; Rx = 'cap[^\n]{0,20}\b360\b' },
        @{ Name = 'context-chars 1000 (now 2000)'; Rx = 'context-chars[^\n]{0,20}\b1000\b' }
    )
    $liveFiles = @($Agents) + (Get-ChildItem $SkillsDir -Recurse -Filter SKILL.md | ForEach-Object { $_.FullName })
    foreach ($sp in $stalePatterns) {
        $hits = 0
        foreach ($f in $liveFiles) {
            $ln = 0
            foreach ($line in [IO.File]::ReadAllLines($f)) {
                $ln++
                if ($line -match $sp.Rx -and $line -notmatch $history) {
                    $hits++; Write-Host ("      stale candidate {0}:{1}: {2}" -f $f.Replace("$Root\", ''), $ln, $line.Trim().Substring(0, [Math]::Min(100, $line.Trim().Length)))
                }
            }
        }
        if ($hits) { Fail "stale Meta Display value: $($sp.Name) — $hits line(s)" }
        else { Ok "no stale value: $($sp.Name)" }
    }
}

# 3. Semantic catalog validity
if (-not (Test-Path $CatalogPy)) { Fail "catalog.py missing at $CatalogPy" }
else {
    $json = & python -B $CatalogPy validate --root $Root 2>$null | Out-String
    try {
        $v = $json | ConvertFrom-Json
        if ($v.valid) { Ok "semantic catalog valid:true ($($v.entries) entries)" }
        else { Fail "semantic catalog diagnostics: $($v.diagnostics.Count) (run catalog.py validate)" }
    } catch { Fail "catalog validate produced no JSON: $json" }
}

# 4. SKILL.md frontmatter: byte-0 '---', name+description, description <= 88 chars
$skills = Get-ChildItem $SkillsDir -Recurse -Filter SKILL.md
foreach ($s in $skills) {
    $raw = [IO.File]::ReadAllBytes($s.FullName)
    if ($raw.Length -lt 3 -or -not ($raw[0] -eq 0x2D -and $raw[1] -eq 0x2D -and $raw[2] -eq 0x2D)) {
        Fail "frontmatter not at byte 0 (BOM/prefix): $($s.FullName.Replace("$Root\", ''))"; continue
    }
    $t = [IO.File]::ReadAllText($s.FullName)
    if ($t -notmatch '(?m)^name:\s*.+') { Warn "no name: $($s.Directory.Name)" }
    $d = [regex]::Match($t, '(?m)^description:\s*(.+)$')
    if (-not $d.Success) { Warn "no description: $($s.Directory.Name)" }
    elseif ($d.Groups[1].Value.Trim([char[]]@(' ', '"', "'")).Length -gt 88) { Warn "description >88 chars (Codex truncates): $($s.Directory.Name)" }
}
Ok "SKILL.md frontmatter checked: $($skills.Count) files"

# 5. Required repo skills present
$needSkills = @(
    'demo1-goal-complete-stop', 'demo1-core-request-router',
    'demo1-meta-display-simple-caption', 'demo1-triad-deliberation',
    'abandonware-desktop-zombie-purge-safe-patch', 'self-ask-query-rewrite-safe-patch'
)
foreach ($n in $needSkills) {
    if (Test-Path (Join-Path $SkillsDir "$n\SKILL.md")) { Ok "repo skill: $n" }
    else { Fail "missing repo skill: $n" }
}

Write-Host ("-" * 40)
if ($fail) { Write-Host "RESULT: FAIL ($fail fail, $warn warn)"; exit 1 }
Write-Host "RESULT: PASS ($warn warn)"; exit 0
