# A5 — do05 조수: 진단 UI/ZIP 재현 체크리스트 + 검증 포인트

Codex가 UI 상태 구분을 패치할 때 쓰는 **재현 절차**다. 제품 코드 수정 없이 현재 동작을
읽는 방법만 담는다. 전부 live 트리 근거(사실), 재현 시나리오는 합성 fixture 기준.

## 1. 관련 자산 지도 (live)

| 자산 | 위치 | 계약 |
|---|---|---|
| 답변 trace HTML/bundle | `GET /api/chat/sessions/{id}/traces/{snapshotId}/html?format=html\|bundle` — `ChatApiController.java:5071` | 소유권 체인: session → typed pointer → assistantId 일치(5078–5101). 불일치·unsafe id·부재 → 404 |
| 저장소 표시 | `X-Trace-Storage: ring \| durable_projection` — `:5106–5117` | ring HTML 우선, 없으면 durable projection 렌더 + 헤더 |
| bundle 내용 | `sessionTraceBundle` `:5120–5200` | `summary.json`·`trace.json`(diagnostics 있을 때)·`events.ndjson`(ring에 있을 때)·`README.txt`·`manifest.json`; schema `awx.answer-trace-bundle.v1`, `checksumsSha256`, 총 256KiB 초과 → 413; `historyComplete:false`, `logs`는 의도적 unavailable |
| UI fetch | `chat-trace-ui.js:485–540` (`loadSnapshot`) | lazy, `AbortController`, version guard, `SNAPSHOT_FETCH_TIMEOUT_MS`, 동시 fetch cap. 오류 문구 분기: 404 / 401·403 / ≥500 / timeout / network |
| 다운로드 링크 | `chat-trace-ui.js:471–478` | `…/html?format=bundle`, `download="answer-trace-bundle.zip"` |
| 비세션 상세 | `/api/diagnostics/trace/snapshots/{id}/html` (UI `:508–510`) | `/api/diagnostics/**`는 GET·POST 모두 `hasRole("ADMIN")` — `AppSecurityConfig.java:177–178` (`/api/diagnostics/display` GET만 `:137,146`에서 permitAll) |
| mutating GET 주의 | `/api/diagnostics/trace/memory/self-probe` — GET·POST 둘 다, synthetic trace 설치+snapshot capture 수행 | passive route 순회 대상 아님 (지시서 E11) |

**live-확인 갭 (E08 조건부, live에서도 동일)**: `loadSnapshot`은 `response.ok`만 본다 —
최종 `response.url`, `Content-Type`, `X-Trace-Storage`를 읽는 분기가 없다
(`chat-trace-ui.js:511–527` grep 교차 확인: `X-Trace-Storage|response.url|Content-Type` 0 hit).
→ **로그인 redirect 뒤 200 HTML을 trace 본문으로 렌더할 수 있는지는 미검증 가설** — fixture로 증명 대상.

## 2. 네 갈래 재현 체크리스트

각 칸: [유도 방법 (합성 fixture)] → [기대 UI/응답] → [FAIL 기준]. 서버 fixture는 mock 응답 또는
소유 세션의 synthetic snapshot — 실 계정/실제 프롬프트 사용 금지.

### A. 권한 거부 (401/403)

- 유도: 세션 소유 아닌 상태로 `/api/chat/sessions/{id}/traces/{sid}/html` 호출, 또는
  ADMIN 아닌 자격으로 `/api/diagnostics/trace/snapshots/{id}/html`.
- 기대: UI "상세 조회 권한 없음" (이미 `:519–520` 분기 존재); 자동 관리자 로그인/재시도/우회 없음.
- FAIL: 권한 오류를 "자료 없음"으로 합치거나, login form HTML을 trace로 렌더.

### B. 자료 소멸/부재 (404 / evicted / durable-only)

- 유도: ring에서 evicted된 snapshotId, 또는 durable projection만 남은 답변.
- 기대: "찾을 수 없음" 또는 `X-Trace-Storage: durable_projection`에 상응하는 "보존된 요약" 표시 —
  전체 로그가 살아있는 것처럼 위장 금지. `/events/page`의 evicted는 **410**(controller :112).
- FAIL: 다른 요청의 최근 자료로 대체 표시; `historyComplete:false`를 완전 이력처럼 표기.

### C. 서버 오류 (500/503)

- 유도: detail 경로가 5xx를 내는 fixture.
- 기대: "서버 오류로 상세 조회 불가" (`:520–521`), transport 실패와 구분된 문구.
- FAIL: 5xx를 네트워크 오류로 표기하거나 무한 재시도.

### D. 네트워크 오류/timeout

- 유도: fetch reject(offline)와 `SNAPSHOT_FETCH_TIMEOUT_MS` 초과 두 경우를 **구분해** 재현.
- 기대: timeout → "시간 초과. 다시 펼쳐 주세요" (`:529–530`); network → "네트워크 오류" (`:531–533`).
- FAIL: 두 사유를 한 문구로 합치기; abort된 요청이 늦게 resolve해 패널을 덮음
  (version guard `:515,:525–526` 회귀).

### 보조 시나리오 (지시서 §5 표와 대응)

- **로그인 redirect → 200 HTML**: content-type + 기대 fragment marker 조합 미검증 →
  fixture로 재현 목표. text/html 하나로는 판별 불가(로그인도 HTML).
- **A/B 동시 세션**: 상대의 trace/events/다운로드 링크 혼입 없음 — 이벤트는 A만 (A2 §5와 연결).
- **panel open/close/전환**: lazy fetch·취소·구식 응답 무시 유지 (version/abort 회귀).
- **passive probe**: `self-probe`/`repair`/`retrain`/`reset` 자동 순회 0회 — 실행하는 GET이 아닌
  allowlist 의미 기반만.

## 3. bundle 오프라인 리더 체크리스트

새 exporter가 아니라 **기존 ZIP을 읽는 reader** 기준 (toolbox 확장 후보 — 구현은 Codex):

- 허용 entry: `summary.json`, `trace.json`, `events.ndjson`, `README.txt`, `manifest.json` —
  이 다섯 외 entry, 절대경로, `..` traversal, 중복 entry, symlink → 거부.
- 크기: 비압축 총량·entry 수를 exporter와 같은 한도로 (256KiB 기준선 — `ChatApiController:5187`);
  과도한 압축률 거부. 메모리에서 bounded로 읽고 자동 extract/execute 금지.
- `manifest.json` 필수 필드: `schema=awx.answer-trace-bundle.v1`, `redactionVersion`,
  `snapshotId`, `assistantMessageId`, `sources{summary,trace,events,logs}`, `checksumsSha256`.
- `checksumsSha256`으로 멤버 무결성 확인 — 단, 이것은 **전송/변조 검사이지 발급자 서명이 아니다**.
  발급 출처·scope는 별도 확인. manifest는 자기 인증하지 않는다.
- 요약 출력: summary/trace/events 각각 `available|unavailable|truncated`; `historyComplete:false`와
  `logs.unavailable`을 보존 — 빈 정상 데이터로 치환 금지.
- reader는 model/검색/DB/메모리 write 0회; report artifact 저장만 명시 허용; 외부 업로드 금지.
- before/after 비교는 동일 schema·동일 synthetic 시나리오·비교 가능 build 정보만;
  `capturedAt`에 offset 없는 값을 임의 UTC로 표시 금지.

## 4. 관련 테스트 자산 (live에 이미 존재 — 실행만)

`src/test/java`: `DebugEventsPageTest`, `DebugEventsDiagnosticsControllerSseLifecycleTest`,
`DebugEventsDiagnosticsControllerFailureSignalTest`, `TraceSnapshotsDiagnosticsControllerTest`,
`TraceSnapshotsDiagnosticsSecurityIntegrationTest`, `ChatTraceDurableDetailTest`,
`ChatSessionTraceDetailTest`, `ChatTraceMetaMessageRestorerTest`,
`ChatTraceSnapshotPointerPersisterTest`, `ChatApiControllerTraceMetaTest`,
`TraceSnapshotExporterTest`, `TraceSnapshotFilterTest`, `TraceSnapshotRedactionTest`,
`RepoScanToolRedactionContractTest`, `SourceMapToolTest`, `DebugCopilotServiceTest`,
`DebugCopilotTriadicAdjudicationTest`, `AgentToolOpsConfigContextTest`,
`InternalAgentToolProbeRoundTest`, `InternalAgentToolControllerSecurityTest`

`src/chatUiTest/java` (`chatUiTest` task): `TraceSnapshotStoreAgentVisibleHtmlFocusedTest`,
`ChatTraceRestoreTest`, `ChatApiControllerLocalLlmTraceContractTest`

## 5. 브라우저 테스트 환경 (사실)

- `playwright.config.*` 부재, `frontend/package.json`에 playwright 의존성 없음
  (Next.js BFF, scripts: dev/build/start/lint/test=node --test).
- 지시서의 `npm run test:agent-debug`는 **제안 예시** — 등록되지 않은 스크립트를 실행했다고 보고 금지.
- `answer-trace-diagnostics.spec.ts`는 [신규 제안] — harness 결정부터.
