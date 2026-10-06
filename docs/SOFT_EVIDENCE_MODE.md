> **역사/참고 전용 (2026-10-06 재분류)** — 당시 tree-copy 진단과 property-gated soft allow 제안이다. 현재 구현 완료 증거나 초안 일괄 공개·설정 변경·재시작 지시로 읽지 않는다.
> 현행 기준: [문서·P0 진입점](PRIMARY_SURFACE.md), [운영 규칙](../AGENTS.md), [현재 현황](PROJECT_STATUS.md), [답변 공개 계약](agents-rules/DEMO1-EVIDENCE-ZERO-RELEASE.md). 아래 원문은 회귀·설계 근거로 보존한다.

# Soft-evidence mode

## Problem
Meta Ray-Ban Display / chat hints go blank or show only "근거 없다" when RAG returns zero citations. Agent-style orchestration still works; the RAG evidence gate was too strict.

## Root cause (verified from tree copies)
1. `EvidenceGate.hasSufficientCoverage` hard-returns `false` when `totalEvidence==0`.
2. `AnswerExpanderService.buildExpandPrompt` told the model to reply exactly `[NO_EVIDENCE]` when draft lacked detail and evidence was empty (callers may surface that or empty answers).
3. Citation / FinalSigmoid / jammini aggressive settings (`require_official`, min counts, `jammini.guard.mode=aggressive`) degrade answers.
4. `ConversateApiCueService` returns `CUE_EVIDENCE_UNAVAILABLE` with a null card when `evidence.isEmpty()` after `RAG_CUE`.

## Fix
Property-gated soft allow + prompt/keep-draft changes. Local profile sets soft defaults; production can re-tighten.

## Restart
- Config-only: restart Spring Boot / `Start-RAG.bat` to pick up YAML/properties.
- Java patches: rebuild then restart.
