package com.example.lms.service.rag.langgraph;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class LangGraphContaminationReportStore {

    private static final int MAX_REPORTS = 80;

    private final Object lock = new Object();
    private final ArrayDeque<Entry> reports = new ArrayDeque<>();

    public void add(LangGraphContaminationReport report) {
        if (report == null || report.runId() == null || report.runId().isBlank()) {
            return;
        }
        synchronized (lock) {
            reports.addFirst(new Entry(Instant.now().toString(), report));
            while (reports.size() > MAX_REPORTS) {
                reports.removeLast();
            }
        }
    }

    public List<Map<String, Object>> summaries(int limit) {
        int lim = Math.max(1, Math.min(limit <= 0 ? 50 : limit, MAX_REPORTS));
        List<Map<String, Object>> out = new ArrayList<>(lim);
        synchronized (lock) {
            int i = 0;
            for (Entry entry : reports) {
                if (entry == null || entry.report == null) {
                    continue;
                }
                out.add(summary(entry));
                if (++i >= lim) {
                    break;
                }
            }
        }
        return out;
    }

    public Optional<LangGraphContaminationReport> get(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        synchronized (lock) {
            for (Entry entry : reports) {
                if (entry != null && entry.report != null && runId.equals(entry.report.runId())) {
                    return Optional.of(entry.report);
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, Object> summary(Entry entry) {
        LangGraphContaminationReport report = entry.report;
        LangGraphContaminationReport.ContaminationSummary summary = report.contaminationSummary();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", report.runId());
        out.put("ts", entry.ts);
        out.put("threadIdHash", report.threadIdHash());
        out.put("queryHash", report.queryHash());
        out.put("graphMode", report.graphMode());
        out.put("nodeCount", report.nodes() == null ? 0 : report.nodes().size());
        if (summary != null) {
            out.put("highestRiskNode", summary.highestRiskNode());
            out.put("likelySourceCategory", summary.likelySourceCategory());
            out.put("maxScore", summary.maxScore());
        }
        return out;
    }

    private record Entry(String ts, LangGraphContaminationReport report) {
    }
}
