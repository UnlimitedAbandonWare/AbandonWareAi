package com.example.lms.conversation.archive;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConversationExportParser {

    private static final Pattern MOBILE_LINE = Pattern.compile(
            "^\\s*(\\d{4}[.\\-/]\\s*\\d{1,2}[.\\-/]\\s*\\d{1,2}[^,]{0,40}),\\s*([^:\\uFF1A]{1,80})\\s*[:\\uFF1A]\\s*(.*)$");
    private static final Pattern BRACKET_LINE = Pattern.compile(
            "^\\s*\\[([^\\]]{1,80})]\\s*\\[([^\\]]{1,80})]\\s*(.*)$");
    private static final Pattern SIMPLE_LINE = Pattern.compile(
            "^\\s*([^:\\uFF1A\\[\\]<>{}]{1,80})\\s*[:\\uFF1A]\\s*(\\S.*)$");

    public List<ConversationMessageRecord> parse(String sourcePath, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ConversationMessageRecord> out = new ArrayList<>();
        ConversationMessageRecord current = null;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            ConversationMessageRecord parsed = parseLine(sourcePath, i + 1, line);
            if (parsed != null) {
                if (current != null) {
                    out.add(current);
                }
                current = parsed;
            } else if (current != null) {
                current = current.withMessage(limit(current.message() + "\n" + line.trim(), 12000));
            } else {
                current = new ConversationMessageRecord(sourcePath, i + 1, "", "", limit(line.trim(), 12000));
            }
        }
        if (current != null) {
            out.add(current);
        }
        return out;
    }

    private static ConversationMessageRecord parseLine(String sourcePath, int lineNo, String line) {
        Matcher mobile = MOBILE_LINE.matcher(line);
        if (mobile.matches()) {
            return new ConversationMessageRecord(sourcePath, lineNo,
                    clean(mobile.group(1)), clean(mobile.group(2)), limit(clean(mobile.group(3)), 12000));
        }

        Matcher bracket = BRACKET_LINE.matcher(line);
        if (bracket.matches()) {
            return new ConversationMessageRecord(sourcePath, lineNo,
                    clean(bracket.group(2)), clean(bracket.group(1)), limit(clean(bracket.group(3)), 12000));
        }

        Matcher simple = SIMPLE_LINE.matcher(line);
        if (simple.matches() && !simple.group(1).toLowerCase(Locale.ROOT).startsWith("http")) {
            return new ConversationMessageRecord(sourcePath, lineNo,
                    "", clean(simple.group(1)), limit(clean(simple.group(2)), 12000));
        }

        return null;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        int end = Math.max(0, max);
        if (end > 0 && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }
}
