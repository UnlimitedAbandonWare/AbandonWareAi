# Build Error Pattern Mining (static scan)
Date: 2025-10-27T21:35:19.329064Z

Total pattern hits: 5513
Top patterns:
[
  [
    "\\bERROR\\b",
    2850
  ],
  [
    "cannot find symbol",
    2251
  ],
  [
    "package .* does not exist",
    137
  ],
  [
    "IllegalStateException",
    89
  ],
  [
    "FAILURE: Build failed",
    87
  ],
  [
    "\\b[Ww]ARNING\\b",
    58
  ],
  [
    "Compilation failed",
    34
  ],
  [
    "ClassNotFoundException",
    4
  ],
  [
    "No such file or directory",
    3
  ]
]

Sample files & contexts:
{
  "/mnt/data/work_src111_merge15/BUILD_FIX_NOTES.md": [
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
  "/mnt/data/work_src111_merge15/PATCH_NOTES.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "NineTileAliasCorrector.java\n\nThis preserves `required=false` semantics if present.\n\n## Why this resolves the boot failure\n\nThe hard failure in your log is the YAML-to-POJO mapping error on `alias:`. Removing that key (or supporting it in the POJO) lets the context refresh complete. The `@Qualifier` change is a safety patch that eliminates an additional startup ha"
    }
  ],
  "/mnt/data/work_src111_merge15/PATCH_NOTES_src96_to_src97.md": [
    {
      "pattern": "IllegalStateException",
      "snippet": "# Patch Notes: src96 → src97\n\n**Issue fixed**: Application failed to start with\n`IllegalStateException: Manifest not found: configs/models.manifest.yaml` in `ModelManifestConfig`.\n\n**What changed**\n1) Copied project-level manifest `src_91/configs/models.manifest.yaml` into the appl"
    }
  ],
  "/mnt/data/work_src111_merge15/BUILD_PATTERN_SUMMARY.md": [
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
  "/mnt/data/work_src111_merge15/BUILD_PATTERN_RUN.md": [
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
  "/mnt/data/work_src111_merge15/BUILD_ERROR_PATTERN_SCAN.md": [
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
      "snippet": "; plugin activates only when explicitly enabled and configured.\n\n\n# BUILD ERROR PATTERN SCAN (from cfvm-raw/BuildLogSlotExtractor)\n\nDetected patterns:\n- PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package com.networknt.schema does not exist`, `package org.springframework.data.redis.core does not exist`, `package org.springframework.kafka.* does not exist`, `package io.micrometer.* does not exist`, `package io.opentelemetry.* does not exist`, `package org.apache.lucene.analysis.ko does not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSc"
    }
  ],
  "/mnt/data/work_src111_merge15/BUILD_PATTERN_REPORT.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# Build Error Pattern Report — src111_merge15a → src111_merge15\n\n**Date:** 2025-10-12\n\n## Inputs\n- Source: `BUILD_LOG.txt` (scanned), developer-provided Gradle output (pasted in request)\n\n## Ex"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "ctor.java`  \n  Recognizes patterns: `MISSING_SYMBOL`, `DUPLICATE_CLASS`, `ILLEGAL_START (type)`, `CLASS_EXPECTED`, `PACKAGE_NOT_FOUND`, `OVERRIDE_MISMATCH`.\n\n## Findings\n- Current error: **`illegal start of expression`** → *not matched* by existing `ILLEGAL_START` (which expects `type`).  \n  **Root cause located:** missing `}` closing brace after constructor in `"
    }
  ],
  "/mnt/data/work_src111_merge15/BUILD_FIX_REPORT.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "# BUILD FIX REPORT — src111_merge15\n\nDate: 2025-10-12T22:30:31.495902Z\n\nDetected common error patterns from in-repo scan:\n\n- PACKAGE_NOT_FOUND: `org.apache.lucene.analysis.ko.*`, `com.networknt.schema.*`\n\n- MISSING_SYMBOL: `NoriTokenizer`, `JsonSchema`, `PlanLoader`\n\n\nAppl"
    }
  ],
  "/mnt/data/work_src111_merge15/BUILD_FIX_NOTES__77_bm25.md": [
    {
      "pattern": "cannot find symbol",
      "snippet": "otes — src111_merge15 (77)\n\n## Errors observed\n- `class, interface, enum, or record expected` around lines with `} catch (Throwable ignore) { /* analyzer not on classpath */ }`\n- `cannot find symbol: class NoriAnalyzer` at:\n  - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexHolder`\n  - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexService`\n\n## Root causes (matched "
    }
  ],
  "/mnt/data/work_src111_merge15/BUILD_FIX_NOTES__79_fusion.md": [
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
  "/mnt/data/work_src111_merge15/PATCH_NOTES_src111_merge15_onnx_fix.md": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": " `:compileJava` failed with multiple errors from `OnnxScoreUtil.java`:\n\n- `illegal start of expression`\n- `class, interface, enum, or record expected`\n\n**Root Cause (from internal error-pattern scan)**  \nPattern `ILLEGAL_START_EXPRESSION` matched: static methods were declared **inside** a still-open private constructor (`private OnnxScoreUtil() {`), so the method"
    }
  ],
  "/mnt/data/work_src111_merge15/build_error_patterns_summary.json": [
    {
      "pattern": "\\bERROR\\b",
      "snippet": "nd symbol”)와 일치.\\n\",\n    \"                                          ^\\nC:\\\\AbandonWare\\\\demo-1\\\\demo-1\\\\src\\\\main\\\\java\\\\com\\\\example\\\\lms\\\\service\\\\impl\\\\ChatServiceImpl.java:21: error: cannot find symbol\\npublic class ChatServiceImpl implements ChatService {\",\n    \"                                        ^\\nC:\\\\AbandonWare\\\\demo-1\\\\demo-1\\\\src\\\\main\\\\java\\\\com\\"
    },
    {
      "pattern": "\\bERROR\\b",
      "snippet": "pl implements ChatService
