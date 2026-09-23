package com.abandonware.ai.agent.integrations;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.acme.aicore.domain.ports.RankingPort;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.RankedDoc;
import com.acme.aicore.domain.model.RankingParams;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;





@Component
@ConditionalOnBean(RankingPort.class)
public class AcmeAICoreGateway implements WebSearchGateway {

    private static final System.Logger LOG = System.getLogger(AcmeAICoreGateway.class.getName());
    private static final String PROVIDER_ATTEMPTS_KEY = "agent.acmeGateway.providerAttempts";
    private static final long MAX_ELAPSED_MS = 3_600_000L;

    private final List<WebSearchProvider> providers;
    private final RankingPort ranking;

    @Autowired
    public AcmeAICoreGateway(List<WebSearchProvider> providers, RankingPort ranking) {
        this.providers = providers;
        this.ranking = ranking;
    }

    @Override
    public List<Map<String, Object>> searchAndRank(String query, int topK, String lang) {
        List<SearchBundle> bundles = new ArrayList<>();
        String requestHash = safeHash(query, "query-unavailable");
        String optionsHash = safeHash("topK=" + topK + ";lang=" + String.valueOf(lang), "options-unavailable");
        int attemptOrdinal = 0;
        for (WebSearchProvider p : providers) {
            attemptOrdinal++;
            long startedAtNanos = System.nanoTime();
            ProviderIdentity identity = providerIdentity(p);
            try {
                var bundle = p.search(new WebSearchQuery(query)).block();
                if (bundle == null) {
                    appendProviderAttempt(requestHash, optionsHash, identity, attemptOrdinal,
                            "nonresponse", "nonresponse", startedAtNanos, 0, false);
                    continue;
                }
                bundles.add(bundle);
                int returnedCount = documentCount(bundle);
                String outcome = returnedCount == 0 ? "zero-result" : "success";
                String reason = returnedCount == 0 ? "zero-result" : "none";
                appendProviderAttempt(requestHash, optionsHash, identity, attemptOrdinal,
                        outcome, reason, startedAtNanos, returnedCount, true);
            } catch (RuntimeException e) {
                FailureKind failure = failureKind(e);
                try {
                    traceSuppressed(identity, e);
                } catch (Throwable diagnosticsFailure) {
                    LOG.log(System.Logger.Level.DEBUG,
                            "[AWX][agent][acme-gateway] diagnostics skipped stage=provider_failure_trace errorType={0}",
                            diagnosticsFailure.getClass().getSimpleName());
                }
                appendProviderAttempt(requestHash, optionsHash, identity, attemptOrdinal,
                        failure.outcome(), failure.reason(), startedAtNanos, 0, false);
                // skip provider on error
            }
        }
        if (bundles.isEmpty()) {
            traceResult("zero-result", 0);
            return List.of();
        }

        List<RankedDoc> ranked;
        try {
            ranked = ranking.fuseAndRank(bundles, RankingParams.defaults()).block();
        } catch (RuntimeException e) {
            try {
                traceRankingSuppressed(e);
            } catch (Throwable diagnosticsFailure) {
                LOG.log(System.Logger.Level.DEBUG,
                        "[AWX][agent][acme-gateway] diagnostics skipped stage=ranking_failure_trace errorType={0}",
                        diagnosticsFailure.getClass().getSimpleName());
            }
            traceResult("ranking-failure", 0);
            return List.of();
        }
        if (ranked == null) {
            traceResult("ranking-nonresponse", 0);
            return List.of();
        }

        Map<String, SearchBundle.Doc> byId = bundles.stream()
                .flatMap(b -> b.docs().stream())
                .collect(Collectors.toMap(SearchBundle.Doc::id, d -> d, (a,b)->a));

        List<Map<String,Object>> out = new ArrayList<>();
        for (RankedDoc rd : ranked) {
            var doc = byId.get(rd.id());
            if (doc != null) {
                Map<String,Object> m = new LinkedHashMap<>();
                m.put("id", doc.id());
                m.put("title", doc.title());
                m.put("snippet", doc.snippet());
                m.put("url", doc.url());
                m.put("publishedAt", doc.publishedAt());
                m.put("score", rd.score());
                out.add(m);
                if (out.size() >= Math.max(1, topK)) break;
            }
        }
        String reason = out.isEmpty() && !ranked.isEmpty() ? "after-filter-starvation"
                : out.isEmpty() ? "zero-result" : "success";
        traceResult(reason, out.size());
        return out;
    }

    private static ProviderIdentity providerIdentity(WebSearchProvider provider) {
        String providerId = null;
        try {
            providerId = provider == null ? null : provider.id();
        } catch (Throwable identityFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[AWX][agent][acme-gateway] diagnostics skipped stage=provider_identity errorType={0}",
                    identityFailure.getClass().getSimpleName());
        }
        int length = providerId == null ? 0 : Math.min(providerId.length(), 4_096);
        return new ProviderIdentity(safeHash(providerId, "provider-id-unavailable"), length);
    }

    private static int documentCount(SearchBundle bundle) {
        try {
            return bundle == null || bundle.docs() == null
                    ? 0
                    : Math.min(bundle.docs().size(), 100_000);
        } catch (RuntimeException countFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[AWX][agent][acme-gateway] diagnostics skipped stage=document_count errorType={0}",
                    countFailure.getClass().getSimpleName());
            return 0;
        }
    }

    private static FailureKind failureKind(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String type = current.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (type.contains("ratelimit") || type.contains("toomanyrequests")) {
                return new FailureKind("rate-limit", "rate-limit");
            }
            if (current instanceof TimeoutException || type.contains("timeout")) {
                return new FailureKind("timeout", "timeout");
            }
            current = current.getCause();
        }
        return new FailureKind("failure", "exception");
    }

    private static void appendProviderAttempt(
            String requestHash,
            String optionsHash,
            ProviderIdentity identity,
            int attemptOrdinal,
            String outcome,
            String reason,
            long startedAtNanos,
            int returnedCount,
            boolean responseObserved) {
        try {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - startedAtNanos));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("requestHash", requestHash);
            row.put("optionsHash", optionsHash);
            row.put("providerIdHash", identity.hash());
            row.put("providerIdLength", identity.length());
            row.put("attemptOrdinal", attemptOrdinal);
            row.put("sequence", TraceStore.nextSequence(PROVIDER_ATTEMPTS_KEY));
            row.put("outcome", outcome);
            row.put("reason", reason);
            row.put("elapsedMs", Math.min(MAX_ELAPSED_MS, elapsedMs));
            row.put("returnedCount", Math.max(0, returnedCount));
            row.put("providerAttemptObserved", true);
            row.put("responseObserved", responseObserved);
            row.put("probeOnly", true);
            row.put("verificationGatePassed", false);
            TraceStore.append(PROVIDER_ATTEMPTS_KEY, row);
        } catch (Throwable diagnosticsFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[AWX][agent][acme-gateway] diagnostics skipped stage=provider_attempt_trace errorType={0}",
                    diagnosticsFailure.getClass().getSimpleName());
        }
    }

    private static String safeHash(String value, String fallback) {
        try {
            String hash = SafeRedactor.hashValue(value);
            if (hash != null) {
                return hash;
            }
            hash = SafeRedactor.hashValue(fallback);
            return hash == null ? "hash:unavailable" : hash;
        } catch (Throwable hashFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[AWX][agent][acme-gateway] diagnostics skipped stage=hash_projection errorType={0}",
                    hashFailure.getClass().getSimpleName());
            return "hash:unavailable";
        }
    }

    private static void traceSuppressed(ProviderIdentity identity, Throwable error) {
        TraceStore.put("agent.acmeGateway.providerFailure", true);
        TraceStore.put("agent.acmeGateway.providerFailure.stage", "provider.search");
        TraceStore.put("agent.acmeGateway.providerFailure.errorClass",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.inc("agent.acmeGateway.providerFailure.count");
        TraceStore.put("agent.acmeGateway.providerFailure.providerIdHash", identity.hash());
        TraceStore.put("agent.acmeGateway.providerFailure.providerIdLength", identity.length());
    }

    private static void traceRankingSuppressed(Throwable error) {
        TraceStore.put("agent.acmeGateway.rankingFailure", true);
        TraceStore.put("agent.acmeGateway.rankingFailure.stage", "ranking.fuseAndRank");
        TraceStore.put("agent.acmeGateway.rankingFailure.errorClass",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.inc("agent.acmeGateway.rankingFailure.count");
    }

    private static void traceResult(String reason, int returnedCount) {
        try {
            TraceStore.put("agent.acmeGateway.result.reason", reason);
            TraceStore.put("agent.acmeGateway.result.returnedCount", Math.max(0, returnedCount));
            TraceStore.put("agent.acmeGateway.result.probeOnly", true);
            TraceStore.put("agent.acmeGateway.result.verificationGatePassed", false);
        } catch (Throwable diagnosticsFailure) {
            LOG.log(System.Logger.Level.DEBUG,
                    "[AWX][agent][acme-gateway] diagnostics skipped stage=result_trace errorType={0}",
                    diagnosticsFailure.getClass().getSimpleName());
        }
    }

    private record ProviderIdentity(String hash, int length) {}

    private record FailureKind(String outcome, String reason) {}
}
