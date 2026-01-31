package com.abandonwareai.nova.cfvm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class CfvmRawService {
    private static final Logger log = LoggerFactory.getLogger(CfvmRawService.class);

    public void logFailure(String sessionId, String question, String plan, String reason) {
        log.warn("CFVM-RAW: session={} plan={} reason={} q='{}'", sessionId, plan, reason, question);
    }
}
