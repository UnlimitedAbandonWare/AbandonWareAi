// src/main/java/com/example/lms/debug/PromptDebugLogger.java
package com.example.lms.debug;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


/**
 * Logs prompts and responses when debugging flags are enabled.  This logger
 * consults Spring configuration properties to determine whether prompts and
 * responses should be dumped, and whether secrets should be masked.  When
 * dumping is disabled the methods are no-ops.  Maximum length limits are
 * respected to avoid flooding logs with large payloads.
 */
@Component
public class PromptDebugLogger {
    private static final Logger log = LoggerFactory.getLogger(PromptDebugLogger.class);

    private static final int DEFAULT_MAX_BYTES = 4096;

    private final Environment env;

    @Autowired
    public PromptDebugLogger(Environment env) {
        this.env = env;
    }

    /**
     * Dump the built prompt for debugging.  The behaviour is controlled by
     * the {@code lms.debug.prompts.dump} and {@code lms.debug.mask-secrets}
     * properties.  When disabled, this method does nothing.
     *
     * @param ctx an optional context describing the prompt context (unused)
     * @param prompt the built prompt string to dump
     */
    public void dumpPrompt(Object ctx, String prompt) {
        boolean dump = Boolean.parseBoolean(env.getProperty("lms.debug.prompts.dump", "false"));
        if (!dump || prompt == null) return;
        boolean mask = Boolean.parseBoolean(env.getProperty("lms.debug.mask-secrets", "true"));
        String msg = prompt;
        if (mask) {
            msg = PromptMasker.mask(msg);
        }
        msg = truncate(msg);
        log.debug("Prompt built:\n{}", msg);
    }

    /**
     * Dump the model response for debugging.  The behaviour is controlled by
     * the {@code lms.debug.responses.dump} and {@code lms.debug.mask-secrets}
     * properties.  When disabled, this method does nothing.
     *
     * @param model the effective model name
     * @param response the text returned by the model
     */
    public void dumpResponse(String model, String response) {
        boolean dump = Boolean.parseBoolean(env.getProperty("lms.debug.responses.dump", "false"));
        if (!dump || response == null) return;
        boolean mask = Boolean.parseBoolean(env.getProperty("lms.debug.mask-secrets", "true"));
        String msg = response;
        if (mask) {
            msg = PromptMasker.mask(msg);
        }
        msg = truncate(msg);
        log.debug("Response from model {}:\n{}", model, msg);
    }

    private String truncate(String text) {
        int maxBytes = DEFAULT_MAX_BYTES;
        try {
            String cfg = env.getProperty("lms.debug.max-bytes");
            if (cfg != null) {
                maxBytes = Integer.parseInt(cfg.trim());
            }
        } catch (Exception ignore) {
        }
        if (text == null) return null;
        // naive byte count (UTF-8) approximation by character length
        if (text.length() > maxBytes) {
            return text.substring(0, maxBytes) + "/* ... *&#47;";
        }
        return text;
    }
}