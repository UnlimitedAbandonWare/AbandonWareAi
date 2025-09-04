package com.example.lms.gptsearch.web;

import com.example.lms.query.config.AiQueryProperties;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.util.CollectionUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 멀티 프로바이더 웹 검색 어그리게이터.
 * <p>등록된 Provider들을 순회 호출하여 결과를 모은 뒤 URL 기준으로 dedup하고, 스니펫에서 날짜를 추출하여 최신성 가중치를 부여한다. 또한
 * {@code alias.yml} 또는 expert 메타 정보에서 제공하는 도메인 allow/deny 정책을 적용한다. 모든 예외는 fail-soft하며 결과 수집을
 * 중단하지 않는다.</p>
 */
/**
 * This bean is only activated when feature.gptsearch.enabled=true.  Without
 * this conditional registration the GPT search providers may interfere with
 * the primary retrieval stack when disabled (ultra profile).
 */
@ConditionalOnProperty(value = "feature.gptsearch.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class MultiWebSearch {
    public record Result(String title, String url, String snippet, Instant publishedAt,
                         double score, Map<String, Object> meta) {
        public Result withScore(double s) {
            return new Result(title, url, snippet, publishedAt, s, meta);
        }
        public double score() { return score; }
    }
    public interface Provider {
        String name();
        List<Result> search(String query, int topK, Map<String, Object> meta);
    }
    private final List<Provider> providers;
    private final AiQueryProperties props;
    public MultiWebSearch(List<Provider> providers, AiQueryProperties props) {
        this.providers = (providers == null) ? List.of() : providers;
        this.props = props;
    }
    public List<Result> searchAggregated(String query, int topK, Map<String, Object> meta) {
        if (topK <= 0) topK = 8;
        // 1) 공급자별 결과 수집
        Map<String, List<Result>> byProvider = new LinkedHashMap<>();
        for (Provider p : providers) {
            try {
                List<Result> r = p.search(query, Math.max(4, topK), meta);
                if (!CollectionUtils.isEmpty(r)) {
                    byProvider.put(p.name(), r);
                }
            } catch (Exception ignore) {
                // fail-soft
            }
        }
        // 2) 가중 Reciprocal Rank Fusion (RRF): score += w_p / (k + rank)
        final int k = 60;
        Map<String, Double> score = new LinkedHashMap<>();
        Map<String, Result> first = new LinkedHashMap<>();
        Locale locale = Locale.KOREA; // 한국어 기본
        for (Map.Entry<String, List<Result>> e : byProvider.entrySet()) {
            final String provider = e.getKey();
            final double w = providerWeight(provider, locale);
            int rank = 0;
            for (Result r : e.getValue()) {
                String key = dedupKey(r.url());
                if (key == null) continue;
                first.putIfAbsent(key, r);
                score.merge(key, w / (k + (++rank)), Double::sum);
            }
        }
        // 3) 도메인 allow/deny 정책 수립
        Set<String> allowTmp = metaList(meta, "expert.web.allow");
        Set<String> denyTmp  = metaList(meta, "expert.web.deny");
        if (allowTmp.isEmpty() && denyTmp.isEmpty() && props != null && props.getDomains() != null) {
            allowTmp = toSet(props.getDomains().getAllow());
            denyTmp  = toSet(props.getDomains().getDeny());
        }
        final Set<String> allow = allowTmp;
        final Set<String> deny  = denyTmp;
        // 4) 최신성 보정 후 최종 정렬
        int boostDays = (props != null && props.getRecency() != null) ? props.getRecency().getBoostDays() : 60;
        double maxBonus = (props != null && props.getRecency() != null) ? props.getRecency().getMaxBonus() : 0.30;
        return score.entrySet().stream()
                .map(e -> {
                    Result base = first.get(e.getKey());
                    double s = e.getValue() + recencyBoost(base, boostDays, maxBonus);
                    return base.withScore(s);
                })
                .filter(r -> domainAllowed(r.url(), allow, deny))
                .sorted(Comparator.comparingDouble(Result::score).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    /** 공급자 가중치: 로케일/콘텐츠 특성 기반 보수적 프리셋 */
    private static double providerWeight(String name, Locale locale) {
        if (name == null) return 1.0;
        String n = name.toLowerCase(Locale.ROOT);
        // KOR: NAVER > Google > Bing > 기타
        if (n.contains("naver"))   return 1.25;
        if (n.contains("google"))  return 1.10;
        if (n.contains("bing"))    return 1.00;
        if (n.contains("tavily") || n.contains("serp")) return 0.95;
        return 1.00;
    }
    private static Set<String> metaList(Map<String,Object> meta, String key) {
        if (meta == null) return Set.of();
        Object v = meta.get(key);
        if (v instanceof Collection<?> c) {
            Set<String> s = new HashSet<>();
            for (Object o : c) if (o != null) s.add(o.toString().toLowerCase());
            return s;
        }
        return Set.of();
    }
    private static Set<String> toSet(List<String> in) {
        if (in == null) return Set.of();
        Set<String> s = new HashSet<>();
        for (String v : in) if (v != null) s.add(v.toLowerCase());
        return s;
    }
    private static String dedupKey(String url) {
        try {
            URI u = new URI(url);
            String host = Optional.ofNullable(u.getHost()).orElse("");
            String path = Optional.ofNullable(u.getPath()).orElse("");
            if (host.isEmpty()) return null;
            return host.toLowerCase() + "|" + path;
        } catch (URISyntaxException e) {
            return null;
        }
    }
    private static boolean domainAllowed(String url, Set<String> allow, Set<String> deny) {
        String host;
        try { host = new URI(url).getHost(); } catch (Exception e) { host = null; }
        if (host == null) return true;
        host = host.toLowerCase();
        if (!deny.isEmpty())  for (String d : deny)  if (host.contains(d))  return false;
        if (!allow.isEmpty()) {
            boolean hit = false;
            for (String a : allow) if (host.contains(a)) hit = true;
            if (!hit) return false;
        }
        return true;
    }
    private static final Pattern DATE_PAT =
            Pattern.compile("\\b(20\\d{2})[-./](0?[1-9]|1[0-2])[-./](0?[1-9]|[12]\\d|3[01])\\b");
    private static double recencyBoost(Result r, int boostDays, double maxBonus) {
        Instant base = r.publishedAt();
        if (base == null) base = inferDateFromText(r.snippet());
        if (base == null) return 0.0;
        long days = Math.max(0, (Instant.now().getEpochSecond() - base.getEpochSecond()) / 86400);
        if (days >= boostDays) return 0.0;
        double ratio = 1.0 - (double) days / (double) boostDays;
        return Math.min(maxBonus, maxBonus * ratio);
    }
    private static Instant inferDateFromText(String snippet) {
        if (snippet == null) return null;
        Matcher m = DATE_PAT.matcher(snippet);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            int M = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            try {
                return LocalDate.of(y, M, d).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) { }
        }
        return null;
    }
}