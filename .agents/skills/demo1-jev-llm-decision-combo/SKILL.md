# demo1-jev-llm-decision-combo

Use when a main-chat `/chat` decision bug appears ("searched when it shouldn't",
"held the answer when it shouldn't") or the routing policy needs re-evaluation.
The rule: **add a case, not a regex**.

## Data + tool layout (all existing)

- Cases: `data/eval/decision-combo/cases.jsonl` — one JSONL line per case:
  `{"id","src","question","context":string|null,"expect":{"search":"NONE|RECENT_ONLY|SCOPED_RAG|WEB|HYBRID|CLARIFY","hold":"ANSWER|ANSWER_HEDGED|HOLD"},"rationale":"one line"}`
- Evaluator: `scripts/decision_combo_eval.py`
- Config (providers/models/thresholds only): `configs/decision-combo.yaml`
- Latest report: `data/eval/decision-combo/latest/{comparison.md,records.json,recorded/*.json}`
- Handoff: `data/agent-handoff/devin-jev-llm-decision-combo-8d094e46/`

## Workflow for a new decision failure

1. Write the failing utterance as a `cases.jsonl` line with expected labels.
   Ambiguous intent -> `CLARIFY`. `HOLD` is for contradictory evidence only.
2. Re-evaluate offline (no network, replays recorded Jev/LLM responses):
   `python -B scripts/decision_combo_eval.py --offline --out data/eval/decision-combo/latest`
   Cases without recordings defer to the safe default — the table still prints.
3. If you need a real judgment for new cases: `--live` (needs `AI_GATEWAY_API_KEY`,
   `GROQ_API_KEY`, `AWX_AGENT_SESSION`; caps Jev 80 / LLM 40; no 401/403/429 retry).
4. Tune only `configs/decision-combo.yaml` (`combo.confidence_floor`,
   `default_on_low_confidence`, `llm.model_preference`). Never grow
   `RulesBaseline` patterns or the floor lists to chase the score — the rules
   column must stay a faithful port of `SearchDecisionService` AUTO.

## Architecture being measured

`floor (explicit prohibition | self-contained arithmetic -> NONE)` ->
`Jev /v1/evaluate (searchRoute + releaseGate, one call)` ->
`LLM referee only on defer / CLARIFY / rules-disagreement` ->
`low confidence -> HYBRID + ANSWER_HEDGED`; `HOLD only on contradiction`.

Product wiring plan for Codex lives in FOR_CODEX.md (shadow -> on, rollback env).
