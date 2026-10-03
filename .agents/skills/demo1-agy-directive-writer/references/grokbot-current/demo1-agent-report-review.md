---
name: demo1-agent-report-review
description: Use this when the user pastes a Codex, Devin, Clean or Grok output (or their own draft message to an agent) and asks whether it went well, what to reply, whether it is safe to send, or what to do next.
---

# demo-1 Agent Report / Draft Review

## A. Reviewing an agent's report or progress log
1. Identify the task, contract ID and the brief it was following. If you can, check the live tree: `docs/PROJECT_STATUS.md`, the `docs/diagnostics/<topic>.md` it cites, `data/agent-handoff/...`, journal status.
2. Classify each claim by evidence level: executed with command and exit code / focused tests only / static reading / mock only / not run. Keep "focused green" separate from "full suite", and "Verify-RAG exit 0" separate from "partial".
3. Check the usual failure modes:
   - It stopped after reading the goal or brief ("읽고 씹기") with no source diff and tests NOT_RUN.
   - It closed the task with a wall of HOLD/SKIP instead of finishing the scope.
   - It weakened or skipped protected tests to go green, or claimed "already exists" without a live check.
   - It waved off external API 401/403/429 as external state.
   - It touched files it did not own, stole a lease, or edited another session's diff.
   - It made forbidden moves: push/`add -A`, admin harden or proto-open=false, secrets printed, paid hammering.
4. Reply in this order:
   - **판정 한 줄** (e.g. "방향은 맞다. 아직 Done은 아니다." / "솔직하고 지시 잘 따른 중간 종료다.")
   - 잘한 점 / 실제 패치 / 핵심 HOLD / 검증 한계 (3–6 bullets)
   - Next action: either **복붙할 답장** (CONTINUE text with scope, don'ts and Done) or "지금 더 시킬 일 없음" plus optional later items.
   - `한 줄:` summary
5. If it is still running, say whether to wait or send a short nudge ("C→E만 CONTINUE"). Never tell the user to start a second session on the same files.

## B. Reviewing the user's draft before they send it ("이대로 보내도 되냐?")
1. Compare the draft against the active brief and project policy (PROTO_OPEN, sole remote AbandonWareAi, spend-guard, role ownership).
2. List the lines to **remove or fix**, each with a reason. A common case: a plugin block asks for admin login or logout-block checks, which conflict with PROTO_OPEN.
3. List what can stay.
4. Give the **full corrected text**, ready to paste.
5. End with "이대로면 CONTINUE랑 안 싸워." or name any remaining risk.

## C. Session continuity advice
- Resume the same session only when its context is short and the goal is already narrow. Otherwise recommend a new session that starts with: `새 세션 재개. 이전 스레드 로그 전체 읽지 말 것.` plus Root, the brief path, what is out of scope, and the report keyword.
- To stop a session: `이 세션은 여기서 종료한다… SESSION_END: STOP | next: new session + PASTE | no_further_edits`.
- To curb scope creep: `STOP EXPAND. 새 기능/다른 WP 시작 금지…`.
- A Codex goal file edit (with a backup, appending §N) only takes effect after a one-line nudge in the Codex window. Edit it only when the user asks.
