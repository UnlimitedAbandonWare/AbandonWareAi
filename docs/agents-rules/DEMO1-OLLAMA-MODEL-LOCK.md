<!-- moved-from: AGENTS.md L27-L31 sha256=0b29cfeec209ee0f09020a3e7d96b4a11ab84645768546042de64290ce9f40e6 movedAt=2026-10-04T02:47:32.936911+00:00 -->
<!-- BEGIN DEMO1-OLLAMA-MODEL-LOCK -->
## DEMO1 Ollama Model Lock (DESKTOP-M5NOV6K)
- Model SSOT: `docs/API_ROUTING_SPEC.md` + `configs/api-routing.yaml` (installed allowlist, role defaults, banned→alias map). Live truth: `ollama ls`. Do not invent models; prefer free/local → cheap → paid.
- Before wiring a model into Display conversate, RAG light, or Spring `llm.fast`/`llmrouter.models.light`, run `ollama show <tag>` or `ollama ls`; if missing use the spec's alias map — never silently pull or Spring-default a banned tag. Verify/enforce: `powershell -NoProfile -File scripts/check-model-lock.ps1`.
<!-- END DEMO1-OLLAMA-MODEL-LOCK -->
