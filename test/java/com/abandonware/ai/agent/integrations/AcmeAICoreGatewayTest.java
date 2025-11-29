package com.abandonware.ai.agent.integrations;

import com.acme.aicore.search.WebSearchProvider;
import com.acme.aicore.ranking.WeightedRrfRanking;
import com.acme.aicore.ranking.Reranker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.*;




import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AcmeAICoreGatewayTest {

    WebSearchProvider p1 = mock(WebSearchProvider.class);
    WebSearchProvider p2 = mock(WebSearchProvider.class);
    WeightedRrfRanking rrf = mock(WeightedRrfRanking.class);
    Reranker reranker = mock(Reranker.class);

    AcmeAICoreGateway gw;

    @BeforeEach
    void setUp() {
        gw = new AcmeAICoreGateway(List.of(p1, p2), rrf, reranker);
        gw.setRerankerEnabled(false);
    }

    @Test
    void gateway_fanoutAndRrf() {
        when(p1.search("llm","ko")).thenReturn(List.of(doc("A",0.8)));
        when(p2.search("llm","ko")).thenReturn(List.of(doc("B",0.7)));
        when(rrf.rank(anyList())).thenAnswer(inv -> inv.getArgument(0));

        var out = gw.searchAndRank("llm", 5, "ko");
        verify(p1).search("llm","ko");
        verify(p2).search("llm","ko");
        verify(rrf).rank(anyList());
        assertThat(out).hasSize(2);
    }

    @Test
    void reranker_toggleOff_shouldNotCall() {
        gw.setRerankerEnabled(false);
        gw.searchAndRank("q", 3, "ko");
        verifyNoInteractions(reranker);
    }

    @Test
    void reranker_toggleOn_shouldCall() {
        when(rrf.rank(anyList())).thenReturn(List.of(doc("A", 0.8), doc("B", 0.7)));
        gw.setRerankerEnabled(true);
        gw.searchAndRank("q", 2, "ko");
        verify(reranker).rerank(eq("q"), anyList());
    }

    private static Map<String,Object> doc(String title, double score){
        return new HashMap<>(Map.of("title", title, "score", score));
    }
}