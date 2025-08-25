package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.dto.ChatResponseDto;
import com.example.lms.dto.ChatStreamEvent;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.service.rag.chain.impl.ChainRunner;
import com.example.lms.service.AdaptiveTranslationService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatService;
import com.example.lms.service.NaverSearchService;
import com.example.lms.service.rag.HybridRetriever; // [HARDENING]
import com.example.lms.service.SettingsService;
import com.example.lms.service.TranslationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.Disposable;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.scheduler.Schedulers;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@Slf4j // ◀◀◀ 2. 어노테이션 추가 (이제 'log' 변수 사용 가능)
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatApiController {

    // ===== constants =====
    private static final String FALLBACK_MODEL = "lc:OpenAiChatModel";
    private static final String KEY_DEFAULT_MODEL = "DEFAULT_MODEL";

    // 숨김 메타 프리픽스(세션 저장용)
    private static final String MODEL_META_PREFIX = "⎔MODEL⎔";
    private static final String TRACE_META_PREFIX = "⎔TRACE⎔"; // ★ add
    // Prefix for Base64-encoded trace HTML. When present the Base64 payload will be
    // decoded and displayed as a search trace panel when the session is restored.
    private static final String TRACE_META_PREFIX_B64  = "⎔TRACE64⎔";

    // 레거시 호환
    private static final String LEGACY_MODEL_META_PREFIX = "[MODEL] ";
    private static final String LEGACY_MODEL_META_PREFIX_Q = "?MODEL?";
    private static final String LEGACY_TRACE_META_PREFIX_Q = "?TRACE?"; // ★ add
    // FE 노출 헤더
    private static final String EXPOSE_HEADERS = "X-Model-Used,X-RAG-Used,X-User,X-Session-Owner";

    // ===== services =====
    private final ChatHistoryService historyService;
    private final ChatService chatService;
    private final AdaptiveTranslationService adaptiveService;
    private final SettingsService settingsService;
    private final TranslationService translationService;
    private final NaverSearchService searchService;
    /**
     * Location service for intent detection and personalised location
     * responses.  Injected to allow early interception of "where am I"
     * queries before invoking the language model or performing any
     * network searches.
     */
    private final com.example.lms.location.LocationService locationService;
    // [HARDENING] hybrid retriever for curated traces
    private final HybridRetriever hybridRetriever;

    /**
     * Emitter used to push additional events such as understanding summaries
     * to the client over SSE.  The controller registers and unregisters a
     * sink per session to receive asynchronous events from downstream
     * services.  This bean is optional so that the application can run
     * without the understanding feature enabled.
     */
    private final ChatStreamEmitter chatStreamEmitter;

    /**
     * Runner for the lightweight pre‑processing chain.  This is injected via
     * constructor thanks to {@link RequiredArgsConstructor}.  The chain
     * combines location interception, attachment context injection and image
     * prompt grounding.  It is executed prior to the main chat logic to
     * allow immediate responses (e.g. personalised location) and meta data
     * enrichment without interfering with the core chat flow.
     */
    private final ChainRunner chainRunner;

    /**
     * Registry of active chat runs.  Used to support SSE replay on reconnection and
     * to track running sessions.  Each run stores a replay sink allowing
     * multiple subscribers to join an in‑flight generation without spawning
     * duplicate tasks.
     */
    private final com.example.lms.service.chat.ChatRunRegistry runRegistry;

    // === Default RAG toggle ===
    // Use server-side default when the client does not explicitly set useRag.
    // This property is defined in application.yml under chat.defaults.useRag and defaults to true.
    @org.springframework.beans.factory.annotation.Value("${chat.defaults.useRag:true}")
    private boolean defaultUseRag;

    /**
     * Cancel the currently running chat streaming for the given session.  This endpoint can be
     * invoked by the client when the user clicks a "Stop generation" button to terminate long
     * running operations.  The current implementation delegates to {@link ChatService#cancelSession(Long)}
     * which performs best‑effort cancellation of any in‑flight tasks.  This method always returns
     * HTTP 200 OK regardless of whether there was an active stream to cancel.
     *
     * @param sessionId the session identifier to cancel; may be {@code null}
     * @return 200 OK
     */
    @PostMapping("/cancel")
    public ResponseEntity<Void> cancel(@RequestParam(required = false) Long sessionId) {
        try {
            chatService.cancelSession(sessionId);
            // Mark the run as cancelled in the registry so that it will be evicted
            if (sessionId != null) {
                try { runRegistry.markCancelled(sessionId); } catch (Throwable ignore) {}
            }
        } catch (Exception ignore) {
            // swallow all exceptions to avoid leaking implementation details
        }
        return ResponseEntity.ok().build();
    }

    /**
     * Retrieve the current state of a chat session.  This endpoint returns whether
     * the session is still running along with the last assistant message,
     * the model used and any trace HTML metadata embedded in the session.  It is
     * used by the client to decide whether to attach to an in‑flight run when
     * reloading the page.  When the session does not exist the returned
     * {@code running} flag will be false and the other values may be null.
     *
     * @param sessionId the session identifier to query
     * @return a JSON map containing running/modelUsed/lastAssistant/traceHtml
     */
    @GetMapping("/state")
    public java.util.Map<String,Object> state(@RequestParam Long sessionId) {
        boolean running = false;
        try {
            running = (sessionId != null) && runRegistry != null && runRegistry.isRunning(sessionId);
        } catch (Exception ignore) {}
        var last = historyService.getLastAssistantMessage(sessionId).orElse(null);
        // Extract model and trace metadata from the session history
        String modelUsed = null;
        String traceHtml = null;
        try {
            var session = historyService.getSessionWithMessages(sessionId);
            if (session != null && session.getMessages() != null) {
                for (var m : session.getMessages()) {
                    var c = m.getContent();
                    if (c == null) continue;
                    var mu = extractModelUsed(c);
                    if (mu != null) modelUsed = mu;
                    var th = extractTraceHtml(c);
                    if (th != null) traceHtml = th;
                }
            }
        } catch (Exception ignore) {
            // ignore parsing errors
        }
        java.util.Map<String,Object> out = new java.util.HashMap<>();
        out.put("running", running);
        out.put("modelUsed", modelUsed);
        out.put("lastAssistant", last);
        out.put("traceHtml", traceHtml);
        return out;
    }
    // === sync chat (blocking) ===
    @PostMapping("/sync")
    public ResponseEntity<ChatResponseDto> chatSync(@RequestBody @Valid ChatRequestDto dto,
                                                    @AuthenticationPrincipal UserDetails principal) {
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        ChatResponseDto body = handleChat(dto, username);
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        String modelHdr = (body.getModelUsed() != null && !body.getModelUsed().isBlank())
                ? body.getModelUsed()
                : settingsService.getAllSettings().getOrDefault(KEY_DEFAULT_MODEL, FALLBACK_MODEL);
        ok.header("X-Model-Used", modelHdr);
        if (body.isRagUsed()) ok.header("X-RAG-Used", "true");
        ok.header("X-User", username);
        ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
        return ok.body(body);
    }

    // ===== sync chat =====
    @PostMapping
    public Mono<ResponseEntity<ChatResponseDto>> chat(@RequestBody @Valid ChatRequestDto req,
                                                      @AuthenticationPrincipal UserDetails principal) {
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";

        // If client didn't explicitly request RAG, apply server default.
        // When useRag is false on the request DTO, override with defaultUseRag.
        if (!req.isUseRag()) {
            req.setUseRag(defaultUseRag);
        }
        if (req.isUseAdaptive()) {
            return adaptiveService.translate(req.getMessage(), "ko", "en")
                    .map(t -> new ChatResponseDto(t, null, "Adaptive-Translator", false))
                    .map(body -> ResponseEntity.ok()
                            .header("X-Model-Used", "Adaptive-Translator")
                            .header("X-User", username)
                            .header("Access-Control-Expose-Headers", EXPOSE_HEADERS)
                            .body(body));
        }

        return Mono.fromCallable(() -> {
                    ChatResponseDto body = handleChat(req, username);
                    ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
                    // ✅ 실제 사용 모델명만 기록 (래퍼명 금지, 빈값이면 설정값으로 폴백)
                    String modelHdr = (body.getModelUsed() != null && !body.getModelUsed().isBlank())
                            ? body.getModelUsed()
                            : settingsService.getAllSettings().getOrDefault(KEY_DEFAULT_MODEL, FALLBACK_MODEL);
                    ok.header("X-Model-Used", modelHdr);
                    if (body.isRagUsed()) ok.header("X-RAG-Used", "true");
                    ok.header("X-User", username);
                    ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
                    return ok.body(body);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    log.error("chat() 처리 오류", ex);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ChatResponseDto("Error: " + ex.getMessage(), null, "error-model", false)));
                });
    }

    // ===== streaming chat (SSE) =====
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chatStream(@RequestBody @Valid ChatRequestDto req,
                                                             @RequestParam(name = "attach", required = false, defaultValue = "false") boolean attach,
                                                             @AuthenticationPrincipal UserDetails principal) {
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";

        // When attach=true and a valid session is provided, immediately join the existing
        // replay sink instead of spawning a new generation.  This supports
        // reconnection after a page refresh or tab restore.  Only sessions
        // currently marked as RUNNING in the run registry will be attached.
        if (attach && req.getSessionId() != null && runRegistry != null
                && runRegistry.isRunning(req.getSessionId())) {
            return runRegistry.attach(req.getSessionId());
        }
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink = Sinks.many().unicast().onBackpressureBuffer();

        // Holder for the computed session key so that it can be referenced in finally
        final String[] currentSessionKeyHolder = new String[1];
        // Track current session id to allow cancellation propagation
        final AtomicReference<Long> currentSessionId = new AtomicReference<>(req.getSessionId());
        // Track background task disposable so it can be disposed on cancellation
        final AtomicReference<Disposable> bgTaskRef = new AtomicReference<>();

        // Reference to the per‑session replay sink.  Once the session is
        // initialised the sink is obtained from the ChatRunRegistry.  This
        // reference allows completion handlers (e.g. onComplete, onCancel)
        // to emit final events or mark the run as done/cancelled.
        final java.util.concurrent.atomic.AtomicReference<reactor.core.publisher.Sinks.Many<ServerSentEvent<ChatStreamEvent>>> runSinkRef = new java.util.concurrent.atomic.AtomicReference<>();
        Disposable d = Mono.fromRunnable(() -> {
                    try {
                        // 1) 설정 병합
                        ChatRequestDto dto = mergeWithSettings(req);

                        // 2) 세션 upsert
                        ChatSession session = (req.getSessionId() == null)
                                ? historyService.startNewSession(dto.getMessage(), username)
                                .orElseThrow(() -> new IllegalStateException("세션 생성 실패"))
                                : historyService.getSessionWithMessages(req.getSessionId());
                        // Propagate real session id so that it can be cancelled later
                        if (session != null && session.getId() != null) {
                            currentSessionId.set(session.getId());
                        }
                        // Initialise the replay sink and bridge events
                        if (session != null && session.getId() != null) {
                            // Obtain or create a replay sink for this session.  Multiple calls
                            // will return the same sink for in‑flight sessions.  Store it in
                            // runSinkRef for later completion signalling.
                            reactor.core.publisher.Sinks.Many<ServerSentEvent<ChatStreamEvent>> runSink = runRegistry.startOrGet(session.getId());
                            runSinkRef.set(runSink);
                            // Bridge all events emitted on the unicast sink to the replay sink.
                            sink.asFlux().subscribe(event -> {
                                try {
                                    runSink.tryEmitNext(event);
                                } catch (Throwable ignore) {
                                    // ignore emission failures
                                }
                            }, err -> {
                                // Propagate an error event into the replay sink.  Any downstream
                                // subscribers will receive this before the run is marked done.
                                try {
                                    runSink.tryEmitNext(sse(ChatStreamEvent.error("오류: " + (err != null ? err.getMessage() : ""))));
                                } catch (Throwable ignore) {
                                    // ignore
                                }
                            }, () -> {
                                // When the unicast sink completes, mark this run as done.  This
                                // allows subsequent attach attempts to replay the completed
                                // conversation without spawning a new generation.
                                runRegistry.markDone(session.getId());
                            });
                        }

                        // 2-a) 세션 키 계산 및 SSE sink 등록
                        String sessionKey;
                        if (session != null && session.getId() != null) {
                            String s = String.valueOf(session.getId());
                            sessionKey = s.startsWith("chat-") ? s : (s.matches("\\d+") ? "chat-" + s : s);
                        } else {
                            sessionKey = java.util.UUID.randomUUID().toString();
                        }
                        // store for later cleanup
                        currentSessionKeyHolder[0] = sessionKey;
                        try {
                            chatStreamEmitter.registerSink(sessionKey, sink);
                        } catch (Throwable t) {
                            // registration is best-effort; proceed even if it fails
                            log.debug("Failed to register SSE sink: {}", t.toString());
                        }

                        if (req.getSessionId() != null) {
                            historyService.appendMessage(session.getId(), "user", dto.getMessage());
                        }

                        // 
            // Run lightweight chain (location intercept / attachment context / image grounding)
            try {
                String userId = (principal != null ? principal.getUsername() : "anonymous");
                // Execute the lightweight pre‑processing chain.  Use the injected
                // ChatStreamEmitter rather than an undefined variable.  Any
                // exceptions are swallowed to avoid blocking the primary chat flow.
                chainRunner.run(sessionKey, userId, req.getMessage(), chatStreamEmitter);
            } catch (Exception ignore) {
                // ignore chain failures; continue with chat stream
            }
                        // Emit an initial thought event so the client knows the agent has started processing.
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("처리를 시작합니다…")));

                        // 3) 상태
                        // Broadcast both status and thought updates so that the UI can display the
                        // same message in the status line and the thought process panel.  Each
                        // call to status() is immediately followed by a corresponding call to
                        // thought() with the same message to satisfy the requirement of
                        // streaming thought events for every step of the agent’s work.
                        sink.tryEmitNext(sse(ChatStreamEvent.status("쿼리 분석 중…")));
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("쿼리 분석 중…")));
                        sink.tryEmitNext(sse(ChatStreamEvent.status("웹/하이브리드 검색 준비…")));
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("웹/하이브리드 검색 준비…")));


                        // 4) 웹 검색(추적)
                        // 웹 검색은 DTO의 useWebSearch 플래그와 검색 모드가 OFF가 아닌 경우에만 수행된다.
                        // FORCE_LIGHT/FORCE_DEEP 모드라도 useWebSearch=false이면 웹 검색은 비활성화된다.
                        int topKParam = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
                        com.example.lms.gptsearch.dto.SearchMode sm = dto.getSearchMode();
                        // treat null as AUTO for compatibility
                        if (sm == null) sm = com.example.lms.gptsearch.dto.SearchMode.AUTO;
                        final boolean allowWeb = dto.isUseWebSearch()
                                && sm != com.example.lms.gptsearch.dto.SearchMode.OFF;
                        NaverSearchService.SearchResult sr;
                        String traceHtml = null;
                        if (allowWeb) {
                            // Signal that we are planning and executing a search
                            sink.tryEmitNext(sse(ChatStreamEvent.status("검색 계획 수립…")));
                            // Execute live search with trace enabled
                            sr = searchService.searchWithTrace(dto.getMessage(), topKParam);
                            if (sr.trace() != null) {
                                // [HARDENING] rebuild trace using curated snippets with same session
                                try {
                                    // 정밀 검색 메타 힌트 구성: precision flag, depth, topK and official flag
                                    java.util.Map<String,Object> meta = new java.util.HashMap<>();
                                    boolean precision = java.lang.Boolean.TRUE.equals(dto.getPrecisionSearch())
                                            || com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP.equals(dto.getSearchMode());
                                    meta.put("precision", precision);
                                    meta.put("depth", precision ? "DEEP" : "LIGHT");
                                    meta.put("webTopK", (dto.getPrecisionTopK() != null && dto.getPrecisionTopK() > 0)
                                            ? dto.getPrecisionTopK()
                                            : topKParam);
                                    meta.put("officialSourcesOnly", java.lang.Boolean.TRUE.equals(dto.getOfficialSourcesOnly()));
                                    // Inject gate metadata so that downstream retrieval can respect search toggles
                                    meta.put("useWebSearch", allowWeb);
                                    meta.put("useRag", dto.isUseRag());
                                    meta.put("searchMode", String.valueOf(dto.getSearchMode()));
                                    List<dev.langchain4j.rag.content.Content> curated =
                                            hybridRetriever.retrieveProgressive(
                                                    dto.getMessage(),
                                                    String.valueOf(session.getId()),
                                                    topKParam,
                                                    meta);
                                    // Prefer original search snippets when available; fall back to curated content
                                    List<String> curSnips = (sr.snippets() != null && !sr.snippets().isEmpty())
                                            ? sr.snippets()
                                            : curated.stream()
                                                     .map(Object::toString)
                                                     .filter(s -> s != null && !s.isBlank())
                                                     .map(s -> s.length() > 240 ? s.substring(0, 240) + "…" : s)
                                                     .toList();
                                    traceHtml = searchService.buildTraceHtml(sr.trace(), curSnips);
                                } catch (Exception e) {
                                    // [HARDENING] fallback: build trace with empty curated snippets rather than raw
                                    traceHtml = searchService.buildTraceHtml(sr.trace(), java.util.Collections.emptyList());
                                }
                                // emit the rebuilt trace HTML as an SSE event
                                sink.tryEmitNext(sse(ChatStreamEvent.trace(traceHtml)));
                            }
                        } else {
                            // Skip web search entirely and inform the client
                            sr = new NaverSearchService.SearchResult(List.of(), null);
                            sink.tryEmitNext(sse(ChatStreamEvent.status("웹 검색 비활성화 — 로컬 컨텍스트만 사용")));
                        }

                        // 5) 본 호출
                        sink.tryEmitNext(sse(ChatStreamEvent.status("하이브리드 검색/재정렬 및 컨텍스트 구성…")));
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("하이브리드 검색/재정렬 및 컨텍스트 구성…")));
                        ChatRequestDto dtoForCall = ChatRequestDto.builder()
                                .sessionId(session.getId())
                                .message(dto.getMessage())
                                .history(dto.getHistory())
                                .model(dto.getModel())
                                .temperature(dto.getTemperature())
                                .topP(dto.getTopP())
                                .frequencyPenalty(dto.getFrequencyPenalty())
                                .presencePenalty(dto.getPresencePenalty())
                                .useRag(dto.isUseRag())
                                .useWebSearch(dto.isUseWebSearch())
                                .understandingEnabled(dto.isUnderstandingEnabled())
        .searchMode(dto.getSearchMode())
        .webProviders(dto.getWebProviders())
        .officialSourcesOnly(dto.getOfficialSourcesOnly())
        .webTopK(dto.getWebTopK())
                                .build();

                        sink.tryEmitNext(sse(ChatStreamEvent.status("답변 생성 중…")));
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("답변 생성 중…")));
                        ChatService.ChatResult result = chatService.continueChat(dtoForCall, q -> sr.snippets());
                        String finalText = result.content();

                        // 6) 토큰 스트리밍(청크)
                        for (String c : chunk(finalText, 60)) {
                            sink.tryEmitNext(sse(ChatStreamEvent.token(c)));
                        }

                        // 7) 세션 저장 + 모델/트레이스 메타
                        historyService.appendMessage(session.getId(), "assistant", finalText);

                        String modelUsedFinal = resolveModelUsed(result.modelUsed(), dto.getModel());

                        historyService.appendMessage(session.getId(), "system", MODEL_META_PREFIX + modelUsedFinal);

                        if (traceHtml != null) {
                            // ※ 여기에서 + 누락했던 부분
                            historyService.appendMessage(session.getId(), "system", TRACE_META_PREFIX + traceHtml);
                        }

                        sink.tryEmitNext(sse(ChatStreamEvent.done(modelUsedFinal, result.ragUsed(), session.getId())));
                    } catch (Exception ex) {
                        log.error("chatStream() 처리 오류", ex);
                        sink.tryEmitNext(sse(ChatStreamEvent.error("오류: " + ex.getMessage())));
                    } finally {
                        // 완료시 SSE sink 등록 해제 및 스트림 완료
                        try {
                            String sKey = currentSessionKeyHolder[0];
                            if (sKey != null) {
                                chatStreamEmitter.unregisterSink(sKey);
                            }
                        } catch (Throwable t) {
                            log.debug("Failed to unregister SSE sink: {}", t.toString());
                        }
                        sink.tryEmitComplete();
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
        // Persist the disposable so it can be disposed later on cancellation
        bgTaskRef.set(d);

        return sink.asFlux()
                .doOnCancel(() -> {
                    // When the client disconnects we intentionally avoid cancelling the
                    // underlying generation.  This allows the run to continue
                    // in the background and permits a later attachment.  Only
                    // local resources associated with this subscriber are cleaned up.
                    Long sid = currentSessionId.get();
                    // Do not call chatService.cancelSession(sid) here; cancellation
                    // is now explicit via the /api/chat/cancel endpoint.  This
                    // preserves in‑flight runs for reconnection.
                    try {
                        String sKey = currentSessionKeyHolder[0];
                        if (sKey != null) chatStreamEmitter.unregisterSink(sKey);
                    } catch (Throwable ignore) {}
                    try {
                        Disposable task = bgTaskRef.get();
                        if (task != null && !task.isDisposed()) task.dispose();
                    } catch (Throwable ignore) {}
                    sink.tryEmitComplete();
                    log.info("SSE stream cancelled by client (sessionId={})", sid);
                })
                .doOnError(e -> log.warn("SSE stream error (sessionId={}): {}", currentSessionId.get(), e.getMessage()))
                .doFinally(sig -> {
                    // In all termination scenarios (CANCEL/ERROR/ON_COMPLETE) ensure that
                    // the per-session SSE sink is unregistered for this subscriber.  The
                    // replay sink remains active for attach() until markDone() or
                    // markCancelled() is invoked via the run registry.
                    try {
                        String sKey = currentSessionKeyHolder[0];
                        if (sKey != null) chatStreamEmitter.unregisterSink(sKey);
                    } catch (Throwable ignore) {}
                });
    }

    private static ServerSentEvent<ChatStreamEvent> sse(ChatStreamEvent e) {
        return ServerSentEvent.<ChatStreamEvent>builder(e).event(e.type()).build();
    }

    private static List<String> chunk(String s, int size) {
        if (s == null) return List.of();
        int n = Math.max(1, size);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < s.length(); i += n) {
            out.add(s.substring(i, Math.min(s.length(), i + n)));
        }
        return out;
    }

    // ===== internal =====
    private ChatResponseDto handleChat(ChatRequestDto uiReq, String username) {
        // 0) 위치 문의는 LLM 호출 이전에 조기 응답 처리한다.  감지된 경우
        // consent, last location and reverse geocoding are evaluated via LocationService.
        try {
            com.example.lms.location.intent.LocationIntent intent =
                    locationService.detectIntent(uiReq.getMessage());
            if (intent == com.example.lms.location.intent.LocationIntent.WHERE_AM_I) {
                // Resolve the user identifier for the location lookup.  Prefer the
                // authenticated principal's username (passed as 'username') and fall back
                // to any identifier encoded in the request if such a property exists.
                String userId = (username != null && !username.isBlank()) ? username : null;
                var msgOpt = locationService.answerWhereAmI(userId);
                if (msgOpt.isPresent()) {
                    // Immediate deterministic response; avoid session creation and web search.
                    return new ChatResponseDto(msgOpt.get(), null, "location:deterministic", false);
                }
                // When the personalised location message cannot be produced (no consent,
                // missing coordinate etc.), continue to the standard flow below.
            }
        } catch (Exception e) {
            // Log but do not interrupt the standard chat flow
            log.debug("handleChat: location interception failed", e);
        }

        // 1) 설정 병합
        ChatRequestDto dto = mergeWithSettings(uiReq);

        // 2) 세션 upsert
        ChatSession session = (uiReq.getSessionId() == null)
                ? historyService.startNewSession(dto.getMessage(), username)
                .orElseThrow(() -> new IllegalStateException("세션 생성 실패"))
                : historyService.getSessionWithMessages(uiReq.getSessionId());

        // 기존 세션이면 user 발화 저장
        if (uiReq.getSessionId() != null) {
            historyService.appendMessage(session.getId(), "user", dto.getMessage());
        }

        // 3) 웹 검색
        // 검색 모드는 ChatRequestDto.searchMode에 의해 제어된다.  OFF이면 검색을 건너뛰고,
        // FORCE_LIGHT/DEEP는 항상 검색을 수행한다.  AUTO는 useWebSearch 플래그에 따라
        // 검색을 수행할지 결정한다.  topK는 webTopK 필드에 의해 지정된다.
        boolean performSearch;
        int topKParam = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
        com.example.lms.gptsearch.dto.SearchMode sm = dto.getSearchMode();
        if (sm == null) sm = com.example.lms.gptsearch.dto.SearchMode.AUTO;
        switch (sm) {
            case OFF -> performSearch = false;
            case FORCE_LIGHT, FORCE_DEEP -> performSearch = true;
            case AUTO -> performSearch = dto.isUseWebSearch();
            default -> performSearch = dto.isUseWebSearch();
        }
        NaverSearchService.SearchResult sr = performSearch
                ? searchService.searchWithTrace(dto.getMessage(), topKParam)
                : new NaverSearchService.SearchResult(List.of(), null);

        // 4) LLM 호출
        ChatRequestDto dtoForCall = ChatRequestDto.builder()
                .sessionId(session.getId())
                .message(dto.getMessage())
                .history(dto.getHistory())
                .model(dto.getModel())
                .temperature(dto.getTemperature())
                .topP(dto.getTopP())
                .frequencyPenalty(dto.getFrequencyPenalty())
                .presencePenalty(dto.getPresencePenalty())
                .useRag(dto.isUseRag())
                .useWebSearch(dto.isUseWebSearch())
                .understandingEnabled(dto.isUnderstandingEnabled())
                .searchMode(dto.getSearchMode())
                .webProviders(dto.getWebProviders())
                .officialSourcesOnly(dto.getOfficialSourcesOnly())
                .webTopK(dto.getWebTopK())
                .build();

        ChatService.ChatResult result = chatService.continueChat(dtoForCall, q -> sr.snippets());

        // 5) 저장
        historyService.appendMessage(session.getId(), "assistant", result.content());

        String modelUsedFinal = resolveModelUsed(result.modelUsed(), dto.getModel());

        historyService.appendMessage(session.getId(), "system", MODEL_META_PREFIX + modelUsedFinal);

        if (dto.isUseWebSearch() && sr.trace() != null) {
            // [HARDENING] rebuild trace in sync path using curated snippets
            String traceHtml;
            try {
                int topKParamSync = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
                // 정밀 검색 메타 힌트 구성: precision flag, depth, topK and official flag
                java.util.Map<String,Object> meta = new java.util.HashMap<>();
                boolean precision = java.lang.Boolean.TRUE.equals(dto.getPrecisionSearch())
                        || com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP.equals(dto.getSearchMode());
                meta.put("precision", precision);
                meta.put("depth", precision ? "DEEP" : "LIGHT");
                meta.put("webTopK", (dto.getPrecisionTopK() != null && dto.getPrecisionTopK() > 0)
                        ? dto.getPrecisionTopK() : topKParamSync);
                meta.put("officialSourcesOnly", java.lang.Boolean.TRUE.equals(dto.getOfficialSourcesOnly()));
                List<dev.langchain4j.rag.content.Content> curated =
                        hybridRetriever.retrieveProgressive(
                                dto.getMessage(),
                                String.valueOf(session.getId()),
                                topKParamSync,
                                meta);
                // Prefer original search snippets when available; fall back to curated content
                List<String> curSnips = (sr.snippets() != null && !sr.snippets().isEmpty())
                        ? sr.snippets()
                        : curated.stream()
                                 .map(Object::toString)
                                 .filter(s -> s != null && !s.isBlank())
                                 .map(s -> s.length() > 240 ? s.substring(0, 240) + "…" : s)
                                 .toList();
                traceHtml = searchService.buildTraceHtml(sr.trace(), curSnips);
            } catch (Exception e) {
                // [HARDENING] fallback: build trace with empty curated snippets rather than raw
                traceHtml = searchService.buildTraceHtml(sr.trace(), java.util.Collections.emptyList());
            }
            historyService.appendMessage(session.getId(), "system", TRACE_META_PREFIX + traceHtml);
        }
        // (sync path) 증거셋 알림은 SSE 전용(sink)이라 여기서는 생략.
        // 필요 시 ChatResponseDto에 evidence 필드를 추가해 응답 바디로 전달하세요.

        return new ChatResponseDto(result.content(), session.getId(), modelUsedFinal, result.ragUsed());
    }
    /** prefer real model id over LangChain wrapper labels */
    private static String resolveModelUsed(String fromLlm, String requested) {
        String cand = safeTrim(fromLlm);
        if (cand != null && !isWrapperLabel(cand)) return cand;
        String req = safeTrim(requested);
        return (req != null && !req.isBlank()) ? req : FALLBACK_MODEL;
    }
    private static String safeTrim(String s) { return (s == null) ? null : s.trim(); }
    private static boolean isWrapperLabel(String s) {
        String v = s.toLowerCase(Locale.ROOT);
        return v.startsWith("lc:") || v.endsWith("chatmodel") || v.equals("openaichatmodel");
    }

    // ===== settings merge =====
    private ChatRequestDto mergeWithSettings(ChatRequestDto ui) {
        Map<String, String> cfg = settingsService.getAllSettings();
        Map<String, String> dirty = new HashMap<>();

        double temperature = firstNonNull(ui.getTemperature(), cfg.get(SettingsService.KEY_TEMPERATURE), 0.7);
        double topP = firstNonNull(ui.getTopP(), cfg.get(SettingsService.KEY_TOP_P), 1.0);
        double frequencyPenalty = firstNonNull(ui.getFrequencyPenalty(), cfg.get(SettingsService.KEY_FREQUENCY_PENALTY), 0.0);
        double presencePenalty = firstNonNull(ui.getPresencePenalty(), cfg.get(SettingsService.KEY_PRESENCE_PENALTY), 0.0);

        String model = Optional.ofNullable(ui.getModel()).filter(s -> !s.isBlank())
                .orElse(cfg.getOrDefault(KEY_DEFAULT_MODEL, FALLBACK_MODEL));

        trackChange(cfg, SettingsService.KEY_TEMPERATURE, temperature, dirty);
        trackChange(cfg, SettingsService.KEY_TOP_P, topP, dirty);
        trackChange(cfg, SettingsService.KEY_FREQUENCY_PENALTY, frequencyPenalty, dirty);
        trackChange(cfg, SettingsService.KEY_PRESENCE_PENALTY, presencePenalty, dirty);

        return ChatRequestDto.builder()
                .sessionId(ui.getSessionId())
                .message(ui.getMessage())
                .history(ui.getHistory())
                .model(model)
                .temperature(temperature)
                .topP(topP)
                .frequencyPenalty(frequencyPenalty)
                .presencePenalty(presencePenalty)
                .useRag(ui.isUseRag())
                .useWebSearch(ui.isUseWebSearch())
                .understandingEnabled(ui.isUnderstandingEnabled())
                // Propagate GPT search preferences
                .searchMode(ui.getSearchMode())
                .webProviders(ui.getWebProviders())
                .officialSourcesOnly(ui.getOfficialSourcesOnly())
                .webTopK(ui.getWebTopK())
                // 새 정밀 검색 옵션을 그대로 전파
                .precisionSearch(ui.getPrecisionSearch())
                .precisionTopK(ui.getPrecisionTopK())
                .build();
    }

    // ===== other APIs =====
    @GetMapping("/sessions")
    public List<SessionInfo> sessions(@AuthenticationPrincipal UserDetails principal) {
        boolean isAdmin = principal != null && principal.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        String user = principal != null ? principal.getUsername() : "anonymousUser";
        List<ChatSession> list = isAdmin ? historyService.getAllSessionsForAdmin()
                : historyService.getSessionsForUser(user);
        return list.stream().map(s -> new SessionInfo(s.getId(), s.getTitle())).toList();
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> deleteSession(@PathVariable Long id, Authentication authentication) {
        String username = (authentication != null && authentication.isAuthenticated())
                ? authentication.getName() : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        ChatSession session = historyService.getSessionWithMessages(id);

        if (!isAdmin && (username == null || !session.getAdministrator().getUsername().equals(username))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        historyService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    /** GET /api/chat/sessions/{id} */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<SessionDetail> getSession(@PathVariable Long id, Authentication authentication) {
        String username = authentication != null && authentication.isAuthenticated() ? authentication.getName() : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        ChatSession session = historyService.getSessionWithMessages(id);
        if (!isAdmin && (username == null || !session.getAdministrator().getUsername().equals(username))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        var raw = Optional.ofNullable(session.getMessages())
                .orElse(Collections.emptyList())
                .stream()
                .sorted(Comparator.comparing(m -> m.getCreatedAt()))
                .toList();

        List<MessageDto> messages = new ArrayList<>();
        int lastAssistantIdx = -1;
        for (var m : raw) {
            String role = m.getRole();
            String content = m.getContent();

            // 1) Handle system meta messages explicitly.
            if ("system".equals(role)) {
                if (content != null) {
                    // 1-1) MODEL meta should not be rendered; skip entirely.
                    if (content.startsWith(MODEL_META_PREFIX)) {
                        continue;
                    }
                    // 1-2) Plain trace meta: strip the prefix and include HTML directly.
                    if (content.startsWith(TRACE_META_PREFIX)) {
                        String html = content.substring(TRACE_META_PREFIX.length()).trim();
                        messages.add(new MessageDto("system", html, m.getCreatedAt()));
                        continue;
                    }
                    // 1-3) Base64-encoded trace meta: decode the payload.
                    if (content.startsWith(TRACE_META_PREFIX_B64)) {
                        String b64 = content.substring(TRACE_META_PREFIX_B64.length()).trim();
                        String html;
                        try {
                            html = new String(java.util.Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
                        } catch (Exception e) {
                            html = "";
                        }
                        messages.add(new MessageDto("system", html, m.getCreatedAt()));
                        continue;
                    }
                }
                // Other system messages (e.g. understanding summaries) are not rendered here.
                continue;
            }

            // 2) For user and assistant roles, append the message as-is.
            messages.add(new MessageDto(role, content, m.getCreatedAt()));
            if ("assistant".equals(role)) lastAssistantIdx = messages.size() - 1;
        }

        String modelUsed = Optional.ofNullable(session.getMessages())
                .orElse(Collections.emptyList())
                .stream()
                .filter(m -> "system".equals(m.getRole()))
                .map(m -> extractModelUsed(m.getContent()))
                .filter(Objects::nonNull)
                .reduce((p, c) -> c)
                .orElse(null);
        String effectiveModel;
        if (modelUsed == null || modelUsed.isBlank() || isWrapperLabel(modelUsed)) {
            String cfgModel = settingsService.getAllSettings().get(KEY_DEFAULT_MODEL);
            effectiveModel = (cfgModel != null && !cfgModel.isBlank()) ? cfgModel : FALLBACK_MODEL;
        } else {
            effectiveModel = modelUsed;
        }

        // Do not inject "model: ..." into assistant message bodies.  The
        // model used for this session is returned via the SessionDetail
        // object and can be displayed in the UI without polluting the
        // assistant message content.

        SessionDetail detail = new SessionDetail(
                session.getId(),
                session.getTitle(),
                session.getCreatedAt(),
                messages,
                effectiveModel
        );

        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        ok.header("X-Model-Used", effectiveModel);
        String owner = session.getAdministrator().getUsername();
        ok.header("X-Session-Owner", owner);
        ok.header("X-User", owner);
        ok.header("Access-Control-Expose-Headers", EXPOSE_HEADERS);
        return ok.body(detail);
    }

    // ===== helpers =====
    private static <T> T firstNonNull(T uiVal, String dbVal, T defVal) {
        if (uiVal != null) return uiVal;
        if (dbVal != null) {
            if (defVal instanceof Number) return (T) Double.valueOf(dbVal);
            return (T) dbVal;
        }
        return defVal;
    }

    private static void trackChange(Map<String, String> cfg, String key, Object newVal, Map<String, String> dirty) {
        String current = cfg.get(key);
        String fresh = String.valueOf(newVal);
        if (!Objects.equals(current, fresh)) dirty.put(key, fresh);
    }

    private static String extractModelUsed(String c) {
        if (c == null) return null;
        if (c.startsWith(MODEL_META_PREFIX)) return c.substring(MODEL_META_PREFIX.length());
        if (c.startsWith(LEGACY_MODEL_META_PREFIX)) return c.substring(LEGACY_MODEL_META_PREFIX.length());
        if (c.startsWith(LEGACY_MODEL_META_PREFIX_Q)) return c.substring(LEGACY_MODEL_META_PREFIX_Q.length());
        return null;
    }

    private static String extractTraceHtml(String c) {
        if (c == null) return null;
        if (c.startsWith(TRACE_META_PREFIX)) return c.substring(TRACE_META_PREFIX.length());
        if (c.startsWith(LEGACY_TRACE_META_PREFIX_Q)) return c.substring(LEGACY_TRACE_META_PREFIX_Q.length());
        return null;
    }

    // ===== DTO records =====
    public record MessageDto(String role, String content, LocalDateTime timestamp) {}
    public record SessionDetail(Long id, String title, LocalDateTime createdAt, List<MessageDto> messages, String modelUsed) {}
    public record SessionInfo(Long id, String title) {}
}
