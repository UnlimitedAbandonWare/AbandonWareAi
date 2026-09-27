---
name: demo1-adaptive-rule-lab
description: Use when demo-1 skills, rules or directives need semantic discovery, overlap review
---

# Adaptive Rule Lab

Use a flexible semantic catalog and measured experiments to improve reusable work.
Keep the original owner, constraints and recovery capability attached to each item.

## Discovery and organization

1. Read `../semantic-catalog.yaml` and search with `python -B .agents/skills/demo1-adaptive-rule-lab/scripts/catalog.py search --query "반례 검증"` from the repository root. The generated `../SEMANTIC_INDEX.md` is a human view; `../INDEX.md` retains typed route authority.
2. On entry, run `catalog.py validate` before using reviewed metadata. Changed hashes invalidate annotations and relations. Resolve diagnostics against current source; never silently refresh a hash without reviewing the changed meaning. After current annotations pass, refresh the derived view with `catalog.py build --write`; repeat after actual invocation/completion events at task exit.
3. Use function, purpose, input, output, dependency and observation scope together. Concepts are extensible, allow multiple labels, and do not force one exclusive folder. Lexical scores identify candidates; the agent reads source meaning and records evidence lines before confirming relationships.
4. Run `catalog.py suggest` to assess overlap. Preserve distinct triggers, inputs, outputs, permissions, mandatory gates and recovery floors. Shared vocabulary is not merge authority. Use `keep_distinct`, a conditional relationship, or a reviewed merge proposal. Physical consolidation requires its existing owner workflow and current preimages.
5. Record actual route events with `catalog.py record-usage --event <repo-relative-json>`. Unknown frequency stays null. Never infer invocation from timestamps, mentions or retrieval. Compare frequency only within the same observation scope.

## Inventive experiment loop

Read [experiment-contract.md](references/experiment-contract.md) for exact commands and schemas.

1. State an original, falsifiable mechanism, predicted benefit, counterexample and protected capabilities. Public methods are optional evidence, not mandatory recipes. Keep diverse alternatives even when one wins.
2. Freeze baseline, evaluator, independent case groups, development/confirmation split, weights, latency limits, effect threshold, statistical look budget and elapsed-time ceiling before measurements. Start with the supplied policy; changes require a newly registered campaign and explanation.
3. Register a candidate with its hypothesis and changed basis. Run paired evaluations; the runner measures elapsed time and collects normalized success, quality, debug verification, error reasons and timeout events. Both arms use the same cases and alternate execution order.
4. Read the retained error analysis. Distinguish retrieval misses, constraint loss, invalid measurements, adapter failures, timeouts and uncertain effects. The agent revises the causal hypothesis, records what changed, then registers a child revision. A wording-only repeat of the same mechanism is rejected.
5. Re-experiment on development cases, compare residuals and metrics, and use fresh confirmation cases only after fixing the candidate. Do not reset a campaign, relabel correlated cases as independent, reuse consumed holdout, hide failures, or inspect outcome-driven subgroups to evade the budget.
6. Promote only a complete confirmation whose recomputed score uncertainty clears the minimum improvement and all protection gates pass. `promote` changes only this campaign's recommendation pointer. Shared SKILL/AGENTS/rule changes require a concrete evidence-bound diff and the existing owner, lease, preimage, verification and rollback workflow.
7. Retain rejected, interrupted and failed runs, causes, parent links and comparison hashes. On regression, roll back the task pointer by expected hash and record the reason. Read prior residuals before another attempt.

## Boundaries and reporting

- These rules never weaken user intent, instruction precedence, credentials, privacy, deployment authority or required proof. A higher score cannot purchase loss of a protected capability.
- Query quality is evaluator-specific; a local catalog benchmark proves route retrieval, not general answer quality. Debugging adapters must return real test/reproduction results, not the proposing agent's confidence.
- Browser/Computer provide demand-driven local UI proof; Data/Visualize explain measured artifacts. Deep Research/SciSpace/Wolfram can inform a decision with public inputs. Sites publication and AWX runtime/control mutations require their existing authority. Plugin presence alone proves no invocation or provider success.
- No scheduled watcher is created. Re-enter this skill for an eligible task; collection is automatic while its registered evaluator runs. Other tools need an explicit normalized adapter/event at the invocation boundary.
- Report `catalog_ready`, `loop_ready`, `measured_improvement_scope`, counts, actual uncertainty and remaining evidence separately. A held candidate is a valid result, not a failed obligation to manufacture a winner.

## Verification

Run `python -B -m unittest discover -s .agents/skills/demo1-adaptive-rule-lab/scripts -p 'test_*.py'`, catalog validation, and the existing skill-family postprocessor. Preserve failing evidence and use the repository checkpoint workflow for executable edits.
