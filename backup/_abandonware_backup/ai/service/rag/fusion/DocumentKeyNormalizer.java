package com.abandonware.ai.service.rag.fusion;

import com.abandonware.ai.service.rag.model.ContextSlice;
import com.abandonware.ai.util.UrlCanonicalizer;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.fusion.DocumentKeyNormalizer
 * Role: config
 * Dependencies: com.abandonware.ai.service.rag.model.ContextSlice, com.abandonware.ai.util.UrlCanonicalizer
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.DocumentKeyNormalizer
role: config
*/
public class DocumentKeyNormalizer {

    public Map<String, ContextSlice> mergeByCanonicalKey(List<ContextSlice> docs){
        Map<String, ContextSlice> out = new LinkedHashMap<>();
        for (ContextSlice d : docs){
            String key = keyOf(d);
            ContextSlice prev = out.get(key);
            if (prev == null || d.getScore() > prev.getScore()){
                out.put(key, d);
            }
        }
        return out;
    }

    public String keyOf(ContextSlice d){
        String id = d.getId();
        if (id == null) return null;
        String low = id.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://")){
            return UrlCanonicalizer.canonicalKey(id);
        }
        return id;
    }
}