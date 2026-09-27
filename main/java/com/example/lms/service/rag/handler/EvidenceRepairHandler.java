package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.service.rag.AnswerQualityEvaluator;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

public class EvidenceRepairHandler extends AbstractRetrievalHandler implements ContentRetriever {
    private static final Logger log = LoggerFactory.getLogger(EvidenceRepairHandler.class);

    private final WebSearchRetriever web;
    private final SubjectResolver subjectResolver;
    private final String domain;
    private final String preferredDomains;

    @Autowired(required = false)
    private AnswerQualityEvaluator evaluator;

    @Value("${rag.critic.enabled:true}")
    private boolean criticEnabled;
    @Value("${rag.critic.min-docs:2}")
    private int minDocs;
    @Value("${rag.critic.min-avg-score:0.45}")
    private double minAvgScore;
    @Value("${rag.critic.min-distinct-sources:2}")
    private int minDistinctSources;

    public EvidenceRepairHandler(WebSearchRetriever web,
                                 SubjectResolver subjectResolver,
                                 String domain,
                                 String preferredDomains) {
        this.web = web;
        this.subjectResolver = subjectResolver;
        this.domain = domain;
        this.preferredDomains = preferredDomains;
    }

    @Override
    protected boolean doHandle(Query query, List<Content> accumulator) {
        if (!criticEnabled || accumulator == null) {
            return true;
        }
        try {
            if (evaluator != null) {
                AnswerQualityEvaluator.RetrievalEvaluation ev = evaluator.evaluateRetrieval(
                        query != null ? query.text() : "",
                        accumulator,
                        minDocs,
                        minAvgScore,
                        minDistinctSources);
                TraceStore.put("rag.critic.triggered", ev.decision() != AnswerQualityEvaluator.Decision.ACCEPT);
                TraceStore.put("rag.critic.reason", safeCriticReason(ev.reason()));
                TraceStore.put("rag.critic.docs.before", ev.docCount());
                if (ev.decision() == AnswerQualityEvaluator.Decision.ACCEPT) {
                    return true;
                }
                if (ev.decision() != AnswerQualityEvaluator.Decision.REPAIR_WITH_WEB) {
                    TraceStore.put("rag.critic.retry.count", 0);
                    TraceStore.put("rag.critic.docs.after", accumulator.size());
                    return true;
                }
            }
            int before = accumulator.size();
            List<Content> repaired = retrieve(query);
            if (repaired != null && !repaired.isEmpty()) {
                accumulator.addAll(repaired);
            }
            TraceStore.put("rag.critic.retry.count", before == accumulator.size() ? 0 : 1);
            TraceStore.put("rag.critic.docs.after", accumulator.size());
            log.debug("[CRAG][repair] rerun={}, before={}, after={}, reason={}",
                    before != accumulator.size(), before, accumulator.size(), TraceStore.get("rag.critic.reason"));
        } catch (Exception e) {
            log.warn("[AWX][rag][handler] evidenceRepair failed failureReason={} errorType={} queryHash12={} queryLength={}",
                    "evidence-repair-handler-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hash12(query == null ? null : query.text()),
                    query == null || query.text() == null ? 0 : query.text().length());
        }
        return true;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query == null) {
            recordRepairInner("skipped", 0, null, "empty-query", null);
            return List.of();
        }
        try {
            List<Content> items = web.retrieve(query);
            if (items == null || items.isEmpty()) {
                recordRepairInner("empty", 0, null, "", query);
                return List.of();
            }

            List<String> pref = parseCsv(preferredDomains);
            List<Content> out = (domain == null || domain.isBlank()) && pref.isEmpty()
                    ? dedupe(items)
                    : dedupe(prioritize(items, pref, domain));
            recordRepairInner(out.isEmpty() ? "empty" : "success", out.size(), null, "", query);
            return out;
        } catch (Exception e) {
            recordRepairInner("failed", 0, e, classifyFailure(e), query);
            log.warn("[AWX][rag][handler] evidenceRepair retrieve failed failureReason={} errorType={} failureClass={} queryHash12={} queryLength={}",
                    "evidence-repair-retrieve-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.traceLabelOrFallback(classifyFailure(e), "silent-failure"),
                    SafeRedactor.hash12(query == null ? null : query.text()),
                    query == null || query.text() == null ? 0 : query.text().length());
            throw new IllegalStateException("repair retrieval failed: " + e.getClass().getSimpleName(), e);
        }
    }

    private static void recordRepairInner(String status, int outCount, Throwable error, String failureClass, Query query) {
        String safeStatus = (status == null || status.isBlank()) ? "unknown" : status;
        String exceptionType = error == null ? "" : error.getClass().getSimpleName();
        String safeFailure = failureClass == null ? "" : failureClass;
        try {
            TraceStore.put("retrieval.stage.repair.innerStatus", safeStatus);
            TraceStore.put("retrieval.stage.repair.innerOutCount", Math.max(0, outCount));
            TraceStore.put("retrieval.stage.repair.exceptionType", exceptionType);
            TraceStore.put("retrieval.stage.repair.failureClass", safeFailure);
            String q = query == null ? null : query.text();
            if (q != null && !q.isBlank()) {
                TraceStore.put("retrieval.stage.repair.queryHash12", SafeRedactor.hash12(q));
            }
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("stageName", "repair");
            event.put("innerStatus", safeStatus);
            event.put("outCount", Math.max(0, outCount));
            event.put("exceptionType", exceptionType);
            event.put("failureClass", safeFailure);
            TraceStore.append("retrieval.stage.repair.events", event);
        } catch (Exception ignore) {
            RetrievalHandlerTraceSuppressions.traceSuppressed(log, "EvidenceRepairHandler", "repair.trace", ignore);
        }
    }

    private static String classifyFailure(Throwable error) {
        if (error == null) {
            return "";
        }
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = root.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        String msg = root.getMessage() == null ? "" : root.getMessage().toLowerCase(Locale.ROOT);
        if (root instanceof java.util.concurrent.CancellationException
                || root instanceof InterruptedException
                || name.contains("cancel")
                || name.contains("interrupt")
                || msg.contains("cancelled")
                || msg.contains("canceled")
                || msg.contains("interrupted")) {
            return "cancelled";
        }
        if (name.contains("timeout") || msg.contains("timeout")) {
            return "timeout";
        }
        if (msg.contains("rate limit") || msg.contains("429")) {
            return "rate-limit";
        }
        if (msg.contains("credential") || msg.contains("api key") || msg.contains("unauthorized")) {
            return "provider-disabled";
        }
        return "silent-failure";
    }

    private static String safeCriticReason(String reason) {
        if (reason == null) {
            return null;
        }
        String withoutHeaders = reason.replaceAll("(?i)\\bauthorization\\s*:\\s*bearer\\s+\\S+", "auth_header_redacted");
        return SafeRedactor.safeMessage(withoutHeaders, 120);
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<Content> prioritize(List<Content> items, List<String> preferred, String domain) {
        if ((preferred == null || preferred.isEmpty()) && (domain == null || domain.isBlank())) return items;
        return items.stream()
                .sorted((a, b) -> Integer.compare(score(textOf(b), preferred, domain),
                        score(textOf(a), preferred, domain)))
                .collect(Collectors.toList());
    }

    private static String textOf(Content c) {
        if (c == null) return "";
        var ts = c.textSegment();
        return (ts != null) ? ts.text() : c.toString();
    }

    private static int score(String text, List<String> preferred, String domain) {
        int s = 0;
        if (domain != null && !domain.isBlank() && text.contains(domain)) s += 2;
        if (preferred != null) {
            for (String d : preferred) {
                if (!d.isBlank() && text.contains(d)) s += 1;
            }
        }
        return s;
    }

    private static List<Content> dedupe(List<Content> items) {
        if (items == null || items.isEmpty()) return List.of();
        LinkedHashMap<String, Content> out = new LinkedHashMap<>();
        for (Content c : items) {
            String key = textOf(c).replaceAll("\\s+", " ").trim();
            if (!key.isBlank()) {
                out.putIfAbsent(key, c);
            }
        }
        return new ArrayList<>(out.values());
    }
}
