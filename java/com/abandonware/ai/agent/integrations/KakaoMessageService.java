package com.abandonware.ai.agent.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;



/**
 * Minimal Kakao messaging client used for demonstration purposes.  In a real
 * deployment this class would wrap HTTP calls to the Kakao API to send
 * messages to users or channels.  Here it simply logs the outgoing
 * message and pretends that the call succeeded.
 */
@Service
public class KakaoMessageService {
    private static final Logger log = LoggerFactory.getLogger(KakaoMessageService.class);

    public void send(String roomId, String text) {
        log.info("[KakaoMessageService] send to roomId={} text={}", roomId, text);
        // No real HTTP call is made in this shim.
    }
}