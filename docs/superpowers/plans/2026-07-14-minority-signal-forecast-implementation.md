# Minority Signal Forecast Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. This Desktop run executes inline because the canonical root is already dirty and external-agent dispatch is not required.

**Goal:** Deliver and verify a repo-local time-bounded minority-forecast skill, standalone 9-hour Safe Patch directive, and the smallest proven MLA breadcrumb count correction.

**Architecture:** Keep the forecast ledger as a process artifact between the existing counter-evidence router and verifier. Preserve the existing four-skill ownership split, keep `verificationGatePassed` verifier-only, and update only the existing MLA breadcrumb aggregate count after append operations.

**Tech Stack:** Markdown/YAML repo-local skills, Python/PowerShell validators, Java 17, JUnit 5, Gradle wrapper.

## Global Constraints

- Work only in `C:\AbandonWare\demo-1\demo-1\src` and preserve unrelated dirty changes.
- Do not create a 20-worker runtime, external broker, SMB service, background daemon, or automatic PatchDrop handoff.
- Keep Browser and Computer demand-driven, Supabase read-only/project-scoped, and PatchDrop manual-only.
- Keep every `dev.langchain4j:*` dependency at `1.0.1` and final prompt construction on `PromptBuilder.build(PromptContext)`.
- Never emit raw prompts, queries, credentials, headers, cookies, DB URLs, or full environment dumps.
- Do not commit, stage, push, or dispatch subagents automatically from this dirty canonical root.

---

### Task 1: Prove the old family lacks a forecast owner

**Files:**
- Read: `.agents/skills/demo1-generating-falsifiable-hypotheses/SKILL.md`
- Read: `.agents/skills/demo1-retrieving-counter-evidence/SKILL.md`
- Read: `.agents/skills/demo1-verifying-evidence-coherence/SKILL.md`
- Read: `.agents/skills/demo1-triangulating-counter-evidence/SKILL.md`

**Interfaces:**
- Consumes: the four current counter-evidence entrypoints.
- Produces: RED evidence that no existing owner defines `demo1.minority-forecast-ledger.v1`, `validAfter`, and `validUntil` together.

- [x] Run a deterministic baseline probe over only the four existing skills.
- [x] Confirm it exits non-zero because the durable forecast schema and time window are absent.
- [x] Record this as the no-guidance control; do not dispatch agents merely for process ceremony.

### Task 2: Verify the forecast skill artifact

**Files:**
- Create or refine: `.agents/skills/demo1-forecasting-minority-signals/SKILL.md`
- Create or refine: `.agents/skills/demo1-forecasting-minority-signals/agents/openai.yaml`

**Interfaces:**
- Consumes: exact claim, lineage, bounded time window, decision impacts, read-only observation cost.
- Produces: `demo1.minority-forecast-ledger.v1` with `verificationGatePassed=false`.

- [x] Require `RUN | RESOLVE | REUSE | SKIP | DEFER` routing and one bounded observation.
- [x] Preserve exactly three counter-evidence query slots and verifier-only terminal authority.
- [x] Run `quick_validate.py` and a contract probe that checks the required schema fields.
- [x] Keep `SKILL.md` within 160 lines and `agents/openai.yaml` within metadata constraints.

### Task 3: Verify the approved design and 9-hour directive

**Files:**
- Create or refine: `docs/superpowers/specs/2026-07-14-minority-signal-forecast-design.md`
- Create or refine: `agent-prompts/demo1_minority_signal_forecast_9h_safe_patch.md`

**Interfaces:**
- Consumes: the attached goal objective and official current OpenAI model guidance.
- Produces: a standalone Desktop Safe Patch directive with no prompt-manifest dependency.

- [x] Correct the approval source to the actual attached `goal-objective.md`.
- [x] Keep GPT-5.6 model roles optional and availability-gated; use `reasoning.mode: "pro"` only as official current guidance.
- [x] Require source-only reconnaissance, first-confirmed patch lane, exact failure classes, and demand-driven external proof.
- [x] Run the prompt secret-pattern validator; intentionally skip prompt manifest rebuild because this is standalone.

### Task 4: Prove the MLA aggregate-count correction

**Files:**
- Modify only if needed: `main/java/com/example/lms/telemetry/MlaBreadcrumb.java`
- Test: `src/test/java/com/example/lms/telemetry/MlaBreadcrumbTest.java`

**Interfaces:**
- Consumes: existing `TraceStore.append("ml.breadcrumbs.v1", row)` calls.
- Produces: immediate `cihRag.mlaBreadcrumbCount` after SSE and LLM reward append paths.

- [x] Preserve unrelated `InteractionEvidencePolicy` changes already present in both files.
- [x] Confirm focused RED assertions cover SSE and reward-only append paths.
- [x] Keep the implementation to one shared count refresh invoked after existing appends.
- [x] Run `MlaBreadcrumbTest` with Desktop output/cache isolation and `--rerun-tasks --fail-fast`.

### Task 5: Run completion gates and audit the objective

**Files:**
- Verify: all files named above.

**Interfaces:**
- Consumes: current files plus fresh command output.
- Produces: requirement-by-requirement completion evidence or exact `evidence_needed` rows.

- [x] Run the four-skill counter-evidence validator and confirm its existing ownership contract remains green.
- [x] Run the skill-family postprocessor with compact `-SummaryJson`; do not open a full report unless counts require it.
- [x] Run `checkLangchain4jVersionPurity`, `checkSourceSetHygiene`, and `compileJava -x test` with isolated Desktop cache.
- [x] Run a count-only secret scan over changed files.
- [x] Recheck Git diff/status and verify no unrelated file was modified by this pass.
- [x] Keep Browser/Computer/Supabase as supporting lanes unless the final evidence audit proves one became necessary.
