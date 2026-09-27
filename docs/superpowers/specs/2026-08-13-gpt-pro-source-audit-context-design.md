# GPT Pro 100-Row Source Audit Context Pack Design

Date: 2026-08-13  
Status: conversational design approved; written-spec review pending  
Primary route: `kind=skill`, `canonicalId=quantitative-metric-normalizer`

## 1. Purpose

Create a self-contained, copy-paste-ready context package that lets GPT Pro
evaluate the current `demo-1` checkout without first asking the user to explain
the repository. The package must present exactly 100 primary audit rows, tell
GPT Pro which statements are live observations versus design claims or
inferences, and ask it to decide what should be repaired first.

The package is advisory. It does not authorize GPT Pro, Codex, or another agent
to modify application source, tests, configuration, Git state, external
providers, databases, credentials, or production systems.

## 2. User Outcome

The user should be able to attach or paste the final artifact into GPT Pro and
receive, without another source-orientation round:

1. an accounting decision for every one of the 100 supplied IDs;
2. corrected evidence classifications and merged root-cause groups;
3. a Top 10 immediate list and a Top 25 ordered backlog;
4. the smallest active-owner repair seam for each retained priority;
5. focused RED/GREEN verification and stop conditions;
6. a separate list of claims that need runtime, provider, database, browser,
   benchmark, or product-policy evidence.

## 3. Scope and Separation from Existing Work

This design creates a one-off GPT Pro handoff. It does not register a new skill
or prompt-pack and does not modify an existing registered route.

The current untracked design
`docs/superpowers/specs/2026-08-13-whole-source-shock-auditor-design.md`
targets an upgrade of registered prompt-pack `demo1_orch_patch_scanner`. That is
a different task with a different source file, output contract, and mutation
scope. It remains untouched.

Planned artifacts after written-spec approval:

- `docs/audits/2026-08-13-demo1-source-audit-100-ledger.md`: authoritative
  100-row ledger plus reserve appendix;
- `agent-prompts/gpt_pro_demo1_source_audit_100.md`: standalone, pasteable GPT
  Pro context and question contract;
- this design document: artifact contract and acceptance criteria.

No manifest, generated prompt registry, Java source, resource, test, Gradle,
PatchDrop, or application-memory file is in scope.

## 4. Authoritative Evidence Snapshot

The final artifact freezes a redacted snapshot immediately before generation.
The current design-time snapshot is:

```yaml
canonicalRoot: C:\AbandonWare\demo-1\demo-1\src
branch: codex/owned-runtime-browser-restart
head: 0796a3c5b29bbb08c3314bd40649d856d4a7bce6
javaMajor: 17
indexLockPresent: false
dirtyCountAtLatestCheck: 1507
staps:
  pathRole: design_claim_map
  bytes: 286051
  sha256: A4AB90929E7C7541C3B4A9B28A60ED4234578D708F641D3479380FF15CFFBE16
activeSourceSets:
  - main/java
  - main/resources
  - src/test/java
  - app/src/main/java_clean
  - app/src/main/resources
canonicalMainClass: com.example.lms.LmsApplication
```

Fresh design-phase commands established that Gradle project discovery,
`sourceScoreReport`, LangChain4j version purity, and source-set hygiene passed.
Those commands prove only their named surfaces. They do not prove runtime,
semantic answer quality, provider attempts, database state, benchmark gains, or
the correctness of all 100 candidates.

The dirty count moved during the audit. Therefore a line number alone is not a
stable evidence pointer. Every final row must carry branch, HEAD, symbol, and a
non-secret anchor or file hash. If the target hash changes before handoff, the
row is downgraded to `evidence_needed / snapshot_changed` until rechecked.

## 5. Treatment of `staps.txt`

`staps.txt` is a design and claim map, not a description of current behavior.
Live observations are written first; STAPS mapping is attached afterward.

Allowed STAPS statuses are:

- `implemented`: current source supports the claim at the stated evidence
  surface;
- `partial`: only part of the claimed flow is wired or verified;
- `contradicted`: an active owner behaves incompatibly with the claim;
- `stale`: the claim describes an older state already repaired or replaced;
- `not_observed`: the required runtime, provider, database, UI, or benchmark
  evidence was not gathered;
- `not_applicable`: the claim does not govern the candidate.

STAPS wording cannot promote a static observation into a runtime defect.

## 6. Candidate Population and Exact-100 Rule

The read-only audit produced 110 raw non-positive candidates:

| Domain | Raw candidates |
| --- | ---: |
| Build, configuration, and source ownership | 11 |
| RAG core and orchestration | 11 |
| Resilience and observability | 12 |
| AutoLearn and data lifecycle | 12 |
| Security and provider boundaries | 10 |
| Query, evidence, citation, and fusion | 7 |
| Prompt and model routing | 9 |
| Plan DSL and AOP interaction | 9 |
| API, session, and attachment lifecycle | 9 |
| Test and proof blind spots | 10 |
| Storage, KG, and retention | 10 |
| **Total** | **110** |

`API-ARCHIVE-05` and `STKG-02` are the same root cause and become one canonical
row. `PM-07` and `PM-09` remain separate: one bypasses gateway eligibility when
selecting a fallback, while the other bypasses model serveability in
`PolicyBasedModelRouter.escalate`; they have different owners, falsifiers, and
minimal repairs.

After the one definite merge, 109 independent candidates remain. The primary
ledger retains exactly 100. These nine lower-decision-value candidates remain
in a clearly labelled reserve appendix rather than being deleted or falsely
reported as primary defects:

- `AUTO-02`: duplicate modern/legacy auto-configuration metadata;
- `DEP-01`: optional legacy-module LangChain4j purity coverage gap;
- `JAVA-01`: root compatibility declaration versus toolchain evidence gap;
- `RC-07`: HYPERNOVA final gate order requires integration evidence;
- `RC-08`: HYPERNOVA runtime profile activation is not observed;
- `RC-12`: broad God-class maintainability observation;
- `R12`: CFVM process-local telemetry does not prove recovery quality;
- `AL-11`: optional OCR runtime and bounded-input evidence gap;
- `TBL-10`: broad source-text-test dominance without a coverage denominator.

The exact-100 rule is a success condition, not permission to split one defect
or pad the list. `evidence_needed` rows count as audit rows but never as
confirmed defects. Category totals are printed separately.

## 7. Audit Row Schema

Every primary and reserve row uses this schema:

```yaml
id: stable audit ID
classification: confirmed_defect | structural_risk | evidence_needed
priorityInput: P0 | P1 | P2 | P3 | unranked
rootCauseClusterId: stable cluster
uniquenessKey: active-owner and behavior key
snapshot:
  branch: string
  head: git object ID
  capturedAt: ISO-8601
activeOwner:
  sourceSet: exact active root
  path: repository-relative path
  symbol: class and method, field, task, or resource key
  line: advisory line number
  anchorHash: non-secret SHA-256 of the bounded source anchor or file
observation: source-backed fact only
inference: bounded consequence, kept separate from observation
reachability: active caller, bean, endpoint, task, or conditional evidence
counterEvidence: existing guard, test, fallback, or limiting fact
stapsStatus: implemented | partial | contradicted | stale | not_observed | not_applicable
confidence: 0.00..1.00
evidenceSurface: static | build | test | runtime | browser | provider | database | benchmark
falsifier:
  assertion: exact behavior that would reject or downgrade the row
  verification: one focused verification action
verificationClass: local_read_only | local_build | local_test | runtime | external
verificationCost: low | medium | high
minimalRepairSeam: candidate owner and behavioral constraint, never a patch diff
dependencies: bounded list or empty
```

`observation`, `inference`, and `counterEvidence` are mandatory. When no
limiting evidence was found, `counterEvidence` says `none_observed` rather than
remaining blank. A row without an active owner or a falsifier cannot be a
primary confirmed defect.

## 8. Quantitative Normalization

The ledger follows `$quantitative-metric-normalizer` and does not convert the
repository-owned `100 / 100` source score into a whole-design verdict.

GPT Pro receives these independent dimensions:

- severity;
- active-path reachability;
- likelihood;
- blast radius;
- evidence confidence and freshness;
- repair scope;
- verification cost;
- dependency ordering.

Recommended priority is based on those dimensions, not on severity labels or
reviewer count alone. `evidence_needed` is excluded from defect totals, risk
totals, and the default Top 10 unless GPT Pro first identifies new supplied
evidence that promotes it.

## 9. Context Packaging and Attention Control

The standalone GPT Pro artifact is ordered to reduce context loss:

1. immutable authority and privacy rules;
2. one-page repository and STAPS snapshot;
3. domain index and category counts;
4. compact 100-row primary ledger;
5. nine-row reserve appendix;
6. required GPT Pro adjudication and response schema.

Before ranking, GPT Pro must return an ID coverage block containing all 100
primary IDs and mark each as:

```text
confirm | merge | downgrade | reject | hold
```

Merged and rejected IDs remain visible with reasons. If an ID is absent, GPT
Pro must stop prioritization and report `context_coverage_incomplete` rather
than silently rank a partial list.

## 10. GPT Pro Question Contract

The final prompt asks GPT Pro to do the following in order:

1. restate the authoritative source boundary and evidence limitations;
2. account for all 100 IDs;
3. identify duplicates and shared root-cause clusters;
4. challenge observations with supplied counter-evidence and falsifiers;
5. correct classifications and confidence without inventing runtime facts;
6. produce a Top 10 immediate list and a Top 25 ordered backlog, with Top 10 a
   strict subset of Top 25;
7. cap cluster dominance so one subsystem cannot occupy the ranking through
   derived symptoms;
8. give each retained priority one active owner, one minimal repair seam, one
   focused RED assertion, one GREEN/regression ladder, and one stop condition;
9. separate local read/build/test verification from runtime or external work;
10. return unresolved product-policy choices and evidence gaps separately.

GPT Pro proposes actions only. It must not output implementation diffs, create
new frameworks or duplicate owners, rename protected properties, change Spring
Boot or LangChain4j versions, call external systems, or claim that a suggested
test has run.

## 11. Same-Surface Evidence Rules

- Source existence proves source existence only.
- Bean declarations do not prove runtime activation without selection evidence.
- `BUILD SUCCESSFUL` proves only the executed task graph.
- `NO-SOURCE` is not test coverage.
- HTTP or SSE delivery does not prove answer semantics.
- A rendered answer does not prove a provider attempt.
- A provider status or response does not prove quality or citation correctness.
- A trace key does not prove the underlying worker, wire, or database event.
- Hash equality proves byte identity, not semantic success.
- Benchmark improvements require a same-corpus, same-configuration baseline.

Unobserved surfaces remain `not_observed` or `evidence_needed`.

## 12. Privacy and Redaction

The artifacts include no credentials, authorization headers, cookies, private
environment values, raw user prompts, provider responses, full error bodies,
internal share mappings, or exact private data. They use only paths already
required for repository navigation, source symbols, counts, reason codes,
boolean presence, and hashes.

Any secret-shaped match is reported by count and category only. Example
queries used as falsifiers must be synthetic and non-sensitive.

## 13. Error and Drift Handling

- Target anchor changed: `evidence_needed / snapshot_changed` for that row.
- Active sourceSet changed: stop generation and refresh the affected ownership
  map.
- Required attachment hash changed: refresh STAPS mappings before handoff.
- Runtime/provider/database/browser evidence absent: retain the static row but
  do not promote its evidence surface.
- Product policy ambiguous: `hold / product_decision_needed` for the affected
  row only.
- Candidate count falls below 100 after validation: report the exact shortage;
  do not split or pad rows and do not claim completion.
- Candidate count exceeds 100: keep the strongest 100 by decision value and
  preserve all others in the reserve appendix.

## 14. Verification and Acceptance Criteria

After written-spec review, artifact generation is complete only when all of
these checks pass:

1. the primary ledger contains exactly 100 unique IDs;
2. the reserve appendix contains exactly nine unique IDs and none appear in the
   primary set;
3. every primary row contains classification, cluster, uniqueness key, active
   owner, observation, inference, counter-evidence, STAPS status, confidence,
   falsifier, verification class, and minimal seam;
4. every `activeOwner.path` exists at generation time; an intentionally absent
   evidence artifact is recorded separately as `evidence_needed`;
5. branch, HEAD, STAPS hash, snapshot time, and row anchors are present;
6. category totals sum to 100 and `evidence_needed` is not labelled a defect;
7. all 100 IDs appear exactly once in the GPT Pro coverage input;
8. Top 10 is explicitly required to be a subset of Top 25;
9. no placeholder markers, unresolved variables, or invented command results
   remain;
10. count-only secret scanning reports zero exposed secret values;
11. the final diff contains only the approved design, ledger, and standalone
    prompt, while all unrelated dirty work remains unchanged;
12. no commit, staging, push, deployment, source mutation, runtime process, or
    external call occurs without separate authority.

No Gradle or boot proof is needed merely to create these Markdown artifacts.
Existing Gradle evidence is included as bounded context, not rerun or promoted
to whole-repository correctness.

## 15. Review and Next Step

This document records the approved conversational design. Per the Superpowers
workflow, the user reviews this written specification before final artifact
generation. After written-spec approval, invoke `superpowers:writing-plans` to
create the bounded artifact-generation plan, then produce and verify the ledger
and standalone GPT Pro prompt.

Superpowers ordinarily asks for a design-document commit. Repository authority
requires explicit operation-level permission for commits, so this document is
neither staged nor committed.
