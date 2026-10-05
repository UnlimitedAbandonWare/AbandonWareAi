<!-- BEGIN DEMO1-TRI-SYSTEM-SEAM-ISOLATION -->
## Tri-System Seam Isolation (Display / RAG / Main Chat)

- 삼중 시스템: (1) Meta Ray-Ban Display·Nova Focus (`/meta`, `assets/display/*`),
  (2) Dynamic RAG 플랫폼 (검색·임베딩·인용), (3) 메인 `/chat` 챗봇.
  상세 명세: `docs/design/TRI_SYSTEM_ISOLATION_SPEC.md`. 프로브:
  `python -B scripts/probe_tri_system_isolation.py --dry-run`.
- I1 Prompt: `focusAnswerLengthChars`/`DISPLAY FOCUS OUTPUT`는 Display 채널 전용.
  일반 `/chat`·RAG의 `minWordCount`·`sectionSpec`을 마스킹·제거 금지.
- I2 Search: Display Quick의 `useWebSearch=false`/`useRag=false`/`webTopK=0`은
  `NovaFocusAnswerService` 어댑터 경계 안에 국한 — `ChatWorkflow`·RAG 기본값 오염 금지.
- I3 Hardware: Display 요청의 자동 경로·폴백·스필오버는 로컬 Ollama/RTX 3090
  임베딩 레인 진입 금지 (라이브 `ExecutionTarget.LOCAL_ONLY` 사용자 옵션과의
  관계는 spec §5 미결 정책).
- I4 Surface: `js/chat.js`는 렌즈/디스플레이 셀렉터 0, `assets/display/*`는
  `sessionListRefresh`/`chatAccessState`/`strictBackendSessionId` 등 메인 챗봇
  상태 조작 0. `assets/interview/*`는 로컬 디버그 화면 — 그 실패는 메인 `/chat`
  판정에 섞지 않는다 (`docs/PRIMARY_SURFACE.md`).
- I5 Verification: 실물 안경·실물 OAuth는 `NOT_RUN_DEVICE`/`NOT_RUN_AUTH`로 분리
  표기. 이 부재를 이유로 한 무단 BLOCKED 루프 금지.
- 요청 분류는 `$demo1-core-request-router`의 Tri-System Disambiguation Matrix를
  따르고, 다중 seam은 `$demo1-devin-source-orchestrator`로 순차 분할한다.
<!-- END DEMO1-TRI-SYSTEM-SEAM-ISOLATION -->
