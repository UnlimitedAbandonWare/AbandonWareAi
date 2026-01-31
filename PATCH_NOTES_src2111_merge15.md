# PATCH NOTES — src2111_merge15

## Build Error Patterns Referenced
- `spring.beans.unsatisfied-dependency` → caused by invalid boolean placeholder `{probe.search.enabled:false}`
- `javac.cannot-find-symbol` on `enabled` in `SearchProbeController`
- `gradle.bootRun.failed` due to TypeMismatch: String→boolean

(From `build-logs/error_patterns.json` & details.)

## Fixes Applied
1) Rewrote `src/main/java/com/abandonware/ai/probe/SearchProbeController.java` to use typed `@Value("${probe.search.enabled:false}")` and to declare the missing fields.
2) Ensured `app/src/main/resources/application.yml` contains:
    ```yaml
    probe:
      search:
        enabled: true
        admin-token: ${PROBE_ADMIN_TOKEN:}
    ```
3) Added tests for FinalSigmoidGate, CVaR, and DPP diversity reranker.
4) Added `org.yaml:snakeyaml` and `org.assertj:assertj-core` dependencies for Plan‑DSL loading and assertions.

## Verification (local recipe)
- `./gradlew :app:test` (JUnit5)
- Probe endpoint: `POST /api/probe/search` with header `X-Admin-Token`
