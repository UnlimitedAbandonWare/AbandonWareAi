package com.abandonwareai.nova.autolearn;

import com.abandonwareai.nova.config.IdleTrainProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Legacy cron-based idle-train chain.
 *
 * <p>As of merge15, orchestration is unified in {@link AutolearnScheduler} with
 * "user absent + spare capacity" gating.
 */
@Component
@Deprecated
public class IdleTrainChain {

    private static final Logger log = LoggerFactory.getLogger(IdleTrainChain.class);

    public IdleTrainChain(IdleTrainProperties props) {
        if (props != null && props.isEnabled()) {
            log.info("IdleTrainChain is deprecated; use AutolearnScheduler (idle poll) instead.");
        }
    }
}
