---
name: demo1-agy-report-review
description: "demo-1 에이전트 보고서·진행 로그·포커스 테스트 로그의 주장과 Acceptance 근거를 비교해 판정 및 남은 검증을 작성할 때. 직접 구현·검사 실행·관찰하지 않은 검사 PASS 판정에는 쓰지 않음."
---

# demo1 agy 보고서 판정 / 재개 문장

레시피 원본: `.agents/skills/demo1-agy-directive-writer/references/grokbot-current/demo1-agent-report-review.md`
(읽기 전용, sha 고정). 이 파일은 agy 전용 **확인 명령**과 답 형식만 더한다.

## A. 에이전트 보고서/진행 로그 판정

확인 명령(전부 $0, 읽기):

```powershell
python -B scripts/agent_signal_digest.py --json      # 라이브 lease·journal·handoff
python -B scripts/work_journal.py show --task <id>   # 저널 상태·이벤트
Get-Content docs/PROJECT_STATUS.md -TotalCount 40    # 전체 상태 행
Get-ChildItem data/agent-handoff/<taskId> -Recurse   # 장부·체크포인트 증거
```

판정 절차(레시피 §A 그대로):

1. 작업·contract·따르던 지시서 특정. 가능하면 라이브 트리 확인
   (`docs/PROJECT_STATUS.md`, 인용한 diagnostics, `data/agent-handoff/`,
   journal status).
2. 각 주장을 증거 등급으로 분류: 명령+exit code 실행 / focused 테스트만 /
   정적 읽기 / mock만 / NOT_RUN. "focused green" ≠ "full suite".
3. 상투 실패 모드 점검: 목표 읽고 멈춤 / HOLD·SKIP 벽으로 조기 종료 /
   보호된 테스트 약화 / 401·403·429를 외부 상태로 넘김 / 남의 파일·lease
   건드림 / push·`add -A`·비밀값 출력·유료 호출.
4. 답 순서: **판정 한 줄** → 남은 일/실제 위치/HOLD/검증 단계(3줄 이내) →
   다음 행동(복붙할 답장 또는 "지금 당장 끝낼 것") → `말로: 「…」` → `한 줄:`.
5. 아직 돌고 있으면 기다릴지 짧은 재촉("CONTINUE")을 보낼지만 판정.
   같은 파일에 두 번째 세션을 열라고 하지 않는다.

## B. 사용자 초안 검수 ("이대로내도 돼?")

1. 초안을 활성 지시서와 프로젝트 정책(PROTO_OPEN, 단일 remote
   AbandonWareAi, spend-guard, 역할 소유권)과 대조.
2. **지우거나 고칠 줄**을 이유와 함께 나열.
3. 남겨도 되는 것 목록.
4. **교정 완료 전체 텍스트**를 붙여넣기 준비 상태로 준다.
5. "이대로면 CONTINUE 보내세요" 또는 남은 리스크를 명시 → `한 줄:`.

## C. 재개 문장 / 세션 연속성

- 같은 세션 재개는 컨텍스트가 짧고 목표가 이미 좁을 때만. 아니면 새 세션
  권고: `이전 세션 재개. 이전 스레드 로그 전체 생략 가능` + Root + 지시서
  경로 + 범위 밖 + 보고 키워드.
- 세션 종료 문장: `이 세션은 여기서 종료한다. SESSION_END: STOP | next: new session + PASTE | no_further_edits`.
- 범위 확장 억제: `STOP EXPAND. 새 기능/다른 WP 시작 금지`.
- Codex goal 파일 수정은 Codex 창에 한 줄 재촉이 있어야 반영 — 사용자가
  요청할 때만, 백업 후 §N 추가.

## 금지

- 파일 내용을 보지 않고 판정(읽은 것만 근거; 못 본 것은 `evidence_needed`).
- 판정 결과로 에이전트에게 직접 보내는 행위(답장 초안은 사용자에게 준다).
- 제품 소스 수정(STRICT_ZERO — 판정·문장 생산만).
