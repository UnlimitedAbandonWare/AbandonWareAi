# Measurement and repair evidence contract

The CLI and `scripts/test_skill_diagnostics.py` are executable contracts. These
commands run from the verified Desktop repository root. All output is new and
exclusive; inputs stay unchanged. JSON is UTF-8. Exit 2 indicates incomplete,
invalid, regressed or unavailable evidence; inspect the result, not exit alone.

## Discovery and reuse

`scan --scope <repo-relative path>` can be repeated to restrict the default roots.
`--cache <prior cache.json>` uses a content/checker/configuration hash cache. All
source bytes are still hashed; cache hits save parsing and candidate extraction,
not source reads. Cache files are task-owned performance hints, not proof of
correctness. Rebuild without cache when provenance is uncertain.

Default caps: 5,000 candidate files, 1 MiB per source file, 64 MiB source input,
60 seconds between scan work units; trace input 32 MiB, line 64 KiB. AST parsing
is a bounded-file synchronous unit, not a preemptible sandbox. A single parse or
filesystem call can exceed the wall-clock checkpoint. Skips/limits are explicit
issues. Reparse paths and escapes are rejected. Files are never imported merely
because discovery found them. Source byte counters exclude the fixed registry
adapter's own reread of skill metadata.

Python AST and Java/PowerShell/skill-text results are location candidates only.
The registry preserves its existing normalized name and collision rules. The
offline log adapter invokes `diagnose(..., use_mcp=False, use_ai=False)` and
retains `claimsVerified=false`. Inspectors succeeding does not certify every
skill or establish source causality.

Use `run --synthetic-input` when the selected adapter input is a synthetic
fixture. Real execution of an adapter is distinct from real production input.

```powershell
python -B -X utf8 tools/skill_diagnostics.py --root . scan --scope tools --scope .agents/skills --cache data/agent-handoff/skill-diagnostics/case-001/scan/cache.json --out data/agent-handoff/skill-diagnostics/case-002/scan
python -B -X utf8 tools/skill_diagnostics.py --root . run --adapter log-diagnose --log data/agent-handoff/skill-diagnostics/case-001/build.log --out data/agent-handoff/skill-diagnostics/case-001/log
```

## Opt-in event capture

Import `TraceRecorder` with the repository `tools` directory on `sys.path`. The
caller owns business outcome/oracle selection and explicit retry policy. The
recorder never invokes a target, retries it, swallows its exception, or changes
its return. Sink failures during the body preserve it and invalidate coverage.
Invalid/existing output or context fails during setup before the body starts.
One recorder has one owning thread; asynchronous work is observed only while
inside the explicit contexts. Do not share a recorder across threads/processes.

```python
from skill_diagnostics_trace import TraceRecorder, digest, file_hash

# Safe, opaque fixture/case IDs only; never put user text into metadata.
context = dict(
    skillId="demo1-observed-debugging-meta", targetSkillId="selected-skill",
    skillHash=file_hash(skill_path), sourceFingerprint=source_manifest_hash,
    caseId="case-001", inputRef="fixture-001", cohortId="fixed-cases-v1",
    environmentHash=environment_hash, oracleHash=file_hash(fixed_test_path),
    policyHash=digest({"retries": 0}), caseRef="debug-case-001")
with TraceRecorder(root, event_path, context) as trace:
    trace.decision("selected-probe", evidence_refs=[
        {"path": "data/agent-handoff/case/evidence.json", "sha256": evidence_hash}])
    with trace.tool("existing-checker", call_id="logical-call-001") as attempt:
        result = existing_probe()
        attempt["status"] = "succeeded" if fixed_oracle(result) else "failed"
        trace.set_result(attempt["status"])
```

Schema `demo1.skill-diagnostics.event.v1` has strict allowed fields. Run identity
includes `skillId`, `targetSkillId`, skill/source hash, case/input/cohort,
environment/oracle/policy hash and `caseRef`. Event/trace/span IDs, parent and
sequence bind the stages `input`, `decision`, `tool_start`, `tool_result`,
`exception`, `run_result`. Tool attempts are 1-based and contiguous per
`toolCallId`; retryOf points to that logical ID. Separate calls are not retries.
`observationSource` is wrapper/imported/declared, and `synthetic` is explicit.
The input trace set is the measurement window; independently selected windows
must not be silently combined to improve denominators.

Decisions contain a short action, optional reason code and up to eight hashed
artifact references. No private reasoning or raw payload. Locations are relative
paths/lines and exception types, with null when outside the known root.

A semantic artifact uses `demo1.skill-diagnostics.verification.v1` with
`caseId`, `inputRef`, `sourceFingerprint`, `oracleHash`, `validatorId` and
`outcome=pass|contradiction`. The values bind to the decision. The parent must
establish the validator's independence and meaning; the schema is not a digital
attestation. Plain reference existence only gives `linked`, never semantic truth.

## Metrics

| Field | Definition |
| --- | --- |
| failureRate | (failed + timed_out) / (succeeded + failed + timed_out) |
| toolSuccessRate | succeeded terminal attempts / all valid terminal attempts |
| logicalToolSuccessRate | calls whose last attempt succeeded / terminal calls |
| firstAttemptFailureRate | failed or timed_out first attempts / first attempts in succeeded, failed, timed_out |
| retryCount | additional observed starts per logical call; independent calls excluded |
| retryRecoveredCallCount | failed/timed-out first attempt followed by succeeded last attempt |
| exceptionCount / exceptionLocations | unique exception events, relative location/type counts; propagated same object recorded once |
| unsupportedDecisionCount | required missing, unavailable, mismatched or invalid evidence |
| contradictedDecisionCount | same-input hash-bound explicit validator contradiction |
| runLatencyMs / toolLatencyMs | terminal complete samples, n, median p50 and nearest-rank p95 |
| traceCoverage | complete required-stage runs / observed runs |

Every rate includes numerator, denominator and value; denominator zero gives
null. Cancelled/skipped/blocked and incomplete runs remain separate statuses.
Malformed ingestion quarantines the supplied batch from reliability denominators
because its missingness may not be attributable to one run; observed retry and
exception counts remain visible. Missing/unpaired stages quarantine their run.
Identical duplicate event IDs are counted once; conflicting IDs never select a
winner by arrival order. Groups retain source, target, environment, oracle,
policy, cohort and observation source. `--expected-skill` makes absent actors
explicit without claiming coverage of the whole registry.

```powershell
python -B -X utf8 tools/skill_diagnostics.py --root . report --events data/agent-handoff/skill-diagnostics/case-001/registry/events.jsonl --expected-skill demo1-observed-debugging-meta --out data/agent-handoff/skill-diagnostics/case-001/metrics.json
python -B -X utf8 tools/skill_diagnostics.py --root . triage --metrics data/agent-handoff/skill-diagnostics/case-001/registry/metrics.json --case-ref adapter-case --evidence-id E-CLI-1 --target tools/ai_debug_assist.py --out data/agent-handoff/skill-diagnostics/case-001/triage.json
```

## Existing DebugCasePacket and repair

`triage` revalidates traces/evidence and emits an `evidenceRow` for the existing
case. Its caseRef must match. The parent attaches that row and references its
E-ID in reproduction/hypotheses. Two matching complete failure observations may
be `reproduced`; no CLI result establishes `causal=true` or mutation authority.
Target preimages are identity snapshots, not permission. `patchIntent` still
needs the semantic RED, one causal probe, rollback and existing source-owner
gate. Normal behavior is preserved by fixed acceptance tests. No observed
failure means no forced patch. Case classification remains the existing owner's.

## Comparison and limits

```powershell
python -B -X utf8 tools/skill_diagnostics.py --root . compare --before data/agent-handoff/skill-diagnostics/case-001/synthetic/before-metrics.json --after data/agent-handoff/skill-diagnostics/case-001/synthetic/after-metrics.json --out data/agent-handoff/skill-diagnostics/case-001/comparison.json
```

The comparison reopens traces and referenced evidence and recomputes metrics.
An edited/resigned summary cannot substitute for the observed input. This is
local artifact revalidation, not authentication of a provider or remote author.
Keep one run per fixed case in each before/after window; repeated trials belong
to separate matched windows. Changed case/input/cohort/environment/oracle/policy
or contract is not comparable. Only intended source/skill bytes may differ.

`improved` needs resolved failure/evidence violation, no remaining failed case,
preserved successful cases, complete telemetry and no worse first-attempt
failure. Additional retries or nonterminal outcomes block improvement; ordinary
latency noise alone never establishes it. Status is improved/unchanged/regressed/
not_comparable/insufficient_evidence. Synthetic results stay synthetic. Check
normal-case regression tests independently; a comparison is not whole-repo
certification. Statistics and token telemetry stay `not_tested`/null until
measured. No provider or database traffic is generated by these commands.
