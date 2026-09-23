package com.example.lms.service.guard;

import com.example.lms.search.TraceStore;

final class EvidenceDocListEnricher {

    private static final System.Logger LOG = System.getLogger(EvidenceDocListEnricher.class.getName());

    private EvidenceDocListEnricher() {
    }

    static java.util.List<EvidenceAwareGuard.EvidenceDoc> enrich(
            java.util.List<EvidenceAwareGuard.EvidenceDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return docs == null ? java.util.List.of() : docs;
        }

        int derivedTitle = 0;
        int derivedSnippet = 0;

        java.util.ArrayList<EvidenceAwareGuard.EvidenceDoc> out = new java.util.ArrayList<>(docs.size());
        for (EvidenceAwareGuard.EvidenceDoc d : docs) {
            if (d == null) {
                continue;
            }

            String url = d.url();
            if (isBlank(url)) {
                url = d.id();
            }

            String title = d.title();
            String snippet = d.snippet();

            boolean titleBlank = isBlank(title);
            boolean snippetBlank = isBlank(snippet);

            String host = "";
            String tail = "";
            if ((titleBlank || snippetBlank) && !isBlank(url)) {
                host = urlHost(url);
                tail = urlTail(url);
            }

            if (titleBlank) {
                String t = deriveTitleFromUrl(host, tail);
                if (!isBlank(t)) {
                    title = t;
                    derivedTitle++;
                }
            }

            if (snippetBlank) {
                String s = deriveSnippetFromUrl(host, tail);
                if (!isBlank(s)) {
                    snippet = s;
                    derivedSnippet++;
                }
            }

            out.add(new EvidenceAwareGuard.EvidenceDoc(d.id(), title, snippet, d.url()));
        }

        if (derivedTitle > 0) {
            TraceStore.put("guard.evidenceList.derivedTitle.count", derivedTitle);
        }
        if (derivedSnippet > 0) {
            TraceStore.put("guard.evidenceList.derivedSnippet.count", derivedSnippet);
        }
        return out;
    }

    private static String urlHost(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            return host == null ? "" : host;
        } catch (Exception ignore) {
            LOG.log(System.Logger.Level.DEBUG, "[EvidenceDocListEnricher] fail-soft stage={0}", "urlHost");
            return "";
        }
    }

    private static String urlTail(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return "";
            }

            String[] parts = path.split("/");
            for (int i = parts.length - 1; i >= 0; i--) {
                String raw = parts[i];
                if (raw == null) {
                    continue;
                }
                String seg = raw.trim();
                if (seg.isEmpty()) {
                    continue;
                }

                seg = safeDecode(seg);
                seg = seg.replaceAll("\\.(html?|php|aspx|jsp)$", "");
                seg = seg.replace('-', ' ').replace('_', ' ').trim();

                if (seg.length() > 80) {
                    seg = seg.substring(0, 80).trim();
                }
                return seg;
            }

            return "";
        } catch (Exception ignore) {
            LOG.log(System.Logger.Level.DEBUG, "[EvidenceDocListEnricher] fail-soft stage={0}", "urlTail");
            return "";
        }
    }

    private static String safeDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignore) {
            LOG.log(System.Logger.Level.DEBUG, "[EvidenceDocListEnricher] fail-soft stage={0}", "safeDecode");
            return s;
        }
    }

    private static String deriveTitleFromUrl(String host, String tail) {
        String t = (tail == null) ? "" : tail.trim();
        if (!t.isEmpty() && !t.equalsIgnoreCase("index") && !t.equalsIgnoreCase("home")) {
            return t;
        }
        return (host == null) ? "" : host.trim();
    }

    private static String deriveSnippetFromUrl(String host, String tail) {
        String h = (host == null) ? "" : host.trim();
        String t = (tail == null) ? "" : tail.trim();
        if (h.isEmpty() && t.isEmpty()) {
            return "";
        }

        String label = "URL-derived fallback (not a verified page title or summary)";
        if (!h.isEmpty() && !t.isEmpty()) {
            return label + " - " + h + " - " + t;
        }
        if (!h.isEmpty()) {
            return label + " - " + h;
        }
        return label + " - " + t;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
