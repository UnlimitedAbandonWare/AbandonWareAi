## Update — 2025‑10‑22 bootRun failure (Spring Boot)

Pattern: CONFIG_PROPERTIES_BINDING_VALIDATION_FAILED → UnsatisfiedDependencyException during controller creation due to `@Validated @ConfigurationProperties` record enforcing `@NotBlank` on missing property.

Signature (exact):
```
Binding to target com.example.lms.plugin.image.OpenAiImageProperties failed:
  Property: openai.image.endpoint
  Value: "null"
  Reason: 공백일 수 없습니다
```

Fix applied:
- Removed `@NotBlank` from `OpenAiImageProperties` and introduced `boolean enabled` flag (default false).
- Added `@ConditionalOnProperty(prefix="openai.image", name="enabled", havingValue="true")` to:
  - `OpenAiImageService`
  - `ImageGenerationPluginController`
- Provided safe default in `application.properties`:
  - `openai.image.enabled=false`
  - `openai.image.endpoint=/v1/images`
- Defensive default in service: if endpoint is blank, fall back to `"/v1/images"`.

Rationale: prevents property‑binding hard‑fail when image plugin is not configured; plugin activates only when explicitly enabled and configured.


# BUILD ERROR PATTERN SCAN (from cfvm-raw/BuildLogSlotExtractor)

Detected patterns:
- PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package com.networknt.schema does not exist`, `package org.springframework.data.redis.core does not exist`, `package org.springframework.kafka.* does not exist`, `package io.micrometer.* does not exist`, `package io.opentelemetry.* does not exist`, `package org.apache.lucene.analysis.ko does not exist`.
- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanLoader/Plan`.
- (No hits) DUPLICATE_CLASS / ILLEGAL_START / CLASS_EXPECTED / OVERRIDE_MISMATCH in this excerpt.

Fix strategy mapped to patterns:
- PACKAGE_NOT_FOUND → Add missing Gradle dependencies (see root build.gradle.kts).
- MISSING_SYMBOL → Make sure the module that declares the symbol is on the classpath (add `implementation(project(":cfvm-raw"))`) and correct artifact coordinates.
## Update — Auto-scan (mergerx15)

Detected in latest compile log:

- ILLEGAL_START_EXPRESSION — found 3 hits (typical cause: stray `{`/`}` or field placed outside class scope).
- CLASS_EXPECTED — found 3 hits (typically the closing `}` count exceeds opening or file tail contains extra braces).

Files:
  - C:/AbandonWare/demo-1/demo-1/src/main/java/com/abandonwareai/fusion/BodeClamp.java:12 — illegal start of expression
  - C:/AbandonWare/demo-1/demo-1/src/main/java/com/abandonwareai/fusion/BodeClamp.java:19 — class, interface, enum, or record expected
  - C:/AbandonWare/demo-1/demo-1/src/main/java/com/abandonwareai/fusion/ScoreCalibrator.java:11 — illegal start of expression
  - C:/AbandonWare/demo-1/demo-1/src/main/java/com/abandonwareai/fusion/ScoreCalibrator.java:19 — class, interface, enum, or record expected
  - C:/AbandonWare/demo-1/demo-1/src/main/java/com/abandonwareai/planner/PlannerNexus.java:14 — illegal start of expression
  - C:/AbandonWare/demo-1/demo-1/src/main/java/com/abandonwareai/planner/PlannerNexus.java:40 — class, interface, enum, or record expected

Remedy applied:
- Normalised brace pairs in: `BodeClamp.java`, `ScoreCalibrator.java`, `PlannerNexus.java` (com.abandonwareai/*).
- Rewrote `PlannerNexus` with fail-soft reflection hooks; enforced Spring `@Autowired` ctor.
- Added logistic fallback calibrator and Bode clamp implementation.


### Fix Applied (src111_merge15)
- Resolved `ILLEGAL_START_EXPRESSION` in `OnnxScoreUtil.java` by closing the private constructor and moving static methods to class scope.
- Added Javadoc hints and ensured brace structure is valid.

## 2025-10-23 — Java compile error: `class, interface, enum, or record expected` caused by stray `*/`

**Symptom (Gradle):**
```
C:\AbandonWare\demo-1\demo-1\src\main\java\com\example\lms\init\AdminInitializer.java:53: error: class, interface, enum, or record expected
*/
^
```

**Root cause:**
- File had a **broken file-path trailer** appended to `package` line (`/*// src/...`) and a **dangling `*/`** at EOF.
- This pattern likely originated from a previous instrumentation that wrapped the file path in a block comment but split start/end incorrectly.

**Signature (diffable):**
- First line matches regex: `^(package\s+[\w\.]+;\s*)(/\*//.*)$`
- Last line equals `*/` (optionally surrounded by whitespace).

**Fix applied:**
- Normalize first line to **only** `package ...;` (strip trailer).
- Remove duplicated second `package` line if identical to the first.
- Remove final dangling `*/` line.

**Files patched:**
- `src/main/java/com/example/lms/init/AdminInitializer.java`

**Post-check:**
- Project-wide scan reported **0** files with unmatched block comments (open or stray closers).

### 2025-10-27 20:29:39Z — bootRun failure (SnakeYAML → Bean property not found)

Pattern: **SNAKEYAML_PROPERTY_NOT_FOUND** → `ConstructorException: Unable to find property 'probe' on class: com.example.lms.manifest.ModelsManifest`

Signature:
```
Cannot create property=probe for JavaBean=com.example.lms.manifest.ModelsManifest
Unable to find property 'probe' on class: com.example.lms.manifest.ModelsManifest
```

Fix applied:
- **Defensive Java bean**: added `probe` / `retrieval` as `Map<String,Object>` in `ModelsManifest`.
- **Source-of-truth**: commented out `probe:` / `retrieval:` blocks in *models.manifest.yaml*, because runtime config belongs to `application.yml`, not the models manifest.
- Verified `application.yml` already contains `probe.search.enabled` and `probe.admin-token` plus `retrieval.vector.enabled`.

Rationale:
- Keep *models.manifest.yaml* strictly for model/router declarations to avoid YAML → bean mapping failures.
- Keep operational toggles in Spring Boot config (`application.yml`) where `@ConfigurationProperties(prefix="probe")` is bound.


### 2025-10-28 23:22:40Z — Automated fixes

- **Normalized Java headers**: removed stray `/*` prefix and duplicate `package` declarations; stripped orphan closing `*/` at start of file.
- **Added FinalQualityGate** minimal helper to support `gate.final.*` toggles.
- **Upgraded DppDiversityReranker** from stub to greedy implementation with cosine penalty.
- **Introduced `zero_break.v1.yaml`** and appended `gate.final`/`fusion.wpm` keys in `application.yml`.
