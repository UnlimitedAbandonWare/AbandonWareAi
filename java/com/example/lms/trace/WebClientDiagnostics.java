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
import org.slf4j.MDC;


@Configuration
@ConditionalOnProperty(name = "lms.trace.http.enabled", havingValue = "true")
public class WebClientDiagnostics {
    private static final Logger log = LoggerFactory.getLogger(WebClientDiagnostics.class);

    private static final int MAX_BODY_PREVIEW = 2048;
	private static final String HDR_REQUEST_ID = "X-Request-Id";
	private static final String HDR_SESSION_ID = "X-Session-Id";

    private ExchangeFilterFunction requestLogger() {
        return ExchangeFilterFunction.ofRequestProcessor(req -> {
            try {
				String sid = MDC.get("sid");
				String trace = MDC.get("trace");

				ClientRequest.Builder b = ClientRequest.from(req);
				if (sid != null && !sid.isBlank() && !req.headers().containsKey(HDR_SESSION_ID)) {
					b.header(HDR_SESSION_ID, sid);
				}
				if (trace != null && !trace.isBlank() && !req.headers().containsKey(HDR_REQUEST_ID)) {
					b.header(HDR_REQUEST_ID, trace);
				}

				ClientRequest out = b.build();
				log.debug("[HTTP][REQ][sid={} trace={}] {} {}", sid, trace, out.method(), out.url());
				return Mono.just(out);
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
								String sid = MDC.get("sid");
								String trace = MDC.get("trace");
								log.debug("[HTTP][RES][sid={} trace={}][{}] {}", sid, trace, res.statusCode().value(), preview);
                                // Rebuild response with the consumed body to avoid downstream breakage.
								ClientResponse mutated = ClientResponse.from(res).body(body).build();
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