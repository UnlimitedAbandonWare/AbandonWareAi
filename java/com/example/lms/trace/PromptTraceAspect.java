// src/main/java/com/example/lms/trace/PromptTraceAspect.java
package com.example.lms.trace;

import com.acme.aicore.domain.model.Prompt;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
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
public class PromptTraceAspect {

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
        String systemPreview = TraceLogger.preview(p.system());
        String userPreview = TraceLogger.preview(p.user());
        String contextPreview = TraceLogger.preview(p.context());

        // Concatenate all parts and redact secrets before hashing
        StringBuilder full = new StringBuilder();
        if (p.system() != null) full.append(p.system());
        if (p.user() != null) full.append(p.user());
        if (p.context() != null) full.append(p.context());
        String redactedAll = SafeRedactor.redact(full.toString());
        String hash = sha256(redactedAll);
        int length = redactedAll.length();
        String tplId = jp.getSignature().getName();
        TraceLogger.emit("prompt", "prompt", Map.of(
                "tpl_id", tplId,
                "user_preview", userPreview,
                "ctx_preview", contextPreview,
                "hash", hash,
                "len", length
        ));
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
            return "na";
        }
    }
}