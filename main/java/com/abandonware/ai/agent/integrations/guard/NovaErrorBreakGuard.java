package com.abandonware.ai.agent.integrations.guard;

public final class NovaErrorBreakGuard {
    public enum Risk { OK, WARN, BREAK }
    public static final class Policy {
        public final boolean onnxEnabled;
        public final int webTopK;
        public final boolean officialSourcesOnly;
        public final boolean cacheOnly;
        public Policy(boolean onnxEnabled, int webTopK, boolean officialSourcesOnly, boolean cacheOnly) {
            this.onnxEnabled = onnxEnabled;
            this.webTopK = webTopK;
            this.officialSourcesOnly = officialSourcesOnly;
            this.cacheOnly = cacheOnly;
        }
    }
    public Policy decide(Risk risk) {
        switch (risk) {
            case WARN: return new Policy(false, 8, true, false);
            case BREAK: return new Policy(false, 4, true, true);
            case OK:
            default: return new Policy(true, 12, false, false);
        }
    }
}