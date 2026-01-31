package com.abandonware.ai.agent.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Map;




/**
 * Simplified notifier that emulates posting JSON payloads to an n8n
 * webhook.  In this implementation the payload is merely logged.  A
 * production version would use WebClient or RestTemplate to perform an
 * outbound HTTP POST to the specified URL.
 */
@Service
public class N8nNotifier {
    private static final Logger log = LoggerFactory.getLogger(N8nNotifier.class);

    public void notify(String webhookUrl, Map<String, Object> payload) {
        log.info("[N8nNotifier] POST to {} payload={} ", webhookUrl, payload);
        // This shim does not perform any real HTTP call.
    }
}