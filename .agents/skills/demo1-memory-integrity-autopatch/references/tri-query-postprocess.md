# Tri-Query Directive Postprocess

## Data Flow

1. `Prepare` freezes the scanner evidence and original directive hashes.
2. Dispatch `POSITIVE_QUERY` and `NEGATIVE_QUERY` concurrently. Each sees the
   same inputs and cannot read the other packet.
3. Dispatch `NEUTRAL_QUERY` only after both packets exist.
4. `Finalize` validates schemas, role/input/packet hashes, order stability,
   recomputed GoalContract score, secret count, and source mutation verdict.
5. A valid `APPLY` writes `rewritten.directive.candidate.md`. It never
   overwrites the original directive.

## Commands

```powershell
$tool = '.\.agents\skills\demo1-memory-integrity-autopatch\scripts\tri_query_directive_postprocess.ps1'

powershell -NoProfile -ExecutionPolicy Bypass -File $tool `
  -Mode Prepare -Root . `
  -EvidencePath .\data\agent-handoff\memory-integrity\latest.json `
  -DirectivePath .\agent-prompts\agents\demo1_context_contamination_scout_directive\system_ko.md

# After the three role packets have been produced:
powershell -NoProfile -ExecutionPolicy Bypass -File $tool `
  -Mode Finalize -Root . -RunDirectory <prepared-run-directory>
```

## Isolation Contract

| Role | May read | Must not read |
| --- | --- | --- |
| Positive | manifest, evidence, original directive | Negative, Neutral |
| Negative | manifest, evidence, original directive | Positive, Neutral |
| Neutral | manifest, evidence, directive, Positive, Negative | new external evidence |

All inputs and outputs stay under
`data/agent-handoff/memory-integrity/tri-query/<runId>`. Packet output is
bounded to 256 KiB. Raw prompts, queries, context, transcripts, credentials,
headers, cookies, or provider payloads are forbidden.

## Finalizer Decisions

- `APPLY`: order-stable score at least 50, matching hashes, safe candidate.
- `HOLD`: valid neutral uncertainty or any missing/contradictory proof.
- `REJECT`: the proposed rewrite violates a proven source or safety contract.

The rewrite verdict and `sourceMutationVerdict` are separate. A candidate may
be produced while source mutation remains `HOLD`; the existing Desktop
autopatch skill must independently reopen that gate.

An `APPLY` candidate must retain these literal safety anchors: `scanner`,
`sourceMutationVerdict`, `mutationAllowed=false`, `Desktop`, `RED`, `GREEN`,
`PromptBuilder.build(PromptContext)`, `1.0.1`, `rollback`, and
`desktopFinalProof=evidence_needed`.

The finalizer recomputes:

```text
100 * (0.25*evidenceStrength + 0.20*causalStrength
 + 0.15*verificationFeasibility + 0.15*userValue + 0.10*reversibility
 + 0.10*costEfficiency + 0.05*timeFit - 0.20*blastRadius
 - 0.15*ambiguity - 0.20*authorityOrSafetyExpansion)
```

Every component is `0..1`; the result is clamped to `0..100`. A mismatch
between the agent-supplied score and the deterministic score is `HOLD`. The
implementation must call the shared `scripts/awx_goal_score_contract.ps1`
evaluator so intake, memory, and terminal postprocessing cannot drift into
different formulas.
