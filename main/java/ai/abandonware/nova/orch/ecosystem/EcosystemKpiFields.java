package ai.abandonware.nova.orch.ecosystem;

import com.example.lms.search.TraceStore;

import java.util.Map;

/**
 * Scalar-only ecosystem KPI projection for logs and probe snapshots.
 */
public final class EcosystemKpiFields {

    private EcosystemKpiFields() {
    }

    public static void putTraceKpi(Map<String, Object> kpi) {
        if (kpi == null) {
            return;
        }
        kpi.put("ecosystem.recirculate.used", truthy(TraceStore.get("ecosystem.recirculate.used")));
        kpi.put("ecosystem.recirculate.count", number(TraceStore.get("ecosystem.recirculate.count")));
        kpi.put("ecosystem.recirculate.safe", number(TraceStore.get("ecosystem.recirculate.safe")));
        kpi.put("ecosystem.recirculate.allUnverified",
                truthy(TraceStore.get("ecosystem.recirculate.allUnverified")));
        kpi.put("ecosystem.pool.size", number(TraceStore.get("ecosystem.pool.size")));
        kpi.put("ecosystem.recycled.total", number(TraceStore.get("ecosystem.recycled.total")));
        kpi.put("ecosystem.ammonia.score", text(TraceStore.get("ecosystem.ammonia.score"), 16));
        kpi.put("ecosystem.ammonia.quarantined", number(TraceStore.get("ecosystem.ammonia.quarantined")));
        kpi.put("ecosystem.ammonia.safe", number(TraceStore.get("ecosystem.ammonia.safe")));
        kpi.put("ecosystem.ammonia.threshold", text(TraceStore.get("ecosystem.ammonia.threshold"), 16));
        kpi.put("ecosystem.ammonia.surgeBlocked", truthy(TraceStore.get("ecosystem.ammonia.surgeBlocked")));
    }

    private static boolean truthy(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) && n.longValue() != 0L;
        }
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static long number(Object value) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? n.longValue() : 0L;
        }
        if (value == null) {
            return 0L;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return 0L;
        }
        boolean negative = text.charAt(0) == '-';
        String digits = negative ? text.substring(1) : text;
        if (digits.isEmpty()) {
            return 0L;
        }
        long result = 0L;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                return 0L;
            }
            int next = c - '0';
            if (result > (Long.MAX_VALUE - next) / 10L) {
                return negative ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            result = result * 10L + next;
        }
        return negative ? -result : result;
    }

    private static String text(Object value, int maxChars) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value).replaceAll("[\\r\\n\\t]+", " ").trim();
        int limit = Math.max(0, maxChars);
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
