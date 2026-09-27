# Toolchain auto-select + agent diagnostic tool map

- AGENTS section: `DEMO1-TOOLCHAIN-AUTO-SELECT`, `DEMO1-DEBUG-ENTRYPOINTS`, `DEMO1-SERVER-LIFECYCLE-VERIFY`
- Skill: `.agents/skills/demo1-toolchain-auto-select/`
- Live inventory snapshot (machine TEMP, regenerate with collector): `%TEMP%\demo1-toolchain-inventory.md`

Do not install new verify tools when Gradle / Start-RAG / `scripts/smoke_*` / `scripts/verify_*` / `frontend` npm scripts already cover the change class.

## 1. Task → entry point (reuse these; no ad-hoc curl/java/node)

| Task | Entry point | Output / verdict |
|---|---|---|
| Compile check | `.\gradlew.bat :compileJava -x test` (`+ :processResources`) | BUILD SUCCESSFUL |
| Focused unit test | `.\gradlew.bat test --tests <Fqcn>` | green + fresh JUnit XML |
| Recorded verification run | `python -B scripts/run_verified_command.py --output <fresh-dir> [--source <path>] -- <cmd>` | `run.json` runId/exit/log/source identity; `status` distinguishes still_running vs orphaned_unconfirmed |
| Start dev runtime | `Start-RAG.bat` → `start_rag_stack.ps1 -MetaDisplay -ForceRestart -DevWatch -OpenBrowser` | exit 0 + READY; `var/rag-launcher/<ts>-*/result.json` |
| Start wear runtime | `Start-Meta-Display.bat` → `-MetaDisplay -Wear -OpenBrowser` | same; `springReused=true` means no rebuild (Close first) |
| Stop dev / wear | `Close-RAG.bat` / `Close-Meta-Display.bat` | exit 0 stopped/already-stopped; 1 incomplete (`-DryRun` inspects) |
| Runtime status (read-only) | `Debug-RAG.bat` / `Debug-Meta-Display.bat` (`-Action status`, `-Json`) | exit 0 ready / 3 not-running / 4 degraded; `var/debug/*-status.json` |
| **Post-edit judgement** | `Debug-RAG.bat` / `Debug-Meta-Display.bat` `-Action verify [-WithCompile] [-Json]` | exit 0 verified / 3 not-running / 6 checks-failed; `failedChecks`+`firstFailure`+`hint` |
| Live log watch | `Debug-*.bat -Action tail [-Pattern <re>] [-TailSeconds n]` | bounded follow; closing it never stops the server |
| Timed session evidence | `Debug-Session.bat -Role <dev\|wear> -Action run\|start\|status\|stop\|finalize [-DurationSeconds n] [-LogPath <file>]` | `var/debug/<role>-<ts>-session/` packet.json+packet.md+events.jsonl+stream logs+contexts; GPT/agent handoff bundle |
| Hang / CPU | `Debug-*.bat -Action threads` / `-Action jfr` | `var/debug/threads-*.txt`, `*.jfr` |
| Build/boot log classify | `python tools/build_error_miner.py scan --in <dir_or_zip> --out <base>` or `python -B scripts/analyze_build_output.py --log <file>` | `.json/.csv/.ndjson/.md` patterns; `analysis/build_error_report.json` |
| Named smoke / verify | matching `scripts/smoke_*.ps1`, `scripts/verify_*.ps1`, `*_tests.ps1` | script exit/log |
| Stale classpath suspicion | `scripts/verify_full_test_refresh.ps1` | refresh proof |
| Control-plane topology | `scripts/verify_control_plane_topology.ps1` | topology green |
| Model lock | `powershell -NoProfile -File scripts/check-model-lock.ps1` | allowlist/alias pass |
| Session entry | `python -B scripts/agent_preflight.py --root .` | one JSON: bus/journals/leases/status-doc/tools/root |
| Fold/wear debug snapshot | `python -B scripts/devin_task_orchestrate.py capture --role wear --invoke` | redacted `data/agent-handoff/display-debug/latest.json` |

## 2. `-Action verify` verdict contract

Checks (each `ok` = pass/warn/fail, with `status`, `detail`, `evidence`):

| Check | What it proves | Fails when |
|---|---|---|
| compile (opt-in `-WithCompile`) | `gradlew.bat compileJava processResources -x test` inside the repo | non-zero exit (firstError + `var/debug/*-verify-compile.out.log` + `analysis/build_error_report.json`), timeout 300s, gradlew missing |
| runtime | role runtime process + ownership manifest | no runtime (exit 3); ownership unproven = warn |
| ports | 18180 owned by the runtime pid; 18181/18182 map | 18180 free or foreign-owned; other ports foreign-owned = warn |
| http | loopback probes: `GET /` (2xx/redirect), `/chat-ui` (+marker), `/assets/interview/index.html`, `:18181/actuator/health` (UP) + role probe (wear: display page, receiver.js, bootstrap POST 400; dev: `/api/chat/sync` POST 400) | any probe status/content miss; probes are validation-only, zero API spend |
| exceptions | generic classes over out-log window (`-LogMinutes`): spring-fatal, config (placeholder/bind/ConfigData), port-bind, exception-lines, error-level with first/last hit + linesFromEnd | any fatal class hit; exception/error lines = warn |
| config | `application.yml`, `application-<active profile>.yml` (from live cmdline), `configs/api-routing.yaml` | config-class log hits = fail; missing files = warn |
| freshness | newest active source vs runtime creation; served `receiver.js` sha256 (wear) | `true-stale-candidate` (edited source not live) or served-asset mismatch |
| devwatch | `var/dev-reload/watch.state.json` status/tier/failStreak | missing state or failStreak>0 = warn |
| shared-ollama | 11434/11435 owner+`/api/version`+`/api/tags` | unhealthy = warn (dependency, not app failure) |

Verdict JSON (`var/debug/<role>-<ts>-verify.json`): `verify.checks[]`, `failedChecks[]`, `warnChecks[]`, `firstFailure`, `hint` (per-check next step), plus `log.path`/`exceptions`/`ports`/`runtimes`. Exit: 0 verified | 3 not-running | 6 checks-failed | 1 diag error.

## 3. Failure → first move

| Symptom | First move |
|---|---|
| Build fails | read `verify-compile` log firstError → `analysis/build_error_report.json` → `tools/build_error_miner.py` for corpus patterns |
| verify firstFailure=ports | Close BAT or identify owner; never broad taskkill |
| firstFailure=http | exceptions/config rows → out-log first/last hits → `tail -Pattern` |
| firstFailure=exceptions/config | the reported firstHit line in `log.path` |
| firstFailure=freshness | matching Close+Start pair or `[DEV-RELOAD] socket ready`; stale JVM 200 is no proof |
| firstFailure=runtime | matching Start BAT or `Debug -Action start` |
| status=degraded | ownership/log sections of `-Action status -Json` before any restart |
| Start/Close refuses protected/conflict | `-Action status` port+role map; protected refusals are not failures |
| hint delay/missing | `-Action status` pipeline lastAt/cue counts → `tail -Pattern` → `restart -Loggers` only around the suspect stage |
| runtime issue needs GPT/agent pass | reproduce under `Debug-Session.bat -Action run` (or detached `start`+`stop`); hand the session `packet.md`/`packet.json` - suspect files, error classes, timeline are already extracted |
