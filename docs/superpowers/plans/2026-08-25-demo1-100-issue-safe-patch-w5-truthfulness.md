# Demo-1 W5 Session, Memory, Trace, and Health Truthfulness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close IDs 13, 17, 30, 48, 49, 51, 55, 56, 78, 81, 84, and 85 by preventing EPHEMERAL reads, making owner/session lifecycle stable, persisting resolvable safe traces, and scoping health success to the route and semantic outcome that earned it.

**Architecture:** `ChatWorkflow` remains the canonical workflow and `PromptBuilder.build(PromptContext)` remains the final prompt boundary. `ChatRunRegistry` remains the exact-run lifecycle owner. Owner identity is bootstrapped once per request and forwarding headers are trusted only through an explicit boundary. Durable chat history stores a bounded allowlisted trace fallback rather than a bare reference to an evictable ring. Model transport observations and semantic promotion are separate states.

**Tech Stack:** Java 17, Spring MVC/Reactor, JUnit 5, AssertJ, Mockito, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md`

## Global Constraints

- EPHEMERAL performs no historical-memory read or write through primary, fallback, compressor, or ask-later paths.
- Cancellation checkpoints must occur before verifier, postprocessor, learning, memory, transcript, trace-pointer, and session-meta writes.
- Never persist raw prompts, raw answers, raw trace HTML, provider bodies, endpoint URLs, forwarding headers, or fingerprints. Persist only allowlisted bounded projections and hashes.
- Default behavior does not trust `X-Forwarded-For`; a forwarding header is considered only when the immediate peer satisfies an explicit trusted-proxy policy.
- A model attempt can be transport-successful without becoming route-health semantic success.
- No global map overflow may call `clear()` and erase unrelated live request state.
- Each changed production cohort requires stable three-way source-edit preflight and immediate preimage verification.

## Coverage

| IDs | Task | Active owner |
|---|---|---|
| 13 | 1 | `ChatWorkflow`, `MemoryMode`, prompt-context memory inputs |
| 17, 51 | 2 | `OwnerKeyBootstrapFilter`, `ClientOwnerKeyResolver` |
| 30, 48 | 3 | `ChatApiController`, `ChatRunRegistry`, `ChatWorkflow` |
| 49 | 4 | `FeedbackController`, `MemoryReinforcementService` |
| 55, 84 | 5 | `DebugEventStore` |
| 56, 85 | 6 | trace pointer/restorer and `TraceSnapshotStore` |
| 78, 81 | 7 | `ModelRuntimeHealthTracker` and semantic call sites |

---

### Task 0: Freeze W5 identity, cancellation, persistence, and health boundaries

**Files:** All task targets and nearest focused tests.

**Interfaces:**

- Consumes: current owner cookie/request attributes, exact run token, memory mode, trace storage stats, and health keys.
- Produces: seven independently gated mutation cohorts with call-path evidence.

- [ ] **Step 1:** Refresh branch/HEAD/source sets/index lock/worktrees/PatchDrop/leases and target hashes.
- [ ] **Step 2:** Trace one request each for EPHEMERAL, first-cookie owner bootstrap, exact cancellation, session deletion, feedback, trace restore, and local-model health promotion. Record only method names, counts, and hashes.
- [ ] **Step 3:** Run the current focused baselines named in Tasks 1-7 before adding tests.
- [ ] **Step 4:** Freeze a separate EvidenceSnapshot and stable three-way `APPLY` for each cohort that needs production edits. A green characterization closes only its exact ID.

---

### Task 1: Prove EPHEMERAL has zero historical-memory reads on every fallback path

**Files:**

- Modify if RED: `main/java/com/example/lms/service/ChatWorkflow.java`.
- Modify if a semantic helper is missing: `main/java/com/example/lms/domain/enums/MemoryMode.java`.
- Modify: `src/test/java/com/example/lms/service/ChatWorkflowS7PromptBoundaryContractTest.java`.
- Modify: `src/test/java/ai/abandonware/nova/orch/compress/DynamicContextCompressorTest.java` only if the active trace proves the compressor can read history itself.

**Interfaces:**

- Consumes: request `MemoryMode`, session ID, ask-later/history intent, fallback state, and `PromptContext`.
- Produces: no history repository/vector/compressor read and no remembered value in EPHEMERAL output.

- [ ] **Step 1: Add a sentinel-history RED contract**

  ```java
  @Test
  void ephemeralCannotReadRememberedValueEvenThroughAskLaterFallback() {
      historyContains("SECRET_SENTINEL_13");
      ChatRequestDto request = ephemeralRequest("What did I tell you earlier?");

      String answer = workflow.continueChat(request, "owner");

      assertThat(answer).doesNotContain("SECRET_SENTINEL_13");
      verify(memoryService, never()).loadConversationMemory(anyLong(), any());
      verify(vectorMemory, never()).search(any());
      assertThat(capturedPrompt()).doesNotContain("SECRET_SENTINEL_13");
  }
  ```

  Exercise normal, ask-later, recent-history, and fail-soft fallback branches with the same sentinel. Verify both absence and zero read calls.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.ChatWorkflowS7PromptBoundaryContractTest" --tests "ai.abandonware.nova.orch.compress.DynamicContextCompressorTest" --no-daemon
  ```

- [ ] **Step 3: Gate once at the canonical boundary**

  Derive one `memoryReadEnabled` value from `MemoryMode.isReadEnabled()` and pass empty historical memory into every downstream fallback/compressor path when false. Do not duplicate phrase tables or bypass `PromptBuilder.build(PromptContext)`.

- [ ] **Step 4:** Re-run tests and record read-call counts and prompt hash only. Close ID 13 when all four branches prove zero reads.

---

### Task 2: Keep first-request owner identity stable and distrust untrusted forwarding headers

**Files:**

- Modify: `main/java/com/example/lms/web/OwnerKeyBootstrapFilter.java`.
- Modify: `main/java/com/example/lms/web/ClientOwnerKeyResolver.java`.
- Create only if an equivalent policy is absent: `main/java/com/example/lms/web/TrustedProxyPolicy.java`.
- Modify: `src/test/java/com/example/lms/web/OwnerKeyBootstrapFilterTest.java`.
- Modify: `src/test/java/com/example/lms/web/ClientOwnerKeyResolverTest.java`.

**Interfaces:**

- Consumes: validated owner cookie, request-scoped bootstrap attribute, immediate peer address, optional trusted-proxy CIDRs, user-agent.
- Produces: one owner key for the entire request and a hashed fallback derived from an authenticated peer boundary.

- [ ] **Step 1: Add first-request continuity and spoofing tests**

  ```java
  @Test
  void cookieLessRequestUsesTheSameOwnerEmittedByTheFilter() throws Exception {
      filter.doFilter(requestWithoutCookies(), response, (req, res) ->
          assertThat(resolverFor((HttpServletRequest) req).ownerKey())
              .isEqualTo(ownerCookieValue(response)));
  }

  @Test
  void untrustedPeerCannotChooseOwnerThroughForwardedFor() {
      request.setRemoteAddr("203.0.113.10");
      request.addHeader("X-Forwarded-For", "198.51.100.77");
      assertThat(resolver.ownerKey())
          .isEqualTo(hashOfPeerAndUa("203.0.113.10", userAgent));
  }

  @Test
  void trustedPeerUsesOnlyTheFirstValidatedForwardedAddress() {
      trustPeer("127.0.0.1");
      request.setRemoteAddr("127.0.0.1");
      request.addHeader("X-Forwarded-For", "198.51.100.77, 127.0.0.1");
      assertThat(resolver.ownerKey()).isEqualTo(hashOfPeerAndUa("198.51.100.77", userAgent));
  }
  ```

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.web.OwnerKeyBootstrapFilterTest" --tests "com.example.lms.web.ClientOwnerKeyResolverTest" --no-daemon
  ```

- [ ] **Step 3: Set a validated request attribute before the filter chain**

  Reuse the existing valid cookie or generate one UUID, set it as a private request attribute, emit the cookie, and let the resolver prefer the attribute. Do not accept a public `X-Owner-Key` header.

- [ ] **Step 4: Add fail-closed trusted-proxy handling**

  Default to an empty trust list. Validate configured address/CIDR entries at startup or disable forwarded-header use with a fixed reason. Never log the raw header or address list.

- [ ] **Step 5:** Re-run tests with absent/malformed cookie, direct untrusted peer, trusted loopback proxy, and no-current-request cases.

---

### Task 3: Cancel a session run before deletion and prevent every late side effect

**Files:**

- Modify: `main/java/com/example/lms/api/ChatApiController.java`.
- Modify if RED: `main/java/com/example/lms/service/chat/ChatRunRegistry.java`.
- Modify if RED: `main/java/com/example/lms/service/ChatWorkflow.java`.
- Create only when extraction is justified by ID 92: `main/java/com/example/lms/service/chat/SessionRunLifecycleService.java`.
- Modify: `src/test/java/com/example/lms/api/ChatApiControllerCancelTest.java`.
- Modify: `src/test/java/com/example/lms/service/chat/ChatRunRegistryEvictionTest.java`.
- Modify: `src/test/java/com/example/lms/service/ChatWorkflowCancellationContractTest.java`.

**Interfaces:**

- Consumes: authorized session ID, exact active run identity, history deletion, and workflow terminal checkpoints.
- Produces: cancel-before-delete ordering, a deletion fence for the old run, and zero late verifier/learning/memory/history/trace writes.

- [ ] **Step 1: Add ordered delete and late-worker RED tests**

  ```java
  @Test
  void deleteCancelsActiveRunBeforeRemovingHistory() {
      controller.deleteSession(sessionId);
      InOrder ordered = inOrder(runRegistry, historyService);
      ordered.verify(runRegistry).cancelSessionForDeletion(sessionId);
      ordered.verify(historyService).deleteSession(sessionId);
  }

  @Test
  void workerReleasedAfterDeletionCannotRecreateDurableState() {
      RunningFixture run = startAtPostGenerationBarrier();
      deleteAuthorizedSession(run.sessionId());
      run.releaseWorker();
      assertThat(historyMessages(run.sessionId())).isEmpty();
      assertThat(sideEffects()).containsOnlyZeros();
  }
  ```

  Use barriers/latches, not sleeps. Verify verifier, postprocessor, training, memory, transcript, trace-pointer, and session-meta collaborators separately.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.ChatApiControllerCancelTest" --tests "com.example.lms.service.chat.ChatRunRegistryEvictionTest" --tests "com.example.lms.service.ChatWorkflowCancellationContractTest" --no-daemon
  ```

- [ ] **Step 3: Introduce one exact deletion fence**

  Reuse `ChatRunRegistry` ownership and its terminal action. Add a session-deletion operation only if the current exact-token method cannot atomically mark the active run cancelled before the delete action. Retain the cancelled/deleted generation long enough that its worker cannot claim persistence; bound retention by the existing terminal TTL.

- [ ] **Step 4: Place cancellation checks before all terminal side effects**

  Consolidate the repeated final-stage checks into one package-visible checkpoint/coordinator only if this reduces `ChatWorkflow` responsibility for structural ID 89. A failed checkpoint returns a fixed cancellation outcome and performs no later work.

- [ ] **Step 5:** Re-run tests with cancellation before generation completion, before postprocessing, before transcript persistence, and immediately after final emission.

---

### Task 4: Apply feedback to the rated assistant record or report its absence

**Files:**

- Modify: `main/java/com/example/lms/api/FeedbackController.java`.
- Modify: `main/java/com/example/lms/service/MemoryReinforcementService.java`.
- Modify: `src/test/java/com/example/lms/api/FeedbackControllerInputTest.java`.
- Create or modify the nearest service test: `src/test/java/com/example/lms/service/MemoryReinforcementServiceTest.java`.

**Interfaces:**

- Consumes: authorized session, feedback ID/message ID, rating/correction, and memory mode.
- Produces: mutation of the exact rated assistant record or a neutral `rated_record_not_found` result with no fallback/global mutation.

- [ ] **Step 1: Add exact-record and missing-record tests**

  ```java
  @Test
  void feedbackTargetsTheRatedAssistantMessage() {
      feedback(messageIdOf("assistant answer B"), positiveRating());
      assertThat(reinforcedMessageIds()).containsExactly(answerBId);
  }

  @Test
  void missingRatedAssistantRecordDoesNotMutateAnyMemory() {
      ResponseEntity<?> response = feedback(unknownMessageId, negativeRating());
      assertThat(response.getStatusCode().value()).isEqualTo(404);
      assertThat(reason(response)).isEqualTo("rated_record_not_found");
      assertThat(memoryWrites()).isZero();
  }
  ```

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.FeedbackControllerInputTest" --tests "com.example.lms.service.MemoryReinforcementServiceTest" --no-daemon
  ```

- [ ] **Step 3: Resolve and authorize before mutation**

  Resolve the assistant record inside the already-authorized session, verify its role, and pass its durable ID/content hash to the reinforcement seam. Do not fall back to the latest global assistant record. Keep Memory OFF behavior from W2 authoritative.

- [ ] **Step 4:** Re-run exact/missing/wrong-role/cross-session cases and record only IDs, counts, hashes, and reason codes.

---

### Task 5: Hash fingerprints before storage and reject invalid debug capacity

**Files:**

- Modify: `main/java/com/example/lms/debug/DebugEventStore.java`.
- Modify: `src/test/java/com/example/lms/debug/DebugEventStoreFingerprintRetentionTest.java`.
- Create if needed: `src/test/java/com/example/lms/debug/DebugEventStoreConfigurationTest.java`.

**Interfaces:**

- Consumes: caller-provided fingerprint, message/data/error, `lms.debug.events.max-size`, and fingerprint capacity.
- Produces: one bounded hash label on every log/NDJSON/JSON/SSE surface and explicit startup/config failure for invalid capacity.

- [ ] **Step 1: Add a raw-sentinel fan-out test**

  Emit fingerprint `RAW_PRIVATE_FP_55` and assert it is absent from the ring event, rate-limit message, list-hotspots output, NDJSON serialization, JSON response, SSE event, and captured logs. Assert one stable bounded hash label is present on all surfaces.

- [ ] **Step 2: Add invalid-capacity cases**

  Instantiate the configuration with `0` and `-1`. Require a fixed `debug_event_capacity_invalid` failure/reason rather than a ring that silently retains nothing or loops incorrectly.

- [ ] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.debug.DebugEventStoreFingerprintRetentionTest" --tests "com.example.lms.debug.DebugEventStoreConfigurationTest" --no-daemon
  ```

- [ ] **Step 4: Normalize once at `emit` admission**

  Replace raw fingerprint use with a fixed-length SHA-256-derived label before aggregation, message construction, event storage, or output. Validate effective capacities once during initialization; do not clamp an invalid configured zero into apparently healthy behavior.

- [ ] **Step 5:** Re-run focused tests and a changed-file secret/privacy sentinel scan.

---

### Task 6: Make durable trace references self-resolving and evict only oldest budget entries

**Files:**

- Modify: `main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java`.
- Modify: `main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java`.
- Modify: `main/java/com/example/lms/trace/TraceSnapshotStore.java`.
- Modify: `src/test/java/com/example/lms/api/ChatTraceSnapshotPointerPersisterTest.java`.
- Create or modify: `src/test/java/com/example/lms/api/ChatTraceMetaMessageRestorerTest.java`.
- Modify: `src/test/java/com/example/lms/trace/TraceSnapshotRetentionStatsTest.java`.

**Interfaces:**

- Consumes: allowlisted trace metadata/projection, memory-ring snapshot ID, durable system message, and bounded capture budgets.
- Produces: a durable bounded fallback that survives restart/eviction and one-at-a-time oldest budget eviction.

- [ ] **Step 1: Add eviction/restart resolvability RED**

  Persist a trace pointer, evict or recreate the memory-only `TraceSnapshotStore`, restore the chat, and require a safe bounded trace projection with `storageMode=durable_fallback`. The test must assert raw trace HTML and private sentinels were not written to chat history.

- [ ] **Step 2: Add bounded-map overflow RED**

  Fill the capture-budget map with `capacity + 1` distinct request hashes. Assert only the oldest entry is evicted and every newer request retains its budget. A global empty map is failure.

- [ ] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.ChatTraceSnapshotPointerPersisterTest" --tests "com.example.lms.api.ChatTraceMetaMessageRestorerTest" --tests "com.example.lms.trace.TraceSnapshotRetentionStatsTest" --no-daemon
  ```

- [ ] **Step 4: Persist a bounded allowlisted fallback beside the pointer**

  Version the system-message envelope. Store only safe labels, booleans, counts, timings, hashes, reason codes, and a strict byte cap. Restore from the in-memory ring when present; otherwise render the durable projection. Keep the legacy prefix readable.

- [ ] **Step 5: Replace global clear with deterministic oldest eviction**

  Use a lock-protected insertion/access-ordered map or the existing order metadata. Remove only enough oldest entries to return to capacity. Never expose request keys.

- [ ] **Step 6:** Re-run tests for legacy pointer, live pointer, ring eviction, simulated restart, malformed durable envelope, and concurrent budget insertion.

---

### Task 7: Scope health by endpoint/model/context and promote only semantic success

**Files:**

- Modify: `main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java`.
- Modify the exact success call sites proved by the Task 0 trace; likely `main/java/com/example/lms/api/ChatApiController.java` and the active fallback/gateway owner.
- Modify: `src/test/java/com/example/lms/llm/ModelRuntimeHealthTrackerTest.java`.
- Modify: `src/test/java/com/example/lms/llm/ModelRuntimeRequestTimelineTest.java`.

**Interfaces:**

- Consumes: provider, endpoint hash, model, bounded runtime-context identity, transport outcome, answer presence, verification/guard decision, and terminal state.
- Produces: isolated route snapshots and semantic promotion only after required gates.

- [ ] **Step 1: Add route-isolation RED**

  ```java
  @Test
  void successOnOneEndpointCannotHealAnotherEndpoint() {
      tracker.recordSemanticSuccess(route("local", "ep-a", "m1", "ctx-a"), accepted());
      tracker.recordFailure(route("local", "ep-b", "m1", "ctx-a"), timeout());
      assertThat(tracker.snapshot(route("local", "ep-b", "m1", "ctx-a")).healthy()).isFalse();
  }

  @Test
  void transportSuccessWithoutSemanticAcceptanceDoesNotPromoteHealth() {
      tracker.recordAttemptSuccess(route, nonBlankTransport());
      tracker.recordSemanticOutcome(route, rejectedByEvidenceGuard());
      assertThat(tracker.snapshot(route).promotable()).isFalse();
  }
  ```

  Add endpoint A/B, model A/B, context A/B, blank answer, cancelled, verifier rejected, hard-guard held, and accepted final cases.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.llm.ModelRuntimeHealthTrackerTest" --tests "com.example.lms.llm.ModelRuntimeRequestTimelineTest" --no-daemon
  ```

- [ ] **Step 3: Introduce one immutable route-health key**

  Key snapshots by normalized provider, endpoint hash, model, and an allowlisted bounded context discriminator. Never key by raw endpoint, API key, prompt, or response. Keep endpoint quarantine compatible by deriving it from the same normalized route identity.

- [ ] **Step 4: Separate attempt success from semantic promotion**

  Keep wire/transport observations in the request-attempt timeline. Promote route health only after nonblank visible output, non-cancel terminal state, and every required verifier/hard-guard gate has accepted it. If verification is not required for a route, record that fixed policy explicitly rather than inferring success.

- [ ] **Step 5:** Re-run tests in both call orders so success/failure ordering on independent routes cannot change the verdict.

---

### Task 8: Verify and close W5

**Files:** W5 production/test diffs and the terminal ledger.

- [ ] **Step 1:** Run all W5 focused tests together with Desktop-isolated Gradle outputs.

  ```powershell
  $env:AWX_SPLIT_BUILD_OUTPUTS = '1'
  $env:AWX_BUILD_HOST_ID = 'desktop-w5'
  $env:GRADLE_USER_HOME = (Join-Path $env:TEMP 'awx-gradle-user-w5')
  .\gradlew.bat test --tests "com.example.lms.service.ChatWorkflowS7PromptBoundaryContractTest" --tests "com.example.lms.service.ChatWorkflowCancellationContractTest" --tests "com.example.lms.web.OwnerKeyBootstrapFilterTest" --tests "com.example.lms.web.ClientOwnerKeyResolverTest" --tests "com.example.lms.api.ChatApiControllerCancelTest" --tests "com.example.lms.service.chat.ChatRunRegistryEvictionTest" --tests "com.example.lms.api.FeedbackControllerInputTest" --tests "com.example.lms.service.MemoryReinforcementServiceTest" --tests "com.example.lms.debug.DebugEventStore*Test" --tests "com.example.lms.api.ChatTrace*Test" --tests "com.example.lms.trace.TraceSnapshotRetentionStatsTest" --tests "com.example.lms.llm.ModelRuntime*Test" --no-daemon --project-cache-dir (Join-Path $env:TEMP 'awx-project-cache-w5')
  ```

- [ ] **Step 2:** Run `compileJava`, focused `chatUiTest` only if public restore payloads changed, source-set hygiene, LangChain4j purity, and changed-file secret/privacy scans.
- [ ] **Step 3:** Inspect the diff for raw fingerprints, raw endpoint keys, trusted-header defaults, duplicated cancellation state, unbounded tombstones/maps, prompt concatenation, and unrelated formatting.
- [ ] **Step 4:** Close all 12 W5 rows with `PATCHED`, `NO_PATCH_NEEDED`, or lane-local `HOLD` plus exact evidence and one next verification action.
- [ ] **Step 5:** Record a no-commit checkpoint. Do not commit, push, deploy, alter proxy infrastructure, or mutate external memory/provider systems without separate authority.
