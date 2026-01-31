# Build Error Pattern Report

This report aggregates known build error patterns declared in the codebase and documents the compile fixes applied in this patch.

## Known Patterns (from cfvm BuildLogSlotExtractor)
- **MISSING_SYMBOL**: `(?i)cannot\\s+find\\s+symbol`
- **DUPLICATE_CLASS**: `(?i)^duplicate\\s+class\\s+.+`
- **ILLEGAL_START**: `(?i)illegal\\s+start\\s+of\\s+type`
- **CLASS_EXPECTED**: `(?i)class,\\s*interface,\\s*enum,?\\s*or\\s*record\\s+expected`
- **PACKAGE_NOT_FOUND**: `(?i)package\\s+.+\\s+does\\s+not\\s+exist`
- **OVERRIDE_MISMATCH**: `(?i)method\\s+does\\s+not\\s+override\\s+or\\s+implement\\s+a\\s+method\\s+from\\s+a\\s+supertype`

## Fixes Applied (illegal escape in regex char classes)
- `src/main/java/com/abandonware/ai/guard/PIISanitizer.java:12` → `private static final Pattern PHONE = Pattern.compile("\\b(?:\\+?\\d[\\d\\- ]{8,})\\b");`
- `src/main/java/com/abandonwareai/zerobreak/sanitizer/PIISanitizer.java:7` → `private static final Pattern PHONE = Pattern.compile("(\\+?\\d[\\d\\-\\s]{7,}\\d)");`
- `src/main/java/com/example/lms/debug/PromptMasker.java:24` → `Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/\\-]+=*");`
- `src/main/java/com/example/lms/matrix/MatrixTransformer.java:396` → `.replaceAll("[^\\p{L}\\p{N}\\s/\\-+'----]+", " ")`
- `src/main/java/com/example/lms/matrix/MatrixTransformer.java:430` → `.replaceAll("^[•\\-*]+\\s*", "")`
- `src/main/java/com/example/lms/service/MemoryReinforcementService.java:566` → `return Pattern.compile("^[A-Za-z0-9\\-]{6,}$").matcher(sid).matches();`
- `src/main/java/com/example/lms/service/rag/pre/DefaultGuardrailQueryPreprocessor.java:21` → `Pattern.compile("(?i)\\b(언어|language)\\s*[:：]\\s*[a-z\\-]+\\b");`
- `src/main/java/com/example/lms/service/verification/SourceAnalyzerService.java:146` → `Matcher m = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)").matcher(text);`
- `src/main/java/com/example/lms/transform/QueryTransformer.java:63` → `private static final Pattern CLEANUP_PREFIX_BULLET = Pattern.compile("^[\\-*•·]\\s*");`
