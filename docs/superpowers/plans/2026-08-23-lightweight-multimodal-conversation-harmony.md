# Lightweight and Multimodal Conversation Harmony Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Repository policy keeps every source write, integration decision, and completion judgment in the parent Codex session.

**Goal:** Add a deterministic request-scoped conversation frame that makes lightweight candidates abstain during repair, support, and immediate-safety turns while preserving one primary final-answer authority and delivering validated images only to the primary vision route.

**Architecture:** `ConversationFrameResolver` classifies only the current user text and image-presence bit into an immutable `ConversationFrameV1`. The frame travels beside `InteractionEvidencePolicy.Decision` through `PromptContext`, gates optional candidate/expansion work in `ChatWorkflow` and `EnsembleFinalAnswerService`, and renders stance-specific instructions only at `PromptBuilder.build(PromptContext)`. Validated image input is converted to one primary `UserMessage` containing `TextContent` and `ImageContent`; auxiliary, verifier, memory, and trace paths receive no image bytes.

**Tech Stack:** Java 17, Spring Boot, Lombok, LangChain4j `1.0.1`, JUnit 5, AssertJ, Mockito, Gradle Wrapper, PowerShell.

**Spec:** `docs/superpowers/specs/2026-08-23-lightweight-multimodal-conversation-harmony-source-directive.md`

## Global Constraints

- Active backend source is root `main/java` and `main/resources`; active tests are under `src/test/java`.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)`.
- Do not change `InteractionEvidencePolicy` evidence/security semantics or `SensitiveTopicDetector` privacy semantics.
- Keep all `dev.langchain4j` dependencies exactly on `1.0.1`; add no production dependency.
- Preserve existing user-owned dirty hunks and require current preimage hashes immediately before every patch.
- Do not stage, commit, push, deploy, call a provider, or mutate DB/credentials without separate authority.
- Record only enums, booleans, counts, fixed reason codes, and hashes; never record raw text, base64, image bytes, prompts, candidates, or responses.
- Feature rollout is `OFF -> SHADOW -> ENFORCE`; `OFF` and `SHADOW` preserve legacy model calls and answers.
- Use `apply_patch` for edits and the repository source-edit lease for the mutation window.

---

### Task 0: Freeze the source-edit decision and ownership boundary

**Files:**
- Read: `AGENTS.md`
- Read: `build.gradle.kts:334-349`
- Read: `__patch_drop__/source_edit_session.ps1`
- Read: `__patch_drop__/janitor_inventory.ps1`
- Read: the spec and every declared production/test preimage

**Interfaces:**
- Consumes: user approval `지시서 승인—소스 구현` and the spec SHA-256.
- Produces: one redacted three-way `EvidenceSnapshot`, stable `APPLY`, and an active `desktop` source-edit lease.

- [ ] **Step 1: Verify Java, branch, sourceSets, lock, worktrees, PatchDrop, lease, and target status**

  Run `java -version`, `git branch --show-current`, `git rev-parse HEAD`, `git worktree list --porcelain`, `git status --short -- <declared targets>`, `Test-Path .git\index.lock`, `__patch_drop__\janitor_inventory.ps1`, and `__patch_drop__\source_edit_session.ps1 -Action status`.

- [ ] **Step 2: Freeze at most 20 redacted evidence rows**

  Include only path, SHA-256, Git status, sourceSet, boolean gate results, and exact verification commands. Hash the canonical JSON with SHA-256.

- [ ] **Step 3: Execute exactly the three logical preflight packets**

  Use scenario IDs `FRAME_STOP_RESPONSIBILITY`, `PRIMARY_AUTHORITY`, and `PRIMARY_IMAGE_DELIVERY` for both `POSITIVE_QUERY` and `NEGATIVE_QUERY`. Evaluate `NEUTRAL_QUERY` in A-B and B-A order; require the same decisive evidence IDs, score at least 50, and `APPLY` in both orders.

- [ ] **Step 4: Acquire the source-edit lease**

  Run:

  ```powershell
  & powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
    -Action begin -Role desktop -Root . `
    -Topic lightweight-multimodal-conversation-harmony `
    -OwnerId codex-root-conversation-harmony -TtlMinutes 180
  ```

  Stop without source writes unless the result is exit `0` and `source-edit-locks=1`.

### Task 1: Add the deterministic conversation frame

**Files:**
- Create: `main/java/com/example/lms/guard/ConversationFrameV1.java`
- Create: `main/java/com/example/lms/guard/ConversationFrameResolver.java`
- Create: `src/test/java/com/example/lms/guard/ConversationFrameResolverTest.java`

**Interfaces:**
- Consumes: current text, `multimodalInputPresent`, and `ConversationFrameV1.Mode`.
- Produces: `ConversationFrameV1 resolve(String userText, boolean multimodalInputPresent, Mode mode)`.

- [ ] **Step 1: Write the resolver RED tests**

  Use literal synthetic fixtures and assert these mappings:

  ```java
  assertThat(resolve("이제 그만하고 분석을 멈춰 줘", ENFORCE))
          .extracting(ConversationFrameV1::stance, ConversationFrameV1::reasonCode)
          .containsExactly(Stance.REPAIR, ReasonCode.EXPLICIT_STOP);
  assertThat(resolve("너무 지치고 혼자인 느낌이야", ENFORCE).stance())
          .isEqualTo(Stance.SUPPORTIVE_CHECK_IN);
  assertThat(resolve("지금 나를 해칠 것 같아", ENFORCE).stance())
          .isEqualTo(Stance.SAFETY_FIRST);
  assertThat(resolve("'죽고 싶다'를 영어로 번역해 줘", ENFORCE).stance())
          .isEqualTo(Stance.STANDARD);
  assertThat(resolve("멈추지 말고 계속 설명해", ENFORCE).stance())
          .isEqualTo(Stance.STANDARD);
  ```

  Also assert null/blank/format-character/over-4096 input is bounded and that priority is `SAFETY_FIRST > REPAIR > SUPPORTIVE_CHECK_IN > STANDARD`.

- [ ] **Step 2: Run the focused test and verify RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.guard.ConversationFrameResolverTest" `
    --no-daemon --project-cache-dir $projectCacheDir
  ```

  Expected failure: missing `ConversationFrameV1` or `ConversationFrameResolver`, not a fixture typo.

- [ ] **Step 3: Implement the minimal immutable frame**

  Define exact enums and methods:

  ```java
  public record ConversationFrameV1(
          Mode mode, Stance stance, LightweightRole lightweightRole,
          boolean multimodalInputPresent, boolean suppressOptionalRefinement,
          boolean suppressMemoryWrites, ReasonCode reasonCode) {
      public enum Mode { OFF, SHADOW, ENFORCE; public static Mode parse(String raw); }
      public enum Stance { STANDARD, REPAIR, SUPPORTIVE_CHECK_IN, SAFETY_FIRST }
      public enum LightweightRole { OBSERVE_ONLY, ABSTAIN }
      public enum ReasonCode { DEFAULT, EXPLICIT_STOP, ASSISTANT_BOUNDARY_COMPLAINT,
          DISTRESS_CHECK_IN, IMMEDIATE_SAFETY_SIGNAL }
      public static ConversationFrameV1 off(boolean multimodalInputPresent);
      public boolean enforcementActive();
      public boolean allowsDirectShortCircuit();
      public boolean allowsOptionalRefinement();
      public boolean allowsOptionalExpansion();
      public boolean suppressesMemoryWrites();
      public boolean shouldTrace();
  }
  ```

  The compact constructor derives `ABSTAIN` and all suppression flags only for `ENFORCE` plus a non-standard stance. The resolver uses Unicode NFKC, removes format characters, collapses whitespace, preserves bounded head/tail text, applies direct-safety negation/meta exclusions, and calls no model, search, DB, session store, provider, or clock.

- [ ] **Step 4: Run the focused test and verify GREEN**

  Re-run the Task 1 command and require exit `0`.

### Task 2: Propagate the frame through the canonical prompt boundary

**Files:**
- Modify: `main/java/com/example/lms/prompt/PromptContext.java`
- Modify: `main/java/com/example/lms/prompt/StandardPromptBuilder.java`
- Modify: `src/test/java/com/example/lms/prompt/StandardPromptBuilderInteractionEvidencePolicyTest.java`
- Test: `src/test/java/com/example/lms/guard/ConversationFrameResolverTest.java`

**Interfaces:**
- Consumes: `ConversationFrameV1` from Task 1.
- Produces: `PromptContext.conversationFrame()`, `Builder.conversationFrame(ConversationFrameV1)`, and stance-specific system instructions.

- [ ] **Step 1: Write RED tests for context propagation and prompt behavior**

  Assert builder null/default becomes `ConversationFrameV1.off(false)`, explicit frame survives `toBuilder()`, and `OFF`, `SHADOW`, and `STANDARD` add no conversation block. Assert `REPAIR` contains boundary acknowledgement plus analysis/relationship-evaluation suppression; `SUPPORTIVE_CHECK_IN` contains non-diagnostic reflection plus one check-in; `SAFETY_FIRST` contains a direct current-safety check and no hard-coded country number.

- [ ] **Step 2: Verify the focused tests fail for the missing context field/block**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.guard.ConversationFrameResolverTest" `
    --tests "com.example.lms.prompt.StandardPromptBuilderInteractionEvidencePolicyTest" `
    --no-daemon --project-cache-dir $projectCacheDir
  ```

- [ ] **Step 3: Add all six immutable context propagation points**

  Add the field, constructor normalization, accessor, `toBuilder()` copy, builder default, and fluent builder method. Do not modify the interaction policy field.

- [ ] **Step 4: Add `appendConversationFrameBlock` at the prompt seam**

  Call the helper immediately after `appendInteractionPolicyBlocks(sb, ctx)`. It emits nothing unless `frame.enforcementActive()` and `stance != STANDARD`; it explicitly overrides later generic analysis/decomposition shape without weakening evidence, citation, guard, or privacy requirements.

- [ ] **Step 5: Re-run Task 2 tests and require GREEN**

### Task 3: Gate lightweight candidates, direct fallbacks, and optional expansion

**Files:**
- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`
- Modify: `main/java/com/example/lms/ensemble/EnsembleFinalAnswerService.java`
- Create: `src/test/java/com/example/lms/service/ChatWorkflowConversationHarmonyContractTest.java`
- Modify: `src/test/java/com/example/lms/ensemble/EnsembleFinalAnswerServiceTest.java`

**Interfaces:**
- Consumes: one request-scoped `ConversationFrameV1`.
- Produces: primary-only non-standard path with auxiliary/refiner/expansion calls suppressed and memory denied.

- [ ] **Step 1: Write RED tests for model authority and call counts**

  In the ensemble test, enable sampling and pass an `ENFORCE + REPAIR` frame. Assert the result is empty, the sampling orchestrator has zero interactions, and bounded trace values are `disabledReason=conversation_stance`, `candidateCount=0`, `decisionAuthority=primary_model`, and `mutationAllowed=false`.

  In the workflow contract test, exercise package-visible pure gate helpers and assert `OFF|SHADOW` preserve short-circuit/refiner/expansion behavior while all three non-standard `ENFORCE` stances deny those optional paths and memory writes.

- [ ] **Step 2: Verify RED**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.service.ChatWorkflowConversationHarmonyContractTest" `
    --tests "com.example.lms.ensemble.EnsembleFinalAnswerServiceTest" `
    --no-daemon --project-cache-dir $projectCacheDir
  ```

- [ ] **Step 3: Resolve one frame and attach it once**

  Add final constructor-injected `ConversationFrameResolver`, property `@Value("${conversation.harmony.mode:off}")`, and compute the frame immediately after the existing interaction decision. Append its bounded breadcrumb, combine `interactionShortCircuitAllowed` with `frame.allowsDirectShortCircuit()`, and add `.conversationFrame(frame)` to the one `PromptContext.Builder`.

- [ ] **Step 4: Add defense-in-depth refiner gates**

  `ChatWorkflow` skips refiner entry when `!frame.allowsOptionalRefinement()`. `EnsembleFinalAnswerService.sampleCandidatesForRefinement` independently returns before feature/provider/citation gates when an enforced non-standard frame is present and writes only bounded values.

- [ ] **Step 5: Gate optional expansion and memory writes**

  Require `frame.allowsOptionalExpansion()` before `AnswerExpanderService`, projection/free-idea generation, merge, and final polish. Set memory denial to `interactionPolicyDecision.suppressMemoryWrites() || frame.suppressesMemoryWrites()`. Sanitizer, redaction, cancellation, request budget, and evidence fail-closed guards remain active.

- [ ] **Step 6: Re-run Task 3 tests and require GREEN**

### Task 4: Validate image input and deliver it only to the primary vision model

**Files:**
- Modify: `main/java/com/example/lms/dto/ChatRequestDto.java`
- Modify: `main/java/com/example/lms/api/PublicRequestBudgetGuard.java`
- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`
- Modify: `src/test/java/com/example/lms/api/PublicRequestBudgetGuardTest.java`
- Create: `src/test/java/com/example/lms/service/ChatWorkflowMultimodalPrimaryMessageTest.java`
- Test: `src/test/java/com/example/lms/service/rag/plan/PlanModelResolverTest.java`
- Test: `src/test/java/com/example/lms/llm/ModelRuntimeRequestTimelineTest.java`
- Test: `src/test/java/com/example/lms/manifest/LocalModelConfigYamlTest.java`

**Interfaces:**
- Consumes: raw base64, optional `imageMediaType`, validated active `llm.vision.model`, and the request frame.
- Produces: one primary `UserMessage` with text plus image content, or a fixed pre-provider error.

- [ ] **Step 1: Write DTO/budget RED cases**

  Add `imageMediaType` and assert allowlist behavior for `image/png`, `image/jpeg`, and `image/webp`. Assert null/blank media type normalizes to PNG, malformed base64 returns `chat_image_base64_invalid`, decoded-empty returns `chat_image_empty`, unsupported MIME returns `chat_image_media_type_unsupported`, a complete `data:` URI returns `chat_image_data_uri_not_allowed`, and every rejected case makes no model call.

- [ ] **Step 2: Write primary-message RED cases**

  Add a package-visible pure helper contract:

  ```java
  static UserMessage primaryUserMessage(
          String finalQuery, ChatRequestDto request, ConversationFrameV1 frame)
  ```

  Assert text-only and `OFF|SHADOW` produce one text content item; valid image plus `ENFORCE` produces exactly one `TextContent` and one `ImageContent`; the LangChain4j `1.0.1` image contains the validated `base64Data` and MIME with no URI; no auxiliary API accepts an image argument.

- [ ] **Step 3: Verify RED**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.api.PublicRequestBudgetGuardTest" `
    --tests "com.example.lms.service.ChatWorkflowMultimodalPrimaryMessageTest" `
    --tests "com.example.lms.service.rag.plan.PlanModelResolverTest" `
    --tests "com.example.lms.llm.ModelRuntimeRequestTimelineTest" `
    --tests "com.example.lms.manifest.LocalModelConfigYamlTest" `
    --no-daemon --project-cache-dir $projectCacheDir
  ```

- [ ] **Step 4: Implement input validation and normalization**

  Decode only for validation and retain no decoded bytes. Trace only fixed reason code, encoded character count, decoded byte count, and `queryRedacted=true`. Never log the payload or data URI.

- [ ] **Step 5: Select the existing vision route before the primary call**

  Only for valid image plus `ENFORCE`, require nonblank resolved `llm.vision.model`, reject unresolved `${...}` or residual `llmrouter.*`, override the effective requested model with `planModelResolver.resolveRequestedModel("llmrouter.vision")`, and return exactly:

  ```text
  evidence_needed: vision_model_unavailable / verify with PlanModelResolverTest and active llm profile
  ```

  before any provider call when the route cannot be proven. Do not silently route the image to a text model.

- [ ] **Step 6: Build the primary multimodal message**

  Replace only the final `UserMessage.from(finalQuery)` call with `primaryUserMessage(finalQuery, llmReq, frame)`. Keep all lightweight candidate, verifier, memory, trace, and postprocessor signatures image-free.

- [ ] **Step 7: Re-run Task 4 tests and require GREEN**

### Task 5: Add bounded conversation-frame telemetry

**Files:**
- Modify: `main/java/com/example/lms/telemetry/MlaBreadcrumb.java`
- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`
- Modify: `src/test/java/com/example/lms/telemetry/MlaBreadcrumbTest.java`

**Interfaces:**
- Consumes: `ConversationFrameV1` only.
- Produces: one deduplicated `conversation_frame` breadcrumb containing no reconstructive data.

- [ ] **Step 1: Write telemetry RED tests**

  Assert the row contains version, mode, stance, reason code, lightweight role, multimodal-present, refiner/expansion/memory suppression, `primaryAuthority=primary_model`, count fields, and `queryRedacted=true`. Assert it contains none of the synthetic user text, base64, MIME, prompt, candidate, final answer, session ID, or provider body.

- [ ] **Step 2: Verify RED**

  ```powershell
  .\gradlew.bat test --tests "com.example.lms.telemetry.MlaBreadcrumbTest" `
    --no-daemon --project-cache-dir $projectCacheDir
  ```

- [ ] **Step 3: Add the bounded append API and workflow call**

  Implement `appendConversationFrameTransition(ConversationFrameV1 frame)` with enum/boolean/count-only data, deduplicate against `mla.breadcrumb.step.conversation_frame`, and call it only when `frame.shouldTrace()`.

- [ ] **Step 4: Re-run Task 5 tests and require GREEN**

### Task 6: Verify the integrated patch and release the lease

**Files:**
- Verify: every exact path changed by Tasks 1–5
- Update: this plan’s checkboxes only after matching command evidence exists

**Interfaces:**
- Consumes: focused GREEN source tree.
- Produces: local source/build evidence, exact diff, postimage hashes, and either `patched_and_verified` or lane-local `HOLD`.

- [ ] **Step 1: Run the complete focused suite**

  ```powershell
  .\gradlew.bat test `
    --tests "com.example.lms.guard.ConversationFrameResolverTest" `
    --tests "com.example.lms.prompt.StandardPromptBuilderInteractionEvidencePolicyTest" `
    --tests "com.example.lms.service.ChatWorkflowConversationHarmonyContractTest" `
    --tests "com.example.lms.service.ChatWorkflowMultimodalPrimaryMessageTest" `
    --tests "com.example.lms.ensemble.EnsembleFinalAnswerServiceTest" `
    --tests "com.example.lms.api.PublicRequestBudgetGuardTest" `
    --tests "com.example.lms.telemetry.MlaBreadcrumbTest" `
    --tests "com.example.lms.service.rag.plan.PlanModelResolverTest" `
    --tests "com.example.lms.llm.ModelRuntimeRequestTimelineTest" `
    --tests "com.example.lms.manifest.LocalModelConfigYamlTest" `
    --no-daemon --project-cache-dir $projectCacheDir
  ```

- [ ] **Step 2: Run the cross-boundary build gates**

  ```powershell
  .\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava :app:classes `
    -x test --no-daemon --project-cache-dir $projectCacheDir
  .\gradlew.bat bootJar --no-daemon --project-cache-dir $projectCacheDir
  ```

- [ ] **Step 3: Inspect diff, privacy, and ownership evidence**

  Run `git diff --check -- <exact paths>`, a count-only active-root secret scan, postimage SHA-256, target `git status --short`, staged count, HEAD, index lock, lease state, and PatchDrop inventory. Confirm no attachment line, raw query, base64, data URI, credential, or provider body entered source telemetry or generated evidence.

- [ ] **Step 4: Classify runtime/provider evidence accurately**

  Source and mock-level success may be `patched_and_verified` while provider generation remains `wireAttemptCoverage=not_observed`. Do not call a provider or claim multimodal wire success without separate current authority and same-request attempt evidence.

- [ ] **Step 5: Release the source-edit lease in a `finally` path**

  ```powershell
  & powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\source_edit_session.ps1 `
    -Action end -Role desktop -Root . `
    -Topic lightweight-multimodal-conversation-harmony `
    -OwnerId codex-root-conversation-harmony
  ```

  Verify final `source-edit-locks=0`. Do not stage or commit without separate user authority.
