<!-- moved-from: AGENTS.md L143-L148 sha256=b6785dbda307430bffce059ae426102e5244d0f9b0b3074c7ab2f80501efcf46 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-OPENROUTER-DESKTOP-ROUTING -->
## OpenRouter Desktop routing
- Routing SSOT: `docs/provider-limits/openrouter-desktop-routing.md`. OpenRouter is used via the **Desktop app** (Settings → Providers → OpenRouter → API key); `stealth/space-bunny-alpha` is a **secondary pre-screen investigator** only ($0 preview, ~1M ctx, multimodal).
- Do not install a new CLI agent for Space Bunny; do not send secrets/credentials or `.secrets/` paths to stealth models (`allowSecrets=false`, `productionCritical=false`).
- Do not replace Codex/Devin/Grok/Kimi with Bunny and do not wire it into production Java/`api-routing.yaml` paths — Bunny output stays candidate-only until Codex/Devin verify and patch.
<!-- END DEMO1-OPENROUTER-DESKTOP-ROUTING -->
