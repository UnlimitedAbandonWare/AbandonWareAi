# Demo-1 W1 Durability and Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close IDs 5, 6, 7, 32, 44, 46, 47, 62, and 63 without losing recoverable state or allowing unbounded process-local retention.

**Architecture:** Four owner-level cohorts enforce destination-before-source state transitions, safe-root physical deletion, validate-then-publish archive ingestion, and bounded hash-only n8n acceptance/status retention. Each cohort is independently testable and receives its own source-edit preflight.

**Tech Stack:** Java 17, Spring MVC, Jackson, Java NIO, JUnit 5, AssertJ, Mockito, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md`

## Global Constraints

- Active owners are root `main/java` and `src/test/java`; preserve all dirty neighboring hunks.
- Defaults are: archive 500 entries, 2 MiB per entry, 64 MiB cumulative; n8n idempotency 4,096 entries and 24 hours.
- Persist a destination before removing its source. Failure retains the source and never returns success.
- Store only idempotency-key/body hashes, job ID, timestamps, counts, and reason codes.
- No production dependency, commit, push, deployment, provider call, database mutation, material deletion, or worktree cleanup is authorized.
- Before each production cohort, pass `$demo1-source-edit-three-way-preflight`, acquire the existing source lease, and re-hash every declared target immediately before `apply_patch`.

## Coverage

| IDs | Task | Active owner |
|---|---|---|
| 5, 6, 7, 32 | 1 | `FileDegradedStorage` JSONL transitions |
| 44 | 2 | `AttachmentService` and `LocalFileStorageService` |
| 46, 47 | 3 | `ConversationArchiveIngestService` |
| 62, 63 | 4 | `N8nWebhookController`, bounded idempotency registry, `InMemoryJobService` |

---

### Task 0: Freeze W1 ownership and focused baselines

**Files:**

- Read: `main/java/ai/abandonware/nova/orch/storage/FileDegradedStorage.java`.
- Read: `main/java/com/example/lms/service/AttachmentService.java`.
- Read: `main/java/com/example/lms/storage/FileStorageService.java`.
- Read: `main/java/com/example/lms/storage/LocalFileStorageService.java`.
- Read: `main/java/com/example/lms/conversation/archive/ConversationArchiveIngestService.java`.
- Read: `main/java/com/example/lms/api/N8nWebhookController.java`.
- Read: `main/java/com/example/lms/jobs/InMemoryJobService.java`.

**Interfaces:**

- Consumes: current preimages and active call paths.
- Produces: four non-overlapping mutation cohorts and their exact verification commands.

- [ ] **Step 1: Refresh source gates and current target hashes**

  Confirm index lock false, no top-level PatchDrop, no source lease, unchanged active sourceSets, and target status. Hash each file with `Get-FileHash -Algorithm SHA256` without recording contents.

- [ ] **Step 2: Run current focused baselines**

  ```powershell
  .\gradlew.bat test --tests "ai.abandonware.nova.orch.storage.FileDegradedStorageConcurrencyTest" --tests "com.example.lms.service.AttachmentServiceRetentionTest" --tests "com.example.lms.conversation.archive.ConversationArchiveIngestServiceTest" --tests "com.example.lms.api.N8nWebhookControllerBodyLimitTest" --tests "com.example.lms.jobs.InMemoryJobServiceTraceContractTest" --no-daemon
  ```

  Expected: current unrelated tests pass. Baseline green does not close any W1 ID because these tests do not inject the required failures.

- [ ] **Step 3: Freeze four three-way packets**

  Use scenario IDs `S_W1_FILE_TRANSITIONS`, `S_W1_ATTACHMENT_DELETE`, `S_W1_ARCHIVE_ATOMICITY`, and `S_W1_N8N_RETENTION`. Each snapshot contains at most 20 redacted rows and receives exactly the three required queries plus stable neutral `APPLY` before its source write.

---

### Task 1: Make JSONL claim, NACK, dedupe, and stale recovery loss-safe

**Files:**

- Modify: `main/java/ai/abandonware/nova/orch/storage/FileDegradedStorage.java`.
- Create: `src/test/java/ai/abandonware/nova/orch/storage/FileDegradedStorageDurabilityTest.java`.

**Interfaces:**

- Consumes: `putPending(PendingMemoryEvent)`, `claim(int)`, `nack(String,String)`, and `sweep()`.
- Produces: boolean destination-append result; per-claim ID dedupe; source retention when append/rewrite fails.

- [ ] **Step 1: Write four filesystem-failure RED tests**

  Use a JSONL temp path configured through `NovaOrchestrationProperties`. Replace only the destination path under test with a directory immediately before the transition, then restore it in `finally`.

  ```java
  @Test
  void failedInflightAppendKeepsPendingAndReturnsNoClaim() {
      storage.putPending(event("claim-loss"));
      makeDirectory(pending.resolveSibling(pending.getFileName() + ".inflight"));
      assertThat(storage.claim(1)).isEmpty();
      assertThat(storage.stats().pending()).isEqualTo(1);
  }

  @Test
  void failedNackDestinationKeepsInflightRecoverable() {
      String token = storage.claim(1).get(0).token();
      makeDirectory(pending); // force requeue append failure
      storage.nack(token, "safe_fixture_failure");
      assertThat(readIds(inflight)).contains("nack-loss");
  }

  @Test
  void duplicatePendingIdsAppearOncePerClaimBatch() {
      appendEnvelopeTwice(pending, "duplicate-id");
      assertThat(storage.claim(10)).extracting(ClaimedPending::token)
              .containsExactly("duplicate-id");
  }

  @Test
  void staleRecoveryRemovesOnlySuccessfullyRequeuedRows() {
      seedTwoStaleInflightRows();
      failSecondPendingAppend();
      storage.sweep();
      assertThat(readIds(inflight)).containsExactly("stale-2");
      assertThat(readIds(pending)).contains("stale-1");
  }
  ```

  Helpers must use only temp files, bounded latches or deterministic filesystem states, and count/hash assertions. Do not add sleeps or raw event text to failure messages.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "ai.abandonware.nova.orch.storage.FileDegradedStorageDurabilityTest" --no-daemon
  ```

  Expected: the claim, NACK, duplicate, and recovery assertions fail against current destination-result-discarding behavior; fixture setup itself must pass.

- [ ] **Step 3: Implement destination-before-source transitions**

  Change the wrapper to preserve `tryAppendJsonl`'s result:

  ```java
  private boolean appendJsonl(Path file, OutboxEnvelope env) {
      return tryAppendJsonl(file, env);
  }
  ```

  In `claimJsonl`, add a `seenThisClaim` set and retain a selected envelope in pending unless inflight append succeeded. In `nack`, append the updated envelope to pending/quarantine first and remove the exact inflight row only on success. In `recoverStaleInflightJsonl`, build `remainingInflight` and remove a row only when pending append returns true. Increment success counters only after the full transition commits.

- [ ] **Step 4: Run focused GREEN and adjacent storage tests**

  ```powershell
  .\gradlew.bat test --tests "ai.abandonware.nova.orch.storage.FileDegradedStorageDurabilityTest" --tests "ai.abandonware.nova.orch.storage.FileDegradedStorageConcurrencyTest" --tests "ai.abandonware.nova.orch.storage.DegradedStorageDrainerRedactionContractTest" --no-daemon
  ```

  Expected: all pass; duplicate suppression is per claim only and later redelivery after failed ACK remains possible.

- [ ] **Step 5: Record one root-cause disposition group**

  Record ID 5 as `PATCHED`; IDs 6, 7, and 32 may be `MERGED_WITH:5` only if their individual focused cases pass and the ledger explains the shared discarded-destination-result cause.

---

### Task 2: Delete attachment bytes through one real-root-safe storage operation

**Files:**

- Modify: `main/java/com/example/lms/storage/FileStorageService.java`.
- Modify: `main/java/com/example/lms/storage/LocalFileStorageService.java`.
- Modify: `main/java/com/example/lms/service/AttachmentService.java`.
- Create: `src/test/java/com/example/lms/service/AttachmentServicePhysicalDeletionTest.java`.

**Interfaces:**

- Consumes: `AttachmentDto.url()`, `AttachmentService.delete`, `deleteForSession`, and expiry metadata deletion.
- Produces: `boolean delete(String storedPath)` that operates only on a real path beneath the configured upload root.

- [ ] **Step 1: Write physical-delete and escape RED tests**

  ```java
  @Test
  void explicitDeleteRemovesMetadataAndPhysicalFile() {
      AttachmentDto saved = service.saveAll(List.of(file("proof.txt"))).get(0);
      Path physical = resolveUpload(saved.url());
      service.delete(saved.id());
      assertThat(Files.exists(physical)).isFalse();
      assertThat(service.find(saved.id())).isEmpty();
  }

  @Test
  void expiryRemovesPhysicalFileButNeverFollowsOutsideLink() {
      Path outside = Files.writeString(temp.resolve("outside.txt"), "safe");
      Path link = createLinkInsideUploadRoot(outside);
      expireAttachmentWithUrl(link);
      service.evictExpiredAttachments();
      assertThat(Files.exists(outside)).isTrue();
  }
  ```

  Include session-scoped delete and missing-file idempotency. Skip the link-specific assertion only when the host cannot create a link/reparse fixture, while retaining a lexical-plus-real-path unit test through an injected root fixture.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.AttachmentServicePhysicalDeletionTest" --no-daemon
  ```

  Expected: the physical file remains because metadata deletion currently performs no storage delete.

- [ ] **Step 3: Add one safe storage deletion API**

  Add `boolean delete(String storedPath)` to `FileStorageService`. In `LocalFileStorageService`, strip only the known `/uploads/` prefix, resolve beneath normalized root, reject lexical escape, resolve the nearest existing parent/root with `toRealPath(NOFOLLOW_LINKS)`, reject symlink/reparse traversal, and call `Files.deleteIfExists` only for the proven target. Log root filename, result, and path hash only.

  In `AttachmentService`, return the deleted DTO from `deleteMetadataLocked`; call storage deletion after releasing `metadataMutationLock`. Explicit delete, session delete, and TTL eviction all use the same helper. A failed physical delete retains a fixed reason breadcrumb and never claims byte deletion.

- [ ] **Step 4: Run GREEN and retention/ownership regressions**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.AttachmentServicePhysicalDeletionTest" --tests "com.example.lms.service.AttachmentServiceRetentionTest" --tests "com.example.lms.service.AttachmentServiceSessionOwnershipTest" --no-daemon
  ```

- [ ] **Step 5: Record ID 44**

  Use `PATCHED` only when explicit delete, session delete, expiry, missing-file, and escape cases pass. Otherwise record the exact unsupported host link proof as `HOLD` without deleting external test data.

---

### Task 3: Validate complete archives before publishing any vector chunk

**Files:**

- Modify: `main/java/com/example/lms/conversation/archive/ConversationArchiveIngestService.java`.
- Modify: `src/test/java/com/example/lms/conversation/archive/ConversationArchiveIngestServiceTest.java`.

**Interfaces:**

- Consumes: ZIP entries and `VectorStoreService.enqueue(explicitId,sessionId,text,metadata)`.
- Produces: a bounded `StagedChunk` list published only after every selected entry validates.

- [ ] **Step 1: Add atomic-prefix and cumulative-byte RED tests**

  ```java
  @Test
  void invalidLaterEntryPublishesNoEarlierChunk() {
      MultipartFile zip = zip(entry("first.txt", validExport()), corruptEntry("later.txt"));
      assertThatThrownBy(() -> service.ingest(List.of(zip), "s1"))
              .hasMessageContaining("invalid_zip_archive");
      verify(vectorStoreService, never()).enqueue(any(), any(), any(), anyMap());
  }

  @Test
  void cumulativeDecompressedBytesRejectBeforePublish() {
      MultipartFile zip = zipOfRepeatedTextEntries(33, 2 * 1024 * 1024);
      assertThatThrownBy(() -> service.ingest(List.of(zip), "s1"))
              .hasMessageContaining("archive_total_too_large");
      verifyNoInteractions(vectorStoreService);
  }
  ```

  Add a retry assertion: a rejected archive followed by the valid archive publishes each deterministic chunk ID exactly once.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.conversation.archive.ConversationArchiveIngestServiceTest" --no-daemon
  ```

  Expected: the later-invalid fixture observes an early enqueue and the cumulative fixture exceeds 64 MiB without a total rejection.

- [ ] **Step 3: Stage, account, then publish**

  Add `MAX_TOTAL_DECOMPRESSED_BYTES = 64L * 1024 * 1024`, overflow-safe cumulative accounting, and:

  ```java
  private record StagedChunk(String id, String sessionId, String text,
                             Map<String, Object> metadata) { }
  ```

  `readZip` and `processTextEntry` append immutable staged chunks and never call the vector service. After all files validate, iterate the staged list once and enqueue deterministic IDs. Any parse/limit failure discards the local list and returns a bounded 400 reason before publishing.

- [ ] **Step 4: Run GREEN and controller boundary tests**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.conversation.archive.*" --tests "com.example.lms.api.AttachmentControllerConversationArchiveTest" --no-daemon
  ```

- [ ] **Step 5: Record IDs 46 and 47**

  Record ID 46 as the owning patch and ID 47 as `MERGED_WITH:46` only when the cumulative-limit test passes and no early enqueue occurs.

---

### Task 4: Add bounded n8n idempotency and bounded retention for every job state

**Files:**

- Create: `main/java/com/example/lms/integrations/n8n/N8nIdempotencyRegistry.java`.
- Create: `src/test/java/com/example/lms/integrations/n8n/N8nIdempotencyRegistryTest.java`.
- Modify: `main/java/com/example/lms/api/N8nWebhookController.java`.
- Modify: `src/test/java/com/example/lms/api/N8nWebhookControllerBodyLimitTest.java`.
- Modify: `main/java/com/example/lms/jobs/InMemoryJobService.java`.
- Modify: `src/test/java/com/example/lms/jobs/InMemoryJobServiceTraceContractTest.java`.

**Interfaces:**

- Consumes: optional `Idempotency-Key`, body bytes, `JobService.enqueue(String)`, and `status(String)`.
- Produces: `N8nIdempotencyRegistry.accept(keyHash,bodyHash,Supplier<String>) -> Decision(jobId,replayed,conflict)` and bounded all-state job retention.

- [ ] **Step 1: Write idempotency and all-state retention RED tests**

  ```java
  @Test
  void sameKeyAndBodyReturnsOriginalJobWithoutSecondEnqueue() {
      ResponseEntity<?> first = accept("key-a", body("x"));
      ResponseEntity<?> replay = accept("key-a", body("x"));
      assertThat(jobIds(first, replay)).containsOnly(oneJobId());
      assertThat(jobs.enqueueCalls()).isEqualTo(1);
  }

  @Test
  void sameKeyWithDifferentBodyReturnsConflict() {
      accept("key-a", body("x"));
      assertThat(accept("key-a", body("y")).getStatusCode().value()).isEqualTo(409);
  }

  @Test
  void pendingStatusesExpireAndCapacityNeverExceeds4096() {
      enqueueAt(now, 4_097);
      assertThat(retainedStatusCount()).isLessThanOrEqualTo(4_096);
      advanceBy(Duration.ofHours(24).plusSeconds(1));
      assertThat(service.status(oldestId)).isEqualTo("NOT_FOUND");
  }
  ```

  Also prove a missing idempotency key still enqueues twice, registry contents expose no raw key/body, terminal/PENDING/RUNNING all share the same cap, and expiry never fabricates `SUCCEEDED`.

- [ ] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.integrations.n8n.N8nIdempotencyRegistryTest" --tests "com.example.lms.api.N8nWebhookControllerBodyLimitTest" --tests "com.example.lms.jobs.InMemoryJobServiceTraceContractTest" --no-daemon
  ```

- [ ] **Step 3: Implement hash-only acceptance and all-state eviction**

  The registry stores `keyHash`, `bodyHash`, `jobId`, and `acceptedAtEpochMs` only. Normalize capacity to 4,096 and TTL to 86,400,000 ms, evict expired/oldest entries under one short mutex, and execute the enqueue supplier at most once for a key/body pair. Controller hashes the optional header and body after signature validation; blank key follows current at-least-once behavior; conflict returns HTTP 409 with fixed `IDEMPOTENCY_CONFLICT`.

  In `InMemoryJobService`, centralize every state write through `putStatus(jobId,state,now)`, retain bounded timestamped state order for PENDING/RUNNING/terminal states, and evict expired/oldest exact `(jobId,stateVersion)` entries. Use a package-visible clock constructor for deterministic tests. `status` returns `NOT_FOUND` after expiry.

- [ ] **Step 4: Run GREEN and endpoint/security regressions**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.integrations.n8n.*" --tests "com.example.lms.api.N8nWebhookController*" --tests "com.example.lms.jobs.InMemoryJobServiceTraceContractTest" --tests "com.example.lms.config.AppSecurityConfigContractTest" --no-daemon
  ```

- [ ] **Step 5: Record IDs 62 and 63**

  Record separately because idempotent acceptance and bounded lifecycle have different production owners and tests. State explicitly that idempotency is process-local and no cross-restart guarantee was added.

---

### Task 5: Verify and close W1

**Files:** All W1 targets and ledger rows 5, 6, 7, 32, 44, 46, 47, 62, 63.

**Interfaces:**

- Consumes: four focused GREEN packets.
- Produces: W1 affected-boundary proof and postimage integrity.

- [ ] **Step 1: Run W1 affected-boundary tests**

  ```powershell
  .\gradlew.bat test --tests "ai.abandonware.nova.orch.storage.*" --tests "com.example.lms.service.AttachmentService*" --tests "com.example.lms.conversation.archive.*" --tests "com.example.lms.api.N8nWebhookController*" --tests "com.example.lms.jobs.*" --no-daemon
  ```

- [ ] **Step 2: Compile active boundaries**

  ```powershell
  .\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test --no-daemon
  ```

- [ ] **Step 3: Inspect integrity**

  Run exact-target `git diff --check`, SHA-256 postimages, count-only secret scan, and final W1 diff review. Verify no raw key, body, attachment content, or filesystem path was added to traces.

- [ ] **Step 4: Update the master ledger and release leases**

  Replace every W1 `OPEN` row with a valid terminal state, include one unblock action for any HOLD, and release each source-edit lease in `finally`. Do not stage or commit.
