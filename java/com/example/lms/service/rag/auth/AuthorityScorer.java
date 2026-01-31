// src/main/java/com/example/lms/service/rag/auth/AuthorityScorer.java
package com.example.lms.service.rag.auth;

import com.example.lms.domain.enums.RerankSourceCredibility;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * URL 신뢰도(Authority)를 분류/감쇠 계수로 제공하는 서비스.
 *
 * <p>기본 동작:
 * 1) application.properties 의 search.authority.weights.* 를 먼저 탐색(선택).
 *    - 값 범위 [0,1]을 등급으로 매핑: >=0.95 OFFICIAL, >=0.75 TRUSTED, >=0.50 COMMUNITY, 그 외 UNVERIFIED
 * 2) 설정 매칭이 없으면 내장 휴리스틱으로 분류(정부/교육/벤더/문서, 메이저 미디어/백과, 커뮤니티/블로그 등).</p>
 *
 * <p><b>주의:</b> KR 포털(예: naver.com/daum.net)은 하위 서브도메인에 UGC(카페/블로그/지식iN)가 섞여 있어
 * 단순 {@code endsWith("naver.com")} 같은 규칙은 오분류를 유발한다. (DEV_COMMUNITY/DOCS로 잘못 라우팅되어
 * 증거 선택이 끊기는 패턴) 따라서 UGC 서브도메인은 명시적으로 UNVERIFIED로 하향한다.</p>
 */
@Component("authAuthorityScorer")
public class AuthorityScorer {
    private static final Logger log = LoggerFactory.getLogger(AuthorityScorer.class);

    /** domain → weight table loaded from configuration (lower-case). */
    private final Map<String, Double> table;

    /** Hard overrides (allow/deny) loaded from configuration. */
    private final Set<String> overrideOfficial;
    private final Set<String> overrideTrusted;
    private final Set<String> overrideCommunity;
    private final Set<String> overrideUnverified;

    // Tier weights (decay multipliers)
    private final double tierOfficial;
    private final double tierGuide;
    private final double tierWiki;
    private final double tierNews;
    private final double tierCommunity;
    private final double tierUnverified;

    public AuthorityScorer(
            @Value("${search.authority.weights:}") String legacyCsv,
            @Value("${search.authority.weights.official:}") String officialCsv,
            @Value("${search.authority.weights.wiki:}") String wikiCsv,
            @Value("${search.authority.weights.community:}") String communityCsv,
            @Value("${search.authority.weights.blog:}") String blogCsv,
            @Value("${search.authority.override.official:}") String overrideOfficialCsv,
            @Value("${search.authority.override.trusted:}") String overrideTrustedCsv,
            @Value("${search.authority.override.community:}") String overrideCommunityCsv,
            @Value("${search.authority.override.unverified:}") String overrideUnverifiedCsv,
            @Value("${authority.tier-weights.official:1.0}") double wOfficial,
            @Value("${authority.tier-weights.guide:0.85}")   double wGuide,
            @Value("${authority.tier-weights.wiki:0.80}")    double wWiki,
            @Value("${authority.tier-weights.news:0.70}")    double wNews,
            @Value("${authority.tier-weights.community:0.55}") double wCommunity,
            @Value("${authority.tier-weights.unverified:0.25}") double wUnverified
    ) {
        LinkedHashMap<String, Double> merged = new LinkedHashMap<>();
        merged.putAll(parse(officialCsv));
        merged.putAll(parse(wikiCsv));
        merged.putAll(parse(communityCsv));
        merged.putAll(parse(blogCsv));

        // 새 설정이 비어있으면 레거시 사용
        if (merged.isEmpty()) {
            merged.putAll(parse(legacyCsv));
        }

        this.table = Collections.unmodifiableMap(merged);

        this.overrideOfficial = Collections.unmodifiableSet(parseDomainList(overrideOfficialCsv));
        this.overrideTrusted  = Collections.unmodifiableSet(parseDomainList(overrideTrustedCsv));
        this.overrideCommunity = Collections.unmodifiableSet(parseDomainList(overrideCommunityCsv));
        this.overrideUnverified = Collections.unmodifiableSet(parseDomainList(overrideUnverifiedCsv));

        this.tierOfficial  = clamp(wOfficial);
        this.tierGuide     = clamp(wGuide);
        this.tierWiki      = clamp(wWiki);
        this.tierNews      = clamp(wNews);
        this.tierCommunity = clamp(wCommunity);
        this.tierUnverified = clamp(wUnverified);

        if (table.isEmpty()) {
            log.info("[AuthorityScorer] No explicit weights loaded. Using heuristic-only classification.");
        } else {
            log.info("[AuthorityScorer] Loaded {} domain weight entries.", table.size());
        }


        int o1 = overrideOfficial != null ? overrideOfficial.size() : 0;
        int o2 = overrideTrusted != null ? overrideTrusted.size() : 0;
        int o3 = overrideCommunity != null ? overrideCommunity.size() : 0;
        int o4 = overrideUnverified != null ? overrideUnverified.size() : 0;
        if (o1 + o2 + o3 + o4 > 0) {
            log.info("[AuthorityScorer] Loaded override sets: official={}, trusted={}, community={}, unverified={}", o1, o2, o3, o4);
        }
    }

    /** (하위호환) weightFor는 등급·감쇠 매핑으로 위임한다. */
    @Deprecated
    public double weightFor(String url) {
        return decayFor(getSourceCredibility(url));
    }

    /** URL을 신뢰도 등급으로 분류한다. */
    public RerankSourceCredibility getSourceCredibility(String url) {
        String host = host(url);
        if (host == null || host.isBlank()) {
            return RerankSourceCredibility.UNVERIFIED;
        }
        String h = normalizeHost(host);
        if (h == null || h.isBlank()) {
            return RerankSourceCredibility.UNVERIFIED;
        }

        // 0) hard overrides (allow/deny) before table/heuristics
        RerankSourceCredibility o = overrideCredibility(h);
        if (o != null) {
            return o;
        }

        // 1) 설정 테이블 매칭 → 수치 → 등급
        Double configured = bestMatchingWeight(h, table);
        if (configured != null) {
            return mapWeightToCredibility(configured);
        }

        // 2) 내장 휴리스틱

        // 정부/교육
        if (isGovOrEdu(h)) {
            return RerankSourceCredibility.OFFICIAL;
        }

        // Vendor-hosted community forums should not be treated as "OFFICIAL docs".
        // (They are still high-signal, but user-generated.)
        if (h.equals("community.openai.com")) {
            return RerankSourceCredibility.COMMUNITY;
        }

        // KR portals: avoid over-promoting UGC subdomains.
        RerankSourceCredibility portalCred = portalCredibilityOverride(h);
        if (portalCred != null) {
            return portalCred;
        }

        // KR finance disclosure & regulator sites (high authority, should be OFFICIAL).
        if (h.equals("dart.fss.or.kr")
                || h.equals("opendart.fss.or.kr")
                || h.equals("fss.or.kr")
                || h.endsWith(".fss.or.kr")
                || h.equals("kind.krx.co.kr")
                || h.equals("krx.co.kr")
                || h.endsWith(".krx.co.kr")
                || h.equals("fs.moef.go.kr")
                || h.equals("www.fsc.go.kr")
                || h.endsWith(".fsc.go.kr")) {
            return RerankSourceCredibility.OFFICIAL;
        }

        // KR company profile directories (useful for entity/company lookups)
        if (h.equals("rocketpunch.com") || h.endsWith(".rocketpunch.com")
                || h.equals("jobplanet.co.kr") || h.endsWith(".jobplanet.co.kr")
                || h.equals("jobkorea.co.kr") || h.endsWith(".jobkorea.co.kr")
                || h.equals("incruit.com") || h.endsWith(".incruit.com")
                || h.equals("saramin.co.kr") || h.endsWith(".saramin.co.kr")
                || h.equals("wanted.co.kr") || h.endsWith(".wanted.co.kr")
                || h.equals("jumpit.co.kr") || h.endsWith(".jumpit.co.kr")
                || h.equals("catch.co.kr") || h.endsWith(".catch.co.kr")
                || h.equals("thevc.kr") || h.endsWith(".thevc.kr")
                || h.equals("bizno.net") || h.endsWith(".bizno.net")) {
            return RerankSourceCredibility.TRUSTED;
        }



        // Major platform domains (often used as primary sources for profile/channel pages).
        // These are not "OFFICIAL docs", but are typically higher signal than random blogs.
        if (h.equals("twitch.tv") || h.endsWith(".twitch.tv")
                || h.equals("watcha.com") || h.endsWith(".watcha.com")) {
            return RerankSourceCredibility.TRUSTED;
        }

        boolean isGoogleFamily =
                h.equals("google.dev") || h.endsWith(".google.dev")
                        || h.equals("google.com") || h.endsWith(".google.com")
                        || h.equals("googleapis.com") || h.endsWith(".googleapis.com");

        boolean isMajorVendor =
                isGoogleFamily
                        || h.equals("openai.com") || h.endsWith(".openai.com")
                        || h.equals("microsoft.com") || h.endsWith(".microsoft.com")
                        || h.equals("apple.com") || h.endsWith(".apple.com")
                        || h.equals("oracle.com") || h.endsWith(".oracle.com")
                        || h.equals("spring.io") || h.endsWith(".spring.io")
                        || h.equals("anthropic.com") || h.endsWith(".anthropic.com");

        // Generic blog platforms / UGC hosts (exclude major vendor families)
        if (isBlogOrUgcHost(h) && !isMajorVendor) {
            return RerankSourceCredibility.UNVERIFIED;
        }

        // OFFICIAL: vendor/government/education/documentation
        if (isMajorVendor
                || h.equals("apache.org") || h.endsWith(".apache.org")
                || h.startsWith("developer.") || h.contains(".docs.") || h.startsWith("docs.")
        ) {
            return RerankSourceCredibility.OFFICIAL;
        }

        // TRUSTED: major media / encyclopedias (general)
        if (h.equals("reuters.com") || h.endsWith(".reuters.com")
                || h.equals("bbc.com") || h.endsWith(".bbc.com")
                || h.equals("bloomberg.com") || h.endsWith(".bloomberg.com")
                || h.equals("nytimes.com") || h.endsWith(".nytimes.com")
                || h.equals("wsj.com") || h.endsWith(".wsj.com")
                || h.equals("britannica.com") || h.endsWith(".britannica.com")
                || h.equals("wikipedia.org") || h.endsWith(".wikipedia.org")
        ) {
            return RerankSourceCredibility.TRUSTED;
        }

        // COMMUNITY: dev Q&A / issue trackers / community platforms
        if (h.equals("github.com") || h.endsWith(".github.com")
                || h.equals("gitlab.com") || h.endsWith(".gitlab.com")
                || h.equals("bitbucket.org") || h.endsWith(".bitbucket.org")
                || h.equals("stackoverflow.com") || h.endsWith(".stackoverflow.com")
                || h.equals("stackexchange.com") || h.endsWith(".stackexchange.com")
                || h.equals("reddit.com") || h.endsWith(".reddit.com")
                || h.equals("medium.com") || h.endsWith(".medium.com")
                || h.equals("hashnode.com") || h.endsWith(".hashnode.com")
                || h.equals("dev.to") || h.endsWith(".dev.to")
        ) {
            return RerankSourceCredibility.COMMUNITY;
        }

        return RerankSourceCredibility.UNVERIFIED;
    }

    /** 등급별 지수 감쇠 상수(OFFICIAL=1.0 ... UNVERIFIED=0.25). */
    public double decayFor(RerankSourceCredibility credibility) {
        if (credibility == null) {
            return tierUnverified;
        }
        return switch (credibility) {
            case OFFICIAL   -> tierOfficial;
            case TRUSTED    -> {
                // Trusted sources encompass guide/wiki/news categories.
                double avg = (tierGuide + tierWiki + tierNews) / 3.0;
                yield clamp(avg);
            }
            case COMMUNITY  -> tierCommunity;
            case UNVERIFIED -> tierUnverified;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private RerankSourceCredibility overrideCredibility(String normalizedHost) {
        if (normalizedHost == null || normalizedHost.isBlank()) return null;
        String h = normalizedHost;
        // Allow "deny" to win
        if (matchesAny(h, overrideUnverified)) return RerankSourceCredibility.UNVERIFIED;
        if (matchesAny(h, overrideOfficial)) return RerankSourceCredibility.OFFICIAL;
        if (matchesAny(h, overrideTrusted)) return RerankSourceCredibility.TRUSTED;
        if (matchesAny(h, overrideCommunity)) return RerankSourceCredibility.COMMUNITY;
        return null;
    }

    private static boolean matchesAny(String host, Set<String> suffixes) {
        if (host == null || host.isBlank() || suffixes == null || suffixes.isEmpty()) return false;
        for (String s : suffixes) {
            if (s == null || s.isBlank()) continue;
            String suf = normalizeDomainToken(s);
            if (suf == null) continue;
            if (host.equals(suf) || host.endsWith("." + suf)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> parseDomainList(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String tok : csv.split("[,\\s]+")) {
            if (tok == null) continue;
            String t = tok.trim();
            if (t.isEmpty()) continue;
            // inline comments
            int hash = t.indexOf('#');
            if (hash >= 0) t = t.substring(0, hash).trim();
            if (t.isEmpty()) continue;
            String n = normalizeDomainToken(t);
            if (n != null && !n.isBlank()) out.add(n);
        }
        return out;
    }

    private static String normalizeDomainToken(String token) {
        if (token == null) return null;
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) return null;
        // strip scheme
        if (t.startsWith("http://")) t = t.substring(7);
        if (t.startsWith("https://")) t = t.substring(8);
        // strip leading dots
        while (t.startsWith(".")) t = t.substring(1);
        // strip path
        int slash = t.indexOf('/');
        if (slash >= 0) t = t.substring(0, slash);
        // strip port
        int colon = t.indexOf(':');
        if (colon >= 0) t = t.substring(0, colon);
        // strip trailing dots
        while (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        if (t.isEmpty()) return null;
        if (t.startsWith("www.")) t = t.substring(4);
        return t;
    }

    /** CSV "domain:weight,domain2:weight" → Map */
    private static Map<String, Double> parse(String csv) {
        if (csv == null || csv.isBlank()) return Map.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && s.contains(":"))
                .map(s -> s.split(":", 2))
                .collect(Collectors.toMap(
                        parts -> parts[0].trim().toLowerCase(Locale.ROOT),
                        parts -> {
                            try {
                                return clamp(Double.parseDouble(parts[1].trim()));
                            } catch (Exception e) {
                                return 0.5; // 파싱 실패 시 중립
                            }
                        },
                        (oldV, newV) -> newV,
                        LinkedHashMap::new
                ));
    }

    private static boolean isGovOrEdu(String host) {
        return host.endsWith(".go.kr")
                || host.endsWith(".ac.kr")
                || host.endsWith(".gov")
                || host.endsWith(".edu")
                || host.contains(".edu.");
    }

    private static boolean isBlogOrUgcHost(String h) {
        if (h == null || h.isBlank()) return false;
        // common patterns
        if (h.contains("blog.") || h.startsWith("blog.")) return true;
        // KR blog platforms
        if (h.endsWith("tistory.com") || h.endsWith("velog.io") || h.endsWith("brunch.co.kr")) return true;
        // portal UGC explicitly
        if (h.endsWith("blog.naver.com") || h.endsWith("cafe.naver.com")
                || h.endsWith("kin.naver.com") || h.endsWith("post.naver.com")) return true;
        if (h.endsWith("blog.daum.net") || h.endsWith("cafe.daum.net")) return true;
        // generic UGC platforms
        return h.endsWith("wordpress.com") || h.endsWith("blogspot.com");
    }

    /**
     * KR portal overrides: return non-null when the host belongs to a portal family.
     * The goal is to avoid marking UGC subdomains as TRUSTED.
     */
    private static RerankSourceCredibility portalCredibilityOverride(String h) {
        if (h == null) return null;

        // NAVER
        if (h.equals("naver.com") || h.endsWith(".naver.com")) {
            // UGC → UNVERIFIED
            if (h.endsWith("blog.naver.com") || h.endsWith("cafe.naver.com")
                    || h.endsWith("kin.naver.com") || h.endsWith("post.naver.com")
                    || h.endsWith("m.blog.naver.com") || h.endsWith("m.cafe.naver.com")
            ) {
                return RerankSourceCredibility.UNVERIFIED;
            }
            // Dev/docs/engineering subdomains (high signal)
            if (h.equals("developers.naver.com")) {
                return RerankSourceCredibility.OFFICIAL;
            }
            if (h.equals("d2.naver.com")) {
                return RerankSourceCredibility.TRUSTED;
            }
            if (h.equals("news.naver.com")) {
                return RerankSourceCredibility.TRUSTED;
            }
            // Naver streaming platform (Chzzk) - trusted platform pages.
            if (h.equals("chzzk.naver.com") || h.equals("m.chzzk.naver.com")) {
                return RerankSourceCredibility.TRUSTED;
            }
            // root portal
            if (h.equals("naver.com") || h.equals("www.naver.com")) {
                return RerankSourceCredibility.TRUSTED;
            }
            // default: do not auto-promote arbitrary naver subdomains
            return RerankSourceCredibility.UNVERIFIED;
        }

        // DAUM
        if (h.equals("daum.net") || h.endsWith(".daum.net")) {
            if (h.endsWith("blog.daum.net") || h.endsWith("cafe.daum.net")) {
                return RerankSourceCredibility.UNVERIFIED;
            }
            if (h.equals("daum.net") || h.equals("www.daum.net")) {
                return RerankSourceCredibility.TRUSTED;
            }
            return RerankSourceCredibility.UNVERIFIED;
        }

        return null;
    }

    /** URL → host 안전 추출 */
    private static String host(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            return new URI(url).getHost();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static String normalizeHost(String h) {
        if (h == null || h.isBlank()) {
            return null;
        }
        String lower = h.toLowerCase(Locale.ROOT).trim();
        if (lower.startsWith("www.")) {
            lower = lower.substring(4);
        }
        return lower;
    }

    /** [0,1]로 클램프 */
    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    /** 설정 테이블에서 가장 잘 매칭되는 가중치 선택(가장 높은 값 우선) */
    private static Double bestMatchingWeight(String host, Map<String, Double> table) {
        if (table == null || table.isEmpty()) return null;
        if (host == null || host.isBlank()) return null;

        String hNorm = normalizeHost(host);
        Double best = null;
        for (Map.Entry<String, Double> e : table.entrySet()) {
            String key = e.getKey();
            if (key == null || key.isBlank()) continue;
            String kNorm = normalizeHost(key);
            if (kNorm == null) continue;
            if (hNorm.equals(kNorm) || hNorm.endsWith("." + kNorm)) {
                if (best == null || e.getValue() > best) {
                    best = e.getValue();
                }
            }
        }
        return best;
    }

    /** 수치 가중치 → 등급 임계값 매핑 */
    private static RerankSourceCredibility mapWeightToCredibility(double w) {
        double v = clamp(w);
        if (v >= 0.95) return RerankSourceCredibility.OFFICIAL;
        if (v >= 0.75) return RerankSourceCredibility.TRUSTED;
        if (v >= 0.50) return RerankSourceCredibility.COMMUNITY;
        return RerankSourceCredibility.UNVERIFIED;
    }
}
