package com.rc111.merge21.rag;

import com.rc111.merge21.rag.fusion.RrfFusion;
import java.util.ArrayList;
import java.util.List;

public class DynamicRetrievalHandlerChain {
    private final RetrievalOrderService order;
    private final List<RetrievalHandler> handlers = new ArrayList<>();
    private RrfFusion fusion;

    public DynamicRetrievalHandlerChain(RetrievalOrderService order) { this.order = order; }

    public void addHandler(RetrievalHandler h) { handlers.add(h); }
    public void setFusion(RrfFusion f) { this.fusion = f; }

    public List<SearchDoc> run(String query, Hint hints) {
        List<RetrievalHandler> plan = order.plan(handlers, query, hints);
        List<List<SearchDoc>> buckets = new ArrayList<>();
        for (RetrievalHandler h : plan) {
            buckets.add(h.search(query, hints));
        }
        return fusion.fuse(buckets);
    }

    public static class Hint {
        public boolean needsWeb;
        public boolean needsVector = true;
        public boolean needsKg;
    }
}
