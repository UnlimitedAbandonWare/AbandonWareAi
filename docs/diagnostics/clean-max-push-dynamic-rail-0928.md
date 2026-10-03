# Clean MAX-PUSH dynamic rail (2026-09-28)

Contract: `DEMO1-CLEAN-MAX-PUSH-DYNAMIC-RAIL-20260928`
Journal: `clean-max-push-rail-0928-d51c03f1`
Packet: `agent-prompts/clean-max-push-20260928/`
Active kit: A (`CURRENT_KIT.md`)

Clean prepared tools, rules, and scripts. Codex journal
`max-push-b-perf-0928-50df21d2` owns the product patch. This session did not
edit `main/java`, product `main/resources`, `chat.js`, or `docs/PROJECT_STATUS.md`.

## What is ready

| Piece | Path |
|---|---|
| Switch | `agent-prompts/clean-max-push-20260928/CURRENT_KIT.md` (`kit: A`) |
| Role map | `PLUGIN_ROLE_MAP.md` (Stuff4 reading; the map wins on conflict) |
| Kits | `KIT_A_COMMANDS.md`, `KIT_B_COMMANDS.md`, `KIT_C_COMMANDS.md`, `KIT_D_SKIP_INACTIVE.md`, `KIT_E_MULTISESSION.md` |
| Codex header | `CODEX_HEADER.md` |
| Env | `scripts/max_push_kit_env.ps1` (dot-source; does not restart Spring) |
| Skip probe | `scripts/max_push_skip_inactive.py` |
| Log counts | `scripts/max_push_executor_log_scan.py` |
| Done warning | `scripts/max_push_done_guard.py` |
| Pointers | `.grok/rules/clean-max-push-dynamic-rail-20260928.md`, `.clinerules/62-clean-max-push-dynamic-rail.md` |

`AGENTS.md` was left unchanged. The six-line pointers are the two rule files above.

## Verification in this session

| Command | Result |
|---|---|
| `python -B -m unittest scripts.test_max_push_skip_inactive` | exit 0, 9 tests OK |
| `python -B scripts/max_push_skip_inactive.py --root .` | exit 0. F05, F06, F11, F12 all `SKIP_INACTIVE`. `enablesNothing=true`. Profiles from `var/rag-launcher/LATEST.json`: `local,meta-display`. |
| `python -B scripts/max_push_executor_log_scan.py --root . --limit 5` | exit 0, status `observed`, 5 files, every pattern count 0. That is a count, not a pool pass. |
| `python -B scripts/max_push_done_guard.py --text "Kit A focused tests only"` | exit 0, `ok=true` |
| dot-source `scripts/max_push_kit_env.ps1 -PrintOnly` | printed `kit=A` and the three-line header |

F12 still names `UnifiedRagOrchestrator.java` as an optional field. The directive class has no component stereotype and no `@Bean` factory in the files that name it, so the probe does not call that activation. It does not register a bean.

Not run here: Gradle focused suites, `node --test`, Verify-RAG, Browser, a full suite, Neo4j, and `classify_h2_ddl_warnings.py --latest`. Those belong to Codex on Kit A, or stay `SKIP_INACTIVE` / classify-only on Kit D and Kit E.

## Holds

`docs/PROJECT_STATUS.md` is in the Codex MAX-PUSH journal scope, so this session did not append a row. No commit and no push.
