# DEMO1-ATTACHMENT-COVERAGE — goal 첨부 읽기·분류 커버리지

Codex 데스크톱 goal 모드의 첨부는 `%USERPROFILE%\.codex\attachments\<id>\`에
`goal-objective.md` 하나만 놓이고, 나머지는 첫 사용자 메시지의
`## <이름>: <경로>` 목록 줄로만 전달된다. 스스로 열지 않으면 아무도 강제하지
않는다 — EVIDENCE·점검 기록이 통째로 묻히는 사고를 막기 위해 **열기·분류는
100% 필수, 구현은 범위 안만**으로 정한다.

도구: `python -B scripts/codex_attachment_coverage.py <manifest|coverage|gate>`
스킬 포인터: `.agents/skills/demo1-attachment-coverage/SKILL.md`

## 규칙

- **R-A1 (manifest 먼저)**: 목표 시작 직후 첨부 목록을 만든다 —
  `python -B scripts/codex_attachment_coverage.py manifest --goal-text <goal-objective.md 또는 첫 메시지 저장본>`
  (롤아웃이 있으면 `--rollout`). 첨부가 메시지 목록 줄로만 오는 경우도 반드시
  포함한다.
- **R-A2 (전부 열기)**: 모든 첨부를 연다. 크면 상한 읽기 허용 — md/txt는 앞·뒤
  + 제목 목차, json은 최상위 키 + 요약, zip은 목록 + README/EVIDENCE/manifest/
  checks 항목만.
- **R-A3 (첨부마다 verdict 1개 + 한 줄 이유)**:
  `APPLIED`(이번 diff 반영) / `REFERENCE`(근거로만) /
  `OUT_OF_SCOPE→NEXT`(다음 지시서·핸드오프 후보) / `CONFLICT→HOLD`(지시서와
  첨부가 다름 — 라이브 소스로 판정) / `DUPLICATE`(다른 첨부와 동일) /
  `UNREADABLE`(이유 필수). OUT_OF_SCOPE·CONFLICT·UNREADABLE은 이유 필수.
- **R-A4 (읽기·분류 100%, 구현은 범위만)**: OUT_OF_SCOPE 내용을 같은 diff에
  끼워 넣지 않는다 — 예산·lease가 우선이다.
- **R-A5 (다른 지시서는 NEXT)**: 첨부 안에 다른 Contract/PASTE 지시서가 있으면
  NEXT로 분류. 같은 세션에서 이어서 할 때는 새 ledger·journal로 분리하고
  보고에 SEQUENTIAL로 적는다.
- **R-A6 (가설이라도 연다)**: 사실 판단은 여전히 라이브 소스 우선. 다만
  "가설이라 안 열었다"는 허용되지 않는다 — 열고 나서 REFERENCE/CONFLICT로
  분류한다.
- **R-A7 (커버리지 표 없으면 DONE 아님)**: 보고서에
  `ATTACHMENT_COVERAGE: N/N` 줄과 표가 없으면 완료가 아니다. `gate` exit 0이
  완료 조건에 들어간다.

## 관련

- `$demo1-codex-goal-intake-continue` — 읽기는 완료 조건이 아니다 (함께 쓰는
  스킬; 본 규칙은 커버리지 측정을 추가).
- `docs/operations/codex-goal-footer-the-one.txt` — [ANTI-STOP] 꼬리.
- `scripts/quarantine_codex_rollout_mine.py` — redact 방식 재사용.
