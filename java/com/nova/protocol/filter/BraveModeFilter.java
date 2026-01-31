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