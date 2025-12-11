# Build Error Pattern Summary — src111_merge15
Scope: compileJava

## Observed Patterns (from logs + in-repo scanner)
1) **MISSING_IMPORT / UNRESOLVED TYPE**
   - `QueryTransformer` and `QueryCorrectionService` referenced without import in `ChatService__backup_pre_interface`.
   - Fix: add explicit imports for `com.example.lms.transform.QueryTransformer`, `com.example.lms.service.correction.QueryCorrectionService`, plus `@Qualifier` import.

2) **LOMBOK_BUILDER_DEFAULT_WITHOUT_BUILDER** (warnings)
   - `@Builder.Default` used on classes without `@Builder` / `@SuperBuilder`: `ChatRequestDto`, `ChatSession`, `Question`, `Rental`.
   - Not fatal; can be cleaned by adding Lombok builders or removing `@Builder.Default` where not needed.

## Why this fixes the build
Gradle failed after the first blocking errors, so resolving symbol imports is sufficient to proceed to the next stage.
