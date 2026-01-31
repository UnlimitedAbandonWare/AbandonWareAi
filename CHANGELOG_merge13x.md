# CHANGELOG — merge13x minimal patch (generated)

Date: 2025-10-11T01:46:05.629219Z

## Added
- `infra/cache/SingleFlightExecutor.java` — single-flight collapsing executor.
- `service/rag/fusion/MultiQueryMergeAdapter.java` — multi-branch dedup/merge utility.
- (If present) Patched `.../UpstashBackedWebCache.java` with single-flight gate hints.
- `application.yml` keys under `zsys.*` and `cache.singleflight.*`.

## Notes
- All features are **OFF by default** (timeBudget=0, singleflight.enabled=false).
- Changes are additive; existing behavior should remain unchanged when toggles are off.
