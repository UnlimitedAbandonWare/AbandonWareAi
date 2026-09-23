// src/main/java/com/example/lms/trace/PromptTraceAspect.java
package com.example.lms.trace;

import com.acme.aicore.domain.model.Prompt;
import com.example.lms.agent.context.AgentDbContextPromptInjector;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;




/**
 * Aspect that captures prompt construction events.  When a prompt is
 * assembled via the {@link com.acme.aicore.domain.ports.PromptPort}
 * abstraction, this advice records a {@code prompt} event summarising
 * the system, user and context parts.  Only a preview of each section
 * is logged to avoid leaking sensitive information.  A hash of the
 * full prompt contents (after redaction) is included to enable
 * reproducibility without storing the entire text.
 */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 110)
public class PromptTraceAspect {

    @Autowired(required = false)
    private AgentDbContextPromptInjector agentDbContextPromptInjector;

    @Around("execution(String com.example.lms.prompt.PromptBuilder+.build(com.example.lms.prompt.PromptContext)) && args(ctx)")
    public Object enrichDbContextBeforePromptBuild(ProceedingJoinPoint pjp, PromptContext ctx) throws Throwable {
        PromptContext enriched = agentDbContextPromptInjector == null ? ctx : agentDbContextPromptInjector.enrichContext(ctx);
        return pjp.proceed(new Object[]{enriched});
    }

    /**
     * Advice that runs after any implementation of
     * {@code com.acme.aicore.domain.ports.PromptPort#buildPrompt(..)}
     * completes successfully.  When the return value is not a
     * {@link Prompt}, no event is emitted.
     *
     * @param jp     the join point providing contextual data
     * @param prompt the returned prompt instance
     */
    @AfterReturning(
            pointcut = "execution(* com.acme.aicore.domain.ports.PromptPort+.buildPrompt(..))",
            returning = "prompt")
    public void afterBuild(JoinPoint jp, Object prompt) {
        if (!(prompt instanceof Prompt p)) return;
        // Concatenate all parts and redact secrets before hashing
        StringBuilder full = new StringBuilder();
        if (p.system() != null) full.append(p.system());
        if (p.user() != null) full.append(p.user());
        if (p.context() != null) full.append(p.context());
        String redactedAll = SafeRedactor.redact(full.toString());
        String hash = sha256(redactedAll);
        int length = redactedAll.length();
        String tplId = signatureName(jp);
        TraceLogger.emit("prompt", "prompt", Map.of(
                "tpl_id", tplId,
                "user_hash", hashOrEmpty(p.user()),
                "user_len", lengthOf(p.user()),
                "ctx_hash", hashOrEmpty(p.context()),
                "ctx_len", lengthOf(p.context()),
                "hash", hash,
                "len", length
        ));
    }

    private static String hashOrEmpty(String value) {
        String hash = SafeRedactor.hashValue(value);
        return hash == null ? "" : hash;
    }

    private static int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    private static String signatureName(JoinPoint jp) {
        if (jp == null) return "unknown";
        Signature signature = jp.getSignature();
        if (signature == null) return "unknown";
        String name = signature.getName();
        return name == null || name.isBlank() ? "unknown" : name;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            TraceStore.put("trace.promptTrace.suppressed.sha256", true);
            TraceStore.put("trace.promptTrace.suppressed.sha256.errorType",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"));
            return "na";
        }
    }
}
