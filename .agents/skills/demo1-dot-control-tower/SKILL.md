---
name: demo1-dot-control-tower
description: >-
  Use when a request, file or session comes from the user's ChatGPT dot
  (UnlimitedAbandon) or a PASTE_CODEX_<UPPER_SNAKE>_<date>.md brief, or when
  another agent is about to edit rules dot recently changed: treat dot as the
  control tower, act as companion only, never revert dot's post-patch rule
  updates.
---

# demo1 dot control tower

dot(사용자의 ChatGPT "UnlimitedAbandon") = 전체 컨트롤 타워. SSOT 규칙:
`docs/agents-rules/DEMO1-DOT-CONTROL-TOWER.md`. 이 스킬은 그 규칙을 각 에이전트
세션에서 **수신·충돌·원복 방지** 관점으로 실행한다.

## 언제 쓰나

- `PASTE_CODEX_<UPPER_SNAKE>_<YYYYMMDD>.md`(대문자 dot 형식) 또는 `[DOT-BRIEF]`
  태그가 올 때
- dot이 최근 고친 룰·스킬·도구 파일을 다른 에이전트(나 포함)가 고치려 할 때
- "지금 dot 지시서 어디까지 됐어?"라는 현황 질문에

## 위계 (한 줄)

사용자 직접 지시 > dot 최신 지시서 > 기존 SSOT 문구. 비밀값·PROTO_OPEN·lease
안전 규칙은 누구도 못 바꾼다. 다른 에이전트는 companion만 쓴다 — 컨트롤 타워
역할을 가져가지 않는다.

## 수신 절차

1. **dot 지시서 수신 시**: 그 지시서의 범위(scope)·lease·금지 목록을 먼저 확인.
   지시서가 룰·스킬을 고치라 하면 `agent-scope-lease` claim 뒤에만 쓴다.
2. **내 작업이 dot 지시서와 파일이 겹치면**: HOLD. 직접 판단해 고치지 말고
   `python -B scripts/dot_tower_status.py --since-hours 24` 출력을 첨부해
   사용자(또는 상위 지시서)에게 넘긴다.
3. **companion(보조 지시서)로 끼어들 때**: dot 지시서가 잡은 파일과 disjoint
   유지. 같은 파일이면 companion은 도구·검증·문서 보조만.

## 원복 방지 (ratchet)

- dot이 패치 성공 뒤 룰·스킬을 갱신하면 옛 문구로 되돌리지 않는다.
- 구조 자체는 불변식으로 잠긴다: `python -B scripts/behavior_ratchet.py
  --config configs/dot-tower-ratchet.json --lock configs/dot-tower-ratchet.lock.json
  check` → INV-T1~T5. 되돌린 흔적이 보이면 `TOWER_CONFLICT`로 ledger에 적고
  사용자에게 한 줄 — 직접 복구하지 않는다.
- `flags.promoteDateBlockToFail`이 false인 동안 INV-T4는
  `WARN_PENDING_DOT_LANE`(dot lane이 F6 파일을 고치는 중). dot의
  OAUTH_CONTINUITY_RULES 반영이 확인되면 플래그를 true로 올리고 `update`로 잠근다.

## 현황 도구

`python -B scripts/dot_tower_status.py [--since-hours N] [--json]`
— Downloads·`Documents\Codex\<날짜>\task-*`의 `PASTE_CODEX_*.md` 목록,
in-progress journal/lease 매칭(RUNNING/CLOSED/NOT_STARTED), dot-tower
ratchet 요약. 읽기 전용, 네트워크 0.

## 금지

- 다른 에이전트 창·방·DM에 자동 게시(dot 자신의 ChatGPT 내 발송은 사용자가
  그 안에서 하는 것이라 예외)
- dot이 쓴 문장 삭제·되돌리기, 옛 문구로 "정리"
- 날짜(예: 2026-12-31)만으로 OAuth 사용을 끊는 로직이나 문장 추가
- ChatGPT/OpenAI 토큰·쿠키·자격 증명 읽기·복사·출력, 비밀값 출력
- "구독 = 무제한" 단정, 영구 1등 모델 하드코딩, 다른 CLI로 토큰 옮기기 권장
