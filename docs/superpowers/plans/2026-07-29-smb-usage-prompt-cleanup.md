# SMB Usage Prompt Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the standalone SMB decommission prompt accurate, bounded, source/profile-aware, and directly usable from both its repo canonical path and the user's attachment copy.

**Architecture:** Keep one canonical standalone `/goal` under `agent-prompts` and one byte-identical attachment copy. Add a focused Python contract test first, then make bounded prompt-only edits: execution profiles, dynamic evidence intake, bounded scans, valid PowerShell secret scanning, request-scoped count-only proof, and verification-by-change-type. No application source, public API, manifest registration, or external evidence mutation is allowed.

**Tech Stack:** Markdown prompt artifact, Python 3 `unittest`, PowerShell command examples, Git path-scoped verification.

## Global Constraints

- Canonical root is `C:\AbandonWare\demo-1\demo-1\src`.
- Do not modify `main/java`, `main/resources`, `src/test/java`, `src/test/resources`, `app/src/main/java_clean`, or `app/src/main/resources`.
- Do not add a public API, DB/DDL change, provider call, background daemon, agent broker, or automatic producer dispatch.
- Preserve PatchDrop janitor safety and make external producer work opt-in.
- Browser, Computer, Supabase, Mac mini, and Notebook remain supporting evidence unless explicitly required.
- Persist only count/hash-only proof; never persist raw prompts, options, responses, proof lines, secrets, headers, cookies, or environment dumps.
- Keep the attachment byte-identical to the canonical prompt after the repo-owned file passes its focused test.

---

### Task 1: Add the standalone prompt RED contract

**Files:**
- Create: `scripts/test_smb_decommission_usage_prompt.py`
- Test: `scripts/test_smb_decommission_usage_prompt.py`

**Interfaces:**
- Consumes: `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md` as UTF-8 text.
- Produces: a zero-exit focused contract covering prompt shape, bounded scans, execution profiles, request-proof rules, valid PowerShell, and external-evidence boundaries.

- [ ] **Step 1: Write the failing contract test**

```python
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
PROMPT = ROOT / "agent-prompts" / "codex_9h_smb_decommission_usage_optimization_goal.md"


def audit_prompt(text: str) -> list[str]:
    violations: list[str] = []
    lines = text.splitlines()
    if not lines or lines[0] != "/goal" or lines[-1] != "[DONE]":
        violations.append("goal_shape")
    required = (
        "agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md",
        "desktop_source_safe_patch",
        "prompt_artifact_only",
        "snapshotFresh=false",
        "sourceTruthFingerprint",
        "statusCount",
        "do_not_repeat_until_input_changes",
        "awx.chat-request-proof.v1",
        "[LLM_REQUEST_PROOF]",
        "providerAttemptObservedCount",
        "wireAttemptObservedCount",
        "modelSuccessClaimAllowed=false",
        "rawProofLinesStored=false",
        "supporting_evidence_needed",
        "nextAction=none_for_desktop_only",
    )
    for marker in required:
        if marker not in text:
            violations.append("missing:" + marker)
    if any(stale in text for stale in ("fileCount=2018", "fileCount=109", "fileCount=841")):
        violations.append("stale_snapshot_count")
    if re.search(r"\$hits\s*=\s*Select-String[\s\S]{0,500}?\n\s*-Recurse\b", text):
        violations.append("unsupported_select_string_recurse")
    if "Never infer provider/wire success from `clientHttpResponse`" not in text:
        violations.append("provider_wire_inference_guard")
    if "Supabase live proof is blocked by missing project ref/auth." in text:
        violations.append("supabase_optional_stop_regression")
    return violations


class SmbUsagePromptContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = PROMPT.read_text(encoding="utf-8")

    def test_current_prompt_has_no_contract_violations(self):
        self.assertEqual([], audit_prompt(self.text))

    def test_audit_detects_stale_snapshot_counts(self):
        self.assertIn("stale_snapshot_count", audit_prompt(self.text + "\nfileCount=2018\n"))

    def test_audit_detects_unsupported_secret_scan(self):
        mutated = self.text + "\n$hits = Select-String -Path $files\n  -Recurse\n"
        self.assertIn("unsupported_select_string_recurse", audit_prompt(mutated))

    def test_audit_detects_missing_prompt_only_profile(self):
        mutated = self.text.replace("prompt_artifact_only", "prompt_profile_removed")
        self.assertIn("missing:prompt_artifact_only", audit_prompt(mutated))

    def test_audit_detects_provider_wire_inference_regression(self):
        guard = "Never infer provider/wire success from `clientHttpResponse`"
        mutated = self.text.replace(guard, "Provider inference guard removed")
        self.assertIn("provider_wire_inference_guard", audit_prompt(mutated))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the test to verify RED**

Run:

```powershell
python -X utf8 scripts\test_smb_decommission_usage_prompt.py
```

Expected: FAIL because the canonical path marker, execution profiles, dynamic snapshot marker, source-truth fingerprint, bounded-scan markers, and request-proof contract are absent; the unsupported `Select-String ... -Recurse` example is still present.

- [ ] **Step 3: Commit the RED contract only if requested by the active execution workflow**

```powershell
git add -- scripts/test_smb_decommission_usage_prompt.py
git diff --cached --name-only
git commit -m "test: define SMB usage prompt contract"
```

The staged path list must contain exactly the test file.

---

### Task 2: Repair the canonical standalone prompt

**Files:**
- Modify: `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`
- Test: `scripts/test_smb_decommission_usage_prompt.py`

**Interfaces:**
- Consumes: Task 1 assertions and current repo paths.
- Produces: one standalone `/goal` supporting `desktop_source_safe_patch` and `prompt_artifact_only` profiles with bounded local evidence reuse.

- [ ] **Step 1: Add canonical ownership and execution profiles**

Add directly below the title:

```markdown
> Canonical prompt source: `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`
> Default profile: `desktop_source_safe_patch`
> If the user says source must not change, switch to `prompt_artifact_only` and limit edits to `agent-prompts/**`, `.agents/skills/**`, `docs/**`, and focused `scripts/**` harness/tests.
```

Make the active profile explicit in the report. The source-safe profile may patch active sourceSets only after a RED seam. The prompt-only profile must never patch application source or run Gradle merely for ceremony.

- [ ] **Step 2: Replace stale numeric snapshot values with dynamic intake**

Replace fixed file counts and prompt-authoring-time provider state with:

```text
snapshotFresh=false
snapshotAuthority=supporting_only
refreshRequired=true
reuseUntil=HEAD_or_target_hash_or_lock_state_changes
```

Require one `source_scan` per stable `sourceTruthFingerprint`; reuse its count-only summary until Git HEAD, candidate-file hash, index-lock state, or PatchDrop top-level queue changes.

- [ ] **Step 3: Bound preflight and search output**

Replace an unbounded `git status --short` dump with:

```powershell
$status = @(git status --short 2>$null)
Write-Host "[AWX][desktop] statusCount=$($status.Count)"
```

After candidate selection, query only those target paths. For `rg` scans, retain total hit/file counts and no more than the first 80 decision-changing rows. Add:

```text
sourceTruthFingerprint=<HEAD + target hashes + index lock + PatchDrop top-level count>
summaryReuseCondition=unchanged_fingerprint
repeatProbeRecommendation=do_not_repeat_until_input_changes
```

- [ ] **Step 4: Resolve prompt/script/source boundary contradictions**

State that active sourceSet restrictions govern application source edits, while an explicitly selected tooling/prompt lane may edit its owning `scripts/**`, `agent-prompts/**`, `.agents/skills/**`, or `docs/**` file. Never describe those files as active Java sourceSets.

- [ ] **Step 5: Add request-scoped count-only proof lane**

Add a prompt/harness-only lane using the existing `[LLM_REQUEST_PROOF]` listener output and schema:

```text
schemaVersion=awx.chat-request-proof.v1
requestHashCount
sequenceCount
appPromptHashSha256Count
appOptionsHashSha256Count
appResponseHashSha256Count
adapterAttemptObservedCount
clientHttpExchangeObservedCount
clientHttpResponseObservedCount
providerAttemptObservedCount
wireAttemptObservedCount
providerEvidenceStatus=observed_positive|observed_zero|not_emitted
correlationStatus=verified|partial|ambiguous|evidence_needed
modelSuccessClaimAllowed=false
rawProofLinesStored=false
```

Require a bounded listener tail starting from a pre-request byte offset and the exact request hash. Explicitly forbid inferring provider/wire success from `clientHttpResponse` or a response hash. Public APIs and source instrumentation remain forbidden in `prompt_artifact_only`.

- [ ] **Step 6: Repair the PowerShell secret scan**

Replace unsupported `Select-String -Recurse` with file enumeration:

```powershell
$files = @(
  Get-ChildItem -LiteralPath ".\main\java" -Recurse -File -Filter "*.java" -EA SilentlyContinue
  Get-ChildItem -LiteralPath ".\main\resources" -Recurse -File -Include "*.yml","*.yaml" -EA SilentlyContinue
  Get-ChildItem -LiteralPath ".\scripts" -File -Include "*.ps1","*.py" -EA SilentlyContinue
)
$hits = if ($files.Count -gt 0) {
  @(Select-String -Path $files.FullName -Pattern $secretPattern -EA SilentlyContinue)
} else { @() }
Write-Host "[AWX][desktop][security] secretHits=$($hits.Count)"
```

Define `$secretPattern` with the existing patterns and never print matched lines.

- [ ] **Step 7: Make verification proportional to the active profile**

Add this decision table:

```text
prompt_artifact_only -> focused prompt/harness tests + UTF-8/placeholder/secret checks; no Gradle
script_only          -> focused PowerShell/Python tests; Gradle only if build contract changed
source_safe_patch    -> focused test, sourceSet/LangChain4j gates, compile, affected tests
ui_only              -> focused UI contract plus Browser when decision-changing
supabase_explicit    -> read-only project-scoped proof only when ref/auth are proven
```

- [ ] **Step 8: Run the focused test to verify GREEN**

Run:

```powershell
python -X utf8 scripts\test_smb_decommission_usage_prompt.py
```

Expected: all tests PASS.

---

### Task 3: Synchronize the attachment copy

**Files:**
- Modify: `C:\Users\nninn\.codex\attachments\11d14676-4719-4d37-b5c0-0da75c6295ce\pasted-text.txt`
- Read: `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`

**Interfaces:**
- Consumes: the Task 2 GREEN canonical prompt.
- Produces: a UTF-8 attachment copy with identical normalized bytes and the same first/last line contract.

- [ ] **Step 1: Apply the canonical prompt changes to the attachment**

Use the same reviewed hunks. Do not add attachment-only instructions or machine-specific secrets.

- [ ] **Step 2: Verify exact normalized equality**

```powershell
$repo = Get-Content -LiteralPath ".\agent-prompts\codex_9h_smb_decommission_usage_optimization_goal.md" -Raw -Encoding UTF8
$attachment = Get-Content -LiteralPath "C:\Users\nninn\.codex\attachments\11d14676-4719-4d37-b5c0-0da75c6295ce\pasted-text.txt" -Raw -Encoding UTF8
$repoNormalized = $repo -replace "`r`n", "`n"
$attachmentNormalized = $attachment -replace "`r`n", "`n"
if ($repoNormalized -cne $attachmentNormalized) { throw "attachment-sync-mismatch" }
Write-Host "attachmentSync=PASS"
```

Expected: `attachmentSync=PASS`.

---

### Task 4: Verify the standalone artifact and usage guard

**Files:**
- Test: `scripts/test_smb_decommission_usage_prompt.py`
- Test: `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`
- Test: attachment copy

**Interfaces:**
- Consumes: Tasks 1-3 artifacts.
- Produces: count-only artifact readiness evidence without claiming runtime source completion.

- [ ] **Step 1: Run the focused prompt test**

```powershell
python -X utf8 scripts\test_smb_decommission_usage_prompt.py
```

Expected: PASS.

- [ ] **Step 2: Verify standalone status intentionally skips manifest build**

```powershell
$needle = "codex_9h_smb_decommission_usage_optimization_goal.md"
$registrationCount = @(Select-String -LiteralPath ".\agent-prompts\prompts.manifest.yaml" -SimpleMatch $needle -EA SilentlyContinue).Count
Write-Host "registrationCount=$registrationCount"
```

Expected: `registrationCount=0`; the file is a paste-ready standalone `/goal`, not an agent pack.

- [ ] **Step 3: Run shape, placeholder, and secret checks**

```powershell
$paths = @(
  ".\agent-prompts\codex_9h_smb_decommission_usage_optimization_goal.md",
  ".\scripts\test_smb_decommission_usage_prompt.py",
  "C:\Users\nninn\.codex\attachments\11d14676-4719-4d37-b5c0-0da75c6295ce\pasted-text.txt"
)
$placeholderPattern = '\b(' + ('TB' + 'D') + '|' + ('TO' + 'DO') + '|' + ('FIX' + 'ME') + '|' + ('X' + 'XX') + ')\b'
$placeholderHits = @(Select-String -Path $paths -Pattern $placeholderPattern -EA SilentlyContinue).Count
$secretHits = @(Select-String -Path $paths -Pattern 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}' -EA SilentlyContinue).Count
Write-Host "placeholderHits=$placeholderHits secretPatternHits=$secretHits"
```

Expected: both counts are zero. Do not output matched text.

- [ ] **Step 4: Verify repo edit scope**

```powershell
git status --short -- `
  agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md `
  scripts/test_smb_decommission_usage_prompt.py `
  docs/superpowers/plans/2026-07-29-smb-usage-prompt-cleanup.md
```

Expected: only the planned prompt/test/plan paths. Application source is absent.

---

### Task 5: Commit only repo-owned artifacts and report the external copy

**Files:**
- Add: `agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md`
- Add: `scripts/test_smb_decommission_usage_prompt.py`
- Add: `docs/superpowers/plans/2026-07-29-smb-usage-prompt-cleanup.md`
- External synchronized copy: attachment path, not part of the Git commit

**Interfaces:**
- Consumes: all GREEN evidence from Task 4.
- Produces: one path-scoped documentation/test commit and a final report distinguishing repo proof from attachment synchronization.

- [ ] **Step 1: Stage the exact repo paths**

```powershell
git add -- `
  agent-prompts/codex_9h_smb_decommission_usage_optimization_goal.md `
  scripts/test_smb_decommission_usage_prompt.py `
  docs/superpowers/plans/2026-07-29-smb-usage-prompt-cleanup.md
git diff --cached --name-only
```

Expected: exactly three paths.

- [ ] **Step 2: Run the staged diff guard and commit**

```powershell
git diff --cached --check
git commit -m "docs: harden SMB usage optimization prompt"
```

- [ ] **Step 3: Report without overclaiming**

Report canonical and attachment paths, changed-file counts, focused-test result, attachment equality, registration count, placeholder/secret counts, and commit ID. State that prompt artifact readiness does not prove the future nine-hour source patch or positive provider/wire evidence.
