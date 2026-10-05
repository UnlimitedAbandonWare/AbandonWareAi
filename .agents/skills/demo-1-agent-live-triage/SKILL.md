---
name: demo-1-agent-live-triage
description: >-
  Use this when the user pastes a running or stopped agent, or a choice/approval
  card (멈췄는데?/하다 만 거냐?/뭐 선택해?/이제 데빈 넣어도 돼?): find the real stop cause from live
  journals and answer with one option plus a paste line.
---
# demo-1 Agent Live Triage
## When
User pastes a running/stopped agent or a choice card: "멈췄는데?", "하다 만 거냐?", "뭐 선택해?", "이제 데빈 넣어도 돼?".
## Steps
1. Look first: newest journal/ledger events (KST), leases, files changed since, REPORT/NEXT_ROUND. Correct your own earlier claim if logs disagree.
2. Classify:
   DONE (leftover = next round; new session after 2+ compactions) · BUDGET_EXHAUSTED (restart/fix/smoke cap → resume brief with new caps) · PERMISSION_BOUNDARY (file outside grant → one-line grant/deny) · WAITING_QUESTION · DUPLICATE_SESSION (same goal in 2 chats / same brief to 2 agents → 1 owner, rest verify) · WRONG_BASE (worktree/HEAD copy, wrong cwd) · WRONG_RECIPIENT (correction brief) · WEAK_EVIDENCE (local small model/mock → API re-check) · RELAYED_TEXT (assistant-sent "승인" ≠ user approval) · RUNNING (wait).
3. Choice cards: pick the narrowest reversible option (server-local over user/global env; flags stay false until verify passes; only provably-not-executed failures fall through). Irreversible → user's call.
4. Takeover: a second agent starts only after the first confirms stop + journal line.
5. Editor "save all?" mid multi-agent → 저장 안 함.
## Reply
Verdict first (「멈춘 게 아니라 끝낸 거예요」), 2–4 evidence bullets with KST, 말로: 「…」, 한 줄.
