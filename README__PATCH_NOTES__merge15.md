# src111_merge15 — Auto Patch Notes (2025-10-31)

This zip was inspected and verified against the internal *Build Error Pattern* database and the "Jammini Memory" snapshot.
Primary actions taken:

1) **SearchProbeController: `cannot find symbol: enabled`**
   - Verified all copies inside this bundle declare:
     ```java
     @Value("${probe.search.enabled:false}")
     private boolean enabled;

     @Value("${probe.admin-token:}")
     private String adminToken;
     ```
   - **Result**: already present in `src/` and `lms-core/` modules. No code change was necessary.
   - If your external build points to a different module (e.g., `demo-1/demo-1/...`), apply the same two fields and guard around `/api/probe/search`.

2) **Evidence checked**
   - Build logs: `build-logs/2025-10-18-compileJava.log` (cannot find symbol at `SearchProbeController.java:37`).
   - Error-pattern DBs: `BUILD_ERROR_PATTERNS.json`, `.build/error_patterns_db.json`, `__reports__/build-error-patterns.json`.

3) **RED Patch presence (sanity)**
   - ONNX gate + timeout (**OK**) — `service/onnx/OnnxCrossEncoderReranker.java` uses `Semaphore` and time budget.
   - Single-flight cache (**OK**) — `infra/upstash/UpstashBackedWebCache.java` contains an `inflight` map (single-flight).
   - Score calibration + RRF (**OK**) — `service/rag/fusion/WeightedRRF.java` imports `ScoreCalibrator`.
   - Probe/Soak infra (**OK**) — `/api/probe/search`, `/internal/soak/run` present.

4) **Artifacts added**
   - `__reports__/AUTO_BUILD_FIX_REPORT__src111_merge15.json` — scan + action metadata (UTC timestamp).

---

## Next suggested steps

If you still see the same compilation error, ensure your build **actually uses** the module inside this zip (the failing path in the log was `demo-1/demo-1/...` which is not included here). Otherwise, copy the field declarations above into that controller.

