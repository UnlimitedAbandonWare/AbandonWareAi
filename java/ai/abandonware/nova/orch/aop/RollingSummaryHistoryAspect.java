package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.chunk.ChunkEnvelope;
import ai.abandonware.nova.orch.chunk.RollingSummaryEnvelope;
import com.example.lms.domain.ChatMessage;
import com.example.lms.repository.ChatMessageRepository;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;

/**
 * Enhances prompt history for long/chunked conversations:
 * <ul>
 *   <li>Prepends the latest rolling summary (⎔RSUM⎔) without consuming the history limit</li>
 *   <li>Strips chunk-envelope headers from user lines in the formatted history (prompt cleanliness)</li>
 * </ul>
 */
@Aspect
public class RollingSummaryHistoryAspect {

    private static final Logger log = LoggerFactory.getLogger(RollingSummaryHistoryAspect.class);

    private final NovaOrchestrationProperties props;
    private final ChatMessageRepository messageRepository;

    public RollingSummaryHistoryAspect(NovaOrchestrationProperties props, ChatMessageRepository messageRepository) {
        this.props = props;
        this.messageRepository = messageRepository;
    }

    @Around("execution(* com.example.lms.service.ChatHistoryService.getFormattedRecentHistory(..))")
    @SuppressWarnings("unchecked")
    public Object aroundGetFormattedRecentHistory(ProceedingJoinPoint pjp) throws Throwable {
        final Object[] args0 = pjp.getArgs();
        final Long sessionId;
        if (args0 != null && args0.length > 0 && args0[0] instanceof Number n) {
            sessionId = n.longValue();
        } else {
            sessionId = null;
        }

        Object out = pjp.proceed();
        if (!(out instanceof List<?> list)) {
            return out;
        }
        if (sessionId == null) {
            return out;
        }

        NovaOrchestrationProperties.ChunkingProps ch = (props != null) ? props.getChunking() : null;
        if (ch == null || !ch.isEnabled()) {
            return out;
        }

        List<String> formatted = (List<String>) list;
        List<String> cleaned = maybeStripChunkEnvelopes(formatted);

        if (!ch.isPrependRollingSummaryInHistory()) {
            return cleaned;
        }

        // Avoid double-inject if a caller already prepended it (or if formatting included it).
        for (String line : cleaned) {
            if (line != null && line.startsWith("System: (Rolling Summary)")) {
                return cleaned;
            }
        }

        try {
            String injected = buildInjectedSummaryLine(sessionId, ch);
            if (injected == null || injected.isBlank()) {
                return cleaned;
            }
            List<String> result = new ArrayList<>(cleaned.size() + 1);
            result.add(injected);
            result.addAll(cleaned);
            return result;
        } catch (Exception e) {
            log.debug("RSUM prepend skipped (sessionId={}): {}", sessionId, e.toString());
            return cleaned;
        }
    }

    private List<String> maybeStripChunkEnvelopes(List<String> formatted) {
        if (formatted == null || formatted.isEmpty()) {
            return formatted;
        }
        List<String> out = new ArrayList<>(formatted.size());
        for (String line : formatted) {
            if (line == null) {
                out.add(null);
                continue;
            }
            // Expected format from ChatHistoryServiceImpl: "User: <content>" etc.
            if (line.startsWith("User: ")) {
                String raw = line.substring("User: ".length());
                ChunkEnvelope env = ChunkEnvelope.parse(raw);
                if (env.meta() != null) {
                    out.add("User: " + env.payload());
                    continue;
                }
            }
            out.add(line);
        }
        return out;
    }

    private String buildInjectedSummaryLine(Long sessionId, NovaOrchestrationProperties.ChunkingProps ch) {
        int scanLimit = Math.max(8, ch.getSummaryScanLimit());
        List<ChatMessage> recent = messageRepository.findBySession_IdOrderByCreatedAtDesc(
                sessionId, PageRequest.of(0, scanLimit));

        for (ChatMessage m : recent) {
            if (m == null) {
                continue;
            }
            if (!"system".equalsIgnoreCase(m.getRole())) {
                continue;
            }
            RollingSummaryEnvelope env = RollingSummaryEnvelope.parse(m.getContent());
            if (env == null) {
                continue;
            }
            String summary = env.summary();
            if (summary == null || summary.isBlank()) {
                return null;
            }
            String clipped = clip(summary.trim(), ch.getHistoryPrependMaxChars());
            return "System: (Rolling Summary)\n" + clipped;
        }
        return null;
    }

    private static String clip(String s, int maxChars) {
        if (s == null) {
            return null;
        }
        int m = Math.max(1, maxChars);
        if (s.length() <= m) {
            return s;
        }
        return s.substring(0, m);
    }
}
