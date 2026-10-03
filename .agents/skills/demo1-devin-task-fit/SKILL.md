---
name: demo1-devin-task-fit
description: "붙여 넣은 데빈 지시서를 SWE-2에 맞는지 나눠, 잘 맞으면 진행하고 애매한 설계는 넘길 문장만 남길 때. 보고서 회신 루프나 멀티 seam 실행 순서에는 쓰지 않음."
---

# Devin SWE-2 task fit

Devin Desktop의 SWE-2 Max는 파일과 완료 조건이 적힌 수정, 테스트, 터미널 재현에 쓴다. 목표만 있고 범위가 없는 설계는 여기서 진행하지 않는다.

운영 카드: `.windsurf/rules/devin-swe2-operating.md`.
숫자 요약: `references/failure-modes.md`.
맞음 표: `var/codex-assist-devin-swe2-tune/devin_task_fit_matrix.md`.
스킬 짧은 목록: `references/devin-skill-shortlist.md`.

보고서 회신·답장 지시서는 `$demo1-devin-directive-loop`.
여러 seam의 실행 순서는 `$demo1-devin-source-orchestrator` (`python -B scripts/devin_task_orchestrate.py plan`). 그 절차를 여기 복사하지 않는다.

## 분류

지시서를 읽은 뒤 아래 중 하나만 고른다.

| 판정 | 언제 | 다음 행동 |
|---|---|---|
| 잘 맞음 | 고칠 파일과 Acceptance가 있다. 단계가 10개 이하다. | 세 줄(목표, 파일, 완료 조건)을 옮기고 바로 수정하거나 실행한다. |
| 조건부 | 파일이 여러 개다. | WP 5개 이하로 나누고, 각 WP에 파일 목록을 붙인 뒤 진행한다. |
| 안 맞음 | "전체 개선"처럼 범위가 없거나, 구조·정책 결정이거나, 웹 사실 조사이거나, 제품 방향이다. | 그 부분만 HOLD. 아래 넘길 문장을 출력하고, 파일 목록이 있는 다른 부분은 계속한다. |

검사기: `python -B scripts/devin_brief_lint.py <PASTE파일>`. FIT가 `HANDOFF`면 안 맞음, `SPLIT`이면 조건부, `GOOD`이면 잘 맞음이다. 검사기가 빠뜨린 항목은 제안 문장대로 지시서에 빈칸을 채우라고 적고, 빈칸을 추측해서 설계로 바꾸지 않는다.

## 넘길 곳

안 맞음 부분마다 한 줄만 낸다.

- 설계·제품 소스(`main\`, `frontend\`): `Codex에게: <목표 한 줄>. 파일은 <아는 경로 또는 모름>. 데빈은 파일 목록이 생긴 뒤에 수정한다.`
- 분석 초안: `GPT Pro에게: <비교할 사실>. 결론 초안만. 데빈은 그 초안을 실행 지시로 받은 뒤에 움직인다.`
- 지시서 작성: `Grok Bot에게: <목표>. 수정 허용 파일, 금지, Acceptance, NOT_RUN, 외부 API 첫 줄을 넣어 데빈 지시서로 써 달라.`

웹 사실은 공식 URL이 있을 때만 적는다. 없으면 `확인 필요`. 보고된 벤치마크 숫자는 근거로 쓰지 않는다.

## 진행 규칙

- 시작 세 줄 안에 새 설계를 넣지 않는다. 가정은 ledger에 한 줄.
- 애매하면 되돌릴 수 있는 가장 좁은 해석만 고른다.
- 완료는 Acceptance가 전부 PASS일 때만. 안 돌린 것은 NOT_RUN. mock 통과는 라이브가 아니다.
- 보고 첫 줄은 `외부 API:`.
- 파일을 읽었으면 다음은 수정이거나 실행이다.
- reasoning effort는 사용자가 UI에서 고른다. 설정 파일은 바꾸지 않는다. 기계적 정리는 낮게, 보통 수정은 기본, 큰 코드베이스 디버깅은 Max. 이 세 줄은 가설이라 확인 필요.

## 검사

```text
python -B scripts/devin_brief_lint.py <PASTE파일>
python -B scripts/devin_report_gate.py --root .
```

리포트 검사의 진행 중 ledger(최근 2시간 또는 활성 journal·lease)는 SKIP이다. `scripts/devin_session_guard.py`는 프로세스·락 점검이라 리포트 내용 검사와 겹치지 않는다.
