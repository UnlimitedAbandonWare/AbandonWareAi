---
name: demo1-uaw-source-reconciliation
description: Use when reconciling demo-1 UAW.txt feature descriptions against current source, equivalent implementations, wiring, tests and runtime evidence; Korean triggers include UAW 문서 교정 and UAW 기능별 대조.
---

# UAW Source Reconciliation

Correct one UAW claim at a time. Current source and hash-bound observations outrank old design text. Produce the [feature record](references/feature-record.md), then a small document diff, then any separately authorized minimal source repair, then verification. A corrected document and a working product are separate outcomes.

Explicit invocation: `$demo1-uaw-source-reconciliation UAW.txt의 <절/기능>을 현재 구현과 대조해 교정해 줘`.

## Entry and existing tools

Work from `C:\AbandonWare\demo-1\demo-1\src`. The sole canonical document is exactly `UAW.txt` at that root. Read actual bytes and SHA256; bind section/line and an existing heading or identifying text anchor to that hash. A misleading internal title does not change this path. Other UAW files, Library attachments and old handoffs are reference data. If canonical is missing/unreadable, report the blocker; do not substitute a copy.

Use current AGENTS instructions, `docs/PROJECT_STATUS.md`, journal and target lease. Before writing preserve current bytes with `codex_work_checkpoint.py begin`; compare current hashes again at apply. A foreign live lease or drift blocks only overlapping writes. Keep the old record, reread current bytes and refresh the proposal without overwriting the other author. Never delete historical copies in this workflow.

Reuse these tools; no new executor, registry, scoring harness or provider call is needed:

```powershell
python -B scripts/agent_preflight.py --root .
python -B scripts/demo1_vibe_skill_router.py resolve "UAW 문서 교정"
Get-FileHash -LiteralPath UAW.txt -Algorithm SHA256
python -B scripts/out_peek.py UAW.txt --grep "<feature/anchor>" --max-chars 6000
git status --short
git diff --cached --name-only
git rev-parse HEAD
```

For writes reuse `work_journal.py open|note|close`, `agent_scope_lease.py claim|verify|heartbeat|done|abort` and `codex_work_checkpoint.py begin|apply|seal|finish`. Declare exact files/current preimage hashes; a directory reservation alone is insufficient for checkpoint's exact-file lease check. Release only owned leases on every exit. Follow existing help/work-ledger contracts instead of adding guards. Preparation-only scope authorizes records/templates and proposals; actual canonical-document or product writes follow the current user's scope.

## One feature cycle

1. **Inspect current implementation.** Reconfirm active Gradle settings/build/sourceSets. Inspect owner, registration/activation conditions and entry -> caller -> output edges. Search behavior, interfaces, aliases, configuration and tests as well as the old class name. An absent name can have an equivalent implementation; a present class can be an empty stub. Record bounded search scope and unknown edges. Use `rg` when available, otherwise bounded `Select-String` or `out_peek.py` on named roots. Do not broadly search secrets, archives or unrelated copies.
2. **Fill the record before rewriting.** Use every slot in [feature-record.md](references/feature-record.md). Classify logic, wiring, test and runtime independently. State current document status, reason/date and edit status separately. Mock PASS can coexist with CALL_UNCONFIRMED. No observation is not proof of dead code. Unmeasured performance stays NOT_MEASURED; do not manufacture probabilities/timings.
3. **Correct only the observed claim.** Replace old names/TODO/completion claims with current behavior and limits. Keep verified resolved work; do not recreate it from old design. Record before/after text and UAW anchor. With document-write authorization, apply that bounded diff through checkpoint and reread the edited claim. With preparation-only scope leave it PROPOSED.
4. **Choose one next action.** PRESERVE useful assets. For partial logic investigate CONNECT through existing owners first. INTEGRATE requires demonstrated overlap/conflict and an existing destination. DEFER missing evidence/authority with one next observation/action. Deletion is a separate decision requiring evidence of unnecessary behavior, duplicate conflict or no recovery value, never implied by a bounded scan's absence. A document discrepancy alone does not authorize a new product feature.
5. **Repair only when separately in scope.** A demonstrated defect can enter [demo1-evidence-debugging](../demo1-evidence-debugging/SKILL.md); a demonstrated search-recovery defect can enter [demo1-search-recovery](../demo1-search-recovery/SKILL.md). Use one primary route for that repair phase and reuse evidence IDs/source owners. Prefer connecting/improving recoverable existing structure. Record RED, minimal diff and focused verification separately from the document edit.
6. **Verify and close this feature.** Record commands, exits, receipts, checked-at times and bound hashes including FAIL/NOT_RUN. Reread document/source hashes; new drift makes affected old verification stale. Main-chat runtime claims use primary `/chat` and current served-build evidence. Unit/mock PASS does not prove deployment. This skill grants no restart, paid API, key/security-setting change, remote mutation or external transmission. Stop at the authorized feature boundary.

## Optional prose handoff

When another model is requested only to improve wording, prepare [rewrite-handoff.md](references/rewrite-handoff.md) from verified facts/uncertainties and return it locally. Actual transmission needs separate explicit authorization. Returned prose cannot promote implementation/test/runtime status, add features or supply missing evidence.

## Reviewable output and Git

Return `feature record -> own document/source diff -> actual verification -> next minimal action/recovery reference`. Keep multiple features' records/cause-specific diffs separate. Inspect user/staged changes first; preserve preimages without Git cleanup/reset. Authorized separable local commits use only `scripts/agent_git_vibe_commit.py` with explicit owned paths. Existing staged changes or uncertain separation mean leave them untouched and report own diff/deferral reason. Never mix unrelated changes or push.

Shared registration uses `.agents/skills/<name>/SKILL.md`, typed `.agents/skills/INDEX.md` and `.agents/skills-intent-index.yaml`. Check route identity and real `source`/`pairedArtifact` paths. Codex/Devin can explicitly read these checkout files; registration/router matches/fresh-context tests do not prove either product's automatic loading. `awx_skill_registry.py` detects collisions; `uaw_spine_probe.py` is static only and `skill_uaw_score.py` is not implementation evidence.

