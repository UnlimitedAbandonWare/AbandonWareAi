<!-- moved-from: AGENTS.md L375-L383 sha256=e4ae6132eb03226e20932e0c47a6c9284ff604fa4d5c4a1c48c7130cf8cee34f movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-PROTOTYPE-LIGHT -->
## Prototype light mode (no admin, minimal default surface)
- Canonical root is `C:\AbandonWare\demo-1\demo-1\src` only. Do not default to UNC `MacSrc`, `Y:\`, or Notebook SMB paths; they apply only when the user explicitly names them.
- Default tools: `Start-RAG.bat` / `Debug-RAG.bat` / `Read-RAG-Debug.bat` (when present), `scripts/conditional_local_git.py` + `agent_git_vibe_commit.py` for user-requested commits, and the existing chat / Meta Display BAT path — nothing heavier without an explicit ask.
- Default OFF (skip unless the user names it): `demo1-docker-autograder`, `macsrc-*`, `*-smb-*`, `notebook-smb-handoff`, `desktop-smb-ack`, `patchdrop-safe-patch-orchestrator`, `demo1-patchdrop-manual-default`, `macmini-safe-patch-assistant`, and three-node SMB `agent-prompts`. Gated skills carry `PROTO-LIGHT` in their description first line.
- No admin required: never install services, pull Docker images, change SMB share ACLs, register scheduled tasks, or touch firewall settings; run only what works under the current user.
- Agent-work model/cost order: `$demo1-agent-api-spend-guard` SSOT (product GPU fallback keeps `$demo1-gpu-power-fallback` order); use already-configured env APIs; no new SaaS accounts, daemons, or background watchers.
- Search cheaply: no wholesale grep/read of `__patch_drop__/`, `data/agent-handoff/`, or `agent-prompts/` unless the user explicitly asks — they are reference residue, not the working surface. One page: `docs/PROTOTYPE_LIGHT.md`.
<!-- END DEMO1-PROTOTYPE-LIGHT -->
