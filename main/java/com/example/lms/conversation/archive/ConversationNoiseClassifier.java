package com.example.lms.conversation.archive;

import java.util.Locale;
import java.util.regex.Pattern;

public class ConversationNoiseClassifier {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>()]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_STYLE = Pattern.compile("(?is)<\\s*(script|style|body|html|iframe|noscript)\\b");
    private static final Pattern HTML_TAG = Pattern.compile("(?is)<[a-z!/][^>]{0,240}>");
    private static final Pattern BASE64 = Pattern.compile("(?is)(data:image/[^;]+;base64,|[A-Za-z0-9+/]{180,}={0,2})");

    // Count source-shaped declarations, not programming words in a conversation.
    private static final String CODE_START = "(?m)(?:^|[;{}])\\h*";
    private static final String CODE_NAME = "[\\p{L}_$][\\p{L}\\p{N}_$]*+";
    private static final Pattern[] CODE_SIGNALS = {
            Pattern.compile(CODE_START + "import\\h+(?:(?:static\\h+)?" + CODE_NAME
                    + "(?:\\." + CODE_NAME + ")*+(?:\\.\\*)?\\h*(?:;|$)"
                    + "|[\\p{L}\\p{N}_$*{},\\s]+?\\bfrom\\h+['\"][^'\"\\r\\n]+['\"]\\h*;?)"),
            Pattern.compile(CODE_START + "package\\h+" + CODE_NAME + "(?:\\." + CODE_NAME + ")*+\\h*(?:;|$)"),
            Pattern.compile(CODE_START + "public\\h+(?:(?:abstract|final)\\h+)?class\\h+" + CODE_NAME
                    + "(?:<[^<>;{}\\r\\n]+>)?(?:\\h+(?:extends|implements)\\h+[^;{}\\r\\n]+)?\\h*\\{"),
            Pattern.compile("(?m)(?:^|[;{}=])\\h*(?:export\\h+(?:default\\h+)?)?(?:async\\h+)?function\\h*"
                    + "(?:\\*\\h*)?(?:" + CODE_NAME + "\\h*)?\\([^;{}\\r\\n]*\\)\\h*\\{"),
            Pattern.compile(CODE_START + "(?:export\\h+)?(?:var|const)\\h+(?:" + CODE_NAME
                    + "|\\{[^;{}\\r\\n]+\\}|\\[[^;\\[\\]\\r\\n]+\\])\\h*(?:=|;)")
    };

    public record Decision(ConversationMessageKind kind, String reason, boolean ingestible, double confidence) {
    }

    public Decision classify(ConversationMessageRecord record) {
        return classifyText(record == null ? null : record.message());
    }

    public Decision classifyText(String text) {
        String raw = text == null ? "" : text;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return quarantine("blank", 1.0);
        }

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (SCRIPT_STYLE.matcher(trimmed).find()) {
            return quarantine("html_script_style", 0.99);
        }
        if (BASE64.matcher(trimmed).find()) {
            return quarantine("base64_blob", 0.98);
        }
        if (htmlSignal(trimmed)) {
            return quarantine("html_noise", 0.94);
        }
        if (repeatedJunk(trimmed)) {
            return quarantine("repeated_noise", 0.92);
        }
        if (codeOrImportDump(lower)) {
            return quarantine("code_or_internal_dump", 0.88);
        }
        if (looksLikeBotSummary(lower)) {
            return new Decision(ConversationMessageKind.BOT_SUMMARY, "bot_summary", true, 0.90);
        }
        if (URL.matcher(trimmed).find()) {
            return new Decision(ConversationMessageKind.LINK_ARTIFACT, "link", true, 0.90);
        }
        return new Decision(ConversationMessageKind.HUMAN_MESSAGE, "plain_text", true, 0.75);
    }

    private static Decision quarantine(String reason, double confidence) {
        return new Decision(ConversationMessageKind.QUARANTINED, reason, false, confidence);
    }

    private static boolean htmlSignal(String text) {
        int tags = 0;
        var matcher = HTML_TAG.matcher(text);
        while (matcher.find() && tags < 6) {
            tags++;
        }
        if (tags >= 3) {
            return true;
        }
        long angleCount = text.chars().filter(c -> c == '<' || c == '>').count();
        return text.length() > 240 && angleCount > 12 && ((double) angleCount / Math.max(1, text.length())) > 0.03;
    }

    private static boolean looksLikeBotSummary(String lower) {
        return lower.contains("short.oursophy.com")
                || lower.contains("bot summary")
                || lower.contains("summary:")
                || lower.contains("conversation summary");
    }

    private static boolean codeOrImportDump(String lower) {
        if (lower.length() < 400) {
            return false;
        }
        int hits = 0;
        for (Pattern signal : CODE_SIGNALS) {
            if (signal.matcher(lower).find()) hits++;
        }
        return hits >= 3;
    }

    private static boolean repeatedJunk(String text) {
        String compact = text.replaceAll("\\s+", "");
        if (compact.length() >= 8) {
            long distinct = compact.chars().distinct().limit(4).count();
            if (distinct <= 2) {
                return true;
            }
        }
        String lower = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (lower.length() < 12) {
            return false;
        }
        String[] parts = lower.split(" ");
        if (parts.length >= 4) {
            int same = 0;
            for (String part : parts) {
                if (!part.isBlank() && part.equals(parts[0])) {
                    same++;
                }
            }
            return same == parts.length;
        }
        return false;
    }
}
