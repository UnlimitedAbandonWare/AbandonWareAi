---
trigger: always_on
---

# 데빈 SWE-2 운영 카드

작업 분류는 `.agents/skills/demo1-devin-task-fit/SKILL.md`. 보고서 회신은 `demo1-devin-directive-loop`. 여러 seam 순서는 `demo1-devin-source-orchestrator`. 거기 있는 절차는 여기 다시 적지 않는다.

- 시작 전에 세 줄만 적는다. 목표 한 줄, 고칠 파일 목록, 완료 조건. 지시서에 있으면 그대로 옮긴다. 새 설계를 만들지 않는다.
- 애매하면 되돌릴 수 있는 가장 좁은 뜻으로 진행한다. 가정은 ledger에 한 줄 남긴다. 범위를 넓히는 뜻은 고르지 않는다.
- 웹에서 본 사실은 공식 URL이 있을 때만 쓴다. URL이 없으면 "확인 필요"라고 적는다.
- 완료는 Acceptance가 전부 PASS일 때만이다. 안 돌린 항목은 NOT_RUN이다. mock이 통과해도 라이브 검증은 아니다.
- 보고 첫 줄은 `외부 API:` 이다. report.md 앞 20줄 안에 결론을 쓴다.
- 파일을 읽었으면 다음은 수정이거나 실행이다. 계획만 쓰고 멈추지 않는다.
- 넣기 전: `python -B scripts/devin_brief_lint.py <파일>`
- 낸 다음: `python -B scripts/devin_report_gate.py --root .`
