# Local experiment contract

All examples run from `C:\AbandonWare\demo-1\demo-1\src`. Prefix the action with
`python -B .agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py`.
Use `catalog.py` in the same directory for discovery. Commands write only an
explicit campaign, derived catalog or usage event. Raw source files are retained.

## Files and identity

- Curated `.agents/skills/semantic-catalog.yaml`: source-bound facets, concepts and
  reviewed relations. JSON syntax is valid YAML and avoids another runtime dependency.
- Derived `semantic-index.json` and `SEMANTIC_INDEX.md`: rebuild with
  `catalog.py build --write`. The default build is read-only.
- Campaigns: `data/agent-handoff/adaptive-rule-lab/campaigns/<campaign>/`.
  `campaign.json` freezes the protocol; `candidates/` retains hypotheses and parent
  links; `runs/<run>/events/` retains every normalized attempt; `report.json` stores
  error analysis and comparison. `state.json` tracks consumed budgets and the local
  recommendation pointer. It is not application memory or a production rule.
- A route identity contains scope, kind and canonical ID. A skill and prompt pack
  with similar names remain separate. Both source hashes bind every semantic edge.
  References inherit context as a candidate hint, never execution authority.

## Register and run

Create a repo-relative JSON cases file. Each independent case has one unique
`caseId`, one unique `independenceGroup`, and `split: development|confirmation`.
Catalog cases additionally provide `query`, `expectedIds` and optional `topK`.
Confirmation cases may declare integer `confirmationBatch` values at registration.
The runner consumes whole batches in ascending order; omitted values mean batch 0.
Repeated variants of the same task belong to one case unit; do not pretend they
are independent by changing IDs. The caller must substantiate sampling scope.

Evaluator JSON:

```json
{"kind":"catalog","index":".agents/skills/semantic-index.json","mode":"title"}
```

The candidate uses `mode: semantic` and may point to a separately frozen index.
Both index bytes and evaluator code are pinned. Do not rebuild an index during a
campaign. Changes need a new candidate index; baseline remains frozen.

```powershell
python -B .agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py register --campaign route-demo --cases data/route-cases.json --spec data/baseline.json --hypothesis "Purpose aliases recover valid routes missed by names."
python -B .agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py candidate --campaign route-demo --candidate alias-v1 --spec data/candidate.json --hypothesis "Reviewed concepts improve top-one retrieval." --changed-basis "Add bilingual purpose aliases."
python -B .agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py run --campaign route-demo --candidate alias-v1 --run-id dev-v1
python -B .agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py candidate --campaign route-demo --candidate alias-v2 --parent alias-v1 --spec data/revised.json --hypothesis "Separate retrieval from verdict concepts." --changed-basis "Development residuals confuse evidence collection with judgment."
python -B .agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py run --campaign route-demo --candidate alias-v2 --run-id dev-v2
python -B .agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py run --campaign route-demo --candidate alias-v2 --run-id confirm-v2 --split confirmation
python -B .agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py status --campaign route-demo
python -B .agents/skills/demo1-adaptive-rule-lab/scripts/experiment.py promote --campaign route-demo --run-id confirm-v2
```

`--case-ids` may narrow development cases only. Confirmation evaluates the entire
next preregistered batch; arbitrary subset selection is rejected. A look and its cases
are reserved before execution, including interrupted attempts. Do not delete a
lock left by another process. Inspect its owner and preserve partial events before
an operation-local recovery. A campaign does not authorize arbitrary command execution.

## Debugging and other adapters

An explicit local adapter spec is
`{"kind":"python","script":"path/to/adapter.py","localExecutionAuthorized":true}`.
The script receives one case as JSON stdin and must return exactly:

```json
{"success":true,"quality":1.0,"debugVerified":true,"status":"ok","errorClass":null}
```

For debugging, `debugVerified` means a real reproduction or regression assertion
passed. `quality` must still be measured on its declared rubric. Set the frozen
policy `taskType` to `debugging` to score debug verification in the quality slot.
The adapter must operate only on authorized local inputs. It may parse an existing
test result or run a bounded approved probe; it must not start persistent children.
The included `scripts/junit_adapter.py` reads a case's repo-relative `reportPath`
and verifies `reportHash` before parsing. It rejects empty/inconsistent reports,
entities and oversized XML. Skipped tests cannot count as verified debugging.
Set optional `capabilityLoss: true` for any protected capability loss; it is a
hard promotion failure regardless of aggregate score.
No arbitrary shell string is accepted. Raw stdout/stderr are discarded. Allowed
error classes are null, timeout, test-failed, assertion, unavailable, adapter-error.
Malformed or nonzero adapter results remain failed attempts in the denominator.

## Metrics and decisions

Query score is `100*(0.50*success + 0.30*quality + 0.20*latencyUtility)` per case.
Debugging substitutes verified debugging for quality in that formula. Average all
cases, including failures. Latency utility is 1 at/below 50 ms and 0 at/above
1000 ms by default; unsuccessful attempts always receive 0. These are configurable calibration
values, not universal targets. Record the full policy before a run.

Promotion requires an effect exceeding 3 points with uncertainty accounted for,
and no observed quality, success, debug or error regression. Default p95 latency
regression allowance is 25 ms. Population equivalence of protective rates is not
claimed by those observed checks. Required per-case capability checks must be
represented explicitly by the evaluator and retained as hard failures.

The conservative score lower bound uses independent paired case differences in
[-100,100] and a one-sided Hoeffding bound. Family alpha is .05, divided across
at most 9 confirmation looks (3 candidate families × 3 revisions by default).
This avoids a zero-width confidence interval when every measured difference is
the same. Small samples usually retain the baseline. Independence and relevance
of the registered cases remain explicit assumptions; synthetic fixtures prove
mechanics, not operational performance. Supporting paired resampling and binary
diagnostics, when present, do not override the conservative promotion bound.

Default campaign time is 900 seconds, evaluator ceiling 60 seconds. Timeouts are
right-censored; observed p95 is not an estimate of uncensored completion latency.
Register different budgets or tolerances only before measurement, with a reason.

## Usage and rollback

A usage event contains exactly `eventId`, `routeId`, `stage`, `at`, and
`observationScope`. Stages are retrieved, selected, invoked, completed. Replaying
identical events is idempotent; changing an existing event ID is rejected.
Invocations from different observation windows cannot establish global frequency.

`rollback --campaign <id> --expected-pointer-hash <sha256> --reason <reason>`
restores the prior task recommendation if its pointer still matches. It retains
promotion, comparison, failure and rollback evidence. Shared rule calibration is
a separate, evidence-bound source edit under the existing repository workflow.

## Failure memory

Before revising, read parent `errorAnalysis.residuals`, failure categories,
candidate hypothesis, evaluator hash and observed metrics. Record a causal
explanation and a changed mechanism, not only a different prompt sentence. Keep
unsupported hypotheses labeled as hypotheses. If the same mechanism already
failed, return its prior evidence and choose a decision-changing experiment.
Preserve failure records; do not mix them with Codex personal memory or training data.
