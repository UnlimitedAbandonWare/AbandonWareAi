# Demo-1 W2 Ownership and Public Mutation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close IDs 11, 12, 14, 15, 16, 19, 45, 50, 52, 57, 64, 65, 66, 82, and 83 by binding mutations to real owners and bounding public work before side effects.

**Architecture:** Attachment identity is principal-bound independently of client session IDs; feedback has validated, replay-safe mutation; Kakao sends require administrator authority and truthful failure responses; public chat uses bounded JSON intake, pagination/session quotas, and lease-based per-owner/global admission.

**Tech Stack:** Java 17, Spring Security/MVC/Validation/Data JPA, Java NIO, Reactor, JUnit 5, MockMvc, AssertJ, Mockito, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md`

## Global Constraints

- A client-supplied `sessionId` is never authority. Denial occurs before file, provider, tuning, vector, or database side effects.
- Upload defaults: at most 16 files and 25 MiB aggregate, in addition to existing per-file limits.
- Feedback message/correction maximum is 4,000 characters; identifiers are bounded to 128 characters; invalid rating is HTTP 400.
- Session list defaults to 50 and caps at 100; detail returns at most the latest 200 messages in chronological order.
- New bounded defaults in this plan are `chat.session.max-per-owner=200`, `chat.public.max-concurrent-per-owner=2`, and `chat.public.max-concurrent-global=32`; existing stricter properties win.
- No raw owner key, username, forwarding header, feedback text, recipient, or provider body enters traces/logs.
- No production dependency, schema migration, commit, push, deployment, credential/ACL mutation, real Kakao call, or destructive cleanup is authorized.
- Each production cohort requires its own stable three-way `APPLY`, lease, and immediate target-preimage verification.

## Coverage

| IDs | Task | Active owner |
|---|---|---|
| 11, 12, 45 | 1 | attachment controller/service/storage |
| 14, 15, 16, 50, 82 | 2 | feedback DTO/controller/reinforcement |
| 19, 57 | 3 | Kakao security/controller/service result |
| 52, 64, 65, 66, 83 | 4 | public budget, chat admission, history repositories |

---

### Task 0: Freeze W2 mutation cohorts

**Files:** All production/test targets named below.

**Interfaces:**

- Consumes: current session ownership rules and public endpoint mappings.
- Produces: four separately gated target manifests.

- [ ] **Step 1:** Refresh branch/HEAD/sourceSet/index-lock/PatchDrop/lease evidence and hash exact targets.
- [ ] **Step 2:** Run current focused baselines:

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.AttachmentServiceSessionOwnershipTest" --tests "com.example.lms.api.FeedbackControllerInputTest" --tests "com.example.lms.api.PublicRequestBudgetGuardTest" --tests "com.example.lms.api.ChatApiControllerStateSecurityTest" --tests "com.example.lms.web.OwnerKeyBootstrapFilterTest" --no-daemon
  ```

- [ ] **Step 3:** Freeze scenarios `S_W2_ATTACHMENT_OWNER`, `S_W2_FEEDBACK_MUTATION`, `S_W2_KAKAO_ADMIN`, and `S_W2_PUBLIC_ADMISSION`; run exactly the required positive/negative/neutral queries and require stable `APPLY` per changed cohort.

---

### Task 1: Bind attachments to principal identity and reject path/request expansion before writes

**Files:**

- Modify: `main/java/com/example/lms/api/AttachmentController.java`.
- Modify: `main/java/com/example/lms/service/AttachmentService.java`.
- Create: `main/java/com/example/lms/service/AttachmentOwnerIdentity.java`.
- Modify: `main/java/com/example/lms/storage/LocalFileStorageService.java`.
- Create: `src/test/java/com/example/lms/api/AttachmentControllerOwnerAdmissionTest.java`.
- Modify: `src/test/java/com/example/lms/service/AttachmentServiceSessionOwnershipTest.java`.
- Create: `src/test/java/com/example/lms/storage/LocalFileStorageRealRootTest.java`.

**Interfaces:**

- Consumes: Spring `Authentication`, `ClientOwnerKeyResolver.ownerKey()`, current session ownership, and multipart metadata.
- Produces: `AttachmentOwnerIdentity` hash, principal-aware service overloads, and real-root-safe save/delete.

- [ ] **Step 1: Write owner, count/aggregate, and reparse RED tests**

  ```java
  @Test
  void foreignSessionUploadRejectsBeforeStorage() {
      when(history.getSessionWithMessages(7L)).thenReturn(sessionOwnedBy("owner-a"));
      owner("owner-b");
      assertThatThrownBy(() -> controller.upload(List.of(file(1)), "7", auth()))
              .hasMessageContaining("session_forbidden");
      verifyNoInteractions(storage);
  }

  @Test
  void seventeenFilesOrAggregateOver25MiBRejectBeforeFirstSave() {
      assertRejected(files(17, 1));
      assertRejected(files(2, 13 * MIB));
      verifyNoInteractions(storage);
  }

  @Test
  void preSessionAttachmentCannotAttachToAnotherOwnerSession() {
      String id = saveAs("owner-a");
      assertThat(service.attachToSession("s-b", List.of(id), ownerHash("owner-b"))).isFalse();
  }

  @Test
  void uploadCannotTraverseLinkOrJunctionOutsideRealRoot() {
      Path link = createDirectoryLink(uploadRoot.resolve("escape"), outside);
      assertThatThrownBy(() -> local.save(file("x.txt"), "escape"))
              .hasMessageContaining("허용되지 않은 업로드 경로");
      assertThat(Files.list(outside)).isEmpty();
  }
  ```

  Include upload, inspect, archive, attach, find, and delete authorization paths. Assert zero `FileStorageService`, parser, vector, and repository side effects on denial.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.AttachmentControllerOwnerAdmissionTest" --tests "com.example.lms.service.AttachmentServiceSessionOwnershipTest" --tests "com.example.lms.storage.LocalFileStorageRealRootTest" --no-daemon
  ```

- [ ] **Step 3: Add principal-bound metadata and early admission**

  Add a package-visible value type that stores only a stable hash:

  ```java
  record AttachmentOwnerIdentity(String hash) {
      AttachmentOwnerIdentity {
          if (hash == null || hash.isBlank()) throw new IllegalArgumentException("missing_owner");
      }
  }
  ```

  `AttachmentController` resolves accessible session plus owner identity before touching files, validates file count and overflow-safe aggregate declared size, and passes identity into service methods. `AttachmentService` records `ownerHashById` atomically with DTO metadata; pre-session and session-bound attachment operations require equality. Never expose the owner hash in public DTOs.

  In `LocalFileStorageService`, validate normalized lexical containment, then real-path containment with no followed symbolic/reparse component before create/copy. Use the W1 safe deletion path for delete. Link/junction uncertainty rejects with a fixed reason.

- [ ] **Step 4: Run GREEN and attachment regressions**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.AttachmentController*" --tests "com.example.lms.service.AttachmentService*" --tests "com.example.lms.storage.*" --no-daemon
  ```

- [ ] **Step 5: Record IDs 11, 12, and 45 separately**

  ID 11 requires cross-owner denial; ID 12 requires real-root escape denial on save and delete; ID 45 requires zero writes after pre-admission rejection.

---

### Task 2: Validate and deduplicate feedback while preserving memory-off behavior and admin ownership

**Files:**

- Modify: `main/java/com/example/lms/dto/FeedbackDto.java`.
- Modify: `main/java/com/example/lms/api/FeedbackController.java`.
- Create: `main/java/com/example/lms/api/FeedbackMutationGuard.java`.
- Modify: `main/java/com/example/lms/service/MemoryReinforcementService.java` only if the focused memory-off/rated-record tests prove a source gap.
- Modify: `src/test/java/com/example/lms/api/FeedbackControllerInputTest.java`.
- Create: `src/test/java/com/example/lms/api/FeedbackMutationGuardTest.java`.
- Create: `src/test/java/com/example/lms/service/MemoryReinforcementFeedbackContractTest.java`.

**Interfaces:**

- Consumes: `FeedbackDto(sessionId,message,rating,corrected)`, authenticated username or owner cookie, and the latest assistant message for the session.
- Produces: validated `POSITIVE|NEGATIVE`, bounded replay fingerprint, and one mutation against an actual rated assistant record.

- [ ] **Step 1: Write five mutation-sensitive RED/characterization cases**

  ```java
  @Test
  void nullOrUnknownRatingReturns400WithoutMutation() {
      assertBadRequest(dto(null));
      assertBadRequest(dto("MAYBE"));
      verifyNoInteractions(memoryService);
  }

  @Test
  void identicalReplayMutatesStrategyOnce() {
      post(validFeedback());
      post(validFeedback());
      verify(memoryService, times(1)).applyFeedback(any(), any(), anyBoolean(), any());
  }

  @Test
  void memoryOffPerformsNoRepositoryOrVectorWrite() {
      serviceWithMemoryEnabled(false).applyFeedback("7", "answer", false, "correction");
      verifyNoInteractions(memoryRepository, vectorStore, perfRepo);
  }

  @Test
  void feedbackMustMatchAStoredAssistantAnswer() {
      historyHasOnlyAssistant("stored-answer");
      assertThat(post(dtoWithMessage("different-answer")).getStatusCode().value()).isEqualTo(409);
      verifyNoInteractions(memoryService);
  }

  @Test
  void accessibleAdminOwnedSessionIsAcceptedOnlyForThatAdmin() {
      assertThat(postAs("admin-a", adminSession("admin-a")).getStatusCode().value()).isEqualTo(200);
      assertThat(postAs("admin-b", adminSession("admin-a")).getStatusCode().value()).isEqualTo(403);
  }
  ```

  Add 4,001-character message/correction cases and maximum-valid boundary cases.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.FeedbackControllerInputTest" --tests "com.example.lms.api.FeedbackMutationGuardTest" --tests "com.example.lms.service.MemoryReinforcementFeedbackContractTest" --no-daemon
  ```

- [ ] **Step 3: Implement validation, rated-record binding, and replay guard**

  Add `@NotNull`, `@Size(max=4000)`, and `@Pattern(regexp="(?i)POSITIVE|NEGATIVE")` constraints and use `@Valid`. Authorize admin-owned sessions by matching `Authentication.getName()`; authorize anonymous sessions by owner key. Resolve the latest matching assistant record from loaded history and reject absent/mismatched content before mutation.

  `FeedbackMutationGuard` stores only a SHA-256 of owner identity, session ID, rated-message identity/content hash, normalized rating, and correction hash; bound it to 4,096 entries and 24 hours. The first call returns `ACCEPT`, an identical replay returns `REPLAY`, and a conflicting reuse returns `CONFLICT`. Controller skips mutation for `REPLAY` and returns 409 for conflict. Keep the existing `memoryEnabled` first guard; patch it only if the no-write characterization fails.

- [ ] **Step 4: Run GREEN and DTO/controller/service regressions**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.FeedbackController*" --tests "com.example.lms.service.MemoryReinforcement*" --no-daemon
  ```

- [ ] **Step 5: Record IDs 14, 15, 16, 50, and 82**

  Use `NO_PATCH_NEEDED` for ID 16 if the preimage's `memoryEnabled` guard passes the mutation-sensitive test; do not edit the service merely to manufacture a RED.

---

### Task 3: Require administrator authority and truthful Kakao send results

**Files:**

- Modify: `main/java/com/example/lms/config/AppSecurityConfig.java`.
- Modify: `main/java/com/example/lms/api/KakaoTriggerController.java`.
- Modify: `main/java/com/example/lms/integrations/KakaoMessageService.java` only for redacted error logging if the tests expose raw provider content.
- Create: `src/test/java/com/example/lms/api/KakaoTriggerSecurityContractTest.java`.

**Interfaces:**

- Consumes: authenticated role and `KakaoMessageService.pushUrl(...) -> boolean`.
- Produces: ADMIN-only trigger and non-success HTTP/UI result when send returns false.

- [ ] **Step 1: Write security and failure RED tests**

  ```java
  @Test
  void anonymousAndNonAdminCannotReachKakaoService() throws Exception {
      mvc.perform(post("/kakao/trigger").contentType(APPLICATION_JSON).content(safeBody()))
              .andExpect(status().isUnauthorized());
      verifyNoInteractions(kakaoService);
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void providerFalseReturnsBadGateway() throws Exception {
      when(kakaoService.pushUrl(any(), any(), any())).thenReturn(false);
      mvc.perform(post("/kakao/trigger").contentType(APPLICATION_JSON).content(safeBody()))
              .andExpect(status().isBadGateway());
  }
  ```

  Add form failure flash/result and recipient/message length validation. No test uses a real Kakao key or network call.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.KakaoTriggerSecurityContractTest" --no-daemon
  ```

- [ ] **Step 3: Apply ADMIN matcher and honor boolean result**

  Add explicit `POST /kakao/trigger` ADMIN authorization before broad authenticated/permit rules. Controller checks `pushUrl`; false returns 502 JSON with fixed `KAKAO_SEND_FAILED`, while the form path reports failure without a success flash. Replace any new raw throwable/provider logging with error type plus hash/length.

- [ ] **Step 4: Run GREEN and security boundary tests**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.KakaoTriggerSecurityContractTest" --tests "com.example.lms.config.AppSecurityConfigContractTest" --tests "com.example.lms.api.KakaoOAuthSecurityContractTest" --no-daemon
  ```

- [ ] **Step 5: Record IDs 19 and 57**

  ID 19 needs zero service call for non-admin; ID 57 needs a non-success contract for a false/failed provider result.

---

### Task 4: Bound public chat JSON, session cardinality/windows, and concurrent work

**Files:**

- Modify: `main/java/com/example/lms/api/PublicRequestBudgetGuard.java`.
- Create: `main/java/com/example/lms/api/PublicChatAdmissionGuard.java`.
- Modify: `main/java/com/example/lms/api/ChatApiController.java`.
- Modify: `main/java/com/example/lms/service/ChatHistoryService.java`.
- Modify: `main/java/com/example/lms/service/ChatHistoryServiceImpl.java`.
- Modify: `main/java/com/example/lms/repository/ChatSessionRepository.java`.
- Modify: `main/java/com/example/lms/repository/ChatMessageRepository.java`.
- Modify: `src/test/java/com/example/lms/api/PublicRequestBudgetGuardTest.java`.
- Create: `src/test/java/com/example/lms/api/PublicChatAdmissionGuardTest.java`.
- Create: `src/test/java/com/example/lms/api/ChatSessionWindowContractTest.java`.

**Interfaces:**

- Consumes: cancel/ack JSON, resolved owner identity, session list/detail requests, and reactive/synchronous chat lifecycles.
- Produces: `PublicChatAdmissionGuard.tryAcquire(ownerHash) -> Optional<Lease>`, Pageable repository reads, and 413/429 bounded responses.

- [ ] **Step 1: Write body, quota, pagination, admission, and title tests**

  ```java
  @Test
  void cancelAndAckRejectOversizeJsonBeforeBinding() throws Exception {
      assertPayloadTooLarge("/api/chat/cancel", jsonOverLimit());
      assertPayloadTooLarge("/api/chat/ack", jsonOverLimit());
  }

  @Test
  void listAndDetailClampWindows() {
      assertThat(list(500)).hasSize(100);
      assertThat(detail(sessionWithMessages(350)).messages()).hasSize(200);
      assertThat(detail(...).messages()).isSortedAccordingTo(createdAtAscending());
  }

  @Test
  void ownerQuotaRejectsBeforeSessionSave() {
      when(repo.countByOwnerKey("owner")).thenReturn(200L);
      assertThatThrownBy(() -> history.startNewSession("x", "anonymousUser", "owner"))
              .hasMessageContaining("session_quota_exceeded");
      verify(repo, never()).save(any());
  }

  @Test
  void perOwnerAndGlobalAdmissionRejectWith429AndReleasesOnTerminalSignal() {
      Lease first = guard.tryAcquire("owner-a").orElseThrow();
      Lease second = guard.tryAcquire("owner-a").orElseThrow();
      assertThat(guard.tryAcquire("owner-a")).isEmpty();
      first.close();
      assertThat(guard.tryAcquire("owner-a")).isPresent();
  }
  ```

  Add a title assertion against the 120-character entity limit; if current 20-character derivation passes, ID 83 is `NO_PATCH_NEEDED` and no production title edit is made.

- [ ] **Step 2: Run focused RED/characterization**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.PublicRequestBudgetGuardTest" --tests "com.example.lms.api.PublicChatAdmissionGuardTest" --tests "com.example.lms.api.ChatSessionWindowContractTest" --no-daemon
  ```

- [ ] **Step 3: Implement bounded admission and repository windows**

  Add `/api/chat/cancel` and `/api/chat/ack` to `PUBLIC_JSON_PATHS`. `PublicChatAdmissionGuard` uses one fair global semaphore and a bounded owner-counter map; `Lease.close()` is idempotent and removes zero-count owner entries. Controller acquires after owner resolution but before session creation/search/model work; sync paths use `try/finally`, reactive paths use `doFinally`.

  Add `Pageable` repository methods and count queries. Clamp list size to 1-100 (default 50), detail size to 1-200 (default 200), fetch detail descending at the database boundary, then reverse the selected window for chronological output. Enforce the 200-session owner/admin quota before save and recheck after a concurrent save conflict. Return fixed 429/400 reasons without owner/session raw values.

- [ ] **Step 4: Run GREEN and affected controller/history tests**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.api.PublicRequestBudgetGuardTest" --tests "com.example.lms.api.ChatApiController*" --tests "com.example.lms.api.ChatSession*" --tests "com.example.lms.service.ChatHistoryServiceImpl*" --no-daemon
  ```

- [ ] **Step 5: Record IDs 52, 64, 65, 66, and 83**

  Keep title disposition independent. A bounded list without create quota does not close ID 52; scheduler choice without acquired/released admission does not close ID 66.

---

### Task 5: Verify and close W2

**Files:** All W2 targets and ledger rows 11, 12, 14, 15, 16, 19, 45, 50, 52, 57, 64, 65, 66, 82, 83.

**Interfaces:**

- Consumes: four focused GREEN packets.
- Produces: W2 security/validation regression proof.

- [ ] **Step 1:** Run `test --tests "com.example.lms.api.*" --tests "com.example.lms.web.*Owner*" --tests "com.example.lms.service.AttachmentService*" --tests "com.example.lms.service.MemoryReinforcement*"` with the Gradle wrapper and record XML totals.
- [ ] **Step 2:** Run `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test`; add `:app:classes -x test` because shared DTO/repository interfaces changed.
- [ ] **Step 3:** Inspect exact diffs, postimage hashes, `git diff --check`, and count-only secret scan. Confirm no security matcher order accidentally opens a more specific endpoint.
- [ ] **Step 4:** Replace every W2 ledger row with a valid terminal state and release leases in `finally`. Do not stage or commit.
