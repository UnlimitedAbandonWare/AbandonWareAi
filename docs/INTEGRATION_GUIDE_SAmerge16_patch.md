# SAmerge16 Delta Patch — Integration Guide

## What this patch adds
- **Nova Protocol — Hypernova plan** (`plans/hypernova.v1.yaml`)
- **TWPM** (Tail-Weighted Power Mean) fuser (`com.nova.protocol.fusion.TailWeightedPowerMeanFuser`)
- **CVaR@α** aggregator (`com.nova.protocol.score.CvarAggregator`)
- **Low-rank ZCA whitening adapter** (`com.nova.protocol.whiten.LegacyLowRankWhiteningAdapter`)
- **DPP diversity reranker** (`com.nova.protocol.rerank.DppDiversityReranker`)
- **Rerank canonicalizer** (`com.nova.protocol.util.RerankCanonicalizer`)
- **Bode-like clamp** (`com.nova.protocol.util.BodeClamp`)
- **Score calibrator with safe fallback** (`com.nova.protocol.score.ScoreCalibrator`)
- Ops policy + config examples.

## Wire-up checklist
1) Ensure classpath scanning picks up `addons/formulas_pack/addons/formulas/java` or move classes under your main source tree.
2) Load plan via property: `nova.modes.hypernova.plan=classpath:plans/hypernova.v1.yaml` or import contents into your existing Plan DSL.
3) In the **fuse_and_score** stage of your pipeline, call TWPM → CVaR → Calibrator → BodeClamp in this order.
4) Within **condense_refine**, call `DppDiversityReranker` between your bi-encoder and cross-encoder passes.
5) Enforce safety: `CitationGate(min=3)` and `FinalSigmoidGate(k=10, x0=0.85)` as shown in the plan.
6) Drop `config/application-sa16-patch.yaml` into your Spring profile or merge keys into `application.yaml`.

## Backout
Remove `plans/hypernova.v1.yaml` and exclude the `addons/formulas_pack` package from builds.