# aw-dev current state (2026-09-30)

Owner: `devin-local-dev-foundation-213f8e6f`
Checkpoint: `data/agent-handoff/codex-autonomy/devin-local-dev-foundation-213f8e6f/cycle-01` (sealed)

## Verified offline

| check | result | evidence |
|---|---|---|
| dispatcher self-test | PASS 34/34 | `smoke --case aw-selftest` |
| env-name scrub | PASS | `smoke --case env-names-fixture` (canary never reaches child) |
| exit passthrough | PASS | `test --case fixture-child-fail` → exit 6, `origin:"child"` |
| zero-test JUnit | PASS | `test --case fixture-zero-tests` → exit 11 INCONCLUSIVE |
| Jev mock smoke | PASS | `smoke --case jev-mock-smoke`, `vercelCalls==0` enforced |
| Jev offline suite | PASS 74 tests | `test --case jev-offline-selftest` (4 steps) |
| Grok campaign suites | PASS 35 tests | `test --case jev-campaign-offline` (G-1/3/4/7) |
| spend tally | PASS | `test --case jev-tally` (read-only, exit 0) |
| doctor | PASS | all required checks green |
| bootstrap --plan | PASS | read-only planHash |

## Reproduction

| scenario | result |
|---|---|
| foreign cwd (`C:\Temp`) | list runs fine |
| Korean+space path (`C:\Temp\aw 재생성 테스트\kit`) | copied toolkit + `--root` → exit 6 passthrough |
| minimal env (no PATH python) | bindings fallback resolves `~/.local/bin/python3.11.exe` |
| worktree doctor (`--root <cline worktree>`) | exit 10, `worktreeMismatch`, missing untracked tools listed |
| fresh `cmd /c` terminal | all runs above use fresh cmd each |

## Honest gaps

- **Cline Desktop invocation not directly verified.** The dispatcher is
  path-driven (`aw-dev.cmd`/`aw-dev.ps1` absolute path works from any cwd);
  Cline calling it is `PENDING` — document as such, not claimed.
- `READY_FOR_DEVIN.md` (Grok handoff doc) still absent; Grok G-2 wire-live
  and G-9 campaign-runner scripts exist as tools but no live calls are
  registered — the registry only contains offline/loopback cases.
- `java-jev-focused` (L-8) gated on `no-gradle-test-busy`; UP-TO-DATE
  Gradle output yields INCONCLUSIVE(11), never a false PASS.
- Spend ledger is advisory-only (float sums); Cline/agent coding-model
  spend is uncontrolled and unmeasured — see `report` `uncontrolledCost`.
- `start` stays HOLD (`HOLD_NOT_ISOLATED`) until an isolated mock-provider
  launcher exists.

## Upstream / pending

- `READY_FOR_DEVIN.md` — Grok formal handoff document (absent).
- Live Jev/Gemini calls — never registered; out of scope for this toolkit.
- Product Java/JS feature changes — owned by Codex/file owners.
