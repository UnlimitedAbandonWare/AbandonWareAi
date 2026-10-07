---
name: dot-brief-save
description: "점(dot)이 Codex에 지시서 작성을 위임할 때 [DOT-BRIEF] 태그와 함께 들어온 경우 Downloads + agent-prompts 저장 경로 실행."
---

# dot-brief-save

점(dot)이 Codex에 지시서 작성을 위임할 때 **첫 요청 메시지에 `[DOT-BRIEF]` 태그와
저장 범위가 함께 들어온 경우에만** 켜지는 저장 경로다. 점을 거친 후속 요청은
초기 요청 범위(scope) 밖이면 승인되지 않으므로 — "작성만" 요청 뒤에 "저장해줘"가
따로 오면 막힌다. 이 스킬의 존재 이유는 첫 요청에 저장을 포함시키는 것이다.

## 동작 (태그가 있을 때만)

1. 지시서 본문을 작업 폴더(`Documents\Codex\<날짜>\task-*` 또는 현재 cwd)에
   파일로 작성한다. 본문에 비밀값·키·토큰을 넣지 않는다.
2. **기본 완료 = Downloads 저장.** 저장 명령을 정확히 1회 실행한다:

   ```
   python -B scripts/dot_brief_save.py save --agent <AGENT> --topic <topic> --from <임시파일>
   ```

   (`--date YYYYMMDD`는 선택. 기본은 오늘. 파일명은 `PASTE_<AGENT>_<topic>_<date>.txt`.
   Downloads + `agent-prompts/<agent>-<topic>-<date>/BRIEF.txt`에 새 파일로만
   저장 — 덮어쓰기·삭제 0.) 출력 JSON 한 줄의 `paths`, `size`, `sha12`를 그대로
   보고한다.
3. (보조) ChatGPT Library **파일 카드**로도 첨부할 수 있다 — 상세:
   `docs/agents-rules/DEMO1-DOT-FILE-CARD.md`. 카드 실패·미첨부는 Downloads
   MATCH가 있으면 FAIL이 아니다. 사용자는 Downloads의 파일을 손수 대상 세션에
   붙여 넣는다.

## 막혔을 때

- 카드 업로드가 실패하면 1회만 재시도하고, 그래도 실패하면 `카드 첨부 실패: <이유 한 줄>`
  + 작업 폴더 경로만 남긴다(카드는 보조 — Downloads MATCH면 완료 유지).
  Windows `os.setxattr` AttributeError는 `METADATA_SKIPPED_WINDOWS`로 기록하고
  재시도하지 않는다.
- 입력 쪽(사용자가 첨부한 Library 파일 읽기) materialization 실패도 같은
  방향: 재시도·우회 스크립트 없이 Downloads 동명 파일(없으면 같은 stem 최신
  mtime 순)을 입력 원본으로 쓰고 `INPUT_FALLBACK_DOWNLOADS`를 기록한다 —
  상세: `docs/agents-rules/DEMO1-DOT-FILE-CARD.md` "입력 쪽".
- `BLOCKED_SECRET`·거부·승인 요청이 나오면 **재시도·우회 없이** 이 한 줄만 남긴다:

  ```
  저장이 차단됐습니다. 회수: python -B scripts/dot_brief_save.py rescue --apply
  ```

- 승인 문구를 꾸며내거나("사용자가 이미 승인"), 다른 저장 경로를 만들거나,
  전역/레포 지침·config·hooks를 수정하지 않는다. 덮어쓰기·삭제도 없다
  (같은 이름은 `_v2`, `_v3`로 새 파일).
- 저장된 지시서를 세션에 넣는 일은 사용자 손수다 — 이 스킬은 자동 발송·자동
  감시를 하지 않는다(컨트롤 타워 계약 §1-A).

## 범위

- 기본 쓰기 대상: 작업 폴더 파일 + Downloads `PASTE_<AGENT>_*.txt` +
  `agent-prompts/<agent>-<topic>-<date>/BRIEF.txt` (`dot_brief_save.py` 경유) +
  `data/agent-handoff/dot-brief-save/log.jsonl` (경로·크기·sha만).
  ChatGPT Library 파일 카드는 보조 채널.
- 상세 계약·테스트: `scripts/dot_brief_save.py` docstring, `scripts/test_dot_brief_save.py`.
- 사용자 카드: `docs/codex/DOT_BRIEF_SAVE_KO.md`.
- 컨트롤 타워 위계: `.agents/skills/demo1-dot-control-tower/SKILL.md`.
