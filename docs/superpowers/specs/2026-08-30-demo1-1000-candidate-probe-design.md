# Demo-1 1,000-Candidate Evidence-Gated Probe Design

**Status:** Approved by the user on 2026-08-30

**Objective:** Analyze the active demo-1 source, create about 1,000 replayable random-probe candidates, promote only independently reproduced failures to defects, and spend up to nine hours applying the smallest evidence-backed repairs while keeping a visible Browser verification lane.

## Outcome Contract

The campaign reports four different counts. They must never be merged:

1. `candidateCount`: deterministic static probe records. A candidate is not a defect.
2. `reproducedDefectCount`: candidates with an active call path and a fresh failing test or probe.
3. `fixedCount`: reproduced defects whose focused RED became GREEN after a scoped patch.
4. `holdCount`: candidates blocked by ownership, policy, runtime, provider, or verification evidence.

The requested approximately 1,000 problems is implemented as a maximum of 1,000 deterministic `UNTRIAGED` candidate rows. No row becomes `VALIDATED`, `FIXED`, or a behavioral defect merely because a static pattern matched.

## Current Evidence Baseline

- Active roots proven by Gradle: `main/java`, `main/resources`, `app/src/main/java_clean`, and `app/src/main/resources`.
- Current active Java file count: 2,121.
- Existing detector observations: 113 historical ledger rows, 729 context-purity action candidates, and 213 cross-subsystem pressure candidates. Their sum, 1,055, is a detector-hit count with possible overlap.
- A fixed-seed 1,000-iteration chat-soak dry run selected eight scenario families exactly 125 times each. It did not make model or provider calls and does not prove 1,000 defects.
- Fresh Gradle baseline: LangChain4j purity, source-set hygiene, root compilation, and `chatUiTest` pass.
- Fresh full test baseline: 5,742 tests, one failure, five skipped.
- First stable RED: the DPP injection test watches the legacy six-argument overload while production now invokes the seven-argument stable-document-key overload.
- Separate production RED: `QueryHygieneFilter` is locale-sensitive under `tr-TR`, but the current branch does not own that untracked path.
- Current checkout is authoritative and highly dirty. A HEAD-based new worktree would omit the behavior under review. This campaign therefore stays in the Desktop canonical root and uses the repository source-owner, lease, preimage, and PatchDrop gates instead of inventing a clean baseline.

## Architecture

The first deliverable is an artifact-only Python producer and validator:

- `scripts/random_probe_candidate_ledger.py` discovers active Java files, freezes one Git-status snapshot, derives bounded static probe families, and emits deterministic canonical NDJSON plus a manifest.
- `scripts/validate_random_probe_candidate_ledger.py` independently recomputes every safety, ordering, hash, count, path, and status invariant. It also performs a fail-closed structural check of the historical Markdown ledger without certifying its narrative counts.

Neither tool may write application source, run Gradle, start a runtime, call a Browser, access a network/provider, or mutate Git. Generated artifacts go only to an explicitly selected non-source evidence path.

## CLI Contract

Producer file mode:

```powershell
python scripts\random_probe_candidate_ledger.py `
  --root . `
  --seed 20260830 `
  --max-records 1000 `
  --output var\codex-smoke\goal-random-probe-20260830\random-probe-candidates.ndjson `
  --manifest-output var\codex-smoke\goal-random-probe-20260830\random-probe-candidates.manifest.json
```

Producer preview mode:

```powershell
python scripts\random_probe_candidate_ledger.py `
  --root . `
  --seed 20260830 `
  --max-records 1000 `
  --stdout
```

Exactly one of `--output` and `--stdout` is required. `--manifest-output` is required in file mode and forbidden in stdout mode. The seed must match `[A-Za-z0-9._-]{1,64}`. `--max-records` is bounded to `1..1000`.

Validator:

```powershell
python scripts\validate_random_probe_candidate_ledger.py `
  --input var\codex-smoke\goal-random-probe-20260830\random-probe-candidates.ndjson `
  --manifest var\codex-smoke\goal-random-probe-20260830\random-probe-candidates.manifest.json `
  --expected-max-records 1000
```

Legacy structural check:

```powershell
python scripts\validate_random_probe_candidate_ledger.py `
  --legacy-markdown data\agent-handoff\codex\report\random-probe-100-20260827-ledger.md
```

Successful v1 validation returns exit code `0`. Unsafe, malformed, or inconsistent v1 artifacts return `2`. A legacy Markdown check returns a structured `EVIDENCE_NEEDED` verdict unless a unique current table and matching v1 manifest prove its authoritative range.

## Candidate Universe

The manifest declares four active roots:

```json
[
  "main/java",
  "main/resources",
  "app/src/main/java_clean",
  "app/src/main/resources"
]
```

Only `*.java` paths under the two Java roots produce candidate rows. Resource roots contribute count-only inventory evidence. Inactive mirrors, archives, generated outputs, build directories, `.git`, `app/src/main/java`, and tests never produce rows.

Git status is collected exactly once with a fixed argument-list command. Each active Java path receives one of these tracking states:

- `clean_tracked`
- `modified_tracked`
- `untracked`
- `ignored_or_unknown`

Only clean tracked rows are eligible for later repair promotion. Dirty or untracked rows may appear as `excluded_dirty` inventory evidence, but they are never repair eligible and the producer never edits them.

## Probe Families

Each file receives at most one row per probe family. Multiple line matches collapse into one candidate with bounded sorted `lineHints` and a count-only `matchCount`.

- `broad_catch_without_local_breadcrumb`
- `locale_sensitive_case_normalization`
- `unbounded_collection_or_read`
- `unbounded_input_or_numeric_conversion`
- `optional_dependency_fail_soft_boundary`
- `no_static_pattern`

`no_static_pattern` means only that a source file was deterministically selected for inspection. It is never a suspicion or defect claim.

## Determinism and Identity

`repoPath` preserves the discovered repository-relative canonical casing and uses `/` separators after traversal rejection. Identity and ranking use a separate lower-invariant path value so Windows enumeration is replayable without destroying an executable cross-platform handoff path.

```text
identityPath = lowerInvariant(repoPath with "/" separators)
identityHash = SHA256(UTF8(identityPath + NUL + probeFamily))
candidateId = "rpc1-" + first16(identityHash)
rankKey = SHA256(UTF8(seed + NUL + identityPath + NUL + probeFamily))
```

Rows sort by `(rankKey, normalizedPath, probeFamily)` and are truncated to `maxRecords`. The same source snapshot, status snapshot, seed, and maximum must produce byte-identical NDJSON. `generatedAt` is informational and excluded from canonical manifest replay comparisons.

`pathOrderHash` hashes the canonical ordered active-Java path list. `sourceContentSetHash` hashes each normalized path plus its source-content SHA-256. `statusSnapshotHash` hashes the normalized count-safe porcelain state, never raw credentials or file contents.

## NDJSON Row Contract

Each line is canonical UTF-8 JSON with lexicographically sorted keys:

```json
{
  "schemaVersion": "awx.random-probe-candidate-row.v1",
  "ordinal": 1,
  "candidateId": "rpc1-2f5c9a3e1b74d8c0",
  "identityHash": "64 lowercase hex",
  "rankKey": "64 lowercase hex",
  "repoPath": "main/java/com/example/lms/example/Example.java",
  "sourceRoot": "main/java",
  "trackingState": "clean_tracked",
  "eligibility": "candidate",
  "probeFamily": "locale_sensitive_case_normalization",
  "lineHints": [41, 87],
  "sourceContentHash": "64 lowercase hex",
  "staticEvidence": {
    "matchCount": 2,
    "rawContentStored": false,
    "providerAttemptObserved": false,
    "runtimeBehaviorObserved": false
  },
  "status": "UNTRIAGED",
  "nextAction": "require_active_call_path_and_smallest_disconfirming_probe"
}
```

No source excerpt, prompt, response, credential, authorization header, cookie, URL query, or exact financial value is stored.

## Manifest Contract

The manifest schema is `awx.random-probe-candidate-ledger.v1` and records:

- the seed and maximum;
- active and candidate roots;
- path and ranking algorithms;
- status, path-order, source-content-set, and NDJSON SHA-256 values;
- tracked, clean, modified, untracked, emitted, eligible, excluded, family, and root counts;
- unique identity and duplicate-observation counts;
- `mutationAllowed=false`;
- `externalCallsAllowed=false`;
- `applicationSourceWritten=false`.

File publication refuses overwrite. It writes a same-directory temporary file, atomically publishes the NDJSON, writes the manifest, and then writes SHA-256 and `.ready` sidecars. Output paths under source, test, script, `.git`, build, or inactive-source roots are rejected.

## Validator Invariants

The independent validator fails closed unless all of these hold:

- exact schema versions and allowed field sets;
- UTF-8 NDJSON with one object per nonblank line;
- `1 <= emitted <= maxRecords <= 1000`;
- contiguous ordinals from one;
- unique candidate IDs, identity hashes, and `(repoPath, probeFamily)` pairs;
- normalized repository-relative descendants of active candidate roots only;
- valid lowercase SHA-256 fields and matching NDJSON hash;
- exact deterministic rank order;
- dirty tracking implies `excluded_dirty`;
- only clean tracked rows may be `candidate`;
- every generated row remains `UNTRIAGED`;
- manifest counts recompute from NDJSON;
- mutation/external/source-write flags remain false;
- secret-pattern scan count is zero.

Validation diagnostics contain stable reason codes and counts, not raw matching content.

## Defect Promotion and Repair Loop

For each eligible candidate selected during the remaining time budget:

1. Trace the active call path and confirm current owner.
2. Define the smallest disconfirming probe.
3. Run the probe and record fresh RED or classify the candidate as `NOT_A_BUG`, `POLICY_NEEDED`, `EVIDENCE_NEEDED`, or `HOLD`.
4. Before any application-source write, freeze at most 20 redacted evidence rows and run exactly `POSITIVE_QUERY`, `NEGATIVE_QUERY`, and `NEUTRAL_QUERY` under the repository three-way preflight contract.
5. Continue only on order-stable `APPLY` with score at least 50 and proven owner, lease, preimage, and verification commands.
6. Write or update the focused test first, watch it fail for the intended reason, then apply the minimum patch.
7. Run focused GREEN, affected-boundary tests, source-set/LangChain4j guards, compilation, and final diff/secret checks.
8. Do not commit, push, deploy, mutate databases, or call paid providers without separate operation-level authority.

The campaign stops early on decisive completion, a repeated external-only blocker, missing authority, changed preimage, an active writer, or exhaustion of the nine-hour maximum. Duration is a ceiling, not evidence of quality.

## Browser and Computer Evidence

The in-app Browser remains visibly open during the campaign. Local UI proof is collected only after the final task-owned runtime artifact for the current source state is built and started on proven free ports.

Browser acceptance covers:

- desktop `1280x720` and mobile `390x844` geometry;
- composer visibility and hit testing;
- single vertical scroll ownership;
- stream rendering;
- late-error suppression;
- cancel followed by late-final suppression;
- reload/session continuity where the current contract requires it.

`HTTP 200`, a visible answer, a delivery event, or a hash does not prove provider generation or semantic correctness. Provider/wire success remains `not_observed` unless same-request attempt evidence exists.

Computer Use is invoked only for a Windows UI surface that shell/file APIs and the Browser cannot prove. It must not automate the Codex or ChatGPT desktop UI.

## Acceptance Criteria

The campaign foundation is accepted when:

- generator and validator tests demonstrate a genuine RED then GREEN cycle;
- the producer emits exactly 1,000 rows when the clean/dirty active universe supplies at least 1,000 ranked records, otherwise it reports the smaller truthful count;
- a second run is byte-identical on the deterministic surface;
- the independent validator returns `VALID` with zero secret hits;
- the legacy Markdown check returns `EVIDENCE_NEEDED`, not a fabricated current count;
- no application source, Git state, runtime, provider, or external system is mutated by the tooling.

Each subsequent repair is accepted only with its own active owner, stable preflight `APPLY`, focused RED-to-GREEN evidence, affected-boundary verification, and final diff review. Campaign completion reports the four separate counts defined at the top of this document and fresh Browser evidence for the final runtime state.
