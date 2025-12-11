# PATCH — src111_mergawe15 (Hypernova Enhancer v2 overlay)

## Added
- `com.nova.protocol.autoconfig.NovaNextAutoConfiguration` (Boot 3 autoconfig + beans for TWPM/CVaR/NovaNextFusionService)
- `resources/application-hypernova.yaml` (kebab-case binding, sane defaults; profile `hypernova`)
- `resources/plans/hypernova.v2.yaml` (env-parameterised plan with DPP / gates / fusion)
- `com.nova.protocol.rag.branch.LongTailBranchingV2` (3-way branching helper)

## Fixed (build blockers)
- Rewrote `com.example.lms.strategy.RetrievalOrderService` to remove placeholder ellipses and invalid imports,
  and to include a **CVaR-aware K allocation** method (fail-soft defaults).
- Kept `RankingPipeline` minimal but compile-safe; DPP can be enabled via Plan.

## Wiring
- Appends `com.nova.protocol.autoconfig.NovaNextAutoConfiguration` to
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
  and to `META-INF/spring.factories` (Boot 2 fallback).
