package com.example.lms.service.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.example.lms.trace.SafeRedactor;

final class TraceHtmlWebSelectedTermsRenderer {

    private TraceHtmlWebSelectedTermsRenderer() {
    }

    static void append(StringBuilder sb, Map<String, Object> meta, Set<String> shown) {
        Object effectiveQueryObj = meta.get("web.effectiveQuery");
        String effectiveQuery = effectiveQueryObj == null ? null : String.valueOf(effectiveQueryObj);
        Object selectedObj = meta.get("web.selectedTerms");
        Object summaryObj = meta.get("web.selectedTerms.summary");
        Object appliedObj = meta.get("web.selectedTerms.applied");

        boolean any = isNonBlank(effectiveQuery) || selectedObj != null || summaryObj != null || appliedObj != null;
        if (!any) {
            return;
        }

        sb.append("<tr class='trace-kv-group'><th colspan='2'>Web Debug</th></tr>");

        if (isNonBlank(effectiveQuery)) {
            sb.append("<tr><th>web.effectiveQuery</th><td><code>")
                    .append(escape(safeDiagnostic("web.effectiveQuery", effectiveQueryObj)))
                    .append("</code></td></tr>");
            shown.add("web.effectiveQuery");
        }

        if (summaryObj != null && isNonBlank(String.valueOf(summaryObj))) {
            sb.append("<tr><th>web.selectedTerms.summary</th><td><code>")
                    .append(escape(safePreview(summaryObj, 800)))
                    .append("</code></td></tr>");
            shown.add("web.selectedTerms.summary");
        }

        if (!(selectedObj instanceof Map)) {
            appendFallbackRows(sb, selectedObj, appliedObj, shown);
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> selected = (Map<String, Object>) selectedObj;

        Object domainProfile = selected.get("domainProfile");
        Object countsObj = selected.get("counts");
        Object samplesObj = selected.get("samples");

        Map<String, Object> counts = (countsObj instanceof Map)
                ? (Map<String, Object>) countsObj
                : Map.of();
        Map<String, Object> samples = (samplesObj instanceof Map)
                ? (Map<String, Object>) samplesObj
                : Map.of();

        sb.append("<tr><th>web.selectedTerms</th><td>");
        appendEffectiveQueryHash(sb, meta, shown);
        appendSummary(sb, meta, shown);

        sb.append("<details class='trace-selected-terms'>");

        String dom = safePreview(domainProfile, 160);
        String countsLine = counts.isEmpty() ? "" : String.valueOf(counts);
        sb.append("<summary><code>")
                .append(escape(firstNonBlank(dom, "(no domainProfile)")))
                .append(escape(isNonBlank(countsLine) ? (" " + countsLine) : ""))
                .append("</code></summary>");

        if (appliedObj instanceof Map<?, ?> appliedMap && !appliedMap.isEmpty()) {
            sb.append("<div class='trace-selected-applied'>");
            sb.append("<div class='trace-selected-label'>Applied tokens (sample)</div>");
            sb.append(renderTokenCategoryDetails("negative", appliedMap.get("negative"), counts.get("negative")));
            sb.append(renderTokenCategoryDetails("aliases", appliedMap.get("aliases"), counts.get("aliases")));
            sb.append(renderTokenCategoryDetails("domains", appliedMap.get("domains"), counts.get("domains")));
            sb.append("</div>");
            shown.add("web.selectedTerms.applied");
        }

        sb.append("<div class='trace-selected-all'>");
        sb.append("<div class='trace-selected-label'>All categories (samples)</div>");
        for (String key : List.of("exact", "must", "should", "negative", "aliases", "domains")) {
            sb.append(renderTokenCategoryDetails(key, samples.get(key), counts.get(key)));
        }
        sb.append("</div>");

        appendRules(sb, meta.get("web.selectedTerms.rules"), shown);

        sb.append("</details>");
        sb.append("</td></tr>");
        shown.add("web.selectedTerms");
    }

    private static void appendFallbackRows(
            StringBuilder sb,
            Object selectedObj,
            Object appliedObj,
            Set<String> shown) {
        if (selectedObj != null) {
            sb.append("<tr><th>web.selectedTerms</th><td><code>")
                    .append(escape(safePreview(selectedObj, 800)))
                    .append("</code></td></tr>");
            shown.add("web.selectedTerms");
        }
        if (appliedObj != null) {
            sb.append("<tr><th>web.selectedTerms.applied</th><td><code>")
                    .append(escape(safePreview(appliedObj, 800)))
                    .append("</code></td></tr>");
            shown.add("web.selectedTerms.applied");
        }
    }

    private static void appendEffectiveQueryHash(StringBuilder sb, Map<String, Object> meta, Set<String> shown) {
        Object effectiveObj = meta.get("web.effectiveQuery");
        if (effectiveObj == null) {
            return;
        }
        String text = String.valueOf(effectiveObj);
        if (text.isBlank()) {
            return;
        }
        Object existingHash = meta.get("web.effectiveQuery.hash12");
        Object existingLength = meta.get("web.effectiveQuery.len");
        sb.append("<div style='margin:0 0 6px 0;'><code>effectiveQueryHash12: ")
                .append(escape(existingHash == null ? SafeRedactor.hash12(text) : String.valueOf(existingHash)))
                .append(" len=")
                .append(escape(existingLength == null ? String.valueOf(text.length()) : String.valueOf(existingLength)))
                .append("</code></div>");
        shown.add("web.effectiveQuery");
    }

    private static void appendSummary(StringBuilder sb, Map<String, Object> meta, Set<String> shown) {
        Object summaryObj = meta.get("web.selectedTerms.summary");
        if (summaryObj == null) {
            return;
        }
        String text = String.valueOf(summaryObj);
        if (text.isBlank()) {
            return;
        }
        sb.append("<div style='margin:0 0 6px 0;'><code>summary: ")
                .append(escape(safePreview(text, 800)))
                .append("</code></div>");
        shown.add("web.selectedTerms.summary");
    }

    private static void appendRules(StringBuilder sb, Object rulesObj, Set<String> shown) {
        List<String> rules = new ArrayList<>();
        if (rulesObj instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                String text = String.valueOf(item).trim();
                if (text.isEmpty()) {
                    continue;
                }
                rules.add(safePreview(text, 220));
                if (rules.size() >= 16) {
                    break;
                }
            }
        } else if (rulesObj != null) {
            String text = String.valueOf(rulesObj).trim();
            if (!text.isEmpty()) {
                rules.add(safePreview(text, 220));
            }
        }

        if (rules.isEmpty()) {
            return;
        }
        sb.append("<details class='trace-fold trace-selected-rules'>");
        sb.append("<summary><code>rules / evidence (").append(rules.size()).append(")</code></summary>");
        sb.append("<ul style='margin:6px 0 0 18px; padding:0;'>");
        for (String rule : rules) {
            sb.append("<li><code>").append(escape(rule)).append("</code></li>");
        }
        sb.append("</ul></details>");
        shown.add("web.selectedTerms.rules");
    }

    private static String renderTokenCategoryDetails(String name, Object sampleObj, Object countObj) {
        List<String> tokens = new ArrayList<>();
        if (sampleObj instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                tokens.add(safePreview(item, 160));
                if (tokens.size() >= 12) {
                    break;
                }
            }
        } else if (sampleObj != null) {
            tokens.add(safePreview(sampleObj, 160));
        }
        String count = countObj == null ? "" : String.valueOf(countObj);
        String header = name + (isNonBlank(count) ? (" (n=" + count + ")") : "");

        StringBuilder sb = new StringBuilder();
        sb.append("<details class='trace-selected-cat'>");
        sb.append("<summary><code>").append(escape(header)).append("</code></summary>");
        if (tokens.isEmpty()) {
            sb.append("<div class='trace-selected-empty'><code>(empty)</code></div>");
        } else {
            sb.append("<div class='trace-selected-tokens'><code>");
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) {
                    sb.append("<br/>");
                }
                sb.append(escape(tokens.get(i)));
            }
            sb.append("</code></div>");
        }
        sb.append("</details>");
        return sb.toString();
    }

    private static String safeValue(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value).replace("\n", " ").trim();
        return text.length() > 800 ? text.substring(0, 800) + "..." : text;
    }

    private static String safePreview(Object value, int maxLen) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).replace("\n", " ").trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return "";
        }
        return SafeRedactor.safeMessage(text, maxLen);
    }

    private static String safeDiagnostic(String key, Object value) {
        Object safe = SafeRedactor.diagnosticValue(key, value, 800);
        return safeValue(safe);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (isNonBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank() && !"null".equals(s);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
