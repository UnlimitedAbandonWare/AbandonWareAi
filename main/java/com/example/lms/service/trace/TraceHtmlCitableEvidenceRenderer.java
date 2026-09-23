package com.example.lms.service.trace;

import com.example.lms.trace.SafeRedactor;

import java.util.Map;

final class TraceHtmlCitableEvidenceRenderer {

    private TraceHtmlCitableEvidenceRenderer() {
    }

    static String render(Map<String, Object> extraMeta) {
        Object raw = extraMeta == null ? null : extraMeta.get("rag.evidence.public");
        if (!(raw instanceof Iterable<?> items)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-section'>");
        sb.append(TraceHtmlLayout.renderPanelHeader("C) Citable Evidence", "Evidence passed through EvidenceGate/CitationGate"));
        sb.append("<ol class='trace-docs'>");
        int count = 0;
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            count++;
            if (count > 20) {
                sb.append("<li>...(truncated)...</li>");
                break;
            }
            String marker = safeValue(map.get("marker"));
            String kind = safeValue(map.get("kind"));
            String title = safeValue(map.get("title"));
            String source = safeValue(map.get("source"));
            String filePath = safeValue(map.get("filePath"));
            String lineStart = safeValue(map.get("lineStart"));
            String lineEnd = safeValue(map.get("lineEnd"));
            String confidence = safeValue(map.get("confidence"));
            sb.append("<li><div class='trace-doc-line'><span class='trace-tag'>")
                    .append(escape(marker)).append("</span>")
                    .append("<span class='trace-title'>")
                    .append(escape(firstNonBlank(title, filePath, source, kind, "evidence")))
                    .append("</span></div>");
            sb.append("<div class='trace-doc-snippet trace-mono'>");
            if (source != null && !source.isBlank() && !"null".equals(source)) {
                sb.append("source=").append(escape(source)).append(" ");
            }
            if (filePath != null && !filePath.isBlank() && !"null".equals(filePath)) {
                sb.append("filePath=").append(escape(filePath)).append(" ");
            }
            if (lineStart != null && !lineStart.isBlank() && !"null".equals(lineStart)) {
                sb.append("lines=").append(escape(lineStart));
                if (lineEnd != null && !lineEnd.isBlank() && !"null".equals(lineEnd) && !lineEnd.equals(lineStart)) {
                    sb.append("-").append(escape(lineEnd));
                }
                sb.append(" ");
            }
            if (confidence != null && !confidence.isBlank() && !"null".equals(confidence)) {
                sb.append("confidence=").append(escape(confidence));
            }
            sb.append("</div></li>");
        }
        if (count == 0) {
            sb.append("<li><span class='text-muted small'>(No promoted evidence)</span></li>");
        }
        sb.append("</ol></div>");
        return sb.toString();
    }

    private static String safeValue(Object v) {
        if (v == null) {
            return "null";
        }
        String s = String.valueOf(v);
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return SafeRedactor.safeMessage(s, 800);
    }

    private static String firstNonBlank(String... ss) {
        if (ss == null) {
            return "";
        }
        for (String s : ss) {
            if (s != null && !s.isBlank() && !"null".equals(s)) {
                return s;
            }
        }
        return "";
    }

    private static String escape(String s) {
        return (s == null ? "" : s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
