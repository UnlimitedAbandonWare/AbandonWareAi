package com.example.lms.service.guard;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContextContaminationSignatures {

    private static final Pattern CHAT_EXPORT_HEADER = Pattern.compile(
            "(?m)^\\s*\\[[^\\]\\r\\n]{1,80}/[^\\]\\r\\n]{1,80}\\]\\s+\\[(?:AM|PM|오전|오후)\\s*\\d{1,2}:\\d{2}\\]");
    private static final Pattern MEDIA_PLACEHOLDER = Pattern.compile(
            "(?iu)\\b(?:photo|image|video|media|attachment)\\b\\s*\\d*|사진\\s*\\d*|동영상\\s*\\d*");
    private static final Pattern DOMAIN_DRIFT_MARKER = Pattern.compile(
            "(?iu)\\b(?:aquarium|fish|shrimp|tank|guppy|medaka)\\b|수족관|어항|새우|물고기|구피|메다카|체리새우");
    private static final Pattern COMMERCE_MARKER = Pattern.compile(
            "(?iu)\\b(?:store recommendation|recommended by|purchase|price|marketplace)\\b|추천\\s*:");

    private ContextContaminationSignatures() {
    }

    public static PrivateChatTranscriptSignals inspectPrivateChatTranscript(String text) {
        String safe = text == null ? "" : text;
        int lines = countLines(safe);
        int headers = countMatches(CHAT_EXPORT_HEADER, safe, 200);
        int mediaLines = countLinesMatching(safe, MEDIA_PLACEHOLDER);
        int domainLines = countLinesMatching(safe, DOMAIN_DRIFT_MARKER);
        int commerceLines = countLinesMatching(safe, COMMERCE_MARKER);
        int markers = mediaLines + domainLines + commerceLines;
        double ratio = lines <= 0 ? 0.0d : (double) headers / (double) lines;
        boolean match = headers >= 3 || (headers >= 1 && ratio >= 0.35d && markers > 0);
        return new PrivateChatTranscriptSignals(match, lines, headers, domainLines, mediaLines, commerceLines, ratio);
    }

    private static int countMatches(Pattern pattern, String text, int cap) {
        if (pattern == null || text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            count++;
            if (count >= cap) {
                break;
            }
        }
        return count;
    }

    private static int countLinesMatching(String text, Pattern pattern) {
        if (text == null || text.isBlank() || pattern == null) {
            return 0;
        }
        int count = 0;
        for (String line : text.split("\\r?\\n")) {
            if (line != null && pattern.matcher(line.toLowerCase(Locale.ROOT)).find()) {
                count++;
            }
        }
        return count;
    }

    private static int countLines(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : text.split("\\r?\\n")) {
            if (line != null && !line.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public record PrivateChatTranscriptSignals(
            boolean match,
            int lines,
            int headers,
            int domainMarkers,
            int mediaMarkers,
            int commerceMarkers,
            double headerRatio) {
    }
}
