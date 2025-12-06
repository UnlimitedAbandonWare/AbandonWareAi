# Patch Notes — src111_merdge15

## What I fixed
- **Ambiguous DI at runtime** causing `determinePrimaryCandidate` → `NoUniqueBeanDefinitionException` during `:bootRun`.
- Root cause: duplicate beans of type **PlanApplier** (and friends) created from duplicated configuration classes in both `app/` and `src/` source trees.

## Changes
- Added `@ConditionalOnMissingBean` to beans in `NovaProtocolConfig` (both `app/` and `src/`):
  - `PlanApplier`, `PlanLoader`, `PIISanitizer`, `KAllocationPolicy`, `ModeAuditLogger`.
- Ensured import: `org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean`.

## Why this works
- With conditional creation, the **first** encountered bean wins; subsequent definitions are skipped, eliminating ambiguous DI for fields like `@Autowired PlanApplier planApplier` (e.g., in `BraveModeFilter`).

## Related prior patterns (from repo history)
- `MERGELOG_src111_merge15dw4.md` documents earlier fixes for *NoUniqueBeanDefinitionException* around planner/retrievers (constructor qualifiers). This patch generalizes the approach at the configuration layer.

## Safe to revert?
- Yes. Purely declarative; no functional changes to business logic.

## Next steps (optional)
- Consider consolidating duplicate source trees or using a single module for protocol/config.
- Alternatively, give one module's config a narrower `@ComponentScan` scope, or mark one bean `@Primary` when semantic preference exists.