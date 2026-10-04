---
name: demo1-attachment-coverage
description: Use when a Codex/agent goal comes with attachments (goal-objective.md + ## name: path list, Downloads, ZIP) — every attachment must be opened and classified, not only the convenient ones
---

# demo1 Attachment Coverage

SSOT 규칙: `docs/agents-rules/DEMO1-ATTACHMENT-COVERAGE.md` (R-A1~R-A7).
규칙을 여기에 복사하지 않는다 — 본 카드는 진입 명령과 표 형식만 다룬다.

## 명령

```powershell
python -B scripts/codex_attachment_coverage.py manifest --goal-text <goal-objective.md 또는 첫 메시지 저장본>   # 첨부 목록 + 빈 verdict 표
python -B scripts/codex_attachment_coverage.py coverage --rollout <session.jsonl>                                # 열기 증거 (미개봉 시 exit 3)
python -B scripts/codex_attachment_coverage.py gate --ledger ATTACHMENT_LEDGER.md --manifest manifest.json       # verdict 완결 검사 (exit 0 = 완료)
```

## 보고 표 (ATTACHMENT_COVERAGE N/N 필수)

| name | verdict | reason |
|------|---------|--------|
| <첨부명> | APPLIED / REFERENCE / OUT_OF_SCOPE→NEXT / CONFLICT→HOLD / DUPLICATE / UNREADABLE | 한 줄 |

- verdict 없는 첨부 = DONE 아님; OUT_OF_SCOPE·CONFLICT·UNREADABLE은 이유 필수.
- 구현은 지시서 범위만 — 미반영 첨부는 NEXT 목록으로.

## 함께 쓰는 스킬

- `$demo1-codex-goal-intake-continue` — 읽기는 완료 조건이 아니다 (수정 없이
  병용; 본 스킬이 커버리지 측정을 추가한다).
