package com.example.lms.service.rag.fusion;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RerankCanonicalizer {
    public String canonicalizeSource(String s){
        if (s == null) return "unknown";
        String out = s.trim().toLowerCase();
        if (out.startsWith("https://")) out = out.substring(8);
        if (out.startsWith("http://")) out = out.substring(7);
        if (out.startsWith("www.")) out = out.substring(4);
        return out;
    }

    public String canonicalizeUrl(String url) {
        if (url == null || url.isBlank()) return "nil";
        try {
            URI u = new URI(url);
            String host = u.getHost();
            String path = u.getPath() == null ? "" : u.getPath();
            String query = u.getRawQuery();
            List<String> keep = new ArrayList<>();
            if (query != null && !query.isBlank()) {
                for (String p : query.split("&")) {
                    int i = p.indexOf('=');
                    String key = (i >= 0 ? p.substring(0, i) : p);
                    if (key == null) continue;
                    key = key.toLowerCase();
                    if (key.startsWith("utm_") || key.equals("fbclid") || key.equals("gclid") || key.equals("ref")) continue;
                    keep.add(p);
                }
                java.util.Collections.sort(keep);
            }
            String h = (host == null) ? "" : host.toLowerCase();
            if (h.startsWith("www.")) h = h.substring(4);
            String base = h + path;
            return keep.isEmpty() ? base : (base + "?" + String.join("&", keep));
        } catch (Exception e) {
            return url;
        }
    }
}