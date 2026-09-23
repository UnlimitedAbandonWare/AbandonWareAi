# GPT Pro 100-Row Source Audit Context Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a verified 100-row source-audit ledger and one standalone, copy-paste-ready GPT Pro prompt that asks what `demo-1` should repair first.

**Architecture:** Keep the evidence ledger and the model instruction separate: the ledger owns current source observations, classifications, falsifiers, and snapshot anchors; the standalone prompt embeds the ledger and owns GPT Pro's adjudication and response contract. Generate both as Markdown only, preserve the existing dirty checkout, and validate cross-file ID coverage with read-only PowerShell commands.

**Tech Stack:** Markdown, PowerShell 5.1+, Git read-only inspection, repository Java/Spring/Gradle source evidence, SHA-256.

## Global Constraints

- Work only in `C:\AbandonWare\demo-1\demo-1\src` on the current branch and HEAD.
- Read `docs/superpowers/specs/2026-08-13-gpt-pro-source-audit-context-design.md` before every task.
- Treat `C:\Users\nninn\Downloads\staps.txt` as `design_claim_map`, never as current runtime truth; expected design hash is `A4AB90929E7C7541C3B4A9B28A60ED4234578D708F641D3479380FF15CFFBE16` unless the changed hash is explicitly reported and mappings are refreshed.
- Active owners default to `main/java`, `main/resources`, `src/test/java`, `app/src/main/java_clean`, and `app/src/main/resources`; verify rather than infer any exception.
- Keep Java 17, Spring Boot's existing version, every `dev.langchain4j` dependency at exactly `1.0.1`, and final prompt construction at `PromptBuilder.build(PromptContext)` as hard invariants.
- Create or modify only the approved design, plan, ledger, and standalone prompt. Do not modify Java, resources, tests, Gradle, manifests, registered prompt packs, generated prompt output, PatchDrop, runtime memory, or application data.
- Do not stage, commit, push, change branches, deploy, start runtime processes, or call providers, databases, Supabase, Browser, or production systems.
- Do not expose credentials, headers, cookies, environment values, raw private prompts, provider responses, full errors, or share mappings. Use counts, reason codes, boolean presence, bounded synthetic examples, and hashes.
- `confirmed_defect`, `structural_risk`, and `evidence_needed` remain separate. `evidence_needed` counts as an audit row but not as a defect or default Top 10 candidate.
- A changed target anchor downgrades only that row to `evidence_needed / snapshot_changed`; it does not authorize local source repair.
- Use `apply_patch` for Markdown writes. Preserve every unrelated dirty file and hunk.
- The plan deliberately contains no commit step because operation-level commit authority was not granted.

---

## File Structure

- Existing design: `docs/superpowers/specs/2026-08-13-gpt-pro-source-audit-context-design.md`
  - Owns scope, selection policy, schemas, and acceptance criteria.
- Create: `docs/audits/2026-08-13-demo1-source-audit-100-ledger.md`
  - Owns the redacted snapshot, 100 primary audit rows, nine reserve rows, category totals, and source anchors.
- Create: `agent-prompts/gpt_pro_demo1_source_audit_100.md`
  - Owns the self-contained GPT Pro task, embeds the compact ledger, and fixes the output/adjudication contract.
- Create: `docs/superpowers/plans/2026-08-13-gpt-pro-source-audit-context.md`
  - Owns this implementation sequence only.

The untracked `docs/superpowers/specs/2026-08-13-whole-source-shock-auditor-design.md` belongs to a separate registered-scanner design and must remain untouched.

---

### Task 1: Freeze the Evidence Snapshot and Create the Ledger Contract

**Files:**
- Read: `docs/superpowers/specs/2026-08-13-gpt-pro-source-audit-context-design.md`
- Read: `C:\Users\nninn\Downloads\staps.txt`
- Create: `docs/audits/2026-08-13-demo1-source-audit-100-ledger.md`

**Interfaces:**
- Consumes: approved design schema and the current Git/sourceSet snapshot.
- Produces: ledger headings, immutable snapshot fields, row format, primary ID inventory, reserve ID inventory, and category-count contract used by Tasks 2-5.

- [ ] **Step 1: Recheck the authority snapshot without printing dirty filenames**

Run:

```powershell
$Root = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location $Root
$snapshot = [ordered]@{
  capturedAt = (Get-Date).ToUniversalTime().ToString('o')
  branch = (git branch --show-current)
  head = (git rev-parse HEAD)
  dirtyCount = @(git status --short).Count
  indexLockPresent = Test-Path '.git\index.lock'
  stapsSha256 = (Get-FileHash -Algorithm SHA256 'C:\Users\nninn\Downloads\staps.txt').Hash
  stapsBytes = (Get-Item 'C:\Users\nninn\Downloads\staps.txt').Length
}
$snapshot | ConvertTo-Json -Compress
```

Expected: nonblank branch and 40-character HEAD; `indexLockPresent=false`; STAPS hash equals the Global Constraints value. If branch, HEAD, or STAPS hash differs, record the new value and refresh affected source mappings before continuing.

- [ ] **Step 2: Reconfirm source-set ownership and Java 17**

Run:

```powershell
java -version
Select-String -Path 'build.gradle.kts','app\build.gradle.kts' `
  -Pattern 'srcDirs|java_clean|main/resources|src/test/java|mainClass'
```

Expected: Java major 17; root and `:app` source paths remain those in Global Constraints; canonical root main class remains `com.example.lms.LmsApplication`.

- [ ] **Step 3: Create the ledger header and exact row contract**

Use `apply_patch` to create the ledger with these fixed top-level sections:

```markdown
# demo-1 Source Audit Ledger: 100 Primary Rows

## Evidence Snapshot
## Classification and Evidence Rules
## Primary Audit Rows
## Reserve Audit Rows
## Category Totals
## Generation Verification
```

Copy the exact Step 1 snapshot values into `Evidence Snapshot`. Under
`Classification and Evidence Rules`, copy the row schema and same-surface
rules from the approved design. Define each row heading as exactly:

```markdown
### [AUDIT-ID] Short factual title
```

and each row body as exactly these labelled fields:

```markdown
- classification:
- priorityInput:
- rootCauseClusterId:
- uniquenessKey:
- activeOwner:
- sourceAnchor:
- observation:
- inference:
- reachability:
- counterEvidence:
- stapsStatus:
- confidence:
- evidenceSurface:
- falsifier:
- verification:
- verificationClass:
- verificationCost:
- minimalRepairSeam:
- dependencies:
```

Use `none_observed` or `none` for an intentionally empty semantic field; never leave a label blank in the completed ledger.

- [ ] **Step 4: Record the exact primary and reserve inventories before row prose**

The primary set is all IDs below, in this order:

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

The reserve set is exactly:

```text
AUTO-02 DEP-01 JAVA-01 RC-07 RC-08 RC-12 R12 AL-11 TBL-10
```

`STKG-02` does not appear because it is merged into canonical row `API-ARCHIVE-05`.
Do not merge `PM-07` with `PM-09`: `PM-07` repairs fallback eligibility in
`LlmRouterAspect`, while `PM-09` repairs escalation serveability in
`PolicyBasedModelRouter`; their owners, falsifiers, and minimal seams differ.

- [ ] **Step 5: Verify inventory arithmetic before adding rows**

Run:

```powershell
$primary = @('CFG-01','CFG-02','AUTO-01','BUILD-01','APP-01','TEST-01','FQCN-01','GATE-01','RC-02','RC-03','RC-04','RC-05','RC-06','RC-09','RC-10','RC-11','R01','R02','R03','R04','R05','R06','R07','R08','R09','R10','R11','AL-01','AL-02','AL-03','AL-04','AL-05','AL-06','AL-07','AL-08','AL-09','AL-10','AL-12','SEC-01','SEC-02','SEC-03','SEC-04','SEC-05','SEC-06','SEC-07','SEC-08','SEC-09','SEC-10','QTX-01','EVID-01','CITE-01','CITE-02','FUSE-01','HYBRID-01','EMPTY-01','PM-01','PM-02','PM-03','PM-04','PM-05','PM-06','PM-07','PM-08','PM-09','PA-01','PA-02','PA-03','PA-04','PA-05','PA-06','PA-07','PA-08','PA-09','API-ATTACH-01','API-CHAT-02','API-SESSION-03','API-TRACE-04','API-ARCHIVE-05','API-ARCHIVE-06','API-HISTORY-07','API-LIFECYCLE-08','API-CONCURRENCY-09','TBL-01','TBL-02','TBL-03','TBL-04','TBL-05','TBL-06','TBL-07','TBL-08','TBL-09','STKG-01','STKG-03','STKG-04','STKG-05','STKG-06','STKG-07','STKG-08','STKG-09','STKG-10')
$reserve = @('AUTO-02','DEP-01','JAVA-01','RC-07','RC-08','RC-12','R12','AL-11','TBL-10')
[pscustomobject]@{
  primaryCount = $primary.Count
  primaryUnique = @($primary | Sort-Object -Unique).Count
  reserveCount = $reserve.Count
  reserveUnique = @($reserve | Sort-Object -Unique).Count
  overlapCount = @(Compare-Object $primary $reserve -IncludeEqual -ExcludeDifferent).Count
}
```

Expected: `primaryCount=100`, `primaryUnique=100`, `reserveCount=9`, `reserveUnique=9`, `overlapCount=0`.

- [ ] **Step 6: Inspect scoped status without staging**

Run:

```powershell
git status --short -- `
  'docs/superpowers/specs/2026-08-13-gpt-pro-source-audit-context-design.md' `
  'docs/superpowers/plans/2026-08-13-gpt-pro-source-audit-context.md' `
  'docs/audits/2026-08-13-demo1-source-audit-100-ledger.md'
```

Expected: only documentation artifacts from this workflow are shown; no staging or commit occurs.

---

### Task 2: Populate Primary Rows 1-38 — Build, RAG, Resilience, and AutoLearn

**Files:**
- Modify: `docs/audits/2026-08-13-demo1-source-audit-100-ledger.md`
- Read: active owners listed in the seed catalog below.

**Interfaces:**
- Consumes: Task 1 row schema and snapshot.
- Produces: the first 38 complete primary rows, each with a current active-owner anchor and one falsifier.

- [ ] **Step 1: Revalidate and write the eight build/configuration rows**

Use these seed observations; inspect the named current source before writing each row:

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| CFG-01 | `main/resources/application.properties`, `main/resources/application.yml` | Critical keys have overlapping ownership and conflicting defaults across property sources. |
| CFG-02 | `main/resources/application-dev.yml` | The profile file uses legacy `spring.profiles: dev` metadata. |
| AUTO-01 | `app/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, `app/build.gradle.kts` | The `:app` artifact advertises auto-configurations whose classes are owned only by root source. |
| BUILD-01 | `settings.gradle`, `settings.gradle.kts` | Two settings files describe different root names and legacy-module graphs. |
| APP-01 | `build.gradle.kts`, `main/java/com/example/lms/LmsApplication.java`, `main/java/com/abandonware/ai/agent/AgentApplication.java` | A second compiled executable entry point has a materially different component scan from the boot-jar owner. |
| TEST-01 | `app/build.gradle.kts` | `:app` declares empty test source and resource directories. |
| FQCN-01 | `app/build.gradle.kts` | Active root and `java_clean` roots contain 12 duplicate FQCNs while the default duplicate mode is non-failing. |
| GATE-01 | `build.gradle.kts`, task `checkSourceSetHygiene` | The named hygiene gate checks directory existence but not configured ownership, duplicates, or contamination. |

Classify conditional configuration/test coverage rows as `structural_risk` or `evidence_needed`, not runtime defects, unless the current source makes the failure deterministic.

- [ ] **Step 2: Revalidate and write the eight RAG-core rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| RC-02 | `RagOrchestratorController`, `ChatService`, `ChatWorkflow` | `/api/rag` uses `UnifiedRagOrchestrator`; `/api/chat` does not. |
| RC-03 | `PlanHintApplier`, `UnifiedRagOrchestrator`, `PlanDslExecutor` | Plan YAML is a partial hint overlay; `llm`, `fusion`, `when`, and `pipeline` are not execution owners. |
| RC-04 | `ChatApiController`, `ChatWorkflow` | Plan selection and application are duplicated across stream, sync, and workflow paths. |
| RC-05 | `ChatWorkflow.continueChat`, `PromptBuilder.build` | System, policy, context, and user messages are added after the builder result. |
| RC-06 | `DynamicRetrievalHandlerChain`, `FinalSigmoidGate` | Any nonempty accumulator is passed as composite `1.0` with risk `0`; `BLOCK` trims to three rather than suppressing all. |
| RC-09 | `ExtremeZSystemHandler`, `ExtremeZBurstAspect`, `RagChainConfig` | The canonical handler lacks a proven production caller while a separate disabled-by-default aspect performs chat bursting. |
| RC-10 | `RagCompressionAspect`, `ChatWorkflow` | The compression pointcut matches `retrieve(..)` while chat invokes `retrieveAll`. |
| RC-11 | `DynamicRetrievalHandlerChain`, `ChatWorkflow` | DPP runs before a later cross-encoder ordering step that can undo diversity. |

- [ ] **Step 3: Revalidate and write the eleven resilience/observability rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| R01 | `SingleFlightManager.run` | Worker catches `Exception` only; an `Error` can leave the shared future incomplete. |
| R02 | `SingleFlightManager.run`, `SingleFlightAspect` | Callers use unbounded `Future.get`; the timeout property is logged but not enforced. |
| R03 | `Zero100WebTimeboxAspect` | Timeout returns fallback without cancelling the worker, which retains parent trace access. |
| R04 | `ExtremeZSystemHandler.execute` | `InterruptedException` is consumed without restoring the interrupt flag. |
| R05 | `LlmTraceAspect` | Reactor `ON_ERROR` is recorded as finish `stop`. |
| R06 | `ChatStreamEmitter` | `tryEmitNext` results are ignored, so sink failures can be silent. |
| R07 | `SseTelemetryDiagnosticsController`, `LoggingSseEventPublisher` | Diagnostics consume a global stream that permits sessionless and cross-request events. |
| R08 | `DebugEventStore.byFingerprint` | The event ring is bounded but the fingerprint aggregation map is not evicted. |
| R09 | `DebugEventStore.ProbeScope` | A volatile check-then-set allows concurrent success and failure terminal events. |
| R10 | `BrainStateChatWorkflowAspect` | Common-pool capture has no retained future or originating-request trace propagation. |
| R11 | `FailurePatternMemoryService` | Recall reads the entire growing JSONL and only then keeps a tail. |

Keep R01 and R02 separate: one is exceptional completion correctness and the other is deadline enforcement, with distinct minimal repairs and tests.

- [ ] **Step 4: Revalidate and write the eleven AutoLearn/data rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| AL-01 | `application.yml` AutoLearn paths | Configured training/handoff artifacts are absent in the current checkout; runtime learning claims need evidence. |
| AL-02 | `TrainRagIngestService` | Parsable rejected/quarantined rows contribute to accepted ingest counts. |
| AL-03 | `TrainRagIngestService`, `VectorStoreService` | Shadow isolation is applied only when an optional DLQ service exists. |
| AL-04 | `TrainRagIngestService` checkpoint logic | File identity is path-based; same-path equal-or-longer replacement can skip new prefix rows. |
| AL-05 | `TrainRagIngestService` upsert/state-save order | Successful vector writes precede a best-effort checkpoint save, allowing replay after save failure. |
| AL-06 | `UawLearningAgentHandoffWriter` | JSONL and manifest writes lack cross-thread/process locking and atomic replacement. |
| AL-07 | `UawDatasetWriter`, `PromptMasker`, `UawLearningAgentHandoffWriter` | General email, phone, and address PII can remain in persisted training/handoff text. |
| AL-08 | `AttachmentController`, `AttachmentService` | Sessionless upload is allowed, delete requires a session, and physical stored files are not removed. |
| AL-09 | `FederatedEmbeddingStore` | Returned or caller-supplied vector IDs are not consistently the IDs delegated upstream. |
| AL-10 | `Neo4jKgChunkWriter` | Chunk, entity, and relation writes commit in separate transactions. |
| AL-12 | `UawAutolearnProperties`, `MemoryReinforcementService` | Reinforcement is disabled by default and this path stores `PENDING`, not proven active recall. |

- [ ] **Step 5: Validate the first batch**

Run:

```powershell
$path = 'docs\audits\2026-08-13-demo1-source-audit-100-ledger.md'
$expected = @('CFG-01','CFG-02','AUTO-01','BUILD-01','APP-01','TEST-01','FQCN-01','GATE-01','RC-02','RC-03','RC-04','RC-05','RC-06','RC-09','RC-10','RC-11','R01','R02','R03','R04','R05','R06','R07','R08','R09','R10','R11','AL-01','AL-02','AL-03','AL-04','AL-05','AL-06','AL-07','AL-08','AL-09','AL-10','AL-12')
$actual = Select-String -Path $path -Pattern '^### \[(?<id>[^]]+)\]' | ForEach-Object { $_.Matches[0].Groups['id'].Value }
Compare-Object $expected $actual
[pscustomobject]@{ expected=$expected.Count; actual=$actual.Count; unique=@($actual | Sort-Object -Unique).Count }
```

Expected: `Compare-Object` emits nothing; counts are `38`, `38`, `38`. Inspect every row for all 18 labelled fields before continuing.

- [ ] **Step 6: Inspect the ledger diff without staging**

Run:

```powershell
git status --short -- 'docs/audits/2026-08-13-demo1-source-audit-100-ledger.md'
```

Expected: one untracked ledger; no application path is written by this task.

---

### Task 3: Populate Primary Rows 39-73 — Security, Search, Prompt, and Plan/AOP

**Files:**
- Modify: `docs/audits/2026-08-13-demo1-source-audit-100-ledger.md`
- Read: active owners listed in the seed catalog below.

**Interfaces:**
- Consumes: the 38-row ledger from Task 2.
- Produces: 35 additional complete rows; cumulative primary count becomes 73.

- [ ] **Step 1: Revalidate and write the ten security/provider rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| SEC-01 | `HybridWebSearchProvider`, `BraveSearchService`, `NaverSearchService` | Provider-bound queries are control-cleaned but not generally PII-sanitized. |
| SEC-02 | `BraveSearchService`, `TavilyWebSearchRetriever`, `SerpApiProvider` | Configurable credential-bearing base URLs lack a provider host/scheme allowlist. |
| SEC-03 | `HybridWebSearchProvider.applyStrikeFilterIfNeeded` | Official-only filtering returns the original list when every result is filtered out. |
| SEC-04 | `DomainWhitelist` | Community hosts are treated as official and `isBanned` always returns false. |
| SEC-05 | `OwnerKeyResolver` | Optional header override accepts caller-provided `X-Owner-Key` without an authentication-bound ownership check. |
| SEC-06 | `ClientOwnerKeyResolver` | First-request ownership trusts `X-Forwarded-For` without proving a trusted proxy. |
| SEC-07 | `RuleBreakInterceptor`, `WebMvcConfig` | The interceptor bean is not registered in the canonical MVC configuration. |
| SEC-08 | Brave/Tavily/Serp call seams | Calls lack a request-scoped physical wire-attempt ordinal and lineage tuple. |
| SEC-09 | `SerpApiProvider` | The provider key is placed in the request URI query string. |
| SEC-10 | `RuleBreakEvaluator` | Secret comparison uses ordinary `String.equals` rather than the existing constant-time helper. |

- [ ] **Step 2: Revalidate and write the seven query/evidence/fusion rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| QTX-01 | `QueryTransformer.correctWithLLM` | A greedy delimiter regex treats a hyphen as a label separator and can turn `COVID-19` into `19`. |
| EVID-01 | `EvidenceGate.hasSufficientCoverage` | Count-based branches can admit several irrelevant snippets despite weak semantic coverage. |
| CITE-01 | `CitationGate.decide` | `allowlistRatio` is traced but does not affect PASS/WARN/DEGRADE. |
| CITE-02 | `RagEvidenceAttributionService.sanitizePublicUrl` | URL reconstruction removes all query parameters and fragments, including functional locators. |
| FUSE-01 | `ReciprocalRankFuser.keyOf` | RRF identity is a 32-bit normalized-text hash rather than stable document metadata. |
| HYBRID-01 | `HybridRetriever` deadline paths | Timed-out branch and fusion futures use `cancel(false)`. |
| EMPTY-01 | `HybridRetriever.emptyEvidence` | Failure/timeout empties hardcode input count zero and fallback stage none. |

- [ ] **Step 3: Revalidate and write the nine prompt/model rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| PM-01 | `StandardPromptBuilder.build`, `ChatWorkflow`, `AnswerExpanderService` | User content crosses into a system-role string while trusted post-orchestration instructions can be flattened into user role. |
| PM-02 | `PromptContext`, `StandardPromptBuilder` | Recent history and last assistant answer are loaded but not rendered by the standard builder. |
| PM-03 | `StandardPromptBuilder`, `DynamicContextCompressor` | Final evidence rendering has no aggregate model-window input cap and fail-soft compression can return the original. |
| PM-04 | `ChatRequestDto`, `ChatWorkflow`, `LlmConfig` | Output caps diverge across profile target, DTO, static bean, and dynamic route. |
| PM-05 | `ChatWorkflow.callWithRetryReportingSuccess`, `OllamaNativeChatModel` | A blank `AiMessage` is reported as success before blank-response retry/fallback. |
| PM-06 | `ChatWorkflow`, `LlmRouterAspect`, `FallbackAwareChatModel` | `strictSingleAttempt` does not prevent a nested gateway fallback from making a second model call. |
| PM-07 | `LlmRouterAspect`, `HybridLlmGatewayProbeService` | ENFORCE evaluates the primary route but not the selected fallback's eligibility. |
| PM-08 | `LlmGatewayProperties.Probe`, `HybridLlmGatewayProbeService` | Probe timeout and TTL settings lack a source-backed transport/cache consumer. |
| PM-09 | `PolicyBasedModelRouter.escalate` | Escalation returns the high model without the serveability checks used by normal routing. |

- [ ] **Step 4: Revalidate and write the nine Plan/AOP rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| PA-01 | `ExecutionPlanApplier.applyOverrides` | Derived mode booleans, including false, overwrite explicit plan/request overrides without a precedence adjudicator. |
| PA-02 | `ChatWorkflow`, `ExecutionPlanApplier.deriveSignals` | Execution plans sample retrieval-outcome signals before current-request retrieval produces them. |
| PA-03 | `ExecutionPlanApplier`, `OverdriveGuard.traceDecision` | Producer and consumer use different Overdrive authority/contradiction trace keys. |
| PA-04 | `GuardDebugTraceAspect`, `OrchestrationSignals.compute` | A debug snapshot calls an impure computation that mutates routing context. |
| PA-05 | `WebFailSoftSearchAspect`, `HybridWebSearchEmptyFallbackAspect` | Outer retries can re-enter the inner empty-fallback advice and multiply work. |
| PA-06 | `UnifiedRagOrchestrator` | `aggressive=true` skips a plan's `whitelistOnly` final stage. |
| PA-07 | `WorkflowOrchestrator.selectPlan` | Cost/fast intent is tested before finance, so a fast finance query can select the low-risk cost plan. |
| PA-08 | `SelfAskWebSearchRetriever.getWithHardTimeout` | Hard-timeout cancellation uses `cancel(false)`, leaving running search work. |
| PA-09 | `Zero100SessionAspect` | Raw descriptive substrings can activate the enabled-by-default high-cost Zero100 mode. |

- [ ] **Step 5: Validate the cumulative 73-row ledger**

Run:

```powershell
$path = 'docs\audits\2026-08-13-demo1-source-audit-100-ledger.md'
$actual = Select-String -Path $path -Pattern '^### \[(?<id>[^]]+)\]' | ForEach-Object { $_.Matches[0].Groups['id'].Value }
[pscustomobject]@{ count=$actual.Count; unique=@($actual | Sort-Object -Unique).Count; duplicates=@($actual | Group-Object | Where-Object Count -gt 1 | Select-Object -ExpandProperty Name) }
```

Expected: `count=73`, `unique=73`, and an empty duplicate list.

- [ ] **Step 6: Recheck the four high-risk source anchors manually**

Run:

```powershell
rg -n -C 3 'return in;|isOfficialHost|allowlistRatio|String finish = signal == SignalType.CANCEL|return highModel' `
  main/java/com/example/lms/search/provider/HybridWebSearchProvider.java `
  main/java/com/example/lms/service/rag/auth/DomainWhitelist.java `
  main/java/com/example/lms/service/guard/CitationGate.java `
  main/java/com/example/lms/trace/LlmTraceAspect.java `
  main/java/com/example/lms/service/routing/PolicyBasedModelRouter.java
```

Expected: current anchors still support their corresponding observations. If not, downgrade only the changed row and record `snapshot_changed`.

---

### Task 4: Populate Primary Rows 74-100 and the Nine-Row Reserve Appendix

**Files:**
- Modify: `docs/audits/2026-08-13-demo1-source-audit-100-ledger.md`

**Interfaces:**
- Consumes: the 73-row ledger from Task 3.
- Produces: exactly 100 complete primary rows, exactly nine reserve rows, category totals, and generation-verification metadata.

- [ ] **Step 1: Revalidate and write the nine API/session rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| API-ATTACH-01 | `AttachmentController`, `AttachmentService`, `ChatSessionAccessGuard` | Attachment operations trust caller-supplied session IDs without checking session ownership. |
| API-CHAT-02 | `ChatSessionAccessGuard`, `ChatApiController`, `ChatHistoryServiceImpl.startNewSession` | A nonexistent session can silently fork and persist the first user turn twice. |
| API-SESSION-03 | `ChatApiController.chatStream`, `ChatHistoryServiceImpl.updateSessionMeta` | Stream code mutates detached session metadata without the repository update path. |
| API-TRACE-04 | `ChatTraceSnapshotPointerPersister`, `TraceSnapshotStore`, `ChatTraceMetaMessageRestorer` | A durable chat pointer targets a bounded, restart-volatile memory ring. |
| API-ARCHIVE-05 | `ConversationNoiseClassifier`, `ConversationTopicTimelineBuilder` | Lexical `summary:` or URL markers can promote untrusted archive text to verified KB metadata; this canonical row absorbs `STKG-02`. |
| API-ARCHIVE-06 | `ConversationArchiveIngestService`, `VectorStoreService.enqueue` | The archive API increments promoted counts after a void enqueue that can drop or quarantine content. |
| API-HISTORY-07 | `ChatHistoryServiceImpl` recent history and authorization | Bounded-looking reads materialize the complete transcript and limit only in memory. |
| API-LIFECYCLE-08 | `ChatApiController.deleteSession`, stream persistence | Session deletion does not coordinate with an active run that can persist later. |
| API-CONCURRENCY-09 | sync chat endpoints versus `ChatRunRegistry` | Same-session sync calls bypass the serialization/join registry used by streaming. |

- [ ] **Step 2: Revalidate and write the nine verification-blind-spot rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| TBL-01 | `sourceHealthValidationLoop`, `source_health_validation_loop.py` | Declared Gradle gates are labels; `--skip-gradle` does not execute them, yet a fresh artifact can be emitted. |
| TBL-02 | `source_health_scorecard.py` JUnit proof | Broad proof selects the best potentially stale subset, omits UI/security tasks, and ignores skipped/freshness provenance. |
| TBL-03 | Gradle `check` task graph | Conventional `check` omits health reports and `bootJar`. |
| TBL-04 | `test_tree_contamination_report.py`, source-health score | Test-tree reliability is effectively missing-import alignment and the reporter exits zero. |
| TBL-05 | `LmsApplicationContextLoadsTest`, servlet slices | The canonical full context proof is non-web, so complete servlet registration and lifecycle remain unproven. |
| TBL-06 | `AgentDbContextContractTest`, auto-config imports | Metadata discovery is a one-entry spot check plus direct runner, not an intended-import inventory. |
| TBL-07 | `PlanLoaderTest`, plan resources | No test maps every selectable plan key to an active runtime consumer. |
| TBL-08 | runtime config tests | Existing checks do not compute effective cross-file/profile property precedence generically. |
| TBL-09 | Matryoshka and DPP tests | Mechanics use fixed/synthetic data without a same-corpus recall, nDCG, latency, and index-size acceptance benchmark. |

- [ ] **Step 3: Revalidate and write the nine storage/KG rows**

| ID | Active-owner seed | Observation seed |
| --- | --- | --- |
| STKG-01 | `ChatHistoryServiceImpl.deleteSession`, `GraphRagChunkingService` | Session deletion removes JPA state but has no proven vector, BrainState, or Neo4j purge. |
| STKG-03 | `DomainKnowledge.entityName` | Composite `(domain, entityName)` uniqueness is contradicted by global `entityName` uniqueness. |
| STKG-04 | `ChatHistoryServiceImpl.updateRollingSummary` | Backlog reads at most 24 rows but advances the watermark to the caller's later ID. |
| STKG-05 | rolling-summary persistence | Each summary update appends another system summary while reads use only the newest. |
| STKG-06 | `BrainStateService.recordChunks` | Reingesting the same chunk replaces the chunk but increments entity/relation counts again. |
| STKG-07 | `BrainStateService` maps, `BrainStateProperties` | Process-lifetime maps have no removal path and the configured snapshot TTL is unused. |
| STKG-08 | production JPA configuration and DDL resources | Production validates schema but the checkout has no complete versioned migration owner; external schema management is unobserved. |
| STKG-09 | `CfvmSnapshotService.periodicSnapshot` | Minute snapshots append indefinitely while consumers read only the latest. |
| STKG-10 | `CfvmBanditStore.flushBestEffort` | Learned state overwrites the canonical file non-atomically and lacks shutdown flush/recovery. |

- [ ] **Step 4: Write the nine reserve rows with the full schema**

Reserve rows use the same fields but are explicitly excluded from primary defect/risk totals and GPT Pro's default Top 10:

| ID | Reason held in reserve |
| --- | --- |
| AUTO-02 | Modern and legacy auto-config metadata duplication may be deduplicated by framework behavior; needs isolated loading proof. |
| DEP-01 | Optional legacy modules are not enabled in the observed project graph. |
| JAVA-01 | Current Java 17 reduces the immediate risk of the root toolchain declaration gap. |
| RC-07 | HYPERNOVA gate order needs integration observation. |
| RC-08 | HYPERNOVA profile activation is not observed. |
| RC-12 | God-class size is real but less decision-specific than the behavioral seams. |
| R12 | Process-local CFVM telemetry does not itself prove failed recovery. |
| AL-11 | OCR is optional and disabled by default; runtime engine and large-input behavior are unobserved. |
| TBL-10 | Source-text-test dominance is broad portfolio context, not one narrow repair seam. |

- [ ] **Step 5: Calculate category and classification totals from headings/fields**

Run:

```powershell
$path = 'docs\audits\2026-08-13-demo1-source-audit-100-ledger.md'
$text = Get-Content -Raw -Encoding UTF8 $path
$primaryPart = ($text -split '## Reserve Audit Rows',2)[0]
$reservePart = ($text -split '## Reserve Audit Rows',2)[1]
$primaryIds = [regex]::Matches($primaryPart,'(?m)^### \[(?<id>[^]]+)\]') | ForEach-Object { $_.Groups['id'].Value }
$reserveIds = [regex]::Matches($reservePart,'(?m)^### \[(?<id>[^]]+)\]') | ForEach-Object { $_.Groups['id'].Value }
$classes = [regex]::Matches($primaryPart,'(?m)^- classification: `(confirmed_defect|structural_risk|evidence_needed)`') | ForEach-Object { $_.Groups[1].Value }
[pscustomobject]@{
  primary=$primaryIds.Count
  primaryUnique=@($primaryIds | Sort-Object -Unique).Count
  reserve=$reserveIds.Count
  reserveUnique=@($reserveIds | Sort-Object -Unique).Count
  classFields=$classes.Count
  confirmed=@($classes | Where-Object { $_ -eq 'confirmed_defect' }).Count
  risk=@($classes | Where-Object { $_ -eq 'structural_risk' }).Count
  evidenceNeeded=@($classes | Where-Object { $_ -eq 'evidence_needed' }).Count
}
```

Expected: primary and unique are 100; reserve and unique are nine; `classFields=100`; the three class counts sum to 100. Copy those exact computed totals into `Category Totals`.

- [ ] **Step 6: Validate all mandatory fields and active-owner paths**

Run:

```powershell
$path = 'docs\audits\2026-08-13-demo1-source-audit-100-ledger.md'
$required = @('classification','priorityInput','rootCauseClusterId','uniquenessKey','activeOwner','sourceAnchor','observation','inference','reachability','counterEvidence','stapsStatus','confidence','evidenceSurface','falsifier','verification','verificationClass','verificationCost','minimalRepairSeam','dependencies')
$text = Get-Content -Raw -Encoding UTF8 $path
$rows = [regex]::Split($text,'(?m)(?=^### \[[^]]+\])') | Where-Object { $_ -match '^### \[' }
$bad = foreach($row in $rows){
  $id = [regex]::Match($row,'^### \[(?<id>[^]]+)\]').Groups['id'].Value
  $missing = @($required | Where-Object { $row -notmatch "(?m)^- $([regex]::Escape($_)): \S" })
  if($missing.Count){ [pscustomobject]@{id=$id;missing=($missing -join ',')} }
}
$bad
```

Expected: no output. Manually verify `activeOwner` paths exist; intentionally absent data artifacts belong in the verification/evidence gap, not `activeOwner`.

- [ ] **Step 7: Inspect scoped diff without staging**

Run:

```powershell
git status --short -- 'docs/audits/2026-08-13-demo1-source-audit-100-ledger.md'
```

Expected: one untracked ledger only for this task.

---

### Task 5: Create the Standalone GPT Pro Context and Adjudication Prompt

**Files:**
- Read: `docs/audits/2026-08-13-demo1-source-audit-100-ledger.md`
- Create: `agent-prompts/gpt_pro_demo1_source_audit_100.md`

**Interfaces:**
- Consumes: exact 100-row primary ledger, nine-row reserve, snapshot, category totals, and same-surface rules.
- Produces: one standalone Markdown artifact that can be pasted without the repository or conversation history.

- [ ] **Step 1: Create the fixed prompt preamble and authority contract**

Use `apply_patch` and start the file with:

```markdown
# GPT Pro Task: Adjudicate the demo-1 100-Row Source Audit

You are reviewing a supplied, source-backed audit snapshot. Do not assume repository access, do not invent current code, and do not claim that any proposed verification ran. Treat STAPS as a design claim map and the audit observations as evidence candidates that still require your adjudication.
```

Then include exact sections:

```markdown
## Decision Requested
## Repository and Evidence Snapshot
## Immutable Technical and Privacy Constraints
## Classification Rules
## Complete Primary ID Coverage List
## 100-Row Primary Ledger
## Nine-Row Reserve Appendix
## Required Adjudication Procedure
## Required Response Schema
## Stop Conditions
```

- [ ] **Step 2: Copy the current snapshot and constraints, not stale design prose**

Copy snapshot values from the completed ledger. Include active sourceSets, canonical main class, Java 17, STAPS hash, branch, HEAD, and captured time. Include same-surface rules and protected invariants from the design. Do not include raw dirty-file lists or private data.

- [ ] **Step 3: Embed all 100 primary rows and nine reserve rows**

Copy the compact row content from the ledger. Preserve every ID, classification, cluster, owner, observation, inference, counter-evidence, STAPS status, confidence, falsifier, verification class, and minimal repair seam. Do not silently shorten the back half of the ledger.

- [ ] **Step 4: Require full ID accounting before prioritization**

The prompt must require one result for every primary ID using exactly:

```text
confirm | merge | downgrade | reject | hold
```

It must require merged and rejected IDs to stay visible with reasons. It must instruct GPT Pro to return `context_coverage_incomplete` and stop ranking if any supplied primary ID is missing from its accounting.

- [ ] **Step 5: Define the required GPT Pro response schema**

Require these sections in order:

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

Each Top 10 and Top 25 entry must include retained IDs, active owner, reason,
severity, reachability, likelihood, blast radius, confidence, dependency,
minimal repair seam, RED assertion, GREEN/regression ladder, verification
class, and stop condition. State that Top 10 must be a strict subset of Top 25
and cap one root-cause cluster at three Top 10 entries unless GPT Pro explains a
cross-system emergency.

- [ ] **Step 6: Add explicit non-authority and no-invention stops**

The prompt must forbid implementation diffs, source edits, new frameworks,
duplicate owners, protected-property renames, dependency/version changes,
external calls, credential use, invented benchmark gains, invented runtime
activation, and claims that a proposed test already passed.

- [ ] **Step 7: Verify prompt ID coverage against the ledger**

Run:

```powershell
$ledger = 'docs\audits\2026-08-13-demo1-source-audit-100-ledger.md'
$prompt = 'agent-prompts\gpt_pro_demo1_source_audit_100.md'
$ledgerText = Get-Content -Raw -Encoding UTF8 $ledger
$promptText = Get-Content -Raw -Encoding UTF8 $prompt
$primaryPart = ($ledgerText -split '## Reserve Audit Rows',2)[0]
$ledgerIds = [regex]::Matches($primaryPart,'(?m)^### \[(?<id>[^]]+)\]') | ForEach-Object { $_.Groups['id'].Value }
$missing = @($ledgerIds | Where-Object { ([regex]::Matches($promptText,"(?<![A-Z0-9-])$([regex]::Escape($_))(?![A-Z0-9-])")).Count -lt 2 })
[pscustomobject]@{ledgerIds=$ledgerIds.Count;missingOrSingleMention=$missing.Count;ids=($missing -join ',')}
```

Expected: `ledgerIds=100`, `missingOrSingleMention=0`. Each ID appears in the coverage list and embedded ledger.

- [ ] **Step 8: Inspect the standalone prompt without registering it**

Run:

```powershell
git status --short -- 'agent-prompts/gpt_pro_demo1_source_audit_100.md' 'agent-prompts/prompts.manifest.yaml'
```

Expected: the standalone prompt is untracked and the manifest has no change caused by this task. Registration count zero is intentional.

---

### Task 6: Cross-Artifact Validation and Final Handoff

**Files:**
- Verify: `docs/superpowers/specs/2026-08-13-gpt-pro-source-audit-context-design.md`
- Verify: `docs/superpowers/plans/2026-08-13-gpt-pro-source-audit-context.md`
- Verify: `docs/audits/2026-08-13-demo1-source-audit-100-ledger.md`
- Verify: `agent-prompts/gpt_pro_demo1_source_audit_100.md`

**Interfaces:**
- Consumes: all completed documentation artifacts.
- Produces: requirement-by-requirement evidence, hashes, scoped status, and a user-ready link to the standalone prompt.

- [ ] **Step 1: Recheck snapshot drift**

Run the Task 1 snapshot command again. Compare branch, HEAD, STAPS hash, and each row's anchor. If only dirty count changed, update the final snapshot count and captured time. If branch, HEAD, STAPS, or a source anchor changed, refresh only the affected mappings/rows before continuing.

- [ ] **Step 2: Run the exact count and schema checks**

Repeat Task 4 Steps 5 and 6. Expected: 100 unique primary rows, nine unique reserve rows, zero overlap, 100 classification fields, and zero missing mandatory fields.

- [ ] **Step 3: Scan for placeholders and unresolved template syntax**

Scan the deliverable content files, not this implementation plan: the plan
necessarily contains the literal placeholder-detection pattern shown below and
would otherwise self-match it.

Run:

```powershell
$files = @(
  'docs/superpowers/specs/2026-08-13-gpt-pro-source-audit-context-design.md',
  'docs/audits/2026-08-13-demo1-source-audit-100-ledger.md',
  'agent-prompts/gpt_pro_demo1_source_audit_100.md'
)
$patterns = @('(?im)^\s*(TBD|TODO|FIXME|XXX)\b','\$\{[^}]+\}','(?i)<insert[^>]*>|<placeholder[^>]*>')
$hits = foreach($file in $files){
  $text = Get-Content -Raw -Encoding UTF8 $file
  foreach($pattern in $patterns){
    if($text -match $pattern){ [pscustomobject]@{file=$file;pattern=$pattern} }
  }
}
$hits
```

Expected: no output.

- [ ] **Step 4: Run a changed-file count-only secret scan**

Run:

```powershell
$files = @(
  'docs/superpowers/specs/2026-08-13-gpt-pro-source-audit-context-design.md',
  'docs/superpowers/plans/2026-08-13-gpt-pro-source-audit-context.md',
  'docs/audits/2026-08-13-demo1-source-audit-100-ledger.md',
  'agent-prompts/gpt_pro_demo1_source_audit_100.md'
)
$secretPattern = '(?i)(sk-[a-z0-9_-]{16,}|bearer\s+[a-z0-9._-]{16,}|authorization\s*:|client_secret\s*[:=]\s*[^\s]+|api[_-]?key\s*[:=]\s*[a-z0-9_-]{16,})'
$count = 0
foreach($file in $files){
  $count += ([regex]::Matches((Get-Content -Raw -Encoding UTF8 $file),$secretPattern)).Count
}
[pscustomobject]@{secretPatternHits=$count}
```

Expected: `secretPatternHits=0`. Do not print matching values if the count is nonzero; report the file and category only and repair the artifact.

- [ ] **Step 5: Verify required prompt anchors**

Run:

```powershell
$prompt = Get-Content -Raw -Encoding UTF8 'agent-prompts\gpt_pro_demo1_source_audit_100.md'
$anchors = @('SOURCE BOUNDARY RESTATEMENT','ID COVERAGE AUDIT','TOP 10 IMMEDIATE','TOP 25 ORDERED BACKLOG','HOLD AND EVIDENCE_NEEDED','MINIMAL REPAIR AND VERIFICATION SEQUENCE','context_coverage_incomplete','confirm | merge | downgrade | reject | hold')
$missing = @($anchors | Where-Object { -not $prompt.Contains($_) })
[pscustomobject]@{anchorCount=$anchors.Count;missingCount=$missing.Count;missing=($missing -join ',')}
```

Expected: `anchorCount=8`, `missingCount=0`.

- [ ] **Step 6: Compute final artifact hashes and scoped status**

Run:

```powershell
$files = @(
  'docs/superpowers/specs/2026-08-13-gpt-pro-source-audit-context-design.md',
  'docs/superpowers/plans/2026-08-13-gpt-pro-source-audit-context.md',
  'docs/audits/2026-08-13-demo1-source-audit-100-ledger.md',
  'agent-prompts/gpt_pro_demo1_source_audit_100.md'
)
$files | ForEach-Object {
  [pscustomobject]@{path=$_;bytes=(Get-Item $_).Length;sha256=(Get-FileHash -Algorithm SHA256 $_).Hash}
}
git status --short -- $files
```

Expected: all four files have nonzero size and SHA-256 values; scoped status contains only these documentation/prompt artifacts from this workflow. Do not stage or commit.

- [ ] **Step 7: Perform the completion audit against the approved design**

Record pass/fail for every item in design Section 14. Completion requires all 12 items to pass. A missing runtime/provider/database/benchmark surface remains an accurately labelled row and does not block the Markdown handoff; a missing primary row, invalid count, secret exposure, snapshot drift, or incomplete GPT Pro coverage does block completion.

- [ ] **Step 8: Hand off the standalone artifact**

Report the prompt and ledger paths, their hashes, primary/reserve/classification counts, zero secret-pattern count, no application-source mutation, and no Git staging/commit. Tell the user to attach the ledger only if their GPT Pro interface truncates the combined prompt; otherwise the standalone prompt already embeds it.
