# Unnatural agent mechanisms probe — 2026-09-27

Project: `C:\AbandonWare\demo-1\demo-1\src`  
Stance: Prototype Light / PROTO_OPEN (no fail-close admin)  
Method: read-only live file evidence (Shell/Read on machine); product code untouched.  
Companion: `FINDINGS.json` (array of 10).

## SUMMARY

### 10 titles (severity 1–5)

| id | sev | title | devonable |
|----|-----|-------|-----------|
| AM-01 | 5 | Sticky blocked ChangeIntents after owner journals closed | yes |
| AM-02 | 5 | Triple write-coordination stack (PS1 lease + scope lease + ChangeIntent) | yes |
| AM-03 | 4 | Zombie in_progress handoff journals (15+) with empty events | yes |
| AM-04 | 4 | Three-way preflight mandatory-vs-optional contradiction | yes |
| AM-05 | 4 | AGENTS.md mega-policy + 125 skills + multi-root always-on rule fanout | yes |
| AM-09 | 4 | Pre-approved build prune + multi Gradle homes + zombie prune journal | yes |
| AM-06 | 3 | Soft-auto git vs Still-forbidden index.lock tension | yes |
| AM-07 | 3 | reject-complete instructional-not-acceptance exit gate | yes |
| AM-08 | 3 | Codex plugin-roles matrix vs Prototype Light minimal tools | yes |
| AM-10 | 2 | GPU ARCHIVED-repair vs ACTIVE-resolved dual narrative | yes |

### shortlist3 (fix urgency, highest first)

1. **AM-01** Sticky blocked ChangeIntents — actively poisons concurrency today (4/7 blocked against closed journals; live locks are unrelated clean-*).
2. **AM-02** Triple write stack — root cause of release misses and the sticky board; needs a single entry path long-term.
3. **AM-03** Zombie in_progress journals — feeds false Active lanes on change-plane LATEST and confuses `who`/preflight.

### THE ONE (for Devin now)

**AM-01 — Sticky blocked ChangeIntent reconciliation**

**Why now:** Live board is lying. Blocking owners `rtx3090-power-resolved-docs-21ae0260`, `mutable-spec-policy-afc0b500`, `chat-video-source-repair-80e240cb` journals are **closed**; `__patch_drop__/source-edit-locks` only has `clean-primitive-debug-ai-impl-0926.lock` and `clean-vibe-agent-auth-relax-0927.lock`. Yet intents stay `blocked` and agents stop or page the user. Docs already say PS1 leases are authoritative and the board is derived — the code does not reconcile.

**Devin-sized scope (no product rewrite, PROTO_OPEN-safe):**
1. Add `python -B scripts/agent_change_plane.py reconcile [--dry-run]` that:
   - For each `state=blocked` intent: if every `blockingLeases[].ownerTaskId` has closed/missing journal AND no live overlapping lock under `source-edit-locks`, mark intent `superseded`/`expired` (append event; do not delete history).
   - Never force-end a live foreign lease; never touch product Java.
2. Wire `status`/`admit`/`plan` to call reconcile (or equivalent check) so ghost blocks cannot gate new admits.
3. One-shot dry-run receipt under `data/agent-handoff/change-plane/` + short AGENTS/skill note: blocked ≠ lock; reconcile is automatic.
4. Optional tiny follow: close empty-event zombie journals older than N days owned by dead sessions via existing `goal_switch_barrier`/`work_journal` (AM-03), only if still in scope.

**Out of scope for THE ONE:** collapsing the triple stack (AM-02), AGENTS slim (AM-05), plugin matrix (AM-08), auth harden.

### NOT_RUN

- No Java/Gradle kill or rebuild
- No madasin/build deletes
- No secret prints
- No product source edits
- No live `admit`/`reclaim`/`prune` mutations (observation only)
- Full-repo Java `ChangeIntent` class search timed out / incomplete; ChangeIntent appears Python/handoff-schema (`awx.change-intent.v1`) not a Java type. `AgentApiSpendGuard` exists as compiled classes under `build/` / `bin/`; source path not confirmed in this probe pass.

---

## Findings detail

### AM-01 Sticky blocked ChangeIntents (sev 5)

**Why unnatural:** A derived “board” becomes a hard stop. Intents remain `blocked` with `source-target-overlap` fingerprints days after blockers finished.

**Evidence:** `intents.json` states `{released:3, blocked:4}`; LATEST.md lists blocked targets `agents.md`, `docs/project_status.md`, `scripts/gpu_power_fallback.py`; owner journals closed 2026-09-24..26; live locks ≠ those owners.

### AM-02 Triple write-coordination stack (sev 5)

**Why unnatural:** Three orchestration APIs for one edit. Skill text insists ChangeIntent never locks, yet `admit` wraps `begin` and agents must keep fence+fingerprint+topic across layers.

**Evidence:** skills `agent-scope-lease`, `demo1-agent-change-plane`, `demo1-lease-conflict-autoflow`; PS1 `source_edit_session.ps1`; AGENTS DEMO1-LEASE-LIFECYCLE; 40 files already in `source-edit-quarantine/`.

### AM-03 Zombie in_progress journals (sev 4)

**Why unnatural:** Dead work stays Active. `build-prune-fast-89faf47f` opened 2026-09-20 with `events:[]`, `status:in_progress`. 15 in_progress sampled. Barrier skill exists but residue accumulates.

### AM-04 Three-way preflight contradiction (sev 4)

**Why unnatural:** Alias skill + SKILL.md “before any write” vs AGENTS “optional review”. Correctness ritual vs vibe speed.

### AM-05 Mega-policy fanout (sev 4)

**Why unnatural:** 125 skills, 69KB AGENTS, duplicated always-on rules (incl. intentional overlap admitted in clinerules 60↔61). Cognitive tax for every agent entry.

### AM-06 Soft-auto vs hard git (sev 3)

**Why unnatural:** Soft path auto-moves stale `index.lock`; hard list forbids deleting it. Same object, two moods.

### AM-07 reject-complete gate (sev 3)

**Why unnatural:** Final sentence grammar becomes a stop/continuation controller (`instructional-not-acceptance` exit 5).

### AM-08 Plugin matrix vs PROTO_LIGHT (sev 3)

**Why unnatural:** Dense Codex plugin allowlist fights “default tools = BATs + local git” light mode.

### AM-09 Build prune / multi Gradle (sev 4)

**Why unnatural:** Pre-approved recursive delete under `build/` with no lease; concurrent agent-named build dirs; zombie prune journal.

### AM-10 GPU dual narrative (sev 2)

**Why unnatural:** ARCHIVED repair brief still named beside ACTIVE RESOLVED block; obsolete “3090 unstable” path keeps tempting agents.

---

## Observed inventory (context)

- Skills: **125** under `.agents/skills/`
- Handoff dirs: **60** under `data/agent-handoff/`
- agent-prompts dirs: **43**
- ChangeIntents: **7** (3 released, 4 blocked)
- Live source-edit locks (probe time): `clean-primitive-debug-ai-impl-0926.lock`, `clean-vibe-agent-auth-relax-0927.lock`
- Quarantine rows: **40**
- in_progress journals (codex-autonomy scan): **15**
- Git remotes live: single `origin` → `AbandonWareAi` (originMismatch not currently hot; gate still exists in `conditional_local_git.py`)
- Agent rule roots present: `.codex` `.windsurf` `.cline` `.clinerules` `.devin` `.grok`
