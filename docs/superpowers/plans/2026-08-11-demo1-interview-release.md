# demo-1 Interview Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to execute this plan task-by-task. Every application-source write first uses `demo1-source-edit-three-way-preflight`; explicit Notebook writes under `Y:\` then use `demo1-macsrc-smb-direct-patch`.

**Goal:** Deliver a Desktop-verifiable, offline-replay-first AI/backend interview release by 2026-10-15.

**Architecture:** Preserve `ChatWorkflow` as the public coordinator and `PromptBuilder.build(PromptContext)` as the final prompt boundary. Establish a pinned green baseline before adding provider proof, subsystem matrices, or extracting evidence, model-attempt, interaction-policy, and answer-finalization collaborators.

**Tech Stack:** Java 17, Gradle 8.7, Spring Boot 3.3.4, LangChain4j 1.0.1, JUnit 5, PowerShell, Desktop-local Ollama plus optional live providers.

## Global Constraints

- Public REST/SSE/DTO, DB, credential, secret-flow, and environment-variable-name changes are forbidden.
- Root active owners are `main/java` and `main/resources`; `:app` owners are `app/src/main/java_clean` and `app/src/main/resources`.
- Keep all `dev.langchain4j` dependencies exactly on `1.0.1`.
- Keep final RAG prompt construction on `PromptBuilder.build(PromptContext)`.
- Optional providers fail soft with explicit redacted `disabledReason` and no outbound call for missing, blank, dummy, `test`, `changeme`, `sk-local`, or unresolved placeholder credentials.
- Notebook direct source writes require verified `Y:\` identity, lease, preimage/CAS, declared targets, focused verification, postimage hashes, and rollback evidence.
- Desktop owns final build, runtime, UI, and provider-lineage proof.
- Do not commit, push, deploy, mutate DBs, or send external messages without separate authorization.

---

### Task 1: Establish G0 Verification Toolchain and Reproduce the Baseline Failure

**Files:**
- Read: `build.gradle.kts`, `app/build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`
- Test: `src/test/java/com/example/lms/web/ChatFrontendSecurityTest.java`

- [ ] Verify mapped-share identity, branch, index lock, PatchDrop queue, source leases, and dirty-target state.
- [ ] Use a checksum-verified Java 17 ZIP in a user-local tool directory; set `JAVA_HOME` only in the current verification process.
- [ ] Run the three named `ChatFrontendSecurityTest` methods with notebook-local Gradle caches and split build outputs.
- [ ] Record the exact failing assertions and classify source regression versus stale expectation.

### Task 2: Repair the Stale Frontend Contract Assertions

**Files:**
- Modify: `src/test/java/com/example/lms/web/ChatFrontendSecurityTest.java`
- Read only: `main/resources/static/js/chat.js`, `main/resources/templates/chat-ui.html`

- [ ] Confirm RED for raw event-ID expectation, brittle whole-finally-block expectation, and outdated session-mode accessibility label.
- [ ] Prepare and verify a guarded write declaring only the test file and the narrow test watch root.
- [ ] Update expectations to require redacted event IDs, the array-driven score-detail renderer and its `signalLabel` boundary, heartbeat cleanup plus session-selection synchronization, and the current diagnostics accessibility label.
- [ ] Run the three focused methods and the complete `ChatFrontendSecurityTest` class; record GREEN evidence and complete or abort the guarded session.

### Task 3: Re-establish the Pinned Full Baseline

**Files:**
- Verify only: active root and `:app` source sets

- [ ] Run `test --rerun-tasks --fail-fast`, then a normal full `test` with isolated Notebook caches.
- [ ] Run `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, `compileJava`, `:app:classes`, and `bootJar`.
- [ ] If class-output errors occur, run `scripts/verify_full_test_refresh.ps1`; do not use it to mask assertion failures.
- [ ] Require Desktop to repeat the full tests and `scripts/verify_control_plane_topology.ps1` at the same pinned revision before G0 is complete.

### Task 4: Build Provider Smoke, Replay, and Request-Lineage Evidence

**Files:**
- Reuse: `configs/models.manifest.yaml`, `main/resources/configs/cloud-models.manifest.yaml`
- Reuse: `docs/superpowers/plans/2026-07-29-request-provider-proof-hybrid.md`
- Modify only after a fresh source-edit preflight: existing provider seams and their focused tests

- [ ] Enumerate every active adapter at the pinned revision; count mismatch from the expected approximately 15 adapters is HOLD.
- [ ] Add sanitized offline replay fixtures before implementation changes and verify provider-disabled, timeout, rate-limit, empty, and after-filter-starvation behavior.
- [ ] Live-smoke all active adapters and run representative OpenAI, Ollama, Brave, and Naver RAG E2E flows.
- [ ] Emit `awx.request-provider-proof.v1` rows without raw prompts, options, responses, requests, or credentials; missing exact runtime rows keeps `runtimeLineageVerdict=HOLD`.

### Task 5: Verify S01-S08 Individually and in Allowed Combinations

- [ ] Run `demo1-ablation-harmony-tracker` before source changes and obtain source-backed owners and required trace keys.
- [ ] Write failing tests for each individual subsystem and each allowed or explicitly blocked combination.
- [ ] Apply the smallest owner-local fixes using `demo1-subsystem-patch-directive`; do not create parallel implementations.
- [ ] Run the focused matrix, cross-subsystem guard, source-set hygiene, LangChain4j purity, and full baseline.

### Task 6: Extract `ChatEvidencePipeline`

**Files:**
- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`
- Create: `main/java/com/example/lms/service/chat/workflow/ChatEvidencePipeline.java`
- Test: focused characterization tests referencing evidence merge, detour retry, citations, and official-source filters

- [ ] Write and run characterization tests before moving behavior.
- [ ] Move evidence merge/retry/reference behavior without changing public methods, trace keys, or prompt construction.
- [ ] Run focused parity, compile, and full tests.

### Task 7: Extract `ChatModelAttemptExecutor`

**Files:**
- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`
- Create: `main/java/com/example/lms/service/chat/workflow/ChatModelAttemptExecutor.java`
- Test: retry, endpoint compatibility, timeout, fail-soft, usage, and lineage characterization tests

- [ ] Write RED characterization for logical calls, physical attempts, retry ordering, and provider-disabled paths.
- [ ] Move model-attempt behavior while preserving current provider seams and `awx.request-provider-proof.v1` ordering.
- [ ] Run focused parity, compile, and full tests.

### Task 8: Extract Interaction Policy and Answer Finalization

**Files:**
- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`
- Create: `main/java/com/example/lms/service/chat/workflow/ChatInteractionPolicy.java`
- Create: `main/java/com/example/lms/service/chat/workflow/ChatAnswerFinalizer.java`

- [ ] Characterize direct-literal, recent-history, mode-status, external-proof, verification, rescue, reinforcement, and final-result behavior.
- [ ] Extract the two responsibilities while retaining all existing public and static compatibility entrypoints on `ChatWorkflow`.
- [ ] Prove response, trace, cancellation, non-web boot, and provider-attempt parity.

### Task 9: Package and Prove the Interview Release

**Files:**
- Create: `docs/interview/demo-1-architecture.md`
- Create: `docs/interview/demo-1-demo-runbook.md`
- Create: `docs/interview/demo-1-known-limits.md`

- [ ] Make offline replay the default demo and live provider use an optional, credential-gated lane.
- [ ] Run Desktop full tests, topology verification, local UI/SSE/cancel smoke, provider matrix, S01-S08 matrix, and count-only secret scan.
- [ ] Record a Desktop-local demo video outside the repository and reference it from the runbook.
- [ ] Keep any unproved provider/runtime claim as `evidence_needed`; do not claim completion without fresh Desktop evidence.
