---
name: dot-brief-save
description: >-
  Use ONLY when the current request already contains the literal tag
  [DOT-BRIEF] (the user's ChatGPT dot — e.g. UnlimitedAbandon — delegated a
  brief-writing task to Codex and declared the save scope in the FIRST
  message). If the tag is absent, never use this skill: normal Codex chats,
  other agents' save flows (brief_save.py), and parallel lanes stay untouched.
  Saves a written 지시서 to Downloads + agent-prompts via scripts/dot_brief_save.py.
---

# dot-brief-save

점(dot)이 Codex에 지시서 작성을 위임할 때 **첫 요청 메시지에 `[DOT-BRIEF]` 태그와
저장 범위가 함께 들어온 경우에만** 켜지는 저장 경로다. 점을 거친 후속 요청은
초기 요청 범위(scope) 밖이면 승인되지 않으므로 — "작성만" 요청 뒤에 "저장해줘"가
따로 오면 막힌다. 이 스킬의 존재 이유는 첫 요청에 저장을 포함시키는 것이다.

## 동작 (태그가 있을 때만)

1. 지시서 본문을 임시 파일로 작성한다 (예: `%TEMP%\PASTE_<AGENT>_<topic>_<yyyymmdd>.txt`).
   본문에 비밀값·키·토큰을 넣지 않는다 — 저장기가 `sk-`, `AIza`, `ghp_`, `xox`,
   `-----BEGIN`, 그리고 `password` `=` 대입 형태를 보면 `BLOCKED_SECRET`으로 거부한다.
2. 저장 명령을 **정확히 1회** 실행한다:

   ```
   python -B scripts/dot_brief_save.py save --agent <AGENT> --topic <topic> --from <임시파일>
   ```

   (`--date YYYYMMDD`는 선택. 기본은 오늘. 파일명은 `PASTE_<AGENT>_<topic>_<date>.txt`.)
3. 출력 JSON 한 줄의 `paths`, `size`, `sha12`를 그대로 보고한다.

## 막혔을 때

- `BLOCKED_SECRET`·거부·승인 요청이 나오면 **재시도·우회 없이** 이 한 줄만 남긴다:

  ```
  저장이 차단됐습니다. 회수: python -B scripts/dot_brief_save.py rescue --apply
  ```

- 승인 문구를 꾸며내거나("사용자가 이미 승인"), 다른 저장 경로를 만들거나,
  전역/레포 지침·config·hooks를 수정하지 않는다. 덮어쓰기·삭제도 없다
  (같은 이름은 `_v2`, `_v3`로 새 파일).

## 범위

- 쓰기 대상은 딱 두 곳: `Downloads/` 와 `agent-prompts/<agent>-<topic>-<date>/BRIEF.txt`,
  그리고 `data/agent-handoff/dot-brief-save/log.jsonl` (경로·크기·sha만).
- 상세 계약·테스트: `scripts/dot_brief_save.py` docstring, `scripts/test_dot_brief_save.py`.
- 사용자 카드: `docs/codex/DOT_BRIEF_SAVE_KO.md`.
