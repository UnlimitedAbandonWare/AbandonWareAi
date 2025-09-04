package com.example.lms.smoke;

import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Collections;
import java.util.List;

import jakarta.annotation.Resource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QueryFlowSmokeIT {

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        ContentRetriever analyzeWebSearchRetriever() {
            return new ContentRetriever() {
                @Override
                public List<Content> retrieve(Query query) {
                    return Collections.emptyList();
                }
            };
        }
    }

    @Resource(name = "perspectiveAnalyzeRetriever")
    ContentRetriever perspective;

    @Resource(name = "subQueryAnalyzeRetriever")
    ContentRetriever subQuery;

    @Test
    void perspective_retrieve_shouldNotThrow_andReturnList() {
        List<Content> res = perspective.retrieve(new Query("ping"));
        Assertions.assertNotNull(res);
    }

    @Test
    void subQuery_retrieve_shouldNotThrow_andReturnList() {
        List<Content> res = subQuery.retrieve(new Query("ping"));
        Assertions.assertNotNull(res);
    }
}
