# GPT Pro 200-Hotspot Source Review Handoff Design

Date: 2026-08-14  
Status: written specification approved by the user on 2026-08-14; implementation planning  
Primary process: `superpowers:brainstorming`  

## 1. Purpose

Create one self-contained Markdown document that the user can paste into GPT
Pro. It will contain exactly 200 source-backed design hotspots from the current
`demo-1` checkout and ask GPT Pro to judge where and how the design should be
improved.

This is an advisory review handoff. It does not patch source, prescribe a final
implementation, or claim that every candidate is a confirmed runtime defect.

## 2. User Outcome

The final document must let GPT Pro do all of the following without a separate
repository-orientation conversation:

1. account for every supplied hotspot;
2. challenge weak or duplicated findings rather than accepting the ledger as
   ground truth;
3. identify the canonical active owner and the smallest useful repair seam;
4. propose a focused RED test and regression boundaries;
5. select a Top 20 and a phased repair roadmap;
6. separate source-backed defects from design risks and evidence gaps.

GPT Pro supplies design advice only. It must not output a patch or claim that a
suggested command or test has run.

## 3. Relationship to Existing Designs

This design is a one-off handoff for the user's current exact-200 request.

- `docs/superpowers/specs/2026-08-13-gpt-pro-source-audit-context-design.md`
  planned a 100-row ledger and a separate prompt. For this goal only, the new
  exact-200 single-document contract supersedes those planned outputs. The
  existing file is not edited or deleted.
- `docs/superpowers/specs/2026-08-13-whole-source-shock-auditor-design.md`
  governs a reusable registered prompt-pack. It remains a separate task and is
  not modified.

The only planned deliverable after written-spec approval is:

- `verification/gpt-pro-design-review-200.md`

No manifest, prompt registry, Java source, resource, test, Gradle file, skill,
PatchDrop bundle, application memory, or external system is in scope.

## 4. Authoritative Snapshot

The design-time evidence snapshot is:

```yaml
canonicalRoot: C:\AbandonWare\demo-1\demo-1\src
branch: codex/owned-runtime-browser-restart
head: 0796a3c5b29bbb08c3314bd40649d856d4a7bce6
javaMajor: 17
indexLockPresent: false
activeSourceSets:
  - main/java
  - main/resources
  - src/test/java
  - app/src/main/java_clean
  - app/src/main/resources
activeMainJavaFiles: 2060
activeTestJavaFiles: 972
activeMainJavaFingerprint: 0a20263b9579499343c83963f3059cc98401cce166740cfc8fa8f203919d0281
activeTestJavaFingerprint: 96e89a9238a36be5afea3c7ae6ee3644bf8b62f651dcbb8060459376a6e78357
fingerprintAlgorithm: sha256 of UTF-8 sorted relative-path, tab, file-sha256 lines with a final LF
dirtyStatusCommand: git status --short --untracked-files=all
dirtyEntriesAtLatestCheck: 13914
```

The dirty-entry count is contextual only. It depends on untracked-file
expansion and can change while unrelated work continues. It neither invalidates
read-only findings nor authorizes cleanup. The final artifact must refresh the
branch, HEAD, fingerprints, target hashes, and affected line anchors before it
claims completion.

Fresh design-phase checks established Java 17, Gradle project discovery,
LangChain4j `1.0.1` purity, and source-set hygiene. These checks prove only
their named surfaces. They do not prove runtime reachability, provider calls,
database state, browser behavior, security exploitability, or semantic answer
quality.

## 5. Active-Source Boundary

Rows may cite active production roots, active test roots, and repository-owned
build or verification scripts when the finding concerns build/test authority.
Inactive mirrors, archives, backups, generated outputs, `project/src/main/java`,
and `app/src/main/java` are excluded unless current Gradle evidence selects
them.

A similarly named class is not enough to establish ownership. Each row must
identify a symbol and one of these reachability forms:

- an active caller or endpoint;
- Spring bean selection, qualifier, primary marker, or conditional;
- an active Gradle task or source-set declaration;
- a directly invoked test/verification owner;
- `not_observed`, when runtime or external selection was not proven.

## 6. Candidate Population and Exact-200 Rule

Independent read-only lanes produced this raw pool:

| Origin | Domain | Raw rows |
| --- | --- | ---: |
| `DR` | Chat, prompt, session, and response lifecycle | 34 |
| `RF` | Retrieval, evidence, fusion, and reranking | 38 |
| `OR` | Orchestration, concurrency, resilience, and observability | 36 |
| `MV` | Memory, vector, embedding, and learning lifecycle | 37 |
| `SC` | API, security, configuration, and provider boundaries | 30 |
| `S` | Source ownership, build, architecture, and test authority | 25 |
| **Total** |  | **200** |

The raw count is not the final count. Chat-lane validation already found one
unsupported claim and one duplicate root cause, leaving 198 chat-adjusted rows
before cross-lane comparison. Global validation has also observed overlapping
vector-store root causes between retrieval and memory lanes. Therefore 198 is
an upper bound before global deduplication, not the final independent count.

The final ledger must satisfy all of these rules:

1. primary IDs are exactly `HP-001` through `HP-200`;
2. every primary ID appears exactly once;
3. each row retains its `originId` for traceability; fresh replacements use
   contiguous `RPL-001..RPL-nnn` IDs;
4. rejected rows do not remain merely to preserve the number;
5. duplicate symptoms merge under one `rootCauseKey`;
6. the replacement count equals `200 - globally unique retained rows`; every
   replacement must be a newly source-validated, independent root cause from an
   active surface not already represented;
7. a broad observation cannot be split into several rows to pad the ledger;
8. style-only, naming-only, and file-size-only observations are excluded;
9. if 200 independent rows cannot be supported, generation stops with the
   exact shortage instead of claiming completion.

The six origin allocations are evidence-collection budgets, not final domain
quotas. A replacement is selected for decision value and independence, not to
preserve an arbitrary category total.

## 7. Evidence Classification

Every row uses exactly one classification:

- `CONFIRMED`: current active source directly shows a contract violation,
  invariant break, unsafe default, or internally contradictory behavior. This
  does not by itself claim that the behavior occurred in production.
- `RISK`: current source shows a credible design, lifecycle, maintainability,
  policy, or verification hazard, but its impact depends on runtime selection,
  workload, configuration, or an unstated product contract.
- `HYPOTHESIS`: the suspected failure requires missing runtime, provider,
  database, browser, benchmark, or product-policy evidence.

Classification and severity are independent. A high-impact hypothesis does not
become confirmed because it is severe, and a confirmed local defect need not be
high priority.

## 8. Severity

- `P0`: source-backed security/data-integrity failure, deadlock or unbounded
  resource/cost path, or broad terminal-state violation with a direct active
  chain.
- `P1`: major behavior, policy, availability, prompt, or evidence-integrity gap
  on an active path.
- `P2`: bounded/local defect, substantial maintainability hazard, or material
  verification blind spot.
- `P3`: low-impact cleanup or watch item that still has a concrete behavioral
  consequence.

Severity is an input for GPT Pro to challenge, not a final priority order.

## 9. Ledger Row Contract

The final artifact uses one compact Markdown table row per hotspot with these
fields:

```yaml
id: HP-001..HP-200
originId: DR | RF | OR | MV | SC | S identifier, or contiguous RPL identifier
domain: bounded subsystem label
classification: CONFIRMED | RISK | HYPOTHESIS
severity: P0 | P1 | P2 | P3
rootCauseKey: stable canonical-owner and behavior key
location: repository-relative file:line
symbol: class plus method, field, task, or configuration key
observation: source-backed fact with no inferred runtime claim
impact: bounded consequence or uncertainty
askGPTPro: specific question about owner, repair seam, and evidence
```

Table cells must be single-line and escape literal pipes. Raw prompts,
responses, credentials, cookies, headers, private environment values, and full
error bodies are forbidden.

## 10. Known Validation Corrections

The final ledger must carry forward these corrections rather than reproducing
the raw audit wording:

- `DR-021` is rejected because the claimed lineage divergence was not supported
  by the inspected source.
- `DR-027` merges into `DR-028` as one unversioned, non-allowlisted
  `sessionMeta` root cause.
- `DR-009`, `DR-013`, `DR-018`, `DR-020`, `DR-023`, `DR-024`, `DR-029`,
  `DR-030`, and `DR-033` are `RISK`, not confirmed defects.
- `main/java/com/example/lms/service/rag/test_mod.java` is valid Java but a
  package-less production-tree residue. It is at most a `P2` structural risk,
  not a syntax failure.
- `main/java/service/rag/rerank/DppDiversityReranker.java` is a comment-only
  deprecation marker. It is not a compilation defect and must not be presented
  as one.

PowerShell `Get-Content` can return a scalar for a one-line file. All final
whole-file validation must normalize reads with `@(...)` before indexing. A
single-character index result is never accepted as the file body.

## 11. High-Risk Manual Re-Read Set

At minimum, these source-backed candidates must be re-read immediately before
the final handoff because they influence `P0` or early `P1` ordering:

1. timed `invokeAll` uses `Future.cancel(false)` while a downstream consumer can
   call unbounded `Future.get()` on a non-cancelled future;
2. `whitelistOnly` retrieval can skip whitelist filtering in aggressive or
   memory-profile-none paths;
3. a strike filter returns the original input when every candidate is removed;
4. embedding fingerprint mismatch can fall back to a different dominant
   fingerprint or raw top-K results.

The final rows must cite their exact active symbols and refreshed lines. This
section records validation priority, not a final GPT Pro verdict.

## 12. GPT Pro Master Prompt Contract

The final document starts with a Korean master prompt. Exact source identifiers,
schema keys, and verdict tokens remain English. GPT Pro is instructed to:

1. treat all 200 rows as claims to adjudicate, not established truth;
2. first return a coverage decision for every `HP` ID;
3. assign exactly one verdict per row:
   `ACCEPT | DOWNGRADE | REJECT | MERGE`;
4. preserve rejected and merged IDs in the accounting table with reasons;
5. identify the `canonicalOwner` and `rootCauseId` for retained rows;
6. describe the smallest behavior change and `minimalFiles`, without code;
7. provide one focused `redTest` and relevant `regressionRisks`;
8. name missing `evidenceNeeded` without inventing runtime facts;
9. produce a Top 20 after full coverage, deduplicated by root cause;
10. produce a phased roadmap ordered by dependencies and verification cost.

For each row, GPT Pro's output schema is:

```yaml
id: HP-nnn
verdict: ACCEPT | DOWNGRADE | REJECT | MERGE
reason: concise evidence-based rationale
mergeInto: HP-nnn | null
correctedClass: CONFIRMED | RISK | HYPOTHESIS
correctedSeverity: P0 | P1 | P2 | P3
rootCauseId: stable identifier
canonicalOwner: exact active symbol
suggestedChange: behavioral change only
minimalFiles: bounded list
redTest: focused failing assertion
regressionRisks: bounded list
evidenceNeeded: exact missing artifact and one verification action, or none
```

If any ID is missing from the coverage response, GPT Pro must report
`context_coverage_incomplete` and stop ranking rather than prioritize a partial
ledger.

## 13. Attention and Ordering Controls

The single final document is ordered as follows:

1. authority, snapshot, and evidence limitations;
2. GPT Pro instructions and response schema;
3. domain/count index;
4. exactly 200 compact ledger rows;
5. required coverage check;
6. Top 20 and phased-roadmap request.

Rows are grouped by domain and given contiguous `HP` IDs. Severity does not
control presentation order; this avoids pre-ranking the entire answer before
GPT Pro has adjudicated duplicates and confidence.

## 14. Duplicate, Drift, and Error Handling

- Duplicate `rootCauseKey`: merge before assigning final `HP` IDs.
- Missing or inactive target: reject the row and select an independent validated
  replacement.
- Line drift with stable symbol: relocate the symbol, refresh the line and
  target hash, then revalidate the observation.
- Changed behavior: reclassify or reject; never preserve stale wording.
- Runtime effect not directly established: downgrade to `RISK` or `HYPOTHESIS`.
- Missing external evidence: record `not_observed`; do not call the external
  system for decorative proof.
- Fingerprint or source-set change: stop affected-row generation, refresh the
  ownership map, and continue only after validation.
- Candidate shortage: report `evidence_needed: independent hotspot shortage /`
  `verify with a fresh active-source review`; do not pad.

## 15. Final Artifact Verification

Generation is complete only when all checks pass:

1. the ledger has exactly 200 data rows;
2. primary IDs are exactly the set `HP-001..HP-200` with no gaps or duplicates;
3. every row has all required fields and one allowed class/severity;
4. every cited path exists in an allowed active or build/test-authority surface;
5. every cited line is within the file and the named symbol exists in a bounded
   surrounding window;
6. every `CONFIRMED` observation is supported by the cited source without using
   its `impact` as evidence;
7. no two rows share the same canonical `rootCauseKey`;
8. every `P0` and security-boundary row receives a manual whole-file re-read;
9. every replacement row is independently validated and is not a split symptom
   of an existing row;
10. the prompt requires all four verdicts, full ID coverage, Top 20, and a phased
    roadmap;
11. no placeholder marker, unresolved variable, or invented command result
    remains;
12. a count-only secret-shaped scan finds no exposed secret values;
13. the final artifact records refreshed branch, HEAD, source fingerprints, and
    generation time;
14. the final diff for this task contains only the approved Markdown artifacts;
15. no application source, tests, configuration, Git index, runtime process, or
    external service is mutated.

No Gradle or boot run is required solely to generate the Markdown handoff.
Existing command results remain bounded evidence, and a changed target is
re-read rather than masked by an unrelated green build.

## 16. Completion and Next Step

The user approved this written specification on 2026-08-14. The
`superpowers:writing-plans` step creates the bounded generation plan; the
selected execution workflow then produces
`verification/gpt-pro-design-review-200.md` and runs the checks in Section 15.

Superpowers ordinarily requests a design-document commit. Repository rules
require separate operation-level commit authority, which has not been granted.
This document is therefore neither staged nor committed.
