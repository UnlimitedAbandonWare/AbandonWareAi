# query-agent-auto closeout — DEMO1-QUERY-AGENT-AUTO-20260928-R1

Goal: remove friction for headless `query → fix → re-query` agent loops.
Constraints kept: no new router, no H2 `AUTO_SERVER`, no forced paid web, no
commit/push, local-first, existing token guard.

## Applied (per checkpoint cycle)

| cycle | scope | result |
|-------|-------|--------|
| cycle-01 | `Verify-RAG.bat`, `Status-RAG.bat`, `Debug-RAG.bat`, `Debug-Meta-Display.bat`, `Verify-Meta-Display.bat` | verified |
| cycle-02 | `scripts/db_agent.py` (`--via file\|live\|auto`, locked→live read fallback, `get-user`, masking, custom `--db-path` guard, `upsert-admin` live/dry-run), `scripts/db-agent.ps1` thin wrapper | verified |
| cycle-03 | `scripts/codex_work_checkpoint.py` scanner FP fix (Python `None`/`True`/`False` literal RHS exempt) + `scripts/test_codex_work_checkpoint.py` regression | verified |
| cycle-04 | controllers + tests + client (sealed bytes had pre-fix `JdbcDataSource` test) | **rolled_back** — post-seal fix caused drift |
| cycle-05 | scanner no-op revert | verified (empty diff) |
| cycle-06 | `MetaDisplayDbAdminController`, `SearchDebugController`, both focused tests, `scripts/search_decision_query.py` — identical verified bytes re-landed | verified |

## Verification evidence

| check | result |
|-------|--------|
| BAT headless | `cmd /c set AWX_AGENT=1 && Status-RAG.bat` → one JSON doc on stdout `{exitCode:0,action:status,status:ready}`, no pause |
| `db-agent-kit-smoke.ps1` | `RESULT fail=0 skip=0` (13 checks incl. lockprobe, mask-prefix, no-full-hash, deny exit 2, missing-db exit 4, live-probe exit 3) |
| live fallback (real, observed earlier) | `locked:true` → `get-user`/`query` served over HTTP; custom `--db-path` rejected |
| `gradlew compileJava` | exit 0 |
| focused tests | `MetaDisplayDbAdminControllerTest` 5/5, `SearchDebugControllerTest` 5/5, 0 failures |
| scanner tests | 24 tests, 1 skip (symlink privilege), exit 0 |

## Held / not_observed

- **Live probes of new endpoints**: `blocked-stale-jvm`. Running app serves the
  old build (`/api/internal/db/meta/tables` 200 vs `/api/internal/search/runtime-status` 404);
  DevWatch PID armed but hung; ForceRestart is user-gated. Probe after next
  reload: `search_decision_query.py runtime-status`, `decision`,
  `db_agent.py upsert-admin --via live`.
- Full Gradle suite not run — focused suites only.

## Key seams

- `POST /api/internal/db/meta/admin/upsert`, `GET /api/internal/db/meta/admin`
  — `MetaDisplayDbAdminController`, token-guarded via `AdminTokenGuardInterceptor`,
  `PasswordEncoder` bcrypt, masked output only.
- `GET /api/internal/search/decision`, `GET /api/internal/search/runtime-status`
  — `SearchDebugController`, dry-run only; no provider call, no routing change.
- Client: `scripts/search_decision_query.py decision|runtime-status` (JSON).
