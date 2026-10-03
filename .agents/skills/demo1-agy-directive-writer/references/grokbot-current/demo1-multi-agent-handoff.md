---
name: demo1-multi-agent-handoff
description: Use this when several agents (Codex, Devin, Clean/Kimmi, Grok CLI, GPT Pro) work on demo-1 at the same time and you need to split roles, sequence them, prevent file collisions, or broadcast a state-change signal.
---

# demo-1 Multi-Agent Handoff & Orchestration

## 1. Role map (default; the user can override)
| Agent | Owns | Must not |
|---|---|---|
| Codex | product Java/JS/templates/yml patches, focused tests, its own ForceRestart | global clean, full suite, others' leases |
| Devin | tools/scripts/skills/AGENTS pointers, runtime evidence, UNBLOCK work | concurrent edits to Codex hot files, product source unless told |
| Clean / Kimmi (Cline) | hygiene, rails, independent verification, red-team counterexamples | demo-1 product source; note it runs on a HEAD worktree copy, so call uncommitted tools by absolute path |
| Grok CLI | auxiliary rails, `.grok/rules` pointers, mock servers | product source, hook edits |
| GPT Pro | analysis and draft directives from attachments | treating stale ZIP line numbers as live |

## 2. Plan the handoff
1. List the hot files and assign one owner per file (ownership table in every brief).
2. Sequence the work. Example: Devin UNBLOCK (lease clean-up plus minimal fix) → `UNBLOCK_REPORT YES` → Codex resumes. The waiting side reports `WAITING_FOR_PEER_UNBLOCK` instead of editing.
3. Evidence handoff goes through files, not chat: `data/agent-handoff/<agent>-<topic>-<mmdd>/{EVIDENCE.md, FOR_CODEX.md, STATUS.md}`, plus one line for the next session's first message.
4. A running Codex will not pick up new facts by itself. Either queue the note for its next session, or (only if the user asks) append a dated §N to its goal file after taking a backup and give the user a one-line nudge to send.

## 3. State-change signal (notice, not a task)
```
[DEMO1-SIGNAL-<TOPIC>-<YYYYMMDD>]
Project Root: <repo>
종류: 상태 변경 공지(작업 지시 아님). 읽고 "각자 할 일"만 반영.
무엇이 바뀌었나: <사실, 시각 KST, 근거>
공통: <이제 stale인 전제 / 옛 셸·데몬은 옛 값 기억 / 비밀 값 출력 금지>
Codex: …   Devin: …   Clean(킴미): …   Grok: …
```

## 4. Collision rules to paste into every brief
- Take a lease before editing (`agent_scope_lease.py claim` → `done`) and release on every exit path. Skip files already leased. Never force-delete lock files.
- Keep other sessions' uncommitted diffs and foreign staging as they are. No `add -A`, no push, no history rewrite.
- One Spring/DevWatch owner (port 18180). Never kill another agent's JVM or Gradle, never wipe the shared `build/`.
- Use `AWX_SPLIT_BUILD_OUTPUTS=1` and `AWX_BUILD_HOST_ID=<me>` when building in parallel.
- If a build or ownership question is ambiguous, stop with HOLD and report. Do not "fix" product code to get unblocked.

## 5. Reply to the user
Give a `받는 이 | 파일 | 역할 | 순서` table, one `말로: 「…」` per agent, and what to hold back for now ("지금은 빼도 됨"). Never broadcast to rooms or channels yourself without explicit approval. Give the text for the user to paste.
