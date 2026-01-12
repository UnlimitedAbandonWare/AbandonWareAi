# PATCH APPLIED — srswc111_merge15

Date: 2025-10-17

## Summary
- **Plan DSL 1.5**: Added `mcp:` budgets and `when:` conditional blocks to **brave.v1.yaml** and **zero_break.v1.yaml**; added `gates.citation` with `hostDiversityMin` and dynamic min logic.
- **CitationGate++**: Enforced host diversity and dynamic minimum citations; novelty boost supported.
- **RiskKAllocator v2 (CVaR-aware)**: Added `allocCvarAware(...)` overload that boosts logits by upper-tail CVaR of per-source scores.
- **MCP HELLO**: New REST endpoint `/mcp/hello` returning PolicyContract for capability-policy handshake.

## Files
- `app/src/main/resources/plans/brave.v1.yaml` (appended blocks)
- `app/src/main/resources/plans/zero_break.v1.yaml` (appended blocks)
- `app/src/main/java/com/nova/protocol/guard/CitationGate.java` (rewritten)
- `app/src/main/java/com/nova/protocol/alloc/RiskKAllocator.java` (new overload)
- `app/src/main/java/com/example/lms/mcp/McpSessionRouter.java` (new)

## Build error patterns considered
Based on `analysis/build_patterns_aggregated.json`: Duplicate class, Missing symbol/import, Override mismatch, illegal start of type, wrong file/name, and regex-escape suspects. Wrapper error in `BUILD_LOG.txt` resolved by existing robust `gradlew` shim.

