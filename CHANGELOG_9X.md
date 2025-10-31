
# 9X Upgrade (src111_merge15dw64)
- Added Planner DSL (PlanLoader/PlannerNexus) with YAML plans: safe_autorun, recency_first, kg_first, brave, zero_break
- Implemented SelfAsk 3-Way splitter (BQ/ER/RC)
- Added MP-Law inspired spectrum normalizer (de-spiking)
- Added ScoreCalibrator (monotonic quantile mapping)
- Added GrandasFusionModule (weighted power mean + RRF + delta projection)
- Z-System: budget guard, semaphore guard, single-flight, final sigmoid gate
- Gates: CitationGate, AutorunPreflightGate
- Modes: Extreme-Z expander, Anger Overdrive narrower, RuleBreak context (scoped)
- Resilience: FlowJoiner, FallbackRetrieveTool, OutboxSendTool
- Telemetry: MatrixTelemetryExtractor, VirtualPointService, CFVM-Raw
- Alias corrector: 9-tile skeleton (animals/games)
- application.yml updated with feature toggles under 'nineX'
- resources/plans/*.yaml added
