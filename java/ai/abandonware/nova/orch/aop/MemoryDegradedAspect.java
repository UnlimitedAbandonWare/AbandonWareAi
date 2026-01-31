package ai.abandonware.nova.orch.aop;

import ai.abandonware.nova.orch.storage.DegradedStorage;
import ai.abandonware.nova.orch.storage.PendingMemoryEvent;
import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.service.guard.EvidenceAwareGuard;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * When long-term memory writes are skipped (HYBRID/FREE/ALLOW_NO_MEMORY),
 * store a minimal candidate to a degraded storage so that data isn't silently lost.
 */
@Aspect
public class MemoryDegradedAspect {

    private static final Logger log = LoggerFactory.getLogger(MemoryDegradedAspect.class);

    private final DegradedStorage storage;

    public MemoryDegradedAspect(DegradedStorage storage) {
        this.storage = storage;
    }

    @Around("execution(* com.example.lms.service.MemoryReinforcementService.reinforceFromGuardDecision(..))")
    public Object aroundReinforce(ProceedingJoinPoint pjp) throws Throwable {
        Object ret = pjp.proceed();

        try {
            Object[] args = pjp.getArgs();
            if (args == null || args.length < 3) return ret;

            String sessionId = (args[0] instanceof String s) ? s : null;
            String query = (args[1] instanceof String s) ? s : null;

            EvidenceAwareGuard.GuardDecision decision = (args[2] instanceof EvidenceAwareGuard.GuardDecision d) ? d : null;
            MemoryMode mode = (args.length >= 4 && args[3] instanceof MemoryMode m) ? m : null;

            if (sessionId == null || sessionId.isBlank()) return ret;
            if (query == null || query.isBlank()) return ret;

            String reason = null;
            if (mode != null && !mode.isWriteEnabled()) {
                reason = "MEMORY_MODE_WRITE_DISABLED:" + mode.name();
            } else if (decision != null && decision.action() == EvidenceAwareGuard.GuardAction.ALLOW_NO_MEMORY) {
                reason = "ALLOW_NO_MEMORY";
            }

            if (reason == null) return ret;

            // If memory write is disabled by mode, don't create pending events.
            // This avoids pointless IO and log noise when the system is intentionally running read-only.
            if (reason.startsWith("MEMORY_MODE_WRITE_DISABLED")) {
                log.debug("[NovaDegradedStorage] skip pending event because memory write disabled. mode={}", mode);
                return ret;
            }

            String answer = (decision != null) ? decision.finalDraft() : null;
            String snippet = (answer != null && answer.length() > 200) ? answer.substring(0, 200) : answer;

            // If we don't have anything meaningful to store, don't create noise.
            if (snippet == null || snippet.isBlank()) {
                return ret;
            }

            if (query == null || query.isBlank()) {
                return ret;
            }

            storage.putPending(new PendingMemoryEvent(
                    Instant.now(),
                    sessionId,
                    sha256_16(query),
                    snippet,
                    reason
            ));

            log.info("[NovaDegradedStorage] stored PENDING event reason={}", reason);
        } catch (Exception ignore) {
            // fail-soft
        }

        return ret;
    }

    private String sha256_16(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.substring(0, 16);
        } catch (Exception e) {
            return "";
        }
    }
}
