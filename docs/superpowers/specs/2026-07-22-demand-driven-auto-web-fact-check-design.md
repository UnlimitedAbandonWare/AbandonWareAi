# Demand-Driven AUTO Web Fact-Check Design

> **MODEL LOCK (2026-09-17, noted 2026-09-19):** This dated document may reference retired model tags (`qwen3:8b`, `qwen3:30b`, `qwen3-coder:*`, `gemma3:*`, `qwen2.5:7b-instruct`). Do not execute or wire those tags — live SoT: repo `AGENTS.md` model lock + `configs/api-routing.yaml` + `ollama ls`.

## Goal

When chat search mode is `AUTO`, recognize an explicit request to look up facts on the web and route it through the existing web/RAG evidence path without turning ordinary chat or the RAG toggle into an always-on external search.

## Evidence

- `SearchDecisionService` currently recognizes question marks, a few Korean question words, and comparison markers only.
- A current-source browser run completed a local arithmetic prompt, but an AUTO+RAG counterexample prompt exhausted its 120-second request budget while `qwen3:8b` was running through the existing CPU fallback.
- Both local Ollama listeners reported the model loaded with zero VRAM, so enabling three more model calls globally would increase latency without proving better answers.
- `AnswerQualityEvaluator` and `EvidenceRepairHandler` already own the bounded post-retrieval repair path. They should remain the only automatic retry layer.

## Approaches Considered

1. Enable web search and three-agent sampling for every chat. Rejected because it violates the Desktop cost goal and amplifies the observed CPU fallback.
2. Keep only manual LIGHT/DEEP controls. Rejected because explicit AUTO fact-check requests still miss the web path.
3. Add deterministic explicit-intent detection to the existing AUTO decision service. Selected because it reuses the live retrieval, evidence repair, citation, and fail-soft seams.

## Design

`SearchDecisionService` will distinguish two signals:

- explicit web lookup language, such as Korean requests to find, search, fetch, or verify something on the web, and equivalent English phrases;
- fact-verification language, such as fact checking or cross-validation.

AUTO returns `DEEP` only when both signals are present. Explicit lookup without a verification signal returns `LIGHT`. Incidental mentions of web/RAG and ordinary local chat continue to return no-search. Forced modes and the top-level `finalUseWeb` gate remain authoritative.

The change does not enable global ensemble sampling, bypass provider/citation preflight, add retries, or alter `PromptBuilder.build(PromptContext)`. The existing support/support-alternative/falsify prompt contracts and primary-model neutral adjudication remain advisory and demand-driven.

## Error Handling And Safety

- Missing providers or credentials continue to fail soft in the existing provider layer.
- No raw query is added to TraceStore or logs; the existing stable reason string is sufficient.
- Supabase remains read-only and is not part of this patch.
- Browser and Computer remain supporting evidence lanes.

## Verification

1. RED/GREEN unit tests for explicit Korean web fact checking and incidental web mentions.
2. Controller-level AUTO gate test proving the top-level web gate still controls the decision.
3. Focused Gradle tests for search decision, controller AUTO routing, quality evaluation, and evidence repair.
4. Current-source Browser run with AUTO+RAG using a bounded factual/counterexample prompt; provider or local-runtime absence must surface as evidence-needed/fail-soft rather than fabricated evidence.

