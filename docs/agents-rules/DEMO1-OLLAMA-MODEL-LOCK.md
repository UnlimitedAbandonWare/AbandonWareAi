<!-- moved-from: AGENTS.md L26-L30 sha256=ccde926a34cffb489d8e0b3caf1452879a65f69dcd57ae62d8ac2ade93e17017 movedAt=2026-10-03T00:10:40.401654+00:00 -->
<!-- BEGIN DEMO1-OLLAMA-MODEL-LOCK -->
## DEMO1 Ollama Model Lock (DESKTOP-M5NOV6K)
- Model SSOT: `docs/API_ROUTING_SPEC.md` + `configs/api-routing.yaml` (installed allowlist, role defaults, banned→alias map). Live truth: `ollama ls`. Do not invent models; agent-work model/cost order follows the `$demo1-agent-api-spend-guard` SSOT - product lane order stays `configs/api-routing.yaml`.
- Before wiring a model into Display conversate, RAG light, or Spring `llm.fast`/`llmrouter.models.light`, run `ollama show <tag>` or `ollama ls`; if missing use the spec's alias map — never silently pull or Spring-default a banned tag. Verify/enforce: `powershell -NoProfile -File scripts/check-model-lock.ps1`.
<!-- END DEMO1-OLLAMA-MODEL-LOCK -->
