package com.acme.aicore.common;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.Optional;
import java.util.UUID;




/**
 * Injects a correlation identifier into the Reactor context for every incoming
 * request.  If the client supplies an {@code X-Correlation-ID} header the
 * provided value is propagated, otherwise a random UUID is generated.  Downstream
 * components such as WebClient filters can retrieve this value from the
 * Reactor {@code Context} and attach it to outbound calls.  Ordering is set
 * to highest precedence to ensure the correlation ID is available to all
 * subsequent filters in the chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestContextFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String cid = Optional.ofNullable(exchange.getRequest().getHeaders().getFirst("X-Correlation-ID"))
                .orElse(UUID.randomUUID().toString());
        return chain.filter(exchange)
                .contextWrite(ctx -> ctx.put("cid", cid));
    }
}