package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import ai.abandonware.nova.orch.ecosystem.EcosystemBufferPool;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HybridWebSearchEmptyFallbackSupport {

    private static final Logger log = LoggerFactory.getLogger(HybridWebSearchEmptyFallbackSupport.class);

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s)]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern SITE_TOKEN_PATTERN = Pattern.compile("(?i)\\bsite:([^\\s]+)");
    private static final List<String> NOFILTER_SAFE_LOW_TRUST_HOST_MARKERS = List.of(
            "blog.naver.com",
            "cafe.naver.com",
            "tistory.com",
            "brunch.co.kr",
            "namu.wiki",
            "dcinside.com",
            "ruliweb.com",
            "inven.co.kr",
            "fmkorea.com",
            "theqoo.net",
            "instiz.net",
            "ppomppu.co.kr",
            "clien.net",
            "reddit.com",
            "quora.com",
            "youtube.com",
            "youtu.be",
            "facebook.com",
            "instagram.com",
            "twitter.com",
            "x.com");

    private HybridWebSearchEmptyFallbackSupport() {
    }

    static List<String> buildCacheProbeQueries(String query, int maxCandidates) {
        if (maxCandidates <= 0) {
            return Collections.emptyList();
        }
        String base = collapseWhitespace(query);
        if (base.isEmpty()) {
            return Collections.emptyList();
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        addProbeCandidate(out, base, maxCandidates);
        String q1 = stripSurroundingQuotes(base);
        addProbeCandidate(out, q1, maxCandidates);
        String q2 = collapseWhitespace(q1
                .replace("\"", "")
                .replace("“", "")
                .replace("”", "")
                .replace("'", ""));
        addProbeCandidate(out, q2, maxCandidates);
        String q2a = stripBracketContent(q2);
        addProbeCandidate(out, q2a, maxCandidates);
        String q2b = normalizePunct(q2a);
        addProbeCandidate(out, q2b, maxCandidates);
        String q3 = stripTrailingSuffix(q2b);
        addProbeCandidate(out, q3, maxCandidates);
        addProbeCandidate(out, chopTailTokens(q3, 1), maxCandidates);
        addProbeCandidate(out, chopTailTokens(q3, 2), maxCandidates);
        String q4 = collapseWhitespace(q3.replaceAll("(?i)\\s+site:[^\\s]+", ""));
        addProbeCandidate(out, q4, maxCandidates);
        addProbeCandidate(out, stripTrailingSuffix(base), maxCandidates);

        if (out.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(out);
    }

    static List<String> tracePoolRescueMerge(String query, int topK, boolean officialOnly, int rescueMergeMax) {
        int limit = Math.min(Math.max(1, topK), Math.max(1, rescueMergeMax));

        Object raw = null;
        String source = "selected";
        try {
            raw = TraceStore.get("web.failsoft.domainStagePairs.selected");
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.tracePoolSelected", ignore);
            raw = null;
        }

        List<Map<?, ?>> events = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    events.add(m);
                }
            }
        }

        if (events.isEmpty()) {
            source = "raw";
            try {
                raw = TraceStore.get("web.failsoft.domainStagePairs");
            } catch (Throwable ignore) {
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.tracePoolRaw", ignore);
                raw = null;
            }
            if (raw instanceof List<?> list2) {
                for (Object o : list2) {
                    if (o instanceof Map<?, ?> m) {
                        events.add(m);
                    }
                }
            }
        }

        if (events.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.source", source);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.rescueMerge.tracePool.size", events.size());
            TraceStore.put("tracePool.size", events.size());
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.tracePoolTrace", ignore);
            // best-effort
        }

        String qNorm = normalizeForProbe(query);
        boolean anyMatch = false;
        if (!qNorm.isEmpty()) {
            for (Map<?, ?> e : events) {
                String c = normalizeForProbe(asString(e.get("canonicalQuery")));
                String ex = normalizeForProbe(asString(e.get("executedQuery")));
                if ((!c.isEmpty() && qNorm.equals(c)) || (!ex.isEmpty() && qNorm.equals(ex))) {
                    anyMatch = true;
                    break;
                }
            }
        }

        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Map<?, ?> e : events) {
            if (out.size() >= limit) {
                break;
            }

            if (anyMatch && !qNorm.isEmpty()) {
                String c = normalizeForProbe(asString(e.get("canonicalQuery")));
                String ex = normalizeForProbe(asString(e.get("executedQuery")));
                if (!(qNorm.equals(c) || qNorm.equals(ex))) {
                    continue;
                }
            }

            String url = asString(e.get("url")).trim();
            if (url.isEmpty()) {
                continue;
            }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) {
                continue;
            }

            if (officialOnly) {
                String stage = asString(e.get("stageFinal"));
                if (stage.isBlank() || "EXCLUDED".equalsIgnoreCase(stage.trim())) {
                    stage = asString(e.get("stage"));
                }
                String st = stage.trim().toUpperCase(java.util.Locale.ROOT);
                if (st.isEmpty()) {
                    continue;
                }
                if (!(st.equals("OFFICIAL")
                        || st.equals("DOCS")
                        || st.equals("WHITELIST")
                        || st.contains("PROFILE")
                        || st.equals("NOFILTER_SAFE"))) {
                    continue;
                }
            }

            out.add("URL: " + url);
        }

        if (out.isEmpty()) {
            return Collections.emptyList();
        }
        return filterScopedCacheOnlySnippets(query, new ArrayList<>(out), limit, "tracePool");
    }

    static List<String> filterScopedCacheOnlySnippets(String query, List<String> snippets, int topK, String source) {
        List<SiteRequirement> requirements = siteRequirements(query);
        if (requirements.isEmpty()) {
            return snippets == null ? Collections.emptyList() : snippets;
        }

        int inputCount = snippets == null ? 0 : snippets.size();
        int limit = Math.max(1, topK);
        List<String> kept = new ArrayList<>();
        int removed = 0;
        if (snippets != null) {
            for (String snippet : snippets) {
                if (snippet == null || snippet.isBlank()) {
                    continue;
                }
                if (matchesAnySiteRequirement(snippet, requirements)) {
                    kept.add(snippet);
                    if (kept.size() >= limit) {
                        break;
                    }
                } else {
                    removed++;
                }
            }
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.enabled", true);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.lastSource",
                    com.example.lms.trace.SafeRedactor.traceLabelOrFallback(source, "unknown"));
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.required.count",
                    requirements.size());
            TraceStore.inc("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.input.count", inputCount);
            TraceStore.inc("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.kept.count", kept.size());
            TraceStore.inc("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.removed.count", removed);
            TraceStore.put("web.failsoft.hybridEmptyFallback.cacheOnly.scopedFilter.required.hashes",
                    requirementHashes(requirements));
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.scopedFilterTrace", ignore);
        }

        if (kept.isEmpty()) {
            return Collections.emptyList();
        }
        return kept;
    }

    static List<String> mergeAndLimit(List<String> a, List<String> b, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        mergeInto(out, a, limit);
        mergeInto(out, b, limit);
        if (out.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(out);
    }

    static Set<String> mergeInto(Set<String> base, List<String> add, int limit) {
        if (base == null) {
            base = new LinkedHashSet<>();
        }
        if (limit <= 0 || base.size() >= limit) {
            return base;
        }
        if (add == null || add.isEmpty()) {
            return base;
        }
        for (String s : add) {
            if (base.size() >= limit) {
                break;
            }
            String t = (s == null) ? "" : s.trim();
            if (t.isEmpty()) {
                continue;
            }
            base.add(t);
        }
        return base;
    }

    static void cacheRescuePayload(String resultKey, List<String> merged) {
        if (resultKey == null || resultKey.isBlank() || merged == null || merged.isEmpty()) {
            return;
        }
        try {
            TraceStore.put(resultKey, merged);
            TraceStore.put("web.failsoft.hybridEmptyFallback.result.cached", true);
            TraceStore.put("web.failsoft.hybridEmptyFallback.result.cached.size", merged.size());
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.cacheRescuePayload", ignore);
            // best-effort
        }
    }

    static String queryHashForGate(String query) {
        String norm = normalizeForGate(query);
        if (norm.isEmpty()) {
            return "00000000";
        }
        return crc32Hex(norm);
    }

    static String extractHost(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return null;
        }

        URI uri = extractUri(snippet);
        if (uri == null) {
            return null;
        }
        try {
            String host = uri.getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            return host;
        } catch (Exception ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.extractHost", ignore);
            return null;
        }
    }

    private static URI extractUri(String snippet) {
        if (snippet == null || snippet.isBlank()) {
            return null;
        }
        Matcher m = URL_PATTERN.matcher(snippet);
        if (!m.find()) {
            return null;
        }
        String url = trimTrailingUrlPunctuation(m.group());
        if (url.isBlank()) {
            return null;
        }
        try {
            return URI.create(url);
        } catch (Exception ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.extractUri", ignore);
            return null;
        }
    }

    private static String trimTrailingUrlPunctuation(String url) {
        if (url == null) {
            return "";
        }
        String out = url.trim();
        while (!out.isEmpty()) {
            char last = out.charAt(out.length() - 1);
            if (last == '.' || last == ',' || last == ';' || last == ':' || last == ']' || last == '}') {
                out = out.substring(0, out.length() - 1).trim();
                continue;
            }
            break;
        }
        return out;
    }

    private static boolean matchesAnySiteRequirement(String snippet, List<SiteRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return true;
        }
        URI uri = extractUri(snippet);
        if (uri == null) {
            return false;
        }
        String host = normalizeHost(uri.getHost());
        if (host.isBlank()) {
            return false;
        }
        String path = normalizePath(uri.getPath());
        for (SiteRequirement requirement : requirements) {
            if (requirement.matches(host, path)) {
                return true;
            }
        }
        return false;
    }

    private static List<SiteRequirement> siteRequirements(String query) {
        if (query == null || query.isBlank()) {
            return Collections.emptyList();
        }
        Matcher matcher = SITE_TOKEN_PATTERN.matcher(query);
        List<SiteRequirement> out = new ArrayList<>();
        while (matcher.find()) {
            SiteRequirement requirement = parseSiteRequirement(matcher.group(1));
            if (requirement != null) {
                out.add(requirement);
            }
        }
        return out;
    }

    private static SiteRequirement parseSiteRequirement(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return null;
        }
        String token = rawToken.trim();
        while (!token.isEmpty()) {
            char last = token.charAt(token.length() - 1);
            if (last == '"' || last == '\'' || last == '`' || last == ',' || last == ';'
                    || last == ')' || last == ']' || last == '}') {
                token = token.substring(0, token.length() - 1).trim();
                continue;
            }
            break;
        }
        while (!token.isEmpty()) {
            char first = token.charAt(0);
            if (first == '"' || first == '\'' || first == '`' || first == '(' || first == '[' || first == '{') {
                token = token.substring(1).trim();
                continue;
            }
            break;
        }
        if (token.isBlank()) {
            return null;
        }

        String hostPart = token;
        String pathPart = "";
        try {
            if (token.startsWith("http://") || token.startsWith("https://")) {
                URI uri = URI.create(token);
                hostPart = uri.getHost();
                pathPart = uri.getPath();
            } else {
                int slash = token.indexOf('/');
                if (slash >= 0) {
                    hostPart = token.substring(0, slash);
                    pathPart = token.substring(slash);
                }
            }
        } catch (Exception ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.parseSiteRequirement", ignore);
        }

        String host = normalizeHost(hostPart);
        if (host.isBlank()) {
            return null;
        }
        return new SiteRequirement(host, normalizePath(pathPart));
    }

    private static String normalizeHost(String host) {
        if (host == null || host.isBlank()) {
            return "";
        }
        String out = host.trim().toLowerCase(java.util.Locale.ROOT);
        while (out.startsWith("*.")) {
            out = out.substring(2);
        }
        if (out.startsWith("www.")) {
            out = out.substring(4);
        }
        return out;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path.trim())) {
            return "";
        }
        String out = path.trim().toLowerCase(java.util.Locale.ROOT);
        if (!out.startsWith("/")) {
            out = "/" + out;
        }
        while (out.endsWith("/") && out.length() > 1) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }

    private static List<String> requirementHashes(List<SiteRequirement> requirements) {
        if (requirements == null || requirements.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        for (SiteRequirement requirement : requirements) {
            if (requirement == null) {
                continue;
            }
            out.add(crc32Hex(requirement.host() + requirement.path()));
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }

    private record SiteRequirement(String host, String path) {
        boolean matches(String candidateHost, String candidatePath) {
            if (candidateHost == null || candidateHost.isBlank()) {
                return false;
            }
            String normalizedPath = candidatePath == null ? "" : candidatePath;
            boolean hostMatches = candidateHost.equals(host) || candidateHost.endsWith("." + host);
            if (!hostMatches) {
                return false;
            }
            if (path == null || path.isBlank()) {
                return true;
            }
            return normalizedPath.equals(path) || normalizedPath.startsWith(path + "/");
        }
    }

    static boolean isLowTrustHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for (String marker : NOFILTER_SAFE_LOW_TRUST_HOST_MARKERS) {
            if (marker == null || marker.isBlank()) {
                continue;
            }
            if (h.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    static List<String> maybeFilterLowTrustForNofilterSafe(
            List<String> merged,
            int topK,
            boolean enabled,
            double maxLowTrustRatioCfg,
            double ammoniaThreshold,
            String ammoniaQuarantineTag) {
        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.enabled", enabled);
        } catch (Throwable ignored) {
            logSuppressed("lowTrustFilter.enabled", ignored);
        }

        if (!enabled || merged == null || merged.isEmpty()) {
            return merged;
        }

        double maxLowTrustRatio = (maxLowTrustRatioCfg < 0d) ? 0d : Math.min(1.0d, maxLowTrustRatioCfg);
        int maxLowTrust = (int) Math.floor(Math.max(0, topK) * maxLowTrustRatio);
        if (maxLowTrust <= 0) {
            maxLowTrust = 1;
        }

        List<String> filtered = new ArrayList<>(merged.size());
        int removed = 0;
        int lowTrustTotal = 0;
        int lowTrustKept = 0;
        List<String> removedHostHashes = new ArrayList<>();
        List<String> keptHostHashes = new ArrayList<>();

        for (String s : merged) {
            String host = extractHost(s);
            boolean lowTrust = host != null && isLowTrustHost(host);
            if (lowTrust) {
                lowTrustTotal++;
            }
            if (lowTrust && lowTrustKept >= maxLowTrust) {
                removed++;
                if (removedHostHashes.size() < 6) {
                    removedHostHashes.add(crc32Hex(host));
                }
                continue;
            }

            if (lowTrust) {
                lowTrustKept++;
            }

            filtered.add(s);
            if (host != null && keptHostHashes.size() < 6) {
                keptHostHashes.add(crc32Hex(host));
            }
            if (filtered.size() >= topK) {
                break;
            }
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.removedCount",
                    removed);
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.keptCount",
                    filtered.size());
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.maxLowTrustRatio",
                    maxLowTrustRatio);
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.maxLowTrust",
                    maxLowTrust);
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.lowTrustKept",
                    lowTrustKept);
            EcosystemBufferPool.recordAmmoniaTrace(merged.size(), lowTrustTotal, ammoniaThreshold,
                    ammoniaQuarantineTag);
            TraceStore.put(
                    "web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.removedHostCrc32.sample",
                    String.join(",", removedHostHashes));
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.keptHostCrc32.sample",
                    String.join(",", keptHostHashes));
        } catch (Throwable ignored) {
            logSuppressed("lowTrustFilter.summary", ignored);
        }

        if (!filtered.isEmpty()) {
            return filtered;
        }

        try {
            TraceStore.put("web.failsoft.hybridEmptyFallback.demotion.nofilterSafe.lowTrustFilter.allFiltered", true);
        } catch (Throwable ignored) {
            logSuppressed("lowTrustFilter.allFiltered", ignored);
        }
        return merged;
    }

    private static void logSuppressed(String stage, Throwable ignored) {
        if (log.isDebugEnabled()) {
            String safeStage = com.example.lms.trace.SafeRedactor.traceLabelOrFallback(stage, "unknown");
            log.debug("[hybrid-empty-fallback] suppressed stage={}", safeStage);
        }
    }

    static String crc32Hex(String s) {
        if (s == null || s.isEmpty()) {
            return "00000000";
        }
        try {
            CRC32 crc = new CRC32();
            crc.update(s.getBytes(StandardCharsets.UTF_8));
            long v = crc.getValue();
            String hx = Long.toHexString(v);
            if (hx.length() >= 8) {
                return hx.substring(hx.length() - 8);
            }
            return "00000000".substring(hx.length()) + hx;
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.crc32", ignore);
            try {
                return Integer.toHexString(s.hashCode());
            } catch (Throwable ignore2) {
                WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.crc32Fallback", ignore2);
                return "00000000";
            }
        }
    }

    private static void addProbeCandidate(LinkedHashSet<String> out, String q, int maxCandidates) {
        if (out == null || maxCandidates <= 0 || out.size() >= maxCandidates) {
            return;
        }
        if (q == null) {
            return;
        }
        String t = collapseWhitespace(q);
        if (t.isEmpty()) {
            return;
        }
        out.add(t);
    }

    private static String collapseWhitespace(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return "";
        }
        try {
            return t.replaceAll("\\s+", " ").trim();
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.collapseWhitespace", ignore);
            return t;
        }
    }

    private static String stripSurroundingQuotes(String s) {
        String t = collapseWhitespace(s);
        if (t.isEmpty() || t.length() < 2) {
            return t;
        }
        String a = t;
        for (int i = 0; i < 2; i++) {
            if (a.length() < 2) {
                break;
            }
            char first = a.charAt(0);
            char last = a.charAt(a.length() - 1);

            boolean dq = (first == '"' && last == '"');
            boolean sq = (first == '\'' && last == '\'');
            boolean fancy = (first == '“' && last == '”');

            if (dq || sq || fancy) {
                a = collapseWhitespace(a.substring(1, a.length() - 1));
            } else {
                break;
            }
        }
        return a;
    }

    private static String stripTrailingSuffix(String s) {
        String t = collapseWhitespace(s);
        if (t.isEmpty()) {
            return t;
        }

        final String[] suffixes = new String[] {
                " 공식 홈페이지",
                " 공식홈페이지",
                " 공식 사이트",
                " 공식사이트",
                " 공식 문서",
                " 공식문서",
                " 홈페이지",
                " 사이트",
                " 공식",
                " 문서",
                " docs",
                " documentation",
                " api docs",
                " api documentation",
                " api",
                " official site",
                " official website",
                " official",
                " homepage",
                " website"
        };

        String cur = t;
        for (int step = 0; step < 3; step++) {
            String lower = cur.toLowerCase(java.util.Locale.ROOT);
            boolean removed = false;

            for (String suf : suffixes) {
                if (suf == null || suf.isEmpty()) {
                    continue;
                }
                String sufLower = suf.toLowerCase(java.util.Locale.ROOT);
                if (lower.endsWith(sufLower) && cur.length() > suf.length()) {
                    cur = collapseWhitespace(cur.substring(0, cur.length() - suf.length()));
                    removed = true;
                    break;
                }
            }

            if (!removed) {
                break;
            }
        }
        return cur;
    }

    private static String chopTailTokens(String s, int tokensToRemove) {
        if (tokensToRemove <= 0) {
            return collapseWhitespace(s);
        }
        String t = collapseWhitespace(s);
        if (t.isEmpty()) {
            return t;
        }
        if (!t.contains(" ")) {
            return t;
        }
        String[] parts = t.split(" ");
        if (parts.length <= tokensToRemove) {
            return t;
        }
        int newLen = parts.length - tokensToRemove;
        if (newLen <= 0) {
            return t;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < newLen; i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(parts[i]);
        }
        String out = collapseWhitespace(sb.toString());
        if (out.length() < 2) {
            return t;
        }
        return out;
    }

    private static String stripBracketContent(String s) {
        String t = collapseWhitespace(s);
        if (t.isEmpty()) {
            return t;
        }
        try {
            String x = t
                    .replaceAll("\\([^\\)]*\\)", " ")
                    .replaceAll("\\[[^\\]]*\\]", " ")
                    .replaceAll("\\{[^}]*\\}", " ");
            return collapseWhitespace(x);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.stripBracketContent", ignore);
            return t;
        }
    }

    private static String normalizePunct(String s) {
        String t = collapseWhitespace(s);
        if (t.isEmpty()) {
            return t;
        }
        try {
            String x = t
                    .replace("…", " ")
                    .replace("·", " ")
                    .replace("•", " ");
            x = x.replaceAll("[\\p{Punct}]+", " ");
            return collapseWhitespace(x);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.normalizePunct", ignore);
            return t;
        }
    }

    private static String normalizeForProbe(String s) {
        String t = stripTrailingSuffix(stripSurroundingQuotes(s));
        if (t.isEmpty()) {
            return "";
        }
        try {
            t = t.replace("\"", "")
                    .replace("“", "")
                    .replace("”", "")
                    .replace("'", "")
                    .trim();
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.normalizeForProbeQuoteStrip", ignore);
            // best-effort
        }
        try {
            t = t.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.normalizeForProbeLower", ignore);
            // best-effort
        }
        return collapseWhitespace(t);
    }

    private static String asString(Object v) {
        if (v == null) {
            return "";
        }
        try {
            return String.valueOf(v);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.asString", ignore);
            return "";
        }
    }

    private static String normalizeForGate(String s) {
        String t = collapseWhitespace(stripSurroundingQuotes(s));
        if (t.isEmpty()) {
            return "";
        }
        try {
            t = t.toLowerCase(java.util.Locale.ROOT);
        } catch (Throwable ignore) {
            WebFailSoftTraceSuppressions.trace("hybridEmptyFallbackSupport.normalizeForGateLower", ignore);
            // best-effort
        }
        t = stripSoakSuffix(t);
        t = stripTrailingPunct(t);

        if (t.length() > 512) {
            t = t.substring(0, 512);
        }
        return t;
    }

    private static String stripSoakSuffix(String t) {
        if (t == null || t.isBlank()) {
            return "";
        }

        String s = t;
        s = s.replaceAll("(해줘|해주세요|부탁해|부탁드립니다|알려줘|알려주세요|정리해줘|정리해주세요|조사해줘|조사해주세요|추천해줘|추천해주세요)\\s*$", "");
        s = s.replaceAll("(please|plz)\\s*$", "");

        return collapseWhitespace(s);
    }

    private static String stripTrailingPunct(String t) {
        if (t == null || t.isBlank()) {
            return "";
        }
        return t.replaceAll("[\\s\\p{Punct}]+$", "");
    }
}
