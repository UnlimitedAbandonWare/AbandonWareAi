# gap15-stubs (AbandonWareAI — Missing Features Skeleton)

This package contains *minimal* Java stubs (Spring-style) + a `application-stubs.yml` for **15 feature groups** identified as gaps.
Each group has clear package/class names and config keys so you can wire them gradually.

> Drop these into your project and copy the relevant sections from `application-stubs.yml` into your `application.yml` (or activate via profile).

## Feature → Package/Class → Config keys
1. MCP (Model Context Protocol)
   - `com.abandonwareai.mcp`: `McpTransport`, `McpToolRegistry`, `McpSessionRouter`
   - keys: `mcp.enabled`, `mcp.transport`, `mcp.tools.manifest`

2. Planner Nexus (Plan-DSL / AutorunPreflight / K-Allocation)
   - `com.abandonwareai.planner`: `PlanLoader`, `PlannerNexus`, `KAllocationPolicy`
   - `com.abandonwareai.guard`: `AutorunPreflightGate`
   - keys: `planner.enabled`, `planner.plan-name`, `planner.plans-path`, `guard.autorun.*`, `k-allocation.*`

3. Critic/Evolver loop (Plan-Act-Critic-Synthesize)
   - `com.abandonwareai.critic`: `CriticService`, `CircuitBreaker`
   - `com.abandonwareai.evolver`: `ArtPlateEvolver`
   - keys: `critic.enabled`, `critic.max-retries`, `critic.backoff-ms`, `evolver.enabled`, `evolver.ab-test-percent`

4. Self-Ask 3-Way + MP-Law + ZCA
   - `com.abandonwareai.selfask`: `SubQuestionPlanner`
   - `com.abandonwareai.fusion`: `MpLawNormalizer`, `MpAwareFuser`
   - keys: `selfask.branching`, `fusion.mp-law.enabled`, `fusion.mp-law.zca.enabled`

5. Special Modes (Overdrive / Extreme-Z / Brave, RuleBreak, ZeroBreak)
   - `com.abandonwareai.overdrive`: `OverdriveGuard`, `AngerOverdriveNarrower`
   - `com.abandonwareai.extreme`: `ExtremeZSystemHandler`
   - `com.abandonwareai.nova`: `RuleBreakInterceptor`, `BravePlanMarker`
   - `com.abandonwareai.guard`: `CitationGate`
   - keys: `overdrive.*`, `extremez.*`, `nova.rulebreak.*`, `planner.plan-name=brave.v1|zero_break.v1`

6. Z-System (Budget, Cancel, Semaphore, Single-Flight)
   - `com.abandonwareai.zsystem`: `TimeBudget`, `CancelSignal`, `BudgetContext`
   - `com.abandonwareai.rerank`: `CrossEncoderGate`
   - `com.abandonwareai.cache`: `SingleFlightExecutor`
   - keys: `zsystem.*`, `rerank.cross-encoder.semaphore.*`, `cache.singleflight.enabled`

7. Observability (AOP, SimHash64, 3D Tensor placeholders)
   - `com.abandonwareai.telemetry.aop`: `DecisionHotspotAspect`
   - `com.abandonwareai.telemetry.compaction`: `SimHash64Reducer`
   - keys: `telemetry.aop.enabled`, `telemetry.compaction.simhash.enabled`

8. Grandas Fusion (Weighted Power Mean, Delta-Projection, Bode Clamp)
   - `com.abandonwareai.fusion`: `WeightedPowerMean`, `DeltaProjector`, `BodeClamp`
   - keys: `fusion.wpm.enabled`, `fusion.delta-project.enabled`, `fusion.bode-clamp.enabled`

9. MatrixTransformer / MoE Gate / Isotonic Calibration
   - `com.abandonwareai.context`: `MatrixTransformer`
   - `com.abandonwareai.gate`: `MoEGate`
   - `com.abandonwareai.fusion`: `ScoreCalibrator` (isotonic stub)
   - keys: `context.matrix-transformer.enabled`, `gate.moe.enabled`, `fusion.isotonic.enabled`

10. Final Sigmoid Gate & CitationGate
   - `com.abandonwareai.guard`: `FinalSigmoidGate` (+`CitationGate` above)
   - keys: `guard.final-sigmoid.*`, `guard.citation.min`

11. CFVM-Raw (RawSlotExtractor / RawMatrixBuffer / RawTile)
   - `com.abandonwareai.resilience.cfvm`: `RawSlotExtractor`, `RawMatrixBuffer`, `RawTile`, `CfvmController`, `RetrievalOrderServiceAdapter`
   - keys: `resilience.cfvm.enabled`

12. 9-Tile Alias Resolver (context+experience)
   - `com.abandonwareai.nlp.alias`: `TileConceptResolver`, `TileDictionary`
   - keys: `nlp.alias.tile.enabled`, `nlp.alias.tile.path`

13. DBVM-X Gate & MPC (3D voxel correction)
   - `com.abandonwareai.vision.mpc`: `MirrorPerfectCube`
   - `com.abandonwareai.fusion.dbvmx`: `DbvmXGate`
   - keys: `vision.mpc.enabled`, `fusion.dbvmx.enabled`

14. Version Purity & Reflection/Proxy Guard
   - `com.abandonwareai.guard`: `VersionPurityChecker`, `ReflectionProxyGuard`
   - keys: `guard.version-purity.*`, `guard.reflection-proxy.enabled`

15. Dynamic Value-based Synthesis (Authority/Novelty/Augmentability)
   - `com.abandonwareai.context.scoring`: `AuthorityScorer`, `NoveltyScorer`, `AugmentabilityScorer`
   - `com.abandonwareai.context`: `PortfolioSynthesizer`
   - keys: `context.portfolio.*`

See `application-stubs.yml` for a single place containing **all** toggle keys with safe defaults.
