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
