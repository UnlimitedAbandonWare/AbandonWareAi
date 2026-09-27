# Demo-1 Source-Edit Three-Way Preflight Design

## Status and authority

- Date: 2026-07-31
- Status: user-approved design; implementation not started
- Request class: `prompt_skill_tooling_only`
- Initial rollout scope: `demo-1` repository only
- Evidence snapshot: `sha256:ea1657501fa2bf26afd67367fc1e7abe3a2c34707038b8319a86161d446558cb`
- Design verdict: `APPLY`
- Implementation verdict: `HOLD` until Desktop preflight and a separate implementation-plan approval
- Goal score: `68.25/100`
- Process support: `superpowers:brainstorming`
- Source authority: live repository evidence and the closest `AGENTS.md`

This document specifies a repository-local Codex workflow. It does not authorize
application-source edits, global Codex configuration changes, commits, pushes,
deployments, database mutations, provider changes, or credential changes.

## Problem

Source-edit turns can consume more tokens because of several independent factors:

- higher reasoning effort;
- repeated source scans and repeated full-log ingestion;
- long instruction or prompt-pack expansion;
- retry and verification loops;
- additional subagents and tool work;
- model routing changes.

The repository has no paired `turn.token_usage` ledger that isolates these factors.
The claim that GPT-5.6 Sol or Luna caused a specific increase is therefore
`evidence_needed`, not a design premise. Official Codex guidance states that higher
reasoning effort increases token usage and that subagent workflows consume more
tokens than comparable single-agent runs. Luna is positioned for fast, high-volume,
lower-cost work; its availability alone does not establish higher total token use.

The repository already contains a registered three-way prompt pack, a repo-local
`demo1-agentic-chat-postprocess` skill, contract tests, and a sealed design grader.
The missing capability is a small, source-edit-specific automatic trigger and a
paired measurement gate. Building a second review framework would duplicate tested
assets and increase context cost.

## Goals

1. Detect explicit application-source modification intent in a Desktop Codex turn.
2. Freeze one redacted EvidenceSnapshot and bind exactly three logical review
   packets to its hash.
3. Produce `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY`; never add a
   fourth reviewer or majority vote.
4. Allow source mutation only after a stable `APPLY` verdict and the existing
   source-owner guard.
5. Minimize incremental context, model calls, repeated scans, and raw artifact
   ingestion.
6. Measure workflow effects separately from model and reasoning-effort effects.
7. Roll out in `demo-1` first and expand only after a paired token/quality gate.

## Non-goals

- Proving that a model-family change caused historical token growth.
- Running three physical subagents on every edit.
- Replacing the existing MacSrc lease/CAS or PatchDrop mutation protocols.
- Loading the full chatbot prompt pack for every source modification.
- Changing application source, Gradle sourceSets, PromptBuilder, providers, public
  APIs, databases, schemas, credentials, or environment-variable names.
- Persisting raw prompts, source snippets, full responses, full logs, or provider
  errors.
- Installing a global plugin or global hook during the first rollout.

## Current evidence

- Notebook workspace: `Y:\`, backed by `\\DESKTOP-M5NOV6K\MacSrc`.
- SMB mode for this design pass: `SMB_ACCESS`.
- Filesystem `.git/HEAD`: `refs/heads/main`.
- Trusted Git branch/status/worktree evidence: unavailable because Git reports
  `dubious ownership`; global `safe.directory` was not changed.
- `.git/index.lock`: absent at observation time.
- Root active sourceSet: `main/java`, `main/resources`.
- `:app` active sourceSet: `app/src/main/java_clean`,
  `app/src/main/resources`.
- Existing three-way prompt-pack files: 9.
- Existing prompt-pack registration references: 25.
- Existing three-way contract tests: 14 passed, 0 failed.
- Sealed design grader: `designVerdict=APPLY`, `metaScore=100`, false accept 0,
  canonical false reject 0, observed secret-pattern hits 0.
- Project `.codex` files: 0; automatic source-edit hook is not currently configured.
- Top-level PatchDrop v3 patch/manifest count: 0/0 at observation time.
- Repository token ledger for this decision: absent.

Notebook evidence is supporting evidence. Desktop must re-run root, branch, dirty
ownership, hook trust, and verification checks before implementation or completion.

## Considered approaches

### A. Repo-local wrapper plus bounded prompt hook — selected

Add one small source-edit preflight skill, one project-local `UserPromptSubmit`
command hook, a short `AGENTS.md` routing rule, and focused contract tests. Reuse
the canonical packet names and scoring invariants from the existing three-way
assets without loading their full chat-specific prompt pack on every edit.

Benefits:

- project-scoped blast radius;
- implicit and explicit skill activation;
- low runtime output and no extra framework;
- reversible by removing the hook, skill, and routing line;
- compatible with the existing source-owner guards.

Limitation: project-local hooks require Codex trust. `UserPromptSubmit` matchers are
not applied by Codex, so the command itself must perform a high-precision bounded
classification.

### B. `PreToolUse` hard gate on every `apply_patch` — deferred

A tool hook could block an application-source write unless a preflight marker is
present. This is stronger enforcement, but it requires per-turn state, target-path
normalization, transcript correlation, and reliable hook coverage. Codex documents
tool hooks as guardrails rather than a complete enforcement boundary.

This approach is not part of phase 1. It may be reconsidered only if phase-1
telemetry proves that semantic routing misses authorized source edits.

### C. User-global plugin or hook — rejected for initial rollout

A global package would cover all repositories but introduces more false triggers,
context expansion, trust and distribution work, and repository-specific routing
ambiguity. It is eligible only after the `demo-1` paired gate succeeds and a second
design explicitly defines cross-repository policy.

## Architecture

~~~text
UserPromptSubmit
  -> bounded source-edit intent classifier
      -> no match: emit no additional context
      -> match: inject a short instruction naming the repo-local skill
  -> demo1-source-edit-three-way-preflight
      -> freeze one redacted EvidenceSnapshot
      -> POSITIVE_QUERY
      -> NEGATIVE_QUERY over the same snapshot and positive scenario IDs
      -> NEUTRAL_QUERY over forward and reverse presentation order
          -> APPLY: route to the existing source-owner mutation guard
          -> HOLD/REJECT: do not mutate source
~~~

The hook does not call a model, dispatch an agent, scan the repository, or write a
decision artifact. It only classifies the submitted prompt and optionally emits a
short developer-context instruction. The skill owns the three logical roles. The
existing repository guard continues to own leases, preimage verification, focused
verification, postimage hashes, rollback, and Desktop proof.

## Component contracts

### 1. Source-edit intent classifier

Owner: repo-local Codex tooling.

Input: the `UserPromptSubmit` JSON object on stdin.

Positive trigger requires both:

- a modification verb such as `implement`, `modify`, `patch`, `fix`, `change`,
  `edit`, `add`, `remove`, `refactor`, `구현`, `수정`, `패치`, `고쳐`, `변경`,
  `추가`, `삭제`, or `리팩터`; and
- an application-source object or path indication.

Non-triggers include read-only analysis, review, explanation, audit, planning,
status questions, documentation-only changes, generated output, archive inspection,
and commands that merely run tests or builds.

The classifier must:

- finish within 2 seconds;
- read at most 64 KiB from stdin;
- emit at most 1 KiB;
- never persist or echo the raw prompt;
- emit a prompt hash only when a test or redacted metric explicitly needs it;
- return no additional context on a non-trigger;
- return reason code `source-edit-intent` on a trigger;
- return fail-soft for malformed input because malformed hook input does not itself
  authorize source mutation.

The emitted additional context must only instruct Codex to invoke
`$demo1-source-edit-three-way-preflight` before application-source mutation. It must
not embed the full skill or packet schemas.

### 2. Repo-local preflight skill

Proposed name: `demo1-source-edit-three-way-preflight`.

Trigger: explicit Desktop application-source implementation or modification intent
in `demo-1`.

Non-trigger: read-only work, prompt/Markdown-only changes, external evidence
collection, application runtime use without source mutation, and post-patch review.

Dependencies:

- root `AGENTS.md`;
- active sourceSet evidence;
- existing three-way packet field names and goal-score formula;
- the applicable source-owner guard after an `APPLY` verdict.

The skill must not invoke the chat-specific `system_ko.md` pack by default. It may
reuse its compact packet schema and offline tests. Chat artifact grading remains a
separate workflow.

Default process mode is `single-agent-logical-roles`. Exactly three isolated role
passes occur, but no physical subagent is spawned. A later explicitly approved
high-risk profile may use at most two lightweight reviewer subagents for Positive
and Negative, with Neutral retained by the coordinator. Model slugs and reasoning
effort are not hard-coded in phase 1.

### 3. EvidenceSnapshot

Capture once per source-edit decision:

- root identity, branch evidence, index lock, dirty-target ownership;
- active sourceSets and intended relative target set;
- existing failure/test evidence;
- source-owner guard availability;
- model name, reasoning effort, and subagent count when exposed as safe metadata;
- artifact paths, counts, hashes, booleans, and redacted reason codes only.

Bounds:

- at most 20 evidence rows;
- at most 6,000 characters in the model-visible summary;
- large logs, source scans, and test outputs by path plus hash;
- one `evidenceSnapshotHash` shared by all three packets;
- no evidence acquisition after Positive begins.

### 4. POSITIVE_QUERY

Purpose: find valid assumptions, reusable assets, user value, and the smallest
successful path.

Required fields:

~~~text
packetType=POSITIVE_QUERY
candidateGoal
scenarioWorlds[2..4]
  scenarioId
  premise
  causalMechanism
  expectedObservation
  evidenceNeeded
  falsifier
validatedAssumptions
reusableAssets
expectedUserValue
minimalVerification
evidenceIds
unknowns
~~~

Hard output bound: 2,400 characters.

### 5. NEGATIVE_QUERY

Purpose: attack every positive scenario with a distinct counterexample, alternative
cause, ownership/safety risk, cost, and smallest disconfirming probe.

Required fields:

~~~text
packetType=NEGATIVE_QUERY
challengedGoal
scenarioAttacks
  scenarioId
  counterExample
  alternativeCause
  boundaryOrAuthorityRisk
  costAndBlastRadius
  smallestDisconfirmingProbe
  evidenceIds
falsifiers
missingEvidence
safetyRisks
~~~

The scenario ID set must exactly equal the Positive scenario ID set. Hard output
bound: 2,400 characters.

### 6. NEUTRAL_QUERY

Purpose: compare Positive then Negative and Negative then Positive without acquiring
evidence, compute the fixed goal score, and return the only mutation verdict.

Required fields:

~~~text
packetType=NEUTRAL_QUERY
forwardOrder=[POSITIVE_QUERY,NEGATIVE_QUERY]
reverseOrder=[NEGATIVE_QUERY,POSITIVE_QUERY]
forwardVerdict
reverseVerdict
forwardDecisiveEvidenceIds
reverseDecisiveEvidenceIds
orderStable
verdict=APPLY|HOLD|REJECT
selectedOrRewrittenGoal
goalScore
decisiveEvidence
rejectedClaims
nextSingleProof
confidence=L|M|H
~~~

If verdicts or decisive evidence sets differ, `orderStable=false` and
`verdict=HOLD`. A goal score below 50, missing source ownership, missing verification,
or a safety-gate failure also forces HOLD. Hard output bound: 1,800 characters.

### 7. Mutation handoff

An `APPLY` preflight does not itself mutate files. It authorizes the main Codex turn
to enter the already applicable source-owner workflow:

- Desktop canonical edit workflow on Desktop;
- `YDRIVE_SMB_GUARDED_DIRECT` routed to
  `demo1-macsrc-smb-direct-patch` for an explicitly authorized Notebook edit;
- `LOCAL_PRODUCER` and PatchDrop when isolation is selected.

All existing lease, compare-and-swap, target declaration, secret scan, focused
verification, rollback, and Desktop-final-proof gates remain mandatory.

## Token and execution budget

Phase 1 uses exactly three logical packets and zero mandatory subagents.

~~~text
classifierTimeoutSeconds=2
classifierMaxInputBytes=65536
classifierMaxOutputBytes=1024
evidenceRowCountMax=20
evidenceSummaryCharsMax=6000
positivePacketCharsMax=2400
negativePacketCharsMax=2400
neutralPacketCharsMax=1800
preflightWallClockTargetSeconds=120
rawPromptStored=false
rawResponseStored=false
fullLogInlined=false
largeArtifactMode=path-plus-hash
~~~

Character bounds are mechanical safety limits, not claims about exact tokenizer
behavior. Actual input, cached input, output, and reasoning tokens are measured from
Codex telemetry when available.

## Failure handling

| Failure class | Required behavior |
|---|---|
| `automatic-trigger-missing` | Report missing project hook; retain AGENTS/implicit-skill routing evidence only |
| `hook-trust-missing` | Do not claim automatic hook coverage; ask Desktop to review/trust the exact hook hash |
| `source-edit-classifier-timeout` | Emit no authorization; source mutation remains disallowed |
| `snapshot-unfrozen` | HOLD before any query role runs |
| `canonical-query-count-invalid` | HOLD; do not add or remove a reviewer |
| `scenario-coverage-mismatch` | HOLD |
| `order-unstable` | HOLD |
| `tri-preflight-timeout` | HOLD for source mutation |
| `source-owner-unproven` | HOLD |
| `redaction-failed` | REJECT and discard the unsafe artifact |
| `token-regression` | Keep rollout project-local and disable automatic expansion |

Read-only tasks fail soft when the classifier or hook is unavailable because no
source mutation is authorized. Source-mutation decisions fail closed.

## Telemetry and causal evaluation

Use Codex's existing per-turn token metrics when configured. Do not add an API call
or scrape raw transcripts. Record only:

~~~text
runIdHash
taskDeckId
featureMode=off|on
model
reasoningEffort
subagentCount
triggered
neutralVerdict
token.total
token.input
token.cached_input
token.output
token.reasoning_output
toolCallCount
elapsedMs
focusedVerificationPassed
reworkCount
failureClass
~~~

### Phase A: workflow effect

Run at least 20 paired source-edit cases with the same task input, model, reasoning
effort, context policy, and verification commands. Compare feature off versus on.

Release gates:

- `canonicalQueryCount=3` for every triggered case;
- explicit source-edit trigger coverage 100%;
- read-only false-trigger rate at most 5%;
- paired median total-token reduction at least 20%;
- no decrease in focused-test pass rate;
- no increase in safety-gate bypass or undeclared-source-write count.

### Phase B: model effect

Only after phase A, hold the workflow fixed and compare available model/reasoning
profiles on the same locked deck. This phase may distinguish model, reasoning effort,
and fan-out effects. It must not reuse phase-A workflow deltas as a model-causality
claim.

## Test strategy

### RED

1. An explicit Korean source-modification prompt produces no preflight instruction.
2. An explicit English source-modification prompt produces no preflight instruction.
3. A read-only audit prompt incorrectly triggers.
4. Malformed hook JSON echoes prompt content or emits an authorization.
5. Positive and Negative use different snapshot hashes.
6. Negative omits or adds a scenario ID.
7. Forward and reverse neutral evidence sets differ but verdict remains APPLY.
8. A missing source owner or verification command still permits mutation.
9. A packet exceeds its hard character bound.

### GREEN

1. Trigger and non-trigger fixtures classify deterministically.
2. Hook output is at most 1 KiB and contains no raw prompt.
3. Exactly three packets share one snapshot hash.
4. Negative covers the exact Positive scenario set.
5. Neutral derives HOLD for order instability, score below 50, or failed safety
   gates.
6. The skill hands APPLY to the existing mutation workflow without editing source.
7. Existing three-way chat tests and the sealed design grader remain green.
8. Count-only secret scanning reports zero matches for the declared files.

### Required verification commands

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\demo1_source_edit_three_way_preflight_contract_tests.ps1
python .\scripts\test_three_perspective_chat_postprocess.py
python .\scripts\score_three_way_long_tail_design.py --input .\agent-prompts\agents\demo1_three_perspective_chat_postprocess\v2_design_contract.json
powershell -NoProfile -ExecutionPolicy Bypass -File .\.agents\skills\demo1-skill-family-postprocessor\scripts\validate_demo1_skill_family.ps1 -Root . -DiscoverPrefix demo1- -SkillLineBudget 160 -SkillWordBudget 1200 -TrimCandidateCount 5 -SummaryJson
~~~

No Gradle command is required when implementation remains confined to prompt, skill,
hook, and test tooling. If application source is touched, that is an undeclared scope
expansion and this directive must stop.

## Proposed files

### Add

- `.agents/skills/demo1-source-edit-three-way-preflight/SKILL.md`
- `.agents/skills/demo1-source-edit-three-way-preflight/agents/openai.yaml`
- `.agents/skills/demo1-source-edit-three-way-preflight/references/preflight-contract.md`
- `.codex/hooks.json`
- `.codex/hooks/source_edit_triage.ps1`
- `scripts/demo1_source_edit_three_way_preflight_contract_tests.ps1`

### Modify

- `AGENTS.md`: add one short durable routing rule; do not copy the full contract.

### Exclude

- `main/**`, `app/**`, `src/test/**`, runtime resources, Gradle files, generated
  output, archives, backups, PatchDrop queue contents, public DTOs, provider code,
  DB/DDL, credentials, environment configuration, and user-global Codex files.

The implementation plan must verify hook command path resolution from the Desktop
canonical root before writing `.codex/hooks.json`. It must not hard-code `Y:\`, a
UNC share, or the Desktop canonical path into the distributable hook.

## GoalContract

~~~text
goalId=AWX-CODEX-TOKEN-TRIAGE-001
rewrittenUserIntent=Automatically run exactly three bounded source-edit review roles in Desktop Codex and verify token savings without reducing patch quality.
desiredOutcome=Lower repeated context and fan-out cost while preserving source ownership and verification gates.
measurableSuccess=20 paired cases; median total-token reduction >=20%; trigger coverage 100%; false-trigger rate <=5%; no focused-test regression.
nonGoals=historical model-causality claim; mandatory three-subagent fan-out; application-source/public-API/DB/credential change.
authorizedMutationSurface=after implementation approval, repo-local skill/hook/test and one AGENTS routing line only.
prohibitedSurface=application source, global config, commits, pushes, deployment, secrets.
evidenceBaseline=E01-E10 and the frozen snapshot hash.
assumptions=demo-1 first; Desktop can review project hook trust; Codex token metrics can be collected without raw prompt storage.
constraints=exactly three roles; one snapshot; no majority vote; bounded output; artifact by reference; existing mutation guard retained.
verificationOwner=desktop
verificationCommands=the four focused commands in this design.
rollback=remove six added files and the single AGENTS routing line; no application rollback.
stopConditions=dirty ownership; hook trust missing for automatic claim; redaction failure; token regression; accuracy regression; undeclared source write.
timeBudgetMinutes=180 hard cap
goalScore=68.25
verdict=design APPLY; implementation HOLD pending plan approval and Desktop proof
evidence_needed=Desktop root/branch/status/dirty ownership; hook trust; phase-A paired ledger; Desktop final proof.
~~~

## SourceDirective

~~~text
directiveId=SD-CODEX-TRI-PREFLIGHT-001
sourceOwner=desktop
provenRoot=evidence_needed; verify on Desktop canonical checkout
provenBranch=filesystem HEAD main; trusted Git proof evidence_needed
activeSourceSets=root main/java+main/resources; app java_clean+resources; no application source target authorized
targetFiles=the six Add files and one AGENTS modification declared above
callPathOrBoundary=UserPromptSubmit command hook -> bounded classifier -> repo-local skill -> three packets -> NeutralVerdict -> existing source-owner guard
beforeBehavior=registered three-way assets exist but source-edit automatic trigger is absent
afterBehavior=explicit source-edit prompts receive one bounded preflight instruction and exactly three logical review packets before mutation
excludedFilesAndMirrors=application source, inactive mirrors, backups, generated outputs, global config
publicApiChange=forbidden
secretMutation=forbidden
redTest=explicit edit lacks trigger; read-only prompt triggers; packet hashes differ; order instability applies; missing owner applies; packet bound exceeded
greenTest=deterministic classification; exactly three packets; shared hash; stable neutral gate; existing mutation guard handoff; zero secret hits
exactVerificationCommands=the four focused commands in this design
expectedEvidence=counts, hashes, reason codes, bounded packet sizes, paired token types, focused-test result
failureClassifications=automatic-trigger-missing; hook-trust-missing; classifier-timeout; snapshot-unfrozen; query-count-invalid; scenario-coverage-mismatch; order-unstable; token-regression; source-owner-unproven; redaction-failed
rollback=remove added tooling and AGENTS routing line
patchdropContract=none for Desktop-local tooling implementation; Notebook producer requires cumulative v3 and Desktop consumption
desktopFinalProof=evidence_needed
~~~

## Rollout

1. Desktop preflight proves canonical root, branch, status, dirty ownership, index
   lock, and project hook trust state.
2. Implement classifier and RED/GREEN contract tests without touching application
   source.
3. Add the compact repo-local skill and one `AGENTS.md` routing line.
4. Add and review the exact project hook hash.
5. Run focused verification and count-only secret scanning.
6. Run phase-A paired evaluation with the workflow toggled off/on.
7. Keep the feature `demo-1`-local unless every release gate passes.
8. Treat global rollout or a `PreToolUse` hard gate as a new design request.

## Rollback

Remove the six added tooling files and the single `AGENTS.md` routing line, then
restart or open a fresh Codex session so skill and hook discovery refreshes. Disable
or remove trust for the deleted project hook as appropriate. No application source,
database, provider, Gradle, credential, deployment, or PatchDrop rollback is required.

## Success boundary

Passing contract tests proves only that source-edit intent is routed into one bounded
three-query preflight and that failure gates are deterministic. Passing the paired
phase-A gate supports a token-efficiency claim for the locked task deck and model
configuration. It does not prove historical model causality, universal savings across
repositories, provider/runtime lineage, or Desktop application correctness beyond the
focused evidence collected by the existing source-owner workflow.

## Official Codex references

- [Models](https://learn.chatgpt.com/docs/models): GPT-5.6 model roles and reasoning-effort guidance.
- [Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents): subagent availability, routing, and token-cost caveat.
- [Build skills](https://developers.openai.com/plugins/build/skills): skill progressive disclosure and explicit/implicit activation.
- [Hooks](https://learn.chatgpt.com/docs/hooks): `UserPromptSubmit`, additional context, trust, command-handler, and tool-hook behavior.
- [Configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference): per-turn token metrics by total, input, cached input, output, and reasoning output.
