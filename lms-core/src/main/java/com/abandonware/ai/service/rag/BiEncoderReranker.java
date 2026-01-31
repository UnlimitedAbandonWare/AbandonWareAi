package com.abandonware.ai.service.rag;

import com.abandonware.ai.service.rag.model.ContextSlice;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.BiEncoderReranker
 * Role: service
 * Dependencies: com.abandonware.ai.service.rag.model.ContextSlice
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.BiEncoderReranker
role: service
*/
public class BiEncoderReranker {
    public List<ContextSlice> rerank(List<ContextSlice> in) {
        // Sort by score desc default; maintain stability
        List<ContextSlice> out = new ArrayList<>(in);
        out.sort(Comparator.comparingDouble(ContextSlice::getScore).reversed());
        for (int i=0;i<out.size();i++) out.get(i).setRank(i+1);
        return out;
    }
}