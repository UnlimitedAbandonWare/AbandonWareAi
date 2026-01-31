package com.abandonware.ai.service.rag.fusion;

import com.abandonware.ai.service.rag.model.ContextSlice;
import com.abandonware.ai.util.UrlCanonicalizer;

import java.util.*;

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