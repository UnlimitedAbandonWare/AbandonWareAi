# Quarantine Failure Modes — Agent Strengthening Pack (2026-09-29)

Contract: `DEMO1-DEVIN-QUARANTINE-AGENT-GRAFT-20260929`
Machine-readable SSOT: `configs/agent-failure-mode-catalog.yaml`
Evidence base: `C:\AbandonWare\_rescue\codex-quarantine-9only-20260919`
(9 rollout sessions, ~154 MB, all classified `child-stale-no-evidence`,
all with empty session titles — cold archive, **read-only, never delete**).

## What this is (and is not)

This pack converts quarantined Codex rollout JSONL into a **behavioral
failure dataset**: compact episode records + a curated failure-mode
catalog + runtime rails. It is **not** a retrieval corpus and **not** a
training dump.

- Raw rollouts: never embedded, chunked, pasted, or indexed into RAG.
  `raw_rollout_rag: forbidden`.
- Episodes: `data/agent-handoff/quarantine-agent-graft-20260929/episodes/`
  — ≤~2 KB each, secret-masked, counts + short normalized command heads
  only. 9 episodes / 15,999 bytes total from 154 MB of source.
- Selective search surface: **only** this document + the catalog YAML may
  be indexed (`curated_index_default: off`).

## The quarantine signature (observed)

All 9 sessions share: `thread_source=subagent` (child), `title=""`,
`shaPreserved=true`, and no `fileChange`/`userMessage` evidence items in
their thread history — children that consumed tools and tokens but left
no deliverable trace before going stale. Largest: 136 MB, ~282M thread
tokens, 31 compactions, 1,395 tool calls, 4,712 done-claims.

Live re-scan with `agent_session_watch.py` (2026-09-29) additionally
surfaced `P5` context-compaction-heavy, `P7` goal-conflict, `P11`
in-output-command-failure (auto), `P12` same-target-retry (auto) on the
biggest sessions. See `STATUS.md` for the P14 coverage note.

## Failure modes → rails (catalog order)

| # | mode id | behavioral symptom | graft rail | sev |
|---|---------|--------------------|------------|-----|
| 1 | `done_without_evidence` | Done/PARTIAL/GREEN claim with no test, no GATE exit record, no handoff path | `agent_done_evidence_guard` via `agent_work_pipeline.py done-check` | high |
| 2 | `reversible_quiz_spam` | approval quiz cards on reversible local work (>10 asks/1k lines observed) | `agent_vibe_auto_decision` self-ask judge | medium |
| 3 | `stale_child_no_evidence` | child/subagent thread goes stale, `taskStarted>taskComplete`, empty title, no evidence items | `agent_session_watch` (P9/P14) | high |
| 4 | `gate_loop_no_exit` | ≥20 GATE/exit-code lines, zero recorded passing exit | `run_verified_command` + journal verify event | medium |
| 5 | `context_blowup_full_rollout` | session >50 MB / turn >200k tokens / ≥2 compactions; temptation to paste it all | `quarantine_failure_episode_extract` (episodes only) | medium |
| 6 | `apply_patch_context_miss` | ≥2 `Failed to find expected lines` on same file — stale preimage while a foreign writer moved it | `codex_work_checkpoint` begin/apply drift refusal | medium |
| 7 | `repeated_failed_command` | same normalized command (or same file via mutated one-liners) failing ≥3× | `agent_work_guard` PostToolUse ledger | medium |
| 8 | `plugin_sprawl` | ≥3 recommended-plugin blocks / @-mentions instead of using installed seams | `demo1-codex-plugin-roles` matrix | low |
| 9 | `auth_claim_no_proof` | "login/admin/auth verified" claims with no GATE evidence (proto-open surface) | `agent_done_evidence_guard` (done-check) | high |
| 10 | `dup_service_suspect` | drift toward a parallel service/JobService instead of extending the owning seam | `demo1-cross-subsystem-guard` | medium |

Per-mode `signals`, `symptoms`, `invoke` commands, `vibe` priors
(pattern/effect/question) live in the catalog YAML — this doc is the
human explanation; the YAML is what the rails consume.

## Guard behavior contract

- **`done_without_evidence` / `auth_claim_no_proof`** — a completion or
  auth claim lacking evidence tokens exits **2** from `done-check` (claim
  blocked). Evidence tokens are paths to verify output, GATE/exit-code
  records, journal `verify` events — never narrative assertions.
- **`reversible_quiz_spam`** — reversible local paths resolve `AUTO`
  through the self-ask judge; quiz cards are for irreversible /
  foreign-scope decisions only. Goal-objective intake is never Done.
- **`stale_child_no_evidence`** — watcher flags the child at `warn`
  (stale) or `info` (early); the parent closes/journals rather than
  silently inheriting the child's claims.
- **`gate_loop_no_exit` / `repeated_failed_command`** — the brake is the
  recorded-failure ledger: the 4th identical failure is blocked; change
  the hypothesis, not the one-liner.
- **`context_blowup_full_rollout`** — the only sanctioned transform is
  episode extraction. Any plan to paste/embed/chunk a raw rollout is an
  `ASK_ONCE` gate via the catalog `vibe` prior.
- **`apply_patch_context_miss`** — context-miss means "another writer
  touched this file": re-read + re-preimage; never blind re-apply.
- **`plugin_sprawl` / `dup_service_suspect`** — prefer existing seams;
  a new parallel service is never the minimal diff.

## How agents consume this

```powershell
# symptom -> ranked modes + rail commands (local heuristic, no paid API)
python -B scripts/agent_failure_mode_advise.py --symptom "stale child empty title"

# self-ask judge with catalog priors layered on agent_vibe_auto_decision
python -B scripts/agent_failure_mode_advise.py judge --action "<planned>" --paths "a,b"
#   exit 0=AUTO | 3=ASK_ONCE | 4=HOLD   (priors only tighten, never relax)

# done/auth claim gate
python -B scripts/agent_work_pipeline.py done-check --task <taskId> --text "<claim>"

# regenerate episodes from a quarantine dir (streaming, masked)
python -B scripts/quarantine_failure_episode_extract.py --seed <quarantine-dir>
```

Boundaries: no product-Java changes, no commits/pushes, no raw rollout
copies, no secrets in artifacts, source archive untouched.
