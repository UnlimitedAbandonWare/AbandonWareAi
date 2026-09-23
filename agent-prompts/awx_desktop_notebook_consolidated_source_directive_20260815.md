# AWX Desktop Notebook Consolidated Source Directive — 2026-08-15

## Canonical contract

~~~yaml
contractVersion: demo1.notebook-directive-consolidation.v1
programId: awx-desktop-notebook-consolidated-source-20260815
canonicalExecutionRoot: C:\AbandonWare\demo-1\demo-1\src
canonicalDirectivePath: agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260815.md
sourceOwner: desktop
finalVerificationOwner: desktop
notebookEvidenceAuthority: supporting_only
fallbackWriteRoot: null
activeSourceSets:
  - main/java
  - main/resources
  - app/src/main/java_clean
  - app/src/main/resources
directiveGenerationVerdict: APPLY
applicationSourceMutationVerdict: HOLD
retirementVerdict: RETIRED
desktopFinalProof: evidence_needed
runtimeLineageVerdict: HOLD
deleteAuthorizedByUser: true
retirementGateOverride: explicit_user_goal_delete_after_delivery
retirementProof: verified
repositoryWideHold: false
firstBlockingRuleForCurrentGoal: null
nextSingleProofForCurrentGoal: null
~~~

This is the one current execution program for the six direct-child handoffs found
under data/agent-handoff/notebook on 2026-08-15. It supersedes those leaf
handoffs as an instruction source, but it does not silently overwrite the older
2026-08-06 canonical program or its lifecycle state. The older controller is a
frozen executor for that older program, not the lifecycle owner of this program.
Current C-root source and fresh tests win over every Notebook claim.

## Frozen Desktop evidence

- Root: C:\AbandonWare\demo-1\demo-1\src
- Branch: codex/owned-runtime-browser-restart
- HEAD: 0796a3c5b29bbb08c3314bd40649d856d4a7bce6
- Java: 17.0.13
- Node: v24.13.0
- Git index lock: absent
- Top-level PatchDrop patches: 0
- Active source leases: 0
- Notebook root and all six direct-child leaves: contained, non-reparse
- All six direct-child leaves: untracked
- Related application targets are already dirty or untracked. Preserve every
  existing hunk; this program grants no new source write while that overlap is
  unresolved.

Fresh source-set evidence:

- root main: main/java and main/resources
- root test: src/test/java
- :app main: app/src/main/java_clean and app/src/main/resources
- :app test source is currently configured empty; do not treat
  app/src/test/java as active without newer Gradle evidence.

## DirectiveInventory

The user-requested root is intentionally the only discovery root for this
program. Sealed Canary directories, the prior consolidated state tree, and
ollama-model-intake are excluded from candidate and retirement scope.

| Input | SHA-256 | Bytes | Role in this program |
| --- | --- | ---: | --- |
| data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json | 702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44 | 12605 | Included source directive; normalized ID G-20260802-RAG-TAIL-WEB-01 |
| data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md | BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B | 29636 | Included target-specific UI directive; IDs AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-20260805 and AWX-DESKTOP-BROWSER-MAIN-CHATBOT-PARITY-V2 |
| data/agent-handoff/notebook/2026-08-07-desktop-consolidated-source-program-superpowers.md | 3D5E8B79B23CC1A713CE432806654193D5C6D770A47B52B561151ADA3435CB0F | 15813 | Included predecessor program; embedded units A0, E10, C20, C21, H30, S40, S50, D60 |
| data/agent-handoff/notebook/g0-desktop-verification-20260811.json | 62704B33741D061FC8FDEFD1207FC653ECD7F92E12E0C129E449A2E6B93CD7C5 | 1685 | Supporting verification requirement; never a source target |
| data/agent-handoff/notebook/qwen38-macos-web-probe-desktop-20260815.md | 9656726346C78F2ADAADC9874EBA5575BCD5BB72DD8E0F29DCEB49CCFDCBB8FC | 10717 | Included read-only capability directive; targetFiles is empty |
| data/agent-handoff/notebook/top25-demo1-evidence-20260814.md | DCE74013204D75AF43EBEBE0BA557EE74A98D26E3151853A0F040927D7BD28A0 | 6380 | Supporting decision packet; explicitly not a patch/apply request |

Excluded subtrees:

| Path | Reason |
| --- | --- |
| data/agent-handoff/notebook/source-directive-canary-v1 | sealed-canary |
| data/agent-handoff/notebook/source-directive-canary-v2 | sealed-canary |
| data/agent-handoff/notebook/consolidated | prior lifecycle evidence and persistent mutex leaves |
| data/agent-handoff/notebook/ollama-model-intake | reusable read-only intake material |

## Fresh verification summary

The following characterization is current Desktop evidence, not carried-forward
Notebook evidence.

| Surface | Fresh result | Decision |
| --- | --- | --- |
| SourceSet hygiene and LangChain4j purity | exit 0 | active roots confirmed; LangChain4j gate passed |
| RAG causal/counter/coherence/web wiring, PromptBuilder, decision/evidence reconstruction, ensemble and final-release boundary | 10 JUnit classes, 132 tests, 0 failures/errors/skips | the tested chain is GREEN; the untested bounded WebSearchTool/Acme request-lineage gap remains WU-R70 |
| chat UI browser-fault fixture | exit 0 | no_patch_needed |
| chat UI stream contract | exit 0 | no_patch_needed |
| chat UI view-layer contract | exit 0 | no_patch_needed |
| consolidation public contract | PASS, 224 assertions | already present |
| consolidation core | PASS, 230 assertions | already present |
| consolidation retirement core | PASS, 143 assertions | already present |
| full 2026-08-06 consolidation controller contract against the changed checkout inventory | exit 1; Snapshot returned candidate-inventory-mismatch before workUnitCount | expected fail-closed result from a historical frozen program; not a defect in this program |
| 2026-08-06 controller Status | exit 1, reason candidate-inventory-mismatch | diagnostic only; it is not a valid GREEN gate for the 2026-08-15 program |

### Historical-controller root cause

The current controller does not implement a generic adoption path. Its validated
manifest is fixed to program ID `awx-desktop-notebook-consolidated-source-20260806`,
10 candidates, 34 exclusions, 14 work units, and the 2026-08-06 canonical/state
paths. Before every action it recomputes the checkout-wide inventory and compares
that inventory byte-for-byte with the frozen manifest.

The fresh comparison found 13 dynamic candidates versus 10 frozen candidates.
The three additions are:

- agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260815.md
- data/agent-handoff/notebook/qwen38-macos-web-probe-desktop-20260815.md
- data/agent-handoff/notebook/top25-demo1-evidence-20260814.md

`g0-desktop-verification-20260811.json` is intentionally skipped by
`Test-KnownNotebookHandoffArtifact`; it is not the mismatch source. No frozen
candidate is missing, and the exclusion count remains 34. Patching the historical
controller to accept a different program would weaken its immutable-inventory
contract and create generalized lifecycle infrastructure for this one-off
consolidation. WU-I00 therefore records `not_applicable`, not a source or tooling
patch.

Representative current source evidence:

- main/resources/static/js/chat.js already generates x-request-id, preserves a
  backend session ID, uses AbortController, calls /api/chat/cancel, and reads
  /api/chat/sessions plus session detail.
- CounterEvidenceRetrieveTool already uses the canonical query slots, query
  trace references, independence-group hashes, and conflict counts.
- EvidenceCoherenceVerifyTool already keeps conflicts fail-closed and exposes a
  verification gate separately from evidence quantity.
- AgentDbContextPromptInjector already enriches PromptContext rather than
  assembling a final prompt outside PromptBuilder.
- main/resources/tool_manifest__kchat_gpt_pro.json keeps web.search disabled.
- WebSearchTool currently lets a blank query reach its gateway, accepts an
  unbounded positive topK, and AcmeAICoreGateway records failures but not one
  bounded attempt/result lineage row for every provider attempt. Existing
  GREEN tests do not cover those three conditions.
- DecisionEvidenceReconstructionValidator and its DecisionTraceAspect wiring
  already exist and have a 17-test current GREEN characterization.
- LocalLlmProcessManager and application-desktop-gpu-node.yml already own the
  local Ollama/11435 seam. The Qwen handoff authorizes inspection only.

## Rewritten three-query decision

### POSITIVE_QUERY

Can the six Notebook handoffs become one smaller Desktop program by preserving
their decision-changing constraints, accepting current GREEN behavior as
no_patch_needed, classifying the historical-controller mismatch correctly, and
isolating only the remaining source-backed WebSearchTool/Acme gap?

Expected observation:

- one canonical Markdown exists outside the retirement root;
- current causal/counter/coherence, chat, prompt, ensemble, and
  decision/evidence contracts stay untouched while their focused tests remain
  GREEN;
- WU-R70 changes only the dormant WebSearchTool/Acme request-lineage seam after
  its exact new RED is reproduced;
- the frozen 2026-08-06 controller is left unchanged and its mismatch is not
  promoted into a 2026-08-15 execution gate;
- optional Browser, provider, Qwen, Mac, and G0 evidence remains explicitly
  separate.

Falsifier: any silent Java/UI expansion, duplicate framework, provider-success
claim without wire evidence, or attempt to make the historical controller own a
program whose identity it cannot represent.

### NEGATIVE_QUERY

Could a mechanical union destroy already patched behavior or turn read-only
evidence into source authority?

Observed counter-evidence:

- the 2026-08-05 directive limits mutation to three UI files, while the
  2026-08-07 predecessor also names Java/backend targets;
- the UI, RAG, PromptBuilder, ensemble, and decision/evidence contracts are
  already GREEN;
- the related source targets are dirty or untracked;
- the new canonical plus Qwen and TOP25 inputs are outside the frozen 2026-08-06
  controller inventory;
- Qwen/macOS and TOP25 explicitly prohibit source adoption.

Smallest disconfirming probe: compare the frozen and dynamic inventories without
mutation. That probe is complete: 13 versus 10, exactly three additions, no
missing frozen candidate, and 34 exclusions on both sides.

### NEUTRAL_QUERY

Forward and reverse consideration produce the same decision:

~~~yaml
orderStable: true
directiveArtifact: APPLY
applicationSourceMutation: HOLD_UNTIL_WU_R70_RED_AND_PREFLIGHT
alreadyGreenBoundaries: no_patch_needed_except_bounded_WU_R70_gap
historicalControllerInventoryReconciliation: not_applicable
browserAndRuntimeProof: evidence_needed
providerAndWireProof: not_observed
qwenAndMacMutation: forbidden
retirement: RETIRED_BY_EXPLICIT_USER_GOAL
~~~

Rewritten executor query:

> At the Desktop canonical root, preserve all current application-source hunks.
> Treat the frozen 2026-08-06 controller's candidate-inventory-mismatch as an
> expected historical-program integrity result, not as a gate for this program.
> Treat the current 132-test Gradle GREEN and three chat UI fixture GREEN results as
> no_patch_needed for their tested behavior. After a focused RED, patch only
> WebSearchTool and AcmeAICoreGateway to reject blank queries before a call,
> bound topK, and emit redacted per-provider request/result lineage; preserve
> every other RAG, chat UI, PromptBuilder, ensemble, and decision/evidence
> behavior. Keep web.search disabled, Browser/runtime evidence separate from
> source proof, and Qwen/macOS plus TOP25 strictly read-only. After each
> remaining inherited unit has its own
> current RED/GREEN or no_patch_needed evidence and final Desktop proof is
> verified, report the result against this canonical provenance. The six
> original direct-child leaves were already retired after exact preimage
> verification; do not reconstruct or depend on them.

## RequirementLedger

| Requirement | Current status | Decision | Live basis |
| --- | --- | --- | --- |
| ND-I00: classify the historical controller against the current six-input program | stale / not_applicable | leave the frozen 2026-08-06 controller and state unchanged | dynamic inventory is 13 versus frozen 10; the additions are the new canonical, Qwen, and TOP25; no frozen candidate is missing |
| ND-R70: exactly three bounded counter-query families plus a fail-closed web tool request/lineage seam | partially_present | implement only the WebSearchTool/Acme bounded gap after focused RED | core chain GREEN; blank query, upper-bound, and all-attempt lineage are absent |
| ND-C20/C21: request/session correlation, resilient stream parsing, explicit cancel, session/evidence/status UI, typed fail-soft states | already_present on tested source boundary | no_patch_needed; runtime proof remains evidence_needed | three current Node fixtures GREEN |
| ND-E10: one existing ensemble final-answer boundary and explicit final verification release gate | already_present | no_patch_needed | current ensemble/final-release/PromptBuilder tests GREEN |
| ND-T25: decision/evidence reconstruction on existing TraceStore/DebugEventStore seams | already_present | no_patch_needed; prohibit second validator/framework | current validator source and 17-test GREEN |
| ND-H30: deterministic local smoke endpoint and process-environment restoration | evidence_needed | characterize in a future execution run | inherited predecessor claim; not rerun in this consolidation |
| ND-S40: source guard plus existing security/non-web boot boundary | evidence_needed | characterize in a future execution run | inherited predecessor claim; related targets dirty |
| ND-S50: local-first next-action routing and opt-in external proof | evidence_needed | characterize in a future execution run | inherited predecessor claim; no external mutation authorized |
| ND-QWEN: Qwen3.8/Desktop and Mac Shortcuts capability assessment | held read-only | no source/config/model-store mutation | targetFiles empty; no GPU/Ollama/Mac live proof gathered here |
| ND-G0: broad Desktop full test and control-plane topology proof | evidence_needed | run only as final broad proof, not as a source-edit excuse | focused tests passed; full 5,891-test claim remains Notebook supporting evidence |
| ND-RETIRE: exact-leaf retirement | verified | retired by the explicit current-goal sequence after canonical delivery and exact preimage verification; no source mutation implied | six targets absent, canonical remains, and four excluded subtrees remain |

## Conflict decisions

1. UI-only versus Java/backend scope:
   current UI contracts are GREEN, so the UI family is no_patch_needed. Do not
   import the predecessor Java targets. A future backend RED requires its own
   target-specific directive, preflight, lease, preimages, rollback, and proof.
2. Browser required versus opt-in:
   Browser is required only before a final current UI-runtime claim. It is not
   required for the already GREEN static/fixture characterization and never
   proves a source write or provider attempt.
3. RAG-tail held versus already implemented:
   current focused source/tests decide. The causal/counter/coherence and prompt
   boundaries are no_patch_needed. WU-R70 owns only the dormant WebSearchTool/
   Acme request-lineage gap. Provider/runtime lineage remains HOLD because no
   provider attempt was made.
4. TOP25 adoption:
   the existing decision/evidence validator satisfies the only concrete local
   seam. Do not install DeepSec, a plugin loader, another orchestrator, a graph
   database, RAGFlow, or a benchmark generator from this packet.
5. Qwen/macOS:
   keep it outside application-source work. No ollama pull, install, persistent
   environment change, process start/stop, TCC change, screenshot, or Mac
   mutation follows from this program.
6. Retirement:
   the current user goal explicitly ordered deletion after delivery of the
   integrated directive. That operation-specific instruction overrides the
   skill's default required-GREEN timing gate, but it does not mark source work
   GREEN or authorize source mutation. Each of the six unchanged leaf preimages
   was verified immediately before exact deletion; excluded subtrees remain.

## Work units

### WU-I00 — historical controller mismatch classification

~~~yaml
status: no_patch_needed
causalBoundary: frozen 2026-08-06 inventory versus current checkout inventory
applicationSourceTargets: []
owner: read-only consolidation diagnosis
observed:
  - controller Status exits 1 with candidate-inventory-mismatch
  - controller Snapshot exits 1 before emitting workUnitCount
  - frozen candidates are 10 and dynamic candidates are 13
  - additions are the new canonical, Qwen, and TOP25; g0 is a known handoff artifact
decision:
  - keep the frozen 2026-08-06 controller and state unchanged
  - do not make exit-0 Status a gate for the 2026-08-15 canonical
rollback: none; no controller, state, event, or application-source file changed
prohibited:
  - direct JSON state editing
  - application-source editing
  - deleting or moving candidate leaves during controller diagnosis
  - changing persistent mutex leaf semantics
~~~

Exact diagnostic command:

~~~powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/scripts/invoke_consolidated_program.ps1 -Action Status -Root C:\AbandonWare\demo-1\demo-1\src
~~~

Observed result: exit 1 and reason candidate-inventory-mismatch. This is stable
with the controller's hard-coded 2026-08-06 identity and is not a future GREEN
expectation for this program. Do not invent an adoption command, hand-edit its
state, or weaken its closed-schema assertions.

### WU-R70 — bounded web request and provider-lineage seam

Status: partially_present; conditional source patch candidate.

Exact allowed targets:

- main/java/com/abandonware/ai/agent/tool/impl/WebSearchTool.java
- main/java/com/abandonware/ai/agent/integrations/AcmeAICoreGateway.java
- src/test/java/com/abandonware/ai/agent/tool/AgentWebSearchToolConditionalWiringTest.java
- src/test/java/com/abandonware/ai/agent/integrations/AcmeAICoreGatewayTraceTest.java

The manifest is an excluded target. Keep web.search disabled.

Current before behavior:

- null/blank query is passed to WebSearchGateway;
- a positive topK has no upper bound;
- missing or unparsable topK defaults to 5 and must keep that compatibility;
- provider failures get a redacted breadcrumb, but successful and failed
  attempts do not share one bounded attempt/result lineage shape.

Required RED before production edits:

1. a blank query returns a typed empty/skipped result and makes zero gateway
   calls;
2. missing or unparsable topK remains 5, while numeric values are clamped to
   1..20 before the gateway call;
3. each provider attempt emits one bounded row with request/options hashes,
   provider ID hash/length, attempt ordinal, outcome/reason, elapsed time and
   returned count, never raw query/provider payload/error text;
4. failure and empty-output rows remain distinct, fan-out remains fail-soft,
   and no provider is enabled or called by the test;
5. web evidence remains probe-only: normalization is required and the
   verification gate is false until the existing coherence verifier accepts
   bound evidence.

Use the existing runtime/decision lineage vocabulary where it fits; do not
create a second global timeline or TraceStore. If adding response metadata
would change a consumer contract, keep the new facts in bounded existing trace
rows and HOLD the public response change.

After behavior:

- blank queries stop locally with EMPTY_QUERY;
- default topK is 5 and numeric topK is bounded to 1..20, matching the active
  agent-tool bounded-query convention;
- every attempted provider has a redacted attempt and terminal result record;
- provider-disabled, empty, timeout/rate-limit/exception, and after-filter
  starvation remain distinct;
- a successful fallback does not hide an earlier attempt without a response.

Patch only after demo1-source-edit-three-way-preflight returns stable APPLY,
the current owner/lease/preimage gates pass, and the focused RED is reproduced.
This work unit may not enable web.search, call a live provider, change
credentials, widen scopes, or alter PromptBuilder/CounterEvidence/
EvidenceCoherence classes.

Preserve:

- one causal/counter/coherence chain;
- exactly three canonical counter slots;
- lineage, independence, time, and relation as separate fields;
- copy deduplication and conflict HOLD;
- hash/count/reason telemetry only;
- web.search disabled unless a separate authorized provider task proves its
  registration, policy, credential fail-soft, budget, and wire lineage;
- final prompt construction at PromptBuilder.build(PromptContext).

Do not create Doctor DB, a second web gateway, another prompt builder, a second
CVaR/ensemble implementation, or fake search results.

### WU-E10 — ensemble and final-release boundary

Status: no_patch_needed on the 2026-08-15 focused characterization.

Preserve one existing ensemble final-answer path, optional verification as an
explicit release gate, and PromptBuilder ownership. Reopen only for a new
focused RED that names one active target and demonstrates the responsibility
boundary failure.

### WU-C20-C21 — Spring main chat UI

Status: no_patch_needed on all three current Node contracts.

Preserve:

- /chat and /chat-ui as the Spring-owned UI;
- x-request-id generation and backend-proven x-session-id restoration;
- resilient SSE event/data parsing and bounded unknown-event handling;
- explicit local AbortController stop and server cancel only with a known
  session;
- session list/detail, evidence/status, typed failure and retry states;
- redacted public events and no raw prompt, token, cookie, authorization header,
  trace dump, or sensitive query.

Do not touch the three UI files or related Java files without a new current
target-bound RED. Fresh localhost Browser proof is a separate final runtime
lane; DOM success is not provider or wire success.

### WU-T25 — decision/evidence reconstruction

Status: no_patch_needed.

Keep DecisionEvidenceReconstructionValidator on the existing DecisionTraceAspect,
TraceStore, and DebugEventStore seam. Preserve request/options hashes, attempt
and response lineage, relation integrity, missing-evidence reasons, and bounded
metrics. Do not create a Python production duplicate, graph database, or second
trace store.

### WU-H30, WU-S40, WU-S50 — inherited conditional units

Status: evidence_needed; not blocked by WU-I00.

Do not patch from the predecessor prose. For each unit:

1. rebind exact current targets and owner;
2. run the named characterization command;
3. if GREEN, record no_patch_needed;
4. if RED, use demo1-source-edit-three-way-preflight before any application
   source write;
5. acquire one existing Desktop source lease, freeze preimages, apply the
   smallest target-local patch, rerun focused GREEN, perform a count-only secret
   scan, record postimages and rollback, then release the lease.

H30 may only repair deterministic local smoke URL/port selection and temporary
process-environment restoration. S40 may only preserve existing source guard,
authentication, HTTPS, diagnostics, admin-token, and non-web boot boundaries.
S50 must keep the default next action local and make Browser/provider/Supabase/
MCP evidence explicit, read-only, opt-in, and secret-safe.

### WU-QWEN-MAC — read-only capability lane

Status: hold / evidence_needed; not a source work unit.

If separately requested, inspect current GPU name/total/free memory, driver,
Ollama version/listeners/health, exact official Qwen artifact compatibility,
and target-Mac Shortcuts/F13/TCC/clipboard facts. Do not download a model,
install or update an engine, start/stop a process, persist environment values,
change TCC, or create a screenshot without later explicit approval. Vendor
scores remain vendor-reported only.

### WU-G0 — final Desktop proof

Status: evidence_needed.

Run the broad Desktop test and control-plane topology wrapper only after all
required local units are green or no_patch_needed. Notebook's two claimed
5,891-test runs are supporting_only. Provider, Browser, Computer, Supabase, and
Mac proof stays lane-specific and cannot be inferred from the broad local build.

## Paste-ready Desktop execution prompt

~~~text
@superpowers

Canonical root: C:\AbandonWare\demo-1\demo-1\src
Canonical program:
agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260815.md

Read AGENTS.md and the canonical program first. Treat Y:\ and every retired or
remaining Notebook handoff as supporting_only. Do not use another source root.

Primary goal:
Execute only the still-required work units in this canonical program. Treat the
frozen 2026-08-06 controller mismatch as a historical integrity result and do
not patch, adopt, or hand-edit that controller/state. Preserve the current
application-source hunks and accept the recorded fresh GREEN boundaries as
no_patch_needed unless a later target-specific command produces a new RED.

Current facts to verify, not assume:
- branch codex/owned-runtime-browser-restart
- HEAD 0796a3c5b29bbb08c3314bd40649d856d4a7bce6
- Java 17 and Node 24
- root main/java + main/resources
- :app app/src/main/java_clean + app/src/main/resources
- no index lock, no top-level PatchDrop patch, no active source lease
- related source targets are dirty or untracked

Reconfirm WU-I00 read-only if inventory identity changes. Do not hand-edit
program-state.json or events.jsonl and do not alter the persistent zero-byte
mutex leaves. Do not retrofit the historical controller to this program.

Rerun the focused tests listed in Verification commands. Record E10, C20-C21,
and T25 as no_patch_needed when they remain GREEN. For WU-R70,
add/reproduce only the four target-owned RED cases named by the canonical
program, then run the source-edit three-way preflight and patch the smallest
WebSearchTool/Acme seam. Do not rewrite unrelated sources to make the diff look
active.

For H30, S40, and S50, characterize one unit at a time. A source edit is allowed
only after a new focused RED, the repository three-way preflight returns stable
APPLY, exact current target ownership and preimages are frozen, and the existing
Desktop source lease is acquired. Preserve unrelated dirty work.

Keep web.search disabled. Do not call a provider, Browser, Supabase, Mac, or
Computer lane for decorative proof. Browser DOM output never proves provider
lineage. Do not install, pull, start, stop, commit, push, deploy, mutate a
database, persist credentials, or change public APIs/environment names.

The original six direct-child leaves were retired on 2026-08-15 after the
canonical path/hash was disclosed and every immediate pre-delete SHA-256 matched
this inventory. The explicit current user goal overrode the default
required-GREEN retirement timing gate without authorizing source mutation. Do
not recreate the retired leaves. Never delete or modify the four excluded
subtrees through this program.

Final report:
- canonical path and SHA-256
- included/supporting/excluded counts
- requirement status counts
- each work-unit command and outcome
- retired and held counts
- desktopFinalProof and runtimeLineageVerdict
- at most one evidence_needed action
~~~

## Verification commands

Run Gradle once, serially, with Desktop-local caches.

~~~powershell
$env:AWX_AGENT_HOST='desktop'
$env:AWX_SPLIT_BUILD_OUTPUTS='1'
$env:AWX_BUILD_HOST_ID='desktop'
$env:GRADLE_USER_HOME=Join-Path $env:USERPROFILE '.gradle-awx-desktop'
$projectCache=Join-Path $env:LOCALAPPDATA 'awx-gradle-project-cache\desktop-notebook-consolidation-20260815'
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME,$projectCache | Out-Null

$gradleArgs=@(
'checkSourceSetHygiene',
'checkLangchain4jVersionPurity',
'test',
'--no-daemon',
'--console=plain',
'--project-cache-dir',$projectCache,
'--tests','com.abandonware.ai.agent.tool.impl.ops.CausalProbeEvaluateToolTest',
'--tests','com.abandonware.ai.agent.tool.impl.ops.CounterEvidenceRetrieveToolTest',
'--tests','com.abandonware.ai.agent.tool.impl.ops.EvidenceCoherenceVerifyToolTest',
'--tests','com.abandonware.ai.agent.tool.AgentWebSearchToolConditionalWiringTest',
'--tests','com.abandonware.ai.agent.integrations.AcmeAICoreGatewayTraceTest',
'--tests','com.example.lms.prompt.PromptBuilderBoundaryTest',
'--tests','com.example.lms.cfvm.DecisionEvidenceReconstructionValidatorTest',
'--tests','com.example.lms.ensemble.DiverseSamplingOrchestratorTest',
'--tests','com.example.lms.ensemble.EnsembleFinalAnswerServiceTest',
'--tests','com.example.lms.service.ChatWorkflowFinalVerificationReleaseGateTest'
)
& .\gradlew.bat @gradleArgs

node scripts/chat_ui_browser_fault_fixture_tests.js
node scripts/chat_ui_stream_contract_tests.js
node scripts/chat_ui_view_layer_contract_tests.js

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program_public_contract.tests.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/consolidated_program_core.tests.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/consolidated_program_retirement_core.tests.ps1
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/tests/invoke_consolidated_program.tests.ps1

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .agents/skills/demo1-consolidating-notebook-directives/scripts/invoke_consolidated_program.ps1 -Action Status -Root C:\AbandonWare\demo-1\demo-1\src
~~~

Current expected result:

- the existing Gradle characterization and three Node commands: GREEN; the new
  WU-R70 RED cases must fail before its patch and pass afterward.
- public/core/retirement-core tests: GREEN.
- the historical full-controller test and Status: expected fail-closed
  candidate-inventory-mismatch; diagnostic only for this program.

## RetirementManifest

~~~yaml
schemaVersion: demo1.notebook-directive-retirement.v1
deleteAuthorized: true
canonicalDirectivePath: agent-prompts/awx_desktop_notebook_consolidated_source_directive_20260815.md
canonicalDirectiveSha256Evidence: 49A6185BAFF696D809530C638D46F63AF79CE3D8E97AE5190596563FCD5D6737
allRequiredWorkUnitsGreen: false
desktopFinalProof: evidence_needed
retirementGateOverride: explicit_user_goal_delete_after_delivery
retirementVerification: verified
status: retired
firstHoldReason: null
items:
  - path: data/agent-handoff/notebook/2026-08-02-rag-tail-web-goal-directive.json
    preimageSha256: 702CC1E37440D1F433AC5EA80CCC8F98CAD9FF2A0EB6C788233381DE6712FA44
    coverage: [WU-R70, WU-G0]
    eligibility: eligible
    deletionResult: removed
  - path: data/agent-handoff/notebook/2026-08-05-desktop-browser-main-chatbot-source-directive.md
    preimageSha256: BB018F590FCFBD7E1B02570FC93B433209CF3C1707B500BAAED0001928FEC05B
    coverage: [WU-C20-C21, WU-G0]
    eligibility: eligible
    deletionResult: removed
  - path: data/agent-handoff/notebook/2026-08-07-desktop-consolidated-source-program-superpowers.md
    preimageSha256: 3D5E8B79B23CC1A713CE432806654193D5C6D770A47B52B561151ADA3435CB0F
    coverage: [WU-I00, WU-H30, WU-S40, WU-S50, WU-G0]
    eligibility: eligible
    deletionResult: removed
  - path: data/agent-handoff/notebook/g0-desktop-verification-20260811.json
    preimageSha256: 62704B33741D061FC8FDEFD1207FC653ECD7F92E12E0C129E449A2E6B93CD7C5
    coverage: [WU-G0]
    eligibility: eligible
    deletionResult: removed
  - path: data/agent-handoff/notebook/qwen38-macos-web-probe-desktop-20260815.md
    preimageSha256: 9656726346C78F2ADAADC9874EBA5575BCD5BB72DD8E0F29DCEB49CCFDCBB8FC
    coverage: [WU-QWEN-MAC]
    eligibility: eligible
    deletionResult: removed
  - path: data/agent-handoff/notebook/top25-demo1-evidence-20260814.md
    preimageSha256: DCE74013204D75AF43EBEBE0BA557EE74A98D26E3151853A0F040927D7BD28A0
    coverage: [WU-T25, WU-G0]
    eligibility: eligible
    deletionResult: removed
~~~

All six direct-child preimages matched this inventory immediately before exact
deletion. Post-delete verification found zero remaining targets, the canonical
path present, and all four excluded subtrees present. The excluded subtrees are
never deletion targets for this program.

## Completion boundary

The current user goal's artifact scope is complete: one canonical directive is
published, all six exact original leaves are retired, and excluded subtrees are
preserved. Application-source execution is intentionally separate and remains
unauthorized in this run. WU-R70 still records the bounded WebSearchTool/Acme
gap for a future source-edit request; desktopFinalProof and runtimeLineageVerdict
therefore remain evidence_needed/HOLD and no provider or runtime success is
claimed.
