---
name: demo1-observed-debugging-meta
description: Use when demo-1 skills or agent helpers need recurring failure diagnosis
---

# Observed Debugging Meta Skill

Measure first. Reuse existing inspectors. Carry one reproduced failure through
the existing repair workflow with the smallest change and fresh verification.
This overlay supplies evidence; it does not issue mutation authority.

## Scope and owners

- Use the live checkout and closest instructions. Default discovery covers repo
  skills, `scripts`, `tools`, and the current Desktop source roots. Confirm
  sourceSets before using discovery defaults for an application repair.
- The Python entry is `tools/skill_diagnostics.py`; its trace implementation is
  `tools/skill_diagnostics_trace.py`. Both use only the standard library.
- Reuse `scripts/awx_skill_registry.py` and offline `tools/ai_debug_assist.py`
  (which owns the existing build-error classifier). Execute only named adapters.
- Reuse `$demo1-evidence-debugging` and its
  [DebugCasePacket contract](../demo1-evidence-debugging/references/case-contract.md)
  for the case, E-IDs, hypotheses, probes, patchIntent and acceptance. Do not make
  another incident lifecycle, approval gate, event service or source owner.
- Existing `$demo1-skill-family-postprocessor` owns skill-family validation.
  Do not rewrite its schema or treat its stale self-test receipt as fresh proof.

## Run the loop

1. **Observe.** Read [measurement-contract.md](references/measurement-contract.md).
   Choose a fresh task-owned output directory. Run `scan`; reuse its cache only
   when content and checker hashes match. Read compact counts first, then the
   exact candidate locations. A static match is `candidate`, never a defect.
2. **Trace.** Select the `registry` or `log-diagnose` offline adapter, or opt into
   `TraceRecorder` around a bounded local probe. Record input reference → short
   action/evidence decision → actual tool attempts → result. Do not invent
   missing stages in old logs. `skillId` is the executing observer and
   `targetSkillId` is its target; observer success does not certify the target.
3. **Reproduce.** Attach `triage` output to the existing case as an evidence row.
   Repeat the same safe input with the same source, environment, oracle and
   policy. `reproduced` means repeated observed failure, not proven causality.
   For a new incident, record its normal comparison as well as semantic RED.
4. **Classify.** Use the first failing boundary and one falsifiable hypothesis.
   Triage reports trace gaps, missing evidence, oracle contradictions, timeout
   and tool failure. Environment/permission/external failures need evidence;
   they do not justify a speculative code patch. Unknown stays unknown.
5. **Repair minimally.** Bind the E-ID, semantic RED, causal probe, exact target
   preimage and rollback to the existing `patchIntent`. Parent Codex applies
   only the confirmed missing observation or defective behavior. Application
   source still requires the existing three-role and source-owner/lease gates.
   An existing user approval remains valid within its scope; this skill adds no
   approval. Do not bulk-edit catches, retry loops, skill wording or good paths.
6. **Reverify.** Keep case IDs, inputs, environment, oracle and retry policy fixed.
   Run focused regression tests and `compare` using fresh before/after metrics.
   It replays original traces and revalidates evidence. Preserve every normal
   case. Changed cohorts, incomplete observations, increased first-attempt
   failure, additional retries or cancelled cases cannot support improvement.
7. **Report.** Include paths, source/trace hashes, denominators, exclusions,
   latency samples, failures, retries, evidence gaps, and remaining coverage.
   Stop on decisive evidence; retry the same failure class at most once without
   new evidence. Recover only owned edits whose current postimage still matches.

## Quick entry

Run from the confirmed repository root in PowerShell. Output paths must be new.

```powershell
python -B -X utf8 tools/skill_diagnostics.py --root . scan --out data/agent-handoff/skill-diagnostics/case-001/scan
python -B -X utf8 tools/skill_diagnostics.py --root . run --adapter registry --out data/agent-handoff/skill-diagnostics/case-001/registry
python -B -X utf8 tools/skill_diagnostics.py --root . reproduce --out data/agent-handoff/skill-diagnostics/case-001/synthetic
python -B -X utf8 -m unittest discover -s scripts -p test_skill_diagnostics.py -q
```

`reproduce` exercises an intentionally defective synthetic evidence gate and
its fixed normal/failure cases. It proves this diagnostic workflow, not an
improvement in production. Read the reference for opt-in tracing, triage and
comparison commands; there is no arbitrary discovered-script runner.

## Evidence limits

- No raw prompts, responses, exception messages, locals, command arguments,
  environment values or credentials in traces. Use safe IDs and references.
- Missing required evidence is `unsupportedDecisionCount`; it is not a count
  of confirmed hallucinations. Only same-input bound oracle contradiction
  contributes to `contradictedDecisionCount`. A file hash proves byte identity.
- Unobserved skills and zero denominators remain `null`; malformed telemetry
  cannot become a 100% success claim. Imported records are locally revalidated
  artifacts, not authenticated provider/wire proof.
- Thread/child-process internals and unwrapped tools are outside coverage.
  There is no global monkeypatch, automatic retry, provider call, watcher,
  production deployment, memory update or all-skills rewrite.
- Reduced parse work is measurable. Token savings, latency significance and
  production reliability improvements require their own actual measurements.
