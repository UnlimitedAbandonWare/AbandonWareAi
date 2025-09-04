package com.example.lms.config;

import com.example.lms.plugin.image.GeminiImagePort;
import com.example.lms.plugin.image.GeminiImageProperties;
import com.example.lms.plugin.image.NoopGeminiImageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Auto-configuration class for the Gemini image integration.  When a
 * {@code gemini.image} configuration is present but no explicit
 * {@link WebClient} or {@link GeminiImagePort} beans are defined, this
 * configuration will register sensible defaults: a WebClient pointing
 * at the configured endpoint with the API key set as a header, and a
 * {@link NoopGeminiImageService} to satisfy the port contract.  Users
 * can provide their own {@code GeminiImagePort} bean to override the
 * no-op behaviour.
 */
@Configuration
@EnableConfigurationProperties(GeminiImageProperties.class)
public class GeminiImageAutoConfiguration {

    /**
     * Creates a {@link WebClient} named {@code geminiWebClient} if no
     * bean with that name is already registered.  The client will be
     * configured with the base URL and API key from
     * {@link GeminiImageProperties}.  Because the API key header name
     * differs from standard authentication headers, it is set as
     * {@code x-goog-api-key}.
     *
     * @param props the Gemini image properties bound from configuration
     * @return a configured WebClient
     */
    @Bean(name = "geminiWebClient")
    @ConditionalOnMissingBean(name = "geminiWebClient")
    public WebClient geminiWebClient(GeminiImageProperties props) {
        WebClient.Builder builder = WebClient.builder().baseUrl(props.getEndpoint());
        String key = props.getApiKey();
        if (key != null && !key.isBlank()) {
            builder = builder.defaultHeader("x-goog-api-key", key);
        }
        return builder.build();
    }

    /**
     * Provides a no-op {@link GeminiImagePort} when no other
     * implementation is available.  This bean prevents wiring errors
     * when the integration is disabled and ensures that controllers
     * depending on the port still start up correctly.
     *
     * @return a no-operation implementation of GeminiImagePort
     */
    @Bean
    @ConditionalOnMissingBean(GeminiImagePort.class)
    public GeminiImagePort noopGeminiImageService() {
        return new NoopGeminiImageService();
    }
}