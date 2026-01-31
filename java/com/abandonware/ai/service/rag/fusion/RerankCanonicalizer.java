package com.abandonware.ai.service.rag.fusion;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

final class RerankCanonicalizer {
    private static final Set<String> DROP = Set.of(
            "utm_source","utm_medium","utm_campaign","utm_term","utm_content",
            "gclid","fbclid","igshid","spm","clid","ref"
    );

    private RerankCanonicalizer() {}

    static String canonicalKey(String urlOrId) {
        if (urlOrId == null) return "";
        String s = urlOrId.trim();
        try {
            URI u = URI.create(s);
            String host = (u.getHost() == null) ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String path = (u.getPath() == null) ? "" : u.getPath().replaceAll("/+$","");
            String query = u.getQuery();
            if (query != null && !query.isBlank()) {
                String kept = Arrays.stream(query.split("&"))
                        .map(p -> p.split("=",2))
                        .filter(kv -> kv.length>0 && !DROP.contains(kv[0].toLowerCase(Locale.ROOT)))
                        .map(kv -> String.join("=", kv))
                        .sorted()
                        .collect(Collectors.joining("&"));
                query = kept.isBlank() ? null : kept;
            }
            String base = host + path;
            return (query == null) ? base : (base + "?" + query);
        } catch (IllegalArgumentException e) {
            int i = s.indexOf('#');
            return (i >= 0) ? s.substring(0, i) : s;
        }
    }
}