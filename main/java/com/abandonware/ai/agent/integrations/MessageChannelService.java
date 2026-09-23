package com.abandonware.ai.agent.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;



/**
 * Fail-soft message channel gateway. The default runtime does not configure an
 * outbound provider, so calls are accepted for tracing only and reported as not
 * delivered.
 */
@Service
public class MessageChannelService {
    private static final Logger log = LoggerFactory.getLogger(MessageChannelService.class);
    private static final String DISABLED_REASON = "message channel provider is not configured";

    public boolean send(String roomId, String text) {
        String targetHash = Integer.toHexString(String.valueOf(roomId).hashCode());
        log.info("[message.channel] accepted=false provider=outbox/noop targetHash={} hasText={} disabledReason={}",
                targetHash, text != null && !text.isBlank(), DISABLED_REASON);
        return false;
    }
}
