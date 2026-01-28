package com.abandonware.ai.placeholder;

import java.net.URI;
import java.util.*;
/** Minimal domain-count gate for citations. */
public final class CitationGate {
    private CitationGate() {}
    /** Returns true if the number of distinct eTLD+1 domains in sources >= minDistinct. */
    public static boolean allow(Collection<String> sourceUrls, int minDistinct) {
        if (sourceUrls == null) return false;
        Set<String> domains = new HashSet<>();
        for (String u : sourceUrls) {
            if (u == null || u.isBlank()) continue;
            try {
                String host = URI.create(u).getHost();
                if (host != null) domains.add(host.toLowerCase(Locale.ROOT));
            } catch (Exception ignore) {}
        }
        return domains.size() >= Math.max(1, minDistinct);
    }
}