# mxasain 트레이스 이식 분석 근거

기준일 2026-09-27. C는 현재 ZIP, L은 구형 ZIP의 archive-relative 경로다. 아래 코드 블록의 숫자는 해당 원본 파일의 1-based 줄 번호다. 파일은 수정하지 않았다. secret 설정 파일은 인용하지 않는다.

**검증 수준:** archive 전체 목록 조사 + 관련 코드/호출부 추적 + 실제 두 Java 클래스 실행 + 실제 browser JS 실행 + 추출 SSE cursor 실행. 전체 애플리케이션 build/E2E/실제 모델 호출은 미실행.

## 입력 식별

```json
{
  "mxasain.zip": {
    "sha256": "2f9590c7806f63b1ff960089359efef338c6322ede433d3b0f2009a02e934a58",
    "files": 2364,
    "expandedBytes": 21714537
  },
  "src111_mergex15.zip": {
    "sha256": "4381359c3f04f57ad309f1da950192c2f3484e63950507e170bbf79c7d0e33f1",
    "files": 4252,
    "expandedBytes": 16275149
  }
}
```

## E01 — 구형 흐름 설계의 명시적 기록

구형 문서가 기술한 설계 의도다. 문서에 나온 모든 패널이 과거 실행됐다는 증명은 아니다.

### L: `docs/TRACESTORE_FLOW.md:1–123`

```text
    1 | # TraceStore 키 흐름도 (Flow)
    2 | 
    3 | 이 문서는 **src111_merge15 패치(2026-01-05 계열)** 기준으로,
    4 | 요청 처리 파이프라인에서 **어떤 TraceStore 키(및 TraceLogger 이벤트)**가 *언제* 찍히는지
    5 | “흐름(Flow)” 관점에서 정리한 것입니다.
    6 | 
    7 | > 용어
    8 | > - **MDC**: 로그 패턴에 찍히는 ThreadLocal (sid/trace...).
    9 | > - **TraceStore**: request-scoped KV bag (Trace HTML / JSON 디버그 패널용).
   10 | > - **TraceLogger**: NDJSON 이벤트 로거 (type + payload).
   11 | 
   12 | ---
   13 | 
   14 | ## 1) 한눈에 보는 요청 플로우
   15 | 
   16 | ```mermaid
   17 | sequenceDiagram
   18 |     participant C as Client
   19 |     participant F as TraceFilter
   20 |     participant O as OrchestrationSignals
   21 |     participant QT as QueryTransformer
   22 |     participant KS as KeywordSelectionService
   23 |     participant D as QueryDisambiguationService
   24 |     participant R as RAG / Vector
   25 |     participant E as Embedding (DecoratingEmbeddingModel)
   26 |     participant A as AblationBridge/Finalize
   27 |     participant CP as DebugCopilot
   28 |     participant UI as TraceHtmlBuilder
   29 | 
   30 |     C->>F: HTTP Request
   31 |     Note over F: MDC(sid/trace/traceId/x-request-id) set
   32 |     Note over F: TraceStore: trace.id, sid, dbg.search.*
   33 | 
   34 |     F->>O: scoring / strike / bypass / compression
   35 |     Note over O: TraceStore: orch.* (score,factors,shap,noiseEscape)
   36 | 
   37 |     O->>QT: transform query / enforce COMPRESSION
   38 |     Note over QT: TraceStore: qtx.* (bypass,noiseEscape)
   39 |     Note over QT: AuxBlockTracker: aux.noiseOverride.*
   40 | 
   41 |     QT->>KS: keyword selection
   42 |     Note over KS: TraceStore: keywordSelection.* (mode,reason,noiseEscape)
   43 | 
   44 |     KS->>D: disambiguation (optional)
   45 |     Note over D: TraceStore: disambiguation.* (noiseEscape)
   46 | 
   47 |     D->>R: vector/web/aux retrieval
   48 |     Note over R: TraceStore: vector.* (poison/quality drop), web.*
   49 |     Note over R: TraceLogger: vector_* events (shadow/quarantine/merge)
   50 | 
   51 |     R->>E: embed/cache
   52 |     Note over E: TraceStore: embed.* (cache, failover, invalidate)
   53 | 
   54 |     E->>A: ablation bridge/finalize
   55 |     Note over A: TraceStore: ablation.* , uaw.ablation.*
   56 | 
   57 |     A->>CP: summarize causes + commands
   58 |     Note over CP: TraceStore: dbg.copilot.*
   59 | 
   60 |     CP->>UI: render trace html
   61 |     Note over UI: pills + panels (Orch/Ablation/Copilot)
   62 | ```
   63 | 
   64 | ---
   65 | 
   66 | ## 2) 단계별 TraceStore 키 (핵심만)
   67 | 
   68 | ### A. TraceFilter (요청 진입)
   69 | - `trace.id` / `sid`
   70 | - `dbg.search.enabled`
   71 | - `dbg.search.source` (request/boost)
   72 | - `dbg.search.boost.*` (active/reason/remainingMs)
   73 | - `uaw.ablation.bridge` (dbgSearch에서도 ablation bridge 활성화)
   74 | 
   75 | ### B. OrchestrationSignals (스코어링/게이트)
   76 | - `orch.mode` / `orch.reason`
   77 | - `orch.strike` / `orch.bypass` / `orch.compression`
   78 | - `orch.debug.score.*` (raw, clamped, factors)
   79 | - `orch.debug.ablation.strike|bypass` (확률/이벤트)
   80 | - `orch.debug.shap.exact.*` (Exact Shapley: strike/bypass)
   81 | - `orch.noiseEscape.bypassSilentFailure.*` + `orch.noiseEscape.used`
   82 | 
   83 | ### C. QueryTransformer (COMPRESSION / bypass)
   84 | - `qtx.stagePolicy.*`
   85 | - `qtx.bypass.*` (trigger/reason/modeLabel)
   86 | - `qtx.noiseEscape.*`
   87 | - 호환 alias: `qtx.noise.escape.used|escapeP|roll`
   88 | 
   89 | ### D. KeywordSelectionService / SmartQueryPlanner
   90 | - `keywordSelection.mode` / `keywordSelection.reason`
   91 | - `keywordSelection.noiseEscape.*`
   92 | - `keywordSelection.bypass.noiseEscape` (planner에서 허용된 경우)
   93 | 
   94 | ### E. QueryDisambiguationService
   95 | - `disambiguation.noiseEscape.*`
   96 | 
   97 | ### F. Embedding (cache/failover)
   98 | - `embed.cache.hit|miss|calls`
   99 | - `embed.key.cur` / `embed.kind.cur` / `embed.vec.len.cur`
  100 | - `embed.failover.used.cur` / `embed.failover.stage.cur`
  101 | - `embed.cache.invalidate.failover` (fallback 사용 감지 시)
  102 | 
  103 | ### G. Vector / Guards
  104 | - `vector.poison.dropped`
  105 | - `vector.quality.dropped`
  106 | - `kb.domain.quality.dropped`
  107 | 
  108 | ### H. Ablation finalize
  109 | - `ablation.score`
  110 | - `ablation.events.count`
  111 | - `ablation.probabilities` / `ablation.top`
  112 | - `ablation.byGuard` / `ablation.byStep`
  113 | 
  114 | ### I. DebugCopilot
  115 | - `dbg.copilot.ok`
  116 | - `dbg.copilot.summary`
  117 | - `dbg.copilot.causes` (ranked 1~3)
  118 | - `dbg.copilot.actions` (copy/paste 명령어 포함)
  119 | 
  120 | ---
  121 | 
  122 | ## 3) TraceLogger 이벤트(참고)
  123 | 
```

## E02 — 디버깅 보존 규칙과 단순 빈 존재 검사

기능 보존 의도는 유용하지만 빈 존재만으로 표시·복원이 입증되지는 않는다.

### L: `src/AGENT/50_DEBUG_PRESERVE.md:1–49`

```text
    1 | # 50_DEBUG_PRESERVE.md — 디버깅·진단·트레이스 보존 정책(Agent‑Safe)
    2 | 
    3 | 본 문서는 에이전트 모드가 **디버깅/진단/트레이스 코드를 '소음 감소' 명목으로 삭제·축소**하지 못하도록 하는 보존 규칙과 가드 예시를 제공합니다.  
    4 | 원칙: **게이팅(gating=branch‑selection)으로 끄고 켜되, 코드는 지우지 않는다.**
    5 | 
    6 | ## 1) 불가침(Do‑Not‑Remove)
    7 | 
    8 | - 패키지/클래스(삭제·인라인·정적 제거 금지)
    9 | - `com.example.lms.debug` : PromptDebugLogger, PromptMasker
   10 | - `com.example.lms.diagnostics` : RetrievalDiagAspect
   11 | - `com.example.lms.service.diagnostic` : DiagnosticsDumpService
   12 | - `com.example.lms.service.trace` : TraceHtmlBuilder
   13 | - `com.example.lms.telemetry` : ConfigKeysLogger, DefaultSseEventPublisher, LoggingSseEventPublisher, SseEventPublisher, TelemetryService
   14 | - `com.example.lms.trace` : EbnaDetector, LlmTraceAspect, PromptTraceAspect, SafeRedactor, SearchTraceAspect, TraceContext, TraceEvent, TraceLogger
   15 | - 설정 파일
   16 |   - `src/main/resources/logback-spring.xml` 내 `TRACE_FILE` appender 및 `TRACE_JSON` 로거
   17 | 
   18 | - 로그 호출
   19 |   - `log.debug(..)`, `log.trace(..)` 삭제 금지. 레벨/프로퍼티로 제어.
   20 | 
   21 | ## 2) 게이팅 정책(끄고 켜는 법, 지우지 않기)
   22 | 
   23 | - 프로퍼티
   24 |   - `lms.trace.enabled` (기본 true)
   25 |   - `lms.trace.http.enabled` (기본 false; 켜면 WebClient 에러 응답 비소모형 로깅 활성)
   26 |   - `lms.trace.sample` (0.0–1.0 샘플링 비율)
   27 |   - `lms.debug.enabled` (기본 false)
   28 |   - `lms.debug.dump-prompts`, `lms.debug.mask-secrets`, `lms.debug.max-bytes`
   29 | - 프로파일: 필요 시 `@Profile("diag")` 등으로 추가 게이팅 허용
   30 | - 로깅 레벨: `logging.level.com.example.lms=INFO`(기본). 문제 재현 시 일시적으로 `DEBUG/TRACE`.
   31 | 
   32 | ## 3) AGENT_KEEP 마커
   33 | 
   34 | - 파일/클래스/메서드 상단 주석: `// AGENT_KEEP: debug`
   35 | - 또는 어노테이션: `@com.example.lms.annotations.AgentKeep`
   36 | 
   37 | ## 4) CI/훅 가드(권장)
   38 | 
   39 | - GitHub Actions: 보존 패키지 내 클래스 수>0, `logback-spring.xml` 존재 확인
   40 | - pre‑commit: 보존 경로의 삭제/이동(D/R) 차단
   41 | 
   42 | ## 5) 스모크 테스트(권장)
   43 | 
   44 | - `DebugPreserveSmokeTest`가 다음 빈 존재를 확인:
   45 |   - `com.example.lms.web.TraceFilter`
   46 |   - `com.example.lms.trace.RequestIdHeaderFilter`
   47 |   - `com.example.lms.debug.PromptDebugLogger`
   48 | 
   49 | > 위 정책은 코드를 **기능 플래그로 게이팅**하여 운용 소음을 낮추면서도, 추후 문제 분석을 위한 자산을 항상 보존하는 것을 목표로 합니다.
```

### L: `src/test/java/com/example/lms/DebugPreserveSmokeTest.java:1–31`

```text
    1 | // src/test/java/com/example/lms/DebugPreserveSmokeTest.java
    2 | package com.example.lms;
    3 | 
    4 | import com.example.lms.debug.PromptDebugLogger;
    5 | import com.example.lms.trace.WebClientDiagnostics;
    6 | import com.example.lms.trace.RequestIdHeaderFilter;
    7 | import com.example.lms.web.TraceFilter;
    8 | import org.junit.jupiter.api.Test;
    9 | import org.springframework.beans.factory.annotation.Autowired;
   10 | import org.springframework.boot.test.context.SpringBootTest;
   11 | import org.springframework.context.ApplicationContext;
   12 | 
   13 | 
   14 | 
   15 | import static org.junit.jupiter.api.Assertions.assertNotNull;
   16 | 
   17 | @SpringBootTest(properties = {
   18 |         "lms.trace.enabled=true",
   19 |         "lms.trace.http.enabled=false",
   20 |         "lms.debug.enabled=true",
   21 |         "lms.debug.dump-prompts=false"
   22 | })
   23 | class DebugPreserveSmokeTest {
   24 |     @Autowired ApplicationContext ctx;
   25 | 
   26 |     @Test void coreBeansPresent() {
   27 |         assertNotNull(ctx.getBean(TraceFilter.class));
   28 |         assertNotNull(ctx.getBean(RequestIdHeaderFilter.class));
   29 |         assertNotNull(ctx.getBean(PromptDebugLogger.class));
   30 |     }
   31 | }
```

## E03 — 현재 답변별 UI의 보존 대상

현재본에는 안전한 DOM, 정렬·필터, dedup, 메타데이터-only root 처리가 존재한다.

### C: `main/resources/static/js/chat-trace-ui.js:19–27`

```text
   19 |   function enabled() {
   20 |     return Boolean(document.querySelector("[data-admin-diagnostics]") &&
   21 |       document.querySelector("[data-chat-trace-toggle]")?.checked);
   22 |   }
   23 | 
   24 |   function withDebugQuery(path) {
   25 |     const url = String(path || "");
   26 |     if (!enabled() || /(?:[?&])debug=/.test(url)) return url;
   27 |     return url + (url.includes("?") ? "&" : "?") + "debug=true";
```

### C: `main/resources/static/js/chat-trace-ui.js:44–79`

```text
   44 |   function ensurePanel(assistant) {
   45 |     if (!assistant || !assistant.parentElement) return null;
   46 |     const existing = byAssistant.get(assistant);
   47 |     if (existing && existing.panel.isConnected) return existing;
   48 |     const panel = document.createElement("details");
   49 |     panel.className = "message-trace awx-trace-panel";
   50 |     panel.dataset.role = "trace";
   51 |     panel.dataset.liveRegion = "excluded";
   52 |     panel.setAttribute("aria-live", "off");
   53 |     const summary = document.createElement("summary");
   54 |     summary.textContent = "이 답변 추적";
   55 |     const metadata = document.createElement("div");
   56 |     metadata.className = "awx-trace-meta";
   57 |     const signals = document.createElement("div");
   58 |     signals.className = "awx-trace-signals";
   59 |     metadata.appendChild(signals);
   60 |     const body = document.createElement("div");
   61 |     body.className = "awx-trace-body";
   62 |     panel.append(summary, metadata, body);
   63 |     // A details element directly in the transcript grid can grow beyond its
   64 |     // allocated row after opening. The block slot gives that row its real height.
   65 |     const slot = document.createElement("div");
   66 |     slot.className = "awx-trace-slot";
   67 |     slot.appendChild(panel);
   68 |     if (typeof assistant.after === "function") assistant.after(slot);
   69 |     else assistant.parentElement.insertBefore(slot, assistant.nextSibling);
   70 |     const state = { assistant, slot, panel, metadata, signals, body, snapshotId: null,
   71 |       diagnosticNodes: new Map(), scoreKeys: [],
   72 |       controller: null, loading: false, loaded: false, version: 0, live: false };
   73 |     byAssistant.set(assistant, state);
   74 |     byPanel.set(panel, state);
   75 |     panel.addEventListener("toggle", () => {
   76 |       if (panel.open && state.snapshotId) loadSnapshot(state);
   77 |     });
   78 |     return state;
   79 |   }
```

### C: `main/resources/static/js/chat-trace-ui.js:105–145`

```text
  105 |     // A stored snapshot can be a whole HTML document. Only its sanitized trace
  106 |     // panel crosses into the live page; head/style/script never does.
  107 |     const details = fragment.querySelector("details.search-trace");
  108 |     if (details) return details;
  109 |     // Older metadata-only snapshots have a section root. Accept only the two
  110 |     // producer shapes, then wrap the sanitized section as a summary, not search detail.
  111 |     const markers = Array.from(source.matchAll(/<section data-trace="(trace-memory|chat-harmony)" data-kind="metadata-only">/g));
  112 |     if (markers.length !== 1) return null;
  113 |     const metadata = markers[0];
  114 |     const bare = source.trim().startsWith(metadata[0]) && source.trim().endsWith("</section>");
  115 |     const wrapped = source.startsWith('<!doctype html><html><head><meta charset="utf-8"/>') &&
  116 |       source.includes("<title>Trace Snapshot ") &&
  117 |       source.includes("</head><body><h2>Trace Snapshot</h2><table>") &&
  118 |       source.endsWith("</body></html>");
  119 |     if (!bare && !wrapped) return null;
  120 |     const sections = fragment.querySelectorAll("section");
  121 |     if (sections.length !== 1) return null;
  122 |     const section = sections[0];
  123 |     const heading = section.querySelectorAll("h3");
  124 |     const lists = section.querySelectorAll("dl");
  125 |     const expectedHeading = metadata[1] === "trace-memory"
  126 |       ? "Trace Memory Checkpoint" : "Chat Harmony Trace";
  127 |     const maxLists = metadata[1] === "trace-memory" ? 1 : 3;
  128 |     if (heading.length !== 1 || heading[0].textContent.trim() !== expectedHeading ||
  129 |         lists.length < 1 || lists.length > maxLists ||
  130 |         lists[0].querySelectorAll("dt").length === 0) return null;
  131 |     const summaryOnly = document.createElement("details");
  132 |     summaryOnly.className = "search-trace trace-metadata-only";
  133 |     const summary = document.createElement("summary");
  134 |     summary.textContent = "진단 요약만 저장됨";
  135 |     summaryOnly.append(summary, section);
  136 |     return summaryOnly;
  137 |   }
  138 | 
  139 |   function wireTraceSteps(safe) {
  140 |     for (const panel of safe.querySelectorAll("details.trace-steps-panel")) {
  141 |       const table = panel.querySelector("table.trace-steps-table");
  142 |       const tbody = table?.querySelector("tbody");
  143 |       const controls = panel.querySelector(".trace-steps-controls");
  144 |       if (!tbody || !controls) continue;
  145 |       const rows = Array.from(tbody.querySelectorAll("tr"));
```

### C: `main/resources/static/js/chat-trace-ui.js:361–411`

```text
  361 |   function upsertDiagnostic(assistant, kind, detail, eventKey) {
  362 |     if (!enabled() || !detail || (kind !== "trace" && kind !== "score")) return null;
  363 |     const state = ensurePanel(assistant);
  364 |     if (!state) return null;
  365 |     const key = kind === "trace" ? "trace" : "score:" + String(eventKey || "").slice(0, 512);
  366 |     const previous = state.diagnosticNodes.get(key);
  367 |     if (previous) previous.remove();
  368 |     if (kind === "score" && !previous) {
  369 |       state.scoreKeys.push(key);
  370 |       if (state.scoreKeys.length > MAX_SCORE_EVENTS) {
  371 |         const oldest = state.scoreKeys.shift();
  372 |         state.diagnosticNodes.get(oldest)?.remove();
  373 |         state.diagnosticNodes.delete(oldest);
  374 |       }
  375 |     }
  376 |     state.signals.appendChild(detail);
  377 |     state.diagnosticNodes.set(key, detail);
  378 |     state.live = true;
  379 |     state.panel.hidden = false;
  380 |     return detail;
  381 |   }
  382 | 
  383 |   function restore(assistant, turnTrace) {
  384 |     const snapshotId = typeof turnTrace?.snapshotId === "string" ? turnTrace.snapshotId : "";
  385 |     if (!SNAPSHOT_ID.test(snapshotId)) return null;
  386 |     const state = ensurePanel(assistant);
  387 |     if (!state) return null;
  388 |     state.panel.hidden = !enabled();
  389 |     if (state.snapshotId !== snapshotId) {
  390 |       abortSnapshot(state);
  391 |       state.snapshotId = snapshotId;
  392 |       state.loaded = false;
  393 |     }
  394 |     state.panel.dataset.traceSnapshotId = snapshotId;
  395 |     state.metadata.replaceChildren();
  396 |     const fields = turnTrace.fields && typeof turnTrace.fields === "object" ? turnTrace.fields : {};
  397 |     const list = document.createElement("dl");
  398 |     for (const key of Object.keys(fields).slice(0, 16)) {
  399 |       const value = fields[key];
  400 |       if (!FIELD_KEY.test(key) || typeof value !== "string" || !FIELD_VALUE.test(value)) continue;
  401 |       const term = document.createElement("dt");
  402 |       term.textContent = key;
  403 |       const description = document.createElement("dd");
  404 |       description.textContent = value;
  405 |       list.append(term, description);
  406 |     }
  407 |     state.metadata.replaceChildren(list, state.signals);
  408 |     if (!state.live && !state.loaded) status(state, "저장된 요약입니다. 상세는 펼칠 때 조회합니다.");
  409 |     if (state.panel.open) loadSnapshot(state);
  410 |     return state.panel;
  411 |   }
```

## E04 — 포인터 v2 및 assistant ID 바인딩

현재는 assistantMessageId를 명시해 복원하며 충돌 소유권을 숨기는 로직도 있다.

### C: `main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java:16–72`

```text
   16 |     private static final String TRACE_SNAPSHOT_META_PREFIX = "?TRACESNAP?";
   17 |     private static final String DURABLE_ENVELOPE_VERSION = "v2";
   18 |     private static final int MAX_DURABLE_PROJECTION_BYTES = 2_048;
   19 | 
   20 |     private ChatTraceSnapshotPointerPersister() {
   21 |     }
   22 | 
   23 |     static Long persist(
   24 |             Long sessionId,
   25 |             Long assistantMessageId,
   26 |             String reason,
   27 |             String method,
   28 |             String path,
   29 |             Map<String, Object> traceMeta,
   30 |             String traceHtml,
   31 |             TraceSnapshotStore traceSnapshotStore,
   32 |             ChatHistoryService historyService,
   33 |             Logger log) {
   34 |         if (sessionId == null || assistantMessageId == null || assistantMessageId <= 0L
   35 |                 || traceSnapshotStore == null) {
   36 |             return null;
   37 |         }
   38 |         boolean missingTraceHtml = traceHtml == null || traceHtml.isBlank();
   39 |         boolean metadataOnlyTraceMemory = isTraceMemorySnapshot(traceMeta) && missingTraceHtml;
   40 |         boolean metadataOnlyHarmony = isAgentVisibleHarmony(traceMeta) && missingTraceHtml;
   41 |         String snapshotHtml = metadataOnlyTraceMemory
   42 |                 ? metadataOnlyTraceMemoryTraceHtml(traceMeta)
   43 |                 : (metadataOnlyHarmony ? metadataOnlyHarmonyTraceHtml(traceMeta) : traceHtml);
   44 |         if (snapshotHtml == null || snapshotHtml.isBlank()) {
   45 |             return null;
   46 |         }
   47 |         try {
   48 |             Map<String, Object> snapMeta = new LinkedHashMap<>(traceMeta == null ? Map.of() : traceMeta);
   49 |             snapMeta.putIfAbsent("ui.traceHtml.kind", metadataOnlyTraceMemory ? "traceMemoryMetadataOnly"
   50 |                     : (metadataOnlyHarmony ? "chatHarmonyMetadataOnly" : "splitPanel"));
   51 |             snapMeta.putIfAbsent("ui.traceHtml.length", snapshotHtml.length());
   52 |             if (metadataOnlyTraceMemory || metadataOnlyHarmony) {
   53 |                 snapMeta.putIfAbsent("ui.traceHtml.synthetic", true);
   54 |             }
   55 |             String snapshotId = traceSnapshotStore.captureCustom(reason, method, path, null, null, snapMeta, snapshotHtml);
   56 |             if (!ChatTraceMetaMessageRestorer.isSafeTraceSnapshotId(snapshotId)) {
   57 |                 return null;
   58 |             }
   59 |             return historyService.appendMessageReturningId(
   60 |                     sessionId,
   61 |                     "system",
   62 |                     durablePointer(snapshotId, assistantMessageId, reason, method, path, snapMeta));
   63 |         } catch (Exception e) {
   64 |             String safeErrorType = errorType(e);
   65 |             TraceStore.put("chat.traceSnapshotPointer.suppressed.stage", "persist");
   66 |             TraceStore.put("chat.traceSnapshotPointer.suppressed.errorType", safeErrorType);
   67 |             TraceStore.put("chat.traceSnapshotPointer.suppressed.persist", true);
   68 |             TraceStore.put("chat.traceSnapshotPointer.suppressed.persist.errorType", safeErrorType);
   69 |             log.debug("[AWX][trace] snapshot pointer skipped reason={} errorType={}",
   70 |                     SafeRedactor.traceLabelOrFallback(reason, "unknown"), safeErrorType);
   71 |             return null;
   72 |         }
```

### C: `main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java:137–198`

```text
  137 |     private static Map<String, String> parseDurableProjection(String pointer, int firstDelimiter, Long turnId) {
  138 |         int secondDelimiter = pointer.indexOf('|', firstDelimiter + 1);
  139 |         String version = secondDelimiter < 0 ? "" : pointer.substring(firstDelimiter + 1, secondDelimiter);
  140 |         if (secondDelimiter < 0
  141 |                 || pointer.indexOf('|', secondDelimiter + 1) >= 0
  142 |                 || !(DURABLE_ENVELOPE_VERSION_V1.equals(version)
  143 |                 || DURABLE_ENVELOPE_VERSION_V2.equals(version))) {
  144 |             traceSnapshotEnvelopeSkipped(turnId, "invalid_envelope");
  145 |             return Map.of();
  146 |         }
  147 |         String encoded = pointer.substring(secondDelimiter + 1);
  148 |         if (encoded.isBlank() || encoded.length() > MAX_DURABLE_PROJECTION_B64_CHARS) {
  149 |             traceSnapshotEnvelopeSkipped(turnId, "invalid_size");
  150 |             return Map.of();
  151 |         }
  152 |         try {
  153 |             byte[] decoded = Base64.getUrlDecoder().decode(encoded);
  154 |             if (decoded.length == 0 || decoded.length > MAX_DURABLE_PROJECTION_BYTES) {
  155 |                 traceSnapshotEnvelopeSkipped(turnId, "invalid_size");
  156 |                 return Map.of();
  157 |             }
  158 |             String text = new String(decoded, StandardCharsets.UTF_8);
  159 |             if (text.indexOf('\r') >= 0 || text.indexOf('\uFFFD') >= 0) {
  160 |                 traceSnapshotEnvelopeSkipped(turnId, "invalid_encoding");
  161 |                 return Map.of();
  162 |             }
  163 |             Map<String, String> projection = new LinkedHashMap<>();
  164 |             for (String line : text.split("\\n", -1)) {
  165 |                 if (line.isEmpty()) {
  166 |                     continue;
  167 |                 }
  168 |                 if (projection.size() >= MAX_DURABLE_PROJECTION_FIELDS) {
  169 |                     traceSnapshotEnvelopeSkipped(turnId, "too_many_fields");
  170 |                     return Map.of();
  171 |                 }
  172 |                 int equals = line.indexOf('=');
  173 |                 if (equals <= 0 || line.indexOf('=', equals + 1) >= 0) {
  174 |                     traceSnapshotEnvelopeSkipped(turnId, "invalid_field");
  175 |                     return Map.of();
  176 |                 }
  177 |                 String key = line.substring(0, equals);
  178 |                 String value = line.substring(equals + 1);
  179 |                 if (projection.containsKey(key) || !isValidDurableField(key, value, version)) {
  180 |                     traceSnapshotEnvelopeSkipped(turnId, "invalid_field");
  181 |                     return Map.of();
  182 |                 }
  183 |                 projection.put(key, value);
  184 |             }
  185 |             if (!"durable_fallback".equals(projection.get("storageMode"))
  186 |                     || !projection.containsKey("reason")
  187 |                     || !projection.containsKey("method")
  188 |                     || !projection.containsKey("pathHash")
  189 |                     || (DURABLE_ENVELOPE_VERSION_V2.equals(version)
  190 |                     && !projection.containsKey("assistantMessageId"))) {
  191 |                 traceSnapshotEnvelopeSkipped(turnId, "missing_required_field");
  192 |                 return Map.of();
  193 |             }
  194 |             return Map.copyOf(projection);
  195 |         } catch (IllegalArgumentException e) {
  196 |             traceSnapshotEnvelopeSkipped(turnId, "invalid_base64");
  197 |             return Map.of();
  198 |         }
```

### C: `main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java:46–106`

```text
   46 |                 .sorted(Comparator.comparing(ChatMessage::getCreatedAt,
   47 |                         Comparator.nullsLast(Comparator.naturalOrder()))
   48 |                         .thenComparing(ChatMessage::getId,
   49 |                                 Comparator.nullsLast(Comparator.naturalOrder())))
   50 |                 .toList();
   51 | 
   52 |         List<ChatApiController.MessageDto> messages = new ArrayList<>();
   53 |         Map<Long, ChatApiController.TurnTraceDto> tracesByAssistant = new LinkedHashMap<>();
   54 |         Set<Long> ambiguousTraceOwners = new HashSet<>();
   55 |         Set<Long> assistantMessageIds = new HashSet<>();
   56 |         for (var message : raw) {
   57 |             if ("assistant".equals(message.getRole()) && message.getId() != null && message.getId() > 0L) {
   58 |                 assistantMessageIds.add(message.getId());
   59 |             }
   60 |         }
   61 |         String lastModelMeta = null;
   62 |         Long legacyAssistantCandidate = null;
   63 |         boolean legacyAssistantAmbiguous = false;
   64 |         for (var m : raw) {
   65 |             String role = m.getRole();
   66 |             String content = m.getContent();
   67 | 
   68 |             if ("system".equals(role)) {
   69 |                 if (content != null) {
   70 |                     String modelMeta = ChatModelMetaSupport.extractModelUsed(content);
   71 |                     if (modelMeta != null) {
   72 |                         lastModelMeta = modelMeta;
   73 |                         continue;
   74 |                     }
   75 |                     if (exposeTrace) {
   76 |                         var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(content, m.getId());
   77 |                         if (pointer.isPresent()) {
   78 |                             Long assistantId = pointer.get().assistantMessageId();
   79 |                             if (assistantId == null && pointer.get().legacyFallbackAllowed()
   80 |                                     && !legacyAssistantAmbiguous) {
   81 |                                 assistantId = legacyAssistantCandidate;
   82 |                             }
   83 |                             if (assistantId != null && assistantMessageIds.contains(assistantId)
   84 |                                     && !ambiguousTraceOwners.contains(assistantId)) {
   85 |                                 ChatApiController.TurnTraceDto existing = tracesByAssistant.get(assistantId);
   86 |                                 if (existing != null && !existing.snapshotId().equals(pointer.get().snapshotId())) {
   87 |                                     tracesByAssistant.remove(assistantId);
   88 |                                     ambiguousTraceOwners.add(assistantId);
   89 |                                 } else if (existing == null) {
   90 |                                     tracesByAssistant.put(assistantId, new ChatApiController.TurnTraceDto(
   91 |                                             assistantId,
   92 |                                             pointer.get().snapshotId(),
   93 |                                             mergeModelMetaField(pointer.get().projection(), lastModelMeta)));
   94 |                                 }
   95 |                             }
   96 |                             lastModelMeta = null;
   97 |                         }
   98 |                     }
   99 |                     Optional<ChatApiController.MessageDto> traceMeta =
  100 |                             ChatTraceMetaMessageRestorer.restore(m.getId(), content, m.getCreatedAt(), exposeTrace);
  101 |                     if (traceMeta.isPresent()) {
  102 |                         messages.add(traceMeta.get());
  103 |                         continue;
  104 |                     }
  105 |                     if (content.startsWith(TRACE_META_PREFIX) || content.startsWith(TRACE_META_PREFIX_B64)) {
  106 |                         continue;
```

## E05 — 타입 손실 규칙과 실제 producer

HTML의 generic sanitize와 SnapshotStore의 standard event 전용 경로가 공존한다. 안전한 수치가 사라지는 사실과 비밀 마스킹의 필요성을 구분한다.

### C: `main/java/com/example/lms/trace/SafeRedactor.java:176–211`

```text
  176 |     private static Object diagnosticValue(String key, Object value, int maxStringLen, int depth) {
  177 |         if (value == null) return null;
  178 |         if (depth > MAX_DEPTH) return "(depth-limit)";
  179 | 
  180 |         String normalizedKey = normalizeKey(key);
  181 |         if (value instanceof String s) {
  182 |             return diagnosticString(normalizedKey, s, maxStringLen);
  183 |         }
  184 |         if (value instanceof Number || value instanceof Boolean) {
  185 |             if (isSecretKey(normalizedKey) && !isQueryTransformerSuperTokenDiagnosticKey(normalizedKey)) {
  186 |                 return "(redacted)";
  187 |             }
  188 |             if (isIdentifierKey(normalizedKey)
  189 |                     || (isRawContentKey(normalizedKey)
  190 |                     && !isQueryRewriteDiagnosticKey(normalizedKey)
  191 |                     && !isQueryTransformerSuperTokenDiagnosticKey(normalizedKey))) {
  192 |                 return scalarSummary(String.valueOf(value), false);
  193 |             }
  194 |             return value;
  195 |         }
  196 |         if (value instanceof Enum<?> e) {
  197 |             return e.name();
  198 |         }
  199 |         if (value instanceof Map<?, ?> m) {
  200 |             Map<String, Object> out = new LinkedHashMap<>();
  201 |             int i = 0;
  202 |             for (Map.Entry<?, ?> e : m.entrySet()) {
  203 |                 if (i++ >= MAX_ITEMS) {
  204 |                     out.put("_truncated", true);
  205 |                     break;
  206 |                 }
  207 |                 if (e == null || e.getKey() == null) continue;
  208 |                 String childKey = String.valueOf(e.getKey());
  209 |                 out.put(traceLabelOrFallback(childKey, "field"),
  210 |                         diagnosticValue(joinKey(key, childKey), e.getValue(), maxStringLen, depth + 1));
  211 |             }
```

### C: `main/java/com/example/lms/trace/SafeRedactor.java:238–277`

```text
  238 |     private static Object diagnosticString(String normalizedKey, String raw, int maxStringLen) {
  239 |         if (raw == null) return null;
  240 |         if (raw.matches("hash:[0-9a-f]{12}")) {
  241 |             return raw;
  242 |         }
  243 |         if (isEvidenceObservedAtKey(normalizedKey) && isIsoInstant(raw)) {
  244 |             return raw;
  245 |         }
  246 |         if (isSecretKey(normalizedKey) && !isQueryTransformerSuperTokenDiagnosticKey(normalizedKey)) {
  247 |             return "(redacted)";
  248 |         }
  249 |         if (isIdentifierKey(normalizedKey)) {
  250 |             return hashValue(raw);
  251 |         }
  252 |         if (isUrlKey(normalizedKey)) {
  253 |             return urlSummary(raw);
  254 |         }
  255 |         if (isQueryRewriteDiagnosticKey(normalizedKey)) {
  256 |             return limit(PromptMasker.mask(raw).replace('\n', ' ').replace('\r', ' ').trim(), maxStringLen);
  257 |         }
  258 |         if (isQueryTransformerSuperTokenDiagnosticKey(normalizedKey)) {
  259 |             return limit(PromptMasker.mask(raw).replace('\n', ' ').replace('\r', ' ').trim(), maxStringLen);
  260 |         }
  261 |         if (isRawContentKey(normalizedKey)) {
  262 |             return scalarSummary(raw, true);
  263 |         }
  264 |         if (isHarmonyAuthorityKey(normalizedKey)) {
  265 |             return sanitizeHarmonyAuthorityValue(normalizedKey, raw);
  266 |         }
  267 |         if (isReasonKey(normalizedKey)) {
  268 |             return traceLabelOrFallback(raw, "unknown");
  269 |         }
  270 |         if (isSafeScalarKey(normalizedKey)) {
  271 |             return limit(PromptMasker.mask(raw).replace('\n', ' ').replace('\r', ' ').trim(), maxStringLen);
  272 |         }
  273 |         String masked = PromptMasker.mask(raw);
  274 |         if (!masked.equals(raw)) {
  275 |             return limit(masked.replace('\n', ' ').replace('\r', ' ').trim(), maxStringLen);
  276 |         }
  277 |         return scalarSummary(raw, true);
```

### C: `main/java/com/example/lms/trace/SafeRedactor.java:345–401`

```text
  345 |     private static boolean isSecretKey(String k) {
  346 |         if (k.equals("tokenbucket")
  347 |                 || k.endsWith(".tokenbucket")
  348 |                 || k.equals("querytokenbucket")
  349 |                 || k.endsWith(".querytokenbucket")) {
  350 |             return false;
  351 |         }
  352 |         return k.contains("authorization")
  353 |                 || k.contains("apikey")
  354 |                 || k.contains("api.key")
  355 |                 || (k.contains("servicerole") && k.contains("key"))
  356 |                 || k.contains("secret")
  357 |                 || k.contains("token")
  358 |                 || k.contains("password")
  359 |                 || k.contains("cookie")
  360 |                 || k.contains("ownertoken")
  361 |                 || (k.contains("openai") && k.contains("key"));
  362 |     }
  363 | 
  364 |     private static boolean isIdentifierKey(String k) {
  365 |         return k.equals("sid")
  366 |                 || k.endsWith(".sid")
  367 |                 || k.equals("sessionid")
  368 |                 || k.endsWith(".sessionid")
  369 |                 || k.equals("datasetpath")
  370 |                 || k.endsWith(".datasetpath")
  371 |                 || k.equals("traceid")
  372 |                 || k.endsWith(".traceid")
  373 |                 || k.equals("trace")
  374 |                 || k.endsWith(".trace")
  375 |                 || k.equals("trace.id")
  376 |                 || k.endsWith(".trace.id")
  377 |                 || k.equals("requestid")
  378 |                 || k.endsWith(".requestid")
  379 |                 || k.equals("xrequestid")
  380 |                 || k.endsWith(".xrequestid")
  381 |                 || k.equals("rid")
  382 |                 || k.endsWith(".rid");
  383 |     }
  384 | 
  385 |     private static boolean isRawContentKey(String k) {
  386 |         return isPathKey(k)
  387 |                 || k.contains("rawquery")
  388 |                 || k.contains("effectivequery")
  389 |                 || k.endsWith("query")
  390 |                 || k.contains(".query")
  391 |                 || k.contains("snippet")
  392 |                 || k.contains("prompt")
  393 |                 || k.contains("html")
  394 |                 || k.contains("httpquery")
  395 |                 || k.contains("httpua")
  396 |                 || k.contains("useragent")
  397 |                 || k.contains("textpreview")
  398 |                 || k.contains("usermessage")
  399 |                 || k.contains("rawtext")
  400 |                 || k.contains("origtext")
  401 |                 || k.contains("payload");
```

### C: `main/java/com/example/lms/service/trace/TraceHtmlBuilder.java:1122–1166`

```text
 1122 |     private static Map<String, Object> sanitizeMeta(Map<String, Object> meta) {
 1123 |         if (meta == null || meta.isEmpty()) {
 1124 |             return java.util.Map.of();
 1125 |         }
 1126 |         Map<String, Object> out = new java.util.LinkedHashMap<>();
 1127 |         int count = 0;
 1128 |         for (Map.Entry<String, Object> e : meta.entrySet()) {
 1129 |             if (e == null || e.getKey() == null) continue;
 1130 |             String key = e.getKey();
 1131 |             String displayKey = safeMetaKey(key);
 1132 |             if ("rag.evidence.public".equals(key)) {
 1133 |                 out.put(displayKey, sanitizePublicEvidence(e.getValue()));
 1134 |             } else {
 1135 |                 out.put(displayKey, SafeRedactor.diagnosticValue(key.toLowerCase(java.util.Locale.ROOT).contains("soakkpijson") ? "query" : key, e.getValue(), 800));
 1136 |             }
 1137 |             if (++count >= 800) {
 1138 |                 out.put("_truncated", true);
 1139 |                 break;
 1140 |             }
 1141 |         }
 1142 |         return out;
 1143 |     }
 1144 | 
 1145 |     private static String safeMetaKey(String key) {
 1146 |         if (key == null || key.isBlank()) {
 1147 |             return "field";
 1148 |         }
 1149 |         String normalized = key.toLowerCase(java.util.Locale.ROOT).replace("_", "").replace("-", "");
 1150 |         boolean tokenBucket = normalized.equals("tokenbucket")
 1151 |                 || normalized.endsWith(".tokenbucket")
 1152 |                 || normalized.equals("querytokenbucket")
 1153 |                 || normalized.endsWith(".querytokenbucket");
 1154 |         boolean sensitive = normalized.contains("authorization")
 1155 |                 || normalized.contains("apikey")
 1156 |                 || (normalized.contains("servicerole") && normalized.contains("key"))
 1157 |                 || normalized.contains("secret")
 1158 |                 || (!tokenBucket && normalized.contains("token"))
 1159 |                 || normalized.contains("password")
 1160 |                 || normalized.contains("cookie")
 1161 |                 || normalized.contains("ownertoken")
 1162 |                 || (normalized.contains("openai") && normalized.contains("key"));
 1163 |         return sensitive ? SafeRedactor.hashValue(key) : SafeRedactor.traceLabelOrFallback(key, "field");
 1164 |     }
 1165 | 
 1166 |     private static Object sanitizePublicEvidence(Object raw) {
```

### C: `main/java/com/example/lms/prompt/StandardPromptBuilder.java:120–133`

```text
  120 |                 sb.append("### RECENT CONVERSATION\n");
  121 |                 if (hasHistory) {
  122 |                     sb.append(truncate(history.strip(), 2_000)).append("\n");
  123 |                 }
  124 |                 if (hasLastAnswer && !containsLine(history, lastAnswer)) {
  125 |                     sb.append("Assistant: ").append(truncate(lastAnswer.strip(), 480)).append("\n");
  126 |                 }
  127 |                 sb.append("\n");
  128 |                 TraceStore.put("prompt.historyRendered", hasHistory);
  129 |                 TraceStore.put("prompt.lastAssistantRendered", hasLastAnswer);
  130 |                 break;
  131 |             } catch (Throwable error) {
  132 |                 traceSkipped("recent_conversation", error);
  133 |             }
```

### C: `main/java/com/example/lms/service/ChatWorkflow.java:6899–6910`

```text
 6899 |         }
 6900 | 
 6901 |         try {
 6902 |             TraceStore.put("llm.call.modelHash", SafeRedactor.hashValue(resolved));
 6903 |             TraceStore.put("llm.call.inputChars", estimateChatMessageChars(msgs));
 6904 |             TraceStore.put("llm.call.approxInputTokens", estimateTokensFromChars(estimateChatMessageChars(msgs)));
 6905 |             TraceStore.put("llm.call.maxAttempts", strictSingleAttempt ? 1 : llmMaxAttempts + 1);
 6906 |             TraceStore.put("llm.call.strictSingleAttempt", strictSingleAttempt);
 6907 |             if (requestedModel != null) {
 6908 |                 TraceStore.put("llm.call.model.requestedHash", SafeRedactor.hashValue(requestedModel));
 6909 |             }
 6910 |             if (routedModel != null) {
```

### C: `main/java/com/example/lms/trace/TraceSnapshotStore.java:538–618`

```text
  538 |     private Map<String, Object> standardEvent(Object raw) {
  539 |         Map<String, Object> src = toMap(raw);
  540 |         if (src.isEmpty()) {
  541 |             return Map.of();
  542 |         }
  543 |         Map<String, Object> out = new LinkedHashMap<>();
  544 |         for (String key : List.of(
  545 |                 "v", "seq", "ts", "traceId", "sessionId", "requestId",
  546 |                 "retrievalExecutionId", "searchExecutionId", "providerAttemptId",
  547 |                 "kind", "phase", "stage", "step", "component", "status")) {
  548 |             out.put(key, eventValue(key, src.get(key)));
  549 |         }
  550 |         out.put("input", nestedEventMap(src.get("input"), "input",
  551 |                 List.of("queryHash", "queryLen", "requestedTopK", "planId", "mode")));
  552 |         out.put("output", nestedEventMap(src.get("output"), "output",
  553 |                 List.of("returnedCount", "afterFilterCount", "selectedCount", "stageMs", "sourceDiversity", "naverRetainedCount")));
  554 |         out.put("failure", nestedEventMap(src.get("failure"), "failure",
  555 |                 List.of("reasonCode", "failureClass", "exceptionType")));
  556 |         out.put("control", nestedEventMap(src.get("control"), "control",
  557 |                 List.of("action", "applied", "reasonCode", "breadcrumbId")));
  558 |         return out;
  559 |     }
  560 | 
  561 |     private Map<String, Object> nestedEventMap(Object raw, String prefix, List<String> allowedKeys) {
  562 |         Map<String, Object> src = toMap(raw);
  563 |         if (src.isEmpty()) {
  564 |             return Map.of();
  565 |         }
  566 |         Map<String, Object> out = new LinkedHashMap<>();
  567 |         for (String key : allowedKeys) {
  568 |             if (src.containsKey(key)) {
  569 |                 out.put(key, eventValue(prefix + "." + key, src.get(key)));
  570 |             }
  571 |         }
  572 |         return out;
  573 |     }
  574 | 
  575 |     @SuppressWarnings("unchecked")
  576 |     private static Map<String, Object> toMap(Object raw) {
  577 |         if (raw instanceof Map<?, ?> m) {
  578 |             Map<String, Object> out = new LinkedHashMap<>();
  579 |             for (Map.Entry<?, ?> e : m.entrySet()) {
  580 |                 if (e == null || e.getKey() == null) {
  581 |                     continue;
  582 |                 }
  583 |                 out.put(String.valueOf(e.getKey()), e.getValue());
  584 |             }
  585 |             return out;
  586 |         }
  587 |         return Map.of();
  588 |     }
  589 | 
  590 |     private Object eventValue(String key, Object value) {
  591 |         if (value == null) {
  592 |             return null;
  593 |         }
  594 |         if (isEventLabelKey(key)) {
  595 |             return SafeRedactor.traceLabelOrFallback(value, "unknown");
  596 |         }
  597 |         return SafeRedactor.diagnosticValue("orch.events.v1." + key, value, maxValueLen);
  598 |     }
  599 | 
  600 |     private static boolean isEventLabelKey(String key) {
  601 |         if (key == null || key.isBlank()) {
  602 |             return false;
  603 |         }
  604 |         String k = key.toLowerCase(Locale.ROOT);
  605 |         return k.equals("kind")
  606 |                 || k.equals("phase")
  607 |                 || k.equals("stage")
  608 |                 || k.equals("step")
  609 |                 || k.equals("component")
  610 |                 || k.equals("status")
  611 |                 || k.endsWith(".mode")
  612 |                 || k.endsWith(".planid")
  613 |                 || k.endsWith(".reasoncode")
  614 |                 || k.endsWith(".failureclass")
  615 |                 || k.endsWith(".exceptiontype")
  616 |                 || k.endsWith(".action")
  617 |                 || k.endsWith(".breadcrumbid");
  618 |     }
```

## E06 — 실제 SafeRedactor JDK 격리 실행

현재 클래스와 PromptMasker를 그대로 컴파일했다. 결과 PASS는 기대했던 현상 재현 성공이며 수정 완료나 전체 테스트 성공이 아니다.

### JDK 실제 실행 결과

```text
prompt.historyRendered	Boolean	LinkedHashMap	{present=true, len=4, hash12=b5bea41b6c62}	idempotent=false
prompt.contextInjected.delivered	Boolean	LinkedHashMap	{present=true, len=4, hash12=b5bea41b6c62}	idempotent=false
prompt.events.webCount	Integer	LinkedHashMap	{present=true, len=1, hash12=4b227777d4dd}	idempotent=false
budget.tokens.remaining	Integer	String	(redacted)	idempotent=true
llm.usage.inputTokens	Integer	String	(redacted)	idempotent=true
llm.call.approxInputTokens	Integer	String	(redacted)	idempotent=true
llm.ollamaNative.maxTokens	Integer	String	(redacted)	idempotent=true
memory.session.tokenEstimate	Integer	String	(redacted)	idempotent=true
llm.gateway.openai.tokenParamRetry	Boolean	String	(redacted)	idempotent=true
orch.mode	String	LinkedHashMap	{present=true, len=6, hash12=a8b34dcd87de}	idempotent=true
orch.events.v1.phase	String	LinkedHashMap	{present=true, len=5, hash12=e37811fce614}	idempotent=true
orch.events.v1.step	String	LinkedHashMap	{present=true, len=6, hash12=7e16e603bbf1}	idempotent=true
orch.events.v1.stage	String	String	rerank	idempotent=true
orch.events.v1.status	String	String	OK	idempotent=true
orch.events.v1.output.selectedCount	Integer	Integer	3	idempotent=true
prompt.events	List12	ArrayList	[{step={present=true, len=8, hash12=63ebfab6ce4e}, webCount={present=true, len=1, hash12=4b227777d4dd}, vectorCount={present=true, len=1, hash12=d4735e3a265e}}]	idempotent=false
authorization	String	String	(redacted)	idempotent=true
apiKey	String	String	(redacted)	idempotent=true
prompt.raw	String	LinkedHashMap	{present=true, len=25, hash12=26ce8fb8ea02}	idempotent=false
traceId	String	String	hash:2616246f5373	idempotent=true
OBSERVATION_ASSERTIONS=PASS; not a project test suite

```

## E07 — 중첩 표 삭제의 실제 브라우저 재현

실제 현재 JS와 동봉 DOMPurify를 네트워크 차단 Chromium에서 실행했다. 합성 3,467자 입력은 길이 제한 미만이었다.

### C: `main/resources/static/js/chat-trace-ui.js:81–104`

```text
   81 |   function sanitizedTrace(html, snapshot) {
   82 |     if (!window.DOMPurify || typeof window.DOMPurify.sanitize !== "function") return null;
   83 |     const source = String(html || "");
   84 |     if (!source) return null;
   85 |     const fragment = window.DOMPurify.sanitize(source.slice(0, MAX_HTML), {
   86 |       RETURN_DOM_FRAGMENT: true,
   87 |       ALLOWED_TAGS: TAGS,
   88 |       ALLOWED_ATTR: ["class", "colspan", "rowspan"],
   89 |       FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "form", "input",
   90 |         "img", "svg", "math", "link", "meta", "audio", "video", "source"],
   91 |       FORBID_ATTR: ["id", "name", "style", "href", "src", "srcset"],
   92 |       ALLOW_DATA_ATTR: false,
   93 |       ALLOW_ARIA_ATTR: false
   94 |     });
   95 |     if (!fragment || fragment.nodeType !== 11) return null;
   96 |     for (const node of fragment.querySelectorAll("[class]")) {
   97 |       const classes = Array.from(node.classList).filter(value =>
   98 |         /^(?:search-trace|trace-[a-z0-9-]+|small|text-muted)$/.test(value));
   99 |       node.className = classes.join(" ");
  100 |     }
  101 |     for (const table of fragment.querySelectorAll("table")) {
  102 |       for (const row of Array.from(table.querySelectorAll("tr")).slice(100)) row.remove();
  103 |     }
  104 |     if (!snapshot) return fragment;
```

### C: `main/resources/static/js/chat-trace-ui.js:328–348`

```text
  328 |   function showHtml(state, html, snapshot) {
  329 |     try {
  330 |       const safe = sanitizedTrace(html, snapshot);
  331 |       if (!safe) {
  332 |         status(state, "트레이스 표시 불가");
  333 |         return false;
  334 |       }
  335 |       wireTraceSteps(safe);
  336 |       wirePipelineEvents(safe);
  337 |       state.body.replaceChildren(safe);
  338 |       if (String(html).length > MAX_HTML) {
  339 |         const note = document.createElement("p");
  340 |         note.className = "awx-trace-status";
  341 |         note.textContent = "표시 크기 제한으로 일부만 표시됨";
  342 |         state.body.appendChild(note);
  343 |       }
  344 |       return true;
  345 |     } catch (_) {
  346 |       status(state, "트레이스 표시 불가");
  347 |       return false;
  348 |     }
```

### C: `main/java/com/example/lms/service/trace/TraceHtmlBuilder.java:699–735`

```text
  699 |                 || truthy(extraMeta.get("orch.webRateLimited"))
  700 |                 || truthy(extraMeta.get("orch.auxLlmDown"));
  701 |         sb.append("<details class='trace-orch'").append(forceOpen ? " open" : "")
  702 |                 .append(">");
  703 |         sb.append("<summary>").append(escape(summary)).append("</summary>");
  704 |         sb.append(TraceHtmlOrchestrationModeCalloutRenderer.render(extraMeta));
  705 |         sb.append(TraceHtmlWebFailSoftRiskCalloutRenderer.render(extraMeta));
  706 |         sb.append(TraceHtmlSoakWebKpiCopyCalloutRenderer.render(extraMeta));
  707 |         sb.append(renderTraceAblationAttributionCallout(extraMeta, webTopK, vectorTopK));
  708 |         sb.append(TraceHtmlRelationThumbnailCallouts.renderBudget(extraMeta));
  709 |         sb.append(TraceHtmlRelationThumbnailCallouts.renderContextLayers(extraMeta));
  710 |         sb.append(TraceHtmlRelationThumbnailCallouts.renderSliceMap(extraMeta));
  711 |         sb.append("<table class='trace-kv'>");
  712 |         java.util.Set<String> shown = new java.util.HashSet<>();
  713 | 
  714 |         // Make "why STRIKE/BYPASS" visible without digging into scattered fields.
  715 |         appendKvGroup(sb, extraMeta, shown, "Mode",
  716 |                 java.util.List.of("orch.mode", "orch.strike", "orch.compression", "orch.bypass", "orch.reason",
  717 |                         "orch.webRateLimited", "orch.auxLlmDown", "orch.highRisk", "orch.irregularity",
  718 |                         "orch.noiseEscape.bypassSilentFailure",
  719 |                         "orch.noiseEscape.bypassSilentFailure.escapeP",
  720 |                         "orch.noiseEscape.bypassSilentFailure.roll",
  721 |                         "bypassReason"));
  722 | 
  723 |         // Context propagation / correlation anchors (high-signal debugging surface).
  724 |         // Render ctx.propagation.missing.events in a structured way (timeline/table),
  725 |         // not raw toString().
  726 |         if (extraMeta.containsKey("ctx.propagation.missing.events")) {
  727 |             shown.add("ctx.propagation.missing.events"); // handled by appendCtxMissingEvents()
  728 |         }
  729 |         appendKvPrefixGroup(sb, extraMeta, shown, "Context", "ctx.", 24);
  730 |         appendCtxMissingEvents(sb, extraMeta, shown);
  731 | 
  732 |         // MERGE_HOOK:PROJ_AGENT::ORCH_PARTS_TABLE_SHOW
  733 |         appendOrchPartsTable(sb, extraMeta, shown);
  734 | 
  735 |         if (extraMeta.containsKey("orch.events.v1")) {
```

### C: `main/java/com/example/lms/service/trace/TraceHtmlBuilder.java:1745–1788`

```text
 1745 |         if (events.isEmpty())
 1746 |             return;
 1747 | 
 1748 |         sb.append("<tr class='trace-kv-group'><th colspan='2'>Prompt Build Events</th></tr>");
 1749 |         sb.append("<tr><th>prompt.events</th><td>");
 1750 |         sb.append("<details open>");
 1751 |         sb.append("<summary>events=").append(events.size()).append("</summary>");
 1752 | 
 1753 |         sb.append("<table class='trace-table small'>");
 1754 |         sb.append("<thead><tr>")
 1755 |                 .append("<th>#</th><th>ts</th><th>step</th><th>web</th><th>rag</th><th>local</th><th>memory</th><th>verbosity</th><th>intent</th><th>domain</th><th>detail</th>")
 1756 |                 .append("</tr></thead><tbody>");
 1757 | 
 1758 |         int idx = 0;
 1759 |         for (java.util.Map<String, Object> ev : events) {
 1760 |             idx++;
 1761 |             Object seqObj = ev.get("seq");
 1762 |             String seq = (seqObj != null && !String.valueOf(seqObj).equals("null")) ? String.valueOf(seqObj)
 1763 |                     : String.valueOf(idx);
 1764 | 
 1765 |             String ts = safeValueOrDefault(ev.get("ts"), "");
 1766 |             if (ts.length() > 30)
 1767 |                 ts = ts.substring(0, 30);
 1768 | 
 1769 |             String step = firstNonBlank(
 1770 |                     safeValueOrDefault(ev.get("step"), null),
 1771 |                     safeValueOrDefault(ev.get("event"), null),
 1772 |                     "");
 1773 | 
 1774 |             String webCount = safeValueOrDefault(ev.get("webCount"), "");
 1775 |             String ragCount = safeValueOrDefault(ev.get("ragCount"), "");
 1776 |             String localCount = safeValueOrDefault(ev.get("localDocsCount"), "");
 1777 |             String mem = safeValueOrDefault(ev.get("memoryPresent"), "");
 1778 |             String verbosity = safeValueOrDefault(ev.get("verbosity"), "");
 1779 |             String intent = safeValueOrDefault(ev.get("intent"), "");
 1780 |             String domain = safeValueOrDefault(ev.get("domain"), "");
 1781 | 
 1782 |             String rowId = "prompt-ev-" + escapeAttr(seq);
 1783 | 
 1784 |             sb.append("<tr id='").append(rowId).append("'>");
 1785 |             sb.append("<td><a href='#").append(rowId).append("'><code>").append(escape(seq))
 1786 |                     .append("</code></a></td>");
 1787 |             sb.append("<td><code>").append(escape(ts)).append("</code></td>");
 1788 |             sb.append("<td><code>").append(escape(step)).append("</code></td>");
```

### Chromium 실제 실행 결과

```json
{
  "inputChars": 3467,
  "inputNestedRows": 120,
  "renderedNestedRows": 99,
  "laterOuterGroupVisible": false,
  "truncationNotice": false,
  "scripts": 0,
  "images": 0,
  "injectionExecuted": false,
  "traceDiagnosticNodeCount": 1,
  "panelCount": 1,
  "scope": "Isolated Chromium execution of actual uploaded JS and DOMPurify. Not deployed-server E2E.",
  "observationAssertions": "PASS (confirms loss bug and preserved sanitizer/dedup behavior)"
}

```

## E08 — 메모리 상세와 영속 요약의 차이

메모리 스냅샷, DB 포인터 projection, 종료 요약 파일을 서로 다른 보존 범위로 분류한다.

### C: `main/java/com/example/lms/trace/TraceSnapshotStore.java:29–62`

```text
   29 |  * In-memory ring buffer for request/task trace snapshots.
   30 |  *
   31 |  * <p>Why this exists:</p>
   32 |  * <ul>
   33 |  *   <li>{@link TraceStore} is ThreadLocal and cleared at the end of the request.</li>
   34 |  *   <li>When a bug happens (DI missing, stage handoff skipped, embedding failover, flush error),
   35 |  *       we want to retain a compact snapshot that can be inspected via a web endpoint.</li>
   36 |  * </ul>
   37 |  *
   38 |  * <p>Design goals:</p>
   39 |  * <ul>
   40 |  *   <li>Fail-soft: never block the request.</li>
   41 |  *   <li>Bounded memory: keep only the last N snapshots.</li>
   42 |  *   <li>Safe serialization: sanitize values to JSON-friendly primitives/strings.</li>
   43 |  * </ul>
   44 |  */
   45 | @Component
   46 | public class TraceSnapshotStore {
   47 | 
   48 |     private static final Logger LOG = LoggerFactory.getLogger("TRACE_SNAPSHOT");
   49 |     private static final Pattern ENSEMBLE_HASH_VALUE = Pattern.compile("hash:[0-9a-f]{12}");
   50 |     private static final int MAX_CAPTURE_BUDGET_ENTRIES = 8_192;
   51 | 
   52 |     @Value("${trace.snapshot.enabled:true}")
   53 |     private boolean enabled;
   54 | 
   55 |     @Value("${trace.snapshot.max-size:200}")
   56 |     private int maxSize;
   57 | 
   58 |     @Value("${trace.snapshot.max-value-len:2000}")
   59 |     private int maxValueLen;
   60 | 
   61 |     @Value("${trace.snapshot.max-entries:800}")
   62 |     private int maxEntries;
```

### C: `main/java/com/example/lms/trace/TraceSnapshotStore.java:124–146`

```text
  124 |     // ---------------------------------------------------------------------
  125 |     // Snapshot HTML (optional)
  126 |     // ---------------------------------------------------------------------
  127 | 
  128 |     @Value("${trace.snapshot.html.enabled:true}")
  129 |     private boolean htmlEnabled;
  130 | 
  131 |     @Value("${trace.snapshot.html.max-len:60000}")
  132 |     private int htmlMaxLen;
  133 | 
  134 |     private final ObjectProvider<com.example.lms.service.trace.TraceHtmlBuilder> htmlBuilderProvider;
  135 | 
  136 |     /** Per-trace capture budget (helps prevent over-capture for loops/background tasks). */
  137 |     private final Object budgetLock = new Object();
  138 |     private final LinkedHashMap<String, CaptureBudget> budgets = new LinkedHashMap<>(16, 0.75f, true);
  139 | 
  140 |     private final Object lock = new Object();
  141 |     private final Deque<TraceSnapshot> ring = new ArrayDeque<>();
  142 |     private long capturedSnapshotCount;
  143 |     private long evictedSnapshotCount;
  144 | 
  145 |     public TraceSnapshotStore(ObjectProvider<com.example.lms.service.trace.TraceHtmlBuilder> htmlBuilderProvider) {
  146 |         this.htmlBuilderProvider = htmlBuilderProvider;
```

### C: `main/java/com/example/lms/trace/TraceSnapshotStore.java:287–324`

```text
  287 |             // Optional: store an HTML diagnostics view (useful for web-based inspection).
  288 |             String html = null;
  289 |             boolean htmlTruncated = false;
  290 |             if (htmlOverride != null && !htmlOverride.isBlank()) {
  291 |                 String trimmedOverride = htmlOverride.trim();
  292 |                 boolean trustedGeneratedHtml =
  293 |                         (trimmedOverride.startsWith("<!doctype html><html data-trace-redacted=\"1\"")
  294 |                                 || trimmedOverride.startsWith("<details data-trace-redacted=\"1\" class=\"search-trace "))
  295 |                                 && "splitPanel".equals(firstString(rawTrace == null ? null : rawTrace.get("ui.traceHtml.kind")))
  296 |                                 && intValue(rawTrace == null ? null : rawTrace.get("ui.traceHtml.length")) == htmlOverride.length();
  297 |                 boolean generatedHarmonyMetadataHtml = isGeneratedHarmonyMetadataHtml(trimmedOverride, rawTrace, htmlOverride);
  298 |                 boolean generatedTraceMemoryMetadataHtml = isGeneratedTraceMemoryMetadataHtml(trimmedOverride, rawTrace, htmlOverride);
  299 |                 String overrideHtml = trustedGeneratedHtml
  300 |                         ? htmlOverride
  301 |                         : generatedHarmonyMetadataHtml
  302 |                         ? buildHarmonyMetadataHtml(rawTrace)
  303 |                         : generatedTraceMemoryMetadataHtml
  304 |                         ? buildTraceMemoryMetadataHtml(rawTrace)
  305 |                         : "<pre class=\"mono\">"
  306 |                                 + htmlEscape(String.valueOf(SafeRedactor.diagnosticValue("query", htmlOverride, 2000)))
  307 |                                 + "</pre>";
  308 |                 html = wrapHtmlIfNeeded(overrideHtml, id, tsIso, sid, traceId, requestId, snapshotReason, safe(method), snapshotPath, status, err);
  309 |             } else if (htmlEnabled) {
  310 |                 try {
  311 |                     html = buildHtmlSnapshot(id, tsIso, sid, traceId, requestId, snapshotReason, safe(method), snapshotPath, status, err, mdc, trace);
  312 |                 } catch (Throwable ignore) {
  313 |                     TraceStore.put("trace.snapshot.suppressed.htmlBuild", true);
  314 |                     TraceStore.put("trace.snapshot.suppressed.htmlBuild.errorType",
  315 |                             SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
  316 |                     html = null;
  317 |                 }
  318 |             }
  319 |             if (html != null && html.length() > Math.max(1024, htmlMaxLen)) {
  320 |                 html = html.substring(0, Math.max(1024, htmlMaxLen)) + "\n<!-- truncated -->";
  321 |                 htmlTruncated = true;
  322 |             }
  323 | 
  324 |             TraceSnapshot snap = new TraceSnapshot(
```

### C: `main/java/com/example/lms/api/TraceSnapshotsDiagnosticsController.java:238–259`

```text
  238 |     @GetMapping(value = "/snapshots/{id}/html", produces = MediaType.TEXT_HTML_VALUE)
  239 |     public ResponseEntity<String> getHtml(@PathVariable("id") String id) {
  240 |         TraceSnapshotStore store = storeProvider.getIfAvailable();
  241 |         if (store == null) {
  242 |             return ResponseEntity.status(HttpStatus.NOT_FOUND)
  243 |                     .contentType(MediaType.TEXT_HTML)
  244 |                     .body(simpleHtml("TraceSnapshotStore not available", snapshotIdMeta(id)));
  245 |         }
  246 |         return store.get(id)
  247 |                 .map(TraceSnapshotsDiagnosticsController::renderSnapshotHtml)
  248 |                 .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
  249 |                         .contentType(MediaType.TEXT_HTML)
  250 |                         .body(simpleHtml("snapshot not found", snapshotIdMeta(id))));
  251 |     }
  252 | 
  253 |     private static ResponseEntity<String> renderSnapshotHtml(TraceSnapshotStore.TraceSnapshot s) {
  254 |         String html = s.html();
  255 |         if (html == null || html.isBlank()) {
  256 |             java.util.LinkedHashMap<String, String> kv = snapshotFallbackMeta(s);
  257 |             html = simpleHtml("Trace snapshot (no stored HTML)", kv);
  258 |         }
  259 |         return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
```

### C: `main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java:82–123`

```text
   82 |     private static String durablePointer(
   83 |             String snapshotId,
   84 |             Long assistantMessageId,
   85 |             String reason,
   86 |             String method,
   87 |             String path,
   88 |             Map<String, Object> traceMeta) {
   89 |         StringBuilder projection = new StringBuilder(512);
   90 |         appendProjection(projection, "storageMode", "durable_fallback");
   91 |         if (assistantMessageId != null && assistantMessageId > 0L) {
   92 |             appendProjection(projection, "assistantMessageId", assistantMessageId);
   93 |         }
   94 |         appendProjection(projection, "reason", safeProjectionLabel(reason, "unknown"));
   95 |         appendProjection(projection, "method", safeProjectionLabel(method, "unknown"));
   96 |         appendProjection(projection, "pathHash", safeHash(path));
   97 |         appendProjection(projection, "traceEntryCount", boundedCount(traceMeta == null ? 0 : traceMeta.size()));
   98 |         appendProjection(projection, "hasMlBreadcrumbs", hasMlBreadcrumbs(traceMeta));
   99 |         appendProjection(projection, "uiTraceHtmlKind",
  100 |                 safeProjectionLabel(traceMeta == null ? null : traceMeta.get("ui.traceHtml.kind"), "unknown"));
  101 |         appendProjection(projection, "uiTraceHtmlLength",
  102 |                 boundedCount(traceMeta == null ? null : traceMeta.get("ui.traceHtml.length")));
  103 |         appendOptionalProjectionLabel(projection, "harmonyDecision", traceMeta,
  104 |                 "chat.harmony.postprocess.decision");
  105 |         appendOptionalProjectionLabel(projection, "harmonyReason", traceMeta,
  106 |                 "chat.harmony.postprocess.reason");
  107 |         appendOptionalProjectionLabel(projection, "traceMemoryStage", traceMeta,
  108 |                 "traceMemory.checkpoint.stage");
  109 |         appendOptionalProjectionLabel(projection, "traceMemoryReason", traceMeta,
  110 |                 "traceMemory.trigger.reason");
  111 | 
  112 |         byte[] bytes = projection.toString().getBytes(StandardCharsets.UTF_8);
  113 |         if (bytes.length > MAX_DURABLE_PROJECTION_BYTES) {
  114 |             String minimal = "storageMode=durable_fallback\n"
  115 |                     + (assistantMessageId != null && assistantMessageId > 0L
  116 |                     ? "assistantMessageId=" + assistantMessageId + "\n" : "")
  117 |                     + "reason=" + safeProjectionLabel(reason, "unknown") + "\n"
  118 |                     + "method=" + safeProjectionLabel(method, "unknown") + "\n"
  119 |                     + "pathHash=" + safeHash(path) + "\n";
  120 |             bytes = minimal.getBytes(StandardCharsets.UTF_8);
  121 |         }
  122 |         String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  123 |         return TRACE_SNAPSHOT_META_PREFIX + snapshotId + "|" + DURABLE_ENVELOPE_VERSION + "|" + encoded;
```

### C: `main/java/com/example/lms/debug/ChatSessionTraceRecorder.java:28–62`

```text
   28 | /**
   29 |  * 채팅/디스플레이 세션 종료 시 정제된 디버그 증거 한 건을 날짜별 파일에 append 한다.
   30 |  *
   31 |  * <p>목적: Grok/Devin/Codex/Cline이 같은 {@code var/debug/chat-session-traces/} 경로만 보고
   32 |  * 해당 세션의 모델·파이프라인 경고·Agent DB 상태를 재현할 수 있게 한다.</p>
   33 |  *
   34 |  * <p>안전 규칙:
   35 |  * <ul>
   36 |  *   <li>프롬프트/응답 본문, 토큰, API 키 값은 절대 기록하지 않는다.</li>
   37 |  *   <li>sessionId/runId는 {@code hash:<sha256-12>} 형태로만 기록한다
   38 |  *       (runId는 attach/cancel 권한을 가진 clientToken이므로 원문 저장 금지).</li>
   39 |  *   <li>trace 메타는 키 이름만 수집하고 값은 복사하지 않는다.</li>
   40 |  *   <li>파일 쓰기 실패는 호출자에게 전파하지 않는다(fail-soft).</li>
   41 |  * </ul>
   42 |  * </p>
   43 |  */
   44 | @Component
   45 | public class ChatSessionTraceRecorder {
   46 | 
   47 |     private static final Logger log = LoggerFactory.getLogger(ChatSessionTraceRecorder.class);
   48 |     private static final String SCHEMA = "awx.chat-session-trace.v1";
   49 |     private static final int MAX_TRACE_KEYS = 256;
   50 |     private static final long MAX_DEDUP_SCAN_BYTES = 512L * 1024L;
   51 |     private static final DateTimeFormatter DAY_DIR =
   52 |             DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
   53 | 
   54 |     private final ObjectMapper mapper = new ObjectMapper();
   55 |     private final Object writeMutex = new Object();
   56 | 
   57 |     @Value("${abandonware.debug.chat-session-traces.dir:var/debug/chat-session-traces}")
   58 |     private String traceDir = "var/debug/chat-session-traces";
   59 | 
   60 |     @Value("${abandonware.debug.chat-session-traces.enabled:true}")
   61 |     private boolean enabled = true;
   62 | 
```

### C: `main/java/com/example/lms/trace/TraceSnapshotExporter.java:125–170`

```text
  125 |     public TraceSnapshotExporter(Path rootDir, boolean enabled) {
  126 |         this.rootDir = rootDir;
  127 |         this.enabled = enabled;
  128 |     }
  129 | 
  130 |     public void exportCurrentTrace(String requestId, String sessionId) {
  131 |         if (!enabled || rootDir == null) {
  132 |             TraceStore.put("trace.snapshot.export.skipped", "disabled");
  133 |             return;
  134 |         }
  135 | 
  136 |         try {
  137 |             Files.createDirectories(rootDir);
  138 |             Map<String, Object> row = new LinkedHashMap<>();
  139 |             row.put("_ts", Instant.now().toString());
  140 |             row.put("_requestHash", SafeRedactor.hashValue(requestId));
  141 |             row.put("_sessionHash", SafeRedactor.hashValue(sessionId));
  142 | 
  143 |             Map<String, Object> trace = TraceStore.getAll();
  144 |             for (Map.Entry<String, Object> entry : trace.entrySet()) {
  145 |                 String key = entry.getKey();
  146 |                 if (!isAllowedKey(key)) {
  147 |                     continue;
  148 |                 }
  149 |                 row.put(key, exportValue(key, entry.getValue()));
  150 |             }
  151 |             putStageCountsFallback(row, trace);
  152 |             putRescueKpiFallbacks(row, trace);
  153 |             putStarvationKpiFallbacks(row, trace);
  154 | 
  155 |             Path file = rootDir.resolve("trace_" + LocalDate.now() + ".ndjson");
  156 |             Files.writeString(file, JSON.writeValueAsString(row) + System.lineSeparator(),
  157 |                     StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
  158 |             TraceStore.put("trace.snapshot.exported", true);
  159 |         } catch (IOException | RuntimeException e) {
  160 |             TraceStore.put("trace.snapshot.export.failed",
  161 |                     SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"));
  162 |         }
  163 |     }
  164 | 
  165 |     private static boolean isAllowedKey(String key) {
  166 |         if (key == null || key.isBlank()) {
  167 |             return false;
  168 |         }
  169 |         if (ALLOWED_KEYS.contains(key)) {
  170 |             return true;
```

## E09 — 관리자 딥링크와 최신 N건 기반 조회

필터 입력과 link는 있지만 서버 입력은 제한된 최신 목록 중심이다.

### C: `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java:90–104`

```text
   90 |     @GetMapping(value = "/events", produces = MediaType.APPLICATION_JSON_VALUE)
   91 |     public List<DebugEvent> list(@RequestParam(name = "limit", defaultValue = "80") int limit) {
   92 |         return store.list(limit);
   93 |     }
   94 | 
   95 |     @GetMapping(value = "/events/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
   96 |     public DebugEvent get(@PathVariable("id") String id) {
   97 |         return store.get(id);
   98 |     }
   99 | 
  100 |     @GetMapping(value = "/fingerprints", produces = MediaType.APPLICATION_JSON_VALUE)
  101 |     public List<Map<String, Object>> fingerprints(@RequestParam(name = "limit", defaultValue = "120") int limit) {
  102 |         return store.listFingerprints(limit);
  103 |     }
  104 | 
```

### C: `main/resources/templates/debug-events.html:620–640`

```text
  620 |       renderLocalLlmSmoke(data);
  621 |     } catch (e) {
  622 |       renderLocalLlmSmoke({ available: false, reason: 'local_llm_smoke_unavailable' });
  623 |     }
  624 |   }
  625 | 
  626 |   async function refreshEvents(revision = ++snapshotRevision) {
  627 |     if (es) return false;
  628 |     const lim = parseInt($('limit').value || '200', 10);
  629 |     const data = await fetchJson('/api/diagnostics/debug/events?limit=' + encodeURIComponent(String(lim)));
  630 |     if (revision !== snapshotRevision || es) return false;
  631 | 
  632 |     // API는 newest-first → UI는 tail(chronological)로 보기 위해 reverse
  633 |     const arr = Array.isArray(data) ? data.slice().reverse() : [];
  634 |     lastEvents = arr;
  635 |     seenIds = new Set(arr.map(e => e && e.id).filter(Boolean));
  636 | 
  637 |     $('listJson').textContent = JSON.stringify(arr, null, 2);
  638 |     renderRows(lastEvents, false);
  639 |     return true;
  640 |   }
```

### C: `main/resources/templates/debug-events.html:782–811`

```text
  782 |   async function loadDetail(id) {
  783 |     if (!id) return;
  784 |     try {
  785 |       const data = await fetchJson('/api/diagnostics/debug/events/' + encodeURIComponent(id));
  786 |       $('detailId').textContent = id;
  787 |       $('detailJson').textContent = JSON.stringify(data, null, 2);
  788 | 
  789 |       const traceId = fmt(data.traceId);
  790 |       const requestId = fmt(data.requestId);
  791 |       const sid = fmt(data.sid);
  792 |       $('detailTraceId').textContent = traceId || '-';
  793 |       $('detailRequestId').textContent = requestId || '-';
  794 |       $('detailErrorType').textContent = eventErrorType(data) || '-';
  795 |       $('detailModelRoute').textContent = eventModelRoute(data) || '-';
  796 |       $('detailSignal').textContent = eventSignalSummary(data) || '-';
  797 |       $('detailSid').textContent = sid || '-';
  798 |       $('detailAgg').textContent = eventAggSummary(data) || '-';
  799 | 
  800 |       const link = $('linkTraceSnapshots');
  801 |       if (traceId) {
  802 |         link.href = '/admin/trace-snapshots?traceId=' + encodeURIComponent(traceId);
  803 |         link.style.display = '';
  804 |       } else if (sid) {
  805 |         link.href = '/admin/trace-snapshots?sid=' + encodeURIComponent(sid);
  806 |         link.style.display = '';
  807 |       } else {
  808 |         link.href = '/admin/trace-snapshots';
  809 |         link.style.display = 'none';
  810 |       }
  811 |     } catch (e) {
```

### C: `main/resources/templates/trace-snapshots.html:184–216`

```text
  184 |   let lastList = [];
  185 | 
  186 |   async function refresh() {
  187 |     $('status').textContent = '불러오는 중...';
  188 |     try {
  189 |       const lim = parseInt($('limit').value || '50', 10);
  190 |       const data = await fetchJson('/api/diagnostics/trace/snapshots?limit=' + encodeURIComponent(String(lim)));
  191 |       lastList = (data && data.snapshots) ? data.snapshots : [];
  192 |       $('listJson').textContent = JSON.stringify(data, null, 2);
  193 |       renderRows(lastList);
  194 | 
  195 |       const ts = new Date().toLocaleString();
  196 |       $('status').textContent = '업데이트 완료: ' + ts;
  197 |     } catch (e) {
  198 |       console.error(e);
  199 |       $('status').textContent = '실패: ' + (e && e.message ? e.message : String(e));
  200 |     }
  201 |   }
  202 | 
  203 |   function applyQueryPrefill() {
  204 |     try {
  205 |       const qs = new URLSearchParams(window.location.search || '');
  206 |       const q = (qs.get('q') || '').trim();
  207 |       const traceId = (qs.get('traceId') || '').trim();
  208 |       const sid = (qs.get('sid') || '').trim();
  209 | 
  210 |       if (q) $('filter').value = q;
  211 |       else if (traceId) $('filter').value = traceId;
  212 |       else if (sid) $('filter').value = sid;
  213 |     } catch (e) { void e; }
  214 |   }
  215 | 
  216 |   async function loadDetail(id) {
```

### C: `main/java/com/example/lms/api/TraceSnapshotsDiagnosticsController.java:52–77`

```text
   52 |                                                ObjectProvider<TraceMemoryFingerprintProbe> traceMemoryProbeProvider) {
   53 |         this.storeProvider = storeProvider;
   54 |         this.traceMemoryProbeProvider = traceMemoryProbeProvider;
   55 |     }
   56 | 
   57 |     @GetMapping("/snapshots")
   58 |     public ResponseEntity<Map<String, Object>> list(
   59 |             @RequestParam(value = "limit", required = false, defaultValue = "50") int limit
   60 |     ) {
   61 |         TraceSnapshotStore store = storeProvider.getIfAvailable();
   62 |         Map<String, Object> out = new LinkedHashMap<>();
   63 |         out.put("ts", Instant.now().toString());
   64 |         out.put("available", store != null);
   65 |         out.put("snapshots", store == null ? java.util.List.of() : store.listSummaries(limit));
   66 |         return ResponseEntity.ok(out);
   67 |     }
   68 | 
   69 |     @GetMapping("/snapshots/{id}")
   70 |     public ResponseEntity<?> get(@PathVariable("id") String id) {
   71 |         TraceSnapshotStore store = storeProvider.getIfAvailable();
   72 |         if (store == null) {
   73 |             return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "TraceSnapshotStore not available"));
   74 |         }
   75 |         return store.get(id)
   76 |                 .<ResponseEntity<?>>map(ResponseEntity::ok)
   77 |                 .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "snapshot not found")));
```

## E10 — SSE resume·tail·cursor 및 기존 bounded worker

초기 backlog는 cursor filtering 없이 전송한다. worker 제한과 취소는 이미 있으므로 보존한다. timestamp 역전은 별도 커서 실험으로 확인했다.

### C: `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java:120–180`

```text
  120 |     @GetMapping(value = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  121 |     public SseEmitter stream(
  122 |             @RequestParam(name = "limit", defaultValue = "50") int limit,
  123 |             @RequestParam(name = "pollMs", defaultValue = "900") long pollMs,
  124 |             @RequestParam(name = "heartbeatMs", defaultValue = "15000") long heartbeatMs,
  125 |             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
  126 |         final int initialLimit = clamp(limit, 1, 500);
  127 |         final long sleepMs = clamp(pollMs, 200, 5000);
  128 |         final long hbMs = clamp(heartbeatMs, 3000, 60000);
  129 | 
  130 |         final SseEmitter emitter = emitterFactory.apply(sseTimeoutMs);
  131 |         final StreamSession session = new StreamSession(emitter);
  132 | 
  133 |         emitter.onCompletion(session::terminateFromCallback);
  134 |         emitter.onTimeout(session::terminateFromCallback);
  135 |         emitter.onError(failure -> session.terminateFromCallback());
  136 | 
  137 |         final FutureTask<Void> task = new FutureTask<>(() -> {
  138 |             // If reconnecting, best-effort resume from last id (if still in ring).
  139 |             try {
  140 |                 if (lastEventId != null && !lastEventId.isBlank()) {
  141 |                     DebugEvent last = store.get(lastEventId.trim());
  142 |                     session.resumeFrom(last);
  143 |                 }
  144 |             } catch (Throwable ignore) {
  145 |                 traceSuppressed("stream.resume", ignore);
  146 |             }
  147 | 
  148 |             long lastHeartbeatAt = System.currentTimeMillis();
  149 | 
  150 |             try {
  151 |                 // Hello / meta event
  152 |                 try {
  153 |                     Map<String, Object> hello = new LinkedHashMap<>();
  154 |                     hello.put("ts", Instant.now().toString());
  155 |                     hello.put("mode", "sse");
  156 |                     hello.put("initialLimit", initialLimit);
  157 |                     hello.put("pollMs", sleepMs);
  158 |                     emitter.send(SseEmitter.event().name("hello").data(hello));
  159 |                 } catch (IOException e) {
  160 |                     traceSuppressed("stream.hello", e);
  161 |                     session.stopFromWorker();
  162 |                 }
  163 | 
  164 |                 // Initial backlog (oldest -> newest)
  165 |                 if (session.isOpen()) {
  166 |                     List<DebugEvent> initial = safeList(initialLimit);
  167 |                     Collections.reverse(initial);
  168 |                     session.sendEvents(initial, "stream.initial");
  169 |                 }
  170 | 
  171 |                 // Tail loop
  172 |                 while (session.isOpen()) {
  173 |                     List<DebugEvent> snapshot = safeList(Math.max(initialLimit, 120));
  174 |                     session.sendEvents(session.newEvents(snapshot), "stream.tail");
  175 | 
  176 |                     long now = System.currentTimeMillis();
  177 |                     if (now - lastHeartbeatAt >= hbMs) {
  178 |                         try {
  179 |                             emitter.send(SseEmitter.event().name("hb").comment("keep-alive"));
  180 |                         } catch (IOException e) {
```

### C: `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java:285–299`

```text
  285 |         private void sendEvents(List<DebugEvent> events, String stage) {
  286 |             for (DebugEvent event : events) {
  287 |                 if (!isOpen()) {
  288 |                     break;
  289 |                 }
  290 |                 try {
  291 |                     sendEvent(emitter, event);
  292 |                     cursor.advance(event);
  293 |                 } catch (IOException failure) {
  294 |                     traceSuppressed(stage, failure);
  295 |                     stopFromWorker();
  296 |                     break;
  297 |                 }
  298 |             }
  299 |         }
```

### C: `main/java/com/example/lms/api/DebugEventsDiagnosticsController.java:364–397`

```text
  364 |     private static final class Cursor {
  365 |         long lastTsMs = 0L;
  366 |         final Set<String> idsAtLastTs = new HashSet<>();
  367 | 
  368 |         boolean isNew(DebugEvent ev) {
  369 |             if (ev == null)
  370 |                 return false;
  371 |             long t = ev.tsMs();
  372 |             String id = ev.id();
  373 |             if (t > lastTsMs)
  374 |                 return true;
  375 |             if (t < lastTsMs)
  376 |                 return false;
  377 |             if (id == null)
  378 |                 return false;
  379 |             return !idsAtLastTs.contains(id);
  380 |         }
  381 | 
  382 |         void advance(DebugEvent ev) {
  383 |             if (ev == null)
  384 |                 return;
  385 |             long t = ev.tsMs();
  386 |             String id = ev.id();
  387 |             if (t > lastTsMs) {
  388 |                 lastTsMs = t;
  389 |                 idsAtLastTs.clear();
  390 |                 if (id != null)
  391 |                     idsAtLastTs.add(id);
  392 |                 return;
  393 |             }
  394 |             if (t == lastTsMs && id != null) {
  395 |                 idsAtLastTs.add(id);
  396 |             }
  397 |         }
```

### C: `main/java/com/example/lms/api/DebugEventsSseRuntime.java:16–42`

```text
   16 | /** Owns the bounded worker lifecycle for the admin debug-events SSE endpoint. */
   17 | @Component
   18 | public final class DebugEventsSseRuntime {
   19 | 
   20 |     private static final int DEFAULT_MAX_CLIENTS = 8;
   21 |     private static final int MAX_CLIENTS_LIMIT = 64;
   22 |     private static final long KEEP_ALIVE_SECONDS = 30L;
   23 | 
   24 |     private final ThreadPoolExecutor executor;
   25 |     private final AtomicLong executionAttempts = new AtomicLong();
   26 | 
   27 |     @Autowired
   28 |     public DebugEventsSseRuntime(
   29 |             @Value("${lms.debug.events.sse.max-clients:8}") int configuredMaxClients) {
   30 |         this(configuredMaxClients, daemonThreadFactory());
   31 |     }
   32 | 
   33 |     DebugEventsSseRuntime(int configuredMaxClients, ThreadFactory threadFactory) {
   34 |         int maxClients = normalizeMaxClients(configuredMaxClients);
   35 |         this.executor = new ThreadPoolExecutor(
   36 |                 0,
   37 |                 maxClients,
   38 |                 KEEP_ALIVE_SECONDS,
   39 |                 TimeUnit.SECONDS,
   40 |                 new SynchronousQueue<>(),
   41 |                 Objects.requireNonNull(threadFactory, "threadFactory"),
   42 |                 new ThreadPoolExecutor.AbortPolicy());
```

### 추출 Cursor 실제 실행 결과

```text
lateArrivalOlderTimestampRecognized=false
sameTimestampDifferentIdRecognized=true
SCOPE=Exact private Cursor class copied from uploaded controller; not a live SSE server

```

## E11 — 각 로그 출력의 실제 분리

DebugEventStore ring/async mirror와 TRACE_JSON 파일은 다른 표면이다. 일반 stdout 전체를 웹으로 보내는 구성이라고 볼 수 없다.

### C: `main/resources/logback-spring.xml:5–59`

```text
    5 |   <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
    6 |     <encoder>
    7 |       <pattern>%d{yyyy-MM-dd'T'HH:mm:ss.SSSZ} %-5level [%X{sid} %X{trace}] %logger{36} - %msg%n</pattern>
    8 |     </encoder>
    9 |   </appender>
   10 | 
   11 |   <!-- DebugEventStore (ring buffer) emits single-line JSON to logger DEBUG_EVENT_JSON.
   12 |        Make it observable in both console and file as NDJSON. -->
   13 |   <appender name="DEBUG_EVENT_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
   14 |     <encoder>
   15 |       <pattern>%msg%n</pattern>
   16 |     </encoder>
   17 |   </appender>
   18 | 
   19 |   <!-- NDJSON file appender: one file per day, retained for one week -->
   20 |   <appender name="DEBUG_EVENT_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
   21 |     <file>logs/debug-events.ndjson</file>
   22 |     <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
   23 |       <fileNamePattern>logs/debug-events.%d{yyyy-MM-dd}.ndjson</fileNamePattern>
   24 |       <maxHistory>7</maxHistory>
   25 |     </rollingPolicy>
   26 |     <encoder>
   27 |       <pattern>%msg%n</pattern>
   28 |     </encoder>
   29 |   </appender>
   30 | 
   31 |   <!-- NDJSON file appender: one file per day, retained for one week -->
   32 |   <appender name="TRACE_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
   33 |     <file>logs/trace.ndjson</file>
   34 |     <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
   35 |       <fileNamePattern>logs/trace.%d{yyyy-MM-dd}.ndjson</fileNamePattern>
   36 |       <maxHistory>7</maxHistory>
   37 |     </rollingPolicy>
   38 |     <encoder>
   39 |       <pattern>%msg%n</pattern>
   40 |     </encoder>
   41 |   </appender>
   42 | 
   43 |   <!-- Dedicated logger for structured trace events -->
   44 |   <logger name="TRACE_JSON" level="INFO" additivity="false">
   45 |     <appender-ref ref="TRACE_FILE"/>
   46 |   </logger>
   47 | 
   48 |   <!-- Dedicated logger for structured DebugEventStore events -->
   49 |   <logger name="DEBUG_EVENT_JSON" level="INFO" additivity="false">
   50 |     <appender-ref ref="DEBUG_EVENT_CONSOLE"/>
   51 |     <appender-ref ref="DEBUG_EVENT_FILE"/>
   52 |   </logger>
   53 | 
   54 |   <!-- Dedicated console logger for search traces (compact summaries) -->
   55 |   <logger name="SEARCH_TRACE" level="INFO" additivity="false">
   56 |     <appender-ref ref="CONSOLE"/>
   57 |   </logger>
   58 | 
   59 |   <!-- Optional: bump search-related packages without turning on 전체 com.example.lms DEBUG -->
```

### C: `main/java/com/example/lms/debug/DebugEventStore.java:71–108`

```text
   71 |     private final Deque<DebugEvent> ring = new ConcurrentLinkedDeque<>();
   72 |     private final Map<String, AggState> byFingerprint = new ConcurrentHashMap<>();
   73 |     private final Object aggregateStateMutex = new Object();
   74 |     private final Object ndjsonExecutorMutex = new Object();
   75 |     private final NdjsonLineWriter ndjsonLineWriter;
   76 |     private final ThreadFactory ndjsonThreadFactory;
   77 |     private final AtomicLong ndjsonDropped = new AtomicLong();
   78 |     private long aggregateTouchOrder;
   79 |     private volatile ThreadPoolExecutor ndjsonExecutor;
   80 |     private volatile boolean ndjsonWriterClosed;
   81 | 
   82 |     @Value("${lms.debug.events.enabled:true}")
   83 |     private boolean enabled = true;
   84 | 
   85 |     @Value("${lms.debug.events.max-size:600}")
   86 |     private int maxSize = 600;
   87 | 
   88 |     @Value("${lms.debug.events.rate.max-fingerprints:0}")
   89 |     private int maxFingerprints;
   90 | 
   91 |     @Value("${lms.debug.events.rate.window-ms:60000}")
   92 |     private long windowMs = 60_000L;
   93 | 
   94 |     @Value("${lms.debug.events.rate.max-per-window:6}")
   95 |     private long maxPerWindow = 6L;
   96 | 
   97 |     @Value("${lms.debug.events.rate.flush-interval-ms:15000}")
   98 |     private long flushIntervalMs = 15_000L;
   99 | 
  100 |     @Value("${abandonware.debug.ndjson-dir:${ABNADON_DEBUG_DIR:var/abnadon/debug}}")
  101 |     private String ndjsonDir = "var/abnadon/debug";
  102 | 
  103 |     @Value("${abandonware.debug.ndjson.enabled:true}")
  104 |     private boolean ndjsonEnabled = true;
  105 | 
  106 |     @Value("${abandonware.debug.ndjson.queue-capacity:256}")
  107 |     private int ndjsonQueueCapacity = DEFAULT_NDJSON_QUEUE_CAPACITY;
  108 | 
```

### C: `main/java/com/example/lms/debug/DebugEventStore.java:492–546`

```text
  492 |         ring.addFirst(ev);
  493 |         while (ring.size() > maxSize) {
  494 |             ring.pollLast();
  495 |         }
  496 |     }
  497 | 
  498 |     private void logJson(DebugEvent ev) {
  499 |         try {
  500 |             // Avoid relying on JSR-310 modules for JSONL logs; Instant is
  501 |             // converted explicitly so debug serialization cannot drop events.
  502 |             String jsonLine = mapper.writeValueAsString(asJsonLine(ev));
  503 |             JSON_LOG.info(jsonLine);
  504 |             mirrorNdjson(jsonLine);
  505 |         } catch (Exception e) {
  506 |             // Never break the request path.
  507 |             LOG.debug("Failed to serialize DebugEvent. errorHash={} errorLength={}",
  508 |                     SafeRedactor.hashValue(messageOf(e)), messageLength(e));
  509 |         }
  510 |     }
  511 | 
  512 |     private void mirrorNdjson(String jsonLine) {
  513 |         if (!ndjsonEnabled || jsonLine == null || jsonLine.isBlank()) {
  514 |             return;
  515 |         }
  516 | 
  517 |         String directory = ndjsonDir == null || ndjsonDir.isBlank() ? "var/abnadon/debug" : ndjsonDir.trim();
  518 |         String fileName = LocalDate.now().toString() + ".ndjson";
  519 |         ThreadPoolExecutor executor = ndjsonExecutor();
  520 |         if (executor == null) {
  521 |             recordNdjsonDrop("writer_closed");
  522 |             return;
  523 |         }
  524 | 
  525 |         try {
  526 |             executor.execute(() -> writeNdjson(directory, fileName, jsonLine));
  527 |         } catch (RejectedExecutionException rejected) {
  528 |             recordNdjsonDrop(executor.isShutdown() ? "writer_closed" : "queue_saturated");
  529 |         }
  530 |     }
  531 | 
  532 |     private void writeNdjson(String directory, String fileName, String jsonLine) {
  533 |         try {
  534 |             ndjsonLineWriter.write(directory, fileName, jsonLine);
  535 |         } catch (InterruptedException interrupted) {
  536 |             Thread.currentThread().interrupt();
  537 |             LOG.debug("Failed to mirror DebugEvent NDJSON. errorHash={} errorLength={}",
  538 |                     SafeRedactor.hashValue(messageOf(interrupted)), messageLength(interrupted));
  539 |         } catch (Exception e) {
  540 |             LOG.debug("Failed to mirror DebugEvent NDJSON. errorHash={} errorLength={}",
  541 |                     SafeRedactor.hashValue(messageOf(e)), messageLength(e));
  542 |         }
  543 |     }
  544 | 
  545 |     private ThreadPoolExecutor ndjsonExecutor() {
  546 |         ThreadPoolExecutor current = ndjsonExecutor;
```

### C: `main/java/com/example/lms/trace/TraceLogger.java:51–95`

```text
   51 |     /**
   52 |      * Emit a structured trace event.  The event inherits the current MDC
   53 |      * values for {@code sid} and {@code trace}.  When tracing is
   54 |      * disabled or the event is dropped by sampling, this method is a
   55 |      * no-op.
   56 |      *
   57 |      * @param type  the event type (e.g. search_decision)
   58 |      * @param stage the pipeline stage (search, prompt, llm, post, summary)
   59 |      * @param kv    structured key/value data to include with the event
   60 |      */
   61 |     public static void emit(String type, String stage, Map<String, Object> kv) {
   62 |         if (!enabled) return;
   63 |         if (sample < 1.0 && Math.random() > sample) return;
   64 |         String sid = firstNonBlank(MDC.get("sid"), MDC.get("sessionId"));
   65 |         String trace = firstNonBlank(MDC.get("traceId"), MDC.get("trace"));
   66 |         String requestId = firstNonBlank(MDC.get("x-request-id"), trace);
   67 |         try {
   68 |             Map<String, Object> safeKv = sanitizeKv(kv);
   69 |             TraceEvent ev = new TraceEvent(Instant.now(), safeLabel(type), safeLabel(stage),
   70 |                     hashOrEmpty(sid),
   71 |                     hashOrEmpty(trace),
   72 |                     hashOrEmpty(requestId),
   73 |                     safeKv);
   74 |             LOG.info(MAPPER.writeValueAsString(ev));
   75 |         } catch (Exception ignore) {
   76 |             TraceStore.put("trace.logger.suppressed.emit", true);
   77 |             TraceStore.put("trace.logger.suppressed.emit.errorType",
   78 |                     SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
   79 |             // suppress any logging exceptions to avoid interfering with core logic
   80 |         }
   81 |     }
   82 | 
   83 |     private static Map<String, Object> sanitizeKv(Map<String, Object> kv) {
   84 |         if (kv == null || kv.isEmpty()) return Map.of();
   85 |         Map<String, Object> out = new LinkedHashMap<>();
   86 |         for (Map.Entry<String, Object> e : kv.entrySet()) {
   87 |             if (e == null || e.getKey() == null || e.getKey().isBlank()) continue;
   88 |             String key = e.getKey();
   89 |             out.put(safeLabel(key), SafeRedactor.diagnosticValue("trace." + key, e.getValue(), 2048));
   90 |         }
   91 |         return out;
   92 |     }
   93 | 
   94 |     private static String firstNonBlank(String... values) {
   95 |         if (values == null) return null;
```

## E12 — prompt 단계와 실제 모델 입력 경계의 차이

delivered가 특정 섹션 마커 포함 여부를 뜻하는 지점을 그대로 기록한다. 네트워크 성공이나 모델 이해를 입증하지 않는다.

### C: `main/java/com/example/lms/service/ChatWorkflow.java:3190–3227`

```text
 3190 |             if (ctx.answerMode() != null)
 3191 |                 TraceStore.put("prompt.answerMode", String.valueOf(ctx.answerMode()));
 3192 |             if (ctx.visionMode() != null)
 3193 |                 TraceStore.put("prompt.visionMode", String.valueOf(ctx.visionMode()));
 3194 |             if (ctx.memoryMode() != null)
 3195 |                 TraceStore.put("prompt.memoryMode", String.valueOf(ctx.memoryMode()));
 3196 |             TraceStore.put("prompt.sectionSpec.count", (ctx.sectionSpec() != null) ? ctx.sectionSpec().size() : 0);
 3197 |             java.util.Map<String, Object> pev = new java.util.LinkedHashMap<>();
 3198 |             pev.put("seq", TraceStore.nextSequence("prompt.events"));
 3199 |             pev.put("ts", java.time.Instant.now().toString());
 3200 |             pev.put("step", "PromptBuilder.build.enter");
 3201 |             pev.put("webCount", webCount);
 3202 |             pev.put("ragCount", ragCount);
 3203 |             pev.put("localDocsCount", localDocsCount);
 3204 |             pev.put("citableEvidenceCount", evidenceCount);
 3205 |             pev.put("memoryPresent", memPresent);
 3206 |             pev.put("learningRole", ragSupportRoleLabel);
 3207 |             pev.put("learningSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
 3208 |             pev.put("learningSourceTags", ragSupportContext.sourceTags());
 3209 |             pev.put("learningDegraded", !learningDegradedReason.isBlank());
 3210 |             if (!learningDegradedReason.isBlank()) {
 3211 |                 pev.put("learningDegradedReason", safeLearningDegradedReason);
 3212 |             }
 3213 |             pev.put("ragSupportRole", ragSupportRoleLabel);
 3214 |             pev.put("ragSupportSignalCount", ctx.learningSignals() != null ? ctx.learningSignals().size() : 0);
 3215 |             pev.put("ragSupportSourceTags", ragSupportContext.sourceTags());
 3216 |             pev.put("ragSupportDegraded", !learningDegradedReason.isBlank());
 3217 |             if (!learningDegradedReason.isBlank()) {
 3218 |             pev.put("ragSupportDegradedReason", safeLearningDegradedReason);
 3219 |             }
 3220 |             pev.put("verbosity", vp.hint());
 3221 |             if (ctx.intent() != null)
 3222 |                 pev.put("intent", SafeRedactor.diagnosticValue("intent", ctx.intent(), 160));
 3223 |             if (ctx.domain() != null)
 3224 |                 pev.put("domain", SafeRedactor.diagnosticValue("domain", ctx.domain(), 160));
 3225 |             TraceStore.append("prompt.events", pev);
 3226 |         } catch (Throwable ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("prompt.contextTrace", ignore); }
 3227 | 
```

### C: `main/java/com/example/lms/service/ChatWorkflow.java:3252–3285`

```text
 3252 |                 // template fingerprint.
 3253 |                 int cap = Math.min(2048, ctxText.length());
 3254 |                 TraceStore.put("prompt.ctx.prefix.sha1", TextUtils.sha1(ctxText.substring(0, cap)));
 3255 |             
 3256 |             // 세션 대화 컨텍스트 단계별 진단: 조립(assembled)된 값과 실제 프롬프트
 3257 |             // 전달(delivered) 여부를 구분해 "메모리 정상" 오판을 막는다.
 3258 |             TraceStore.put("prompt.contextInjected.history", historyStr != null && !historyStr.isBlank());
 3259 |             TraceStore.put("prompt.contextInjected.historyChars", historyStr == null ? 0 : historyStr.length());
 3260 |             TraceStore.put("prompt.contextInjected.lastAssistant", lastAnswer != null && !lastAnswer.isBlank());
 3261 |             TraceStore.put("prompt.contextInjected.memory", memoryCtx != null && !memoryCtx.isBlank());
 3262 |             TraceStore.put("prompt.contextInjected.delivered",
 3263 |                     ctxText != null && ctxText.contains("### RECENT CONVERSATION"));}
 3264 |             int webCount = (ctx.web() != null) ? ctx.web().size() : 0;
 3265 |             int ragCount = (ctx.rag() != null) ? ctx.rag().size() : 0;
 3266 |             int localDocsCount = (ctx.localDocs() != null) ? ctx.localDocs().size() : 0;
 3267 |             int evidenceCount = (ctx.evidence() != null) ? ctx.evidence().size() : 0;
 3268 |             int sourceDiversity = 0;
 3269 |             if (webCount > 0) sourceDiversity++;
 3270 |             if (ragCount > 0) sourceDiversity++;
 3271 |             if (localDocsCount > 0) sourceDiversity++;
 3272 |             if (ctx.memory() != null && !ctx.memory().isBlank()) sourceDiversity++;
 3273 |             Map<String, Object> input = new LinkedHashMap<>();
 3274 |             input.put("queryHash", SafeRedactor.hash12(finalQuery));
 3275 |             input.put("queryLen", safeLen(finalQuery));
 3276 |             input.put("requestedTopK", webCount + ragCount + localDocsCount);
 3277 |             input.put("mode", "prompt_builder");
 3278 |             Map<String, Object> output = new LinkedHashMap<>();
 3279 |             output.put("returnedCount", webCount + ragCount + localDocsCount);
 3280 |             output.put("afterFilterCount", webCount + ragCount + localDocsCount);
 3281 |             output.put("selectedCount", evidenceCount);
 3282 |             output.put("promotedCount", evidenceCount);
 3283 |             output.put("stageMs", promptBuildMs);
 3284 |             output.put("sourceDiversity", sourceDiversity);
 3285 |             Map<String, Object> control = new LinkedHashMap<>();
```

### C: `main/java/com/example/lms/service/ChatWorkflow.java:6901–6913`

```text
 6901 |         try {
 6902 |             TraceStore.put("llm.call.modelHash", SafeRedactor.hashValue(resolved));
 6903 |             TraceStore.put("llm.call.inputChars", estimateChatMessageChars(msgs));
 6904 |             TraceStore.put("llm.call.approxInputTokens", estimateTokensFromChars(estimateChatMessageChars(msgs)));
 6905 |             TraceStore.put("llm.call.maxAttempts", strictSingleAttempt ? 1 : llmMaxAttempts + 1);
 6906 |             TraceStore.put("llm.call.strictSingleAttempt", strictSingleAttempt);
 6907 |             if (requestedModel != null) {
 6908 |                 TraceStore.put("llm.call.model.requestedHash", SafeRedactor.hashValue(requestedModel));
 6909 |             }
 6910 |             if (routedModel != null) {
 6911 |                 TraceStore.put("llm.call.model.routedHash", SafeRedactor.hashValue(routedModel));
 6912 |             }
 6913 |         } catch (Exception ignore) { ChatWorkflowTraceSuppressions.traceSuppressed("llm.callModelTrace", ignore); }
```

### C: `main/java/com/example/lms/service/trace/TraceHtmlBuilder.java:70–104`

```text
   70 |         int rawCount = (rawSnippets == null) ? 0 : rawSnippets.size();
   71 |         boolean webEnabled = webTopK != null;
   72 |         boolean vectorEnabled = vectorTopK != null;
   73 |         boolean webTraceUnavailable = Boolean.TRUE.equals(webSearchAttempted) || rawCount > 0
   74 |                 || (webTopK != null && !webTopK.isEmpty());
   75 |         Map<String, Object> safeExtraMeta = sanitizeMeta(extraMeta);
   76 | 
   77 |         RiskLevel risk = evaluateRisk(safeExtraMeta);
   78 |         String riskClass = cssRiskClass(risk);
   79 |         boolean autoOpen = risk != RiskLevel.OK;
   80 | 
   81 |         String summaryLine = buildSummaryLine(rawTrace, rawCount, webTopK, vectorTopK,
   82 |                 safeExtraMeta, risk, webTraceUnavailable);
   83 | 
   84 |         StringBuilder sb = new StringBuilder();
   85 |         sb.append("<details data-trace-redacted=\"1\" class=\"search-trace ").append(riskClass).append("\"");
   86 |         if (autoOpen) {
   87 |             sb.append(" open");
   88 |         }
   89 |         sb.append(">");
   90 |         sb.append("<summary>");
   91 |         sb.append(summaryLine);
   92 |         sb.append("</summary>");
   93 |         sb.append("<div class='trace-body'>");
   94 | 
   95 |         sb.append(renderRawSearchPanel(rawTrace, rawSnippets, safeExtraMeta, webTraceUnavailable));
   96 |         sb.append(renderTopKPanel("B) Final Context (LLM Input)",
   97 |                 webEnabled ? webTopK : null,
   98 |                 vectorEnabled ? vectorTopK : null));
   99 |         sb.append(TraceHtmlCitableEvidenceRenderer.render(safeExtraMeta));
  100 |         sb.append(renderOrchestrationPanel(safeExtraMeta, webTopK, vectorTopK, risk));
  101 | 
  102 |         sb.append("</div>");
  103 |         sb.append("</details>");
  104 |         return sb.toString();
```

## E13 — debug=true와 bridge, 명시적 triadic 동작

debug 플래그가 관측 외 계산과 연결될 수 있음을 보여준다. 실제 답변 차이는 측정되지 않았으므로 on/off differential test 항목이다.

### C: `main/java/com/example/lms/web/TraceFilter.java:137–143`

```text
  137 |         boolean requestDebug = isTruthy(req.getParameter("debug"))
  138 |                 || isTruthy(req.getHeader(DBG_SEARCH_HEADER))
  139 |                 || isTruthy(req.getHeader("X-Debug"));
  140 | 
  141 |         boolean dbgSearch = requestDebug || boostActive;
  142 | 
  143 |         // Enrich TraceStore with boost status + debug UX knobs (best-effort)
```

### C: `main/java/com/example/lms/web/TraceFilter.java:182–200`

```text
  182 |             // Bridge dbgSearch state into TraceStore so console/HTML can share one truth.
  183 |             try {
  184 |                 if (dbgSearch)
  185 |                     com.example.lms.search.TraceStore.put("dbg.search.enabled", true);
  186 |                 if (dbgSearch)
  187 |                     com.example.lms.search.TraceStore.put("uaw.ablation.bridge", true);
  188 |                 if (boostActive)
  189 |                     com.example.lms.search.TraceStore.put("dbg.search.source", "boost");
  190 |                 else if (dbgSearch)
  191 |                     com.example.lms.search.TraceStore.put("dbg.search.source", "request");
  192 |             } catch (Throwable ignore) {
  193 |                 traceSuppressed("debugSource.traceStore", ignore);
  194 |             }
  195 | 
  196 |             if (dbgSearch) {
  197 |                 MDC.put(DBG_SEARCH_MDC, "1");
  198 |                 MDC.put(DBG_SEARCH_SOURCE_MDC, boostActive ? "boost" : "request");
  199 |                 if (boostActive && awaitBoostDetailEnginesCsv != null && !awaitBoostDetailEnginesCsv.isBlank()) {
  200 |                     MDC.put(DBG_SEARCH_BOOST_ENGINES_MDC, awaitBoostDetailEnginesCsv);
```

### C: `main/java/com/example/lms/service/trace/DebugCopilotService.java:86–112`

```text
   86 |     /**
   87 |      * Explicit admin action. This is intentionally separate from
   88 |      * {@link #maybeEnrichTrace()} so ordinary chat requests never incur the
   89 |      * triadic model calls.
   90 |      */
   91 |     public EvidenceGroundedTriadicDebugAdjudicator.Adjudication adjudicateLatestPatchCandidate() {
   92 |         return adjudicateLatestPatchCandidate(null);
   93 |     }
   94 | 
   95 |     public EvidenceGroundedTriadicDebugAdjudicator.Adjudication adjudicateLatestPatchCandidate(
   96 |             EvidenceGroundedTriadicDebugAdjudicator.PatchCandidate patchCandidate) {
   97 |         if (triadicAdjudicator == null || debugEventStore == null) {
   98 |             return rememberTriadicResult(EvidenceGroundedTriadicDebugAdjudicator.Adjudication.hold(
   99 |                     "triadic_service_unavailable", "none", 0, 0, 0.0d, 0.0d, 0));
  100 |         }
  101 |         try {
  102 |             String rid = firstNonBlank(
  103 |                     MDC.get("requestId"),
  104 |                     MDC.get("traceId"),
  105 |                     asString(TraceStore.get("trace.id")),
  106 |                     "debug-admin");
  107 |             var result = rememberTriadicResult(triadicAdjudicator.adjudicate(
  108 |                     triadicFingerprintEvidence(), patchCandidate, rid));
  109 |             try {
  110 |                 debugEventStore.emit(
  111 |                         DebugProbeType.ORCHESTRATION,
  112 |                         result.decision() == com.example.lms.ensemble.EnsembleJudgeService.DebugPatchDecision.HOLD
```

## E14 — 종료 캡처 budget 및 filter-after-chain의 한계

captureCustom도 budget을 거친다. servlet chain 반환 시점과 비동기 생성 종료의 차이는 별도 서버 테스트가 필요하다.

### C: `main/java/com/example/lms/trace/TraceSnapshotStore.java:247–283`

```text
  247 |             // Sampling (non-critical only)
  248 |             boolean critical = hasException || statusTrigger || !"http_request".equalsIgnoreCase(safe(reason));
  249 |             if (!critical && captureSample < 1.0d) {
  250 |                 double r = Math.random();
  251 |                 if (r > Math.max(0.0d, Math.min(1.0d, captureSample))) {
  252 |                     traceCaptureSkipped(reason, "sampled_out");
  253 |                     return null;
  254 |                 }
  255 |             }
  256 | 
  257 |             String rawSid = firstNonBlank(rawMdc.get("sid"), rawMdc.get("sessionId"));
  258 |             String rawTraceId = firstNonBlank(
  259 |                     rawMdc.get("traceId"),
  260 |                     rawMdc.get("trace"),
  261 |                     rawMdc.get("x-request-id"),
  262 |                     firstString(rawTrace == null ? null : rawTrace.get("trace.id")),
  263 |                     firstString(rawTrace == null ? null : rawTrace.get("traceId"))
  264 |             );
  265 |             String rawRequestId = firstNonBlank(rawMdc.get("x-request-id"), rawTraceId);
  266 | 
  267 |             // Rate limit per trace id (helps prevent accidental hot loops).
  268 |             if (!consumeBudget(rawTraceId, ts)) {
  269 |                 traceCaptureSkipped(reason, "budget_exhausted");
  270 |                 return null;
  271 |             }
  272 | 
  273 |             Map<String, String> mdc = sanitizeMdc(rawMdc);
  274 |             Map<String, Object> trace = sanitizeTrace(rawTrace);
  275 |             Map<String, Object> orchestration = buildOrchestration(trace);
  276 | 
  277 |             String sid = SafeRedactor.hashValue(rawSid);
  278 |             String traceId = SafeRedactor.hashValue(rawTraceId);
  279 |             String requestId = SafeRedactor.hashValue(rawRequestId);
  280 |             String err = (error == null) ? null : String.valueOf(SafeRedactor.diagnosticValue("error", String.valueOf(error), 600));
  281 |             String snapshotReason = SafeRedactor.traceLabelOrFallback(reason, "");
  282 |             if (snapshotReason == null) snapshotReason = "";
  283 |             String snapshotPath = safeRequestPath(path);
```

### C: `main/java/com/example/lms/trace/TraceSnapshotFilter.java:27–55`

```text
   27 |     }
   28 | 
   29 |     @Override
   30 |     public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
   31 |             throws IOException, ServletException {
   32 |         try {
   33 |             chain.doFilter(request, response);
   34 |         } finally {
   35 |             exportAfterChain(request);
   36 |         }
   37 |     }
   38 | 
   39 |     private void exportAfterChain(ServletRequest request) {
   40 |         try {
   41 |             TraceSnapshotExporter exporter = exporterProvider == null ? null : exporterProvider.getIfAvailable();
   42 |             if (exporter == null || !(request instanceof HttpServletRequest httpRequest)) {
   43 |                 return;
   44 |             }
   45 |             exporter.exportCurrentTrace(
   46 |                     firstNonBlank(httpRequest.getHeader(REQUEST_ID_HEADER), TraceStore.getString("requestId")),
   47 |                     firstNonBlank(httpRequest.getHeader(SESSION_ID_HEADER), TraceStore.getString("sessionId"))
   48 |             );
   49 |         } catch (RuntimeException ignore) {
   50 |             TraceStore.put("trace.snapshot.filter.failed",
   51 |                     SafeRedactor.traceLabelOrFallback(ignore.getClass().getSimpleName(), "unknown"));
   52 |         }
   53 |     }
   54 | 
   55 |     private static String firstNonBlank(String first, String second) {
```

### C: `main/java/com/example/lms/api/ChatApiController.java:4530–4582`

```text
 4530 |             if (!syncRagControlProjection.held()) {
 4531 |                 clearLocalLlmOperatorActionAfterVisibleSuccess(extraMeta, persistableFinalContent, modelUsedFinal);
 4532 |             }
 4533 |             promoteDebugEvents("final", extraMeta, "ChatApiController.sync.final");
 4534 |             finalWebTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalWebTopK"));
 4535 |             finalVectorTopK = ChatTraceContentLists.nullableContentList(extraMeta.get("finalVectorTopK"));
 4536 | 
 4537 |             // Console diagnostics: dump search trace + planner meta without exposing it to the client
 4538 |             try {
 4539 |                 searchTraceConsoleLogger.maybeLog("sync", (__srFinal == null ? null : __srFinal.trace()), (__srFinal == null ? null : __srFinal.snippets()), finalWebTopK, finalVectorTopK, extraMeta);
 4540 |             } catch (Exception ignoreLog) {
 4541 |                 logSuppressed("sync.searchTraceConsole");
 4542 |             }
 4543 |         } catch (Exception ignore) {
 4544 |             logSuppressed("sync.finalTraceMeta");
 4545 |         } finally {
 4546 |             try {
 4547 |                 TraceStore.clear();
 4548 |             } catch (Exception ignore2) {
 4549 |                 logSuppressed("sync.finalTraceMeta.clear");
 4550 |             }}
 4551 | 
 4552 |         String traceHtmlForSnapshot = null;
 4553 |         if (__srFinal != null) {
 4554 |             String traceHtml = "";
 4555 |             try {
 4556 |                 java.util.List<String> rawSnips = (__srFinal.snippets() == null)
 4557 |                         ? java.util.Collections.emptyList()
 4558 |                         : __srFinal.snippets();
 4559 |                 traceHtml = traceHtmlBuilder.buildSplitPanel(__srFinal.trace(), rawSnips,
 4560 |                         finalWebTopK, finalVectorTopK, extraMeta, performSearch);
 4561 |             } catch (Exception ignore) {
 4562 |                 traceHtml = "";
 4563 |                 logSuppressed("sync.traceHtml.final");
 4564 |             }
 4565 |             if (traceHtml != null && !traceHtml.isBlank()) {
 4566 |                 traceHtmlForSnapshot = traceHtml;
 4567 |             }
 4568 |         }
 4569 | 
 4570 |         Long traceTurnId = ChatTraceSnapshotPointerPersister.persist(
 4571 |                 completedSession.getId(),
 4572 |                 assistantMessageId,
 4573 |                 "chat.trace_html.final",
 4574 |                 "POST",
 4575 |                 "/api/chat",
 4576 |                 extraMeta,
 4577 |                 traceHtmlForSnapshot,
 4578 |                 traceSnapshotStore,
 4579 |                 historyService,
 4580 |                 log);
 4581 | 
 4582 |         // Persist answer.mode + traceTurnId snapshot for cross-device badges and deterministic trace open.
```

### C: `main/java/com/example/lms/api/ChatApiController.java:3540–3570`

```text
 3540 |     private static java.util.Map<String, Object> mergeStreamTraceMeta(
 3541 |             java.util.Map<String, Object> stashed) {
 3542 |         java.util.Map<String, Object> live = TraceStore.getAll();
 3543 |         if (stashed == null || stashed.isEmpty()) {
 3544 |             return live;
 3545 |         }
 3546 |         java.util.Map<String, Object> merged = new java.util.LinkedHashMap<>(stashed);
 3547 |         if (live != null) {
 3548 |             merged.putAll(live);
 3549 |         }
 3550 |         return merged;
 3551 |     }
 3552 | 
 3553 |     private void emitChatSessionTrace(AtomicBoolean once, String surface, Long sessionId,
 3554 |             ChatRunExecutionContext run, String requestedModel, String effectiveModel,
 3555 |             Boolean ragEnabled, String outcome, java.util.Map<String, Object> meta) {
 3556 |         try {
 3557 |             if (chatSessionTraceRecorder == null || once == null || !once.compareAndSet(false, true)) {
 3558 |                 return;
 3559 |             }
 3560 |             chatSessionTraceRecorder.recordTerminal(surface, sessionId,
 3561 |                     run == null ? null : run.clientToken(), requestedModel, effectiveModel,
 3562 |                     ragEnabled, outcome, meta);
 3563 |         } catch (Throwable ignore) {
 3564 |             logSuppressed("chat.sessionTrace.emit");
 3565 |         }
 3566 |     }
 3567 | 
 3568 |     private static boolean isVisibleLocalModelSuccess(String answer, String modelUsed) {
 3569 |         if (answer == null || answer.isBlank() || modelUsed == null || modelUsed.isBlank()) {
 3570 |             return false;
```

## E15 — 검토 중 반증한 웹 OFF 동기 경로 결함 가설

OFF 분기에서도 빈 SearchResult가 생성된다. 따라서 __srFinal != null만 보고 RAG-only 상세가 무조건 빠진다고 주장하지 않는다.

### C: `main/java/com/example/lms/api/ChatApiController.java:4260–4276`

```text
 4260 |         final com.example.lms.gptsearch.dto.SearchMode effectiveSearchMode =
 4261 |                 effectiveSearchMode(dto.getMessage(), sm);
 4262 |         boolean performSearch = shouldUseWebForSearchMode(
 4263 |                 dto.getMessage(), effectiveSearchMode, __finalUseWeb, __finalUseRag, searchDecisionService, topKParam);
 4264 |         GuardContext __preSearchCtx = GuardContextHolder.get();
 4265 |         markCheapSearchMode(__preSearchCtx, effectiveSearchMode, "sync.preSearch");
 4266 |         final String __providerSearchQuery = providerSearchQuery(dto.getMessage());
 4267 |         if (performSearch) {
 4268 |             recordSearchModeRewriteHint(effectiveSearchMode);
 4269 |         }
 4270 |         NaverSearchService.SearchResult sr = performSearch
 4271 |                 ? webSearchProvider.searchWithTrace(__providerSearchQuery, topKParam)
 4272 |                 : new NaverSearchService.SearchResult(List.of(), null);
 4273 |         if (performSearch && sr != null) {
 4274 |             List<String> rawSnips = prioritizeDomainEvidenceSnippets(dto.getMessage(), sr.snippets());
 4275 |             rawSnips = completeNamedOfficialCoverageSnippets(
 4276 |                     dto.getMessage(),
```

### C: `main/java/com/example/lms/api/ChatApiController.java:4552–4568`

```text
 4552 |         String traceHtmlForSnapshot = null;
 4553 |         if (__srFinal != null) {
 4554 |             String traceHtml = "";
 4555 |             try {
 4556 |                 java.util.List<String> rawSnips = (__srFinal.snippets() == null)
 4557 |                         ? java.util.Collections.emptyList()
 4558 |                         : __srFinal.snippets();
 4559 |                 traceHtml = traceHtmlBuilder.buildSplitPanel(__srFinal.trace(), rawSnips,
 4560 |                         finalWebTopK, finalVectorTopK, extraMeta, performSearch);
 4561 |             } catch (Exception ignore) {
 4562 |                 traceHtml = "";
 4563 |                 logSuppressed("sync.traceHtml.final");
 4564 |             }
 4565 |             if (traceHtml != null && !traceHtml.isBlank()) {
 4566 |                 traceHtmlForSnapshot = traceHtml;
 4567 |             }
 4568 |         }
```

## E16 — 기존 OrchTrace·OTel와 오래된 선언-only helper

기존 표준 이벤트 seq와 OTel bridge를 보존한다. 구형 helper 두 개의 파일 내 이름 출현은 각각 1회로 선언뿐이었다.

### C: `main/java/ai/abandonware/nova/orch/trace/OrchTrace.java:18–42`

```text
   18 |  * TraceStore-backed event log helper with deterministic sequencing.
   19 |  *
   20 |  * <p>Stores per-trace AtomicLong under TraceStore shared map so cross-thread append becomes sortable.</p>
   21 |  */
   22 | public final class OrchTrace {
   23 | 
   24 |     /** Versioned orchestration event list key. */
   25 |     public static final String TRACE_KEY_EVENTS_V1 = "orch.events.v1";
   26 | 
   27 |     private static final String SEQ_KEY_PREFIX = "__seq.orch.";
   28 | 
   29 |     private OrchTrace() {
   30 |     }
   31 | 
   32 |     public static long nextSeq(String scope) {
   33 |         String k = SEQ_KEY_PREFIX + (scope == null ? "default" : scope);
   34 |         Object existing = TraceStore.get(k);
   35 |         if (existing instanceof AtomicLong al) {
   36 |             return al.incrementAndGet();
   37 |         }
   38 |         AtomicLong created = new AtomicLong(0L);
   39 |         Object prev = TraceStore.putIfAbsent(k, created);
   40 |         AtomicLong al = (prev instanceof AtomicLong p) ? p : created;
   41 |         return al.incrementAndGet();
   42 |     }
```

### C: `main/java/ai/abandonware/nova/orch/trace/OrchTrace.java:125–143`

```text
  125 | 
  126 |         Object kind = ev.get("kind");
  127 |         if (kind != null) {
  128 |             TraceStore.put("orch.events.v1.last." + kind, ev);
  129 |         }
  130 |         MlaOtelBridge.emit(ev);
  131 |     }
  132 | 
  133 |     @Nullable
  134 |     private static String firstNonBlank(@Nullable String... values) {
  135 |         if (values == null) {
  136 |             return null;
  137 |         }
  138 |         for (String value : values) {
  139 |             if (value != null && !value.isBlank()) {
  140 |                 return value;
  141 |             }
  142 |         }
  143 |         return null;
```

### C: `main/java/ai/abandonware/nova/orch/trace/MlaOtelBridge.java:16–40`

```text
   16 |     private static final String ENABLED_PROPERTY = "otel.mla.bridge.enabled";
   17 |     private static final String ENABLED_ENV = "OTEL_MLA_BRIDGE_ENABLED";
   18 |     private static volatile Boolean configuredEnabled = false;
   19 | 
   20 |     private MlaOtelBridge() {
   21 |     }
   22 | 
   23 |     public static void emit(Map<String, Object> event) {
   24 |         if (!enabled() || event == null || event.isEmpty()) {
   25 |             return;
   26 |         }
   27 |         try {
   28 |             Tracer tracer = GlobalOpenTelemetry.get().getTracer("demo1-mla");
   29 |             Span span = tracer.spanBuilder(spanName(event)).startSpan();
   30 |             try {
   31 |                 applyAttributes(span, attributes(event));
   32 |                 if ("error".equalsIgnoreCase(String.valueOf(event.get("status")))) {
   33 |                     span.setStatus(StatusCode.ERROR);
   34 |                 }
   35 |             } finally {
   36 |                 span.end();
   37 |             }
   38 |         } catch (Throwable e) {
   39 |             traceSuppressed("emit", e);
   40 |             // Telemetry must never affect orchestration.
```

### C: `main/java/ai/abandonware/nova/orch/trace/MlaOtelBridge.java:58–80`

```text
   58 |     }
   59 | 
   60 |     static Map<String, Object> attributes(Map<String, Object> event) {
   61 |         Map<String, Object> attrs = new LinkedHashMap<>();
   62 |         putString(attrs, "mla.phase", event.get("phase"));
   63 |         putString(attrs, "mla.step", first(event.get("step"), event.get("stage")));
   64 |         putString(attrs, "mla.component", event.get("component"));
   65 |         putString(attrs, "mla.status", event.get("status"));
   66 |         attrs.put("gen_ai.operation.name", operationName(event));
   67 |         attrs.put("rag.retriever.kind", retrieverKind(event));
   68 |         putHash(attrs, "session.id.hash", first(first(event.get("sessionId"), event.get("sid")), event.get("sessionIdHash")));
   69 |         putHash(attrs, "request.id.hash", first(event.get("requestId"), event.get("requestIdHash")));
   70 |         putHash(attrs, "trace.id.hash", first(event.get("traceId"), event.get("traceIdHash")));
   71 |         putHash(attrs, "mla.anchor.hash", first(first(field(event, "control", "anchorHash"), field(event, "output", "anchorHash")),
   72 |                 field(event, "traceAnchor", "anchorHash")));
   73 |         putLong(attrs, "rag.matrix.tile", first(first(field(event, "control", "matrixTile"), field(event, "output", "matrixTile")),
   74 |                 field(event, "traceAnchor", "matrixTile")));
   75 |         putDouble(attrs, "rag.ablation.drop", first(first(field(event, "control", "ablationDrop"), field(event, "output", "ablationDrop")),
   76 |                 field(event, "traceAnchor", "expectedDelta")));
   77 |         putString(attrs, "rag.route.hint", first(first(field(event, "control", "routeHint"), field(event, "output", "routeHint")),
   78 |                 field(event, "traceAnchor", "routeHint")));
   79 |         emitCounts(attrs, event.get("output"));
   80 |         return attrs;
```

### L: `src/main/java/com/example/lms/service/trace/TraceHtmlBuilder.java:3161–3177`

```text
 3161 |     private String renderAblationPanel(Map<String, Object> extraMeta) {
 3162 |         if (extraMeta == null || extraMeta.isEmpty())
 3163 |             return "";
 3164 |         boolean has = extraMeta.containsKey("ablation.score")
 3165 |                 || extraMeta.containsKey("ablation.probabilities")
 3166 |                 || extraMeta.containsKey("ablation.top")
 3167 |                 || extraMeta.containsKey("ablation.byGuard")
 3168 |                 || extraMeta.containsKey("ablation.byStep");
 3169 |         if (!has)
 3170 |             return "";
 3171 | 
 3172 |         StringBuilder sb = new StringBuilder();
 3173 |         sb.append("<div class='trace-section'>");
 3174 |         sb.append("<h3>D) Ablation (why degraded?)</h3>");
 3175 | 
 3176 |         sb.append("<div class='trace-callout'>");
 3177 |         sb.append("<b>score</b>: ").append(escape(String.valueOf(extraMeta.getOrDefault("ablation.score", ""))));
```

### L: `src/main/java/com/example/lms/service/trace/TraceHtmlBuilder.java:3245–3265`

```text
 3245 |     private String renderDebugCopilotPanel(Map<String, Object> extraMeta) {
 3246 |         if (extraMeta == null || extraMeta.isEmpty())
 3247 |             return "";
 3248 |         boolean has = extraMeta.containsKey("dbg.copilot.summary")
 3249 |                 || extraMeta.containsKey("dbg.copilot.actions")
 3250 |                 || extraMeta.containsKey("dbg.copilot.causes");
 3251 |         if (!has)
 3252 |             return "";
 3253 | 
 3254 |         StringBuilder sb = new StringBuilder();
 3255 |         sb.append("<div class='trace-section'>");
 3256 |         sb.append("<h3>E) Debug Copilot</h3>");
 3257 | 
 3258 |         Object ok = extraMeta.get("dbg.copilot.ok");
 3259 |         Object summary = extraMeta.get("dbg.copilot.summary");
 3260 |         Object actions = extraMeta.get("dbg.copilot.actions");
 3261 |         Object causes = extraMeta.get("dbg.copilot.causes");
 3262 |         Object traceId = extraMeta.get("dbg.copilot.traceId");
 3263 |         Object sid = extraMeta.get("dbg.copilot.sid");
 3264 | 
 3265 |         sb.append("<div class='trace-callout'>");
```

파일 내 함수명 출현 횟수: {'renderAblationPanel': 1, 'renderDebugCopilotPanel': 1}

## E17 — 인증 경계와 보조 route 주의사항

현재 diagnostics의 ADMIN 보호와 각 보조 controller의 자체 gate는 구분한다. 보조 클래스의 실제 활성/노출 여부는 bean 등록·filter chain 서버 검증 전 단정하지 않는다.

### C: `main/java/com/example/lms/config/AppSecurityConfig.java:164–179`

```text
  164 |                         .requestMatchers("/admin", "/admin/**").hasRole("ADMIN")
  165 |                         .requestMatchers("/api/admin/fine-tuning", "/api/admin/fine-tuning/**").hasRole("ADMIN")
  166 |                         .requestMatchers("/api/internal/**").hasRole("ADMIN")
  167 |                         .requestMatchers("/api/learning/gemini", "/api/learning/gemini/**").hasRole("ADMIN")
  168 |                         .requestMatchers("/api/integrations/check").hasRole("ADMIN")
  169 |                         .requestMatchers("/v1/tasks", "/v1/tasks/**").hasRole("ADMIN")
  170 |                         .requestMatchers(HttpMethod.POST, "/api/rag/probe").hasRole("ADMIN")
  171 |                         .requestMatchers(HttpMethod.POST, "/api/nova/outbox/**").hasRole("ADMIN")
  172 |                         .requestMatchers(HttpMethod.POST, "/api/train", "/api/train/**").hasRole("ADMIN")
  173 |                         .requestMatchers(HttpMethod.POST, "/api/translate/train", "/api/translate/train-now").hasRole("ADMIN")
  174 |                         .requestMatchers(HttpMethod.POST, "/webhooks/channel").hasRole("ADMIN")
  175 |                         .requestMatchers(HttpMethod.POST, "/messages/trigger").hasRole("ADMIN")
  176 |                         .requestMatchers("/api/diagnostics/debug/triadic-adjudication").hasRole("ADMIN")
  177 |                         .requestMatchers(HttpMethod.POST, "/api/diagnostics/**").hasRole("ADMIN")
  178 |                         .requestMatchers(HttpMethod.GET, "/api/diagnostics/**").hasRole("ADMIN")
  179 |                         .requestMatchers(HttpMethod.POST, "/internal/dataset/**").permitAll()
```

### C: `main/java/com/example/lms/config/AppSecurityConfig.java:198–204`

```text
  198 |                                 "/api/chat/**",
  199 |                                 "/api/chat-extra/**",
  200 |                                 "/api/rag/**",
  201 |                                 "/hooks/n8n/**"
  202 |                         ).permitAll()
  203 |                         .anyRequest().authenticated()
  204 |                 )
```

### C: `main/java/com/abandonware/ai/agent/web/AgentTraceController.java:16–31`

```text
   16 | 
   17 | @RestController
   18 | @RequestMapping("/trace")
   19 | public class AgentTraceController {
   20 |     private final Sinks.Many<Map<String,Object>> sink = Sinks.many().multicast().onBackpressureBuffer();
   21 | 
   22 |     @GetMapping(value="/events", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
   23 |     public Flux<ServerSentEvent<Map<String,Object>>> stream(){
   24 |         return sink.asFlux().map(ev -> ServerSentEvent.<Map<String,Object>>builder(ev).build());
   25 |     }
   26 | 
   27 |     public void publish(Map<String,Object> ev){
   28 |         sink.tryEmitNext(sanitize(ev));
   29 |     }
   30 | 
   31 |     private static Map<String, Object> sanitize(Map<String, Object> ev) {
```

### C: `main/java/com/abandonware/ai/telemetry/OpsSseController.java:12–31`

```text
   12 | 
   13 | @RestController
   14 | @RequestMapping("/internal/stream")
   15 | public class OpsSseController {
   16 | 
   17 |     private final SseEventPublisher publisher;
   18 | 
   19 |     public OpsSseController(SseEventPublisher publisher) {
   20 |         this.publisher = publisher;
   21 |     }
   22 | 
   23 |     @GetMapping(value="/ops", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
   24 |     public Flux<Map<String,Object>> stream(@RequestHeader(name="X-Token", required=false) String token) {
   25 |         // naive token gate
   26 |         if (token == null || token.isBlank()) {
   27 |             return Flux.error(new IllegalArgumentException("token required"));
   28 |         }
   29 |         return publisher.asStream();
   30 |     }
   31 | }
```

### C: `main/java/com/example/lms/api/AblationDebugController.java:35–59`

```text
   35 |     private final String adminToken;
   36 | 
   37 |     public AblationDebugController(
   38 |             Environment env,
   39 |             @Value("${probe.orch.enabled:false}") boolean enabled,
   40 |             @Value("${probe.admin-token:}") String adminToken
   41 |     ) {
   42 |         this.env = env;
   43 |         this.adminToken = adminToken;
   44 |         if (enabled && ConfigValueGuards.isMissing(adminToken)) {
   45 |             this.enabled = false;
   46 |             log.warn("[ProviderGuard] PROBE_TOKEN missing -> /api/debug/ablation disabled{}", LogCorrelation.suffix());
   47 |         } else {
   48 |             this.enabled = enabled;
   49 |         }
   50 |     }
   51 | 
   52 |     @GetMapping("/ablation")
   53 |     public ResponseEntity<?> ablation(@RequestHeader(value = "X-Probe-Token", required = false) String token) {
   54 |         if (!enabled) {
   55 |             return ResponseEntity.status(404).body(err("PROBE_DISABLED"));
   56 |         }
   57 |         if (!tokenMatches(adminToken, token)) {
   58 |             return ResponseEntity.status(401).body(err("UNAUTHORIZED"));
   59 |         }
```

### C: `main/java/com/example/lms/api/ChatApiController.java:4990–5024`

```text
 4990 |             ChatSession session) {
 4991 |         String username = authentication != null && authentication.isAuthenticated()
 4992 |                 ? authentication.getName()
 4993 |                 : null;
 4994 |         boolean isAdmin = isAdmin(authentication);
 4995 | 
 4996 |         if (session == null) {
 4997 |             if (restoreProbe) {
 4998 |                 return restoreProbeReset("SESSION_UNAVAILABLE");
 4999 |             }
 5000 |             return ResponseEntity.status(HttpStatus.NOT_FOUND)
 5001 |                     .body(Map.of(
 5002 |                             "action", "RESET_SESSION",
 5003 |                             "message", SESSION_EXPIRED_MESSAGE,
 5004 |                             "error", "SESSION_NOT_FOUND"));
 5005 |         }
 5006 | 
 5007 |         var owner = session.getAdministrator();
 5008 |         boolean traceOwner = owner == null
 5009 |                 ? session.getOwnerKey() != null && session.getOwnerKey().equals(ownerKeyResolver.ownerKey())
 5010 |                 : username != null && owner.getUsername().equals(username);
 5011 |         if (!isAdmin && !traceOwner) {
 5012 |             if (restoreProbe) {
 5013 |                 return restoreProbeReset("SESSION_UNAVAILABLE");
 5014 |             }
 5015 |             return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
 5016 |         }
 5017 | 
 5018 |         return ChatSessionDetailResponseBuilder.build(
 5019 |                 session,
 5020 |                 username,
 5021 |                 objectMapper,
 5022 |                 settingsService.getAllSettings(),
 5023 |                 isAdmin && traceOwner && (debug || exposeTrace),
 5024 |                 log);
```

## E18 — 생성되는 세부 키와 선택적 화면 그룹의 간극

현재 chat orchestration HTML은 선택된 prefix와 개별 키를 표시한다. qtx.stagePolicy 및 keywordSelection.mode, dbg.copilot 원인 목록의 생성과 전용 상세 매핑은 구분해야 한다. snapshot JSON 등의 다른 표면에 값이 남을 수 있으므로 전체 기능 소실이라고 단정하지 않는다.

### C: `main/java/com/example/lms/transform/QueryTransformer.java:988–1000`

```text
  988 |         // MERGE_HOOK:PROJ_AGENT::STAGE_POLICY_CLAMP_QUERY_TRANSFORMER_V2
  989 |         if (!disabled && !breakerOpen) {
  990 |             try {
  991 |                 if (stagePolicy != null && stagePolicy.isEnabled()) {
  992 |                     boolean enabled = stagePolicy.isStageEnabled(OrchStageKeys.QUERY_TRANSFORMER, modeLabel, true);
  993 |                     TraceStore.put("qtx.stagePolicy.enabled", enabled);
  994 |                     if (!enabled) {
  995 |                         stagePolicyClamped = true;
  996 |                         TraceStore.put("qtx.stagePolicy.clamped", true);
  997 |                     }
  998 |                 }
  999 |             } catch (Throwable ignore) {
 1000 |                 TraceStore.put("qtx.suppressed.stagePolicyTrace", true);
```

### C: `main/java/com/example/lms/search/KeywordSelectionService.java:652–675`

```text
  652 |                 // Blank output is a *cheap* degradation signal (not a hard block).
  653 |                 // Record degraded KPI so dashboards can distinguish "blocked" vs "degraded".
  654 |                 try {
  655 |                     AuxDownTracker.markSoft("keyword-selection", "blank");
  656 |                 } catch (Throwable suppressed) {
  657 |                     traceSuppressed("blank.auxSoft", suppressed);
  658 |                 }
  659 |                 try {
  660 |                     TraceStore.put("keywordSelection.mode", "fallback_blank");
  661 |                     TraceStore.putIfAbsent("aux.keywordSelection", "degraded:blank");
  662 |                     TraceStore.put("aux.keywordSelection.degraded", Boolean.TRUE);
  663 |                     TraceStore.put("aux.keywordSelection.degraded.reason", "blank");
  664 |                     TraceStore.inc("aux.keywordSelection.degraded.count");
  665 |                 } catch (Throwable suppressed) {
  666 |                     traceSuppressed("blank.degradedTrace", suppressed);
  667 |                 }
  668 |                 traceRule("keywordSelection.mode=fallback_blank degraded=true reason=blank");
  669 |                 if (irregularityProfiler != null) {
  670 |                     irregularityProfiler.bump(GuardContextHolder.getOrDefault(), 0.15, "keyword_blank");
  671 |                 }
  672 |                 if (cached != null) {
  673 |                     try {
  674 |                         TraceStore.put("keywordSelection.mode", "cache_rescue_blank");
  675 |                     } catch (Throwable suppressed) {
```

### C: `main/java/com/example/lms/service/trace/DebugCopilotService.java:476–521`

```text
  476 |             // Final ranking
  477 |             List<Cause> ranked = rankTop(causes, MAX_CAUSES);
  478 | 
  479 |             boolean ok = ranked.isEmpty();
  480 |             TraceStore.put("dbg.copilot.ok", ok);
  481 | 
  482 |             // Always provide correlation hints (best-effort)
  483 |             if (traceId != null) TraceStore.put("dbg.copilot.traceId", SafeRedactor.hashValue(traceId));
  484 |             if (sid != null) TraceStore.put("dbg.copilot.sid", SafeRedactor.hashValue(sid));
  485 | 
  486 |             if (ok) {
  487 |                 TraceStore.put("dbg.copilot.summary",
  488 |                         "No obvious degradation signals detected (based on TraceStore breadcrumbs)."
  489 |                                 + " (Try checking embedding provider health / vectorstore upserts / orchestration strike/bypass.)");
  490 |                 TraceStore.put("dbg.copilot.actions", List.of(
  491 |                         "# If output still looks off, grep by trace id",
  492 |                         cmdGrepTrace(traceId, ""),
  493 |                         "# Then inspect: orch.*, qtx.*, aux.*, embed.*, vector.* keys"
  494 |                 ));
  495 |                 return;
  496 |             }
  497 | 
  498 |             // Structured causes for Trace UI
  499 |             List<Map<String, Object>> out = new ArrayList<>();
  500 |             int rank = 1;
  501 |             for (Cause c : ranked) {
  502 |                 Map<String, Object> row = new LinkedHashMap<>();
  503 |                 row.put("rank", rank++);
  504 |                 row.put("id", c.id);
  505 |                 row.put("score", round3(c.score));
  506 |                 row.put("title", c.title);
  507 |                 if (!c.evidence.isEmpty()) row.put("evidence", c.evidence);
  508 |                 if (!c.commands.isEmpty()) row.put("commands", c.commands);
  509 |                 out.add(row);
  510 |             }
  511 |             TraceStore.put("dbg.copilot.causes", out);
  512 | 
  513 |             // Short summary (top 1~3)
  514 |             StringBuilder sum = new StringBuilder();
  515 |             sum.append("Top causes: ");
  516 |             for (int i = 0; i < ranked.size(); i++) {
  517 |                 if (i > 0) sum.append(" · ");
  518 |                 sum.append(i + 1).append(") ").append(ranked.get(i).title);
  519 |             }
  520 |             TraceStore.put("dbg.copilot.summary", clip(sum.toString(), 900));
  521 | 
```

### C: `main/java/com/example/lms/service/trace/TraceHtmlBuilder.java:806–842`

```text
  806 |         appendKvPrefixGroup(sb, extraMeta, shown, "Plan", "plan.", 24);
  807 |         appendKvPrefixGroup(sb, extraMeta, shown, "QueryPlanner", "queryPlanner.", 24);
  808 |         appendKvPrefixGroup(sb, extraMeta, shown, "NoiseGate", "orch.noiseGate.", 24);
  809 | 
  810 |         // Structured prompt events (table) for "click-to-trace" UX.
  811 |         if (extraMeta.containsKey("prompt.events")) {
  812 |             shown.add("prompt.events"); // handled by appendPromptEvents()
  813 |         }
  814 |         appendPromptEvents(sb, extraMeta, shown);
  815 | 
  816 |         appendKvPrefixGroup(sb, extraMeta, shown, "Prompt", "prompt.", 24);
  817 | 
  818 |         // LLM endpoint/model routing + model-guard (OpenAI chat vs responses mismatch)
  819 |         // breadcrumbs
  820 |         appendKvPrefixGroup(sb, extraMeta, shown, "LLM", "llm.", 24);
  821 | 
  822 |         // Structured model routing events (table) for "click-to-trace" UX.
  823 |         if (extraMeta.containsKey("ml.router.events")) {
  824 |             shown.add("ml.router.events"); // handled by appendMlRouterEvents()
  825 |         }
  826 |         appendMlRouterEvents(sb, extraMeta, shown);
  827 | 
  828 |         // Merge-boundary / stage-handoff breadcrumbs (safe, compact)
  829 |         appendKvPrefixGroup(sb, extraMeta, shown, "Stage Boundary", "stageBoundary.", 24);
  830 |         appendKvPrefixGroup(sb, extraMeta, shown, "MLA Breadcrumb Step", "mla.breadcrumb.step.", 24);
  831 |         appendKvPrefixGroup(sb, extraMeta, shown, "ML", "ml.", 24);
  832 |         appendKvPrefixGroup(sb, extraMeta, shown, "Embedding", "embed.", 24);
  833 | 
  834 |         // Custom (human-friendly) debug UX for web.* fields
  835 |         TraceHtmlWebSelectedTermsRenderer.append(sb, extraMeta, shown);
  836 |         appendWebNaverPlanHintBoostOnlyOverlay(sb, extraMeta, shown);
  837 |         TraceHtmlWebAwaitEventsRenderer.append(sb, extraMeta, shown);
  838 |         TraceHtmlWebFailSoftRunsRenderer.append(sb, extraMeta, shown);
  839 | 
  840 |         appendKvPrefixGroup(sb, extraMeta, shown, "Web", "web.", 40);
  841 |         sb.append("</table></details></div>");
  842 |         return sb.toString();
```

## 범위 제한 및 재검증 주의

정적 코드에서 가능한 문제를 찾은 항목은 실제 서버에서 같은 조건을 재현해야 한다. sourceRoot의 이후 수정으로 줄 번호가 달라질 수 있으므로 SHA-256과 메서드 이름을 함께 확인한다. 테스트 파일의 PASS는 이번 보고서의 재현 assertion 통과를 뜻하며 프로젝트 수정·전체 suite 통과를 뜻하지 않는다. 정제된 값이 잘 보이는 것만으로 RAG 검색 품질·세션 기억·모델 전달이 모두 정상이라는 결론을 내리지 않는다.
