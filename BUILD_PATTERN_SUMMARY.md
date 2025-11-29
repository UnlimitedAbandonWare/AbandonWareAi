# BUILD Pattern Summary

This summary was auto-generated from recent build failures using the in-repo pattern extractor
(`cfvm-raw/src/main/java/com/example/lms/cfvm/BuildLogSlotExtractor.java`).

## Recognized patterns (and mapped codes)

- duplicate bean name (e.g., `ConflictingBeanDefinitionException: Annotation-specified bean name ... conflicts`) → **BeanNameConflict**

- Lombok `@Slf4j` and manual logger collide → **Slf4jLogFieldConflict**

- `method does not override or implement a method from a supertype` → **OverrideMismatch**

- `cannot find symbol` → **MissingSymbol**
- `duplicate class` → **DuplicateClass**
- `illegal start of type` → **IllegalStartOfType**
- `class, interface, enum, or record expected` → **ClassOrInterfaceExpected**
- `package ... does not exist` → **PackageNotFound**


- `found duplicate key .* \(YAML\)` → **YamlDuplicateKey**
- `class <Name> is public, should be declared in a file named <Name>.java` → **PublicClassFileNameMismatch**
## Latest incidents (from provided logs)

1. **MissingSymbol** — `VectorAliasCorrector` not found  
   - Observed in `AliasCorrectionPreResolveAspect.java` and `CorrectionBootstrapConfig.java`  
   - **Fix**: Added `VectorAliasCorrector` (minimal, dependency‑free) and restored AOP aspect.

2. **IllegalStartOfType / ClassOrInterfaceExpected** — stray tokens or brace imbalance  
   - Examples: `OnnxCrossEncoderReranker.java`, `WeightedRRF.java` (ellipses or extra braces)  
   - **Fix**: Rewrote minimal, compilable implementations.

- `method does not override or implement a method from a supertype` → **OverrideMismatch**

## Latest incidents (continued)

3. **OverrideMismatch** — `@Override` annotated method signature did not match any supertype
   - Observed in `NoopCrossEncoderReranker.java` (corrupted method signature with ellipses like `...ava.util.List`)
   - **Fix**: Rewrote the class with correct interface signature:

     `List<Content> rerank(String query, List<Content> candidates, int topN)`

     and kept a convenience overload `rerank(String, List<Content>)`. Also retained a `status()` helper returning `RerankerStatus`.


- `constructor ChatService in class ChatService cannot be applied to given types; required: ... found: no arguments` → **SuperConstructorMissing**
  - Context: Subclass declares no constructor while the parent has only Lombok-generated `@RequiredArgsConstructor` (no default ctor).
  - Fix options:
    1) Remove inheritance if the subclass is only a documentation alias.
    2) Or add a subclass constructor that delegates to `super(...)` with the exact field order.


## Latest incidents (recent)

- **MissingSymbol** — `log` field missing in `VersionPurityCheck` after removing Lombok.


## 2025-10-22 — Fix: JDK incubator Vector API / preview flags
**Symptom**  
```
error: package jdk.incubator.vector does not exist
error: Preview features are not enabled for unit ...
```
**Root cause**  
Gradle toolchain locked at Java 17 while modules under `app` introduced dependencies that require Java 20+ Vector API (Jlama).

**Fix**  
- Upgraded `app/build.gradle.kts` toolchain to Java 21.  
- Added `--enable-preview` and `--add-modules=jdk.incubator.vector` to `compileJava`, `test`, `run` tasks.  
- Introduced `runtimeOnly "com.github.tjake:jlama-core:+"` and reflective adapter; build remains green even when Jlama is absent.

---
**New pattern (auto-appended — 2025-10-24 00:52:53Z):**

- `illegal start of expression` caused by `; * authorityDecayMultiplier` after a closed expression.  
  **Fix:** fold the multiply into the same expression:  
  `double score = (sim + subjectTerm + genericTerm + ruleDelta + (synergyBonus * synergyWeight)) * authorityDecayMultiplier;`  
  **File:** src/main/java/com/example/lms/service/rag/EmbeddingModelCrossEncoderReranker.java (around L226).

