# src111_merge15 — Patch Report

## What I changed (build + wiring)

1) **Activated `lms-core` module** by renaming `lms-core/build.gradle.kts.bak` → `lms-core/build.gradle.kts` and
   configured Java 17 toolchain, Lombok (compileOnly/annotationProcessor), and **sourceSets** to include the
   previously orphaned root sources at `../src/main/java` and resources at `../src/main/resources`.
   This addresses the bulk of `cannot find symbol` errors where `app` referenced classes that lived under the root
   source tree but were not part of any Gradle module.

2) **Added `implementation(project(":lms-core"))`** to `app/build.gradle.kts` so the app sees core classes.

3) **Standardized Lombok** across modules and added a root `lombok.config` with `lombok.log.fieldName = log`.

4) **Applied PR1~PR8 toggles** (calibration / MP-Law / DPP / embedding cache / self‑ask / ONNX semaphore /
   time‑budget / final sigmoid gate) into `app/src/main/resources/application.yml` under a dedicated `score.*`,
   `rerank.dpp.*`, `embedding.cache.*`, `planner.*`, `onnx.*`, `budget.*`, `quality.sigmoid.*` namespace.

## Why this fixes the recurrent build errors

• **cannot find symbol** — Caused by `app` compiling in isolation without the root sources. By turning those sources
  into `:lms-core` and adding a project dependency, the types resolve. Lombok is ensured for `@Slf4j`, etc.

• **Interrupted tryAcquire** — Existing ONNX guards already use `tryAcquire(timeout, unit)` inside methods that can
  throw. No global changes were required for compilation; if needed, wrap call sites with `catch (InterruptedException ie)`
  and set the thread interrupt flag (ie.g., `Thread.currentThread().interrupt()`) returning safe fallbacks.

• **Unreachable after finally** — Only one file matched the heuristic. The current code has `finally { }` with the
  `return` **outside**, which is valid. No change required for compilation.

## How to verify (without CI)

- Gradle: `./gradlew :lms-core:build :app:build`
- Expect dependency resolution and Lombok processing to succeed.
