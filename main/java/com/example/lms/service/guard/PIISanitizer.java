package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PIISanitizer {

    private static final System.Logger LOG = System.getLogger(PIISanitizer.class.getName());

    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile(
            "\\b(?:\\+?\\d{1,3}[-. ]?)?(?:\\d{2,4}[-. ]?){2}\\d{4}\\b");
    private static final Pattern KOREAN_RRN = Pattern.compile(
            "\\b\\d{6}[- ]?[1-4]\\d{6}\\b");
    private static final Pattern CARD_LIKE = Pattern.compile(
            "\\b(?:\\d[ -]*?){13,19}\\b");
    private static final Pattern BEARER_TOKEN = Pattern.compile(
            "(?i)\\bAuthorization\\s*:\\s*Bearer\\s+[^\\s,;]+");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(api[_-]?key|token|secret|password|client[_-]?secret|ownerToken)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern SUPABASE_KEY = Pattern.compile(
            "(?i)\\bsb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}\\b");

    public String mask(String text) {
        if (text == null) return null;
        ReplacementResult r = replace(EMAIL, text, "[redacted-email]");
        String t = r.text();
        int changedCount = r.count();
        r = replace(KOREAN_RRN, t, "[redacted-rrn]");
        t = r.text();
        changedCount += r.count();
        r = replace(CARD_LIKE, t, "[redacted-card]");
        t = r.text();
        changedCount += r.count();
        r = replace(PHONE, t, "[redacted-phone]");
        t = r.text();
        changedCount += r.count();
        r = replace(BEARER_TOKEN, t, "Authorization: Bearer [redacted-token]");
        t = r.text();
        changedCount += r.count();
        r = replace(SECRET_ASSIGNMENT, t, "$1=[redacted]");
        t = r.text();
        changedCount += r.count();
        r = replace(SUPABASE_KEY, t, "[redacted-secret]");
        t = r.text();
        changedCount += r.count();
        traceMaskMetrics(text.length(), t.length(), changedCount);
        return t;
    }

    private static ReplacementResult replace(Pattern pattern, String text, String replacement) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        if (count == 0) {
            return new ReplacementResult(text, 0);
        }
        return new ReplacementResult(pattern.matcher(text).replaceAll(replacement), count);
    }

    private static void traceMaskMetrics(int inputLength, int outputLength, int changedCount) {
        try {
            TraceStore.put("piiSanitizer.service.applied", true);
            TraceStore.put("piiSanitizer.service.changedCount", changedCount);
            TraceStore.put("piiSanitizer.service.inputLength", inputLength);
            TraceStore.put("piiSanitizer.service.outputLength", outputLength);
        } catch (Exception ignore) {
            LOG.log(System.Logger.Level.DEBUG,
                    "PII sanitizer trace metrics skipped stage=trace_store_unavailable");
        }
    }

    private record ReplacementResult(String text, int count) {
    }
}
