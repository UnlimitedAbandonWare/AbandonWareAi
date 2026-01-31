# src111_merge15dw3 — Adapter Patch (AnalyzeWebSearchRetriever / FederatedEmbeddingStore / WeightedRRF)

## What’s included
- **config/RagLightAdapters.java**
  - Wires concrete implementations to the lightweight Self‑Ask planner via small adapters.
  - Beans:
    - `"webRetriever"` → `AnalyzeWebSearchRetriever` → `SelfAskPlanner.Retriever`
    - `"vectorRetriever"` → `FederatedEmbeddingStore` → `SelfAskPlanner.Retriever`
    - `"rrfFuser"` → `WeightedRRF` or `RrfFusion` → `SelfAskPlanner.Fuser` (WeightedRRF preferred, legacy fallback)
  - All retrieval delegates are wrapped with `FallbackRetrieveTool.retrieveOrEmpty(...)` for fail‑soft behavior.

- **com/example/lms/config/RagLightAdapterConfig.java**
  - Adds a narrow component scan bridge for `service.*`, `vector.*`, and `rag.fusion.*` packages in case the app only scans `com.example.*`.

- **service/rag/planner/SelfAskPlanner.java (patch)**
  - Adds `@Qualifier` annotations to constructor args so that Spring can unambiguously inject the proper beans by name:
    - `@Qualifier("webRetriever") Optional<Retriever>`
    - `@Qualifier("vectorRetriever") Optional<Retriever>`
    - `@Qualifier("rrfFuser") Optional<Fuser>`

## How to use
1) Ensure your concrete implementations exist (per Jammini memory):
   - `service/rag/AnalyzeWebSearchRetriever.java`
   - `vector/FederatedEmbeddingStore.java`
   - `rag/fusion/WeightedRRF.java` (or `service/rag/fusion/RrfFusion.java`)

2) Build & run:
   ```bash
   ./gradlew build -x test
   java -jar build/libs/app.jar
   ```

3) Optional toggles in `application.yml` (already provided earlier):
   ```yaml
   features:
     selfask:
       enabled: true
   ```

4) Probe:
   ```bash
   curl -s -X POST http://localhost:8080/api/probe/search \
     -H "Content-Type: application/json" \
     -d '{"query":"최근 KPI 변경 내역은?","useWeb":true,"useRag":true}'
   ```

## Notes
- If both `WeightedRRF` and `RrfFusion` are on the classpath, the `WeightedRRF` adapter is marked `@Primary` and will be used.
- If your application already scans `service.*`, the bridge config is harmless (no-ops).
