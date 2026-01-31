package com.example.lms.config;

import com.acme.aicore.adapters.search.CachedWebSearch;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

/**
 * Bridges the "com.acme.aicore.adapters.search" web-search adapter components into the main Spring context.
 *
 * <p>Spring Boot's default component scan is rooted at {@code com.example.lms}, so beans under
 * {@code com.acme.aicore.adapters.search} will not be discovered unless explicitly scanned.</p>
 *
 * <p>Important: {@link CachedWebSearch} is excluded from scanning because {@link LangChainConfig}
 * registers it via a {@code @Bean} method. Scanning both would create duplicate bean definitions.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "adapter.acme-websearch", name = "enabled", havingValue = "true", matchIfMissing = true)
@ComponentScan(
        basePackages = {"com.acme.aicore.adapters.search"},
        excludeFilters = {
                @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = CachedWebSearch.class)
        }
)
public class AcmeWebSearchAdapterConfig {
}
