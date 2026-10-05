---
title: "LLM Sampling Parameters Canonical Specification"
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
stabilityReason: "softmax/nucleus 샘플링 수학은 모델·벤더 세대와 무관한 불변 정의"
runtimeEnforcement: "unchanged"
canonicalSources:
  - "https://platform.openai.com/docs/api-reference/chat/create"
  - "https://arxiv.org/abs/1904.09751 (Holtzman et al., nucleus sampling)"
  - "https://github.com/ollama/ollama/blob/main/docs/modelfile.md#parameter"
---

# LLM Sampling Parameters — canonical spec

## 1. Temperature (softmax scaling)

$$P(x_i) = \frac{\exp(z_i / T)}{\sum_j \exp(z_j / T)}$$

- $z_i$ = logit of token $x_i$; $T$ = temperature, domain **[0.0, 2.0]**
  (OpenAI chat-completions range; other providers may clamp differently).
- $T \to 0.0$ collapses to **greedy/argmax** — the RAG/extraction standard:
  deterministic-leaning, highest-probability token each step.
- $T = 1.0$ leaves the model's learned distribution unchanged; $T > 1.0$
  flattens it (more diversity, more drift risk).
- Adjusting $T$ reshapes the *whole* distribution; it never truncates the tail
  — combine with top-p when low-probability junk must be cut.

## 2. Top-P (nucleus sampling)

- Candidate set = smallest token group whose **cumulative probability mass
  ≥ p**; sampling is renormalized inside that nucleus. Domain **(0.0, 1.0]**.
- `p = 1.0` disables the cut; small `p` (e.g. 0.1) is near-greedy.
- Dynamic support: unlike fixed top-k, the nucleus grows/shrinks with
  distribution sharpness — the reason it is the default tail-trimmer.
- Common guidance: tune temperature **or** top-p, not both aggressively.

## 3. Seed (determinism)

- `seed` requests reproducible sampling for identical inputs — e.g. OpenAI
  `seed` + `system_fingerprint`, Ollama `seed` Modelfile parameter.
- **Best-effort, not a bitwise contract**: determinism holds for the same
  model build/backend; hardware, batching, kernels, and version changes can
  legitimately break it. Never assert seed → identical bytes across upgrades
  without per-run evidence.

## 4. Related penalty/cap parameters (demo-1 exposes them)

- `frequency_penalty`, `presence_penalty` ∈ **[-2.0, 2.0]** (OpenAI):
  frequency scales with token count seen; presence is a flat on/off nudge.
- `maxTokens` bounds generated length only — it is a cap, not a quality knob.

## 5. demo-1 wiring (verified 2026-10-05)

- Precedence per request: `FACTORY → ADMIN_DB → USER → REQUEST/SESSION`, merged
  in `main/java/com/example/lms/api/ChatRequestSettingsMerger.java` (lines
  19-72); `ChatPreferenceService.validate` bounds values before they reach the
  provider call.
- A user-supplied temperature/topP is **preserved** to the final endpoint —
  the merger tracks the source tier (`FACTORY` vs `USER`/`REQUEST`) so
  non-factory values are not silently overwritten
  (`ChatRequestSettingsMerger.java:69-71`).
- Mutable defaults (per model/lane) live in settings/configs, not in this doc —
  re-read the live values (`DEMO1-MUTABLE-SPEC-POLICY`); only the math above
  is invariant.
