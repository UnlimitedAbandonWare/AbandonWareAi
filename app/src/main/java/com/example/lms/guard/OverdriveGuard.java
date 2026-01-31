
package com.example.lms.guard;

import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/** Detects low-confidence conditions to flip Overdrive/Anger mode. */
@Component
public class OverdriveGuard {
    private static final Logger log = LoggerFactory.getLogger(OverdriveGuard.class);
    public boolean shouldOverdrive(double coverage, double authorityScore){ 
        double sparse = (coverage < 0.4) ? 1.0 : 0.0;
        double lowAuth = (authorityScore < 0.3) ? 1.0 : 0.0;
        double score = 0.6 * sparse + 0.4 * lowAuth;
        boolean trigger = score >= 0.55;
        if (trigger) {
            log.info(String.format("OverdriveGuard trigger: coverage=%.2f, authority=%.2f, score=%.2f", coverage, authorityScore, score));
        }
        return trigger;
    }
}