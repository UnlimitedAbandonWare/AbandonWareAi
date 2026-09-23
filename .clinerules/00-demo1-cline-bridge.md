# demo-1 — Cline bridge

`AGENTS.md` at the project root is the authoritative rule SSOT and is auto-loaded by Cline. This file adds only Cline-specific wiring — do not duplicate AGENTS.md content here.

## Project root
- `C:\AbandonWare\demo-1\demo-1\src` — resolve all relative paths from here. `D:\ai\Cline` is only the app install dir; never edit it.

## Rules coverage
- `.windsurf/rules/*.md` are Windsurf-format extracts of AGENTS.md sections. Cline does not auto-read that directory — AGENTS.md already covers their content. Do not copy them into `.clinerules/`.
- `.devin/`, `.codex/` files belong to other agents' runtimes; `.codex/hooks` are NOT wired into Cline. Do not import them.

## Skills
- `.agents/skills/<name>/SKILL.md` is Cline's native project skills location — use as-is; do not recreate under `.cline/skills` or clone bodies.
- Pick at most one primary skill per task via `.agents/skills/INDEX.md`; Meta Display/RAG specialist skills only when the task touches that area. Alias skills (e.g. `safe-source-edit`) point to an SSOT skill — read the referenced file, don't edit the alias.

## File-changing work
- Follow AGENTS.md `DEMO1-WORK-LEDGER` + `DEMO1-AGENT-GUARD-COMMON`: read `docs/PROJECT_STATUS.md` → `python -B scripts/agent_preflight.py --root .` at task entry → `python -B scripts/work_journal.py open` → per-change preserve via `scripts/codex_work_checkpoint.py` → record real verification. Read-only work needs none of it.
- Bounded writes go through `codex_work_checkpoint.py apply --run <cycle> --target <path> --content-file <file>` (refuses stale preimages); recovery via `restore --run <cycle>` (stages to `--staging` first when unsure; never overwrites foreign changes). `docs/PROJECT_STATUS.md` rows only via `python -B scripts/status_doc.py` (read → update-row/append-row with `--expect-sha256`).
- Re-read a file right before editing; if it changed mid-work, hold only that file — Devin/Codex/Grok share this checkout.
- Never use Cline "Restore checkpoint" / message-edit undo to revert files in this shared tree; recovery comes only from the task's checkpoint dirs.

## Secrets
- `.clineignore` blocks `.secrets/`, `.env*`, `apikey.txt`, `config/secrets/`, `shared.env*`, `data/device-resources/*` (except `events/` and `registry/`, which are shared evidence). Do not ask to read or print secrets — reference env names only. openssl/opnessl key name/value/format/structure is immutable.

## Runtime protection
- Never run `Start-RAG.bat`, `Start-Meta-Display.bat`, `start_rag_stack.ps1` (any start/stop/ForceRestart), DevWatch restarts, or kill the live JVM/mic/transcript/browser session to verify rule or config changes. Ports 18180–18182 may be serving a worn Meta Display session.
- Fold 안경 표시 설정 timings (transcript/hint hold, page interval, **and** cue/generation cycle) follow persisted settings. YAML 20s / 2.5s / 10s / 180s are defaults. See AGENTS.md `DEMO1-META-RAYBAN-DISPLAY-RUNTIME` — do not treat those seconds as frozen.

## Model / cost
- Keep the configured free model (Kimi K3). No BYOK/paid-model switch, no auto top-up. If the free tier ends or the same call keeps failing, stop and report — no same-way retry loops.
- `.mcp.json` (supabase) is not wired into Cline in this setup; do not auto-enable extra MCP servers or hooks.

## Vibe-Max-Agency
- `Vibe-Max-Agency.bat -Check|-Apply` (AGENTS.md `DEMO1-VIBE-MAX-AGENCY`) applies only the documented read-path ignore exceptions and local `agent.db-context` enablement; it never permits forced lease release, secret reads, or Restore-checkpoint rollback of the shared tree.

## Mutable spec
- `$demo1-mutable-spec-policy` (AGENTS.md `DEMO1-MUTABLE-SPEC-POLICY`, `docs/MUTABLE_SPEC_POLICY.md`): API/model/Display/routing/port values are mutable — re-read the SSOT (`configs/api-routing.yaml`, `docs/API_ROUTING_SPEC.md`, `ollama ls`) before implementing or verifying; only hard constraints are constants.
