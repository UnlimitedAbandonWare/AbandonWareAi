package com.example.patch;

import java.util.logging.Level;
import java.util.logging.Logger;


/** Safe bridge: resolve FlowJoiner via reflection if present. */
public class FlowJoinerBridge {
    private static final Logger log = Logger.getLogger(FlowJoinerBridge.class.getName());

    public static String plan(Object ctx) {
        logFailSoft("plan.disabled", null);
        return "SKIPPED";
    }

    private static void logFailSoft(String stage, Throwable t) {
        if (log.isLoggable(Level.FINE)) {
            String errorType = t == null ? "unknown" : t.getClass().getSimpleName();
            log.fine("[AWX][patch][flow-joiner] failSoft stage=" + stage + " errorType=" + errorType);
        }
    }
}
