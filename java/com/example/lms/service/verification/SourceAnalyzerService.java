package com.example.lms.service.verification;

import com.example.lms.domain.enums.SourceCredibility;
import com.example.lms.service.rag.auth.AuthorityScorer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;




/**
 * 출처의 신뢰도를 종합적으로 분석하는 서비스입니다.
 *
 * <p>분석은 다음 우선순위로 진행됩니다:
 * <ol>
 * <li><b>명시적 텍스트 단서:</b> 내용에 '모순', '상충' 등 명백한 충돌 키워드가 있으면 즉시 {@code CONFLICTING}으로 판정합니다.</li>
 * <li><b>URL 기반 정밀 분석 (AuthorityScorer):</b> 출처 URL의 가중치를 정밀하게 평가하여 {@code OFFICIAL}, {@code RELIABLE} 등으로 판정합니다. ({@code AuthorityScorer} Bean이 있는 경우)</li>
 * <li><b>휴리스틱 폴백:</b> 위 방법이 불가능할 경우, URL의 도메인 종류와 '루머', '추측' 같은 키워드를 종합해 신뢰도를 추정합니다.</li>
 * </ol>
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SourceAnalyzerService {
    private static final Logger log = LoggerFactory.getLogger(SourceAnalyzerService.class);

    private final ObjectProvider<AuthorityScorer> authorityScorerProvider;

    // --- 텍스트 단서 패턴 (두 버전의 키워드 통합 및 강화) ---
    private static final Pattern CONFLICT_TXT = Pattern.compile(
            "(?i)(상반|엇갈|모순|상충|논란|서로\\s*다르|반박|conflict|contradict)");
    private static final Pattern FAN_CUE = Pattern.compile(
            "(?i)(fan-?made|추측|루머|유출|소문|카더라|predicted|예상 스펙|추정|확정 아님|datamine|rumor|leak|unconfirmed|speculation)");

    // --- AuthorityScorer 가중치 임계값 ---
    private static final double OFFICIAL_THRESHOLD = 0.90;
    private static final double RELIABLE_THRESHOLD = 0.70;
    private static final double COMMUNITY_THRESHOLD = 0.40;
    private static final double CONFLICT_MIN_RATIO = 0.25;

    /**
     * 질문과 컨텍스트를 기반으로 출처의 신뢰도를 분석합니다.
     *
     * @param question 질문 (현재는 사용되지 않으나 확장성을 위해 유지)
     * @param context 분석할 컨텍스트 텍스트 (URL 포함 가능)
     * @return 분석된 {@link SourceCredibility} 값
     */
    public SourceCredibility analyze(String question, String context) {
        if (context == null || context.isBlank()) {
            return SourceCredibility.INSUFFICIENT;
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
                SourceCredibility judgedByScorer = judgeByWeightDistribution(scorer, urls);
                // 명확한 판정이 나왔고, 내용에 루머 단서가 없다면 그 결과를 신뢰
                if (judgedByScorer != SourceCredibility.UNKNOWN && !FAN_CUE.matcher(context).find()) {
                    return judgedByScorer;
                }
            } catch (Exception e) {
                log.debug("AuthorityScorer 평가 실패 (휴리스틱으로 폴백): {}", e.toString());
            }
        }

        // 3. 휴리스틱 폴백 분석
        return judgeByHeuristics(context, urls);
    }

    private SourceCredibility judgeByWeightDistribution(AuthorityScorer scorer, List<String> urls) {
        int total = urls.size();
        if (total == 0) return SourceCredibility.UNKNOWN;

        int officialCount = 0, reliableCount = 0, communityCount = 0;

        for (String url : urls) {
            double weight = scorer.weightFor(url);
            if (weight >= OFFICIAL_THRESHOLD) officialCount++;
            else if (weight >= RELIABLE_THRESHOLD) reliableCount++;
            else if (weight <= COMMUNITY_THRESHOLD) communityCount++;
        }

        double officialRatio = (double) officialCount / total;
        double reliableRatio = (double) reliableCount / total;
        double communityRatio = (double) communityCount / total;

        boolean isConflicting = (officialRatio >= CONFLICT_MIN_RATIO) && (communityRatio >= CONFLICT_MIN_RATIO);
        if (isConflicting) return SourceCredibility.CONFLICTING;

        if (officialRatio >= 0.5) return SourceCredibility.OFFICIAL;
        if (reliableRatio >= 0.5 && officialRatio < 0.2) return SourceCredibility.RELIABLE;
        if (communityRatio >= 0.6 && officialRatio == 0) return SourceCredibility.COMMUNITY;

        return SourceCredibility.UNKNOWN;
    }

    private SourceCredibility judgeByHeuristics(String context, List<String> urls) {
        HeuristicTally tally = tallyByHosts(urls);

        // 내용에 루머 키워드가 있거나, URL 대부분이 커뮤니티/블로그면 FAN_MADE_SPECULATION
        boolean hasFanCue = FAN_CUE.matcher(context).find();
        if (hasFanCue) {
            // 단, 공식 출처가 명확히 있으면 충돌로 판단
            if (tally.official > 0) return SourceCredibility.CONFLICTING;
            return SourceCredibility.FAN_MADE_SPECULATION;
        }

        if (tally.total > 0) {
            double communityRatio = (double) (tally.community + tally.blog) / tally.total;
            if (communityRatio >= 0.75 && tally.official == 0) {
                return SourceCredibility.FAN_MADE_SPECULATION;
            }
        }

        // 공식/신뢰 도메인 우선 판정
        if (tally.official > 0) return SourceCredibility.OFFICIAL;
        if (tally.reliable > 0) return SourceCredibility.RELIABLE;
        if (tally.community > 0 || tally.blog > 0) return SourceCredibility.COMMUNITY;

        // URL 없이 텍스트만 있을 때, 단순 충돌 감지
        boolean hasAffirmative = context.contains("탑재") || context.contains("확정") || context.contains("발표");
        boolean hasNegative = context.contains("미정") || context.contains("아님") || context.contains("부정");
        if (hasAffirmative && hasNegative) return SourceCredibility.CONFLICTING;

        return SourceCredibility.UNKNOWN;
    }

    // --- Helper 메서드 ---
    private static List<String> extractUrls(String text) {
        if (text == null || text.isBlank()) return List.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)").matcher(text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    private static String getHost(String url) {
        if (url == null) return null;
        try {
            return URI.create(url).getHost();
        } catch (Exception ignore) {
            return null; // Invalid URL syntax
        }
    }

    private HeuristicTally tallyByHosts(List<String> urls) {
        HeuristicTally t = new HeuristicTally();
        t.total = urls.size();
        for (String url : urls) {
            String host = getHost(url);
            if (host != null) {
                String h = host.toLowerCase(Locale.ROOT);
                if (isOfficial(h)) t.official++;
                else if (isReliable(h)) t.reliable++;
                else if (isCommunity(h)) t.community++;
                else if (isBlog(h)) t.blog++;
            }
        }
        return t;
    }

    // 도메인 분류 헬퍼 (V1, V2 목록 통합)
    private static boolean isOfficial(String h) {
        return h.endsWith(".go.kr") || h.endsWith(".ac.kr") || h.contains(".gov") || h.contains(".edu")
                || h.contains("hoyoverse.com") || h.contains("hoyolab.com");
    }

    private static boolean isReliable(String h) {
        return h.contains("wikipedia.org") || h.contains("namu.wiki") || h.contains("nature.com")
                || h.contains("sciencedirect.com") || h.contains("ieee.org");
    }

    private static boolean isCommunity(String h) {
        return h.contains("fandom.com") || h.contains("dcinside.com") || h.contains("arca.live")
                || h.contains("reddit.com") || h.contains("fmkorea.com") || h.contains("ruliweb.com")
                || h.contains("inven.co.kr") || h.contains("clien.net") || h.contains("youtube.com")
                || h.contains("x.com") || h.contains("twitter.com");
    }

    private static boolean isBlog(String h) {
        return h.contains("blog.naver.com") || h.contains("tistory.com") || h.contains("medium.com")
                || h.contains("velog.io");
    }

    // 집계용 내부 클래스
    private static class HeuristicTally {
        int total = 0, official = 0, reliable = 0, community = 0, blog = 0;
    }
}