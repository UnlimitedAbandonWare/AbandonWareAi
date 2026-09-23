# A03 Prompt Asset Trait Boundary Implementation Plan

> Execution is authorized by the active goal. Staging, commits, pushes, dependency changes, and broad cleanup are prohibited.

**Goal:** Prevent public trait identifiers from resolving outside trait asset roots by applying the existing public prompt-ID contract at the owning resolver.

**Architecture:** Keep `PromptAssetService` as the single asset boundary. Characterize the traversal behavior in the existing public contract test, freeze a three-way evidence decision, acquire the repository source-edit lease, then add one guard before cache/resource work.

**Stack:** Java 17, Spring resource abstraction, JUnit 5, Gradle wrapper 8.7.

## Task 1: Freeze the target and prove RED

Files:
- Modify: `src/test/java/com/example/lms/service/prompt/PromptAssetServicePublicContractTest.java`
- Read only: `main/java/com/example/lms/service/prompt/PromptAssetService.java`

1. Recheck target/test Git status, SHA-256, size, and mtime.
2. Add one test that calls `resolveTraitText("../system/projection.final")` and expects `null`.
3. Run:

   `gradlew.bat test --tests com.example.lms.service.prompt.PromptAssetServicePublicContractTest --rerun-tasks`

4. Require RED at the new null assertion because the system asset is returned. Any compile/configuration failure is not accepted as RED.

## Task 2: Three-way source-edit preflight and lease

1. Freeze one redacted EvidenceSnapshot with no more than 20 rows, including HEAD, selected sourceSet, target/test preimages, RED output, target status, PatchDrop state, index lock, and source-edit lease state.
2. Evaluate exactly POSITIVE, NEGATIVE, and NEUTRAL packets over the same scenario IDs in forward and reverse order.
3. Continue only on stable `APPLY` with score at least 50.
4. Recheck the production target preimage, then acquire the existing `desktop` source-edit lease for this target.

## Task 3: Minimal production correction

File:
- Modify: `main/java/com/example/lms/service/prompt/PromptAssetService.java`

1. Immediately after `String trimmed = traitId.trim();`, return `null` when `isSafePromptId(trimmed)` is false.
2. Do not change the helper, lookup order, cache, exceptions, resources, properties, or callers.
3. Inspect the exact diff and confirm the preimage matched immediately before the edit.

## Task 4: Focused and adjacent verification

Run in this order with isolated Desktop Gradle caches:

1. `gradlew.bat test --tests com.example.lms.service.prompt.PromptAssetServicePublicContractTest --rerun-tasks`
2. Related prompt boundary contract tests selected from the current test inventory.
3. `gradlew.bat compileJava`
4. `gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene`
5. `gradlew.bat :app:classes`
6. `gradlew.bat test --rerun-tasks --fail-fast`
7. Count-only secret/protected-key scan over this Work Unit's diff.
8. Recheck target/test postimage hashes, goal-owned diff, and unrelated status preservation; release the lease in a `finally` path.

## Task 5: Handoff

1. Regenerate the 150-row ledger with A03 marked closed by Work Unit 1.
2. Write the top-20, verification, remaining-risk, and NEXT artifacts outside production source.
3. Report provider/browser/load/DB evidence as `NOT_OBSERVED` because this asset-boundary fix does not require those surfaces.
