// src/main/java/com/example/lms/debug/PromptMasker.java
package com.example.lms.debug;

import java.util.regex.Pattern;



/**
 * Utility for masking sensitive tokens and API keys when logging prompts
 * and responses.  Certain token formats, such as OpenAI API keys
 * (sk-/* ... *&#47;) or Google API keys (AIza/* ... *&#47;.), should never be logged in
 * plain text.  This class replaces such substrings with asterisks while
 * preserving overall length to aid debugging without exposing secrets.
 */
public final class PromptMasker {
    private PromptMasker() {}

    // Precompile patterns for known secret prefixes
    private static final Pattern OPENAI_KEY = Pattern.compile("sk-[A-Za-z0-9_-]{10,}");
    private static final Pattern GOOGLE_KEY = Pattern.compile("AIza[0-9A-Za-z_-]{10,}");
    private static final Pattern GROQ_KEY = Pattern.compile("gsk_[A-Za-z0-9_-]{10,}");
    private static final Pattern PINECONE_KEY = Pattern.compile("pcsk_[A-Za-z0-9_-]{10,}");
    private static final Pattern TAVILY_KEY = Pattern.compile("tvly-[A-Za-z0-9_-]{10,}");
    private static final Pattern SUPABASE_KEY = Pattern.compile("sb_(?:secret|publishable)_[A-Za-z0-9_-]{10,}");
    private static final Pattern KAKAO_AUTH =
            Pattern.compile("(?i)\\bKakaoAK\\s+[A-Za-z0-9._~+/\\-]+=*");
    private static final String SECRET_FIELD_NAME =
            "(?:groq|pinecone|tavily|brave|kakao|upstash|naver|serpapi|serp_api)[A-Za-z0-9_.-]*"
                    + "(?:api[-_]?key|key|token|secret|client[-_]?id|client[-_]?secret)"
                    + "|(?:api[-_]?key|client[-_]?secret|client[-_]?id|subscription[-_]?token|owner[-_]?token"
                    + "|access[-_]?token|refresh[-_]?token|auth[-_]?token|session[-_]?token"
                    + "|token|secret|password|x[-_]?naver[-_]?client[-_]?(?:id|secret)|x[-_]?subscription[-_]?token)";
    private static final Pattern VENDOR_KEY_VALUE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(" + SECRET_FIELD_NAME + ")"
                    + "\\s*([:=])\\s*([^\\s,;\"']{4,})");
    private static final Pattern QUOTED_KEY_VALUE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])([\"']?)(" + SECRET_FIELD_NAME + ")\\1"
                    + "(\\s*[:=]\\s*)([\"'])((?:\\\\.|(?!\\4)[^\\\\])*+)\\4");
    // Accepts "Bearer <token>", case-insensitive; allows JWT/base64url charset.
    // Put '-' at the end of the class or escape as \\- to avoid illegal escape.
    private static final Pattern BEARER =
            Pattern.compile("(?i)\\bBearer\\s+[A-Za-z0-9._~+/\\-]+=*");

    /**
     * Mask any secrets contained in the input string.  Known secret
     * prefixes are replaced with a masked string of the same length so
     * that logs remain roughly aligned while protecting the sensitive
     * portion.  If no secrets are found the original string is returned.
     *
     * @param input text to mask
     * @return masked text
     */
    public static String mask(String input) {
        if (input == null || input.isEmpty()) return input;
        String masked = OPENAI_KEY.matcher(input).replaceAll(m -> stars(m.group().length()));
        masked = GOOGLE_KEY.matcher(masked).replaceAll(m -> stars(m.group().length()));
        masked = GROQ_KEY.matcher(masked).replaceAll(m -> stars(m.group().length()));
        masked = PINECONE_KEY.matcher(masked).replaceAll(m -> stars(m.group().length()));
        masked = TAVILY_KEY.matcher(masked).replaceAll(m -> stars(m.group().length()));
        masked = SUPABASE_KEY.matcher(masked).replaceAll(m -> stars(m.group().length()));
        masked = KAKAO_AUTH.matcher(masked).replaceAll(m -> {
            String g = m.group();
            return g.substring(0, Math.min(8, g.length())) + stars(Math.max(0, g.length() - 8));
        });
        masked = QUOTED_KEY_VALUE.matcher(masked).replaceAll(m ->
                m.group(1) + m.group(2) + m.group(1) + m.group(3) + m.group(4)
                        + stars(m.group(5).length()) + m.group(4));
        masked = VENDOR_KEY_VALUE.matcher(masked).replaceAll(m ->
                m.group(1) + m.group(2) + stars(m.group(3).length()));
        masked = BEARER.matcher(masked).replaceAll(m -> {
            String g = m.group();
            return g.substring(0, Math.min(6, g.length())) + stars(Math.max(0, g.length() - 6));
        });
        return masked;
    }

    private static String stars(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append('*');
        return sb.toString();
    }
}
