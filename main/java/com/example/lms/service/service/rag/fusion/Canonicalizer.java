package com.example.lms.service.service.rag.fusion;

import com.example.lms.search.TraceStore;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;



public class Canonicalizer {
    public static String canonicalUrl(String url) {
        if (url == null) return null;
        try {
            URI u = new URI(url);
            String host = (u.getHost() == null) ? "" : u.getHost().toLowerCase(Locale.ROOT);
            String path = (u.getPath() == null) ? "" : u.getPath();
            // strip common tracking params by ignoring query completely
            return new URI(u.getScheme(), host, path, null).toString();
        } catch (URISyntaxException e) {
            TraceStore.put("rag.fusion.canonicalizer.suppressed.url", true);
            TraceStore.put("rag.fusion.canonicalizer.suppressed.url.errorType", "invalid_url");
            return url;
        }
    }

    public static String contentHash(String content) {
        if (content == null) return null;
        // light-weight stable hash (not secure)
        return Integer.toHexString(content.replaceAll("\\s+"," ").trim().hashCode());
    }
}
