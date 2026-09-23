package com.abandonware.ai.agent.service.rag.bm25;

import com.abandonware.ai.agent.config.Bm25Props;
import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Bm25LocalRetrieverTraceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void searchFailureReturnsEmptyWithRedactedBreadcrumb() {
        Bm25Props props = new Bm25Props();
        props.setEnabled(true);
        Bm25IndexHolder holder = mock(Bm25IndexHolder.class);
        when(holder.searcher()).thenThrow(new IllegalStateException("private bm25 failure"));

        assertThat(new Bm25LocalRetriever(props, holder).retrieve("private bm25 query", 3)).isEmpty();

        assertThat(TraceStore.get("agent.bm25.retrieve.suppressed")).isEqualTo(Boolean.TRUE);
        assertThat(TraceStore.get("agent.bm25.retrieve.suppressed.stage")).isEqualTo("retrieve");
        assertThat(TraceStore.get("agent.bm25.retrieve.suppressed.errorType")).isEqualTo("IllegalStateException");
        assertThat(TraceStore.get("agent.bm25.retrieve.suppressed.queryHash")).asString().startsWith("hash:");
        assertThat(TraceStore.get("agent.bm25.retrieve.suppressed.queryLength")).isEqualTo(18);
        assertThat(String.valueOf(TraceStore.getAll())).doesNotContain(
                "private bm25 query",
                "private bm25 failure");
    }
}
