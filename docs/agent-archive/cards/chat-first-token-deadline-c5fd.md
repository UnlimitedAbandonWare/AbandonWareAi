# chat.js pre-first-token client deadline can abort at min(5000, deadline) while the server budget field is 300000 ms
- card-id: chat-first-token-deadline-c5fd
- kind: failure-signature
- status: 확인 필요
- date: 2026-09-29 KST (bytes re-checked 2026-10-03)
- evidence: data/agent-handoff/grok-assist-trace-r2-jev-20260929/OBSERVE_chat_first_token_deadline.md ; main/resources/static/js/chat.js:6897,6944
- reverify: `Select-String -Path main\resources\static\js\chat.js -Pattern 'Math.min\(5000'`

## 근거
- `streamClientDeadlineMs` (chat.js:6789) = `streamServerBudgetMs`; server budget meta cap 30000,
  PageController field 300000.
- Heartbeat pre-answer wait = `Math.min(5000, clientDeadlineMs)` (chat.js:6897); second gate at
  chat.js:6944 (`streamWaitMs() >= Math.min(5000, clientDeadlineMs)`).
- Hypothesis (원문 그대로 보존): 첫 토큰 전에 클라이언트가 5초에 중단될 수 있다 — verdict 아님, patch 아님.
