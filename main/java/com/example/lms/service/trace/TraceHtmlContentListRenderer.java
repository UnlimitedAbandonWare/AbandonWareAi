package com.example.lms.service.trace;

import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import dev.langchain4j.rag.content.Content;

final class TraceHtmlContentListRenderer {

    private static final Pattern URL_IN_TEXT = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    private TraceHtmlContentListRenderer() {
    }

    static String render(List<Content> list, String tagPrefix, int max) {
        if (list == null || list.isEmpty()) {
            return "<div class=\"text-muted small\">(empty)</div>";
        }
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<ol class=\"trace-docs\">");
        int idx = 1;
        for (Content c : list) {
            if (c == null) {
                continue;
            }
            if (idx > Math.max(1, max)) {
                break;
            }

            String url = extractUrl(c);
            String title = extractTitle(c);
            String snippet = extractSnippet(c);
            String host = hostOf(url);

            sb.append("<li>");
            sb.append("<div class=\"trace-doc-line\">")
                    .append("<span class=\"trace-tag\">")
                    .append(escape(tagPrefix)).append(idx)
                    .append("</span>");

            sb.append("<span class=\"trace-title\">")
                    .append(escape(safeDiagnostic("title", title)))
                    .append("</span>");
            if (host != null && !host.isBlank()) {
                sb.append("<span class=\"trace-host text-muted\">")
                        .append(escape(host))
                        .append("</span>");
            }
            String urlHash = SafeRedactor.hash12(url);
            if (urlHash != null && !urlHash.isBlank()) {
                sb.append("<span class=\"trace-host text-muted\">urlHash=")
                        .append(escape(urlHash))
                        .append("</span>");
            }
            sb.append("</div>");

            if (snippet != null && !snippet.isBlank()) {
                sb.append("<div class=\"trace-doc-snippet\">")
                        .append(escape(safeDiagnostic("snippet", snippet)))
                        .append("</div>");
            }
            sb.append("</li>");
            idx++;
        }
        sb.append("</ol>");
        return sb.toString();
    }

    private static String extractTitle(Content c) {
        if (c == null) {
            return "(No title)";
        }
        try {
            var seg = c.textSegment();
            if (seg != null) {
                try {
                    var md = seg.metadata();
                    if (md != null) {
                        String t = md.getString("title");
                        if (t != null && !t.isBlank()) {
                            return truncate(t.strip(), 80);
                        }
                    }
                } catch (Exception ignore) {
                    traceSuppressed("title.metadata", ignore);
                }
                String text = seg.text();
                if (text != null && !text.isBlank()) {
                    String line1 = text.strip().split("\\r?\\n", 2)[0].strip();
                    if (line1.startsWith("[") && line1.contains("]")) {
                        String inside = line1.substring(1, line1.indexOf(']'));
                        String[] parts = inside.split("\\s*\\|\\s*");
                        if (parts.length > 0 && !parts[0].isBlank()) {
                            return truncate(parts[0].strip(), 80);
                        }
                    }
                    if (!line1.isBlank()) {
                        return truncate(line1, 80);
                    }
                }
            }
        } catch (Exception ignore) {
            traceSuppressed("title.extract", ignore);
        }
        return "(No title)";
    }

    private static String extractSnippet(Content c) {
        if (c == null) {
            return "";
        }
        try {
            var seg = c.textSegment();
            if (seg != null && seg.text() != null) {
                String t = seg.text().strip();
                if (t.isBlank()) {
                    return "";
                }
                String[] lines = t.split("\\r?\\n", 2);
                if (lines.length == 2) {
                    String first = lines[0].strip();
                    if (first.startsWith("[") && first.contains("]")) {
                        t = lines[1].strip();
                    }
                }
                return truncate(t, 200);
            }
        } catch (Exception ignore) {
            traceSuppressed("snippet.extract", ignore);
        }
        return "";
    }

    private static String extractUrl(Content c) {
        if (c == null) {
            return null;
        }
        try {
            var seg = c.textSegment();
            if (seg == null) {
                return null;
            }
            try {
                var md = seg.metadata();
                if (md != null) {
                    String url = md.getString("url");
                    if (url == null || url.isBlank()) {
                        url = md.getString("source");
                    }
                    if (url != null && !url.isBlank()) {
                        return url.strip();
                    }
                }
            } catch (Exception ignore) {
                traceSuppressed("url.metadata", ignore);
            }
            String text = seg.text();
            if (text != null && !text.isBlank()) {
                Matcher m = URL_IN_TEXT.matcher(text);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (Exception ignore) {
            traceSuppressed("url.extract", ignore);
        }
        return null;
    }

    private static String hostOf(String url) {
        if (!isHttpUrl(url)) {
            return null;
        }
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            traceSuppressed("host", e);
            return null;
        }
    }

    private static boolean isHttpUrl(String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase();
        return u.startsWith("http://") || u.startsWith("https://");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? (s.substring(0, max) + "...") : s;
    }

    private static String safeDiagnostic(String key, Object v) {
        Object redacted = SafeRedactor.diagnosticValue(key, v, 800);
        return safeValue(redacted);
    }

    private static String safeValue(Object v) {
        if (v == null) {
            return "null";
        }
        String s = String.valueOf(v);
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() > 800 ? s.substring(0, 800) + "..." : s;
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = errorType(safeStage, failure);
        TraceStore.put("traceHtml.contentList.suppressed.stage", safeStage);
        TraceStore.put("traceHtml.contentList.suppressed.errorType", errorType);
        TraceStore.inc("traceHtml.contentList.suppressed.count");
        TraceStore.put("traceHtml.contentList.suppressed." + safeStage, true);
        TraceStore.put("traceHtml.contentList.suppressed." + safeStage + ".errorType", errorType);
        TraceStore.inc("traceHtml.contentList.suppressed." + safeStage + ".count");
    }

    private static String errorType(String stage, Throwable failure) {
        if ("host".equals(stage)) {
            return "invalid_uri";
        }
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }

    private static String escape(String s) {
        return (s == null ? "" : s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
