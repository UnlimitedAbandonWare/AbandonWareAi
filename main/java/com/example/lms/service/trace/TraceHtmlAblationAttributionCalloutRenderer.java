package com.example.lms.service.trace;

import java.util.List;

import com.example.lms.trace.SafeRedactor;
import com.example.lms.trace.attribution.TraceAblationAttributionResult;

final class TraceHtmlAblationAttributionCalloutRenderer {

    private TraceHtmlAblationAttributionCalloutRenderer() {
    }

    static String render(TraceAblationAttributionResult result) {
        if (result == null || result.contributors() == null || result.contributors().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(
                "<div style=\"margin:8px 0 10px 0; padding:10px 12px; border-left:4px solid #4a90e2; background:#f7fbff; border-radius:6px;\">");
        sb.append("<div style=\"font-weight:700; margin-bottom:4px;\">Trace-Ablation Attribution</div>");
        sb.append("<div style=\"font-size:12px; color:#555; margin-bottom:8px;\">");
        sb.append("outcome=").append(escape(safeDisplay(result.outcome(), 160)));
        sb.append(" / risk=").append(String.format(java.util.Locale.ROOT, "%.3f", result.outcomeRisk()));
        sb.append(" / v=").append(escape(safeDisplay(result.version(), 120)));
        sb.append("</div>");

        sb.append("<div style=\"font-size:13px;\">");
        sb.append("<details open><summary style=\"cursor:pointer;\"><b>Top contributors</b></summary>");
        sb.append("<ol style=\"margin:8px 0 0 18px; padding:0;\">");

        int limit = Math.min(6, result.contributors().size());
        for (int i = 0; i < limit; i++) {
            appendContributor(sb, result.contributors().get(i));
        }
        sb.append("</ol></details>");

        appendBeams(sb, result.beams());

        sb.append("</div></div>");
        return sb.toString();
    }

    private static void appendContributor(StringBuilder sb, TraceAblationAttributionResult.Contributor contributor) {
        if (contributor == null) {
            return;
        }
        int pct = (int) Math.round(contributor.contribution() * 100.0);
        sb.append("<li style=\"margin:6px 0;\">");
        sb.append("<details>");
        sb.append("<summary style=\"cursor:pointer;\">");
        sb.append("<span style=\"display:inline-block; min-width:42px; font-weight:700;\">").append(pct)
                .append("%</span>");
        sb.append("<span style=\"color:#666;\">[").append(escape(safeDisplay(contributor.group(), 120)))
                .append("]</span> ");
        sb.append(escape(safeDisplay(contributor.title(), 240)));
        sb.append("</summary>");

        if (contributor.evidence() != null && !contributor.evidence().isEmpty()) {
            sb.append("<div style=\"margin-top:6px;\"><b>evidence</b>");
            sb.append(renderSimpleList(contributor.evidence()));
            sb.append("</div>");
        }
        if (contributor.recommendations() != null && !contributor.recommendations().isEmpty()) {
            sb.append("<div style=\"margin-top:6px;\"><b>fix hints</b>");
            sb.append(renderSimpleList(contributor.recommendations()));
            sb.append("</div>");
        }
        sb.append("</details>");
        sb.append("</li>");
    }

    private static void appendBeams(StringBuilder sb, List<TraceAblationAttributionResult.Beam> beams) {
        if (beams == null || beams.isEmpty()) {
            return;
        }
        sb.append("<details style=\"margin-top:10px;\">");
        sb.append("<summary style=\"cursor:pointer;\"><b>Self-Ask beams</b></summary>");
        sb.append(
                "<div style=\"margin-top:6px; font-size:12px; color:#666;\">beam search over evidence-weighted hypotheses</div>");
        int limit = Math.min(2, beams.size());
        for (int i = 0; i < limit; i++) {
            appendBeam(sb, i, beams.get(i));
        }
        sb.append("</details>");
    }

    private static void appendBeam(StringBuilder sb, int index, TraceAblationAttributionResult.Beam beam) {
        if (beam == null) {
            return;
        }
        sb.append(
                "<div style=\"margin-top:8px; padding:8px; border:1px solid #e6eef8; border-radius:6px; background:#fff;\">");
        sb.append("<div style=\"font-weight:700;\">beam #").append(index + 1).append("</div>");
        sb.append("<div style=\"font-size:12px; color:#666;\">score=").append(beam.score())
                .append(", weight=").append(beam.weight()).append("</div>");
        if (beam.steps() != null && !beam.steps().isEmpty()) {
            sb.append("<ul style=\"margin:8px 0 0 18px;\">");
            for (TraceAblationAttributionResult.QaStep step : beam.steps()) {
                appendQaStep(sb, step);
            }
            sb.append("</ul>");
        }
        sb.append("</div>");
    }

    private static void appendQaStep(StringBuilder sb, TraceAblationAttributionResult.QaStep step) {
        if (step == null) {
            return;
        }
        sb.append("<li>");
        sb.append("<b>Q</b> ").append(escape(safeDiagnostic("selfAsk.question", step.question()))).append("<br/>");
        sb.append("<b>A</b> ").append(escape(safeDiagnostic("selfAsk.answer", step.answer())));
        sb.append("</li>");
    }

    private static String renderSimpleList(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<ul style=\"margin:6px 0 0 18px; padding:0;\">");
        int limit = Math.min(8, items.size());
        for (int i = 0; i < limit; i++) {
            String item = items.get(i);
            if (item == null || item.isBlank()) {
                continue;
            }
            sb.append("<li>").append(escape(safeDiagnostic("traceAblation.listItem", item))).append("</li>");
        }
        if (items.size() > limit) {
            sb.append("<li>(").append(items.size() - limit).append(" more)</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private static String safeDiagnostic(String key, Object value) {
        Object safe = SafeRedactor.diagnosticValue(key, value, 800);
        return safeValue(safe);
    }

    private static String safeDisplay(String value, int maxLength) {
        return SafeRedactor.safeMessage(value, maxLength);
    }

    private static String safeValue(Object value) {
        if (value == null) {
            return "null";
        }
        String s = String.valueOf(value).replace("\n", " ").trim();
        return s.length() > 800 ? s.substring(0, 800) + "..." : s;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
