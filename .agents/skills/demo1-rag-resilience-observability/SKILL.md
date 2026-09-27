---
name: demo1-rag-resilience-observability
description: "Use when working on demo-1 resilience, failure learning"
---

# Demo1 RAG Resilience Observability

## Core Rules

- Reuse existing `TraceStore`, `DebugEventStore`, SSE publishers, autolearn trackers, and recovery policy classes. Do not create a duplicate event store.
- Keep diagnostics stable, redacted, and allowlisted. No raw query, raw snippets, API keys, owner tokens, or long payloads.
- Preserve durable last-cycle snapshots outside request-local `ThreadLocal` state when the user asks for loop-level diagnosis.
- Treat OpenClaw or external evaluators as offline/advisory only unless the user explicitly changes runtime policy.
- Do not collect provider-disabled, low-signal, contaminated, or low-diversity traces into retrain data.
- Prefer evidence-preserving quarantine over deletion for suspicious vector/memory artifacts unless a cleanup skill explicitly permits deletion.

## Workflow

1. Identify the failure class: provider-disabled, zero-result after-filter, timeout, rate-limit, citation miss, model blank, context contamination, duplicate FQCN, build failure, or silent failure.
2. Read `references/signals-map.md` for concrete classes, trace keys, and endpoints.
3. Add or adjust the smallest redacted breadcrumb at the stage where the signal is created.
4. If an API exposes diagnostics, keep it read-only unless the user asks for a control endpoint.
5. For autolearn, preserve loop-level summaries: failure summary, hotspot label, contamination score, lane coverage, requery confirmation, and vector metadata.
6. Verify with unit tests when available, then run the narrow Gradle target for the active root.

## Output Expectations

- Report the exact file path, trace key, and reason code added or changed.
- Distinguish provider-disabled from true zero results and after-filter starvation.
- Include the verification command and observed result.
- Include one residual blind spot if the signal is inferred rather than directly measured.
