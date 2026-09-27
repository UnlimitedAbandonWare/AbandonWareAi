# GPT Pro Task: Adjudicate the demo-1 100-Row Source Audit

You are reviewing a supplied, source-backed audit snapshot. Do not assume repository access, do not invent current code, and do not claim that any proposed verification ran. Treat STAPS as a design claim map and the audit observations as evidence candidates that still require your adjudication.

## Decision Requested

Adjudicate every one of the 100 supplied primary audit IDs before ranking anything. Correct classifications, merge only genuinely shared root causes, retain rejected and merged IDs visibly with reasons, and propose a Top 10 immediate set plus a Top 25 ordered backlog. For each retained priority, identify the smallest active-owner repair seam and a falsifiable RED/GREEN verification path. Separate source-backed conclusions from runtime, browser, provider, database, benchmark, and product-policy gaps.

This is an advisory request only. Your response proposes decisions and verification; it grants no authority to implement, edit, run, call, authenticate, register, stage, commit, push, deploy, or mutate anything.

## Repository and Evidence Snapshot

The following immutable, redacted snapshot is the complete source boundary supplied to you. You have no repository or conversation access beyond this prompt.

- canonicalRoot: `C:\AbandonWare\demo-1\demo-1\src`
- capturedAt: `2026-08-13T15:46:38.3020208Z`
- branch: `codex/owned-runtime-browser-restart`
- head: `0796a3c5b29bbb08c3314bd40649d856d4a7bce6`
- dirtyCount: `1509` (count only; no dirty-file names are supplied)
- indexLockPresent: `false`
- javaMajor: `17`
- staps.pathRole: `design_claim_map`
- staps.bytes: `286051`
- staps.sha256: `A4AB90929E7C7541C3B4A9B28A60ED4234578D708F641D3479380FF15CFFBE16`
- activeSourceSets: `main/java`, `main/resources`, `src/test/java`, `app/src/main/java_clean`, `app/src/main/resources`
- canonicalMainClass: `com.example.lms.LmsApplication`
- suppliedLedger.sha256: `A0BF93D5F62EEC2E0DB094A7C45A80F53C4733A24572C14E9372269A651DB31B`
- suppliedLedger.primaryCount: `100`
- suppliedLedger.reserveCount: `9`

`staps.txt` is a design-claim map only, never runtime truth. A changed branch, HEAD, STAPS hash, source-set ownership, or row anchor would require the affected mapping to be refreshed; if such changed evidence is explicitly supplied, classify the affected row as `hold` with reason `snapshot_changed`. Do not infer drift merely because you cannot inspect the repository.

The ledger generation recorded the following category population:

| Domain | Raw candidates | Primary rows | Reserve rows | Merge note |
| --- | ---: | ---: | ---: | --- |
| Build, configuration, and source ownership | 11 | 8 | 3 | `AUTO-02`, `DEP-01`, `JAVA-01` are reserve |
| RAG core and orchestration | 11 | 8 | 3 | `RC-07`, `RC-08`, `RC-12` are reserve |
| Resilience and observability | 12 | 11 | 1 | `R12` is reserve |
| AutoLearn and data lifecycle | 12 | 11 | 1 | `AL-11` is reserve |
| Security and provider boundaries | 10 | 10 | 0 | none |
| Query, evidence, citation, and fusion | 7 | 7 | 0 | none |
| Prompt and model routing | 9 | 9 | 0 | none |
| Plan DSL and AOP interaction | 9 | 9 | 0 | none |
| API, session, and attachment lifecycle | 9 | 9 | 0 | owns merged canonical `API-ARCHIVE-05` |
| Test and proof blind spots | 10 | 9 | 1 | `TBL-10` is reserve |
| Storage, KG, and retention | 10 | 9 | 0 | `STKG-02` merged into `API-ARCHIVE-05` |
| **Total** | **110** | **100** | **9** | **1 canonical merge; 109 independent candidates** |

The supplied primary classifications are inputs to adjudicate, not accepted truth:

| Classification | Primary rows |
| --- | ---: |
| `confirmed_defect` | 26 |
| `structural_risk` | 67 |
| `evidence_needed` | 7 |
| **Total** | **100** |

Reserve classifications are excluded from those totals and from the default Top 10. An `evidence_needed` row is an audit row, not a defect.

## Immutable Technical and Privacy Constraints

- Preserve Java 17 and the current Spring Boot version. Do not propose version changes.
- Preserve every `dev.langchain4j` dependency exactly at `1.0.1`; do not propose dependency additions, removals, or version changes.
- Keep final RAG prompt construction on `PromptBuilder.build(PromptContext)` or the existing equivalent builder/context boundary. Do not move final prompt assembly into ad hoc ChatService string concatenation.
- Preserve the active-source boundary and canonical owners supplied here. Do not invent a second implementation, wrapper, route, framework, orchestration layer, or duplicate owner.
- Preserve existing property names, formats, shapes, and secret flow. In particular, do not rename, delete, normalize, or restructure any `openssl` or `opnessl` key/value/name.
- Preserve classification separation: observation is a source-backed fact; inference is only a bounded consequence; counter-evidence is a limiting fact; a design claim is not runtime truth; `evidence_needed` is neither a confirmed defect nor a structural risk.
- Preserve same-surface evidence rules. Do not promote static, build, test, runtime, browser, provider, database, or benchmark evidence across surfaces.
- Do not request, reveal, reconstruct, or use credentials, authorization headers, cookies, owner tokens, private environment values, raw private prompts, provider responses, full error bodies, internal share mappings, or exact private data. Use only synthetic examples and count-, boolean-, reason-code-, path-, symbol-, and hash-level evidence.
- Do not expose broad dirty-file names. The supplied `dirtyCount` is the complete permitted dirty-tree context.

## Classification Rules

Allowed supplied and corrected classifications are:

- `confirmed_defect`: an active-owner behavior is source-backed and has an active-path reachability case.
- `structural_risk`: a source-backed concern whose failure condition or active behavior requires more proof.
- `evidence_needed`: an owner, timestamp, source, verifier, or required evidence surface is absent or changed.

`evidence_needed` rows count as audit rows but never as confirmed defects. Exclude them from corrected defect totals, corrected risk totals, and the default Top 10 unless newly supplied evidence within this prompt itself promotes them. `stapsStatus` is exactly one of `implemented`, `partial`, `contradicted`, `stale`, `not_observed`, or `not_applicable`; STAPS wording cannot promote a static observation into a runtime defect.

For every row, independently challenge the observation, inference, reachability, counter-evidence, confidence, falsifier, verification class, and minimal repair seam. Do not use reviewer count, severity labels, or STAPS wording as a vote.

Same-surface rules:

- Source existence proves source existence only.
- Bean declarations do not prove runtime activation without selection evidence.
- `BUILD SUCCESSFUL` proves only the executed task graph.
- `NO-SOURCE` is not test coverage.
- HTTP or SSE delivery does not prove answer semantics.
- A rendered answer does not prove a provider attempt.
- A provider status or response does not prove quality or citation correctness.
- A trace key does not prove the underlying worker, wire, or database event.
- Hash equality proves byte identity, not semantic success.
- Benchmark improvements require a same-corpus, same-configuration baseline.

Unobserved runtime, browser, provider, database, and benchmark surfaces remain `not_observed` or `evidence_needed`. `evidenceSurface` is exactly one of `static`, `build`, `test`, `runtime`, `browser`, `provider`, `database`, or `benchmark`; `verificationClass` is exactly one of `local_read_only`, `local_build`, `local_test`, `runtime`, or `external`; `verificationCost` is exactly one of `low`, `medium`, or `high`.

## Complete Primary ID Coverage List

Account for each ID below exactly once in your `# ID COVERAGE AUDIT`, using exactly one status from `confirm | merge | downgrade | reject | hold`.

```text
CFG-01 CFG-02 AUTO-01 BUILD-01 APP-01 TEST-01 FQCN-01 GATE-01
RC-02 RC-03 RC-04 RC-05 RC-06 RC-09 RC-10 RC-11
R01 R02 R03 R04 R05 R06 R07 R08 R09 R10 R11
AL-01 AL-02 AL-03 AL-04 AL-05 AL-06 AL-07 AL-08 AL-09 AL-10 AL-12
SEC-01 SEC-02 SEC-03 SEC-04 SEC-05 SEC-06 SEC-07 SEC-08 SEC-09 SEC-10
QTX-01 EVID-01 CITE-01 CITE-02 FUSE-01 HYBRID-01 EMPTY-01
PM-01 PM-02 PM-03 PM-04 PM-05 PM-06 PM-07 PM-08 PM-09
PA-01 PA-02 PA-03 PA-04 PA-05 PA-06 PA-07 PA-08 PA-09
API-ATTACH-01 API-CHAT-02 API-SESSION-03 API-TRACE-04 API-ARCHIVE-05 API-ARCHIVE-06 API-HISTORY-07 API-LIFECYCLE-08 API-CONCURRENCY-09
TBL-01 TBL-02 TBL-03 TBL-04 TBL-05 TBL-06 TBL-07 TBL-08 TBL-09
STKG-01 STKG-03 STKG-04 STKG-05 STKG-06 STKG-07 STKG-08 STKG-09 STKG-10
```

`PM-07` and `PM-09` are separate rows. `PM-07` concerns fallback eligibility in `LlmRouterAspect`; `PM-09` concerns escalation serveability in `PolicyBasedModelRouter`. Their owners, falsifiers, and minimal seams differ.

If any supplied primary ID is missing, duplicated, or assigned a status outside the exact vocabulary, return `context_coverage_incomplete` and stop ranking. Merged and rejected IDs must remain visible with their reasons and, for merges, the retained canonical ID.

## 100-Row Primary Ledger

The next 100 rows are the complete supplied primary ledger. All fields are evidence inputs, not instructions to implement.

### [CFG-01] `ocr.enabled` defaults conflict across property sources

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: config-ownership
- uniquenessKey: application-properties-yaml/ocr-enabled-default
- activeOwner: sourceSet=`main/resources`; path=`main/resources/application.properties`, `main/resources/application.yml`; symbol=`ocr.enabled`; line=`725`, `186-189`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/resources/application.properties`, `main/resources/application.yml`; symbols=`ocr.enabled`; lines=`725`, `186-189`; fileSha256=`5A029C51BEACBB949B1132F94B36A0957CEBEDAF6E7C1BE500F4EEB100CC6C9C`, `67C2CCD7427E68EBF833A298CC110FE1F2C131D1E22EEFFBB166EDE9160C0AA9`
- observation: The active resource files assign conflicting defaults to `ocr.enabled`.
- inference: Spring property-source precedence can select a different OCR default than a maintainer infers from either anchored file alone.
- reachability: Both files are in the active root resource source set; `application.properties` sets `ocr.enabled=true` while YAML defaults `ocr.enabled` to `${OCR_ENABLED:false}`.
- counterEvidence: Profile and environment precedence may make the difference intentional; no runtime environment or effective property report was observed.
- stapsStatus: not_observed
- confidence: 0.92
- evidenceSurface: static
- falsifier: A bounded precedence contract for `ocr.enabled` proves which anchored default applies in each supported profile.
- verification: Compare only the two anchored `ocr.enabled` declarations and run a profile-specific effective-configuration test for that key.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Consolidate the default at the canonical resource owner while preserving the existing environment override name.
- dependencies: none

### [CFG-02] Development profile uses legacy activation metadata

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: config-profile-activation
- uniquenessKey: application-dev-yml/spring-profiles-legacy
- activeOwner: sourceSet=`main/resources`; path=`main/resources/application-dev.yml`; symbol=`spring.profiles`; line=`8`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/resources/application-dev.yml`; symbol=`spring.profiles`; line=`8`; fileSha256=`CC50CB692D8AD32FF9DF164EE196F593ED0B9708D716EA0FDEBC8AA80C95C0C1`
- observation: The profile file uses legacy `spring.profiles: dev` metadata.
- inference: Current Spring Boot profile-document processing may reject or ignore the legacy selector rather than activate the intended development overrides.
- reachability: The file is in the active resource source set and is named `application-dev.yml`, but activation behavior was not executed.
- counterEvidence: Filename-based profile loading can still select the file when `dev` is active, so a runtime failure is not statically proven.
- stapsStatus: not_observed
- confidence: 0.91
- evidenceSurface: static
- falsifier: A focused Spring configuration test proves the current Boot version loads the file and all intended keys without a legacy-metadata error.
- verification: Start an isolated application context with profile `dev` and assert one file-specific property plus absence of configuration-data errors.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Replace only the document activation metadata with the current `spring.config.activate.on-profile` form if the test is RED.
- dependencies: CFG-01

### [AUTO-01] App metadata names root-owned auto-configurations

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: app-artifact-ownership
- uniquenessKey: app-autoconfiguration-imports/root-only-classes
- activeOwner: sourceSet=`app/src/main/resources`; path=`app/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `app/build.gradle.kts`; symbol=`AutoConfiguration.imports`, `sourceSets.main`; line=`1-2`, `44-48`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`app/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `app/build.gradle.kts`; symbols=`AddonsAutoConfiguration/NovaProtocolConfig imports`, `sourceSets.main`; lines=`1-2`, `44-48`; fileSha256=`FEBE5CC25FA19C90511D2BBF7DC54BDCEA8766079175806035B489B89E4387F9`, `E0A083AC253FB5F25FB8BAE4B271EC3C1FEDF99A7AE2908DF565BA4DC24576C4`
- observation: The `:app` artifact advertises auto-configurations whose classes are owned only by root source.
- inference: Consuming `:app` without the root application classes can expose imports that cannot resolve, making the artifact contract depend on reverse ownership.
- reachability: `:app` packages its active resources and compiles only `src/main/java_clean`; the named classes exist under root `main/java` and not that app source root.
- counterEvidence: The root boot artifact includes both root classes and the `:app` dependency, so the canonical root runtime may resolve the imports.
- stapsStatus: partial
- confidence: 0.95
- evidenceSurface: static
- falsifier: A standalone `:app` consumer context loads both advertised auto-configurations from declared app dependencies without root project classes.
- verification: Build a minimal isolated consumer of `:app` and load Spring auto-configuration imports.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Align `:app` metadata with classes actually owned or declared as dependencies by the app artifact.
- dependencies: BUILD-01

### [BUILD-01] Settings files declare divergent project graphs

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: gradle-settings-ownership
- uniquenessKey: dual-settings/root-name-module-graph
- activeOwner: sourceSet=`Gradle settings`; path=`settings.gradle`, `settings.gradle.kts`; symbol=`rootProject.name` and `include`; line=`15-38`, `93-110`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`settings.gradle`, `settings.gradle.kts`; symbols=`rootProject.name/include`; lines=`15-38`, `93-110`; fileSha256=`BCC1DA452154B69470A79D53604276C617CE356EA0E42428057BBAB5294EF895`, `989A5870C5E2EBFDA4E248F2A39CBBBD8008C9F601539DD16E187E216F1370CD`
- observation: Two settings files describe different root names and legacy-module graphs.
- inference: Tooling or maintainers can reason from a settings file Gradle does not select, obscuring the effective project topology.
- reachability: Both files are at the repository root; Groovy names `src111_merge15` and conditionally includes `cfvm-raw`, while Kotlin names `lms-core` and lists other legacy and backup modules.
- counterEvidence: Gradle deterministically selects one settings script; no project-discovery failure was observed in this task.
- stapsStatus: not_applicable
- confidence: 0.96
- evidenceSurface: static
- falsifier: Repository documentation plus a guard proves one file is intentionally inert and all supported tooling ignores it.
- verification: Run Gradle project discovery separately with each settings file in an isolated copy and compare root name and project paths.
- verificationClass: local_build
- verificationCost: medium
- minimalRepairSeam: Retain one canonical settings owner or add an explicit inert-file guard without altering module policy.
- dependencies: none

### [APP-01] A second executable entry point scans a different component set

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: application-entrypoint-ownership
- uniquenessKey: compiled-main/scan-base-packages-divergence
- activeOwner: sourceSet=`main/java`; path=`build.gradle.kts`, `main/java/com/example/lms/LmsApplication.java`, `main/java/com/abandonware/ai/agent/AgentApplication.java`; symbol=`bootJar.mainClass`, `@SpringBootApplication`; line=`483`, `18`, `19-24`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`build.gradle.kts`, `main/java/com/example/lms/LmsApplication.java`, `main/java/com/abandonware/ai/agent/AgentApplication.java`; symbols=`bootJar.mainClass`, `LmsApplication`, `AgentApplication`; lines=`483`, `18-27`, `19-29`; fileSha256=`B4D9D4791F0F256CD312267564F889F1E0A30F88D95024FF85B5CB1451C397F4`, `3C5778AF3A49DD4A25C3F8946F21239E8E1D442A483F24CBABA0DB3E44167D21`, `456185199F76751CAB3B7603747256A92945423FAE1623E9F62FC893F7C377ED`
- observation: A second compiled executable entry point has a materially different component scan from the boot-jar owner.
- inference: Launching the secondary main class can create a context with different beans and behavior from the canonical boot jar.
- reachability: Both classes are in active root `main/java`; the boot jar selects `com.example.lms.LmsApplication`, while `AgentApplication` remains directly executable.
- counterEvidence: The build explicitly selects `LmsApplication`, and no evidence shows production launches the secondary class.
- stapsStatus: partial
- confidence: 0.93
- evidenceSurface: static
- falsifier: A launch policy or test proves `AgentApplication` is intentionally supported and context-equivalent for its documented use.
- verification: Compare application-context bean inventories from isolated non-web launches of both main classes.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Constrain or remove secondary executability, or make its documented scan boundary explicit without changing the canonical main class.
- dependencies: BUILD-01

### [TEST-01] App test source sets are explicitly empty

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: app-test-coverage
- uniquenessKey: app-sourceSets/test-empty
- activeOwner: sourceSet=`Gradle app build`; path=`app/build.gradle.kts`; symbol=`sourceSets.test`; line=`49-52`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`app/build.gradle.kts`; symbol=`sourceSets.test`; line=`49-52`; fileSha256=`E0A083AC253FB5F25FB8BAE4B271EC3C1FEDF99A7AE2908DF565BA4DC24576C4`
- observation: `:app` declares empty test source and resource directories.
- inference: App-owned artifact contracts can receive no tests from the conventional app test tree, even though test dependencies are declared.
- reachability: The active `:app` Gradle source-set definition calls `setSrcDirs(emptyList<String>())` for both test Java and resources.
- counterEvidence: Root tests may exercise app classes transitively; no coverage measurement or `:app:test` execution was observed.
- stapsStatus: not_observed
- confidence: 0.98
- evidenceSurface: static
- falsifier: `:app:test` discovers at least one app-owned artifact-contract test through a test source directory mapped by the current app build.
- verification: Inspect Gradle test source directories and run `:app:test` with task outcome and discovered-test count captured.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Map the intended app test root or move the smallest artifact-contract tests under an active test source set.
- dependencies: AUTO-01

### [FQCN-01] Duplicate FQCNs default to non-failing handling

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: source-ownership-duplicates
- uniquenessKey: root-java_clean/duplicate-fqcn-default-none
- activeOwner: sourceSet=`app Gradle packaging`; path=`app/build.gradle.kts`; symbol=`parseDupFqcnExcludeMode`, `generateDupFqcnExcludes`; line=`85-115`, `197-271`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`app/build.gradle.kts`; symbol=`duplicate FQCN scan/default`; line=`85-115`, `197-271`; fileSha256=`E0A083AC253FB5F25FB8BAE4B271EC3C1FEDF99A7AE2908DF565BA4DC24576C4`; boundedScan=`duplicateCount:12, uniqueCount:12`
- observation: Active root and `java_clean` roots contain 12 duplicate FQCNs while the default duplicate mode is non-failing.
- inference: Packaging remains dependent on exclusion heuristics, and a newly kept duplicate can shadow an active owner without stopping the default build.
- reachability: The app jar task depends on duplicate scanning, but a blank mode parses to `filter=stereotype,onDup=none`.
- counterEvidence: Generated and hard-coded exclusions remove known duplicate classes from the app jar; duplicate source presence alone does not prove runtime shadowing.
- stapsStatus: partial
- confidence: 0.97
- evidenceSurface: static
- falsifier: The default jar task proves zero kept duplicates and fails whenever a future duplicate is not excluded.
- verification: Reproduce the bounded FQCN scan, then inspect the generated exclusions and kept count under the default mode.
- verificationClass: local_build
- verificationCost: low
- minimalRepairSeam: Make the existing duplicate task fail on kept duplicates while preserving intentional compatibility exclusions.
- dependencies: AUTO-01

### [GATE-01] Source-set hygiene gate checks presence only

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: source-ownership-gate
- uniquenessKey: checkSourceSetHygiene/directory-existence-only
- activeOwner: sourceSet=`root Gradle build`; path=`build.gradle.kts`; symbol=`checkSourceSetHygiene`; line=`156-169`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`build.gradle.kts`; symbol=`checkSourceSetHygiene`; line=`156-169`; fileSha256=`B4D9D4791F0F256CD312267564F889F1E0A30F88D95024FF85B5CB1451C397F4`
- observation: The named hygiene gate checks directory existence but not configured ownership, duplicates, or contamination.
- inference: A successful gate can coexist with duplicate FQCNs or unexpected content inside an active root.
- reachability: Root `check` depends on this task, whose assertions cover four directory paths and only log the inactive app Java directory.
- counterEvidence: Separate reports and the app duplicate task cover additional surfaces; they are not assertions inside this named gate.
- stapsStatus: contradicted
- confidence: 0.98
- evidenceSurface: static
- falsifier: The task graph or task implementation proves duplicate and contamination assertions are transitively mandatory for `checkSourceSetHygiene` itself.
- verification: Inspect task dependencies and inject a synthetic duplicate in an isolated checkout to confirm the gate becomes RED.
- verificationClass: local_build
- verificationCost: medium
- minimalRepairSeam: Extend the existing gate or its mandatory dependencies with configured-owner and kept-duplicate assertions; do not create a second hygiene framework.
- dependencies: FQCN-01

### [RC-02] Chat and RAG endpoints use different orchestration owners

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: rag-endpoint-divergence
- uniquenessKey: api-rag-vs-api-chat/orchestrator-owner
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/RagOrchestratorController.java`, `main/java/com/example/lms/service/ChatService.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbol=`query`, `continueChat`; line=`32-46`, `26-35`, `996`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/api/RagOrchestratorController.java`, `main/java/com/example/lms/service/rag/langgraph/RagOrchestratorFacade.java`, `main/java/com/example/lms/service/ChatService.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbols=`RagOrchestratorController.query`, `RagOrchestratorFacade.query`, `ChatService.continueChat`, `ChatWorkflow.continueChat`; lines=`32-46`, `56-108`, `26-35`, `996`; fileSha256=`72C6132747F44ADF78EACE7A95A67E8F3ABEFD4C00FFB171A56D332B30C26831`, `4E9E2858D00C3DC91C6FCA3CAE95C27F65C431191F32944C461084573E1EADA9`, `041C4AC4D1FC8C21D11FDC45E1C8567D8318B2499E91587BDA129E6C40297416`, `367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`
- observation: `/api/rag` uses `UnifiedRagOrchestrator`; `/api/chat` does not.
- inference: Retrieval policy, plan handling, telemetry, and fallback semantics can diverge between two public answer paths.
- reachability: `/api/rag/query` calls a facade that delegates to `UnifiedRagOrchestrator` on legacy/fallback modes; `/api/chat` delegates through `ChatService` to `ChatWorkflow`.
- counterEvidence: `ChatWorkflow` implements its own extensive retrieval pipeline, and no semantic mismatch was measured.
- stapsStatus: partial
- confidence: 0.94
- evidenceSurface: static
- falsifier: Contract tests prove both endpoints share one behaviorally equivalent orchestration boundary for the same request policy.
- verification: Feed an identical synthetic request to focused controller tests and compare selected plan, retrieval stages, and final evidence metadata.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Define one canonical orchestration contract at the controller-to-workflow boundary without duplicating either pipeline.
- dependencies: none

### [RC-03] Plan YAML is applied as hints, not executed as a full DSL

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: plan-dsl-ownership
- uniquenessKey: plan-hint-applier/unwired-dsl-sections
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/plan/PlanHintApplier.java`, `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`, `main/java/com/example/lms/service/rag/orchestrator/PlanDslExecutor.java`; symbol=`applyToGuardContext/applyToHintsAndMeta`, `applyPlanHints`, `execute`; line=`47-224`, `330-333`, `14`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/plan/PlanHintApplier.java`, `main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`, `main/java/com/example/lms/service/rag/orchestrator/PlanDslExecutor.java`; symbols=`PlanHintApplier`, `UnifiedRagOrchestrator.applyPlanHints`, `PlanDslExecutor`; lines=`47-224`, `330-333`, `14`; fileSha256=`EBB4EA2547138454F0005AAD66E8B396840568C06E3CFF6D6ACC065D869F76BC`, `E04E426F596A02F3F495BA8A08F8854468F7147095EBFF08A1391FDCE6BB0B1F`, `C39694F589C20980EE83FD9D43BDD4A8EF7F6D423C8297D157802AD9CF32FC1C`
- observation: Plan YAML is a partial hint overlay; `llm`, `fusion`, `when`, and `pipeline` are not execution owners.
- inference: Authors can mistake declarative plan sections for executable control flow even when runtime records them as unwired.
- reachability: `UnifiedRagOrchestrator` applies `PlanHintApplier` and records `planDsl.status=not_used` plus unwired keys.
- counterEvidence: Many retrieval, rerank, and guard knobs are explicitly mapped and do affect orchestration hints.
- stapsStatus: partial
- confidence: 0.97
- evidenceSurface: static
- falsifier: A call graph proves the named DSL sections are consumed by `PlanDslExecutor` on the active request path.
- verification: Run a focused plan fixture with sentinel values in each section and assert the owning runtime stages consume them.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Either wire only supported sections through the existing executor or reject unsupported keys at plan validation.
- dependencies: RC-02

### [RC-04] Plan selection and application repeat across chat paths

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: chat-plan-duplication
- uniquenessKey: chat-controller-workflow/repeated-plan-application
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/ChatApiController.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbol=`chatStream/chat/budget preflight`, `continueChat`; line=`1680-1698`, `3413-3434`, `3928-3943`, `1184-1201`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/api/ChatApiController.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbols=`plan pre-search blocks`, `ChatWorkflow.continueChat plan block`; lines=`1680-1698/3413-3434/3928-3943`, `1184-1201`; fileSha256=`C140D180CB9FA597D34391075FDBE44683DDF07E4F260C93B3F216C75FB934EA`, `367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`
- observation: Plan selection and application are duplicated across stream, sync, and workflow paths.
- inference: A future change can update one copy while leaving other request modes with stale plan caps or guard overrides.
- reachability: Three controller blocks and the workflow independently call `ensurePlanSelected`, `load`, and `applyToGuardContext`.
- counterEvidence: Reapplication may currently be idempotent for the mapped hints, and no divergent result was executed.
- stapsStatus: partial
- confidence: 0.98
- evidenceSurface: static
- falsifier: A focused stream/sync/workflow call-count test shows each request performs exactly one effective plan selection/application and receives identical plan caps despite the visible blocks.
- verification: Add stream/sync/workflow contract tests that count selection and application calls for one request.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Extract one existing chat-plan preparation boundary and make all three entry paths delegate to it.
- dependencies: RC-03

### [RC-05] Final model messages are assembled after PromptBuilder

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: prompt-boundary-bypass
- uniquenessKey: chatworkflow/post-builder-message-assembly
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ChatWorkflow.java`; symbol=`continueChat prompt build and message assembly`; line=`2780-2784`, `2942-2986`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/ChatWorkflow.java`; symbol=`PromptBuilder.build then msgs.add`; line=`2780-2784`, `2942-2986`; fileSha256=`367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`
- observation: System, policy, context, and user messages are added after the builder result.
- inference: The canonical prompt boundary does not own the complete message sequence delivered to the model.
- reachability: `continueChat` builds `ctxText` and instructions, then later creates a new message list containing policy, privacy, context, and user messages.
- counterEvidence: `PromptBuilder` still owns context text and instruction generation; the later assembly is explicit and ordered.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A prompt-boundary test proves the exact final model message sequence is returned by `PromptBuilder.build(PromptContext)` or its existing equivalent boundary.
- verification: Capture messages passed to the chat model in a focused `ChatWorkflow` test and compare them with the builder-owned output.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Move final message construction onto the existing `PromptBuilder`/`PromptContext` boundary without adding a parallel builder.
- dependencies: RC-04

### [RC-06] Final sigmoid gate receives synthetic certainty and only trims BLOCK

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: retrieval-final-gate
- uniquenessKey: dynamic-chain/final-sigmoid-constant-input-block-trim
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java`, `main/java/com/example/lms/guard/FinalSigmoidGate.java`; symbol=`applyFinalSigmoidGate`, `check/evaluate`; line=`1743-1758`, `87-129`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java`, `main/java/com/example/lms/guard/FinalSigmoidGate.java`; symbols=`applyFinalSigmoidGate`, `FinalSigmoidGate.check`; lines=`1743-1758`, `87-129`; fileSha256=`37D4F5D8BB58DE7EC8262615558130558CBB70E85B085EEC0BA5449A2DB1C5F5`, `F423AF9183B8FABAECDCB7314731B517214934EC0D9BB37FD6E35CF951931CA8`
- observation: Any nonempty accumulator is passed as composite `1.0` with risk `0`; `BLOCK` trims to three rather than suppressing all.
- inference: If the optional gate is active, evidence quality and policy risk do not drive its inputs, and a hard block can still preserve evidence for downstream use.
- reachability: The active handler calls this method after fusion and repair when the optional gate bean is present; bean activation was not observed.
- counterEvidence: The gate is optional, strong-evidence count is considered, non-HARD modes intentionally do not block, and no runtime activation was observed.
- stapsStatus: contradicted
- confidence: 0.94
- evidenceSurface: static
- falsifier: A unit test shows measured composite/risk inputs reach the gate and `BLOCK` produces the documented zero-evidence outcome.
- verification: Inject a recording gate, exercise empty/weak/risky accumulators, and assert arguments plus post-BLOCK contents.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Compute inputs from existing evidence signals and make the handler honor the gate result contract at `applyFinalSigmoidGate`.
- dependencies: none

### [RC-09] Canonical ExtremeZ handler lacks a proven chat caller

- classification: `evidence_needed`
- priorityInput: P2
- rootCauseClusterId: extremez-runtime-ownership
- uniquenessKey: extremez-handler/no-proven-production-caller
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`, `main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java`, `main/java/com/example/lms/service/rag/RagChainConfig.java`; symbol=`execute`, `aroundHybridRetrieve`, `extremeZSystemHandler`; line=`175`, `123-143`, `85-106`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`, `main/java/ai/abandonware/nova/orch/aop/ExtremeZBurstAspect.java`, `main/java/com/example/lms/service/rag/RagChainConfig.java`; symbols=`ExtremeZSystemHandler.execute`, `ExtremeZBurstAspect.aroundHybridRetrieve`, `RagChainConfig.extremeZSystemHandler`; lines=`175`, `123-143`, `85-106`; fileSha256=`FAC5467E9951D43DB3D74A3043670CD4979C57DC0BB789460A9A0E09FD4A6DCF`, `D85718B00C67DBD51012E8AD2B9423B5BC20E9F0C393E063F98B51021E43C0A9`, `478DF97669C81ECC22179B2713C135B77BDE6502E48115FD82BF885B3E544A29`
- observation: The canonical handler lacks a proven production caller while a separate disabled-by-default aspect performs chat bursting.
- inference: ExtremeZ behavior may be owned by the aspect while the richer canonical handler remains probe-only or dormant.
- reachability: The handler is constructed as a bean and referenced by probe configuration; the aspect intercepts `HybridRetriever.retrieve*`, but activation was not observed.
- counterEvidence: A bean plus an aspect pointcut is not runtime-selection proof, and probe use is a legitimate caller.
- stapsStatus: not_observed
- confidence: 0.82
- evidenceSurface: static
- falsifier: Fresh runtime traces show `ExtremeZSystemHandler.execute` handles a normal chat retrieval under the intended feature configuration.
- verification: Run one deterministic local chat with ExtremeZ enabled and assert caller-specific trace keys from the canonical handler versus aspect.
- verificationClass: runtime
- verificationCost: medium
- minimalRepairSeam: First choose one runtime owner; only then connect the existing canonical handler or retire duplicate aspect behavior.
- dependencies: RC-02

### [RC-10] Compression pointcut misses ChatWorkflow retrieval method

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: rag-aop-pointcut
- uniquenessKey: rag-compression/retrieve-vs-retrieveAll
- activeOwner: sourceSet=`main/java`; path=`main/java/ai/abandonware/nova/orch/aop/RagCompressionAspect.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbol=`aroundRetrieve pointcut`, `continueChat retrieveAll`; line=`67-69`, `2009-2012`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/ai/abandonware/nova/orch/aop/RagCompressionAspect.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbols=`@Around retrieve`, `HybridRetriever.retrieveAll`; lines=`67-69`, `2009-2012`; fileSha256=`7EFFD324B3F20440FE134E37DDEC9987D1EE2EF66F817F2DD7B2BA0EDF0CA6C8`, `367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`
- observation: The compression pointcut matches `retrieve(..)` while chat invokes `retrieveAll`.
- inference: If the compression aspect is active for chat, the `retrieveAll` call is outside the advice selected by the anchored expression.
- reachability: `ChatWorkflow.continueChat` invokes `hybridRetriever.retrieveAll` on its source-visible retrieval path; the AspectJ expression names only methods exactly called `retrieve`, but aspect activation was not observed.
- counterEvidence: Other retriever calls named `retrieve` can still be advised, and neither runtime aspect activation nor a chat advice invocation was observed.
- stapsStatus: contradicted
- confidence: 0.95
- evidenceSurface: static
- falsifier: An AOP-focused test proves `aroundRetrieve` executes for the exact `ChatWorkflow` `retrieveAll` invocation.
- verification: Spy on the aspect around a focused chat retrieval and assert the compression trace key is emitted by that invocation.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Correct the existing pointcut to the intended active method signature without broadening it beyond the hybrid owner.
- dependencies: none


### [RC-11] Cross-encoder ordering can undo earlier DPP diversity

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: rerank-ordering
- uniquenessKey: dpp-before-cross-encoder
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbol=`handle DPP stage`, `continueChat cross-encoder stage`; line=`722-740`, `2103-2166`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/rag/handler/DynamicRetrievalHandlerChain.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbols=`DppDiversityReranker.rerank`, `CrossEncoderReranker.rerank`; lines=`722-740`, `2103-2166`; fileSha256=`37D4F5D8BB58DE7EC8262615558130558CBB70E85B085EEC0BA5449A2DB1C5F5`, `367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`
- observation: DPP runs before a later cross-encoder ordering step that can undo diversity.
- inference: Relevance-only reordering can cluster similar documents after the diversity objective has already run.
- reachability: The dynamic handler DPP-reranks fused results before adding them to the accumulator; `ChatWorkflow` later cross-encoder-reranks the fused list.
- counterEvidence: Candidate caps preserve the DPP-selected pool, and no measured diversity regression was produced.
- stapsStatus: partial
- confidence: 0.91
- evidenceSurface: static
- falsifier: A focused ordering test proves the later reranker preserves the DPP diversity metric or DPP is reapplied after it.
- verification: Use a synthetic duplicate-heavy candidate set and compare source/domain diversity before DPP, after DPP, and after cross-encoder ordering.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Establish one final rerank order and preserve DPP as the final diversity constraint at the existing rerank seam.
- dependencies: RC-06

### [R01] Single-flight worker can strand its shared future on Error

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: singleflight-completion
- uniquenessKey: singleflight-worker/catch-exception-only
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/resilience/SingleFlightManager.java`; symbol=`run`; line=`28-49`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/resilience/SingleFlightManager.java`; symbol=`SingleFlightManager.run`; line=`28-49`; fileSha256=`0F945AB6CB91C7E79207D3A640B500F6DC93F9353E14B672FB50CD480DB671B8`
- observation: Worker catches `Exception` only; an `Error` can leave the shared future incomplete.
- inference: All callers joined to that key can wait forever after the worker exits through an uncaught `Error`.
- reachability: `computeIfAbsent` submits the owner task, callers invoke unbounded `fut.get()`, and the `finally` removal does not complete the future.
- counterEvidence: Ordinary checked and runtime exceptions are completed exceptionally; `Error` is uncommon.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A focused test shows an `Error` from the callable completes the shared future exceptionally and releases every waiter.
- verification: Run two callers on one key with a callable throwing a synthetic `AssertionError` and enforce a short test deadline.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Guarantee terminal completion in the existing worker boundary for every thrown `Throwable`, while preserving interrupt semantics.
- dependencies: none

### [R02] Single-flight timeout is logged but not enforced

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: singleflight-deadline
- uniquenessKey: singleflight/future-get-unbounded
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/resilience/SingleFlightManager.java`, `main/java/com/example/lms/resilience/SingleFlightAspect.java`; symbol=`run`, `SingleFlightAspect constructor/dedupe`; line=`28-49`, `30-46`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/resilience/SingleFlightManager.java`, `main/java/com/example/lms/resilience/SingleFlightAspect.java`; symbols=`fut.get`, `cache.singleflight.timeout-ms`; lines=`43-49`, `30-46`; fileSha256=`0F945AB6CB91C7E79207D3A640B500F6DC93F9353E14B672FB50CD480DB671B8`, `953D3078EB0A1950DE7546812BFD7E1B8150DC8624263C1E08F35B842010737C`
- observation: Callers use unbounded `Future.get`; the timeout property is logged but not enforced.
- inference: If the aspect is enabled, a slow or stuck owner can hold same-key requests past the configured deadline.
- reachability: The aspect reads `cache.singleflight.timeout-ms` only in its constructor log and delegates to `manager.run`, which calls parameterless `get()`; the aspect is disabled by default.
- counterEvidence: The aspect is disabled by default, only wraps selected search services, and no active runtime invocation was observed.
- stapsStatus: contradicted
- confidence: 0.93
- evidenceSurface: static
- falsifier: A focused test proves same-key callers receive the configured timeout outcome before a deliberately blocked owner completes.
- verification: Enable the aspect with a short timeout, block the advised call, and assert bounded caller completion plus cleanup.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Thread the existing timeout property into `SingleFlightManager.run` and define one bounded timeout cleanup policy.
- dependencies: R01

### [R03] Zero100 timeout leaves the worker running with copied trace context

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: timebox-cancellation
- uniquenessKey: zero100-timebox/fallback-without-cancel
- activeOwner: sourceSet=`main/java`; path=`main/java/ai/abandonware/nova/orch/aop/Zero100WebTimeboxAspect.java`; symbol=`callWithTimebox`; line=`79-190`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/ai/abandonware/nova/orch/aop/Zero100WebTimeboxAspect.java`; symbol=`callWithTimebox`; line=`79-190`; fileSha256=`00BD8A5BB17E1738039717A3C46D60E7E20E33C4D310814A9EF33BA051CA4B4C`
- observation: Timeout returns fallback without cancelling the worker, which retains parent trace access.
- inference: Work can continue after fallback and may mutate shared caches or copied request telemetry out of phase unless a detached-background ownership contract governs it.
- reachability: The aspect captures and installs `TraceStore`, guard, and MDC contexts in the submitted task; the timeout branch explicitly leaves `f.cancel` commented out, but a timeout execution was not observed.
- counterEvidence: The comment states completion is intentional for cache population, the task clears installed contexts in `finally`, and the required cancellation-versus-background-ownership contract is unresolved.
- stapsStatus: partial
- confidence: 0.90
- evidenceSurface: static
- falsifier: A concurrency test proves post-timeout worker completion cannot mutate request-scoped state and remains within a separately owned background-work contract.
- verification: Block the advised call beyond the timebox, return fallback, then observe whether the worker continues and writes request-scoped trace/cache state.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: At `callWithTimebox`, choose and enforce one explicit cancellation or detached-background ownership contract.
- dependencies: none

### [R04] ExtremeZ consumes interruption without restoring the flag

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: interrupt-correctness
- uniquenessKey: extremez-execute/interrupted-flag-lost
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`; symbol=`execute`; line=`311-319`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/burst/ExtremeZSystemHandler.java`; symbol=`ExtremeZSystemHandler.execute InterruptedException catch`; line=`311-319`; fileSha256=`FAC5467E9951D43DB3D74A3043670CD4979C57DC0BB789460A9A0E09FD4A6DCF`
- observation: `InterruptedException` is consumed without restoring the interrupt flag.
- inference: If the executing thread is interrupted on this branch, upstream cancellation and shutdown logic may no longer observe the interrupted state after the handler returns.
- reachability: The parallel execution catch records suppression telemetry but does not call `Thread.currentThread().interrupt()` or rethrow; an active interrupted execution was not observed.
- counterEvidence: The cancel-shield policy intentionally suppresses interrupts for child futures, and no source-backed caller contract proves that the current-thread flag must survive this boundary.
- stapsStatus: contradicted
- confidence: 0.93
- evidenceSurface: static
- falsifier: A focused test interrupts the executing thread and observes the flag restored or the interruption propagated at the handler boundary.
- verification: Exercise the parallel path with an interrupting future and assert current-thread interrupt state after `execute` returns.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Restore the interrupt at the existing catch boundary and retain the current bounded telemetry.
- dependencies: RC-09

### [R05] Flux errors emit a terminal stop record

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: llm-trace-terminal-state
- uniquenessKey: llm-flux/doFinally-error-as-stop
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/trace/LlmTraceAspect.java`; symbol=`aroundFlux`; line=`97-116`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/trace/LlmTraceAspect.java`; symbol=`Flux.doFinally`; line=`105-116`; fileSha256=`3E1FEB61D8455B4D2696F9BCB991741D06E4034E19BD1D5374BF8D0909F73E66`
- observation: Reactor `ON_ERROR` is recorded as finish `stop`.
- inference: Streaming failures become indistinguishable from normal completion in the final trace record.
- reachability: `doFinally` maps only `CANCEL` specially and maps every other `SignalType`, including `ON_ERROR`, to `stop`.
- counterEvidence: Mono calls have a separate `doOnError` record with finish `error`; Flux may have other error telemetry outside this aspect.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A failing Flux produces exactly one final LLM response record whose finish value is `error`.
- verification: Advise `Flux.error`, collect emitted trace records, and assert terminal count and finish classification.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Map `SignalType.ON_ERROR` at the existing `doFinally` terminal classifier and prevent contradictory duplicate terminals.
- dependencies: none

### [R06] Chat stream emission failures are ignored

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: chat-sse-emission
- uniquenessKey: chat-stream/tryEmitNext-result-ignored
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/chat/ChatStreamEmitter.java`; symbol=`emitUnderstanding`, `send`; line=`114-124`, `150-160`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/chat/ChatStreamEmitter.java`; symbols=`emitUnderstanding/send`; lines=`114-124`, `150-160`; fileSha256=`25FAF94DF98918CB90B95C5E38D3B799123AA42E0FC578C9443DAF5F90281564`
- observation: `tryEmitNext` results are ignored, so sink failures can be silent.
- inference: Overflow, cancellation, termination, or concurrent-emission rejection can drop UI events without a reason code or recovery path.
- reachability: Both helper methods call `sink.tryEmitNext(...)` and discard its `EmitResult`; only thrown exceptions are logged.
- counterEvidence: `tryEmitNext` itself does not throw for normal emission failures, and some dropped best-effort events may be acceptable.
- stapsStatus: partial
- confidence: 0.99
- evidenceSurface: static
- falsifier: A focused non-OK `EmitResult` test proves an existing upstream owner observes and handles the failure from both helpers despite their discarded return values.
- verification: Inject sinks returning representative failure results and assert bounded failure handling for each helper.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Handle `EmitResult` at the two existing emission calls with bounded low-cardinality diagnostics and no recursive retry loop.
- dependencies: none

### [R07] SSE diagnostics expose a global mixed-session stream

- classification: `structural_risk`
- priorityInput: P0
- rootCauseClusterId: telemetry-session-isolation
- uniquenessKey: diagnostics-sse/global-replay-stream
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/SseTelemetryDiagnosticsController.java`, `main/java/com/example/lms/telemetry/LoggingSseEventPublisher.java`; symbol=`stream`, `emit/asStream`; line=`25-30`, `43-80`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/api/SseTelemetryDiagnosticsController.java`, `main/java/com/example/lms/telemetry/LoggingSseEventPublisher.java`; symbols=`SseTelemetryDiagnosticsController.stream`, `LoggingSseEventPublisher.emit/asStream`; lines=`25-30`, `43-80`; fileSha256=`5E0534683BCE781CFA79B6C013959945CE3602A65FC70FFD7731E889B7A57DE6`, `CA28CCE126AFB643CE5A63A16E4B33079E8BACC9DF006612C0AD21A4F98301A9`
- observation: Diagnostics consume a global stream that permits sessionless and cross-request events.
- inference: A subscriber can receive replayed telemetry from unrelated requests unless a separate security boundary limits access.
- reachability: The controller calls parameterless `publisher.asStream()`; the publisher omits `sessionId` for blank input and only filters in its unused `asStream(sessionId)` overload.
- counterEvidence: Payloads and session identifiers are redacted, replay is bounded, and endpoint authorization was not audited here.
- stapsStatus: not_observed
- confidence: 0.96
- evidenceSurface: static
- falsifier: A controller/security test proves subscribers can access only authorized session events and sessionless events cannot cross request boundaries.
- verification: Publish two hashed-session events plus one sessionless event, subscribe through the controller, and assert the permitted event set.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Require an authorized session scope at the diagnostics controller and delegate to the existing filtered stream overload.
- dependencies: none

### [R08] Debug fingerprint aggregation has no eviction

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: debug-store-retention
- uniquenessKey: debug-event-store/unbounded-fingerprint-map
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/debug/DebugEventStore.java`; symbol=`byFingerprint`, `decide`; line=`56-63`, `371-378`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/debug/DebugEventStore.java`; symbols=`byFingerprint`, `decide`; lines=`56-63`, `371-378`; fileSha256=`1D2938818CD4BB5AC5101E9A38D9E261422EB6AFD1E07DD5B3A62758B5EC73B7`
- observation: The event ring is bounded but the fingerprint aggregation map is not evicted.
- inference: Unique fingerprints accumulate for process lifetime even after corresponding events leave the ring.
- reachability: Each emitted fingerprint reaches `computeIfAbsent`; only the separate ring enforces `maxSize`.
- counterEvidence: Fingerprints may be low-cardinality by convention, but no key bound or eviction enforces that convention.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A high-cardinality emission test proves `byFingerprint` remains within an explicit bound after old ring events are evicted.
- verification: Emit more unique fingerprints than `maxSize`, then inspect fingerprint count after the ring has trimmed.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Add bounded eviction to the existing aggregation map tied to the store retention policy.
- dependencies: none

### [R09] Probe terminal state uses a check-then-set race

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: debug-probe-concurrency
- uniquenessKey: probe-scope/volatile-check-then-set
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/debug/DebugEventStore.java`; symbol=`ProbeScope.success/failure`; line=`682-731`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/debug/DebugEventStore.java`; symbol=`DebugEventStore.ProbeScope`; line=`682-731`; fileSha256=`1D2938818CD4BB5AC5101E9A38D9E261422EB6AFD1E07DD5B3A62758B5EC73B7`
- observation: A volatile check-then-set allows concurrent success and failure terminal events.
- inference: If two threads race to complete one probe, both can observe `done=false` and emit contradictory terminal records.
- reachability: Both methods independently execute `if (done) return; done = true;` without compare-and-set or synchronization; no active concurrent terminal caller was proven.
- counterEvidence: Most probe scopes may be completed by a single thread, the race requires concurrent terminal calls, and no such execution was observed.
- stapsStatus: contradicted
- confidence: 0.94
- evidenceSurface: static
- falsifier: A repeated two-thread race test proves exactly one terminal event is emitted for every probe.
- verification: Synchronize two threads to call `success` and `failure` together and count emitted terminals over repeated iterations.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Replace the `done` field protocol with one atomic compare-and-set at the existing `ProbeScope` boundary.
- dependencies: R08


### [R10] Brain-state capture uses untracked common-pool work

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: brainstate-async-ownership
- uniquenessKey: brainstate-chat-aspect/runAsync-untracked
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/graph/BrainStateChatWorkflowAspect.java`; symbol=`captureConversationTurn`; line=`33-53`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/graph/BrainStateChatWorkflowAspect.java`; symbol=`captureConversationTurn`; line=`33-53`; fileSha256=`5321EA18DF899AF71B0D90A41842027FBAB9F36DEC36EE6AC93AFDFDEBC9698D`
- observation: Common-pool capture has no retained future or originating-request trace propagation.
- inference: Capture failures and shutdown completion cannot be joined to the request, and failure telemetry can lose request attribution.
- reachability: When three feature gates pass, the aspect calls `CompletableFuture.runAsync` without an executor or retaining the returned future.
- counterEvidence: Session, user, and assistant values are copied into the task, and capture is deliberately asynchronous.
- stapsStatus: not_observed
- confidence: 0.97
- evidenceSurface: static
- falsifier: A focused async test proves capture uses a managed executor, propagates redacted correlation context, and is observable through completion or failure.
- verification: Trigger capture with a controlled executor and assert executor ownership, correlation fields, failure reporting, and shutdown behavior.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Submit through an existing managed context-aware executor and retain only bounded completion telemetry at the aspect boundary.
- dependencies: none

### [R11] Failure-pattern recall reads the whole JSONL before tailing

- classification: `confirmed_defect`
- priorityInput: P2
- rootCauseClusterId: failure-memory-read-amplification
- uniquenessKey: failure-pattern-memory/readAllLines-before-tail
- activeOwner: sourceSet=`main/java`; path=`main/java/ai/abandonware/nova/orch/failpattern/FailurePatternMemoryService.java`; symbol=`readRows`; line=`277-300`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/ai/abandonware/nova/orch/failpattern/FailurePatternMemoryService.java`; symbol=`readRows`; line=`277-300`; fileSha256=`2CA4D5528B37B3B9D17D7D1C91D1DEC2C610DF920C7C4AF72906450C9F7EA7A3`
- observation: Recall reads the entire growing JSONL and only then keeps a tail.
- inference: Recall memory and latency scale with total file length rather than the bounded number of rows actually parsed.
- reachability: `recall` calls `readRows`; that method uses `Files.readAllLines` before `subList(start, lines.size())`.
- counterEvidence: Only the bounded tail is deserialized, and current file size was not inspected.
- stapsStatus: partial
- confidence: 0.99
- evidenceSurface: static
- falsifier: A large-file test proves bytes or lines read remain bounded as historical prefix size grows.
- verification: Compare allocations and elapsed time for equal tails behind small and large prefixes using a temporary synthetic JSONL.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Implement reverse-tail or bounded streaming at `readRows` while keeping the existing row schema and malformed-row behavior.
- dependencies: none

### [AL-01] Configured AutoLearn artifacts are absent from the snapshot

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: autolearn-runtime-evidence
- uniquenessKey: autolearn-configured-paths/artifacts-absent
- activeOwner: sourceSet=`main/resources`; path=`main/resources/application.yml`; symbol=`uaw.autolearn.dataset/agent-handoff`; line=`752-787`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/resources/application.yml`; symbol=`uaw.autolearn paths`; line=`752-787`; fileSha256=`67C2CCD7427E68EBF833A298CC110FE1F2C131D1E22EEFFBB166EDE9160C0AA9`; boundedExistence=`data/train_rag.jsonl:false, accepted:false, rejected:false, cycles:false, manifest:false`
- observation: Configured training/handoff artifacts are absent in the current checkout; runtime learning claims need evidence.
- inference: The checkout cannot substantiate that AutoLearn has produced, accepted, rejected, ingested, or handed off any runtime sample.
- reachability: YAML configures the paths and defaults `uaw.autolearn.enabled=false`; bounded existence checks found none of the five configured artifacts.
- counterEvidence: Runtime may use environment-overridden paths outside the checkout; those paths and processes were not inspected.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: static
- falsifier: Redacted runtime evidence supplies the effective paths and current artifact counts/hashes for a successful learning cycle.
- verification: Read an authorized runtime configuration report and count-only artifact manifest without exposing row contents.
- verificationClass: runtime
- verificationCost: medium
- minimalRepairSeam: none until runtime evidence identifies a broken owner; do not create placeholder learning artifacts.
- dependencies: none

### [AL-02] Rejected or quarantined rows inflate accepted ingest counts

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: autolearn-ingest-accounting
- uniquenessKey: train-rag-ingest/accepted-count-includes-quarantine
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`; symbol=`ingestNewSamples`, `copyValidationMeta`; line=`118-280`, `482-484`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`; symbols=`acceptedDocs`, `agentHandoffDecision`; lines=`118-280`, `369-380`, `482-595`; fileSha256=`2D42D0B0B574178C902E48D659EF4B82DF6F8E3E0EC2C0F367453D6A2B1D757D`
- observation: Parsable rejected/quarantined rows contribute to accepted ingest counts.
- inference: If AutoLearn ingest is activated with mixed decisions, `uaw.retrain.ingest.count` and the return value can overstate accepted learning.
- reachability: Validation metadata sets decision fields, but every queued and successfully flushed batch increments `acceptedDocs` by batch size without filtering; no active scheduler/request path or dataset artifact was observed.
- counterEvidence: AutoLearn defaults disabled, configured artifacts are absent, and the count may have been intended to mean successful projections rather than accepted training.
- stapsStatus: contradicted
- confidence: 0.94
- evidenceSurface: static
- falsifier: A focused mixed-decision ingest test returns and traces only validation-accepted rows as accepted.
- verification: Ingest one accepted, one rejected, and one quarantined synthetic row with a fake vector store and assert counts by category.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Split projection success from validation acceptance counters inside `ingestNewSamples` without changing raw row retention.
- dependencies: AL-01

### [AL-03] Shadow isolation depends on an optional DLQ bean

- classification: `structural_risk`
- priorityInput: P0
- rootCauseClusterId: autolearn-shadow-isolation
- uniquenessKey: vector-store/shadow-routing-optional-dlq
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`, `main/java/com/example/lms/service/VectorStoreService.java`; symbol=`upsertSegments`, `enqueue shadow routing`; line=`283-300`, `475-564`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`, `main/java/com/example/lms/service/VectorStoreService.java`; symbols=`upsertSegments`, `VectorStoreService.enqueue`; lines=`283-300`, `475-564`; fileSha256=`2D42D0B0B574178C902E48D659EF4B82DF6F8E3E0EC2C0F367453D6A2B1D757D`, `5A36C69EEAEE7651B54954DA461699FDBFA996FC44926818637F37EFE9C19D78`
- observation: Shadow isolation is applied only when an optional DLQ service exists.
- inference: With the DLQ bean absent, rows marked unverified and shadow-write can retain the primary SID and explicit ID.
- reachability: AutoLearn always enqueues shadow metadata; `VectorStoreService` enters shadow SID/ID rewriting only when `vectorShadowMergeDlqService != null`.
- counterEvidence: Other poison or ingest-protection guards may route a row to quarantine, and bean availability was not observed.
- stapsStatus: not_observed
- confidence: 0.98
- evidenceSurface: static
- falsifier: A context without `VectorShadowMergeDlqService` still routes every unverified AutoLearn row to an isolated SID and non-primary stable ID.
- verification: Construct `VectorStoreService` without the optional DLQ, enqueue a shadow row, and inspect the buffered ID/SID route.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Decouple mandatory shadow routing from optional DLQ persistence at the existing `enqueue` decision block.
- dependencies: AL-02

### [AL-04] Ingest checkpoint identity ignores file content

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: autolearn-checkpoint-identity
- uniquenessKey: train-rag-state/path-hash-only
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`; symbol=`loadState`, `setFileFingerprint`, `sameStateFile`; line=`782-820`, `848-869`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`; symbols=`IngestState/setFileFingerprint/sameStateFile`; lines=`79-93`, `782-820`, `848-869`; fileSha256=`2D42D0B0B574178C902E48D659EF4B82DF6F8E3E0EC2C0F367453D6A2B1D757D`
- observation: File identity is path-based; same-path equal-or-longer replacement can skip new prefix rows.
- inference: If AutoLearn ingest is active and a dataset is replaced at the same path without shrinking below the saved offset, processing can resume in unrelated content.
- reachability: State stores a hash and length of the absolute path string; reset occurs only for path mismatch or `offset > raf.length()`, but no active scheduler/request path or dataset artifact was observed.
- counterEvidence: AutoLearn defaults disabled, configured artifacts are absent, and stable append-only JSONL operation makes the saved byte offset appropriate when the file is never replaced.
- stapsStatus: contradicted
- confidence: 0.94
- evidenceSurface: static
- falsifier: A same-path replacement test with length at least the saved offset reprocesses the new prefix from byte zero.
- verification: Save a checkpoint, replace the file at the same path with distinct equal-or-longer content, and record which rows are read.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Add a bounded content identity signal to the existing checkpoint and reset only when append-only continuity is disproven.
- dependencies: AL-02

### [AL-05] Vector writes can succeed before checkpoint persistence

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: autolearn-checkpoint-ordering
- uniquenessKey: train-rag/upsert-before-best-effort-state-save
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`; symbol=`ingestNewSamples`, `saveState`; line=`241-263`, `802-820`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/uaw/autolearn/ingest/TrainRagIngestService.java`; symbols=`upsertSegments/saveState`; lines=`241-263`, `283-305`, `802-820`; fileSha256=`2D42D0B0B574178C902E48D659EF4B82DF6F8E3E0EC2C0F367453D6A2B1D757D`
- observation: Successful vector writes precede a best-effort checkpoint save, allowing replay after save failure.
- inference: If AutoLearn ingest is active and checkpoint persistence fails, a later run can repeat already-flushed rows while metrics again count them as newly accepted.
- reachability: Each batch calls `upsertSegments`, increments `acceptedDocs`, then calls `saveState`, which suppresses every exception; no active scheduler/request path or dataset artifact was observed.
- counterEvidence: AutoLearn defaults disabled, configured artifacts are absent, and stable explicit vector IDs can make replay an upsert rather than create duplicates in stores that honor supplied IDs.
- stapsStatus: partial
- confidence: 0.93
- evidenceSurface: static
- falsifier: A forced checkpoint-save failure after a successful flush cannot cause the same row to be projected or counted again on restart.
- verification: Inject a state-write failure after vector success, restart ingest from the old checkpoint, and count delegated IDs and accepted metrics.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Make checkpoint failure an explicit incomplete outcome and ensure replay accounting is idempotent at the existing batch boundary.
- dependencies: AL-04, AL-09

### [AL-06] Agent handoff writes lack shared-writer safety

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: autolearn-handoff-atomicity
- uniquenessKey: handoff-writer/unlocked-append-direct-manifest-write
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/uaw/autolearn/UawLearningAgentHandoffWriter.java`; symbol=`writeManifest`, `appendLine`; line=`217-240`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/uaw/autolearn/UawLearningAgentHandoffWriter.java`; symbols=`writeManifest/appendLine`; lines=`217-240`; fileSha256=`CBD9802075412B35CC32EF1F7A3FCE4E9AC02D935B7871C561ED5980F2FEE8F6`
- observation: JSONL and manifest writes lack cross-thread/process locking and atomic replacement.
- inference: If multiple handoff writers are active, they can interleave JSONL records or expose a partially written manifest to readers.
- reachability: JSONL uses `BufferedWriter` with `CREATE,APPEND` and no lock; manifest uses direct `Files.writeString`, but no active scheduler/request path or concurrent writer was proven.
- counterEvidence: AutoLearn defaults disabled, configured artifacts are absent, and a single scheduler may serialize normal writes in one process.
- stapsStatus: contradicted
- confidence: 0.91
- evidenceSurface: static
- falsifier: Concurrent process and thread tests produce parseable complete JSONL records and readers never observe a partial manifest.
- verification: Race two writers against a polling parser using temporary files and validate every observed document.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Add file locking for append and temp-file atomic replacement for manifest inside the existing writer.
- dependencies: AL-01

### [AL-07] Persisted learning text is not generally PII-redacted

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: autolearn-pii-boundary
- uniquenessKey: dataset-handoff/credential-only-redaction
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/uaw/autolearn/UawDatasetWriter.java`, `main/java/com/example/lms/debug/PromptMasker.java`, `main/java/com/example/lms/uaw/autolearn/UawLearningAgentHandoffWriter.java`; symbol=`safeRedact`, `mask`, `safePreview`; line=`53-55/166-175`, `18-66`, `461-469`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/uaw/autolearn/UawDatasetWriter.java`, `main/java/com/example/lms/debug/PromptMasker.java`, `main/java/com/example/lms/uaw/autolearn/UawLearningAgentHandoffWriter.java`; symbols=`safeRedact`, `PromptMasker.mask`, `safePreview`; lines=`53-55/166-175`, `18-66`, `461-469`; fileSha256=`70F347917A39B8017D1F356C61D4670DE45D511161B38D045C9255AB9006E91D`, `20CF0B7A90B0EEB9D454C8D6EF544753FE7A49B91ED57A065DF297F8A583D5D3`, `CBD9802075412B35CC32EF1F7A3FCE4E9AC02D935B7871C561ED5980F2FEE8F6`
- observation: General email, phone, and address PII can remain in persisted training/handoff text.
- inference: If the governing learning-data policy requires removal of these categories, accepted dataset rows and handoff previews can persist personal identifiers despite credential redaction.
- reachability: Dataset redaction delegates to `SafeRedactor`, which wraps `PromptMasker`; the anchored patterns omit general email, phone, and address shapes, but AutoLearn activation and a policy requiring those categories were not proven.
- counterEvidence: AutoLearn defaults disabled, configured artifacts are absent, upstream filters may reject some sensitive samples, and the exact PII persistence policy is unresolved.
- stapsStatus: contradicted
- confidence: 0.82
- evidenceSurface: static
- falsifier: A governing persistence policy explicitly permits these categories, or a focused serialization test proves an existing upstream owner blocks them before both write boundaries.
- verification: Pass synthetic non-secret PII fixtures through dataset and handoff serialization and inspect only categorical redaction results.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Add one policy-owned PII sanitizer immediately before the existing dataset/handoff write boundaries.
- dependencies: AL-06

### [AL-08] Attachment upload and deletion lifecycle is asymmetric

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: attachment-lifecycle
- uniquenessKey: attachments/sessionless-upload-session-delete-file-retention
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/AttachmentController.java`, `main/java/com/example/lms/service/AttachmentService.java`; symbol=`upload/delete`, `saveAll/delete`; line=`36-48/77-87`, `68-92/103-123`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/api/AttachmentController.java`, `main/java/com/example/lms/service/AttachmentService.java`; symbols=`upload/delete`, `saveAll/delete`; lines=`36-48/77-87`, `68-92/103-123`; fileSha256=`6FE3924B530B25947DB6DAA6A0750CDBA0FD82EFCF23FAFCDA2CA0618FED9982`, `7983616D3A61AE68013BCF68058ED67A6E1E6AAADC3E46D929F50F7A2525BA23`
- observation: Sessionless upload is allowed, delete requires a session, and physical stored files are not removed.
- inference: A sessionless upload may become undeletable through the API, and any logical deletion leaves its stored bytes behind.
- reachability: Upload calls `saveAll(files)` when session is blank; delete rejects blank session and `AttachmentService.delete` removes only maps/session links while commenting that physical deletion remains to be added.
- counterEvidence: `attachToSession` can later associate known IDs, and storage may have independent retention not inspected here.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: An endpoint test proves every successful sessionless upload can be authorized for deletion and the backing file no longer exists afterward.
- verification: Upload a temporary file without a session, exercise the documented ownership flow and delete endpoint, then assert metadata and file removal.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Define attachment ownership at upload and invoke a path-safe delete in the existing service deletion transaction.
- dependencies: none
### [AL-09] Federated writes do not preserve IDs consistently

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: vector-id-consistency
- uniquenessKey: federated-store/generated-caller-id-not-delegated
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/vector/FederatedEmbeddingStore.java`; symbol=`add/addAll`; line=`135-218`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/vector/FederatedEmbeddingStore.java`; symbol=`FederatedEmbeddingStore.add/addAll`; line=`135-218`; fileSha256=`191FFF695B2B3725EB322788CE79D4DC4AEB3F6CFCF5F251ABAE1F8868ACB835`
- observation: Returned or caller-supplied vector IDs are not consistently the IDs delegated upstream.
- inference: Callers can receive IDs that cannot address the stored vectors, and fallback stores can discard stable upsert IDs.
- reachability: `add(Embedding)` returns a generated ID but calls id-less `addAll`; the unsupported `addAll(ids,...)` fallback also delegates through id-less overloads.
- counterEvidence: `add(Embedding, TextSegment)` and stores supporting `addAll(ids,...)` receive the generated or caller IDs correctly.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: Recording upstream stores show every returned and caller-supplied ID is the exact delegated storage ID across all overloads and fallbacks.
- verification: Exercise each add overload against stores that support and reject ID-aware bulk writes, then compare returned, supplied, and recorded IDs.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Route every public add overload through the existing ID-aware fan-out and reject any fallback that cannot honor stable-ID semantics.
- dependencies: none

### [AL-10] KG chunk graph writes are not atomic per chunk

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: kg-write-atomicity
- uniquenessKey: neo4j-chunk-writer/separate-transactions
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/graph/Neo4jKgChunkWriter.java`; symbol=`writeChunks`; line=`41-115`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/graph/Neo4jKgChunkWriter.java`; symbol=`Neo4jKgChunkWriter.writeChunks`; line=`41-115`; fileSha256=`083F011FB61050473F1E6CC0FDFEA4877998EAA3EDB561B6AFD6A8BC2ECB232C`
- observation: Chunk, entity, and relation writes commit in separate transactions.
- inference: If a later entity or relation write fails, the earlier per-chunk transactions can leave a partially materialized graph unless compensation exists.
- reachability: `writeChunks` invokes `session.executeWrite` once for the chunk, once per entity, and once per relation; no active caller or database execution was observed.
- counterEvidence: MERGE statements make retries partially idempotent, and neither runtime activation nor an existing compensation policy was observed.
- stapsStatus: partial
- confidence: 0.92
- evidenceSurface: static
- falsifier: Driver/session evidence shows these calls share one enclosing transaction, or a forced relation failure triggers existing compensation that removes all earlier mutations for the logical chunk.
- verification: Use a Neo4j test fixture, fail one relation statement, and query for partial chunk/entity state.
- verificationClass: local_test
- verificationCost: high
- minimalRepairSeam: Execute one logical chunk's chunk/entity/relation statements inside a single existing Neo4j write transaction.
- dependencies: none

### [AL-12] AutoLearn reinforcement is disabled and stores pending candidates only

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: autolearn-recall-evidence
- uniquenessKey: autolearn-reinforcement/default-off-pending-only
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/uaw/autolearn/UawAutolearnProperties.java`, `main/java/com/example/lms/service/MemoryReinforcementService.java`; symbol=`MemoryReinforcement.enabled`, `reinforce AutoLearn validation path`; line=`238-252`, `226-227/280-313/875-876`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/uaw/autolearn/UawAutolearnProperties.java`, `main/java/com/example/lms/service/MemoryReinforcementService.java`; symbols=`MemoryReinforcement`, `upsertPendingCandidate`; lines=`238-252`, `226-227/280-313/875-876`; fileSha256=`631CC84BF4E25AD03672FB30997851FAA92926B04BD9573CF1A9C31606B05E5A`, `8F6A4B4653791BAA866DDE5039B7135ECBF4E18272EFAD58C5580B3EFF09A534`
- observation: Reinforcement is disabled by default and this path stores `PENDING`, not proven active recall.
- inference: Source presence cannot support a claim that AutoLearn samples are active in retrieval or improve answers.
- reachability: Both property binding and `@Value` default the feature off; the accepted validation path delegates to `upsertPendingCandidate`, which assigns `MemoryStatus.PENDING`.
- counterEvidence: Other memory promotion or recall paths may activate pending rows after verification, but no runtime state was inspected.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: static
- falsifier: Authorized runtime evidence shows the feature enabled, a specific pending sample promoted, and the same sample recalled on an active request.
- verification: Capture count-only state transitions and a same-sample recall trace without exposing sample text.
- verificationClass: runtime
- verificationCost: high
- minimalRepairSeam: none until promotion and recall evidence identifies a broken transition; preserve disabled-by-default behavior.
- dependencies: AL-01, AL-02

### [SEC-01] Provider-bound queries lack a general PII sanitizer

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: provider-query-privacy
- uniquenessKey: hybrid-brave-naver/control-cleaning-without-general-pii
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`, `main/java/com/example/lms/service/web/BraveSearchService.java`, `main/java/com/example/lms/service/NaverSearchService.java`; symbol=`search`, `sanitizeQuery`, provider call paths; line=`339`, `73-79/941-950`, `2207-2243/2937-2965`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`, `main/java/com/example/lms/service/web/BraveSearchService.java`, `main/java/com/example/lms/service/NaverSearchService.java`; symbols=`search/sanitizeQuery/provider calls`; lines=`339-404`, `73-79/941-950`, `2207-2243/2937-2965`; fileSha256=`525E7A2AEBFCF479B314F6E3876B05FB6B664AC4C8206B47BD5F3323ED815A49`, `3457D6FF23A97E80515ABB783A5D7EC9D47657D9615CB6C6DBC8800467DD5642`, `83E565914F3E3A2FBF40F3DC734748C7BED27C7AAD056906AD0C42DA81009B85`
- observation: Provider-bound queries are normalized for control characters and length but are not passed through a general PII sanitizer at these call boundaries.
- inference: If a request contains personal identifiers, an enabled external search provider can receive them unchanged apart from control cleaning.
- reachability: `ChatWorkflow` reaches the active hybrid retriever and the hybrid provider delegates query text to Brave or Naver; actual provider enablement and wire attempts were not observed.
- counterEvidence: Privacy guards can block web search for sensitive requests, and credentials may disable providers before any outbound call.
- stapsStatus: partial
- confidence: 0.90
- evidenceSurface: static
- falsifier: A focused call-path test proves every provider-bound query is categorically PII-sanitized or blocked before the Brave and Naver request builders.
- verification: Pass synthetic email/phone fixtures through the hybrid call path with recording provider doubles and inspect only categorical redaction outcomes.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Apply the existing policy-owned PII sanitizer once at the shared provider-bound query boundary without changing provider selection.
- dependencies: none

### [SEC-02] Credential-bearing provider base URLs lack an allowlist

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: provider-endpoint-trust
- uniquenessKey: brave-tavily-serp/configurable-base-url-with-credential
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/web/BraveSearchService.java`, `main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java`, `main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java`; symbol=`baseUrl request construction`; line=`135-136/941-950`, `43-47/69-74`, `54-58/162-175`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/web/BraveSearchService.java`, `main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java`, `main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java`; symbols=`baseUrl/request construction`; lines=`135-136/941-950`, `43-47/69-74`, `54-58/162-175`; fileSha256=`3457D6FF23A97E80515ABB783A5D7EC9D47657D9615CB6C6DBC8800467DD5642`, `BAF602C5ED158589EB7DCECE1C7928DB53FFC6054527888228056722AD8C6FB1`, `86BC3236DE45C2423758A00A18CF52360663F3B0676A5B389BBC5AAE55B61F9A`
- observation: Each provider accepts a configurable base URL and sends its credential without a source-backed scheme and provider-host allowlist.
- inference: A configuration mistake or hostile override could redirect a credential-bearing request to an unintended endpoint.
- reachability: The request builders consume the configured URL when their optional provider is enabled; no effective configuration or outbound call was observed.
- counterEvidence: Deployment configuration may be trusted and existing missing-key guards disable calls without usable credentials.
- stapsStatus: not_observed
- confidence: 0.94
- evidenceSurface: static
- falsifier: Configuration binding or a shared HTTP client guard rejects non-HTTPS and non-approved provider hosts before any credential is attached.
- verification: Instantiate each provider with synthetic disallowed URLs and assert request construction fails before a recording transport sees headers or body.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Validate scheme and provider-specific host at each existing initialization boundary before enabling the provider.
- dependencies: none


### [SEC-03] Official-only filtering restores an entirely rejected list

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: official-only-filter-integrity
- uniquenessKey: hybrid-strike-filter/all-rejected-return-input
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`; symbol=`applyStrikeFilterIfNeeded`; line=`2503-2524`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/search/provider/HybridWebSearchProvider.java`; symbol=`applyStrikeFilterIfNeeded`; line=`2503-2524`; fileSha256=`525E7A2AEBFCF479B314F6E3876B05FB6B664AC4C8206B47BD5F3323ED815A49`
- observation: In strike or official-only mode, the method returns the original input when every item is classified low trust.
- inference: The strongest filtering case deterministically re-admits all rejected results and violates the mode constraint.
- reachability: Active `search` returns through this method, and `GuardContext` can set strike or official-only mode on the request path.
- counterEvidence: Mixed lists retain the filtered subset, and `isLowTrustUrl` recognizes only configured markers.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A direct method-path test with official-only context and an all-low-trust list returns empty rather than the original list.
- verification: Invoke the active search filtering seam with synthetic low-trust URLs under official-only context and assert no input item survives.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Preserve the empty filtered result in `applyStrikeFilterIfNeeded`; do not change provider fallback policy.
- dependencies: SEC-04

### [SEC-04] Community hosts are official and no host is banned

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: domain-policy-semantics
- uniquenessKey: domain-whitelist/community-official-banned-false
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/auth/DomainWhitelist.java`; symbol=`isOfficialHost`, `isCommunity`, `isBanned`; line=`28-44`, `115-120`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/auth/DomainWhitelist.java`; symbols=`isOfficialHost/isCommunity/isBanned`; lines=`28-44`, `115-120`; fileSha256=`5C20702ACF6EF24F86DA34C4A40B0C2B675B78C85AA648E0C709781D411A7462`
- observation: `isOfficialHost` returns true for configured community hosts, while `isBanned` always returns false.
- inference: Official-only and banned-host decisions can express broader trust than their names imply when domain filtering is active.
- reachability: The component is active and used by evidence filtering and orchestration, but the governing product policy and effective allowlist were not observed.
- counterEvidence: High-risk `isAllowed` separately rejects community hosts, and the community treatment may be an intentional low-risk policy.
- stapsStatus: partial
- confidence: 0.96
- evidenceSurface: static
- falsifier: A documented policy explicitly defines these community hosts as official and defines the banned-host set as intentionally empty for every supported profile.
- verification: Run focused policy tests across official, community, unknown, and banned fixtures for each risk band.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Separate official, community, allowed, and banned predicates in `DomainWhitelist` while preserving risk-band policy.
- dependencies: none

### [SEC-05] Optional owner header override is not authentication-bound

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: request-owner-identity
- uniquenessKey: owner-key-resolver/header-override-caller-controlled
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/web/OwnerKeyResolver.java`; symbol=`resolveOwnerKey`; line=`24-34`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/web/OwnerKeyResolver.java`; symbol=`resolveOwnerKey`; line=`24-34`; fileSha256=`C1533AF2112EF7B6BFE21E54FA106C32BE10052AF1FE0C04B4B73F048EA18E68`
- observation: When `owner.header-override.enabled` is true, any nonblank `X-Owner-Key` is returned without an authentication-bound ownership check.
- inference: Enabling the option on an untrusted ingress would let a caller select another logical owner key.
- reachability: `ChatApiController` injects the resolver, but the override is disabled by default and effective configuration was not observed.
- counterEvidence: The source comment identifies spoofing risk and the default false value prevents the branch in ordinary configuration.
- stapsStatus: not_observed
- confidence: 0.97
- evidenceSurface: static
- falsifier: An enabled-override request test proves an upstream authenticated principal or trusted gateway binding authorizes the exact supplied owner key.
- verification: Enable the option in an isolated MVC test and send mismatched synthetic principal and header identities.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Bind header override acceptance to an existing authenticated/trusted-ingress assertion inside `OwnerKeyResolver`.
- dependencies: none

### [SEC-06] First-request ownership trusts unverified forwarded IP

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: request-owner-identity
- uniquenessKey: client-owner-key/xff-first-hop-trust
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/web/ClientOwnerKeyResolver.java`; symbol=`firstForwardedIpOrRemoteAddr`; line=`78-85`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/web/ClientOwnerKeyResolver.java`; symbol=`firstForwardedIpOrRemoteAddr`; line=`78-85`; fileSha256=`44DC9C89122EA46DADBF3BADF6A4B6F922364CE2795DDC6A953FC50160B2DF1B`
- observation: The fallback owner identity uses the first `X-Forwarded-For` value whenever present, without checking that the immediate peer is a trusted proxy.
- inference: On a direct or misconfigured ingress, a caller can influence the derived first-request owner identity.
- reachability: Multiple active controllers and `ChatHistoryServiceImpl` inject this component; whether infrastructure strips or rewrites the header was not observed.
- counterEvidence: Existing cookies or stable client IDs take precedence, and a trusted reverse proxy may normalize the header in production.
- stapsStatus: not_observed
- confidence: 0.94
- evidenceSurface: static
- falsifier: An ingress contract or request test proves client-supplied forwarding headers are ignored unless the direct peer is trusted.
- verification: Exercise direct and trusted-proxy request fixtures and compare only the resulting owner-key category/hash.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Consult the existing trusted-proxy boundary before using forwarded addresses in `ClientOwnerKeyResolver`.
- dependencies: none

### [SEC-07] Canonical MVC configuration does not register RuleBreak

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: rulebreak-enforcement-wiring
- uniquenessKey: webmvc/rulebreak-interceptor-unregistered
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/guard/rulebreak/RuleBreakInterceptor.java`, `main/java/com/example/lms/config/WebMvcConfig.java`; symbol=`RuleBreakInterceptor`, `addInterceptors`; line=`15-31`, `28-47`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/guard/rulebreak/RuleBreakInterceptor.java`, `main/java/com/example/lms/config/WebMvcConfig.java`; symbols=`RuleBreakInterceptor/WebMvcConfig.addInterceptors`; lines=`15-31`, `28-47`; fileSha256=`D17E15D72B60D5C722C4E2226FB9BE3C6096D9D452628395B6B76E18B8D7EA7F`, `A57C4DA479749597670942C38075AB4C244FD43C7FC25A77ABD77A0D7EF82BE1`
- observation: The canonical `WebMvcConfig.addInterceptors` registers only `ReqLogInterceptor`; the active RuleBreak MVC interceptor bean is not registered there.
- inference: Component creation alone does not cause an MVC `HandlerInterceptor` to execute, so the evaluator is bypassed on canonical MVC requests.
- reachability: `WebMvcConfig` is an active configuration covering `/**`, and the RuleBreak interceptor implements `HandlerInterceptor`; no alternative registration was found in the active package.
- counterEvidence: A separate similarly named WebFlux filter exists, but it is a different class and transport contract.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A focused MVC context inventory shows the canonical RuleBreak interceptor mapped to intended paths by another active `WebMvcConfigurer`.
- verification: Inspect the MVC handler mappings in an isolated context and send a synthetic RuleBreak request through `MockMvc`.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Inject and register the canonical interceptor in the existing `WebMvcConfig` with explicit intended paths.
- dependencies: SEC-10

### [SEC-08] Provider attempts lack request-scoped wire lineage

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: provider-wire-observability
- uniquenessKey: brave-tavily-serp/wire-attempt-ordinal-lineage
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/web/BraveSearchService.java`, `main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java`, `main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java`; symbol=`provider HTTP call seams`; line=`941-950`, `69-75`, `162-175`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/web/BraveSearchService.java`, `main/java/com/example/lms/service/rag/TavilyWebSearchRetriever.java`, `main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java`; symbols=`HTTP call seams`; lines=`941-950`, `69-75`, `162-175`; fileSha256=`3457D6FF23A97E80515ABB783A5D7EC9D47657D9615CB6C6DBC8800467DD5642`, `BAF602C5ED158589EB7DCECE1C7928DB53FFC6054527888228056722AD8C6FB1`, `86BC3236DE45C2423758A00A18CF52360663F3B0676A5B389BBC5AAE55B61F9A`
- observation: The provider call seams expose aggregate traces but no source-backed physical wire-attempt ordinal joined to request, provider, logical call, and retry lineage.
- inference: Existing status and count telemetry cannot by itself prove how many physical outbound attempts occurred for one request.
- reachability: These are active provider implementations, but no provider call or wire capture was performed and optional credentials can disable them.
- counterEvidence: HTTP clients or infrastructure agents outside these classes may supply wire-level tracing that was not inspected.
- stapsStatus: not_observed
- confidence: 0.88
- evidenceSurface: static
- falsifier: A source-backed transport interceptor or authorized trace shows a request-scoped tuple with monotonically increasing physical attempt ordinals for all three providers.
- verification: Use recording local transports and one synthetic request to compare logical-call and physical-attempt lineage without external calls.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Add a redacted request-scoped attempt tuple at the shared HTTP execution boundary only after the missing evidence is confirmed.
- dependencies: none

### [SEC-09] SerpApi credential is placed in the request URI

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: provider-credential-transport
- uniquenessKey: serpapi/query-param-api-key
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java`; symbol=`doSearch`; line=`162-175`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java`; symbol=`doSearch`; line=`162-175`; fileSha256=`86BC3236DE45C2423758A00A18CF52360663F3B0676A5B389BBC5AAE55B61F9A`
- observation: `doSearch` adds `apiKey` as the `api_key` query parameter before calling `RestTemplate.getForObject`.
- inference: If the optional provider is enabled, URI-level intermediaries or diagnostics outside this method may expose the credential despite the local no-log comment.
- reachability: The URI is deterministically built only for an enabled provider with a nonblank query; provider enablement, a wire attempt, and intermediary logging were not observed.
- counterEvidence: The provider is optional and credential-gated, the method does not itself log the URI, and the vendor may require query-parameter authentication.
- stapsStatus: implemented
- confidence: 0.96
- evidenceSurface: static
- falsifier: Current official vendor documentation and the active client prove a supported non-URI credential transport is unavailable and every URI observer is redacted by contract.
- verification: Use a recording request factory to assert whether the synthetic key appears in the constructed URI without making an external call.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Move authentication to a vendor-supported header or body at `SerpApiProvider.doSearch`; otherwise enforce end-to-end URI redaction at this seam.
- dependencies: SEC-02

### [SEC-10] RuleBreak token comparison is not constant-time

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: rulebreak-secret-validation
- uniquenessKey: rulebreak-evaluator/string-equals-token
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/guard/rulebreak/RuleBreakEvaluator.java`; symbol=`validate`; line=`80-87`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/guard/rulebreak/RuleBreakEvaluator.java`; symbol=`validate`; line=`80-87`; fileSha256=`CD9A0981DBCEE64FAA237A2E360402D948FEEAFD83603B554D2A90A0BE87E75C`
- observation: The configured and request tokens are compared with ordinary `String.equals` rather than a constant-time helper.
- inference: If the interceptor is reachable across a measurable boundary, comparison timing may reveal incremental information about the secret.
- reachability: The evaluator is an active component, but SEC-07 shows its canonical MVC interceptor is unregistered and no remote timing surface was observed.
- counterEvidence: Network jitter and current wiring reduce exploitability; existing configuration guards reject missing or dummy-like values first.
- stapsStatus: partial
- confidence: 0.91
- evidenceSurface: static
- falsifier: The active authentication boundary wraps this comparison in an existing constant-time verifier before any externally distinguishable result.
- verification: Add a focused source/behavior contract that the existing constant-time helper is invoked for equal-length synthetic tokens.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Replace only the token equality check in `RuleBreakEvaluator.validate` with the repository's existing constant-time comparison helper.
- dependencies: none

### [QTX-01] Hyphenated terms can be truncated as labelled output

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: query-transform-output-parsing
- uniquenessKey: query-transformer/correct-llm-greedy-hyphen-delimiter
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/transform/QueryTransformer.java`; symbol=`correctWithLLM`; line=`1669-1683`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/transform/QueryTransformer.java`; symbol=`correctWithLLM`; line=`1669-1683`; fileSha256=`EE3FDC3BE1ED605A57515ADCDD048985FB2AFBFA44E9F7C36DD1C1302087BF7F`
- observation: A greedy `.*[:：→>-]` pattern treats any hyphen as a label separator and retains only the suffix after the last match.
- inference: A valid correction such as `COVID-19` deterministically becomes `19`, changing retrieval meaning.
- reachability: `NaverSearchService` injects the active transformer and query correction can call this method on provider-bound queries.
- counterEvidence: The LLM may return the original query or a prefixed label using another delimiter; the method fails soft on exceptions.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A focused transformation test proves `COVID-19` and other compound tokens remain intact while supported answer labels are removed.
- verification: Stub the correction model to return synthetic hyphenated terms and assert exact output.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Restrict label stripping in `correctWithLLM` to anchored known prefixes or non-hyphen delimiters.
- dependencies: none

### [EVID-01] Evidence count overrides weak semantic coverage

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: evidence-gate-coverage
- uniquenessKey: evidence-gate/count-force-pass
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/guard/EvidenceGate.java`; symbol=`hasSufficientCoverage`; line=`68-90`, `94-146`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/guard/EvidenceGate.java`; symbol=`hasSufficientCoverage`; line=`68-90`, `94-146`; fileSha256=`C1DAEC62BA34DFDB656B6C025046F900B8FA0E6D0BF901FDCFD0607A2778FBF1`
- observation: Domain and count branches return true for two game/subculture items or four web items even when semantic coverage remains below threshold.
- inference: Several irrelevant but nonempty snippets can satisfy the evidence gate and permit an unsupported answer path.
- reachability: `FactVerifierService` and `RagEvidenceAttributionService` call this active gate on request evidence.
- counterEvidence: Other branches require positive combined coverage, and downstream attribution/citation guards may still downgrade the answer.
- stapsStatus: partial
- confidence: 0.97
- evidenceSurface: static
- falsifier: Focused tests with several lexically irrelevant snippets fail the gate for every count-based branch.
- verification: Supply a synthetic question plus four unrelated snippets and assert the gate remains false across relevant profiles/domains.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Make count-based rescue in `EvidenceGate` conditional on a bounded minimum semantic coverage or relevance quorum.
- dependencies: none


### [CITE-01] Citation allowlist ratio is diagnostic only

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: citation-gate-policy
- uniquenessKey: citation-gate/allowlist-ratio-unused
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/guard/CitationGate.java`; symbol=`decide`; line=`43-57`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/guard/CitationGate.java`; symbol=`decide/traceDecision`; line=`43-68`; fileSha256=`F1604AE7EB08EF90AF94242E845569CCC6C2103CB2AC2ABDED4DA0B95CBBB675`
- observation: `allowlistRatio` is forwarded only to tracing; PASS, WARN, and DEGRADE depend exclusively on source count and `logOnly`.
- inference: A source set with zero approved-domain coverage can pass whenever it meets the minimum count.
- reachability: `RagEvidenceAttributionService` calls `citationGate.ok` on active request evidence, currently passing a ratio of zero.
- counterEvidence: Other domain filtering may remove disallowed sources before this gate, and the caller's zero ratio signals incomplete integration.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A focused decision test shows identical source counts produce different decisions when allowlist ratio crosses the configured policy threshold.
- verification: Call `decide` with fixed sources and ratios 0.0 and 1.0 and assert policy-sensitive outcomes.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Incorporate a caller-supplied, policy-owned allowlist threshold into `CitationGate.decide` without changing count semantics.
- dependencies: SEC-04

### [CITE-02] Public URL sanitization removes functional locators

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: citation-url-sanitization
- uniquenessKey: evidence-attribution/url-query-fragment-stripped
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java`; symbol=`sanitizePublicUrl`; line=`1129-1154`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/RagEvidenceAttributionService.java`; symbol=`sanitizePublicUrl`; line=`1129-1154`; fileSha256=`AC204E14A658B96B4B91E188FAA35751DD81D2D430E0E9E9B804B8F2DD7951B8`
- observation: URL reconstruction preserves scheme, host, port, and path but unconditionally sets query and fragment to null.
- inference: Citations whose target is selected by query parameters or fragments can become non-equivalent or lose precise location.
- reachability: Active evidence attribution sanitizes metadata and text-extracted URLs through this method before public evidence construction.
- counterEvidence: Removing query parameters limits token leakage and tracking; many cited pages remain useful from the path alone.
- stapsStatus: partial
- confidence: 0.96
- evidenceSurface: static
- falsifier: A URL-policy contract proves every supported source locator remains semantically equivalent after removing all query and fragment components.
- verification: Test synthetic allowlisted functional parameters/fragments and sensitive tracking parameters against expected public URLs.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Add an allowlisted locator policy inside `sanitizePublicUrl` while continuing to remove credentials and sensitive/tracking fields.
- dependencies: none

### [FUSE-01] RRF document identity is a 32-bit text hash

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: fusion-document-identity
- uniquenessKey: reciprocal-rank-fuser/normalized-text-hashcode
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/fusion/ReciprocalRankFuser.java`; symbol=`keyOf`; line=`63-68`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/fusion/ReciprocalRankFuser.java`; symbol=`keyOf`; line=`63-68`; fileSha256=`E759B611BABA67ABB594963C0970365BBE9B67F895A047E57C210D42D349E70F`
- observation: `keyOf` ignores document metadata and returns `Integer.toHexString(normalizedText.hashCode())`.
- inference: Distinct documents with a Java hash collision are deterministically merged, while the same document with text variation is split.
- reachability: The active fuser uses the key for both first-seen identity and score accumulation on every fused content item.
- counterEvidence: Collisions are uncommon in small result sets, and normalized text deduplication intentionally merges exact textual duplicates.
- stapsStatus: partial
- confidence: 0.99
- evidenceSurface: static
- falsifier: Focused fixtures with known Java string-hash collisions remain distinct and stable metadata-equivalent documents merge across text formatting changes.
- verification: Fuse collision fixtures plus shared-document metadata fixtures and inspect result identity and scores.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Prefer stable source/document metadata in `keyOf`, with a collision-resistant normalized-text digest only as fallback.
- dependencies: none

### [HYBRID-01] Retrieval deadlines cancel without interrupting work

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: retrieval-timeout-cancellation
- uniquenessKey: hybrid-retriever/cancel-false-branch-fusion
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/HybridRetriever.java`; symbol=`retrieveAll deadline paths`; line=`1746-1764`, `1881-1956`, `2012-2016`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/HybridRetriever.java`; symbol=`deadline cancellation paths`; line=`1746-1764`, `1881-1956`, `2012-2016`; fileSha256=`C2630D06E9B7A6C9D7F74C1F400A364F0BEFE2703B0B9B86615178FA5879684E`
- observation: Timed-out branch futures and fusion futures are cancelled with `cancel(false)`.
- inference: Running retrieval or fusion tasks may continue consuming pool capacity after the caller has returned a timeout fallback.
- reachability: `ChatWorkflow` calls the active retriever, but whether providers honor their own timeouts and whether work outlives requests was not observed.
- counterEvidence: The code deliberately avoids interrupt toxicity, purges queues, and many HTTP clients have bounded connect/read timeouts.
- stapsStatus: partial
- confidence: 0.93
- evidenceSurface: static
- falsifier: A saturation test proves all timed-out running tasks stop or release bounded resources before the next request's capacity is affected.
- verification: Use blocking interrupt-aware and interrupt-ignoring retriever doubles, expire the deadline, and inspect active worker/queue counts.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Add cooperative cancellation or task-owned abort at the existing branch/fusion boundary without interrupting unrelated pooled work.
- dependencies: none

### [EMPTY-01] Failure empties erase retrieval input and fallback stage

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: retrieval-integrity-telemetry
- uniquenessKey: hybrid-retriever/empty-evidence-hardcoded-zero-none
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/HybridRetriever.java`; symbol=`emptyEvidence`; line=`2072-2078`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/HybridRetriever.java`; symbol=`emptyEvidence`; line=`2072-2078`; fileSha256=`C2630D06E9B7A6C9D7F74C1F400A364F0BEFE2703B0B9B86615178FA5879684E`
- observation: Every failure/timeout empty writes `retrieval.integrity.inputCount=0` and `fallbackStage=none`, regardless of submitted queries or the fallback path.
- inference: Telemetry deterministically misstates attempted input and cannot distinguish a pre-input empty from a failed post-submission retrieval.
- reachability: Multiple active `retrieveProgressive` and `retrieveAll` failure branches return through this helper.
- counterEvidence: The helper records source, reason, final count, and elapsed time, and other trace keys may retain branch counts.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A timeout after submitting nonzero queries records the true input count and a non-`none` fallback stage in the integrity envelope.
- verification: Force a post-submission timeout with two synthetic queries and assert the resulting count-only trace fields.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Pass actual input count and terminal/fallback stage into `emptyEvidence` from each existing call site.
- dependencies: HYBRID-01

### [PM-01] Prompt content crosses trust roles

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: prompt-role-boundary
- uniquenessKey: standard-builder-chat-expander/content-role-flattening
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/prompt/StandardPromptBuilder.java`, `main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/com/example/lms/service/answer/AnswerExpanderService.java`; symbol=`build`, prompt message assembly, `expand`; line=`40-78`, `2945-2986`, `124-128`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/prompt/StandardPromptBuilder.java`, `main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/com/example/lms/service/answer/AnswerExpanderService.java`; symbols=`build/message assembly/expand`; lines=`40-78`, `2945-2986`, `124-128`; fileSha256=`6BB58C1EE06B4395E006A4748F83C6B84BE9E7510CF56AA416A4FF022B41F83A`, `367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`, `A54AF5D78D7AB46995D03A63E4588A9421C4E262757050215C7ED52F63C30F5D`
- observation: The builder mixes supplied system instruction and contextual/user-controlled text into one string that `ChatWorkflow` sends as system role, while `AnswerExpanderService` sends a builder string containing its trusted instruction as user role.
- inference: Trust precedence depends on string layout rather than message roles, increasing instruction-confusion and injection risk.
- reachability: Both `ChatWorkflow` prompt assembly and the injected answer expander call these active seams.
- counterEvidence: `ChatWorkflow` sends the final query separately as user role and adds some trusted policies as separate system messages.
- stapsStatus: contradicted
- confidence: 0.97
- evidenceSurface: static
- falsifier: Message-capture tests prove all untrusted evidence/query text is user/tool role and every trusted instruction remains system/developer role in both flows.
- verification: Capture message roles for synthetic instruction-shaped evidence and an expansion request.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Preserve typed trust roles at the `PromptBuilder` output boundary used by `ChatWorkflow` and `AnswerExpanderService`.
- dependencies: none

### [PM-02] Loaded conversation context is omitted by the standard builder

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: prompt-context-rendering
- uniquenessKey: standard-builder/history-last-answer-unused
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/prompt/PromptContext.java`, `main/java/com/example/lms/prompt/StandardPromptBuilder.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbol=`history/lastAssistantAnswer`, `build`, context builder; line=`38-41/151-153`, `40-185`, `2408-2414/2506-2512`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/prompt/PromptContext.java`, `main/java/com/example/lms/prompt/StandardPromptBuilder.java`, `main/java/com/example/lms/service/ChatWorkflow.java`; symbols=`history/lastAssistantAnswer/build`; lines=`38-41/151-153`, `40-185`, `2408-2414/2506-2512`; fileSha256=`F6C0BBEA6E6F20AE586562178CBE277B0AF91CB53C17D1151385DDBFA65A7F68`, `6BB58C1EE06B4395E006A4748F83C6B84BE9E7510CF56AA416A4FF022B41F83A`, `367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`
- observation: `ChatWorkflow` loads recent history and the last assistant answer into `PromptContext`, but `StandardPromptBuilder.build` does not render those fields.
- inference: Follow-up model calls can lose conversational context even though the workflow paid to load it.
- reachability: The standard builder is enabled when missing and injected into the active chat workflow.
- counterEvidence: Disambiguation and several deterministic history fallbacks consume recent history before prompt construction.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A prompt-capture test shows distinct recent-history and last-answer markers in the final standard-builder output.
- verification: Build a context with unique synthetic history/last-answer strings and assert bounded, role-safe rendering.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Render bounded recent history and last answer once in `StandardPromptBuilder` with explicit untrusted-context labels.
- dependencies: PM-01

### [PM-03] Evidence rendering lacks an aggregate model-window cap

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: prompt-window-budget
- uniquenessKey: standard-builder-compressor/no-aggregate-input-cap
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/prompt/StandardPromptBuilder.java`, `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java`; symbol=`build`, compression fail-soft paths; line=`74-185/240-400`, `230-370/374-460/463-541`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/prompt/StandardPromptBuilder.java`, `main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java`; symbols=`evidence rendering/compression fail-soft`; lines=`74-185/240-400`, `230-370/374-460/463-541`; fileSha256=`6BB58C1EE06B4395E006A4748F83C6B84BE9E7510CF56AA416A4FF022B41F83A`, `514A04E9206D08128E13B07193D4CDB979B2FD9D8CE18D4BE30E4018BE80225E`
- observation: Evidence sections have per-item limits but no aggregate cap tied to the selected model window, and fail-soft compression paths can preserve original input.
- inference: Large combined context can exceed a route's usable window or crowd out the final question and policies.
- reachability: The active builder renders multiple evidence collections; actual model window, accumulated size, and compressor failure were not observed.
- counterEvidence: Retrieval top-K, per-field truncation, and model/client limits bound portions of the prompt.
- stapsStatus: partial
- confidence: 0.91
- evidenceSurface: static
- falsifier: A model-aware prompt budget test proves total input always stays below the selected route's reserved window even when compression fails.
- verification: Generate maximal synthetic context for the smallest supported window and count encoded or conservative estimated input tokens.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Enforce one model-aware aggregate budget at the final `PromptBuilder` boundary with deterministic section precedence.
- dependencies: PM-02

### [PM-04] Output token caps have multiple non-adjudicated owners

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: model-output-budget
- uniquenessKey: chat-profile-dto-bean-route/divergent-output-caps
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/dto/ChatRequestDto.java`, `main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/com/example/lms/config/LlmConfig.java`; symbol=`maxTokens`, profile target, model bean caps; line=`73`, `5907-6142`, `193/278/332/388`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/dto/ChatRequestDto.java`, `main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/com/example/lms/config/LlmConfig.java`; symbols=`maxTokens/profileTarget/configured caps`; lines=`73`, `5907-6142`, `193/278/332/388`; fileSha256=`BBECE1E3FC13A49248D3841906B30C8CD42BF84F2296ECEDFAF6B296D6FED329`, `367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`, `599ADC5F474DD94347B73F86AA3822BFF68A79B6A495DE9378B3EED3DC45E7B9`
- observation: Profile target, request DTO, statically configured bean, and dynamically rebuilt route can supply different output caps.
- inference: The effective cap and accounting source can vary with route/rebuild selection rather than a single precedence contract.
- reachability: `ChatWorkflow` passes DTO caps into dynamic construction and usage accounting; effective route/profile combinations were not executed.
- counterEvidence: Different models and purposes intentionally use different caps, and `ChatUsageLedger` records configured-cap context.
- stapsStatus: partial
- confidence: 0.89
- evidenceSurface: static
- falsifier: A precedence matrix test proves one documented effective cap for every profile, DTO override, static bean, and dynamic route combination.
- verification: Run parameterized configuration-only tests and compare requested, configured, effective, and ledger cap values.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Centralize cap adjudication at the existing model-invocation budget seam while preserving model-specific maximums.
- dependencies: PM-03

### [PM-05] Blank model output is reported as success

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: model-attempt-outcome
- uniquenessKey: chat-workflow/blank-ai-success-before-retry
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/com/example/lms/llm/OllamaNativeChatModel.java`; symbol=`callWithRetryReportingSuccess`, `chat`; line=`6125-6153`, `191-206`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/com/example/lms/llm/OllamaNativeChatModel.java`; symbols=`callWithRetryReportingSuccess/OllamaNativeChatModel.chat`; lines=`6125-6153`, `191-206`; fileSha256=`367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`, `1A6C6F45A78073F359CAED0118912C6EC0EB15CAC867F2E89BFADA1BF145CEA1`
- observation: `ChatWorkflow` calls the success sink and returns immediately even when `AiMessage.text()` is blank; the native adapter can return a blank `AiMessage` after recording failure.
- inference: Blank-response retry/fallback is skipped while success telemetry and health can be credited.
- reachability: The active draft loop calls this method, and the configured factory can select `OllamaNativeChatModel` for supported local routes.
- counterEvidence: Downstream fallback composition may replace a blank final answer, but it cannot undo the already reported model success.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A blank-response model double causes no success callback and enters the bounded retry/fallback path.
- verification: Invoke the draft call with a model returning blank `AiMessage` and assert outcome telemetry plus attempt count.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Validate nonblank semantic output before `successSink.accept` in `callWithRetryReportingSuccess`.
- dependencies: none

### [PM-06] Strict single attempt does not bound nested gateway fallback

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: model-attempt-budget
- uniquenessKey: chat-strict-attempt/fallback-aware-nested-second-call
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`, `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java`; symbol=`strictSingleAttempt`, `route`, `chat`; line=`6002-6037/6125-6153`, `194-219`, `68-116`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`, `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java`; symbols=`strictSingleAttempt/route/chat`; lines=`6002-6037/6125-6153`, `194-219`, `68-116`; fileSha256=`367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`, `31894345507F0977B6DBADB907803EB4BCBD94B29796DF2BDB4F77B95214E2B8`, `7BDC2FD19FE05E288ADB69222AE89297A9EC4B7D4B44ACF83AB603CA25733272`
- observation: `strictSingleAttempt` bounds the outer workflow loop, while `FallbackAwareChatModel.chat` can call primary then fallback inside one outer invocation.
- inference: A request labelled maximum one attempt can make two physical model calls when the selected model remains fallback-aware.
- reachability: The aspect constructs fallback-aware models when fallback is configured; the strict path may rebuild a model and the exact runtime wrapper was not observed.
- counterEvidence: Strict dynamic rebuilding passes zero retries and can yield a model without the aspect wrapper, depending on route/factory wiring.
- stapsStatus: not_observed
- confidence: 0.88
- evidenceSurface: static
- falsifier: A strict-mode call with a fallback-configured route records exactly one physical attempt for every primary failure class.
- verification: Use recording primary/fallback models under the real strict route construction and count physical invocations.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Propagate the request attempt budget into `FallbackAwareChatModel` and suppress nested fallback when exhausted.
- dependencies: SEC-08

### [PM-07] ENFORCE fallback bypasses eligibility evaluation

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: gateway-route-eligibility
- uniquenessKey: llm-router-aspect/enforce-fallback-unprobed
- activeOwner: sourceSet=`main/java`; path=`main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`, `main/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeService.java`; symbol=`route ENFORCE branch`, `evaluate`; line=`178-190/232-247`, `57-142`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`, `main/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeService.java`; symbols=`ENFORCE fallback/evaluate`; lines=`178-190/232-247`, `57-142`; fileSha256=`31894345507F0977B6DBADB907803EB4BCBD94B29796DF2BDB4F77B95214E2B8`, `75E5618093DF54D62618F07A4B06D2C94EC7DE761EB78B20619DB036DBC5B7FE`
- observation: When the primary is ineligible in ENFORCE mode, the aspect selects and builds an enabled fallback without calling `evaluate` for that fallback.
- inference: The enforced route can move to a fallback that fails credential, context, embedding, health, or score eligibility checks.
- reachability: The branch requires ENFORCE plus configured cloud fallback; effective policy and route configuration were not observed.
- counterEvidence: `fallbackSelection` rejects missing, disabled, self-referential fallbacks, and model construction may fail safely on missing credentials.
- stapsStatus: partial
- confidence: 0.97
- evidenceSurface: static
- falsifier: An ENFORCE test with an ineligible primary and independently ineligible fallback rejects both without invoking either model.
- verification: Configure synthetic primary/fallback failure reasons and capture evaluation plus invocation events.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Evaluate the selected fallback with `HybridLlmGatewayProbeService` before `buildRoutedModel` in the ENFORCE branch.
- dependencies: PM-08

### [PM-08] Probe timeout and TTL settings have no observed consumer

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: gateway-probe-implementation
- uniquenessKey: gateway-properties/probe-timeout-ttl-unconsumed
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/llm/gateway/LlmGatewayProperties.java`, `main/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeService.java`; symbol=`Probe.timeoutMs/ttlMs`, `evaluate`; line=`83-110`, `57-142`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/llm/gateway/LlmGatewayProperties.java`, `main/java/com/example/lms/llm/gateway/HybridLlmGatewayProbeService.java`; symbols=`Probe.timeoutMs/ttlMs/evaluate`; lines=`83-110`, `57-142`; fileSha256=`3CF1452DC68DB546A128F98DFAD8C4F250866C2E9D43D19438DAF80F30DE05B7`, `75E5618093DF54D62618F07A4B06D2C94EC7DE761EB78B20619DB036DBC5B7FE`
- observation: Properties expose probe timeout and TTL, but `HybridLlmGatewayProbeService.evaluate` performs no transport probe or TTL cache lookup using them.
- inference: Configuration names imply freshness-bounded active probing that the inspected owner does not provide.
- reachability: The service is active and called by routing, but another transport/cache consumer or runtime probe was not found or observed.
- counterEvidence: Eligibility intentionally uses configuration, model specs, and recorded health; timeout/TTL may be reserved for a separate future implementation.
- stapsStatus: not_observed
- confidence: 0.95
- evidenceSurface: static
- falsifier: A source-backed active collaborator consumes both settings to perform and cache bounded probe results used by `evaluate`.
- verification: Trace references to both property getters and run a component inventory without outbound calls.
- verificationClass: local_read_only
- verificationCost: low
- minimalRepairSeam: none until the intended probe contract is established; then consume or remove the settings at the existing gateway eligibility owner.
- dependencies: none

### [PM-09] Escalation bypasses normal model serveability checks

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: model-route-serveability
- uniquenessKey: policy-router/escalate-unchecked-high-model
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java`; symbol=`escalate`; line=`632-635`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java`; symbol=`escalate`; line=`632-635`; fileSha256=`FB75AACF62997839541AAC21197A5E9ABF02941D9AB32896DD4320F7521DF980`
- observation: `escalate` unconditionally returns `highModel`, while normal routing contains explicit high-model serveability checks.
- inference: If the injected high route is not serveable, escalation can select a route that normal promotion would block.
- reachability: `ModelRouterAdapter` delegates active escalation calls to this method, but high-model unavailability and harmful post-selection behavior were not observed.
- counterEvidence: The injected high model may always be serveable in supported configurations or may be a disabled fail-soft model that contains failure at invocation time.
- stapsStatus: partial
- confidence: 0.95
- evidenceSurface: static
- falsifier: An authoritative route contract plus a focused escalation test proves `highModel` is always serveable in supported configurations or that its fail-soft result is the accepted escalation behavior.
- verification: Construct the router with an unservable high route and invoke escalation through `ModelRouterAdapter`.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Reuse the normal serveability adjudicator inside `PolicyBasedModelRouter.escalate`.
- dependencies: none

### [PA-01] Derived plan booleans overwrite explicit overrides

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: execution-plan-precedence
- uniquenessKey: plan-applier/derived-false-overwrites-explicit
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/orchestration/ExecutionPlanApplier.java`; symbol=`applyOverrides`; line=`66-102`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/orchestration/ExecutionPlanApplier.java`; symbol=`applyOverrides`; line=`66-102`; fileSha256=`6EB0334B61785060F72734949DFC887E52ADA599539BA013423DA8000484E1BD`
- observation: `applyOverrides` writes derived mode booleans, including false, directly into `GuardContext` without checking existing plan/request overrides.
- inference: If explicit request/plan values are intended to outrank derived execution-plan values, the current writes can erase those caller constraints.
- reachability: `OrchestrationSignals.compute` invokes the applier and `ChatWorkflow` computes signals on the active request before and after preprocessing.
- counterEvidence: Some numeric overrides use max semantics, the resolver may intentionally own final precedence, and no authoritative precedence contract was found.
- stapsStatus: partial
- confidence: 0.95
- evidenceSurface: static
- falsifier: An authoritative existing precedence artifact states that derived execution-plan booleans intentionally outrank explicit request/plan values, and a focused combination test shows `applyOverrides` matches that contract.
- verification: Parameterize explicit and derived boolean combinations and inspect final `GuardContext` values.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Encode the authoritative precedence decision once inside `ExecutionPlanApplier.applyOverrides` if current behavior does not match it.
- dependencies: none

### [PA-02] Execution plans sample retrieval signals before retrieval

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: execution-plan-signal-timing
- uniquenessKey: chat-plan/derive-retrieval-before-current-retrieval
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/com/example/lms/orchestration/ExecutionPlanApplier.java`; symbol=`continueChat orchestration`, `deriveSignals`; line=`1502-1508/1629-1635`, `43-63`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/ChatWorkflow.java`, `main/java/com/example/lms/orchestration/ExecutionPlanApplier.java`; symbols=`OrchestrationSignals.compute/deriveSignals`; lines=`1502-1508/1629-1635`, `43-63`; fileSha256=`367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`, `6EB0334B61785060F72734949DFC887E52ADA599539BA013423DA8000484E1BD`
- observation: `deriveSignals` reads retrieval outcome traces such as output count, starvation, authority, and contradiction when `ChatWorkflow` computes the plan before current-request retrieval.
- inference: The plan can use absent defaults or stale same-thread/request traces instead of the current retrieval outcome.
- reachability: The active workflow computes signals twice before its main retrieval stages and each compute applies a plan.
- counterEvidence: `TraceStore` is request-scoped and earlier preprocessing may intentionally contribute some signals.
- stapsStatus: contradicted
- confidence: 0.97
- evidenceSurface: static
- falsifier: A trace-isolated test proves current-request retrieval outcome exists before `deriveSignals`, or the pre-retrieval plan ignores all outcome-only keys.
- verification: Record event order for plan application and retrieval-integrity writes in one synthetic request.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Split pre-retrieval intent signals from post-retrieval outcome signals at `ExecutionPlanApplier.deriveSignals`.
- dependencies: EMPTY-01

### [PA-03] Overdrive producer and plan consumer use different trace keys

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: execution-plan-signal-contract
- uniquenessKey: overdrive-trace/producer-consumer-key-mismatch
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/orchestration/ExecutionPlanApplier.java`, `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java`; symbol=`deriveSignals`, `traceDecision`; line=`51-56`, `303-346`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/orchestration/ExecutionPlanApplier.java`, `main/java/com/example/lms/service/rag/overdrive/OverdriveGuard.java`; symbols=`deriveSignals/traceDecision`; lines=`51-56`, `303-346`; fileSha256=`6EB0334B61785060F72734949DFC887E52ADA599539BA013423DA8000484E1BD`, `D420F6793E4FC7E693C2DE68D16A284A46325A75B0C7B967CC9BBA2762E9BFD7`
- observation: The plan consumer reads `overdrive.authority.avg` and `overdrive.contradiction.mean`, while the producer writes `overdrive.trigger.avgAuthority` and `overdrive.trigger.contradictionScore`.
- inference: Overdrive-derived low-authority and contradiction signals deterministically fall back to defaults at this consumer.
- reachability: Both classes are active and `OrchestrationSignals.compute` calls the consumer.
- counterEvidence: The consumer also reads generic/extremez/RAG aliases that another producer may populate.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A focused test running `traceDecision` then `deriveSignals` transfers the produced values without another alias writer.
- verification: Seed candidates to produce low authority/contradiction and inspect the resulting resolver signals.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Define one canonical Overdrive authority/contradiction trace contract shared by producer and consumer.
- dependencies: PA-02


### [PA-04] Debug snapshots mutate routing context

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: debug-observation-purity
- uniquenessKey: guard-debug-snapshot/orchestration-compute-mutates-plan
- activeOwner: sourceSet=`main/java`; path=`main/java/ai/abandonware/nova/orch/aop/GuardDebugTraceAspect.java`, `main/java/com/example/lms/orchestration/OrchestrationSignals.java`; symbol=`Snapshot.capture`, `compute/applyExecutionPlan`; line=`295-303`, `52-232/235-248`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/ai/abandonware/nova/orch/aop/GuardDebugTraceAspect.java`, `main/java/com/example/lms/orchestration/OrchestrationSignals.java`; symbols=`Snapshot.capture/compute/applyExecutionPlan`; lines=`295-303`, `52-232/235-248`; fileSha256=`EF7D4F137D737451BC34118A6025E79AD03057F438D688F995000DC5A9CCA17E`, `A1D77934FBC6FBC8ABEBBF8254FCC2FB6B94E966F026EEC371EC5FA9827E3D1D`
- observation: Debug snapshot capture calls `OrchestrationSignals.compute`, which unconditionally invokes `ExecutionPlanApplier.apply` on the current `GuardContext`.
- inference: Enabling the aspect changes routing state before/after the advised operation, so observation is not behaviorally neutral.
- reachability: The active aspect advises `ChatWorkflow.continueChat` and three preprocessing methods.
- counterEvidence: Reapplying the same deterministic plan may be idempotent for some contexts, and trace writes are fail-soft.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: Capturing a snapshot leaves all `GuardContext` plan overrides byte-for-byte unchanged.
- verification: Compare a context's overrides before and after `Snapshot.capture` with deterministic signals.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Separate pure signal calculation from `applyExecutionPlan`; use the pure function in `GuardDebugTraceAspect`.
- dependencies: PA-01, PA-02

### [PA-05] Nested web-search advice can multiply fallback work

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: web-search-aop-reentry
- uniquenessKey: web-failsoft-empty-fallback/nested-proceed-extra-calls
- activeOwner: sourceSet=`main/java`; path=`main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java`, `main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java`; symbol=`aroundSearch/aroundSearchWithTrace`, `aroundHybridSearch/aroundHybridSearchWithTrace`; line=`148-212/376-380/534-538/634-638`, `147-149/279-281`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/ai/abandonware/nova/orch/aop/WebFailSoftSearchAspect.java`, `main/java/ai/abandonware/nova/orch/aop/HybridWebSearchEmptyFallbackAspect.java`; symbols=`nested search advice`; lines=`148-212/376-380/534-538/634-638`, `147-149/279-281`; fileSha256=`4ACEA82D3AD289180FFF1C060D8EE2CD73B3D5B46E820A4D3483F3B6E7A9F179`, `3C477E34ECFCCFE6665C5150CF80149786F43A798534736F5AF711CA1801D461`
- observation: Both aspects advise the same search methods; the outer fail-soft advice performs multiple `pjp.proceed` rescue calls and each invocation can enter the inner empty-fallback advice.
- inference: Empty or degraded results can multiply provider/cache fallback work beyond the outer aspect's apparent extra-call count.
- reachability: Both aspects are active, default-enabled components; actual advice order, empty outcomes, and call counts were not executed.
- counterEvidence: Spring AOP ordering and `ProceedingJoinPoint.proceed` semantics may limit re-entry, and both aspects contain budgets and trigger gates.
- stapsStatus: not_observed
- confidence: 0.90
- evidenceSurface: static
- falsifier: An aspect-enabled call-count test proves total inner fallback attempts remain within one shared bound across every outer rescue.
- verification: Use recording provider doubles with empty results and count logical proceeds plus physical provider calls.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Add one request-scoped shared retry/fallback budget or re-entry guard across the two existing aspects.
- dependencies: SEC-08

### [PA-06] Aggressive mode skips requested whitelist filtering

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: rag-plan-constraint-precedence
- uniquenessKey: unified-orchestrator/aggressive-skips-whitelist-only
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`; symbol=`run final domain whitelist stage`; line=`734-744`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/orchestrator/UnifiedRagOrchestrator.java`; symbol=`domain whitelist stage`; line=`734-744`; fileSha256=`E04E426F596A02F3F495BA8A08F8854468F7147095EBFF08A1391FDCE6BB0B1F`
- observation: The final whitelist stage runs only when `whitelistOnly` is true, `aggressive` is false, and memory profile is not `NONE`.
- inference: If product policy requires explicit `whitelistOnly` to outrank aggressive expansion, the current intentional bypass violates that precedence.
- reachability: `PlanHintApplier` can set `whitelistOnly`, and callers can explicitly set aggressive mode, but the combined mode and its governing product policy were not observed.
- counterEvidence: The source comment explicitly describes the bypass as intentional aggressive-mode policy, and upstream filters may still favor official sources.
- stapsStatus: partial
- confidence: 0.95
- evidenceSurface: static
- falsifier: An authoritative existing product-policy artifact states that aggressive mode intentionally outranks `whitelistOnly`, and a focused mixed-domain behavior check matches the sanctioned result set.
- verification: Feed mixed-domain synthetic documents with both flags true and inspect the final selected set.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Resolve and encode the authoritative precedence at the existing final whitelist stage without adding a duplicate filter.
- dependencies: SEC-04, PA-01

### [PA-07] Fast intent precedes finance specialization

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: workflow-plan-priority
- uniquenessKey: workflow-orchestrator/cost-before-finance
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/orchestration/WorkflowOrchestrator.java`; symbol=`selectPlan`; line=`85-135`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/orchestration/WorkflowOrchestrator.java`; symbol=`selectPlan`; line=`85-135`; fileSha256=`CFB9BE205EA91E8E4B6EE3B8DD23F80A5C8C80C0D5625947F771C5C44802FD85`
- observation: `selectPlan` tests cost/fast language before finance language and returns immediately on the first match.
- inference: For an overlapping fast-finance query, the code selects cost-saver first; whether that conflicts with intended product priority is unresolved.
- reachability: The active workflow invokes `selectPlan` only when plan routing is enabled; effective enablement and an overlapping request were not observed.
- counterEvidence: Sensitive/high-risk context is checked earlier, finance specialization may not be required for every finance mention, and no authoritative overlap policy was found.
- stapsStatus: partial
- confidence: 0.95
- evidenceSurface: static
- falsifier: An authoritative existing plan-selection policy states that speed/cost intent outranks finance specialization for overlapping queries, and a focused overlap test returns the policy-prescribed plan.
- verification: Parameterize overlapping intent strings and assert documented priority decisions.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Document and encode the chosen overlap precedence once in `WorkflowOrchestrator.selectPlan` if current ordering is not authoritative.
- dependencies: none

### [PA-08] Self-Ask timeout cancellation leaves running search work

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: retrieval-timeout-cancellation
- uniquenessKey: selfask-hard-timeout/cancel-false
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java`; symbol=`getWithHardTimeout`; line=`1981-2017`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/SelfAskWebSearchRetriever.java`; symbol=`getWithHardTimeout`; line=`1981-2017`; fileSha256=`4442167E7947CF69F676028857AB0FA9F908802A5BD45734EE6F7E1DBB97A521`
- observation: Deadline, timeout, interrupted-wait, and failure paths call `future.cancel(false)`, explicitly avoiding interruption of running workers.
- inference: Blocking searches can continue after the caller records timeout and may consume executor capacity for later branches or requests.
- reachability: The active retriever submits provider searches to `searchExecutor`; an actual overrun or capacity impact was not observed.
- counterEvidence: Provider transports may enforce shorter timeouts, and avoiding interrupts prevents cancellation toxicity in shared pools.
- stapsStatus: partial
- confidence: 0.94
- evidenceSurface: static
- falsifier: A blocking-search test proves timed-out work terminates cooperatively and executor capacity is fully available within the documented bound.
- verification: Run bounded blocking doubles through `getWithHardTimeout` and measure active task/queue counts only.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Add task-owned cooperative abort or isolate disposable search work at the existing hard-timeout boundary.
- dependencies: HYBRID-01

### [PA-09] Raw descriptive markers conditionally activate Zero100 mode

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: high-cost-mode-activation
- uniquenessKey: zero100-aspect/raw-substring-conditional-activation
- activeOwner: sourceSet=`main/java`; path=`main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java`; symbol=`isZero100Enabled`, `looksLikeZero100Hint`; line=`229-265`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/ai/abandonware/nova/orch/aop/Zero100SessionAspect.java`; symbols=`isZero100Enabled/looksLikeZero100Hint`; lines=`229-265`; fileSha256=`84CA9388938FA9E36E3B11B174253EA4D65A1B3A7A99A049D7BA1950B00A13BC`
- observation: Unless an explicit plan enables the mode, the aspect still activates it when raw message text contains broad markers such as `zero100`, `제로백`, `엠페러`, or `emperor time`; the aspect's runtime path has no enclosing default-off check here.
- inference: If the aspect is active, discussion, quotation, or negation containing a marker can unintentionally enter a high-cost multi-lane session.
- reachability: The aspect advises active chat flow, but effective bean/property activation and actual added cost were not observed.
- counterEvidence: Exact plan IDs are separately recognized, time budgets clamp work, and users may intentionally use these phrases as commands.
- stapsStatus: partial
- confidence: 0.95
- evidenceSurface: static
- falsifier: An authoritative activation contract plus a focused enabled-profile test proves descriptive, quoted, and negated markers cannot schedule Zero100 without an explicit command or plan flag.
- verification: Exercise positive command, quoted mention, and negated mention cases while recording only activation and scheduled-lane counts.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Require an explicit structured activation signal or bounded command parser in `isZero100Enabled`; retain exact plan-ID support.
- dependencies: none

### [API-ATTACH-01] Attachment endpoints do not authorize caller-supplied session IDs

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: session-resource-authorization
- uniquenessKey: attachment-api/session-id-without-chat-owner-check
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/AttachmentController.java`, `main/java/com/example/lms/service/AttachmentService.java`, `main/java/com/example/lms/api/ChatSessionAccessGuard.java`; symbol=`upload/inspect/delete`, `saveAll/deleteForSession/attachToSession`, `authorize`; line=`36-88`, `125-171/549-558`, `18-55`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/api/AttachmentController.java`, `main/java/com/example/lms/service/AttachmentService.java`, `main/java/com/example/lms/api/ChatSessionAccessGuard.java`; symbols=`upload/inspect/delete`, `saveAll/deleteForSession/attachToSession`, `authorize`; lines=`36-88`, `125-171/549-558`, `18-55`; fileSha256=`6FE3924B530B25947DB6DAA6A0750CDBA0FD82EFCF23FAFCDA2CA0618FED9982`, `7983616D3A61AE68013BCF68058ED67A6E1E6AAADC3E46D929F50F7A2525BA23`, `F7FF7A767ABFF6E36F59938C09A0C331EEB6D72B7B8DEE1B1902CE8EDA39419A`
- observation: Attachment upload, inspection, archive ingest, and deletion accept a raw `sessionId` and delegate directly to the attachment service; none calls the existing chat-session ownership guard.
- inference: A caller can create or operate attachment-to-session associations for a session identifier that the caller does not own.
- reachability: `/api/attachments/upload`, `/inspect`, `/conversation-archive/ingest`, and `DELETE /api/attachments/{id}` are controller endpoints; `ChatSessionAccessGuard.authorize` proves an ownership seam exists for chat endpoints.
- counterEvidence: `deleteForSession` checks the attachment's in-memory session association, and chat retrieval separately authorizes chat sessions; neither proves the caller owns the supplied session at attachment intake.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A focused MVC test using a foreign session proves every session-scoped attachment operation returns a neutral forbidden/not-found result before any attachment association or archive enqueue occurs.
- verification: Exercise upload, inspect, archive ingest, and delete with owned, foreign, nonexistent, and blank session IDs while recording repository and session-index mutations only.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Reuse the existing session-access owner check at the attachment controller boundary before calling `AttachmentService` or archive ingest.
- dependencies: SEC-05, SEC-06

### [API-CHAT-02] A nonexistent session can fork and persist the first turn twice

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: session-recovery-duplication
- uniquenessKey: chat-stream/nonexistent-session-recovery-double-user-persist
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/ChatSessionAccessGuard.java`, `main/java/com/example/lms/api/ChatApiController.java`, `main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbol=`authorize`, `chatStream worker`, `startNewSession`; line=`18-46`, `1747-1760/1922-1924`, `1070-1102`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/api/ChatSessionAccessGuard.java`, `main/java/com/example/lms/api/ChatApiController.java`, `main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbols=`authorize/chatStream/startNewSession`; lines=`18-46`, `1747-1760/1922-1924`, `1070-1102`; fileSha256=`F7FF7A767ABFF6E36F59938C09A0C331EEB6D72B7B8DEE1B1902CE8EDA39419A`, `C140D180CB9FA597D34391075FDBE44683DDF07E4F260C93B3F216C75FB934EA`, `84139B74F13AE15557F7C86A578065670B017B219A3370DEA4BC8A83BD52B43E`
- observation: Authorization returns no denial when the requested session is absent; stream recovery then calls `startNewSession`, which stores the first user message, and the existing-session branch later appends the same message because the request still has a non-null session ID.
- inference: A request naming a nonexistent session creates a new session and stores two copies of its first user turn.
- reachability: `POST /api/chat/stream` enters this branch for a non-null missing session ID and the active history implementation persists both calls.
- counterEvidence: A null session ID follows the ordinary new-session path and does not execute the later existing-session append condition.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A repository-backed stream test with a nonexistent requested ID creates one canonical session containing exactly one user turn before assistant persistence.
- verification: Invoke the stream endpoint with a synthetic missing session ID and count persisted user-role rows for the recovered session.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Make missing-session recovery explicit and ensure exactly one owner persists the initial user turn.
- dependencies: API-ATTACH-01


### [API-SESSION-03] Stream metadata is changed on a detached session without repository persistence

- classification: `confirmed_defect`
- priorityInput: P1
- rootCauseClusterId: session-metadata-persistence
- uniquenessKey: chat-stream/detached-session-meta-without-update-owner
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/ChatApiController.java`, `main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbol=`chatStream sessionMeta merge`, `updateSessionMeta`; line=`1761-1767`, `393-408`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/api/ChatApiController.java`, `main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbols=`chatStream sessionMeta merge/updateSessionMeta`; lines=`1761-1767`, `393-408`; fileSha256=`C140D180CB9FA597D34391075FDBE44683DDF07E4F260C93B3F216C75FB934EA`, `84139B74F13AE15557F7C86A578065670B017B219A3370DEA4BC8A83BD52B43E`
- observation: The stream worker mutates `session.setSessionMeta(...)` after the history read has returned, but it does not call `updateSessionMeta` or `sessionRepository.save`; the service exposes that explicit repository update path.
- inference: Metadata merged for an existing streamed session is not durably written by this code path.
- reachability: The mutation is inside the active `/api/chat/stream` worker for both fetched and recovered sessions.
- counterEvidence: A just-created session can remain managed within its creation transaction only until that method returns; later unrelated persistence might merge the entity, but no such merge is present at this seam.
- stapsStatus: contradicted
- confidence: 0.98
- evidenceSurface: static
- falsifier: A repository-backed stream test reloads an existing session after completion and observes the merged metadata without any later explicit metadata update call.
- verification: Stream one request with a deterministic metadata field, clear the persistence context, and reload the session from the repository.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Route the merged map through `ChatHistoryService.updateSessionMeta` instead of mutating the controller-held entity.
- dependencies: none

### [API-TRACE-04] Durable chat pointers target a bounded process-memory trace ring

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: durable-pointer-volatile-target
- uniquenessKey: chat-trace-pointer/jpa-message-to-memory-ring-id
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java`, `main/java/com/example/lms/trace/TraceSnapshotStore.java`, `main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java`; symbol=`persist`, `captureCustom/retentionStats/get`, `restore`; line=`19-56`, `28-45/343-348/422-447`, `21-36`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java`, `main/java/com/example/lms/trace/TraceSnapshotStore.java`, `main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java`; symbols=`persist/captureCustom-retentionStats-get/restore`; lines=`19-56`, `28-45/343-348/422-447`, `21-36`; fileSha256=`14526CD418F573A36142603D66ED5C6D3B77200408553ED0ABDD0D799441C30B`, `D98AFF8E12C53604204366F6DA2F3B1CD3A927ECA3718D98EB50356EF26DEF6C`, `65B8417511953B0386B748605EA1E1BA64A7ED91FB7DCABF16BC37F0C0093E91`
- observation: The persister saves a snapshot ID as a system chat message, while `TraceSnapshotStore` identifies itself as memory-only, evicts beyond `maxSize`, and resolves IDs only by scanning its process-local ring.
- inference: Restored chat history can render a durable link whose target disappears after eviction or restart.
- reachability: Final stream persistence writes the pointer and history restoration turns it into `/api/diagnostics/trace/snapshots/{id}/html`; an eviction or restart was not executed.
- counterEvidence: The ring is bounded, pointer IDs are validated, and short-lived sessions may access the snapshot before eviction.
- stapsStatus: partial
- confidence: 0.98
- evidenceSurface: static
- falsifier: A restart-and-eviction integration check proves every persisted pointer remains resolvable or is replaced with an explicit durable tombstone state.
- verification: Persist a synthetic trace pointer, exceed ring capacity and restart an isolated context, then query the restored pointer endpoint.
- verificationClass: runtime
- verificationCost: high
- minimalRepairSeam: Align pointer lifetime with target lifetime at the existing persister/store boundary, either through durable snapshot storage or explicit non-durable restoration semantics.
- dependencies: none

### [API-ARCHIVE-05] Lexical archive markers promote untrusted text to verified KB metadata

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: archive-trust-promotion
- uniquenessKey: conversation-archive/lexical-kind-to-verified-kb
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/conversation/archive/ConversationNoiseClassifier.java`, `main/java/com/example/lms/conversation/archive/ConversationTopicTimelineBuilder.java`; symbol=`classifyText/looksLikeBotSummary`, `metadataFor`; line=`20-49/69-74`, `91-123`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/conversation/archive/ConversationNoiseClassifier.java`, `main/java/com/example/lms/conversation/archive/ConversationTopicTimelineBuilder.java`; symbols=`classifyText/looksLikeBotSummary/metadataFor`; lines=`20-49/69-74`, `91-123`; fileSha256=`1E6E827041F243E231280AA6E0E87B70DF950FD547D2C75C97715314F02B17C3`, `EC3424C9B167EAE664D468DD9A109769DC665423EA54C0ED7A2768D626A68F23`
- observation: Any archive text containing `summary:` or one URL is classified as an ingestible non-human kind; `metadataFor` assigns every non-human kind `doc_type=KB`, `verified=true`, and `verification_needed=false`.
- inference: User-controlled lexical markers can promote archive content into trusted KB metadata without source verification.
- reachability: `POST /api/attachments/conversation-archive/ingest` passes parsed archive records through this classifier and builder before vector enqueue.
- counterEvidence: HTML, base64, repeated junk, and code-dump guards quarantine several noisy inputs, and PII/redaction sanitation runs before chunk construction.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: Parameterized archive tests prove arbitrary human text containing summary or URL markers remains unverified unless an independent provenance verifier succeeds.
- verification: Feed synthetic plain text, summary-marker text, and URL text through classify/build and assert emitted trust metadata.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Decouple lexical archive kind from verification state in `ConversationTopicTimelineBuilder.metadataFor` and require explicit provenance evidence for `verified=true`.
- dependencies: STKG-02-merged

### [API-ARCHIVE-06] Archive promoted counts advance after an outcome-blind enqueue

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: ingest-outcome-accounting
- uniquenessKey: conversation-archive/void-enqueue-promoted-count
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/conversation/archive/ConversationArchiveIngestService.java`, `main/java/com/example/lms/service/VectorStoreService.java`; symbol=`processTextEntry`, `enqueue`; line=`120-142`, `248-361/480-620`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/conversation/archive/ConversationArchiveIngestService.java`, `main/java/com/example/lms/service/VectorStoreService.java`; symbols=`processTextEntry/enqueue`; lines=`120-142`, `248-361/480-620`; fileSha256=`1BA187BB7EA27BD8D9FBBEDAB326B3B46426C761B01113AACD27ADEFCD662093`, `5A36C69EEAEE7651B54954DA461699FDBFA996FC44926818637F37EFE9C19D78`
- observation: `processTextEntry` increments `ingestedCount` immediately after a void `enqueue`; enqueue can return early for a generated-artifact poison decision or route content to quarantine/shadow without returning an outcome.
- inference: The archive report's ingested/promoted counts can describe attempted chunks as successfully promoted even when they were dropped or isolated.
- reachability: The archive ingest endpoint calls this loop; the specific poison, quarantine, and shadow conditions depend on optional guards and current configuration.
- counterEvidence: Ordinary accepted chunks reach the in-memory queue, stable IDs deduplicate with `putIfAbsent`, and downstream trace fields expose some route states.
- stapsStatus: partial
- confidence: 0.97
- evidenceSurface: static
- falsifier: A focused test across primary, deduplicated, quarantined, shadowed, and dropped outcomes proves the reported promoted count equals only durable primary promotions.
- verification: Use recording guard/store doubles and compare archive report counts with queue route/outcome counts.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Return a bounded enqueue outcome from the existing vector seam and derive archive attempted, queued, quarantined, dropped, and promoted counts separately.
- dependencies: API-ARCHIVE-05

### [API-HISTORY-07] Bounded history APIs materialize the complete transcript first

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: transcript-query-bounding
- uniquenessKey: chat-history/full-materialization-before-limit
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbol=`getSessionWithMessages/getFormattedRecentHistory`; line=`471-484/496-525`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbols=`getSessionWithMessages/getFormattedRecentHistory`; lines=`471-484/496-525`; fileSha256=`84139B74F13AE15557F7C86A578065670B017B219A3370DEA4BC8A83BD52B43E`
- observation: Both authorization-oriented session loading and recent-history formatting call `findBySessionIdOrderByCreatedAtAsc`, materialize all messages, and only then filter/slice in memory.
- inference: Session authorization and bounded recent-history requests can incur work proportional to the full transcript rather than the requested limit.
- reachability: `ChatSessionAccessGuard` uses `getSessionWithMessages` for chat endpoints and conversation-memory callers use the recent-history method; large-session impact was not measured.
- counterEvidence: Message ordering is deterministic, meta rows are filtered before limiting, and current session sizes may remain small.
- stapsStatus: partial
- confidence: 0.98
- evidenceSurface: static
- falsifier: Repository query capture for a long synthetic transcript proves these call paths issue bounded projections and do not hydrate rows beyond the requested authorization/recent-history need.
- verification: Seed a long session and inspect executed query row counts for authorization and `getFormattedRecentHistory(sessionId, limit)`.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Add bounded repository projections for ownership and recent visible messages at the existing history-service boundary.
- dependencies: none

### [API-LIFECYCLE-08] Session deletion is not coordinated with an active stream commit

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: session-run-lifecycle-race
- uniquenessKey: chat-delete/active-run-late-persist
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/ChatApiController.java`, `main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbol=`deleteSession/chatStream transcript commit`, `deleteSession`; line=`4004-4038/2420-2454`, `487-492`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/api/ChatApiController.java`, `main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbols=`deleteSession/chatStream transcript commit`; lines=`4004-4038/2420-2454`, `487-492`; fileSha256=`C140D180CB9FA597D34391075FDBE44683DDF07E4F260C93B3F216C75FB934EA`, `84139B74F13AE15557F7C86A578065670B017B219A3370DEA4BC8A83BD52B43E`
- observation: The delete endpoint authorizes and directly deletes JPA session state without consulting `ChatRunRegistry`; a stream independently commits assistant/system/trace messages after its run-local cancellation checks.
- inference: Deletion racing an active generation can produce a late persistence failure, inconsistent terminal outcome, or post-delete transcript activity.
- reachability: Both endpoints are active, but the failure requires an overlapping delete and stream timing window and was not executed.
- counterEvidence: Stream commits have cancellation and single-commit guards, and database foreign-key/cascade behavior may reject late writes consistently.
- stapsStatus: not_observed
- confidence: 0.95
- evidenceSurface: static
- falsifier: A deterministic concurrency test proves deletion atomically cancels/joins the active run and no message, trace pointer, or terminal state is persisted afterward.
- verification: Pause a stream before transcript commit, delete the same owned session, release the stream, and assert registry plus repository outcomes.
- verificationClass: local_test
- verificationCost: high
- minimalRepairSeam: Coordinate delete with the existing `ChatRunRegistry` lifecycle before `historyService.deleteSession`.
- dependencies: none

### [API-CONCURRENCY-09] Same-session synchronous chat bypasses the stream run registry

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: session-run-serialization
- uniquenessKey: chat-sync/no-run-registry-claim
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/api/ChatApiController.java`; symbol=`chatSync/chat/chatStream`; line=`1279-1450/1523-1530`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/api/ChatApiController.java`; symbols=`chatSync/chat/chatStream`; lines=`1279-1450/1523-1530`; fileSha256=`C140D180CB9FA597D34391075FDBE44683DDF07E4F260C93B3F216C75FB934EA`
- observation: Both synchronous endpoints authorize and call `handleChat` directly, while only the streaming endpoint claims or joins a same-session run through `ChatRunRegistry.beginOrJoin`.
- inference: Concurrent sync and stream requests for one session can generate and persist without one shared serialization or duplicate-suppression contract.
- reachability: `/api/chat/sync`, `/api/chat`, and `/api/chat/stream` are active endpoints; the conflicting interleaving is race-dependent and unobserved.
- counterEvidence: Database transaction boundaries may serialize individual writes, and callers may conventionally avoid concurrent same-session sync requests.
- stapsStatus: not_observed
- confidence: 0.97
- evidenceSurface: static
- falsifier: A concurrent endpoint test proves sync and stream requests for one session share one registry claim and yield one ordered transcript outcome.
- verification: Block generation behind a latch, overlap sync and stream calls for the same session, and inspect model-call, registry, and persisted-turn counts.
- verificationClass: local_test
- verificationCost: high
- minimalRepairSeam: Route all generating endpoints through the existing same-session run claim without creating a second registry.
- dependencies: none

### [TBL-01] The source-health validation loop records gates without executing them

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: verification-artifact-provenance
- uniquenessKey: source-health-loop/declared-gates-without-execution
- activeOwner: sourceSet=`scripts`; path=`scripts/source_health_validation_loop.py`; symbol=`REQUIRED_GATES/main`; line=`46-53/442-474`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`scripts/source_health_validation_loop.py`; symbols=`REQUIRED_GATES/main`; lines=`46-53/442-474`; fileSha256=`9540B4CE5CF1480A72E9781843712D422BCFB4F07D4AE5973AD06E58048222FC`
- observation: The script places six task names in `requiredGates`, parses `--skip-gradle` without reading it, and always writes report/cycle artifacts without invoking Gradle or recording task results.
- inference: A freshly timestamped validation-loop artifact can exist while every declared Gradle gate is unexecuted, but whether that violates the script's contract depends on which separate owner, if any, is authoritative for gate execution.
- reachability: Direct script execution and the declared `--skip-gradle` CLI path both enter `main` and emit the artifacts; no authoritative current contract was identified that requires this recorder itself to execute Gradle gates.
- counterEvidence: The module docstring describes a ledger recorder, and a separate named task may own gate execution before consumers accept the artifact.
- stapsStatus: contradicted
- confidence: 0.96
- evidenceSurface: static
- falsifier: An authoritative repository contract states this script is ledger-only, names the separate task that owns the six Gradle gates, and a focused task-graph/static check proves that named task supplies gate execution before the artifact is consumed.
- verification: Locate the authoritative script/consumer contract, identify the concrete separate task name if present, and inspect its focused task graph plus static artifact-consumption path for the six declared gates.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Either execute and attest the declared gates in `main` or rename/schema the artifact so gate names cannot be interpreted as executed proof.
- dependencies: none

### [TBL-02] Broad JUnit proof selects an unversioned best subset

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: junit-proof-freshness
- uniquenessKey: source-health-scorecard/best-subset-no-freshness
- activeOwner: sourceSet=`scripts`; path=`scripts/source_health_scorecard.py`; symbol=`TEST_RESULT_TASK_NAMES/_test_result_roots/_broad_runtime_test_proof`; line=`73/281-375`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`scripts/source_health_scorecard.py`; symbols=`TEST_RESULT_TASK_NAMES/_test_result_roots/_broad_runtime_test_proof`; lines=`73/281-375`; fileSha256=`9A4825D5D43F220CA49941F32FDBAF4D0835ACF58652B0942BBF78374C274647`
- observation: Broad proof searches every root/default/child `test` and `crossSubsystemContractTest` XML directory, returns the highest-ranked aggregate, omits skipped counts from pass criteria, and has no task start, commit, or mtime freshness binding.
- inference: A larger clean but stale or skipped subset can be selected as current broad proof, while isolated UI and gateway-security tasks are outside the search set.
- reachability: `build_scorecard` consumes this result; actual current XML freshness and skipped counts were not read for this documentation task.
- counterEvidence: Failures and errors must be zero, focused cross-subsystem classes require non-skipped suites, and separate proof fields cover some specialized surfaces.
- stapsStatus: partial
- confidence: 0.98
- evidenceSurface: static
- falsifier: Fixture XML roots with stale, skipped, current failing, UI, and security results prove selection is commit/task/freshness-bound and cannot prefer a stale subset.
- verification: Unit-test `_broad_runtime_test_proof` with controlled timestamps and suite counters across every configured test task.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Bind broad JUnit aggregation to one declared run identity and include skipped, freshness, and intended-task coverage in the proof decision.
- dependencies: TBL-01

### [TBL-03] Conventional Gradle check omits health reports and boot packaging

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: gradle-verification-graph
- uniquenessKey: gradle-check/health-and-bootjar-not-dependent
- activeOwner: sourceSet=`build`; path=`build.gradle.kts`; symbol=`check dependencies/sourceHealthScorecard/sourceScoreReport/bootJar`; line=`142-169/197-255/455-461`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`build.gradle.kts`; symbol=`check task graph`; lines=`142-169/197-255/455-461`; fileSha256=`B4D9D4791F0F256CD312267564F889F1E0A30F88D95024FF85B5CB1451C397F4`
- observation: `check` depends on version purity, source-set hygiene, and three test tasks; `sourceScoreReport`, `harmonyPressureReport`, `sourceHealthScorecard`, and `bootJar` are separate tasks with no dependency edge from `check`.
- inference: Reporting a plain `check` run as the repository's full health-and-package verification would omit those surfaces.
- reachability: `check` is a conventional Gradle entry point, but no policy proving it is intended to be the complete release gate was found.
- counterEvidence: Gradle does not require `check` to build a boot JAR, and repository runbooks may intentionally compose a broader explicit command ladder.
- stapsStatus: partial
- confidence: 0.99
- evidenceSurface: build
- falsifier: A repository-owned release task or wrapper transitively executes `check`, all named health reports, and `bootJar`, and all completion claims consistently use that wrapper.
- verification: Inspect `gradlew.bat tasks` and a dry-run task graph for the documented final verification command.
- verificationClass: local_build
- verificationCost: low
- minimalRepairSeam: Define or document one existing aggregate verification task; do not overload `check` unless it is the sanctioned owner.
- dependencies: TBL-01

### [TBL-04] Test-tree reliability is reduced to missing-import alignment

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: test-tree-metric-validity
- uniquenessKey: test-tree-report/missing-import-only-zero-exit
- activeOwner: sourceSet=`scripts`; path=`scripts/test_tree_contamination_report.py`, `scripts/source_health_scorecard.py`; symbol=`build_report/main`, `test_tree_reliability component`; line=`142-221`, `2196-2235/2497-2503`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`scripts/test_tree_contamination_report.py`, `scripts/source_health_scorecard.py`; symbols=`build_report/main/test_tree_reliability`; lines=`142-221`, `2196-2235/2497-2503`; fileSha256=`B1EE73B117842B3FDD3AE172BB27145756DD50C04158181B39A870A05A2506B9`, `9A4825D5D43F220CA49941F32FDBAF4D0835ACF58652B0942BBF78374C274647`
- observation: The reporter calculates `riskScore` solely from unresolved repository imports and always returns zero; the scorecard converts that scalar into the `test_tree_reliability` component.
- inference: Compile failures, skipped tests, source-text assertions, stale outputs, flaky behavior, and missing behavioral coverage are outside this reliability score.
- reachability: The Gradle `testTreeContaminationReport` task feeds `sourceHealthScorecard`; those omitted failure modes were not executed here.
- counterEvidence: The metric is explicitly named contamination, and broad JUnit proof separately accounts for some test execution results.
- stapsStatus: partial
- confidence: 0.99
- evidenceSurface: static
- falsifier: The published scorecard schema and tests prove `test_tree_reliability` is explicitly limited to import contamination and cannot be consumed as overall test reliability.
- verification: Inject fixtures with clean imports but compile failure, skipped suites, and stale XML; inspect report exit and scorecard component decisions.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Rename/narrow the component or compose independent compile, execution, skip, freshness, and behavioral-coverage signals at the scorecard boundary.
- dependencies: TBL-02

### [TBL-05] The canonical full-context test excludes servlet registration

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: servlet-context-proof-gap
- uniquenessKey: application-context-test/non-web-canonical-proof
- activeOwner: sourceSet=`src/test/java`; path=`src/test/java/com/example/lms/LmsApplicationContextLoadsTest.java`, `src/test/java/com/example/lms/api/TraceSnapshotsDiagnosticsSecurityIntegrationTest.java`; symbol=`contextLoads`, `trace snapshot servlet slice`; line=`7-21`, `52-168`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`src/test/java/com/example/lms/LmsApplicationContextLoadsTest.java`, `src/test/java/com/example/lms/api/TraceSnapshotsDiagnosticsSecurityIntegrationTest.java`; symbols=`contextLoads/servlet slice`; lines=`7-21`, `52-168`; fileSha256=`CC09C5307C7856EA8F8913071A41D3B2B9456982ADC9EA13A8FD48E3DB9E7B23`, `851C1D82F30B2CD9D3DF71140B47A673B1A8F4CBEA4C0700144D3FF23413533E`
- observation: `LmsApplicationContextLoadsTest` forces `WebEnvironment.NONE` and `spring.main.web-application-type=none`; servlet coverage is supplied by narrower controller/security slices rather than one complete servlet application context.
- inference: Cross-controller mappings, full filter-chain ordering, servlet-only bean registration, and lifecycle interaction are not proven by the canonical context-load test.
- reachability: The non-web test is part of the root test source set; a full servlet context run was not executed.
- counterEvidence: Multiple `@WebMvcTest`, security integration, and standalone MockMvc tests cover focused servlet surfaces, and non-web boot is an explicit repository requirement.
- stapsStatus: partial
- confidence: 0.98
- evidenceSurface: test
- falsifier: A current full-servlet `LmsApplication` context test loads all production MVC/security beans and validates mapping/filter uniqueness without replacing the required non-web proof.
- verification: Start a bounded servlet test context with external integrations disabled and assert controller mappings plus all security chains.
- verificationClass: local_test
- verificationCost: high
- minimalRepairSeam: Add one complementary full-servlet context contract; preserve the existing non-web boot test.
- dependencies: TBL-03

### [TBL-06] Auto-configuration discovery proof spot-checks one import entry

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: autoconfiguration-inventory-proof
- uniquenessKey: agent-db-contract/single-import-direct-runner
- activeOwner: sourceSet=`src/test/java`, `main/resources`; path=`src/test/java/com/example/lms/agent/context/AgentDbContextContractTest.java`, `main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; symbol=`endpointIsAdminOnlyAndFeatureFlagged/autoConfigurationRegistersBeansOnlyWhenEnabled`, `auto-configuration inventory`; line=`16-34/84-106`, `1-6`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`src/test/java/com/example/lms/agent/context/AgentDbContextContractTest.java`, `main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; symbols=`AgentDbContextContractTest/auto-configuration inventory`; lines=`16-34/84-106`, `1-6`; fileSha256=`8384E3E7AF7234972E15233B77822D83E53B2A819E1569839682DE03D0F3545C`, `5C647CB81692DF339EF336F4ACFC5DCC4E169D879278616EBAF9973FAD22D241`
- observation: The contract asserts the Agent DB class appears in the six-entry imports file and separately loads that class with `ApplicationContextRunner`; it does not inventory every metadata entry against intended discovery and conditional outcomes.
- inference: Missing, duplicated, stale, or unexpectedly loadable auto-config entries outside Agent DB can evade this proof.
- reachability: The imports file is an active resource; framework discovery for the complete inventory was not executed in this task.
- counterEvidence: Individual auto-configurations have focused tests and `@ConditionalOnMissingBean`/property guards that reduce duplicate-bean impact.
- stapsStatus: partial
- confidence: 0.97
- evidenceSurface: test
- falsifier: A finite inventory contract for `main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` proves exactly these six entries and their intended enabled/disabled outcomes: `ai.abandonware.nova.autoconfig.NovaDebugPortAutoConfiguration`, `ai.abandonware.nova.autoconfig.NovaFailurePatternAutoConfiguration`, `ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration`, `ai.abandonware.nova.autoconfig.NovaOpsStabilizationAutoConfiguration`, `ai.abandonware.nova.autoconfig.NovaZero100AutoConfiguration`, and `com.example.lms.agent.context.AgentDbContextAutoConfiguration`.
- verification: Read the anchored root imports file, assert equality with that six-class inventory, and run parameterized context-runner cases for each class's declared enabled/disabled condition set.
- verificationClass: local_test
- verificationCost: high
- minimalRepairSeam: Extend the existing metadata contract into one authoritative auto-configuration inventory rather than adding per-entry string checks.
- dependencies: none

### [TBL-07] Plan tests do not map every selectable key to a runtime consumer

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: plan-dsl-consumer-coverage
- uniquenessKey: plan-loader-tests/resources-without-consumer-map
- activeOwner: sourceSet=`src/test/java`, `main/java`; path=`src/test/java/com/nova/protocol/plan/PlanLoaderTest.java`, `main/java/com/nova/protocol/plan/PlanLoader.java`; symbol=`loadsClasspathPlanFieldsFromResource/loadsNestedPlanIdAndKnobsFromResource`, `discoverPlanIds/loadFromClasspath`; line=`18-83`, `29-50/250-298`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`src/test/java/com/nova/protocol/plan/PlanLoaderTest.java`, `main/java/com/nova/protocol/plan/PlanLoader.java`; symbols=`PlanLoaderTest/discoverPlanIds/loadFromClasspath`; lines=`18-83`, `29-50/250-298`; fileSha256=`BF35509A9A032586B6E61B14939D2CFBFE8E9A9609904CB94CD4349B599011FA`, `D4D1A0E51436082EB8972A9DF199C85A5DB43D3F172F250529E4F2026DD4AA4D`
- observation: Tests load selected `safe.v1` and `brave.v1` fields plus loader fail-soft cases; no test enumerates every discovered plan ID and proves each selectable key is consumed by an active runtime owner.
- inference: A plan can parse successfully while one or more knobs are inert, misspelled, or mapped only in inactive code.
- reachability: `PlanLoader` discovers resources under `plans`; selection frequency and effective consumers for every plan were not observed.
- counterEvidence: Plan application and orchestration have separate focused tests, and missing resources return null rather than a synthetic default.
- stapsStatus: partial
- confidence: 0.96
- evidenceSurface: test
- falsifier: A parameterized contract enumerates every shipped plan and maps every non-metadata key to an asserted active consumer effect or explicit no-op policy.
- verification: Discover all plan IDs, flatten keys, and compare them with an allowlisted consumer/effect matrix exercised in tests.
- verificationClass: local_test
- verificationCost: high
- minimalRepairSeam: Add one plan-key-to-consumer inventory around the existing loader/applier tests.
- dependencies: RC-03, RC-04

### [TBL-08] Runtime configuration tests do not generically compute effective precedence

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: configuration-precedence-proof
- uniquenessKey: runtime-config-tests/file-contracts-without-effective-environment-matrix
- activeOwner: sourceSet=`src/test/java`; path=`src/test/java/com/example/lms/boot/RuntimeConfigShadowGuardTest.java`, `src/test/java/com/example/lms/boot/RuntimeApplicationYamlDuplicateKeyTest.java`; symbol=`runtime shadow contracts/application file inventory`; line=`16-142`, `22-150`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`src/test/java/com/example/lms/boot/RuntimeConfigShadowGuardTest.java`, `src/test/java/com/example/lms/boot/RuntimeApplicationYamlDuplicateKeyTest.java`; symbols=`RuntimeConfigShadowGuardTest/RuntimeApplicationYamlDuplicateKeyTest`; lines=`16-142`, `22-150`; fileSha256=`429E6D97859C0746790682CDFC00266A2EFBC909E7255FFA37FBCCBCC0F8A66D`, `6664A6CB7153E008804D265F99D496E1C5BA674949E4B840AE568C3A8A2A3A3F`
- observation: Existing tests inspect selected source strings, duplicate keys, packaging, and profile-specific values; they do not load a generic matrix of root, profile, environment, system-property, and imported sources and compare every protected effective key.
- inference: Cross-file/profile shadowing outside enumerated assertions can change effective runtime values without failing these contracts.
- reachability: Active root and app resources are inspected by these tests; actual effective environments for deployment profiles were not constructed.
- counterEvidence: Focused environment/context tests cover several high-risk keys, and Spring's documented property-source order remains the framework baseline.
- stapsStatus: partial
- confidence: 0.95
- evidenceSurface: test
- falsifier: An authoritative current contract limits precedence to `main/resources/application.properties`, `main/resources/application.yml`, `main/resources/application-dev.yml`, `main/resources/application-local.yml`, and `main/resources/application-llm.yaml` for profiles `default`, `dev`, `local`, and `llm`, and a focused matrix proves owner-only results for these 43 leaf keys: `spring.config.import`, `spring.datasource.driver-class-name`, `spring.datasource.hikari.connection-init-sql`, `spring.datasource.hikari.connection-timeout`, `spring.datasource.hikari.idle-timeout`, `spring.datasource.hikari.maximum-pool-size`, `spring.datasource.hikari.minimum-idle`, `spring.datasource.password`, `spring.datasource.url`, `spring.datasource.username`, `server.port`, `server.ssl.enabled`, `server.ssl.key-password`, `server.ssl.key-store`, `server.ssl.key-store-password`, `server.ssl.key-store-type`, `management.endpoints.web.exposure.include`, `management.endpoint.env.show-values`, `management.endpoint.httptrace.enabled`, `naver.keys`, `naver.filters.enable-domain-filter`, `naver.filters.domain-policy`, `naver.filters.keyword-min-hits`, `naver.search.web-top-k`, `naver.search.timeout-ms`, `fallback.enabled`, `selfask.enabled`, `probe.search.enabled`, `probe.admin-token`, `llm.fast.timeout-seconds`, `onnx.enabled`, `local-llm.enabled`, `local-llm.base-url`, `local-llm.autostart`, `gemini.api-key`, `tavily.enabled`, `nova.orch.evidence-list.trace-injection.enabled`, `uaw.autolearn.min-evidence-count`, `energy.w.rel`, `energy.w.auth`, `energy.w.rec`, `energy.w.red`, and `energy.w.ctr`. The declared environment placeholders are `APP_CONFIG_IMPORT`, `LMS_DB_DRIVER`, `LMS_DB_CONNECTION_INIT_SQL`, `LMS_DB_HIKARI_CONNECTION_TIMEOUT`, `LMS_DB_HIKARI_IDLE_TIMEOUT`, `LMS_DB_HIKARI_MAX_POOL_SIZE`, `LMS_DB_HIKARI_MIN_IDLE`, `LMS_DB_PASSWORD`, `LMS_DB_URL`, `LMS_DB_USERNAME`, `SERVER_PORT`, `SERVER_SSL_ENABLED`, `SERVER_SSL_KEY_PASSWORD`, `SERVER_SSL_KEY_STORE`, `SERVER_SSL_KEY_STORE_PASSWORD`, `SERVER_SSL_KEY_STORE_TYPE`, `NAVER_KEYS`, `SELFASK_ENABLED`, `PROBE_SEARCH_ENABLED`, `DOMAIN_ALLOWLIST_ADMIN_TOKEN`, `PROBE_ADMIN_TOKEN`, `ONNX_ENABLED`, `LOCAL_LLM_ENABLED`, `LOCAL_LLM_BASE_URL`, `OPENAI_COMPAT_BASE_URL`, `LOCAL_LLM_AUTOSTART`, `GEMINI_API_KEY`, `TAVILY_ENABLED`, `NOVA_EVIDENCE_LIST_TRACE_INJECTION_ENABLED`, and `UAW_AUTOLEARN_MIN_EVIDENCE_COUNT`; the keys `management.endpoints.web.exposure.include`, `management.endpoint.env.show-values`, `management.endpoint.httptrace.enabled`, `naver.filters.enable-domain-filter`, `naver.filters.domain-policy`, `naver.filters.keyword-min-hits`, `naver.search.web-top-k`, `naver.search.timeout-ms`, `fallback.enabled`, `llm.fast.timeout-seconds`, `energy.w.rel`, `energy.w.auth`, `energy.w.rec`, `energy.w.red`, and `energy.w.ctr` have `none_declared_in_anchored_files`, and declared system-property override names are `none_declared_in_anchored_files`.
- verification: Parse only `main/resources/application.properties`, `main/resources/application.yml`, `main/resources/application-dev.yml`, `main/resources/application-local.yml`, and `main/resources/application-llm.yaml`; assert occurrence of the falsifier's 43 leaf-key literals, 30 environment-placeholder literals, 15 `none_declared_in_anchored_files` key literals, and system-property sentinel; then load profiles `default`, `dev`, `local`, and `llm` and snapshot owner-only results for that closed set.
- verificationClass: local_test
- verificationCost: high
- minimalRepairSeam: Add a generic effective-property ownership matrix beside the existing shadow and duplicate-key tests.
- dependencies: CFG-01, CFG-02

### [TBL-09] Matryoshka and DPP have no same-corpus acceptance benchmark

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: retrieval-quality-benchmark-gap
- uniquenessKey: embedding-dpp-tests/no-same-corpus-quality-cost-baseline
- activeOwner: sourceSet=`src/test/java`; path=`src/test/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingModelPostProcessorTest.java`, `src/test/java/com/example/lms/service/rag/rerank/DppDiversityRerankerTest.java`; symbol=`dimension/slice mechanics tests`, `synthetic rerank mechanics tests`; line=`78-238`, `24-243`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`src/test/java/ai/abandonware/nova/boot/embedding/MatryoshkaEmbeddingModelPostProcessorTest.java`, `src/test/java/com/example/lms/service/rag/rerank/DppDiversityRerankerTest.java`; symbols=`Matryoshka mechanics/DPP synthetic mechanics`; lines=`78-238`, `24-243`; fileSha256=`FA9A86502FAE951AE391A138C6D3D5EB5DF5297C94B4CD2061D18AD378B26ECF`, `0363AFD6527DFBD6C86A68BD51748CA78824968642CF518CCE933EC55AE71B29`
- observation: Tests use fixed vectors and small synthetic candidate lists to assert slicing, normalization, ordering, and traces; no supplied artifact measures recall, nDCG, latency, and index size on one corpus/configuration before and after both features.
- inference: Claimed quality or efficiency gains cannot be accepted from mechanics and formula-derived trace ratios alone.
- reachability: Both implementations have active tests, but no benchmark dataset/result identity was supplied or executed.
- counterEvidence: Deterministic unit tests cover edge cases and DPP diversity behavior; absence of a benchmark is not evidence of degraded retrieval.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: benchmark
- falsifier: A current same-corpus benchmark artifact reports configuration, corpus hash, recall/nDCG, p50/p95 latency, and index bytes for baseline and enabled variants with acceptance thresholds.
- verification: Run the repository-sanctioned benchmark, if one exists, with fixed corpus/query hashes and both features independently ablated.
- verificationClass: local_build
- verificationCost: high
- minimalRepairSeam: Supply a reproducible benchmark harness/result contract before proposing algorithm changes.
- dependencies: RC-11

### [STKG-01] Session deletion has no proven vector, BrainState, or Neo4j purge

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: cross-store-session-lifecycle
- uniquenessKey: chat-session-delete/jpa-only-with-graph-ingest
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ChatHistoryServiceImpl.java`, `main/java/com/example/lms/service/rag/graph/GraphRagChunkingService.java`; symbol=`deleteSession`, `ingestConversationTurn/persistChunks`; line=`487-492`, `81-132/233-315`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/ChatHistoryServiceImpl.java`, `main/java/com/example/lms/service/rag/graph/GraphRagChunkingService.java`; symbols=`deleteSession/ingestConversationTurn/persistChunks`; lines=`487-492`, `81-132/233-315`; fileSha256=`84139B74F13AE15557F7C86A578065670B017B219A3370DEA4BC8A83BD52B43E`, `148CD705D4CB378126BBE12B7774938615AA558501AD2A67C07DE46409C0936E`
- observation: Chat deletion invokes only `sessionRepository.deleteById`; graph ingestion writes session-scoped content independently to vector, BrainState, and optionally Neo4j owners, and no purge call is present in the delete path.
- inference: Deleting chat history can leave derived session data in auxiliary stores.
- reachability: JPA deletion is active; auxiliary writes depend on graph/vector/Neo4j enablement, and no live store state was queried.
- counterEvidence: Some derived stores may use TTL, external cascade, quarantine, or disabled configuration; no current database/provider evidence established residue.
- stapsStatus: not_observed
- confidence: 0.97
- evidenceSurface: static
- falsifier: A deletion integration contract proves every enabled derived store removes or irreversibly tombstones all data keyed to the session before the endpoint returns.
- verification: Ingest one synthetic session into enabled test doubles, delete it, and query count-only residues by session hash.
- verificationClass: local_test
- verificationCost: high
- minimalRepairSeam: Add one session-lifecycle coordinator at `ChatHistoryService.deleteSession` that delegates to existing store-specific purge/tombstone owners.
- dependencies: API-LIFECYCLE-08


### [STKG-03] DomainKnowledge declares both composite and global name uniqueness

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: knowledge-schema-uniqueness
- uniquenessKey: domain-knowledge/entity-name-global-vs-composite
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/domain/knowledge/DomainKnowledge.java`; symbol=`@Table indexes/entityName`; line=`7-15/31-35`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/domain/knowledge/DomainKnowledge.java`; symbols=`@Table indexes/entityName`; lines=`7-15/31-35`; fileSha256=`7E28D3A807594EA2F50630D1CA557213E326D4D123D726A6B15DDE6D4BE4FF6F`
- observation: The table declares unique `(domain, entityName)`, while `entityName` also has `@Column(unique = true)` despite its comment saying uniqueness should be combined with domain.
- inference: If the active mapping drives schema generation or validation, global entity-name uniqueness can reject the same name in two domains; the deployed constraint behavior is not established by the annotation contradiction alone.
- reachability: `DomainKnowledge` is an active JPA entity and the mapping contradiction is deterministic, but the deployed or externally managed schema constraints were not observed.
- counterEvidence: An externally managed schema may omit the global constraint, and existing datasets may not contain cross-domain duplicate names.
- stapsStatus: contradicted
- confidence: 0.96
- evidenceSurface: static
- falsifier: JPA metadata plus the authoritative migration schema show only composite uniqueness and permit two rows with one entity name in distinct domains.
- verification: Run a schema-backed repository test inserting the same entity name under two domains and inspect generated constraint metadata.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Remove the contradictory field-level uniqueness only after a versioned migration and data precheck establish the authoritative composite constraint.
- dependencies: STKG-08

### [STKG-04] Rolling-summary watermark can skip an unprocessed backlog

- classification: `confirmed_defect`
- priorityInput: P0
- rootCauseClusterId: rolling-summary-watermark
- uniquenessKey: chat-summary/page-24-advance-to-caller-id
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbol=`updateRollingSummary`; line=`550-627`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbol=`updateRollingSummary`; line=`550-627`; fileSha256=`84139B74F13AE15557F7C86A578065670B017B219A3370DEA4BC8A83BD52B43E`
- observation: When a prior watermark exists, the method fetches at most 24 later rows, filters them against `effectiveLastId`, but persists `lastMessageId=effectiveLastId` rather than the largest processed row ID.
- inference: If more than 24 eligible messages lie between the prior watermark and caller ID, later calls skip the unprocessed remainder permanently.
- reachability: Stream completion calls the rolling-summary update with a later assistant message ID; a backlog greater than 24 is reachable in an active session.
- counterEvidence: Normal request cadence may keep deltas below 24, and meta/non-conversation rows are intentionally filtered.
- stapsStatus: contradicted
- confidence: 0.99
- evidenceSurface: static
- falsifier: A repository test with more than 24 post-watermark conversation rows proves repeated updates eventually summarize all rows with no skipped IDs.
- verification: Seed 30 ordered turns after a saved watermark, invoke update with the final ID, and inspect processed content plus stored watermark across two calls.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Persist the maximum actually processed conversation-message ID and iterate bounded pages when the caller target remains ahead.
- dependencies: STKG-05

### [STKG-05] Rolling-summary persistence appends superseded system rows indefinitely

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: rolling-summary-retention
- uniquenessKey: chat-summary/append-new-read-latest-only
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbol=`updateRollingSummary/loadRollingSummary`; line=`613-660`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/ChatHistoryServiceImpl.java`; symbols=`updateRollingSummary/loadRollingSummary`; lines=`613-660`; fileSha256=`84139B74F13AE15557F7C86A578065670B017B219A3370DEA4BC8A83BD52B43E`
- observation: Every successful summary update calls `save(..., "system", RSUM_META_PREFIX + ...)`; reads select only the newest matching row and no compaction/deletion of older summary rows is present.
- inference: Superseded summary payloads accumulate with update count and can enlarge full-transcript materialization paths, but whether that is defective depends on the authoritative retention/audit policy and its storage bounds.
- reachability: Stream completion can update summaries repeatedly for a long-lived session; no workload bound or authoritative rolling-summary retention policy was observed.
- counterEvidence: Meta rows are excluded from recent visible history, and retaining historical summaries can be intentional diagnostic or audit policy.
- stapsStatus: contradicted
- confidence: 0.96
- evidenceSurface: static
- falsifier: A repeated-update repository test proves old summary rows are replaced, bounded, or retained under an explicit audited retention policy with a fixed cap.
- verification: Invoke more updates than the intended cap and count `RSUM_META_PREFIX` rows plus the selected latest payload.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Apply an explicit bounded replace/retention policy in the existing rolling-summary persistence method.
- dependencies: API-HISTORY-07

### [STKG-06] Reingesting one chunk increments entity and relation counters again

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: brainstate-ingest-idempotency
- uniquenessKey: brainstate/replace-chunk-but-increment-aggregates
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/graph/BrainStateService.java`; symbol=`recordChunks`; line=`76-123`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/graph/BrainStateService.java`; symbol=`recordChunks`; line=`76-123`; fileSha256=`D69A45359A30F4623B920F873B1A90C769FC3A4C08638AA6EE6377010E2F9FBD`
- observation: `recordChunks` replaces `chunks[chunkId]`, but every call increments matching entity `mentionCount` and relation `count` without subtracting the prior chunk contribution or detecting an identical preimage.
- inference: Replaying an identical stable chunk increases aggregate entity/relation frequency while stored chunk cardinality remains unchanged; that conflicts with unique-chunk frequency semantics but is consistent with ingestion-event counter semantics.
- reachability: `GraphRagChunkingService.persistChunks` calls `recordChunks`, and repeated ingestion of stable IDs is possible; the authoritative meaning of the counters is not defined on this path.
- counterEvidence: The service may intentionally count ingestion events rather than unique chunk evidence, and no authoritative product policy resolves those alternatives.
- stapsStatus: contradicted
- confidence: 0.96
- evidenceSurface: static
- falsifier: Calling `recordChunks` twice with the same chunk ID and content leaves chunk, entity, relation, and frequency evidence identical to one call under the authoritative counting policy.
- verification: Record one deterministic `KgChunk` twice and compare snapshot counts after the first and second calls.
- verificationClass: local_test
- verificationCost: low
- minimalRepairSeam: Make `recordChunks` reconcile prior chunk contributions by stable chunk ID before updating accumulators.
- dependencies: STKG-01

### [STKG-07] BrainState maps have no lifecycle eviction and ignore snapshot TTL

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: brainstate-memory-retention
- uniquenessKey: brainstate/process-maps-no-remove-ttl-unused
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/graph/BrainStateService.java`, `main/java/com/example/lms/service/rag/graph/BrainStateProperties.java`; symbol=`chunks/entities/relations`, `Indexing.snapshotCacheTtlSeconds`; line=`25-40/76-123/538-567`, `63-101`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/rag/graph/BrainStateService.java`, `main/java/com/example/lms/service/rag/graph/BrainStateProperties.java`; symbols=`process maps/Indexing.snapshotCacheTtlSeconds`; lines=`25-40/76-123/538-567`, `63-101`; fileSha256=`D69A45359A30F4623B920F873B1A90C769FC3A4C08638AA6EE6377010E2F9FBD`, `BBE42C48338ABB5197BD4773873AE348B22394FFC158743FCB60B04FBD618747`
- observation: The service stores chunks, entities, and relations in process-lifetime concurrent maps and exposes no removal/clear path; `snapshotCacheTtlSeconds` is declared only in properties and is not consumed by the service.
- inference: Distinct chunk/entity/relation keys can accumulate for the process lifetime, and the configured TTL does not bound this state.
- reachability: `recordChunks` is active when BrainState indexing is enabled; workload cardinality and heap impact were not measured.
- counterEvidence: Chunk IDs replace in place, snapshot selection applies a configured entity limit, and process restarts clear the maps.
- stapsStatus: partial
- confidence: 0.98
- evidenceSurface: static
- falsifier: A lifecycle test proves inactive/session-deleted entries are evicted within the configured TTL and long-running map cardinality remains within an explicit bound.
- verification: Use a controllable clock to ingest distinct sessions, advance beyond TTL, trigger the documented maintenance path, and inspect count-only map sizes.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Implement bounded lifecycle eviction in `BrainStateService` using the existing indexing retention properties, or remove/rename the unused TTL claim.
- dependencies: STKG-01, STKG-06

### [STKG-08] Production schema ownership is not established by this checkout

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: database-migration-ownership
- uniquenessKey: jpa-validate/no-complete-versioned-migration-owner
- activeOwner: sourceSet=`main/resources`; path=`main/resources/application.properties`, `main/resources/db/README.md`; symbol=`spring.jpa.hibernate.ddl-auto`, `DB schema notes`; line=`200-217`, `1-15`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/resources/application.properties`, `main/resources/db/README.md`; symbols=`ddl-auto/schema notes`; lines=`200-217`, `1-15`; fileSha256=`5A029C51BEACBB949B1132F94B36A0957CEBEDAF6E7C1BE500F4EEB100CC6C9C`, `E59A5784CA9169875B79BBBE8AFCA5702872878C9C7AD2F6FA980B3EE7DA24B5`
- observation: The active default sets `ddl-auto` to environment-or-`validate`; the DB README says no Flyway dependency and an intentionally empty migration directory, while only a limited DDL patch area is documented.
- inference: The checkout does not identify a complete versioned owner capable of creating/upgrading every production JPA structure that validation expects.
- reachability: Effective production schema management and current database constraints were not observed; external operations may own the schema.
- counterEvidence: External DBA/deployment migrations may be authoritative, and validation is safer than implicit production mutation.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: database
- falsifier: A current deployment artifact maps every JPA-required table/index/constraint to an ordered versioned migration and proves the production database applied through the expected version.
- verification: Obtain a read-only schema/migration-history snapshot and compare it with the active JPA metadata plus repository-owned migration inventory.
- verificationClass: external
- verificationCost: high
- minimalRepairSeam: Establish the authoritative migration owner and completeness evidence before changing entity mappings or DDL mode.
- dependencies: none

### [STKG-09] Periodic CFVM snapshots append without retention

- classification: `structural_risk`
- priorityInput: P2
- rootCauseClusterId: cfvm-snapshot-retention
- uniquenessKey: cfvm-snapshot/minute-append-latest-read-only
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/cfvm/CfvmSnapshotService.java`; symbol=`restoreOnStartup/periodicSnapshot/persistSnapshot`; line=`39-100`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/cfvm/CfvmSnapshotService.java`; symbols=`restoreOnStartup/periodicSnapshot/persistSnapshot`; lines=`39-100`; fileSha256=`B8AA7DCF8B64C007BBDA04E64B2F75483BAF54DAC6210D39EE7EF6927D76B13E`
- observation: A scheduled method persists a new JPA snapshot every configured minute, startup reads only the latest row, and no deletion, compaction, uniqueness, or retention operation is present.
- inference: When scheduling and the repository are active, snapshot rows grow with process uptime although consumers need only the newest state.
- reachability: The service and schedule are active components, but scheduler enablement, database availability, and live row growth were not observed.
- counterEvidence: Operators can increase the interval, external retention may exist, and historical snapshots may have audit value under an unstated policy.
- stapsStatus: not_observed
- confidence: 0.98
- evidenceSurface: static
- falsifier: A scheduler/repository test or migration proves snapshots are capped or expired under an explicit retention policy while latest restore remains correct.
- verification: Advance a test scheduler across more intervals than the intended cap and inspect row count plus latest restored state.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Add explicit bounded retention to `CfvmSnapshotService.persistSnapshot` or its repository owner.
- dependencies: STKG-08

### [STKG-10] CFVM learned state overwrites its canonical file non-atomically

- classification: `structural_risk`
- priorityInput: P1
- rootCauseClusterId: cfvm-file-durability
- uniquenessKey: cfvm-bandit/direct-overwrite-no-shutdown-flush
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/rag/learn/CfvmBanditStore.java`; symbol=`loadBestEffort/maybeFlush/flushBestEffort`; line=`48-98/114-180`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/rag/learn/CfvmBanditStore.java`; symbols=`loadBestEffort/maybeFlush/flushBestEffort`; lines=`48-98/114-180`; fileSha256=`684DCBB7C80ED4351DF1DEDDBDFD489973E194140152F560C78CA60A5EAFAF2B`
- observation: `flushBestEffort` serializes current tiles and writes bytes directly to the canonical path; there is no temporary-file atomic move, backup recovery, checksum, or shutdown hook, and load failure marks the store loaded fail-soft.
- inference: A crash/interruption during overwrite can leave unreadable learned state, while updates inside the rate-limit interval can be lost on shutdown.
- reachability: Updates call `maybeFlush` when learning is enabled; crash timing and filesystem atomicity were not observed.
- counterEvidence: Writes are synchronized, the file is small, failures do not break requests, and the next successful interval can rewrite state.
- stapsStatus: partial
- confidence: 0.98
- evidenceSurface: static
- falsifier: Active-path inspection proves an existing enclosing durable writer, backup/recovery owner, or lifecycle shutdown owner already protects this canonical file, and a focused recovery check demonstrates that protection handles interrupted overwrite and pending dirty state.
- verification: Interrupt writes at each filesystem phase and simulate shutdown before the rate-limit interval, then restart and compare recovered aggregate hashes.
- verificationClass: local_test
- verificationCost: high
- minimalRepairSeam: Use same-directory temporary write plus fsync/atomic replace and an idempotent lifecycle flush in `CfvmBanditStore`.
- dependencies: none


## Nine-Row Reserve Appendix

These nine full-schema rows remain visible for accounting but are excluded from primary classification totals and the default Top 10 unless supplied evidence justifies promotion. `STKG-02` is intentionally absent because it is already merged into canonical primary row `API-ARCHIVE-05`.

Reserve IDs: `AUTO-02`, `DEP-01`, `JAVA-01`, `RC-07`, `RC-08`, `RC-12`, `R12`, `AL-11`, `TBL-10`.

### [AUTO-02] Modern and legacy auto-configuration metadata both name NovaProtocolConfig

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: autoconfiguration-metadata-duplication
- uniquenessKey: app-autoconfig/modern-and-legacy-nova-protocol-entry
- activeOwner: sourceSet=`app/src/main/resources`; path=`app/src/main/resources/META-INF/spring.factories`, `app/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; symbol=`EnableAutoConfiguration`, `AutoConfiguration.imports`; line=`2`, `1-2`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`app/src/main/resources/META-INF/spring.factories`, `app/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`; symbols=`EnableAutoConfiguration/AutoConfiguration.imports`; lines=`2`, `1-2`; fileSha256=`D5517F04153BCD124824D29259B5F22524BC488741E3C5BD6435DCE2616EAFA2`, `FEBE5CC25FA19C90511D2BBF7DC54BDCEA8766079175806035B489B89E4387F9`
- observation: Both the legacy `spring.factories` key and modern imports file name `com.nova.protocol.config.NovaProtocolConfig` in the active app resource set.
- inference: Packaging may present the same auto-configuration candidate through two discovery mechanisms, but duplicate candidate processing or bean creation is not established.
- reachability: The resources are part of the active app resource root; isolated packaged-app discovery was not executed.
- counterEvidence: Reserve reason: Spring Boot may deduplicate identical auto-configuration candidates, and the configuration uses several missing-bean guards.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: runtime
- falsifier: An isolated packaged app context records exactly one effective `NovaProtocolConfig` candidate and no duplicate registration side effect across supported Spring Boot discovery paths.
- verification: Build only the app resource fixture and inspect condition-evaluation/configuration-class events by class name and count.
- verificationClass: local_test
- verificationCost: medium
- minimalRepairSeam: Reserve only; remove one metadata entry only if isolated loading proves duplication and the supported Spring Boot compatibility target selects the canonical format.
- dependencies: none

### [DEP-01] Optional legacy modules are outside the observed dependency-purity graph

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: optional-module-dependency-proof
- uniquenessKey: gradle-legacy-modules/disabled-unscanned-dependencies
- activeOwner: sourceSet=`build`; path=`settings.gradle.kts`, `build.gradle.kts`; symbol=`includeLegacyModules`, `checkLangchain4jVersionPurity`; line=`93-108`, `142-154`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`settings.gradle.kts`, `build.gradle.kts`; symbols=`includeLegacyModules/checkLangchain4jVersionPurity`; lines=`93-108`, `142-154`; fileSha256=`989A5870C5E2EBFDA4E248F2A39CBBBD8008C9F601539DD16E187E216F1370CD`, `B4D9D4791F0F256CD312267564F889F1E0A30F88D95024FF85B5CB1451C397F4`
- observation: Legacy projects are included only when `includeLegacyModules` is true; the root purity task scans dependencies from root configurations and the observed project graph left those optional modules disabled.
- inference: The current purity result does not establish LangChain4j version consistency inside an enabled legacy graph.
- reachability: The settings switch can include legacy modules, but that graph was not enabled or resolved in the frozen snapshot.
- counterEvidence: Reserve reason: optional legacy modules are not active runtime owners in the observed project graph, so no current production dependency defect is established.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: build
- falsifier: A legacy-enabled dependency report proves every included project's resolved LangChain4j modules are exactly `1.0.1` and the purity gate covers them transitively.
- verification: Run a read-only dependency/version inventory with legacy inclusion in an isolated Gradle cache and compare all subprojects.
- verificationClass: local_build
- verificationCost: high
- minimalRepairSeam: Reserve only; extend the existing purity task across included subprojects if the legacy graph becomes supported and evidence shows a coverage gap.
- dependencies: BUILD-01

### [JAVA-01] Root build declares compatibility but not a Java toolchain

- classification: `structural_risk`
- priorityInput: unranked
- rootCauseClusterId: java-toolchain-reproducibility
- uniquenessKey: root-gradle/source-compatibility-without-toolchain
- activeOwner: sourceSet=`build`; path=`build.gradle.kts`; symbol=`java.sourceCompatibility`; line=`49-55`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`build.gradle.kts`; symbol=`java.sourceCompatibility`; line=`49-55`; fileSha256=`B4D9D4791F0F256CD312267564F889F1E0A30F88D95024FF85B5CB1451C397F4`
- observation: The root build sets `sourceCompatibility=JavaVersion.VERSION_17` but declares no `JavaLanguageVersion` toolchain owner in this block.
- inference: Builds can select the ambient JDK rather than a Gradle-provisioned Java 17 compiler, weakening cross-host reproducibility.
- reachability: Every root Java compile uses this build configuration; a mismatched host JDK was not observed.
- counterEvidence: Reserve reason: the frozen environment reports Java 17, so the immediate checkout does not demonstrate a compiler mismatch.
- stapsStatus: partial
- confidence: 0.99
- evidenceSurface: build
- falsifier: Repository wrappers or CI launch contracts reject non-Java-17 runtimes before configuration and prove identical compiler vendor/version inputs across supported hosts.
- verification: Run Gradle JVM/toolchain diagnostics on each supported host and compare major/vendor/compiler identity.
- verificationClass: local_build
- verificationCost: medium
- minimalRepairSeam: Reserve only; add a root Java 17 toolchain declaration if cross-host evidence shows ambient-JDK drift.
- dependencies: none

### [RC-07] HYPERNOVA final-gate ordering lacks integration observation

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: hypernova-gate-order
- uniquenessKey: nova-next-fusion/downstream-final-gate-pending
- activeOwner: sourceSet=`main/java`; path=`main/java/com/nova/protocol/fusion/NovaNextFusionService.java`, `main/java/com/example/lms/service/rag/fusion/WeightedReciprocalRankFuser.java`; symbol=`fuse/fuseInternal`, `applyNovaNext`; line=`131-247`, `208-265`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/nova/protocol/fusion/NovaNextFusionService.java`, `main/java/com/example/lms/service/rag/fusion/WeightedReciprocalRankFuser.java`; symbols=`fuse/fuseInternal/applyNovaNext`; lines=`131-247`, `208-265`; fileSha256=`7B0A59262D1C537166F5F1AA899FBD8A866F8ED05A88B6549F18B64D2CA8B279`, `92DC19E0355566E06F15C45276F6686934101C6FA5668C3A1F989E823B392169`
- observation: Nova fusion marks `hypernova.finalGatePassed=false` with reason `downstream_gate_callback_pending`, performs score adjustment, Risk-K allocation, and DPP, then returns to the weighted fuser; these owners do not execute the final gate callback.
- inference: The relative order and enforcement of the final gate elsewhere in the full retrieval path cannot be established from these owners alone.
- reachability: `WeightedReciprocalRankFuser` calls Nova fusion only when its optional bean is present; a complete enabled-path integration trace was not observed.
- counterEvidence: Reserve reason: the source explicitly labels the gate as downstream, so absence inside the fusion service is not by itself a defect.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: runtime
- falsifier: An enabled integration trace proves one final gate executes after all HYPERNOVA adjustments and before results reach prompt evidence, with rejected candidates unable to re-enter.
- verification: Run a synthetic enabled pipeline with trace-only candidate IDs and assert the ordered event sequence from fusion through final evidence selection.
- verificationClass: runtime
- verificationCost: high
- minimalRepairSeam: Reserve only; identify the existing downstream gate owner and change ordering only if the integration trace contradicts the intended contract.
- dependencies: RC-08

### [RC-08] HYPERNOVA profile activation is not observed

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: hypernova-runtime-activation
- uniquenessKey: nova-next/profile-and-property-unobserved
- activeOwner: sourceSet=`main/java`, `main/resources`; path=`main/java/com/nova/protocol/config/NovaProtocolConfig.java`, `main/resources/application-nova-next.yml`; symbol=`novaNextFusionService`, `novanext profile`; line=`83-97`, `1-18`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/nova/protocol/config/NovaProtocolConfig.java`, `main/resources/application-nova-next.yml`; symbols=`novaNextFusionService/novanext profile`; lines=`83-97`, `1-18`; fileSha256=`4A5B4810E884EBFAE94283442BC214E67F8C407C91FAFA8463CBCE579E9E674C`, `C6CB0A202AE957A9E7B12501ED8AFA6B81021943AD97D35017324B570418197E`
- observation: The fusion bean requires `nova.next.enabled=true`, and the shipped profile file supplies that value only when profile `novanext` is activated.
- inference: Static source proves an activation contract but not that any current runtime selects the profile or bean.
- reachability: A context-runner test covers explicit true/false/missing values; no live profile, condition report, or request trace was gathered.
- counterEvidence: Reserve reason: default-off conditional wiring is intentional and prevents an unobserved optional algorithm from being treated as an active defect.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: runtime
- falsifier: A current runtime condition report and same-request trace show the `novanext` profile, singleton bean, and `hypernova.active=true` on an ordinary retrieval path.
- verification: Start an isolated runtime with the sanctioned profile and capture redacted active-profile, bean-presence, and one request trace booleans.
- verificationClass: runtime
- verificationCost: high
- minimalRepairSeam: Reserve only; retain conditional wiring unless a required deployment is proven to expect HYPERNOVA without activation.
- dependencies: none

### [RC-12] ChatWorkflow concentrates a broad orchestration surface

- classification: `structural_risk`
- priorityInput: unranked
- rootCauseClusterId: workflow-maintainability
- uniquenessKey: chat-workflow/large-multi-responsibility-owner
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ChatWorkflow.java`; symbol=`ChatWorkflow`; line=`193-12629`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; path=`main/java/com/example/lms/service/ChatWorkflow.java`; symbol=`ChatWorkflow`; line=`193-12629`; fileSha256=`367E7D95671769B057EA3762E7AF9D22480A6CE2388BE39C83547FB2E732A10B`
- observation: The active `ChatWorkflow` class spans line 193 through 12629 and owns numerous injected retrieval, routing, prompt, model, diagnostics, and fallback collaborators.
- inference: Its size and responsibility breadth increase navigation, review, and change-coupling cost, but they do not identify one behavioral failure or repair seam.
- reachability: The class is the active chat workflow owner; no defect-rate, change-coupling, or performance evidence was computed.
- counterEvidence: Reserve reason: God-class size is real but less decision-specific than the primary behavioral seams, and broad refactoring would violate the smallest-repair rule.
- stapsStatus: partial
- confidence: 0.99
- evidenceSurface: static
- falsifier: Ownership and change-history analysis proves the class is a cohesive façade with stable bounded regions and no elevated coupling or regression concentration relative to peers.
- verification: Produce a read-only method/collaborator/change-coupling map and compare it with active callers and focused test ownership.
- verificationClass: local_read_only
- verificationCost: high
- minimalRepairSeam: Reserve only; extract nothing solely for size, and move code only when a retained behavioral row proves one bounded owner seam.
- dependencies: multiple-primary-behavioral-rows

### [R12] Process-local CFVM telemetry does not prove failed recovery

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: cfvm-recovery-evidence
- uniquenessKey: cfvm-raw-buffer/telemetry-not-recovery-outcome
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/cfvm/RawMatrixBuffer.java`, `main/java/com/example/lms/cfvm/CfvmFailureRecorder.java`; symbol=`RawMatrixBuffer`, `record/traceNormalizedContract`; line=`17-27/78-170`, `61-120`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/cfvm/RawMatrixBuffer.java`, `main/java/com/example/lms/cfvm/CfvmFailureRecorder.java`; symbols=`RawMatrixBuffer/record/traceNormalizedContract`; lines=`17-27/78-170`, `61-120`; fileSha256=`8FFDB20218D41274369AE33B6D985AD1D729184D7A72142CF6B1A1A5DCA1AA43`, `455DC21F4EC9E6F9FBE6281B1AAEB5BC20931430DC81153D2A4EB1330F3AA16F`
- observation: `RawMatrixBuffer` explicitly describes itself as process-local telemetry, while the recorder emits buffered, memory-recorded, weight, and retrieval-order trace facts without measuring a later request outcome.
- inference: Those trace keys cannot by themselves establish that CFVM recovery failed or succeeded.
- reachability: Failure recording is wired to buffer and optional memory/training owners; no controlled failure-to-recovery sequence was executed.
- counterEvidence: Reserve reason: durable failure-pattern memory and snapshot services exist, so process-local scope alone is not evidence of missing recovery.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: runtime
- falsifier: A deterministic failure/retry experiment correlates one recorded pattern with a changed retrieval decision and improved same-class outcome under a bounded baseline.
- verification: Run paired same-seed failure/recovery requests and retain only decision, outcome, count, and hash lineage.
- verificationClass: runtime
- verificationCost: high
- minimalRepairSeam: Reserve only; add outcome lineage at the existing recorder/recovery boundary before considering recovery algorithm changes.
- dependencies: STKG-09, STKG-10

### [AL-11] Optional OCR engine and large-input behavior are unobserved

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: optional-ocr-runtime-proof
- uniquenessKey: ocr/disabled-optional-engine-unbounded-read
- activeOwner: sourceSet=`main/java`; path=`main/java/com/example/lms/service/ocr/BasicTesseractOcrService.java`, `main/java/com/example/lms/service/rag/retriever/OcrRetriever.java`; symbol=`extract`, `retrieve`; line=`12-38`, `25-69/83-159`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`main/java/com/example/lms/service/ocr/BasicTesseractOcrService.java`, `main/java/com/example/lms/service/rag/retriever/OcrRetriever.java`; symbols=`extract/retrieve`; lines=`12-38`, `25-69/83-159`; fileSha256=`59CE31A10DF599E0A07EA1B2CE84B1D84642234FD2FF46D88A3A5EC5BAF7D13A`, `A89FB02DC812B6A262776E7262DE27E3F3341C187FB6E5409FA438B41DFDA5E5`
- observation: The OCR retriever defaults disabled and treats its delegate as optional; the basic service reads the entire input stream into bytes before delegating and returns empty when the engine is absent or errors.
- inference: Enabled-engine correctness, memory behavior for large inputs, and user-visible fail-soft semantics cannot be determined from static defaults.
- reachability: The optional path requires `rag.ocr.enabled=true` plus a delegate; neither an engine nor a large input was exercised.
- counterEvidence: Reserve reason: OCR is optional and disabled by default, and retrieval output is text-clamped and fail-soft.
- stapsStatus: not_observed
- confidence: 0.99
- evidenceSurface: runtime
- falsifier: An enabled isolated OCR test with the supported engine enforces an input-byte/page cap, returns bounded results, and emits explicit disabled/missing/oversize outcomes.
- verification: Run synthetic small and over-limit image/PDF fixtures with present and absent delegates while recording only sizes, counts, and reason codes.
- verificationClass: runtime
- verificationCost: high
- minimalRepairSeam: Reserve only; establish the supported OCR engine and input-bound contract before changing the existing optional adapter.
- dependencies: none

### [TBL-10] Source-text assertions lack a portfolio denominator

- classification: `evidence_needed`
- priorityInput: unranked
- rootCauseClusterId: source-text-test-portfolio
- uniquenessKey: test-portfolio/source-string-assertions-without-coverage-denominator
- activeOwner: sourceSet=`src/test/java`; path=`src/test/java/com/example/lms/agent/context/AgentDbContextContractTest.java`, `src/test/java/com/example/lms/service/rag/rerank/DppDiversityRerankerTest.java`; symbol=`source contract assertions`, `source description/wiring assertions`; line=`16-82`, `69-77/194-201`
- sourceAnchor: branch=`codex/owned-runtime-browser-restart`; head=`0796a3c5b29bbb08c3314bd40649d856d4a7bce6`; capturedAt=`2026-08-13T15:46:38.3020208Z`; paths=`src/test/java/com/example/lms/agent/context/AgentDbContextContractTest.java`, `src/test/java/com/example/lms/service/rag/rerank/DppDiversityRerankerTest.java`; symbols=`representative source-text assertions`; lines=`16-82`, `69-77/194-201`; fileSha256=`8384E3E7AF7234972E15233B77822D83E53B2A819E1569839682DE03D0F3545C`, `0363AFD6527DFBD6C86A68BD51748CA78824968642CF518CCE933EC55AE71B29`
- observation: Representative active tests read production source and assert string presence/absence for configuration, redaction, descriptions, and wiring; no supplied metric counts these against behavioral tests or uncovered runtime contracts.
- inference: The broad claim that source-text tests dominate the portfolio cannot be quantified or tied to one repair seam from the inspected examples.
- reachability: These tests run in the active test source set; a complete portfolio taxonomy and coverage denominator were not generated.
- counterEvidence: Reserve reason: source-text contracts can validly protect metadata, redaction, and architecture invariants, so their existence is not itself a defect.
- stapsStatus: not_observed
- confidence: 0.98
- evidenceSurface: test
- falsifier: A complete test taxonomy shows source-text assertions are a bounded minority or are paired with behavioral coverage for every critical contract.
- verification: Classify all active tests by source-text, unit behavior, slice, integration, runtime, and benchmark evidence, then report counts without inferring quality from count alone.
- verificationClass: local_read_only
- verificationCost: high
- minimalRepairSeam: Reserve only; create a portfolio metric before replacing any individual source-text test, and preserve valid architecture/redaction contracts.
- dependencies: TBL-04

## Required Adjudication Procedure

Perform these steps in order, using only the supplied snapshot and rows:

1. Restate the authoritative active-source boundary, snapshot identity, STAPS role, and evidence limitations.
2. Produce exactly one coverage-accounting result for every primary ID with status exactly `confirm | merge | downgrade | reject | hold`. Keep merged and rejected IDs visible with reasons. A merge must name the retained canonical ID.
3. Verify internally that all 100 unique primary IDs are present once in the accounting. If any are missing, duplicated, or use another status, return `context_coverage_incomplete` and stop ranking.
4. Identify genuine duplicates and shared root-cause clusters. Do not merge rows merely because they share a subsystem, owner, severity, or dependency.
5. Challenge each supplied observation with its counter-evidence and falsifier. Correct classification and confidence without inventing source, runtime, browser, provider, database, benchmark, or product-policy facts.
6. Recompute classification totals from retained primary IDs. Keep `evidence_needed` separate from defect and risk totals. Explain how merges affect accounting without making IDs disappear.
7. Rank by severity, active-path reachability, likelihood, blast radius, evidence confidence/freshness, repair scope, verification cost, and dependency ordering—not reviewer count or label alone.
8. Produce a Top 10 immediate set and Top 25 ordered backlog. The Top 10 must be a strict subset of the Top 25: every Top 10 item appears unchanged in the Top 25, and the Top 25 contains at least one additional item. Cap any one `rootCauseClusterId` at three Top 10 entries unless you explicitly explain a cross-system emergency and why separate repairs are unavoidable.
9. For every retained ranking item, select one existing active owner and one minimal behavioral seam. Do not output a diff or invent infrastructure.
10. Give each retained ranking item one focused RED assertion, one ordered GREEN/regression ladder, one verification class, and one stop condition. Label proposed checks as not run.
11. Separate local read/build/test work from runtime and external verification. Place unresolved runtime, browser, provider, database, benchmark, and product-policy needs in their required sections.
12. End with a concise advisory recommendation that preserves all non-authority and stop rules.

## Required Response Schema

Return the following top-level sections in exactly this order and with these exact headings:

```markdown
# SOURCE BOUNDARY RESTATEMENT
# ID COVERAGE AUDIT
# MERGE AND ROOT-CAUSE MAP
# CORRECTED CLASSIFICATION TOTALS
# TOP 10 IMMEDIATE
# TOP 25 ORDERED BACKLOG
# HOLD AND EVIDENCE_NEEDED
# PRODUCT-POLICY DECISIONS
# MINIMAL REPAIR AND VERIFICATION SEQUENCE
# REJECTED OR DOWNGRADED
# FINAL RECOMMENDATION
```

Requirements by section:

- `# SOURCE BOUNDARY RESTATEMENT`: state the supplied branch, HEAD, capture time, active source sets, canonical main class, Java 17, STAPS hash and `design_claim_map` role, ledger hash, and unavailable evidence surfaces. Say explicitly that you had no repository, runtime, provider, database, browser, credential, or conversation access.
- `# ID COVERAGE AUDIT`: provide exactly 100 unique primary-ID coverage records, one for every supplied primary ID. Each record must include `id`, `status`, `canonicalRetainedId` (`self`, another supplied primary ID, or `none`), `correctedClassification`, `reason`, and `confidence`. `status` must be exactly one of `confirm | merge | downgrade | reject | hold`. For `confirm`, `downgrade`, and `hold`, `correctedClassification` must be exactly one of `confirmed_defect | structural_risk | evidence_needed`, consistent with the supplied evidence. For `merge`, set `correctedClassification` to the canonical retained row's corrected classification and set `canonicalRetainedId` to that supplied primary ID; the merged-away ID remains a coverage record but not a separate retained classification total. For `reject`, set `correctedClassification` to the literal `none`, set `canonicalRetainedId` to `none`, and provide the rejection reason; the rejected ID remains a coverage record but is excluded from corrected retained classification totals. `none` is legal only as the `correctedClassification` of a `reject` record and is not a fourth audit classification. Merged and rejected IDs remain visible. After the records, print `primaryIdsSupplied`, `primaryIdsAccounted`, `primaryIdsUnique`, `missingIds`, `duplicateIds`, `unknownIds`, `invalidStatuses`, and `coverageResult`.
- `# MERGE AND ROOT-CAUSE MAP`: show every merge as `canonicalRetainedId <- mergedId(s)` with source-backed reason; then show retained clusters without treating cluster membership as automatic duplication.
- `# CORRECTED CLASSIFICATION TOTALS`: report retained primary counts for `confirmed_defect`, `structural_risk`, and `evidence_needed`; separately report merged and rejected ID counts so the full 100-ID accounting remains reconcilable.
- `# TOP 10 IMMEDIATE` and `# TOP 25 ORDERED BACKLOG`: use ordered entries. Every entry must include all of: `rank`, `retainedIds`, `rootCauseClusterId`, `activeOwner`, `reason`, `severity`, `reachability`, `likelihood`, `blastRadius`, `confidence`, `dependency`, `minimalRepairSeam`, `redAssertion`, `greenRegressionLadder`, `verificationClass`, and `stopCondition`. The Top 10 must be a strict subset of the Top 25; preserve identical retained IDs and ranking-item content for shared entries. Use no more than three Top 10 entries from one root-cause cluster unless an itemized cross-system emergency explanation follows the list.
- `# HOLD AND EVIDENCE_NEEDED`: list every held or evidence-needed primary and any reserve considered, the missing evidence surface, the exact bounded verification action, and the lane-local stop reason. Do not convert one lane's gap into a repository-wide hold.
- `# PRODUCT-POLICY DECISIONS`: list only choices that cannot be derived from the supplied evidence, with the affected IDs, decision owner, alternatives, and consequence of deferral.
- `# MINIMAL REPAIR AND VERIFICATION SEQUENCE`: order proposed work by dependencies. For each step, name existing owner, behavioral constraint, RED assertion, GREEN/regression ladder, verification class, stop condition, and whether external authority/evidence would be required. State `proposed_not_run: true` for every step.
- `# REJECTED OR DOWNGRADED`: retain every rejected, downgraded, or merged-away ID with its supplied classification, corrected classification/status, counter-evidence or falsifier basis, and reason. Do not hide IDs through merging.
- `# FINAL RECOMMENDATION`: summarize the advisory order, unresolved evidence, non-authority boundary, and `coverageResult` without claiming any change or verification ran.

If `coverageResult` is not `complete`, still emit all headings in the required order, set `coverageResult: context_coverage_incomplete`, and place `not_produced: context_coverage_incomplete` under both ranking headings and every downstream ranking-dependent section.

## Stop Conditions

- Fewer than or more than 100 unique valid primary-ID coverage records, any missing, duplicate, or unknown primary ID, or any status outside `confirm | merge | downgrade | reject | hold`: return `context_coverage_incomplete`; stop ranking and do not improvise a replacement row.
- Snapshot, active source set, or row-anchor change explicitly supplied: use `hold / snapshot_changed` for the affected row; do not infer current code.
- Missing runtime, browser, provider, database, or benchmark evidence: retain the row at its supported surface and report `hold` or `evidence_needed`; do not promote it.
- Ambiguous product policy: use `hold / product_decision_needed` for the affected row only.
- Valid `merge` and `reject` outcomes may reduce the retained canonical row count below 100. That is not a coverage failure when all 100 original primary IDs still have exactly one valid coverage record; do not split, pad, or promote reserve rows to replace merged-away or rejected rows.
- No implementation authority: do not output diffs, replacement source, source edits, new frameworks, duplicate owners, protected-property renames, Spring Boot or LangChain4j changes, dependency changes, registry/manifest changes, Git operations, deployment actions, database mutations, process actions, browser actions, or external calls.
- No credential authority: do not request, use, echo, infer, or reconstruct credentials, authorization material, cookies, owner tokens, private environment values, raw private prompts, provider responses, or full errors.
- No invented proof: do not invent benchmark gains, runtime activation, provider attempts, database state, browser behavior, test results, build results, command output, or claim a proposed verification already passed.
- No broad redesign: reject any recommendation whose first step is a new framework, duplicate orchestration layer, duplicate route, shadow implementation, or generalized infrastructure where the supplied row identifies a smaller existing owner seam.

Your final answer is an advisory adjudication of the enclosed snapshot only.
