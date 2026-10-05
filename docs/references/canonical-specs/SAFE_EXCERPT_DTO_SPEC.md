---
title: "Safe Excerpt DTO Canonical Specification"
category: "canonical-spec"
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 365
expiresAt: "2027-10-05"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "live_repo_contract"
reviewAfterDays: 365
cadence: "evergreen"
stability: "high"
decayRate: "low"
stabilityReason: "발췌 DTO의 안전 경계(로컬 경로·시크릿·내부 ID 차단, 280자 상한)는 모델·벤더 churn과 무관한 불변 표시 계약"
runtimeEnforcement: "unchanged"
canonicalSources:
  - "main/resources/static/js/chat-evidence-graph.js (SafeExcerptDto projection boundary)"
  - "data/agent-handoff/codex-autonomy/graph-provenance-resume-bf7b6fea/HANDOFF.md (U6 FULL EXCERPT HOLD)"
---

# Safe Excerpt DTO — canonical spec

## 0. Purpose

Graph answer-provenance work is held at U6 (`FULL EXCERPT HOLD`) because no
approved safe excerpt DTO exists: raw chunk text could leak internal local
paths, raw DB sourceIds, session/user query text, or API tokens into the
public evidence projection. This spec pins the **invariant contract** for an
excerpt object the backend may emit and `chat-evidence-graph.js` may render.
Until a compliant DTO arrives, the frontend keeps showing `제공되지 않음`.

## 1. DTO contract — `SafeExcerptDto`

| Field | Type | Invariant |
|---|---|---|
| `excerptId` | string | Deterministic id: first 12 lowercase hex chars of SHA-256 over the sanitized chunk content. Never a raw DB primary key. |
| `text` | string | Sanitized excerpt body, **max 280 chars**. Newlines and control chars are normalized to single spaces; HTML tags are stripped. |
| `charCount` | int | Actual character count of `text`; must equal `text.length` and be `<= 280`. |
| `truncated` | boolean | `true` when the source chunk exceeded 280 chars and was cut. |
| `redacted` | boolean | `true` when any masking rule in §2 fired on this excerpt. |
| `sanitized` | boolean | Always `true` on emission — guarantees `text` is XSS/HTML-safe for `textContent`-only rendering. A `false` or absent value makes the DTO non-compliant. |

Wire shape:

```json
{
  "excerptId": "9f2ab1c04d7e",
  "text": "...sanitized excerpt...",
  "charCount": 128,
  "truncated": false,
  "redacted": true,
  "sanitized": true
}
```

## 2. Masking / exclusion rules (all four mandatory)

- **Rule 1 — local paths**: drive-letter paths (`C:\`, `D:\`, ...) and absolute
  POSIX paths are replaced by the literal token `[local-path]`.
- **Rule 2 — secrets/tokens**: any value matching
  `token|secret|password|api[_-]?key|auth|session` (case-insensitive) is
  replaced by `[REDACTED]`. The whole key=value pair or bearer string is
  masked, not just the key name.
- **Rule 3 — internal DB ids**: raw DB primary keys (sourceId rows, numeric
  PKs) never appear. Externally visible identity is the answer-side marker
  (`[A1]`-style) or `excerptId` only.
- **Rule 4 — frontend projection**: `chat-evidence-graph.js` may render an
  excerpt only when it receives a `SafeExcerptDto` with `sanitized=true`.
  When no compliant DTO arrives it keeps rendering `제공되지 않음` —
  the current U6-safe default.

## 3. Length bound rationale

280 chars is fixed (mobile 320px viewport / Meta Ray-Ban lens single-card
readability). It is part of the immutable contract, not a tunable; changing
it is a spec revision, not a config edit.

## 4. demo-1 wiring

- Consumer: `main/resources/static/js/chat-evidence-graph.js` — `project()`
  currently emits `excerpt: '제공되지 않음'`; on receipt of a compliant DTO it
  projects `text` via `textContent` only, never `innerHTML`.
- Producer: server-side evidence DTO assembly applies all four rules before
  the object crosses the trust boundary; this spec does not move secrets to
  the client for filtering.
- Validator: `scripts/test_safe_excerpt_dto_spec.py` — checks this doc's
  required sections and exercises a reference sanitizer against violating
  samples (exit 0 = compliant).
