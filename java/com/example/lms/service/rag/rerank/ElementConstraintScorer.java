
        package com.example.lms.service.rag.rerank;

import com.example.lms.genshin.GenshinElementLexicon;
import com.example.lms.service.rag.pre.QueryContextPreprocessor;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;




@Component
public class ElementConstraintScorer {

    // 조합, 시너지 관련 키워드 패턴
    private static final Pattern PAIRING_TERMS = Pattern.compile(
            "(잘\\s*어울리|궁합|상성|시너지|조합|파티|함께|pairing|synergy|combo)",
            Pattern.CASE_INSENSITIVE);

    private final QueryContextPreprocessor preprocessor;
    private final GenshinElementLexicon lexicon;

    // 두 클래스의 의존성을 모두 주입받도록 생성자 통합
    public ElementConstraintScorer(QueryContextPreprocessor preprocessor, GenshinElementLexicon lexicon) {
        this.preprocessor = preprocessor;
        this.lexicon = lexicon;
    }

    /**
     * 허용/비선호 원소 정책을 기반으로 검색된 Content 리스트의 순위를 재조정합니다.
     * @param queryText 원본 사용자 쿼리
     * @param ranked    초기 순위가 적용된 Content 리스트
     * @return 재조정된 Content 리스트
     */
    public List<Content> rescore(String queryText, List<Content> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return ranked;
        }

        // 1. 쿼리로부터 허용/비선호 원소 정책 가져오기
        Set<String> allowed = java.util.Set.of();
        Set<String> discouraged = java.util.Set.of();

        boolean hasPolicy = (allowed != null && !allowed.isEmpty()) || (discouraged != null && !discouraged.isEmpty());
        if (!hasPolicy) {
            return ranked; // 적용할 정책이 없으면 원본 리스트 반환
        }

        class ScoredContent {
            final Content content;
            final double score;
            ScoredContent(Content content, double score) {
                this.content = content;
                this.score = score;
            }
        }

        List<ScoredContent> scoredList = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            Content content = ranked.get(i);
            String text = Optional.ofNullable(content.textSegment())
                    .map(TextSegment::text)
                    .orElseGet(content::toString);

            // 2. 기본 점수(기존 순위)와 정책 기반 조정 점수(delta)를 합산
            double baseScore = 1.0 / (i + 1.0); // 기존 순위에 가중치 부여
            double delta = deltaForText(text, allowed, discouraged); // 상세 로직으로 delta 계산

            scoredList.add(new ScoredContent(content, baseScore + delta));
        }

        // 3. 최종 점수로 리스트를 내림차순 정렬
        scoredList.sort((a, b) -> Double.compare(b.score, a.score));

        // 4. Content만 추출하여 새로운 리스트로 반환
        List<Content> rescoredList = new ArrayList<>();
        for (ScoredContent sc : scoredList) {
            rescoredList.add(sc.content);
        }
        return rescoredList;
    }

    /**
     * 텍스트와 원소 정책(허용/비선호)을 기반으로 점수 변동(delta)을 계산합니다.
     * 비선호 원소가 발견되면 큰 페널티를, 허용 원소는 보너스를 부여합니다.
     * @param text         문서 텍스트
     * @param allowed      허용 원소 Set
     * @param discouraged  비선호 원소 Set
     * @return 계산된 점수(delta)
     */
    public double deltaForText(String text, Set<String> allowed, Set<String> discouraged) {
        if (text == null || (allowed.isEmpty() && discouraged.isEmpty())) {
            return 0.0;
        }

        Set<String> elemsInText = lexicon.tagElementsInText(text);
        if (elemsInText.isEmpty()) {
            return 0.0;
        }

        boolean hasSynergyCue = PAIRING_TERMS.matcher(text).find();
        long hitAllowed = elemsInText.stream().filter(allowed::contains).count();
        long hitDiscouraged = elemsInText.stream().filter(discouraged::contains).count();

        if (hitDiscouraged > 0) {
            // 정책 위반 시 강력한 감점 (시너지 맥락이면 추가 감점)
            return -0.25 - 0.05 * (hitDiscouraged - 1) - (hasSynergyCue ? 0.10 : 0.0);
        }
        if (hitAllowed > 0) {
            // 정책 충족 시 완만한 보너스 (중복은 약하게 누적, 시너지 맥락이면 추가 보너스)
            return 0.12 + 0.03 * (hitAllowed - 1) + (hasSynergyCue ? 0.04 : 0.0);
        }
        return 0.0;
    }
}