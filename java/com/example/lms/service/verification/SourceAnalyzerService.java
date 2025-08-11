// src/main/java/com/example/lms/service/verification/SourceAnalyzerService.java
package com.example.lms.service.verification;

import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.service.rag.auth.AuthorityScorer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 출처 신뢰도 메타 점검 서비스 (통합판)
 *
 * 판정 흐름(우선순위):
 *  1) 텍스트 충돌 단서 → CONFLICTING
 *  2) AuthorityScorer 가중치 분포(있으면) → OFFICIAL/RELIABLE_WIKI/FAN_MADE_SPECULATION/CONFLICTING/UNKNOWN
 *  3) 휴리스틱 폴백:
 *     - 공식 도메인 포함 → OFFICIAL
 *     - 루머/팬메이드 강한 단서 or 커뮤니티·블로그 비율 과다(공식 0) → FAN_MADE_SPECULATION
 *     - 공식 & 커뮤니티 혼재 + 충돌 단서 → CONFLICTING
 *     - 그 외 → safeOk()  (enum 차이를 고려해 OK 또는 TRUSTED 또는 UNKNOWN)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceAnalyzerService {

    private final ObjectProvider<AuthorityScorer> authorityScorerProvider;

    // --- 텍스트 단서 패턴 ---
    private static final Pattern CONFLICT_TXT =
            Pattern.compile("(?i)(상반|엇갈|모순|상충|논란|서로\\s*다르|conflict)");
    private static final Pattern FAN_CUE =
            Pattern.compile("(?i)(fan[- ]?made|추측|루머|유출|소문|rumor|leak|unconfirmed|speculation)");
    private static final Pattern OFFICIAL_DOMAIN_TXT =
            Pattern.compile("(?i)(hoyoverse\\.com|hoyolab\\.com|genshin\\.hoyoverse\\.com|\\.go\\.kr|\\.ac\\.kr|\\.gov|\\.edu)");

    // --- 가중치 임계값 (AuthorityScorer) ---
    private static final double HIGH_T = 0.90;  // 고신뢰 컷
    private static final double WIKI_T = 0.70;  // 위키 컷
    private static final double LOW_T  = 0.40;  // 저신뢰 컷

    private static final double OFFICIAL_RATIO = 0.50; // high 비율 ≥ 50%
    private static final double WIKI_RATIO     = 0.50; // wiki 비율 ≥ 50%
    private static final double WIKI_HIGH_MIN  = 0.10; // wiki 판정 시 high 최소 비율
    private static final double FAN_LOW_RATIO  = 0.50; // low 비율 ≥ 50%
    private static final double FAN_HIGH_MAX   = 0.10; // fan 판정 시 high 최대 비율
    private static final double CONFLICT_MIN   = 0.25; // high/low 동시 존재 비율

    public SourceCredibility analyze(String question, String context) {
        if (context == null || context.isBlank()) {
            return safeOk(); // 빈 컨텍스트는 보수적으로 OK/TRUSTED/UNKNOWN 중 enum 호환
        }

        // 1) 텍스트 충돌 단서 즉시 판정
        if (CONFLICT_TXT.matcher(context).find()) {
            return enumOrFallback(SourceCredibility.CONFLICTING);
        }

        // 2) URL 기반 정밀 판정 (가능 시 우선)
        List<String> urls = extractUrls(context);
        AuthorityScorer scorer = authorityScorerProvider.getIfAvailable();
        if (scorer != null && !urls.isEmpty()) {
            try {
                SourceCredibility judged = judgeByWeightDistribution(scorer, urls);
                if (judged != null && judged != SourceCredibility.UNKNOWN) {
                    return judged;
                }
            } catch (Exception e) {
                log.debug("AuthorityScorer 평가 실패(폴백 사용): {}", e.toString());
            }
        }

        // 3) 휴리스틱 폴백(도메인/루머/비율)
        if (OFFICIAL_DOMAIN_TXT.matcher(context).find()) {
            return enumOrFallback(SourceCredibility.OFFICIAL);
        }

        HeuristicTally tally = tallyByHosts(context);
        double commRatio = tally.total == 0 ? 0.0 : (tally.community + tally.blog) / (double) tally.total;

        if (tally.rumorCue >= 2 || (commRatio >= 0.75 && tally.official == 0)) {
            return enumOrFallback(SourceCredibility.FAN_MADE_SPECULATION);
        }
        if (tally.official > 0 && tally.community > 0 && hasConflictCue(context)) {
            return enumOrFallback(SourceCredibility.CONFLICTING);
        }

        return safeOk();
    }

    // --- AuthorityScorer 분포 판정 ---
    private SourceCredibility judgeByWeightDistribution(AuthorityScorer scorer, List<String> urls) {
        int total = 0, high = 0, wiki = 0, low = 0;

        for (String u : urls) {
            double w;
            try {
                w = scorer.weightFor(u);
            } catch (Exception e) {
                w = 0.5; // 평가 실패 시 중립
            }
            total++;
            if (w >= HIGH_T)       high++;
            else if (w >= WIKI_T)  wiki++;
            else if (w <= LOW_T)   low++;
        }
        if (total == 0) return SourceCredibility.UNKNOWN;

        double highR = (double) high / total;
        double wikiR = (double) wiki / total;
        double lowR  = (double) low  / total;

        boolean conflicting = (highR >= CONFLICT_MIN) && (lowR >= CONFLICT_MIN);

        if (highR >= OFFICIAL_RATIO)                        return enumOrFallback(SourceCredibility.OFFICIAL);
        if (wikiR  >= WIKI_RATIO && highR >= WIKI_HIGH_MIN) return enumOrFallback(SourceCredibility.RELIABLE_WIKI);
        if (lowR   >= FAN_LOW_RATIO && highR < FAN_HIGH_MAX) return enumOrFallback(SourceCredibility.FAN_MADE_SPECULATION);
        if (conflicting)                                     return enumOrFallback(SourceCredibility.CONFLICTING);

        return SourceCredibility.UNKNOWN;
    }

    // --- 휴리스틱: URL/호스트 집계 ---
    private HeuristicTally tallyByHosts(String context) {
        HeuristicTally t = new HeuristicTally();
        for (String line : context.split("\\R+")) {
            if (line.isBlank()) continue;
            String url = extractUrlLoose(line);
            String host = host(url);
            if (host != null) {
                String h = host.toLowerCase(Locale.ROOT);
                if (isOfficial(h)) t.official++;
                else if (isCommunity(h)) t.community++;
                else if (isBlog(h)) t.blog++;
                t.total++;
            }
            if (containsRumorCue(line)) t.rumorCue++;
        }
        return t;
    }

    // --- URL 추출 (여러 줄 텍스트에서) ---
    public static List<String> extractUrls(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher a = Pattern.compile("href\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE).matcher(text);
        while (a.find()) out.add(a.group(1));
        Matcher b = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)", Pattern.CASE_INSENSITIVE).matcher(text);
        while (b.find()) out.add(b.group(1));
        return new ArrayList<>(out);
    }

    // 한 줄에서 URL 1개 느슨 추출 (휴리스틱 집계용)
    private static String extractUrlLoose(String text) {
        if (text == null) return null;
        int a = text.indexOf("href=\"");
        if (a >= 0) {
            int s = a + 6, e = text.indexOf('"', s);
            if (e > s) return text.substring(s, e);
        }
        int http = text.indexOf("http");
        if (http >= 0) {
            int sp = text.indexOf(' ', http);
            return sp > http ? text.substring(http, sp) : text.substring(http);
        }
        return null;
    }

    private static String host(String url) {
        if (url == null) return null;
        try { return URI.create(url).getHost(); }
        catch (Exception ignore) { return null; }
    }

    // --- 도메인 분류 규칙 ---
    private static boolean isOfficial(String h) {
        return h.endsWith(".go.kr") || h.endsWith(".ac.kr") || h.contains(".gov") || h.contains(".edu")
                || h.contains("hoyoverse.com") || h.contains("hoyolab.com");
    }
    private static boolean isCommunity(String h) {
        return h.contains("fandom.com") || h.contains("dcinside.com") || h.contains("arca.live") || h.contains("reddit.com");
    }
    private static boolean isBlog(String h) {
        return h.contains("blog.naver.com") || h.contains("tistory.com") || h.contains("medium.com");
    }

    private static boolean containsRumorCue(String s) {
        if (s == null) return false;
        String t = s.toLowerCase(Locale.ROOT);
        return t.contains("루머") || t.contains("유출") || t.contains("추정") || t.contains("소문")
                || t.contains("rumor") || t.contains("leak") || t.contains("unconfirmed") || t.contains("fan made") || t.contains("추측");
    }
    private static boolean hasConflictCue(String ctx) {
        if (ctx == null) return false;
        String t = ctx.toLowerCase(Locale.ROOT);
        return t.contains("상반") || t.contains("엇갈") || t.contains("모순") || t.contains("다르다") || t.contains("논란") || t.contains("상충") || t.contains("conflict");
    }

    // --- enum 호환 안전 반환 ---
    private static SourceCredibility safeOk() {
        // 우선 OK → 실패 시 TRUSTED → 그래도 없으면 UNKNOWN
        try { return SourceCredibility.valueOf("OK"); }
        catch (Exception ignored1) {
            try { return SourceCredibility.valueOf("TRUSTED"); }
            catch (Exception ignored2) { return SourceCredibility.UNKNOWN; }
        }
    }
    private static SourceCredibility enumOrFallback(SourceCredibility v) {
        try {
            // enum 상수 존재 시 그대로 사용
            SourceCredibility.valueOf(v.name());
            return v;
        } catch (Exception e) {
            // 존재하지 않는 상수면 최대한 의미가 가까운 쪽으로 폴백
            switch (v.name()) {
                case "OFFICIAL":                return safeOk();
                case "RELIABLE_WIKI":           return safeOk();
                case "FAN_MADE_SPECULATION":    return SourceCredibility.UNKNOWN;
                case "CONFLICTING":             return SourceCredibility.UNKNOWN;
                default:                        return SourceCredibility.UNKNOWN;
            }
        }
    }

    // 집계 구조체
    private static class HeuristicTally {
        int total = 0;
        int official = 0;
        int community = 0;
        int blog = 0;
        int rumorCue = 0;
    }
}
