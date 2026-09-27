package com.example.lms.service.chat;

import com.example.lms.domain.ChatMessage;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.search.TraceStore;
import com.example.lms.service.guard.InfoFailurePatterns;
import com.example.lms.trace.SafeRedactor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * DB 기반 ChatHistoryService.
 *
 * <p>CuriosityTriggerService 가 gap detection 을 수행하려면
 * summarizeRecentLowConfidence() 가 "" 가 아닌 데이터를 제공해야 한다.
 * InMemoryChatHistoryService 의 stub("" 반환) 때문에 Curiosity 루프가 영구 스킵되는
 * 문제를 보완한다.</p>
 */
@Service
@Primary
@RequiredArgsConstructor
public class JpaChatHistorySummaryService implements ChatHistoryService {
    private static final Logger log = LoggerFactory.getLogger(JpaChatHistorySummaryService.class);

    /**
     * Repository is optional because some deployments run without JPA (e.g., shim/dev modes).
     * In that case we still want a ChatHistoryService bean so CuriosityTriggerService can
     * safely attempt summarization (it will simply return empty and skip).
     */
    private final ObjectProvider<ChatMessageRepository> chatMessageRepository;

    @Override
    public String summarizeRecentLowConfidence(int limit) {
        int lim = Math.max(1, Math.min(limit, 12));
        int fetch = Math.max(10, lim * 4);

        ChatMessageRepository repo = chatMessageRepository.getIfAvailable();
        if (repo == null) {
            return "";
        }

        List<ChatMessage> recent;
        try {
            recent = repo.findByRoleOrderByIdDesc("assistant", PageRequest.of(0, fetch));
        } catch (Exception ignore) {
            log.debug("[JpaChatHistorySummary] fail-soft stage={}", "repo.findRecent");
            traceFailSoft("repo.findRecent", ignore);
            // fail-soft: DB 사용 불가/미설정 환경이면 빈 문자열로 돌아가 Curiosity가 skip 하도록 둔다.
            return "";
        }

        if (recent == null || recent.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (ChatMessage m : recent) {
            if (m == null) continue;
            String c = m.getContent();
            if (c == null || c.isBlank()) continue;

            if (!InfoFailurePatterns.looksLikeFailure(c)) {
                continue;
            }

            String shortC = c.replaceAll("\\s+", " ").trim();
            if (shortC.length() > 160) {
                shortC = shortC.substring(0, 160) + "...";
            }

            if (added > 0) {
                sb.append("\n---\n");
            }
            sb.append(shortC);
            added++;

            if (added >= lim) {
                break;
            }
        }

        return sb.toString();
    }

    private static void traceFailSoft(String stage, Throwable error) {
        try {
            String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
            String errorType = SafeRedactor.traceLabelOrFallback(
                    error == null ? null : error.getClass().getSimpleName(), "unknown");
            TraceStore.put("chat.history.summary.failSoft", true);
            TraceStore.put("chat.history.summary.failSoft.stage", safeStage);
            TraceStore.put("chat.history.summary.failSoft.errorType", errorType);
            TraceStore.put("chat.history.summary.failSoft." + safeStage, true);
            TraceStore.put("chat.history.summary.failSoft." + safeStage + ".errorType", errorType);
            TraceStore.put("chat.history.summary.failSoft." + safeStage + ".errorHash",
                    SafeRedactor.hashValue(messageOf(error)));
            TraceStore.put("chat.history.summary.failSoft." + safeStage + ".errorLength", messageLength(error));
        } catch (RuntimeException traceFailure) {
            log.debug("[JpaChatHistorySummary] fail-soft trace failed. errorHash={} errorLength={}",
                    SafeRedactor.hashValue(messageOf(traceFailure)), messageLength(traceFailure));
        }
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String message = messageOf(t);
        return message == null ? 0 : message.length();
    }
}
