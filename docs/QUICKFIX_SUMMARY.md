# Quick Fix Summary — src111_merge15

## What I fixed
- **Illegal escape character in Java regex strings** (e.g. `\\-`): replaced with valid forms (mostly `\-` or placing `-` at the end of a character class).
- Normalized several bullet/prefix cleanup regexes to avoid fragile escaping.
- Tightened Bearer token mask (PromptMasker) while keeping `-` and `/` allowed.
- Left a consolidated report in `docs/build_error_patterns.report.md` (includes patterns already defined in `cfvm-raw/.../BuildLogSlotExtractor.java`).

## Files touched
- `src/main/java/com/abandonware/ai/guard/PIISanitizer.java`
- `src/main/java/com/abandonwareai/zerobreak/sanitizer/PIISanitizer.java`
- `src/main/java/com/example/lms/debug/PromptMasker.java`
- `src/main/java/com/example/lms/matrix/MatrixTransformer.java`
- `src/main/java/com/example/lms/service/MemoryReinforcementService.java`
- `src/main/java/com/example/lms/service/rag/pre/DefaultGuardrailQueryPreprocessor.java`
- `src/main/java/com/example/lms/service/understanding/AnswerUnderstandingService.java`
- `src/main/java/com/example/lms/service/verification/SourceAnalyzerService.java`
- `src/main/java/com/example/lms/transform/QueryTransformer.java`

## Build tip
If you still see regex-related errors, prefer these patterns:
- Place `-` **at the end** of a character class, e.g. `[A-Za-z0-9-]`, or escape it as `\-`.
- When you need a **backslash** inside a character class, write it as `\\` in a Java string literal.
- Avoid `\uXXXX` in patterns when a literal character (or an escaping in the regex) is sufficient.

--
