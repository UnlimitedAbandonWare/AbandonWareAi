# Local LLM And Embedding Runtime Map

> **MODEL LOCK (2026-09-17):** Do **not** use dead tags `qwen3:8b`, `qwen3:30b`, `qwen3-coder:*`, `gemma3:*`, `qwen2.5:7b-instruct` as defaults. Live SoT: `docs/API_ROUTING_SPEC.md` + `configs/api-routing.yaml` + `ollama ls`.

## Memory-Backed Baseline To Revalidate

- Main/default chat (RTX 3090 / 11434): `gemma4:26b` (alt: `gemma4:31b`, `qwen3.8:27b`).
- Fast/helper / Display+RAG light lane (RTX 3060 / 11435): **`qwen3.5:9b`** (NOT `qwen3:8b` — not installed).
- Judge/critic/coder (3090): `qwen3.8:27b`.
- Vision: `qwen3-vl:8b`.
- Embedding (RAG): `qwen3-embedding:4b` or `qwen3-embedding:latest` (alts: `nomic-embed-text`, `bge-m3`).
- Prefer 11435 for 3060 fast/embed; probe `/api/version` before assuming 11434.

These baselines must match live `ollama ls` before editing config. If a tag is missing, map via alias — never invent a pull of deprecated names for Meta Display or RAG light paths.

## Key Files

- OpenAI-compatible endpoint handling:
  - `main/java/com/example/lms/llm/OpenAiChatModel.java`
  - `main/java/com/example/lms/llm/OpenAiCompatBaseUrl.java`
  - `main/java/com/example/lms/llm/OpenAiEndpointCompatibility.java`
- Router and local clients:
  - `main/java/com/example/lms/service/llm/LlmRouterService.java`
  - `main/java/com/example/lms/service/llm/LocalOpenAiClient.java`
  - `main/java/com/example/lms/service/llm/LocalVllmClient.java`
  - `main/java/com/example/lms/service/llm/DualGpuScheduler.java`
- Provider/key guard:
  - search for `ProviderGuard`, `KeyResolver`, `ModelGuard`, `LLMProperties`, and `AppSecretsProperties`
- Matryoshka embedding:
  - `main/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingNormalizer.java`
  - `main/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingModelPostProcessor.java`
  - search for `OllamaEmbeddingModel`
- Local model config:
  - `main/resources/application-llm.yaml`
  - `main/resources/application-local-llm.yml`
  - `main/resources/application*.yml`
  - `configs/api-routing.yaml` (live SoT; `models.manifest.yaml` is DEPRECATED-AS-SOT)

## Safe Probes

```powershell
ollama list
Invoke-RestMethod -Method Get http://127.0.0.1:11435/api/tags
Invoke-RestMethod -Method Post http://127.0.0.1:11435/api/chat -ContentType 'application/json' -Body '{"model":"qwen3.5:9b","messages":[{"role":"user","content":"ping"}],"stream":false}'
```

Do not include external API keys in probes. If a local OpenAI-compatible endpoint is used, keep it loopback-only unless the user explicitly provides an internal server address.
