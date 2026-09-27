# Evidence-Neutral Interaction Policy Implementation Plan

> **For Codex:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task.

**Goal:** Replace the partial single-mode interaction heuristic with a request-scoped, evidence-neutral five-axis policy, then prove prompt, guard, memory, trace, skill, and deterministic-audit contracts without disturbing unrelated dirty changes.

**Architecture:** Keep one pure policy owner under `com.example.lms.guard`. `ChatWorkflow` computes one decision from bounded observations and carries it through the existing `GuardContext`, `PromptContext`, canonical `PromptBuilder`, canonical `EvidenceAwareGuard`, and final memory gate. A single `off|shadow|enforce` property controls effects; style and security remain independent typed axes.

**Tech Stack:** Java 17, Spring Boot 3, JUnit 5, Gradle 8.7, PowerShell 5+, Codex personal skills.

---

### Task 1: Freeze RED production contracts

**Files:**
- Modify: `src/test/java/com/example/lms/guard/InteractionEvidencePolicyTest.java`
- Modify: `src/test/java/com/example/lms/prompt/StandardPromptBuilderInteractionEvidencePolicyTest.java`
- Add: `src/test/java/com/example/lms/service/guard/InteractionEvidencePolicyPropagationTest.java`
- Add: `src/test/java/com/example/lms/service/guard/EvidenceAwareGuardInteractionPolicyTest.java`
- Modify: `src/test/java/com/example/lms/service/ChatWorkflowInteractionEvidencePolicyContractTest.java`
- Modify: `src/test/java/com/example/lms/telemetry/MlaBreadcrumbTest.java`

**Steps:**
1. Encode the five independent axes, cooperation-style-only behavior, neutral profanity/disagreement, confirmed-proof rules, off/shadow/enforce semantics, and forbidden raw fields.
2. Prove `GuardContext.copy()` and `PromptContext.toBuilder()` preserve the same immutable decision.
3. Prove quarantine-before-strict-guard, clean-evidence continuation, insufficient-evidence fail-closed, enforcement-exception fail-closed, and memory suppression.
4. Run the focused tests and record the expected compile/test failure before production edits.

**RED command:**
```powershell
.\gradlew.bat test --tests "*InteractionEvidencePolicy*" --tests "*MlaBreadcrumbTest" --no-daemon --project-cache-dir "$env:USERPROFILE\.awx-gradle-project-cache\desktop"
```

### Task 2: Implement the pure typed policy

**Files:**
- Modify: `main/java/com/example/lms/guard/InteractionEvidencePolicy.java`

**Steps:**
1. Add `ResponseStyle`, `SecurityStance`, `EvidenceMode`, `MemoryWriteMode`, `FailureMode`, and `FeatureMode` enums.
2. Add immutable `InteractionObservation`, bounded `ManipulationFact`, proof/rule/surface enums, and a decision without raw query, evidence, identity, or session data.
3. Make ordinary style/tone facts neutral; only concrete proof facts activate defensive axes.
4. Gate all effects behind exact `enforce`; keep `shadow` compute-and-trace-only and `off` neutral/no-trace.

### Task 3: Preserve typed propagation and prompt separation

**Files:**
- Modify: `main/java/com/example/lms/service/guard/GuardContext.java`
- Modify: `main/java/com/example/lms/prompt/PromptContext.java`
- Modify: `main/java/com/example/lms/prompt/StandardPromptBuilder.java`

**Steps:**
1. Add neutral safe defaults and immutable decision accessors.
2. Preserve the exact decision through `GuardContext.copy()` and `PromptContext.toBuilder()`.
3. Render a collaborative presentation block only for enforced collaborative style.
4. Render a separate defensive evidence block only for enforced defensive stance, retaining the existing evidence protocol and `PromptBuilder.build(PromptContext)` boundary.

### Task 4: Enforce containment, strict guard, memory suppression, and redacted traces

**Files:**
- Modify: `main/java/com/example/lms/service/guard/EvidenceAwareGuard.java`
- Modify: `main/java/com/example/lms/service/ChatWorkflow.java`
- Modify: `main/java/com/example/lms/telemetry/MlaBreadcrumb.java`

**Steps:**
1. Add canonical-guard preparation that removes explicitly suspect evidence IDs before prompt/guard use and returns bounded containment status.
2. Continue with clean evidence under `VisionMode.STRICT`; block only for no clean evidence, containment failure, or enforcement exception.
3. Add `interaction.evidence-neutral.mode:off` as the only rollout property and compute the decision once per request.
4. Carry the decision into both contexts, disable shortcuts only under enforced defense, and suppress the existing final memory writer group.
5. Trace enums, counts, bounded rule identifiers, and containment status only; remove query hash/length from this policy trace.

### Task 5: Build the deterministic repository audit with TDD

**Files:**
- Add first: `scripts/audit_evidence_neutral_interaction_policy_tests.ps1`
- Add after RED: `scripts/audit_evidence_neutral_interaction_policy.ps1`

**Steps:**
1. Run the test harness while the implementation script is absent and capture the expected RED result.
2. Implement ENI001-ENI005 over active source only, with sorted relative paths and stable JSON.
3. Cover clean and violating fixtures, invalid root, missing files, internal error, deterministic ordering, byte-identical output, exit codes 0-3, and secret-sentinel non-disclosure.

### Task 6: Create and pressure-test the personal skill

**Files:**
- Generate: `C:/Users/nninn/.codex/skills/patching-evidence-neutral-interaction-policy/SKILL.md`
- Generate: `C:/Users/nninn/.codex/skills/patching-evidence-neutral-interaction-policy/agents/openai.yaml`

**Steps:**
1. Preserve the completed without-skill pressure baseline.
2. Run the installed `skill-creator` initializer with the attachment's exact interface metadata.
3. Reduce the generated skill to the two permitted files and teach only the observed gaps: independent axes, proof facts, canonical wiring, containment, memory suppression, trace redaction, and verification.
4. Run `quick_validate.py`, repeat the pressure scenarios with the skill, and reserve held-out cases for the third read-only agent.

### Task 7: GREEN, refactor, freeze, and held-out review

**Files:**
- Test: all files above

**Steps:**
1. Run focused Java tests, audit-tool tests twice, deterministic byte comparison, and `git diff --check`.
2. Run `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, `compileJava`, focused guard/prompt/workflow tests, and count-only secret scans.
3. Freeze the diff, then create exactly the third read-only agent `forward_policy_eval`; allow no edits or children.
4. Apply only concrete reviewer findings through a new RED test, then re-freeze and re-run affected gates.

### Task 8: Desktop runtime and explicit supporting evidence lanes

**Files:**
- No planned source edits

**Steps:**
1. Run `bootJar -x test`; run boot/local UI smoke only if the runtime can be isolated without a port/cache collision.
2. Use the in-app Browser for a localhost DOM/count-only smoke and keep the browser open.
3. Use Computer only for current Windows-visible count-only proof, never to control the browser.
4. Keep Supabase read-only and optional; report project-scoped auth gaps as `evidence_needed`.
5. Re-run source scan, PatchDrop inventory, secret count, and completion audit. Do not commit or reset the unrelated dirty worktree.
