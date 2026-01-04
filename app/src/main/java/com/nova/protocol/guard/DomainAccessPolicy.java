package com.nova.protocol.guard;

import com.nova.protocol.context.RuleBreakContext;
import reactor.util.context.ContextView;
import java.util.Set;
import java.util.logging.Logger;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.guard.DomainAccessPolicy
 * Role: config
 * Feature Flags: whitelist
 * Dependencies: com.nova.protocol.context.RuleBreakContext
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.guard.DomainAccessPolicy
role: config
flags: [whitelist]
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