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

## P0 목표와 증거 경계

- 10/13 면접 백엔드 마감의 현재 목표 계약·다음 한 가지는 [Primary/P0 entry](../../../docs/PRIMARY_SURFACE.md)를 읽는다. 기존 Spring 메인 생성/복구와 사용자·세션·자원 격리부터 단계별로 검증한다.
- [답변 공개 계약](../../../docs/agents-rules/DEMO1-EVIDENCE-ZERO-RELEASE.md)을 적용하되 정책 요구와 구현 상태를 구분한다. 검색/검증 장애는 유용한 한계 안내·지원 부분/저위험 일반 설명으로 처리하고 초안 전체를 일괄 공개하지 않는다. 취소·known-negative·개인정보·scope·메모리 차단을 유지한다.
- SOURCE_PRESENT, focused 단위/통합 검증, 실기/배포·부하 실측을 별도 상태로 보고한다. 단위 PASS를 실측 PASS로 확대하지 않으며, 개인정보 캐시 타인 재사용·무한 timeout 확대·보호 assert 완화를 금지한다.
- RRF 확장·추가 로깅·보조 기능은 핵심 P0 통과 후 필요한 범위만 처리한다. 문서/지침 작업 권한은 제품 패치·재시작·실모델/유료 호출 권한이 아니다.

## Standard Workflow

1. Reconfirm the root: run `Get-Location`, list `settings.gradle*`, `build.gradle*`, and inspect `sourceSets`.
2. Read current `settings.gradle` and Gradle sourceSets first; run module/build commands only when the task authorizes execution. `settings.gradle.kts` is a sentinel, not the active settings file.
3. Map the task to the current runtime owner before editing:
   - root runtime: `main/java`, `main/resources`
   - empty module: `:app`; legacy `app/src/main/java_clean` and `app/src/main/resources` are reference-only
   - inactive or reference unless proven otherwise: `project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, backups
4. Search the relevant root package/files for an existing seam before adding anything. Do not search quarantined app sources as active implementations; use `docs/agents-rules/DEMO1-LOCAL-FIRST-RAG.md` for verified adoption and pending parallel changes.
5. Preserve fail-soft behavior. Optional providers may disable themselves with an explicit reason; they should not crash boot unless a strict profile requires it.
6. Verify with the narrowest command that proves the changed surface, then broaden only if the edit crosses module boundaries.

## Required Reads

- Read `references/runtime-map.md` when the task touches Gradle, sourceSets, endpoints, search providers, prompts, or verification evidence.
- Use `$demo1-rag-strategy-orchestration` for Plan DSL, MoE, Brave, Hypernova, Self-Ask, Anchor compression, or reranking strategy changes.
- Use `$demo1-rag-resilience-observability` for failure learning, trace/SSE/debug event, autolearn, ablation, or silent-failure work.
- Use `$demo1-local-llm-gpu-gateway` for local model routing, Ollama/vLLM, provider guard, OpenAI-compatible adapters, or embedding dimension changes.
- Use `$nextjs-rag-bff` when exposing this backend through a Next.js App Router frontend or BFF.

