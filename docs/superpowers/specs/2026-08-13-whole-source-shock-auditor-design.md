# Whole-Source Shock Auditor Prompt Design

Date: 2026-08-13
Status: approved by the user on 2026-08-13
Primary route: `kind=prompt-pack`, `canonicalId=demo1_orch_patch_scanner`

## 1. Purpose

Upgrade the existing `demo1_orch_patch_scanner` prompt into a reusable,
evidence-first whole-source auditor for Desktop Codex. The auditor must find
design contradictions, split authority, high-impact shock paths, verification
blind spots, and narrow improvement seams without editing application source.

The resulting prompt is intended to be pasted into or selected by Codex. Its
output must be decision-ready: every ranked candidate includes active-owner
proof, an observation/inference split, a bounded counter-evidence search
result, a falsifier, measured risk, and a Codex-ready repair directive that
stops before mutation.

## 2. Current Problem

The registered prompt already scans AOP, TraceStore, fail-soft behavior, simple
class-name duplicates, and request identifiers. Its current heuristics are too
weak for the requested audit:

- raw text occurrence counts can mistake intentional retries for double invoke;
- simple class-name duplication does not prove active FQCN or bean conflict;
- empty returns do not distinguish provider-disabled, normal-empty,
  after-filter starvation, cancellation, or timeout;
- the current score mixes patch convenience with defect severity;
- it emits Mac mini PatchDrop tasks even though this request targets Codex;
- it writes `__reports__` by default despite the selected read-only boundary;
- it does not require counter-evidence or a falsifying test;
- it does not test whether a green task actually owns the relevant tests, such
  as Gradle reporting `NO-SOURCE` while test files exist.

## 3. Chosen Approach

Modify the existing registered prompt rather than add another overlapping
route. Keep these identities unchanged:

- source: `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md`
- manifest ID: `demo1_orch_patch_scanner`
- generated output: `agent-prompts/out/demo1_orch_patch_scanner.prompt`

The manifest entry remains untouched unless current live evidence proves it is
invalid. This avoids overlapping scanners and preserves the typed routing
contract in `.agents/skills/INDEX.md`.

### Rejected alternatives

1. A new `whole_source_shock_auditor` prompt would provide a clean slate but
   duplicate the existing scanner's authority.
2. A one-off chat response would be immediately useful but would not provide a
   repeatable audit contract or stable output schema.
3. An autonomous patch loop would exceed the approved read-only authority and
   would require the application-source three-way preflight.

## 4. Authority Boundary

The prompt operates in read-only audit mode.

Its human-facing instructions and report prose are Korean. Repository
identifiers, commands, reason codes, and schema keys remain exact English text
so Codex can search and execute them without translation drift.

Allowed:

- bounded source, resource, test, build, and configuration reads;
- read-only Git inspection;
- active sourceSet and Gradle project discovery;
- isolated, decision-changing Gradle verification using temporary caches and
  temporary build output;
- count-only and hash-only summaries;
- independent read-only evidence lanes when multi-agent support exists;
- Codex repair directives containing target seams and RED/GREEN contracts.

Forbidden:

- application-source, test, configuration, prompt, report, or PatchDrop writes;
- staging, committing, pushing, deploying, or changing branches;
- external provider, database, Supabase, Browser, or production calls;
- starting or stopping runtime processes;
- raw query, prompt, response, credential, environment, or error-body output;
- generating Mac mini work orders;
- treating delivery, HTTP success, terminal output, or hashes as semantic or
  provider-attempt proof.

The prompt itself is a Markdown artifact. Updating it does not authorize the
Codex instance that later runs it to modify source.

## 5. Evidence Snapshot

At the start of each audit, Codex freezes one redacted `EvidenceSnapshot`:

```yaml
capturedAt: ISO-8601
canonicalRoot: C:\AbandonWare\demo-1\demo-1\src
branch: string
head: git-object-id
agentsHash: sha256
routingIndexHash: sha256
scannerPromptHash: sha256
activeSourceSets:
  - main/java
  - main/resources
  - src/test/java
  - app/src/main/java_clean
  - app/src/main/resources
settingsAuthority: observed-file
javaMajor: 17
dirtyCounts:
  modified: integer
  deleted: integer
  untracked: integer
indexLockPresent: boolean
topLevelPatchCount: integer
activeLeaseCount: integer
```

The snapshot records counts and hashes, not the raw dirty-file list or share
mapping. Active roots are defaults until Gradle evidence confirms them.
`project/src/main/java`, `app/src/main/java`, `demo-1`, `lms-core`, archives,
backups, and generated outputs remain inactive or reference-only unless current
Gradle evidence explicitly selects them.

Before final ranking, Codex rechecks `AGENTS.md`, `.agents/skills/INDEX.md`, the
scanner prompt, and each proposed target preimage. A changed global authority
file invalidates the affected interpretation. A changed target invalidates only
that candidate. Unaffected read-only findings continue.

## 6. Audit Components

### 6.1 Authority Mapper

Trace active flows from entry point to terminal owner:

```text
UI or API
  -> controller
  -> service or workflow
  -> PromptBuilder boundary
  -> retrieval and rerank
  -> provider or model caller
  -> stream or response
  -> persistence and restore
```

For each role, record:

- active sourceSet;
- bean name, `@Primary`, `@Qualifier`, conditional, and feature flag;
- property prefix and default;
- endpoint or caller selection;
- aliases or inactive mirrors;
- the exact source pointer that proves selection.

Names alone do not prove authority. An implementation becomes an active owner
only when wiring, a live call path, or a focused context test selects it.

### 6.2 Contradiction Analyzer

Compare declared contracts with observed behavior. Required categories:

- endpoint split: apparently equivalent endpoints use different pipelines;
- bean split: qualified and unqualified injection select different owners;
- prompt split: final model messages change outside the canonical builder;
- policy split: filters apply to one evidence class but not another;
- lifecycle split: cancel or timeout terminal state differs from worker/wire
  termination;
- verification split: tests exist but the invoked task does not execute them;
- persistence split: UI/session choices are not restored by the authoritative
  metadata path;
- evidence split: visible success exists without provider-attempt evidence.

### 6.3 Shock Analyzer

Inspect bounded concurrency and failure-propagation surfaces:

- `Future`, `CompletableFuture`, Reactor, executors, common pools, and queues;
- cancellation and interrupt conversion;
- deadline and time-budget edge values, including zero;
- fan-out width, retry count, AOP `proceed()` amplification, and fallback ladders;
- breaker OPEN admission behavior;
- replay capacity and reconnect identity;
- cross-request mutable context and trace ownership.

Do not infer task termination from a cancellation counter. Do not infer wire
termination without an observed client exchange or equivalent provider seam.

### 6.4 Prompt and Evidence Integrity Analyzer

Inspect:

- `PromptBuilder.build(PromptContext)` and equivalent instruction boundaries;
- messages inserted after the builder result;
- Web, Vector, KG, and BM25 evidence-policy symmetry;
- query rewrite preservation of domain, `only`, negation, privacy, and temporal
  constraints;
- citation and evidence appendix survival after every postprocessor;
- persisted system metadata that can re-enter chat history;
- UTF-8 and mojibake in active runtime strings;
- redaction of query, prompt, response, token, URL, and provider errors.

### 6.5 Verification Authority Analyzer

Separate these claims:

- source file exists;
- test file exists;
- Gradle discovers the test sourceSet;
- the requested test executes;
- the assertion covers the candidate;
- compiled/JAR/runtime behavior was observed.

`BUILD SUCCESSFUL`, `NO-SOURCE`, and a focused green test have different
meanings and must be reported separately.

### 6.6 Quantitative Pressure Analyzer

Measure only active roots and report at least:

- production files and physical LOC;
- files over 1,000 and 2,000 LOC and their LOC share;
- cross-subsystem file count and LOC share;
- aspect count, size, and duplicate order-expression groups;
- catch count, broad-catch ratio, exact empty catches, and local breadcrumb
  coverage under both broad and strict definitions;
- raw duplicate FQCN count and compiled/JAR mitigation status;
- test files, test LOC, and inactive or unowned test trees;
- count-only production secret-pattern results.

Static keyword metrics must be labelled as approximations. They cannot prove a
runtime call path or semantic defect.

## 7. Measurement Model

### 7.1 Primary scores

```text
contradiction:         0..5
authority_dispersion: 0..5
severity:              1..5
likelihood:            1..5
detectability:         1..5
shock_risk = severity * likelihood * (6 - detectability)
blast_radius:          1..5
causal_strength:       1..5
patch_size:            1..5
verification_cost:     1..5
evidence_confidence:   0.00..1.00
```

`shock_risk` ranges from 1 to 125. Higher detectability lowers the score
because the failure is more likely to be caught before impact.

The remaining scales use one direction consistently:

- severity 1 is cosmetic or local degradation; 5 is security, corruption,
  unbounded cost, or broad service failure;
- likelihood 1 requires a rare conjunction; 5 is deterministic or common;
- detectability 1 is silent or misleading; 5 fails early with explicit proof;
- blast radius 1 is one optional path; 5 crosses primary chat/RAG or persisted
  data boundaries;
- causal strength 1 is correlation; 5 is an exact entry-to-effect chain;
- patch size 1 is one narrow seam; 5 crosses multiple owners or subsystems;
- verification cost 1 is a focused static or unit contract; 5 requires runtime
  or external evidence.

### 7.2 Contradiction rubric

| Score | Meaning |
| ---: | --- |
| 0 | observed implementation matches the declared contract |
| 1 | wording or naming drift with one active behavior |
| 2 | redundant surface with explicit selection and equivalent semantics |
| 3 | active paths differ but the difference is explicitly documented |
| 4 | active paths differ and one policy or proof boundary is inconsistent |
| 5 | mutually incompatible behavior or success claims exist on the same user contract |

### 7.3 Authority-dispersion rubric

| Score | Meaning |
| ---: | --- |
| 0 | one active owner and one explicit selection path |
| 1 | one owner plus inactive alias or adapter |
| 2 | multiple definitions with explicit qualifier, flag, or endpoint policy |
| 3 | multiple active owners with documented endpoint separation |
| 4 | implicit selection through `@Primary`, ordering, defaults, or fallback |
| 5 | selection changes by caller or configuration without a single authoritative contract |

### 7.4 Evidence-confidence rubric

| Range | Evidence |
| ---: | --- |
| 0.95–1.00 | active call/wiring path plus focused command, test, or runtime observation |
| 0.85–0.94 | active source and unambiguous static call/wiring path |
| 0.70–0.84 | active-source pattern with incomplete selection or execution proof |
| below 0.70 | insufficient for a patch candidate; return `HOLD` or `evidence_needed` |

High confidence does not mean high risk. Each dimension remains independent.

### 7.5 Priority classification

```text
P0: evidence_confidence >= 0.85 AND (shock_risk >= 80 OR (contradiction=5 AND authority_dispersion>=4 AND blast_radius>=4))
P1: evidence_confidence >= 0.80 AND NOT P0 AND (shock_risk 48..79 OR active policy/security/prompt boundary gap)
P2: evidence_confidence >= 0.70 AND NOT P0/P1 AND shock_risk 24..47 AND active owner AND bounded falsifier
P3: evidence_confidence >= 0.70 AND shock_risk 1..23; WATCH only
HOLD: below the confidence threshold for the otherwise-matching priority band, or weak evidence/owner ambiguity/moving preimage/missing product policy/unavailable proof
```

A candidate that otherwise matches a priority band but is below its confidence
threshold becomes `HOLD`; it is never silently assigned a lower priority. `P3`
is a measured watch item and does not generate a repair directive by default.
- `REJECT`: inactive source, counter-evidence fully explains the observation,
  or the falsifier disproves the candidate.

Priority is not permission to patch.

## 8. Candidate Dossier

Every retained candidate uses this schema:

```yaml
candidateId: stable-slug
title: short statement
priority: P0 | P1 | P2 | P3 | HOLD | REJECT
activeOwner:
  entryPoint: file:line
  selectedImplementation: file:line
  terminalEffect: file:line
observation: source-backed fact
inference: bounded consequence inferred from the observation
counterEvidenceStatus: found | none_observed | not_checked
counterEvidence: exact contrary fact when found; bounded search record when none_observed; empty only when not_checked
falsifier:
  assertion: exact behavior that would reject the candidate
  command: narrow read-only or test command
scores:
  contradiction: integer
  authorityDispersion: integer
  severity: integer
  likelihood: integer
  detectability: integer
  shockRisk: integer
  blastRadius: integer
  causalStrength: integer
  patchSize: integer
  verificationCost: integer
  evidenceConfidence: decimal
improvementSeam:
  targetFiles: bounded list
  smallestBehaviorChange: prose
  preservedContracts: bounded list
decision: PATCH_DIRECTIVE | WATCH | HOLD | REJECT | NO_PATCH_NEEDED
evidenceNeeded: exact artifact and one verification command, or empty
```

The counter-evidence result has these meanings:

- `found`: an existing guard, test, fallback, or inactive-path fact exists;
- `none_observed`: a bounded search ran and found no contrary fact; the
  candidate remains scoreable and the field must not contain invented
  evidence;
- `not_checked`: the bounded search did not run; scoring is invalid and the
  candidate becomes `HOLD`.

Scores without an observation, active owner, and a `found` or `none_observed`
counter-evidence result are invalid.

## 9. Data Flow

```text
freeze EvidenceSnapshot
  -> confirm active roots and settings authority
  -> map entry points and selected owners
  -> run independent audit lanes
  -> normalize observations into candidate dossiers
  -> run one bounded counter-evidence search per candidate
  -> record counterEvidenceStatus and counterEvidence
  -> move not_checked candidates to HOLD before scoring
  -> define one falsifier per candidate
  -> calculate scores
  -> reject inactive or disproved candidates
  -> recheck global and target preimages
  -> rank retained candidates
  -> emit Codex directives and evidence gaps
```

When multi-agent support is available, lanes may run independently against the
same snapshot. The root Codex remains the only synthesizer. Review count is not
a vote, and one candidate is not stronger merely because several lanes repeat
the same observation.

## 10. Codex Directive Format

For each `PATCH_DIRECTIVE` candidate, emit:

```markdown
## CODEX PATCH DIRECTIVE: CANDIDATE_ID

### Authority proof
- entry point
- active owner
- terminal effect

### Defect contract
- observation
- inference
- counter-evidence status and payload
- falsifier

### Allowed targets
- exact active files

### Forbidden changes
- inactive mirrors, duplicate wrappers, new orchestration frameworks,
  dependencies, secrets, provider substitution, unrelated formatting

### RED contract
- focused failing assertion

### Minimal repair seam
- behavioral constraint, not speculative code

### GREEN and regression ladder
- focused test
- affected boundary tests
- compile or packaging proof where relevant

### Rollback
- exact changed seam to revert

### Remaining evidence
- `evidence_needed`, or `none`
```

The directive does not contain an implementation diff and does not start a
source-edit session.

## 11. Final Report Shape

The report order is fixed:

1. `SUPER_TITLE`
2. `SUPER_TOKEN`
3. `EVIDENCE_SNAPSHOT`
4. `EXECUTIVE_FINDINGS`
5. `AUTHORITY_MAP`
6. `TOP_SHOCKS`
7. `CONTRADICTION_MATRIX`
8. `VERIFICATION_GAPS`
9. `QUANTITATIVE_PRESSURE`
10. `RANKED_IMPROVEMENTS`
11. `CODEX_DIRECTIVES`
12. `REJECTED_OR_DOWNGRADED_CANDIDATES`
13. `EVIDENCE_NEEDED`
14. `NO_PATCH_NEEDED`

The executive section must state separately what was observed, inferred, not
observed, and blocked. It must not collapse static, test, runtime, provider, or
wire evidence into one green status.

`NO_PATCH_NEEDED` records a boolean, rationale, `holdCount`, and
`evidenceNeededCount` and obeys this fail-closed contract:

```text
NO_PATCH_NEEDED=true only when holdCount=0 AND evidenceNeededCount=0 AND every candidate is REJECT or covered by a proven safeguard.
Any HOLD or non-empty EVIDENCE_NEEDED => NO_PATCH_NEEDED=false and rationale=undetermined.
```

## 12. Error and HOLD Handling

- Missing optional history does not block live-source findings.
- Missing named acceptance evidence blocks only the dependent claim.
- A moving target returns `HOLD / snapshot_changed` for that candidate.
- An unclear sourceSet returns `HOLD / source_owner_unproven`.
- A provider result without an observed attempt returns
  `wireAttemptCoverage=not_observed`.
- A green Gradle task with `NO-SOURCE` returns
  `testExecutionAuthority=missing`.
- A counter-evidence search that did not run returns `not_checked`, invalidates
  scoring, and moves only that candidate to `HOLD`.
- A broad scan timeout narrows the path; it does not justify a conclusion.
- A secret-shaped match is reported by count and location category only.
- External credentials, ports, project refs, or runtime owners are never
  guessed.

## 13. Decision-Changing Verification Budget

The prompt should use one primary source-audit lane and at most one
decision-changing verification lane at a time. It may use parallel read-only
subagents for independent evidence collection, but it must not run parallel
Gradle or boot tasks against shared caches.

Default verification ceiling:

- no `bootRun`;
- no full test suite;
- no external call;
- at most one isolated Gradle project/sourceSet gate;
- at most three focused test groups in the entire audit, and only when each one
  changes a top-candidate verdict;
- stop on decisive proof, target change, missing authority, or repeated no-op.

Gradle commands use Java 17, a unique host ID, temporary
`GRADLE_USER_HOME`, temporary `--project-cache-dir`, and temporary
`AWX_BUILD_ROOT_DIR`.

## 14. Implementation Scope

After written-spec approval, implementation changes only:

1. `agent-prompts/agents/demo1_orch_patch_scanner/system_ko.md`
2. generated `agent-prompts/out/demo1_orch_patch_scanner.prompt` as a local
   verification artifact

The already-dirty manifest is read but not edited because its existing entry is
valid. Application Java, resources, tests, Gradle files, scripts, skills,
PatchDrop, and existing reports remain unchanged.

## 15. Prompt-Only Verification

No Gradle build is required for this prompt-only change. Verification must run
after rechecking the source prompt preimage.

```powershell
python -X utf8 agent-prompts\build.py `
  --manifest agent-prompts\prompts.manifest.yaml `
  --agent demo1_orch_patch_scanner
```

Then verify:

1. manifest YAML parses and contains exactly one matching ID;
2. the generated UTF-8 output equals the system prompt because the manifest
   declares no traits and `merge.order=[system]`;
3. required headings and score fields exist;
4. forbidden default-write, auto-patch, and Mac mini task language is absent;
5. no placeholder markers or unresolved template variables remain;
6. changed-file secret scan reports count only;
7. final diff contains only the approved prompt and this spec, excluding the
   generated output when ignored by Git.

Semantic canaries must show that the prompt can represent these known classes
without hard-coding their current verdicts:

- two endpoints selecting different retrieval owners;
- UI cancellation without worker or wire termination proof;
- a test tree present while the Gradle task reports `NO-SOURCE`;
- raw duplicate FQCNs mitigated by a packaging exclusion;
- final response success without provider-attempt evidence;
- a prompt policy applied to Web evidence but not Vector evidence.

## 16. Acceptance Criteria

The implementation is acceptable when:

- the existing route and manifest identity remain unique;
- the prompt is read-only by default and contains no implicit report write;
- every retained candidate requires observation, inference,
  `counterEvidenceStatus`, its truthful counter-evidence payload, a falsifier,
  active-owner proof, and numeric scores;
- `none_observed` remains scoreable after a bounded search, while
  `not_checked` invalidates scoring and produces `HOLD`;
- severity is not reduced because a patch is easy;
- confidence is not used as a synonym for risk;
- active and inactive sources are separated before scoring;
- cancellation, timeout, delivery, and provider-attempt evidence are distinct;
- `NO-SOURCE` cannot be reported as test coverage;
- output produces Codex directives rather than Mac mini tasks or code diffs;
- `NO_PATCH_NEEDED=true` is impossible when any candidate is `HOLD` or
  `EVIDENCE_NEEDED` is non-empty;
- prompt build, manifest uniqueness, output equality, structure checks, and
  count-only secret checks pass;
- the final diff preserves all unrelated dirty work.

## 17. Process and Repository Authority

```text
superpowers: supporting_process
repoEvidence: authoritative
decision: use the approved design workflow, preserve the existing prompt-pack
          route, and do not commit without explicit user authorization
evidence_needed: none; written spec approved on 2026-08-13
```

Superpowers requests a design-document commit, but repository rules prohibit
committing without explicit user authorization. The document is therefore
written and reviewed without staging or committing.
