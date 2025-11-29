# Build Error Auto-Remediation — src111_merge15

This patch bundle automatically applied targeted fixes based on prior build error patterns.

## Top Patterns (from NOVA_ERRORBREAK__build_pattern_report.jsonl)

- E_CANNOT_FIND_SYMBOL — 81 hits
- E_TIMEOUT — 18 hits
- E_DUPLICATE_KEY — 12 hits
- E_NO_UNIQUE_BEAN — 7 hits

## Fixes Applied

1) **Duplicate Java classes**
   - Disabled duplicate: `lms-core/.../HybridRetriever.java` → `HybridRetriever.java.disabled` (primary retained under `src/...`).

2) **Duplicate YAML keys**
   - Commented repeated keys (marked with `# DUPFIX`) in:
   - work_src111_mersagea15/src/main/resources/matrix_policy.yaml
   - work_src111_mersagea15/src/main/resources/catalog/concepts.yml
   - work_src111_mersagea15/src/main/resources/catalog/orgs.yml
   - work_src111_mersagea15/src/main/resources/configs/models.manifest.yaml
   - work_src111_mersagea15/configs/models.manifest.yaml
   - work_src111_mersagea15/configs/prometheus/agent-alerting-rules.yml
   - work_src111_mersagea15/configs/prometheus/agent-recording-rules.yml

3) **Bean ambiguity**
   - Marked `CfvmRawService` bean in `NovaErrorBreakConfig` as `@Primary` to ensure unique injection.

4) **CFVM Error Break datasets**
   - Merged presets from `cfvm_errorbreak_out.zip` into classpath resources:
     - `cfvm-raw/src/main/resources/novabreak_fake_experiences.jsonl` (merged 40→165 lines)
     - `cfvm-raw/src/main/resources/cfvm_errorbreak_presets.yaml`
     - `cfvm-raw/src/main/resources/nova_errorbreak_context_hints.jsonl`

## Safe Defaults

- `application.yml` already contains Nova Error Break guard:
  ```
  nova.errorbreak:
    enabled: true
    mode: guard
    cluster:
      min-support: 3
      min-confidence: 0.6
      cooldown-seconds: 900
  ```

## Recommended Follow‑ups

- If Gradle still reports `E_NO_UNIQUE_BEAN` for `CfvmRawService`, consider removing `@Service` on duplicate package variants or qualify injection points.
- Re-run CI with three dataset presets (OK/WARN/BREAK) to validate guard behavior.
