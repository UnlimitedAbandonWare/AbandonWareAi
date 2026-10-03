# CODEX_HANDOFF — mAWSain do01~do05 다음 패치 심 + 금지선

Devin 조수 패킷 인계. 제품 소스는 전부 Codex 소유 — 이 문서는 심(seed)과 경계만 준다.
근거: 같은 폴더의 A1~A5 문서 (live 라인 근거 포함).

## 다음 패치 심 (우선순위 순, 1~3개)

### 심 1 — do02: `debug.trace.lookup`에 correlated 모드 (최우선)

- 무엇: `DebugTraceLookupTool`에 `requestIdHash`/`traceIdHash`/`cursor` 입력과
  `mode=correlated`를 **additive**로 추가 → `DebugEventStore.page()`로 위임.
- 재사용: `ChatApiController.java:5136`가 이미 `store.page(requestHash, traceHash, null, 200)`를
  in-process로 호출하는 선례. HTTP self-call 불필요. cursor codec은
  `DebugEventsDiagnosticsController.encode/decodeCursor` 공용 추출 후보(public 동작 불변).
- 응답 필드(additive): `scope`(correlated|event|global|global_metrics), `requestIdHash`,
  `traceIdHash`, `returnedCount`, `nextCursor`, `hasMore`, `cursorStatus`, `providerSurface`.
- 짝: `trace.snapshot`에 `stored_snapshot` 모드 — `TraceSnapshotStore.get(id)` +
  `isSafeTraceSnapshotId` + 소유권 검증. `current_request` 기본 유지.
- RED-first: `DebugTraceLookupCorrelationTest`, `TraceSnapshotToolStoredLookupTest` (A2 §6).
- contract 갱신: `tool_manifest__kchat_gpt_pro.json:93–102` 설명/`AgentToolOpsConfig` 생성자는
  시그니처가 실제 바뀔 때만.

### 심 2 — do01: `repo.scan` 루트 판정 + 한도

- 무엇: `Path.of(".")` 대신 검증된 루트 해석 + 응답 additive 필드
  (`status|rootKind|sourceSets[]|limits{}`) — do01 지시서 §Patch Blocks 스키마 그대로.
- 이 저장소 전용 사실(A1 §1): 스캔 경로 자체는 활성 레이아웃과 일치 — 문제는 CWD 의존·무한도·
  부재/조사불가 혼동. `settings.gradle`/`settings.gradle.kts` 공존에서 Groovy 승자 표기 필요.
- 가짜 pom/gradle 생성 금지; 루트 불명이면 `ambiguous_root`/`build_root_not_proven`.
- RED-first: `RepoScanToolRootContractTest` [PROPOSED].

### 심 3 — do05: UI 응답 검증 보강 (조건부, 재현 통과 후)

- 무엇: `chat-trace-ui.js` `loadSnapshot`에 최종 URL/content-type/기대 marker 또는
  `X-Trace-Storage` 분기 추가 — **단, A5 §2의 login-redirect-200 재현 fixture로 먼저 증명**.
- 재현이 안 되면 소스 변경 없이 `not_observed`로 닫는다 (조건부 갭).
- ZIP 오프라인 reader는 toolbox 확장 후보(A5 §3), exporter 신설 금지.

(do03/do04는 심 1·2 이후 독립 소패치 — `source.map` routePrefix/handler·`config.inspect` effective
모드·DebugCopilot 구조화 명령. runbook/머지 문안은 A3/A4에 준비됨.)

## 금지선 (PASTE §2 + AGENTS 정책)

- Devin/다른 에이전트가 `main/java`, `static/js`, Spring config를 패치하지 않는다 — Codex 소유.
- `AGENTS.md` 통덮어쓰기 금지 — A4 제안문만.
- `web.search` flag OFF 유지 (manifest enabled=true여도 catalog flag가 게이트;
  live ON은 별도 WP2_AC green + 사용자 승인).
- admin/HOLD/backend_unavailable을 Browser Done 조건으로 쓰지 않는다 (PROTO_OPEN).
- 새 MCP 서버/RAG 구조/trace DB/exporter/범용 shell executor 금지 — 기존 자산 재연결.
- GET 자동 순회에 `self-probe`/`repair`/`retrain`/`reset` 포함 금지.
- 인증/세션 소유권 완화 금지 — 도구에서 소유권은 `getSessionResponse` 계열 재사용,
  클라이언트 문자열을 principal로 취급 금지.
- 대상 요청 지정했는데 자료 없으면 전역 최근으로 fallback 금지; `hash:[0-9a-f]{12}` 재해시 금지.
- 응답 크기 초과 데이터는 기존 artifact-by-reference 정책 재사용.
- git push/`add -A`/`commit -a` 금지 (conditional local git 범위 내에서만).

## evidence_needed (Codex가 채울 것)

| 항목 | 필요한 관측 |
|---|---|
| live `routeCount` | 기동 중 서버의 실제 route 수 — 250 초과 여부는 runtime 호출 또는 synthetic fixture로 |
| DebugCopilot 실행 경로 | `cmdGrepTrace` 문자열이 실제 자동 실행되는 경로가 있는지 — 코드상 렌더링만 보임, executor 존재 여부 미확인 |
| stored snapshot 소유권 경로 | 도구 경계에서 인증된 principal을 얻는 기존 API (`getSessionResponse` 외 도구용 경로) |
| `:app` sourceSet/테스트 세트 | `app/build.gradle.kts` 미열람 |
| browser harness | playwright 미설치 — do05 browser 검증은 harness 결정 선행 |
| A/B 혼입 live 재현 | fixture에서만 증명된 갭 — 실 운영 혼입 관측은 별도 |

## 보고 시 분리

- `positive`(유지된 자산): `DebugEventStore.page`+HTTP cursor, bundle+ownership, SSE resume,
  toolbox+manifest 30, sanitizer/DOMPurify/lazy fetch.
- `negative`(live 확인된 갭): 도구의 global-only 조회, source.map handler 부재+250 cap,
  config.inspect presence-only, copilot POSIX 전제 명령, UI 응답 미검증.
- `evidence_needed`: 위 표.
- 실행한 명령/exit는 `VERIFY_COMMANDS.md`에 채운다 — 실행 전 PASS 선언 금지.
