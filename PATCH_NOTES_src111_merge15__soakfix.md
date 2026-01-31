# Patch: src111_merge15 — Soak pipeline autowiring fix + safe defaults

## What I changed
1) **Define a real `SearchOrchestrator` bean**  
   *Added `DefaultSearchOrchestrator` (`@Service`, `@Primary`) that returns an empty list.*  
   This satisfies `SoakConfig.soakTestService(..., SearchOrchestrator)` so boot no longer fails if your app doesn't register a concrete orchestrator.

2) **Stabilise Soak provider wiring**  
   *Rewrote `CombinedSoakQueryProvider` with `@Component("combinedSoakQueryProvider")` and `@Primary`.*  
   This matches the qualifier used in `SoakConfig` and keeps backwards compatibility.

3) **Completed minimal Soak support classes (previously had placeholders)**  
   Replaced placeholder bodies with compilable implementations:
   - `DefaultSoakTestService` – aggregates simple nDCG@10 and latency stats.
   - `SoakReport`, `SoakRunResult`, `SoakMetricsAgg` – DTO + helpers used by the API.
   - `TopicClassifier` – tiny heuristic classifier used by the combined provider.

## Why
Your boot failure was:

```
Unsatisfied dependency: No qualifying bean of type 'com.example.lms.service.soak.SearchOrchestrator'
required by parameter 1 of SoakConfig.soakTestService(...)
```

Adding a conservative default bean and aligning the provider qualifier unblocks the context refresh.  
Design mirrors the "fail‑soft, probe‑friendly" approach used by other defaults (e.g., `NoopCrossEncoderReranker`).

## Related internal docs I followed
- Soak API + files: `SoakTestService`, `SoakApiController`, `SoakQueryProvider` (Jammini memory). 
- Dynamic retrieval/ordering is optional; default orchestrator intentionally no‑ops so the /internal/soak route stays alive.

## Files touched
- src/main/java/com/example/lms/service/soak/DefaultSearchOrchestrator.java (new)
- src/main/java/com/example/lms/service/soak/CombinedSoakQueryProvider.java
- src/main/java/com/example/lms/service/soak/DefaultSoakTestService.java
- src/main/java/com/example/lms/service/soak/SoakReport.java
- src/main/java/com/example/lms/service/soak/SoakRunResult.java
- src/main/java/com/example/lms/service/soak/SoakMetricsAgg.java
- src/main/java/com/example/lms/vector/TopicClassifier.java

## Verification
- Constructor wiring now resolves: `SoakConfig.soakTestService(@Qualifier("combinedSoakQueryProvider") ..., SearchOrchestrator)`  
- `/internal/soak/run` responds even without a full search stack; metrics will be zeros but server boots.

