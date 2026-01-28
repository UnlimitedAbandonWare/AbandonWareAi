# Patch Summary — src111_merge15

This repository was normalized to a two-module Gradle build (`app` + optional `lms-core`), resource layout fixed, plan YAML autoload added, and ONNX reranker wired as a toggle.

## Key changes
- settings.gradle: ensure `include("app", "lms-core")` when applicable.
- app/build.gradle.kts: compile only `app/src/main/java_clean`, treat `plans/` as resources, add SnakeYAML/SLF4J/ONNX deps, and add `verifyResources` / `verifyDuplicates` tasks.
- application.yml: converted to pure YAML; secrets moved to environment variables.
- PlannerNexus: loads `classpath*:plans/*.yaml` at startup.
- Plans: added placeholders for `brave.v1.yaml`, `zero_break.v1.yaml`, `recency_first.v1.yaml` when missing.
- Reports: generate duplicate classes report at `__reports__/duplicate-classes.tsv`.

## Build
Run:
```
./gradlew :app:verifyResources :app:verifyDuplicates
```