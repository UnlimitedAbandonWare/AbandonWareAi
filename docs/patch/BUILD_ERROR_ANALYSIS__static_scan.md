# Build Error Pattern Mining (static, repository text scan)
Date: 2025-10-27T21:56:33.924110Z

Total hits: 1790
Top patterns: [('\\bERROR\\b', 1051), ('cannot find symbol', 535), ('IllegalStateException', 92), ('package .* does not exist', 79), ('Compilation failed', 28), ('ClassNotFoundException', 5)]

Sample contexts:
{
  "/mnt/data/work_src111_smerge15/BUILD_FIX_NOTES.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "trieval:' -n src/main/resources/application.yml` → now **1** occurrence in doc#2.\n\n\n## 2025-10-09 — Fix: Lombok super-constructor required due to alias subclass\n\n**Symptom**  \n```\nerror: constructor ChatService in class ChatService cannot be applied to given types;\nclass AbandonWareAi_ChatService extends ChatService {\n^\n  required: QueryTransformer,CircuitBreaker"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "issues resolved.\n\n**Verification**\n- `gradle :compileJava` should now pass the previously reported 41 errors related to these files.\n\n\n## src111_merge15 — Fix #001 (2025-10-12)\n\n**Error Pattern:** `illegal start of expression` (unmatched constructor brace)\n- **Detector:** cfvm-raw/.../BuildLogSlotExtractor.java (pattern `ILLEGAL_START` didn't match 'expression', "
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "itivity()/evidenceTypes()/complexityBudget()`.  \n  ➜ Added alias methods that delegate to the current enums and derive evidence types from execution mode.\n\nArtifacts:\n- `dev/build/error-patterns.json` — flags extracted from `BUILD_LOG.txt`.\n- `BUILD_PATTERN_REPORT__auto.md` — summary for this patch.\n"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "type-per-file rule.\n- Enabled Lombok annotation processing in `lms-core` (and tests) to restore getters/setters, constructors, and `@Slf4j` loggers.\n- These address the cascading 'cannot find symbol get*/set*' and 'log' errors across shared sources compiled by `lms-core`.\n\n\n## 2025-10-09 05:28:45 — Fix: SnakeYAML DuplicateKeyException (application.yml)\n**Symptom**  \n`org.yaml"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "d by Gradle as configured in `settings.gradle`. See `DUPLICATE_CLASS_REPORT.md` for context.\n\n\n## 2025-10-21 fix: Missing EmbeddingCache for DecoratingEmbeddingModel\n**Pattern**: `cannot find symbol: class EmbeddingCache` in `com.example.lms.service.embedding.DecoratingEmbeddingModel`.\n\n**Root cause**: cache abstraction type not present in source-set; decorator expects `Embed"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "ievalHandlerChain cleanup\n\n**Errors fixed**\n- `incompatible types: ScoreCalibrator cannot be converted to boolean` @ `com.abandonware.ai.agent.service.rag.fusion.FusionService`\n- `cannot find symbol: variable calibrator` @ `com.abandonware.ai.service.rag.fusion.WeightedRRF`\n- `cannot find symbol: variable diversity` and `package service.embedding does not exist` @ `com.abando"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "rted to boolean` @ `com.abandonware.ai.agent.service.rag.fusion.FusionService`\n- `cannot find symbol: variable calibrator` @ `com.abandonware.ai.service.rag.fusion.WeightedRRF`\n- `cannot find symbol: variable diversity` and `package service.embedding does not exist` @ `com.abandonware.ai.service.rag.handler.DynamicRetrievalHandlerChain`\n\n**Root cause patterns**\n- *API drift*:"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "service.rag.fusion.FusionService`\n- `cannot find symbol: variable calibrator` @ `com.abandonware.ai.service.rag.fusion.WeightedRRF`\n- `cannot find symbol: variable diversity` and `package service.embedding does not exist` @ `com.abandonware.ai.service.rag.handler.DynamicRetrievalHandlerChain`\n\n**Root cause patterns**\n- *API drift*: Call site passed a `ScoreCalibrator` but API expected `(…, boolean"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "NineTileAliasCorrector.java\n\nThis preserves `required=false` semantics if present.\n\n## Why this resolves the boot failure\n\nThe hard failure in your log is the YAML-to-POJO mapping error on `alias:`. Removing that key (or supporting it in the POJO) lets the context refresh complete. The `@Qualifier` change is a safety patch that eliminates an additional startup ha"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_src96_to_src97.md": [
    {
      "pattern": "IllegalStateException",
      "snippet": "# Patch Notes: src96 → src97\n\n**Issue fixed**: Application failed to start with\n`IllegalStateException: Manifest not found: configs/models.manifest.yaml` in `ModelManifestConfig`.\n\n**What changed**\n1) Copied project-level manifest `src_91/configs/models.manifest.yaml` into the appl"
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_PATTERN_SUMMARY.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "recent)\n\n- **MissingSymbol** — `log` field missing in `VersionPurityCheck` after removing Lombok.\n\n\n## 2025-10-22 — Fix: JDK incubator Vector API / preview flags\n**Symptom**  \n```\nerror: package jdk.incubator.vector does not exist\nerror: Preview features are not enabled for unit ...\n```\n**Root cause**  \nGradle toolchain locked at Java 17 while modules under `app`"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "in `VersionPurityCheck` after removing Lombok.\n\n\n## 2025-10-22 — Fix: JDK incubator Vector API / preview flags\n**Symptom**  \n```\nerror: package jdk.incubator.vector does not exist\nerror: Preview features are not enabled for unit ...\n```\n**Root cause**  \nGradle toolchain locked at Java 17 while modules under `app` introduced dependencies that require Java 20+ Vect"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "Conflict**\n\n- Lombok `@Slf4j` and manual logger collide → **Slf4jLogFieldConflict**\n\n- `method does not override or implement a method from a supertype` → **OverrideMismatch**\n\n- `cannot find symbol` → **MissingSymbol**\n- `duplicate class` → **DuplicateClass**\n- `illegal start of type` → **IllegalStartOfType**\n- `class, interface, enum, or record expected` → **ClassOrInterfac"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "mbol**\n- `duplicate class` → **DuplicateClass**\n- `illegal start of type` → **IllegalStartOfType**\n- `class, interface, enum, or record expected` → **ClassOrInterfaceExpected**\n- `package ... does not exist` → **PackageNotFound**\n\n\n- `found duplicate key .* \\(YAML\\)` → **YamlDuplicateKey**\n- `class <Name> is public, should be declared in a file named <Name>.java` → **PublicClassFileN"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "\n\n- **MissingSymbol** — `log` field missing in `VersionPurityCheck` after removing Lombok.\n\n\n## 2025-10-22 — Fix: JDK incubator Vector API / preview flags\n**Symptom**  \n```\nerror: package jdk.incubator.vector does not exist\nerror: Preview features are not enabled for unit ...\n```\n**Root cause**  \nGradle toolchain locked at Java 17 while modules under `app` introduced dependencies that require Java 20"
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_PATTERN_RUN.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "repo pattern extractor (`cfvm-raw/src/main/java/com/example/lms/cfvm/BuildLogSlotExtractor.java`). ##...\n  - /mnt/data/work_src111_mergsae15_3/BUILD_ERROR_PATTERN_SCAN.md: # BUILD ERROR PATTERN SCAN (from cfvm-raw/BuildLogSlotExtractor) Detected patterns: - PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package...\n- **D"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "repo pattern extractor (`cfvm-raw/src/main/java/com/example/lms/cfvm/BuildLogSlotExtractor.java`). ##...\n  - /mnt/data/work_src111_mergsae15_3/BUILD_ERROR_PATTERN_SCAN.md: # BUILD ERROR PATTERN SCAN (from cfvm-raw/BuildLogSlotExtractor) Detected patterns: - PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package...\n- **O"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "SlotExtractor.java`). ##...\n  - /mnt/data/work_src111_mergsae15_3/BUILD_FIX_NOTES__79_fusion.md: # Build Fix Notes — src111_merge15 (79) ## Symptoms - `Bm25LocalRetriever.java:26: error: method does not override or implement a method from a supertype` - `FusionService.java:36: error: method...\n\n\n\n## [2025-10-21 21:50:29 UTC] Pattern detected & resolved — Missing "
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": " Build Fix Notes — src111_merge15 (79) ## Symptoms - `Bm25LocalRetriever.java:26: error: method does not override or implement a method from a supertype` - `FusionService.java:36: error: method...\n\n\n\n## [2025-10-21 21:50:29 UTC] Pattern detected & resolved — Missing bean due to feature flag\n**Signature:** `UnsatisfiedDependencyException` → requires `OpenAiImageSe"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "  - /mnt/data/work_src111_mergsae15_3/BUILD_ERROR_PATTERN_SCAN.md: # BUILD ERROR PATTERN SCAN (from cfvm-raw/BuildLogSlotExtractor) Detected patterns: - PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package...\n- **DuplicateClass**: 10\n  - /mnt/data/work_src111_mergsae15_3/DUPLICATE_CLASS_REPORT.md: # Duplicate Class Report: HybridRetriever During the patch process a duplic"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "  - /mnt/data/work_src111_mergsae15_3/BUILD_ERROR_PATTERN_SCAN.md: # BUILD ERROR PATTERN SCAN (from cfvm-raw/BuildLogSlotExtractor) Detected patterns: - PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package...\n- **OverrideMismatch**: 5\n  - /mnt/data/work_src111_mergsae15_3/BUILD_PATTERN_SUMMARY.md: # BUILD Pattern Summary This summary was auto-generated from recent build f"
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_ERROR_PATTERN_SCAN.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "ack to `\"/v1/images\"`.\n\nRationale: prevents property‑binding hard‑fail when image plugin is not configured; plugin activates only when explicitly enabled and configured.\n\n\n# BUILD ERROR PATTERN SCAN (from cfvm-raw/BuildLogSlotExtractor)\n\nDetected patterns:\n- PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package com.net"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "reUtil.java` by closing the private constructor and moving static methods to class scope.\n- Added Javadoc hints and ensured brace structure is valid.\n\n## 2025-10-23 — Java compile error: `class, interface, enum, or record expected` caused by stray `*/`\n\n**Symptom (Gradle):**\n```\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\init\\AdminInitializer.java"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "ass, interface, enum, or record expected` caused by stray `*/`\n\n**Symptom (Gradle):**\n```\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\init\\AdminInitializer.java:53: error: class, interface, enum, or record expected\n*/\n^\n```\n\n**Root cause:**\n- File had a **broken file-path trailer** appended to `package` line (`/*// src/...`) and a **dangling `*/`**"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "ot exist`, `package io.micrometer.* does not exist`, `package io.opentelemetry.* does not exist`, `package org.apache.lucene.analysis.ko does not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriToke"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": ".* does not exist`, `package io.opentelemetry.* does not exist`, `package org.apache.lucene.analysis.ko does not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanL"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "try.* does not exist`, `package org.apache.lucene.analysis.ko does not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanLoader/Plan`.\n- (No hits) DUPLICATE_CLASS /"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "apache.lucene.analysis.ko does not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanLoader/Plan`.\n- (No hits) DUPLICATE_CLASS / ILLEGAL_START / CLASS_EXPECTED / OV"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanLoader/Plan`.\n- (No hits) DUPLICATE_CLASS / ILLEGAL_START / CLASS_EXPECTED / OVERRIDE_MISMATCH in this excerpt"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "— e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanLoader/Plan`.\n- (No hits) DUPLICATE_CLASS / ILLEGAL_START / CLASS_EXPECTED / OVERRIDE_MISMATCH in this excerpt.\n\nFix strategy mapped to pat"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "ma`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanLoader/Plan`.\n- (No hits) DUPLICATE_CLASS / ILLEGAL_START / CLASS_EXPECTED / OVERRIDE_MISMATCH in this excerpt.\n\nFix strategy mapped to patterns:\n- PACKAGE_NOT_FOUND → Add mis"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "; plugin activates only when explicitly enabled and configured.\n\n\n# BUILD ERROR PATTERN SCAN (from cfvm-raw/BuildLogSlotExtractor)\n\nDetected patterns:\n- PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package com.networknt.schema does not exist`, `package org.springframework.data.redis.core does not exist`, `package org.springframework.kafka.* does not exist`, `package io.micrometer.* does not exist`, `package io.opentelemetry.* does not exist`, `package org.apache.lucene.analysis.ko does not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot fi"
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_PATTERN_REPORT.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# Build Error Pattern Report — src111_merge15a → src111_merge15\n\n**Date:** 2025-10-12\n\n## Inputs\n- Source: `BUILD_LOG.txt` (scanned), developer-provided Gradle output (pasted in request)\n\n## Ex"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "ctor.java`  \n  Recognizes patterns: `MISSING_SYMBOL`, `DUPLICATE_CLASS`, `ILLEGAL_START (type)`, `CLASS_EXPECTED`, `PACKAGE_NOT_FOUND`, `OVERRIDE_MISMATCH`.\n\n## Findings\n- Current error: **`illegal start of expression`** → *not matched* by existing `ILLEGAL_START` (which expects `type`).  \n  **Root cause located:** missing `}` closing brace after constructor in `"
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_FIX_REPORT.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# BUILD FIX REPORT — src111_merge15\n\nDate: 2025-10-12T22:30:31.495902Z\n\nDetected common error patterns from in-repo scan:\n\n- PACKAGE_NOT_FOUND: `org.apache.lucene.analysis.ko.*`, `com.networknt.schema.*`\n\n- MISSING_SYMBOL: `NoriTokenizer`, `JsonSchema`, `PlanLoader`\n\n\nAppl"
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_FIX_NOTES__77_bm25.md": [
    {
      "pattern": "cannot find symbol",
      "snippet": "otes — src111_merge15 (77)\n\n## Errors observed\n- `class, interface, enum, or record expected` around lines with `} catch (Throwable ignore) { /* analyzer not on classpath */ }`\n- `cannot find symbol: class NoriAnalyzer` at:\n  - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexHolder`\n  - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexService`\n\n## Root causes (matched "
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_FIX_NOTES__79_fusion.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# Build Fix Notes — src111_merge15 (79)\n\n## Symptoms\n- `Bm25LocalRetriever.java:26: error: method does not override or implement a method from a supertype`\n- `FusionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionServ"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "Build Fix Notes — src111_merge15 (79)\n\n## Symptoms\n- `Bm25LocalRetriever.java:26: error: method does not override or implement a method from a supertype`\n- `FusionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal bu"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": " not override or implement a method from a supertype`\n- `FusionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal build-error patterns)\n- **OverrideMismatch**: `@Override` present while the class does not implement/e"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "thod fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal build-error patterns)\n- **OverrideMismatch**: `@Override` present while the class does not implement/extend a supertype that declares `retrieve(String,int)`.\n- **API-SignatureDrift**: `Weight"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "erride or implement a method from a supertype`\n- `FusionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal build-error patterns)\n- **OverrideMismatch**: `@Override` present while the class does not implement/extend a supertype th"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_src111_merge15_onnx_fix.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": " `:compileJava` failed with multiple errors from `OnnxScoreUtil.java`:\n\n- `illegal start of expression`\n- `class, interface, enum, or record expected`\n\n**Root Cause (from internal error-pattern scan)**  \nPattern `ILLEGAL_START_EXPRESSION` matched: static methods were declared **inside** a still-open private constructor (`private OnnxScoreUtil() {`), so the method"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES__mxerge15.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "noise reduction).\n4) Generated machine-readable report: `PATCH_REPORT__nine_tile_alias_corrector.json`.\n5) No destructive deletions; all changes are additive or guarded.\n\n## Build error patterns (from repo artifacts)\n- Frequent: `cannot find symbol` (95 occurrences in logs)\n- `package lombok does not exist` (fixed by adding Lombok)\n- `package ... does not exist` "
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "port: `PATCH_REPORT__nine_tile_alias_corrector.json`.\n5) No destructive deletions; all changes are additive or guarded.\n\n## Build error patterns (from repo artifacts)\n- Frequent: `cannot find symbol` (95 occurrences in logs)\n- `package lombok does not exist` (fixed by adding Lombok)\n- `package ... does not exist` (generic; mitigated by adding Spring starters)\n- Duplicates: 18"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "son`.\n5) No destructive deletions; all changes are additive or guarded.\n\n## Build error patterns (from repo artifacts)\n- Frequent: `cannot find symbol` (95 occurrences in logs)\n- `package lombok does not exist` (fixed by adding Lombok)\n- `package ... does not exist` (generic; mitigated by adding Spring starters)\n- Duplicates: 184 duplicate FQCNs detected → potential runtime bean ambigui"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": " or guarded.\n\n## Build error patterns (from repo artifacts)\n- Frequent: `cannot find symbol` (95 occurrences in logs)\n- `package lombok does not exist` (fixed by adding Lombok)\n- `package ... does not exist` (generic; mitigated by adding Spring starters)\n- Duplicates: 184 duplicate FQCNs detected → potential runtime bean ambiguity\n\n## Next steps\n- Consider consolidating duplicate cla"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES__src111s_merge15.md": [
    {
      "pattern": "package .* does not exist",
      "snippet": "\n# Patch Notes — src111s_merge15\nDate: 2025-10-15T05:24:50.897872Z\n\n## Fixed compile errors\n- **package com.example.lms.config.alias does not exist** → Added `NineTileAliasCorrector` in both packages:\n  - `com.example.lms.config.alias`\n  - `com.abandonware.ai.config.alias`\n  under *each* module's `src/main/java`.\n- Adjusted i"
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_FIX_REPORT__merge15.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": " Calibrators, Planner Nexus, Context Hygiene, MCP, Telemetry, and Learning.\n- Renamed `RuleBreakInterceptor` bean in app module to `novaRuleBreakInterceptor`.\n\n## Historical Build Error Patterns (from repo telemetry)\n- Lombok/Logger symbol errors, Bean name conflicts, YAML DuplicateKeyException, One-public-type-per-file violations (OCR models), Wrong imports in F"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_src111_wmerge15.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# PATCH NOTES — src111_wmerge15\n\n**Goal**: apply safe drop-in fixes, wire missing beans (off by default), and resolve compile-time breakages found by the internal error-pattern scanner.\n\n## What changed\n- **Fixed**: `app/.../KakaoPlacesClient.java` brace mismatch → valid `@Service` with empty-result shim.\n- **Added**: `SensitivityClamp` (Bode-lik"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES__hypernova.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": ".java with DPP stage hint.\n* [2025-10-16T07:44:30.722444] Failed to update application.yml: [Errno 2] No such file or directory: 'app/src/main/resources/application.yml'\n\n## Build error pattern scan (best-effort)\n- Build system files: gradlew, settings.gradle\n- Scanned log files: 81\n\n### error: cannot find symbol — 149 hits\n- BUILD_FIX_NOTES__79_fusion.md:\n```\nio"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": " file or directory: 'app/src/main/resources/application.yml'\n\n## Build error pattern scan (best-effort)\n- Build system files: gradlew, settings.gradle\n- Scanned log files: 81\n\n### error: cannot find symbol — 149 hits\n- BUILD_FIX_NOTES__79_fusion.md:\n```\nionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService."
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "t-effort)\n- Build system files: gradlew, settings.gradle\n- Scanned log files: 81\n\n### error: cannot find symbol — 149 hits\n- BUILD_FIX_NOTES__79_fusion.md:\n```\nionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal bu"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "find symbol — 149 hits\n- BUILD_FIX_NOTES__79_fusion.md:\n```\nionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal build-error patterns)\n- **OverrideMismatch**: `@Override` pres\n```\n- analysis/build_patterns_aggregate"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "thod fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal build-error patterns)\n- **OverrideMismatch**: `@Override` pres\n```\n- analysis/build_patterns_aggregated.json:\n```\nitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"error: package "
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "nal build-error patterns)\n- **OverrideMismatch**: `@Override` pres\n```\n- analysis/build_patterns_aggregated.json:\n```\nitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"error: package service.rag.auth does not exist\",\n        \"error: cannot find symbol class DomainWhitelist\"\n      ]\n    }\n  ]\n}\n```\n### symbol:\\s+class\\s+\\w+ — 104 hits\n- BUILD_FIX_NOTES"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "de` pres\n```\n- analysis/build_patterns_aggregated.json:\n```\nitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"error: package service.rag.auth does not exist\",\n        \"error: cannot find symbol class DomainWhitelist\"\n      ]\n    }\n  ]\n}\n```\n### symbol:\\s+class\\s+\\w+ — 104 hits\n- BUILD_FIX_NOTES__77_bm25.md:\n```\n, or record expected` around lines with "
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "lass NoriAnalyzer` at:\n  - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexHolder`\n  - `com.abandonware.ai.agent.service.rag.bm25.Bm2\n```\n- BUILD_FIX_NOTES__79_fusion.md:\n```\n error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal bu"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "donware.ai.agent.service.rag.bm25.Bm2\n```\n- BUILD_FIX_NOTES__79_fusion.md:\n```\n error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal build-error patterns)\n- **OverrideMismatch**: `@Override` present while the class \n```\n### BUILD FAILE"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "thod fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal build-error patterns)\n- **OverrideMismatch**: `@Override` present while the class \n```\n### BUILD FAILED — 17 hits\n- _assistant/diagnostics/build_error_patterns.json:\n```\ns not exist\",\n    \"li"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": ";\n               ^\n  required: no arguments\n  found:    Analyzer,NaverSearchService,int,QueryContextPreprocessor,SmartQueryPlanner\n  reason: actual and formal argument lis\n```\n### error: method does not override or implement a method from a supertype — 9 hits\n- BUILD_FIX_NOTES__79_fusion.md:\n```\n# Build Fix Notes — src111_merge15 (79)\n\n## Symptoms\n- `Bm25LocalRet"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "t override or implement a method from a supertype — 9 hits\n- BUILD_FIX_NOTES__79_fusion.md:\n```\n# Build Fix Notes — src111_merge15 (79)\n\n## Symptoms\n- `Bm25LocalRetriever.java:26: error: method does not override or implement a method from a supertype`\n- `FusionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionServ"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "Build Fix Notes — src111_merge15 (79)\n\n## Symptoms\n- `Bm25LocalRetriever.java:26: error: method does not override or implement a method from a supertype`\n- `FusionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.j\n```\n- tools/Matrixtxt.snapshot.txt:\n```\nlass ChatApiController\nC:\\AbandonWare\\demo-1\\demo-1\\"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "\n- `FusionService.j\n```\n- tools/Matrixtxt.snapshot.txt:\n```\nlass ChatApiController\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\service\\impl\\ChatServiceImpl.java:23: error: method does not override or implement a method from a supertype\n    @Override\n    ^\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\service\\impl\\ChatServiceImpl.java:2"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "not override or implement a method from a supertype\n    @Override\n    ^\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\service\\impl\\ChatServiceImpl.java:25: er\n```\n### error: package [\\w\\.]+ does not exist — 4 hits\n- analysis/build_patterns_aggregated.json:\n```\nmainWhitelist\",\n        \"replace whitelist.isAllowed -> whitelist.isOfficial\"\n      ],\n    "
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "ist — 4 hits\n- analysis/build_patterns_aggregated.json:\n```\nmainWhitelist\",\n        \"replace whitelist.isAllowed -> whitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"error: package service.rag.auth does not exist\",\n        \"error: cannot find symbol class DomainWhitelist\"\n      ]\n    }\n  ]\n}\n```\n- build_error_patterns_summary.json:\n```\nms.service\\nC"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "`\nmainWhitelist\",\n        \"replace whitelist.isAllowed -> whitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"error: package service.rag.auth does not exist\",\n        \"error: cannot find symbol class DomainWhitelist\"\n      ]\n    }\n  ]\n}\n```\n- build_error_patterns_summary.json:\n```\nms.service\\nC:\\\\AbandonWare\\\\demo-1\\\\demo-1\\\\src\\\\main\\\\java\\\\com\\\\exam"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": " ]\n    }\n  ]\n}\n```\n- build_error_patterns_summary.json:\n```\nms.service\\nC:\\\\AbandonWare\\\\demo-1\\\\demo-1\\\\src\\\\main\\\\java\\\\com\\\\example\\\\lms\\\\service\\\\impl\\\\ChatServiceImpl.java:8: error: package com.example.lms.service.ChatService does not exist\\nimport com.example.lms.service.ChatService.ChatResult;\"\n  ],\n  \"unreachable_statement_finally\": [\n    \"Unreachable sta"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": " com.example.lms.service.ChatService does not exist\\nimport com.example.lms.service.ChatService.ChatResult;\"\n  ],\n  \"unreachable_statement_finally\": [\n    \"Unreachable sta\n```\n### error: incompatible types: — 3 hits\n- tools/Matrixtxt.snapshot.txt:\n```\npe\n    @Override\n    ^\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\service\\impl\\ChatServiceImpl.ja"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "patible types: — 3 hits\n- tools/Matrixtxt.snapshot.txt:\n```\npe\n    @Override\n    ^\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\service\\impl\\ChatServiceImpl.java:25: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResu\n```\n- tools/Matrixtxt.snapshot.txt:\n``"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "hatService.ChatResu\n```\n- tools/Matrixtxt.snapshot.txt:\n```\npe\n    @Override\n    ^\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\service\\impl\\ChatServiceImpl.java:31: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResu\n```\n"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "r directory: 'app/src/main/resources/application.yml'\n\n## Build error pattern scan (best-effort)\n- Build system files: gradlew, settings.gradle\n- Scanned log files: 81\n\n### error: cannot find symbol — 149 hits\n- BUILD_FIX_NOTES__79_fusion.md:\n```\nionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cann"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "mbol — 149 hits\n- BUILD_FIX_NOTES__79_fusion.md:\n```\nionService.java:36: error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal build-error patterns)\n- **OverrideMismatch**: `@Override` pres\n```\n- analysis/build_patterns_aggregated.json:\n```\nitelist."
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "s\n```\n- analysis/build_patterns_aggregated.json:\n```\nitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"error: package service.rag.auth does not exist\",\n        \"error: cannot find symbol class DomainWhitelist\"\n      ]\n    }\n  ]\n}\n```\n### symbol:\\s+class\\s+\\w+ — 104 hits\n- BUILD_FIX_NOTES__77_bm25.md:\n```\n, or record expected` around lines with `} catch (Throwable "
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "### symbol:\\s+class\\s+\\w+ — 104 hits\n- BUILD_FIX_NOTES__77_bm25.md:\n```\n, or record expected` around lines with `} catch (Throwable ignore) { /* analyzer not on classpath */ }`\n- `cannot find symbol: class NoriAnalyzer` at:\n  - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexHolder`\n  - `com.abandonware.ai.agent.service.rag.bm25.Bm2\n```\n- BUILD_FIX_NOTES__79_fusion.md:\n``"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": ".ai.agent.service.rag.bm25.Bm2\n```\n- BUILD_FIX_NOTES__79_fusion.md:\n```\n error: method fuse in class WeightedRRF cannot be applied to given types`\n- `FusionService.java:40: error: cannot find symbol: class SearchResult`\n\n## Root causes (mapped to internal build-error patterns)\n- **OverrideMismatch**: `@Override` present while the class \n```\n### BUILD FAILED — 17 hits\n- _assis"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "exist\"\n  },\n  {\n    \"file\": \"src/fix-and-build.ps1\",\n    \"pattern\": \"BUILD FAILED\",\n    \"line\": \"BUILD FAILED\"\n  },\n  {\n    \"file\": \"tools/Matrixtxt.snapshot.txt\",\n    \"pattern\": \"cannot find symbol\",\n\n```\n- _assistant/diagnostics/build_error_patterns.json:\n```\nackage lombok does not exist\"\n  },\n  {\n    \"file\": \"src/fix-and-build.ps1\",\n    \"pattern\": \"BUILD FAILED\",\n    \"line"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "exist\"\n  },\n  {\n    \"file\": \"src/fix-and-build.ps1\",\n    \"pattern\": \"BUILD FAILED\",\n    \"line\": \"BUILD FAILED\"\n  },\n  {\n    \"file\": \"tools/Matrixtxt.snapshot.txt\",\n    \"pattern\": \"cannot find symbol\",\n    \"line\": \"cannot find sym\n```\n### found:\\s+[\\w<>\\[\\],\\s]+ — 11 hits\n- BUILD_FIX_NOTES.md:\n```\nvice,MemoryHandler,MemoryWriteInterceptor,LearningWriteInterceptor,UnderstandAnd"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "hitelist\",\n        \"replace whitelist.isAllowed -> whitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"error: package service.rag.auth does not exist\",\n        \"error: cannot find symbol class DomainWhitelist\"\n      ]\n    }\n  ]\n}\n```\n- build_error_patterns_summary.json:\n```\nms.service\\nC:\\\\AbandonWare\\\\demo-1\\\\demo-1\\\\src\\\\main\\\\java\\\\com\\\\example\\\\lms\\\\service\\\\i"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "ld-error patterns)\n- **OverrideMismatch**: `@Override` pres\n```\n- analysis/build_patterns_aggregated.json:\n```\nitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"error: package service.rag.auth does not exist\",\n        \"error: cannot find symbol class DomainWhitelist\"\n      ]\n    }\n  ]\n}\n```\n### symbol:\\s+class\\s+\\w+ — 104 hits\n- BUILD_FIX_NOTES__77_bm25.md:\n```\n, or record expected` a"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "rns)\n- **OverrideMismatch**: `@Override` present while the class \n```\n### BUILD FAILED — 17 hits\n- _assistant/diagnostics/build_error_patterns.json:\n```\ns not exist\",\n    \"line\": \"package lombok does not exist\"\n  },\n  {\n    \"file\": \"src/fix-and-build.ps1\",\n    \"pattern\": \"BUILD FAILED\",\n    \"line\": \"BUILD FAILED\"\n  },\n  {\n    \"file\": \"tools/Matrixtxt.snapshot.txt\",\n    \"pattern\": \"canno"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "rride or implement a method from a supertype\n    @Override\n    ^\nC:\\AbandonWare\\demo-1\\demo-1\\src\\main\\java\\com\\example\\lms\\service\\impl\\ChatServiceImpl.java:25: er\n```\n### error: package [\\w\\.]+ does not exist — 4 hits\n- analysis/build_patterns_aggregated.json:\n```\nmainWhitelist\",\n        \"replace whitelist.isAllowed -> whitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"err"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": " hits\n- analysis/build_patterns_aggregated.json:\n```\nmainWhitelist\",\n        \"replace whitelist.isAllowed -> whitelist.isOfficial\"\n      ],\n      \"sample_error\": [\n        \"error: package service.rag.auth does not exist\",\n        \"error: cannot find symbol class DomainWhitelist\"\n      ]\n    }\n  ]\n}\n```\n- build_error_patterns_summary.json:\n```\nms.service\\nC:\\\\AbandonWare\\\\demo-1\\\\demo-1\\\\src\\\\main"
    },
    {
      "pattern": "package .* does not exist",
      "snippet": "}\n  ]\n}\n```\n- build_error_patterns_summary.json:\n```\nms.service\\nC:\\\\AbandonWare\\\\demo-1\\\\demo-1\\\\src\\\\main\\\\java\\\\com\\\\example\\\\lms\\\\service\\\\impl\\\\ChatServiceImpl.java:8: error: package com.example.lms.service.ChatService does not exist\\nimport com.example.lms.service.ChatService.ChatResult;\"\n  ],\n  \"unreachable_statement_finally\": [\n    \"Unreachable sta\n```\n### error: incompatible types: — 3 hits\n- tools/Matrixt"
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_NOTES.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "align with call sites returning a `List` instead of a `ContextSlice`.\n\n- Fixed missing parenthesis in `RuleBreakInterceptor` call to `HmacSigner.verifyAndDecode`.\n\n\n## Found build-error pattern logs\n\n- `BUILD_FIX_NOTES.md` (contains compile error traces)\n\n- `BUILD_FIX_REPORT.md` (contains compile error traces)\n\n- `BUILD_FIX_NOTES__79_fusion.md` (contains compile "
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "tSlice`.\n\n- Fixed missing parenthesis in `RuleBreakInterceptor` call to `HmacSigner.verifyAndDecode`.\n\n\n## Found build-error pattern logs\n\n- `BUILD_FIX_NOTES.md` (contains compile error traces)\n\n- `BUILD_FIX_REPORT.md` (contains compile error traces)\n\n- `BUILD_FIX_NOTES__79_fusion.md` (contains compile error traces)\n\n- `build_error_patterns_summary.json` (contain"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "eptor` call to `HmacSigner.verifyAndDecode`.\n\n\n## Found build-error pattern logs\n\n- `BUILD_FIX_NOTES.md` (contains compile error traces)\n\n- `BUILD_FIX_REPORT.md` (contains compile error traces)\n\n- `BUILD_FIX_NOTES__79_fusion.md` (contains compile error traces)\n\n- `build_error_patterns_summary.json` (contains compile error traces)\n\n- `PATCH_REPORT__nine_tile_alias"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": " pattern logs\n\n- `BUILD_FIX_NOTES.md` (contains compile error traces)\n\n- `BUILD_FIX_REPORT.md` (contains compile error traces)\n\n- `BUILD_FIX_NOTES__79_fusion.md` (contains compile error traces)\n\n- `build_error_patterns_summary.json` (contains compile error traces)\n\n- `PATCH_REPORT__nine_tile_alias_corrector.json` (contains compile error traces)\n\n- `HYPERNOVA_PATC"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "- `BUILD_FIX_REPORT.md` (contains compile error traces)\n\n- `BUILD_FIX_NOTES__79_fusion.md` (contains compile error traces)\n\n- `build_error_patterns_summary.json` (contains compile error traces)\n\n- `PATCH_REPORT__nine_tile_alias_corrector.json` (contains compile error traces)\n\n- `HYPERNOVA_PATCH_REPORT.json` (contains compile error traces)\n\n- `scripts/analyze_buil"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "sion.md` (contains compile error traces)\n\n- `build_error_patterns_summary.json` (contains compile error traces)\n\n- `PATCH_REPORT__nine_tile_alias_corrector.json` (contains compile error traces)\n\n- `HYPERNOVA_PATCH_REPORT.json` (contains compile error traces)\n\n- `scripts/analyze_build_output.py` (contains compile error traces)\n\n\n---\nSee AUTO_PATTERN_APPLY_REPORT.m"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "_summary.json` (contains compile error traces)\n\n- `PATCH_REPORT__nine_tile_alias_corrector.json` (contains compile error traces)\n\n- `HYPERNOVA_PATCH_REPORT.json` (contains compile error traces)\n\n- `scripts/analyze_build_output.py` (contains compile error traces)\n\n\n---\nSee AUTO_PATTERN_APPLY_REPORT.md for the pattern scan and fix mapping."
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "_tile_alias_corrector.json` (contains compile error traces)\n\n- `HYPERNOVA_PATCH_REPORT.json` (contains compile error traces)\n\n- `scripts/analyze_build_output.py` (contains compile error traces)\n\n\n---\nSee AUTO_PATTERN_APPLY_REPORT.md for the pattern scan and fix mapping."
    }
  ],
  "/mnt/data/work_src111_smerge15/AUTO_PATTERN_APPLY_REPORT.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# AUTO_PATTERN_APPLY_REPORT\n\nScanned build-error artifacts and applied deterministic fixes.\n\n\n## Pattern: cannot_find_symbol_NovaNextFusionService\n- Fix: Added local stub com.nova.protocol.fusion.NovaNextFusionService with neste"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "nt)` (legacy SelfAsk).\n- Occurrences: 3 (FusionService.java, RrfFusion.java, SelfAskPlanner.java).\n\n## Pattern: cannot_find_symbol__OnnxCrossEncoderReranker.rerankTopK\n- Symptom: `cannot find symbol rerankTopK(List<ContextSlice>,int)`.\n- Fix: Added convenience overload `OnnxCrossEncoderReranker#rerankTopK(List<ContextSlice>, int)` that sorts by `ContextSlice.getScore()` with "
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_src111_mergsae15.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# PATCH NOTES — src111_mergsae15\n\nDate (UTC): 2025-10-16T11:46:54.538787Z\n\nThis patch applies the Hyper‑Nova fusion refinements and repairs common build error patterns detected in prior logs.\n\n## What changed\n\n1) **Hyper‑Nova components (app module)**\n   - `app/src/main/java/com/nova/protocol/fusion/CvarAggregator.java`\n     * CVaR@α up"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "vice`.)\n\n2) **Bridge remains the same**\n   - `RrfHypernovaBridge` + `NovaNextConfig` gate Hyper‑Nova via `spring.profiles.active=novanext` or `nova.next.enabled=true`.\n\n3) **Build error pattern fixes (IllegalStartOfType / ClassOrInterfaceExpected)**\n   - Rewrote *minimal, compilable* stubs for duplicated legacy `WeightedRRF` classes that contained stray `...` tok"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_src111_mergswe15.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# PATCH NOTES — src111_mergswe15\n\n**Goal:** Fix Gradle compile errors from `WeightedRRF` signature mismatches and missing method on `OnnxCrossEncoderReranker`. Also record the error patterns into the in-repo build-pattern memory.\n\n## What I changed\n\n### 1) WeightedRRF — rich overloads added\n- **File:** `src/main/java/com/abandonware/ai/service/rag/fusion/Weig"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_src111_mergswae15.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "mergswae15\n\nApplied A1..A6 quick fixes: root build.gradle, app deps, secrets externalization, application.yml normalization, plan gating baseline, duplicate-class check.\n\n## Build error pattern summary (from BUILD_LOG.txt)\n- MISSING_SYMBOL: 0 hit(s)\n- DUPLICATE_CLASS: 0 hit(s)\n- ILLEGAL_START: 0 hit(s)\n- CLASS_EXPECTED: 0 hit(s)\n- PACKAGE_NOT_FOUND: 0 hit(s)\n\n## "
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_APPLIED__merge_legacy_and_fix_build.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "Patched at 2025-10-16T22:15:26.634015Z\n\n# Build Error Pattern Summary (auto)\n- Duplicate YAML key `retrieval:` in `app/src/main/resources/application.yml` → **fixed** by collapsing to a single block.\n- Missing Gradle module include f"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_src111_mersage15.md": [
    {
      "pattern": "cannot find symbol",
      "snippet": "filters.domain-allowlist.*`). No code change was necessary beyond ensuring property accessors compile. fileciteturn0file1\n\n### Suggested follow-ups (optional)\n- If any further “cannot find symbol” remain, run with `-Xdiags:verbose -Xlint:deprecation,unchecked -Xmaxerrs 2000` and feed the next 100–200 lines back into the pattern scanner.\n- If your build still trips on `Dupl"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_APPLIED__srswc111_merge15.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": ".java` (rewritten)\n- `app/src/main/java/com/nova/protocol/alloc/RiskKAllocator.java` (new overload)\n- `app/src/main/java/com/example/lms/mcp/McpSessionRouter.java` (new)\n\n## Build error patterns considered\nBased on `analysis/build_patterns_aggregated.json`: Duplicate class, Missing symbol/import, Override mismatch, illegal start of type, wrong file/name, and rege"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "on `analysis/build_patterns_aggregated.json`: Duplicate class, Missing symbol/import, Override mismatch, illegal start of type, wrong file/name, and regex-escape suspects. Wrapper error in `BUILD_LOG.txt` resolved by existing robust `gradlew` shim.\n\n"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_REPORT.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "anker.java\n- lms-core/src/main/java/com/abandonware/ai/service/onnx/OnnxRuntimeService.java\n- app/src/main/resources/application.yml\n\n## Gradle toolchain patched\n- (no change)\n\n## Error pattern summary (repo logs + CSV)\n- cannot_find_symbol: 57\n- package_does_not_exist: 21\n- class_not_found: 3\n- incompatible_types: 10\n- spring_context: 35\n- onnx_runtime: 108\n- ja"
    }
  ],
  "/mnt/data/work_src111_smerge15/BUILD_ERROR__latest.txt": [
    {
      "pattern": "cannot find symbol",
      "snippet": "[RAW BUILD LOG SNIPPET — 2025-10-17]\nTask :compileJava FAILED\n... illegal escape character at regex tokens like \\p{L}, \\p{Nd}, \\s, \\- ...\n... cannot find symbol: class Bm25LocalIndex in Bm25LocalRetriever ...\n"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_src111_merge15__auto.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# PATCH NOTES — src111_merge15 (auto)\n\nApplied targeted build-fix and A–E bundle essentials:\n\n1) **Build wrapper error** — confirmed wrapper present and self-healing `gradlew` does not call `gradlew-real`.\n2) **Fix illegal-start/class-expected** — corrected brace structure and stray tokens in:\n   "
    }
  ],
  "/mnt/data/work_src111_smerge15/CHANGELOG_src111_merge15.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "iController#askAsync` to match `JobService` API and run asynchronously using `CompletableFuture`.\n- Repaired `ProbeConfig` sample pipeline to remove `...` artifacts.\n- Added build error patterns registry at app/resources/dev/build/ERROR_PATTERNS.json.\n"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_src111_merge15_job_soak_fix.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "eAsync(...)`.\n- `SoakConfig` referenced `DefaultSoakQueryProvider` and `DefaultSoakTestService` which were previously package‑private and not visible across packages.\n\n## Affected error signatures (before)\n- `method enqueue in interface JobService cannot be applied to given types`\n- `cannot find symbol: method executeAsync(...)`\n- `cannot find symbol: class InMem"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "faultSoakTestService`\n\n## After\n- All symbols are provided. The Job API matches controller usage; default soak classes are public and bean‑creatable.\n\n## Notes\n- The in‑repo build‑error pattern scanner flagged prior incidents such as regex escape issues and missing symbols; none of those touch the modified files. See `BUILD_ERROR_PATTERNS.json` and `BUILD_PATTERN"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "e previously package‑private and not visible across packages.\n\n## Affected error signatures (before)\n- `method enqueue in interface JobService cannot be applied to given types`\n- `cannot find symbol: method executeAsync(...)`\n- `cannot find symbol: class InMemoryJobService`\n- `cannot find symbol: class DefaultSoakQueryProvider`\n- `cannot find symbol: class DefaultSoakTestServ"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "ss packages.\n\n## Affected error signatures (before)\n- `method enqueue in interface JobService cannot be applied to given types`\n- `cannot find symbol: method executeAsync(...)`\n- `cannot find symbol: class InMemoryJobService`\n- `cannot find symbol: class DefaultSoakQueryProvider`\n- `cannot find symbol: class DefaultSoakTestService`\n\n## After\n- All symbols are provided. The Jo"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "e)\n- `method enqueue in interface JobService cannot be applied to given types`\n- `cannot find symbol: method executeAsync(...)`\n- `cannot find symbol: class InMemoryJobService`\n- `cannot find symbol: class DefaultSoakQueryProvider`\n- `cannot find symbol: class DefaultSoakTestService`\n\n## After\n- All symbols are provided. The Job API matches controller usage; default soak clas"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "applied to given types`\n- `cannot find symbol: method executeAsync(...)`\n- `cannot find symbol: class InMemoryJobService`\n- `cannot find symbol: class DefaultSoakQueryProvider`\n- `cannot find symbol: class DefaultSoakTestService`\n\n## After\n- All symbols are provided. The Job API matches controller usage; default soak classes are public and bean‑creatable.\n\n## Notes\n- The in‑r"
    }
  ],
  "/mnt/data/work_src111_smerge15/PATCH_NOTES_buildfix_w15.md": [
    {
      "pattern": "cannot find symbol",
      "snippet": "\n   - Removed malformed inline usage `public @org.springframework.stereotype.Component`.\n   - Re-inserted a single annotation line above `class DynamicRetrievalHandlerChain`.\n\n3) `cannot find symbol: variable log` in `ChatApiController`\n   - Added SLF4J logger field:\n     `private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatApiController.class);`"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "calls to use no-arg constructors to match current class signatures:\n     - `new AnalyzeWebSearchRetriever()`\n     - `new com.example.lms.service.rag.handler.AnalyzeHandler()`\n\n5) `cannot find symbol ... service.ocr.OcrService / service.EmbeddingStoreManager`\n   - Corrected to detected packages via source scan:\n     - `{ 'OcrService': 'com.abandonware.ai.service.ocr' }`\n     -"
    },
    {
      "pattern": "cannot find symbol",
      "snippet": "Corrected to detected packages via source scan:\n     - `{ 'OcrService': 'com.abandonware.ai.service.ocr' }`\n     - `{ 'EmbeddingStoreManager': 'com.abandonware.ai.service' }`\n\n6) `cannot find symbol: method emit(...)` when `sse` typed as `Object`\n   - Converted direct calls to reflection:\n     - `sse.getClass().getMethod(\"emit\", String.class, Object.class, Map.class).invoke(."
    }
  ]
}

Note:
- 이 리포트는 정적 문자열 스캔이며, 실제 컴파일러 출력과 1:1 일치하지 않을 수 있습니다.
- 본 패치에서는 DppDiversityReranker API 미스매치로 인한 컴파일 에러를 우선 해소했습니다.
