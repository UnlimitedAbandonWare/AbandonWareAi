package com.abandonware.ai.service.rag.whiten;

import java.util.logging.Level;
import java.util.logging.Logger;

public interface Whitening {
    Logger log = Logger.getLogger(Whitening.class.getName());

    boolean isEnabled();
    float[] apply(float[] x);

    default float[] maybeApply(float[] x){
        try {
            return isEnabled() ? apply(x) : x;
        } catch (Throwable t) {
            logFailSoft("maybeApply", t);
            return x;
        }
    }

    private static void logFailSoft(String stage, Throwable t) {
        if (log.isLoggable(Level.FINE)) {
            String errorType = t == null ? "unknown" : t.getClass().getSimpleName();
            log.fine("[AWX][rag][whitening] failSoft stage=" + stage + " errorType=" + errorType);
        }
    }
}
