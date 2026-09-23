package com.example.lms.service.rag;

import com.example.lms.search.TraceStore;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HybridRetrieverSupport {

    private static final Logger log = LoggerFactory.getLogger(HybridRetrieverSupport.class);

    private HybridRetrieverSupport() {
    }

    static String extractUrl(String text) {
        if (text == null) {
            return null;
        }
        int href = text.indexOf("href=\"");
        if (href >= 0) {
            int start = href + 6;
            int end = text.indexOf('"', start);
            if (end > start) {
                return text.substring(start, end);
            }
        }
        int http = text.indexOf("http");
        if (http >= 0) {
            int space = text.indexOf(' ', http);
            return space > http ? text.substring(http, space) : text.substring(http);
        }
        return null;
    }

    static boolean isOfficial(String url, List<String> officialDomains) {
        if (url == null || officialDomains == null) {
            return false;
        }
        String host = hostFromUrl(url);
        if (host == null) {
            return false;
        }
        for (String domain : officialDomains) {
            if (hostMatchesSuffix(host, domain)) {
                return true;
            }
        }
        return false;
    }

    private static String hostFromUrl(String url) {
        String raw = url == null ? "" : url.trim();
        if (raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw);
            String host = uri.getHost();
            if (host != null && !host.isBlank()) {
                return normalizeHost(host);
            }
        } catch (IllegalArgumentException ignored) {
            log.debug("[HybridRetrieverSupport] fail-soft stage={} errorType={}",
                    "hostFromUrl", "invalid_uri");
        }
        try {
            URI uri = URI.create("https://" + raw);
            return normalizeHost(uri.getHost());
        } catch (IllegalArgumentException ignored) {
            log.debug("[HybridRetrieverSupport] fail-soft stage={} errorType={}",
                    "hostFromUrl.withScheme", "invalid_uri");
            return null;
        }
    }

    private static boolean hostMatchesSuffix(String host, String suffix) {
        String h = normalizeHost(host);
        String s = normalizeSuffix(suffix);
        if (h == null || s == null) {
            return false;
        }
        return h.equals(s) || h.endsWith("." + s);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.isBlank()) {
            return null;
        }
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    private static String normalizeSuffix(String suffix) {
        if (suffix == null) {
            return null;
        }
        String s = suffix.trim().toLowerCase(Locale.ROOT);
        while (s.startsWith(".")) {
            s = s.substring(1);
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.isBlank() ? null : s;
    }

    static List<Integer> toIntList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(HybridRetrieverSupport::toIntegerOrNull)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        }
        return null;
    }

    private static Integer toIntegerOrNull(Object value) {
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) {
                traceInvalidNumber();
                log.debug("[HybridRetrieverSupport] fail-soft stage={} errorType={}",
                        "toIntegerOrNull", "invalid_number");
                return null;
            }
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            traceInvalidNumber();
            log.debug("[HybridRetrieverSupport] fail-soft stage={} errorType={}",
                    "toIntegerOrNull", "invalid_number");
            return null;
        }
    }

    private static void traceInvalidNumber() {
        TraceStore.put("rag.hybridRetrieverSupport.suppressed.toIntegerOrNull", true);
        TraceStore.put("rag.hybridRetrieverSupport.toIntegerOrNull.errorType", "invalid_number");
    }
}
