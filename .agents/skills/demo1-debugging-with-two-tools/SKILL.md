---
name: demo1-debugging-with-two-tools
description: Use when demo-1 debugging needs AI hypotheses, validated MCP observations
---

# Debugging with Two Tools

## Core Contract

Work like a spire: one narrow evidence base, one elevated target. Lock
one first failing layer; fact, inference, and suspicion set;
one falsifiable hypothesis and disproof; one patch intent and rollback;
one observation slot; and one verification slot. Stop when proof changes.

Suspicion cannot authorize an edit. If the failure class changes, close the
cycle before selecting another tool or patch.

## Two Evidence Slots

| Slot | Decision | Default |
| --- | --- | --- |
| Observation slot | Does the hypothesis survive? | `Debug-RAG.bat` / `Debug-Meta-Display.bat -Action status` (or `tail`/`threads`), else `rg` plus targeted reads |
| Verification slot | Is the patch claim false? | one focused test or contract script |

Editing and ledger updates are not evidence slots. A third evidence tool
starts a new cycle only after verification changes the failure class. Broad
scans, logs, suites, boot, UI, or providers require a recorded decision reason.

## First Failing Layer

Classify earliest to latest:

1. workspace, Git, sourceSet, version, or PatchDrop;
2. build or Spring context;
3. request, session, or query transformation;
4. retrieval or provider;
5. KG, fusion, or rerank;
6. LLM routing or generation;
7. output, evidence, SSE, or UI.

Patch the earliest proven layer, never a downstream symptom while an earlier
layer remains red.

## Priority Gate

Use `P0 → P1 → P2`.

- P0: build, ownership, security, redaction, PromptBuilder, version purity,
  data loss, or unsafe outbound calls.
- P1: fail-soft, cancellation, timeout, observability, bounded resources,
  verification, or rollback.
- P2: narrow cleanup, deduplication, metrics, or documentation with measurable
  debugging value.

P2 starts only after every known P0 and P1 item is verified or recorded
`SKIP` or `evidence_needed`. A P2 patch stays in one owner, touches
at most three files, and has a focused verifier and rollback.

## Cycle Output

```text
failure_layer:
fact:
inference:
suspicion:
hypothesis:
observation_tool:
patch_intent:
verification_tool:
observed_result:
rollback:
next_priority:
source_snapshot_hashes:
ai_candidate_origin:
validated_evidence:
mcp_verification_scope:
seed_and_case_count:
counterexample_hash:
```

## AI-Assisted Observation

When an explicit build/boot log and one to eight exact source files need the
observation adapter, read [AI-assisted observation](references/ai-assisted-observation.md).
It owns adapter invocation, input limits, provider fallback, candidate validation
and seeded falsifiers. Keep the two-slot limit and existing source gate above;
an AI candidate alone never proves the cause or authorizes edits.

## Success Memory

Store only a freshly verified, redacted pattern:

```text
symptom -> proof -> minimal_patch -> verifier -> rollback
```

Keep at most five entries. Prefer reuse only after a pattern is
successful twice. Evict the oldest or least reusable entry. Never store raw prompts,
queries, logs, credentials, headers, cookies, or private environment values.

## Example

For `outCount=0` after provider success, classify retrieval filtering.
Hypothesize post-filter starvation. Inspect canonical code and test, patch,
then verify. A new timeout closes the cycle because the failure class changes.

## Red Flags

- Two active hypotheses
- Editing before observation changes a decision
- Treating a broad suite as the narrowest verifier
- Continuing after the failure class changes
- Entering P2 because time remains
- Storing unverified memory
- Reporting Notebook evidence as Desktop final proof

Any red flag closes the cycle and returns to the first failing layer.
