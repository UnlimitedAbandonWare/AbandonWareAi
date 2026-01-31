package com.abandonware.ai.service.rag;

import com.abandonware.ai.service.rag.model.ContextSlice;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Fast 1-pass bi-encoder style sorter (lightweight heuristic placeholder).
 */
@Service
public class BiEncoderReranker {
    public List<ContextSlice> rerank(List<ContextSlice> in) {
        // Sort by score desc default; maintain stability
        List<ContextSlice> out = new ArrayList<>(in);
        out.sort(Comparator.comparingDouble(ContextSlice::getScore).reversed());
        for (int i=0;i<out.size();i++) out.get(i).setRank(i+1);
        return out;
    }
}