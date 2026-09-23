package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatResult;
import com.example.lms.service.postprocess.FinalAnswerPostProcessor;
import com.example.lms.service.postprocess.OutputSanitizer;
import com.example.lms.trace.SafeRedactor;
import com.example.lms.util.HashUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.Set;

/**
 * Output-boundary guard: prevents internal trace/diagnostic strings from
 * leaking into user-visible answers.
 *
 * <p>
 * Motivation: TraceStore / probe / diagnostics are valuable for operators, but
 * must not be mixed into
 * the end-user answer. We cut known trace appendix markers and record a safe
 * breadcrumb into TraceStore.
 */
@Aspect
@Order(Ordered.LOWEST_PRECEDENCE - 5)
public class CleanOutputRedactionAspect {

    private static final Logger log = LoggerFactory.getLogger(CleanOutputRedactionAspect.class);
    private static final Set<String> CANONICAL_POSTPROCESS_REASONS = Set.of(
            "none",
            "blank_content",
            "diagnostics_removed",
            "diagnostics_removed_empty");

    private final Environment env;
    private final FinalAnswerPostProcessor finalAnswerPostProcessor;

    public CleanOutputRedactionAspect(Environment env) {
        this.env = env;
        this.finalAnswerPostProcessor = new FinalAnswerPostProcessor(new OutputSanitizer());
    }

    private boolean enabled() {
        return env.getProperty("nova.orch.output.clean.enabled", Boolean.class, true);
    }

    @Around("execution(com.example.lms.service.ChatResult com.example.lms.service.ChatService.ask(..))"
            + " || execution(com.example.lms.service.ChatResult com.example.lms.service.ChatService.continueChat(..))")
    public Object redactUserVisibleOutput(ProceedingJoinPoint pjp) throws Throwable {
        Object out = pjp.proceed();
        if (!enabled()) {
            return out;
        }
        if (!(out instanceof ChatResult cr)) {
            return out;
        }
        if (canonicalFinalizationObserved(cr)) {
            try {
                TraceStore.put("orch.output.redaction.skipped", "canonical_postprocess");
            } catch (Throwable ignored) {
                traceSuppressed("canonical.skip.trace", ignored);
            }
            return out;
        }

        FinalAnswerPostProcessor.Result sanitized = finalAnswerPostProcessor.process(
                new FinalAnswerPostProcessor.Request(cr.content()));
        if (!sanitized.changed()) {
            return out;
        }

        if ("blank_content".equals(sanitized.reasonCode())) {
            try {
                TraceStore.put("orch.output.blank.prevented", true);
                TraceStore.put("orch.output.blank.prevented.reason", "blank_content");
            } catch (Throwable ignored) {
                traceSuppressed("blank.prevented", ignored);
            }
            return withContent(cr, sanitized.content());
        }

        if ("diagnostics_removed_empty".equals(sanitized.reasonCode())) {
            try {
                TraceStore.put("orch.output.blank.prevented", true);
                TraceStore.put("orch.output.blank.prevented.reason", "diagnostics_removed_empty");
                TraceStore.put("orch.output.blank.prevented.marker", sanitized.marker());
            } catch (Throwable ignored) {
                traceSuppressed("blank.prevented.marker", ignored);
            }
        }

        try {
            TraceStore.put("orch.output.redaction.applied", true);
            TraceStore.put("orch.output.redaction.marker", sanitized.marker());
            TraceStore.put("orch.output.redaction.removedChars", sanitized.removedChars());
            TraceStore.put("orch.output.redaction.removedHash", sanitized.removedHash());
        } catch (Throwable ignored) {
            traceSuppressed("redaction.applied", ignored);
        }

        if (log.isDebugEnabled()) {
            log.debug("CleanOutputRedactionAspect redacted injected trace tail (marker={}, removedChars={})",
                    sanitized.marker(), sanitized.removedChars());
        }

        return withContent(cr, sanitized.content());
    }

    private static boolean canonicalFinalizationObserved(ChatResult result) {
        try {
            Object reason = TraceStore.get("finalAnswer.postprocess.reason");
            Object contentHash = TraceStore.get("finalAnswer.postprocess.contentHash");
            return TraceStore.get("finalAnswer.memorySaveAllowed") instanceof Boolean
                    && reason instanceof String value
                    && CANONICAL_POSTPROCESS_REASONS.contains(value)
                    && contentHash instanceof String expectedHash
                    && expectedHash.equals(HashUtil.sha256(result.content()));
        } catch (Throwable ignored) {
            traceSuppressed("canonical.marker.read", ignored);
            return false;
        }
    }

    private static ChatResult withContent(ChatResult source, String content) {
        return new ChatResult(
                content,
                source.modelUsed(),
                source.ragUsed(),
                source.evidence(),
                source.evidenceMetadata());
    }

    private static void traceSuppressed(String stage, Throwable error) {
        if (!log.isDebugEnabled()) {
            return;
        }
        log.debug("CleanOutputRedactionAspect trace fallback (stage={} errorHash={} errorLength={})",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"),
                SafeRedactor.hashValue(messageOf(error)), messageLength(error));
    }

    private static String messageOf(Throwable error) {
        return error == null ? "" : String.valueOf(error.getMessage());
    }

    private static int messageLength(Throwable error) {
        return messageOf(error).length();
    }

}
