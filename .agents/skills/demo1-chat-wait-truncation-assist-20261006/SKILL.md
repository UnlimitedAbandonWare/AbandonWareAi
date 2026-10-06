---
name: demo1-chat-wait-truncation-assist-20261006
description: Read-only pin, coverage, and diff checks for the Codex chat wait-progress UI and answer-truncation briefs. Product source stays with Codex.
---

# Chat wait-progress and answer-truncation assist (2026-10-06)

## When
Codex is patching `CHAT-WAIT-PROGRESS-UI-20261006` or `CHAT-ANSWER-TRUNCATION-20261006`, and the assist side needs anchors, forbid lines, or a named phrase scan.

## SSOT
`var/codex-assist-chat-wait-truncation-20261006/README.md`

## Check
```
python -B scripts/chat_wait_truncation_assist.py pin --root .
python -B scripts/chat_wait_truncation_assist.py cover --root .
python -B scripts/chat_wait_truncation_assist.py diff-forbid --root . --diff <owned.diff>
node --test scripts/chat_wait_progress_contract_tests.cjs
```

## Do not
1. Edit `main/java`, product resources, `chat.js`, or product tests from this skill.
2. Treat exit 0 as a product PASS, a finished answer, or a browser run.
3. Force-release a live source lease. Journal overlap on `chat.js`, `ChatWorkflow.java`, or `TimedChatModelCaller.java` holds that file for the owning writer.
4. Restore a paste SHA12 over a drifted CSS or template save. Re-read the live file.
5. Add a second progress endpoint, a new poll loop, or an unbounded continuation.
