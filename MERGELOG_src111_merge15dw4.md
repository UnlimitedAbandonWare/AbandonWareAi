# src111_merge15dw4 — Merge Log

## What changed
- Patched `src/main/java/service/rag/planner/SelfAskPlanner.java`
  - Constructor now uses `@Qualifier("webRetriever")`, `@Qualifier("vectorRetriever")`, `@Qualifier("rrfFuser")` for **disambiguated Optional DI**.
  - Original is backed up as `SelfAskPlanner___ORIG_dw3.java`.
- Added adapter wiring:
  - `src/main/java/config/RagLightAdapters.java` — **reflection‑safe adapters** for:
    - web retriever → standard context map (`{id,title,snippet,source,score,rank}`)
    - vector/hybrid retriever (best‑effort; gracefully returns empty if not available)
    - RRF fuser (bean or static helper; else simple score sort)
  - `src/main/java/com/example/lms/config/RagLightAdapterConfig.java` — component scan bridge.
- Carried over `PATCH_NOTES_adapters.md`.

## Rationale (resume‑aligned)
- *3‑Way Long‑tail Branching* (Self‑Ask) + *Weighted‑RRF* fusion with **fail‑soft** fallbacks.
- **Domain heterogeneity tolerance**: multiple package variants (`com.abandonware.*`, `com.example.*`, `service.*`) coexist → adapters use **ApplicationContext + reflection** to avoid hard deps and compile breakage.
- **Quality & safety**: keeps SelfAskPlanner optional retrievers, fuses when available, otherwise **gracefully degrades** (no hard failures in prod).

## Build & run
- Gradle multi‑module; typical commands:
  ```bash
  ./gradlew clean build -x test
  ```
- No extra properties required, but you can toggle vector/web as needed:
  - `retrieval.vector.enabled=true|false`
  - `naver.search.web-top-k=10`

## Verification checklist
- [ ] Application starts without `NoUniqueBeanDefinitionException` for `SelfAskPlanner.Retriever`.
- [ ] `/bootstrap` (if present) returns 200.
- [ ] Self‑Ask flow returns fused top‑K when at least one retriever is resolvable.

## Notes
- The adapters are conservative and do not change domain logic; they only **wire** and **normalize**.
- You can later replace reflection with direct DI once package names stabilise.
