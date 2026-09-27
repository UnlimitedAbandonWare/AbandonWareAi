# Demo-1 Post-Audit Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans`. Repository policy keeps all writes, integration, verification, and final judgment in the parent Codex task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Close the six user-selected gaps with mutation-sensitive RED tests, minimal active-source patches, durable hash-only lifecycle evidence, and fresh Browser/Computer proof.

**Architecture:** Keep each behavioral repair at its current owner, except for four W8 responsibilities that move behind narrow package-local policies/coordinators. One JSONL lifecycle store persists only hashes, allowlisted state/reason, and UTC epoch time; it never persists feedback text, webhook bodies, attachment names/URLs/paths, credentials, prompts, or responses. Cancellation is terminal before fallback or any post-generation side effect.

**Tech Stack:** Java 17, Spring Boot, Jackson, Java NIO, Gradle/JUnit 5, Python `unittest`, Node/Playwright, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md` plus the user's 2026-08-27 six-item implementation instruction, which supersedes the older process-local n8n limitation.

## Global Constraints

- Active owners remain root `main/java`, `main/resources`, and `src/test/java`; preserve every unrelated dirty hunk.
- Add no production dependency and make no commit, push, deploy, database, credential, or destructive external mutation.
- Before application-source writes, require one frozen three-way preflight with stable `APPLY`, the existing Desktop source lease, and immediate target preimage hashes.
- Every behavior change follows RED -> minimal implementation -> GREEN -> affected-boundary verification.
- Durable lifecycle rows contain only `schemaVersion`, `lifecycle`, `subjectHash`, `payloadHash`, `state`, `atEpochMs`, and `reason`.
- A durable n8n replay after process restart must not fabricate the lost process-local job ID; it returns a fixed durable-replay state and receipt hash.
- Browser geometry acceptance requires the current served CSS hash and real rectangles at `1280x720` and `390x844`.

---

### Task 1: Make composer geometry a real browser contract

**Files:**
- Create: `scripts/chat_ui_geometry_contract_tests.js`
- Modify: `main/resources/static/css/chat-style.css`
- Modify: `src/test/java/com/example/lms/web/ChatFrontendSecurityTest.java`

**Interfaces:**
- Consumes: `/chat-ui`, `.chat-area-wrapper`, `.chat-transcript-region`, `#chatWindow`, `.composer`, `#messageInput`.
- Produces: one viewport-height owner, one transcript scroll owner, and an interactive composer fully inside the wrapper and viewport.

- [ ] Add a Playwright geometry test that asserts `composer.bottom <= wrapper.bottom + 1`, `textarea.bottom <= innerHeight + 1`, `document.scrollWidth <= innerWidth + 1`, and `elementFromPoint(textarea-center)` resolves to the input or its descendant.
- [ ] Run it against the current localhost page and retain the expected RED measurements.
- [ ] Replace wrapper `max-height`/clipped overflow plus transcript minimum-height conflict with an explicit wrapper height and `min-height:0` transcript remainder; keep vertical scrolling only on `#chatWindow`.
- [ ] Run static contracts and Playwright at desktop and narrow viewports, then capture Browser and Computer visual proof.

### Task 2: Make cancellation terminal and extract ChatWorkflow terminal persistence

**Files:**
- Modify: `src/test/java/com/example/lms/service/rag/langgraph/RagGraphExecutorTest.java`
- Modify: `main/java/com/example/lms/service/rag/langgraph/RagGraphExecutor.java`
- Create: `main/java/com/example/lms/service/chat/FinalizedMemoryPersistence.java`
- Create: `src/test/java/com/example/lms/service/chat/FinalizedMemoryPersistenceTest.java`
- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`
- Modify: `src/test/java/com/example/lms/service/ChatWorkflowCancellationContractTest.java`

**Interfaces:**
- `RagGraphExecutor` rethrows `CancellationException`; `InterruptedException` restores the interrupt flag and becomes terminal cancellation.
- `FinalizedMemoryPersistence.commit(ChatRunExecutionContext,List<SideEffect>,Supplier<? extends CancellationException>)` owns one commit admission and four cancellation-fenced effects.

- [ ] Add RED cases for graph cancellation and sneaky `InterruptedException`, asserting no second `queryWithTrace` call, no repair, and a terminal exception.
- [ ] Add RED cases proving cancellation prevents learning, memory, understanding, reinforcement, and persistence callbacks.
- [ ] Implement terminal propagation at graph invoke/retrieve/repair boundaries and move the already-green terminal memory block out of `ChatWorkflow` without changing answer construction.
- [ ] Run graph, workflow cancellation, run-registry, and controller cancellation tests.

### Task 3: Expand degraded-storage fault contracts

**Files:**
- Modify: `src/test/java/ai/abandonware/nova/orch/storage/FileDegradedStorageDurabilityTest.java`
- Modify: `src/test/java/ai/abandonware/nova/orch/storage/FileDegradedStorageConcurrencyTest.java`
- Modify: `main/java/ai/abandonware/nova/orch/storage/FileDegradedStorage.java`

**Interfaces:**
- Two instances sharing one JSONL path serialize the entire read/append/rewrite claim transaction.
- Directory writes report final-move failure; NACK deletes inflight only after destination commit.
- JSONL release returns an envelope only when its inflight rewrite commits.

- [ ] Add deterministic RED tests for a barrier-synchronized two-instance claim, directory final-move collision, directory NACK destination collision, and an inflight `.tmp` directory that forces release rewrite failure.
- [ ] Implement one shared per-canonical-JSONL lock, boolean directory persistence, destination-before-source NACK, and rewrite-result-aware release.
- [ ] Run all degraded-storage tests and inspect pending/inflight counts after every injected fault.

### Task 4: Add hash-only durable lifecycle receipts

**Files:**
- Create: `main/java/com/example/lms/lifecycle/DurableLifecycleReceiptStore.java`
- Create: `main/java/com/example/lms/lifecycle/JsonlLifecycleReceiptStore.java`
- Create: `src/test/java/com/example/lms/lifecycle/JsonlLifecycleReceiptStoreTest.java`
- Modify: feedback guard/controller tests and owners; n8n registry/controller tests and owners; attachment service/storage tests and owners.

**Interfaces:**
- `find(lifecycle,subjectHash)`, `record(Receipt)`, and `latest(lifecycle)` operate on validated 64-hex hashes and allowlisted enum state/reason.
- Feedback persists `PENDING -> COMMITTED|FAILED`; committed replay cannot mutate strategy after restart.
- n8n persists `INTENT -> ACCEPTED|FAILED`; a restart replay returns `accepted_unknown` plus receipt hash without a fabricated job ID.
- Attachment persists `CREATED`, `DELETE_REQUESTED`, and `DELETED|DELETE_FAILED` with attachment/path hashes only.

- [ ] Write restart RED tests first and assert the JSONL contains no private sentinels or raw path/body/comment text.
- [ ] Implement lock/read/update/force/atomic-rewrite using only JDK NIO and Jackson already present.
- [ ] Adapt each owner with constructor-injected temp stores for tests and a Spring default path `data/lifecycle-receipts.jsonl`.
- [ ] Run lifecycle, feedback, n8n, attachment retention/physical-delete/ownership, and redaction tests.

### Task 5: Require per-input scorecard freshness

**Files:**
- Modify: `scripts/test_source_health_scorecard.py`
- Modify: `scripts/source_health_scorecard.py`

**Interfaces:**
- `inputFreshness` reports canonical path, presence, parsed `generatedAt`, age, status, and reason for quantitative, harmony, and test-tree inputs.
- Missing, undated, malformed, or stale required input yields `metric-input-invalid` and zero for every dependent metric; the report's new `generatedAt` does not change this state.

- [ ] Add stale/missing/mixed-freshness RED fixtures and assert a fresh output timestamp cannot make them current.
- [ ] Reuse the existing UTC parsing/freshness vocabulary and make dependency-to-score invalidation explicit.
- [ ] Run the focused cases and full Python scorecard suite with temp-only output.

### Task 6: Complete the four W8 extractions

**Files:**
- Create: `main/java/com/example/lms/api/ChatCancellationCommandHandler.java` and focused test; modify only `/cancel` delegation in `ChatApiController`.
- Create: `main/java/com/example/lms/llm/RequestTimelineRetentionPolicy.java` and focused test; modify only capacity selection in `ModelRuntimeHealthTracker`.
- Create: `main/java/com/example/lms/agent/context/ExternalEvidenceFreshnessPolicy.java` and focused test; modify only freshness classification in `AgentPipelineHealthController`.
- Reuse Task 2's `FinalizedMemoryPersistence` extraction for `ChatWorkflow`.

- [ ] Add direct policy/coordinator tests before moving code.
- [ ] Preserve controller outcomes, timeline capacity/order, heartbeat reason codes/shape, and exactly one terminal commit admission.
- [ ] Record pre/post responsibility and branch/call counts; reject LOC-only delegation.
- [ ] Run the four focused owner suites and the originating Task 2/5 tests.

### Task 7: Broad verification

- [ ] Run focused suites, `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, `compileJava`, `:app:classes`, fresh full test refresh, and `bootJar` with isolated Desktop caches.
- [ ] Run current-JAR localhost geometry, Browser, and Computer proof; keep provider/model success `not_observed` unless a wire attempt is actually seen.
- [ ] Run `git diff --check`, exact-target postimage hashes, count-only secret scan, PatchDrop janitor inventory, and final diff review.
- [ ] Leave all changes unstaged and uncommitted.
