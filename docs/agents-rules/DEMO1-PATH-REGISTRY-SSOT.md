# DEMO1-PATH-REGISTRY-SSOT

Path registry contract for this checkout. Directive:
`DEMO1-DEVIN-PATH-REGISTRY-SAFE-RELOCATE-20261003`.

## Rules

1. **Look paths up by key, not by literal.** New tool code resolves shared
   locations through the registry:
   - Python: `from awx_paths import resolve` → `resolve("lease.locks")`
   - PowerShell: `. scripts/AwxPaths.ps1` → `Resolve-AwxPath -Key 'lease.locks'`
   - CLI: `python -B scripts/awx_paths.py where <key>`
   Resolution order: environment variable → `configs/agent-paths.yaml` →
   `old_paths` fallback (emits `[AWX][path-alias]` on stderr when an old path
   is used).
2. **Register a new shared path before using it.** Add a `key/path/env/
   status/owner/consumers` entry to `configs/agent-paths.yaml` first; never
   introduce a new `C:\AbandonWare\...` literal in code or docs. Detection:
   `python -B scripts/awx_paths.py check`.
3. **Moving a registered path leaves an alias plus a ledger line.** Directory
   moves keep the old location as an NTFS junction (`mklink /J old new`) or a
   `MOVED.json` pointer when junctions are unavailable, for 14 days, and append
   one JSONL line to `data/agent-handoff/_path-moves/moved.jsonl`
   (`ts_kst,key,old,new,alias,consumers_switched,expires`). Inspect recent moves
   with `python -B scripts/awx_paths.py moved`; list expired aliases (no delete)
   with `python -B scripts/awx_paths.py aliases --expired --plan`.
4. **FROZEN keys are never moved without human approval.** Moving them breaks
   concurrent sessions and product runtime reads.
5. **Signal paths (lease/journal/hook/state) require explicit approval** before
   relocation; treat any such need as ASK_ONCE.

## FROZEN keys (as of 2026-10-03)

- `lease.locks`, `lease.events`, `lease.heartbeats`, `lease.scopes`,
  `lease.quarantine`, `patch_drop.root` — concurrent-session signal surface.
- `journal.base` (`data/agent-handoff/codex-autonomy`) — work journal root.
- `state.chatgpt_oauth`, `state.interview_tunnel`, `state.codex_handoff` —
  product runtime reads these (`main/java`, `main/resources`).
- `pathmoves.log` — the move ledger itself.
- `hooks.dir`, `config.project_resources` — Codex hook surface.
- `var.rag_launcher`, `var.codex_runtime`, `db.meta_display`,
  `port_lease.dir` — runtime/product consumers.
- `git.exe`, `user.home`, `user.downloads`, `python.venv` — local toolchain/OS
  anchors.
- `repo.root` — the checkout root itself.

## Status grades

- `FROZEN` — registered, never moved this cycle.
- `ALIASED` — logical key registered, physical location unchanged; a new
  location is proposed for a later step (e.g. `assist.output_root`,
  `runtime_state.proposed`).
- `MOVE-SAFE` — eligible to move only when all five P3 conditions pass
  (consumers switched, no writes in 72h and no live session, not read by
  main/resources/bat/hooks, alias left behind, post-move re-scan shows zero
  broken references).
- `STABLE` — correct where it is (e.g. `scripts.dir`, `docs.agents_rules`).

## Authoritative field reference

`.agents/skills/demo1-path-registry/references/registry-fields.md`
