package com.acme.aicore.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;




/**
 * Provides a global {@link WebClient} bean configured with a timeout and
 * correlation ID propagation.  Outbound requests will automatically include
 * the {@code X-Correlation-ID} header from the Reactor context when present.
 * A default response timeout of 6 seconds is applied to avoid hanging
 * indefinitely on remote services.
 */
@Configuration
public class WebClientConfig {

    /**
     * Construct a shared WebClient with correlation header propagation and a
     * reasonable response timeout.  The reactor context is inspected for a
     * "cid" entry which is then copied into the X-Correlation-ID header of
     * each outgoing request.
     *
     * @param builder the WebClient builder provided by Spring
     * @return a configured WebClient instance
     */
    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        return builder
                .clientConnector(new ReactorClientHttpConnector(
                        HttpClient.create().responseTimeout(Duration.ofSeconds(6))))
                .filter(correlationFilter())
                .build();
    }

    private ExchangeFilterFunction correlationFilter() {
        return (request, next) -> Mono.deferContextual(ctx -> {
            String cid = ctx.getOrDefault("cid", "");
            ClientRequest withCid = ClientRequest.from(request)
                    .header("X-Correlation-ID", cid)
                    .build();
            return next.exchange(withCid);
        });
    }
}