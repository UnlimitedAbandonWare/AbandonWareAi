package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.session.SessionKeyUtil;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.TraceContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Conversation breadcrumb enforcement.
 *
 * <p>Goal: keep a stable conversation-level sid ({@code chat-<sessionId>}) across
 * chunked requests, while preserving the raw/browser sid in {@code MDC[requestSid]}.
 *
 * <p>NOTE: TraceStore is cleared early inside {@code ChatWorkflow.continueChat(..)}.
 * Apply MDC before the call, then publish the redacted trace breadcrumb after it returns.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 8)
public class ConversationBreadcrumbAspect {
    private static final Logger log = LoggerFactory.getLogger(ConversationBreadcrumbAspect.class);

    private final NovaOrchestrationProperties props;

    public ConversationBreadcrumbAspect(NovaOrchestrationProperties props) {
        this.props = props;
    }

    @Around("execution(* com.example.lms.service.ChatWorkflow.continueChat(com.example.lms.dto.ChatRequestDto,..))")
    public Object aroundContinueChat(ProceedingJoinPoint pjp) throws Throwable {
        NovaOrchestrationProperties.BreadcrumbProps bc = (props == null) ? null : props.getBreadcrumb();
        if (bc == null || !bc.isEnabled()) {
            return pjp.proceed();
        }
        if (!bc.isOverrideSidWithConversationSid()) {
            return pjp.proceed();
        }

        Object[] args0 = pjp.getArgs();
        ChatRequestDto req = null;
        if (args0 != null && args0.length > 0 && args0[0] instanceof ChatRequestDto) {
            req = (ChatRequestDto) args0[0];
        }
        if (req == null || req.getSessionId() == null) {
            return pjp.proceed();
        }

        String convSid = SessionKeyUtil.conversationSid(bc.getConversationSidPrefix(), req.getSessionId());
        if (SessionKeyUtil.isBlank(convSid)) {
            return pjp.proceed();
        }

        final String requestSidKey = (bc.getRequestSidKey() == null || bc.getRequestSidKey().isBlank())
                ? "requestSid"
                : bc.getRequestSidKey();
        final String chatSessionIdKey = (bc.getChatSessionIdKey() == null || bc.getChatSessionIdKey().isBlank())
                ? "chatSessionId"
                : bc.getChatSessionIdKey();

        // Capture current state.
        final String prevRequestSid = MDC.get(requestSidKey);
        final String prevChatSessionId = MDC.get(chatSessionIdKey);
        final String prevSid = MDC.get("sid");

        // SoT snapshot: clone args once, then proceed(args) exactly once.
        final Object[] args = (args0 == null) ? null : args0.clone();

        try {
            // Preserve raw/browser sid for later debugging (if caller hasn't already set one).
            if (bc.isKeepRequestSid() && SessionKeyUtil.isBlank(prevRequestSid)) {
                if (!SessionKeyUtil.isBlank(prevSid) && !prevSid.equals(convSid)) {
                    MDC.put(requestSidKey, prevSid);
                }
            }

            // Optional: make numeric session id visible in MDC.
            MDC.put(chatSessionIdKey, String.valueOf(req.getSessionId()));

            // Force conversation sid.
            MDC.put("sid", convSid);
            MDC.put("sessionId", convSid);
            return (args == null) ? pjp.proceed() : pjp.proceed(args);
        } finally {
            recordConversationBreadcrumb(convSid, prevSid, req.getSessionId());
            // If the caller already had these keys set, restore them; otherwise leave them for TraceFilter to clean up.
            if (prevRequestSid != null) {
                restore(requestSidKey, prevRequestSid);
            }
            if (prevChatSessionId != null) {
                restore(chatSessionIdKey, prevChatSessionId);
            }
        }
    }

    private static void restore(String key, String prev) {
        if (prev == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, prev);
        }
    }

    private static void recordConversationBreadcrumb(String convSid, String requestSid, Long chatSessionId) {
        try {
            TraceStore.putIfAbsent("conversation.sid", SafeRedactor.hashValue(convSid));
            if (requestSid != null && !requestSid.isBlank()) {
                TraceStore.putIfAbsent("conversation.requestSid", SafeRedactor.hashValue(requestSid));
            }
            if (chatSessionId != null) {
                TraceStore.putIfAbsent("conversation.chatSessionHash", SafeRedactor.hashValue(String.valueOf(chatSessionId)));
            }
            TraceContext.current().setFlag("conversation.sid", SafeRedactor.hashValue(convSid));

            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("queryRedacted", true);
            data.put("stage", "conversation_sid");
            data.put("relevance", 0.0d);
            data.put("routeDecision", "conversation_sid_applied");
            data.put("chatSessionHash", SafeRedactor.hashValue(String.valueOf(chatSessionId)));
            data.put("hasRequestSid", requestSid != null && !requestSid.isBlank());
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("v", 1);
            row.put("seq", TraceStore.nextSequence("ml.breadcrumbs.v1"));
            row.put("ts", java.time.Instant.now().toString());
            row.put("component", "ConversationBreadcrumbAspect");
            row.put("rules", "conversation_sid");
            row.put("decision", "conversation_sid_applied");
            row.put("requestId", SafeRedactor.hashValue(firstNonBlank(MDC.get("x-request-id"), TraceStore.getString("requestId"))));
            row.put("sessionId", SafeRedactor.hashValue(convSid));
            row.put("data", data);
            TraceStore.append("ml.breadcrumbs.v1", row);
            TraceStore.putIfAbsent("cihRag.breadcrumb.queryRedacted", true);
            TraceStore.putIfAbsent("cihRag.breadcrumb.stage", "conversation_sid");
            TraceStore.putIfAbsent("cihRag.breadcrumb.relevance", 0.0d);
            TraceStore.putIfAbsent("cihRag.breadcrumb.routeDecision", "conversation_sid_applied");
        } catch (Throwable ignore) {
            log.debug("[ConversationBreadcrumbAspect] breadcrumb trace skipped stage={} errorHash={} errorLength={}",
                    SafeRedactor.traceLabelOrFallback("conversation.record", "unknown"),
                    SafeRedactor.hashValue(messageOf(ignore)), messageLength(ignore));
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank() && !"null".equalsIgnoreCase(value.trim())) {
                return value.trim();
            }
        }
        return null;
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }
}
