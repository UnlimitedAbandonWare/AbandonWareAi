---
name: demo-1-gpt-pro-directive-guide
description: >-
  Use this when the user will hand GPT Pro (or another sandbox model) a
  demo1_*.zip snapshot and wants a brief telling it to unpack the ZIP, actually
  do the work, and return a patch: start from the v2 harmonized template, and
  only add a task-specific brief when the user wants one.
---
# demo-1 GPT Pro ZIP Work Brief
## When
"zip 소스 줄 테니 풀어서 작업하라는 지시서", "gpt pro한테 zip 주고 수정하라고", "올라운더 프롬프트". The recipient works inside the ZIP, not on the PC.
## Default: v2 harmonized template
- File: `C:\Users\nninn\Downloads\PASTE_GPTPRO_zipwork-harmonized_20261003.txt`. About 5.9K characters.
- The user fills only 요청 / 모드 / 꼭 지킬 것 / 받을 사람.
- What the template covers:
  - Unzip into base and edit folders.
  - Input gate: stop only if the ZIP or a core attachment can't be opened.
  - A 5-line reading of the request (type plus PATCH or ANALYZE mode).
  - Evidence tags (확인됨 / 보고됨 / 공식 / 추론 / evidence_needed) and status tags (DONE / PARTIAL / NEW / CONFLICT).
  - At most 5 WPs, one root cause each.
  - Domain invariants for RAG, GraphRAG, memory, retry, status, display and logs.
  - A per-type table, the common forbidden list and the cost order.
  - A patch ready for `git apply -p1`, a zip of changed files, and REPORT.md.
  - REPORT.md order: input table, conclusion, limits, then the body.
- Edit the template into a v3. Don't write a new one each time.
- When merging other prompts into it:
  - Keep only what fits together and drop what conflicts.
  - "Never ask" and "stop at the gate" become "the gate stops only on an unreadable core input, and questions go to ASK_ONCE".
  - Old "paid false / cap 0" defaults become "keep existing defaults and only propose changes" (the cost order is firepower first).
## Task-specific variant (only when asked)
1. Unpack the ZIP on the box and read the README and MANIFEST. The core profile has no src/test and no Gradle wrapper jar.
2. Compare the target files' sha12 against the live PC tree, add verified facts, the PC tests to run, and a narrow allowed-file list.
3. Save it with the normal brief-saving procedure: write on the box, copy to the PC, move it into Downloads, and check size and sha12. Don't make an extra copy under `src\agent-prompts\`.
## After GPT Pro answers
1. Check the base hashes on the PC.
2. Run `git apply --check`.
3. Triage the REPORT with demo-1 External Plan Triage.
4. Write the Codex apply brief.
## Reply
Path, size, sha12, character count when asked, what to attach, 말로, 한 줄.
