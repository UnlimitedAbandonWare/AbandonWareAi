// src/main/java/com/example/lms/config/WebClientConfig.java
package com.example.lms.config;

import com.example.lms.api.KakaoProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;


import org.springframework.context.annotation.Primary;   // ⭐ import 추가


@RequiredArgsConstructor
@Configuration
public class WebClientConfig {

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
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String openAiApiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String openAiBaseUrl;

    @Value("${openai.http.max-in-mem-mb:30}")
    private int openAiMaxInMemMb;

    @Value("${openai.http.timeout-sec:120}")
    private int openAiTimeoutSec;

    /** Google Translate API용 WebClient - 대표 빈 */
    @Bean(name = "googleTranslateWebClient")
    @Primary                                // ⭐ 대표(우선순위) 표시
    public WebClient googleTranslateWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl("${NAVER_SEARCH_API_BASE_URL:https://openapi.naver.com}")
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
                .baseUrl("${NAVER_SEARCH_API_BASE_URL:https://openapi.naver.com}")
                .build();
    }
    
    // ---------------------------------------------------------------------
    // [ADD] OpenAI 전용 WebClient (긴 러닝타임 + 대용량 안전)
    /**
     * Provide a dedicated WebClient for OpenAI calls.  This client is
     * configured with a generous response timeout and enlarged in-memory
     * buffer to accommodate the long running image and chat completions
     * endpoints.  Compression is enabled to reduce payload sizes over the
     * wire and a default base URL is set to the OpenAI API host.  The global
     * WebClient bean remains untouched and continues to enforce shorter
     * timeouts for internal services.
     */
    @Bean(name = "openaiWebClient")
    public WebClient openaiWebClient() {
        /*
         * Create a dedicated WebClient for OpenAI requests.  The timeout
         * and in-memory buffer sizes are configurable via the
         * `openai.http.timeout-sec` and `openai.http.max-in-mem-mb` properties.
         * A bearer token is automatically added when the API key is
         * provided.  A connect timeout is also configured using the same
         * timeout value to avoid hung connections.
         */
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .responseTimeout(Duration.ofSeconds(openAiTimeoutSec))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, openAiTimeoutSec * 1000)
                .doOnConnected(conn -> conn.addHandlerLast(new ReadTimeoutHandler(openAiTimeoutSec)))
                .compress(true);
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(openAiMaxInMemMb * 1024 * 1024))
                .build();
        return WebClient.builder()
                .baseUrl(openAiBaseUrl)
                .defaultHeaders(h -> {
                    if (openAiApiKey != null && !openAiApiKey.isBlank()) {
                        h.setBearerAuth(openAiApiKey);
                    }
                })
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}