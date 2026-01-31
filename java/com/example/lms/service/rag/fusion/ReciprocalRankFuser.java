package com.example.lms.service.rag.fusion;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;




/**
 * 여러 검색 결과 리스트를 RRF(Reciprocal Rank Fusion) 알고리즘으로 융합하여
 * 순위를 재조정하는 컴포넌트입니다.
 * <p>
 * - 여러 출처의 검색 결과를 종합하여 가장 관련성 높은 문서를 상위로 올립니다.
 * - 안정적인 키(해시) 생성을 통해 중복 문서를 효율적으로 제거합니다.
 * </p>
 */
@Component
public class ReciprocalRankFuser {

    /**
     * RRF 알고리즘의 순위 가중치를 조절하는 상수 (k=60이 표준값).
     * 점수 = 1 / (k + rank)
     */
    private final int k;

    /**
     * 기본 생성자. Spring에서 Bean으로 등록 시 사용됩니다. (k=60)
     */
    public ReciprocalRankFuser() {
        this(60);
    }

    /**
     * k값을 직접 지정하는 생성자.
     * @param k RRF 알고리즘에 사용할 k 상수
     */
    public ReciprocalRankFuser(int k) {
        this.k = Math.max(1, k);
    }

    /**
     * 여러 문서 리스트를 RRF 알고리즘으로 융합합니다.
     *
     * @param sourceLists 융합할 여러 소스의 문서 리스트(List of List of Content)
     * @param topK        최종적으로 반환할 상위 문서의 개수
     * @return RRF 점수 기준으로 내림차순 정렬된 최종 문서 리스트
     */
    public List<Content> fuse(List<List<Content>> sourceLists, int topK) {
        if (sourceLists == null || sourceLists.isEmpty()) {
            return Collections.emptyList();
        }

        // Use a simple RRF score without any z-score normalisation or min/max
        // scaling.  The original definition of Reciprocal Rank Fusion sums
        // 1/(k + rank) across all sources.  Normalisation or capping is
        // explicitly avoided to preserve the ranking based solely on ranks.
        Map<String, Double> scores = new HashMap<>();
        // LinkedHashMap을 사용하여 처음 등장한 순서를 유지 (안정성 확보)
        Map<String, Content> firstAppearance = new LinkedHashMap<>();

        for (List<Content> list : sourceLists) {
            if (list == null) continue;

            int rank = 0;
            for (Content content : list) {
                if (content == null) continue;
                rank++; // RRF의 rank는 1부터 시작
                String key = keyOf(content);
                // 처음 등장한 문서만 저장 (중복 방지)
                firstAppearance.putIfAbsent(key, content);
                // RRF 점수 계산 및 누적
                double reciprocalRankScore = 1.0 / (k + rank);
                scores.merge(key, reciprocalRankScore, Double::sum);
            }
        }
        if (scores.isEmpty()) {
            return Collections.emptyList();
        }
        // 계산된 RRF 점수를 기준으로 내림차순 정렬하여 상위 topK개만 반환합니다.
        return scores.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .limit(Math.max(1, topK))
                .map(entry -> firstAppearance.get(entry.getKey()))
                .collect(Collectors.toList());
    }

    /**
     * Content 객체로부터 안정적인 중복 제거용 키를 생성합니다.
     * <p>
     * 텍스트의 공백/줄바꿈을 정규화한 후 해시코드를 사용해 메모리 효율성을 높입니다.
     *
     * @param content 키를 생성할 Content 객체
     * @return 정규화된 해시 키 문자열
     */
    private static String keyOf(Content content) {
        String text = Optional.ofNullable(content.textSegment())
                .map(TextSegment::text)
                .orElseGet(content::toString);

        // 텍스트가 null일 경우 빈 문자열로 처리
        String normalized = (text == null) ? "" : text.replaceAll("\\s+", " ").trim();

        // 정규화된 텍스트의 해시코드를 키로 사용
        return Integer.toHexString(normalized.hashCode());
    }
}