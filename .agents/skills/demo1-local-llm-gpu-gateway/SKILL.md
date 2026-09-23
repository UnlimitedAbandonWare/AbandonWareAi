---
name: demo1-local-llm-gpu-gateway
description: "Use when working on demo-1 local LLM, embedding"
---

# Demo1 Local LLM GPU Gateway

## Core Rules

- Keep LangChain4j dependency purity at `1.0.1`. Use OpenAI-compatible HTTP protocol adaptation instead of adding Ollama beta dependencies.
- Treat live local runtime evidence as source of truth: check ports, `/api/tags`, `/api/chat`, or `/v1/chat/completions` before changing model strings.
- Do not assume port `11435` is alive. Prefer the already-running endpoint unless a live check proves otherwise.
- Keep default/main chat model, fast/helper model, and embedding model separate.
- Do not change embeddings unless the user explicitly asks. If embedding dimensions change, update vector index compatibility and tests.
- External provider keys must be property-first then ENV, and duplicate configured sources should fail fast by existing policy.
- Local placeholders may exist only when code treats them as local non-external placeholders.

## Workflow

1. Read `references/runtime-models.md` for current model and file seams.
2. Probe live endpoints without printing secrets:
   - `ollama list`
   - `Invoke-RestMethod http://127.0.0.1:11434/api/tags`
   - OpenAI-compatible smoke to `/v1/chat/completions` only with a local endpoint.
3. Locate current owner with `rg -n "OpenAiChatModel|ProviderGuard|KeyResolver|Ollama|vLLM|Matryoshka|EmbeddingModel|LlmRouterService|11434|11435" main app/src/main/java_clean`.
4. Patch the smallest policy/config adapter, not every caller.
5. For Matryoshka slicing, preserve trace keys for `embed.targetDim`, `embed.actualDim`, and slice/pad reason without logging embedding values.
6. Verify with targeted tests plus `compileJava` or `bootJar -x test` if the change affects auto-configuration.

## Do Not

- Add `dev.langchain4j` beta or `0.x` dependencies.
- Replace the current RAG pipeline with a new gateway stack.
- Move secrets into files or logs.
- Treat blank/dummy external keys as usable credentials.
- Enable heavy GPU/ONNX/autolearn defaults without explicit opt-in.

