package com.example.lms.service.rag.rerank;

import com.example.lms.service.rag.knowledge.UniversalContextLexicon;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ElementConstraintScorer
 *
 * <p>기존에는 Genshin 원소(PYRO/HYDRO/CRYO 등)에만 특화되어 있었던
 * 재랭킹 스코어러를 범용 Attribute 기반 스코어러로 재구현한 클래스입니다.
 *
 * <p>UniversalContextLexicon 에서 추론한 쿼리/문서 Attribute 와
 * Policy(allowed / discouraged)를 사용하여, 도메인 일치 여부 및
 * 혼선 키워드를 기반으로 작은 가중치를 부여합니다.
 *
 * <p>주의: 이 스코어러는 "미세 조정용"으로 설계되었습니다.
 * - Attribute가 없거나 정책이 비어 있으면 입력 순서를 그대로 유지합니다.
 * - 점수 차이는 작게(±0.1~0.2 수준) 유지하여 기존 RRF/CE 랭킹을 존중합니다.
 */
@Component
public class ElementConstraintScorer {

    private final UniversalContextLexicon contextLexicon;

    public ElementConstraintScorer(UniversalContextLexicon contextLexicon) {
        this.contextLexicon = contextLexicon;
    }

    /**
     * 허용/비선호 속성 정책을 기반으로 검색된 Content 리스트의 순위를 재조정합니다.
     *
     * @param queryText 원본 사용자 쿼리
     * @param ranked    초기 순위가 적용된 Content 리스트
     * @return 재조정된 Content 리스트
     */
    public List<Content> rescore(String queryText, List<Content> ranked) {
        if (ranked == null || ranked.isEmpty()) {
            return ranked;
        }

        String queryAttr = contextLexicon.inferAttribute(queryText);
        if (queryAttr == null) {
            // 속성을 추론할 수 없으면 중립적으로 기존 순서를 유지한다.
            return ranked;
        }

        UniversalContextLexicon.Policy policy = contextLexicon.policyForQuery(queryText);

        // 원본 순서를 보존하기 위해 인덱스를 함께 보관한다.
        class Scored {
            final Content content;
            final int originalIndex;
            final double delta;

            Scored(Content content, int originalIndex, double delta) {
                this.content = content;
                this.originalIndex = originalIndex;
                this.delta = delta;
            }
        }

        List<Scored> scored = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            Content c = ranked.get(i);
            String text = extractText(c);
            double delta = scoreDelta(queryAttr, policy, text);
            scored.add(new Scored(c, i, delta));
        }

        // delta 기준으로만 미세하게 재정렬하고, 동점 시 원래 순서를 유지한다.
        scored.sort((a, b) -> {
            int cmp = Double.compare(b.delta, a.delta);
            if (cmp != 0) return cmp;
            return Integer.compare(a.originalIndex, b.originalIndex);
        });

        List<Content> result = new ArrayList<>(ranked.size());
        for (Scored s : scored) {
            result.add(s.content);
        }
        return result;
    }

    /**
     * Content에서 텍스트를 안전하게 추출한다.
     * Content API 변화에 강인하도록 textSegment() / toString() 순으로 시도한다.
     */
    private String extractText(Content c) {
        if (c == null) {
            return "";
        }
        try {
            TextSegment segment = c.textSegment();
            if (segment != null && segment.text() != null) {
                return segment.text();
            }
        } catch (NoSuchMethodError ignored) {
            // 구버전 Content 구현에서는 textSegment() 가 없을 수 있음
        } catch (Exception ignored) {
            // 방어적인 예외 처리
        }
        return c.toString();
    }

    /**
     * 쿼리/문서 Attribute 및 Policy를 기반으로 점수 델타를 계산한다.
     */
    private double scoreDelta(String queryAttr,
                              UniversalContextLexicon.Policy policy,
                              String text) {

        if (text == null || text.isBlank()) {
            return 0.0;
        }

        String docAttr = contextLexicon.inferAttribute(text);
        double delta = 0.0;

        // 1) 속성이 정확히 일치하면 소량의 보너스
        if (queryAttr != null && queryAttr.equals(docAttr)) {
            delta += 0.10;
        }

        // 2) 정책상 지양되는(discouraged) 토큰이 포함되어 있으면 페널티
        if (policy != null && policy.discouraged() != null && !policy.discouraged().isEmpty()) {
            String lower = text.toLowerCase(Locale.ROOT);
            for (String bad : policy.discouraged()) {
                if (bad == null || bad.isBlank()) {
                    continue;
                }
                if (lower.contains(bad.toLowerCase(Locale.ROOT))) {
                    delta -= 0.20;
                    break;
                }
            }
        }

        return delta;
    }
}
