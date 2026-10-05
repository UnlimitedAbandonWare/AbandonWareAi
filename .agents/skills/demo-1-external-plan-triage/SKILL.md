---
name: demo-1-external-plan-triage
description: >-
  Use this when the user pastes or attaches a GPT Pro answer, PLAN/P6/UAW
  analysis, ZIP snapshot or another agent's design doc and wants a source brief:
  verify each claim on the live tree, keep only real in-scope items, and say
  what was dropped.
---
# demo-1 External Plan Triage
## When
"첨부 다 읽고 코덱스한테 지시서", "GPT Pro가 한 말인데", "PLAN5 위주로 분석", "첨부로 뭐 더 보낼 거 없나? 정제해줘".
## Steps
1. Inputs table: name | bytes | sha12 | read fully Y/N. If a core file is truncated, say so and write no brief from partial input.
2. Pull claims (defect, `file:line`, symbol, number, rule). Re-find each in the live tree by symbol/content, never by ZIP line number (`scripts/zip_path_to_sourceset.py`, `p6dbg_claim_drift.py`). Note HEAD and KST time.
3. Tag each claim: LIVE / MOVED / ALREADY_DONE (REPORT/ledger) / STALE / UNVERIFIED.
4. Policy filter: PROTO_OPEN / admin-hardening → HOLD (one question max). Pilot caps/budgets are proposals, not policy. Cost follows Codex credits → paid API → free → Ollama last, with a live-call cap.
5. Scope: one theme, ≤5 WPs per round (else phases), RED→GREEN per item; files other sessions are editing = "남의 hunk 보존".
6. Route: product source → Codex brief; checkers/tools → companion brief.
## Reply
`넣은 것 / 뺀 것(이유) / HOLD` + path, 말로, 한 줄.
예: 「PLAN5에서 지금 재현되는 8건만 넣었고, PROTO_OPEN 관련 2건은 HOLD했어요.」
## Don't
Pass stale line numbers through; let an attachment outrank the live tree; port web/blog code without checking Java 17 · LangChain4j 1.0.1.
