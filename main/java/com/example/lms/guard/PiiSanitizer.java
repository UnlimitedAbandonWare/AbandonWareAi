package com.example.lms.guard;

import com.example.lms.search.TraceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Simple PII sanitizer supporting email/phone patterns.
 */
@Component("legacyPiiSanitizer")
public class PiiSanitizer {

    public enum Mode { redact, mask, block }

    @Value("${guard.pii.enabled:true}")
    private boolean enabled = true;

    @Value("${guard.pii.mode:redact}")
    private String modeName = "redact";

    public PiiSanitizer() {
    }

    public PiiSanitizer(boolean enabled, String modeName) {
        this.enabled = enabled;
        this.modeName = modeName == null || modeName.isBlank() ? "redact" : modeName;
    }

    public String apply(String text) {
        if (!enabled || text == null) {
            traceApply(text, text);
            return text;
        }
        String t = maskEmail(text);
        t = maskPhone(t);
        traceApply(text, t);
        return t;
    }

    private void traceApply(String input, String output) {
        TraceStore.put("guard.pii.enabled", enabled);
        TraceStore.put("guard.pii.mode", mode().name());
        TraceStore.put("guard.pii.changed", input != null && !input.equals(output));
        TraceStore.put("guard.pii.inputLength", input == null ? 0 : input.length());
        TraceStore.put("guard.pii.outputLength", output == null ? 0 : output.length());
    }

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(\\+?\\d{1,3}[ -]?)?(\\(?\\d{2,4}\\)?[ -]?)?[\\d -]{7,}");

    private Mode mode() {
        String normalized = modeName == null ? "redact" : modeName.trim().toLowerCase();
        if ("mask".equals(normalized)) return Mode.mask;
        if ("block".equals(normalized)) return Mode.block;
        return Mode.redact;
    }

    private String maskEmail(String s) {
        return EMAIL.matcher(s).replaceAll(mode() == Mode.block ? "[blocked]" : "***@***");
    }
    private String maskPhone(String s) {
        return PHONE.matcher(s).replaceAll(mode() == Mode.block ? "[blocked]" : "********");
    }
}
