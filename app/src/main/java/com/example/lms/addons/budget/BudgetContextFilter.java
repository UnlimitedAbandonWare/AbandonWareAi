package com.example.lms.addons.budget;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.addons.budget.BudgetContextFilter
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.addons.budget.BudgetContextFilter
role: config
*/
public class BudgetContextFilter implements WebFilter {
    private final TimeBudget budget;

    public BudgetContextFilter(
        @Value("${budget.time-ms.total:30000}") long total,
        @Value("${budget.time-ms.web:8000}") long web,
        @Value("${budget.time-ms.vector:6000}") long vector,
        @Value("${budget.time-ms.rerank:9000}") long rerank
    ){
        this.budget = TimeBudget.of(total, web, vector, rerank);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        return chain.filter(exchange).contextWrite(ctx -> ctx.put("timeBudget", budget));
    }
}