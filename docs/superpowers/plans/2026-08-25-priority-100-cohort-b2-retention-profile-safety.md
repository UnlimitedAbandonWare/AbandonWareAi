# Priority-100 Cohort B2 Retention and Profile Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development`. Repository policy keeps all writes, integration, verification claims, and final judgment in the parent Codex session; subagents remain bounded read-only analysts/reviewers.

**Goal:** Close authoritative rank 13 by expiring in-memory attachment metadata and close ranks 56–58 by making explicit diagnostic/learning profiles safe by default, while recording rank 55 as evidence-backed `NO_PATCH_NEEDED`.

**Architecture:** `AttachmentService` keeps physical uploaded files unchanged but associates each in-memory attachment ID with a retention timestamp. One private metadata-mutation mutex publishes registration state atomically and serializes deletion, text caching, and session-link mutation, so expiry cannot leave untracked metadata or erase a concurrent fresh link. A scheduled five-minute janitor removes metadata older than a configurable 24-hour TTL through one lock-held metadata-deletion helper. Ultra and learning resource defaults become environment-gated safe values. One focused resource contract proves packaged ownership and all four configuration dispositions.

**Tech Stack:** Java 17, Spring Scheduling, Spring Environment, concurrent maps, JUnit 5, Mockito, Spring Test, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-24-priority-100-risk-burn-down-design.md`

## Scope and constraints

- Active owners are root `main/java`, `main/resources`, and `src/test/java`.
- This plan covers ranks 13 and 55–58 only. It does not modify chat session deletion, attachment authorization, physical storage files, databases, build ownership, or unrelated debug settings.
- `AttachmentService.java` is heavily user-dirty, including map declarations, `saveAll`, `delete`, and interaction-evidence code. Re-hash immediately before every hunk and preserve all unrelated lines.
- `application-ultra.properties`, `application-learning.yml`, and `RuntimeConfigShadowGuardTest.java` are clean at planning time; freshness must still be rechecked before mutation.
- Attachment policy is explicit: metadata TTL default `86_400_000` ms (24 hours), cleanup delay default `300_000` ms (5 minutes), both environment-configurable. A missing, zero, negative, or malformed TTL falls back to 24 hours so retention cannot be silently disabled.
- TTL is fixed from successful in-memory registration, not refreshed by reads. This prevents hot entries from becoming immortal.
- Expiry is inclusive at `age == TTL`; `age == TTL - 1` remains live. A future registration timestamp (clock rollback) remains live. Age calculation uses `Math.subtractExact(now, retainedAt)` after `now >= retainedAt`; positive overflow means the entry is expired, avoiding `retainedAt + ttl` overflow.
- Expiry deletes only in-memory `AttachmentDto`, extracted text, content digest, retention timestamp, and session links. It must not delete or overwrite uploaded files.
- All metadata mutations for an attachment are serialized by one private mutex. Registration publishes DTO, optional digest, and timestamp in one critical section. Expiry/public deletion removes every metadata map and session link in one critical section. `cacheExtractedText`, `saveAll(..., sessionId)`, and `attachToSession` may write only while the DTO is still live under that same mutex; post-expiry calls cannot recreate orphan text or links.
- The mutex also covers the session map's remove-empty and add-link operations. This intentionally trades a short, low-volume global metadata critical section for a simple invariant: cleanup of one ID cannot remove a session-list object after another ID was freshly linked to it. File storage, digest I/O, text extraction, and reads stay outside the mutex.
- A global hard capacity is not added because current API behavior does not define whether overflow should reject, partially accept, or evict live user data. Time-bounded retention is the approved “bound or expire” closure.
- Do not add raw session IDs, attachment IDs, paths, file names, text, digests, credentials, or exception messages to cleanup diagnostics. Count-only debug logging is allowed.
- Rank 55 receives no production patch: active root base config has no `wiretap=true`, and ultra explicitly sets server/client wiretap false. Add only a regression assertion.
- Rank 56 changes only stacktrace default. Rank 57 changes only the three audited binder/transaction/LangChain4j log defaults. Rank 58 changes only learning DDL default.
- Do not stage, commit, push, merge, deploy, call providers, mutate a live database, delete files, add dependencies, or clean worktrees/build outputs.

---

## File structure

### Production/resource files

- Modify `main/java/com/example/lms/service/AttachmentService.java`: retention timestamps, metadata-mutation mutex, lock-held deletion/link helpers, scheduled wrapper, and deterministic eviction helper.
- Modify `main/resources/application-ultra.properties`: safe environment-backed defaults for stacktrace and three verbose log categories.
- Modify `main/resources/application-learning.yml`: safe environment-backed default for `ddl-auto`.

### Test files

- Create `src/test/java/com/example/lms/service/AttachmentServiceRetentionTest.java`: expired/fresh state, exact cross-map cleanup, boundary/clock behavior, registration and read semantics, deterministic mutation races, scheduled reachability/defaults, no physical deletion, and privacy.
- Modify `src/test/java/com/example/lms/boot/RuntimeConfigShadowGuardTest.java`: rank 55 characterization and ranks 56–58 RED/GREEN resource contract.

### Existing boundary tests to retain

- `src/test/java/com/example/lms/service/AttachmentServiceSessionOwnershipTest.java`
- `src/test/java/com/example/lms/service/AttachmentServiceDocumentLimitTest.java`
- `src/test/java/com/example/lms/api/AttachmentControllerConversationArchiveTest.java`

---

### Task 0: Freeze ownership and run the source-edit preflight

- [x] **Step 1: Refresh repository gates**

  Record Java/Gradle versions, branch, HEAD, worktrees, exact target status/hashes, index lock, PatchDrop inventory, and source-edit leases. Re-run `checkSourceSetHygiene` and `checkLangchain4jVersionPurity` with Desktop split outputs.

- [x] **Step 2: Inspect exact dirty overlap**

  Confirm the planned AttachmentService insertion points can preserve the current user hunks: new import/constants/mutex/map; atomic registration beside `repo.put`; the `delete`, `deleteForSession`, `cacheExtractedText`, `saveAll(..., sessionId)`, `attachToSession`, and session-removal mutation seams; and new helpers beside existing property helpers. Freeze those expanded overlap ranges and their current hashes. If any current hunk changed, HOLD only rank 13. Resource targets remain independent.

- [x] **Step 3: Freeze the required three-way packets**

  Freeze at most 20 redacted evidence rows. Run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY` for `S_ATTACHMENT_TTL_RETENTION` and `S_PROFILE_SAFE_DEFAULTS`. Require identical `APPLY` and decisive evidence in A-B/B-A order; score must be at least 50.

- [x] **Step 4: Acquire the existing Desktop source-edit lease**

  Use topic `priority-100-cohort-b2-retention-profile-safety` and owner `codex-root-priority-100-b2`. A dirty-overlap HOLD for AttachmentService must not block the clean resource scenario.

---

### Task 1: Expire attachment metadata without deleting files

**Files:**

- Modify: `main/java/com/example/lms/service/AttachmentService.java`
- Create: `src/test/java/com/example/lms/service/AttachmentServiceRetentionTest.java`

- [x] **Step 1: Write focused retention RED tests**

  Use a real `AttachmentService` with a mocked `LocalFileStorageService`, a real or mocked `FileIngestionService`, a deterministic `MockEnvironment`, and reflection only to seed/inspect private state or install a latch-controlled test map. Cover:

  1. `evictExpired(now)` removes an expired ID from `repo`, `extractedTextById`, `contentDigestById`, retention timestamps, and its session list; the empty raw session key disappears.
  2. A fresh ID remains in all maps and is returned only by its owning session.
  3. `saveAll(files, session)` records a positive timestamp for every successfully stored DTO; a fresh eviction leaves it intact. A storage mock blocked before registration lets cleanup run first, then proves the completed registration contains both DTO and timestamp rather than an untracked DTO.
  4. Snapshot a retained timestamp, call `find`, `findBySession`, and `findIdsBySession`, and prove no read refreshes it.
  5. Boundary cases prove `age == TTL` expires, `age == TTL - 1` remains live, a future timestamp remains live after clock rollback, and an extreme positive TTL cannot overflow into premature expiry.
  6. A latch-controlled map blocks expiry inside the metadata mutex. Concurrent text caching and same-session linking start while expiry owns the mutex; after release they must observe the missing DTO and cannot recreate text/session state.
  7. A second deterministic race starts expiry of the only old ID in a session, then links a different fresh ID to the same session while expiry is blocked. The fresh link must survive after both operations finish.
  8. Cleanup never invokes any physical storage delete/write operation after seeded metadata eviction.
  9. `evictExpiredAttachments()` has `@Scheduled(fixedDelayString="${attachments.retention.cleanup-interval-ms:300000}")`; active `LmsApplication` retains `@EnableScheduling`; `AttachmentService` retains `@Service`; and the default TTL constant is 24 hours.
  10. Malformed, zero, or negative TTL configuration falls back to the positive 24-hour default.

  Use non-sensitive fixture IDs/session names and assert cleanup diagnostics never contain raw fixture IDs or session strings. Existing hashed per-item delete telemetry is permitted; the scheduled wrapper adds only count/reason diagnostics.

- [x] **Step 2: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.service.AttachmentServiceRetentionTest" --no-daemon
  ```

  Expected: compilation failure because the retention map and deterministic eviction API do not exist. A mock/storage fixture failure is not acceptable RED.

- [x] **Step 3: Add the smallest retention owner**

  Add:

  ```java
  static final long DEFAULT_RETENTION_TTL_MS = 86_400_000L;
  private final Object metadataMutationLock = new Object();
  private final Map<String, Long> retainedAtEpochMsById = new ConcurrentHashMap<>();
  ```

  Keep `storage.save(...)`, optional digest computation, and DTO construction outside the mutex. After those operations succeed, enter `synchronized (metadataMutationLock)`, capture `System.currentTimeMillis()`, and publish `repo`, optional digest, and retention timestamp before releasing the mutex. This critical section defines successful in-memory registration; no janitor can observe a partially published entry.

  Extract a private `deleteMetadataLocked(id)` that assumes the mutex is held and removes DTO, extracted text, digest, timestamp, and all session links. Make public `delete(id)` and `deleteForSession(id, sessionId)` perform their state checks and call that helper while holding the mutex, preserving their current trace outcomes. Run `cacheExtractedText`, `saveAll(..., sessionId)` association, and `attachToSession` writes under the same mutex and require `repo.containsKey(id)` before writing; the session-map compute/add and remove-empty sequence must occur entirely under the mutex.

  Add package-visible deterministic `int evictExpired(long nowEpochMs)`. It reads `attachments.retention.ttl-ms` through the existing `longProperty` helper and falls back to the default for nonpositive/malformed values. Under the metadata mutex, inspect each timestamp and delete expired state through `deleteMetadataLocked`. Determine expiry without addition overflow: return live when `nowEpochMs < retainedAt`; otherwise use `Math.subtractExact(nowEpochMs, retainedAt)` and compare `age >= ttl`, treating positive subtraction overflow as expired. Return only the eviction count.

  Add:

  ```java
  @Scheduled(fixedDelayString = "${attachments.retention.cleanup-interval-ms:300000}")
  public void evictExpiredAttachments() {
      int evicted = evictExpired(System.currentTimeMillis());
      if (evicted > 0) {
          log.debug("[AttachmentService] expired metadata evicted count={}", evicted);
      }
  }
  ```

  Do not change `LocalFileStorageService`, session authorization outcomes, controller endpoints, DTOs, or existing physical-file policy. The new mutex must never cover storage I/O, digest I/O, extraction, or read-only lookup methods.

- [x] **Step 4: Run focused and affected GREEN**

  Run the new retention test plus the existing session-ownership, document-limit, and attachment-controller archive tests. Inspect exact diff/postimage before Task 2.

---

### Task 2: Make packaged diagnostic and learning profiles safe by default

**Files:**

- Modify: `main/resources/application-ultra.properties`
- Modify: `main/resources/application-learning.yml`
- Modify: `src/test/java/com/example/lms/boot/RuntimeConfigShadowGuardTest.java`

- [x] **Step 1: Add one focused config-contract RED test**

  Read the active root `application.yml`, ultra properties, learning YAML, root build file, and app build file. Assert:

  - base YAML contains no active `wiretap: true`, while ultra contains exact server/client `wiretap=false` values;
  - stacktrace is exactly `${ULTRA_ERROR_INCLUDE_STACKTRACE:never}` and not unconditional `always`;
  - BasicBinder, transaction, and `dev.langchain4j` levels use environment placeholders whose defaults are `WARN`, with no unconditional `TRACE`;
  - learning DDL is exactly `${LEARNING_JPA_DDL_AUTO:validate}`, with no unconditional `create-drop`;
  - root resource ownership includes `main/resources`, and `:app` excludes its duplicate application config patterns.

- [x] **Step 2: Run RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.boot.RuntimeConfigShadowGuardTest" --no-daemon
  ```

  Expected: rank 55 assertions pass; ranks 56–58 assertions fail on current unsafe defaults.

- [x] **Step 3: Apply exactly five safe-default replacements**

  ```properties
  logging.level.org.hibernate.type.descriptor.sql.BasicBinder=${ULTRA_HIBERNATE_BINDER_LOG_LEVEL:WARN}
  logging.level.org.springframework.transaction=${ULTRA_TRANSACTION_LOG_LEVEL:WARN}
  logging.level.dev.langchain4j=${ULTRA_LANGCHAIN4J_LOG_LEVEL:WARN}
  server.error.include-stacktrace=${ULTRA_ERROR_INCLUDE_STACKTRACE:never}
  ```

  ```yaml
  ddl-auto: ${LEARNING_JPA_DDL_AUTO:validate}
  ```

  Do not change wiretap lines, SQL logging, error messages/binding flags, datasource, H2 console, or other profile knobs.

- [x] **Step 4: Run focused GREEN**

  Re-run `RuntimeConfigShadowGuardTest`, then run any existing profile/config boundary tests selected by name from the affected package. No profile boot or live database is required to prove packaged defaults.

---

### Task 3: Integrate, review, verify, and record dispositions

- [x] **Step 1: Run combined affected tests**

  Run the retention test, three attachment boundary tests, and runtime config shadow guard together. Record exact XML totals.

- [x] **Step 2: Request independent task and integrated review**

  Reviewers receive only contract, exact diff, RED/GREEN output, hashes, and privacy evidence. Require `SPEC`, `QUALITY`, Critical/Important findings, and `APPROVE|HOLD|REJECT`. Any Critical/Important issue gets a new focused RED before correction.

- [x] **Step 3: Broaden verification**

  Run Desktop-isolated `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes`, then a host-isolated full `test --rerun-tasks --fail-fast`, followed by `bootJar` because packaged resources changed.

- [x] **Step 4: Integrity and terminal ledger**

  Run exact-target `git diff --check`, postimage hashes, and count-only secret scan. Record rank 13, 56, 57, and 58 independently as `FIXED` if approved; rank 55 as `NO_PATCH_NEEDED` with the active resource evidence and regression. Release the lease in `finally`. Do not claim all of Cohort B or the 100-item goal complete unless every listed item has its own terminal disposition.
