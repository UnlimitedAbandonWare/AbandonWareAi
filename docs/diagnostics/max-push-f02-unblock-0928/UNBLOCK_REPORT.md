# UNBLOCK_REPORT — MAX-PUSH F02 (checkpoint scanner lease + false positive)

- Contract: `DEMO1-DEVIN-MAXPUSH-F02-UNBLOCK-20260928`
- Task: `devin-f02-scanner-unblock-0928-33a0e48d` (agent: devin)
- Date: 2026-09-28 (UTC evidence window ~13:45–14:08)
- **Codex may resume F02: YES**

## Lease before → after

| | before | after |
|---|---|---|
| `checkpoint-scanner-jackson-read-0928` | `active`/`live` on `scripts/codex_work_checkpoint.py` + `scripts/test_checkpoint_java_json_field_read.py`, leaseId `5faf346470c242fead5d640f74b07fa1`, owner `devin:nova-meta-graphrag-settings-0928-d70a0915` | **released** via `source_edit_session.ps1 -Action end` (fingerprint-pinned normal release; `release` event written) |
| foreign active claim on scanner | 1 | **0** |
| devin claim | — | `devin-f02-scanner-unblock-0928-33a0e48d.lock` fp `f0944349…` → re-scoped `91f014fa…` |

Orphan evidence (branch C): owner journal **closed** (`nova-meta-graphrag-settings-0928-d70a0915`, result `partial`); topic journal `missing`; claims `[]`; `ownerProcessId=0`; heartbeat `absent`; pending release-request since 12:45:16Z.
`recover` → inspected 3 / recovered 0 (no PID-death proof possible). `reclaim --dry-run` → `skipped: live-lease` (valid TTL). Neither tool could act → `end` per the release-request's own documented command. Full detail: `00_LEASE_CLEAR.md`.

## Scanner pre/post sha256

- `scripts/codex_work_checkpoint.py` pre `baec4562b19fd44e87f791c71fa00d435541d4ad8ddb8242f04714e2d37176c8` (== proof baseline) → post `c63c127d9c6ccaa2d1087192fc565b54bcef62a32e6fc345b87dfc095b968eaf`
- `scripts/test_codex_work_checkpoint_selfask_fixtures.py` (new) `857319f151ef3d51c80354d067dd567aa4015875b586de3d6cc630c7c9813d35`
- checkpoint cycle-01 `verified`, manifest `5b30d75ddaaa938116df69479f0bd60f79fa67b80bb6348402602f5c6692d14e`, diff `6e1f4a2e…`

## Applied change (minimal)

`f02-scanner-candidate.patch` applied verbatim at `codex_work_checkpoint.py` L267–273: path-scoped (`casefold`) to `SelfAskWebSearchRetrieverTest.java`; rewrites only the two exact fixture literals (`"retry branch api_key=sk-" + "abcdefghijklmnopqrstuvwxyz123456"`, `"raw timeout query with api_key=sk-" + "…"`, incl. the `+ "` concat bytes) to `"<synthetic-selfask-redaction-fixture>"`. Other paths, mutated values, and neighbouring secrets stay scanned. No scanner weakening, no global exemption.

## Regression (permanent)

`python -B -m unittest scripts.test_codex_work_checkpoint_selfask_fixtures -v` — 6 methods / OK:
- positive×2: both fixture literals pass on the SelfAsk path; plus the **real** `SelfAskWebSearchRetrieverTest.java` passes `secret_free` (the actual F02 gate).
- negative×8+ subtests: off-path (other test java, main java, scripts, docs, empty), mutated token ×4, single-literal no-concat form, co-resident `api_key` secret — all still `secret-pattern` blocked.

Scoped verification (recorded): `python -B -m unittest scripts.test_codex_work_checkpoint scripts.test_codex_work_checkpoint_source_expressions scripts.test_codex_work_checkpoint_selfask_fixtures scripts.test_checkpoint_java_call_args scripts.test_checkpoint_java_json_field_read -v` → **exit 0**, 76 tests (1 skip), runId `84da7926-2818-4e29-9749-07c77e34295f`, log `data/agent-handoff/codex-autonomy/devin-f02-scanner-unblock-0928-33a0e48d/verify-cycle-01b/`.

Full-family run (14 modules, 118 tests) → exit 1: single failure `test_checkpoint_java_encoding … formatHex(getBytes())` subtest — **proven pre-existing** by preimage probe (`cycle-01/before/0.bin` raises neither before nor after). Separate scanner gap in the nonliteral-expression exemption; not caused by this patch; out of scope here.

## Resume gate for Codex (re-check before F02)

```
python -B scripts/agent_scope_lease.py show --task checkpoint-scanner-jackson-read-0928   # claims: []
python -B -m unittest scripts.test_codex_work_checkpoint_selfask_fixtures                 # OK
```

## Remaining prohibitions (unchanged)

- `pending-f02-tests.patch` + SelfAsk/ChatWorkflow product Java = **Codex-owned**, untouched here (product Java diff 0).
- Do not revert the scanner patch or disable the secret scan; do not force-release foreign leases; no commit/push/`add -A`.
- `checkpoint-scanner-jackson-read-0928` lease dir is gone via normal `end`; if a *new* live lease appears, the standard request-release path applies — do not force.
