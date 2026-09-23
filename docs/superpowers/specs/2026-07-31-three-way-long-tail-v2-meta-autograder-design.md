# Three-Way Long-Tail v2 Meta-Autograder Design

## Status and scope

- Date: 2026-07-31
- Request class: `prompt_skill_tooling_only`
- User-selected approach: deterministic structural and statistical evaluation
- Design authority: executable meta-autograder, not an unverified natural-language approval
- Mutation surface: prompt documentation, repo-local skill documentation, deterministic Python tooling, fixtures, and focused tests
- Prohibited surface: application source, public APIs, databases, credentials, providers, runtime orchestration, and deployment

## Problem

The v1 grader strongly validates packet shape and safety boundaries, but several scores can be gamed with fluent filler:

- a non-empty falsifier or probe receives credit without being discriminating;
- paraphrased duplicate claims evade exact normalized-text fingerprints;
- the candidate supplies evidence authority, freshness, and independence metadata;
- baseline totals and case counts are trusted rather than derived from symmetric per-case grading;
- aggregate deltas have no defined estimand, paired sampling unit, uncertainty interval, or cluster handling;
- an order-stable verdict can be self-declared without recomputing the neutral decision.

The user should not need to decide whether the statistical and anti-gaming design is technically sound. A bounded meta-autograder must reject an incomplete design before v2 implementation is accepted.

## Independent audit findings

The statistical audit classified the initial v2 draft as `HOLD` because it did not define symmetric regrading, derived pair counts, the resampling unit, cluster dependence, quantile semantics, or locked safety labels.

The anti-gaming audit classified v1 as structurally strong but semantically gameable. It required fixture-owned evidence and claim registries, symbolic causal discrimination, derived neutral verdicts, and adversarial fixtures for filler, paraphrase, evidence laundering, tautological falsifiers, non-discriminating probes, and branch collusion.

These findings are input to the meta-contract. They are not themselves a pass.

## Chosen architecture

Implement two separate deterministic tools.

### 1. Design meta-autograder

`scripts/score_three_way_long_tail_design.py` consumes a compact design contract and judges whether the proposed v2 design is implementable, falsifiable, bounded, statistically defined, backward compatible, and safe.

It does not read prose to infer intent. It validates explicit fields and executes a fixed reference rule matrix against design-declared expected outcomes.

### 2. Runtime artifact grader v2

The meta-autograder approves or holds the v2 design. It does not claim that the runtime artifact grader has been implemented. A later implementation must follow the accepted contract and pass the same adversarial matrix.

The existing v1 grader remains available and unchanged until the v2 implementation passes its own RED/GREEN suite.

## Meta-contract input

~~~text
schemaVersion=awx.three-way-long-tail.design-eval.v1
designId
designRevision
canonicalQueryCount=3
canonicalQueries=[POSITIVE_QUERY,NEGATIVE_QUERY,NEUTRAL_QUERY]
scopeSeparation
fixtureAuthority
evidenceRelationContract
causalContract
neutralDerivationContract
metamorphicContract
statisticalContract
safetyContract
compatibilityContract
resourceContract
releaseGate
ruleMatrix
~~~

The contract uses exact allowlisted keys. Unknown keys fail closed.

## Reference invariants

### Query and scope

- Exactly three canonical queries; no fourth judge or majority vote.
- Artifact, statistical uplift, and runtime lineage verdicts remain separate.
- The grader performs no network, model, Supabase, browser, provider, database, or source mutation.
- Runtime/provider improvement remains `HOLD` without separate lineage evidence.

### Fixture authority

- The sealed fixture envelope owns evidence, claim, semantic-family, scenario-family, stratum, cluster, safety, and generation-condition metadata.
- Candidate packets may reference registered IDs but may not submit an inline registry, baseline, case count, authority label, freshness label, independence group, threshold, or delta.
- Baseline and candidate raw artifacts are symmetrically regraded with the same scorer, rubric, deck, and generation-condition hashes.

### Evidence and semantic boundaries

- Scored relations are registered `(claimSpecId, evidenceId, relation)` tuples where relation is `supports`, `contradicts`, `qualifies`, or `context`.
- Free-text rationale is bounded and unscored.
- Semantic equivalence outside fixture-owned family IDs is not claimed to be deterministically verified.
- Real-world authority, factuality, probe cost, and agent independence remain external evidence.

### Causal discrimination

Each registered causal contract contains:

~~~text
hypothesisId
interventionId
controlId
outcomeId
alternativeCauseId
discriminatorProbeId
expectedUnderHypothesis
expectedUnderAlternative
falsifierId
~~~

The hypothesis and alternative must be distinct registered families. Expected outcomes must differ. Otherwise the probe is non-discriminating.

### Derived neutral decision

The grader derives evidence coverage, decisive evidence, goal score, order stability, and artifact verdict from registered relations. Packet self-declarations are checked only for equality with the derived result.

### Metamorphic behavior

The design declares mutations and expected behavior:

- reorder claims, evidence rows, or branch presentation: score invariant;
- change only authority adjectives in unscored rationale: score invariant;
- add filler or same-family paraphrases: no score increase;
- remove decisive evidence, a counterexample, confounder, or distinct alternative: score decreases or hard gate activates;
- inject inline authority, baseline, case count, threshold, or delta: reject or hold;
- swap Positive/Negative presentation to neutral: derived verdict and decisive evidence set remain stable.

## Statistical contract

The comparison configuration locks:

~~~text
rubricVersion
scorerVersion
formulaHash
fixtureDeckHash
generationConditionHash
policyVersion
caseIds
stratumIds
clusterIds
safetyLabels
deterministicSeed
rngAlgorithm=sha256-counter-v1
bootstrapMethod=stratified-cluster-paired-percentile-v1
bootstrapIterations=10000
confidenceLevel=0.95
quantileMethod=linear-r7
tieEpsilon
~~~

Rules:

- Pair IDs are unique and baseline/candidate case sets match exactly.
- `pairedCaseCount` is derived from validated pairs, never submitted.
- Scores and deltas are finite and recomputed from unrounded arm grades.
- No failed or missing case is silently dropped.
- The independent resampling unit is `clusterId`, not an individual correlated case.
- Resampling occurs within predeclared strata and preserves declared deck weights.
- Exactly 10,000 replicates are generated through SHA-256 counter indices.
- The estimand is the predeclared deck-weighted mean paired delta.
- The 95% percentile interval and R7 interpolation are computed over unrounded replicates.
- `deckWinRate` is descriptive; it is not called a population probability.
- Minimum case and stratum counts are representation gates, not proof of stratum-specific uplift.

Statistical outcomes:

~~~text
INCONCLUSIVE: comparison is unevaluable or underpowered
NO_UPLIFT: valid comparison fails a policy threshold
UPLIFT_CANDIDATE: every eligibility and policy gate passes
REJECT: a locked safety-critical case regresses
~~~

Policy thresholds are non-compensable gates, never weighted quality-score components.

## Meta-autograder rubric

| Dimension | Points |
|---|---:|
| `scopeSeparation` | 10 |
| `fixtureAuthority` | 15 |
| `evidenceAndSemanticBoundary` | 15 |
| `causalDiscrimination` | 15 |
| `metamorphicAntiGaming` | 15 |
| `pairedStatisticalDefinition` | 15 |
| `determinismAndSerialization` | 5 |
| `safetyAndAuthorityBoundary` | 5 |
| `compatibilityAndRollback` | 3 |
| `resourceBoundedness` | 2 |

The score describes contract completeness, not runtime quality or scientific truth.

## Meta hard gates

Any hard gate forces `designVerdict=HOLD` regardless of score:

~~~text
canonical-query-count-invalid
majority-vote-enabled
verdict-scope-collapsed
candidate-owned-evidence-registry
candidate-owned-baseline
candidate-owned-case-count
candidate-owned-threshold-or-delta
free-text-semantic-score-claimed
causal-contract-nondiscriminating
neutral-verdict-not-derived
paired-case-set-unlocked
asymmetric-arm-grading
resampling-unit-undefined
cluster-dependence-ignored
bootstrap-semantics-undefined
nondeterministic-rng
safety-labels-unlocked
safety-regression-not-rejected
thresholds-used-as-score-weights
network-or-model-call-required
runtime-improvement-self-asserted
legacy-score-auto-converted
resource-bound-missing
adversarial-matrix-incomplete
false-accept-observed
canonical-false-reject-observed
~~~

## Rule matrix

The design contract must include one canonical valid row plus the following 20 behavioral categories with exact expected verdict and reason code, for 21 required rows total:

1. claim padding;
2. same-family paraphrase duplication;
3. unrelated evidence relation;
4. inline evidence registry;
5. evidence independence laundering;
6. authority wording only;
7. tautological falsifier;
8. non-discriminating probe;
9. identical intervention/control expectations;
10. non-distinct alternative cause;
11. duplicated scenario family;
12. branch semantic collusion;
13. inconsistent neutral declaration;
14. candidate baseline or case-count injection;
15. unmatched paired case sets;
16. missing cluster IDs;
17. undefined bootstrap quantile;
18. deterministic replay;
19. safety-critical regression;
20. v1 compatibility without v2 uplift.

The meta-autograder owns the reference mapping from category to expected verdict/reason. A design cannot pass by changing its own expected answer.

## Release gate

`designVerdict=APPLY` requires all of:

~~~text
metaScore >= 95
hardGateCount = 0
requiredRuleCategoryCoverage = 100%
adversarialExpectedOutcomeAccuracy = 100%
falseAcceptCount = 0
canonicalFalseRejectCount = 0
~~~

The remaining release observations are grader-owned output evidence, never submitted `releaseGate` fields:

~~~text
observedEvidence.deterministicReplayByteIdentical = true
observedEvidence.secretPatternHitCount = 0
~~~

The grader validates once, executes its side-effect-free grading core twice, and compares canonical bytes. A failed replay adds `deterministic-replay-failed` and forces HOLD. Bounded input scanning either rejects unsafe content or derives a zero observed input-secret count. Repository-file secret scanning remains a separate count-only verification command.

If the design fails, emit one primary reason and the smallest contract change that can alter the verdict. Do not ask the user to adjudicate statistical internals.

## Compatibility and resource contract

- v1 input remains supported by the existing v1 grader.
- v1 output is never numerically converted to v2.
- A v1 artifact may receive `compatibilityMode=v1-readonly` and `statisticalUpliftVerdict=INCONCLUSIVE` only.
- The meta-autograder accepts at most 256 KiB input, 64 rule rows, and 512 KiB output.
- The future v2 runtime grader may accept at most 4 MiB, 512 cases, 2,048 claims, and 256 KiB output.
- Both tools must target five seconds and stop at ten seconds.
- Output is canonical UTF-8 JSON with sorted keys, no NaN/Infinity, and no reflected secret-like content.
- Output emits `designIdHash` and `designRevisionHash`; raw submitted identifiers are never reflected.
- `--output` requires `--run-root`. The run root must be an existing child of the OS temporary directory, the output must remain inside it, and input/output paths must be distinct. Stdout mode needs no run root.
- Exit 0: valid APPLY; exit 1: valid HOLD/REJECT; exit 2: malformed/unsafe input; exit 3: internal failure.

## Test strategy

### RED

1. Add meta-autograder tests before the implementation module exists.
2. Confirm the initial v2 draft fails for candidate-owned baseline/counts, undefined cluster resampling, and unscored filler semantics.
3. Confirm a design can score above 95 yet remains HOLD when one hard gate is active.
4. Confirm changing a rule row's expected answer cannot make an invalid design pass.

### GREEN

1. Implement exact schema and reference invariants only.
2. Add the corrected design contract.
3. Run the meta-autograder twice and compare output bytes.
4. Keep existing v1 tests green.

### Required verification

~~~powershell
python scripts\test_three_way_long_tail_design_autograder.py
python scripts\test_three_way_long_tail_autograder.py
python scripts\test_three_perspective_chat_postprocess.py
python scripts\score_three_way_long_tail_design.py --input <design-contract> --output <result> --run-root <caller-created-temp-root>
~~~

Run a count-only secret scan over declared files and validate the repo-local skill with process-local UTF-8 mode on Windows.

## Files

### Add

- `scripts/score_three_way_long_tail_design.py`
- `scripts/test_three_way_long_tail_design_autograder.py`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/v2_design_contract.json`

### Modify

- `.agents/skills/demo1-agentic-chat-postprocess/SKILL.md`
- `.agents/skills/demo1-agentic-chat-postprocess/references/execution-contract.md`
- `.agents/skills/demo1-agentic-chat-postprocess/references/review-packets.md`
- `.agents/skills/demo1-agentic-chat-postprocess/references/stop-conditions.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/system_ko.md`
- `agent-prompts/agents/demo1_three_perspective_chat_postprocess/scenario_matrix_ko.md`

### Exclude

- `main/**`, `app/**`, `src/test/**`, DB/DDL, public DTOs, providers, credentials, runtime configuration, generated build outputs, archives, and PatchDrop payloads.

## Rollback

Remove the three new files and restore the six modified prompt/skill files to their recorded preimages. Rebuild the registered prompt and rerun the existing v1 focused tests. No DB, runtime, provider, or external rollback is required.

## Success boundary

A passing meta-autograder means the v2 design contract covers the declared structural, statistical, safety, and adversarial invariants. It does not mean the v2 runtime grader exists, the prompts improve live RAG accuracy, or provider/runtime lineage has passed.

## External evidence notes

- OpenAI's official [Graders API reference](https://platform.openai.com/docs/api-reference/graders?api-mode=chat) separates fixed reference/string checks from model-based graders and supports composite grading. This design deliberately keeps the release-critical meta gate on fixed fixture-owned references rather than a model's self-judgment.
- OpenAI's official [model guidance](https://developers.openai.com/api/docs/guides/latest-model) recommends comparing configurations on the same representative tasks and testing program output separately from the final message. The design therefore locks one deck/condition and verifies both grader JSON and built prompt behavior.
- Published matched-pair work reports percentile-bootstrap intervals as one usable method in stratified paired designs ([Tang, 2010](https://onlinelibrary.wiley.com/doi/abs/10.1002/sim.3744)); a separate open study describes the percentile interval directly from bootstrap quantiles ([PLOS ONE, 2011](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0019539)). These sources support explicitly pinning the estimand and interval method; they do not prove that this deck is powered or that 10,000 replicates make a weak fixture scientifically valid.
