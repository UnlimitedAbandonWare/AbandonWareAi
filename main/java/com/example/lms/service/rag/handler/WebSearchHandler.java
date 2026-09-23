package com.example.lms.service.rag.handler;

import com.example.lms.search.TraceStore;
import com.example.lms.search.terms.SelectedTerms;
import com.example.lms.search.terms.SelectedTermsDebug;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.rag.WebSearchRetriever;
import com.example.lms.trace.SafeRedactor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web retrieval handler.
 *
 * <p>Enhancements:
 * <ul>
 *   <li>Records hash/length effective-query and {@code web.selectedTerms.*} diagnostics in {@link TraceStore}.</li>
 *   <li>Stores safe "applied tokens" samples to make root-cause / stage / engine visible.</li>
 * </ul>
 */
@RequiredArgsConstructor
public class WebSearchHandler extends AbstractRetrievalHandler {

    private static final Logger log = LoggerFactory.getLogger(WebSearchHandler.class);

    private final WebSearchRetriever retriever;

    @Override
    protected boolean doHandle(Query q, List<Content> acc) {
        Query effectiveQuery = q;

        try {
            // Incorporate negative keywords, aliases and preferred domains into
            // the query string by appending exclusion operators and site filters.
            Object selObj = TraceStore.get("selectedTerms");
            if (selObj instanceof SelectedTerms selected) {

                // Make SelectedTerms visible for HTML/console (safe + compact).
                try {
                    TraceStore.put("web.selectedTerms.summary", SelectedTermsDebug.toSummaryString(selected));
                    TraceStore.put("web.selectedTerms", SelectedTermsDebug.toDebugMap(selected, 8));
                } catch (Throwable ignore) {
                    RetrievalHandlerTraceSuppressions.traceSuppressed(log, "WebSearchHandler", "selectedTerms.summary", ignore);
                }

                String base = (q != null && q.text() != null) ? q.text() : "";
                StringBuilder modified = new StringBuilder(base.trim());

                Map<String, Object> applied = new LinkedHashMap<>();

                // Negative keywords: prefix with '-' to exclude undesired topics.
                List<String> usedNeg = new ArrayList<>();
                if (selected.getNegative() != null) {
                    for (String neg : selected.getNegative()) {
                        if (neg == null) continue;
                        String t = neg.trim();
                        if (t.isEmpty()) continue;
                        modified.append(" -").append(t);
                        usedNeg.add("-" + t);
                    }
                }
                if (!usedNeg.isEmpty()) {
                    applied.put("negative", SelectedTermsDebug.sampleTokens(usedNeg, 8));
                    appendRule("applied.negative=" + usedNeg.size());
                }

                // Aliases: add an OR-group.
                List<String> aliases = new ArrayList<>();
                if (selected.getAliases() != null) {
                    for (String a : selected.getAliases()) {
                        if (a == null) continue;
                        String t = a.trim();
                        if (t.isEmpty()) continue;
                        aliases.add(t);
                    }
                }
                if (!aliases.isEmpty()) {
                    modified.append(" (");
                    boolean first = true;
                    for (String a : aliases) {
                        if (!first) modified.append(" OR ");
                        modified.append(a);
                        first = false;
                    }
                    modified.append(")");
                    applied.put("aliases", SelectedTermsDebug.sampleTokens(aliases, 8));
                    appendRule("applied.aliases=" + aliases.size());
                }

                // Preferred domains: add site: filters (keep count low to avoid query bloat).
                List<String> domains = new ArrayList<>();
                if (selected.getDomains() != null) {
                    for (String d : selected.getDomains()) {
                        if (d == null) continue;
                        String t = d.trim();
                        if (t.isEmpty()) continue;
                        domains.add(t);
                    }
                }
                if (!domains.isEmpty()) {
                    int lim = Math.min(domains.size(), 5);
                    for (int i = 0; i < lim; i++) {
                        modified.append(" site:").append(domains.get(i));
                    }
                    applied.put("domains", SelectedTermsDebug.sampleTokens(domains, 8));
                    appendRule("applied.domains=" + lim + (domains.size() > lim ? " (clipped)" : ""));
                }

                // Store applied token samples (merge with any previous values).
                mergeAppliedTokens(applied);

                String effectiveText = modified.toString().trim();
                effectiveQuery = q == null ? QueryUtils.buildQuery(effectiveText) : QueryUtils.rebuild(q, effectiveText);

                // Capture the final effective query without retaining query text in TraceStore.
                try {
                    if (effectiveQuery.text() != null && !effectiveQuery.text().isBlank()) {
                        TraceStore.put("web.effectiveQuery.hash12", SafeRedactor.hash12(effectiveQuery.text()));
                        TraceStore.put("web.effectiveQuery.len", effectiveQuery.text().length());
                    }
                } catch (Throwable ignore) {
                    RetrievalHandlerTraceSuppressions.traceSuppressed(log, "WebSearchHandler", "effectiveQuery.trace", ignore);
                }
            }

            acc.addAll(retriever.retrieve(effectiveQuery));

        } catch (Exception e) {
            traceWebRetrievalFailure(effectiveQuery, e);
            log.warn("[AWX][rag][handler] webSearch failed failureReason={} errorType={} queryHash12={} queryLength={}",
                    "web-search-handler-error",
                    SafeRedactor.traceLabelOrFallback(e.getClass().getSimpleName(), "unknown"),
                    SafeRedactor.hash12(effectiveQuery == null ? null : effectiveQuery.text()),
                    effectiveQuery == null || effectiveQuery.text() == null ? 0 : effectiveQuery.text().length());
        }

        return true;
    }

    private static void traceWebRetrievalFailure(Query query, Throwable error) {
        String queryText = query == null ? null : query.text();
        String queryHash12 = SafeRedactor.hash12(queryText);
        int queryLength = queryText == null ? 0 : queryText.length();
        String safeFailure = error == null
                ? "unknown"
                : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");

        TraceStore.inc("retrieval.web.failureCount");
        TraceStore.put("retrieval.web.status", "failed");
        TraceStore.put("retrieval.web.enabled", true);
        TraceStore.put("retrieval.web.returnedCount", 0);
        TraceStore.put("retrieval.web.addedCount", 0);
        TraceStore.put("retrieval.web.disabledReason", "web-search-handler-error");
        TraceStore.put("retrieval.web.failureClass", safeFailure);
        TraceStore.put("retrieval.web.failSoft", true);
        TraceStore.put("retrieval.web.queryLength", queryLength);
        if (queryHash12 != null && !queryHash12.isBlank()) {
            TraceStore.put("retrieval.web.queryHash12", queryHash12);
        }

        TraceStore.put("retrieval.dependency.web.status", "failed");
        TraceStore.put("retrieval.dependency.web.failureClass", safeFailure);
        TraceStore.put("retrieval.dependency.web.fallbackUsed", true);
        if (queryHash12 != null && !queryHash12.isBlank()) {
            TraceStore.put("retrieval.dependency.web.queryHash12", queryHash12);
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("status", "failed");
        event.put("disabledReason", "web-search-handler-error");
        event.put("failureClass", safeFailure);
        event.put("returnedCount", 0);
        event.put("addedCount", 0);
        event.put("queryLength", queryLength);
        if (queryHash12 != null && !queryHash12.isBlank()) {
            event.put("queryHash12", queryHash12);
        }
        TraceStore.append("retrieval.web.events", event);
        TraceStore.append("retrieval.dependency.events", event);
    }

    private static void mergeAppliedTokens(Map<String, Object> applied) {
        if (applied == null || applied.isEmpty()) return;

        Map<String, Object> merged = new LinkedHashMap<>();
        try {
            Object existing = TraceStore.get("web.selectedTerms.applied");
            if (existing instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getKey() == null) continue;
                    merged.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
        } catch (Throwable t) {
            TraceStore.put("retrieval.handler.suppressed.web.appliedTokens.read", true);
            RetrievalHandlerTraceSuppressions.trace("web.appliedTokens.read", t);
        }
        merged.putAll(applied);

        try {
            TraceStore.put("web.selectedTerms.applied", merged);
        } catch (Throwable t) {
            TraceStore.put("retrieval.handler.suppressed.web.appliedTokens.write", true);
            RetrievalHandlerTraceSuppressions.trace("web.appliedTokens.write", t);
        }
    }

    private static void appendRule(String rule) {
        if (rule == null) return;
        String r = rule.trim();
        if (r.isEmpty()) return;
        r = SafeRedactor.safeMessage(r, 180);
        if (r == null || r.isBlank()) return;

        // Keep it short, low-noise, and safe for HTML/console.
        if (r.length() > 180) r = r.substring(0, 180) + "…";

        try {
            TraceStore.append("web.selectedTerms.rules", r);
        } catch (Throwable t) {
            TraceStore.put("retrieval.handler.suppressed.web.ruleTrace", true);
            RetrievalHandlerTraceSuppressions.trace("web.ruleTrace", t);
        }
    }
}
