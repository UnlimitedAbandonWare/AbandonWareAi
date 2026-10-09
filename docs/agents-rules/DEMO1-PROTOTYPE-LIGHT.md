<!-- moved-from: AGENTS.md L383-L391 sha256=e62f0a539848ee7fa86d7da249615bcc42828dac79a1823ca5da45833600db90 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-PROTOTYPE-LIGHT -->
## Prototype light mode (no admin, minimal default surface)
- Canonical root is `C:\AbandonWare\demo-1\demo-1\src` only. Do not default to UNC `MacSrc`, `Y:\`, or Notebook SMB paths; they apply only when the user explicitly names them.
- Default tools: `Start-RAG.bat` / `Debug-RAG.bat` / `Read-RAG-Debug.bat` (when present), `scripts/conditional_local_git.py` + `agent_git_vibe_commit.py` for user-requested commits, and the existing chat / Meta Display BAT path — nothing heavier without an explicit ask.
- Default OFF (skip unless the user names it): `demo1-docker-autograder`, `macsrc-*`, `*-smb-*`, `notebook-smb-handoff`, `desktop-smb-ack`, `patchdrop-safe-patch-orchestrator`, `demo1-patchdrop-manual-default`, `macmini-safe-patch-assistant`, and three-node SMB `agent-prompts`. Gated skills carry `PROTO-LIGHT` in their description first line.
- No admin required: never install services, pull Docker images, change SMB share ACLs, register scheduled tasks, or touch firewall settings; run only what works under the current user.
- Provider order is scope-specific: agent-work = Codex credits → external paid API → free → local Ollama (last); product main `/chat` = API/OAuth-first, Ollama last; RAG/embed retain the 3090-local lane and classified incident fallback (`docs/agents-rules/DEMO1-RTX3090-WATCH.md`). Use existing configured providers; no new SaaS accounts, daemons, or background watchers.
- Search cheaply: no wholesale grep/read of `__patch_drop__/`, `data/agent-handoff/`, or `agent-prompts/` unless the user explicitly asks — they are reference residue, not the working surface. One page: `docs/PROTOTYPE_LIGHT.md`.
<!-- END DEMO1-PROTOTYPE-LIGHT -->
