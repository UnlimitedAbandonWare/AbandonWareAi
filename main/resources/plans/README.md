# Plans

These YAML files are active Spring resources. For the main chat/RAG runtime,
generic plan knobs are loaded and applied through
`com.example.lms.plan.PlanHintApplier` and `PlanHints`.

`com.example.lms.service.rag.plan.PlanDslLoader` is currently
projection-agent specific. Older `PlanLoader` variants under `planner`,
`strategy`, `service.plan`, and `com.nova.protocol.plan` are compatibility or
reference owners unless a current caller proves otherwise.

- `safe_autorun.v1` - conservative defaults, no overdrive, balanced web/vec/kg.
- `recency_first.v1` - emphasizes recency and enables mild burst.
- `kg_first.v1` - prioritizes KG for entity-heavy queries.
- `brave.v1` - aggressive recall with Extreme-Z burst; overdrive enabled.
- `rulebreak.v1` - admin override plan; pair with RuleBreakInterceptor.

Tune `kAllocation` and `timeouts` per environment.
