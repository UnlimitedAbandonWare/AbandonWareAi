# Goal-Next Mode Identity and Mode-Safe Fresh Reuse Design

Date: 2026-07-18

Status: design A approved in chat; written specification pending user review

## Goal

Prevent `scripts/goal_next_auto.ps1 -EnsureFresh` from reusing a fresh result that was produced under a different evidence or dispatch mode. Preserve the Desktop-local warm path when the requested mode matches exactly, while failing closed for legacy, malformed, or ambiguous latest pointers.

This is a correctness and tool-budget boundary change. It must not make optional Browser, Computer, Supabase, Mac mini, Notebook, or PatchDrop evidence mandatory for the default Desktop-only path.

## Current Evidence

- Canonical root: `C:\AbandonWare\demo-1\demo-1\src`
- Branch: `main`
- Index lock: absent
- Dirty worktree rows at design intake: 1,095
- Active top-level PatchDrop patches: 0
- The three candidate runtime/test files already contain unrelated working-tree changes.
- `goal_next_auto.ps1` currently creates a four-boolean `requestMode` containing `requireSupabaseProof`, `externalDispatch`, `refreshWebProbe`, and `requireUiProof`.
- Both provisional and final `goal-next-auto.latest.json` writers currently persist that `requestMode`.
- `-EnsureFresh` currently compares those four booleans and sets `latest-mode-mismatch` when a fresh pointer differs.
- `goal_next_auto_tests.ps1` currently proves the default-to-Supabase mismatch path invokes the two Supabase fixture children.
- The current partial contract does not include the topic or a mode schema, does not distinguish missing identity from invalid identity, applies the mismatch gate only to `-EnsureFresh`, and is not validated by `awx_mcp_completion_audit.py`.

These observations are an intake snapshot. Implementation must recheck the live files before editing and must preserve any later compatible work.

## Scope

### In scope

1. Define one canonical, typed mode identity for goal-next results.
2. Require exact mode identity equality before a fresh latest pointer can be reused.
3. Classify missing, invalid, and mismatched identity separately.
4. Keep `-Status` read-only while exposing that an ambiguous pointer is not reusable.
5. Write the same identity to both provisional and final latest pointers.
6. Add stage-safe completion-audit validation without reordering the existing audit convergence chain.
7. Add focused PowerShell and Python regression tests.

### Out of scope

- Java, Spring, PromptBuilder, RAG algorithm, LangChain4j, or Gradle changes
- Browser or Computer automation changes
- Supabase authentication, SQL, schema, or data mutation
- PatchDrop janitor removal or source-lease changes
- A new daemon, scheduler, broker, cache service, or background coordinator
- Mode-keyed latest files or broad artifact cleanup
- Test-harness parallelization, case filtering, or process-timeout redesign

## Design

### 1. Canonical mode identity

The canonical identity is an additive object on the existing latest-v1 contract:

```json
{
  "schemaVersion": "awx.goal_next_auto.mode_identity.v1",
  "topic": "mcp-control-loop",
  "requireSupabaseProof": false,
  "externalDispatch": false,
  "refreshWebProbe": false,
  "requireUiProof": false
}
```

`topic` participates in equality because a fresh result for one operator topic must not satisfy another topic. It must be a non-empty JSON string and is compared with ordinal, case-sensitive equality; the comparison does not trim or case-fold it. Reporting continues to use the existing bounded/redacted topic path. The four mode flags must be actual JSON booleans; strings such as `"false"`, numbers, nulls, missing properties, or additional schema interpretations are invalid rather than truthy PowerShell casts.

The current `requestMode` field remains as a compatibility alias during this pass. New writes set both fields from one canonical object so they cannot diverge. Reuse decisions use `modeIdentity`, not the alias.

### 2. Exact-match reuse decision

The requested identity is built once from the current CLI arguments. A latest pointer is reusable only when all of the following are true:

- the latest pointer parses as a JSON object;
- normal age, dependency, and secret-safety checks pass;
- `modeIdentity.schemaVersion` is the supported value;
- `topic` and all four booleans are present and correctly typed;
- the stored and requested identities are exactly equal.

Subset reuse is forbidden in both directions. A strict result may contain external blockers or consent prompts that are not valid for a weaker Desktop-only request, while a weaker result can false-green a strict request.

### 3. Failure classification and command behavior

Identity failures use these exact labels:

- `latest-mode-identity-missing`
- `latest-mode-identity-invalid`
- `latest-mode-mismatch`

Secret evidence remains higher priority and retains exit code 4. If time/dependency staleness and identity mismatch occur together, the existing staleness reason remains primary while a separate status field records the identity result. This avoids hiding an older, independently sufficient refresh reason.

`-Status` never runs child gates. For a fresh but non-reusable identity it writes status with `statusDecision=evidence_needed`, `reuseAllowed=false`, and the exact identity classification, then exits 2.

`-EnsureFresh` refreshes exactly once in the requested mode when identity is missing, invalid, or mismatched. After a successful refresh, the final status reports `latestModeMatches=true` and `reuseAllowed=true`. A second identical `-EnsureFresh` call takes the warm path and does not rerun child gates.

Legacy latest-v1 pointers without `modeIdentity` are readable for diagnostics but non-reusable. They incur one cold refresh per root, after which exact-mode warm reuse resumes.

### 4. Writer and audit integration

Both latest writers must persist identical `modeIdentity` and compatibility `requestMode` objects:

- the provisional writer before packet/final completion-audit invocations;
- the final writer after summary and digest generation.

The existing completion-audit ordering is not changed. `awx_mcp_completion_audit.py` validates that the pointer identity is present, typed, and internally compatible with the request it can prove. When invoked with `--require-supabase-proof`, it requires `requireSupabaseProof=true`. Missing or invalid identity is a hard readiness failure rather than a warning.

The completion audit must not require a newly written final summary during the provisional stage because the current convergence chain publishes the provisional latest before the final summary. Full stored-versus-requested equality remains owned by `goal_next_auto.ps1`; final pointer-versus-summary equality is verified in the PowerShell regression suite after the run completes.

No completion-audit schema field is renamed or removed.

### 5. Reporting and redaction

Status and summary artifacts expose only:

- canonical boolean flags;
- bounded topic text already used by the existing contract;
- schema and reason labels;
- `latestModeMatches` and `reuseAllowed` booleans.

They must not contain tokens, headers, cookies, raw prompts, raw queries, environment values, database URLs, or external response bodies.

## Error Handling and Rollback

- Missing or malformed identity fails closed and never becomes fake `OK`.
- A failed refresh retains the exact existing failure and does not rewrite it as a successful mode migration.
- Secret leakage evidence stops before mode-based reuse.
- No external evidence is fabricated to satisfy an identity.
- Rollback removes the canonical identity helpers and additive fields while leaving unrelated working-tree changes untouched.
- The compatibility `requestMode` alias prevents consumers from breaking during the first implementation pass.

## Tests and Verification

Implementation follows RED, GREEN, then broader local proof.

### PowerShell RED cases

1. A fresh default pointer followed by `-EnsureFresh -RequireSupabaseProof` must refresh and invoke the two Supabase fixture children exactly once.
2. A fresh default pointer followed by `-EnsureFresh -ExternalDispatch`, `-RequireUiProof`, `-RefreshWebProbe`, or a different topic must refresh in that requested mode.
3. A strict pointer followed by a default Desktop-only request must refresh rather than leak strict blockers into the default result.
4. A legacy pointer without `modeIdentity` must be `latest-mode-identity-missing`.
5. A pointer with a wrong schema, missing field, non-boolean flag, or missing, empty, or non-string topic must be `latest-mode-identity-invalid`.
6. `-Status` for missing, invalid, or mismatched identity must not invoke child gates and must exit 2.
7. An exact-mode fresh pointer must be reused without writing a new run summary or invoking child gates.
8. Both provisional and final latest writers must contain identical canonical identity.
9. The final pointer identity must match the completed summary mode fields and topic.
10. Secret-risk and ordinary stale-pointer precedence must remain unchanged.

### Completion-audit RED cases

1. Missing or invalid pointer identity fails the goal-next packet readiness contract.
2. `--require-supabase-proof` rejects a pointer whose identity has `requireSupabaseProof=false`.
3. A valid default identity remains acceptable when strict Supabase proof is not requested.

### Verification commands

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\goal_next_auto_tests.ps1
python .\scripts\test_awx_mcp_completion_audit.py
python .\scripts\awx_mcp_completion_audit.py --root .
```

Java or Gradle verification is not required unless implementation evidence proves that the patch crosses into Java/build contracts. Browser, Computer, and live Supabase proof remain supporting and are not part of this script-only verification ladder.

A changed-file secret scan reports counts only.

## Acceptance Criteria

- Cross-mode fresh reuse cannot skip a requested Supabase, external dispatch, UI, web-refresh, or topic-specific lane.
- Exact-mode fresh reuse retains the current cost-saving warm path.
- Legacy and malformed identities are honest `evidence_needed`, not false success.
- `-Status` remains child-process-free.
- Both latest writers publish the same typed identity.
- Completion audit fails closed for missing/invalid identity and for a strict Supabase audit backed by a non-strict pointer.
- Focused PowerShell and Python tests pass with real command output.
- Changed-file secret-pattern hits are zero.
- No Java, Gradle, Browser, Computer, Supabase mutation, PatchDrop, or unrelated dirty-worktree file is changed.

## Known Limitation

The canonical latest pointer remains global. Alternating between different modes or topics therefore causes a refresh on each switch. Mode-keyed pointers could eliminate that cache thrash, but their consumer and cleanup migration cost is intentionally outside this minimal Safe Patch.
