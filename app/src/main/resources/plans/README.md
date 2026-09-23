# Plans

These YAML files are loaded via `com.nova.protocol.plan.PlanLoader` from `/plans/{id}.yaml`.

- `safe_autorun.v1` — conservative defaults, no overdrive, balanced web/vec/kg.
- `recency_first.v1` — emphasizes recency (web K ↑), enables mild burst.
- `kg_first.v1` — prioritizes KG for entity-heavy queries.
- `brave.v1` — aggressive recall with Extreme‑Z burst; overdrive enabled.
- `rulebreak.v1` — admin override plan (pair with RuleBreakInterceptor).

Tune `kAllocation` and `timeouts` per environment.
