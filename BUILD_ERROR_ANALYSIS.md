
# Build Error Analysis — 2025-10-18

Root cause: Bean injection typed as `java.lang.Object` in `com.example.lms.scheduler.IndexingScheduler`:
- `ocr` field
- `embeddingStoreManager` field

Spring tries to autowire by type and `Object` matches *every* bean, yielding `NoUniqueBeanDefinitionException` with a long candidate list.

Fixes applied:
1. Change field types to concrete interfaces/services:
   - `OcrService` for `ocr`
   - `EmbeddingStoreManager` for `embeddingStoreManager`
   (both optional, `required=false`)

2. Register default OCR bean:
   - Annotated `BasicTesseractOcrService` with `@Service` so it participates in component scan (safe because it uses reflection and gracefully no‑ops when Tess4J is absent).

Notes:
- The lightweight `scheduler.IndexingScheduler` (non‑Spring bean) remains unchanged and is outside the `com.example.lms` base package.
- OCR run method still uses reflective fallbacks and is wrapped in try/catch; it will no‑op unless an OCR implementation exposes the expected methods.
