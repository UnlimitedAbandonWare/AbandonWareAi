package com.example.lms.service.trace;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.util.Map;

final class TraceHtmlRelationThumbnailCallouts {

    private TraceHtmlRelationThumbnailCallouts() {
    }

    static String renderBudget(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty()) {
            return "";
        }
        String prefix = "uaw.thumbnail.relationThumbnail.";
        String[][] fields = {
                { "inputAnchorCount", "inputAnchorCount", "int",
                        "rag.eval.kgAxis.uawRelationThumbnailInputAnchorCount" },
                { "selectedAnchorCount", "selectedAnchorCount", "int",
                        "rag.eval.kgAxis.uawRelationThumbnailSelectedAnchorCount" },
                { "anchorBudget", "anchorBudget", "int",
                        "rag.eval.kgAxis.uawRelationThumbnailAnchorBudget" },
                { "pairBudget", "pairBudget", "int",
                        "rag.eval.kgAxis.uawRelationThumbnailPairBudget" },
                { "emittedPairCount", "emittedPairCount", "int",
                        "rag.eval.kgAxis.uawRelationThumbnailEmittedPairCount" },
                { "sliced", "sliced", "boolean",
                        "rag.eval.kgAxis.uawRelationThumbnailSliced" }
        };

        StringBuilder body = new StringBuilder();
        int rendered = 0;
        for (String[] field : fields) {
            String label = field[0];
            String key = prefix + field[1];
            String type = field[2];
            Object value = extraMeta.get(key);
            if (!extraMeta.containsKey(key) && field.length > 3) {
                value = extraMeta.get(field[3]);
            }
            if (value == null) {
                continue;
            }
            body.append("<tr>")
                    .append(relationSliceCell(label))
                    .append(relationBudgetValueCell(value, type))
                    .append("</tr>");
            rendered++;
        }
        if (rendered == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-orch-callout' style='margin:10px 0;padding:10px;border:1px solid #ddd;")
                .append("border-left:6px solid #7b1fa2;background:#fafafa;'>");
        sb.append("<div><b>UAW Relation Thumbnail Budget</b></div>");
        sb.append("<table class='trace-kv' style='margin-top:6px'><thead><tr>")
                .append("<th>field</th><th>value</th>")
                .append("</tr></thead><tbody>")
                .append(body)
                .append("</tbody></table>");
        sb.append("</div>");
        return sb.toString();
    }

    static String renderContextLayers(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty()) {
            return "";
        }
        String prefix = "rgb.soak.strategy.";
        String suffix = ".relationThumbnailContextLayerCounts";
        StringBuilder body = new StringBuilder();
        int rendered = 0;
        for (Map.Entry<String, Object> entry : extraMeta.entrySet()) {
            String key = entry.getKey();
            if (key == null || !key.startsWith(prefix) || !key.endsWith(suffix)) {
                continue;
            }
            String strategy = safeRelationLayerToken(key.substring(prefix.length(), key.length() - suffix.length()));
            if (strategy.isBlank() || !(entry.getValue() instanceof Map<?, ?> counts)) {
                continue;
            }
            for (Map.Entry<?, ?> countEntry : counts.entrySet()) {
                String layer = safeRelationLayerToken(countEntry.getKey());
                Integer count = toInt(countEntry.getValue());
                if (layer.isBlank() || count == null || count <= 0) {
                    continue;
                }
                body.append("<tr>")
                        .append(relationSliceCell(strategy))
                        .append(relationSliceCell(layer))
                        .append(relationSliceCell(count))
                        .append("</tr>");
                if (++rendered >= 12) {
                    break;
                }
            }
            if (rendered >= 12) {
                break;
            }
        }
        if (rendered == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-orch-callout' style='margin:10px 0;padding:10px;border:1px solid #ddd;")
                .append("border-left:6px solid #455a64;background:#fafafa;'>");
        sb.append("<div><b>Relation Thumbnail Context Layers</b></div>");
        sb.append("<table class='trace-kv' style='margin-top:6px'><thead><tr>")
                .append("<th>strategy</th><th>contextLayer</th><th>count</th>")
                .append("</tr></thead><tbody>")
                .append(body)
                .append("</tbody></table>");
        sb.append("</div>");
        return sb.toString();
    }

    static String renderSliceMap(Map<String, Object> extraMeta) {
        if (extraMeta == null || extraMeta.isEmpty()) {
            return "";
        }
        Object raw = extraMeta.get("retrieval.kg.relationThumbnail.sliceMap");
        if (!(raw instanceof Iterable<?> rows)) {
            return "";
        }

        StringBuilder body = new StringBuilder();
        int rendered = 0;
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row)) {
                continue;
            }
            body.append("<tr>")
                    .append(relationSliceCell(row.get("rank")))
                    .append(relationSliceCell(row.get("hash")))
                    .append(relationSliceCell(row.get("relationKind")))
                    .append(relationSliceCell(SafeRedactor.traceLabelOrFallback(row.get("selectionReason"), "reason")))
                    .append(relationSliceCell(row.get("contextLayer")))
                    .append(relationSliceCell(row.get("overlap")))
                    .append("</tr>");
            if (++rendered >= 8) {
                break;
            }
        }
        if (rendered == 0) {
            return "";
        }

        Integer count = toInt(extraMeta.get("retrieval.kg.relationThumbnail.sliceMapCount"));
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='trace-orch-callout' style='margin:10px 0;padding:10px;border:1px solid #ddd;")
                .append("border-left:6px solid #607d8b;background:#fafafa;'>");
        sb.append("<div><b>Relation Thumbnail Slice Map</b>");
        if (count != null) {
            sb.append(": sliceMapCount=").append(count);
        }
        if (count != null && count > rendered) {
            sb.append(" <span class='text-muted small'>(showing ").append(rendered).append(")</span>");
        }
        sb.append("</div>");
        sb.append("<table class='trace-kv' style='margin-top:6px'><thead><tr>")
                .append("<th>rank</th><th>hash</th><th>relationKind</th><th>selectionReason</th>")
                .append("<th>contextLayer</th><th>overlap</th>")
                .append("</tr></thead><tbody>")
                .append(body)
                .append("</tbody></table>");
        sb.append("</div>");
        return sb.toString();
    }

    private static String relationBudgetValueCell(Object value, String type) {
        return "<td><code>" + escape(coerceRelationBudgetValue(value, type)) + "</code></td>";
    }

    private static String coerceRelationBudgetValue(Object value, String type) {
        if ("boolean".equals(type)) {
            if (value instanceof Boolean b) {
                return String.valueOf(b);
            }
            String s = value == null ? "" : String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s)) {
                return s.toLowerCase(java.util.Locale.ROOT);
            }
            return "n/a";
        }
        Integer n = toInt(value);
        return n == null ? "n/a" : String.valueOf(n);
    }

    private static String safeRelationLayerToken(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank() || text.length() > 64 || !text.matches("[A-Za-z0-9_.:-]+")) {
            return "";
        }
        return text;
    }

    private static String relationSliceCell(Object value) {
        return "<td><code>" + escape(safeValueOrDefault(value, "")) + "</code></td>";
    }

    private static Integer toInt(Object v) {
        if (v instanceof Number n) {
            double numeric = n.doubleValue();
            if (Double.isFinite(numeric)) {
                return n.intValue();
            }
            TraceStore.put("traceHtml.relationThumbnail.suppressed.toInt", true);
            TraceStore.put("traceHtml.relationThumbnail.suppressed.toInt.errorType", "invalid_number");
            return null;
        }
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            TraceStore.put("traceHtml.relationThumbnail.suppressed.toInt", true);
            TraceStore.put("traceHtml.relationThumbnail.suppressed.toInt.errorType", "invalid_number");
            return null;
        }
    }

    private static String safeValueOrDefault(Object v, String defaultValue) {
        String s = safeValue(v);
        if (s == null || s.isBlank() || "null".equals(s)) {
            return defaultValue;
        }
        return s;
    }

    private static String safeValue(Object v) {
        if (v == null) {
            return "null";
        }
        String s = String.valueOf(v);
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return SafeRedactor.safeMessage(s, 800);
    }

    private static String escape(String s) {
        return (s == null ? "" : s)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
