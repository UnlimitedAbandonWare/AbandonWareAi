# T01–T16 완료표 — /chat 답변별 디버깅 트레이스 복원

기록 규칙 (지시서 §7):
- **PASS = 실행 명령 + exit code + 관측 결과가 있을 때만.** "테스트 파일을 만들었다"는 통과가 아니다.
- `NOT_RUN`에는 사유를 적는다. 실제 브라우저를 못 열면 `BROWSER_NOT_VERIFIED`, 실제 외부 API 미사용이면 `LIVE_PROVIDER_NOT_VERIFIED`.
- 정적 스모크(`scripts/mgain_trace_smoke.py`) 통과는 T항목의 증거가 아니다 — 별도 보조 수단.
- JS 회귀 하네스: `node --test src/test/js/<file>.test.cjs` (기존 `chat-trace-restore.test.cjs` 확장 우선, 새 하네스 체계 신설 금지).
- Java 타겟 테스트 예: `gradlew.bat test --tests "com.example.lms.service.trace.*" --tests "com.example.lms.api.ChatTrace*" --tests "com.example.lms.api.TraceSnapshots*"` (실제 클래스명으로 조정).

| ID | 재현 | 합격 조건 | 상태 | 명령·exit | 증거/비고 |
|---|---|---|---|---|---|
| T01 | 관리자 ON, 검색 포함 질문 | 해당 답변 아래 실제 `<details>` 패널과 A(검색)/B(컨텍스트)/C(오케스트레이션) 섹션 표시 | NOT_RUN | | |
| T02 | 관리자 OFF | 기존 안전 요약 정책 유지, 새 상세자료 노출 요청 없음 | NOT_RUN | | |
| T03 | 비관리자/익명 `debug=true` | 상세 HTML·snapshot 자료 노출 없음, 기존 채팅 경로 유지 | NOT_RUN | | |
| T04 | 같은 턴 prefetch/final/replayed trace | 패널 한 개, 최종 내용으로 갱신, 펼침 상태 유지 | NOT_RUN | | |
| T05 | assistant 두 개가 같은 transcript 부모 공유 | 서로 다른 패널, 이전 패널 덮어쓰기 없음 (WeakMap 소유권) | NOT_RUN | | |
| T06 | Web off, Vector on | Vector 최종 컨텍스트 관측, Web은 not_requested/disabled로 구분 | NOT_RUN | | |
| T07 | Web off, Vector off | 모델/실행 상태 패널, 검색 성공값 조작 없음 | NOT_RUN | | |
| T08 | snapshotId ≠ traceTurnId ≠ turnId fixture | 정확한 snapshotId로만 `/api/diagnostics/trace/snapshots/{id}/html` 요청 | NOT_RUN | | |
| T09 | 새로고침·세션 A→B→A 왕복 | 같은 메시지의 trace 복원, 늦은 fetch 결과 혼합 없음 (세대 번호/AbortController) | NOT_RUN | | |
| T10 | snapshot 404 / 저장소 unavailable | 요약 유지 + 상세 부재 표시, full trace 복원 성공이라 주장 금지 | NOT_RUN | | |
| T11 | 관리자 실행 중 재접속·권한 변경 | 재생 스트림도 현재 구독자 권한 적용, token/final/cancel/ACK 보존 | NOT_RUN | | |
| T12 | 악성 HTML/URL/event attribute (`script`, `img onerror`, `javascript:`, `data:`) | 스크립트·외부 자원 실행 없음, 안전한 구조만 유지 (DOMPurify fragment) | NOT_RUN | | |
| T13 | 패널 업데이트 중 키보드/TTS | summary 키보드 조작 가능, 자동 낭독·TTS에 디버그 본문 제외, details에 `aria-hidden` 금지 | NOT_RUN | | |
| T14 | 대형 trace/여러 과거 턴 | 렌더·요청 수 상한 준수 (HTML 60k/표 100행/이벤트 200/열린 snapshot ≤2 제안값), 스크롤·메모리 폭증 없음 | NOT_RUN | | |
| T15 | 디버그 저장·렌더 실패 주입 | 답변 생성·저장·ACK가 디버그 오류로 실패하지 않음 (fail-soft) | NOT_RUN | | |
| T16 | sync/nonstream 경로 | 질문 재생성 없이 해당 답변의 요약/상세 복원 | NOT_RUN | | |

## 정적 보조 확인 (verify-v2 구분 유지)

아래는 "도구가 실행됐다"와 "대상이 검증됐다"를 구분하기 위한 보조 단계다.

| 단계 | 명령 | 기대 | 실제 |
|---|---|---|---|
| 패치 전 baseline 재현 | `node C:\Users\nninn\Downloads\MGAIN_DEBUG_TRACE_RESTORE_2026-09-26\tools\read_only_renderer_probe.mjs main\resources\static\js\chat.js` | `literalHtmlStoredAsText=true`, `appendedHolderCount=2` (SSOT `renderer_probe_result.json`과 동일) | |
| 정적 스모크 (advisory) | `python -B scripts\mgain_trace_smoke.py` | exit 0, 가드 FAIL 0건, R항목 상태 표 | |
| 정적 스모크 (post-patch strict) | `python -B scripts\mgain_trace_smoke.py --strict` | exit 0 (복원 산출물 존재 + 가드 통과) | |
| JS 회귀 | `node --test src\test\js\chat-trace-restore.test.cjs` (+ 신규 trace UI 테스트) | exit 0 | |
| Java 타겟 | `gradlew.bat test --tests "*Trace*"` (실제 모듈/클래스로 조정) | exit 0 | |
| 컴파일+라이브 반영 | `gradlew.bat compileJava` → ForceRestart/DevWatch (`$demo1-dev-reload`) | BUILD OK + served asset 갱신 | |

## 라이브 앵커 (수시 재탐색 — `mgain_trace_smoke.py` 실행 시 항상 최신 줄번호 출력)

확인 시점 2026-09-26 기준 대표 앵커 (Codex는 패치 전 반드시 재확인):

- `main/resources/static/js/chat.js`: `appendMessage` ~3060 (모든 메시지가 `dom.chatMessages` 직접 자식), `replaceWithSanitizedHtml` ~3134 (`trace.textContent = cleanHtml` — F1), `renderTraceHtml` ~3152, `type === "trace"`/`"trace_html"` 디스패치 ~6039–6051, `streamUrl` ~6851 (`debug` query 없음 — F3), `validateTurnTraces` ~1155, `renderRestoredTurnTrace` ~1192, `createSseEventParser`/`decodeSseEvent` ~681/814, `markChatDiagnosticNode` ~2827.
- `main/resources/templates/chat-ui.html`: DOMPurify 로드 ~14, chat.js cache key `?v=` ~17, `[data-admin-diagnostics]` ~56 (`th:if=chatDiagnosticsEnabled`).
- `main/java/com/example/lms/web/PageController.java`: `chatDiagnosticsEnabled = isAdmin(auth)` ~333.
- `main/java/com/example/lms/config/ChatUiViewConfig.java`: Jsoup 투영 ~96–135, 비관리자 `[data-admin-diagnostics]` 제거 ~111.
- `main/java/com/example/lms/api/ChatApiController.java`: `exposeTrace` 필드 ~930, `/state` `debug` param ~1209, stream `debug` ~1588, prefetch trace emit `(debug || exposeTrace)` ~2255, final trace ~2573–2591.
- `main/java/com/example/lms/service/trace/TraceHtmlBuilder.java`: `rawTrace == null → ""` ~54 (F4), `renderRawSearchPanel` ~196, `renderOrchestrationPanel` ~171, `buildSummaryLine` ~629.
- `main/java/com/example/lms/api/TraceSnapshotsDiagnosticsController.java`: `GET /api/diagnostics/trace/snapshots/{id}/html` ~238.
- 보안: `AppSecurityConfig` — `GET/POST /api/diagnostics/**` → `hasRole("ADMIN")` (~177–178); `ChatOpenSecurityConfig` — `/api/chat/**` permitAll 체인 (~175+). 체인 우선순위는 테스트로 확인.
- `main/resources/application.yml`: `web.trace.expose: ${WEB_TRACE_EXPOSE:false}` ~455, `abandonware.web.trace.expose` ~465.
