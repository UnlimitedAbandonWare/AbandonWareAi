# Hypernova Patch4 — Build Pattern Error-Rate Mitigation

This auto-generated patch uses **synthetic failure sampling** and **clustering** to lower observed build-pattern error rates.

## Inputs
- `build-logs/error_patterns.json`
- `build-logs/error_patterns_detail.json`
- `analysis/build_error_report.json`

## Method
1) **Poisson bootstrapping + adversarial max-outliers** on pattern counts to approximate slope-of-error under stress.
2) **K-Means clustering** over features: `count_sim`, `slope_proxy`, and categorical bucket (`gradle`, `javac`, `spring/config`).
3) **Cluster-to-mitigation mapping** producing `build/pattern_guard_config.json`.
4) **Config override** for known type-mismatch (`probe.search.enabled`) → `config/application-overrides.properties`.

## Cluster semantics
{
  "0": {
    "label": "spring-config/type-mismatch",
    "mitigation": [
      "Add relaxed boolean binder: treat 'true/false', '1/0', 'yes/no'",
      "Add override defaults in application.properties with strict types",
      "Fail-fast validator on critical @Value placeholders"
    ]
  },
  "1": {
    "label": "spring-config/type-mismatch",
    "mitigation": [
      "Add relaxed boolean binder: treat 'true/false', '1/0', 'yes/no'",
      "Add override defaults in application.properties with strict types",
      "Fail-fast validator on critical @Value placeholders"
    ]
  },
  "2": {
    "label": "spring-config/type-mismatch",
    "mitigation": [
      "Add relaxed boolean binder: treat 'true/false', '1/0', 'yes/no'",
      "Add override defaults in application.properties with strict types",
      "Fail-fast validator on critical @Value placeholders"
    ]
  }
}

## What changed
- Added `analysis/_auto_patch4/synthetic_failures.csv` (5× stress per pattern) and `cluster_summary.csv`.
- Added `build/pattern_guard_config.json` (pattern → mitigation).
- Added `config/application-overrides.properties` to strictly bind booleans.

## How to apply
- Merge `application-overrides.properties` into your active profile or pass `--spring.config.import=optional:config/application-overrides.properties`.
- For Gradle, set:

  ```
  org.gradle.caching=true
  org.gradle.parallel=false
  org.gradle.workers.max=1
  ```

## Expected effect (heuristic)
- Config/runtime mismatches are short-circuited before Spring context refresh.
- Gradle flakiness reduced by serializing flaky tasks and enabling cache.
- Java compile failures surface earlier via duplicate-class precheck (see `DUPLICATE_CLASS_REPORT.md`).

> Note: This patch **does not** delete or overwrite original files. Review and adjust as needed.
