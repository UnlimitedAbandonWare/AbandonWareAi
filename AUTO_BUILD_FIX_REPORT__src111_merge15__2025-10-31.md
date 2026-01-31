# Auto Build Fix Report — src111_merge15 (2025-10-31)
## Inputs
- Scanned error DB: BUILD_ERROR_PATTERNS.json, BUILD_ERROR__latest.txt
## Fixes applied
- Regex escapes normalized in `app/src/main/java/com/example/lms/guard/PIISanitizer.java` (`\\s`, `\\d`).
- Fixed illegal escape in `PlannerNexus.split("\\s+")`.
- Rewrote `DppDiversityReranker` with simple diversity guard.
- Added `AnswerPostProcessor` to combine QualityGate + PII + normalizer.
- Updated `application.yml` keys: guard.finalSigmoid / guard.pii.

## Pattern rationale
- Detected patterns: ['ILLEGAL_ESCAPE_CHAR', 'BAD_HYPHEN_ESCAPE', 'SPLIT_RAW_WS', 'CNF_BM25_INDEX']
- Examples: ['com/abandonware/ai/agent/integrations/index/Bm25LocalIndex.java', 'com/abandonware/ai/agent/integrations/TextUtils.java', 'com/example/lms/matrix/MatrixTransformer.java', 'com/example/lms/service/rag/patch/SelfAskWebSearchRetriever.java']

## Safety checks
- Did not touch non-source artifacts or build scripts except `application.yml`.
- Avoided modifying backup/experimental sources not on `java_clean` path.