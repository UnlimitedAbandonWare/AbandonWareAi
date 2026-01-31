# src111_merge15dw6 — Integration Notes (gap15 stubs wired)

This drop integrates **gap15-stubs_v1** into `src111_merge15dw4` with *non-destructive* adds:

## What changed
- Added packages under `src/main/java/com/abandonwareai/**` for 15 gap features (MCP, Planner-Nexus, Critic/Evolver, 3-Way Self-Ask, Overdrive/Extreme-Z, Z-System, Observability AOP/SimHash, Grandas WPM/Delta/Bode, MatrixTransformer/MoE/Isotonic, Final Sigmoid/Citation, CFVM-Raw, 9-Tile Alias, DBVM-X/MPC, Version-Purity/Reflection Guard, Dynamic Portfolio).
- Added `src/main/resources/application-gap15-stubs.yml` with **all toggle keys** (copy needed blocks into your `application.yml`).
- Added Plan-DSL samples: `src/main/resources/planner/plans/safe.v1.yaml`, `.../brave.v1.yaml`.
- Bundled original stubs zip under `/extras/gap15-stubs_v1.zip` for traceability.

## Wire-up checklist (minimal)
1) **Plan selection**
   - Set `planner.enabled=true`
   - Choose plan: `planner.plan-name=safe.v1.yaml` or `brave.v1.yaml`

2) **Preflight & Citations**
   - Enable `guard.autorun.enabled=true`, tune `min-citations`, `min-evidence`
   - Optionally enforce `guard.citation.min=3`

3) **Cross-encoder guard**
   - `rerank.cross-encoder.semaphore.max-permits=4`

4) **Time budget / Single-flight**
   - `zsystem.budget.enabled=true`, `zsystem.budget.millis=3500`
   - `cache.singleflight.enabled=true`

5) **Grandas-lite**
   - `fusion.wpm.enabled=true`, `fusion.delta-project.enabled=true` (with RRF)

6) **Final quality gate**
   - `guard.final-sigmoid.enabled=true` (k=12, x0=0.9 default)

> Note: No existing files were overwritten. You only need to **import beans** where desired and read the toggles from `application.yml`.

## Suggested insertion points
- Chain entry: call `PlannerNexus.applyPlan()` before `DynamicRetrievalHandlerChain`.
- Preflight: run `AutorunPreflightGate.allowAction(...)` before tool execution.
- Cross-encoder: guard calls with `CrossEncoderGate.acquire()/release()`.
- Cache: wrap remote cache-miss with `SingleFlightExecutor.run(key, ...)`.
- Fusion: compute WPM/Delta → mix with existing `WeightedRRF`.
