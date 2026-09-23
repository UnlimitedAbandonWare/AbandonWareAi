# Demo-1 W7 Public UI and Scorecard Truthfulness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close IDs 67-75 and 86-88 by projecting a minimal cached public heartbeat, making score artifacts side-effect explicit, requiring structured evidence for correctness points, scoring real silent/large-file debt, and proving the status rail at real browser geometry.

**Architecture:** Keep broad internal diagnostic collection private. `/ui-heartbeat` returns a small allowlisted projection from a 30-second bounded single-flight cache. `source_health_scorecard.py` writes only the requested output unless an explicit canonical-write flag is present. Java `ScoringRunner` consumes structured verification evidence for correctness categories; source-string presence cannot earn those points. CSS changes remain confined to the existing status-rail layout.

**Tech Stack:** Java 17, Spring MVC, Python 3 standard library, JUnit 5, Python `unittest`, Node contract scripts, in-app Browser geometry inspection, Gradle Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-25-demo1-100-issue-safe-patch-design.md`

## Global Constraints

- Public heartbeat exposes only user-actionable bands, fixed reason codes, bounded age, and next action. It exposes no provider/model/route/credential/environment/MCP/project/file/path/internal-topology detail.
- Cache keys and values remain process-local, bounded to one current snapshot, and use monotonic expiration. Concurrent misses coalesce.
- `--output X` writes only `X`. Canonical artifact mutation requires a separate explicit flag and is not exercised without authority.
- Missing or malformed required evidence earns zero for the affected category and emits `metric-input-invalid`; it never receives a default passing value.
- Source text, class names, and keywords are discovery hints only, never correctness proof.
- Browser proof is fresh for the current CSS/JAR and reports measured geometry. Static source tests alone do not close ID 86.
- Each production source cohort requires stable three-way source-edit preflight and immediate preimage verification. Python/test-only changes use their ordinary focused preimage/diff checks.

## Coverage

| IDs | Task | Active owner |
|---|---|---|
| 67, 68 | 1 | `ChatUiHeartbeatPayload`, `ChatUiHeartbeatController`, `ChatUiCoreHeartbeatProbe` |
| 69 | 2 | `scripts/source_health_scorecard.py` |
| 71-75 | 3 | `ScoringRunner` structured local-artifact contract |
| 70 | 3a | Python quantitative-input fail-closed contract |
| 87, 88 | 4 | `ScoringRunner` debt metrics |
| 86 | 5 | `chat-ui.html`, `chat-style.css`, browser geometry |

---

### Task 0: Freeze public/private surfaces and score provenance

**Files:** W7 targets, focused tests, current score artifacts, UI DOM/CSS/JS, and W7 ledger rows.

**Interfaces:**

- Consumes: current `/ui-heartbeat` JSON keys, file/DB collaborator counts, score inputs/outputs, and browser viewport contract.
- Produces: five separately gated cohorts and a field-level public allowlist.

- [x] **Step 1:** Refresh branch/HEAD/source sets/index lock/worktrees/PatchDrop/leases/ports 8080 and 8081, plus target hashes. Do not stop unrelated port owners.
- [x] **Step 2:** Capture only the set of current heartbeat field names and value types; do not retain raw values. Classify each field `PUBLIC_ACTIONABLE` or `PRIVATE_INTERNAL`.
- [x] **Step 3:** Record every current score category, maximum, input source, and whether it is structured execution evidence or source-string inference.
- [x] **Step 4:** Run baseline heartbeat payload/probe, ScoringRunner, Python scorecard, frontend security, and Node UI contract tests.
- [ ] **Step 5:** Freeze stable three-way source-edit snapshots for the heartbeat and CSS cohorts only if production edits are required.

---

### Task 1: Project and cache a minimal public heartbeat

**Files:**

- Modify: `main/java/com/example/lms/web/ChatUiHeartbeatPayload.java`.
- Modify: `main/java/com/example/lms/web/ChatUiHeartbeatController.java`.
- Modify if needed for cost isolation: `main/java/com/example/lms/web/ChatUiCoreHeartbeatProbe.java`.
- Create only if the controller cannot own the state cleanly: `main/java/com/example/lms/web/ChatUiHeartbeatSnapshotCache.java`.
- Modify: `src/test/java/com/example/lms/web/ChatUiHeartbeatPayloadTest.java`.
- Modify: `src/chatUiTest/java/com/example/lms/web/ChatUiCoreHeartbeatProbeFocusedTest.java`.
- Create or modify: `src/test/java/com/example/lms/web/ChatUiHeartbeatControllerTest.java`.

**Interfaces:**

- Consumes: private internal snapshot, 30-second TTL, monotonic clock, concurrent public polls.
- Produces: immutable public payload with `statusBand`, `reasonCode`, `nextAction`, `ageMs`, and no internal topology.

- [x] **Step 1: Add a forbidden-key/value RED contract**

  ```java
  @Test
  void publicHeartbeatOmitsInternalProviderAndTopologyFacts() {
      Map<String, Object> payload = ChatUiHeartbeatPayload.from(privateFixture());
      String json = objectMapper.writeValueAsString(payload);
      assertThat(flattenedKeys(payload)).doesNotContain(
          "provider", "providerStatus", "providerRuntime", "route", "model",
          "credential", "environment", "mcp", "projectRef", "path", "host");
      assertThat(json).doesNotContain("PRIVATE_SENTINEL_67");
      assertThat(payload).containsKeys("statusBand", "reasonCode", "nextAction");
  }
  ```

- [x] **Step 2: Add cache/single-flight RED**

  ```java
  @Test
  void repeatedAndConcurrentPollsShareOneThirtySecondSnapshot() throws Exception {
      callHeartbeatConcurrently(20);
      assertThat(probeInvocationCount()).isEqualTo(1);
      advanceMonotonicClock(Duration.ofSeconds(29));
      heartbeat();
      assertThat(probeInvocationCount()).isEqualTo(1);
      advanceMonotonicClock(Duration.ofSeconds(2));
      heartbeat();
      assertThat(probeInvocationCount()).isEqualTo(2);
  }
  ```

  Count representative filesystem/DB collaborators as well as probe calls.

- [x] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.web.ChatUiHeartbeatPayloadTest" --tests "com.example.lms.web.ChatUiHeartbeatControllerTest" --no-daemon
  .\gradlew.bat chatUiTest --tests "com.example.lms.web.ChatUiCoreHeartbeatProbeFocusedTest" --no-daemon
  ```

- [x] **Step 4: Implement allowlist projection and one-entry cache**

  Project fixed public bands/reasons/actions only. Cache the already-projected immutable payload for 30 seconds using an injected monotonic clock and atomic/synchronized single-flight refresh. On refresh failure, serve a fixed degraded projection; do not leak the exception body or stale private snapshot.

- [x] **Step 5:** Re-run absent-provider, collector exception, concurrent miss, cache hit, TTL expiry, and payload-serialization cases.

---

### Task 2: Make Python scorecard output writes explicit and singular

**Files:**

- Modify: `scripts/source_health_scorecard.py`.
- Modify: `scripts/test_source_health_scorecard.py`.

**Interfaces:**

- Consumes: `--root`, `--output`, and a new explicit canonical-output authorization flag.
- Produces: exactly the declared output path by default, with optional separately authorized canonical write.

- [x] **Step 1: Replace the old dual-write expectation with RED side-effect tests**

  ```python
  def test_custom_output_writes_only_custom_path(self):
      requested = self.root / "tmp" / "score.json"
      canonical = self.root / DEFAULT_OUTPUT
      run_scorecard("--root", self.root, "--output", requested)
      self.assertTrue(requested.is_file())
      self.assertFalse(canonical.exists())

  def test_canonical_write_requires_explicit_flag(self):
      run_scorecard("--root", self.root, "--output", requested,
                    "--write-canonical-output")
      self.assertTrue((self.root / DEFAULT_OUTPUT).is_file())
  ```

- [x] **Step 2: Run focused RED**

  ```powershell
  python -m unittest scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_custom_output_writes_only_custom_path scripts.test_source_health_scorecard.SourceHealthScorecardTest.test_canonical_write_requires_explicit_flag
  ```

- [x] **Step 3: Remove implicit canonical write**

  Add a clearly named `store_true` flag. Write the canonical file only when it is present; still avoid duplicate writes when requested output already equals canonical. Keep JSON schema and stdout summary stable.

- [x] **Step 4:** Re-run the entire Python scorecard test module and assert changed paths are limited to the temporary test root.

---

### Task 3: Require structured local verification artifacts for correctness scores

**Files:**

- Modify: `main/java/com/example/lms/tools/ScoringRunner.java`.
- Modify: `src/test/java/com/example/lms/tools/ScoringRunnerTest.java`.
- Create only if a nested record would obscure the parser: `main/java/com/example/lms/tools/SourceScoreEvidence.java`.
- Create test fixtures under: `src/test/resources/scoring/`.

**Interfaces:**

- Consumes: `--root`, `--output`, optional `--evidence <json>`, structured task/test outcomes, artifact hashes, and active-call-path identifiers.
- Produces: points only for valid current artifacts, plus category reason `metric-input-invalid`, `evidence-needed`, `failed`, or `artifact-consistent-pass`.
- Truthfulness boundary: the runner performs no command execution and is not an attestation service. Every report states `Evidence Provenance: local-artifact-consistency` and `Execution Observed: false`; a coherent local `PASS` artifact proves only that the bounded artifact is current and internally consistent. Fresh Gradle command output remains separate completion evidence.

- [x] **Step 1: Define the minimal versioned evidence envelope in tests**

  ```json
  {
    "schemaVersion": 1,
    "sourceHead": "fixture-head",
    "checks": {
      "sourceSetVersionPurity": {"status": "PASS", "command": "checkSourceSetHygiene", "artifactSha256": "..."},
      "cfvmBehavior": {"status": "PASS", "test": "com.example.lms.cfvm.CfvmSnapshotRoundTripTest", "artifactSha256": "..."},
      "artPlateBehavior": {"status": "PASS", "test": "com.example.lms.artplate.NineArtPlateGateRolloutTest", "artifactSha256": "..."},
      "hypernovaBehavior": {"status": "PASS", "test": "com.nova.protocol.fusion.NovaNextFusionServiceTest", "artifactSha256": "..."},
      "piiRedaction": {"status": "PASS", "test": "com.example.lms.service.guard.PIISanitizerTest", "artifactSha256": "..."},
      "citationOwnership": {"status": "PASS", "test": "com.example.lms.service.rag.CitationGateEvidenceComposerBoundaryContractTest", "artifactSha256": "..."},
      "promptBoundary": {"status": "PASS", "test": "com.example.lms.prompt.PromptBuilderBoundaryTest", "artifactSha256": "..."}
    }
  }
  ```

  Fixtures use dummy hashes only. Production runs compare `sourceHead`/artifact identity with the current source image and bind each named test FQCN to its canonical source path plus at least one enabled JUnit test method; they do not trust an arbitrary stale PASS file and do not claim that a matching local artifact proves command execution.

- [x] **Step 2: Add fail-closed tests for IDs 71-75**

  Add one test each for absent file, malformed JSON, missing check, FAIL, stale source identity, keyword-only source, and valid structured PASS. Assert the affected category earns zero unless valid evidence exists, and assert that a valid local PASS is labelled `artifact-consistent-pass` with `Execution Observed: false`.

- [x] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.tools.ScoringRunnerTest" --no-daemon
  ```

- [x] **Step 4: Parse bounded evidence without a new dependency**

  Use Jackson already present in the repository or the existing JSON helper. Allow only known schema/version/check names/statuses and bounded strings. Source text may locate candidate owners for the report but must not alter correctness points.

- [x] **Step 5: Map each ID to required evidence**

  - ID 71: actual `checkSourceSetHygiene` and LangChain4j/version-purity task evidence.
  - ID 72: executable CFVM, ArtPlate, and HYPERNOVA behavior tests; all required checks must pass.
  - ID 73: redaction behavior test with private sentinel absent from outputs.
  - ID 74: test proving the response call path invokes the canonical citation owner.
  - ID 75: test proving `PromptBuilder.build(PromptContext)` and trace emission occur on the same active call path.

- [x] **Step 6:** Re-run ScoringRunner tests and render one report from invalid evidence and one from valid fixtures. Verify deterministic points/reasons, explicit non-attestation provenance, and no undeclared writes. Record actual Gradle execution output separately; do not derive an execution-success claim from the score report.

---

### Task 3a: Fail closed on missing quantitative maintainability inputs

**Files:**

- Modify: `scripts/source_health_scorecard.py`.
- Modify: `scripts/test_source_health_scorecard.py`.

- [x] **Step 1:** RED proves missing quantitative keys still award full maintainability points.
- [x] **Step 2:** Require a nonnegative integer `largeActiveFilesOver2000` and finite nonnegative numeric `activeJavaLocP95`.
- [x] **Step 3:** Missing/invalid inputs produce normalized and weighted zero with `metric-input-invalid`; exact zero remains valid.
- [x] **Step 4:** Run focused and full Python scorecard tests: 1/1 and 58/58 GREEN.

---

### Task 4: Penalize every proven silent catch and make large-file concentration affect total

**Files:**

- Modify: `main/java/com/example/lms/tools/ScoringRunner.java`.
- Modify: `src/test/java/com/example/lms/tools/ScoringRunnerTest.java`.
- Add fixtures under: `src/test/resources/scoring/silent-catches/` and `src/test/resources/scoring/large-files/`.

**Interfaces:**

- Consumes: active Java source, fixture-proven silent-catch variants, large active Java file count/threshold.
- Produces: deterministic debt counts and a non-perfect score whenever either proven debt class is present.

- [x] **Step 1: Add silent-catch fixture matrix**

  Cover empty body, comment-only body, ignored exception with no side effect, nested empty block, multiline formatting, and a legitimate fixed-reason trace/log/rethrow case. Require every real silent fixture to count and the legitimate case not to count.

- [x] **Step 2: Add score monotonicity tests**

  ```java
  @Test
  void anyRealSilentCatchPreventsPerfectSilentCatchPoints() {
      assertThat(score(fixtureWithOneSilentCatch()).category("silent-catch").points())
          .isLessThan(score(fixtureWithNoSilentCatch()).category("silent-catch").points());
  }

  @Test
  void largeFileConcentrationReducesTotal() {
      assertThat(score(fixtureWithLargeActiveFiles()).total())
          .isLessThan(score(equivalentSplitFixture()).total());
  }
  ```

- [x] **Step 3: Run focused RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.tools.ScoringRunnerTest" --no-daemon
  ```

- [x] **Step 4: Replace the exact-empty-only false green**

  Use a bounded lexical scanner or consume existing structured scanner evidence; do not add a parser dependency. Ignore braces in strings/comments, classify only fixture-proven silent bodies, and expose count plus reason. Make large-file count/concentration enter the score formula with a documented maximum penalty. Remove every unconditional path to 100 when debt count is positive.

- [x] **Step 5:** Re-run fixtures in different file orders and require byte-stable report output.

---

### Task 5: Remove the nested status-rail scrollbar and prove browser geometry

**Files:**

- Modify: `main/resources/static/css/chat-style.css`.
- Modify only if DOM grouping is required: `main/resources/templates/chat-ui.html`.
- Modify: `scripts/chat_ui_stream_contract_tests.js`.
- Modify: `src/test/java/com/example/lms/web/ChatFrontendSecurityTest.java`.
- Produce during execution: a redacted browser-proof JSON/screenshot under the existing approved `var/codex-smoke` evidence location, without staging or committing it.

**Interfaces:**

- Consumes: current status-rail DOM, desktop/mobile media queries, long model/status labels, browser viewport.
- Produces: wrapping or responsive collapse with no nested horizontal scrollbar and no viewport overflow.

- [x] **Step 1: Add static contract RED**

  Assert desktop `.status-rail` rules do not set `overflow-x:auto|scroll`, permit wrapping/collapse, and keep long status text breakable. Keep accessibility labels and keyboard reachability.

- [x] **Step 2: Run static RED**

  ```powershell
  node scripts\chat_ui_stream_contract_tests.js
  .\gradlew.bat test --tests "com.example.lms.web.ChatFrontendSecurityTest" --no-daemon
  ```

- [x] **Step 3: Apply the smallest CSS repair**

  Prefer `flex-wrap`, bounded child `min-width:0`, and overflow wrapping. Remove conflicting later/mobile overrides that reinstate nested horizontal scrolling. Do not redesign the page or change unrelated debug panels.

- [x] **Step 4: Run fresh localhost/browser proof**

  After read-only port ownership gates, boot the current artifact sequentially and use the Browser skill. At desktop `1365x768` and `1280x720`, and narrow `390x844`, measure:

  ```javascript
  ({
    viewportWidth: innerWidth,
    documentScrollWidth: document.documentElement.scrollWidth,
    railClientWidth: document.querySelector('.status-rail').clientWidth,
    railScrollWidth: document.querySelector('.status-rail').scrollWidth,
    railOverflowX: getComputedStyle(document.querySelector('.status-rail')).overflowX
  })
  ```

  Acceptance: `documentScrollWidth <= viewportWidth + 1`, `railScrollWidth <= railClientWidth + 1`, and rail overflow is not `auto`/`scroll`. Capture one screenshot per viewport and a count/hash-only console/network summary.

- [x] **Step 5:** Verify visible stream/cancel controls still render and operate locally. Do not claim model/provider success unless an actual provider attempt and semantic answer are observed.

---

### Task 6: Verify and close W7

**Files:** W7 diffs, generated non-staged proof, and terminal ledger.

- [x] **Step 1:** Run all W7 non-browser tests with isolated outputs.

  ```powershell
  python -m unittest scripts.test_source_health_scorecard
  .\gradlew.bat test --tests "com.example.lms.tools.ScoringRunnerTest" --tests "com.example.lms.web.ChatUiHeartbeatPayloadTest" --tests "com.example.lms.web.ChatUiHeartbeatControllerTest" --tests "com.example.lms.web.ChatFrontendSecurityTest" --no-daemon
  .\gradlew.bat chatUiTest --tests "com.example.lms.web.ChatUiCoreHeartbeatProbeFocusedTest" --no-daemon
  node scripts\chat_ui_stream_contract_tests.js
  ```

- [x] **Step 2:** Run the browser geometry proof from Task 5 against the current artifact only; record CSS/JAR hash and viewport measurements.
- [x] **Step 3:** Run `compileJava`, `processResources`, source-set hygiene, LangChain4j purity, and changed-file secret/privacy scans.
- [x] **Step 4:** Inspect the diff for private heartbeat keys, mutable cached maps, wall-clock TTL races, implicit canonical writes, stale PASS trust, source-keyword correctness points, and unrelated visual changes.
- [x] **Step 5:** Close all 12 W7 rows with `PATCHED`, `NO_PATCH_NEEDED`, or lane-local `HOLD` plus exact evidence and one next verification action.
- [x] **Step 6:** Record a no-commit checkpoint. Do not stage generated browser artifacts or commit/push/deploy without separate authority.
