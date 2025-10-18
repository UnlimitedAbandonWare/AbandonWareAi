package com.nova.protocol.guard;

import com.nova.protocol.context.RuleBreakContext;
import reactor.util.context.ContextView;
import java.util.Set;
import java.util.logging.Logger;




/**
 * DomainWhitelist를 대체/보조하는 정책 래퍼.
 * RuleBreak 활성 시 조건부 우회 후 감사 로그를 남깁니다.
 */
public class DomainAccessPolicy {
    private static final Logger log = Logger.getLogger("NovaDomainAccess");

    public boolean isAllowed(String domain, ContextView ctx, Set<String> whitelist) {
        boolean inWhite = whitelist == null || whitelist.isEmpty() || whitelist.contains(domain);
        if (inWhite) return true;
        if (RuleBreakContext.isActive(ctx)) {
            log.warning("[NOVA-DOMAIN] RuleBreak override for domain=" + domain);
            return true;
        }
        return false;
    }
}