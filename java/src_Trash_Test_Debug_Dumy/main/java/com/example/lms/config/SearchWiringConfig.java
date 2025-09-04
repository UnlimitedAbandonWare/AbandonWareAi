package com.example.lms.config;

import com.example.lms.gptsearch.web.MultiWebSearch;
import com.example.lms.query.config.AiQueryProperties;
import com.example.lms.service.rag.HybridRetriever;
import com.example.lms.service.rag.MoeHybridRetriever;
import com.example.lms.service.rag.impl.HybridRetrieverImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 검색 관련 배선을 정의하는 구성 클래스입니다. 기본 하이브리드 체인과 MOE 래퍼를 주입하고,
 * MultiWebSearch 빈을 초기화합니다.
 */
@Configuration
public class SearchWiringConfig {

    /**
     * 기존 코어 체인을 명시적으로 노출합니다. MoeHybridRetriever는 이 빈을 래핑합니다.
     */
    @Bean(name = "baseHybridRetriever")
    public HybridRetriever baseHybridRetriever(HybridRetrieverImpl impl) {
        return impl;
    }

    /**
     * 외부에 노출되는 기본 HybridRetriever는 MoeHybridRetriever입니다. Primary로 지정하여 우선 주입합니다.
     */
    @Primary
    @Bean
    public HybridRetriever hybridRetrieverPrimary(MoeHybridRetriever moe) {
        return moe;
    }

    /**
     * MultiWebSearch 빈을 생성합니다. Provider 목록과 ai.query 프로퍼티를 주입받습니다.
     */
    @Bean
    public MultiWebSearch multiWebSearch(
            java.util.List<MultiWebSearch.Provider> providers,
            AiQueryProperties props
    ) {
        return new MultiWebSearch(providers, props);
    }
}