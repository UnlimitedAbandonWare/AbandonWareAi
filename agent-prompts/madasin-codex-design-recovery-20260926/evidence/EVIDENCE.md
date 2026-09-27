# 소스 근거 인덱스 — madasin / src111_mergex15

기준일: 2026-09-26 (Asia/Seoul). current 경로는 madasin.zip의 루트 기준, legacy 경로는 src111_mergex15.zip의 루트 기준입니다. 아래는 분석에 사용한 실제 소스 발췌이며 런타임 실행 증명과 구분합니다.

전 파일 목록·해시·개행 차이·구조 탐침은 `source_inventory.json`, 브라우저 재현은 `browser_probe_results.json`, JS 구문 검사는 `js_syntax_results.json`, 독립 Java 계약 검사는 `plan_contract_probe.txt`를 참조하십시오.

## E01 — 현재 실행 경계와 자동설정 등록
`current:main/java/com/example/lms/LmsApplication.java`
SHA-256: `f4d766a7943d690222889614c8baa59fbc8d081651a8a7aa28a06590d8a1036f`

행 18–31:
```text
   18 | import org.slf4j.LoggerFactory;
   19 | 
   20 | @SpringBootApplication(scanBasePackages = {"com.example.lms", "com.nova.protocol"})
   21 | @EnableConfigurationProperties({LlmRouterProperties.class})
   22 | @ConfigurationPropertiesScan
   23 | @EnableScheduling
   24 | @EnableAsync
   25 | @Import(SubagentFlowRuntimeConfiguration.class)
   26 | public class LmsApplication {
   27 |     private static final Logger log = LoggerFactory.getLogger(LmsApplication.class);
   28 | 
   29 |     public static void main(String[] args) {
   30 |         SpringApplication.run(LmsApplication.class, args);
   31 |     }
```

## E02 — 현재 자동설정 imports: 과거 누락 지시를 재적용하지 말 것
`current:main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
SHA-256: `5c647cb81692df339ef336f4acfc5dcc4e169d879278616ebaf9973fad22d241`

행 1–6:
```text
    1 | ai.abandonware.nova.autoconfig.NovaDebugPortAutoConfiguration
    2 | ai.abandonware.nova.autoconfig.NovaFailurePatternAutoConfiguration
    3 | ai.abandonware.nova.autoconfig.NovaOrchestrationAutoConfiguration
    4 | ai.abandonware.nova.autoconfig.NovaOpsStabilizationAutoConfiguration
    5 | ai.abandonware.nova.autoconfig.NovaZero100AutoConfiguration
    6 | com.example.lms.agent.context.AgentDbContextAutoConfiguration
```

## E03 — UI는 Thymeleaf 실행이 아니라 Jsoup 명시적 projection
`current:main/java/com/example/lms/config/ChatUiViewConfig.java`
SHA-256: `de24b01cc1a0c39e70f16f53fb942a14dead22a722ef40014f32b5bccd1ce7db`

행 98–138:
```text
   98 |                 Map<String, ?> model,
   99 |                 HttpServletRequest request) {
  100 |             // This application deliberately uses classpath views (no Thymeleaf runtime).
  101 |             // Project the few server-owned values through an HTML parser, not regex conditions.
  102 |             var document = Jsoup.parse(html);
  103 |             document.outputSettings().prettyPrint(false);
  104 |             Object requestBudget = model.get("chatRequestBudgetMs");
  105 |             if (requestBudget instanceof Number budget && budget.longValue() > 0) {
  106 |                 document.select("meta[name=chat-request-budget-ms]")
  107 |                         .attr("content", Long.toString(budget.longValue()));
  108 |             }
  109 |             String surface = "compact".equals(model.get("chatSurface")) ? "compact" : "web";
  110 |             document.body().attr("data-chat-surface", surface);
  111 |             if (!Boolean.TRUE.equals(model.get("chatDiagnosticsEnabled"))) {
  112 |                 document.select("[data-admin-diagnostics]").remove();
  113 |             }
  114 |             if ("compact".equals(surface)) document.select(".conversation-sidebar").remove();
  115 |             String selected = currentModel(model);
  116 |             document.select("[data-current-model], #modelStatus").forEach(element -> element.text(selected));
  117 |             Element select = document.getElementById("modelSelect");
  118 |             if (select != null) {
  119 |                 select.empty();
  120 |                 List<String> ids = modelIds(model.get("models"));
  121 |                 if (ids.isEmpty()) ids.add(selected);
  122 |                 for (String id : ids) {
  123 |                     Element option = select.appendElement("option").attr("value", id).text(id);
  124 |                     if (id.equals(selected)) option.attr("selected", "");
  125 |                 }
  126 |             }
  127 |             // No inert template expressions may survive the explicit projection.
  128 |             for (Element element : document.getAllElements()) {
  129 |                 for (var attribute : new ArrayList<>(element.attributes().asList())) {
  130 |                     if (attribute.getKey().startsWith("th:") || attribute.getKey().equals("xmlns:th")) {
  131 |                         element.removeAttr(attribute.getKey());
  132 |                     }
  133 |                 }
  134 |             }
  135 |             return renderCsrfMeta(document.outerHtml(), model, request);
  136 |         }
  137 | 
  138 |         private static String renderLoginHtml(String html,
```

## E04 — 관리자 표시와 기본 unchecked trace 토글
`current:main/resources/templates/chat-ui.html`
SHA-256: `df919f7e3c5124169dc4d96a35fcdae6937e4ee4af13fce75843ee7d2c05bec1`

행 54–64:
```text
   54 |             <details class="answer-tools">
   55 |                 <summary>이번 답변 진단 <span aria-hidden="true">↗</span></summary>
   56 |                 <div class="answer-tools-panel">
   57 |                     <p class="panel-heading">이번 답변의 진행 상태</p>
   58 | <details class="diagnostics-disclosure" data-testid="chat-diagnostics" data-admin-diagnostics th:if="${chatDiagnosticsEnabled}">
   59 |             <summary id="diagnosticsSummaryControl" aria-label="Diagnostics: Checking signals"><span>Diagnostics</span><span id="diagnosticsSummary" data-status="WARN">Checking signals</span></summary>
   60 |             <div class="diagnostics-stack">
   61 |                 <label class="chat-trace-toggle"><input type="checkbox" data-chat-trace-toggle> 이 답변 디버깅 트레이스 표시</label>
   62 |                 <section aria-label="Runtime toolkit">
   63 |                     <div id="runtimeToolkitStatus" role="status" data-status="WARN">런타임 검증 대기</div>
   64 |                     <pre id="runtimeToolkitServices" style="white-space:pre-wrap;overflow-wrap:anywhere">현재 런타임 증거가 없습니다.</pre>
```

행 273–280:
```text
  273 |                                 th:text="${model.modelId}"
  274 |                                 th:selected="${model.modelId == currentModel}">model</option>
  275 |                     </select>
  276 |                 
  277 |                 </label>
  278 |                 <button id="modelBrowserTrigger" class="model-browser-trigger" type="button" aria-haspopup="dialog" aria-controls="modelBrowser">모델 탐색 ⌄</button>
  279 |                 <label><span>모델 사용 방식</span>
  280 |                     <select id="modelSelectionMode" aria-label="모델 사용 방식">
```

행 303–314:
```text
  303 |                 <label><span>검색</span>
  304 |                     <select id="searchModeSelect" name="searchMode" aria-label="Search" data-testid="chat-search-mode-select">
  305 |                         <option value="AUTO">자동 검색</option>
  306 |                         <option value="OFF" selected>검색 끔</option>
  307 |                         <option value="FORCE_LIGHT">빠른 검색</option>
  308 |                         <option value="FORCE_DEEP">깊이 검색</option>
  309 |                     </select>
  310 |                 </label>
  311 |                 <label class="toggle-row"><input id="useRagToggle" type="checkbox" aria-label="Use RAG context" data-testid="chat-rag-toggle"><span>RAG</span></label>
  312 |             </div>
  313 |             <span id="responseSettingsSummary" class="sr-only">설정 확인 중</span>
  314 |         </div>
```

## E05 — 현재 trace exact assistant upsert와 sanitize 계약
`current:main/resources/static/js/chat-trace-ui.js`
SHA-256: `d601e26db9563c16c47670938fc9f5fde7f845cae7f898468daad603ec42d07f`

행 17–26:
```text
   17 |   function enabled() {
   18 |     return Boolean(document.querySelector("[data-admin-diagnostics]") &&
   19 |       document.querySelector("[data-chat-trace-toggle]")?.checked);
   20 |   }
   21 | 
   22 |   function withDebugQuery(path) {
   23 |     const url = String(path || "");
   24 |     if (!enabled() || /(?:[?&])debug=/.test(url)) return url;
   25 |     return url + (url.includes("?") ? "&" : "?") + "debug=true";
   26 |   }
```

행 42–73:
```text
   42 |   function ensurePanel(assistant) {
   43 |     if (!assistant || !assistant.parentElement) return null;
   44 |     const existing = byAssistant.get(assistant);
   45 |     if (existing && existing.panel.isConnected) return existing;
   46 |     const panel = document.createElement("details");
   47 |     panel.className = "message-trace awx-trace-panel";
   48 |     panel.dataset.role = "trace";
   49 |     panel.dataset.liveRegion = "excluded";
   50 |     panel.setAttribute("aria-live", "off");
   51 |     const summary = document.createElement("summary");
   52 |     summary.textContent = "이 답변 추적";
   53 |     const metadata = document.createElement("div");
   54 |     metadata.className = "awx-trace-meta";
   55 |     const body = document.createElement("div");
   56 |     body.className = "awx-trace-body";
   57 |     panel.append(summary, metadata, body);
   58 |     // A details element directly in the transcript grid can grow beyond its
   59 |     // allocated row after opening. The block slot gives that row its real height.
   60 |     const slot = document.createElement("div");
   61 |     slot.className = "awx-trace-slot";
   62 |     slot.appendChild(panel);
   63 |     if (typeof assistant.after === "function") assistant.after(slot);
   64 |     else assistant.parentElement.insertBefore(slot, assistant.nextSibling);
   65 |     const state = { assistant, slot, panel, metadata, body, snapshotId: null,
   66 |       controller: null, loading: false, loaded: false, version: 0, live: false };
   67 |     byAssistant.set(assistant, state);
   68 |     byPanel.set(panel, state);
   69 |     panel.addEventListener("toggle", () => {
   70 |       if (panel.open && state.snapshotId) loadSnapshot(state);
   71 |     });
   72 |     return state;
   73 |   }
```

행 75–101:
```text
   75 |   function sanitizedTrace(html, snapshot) {
   76 |     if (!window.DOMPurify || typeof window.DOMPurify.sanitize !== "function") return null;
   77 |     const source = String(html || "");
   78 |     if (!source) return null;
   79 |     const fragment = window.DOMPurify.sanitize(source.slice(0, MAX_HTML), {
   80 |       RETURN_DOM_FRAGMENT: true,
   81 |       ALLOWED_TAGS: TAGS,
   82 |       ALLOWED_ATTR: ["class", "colspan", "rowspan"],
   83 |       FORBID_TAGS: ["script", "style", "iframe", "object", "embed", "form", "input",
   84 |         "img", "svg", "math", "link", "meta", "audio", "video", "source"],
   85 |       FORBID_ATTR: ["id", "name", "style", "href", "src", "srcset"],
   86 |       ALLOW_DATA_ATTR: false,
   87 |       ALLOW_ARIA_ATTR: false
   88 |     });
   89 |     if (!fragment || fragment.nodeType !== 11) return null;
   90 |     for (const node of fragment.querySelectorAll("[class]")) {
   91 |       const classes = Array.from(node.classList).filter(value =>
   92 |         /^(?:search-trace|trace-[a-z0-9-]+|small|text-muted)$/.test(value));
   93 |       node.className = classes.join(" ");
   94 |     }
   95 |     for (const table of fragment.querySelectorAll("table")) {
   96 |       for (const row of Array.from(table.querySelectorAll("tr")).slice(100)) row.remove();
   97 |     }
   98 |     if (!snapshot) return fragment;
   99 |     // A stored snapshot can be a whole HTML document. Only its sanitized trace
  100 |     // panel crosses into the live page; head/style/script never does.
  101 |     return fragment.querySelector("details.search-trace");
```

행 125–133:
```text
  125 |   function upsert(assistant, view) {
  126 |     if (!enabled()) return null;
  127 |     const state = ensurePanel(assistant);
  128 |     if (!state) return null;
  129 |     state.live = true;
  130 |     state.panel.hidden = false;
  131 |     showHtml(state, view?.html, false);
  132 |     return state.panel;
  133 |   }
```

## E06 — snapshot 조회/토글 취소/복원과 표시 한도
`current:main/resources/static/js/chat-trace-ui.js`
SHA-256: `d601e26db9563c16c47670938fc9f5fde7f845cae7f898468daad603ec42d07f`

행 135–162:
```text
  135 |   function restore(assistant, turnTrace) {
  136 |     const snapshotId = typeof turnTrace?.snapshotId === "string" ? turnTrace.snapshotId : "";
  137 |     if (!SNAPSHOT_ID.test(snapshotId)) return null;
  138 |     const state = ensurePanel(assistant);
  139 |     if (!state) return null;
  140 |     if (state.snapshotId !== snapshotId) {
  141 |       abortSnapshot(state);
  142 |       state.snapshotId = snapshotId;
  143 |       state.loaded = false;
  144 |     }
  145 |     state.panel.dataset.traceSnapshotId = snapshotId;
  146 |     state.metadata.replaceChildren();
  147 |     const fields = turnTrace.fields && typeof turnTrace.fields === "object" ? turnTrace.fields : {};
  148 |     const list = document.createElement("dl");
  149 |     for (const key of Object.keys(fields).slice(0, 16)) {
  150 |       const value = fields[key];
  151 |       if (!FIELD_KEY.test(key) || typeof value !== "string" || !FIELD_VALUE.test(value)) continue;
  152 |       const term = document.createElement("dt");
  153 |       term.textContent = key;
  154 |       const description = document.createElement("dd");
  155 |       description.textContent = value;
  156 |       list.append(term, description);
  157 |     }
  158 |     state.metadata.appendChild(list);
  159 |     if (!state.live && !state.loaded) status(state, "저장된 요약입니다. 상세는 펼칠 때 조회합니다.");
  160 |     if (state.panel.open) loadSnapshot(state);
  161 |     return state.panel;
  162 |   }
```

행 164–206:
```text
  164 |   async function loadSnapshot(state) {
  165 |     if (!enabled() || !state.snapshotId || state.loaded || state.loading || !state.panel.isConnected) return;
  166 |     if (activeSnapshotFetches >= MAX_SNAPSHOT_FETCHES) {
  167 |       status(state, "상세 조회가 진행 중입니다. 잠시 후 다시 펼쳐 주세요.");
  168 |       return;
  169 |     }
  170 |     const version = ++state.version;
  171 |     const snapshotId = state.snapshotId;
  172 |     const controller = new AbortController();
  173 |     state.controller = controller;
  174 |     state.loading = true;
  175 |     activeSnapshotFetches += 1;
  176 |     status(state, "상세 트레이스를 조회하는 중입니다.");
  177 |     try {
  178 |       const response = await fetch("/api/diagnostics/trace/snapshots/" +
  179 |         encodeURIComponent(snapshotId) + "/html", {
  180 |         method: "GET", cache: "no-store", credentials: "same-origin", signal: controller.signal
  181 |       });
  182 |       if (version !== state.version || !state.panel.isConnected || !enabled()) return;
  183 |       if (!response.ok) {
  184 |         status(state, response.status === 404
  185 |           ? "상세 스냅샷을 현재 저장소에서 찾을 수 없음"
  186 |           : response.status === 401 || response.status === 403
  187 |             ? "상세 조회 권한 없음" : "상세 조회 불가");
  188 |         return;
  189 |       }
  190 |       const html = await response.text();
  191 |       if (version !== state.version || state.snapshotId !== snapshotId ||
  192 |           !state.panel.isConnected || !enabled()) return;
  193 |       state.loaded = showHtml(state, html, true);
  194 |     } catch (_) {
  195 |       if (version === state.version && !controller.signal.aborted) status(state, "상세 조회 불가");
  196 |     } finally {
  197 |       activeSnapshotFetches -= 1;
  198 |       if (state.controller === controller) state.controller = null;
  199 |       if (version === state.version) state.loading = false;
  200 |     }
  201 |   }
  202 | 
  203 |   function dispose(root) {
  204 |     if (!root || typeof root.querySelectorAll !== "function") return;
  205 |     for (const panel of root.querySelectorAll('[data-role="trace"]')) {
  206 |       const state = byPanel.get(panel);
```

행 208–230:
```text
  208 |       abortSnapshot(state);
  209 |       byAssistant.delete(state.assistant);
  210 |       byPanel.delete(panel);
  211 |     }
  212 |   }
  213 | 
  214 |   document.addEventListener("change", event => {
  215 |     if (!event.target?.matches?.("[data-chat-trace-toggle]")) return;
  216 |     for (const panel of document.querySelectorAll('[data-role="trace"]')) {
  217 |       const state = byPanel.get(panel);
  218 |       if (!state) continue;
  219 |       if (!enabled()) {
  220 |         abortSnapshot(state);
  221 |         state.loaded = false;
  222 |         state.body.replaceChildren();
  223 |         if (state.snapshotId) status(state, "저장된 요약입니다. 상세는 펼칠 때 조회합니다.");
  224 |         if (state.live && !state.snapshotId) panel.hidden = true;
  225 |       } else {
  226 |         panel.hidden = false;
  227 |         if (panel.open && state.snapshotId) loadSnapshot(state);
  228 |       }
  229 |     }
  230 |   });
```

## E07 — metadata-only section 생산과 pointer persistence
`current:main/java/com/example/lms/api/ChatTraceSnapshotPointerPersister.java`
SHA-256: `a482c4b0dc02820c49929c836c146b9a7193fd64c6c2a36393a510b8640f13b9`

행 31–63:
```text
   31 |             ChatHistoryService historyService,
   32 |             Logger log) {
   33 |         if (sessionId == null || traceSnapshotStore == null) {
   34 |             return null;
   35 |         }
   36 |         boolean missingTraceHtml = traceHtml == null || traceHtml.isBlank();
   37 |         boolean metadataOnlyTraceMemory = isTraceMemorySnapshot(traceMeta) && missingTraceHtml;
   38 |         boolean metadataOnlyHarmony = isAgentVisibleHarmony(traceMeta) && missingTraceHtml;
   39 |         String snapshotHtml = metadataOnlyTraceMemory
   40 |                 ? metadataOnlyTraceMemoryTraceHtml(traceMeta)
   41 |                 : (metadataOnlyHarmony ? metadataOnlyHarmonyTraceHtml(traceMeta) : traceHtml);
   42 |         if (snapshotHtml == null || snapshotHtml.isBlank()) {
   43 |             return null;
   44 |         }
   45 |         try {
   46 |             Map<String, Object> snapMeta = new LinkedHashMap<>(traceMeta == null ? Map.of() : traceMeta);
   47 |             snapMeta.putIfAbsent("ui.traceHtml.kind", metadataOnlyTraceMemory ? "traceMemoryMetadataOnly"
   48 |                     : (metadataOnlyHarmony ? "chatHarmonyMetadataOnly" : "splitPanel"));
   49 |             snapMeta.putIfAbsent("ui.traceHtml.length", snapshotHtml.length());
   50 |             if (metadataOnlyTraceMemory || metadataOnlyHarmony) {
   51 |                 snapMeta.putIfAbsent("ui.traceHtml.synthetic", true);
   52 |             }
   53 |             String snapshotId = traceSnapshotStore.captureCustom(reason, method, path, null, null, snapMeta, snapshotHtml);
   54 |             if (!ChatTraceMetaMessageRestorer.isSafeTraceSnapshotId(snapshotId)) {
   55 |                 return null;
   56 |             }
   57 |             return historyService.appendMessageReturningId(
   58 |                     sessionId,
   59 |                     "system",
   60 |                     durablePointer(snapshotId, reason, method, path, snapMeta));
   61 |         } catch (Exception e) {
   62 |             String safeErrorType = errorType(e);
   63 |             TraceStore.put("chat.traceSnapshotPointer.suppressed.stage", "persist");
```

행 83–130:
```text
   83 |             String method,
   84 |             String path,
   85 |             Map<String, Object> traceMeta) {
   86 |         StringBuilder projection = new StringBuilder(512);
   87 |         appendProjection(projection, "storageMode", "durable_fallback");
   88 |         appendProjection(projection, "reason", safeProjectionLabel(reason, "unknown"));
   89 |         appendProjection(projection, "method", safeProjectionLabel(method, "unknown"));
   90 |         appendProjection(projection, "pathHash", safeHash(path));
   91 |         appendProjection(projection, "traceEntryCount", boundedCount(traceMeta == null ? 0 : traceMeta.size()));
   92 |         appendProjection(projection, "hasMlBreadcrumbs", hasMlBreadcrumbs(traceMeta));
   93 |         appendProjection(projection, "uiTraceHtmlKind",
   94 |                 safeProjectionLabel(traceMeta == null ? null : traceMeta.get("ui.traceHtml.kind"), "unknown"));
   95 |         appendProjection(projection, "uiTraceHtmlLength",
   96 |                 boundedCount(traceMeta == null ? null : traceMeta.get("ui.traceHtml.length")));
   97 |         appendOptionalProjectionLabel(projection, "harmonyDecision", traceMeta,
   98 |                 "chat.harmony.postprocess.decision");
   99 |         appendOptionalProjectionLabel(projection, "harmonyReason", traceMeta,
  100 |                 "chat.harmony.postprocess.reason");
  101 |         appendOptionalProjectionLabel(projection, "traceMemoryStage", traceMeta,
  102 |                 "traceMemory.checkpoint.stage");
  103 |         appendOptionalProjectionLabel(projection, "traceMemoryReason", traceMeta,
  104 |                 "traceMemory.trigger.reason");
  105 | 
  106 |         byte[] bytes = projection.toString().getBytes(StandardCharsets.UTF_8);
  107 |         if (bytes.length > MAX_DURABLE_PROJECTION_BYTES) {
  108 |             String minimal = "storageMode=durable_fallback\n"
  109 |                     + "reason=" + safeProjectionLabel(reason, "unknown") + "\n"
  110 |                     + "method=" + safeProjectionLabel(method, "unknown") + "\n"
  111 |                     + "pathHash=" + safeHash(path) + "\n";
  112 |             bytes = minimal.getBytes(StandardCharsets.UTF_8);
  113 |         }
  114 |         String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  115 |         return TRACE_SNAPSHOT_META_PREFIX + snapshotId + "|" + DURABLE_ENVELOPE_VERSION + "|" + encoded;
  116 |     }
  117 | 
  118 |     private static void appendProjection(StringBuilder out, String key, Object value) {
  119 |         out.append(key).append('=').append(value).append('\n');
  120 |     }
  121 | 
  122 |     private static void appendOptionalProjectionLabel(
  123 |             StringBuilder out,
  124 |             String projectionKey,
  125 |             Map<String, Object> traceMeta,
  126 |             String sourceKey) {
  127 |         if (traceMeta == null || !traceMeta.containsKey(sourceKey)) {
  128 |             return;
  129 |         }
  130 |         appendProjection(out, projectionKey, safeProjectionLabel(traceMeta.get(sourceKey), "unknown"));
```

행 196–231:
```text
  196 |     private static String metadataOnlyTraceMemoryTraceHtml(Map<String, Object> traceMeta) {
  197 |         return "<section data-trace=\"trace-memory\" data-kind=\"metadata-only\">"
  198 |                 + "<h3>Trace Memory Checkpoint</h3>"
  199 |                 + "<dl>"
  200 |                 + item("Checkpoint stage", traceMeta.get("traceMemory.checkpoint.stage"))
  201 |                 + item("Checkpoint phase", traceMeta.get("traceMemory.checkpoint.phase"))
  202 |                 + item("Checkpoint history", traceMeta.get("traceMemory.checkpoint.historySize"))
  203 |                 + item("Triggered", traceMeta.get("traceMemory.triggered"))
  204 |                 + item("Reason", traceMeta.get("traceMemory.trigger.reason"))
  205 |                 + item("Failure class", traceMeta.get("traceMemory.recovery.failureClass"))
  206 |                 + item("Recovery action", traceMeta.get("traceMemory.recovery.action"))
  207 |                 + item("Recovery route", traceMeta.get("traceMemory.recovery.route"))
  208 |                 + item("Quarantine", traceMeta.get("traceMemory.recovery.quarantine"))
  209 |                 + item("Suspect isolated", traceMeta.get("traceMemory.suspectPayload.isolated"))
  210 |                 + item("Error break risk", traceMeta.get("traceMemory.errorBreak.risk"))
  211 |                 + item("CFVM offered", traceMeta.get("traceMemory.cfvm.offered"))
  212 |                 + item("Supabase shadow count", traceMeta.get("traceMemory.rawSnapshot.supabaseShadowCount"))
  213 |                 + item("Delta changed", traceMeta.get("traceMemory.delta.changed"))
  214 |                 + item("Delta changed count", traceMeta.get("traceMemory.delta.changedCount"))
  215 |                 + item("Dropped breadcrumbs", traceMeta.get("traceMemory.delta.droppedBreadcrumbCount"))
  216 |                 + "</dl>"
  217 |                 + "</section>";
  218 |     }
  219 | 
  220 |     private static String metadataOnlyHarmonyTraceHtml(Map<String, Object> traceMeta) {
  221 |         return "<section data-trace=\"chat-harmony\" data-kind=\"metadata-only\">"
  222 |                 + "<h3>Chat Harmony Trace</h3>"
  223 |                 + "<dl>"
  224 |                 + item("Decision", traceMeta.get("chat.harmony.postprocess.decision"))
  225 |                 + item("Reason", traceMeta.get("chat.harmony.postprocess.reason"))
  226 |                 + item("Weighted score", traceMeta.get("chat.harmony.postprocess.weightedScore"))
  227 |                 + item("Evidence count", traceMeta.get("chat.harmony.postprocess.evidenceCount"))
  228 |                 + item("Next debug action", traceMeta.get("debug.ai.metrics.nextAction"))
  229 |                 + item("Next debug reason", traceMeta.get("debug.ai.metrics.nextReason"))
  230 |                 + "</dl>"
  231 |                 + "</section>";
```

## E08 — snapshot HTML endpoint가 stored HTML 그대로 반환
`current:main/java/com/example/lms/api/TraceSnapshotsDiagnosticsController.java`
SHA-256: `f218d27492d0cbe7095009cf1ce87c4300e98c83b676e4ae397cb67ce0379654`

행 238–259:
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

## E09 — trace를 직전 일반 메시지에 연결하고 createdAt만 정렬
`current:main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java`
SHA-256: `c34dfdd730f3574423864fb13edc746c3069dd956937eec9e5e7e15e13011c82`

행 39–85:
```text
   39 |             Logger log) {
   40 |         var raw = Optional.ofNullable(session.getMessages())
   41 |                 .orElse(Collections.emptyList())
   42 |                 .stream()
   43 |                 .sorted(Comparator.comparing(m -> m.getCreatedAt()))
   44 |                 .toList();
   45 | 
   46 |         List<ChatApiController.MessageDto> messages = new ArrayList<>();
   47 |         List<ChatApiController.TurnTraceDto> turnTraces = new ArrayList<>();
   48 |         Long lastChatMessageId = null;
   49 |         String lastModelMeta = null;
   50 |         for (var m : raw) {
   51 |             String role = m.getRole();
   52 |             String content = m.getContent();
   53 | 
   54 |             if ("system".equals(role)) {
   55 |                 if (content != null) {
   56 |                     String modelMeta = ChatModelMetaSupport.extractModelUsed(content);
   57 |                     if (modelMeta != null) {
   58 |                         lastModelMeta = modelMeta;
   59 |                         continue;
   60 |                     }
   61 |                     if (exposeTrace) {
   62 |                         var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(content, m.getId());
   63 |                         if (pointer.isPresent()) {
   64 |                             turnTraces.add(new ChatApiController.TurnTraceDto(
   65 |                                     lastChatMessageId != null ? lastChatMessageId : m.getId(),
   66 |                                     pointer.get().snapshotId(),
   67 |                                     mergeModelMetaField(pointer.get().projection(), lastModelMeta)));
   68 |                             lastModelMeta = null;
   69 |                         }
   70 |                     }
   71 |                     Optional<ChatApiController.MessageDto> traceMeta =
   72 |                             ChatTraceMetaMessageRestorer.restore(m.getId(), content, m.getCreatedAt(), exposeTrace);
   73 |                     if (traceMeta.isPresent()) {
   74 |                         messages.add(traceMeta.get());
   75 |                         continue;
   76 |                     }
   77 |                     if (content.startsWith(TRACE_META_PREFIX) || content.startsWith(TRACE_META_PREFIX_B64)) {
   78 |                         continue;
   79 |                     }
   80 |                 }
   81 |                 continue;
   82 |             }
   83 | 
   84 |             lastChatMessageId = m.getId();
   85 |             messages.add(new ChatApiController.MessageDto(m.getId(), role, content, m.getCreatedAt()));
```

## E10 — v1 parser: explicit assistant id 없는 strict allowlist
`current:main/java/com/example/lms/api/ChatTraceMetaMessageRestorer.java`
SHA-256: `51e260a2791e25ea1bbf2ca0b4838911cfd82dc50e257de22a869e21cf795789`

행 20–40:
```text
   20 |     private static final String TRACE_META_PREFIX_B64 = "?TRACE64?";
   21 |     private static final String TRACE_SNAPSHOT_META_PREFIX = "?TRACESNAP?";
   22 |     private static final int MAX_TRACE_META_B64_CHARS = 64_000;
   23 |     private static final String DURABLE_ENVELOPE_VERSION = "v1";
   24 |     private static final int MAX_DURABLE_PROJECTION_BYTES = 2_048;
   25 |     private static final int MAX_DURABLE_PROJECTION_B64_CHARS = 2_732;
   26 |     private static final int MAX_DURABLE_PROJECTION_FIELDS = 16;
   27 |     private static final Pattern SAFE_LABEL = Pattern.compile("[A-Za-z0-9_.:-]{1,80}");
   28 |     private static final Pattern SAFE_HASH = Pattern.compile("hash:[0-9a-f]{12}");
   29 |     private static final Set<String> LABEL_FIELDS = Set.of(
   30 |             "reason",
   31 |             "method",
   32 |             "uiTraceHtmlKind",
   33 |             "harmonyDecision",
   34 |             "harmonyReason",
   35 |             "traceMemoryStage",
   36 |             "traceMemoryReason");
   37 |     private static final Set<String> COUNT_FIELDS = Set.of(
   38 |             "traceEntryCount",
   39 |             "uiTraceHtmlLength");
   40 |     private static final Set<String> BOOLEAN_FIELDS = Set.of("hasMlBreadcrumbs");
```

행 90–109:
```text
   90 |     }
   91 | 
   92 |     static Optional<SnapshotPointer> parseSnapshotPointer(String content, Long messageId) {
   93 |         if (content == null || !content.startsWith(TRACE_SNAPSHOT_META_PREFIX)) {
   94 |             return Optional.empty();
   95 |         }
   96 |         String pointer = content.substring(TRACE_SNAPSHOT_META_PREFIX.length()).trim();
   97 |         int firstDelimiter = pointer.indexOf('|');
   98 |         String snapshotId = firstDelimiter < 0 ? pointer : pointer.substring(0, firstDelimiter);
   99 |         if (!isSafeTraceSnapshotId(snapshotId)) {
  100 |             log.debug("[AWX][trace] trace snapshot pointer skipped reason=invalid_id messageId={}", messageId);
  101 |             return Optional.empty();
  102 |         }
  103 |         Map<String, String> projection = firstDelimiter < 0
  104 |                 ? Map.of()
  105 |                 : parseDurableProjection(pointer, firstDelimiter, messageId);
  106 |         return Optional.of(new SnapshotPointer(snapshotId, projection));
  107 |     }
  108 | 
  109 |     static boolean isSafeTraceSnapshotId(String snapshotId) {
```

행 120–145:
```text
  120 |                 return false;
  121 |             }
  122 |         }
  123 |         return true;
  124 |     }
  125 | 
  126 |     private static Map<String, String> parseDurableProjection(String pointer, int firstDelimiter, Long turnId) {
  127 |         int secondDelimiter = pointer.indexOf('|', firstDelimiter + 1);
  128 |         if (secondDelimiter < 0
  129 |                 || pointer.indexOf('|', secondDelimiter + 1) >= 0
  130 |                 || !DURABLE_ENVELOPE_VERSION.equals(pointer.substring(firstDelimiter + 1, secondDelimiter))) {
  131 |             traceSnapshotEnvelopeSkipped(turnId, "invalid_envelope");
  132 |             return Map.of();
  133 |         }
  134 |         String encoded = pointer.substring(secondDelimiter + 1);
  135 |         if (encoded.isBlank() || encoded.length() > MAX_DURABLE_PROJECTION_B64_CHARS) {
  136 |             traceSnapshotEnvelopeSkipped(turnId, "invalid_size");
  137 |             return Map.of();
  138 |         }
  139 |         try {
  140 |             byte[] decoded = Base64.getUrlDecoder().decode(encoded);
  141 |             if (decoded.length == 0 || decoded.length > MAX_DURABLE_PROJECTION_BYTES) {
  142 |                 traceSnapshotEnvelopeSkipped(turnId, "invalid_size");
  143 |                 return Map.of();
  144 |             }
  145 |             String text = new String(decoded, StandardCharsets.UTF_8);
```

행 178–201:
```text
  178 |             }
  179 |             return Map.copyOf(projection);
  180 |         } catch (IllegalArgumentException e) {
  181 |             traceSnapshotEnvelopeSkipped(turnId, "invalid_base64");
  182 |             return Map.of();
  183 |         }
  184 |     }
  185 | 
  186 |     private static boolean isValidDurableField(String key, String value) {
  187 |         if ("storageMode".equals(key)) {
  188 |             return "durable_fallback".equals(value);
  189 |         }
  190 |         if ("pathHash".equals(key)) {
  191 |             return "none".equals(value) || SAFE_HASH.matcher(value).matches();
  192 |         }
  193 |         if (LABEL_FIELDS.contains(key)) {
  194 |             return SAFE_LABEL.matcher(value).matches() || SAFE_HASH.matcher(value).matches();
  195 |         }
  196 |         if (COUNT_FIELDS.contains(key)) {
  197 |             try {
  198 |                 long parsed = Long.parseLong(value);
  199 |                 return parsed >= 0L && parsed <= 1_000_000L;
  200 |             } catch (NumberFormatException ignored) {
  201 |                 return false;
```

## E11 — assistant message ID와 trace meta message ID 별개
`current:main/java/com/example/lms/api/ChatApiController.java`
SHA-256: `30a8bfe311f2cdb8b37e442ececb6796ed95d13660b1b50fb9512f31b5e8e19c`

행 2670–2693:
```text
 2670 |                 }
 2671 | 
 2672 |                 // 7) ?筌뤾쑬??????+ 嶺뚮ㅄ維???筌뤾퍔???怨룸츩 嶺뚮∥??
 2673 |                 Long persistenceSessionId = session.getId();
 2674 |                 String persistenceAnswerMode = answerModeFinal;
 2675 |                 var persistenceGraphScope = dtoForCall.getGeneralGraphScope();
 2676 |                 java.util.Map<String, Object> persistenceTraceMeta = traceMetaForSnapshot;
 2677 |                 String persistenceTraceHtml = traceHtmlForSnapshot;
 2678 |                 ChatStreamEvent.PipelineSnapshot pipelineSnapshotBeforePersistence = finalPipelineSnapshot;
 2679 |                 AtomicReference<Long> traceTurnIdRef = new AtomicReference<>();
 2680 |                 AtomicReference<ChatStreamEvent.PipelineSnapshot> persistedPipelineSnapshotRef =
 2681 |                         new AtomicReference<>(pipelineSnapshotBeforePersistence);
 2682 |                 Runnable durablePersistence = () -> {
 2683 |                     Long assistantMessageId = historyService.appendMessageReturningId(
 2684 |                             persistenceSessionId, "assistant", persistableFinalText);
 2685 |                     if (assistantMessageId != null && committingRun != null && !committingRun.markPersisted()) {
 2686 |                         throw new IllegalStateException("exact transcript persistence outcome rejected");
 2687 |                     }
 2688 |                     captureFinalizedGraph(persistenceGraphScope, persistedUserMessageId,
 2689 |                             assistantMessageId, streamRagControlProjection.held());
 2690 |                     if (!streamRagControlProjection.held()) {
 2691 |                         updateRollingSummaryAndMaybePromote(
 2692 |                                 persistenceSessionId, assistantMessageId, req);
 2693 |                     }
```

행 2716–2745:
```text
 2716 |                     try {
 2717 |                         historyService.updateSessionAnswerModeAndTrace(
 2718 |                                 persistenceSessionId,
 2719 |                                 persistenceAnswerMode,
 2720 |                                 persistedTraceTurnId);
 2721 |                     } catch (Exception ignore) {
 2722 |                         logSuppressed("stream.answerModeTracePersist");
 2723 |                     }
 2724 |                 };
 2725 |                 boolean durablePersistenceAccepted;
 2726 |                 if (committingRun == null) {
 2727 |                     durablePersistence.run();
 2728 |                     durablePersistenceAccepted = true;
 2729 |                 } else {
 2730 |                     durablePersistenceAccepted =
 2731 |                             committingRun.runTerminalSideEffect(durablePersistence);
 2732 |                 }
 2733 |                 if (!durablePersistenceAccepted) {
 2734 |                     writeSelectionEntropyProjection(
 2735 |                             selectionEntropy, selectionDecisionLedger, true);
 2736 |                     emitSelectionEntropy(sink);
 2737 |                     recordRunTerminal(runContextRef.get(), "cancelled");
 2738 |                     recordModelRequestTerminal("cancelled", requestTimelineId, modelUsedFinal);
 2739 |                     emitStreamCancelledStatus(sink, __capturedBudget, __streamStartedNs);
 2740 |                     return;
 2741 |                 }
 2742 |                 Long traceTurnId = traceTurnIdRef.get();
 2743 |                 finalPipelineSnapshot = persistedPipelineSnapshotRef.get();
 2744 | 
 2745 |                 try {
```

## E12 — initial/replay 모두 subscriber별 HTML projection
`current:main/java/com/example/lms/api/ChatApiController.java`
SHA-256: `30a8bfe311f2cdb8b37e442ececb6796ed95d13660b1b50fb9512f31b5e8e19c`

행 1585–1599:
```text
 1585 |     @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
 1586 |     public Flux<ServerSentEvent<ChatStreamEvent>> chatStream(@RequestBody @Valid ChatRequestDto req,
 1587 |             @RequestParam(name = "attach", required = false, defaultValue = "false") boolean attach,
 1588 |             @RequestParam(name = "debug", required = false, defaultValue = "false") boolean debug,
 1589 |             @AuthenticationPrincipal UserDetails principal,
 1590 |             HttpServletRequest request) {
 1591 |         if (req == null) {
 1592 |             return Flux.just(sse(ChatStreamEvent.error("bad_request")));
 1593 |         }
 1594 |         // Capture authority on the request thread. Generation and replay may run on
 1595 |         // other threads, where SecurityContextHolder is not the subscriber's context.
 1596 |         final boolean mayReadTraceHtml = isAdmin(
 1597 |                 org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication())
 1598 |                 && (debug || exposeTrace);
 1599 |         SelectionReplayRequestResolver.Resolved selectionState =
```

행 3150–3172:
```text
 3150 |     private static ServerSentEvent<ChatStreamEvent> projectTraceForSubscriber(
 3151 |             ServerSentEvent<ChatStreamEvent> event, boolean mayReadTraceHtml) {
 3152 |         ChatStreamEvent data = event == null ? null : event.data();
 3153 |         if (mayReadTraceHtml || data == null || data.html() == null) {
 3154 |             return event;
 3155 |         }
 3156 |         ChatStreamEvent projected = new ChatStreamEvent(
 3157 |                 data.type(), data.data(), null, data.modelUsed(), data.ragUsed(),
 3158 |                 data.sessionId(), data.answerMode(), data.traceTurnId(),
 3159 |                 data.learningContext(), data.evidence(), data.statusSignal(),
 3160 |                 data.traceSignal(), data.scoreDelta(), data.pipelineSnapshot(),
 3161 |                 data.debugFxSignal(), data.transformerBlocks(), data.selectionEntropySignal(),
 3162 |                 data.generationTermination());
 3163 |         return ServerSentEvent.<ChatStreamEvent>builder(projected)
 3164 |                 .event(event.event()).id(event.id()).retry(event.retry())
 3165 |                 .comment(event.comment()).build();
 3166 |     }
 3167 | 
 3168 |     private static SelectionEntropyProjection writeSelectionEntropyProjection(
 3169 |             SelectionEntropy entropy,
 3170 |             SelectionDecisionLedger ledger,
 3171 |             boolean terminal) {
 3172 |         SelectionEntropyProjection projection =
```

행 5012–5038:
```text
 5012 |             }
 5013 |             return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
 5014 |         }
 5015 | 
 5016 |         return ChatSessionDetailResponseBuilder.build(
 5017 |                 session,
 5018 |                 username,
 5019 |                 objectMapper,
 5020 |                 settingsService.getAllSettings(),
 5021 |                 isAdmin && traceOwner && (debug || exposeTrace),
 5022 |                 log);
 5023 |     }
 5024 | 
 5025 |     private static ResponseEntity<Map<String, Object>> restoreProbeReset(String error) {
 5026 |         return ResponseEntity.ok(Map.of(
 5027 |                 "found", false,
 5028 |                 "action", "RESET_SESSION",
 5029 |                 "error", error));
 5030 |     }
 5031 | 
 5032 |     // ===== helpers =====
 5033 |     private static void tracePut(String key, Object value) {
 5034 |         try {
 5035 |             TraceStore.put(key, value);
 5036 |         } catch (Exception ignore) {
 5037 |             logSuppressed(key);
 5038 |         }
```

## E13 — typed diagnostic 행은 append, trace html은 exact bubble
`current:main/resources/static/js/chat.js`
SHA-256: `68ce97f02c9fafa1bfce475d98ce2b27877afb4a3b702de1648f8fcf98cc5096`

행 3368–3384:
```text
 3368 | const renderTraceSignalDetail = (signal, pipeline, target) => {
 3369 |   if (shouldSuppressMessageDiagnostics(target)) return null;
 3370 |   const detail = document.createElement("div");
 3371 |   detail.dataset.role = "trace-signal-detail";
 3372 |   markChatDiagnosticNode(detail);
 3373 |   appendTraceSignalLabel(detail, "trace", signal.traceIdHash || pipeline.traceTurnId);
 3374 |   appendTraceSignalLabel(detail, "request", signal.requestIdHash);
 3375 |   appendTraceSignalLabel(detail, "session", signal.sessionIdHash);
 3376 |   appendTraceSignalLabel(detail, "events", signal.eventCount);
 3377 |   appendTraceSignalLabel(detail, "failure", signal.failureClass || pipeline.failureClass);
 3378 |   appendTraceSignalLabel(detail, "reason", signal.reasonCode || pipeline.disabledReason);
 3379 |   Object.entries(signal.stageCounts || {}).forEach(([stage, count]) => {
 3380 |     appendTraceSignalLabel(detail, stage, count);
 3381 |   });
 3382 |   target?.appendChild(detail);
 3383 | };
 3384 | 
```

행 3414–3439:
```text
 3414 | const renderScoreDeltaDetail = (signal, target) => {
 3415 |   if (shouldSuppressMessageDiagnostics(target)) return null;
 3416 |   if (!signal) return null;
 3417 |   const numericFields = [
 3418 |     ["scoreDelta", signal.scoreDelta],
 3419 |     ["dropRatio", signal.dropRatio],
 3420 |     ["maxDrawdown", signal.maxDrawdown],
 3421 |     ["expectedDelta", signal.expectedDelta],
 3422 |     ["rawScoreDelta", signal.rawScoreDelta]
 3423 |   ].filter(([, value]) => isSupportedScoreDeltaNumber(value));
 3424 |   const labelFields = [
 3425 |     ["clampName", signal.clampName],
 3426 |     ["stage", signal.stage],
 3427 |     ["guard", signal.guard],
 3428 |     ["eventId", signal.eventId]
 3429 |   ].filter(([, value]) => value != null && value !== "");
 3430 |   if (numericFields.length === 0 && labelFields.length === 0) return null;
 3431 |   const detail = document.createElement("div");
 3432 |   detail.dataset.role = "score-delta-detail";
 3433 |   markChatDiagnosticNode(detail);
 3434 |   numericFields.forEach(([label, value]) => appendScoreDeltaLabel(detail, label, value));
 3435 |   labelFields.forEach(([label, value]) => appendScoreDeltaLabel(detail, label, value));
 3436 |   target?.appendChild(detail);
 3437 |   return detail;
 3438 | };
 3439 | 
```

행 6037–6055:
```text
 6037 |         pipelineSnapshot: pipeline,
 6038 |         learningContext: payload.learningContext || {}
 6039 |       }, bubble?.parentElement || dom.chatMessages);
 6040 |     }
 6041 |     if (type === "trace") {
 6042 |       renderTraceSignalDetail(signal, pipeline, bubble?.parentElement || dom.chatMessages);
 6043 |       renderScoreDeltaDetail(signal, bubble?.parentElement || dom.chatMessages);
 6044 |       if (payload.html) renderTraceHtml(payload, bubble);
 6045 |     }
 6046 |     setStatusRailValue(dom.traceStatus, type);
 6047 |   } else if (type === "debug_fx") {
 6048 |     setStatusRailValue(dom.traceStatus, type);
 6049 |     handleDebugFxSignal(payload);
 6050 |     reconcileChatHarmonyRailFromDebugFx(payload, assistant);
 6051 |     renderDebugFxTrace(payload, assistant);
 6052 |   } else if (type === "trace_html") {
 6053 |     renderTraceHtml(payload, assistant);
 6054 |   } else if (type === "error") {
 6055 |     clearActiveRunIdentity();
```

## E14 — 과거 innerHTML와 script 재실행 구현: 복구 금지
`legacy:src/main/resources/static/js/chat.js`
SHA-256: `49677b0c14292d81ce5795144d2491201f311d16132b093abf3062e496a6e46c`

행 1855–1905:
```text
 1855 |      if (type === "status" && statusEl) {
 1856 |        statusEl.innerHTML =
 1857 |          `<div class="spinner-border spinner-border-sm me-2" role="status"><span class="visually-hidden">Loading...</span></div>${payload.data || ""}`;
 1858 |      }
 1859 |      // When we receive a trace event, append the search details to a
 1860 |      // collapsible panel within the current turn wrapper.  Only one
 1861 |      // trace container is created per turn and subsequent traces replace
 1862 |      // the panel content. This prevents duplicate/nested details blocks
 1863 |      // and keeps the UI in sync when the server emits an updated trace
 1864 |      // (e.g., raw snippets first, final TopK context later).
 1865 |      if (type === "trace" && bubble) {
 1866 |        const wrap = bubble.parentElement;
 1867 |        // Find or create a simple container. The server already sends a
 1868 |        // <details class="search-trace"> block, so we avoid nesting.
 1869 |        let holder = wrap.querySelector('[data-role="trace"]');
 1870 |        if (!holder) {
 1871 |          holder = document.createElement("div");
 1872 |          holder.dataset.role = "trace";
 1873 |          holder.dataset.ttsIgnore = '1';
 1874 |          holder.classList.add('trace-inline');
 1875 |          wrap.appendChild(holder);
 1876 |        }
 1877 |        holder.innerHTML = payload.html || "";
 1878 | 
 1879 |        // NOTE: <script> tags injected via innerHTML do not execute by default.
 1880 |        // Execute only scripts explicitly marked by server-side TraceHtmlBuilder.
 1881 |        try {
 1882 |          const scripts = holder.querySelectorAll('script[data-trace-script=\"1\"]');
 1883 |          scripts.forEach((oldScript) => {
 1884 |            const code = (oldScript.textContent || oldScript.innerText || '').trim();
 1885 |            if (!code) return;
 1886 |            const s = document.createElement('script');
 1887 |            s.dataset.traceScript = '1';
 1888 |            s.text = code;
 1889 |            (document.body || document.head || holder).appendChild(s);
 1890 |            s.parentNode && s.parentNode.removeChild(s);
 1891 |          });
 1892 |        } catch (e) {
 1893 |          console.warn('trace script exec failed', e);
 1894 |        }
 1895 | 
 1896 |      }
 1897 |      if (type === "token" && bubble) {
 1898 |        const current = bubble.innerHTML.replace(/<div id=".*?">[\s\S]*?<\/div>/, "");
 1899 |        const chunk = String(payload.data || "").replace(/\n/g, "<br>");
 1900 |        bubble.innerHTML = current + chunk;
 1901 |        dom.chatWindow.scrollTop = dom.chatWindow.scrollHeight;
 1902 |      }
 1903 | 
 1904 |     // Handle intermediate thought updates by appending them to the thought process panel
 1905 |     if (type === "thought") {
```

## E15 — 현재 builder에도 남은 과거 필터/정렬 inline script
`current:main/java/com/example/lms/service/trace/TraceHtmlBuilder.java`
SHA-256: `d57875eee3c61b37a08bd8cf134215de9830eaa2b54a0af844f898c7da0c89db`

행 300–320:
```text
  300 |                 sb.append("<details class='trace-fold trace-steps-panel'>");
  301 |                 sb.append("<summary><span class='trace-mono'>trace steps</span> <span class='text-muted small'>(")
  302 |                         .append(rawTrace.steps.size()).append("; showing up to ").append(maxRows).append(")</span>");
  303 |                 if (boostDetail) {
  304 |                     sb.append(" <span class='text-muted small'>(boost detail)</span>");
  305 |                 }
  306 |                 sb.append("</summary>");
  307 | 
  308 |                 // Minimal filter controls (all client-side)
  309 |                 sb.append("<div class='trace-controls small trace-steps-controls'>");
  310 |                 sb.append("<label><input type='checkbox' data-steps-filter='nonok'> non-ok</label> ");
  311 |                 sb.append("<label><input type='checkbox' data-steps-filter='slow'> slow(??000ms)</label> ");
  312 |                 sb.append(
  313 |                         "<input type='text' class='trace-input small' placeholder='filter query?? data-steps-filter='q'>");
  314 |                 sb.append("</div>");
  315 | 
  316 |                 sb.append("<table class='trace-table small trace-steps-table'>");
  317 |                 sb.append("<thead><tr>");
  318 |                 sb.append("<th data-skey='idx' data-stype='num'>#</th>");
  319 |                 sb.append("<th data-skey='query' data-stype='txt'>query</th>");
  320 |                 if (showQlen)
```

행 336–375:
```text
  336 |                         continue;
  337 |                     String qfull = safeValueOrDefault(st.query, "");
  338 |                     int qlen = qfull.length();
  339 | 
  340 |                     String qtxt = safeDiagnostic("query", qfull);
  341 |                     if (qtxt.length() > maxQuery)
  342 |                         qtxt = qtxt.substring(0, maxQuery) + "...";
  343 |                     String qData = String.valueOf(SafeRedactor.hash12(qfull));
  344 |                     if (qData.length() > 400)
  345 |                         qData = qData.substring(0, 400) + "...";
  346 | 
  347 |                     sb.append("<tr")
  348 |                             .append(" data-idx='").append(idx2).append("'")
  349 |                             .append(" data-query='").append(escapeAttr(qData)).append("'")
  350 |                             .append(" data-qlen='").append(qlen).append("'")
  351 |                             .append(" data-returned='").append(st.returned).append("'")
  352 |                             .append(" data-kept='").append(st.afterFilter).append("'")
  353 |                             .append(" data-took='").append(st.tookMs).append("'")
  354 |                             .append(">");
  355 | 
  356 |                     sb.append("<td>").append(idx2).append("</td>");
  357 |                     sb.append("<td class='trace-mono'>").append(escape(qtxt)).append("</td>");
  358 |                     if (showQlen)
  359 |                         sb.append("<td>").append(qlen).append("</td>");
  360 |                     sb.append("<td>").append(st.returned).append("</td>");
  361 |                     sb.append("<td>").append(st.afterFilter).append("</td>");
  362 |                     sb.append("<td>").append(escape(fmtMs(st.tookMs))).append("</td>");
  363 |                     sb.append("</tr>");
  364 |                 }
  365 |                 sb.append("</tbody></table>");
  366 | 
  367 |                 // Column sorting + filter wiring (safe no-op if browser blocks scripts)
  368 |                 sb.append(
  369 |                         """
  370 |                                 <script data-trace-script="1">
  371 |                                 (function(){
  372 |                                   function wire(panel){
  373 |                                     if(!panel || panel.__stepsWired) return;
  374 |                                     panel.__stepsWired = true;
  375 |                                     var table = panel.querySelector('table.trace-steps-table');
```

## E16 — PlanExecutionSpec의 실제 tri-state/관측 ledger 의미
`current:main/java/com/example/lms/plan/PlanExecutionSpec.java`
SHA-256: `c1ec2e198a6530069c0cf4f4a4dfeada32c1b260613466a9c3f5c9e0b435aaa0`

행 14–40:
```text
   14 |  * Additive execution view of the {@code plan.when} / {@code plan.pipeline}
   15 |  * sections that the typed {@link PlanHints} projection deliberately does not
   16 |  * carry (the projection boundary tests pin those keys as diagnostics only).
   17 |  *
   18 |  * <p>Semantics:
   19 |  * <ul>
   20 |  *   <li>{@code when.any} is an OR of allowlisted condition expressions.
   21 |  *       Tri-state: TRUE if any condition is satisfied, FALSE only when every
   22 |  *       condition resolved to false, UNKNOWN when at least one condition is
   23 |  *       unresolvable and none is satisfied. An absent {@code when} means the
   24 |  *       plan has no gate and evaluates TRUE.</li>
   25 |  *   <li>Unobserved metrics are never coerced to zero: a missing metric makes
   26 |  *       its condition UNKNOWN, which cannot activate the plan by itself.</li>
   27 |  *   <li>{@code pipeline} labels are validated against a stage allowlist and
   28 |  *       mapped to observable runtime evidence. A declared stage is never
   29 |  *       reported as executed without a real marker; unmapped stages are
   30 |  *       reported {@code unavailable} rather than silently claimed.</li>
   31 |  * </ul>
   32 |  */
   33 | public final class PlanExecutionSpec {
   34 | 
   35 |     public enum TriState { TRUE, FALSE, UNKNOWN }
   36 | 
   37 |     public enum StageStatus {
   38 |         EXECUTED, ENABLED, DECLARED,
   39 |         SKIPPED_FLAG_OFF, SKIPPED_WHEN_INACTIVE, SKIPPED_DEPENDENCY, SKIPPED_DUPLICATE,
   40 |         FAILED, UNAVAILABLE, DELEGATED
```

행 253–277:
```text
  253 |     public WhenVerdict evaluateWhen(Map<String, Object> scope) {
  254 |         if (!whenPresent) {
  255 |             return new WhenVerdict(TriState.TRUE, List.of());
  256 |         }
  257 |         List<ConditionEval> evals = new ArrayList<>();
  258 |         boolean anyTrue = false;
  259 |         boolean anyUnknown = false;
  260 |         for (Condition condition : whenAny) {
  261 |             ConditionEval eval = evalCondition(condition, scope == null ? Map.of() : scope);
  262 |             evals.add(eval);
  263 |             if ("true".equals(eval.result())) {
  264 |                 anyTrue = true;
  265 |             } else if ("unknown".equals(eval.result())) {
  266 |                 anyUnknown = true;
  267 |             }
  268 |         }
  269 |         TriState state = anyTrue ? TriState.TRUE
  270 |                 : (anyUnknown || whenAny.isEmpty() || unsupportedWhenKeys > 0) ? TriState.UNKNOWN
  271 |                 : TriState.FALSE;
  272 |         return new WhenVerdict(state, List.copyOf(evals));
  273 |     }
  274 | 
  275 |     private static ConditionEval evalCondition(Condition condition, Map<String, Object> scope) {
  276 |         if ("literal".equals(condition.kind())) {
  277 |             return new ConditionEval(condition.expression(),
```

행 324–363:
```text
  324 |     public List<StageEntry> stageLedger(Map<String, Object> evidence, StageFlags flags) {
  325 |         List<StageEntry> out = new ArrayList<>();
  326 |         Set<String> emitted = new LinkedHashSet<>();
  327 |         for (String stage : pipeline) {
  328 |             String key = stage.toLowerCase(Locale.ROOT);
  329 |             if (!emitted.add(key)) {
  330 |                 out.add(new StageEntry(stage, StageStatus.SKIPPED_DUPLICATE, "", "duplicate_stage"));
  331 |                 continue;
  332 |             }
  333 |             Binding binding = bind(key);
  334 |             if (binding == Binding.NONE) {
  335 |                 out.add(new StageEntry(stage, StageStatus.UNAVAILABLE, "", "no_binding"));
  336 |                 continue;
  337 |             }
  338 |             if (binding == Binding.DELEGATED) {
  339 |                 out.add(new StageEntry(stage, StageStatus.DELEGATED, "", "caller_owned"));
  340 |                 continue;
  341 |             }
  342 |             boolean expansionClass = EXPANSION_STAGES.contains(key);
  343 |             if (expansionClass && !flags.expansionEligible()) {
  344 |                 out.add(new StageEntry(stage, StageStatus.SKIPPED_WHEN_INACTIVE, "", "when_not_true"));
  345 |                 continue;
  346 |             }
  347 |             out.add(resolveStage(stage, binding, evidence, flags));
  348 |         }
  349 |         return List.copyOf(out);
  350 |     }
  351 | 
  352 |     private static Binding bind(String stage) {
  353 |         return switch (stage) {
  354 |             case "analyze.selfask" -> Binding.SELF_ASK;
  355 |             case "retrieve.dynamicchain" -> Binding.RETRIEVAL;
  356 |             case "fuse.rrf.weighted" -> Binding.FUSION;
  357 |             case "rerank.biencoder" -> Binding.BIENCODER;
  358 |             case "rerank.crossencoder.onnx" -> Binding.ONNX;
  359 |             case "diversity.dpp" -> Binding.DPP;
  360 |             case "prompt.build", "answer.generate" -> Binding.DELEGATED;
  361 |             default -> Binding.NONE;
  362 |         };
  363 |     }
```

## E17 — ChatWorkflow의 UNKNOWN 허용과 FALSE-only 확장 제거
`current:main/java/com/example/lms/service/ChatWorkflow.java`
SHA-256: `7cc71484c305371b6e0d566047d027234360109a06a9d2cc75793947cb65c830`

행 1196–1208:
```text
 1196 |     static com.example.lms.plan.PlanExecutionSpec.StageFlags chatStageFlags(
 1197 |             OrchestrationHints hints,
 1198 |             java.util.Map<String, Object> metaHints,
 1199 |             com.example.lms.plan.PlanExecutionSpec.WhenVerdict planWhen) {
 1200 |         boolean selfAskOn = hints != null && hints.isEnableSelfAsk();
 1201 |         boolean onnxOn = hints != null && hints.isEnableCrossEncoder();
 1202 |         Object dpp = metaHints == null ? null : metaHints.get("diversity.dpp.enabled");
 1203 |         boolean diversityOn = dpp instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(dpp));
 1204 |         boolean expansionEligible = planWhen == null
 1205 |                 || planWhen.state() != com.example.lms.plan.PlanExecutionSpec.TriState.FALSE;
 1206 |         return new com.example.lms.plan.PlanExecutionSpec.StageFlags(
 1207 |                 selfAskOn, false, onnxOn, diversityOn, expansionEligible);
 1208 |     }
```

행 1458–1479:
```text
 1458 |                 if (planHintApplier != null) {
 1459 |                     planHints = planHintApplier.load(gctx.getPlanId());
 1460 |                     planHintApplier.applyToGuardContext(planHints, gctx);
 1461 |                     // plan.when execution gate (chat path): the plan's own activation
 1462 |                     // condition is evaluated against observable request scope. A
 1463 |                     // conclusively FALSE verdict suppresses plan-driven expansion
 1464 |                     // overrides; UNKNOWN never fabricates metric evidence.
 1465 |                     planExecSpec = planHintApplier.loadExecutionSpec(gctx.getPlanId());
 1466 |                     if (planExecSpec != null && !planExecSpec.isEmpty()) {
 1467 |                         planWhen = planExecSpec.evaluateWhen(chatPlanScope(gctx));
 1468 |                         TraceStore.put("plan.when", planWhen.state().name().toLowerCase(Locale.ROOT));
 1469 |                         TraceStore.put("plan.when.conditions", planWhen.debugView());
 1470 |                         if (!planExecSpec.pipeline().isEmpty()) {
 1471 |                             TraceStore.put("plan.pipeline.declared", planExecSpec.pipeline());
 1472 |                         }
 1473 |                         if (planWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.FALSE
 1474 |                                 && planExecSpec.declaresExpansion()) {
 1475 |                             stripPlanExpansionKeys(gctx.getPlanOverrides());
 1476 |                             TraceStore.put("plan.expansion.gated", "when_false");
 1477 |                         }
 1478 |                     }
 1479 |                 }
```

행 2140–2156:
```text
 2140 |             // plan-driven expansion while preserving caller-set flags; UNKNOWN and
 2141 |             // TRUE keep the plan's expansion knobs.
 2142 |             if (planExecSpec != null && planWhen != null
 2143 |                     && planWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.FALSE
 2144 |                     && planExecSpec.declaresExpansion()
 2145 |                     && hints != null && metaHints != null) {
 2146 |                 if (!callerEnableSelfAsk && hints.isEnableSelfAsk()) {
 2147 |                     hints.setEnableSelfAsk(false);
 2148 |                     TraceStore.put("plan.selfAsk.gated", "when_false");
 2149 |                     metaHints.put("plan.selfAsk.gated", "when_false");
 2150 |                 }
 2151 |                 metaHints.put("enableSelfAsk", String.valueOf(hints.isEnableSelfAsk()));
 2152 |                 stripPlanExpansionKeys(metaHints);
 2153 |                 metaHints.put("plan.expansion.gated", "when_false");
 2154 |             }
 2155 | 
 2156 |             // MERGE_HOOK:PROJ_AGENT::ORCH_STAGE_POLICY_CLAMP
```

## E18 — 실측 stage 번역과 lateActivation의 관측용 기록
`current:main/java/com/example/lms/service/ChatWorkflow.java`
SHA-256: `7cc71484c305371b6e0d566047d027234360109a06a9d2cc75793947cb65c830`

행 1145–1191:
```text
 1145 | 
 1146 |     /**
 1147 |      * Runtime evidence for the plan stage ledger on the chat path: translates
 1148 |      * the markers this path actually writes ({@code rag.fusion.sizes.*},
 1149 |      * {@code rag.selfask.count}, {@code retrieval.web.skipped}, {@code rerank.*})
 1150 |      * into the shared ledger evidence keys. Absent evidence stays absent —
 1151 |      * the ledger reports DECLARED/UNAVAILABLE rather than claiming execution.
 1152 |      */
 1153 |     static java.util.Map<String, Object> chatStageEvidence(
 1154 |             java.util.List<dev.langchain4j.rag.content.Content> topDocs,
 1155 |             String rerankBackend) {
 1156 |         java.util.Map<String, Object> evidence = new java.util.LinkedHashMap<>();
 1157 |         Number selfAskCount = traceNumber("rag.selfask.count");
 1158 |         if (selfAskCount != null && selfAskCount.intValue() > 0) {
 1159 |             evidence.put("selfAsk", "enabled");
 1160 |         }
 1161 |         if (Boolean.TRUE.equals(TraceStore.get("retrieval.web.skipped"))) {
 1162 |             evidence.put("stage.web", "disabled");
 1163 |         } else {
 1164 |             Number webSize = traceNumber("rag.fusion.sizes.web");
 1165 |             Number webOnly = traceNumber("fallback.webOnly.count");
 1166 |             int webTotal = (webSize == null ? 0 : webSize.intValue())
 1167 |                     + (webOnly == null ? 0 : webOnly.intValue());
 1168 |             if (webTotal > 0) {
 1169 |                 evidence.put("stage.web", "success:" + webTotal);
 1170 |             }
 1171 |         }
 1172 |         Number vecSize = traceNumber("rag.fusion.sizes.vector");
 1173 |         if (vecSize != null && vecSize.intValue() > 0) {
 1174 |             evidence.put("stage.vector", "success:" + vecSize.intValue());
 1175 |         }
 1176 |         Number kgSize = traceNumber("rag.fusion.sizes.kg");
 1177 |         if (kgSize != null && kgSize.intValue() > 0) {
 1178 |             evidence.put("stage.kg", "success:" + kgSize.intValue());
 1179 |         }
 1180 |         Object fusedCount = TraceStore.get("rag.fusion.final.totalCount");
 1181 |         if (fusedCount instanceof Number) {
 1182 |             evidence.put("stage.fuse", fusedCount);
 1183 |         }
 1184 |         Object rerankSkip = TraceStore.get("rerank");
 1185 |         if ("skipped_by_plate".equals(rerankSkip) || "skipped_by_plan".equals(rerankSkip)) {
 1186 |             evidence.put("stage.onnx", "skipped:" + rerankSkip);
 1187 |         } else if (Boolean.TRUE.equals(TraceStore.get("rerank.fallback"))) {
 1188 |             evidence.put("stage.onnx", "error:rerank_fallback");
 1189 |         } else if (topDocs != null && !topDocs.isEmpty()
 1190 |                 && "onnx".equalsIgnoreCase(String.valueOf(rerankBackend))) {
 1191 |             evidence.put("stage.onnx", Integer.valueOf(topDocs.size()));
```

행 2763–2809:
```text
 2763 | 
 2764 |         // Expose the final evidence sets (post rerank / retrieval) to the UI
 2765 |         // layer. Controllers may read these from TraceStore to render the
 2766 |         // "理쒖쥌 而⑦뀓?ㅽ듃" section without re-running retrieval.
 2767 |         try {
 2768 |             // Preserve "enabled" signal for the trace UI:
 2769 |             // - null : disabled (feature not used)
 2770 |             // - empty : enabled but no results
 2771 |             TraceStore.put("finalWebTopK",
 2772 |                     useWeb ? ((topDocs == null) ? java.util.Collections.emptyList() : topDocs) : null);
 2773 |             TraceStore.put("finalVectorTopK",
 2774 |                     useRag ? ((vectorDocs == null) ? java.util.Collections.emptyList() : vectorDocs) : null);
 2775 |         } catch (Exception ignore) {
 2776 |             ChatWorkflowTraceSuppressions.traceSuppressed("finalEvidence.trace", ignore);
 2777 |         }
 2778 | 
 2779 |         // plan.pipeline stage ledger (chat path): each declared stage is mapped to
 2780 |         // real runtime evidence only; unmapped or unevidenced stages stay honest.
 2781 |         try {
 2782 |             if (planExecSpec != null && !planExecSpec.isEmpty()) {
 2783 |                 java.util.Map<String, Object> postScope = chatPlanScope(gctx);
 2784 |                 postScope.putAll(chatPlanObservedMetrics());
 2785 |                 com.example.lms.plan.PlanExecutionSpec.WhenVerdict postWhen =
 2786 |                         planExecSpec.evaluateWhen(postScope);
 2787 |                 TraceStore.put("plan.when.post",
 2788 |                         postWhen.state().name().toLowerCase(Locale.ROOT));
 2789 |                 if (planWhen != null
 2790 |                         && planWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.UNKNOWN
 2791 |                         && postWhen.state() == com.example.lms.plan.PlanExecutionSpec.TriState.TRUE) {
 2792 |                     TraceStore.put("plan.when.lateActivation", true);
 2793 |                 }
 2794 |                 if (planExecSpec.pipeline() != null && !planExecSpec.pipeline().isEmpty()) {
 2795 |                     java.util.List<java.util.Map<String, Object>> planLedger = planExecSpec
 2796 |                             .stageLedger(
 2797 |                                     chatStageEvidence(topDocs, rerankKnobs == null ? null : rerankKnobs.backend()),
 2798 |                                     chatStageFlags(hints, metaHints, planWhen))
 2799 |                             .stream()
 2800 |                             .map(com.example.lms.plan.PlanExecutionSpec.StageEntry::debugView)
 2801 |                             .toList();
 2802 |                     TraceStore.put("plan.stageLedger", planLedger);
 2803 |                     if (metaHints != null) {
 2804 |                         metaHints.put("plan.stageLedger", planLedger);
 2805 |                     }
 2806 |                 }
 2807 |             }
 2808 |         } catch (Exception ignore) {
 2809 |             ChatWorkflowTraceSuppressions.traceSuppressed("plan.stageLedger", ignore);
```

## E19 — 현재 pipeline DTO에는 상세 stage ledger 필드 없음
`current:main/java/com/example/lms/dto/ChatStreamEvent.java`
SHA-256: `df02e1fba22038a2b8707555ab51f3fa03731539b551a84dff33cf838bf58021`

행 400–433:
```text
  400 |          * Redacted orchestration snapshot shared by trace/final events.
  401 |          *
  402 |          * <p>Security: counts, labels, and already-redacted reason codes only.
  403 |          * Raw queries, snippets, prompts, keys, tokens, and headers must not be
  404 |          * copied into this surface.</p>
  405 |          */
  406 |         public record PipelineSnapshot(
  407 |                 String planId,
  408 |                 String route,
  409 |                 String answerMode,
  410 |                 Long traceTurnId,
  411 |                 Integer webCount,
  412 |                 Integer vectorCount,
  413 |                 Integer finalContextCount,
  414 |                 Double citationCoverage,
  415 |                 Double finalSigmoid,
  416 |                 String failureClass,
  417 |                 String disabledReason
  418 |         ) {
  419 |                 public PipelineSnapshot {
  420 |                         planId = cleanSignal(planId);
  421 |                         route = cleanSignal(route);
  422 |                         answerMode = cleanSignal(answerMode);
  423 |                         traceTurnId = nonNegative(traceTurnId);
  424 |                         webCount = nonNegative(webCount);
  425 |                         vectorCount = nonNegative(vectorCount);
  426 |                         finalContextCount = nonNegative(finalContextCount);
  427 |                         citationCoverage = clamp01(citationCoverage);
  428 |                         finalSigmoid = clamp01(finalSigmoid);
  429 |                         failureClass = cleanSignal(failureClass);
  430 |                         disabledReason = cleanReason(disabledReason);
  431 |                 }
  432 |         }
  433 | 
```

## E20 — pipeline snapshot의 실측/대체 값 projection 경로
`current:main/java/com/example/lms/api/ChatStreamSignalBuilder.java`
SHA-256: `90f72513e41d2dae5b0e89b592c786d548d1644290eb75d794f513fc1de0213b`

행 80–145:
```text
   80 |     static ChatStreamEvent.PipelineSnapshot buildPipelineSnapshot(
   81 |             Map<String, Object> meta,
   82 |             String answerMode,
   83 |             Long traceTurnId,
   84 |             ChatStreamEvent.TraceSignal traceSignal) {
   85 |         Map<String, Object> safeMeta = meta == null ? Map.of() : meta;
   86 |         Integer webCount = firstNonNull(
   87 |                 countValue(safeMeta.get("webCount")),
   88 |                 countValue(safeMeta.get("web.count")),
   89 |                 countValue(safeMeta.get("finalWebTopKCount")),
   90 |                 collectionSizeOrNull(safeMeta.get("finalWebTopK")));
   91 |         Integer vectorCount = firstNonNull(
   92 |                 countValue(safeMeta.get("vectorCount")),
   93 |                 countValue(safeMeta.get("vector.count")),
   94 |                 countValue(safeMeta.get("finalVectorTopKCount")),
   95 |                 collectionSizeOrNull(safeMeta.get("finalVectorTopK")));
   96 |         Integer finalContextCount = firstNonNull(
   97 |                 countValue(safeMeta.get("finalContextCount")),
   98 |                 countValue(safeMeta.get("final.context.count")),
   99 |                 countValue(safeMeta.get("prompt.context.count")));
  100 |         if (finalContextCount == null && (webCount != null || vectorCount != null)) {
  101 |             finalContextCount = Math.max(0, webCount == null ? 0 : webCount) + Math.max(0, vectorCount == null ? 0 : vectorCount);
  102 |         }
  103 | 
  104 |         String failureClass = firstNonBlank(
  105 |                 traceSignal == null ? null : traceSignal.failureClass(),
  106 |                 safeString(safeMeta.get("failureClass")),
  107 |                 safeString(safeMeta.get("lastFailureReason")),
  108 |                 safeString(safeMeta.get("rag.eval.failureClass")),
  109 |                 providerCancellationFailureClass(safeMeta));
  110 |         String disabledReason = firstNonBlank(
  111 |                 safeString(safeMeta.get("llm.final.skipped")),
  112 |                 safeString(safeMeta.get("disabledReason")),
  113 |                 safeString(safeMeta.get("disabledReasonCanonical")),
  114 |                 safeString(safeMeta.get("web.naver.disabledReasonCanonical")),
  115 |                 safeString(safeMeta.get("web.brave.disabledReasonCanonical")),
  116 |                 safeString(safeMeta.get("web.serpapi.disabledReasonCanonical")),
  117 |                 safeString(safeMeta.get("web.tavily.disabledReasonCanonical")),
  118 |                 safeString(safeMeta.get("selfask.3way.api.disabledReason")),
  119 |                 safeString(safeMeta.get("llmrouter.api.disabledReason")));
  120 | 
  121 |         ChatStreamEvent.PipelineSnapshot snapshot = new ChatStreamEvent.PipelineSnapshot(
  122 |                 firstNonBlank(
  123 |                         safeString(safeMeta.get("plan.id")),
  124 |                         safeString(safeMeta.get("plan.auto")),
  125 |                         safeString(safeMeta.get("planId")),
  126 |                         safeString(safeMeta.get("planApplied"))),
  127 |                 firstNonBlank(
  128 |                         safeString(safeMeta.get("route")),
  129 |                         safeString(safeMeta.get("rag.route")),
  130 |                         safeString(safeMeta.get("rag.route.hint")),
  131 |                         safeString(safeMeta.get("answer.route"))),
  132 |                 firstNonBlank(answerMode, safeString(safeMeta.get("answer.mode"))),
  133 |                 traceTurnId,
  134 |                 webCount,
  135 |                 vectorCount,
  136 |                 finalContextCount,
  137 |                 firstNonNull(
  138 |                         asDouble(safeMeta.get("citationCoverage"), null),
  139 |                         asDouble(safeMeta.get("citation.coverage"), null),
  140 |                         asDouble(safeMeta.get("rag.citation.coverage"), null),
  141 |                         asDouble(safeMeta.get("citationGate.coverage"), null)),
  142 |                 firstNonNull(
  143 |                         asDouble(safeMeta.get("finalSigmoid"), null),
  144 |                         asDouble(safeMeta.get("finalSigmoid.score"), null),
  145 |                         asDouble(safeMeta.get("gate.finalSigmoid.score"), null),
```

## E21 — 현재 memory-only/empty fallback과 단일 PromptBuilder
`current:main/java/com/example/lms/service/rag/ContextOrchestrator.java`
SHA-256: `85fd1c54bd2a4760ab9ff0bfe590257577711a19f4791234a361e8c3313ed775`

행 97–111:
```text
   97 | 
   98 |         String promptMemoryCtx = memoryCtx;
   99 |         traceMemoryFingerprint("raw_snapshot", "before_memory_compression", query, promptMemoryCtx, null);
  100 |         if (shouldActivateMemoryCompression(query, promptMemoryCtx)) {
  101 |             try {
  102 |                 promptMemoryCtx = promptContextCompressor.compressMemoryForPrompt(query, promptMemoryCtx);
  103 |                 TraceStore.put("prompt.memory.composer.applied", true);
  104 |             } catch (NullPointerException | IllegalArgumentException | IllegalStateException ex) {
  105 |                 TraceStore.put("prompt.memory.composer.applied", false);
  106 |                 traceMemoryComposerSkipped("exception_fail_soft");
  107 |                 log.debug("[ContextOrchestrator] compressMemoryForPrompt fail-soft. errorHash={} errorLength={}",
  108 |                         SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
  109 |                 // fail-soft: keep original memory context
  110 |             }
  111 |         }
```

행 134–181:
```text
  134 |         if (merged.isEmpty() && hasMemory) {
  135 |             traceContextResult(0, "memory_only_no_candidates");
  136 |             traceMemoryFingerprint("load", "memory_only_no_candidates", query, promptMemoryCtx,
  137 |                     checkpointDetails("memory_only_no_candidates", 0, 0, 0));
  138 |             PromptContext ctx = PromptContext.builder()
  139 |                     .rag(List.of())
  140 |                     .web(List.of())
  141 |                     .memory(promptMemoryCtx)
  142 |                     .domain("GENERAL")
  143 |                     .intent("GENERAL")
  144 |                     .interactionRules(interactionRules == null ? Map.of() : interactionRules)
  145 |                     .verbosityHint(profile == null ? null : profile.hint())
  146 |                     .minWordCount(profile == null ? null : profile.minWordCount())
  147 |                     .targetTokenBudgetOut(profile == null ? null : profile.targetTokenBudgetOut())
  148 |                     .sectionSpec(profile == null ? null : profile.sections())
  149 |                     .audience(profile == null ? null : profile.audience())
  150 |                     .citationStyle(profile == null ? null : profile.citationStyle())
  151 |                     .build();
  152 |             return promptBuilder.build(ctx);
  153 |         }
  154 | 
  155 |         // 3) empty handling
  156 |         // [FUTURE_TECH FIX] Do NOT early-return '정보 없음' here; delegate graceful handling to Prompt/Guard layers.
  157 |         // Build an empty-context prompt so the assistant can respond based on system policies.
  158 |         if (merged.isEmpty()) {
  159 |             traceContextResult(0, "empty_candidates");
  160 |             traceMemoryFingerprint("load", "empty_candidates", query, promptMemoryCtx,
  161 |                     checkpointDetails("empty_candidates", 0, 0, 0));
  162 |             PromptContext ctx = PromptContext.builder()
  163 |                     .rag(List.of())
  164 |                     .web(List.of())
  165 |                     .memory(promptMemoryCtx)
  166 |                     .domain("GENERAL")
  167 |                     .intent("GENERAL")
  168 |                     .interactionRules(interactionRules == null ? Map.of() : interactionRules)
  169 |                     .verbosityHint(profile == null ? null : profile.hint())
  170 |                     .minWordCount(profile == null ? null : profile.minWordCount())
  171 |                     .targetTokenBudgetOut(profile == null ? null : profile.targetTokenBudgetOut())
  172 |                     .sectionSpec(profile == null ? null : profile.sections())
  173 |                     .audience(profile == null ? null : profile.audience())
  174 |                     .citationStyle(profile == null ? null : profile.citationStyle())
  175 |                     .build();
  176 |             return promptBuilder.build(ctx);
  177 |         }
  178 | 
  179 |         // 4) dedupe (text 기반)
  180 |         LinkedHashMap<String, Content> uniq = new LinkedHashMap<>();
  181 |         for (Content c : merged) {
```

행 205–237:
```text
  205 |                 .limit(Math.max(1, cap))
  206 |                 .toList();
  207 | 
  208 |         List<Content> finalWeb = new ArrayList<>();
  209 |         List<Content> finalRag = new ArrayList<>();
  210 |         splitPromptDocs(finalDocs, finalWeb, finalRag);
  211 |         String contextRefinementSummary = "";
  212 |         Map<String, Double> contextRefinementSignals = Map.of();
  213 |         if (promptContextCompressor != null && allPromptDocsAlreadyCompressed(finalWeb, finalRag)) {
  214 |             tracePromptComposerSkipped("already_compressed");
  215 |         } else if (promptContextCompressor != null) {
  216 |             if (shouldActivatePromptCompression(query, finalDocs)) {
  217 |                 finalDocs = maybeApplyOverdriveAnchorNarrowing(query, finalDocs);
  218 |                 finalWeb = new ArrayList<>();
  219 |                 finalRag = new ArrayList<>();
  220 |                 splitPromptDocs(finalDocs, finalWeb, finalRag);
  221 |                 try {
  222 |                     DynamicContextCompressor.PromptContextComposition composition =
  223 |                             promptContextCompressor.composeForPrompt(query, finalWeb, finalRag);
  224 |                     if (composition != null) {
  225 |                         finalWeb = composition.web() == null ? new ArrayList<>() : new ArrayList<>(composition.web());
  226 |                         finalRag = composition.rag() == null ? new ArrayList<>() : new ArrayList<>(composition.rag());
  227 |                         contextRefinementSummary = composition.contextRefinementSummary();
  228 |                         contextRefinementSignals = composition.contextRefinementSignals();
  229 |                     }
  230 |                 } catch (Exception ex) {
  231 |                     TraceStore.put("orchestrator.compress.skipReason", "exception_fail_soft");
  232 |                     log.debug("[ContextOrchestrator] composeForPrompt fail-soft. errorHash={} errorLength={}",
  233 |                             SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
  234 |                     // fail-soft: keep original assembled context
  235 |                 }
  236 |             }
  237 |         }
```

행 246–267:
```text
  246 |         PromptContext ctx = PromptContext.builder()
  247 |                 .rag(finalRag)
  248 |                 // 웹/벡터 분리는 retrieval 단계에서 결정해야 하므로 여기서는 빈 리스트 유지
  249 |                 .web(finalWeb)
  250 |                 .memory(promptMemoryCtx)
  251 |                 .domain("GENERAL")
  252 |                 .intent("GENERAL")
  253 |                 .interactionRules(interactionRules == null ? Map.of() : interactionRules)
  254 |                 .verbosityHint(profile == null ? null : profile.hint())
  255 |                 .minWordCount(profile == null ? null : profile.minWordCount())
  256 |                 .targetTokenBudgetOut(profile == null ? null : profile.targetTokenBudgetOut())
  257 |                 .sectionSpec(profile == null ? null : profile.sections())
  258 |                 .audience(profile == null ? null : profile.audience())
  259 |                 .citationStyle(profile == null ? null : profile.citationStyle())
  260 |                 .contextRefinementSummary(contextRefinementSummary)
  261 |                 .contextRefinementSignals(contextRefinementSignals)
  262 |                 .build();
  263 | 
  264 |         traceMemoryFingerprint("load", "prompt_builder", query, promptMemoryCtx,
  265 |                 checkpointDetails("prompt_builder", finalContextCount, finalWeb.size(), finalRag.size()));
  266 |         return promptBuilder.build(ctx);
  267 |     }
```

## E22 — 현재 compressor의 조건부 압축/밸런스/원본 복구
`current:main/java/ai/abandonware/nova/orch/compress/DynamicContextCompressor.java`
SHA-256: `514a04e9206d08128e13b07193d4cdb979b2fd9d8ce18d4be30e4018be80225e`

행 229–246:
```text
  229 |     public PromptContextComposition composeForPrompt(String query, List<Content> webDocs, List<Content> ragDocs) {
  230 |         int inputWebCount = sizeOf(webDocs);
  231 |         int inputRagCount = sizeOf(ragDocs);
  232 |         NovaOrchestrationProperties.RagCompressorProps cfg = props != null ? props.getRagCompressor() : null;
  233 |         ContextRefinerDecision refinerDecision = contextRefinerDecision(cfg, TraceStore.getAll());
  234 |         traceContextRefiner(refinerDecision);
  235 |         boolean enabled = cfg != null && cfg.isEnabled() && cfg.isAblationGuidedEnabled();
  236 |         if (!enabled) {
  237 |             CompositionDecision decision = decision(false, false, "disabled", 0.0d, "none", query,
  238 |                     inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), false);
  239 |             tracePromptComposition(decision);
  240 |             return promptComposition(webDocs, ragDocs, decision, refinerDecision);
  241 |         }
  242 |         if (inputWebCount + inputRagCount <= 0) {
  243 |             CompositionDecision decision = decision(true, false, "empty_input", 0.0d, "none", query,
  244 |                     inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), false);
  245 |             tracePromptComposition(decision);
  246 |             return promptComposition(webDocs, ragDocs, decision, refinerDecision);
```

행 261–275:
```text
  261 |             boolean starvation = starvationFlag(trace);
  262 |             double pressure = clamp01(max(blackboxRisk, componentRisk.topProbability(), overdriveScore,
  263 |                     traceAnchorPressure, promptOverflow, starvation ? 0.75d : 0.0d));
  264 |             double threshold = clamp01(cfg.getAblationPressureThreshold());
  265 |             boolean activated = pressure >= threshold || promptOverflow > 0.0d || traceBoolean(trace, "rag.compress.applied");
  266 |             String topFactor = firstNonBlank(nonNone(componentRisk.topFactor()),
  267 |                     nonNone(nestedString(trace, "blackbox.risk.traceAnchor", "routeHint")),
  268 |                     safeLabel(trace.get("blackbox.risk.dominantFailure")),
  269 |                     safeLabel(trace.get("blackbox.risk.hotspot")),
  270 |                     "none");
  271 |             if (!activated) {
  272 |                 CompositionDecision decision = decision(true, false, "below_threshold", pressure, topFactor, query,
  273 |                         inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), false);
  274 |                 tracePromptComposition(decision);
  275 |                 return promptComposition(webDocs, ragDocs, decision, refinerDecision);
```

행 313–338:
```text
  313 |                     .thenComparingInt(ScoredContent::bucketIndex));
  314 | 
  315 |             int total = scored.size();
  316 |             int maxKeep = Math.min(
  317 |                     Math.max(1, cfg.getMaxContents()),
  318 |                     Math.max(1, cfg.getMaxDocs()));
  319 |             int minKeep = Math.max(1, cfg.getMinDocs());
  320 |             int keepN = Math.min(total, Math.max(minKeep, maxKeep));
  321 | 
  322 |             List<ScoredContent> baselineSelected = selectBalanced(scored, keepN, inputWebCount > 0, inputRagCount > 0);
  323 |             AnchorProbeHandler.Selection anchorProbe = anchorProbeHandler.select(scored, baselineSelected, cfg,
  324 |                     inputWebCount > 0, inputRagCount > 0);
  325 |             List<ScoredContent> selected = anchorProbe.selected();
  326 |             boolean anchorProbeApplied = anchorProbe.applied() && !anchorProbe.failSoft();
  327 |             List<Content> outWeb = new ArrayList<>();
  328 |             List<Content> outRag = new ArrayList<>();
  329 |             int penalized = 0;
  330 |             for (ScoredContent candidate : selected) {
  331 |                 if (candidate.penalty() > 0.0d) {
  332 |                     penalized++;
  333 |                 }
  334 |                 Content annotated = prepareForPrompt(candidate, anchor, cfg,
  335 |                         anchorProbeApplied, anchorProbe.finalCap(), anchorProbeApplied, anchorProbe.probeMode());
  336 |                 if ("web".equals(candidate.bucket())) {
  337 |                     outWeb.add(annotated);
  338 |                 } else {
```

행 341–373:
```text
  341 |             }
  342 | 
  343 |             if (selected.isEmpty()) {
  344 |                 CompositionDecision decision = decision(true, true, "empty_output_original_returned", pressure, topFactor,
  345 |                         query, inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), true);
  346 |                 tracePromptComposition(decision);
  347 |                 anchorProbeHandler.trace(anchorProbe, query, total, 0);
  348 |                 return promptComposition(webDocs, ragDocs, decision, refinerDecision);
  349 |             }
  350 | 
  351 |             Map<String, Integer> dropCounts = new LinkedHashMap<>();
  352 |             dropCounts.put("web", Math.max(0, inputWebCount - outWeb.size()));
  353 |             dropCounts.put("rag", Math.max(0, inputRagCount - outRag.size()));
  354 |             dropCounts.put("penalized", Math.max(0, penalized));
  355 | 
  356 |             String reason = reasonFor(pressure, promptOverflow, starvation, topFactor);
  357 |             CompositionDecision decision = decision(true, true, reason, pressure, topFactor, query,
  358 |                     inputWebCount, inputRagCount, outWeb.size(), outRag.size(), dropCounts, false);
  359 |             tracePromptComposition(decision);
  360 |             anchorProbeHandler.trace(anchorProbe, query, total, selected.size());
  361 |             return promptComposition(webDocs == null ? null : outWeb, ragDocs == null ? null : outRag,
  362 |                     decision, refinerDecision);
  363 |         } catch (Exception e) {
  364 |             CompositionDecision decision = decision(true, false, "exception_original_returned", 0.0d, "exception", query,
  365 |                     inputWebCount, inputRagCount, inputWebCount, inputRagCount, Map.of(), true);
  366 |             tracePromptComposition(decision);
  367 |             TraceStore.put("prompt.context.composer.exception", "prompt_context_composer_failed");
  368 |             log.debug("[DynamicContextCompressor] composeForPrompt fail-soft errorHash={} errorLength={}",
  369 |                     SafeRedactor.hashValue(messageOf(e)), messageLength(e));
  370 |             return promptComposition(webDocs, ragDocs, decision, refinerDecision);
  371 |         }
  372 |     }
  373 | 
```

## E23 — NineArtPlateGate의 synthetic samples 및 rollout 호출
`current:main/java/com/example/lms/artplate/NineArtPlateGate.java`
SHA-256: `a47c593adf20103ce4149497bc46b3565c0f5233474b2efe6318bb5ec9566cb9`

행 60–86:
```text
   60 |         ArtPlateSpec base = moeStrategyBasePlate();
   61 |         if (base == null) {
   62 |             base = baseDecision(ctx);
   63 |         }
   64 |         Candidate scored = bestCandidate(ctx, base);
   65 |         ArtPlateSpec candidate = proposeCandidate(scored.plate(), scored.scoreCard());
   66 |         ArtPlateEvolver.RolloutDecision rollout;
   67 |         ArtPlateSpec selected;
   68 |         try {
   69 |             rollout = evolver.abTest(candidate, scored.scoreCard());
   70 |             selected = evolver.shouldRouteToCandidate(rollout, rolloutKey(ctx, base, scored.plate()))
   71 |                     ? rollout.candidate()
   72 |                     : base;
   73 |         } catch (RuntimeException ex) {
   74 |             TraceStore.put("moe.evolver.error", ex == null ? "unknown" : ex.getClass().getSimpleName());
   75 |             log.debug("[NineArtPlateGate] Art plate rollout fail-soft. errorHash={} errorLength={}",
   76 |                     SafeRedactor.hashValue(messageOf(ex)), messageLength(ex));
   77 |             rollout = new ArtPlateEvolver.RolloutDecision(candidate, 0.0d, 0, false, "evolver_error");
   78 |             selected = base != null ? base : (scored.plate() != null ? scored.plate() : emergencyCostSaverPlate());
   79 |         }
   80 |         lastSelected = selected;
   81 |         traceSelection(ctx, base, candidate, selected, rollout, scored.scoreCard(), scored.evaluatedCount());
   82 |         return selected;
   83 |     }
   84 | 
   85 |     public ArtPlateSpec getLastSelected() {
   86 |         return lastSelected;
```

행 169–191:
```text
  169 | 
  170 |     private ArtPlateEvolver.ScoreCard scoreCard(PlateContext ctx, ArtPlateSpec candidate) {
  171 |         if (ctx == null) {
  172 |             return ArtPlateEvolver.ScoreCard.neutral();
  173 |         }
  174 |         double authority = ctx.authority();
  175 |         double novelty = Math.max(ctx.recallNeed(), Math.max(ctx.webGate(), ctx.vectorGate()));
  176 |         double fusionDiversity = Math.max(ctx.webGate(), Math.max(ctx.vectorGate(), ctx.memoryGate()));
  177 |         double match = plateMatch(ctx, candidate);
  178 |         double latencyPenalty = candidate == null
  179 |                 ? 0.10d
  180 |                 : ((double) Math.max(0, candidate.webBudgetMs() + candidate.vecBudgetMs())) / 8_000.0d;
  181 |         double errorPenalty = ctx.noisy() ? 0.45d : (authority < 0.25d ? 0.20d : 0.03d);
  182 |         int samples = Math.max(0, ctx.evidenceCount() * 5 + ctx.sessionRecur());
  183 |         return new ArtPlateEvolver.ScoreCard(
  184 |                 authority,
  185 |                 novelty,
  186 |                 fusionDiversity,
  187 |                 match,
  188 |                 latencyPenalty,
  189 |                 errorPenalty,
  190 |                 samples);
  191 |     }
```

## E24 — 실험 5/15/50%를 결정하는 heuristic scorecard
`current:main/java/com/example/lms/artplate/ArtPlateEvolver.java`
SHA-256: `237c599efc97d0459fd37db286ecb6e313df346af8448ee0b8210696647aaf95`

행 224–261:
```text
  224 | 
  225 |     public RolloutDecision abTest(ArtPlateSpec candidate, ScoreCard scoreCard) {
  226 |         ScoreCard card = scoreCard == null ? ScoreCard.neutral() : scoreCard;
  227 |         if (candidate == null) {
  228 |             TraceStore.put("moe.evolver.candidateNull", true);
  229 |             RolloutDecision decision = new RolloutDecision(null, 0.0d, 0, false, "candidate_null");
  230 |             traceRollout(decision, card);
  231 |             traceEvolutionSkipped("candidate_null");
  232 |             return decision;
  233 |         }
  234 |         TraceStore.put("moe.evolver.candidateNull", false);
  235 |         double score = card.composite();
  236 |         int percent;
  237 |         String reason;
  238 |         String gateReason = adoptionGateReason(candidate, card);
  239 |         if (!gateReason.isBlank()) {
  240 |             percent = 0;
  241 |             reason = gateReason;
  242 |         } else if (score >= 0.80d && card.samples() >= 10) {
  243 |             percent = 50;
  244 |             reason = "scorecard_promote_50";
  245 |         } else if (score >= 0.62d && card.samples() >= 8) {
  246 |             percent = 15;
  247 |             reason = "scorecard_promote_15";
  248 |         } else if (score >= 0.50d) {
  249 |             percent = 5;
  250 |             reason = "scorecard_canary_5";
  251 |         } else {
  252 |             percent = 0;
  253 |             reason = "scorecard_below_rollout_floor";
  254 |         }
  255 |         RolloutDecision decision = new RolloutDecision(candidate, score, percent, percent > 0, reason);
  256 |         traceRollout(decision, card);
  257 |         persistEvolution(decision, card);
  258 |         return decision;
  259 |     }
  260 | 
  261 |     public boolean shouldRouteToCandidate(RolloutDecision decision) {
```

행 423–441:
```text
  423 |     private static String adoptionGateReason(ArtPlateSpec candidate, ScoreCard card) {
  424 |         if (candidate == null) {
  425 |             return "candidate_null";
  426 |         }
  427 |         ScoreCard safeCard = card == null ? ScoreCard.neutral() : card;
  428 |         if (Math.max(0, safeCard.samples()) < Math.max(1, candidate.minEvidence())) {
  429 |             return "evidence_gate_failed";
  430 |         }
  431 |         if (clamp01(safeCard.authority()) < clamp01(candidate.authorityFloor())) {
  432 |             return "authority_gate_failed";
  433 |         }
  434 |         if (clamp01(safeCard.match()) < 0.50d) {
  435 |             return "citation_gate_failed";
  436 |         }
  437 |         return "";
  438 |     }
  439 | 
  440 |     private static boolean within(int value, int low, int high) {
  441 |         return value >= low && value <= high;
```

## E25 — attachment identity와 session scope; IQR은 현재 단일 pass
`current:main/java/com/example/lms/service/rag/chain/AttachmentContextHandler.java`
SHA-256: `82e347438f10c4f5e7be45a2780e51ba32b25f224325dc255a9a840fcca02623`

행 49–82:
```text
   49 |     @Override
   50 |     public ChainOutcome handle(ChainContext ctx, Chain next) {
   51 |         try {
   52 |             // Look up any attachments associated with the current session.  When
   53 |             // attachments exist, invoke ctx.withAttachment for each so that
   54 |             // downstream prompt builders can incorporate summaries or previews.
   55 |             AttachmentOwnerIdentity ownerIdentity = ctx == null ? null : ctx.attachmentOwnerIdentity();
   56 |             var atts = attachmentService == null || ownerIdentity == null
   57 |                     ? java.util.List.<com.example.lms.dto.AttachmentDto>of()
   58 |                     : attachmentService.findBySession(ctx.sessionId(), ownerIdentity);
   59 |             int activeCount = 0;
   60 |             int skippedCount = 0;
   61 |             List<String> attachmentIds = new ArrayList<>();
   62 |             if (atts != null) {
   63 |                 for (var att : atts) {
   64 |                     try {
   65 |                         ctx.withAttachment(att);
   66 |                         if (att != null && att.id() != null && !att.id().isBlank()) {
   67 |                             attachmentIds.add(att.id());
   68 |                         }
   69 |                         activeCount++;
   70 |                     } catch (Exception attachEx) {
   71 |                         skippedCount++;
   72 |                         TraceStore.put("cihRag.attachment.skipReason." + skippedCount,
   73 |                                 SafeRedactor.traceLabelOrFallback(attachEx.getClass().getSimpleName(), "unknown_attach_error"));
   74 |                         log.debug("[AWX][rag][chain] attachment skip errorType={} skippedTotal={}",
   75 |                                 SafeRedactor.traceLabelOrFallback(attachEx.getClass().getSimpleName(), "unknown"),
   76 |                                 skippedCount);
   77 |                     }
   78 |                 }
   79 |             }
   80 |             CihPipelineTrace pipeline = runIqrPipeline(ctx, attachmentIds, ownerIdentity);
   81 |             traceCihRag(activeCount, skippedCount, pipeline);
   82 |             return next.proceed(ctx);
```

행 94–126:
```text
   94 |             ChainContext ctx,
   95 |             List<String> attachmentIds,
   96 |             AttachmentOwnerIdentity ownerIdentity) {
   97 |         if (attachmentService == null) {
   98 |             return CihPipelineTrace.disabled("attachment_service_unavailable");
   99 |         }
  100 |         if (attachmentIds == null || attachmentIds.isEmpty()) {
  101 |             return CihPipelineTrace.disabled("no_attachments");
  102 |         }
  103 |         if (ownerIdentity == null) {
  104 |             return CihPipelineTrace.disabled("missing_attachment_owner");
  105 |         }
  106 |         try {
  107 |             List<Document> docs = attachmentService.asDocumentsForSession(
  108 |                     attachmentIds,
  109 |                     ctx == null ? null : ctx.sessionId(),
  110 |                     ownerIdentity);
  111 |             int iterations = 1;
  112 |             if (docs == null || docs.isEmpty()) {
  113 |                 return CihPipelineTrace.empty(iterations, "no_attachment_docs");
  114 |             }
  115 |             List<Document> refined = new ArrayList<>(docs);
  116 |             String query = ctx == null ? "" : ctx.userMessage();
  117 |             CihPipelineTrace trace = CihPipelineTrace.pipeline(iterations, refined.size());
  118 |             refined = applyCrossEncoder("bi_encoder", biEncoderReranker, query, refined, trace);
  119 |             refined = applyCrossEncoder("onnx", onnxReranker, query, refined, trace);
  120 |             refined = applyDpp(query, refined, trace);
  121 |             if (ctx != null && !refined.isEmpty()) {
  122 |                 ctx.withLocalDocs(refined);
  123 |             }
  124 |             trace.localDocCount = refined.size();
  125 |             return trace;
  126 |         } catch (Exception ex) {
```

## E26 — graph의 owner/session/consent/revision gate
`current:main/java/com/example/lms/service/rag/graph/GeneralGraphVectorGate.java`
SHA-256: `478db273b1e1c7d66290ce028286bda36e3b0efa2f05dfd13fdf78f007820dcd`

행 34–83:
```text
   34 |     public static boolean commit(GeneralGraphVectorGate gate, String sid, Map<String, Object> meta, Runnable write) {
   35 |         if (!requiresGate(meta) || publicManual(meta)) {
   36 |             write.run();
   37 |             return true;
   38 |         }
   39 |         return gate != null && gate.commitPrivate(sid, meta, write);
   40 |     }
   41 | 
   42 |     private boolean commitPrivate(String sid, Map<String, Object> meta, Runnable write) {
   43 |         GeneralGraphScope scope = claim(meta);
   44 |         if (scope == null || sid == null
   45 |                 || !(sid.equals(Long.toString(scope.sessionId()))
   46 |                      || sid.startsWith(scope.sessionId() + "#") || sid.startsWith(scope.sessionId() + "~")))
   47 |             return false;
   48 |         var source = current(scope, meta);
   49 |         if (source.isEmpty()) return false;
   50 |         return authority.withCurrentSource(scope, source.get(), current -> {
   51 |             write.run();
   52 |             return true;
   53 |         }).orElse(false);
   54 |     }
   55 | 
   56 |     public static Optional<Content> content(GeneralGraphVectorGate gate, GeneralGraphScope requested,
   57 |                                              TextSegment segment) {
   58 |         if (segment == null) return Optional.empty();
   59 |         Map<String, Object> meta = segment.metadata().toMap();
   60 |         if (!requiresGate(meta) || publicManual(meta)) return Optional.of(Content.from(segment));
   61 |         if (gate == null || requested == null || !requested.memoryEnabled()) return Optional.empty();
   62 |         GeneralGraphScope stored = claim(meta);
   63 |         if (stored == null || !stored.indexNamespace().equals(requested.indexNamespace())) return Optional.empty();
   64 |         return gate.current(requested, meta).map(gate.authority::evidenceContent);
   65 |     }
   66 | 
   67 |     private Optional<MemoryEvidence> current(GeneralGraphScope scope, Map<String, Object> meta) {
   68 |         long revision = positive(meta.get(PREFIX + "source_revision"));
   69 |         if (revision <= 0) return Optional.empty();
   70 |         return authority.source(scope, new KgChunk.SourceRef(value(meta, PREFIX + "source_id"), revision));
   71 |     }
   72 | 
   73 |     private static GeneralGraphScope claim(Map<String, Object> meta) {
   74 |         if (meta == null || !"true".equals(value(meta, PREFIX + "private"))) return null;
   75 |         try {
   76 |             return GeneralGraphScope.indexClaim(value(meta, PREFIX + "owner_namespace"),
   77 |                     positive(meta.get(PREFIX + "session_id")), positive(meta.get(PREFIX + "consent_epoch")));
   78 |         } catch (IllegalArgumentException invalid) {
   79 |             return null;
   80 |         }
   81 |     }
   82 |     private static String value(Map<String, Object> meta, String key) {
   83 |         Object value = meta.get(key);
```

## E27 — Jev finite concurrency/time budget 및 무료 기간 방어
`current:main/java/com/example/lms/assist/JevDecisionAdvisor.java`
SHA-256: `1c558dfd09234cc1f9662c6cbd43a0dd5de3b77795843fd584681e628b5d4452`

행 7–18:
```text
    7 | import org.springframework.util.StringUtils;
    8 | import java.time.Clock;
    9 | import java.util.*;
   10 | import java.util.concurrent.*;
   11 | import java.util.concurrent.atomic.AtomicBoolean;
   12 | import java.util.function.Function;
   13 | 
   14 | /**
   15 |  * Jev (typesafe-ai/jev) fast decision signal: one bounded Gateway /v1/evaluate call per
   16 |  * confirmed question, verdict passed to the existing search/answer path. Never generates
   17 |  * text, never touches the PCM/voice/reconnect hot path, and fails open to the
   18 |  * deterministic local rules on any gap. mode: off | shadow | on (demo.jev.mode).
```

행 78–116:
```text
   78 |      * One evaluation per confirmed question. shadow submits a bounded async task and
   79 |      * returns immediately; on waits at most decision-wait-ms. Every failure defers.
   80 |      */
   81 |     public Advice advise(String surface,String question,String baseline){
   82 |         String mode=mode();
   83 |         if("off".equals(mode))return Advice.off();
   84 |         if(!configured())return Advice.defer("jev_not_configured",mode);
   85 |         if(authBlocked.get())return Advice.defer("auth_blocked",mode);
   86 |         if(clock.millis()<rateLimitedUntilMs)return Advice.defer("rate_limited",mode);
   87 |         if(!budgetAdmitted())return Advice.defer("budget_skip",mode);
   88 |         if(!inFlight.tryAcquire())return Advice.defer("busy",mode);
   89 |         var req=new EvalRequest(endpoint(),model(),credentialEnv(),bounded(question),
   90 |                 surface==null?"unknown":surface,baseline==null?"":baseline,
   91 |                 intProp("connect-timeout-ms",250,50,5000),intProp("request-timeout-ms",800,50,10000),
   92 |                 intProp("max-state-bytes",8192,1024,65536),intProp("max-response-bytes",65536,1024,1048576));
   93 |         if("shadow".equals(mode)){
   94 |             pool.execute(()->{
   95 |                 long began=clock.millis();String decision="defer",reason="transport_error";
   96 |                 try{
   97 |                     var res=transport.evaluate(req);remember(res);
   98 |                     if(res.verdict()!=null){decision=res.verdict().name();reason="ok";}
   99 |                     else reason=res.failure()==null?"invalid_response":res.failure();
  100 |                 }catch(RuntimeException failure){/* reason stays transport_error */}
  101 |                 finally{inFlight.release();}
  102 |                 LOG.info("[AWX][jev] surface={} mode=shadow decision={} reasonCode={} latencyMs={}",surface,decision,reason,clock.millis()-began);
  103 |             });
  104 |             return Advice.defer("shadow",mode);
  105 |         }
  106 |         long began=clock.millis();
  107 |         var future=CompletableFuture.supplyAsync(()->{
  108 |             try{return transport.evaluate(req);}
  109 |             catch(RuntimeException failure){return new EvalResponse(0,null,"transport_error",null);}
  110 |             finally{inFlight.release();}
  111 |         },pool);
  112 |         try{
  113 |             var res=future.get(intProp("decision-wait-ms",150,0,5000),TimeUnit.MILLISECONDS);
  114 |             remember(res);
  115 |             if(res.verdict()==null)return Advice.defer(res.failure()==null?"invalid_response":res.failure(),mode);
  116 |             return Advice.of(res.verdict(),mode,clock.millis()-began);
```

행 128–147:
```text
  128 |         if("rate_limited".equals(res.failure()))
  129 |             rateLimitedUntilMs=clock.millis()+Math.max(0,Math.min(res.retryAfterMs()==null?30000:res.retryAfterMs(),300000));
  130 |     }
  131 |     private boolean configured(){return StringUtils.hasText(secrets.apply(credentialEnv()));}
  132 |     private String credentialEnv(){return env.getProperty("demo.jev.credential-env","AI_GATEWAY_API_KEY");}
  133 |     private String endpoint(){return env.getProperty("demo.jev.endpoint","https://ai-gateway.vercel.sh/v1/evaluate");}
  134 |     private String model(){return env.getProperty("demo.jev.model","typesafe-ai/jev");}
  135 |     /** Free promo ended 2026-09-25 (Vercel notice). Past the window with unknown pricing,
  136 |      *  free-only + !allow-paid skips the call; allow-paid is the explicit paid opt-in. */
  137 |     private boolean budgetAdmitted(){
  138 |         boolean freeOnly=env.getProperty("demo.jev.free-only",Boolean.class,true);
  139 |         boolean allowPaid=env.getProperty("demo.jev.allow-paid",Boolean.class,false);
  140 |         if(!(freeOnly&&!allowPaid))return true;
  141 |         String end=env.getProperty("demo.jev.free-window-end","2026-09-26T00:00:00Z");
  142 |         try{return clock.instant().isBefore(java.time.Instant.parse(end.trim()));}
  143 |         catch(RuntimeException malformed){return false;}
  144 |     }
  145 |     private int intProp(String name,int fallback,int min,int max){
  146 |         Integer value=env.getProperty("demo.jev."+name,Integer.class);
  147 |         return value==null?fallback:Math.max(min,Math.min(max,value));
```

## E28 — 현재 embedding 차원/fingerprint/옵션 경계
`current:main/java/com/example/lms/service/embedding/OllamaEmbeddingModel.java`
SHA-256: `35f638bee64f2adfcf94a05d15b14668f5ab0b3ca0404c7a3a3d38ead1064503`

행 187–210:
```text
  187 |     /** Target dimensions (used for matryoshka slicing). */
  188 |     @Value("${embedding.dimensions:1536}")
  189 |     private int dimensions;
  190 | 
  191 |     /**
  192 |      * Expected raw provider dimension for diagnostics. qwen3-embedding:4b returns
  193 |      * 2560 natively, while the repo vector contract remains embedding.dimensions.
  194 |      */
  195 |     @Value("${embedding.provider-raw-dimensions:0}")
  196 |     private int providerRawDimensions;
  197 | 
  198 |     @Value("${embedding.normalization-mode:SLICE_TO_CONFIGURED_DIM}")
  199 |     private String normalizationMode;
  200 | 
  201 |     /** WARN_ONLY | STRICT (best-effort). */
  202 |     @Value("${embedding.dimension-guard-mode:WARN_ONLY}")
  203 |     private String dimensionGuardMode;
  204 | 
  205 |     @Value("${embedding.allow-zero-pad:false}")
  206 |     private boolean allowZeroPad;
  207 | 
  208 |     @Value("${embedding.log-dimension-mismatch:true}")
  209 |     private boolean logDimensionMismatch;
  210 | 
```

행 1200–1225:
```text
 1200 |     /** Cache identity follows the endpoint selected at dispatch; it contains no raw URL or credentials. */
 1201 |     public String cacheIdentity() {
 1202 |         String endpoint = localLlmProcessManager == null ? apiUrl : localLlmProcessManager.resolveServiceUrl(apiUrl);
 1203 |         Object execution = localLlmProcessManager != null && localLlmProcessManager.managesEndpoint(apiUrl)
 1204 |                 ? List.of(localLlmProcessManager.diagnostics().get("pid"),
 1205 |                         localLlmProcessManager.diagnostics().get("serverStartedAtEpochMs")) : "unmanaged";
 1206 |         return hashOrEmpty(endpoint + "|" + model + "|" + dimensions + "|" + effectiveNormalizationMode()
 1207 |                 + "|" + providerRawDimensions + "|" + dimensionGuardMode + "|" + allowZeroPad + "|" + execution);
 1208 |     }
 1209 | 
 1210 |     private Map<String, Object> buildEmbedBody(Object input, Integer targetDim, String keepAliveOverride) {
 1211 |         Map<String, Object> body = new LinkedHashMap<>();
 1212 |         body.put("model", model);
 1213 |         body.put("input", input);
 1214 | 
 1215 |         // Ollama /api/embed supports top-level dimensions. Send it only when
 1216 |         // explicitly enabled and the server hasn't rejected it.
 1217 |         // hasn't shown it doesn't support the option.
 1218 |         if (targetDim != null && targetDim > 0 && dimensionsOptionEnabled && !dimensionsOptionSuppressed.get()) {
 1219 |             body.put("dimensions", targetDim);
 1220 |         }
 1221 | 
 1222 |         String ka = (keepAliveOverride != null && !keepAliveOverride.isBlank())
 1223 |                 ? keepAliveOverride
 1224 |                 : ollamaKeepAlive;
 1225 |         if (ka != null && !ka.isBlank()) {
```

## E29 — autolearn retrain은 JSONL vector ingest
`current:main/java/com/example/lms/uaw/autolearn/AutolearnRagRetrainOrchestrator.java`
SHA-256: `a998b85f94fe15068b9f0a8e8175033cbe7207de47c897b8f5596a924879fe92`

행 17–19:
```text
   17 | /**
   18 |  * Runs the "closed-loop" step: ingest newly appended train_rag.jsonl into the vector store.
   19 |  */
```

행 40–84:
```text
   40 | 
   41 |     public int maybeRetrain(Path jsonl, int acceptedCount, PreemptionToken token) {
   42 |         // Policy:
   43 |         // - acceptedCount >= minAcceptedToTrain : ingest this verified batch.
   44 |         // - acceptedCount == 0 : occasionally probe ingest (cooldown) in case
   45 |         //   there are un-ingested samples written by other workers or delayed I/O.
   46 |         // - 0 < acceptedCount < minAcceptedToTrain : keep accumulating; do not
   47 |         //   retrain on tiny batches.
   48 |         int minAcceptedToTrain = props.getRetrain().getMinAcceptedToTrain();
   49 |         int maxRunsPerDay = props.getRetrain().getMaxRunsPerDay();
   50 |         String datasetName = props.getDataset().getName();
   51 |         traceCheck(jsonl, acceptedCount, minAcceptedToTrain, maxRunsPerDay, datasetName);
   52 | 
   53 |         boolean hasNewAccepted = acceptedCount > 0;
   54 |         if (hasNewAccepted && acceptedCount < minAcceptedToTrain) {
   55 |             log.info("[UAW] retrain skipped: acceptedCount={} minAcceptedToTrain={}",
   56 |                     acceptedCount, minAcceptedToTrain);
   57 |             traceSkip("below_min_accepted");
   58 |             return 0;
   59 |         }
   60 |         if (!hasNewAccepted) {
   61 |             long now = System.currentTimeMillis();
   62 |             long cooldownMs = 10L * 60L * 1000L; // 10 minutes
   63 |             if (now - lastProbeEpochMs < cooldownMs) {
   64 |                 traceSkip("probe_cooldown");
   65 |                 return 0;
   66 |             }
   67 |             lastProbeEpochMs = now;
   68 |         }
   69 | 
   70 |         LocalDate today = LocalDate.now();
   71 |         if (lastTrainDate == null || !lastTrainDate.equals(today)) {
   72 |             lastTrainDate = today;
   73 |             todayTrainCount.set(0);
   74 |         }
   75 |         if (todayTrainCount.get() >= maxRunsPerDay) {
   76 |             log.info("[UAW] reached retrain daily cap={}", maxRunsPerDay);
   77 |             traceSkip("daily_cap");
   78 |             return 0;
   79 |         }
   80 | 
   81 |         int n = ingestService.ingestNewSamples(jsonl, datasetName, token);
   82 |         TraceStore.put("uaw.retrain.ingest.count", n);
   83 |         if (n > 0) {
   84 |             todayTrainCount.incrementAndGet();
```

## E30 — 현재 session settings 복원 및 명시적 boolean 처리
`current:main/resources/static/js/chat.js`
SHA-256: `68ce97f02c9fafa1bfce475d98ce2b27877afb4a3b702de1648f8fcf98cc5096`

행 1493–1548:
```text
 1493 | function restoredSessionSetting(settings, ...keys) {
 1494 |   if (!settings || typeof settings !== "object") return null;
 1495 |   for (const key of keys) {
 1496 |     if (!Object.prototype.hasOwnProperty.call(settings, key)) continue;
 1497 |     const value = settings[key];
 1498 |     if (value == null) continue;
 1499 |     if (typeof value === "string" && !value.trim()) continue;
 1500 |     return value;
 1501 |   }
 1502 |   return null;
 1503 | }
 1504 | 
 1505 | function restoredSessionBoolean(value) {
 1506 |   if (typeof value === "boolean") return value;
 1507 |   if (value == null) return null;
 1508 |   const text = String(value).trim().toLowerCase();
 1509 |   if (text === "true") return true;
 1510 |   if (text === "false") return false;
 1511 |   return null;
 1512 | }
 1513 | 
 1514 | function selectCanUseValue(select, value) {
 1515 |   const text = String(value ?? "").trim();
 1516 |   if (!select || !text) return false;
 1517 |   const options = Array.from(select.options || []);
 1518 |   return options.length === 0 || options.some((option) => String(option.value) === text);
 1519 | }
 1520 | 
 1521 | function applyRestoredSessionSettings(detail = {}, options = {}) {
 1522 |   const settings = detail?.settings && typeof detail.settings === "object" ? detail.settings : {};
 1523 |   if (localControlOverrideActive && options.source !== "stored-controls") {
 1524 |     syncControlStatus({ persist: false });
 1525 |     return false;
 1526 |   }
 1527 |   const restoredModel = restoredSessionSetting(settings, "model", "modelId");
 1528 |   if (dom.modelSelectionMode && ["preferred", "strict", "auto"].includes(settings.modelSelectionMode)) {
 1529 |     dom.modelSelectionMode.value = settings.modelSelectionMode;
 1530 |   }
 1531 |   let modelApplied = false;
 1532 |   if (restoredModel && dom.modelSelect && selectCanUseValue(dom.modelSelect, restoredModel)) {
 1533 |     dom.modelSelect.value = String(restoredModel).trim();
 1534 |     modelApplied = true;
 1535 |   }
 1536 | 
 1537 |   const restoredSearchMode = restoredSessionSetting(settings, "searchMode", "search_mode");
 1538 |   const searchMode = restoredSearchMode == null ? null : String(restoredSearchMode).trim().toUpperCase();
 1539 |   if (searchMode && dom.searchModeSelect && selectCanUseValue(dom.searchModeSelect, searchMode)) {
 1540 |     dom.searchModeSelect.value = searchMode;
 1541 |   }
 1542 | 
 1543 |   const restoredUseRag = restoredSessionBoolean(restoredSessionSetting(settings, "useRag", "use_rag"));
 1544 |   if (restoredUseRag !== null && dom.useRag) {
 1545 |     dom.useRag.checked = restoredUseRag;
 1546 |   }
 1547 | 
 1548 |   // 복원 자체는 렌더링만 한다 — persist는 명시 opt-in이며 현재 호출자는 모두 false다.
```

## E31 — 현재 request settings mapper는 사용자 retrieval intent 유지
`current:main/java/com/example/lms/api/ChatRequestSettingsMerger.java`
SHA-256: `3974d4ba945c1ed533459baa7293fc60a78ce48e985c0ebdb5adf834a7ffa555`

행 84–144:
```text
   84 |         ChatRequestDto.RetrievalRequestIntent retrievalIntent = ui.getRetrievalRequestIntent() != null
   85 |                 ? ui.getRetrievalRequestIntent()
   86 |                 : new ChatRequestDto.RetrievalRequestIntent(ui.getUseWebSearch(), ui.getUseRag());
   87 |         Boolean normUseRag = ui.getUseRag() != null ? ui.getUseRag() : defaultUseRag;
   88 |         Boolean normUseWeb;
   89 |         if (ui.getUseWebSearch() != null) {
   90 |             normUseWeb = ui.getUseWebSearch();
   91 |         } else {
   92 |             String cfgVal = cfg.getOrDefault("chat.defaults.useWebSearch", "false");
   93 |             normUseWeb = Boolean.valueOf(cfgVal);
   94 |         }
   95 |         return ui.toBuilder()
   96 |                 .sessionId(ui.getSessionId())
   97 |                 .message(ui.getMessage())
   98 |                 .history(ui.getHistory())
   99 |                 .mode(ui.getMode())
  100 |                 .memoryMode(ui.getMemoryMode())
  101 |                 .model(model)
  102 |                 .temperature(temperature)
  103 |                 .topP(topP)
  104 |                 .frequencyPenalty(frequencyPenalty)
  105 |                 .presencePenalty(presencePenalty)
  106 |                 .maxTokens(ui.getMaxTokens())
  107 |                 .useVerification(ui.getUseVerification())
  108 |                 .useRag(normUseRag)
  109 |                 .useWebSearch(normUseWeb)
  110 |                 .understandingEnabled(ui.isUnderstandingEnabled())
  111 |                 .searchMode(ui.getSearchMode())
  112 |                 .webProviders(ui.getWebProviders())
  113 |                 .officialSourcesOnly(ui.getOfficialSourcesOnly())
  114 |                 .webTopK(ui.getWebTopK())
  115 |                 .precisionSearch(ui.getPrecisionSearch())
  116 |                 .precisionTopK(ui.getPrecisionTopK())
  117 |                 .accumulation(ui.getAccumulation())
  118 |                 .roleScope(ui.getRoleScope())
  119 |                 .domainProfile(ui.getDomainProfile())
  120 |                 .attachmentIds(ui.getAttachmentIds())
  121 |                 .polish(ui.getPolish())
  122 |                 .ragAnswerPolicy(ui.getRagAnswerPolicy() != null
  123 |                         ? ui.getRagAnswerPolicy()
  124 |                         : cfg.getOrDefault("chat.ragAnswerPolicy", "adaptive"))
  125 |                 .webSearchExplicit(ui.getWebSearchExplicit())
  126 |                 .retrievalRequestIntent(retrievalIntent)
  127 |                 .build();
  128 |     }
  129 | 
  130 |     @SuppressWarnings("unchecked")
  131 |     private static <T> T firstNonNull(T uiVal, String dbVal, T defVal) {
  132 |         if (uiVal != null) {
  133 |             return uiVal;
  134 |         }
  135 |         if (dbVal != null) {
  136 |             if (defVal instanceof Number) {
  137 |                 return (T) Double.valueOf(dbVal);
  138 |             }
  139 |             return (T) dbVal;
  140 |         }
  141 |         return defVal;
  142 |     }
  143 | 
  144 |     private static void trackChange(Map<String, String> cfg, String key, Object newVal, Map<String, String> dirty) {
```
