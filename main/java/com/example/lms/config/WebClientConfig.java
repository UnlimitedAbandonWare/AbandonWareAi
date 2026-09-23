// src/main/java/com/example/lms/config/WebClientConfig.java
package com.example.lms.config;

import com.example.lms.api.MessageGatewayProperties;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
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
import java.net.URI;
import java.time.Duration;


import org.springframework.context.annotation.Primary;   // 狩?import 異붽?


@RequiredArgsConstructor
@Configuration
public class WebClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WebClientConfig.class);

    private final MessageGatewayProperties messageGatewayProps;

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


    /**
     * 湲곕낯 WebClient (baseUrl ?놁쓬).
     *
     * <p>Qualifier ?놁씠 WebClient瑜?二쇱엯諛쏅뒗 而댄룷?뚰듃媛 ?덉쓣 ?? ?뱀젙 baseUrl??     * @Primary濡?吏?뺥빐踰꾨━硫??섎룄移??딄쾶 ?곷? 寃쎈줈 ?몄텧??瑗ъ씪 ???덉뒿?덈떎.
     * 洹몃옒??湲곕낯 WebClient??"以묐┰"?쇰줈 ?먭퀬, ?몃? API???대씪?댁뼵?몃뒗
     * @Qualifier濡?紐낆떆?곸쑝濡?二쇱엯諛쏅룄濡??⑸땲??</p>
     */
    @Bean
    @Primary
    public WebClient defaultWebClient(WebClient.Builder builder) {
        return builder.build();
    }


    /* ---------- Channel 怨듯넻 而ㅻ꽖??---------- */
    private ReactorClientHttpConnector messageGatewayConnector() {
        HttpClient httpClient = HttpClient.create()
                .compress(true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        messageGatewayProps.getWebclientConnectTimeoutMs())
                .responseTimeout(
                        Duration.ofMillis(messageGatewayProps.getWebclientReadTimeoutMs()));

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
                traceSuppressed("webClient.correlationTraceStore", ignore);
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
                        URI uri = request.url();
                        String rawQuery = uri == null ? null : uri.getRawQuery();
                        String requestId = request.headers().getFirst("X-Request-Id");
                        String sessionId = request.headers().getFirst("X-Session-Id");
                        log.warn("[AWX][trace][webclient] method={} target={} hasQuery={} queryHash={} status={} bodyHash={} bodyLength={} xRequestIdPresent={} xSessionIdPresent={}",
                                request.method(),
                                hostPath(uri),
                                rawQuery != null && !rawQuery.isBlank(),
                                rawQuery == null || rawQuery.isBlank() ? "" : SafeRedactor.hashValue(rawQuery),
                                resp.statusCode().value(),
                                body == null || body.isBlank() ? "" : SafeRedactor.hashValue(body),
                                body == null ? 0 : body.length(),
                                requestId != null && !requestId.isBlank(),
                                sessionId != null && !sessionId.isBlank());
                        return ClientResponse.create(resp.statusCode())
                                .headers(h -> h.addAll(resp.headers().asHttpHeaders()))
                                .body(body)
                                .build();
                    });
        });
    }

    private static String hostPath(URI uri) {
        if (uri == null) {
            return "";
        }
        String host = uri.getHost();
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (host == null || host.isBlank()) {
            return path;
        }
        return host + path;
    }

    private static String firstNonBlank(String... xs) {
        if (xs == null) return null;
        for (String x : xs) {
            if (x != null && !x.isBlank()) return x;
        }
        return null;
    }

    private static void traceSuppressed(String stage, Throwable ignored) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = ignored == null ? "unknown"
                : SafeRedactor.traceLabelOrFallback(ignored.getClass().getSimpleName(), "unknown");
        TraceStore.put("webclient.suppressed.stage", safeStage);
        TraceStore.put("webclient.suppressed.errorType", errorType);
        TraceStore.put("webclient.suppressed." + safeStage, true);
        TraceStore.put("webclient.suppressed." + safeStage + ".errorType", errorType);
    }

    /** Generic message gateway WebClient. No vendor auth header is attached by default. */
    @Bean(name = "messageGatewayWebClient")
    public WebClient messageGatewayWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(messageGatewayConnector())
                .baseUrl(messageGatewayProps.getApiBaseUrl())
                .build();
    }

    /** Generic message gateway business WebClient. No vendor auth header is attached by default. */
    @Bean(name = "messageGatewayBizWebClient")
    public WebClient messageGatewayBizWebClient(WebClient.Builder builder) {
        return builder
                .clientConnector(messageGatewayConnector())
                .baseUrl(messageGatewayProps.getApiBaseUrl())
                .build();
    }

    private static int safeTimeoutMs(int configuredMs) {
        return configuredMs > 0 ? configuredMs : 3000;
    }

    /**
     * NAVER Open API ?꾩슜 WebClient.  寃???쒕퉬?ㅻ뒗 ??鍮덉쓣 二쇱엯諛쏆븘
     * 湲곕낯 URL怨?怨듭슜 而ㅻ꽖?곕? ?ъ궗?⑺븳??  異붽? ?ㅻ뜑???몄텧?먯뿉???ㅼ젙?쒕떎.
     */
    @Bean(name = "naverWebClient")
    public WebClient naverWebClient(WebClient.Builder builder) {
        // Use the default builder here.  If a shared ReactorClientHttpConnector
        // is desired it can be injected similarly to the Channel connector.  The
        // base URL points to the Naver OpenAPI host.
        return builder
                .baseUrl(naverSearchApiBaseUrl)
                .filter(correlationHeadersFilter())
                .filter(logErrorBodyFilter(2048))
                .build();
    }
    
    // ---------------------------------------------------------------------
    // [ADD] OpenAI ?꾩슜 WebClient (湲??щ떇???+ ??⑸웾 ?덉쟾)
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
                    if (!ConfigValueGuards.isMissing(openAiApiKey)) {
                        h.setBearerAuth(openAiApiKey.trim());
                    }
                })
                .exchangeStrategies(strategies)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .filter(correlationHeadersFilter())
                .filter(logErrorBodyFilter(4096))
                .build();
    }
}
