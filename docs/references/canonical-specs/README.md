---
title: "Canonical & Evergreen Specifications Reference Index"
category: "canonical-spec"
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 365
expiresAt: "2027-10-05"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "official_public_documentation + live_repo_contract"
reviewAfterDays: 365
cadence: "evergreen"
stability: "high"
decayRate: "low"
stabilityReason: "W3C/WHATWG 표준 및 학술 알고리즘 불변 규격 SSOT — vendor pricing/model churn과 무관"
runtimeEnforcement: "unchanged"
---

# Canonical & Evergreen Specifications Reference Index

## What "canonical spec" means here

This directory is the SSOT for **externally defined, long-lived invariants** the
project depends on: published standards (W3C/WHATWG), peer-reviewed algorithms
(Cormack et al. 2009), and database-engine semantics (H2 2.x). These change on
multi-year timescales, unlike vendor pricing/models which carry a 90-day TTL
(`docs/provider-limits/`).

- `cadence: evergreen` + `ttlDays: 365` — reviewed yearly, not quarterly.
- `expiresAt` 경과 시 문서는 **STALE_DISCARD** — `status: ACTIVE`가 남아 있어도
  근거로 인용하지 않는다 (동일 계약: `docs/provider-limits/README.md` §TTL).
- Each doc lists `canonicalSources` — the normative external references. If a
  live source file contradicts a doc, the **live tree wins** and the doc gets a
  dated correction (`UAW/handoff directives are a reference map, not source
  authority` — same rule applies here in reverse).

## Index (5 specs)

| Doc | Invariant | Live-tree anchor |
|---|---|---|
| `W3C_SERVER_SENT_EVENTS_SPEC.md` | `text/event-stream` framing: `\n\n` event boundary, `data:`/`event:`/`id:`/`retry:` fields, `:` comment keep-alive, buffering-control headers | `main/java/com/abandonware/ai/telemetry/SseEventPublisher.java`, `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java`, `main/resources/static/js/chat.js` (EventSource) |
| `RECIPROCAL_RANK_FUSION_RRF_SPEC.md` | $RRFscore(d) = \sum_r \frac{1}{k + r(d)}$, $k=60$; weighted variant $\sum_r w_r/(k+r(d))$; 1-based rank | `main/java/com/example/lms/service/rag/fusion/WeightedReciprocalRankFuser.java`, `.../fusion/RrfFusion.java`, `main/resources/application.yml` `rrf.*` |
| `H2_DATABASE_LOCKING_SPEC.md` | H2 2.x file-lock modes, `AUTO_SERVER` mixed mode, `.lock.db` semantics | `scripts/db_agent.py` (exit 3 = locked by live JVM), `docs/agents-rules/DEMO1-DB-AGENT-SSOT.md` |
| `LLM_SAMPLING_PARAMETERS_SPEC.md` | Softmax temperature, nucleus (top-p) mass, seed determinism | `main/java/com/example/lms/api/ChatRequestSettingsMerger.java` |
| `SAFE_EXCERPT_DTO_SPEC.md` | Sanitized 280-char excerpt DTO, path/secret redaction, frontend projection | `main/resources/static/js/chat-evidence-graph.js` |

## Agent reference rules (Codex / Devin / Grok)

1. **Read before guessing.** RRF formula, SSE framing, H2 lock behavior, and
   sampling-parameter semantics are pinned here — do not re-derive them from
   memory or burn web-search budget on them.
2. **Cite the doc, link the anchor.** Reports reference this SSOT *and* the
   live-tree anchor `file:line` when a claim is about demo-1 behavior.
3. **Mutable values still live in SSOT configs.** `k`, per-lane weights, model
   defaults, ports, and timings are *variables* re-read from
   `configs/api-routing.yaml` / `application.yml` / `docs/API_ROUTING_SPEC.md`
   (`DEMO1-MUTABLE-SPEC-POLICY`). Only the external invariant (formula shape,
   framing rule, lock semantics) is constant.
4. **Do not edit casually.** A change here means the external standard itself
   changed or a doc was wrong — record `reviewedAt` and evidence. Product code
   changes never go through this directory.
5. `unknown`/`NOT_RUN` stay honest: a spec doc is reference material, not
   runtime verification evidence.
