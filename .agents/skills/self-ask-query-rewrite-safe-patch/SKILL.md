---
name: self-ask-query-rewrite-safe-patch
description: Use when Codex is asked to fix ambiguous source code, fill a blank implementation
---

# Self-Ask Query Rewrite Safe Patch

## Overview

Use this skill before source edits when the user's requested fix is underspecified, blank-fill style, creative, stochastic, or likely to sprawl. Convert the request into an evidence-backed patch brief before touching runtime source.

Core rule: exploration can propose options; patch execution must be driven by current files, tests, logs, and the smallest verified source seam.

## Evidence Gate

Before any source edit, collect and summarize:

- Active repo root.
- Declared-target preimage hashes and any overlapping source-edit lease.
- Whether a real Git index/ref operation or live git writer is in progress (a bare `.git/index.lock` file alone does not stop scoped edits).
- Active sourceSets and build files.
- Relevant source files.
- Relevant tests.
- Failing logs or reproduction command output.
- PatchDrop pending state when in demo-1.

If any required evidence is missing, stop with:

```text
evidence_needed: <missing artifact> / verify with <exact command>
```

For demo-1, treat `AGENTS.md`, active sourceSet proof, `source_scan`, PatchDrop state, and Desktop command output as higher authority than memory, stale reports, or prompt wording.

## Input Contract

Capture the user's request as an input packet before rewriting it. Preserve the user's wording, but add enough structure that the next agent can tell what is known, unknown, and unsafe to assume.

| Input field | Required detail | Missing action |
| --- | --- | --- |
| `raw_request` | The exact user ask, including blank-fill text, pasted snippets, tags, or constraints. | Ask for the missing ask or stop if there is no actionable request. |
| `task_type` | One of: `ambiguous_fix`, `blank_fill`, `creative_solution`, `controlled_randomness`, `rewrite_before_patch`, `evidence_comparison`. | Infer from wording only if evidence supports it; otherwise mark `ambiguous_task_type`. |
| `repo_scope` | Root path, branch, active sourceSet, and files allowed to change. | Run the repo/sourceSet commands or stop with `evidence_needed`. |
| `evidence_inputs` | Source files, tests, logs, screenshots, prior diffs, or reproduction output that support the issue. | Name each missing artifact and the exact command or file needed. |
| `constraints` | Non-negotiables: APIs, secrets, prompt boundaries, migrations, UI proof, external lanes, or user-owned dirty files. | Re-read repo rules and nearby files before selecting scope. |
| `randomness_bounds` | When creativity is requested, state what may vary and what must remain deterministic. | Default to deterministic patching and mark creative latitude as missing. |

Accepted input is not proof. Treat it as a lead that must be checked against files and command output.

## Output Contract

Produce a patch brief with explicit artifacts and decisions. The output must be detailed enough that another Codex instance can execute the selected path without rereading the whole conversation.

| Output artifact | Detail required |
| --- | --- |
| `evidence_packet` | Root, branch, status summary, sourceSet/build proof, target files, target tests, log/repro result, PatchDrop state when relevant. |
| `self_ask_answers` | The five Self-Ask answers below, each tied to file, test, command, or `evidence_needed`. |
| `rewrite_options` | Exactly three prompts: conservative, exploratory, adversarial. Each includes seam, required evidence, command, stop condition, risk. |
| `selected_prompt` | The chosen prompt text, why it won, what assumption it freezes, and what would disprove it. |
| `patch_execution_brief` | Files allowed to edit, tests to add or run, rollback note, and external proof lanes that are in or out of scope. |
| `verification_ledger` | Command, expected success condition, observed result, failure class, retry decision, remaining evidence. |

If the correct outcome is no edit, output `no_patch_needed` only with current evidence that proves the live source already satisfies the rewritten request.

## I/O Detail Quality Bar

Use concrete, inspectable details. Do not inflate the output with vague prose.

| Weak detail | Strong detail |
| --- | --- |
| "Checked the repo." | `root=C:/.../src`, `branch=main`, `indexLock=false`, `status=dirty`, command used. |
| "Relevant tests exist." | Test path, test method or script name, and what behavior it covers. |
| "Logs indicate failure." | Failure class, timestamp or run id when available, command that produced it, and a short redacted excerpt. |
| "Patch the service." | Exact file path, method/branch, expected behavior, and why adjacent files are out of scope. |
| "Run verification." | Command, expected success condition, observed result, and retry decision. |
| "Need more info." | `evidence_needed: <artifact> / verify with <exact command>`. |

For input detail:

- Preserve the original request separately from the rewritten request.
- Distinguish user intent, repo evidence, and agent inference.
- Mark each external lane as `required`, `supporting`, or `out_of_scope`.
- Capture randomness as a bounded choice, seed, candidate count, or deterministic fallback.

For output detail:

- Include file paths and line numbers when current files were inspected.
- Quote only short snippets; prefer summaries for long logs or prompts.
- Use status words consistently: `selected`, `rejected`, `evidence_needed`, `no_patch_needed`, `verified`.
- Keep secret-adjacent evidence as booleans, counts, hashes, or redacted tails.

## Self-Ask Rewrite

After the evidence gate, write a compact Self-Ask block:

| Question | Required answer |
| --- | --- |
| What behavior is actually requested? | One sentence, no implementation. |
| What evidence proves the current behavior? | File/test/log references only. |
| What is ambiguous or creative? | Name the unknowns and acceptable variation. |
| What must not change? | Source boundaries, secrets, APIs, prompt boundaries, or safety gates. |
| What is the smallest patch seam? | One file or narrow file set, with fallback if evidence disproves it. |

Do not edit source from the original vague request. Edit only after the rewritten brief identifies evidence, scope, verification, and rollback.

## Three Alternative Prompts

Generate exactly three alternative patch prompts before choosing scope:

1. **Conservative prompt**: Preserve behavior, patch the narrowest confirmed seam, prefer characterization tests.
2. **Exploratory prompt**: Allow a creative or emergent approach, but keep source edits behind evidence and focused tests.
3. **Adversarial prompt**: Try to disprove the requested fix, look for stale assumptions, inactive sourceSets, missing logs, and safer no-patch outcomes.

For each prompt include:

- Candidate patch seam.
- Evidence required before edit.
- Test or reproduction command.
- Stop condition.
- Risk if the prompt is wrong.

Choose one prompt for execution and state why. If two prompts are tied, run one more cheap evidence command instead of guessing.

## Exploration vs Patch Execution

Keep these phases separate:

| Phase | Allowed | Forbidden |
| --- | --- | --- |
| Exploration | Read files, inspect logs, run safe probes, draft alternatives, create throwaway notes in the answer. | Runtime source edits, secret reads, schema mutations, broad rewrites. |
| Patch execution | Add or update focused tests, patch the chosen seam, run narrow verification, broaden only when risk requires it. | Editing unrelated files, hiding missing evidence, turning optional proof into fake success. |

If exploration used randomness or model creativity, freeze the selected prompt before editing. Record the selected assumption as a testable claim, not as truth.

## Patch Rules

- Prefer a failing test or characterization test before behavior changes.
- Patch active sourceSets only, unless build output proves another source root is active.
- Preserve public contracts, secret flow, prompt-builder boundaries, and existing safety tooling.
- Keep Browser, Computer, Supabase, Mac mini, Notebook, and PatchDrop evidence demand-driven unless the task explicitly targets those lanes.
- Treat missing optional credentials or external proof as `evidence_needed`, not success.
- Never print raw secrets, authorization headers, cookies, raw DB URLs, raw prompts, or full environment dumps.
- Stop if the next failure class changes, the patch scope expands beyond the brief, or verification fails for an unrelated reason.

## Verification Contract

Before claiming completion, report:

- Commands run.
- Expected success condition.
- Observed result.
- Failure classification when a command fails.
- Remaining `evidence_needed` entries.

Use count-only secret scans when files near credentials or config changed. Do not claim build, boot, browser, computer-use, Supabase, Mac mini, Notebook, or PatchDrop proof unless current command output proves it.

## Detailed Example

Input:

```text
raw_request: "Fill the blank so remembered-value questions use recent chat history correctly."
task_type: blank_fill + ambiguous_fix
repo_scope: demo-1 Desktop root, main branch, active backend sourceSet only
evidence_inputs: ChatWorkflow source, focused history fallback tests, failing Korean ask-later transcript if available
constraints: no secret reads, no provider calls, Browser proof only if UI changed
randomness_bounds: none; deterministic classifier/refactor only
```

Output:

```text
evidence_packet:
  root: C:/AbandonWare/demo-1/demo-1/src
  branch: main
  sourceSet: main/java + src/test/java
  targetFiles: main/java/.../ChatWorkflow.java, src/test/java/.../ChatWorkflow...Test.java
  missing: failing transcript / verify with <exact command or file path>

self_ask_answers:
  requested_behavior: remembered-value questions should answer from recent chat history when evidence exists
  current_evidence: <file/test/log references>
  ambiguity: phrase coverage vs shared classifier behavior
  non_negotiables: no prompt-boundary rewrite, no provider calls, no secret output
  smallest_seam: shared history-question classifier, disproved if failing log points elsewhere

rewrite_options:
  conservative: add characterization test, patch one classifier branch
  exploratory: consolidate phrase table if duplicate branches are proven
  adversarial: prove no patch is needed if existing test/log already covers behavior
selected_prompt: conservative, unless duplicate branches are confirmed

patch_execution_brief:
  allowed_edits: target source + focused test only
  verification: focused test first, then narrow Gradle gate
  rollback: revert only the changed files
```

## Output Shape

When using this skill for a source task, include these sections before editing or in the final report:

```md
## Input Packet
- raw_request:
- task_type:
- repo_scope:
- evidence_inputs:
- constraints:
- randomness_bounds:

## Evidence Packet
- repo/root/branch/status:
- sourceSet/build proof:
- source/test/log proof:
- PatchDrop/external lane state:
- missing evidence:

## Self-Ask
- requested behavior:
- current evidence:
- ambiguity:
- non-negotiables:
- smallest seam:

## Rewrite Options
- conservative: seam / evidence / command / stop / risk
- exploratory: seam / evidence / command / stop / risk
- adversarial: seam / evidence / command / stop / risk
- selected prompt:
- frozen assumption:
- disproof condition:

## Patch And Verification
- allowed edits:
- changed files:
- commands and expected results:
- observed results:
- failure classes:
- remaining evidence_needed:
- rollback:
```

Keep the report short. The skill exists to reduce patch sprawl, not to create a second architecture document.

## Common Mistakes

| Mistake | Correction |
| --- | --- |
| Editing from the user's vague wording | Rewrite first, then patch from evidence. |
| Treating a creative idea as proof | Convert it into a testable claim. |
| Generating many options | Generate exactly three alternatives. |
| Asking for Browser/Computer/Supabase proof by default | Require those only when the changed surface needs them. |
| Using stale memory or prior reports as authority | Re-check live files and command output. |
| Declaring no-patch success without proof | State `no_patch_needed` only after current evidence supports it. |
