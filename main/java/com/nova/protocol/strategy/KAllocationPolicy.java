package com.nova.protocol.strategy;

import com.nova.protocol.context.BraveContext;
import reactor.util.context.ContextView;



public class KAllocationPolicy {
    public static final class K {
        public final int web, vec, kg;
        public K(int web, int vec, int kg) { this.web = web; this.vec = vec; this.kg = kg; }
    }

    public K allocate(Intent intent, ContextView ctx) {
        boolean brave = BraveContext.isOn(ctx);
        if (brave && intent == Intent.RECENCY_CRITICAL) return new K(15, 5, 3);
        if (brave) return new K(12, 6, 3);
        if (intent == Intent.FACT_CHECK) return new K(8, 8, 4);
        return new K(8, 8, 2);
    }
}