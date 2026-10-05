---
name: demo1-codex-selfask-triad
description: 'Use when a demo-1 Codex decision needs three read-only subagent axes (UAW self-ask): definer=contract/definition, aliaser=alias/omission, challenger=counterexample. Auto-attach on verdict-splitting RED/GREEN, second failed hypothesis, P0/P1 "done" claim, or conflicting evidence; never for typos or clear single edits.'
---

# demo1-codex-selfask-triad

Contract `DEMO1-DEVIN-CODEX-SELFASK-TRIAD-20261001`. One question is fanned out
to three read-only Codex subagents defined in `.codex/agents-staged/` (install
via `scripts/selfask_triad_install.py`; see `data/agent-handoff/selfask-triad/
W1-placement.md`). The parent stays the neutral judge — verdict semantics come
from `$demo1-vibe-selfask-judge-auto` and deliberation rules from
`$demo1-triad-deliberation`; this skill only adds the subagent fan-out. Read
those SKILL.md files for the rules; do not copy them here.

## Auto-attach (parent decides, no quiz)

- 예상과 다른 RED/GREEN (unexpected test result)
- 같은 문제에서 가설 2회 실패
- P0/P1 "완료" 주장 직전
- 근거가 둘 이상 충돌
- `$demo1-vibe-selfask-judge-auto`가 ASK_ONCE/HOLD를 고르기 직전

Never attach: 오타·문구 수정, 이음매가 명확한 단일 수정, 사용자가 토큰 절약·
중단을 지시한 경우. 한 작업당 triad 최대 3회.

## Delegate

```
python -B scripts/selfask_triad.py packet --question "<claim under review>" \
  --paths "a,b" [--task <taskId>] [--out data/agent-handoff/selfask-triad]
```

prints/writes three prompts (definer/aliaser/challenger) each embedding a
non-sensitive `deliveryMarker`; every subagent must echo
`task_received=<marker>` first under `finding` (GLM delivery rule — GLM lane calls route via `$demo1-glm-route-guard`; native `glm_worker` spawn is forbidden under ChatGPT login) and
return 4 sections + `stance:` + `evidence_tier:` (T1=file:line+test,
T2=file:line, T3=doc, T4=guess). Spawn via `spawn_agent` tool, one call per
axis. Agents are read-only and cannot nest (`multi_agent=false`).

## Budget (Zero-100 rotation)

Per branch: one bounded packet, no retry. Timeout / 429 / transport failure /
missing marker → mark the branch `NOT_RUN`, keep the remaining branches, and
name the missing axis in the verdict reason. Never wait on a hung branch.

## Judge (offline, deterministic)

```
python -B scripts/selfask_triad.py judge <definer.md> <aliaser.md> <challenger.md> \
  [--marker <marker>] [--root .] [--task <taskId>]
```

`NOT_RUN` may replace a missing branch file. Judge validates 4-sections,
marker echo, stance, tier; verifies every cited `file:line` exists under the
root; lists conflicts (same anchor + opposing stances) and `disagrees_with:`
hints; computes tier-weighted consensus; emits exactly one verdict —
`AUTO` | `ASK_ONCE` | `HOLD` — plus JSON (exit 0/3/4, same as
`agent_vibe_auto_decision.py`). Optional: on conflict the parent may run ONE
cross-query round, then re-judge.

## Rewrite & Bypass (UAW Self-Ask)

```
python -B scripts/selfask_triad.py rewrite --question "<q>" \
  [--trigger zero_hit|unexpected_red|ambiguous_goal|multi_hop|force] \
  [--paths "a,b"] [--json]
```

- 일상 바이브 작업: `--trigger` 없이 유효 `--paths`를 주면
  `bypass=strong_evidence`·`rewritten=false`·`verdict=AUTO` — FinalSigmoidGate
  strong-evidence bypass와 같은 결론으로 승인 퀴즈 없이 진행한다.
- 특수 상황(검색 0-hit, 예상 밖 RED·가설 연쇄 실패, 모호·다중 홉 질문):
  `--trigger`를 붙여 `analyze.selfAsk` 3축(`expand.selfAsk.count: 3`)
  재작성 질의를 받고 그 질의로 자율 탐색을 계속한다 — 질의는 `packet`/spawn의
  입력으로도 그대로 쓸 수 있다.
- 트리거도 유효 경로도 없으면 `ambiguous_goal`로 자동 분류된다
  (`triggerSource=auto`). 항상 오프라인·결정론적·exit 0; 재작성 자체는
  판정이 아니므로 최종 AUTO/ASK_ONCE/HOLD는 여전히 `judge`의 몫이다.

## Journal (always, even AUTO)

```
python -B scripts/work_journal.py note --root . --task <taskId> --kind plan \
  --text "SELFASK_TRIAD AUTO|ASK_ONCE|HOLD | <reason> | <question-slug>"
```

## Hard rules

- Subagents never edit/commit/push; the parent owns all writes and the final
  call. `spawn_agent` 결과는 참고일 뿐 판정·적용은 부모.
- No `model`/`model_provider` in triad TOMLs — subagents inherit the parent
  model (paid-model override stays forbidden).
- Triad never relaxes lease/secret/git-remote/fail-safe hard constraints.
