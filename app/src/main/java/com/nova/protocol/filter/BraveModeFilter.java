package com.nova.protocol.filter;

import com.nova.protocol.config.NovaProperties;
import com.nova.protocol.context.BraveContext;
import com.nova.protocol.plan.Plan;
import com.nova.protocol.plan.PlanApplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;



@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.filter.BraveModeFilter
 * Role: config
 * Dependencies: com.nova.protocol.config.NovaProperties, com.nova.protocol.context.BraveContext, com.nova.protocol.plan.Plan, +1 more
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.filter.BraveModeFilter
role: config
*/
public class BraveModeFilter implements WebFilter {

    @Autowired
    NovaProperties props;

    @Autowired
    PlanApplier planApplier;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!props.isBraveEnabled()) return chain.filter(exchange);
        HttpHeaders h = exchange.getRequest().getHeaders();
        boolean braveOn = "on".equalsIgnoreCase(h.getFirst("X-Brave-Mode"));
        String planId = props.getDefaultPlanId();
        if (braveOn) {
            Plan plan = planApplier.resolvePlan(planId, true);
            BraveContext brave = new BraveContext(true, plan.getId());
            return chain.filter(exchange).contextWrite(ctx -> ctx.put(BraveContext.KEY, brave));
        }
        return chain.filter(exchange);
    }
}