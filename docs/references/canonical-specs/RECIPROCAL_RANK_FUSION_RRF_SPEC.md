---
title: "Reciprocal Rank Fusion Canonical Specification"
category: "canonical-spec"
capturedAt: "2026-10-05"
timezone: "Asia/Seoul"
reviewedAt: "2026-10-05"
ttlDays: 365
expiresAt: "2027-10-05"
status: "ACTIVE"
expiryAction: "archive"
sourceType: "peer_reviewed_publication + live_repo_contract"
reviewAfterDays: 365
cadence: "evergreen"
stability: "high"
decayRate: "low"
stabilityReason: "SIGIR '09 학술 수식 — 논문 상수와 공식은 변하지 않는다"
runtimeEnforcement: "unchanged"
canonicalSources:
  - "https://doi.org/10.1145/1571941.1572114"
  - "Cormack, Clarke, Buettcher — 'Reciprocal rank fusion outperforms condorcet and individual rank learning methods', SIGIR 2009"
---

# Reciprocal Rank Fusion (RRF) — canonical spec (Cormack et al. 2009)

## 1. Standard formula (invariant)

$$RRFscore(d \in D) = \sum_{r \in R} \frac{1}{k + r(d)}$$

- $r(d)$ = the **1-based** rank of document $d$ in ranked list $r$.
- $R$ = the set of rankers/lists being fused.
- $k$ = rank-smoothing constant. The paper fixes **$k = 60$** — it dampens the
  outsize influence of top ranks while preserving order. Changing $k$ is a
  tunable, not a spec change.

$$Weighted\_RRFscore(d) = \sum_{r \in R} w_r \times \frac{1}{k + r(d)}$$

- Weighted variant multiplies each ranker's contribution by $w_r$; weights are
  a project tuning surface, not part of the paper.

## 2. Properties the spec guarantees

- Score is **rank-only** — incomparable score scales across BM25, dense-vector
  similarity, and KG hits fuse safely.
- Documents present in multiple lists **accumulate** contributions; a document
  absent from a ranker contributes **0** for that ranker (not a penalty term).
- Fused output sorts by descending summed score; ties keep stable
  first-appearance order (implementation choice, not paper mandate).

## 3. demo-1 implementation contract (verified 2026-10-05)

| Rule | Live anchor |
|---|---|
| $k$ default `60`, min-clamped to ≥1, override `${retrieval.fusion.rrf.k}` | `main/java/com/example/lms/service/rag/fusion/WeightedReciprocalRankFuser.java:82` |
| Score `w_i / (k + rank_i)`, rank_i is **1-based** list index; null sublists ignored; missing weight → `1.0` | same file :99-135 |
| Weight keys `w_ce`, `w_bm25`, `w_sem` via `HyperparameterService` / `retrieval.fusion.rrf.weights` | same file :51, :84 |
| YAML defaults: `rrf.constant: 60`, `weight.web: 1.0`, `vector: 0.8`, `bm25: 0.9`, `kg: 0.7`; `web-rich-threshold: 3` down-weights vector/bm25 to `0.15` and web to `0.7` when the web lane is rich | `main/resources/application.yml` `rrf.*` |
| Lightweight map-based util uses `K = 60.0`, key = canonical URL (`utm_*`/`fbclid` stripped) or `id` | `main/java/com/example/lms/service/rag/fusion/RrfFusion.java:30-65` |
| Dedup key: for `source=general_graph_evidence` metadata → `graph_source:` + SHA-256 of `len(sourceId):sourceId:len(sourceRevision):sourceRevision`; else first non-blank of `url/uri/source_url/link/doc_id/...`; else `sha256:` of normalized lowercase text | `WeightedReciprocalRankFuser.java:346-386` |

## 4. What is invariant vs tunable

- **Invariant (this doc):** the $1/(k+r)$ form, 1-based ranking, accumulation
  across lists, missing-ranker zero contribution, and the paper constant
  $k=60$ as the reference default.
- **Tunable (SSOT configs, re-read per `DEMO1-MUTABLE-SPEC-POLICY`):** actual
  $k$ override, per-lane weights (`application.yml`, `HyperparameterService`),
  `topK` cut, dedup-key policy details.
- A doc/comment claiming "RRF constant must stay 60" is citing the paper
  default — project YAML may legitimately carry a different tuned value; the
  *formula* is what must not change.
