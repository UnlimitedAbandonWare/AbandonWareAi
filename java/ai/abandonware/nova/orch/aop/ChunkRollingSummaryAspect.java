package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.config.NovaOrchestrationProperties;
import ai.abandonware.nova.orch.chunk.ChunkEnvelope;
import ai.abandonware.nova.orch.chunk.RollingSummaryEnvelope;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.rag.pre.LongInputDistillationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rolling-summary maintainer for long/chunked user uploads.
 *
 * <p>Stores a server meta message:
 * <pre>
 *   ⎔RSUM⎔{json}\n&lt;summary&gt;
 * </pre>
 *
 * and updates it only every N chunks or when a budget-trigger threshold is exceeded.
 */
@Aspect
public class ChunkRollingSummaryAspect {

    public static final String RSUM_PREFIX = RollingSummaryEnvelope.PREFIX;

    private static final Logger log = LoggerFactory.getLogger(ChunkRollingSummaryAspect.class);

    private final NovaOrchestrationProperties props;
    private final ChatMessageRepository messageRepository;
    private final ChatHistoryService historyService;
    private final LongInputDistillationService distillationService;
    private final ObjectMapper objectMapper;

    public ChunkRollingSummaryAspect(
            NovaOrchestrationProperties props,
            ChatMessageRepository messageRepository,
            ChatHistoryService historyService,
            LongInputDistillationService distillationService,
            ObjectMapper objectMapper) {
        this.props = props;
        this.messageRepository = messageRepository;
        this.historyService = historyService;
        this.distillationService = distillationService;
        this.objectMapper = objectMapper;
    }

    @Around("execution(* com.example.lms.service.ChatHistoryService.appendMessage(..))")
    public Object aroundAppendMessage(ProceedingJoinPoint pjp) throws Throwable {
        NovaOrchestrationProperties.ChunkingProps ch = (props != null) ? props.getChunking() : null;
        if (ch == null || !ch.isEnabled()) {
            return pjp.proceed();
        }

        final Object[] args0 = pjp.getArgs();
        if (args0 == null || args0.length < 3) {
            return pjp.proceed();
        }
        final Long sessionId;
        if (args0[0] instanceof Number n) {
            sessionId = n.longValue();
        } else {
            sessionId = null;
        }
        final String role = (args0[1] == null) ? null : String.valueOf(args0[1]);
        final String content = (args0[2] == null) ? null : String.valueOf(args0[2]);

        if (sessionId == null || role == null) {
            return pjp.proceed();
        }

        // Avoid recursion when writing RSUM itself.
        if ("system".equalsIgnoreCase(role) && content != null && content.startsWith(RSUM_PREFIX)) {
            return pjp.proceed();
        }

        // Only user messages contribute to rolling summary.
        if (!"user".equalsIgnoreCase(role)) {
            return pjp.proceed();
        }

        final ChunkEnvelope env = ChunkEnvelope.parse(content);
        final String payload = env.payload();
        final ChunkEnvelope.Meta meta = env.meta();

        // Optionally strip envelope on persist.
        Object result;
        if (meta != null && ch.isStripChunkEnvelopeOnPersist()) {
            // SoT snapshot: clone args once, then proceed(args) exactly once.
            Object[] args = args0.clone();
            args[0] = sessionId;
            args[1] = role;
            args[2] = payload;
            result = pjp.proceed(args);
        } else {
            result = pjp.proceed();
        }

        if (!isChunkCandidate(payload, meta, ch)) {
            return result;
        }

        // Cost throttle: only update every N chunks or on budget-trigger.
        try {
            maybeUpdateRollingSummary(sessionId, env, ch);
        } catch (Exception e) {
            log.debug("RSUM update skipped (sessionId={}): {}", sessionId, e.toString());
        }

        return result;
    }

    @Around("execution(* com.example.lms.service.ChatHistoryService.startNewSession(..))")
    public Object aroundStartNewSession(ProceedingJoinPoint pjp) throws Throwable {
        NovaOrchestrationProperties.ChunkingProps ch = (props != null) ? props.getChunking() : null;
        if (ch == null || !ch.isEnabled()) {
            return pjp.proceed();
        }

        Object[] args = pjp.getArgs();
        if (args == null || args.length < 1 || !(args[0] instanceof String)) {
            return pjp.proceed();
        }

        String firstMessage = (String) args[0];
        ChunkEnvelope env = ChunkEnvelope.parse(firstMessage);
        String payload = env.payload();
        ChunkEnvelope.Meta meta = env.meta();

        Object result;
        if (meta != null && ch.isStripChunkEnvelopeOnPersist()) {
            Object[] newArgs = args.clone();
            newArgs[0] = payload;
            result = pjp.proceed(newArgs);
        } else {
            result = pjp.proceed();
        }

        if (!ch.isCreateSummaryOnFirstChunk()) {
            return result;
        }

        Long createdSessionId = extractSessionId(result);
        if (createdSessionId == null) {
            return result;
        }

        if (!isChunkCandidate(payload, meta, ch)) {
            return result;
        }

        try {
            // Force an initial summary if none exists yet.
            maybeUpdateRollingSummary(createdSessionId, env, ch);
        } catch (Exception e) {
            log.debug("RSUM init skipped (sessionId={}): {}", createdSessionId, e.toString());
        }

        return result;
    }

    private static Long extractSessionId(Object startNewSessionResult) {
        if (!(startNewSessionResult instanceof Optional<?> opt)) {
            return null;
        }
        if (opt.isEmpty()) {
            return null;
        }
        Object v = opt.get();
        if (v instanceof ChatSession s) {
            return s.getId();
        }
        return null;
    }

    private static boolean isChunkCandidate(String payload, ChunkEnvelope.Meta meta, NovaOrchestrationProperties.ChunkingProps ch) {
        if (meta != null) {
            return true;
        }
        if (payload == null) {
            return false;
        }
        return payload.length() >= Math.max(1, ch.getUserMinChars());
    }

    private void maybeUpdateRollingSummary(Long sessionId, ChunkEnvelope current, NovaOrchestrationProperties.ChunkingProps ch) {
        if (distillationService == null) {
            return;
        }

        final int scanLimit = Math.max(8, ch.getSummaryScanLimit());
        final int everyN = Math.max(1, ch.getSummaryEveryNChunks());

        // Resolve last RSUM outside the scan window (prevents "false initial" triggers on long sessions).
        ChatMessage lastRsumMsg = null;
        RollingSummaryEnvelope lastRsum = null;
        try {
            lastRsumMsg = messageRepository
                    .findTopBySession_IdAndRoleAndContentStartingWithOrderByIdDesc(sessionId, "system", RSUM_PREFIX)
                    .orElse(null);
        } catch (Exception ignore) {
        }
        if (lastRsumMsg != null) {
            lastRsum = RollingSummaryEnvelope.parse(lastRsumMsg.getContent());
        }

        final boolean hasRsum = lastRsum != null;
        final Long lastRsumId = (lastRsumMsg != null) ? lastRsumMsg.getId() : null;

        // Collect recent messages (newest-first). We page a small number of windows if needed to cross the RSUM boundary.
        final int maxPages = 3;
        int scannedPages = 0;
        List<ChatMessage> recentDesc = new ArrayList<>();
        boolean reachedBoundary = !hasRsum;
        for (int page = 0; page < maxPages && !reachedBoundary; page++) {
            scannedPages++;
            List<ChatMessage> pageMsgs = messageRepository.findBySession_IdOrderByCreatedAtDesc(
                    sessionId, PageRequest.of(page, scanLimit));
            if (pageMsgs == null || pageMsgs.isEmpty()) {
                reachedBoundary = true;
                break;
            }
            recentDesc.addAll(pageMsgs);

            // If we got a short page, there are no more messages.
            if (pageMsgs.size() < scanLimit) {
                reachedBoundary = true;
            }

            // Stop once we reach (or cross) the latest RSUM message.
            if (hasRsum && lastRsumId != null) {
                for (ChatMessage m : pageMsgs) {
                    if (m != null && m.getId() != null && m.getId() <= lastRsumId) {
                        reachedBoundary = true;
                        break;
                    }
                }
            }
        }

        if (!hasRsum) {
            // For an initial RSUM, only the latest window is needed.
            recentDesc = messageRepository.findBySession_IdOrderByCreatedAtDesc(
                    sessionId, PageRequest.of(0, scanLimit));
            scannedPages = 1;
            reachedBoundary = true;
        }

        // Candidate chunks since last RSUM (newest-first).
        List<ChunkEnvelope> candidatesNewestFirst = new ArrayList<>();
        for (ChatMessage m : recentDesc) {
            if (m == null) continue;
            if (!"user".equalsIgnoreCase(m.getRole())) continue;
            if (hasRsum && lastRsumId != null && m.getId() != null && m.getId() <= lastRsumId) {
                continue;
            }
            ChunkEnvelope env = ChunkEnvelope.parse(m.getContent());
            if (!isChunkCandidate(env.payload(), env.meta(), ch)) continue;
            candidatesNewestFirst.add(env);
        }

        // Ensure current is included (it should be in the latest window, but be defensive).
        if (isChunkCandidate(current.payload(), current.meta(), ch)) {
            boolean exists = false;
            for (ChunkEnvelope e : candidatesNewestFirst) {
                if (samePayload(e, current)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                candidatesNewestFirst.add(0, current);
            }
        }

        int candidateCount = candidatesNewestFirst.size();
        if (candidateCount <= 0) {
            return;
        }

        // Decide trigger.
        String payload = current.payload();
        boolean budgetTriggered = payload != null && payload.length() >= Math.max(1, ch.getBudgetTriggerChars());
        boolean periodicTriggered = candidateCount >= everyN;
        boolean initialTriggered = !hasRsum;
        boolean overflowTriggered = !reachedBoundary && candidateCount >= Math.max(1, Math.min(scanLimit, everyN));

        if (!(budgetTriggered || periodicTriggered || initialTriggered || overflowTriggered)) {
            return;
        }

        String trigger = budgetTriggered ? "budget" : (initialTriggered ? "initial" : (overflowTriggered ? "overflow" : "periodic"));

        // Build distillation input.
        String existing = "";
        if (hasRsum) {
            String s = lastRsum.summary();
            if (s != null && !s.isBlank()) {
                existing = "Existing rolling summary (update, keep key facts):\n" + s.trim() + "\n\n";
            }
        }

        int maxInputChars = Math.max(400, ch.getDistillMaxInputChars());
        int remaining = Math.max(200, maxInputChars - existing.length());

        // Prefer newest chunks (to guarantee the current chunk is included), but keep chronology in the final input.
        List<String> blocksNewestFirst = new ArrayList<>();
        for (ChunkEnvelope env : candidatesNewestFirst) {
            if (env == null) continue;
            String block = formatChunkBlock(env, Math.max(200, ch.getChunkMaxChars()));
            if (block.isBlank()) continue;

            if (blocksNewestFirst.isEmpty() && block.length() > remaining) {
                blocksNewestFirst.add(block.substring(0, Math.max(1, remaining)));
                remaining = 0;
                break;
            }
            if (block.length() > remaining) {
                break;
            }

            blocksNewestFirst.add(block);
            remaining -= block.length();

            // Hard cap: avoid feeding too many independent blocks.
            if (blocksNewestFirst.size() >= Math.max(8, everyN * 2)) {
                break;
            }
        }

        java.util.Collections.reverse(blocksNewestFirst);

        StringBuilder newContent = new StringBuilder();
        newContent.append("New content (chunked uploads) since last RSUM:\n");
        for (String b : blocksNewestFirst) {
            newContent.append(b).append("\n\n");
        }

        String input = fitDistillBudget(existing, newContent.toString(), ch.getDistillMaxInputChars());

        String distilled;
        try {
            distilled = distillationService.distill(input)
                    .block(Duration.ofMillis(Math.max(250L, ch.getDistillTimeoutMs())));
        } catch (Exception e) {
            log.debug("distill failed (sessionId={}): {}", sessionId, e.toString());
            return;
        }

        if (distilled == null || distilled.isBlank()) {
            return;
        }

        String finalSummary = clip(distilled.trim(), ch.getRollingSummaryMaxChars());

        // Build RSUM meta.
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("v", 2);
        meta.put("sessionId", sessionId);
        meta.put("trigger", trigger);
        meta.put("updatedAt", Instant.now().toString());
        meta.put("candidateCount", candidateCount);
        meta.put("scanLimit", scanLimit);
        meta.put("scannedPages", scannedPages);
        meta.put("partial", !reachedBoundary);
        if (hasRsum && lastRsumId != null) {
            meta.put("baseRsumId", lastRsumId);
        }

        ChunkEnvelope.Meta cm = current.meta();
        if (cm != null) {
            Map<String, Object> last = new LinkedHashMap<>();
            if (cm.chunkId() != null) last.put("chunkId", cm.chunkId());
            if (cm.idx() != null) last.put("idx", cm.idx());
            if (cm.total() != null) last.put("total", cm.total());
            if (cm.doc() != null) last.put("doc", cm.doc());
            if (!last.isEmpty()) {
                meta.put("lastChunk", last);
            }
        }

        String metaJson;
        try {
            metaJson = (objectMapper != null) ? objectMapper.writeValueAsString(meta) : "{}";
        } catch (Exception e) {
            metaJson = "{}";
        }

        String rsumContent = RSUM_PREFIX + metaJson + "\n" + finalSummary;

        // Persist as a system meta message.
        historyService.appendMessage(sessionId, "system", rsumContent);
    }

    private static String formatChunkBlock(ChunkEnvelope env, int payloadClipChars) {
        if (env == null) return "";
        String payload = (env.payload() == null) ? "" : env.payload();
        payload = payload.trim();
        if (payload.isEmpty()) return "";

        int clip = Math.max(200, payloadClipChars);
        if (payload.length() > clip) {
            payload = payload.substring(0, clip);
        }

        StringBuilder sb = new StringBuilder();
        ChunkEnvelope.Meta meta = env.meta();
        if (meta != null && (meta.idx() != null || meta.total() != null || meta.doc() != null || meta.chunkId() != null)) {
            sb.append("--- CHUNK ");
            if (meta.idx() != null && meta.total() != null) {
                sb.append(meta.idx()).append('/').append(meta.total());
            } else {
                sb.append('?');
            }
            if (meta.doc() != null) {
                sb.append(" doc=").append(meta.doc());
            }
            if (meta.chunkId() != null) {
                sb.append(" id=").append(meta.chunkId());
            }
            sb.append(" ---\n");
        }
        sb.append(payload);
        return sb.toString();
    }

    private static boolean samePayload(ChunkEnvelope a, ChunkEnvelope b) {
        if (a == null || b == null) return false;
        String ap = a.payload();
        String bp = b.payload();
        if (ap == null || bp == null) return false;
        return ap.equals(bp);
    }

    private static String clip(String s, int maxChars) {
        if (s == null) return null;
        int m = Math.max(1, maxChars);
        if (s.length() <= m) return s;
        return s.substring(0, m);
    }

    private static String fitDistillBudget(String existing, String newContent, int maxChars) {
        int max = Math.max(400, maxChars);
        String head = (existing == null) ? "" : existing;
        String tail = (newContent == null) ? "" : newContent;

        if (head.length() >= max) {
            // Extremely defensive: keep only head prefix
            return head.substring(0, max);
        }

        int remaining = max - head.length();
        if (tail.length() <= remaining) {
            return head + tail;
        }

        // Keep the last 'remaining' chars of new content (most recent chunks)
        String clippedTail = tail.substring(Math.max(0, tail.length() - remaining));
        return head + clippedTail;
    }
}
