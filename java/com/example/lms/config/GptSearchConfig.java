package com.example.lms.config;

import com.example.lms.gptsearch.decision.SearchDecisionService;
import com.example.lms.gptsearch.web.WebSearchProvider;
import com.example.lms.integration.handlers.AdaptiveWebSearchHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;
import java.util.stream.Collectors;




/**
 * GPT Web Search 통합 설정.
 * - SearchDecisionService: 검색 여부·전략 결정
 * - webSearchProviders: 컨텍스트에 등록된 모든 WebSearchProvider를 선택적으로 수집
 * - AdaptiveWebSearchHandler: 결정 서비스 + 공급자 목록 주입
 *
 * 주의:
 * - Bing/Tavily/SerpAPI/GoogleCSE/Naver 등 구체 Provider 빈은 @Component 또는
 *   별도 @Bean/@ConditionalOnProperty 등으로 정의돼 있어야 수집됩니다.
 * - 어떤 Provider가 없어도 여기서 오류가 나지 않도록 선택 수집(ObjectProvider)만 사용합니다.
 */
@Configuration
public class GptSearchConfig {

    @Bean
    public SearchDecisionService searchDecisionService() {
        return new SearchDecisionService();
    }

    /**
     * 컨테이너에 등록된 모든 WebSearchProvider를 "있는 것만" 모아 반환합니다.
     * (예: NaverProvider만 있어도 OK, BingProvider가 없어도 에러 없음)
     */
    @Bean
    public List<WebSearchProvider> webSearchProviders(ObjectProvider<WebSearchProvider> providers) {
        return providers.orderedStream().collect(Collectors.toList());
    }

    @Bean
    public AdaptiveWebSearchHandler adaptiveWebSearchHandler(
            SearchDecisionService decisionService,
            List<WebSearchProvider> webSearchProviders,
            com.example.lms.service.rag.extract.PageContentScraper scraper,
            com.example.lms.service.rag.RelevanceScoringService relevanceScoringService,
            com.example.lms.service.rag.auth.DomainProfileLoader domainProfileLoader
    ) {
        return new AdaptiveWebSearchHandler(decisionService, webSearchProviders, scraper, relevanceScoringService, domainProfileLoader);
    }
}