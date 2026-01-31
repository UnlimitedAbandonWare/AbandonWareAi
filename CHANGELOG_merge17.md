# merge17 Patch Notes (src111_merge17)

Scope: Minimal-intrusion ("plug-in style") orchestration & safety patchset layered on top of the existing dynamic retrieval stack.

Highlights
- Plan-DSL (Planner Nexus): YAML-driven retrieval order & K-values, recency half-life and gates.
- Self-Ask 3-Way (BQ/ER/RC): Sub-query composition → hybrid retrieval → RRF → CE.
- Dynamic-K policy & simple isotonic score calibrator.
- Recency weighting in fusion (half-life model).
- Reranker Concurrency Guard + Time-Budget (fallback to fast filter).
- Single-Flight cache scaffold in front of Upstash.
- RuleBreak filter (header token) to temporarily bypass WL & expand web K.
- PII Sanitizer post-processing.
- Minimal MCP integration (mcp.json + client + ping tool).
- Boot wiring via OrchestrationConfig.java.
- Feature flags are **OFF by default**; enable per environment as needed.

Files Added (major)
- planner/*, service/rag/selfask/*, service/rag/policy/*, service/rag/calibration/*,
  service/guard/*, infra/cache/SingleFlightCache.java, web/RuleBreakInterceptor.java,
  guard/PIISanitizer.java, integrations/mcp/*, service/tools/*, config/OrchestrationConfig.java
- resources: plans/*.yaml, app/resources/mcp/mcp.json
- resources/application.yml: merge17 feature flag block appended

Build Notes
- Ensure dependencies include: `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` and Spring Boot AOP if you add aspects later.
- No existing classes were modified in-place; wiring uses new beans and filters to keep risk low.

Enable (example)
- Set in application.yml or env:
  - `features.planner.enabled=true`
  - `features.selfask.enabled=true`
  - `features.fusion.recencyWeight.enabled=true`
  - `features.reranker.guard.enabled=true`
  - `features.pii.sanitizer.enabled=true`
  - `features.mcp.enabled=true`

Operational Checks
- Probe `/api/probe/search` with intent=news and compare recency-weighted ranking.
- Soak `/internal/soak/run` to compare nDCG / evidence rate before/after toggles.
- Verify SSE stream contains `plan`, `rb`, and `budget.remainMs` fields.

Notes
- Some integrations (e.g., hook points into existing handlers/reranker) may still require small code deltas or AOP aspects if strict bean names differ in your repo.
