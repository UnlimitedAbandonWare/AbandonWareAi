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
 * 출처 신뢰도를 종합적으로 분석하는 서비스.
 *
 * <p>분석 우선순위:
 * <ol>
 * <li><b>텍스트 단서:</b> 내용에 명백한 '충돌', '모순' 키워드가 있으면 즉시 CONFLICTING 판정.</li>
 * <li><b>AuthorityScorer 정밀 분석:</b> 출처 URL을 기반으로 가중치를 평가하여 OFFICIAL, RELIABLE_WIKI 등으로 정밀 판정.</li>
 * <li><b>휴리스틱 폴백:</b> 위 방법이 불가능할 경우, 도메인 종류(공식, 위키, 커뮤니티) 비율과 루머 키워드 등을 종합해 추정.</li>
 * </ol>
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SourceAnalyzerService {

    private final ObjectProvider<AuthorityScorer> authorityScorerProvider;

    // --- 텍스트 단서 패턴 (두 버전의 키워드 통합) ---
    private static final Pattern CONFLICT_TXT = Pattern.compile(
            "(?i)(상반|엇갈|모순|상충|논란|서로\\s*다르|반박|conflict|contradict)");
    private static final Pattern FAN_CUE = Pattern.compile(
            "(?i)(fan[- ]?made|추측|루머|유출|소문|datamine|datamining|rumor|leak|unconfirmed|speculation)");
    private static final Pattern OFFICIAL_DOMAIN_TXT = Pattern.compile(
            "(?i)(hoyoverse\\.com|hoyolab\\.com|genshin\\.hoyoverse\\.com|\\.go\\.kr|\\.ac\\.kr|\\.gov|\\.edu)");

    // --- AuthorityScorer 가중치 임계값 ---
    private static final double HIGH_T = 0.90;  // 공식
    private static final double WIKI_T = 0.70;  // 신뢰도 높은 위키
    private static final double LOW_T  = 0.40;  // 팬 커뮤니티/블로그
    private static final double OFFICIAL_RATIO = 0.50;
    private static final double WIKI_RATIO     = 0.50;
    private static final double WIKI_HIGH_MIN  = 0.10;
    private static final double FAN_LOW_RATIO  = 0.50;
    private static final double FAN_HIGH_MAX   = 0.10;
    private static final double CONFLICT_MIN   = 0.25;

    /**
     * 질문과 컨텍스트를 기반으로 출처의 신뢰도를 분석합니다.
     */
    public SourceCredibility analyze(String question, String context) {
        if (context == null || context.isBlank()) {
            return SourceCredibility.UNKNOWN;
        }

        // 1. 텍스트에서 명백한 충돌 단서 확인
        if (CONFLICT_TXT.matcher(context).find()) {
            return SourceCredibility.CONFLICTING;
        }

        // 2. AuthorityScorer를 이용한 URL 기반 정밀 분석
        AuthorityScorer scorer = authorityScorerProvider.getIfAvailable();
        List<String> urls = extractUrls(context);
        if (scorer != null && !urls.isEmpty()) {
            try {
                SourceCredibility judged = judgeByWeightDistribution(scorer, urls);
                if (judged != SourceCredibility.UNKNOWN) {
                    return judged;
                }
            } catch (Exception e) {
                log.debug("AuthorityScorer 평가 실패 (휴리스틱으로 폴백): {}", e.toString());
            }
        }

        // 3. 휴리스틱 폴백 분석
        if (OFFICIAL_DOMAIN_TXT.matcher(context).find()) {
            return SourceCredibility.OFFICIAL;
        }

        HeuristicTally tally = tallyByHosts(context);
        if (tally.total == 0) { // URL 정보가 전혀 없으면 추측성 키워드로만 판단
            return FAN_CUE.matcher(context).find() ? SourceCredibility.FAN_MADE_SPECULATION : SourceCredibility.UNKNOWN;
        }

        double fanRatio = (double) (tally.community + tally.blog) / tally.total;

        if (FAN_CUE.matcher(context).find() || (fanRatio >= 0.75 && tally.official == 0)) {
            return SourceCredibility.FAN_MADE_SPECULATION;
        }
        if (tally.wiki > 0 && tally.official == 0 && fanRatio < 0.3) {
            return SourceCredibility.RELIABLE_WIKI;
        }
        if (tally.official > 0 && (tally.community > 0 || tally.blog > 0)) {
            return SourceCredibility.CONFLICTING; // 공식과 비공식이 혼재하면 충돌 가능성
        }

        return SourceCredibility.UNKNOWN;
    }

    // --- AuthorityScorer 분포 판정 로직 ---
    private SourceCredibility judgeByWeightDistribution(AuthorityScorer scorer, List<String> urls) {
        int total = 0, high = 0, wiki = 0, low = 0;

        for (String u : urls) {
            double w = scorer.weightFor(u);
            total++;
            if (w >= HIGH_T) high++;
            else if (w >= WIKI_T) wiki++;
            else if (w <= LOW_T) low++;
        }
        if (total == 0) return SourceCredibility.UNKNOWN;

        double highR = (double) high / total;
        double wikiR = (double) wiki / total;
        double lowR = (double) low / total;

        boolean conflicting = (highR >= CONFLICT_MIN) && (lowR >= CONFLICT_MIN);

        if (highR >= OFFICIAL_RATIO) return SourceCredibility.OFFICIAL;
        if (wikiR >= WIKI_RATIO && highR >= WIKI_HIGH_MIN) return SourceCredibility.RELIABLE_WIKI;
        if (lowR >= FAN_LOW_RATIO && highR < FAN_HIGH_MAX) return SourceCredibility.FAN_MADE_SPECULATION;
        if (conflicting) return SourceCredibility.CONFLICTING;

        return SourceCredibility.UNKNOWN;
    }

    // --- 휴리스틱 집계 로직 ---
    private HeuristicTally tallyByHosts(String context) {
        HeuristicTally t = new HeuristicTally();
        List<String> urls = extractUrls(context);
        t.total = urls.size();
        for (String url : urls) {
            String host = host(url);
            if (host != null) {
                String h = host.toLowerCase(Locale.ROOT);
                if (isOfficial(h)) t.official++;
                else if (isWiki(h)) t.wiki++;
                else if (isCommunity(h)) t.community++;
                else if (isBlog(h)) t.blog++;
            }
        }
        return t;
    }

    // --- Helper 메서드 ---
    public static List<String> extractUrls(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    private static String host(String url) {
        if (url == null) return null;
        try {
            return URI.create(url).getHost();
        } catch (Exception ignore) {
            return null;
        }
    }

    private static boolean isOfficial(String h) {
        return h.endsWith(".go.kr") || h.endsWith(".ac.kr") || h.contains(".gov") || h.contains(".edu")
                || h.contains("hoyoverse.com") || h.contains("hoyolab.com");
    }

    private static boolean isWiki(String h) {
        return h.contains("wikipedia.org") || h.contains("namu.wiki");
    }

    private static boolean isCommunity(String h) {
        return h.contains("fandom.com") || h.contains("dcinside.com") || h.contains("arca.live") || h.contains("reddit.com");
    }

    private static boolean isBlog(String h) {
        return h.contains("blog.naver.com") || h.contains("tistory.com") || h.contains("medium.com");
    }

    // 집계용 내부 클래스
    private static class HeuristicTally {
        int total = 0, official = 0, wiki = 0, community = 0, blog = 0;
    }
}