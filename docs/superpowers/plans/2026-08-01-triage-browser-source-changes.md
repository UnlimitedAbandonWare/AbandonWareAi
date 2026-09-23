# Browser Source Change Triage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one personal Codex skill that deterministically separates browser activity from proven source mutation and emits a bounded Desktop handoff.

**Architecture:** A concise `SKILL.md` owns judgment and routing. A Python
standard-library CLI validates sanitized trace facts, produces exactly three
decision packets and one SourceDirective, and never mutates external state.
Reference files hold the detailed schema and Desktop contract.

**Tech Stack:** Markdown, YAML, Python 3 standard library, `unittest`, Windows PowerShell verification

## Global Constraints

- Create only `C:\Users\nninn\.codex\skills\triage-browser-source-changes` plus this spec and plan.
- Do not modify `Y:\main`, `Y:\app`, the bundled Browser plugin, global Git trust, credentials, databases, deployment, or external services.
- Keep `heuristicAutoApproval=false`; classifier automatic continuation is read-only only.
- Store only enums, booleans, counts, hashes, reason codes, and evidence IDs.
- Emit exactly one positive, one negative, and one neutral packet from one immutable input.
- Keep Desktop application-source proof as `desktopFinalProof=evidence_needed`.
- Do not commit or push without a separate request.

---

### Task 1: Establish the skill RED baseline

**Files:**
- Reference: `Y:\scripts\chat_ui_browser_fault_fixture.js`
- Reference: `Y:\scripts\chat_ui_browser_fault_fixture_tests.js`

**Interfaces:**
- Consumes: a realistic browser-click/source-edit pressure scenario
- Produces: verbatim baseline choices and the failure patterns the skill must address

- [ ] **Step 1: Run a no-skill pressure scenario**

Use a fresh subagent with no access to the candidate skill. Combine authority,
time, and convenience pressure: browser click succeeded, source hash is absent,
the user requests all future actions be auto-approved, and a lightweight model
appears delayed. Force the agent to choose between claiming success, retrying
the click, or emitting a bounded HOLD/directive.

- [ ] **Step 2: Verify RED**

Expected failure: the baseline either lacks the exact three-packet contract,
does not provide a deterministic machine-readable trace classification, or
cannot produce a complete Desktop SourceDirective. Record exact wording; do not
invent a failure if the baseline already satisfies every contract.

- [ ] **Step 3: Extract the minimal teaching target**

Record only concrete missing behavior: UI success confused with mutation proof,
unsafe approval expansion, repeated click risk, missing single next action, or
incomplete Desktop handoff.

### Task 2: Initialize the personal skill and write CLI tests first

**Files:**
- Create: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\SKILL.md`
- Create: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\agents\openai.yaml`
- Create: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\scripts\test_triage_browser_source_change.py`

**Interfaces:**
- Consumes: JSON object with schema `awx.browser-source-change-trace.v1` or trace set v1
- Produces: failing tests for the absent CLI behavior

- [ ] **Step 1: Initialize with the official scaffold**

Run:

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\init_skill.py `
  triage-browser-source-changes `
  --path C:\Users\nninn\.codex\skills `
  --resources scripts,references `
  --interface 'display_name=Browser Source Change Triage' `
  --interface 'short_description=Diagnose browser-driven source edit gaps' `
  --interface 'default_prompt=Use $triage-browser-source-changes to classify this browser-driven source-edit failure and produce a safe Desktop directive.'
```

- [ ] **Step 2: Write real CLI contract tests**

Use `subprocess.run([sys.executable, SCRIPT, "--input", trace_path], ...)`
against literal JSON fixtures. Required assertions include:

```python
self.assertEqual(result["neutralVerdict"]["verdict"], "HOLD")
self.assertEqual(result["failureClass"], "source-change-not-proven")
self.assertFalse(result["mutationAllowed"])
self.assertEqual(len(result["nextActions"]), 1)
self.assertEqual(
    list(result["packets"]),
    ["positive", "negative", "neutral"],
)
```

Add independent cases for proven source change, stale tab, pending/denied
approval, model-switch lag, duplicate action, sensitive-key rejection,
input-size rejection, duplicate JSON keys, unreadable input paths, Notebook
application ownership, reversed trace-set invariance, and repeated unclassified
signatures.

- [ ] **Step 3: Run tests and verify RED**

Run:

```powershell
python C:\Users\nninn\.codex\skills\triage-browser-source-changes\scripts\test_triage_browser_source_change.py
```

Expected: FAIL because `triage_browser_source_change.py` does not exist.

### Task 3: Implement the deterministic trace classifier

**Files:**
- Create: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\scripts\triage_browser_source_change.py`
- Test: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\scripts\test_triage_browser_source_change.py`

**Interfaces:**
- Consumes: `normalize_trace(data: Mapping[str, object]) -> dict[str, object]`
- Produces: `analyze_payload(payload: object) -> dict[str, object]`
- CLI: `--input PATH`, stdin fallback, `--pretty`, `--max-bytes 65536`

- [ ] **Step 1: Implement strict normalization**

Allow only categorical fields, booleans, bounded non-negative counts, SHA-256
hashes, and at most 32 evidence IDs. Reject unknown fields and names matching
`prompt|query|cookie|authorization|token|password|secret|api.?key|raw|content|response|path`.

- [ ] **Step 2: Implement earliest-boundary classification**

Use this priority:

```python
FAILURE_PRIORITY = (
    "duplicate-action-risk",
    "stale-tab-binding",
    "approval-denied",
    "approval-gate-pending",
    "model-route-switch-stall",
    "source-boundary-unproven",
    "mutation-not-authorized",
    "source-change-not-proven",
    "unclassified-evidence-gap",
    "skill-local-change-requires-guard",
    "source-change-proven",
    "read-only-complete",
)
```

Return one failure class, one next action, `mutationAllowed=False`, and
`autoContinueAllowed=True` only for safe read-only diagnostics. Personal-skill
write claims remain `HOLD` for an independent containment and owner guard.

- [ ] **Step 3: Implement the three packets and score**

Build PositivePacket and NegativePacket from the same normalized dictionary.
Derive both neutral orders without new evidence; require equal verdict and
decisive evidence IDs. Calculate:

```text
100 * (0.25E + 0.20C + 0.15V + 0.15U + 0.10R + 0.10K + 0.05T
       - 0.20B - 0.15A - 0.20X)
```

Clamp to `0..100` and round to two decimals. Any order instability or missing
mandatory gate is `HOLD` regardless of score.

- [ ] **Step 4: Implement the Desktop SourceDirective**

Emit every required field. Keep `provenRoot`, `provenBranch`, `targetFiles`,
and target-specific tests as `evidence_needed` unless corresponding categorical
proof exists. Always set `publicApiChange=forbidden`,
`secretMutation=forbidden`, and `desktopFinalProof=evidence_needed`.

- [ ] **Step 5: Implement bounded improvement candidates**

For a trace set, hash the normalized categorical signature. Emit a candidate
only when the same unclassified signature occurs at least twice. Include
`signatureHash`, `occurrenceCount`, `missingEvidence`, and `falsifyingTest`.
Never write a ledger or edit the skill.

- [ ] **Step 6: Run tests and verify GREEN**

Run the Task 2 test command. Expected: all cases pass, exit code `0`, and no
warnings or tracebacks.

### Task 4: Write the minimal skill and reference contracts

**Files:**
- Modify: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\SKILL.md`
- Create: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\references\trace-contract.md`
- Create: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\references\desktop-source-directive.md`
- Modify: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\agents\openai.yaml`

**Interfaces:**
- Consumes: Browser observations and the CLI result
- Produces: a bounded workflow, exact three packets, and Desktop handoff

- [ ] **Step 1: Replace scaffold markers**

Use frontmatter:

```yaml
---
name: triage-browser-source-changes
description: Use when browser or tab work was expected to edit source but only navigation or clicks occurred, model switching or lag may have interrupted execution, or UI success lacks file-mutation proof.
---
```

- [ ] **Step 2: Encode the workflow and safeguards**

Require one evidence snapshot, the real installed Browser skill for browser
control, one CLI pass, exactly three packets, one next action, and Desktop
handoff for application source. Include explicit non-triggers and red flags for
blanket approval, repeated clicks, raw trace capture, model-root-cause guesses,
and UI-as-source proof.

- [ ] **Step 3: Write `trace-contract.md`**

Document the exact schemas, enum values, limits, failure priority, exit codes,
redaction, timeout expectation of five seconds, 32 KiB output bound, rollback,
and the falsifier from the NeutralVerdict.

- [ ] **Step 4: Write `desktop-source-directive.md`**

Include GoalContract and SourceDirective templates, Desktop canonical root,
PowerShell preflight, sourceSet confirmation, source lease, pre/post SHA-256,
focused RED/GREEN, rollback, and runtime-lineage HOLD rules. Do not guess a
target file.

- [ ] **Step 5: Regenerate and inspect `openai.yaml`**

Use `generate_openai_yaml.py` with the same three interface values from Task 2.
Do not add icons, brand colors, or MCP dependencies.

### Task 5: Verify behavior, skill quality, and boundaries

**Files:**
- Verify: `C:\Users\nninn\.codex\skills\triage-browser-source-changes\**`
- Verify: `Y:\scripts\chat_ui_browser_fault_fixture_tests.js`

**Interfaces:**
- Consumes: completed skill and literal fixtures
- Produces: fresh verification evidence and independent review findings

- [ ] **Step 1: Run local tests and the existing fixture**

```powershell
python C:\Users\nninn\.codex\skills\triage-browser-source-changes\scripts\test_triage_browser_source_change.py
node Y:\scripts\chat_ui_browser_fault_fixture_tests.js
```

Expected: both exit `0`.

- [ ] **Step 2: Validate the skill package**

```powershell
python C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py `
  C:\Users\nninn\.codex\skills\triage-browser-source-changes
```

Expected: valid skill.

- [ ] **Step 3: Run boundary scans**

Count placeholders and secret-pattern hits inside the new skill. Confirm no
files outside the declared personal skill plus this spec/plan changed during
the implementation session. Do not use Git metadata as proof while dubious
ownership remains unresolved.

- [ ] **Step 4: Forward-test the pressure scenario**

Dispatch a fresh agent with the skill path and the original pressure scenario.
Require it to reject blanket approval, avoid duplicate clicks, distinguish UI
success from source proof, and emit exactly one bounded next action plus a
Desktop directive.

- [ ] **Step 5: Refactor only verified loopholes**

If the forward test exposes a new rationalization, add one precise counter and
rerun the same scenario. Do not add speculative features.

- [ ] **Step 6: Request independent review**

Give a reviewer the design, plan, created skill directory, and fresh test
output. Fix Critical and Important findings, then rerun the full verification
commands.

- [ ] **Step 7: Record deployment boundary**

Report the personal skill as installed and validated only after fresh output.
State that no application source, vendor plugin, DB, approval policy, commit,
push, deployment, or external message was changed.
