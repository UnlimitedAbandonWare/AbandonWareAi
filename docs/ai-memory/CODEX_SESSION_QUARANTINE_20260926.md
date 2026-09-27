# Codex session quarantine rerun — 2026-09-26

Task: `clean-codex-devin-memory-optimize-6114d683` (agent `cline-clean`)
Rule: current source plus live verification outrank memory; quarantine is
evidence, never memory. Move-only, restore-able, no deletion.

## Run identity

- Rescue root: `C:\AbandonWare\_rescue\codex-quarantine-20260926` (new run; the
  tool default `codex-quarantine-20260919` was overridden with `--rescue=`).
- `runId=20260926T143008Z-115f6a19`
- `manifest-20260926T143008Z-115f6a19.jsonl` entries=122
  sha256=`6133a35b609a4983cea9ef0a060042ecbe9e0d087a3025813a3a2313a6d97f92`
- Final `status`: `{"manifestEntries":122,"pending":0,"results":{"moved":122}}`;
  apply-log shows 122/122 `moved` with `preSha256 == postSha256` on every file.
- `codex-quarantine-9only-20260919` was used only as the class/basis template.
  Its 10 files / 154.1 MiB are byte-present and untouched after this run: no
  `restore`, no re-injection into `sessions\`, no ingestion into any memory store.

## Preview classes (measured, not assumed)

| class | n | bytes |
|---|---|---|
| child-stale-no-evidence | 121 | 616,675,946 (588.1 MiB) |
| tmp-globalstate | 1 | 2,097,152 (2.0 MiB) |
| automation-stale | 0 | — |
| stale-db-copy | 0 | idle<60d (`codex-thread-summaries-dev.db` 35d) |
| viz-gradle-cache | 0 | no `visualizations/*/caches` present |

Rollout-content scan rejected 991 otherwise-eligible child threads
(`exec` 979, `apply_patch` 9, `shell_command` 2, `update_plan` 1) — the
conservative fail-closed path worked; those rollouts stay in place.

## Result

- Sessions before: 2,772 files / 9.52 GB. After: 2,653 files / **8.96 GB**.
- Moved out: 616,675,946 + 2,097,152 bytes into the 20260926 rescue root.
- File-count delta is −119 net because the live Codex process created 2 new
  rollouts during the run; those are recent and were never candidates.
- `config.toml` still `generate_memories = false`, `use_memories = false`
  (not edited here). `memories_1.sqlite` unchanged at 3,997,696 bytes.
- Untouched by design and verified present after apply: `state_5.sqlite`,
  `thread_history_1.sqlite` (2,795,020,288), `logs_2.sqlite` (1,573,093,376),
  `sqlite\codex-dev.db` live set, `session_index.jsonl`.

## Why 9.5 GB does not shrink to a few GB (read-only accounting)

Total 2,773 rollout files / 9.53 GiB split under the tool's own protection rules:

| bucket | n | MiB | share |
|---|---|---|---|
| in `session_index.jsonl` (protected) | 1,543 | 7,879.7 | 80.8% |
| child-stale DB-eligible | 1,112 | 1,468.2 | 15.0% |
| live / recent <7d (protected) | 41 | 230.3 | 2.4% |
| has `fileChange`/`userMessage` evidence | 73 | 177.1 | 1.8% |
| main-thread stale (out of tool scope) | 3 | 1.5 | 0.0% |
| not in `state_5.threads` | 1 | 0.1 | 0.0% |

So the recoverable ceiling under the current rule set was ~1.47 GiB, and only
588.1 MiB survived the content scan. The bulk is index-listed sessions that the
SSOT tool deliberately protects, plus two live SQLite stores
(`thread_history` 2.79 GB, `logs` 1.57 GB) the tool never touches.

Further reduction is a product decision, not a hygiene edit, and needs explicit
approval: in-app archive/prune of index-listed sessions, or `VACUUM` of live
Codex stores while no writer holds them. Direct SQLite `DELETE` to "clean"
remains prohibited (`SESSION_MEMORY_HYGIENE_AUDIT_20260919.md`).

## Memory-quality boundary

- Quarantine manifests/apply-logs and rollout payloads stay out of retrieval,
  out of `~/.codex/memories*.sqlite`, and out of `docs/` as raw dumps.
- Durable facts live here plus the task journal only.
- `stage1_outputs` manual DELETE stays prohibited.

## Restore

```text
cd C:\AbandonWare\demo-1\demo-1\src
python scripts\codex_home_quarantine.py restore --rescue=C:\AbandonWare\_rescue\codex-quarantine-20260926 --run=20260926T143008Z-115f6a19
```

Restore refuses to overwrite a foreign destination change or a recreated
source; permanent deletion of quarantined payloads still requires separate
explicit approval.

## Reference

- `docs/ai-memory/SESSION_MEMORY_HYGIENE_AUDIT_20260919.md`
- `docs/ai-memory/AGENT_MEMORY_ROUTING.md`
- `docs/codex-session-cleanup-execution-20260919.md`
- Read-only accounting helper kept with the evidence (outside the repo):
  `C:\AbandonWare\_rescue\codex-quarantine-20260926\logs\sessions_accounting.py`
