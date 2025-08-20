package com.example.lms.config;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.gptsearch.web.impl.BingProvider;
import com.example.lms.gptsearch.web.impl.TavilyProvider;
import com.example.lms.gptsearch.web.impl.GoogleCseProvider;
import com.example.lms.gptsearch.web.impl.SerpApiProvider;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/**
 * Configuration class for the GPT Web Search integration.  Declares beans
 * for the search decision engine, web providers and the adaptive search
 * handler.  Providers that require API keys can be conditionally
 * instantiated using environment variables or application properties.
 */
@Configuration
public class GptSearchConfig {

    @Bean
    public SearchDecisionService searchDecisionService() {
        return new SearchDecisionService();
    }

    @Bean
    public List<WebSearchProvider> webSearchProviders() {
        // Assemble the default provider list.  Real implementations would
        // check for configured API keys and replace missing ones with the
        // MockProvider.
        return java.util.List.of(
                new BingProvider(),
                new TavilyProvider(),
                new GoogleCseProvider(),
                new SerpApiProvider()
        );
    }

    @Bean
    public AdaptiveWebSearchHandler adaptiveWebSearchHandler(SearchDecisionService decisionService,
                                                             List<WebSearchProvider> webSearchProviders) {
        return new AdaptiveWebSearchHandler(decisionService, webSearchProviders);
    }
}