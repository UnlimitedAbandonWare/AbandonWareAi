
# Integration Guide — 9X Upgrade

## 1) Beans
Spring Boot auto-config registers all 9X components via `com.abandonware.ai.config.Bootstrap9XConfig`.

## 2) Planner Nexus usage (DynamicRetrievalHandlerChain)
Inject `PlannerNexus` and `PlanLoader`.
- On query receipt, select plan by intent/brave/zeroBreak.
- Iterate over `plan.chain` and invoke corresponding handlers in your chain.

Suggested map:
- `alias_correct` -> TileAliasCorrector
- `selfask_3way` -> SelfAskPlanner
- `expand_extreme_z` -> ExtremeZSystemHandler
- `retrieve_*` -> existing retrievers (web/vector/kg)
- `calibrate_scores` -> ScoreCalibrator
- `fuse_rrf` -> existing RRF; `fuse_grandas` -> GrandasFusionModule
- `rerank_bi`/`rerank_cross` -> existing Bi-Encoder / Onnx Cross-Encoder
- `anger_overdrive_narrow` -> AngerOverdriveNarrower
- `gate_citation` -> CitationGate
- `final_sigmoid` -> ZSystem.finalSigmoidGate

## 3) Z-System guards
- Wrap expensive cross-encoder calls with `zSystem.guardedRerank(...)` and budget with `withBudget(...)`.
- Wrap web-cache lookups with `zSystem.singleFlight(key, supplier)` to avoid stampede.

## 4) Config toggles
See `application.yml` (`nineX.*`).

## 5) Telemetry
- After each run, collect matrices via `MatrixTelemetryExtractor` and compress with `VirtualPointService`.
- (Optional) record failure patterns via `CfvmRaw`.

## 6) Modes
- Set header `X-Brave-Mode: on` or a feature flag to select `brave.v1.yaml`.
- Admin-only: Zero Break plan.

## 7) Safety
- Enforce `CitationGate` and `AutorunPreflightGate` before final answer.

