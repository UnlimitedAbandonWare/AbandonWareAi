---
name: demo1-dot-control-tower
description: "dot(사용자의 ChatGPT UnlimitedAbandon) = 지시서 작성 + Downloads 배달 역할. 각 에이전트 세션에서 수신·충돌·원복 방지 관점으로 실행."
---

# demo1 dot control tower

dot(사용자의 ChatGPT "UnlimitedAbandon") = **지시서 작성 + Downloads 배달**
역할(2026-10-05 아침 페이스 재고정). SSOT 규칙:
`docs/agents-rules/DEMO1-DOT-CONTROL-TOWER.md`. 이 스킬은 그 규칙을 각 에이전트
세션에서 **수신·충돌·원복 방지** 관점으로 실행한다.

## 언제 쓰나

- `PASTE_CODEX_<UPPER_SNAKE>_<YYYYMMDD>.md`(대문자 dot 형식) 또는 `[DOT-BRIEF]`
  태그가 올 때
- dot이 최근 고친 룰·스킬·도구 파일을 다른 에이전트(나 포함)가 고치려 할 때
- "지금 dot 지시서 어디까지 됐어?"라는 현황 질문에
- 스킬·`[DOT-BRIEF]`·첨부 지시서 없는 **맨명령**으로 제품 작업을 시작하려 할 때
  (거절 절차는 아래)

## 역할 게이트 (아침 페이스)

- **dot Primary**: 목표·스킬·자료를 받으면 `PASTE_CODEX_*.md` 또는
  `PASTE_<AGENT>_*.txt` 지시서를 만들고 Downloads에 sha12 MATCH 사본이 놓일
  때까지. 그것이 dot의 완료다.
- **사용자 손수 적용**: 그 파일을 Codex/세션에 붙여 넣는 일과 적용·보류
  판단은 사용자 몫. dot·companion 어느 쪽도 세션에 자동 발송하지 않는다.
- **dot Forbidden(오토 실패 원인)**: 제품 소스 장시간 단독 패치 루프, 여러
  Codex 세션 자동 발송·자동 감시 책임, 패치 성공 후 룰·스킬 자동 대량 갱신
  루프(사용자 요청 단건 companion만), 스킬/`[DOT-BRIEF]`/첨부 지시서 없는
  맨명령으로 제품 작업 시작.
- **맨명령 거절 한 줄**: `스킬 또는 지시서(또는 [DOT-BRIEF])를 먼저 넣어
  주세요. 맨명령으로 제품 수정·멀티세션 오토는 하지 않습니다.`

## SERIAL_LANE (세션당 활성 지시서 1개)

- 한 Codex/dot 작업 세션 = **활성 지시서 1개**. 진행 중 목표에 새 PASTE·새
  역할(서브딜러↔제품↔지시서작성)을 같은 컨텍스트에 합치지 않는다.
- 새 목표가 오면 (a) 현재 목표를 Acceptance/HOLD 보고로 닫거나 (b) 거절한다.
- **거절 한 줄**: `지금 세션은 활성 지시서 1개만 수행합니다. 새 목표는 현재
  작업을 HOLD/완료 보고한 뒤 새 세션(또는 새 PASTE 손수 부착)으로 주세요.
  합치면 컨텍스트가 오염됩니다.`
- companion: 메인 lease/journal RUNNING 중 새 제품·새 주제 PASTE 주입 금지 —
  disjoint 지원 artifact만 만들거나 대기.
- 룰·스킬 갱신은 닫힌 지침 뒤 단건 — 패치와 같은 턴에 섞지 않는다.
- 탐침: `dot_session_hygiene.py`의 `MULTI_GOAL_CONTAMINATION` /
  `ROLE_SWITCH_MID_SESSION`. 잠금: dot-tower-ratchet `INV-S1~S3`.

## ASSIST_PAIR (읽기 전용 듀얼 레인, SSOT §1-C)

- 세션 첫 메시지 `[DOT-ASSIST-PAIR] lanes=<taskIdA>,<taskIdB>
  mode=read-only` 선언이 있을 때만 성립 — 목표 1개 = "레인 A·B
  어시스트". 최대 2레인, 도중 추가·교체 금지(바꾸려면 RESUME 카드로
  닫고 새 세션).
- 한 턴 = 한 레인. 턴 시작에 `var/codex-assist-dot-pair/<taskId>.card.md`
  만 읽고 끝에 그 카드만 갱신한다(대화 기억 의존 금지).
- 읽기 예산: 메인 레인 ledger에서는 `journal.json` + `*-green.json` +
  `*-red.json` + `final-test-counts.json`만. `*.java`/`*.py` 스냅샷,
  64KB 초과 파일, rollout 본문은 읽지 않는다.
- 산출물은 레인별 조수 PASTE(도구·스크립트만) 또는 재개 한 줄을
  Downloads에 — 사용자 손수 부착, 세션 자동 주입·자동 재개 금지.
- 용량 이어달리기: `ROLLOUT_NEAR_CAPACITY` 신호 또는 dot 응답 잘림이면
  두 카드 + `RESUME_<date>.md`(≤20줄, `demo1-session-state-checkpoint`
  형식)를 쓰고 `새 dot 세션에 RESUME 파일만 붙여 주세요` 한 줄로 멈춤.
- 카드 생성: `python -B scripts/session_context_lane_board.py --run
  --cards --task <idA> --task <idB> --out var/codex-assist-dot-pair`
  → `*.card.md`(≤2KB) + `pair.json`(plannedScope 겹침 경고).
- 탐침: `ASSIST_PAIR_OK` = 위반 아님(count만). 선언 중인데 3번째 PASTE
  식별자 또는 작성 역할 마커가 나오면 `assistPairBreach:true` + 기존
  `MULTI_GOAL_CONTAMINATION`/`ROLE_SWITCH_MID_SESSION` 유지. 잠금:
  dot-tower-ratchet `INV-P1~P4`.

## SUB_REPORT_V1 (하위 에이전트 1회 완결 보고)

하위 에이전트의 첫 보고는 6칸(전체 상태·항목별 표·입력 원본·쓰기 장부·own
lease 잔존·NOT_RUN)을 갖춘다. 칸 누락 시 되묻지 말고 검사 결과를 돌려보낸다:

- 계약: `references/sub-report-contract.md`
- 검사: `python -B scripts/dot_card_check.py --sub-report <보고 파일>`
  (PASS일 때만 사용자에게 올린다)
- SKIP 근거 표: `python -B scripts/brief_save.py cover --topic <주제>
  --terms "t1|t2"` 출력을 그대로 붙인다.

## 위계 (한 줄)

사용자 직접 지시 > dot 최신 지시서 > 기존 SSOT 문구. 비밀값·PROTO_OPEN·lease
안전 규칙은 누구도 못 바꾼다. 다른 에이전트는 companion만 쓴다 — dot의
지시서 작성 역할을 가져가지 않는다.

## 수신 절차

1. **dot 지시서 수신 시**: 그 지시서의 범위(scope)·lease·금지 목록을 먼저 확인.
   지시서가 룰·스킬을 고치라 하면 `agent-scope-lease` claim 뒤에만 쓴다.
2. **내 작업이 dot 지시서와 파일이 겹치면**: HOLD. 직접 판단해 고치지 말고
   `python -B scripts/dot_tower_status.py --since-hours 24` 출력을 첨부해
   사용자(또는 상위 지시서)에게 넘긴다.
3. **companion(보조 지시서)로 끼어들 때**: dot 지시서가 잡은 파일과 disjoint
   유지. 같은 파일이면 companion은 도구·검증·문서 보조만.

## 원복 방지 (ratchet)

- dot이 패치 성공 뒤 룰·스킬을 갱신하면 옛 문구로 되돌리지 않는다(갱신 자체는
  사용자 요청 단건으로만 일어난다 — SSOT §1-A).
- 구조 자체는 불변식으로 잠긴다: `python -B scripts/behavior_ratchet.py
  --config configs/dot-tower-ratchet.json --lock configs/dot-tower-ratchet.lock.json
  check` → INV-T1~T5 + INV-R1~R4(아침 페이스 계약). 되돌린 흔적이 보이면
  `TOWER_CONFLICT`로 ledger에 적고 사용자에게 한 줄 — 직접 복구하지 않는다.
- `flags.promoteDateBlockToFail`이 false인 동안 INV-T4는
  `WARN_PENDING_DOT_LANE`(dot lane이 F6 파일을 고치는 중). dot의
  OAUTH_CONTINUITY_RULES 반영이 확인되면 플래그를 true로 올리고 `update`로 잠근다.

## 현황 도구

- `python -B scripts/dot_tower_status.py [--since-hours N] [--json]`
  — Downloads·`Documents\Codex\<날짜>\task-*`의 `PASTE_CODEX_*.md` 목록,
  in-progress journal/lease 매칭(RUNNING/CLOSED/NOT_STARTED), dot-tower
  ratchet 요약. 읽기 전용, 네트워크 0.
- `python -B scripts/dot_session_hygiene.py [--since-hours 36]`
  — 세션 hygiene 탐침(LARGE_ROLLOUT/ROLLOUT_NEAR_CAPACITY/PARTIAL_STAGING/
  BRIEF_NOT_IN_DOWNLOADS/BARE_AUTO_SUSPECT/MULTI_GOAL_CONTAMINATION/
  ROLE_SWITCH_MID_SESSION/ASSIST_PAIR_OK). 읽기 전용, 본문 미출력.
- `python -B scripts/session_context_lane_board.py --run --cards --task
  <idA> --task <idB> --out var/codex-assist-dot-pair` — ASSIST_PAIR 레인
  카드(≤2KB) + pair.json 생성. 읽기는 journal 메타·green/red·test
  카운트·lease 상태뿐.

## 금지

- 다른 에이전트 창·방·DM에 자동 게시(dot 자신의 ChatGPT 내 발송은 사용자가
  그 안에서 하는 것이라 예외), 여러 Codex 세션 자동 발송·자동 감시 책임
- dot이 쓴 문장 삭제·되돌리기, 옛 문구로 "정리"
- 날짜(예: 2026-12-31)만으로 OAuth 사용을 끊는 로직이나 문장 추가
- ChatGPT/OpenAI 토큰·쿠키·자격 증명 읽기·복사·출력, 비밀값 출력
- "구독 = 무제한" 단정, 영구 1등 모델 하드코딩, 다른 CLI로 토큰 옮기기 권장
- 스킬·`[DOT-BRIEF]`·첨부 지시서 없는 맨명령으로 제품 수정 시작
