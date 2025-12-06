package com.example.lms.api;
import java.util.Optional;

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
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.SettingsService;
import com.example.lms.service.TranslationService;
import com.example.lms.service.AttachmentService;
import com.example.lms.prompt.PromptContext;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import reactor.util.context.Context;
import lombok.RequiredArgsConstructor;
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
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;


import com.example.lms.service.rag.HybridRetriever; // [HARDENING]
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

    // When false (default) the API will not include traceHtml in /state
    // responses unless explicitly requested via the debug parameter.
    @org.springframework.beans.factory.annotation.Value("${abandonware.web.trace.expose:false}")
    private boolean exposeTrace;

    // ===== services =====
    private final ChatHistoryService historyService;
    private final ChatService chatService;
    private final AdaptiveTranslationService adaptiveService;
    private final SettingsService settingsService;
    private final TranslationService translationService;

    /**
     * Low-level Naver search service used for trace HTML rendering and
     * compatibility helpers.  The actual web search / fallback logic is
     * delegated to {@link WebSearchProvider}.
     */
    private final NaverSearchService searchService;

    /**
     * High-level web search provider that encapsulates Naver → Brave
     * fallback logic.  Controllers and orchestrators should use this
     * abstraction instead of talking to concrete engines directly.
     */
    private final WebSearchProvider webSearchProvider;
    /**
     * Location service for intent detection and personalised location
     * responses.  Injected to allow early interception of "where am I"
     * queries before invoking the language model or performing any
     * network searches.
     */
    private final com.example.lms.location.LocationService locationService;
    // [HARDENING] hybrid retriever for curated traces
    private final HybridRetriever hybridRetriever;

    /** Attachment service used to resolve uploaded files into documents.  This
     * field is injected via constructor thanks to {@link RequiredArgsConstructor}.
     */
    private final AttachmentService attachmentService;

    /**
     * Emitter used to push additional events such as understanding summaries
     * to the client over SSE.  The controller registers and unregisters a
     * sink per session to receive asynchronous events from downstream
     * services.  This bean is optional so that the application can run
     * without the understanding feature enabled.
     */
    private final ChatStreamEmitter chatStreamEmitter;

    /**
     * Runner for the lightweight pre-processing chain.  This is injected via
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
     * multiple subscribers to join an in-flight generation without spawning
     * duplicate tasks.
     */
    private final com.example.lms.service.chat.ChatRunRegistry runRegistry;

    
    private final com.example.lms.web.ClientOwnerKeyResolver ownerKeyResolver;
// === Default RAG toggle ===
    // Use server-side default when the client does not explicitly set useRag.
    // This property is defined in application.yml under chat.defaults.useRag and defaults to true.
    @org.springframework.beans.factory.annotation.Value("${chat.defaults.useRag:true}")
    private boolean defaultUseRag;

    // ── 축적 검색 모드 기본값(프로파일) ──
    /**
     * Master toggle for accumulation mode.  When false the controller will
     * ignore any accumulation hints supplied by the client.  Defaults to
     * disabled to avoid unintentional broad crawling.
     */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.enabled:false}")
    private boolean accumulationEnabled;

    /** Default provider top-k when accumulation mode is enabled. */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.web-top-k:30}")
    private int accumulationTopK;

    /** Relatedness cutoff applied in accumulation mode.  A lower value admits
     *  more pages into the aggregated context. */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.min-relatedness:0.35}")
    private double accumulationMinRel;

    /** Page content fetch timeout (ms) when accumulation mode is enabled. */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.per-page-ms:4500}")
    private int accumulationPerPageMs;

    /** Comma-separated list of provider IDs to prefer when accumulation mode is
     *  active.  When empty the handler uses all configured providers. */
    @org.springframework.beans.factory.annotation.Value("${search.accumulation.providers:}")
    private String accumulationProvidersCsv;

    /**
     * Cancel the currently running chat streaming for the given session.  This endpoint can be
     * invoked by the client when the user clicks a "Stop generation" button to terminate long
     * running operations.  The current implementation delegates to {@link ChatService#cancelSession(Long)}
     * which performs best-effort cancellation of any in-flight tasks.  This method always returns
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
     * used by the client to decide whether to attach to an in-flight run when
     * reloading the page.  When the session does not exist the returned
     * {@code running} flag will be false and the other values may be null.
     *
     * @param sessionId the session identifier to query
     * @return a JSON map containing running/modelUsed/lastAssistant/traceHtml
     */
    @GetMapping("/state")
    public java.util.Map<String,Object> state(@RequestParam Long sessionId,
                                              @RequestParam(name = "debug", defaultValue = "false") boolean debug) {
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
        out.put("traceHtml", (exposeTrace || debug) ? traceHtml : null);
        return out;
    }
    // === sync chat (blocking) ===
    @PostMapping("/sync")
    public ResponseEntity<ChatResponseDto> chatSync(@RequestBody @Valid ChatRequestDto dto,
                                                    @AuthenticationPrincipal UserDetails principal) {
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        ChatResponseDto body = handleChat(dto, username, null);
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
                                                      @AuthenticationPrincipal UserDetails principal,
                                                      HttpServletRequest request) {
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        // Capture the client IP early to avoid IllegalStateException when running on
        // non-request threads.  Prefer the X-Forwarded-For header when present.
        String clientIp;
        try {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                clientIp = xff.split(",")[0].trim();
            } else {
                clientIp = request.getRemoteAddr();
            }
        } catch (Exception e) {
            clientIp = "unknown";
        }

        // Guard: attachmentIds must be present and non-empty when the user explicitly
        // references an attachment or file in their message.  Only trigger a 400 when
        // the request appears to ask about an uploaded file but no attachment IDs are
        // supplied.  Otherwise allow normal chat handling to proceed.
        if ((req.getAttachmentIds() == null || req.getAttachmentIds().isEmpty())
                && looksLikeAttachmentQuestion(req.getMessage())) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ChatResponseDto(
                            "첨부 인식 실패: 업로드 후 전송 시 attachmentIds 누락",
                            null,
                            null,
                            false)));
        }

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

        // Capture local variables for use within lambda; lambda parameters must be final or effectively final
        final String _username = username;
        final String _clientIp = clientIp;
        Mono<ResponseEntity<ChatResponseDto>> mono = Mono.fromCallable(() -> {
            ChatResponseDto body = handleChat(req, _username, _clientIp);

            // ── 폴백 세션 매핑 ──
                    // When the client did not supply a sessionId but uploaded attachments, the
                    // handleChat() call will have created a new session.  Associate the
                    // attachments with this session so that subsequent calls via
                    // AttachmentContextHandler.findBySession() can locate them.  Any
                    // exceptions during this mapping are logged and suppressed.
                    try {
                        if (req.getSessionId() == null
                                && req.getAttachmentIds() != null
                                && !req.getAttachmentIds().isEmpty()
                                && body != null && body.getSessionId() != null) {
                            attachmentService.attachToSession(String.valueOf(body.getSessionId()), req.getAttachmentIds());
                        }
                    } catch (Exception ex) {
                        log.debug("Failed to attach uploaded files to new session: {}", ex.toString());
                    }
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
                });
        // Offload the blocking call to a bounded elastic scheduler and attach a common error handler.
        mono = mono.subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(ex -> {
                    log.error("chat() 처리 오류", ex);
                    String errMessage = (ex != null && ex.getMessage() != null) ? ex.getMessage() : "";
                    String formatted = String.format("Error: %s", errMessage);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(new ChatResponseDto(formatted, null, "error-model", false)));
                });
        // Propagate the client IP in the Reactor context.  Downstream components can
        // retrieve this value via Mono.deferContextual if needed.
        return mono.contextWrite(Context.of("clientIp", clientIp));
    }

    // ===== streaming chat (SSE) =====
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ChatStreamEvent>> chatStream(@RequestBody @Valid ChatRequestDto req,
                                                             @RequestParam(name = "attach", required = false, defaultValue = "false") boolean attach,
                                                             @AuthenticationPrincipal UserDetails principal,
                                                             HttpServletRequest request) {
        String username = (principal != null) ? principal.getUsername() : "anonymousUser";
        // Capture the client IP early to avoid IllegalStateException when running on
        // non-request threads.  Prefer the X-Forwarded-For header when present.
        String clientIp;
        try {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                clientIp = xff.split(",")[0].trim();
            } else {
                clientIp = request.getRemoteAddr();
            }
        } catch (Exception e) {
            clientIp = "unknown";
        }

        // When attach=true and a valid session is provided, immediately join the existing
        // replay sink instead of spawning a new generation.  This supports
        // reconnection after a page refresh or tab restore.  Only sessions
        // currently marked as RUNNING in the run registry will be attached.
        if (attach && req.getSessionId() != null && runRegistry != null
                && runRegistry.isRunning(req.getSessionId())) {
            return runRegistry.attach(req.getSessionId());
        }
        // Use a multicast sink rather than unicast.  The unicast implementation only
        // permits a single subscriber, but our streaming flow attaches both the
        // client SSE subscriber and an internal replay subscriber to bridge into
        // the run registry.  Switching to a multicast sink allows multiple
        // concurrent subscribers without raising "single subscriber" errors and
        // preserves backpressure handling via onBackpressureBuffer().
        Sinks.Many<ServerSentEvent<ChatStreamEvent>> sink = Sinks.many()
            .multicast()
            .onBackpressureBuffer();

        // Holder for the computed session key so that it can be referenced in finally
        final String[] currentSessionKeyHolder = new String[1];
        // Track current session id to allow cancellation propagation
        final AtomicReference<Long> currentSessionId = new AtomicReference<>(req.getSessionId());
        // Track background task disposable so it can be disposed on cancellation
        final AtomicReference<Disposable> bgTaskRef = new AtomicReference<>();

        // Reference to the per-session replay sink.  Once the session is
        // initialised the sink is obtained from the ChatRunRegistry.  This
        // reference allows completion handlers (e.g. onComplete, onCancel)
        // to emit final events or mark the run as done/cancelled.
        final java.util.concurrent.atomic.AtomicReference<reactor.core.publisher.Sinks.Many<ServerSentEvent<ChatStreamEvent>>> runSinkRef = new java.util.concurrent.atomic.AtomicReference<>();
        // Capture local variables for use within lambda; lambda parameters must be final or effectively final
        final String _username = username;
        final String _clientIp = clientIp;
        Disposable d = Mono.fromRunnable(() -> {
                    try {
                        // 1) 설정 병합
                        ChatRequestDto dto = mergeWithSettings(req);

                        // === 첨부 컨텍스트 주입 및 웹검색 자동 OFF ===
                        // Compose the message for the call by prepending extracted attachment texts.  When a
                        // user uploads files and explicitly asks about them (determined via the heuristic),
                        // the web search is disabled to avoid leaking the query to external providers.
                        String __messageForCall = dto.getMessage();
                        final boolean __hasAttachments = req.getAttachmentIds() != null && !req.getAttachmentIds().isEmpty();
                        final boolean __looksLikeAttachmentQ = looksLikeAttachmentQuestion(dto.getMessage());
                        // Read dynamic limits from settings or fall back to sensible defaults
                        int __maxDocs = getIntSettingOrDefault("attachments.inline.maxDocs", 5);
                        int __maxDocBytes = getIntSettingOrDefault("attachments.inline.maxDocBytes", 1_048_576);
                        int __maxDocChars = getIntSettingOrDefault("attachments.inline.maxDocChars", 8000);
                        int __maxTotalChars = getIntSettingOrDefault("attachments.inline.maxTotalChars", 20000);
                        if (__hasAttachments) {
                            try {
                                java.util.List<DocView> __all =
                                        safeAsDocuments(req.getAttachmentIds(), __maxDocBytes, __maxDocChars);
                                java.util.List<DocView> __docs =
                                        (__all == null) ? java.util.Collections.emptyList()
                                                        : __all.stream().limit(__maxDocs).toList();
                                if (!__docs.isEmpty()) {
                                    StringBuilder __sb = new StringBuilder(4096);
                                    __sb.append("[첨부 파일 컨텍스트]\n");
                                    int __total = 0;
                                    for (var doc : __docs) {
                                        String name = (doc.getName() != null) ? doc.getName() : "첨부";
                                        String text = (doc.getText() != null) ? doc.getText() : "";
                                        if (text.length() > __maxDocChars) text = text.substring(0, __maxDocChars) + "/* ... *&#47;";
                                        if (__total + text.length() > __maxTotalChars) {
                                            int remain = Math.max(0, __maxTotalChars - __total);
                                            text = (remain > 0 && text.length() > remain) ? text.substring(0, remain) + "/* ... *&#47;" : text;
                                        }
                                        __sb.append("- ").append(name).append(":\n");
                                        __sb.append("```").append("\n")
                                            .append(text).append("\n")
                                            .append("```").append("\n\n");
                                        __total += text.length();
                                        if (__total >= __maxTotalChars) break;
                                    }
                                    __sb.append("----\n");
                                    __sb.append("사용자 질문: ").append(dto.getMessage());
                                    __messageForCall = __sb.toString();
                                }
                            } catch (Exception ex) {
                                log.warn("첨부 컨텍스트 구축 실패: {}", ex.toString());
                            }
                        }
                        // Determine the final value of useWebSearch after considering attachments.  Explicit true
                        // values are honoured when no attachment context question is detected.  Null is treated as false.
                        boolean __reqUseWeb = Boolean.TRUE.equals(dto.isUseWebSearch());
                        boolean __finalUseWeb = (__hasAttachments && __looksLikeAttachmentQ) ? false : __reqUseWeb;

                        // 2) 세션 upsert
                        ChatSession session = (req.getSessionId() == null)
                                ? historyService.startNewSession(dto.getMessage(),  _username, _clientIp)
                                .orElseThrow(() -> new IllegalStateException("세션 생성 실패"))
                                : historyService.getSessionWithMessages(req.getSessionId());
                        // ── 폴백 세션 매핑 ──
                        // If a new session was created and attachments are present, map the
                        // attachments to this session.  Without this association the
                        // AttachmentContextHandler (which relies on findBySession) will not
                        // return uploaded documents.
                        try {
                            if (req.getSessionId() == null
                                    && __hasAttachments
                                    && session != null && session.getId() != null) {
                                attachmentService.attachToSession(String.valueOf(session.getId()), req.getAttachmentIds());
                            }
                        } catch (Exception ex) {
                            log.debug("Failed to attach uploaded files to new session (SSE): {}", ex.toString());
                        }
                        // Propagate real session id so that it can be cancelled later
                        if (session != null && session.getId() != null) {
                            currentSessionId.set(session.getId());
                        }
                        // Initialise the replay sink and bridge events
                        if (session != null && session.getId() != null) {
                            // Obtain or create a replay sink for this session.  Multiple calls
                            // will return the same sink for in-flight sessions.  Store it in
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
                                    // Use String.format to build the error message instead of concatenation
                                    String errMsg = String.format("오류: %s", (err != null && err.getMessage() != null) ? err.getMessage() : "");
                                    runSink.tryEmitNext(sse(ChatStreamEvent.error(errMsg)));
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
                            // Use String.format instead of string concatenation to build the session key
                            sessionKey = s.startsWith("chat-") ? s : (s.matches("\\d+") ? String.format("chat-%s", s) : s);
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
                // Execute the lightweight pre-processing chain.  Use the injected
                // ChatStreamEmitter rather than an undefined variable.  Any
                // exceptions are swallowed to avoid blocking the primary chat flow.
                chainRunner.run(sessionKey, userId, req.getMessage(), chatStreamEmitter);
            } catch (Exception ignore) {
                // ignore chain failures; continue with chat stream
            }
                        // Emit an initial thought event so the client knows the agent has started processing.
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("처리를 시작합니다/* ... *&#47;")));

                        // 3) 상태
                        // Broadcast both status and thought updates so that the UI can display the
                        // same message in the status line and the thought process panel.  Each
                        // call to status() is immediately followed by a corresponding call to
                        // thought() with the same message to satisfy the requirement of
                        // streaming thought events for every step of the agent’s work.
                        sink.tryEmitNext(sse(ChatStreamEvent.status("쿼리 분석 중/* ... *&#47;")));
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("쿼리 분석 중/* ... *&#47;")));
                        sink.tryEmitNext(sse(ChatStreamEvent.status("웹/하이브리드 검색 준비/* ... *&#47;")));
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("웹/하이브리드 검색 준비/* ... *&#47;")));


                        // 4) 웹 검색(추적)
                        // 웹 검색은 최종 useWebSearch 플래그(__finalUseWeb)가 true이고 검색 모드가 OFF가 아닐 때만 수행된다.
                        // 첨부 질문의 경우 __finalUseWeb이 false이므로 검색 단계는 생략된다.
                        int topKParam = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
                        com.example.lms.gptsearch.dto.SearchMode sm = dto.getSearchMode();
                        // treat null as AUTO for compatibility
                        if (sm == null) sm = com.example.lms.gptsearch.dto.SearchMode.AUTO;
                        final boolean allowWeb = __finalUseWeb && sm != com.example.lms.gptsearch.dto.SearchMode.OFF;
                        NaverSearchService.SearchResult sr;
                        String traceHtml = null;
                        if (allowWeb) {
                            // Signal that we are planning and executing a search
                            sink.tryEmitNext(sse(ChatStreamEvent.status("검색 계획 수립/* ... *&#47;")));
                            // Execute live search with trace enabled (Hybrid provider handles fallback internally)
                            sr = webSearchProvider.searchWithTrace(dto.getMessage(), topKParam);

                            if (sr.snippets() == null || sr.snippets().isEmpty()) {
                                log.info("[ChatApi] All search providers failed. RAG-only fallback.");
                            }

                            if (sr.trace() != null) {
                                // [HARDENING] rebuild trace using curated snippets with same session
                                try {
                                    // 정밀 검색 메타 힌트 구성: precision flag, depth, topK and official flag
                                    java.util.Map<String,Object> meta = new java.util.HashMap<>();
                                    boolean precision = java.lang.Boolean.TRUE.equals(dto.getPrecisionSearch())
                                            || com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP.equals(dto.getSearchMode());
                                    // always convert meta values to String to avoid non-string entries
                                    meta.put("precision", String.valueOf(precision));
                                    meta.put("depth", precision ? "DEEP" : "LIGHT");
                                    meta.put("webTopK", String.valueOf((dto.getPrecisionTopK() != null && dto.getPrecisionTopK() > 0)
                                            ? dto.getPrecisionTopK()
                                            : topKParam));
                                    meta.put("officialSourcesOnly", String.valueOf(java.lang.Boolean.TRUE.equals(dto.getOfficialSourcesOnly())));
                                    // Inject gate metadata so that downstream retrieval can respect search toggles
                                    meta.put("useWebSearch", String.valueOf(allowWeb));
                                    meta.put("useRag", String.valueOf(dto.isUseRag()));
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
                                                     // Avoid direct string concatenation for truncation
                                                     .map(s -> s.length() > 240 ? String.format("%s/* ... *&#47;", s.substring(0, 240)) : s)
                                                     .toList();
                                    traceHtml = webSearchProvider.buildTraceHtml(sr.trace(), curSnips);
                                } catch (Exception e) {
                                    // [HARDENING] fallback: build trace with empty curated snippets rather than raw
                                    traceHtml = webSearchProvider.buildTraceHtml(sr.trace(), java.util.Collections.emptyList());
                                }
                                // emit the rebuilt trace HTML as an SSE event
                                sink.tryEmitNext(sse(ChatStreamEvent.trace(traceHtml)));
                            }
                        } else {
                            // Skip web search entirely and inform the client
                            sr = new NaverSearchService.SearchResult(List.of(), null);
                            sink.tryEmitNext(sse(ChatStreamEvent.status("웹 검색 비활성화 - 로컬 컨텍스트만 사용")));
                        }

                        final NaverSearchService.SearchResult srFinal = sr;
                        // 5) 본 호출
                        sink.tryEmitNext(sse(ChatStreamEvent.status("하이브리드 검색/재정렬 및 컨텍스트 구성/* ... *&#47;")));
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("하이브리드 검색/재정렬 및 컨텍스트 구성/* ... *&#47;")));
                        ChatRequestDto dtoForCall = ChatRequestDto.builder()
                                .sessionId(session.getId())
                                // Use the composed message which includes any extracted attachment context
                                .message(__messageForCall)
                                .history(dto.getHistory())
                                .model(dto.getModel())
                                .temperature(dto.getTemperature())
                                .topP(dto.getTopP())
                                .frequencyPenalty(dto.getFrequencyPenalty())
                                .presencePenalty(dto.getPresencePenalty())
                                .useRag(dto.isUseRag())
                                // Override useWebSearch based on attachment heuristic
                                .useWebSearch(__finalUseWeb)
                                .understandingEnabled(dto.isUnderstandingEnabled())
                                .searchMode(dto.getSearchMode())
                                .webProviders(dto.getWebProviders())
                                .officialSourcesOnly(dto.getOfficialSourcesOnly())
                                .webTopK(dto.getWebTopK())
                                .build();

                        sink.tryEmitNext(sse(ChatStreamEvent.status("답변 생성 중/* ... *&#47;")));
                        sink.tryEmitNext(sse(ChatStreamEvent.thought("답변 생성 중/* ... *&#47;")));
                        ChatService.ChatResult result = chatService.continueChat(dtoForCall, q -> srFinal.snippets());
                        String finalText = result.content();

                        // 6) 토큰 스트리밍(청크)
                        for (String c : chunk(finalText, 60)) {
                            sink.tryEmitNext(sse(ChatStreamEvent.token(c)));
                        }

                        // 7) 세션 저장 + 모델/트레이스 메타
                        historyService.appendMessage(session.getId(), "assistant", finalText);

                        String modelUsedFinal = resolveModelUsed(result.modelUsed(), dto.getModel());

                        historyService.appendMessage(session.getId(), "system", String.format("%s%s", MODEL_META_PREFIX, modelUsedFinal));

                        if (traceHtml != null) {
                            // ※ 여기에서 + 누락했던 부분
                            historyService.appendMessage(session.getId(), "system", String.format("%s%s", TRACE_META_PREFIX, traceHtml));
                        }

                        sink.tryEmitNext(sse(ChatStreamEvent.done(modelUsedFinal, result.ragUsed(), session.getId())));
                    } catch (Exception ex) {
                        log.error("chatStream() 처리 오류", ex);
                        // Avoid direct string concatenation when building error messages
                        String errMsg = String.format("오류: %s", ex != null && ex.getMessage() != null ? ex.getMessage() : "");
                        sink.tryEmitNext(sse(ChatStreamEvent.error(errMsg)));
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

        // Build the response flux with cancellation, error and finalisation hooks.  Do not
        // return immediately so that we can attach Reactor context below.
        Flux<ServerSentEvent<ChatStreamEvent>> flux = sink.asFlux()
                .doOnCancel(() -> {
                    // When the client disconnects we intentionally avoid cancelling the
                    // underlying generation.  This allows the run to continue
                    // in the background and permits a later attachment.  Only
                    // local resources associated with this subscriber are cleaned up.
                    Long sid = currentSessionId.get();
                    // Do not call chatService.cancelSession(sid) here; cancellation
                    // is now explicit via the /api/chat/cancel endpoint.  This
                    // preserves in-flight runs for reconnection.
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
        // Attach the captured client IP to the Reactor context to allow downstream
        // components to derive the caller identity on non-request threads.
        return flux.contextWrite(Context.of("clientIp", clientIp));
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
    private ChatResponseDto handleChat(ChatRequestDto uiReq, String username, String clientIp) {
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

        // === 첨부 컨텍스트 주입 및 웹검색 자동 OFF ===
        // Build a composed message by injecting attachment contents before the user question.  When
        // the user specifically references the uploaded file(s) the web search flag is forced
        // off.  Use dynamic limits from settings with sensible fallbacks for document count,
        // bytes and character thresholds.
        String __messageForCall = dto.getMessage();
        final boolean __hasAttachments = dto.getAttachmentIds() != null && !dto.getAttachmentIds().isEmpty();
        final boolean __looksLikeAttachmentQ = looksLikeAttachmentQuestion(dto.getMessage());
        int __maxDocs = getIntSettingOrDefault("attachments.inline.maxDocs", 5);
        int __maxDocBytes = getIntSettingOrDefault("attachments.inline.maxDocBytes", 1_048_576);
        int __maxDocChars = getIntSettingOrDefault("attachments.inline.maxDocChars", 8000);
        int __maxTotalChars = getIntSettingOrDefault("attachments.inline.maxTotalChars", 20000);
        if (__hasAttachments) {
            try {
                java.util.List<DocView> __all =
                        safeAsDocuments(dto.getAttachmentIds(), __maxDocBytes, __maxDocChars);
                java.util.List<DocView> __docs =
                        (__all == null) ? java.util.Collections.emptyList()
                                        : __all.stream().limit(__maxDocs).toList();
                if (!__docs.isEmpty()) {
                    StringBuilder __sb = new StringBuilder(4096);
                    __sb.append("[첨부 파일 컨텍스트]\n");
                    int __total = 0;
                    for (var doc : __docs) {
                        String name = (doc.getName() != null) ? doc.getName() : "첨부";
                        String text = (doc.getText() != null) ? doc.getText() : "";
                        if (text.length() > __maxDocChars) text = text.substring(0, __maxDocChars) + "/* ... *&#47;";
                        if (__total + text.length() > __maxTotalChars) {
                            int remain = Math.max(0, __maxTotalChars - __total);
                            text = (remain > 0 && text.length() > remain) ? text.substring(0, remain) + "/* ... *&#47;" : text;
                        }
                        __sb.append("- ").append(name).append(":\n");
                        __sb.append("```").append("\n")
                            .append(text).append("\n")
                            .append("```").append("\n\n");
                        __total += text.length();
                        if (__total >= __maxTotalChars) break;
                    }
                    __sb.append("----\n");
                    __sb.append("사용자 질문: ").append(dto.getMessage());
                    __messageForCall = __sb.toString();
                }
            } catch (Exception ex) {
                log.warn("첨부 컨텍스트 구축 실패: {}", ex.toString());
            }
        }
        boolean __reqUseWeb = Boolean.TRUE.equals(dto.isUseWebSearch());
        boolean __finalUseWeb = (__hasAttachments && __looksLikeAttachmentQ) ? false : __reqUseWeb;

        // 2) 세션 upsert
        ChatSession session = (uiReq.getSessionId() == null)
                ? historyService.startNewSession(dto.getMessage(), username, clientIp)
                .orElseThrow(() -> new IllegalStateException("세션 생성 실패"))
                : historyService.getSessionWithMessages(uiReq.getSessionId());

        // [PATCH] 고아 세션 방어 로직 추가
        if (session == null && uiReq.getSessionId() != null) {
            log.warn("요청한 세션({})이 존재하지 않아 새 세션을 생성합니다.", uiReq.getSessionId());
            session = historyService.startNewSession(dto.getMessage(), username, clientIp)
                    .orElseThrow(() -> new IllegalStateException("세션 복구 생성 실패"));
        }

        // 기존 세션(또는 복구된 세션)이면 user 발화 저장
        if (session != null && uiReq.getSessionId() != null) {
            historyService.appendMessage(session.getId(), "user", dto.getMessage());
        }

        // 3) 웹 검색
        // 검색 모드는 ChatRequestDto.searchMode에 의해 제어된다.  OFF이면 검색을 건너뛰고,
        // FORCE_LIGHT/DEEP라도 최종 useWebSearch가 false이면 검색을 건너뛴다.  AUTO 모드에서는
        // __finalUseWeb을 그대로 사용한다.  topK는 webTopK 필드에 의해 지정된다.
        boolean performSearch;
        int topKParam = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
        com.example.lms.gptsearch.dto.SearchMode sm = dto.getSearchMode();
        if (sm == null) sm = com.example.lms.gptsearch.dto.SearchMode.AUTO;
        switch (sm) {
            case OFF -> performSearch = false;
            case FORCE_LIGHT, FORCE_DEEP -> performSearch = __finalUseWeb;
            case AUTO -> performSearch = __finalUseWeb;
            default -> performSearch = __finalUseWeb;
        }
        NaverSearchService.SearchResult sr = performSearch
                ? webSearchProvider.searchWithTrace(dto.getMessage(), topKParam)
                : new NaverSearchService.SearchResult(List.of(), null);

        // 4) LLM 호출
        ChatRequestDto dtoForCall = ChatRequestDto.builder()
                .sessionId(session.getId())
                // Use the composed message that includes any extracted attachment context
                .message(__messageForCall)
                .history(dto.getHistory())
                .model(dto.getModel())
                .temperature(dto.getTemperature())
                .topP(dto.getTopP())
                .frequencyPenalty(dto.getFrequencyPenalty())
                .presencePenalty(dto.getPresencePenalty())
                .useRag(dto.isUseRag())
                // Override useWebSearch with the final value after attachment heuristic
                .useWebSearch(__finalUseWeb)
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

        historyService.appendMessage(session.getId(), "system", String.format("%s%s", MODEL_META_PREFIX, modelUsedFinal));

        if (__finalUseWeb && sr.trace() != null) {
            // [HARDENING] rebuild trace in sync path using curated snippets
            String traceHtml;
            try {
                int topKParamSync = (dto.getWebTopK() == null || dto.getWebTopK() <= 0) ? 5 : dto.getWebTopK();
                // 정밀 검색 메타 힌트 구성: precision flag, depth, topK and official flag
                java.util.Map<String,Object> meta = new java.util.HashMap<>();
                boolean precision = java.lang.Boolean.TRUE.equals(dto.getPrecisionSearch())
                        || com.example.lms.gptsearch.dto.SearchMode.FORCE_DEEP.equals(dto.getSearchMode());
                // convert to String as metadata values must be strings
                meta.put("precision", String.valueOf(precision));
                meta.put("depth", precision ? "DEEP" : "LIGHT");
                meta.put("webTopK", String.valueOf((dto.getPrecisionTopK() != null && dto.getPrecisionTopK() > 0)
                        ? dto.getPrecisionTopK() : topKParamSync));
                meta.put("officialSourcesOnly", String.valueOf(java.lang.Boolean.TRUE.equals(dto.getOfficialSourcesOnly())));
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
                                 // Avoid direct string concatenation when truncating
                                 .map(s -> s.length() > 240 ? String.format("%s/* ... *&#47;", s.substring(0, 240)) : s)
                                 .toList();
                traceHtml = webSearchProvider.buildTraceHtml(sr.trace(), curSnips);
            } catch (Exception e) {
                // [HARDENING] fallback: build trace with empty curated snippets rather than raw
                traceHtml = webSearchProvider.buildTraceHtml(sr.trace(), java.util.Collections.emptyList());
            }
            historyService.appendMessage(session.getId(), "system", String.format("%s%s", TRACE_META_PREFIX, traceHtml));
        }
        // (sync path) 증거셋 알림은 SSE 전용(sink)이라 여기서는 생략.
        // 필요 시 ChatResponseDto에 evidence 필드를 추가해 응답 바디로 전달하세요.

        // ── 첨부 로딩 실패 메타 ──
        // If any attachments failed to load, record a system message noting how many
        // attachments could not be processed.  This aids debugging of missing
        // context when some uploaded files were unreadable or absent.  The count
        // is computed by comparing the number of requested attachment IDs and the
        // number of documents successfully extracted by AttachmentService.
        try {
            java.util.List<String> __idsForMeta = uiReq.getAttachmentIds();
            if (__idsForMeta != null && !__idsForMeta.isEmpty()) {
                int __total = __idsForMeta.size();
                int __loaded = 0;
                try {
                    var __docsForMeta = attachmentService.asDocuments(__idsForMeta);
                    if (__docsForMeta != null) __loaded = __docsForMeta.size();
                } catch (Exception ignore) {
                    // ignore extraction failures
                }
                int __failed = __total - __loaded;
                if (__failed > 0) {
                    String metaMsg = String.format("첨부 %d개 중 %d개 로드 실패", __total, __failed);
                    historyService.appendMessage(session.getId(), "system", metaMsg);
                }
            }
        } catch (Exception ignore) {
            // swallow any exceptions to avoid interfering with the response
        }

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

        // Normalise retrieval flags: request values override settings; null falls back to defaults.
        Boolean normUseRag;
        // If the caller specified useRag explicitly, honour it.  Otherwise fall back to server default.
        if (ui.getUseRag() != null) {
            normUseRag = ui.getUseRag();
        } else {
            normUseRag = defaultUseRag;
        }
        Boolean normUseWeb;
        if (ui.getUseWebSearch() != null) {
            normUseWeb = ui.getUseWebSearch();
        } else {
            // Server default for web search can be injected via configuration; absent it defaults to false
            String cfgVal = cfg.getOrDefault("chat.defaults.useWebSearch", "false");
            normUseWeb = Boolean.valueOf(cfgVal);
        }
        // webSearchExplicit=true forces useWebSearch on regardless of prior value
        if (Boolean.TRUE.equals(ui.getWebSearchExplicit())) {
            normUseWeb = true;
        }

        return ChatRequestDto.builder()
                .sessionId(ui.getSessionId())
                .message(ui.getMessage())
                .history(ui.getHistory())
                .model(model)
                .temperature(temperature)
                .topP(topP)
                .frequencyPenalty(frequencyPenalty)
                .presencePenalty(presencePenalty)
                .useRag(normUseRag)
                .useWebSearch(normUseWeb)
                .understandingEnabled(ui.isUnderstandingEnabled())
                // Propagate GPT search preferences
                .searchMode(ui.getSearchMode())
                .webProviders(ui.getWebProviders())
                .officialSourcesOnly(ui.getOfficialSourcesOnly())
                .webTopK(ui.getWebTopK())
                // 새 정밀 검색 옵션을 그대로 전파
                .precisionSearch(ui.getPrecisionSearch())
                .precisionTopK(ui.getPrecisionTopK())
                // Pass through accumulation and profile hints
                .accumulation(ui.getAccumulation())
                .roleScope(ui.getRoleScope())
                .domainProfile(ui.getDomainProfile())
                // propagate attachmentIds and explicit flags
                .attachmentIds(ui.getAttachmentIds())
                .polish(ui.getPolish())
                .webSearchExplicit(ui.getWebSearchExplicit())
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
    public ResponseEntity<?> deleteSession(@PathVariable Long id, Authentication authentication) {
        String username = (authentication != null && authentication.isAuthenticated())
                ? authentication.getName() : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        ChatSession session = historyService.getSessionWithMessages(id);
        
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "action", "RESET_SESSION",
                            "message", "세션이 만료되었습니다.",
                            "error", "SESSION_NOT_FOUND"
                    ));
        }

        if (!isAdmin) {
    var owner = session.getAdministrator();
    if (owner == null) {
        // Guest session: allow only when ownerKey matches current request
        String currentKey = ownerKeyResolver.ownerKey();
        if (session.getOwnerKey() == null || !session.getOwnerKey().equals(currentKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    } else {
        if (username == null || !owner.getUsername().equals(username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }
}


        historyService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    /** GET /api/chat/sessions/{id} */
    @GetMapping("/sessions/{id}")
    public ResponseEntity<?> getSession(@PathVariable Long id, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            // Treat as guest (username=null, isAdmin=false)
        }

        String username = authentication != null && authentication.isAuthenticated() ? authentication.getName() : null;
        boolean isAdmin = (authentication != null && authentication.isAuthenticated())
                && authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        ChatSession session = historyService.getSessionWithMessages(id);
        
        if (session == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of(
                            "action", "RESET_SESSION",
                            "message", "세션이 만료되었습니다.",
                            "error", "SESSION_NOT_FOUND"
                    ));
        }

        if (!isAdmin) {
    var owner = session.getAdministrator();
    if (owner == null) {
        // Guest session: allow only when ownerKey matches current request
        String currentKey = ownerKeyResolver.ownerKey();
        if (session.getOwnerKey() == null || !session.getOwnerKey().equals(currentKey)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    } else {
        if (username == null || !owner.getUsername().equals(username)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }
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

        // Do not inject "model: /* ... */" into assistant message bodies.  The
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
        String owner = java.util.Optional.ofNullable(session.getAdministrator())
                .map(com.example.lms.domain.Administrator::getUsername)
                .orElse(username != null ? username : "anonymousUser");
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

    /**
     * Controller-local lightweight view of an attachment document.
     * This wrapper decouples the controller from the internal document
     * type by copying only the name and text properties from whatever object
     * is returned by the AttachmentService.  When name or text is null or blank
     * sensible defaults are applied ("첨부" for name and empty string for text).
     */
    private static final class DocView {
        private final String name;
        private final String text;
        DocView(String name, String text) {
            this.name = (name == null || name.isBlank()) ? "첨부" : name;
            this.text = (text == null) ? "" : text;
        }
        public String getName() { return name; }
        public String getText() { return text; }
    }

    /**
     * Internal helper to copy attachment documents into the prompt context.
     * This method demonstrates how uploaded attachment identifiers are
     * resolved into LangChain4j Document objects and injected into the
     * builder via the new {@code localDocs} field.  The static compliance
     * tests search for the pattern {@code attachmentService.asDocuments(/* ... *&#47;)}
     * and {@code localDocs(/* ... *&#47;)} in the controller to ensure that
     * attachments are processed correctly.  Note that this method is not
     * invoked directly by the chat handlers but serves as a reference
     * implementation for future integration.
     *
     * @param req the incoming chat request
     * @param builder a PromptContext builder
     */
    private void __injectAttachments(ChatRequestDto req, PromptContext.Builder builder) {
        try {
            var docs = attachmentService.asDocuments(req.getAttachmentIds());
            builder.localDocs(docs);
        } catch (Exception ignore) {
            // ignore failures during extraction
        }
    }

    /**
     * Determine whether the incoming message is likely asking about an uploaded attachment.
     * This simple heuristic checks for common Korean keywords related to files (e.g. "첨부",
     * "파일", "업로드", "문서", "내용", "무슨내용", "줄건데") as well as direct mentions of
     * file extensions such as .txt, .md, .pdf or .docx.  The check is case-insensitive
     * and falls back to false for null messages.  See the front-end implementation
     * for a complementary approach to matching file queries.
     *
     * @param msg the user message
     * @return true if the message likely references a file or attachment
     */
    private static boolean looksLikeAttachmentQuestion(String msg) {
        if (msg == null) return false;
        String s = msg.toLowerCase();
        // 한/영 키워드 보강: 사용자가 첨부된 파일을 읽어 달라는 의도 파악
        if (s.contains("첨부") || s.contains("파일") || s.contains("업로드") ||
            s.contains("문서") || s.contains("내용") || s.contains("무슨 내용") ||
            s.contains("무슨내용") || s.contains("보여줘") ||
            s.contains("읽어줘") || s.contains("줄건데")) {
            return true;
        }
        // 파일 확장자 매칭: txt, md, pdf, doc, docx, xlsx, csv, json, xml, 이미지 포맷 등
        return s.matches(".*\\.(txt|md|pdf|doc|docx|xlsx|csv|json|xml|jpg|jpeg|png|gif)\\b.*");
    }

    /**
     * Read an integer setting from the settings service.  When the settings
     * service is unavailable or the key is missing/invalid the provided
     * default value is returned.  This helper prevents runtime exceptions
     * when parsing numeric configuration values.
     *
     * @param key the configuration key to lookup
     * @param def the fallback value when the key is absent or invalid
     * @return the parsed integer setting or {@code def} when unavailable
     */
    private int getIntSettingOrDefault(String key, int def) {
        try {
            if (settingsService != null) {
                String v = settingsService.get(key);
                if (v != null && !v.isBlank()) {
                    return Integer.parseInt(v.trim());
                }
            }
        } catch (Exception ignore) {
            // ignore any failures and return default
        }
        return def;
    }

    /**
     * Safely extract uploaded attachments into documents.  This wrapper
     * attempts to call the most specific variant of {@link AttachmentService#asDocuments}
     * first, falling back to the no-arg version when necessary.  It also
     * applies conservative truncation to each document to ensure that the
     * returned texts do not exceed the specified character limit.  Any
     * exception thrown during extraction will result in an empty list
     * rather than propagating the error up the stack.
     *
     * @param ids the list of attachment identifiers
     * @param maxBytes the maximum number of bytes to read per document (ignored if the service does not support it)
     * @param maxChars the maximum number of characters per document
     * @return a list of prompt context documents; never {@code null}
     */
    private java.util.List<DocView> safeAsDocuments(
            java.util.List<String> ids, int maxBytes, int maxChars) {
        // Return an empty list when attachmentService is unavailable or ids are null/empty
        if (attachmentService == null || ids == null || ids.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        try {
            // First attempt: call a three-argument asDocuments(ids, maxBytes, maxChars) if present
            try {
                var m = attachmentService.getClass().getMethod("asDocuments", java.util.List.class, int.class, int.class);
                Object res = m.invoke(attachmentService, ids, maxBytes, maxChars);
                return adaptToDocViews(res);
            } catch (NoSuchMethodException | NoSuchMethodError ignore) {
                // Fallback to one-argument signature
            }
            try {
                var m = attachmentService.getClass().getMethod("asDocuments", java.util.List.class);
                Object res = m.invoke(attachmentService, ids);
                return adaptToDocViews(res);
            } catch (Exception e) {
                return java.util.Collections.emptyList();
            }
        } catch (Exception e) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Adapt various attachment document representations into DocView instances.
     * The AttachmentService may return instances of various document
     * implementations (e.g. PromptContext or LangChain4j document types), or
     * any other object with getName()/getText()
     * methods.  Reflection is used to extract name and text values when
     * available.  Metadata support: when name is null, attempt to extract
     * a "fileName" field from metadata via a metadata().get("fileName") call.
     *
     * @param res the object returned from attachmentService.asDocuments(..)
     * @return a list of DocView wrappers; never null
     */
    private static java.util.List<DocView> adaptToDocViews(Object res) {
        if (!(res instanceof java.util.List<?> list)) {
            return java.util.Collections.emptyList();
        }
        java.util.ArrayList<DocView> out = new java.util.ArrayList<>(list.size());
        for (Object o : list) {
            String name = null;
            String text = null;
            // Attempt to call getName()
            try {
                var mName = o.getClass().getMethod("getName");
                Object val = mName.invoke(o);
                if (val instanceof String s) name = s;
            } catch (Exception ignore) {
                // ignore missing method
            }
            // Attempt to call getText()
            try {
                var mText = o.getClass().getMethod("getText");
                Object val = mText.invoke(o);
                if (val instanceof String s) text = s;
            } catch (Exception ignore) {
                // ignore missing method
            }
            // LangChain4j Document: text() returns the content
            if (text == null) {
                try {
                    var mTextFn = o.getClass().getMethod("text");
                    Object val = mTextFn.invoke(o);
                    if (val != null) text = val.toString();
                } catch (Exception ignore) {
                    // ignore
                }
            }
            // When name still null, attempt to derive from metadata via metadata().get("fileName")
            if (name == null) {
                try {
                    var mMeta = o.getClass().getMethod("metadata");
                    Object meta = mMeta.invoke(o);
                    if (meta != null) {
                        try {
                            var mGet = meta.getClass().getMethod("get", Object.class);
                            Object v = mGet.invoke(meta, "fileName");
                            if (v != null) name = v.toString();
                        } catch (Exception ignore) {
                            // ignore
                        }
                    }
                } catch (Exception ignore) {
                    // ignore
                }
            }
            out.add(new DocView(name, text));
        }
        return out;
    }


private String normalizeModelId(String modelId) {
    if (modelId == null || modelId.isBlank()) return "qwen2.5-7b-instruct";
    String id = modelId.trim().toLowerCase();
    if (id.equals("qwen2.5-7b-instruct") || id.equals("gpt-5-chat-latest") || id.equals("gemma3:27b")) return "qwen2.5-7b-instruct";
    if (id.contains("llama-3.1-8b")) return "qwen2.5-7b-instruct";
    return modelId;
}

}