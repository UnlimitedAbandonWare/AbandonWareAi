---
name: demo1-rag-platform
description: "Use when changing the Dynamic RAG Orchestration Platform backend in demo-1"
---

# Demo1 RAG Platform

## Core Rules

- Treat the active runtime surface as an evidence question, not a memory fact. Reconfirm Gradle settings and sourceSets before editing.
- Keep LangChain4j on exactly `1.0.1`. Stop and report if any `dev.langchain4j` dependency resolves to another version or beta line.
- Preserve existing property names and secrets flow. Do not rename env vars, keys, `openssl`, or `opnessl` entries.
- Never log raw API keys, owner tokens, raw user query text, or long source snippets. Use `hasKey`, `keySource`, `maskedTail`, hash, counts, and reason codes.
- Route all prompt construction through `PromptBuilder.build(PromptContext)` or the existing equivalent. Do not assemble final RAG prompts with string concatenation in ChatService paths.
- Patch only the confirmed blocker. Prefer gating, report generation, and fail-soft branches over wrapper classes or duplicated routes.

## Standard Workflow

1. Reconfirm the root: run `Get-Location`, list `settings.gradle*`, `build.gradle*`, and inspect `sourceSets`.
2. Reconfirm active modules with `.\gradlew.bat projects --no-daemon` when available. If only shell wrappers are present, use the repo-local fallback already in `verify_boot*.sh`.
3. Map the task to the current runtime owner before editing:
   - root runtime: `main/java`, `main/resources`
   - app legacy shim: `app/src/main/java_clean`, `app/src/main/resources`
   - inactive or reference unless proven otherwise: `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups
4. Search for an existing seam before adding anything: `rg -n "<ClassOrKey>" main app/src/main/java_clean app/src/main/java`.
5. Preserve fail-soft behavior. Optional providers may disable themselves with an explicit reason; they should not crash boot unless a strict profile requires it.
6. Verify with the narrowest command that proves the changed surface, then broaden only if the edit crosses module boundaries.

## Required Reads

- Read `references/runtime-map.md` when the task touches Gradle, sourceSets, endpoints, search providers, prompts, or verification evidence.
- Use `$demo1-rag-strategy-orchestration` for Plan DSL, MoE, Brave, Hypernova, Self-Ask, Anchor compression, or reranking strategy changes.
- Use `$demo1-rag-resilience-observability` for failure learning, trace/SSE/debug event, autolearn, ablation, or silent-failure work.
- Use `$demo1-local-llm-gpu-gateway` for local model routing, Ollama/vLLM, provider guard, OpenAI-compatible adapters, or embedding dimension changes.
- Use `$nextjs-rag-bff` when exposing this backend through a Next.js App Router frontend or BFF.

