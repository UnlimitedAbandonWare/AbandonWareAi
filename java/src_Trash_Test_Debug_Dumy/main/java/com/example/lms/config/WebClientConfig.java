// src/main/java/com/example/lms/config/WebClientConfig.java
package com.example.lms.config;

import com.example.lms.api.KakaoProperties;
import com.example.lms.plugin.image.GeminiImageProperties;
import io.netty.channel.ChannelOption;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider; // ⭐ import 추가
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils; // ⭐ import 추가
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.netty.http.client.HttpClient;

// Added for fail-fast and auth checks
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import org.springframework.util.StringUtils;
@RequiredArgsConstructor
@Configuration
public class WebClientConfig {

    /**
     * Logger used for reporting missing API keys and header issues.  The
     * WebClientConfig class does not currently define a logger; adding one
     * here enables fail‑fast diagnostics without altering existing behaviour
     * elsewhere in the configuration.
     */
    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    private final KakaoProperties kakaoProps;

    // -----------------------------------------------------------------------------
    // OpenAI configuration properties.  When provided via application.yml or
    // environment variables these values control the behaviour of the dedicated
    // OpenAI WebClient.  The defaults match the existing configuration but
    // can be overridden at runtime.  openAiApiKey resolves OPENAI_API_KEY when
    // undefined to maintain backward compatibility with earlier deployments.
    // Resolve the API key for OpenAI from multiple sources. Prefer the
    // `openai.api.key` property and fall back to OPENAI_API_KEY.  Do not
    // include other vendor keys (e.g. GROQ_API_KEY) in the fallback chain to
    // avoid using incompatible credentials.
    // Resolve the OpenAI API key strictly from the `openai.api.key` property. Do not
    // fall back to any environment variables. External credentials must be
    // supplied via configuration files only.
    @Value("${openai.api.key:${openai.image.api-key:}}")
    private String openAiApiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String openAiBaseUrl;

    @Value("${openai.http.max-in-mem-mb:30}")
    private int openAiMaxInMemMb;

    @Value("${openai.http.timeout-sec:120}")
    private int openAiTimeoutSec;

    /** Google Translate API용 WebClient – 대표 빈 */
    @Bean(name = "googleTranslateWebClient")
    @Primary
    public WebClient googleTranslateWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("https://translation.googleapis.com")
                .build();
    }

    /* ---------- Kakao 공통 커넥터 ---------- */
    private ReactorClientHttpConnector kakaoConnector() {
        HttpClient httpClient = HttpClient.create()
                .compress(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        kakaoProps.getWebclientConnectTimeoutMs())
                .responseTimeout(
                        Duration.ofMillis(kakaoProps.getWebclientReadTimeoutMs()));

        return new ReactorClientHttpConnector(httpClient);
    }

    /** Kakao REST API용 WebClient (친구/메모) */
    @Bean(name = "kakaoWebClient")
    public WebClient kakaoWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(kakaoConnector())
                .baseUrl(kakaoProps.getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakaoProps.getAdminKey())
                .build();
    }

    /** Kakao Biz API용 WebClient (알림톡) */
    @Bean(name = "kakaoBizWebClient")
    public WebClient kakaoBizWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(kakaoConnector())
                .baseUrl(kakaoProps.getApiBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "KakaoAK " + kakaoProps.getAdminKey())
                .build();
    }

    /**
     * NAVER Open API 전용 WebClient.  검색 서비스는 이 빈을 주입받아
     * 기본 URL과 공용 커넥터를 재사용한다.  추가 헤더는 호출자에서 설정한다.
     */
    @Bean(name = "naverWebClient")
    public WebClient naverWebClient(WebClient.Builder builder) {
        // Use the default builder here.  If a shared ReactorClientHttpConnector
        // is desired it can be injected similarly to the Kakao connector.  The
        // base URL points to the Naver OpenAPI host.
        return builder
                .baseUrl("https://openapi.naver.com")
                .build();
    }

    // ---------------------------------------------------------------------
    // [ADD] OpenAI 전용 WebClient (긴 러닝타임 + 대용량 안전)
    /**
     * Provide a dedicated WebClient for OpenAI calls.  This client is
     * configured with a generous response timeout and enlarged in‑memory
     * buffer to accommodate the long running image and chat completions
     * endpoints.  Compression is enabled to reduce payload sizes over the
     * wire and a default base URL is set to the OpenAI API host.  The global
     * WebClient bean remains untouched and continues to enforce shorter
     * timeouts for internal services.
     */
    @Bean(name = "openaiWebClient")
    public WebClient openaiWebClient() {
        // Fail fast when the API key is absent.  Without a valid key the
        // downstream OpenAI calls will always return 401 (Unauthorized) and
        // confuse the caller.  Throwing an exception here prevents the
        // application from starting with a misconfigured state and surfaces
        // a clear error message in the logs.
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            log.error("OPENAI_API_KEY is empty; refusing to create openaiWebClient");
            throw new IllegalStateException("OPENAI_API_KEY_MISSING");
        }
        /*
         * Create a dedicated WebClient for OpenAI requests.  The timeout
         * and in‑memory buffer sizes are configurable via the
         * `openai.http.timeout-sec` and `openai.http.max-in-mem-mb` properties.
         * A bearer token is automatically added when the API key is
         * provided.  A connect timeout is also configured using the same
         * timeout value to avoid hung connections.
         */
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .responseTimeout(Duration.ofSeconds(openAiTimeoutSec))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, openAiTimeoutSec * 1000)
                .compress(true);
        // Always allocate at least 64MB for the in‑memory buffer.  If a larger value
        // is configured via the openAiMaxInMemMb property, honour that instead.
        int effectiveMemMb = Math.max(openAiMaxInMemMb, 64);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(effectiveMemMb * 1024 * 1024))
                .build();
        // Build the WebClient with a fixed base URL and default headers.
        // The bearer token is applied unconditionally here because the API key
        // has already been validated.  The Accept header is set to
        // application/json to prevent unexpected content type negotiation.
        WebClient.Builder builder = WebClient.builder()
                .baseUrl(openAiBaseUrl)
                .defaultHeaders(h -> {
                    h.setBearerAuth(openAiApiKey);
                    h.set(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
                })
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        // Insert a filter that validates the presence of the Authorization header
        // immediately before the request is sent.  If another component
        // inadvertently clears the header this check will catch it and fail
        // fast, avoiding spurious 401/403 responses from the OpenAI API.
        builder = builder.filter((request, next) -> {
            if (!request.headers().containsKey(HttpHeaders.AUTHORIZATION)) {
                log.warn("openaiWebClient: Authorization header missing just before send (fail fast)");
                return Mono.error(new IllegalStateException("OPENAI_AUTH_HEADER_MISSING"));
            }
            return next.exchange(request);
        });
        return builder.build();
    }

    // --- Gemini API 전용 WebClient (ENV fallback 없음) ---
    /**
     * Provide a dedicated WebClient for Gemini image calls.  Unlike the
     * OpenAI WebClient, this bean does not perform any environment variable
     * fallback for API keys.  The base URL and API key are derived from
     * {@link GeminiImageProperties}.  When no
     * properties are defined the defaults (endpoint only) are used.  The
     * API key, when present, is sent via the {@code x-goog-api-key}
     * header as required by the Gemini API.  A simple builder is used
     * without any extended timeouts; callers should apply their own
     * timeout handling when invoking this WebClient.
     */
    @Bean(name = "geminiWebClient")
    public WebClient geminiWebClient(ObjectProvider<GeminiImageProperties> propsProvider) {
        var props = propsProvider.getIfAvailable();
        String baseUrl = (props != null && props.getEndpoint() != null)
                ? props.getEndpoint()
                : "https://generativelanguage.googleapis.com";
        WebClient.Builder builder = WebClient.builder().baseUrl(baseUrl);
        if (props != null && props.getApiKey() != null && !props.getApiKey().isBlank()) {
            builder.defaultHeader("x-goog-api-key", props.getApiKey());
        }
        return builder.build();
    }

}