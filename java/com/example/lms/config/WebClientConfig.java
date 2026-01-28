// src/main/java/com/example/lms/config/WebClientConfig.java
package com.example.lms.config;

import com.example.lms.api.KakaoProperties;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
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
    @Value("${openai.api.key:${OPENAI_API_KEY:}}")
    private String openAiApiKey;

    @Value("${openai.base-url:https://api.openai.com}")
    private String openAiBaseUrl;

    @Value("${openai.http.max-in-mem-mb:30}")
    private int openAiMaxInMemMb;

    @Value("${openai.http.timeout-sec:120}")
    private int openAiTimeoutSec;

    // ---------------------------------------------------------------------
    // External API base URLs
    // NOTE: Spring property placeholders (e.g. ${...}) are resolved when
    // injected via @Value / @ConfigurationProperties. Passing the placeholder
    // string literal directly into WebClient.baseUrl("${...}") will NOT be
    // resolved and can break URI parsing at runtime.
    @Value("${naver.search.api-base-url:${NAVER_SEARCH_API_BASE_URL:https://openapi.naver.com}}")
    private String naverSearchApiBaseUrl;

    @Value("${google.translate.base-url:https://translation.googleapis.com}")
    private String googleTranslateBaseUrl;

    /**
     * 기본 WebClient (baseUrl 없음).
     *
     * <p>Qualifier 없이 WebClient를 주입받는 컴포넌트가 있을 때, 특정 baseUrl을
     * @Primary로 지정해버리면 의도치 않게 상대 경로 호출이 꼬일 수 있습니다.
     * 그래서 기본 WebClient는 "중립"으로 두고, 외부 API용 클라이언트는
     * @Qualifier로 명시적으로 주입받도록 합니다.</p>
     */
    @Bean
    @Primary
    public WebClient defaultWebClient(WebClient.Builder builder) {
        return builder.build();
    }

    /** Google Translate API용 WebClient */
    @Bean(name = "googleTranslateWebClient")
    public WebClient googleTranslateWebClient(WebClient.Builder builder) {
        return builder
                .baseUrl(googleTranslateBaseUrl)
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
    private static ExchangeFilterFunction correlationHeadersFilter() {
        final String HDR_REQUEST_ID = "X-Request-Id";
        final String HDR_SESSION_ID = "X-Session-Id";

        return (request, next) -> {
            // If already present (non-blank), avoid duplicating headers.
            String existingRid = request.headers().getFirst(HDR_REQUEST_ID);
            String existingSid = request.headers().getFirst(HDR_SESSION_ID);
            boolean hasRid = existingRid != null && !existingRid.isBlank();
            boolean hasSid = existingSid != null && !existingSid.isBlank();
            if (hasRid && hasSid) {
                return next.exchange(request);
            }

            // Prefer MDC, but fall back to TraceStore to survive async/reactive boundaries.
            String traceFromStore = null;
            String sidFromStore = null;
            try {
                Object t = com.example.lms.search.TraceStore.get("trace.id");
                if (t != null) traceFromStore = String.valueOf(t);
                Object s = com.example.lms.search.TraceStore.get("sid");
                if (s != null) sidFromStore = String.valueOf(s);
            } catch (Throwable ignore) {
                // fail-soft
            }

            String rid = hasRid ? existingRid : firstNonBlank(MDC.get("x-request-id"), MDC.get("trace"), traceFromStore);
            String sid = hasSid ? existingSid : firstNonBlank(MDC.get("sessionId"), MDC.get("sid"), sidFromStore);

            if ((rid == null || rid.isBlank()) && (sid == null || sid.isBlank())) {
                return next.exchange(request);
            }

            ClientRequest.Builder b = ClientRequest.from(request);
            b.headers(h -> {
                // Replace blank values (if any) to avoid downstream choosing an empty first value.
                if (!hasRid && rid != null && !rid.isBlank()) {
                    h.remove(HDR_REQUEST_ID);
                    h.add(HDR_REQUEST_ID, rid);
                }
                if (!hasSid && sid != null && !sid.isBlank()) {
                    h.remove(HDR_SESSION_ID);
                    h.add(HDR_SESSION_ID, sid);
                }
            });
            return next.exchange(b.build());
        };
    }

    private static ExchangeFilterFunction logErrorBodyFilter(int maxChars) {
        return (request, next) -> next.exchange(request).flatMap(resp -> {
            if (!resp.statusCode().isError()) {
                return Mono.just(resp);
            }
            return resp.bodyToMono(String.class)
                    .defaultIfEmpty("")
                    .map(body -> {
                        String b = body;
                        if (b.length() > maxChars) {
                            b = b.substring(0, maxChars) + "...(truncated)";
                        }
                        log.warn("[WebClient] {} {} -> {} body={}",
                                request.method(), request.url(), resp.statusCode().value(), b);
                        return ClientResponse.create(resp.statusCode())
                                .headers(h -> h.addAll(resp.headers().asHttpHeaders()))
                                .body(body)
                                .build();
                    });
        });
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return null;
        for (String x : xs) {
            if (x != null && !x.isBlank()) return x;
        }
        return null;
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
                .baseUrl(naverSearchApiBaseUrl)
                .filter(correlationHeadersFilter())
                .filter(logErrorBodyFilter(2048))
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
    public WebClient openaiWebClient(WebClient.Builder builder) {
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
        return builder
                .baseUrl(openAiBaseUrl)
                .defaultHeaders(h -> {
                    if (openAiApiKey != null && !openAiApiKey.isBlank()) {
                        h.setBearerAuth(openAiApiKey);
                    }
                })
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(correlationHeadersFilter())
                .filter(logErrorBodyFilter(4096))
                .build();
    }
}