# Patch Notes — src111_mxerge15
Date: 2025-10-15T05:08:10.467543Z

## What changed
1) Implemented **NineTileAliasCorrector** (`com.example.lms.config.alias.NineTileAliasCorrector`), Spring component with safe overlay semantics.
2) Wired alias correction into **DynamicRetrievalHandlerChain / SelfAskPlanner** (best-effort injection).
3) Gradle hardening:
   - Added **Lombok** (compileOnly + annotationProcessor) and **Java toolchain 17**.
   - Enabled `failOnVersionConflict()` and compiler `-Xlint`.
   - Ensured Spring starters: *web*, *validation*, *data-jpa* (if missing).
   - Added `sourceSets` excludes for `legacy/examples/sample` (duplicate-noise reduction).
4) Generated machine-readable report: `PATCH_REPORT__nine_tile_alias_corrector.json`.
5) No destructive deletions; all changes are additive or guarded.

## Build error patterns (from repo artifacts)
- Frequent: `cannot find symbol` (95 occurrences in logs)
- `package lombok does not exist` (fixed by adding Lombok)
- `package ... does not exist` (generic; mitigated by adding Spring starters)
- Duplicates: 184 duplicate FQCNs detected → potential runtime bean ambiguity

## Next steps
- Consider consolidating duplicate classes under a single namespace.
- If ambiguity occurs at runtime, enable `spring.main.allow-bean-definition-overriding=true` temporarily.
