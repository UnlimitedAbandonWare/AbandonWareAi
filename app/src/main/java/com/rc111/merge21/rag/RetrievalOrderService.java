package com.rc111.merge21.rag;

import java.util.ArrayList;
import java.util.List;

public class RetrievalOrderService {
    public List<RetrievalHandler> plan(List<RetrievalHandler> handlers, String query, DynamicRetrievalHandlerChain.Hint hints) {
        List<RetrievalHandler> out = new ArrayList<>();
        if (hints == null) hints = new DynamicRetrievalHandlerChain.Hint();

        // 1) Web if requested or we have no vector results forecast
        for (RetrievalHandler h : handlers) {
            String name = h.getName().toLowerCase();
            if (name.contains("web") && (hints.needsWeb)) out.add(h);
        }
        // 2) Vector is default
        for (RetrievalHandler h : handlers) {
            String name = h.getName().toLowerCase();
            if (name.contains("vector") && (hints.needsVector || true)) out.add(h);
        }
        // 3) KG when explicitly needed
        for (RetrievalHandler h : handlers) {
            String name = h.getName().toLowerCase();
            if (name.contains("kg") && hints.needsKg) out.add(h);
        }
        return out;
    }
}
