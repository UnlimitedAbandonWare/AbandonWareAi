package com.example.lms.util;

import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * ★ 초간단 Jaccard 유사도 계산기
 *
 *  - 공백(Whitespace) 기준으로 토큰화한 뒤
 *    교집합/합집합 비율을 반환합니다. (0.0 ~ 1.0)
 *  - 실서비스에서는 Apache Lucene, Elasticsearch,
 *    Vector DB(FAISS·Qdrant 등)를 사용하는 것을 권장합니다.
 */
@Component
public class TextSimilarityUtil {

    /**
     * @param a 첫 번째 문자열
     * @param b 두 번째 문자열
     * @return Jaccard Similarity (0 ≦ sim ≦ 1)
     */
    public double calculateSimilarity(String a, String b) {
        if (a == null || b == null) return 0.0;

        Set<String> A = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> B = new HashSet<>(Arrays.asList(b.split("\\s+")));

        int inter = (int) A.stream().filter(B::contains).count();
        int union = A.size() + B.size() - inter;

        return union == 0 ? 1.0 : (double) inter / union;
    }
}
