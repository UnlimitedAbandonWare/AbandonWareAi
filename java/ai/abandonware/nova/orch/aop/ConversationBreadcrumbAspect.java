package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.session.SessionKeyUtil;
import com.example.lms.dto.ChatRequestDto;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;

/**
 * Conversation breadcrumb enforcement.
 *
 * <p>Goal: keep a stable conversation-level sid ({@code chat-<sessionId>}) across
 * chunked requests, while preserving the raw/browser sid in {@code MDC[requestSid]}.
 *
 * <p>NOTE: TraceStore is cleared early inside {@code ChatWorkflow.continueChat(..)};
 * this aspect focuses on MDC breadcrumbs only.
 */
@Aspect
public class ConversationBreadcrumbAspect {

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
}
