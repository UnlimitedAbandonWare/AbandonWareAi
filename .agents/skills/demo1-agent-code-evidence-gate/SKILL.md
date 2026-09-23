---
name: demo1-agent-code-evidence-gate
description: Use when demo-1 agent-produced code has independently collected verification
---

# Agent Code Evidence Gate Reference

`scripts/agent_code_evidence_gate.py` reduces evidence to a **review-only** result.
It executes no candidate command and grants no source, apply, rollback, deployment,
provider, database, or training authority. Existing source-owner and preflight
contracts still govern a later authorized edit.

## Inputs and owners

- Reuse `scripts/run_verified_command.py` for the execution ID, command hash,
  exit status, selected JUnit XML, result hashes, counts, and freshness checks.
- Use `demo1-docker-autograder` when isolated execution is required. Its ready-last
  job/result hashes, COMPLETE status, actual isolation evidence, expected signal,
  and nonzero tests must agree. A HOLD result's default isolation fields do not
  prove Docker ran. Missing Docker, a digest-pinned local image, or root authority
  remains HOLD; do not add another runner, pull an image, or change its root guard.
- Keep the gate contract and oracle outside the candidate write root. Supply the
  contract SHA-256 independently of the observation envelope. An embedded contract
  is not an authority source. The gate reads the actual oracle and checks its hash.
- The input envelope contains `run` and separately collected `evidence`. The
  contract pins candidate/fixture/oracle/root/toolchain hashes, expected command
  hash, scope, run ID, run-record hash, required suites, maximum evidence age,
  candidate root, and oracle path. `runSha256` is SHA-256 of UTF-8
  `json.dumps(run, sort_keys=True, separators=(",", ":"), allow_nan=False)`.
- Collect static findings, secret counts, expected-signal results, deterministic
  replay, completeness, required-tool/sandbox observations, and all six mutation
  counters from their existing owners. Missing observations stay missing; unit
  fixture booleans and copied policy defaults cannot attest a real run.

The executable schema and synthetic fixtures are maintained in
`scripts/agent_code_evidence_gate.py` and `scripts/test_agent_code_evidence_gate.py`.
Read these for exact field types and bounds rather than inventing adapter fields.

## Invocation

From the verified repository root, with independently supplied artifact paths:

```powershell
python scripts/agent_code_evidence_gate.py --input $gateInput --contract $gateContract --contract-sha256 $gateContractSha256
```

The Gradle task is `agentCodeEvidenceGate`; its properties are `agentCodeGateInput`,
`agentCodeGateContract`, and `agentCodeGateContractSha256`. The existing validation
loop accepts corresponding `--code-gate-input`, `--code-gate-contract`, and
`--code-gate-contract-sha256` options. Report/cycle outputs must not alias any input,
contract, or oracle file. Without a gate input the old ledger behavior is retained.

| Result | Exit | Meaning |
|---|---:|---|
| PASS | 0 | Complete declared evidence supports human review; mutation remains false |
| HOLD | 3 | Required identity, freshness, tool, isolation, oracle, or evidence is absent/unconfirmed |
| REJECT | 2 | Verified candidate failure, secret/hash/oracle violation, or mutation observed |

Mandatory failures dominate advisory success. A stale failed run does not establish
a current candidate failure. Preserve counts, hashes, fixed reasons, and bounded
results; keep raw code, queries, credentials, environment, and logs out of reports.
Runtime/product acceptance and Desktop final verification remain separate.

## Verify changes

Run `python -m unittest scripts.test_agent_code_evidence_gate
scripts.test_run_verified_command scripts.test_source_health_validation_loop`
as one command. Reuse the existing autograder contract tests for adapter changes.
Rollback only this gate's changed hunks and optional wiring; preserve original
evidence and unrelated source. No automatic patch application or promotion exists.
