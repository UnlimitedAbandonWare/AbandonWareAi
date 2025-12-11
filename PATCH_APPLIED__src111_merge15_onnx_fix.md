# Patch Applied — src111_merge15

**Issue fixed**: `variable gate not initialized in the default constructor` and `variable timeout not initialized in the default constructor` in `OnnxCrossEncoderReranker.java`.

**What I changed**
- Added a constructor to initialize the final fields `gate` and `timeout`.
  - For Spring-managed bean (`com.abandonware.ai.service.onnx.OnnxCrossEncoderReranker`), use constructor injection with the existing configuration keys:
    - `rerank.onnx.semaphore.max-concurrent` (default `4`)
    - `rerank.onnx.semaphore.queue-timeout-ms` (default `120` ms)
- Mirrored a no-argument default constructor in non-Spring stubs under `com.example.*` and `service.*` packages with safe defaults so any non-Spring usage compiles.

**Rationale**
- Project docs list the ONNX cross‑encoder and related toggles (`onnx.enabled`, etc.) and the rerank semaphore keys; defaulting here preserves behavior while fixing the compilation contract for `final` fields.
- Keeps behavior consistent with `OnnxRerankSemaphore` and `AddonsProperties.Onnx` concurrency gating patterns.

**Files touched**
- `src/main/java/com/abandonware/ai/service/onnx/OnnxCrossEncoderReranker.java` (Spring bean; constructor-injected)
- `src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java` (plain stub; default constructor)
- `src/main/java/service/onnx/OnnxCrossEncoderReranker.java` (plain stub; default constructor)
- `app/src/main/java/com/example/lms/service/onnx/OnnxCrossEncoderReranker.java` (plain stub; default constructor)

**How to verify**
- `./gradlew :compileJava` should proceed past the previous two errors.
- (Optional) Toggle: `-Drerank.onnx.semaphore.max-concurrent=4 -Drerank.onnx.semaphore.queue-timeout-ms=120`
- Runtime sanity: call the `SearchProbe` or RAG orchestrator with `onnx.enabled=true` to confirm CE path is exercised.

