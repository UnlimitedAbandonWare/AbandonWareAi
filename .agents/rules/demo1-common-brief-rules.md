---
trigger: always_on
---

# COMMON_RULES — demo-1 에이전트 지시서 공통 규칙 (P6-S S2)

지시서에는 이 블록 대신 `COMMON_RULES: .agents/rules/demo1-common-brief-rules.md 적용` 한 줄만 적는다.
충돌 시 AGENTS.md가 이긴다. 출처: A=AGENTS.md, C=P6 Codex 지시서, D=P6 Devin 지시서 (숫자=줄).

## 금지
- git push/pull/fetch/merge/rebase/reset/clean/히스토리 재작성, `add -A`/`.`/`commit -a`/`--no-verify`, 남의 staged 탈취, remote 변경. 커밋은 `agent_git_vibe_commit.py` 단일 진입뿐. [A:320-337 C:161 D:130]
- 실제 외부 API·유료 호출 0. mock/fixture 통과는 live 검증 아님 → `NOT_RUN(실제 확인 안 함)` 표기. [A:430 C:50,165 D:32,131]
- `.secrets`/`.env*`/키 파일 열람·출력·커밋 금지. 비밀·토큰·사용자 원문은 env 이름·해시·길이만. [A:292-293,350 C:54,162 D:132]
- 전체 suite·global clean·새 npm/pip 패키지 금지. focused 테스트만. [A:203 C:161 D:23,129]
- 데이터셋·체크포인트 원본 쓰기·삭제 금지(사본만). 남의 lease·staging·미커밋·HOLD 보존. [C:8,163-164 D:19,33,128]

## lease·기록
- 변경 작업: journal open→preimage→검증→note. 자기 파일만 `source_edit_session.ps1` begin→heartbeat→end. foreign live lease는 비겹침 작업+request-release 1회, stale은 `lease_conflict_autoflow.py reclaim`만. 종료 시 lease 0. [A:218-226,231-232 C:48 D:30]

## NOT_RUN·보고
- 안 돌린 확인은 `NOT_RUN + 이유`(PASS 전환 금지). 돌린 범위만 PASS(명령·exit·tests/failures/skipped). [A:302-303 C:180,186 D:15]
- REPORT 맨 위: `실호출 0, 비밀 열람 0, 제품 소스 diff 0, Gradle 0, 종료 시 lease 0`. [D:151]

## 역할 경계
- 보조(도구) 에이전트는 제품 소스(main/java·resources·src/test·frontend·chat.js) 수정 0 — 지시서 역할 줄이 정한다. Gradle도 지시서가 정함(보조 기본 금지, `build\` 재사용 금지). [D:6-7,31]

## 에이전트 인계 3-Pack
- 다른 에이전트(Devin/Grok CLI 등)의 분석·로그를 지시서나 프롬프트에 넣을 때 아래 3요소만, 합계 20줄 이내로 정제한다. [C:WP2]
  1) 결론 ≤3줄 — 무엇을 확인했고 무엇이 문제인지.
  2) 근거 `file:line` 목록 — 라이브 트리에서 실제 확인한 위치만, 추정 제외.
  3) 필수 diff 또는 핵심 에러 ≤10줄 — 수정에 필요한 최소 블록.
- 배경 설명·시행착오 과정·대화 핑퐁·50줄 이상 비정제 원시 로그의 통붙여넣기 금지. 유입되면 수신자는 요약을 요구하고 정지한다. [C:WP2 RED/H-2]
