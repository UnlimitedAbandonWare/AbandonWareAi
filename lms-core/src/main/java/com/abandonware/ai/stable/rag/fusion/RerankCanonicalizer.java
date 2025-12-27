package com.abandonware.ai.stable.rag.fusion;

import java.net.URI;
import java.util.*;
import com.abandonware.ai.stable.rag.model.ContextSlice;

/** Deduplicate by canonical URL or id, stripping utm_ params and fragments. */
public final class RerankCanonicalizer {

    public String canonicalKey(String urlOrId){
        if(urlOrId==null) return "";
        try{
            URI u = URI.create(urlOrId);
            String scheme = u.getScheme()==null? "": u.getScheme().toLowerCase();
            String host = u.getHost()==null? "": u.getHost().toLowerCase();
            int port = u.getPort();
            String path = Optional.ofNullable(u.getPath()).orElse("");
            String query = Optional.ofNullable(u.getQuery()).orElse("");
            // remove utm_* params
            Map<String,String> kept = new LinkedHashMap<>();
            for(String kv : query.split("&")){
                if(kv.isBlank()) continue;
                int idx = kv.indexOf('=');
                String key = (idx>0? kv.substring(0,idx):kv).toLowerCase();
                if(!key.startsWith("utm_")) kept.put(key, idx>0?kv.substring(idx+1):"");
            }
            String q = kept.isEmpty()? "" : ("?"+kept.entrySet().stream()
                .map(e->e.getKey()+"="+e.getValue()).reduce((a,b)->a+"&"+b).orElse(""));
            String portPart = (port==-1 || (port==80 && "http".equals(scheme)) || (port==443 && "https".equals(scheme)))? "" : (":"+port);
            return scheme + "://" + host + portPart + path + q;
        }catch(Exception ex){
            return urlOrId; // not a URI, treat as id
        }
    }

    public List<ContextSlice> normalize(List<ContextSlice> in){
        if(in==null || in.isEmpty()) return Collections.emptyList();
        Map<String, ContextSlice> best = new LinkedHashMap<>();
        for(ContextSlice c: in){
            String key = canonicalKey(c.getId());
            ContextSlice prev = best.get(key);
            if(prev==null || c.getScore()>prev.getScore()) best.put(key, c);
        }
        return new ArrayList<>(best.values());
    }
}