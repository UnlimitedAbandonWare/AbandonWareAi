package com.example.lms.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

/**
 * Configures a WebClient filter that logs the response body of error
 * responses.  When an HTTP 4xx or 5xx response is returned the body
 * content is consumed and logged.  The body is truncated to avoid
 * excessively long log lines.  The filter does not modify the
 * behaviour of the WebClient beyond logging; the original response is
 * passed downstream.
 */
@Configuration
public class HttpErrorLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger("HTTP.ERROR");

    @Bean
    public ExchangeFilterFunction logOnErrorFilter() {
        return ExchangeFilterFunction.ofResponseProcessor((ClientResponse res) -> {
            if (!res.statusCode().isError()) {
                return Mono.just(res);
            }
            // Use createException to access the response body without consuming it twice.
            return res.createException().doOnNext(ex -> {
                try {
                    String b = ex.getResponseBodyAsString();
                    if (b != null && b.length() > 4000) {
                        b = b.substring(0, 4000) + "...(truncated)";
                    }
                    log.warn("HTTP {} {} | body: {}", res.rawStatusCode(), res.statusCode(), b);
                } catch (Throwable t) {
                    log.warn("HTTP {} {} | <no-body>", res.rawStatusCode(), res.statusCode());
                }
            }).thenReturn(res);
        });
    }

    @Bean
    public WebClientCustomizer attachLogOnError(ExchangeFilterFunction logOnErrorFilter) {
        return builder -> builder.filter(logOnErrorFilter);
    }
}