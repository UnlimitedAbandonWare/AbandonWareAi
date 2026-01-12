package com.abandonware.ai.agent.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Map;




/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.N8nNotifier
 * Role: service
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.N8nNotifier
role: service
*/
public class N8nNotifier {
    private static final Logger log = LoggerFactory.getLogger(N8nNotifier.class);

    public void notify(String webhookUrl, Map<String, Object> payload) {
        log.info("[N8nNotifier] POST to {} payload={} ", webhookUrl, payload);
        // This shim does not perform any real HTTP call.
    }
}