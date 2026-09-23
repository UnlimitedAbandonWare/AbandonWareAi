# demo-1 Spire Debug Operator — 9-Hour Safe Patch

Use this directive for a maximum nine-hour source-modification pass on the
Desktop canonical demo-1 root.

**REQUIRED SKILL:** Use `$demo1-debugging-with-two-tools` before the first
source decision and for every patch cycle.

## 0. Role and outcome

Act as a debugging operator with a narrow evidence base. Resolve one proven
failure layer at a time, using one observation tool and one verification tool.
Advance through `P0 → P1 → P2`; P2 remains gated. Produce source changes only
while the next candidate is owned, falsifiable, testable, and reversible.

Nine hours is a maximum agent effort budget. It does not authorize broad
rewrites, product runtime timers, schedulers, daemons, or coding because time
remains.

## 1. Authority

1. Current repository files and command output.
2. Current Desktop final verification output.
3. Current task attachments and PatchDrop evidence.
4. Official vendor documentation.
5. Older prompts, memory, and human estimates.

Notebook, Mac mini, Browser, Computer, and Supabase evidence is supporting
unless current Desktop proof makes that lane authoritative.

## 2. Hard invariants

- Desktop canonical root: `C:\AbandonWare\demo-1\demo-1\src`.
- Active roots: `main/java`, `main/resources`,
  `app/src/main/java_clean`, `app/src/main/resources`.
- `dev.langchain4j:*` stays exactly `1.0.1`.
- Final RAG prompt assembly stays on `PromptBuilder.build(PromptContext)`.
- Patch one intent, normally at most three files.
- Do not edit inactive mirrors, archives, backups, generated output, secrets,
  `.env*` or secret-tool configuration names.
- Do not fabricate build, boot, provider, Browser, Computer, Supabase,
  PatchDrop, Mac mini, or Notebook proof.
- Missing optional credentials must disable the provider, give a redacted
  reason, and make no outbound call.

## 3. Intake

Run before source edits:

```powershell
$Root = "C:\AbandonWare\demo-1\demo-1\src"
Set-Location $Root
Get-Location
git rev-parse --show-toplevel
git branch --show-current
git worktree list
git status --short
if (Test-Path ".git\index.lock") {
  Write-Error "[AWX][spire] index-lock-conflict"
  exit 1
}

$BuildFiles = @(
  ".\settings.gradle", ".\settings.gradle.kts",
  ".\build.gradle", ".\build.gradle.kts",
  ".\app\build.gradle", ".\app\build.gradle.kts"
) | Where-Object { Test-Path $_ }
Select-String -Path $BuildFiles `
  -Pattern "sourceSets|srcDirs|java_clean|main/java|langchain4j"

if (Test-Path ".\__patch_drop__\janitor_inventory.ps1") {
  powershell -NoProfile -ExecutionPolicy Bypass `
    -File .\__patch_drop__\janitor_inventory.ps1
}
```

Stop on wrong root, wrong branch ownership, index lock, ambiguous active
PatchDrop patches, overlapping target-file changes, unproven sourceSet, or
mixed LangChain4j versions.

## 4. Goal contract

Write this before selecting a patch:

```text
target_metric:
observed_current:
gap:
suspected_causal_chain:
active_surface:
active_sourceSet:
proof_command:
```

Use `evidence_needed: missing artifact / verify with exact command` for every
field that cannot be proven.

## 5. Two-tool patch cycle

Emit this record for every cycle:

```text
cycle_id:
priority:
failure_layer:
fact:
inference:
suspicion:
hypothesis:
observation_tool:
observation_result:
patch_intent:
changed_files:
verification_tool:
observed_result:
failure_class:
source_snapshot_hashes:
ai_candidate_origin:
validated_evidence:
mcp_verification_scope:
seed_and_case_count:
counterexample_hash:
rollback:
next_priority:
```

Rules:

1. Select the first failing layer.
2. State one falsifiable hypothesis and one disproof condition.
3. Spend the observation slot on `rg` plus targeted reads, or one focused
   diagnostic command.
4. Add a characterization or RED test when behavior changes and a focused
   test surface exists.
5. Patch one intent with the fewest active files and lines.
6. Spend the verification slot on the narrowest command that falsifies the
   claim.
7. On success, update the ledger and consider one redacted success pattern.
8. On failure, roll back the current cycle diff and do not advance priority.
9. When the failure class changes, close the cycle. The next evidence tool
   starts a new cycle with a new hypothesis.

The edit and ledger update are required workflow steps, not extra evidence
tools. Broad scans, full logs, full tests, boot smoke, UI automation, and
external providers require written proof that the narrow verifier cannot make
the next decision.

When AI debugging is requested, use the required skill's AI-Assisted
Observation contract and `tools/ai_debug_assist.py`. Reuse its log-content and
source hashes, validated MCP classification, and existing read-only delegate.
If the external delegate is unavailable, Codex may supply a hash-bound
candidate with `--proposal`; record its origin as supplied, not a live provider.
Keep `claimsVerified=false` until a separately executed focused test supports
the selected hypothesis. Seeded random inputs belong only in synthetic tests;
record seed/case/count/counterexample hash and preserve runtime settings.

## 6. Priority board

### P0

- sourceSet, FQCN, branch, or PatchDrop ownership;
- build and boot blockers;
- secret, authorization, redaction, or data-loss risks;
- PromptBuilder boundary bypass;
- LangChain4j version impurity;
- unsafe outbound calls.

### P1

- fail-soft behavior;
- cancellation and timeout correctness;
- silent failure observability;
- bounded resource use;
- missing focused verification or rollback.

### P2

- narrow cleanup, deduplication, metrics, or documentation that measurably
  reduces future debugging cost.

P2 begins only when every known P0 and P1 item is verified or explicitly
recorded `SKIP` or `evidence_needed`. Each P2 change stays within one owner,
touches at most three files, and has measurable value, a focused verifier, and
a rollback path.

## 7. Time budget

- Reserve the final 45 minutes for cumulative verification, count-only secret
  scan, ledger closure, and PatchDrop or final report preparation.
- P0 may consume all patch time.
- Begin P1 only after the current P0 gate is green or explicitly blocked.
- Continue P2 only while each candidate clears ownership, value, verification,
  and rollback gates.
- Stop early when no candidate clears the gates, an external-only blocker
  repeats, or the next change adds an orchestration framework.

## 8. Success memory

```text
success_patterns_max: 5
symptom -> proof -> minimal_patch -> verifier -> rollback
```

Store a pattern only after fresh verification. Prefer reuse after the pattern
succeeds twice. Evict the oldest or least reusable entry. Never store raw
prompts, queries, logs, credentials, headers, cookies, or private environment
values.

## 9. Verification and completion

Verify the narrow changed surface first. Broaden only when the changed owner
requires it. Before a completion claim, run fresh sourceSet and LangChain4j
purity checks, the cumulative focused tests, `bootJar -x test` when packaging
changed, and a count-only secret scan over the patch.

Stop with an exact failure class on:

- `index-lock-conflict`
- `patch-drop-pending`
- `branch-ownership-mismatch`
- `wrong-sourceset`
- `langchain4j-version-purity`
- `secret-leak-risk`
- `verification-failed`
- `changed-failure-class`
- `desktop-proof-missing`

Do not mark a patch `DONE` without fresh proof or a proven
`no_patch_needed` result.

## 10. Final report

```markdown
## 요약
- 실제 수정 범위와 현재 검증 상태만 기록한다.

## Observation
- 실행한 명령
- 최초 실패 레이어
- fact / inference / suspicion
- root / branch / active sourceSets
- evidence_needed

## Patch
- 파일별 before / after
- patch intent와 우선순위
- 성공 패턴 ledger 변경
- rollback note

## Verification
- Command
- Expected
- Observed
- Failure classification
- Desktop 재검증 필요 여부

## Risks & Next
- 최대 5개
- confidence: L / M / H
- next single most urgent action
```
