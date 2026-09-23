# Demo-1 W6 Provider Edge Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close IDs 40-43, 58, 59, 79, and 80 with caller-bounded provider deadlines, exact result caps, shared-future isolation, overflow-safe pagination, and redacted truthful failure/cooldown reporting.

**Architecture:** Patch only the active provider owner reached from a live call trace. Brave, Naver, SerpApi, OpenAI fallback, and Kakao remain independent cohorts. Request time comes from the existing addon `TimeBudgetContext`; provider calls receive only remaining time. No provider is silently substituted and no fake result is introduced.

**Tech Stack:** Java 17, Spring `RestTemplate`/WebClient, Caffeine async cache, LangChain4j 1.0.1, JUnit 5, AssertJ, MockWebServer or the repository's existing fake HTTP transport, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md`

## Global Constraints

- Use no real provider credentials or network calls. Test with local fake transports and redacted sentinel bodies.
- Never print or retain API keys, authorization headers, raw query text, remote body text, endpoint URLs, tokens, cookies, or forwarding headers.
- Clamp request counts before URI construction and trim parsed results afterward.
- A waiter timeout may time out only that waiter; it must not cancel a cache-owned/shared computation used by another waiter.
- Report the effective enforced cooldown, not the raw remote hint when clamping changes it.
- If the active OpenAI fallback call path cannot be proved, do not patch a candidate class. Close with exact `evidence_needed`/`HOLD` or `NO_PATCH_NEEDED` after an exhaustive active-source/call-path proof.
- Each changed production cohort requires stable three-way source-edit preflight and immediate preimage verification.

## Coverage

| IDs | Task | Active owner |
|---|---|---|
| 40, 43, 80 | 1 | `BraveSearchService` |
| 41, 42 | 2 | `NaverSearchService` |
| 59 | 3 | `SerpApiProvider` |
| 58 | 4 | call-path-gated `FallbackAwareChatModel` / `LlmRouterAspect` owner |
| 79 | 5 | `ChannelRecipientController` |

---

### Task 0: Prove active provider routes and freeze outbound contracts

**Files:** W6 targets, their bean wiring, focused tests, and the W6 terminal-ledger rows.

**Interfaces:**

- Consumes: active Spring bean/call references, request-budget state, configured provider limits, and current trace keys.
- Produces: five separately gated provider cohorts with exact owner evidence.

- [x] **Step 1:** Refresh branch/HEAD/source sets/index lock/worktrees/PatchDrop/leases and hashes for every W6 target.
- [x] **Step 2:** Trace the active callers of `BraveSearchService`, `NaverSearchService`, `SerpApiProvider`, `FallbackAwareChatModel`, and `ChannelRecipientController`. Record symbol paths and bean names only.
- [x] **Step 3:** For ID 58, prove that an OpenAI-configured primary/fallback route actually passes through the candidate owner and identify where remaining time can be enforced without a second request budget or request-owned executor.
- [x] **Step 4:** Run all current focused baselines named below. Freeze one stable three-way preflight per production owner that needs a patch.

---

### Task 1: Cap Brave by remaining time and make redaction/cooldown evidence exact

**Files:**

- Modify: `main/java/com/example/lms/service/web/BraveSearchService.java`.
- Modify: `src/test/java/com/example/lms/service/web/BraveSearchServiceOutboundHeaderTest.java`.
- Modify: `src/test/java/com/example/lms/service/web/SearchProviderTraceStandardizationTest.java`.
- Modify: `src/test/java/ai/abandonware/nova/orch/web/RateLimitBackoffCoordinatorRedactionTest.java` only if the active cooldown coordinator participates.

**Interfaces:**

- Consumes: configured Brave timeout, caller remaining budget, remote status/body, Retry-After/cooldown hint, and configured cooldown cap.
- Produces: effective timeout `<= remaining`, redacted failure facts, and trace cooldown equal to the enforced state.

- [x] **Step 1: Add caller-budget RED**

  ```java
  @Test
  void configuredTimeoutNeverExceedsCallerRemainingBudget() {
      withRequestBudgetMillis(180);
      service.applyRestTemplateTimeout(5_000);
      assertThat(capturedConnectTimeoutMillis()).isLessThanOrEqualTo(180);
      assertThat(capturedReadTimeoutMillis()).isLessThanOrEqualTo(180);
  }
  ```

  Include exhausted-budget behavior: no outbound call and fixed `request_budget_exhausted` reason.

- [x] **Step 2: Add raw remote sentinel and cooldown equality RED**

  Return status/body sentinel `REMOTE_PRIVATE_STATUS_43` plus an excessive Retry-After. Assert the sentinel is absent from logs and trace, body hash/length and status family remain, and `web.brave.cooldownMs` equals `cooldownRemainingMs()` within deterministic fake-clock tolerance after clamping.

- [x] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.web.BraveSearchServiceOutboundHeaderTest" --tests "com.example.lms.service.web.SearchProviderTraceStandardizationTest" --tests "ai.abandonware.nova.orch.web.RateLimitBackoffCoordinatorRedactionTest" --no-daemon
  ```

- [x] **Step 4: Compute one effective timeout and one effective cooldown**

  Cap the configured timeout by positive remaining duration before applying the request factory. Compute/clamp the cooldown once, update `cooldownUntilEpochMs` from that value, and trace that same value. Reduce remote errors to allowlisted status family, hash, length, and fixed reason.

- [x] **Step 5:** Re-run tests for no budget, exhausted budget, short remaining budget, status 429, malformed Retry-After, and excessive Retry-After.

---

### Task 2: Clamp Naver top-K and isolate each waiter from the shared cache future

**Files:**

- Modify: `main/java/com/example/lms/service/NaverSearchService.java`.
- Modify: `src/test/java/com/example/lms/service/NaverSearchServiceRedactionContractTest.java`.
- Modify: `src/test/java/com/example/lms/service/NaverSearchServiceInterruptContractTest.java`.
- Modify: `src/test/java/com/example/lms/resilience/SingleFlightManagerTest.java` only if that manager owns the active future.

**Interfaces:**

- Consumes: request top-K, configured Naver maximum, cache-owned future, caller remaining time/interruption.
- Produces: outbound `display` and returned list within the same cap, and waiter-local timeout/cancellation.

- [x] **Step 1: Add outbound-and-returned-count RED**

  ```java
  @Test
  void requestTopKIsClampedBeforeOutboundAndAfterParsing() {
      fakeNaverReturns(100);
      List<String> out = service.searchSnippetsSync("hash-only-query", 10_000);
      assertThat(capturedDisplay()).isEqualTo(configuredMaximum());
      assertThat(out).hasSize(configuredMaximum());
  }
  ```

  Include zero and negative admission according to the public contract; do not silently turn an explicitly valid zero into an unbounded result.

- [x] **Step 2: Add two-waiter cache RED**

  ```java
  @Test
  void timingOutOneWaiterDoesNotCancelSharedComputation() throws Exception {
      CompletableFuture<List<String>> shared = loaderFuture();
      CompletableFuture<List<String>> first = service.searchSnippetsMono(query).toFuture();
      CompletableFuture<List<String>> second = service.searchSnippetsMono(query).toFuture();
      timeoutFirstWaiter(first);
      shared.complete(List.of("result"));
      assertThat(second.get()).containsExactly("result");
      assertThat(shared.isCancelled()).isFalse();
  }
  ```

- [x] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.NaverSearchServiceRedactionContractTest" --tests "com.example.lms.service.NaverSearchServiceInterruptContractTest" --tests "com.example.lms.resilience.SingleFlightManagerTest" --no-daemon
  ```

- [x] **Step 4: Apply timeout to a dependent stage, not the cache-owned future**

  Derive a waiter-owned dependent future/stage and apply timeout/cancellation there. Never call `cancel` or mutating timeout methods on the cache's shared future. Preserve caller interruption and leave the shared computation available for later waiters/cache fill.

- [x] **Step 5:** Re-run with first waiter timeout, first waiter interruption, second waiter success, loader failure, and result overflow.

---

### Task 3: Bound SerpApi outbound and returned counts

**Files:**

- Modify: `main/java/com/example/lms/gptsearch/web/impl/SerpApiProvider.java`.
- Create: `src/test/java/com/example/lms/gptsearch/web/impl/SerpApiProviderTest.java`.

**Interfaces:**

- Consumes: `WebSearchQuery.topK`, configured maximum, fake SerpApi JSON, provider-disabled state.
- Produces: bounded `num` query parameter and bounded `WebDocument` list.

- [x] **Step 1: Add a fake-HTTP clamp test**

  ```java
  @Test
  void topKIsClampedBeforeUriAndAfterParsing() throws Exception {
      provider = enabledProviderWithMaximum(20, fakeResponseWithResults(80));
      WebSearchResult result = provider.search(queryWithTopK(Integer.MAX_VALUE));
      assertThat(capturedQueryParameter("num")).isEqualTo("20");
      assertThat(result.getDocuments()).hasSize(20);
  }
  ```

  Also assert missing/dummy credentials produce disabled state and zero outbound calls. Capture the request URI only inside the fake server and never print it because it contains `api_key`.

- [x] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.gptsearch.web.impl.SerpApiProviderTest" --no-daemon
  ```

- [x] **Step 3: Add one bounded property and helper**

  Reuse a closer existing provider maximum if present; otherwise add a positive bounded property beside current SerpApi settings. Clamp once before URI construction and `limit`/sublist after parsing. Keep current disabled reasons and LangChain4j versions unchanged.

- [x] **Step 4:** Re-run missing-key, zero/negative, within-limit, huge-limit, oversized-response, timeout, and rate-limit cases.

---

### Task 4: Bound the active OpenAI fallback by the original request deadline

**Files:**

- Characterize: `main/java/ai/abandonware/nova/orch/aop/LlmRouterAspect.java`.
- Modify only if the call trace confirms ownership: `main/java/com/example/lms/llm/gateway/FallbackAwareChatModel.java`.
- Modify only if it is the existing whole-call owner: the repository's current timed-chat-model caller/configuration file.
- Modify: `src/test/java/com/example/lms/llm/gateway/FallbackAwareChatModelTest.java`.
- Modify: `src/test/java/ai/abandonware/nova/orch/aop/LlmRouterHttpAttemptBudgetContractTest.java`.

**Interfaces:**

- Consumes: original monotonic request deadline, primary failure, fallback eligibility, active OpenAI route, and remaining time.
- Produces: at most one eligible fallback attempt whose deadline is no later than the original request deadline.

- [x] **Step 1: Prove exact ownership before RED**

  Trace a fake configured OpenAI route through `LlmRouterAspect` into the wrapper that executes `fallback.chat(...)`. If no active OpenAI fallback branch reaches that code, record exact bean/call evidence and close ID 58 as `NO_PATCH_NEEDED` or lane-local `HOLD`; do not edit either candidate.

- [x] **Step 2: Add one-deadline RED on the confirmed owner**

  ```java
  @Test
  void fallbackReceivesOnlyTimeRemainingAfterPrimaryFailure() {
      withRequestBudgetMillis(160);
      primaryBlocksThenFailsAfter(110);
      fallbackBlocksFor(110);
      assertThat(measureMillis(() -> model.chat(messages()))).isLessThan(240);
      assertThat(trace("llm.gateway.fallback.remainingMs")).isBetween(0L, 80L);
  }
  ```

  Use latches/fake clock when the active timed caller permits it. Assert no fallback starts when remaining time is exhausted.

- [x] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.llm.gateway.FallbackAwareChatModelTest" --tests "ai.abandonware.nova.orch.aop.LlmRouterHttpAttemptBudgetContractTest" --no-daemon
  ```

- [x] **Step 4: Propagate the existing deadline**

  Cap the fallback transport/client timeout or existing timed-caller lease by `TimeBudgetContext` remaining time. Do not create a second request-owned executor. Record attempt started/completed/unfinished truthfully; cancellation request alone is not worker termination.

- [x] **Step 5:** Re-run primary-fast-fail, primary-late-fail, exhausted-before-fallback, fallback-success, and interrupt-ignoring fallback cases.

---

### Task 5: Reject or clamp Kakao pagination before overflow and provider access

**Files:**

- Modify: `main/java/com/example/lms/api/ChannelRecipientController.java`.
- Modify: `src/test/java/com/example/lms/api/ChannelRecipientControllerTest.java`.
- Modify only if the controller delegates validation there: `src/test/java/com/example/lms/impl/ChannelRecipientServiceImplTest.java`.

**Interfaces:**

- Consumes: integer `page`, configured page size, authenticated access-token presence, recipient service.
- Produces: bounded nonnegative offset or validation failure before `fetchRecipientIds`.

- [x] **Step 1: Add overflow and invalid-input RED**

  ```java
  @Test
  void hugePageCannotOverflowOffsetOrCallProvider() {
      assertThat(controller.listRecipients(Integer.MAX_VALUE, model, redirect).getStatusCode().value())
          .isEqualTo(400);
      verifyNoInteractions(recipientService);
  }

  @Test
  void negativePageIsRejectedBeforeTokenOrProviderUse() {
      assertValidationFailure(controller.listRecipients(-1, model, redirect));
      verifyNoInteractions(recipientService);
  }
  ```

  Adapt the assertion to the existing MVC return type; prove the rendered/redirected contract with MockMvc when it is not `ResponseEntity`.

- [x] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.ChannelRecipientControllerTest" --tests "com.example.lms.impl.ChannelRecipientServiceImplTest" --no-daemon
  ```

- [x] **Step 3: Validate with long arithmetic before conversion**

  Validate `page >= 1`, positive bounded page size, and `((long) page - 1L) * pageSize <= Integer.MAX_VALUE` before converting to `int`. Prefer a configured/provider-supported maximum page and return the existing validation view/HTTP 400 contract. Do not log access tokens or recipients.

- [x] **Step 4:** Re-run page `1`, boundary page, boundary+1, `Integer.MAX_VALUE`, negative page, and malformed MVC binding cases.

---

### Task 6: Verify and close W6

**Files:** W6 production/test diffs and the terminal ledger.

- [x] **Step 1:** Run all W6 focused tests together with isolated Desktop Gradle outputs.

  ```powershell
  $env:AWX_SPLIT_BUILD_OUTPUTS = '1'
  $env:AWX_BUILD_HOST_ID = 'desktop-w6'
  $env:GRADLE_USER_HOME = (Join-Path $env:TEMP 'awx-gradle-user-w6')
  .\gradlew.bat test --tests "com.example.lms.service.web.BraveSearchServiceOutboundHeaderTest" --tests "com.example.lms.service.web.SearchProviderTraceStandardizationTest" --tests "com.example.lms.service.NaverSearchService*Test" --tests "com.example.lms.resilience.SingleFlightManagerTest" --tests "com.example.lms.gptsearch.web.impl.SerpApiProviderTest" --tests "com.example.lms.llm.gateway.FallbackAwareChatModelTest" --tests "ai.abandonware.nova.orch.aop.LlmRouterHttpAttemptBudgetContractTest" --tests "com.example.lms.api.ChannelRecipientControllerTest" --tests "com.example.lms.impl.ChannelRecipientServiceImplTest" --no-daemon --project-cache-dir (Join-Path $env:TEMP 'awx-project-cache-w6')
  ```

- [x] **Step 2:** Run `compileJava`, provider trace standardization tests, source-set hygiene, LangChain4j version purity, and changed-file secret/redaction scans.
- [x] **Step 3:** Inspect the diff for URI/API-key logging, raw body/status storage, shared-future cancellation, per-attempt deadline reset, integer multiplication before validation, and provider substitution.
- [x] **Step 4:** Close all eight W6 rows with `PATCHED`, `NO_PATCH_NEEDED`, or lane-local `HOLD` plus exact evidence and one next verification action.
- [x] **Step 5:** Record a no-commit checkpoint. No real provider request, credential change, commit, push, or deploy is authorized.
