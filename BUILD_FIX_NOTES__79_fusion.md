# Build Fix Notes — src111_merge15 (79)

## Symptoms
- `Bm25LocalRetriever.java:26: error: method does not override or implement a method from a supertype`
- `FusionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`
- `FusionService.java:40: error: cannot find symbol: class SearchResult`

## Root causes (mapped to internal build-error patterns)
- **OverrideMismatch**: `@Override` present while the class does not implement/extend a supertype that declares `retrieve(String,int)`.
- **API-SignatureDrift**: `WeightedRRF#fuse(...)` evolved to `(List<List<ContextSlice>>, int k, Map<String,Double> weights, ScoreCalibrator calibrator, boolean dedupe)` and returns `Map<String,ContextSlice>`, but call site used an older 2-arg signature returning `List<ContextSlice>`.
- **TypeMismatch/LeftoverRef**: Fusion result was iterated as `SearchResult` while the collection actually holds `ContextSlice`.

## Fix
1) **Bm25LocalRetriever**
   - Removed `@Override` on `retrieve(String,int)` (no supertype to override).

2) **FusionService**
   - Injected `com.abandonware.ai.service.rag.fusion.ScoreCalibrator` (Spring will provide `MinMaxCalibrator`).
   - Adapted call to `WeightedRRF#fuse(...)` with the 5 required args:
     - `k`: `plan.rrf().k` (default 60)
     - `weights`: `plan.rrf().weight` (default empty map)
     - `calibrator`: injected `ScoreCalibrator`
     - `dedupeByCanonicalKey`: `true`
   - Converted returned `Map<String,ContextSlice>` to `List<ContextSlice>`.
   - Replaced `for (SearchResult sr : fused)` → `for (ContextSlice cs : fused)` and re-ranked.

## Files changed
- `src/main/java/com/abandonware/ai/agent/service/rag/bm25/Bm25LocalRetriever.java`
- `src/main/java/com/abandonware/ai/agent/service/rag/fusion/FusionService.java`

See `PATCHES/PATCH__Bm25LocalRetriever.diff` and `PATCHES/PATCH__FusionService.diff` in this zip.