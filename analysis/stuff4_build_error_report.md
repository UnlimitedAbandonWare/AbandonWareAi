# Build Error Patterns — Report

- **JavacCannotFindSymbol**: 463
- **GradleBuildFailed**: 207
- **JavacClassInterfaceExpected**: 119
- **JavacIllegalStartOfType**: 51
- **SpringUnsatisfiedDependency**: 42
- **SpringApplicationRunFailed**: 30
- **JavacDuplicateClass**: 24
- **JavacPackageDoesNotExist**: 15
- **JavacIncompatibleTypes**: 9
- **SpringConfigBindingFailed**: 3
- **JavacMissingSemicolon**: 3

---

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/BUILD_FIX_NOTES.md

### JavacIllegalStartOfType (1)

> ## src111_merge15 — Fix #001 (2025-10-12) **Error Pattern:** `illegal start of expression` (unmatched constructor brace) - **Detector:** cfvm-raw<path> (pattern `ILLEGAL_START` didn't match 'expression', only 'type') - **Incident:** QdrantClient.java line <n> (`public List... search(...)`) declared before closing constructor block.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/BUILD_PATTERN_SUMMARY.md

### JavacDuplicateClass (1)

> - `cannot find symbol` → **MissingSymbol** - `duplicate class` → **DuplicateClass** - `illegal start of type` → **IllegalStartOfType** - `class, interface, enum, or record expected` → **ClassOrInterfaceExpected**

### JavacIllegalStartOfType (2)

> - `cannot find symbol` → **MissingSymbol** - `duplicate class` → **DuplicateClass** - `illegal start of type` → **IllegalStartOfType** - `class, interface, enum, or record expected` → **ClassOrInterfaceExpected** - `package ... does not exist` → **PackageNotFound**

> **New pattern (auto-appended — 2025-10-24 00:<n>:53Z):** - `illegal start of expression` caused by `; * authorityDecayMultiplier` after a closed expression. **Fix:** fold the multiply into the same expression: `double score = (sim + subjectTerm + genericTerm + ruleDelta + (synergyBonus * synergyWeight)) * authorityDecayMultiplier;`

### JavacClassInterfaceExpected (1)

> - `duplicate class` → **DuplicateClass** - `illegal start of type` → **IllegalStartOfType** - `class, interface, enum, or record expected` → **ClassOrInterfaceExpected** - `package ... does not exist` → **PackageNotFound**

### JavacPackageDoesNotExist (1)

> **Symptom** ``` error: package jdk.incubator.vector does not exist error: Preview features are not enabled for unit ... ```

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/BUILD_PATTERN_RUN.md

### SpringUnsatisfiedDependency (1)

> ## [2025-10-21 21:<n>:29 UTC] Pattern detected & resolved — Missing bean due to feature flag **Signature:** `UnsatisfiedDependencyException` → requires `OpenAiImageService` **Context:** `ImageJobService` ctor (param #1) **Action:** Added `@ConditionalOnBean(OpenAiImageService.class)` to `ImageJobService`.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/BUILD_ERROR_PATTERN_SCAN.md

### SpringUnsatisfiedDependency (1)

> ## Update — 2025‑10‑22 bootRun failure (Spring Boot) Pattern: CONFIG_PROPERTIES_BINDING_VALIDATION_FAILED → UnsatisfiedDependencyException during controller creation due to `@Validated @ConfigurationProperties` record enforcing `@NotBlank` on missing property. Signature (exact):

### SpringConfigBindingFailed (1)

> Signature (exact): ``` Binding to target com.example.lms.plugin.image.OpenAiImageProperties failed: Property: openai.image.endpoint Value: "null"

### JavacIllegalStartOfType (3)

> Files: - <path>:12 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression

> - <path>:12 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression

> - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression - <path>:40 — class, interface, enum, or record expected

### JavacClassInterfaceExpected (5)

> Files: - <path>:12 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected

> - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression - <path>:40 — class, interface, enum, or record expected

> - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression - <path>:40 — class, interface, enum, or record expected Remedy applied:

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/BUILD_PATTERN_REPORT.md

### JavacIllegalStartOfType (1)

> ## Findings - Current error: **`illegal start of expression`** → *not matched* by existing `ILLEGAL_START` (which expects `type`). **Root cause located:** missing `}` closing brace after constructor in `QdrantClient.java`.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/BUILD_FIX_NOTES__77_bm25.md

### JavacClassInterfaceExpected (1)

> ## Errors observed - `class, interface, enum, or record expected` around lines with `} catch (Throwable ignore) { /* analyzer not on classpath */ }` - `cannot find symbol: class NoriAnalyzer` at: - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexHolder`

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/BUILD_FIX_NOTES__79_fusion.md

### JavacCannotFindSymbol (1)

> - `Bm25LocalRetriever.java:<n>: error: method does not override or implement a method from a supertype` - `FusionService.java:<n>: error: method fuse in class WeightedRRF cannot be applied to given types` - `FusionService.java:<n>: error: cannot find symbol: class SearchResult` ## Root causes (mapped to internal build-error patterns)

> - `FusionService.java:<n>: error: cannot find symbol: class SearchResult` ## Root causes (mapped to internal build-error patterns) - **OverrideMismatch**: `@Override` present while the class does not implement/extend a supertype that declares `retrieve(String,int)`.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/BUILD_FIX__onnx_cross_encoder.md

### JavacIllegalStartOfType (1)

> **Problem** `illegal start of expression`/`illegal start of type` around `resolveBudgetMs(...)` in multiple `OnnxCrossEncoderReranker.java` variants. Root cause: stray tokens (`...`) and brace imbalance from partial snippets caused methods to appear inside expression contexts.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/MERGE_NOTE__mergerx15.txt

### JavacClassInterfaceExpected (1)

> Fix: compileJava failures (illegal start/class expected) in com.abandonwareai/* Changes:

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/build_error_patterns_summary.json

### JavacCannotFindSymbol (43)

> "\ncannot find symbol: ChatApiController.java 15, TasksApiController.java 15, ChatWebSocketHandler.java 15\n", "\ncannot find symbol: class ChatService가 컨트롤러<path> 연쇄적으로 발생\n(Lombok @RequiredArgsConstructor가 붙은 필드 주입 지점에서 타입을 못 찾으니 애노테이션 라인까지 오류가 전파됨)", "\nerror: cannot find symbol\nimport com.example.lms.service.ChatService;", "\nmissing_symbol → MissingSymbol → \"Cannot find symbol<path> "\n다 봤어요. 이번 빌드 실패의 핵심 원인은 모듈 내에서 com.example.lms.service.ChatService 타입 자체가 존재하지 않아(파일 부재) 이를 참조하는 컨트롤러<path> 연쇄적으로 cannot find symbol: ChatService가 발생한 것입니다.\n",

> "\n이 중 “cannot find symbol”의 상당수는 패키지 이동이나 리팩토링 후 import 경로가 낡은 경우였습니다. 다음 자동 치환을 적용해 관련 오류를 줄였습니다(7개 파일 수정):\n", "\n패턴 일치 → 원인 지목: 내부 “에러패턴 메모리”에서 missing_symbol 규칙이 이번 로그(“Cannot find symbol”)와 일치.\n", " ^\n<path>:<n>: error: cannot find symbol\npublic class ChatServiceImpl implements ChatService {", " ^\n<path>:<n>: error: cannot find symbol\n log.debug(\"Failed to attach uploaded files to new session: {}\", ex.toString());", " ^\n<path>:<n>: error: cannot find symbol\n List<Object> steps = chain.getSteps(); // 체인 내부 컬렉션을 노출하는 간단 API가 있다고 가정",

> "\n패턴 일치 → 원인 지목: 내부 “에러패턴 메모리”에서 missing_symbol 규칙이 이번 로그(“Cannot find symbol”)와 일치.\n", " ^\n<path>:<n>: error: cannot find symbol\npublic class ChatServiceImpl implements ChatService {", " ^\n<path>:<n>: error: cannot find symbol\n log.debug(\"Failed to attach uploaded files to new session: {}\", ex.toString());", " ^\n<path>:<n>: error: cannot find symbol\n List<Object> steps = chain.getSteps(); // 체인 내부 컬렉션을 노출하는 간단 API가 있다고 가정", " ^\n<path>:<n>: error: cannot find symbol\n private static final Logger log = LoggerFactory.getLogger(ChatService.class);",

### GradleBuildFailed (2)

> "1) 빌드오류 패턴 스캔 결과 → 원인 규정\n저장소의 tools/build_memory_config.yaml에는 정규식 기반 오류 사전이 들어있고, 그 중 missing_symbol 규칙이 \"Cannot find symbol\"을 매칭합니다. 제공해주신 로그는 이 규칙과 정확히 일치했습니다(MissingSymbol). \n", "6) 왜 이 방식이 안전한가?\n패턴 일치 검증: “Cannot find symbol” → 내부 규칙 missing_symbol로 분류해 원인(심볼 부재)을 즉시 확정. \n", "> Task :compileJava FAILED\n<path>:<n>: error: cannot find symbol\nimport com.example.lms.service.ChatService;", "^\n<path>:<n>: error: cannot find symbol\nimport com.example.lms.service.ChatService;", "cannot find symbol log (ChatApiController 등)\tLombok 미적용/중복 정의\t@Slf4j로 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석\ncannot find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가\nincompatible types: ChatOrchestrator.ChatResult -> ChatService.ChatResult\t반환타입 불일치\ttoServiceResult(...) 어댑터로 변환 후 반환",

> "class_or_record_expected": [ "\nerror: class, interface, enum, or record expected\n이는 파일의 클래스 범위 밖에 }가 더 존재할 때 흔히 발생합니다.", "> Task :compileJava FAILED\n<path>:<n>: error: class, interface, enum, or record expected\n}", "Reasoned for 12m 59s\n다 확인했습니다. 핵심 원인은 NaverProvider.java 말미에 } 가 1개 더 있어서 클래스 블록 밖으로 나간 것입니다. Gradle가 “class, interface, enum, or record expected”를 뿜는 전형적인 상황이죠. 동일 문제가 두 군데에 있었습니다.\n", "\n다 봤어요. Gradle 컴파일 에러의 직접 원인은 demo-1<path> 내부 괄호 불일치였습니다. } 가 클래스/메서드 범위를 잘못 닫아서 컴파일러가 “class, interface, enum, or record expected” 를 뿜은 상태였고, 특히 두 군데가 깨져 있었습니다.\n",

### JavacClassInterfaceExpected (6)

> "^\n<path>:<n>: error: cannot find symbol\nimport com.example.lms.service.ChatService;", "cannot find symbol log (ChatApiController 등)\tLombok 미적용/중복 정의\t@Slf4j로 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석\ncannot find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가\nincompatible types: ChatOrchestrator.ChatResult -> ChatService.ChatResult\t반환타입 불일치\ttoServiceResult(...) 어댑터로 변환 후 반환", "interface expected here (ChatServiceImpl implements ChatService)\tChatService가 클래스(레거시)로 남아있던 충돌\t레거시를 legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입\ncannot find symbol log (ChatApiController 등)\tLombok 미적용/중복 정의\t@Slf4j로 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석\ncannot find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가", "“빌드 에러 패턴 메모리” 적용 요약\n패턴 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발\n", "과거 수집 요약: build_error_patterns.csv, build_error_patterns_summary.csv, build_error_patterns_by_config.csv\n→ 이번 오류는 구성 파일의 missing_symbol 규칙과 정확히 일치합니다(“Cannot find symbol”).\n",

> ], "class_or_record_expected": [ "\nerror: class, interface, enum, or record expected\n이는 파일의 클래스 범위 밖에 }가 더 존재할 때 흔히 발생합니다.", "> Task :compileJava FAILED\n<path>:<n>: error: class, interface, enum, or record expected\n}", "Reasoned for 12m 59s\n다 확인했습니다. 핵심 원인은 NaverProvider.java 말미에 } 가 1개 더 있어서 클래스 블록 밖으로 나간 것입니다. Gradle가 “class, interface, enum, or record expected”를 뿜는 전형적인 상황이죠. 동일 문제가 두 군데에 있었습니다.\n",

> "class_or_record_expected": [ "\nerror: class, interface, enum, or record expected\n이는 파일의 클래스 범위 밖에 }가 더 존재할 때 흔히 발생합니다.", "> Task :compileJava FAILED\n<path>:<n>: error: class, interface, enum, or record expected\n}", "Reasoned for 12m 59s\n다 확인했습니다. 핵심 원인은 NaverProvider.java 말미에 } 가 1개 더 있어서 클래스 블록 밖으로 나간 것입니다. Gradle가 “class, interface, enum, or record expected”를 뿜는 전형적인 상황이죠. 동일 문제가 두 군데에 있었습니다.\n", "\n다 봤어요. Gradle 컴파일 에러의 직접 원인은 demo-1<path> 내부 괄호 불일치였습니다. } 가 클래스/메서드 범위를 잘못 닫아서 컴파일러가 “class, interface, enum, or record expected” 를 뿜은 상태였고, 특히 두 군데가 깨져 있었습니다.\n",

### JavacPackageDoesNotExist (1)

> ], "package_does_not_exist": [ " location: package com.example.lms.service\n<path>:<n>: error: package com.example.lms.service.ChatService does not exist\nimport com.example.lms.service.ChatService.ChatResult;" ], "unreachable_statement_finally": [

### JavacIllegalStartOfType (1)

> "illegal_start_of_expression": [ { "pattern": "illegal start of expression", "signature": "double score = (...) ; * authorityDecayMultiplier;", "file": "src<path>

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/BUILD_ERROR_PATTERNS.md

### JavacCannotFindSymbol (1)

> ### cannot_find_symbol — hits: 2 - <path>: — 2025-10-17] Task :compileJava FAILED ... illegal escape character at regex tokens like \p{L}, \p{Nd}, \s, \- ... ... cannot find symbol: class Bm25LocalIndex in Bm25LocalRetriever ... - <path>: :compileJava <path>:<n>: error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body( ### class_interface_expected — hits: 0

> - <path>: — 2025-10-17] Task :compileJava FAILED ... illegal escape character at regex tokens like \p{L}, \p{Nd}, \s, \- ... ... cannot find symbol: class Bm25LocalIndex in Bm25LocalRetriever ... - <path>: :compileJava <path>:<n>: error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body( ### class_interface_expected — hits: 0

> - <path>: :compileJava <path>:<n>: error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body( ### class_interface_expected — hits: 0

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/.build/error_patterns_db.json

### GradleBuildFailed (1)

> ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path> ], "gradle.dependency_resolution": [

### JavacCannotFindSymbol (1)

> ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path> ], "gradle.dependency_resolution": [

> "- Split `OcrModels.java` into `Rect.java`, `OcrSpan.java`, `OcrChunk.java` to satisfy Java's one-public-type-per-file rule.\n- Enabled Lombok annotation processing in `lms-core` (and tests) to restore getters/setters, constructors, and `@Slf4j` loggers.\n- These address the cascading 'cannot find symbol get*/set*' and 'log' errors across shared sources compiled by `lms-core`.<path> ], "gradle.build_failed": [ "3 errors<path>: Build failed with an exception.<path> What went wrong:"

> "\nDetected patterns:\n- PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package com.networknt.schema does not exist`, `package org.springframework.data.redis.core does not exist`, `package org.springframework.kafka.* does not exist`, `package io.micrometer.* does not exist`, `package io.opentelemetry.* does not exist`, `package org.apache.lucene.analysis.ko does not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanLoader<path> (No hits) DUPLICATE_CLASS / ILLEGAL_START / CLASS_EXPECTED / OVERRIDE_MISMATCH in this excerpt." ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path>

### JavacClassInterfaceExpected (1)

> ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path> ], "gradle.dependency_resolution": [

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/_assistant/diagnostics/build_error_patterns.json

### GradleBuildFailed (2)

> { "file": "src/fix-and-build.ps1", "pattern": "BUILD FAILED", "line": "BUILD FAILED" },

> "file": "src/fix-and-build.ps1", "pattern": "BUILD FAILED", "line": "BUILD FAILED" }, {

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/analysis/build_patterns_aggregated.json

### JavacDuplicateClass (3)

> }, { "pattern": "duplicate class", "code": "DuplicateClass" },

> }, { "pattern": "duplicate class: .*_abandonware_backup.*", "code": "DuplicateClassAbandonwareBackup" }

> "notes": [ "Fixed repeated modifier in SearchTrace class declaration by keeping a single 'public static final'.", "Consider checking for potential duplicate class: two NaverSearchService.java exist under the same package; ensure only one is compiled." ], "patterns": {

### JavacIllegalStartOfType (1)

> }, { "pattern": "illegal start of type", "code": "IllegalStartOfType" },

### JavacClassInterfaceExpected (1)

> }, { "pattern": "class, interface, enum, or record expected", "code": "ClassOrInterfaceExpected" },

### JavacPackageDoesNotExist (1)

> ], "sample_error": [ "error: package service.rag.auth does not exist", "error: cannot find symbol class DomainWhitelist" ]

### JavacCannotFindSymbol (1)

> "sample_error": [ "error: package service.rag.auth does not exist", "error: cannot find symbol class DomainWhitelist" ] }

> "pattern": "cannot find symbol", "code": "MissingSymbol" }, {

> "pattern": "cannot find symbol rerankTopK(List<ContextSlice>,int)", "code": "MissingMethod" }, {

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/analysis/build_fix_applied.json

### JavacDuplicateClass (1)

> }, { "pattern": "duplicate class", "code": "DuplicateClass" },

### JavacIllegalStartOfType (1)

> }, { "pattern": "illegal start of type", "code": "IllegalStartOfType" },

### JavacClassInterfaceExpected (1)

> }, { "pattern": "class, interface, enum, or record expected", "code": "ClassOrInterfaceExpected" },

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/app/resources/dev/build/ERROR_PATTERNS.json

### JavacMissingSemicolon (1)

> }, { "pattern": "unclosed string literal|<identifier> expected|';' expected", "fix": "Remove stray characters and complete the statement; watch for logging format strings." }

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/build/error-patterns.json

### JavacDuplicateClass (2)

> { "code": "DuplicateClassAbandonwareBackup", "pattern": "duplicate class: .*_abandonware_backup.*", "example": "<path>:<n>: error: duplicate class: com.abandonware.ai.addons.budget.CancelToken", "fix": [

> "code": "DuplicateClassAbandonwareBackup", "pattern": "duplicate class: .*_abandonware_backup.*", "example": "<path>:<n>: error: duplicate class: com.abandonware.ai.addons.budget.CancelToken", "fix": [ "Move src<path> -> backup/_abandonware_backup (outside source set)",

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/build-logs/2025-10-18-bootRun.log

### SpringUnsatisfiedDependency (2)

> 2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] 2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]

> 2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] 2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]

### SpringApplicationRunFailed (1)

> 2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] 2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]

### GradleBuildFailed (2)

> Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}] > Task :bootRun FAILED FAILURE: Build failed with an exception. * What went wrong:

> FAILURE: Build failed with an exception. * What went wrong: Execution failed for task ':bootRun'. > Process 'command '<path> finished with non-zero exit value 1

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/build-logs/2025-10-18-compileJava.log

### JavacCannotFindSymbol (1)

> > Task :compileJava <path>:<n>: error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^

> <path>:<n>: error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^ symbol: variable enabled

### GradleBuildFailed (1)

> 1 error > Task :compileJava FAILED FAILURE: Build failed with an exception.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/build-logs/error_patterns_detail.json

### SpringApplicationRunFailed (7)

> { "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ],

> "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ], "config.value.invalid-boolean.probe.search.enabled": [

> ], "config.value.invalid-boolean.probe.search.enabled": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED",

### SpringUnsatisfiedDependency (8)

> { "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ],

> "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ], "config.value.invalid-boolean.probe.search.enabled": [

> ], "config.value.invalid-boolean.probe.search.enabled": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED",

### GradleBuildFailed (6)

> "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED", "org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED\nFAILURE: Build failed with an exception." ],

> "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED", "org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED\nFAILURE: Build failed with an exception." ], "spring.boot.application-run-failed": [

> ], "spring.beans.type-mismatch": [ "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED" ], "gradle.bootRun.failed": [

### JavacCannotFindSymbol (2)

> ], "javac.cannot-find-symbol": [ "> Task :compileJava\n<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^" ], "javac.missing-variable.enabled": [

> ], "javac.missing-variable.enabled": [ "<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^\n symbol: variable enabled\n location: class SearchProbeController" ], "gradle.compileJava.failed": [

> "> Task :compileJava\n<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^" ], "javac.missing-variable.enabled": [ "<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^\n symbol: variable enabled\n location: class SearchProbeController"

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/devtools/build/error_patterns.json

### JavacIllegalStartOfType (3)

> "file": "<path> "line": 12, "message": "illegal start of expression" }, {

> "file": "<path> "line": 11, "message": "illegal start of expression" }, {

> "file": "<path> "line": 14, "message": "illegal start of expression" }, {

### JavacClassInterfaceExpected (3)

> "file": "<path> "line": 19, "message": "class, interface, enum, or record expected" }, {

> "file": "<path> "line": 19, "message": "class, interface, enum, or record expected" }, {

> "file": "<path> "line": 40, "message": "class, interface, enum, or record expected" }, {

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/docs/errors_input.txt

### SpringApplicationRunFailed (1)

> 2025-10-14T13:<n>:40.018+0900 ERROR o.s.boot.SpringApplication - Application run failed org.yaml.snakeyaml.constructor.DuplicateKeyException: while constructing a mapping found duplicate key retrieval

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/docs/BUILD_ERRORS.md

### SpringUnsatisfiedDependency (1)

> **Symptom** - `UnsatisfiedDependencyException` on `SearchProbeController` (constructor param index=1) - `TypeMismatchException`: String → boolean, *Invalid boolean value* `{probe.search.enabled:false}` - Gradle `:bootRun` non-zero exit

### JavacCannotFindSymbol (1)

> **Symptom** ``` error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^

> ## 2025-10-18 — compileJava failure: cannot find symbol `enabled` in SearchProbeController **Symptom** ```

> error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^ symbol: variable enabled

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/docs/build_error_memory__docblock.md

### JavacClassInterfaceExpected (1)

> ## Symptoms (examples from Gradle compile) - `'{'' expected` right after a sentence like `enum to satisfy ... */` - `class, interface, enum, or record expected` around lines that begin with prose such as `class replaces ...` - `illegal character: '#'` or `illegal character: '\u2011'` where Javadoc `{@link Optional#empty()}` or non‑ASCII punctuation leaked into code - `illegal start of type` where a `* @param` line appears without an opening comment

### JavacIllegalStartOfType (1)

> - `class, interface, enum, or record expected` around lines that begin with prose such as `class replaces ...` - `illegal character: '#'` or `illegal character: '\u2011'` where Javadoc `{@link Optional#empty()}` or non‑ASCII punctuation leaked into code - `illegal start of type` where a `* @param` line appears without an opening comment ## Heuristic detectors

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/docs/build_error_memory__neutralized_block_closer.md

### JavacIllegalStartOfType (1)

> - 전형적 로그: - `error: unclosed comment` (여러 파일) - 직후 `reached end of file while parsing`, `illegal start of expression`, `'var' is not allowed here` 등 **연쇄 오류**. 시그니처

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/docs/BUILD_FIX_NOTES__src111_msaerge15.md

### JavacDuplicateClass (1)

> ## Summary - Fixed Gradle compilation failure caused by duplicate classes coming from `src<path> - Implemented **build-time exclusion** for the backup folder across all Gradle modules. - Emitted **duplicate-class scan report** at `tools<path>

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/scripts/analyze_build_output.py

### GradleBuildFailed (1)

> LOG_PATTERNS = { "gradle_build_failed": re.compile(r"FAILURE: Build failed with an exception", re.I), "gradle_task_failed": re.compile(r"Execution failed for task", re.I), "dependency_resolve_failed": re.compile(r"Could not resolve (?:all )?files? for configuration|Could not resolve [^:]+:[^:]+", re.I), "java_symbol_not_found": re.compile(r"symbol:\s+(?:class|method|variable)\s", re.I),

### JavacIllegalStartOfType (1)

> "java_classfile_not_found": re.compile(r"class file for<path> found", re.I), "java_compilation_failed": re.compile(r"<path>:s)?\s*$", re.I | re.M), "java_illegal_start": re.compile(r"illegal start of expression", re.I), "java_incompatible_types": re.compile(r"incompatible types:", re.I), "java_unreachable_statement": re.compile(r"unreachable statement", re.I),

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/src/fix-and-build.ps1

### GradleBuildFailed (1)

> Start-Sleep -Seconds 1 } Write-Error "BUILD FAILED after $MaxAttempts attempts. See build<path> if available." exit 1

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/tools/Matrixtxt.snapshot.txt

### GradleBuildFailed (51)

> src111_merge15 (<n>).zip분석하고, 빌드오류 에러패턴이 내부에 남기게한 기능 있는데,그걸로 여태 빌드 패턴들 찾아서,아래 빌드오류 해결하고 src111_merge15.zip로 출력해줘. > Task :compileJava FAILED <path>:<n>: error: cannot find symbol import com.example.lms.service.ChatService;

> * What went wrong: Execution failed for task ':compileJava'. > Compilation failed; see the compiler error output for details.

> * Exception is: org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':compileJava'. at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.lambda$executeIfValid$1(ExecuteActionsTaskExecuter.java:<n>) at org.gradle.internal.Try$Failure.ifSuccessfulOrElse(Try.java:<n>)

### JavacCannotFindSymbol (103)

> src111_merge15 (<n>).zip분석하고, 빌드오류 에러패턴이 내부에 남기게한 기능 있는데,그걸로 여태 빌드 패턴들 찾아서,아래 빌드오류 해결하고 src111_merge15.zip로 출력해줘. > Task :compileJava FAILED <path>:<n>: error: cannot find symbol import com.example.lms.service.ChatService; ^

> symbol: class ChatService location: package com.example.lms.service <path>:<n>: error: cannot find symbol private final ChatService chatService; ^

> symbol: class ChatService location: class ChatApiController <path>:<n>: error: cannot find symbol @RequiredArgsConstructor ^

### JavacClassInterfaceExpected (18)

> public class ChatService { ^ <path>:<n>: error: interface expected here public class ChatServiceImpl implements ChatService { ^

> 상단에 “백업 파일, 컴파일 충돌 방지용” 주석 추가. 2) ChatServiceImpl implements ChatService 에서 “interface expected here” 증상: 백업 충돌 탓에 ChatService가 클래스로 인식되어 implements 지점에서 실패.

> 필요하시면 이 상태에서 추가 자동 머지(특정 파일 단위) 또는 정책에 맞춘 설정값 주입까지 바로 진행해 드릴게요. > Task :compileJava FAILED <path>:<n>: error: interface expected here public class ChatServiceImpl implements ChatService { ^

### JavacIncompatibleTypes (3)

> @Override ^ <path>:<n>: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResult return orchestrator.continueChat(req, q -> List.of()); ^

> @Override ^ <path>:<n>: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResult return orchestrator.continueChat(req, snippetProvider); ^

> @Override ^ <path>:<n>: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResult return orchestrator.ask(userMessage); ^

### JavacPackageDoesNotExist (2)

> symbol: class ChatService location: package com.example.lms.service <path>:<n>: error: package com.example.lms.service.ChatService does not exist import com.example.lms.service.ChatService.ChatResult; ^

> symbol: class ChatService location: package com.example.lms.service <path>:<n>: error: package com.example.lms.service.ChatService does not exist import com.example.lms.service.ChatService.ChatResult; ^

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/tools/build_matrix.yaml

### JavacCannotFindSymbol (1)

> actions: [ensure_gradle_wrapper_script] - id: lombok_missing_symbols when: "(error: cannot find symbol.*@(?:Getter|Setter|Builder|Slf4j|RequiredArgsConstructor))|(lombok)" actions: [ensure_gradle_lombok, enable_idea_annotation_processing] - id: invalid_java_release

> when: "(error: cannot find symbol.*@(?:Getter|Setter|Builder|Slf4j|RequiredArgsConstructor))|(lombok)" actions: [ensure_gradle_lombok, enable_idea_annotation_processing] - id: invalid_java_release when: "(invalid source release)|(target release)|Unsupported class file major version"

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T214414.465/tools/error_pattern_extractor.py

### SpringApplicationRunFailed (1)

> R_PATTERNS = { 'spring.boot.application-run-failed': re.compile(r'Application run failed'), 'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'), 'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'),

### SpringUnsatisfiedDependency (1)

> R_PATTERNS = { 'spring.boot.application-run-failed': re.compile(r'Application run failed'), 'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'), 'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'), 'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'),

### GradleBuildFailed (2)

> 'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'), 'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'), 'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'), 'gradle.process.non-zero-exit': re.compile(r'finished with non-zero exit value'), 'gradle.compileJava.failed': re.compile(r'> Task :compileJava FAILED'),

> 'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'), 'gradle.process.non-zero-exit': re.compile(r'finished with non-zero exit value'), 'gradle.compileJava.failed': re.compile(r'> Task :compileJava FAILED'), 'javac.cannot-find-symbol': re.compile(r'error:\s+cannot find symbol'), 'javac.package-not-found': re.compile(r'error:\s+package .+ does not exist'),

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/BUILD_FIX_NOTES.md

### JavacIllegalStartOfType (1)

> ## src111_merge15 — Fix #001 (2025-10-12) **Error Pattern:** `illegal start of expression` (unmatched constructor brace) - **Detector:** cfvm-raw<path> (pattern `ILLEGAL_START` didn't match 'expression', only 'type') - **Incident:** QdrantClient.java line <n> (`public List... search(...)`) declared before closing constructor block.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/BUILD_PATTERN_SUMMARY.md

### JavacDuplicateClass (1)

> - `cannot find symbol` → **MissingSymbol** - `duplicate class` → **DuplicateClass** - `illegal start of type` → **IllegalStartOfType** - `class, interface, enum, or record expected` → **ClassOrInterfaceExpected**

### JavacIllegalStartOfType (2)

> - `cannot find symbol` → **MissingSymbol** - `duplicate class` → **DuplicateClass** - `illegal start of type` → **IllegalStartOfType** - `class, interface, enum, or record expected` → **ClassOrInterfaceExpected** - `package ... does not exist` → **PackageNotFound**

> **New pattern (auto-appended — 2025-10-24 00:<n>:53Z):** - `illegal start of expression` caused by `; * authorityDecayMultiplier` after a closed expression. **Fix:** fold the multiply into the same expression: `double score = (sim + subjectTerm + genericTerm + ruleDelta + (synergyBonus * synergyWeight)) * authorityDecayMultiplier;`

### JavacClassInterfaceExpected (1)

> - `duplicate class` → **DuplicateClass** - `illegal start of type` → **IllegalStartOfType** - `class, interface, enum, or record expected` → **ClassOrInterfaceExpected** - `package ... does not exist` → **PackageNotFound**

### JavacPackageDoesNotExist (1)

> **Symptom** ``` error: package jdk.incubator.vector does not exist error: Preview features are not enabled for unit ... ```

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/BUILD_PATTERN_RUN.md

### SpringUnsatisfiedDependency (1)

> ## [2025-10-21 21:<n>:29 UTC] Pattern detected & resolved — Missing bean due to feature flag **Signature:** `UnsatisfiedDependencyException` → requires `OpenAiImageService` **Context:** `ImageJobService` ctor (param #1) **Action:** Added `@ConditionalOnBean(OpenAiImageService.class)` to `ImageJobService`.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/BUILD_ERROR_PATTERN_SCAN.md

### SpringUnsatisfiedDependency (1)

> ## Update — 2025‑10‑22 bootRun failure (Spring Boot) Pattern: CONFIG_PROPERTIES_BINDING_VALIDATION_FAILED → UnsatisfiedDependencyException during controller creation due to `@Validated @ConfigurationProperties` record enforcing `@NotBlank` on missing property. Signature (exact):

### SpringConfigBindingFailed (1)

> Signature (exact): ``` Binding to target com.example.lms.plugin.image.OpenAiImageProperties failed: Property: openai.image.endpoint Value: "null"

### JavacIllegalStartOfType (3)

> Files: - <path>:12 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression

> - <path>:12 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression

> - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression - <path>:40 — class, interface, enum, or record expected

### JavacClassInterfaceExpected (5)

> Files: - <path>:12 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected

> - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression - <path>:40 — class, interface, enum, or record expected

> - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression - <path>:40 — class, interface, enum, or record expected Remedy applied:

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/BUILD_PATTERN_REPORT.md

### JavacIllegalStartOfType (1)

> ## Findings - Current error: **`illegal start of expression`** → *not matched* by existing `ILLEGAL_START` (which expects `type`). **Root cause located:** missing `}` closing brace after constructor in `QdrantClient.java`.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/BUILD_FIX_NOTES__77_bm25.md

### JavacClassInterfaceExpected (1)

> ## Errors observed - `class, interface, enum, or record expected` around lines with `} catch (Throwable ignore) { /* analyzer not on classpath */ }` - `cannot find symbol: class NoriAnalyzer` at: - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexHolder`

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/BUILD_FIX_NOTES__79_fusion.md

### JavacCannotFindSymbol (1)

> - `Bm25LocalRetriever.java:<n>: error: method does not override or implement a method from a supertype` - `FusionService.java:<n>: error: method fuse in class WeightedRRF cannot be applied to given types` - `FusionService.java:<n>: error: cannot find symbol: class SearchResult` ## Root causes (mapped to internal build-error patterns)

> - `FusionService.java:<n>: error: cannot find symbol: class SearchResult` ## Root causes (mapped to internal build-error patterns) - **OverrideMismatch**: `@Override` present while the class does not implement/extend a supertype that declares `retrieve(String,int)`.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/BUILD_FIX__onnx_cross_encoder.md

### JavacIllegalStartOfType (1)

> **Problem** `illegal start of expression`/`illegal start of type` around `resolveBudgetMs(...)` in multiple `OnnxCrossEncoderReranker.java` variants. Root cause: stray tokens (`...`) and brace imbalance from partial snippets caused methods to appear inside expression contexts.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/MERGE_NOTE__mergerx15.txt

### JavacClassInterfaceExpected (1)

> Fix: compileJava failures (illegal start/class expected) in com.abandonwareai/* Changes:

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/build_error_patterns_summary.json

### JavacCannotFindSymbol (43)

> "\ncannot find symbol: ChatApiController.java 15, TasksApiController.java 15, ChatWebSocketHandler.java 15\n", "\ncannot find symbol: class ChatService가 컨트롤러<path> 연쇄적으로 발생\n(Lombok @RequiredArgsConstructor가 붙은 필드 주입 지점에서 타입을 못 찾으니 애노테이션 라인까지 오류가 전파됨)", "\nerror: cannot find symbol\nimport com.example.lms.service.ChatService;", "\nmissing_symbol → MissingSymbol → \"Cannot find symbol<path> "\n다 봤어요. 이번 빌드 실패의 핵심 원인은 모듈 내에서 com.example.lms.service.ChatService 타입 자체가 존재하지 않아(파일 부재) 이를 참조하는 컨트롤러<path> 연쇄적으로 cannot find symbol: ChatService가 발생한 것입니다.\n",

> "\n이 중 “cannot find symbol”의 상당수는 패키지 이동이나 리팩토링 후 import 경로가 낡은 경우였습니다. 다음 자동 치환을 적용해 관련 오류를 줄였습니다(7개 파일 수정):\n", "\n패턴 일치 → 원인 지목: 내부 “에러패턴 메모리”에서 missing_symbol 규칙이 이번 로그(“Cannot find symbol”)와 일치.\n", " ^\n<path>:<n>: error: cannot find symbol\npublic class ChatServiceImpl implements ChatService {", " ^\n<path>:<n>: error: cannot find symbol\n log.debug(\"Failed to attach uploaded files to new session: {}\", ex.toString());", " ^\n<path>:<n>: error: cannot find symbol\n List<Object> steps = chain.getSteps(); // 체인 내부 컬렉션을 노출하는 간단 API가 있다고 가정",

> "\n패턴 일치 → 원인 지목: 내부 “에러패턴 메모리”에서 missing_symbol 규칙이 이번 로그(“Cannot find symbol”)와 일치.\n", " ^\n<path>:<n>: error: cannot find symbol\npublic class ChatServiceImpl implements ChatService {", " ^\n<path>:<n>: error: cannot find symbol\n log.debug(\"Failed to attach uploaded files to new session: {}\", ex.toString());", " ^\n<path>:<n>: error: cannot find symbol\n List<Object> steps = chain.getSteps(); // 체인 내부 컬렉션을 노출하는 간단 API가 있다고 가정", " ^\n<path>:<n>: error: cannot find symbol\n private static final Logger log = LoggerFactory.getLogger(ChatService.class);",

### GradleBuildFailed (2)

> "1) 빌드오류 패턴 스캔 결과 → 원인 규정\n저장소의 tools/build_memory_config.yaml에는 정규식 기반 오류 사전이 들어있고, 그 중 missing_symbol 규칙이 \"Cannot find symbol\"을 매칭합니다. 제공해주신 로그는 이 규칙과 정확히 일치했습니다(MissingSymbol). \n", "6) 왜 이 방식이 안전한가?\n패턴 일치 검증: “Cannot find symbol” → 내부 규칙 missing_symbol로 분류해 원인(심볼 부재)을 즉시 확정. \n", "> Task :compileJava FAILED\n<path>:<n>: error: cannot find symbol\nimport com.example.lms.service.ChatService;", "^\n<path>:<n>: error: cannot find symbol\nimport com.example.lms.service.ChatService;", "cannot find symbol log (ChatApiController 등)\tLombok 미적용/중복 정의\t@Slf4j로 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석\ncannot find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가\nincompatible types: ChatOrchestrator.ChatResult -> ChatService.ChatResult\t반환타입 불일치\ttoServiceResult(...) 어댑터로 변환 후 반환",

> "class_or_record_expected": [ "\nerror: class, interface, enum, or record expected\n이는 파일의 클래스 범위 밖에 }가 더 존재할 때 흔히 발생합니다.", "> Task :compileJava FAILED\n<path>:<n>: error: class, interface, enum, or record expected\n}", "Reasoned for 12m 59s\n다 확인했습니다. 핵심 원인은 NaverProvider.java 말미에 } 가 1개 더 있어서 클래스 블록 밖으로 나간 것입니다. Gradle가 “class, interface, enum, or record expected”를 뿜는 전형적인 상황이죠. 동일 문제가 두 군데에 있었습니다.\n", "\n다 봤어요. Gradle 컴파일 에러의 직접 원인은 demo-1<path> 내부 괄호 불일치였습니다. } 가 클래스/메서드 범위를 잘못 닫아서 컴파일러가 “class, interface, enum, or record expected” 를 뿜은 상태였고, 특히 두 군데가 깨져 있었습니다.\n",

### JavacClassInterfaceExpected (6)

> "^\n<path>:<n>: error: cannot find symbol\nimport com.example.lms.service.ChatService;", "cannot find symbol log (ChatApiController 등)\tLombok 미적용/중복 정의\t@Slf4j로 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석\ncannot find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가\nincompatible types: ChatOrchestrator.ChatResult -> ChatService.ChatResult\t반환타입 불일치\ttoServiceResult(...) 어댑터로 변환 후 반환", "interface expected here (ChatServiceImpl implements ChatService)\tChatService가 클래스(레거시)로 남아있던 충돌\t레거시를 legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입\ncannot find symbol log (ChatApiController 등)\tLombok 미적용/중복 정의\t@Slf4j로 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석\ncannot find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가", "“빌드 에러 패턴 메모리” 적용 요약\n패턴 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발\n", "과거 수집 요약: build_error_patterns.csv, build_error_patterns_summary.csv, build_error_patterns_by_config.csv\n→ 이번 오류는 구성 파일의 missing_symbol 규칙과 정확히 일치합니다(“Cannot find symbol”).\n",

> ], "class_or_record_expected": [ "\nerror: class, interface, enum, or record expected\n이는 파일의 클래스 범위 밖에 }가 더 존재할 때 흔히 발생합니다.", "> Task :compileJava FAILED\n<path>:<n>: error: class, interface, enum, or record expected\n}", "Reasoned for 12m 59s\n다 확인했습니다. 핵심 원인은 NaverProvider.java 말미에 } 가 1개 더 있어서 클래스 블록 밖으로 나간 것입니다. Gradle가 “class, interface, enum, or record expected”를 뿜는 전형적인 상황이죠. 동일 문제가 두 군데에 있었습니다.\n",

> "class_or_record_expected": [ "\nerror: class, interface, enum, or record expected\n이는 파일의 클래스 범위 밖에 }가 더 존재할 때 흔히 발생합니다.", "> Task :compileJava FAILED\n<path>:<n>: error: class, interface, enum, or record expected\n}", "Reasoned for 12m 59s\n다 확인했습니다. 핵심 원인은 NaverProvider.java 말미에 } 가 1개 더 있어서 클래스 블록 밖으로 나간 것입니다. Gradle가 “class, interface, enum, or record expected”를 뿜는 전형적인 상황이죠. 동일 문제가 두 군데에 있었습니다.\n", "\n다 봤어요. Gradle 컴파일 에러의 직접 원인은 demo-1<path> 내부 괄호 불일치였습니다. } 가 클래스/메서드 범위를 잘못 닫아서 컴파일러가 “class, interface, enum, or record expected” 를 뿜은 상태였고, 특히 두 군데가 깨져 있었습니다.\n",

### JavacPackageDoesNotExist (1)

> ], "package_does_not_exist": [ " location: package com.example.lms.service\n<path>:<n>: error: package com.example.lms.service.ChatService does not exist\nimport com.example.lms.service.ChatService.ChatResult;" ], "unreachable_statement_finally": [

### JavacIllegalStartOfType (1)

> "illegal_start_of_expression": [ { "pattern": "illegal start of expression", "signature": "double score = (...) ; * authorityDecayMultiplier;", "file": "src<path>

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/BUILD_ERROR_PATTERNS.md

### JavacClassInterfaceExpected (1)

> ## Pattern: ImportBeforePackage **Symptom**: `class, interface, enum, or record expected` at `package ...` line. **Cause**: One or more `import ...;` lines appear *before* the `package` declaration. **Fix**: Move `package` to be the first non-comment line and place all `import` lines after it. (Auto-fixed by reordering script.)

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/.build/error_patterns_db.json

### GradleBuildFailed (1)

> ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path> ], "gradle.dependency_resolution": [

### JavacCannotFindSymbol (1)

> ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path> ], "gradle.dependency_resolution": [

> "- Split `OcrModels.java` into `Rect.java`, `OcrSpan.java`, `OcrChunk.java` to satisfy Java's one-public-type-per-file rule.\n- Enabled Lombok annotation processing in `lms-core` (and tests) to restore getters/setters, constructors, and `@Slf4j` loggers.\n- These address the cascading 'cannot find symbol get*/set*' and 'log' errors across shared sources compiled by `lms-core`.<path> ], "gradle.build_failed": [ "3 errors<path>: Build failed with an exception.<path> What went wrong:"

> "\nDetected patterns:\n- PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package com.networknt.schema does not exist`, `package org.springframework.data.redis.core does not exist`, `package org.springframework.kafka.* does not exist`, `package io.micrometer.* does not exist`, `package io.opentelemetry.* does not exist`, `package org.apache.lucene.analysis.ko does not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanLoader<path> (No hits) DUPLICATE_CLASS / ILLEGAL_START / CLASS_EXPECTED / OVERRIDE_MISMATCH in this excerpt." ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path>

### JavacClassInterfaceExpected (1)

> ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path> ], "gradle.dependency_resolution": [

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/_assistant/diagnostics/build_error_patterns.json

### GradleBuildFailed (2)

> { "file": "src/fix-and-build.ps1", "pattern": "BUILD FAILED", "line": "BUILD FAILED" },

> "file": "src/fix-and-build.ps1", "pattern": "BUILD FAILED", "line": "BUILD FAILED" }, {

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/analysis/build_patterns_aggregated.json

### JavacDuplicateClass (3)

> }, { "pattern": "duplicate class", "code": "DuplicateClass" },

> }, { "pattern": "duplicate class: .*_abandonware_backup.*", "code": "DuplicateClassAbandonwareBackup" }

> "notes": [ "Fixed repeated modifier in SearchTrace class declaration by keeping a single 'public static final'.", "Consider checking for potential duplicate class: two NaverSearchService.java exist under the same package; ensure only one is compiled." ], "patterns": {

### JavacIllegalStartOfType (1)

> }, { "pattern": "illegal start of type", "code": "IllegalStartOfType" },

### JavacClassInterfaceExpected (1)

> }, { "pattern": "class, interface, enum, or record expected", "code": "ClassOrInterfaceExpected" },

### JavacPackageDoesNotExist (1)

> ], "sample_error": [ "error: package service.rag.auth does not exist", "error: cannot find symbol class DomainWhitelist" ]

### JavacCannotFindSymbol (1)

> "sample_error": [ "error: package service.rag.auth does not exist", "error: cannot find symbol class DomainWhitelist" ] }

> "pattern": "cannot find symbol", "code": "MissingSymbol" }, {

> "pattern": "cannot find symbol rerankTopK(List<ContextSlice>,int)", "code": "MissingMethod" }, {

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/analysis/build_fix_applied.json

### JavacDuplicateClass (1)

> }, { "pattern": "duplicate class", "code": "DuplicateClass" },

### JavacIllegalStartOfType (1)

> }, { "pattern": "illegal start of type", "code": "IllegalStartOfType" },

### JavacClassInterfaceExpected (1)

> }, { "pattern": "class, interface, enum, or record expected", "code": "ClassOrInterfaceExpected" },

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/app/resources/dev/build/ERROR_PATTERNS.json

### JavacMissingSemicolon (1)

> }, { "pattern": "unclosed string literal|<identifier> expected|';' expected", "fix": "Remove stray characters and complete the statement; watch for logging format strings." }

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/build/error-patterns.json

### JavacDuplicateClass (2)

> { "code": "DuplicateClassAbandonwareBackup", "pattern": "duplicate class: .*_abandonware_backup.*", "example": "<path>:<n>: error: duplicate class: com.abandonware.ai.addons.budget.CancelToken", "fix": [

> "code": "DuplicateClassAbandonwareBackup", "pattern": "duplicate class: .*_abandonware_backup.*", "example": "<path>:<n>: error: duplicate class: com.abandonware.ai.addons.budget.CancelToken", "fix": [ "Move src<path> -> backup/_abandonware_backup (outside source set)",

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/build-logs/2025-10-18-bootRun.log

### SpringUnsatisfiedDependency (2)

> 2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] 2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]

> 2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] 2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]

### SpringApplicationRunFailed (1)

> 2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] 2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]

### GradleBuildFailed (2)

> Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}] > Task :bootRun FAILED FAILURE: Build failed with an exception. * What went wrong:

> FAILURE: Build failed with an exception. * What went wrong: Execution failed for task ':bootRun'. > Process 'command '<path> finished with non-zero exit value 1

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/build-logs/2025-10-18-compileJava.log

### JavacCannotFindSymbol (1)

> > Task :compileJava <path>:<n>: error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^

> <path>:<n>: error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^ symbol: variable enabled

### GradleBuildFailed (1)

> 1 error > Task :compileJava FAILED FAILURE: Build failed with an exception.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/build-logs/error_patterns_detail.json

### SpringApplicationRunFailed (7)

> { "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ],

> "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ], "config.value.invalid-boolean.probe.search.enabled": [

> ], "config.value.invalid-boolean.probe.search.enabled": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED",

### SpringUnsatisfiedDependency (8)

> { "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ],

> "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ], "config.value.invalid-boolean.probe.search.enabled": [

> ], "config.value.invalid-boolean.probe.search.enabled": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED",

### GradleBuildFailed (6)

> "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED", "org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED\nFAILURE: Build failed with an exception." ],

> "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED", "org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED\nFAILURE: Build failed with an exception." ], "spring.boot.application-run-failed": [

> ], "spring.beans.type-mismatch": [ "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED" ], "gradle.bootRun.failed": [

### JavacCannotFindSymbol (2)

> ], "javac.cannot-find-symbol": [ "> Task :compileJava\n<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^" ], "javac.missing-variable.enabled": [

> ], "javac.missing-variable.enabled": [ "<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^\n symbol: variable enabled\n location: class SearchProbeController" ], "gradle.compileJava.failed": [

> "> Task :compileJava\n<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^" ], "javac.missing-variable.enabled": [ "<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^\n symbol: variable enabled\n location: class SearchProbeController"

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/devtools/build/error_patterns.json

### JavacIllegalStartOfType (3)

> "file": "<path> "line": 12, "message": "illegal start of expression" }, {

> "file": "<path> "line": 11, "message": "illegal start of expression" }, {

> "file": "<path> "line": 14, "message": "illegal start of expression" }, {

### JavacClassInterfaceExpected (3)

> "file": "<path> "line": 19, "message": "class, interface, enum, or record expected" }, {

> "file": "<path> "line": 19, "message": "class, interface, enum, or record expected" }, {

> "file": "<path> "line": 40, "message": "class, interface, enum, or record expected" }, {

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/docs/errors_input.txt

### SpringApplicationRunFailed (1)

> 2025-10-14T13:<n>:40.018+0900 ERROR o.s.boot.SpringApplication - Application run failed org.yaml.snakeyaml.constructor.DuplicateKeyException: while constructing a mapping found duplicate key retrieval

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/docs/BUILD_ERRORS.md

### SpringUnsatisfiedDependency (1)

> **Symptom** - `UnsatisfiedDependencyException` on `SearchProbeController` (constructor param index=1) - `TypeMismatchException`: String → boolean, *Invalid boolean value* `{probe.search.enabled:false}` - Gradle `:bootRun` non-zero exit

### JavacCannotFindSymbol (1)

> **Symptom** ``` error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^

> ## 2025-10-18 — compileJava failure: cannot find symbol `enabled` in SearchProbeController **Symptom** ```

> error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^ symbol: variable enabled

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/docs/build_error_memory__docblock.md

### JavacClassInterfaceExpected (1)

> ## Symptoms (examples from Gradle compile) - `'{'' expected` right after a sentence like `enum to satisfy ... */` - `class, interface, enum, or record expected` around lines that begin with prose such as `class replaces ...` - `illegal character: '#'` or `illegal character: '\u2011'` where Javadoc `{@link Optional#empty()}` or non‑ASCII punctuation leaked into code - `illegal start of type` where a `* @param` line appears without an opening comment

### JavacIllegalStartOfType (1)

> - `class, interface, enum, or record expected` around lines that begin with prose such as `class replaces ...` - `illegal character: '#'` or `illegal character: '\u2011'` where Javadoc `{@link Optional#empty()}` or non‑ASCII punctuation leaked into code - `illegal start of type` where a `* @param` line appears without an opening comment ## Heuristic detectors

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/docs/build_error_memory__neutralized_block_closer.md

### JavacIllegalStartOfType (1)

> - 전형적 로그: - `error: unclosed comment` (여러 파일) - 직후 `reached end of file while parsing`, `illegal start of expression`, `'var' is not allowed here` 등 **연쇄 오류**. 시그니처

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/docs/BUILD_FIX_NOTES__src111_msaerge15.md

### JavacDuplicateClass (1)

> ## Summary - Fixed Gradle compilation failure caused by duplicate classes coming from `src<path> - Implemented **build-time exclusion** for the backup folder across all Gradle modules. - Emitted **duplicate-class scan report** at `tools<path>

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/scripts/analyze_build_output.py

### GradleBuildFailed (1)

> LOG_PATTERNS = { "gradle_build_failed": re.compile(r"FAILURE: Build failed with an exception", re.I), "gradle_task_failed": re.compile(r"Execution failed for task", re.I), "dependency_resolve_failed": re.compile(r"Could not resolve (?:all )?files? for configuration|Could not resolve [^:]+:[^:]+", re.I), "java_symbol_not_found": re.compile(r"symbol:\s+(?:class|method|variable)\s", re.I),

### JavacIllegalStartOfType (1)

> "java_classfile_not_found": re.compile(r"class file for<path> found", re.I), "java_compilation_failed": re.compile(r"<path>:s)?\s*$", re.I | re.M), "java_illegal_start": re.compile(r"illegal start of expression", re.I), "java_incompatible_types": re.compile(r"incompatible types:", re.I), "java_unreachable_statement": re.compile(r"unreachable statement", re.I),

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/src/fix-and-build.ps1

### GradleBuildFailed (1)

> Start-Sleep -Seconds 1 } Write-Error "BUILD FAILED after $MaxAttempts attempts. See build<path> if available." exit 1

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/tools/Matrixtxt.snapshot.txt

### GradleBuildFailed (51)

> src111_merge15 (<n>).zip분석하고, 빌드오류 에러패턴이 내부에 남기게한 기능 있는데,그걸로 여태 빌드 패턴들 찾아서,아래 빌드오류 해결하고 src111_merge15.zip로 출력해줘. > Task :compileJava FAILED <path>:<n>: error: cannot find symbol import com.example.lms.service.ChatService;

> * What went wrong: Execution failed for task ':compileJava'. > Compilation failed; see the compiler error output for details.

> * Exception is: org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':compileJava'. at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.lambda$executeIfValid$1(ExecuteActionsTaskExecuter.java:<n>) at org.gradle.internal.Try$Failure.ifSuccessfulOrElse(Try.java:<n>)

### JavacCannotFindSymbol (103)

> src111_merge15 (<n>).zip분석하고, 빌드오류 에러패턴이 내부에 남기게한 기능 있는데,그걸로 여태 빌드 패턴들 찾아서,아래 빌드오류 해결하고 src111_merge15.zip로 출력해줘. > Task :compileJava FAILED <path>:<n>: error: cannot find symbol import com.example.lms.service.ChatService; ^

> symbol: class ChatService location: package com.example.lms.service <path>:<n>: error: cannot find symbol private final ChatService chatService; ^

> symbol: class ChatService location: class ChatApiController <path>:<n>: error: cannot find symbol @RequiredArgsConstructor ^

### JavacClassInterfaceExpected (18)

> public class ChatService { ^ <path>:<n>: error: interface expected here public class ChatServiceImpl implements ChatService { ^

> 상단에 “백업 파일, 컴파일 충돌 방지용” 주석 추가. 2) ChatServiceImpl implements ChatService 에서 “interface expected here” 증상: 백업 충돌 탓에 ChatService가 클래스로 인식되어 implements 지점에서 실패.

> 필요하시면 이 상태에서 추가 자동 머지(특정 파일 단위) 또는 정책에 맞춘 설정값 주입까지 바로 진행해 드릴게요. > Task :compileJava FAILED <path>:<n>: error: interface expected here public class ChatServiceImpl implements ChatService { ^

### JavacIncompatibleTypes (3)

> @Override ^ <path>:<n>: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResult return orchestrator.continueChat(req, q -> List.of()); ^

> @Override ^ <path>:<n>: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResult return orchestrator.continueChat(req, snippetProvider); ^

> @Override ^ <path>:<n>: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResult return orchestrator.ask(userMessage); ^

### JavacPackageDoesNotExist (2)

> symbol: class ChatService location: package com.example.lms.service <path>:<n>: error: package com.example.lms.service.ChatService does not exist import com.example.lms.service.ChatService.ChatResult; ^

> symbol: class ChatService location: package com.example.lms.service <path>:<n>: error: package com.example.lms.service.ChatService does not exist import com.example.lms.service.ChatService.ChatResult; ^

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/tools/build_matrix.yaml

### JavacCannotFindSymbol (1)

> actions: [ensure_gradle_wrapper_script] - id: lombok_missing_symbols when: "(error: cannot find symbol.*@(?:Getter|Setter|Builder|Slf4j|RequiredArgsConstructor))|(lombok)" actions: [ensure_gradle_lombok, enable_idea_annotation_processing] - id: invalid_java_release

> when: "(error: cannot find symbol.*@(?:Getter|Setter|Builder|Slf4j|RequiredArgsConstructor))|(lombok)" actions: [ensure_gradle_lombok, enable_idea_annotation_processing] - id: invalid_java_release when: "(invalid source release)|(target release)|Unsupported class file major version"

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-28T010004.821/tools/error_pattern_extractor.py

### SpringApplicationRunFailed (1)

> R_PATTERNS = { 'spring.boot.application-run-failed': re.compile(r'Application run failed'), 'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'), 'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'),

### SpringUnsatisfiedDependency (1)

> R_PATTERNS = { 'spring.boot.application-run-failed': re.compile(r'Application run failed'), 'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'), 'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'), 'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'),

### GradleBuildFailed (2)

> 'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'), 'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'), 'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'), 'gradle.process.non-zero-exit': re.compile(r'finished with non-zero exit value'), 'gradle.compileJava.failed': re.compile(r'> Task :compileJava FAILED'),

> 'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'), 'gradle.process.non-zero-exit': re.compile(r'finished with non-zero exit value'), 'gradle.compileJava.failed': re.compile(r'> Task :compileJava FAILED'), 'javac.cannot-find-symbol': re.compile(r'error:\s+cannot find symbol'), 'javac.package-not-found': re.compile(r'error:\s+package .+ does not exist'),

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/BUILD_FIX_NOTES.md

### JavacIllegalStartOfType (1)

> ## src111_merge15 — Fix #001 (2025-10-12) **Error Pattern:** `illegal start of expression` (unmatched constructor brace) - **Detector:** cfvm-raw<path> (pattern `ILLEGAL_START` didn't match 'expression', only 'type') - **Incident:** QdrantClient.java line <n> (`public List... search(...)`) declared before closing constructor block.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/BUILD_PATTERN_SUMMARY.md

### JavacDuplicateClass (1)

> - `cannot find symbol` → **MissingSymbol** - `duplicate class` → **DuplicateClass** - `illegal start of type` → **IllegalStartOfType** - `class, interface, enum, or record expected` → **ClassOrInterfaceExpected**

### JavacIllegalStartOfType (2)

> - `cannot find symbol` → **MissingSymbol** - `duplicate class` → **DuplicateClass** - `illegal start of type` → **IllegalStartOfType** - `class, interface, enum, or record expected` → **ClassOrInterfaceExpected** - `package ... does not exist` → **PackageNotFound**

> **New pattern (auto-appended — 2025-10-24 00:<n>:53Z):** - `illegal start of expression` caused by `; * authorityDecayMultiplier` after a closed expression. **Fix:** fold the multiply into the same expression: `double score = (sim + subjectTerm + genericTerm + ruleDelta + (synergyBonus * synergyWeight)) * authorityDecayMultiplier;`

### JavacClassInterfaceExpected (1)

> - `duplicate class` → **DuplicateClass** - `illegal start of type` → **IllegalStartOfType** - `class, interface, enum, or record expected` → **ClassOrInterfaceExpected** - `package ... does not exist` → **PackageNotFound**

### JavacPackageDoesNotExist (1)

> **Symptom** ``` error: package jdk.incubator.vector does not exist error: Preview features are not enabled for unit ... ```

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/BUILD_PATTERN_RUN.md

### SpringUnsatisfiedDependency (1)

> ## [2025-10-21 21:<n>:29 UTC] Pattern detected & resolved — Missing bean due to feature flag **Signature:** `UnsatisfiedDependencyException` → requires `OpenAiImageService` **Context:** `ImageJobService` ctor (param #1) **Action:** Added `@ConditionalOnBean(OpenAiImageService.class)` to `ImageJobService`.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/BUILD_ERROR_PATTERN_SCAN.md

### SpringUnsatisfiedDependency (1)

> ## Update — 2025‑10‑22 bootRun failure (Spring Boot) Pattern: CONFIG_PROPERTIES_BINDING_VALIDATION_FAILED → UnsatisfiedDependencyException during controller creation due to `@Validated @ConfigurationProperties` record enforcing `@NotBlank` on missing property. Signature (exact):

### SpringConfigBindingFailed (1)

> Signature (exact): ``` Binding to target com.example.lms.plugin.image.OpenAiImageProperties failed: Property: openai.image.endpoint Value: "null"

### JavacIllegalStartOfType (3)

> Files: - <path>:12 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression

> - <path>:12 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression

> - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression - <path>:40 — class, interface, enum, or record expected

### JavacClassInterfaceExpected (5)

> Files: - <path>:12 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected

> - <path>:19 — class, interface, enum, or record expected - <path>:11 — illegal start of expression - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression - <path>:40 — class, interface, enum, or record expected

> - <path>:19 — class, interface, enum, or record expected - <path>:14 — illegal start of expression - <path>:40 — class, interface, enum, or record expected Remedy applied:

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/BUILD_PATTERN_REPORT.md

### JavacIllegalStartOfType (1)

> ## Findings - Current error: **`illegal start of expression`** → *not matched* by existing `ILLEGAL_START` (which expects `type`). **Root cause located:** missing `}` closing brace after constructor in `QdrantClient.java`.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/BUILD_FIX_NOTES__77_bm25.md

### JavacClassInterfaceExpected (1)

> ## Errors observed - `class, interface, enum, or record expected` around lines with `} catch (Throwable ignore) { /* analyzer not on classpath */ }` - `cannot find symbol: class NoriAnalyzer` at: - `com.abandonware.ai.agent.service.rag.bm25.Bm25IndexHolder`

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/BUILD_FIX_NOTES__79_fusion.md

### JavacCannotFindSymbol (1)

> - `Bm25LocalRetriever.java:<n>: error: method does not override or implement a method from a supertype` - `FusionService.java:<n>: error: method fuse in class WeightedRRF cannot be applied to given types` - `FusionService.java:<n>: error: cannot find symbol: class SearchResult` ## Root causes (mapped to internal build-error patterns)

> - `FusionService.java:<n>: error: cannot find symbol: class SearchResult` ## Root causes (mapped to internal build-error patterns) - **OverrideMismatch**: `@Override` present while the class does not implement/extend a supertype that declares `retrieve(String,int)`.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/BUILD_FIX__onnx_cross_encoder.md

### JavacIllegalStartOfType (1)

> **Problem** `illegal start of expression`/`illegal start of type` around `resolveBudgetMs(...)` in multiple `OnnxCrossEncoderReranker.java` variants. Root cause: stray tokens (`...`) and brace imbalance from partial snippets caused methods to appear inside expression contexts.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/MERGE_NOTE__mergerx15.txt

### JavacClassInterfaceExpected (1)

> Fix: compileJava failures (illegal start/class expected) in com.abandonwareai/* Changes:

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/build_error_patterns_summary.json

### JavacCannotFindSymbol (43)

> "\ncannot find symbol: ChatApiController.java 15, TasksApiController.java 15, ChatWebSocketHandler.java 15\n", "\ncannot find symbol: class ChatService가 컨트롤러<path> 연쇄적으로 발생\n(Lombok @RequiredArgsConstructor가 붙은 필드 주입 지점에서 타입을 못 찾으니 애노테이션 라인까지 오류가 전파됨)", "\nerror: cannot find symbol\nimport com.example.lms.service.ChatService;", "\nmissing_symbol → MissingSymbol → \"Cannot find symbol<path> "\n다 봤어요. 이번 빌드 실패의 핵심 원인은 모듈 내에서 com.example.lms.service.ChatService 타입 자체가 존재하지 않아(파일 부재) 이를 참조하는 컨트롤러<path> 연쇄적으로 cannot find symbol: ChatService가 발생한 것입니다.\n",

> "\n이 중 “cannot find symbol”의 상당수는 패키지 이동이나 리팩토링 후 import 경로가 낡은 경우였습니다. 다음 자동 치환을 적용해 관련 오류를 줄였습니다(7개 파일 수정):\n", "\n패턴 일치 → 원인 지목: 내부 “에러패턴 메모리”에서 missing_symbol 규칙이 이번 로그(“Cannot find symbol”)와 일치.\n", " ^\n<path>:<n>: error: cannot find symbol\npublic class ChatServiceImpl implements ChatService {", " ^\n<path>:<n>: error: cannot find symbol\n log.debug(\"Failed to attach uploaded files to new session: {}\", ex.toString());", " ^\n<path>:<n>: error: cannot find symbol\n List<Object> steps = chain.getSteps(); // 체인 내부 컬렉션을 노출하는 간단 API가 있다고 가정",

> "\n패턴 일치 → 원인 지목: 내부 “에러패턴 메모리”에서 missing_symbol 규칙이 이번 로그(“Cannot find symbol”)와 일치.\n", " ^\n<path>:<n>: error: cannot find symbol\npublic class ChatServiceImpl implements ChatService {", " ^\n<path>:<n>: error: cannot find symbol\n log.debug(\"Failed to attach uploaded files to new session: {}\", ex.toString());", " ^\n<path>:<n>: error: cannot find symbol\n List<Object> steps = chain.getSteps(); // 체인 내부 컬렉션을 노출하는 간단 API가 있다고 가정", " ^\n<path>:<n>: error: cannot find symbol\n private static final Logger log = LoggerFactory.getLogger(ChatService.class);",

### GradleBuildFailed (2)

> "1) 빌드오류 패턴 스캔 결과 → 원인 규정\n저장소의 tools/build_memory_config.yaml에는 정규식 기반 오류 사전이 들어있고, 그 중 missing_symbol 규칙이 \"Cannot find symbol\"을 매칭합니다. 제공해주신 로그는 이 규칙과 정확히 일치했습니다(MissingSymbol). \n", "6) 왜 이 방식이 안전한가?\n패턴 일치 검증: “Cannot find symbol” → 내부 규칙 missing_symbol로 분류해 원인(심볼 부재)을 즉시 확정. \n", "> Task :compileJava FAILED\n<path>:<n>: error: cannot find symbol\nimport com.example.lms.service.ChatService;", "^\n<path>:<n>: error: cannot find symbol\nimport com.example.lms.service.ChatService;", "cannot find symbol log (ChatApiController 등)\tLombok 미적용/중복 정의\t@Slf4j로 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석\ncannot find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가\nincompatible types: ChatOrchestrator.ChatResult -> ChatService.ChatResult\t반환타입 불일치\ttoServiceResult(...) 어댑터로 변환 후 반환",

> "class_or_record_expected": [ "\nerror: class, interface, enum, or record expected\n이는 파일의 클래스 범위 밖에 }가 더 존재할 때 흔히 발생합니다.", "> Task :compileJava FAILED\n<path>:<n>: error: class, interface, enum, or record expected\n}", "Reasoned for 12m 59s\n다 확인했습니다. 핵심 원인은 NaverProvider.java 말미에 } 가 1개 더 있어서 클래스 블록 밖으로 나간 것입니다. Gradle가 “class, interface, enum, or record expected”를 뿜는 전형적인 상황이죠. 동일 문제가 두 군데에 있었습니다.\n", "\n다 봤어요. Gradle 컴파일 에러의 직접 원인은 demo-1<path> 내부 괄호 불일치였습니다. } 가 클래스/메서드 범위를 잘못 닫아서 컴파일러가 “class, interface, enum, or record expected” 를 뿜은 상태였고, 특히 두 군데가 깨져 있었습니다.\n",

### JavacClassInterfaceExpected (6)

> "^\n<path>:<n>: error: cannot find symbol\nimport com.example.lms.service.ChatService;", "cannot find symbol log (ChatApiController 등)\tLombok 미적용/중복 정의\t@Slf4j로 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석\ncannot find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가\nincompatible types: ChatOrchestrator.ChatResult -> ChatService.ChatResult\t반환타입 불일치\ttoServiceResult(...) 어댑터로 변환 후 반환", "interface expected here (ChatServiceImpl implements ChatService)\tChatService가 클래스(레거시)로 남아있던 충돌\t레거시를 legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입\ncannot find symbol log (ChatApiController 등)\tLombok 미적용/중복 정의\t@Slf4j로 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석\ncannot find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가", "“빌드 에러 패턴 메모리” 적용 요약\n패턴 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발\n", "과거 수집 요약: build_error_patterns.csv, build_error_patterns_summary.csv, build_error_patterns_by_config.csv\n→ 이번 오류는 구성 파일의 missing_symbol 규칙과 정확히 일치합니다(“Cannot find symbol”).\n",

> ], "class_or_record_expected": [ "\nerror: class, interface, enum, or record expected\n이는 파일의 클래스 범위 밖에 }가 더 존재할 때 흔히 발생합니다.", "> Task :compileJava FAILED\n<path>:<n>: error: class, interface, enum, or record expected\n}", "Reasoned for 12m 59s\n다 확인했습니다. 핵심 원인은 NaverProvider.java 말미에 } 가 1개 더 있어서 클래스 블록 밖으로 나간 것입니다. Gradle가 “class, interface, enum, or record expected”를 뿜는 전형적인 상황이죠. 동일 문제가 두 군데에 있었습니다.\n",

> "class_or_record_expected": [ "\nerror: class, interface, enum, or record expected\n이는 파일의 클래스 범위 밖에 }가 더 존재할 때 흔히 발생합니다.", "> Task :compileJava FAILED\n<path>:<n>: error: class, interface, enum, or record expected\n}", "Reasoned for 12m 59s\n다 확인했습니다. 핵심 원인은 NaverProvider.java 말미에 } 가 1개 더 있어서 클래스 블록 밖으로 나간 것입니다. Gradle가 “class, interface, enum, or record expected”를 뿜는 전형적인 상황이죠. 동일 문제가 두 군데에 있었습니다.\n", "\n다 봤어요. Gradle 컴파일 에러의 직접 원인은 demo-1<path> 내부 괄호 불일치였습니다. } 가 클래스/메서드 범위를 잘못 닫아서 컴파일러가 “class, interface, enum, or record expected” 를 뿜은 상태였고, 특히 두 군데가 깨져 있었습니다.\n",

### JavacPackageDoesNotExist (1)

> ], "package_does_not_exist": [ " location: package com.example.lms.service\n<path>:<n>: error: package com.example.lms.service.ChatService does not exist\nimport com.example.lms.service.ChatService.ChatResult;" ], "unreachable_statement_finally": [

### JavacIllegalStartOfType (1)

> "illegal_start_of_expression": [ { "pattern": "illegal start of expression", "signature": "double score = (...) ; * authorityDecayMultiplier;", "file": "src<path>

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/BUILD_ERROR_PATTERNS.md

### JavacClassInterfaceExpected (1)

> ## Pattern: ImportBeforePackage **Symptom**: `class, interface, enum, or record expected` at `package ...` line. **Cause**: One or more `import ...;` lines appear *before* the `package` declaration. **Fix**: Move `package` to be the first non-comment line and place all `import` lines after it. (Auto-fixed by reordering script.)

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/.build/error_patterns_db.json

### GradleBuildFailed (1)

> ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path> ], "gradle.dependency_resolution": [

### JavacCannotFindSymbol (1)

> ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path> ], "gradle.dependency_resolution": [

> "- Split `OcrModels.java` into `Rect.java`, `OcrSpan.java`, `OcrChunk.java` to satisfy Java's one-public-type-per-file rule.\n- Enabled Lombok annotation processing in `lms-core` (and tests) to restore getters/setters, constructors, and `@Slf4j` loggers.\n- These address the cascading 'cannot find symbol get*/set*' and 'log' errors across shared sources compiled by `lms-core`.<path> ], "gradle.build_failed": [ "3 errors<path>: Build failed with an exception.<path> What went wrong:"

> "\nDetected patterns:\n- PACKAGE_NOT_FOUND — e.g., `package com.fasterxml.jackson.dataformat.yaml does not exist`, `package com.networknt.schema does not exist`, `package org.springframework.data.redis.core does not exist`, `package org.springframework.kafka.* does not exist`, `package io.micrometer.* does not exist`, `package io.opentelemetry.* does not exist`, `package org.apache.lucene.analysis.ko does not exist`.\n- MISSING_SYMBOL — e.g., `cannot find symbol JsonSchema`, `cannot find symbol StringRedisTemplate`, `cannot find symbol KafkaTemplate`, `cannot find symbol NewTopic`, `cannot find symbol Tracer`, `cannot find symbol NoriTokenizer`, `cannot find symbol PlanLoader<path> (No hits) DUPLICATE_CLASS / ILLEGAL_START / CLASS_EXPECTED / OVERRIDE_MISMATCH in this excerpt." ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path>

### JavacClassInterfaceExpected (1)

> ], "java.incompatible_types": [ " \"> Task :compileJava FAILED<path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> <path>:<path>:<n>: error: cannot find symbol<path> com.example.lms.service.ChatService;<path> \"cannot find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> types: ChatOrchestrator.ChatResult -> ChatService.ChatResult<path> 불일치<path> 어댑터로 변환 후 반환<path> \"interface expected here (ChatServiceImpl implements ChatService)<path> 클래스(레거시)로 남아있던 충돌<path> legacy/ChatServiceLegacy로 이동/개명 + 인터페이스 도입<path> find symbol log (ChatApiController 등)<path> 미적용/중복 정의<path> 통일(중복 Logger import 제거), 필요 시 수동 Logger 한 줄 대안 주석<path> find symbol ChatService (컨트롤러<path> 파일 경로 부재<path> 직접 추가<path> \"“빌드 에러 패턴 메모리” 적용 요약<path> 감지: 서비스 인터페이스 유실 → 전 계층에서 cannot find symbol 다발<path> ], "gradle.dependency_resolution": [

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/_assistant/diagnostics/build_error_patterns.json

### GradleBuildFailed (2)

> { "file": "src/fix-and-build.ps1", "pattern": "BUILD FAILED", "line": "BUILD FAILED" },

> "file": "src/fix-and-build.ps1", "pattern": "BUILD FAILED", "line": "BUILD FAILED" }, {

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/analysis/build_patterns_aggregated.json

### JavacDuplicateClass (3)

> }, { "pattern": "duplicate class", "code": "DuplicateClass" },

> }, { "pattern": "duplicate class: .*_abandonware_backup.*", "code": "DuplicateClassAbandonwareBackup" }

> "notes": [ "Fixed repeated modifier in SearchTrace class declaration by keeping a single 'public static final'.", "Consider checking for potential duplicate class: two NaverSearchService.java exist under the same package; ensure only one is compiled." ], "patterns": {

### JavacIllegalStartOfType (1)

> }, { "pattern": "illegal start of type", "code": "IllegalStartOfType" },

### JavacClassInterfaceExpected (1)

> }, { "pattern": "class, interface, enum, or record expected", "code": "ClassOrInterfaceExpected" },

### JavacPackageDoesNotExist (1)

> ], "sample_error": [ "error: package service.rag.auth does not exist", "error: cannot find symbol class DomainWhitelist" ]

### JavacCannotFindSymbol (1)

> "sample_error": [ "error: package service.rag.auth does not exist", "error: cannot find symbol class DomainWhitelist" ] }

> "pattern": "cannot find symbol", "code": "MissingSymbol" }, {

> "pattern": "cannot find symbol rerankTopK(List<ContextSlice>,int)", "code": "MissingMethod" }, {

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/analysis/build_fix_applied.json

### JavacDuplicateClass (1)

> }, { "pattern": "duplicate class", "code": "DuplicateClass" },

### JavacIllegalStartOfType (1)

> }, { "pattern": "illegal start of type", "code": "IllegalStartOfType" },

### JavacClassInterfaceExpected (1)

> }, { "pattern": "class, interface, enum, or record expected", "code": "ClassOrInterfaceExpected" },

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/app/resources/dev/build/ERROR_PATTERNS.json

### JavacMissingSemicolon (1)

> }, { "pattern": "unclosed string literal|<identifier> expected|';' expected", "fix": "Remove stray characters and complete the statement; watch for logging format strings." }

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/build/error-patterns.json

### JavacDuplicateClass (2)

> { "code": "DuplicateClassAbandonwareBackup", "pattern": "duplicate class: .*_abandonware_backup.*", "example": "<path>:<n>: error: duplicate class: com.abandonware.ai.addons.budget.CancelToken", "fix": [

> "code": "DuplicateClassAbandonwareBackup", "pattern": "duplicate class: .*_abandonware_backup.*", "example": "<path>:<n>: error: duplicate class: com.abandonware.ai.addons.budget.CancelToken", "fix": [ "Move src<path> -> backup/_abandonware_backup (outside source set)",

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/build-logs/2025-10-18-bootRun.log

### SpringUnsatisfiedDependency (2)

> 2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] 2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]

> 2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] 2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]

### SpringApplicationRunFailed (1)

> 2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] 2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]

### GradleBuildFailed (2)

> Caused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}] Caused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}] > Task :bootRun FAILED FAILURE: Build failed with an exception. * What went wrong:

> FAILURE: Build failed with an exception. * What went wrong: Execution failed for task ':bootRun'. > Process 'command '<path> finished with non-zero exit value 1

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/build-logs/2025-10-18-compileJava.log

### JavacCannotFindSymbol (1)

> > Task :compileJava <path>:<n>: error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^

> <path>:<n>: error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^ symbol: variable enabled

### GradleBuildFailed (1)

> 1 error > Task :compileJava FAILED FAILURE: Build failed with an exception.

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/build-logs/error_patterns_detail.json

### SpringApplicationRunFailed (7)

> { "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ],

> "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ], "config.value.invalid-boolean.probe.search.enabled": [

> ], "config.value.invalid-boolean.probe.search.enabled": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED",

### SpringUnsatisfiedDependency (8)

> { "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ],

> "spring.beans.unsatisfied-dependency": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]" ], "config.value.invalid-boolean.probe.search.enabled": [

> ], "config.value.invalid-boolean.probe.search.enabled": [ "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED",

### GradleBuildFailed (6)

> "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED", "org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED\nFAILURE: Build failed with an exception." ],

> "2025-10-18T20:<n>:00.742+0900 WARN o.s.b.w.s.c.AnnotationConfigServletWebServerApplicationContext - Exception encountered during context initialization - cancelling refresh attempt: org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\n2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]", "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED", "org.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED\nFAILURE: Build failed with an exception." ], "spring.boot.application-run-failed": [

> ], "spring.beans.type-mismatch": [ "2025-10-18T20:<n>:00.794+0900 ERROR o.s.boot.SpringApplication - Application run failed\norg.springframework.beans.factory.UnsatisfiedDependencyException: Error creating bean with name 'searchProbeController' defined in file [<path>: Unsatisfied dependency expressed through constructor parameter 1: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: org.springframework.beans.TypeMismatchException: Failed to convert value of type 'java.lang.String' to required type 'boolean'; Invalid boolean value [{probe.search.enabled:false}]\nCaused by: java.lang.IllegalArgumentException: Invalid boolean value [{probe.search.enabled:false}]\n> Task :bootRun FAILED" ], "gradle.bootRun.failed": [

### JavacCannotFindSymbol (2)

> ], "javac.cannot-find-symbol": [ "> Task :compileJava\n<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^" ], "javac.missing-variable.enabled": [

> ], "javac.missing-variable.enabled": [ "<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^\n symbol: variable enabled\n location: class SearchProbeController" ], "gradle.compileJava.failed": [

> "> Task :compileJava\n<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^" ], "javac.missing-variable.enabled": [ "<path>:<n>: error: cannot find symbol\n if (!enabled) return ResponseEntity.status(<n>).body(\"Probe disabled<path> ^\n symbol: variable enabled\n location: class SearchProbeController"

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/devtools/build/error_patterns.json

### JavacIllegalStartOfType (3)

> "file": "<path> "line": 12, "message": "illegal start of expression" }, {

> "file": "<path> "line": 11, "message": "illegal start of expression" }, {

> "file": "<path> "line": 14, "message": "illegal start of expression" }, {

### JavacClassInterfaceExpected (3)

> "file": "<path> "line": 19, "message": "class, interface, enum, or record expected" }, {

> "file": "<path> "line": 19, "message": "class, interface, enum, or record expected" }, {

> "file": "<path> "line": 40, "message": "class, interface, enum, or record expected" }, {

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/docs/errors_input.txt

### SpringApplicationRunFailed (1)

> 2025-10-14T13:<n>:40.018+0900 ERROR o.s.boot.SpringApplication - Application run failed org.yaml.snakeyaml.constructor.DuplicateKeyException: while constructing a mapping found duplicate key retrieval

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/docs/BUILD_ERRORS.md

### SpringUnsatisfiedDependency (1)

> **Symptom** - `UnsatisfiedDependencyException` on `SearchProbeController` (constructor param index=1) - `TypeMismatchException`: String → boolean, *Invalid boolean value* `{probe.search.enabled:false}` - Gradle `:bootRun` non-zero exit

### JavacCannotFindSymbol (1)

> **Symptom** ``` error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^

> ## 2025-10-18 — compileJava failure: cannot find symbol `enabled` in SearchProbeController **Symptom** ```

> error: cannot find symbol if (!enabled) return ResponseEntity.status(<n>).body("Probe disabled"); ^ symbol: variable enabled

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/docs/build_error_memory__docblock.md

### JavacClassInterfaceExpected (1)

> ## Symptoms (examples from Gradle compile) - `'{'' expected` right after a sentence like `enum to satisfy ... */` - `class, interface, enum, or record expected` around lines that begin with prose such as `class replaces ...` - `illegal character: '#'` or `illegal character: '\u2011'` where Javadoc `{@link Optional#empty()}` or non‑ASCII punctuation leaked into code - `illegal start of type` where a `* @param` line appears without an opening comment

### JavacIllegalStartOfType (1)

> - `class, interface, enum, or record expected` around lines that begin with prose such as `class replaces ...` - `illegal character: '#'` or `illegal character: '\u2011'` where Javadoc `{@link Optional#empty()}` or non‑ASCII punctuation leaked into code - `illegal start of type` where a `* @param` line appears without an opening comment ## Heuristic detectors

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/docs/build_error_memory__neutralized_block_closer.md

### JavacIllegalStartOfType (1)

> - 전형적 로그: - `error: unclosed comment` (여러 파일) - 직후 `reached end of file while parsing`, `illegal start of expression`, `'var' is not allowed here` 등 **연쇄 오류**. 시그니처

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/docs/BUILD_FIX_NOTES__src111_msaerge15.md

### JavacDuplicateClass (1)

> ## Summary - Fixed Gradle compilation failure caused by duplicate classes coming from `src<path> - Implemented **build-time exclusion** for the backup folder across all Gradle modules. - Emitted **duplicate-class scan report** at `tools<path>

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/scripts/analyze_build_output.py

### GradleBuildFailed (1)

> LOG_PATTERNS = { "gradle_build_failed": re.compile(r"FAILURE: Build failed with an exception", re.I), "gradle_task_failed": re.compile(r"Execution failed for task", re.I), "dependency_resolve_failed": re.compile(r"Could not resolve (?:all )?files? for configuration|Could not resolve [^:]+:[^:]+", re.I), "java_symbol_not_found": re.compile(r"symbol:\s+(?:class|method|variable)\s", re.I),

### JavacIllegalStartOfType (1)

> "java_classfile_not_found": re.compile(r"class file for<path> found", re.I), "java_compilation_failed": re.compile(r"<path>:s)?\s*$", re.I | re.M), "java_illegal_start": re.compile(r"illegal start of expression", re.I), "java_incompatible_types": re.compile(r"incompatible types:", re.I), "java_unreachable_statement": re.compile(r"unreachable statement", re.I),

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/src/fix-and-build.ps1

### GradleBuildFailed (1)

> Start-Sleep -Seconds 1 } Write-Error "BUILD FAILED after $MaxAttempts attempts. See build<path> if available." exit 1

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/tools/Matrixtxt.snapshot.txt

### GradleBuildFailed (51)

> src111_merge15 (<n>).zip분석하고, 빌드오류 에러패턴이 내부에 남기게한 기능 있는데,그걸로 여태 빌드 패턴들 찾아서,아래 빌드오류 해결하고 src111_merge15.zip로 출력해줘. > Task :compileJava FAILED <path>:<n>: error: cannot find symbol import com.example.lms.service.ChatService;

> * What went wrong: Execution failed for task ':compileJava'. > Compilation failed; see the compiler error output for details.

> * Exception is: org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':compileJava'. at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.lambda$executeIfValid$1(ExecuteActionsTaskExecuter.java:<n>) at org.gradle.internal.Try$Failure.ifSuccessfulOrElse(Try.java:<n>)

### JavacCannotFindSymbol (103)

> src111_merge15 (<n>).zip분석하고, 빌드오류 에러패턴이 내부에 남기게한 기능 있는데,그걸로 여태 빌드 패턴들 찾아서,아래 빌드오류 해결하고 src111_merge15.zip로 출력해줘. > Task :compileJava FAILED <path>:<n>: error: cannot find symbol import com.example.lms.service.ChatService; ^

> symbol: class ChatService location: package com.example.lms.service <path>:<n>: error: cannot find symbol private final ChatService chatService; ^

> symbol: class ChatService location: class ChatApiController <path>:<n>: error: cannot find symbol @RequiredArgsConstructor ^

### JavacClassInterfaceExpected (18)

> public class ChatService { ^ <path>:<n>: error: interface expected here public class ChatServiceImpl implements ChatService { ^

> 상단에 “백업 파일, 컴파일 충돌 방지용” 주석 추가. 2) ChatServiceImpl implements ChatService 에서 “interface expected here” 증상: 백업 충돌 탓에 ChatService가 클래스로 인식되어 implements 지점에서 실패.

> 필요하시면 이 상태에서 추가 자동 머지(특정 파일 단위) 또는 정책에 맞춘 설정값 주입까지 바로 진행해 드릴게요. > Task :compileJava FAILED <path>:<n>: error: interface expected here public class ChatServiceImpl implements ChatService { ^

### JavacIncompatibleTypes (3)

> @Override ^ <path>:<n>: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResult return orchestrator.continueChat(req, q -> List.of()); ^

> @Override ^ <path>:<n>: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResult return orchestrator.continueChat(req, snippetProvider); ^

> @Override ^ <path>:<n>: error: incompatible types: com.example.lms.service.ChatOrchestrator.ChatResult cannot be converted to com.example.lms.service.ChatService.ChatResult return orchestrator.ask(userMessage); ^

### JavacPackageDoesNotExist (2)

> symbol: class ChatService location: package com.example.lms.service <path>:<n>: error: package com.example.lms.service.ChatService does not exist import com.example.lms.service.ChatService.ChatResult; ^

> symbol: class ChatService location: package com.example.lms.service <path>:<n>: error: package com.example.lms.service.ChatService does not exist import com.example.lms.service.ChatService.ChatResult; ^

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/tools/build_matrix.yaml

### JavacCannotFindSymbol (1)

> actions: [ensure_gradle_wrapper_script] - id: lombok_missing_symbols when: "(error: cannot find symbol.*@(?:Getter|Setter|Builder|Slf4j|RequiredArgsConstructor))|(lombok)" actions: [ensure_gradle_lombok, enable_idea_annotation_processing] - id: invalid_java_release

> when: "(error: cannot find symbol.*@(?:Getter|Setter|Builder|Slf4j|RequiredArgsConstructor))|(lombok)" actions: [ensure_gradle_lombok, enable_idea_annotation_processing] - id: invalid_java_release when: "(invalid source release)|(target release)|Unsupported class file major version"

## FILE: /mnt/data/work/stuff4/src111_merge15 - 2025-10-27T222650.778/tools/error_pattern_extractor.py

### SpringApplicationRunFailed (1)

> R_PATTERNS = { 'spring.boot.application-run-failed': re.compile(r'Application run failed'), 'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'), 'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'),

### SpringUnsatisfiedDependency (1)

> R_PATTERNS = { 'spring.boot.application-run-failed': re.compile(r'Application run failed'), 'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'), 'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'), 'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'),

### GradleBuildFailed (2)

> 'spring.beans.unsatisfied-dependency': re.compile(r'UnsatisfiedDependencyException'), 'spring.beans.type-mismatch': re.compile(r'TypeMismatchException'), 'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'), 'gradle.process.non-zero-exit': re.compile(r'finished with non-zero exit value'), 'gradle.compileJava.failed': re.compile(r'> Task :compileJava FAILED'),

> 'gradle.bootRun.failed': re.compile(r'> Task :bootRun FAILED'), 'gradle.process.non-zero-exit': re.compile(r'finished with non-zero exit value'), 'gradle.compileJava.failed': re.compile(r'> Task :compileJava FAILED'), 'javac.cannot-find-symbol': re.compile(r'error:\s+cannot find symbol'), 'javac.package-not-found': re.compile(r'error:\s+package .+ does not exist'),
