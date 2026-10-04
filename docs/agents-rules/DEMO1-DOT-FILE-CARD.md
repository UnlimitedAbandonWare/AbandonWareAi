# DEMO1-DOT-FILE-CARD — dot 산출물은 ChatGPT Library 파일 카드로 전달

dot(ChatGPT "UnlimitedAbandon") 채팅에서 지시서·보고서·패치 브리프를 만들면,
답변 끝의 **파일 카드**가 유일한 전달 수단이다. Downloads 복사·이동은 없다
(2026-10-04 `DEMO1-DELIVERY-DOWNLOADS` 폐기).

## 절차

1. 작업 폴더(`Documents\Codex\<날짜>\task-*` 또는 현재 cwd)에 파일을 쓴다.
2. 그 파일을 ChatGPT Library 파일로 업로드해 답변에 **파일 카드**로 첨부한다.
3. 답변에는 파일명·크기·sha256 앞 12자(sha12) 한 줄만 적는다.

## 금지

- Downloads 복사·이동·경로 안내 금지. "다운로드 폴더에 넣었다"는 문장 금지.
  사용자는 카드를 눌러 받는다.
- 같은 내용의 사본을 두 군데 만들지 않는다. 파일이 여러 개면 카드도 각각.

## Windows xattr 오류는 무해

업로드 헬퍼(`library_file_transfer.py`)가 Windows `os.setxattr` 단계에서
`AttributeError`를 내는 것은 **메타데이터 단계만의 실패**다 — 업로드와 카드는
이미 완료됐다. `METADATA_SKIPPED_WINDOWS` 한 줄로 기록하고 끝. 재시도·helper
수정·우회 스크립트 작성 금지(helper는 ChatGPT가 내려주는 파일 — 고쳐도 다음에
덮어쓴다). 성공 판정 기준은 "카드(`library_file_id`)가 생겼나" 하나.

## 업로드 자체 실패

1회만 다시 시도한다. 그래도 실패하면 답변에 `카드 첨부 실패: <이유 한 줄>`과
작업 폴더 경로만 남긴다. 이때도 Downloads로 옮기지 않는다.

## 관련

- 확인 도구: `python -B scripts/dot_card_check.py --identity <identity.json>`
  → `CARD_OK` / `CARD_MISSING`. `--downloads-audit --since-minutes N`은
  Downloads에 새로 쌓인 파일을 hook 후보/사용자 다운로드로 분류한다.
- 증거: `data/agent-handoff/devin-dot-file-card-delivery-3530304b/EVIDENCE.md`.
- 폐기된 이전 계약: `docs/agents-rules/DEMO1-DELIVERY-DOWNLOADS.md` (이력용).
