# DEMO1-DOT-FILE-CARD — dot 지시서 완료 = Downloads sha12 MATCH (카드는 보조)

> 2026-10-05 정정(아침 페이스 복구): dot 지시서의 **완료 조건은 Downloads에
> sha 일치 사본이 놓이는 것**이다. ChatGPT Library 파일 카드는 보조 전달
> 수단이다 — 있으면 붙이고, 실패·미첨부여도 Downloads MATCH가 있으면 완료다.
> 2026-10-04의 "카드가 유일한 전달 수단" 계약은 폐기한다.

## 절차

1. 작업 폴더(`Documents\Codex\<날짜>\task-*` 또는 현재 cwd)에 지시서 파일을 쓴다.
2. `python -B scripts/dot_brief_save.py save --agent <AGENT> --topic <topic>
   --from <파일>`을 1회 실행해 Downloads(+agent-prompts)에 놓는다.
3. 답변에는 파일명·크기·sha256 앞 12자(sha12) 한 줄만 적는다.
4. (보조) 같은 파일을 ChatGPT Library 파일 카드로도 첨부할 수 있다. 카드가
   실패해도 Downloads MATCH가 있으면 지시서 완료다.

## 금지

- Downloads 사본 없이 "카드 올렸다"만으로 완료 보고 금지.
- 전 에이전트 Stop 스캔·전역 자동 복사 부활 금지 — dot 지시서만
  `dot_brief_save.py save`로 Downloads에 둔다(상세:
  `DEMO1-DELIVERY-DOWNLOADS.md` 상단 정정).
- 같은 내용의 사본을 두 군데 이상 만들지 않는다. 파일이 여러 개면 카드도 각각.

## Windows xattr 오류는 무해

업로드 헬퍼(`library_file_transfer.py`)가 Windows `os.setxattr` 단계에서
`AttributeError`를 내는 것은 **메타데이터 단계만의 실패**다 — 업로드와 카드는
이미 완료됐다. `METADATA_SKIPPED_WINDOWS` 한 줄로 기록하고 끝. 재시도·helper
수정·우회 스크립트 작성 금지(helper는 ChatGPT가 내려주는 파일 — 고쳐도 다음에
덮어쓴다). 카드 성공 판정 기준은 "카드(`library_file_id`)가 생겼나" 하나 —
그리고 카드는 어디까지나 보조다.

## 카드 업로드 자체 실패

1회만 다시 시도한다. 그래도 실패하면 답변에 `카드 첨부 실패: <이유 한 줄>`과
작업 폴더 경로만 남긴다 — Downloads MATCH는 그대로 유효하다(카드 없이도 완료).

## 입력 쪽: Library 첨부 읽기 실패 → Downloads 폴백 (2026-10-06)

위는 출력(카드 업로드) 쪽 규칙이다. **입력**(사용자가 첨부한 Library 파일을
읽는 것)의 materialization이 실패하면 — Windows `os.setxattr` AttributeError
포함 — 재시도·우회 스크립트 없이 `C:\Users\nninn\Downloads`에서 **같은
파일명 → 같은 stem의 최신 mtime 순**으로 찾아 그 파일을 입력 원본으로 쓴다.

- 기록: `INPUT_FALLBACK_DOWNLOADS` + 이름·크기·sha12·mtime(KST)·후보 수.
- 후보가 0개면 그때만 `입력 파일 없음: <파일명>`으로 HOLD — 사용자 질문
  카드는 띄우지 않는다.
- 비밀 파일(.env·.secrets·토큰·키·자격 증명 파일)을 입력으로 쓰는 질문에는
  이 폴백을 적용하지 않는다(기존 판정 유지).
- 저장소 밖에 새 helper 사본·우회 스크립트를 만들지 않는다.
- 분류기: `codex_question_classifier.py` D38 — 이 형태의 질문은 AUTO로
  picked=Downloads 파일 사용.

## 관련

- 완료 판정 도구: `python -B scripts/dot_card_check.py --identity <identity.json>`
  → `CARD_OK` / `CARD_MISSING`(보조 채널 검사용). `--downloads-audit
  --since-minutes N`은 Downloads에 새로 쌓인 파일을 분류한다.
- 증거: `data/agent-handoff/devin-dot-file-card-delivery-3530304b/EVIDENCE.md`.
- 도구 계약: `scripts/dot_brief_save.py` docstring,
  `docs/agents-rules/DEMO1-DELIVERY-DOWNLOADS.md`(dot 지시서 한정 부활).
