# RAG Tail-Signal Counter-Evidence Web Tooling Implementation Plan

> **For Desktop Codex:** REQUIRED SUB-SKILL: use `superpowers:executing-plans` task-by-task. Do not spawn subagents unless the user separately authorizes delegation.

**Goal:** In at most 540 minutes, extend the existing demo-1 causal-probe, three-slot counter-evidence, coherence-verifier, and web-search assets into a guarded evidence workflow that preserves minority signals without granting them verdict authority.

**Architecture:** Keep local counter-evidence and external web evidence as separate lanes. `CausalProbeEvaluateTool` owns probe eligibility, `CounterEvidenceRetrieveTool` keeps its exact three local query slots, `WebSearchTool` remains an explicitly authorized external call, and `EvidenceCoherenceVerifyTool` alone owns release. Evidence may enter the final RAG prompt only through `PromptContext` and `PromptBuilder`; no tool or strategy assembles a final prompt.

**Tech Stack:** Java/Spring Boot, Gradle Kotlin DSL, JUnit 5, existing AgentTool manifest/registry/policy, `TraceStore`, `DebugEventStore`, `SafeRedactor`, existing Acme web-search gateway/providers.

## Global Constraints

- Desktop owns application-source mutation and final proof. Notebook evidence is supporting only.
- Hard cap: `timeBudgetMinutes=540`; stop immediately when success is proven.
- Keep `dev.langchain4j:*` exactly on `1.0.1`.
- Do not change public routes, public DTOs, DB schemas, Supabase state, credential names/values, or secret flow.
- Do not change global Git `safe.directory`.
- Do not create a new RAG framework, Doctor DB, web provider abstraction, query planner, prompt builder, or score fuser.
- Keep final prompt construction on `PromptBuilder.build(PromptContext)` or its verified current equivalent.
- A tail/weak/dissent signal has `decisionAuthority=probe_only`; only normalized independent evidence may affect a verifier verdict.
- Preserve `CounterEvidenceRetrieveTool`'s exact slots: `AUTHORITATIVE_CONSTRAINT`, `ALTERNATIVE_OR_UNKNOWN`, `PROVENANCE_AND_TIME`.
- Preserve fail-soft provider behavior, but classify disabled, empty, after-filter starvation, timeout, rate-limit, and other failure states separately where the owning provider exposes them.
- Never log raw queries, snippets, URLs containing sensitive data, API keys, auth headers, cookies, owner tokens, or full environment values.
- Do not commit, push, deploy, persist environment values, or mutate a database without separate user authorization.

---

## Causal model and terminology

The inpainting/outpainting analogy is useful only at the product level: both workflows add or repair context around an existing artifact. It is not a mechanism-level equivalence. Image post-processing synthesizes pixels; RAG retrieves, filters, cites, and conditions generation on evidence.

The desired causal chain is:

```text
ambiguous or weakly grounded claim
  -> register 2..5 falsifiable candidate worlds plus none/unknown
  -> select one independent, decision-changing dissent signal
  -> run three bounded counter-evidence families
  -> normalize provenance, time, directness, authority, independence, coverage, relation
  -> deterministic coherence verdict
  -> only verified evidence enters PromptContext
  -> PromptBuilder builds the final prompt
```

The forbidden causal shortcut is:

```text
rare/high tail score -> amplify -> treat as truth -> inject into final prompt
```

TWPM/CVaR/HYPERNOVA may prioritize inspection of a sparse signal. They do not prove it. Correlated copies count once, a weak-signal ID never enters `decisiveEvidenceIds`, and unresolved contradiction returns HOLD.

## Evidence baseline

- Frozen EvidenceSnapshot hash: `bfa82ed9ff40cde5a0074340d7d79707d789d1e8d109c5f73d1476c4358eb332`.
- Triad validator: valid; `canonicalQueryCount=3`, `scenarioCoverageRatio=1.0`, `schemaCompletenessRatio=1.0`, `snapshotHashMatch=true`, `orderStable=true`, `scoreMatches=true`.
- Active source evidence in the Notebook checkout: root `main/java`, `main/resources`, `src/test/java`; `:app` uses `app/src/main/java_clean`, `app/src/main/resources`.
- Existing chain: `CausalProbeEvaluateTool` -> `CounterEvidenceRetrieveTool` -> `EvidenceCoherenceVerifyTool`, exposed by `InternalAgentToolController`.
- Existing local counter-evidence is intentionally `retrieveStrictLocal(...)` and verifier-deferred.
- Existing `WebSearchTool` is conditional on `WebSearchGateway`, requires `web.get`, and is disabled in `tool_manifest__kchat_gpt_pro.json` with `legacy_reference_not_exposed`.
- Existing Acme gateway calls registered providers and ranks results, but current source does not prove a complete request/options hash plus provider attempt/response lineage row.
- Literal `Doctor DB` source term count is zero. `AgentDbContextProvider` and `AgentDbContextPromptInjector` exist, but do not reinterpret them as an authoritative document database without new evidence.
- Notebook focused Gradle proof is unavailable because Java/JAVA_HOME is absent. This is `java-runtime-unavailable`, not PASS.
- Official provider contracts were refreshed on 2026-08-02. Recheck them immediately before changing authentication, endpoint, pagination, or cost controls.

## Considered approaches

1. **Selected — guarded reuse/extension:** harden and conditionally expose the existing `web.search` tool, keep it separate from the local three-slot counter path, and require caller normalization before coherence verification. Lowest duplication and easiest rollback.
2. **Rejected — silently switch `retrieveStrictLocal` to hybrid/web:** this turns a local read tool into an external call without an explicit scope boundary and couples API cost/failure to local counter evidence.
3. **Rejected — new Doctor DB/web-agent subsystem:** the source owner and data contract are unproven, while equivalent AgentTool, AgentDb, provider, and verifier assets already exist.

## Three-query verdict

### POSITIVE_QUERY

- Existing source already contains the causal, retrieval, verification, web, AgentDb, and PromptBuilder seams.
- The smallest success path is to expose the existing web tool only after registry/policy proof and add bounded, redacted lineage.
- Expected value: better contradiction discovery and explicit uncertainty without a duplicate RAG implementation.

### NEGATIVE_QUERY

- More retrieval can add correlated noise; tail weighting can amplify poison or stale evidence.
- AgentDb is operational context, not automatically an authoritative document corpus.
- Enabling a manifest entry without registry, authorization, credential fail-soft, provider lineage, and output-bound proof expands risk.

### NEUTRAL_QUERY

- `verdict=APPLY`, `orderStable=true`, `goalScore=71.0`, `confidence=H`.
- APPLY authorizes the Desktop preflight and the conditional minimal patch only. Any failed source-owner, policy, secret, provider-lineage, or verification gate converts the execution verdict to HOLD.

## GoalContract

- **goalId:** `G-20260802-RAG-TAIL-WEB-01`
- **rewrittenUserIntent:** Strengthen grounded conversation and source-modification diagnosis by preserving independent tail signals, retrieving counter-evidence from existing local/web assets, and preventing unverified signals from becoming answer authority.
- **desiredOutcome:** A guarded `web.search` evidence lane with bounded inputs/outputs, fail-soft provider behavior, hash-only runtime lineage, and a tested handoff to the existing normalization/coherence contract.
- **measurableSuccess:**
  - `web.search` manifest status and runtime registry status agree.
  - Missing/blank/dummy credentials cause no outbound call and emit a redacted disabled reason.
  - `topK` and output bytes are bounded; blank query fails closed.
  - Every invocation records `queryHash`, `optionsHash`, `requestHash`, elapsed time, requested/returned counts, and overall outcome.
  - Every attempted provider produces one bounded row with provider-id hash, attempt status, response class, count, timing, and redacted failure class.
  - No provider result is considered normalized evidence until provenance/time/directness/authority/independence/coverage/relation are supplied.
  - The three local counter-evidence slots remain exact and local-only.
  - Correlated provenance counts once; weak-signal IDs cannot be decisive; contradiction or incomplete evidence HOLDs.
  - PromptBuilder boundary, LangChain4j purity, sourceSet hygiene, focused tests, `:app:classes`, and `bootJar` pass on Desktop.
  - Count-only secret scan result is zero.
- **nonGoals:** LoRA/image generation, inpainting/outpainting code, new DB/schema, new public endpoint, provider replacement, new orchestration framework, HYPERNOVA threshold tuning, ExtremeZ fan-out increase, automatic source patching.
- **authorizedMutationSurface:** Desktop canonical checkout; only the declared files below after preflight and RED proof.
- **prohibitedSurface:** inactive/reference roots, aliases/mirrors, archives/backups, generated output, DB/Supabase, secrets/env names or values, public APIs, PromptBuilder bypass.
- **evidenceBaseline:** EvidenceSnapshot hash and source observations above.
- **assumptions:** “Doctor DB” means deep document/agent context conceptually; no concrete owner is assumed. Existing AgentDb may be reused only for its proven operational-summary contract.
- **constraints:** Desktop final ownership, 540-minute cap, TDD, minimal diff, fail-soft providers, fail-closed authority and verification gates.
- **verificationOwner:** Desktop Codex in the canonical Desktop checkout.
- **verificationCommands:** exact commands in Task 6.
- **rollback:** revert only this session's declared-file hunks; restore `web.search.enabled=false`; retain tests and evidence logs if they document a rejected path. Do not use `git reset --hard`.
- **stopConditions:** index lock, dirty target overlap, pending PatchDrop collision, wrong sourceSet, mixed LangChain4j version, unregistered gateway/tool, authorization bypass, changed provider contract, secret risk, prompt-boundary bypass, failed focused/broad verification, or missing live lineage when runtime success is claimed.
- **timeBudgetMinutes:** `540`
- **goalScore:** `71.0`
- **verdict:** `APPLY`
- **evidence_needed:** Desktop root/branch/worktree/dirty proof; Desktop Gradle output; exact Doctor DB owner if DB work is still requested; credential-backed provider attempt/response proof for runtime lineage.

## SourceDirective

- **directiveId:** `SD-20260802-RAG-TAIL-WEB-01`
- **sourceOwner:** `desktop`
- **provenRoot:** `evidence_needed` — verify the Desktop canonical root before mutation.
- **provenBranch:** `evidence_needed` — require a non-detached branch owned by this session.
- **activeSourceSets:** expected from current evidence: root `main/java`, `main/resources`, `src/test/java`; reconfirm on Desktop at the same revision.
- **targetFiles:**
  - Modify: `main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java`
  - Modify: `main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java`
  - Modify: `main/resources/tool_manifest__kchat_gpt_pro.json`
  - Modify tests: `src/test/java/com/abandonware/ai/agent/tool/AgentWebSearchToolConditionalWiringTest.java`
  - Modify tests: `src/test/java/com/example/lms/config/AgentToolOpsConfigContextTest.java`
  - Modify tests only if an existing assertion owns the gate: `src/test/java/com/example/lms/api/internal/InternalAgentToolControllerSecurityTest.java`
  - Characterization-only unless RED proves a defect: `main/java/com/abandonware/ai/agent/tool/impl/ops/CausalProbeEvaluateTool.java`
  - Characterization-only unless RED proves a defect: `main/java/com/abandonware/ai/agent/tool/impl/ops/CounterEvidenceRetrieveTool.java`
  - Characterization-only unless RED proves a defect: `main/java/com/abandonware/ai/agent/tool/impl/ops/EvidenceCoherenceVerifyTool.java`
- **callPathOrBoundary:** `AgentToolInvoker -> WebSearchTool -> WebSearchGateway -> AcmeAICoreGateway -> WebSearchProvider(s) -> ranking`; separate verification path `CausalProbeEvaluateTool -> CounterEvidenceRetrieveTool -> caller normalization -> EvidenceCoherenceVerifyTool`; final answer boundary `PromptContext -> PromptBuilder`.
- **beforeBehavior:** web tool class and gateway exist, but manifest disables `web.search`; local counter path is three-slot/local-only; runtime provider lineage is incomplete; final runtime lineage is HOLD.
- **afterBehavior:** `web.search` is exposed only through existing manifest/registry/policy controls, rejects blank input, clamps budget/output, emits redacted request and provider attempt/response lineage, returns `decisionAuthority=probe_only` and `normalizationRequired=true`, and never auto-promotes results into a verifier or prompt.
- **excludedFilesAndMirrors:** `app/src/main/java`, `project/src/main/java`, `demo-1`, `lms-core`, `main/java/com/abandonwareai/**`, `main/java/service/**`, backups, archives, build output, legacy aliases, PromptBuilder implementation unless its existing boundary test fails.
- **publicApiChange:** `forbidden`
- **secretMutation:** `forbidden`
- **redTest:** manifest exposure fails at baseline; blank-query, topK clamp, policy denial, missing-gateway/credential fail-soft, attempt-row lineage, output bounds, weak-signal non-authority, three-slot exactness, and PromptBuilder-boundary assertions must fail for the intended missing behavior and pass for current protected behavior.
- **greenTest:** all focused tests in Task 6 pass; no local counter path calls web; `web.search` is invokable only with the existing required authority; disabled/empty/timeout/rate-limit remain distinct where source evidence permits; no secret/raw-query trace appears.
- **exactVerificationCommands:** Task 6 commands.
- **expectedEvidence:** test counts and status, diff path list, source/output hashes, tool manifest/registry match, request/options hash, provider attempt/response row, count-only secret scan, and explicit disabled reasons when credentials are absent.
- **failureClassifications:** `index-lock-conflict`, `dirty-target-overlap`, `patch-drop-pending`, `wrong-sourceset`, `tool_registry_missing`, `tool_policy_denied`, `provider-disabled`, `provider-empty`, `after-filter-starved`, `timeout`, `rate-limit`, `provider-contract-drift`, `output-bound-exceeded`, `secret-leak-risk`, `prompt-boundary-bypass`, `runtime-lineage-missing`, `desktop-proof-missing`.
- **rollback:** restore the manifest entry to disabled and revert only declared production hunks; rerun protected-behavior tests.
- **patchdropContract:** not required for Desktop-owned direct work. If execution mode changes to producer handoff, stop and create one manifest-pinned cumulative v3 bundle under the repository PatchDrop contract.
- **desktopFinalProof:** `evidence_needed`

## Task 1: Desktop preflight and frozen baseline (45 minutes maximum)

**Files:** no mutation.

- [ ] Set the Desktop root and collect ownership state.

```powershell
$Root = 'C:\AbandonWare\demo-1\demo-1\src'
Set-Location $Root
Get-Location
git rev-parse --show-toplevel
git branch --show-current
git worktree list
git status --short
if (Test-Path '.git\index.lock') { throw '[AWX][desktop] index-lock-conflict' }
powershell -NoProfile -ExecutionPolicy Bypass -File .\__patch_drop__\janitor_inventory.ps1
```

- [ ] HOLD if any declared target overlaps unexplained dirty work or a source-edit/PatchDrop lease.
- [ ] Confirm active sourceSets and version purity before editing.

```powershell
$env:AWX_SPLIT_BUILD_OUTPUTS = '1'
$env:AWX_BUILD_HOST_ID = 'desktop'
$env:GRADLE_USER_HOME = Join-Path $env:LOCALAPPDATA 'awx-gradle-user-home\rag-tail-web'
$ProjectCache = Join-Path $env:LOCALAPPDATA 'awx-gradle-project-cache\rag-tail-web'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$ProjectCache | Out-Null
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene --no-daemon --project-cache-dir $ProjectCache
```

Expected: both tasks PASS; every `dev.langchain4j` artifact is `1.0.1`.

## Task 2: RED and characterization tests (75 minutes maximum)

**Files:** existing focused tests only.

- [ ] Add failing assertions to `AgentWebSearchToolConditionalWiringTest` for:
  - blank query -> `web_search_query_required` with no gateway call;
  - `topK > 20` -> gateway receives `20`;
  - response contains `decisionAuthority=probe_only` and `normalizationRequired=true`;
  - trace contains query/options/request hashes, counts, elapsed time, and bounded outcome;
  - failure traces contain only redacted class/hash/length fields.
- [ ] Add gateway-focused tests in the nearest existing Acme gateway test file. If none owns the class, create `src/test/java/com/abandonware/ai/agent/integrations/AcmeAICoreGatewayLineageTest.java`.
  - one success, one empty, and one throwing provider produce three attempt rows;
  - provider identifiers are hashes, not raw labels;
  - no exception message, query, snippet, or credential appears in TraceStore/DebugEventStore;
  - response counts and per-attempt timing are nonnegative and bounded.
- [ ] Add manifest/registry assertions using existing catalog and registry patterns:
  - manifest `web.search.enabled=true`, `risk=external_call`, scope `web.get`, owner token required;
  - gateway absent -> tool not registered/invocation denied;
  - gateway present -> manifest and registry agree;
  - missing admin/consent -> policy denial.
- [ ] Preserve characterization tests proving `CounterEvidenceRetrieveTool` still calls `retrieveStrictLocal`, has exactly three slots, and never passes weak-signal IDs as decisive evidence.
- [ ] Run only the new/changed tests and observe the intended failures before production changes.

## Task 3: Minimal WebSearchTool and gateway hardening (120 minutes maximum)

**Files:** `WebSearchTool.java`, `AcmeAICoreGateway.java`, their focused tests.

- [ ] In `WebSearchTool`, validate a nonblank query before any external call.
- [ ] Clamp `topK` to an internal range of `1..20`; retain default `5`.
- [ ] Compute and trace only hash/count/timing metadata:

```text
web.search.tool.queryHash
web.search.tool.queryLength
web.search.tool.optionsHash
web.search.tool.requestHash
web.search.tool.requestedCount
web.search.tool.returnedCount
web.search.tool.elapsedMs
web.search.tool.outcome
web.search.tool.providerAttemptCount
web.search.tool.providerResponseCount
```

- [ ] Return additive bounded metadata:

```text
decisionAuthority=probe_only
normalizationRequired=true
verificationGatePassed=false
```

- [ ] In `AcmeAICoreGateway`, create one bounded attempt row per provider with this exact semantic schema; store it only in the existing redacted diagnostic/trace owner:

```text
providerIdHash, attempted, responseState, returnedCount, elapsedMs, errorType
```

Allowed `responseState`: `success`, `empty`, `failed`. Provider-specific adapters may retain their already proven `provider-disabled`, `after-filter-starved`, `timeout`, and `rate-limit` reasons; do not infer those reasons from an untyped generic exception.

- [ ] Cap result count before returning; do not alter `WebSearchGateway`'s public method signature.
- [ ] Run Task 2 tests until GREEN.

## Task 4: Guarded manifest exposure (75 minutes maximum)

**Files:** `tool_manifest__kchat_gpt_pro.json`, registry/policy tests.

- [ ] Change only the existing `web.search` entry:
  - `enabled: true`;
  - retain `risk: external_call`, `scopes: [web.get]`, `readOnly: false`, `ownerTokenRequired: true`;
  - reduce `maxOutputBytes` to the smallest value that passes bounded-result tests, no more than the current 65536;
  - retain artifact-by-reference behavior for oversized results;
  - replace `legacy_reference_not_exposed` only after registry and authorization tests pass.
- [ ] Do not enable `rag.retrieve`; its ownership remains separately unproven.
- [ ] Run manifest, contract-validator, registry, policy, and controller-security tests.

## Task 5: Counter-evidence and PromptBuilder integration guard (90 minutes maximum)

**Files:** characterization tests; production chain files only if a RED test proves a specific defect.

- [ ] Prove the local chain remains:

```text
CausalProbeEvaluateTool
  -> CounterEvidenceRetrieveTool.retrieveStrictLocal
  -> NORMALIZATION_REQUIRED
  -> EvidenceCoherenceVerifyTool
```

- [ ] Prove web results are not verdict-ready. The caller must supply atomic normalized rows with provenance, applicable time, directness, authority, independence group, coverage, and relation to the frozen original claim.
- [ ] Prove these decision rules:
  - copies from one provenance group count once;
  - unresolved hard conflict -> `UNDERDETERMINED/HOLD` or `CONTRADICTED/REJECT`;
  - an alternative remains `SUGGESTION_ONLY` until independently verified;
  - weak-signal IDs never enter `decisiveEvidenceIds`;
  - zero/empty provider output is not a contradiction;
  - final prompt construction remains on `PromptBuilder`.
- [ ] Do not modify HYPERNOVA/ExtremeZ unless a focused RED test directly proves that web evidence bypasses their existing clamp, DPP, citation, contradiction, time-budget, or cancellation gates. If such a cross-subsystem defect is proven, stop this plan and create a separately reviewed cross-subsystem directive.

## Task 6: Desktop verification and completion report (90 minutes maximum)

**Files:** no new production scope.

- [ ] Run static and focused verification.

```powershell
.\gradlew.bat checkLangchain4jVersionPurity checkSourceSetHygiene compileJava --no-daemon --project-cache-dir $ProjectCache

.\gradlew.bat test --no-daemon --project-cache-dir $ProjectCache `
  --tests '*CausalProbeEvaluateToolTest' `
  --tests '*CounterEvidenceRetrieveToolTest' `
  --tests '*EvidenceCoherenceVerifyToolTest' `
  --tests '*InternalAgentToolProbeRoundTest' `
  --tests '*InternalAgentToolControllerSecurityTest' `
  --tests '*AgentWebSearchToolConditionalWiringTest' `
  --tests '*AcmeAICoreGatewayLineageTest' `
  --tests '*AgentToolOpsConfigContextTest' `
  --tests '*ContractValidatorPathTest' `
  --tests '*PromptBuilderBoundaryTest'
```

If the new gateway test class was not needed because an existing owner test was extended, remove only that nonexistent `--tests` pattern; report `test-owner-reused`, not PASS for a nonexistent test.

- [ ] Broaden only after focused GREEN.

```powershell
.\gradlew.bat :app:classes bootJar --no-daemon --project-cache-dir $ProjectCache
```

- [ ] Run a count-only secret scan over changed files; never print matching lines.

```powershell
$Changed = @(
  'main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java',
  'main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java',
  'main/resources/tool_manifest__kchat_gpt_pro.json',
  'src/test/java/com/abandonware/ai/agent/tool/AgentWebSearchToolConditionalWiringTest.java',
  'src/test/java/com/example/lms/config/AgentToolOpsConfigContextTest.java',
  'src/test/java/com/example/lms/api/internal/InternalAgentToolControllerSecurityTest.java'
) | Where-Object { Test-Path $_ }
$SecretHits = @(rg -n 'sk-[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{20,}|gsk_[A-Za-z0-9]{20,}|pcsk_[A-Za-z0-9_-]{20,}|sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}|sbp_[A-Za-z0-9_-]{10,}' -- $Changed 2>$null)
Write-Host "[AWX][security] secretPatternHits=$($SecretHits.Count)"
if ($SecretHits.Count -ne 0) { throw '[AWX][security] secret-leak-risk' }
```

- [ ] Live provider proof is optional and credential-dependent. Use only credentials already configured for the task; record `hasCredential`, source type, provider-id hash, attempt/response counts, and reason codes. Never print or persist a key.
- [ ] Set `runtimeLineageVerdict=APPLY` only when the same request has an options hash and at least one matching provider attempt/response row. Otherwise set `runtimeLineageVerdict=HOLD` even if compile, tests, boot, or UI pass.
- [ ] Produce the final report in repository order: Summary, Observation, Patch, Verification, Risks & Next. Include command, expected, observed, failure classifications, changed-file hashes, rollback, and `desktopFinalProof`.

## Completion gate

Completion requires all source/test gates above plus Desktop-owned command output. If source already meets the requested behavior after characterization, return `no_patch_needed` with the proving commands and do not churn code. Browser is optional unless visible UI behavior changes; Computer Use is unnecessary; Supabase remains read-only/evidence-needed and must not be used for this source-only objective.
