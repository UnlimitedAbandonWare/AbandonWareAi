package com.example.lms.service.vector;

import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpstashVectorStoreAdapterFilterTest {

    @Test
    void translatesConcreteLangChainFiltersWithoutReflection() {
        UpstashVectorStoreAdapter adapter = new UpstashVectorStoreAdapter(WebClient.builder().build());

        String rendered = adapter.toUpstashFilter(new And(
                new IsEqualTo("sid", "session-1"),
                new IsIn("tag", List.of("rag", "memory"))));

        assertTrue(rendered.startsWith("(sid = 'session-1' AND tag IN ("));
        assertTrue(rendered.contains("'rag'"));
        assertTrue(rendered.contains("'memory'"));
        assertTrue(rendered.endsWith(")"));
    }

    @Test
    void unknownFilterTypeFailsClosedToNoExpression() {
        UpstashVectorStoreAdapter adapter = new UpstashVectorStoreAdapter(WebClient.builder().build());

        String rendered = adapter.toUpstashFilter(new Filter() {
            @Override
            public boolean test(Object object) {
                return true;
            }
        });

        assertEquals("", rendered);
    }

    @Test
    void placeholderApiKeyIsNotConfigured() {
        UpstashVectorStoreAdapter adapter = new UpstashVectorStoreAdapter(WebClient.builder().build());
        ReflectionTestUtils.setField(adapter, "restUrl", "https://upstash.example.invalid");
        ReflectionTestUtils.setField(adapter, "apiKey", "sk-local");

        assertTrue(!adapter.isConfigured());
    }
}
