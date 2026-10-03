# ATT + CTX Devin assist — 2026-09-28

조수 패킷만 있다. Codex가 ATT-0→6과 CTX 제품 패치를 소유한다.
이 폴더는 `main/java`, `main/resources`, 정적 JS, YAML, 테스트를 바꾸지 않는다.

| 항목 | 상태 |
|---|---|
| A1 플러그인 로드아웃 | 작성 |
| A2 ATT 테스트 뼈대 (T01·T05·T08·T13·T17·T18) | 작성. `--tests` 칸은 비어 있음 |
| A3 CTX 관측표 | 작성. 카운트는 전부 `not_observed` |
| A4 AGENTS fragment | 제안만. `AGENTS.md` 미수정 |
| A5 스크립트 inventory + 체크리스트 스케치 | 작성. `scripts/`에 새 파일 없음 |
| CODEX_HANDOFF | seam 1개 |
| 제품 소스 diff | 없음 |
| Gradle / Node / Verify-RAG | NOT_RUN |
| Browser HOLD / admin | NOT_RUN |
| live `web.search` ON | NOT_RUN |
| AWX `build_error_mine` | NOT_RUN (실패 로그 없음) |
| commit / push | 하지 않음 |

작업 저널: `att-ctx-devin-assist-20260928-c923b84d`.
체크포인트: `data/agent-handoff/codex-autonomy/att-ctx-devin-assist-20260928-c923b84d/cycle-01`.

## Codex에 이미 준 ATT/CTX 경로

| 역할 | 경로 |
|---|---|
| ATT 킥오프 | `C:\Users\nninn\Downloads\PASTE_CODEX_ATT_GRAPHRAG_SOURCE_MOD.txt` |
| ATT 전문 | `C:\Users\nninn\Downloads\00_START_ATTACHMENT_GRAPHRAG_2026-09-28.txt` |
| CTX 킥오프 | `C:\Users\nninn\Downloads\PASTE_CODEX_CTX_PREP.txt` |
| CTX 전문 | `C:\Users\nninn\Downloads\00_START_CONTEXT_PREPARATION_2026-09-28.txt` |

## Codex 다음 1동작

`AttachmentInspectionService.inspectOne`의 내용 digest부터. 실패하는 T01을 먼저 두고, 그 파일만 고친다. `ChatWorkflow`, `StandardPromptBuilder`, YAML은 그 패치에 넣지 않는다. 자세한 멈춤 지점은 `CODEX_HANDOFF.md`.
