package com.example.lms.config;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import com.example.lms.service.rag.RelevanceScoringService;
import com.example.lms.service.rag.auth.DomainProfileLoader;
import com.example.lms.service.rag.extract.PageContentScraper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Collectors;

@Configuration
public class GptSearchConfig {

    @Bean
    public SearchDecisionService searchDecisionService() {
        return new SearchDecisionService();
    }

    @Bean
    public List<WebSearchProvider> webSearchProviders(ObjectProvider<WebSearchProvider> providers) {
        return providers.orderedStream().collect(Collectors.toList());
    }

    @Bean
    public AdaptiveWebSearchHandler adaptiveWebSearchHandler(
            SearchDecisionService decisionService,
            List<WebSearchProvider> webSearchProviders,
            PageContentScraper scraper,
            RelevanceScoringService relevanceScoringService,
            DomainProfileLoader domainProfileLoader) {
        return new AdaptiveWebSearchHandler(
                decisionService,
                webSearchProviders,
                scraper,
                relevanceScoringService,
                domainProfileLoader);
    }
}
