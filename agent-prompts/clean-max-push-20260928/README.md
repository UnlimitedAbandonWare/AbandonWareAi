# Clean rail — MAX-PUSH dynamic kits

Contract: `DEMO1-CLEAN-MAX-PUSH-DYNAMIC-RAIL-20260928`
Project Root: `C:\AbandonWare\demo-1\demo-1\src`
Codex goal text: `C:\Users\nninn\Downloads\PASTE_CODEX_MAX_PUSH_B_AND_MAIASWSN.txt`
Codex journal already open: `max-push-b-perf-0928-50df21d2` (agent `codex-max-push`)

Clean prepares tools, rules, and scripts only. Codex owns the product patch
(`main/java`, `src/test/java`, product `main/resources`, `chat.js`, and
`docs/PROJECT_STATUS.md` on that journal). This packet does not edit those paths.

## Switch

`CURRENT_KIT.md` is the only switch. Start state is **A** because Codex Track A
is M9, then ReleaseGate, then B04+ only when reproduced. Move A→B→C→D only
after that kit's focused command has a recorded exit code. Kit E stays
available beside the active kit. Do not turn every kit on at once.

| Kit | When | File |
|---|---|---|
| A | M9 / ReleaseGate / B04+ regression | `KIT_A_COMMANDS.md` |
| B | F01–F04, one F at a time | `KIT_B_COMMANDS.md` |
| C | F08 / F09 / F07 / F10 | `KIT_C_COMMANDS.md` |
| D | F05 / F06 / F11 / F12 only if the probe says active | `KIT_D_SKIP_INACTIVE.md` |
| E | Always: multi-session, git, H2 DDL classify | `KIT_E_MULTISESSION.md` |

Stuff4 is the role map in `PLUGIN_ROLE_MAP.md`. When the original Stuff4 text
disagrees with that map, the map wins.

## Codex header

Copy `CODEX_HEADER.md` and keep the kit letter equal to `CURRENT_KIT.md`.

## Commands Clean may run

```powershell
. .\scripts\max_push_kit_env.ps1
python -B scripts/max_push_skip_inactive.py --root .
python -B scripts/max_push_executor_log_scan.py --root .
python -B scripts/max_push_done_guard.py --text "<completion sentence>"
```

`max_push_kit_env.ps1` is session-local for focused Gradle. It does not restart
Spring and it does not change the live Start-RAG host id `desktop-meta-display`.
