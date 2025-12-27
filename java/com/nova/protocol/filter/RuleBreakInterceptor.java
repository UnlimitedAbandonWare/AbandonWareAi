package com.nova.protocol.filter;

import com.nova.protocol.config.NovaProperties;
import com.nova.protocol.context.RuleBreakContext;
import com.nova.protocol.rulebreak.RuleBreakPolicy;
import com.nova.protocol.util.HmacSigner;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import java.util.Map;




@Component
public class RuleBreakInterceptor implements WebFilter {

    @Autowired
    NovaProperties props;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!props.isRulebreakEnabled()) return chain.filter(exchange);
        String tok = exchange.getRequest().getHeaders().getFirst("X-RuleBreak-Token");
        if (tok == null || tok.isEmpty()) return chain.filter(exchange);
        try {
            Map<String, String> claims = HmacSigner.verifyAndDecode(tok, props.getRulebreakHmacSecret(), props.getRulebreakTokenTtlSeconds());
            RuleBreakPolicy policy = RuleBreakPolicy.valueOf(claims.getOrDefault("policy", "fast"));
            long iat = Long.parseLong(claims.getOrDefault("iat", "0"));
            RuleBreakContext ctx = new RuleBreakContext(policy, iat);
            return chain.filter(exchange).contextWrite(c -> c.put(RuleBreakContext.KEY, ctx));
        } catch (Exception e) {
            // invalid token → ignore (no elevation)
            return chain.filter(exchange);
        }
    }
}