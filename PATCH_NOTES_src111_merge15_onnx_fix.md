# PATCH NOTES — src111_merge15 — OnnxScoreUtil.java fix

**Issue**  
Gradle `:compileJava` failed with multiple errors from `OnnxScoreUtil.java`:

- `illegal start of expression`
- `class, interface, enum, or record expected`

**Root Cause (from internal error-pattern scan)**  
Pattern `ILLEGAL_START_EXPRESSION` matched: static methods were declared **inside** a still-open private constructor (`private OnnxScoreUtil() {`), so the methods were parsed as statements, not members. See `BUILD_ERROR_PATTERN_SCAN.md` for the pattern catalog and mapping.

**Fix**  
- Close the private constructor (`private OnnxScoreUtil() {}`).
- Place both `logistic(...)` overloads at the class level.
- Keep the method bodies unchanged; only structural braces/Javadoc were adjusted.

**Result**  
- File compiles structurally (no top-level method declarations).
- No dependency or API surface changes.

**Touched files**
- `src/main/java/com/example/lms/service/onnx/OnnxScoreUtil.java` (edited)
- `BUILD_ERROR_PATTERN_SCAN.md` (appended "Fix Applied" section)

**Verification suggestions**
- Run `./gradlew clean compileJava -x test` locally.
- Sanity-check call sites for any signature change (none expected).
