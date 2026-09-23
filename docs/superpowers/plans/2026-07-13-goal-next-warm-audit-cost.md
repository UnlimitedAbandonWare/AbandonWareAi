# Goal Next Warm Audit Cost Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development to execute this single task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce eligible Desktop-only `goal_next_auto.ps1` completion-audit executions from three to two while retaining the three-audit cold path.

**Architecture:** Make `var/codex-smoke/awx-mcp-completion-audit-current.json` canonical-first for source-health input, falling back to legacy glob files only when canonical input is missing or unparseable. Reuse canonical input before the initial source-health run only when it is parseable, no more than 86,400 seconds old, no more than 300 seconds future-dated, and both declared and raw secret-pattern counts are zero; packet and final audits always remain authoritative.

**Tech Stack:** PowerShell 5.1, Python completion-audit stub fixtures, repo-local JSON evidence contracts.

## Global Constraints

- Preserve `-ExternalDispatch` and `-RequireSupabaseProof` as explicit opt-ins.
- Preserve packet and final completion audits on every path.
- Preserve existing PatchDrop, sourceSet, LangChain4j 1.0.1, PromptBuilder, and secret-redaction gates.
- Do not reset, stage, or rewrite unrelated dirty hunks in either target file.
- Do not create producer bundles, dispatch packets, Supabase mutations, or Browser/Computer requirements.

---

### Task 1: Canonical-first source-health audit selection

**Files:**

- Modify: `scripts/test_source_health_scorecard.py`
- Modify: `scripts/source_health_scorecard.py`

**Interfaces:**

- Consumes: canonical completion audit plus legacy `awx-mcp-completion-audit*.json` files.
- Produces: deterministic canonical artifact data and repo-relative path, with legacy fallback only for missing/unparseable canonical input.

- [x] **Step 1: Add RED tests**

Add three direct `_read_latest_completion_audit` tests: valid canonical beats a newer legacy file; malformed canonical falls back to legacy; valid stale canonical remains selected even when a newer fresh legacy file exists.

- [x] **Step 2: Run RED**

```powershell
python scripts\test_source_health_scorecard.py
```

Expected: canonical-priority tests fail because the current implementation sorts the whole glob by mtime.

- [x] **Step 3: Implement canonical-first selection**

```python
canonical = directory / "awx-mcp-completion-audit-current.json"
if canonical.is_file():
    try:
        return _load_json_file(canonical), canonical.relative_to(root).as_posix()
    except Exception:
        pass
candidates = sorted(
    (
        path
        for path in directory.glob(COMPLETION_AUDIT_GLOB)
        if path.is_file() and path != canonical
    ),
    key=lambda path: (path.stat().st_mtime, path.name),
    reverse=True,
)
```

- [x] **Step 4: Run GREEN**

```powershell
python scripts\test_source_health_scorecard.py
```

Expected: exit `0` and all source-health scorecard tests pass.

---

### Task 2: Conditional completion-audit preflight reuse

**Files:**

- Modify: `scripts/goal_next_auto_tests.ps1`
- Modify: `scripts/goal_next_auto.ps1`

**Interfaces:**

- Consumes: canonical completion-audit JSON at `var/codex-smoke/awx-mcp-completion-audit-current.json`.
- Produces: `completionAuditPreflight` summary fields `executed`, `reuseAllowed`, `reason`, `ageSeconds`, `maxAgeSeconds`, and `secretHits`.

- [x] **Step 1: Write the failing warm-path regression**

Create a separate warm fake Desktop root and seed it before invoking `goal_next_auto.ps1`; leave the existing missing-canonical Desktop root as the cold-path regression:

```powershell
$warmRoot = New-FakeGoalRoot -Mode 'partial'
$warmOutput = Join-Path $warmRoot 'warm-out'
$warmCanonicalAuditPath = Join-Path $warmRoot 'var\codex-smoke\awx-mcp-completion-audit-current.json'
Set-TestFile $warmCanonicalAuditPath (([ordered]@{
    schemaVersion = 'awx.mcp.completion_audit.v1'
    generatedAt = [DateTimeOffset]::UtcNow.ToString('o')
    ok = $true
    status = 'local_control_tower_ready'
    secretHits = 0
    secretPatternHits = 0
    rawSecretPatternHits = 0
}) | ConvertTo-Json -Depth 10)
$warmRun = Invoke-Captured -Arguments @('-File', $script, '-Root', $warmRoot, '-OutputDir', $warmOutput, '-Topic', 'mcp-control-loop')
$warmSummary = Get-Content -Raw -LiteralPath (Join-Path $warmOutput 'goal-next-auto.summary.json') | ConvertFrom-Json
$warmAuditInvocations = @(Get-Content -LiteralPath (Join-Path $warmRoot 'completion-audit-invocations.jsonl'))
```

Require two invocation-ledger rows, no preflight result artifact, and an explicit reuse reason:

```powershell
Assert-True 'warm Desktop-only path keeps packet and final audits only' (
    @($warmAuditInvocations).Count -eq 2 -and
    -not (Test-Path -LiteralPath (Join-Path $warmOutput 'awx-mcp-completion-audit.preflight.result.json')) -and
    $warmSummary.completionAuditPreflight.executed -eq $false -and
    $warmSummary.completionAuditPreflight.reuseAllowed -eq $true -and
    [string]$warmSummary.completionAuditPreflight.reason -eq 'fresh-safe-canonical-reuse'
) "auditInvocations=$($warmAuditInvocations.Count)"
```

Retain the existing missing-canonical three-invocation end-to-end assertion. Load the actual bootstrap helper definitions through PowerShell AST for a fast table test of malformed, missing timestamp, invalid timestamp, stale, future, and unsafe reason codes without running six redundant full wrappers.

- [x] **Step 2: Run RED and verify the intended failure**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto_tests.ps1
```

Expected: FAIL because the warm fixture still executes preflight and records three completion-audit invocations.

- [x] **Step 3: Add a fail-closed bootstrap decision helper**

Add `Get-CompletionAuditBootstrapDecision` near the existing JSON/count helpers. It must:

```powershell
function Get-CompletionAuditBootstrapDecision {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [int]$MaxAgeSeconds = 86400,
        [int]$FutureToleranceSeconds = 300
    )

    $path = Join-Path $ProjectRoot 'var\codex-smoke\awx-mcp-completion-audit-current.json'
    $result = [ordered]@{
        reuseAllowed = $false
        reason = 'canonical-missing'
        ageSeconds = -1
        maxAgeSeconds = $MaxAgeSeconds
        secretHits = 0
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return $result }

    $raw = Get-Content -Raw -LiteralPath $path -ErrorAction SilentlyContinue
    $artifact = Read-JsonObjectFromFile -Path $path
    if ($null -eq $artifact -or $artifact -is [System.Array]) {
        $result.reason = 'canonical-malformed'
        return $result
    }

    $declaredSecretHits = (Get-SafeCountValue $artifact.secretHits) +
        (Get-SafeCountValue $artifact.secretPatternHits) +
        (Get-SafeCountValue $artifact.rawSecretPatternHits)
    $result.secretHits = $declaredSecretHits + (Count-SecretPatternHits $raw)
    if ($result.secretHits -gt 0) {
        $result.reason = 'canonical-unsafe-secret-hits'
        return $result
    }

    $generatedAt = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$artifact.generatedAt, [ref]$generatedAt)) {
        $result.reason = 'canonical-generated-at-invalid'
        return $result
    }
    $result.ageSeconds = [int][math]::Floor(([DateTimeOffset]::UtcNow - $generatedAt.ToUniversalTime()).TotalSeconds)
    if ($result.ageSeconds -lt (-1 * $FutureToleranceSeconds)) { $result.reason = 'canonical-future-dated'; return $result }
    if ($result.ageSeconds -gt $MaxAgeSeconds) { $result.reason = 'canonical-stale'; return $result }

    $result.reuseAllowed = $true
    $result.reason = 'fresh-safe-canonical-reuse'
    return $result
}
```

- [x] **Step 4: Gate only the preflight audit**

Before the current preflight block, compute the decision. When `reuseAllowed=true`, use `New-SkippedProcessCapture` with reason `fresh-safe-canonical-reuse`; otherwise execute and copy the existing preflight result exactly as before. Leave packet and final blocks unchanged.

- [x] **Step 5: Expose truthful summary evidence**

Add this top-level summary object and matching breadcrumb-tool-step fields:

```powershell
completionAuditPreflight = [ordered]@{
    executed = [bool]$completionAuditPreflight.ProcessExecuted
    reuseAllowed = [bool]$completionAuditBootstrap.reuseAllowed
    reason = [string]$completionAuditBootstrap.reason
    ageSeconds = [int]$completionAuditBootstrap.ageSeconds
    maxAgeSeconds = [int]$completionAuditBootstrap.maxAgeSeconds
    secretHits = [int]$completionAuditBootstrap.secretHits
}
```

- [x] **Step 6: Run GREEN and focused companion tests**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\goal_next_auto_tests.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\smb_decommission_debug_probe_tests.ps1
```

Expected: both scripts exit `0` with `failed=0`; warm path records two audits and cold path records three.

- [x] **Step 7: Run repository gates**

Run with Desktop cache isolation:

```powershell
$env:AWX_AGENT_HOST = 'desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop'
$env:GRADLE_USER_HOME = "$env:USERPROFILE\.gradle-awx-desktop"
$pcd = "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $pcd
```

Then run a changed-file count-only secret scan and verify no top-level PatchDrop patch or source lease appeared.
