# Dual-Hypothesis Evidence Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development. Execute one RED/GREEN cycle at a time; do not parallel-edit the dirty Desktop checkout.

**Goal:** Replace the active three-call PromptContext refinement lane with two code-scored SUPPORT/FALSIFY references while preserving the legacy judge rollback path and primary final ownership.

**Architecture:** Add a pure evidence scorer over the existing `EnsembleEvidenceMatrix`, add a dual-hypothesis sampler entry point that reuses the current deadline/cancellation machinery, and route only `sampleCandidatesForRefinement(...)` through it. Carry score metadata in `SampledCandidate`; keep ChatWorkflow, PromptContext, and MLA source unchanged because their live boundaries already satisfy the contract.

**Tech Stack:** Java 17, Spring Boot 3.3.4, Gradle 8.7 wrapper, JUnit 5, Mockito, LangChain4j 1.0.1.

## Global Constraints

- Active source only: `main/java`, `main/resources`, `src/test/java`, `src/test/resources`, `app/src/main/java_clean`, `app/src/main/resources`.
- Every `dev.langchain4j:*` dependency remains exactly `1.0.1`.
- Final prompt assembly remains `PromptBuilder.build(PromptContext)`.
- No raw query, evidence snippet, secret, token, header, cookie, or environment dump in trace/output.
- No new SMB service, producer loop, agent broker, background daemon, DB mutation, or external proof requirement.
- Existing dirty changes are preserved; edits stay on the named files and exact seams.

---

### Task 1: Pure Candidate Evidence Scorer

**Files:**
- Create: `src/test/java/com/example/lms/ensemble/DualHypothesisEvidenceScorerTest.java`
- Create: `main/java/com/example/lms/ensemble/DualHypothesisEvidenceScorer.java`

**Interfaces:**
- Consumes: `EnsembleEvidenceMatrix` and a bounded directional dossier string.
- Produces: `DualHypothesisEvidenceScorer.Score` with evidence rate, source diversity, contradiction rate, grounding score, and evidence status.

- [ ] **Step 1: Write the failing tests** for two valid provenance groups, a well-formed unknown `ev1:*` ID, mixed supported/contradicted claims, and no valid evidence.
- [ ] **Step 2: Run** `gradlew.bat test --tests "com.example.lms.ensemble.DualHypothesisEvidenceScorerTest" ...`; expect compile failure because the scorer does not exist.
- [ ] **Step 3: Implement** a side-effect-free parser and the exact formula from the design. Clamp every component to `0..1`; return score `0` when no valid ID is present.
- [ ] **Step 4: Re-run the focused test** and require zero failures.

### Task 2: Candidate Metadata And Two-Worker Sampling

**Files:**
- Modify: `main/java/com/example/lms/ensemble/SampledCandidate.java`
- Modify: `main/java/com/example/lms/ensemble/DiverseSamplingOrchestrator.java`
- Modify: `src/test/java/com/example/lms/ensemble/DiverseSamplingOrchestratorTest.java`

**Interfaces:**
- Produces: `sampleDualHypotheses(PromptContext, String, Runnable)` returning ordered `support`, `falsify` candidates.
- Preserves: `sample(...)` returning the legacy cooperative/base-rate/opportunistic triad.

- [ ] **Step 1: Add a failing sampler test** that expects two model requests, node order `support,falsify`, profiles `0.85/0.90` and `0.00/0.40`, the redacted evidence matrix in both prompts, and distinct code-owned scores.
- [ ] **Step 2: Run only that test**; expect failure because `sampleDualHypotheses` and metadata accessors do not exist.
- [ ] **Step 3: Extend `SampledCandidate`** with enums `HypothesisDirection` and `EvidenceStatus`, score components, and a seven-argument compatibility constructor that maps legacy citation score to grounding score.
- [ ] **Step 4: Extract the existing worker loop** behind a private mode/spec method without changing legacy behavior. Add the two dual specs and validate/score dual output through the pure scorer.
- [ ] **Step 5: Re-run the new sampler test and the existing triad characterization test**; both must pass.

### Task 3: Active Refiner Routing, Tie Contract, And Prompt Metadata

**Files:**
- Modify: `main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java`
- Modify: `src/test/java/com/example/lms/ensemble/EnsembleFinalAnswerServiceTest.java`
- Modify: `main/java/com/example/lms/prompt/StandardPromptBuilder.java`
- Modify: `src/test/java/com/example/lms/prompt/StandardPromptBuilderEnsembleJudgeModeTest.java`

**Interfaces:**
- `sampleCandidatesForRefinement(...)` returns a complete safe dual pair and never calls `EnsembleJudgeService`.
- A score gap `< 0.05` records `ensemble.refiner.selectionDecision=underdetermined`; otherwise it records the higher-scoring direction. Both candidates are returned in either case.

- [ ] **Step 1: Change the existing refiner test to a RED pair contract** and add tie/non-tie assertions. Keep direct `tryGenerate()` triad tests unchanged.
- [ ] **Step 2: Run the service test method**; expect failure because the service still calls the triad sampler and triad validator.
- [ ] **Step 3: Route the refiner through `sampleDualHypotheses`**, validate exactly one SUPPORT and one FALSIFY candidate, use grounding score for safety, and record tie decision without dropping either reference.
- [ ] **Step 4: Add a failing prompt test** for direction, evidence status, evidence rate, diversity, contradiction rate, and grounding score in the existing escaped untrusted block.
- [ ] **Step 5: Render those bounded scalar fields** without adding raw evidence/query data, then re-run service and prompt tests.

### Task 4: Integration And Desktop Verification

**Files:**
- Verify only: `main/java/com/example/lms/service/ChatWorkflow.java`
- Verify only: `main/java/com/example/lms/telemetry/MlaBreadcrumb.java`
- Verify only: `src/test/java/com/example/lms/service/ChatWorkflowPostOrchestrationPromptBoundaryTest.java`
- Verify only: `src/test/java/com/example/lms/telemetry/MlaBreadcrumbTest.java`

- [ ] **Step 1: Run focused ensemble/scorer/prompt/call-path/MLA tests** with isolated Desktop Gradle caches.
- [ ] **Step 2: Run** `checkLangchain4jVersionPurity checkSourceSetHygiene compileJava -x test`.
- [ ] **Step 3: Run `:app:classes` and `bootJar -x test`** because the changed Java types cross prompt/service boundaries.
- [ ] **Step 4: Run** count-only secret scan, `git diff --check` on changed files, janitor CoreGuards, janitor inventory, completion audit, and goal status audit.
- [ ] **Step 5: Start the verified Desktop JAR only if backend/UI smoke is still decision-changing; use Browser DOM proof and Computer count-only supporting proof without submitting a chat message.
