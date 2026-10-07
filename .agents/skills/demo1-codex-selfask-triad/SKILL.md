---
name: demo1-codex-selfask-triad
description: 'Use when a demo-1 Codex decision needs three read-only subagent axes (UAW self-ask): definer=contract/definition, aliaser=alias/omission, challenger=counterexample. Use for explicit three-way long-tail query branching. Auto-attach on verdict-splitting RED/GREEN, second failed hypothesis, P0/P1 "done" claim, or conflicting evidence; never for typos or clear single edits.'
---

# demo1-codex-selfask-triad

Contract `DEMO1-DEVIN-CODEX-SELFASK-TRIAD-20261001`. One question is fanned out
to three read-only Codex subagents defined in `.codex/agents-staged/` (install
via `scripts/selfask_triad_install.py`; see `data/agent-handoff/selfask-triad/
W1-placement.md`). The parent stays the neutral judge — verdict semantics come
from `$demo1-vibe-selfask-judge-auto` and deliberation rules from
`$demo1-triad-deliberation`; this skill only adds the subagent fan-out. Read
those SKILL.md files for the rules; do not copy them here.

## Codex 판정 경계 (3 branches / 4 perspectives)

- 정의/별칭·누락/반례는 탐색 분업이며 고정 찬성·반대 표가 아니다. 각 축은 SUPPORT/OPPOSE/UNSURE 어느 쪽도 낼 수 있다. 부모가 긍정 최선안, 부정 근거, 구체 반례, 중립 baseline·더 단순한 대안을 함께 검토한다. 중립은 네 번째 모델 호출이 아니다.
- 최신 사용자 목표·허용 경로·취소된 범위·완료 조건을 먼저 고정한다. 일반 문구 수정은 inline 판단으로 끝내고 모든 질문에 세/네 모델을 호출하지 않는다. 한 번의 bounded packet만 만들고 같은 조회·검증을 중복하지 않는다.
- 현재 tool schema가 `spawn_agent(task_name, message)`만 지원하면 전체 role prompt를 message에 전달한다. packet의 `agent_type`은 역할 표기이며 등록·자동 로드 증거가 아니다. staged TOML 존재만으로 설치를 주장하거나 개인/plugin 스킬을 복제·수정하지 않는다.
- 시간·범위·재평가 종료는 기존 [deadline/scope 본문](../../../docs/agents-rules/DEMO1-DEADLINE-SCOPE-JUDGMENT.md)을 따른다. 아래 최대 3회는 packet 수의 상한이며 같은 판단의 재평가 횟수를 늘리는 허가가 아니다. 목표가 검증되면 종료하고 취소된 범위를 재개하지 않는다.

## Auto-attach (parent decides, no quiz)

- 예상과 다른 RED/GREEN (unexpected test result)
- 같은 문제에서 가설 2회 실패
- P0/P1 "완료" 주장 직전
- 근거가 둘 이상 충돌
- `$demo1-vibe-selfask-judge-auto`가 ASK_ONCE/HOLD를 고르기 직전

Never attach: 오타·문구 수정, 이음매가 명확한 단일 수정, 사용자가 토큰 절약·
중단을 지시한 경우. 한 작업당 triad 최대 3회.

## Compact development evidence handoff

For delegated development findings, use [context-handoff.md](references/context-handoff.md).
`packet --task <taskId>` binds task/scope/delivery marker and packet generation time;
unknown source identity stays unknown. Return the core summary and metadata in
existing four sections. The parent samples source support and risk branches;
the helper remains structural advice. This contract also fits an existing
read-only explorer; it does not activate triad for simple low-risk work.

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
marker echo, stance, declared tier and cited `file:line` existence; lists
same-anchor conflicts and `disagrees_with:`; computes a weighted structural
recommendation `AUTO` | `ASK_ONCE` | `HOLD` (exit 0/3/4).
This is advisory: path/line existence and declared T1 do not verify content,
executed tests, authority, independence, or whether a counterexample survives.
A real opposing finding at a different anchor can be missed by the score.
The parent reads each claim's cited evidence and the counterexample outcome,
then selects, shrinks, or discards the proposal against the current baseline.
A helper AUTO never grants authority; ASK_ONCE is not an automatic user quiz.
On unresolved local conflict, prefer one permitted discriminating check or
scope reduction. Any cross-query re-evaluation follows the existing cap above.

## Rewrite & Bypass (UAW Self-Ask)

```
python -B scripts/selfask_triad.py rewrite --question "<q>" \
  [--trigger zero_hit|unexpected_red|ambiguous_goal|multi_hop|force] \
  [--paths "a,b"] [--json]
```

- 현재 helper는 `--trigger` 없이 존재하는 파일 `--paths`를 주면
  `bypass=strong_evidence`·`rewritten=false`·`verdict=AUTO`를 반환한다.
  이는 rewrite 생략 표기이며 파일 본문 검증이나 최종 AUTO 판정의 증거가 아니다.
  긴꼬리/모호함/다중 홉 조건이 있으면 경로 존재로 생략하지 말고 적합한 explicit trigger를 고른다.
- 특수 상황(검색 0-hit, 예상 밖 RED·가설 연쇄 실패, 모호·다중 홉 질문):
  `--trigger`를 붙여 `analyze.selfAsk` 3축(`expand.selfAsk.count: 3`)
  재작성 질의를 받고 그 질의로 자율 탐색을 계속한다 — 질의는 `packet`/spawn의
  입력으로도 그대로 쓸 수 있다.
- 트리거도 유효 경로도 없으면 `ambiguous_goal`로 자동 분류된다
  (`triggerSource=auto`). 항상 오프라인·결정론적·exit 0; 재작성 자체는
  판정이 아니며 `judge`의 AUTO/ASK_ONCE/HOLD도 형식 추천이다. 최종 내용·반례·권한·scope 판정은 부모가 한다.

### Long-tail 전달과 판정 근거

- 원 질문의 조건·예외·부정 조건·마지막 홉을 보존한다. `rewrite`의 세 `subqueries`를 축에 맞춰 기존 packet의 해당 role prompt에 덧붙여 전달한다. packet의 원 질문을 지우거나 세 질의를 각 branch에 모두 중복 전달하지 않는다. 현재 helper가 질문 의미로 trigger를 자동 식별한다고 주장하지 않는다.
- 각 finding은 `주장 → 현재 source 위치·시점 → 실제 관찰/시험 상태 → 반례 결과 → 채택/축소/기각 이유`로 짧게 연결한다. 동일 원본의 재인용·세 역할 합의·가중 점수를 독립 증거로 세지 않는다. reasoning 원문 대신 결론·근거·대안만 전달한다.
- 호출/반환 receipt와 trigger·재작성 축·판정에 사용한 근거를 기존 own journal에 간단히 남긴다. inline 검토, packet 생성, 실제 branch 호출/반환, 정적 검사, 모델 품질 효과를 구분한다. 호출 표시가 없으면 NOT_OBSERVED이며 미발동으로 단정하지 않는다.

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
