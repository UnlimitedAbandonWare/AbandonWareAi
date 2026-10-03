# Idea Burst Rubric — 즉흥 아이디어 연사(Rapid-Fire) → 최적해(THE ONE)

Companion to `grokbot-playbook.md` §1 (Top10 → Shortlist3 → THE ONE). That
playbook ranks *problems*; this rubric amplifies a *fragmentary user idea*
("이거 어때?", "이렇게 하면 안돼?", one-line feature asks) into exactly one
directive-ready design. agy는 소스를 고치지 않는다 — 아이디어를 지시서로
증폭하는 것이 산출물이다.

Trigger: the user throws an unprompted/sparse idea and expects a directive or a
THE-ONE recommendation. Skip for fully specified briefs (just verify + write).

## Stage 1 — 가설 연사 (Hypothesis Burst)

Fire **3 structurally different** hypotheses immediately, one line each —
divergent, not ranked:

- **A — 최소 패치 (Minimal Patch)**: smallest diff that realizes the idea on
  existing seams. Question: "이미 있는 배선에 끼우면?"
- **B — 정석 아키텍처 (Canonical Build)**: the textbook/clean design if built
  fresh. Question: "규칙대로 다시 설계하면?"
- **C — 래퍼/어댑터 분리 (Wrapper/Adapter)**: isolate the new behavior behind a
  boundary so existing code stays untouched. Question: "바깥에 감싸면?"

Rules: no hypothesis may be a strawman of another; each names its owner lane
(Codex = product source / Devin = runtime+scripts+docs / Grok = tools+mocks /
Clean = red-team / agy = directive only). If the idea is ambiguous, the burst
runs on the *strongest reasonable interpretation* — do not stall to ask.

## Stage 2 — 반례·위험 스트레스 테스트 (Adversarial Check)

Score each hypothesis on three axes (one line each, 고/중/저 + 근거):

| Axis | What it measures |
|---|---|
| 소스 오염도 | product-source diff surface (`main/**`, `src/test/**` touched?) — agy directives should push edits to the right owner, ideally 0 for side lanes |
| 롤백 비용 | reversibility: local file edit < config flag < schema/data change < remote/irreversible |
| 병렬 충돌 | overlap with live leases/journals/handoffs — from `scripts/agent_signal_digest.py` output (stage 0), never guessed |

Counterexample duty: for the leading hypothesis, name one concrete scenario
where it fails (file:line or command evidence preferred over prose).

## Stage 3 — Triad Deliberation (긍정/부정/중립)

One pass only (`$demo1-triad-deliberation` / `positive-negative-neutral-judge`):

- **긍정**: best case for the leading hypothesis — what it unlocks.
- **부정**: strongest objection — what breaks or who pays the cost.
- **중립 판사**: fact-vs-inference verdict; 사실/추정 라벨 강제.
  Role agreement is not proof — code/test/live evidence beats consensus.

High-stakes architecture calls may escalate to
`tools\agents\cross-check.cmd <scope-path>` — a 3-way plan-mode cross-verdict
(`verdict.json`). Costs **3 live calls per run** (not $0); reserve for
decisions a single deliberation cannot settle.

## Stage 4 — 최적해(THE ONE) 수렴 → 지시서 스켈레톤 매핑

Pick exactly one winner (or a named hybrid, e.g. "A+C"). Losers go on a
"나중에" list — never silently dropped.

Map THE ONE into the directive skeleton
(`references/directive-template.md`):

| THE ONE trait | Directive field it feeds |
|---|---|
| owner lane | WP header `[CODEX\|DEVIN\|GROK\|CLEAN]` |
| 소스 오염도 0 achievable | 금지 목록: "제품 소스수정 0" + STRICT_ZERO guardrail |
| rollback-cheap steps | WP order: reversible-first, irreversible last |
| lease/journal hits seen in digest | lease · journal 절차 섹션 (check --path + work_journal open/close) |
| unverified claims from burst | 입력 판정표 RIGHT/WRONG/STALE/UNVERIFIED rows |
| residual ambiguity | 사용자 결정 ≤6 items, 권장값 먼저 |

Filename contract stays `PASTE_<TARGET>_<TOPIC>_<YYYYMMDD>.txt` in
`%USERPROFILE%\Downloads\` (SKILL.md §6), `_R2` suffix on collision.

## Stage 0 — 신호 수집 (前置, mandatory)

Before the burst: `python -B scripts/agent_signal_digest.py` (~1s, $0).
Leases, in-progress journals, fresh handoffs, git dirt, recent Grok asks and
the event bus are inputs to stage 2 — a directive that ignores a live lease or
a fresh Codex handoff is wrong by construction.
