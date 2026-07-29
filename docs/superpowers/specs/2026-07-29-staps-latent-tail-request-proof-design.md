# STAPS latent-tail prompt and request-proof design

Date: 2026-07-29
Status: approved design, awaiting written-spec review
Canonical root: `C:\AbandonWare\demo-1\demo-1\src`

## 1. Objective

Improve the existing demo-1 prompt, repo-local skill, Markdown analysis, and
chat soak harness without modifying application source. The improvement must:

1. turn the useful claims in `staps.txt` into falsifiable, count/hash-only
   latent-signal packets;
2. stress upper-tail and compensation signals strongly enough to expose a
   decision change, while remaining deterministic and bounded;
3. detect and measure suspected embedding-loss rather than claiming that a
   prompt can recreate discarded dimensions;
4. link the app's exact prompt/options/response hashes and attempt evidence to
   one request without adding a controller, endpoint, or other public API;
5. preserve two independent SUPPORT lanes, an independent FALSIFY lane, and a
   NEUTRAL adjudicator that does not use majority voting;
6. keep Browser, Computer, and Supabase as explicit demand-driven evidence
   lanes rather than completion prerequisites for a prompt/harness-only pass.

The nine-hour wording is an evidence budget, not a mandatory wait and not an
automatic success claim. Work stops early when a decisive gate passes or when
the failure class changes.

## 2. Authority and current evidence

The design input is `C:\Users\nninn\Downloads\staps.txt`:

```text
bytes=286049
lines=3760
sha256=6f100849be5f5dbc7b83032d299ff51831a0b6352ed2c88ed40016ed06d736a1
```

The attachment is a claim map, not runtime proof. In particular:

- the document markets `4096 -> 1536`, less than `1-2%` loss, `3x` speed, and
  in another passage `100%` accuracy;
- the inspected live dimension contract instead described `2560 -> 1536`;
- dimension slicing/padding tests prove shape compatibility, not recall,
  ranking quality, latency, index size, or loss recovery;
- TWPM/CVaR/Risk-K/ZCA/DPP/BodeClamp language motivates upper-tail probes but
  does not authorize an unbounded tuner or production parameter change;
- CFVM, RawTile, reward, and re-index narratives remain hypotheses until a
  same-corpus before/after artifact proves them.

Current source already contains an internal count/hash-only seam in
`ModelRuntimeHealthTracker` and `OllamaNativeChatModel`. A captured runtime log
showed one redacted request hash with three sequences:

```text
requestHashCount=1
sequenceCount=3
promptHashDistinct=1
optionsHashDistinct=2
responseHashValidRows=2
adapterAttemptCount=3
clientHttpExchangeCount=2
clientHttpResponseCount=2
providerAttemptCount=0
wireAttemptCount=0
```

That artifact is `partial`: it proves request correlation and two client HTTP
responses, but it does not prove a positive provider or wire attempt. It also
predates the latest source timestamp, so it cannot certify the current source
build.

## 3. Scope and file boundaries

### 3.1 Files eligible for the implementation plan

- `agent-prompts/agents/demo1_quant_harmony_9h_safe_patch/system_ko.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/system_ko.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/meta.yaml`
  only if trigger metadata must change
- `agent-prompts/prompts.manifest.yaml` only if the existing registrations do
  not already cover the changed files
- `.agents/skills/demo1-bounded-hypernova-probe/SKILL.md`
- `.agents/skills/demo1-bounded-hypernova-probe/references/desktop-postprocess-directive.md`
- `scripts/chat_ui_vibe_soak.ps1`
- `scripts/chat_ui_vibe_soak_tests.ps1`
- `scripts/test_three_perspective_chat_postprocess.py`
- `docs/postprocess/2026-07-29-staps-latent-tail-analysis.md`
- this design and its later implementation plan

Generated prompt output may be rebuilt by the existing prompt builder. It must
not be edited by hand.

### 3.2 Forbidden implementation surface

- `main/java/**`, `main/resources/**`, `src/test/java/**`, and application
  source of any kind;
- new REST/controller/debug endpoints or changes to public response schemas;
- provider, model, embedding, database, or index mutation;
- adaptive/random parameter search, background daemons, external dispatch,
  PatchDrop producer loops, or new orchestration frameworks;
- raw prompts, raw options, raw responses, full proof-log lines, credentials,
  headers, cookies, raw environment values, or full score vectors in output.

If a required proof cannot be obtained through the existing log and harness
surface, the result is `evidence_needed`; application source is not expanded.

## 4. Component design

### 4.1 STAPS claim ledger

The quant-harmony prompt and Markdown analysis will introduce
`demo1.latent-tail-ledger.v1`. Here, "latent" means an observable structured
signal inferred from evidence; it never means chain-of-thought or hidden model
reasoning.

Required fields:

```yaml
schemaVersion: demo1.latent-tail-ledger.v1
claimId: stable_slug
sourceAnchorHash: hash_only
signalClass: baseline | sparse_upper_tail | contradiction | compensation | embedding_loss
observationCount: 0
independentProvenanceCount: 0
contradictionCount: 0
decisionFlipCount: 0
tailRescueCount: 0
falseTailPromotionCount: 0
embeddingLossSuspectCount: 0
baselineMetricCount: 0
candidateMetricCount: 0
verificationGatePassed: false
action: RUN | RESOLVE | REUSE | SKIP | DEFER
rawEvidenceStored: false
compactReportLine: bounded_redacted_summary
```

Rules:

- similarity, reward, confidence, and majority agreement are not truth;
- a tail signal is ledger-eligible only when it has independent provenance or
  a falsifiable measurement;
- absent evidence, literal `unknown`, contradiction, and provider-disabled are
  separate states;
- the ledger may recommend a next probe but cannot claim that the runtime has
  learned, re-indexed, or recovered an embedding.

### 4.2 Strong but bounded tail perturbation

The prompt/skill will evaluate the same evidence through three fixed views:

1. `baseline_order`: incumbent evidence order and current decision;
2. `sparse_upper_tail`: independently sourced rare/tail evidence is evaluated
   before the majority packet;
3. `contradiction_heavy`: strongest counterexample is evaluated first, then one
   compensation signal is allowed to recover a decision only if it closes the
   stated falsifier.

The existing `mixed_score_scale` fixture checks scale normalization across the
three views. "Strong shaking" therefore means a deterministic order shock and
decision-flip measurement, not duplicated evidence, invented scores, random
search, or a production weight increase.

At most three fixed in-memory fixture families are used:

```text
sparse_upper_tail
contradiction_heavy
mixed_score_scale
```

Each fixture records only IDs, counts, bounded aggregates, decision class, and
hashes. A tail rescue is accepted only when the FALSIFY lane's explicit
counterexample is resolved. Otherwise it is counted as `falseTailPromotion`.

### 4.3 Embedding-loss detection and recovery decision

A prompt cannot reconstruct dimensions discarded by an embedding transform.
For this artifact family, "recovery" means detecting a measurable regression
and selecting an evidence-preserving next action.

The prompt must require a same-corpus baseline/candidate comparison containing:

- actual input/model/index dimensions;
- corpus and query-set hashes plus counts;
- recall@n and nDCG at the same `n`;
- latency distribution and index-size count/bytes;
- identical filters, reranker, and evaluation labels;
- a declared acceptance bound from current project policy, not from the STAPS
  marketing numbers;
- rollback or full-dimension rerun instructions by reference only.

Decision states:

```text
RECOVERED       measured candidate is within the declared project bound
LOSS_DETECTED   candidate violates the bound with a reproducible artifact
NO_COMPARISON   one side, dimension, corpus hash, or metric is missing
CONFOUNDED      corpus/options/filter/reranker differ
EVIDENCE_NEEDED no active benchmark seam is available without source changes
```

No `1-2%`, `3x`, or `100%` claim is promoted without the complete comparison.

### 4.4 Three-perspective adjudication

The postprocess prompt retains four independent packets:

- SUPPORT-CONTRACT: verifies schema, invariants, and exact count relationships;
- SUPPORT-SCENARIO: verifies adverse Korean/English scenario behavior;
- FALSIFY: searches for false greens, mixed request hashes, unknown responses,
  stale source builds, and provider/wire inference errors;
- NEUTRAL: returns `APPLY | HOLD | REJECT` from the packets and evidence gates,
  not by vote count.

The two SUPPORT packets may not share conclusions before NEUTRAL adjudication.
FALSIFY must be able to force `HOLD` even when both SUPPORT packets pass.

## 5. Request-scoped count-only proof

### 5.1 Schema

The harness will emit `awx.chat-request-proof.v1`:

```yaml
schemaVersion: awx.chat-request-proof.v1
requestHash: hash:xxxxxxxxxxxx
requestScoped: true
sourceHashBefore: sha256:...
sourceHashAfter: sha256:...
sourceChangedDuringRun: false
proofRowCount: 0
sequenceCount: 0
sequenceUniqueCount: 0
appPromptHashSha256Count: 0
appPromptHashSha256DistinctCount: 0
appOptionsHashSha256Count: 0
appOptionsHashSha256DistinctCount: 0
appResponseHashSha256Count: 0
appResponseHashSha256DistinctCount: 0
adapterAttemptObservedCount: 0
clientHttpExchangeObservedCount: 0
clientHttpResponseObservedCount: 0
providerAttemptObservedCount: 0
wireAttemptObservedCount: 0
unknownResponseHashCount: 0
providerEvidenceStatus: not_emitted | observed_zero | observed_positive
correlationStatus: verified | partial | ambiguous | evidence_needed
modelSuccessClaimAllowed: false
rawPromptStored: false
rawOptionsStored: false
rawResponseStored: false
rawProofLinesStored: false
```

The probe input-message hash remains a separate field. It must never be labeled
as the app's final prompt hash, because the app may add system, context, RAG, or
option material before the model adapter call.

### 5.2 Data flow

1. Hash the relevant source owners before the request.
2. Record the listener stdout file length as a byte offset; do not scan every
   log in the repository.
3. Send one bounded `/api/chat/stream` request through the existing harness and
   retain only the generated request ID in memory.
4. Derive `requestHash` with the existing redaction convention and inspect only
   the newly appended, size-bounded listener tail.
5. Parse allowlisted fields from `[LLM_REQUEST_PROOF]` rows matching that exact
   request hash.
6. Reject rows from other request hashes and never persist the original line.
7. Re-hash the source owners and classify a change as `worktree-overlap`.
8. Write only the schema above and the existing redacted probe result.

### 5.3 Classification

`correlationStatus=verified` requires:

- unchanged source hash;
- at least one matching proof row;
- unique sequence values and count agreement;
- exact `sha256:` format for every available prompt/options/response hash;
- monotonic count relationships:
  `clientHttpResponse <= clientHttpExchange <= adapterAttempt`;
- explicit provider and wire keys, even when their true counts are zero;
- no raw retention flags set to true.

`verified` certifies correlation, not model success. A zero provider/wire count
is `providerEvidenceStatus=observed_zero`, and
`modelSuccessClaimAllowed=false` remains mandatory. `partial` applies when an
unknown response hash or incomplete physical-attempt row remains. Mixed request
hashes, duplicate sequences, or a source change are `ambiguous` or
`worktree-overlap`, never success.

## 6. Error handling and stop rules

- Missing listener log or no matching row: `evidence_needed`.
- Listener tail exceeds the bounded read limit: `evidence_needed`, with byte
  counts only; do not fall back to a repo-wide scan.
- Source changes during build/request: `worktree-overlap`; retry at most once
  after a stable hash is observed.
- `providerAttempt=false` or `wireAttempt=false`: preserve the zero; do not infer
  success from client HTTP response or response hash.
- Unknown response hash: count it and classify `partial`.
- Browser shows a response while request proof is absent: UI supporting proof
  only.
- Supabase project ref/auth absent: read-only `evidence_needed`; no schema or DB
  work.
- Computer proof is not collected unless visible Windows UI behavior changes a
  decision.
- Any required change to application source or a public API stops this pass.

## 7. Test design

### 7.1 Prompt/skill RED contracts

Before editing the prompt/skill artifacts, focused tests must fail for missing:

- `demo1.latent-tail-ledger.v1`;
- the three fixed perturbation views;
- embedding decision states and marketing-claim guards;
- exact request-proof schema and provider/wire non-inference rule;
- four independent review packets and NEUTRAL non-voting adjudication;
- source-free/public-API prohibition.

### 7.2 Harness fixtures

Synthetic listener-tail tests cover:

1. one request, three unique sequences, one prompt hash, two options hashes,
   two response hashes, and provider/wire zero;
2. positive provider/wire observations without changing correlation semantics;
3. a mixed request hash that must be ignored;
4. duplicate sequence rows that produce `ambiguous`;
5. `hash:unknown` response that produces `partial`;
6. no proof row that produces `evidence_needed`;
7. source hash change that produces `worktree-overlap`;
8. proof-line content that never appears in the serialized result;
9. bounded tail-reading that never opens unrelated repository logs.

### 7.3 Verification ladder

```powershell
python -X utf8 scripts\test_three_perspective_chat_postprocess.py
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\chat_ui_vibe_soak_tests.ps1
python -X utf8 C:\Users\nninn\.codex\skills\.system\skill-creator\scripts\quick_validate.py `
  .agents\skills\demo1-bounded-hypernova-probe
python -X utf8 agent-prompts\build.py `
  --manifest agent-prompts\prompts.manifest.yaml `
  --agent demo1_three_perspective_chat_postprocess
python -X utf8 agent-prompts\build.py `
  --manifest agent-prompts\prompts.manifest.yaml `
  --agent demo1_quant_harmony_9h_safe_patch
```

Then run changed-file placeholder/secret scans with count-only output. Existing
focused request-timeline Gradle tests remain supporting structural evidence;
they are not rerun merely to claim a docs-only artifact is complete unless the
live source changes again.

Fresh runtime closure requires one request through a listener built from the
same unchanged source hash. Browser-visible success cannot replace this proof.

## 8. Nine-hour evidence budget

```text
0:00-2:00  attachment/source/prompt/harness reconnaissance; stop early if decisive
2:00-4:00  RED prompt/skill/harness contracts and claim-ledger fixtures
4:00-6:00  minimal prompt/skill/Markdown/harness implementation
6:00-7:30  focused GREEN tests and generated-prompt equality checks
7:30-8:30  SUPPORT-CONTRACT, SUPPORT-SCENARIO, and FALSIFY packets
8:30-9:00  NEUTRAL adjudication and one fresh request-scoped runtime proof
```

No phase waits for wall-clock time. Repeated scans and external-agent dispatch
are prohibited when they do not change the decision.

## 9. Acceptance criteria

Artifact readiness requires all of the following:

- only eligible prompt/skill/Markdown/harness/test files changed;
- no application source or public API changed;
- prompt and skill validators pass;
- all synthetic request-proof fixtures pass;
- generated prompts match the registered source inputs;
- placeholder count is zero and changed-file secret-pattern count is zero;
- the report distinguishes artifact readiness from runtime/model success;
- Browser/Computer/Supabase states remain explicit and supporting-only;
- a fresh source-hash-stable request produces a count-only correlation result,
  or the remaining gap is named exactly as `evidence_needed`;
- NEUTRAL returns `APPLY`, `HOLD`, or `REJECT` from the full evidence packets.

The objective is not complete merely because the files validate. Positive
provider/wire proof, embedding recovery, and model-answer success may remain
`evidence_needed` when current evidence does not support them.

## 10. Rollback

Rollback is file-local:

- remove only the new claim-ledger and request-proof prompt sections;
- remove only the new bounded-tail reference section;
- remove the listener-tail parser and its focused tests together;
- delete the generated analysis/spec/plan files if rejected;
- rebuild generated prompts from their source files;
- never revert unrelated dirty or untracked work.

If concurrent edits overlap a target hunk, stop with `worktree-overlap` rather
than replacing or reformatting the file.
