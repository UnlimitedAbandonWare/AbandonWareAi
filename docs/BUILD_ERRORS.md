# Build Error Notes — 2025-10-18

    **Root cause**  
    `@Value` placeholder used without `$` sign, yielding literal string `{probe.search.enabled:false}` which Spring failed to coerce to boolean.

    **Symptom**  
    - `UnsatisfiedDependencyException` on `SearchProbeController` (constructor param index=1)  
    - `TypeMismatchException`: String → boolean, *Invalid boolean value* `{probe.search.enabled:false}`  
    - Gradle `:bootRun` non-zero exit

    **Fix**  
    1) Replace `@Value("{probe.search.enabled:false}")` → `@Value("${probe.search.enabled:false}")`.  
    2) Provide typed configuration via `ProbeProperties` (`@ConfigurationProperties(prefix="probe")`).  
    3) Ensure defaults in `application.yml`:
       ```yaml
       probe:
         search:
           enabled: ${PROBE_SEARCH_ENABLED:false}
         admin-token: ${PROBE_ADMIN_TOKEN:}
       ```

    **Error pattern summary (auto-extracted)**  
    ```json
    {
  "spring.beans.unsatisfied-dependency": 2,
  "config.value.invalid-boolean.probe.search.enabled": 4,
  "spring.boot.application-run-failed": 1,
  "spring.beans.type-mismatch": 1,
  "gradle.bootRun.failed": 1,
  "gradle.process.non-zero-exit": 1
}
    ```

    **How to verify**  
    - `./gradlew bootRun` should start without the boolean conversion error.  
    - `POST /api/probe/search` should be *disabled* by default (HTTP 404 or 403 depending on controller gating).  
    - Set `PROBE_SEARCH_ENABLED=true` to allow the probe endpoint for admins.

---

## 2025-10-18 — compileJava failure: cannot find symbol `enabled` in SearchProbeController

**Symptom**
```
error: cannot find symbol
    if (!enabled) return ResponseEntity.status(403).body("Probe disabled");
         ^
  symbol:   variable enabled
  location: class SearchProbeController
```

**Root cause**
Duplicate `SearchProbeController` under `com.abandonware.ai.probe` referenced a non-existent field `enabled`.

**Fix**
- Replaced the class with a minimal, typed version that declares and injects:
  ```java
  @Value("${probe.search.enabled:false}") boolean enabled;
  @Value("${probe.admin-token:}") String adminToken;
  ```
  and guards `/api/probe/search` accordingly.

**Status**
- Patched `src/main/java/com/abandonware/ai/probe/SearchProbeController.java`.
- Extended `tools/error_pattern_extractor.py` to recognize `javac.*` patterns.
