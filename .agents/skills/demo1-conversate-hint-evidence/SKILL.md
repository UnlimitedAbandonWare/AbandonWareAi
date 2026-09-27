---
name: demo1-conversate-hint-evidence
description: >-
  Use when Fold6/Conversate/Meta Display hints show fixed evidence-refusal text,
  wipe a useful answer after empty or fragmented search, or route stable
  definition questions into retrieval. Preserves transcript, FAST for concepts,
  general hints without fabricated citations, and Fold6 diagnostics vs lens
  caption separation. Not for ASR mic hardware, Brave key routing, full RAG
  platform rewrites, or past-context input windows (`$demo1-conversate-hint-context`).
---

# Demo1 Conversate Hint Evidence

## Goal

Keep working transcription. On the lens / conversate card, show a **useful short answer or next step**, not a stuck "근거 부족" (or equivalent fixed refusal) when the question is answerable as general knowledge or when a weaker useful hint already exists.

## Seams (edit these, not parallel stacks)

- `ConversateQuestionPolicy` — FAST vs evidence routing
- `ConversateCardPrompt` — answer shape when evidence is thin
- `ConversateApiCueService` — generate / preserve / refuse overwrite
- `DisplayConversateController` — phone diagnostics fields only (`hintPath`, `evidenceStatus`, …)
- Lens text still follows `$demo1-meta-display-simple-caption` (conversation + short hint; no diagnostic overlay as primary UI)

Reference task (patterns, not automatic truth): `data/agent-handoff/codex-autonomy/hint-flow-01a0b1c3/report.md`

## Hard rules

1. **Stable definitions / comparisons / conceptual follow-ups → FAST** (`GENERAL_KNOWLEDGE`). Do not send "하이젠베르크 불확정성 원리가 뭐냐?"-class questions into retrieval by default.
2. **Keep evidence routing** for explicit verification, current facts, private data, and high-stakes particulars.
3. **Empty / fragmented / insufficient evidence** must not blank a valid general hint or replace model text with a fixed refusal boilerplate. Prefer a short substantive general answer or a concrete conversational next step. Still forbid inventing unsupported current facts and fake citations.
4. **Preserve the first useful hint** if supplemental retrieval/generation fails or returns a weaker/refusal result.
5. **Reject unusable fragmented citations** even if later evidence is appended; do not re-promote truncated junk as grounding.
6. **Fold6 diagnostics ≠ lens.** Path/status/counts stay on the phone diagnostic surface. Glasses get caption/hint text only.
7. **Do not** change ASR transcription, launchers, auth, or global EvidenceGate constructors unless the live failure proves that seam. Prefer focused Conversate tests over rewriting the whole suite when unrelated `EvidenceGate` tests are already broken.

## Verify (smallest)

1. Red: focused Conversate tests that reproduce refusal-overwrite / FAST misroute.
2. Green: same focused suite + existing Conversate/caption/HTTP tests you already touch.
3. Optional synthetic browser harness with **no paid outbound** providers.
4. Live Fold6 / server restart only when the user asks, or when tests are green **and** the running process is task-owned. If the user will self-verify in seconds to save tokens, **stop** — see `$demo1-agent-api-spend-guard`.

## Exit report

`route=conversate-hint-evidence`, `fastVsSearch=…`, `preserveHint=true|false`, `lens=simple-caption`, `liveRestart=done|skipped-user-verify|blocked-unowned`.
