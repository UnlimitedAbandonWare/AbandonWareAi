package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class TraceHtmlOrchestrationModeCalloutRenderer {

    private TraceHtmlOrchestrationModeCalloutRenderer() {
    }

    static String render(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty()) {
            return "";
        }

        String mode = firstNonBlank(getString(extraMeta, "orch.mode"), getString(extraMeta, "orch.modeLabel"));
        if (!isNonBlank(mode)) {
            mode = "NORMAL";
        }

        boolean strike = truthy(extraMeta.get("orch.strike"));
        boolean bypass = truthy(extraMeta.get("orch.bypass"));
        boolean compression = truthy(extraMeta.get("orch.compression"));
        boolean auxDown = truthy(extraMeta.get("orch.auxLlmDown"));
        boolean webRateLimited = truthy(extraMeta.get("orch.webRateLimited"));

        String reason = firstNonBlank(getString(extraMeta, "orch.reason"), getString(extraMeta, "bypassReason"));
        if (!isNonBlank(reason)) {
            Object reasonsObj = extraMeta.get("orch.reasons");
            if (reasonsObj instanceof java.util.Collection<?> col) {
                List<String> parts = new ArrayList<>();
                for (Object o : col) {
                    if (o == null) {
                        continue;
                    }
                    String s = String.valueOf(o).trim();
                    if (!s.isBlank()) {
                        parts.add(s);
                    }
                }
                if (!parts.isEmpty()) {
                    reason = String.join(", ", parts);
                }
            }
        }
        if (!isNonBlank(reason)) {
            reason = inferredRelaxedModeReason(extraMeta);
        }

        String accent = "#4a90e2";
        if (strike) {
            accent = "#d33";
        } else if (bypass) {
            accent = "#f90";
        } else if (compression) {
            accent = "#c90";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(
                "<div class='trace-orch-callout' style='margin:10px 0;padding:10px;border:1px solid #ddd;border-left:6px solid ")
                .append(accent)
                .append(";background:#fafafa;'>");
        sb.append("<div><b>Mode</b>: ").append(escape(mode)).append("</div>");
        sb.append("<div style='margin-top:4px;font-size:12px;opacity:0.9;'>")
                .append("STRIKE=").append(strike)
                .append(", COMPRESSION=").append(compression)
                .append(", BYPASS=").append(bypass)
                .append(", webRateLimited=").append(webRateLimited)
                .append(", auxDown=").append(auxDown)
                .append("</div>");

        if (isNonBlank(reason)) {
            sb.append("<div style='margin-top:6px;'><b>Why</b>: ")
                    .append(escape(SafeRedactor.traceLabelOrFallback(reason, "reason")))
                    .append("</div>");
        }

        Integer thumbHits = toInt(extraMeta.get("uaw.thumb.recall.hits"));
        if (thumbHits != null && thumbHits > 0) {
            String entities = getString(extraMeta, "uaw.thumb.recall.entities");
            sb.append("<div style='margin-top:6px;'><b>UAW Thumbnail</b>: recall hits=")
                    .append(thumbHits);
            if (isNonBlank(entities)) {
                sb.append(" (").append(escape(SafeRedactor.safeMessage(entities, 240))).append(")");
            }
            sb.append("</div>");
        }

        sb.append("</div>");
        return sb.toString();
    }

    private static boolean truthy(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v == null) {
            return false;
        }
        String s = String.valueOf(v).trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return n.intValue();
            }
            TraceStore.put("traceHtml.orchestrationMode.suppressed.toInt", true);
            TraceStore.put("traceHtml.orchestrationMode.suppressed.toInt.errorType", "invalid_number");
            return null;
        }
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            TraceStore.put("traceHtml.orchestrationMode.suppressed.toInt", true);
            TraceStore.put("traceHtml.orchestrationMode.suppressed.toInt.errorType", "invalid_number");
            return null;
        }
    }

    private static String inferredRelaxedModeReason(Map<String, Object> extraMeta) {
        List<String> filters = new ArrayList<>();
        if (truthy(extraMeta.get("vector.scopeFilter.relaxed"))) {
            filters.add("scope_filter");
        }
        if (truthy(extraMeta.get("vector.docTypeFilter.relaxed"))) {
            filters.add("doc_type_filter");
        }
        if (!filters.isEmpty()) {
            return "vector_filter_relaxed." + String.join(".", filters);
        }

        String marker = firstNonBlank(
                getString(extraMeta, "orch.mode"),
                getString(extraMeta, "orch.modeLabel"),
                getString(extraMeta, "plan.id"));
        if (isRelaxedModeMarker(marker)) {
            return "relaxed_mode_policy";
        }
        return "";
    }

    private static boolean isRelaxedModeMarker(String marker) {
        if (!isNonBlank(marker)) {
            return false;
        }
        String normalized = marker.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("rulebreak")
                || normalized.contains("zero_break")
                || normalized.contains("zero-break")
                || normalized.contains("fullscale");
    }

    private static String getString(Map<String, Object> meta, String key) {
        Object v = meta == null ? null : meta.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static boolean isNonBlank(String s) {
        return s != null && !s.isBlank() && !"null".equalsIgnoreCase(s.trim());
    }

    private static String firstNonBlank(String... ss) {
        if (ss == null) {
            return "";
        }
        for (String s : ss) {
            if (isNonBlank(s)) {
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
