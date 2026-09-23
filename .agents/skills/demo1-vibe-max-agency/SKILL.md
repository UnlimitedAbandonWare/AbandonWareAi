---
name: demo1-vibe-max-agency
description: Use when the user asks for vibe coding, maximum agency, or autonomous four-agent operation (Devin/Grok/Codex/Cline) in demo-1 — same root, same evidence, same restart contract.
---

# demo1 Vibe-Max-Agency

"최대" = 하드 금지를 제외한 읽기·재기동·lease 범위 쓰기. 전체 계약은 `AGENTS.md` `DEMO1-VIBE-MAX-AGENCY` 블록이 SSOT다.

## Do

1. Run `Vibe-Max-Agency.bat -Apply` from `C:\AbandonWare\demo-1\demo-1\src` (idempotent; `-Check` is report-only).
2. Share/inspect `var/debug/vibe-max-agency-status.json` — per-agent open/blocked read paths, `agent.db-context` local flag, meta_display_db `status`/`live status`, hard-block integrity.
3. Follow the `DEMO1-VIBE-MAX-AGENCY` section in `AGENTS.md`: `agent_preflight.py` → `awx_device_bus.py start` → `$demo1-vibe-skill-router` (or `$demo1-core-request-router`); maximum reads of device-bus events/registry, `var/debug/**`, `logs/debug-events*.ndjson`, meta-display DB exports, `/agent/db-context/*` (local profile); writes only inside journal+checkpoint(+lease) scope.
4. Overlapping lease scope → report blocked/wait. Verification claims keep tool-ran vs target-health vs build-ran vs full-verification distinct (403 = auth-blocked, not DOWN).

## Don't

- Never relax hard constraints: no `.secrets/`/`apikey.txt`/`.env*`/`shared.env*` reads or prints, no openssl key changes.
- Never force-release, recover, or delete another agent's lease — including expired leases whose owner is not proven dead.
- No paid-provider calls or production-secret deploys without explicit user authorization.
- Do not delete the skill catalog or broadly modify `.agents/skills/`; do not enable `agent.db-context` in public/base profiles.
- Do not start/stop/ForceRestart the live runtime just to verify these rule/config changes (see `.clinerules/00-demo1-cline-bridge.md` Runtime protection).
