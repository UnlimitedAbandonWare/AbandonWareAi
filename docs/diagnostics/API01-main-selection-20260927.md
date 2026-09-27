# API-01: registered API main-selection readiness

Task: `api01-main-selection-0927-c9be6137`. Checked 2026-09-27, Asia/Seoul.
Status: **HOLD / 보류; no application or routing change applied**.

## Selected unit and authority

The live user selected only API-01 from `C:/Users/nninn/Downloads/DIRECTIVE_V2.md`.
Directive SHA-256: `c6ee65b2558c9d2cdea6107de7c32853e347d32078fb20e43c3f8bd5f8c14fac`.
The older trace packets are supporting references. API-02, API-03, trace porting,
ChangeIntent implementation, authentication changes, commits and deployments are
outside this unit. PROTO_OPEN is preserved. No provider was substituted.

Purpose: make exactly one prepared, already registered API route available for
direct main-answer selection through the existing catalog and strict-selection
path. Candidate: `llmrouter.api3` / Groq / `openai/gpt-oss-120b`.

Root/build: `C:/AbandonWare/demo-1/demo-1/src`; root Gradle `main/java`,
`main/resources`, `src/test/java`; Java 17.0.13, Spring Boot 3.3.4,
LangChain4j 1.0.1. Branch `codex/owned-runtime-browser-restart`,
HEAD `847d32389a8e14b472e0931d2a22da1ab40ca3e2`.

Remote `main` resolved through GitHub to
`b2eaba4679f70ded860b052faa59b29073d0c859`. A local ancestry check did not
establish it as an ancestor of HEAD; `merge-base` returned no common ancestor
and symmetric counts were 31 local / 2 remote. Remote snapshots and CI were
therefore not used to certify current working-tree behavior.

## Current source facts and blockers

1. `main/resources/application.properties:665` sets
   `app.ai.allow-remote-model-selection=false`. This is a source value, not a
   claim about a running Environment or its effective origin.
2. `main/resources/application-llm.yaml:511` already registers `api3`, defaults
   enabled=true, fallback-only=true and weight=0.0. The model and endpoint match
   the Groq manifest entry. `application-meta-display.yml:185` supplies the
   existing credential environment name. Fallback-only does not forbid direct
   selection and must not be disabled for API-01.
3. The active classpath manifest is
   `main/resources/configs/cloud-models.manifest.yaml`, not the missing root
   `configs/cloud-models.manifest.yaml`. Its Groq row is disabled. The manifest
   contains eight entries and one enabled OpenAI economy entry. The latter also
   has an enabled route in the meta-display profile. Enabling the common flag
   could expose that additional route if eligible; the required effective
   before/after selectable-set proof has not been obtained. No unrelated route
   was disabled to force a one-route result.
4. The default Groq admission evidence was verified at
   `2026-09-24T05:11:04.881Z` and expired at
   `2026-09-25T05:11:04.881Z` (2026-09-25 14:11 KST). Only the free-plan boolean
   and timestamps were inspected; no credential or account identifier is in
   this report. `GROQ_API_KEY` presence was observed as a boolean; presence is
   not authentication or readiness proof. `GROQ_FREE_EVIDENCE` was absent in
   this agent process. `GroqFreeTierGuard.proof` rejects stale evidence and
   `disabledReason` categorizes it as `groq_free_account_evidence_needed`.
   The effective server property origin/guard result remains **not_observed**.
   The public limits document is not a replacement for runtime account evidence.
5. A new task browser tab to `http://127.0.0.1:18180/chat` returned
   `net::ERR_CONNECTION_REFUSED`; the port listener query found no listener on
   18180/18181/18182. The older ready launcher record was not treated as current
   readiness. No saved login, cookie, browser trace or token was exported.

The first source-backed candidate remains configuration admission, not a missing
router or missing main-generation service. Existing path:
`GET /api/chat/models -> ChatModelCatalogService -> strictModelSelection ->
ChatWorkflow -> DynamicChatModelFactory -> LlmRouterAspect`.
No cue JSON path, 1024-token auxiliary ceiling, automatic-routing weight,
embedding policy, session memory or authorization policy was changed.

## Scope and preservation card

Potential activation scope, only after readiness is proved: the existing remote
selection property, the single registered api3 route and its manifest entry.
No product write was admitted. No source lease was acquired or retained.
Current report writes: this file and one task row in `docs/PROJECT_STATUS.md`.

Observed source SHA-256 baselines:

| File under root | SHA-256 |
|---|---|
| main/resources/application.properties | ff911b7fbafd52d7fd15a3a16ae46a732dafcd369a2a37055d43af61bc7b81a4 |
| main/resources/application-llm.yaml | 18e001db99aeca776d78249c6352a89ef77320a58f52aa234c324f0c4e12ca11 |
| main/resources/application-meta-display.yml | c4ad6eafbadc1757674729f62a2b8ca11e195a445b41da5c0ecdff84ca200eba |
| main/resources/configs/cloud-models.manifest.yaml | ee779d82c71f11a0b6b7ddc66fe4dbeadbe4ec11e368a5f321388ee07fdb8a95 |
| main/java/com/example/lms/service/ChatModelCatalogService.java | 60ecbb4f5553b427ff22807503c4ac42369414d1d022236538db6954a7e27c26 |
| main/java/com/example/lms/agent/GroqFreeTierGuard.java | e875be8d44d97fef9c0c74d90c63177ae7db0e648bc752c56bed330e791d9223 |

## Fresh verification

Run `4af2dddc-812c-4d15-b6f3-ff4d31f2505f`, 2026-09-27 10:22–10:24 KST.
`AWX_SPLIT_BUILD_OUTPUTS=1`, `AWX_BUILD_HOST_ID=desktop`.

```powershell
.\gradlew.bat :compileJava :processResources :test `
  --tests com.example.lms.service.ChatModelCatalogServiceTest `
  --tests com.example.lms.llm.gateway.CloudModelRouteClassifierTest `
  --tests com.example.lms.agent.GroqFreeTierGuardTest `
  --tests com.example.lms.service.routing.ExactRequestedModelTest `
  --tests ai.abandonware.nova.orch.aop.ExactModelGatewayTest --console=plain
```

Compile and resource processing passed. Overall command exit=1.

| Suite | Tests | Passed | Failed | Skipped |
|---|---:|---:|---:|---:|
| ChatModelCatalogServiceTest | 4 | 4 | 0 | 0 |
| CloudModelRouteClassifierTest | 6 | 6 | 0 | 0 |
| GroqFreeTierGuardTest | 10 | 10 | 0 | 0 |
| ExactRequestedModelTest | 3 | 2 | 1 | 0 |
| ExactModelGatewayTest | 4 | 4 | 0 | 0 |
| Total | 27 | 26 | 1 | 0 |

The failing assertion is
`ExactRequestedModelTest.unavailableOrFailedManualModelNeverReturnsBaseModel`,
line 43: expected `model_unavailable`, actual `backend_unavailable`.
The synthetic upstream exception passes through
`PolicyBasedModelRouter.exactRequestedModel:507 -> ModelSelectionException.failure:56`;
the current failure classifier maps an unclassified backend failure to
`backend_unavailable`. This failure was observed before any product changes;
it was not hidden by weakening the assertion. It does not establish a live
provider or browser failure and prevents claiming all focused tests green.

Default AWX `build_error_mine` received only the allowlisted, sanitized build
log path and returned `primaryClass=other`, `outputCount=0`. The source and
original JUnit failure were checked independently. Recovery AWX was not used.

Evidence is retained in
`data/agent-handoff/codex-autonomy/api01-main-selection-0927-c9be6137/verify-admission/`:
`command.log`, `run.json`, `sanitized-build.log`, `suite-summary.json`.
The recorder reported zero JUnit totals because it did not collect this split
output; the totals above were independently read from the five fresh
`build/desktop/test-results/test/TEST-*.xml` files with timestamp and hashes
retained in `suite-summary.json`. Zero recorder totals were not called a pass.

No full test suite, server startup, real API generation, successful main API
answer, prompt-parity integration, final browser regression, or separate auth
profile login verification was performed. No post-patch GLM review was called
because no first product patch occurred.

Official Exa checks, 2026-09-27: [Groq OpenAI compatibility](https://console.groq.com/docs/openai)
confirms the existing OpenAI-compatible base URL;
[Groq rate limits](https://console.groq.com/docs/rate-limits) distinguishes
published tables from account-specific limits. Neither proves this account's
admission or generation success. No SDK/version change was made.

## Stop and next evidence

`holdScope=API-01 activation`; `firstBlockingRule=prepared-single-route-evidence-needed`;
`blockingEvidence=expired-default-account-proof + runtime-not-observed +
unproven-single-route-selectable-delta`;
`independentWorkCompleted=source-trace + structural-probe + focused-build-tests`;
`repositoryWideHold=false`.

Next verification: obtain the existing runtime's metadata-only evidence showing
the effective property origins, api3's current guard eligibility, and that the
proposed change adds only the approved route to the selectable set. This is not
a request to reveal a key, buy credits, log into Billing, fabricate evidence,
or disable the guard. Until that evidence exists, do not activate api3 or
automatically choose a paid alternative.

There is no application diff to roll back. Report/status recovery uses the
task-owned `cycle-02-report` checkpoint; restore only after confirming unchanged
postimages, and never restore over another task's later status edits.
The directive remains incomplete and is retained; no completed-directive
deletion was authorized by this partial result. Stop after this unit's HOLD;
API-02/API-03/TRACE/QUERY/VECTOR were not started.
