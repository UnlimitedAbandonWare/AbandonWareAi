package com.abandonware.ai.agent.integrations;

import com.acme.aicore.adapters.ranking.WeightedRrfRanking;
import com.acme.aicore.domain.model.SearchBundle;
import com.acme.aicore.domain.model.WebSearchQuery;
import com.acme.aicore.domain.ports.WebSearchProvider;
import com.example.lms.routing.ApiRoutingPolicySnapshot;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class AcmeAICoreGatewayPolicyTest {
    @AfterEach void clear() { TraceStore.clear(); TraceContext.cleanupCurrentThread(); }

    @Test void yamlSortsBeforeCallsAndStopsAfterEvidenceWithoutFanout() {
        List<String> calls = new ArrayList<>();
        var gateway = gateway(calls, true, "serpapi", "tavily", "naver", "brave", "unknown");
        try (var ctx = TraceContext.attach("fixture", "search-one")) {
            assertThat(gateway.searchAndRank("docs", 3, "en")).hasSize(1);
            assertThat(calls).containsExactly("brave");
        }
    }

    @Test void deniedWebExhaustedSharedBudgetAndMissingCredentialMakeNoProviderCalls() {
        List<String> calls = new ArrayList<>();
        var gateway = gateway(calls, true, "brave");
        try (var ctx = TraceContext.attach("fixture", "denied")) {
            ctx.setFlag("allowWeb", false);
            assertThat(gateway.searchAndRank("docs", 3, "en")).isEmpty();
            ctx.setFlag("allowWeb", true);
            ctx.setFlag("agent.webSearch.maxProviderCalls", 0);
            assertThat(gateway.searchAndRank("docs", 3, "en")).isEmpty();
            assertThat(calls).isEmpty();
        }
        try (var ctx = TraceContext.attach("fixture", "no-credential")) {
            assertThat(gateway(calls, false, "brave").searchAndRank("docs", 3, "en")).isEmpty();
            assertThat(calls).isEmpty();
        }
    }

    @Test void sharedAttemptCapSurvivesRepeatedSearchesInTheSameRequest() {
        List<String> calls = new ArrayList<>();
        var gateway = gateway(calls, true, "brave");
        try (var ctx = TraceContext.attach("fixture", "budget")) {
            gateway.searchAndRank("one", 3, "en");
            gateway.searchAndRank("two", 3, "en");
            assertThat(gateway.searchAndRank("three", 3, "en")).isEmpty();
            assertThat(calls).hasSize(2);
        }
    }

    private AcmeAICoreGateway gateway(List<String> calls, boolean credentials, String... ids) {
        List<WebSearchProvider> providers = Arrays.stream(ids).map(id -> new WebSearchProvider() {
            public String id() { return id; }
            public Mono<SearchBundle> search(WebSearchQuery query) {
                calls.add(id);
                return Mono.just(new SearchBundle("web", List.of(new SearchBundle.Doc(
                        id, "Synthetic docs", "Synthetic evidence", "https://docs.example.test/" + id, null))));
            }
        }).map(WebSearchProvider.class::cast).toList();
        return new AcmeAICoreGateway(providers, new WeightedRrfRanking(),
                new ApiRoutingPolicySnapshot(new MockEnvironment()), id -> credentials);
    }
}
