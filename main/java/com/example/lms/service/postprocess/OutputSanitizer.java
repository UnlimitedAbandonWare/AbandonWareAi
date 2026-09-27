package com.example.lms.service.postprocess;

import com.example.lms.trace.SafeRedactor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Deterministic answer cleanup with redacted fail-soft diagnostics.
 */
public final class OutputSanitizer {

    private static final System.Logger LOG = System.getLogger(OutputSanitizer.class.getName());
    private static final String TRACE_MARKER = "<!-- NOVA_TRACE_INJECTED -->";
    private static final String BLANK_FALLBACK =
            "The answer body was blank. Please retry the request.";
    private static final String DIAGNOSTICS_REMOVED_FALLBACK =
            "The answer body was removed while cleaning diagnostics. Please retry the request.";
    private static final String[] CUT_MARKERS = new String[] {
            TRACE_MARKER,
            "TRACE_JSON",
            "TRACE_HTML",
            "SearchTrace{",
            "SearchTrace(",
            "SearchTrace:",
            "StageSnapshot",
            "SoakProbe",
            "nightmare:state"
    };

    public Result sanitize(String content) {
        if (content == null || content.isBlank()) {
            return new Result(BLANK_FALLBACK, true, "blank_content", null, 0L, null);
        }

        Cut cut = cutAtFirstMarker(content);
        if (!cut.applied()) {
            return new Result(content, false, "none", null, 0L, null);
        }

        String cleaned = cut.cleaned() == null ? "" : cut.cleaned().trim();
        String reasonCode = "diagnostics_removed";
        if (cleaned.isBlank()) {
            cleaned = DIAGNOSTICS_REMOVED_FALLBACK;
            reasonCode = "diagnostics_removed_empty";
        }
        long removedChars = Math.max(0L, content.length() - cleaned.length());
        return new Result(
                cleaned,
                true,
                reasonCode,
                cut.marker(),
                removedChars,
                cut.removedHash());
    }

    private static Cut cutAtFirstMarker(String content) {
        int bestIndex = -1;
        String bestMarker = null;
        for (String marker : CUT_MARKERS) {
            int index = findMarkerIndex(content, marker);
            if (index >= 0 && (bestIndex < 0 || index < bestIndex)) {
                bestIndex = index;
                bestMarker = marker;
            }
        }
        if (bestIndex < 0 || bestMarker == null) {
            return Cut.noop();
        }

        int afterMarker = Math.min(content.length(), bestIndex + bestMarker.length());
        String prefix = content.substring(0, bestIndex);
        boolean headMarker = prefix.isBlank();
        String cleaned = headMarker ? "" : prefix;
        String removed = headMarker ? content : content.substring(bestIndex);
        String removedHash = sha1Hex(SafeRedactor.redact(clip(removed, 2_048)));
        String markerLabel = headMarker ? bestMarker + ":head" : bestMarker;
        return new Cut(true, markerLabel, cleaned, removedHash);
    }

    private static int findMarkerIndex(String content, String marker) {
        if (TRACE_MARKER.equals(marker)) {
            return content.indexOf(marker);
        }
        int from = 0;
        while (from < content.length()) {
            int index = content.indexOf(marker, from);
            if (index < 0) {
                return -1;
            }
            if (isLineStartMarker(content, index)
                    && hasDiagnosticPayloadShape(content, index + marker.length())) {
                return index;
            }
            from = index + marker.length();
        }
        return -1;
    }

    private static boolean isLineStartMarker(String content, int index) {
        int cursor = index - 1;
        while (cursor >= 0) {
            char value = content.charAt(cursor);
            if (value == '\n' || value == '\r') {
                return true;
            }
            if (!Character.isWhitespace(value)) {
                return false;
            }
            cursor--;
        }
        return true;
    }

    private static boolean hasDiagnosticPayloadShape(String content, int afterMarker) {
        int cursor = afterMarker;
        while (cursor < content.length()
                && Character.isWhitespace(content.charAt(cursor))
                && content.charAt(cursor) != '\n'
                && content.charAt(cursor) != '\r') {
            cursor++;
        }
        if (cursor >= content.length()
                || content.charAt(cursor) == '\n'
                || content.charAt(cursor) == '\r') {
            return true;
        }
        char value = content.charAt(cursor);
        return value == '{'
                || value == '['
                || value == '('
                || value == ':'
                || value == '='
                || value == '"'
                || value == '\'';
    }

    private static String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static String sha1Hex(String value) {
        if (value == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Output sanitizer hash unavailable valueLength={0} errorType={1}",
                    value.length(), failure.getClass().getSimpleName());
            return null;
        }
    }

    public record Result(
            String content,
            boolean changed,
            String reasonCode,
            String marker,
            long removedChars,
            String removedHash) {
    }

    private record Cut(boolean applied, String marker, String cleaned, String removedHash) {
        private static Cut noop() {
            return new Cut(false, null, null, null);
        }
    }
}
