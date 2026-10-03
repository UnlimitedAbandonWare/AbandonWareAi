# A2 — do02 조수: 동일 요청 조회 계약 맵 (최우선 지원)

Codex가 `debug.trace.lookup`/`trace.snapshot`을 **이미 있는 exact-filter + cursor 계약**에
재연결할 때 필요한 계약만 정리한다. 구현·수정 제안이 아니라 **live 시그니처 지도**다.
전부 live 트리 라인 근거(사실). ZIP 증거 E02/E03/E06/E07과 일치 확인.

## 1. 저장소 계약 (이미 존재 — 재사용 대상)

### `main/java/com/example/lms/debug/DebugEventStore.java`

| API | 라인 | 계약 |
|---|---|---|
| `record EventPage(List<DebugEvent> items, String nextId, boolean hasMore, String cursorStatus)` | 237 | cursorStatus = `initial` / `ok` / `evicted` |
| `EventPage page(String requestIdHash, String traceIdHash, String afterId, int limit)` | 240–260 | **filter-before-limit**(ring 삽입 순서), `afterId` 커서 소멸 시 `EventPage(...,"evicted")`, limit clamp 1..500 |
| `List<DebugEvent> list(int limit)` | 225 | 전역 최근 — correlation 없음 |
| `DebugEvent get(String id)` | 283 | 단건 |
| `listByProbe / listFingerprints` | 263 / 296 | 보조 전역 조회 |

`DebugEvent` record 필드 (`DebugEvent.java:17–33`):
`id, ts, tsMs, level, probe, fingerprint, message, sid, traceId, requestId, thread, where, data, error, agg`
→ **`traceId`/`requestId`는 emit 시점에 이미 `hash:`-정규화된 값**(`correlationTraceId()`/
`correlationRequestId()` 경로, `DebugEventStore.java:787–852`). 다시 해시하면 exact join이 깨진다
(`ChatApiController.java:5131` 주석이 같은 사실을 명시).

### `main/java/com/example/lms/trace/TraceSnapshotStore.java`

| API | 라인 | 계약 |
|---|---|---|
| `Optional<TraceSnapshot> get(String id)` | 441 | ring 조회, 소멸 시 `Optional.empty()` — 소멸과 부재 구분 근거 |
| `record TraceSnapshot(id, tsEpochMs, tsIso, sid, sessionId, traceId, requestId, reason, method, path, status, error, hasMlBreadcrumbs, traceEntryCount, mdc, trace, orchestration, html, htmlTruncated)` | 161–181 | `requestId`/`traceId`는 저장 시 해시 정규화; `sessionId`로 소유권 검증 재료 존재 |
| `captureCurrent / captureCustom` | 185 / 196 | 캡처 경로(참고) |
| `listSummaries(int)`, `retentionStats()` | 411 / 426 | 요약/보존 메타 |

safe-id 검증기: `ChatTraceMetaMessageRestorer.isSafeTraceSnapshotId(snapshotId)` — `ChatTraceMetaMessageRestorer.java:206` (호출부 136).

### `main/java/com/example/lms/search/TraceStore` (현재 요청 전용)

`TraceStore.getAll()` / `getByPrefix(prefix)` — 프로세스·현재 요청 컨텍스트 맵.
과거 요청 조회 수단이 아니다 (E03 확인).

## 2. HTTP 계약 (이미 존재 — 참조 구현)

### `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java` — `/api/diagnostics/debug`

| endpoint | 라인 | 계약 |
|---|---|---|
| `GET /events/page?limit&requestIdHash&traceIdHash&cursor` | 100–113 | `store.page` 위임. 응답 `EventPage(items,nextCursor,hasMore,cursorStatus)`; evicted → **410 Gone** |
| `exactHash` | 115–119 | `hash:[0-9a-f]{12}` 외 입력 → 400 `invalid_event_page` |
| `encodeCursor` | 121–124 | b64url(`v1\n{eventId}\n{requestHash}\n{traceHash}`) — **cursor가 filter 집합에 바인딩** |
| `decodeCursor` | 126–141 | 포맷/버전/id 패턴 `[0-9a-f]{1,16}-[0-9a-f]{1,16}`/filter 일치/재인코딩 동등성 검사 → 불일치 시 400 |
| `GET /events` | 93–96 | `store.list(limit)` 전역 |
| `GET /events/{id}` | 147–150 | 단건 |
| `GET /events/stream` (SSE) | 172–270 | `Last-Event-ID` 재접속 + `gap` 이벤트(`cursor_not_retained`/`replay_window_exceeded`/`historyComplete:false`) |
| `GET /fingerprints` | 152–155 | 전역 fingerprint 요약 |
| `GET /api-failures` | 62–69 | 별도 incident 표면 |

### 번들 소비자 (참조) — `ChatApiController.java`

- `GET /api/chat/sessions/{id}/traces/{snapshotId}/html?format=html|bundle` (5071)
- `sessionTraceBundle` 내부: `traceBundleEvents.page(requestHash, traceHash, null, 200)`
  (5136–5137) — **store.page를 filter 인자로 직접 호출하는 in-process 선례**.
  해시 parity 규칙: `snapshot.requestId()/traceId()`가 `hash:[0-9a-f]{12}` 매치일 때만 사용 (5132–5135).

## 3. 도구 표면 현황 (갭)

### `debug.trace.lookup` — `main/java/com/abandonware/ai/agent/tool/impl/ops/DebugTraceLookupTool.java:41–65`

| 입력 | 현재 |
|---|---|
| `eventId` | `store.get(eventId)` 단건 (48–53) |
| `limit` | `store.list(limit)` **전역 최근** (54–55), clamp 1..100 |
| `requestIdHash` / `traceIdHash` / `cursor` / `mode` / `snapshotId` | **없음** |
| 부가 | `debugAi = metrics.compactSnapshot(min(limit,50))` — 전역 메트릭 (60–63) |

### `trace.snapshot` — `TraceSnapshotTool.java:31–67`

| 입력 | 현재 |
|---|---|
| `prefix`, `limit` | `TraceStore.getAll()/getByPrefix` — **호출한 진단 요청의 현재 TraceStore** |
| `snapshotId` (stored) | **없음** — 과거 요청의 TraceSnapshot 조회 불가 |

### 별칭 충돌 주의 (서로 다른 제공자 표면)

| 표면 | toolId/별칭 | 실제 |
|---|---|---|
| `providerSurface=java_agent_tool` | `trace.snapshot` | 현재 요청 TraceStore |
| `providerSurface=control_tower` | `trace.snapshot` (alias) | `trace_snapshot_probe` — Desktop 런타임 가용성 메타데이터 probe (toolbox) |
| `providerSurface=http` | `/api/diagnostics/debug/events/page` 등 | 위 §2 |

호출 기록에 `providerSurface + toolId + mode`를 함께 남겨야 한다 (지시서 §1과 동일 결론).
레지스트리 충돌이 실제 발생했다는 증거는 없음(추정→사실 전환 금지).

## 4. 갭 표 (do02가 메울 것 — 이름만)

| 필요한 것 | 이미 있는 계약 | 도구에 없는 것 |
|---|---|---|
| 요청별 이벤트 | `store.page(requestIdHash, traceIdHash, …)` | tool 입력 `requestIdHash`/`traceIdHash` |
| 페이징 | `afterId` + `EventPage.nextId` + `cursorStatus` | tool 입력 `cursor`, 출력 `nextCursor/hasMore/cursorStatus` |
| filter↔cursor 바인딩 | HTTP b64url `v1\n…` codec (공용 추출 후보) | tool은 미구현 |
| 과거 스냅샷 | `TraceSnapshotStore.get(id)` + `isSafeTraceSnapshotId` | `stored_snapshot` 모드 + 소유권 검사 |
| 과거 요청의 correlation | `TraceSnapshot.requestId()/traceId()` | tool이 snapshot→hash 파생 경로 사용 |
| 구분 라벨 | — | 응답 `scope`(correlated|global|global_metrics), `providerSurface` |

**설계상 확인점**(Codex 판단): tool이 HTTP를 self-call하지 않고 `store.page`를 직접 호출하는 것이
정석 — `ChatApiController:5136`가 이미 그 선례. cursor codec는 public HTTP 동작을 바꾸지 않는
범위에서 공용 추출 또는 재사용.

## 5. 재현 체크리스트 (제품 수정 없이 갭을 증명하는 절차)

1. synthetic fixture: `DebugEventStore` 인스턴스에 request A 이벤트(`requestId="hash:aaaaaaaaaaaa"` 형태의
   테스트용 해시)와 request B 이벤트를 혼합 emit.
2. `DebugTraceLookupTool.execute`에 `limit`만 준 호출 → A+B 혼합 관찰 (갭 재현, 예상 FAIL).
3. 같은 fixture로 `store.page(A.hash, null, null, N)` 직접 호출 → A만 반환 (계약 정상 동작 확인 —
   이미 `DebugEventsPageTest`가 HTTP층 계약을 커버).
4. 대조: 조회 scope 라벨 부재 시, 응답의 어느 이벤트가 어느 요청 것인지 소비자가 구분 불가.

## 6. Codex용 최소 실패 테스트 스케치 (이름만 — 구현은 Codex)

| 테스트 (제안명) | 핵심 assertion |
|---|---|
| `DebugTraceLookupCorrelationTest` | A/B 혼합 fixture에서 correlated 모드는 A만; filter-before-limit; invalid hash → 명시적 invalid input; cursor↔filter 불일치 → invalid; ring 소멸 → evicted; store null → `available:false` |
| `TraceSnapshotToolStoredLookupTest` | stored A vs 현재 B 구분; 소유권 불일치 거부; 만료/부재 시 전역 fallback 없음; `current_request` 기본 호환; model/retrieval/memory write 0 |
| (유지) `DebugEventsPageTest`, `DebugEventsDiagnosticsControllerSseLifecycleTest` | HTTP 계약 회귀 — 이미 존재, 재실행만 |
| (유지) `ChatTraceDurableDetailTest`, `ChatTraceSnapshotPointerPersisterTest` | 소유권/pointer 회귀 — 이미 존재 |

소유권 검증은 `sessionId`/`sid`/기존 `getSessionResponse` 경로를 재사용하되, 클라이언트 제공
문자열을 인증 principal로 취급하지 않는다 — 검증된 세션/권한 획득 경로가 도구 경계에 있는지가
Codex의 확인 지점이다 (`evidence_needed` 항목, CODEX_HANDOFF 참조).
