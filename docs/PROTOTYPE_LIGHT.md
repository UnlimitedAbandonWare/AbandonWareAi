# demo-1 Prototype Light Mode (no admin)

Policy block: `AGENTS.md` `DEMO1-PROTOTYPE-LIGHT`. This page is the one-screen
version for daily vibe work on `C:\AbandonWare\demo-1\demo-1\src` — the only
canonical root. `\\desktop-m5nov6k\MacSrc`, `Y:\`, and Notebook SMB paths apply
only when the user explicitly names them.

## Allowed by default (user permission, no admin)

| # | Command | Purpose |
|---|---------|---------|
| 1 | `Start-RAG.bat` | Dev runtime 18180/18181/18182 (`local,meta-display`) |
| 2 | `Close-RAG.bat` / `Close-Meta-Display.bat` | Stop owned runtime roles only |
| 3 | `Debug-RAG.bat` / `Debug-Meta-Display.bat` | Read-only status / verify |
| 4 | `Read-RAG-Debug.bat` | Latest boot/debug summary (`var/rag-launcher/LATEST.json`) |
| 5 | `python -B scripts/agent_preflight.py --root .` | Task entry guard check |
| 6 | `python -B scripts/work_journal.py open|note|close` + `codex_work_checkpoint.py` | Work ledger for file changes |
| 7 | `powershell -File __patch_drop__/source_edit_session.ps1 -Action begin|verify|end` | Target-scoped edit lease |
| 8 | `python -B scripts/conditional_local_git.py policy|check|scan` | Local Git read/scan |
| 9 | `python -B scripts/agent_git_vibe_commit.py --repo . --path <owned> --message-file <f>` | One local commit, only on user request |
| 10 | `.\gradlew.bat :compileJava -x test` / `test --tests <Fqcn>` | Compile / focused unit test |

## Default OFF — never run unless the user names it

- Skills: `demo1-docker-autograder`, `demo1-macsrc-*` (4), `demo1-patchdrop-manual-default`,
  `patchdrop-safe-patch-orchestrator`, `desktop-smb-ack`, `notebook-smb-handoff`,
  `macmini-safe-patch-assistant` — all carry `PROTO-LIGHT` in their description.
- Three-node / SMB `agent-prompts` packets (MacSrc decommission, three-node orchestration).
- `__patch_drop__/`, `data/agent-handoff/`, `agent-prompts/` wholesale grep or read — residue, not working surface.

## Forbidden in light mode (needs admin or new infra)

- Service install, `sc`/scheduled-task registration, firewall or share-ACL changes.
- `docker pull`/build/run for grading; SMB share re-enable; new daemons or background watchers.
- New SaaS signups/keys — prefer local Ollama, then existing `configs/api-routing.yaml` env APIs.
- Any destructive Git (`reset --hard`, `clean`, `add -A`, push, remote mutation) — unchanged by light mode.

## Why it got heavy (2026-09-24)

`.agents/skills` ≈119, `agent-prompts` ≈135, `__patch_drop__` ≈1646 files,
`data/agent-handoff` ≈56 dirs, `scripts/*.ps1` ≈149 — a single-desktop prototype
carrying multi-node MacSrc/SMB/patch-orchestration layers. Light mode keeps the
files (no mass delete) and gates them by default.
