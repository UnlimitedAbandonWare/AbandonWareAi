// src/main/java/com/example/lms/trace/WebClientDiagnostics.java
package com.example.lms.trace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;


@Configuration
@ConditionalOnProperty(name = "lms.trace.http.enabled", havingValue = "true")
public class WebClientDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(WebClientDiagnostics.class);

    private static final int MAX_BODY_PREVIEW = 2048;

    private ExchangeFilterFunction requestLogger() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            try {
                log.debug("[HTTP][REQ] {} {}", req.method(), req.url());
            } catch (Exception ignore) {}
            return Mono.just(req);
        });
    }

    private ExchangeFilterFunction responseLogger() {
        return ExchangeFilterFunction.ofResponseProcessor(res -> {
            try {
                if (res.statusCode().isError()) {
                    return res.bodyToMono(String.class)
                            .defaultIfEmpty("")
                            .flatMap(body -> {
                                String preview = body.length() > MAX_BODY_PREVIEW
                                        ? body.substring(0, MAX_BODY_PREVIEW) + "/* ... *&#47;"
                                        : body;
                                log.debug("[HTTP][RES][{}] {}", res.statusCode().value(), preview);
                                // Rebuild response with the consumed body to avoid downstream breakage.
                                ClientResponse mutated = ClientResponse.from(res).body(preview).build();
                                return Mono.just(mutated);
                            });
                }
            } catch (Exception ignore) {}
            return Mono.just(res);
        });
    }

    @Bean
    public WebClientCustomizer debugWebClientCustomizer() {
        ExchangeFilterFunction req = requestLogger();
        ExchangeFilterFunction res = responseLogger();
        return builder -> builder.filter(req).filter(res);
    }
}