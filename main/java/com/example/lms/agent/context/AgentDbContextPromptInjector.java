package com.example.lms.agent.context;

import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

@Component
@ConditionalOnBean(AgentDbContextProvider.class)
public class AgentDbContextPromptInjector {

    private static final int MAX_SUMMARY_CHARS = 900;

    private final AgentDbContextProvider provider;

    public AgentDbContextPromptInjector(AgentDbContextProvider provider) {
        this.provider = provider;
    }

    public void enrichBuilder(PromptContext.Builder builder) {
        if (builder == null || provider == null) {
            return;
        }
        try {
            AgentDbContextProvider.AgentDbSnapshot snapshot = provider.snapshot();
            String dbSummary = snapshotSummary(snapshot);
            if (dbSummary == null || dbSummary.isBlank()) {
                return;
            }
            String existing = builder.build().learningContextSummary();
            builder.learningContextSummary(append(existing, dbSummary));
            TraceStore.put("agent.dbContext.prompt.injected", true);
        } catch (DataAccessException | IllegalStateException ex) {
            TraceStore.put("agent.dbContext.prompt.failSoft", true);
            TraceStore.put("agent.dbContext.prompt.reason", "db_context_snapshot_unavailable");
        }
    }

    public PromptContext enrichContext(PromptContext context) {
        if (context == null) {
            return null;
        }
        PromptContext.Builder builder = context.toBuilder();
        enrichBuilder(builder);
        return builder.build();
    }

    private String snapshotSummary(AgentDbContextProvider.AgentDbSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(256);
        appendMemory(out, snapshot.memory);
        appendHotspots(out, snapshot.ledger);
        appendStrategy(out, snapshot.strategy);
        appendSubsystemAliases(out, snapshot.subsystemPersistence);
        return clip(out.toString().trim(), MAX_SUMMARY_CHARS);
    }

    private void appendMemory(StringBuilder out, AgentDbContextProvider.MemorySnapshot memory) {
        if (memory == null || memory.statusCounts == null || memory.statusCounts.isEmpty()) {
            return;
        }
        appendSeparator(out);
        out.append("db.memory active=")
                .append(count(memory.statusCounts, "ACTIVE"))
                .append(" pending=")
                .append(count(memory.statusCounts, "PENDING"))
                .append(" quarantined=")
                .append(count(memory.statusCounts, "QUARANTINED"));
    }

    private void appendHotspots(StringBuilder out, AgentDbContextProvider.LedgerSnapshot ledger) {
        if (ledger == null || ledger.hotspotDistribution == null || ledger.hotspotDistribution.isEmpty()) {
            return;
        }
        appendSeparator(out);
        out.append("db.hotspots ");
        List<String> items = ledger.hotspotDistribution.stream()
                .limit(3)
                .map(row -> safeLabel(row.get("hotspot"), "unknown") + "=" + nonNegativeLong(row.get("count")))
                .toList();
        out.append(String.join(", ", items));
    }

    private void appendStrategy(StringBuilder out, AgentDbContextProvider.StrategySnapshot strategy) {
        if (strategy == null || strategy.performances == null || strategy.performances.isEmpty()) {
            return;
        }
        Map<String, Object> best = strategy.performances.stream()
                .max(Comparator.comparingLong(row -> nonNegativeLong(row.get("sampleCount"))))
                .orElse(null);
        if (best == null) {
            return;
        }
        appendSeparator(out);
        out.append("db.strategy ")
                .append(safeLabel(best.get("strategyName"), "unknown"))
                .append(" successRate=")
                .append(twoDecimals(best.get("successRate")))
                .append(" samples=")
                .append(nonNegativeLong(best.get("sampleCount")))
                .append(" reward=")
                .append(twoDecimals(best.get("averageReward")));
    }

    private void appendSubsystemAliases(StringBuilder out, Map<String, Map<String, Object>> subsystemPersistence) {
        if (subsystemPersistence == null || subsystemPersistence.isEmpty()) {
            return;
        }
        List<String> aliasSummaries = Stream.of(
                        subsystemPersistence.get("extremeZ"),
                        subsystemPersistence.get("dppDiversityReranker"),
                        subsystemPersistence.get("retrievalOrderService"),
                        subsystemPersistence.get("localLlmProcessManager"))
                .filter(row -> row != null && nonNegativeLong(row.get("aliasCount")) > 1L)
                .map(row -> safeLabel(row.get("component"), "component")
                        + "=" + nonNegativeLong(row.get("aliasCount"))
                        + "/" + safeLabel(row.get("gapClass"), "alias-surface"))
                .toList();
        if (aliasSummaries.isEmpty()) {
            return;
        }
        appendSeparator(out);
        out.append("db.aliases ").append(String.join(", ", aliasSummaries));
    }

    private static void appendSeparator(StringBuilder out) {
        if (!out.isEmpty()) {
            out.append("; ");
        }
    }

    private static String append(String existing, String dbSummary) {
        String safeExisting = existing == null ? "" : existing.trim();
        if (safeExisting.isBlank()) {
            return dbSummary;
        }
        return clip(safeExisting + "\n" + dbSummary, MAX_SUMMARY_CHARS);
    }

    private static long count(Map<String, Long> counts, String key) {
        Long value = counts == null ? null : counts.get(key);
        return value == null ? 0L : Math.max(0L, value);
    }

    private static long nonNegativeLong(Object value) {
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            return Double.isFinite(numeric) ? Math.max(0L, n.longValue()) : 0L;
        }
        String text = String.valueOf(value).trim();
        return text.matches("\\d{1,18}") ? Math.max(0L, Long.parseLong(text)) : 0L;
    }

    private static String twoDecimals(Object value) {
        double number = 0.0d;
        if (value instanceof Number n) {
            double numeric = n.doubleValue();
            number = Double.isFinite(numeric) ? numeric : 0.0d;
        } else {
            String text = String.valueOf(value).trim();
            if (text.matches("\\d{1,3}(?:\\.\\d{1,12})?")) {
                double parsed = Double.parseDouble(text);
                number = Double.isFinite(parsed) ? parsed : 0.0d;
            }
        }
        return String.format(Locale.ROOT, "%.2f", Math.max(0.0d, Math.min(1.0d, number)));
    }

    private static String safeLabel(Object value, String fallback) {
        return clip(SafeRedactor.traceLabelOrFallback(value, fallback), 64);
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        int safeMax = Math.max(1, max);
        if (value.length() <= safeMax) {
            return value;
        }
        int end = safeMax;
        if (Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }
}
