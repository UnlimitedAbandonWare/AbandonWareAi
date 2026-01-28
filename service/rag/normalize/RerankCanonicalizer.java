package com.abandonware.ai.normalization.service.rag.normalize;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

public class RerankCanonicalizer {
    public static String canonicalKey(String url) {
        if (url == null || url.isEmpty()) return url;
        try {
            URI u = new URI(url);
            String host = u.getHost()==null? "" : u.getHost().toLowerCase();
            String path = (u.getPath()==null? "" : u.getPath()).replaceAll("/+", "/");
            String query = u.getQuery();
            Map<String, List<String>> params = new TreeMap<>();
            if (query != null) {
                for (String kv : query.split("&")) {
                    if (kv.toLowerCase().startsWith("utm_")) continue;
                    int i = kv.indexOf('=');
                    String k = i>0? kv.substring(0,i): kv;
                    String v = i>0? kv.substring(i+1): "";
                    params.computeIfAbsent(k, kk->new ArrayList<>()).add(v);
                }
            }
            String q = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + String.join(",", new TreeSet<>(e.getValue())))
                .collect(Collectors.joining("&"));
            String scheme = (u.getScheme()==null? "http": u.getScheme().toLowerCase());
            return scheme + "://" + host + path + (q.isEmpty()? "" : "?" + q);
        } catch (URISyntaxException e) {
            return url;
        }
    }
}