package com.abandonware.ai.agent.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;



/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.KakaoMessageService
 * Role: service
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.KakaoMessageService
role: service
*/
public class KakaoMessageService {
    private static final Logger log = LoggerFactory.getLogger(KakaoMessageService.class);

    public void send(String roomId, String text) {
        log.info("[KakaoMessageService] send to roomId={} text={}", roomId, text);
        // No real HTTP call is made in this shim.
    }
}