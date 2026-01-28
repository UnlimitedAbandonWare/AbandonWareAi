# Patch Report — src111_merge15 (applied 2025-10-29T02:34:04)

**What changed**

1) **Gradle wrapper fix**
   - Replaced broken `gradlew` launcher with standard wrapper script.
   - Resolves: `gradlew: exec: ./gradlew-real: not found`.

2) **Probe API hardening**
   - `app/src/main/java/com/example/lms/probe/SearchProbeController.java`:
     - Added `@Value` injections `probe.search.enabled` and `probe.admin-token`.
     - Guards `/api/probe/search` by feature flag and `X-Probe-Token`.
   - `app/src/main/resources/application.yml`:
     - Added defaults: `PROBE_SEARCH_ENABLED`, `PROBE_ADMIN_TOKEN`.

3) **App source set broadened**
   - `app/build.gradle.kts`: compile all `com/example/lms/**` (was `AppApplication` only) so controllers are included.

4) **ONNX budget naming unify**
   - `application.yml`: `onnx.semaphore-max-concurrency` → `onnx.max-concurrency` for consistency.

**Why**

- Aligns with internal pattern notes and P0 priorities (safety/guardrails, budget gate, probe endpoint usability). See internal memos. 

**Safe toggles**

```properties
probe.search.enabled=true|false
probe.admin-token=
onnx.max-concurrency=2
```

