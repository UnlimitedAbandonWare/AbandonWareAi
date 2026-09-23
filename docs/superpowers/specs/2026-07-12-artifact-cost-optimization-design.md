# Artifact Cleanup and Cost Optimization Design

Date: 2026-07-12

Status: design approved in chat; implementation pending written-spec review

## Goal

Reduce the active artifact and repeated-audit cost of the Desktop-local demo-1 workflow without changing chatbot or LLM source behavior. Preserve current evidence contracts, PatchDrop safety, secret redaction, active source-set ownership, and honest runtime reporting.

The user permits one bounded Ollama restart as a verification action. That permission does not include an Ollama upgrade, reinstall, model pull, persistent environment change, or Java-side LLM recovery patch.

## Current evidence

- Canonical root: `C:\AbandonWare\demo-1\demo-1\src`
- Branch: `main`
- Index lock: absent
- Dirty worktree rows: 1002; all target files already contain unrelated changes
- Active PatchDrop top-level patches: 0; pending producer artifacts: 1 supporting item
- Source-scan secret-pattern hits: 0
- `var/codex-smoke`: 4,181 files and 360,337,872 bytes; the tree is untracked
- `sourceHealthScorecard` currently declares the whole `var/codex-smoke` JSON/NDJSON/text tree as a Gradle input
- `goal_next_auto.ps1` currently invokes completion audit at preflight, packet, and final stages
- Existing run evidence shows the stages are not interchangeable: preflight can lack command/collection packets, packet audit can still see an incomplete source-health contract, and final audit is the stage that proves both contracts together
- Ollama `0.31.2` listens on port 11434; the existing chatbot listens on port 18166

These values are an intake snapshot, not permanent constants. Implementation must remeasure them immediately before mutation.

## Scope

### In scope

1. Make source-health artifact selection canonical-first and bounded.
2. Replace the recursive Gradle artifact input tree with exact canonical inputs.
3. Skip the redundant preflight completion audit on a safe warm path while preserving the packet and final convergence checks.
4. Move obsolete generated smoke artifacts out of the active tree through a reversible quarantine operation.
5. Restart Ollama at most once when a pre-restart generation probe reproduces the runner failure.
6. Verify the current source on an isolated application port and perform a browser chat probe.

### Out of scope

- Java chatbot, Ollama adapter, retry, model-routing, or prompt changes
- Supabase writes, schema work, or authentication changes
- Ollama upgrade, reinstall, model download, or persistent configuration
- Removal of PatchDrop janitors, external-evidence schemas, or secret gates
- Deletion of tracked files, active browser profiles, current-run artifacts, canonical evidence, or unrelated dirty changes
- New background cleanup service, daemon, or orchestration framework

## Design

### 1. Canonical source-health inputs

`scripts/source_health_scorecard.py` will resolve `var/codex-smoke/awx-mcp-completion-audit-current.json` first. It may inspect legacy top-level `awx-mcp-completion-audit*.json` files only when the canonical file is absent or cannot be parsed. A valid but stale canonical file remains authoritative and must be reported as stale; a newer ad hoc file must not silently replace it.

`build.gradle.kts` will declare only these optional smoke inputs for `sourceHealthScorecard`:

- `var/codex-smoke/awx-mcp-completion-audit-current.json`
- `var/codex-smoke/goal-next-auto.status.json`

It will no longer register a recursive `fileTree` over JSON, NDJSON, and text artifacts. This changes Gradle invalidation and enumeration cost, not the evidence schema.

### 2. Conditional bootstrap and two-audit warm path

`scripts/goal_next_auto.ps1` will always retain:

- packet audit, which runs after command and collection packets exist and refreshes the canonical completion-audit input for final source-health generation;
- final audit, which runs after final source-health generation, owns the final canonical copy, and proves the converged contracts.

Before the initial source-health run, the wrapper will inspect the canonical completion audit. The warm path may reuse it and skip preflight only when it parses as an object, has current `generatedAt` evidence within the existing maximum age, and its redacted secret-hit counters are zero. The warm path therefore executes packet and final audits: two audit runs.

If the canonical file is missing, malformed, stale, future-dated, or unsafe, the cold path runs the existing preflight audit before continuing. The cold path retains preflight, packet, and final audits: three audit runs. This fallback preserves first-run convergence and fails closed instead of claiming a cost saving without reusable bootstrap evidence.

Exit code 4 or secret hits from every audit that executes remain `secret-leak-risk`. A nonzero final audit remains `evidence_needed` unless a stronger existing failure applies. Skipping preflight must be explicit in the summary with a bounded reason code; it must not be represented as a successful executed audit.

No public completion-audit schema field will be renamed or removed.

### 3. Reversible artifact quarantine

Cleanup is a one-shot, explicitly reviewed operation rather than a new recurring service. Before moving anything, implementation will produce a count-only inventory containing candidate count, byte count, age range, and path hashes. It will verify that every source and destination resolves under the intended `var` roots.

Eligible items are generated, untracked smoke runs that are not part of the current run and are not canonical evidence. Candidates will be moved to a timestamped sibling quarantine under `var`, outside `var/codex-smoke`, so Gradle and source-health scans no longer enumerate them. No irreversible deletion occurs in this pass.

The preserve allowlist includes:

- canonical completion audit, goal status, and latest pointers;
- current browser/computer/local-interaction summaries;
- current web-probe and peer-evidence summaries when present;
- the currently active goal-next output directory;
- browser profile directories used by a live browser process;
- files newer than the selected run boundary.

If active ownership cannot be proven, the item stays in place and is reported as held. The quarantine manifest records only relative paths, hashes, counts, bytes, and reason codes; it must not contain raw prompts, queries, headers, environment values, or secret candidates.

### 4. Ollama restart and runtime proof

Systematic debugging will use one variable at a time:

1. Record process identity, `/api/version`, and a small non-sensitive generation probe.
2. Restart only if the generation failure reproduces and the listener owner is verified as `ollama.exe`.
3. Start the same executable once, without upgrade or persistent setting changes.
4. Poll `/api/version` with a bounded timeout, then repeat the same generation probe.
5. Stop after this single restart attempt.

If the Windows runner failure remains, the result is an external runtime blocker. This scope does not permit masking it with an LLM source change. Browser verification must distinguish a model-backed answer from the application's local fallback.

The application proof will use an isolated build host ID, Gradle cache, output directory, and unused port. It will not kill or overwrite the existing port-18166 process. Browser validation will send a harmless deterministic prompt, check the rendered response and route label, and inspect console errors. Supabase remains a read-only supporting lane and is not required for this artifact-only patch.

## Error handling and rollback

- Any index lock, top-level PatchDrop patch, unsafe resolved path, or ambiguous live ownership stops mutation.
- Any overlap that cannot be patched around in the five already-dirty target files stops that file's patch.
- Quarantined artifacts are restored by moving the manifest-listed items back to their original relative paths.
- The source patch is reversible as a small diff; unrelated worktree changes are never reset or staged.
- Ollama recovery is limited to starting the same executable after the bounded restart. No system service or machine-level environment is changed.
- Raw upstream response bodies and secret-like values are never emitted.

## Tests and verification

Implementation follows RED, GREEN, then broader proof:

1. Add tests showing canonical completion audit wins over newer legacy files, fallback occurs only for absent/unparseable canonical input, and a valid stale canonical input remains stale.
2. Add a static Gradle contract test that rejects recursive `var/codex-smoke` task inputs and requires both exact canonical files.
3. Change goal-next tests to require two audit invocations and no preflight artifacts on the eligible warm path; three invocations and retained preflight artifacts on missing, malformed, stale, future-dated, or unsafe canonical input; packet and final artifacts on both paths; final canonical ownership; and preserved exit-code-4 handling.
4. Run the focused Python and PowerShell tests.
5. Run `sourceHealthScorecard`, source-set hygiene, and LangChain4j purity with isolated Desktop caches.
6. Compare pre/post active-tree file count, byte count, Gradle input count, and completion-audit invocation count.
7. Run the bounded Ollama probe/restart sequence.
8. Start the current source on an isolated port and complete a real browser chat probe.
9. Run a changed-file, count-only secret scan.

## Acceptance criteria

- Eligible warm-path goal-next completion-audit invocations decrease from three to two; cold-path runs remain three and expose the fallback reason.
- `sourceHealthScorecard` no longer declares the recursive smoke tree as a Gradle input.
- Canonical evidence selection is deterministic and preserves stale/malformed classification.
- The active smoke tree has fewer files and bytes, while every held or moved item is accounted for in a redacted manifest.
- No tracked file, live browser profile, current-run artifact, or canonical evidence is removed.
- No LLM Java source, Supabase state, PatchDrop safety gate, secret, or unrelated dirty change is modified.
- Ollama is restarted no more than once and its post-restart generation outcome is reported honestly.
- Browser proof identifies whether the response is model-backed or fallback-backed; a persistent external runner crash remains `evidence_needed` rather than being reported as fixed.

## Known limitation

Reducing artifact and scan cost cannot repair an Ollama runner crash. If the one permitted restart does not restore generation, the artifact optimization can still be verified, but model-backed chat remains an explicitly separate external-runtime blocker.
