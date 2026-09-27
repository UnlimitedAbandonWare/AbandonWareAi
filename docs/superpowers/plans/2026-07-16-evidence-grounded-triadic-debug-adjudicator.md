# Evidence-Grounded Triadic Debug Adjudicator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a disabled-by-default, admin-triggered SUPPORT/FALSIFY/HOLD adjudicator that classifies a redacted debug patch candidate as APPLY, HOLD, or REJECT.

**Architecture:** Existing dual sampling supplies SUPPORT and FALSIFY. A debug-specific `EnsembleJudgeService` method is the neutral HOLD role, and pure code owns the final threshold decision. `DebugCopilotService` bridges the existing event store to a separate admin diagnostics controller; the normal request path never starts these model calls.

**Tech Stack:** Java 17, Spring Boot 3, LangChain4j 1.0.1, JUnit 5, Mockito, Thymeleaf/vanilla JavaScript, Gradle wrapper.

## Global Constraints

- Patch only `main/java`, `main/resources`, and `src/test/java` active roots.
- Preserve every `dev.langchain4j:*` dependency at exactly `1.0.1`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)`.
- Do not revert unrelated dirty worktree changes.
- Do not persist or render raw prompts, raw queries, dossier text, secrets, headers, cookies, or full errors.
- Keep Browser, Computer, and Supabase as separate demand-driven evidence lanes.
- Do not commit or stage from the dirty canonical root unless the user explicitly asks.

---

### Task 1: RED decision and neutral-judge contracts

**Files:**
- Create: `src/test/java/com/example/lms/ensemble/EvidenceGroundedTriadicDebugAdjudicatorTest.java`
- Create: `src/test/java/com/example/lms/ensemble/TriadicDebugJudgeServiceTest.java`

**Interfaces:**
- Consumes: existing `sampleDualHypotheses(PromptContext,String)` and the future `judgeDebugPatch(List<SampledCandidate>,PromptContext,String)`.
- Produces: an executable decision table for `APPLY`, `HOLD`, and `REJECT` plus strict fail-soft neutral-vote behavior.

- [ ] Write tests that require zero calls while disabled or with fewer than two fingerprints.
- [ ] Write tests that require `APPLY` only for a SUPPORT vote, `SUFFICIENT` support grounding `>=0.70`, and a support-minus-falsify gap `>=0.05`.
- [ ] Write the symmetric `REJECT` tests and HOLD tests for exact failure reasons, invalid IDs, blank output, exception, incomplete roles, and a gap below `0.05`.
- [ ] Run `\.\gradlew.bat test --tests "com.example.lms.ensemble.EvidenceGroundedTriadicDebugAdjudicatorTest" --tests "com.example.lms.ensemble.TriadicDebugJudgeServiceTest" --no-daemon --project-cache-dir $pcd` and confirm compilation fails because the new production API does not exist.

### Task 2: Minimal triadic core

**Files:**
- Create: `main/java/com/example/lms/ensemble/EvidenceGroundedTriadicDebugAdjudicator.java`
- Modify: `main/java/com/example/lms/ensemble/EnsembleJudgeService.java`

**Interfaces:**
- `Adjudication adjudicate(List<Map<String,Object>> fingerprints, String rid)` returns a safe immutable result.
- `Adjudication latest()` returns the last safe snapshot.
- `DebugPatchVote judgeDebugPatch(List<SampledCandidate> candidates, PromptContext ctx, String rid)` returns a strict neutral vote and never raw model text.

- [ ] Add the disabled-by-default property `debug.copilot.triadic.enabled` with environment fallback `DEBUG_COPILOT_TRIADIC_ENABLED:false`.
- [ ] Normalize at most six fingerprints into safe `RagEvidenceMetadata`; filter the adjudicator's own event fingerprint and reject fewer than two rows.
- [ ] Call existing dual sampling once, validate exactly one SUPPORT and one FALSIFY result, then call the new neutral judge method once.
- [ ] Parse only `ROLE`, `DECISION`, `CONFIDENCE`, `DECISIVE EVIDENCE IDS`, and `REASON CODE`; require known matrix IDs and no extra lines.
- [ ] Apply the code threshold table and trace only hashes, counts, reason codes, decisions, and numeric scores.
- [ ] Rerun the two Task 1 tests until GREEN.

### Task 3: DebugCopilot and diagnostics surface

**Files:**
- Modify: `main/java/com/example/lms/service/trace/DebugCopilotService.java`
- Create: `main/java/com/example/lms/api/TriadicDebugAdjudicationController.java`
- Modify: `main/resources/templates/debug-events.html`
- Create: `src/test/java/com/example/lms/api/TriadicDebugAdjudicationControllerTest.java`
- Create: `src/test/java/com/example/lms/web/TriadicDebugAdjudicationPageContractTest.java`

**Interfaces:**
- `DebugCopilotService.adjudicateLatestPatchCandidate()` runs only from the explicit admin POST.
- `DebugCopilotService.latestTriadicAdjudication()` is read-only.
- `GET /api/diagnostics/debug/triadic-adjudication` returns the latest safe map.
- `POST /api/diagnostics/debug/triadic-adjudication` triggers one bounded adjudication; existing security requires `ROLE_ADMIN` for diagnostics POST requests.

- [ ] Add RED controller and template tests for both endpoints, `btnTriadicAdjudicate`, decision/reason/score fields, and forbidden raw fields.
- [ ] Keep existing constructors compatible while adding Spring dependencies for the adjudicator and `DebugEventStore`.
- [ ] Emit one `ORCHESTRATION` debug event with the safe result; do not call adjudication from `maybeEnrichTrace()`.
- [ ] Add GET refresh and explicit POST button behavior to the existing page using `textContent` and `JSON.stringify`, never `innerHTML` for model-derived data.
- [ ] Run the controller, page, DebugCopilot, DebugEvent redaction, and existing ensemble tests.

### Task 4: Desktop proof and external evidence

**Files:**
- Verify only; no new source file unless a focused RED proves a missing seam.

**Interfaces:**
- Produces current build, runtime, UI, and external-lane evidence for the final report.

- [ ] Run `\.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes bootJar -x test --no-daemon --project-cache-dir $pcd`.
- [ ] Run a count-only secret scan over the changed files.
- [ ] Start the built Desktop artifact on isolated ports and verify HTTP responses for `/api/diagnostics/debug/triadic-adjudication` and `/admin/debug-events`.
- [ ] Inspect the page with Browser when the plugin connection works; otherwise retain `browser-smoke-missing` without substituting another browser surface.
- [ ] Use Computer only for count-only visible-window evidence and keep Supabase read-only with `mutationAllowed=false` until project scope and auth are proven.
- [ ] Request an independent final delta review, rerun affected tests after any accepted fix, and report remaining evidence gaps.

