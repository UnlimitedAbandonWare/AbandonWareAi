# Priority-100 Cohort B1 Security Intake and Egress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to execute this plan task-by-task. Repository policy keeps source writes, integration decisions, verification claims, and final judgment in the parent Codex session; subagents provide bounded read-only analysis and independent review.

**Goal:** Close authoritative backlog items 1 and 6 by bounding unauthenticated N8n webhook allocation before signature work and preventing internal request/session correlation identifiers from leaving the process in Brave provider headers.

**Architecture:** The N8n controller rejects a declared or observed body larger than one mebibyte with `413 PAYLOAD_TOO_LARGE`; unknown/chunked lengths are read through a `limit + 1` sentinel so allocation is bounded before HMAC or enqueue. Brave retains only provider-required authentication and content-negotiation headers. Both seams receive request-level behavioral tests rather than source-text-only checks.

**Tech Stack:** Java 17, Spring Boot MVC, Jakarta Servlet, Spring `RestTemplate`, JUnit 5, Mockito, Spring Test, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`

## Scope and constraints

- This plan implements only backlog ranks 1 and 6, the two highest-risk unblocked security/privacy boundaries. Attachment retention and unsafe profile/config defaults remain separate Cohort B work.
- Active production owners are root `main/java`; tests belong under `src/test/java`.
- `main/java/com/example/lms/api/N8nWebhookController.java` and `main/java/com/example/lms/service/web/BraveSearchService.java` are user-dirty. Preserve all unrelated hunks and re-hash immediately before each patch.
- Create new test files so the regression does not overwrite dirty test ownership.
- The N8n owner-level limit is exactly `1_048_576` bytes. This is a new explicit policy boundary, not a previously discovered repository setting.
- A declared `Content-Length` check is an optimization only. The observed-body check using `readNBytes(MAX_BODY_BYTES + 1)` is mandatory for missing, negative, false-short, or chunked lengths.
- Oversize input must not reach signature verification, UTF-8 conversion, or `JobService.enqueue`.
- Brave outbound requests must contain neither `x-session-id` nor `x-request-id`, regardless of header casing, while retaining `X-Subscription-Token` and JSON `Accept`.
- Do not log or trace raw bodies, signatures, idempotency keys, queries, provider responses, request/session identifiers, credentials, or full exception text. N8n diagnostics are fixed reasons plus count and bounded observed length only.
- Do not stage, commit, push, merge, deploy, call an external provider, mutate a live database, add a production dependency, or clean user files/worktrees.
- `FIXED` requires fresh focused RED/GREEN behavior, affected-boundary tests, source-set/version checks, compilation, broader isolated tests, exact diff review, and a count-only secret scan.

---

## File structure

### Production files

- Modify `main/java/com/example/lms/service/web/BraveSearchService.java`: remove the MDC-derived outbound request/session header block only.
- Modify `main/java/com/example/lms/api/N8nWebhookController.java`: introduce the one-mebibyte boundary, declared-length fast rejection, bounded observed read, `413` response, and redacted trace helper.

### New focused tests

- Create `src/test/java/com/example/lms/service/web/BraveSearchServiceOutboundHeaderTest.java`: inspect the real `RestTemplate` request with `MockRestServiceServer` and deny all internal correlation headers.
- Create `src/test/java/com/example/lms/api/N8nWebhookControllerBodyLimitTest.java`: prove early declared-length rejection, bounded unknown-length reading, and an accepted valid request at the exact boundary.

### Existing affected tests to retain

- `src/test/java/com/example/lms/service/web/SearchProviderTraceStandardizationTest.java`
- `src/test/java/com/example/lms/service/rag/adapter/JamminiBraveSearchAdapterTest.java`
- `src/test/java/com/example/lms/api/N8nWebhookControllerRedactionTest.java`
- `src/test/java/com/example/lms/integrations/n8n/SignatureVerifierTest.java`

---

### Task 0: Freeze the security-cohort decision and source ownership

**Files:**

- Read: `AGENTS.md`
- Read: the approved design and this plan
- Read: both production targets and the four directly affected existing tests
- Read: `__patch_drop__/janitor_inventory.ps1`
- Read: `__patch_drop__/source_edit_session.ps1`

- [ ] **Step 1: Refresh boundary evidence**

  Record Java/Gradle versions, branch, HEAD, worktrees, exact target status, `.git/index.lock`, PatchDrop inventory, and source-edit lease status. Confirm no active overlapping writer.

- [ ] **Step 2: Prove source ownership and exact preimages**

  Run `checkSourceSetHygiene` and `checkLangchain4jVersionPurity` with Desktop split output. Record SHA-256 for both production targets and all new/existing focused test targets. Inspect exact user diffs around the planned hunks.

- [ ] **Step 3: Run the mandatory three-way preflight**

  Freeze one redacted EvidenceSnapshot with at most 20 rows. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`. Require `APPLY` in both A-B and B-A packet order with identical decisive evidence IDs for scenarios `S_BRAVE_OUTBOUND_HEADER_DENIAL` and `S_N8N_BOUNDED_BODY`.

- [ ] **Step 4: Acquire one Desktop source-edit lease**

  Use topic `priority-100-cohort-b1-security-intake-egress`, owner `codex-root-priority-100-b1`, and a bounded TTL. If one target preimage overlaps or changes, HOLD only that scenario and continue the independent safe scenario.

---

### Task 1: Deny internal correlation headers at the Brave wire boundary

**Files:**

- Modify: `main/java/com/example/lms/service/web/BraveSearchService.java:974-994`
- Create: `src/test/java/com/example/lms/service/web/BraveSearchServiceOutboundHeaderTest.java`

- [ ] **Step 1: Write the outbound-wire RED test**

  Construct an enabled `BraveSearchService` with an in-memory non-secret test token, configure the existing `RestTemplate`, and bind `MockRestServiceServer`. Put distinct non-sensitive sentinels into `LogCorrelation.KEY_REQUEST_ID` and `LogCorrelation.KEY_SESSION_ID`. On the actual GET request, assert:

  - case-insensitive absence of `x-request-id` and `x-session-id`;
  - no header value, URI, or request body contains either sentinel;
  - `X-Subscription-Token` equals the test token;
  - `Accept` contains `application/json`.

  Return a minimal valid Brave JSON response and verify the request completed. Add a second no-MDC case proving the provider-required headers remain unchanged. Clear MDC, TraceStore, and mock-server state in `finally`/`@AfterEach`.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.web.BraveSearchServiceOutboundHeaderTest" --no-daemon
  ```

  Expected: the real outbound request contains at least `x-session-id` and the denial assertion fails. A fixture, quota, rate-limiter, or JSON-shape failure is not acceptable RED evidence.

- [ ] **Step 3: Apply the smallest production deletion**

  Preserve:

  ```java
  HttpHeaders headers = new HttpHeaders();
  headers.set("X-Subscription-Token", apiKey);
  headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
  HttpEntity<Void> entity = new HttpEntity<>(headers);
  ```

  Remove only the entire best-effort MDC correlation-header block that reads `LogCorrelation.KEY_REQUEST_ID` / `KEY_SESSION_ID` and sets `x-request-id` / `x-session-id`. Do not change URI construction, authentication, timeout, quota, breaker, parsing, or diagnostics behavior.

- [ ] **Step 4: Run focused GREEN and affected provider tests**

  Run the new test, `SearchProviderTraceStandardizationTest`, and `JamminiBraveSearchAdapterTest`. Inspect the exact diff and postimage hash before moving to Task 2.

---

### Task 2: Bound webhook allocation before signature verification

**Files:**

- Modify: `main/java/com/example/lms/api/N8nWebhookController.java:17-43,69-91`
- Create: `src/test/java/com/example/lms/api/N8nWebhookControllerBodyLimitTest.java`

- [ ] **Step 1: Write deterministic body-limit RED tests**

  Add four request-level cases:

  1. A mocked request declares `MAX_BODY_BYTES + 1`; `getInputStream()` must never be called, response is `413`, error is `PAYLOAD_TOO_LARGE`, and enqueue count is zero.
  2. A request reports unknown length and exposes a counting `ServletInputStream` with more than the limit; the controller reads exactly `MAX_BODY_BYTES + 1`, returns `413`, does not enqueue, and emits only `payload_too_large`, count `1`, and bounded length evidence.
  3. A request falsely reports length `0` while a counting `ServletInputStream` supplies more than the limit; the controller still reads exactly `MAX_BODY_BYTES + 1`, returns `413`, and does not enqueue. This is the decisive false-short bypass regression.
  4. A body exactly `MAX_BODY_BYTES` with a valid HMAC is accepted and enqueued once, proving the boundary is inclusive and signature behavior is preserved. Build the payload deterministically with `byte[] body = new byte[MAX_BODY_BYTES]` and `Arrays.fill(body, (byte) 'a')`. Construct the controller with the fixed non-secret test value `webhook-body-limit-test-secret`, and compute `"sha256=" + HexFormat.of().formatHex(Mac.getInstance("HmacSHA256").doFinal(body))` after initializing the MAC with `new SecretKeySpec(secret.getBytes(UTF_8), "HmacSHA256")`, matching the existing `SignatureVerifier` contract exactly.

  For all oversize cases, use an invalid or absent signature so `413` proves size precedence. Assert the response and TraceStore contain none of the body sentinel, signature, or idempotency key. The false-short and unknown-length fixtures must use the same counting-stream helper and assert the exact observed read count; a request mock that silently substitutes the declared length is not acceptable.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.N8nWebhookControllerBodyLimitTest" --no-daemon
  ```

  Expected: compilation fails because `MAX_BODY_BYTES` does not exist, or behavior returns `401` after unbounded reading. A malformed servlet fixture is not acceptable RED evidence.

- [ ] **Step 3: Add the bounded intake seam**

  Add package-visible:

  ```java
  static final int MAX_BODY_BYTES = 1_048_576;
  ```

  Before acquiring the stream, reject `request.getContentLengthLong() > MAX_BODY_BYTES`. Otherwise read only:

  ```java
  byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
  ```

  Reject `body.length > MAX_BODY_BYTES` before calling `verifier.verify(...)`. Return `HttpStatus.PAYLOAD_TOO_LARGE` with `Map.of("error", "PAYLOAD_TOO_LARGE")`.

  Add `traceAcceptPayloadTooLarge(int observedLength)` that writes only:

  - `api.n8nWebhook.accept.payloadTooLarge=true`;
  - increment `api.n8nWebhook.accept.payloadTooLarge.count`;
  - `api.n8nWebhook.accept.skipped.reason=payload_too_large`;
  - nonnegative `api.n8nWebhook.accept.bodyLength`, capped at `MAX_BODY_BYTES + 1` when only a declared larger length is known.

  Preserve all current signature-rejection, enqueue-failure, status, and user-owned count hunks unchanged.

- [ ] **Step 4: Run focused GREEN and affected N8n tests**

  Run the new test, `N8nWebhookControllerRedactionTest`, and `SignatureVerifierTest`. Inspect the exact diff and postimage hash.

---

### Task 3: Integrate, review, and verify the cohort

- [ ] **Step 1: Run affected-boundary selectors together**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.web.BraveSearchServiceOutboundHeaderTest" --tests "com.example.lms.service.web.SearchProviderTraceStandardizationTest" --tests "com.example.lms.service.rag.adapter.JamminiBraveSearchAdapterTest" --tests "com.example.lms.api.N8nWebhookControllerBodyLimitTest" --tests "com.example.lms.api.N8nWebhookControllerRedactionTest" --tests "com.example.lms.integrations.n8n.SignatureVerifierTest" --no-daemon
  ```

- [ ] **Step 2: Request independent review**

  Give the reviewer only the approved contract, exact target diff, RED/GREEN output, pre/post hashes, and privacy assertions. Require `SPEC`, `QUALITY`, and `VERDICT`; review count is not a vote. Fix any Critical/Important issue through a new RED regression before re-review.

- [ ] **Step 3: Broaden verification proportionally**

  Run Desktop-isolated `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes`, then host-isolated `test --rerun-tasks --fail-fast`. Run `bootJar` because the production request/provider boundary changed. No external Brave call or browser proof is required because `MockRestServiceServer` observes the wire request locally and the webhook seam is backend-only.

- [ ] **Step 4: Final integrity checks**

  Run `git diff --check` for exact targets, inspect the final diff, recompute SHA-256, and run a count-only secret scan over only the six planned files. Confirm the target files contain no raw test sentinel outside test fixtures and no `x-session-id`/`x-request-id` outbound setters.

- [ ] **Step 5: Record terminal dispositions and release the lease**

  Record rank 1 and rank 6 independently as `FIXED`, `NO_PATCH_NEEDED`, `EVIDENCE_NEEDED`, or lane-local `HOLD`, including exact verification evidence. Release the source-edit lease in `finally`. Do not claim Cohort B or the full 100-item goal complete; continue with attachment retention and unsafe profile/config defaults next.
