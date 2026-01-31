package ai.abandonware.nova.orch.adapters;

import com.example.lms.infra.exec.ContextPropagation;
import com.example.lms.search.policy.SearchPolicyDecision;
import com.example.lms.search.policy.SearchPolicyEngine;
import com.example.lms.search.provider.WebSearchProvider;
import com.example.lms.service.rag.AnalyzeWebSearchRetriever;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import com.example.lms.service.routing.plan.RoutingPlanService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.apache.lucene.analysis.Analyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Nova overlay implementation of {@link AnalyzeWebSearchRetriever}.
 *
 * <p>Fixes:</p>
 * <ul>
 *   <li>Only attempts Brave JSON flattening when the snippet actually looks like Brave JSON.</li>
 *   <li>Better URL extraction for de-duplication when snippets are HTML or stage-tagged.</li>
 * </ul>
 */
public class NovaAnalyzeWebSearchRetriever extends AnalyzeWebSearchRetriever implements ContentRetriever {

    private static final Logger log = LoggerFactory.getLogger(NovaAnalyzeWebSearchRetriever.class);

    private static final Pattern HREF_DQ = Pattern.compile("href=\\\"(https?://[^\\\"\\s>]+)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_SQ = Pattern.compile("href='(https?://[^'\\s>]+)'", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_URL = Pattern.compile("(https?://\\S+)", Pattern.CASE_INSENSITIVE);

    private final Analyzer analyzer; // signature compatibility
    private final WebSearchProvider webSearchProvider;
    private final QueryContextPreprocessor preprocessor;
    private final RoutingPlanService routingPlanService;
    private final SearchPolicyEngine searchPolicyEngine;
    private final ExecutorService searchIoExecutor;
    private final ObjectMapper objectMapper;

    private volatile int timeoutMs = 1800;
    private volatile int webTopK = 10;

    public NovaAnalyzeWebSearchRetriever(
            Analyzer analyzer,
            WebSearchProvider webSearchProvider,
            @Qualifier("guardrailQueryPreprocessor") QueryContextPreprocessor preprocessor,
            RoutingPlanService routingPlanService,
            SearchPolicyEngine searchPolicyEngine,
            @Qualifier("searchIoExecutor") ExecutorService searchIoExecutor,
            ObjectMapper objectMapper) {
        super(analyzer, webSearchProvider, preprocessor, routingPlanService, searchPolicyEngine, searchIoExecutor);
        this.analyzer = analyzer;
        this.webSearchProvider = Objects.requireNonNull(webSearchProvider);
        this.preprocessor = Objects.requireNonNull(preprocessor);
        this.routingPlanService = Objects.requireNonNull(routingPlanService);
        this.searchPolicyEngine = Objects.requireNonNull(searchPolicyEngine);
        this.searchIoExecutor = Objects.requireNonNull(searchIoExecutor);
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    @Override
    public void setTimeoutMs(int ms) {
        super.setTimeoutMs(ms);
        this.timeoutMs = ms;
    }

    @Override
    public void setWebTopK(int k) {
        super.setWebTopK(k);
        this.webTopK = k;
    }

    @Override
    public List<Content> retrieve(Query query) {
        String originalQuery = (query != null && query.text() != null) ? query.text().trim() : "";
        {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("purpose", "WEB_SEARCH");
            originalQuery = preprocessor.enrich(originalQuery, meta);
        }
        if (originalQuery == null || originalQuery.isBlank()) {
            return Collections.emptyList();
        }

        Map<String, Object> qMeta = (query != null && query.metadata() != null)
                ? ((Metadata) query.metadata()).asMap()
                : Collections.emptyMap();

        SearchPolicyDecision spDecision = null;
        try {
            spDecision = searchPolicyEngine.decide(originalQuery, qMeta);
        } catch (Exception ignore) {
            // fail-soft
        }

        boolean explicitWebTopK = qMeta.containsKey("webTopK");
        int calculatedTopK = metaInt(qMeta, "webTopK", this.webTopK);
        if (!explicitWebTopK && spDecision != null) {
            calculatedTopK = searchPolicyEngine.tuneTopK(calculatedTopK, spDecision);
        }
        final int reqTopK = calculatedTopK;

        int plannerMax = 8;
        if (spDecision != null) {
            plannerMax = searchPolicyEngine.tunePlannerMaxQueries(plannerMax, spDecision);
        }

        List<String> basePlan = routingPlanService.plan(originalQuery, null, plannerMax);
        List<String> queries = (spDecision != null)
                ? searchPolicyEngine.apply(basePlan, originalQuery, spDecision)
                : basePlan;

        if (queries == null || queries.isEmpty()) {
            queries = List.of(originalQuery);
        }

        List<Callable<List<String>>> tasks = new ArrayList<>();
        for (String q : queries) {
            if (q == null || q.isBlank()) {
                continue;
            }
            tasks.add(ContextPropagation.wrapCallable(() -> {
                try {
                    return webSearchProvider.search(q, reqTopK);
                } catch (Exception e) {
                    log.debug("[Analyze][nova] web search failed: {}", e.toString());
                    return Collections.emptyList();
                }
            }));
        }

        List<String> merged = new ArrayList<>();

        // IMPORTANT: avoid ExecutorService.invokeAll(..., timeout) here.
        // The JDK's invokeAll timeout path cancels unfinished tasks (often via cancel(true)),
        // which can "poison" pooled workers. Use CompletionService + poll and best-effort
        // cancel(false).
        final long hardTimeoutMs = Math.max(250L, timeoutMs);
        final long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(hardTimeoutMs);
        java.util.concurrent.CompletionService<List<String>> cs =
                new java.util.concurrent.ExecutorCompletionService<>(searchIoExecutor);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (Callable<List<String>> task : tasks) {
            futures.add(cs.submit(task));
        }

        int remaining = tasks.size();
        while (remaining > 0) {
            long remNs = deadlineNs - System.nanoTime();
            if (remNs <= 0L) {
                break;
            }
            long remMs = TimeUnit.NANOSECONDS.toMillis(remNs);
            // Precision guard: toMillis(...) truncates; if time remains but converts to 0ms,
            // keep a 1ms floor so late completions can be harvested before we cancel.
            if (remMs <= 0L) {
                remMs = 1L;
            }
            try {
                Future<List<String>> f = cs.poll(remMs, TimeUnit.MILLISECONDS);
                if (f == null) {
                    break;
                }
                remaining--;
                if (f.isCancelled()) {
                    continue;
                }
                try {
                    List<String> part = f.get();
                    if (part != null && !part.isEmpty()) {
                        merged.addAll(part);
                    }
                } catch (Exception e) {
                    log.debug("[Analyze][nova] partial search failure: {}", e.toString());
                }
            } catch (InterruptedException ie) {
                // Avoid poisoning pooled request threads.
                Thread.interrupted();
                break;
            }
        }

        // Best-effort cancel of unfinished tasks without interrupt.
        for (Future<List<String>> f : futures) {
            if (f != null && !f.isDone()) {
                try {
                    f.cancel(false);
                } catch (Exception ignore) {
                    // ignore
                }
            }
        }

        List<String> flattened = new ArrayList<>();
        for (String raw : merged) {
            Optional<List<String>> maybeJson = tryFlattenBraveJson(raw, reqTopK);
            if (maybeJson.isPresent()) {
                flattened.addAll(maybeJson.get());
            } else {
                flattened.add(raw);
            }
        }

        LinkedHashMap<String, String> dedup = new LinkedHashMap<>();
        for (String snippet : flattened) {
            if (snippet == null || snippet.isBlank()) {
                continue;
            }
            String url = extractUrl(snippet);
            String key = (url != null && !url.isBlank()) ? url : snippet;
            dedup.putIfAbsent(key, snippet);
        }

        if (dedup.isEmpty()) {
            // Fallback: direct original query.
            try {
                List<String> fallback = webSearchProvider.search(originalQuery, reqTopK);
                if (fallback != null) {
                    for (String raw : fallback) {
                        Optional<List<String>> maybeJson = tryFlattenBraveJson(raw, reqTopK);
                        List<String> items = maybeJson.orElse(List.of(raw));
                        for (String s : items) {
                            if (s == null || s.isBlank()) continue;
                            String url = extractUrl(s);
                            String key = (url != null && !url.isBlank()) ? url : s;
                            dedup.putIfAbsent(key, s);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[Analyze][nova] fallback search failed: {}", e.toString());
            }
        }

        return dedup.values().stream()
                .limit(reqTopK)
                .map(Content::from)
                .collect(Collectors.toList());
    }

    private static int metaInt(Map<String, Object> meta, String key, int defaultValue) {
        if (meta == null || key == null) {
            return defaultValue;
        }
        Object v = meta.get(key);
        if (v == null) {
            return defaultValue;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return defaultValue;
            }
            return Integer.parseInt(s);
        } catch (Exception ignore) {
            return defaultValue;
        }
    }

    private Optional<List<String>> tryFlattenBraveJson(String raw, int max) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = stripCodeFence(raw);
        if (!looksLikeBraveJson(cleaned)) {
            return Optional.empty();
        }
        try {
            List<String> flattened = flattenBraveJson(cleaned, max);
            return (flattened == null || flattened.isEmpty()) ? Optional.empty() : Optional.of(flattened);
        } catch (Exception e) {
            // Only warn for JSON-looking payloads.
            log.warn("[AnalyzeWebSearchRetriever][nova] Failed to flatten Brave JSON snippet: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private boolean looksLikeBraveJson(String s) {
        if (s == null) return false;
        String trimmed = s.trim();
        if (trimmed.length() < 100) {
            return false;
        }
        return trimmed.startsWith("{")
                && trimmed.contains("\"web\"")
                && trimmed.contains("\"results\"");
    }

    private List<String> flattenBraveJson(String json, int max) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode results = root.path("web").path("results");
        List<String> out = new ArrayList<>();
        if (results.isArray()) {
            for (JsonNode node : results) {
                String title = node.path("title").asText("");
                String desc = node.path("description").asText("");
                String url = node.path("url").asText("");

                StringBuilder sb = new StringBuilder();
                if (!title.isBlank()) sb.append(title.trim()).append("\n");
                if (!desc.isBlank()) sb.append(desc.trim()).append("\n");
                if (!url.isBlank()) sb.append("URL: ").append(url.trim());

                String snippet = sb.toString().trim();
                if (!snippet.isBlank()) {
                    out.add(snippet);
                }
                if (out.size() >= max) {
                    break;
                }
            }
        }
        return out;
    }

    private static String stripCodeFence(String s) {
        String trimmed = (s == null) ? "" : s.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }
        if (trimmed.endsWith("```")) {
            int lastFence = trimmed.lastIndexOf("```");
            if (lastFence >= 0) {
                trimmed = trimmed.substring(0, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String extractUrl(String snippet) {
        if (snippet == null) {
            return null;
        }
        String s = snippet;

        int idx = s.indexOf("URL:");
        if (idx >= 0) {
            String rest = s.substring(idx + 4).trim();
            int newline = rest.indexOf('\n');
            if (newline >= 0) {
                rest = rest.substring(0, newline);
            }
            return cleanupUrl(rest);
        }

        Matcher m1 = HREF_DQ.matcher(s);
        if (m1.find()) {
            return cleanupUrl(m1.group(1));
        }
        Matcher m2 = HREF_SQ.matcher(s);
        if (m2.find()) {
            return cleanupUrl(m2.group(1));
        }

        Matcher m3 = BARE_URL.matcher(s);
        if (m3.find()) {
            return cleanupUrl(m3.group(1));
        }

        return null;
    }

    private static String cleanupUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        // strip trailing punctuation common in logs/markdown
        while (!u.isEmpty()) {
            char c = u.charAt(u.length() - 1);
            if (c == ')' || c == ']' || c == '}' || c == '>' || c == '.' || c == ',' || c == ';') {
                u = u.substring(0, u.length() - 1).trim();
                continue;
            }
            break;
        }
        if (u.length() < 8) return null;
        if (!u.toLowerCase(Locale.ROOT).startsWith("http")) return null;
        return u;
    }
}
