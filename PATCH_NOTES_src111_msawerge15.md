# PATCH_NOTES_src111_msawerge15

Scope: WBI‑01/03/04/05/06 partial wiring + OCR compilation errors fixed.

## What changed

### OCR (build fix, WBI‑03)
- Replaced broken stubs and conflicting classes with a **unified OCR contract** under
  `com.abandonware.ai.service.ocr.*` and provided a **time‑budget facade**:
  - `OcrService`, `BasicTesseractOcrService`, `OcrNullService`, `OcrChunk`, `TimeBudgetOcrFacade`.
- Implemented an **Example‑compat wrapper** at `com.example.lms.service.ocr.BasicTesseractOcrService`
  that delegates to Abandonware OCR when available and falls back gracefully.
- Removed stray `src/main/java/service/ocr/BasicTesseractOcrService.java` (invalid package).
- This resolves the previous duplicate/mismatch between interface/class and prevents
  `` class/interface errors from appearing again.

### ONNX Cross‑Encoder (WBI‑01)
- Added real **ONNX Runtime** loader and scoring (`OnnxRuntimeService`), **DJL tokenizer adapter**,
  and a practical **Cross‑Encoder reranker** in `src/main/java/com/abandonware/ai/service/onnx/*`.
- Injected required Gradle deps into `app/build.gradle.kts` and added `onnx` section to `application.yml`.

### Federated Vector Store (WBI‑04)
- Implemented minimal `LocalEmbeddingStore` and **RRF‑fusing** `FederatedEmbeddingStore`.

### Governance + Observability (WBI‑05/06)
- Added `TraceHeaders` constants and `SseKeys` to standardize correlation and SSE stream keys.

## Build‑error patterns addressed
- **DUPLICATE_CLASS / CLASS_EXPECTED / INTERFACE_EXPECTED** in OCR namespace:
  unified contracts + deleted invalid package file.
- **Illegal start of expression** due to placeholder ellipses (`...`): scrubbed for the files we touched.

## How to verify (quick)
- `./gradlew :app:dependencies` → check `ai.onnxruntime` & DJL artifacts are present.
- Run `POST /api/probe/search` with `onnx.enabled=true` to confirm reranker path is hit.
- Ensure `X-Request-Id`/`X-Session-Id` propagate end‑to‑end; SSE payload keys include `stage,t,reqId`.

