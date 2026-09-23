package com.example.lms.orchestration;

import java.util.List;
import java.util.Map;

final class OrchAutoReportTextRenderer {
    private OrchAutoReportTextRenderer() {
    }

    @SuppressWarnings("unchecked")
    static String renderText(String version, Map<String, Object> report) {
        if (report == null || report.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Orchestration Auto Report (" + version + ")\n\n");
        sb.append("- mode: ").append(safeString(report.get("mode"))).append("\n");
        sb.append("- strike: ").append(report.get("strike")).append("\n");
        sb.append("- bypass: ").append(report.get("bypass")).append("\n");
        sb.append("- compression: ").append(report.get("compression")).append("\n");
        String reason = safeString(report.get("reason"));
        if (!reason.isBlank()) {
            sb.append("- reason: ").append(reason).append("\n");
        }
        Object irr = report.get("irregularity");
        if (irr != null) {
            sb.append("- irregularity: ").append(irr).append("\n");
        }
        sb.append("\n## Top causes\n");
        Object tc = report.get("topCauses");
        if (tc instanceof List<?> list && !list.isEmpty()) {
            int i = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                i++;
                sb.append(i).append(". ").append(safeString(m.get("title")))
                        .append(" (score=").append(safeString(m.get("score"))).append(")\n");
                Object reps = m.get("representatives");
                if (reps instanceof List<?> rl && !rl.isEmpty()) {
                    Object r0 = rl.get(0);
                    if (r0 instanceof Map<?, ?> rm) {
                        sb.append("   - ").append(safeString(rm.get("title")))
                                .append(" (score=").append(safeString(rm.get("score"))).append(")\n");
                    }
                }
                if (i >= 5) {
                    break;
                }
            }
        } else {
            sb.append("(no structured signals found)\n");
        }

        // Provider-level breakdown: tie breaker kind (RATE_LIMIT/TIMEOUT/CANCEL) to
        // observed HTTP status (e.g., 429) so oncall can immediately pick the right lever.
        sb.append("\n## Provider correlation (breaker kind ??HTTP status)\n");
        Object pc = report.get("providerCorrelation");
        if (pc instanceof List<?> list && !list.isEmpty()) {
            int shown = 0;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                String engine = safeString(m.get("engine"));
                if (engine.isBlank()) {
                    engine = safeString(m.get("breakerKey"));
                }
                String ok = safeString(m.get("ok"));
                String nonOk = safeString(m.get("nonOk"));
                String openKind = safeString(m.get("breakerOpenKind"));
                String rl = safeString(m.get("rateLimitSignals"));
                String http429 = safeString(m.get("http429"));
                String timeoutSignals = safeString(m.get("timeoutSignals"));
                String cancelSignals = safeString(m.get("cancelSignals"));
                String rec = safeString(m.get("recommend"));

                sb.append("- ").append(engine);
                if (!openKind.isBlank()) {
                    sb.append(" openKind=").append(openKind);
                }
                if (!rl.isBlank() && !"0".equals(rl)) {
                    sb.append(" RATE_LIMIT=").append(rl);
                }
                if (!http429.isBlank() && !"0".equals(http429)) {
                    sb.append(" http429=").append(http429);
                }
                if (!timeoutSignals.isBlank() && !"0".equals(timeoutSignals)) {
                    sb.append(" timeout=").append(timeoutSignals);
                }
                if (!cancelSignals.isBlank() && !"0".equals(cancelSignals)) {
                    sb.append(" cancelled=").append(cancelSignals);
                }
                if (!ok.isBlank() || !nonOk.isBlank()) {
                    sb.append(" (ok=").append(ok.isBlank() ? "0" : ok)
                            .append(", nonOk=").append(nonOk.isBlank() ? "0" : nonOk)
                            .append(")");
                }
                if (!rec.isBlank()) {
                    sb.append(" ??").append(rec);
                }
                sb.append("\n");
                shown++;
                if (shown >= 10) {
                    break;
                }
            }
        } else {
            sb.append("(none)\n");
        }

        sb.append("\n## Grep shortcuts\n");
        Object gs = report.get("traceShortcutsGrep");
        if (gs instanceof Map<?, ?> gm && !gm.isEmpty()) {
            for (Map.Entry<?, ?> e : gm.entrySet()) {
                sb.append("- ").append(String.valueOf(e.getKey())).append(": ")
                        .append(String.valueOf(e.getValue())).append("\n");
            }
        } else {
            sb.append("(none)\n");
        }

        return sb.toString();
    }

    private static String safeString(Object v) {
        if (v == null) {
            return "";
        }
        return String.valueOf(v);
    }
}
