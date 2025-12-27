package com.example.lms.service.rag.fusion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Weighted power mean fusion utility.
 *
 * <p>
 * 기존 {@link #fuse(List, double, List)} 시그니처를 유지하면서,
 * 검색 소스(web / vector / memory 등)에 따라 기본 가중치를
 * application.yml 에서 주입받아 사용할 수 있는 헬퍼를 추가했다.
 * </p>
 *
 * <p>
 *  - p &gt; 1  : 상위 점수에 더 민감 (상위 문서에 집중)
 *  - p = 1    : 단순 가중 평균
 *  - 0 &lt; p &lt; 1 : 꼬리(tail)를 조금 더 살려줌
 *  - p = 0    : 기하 평균(geometric mean)
 * </p>
 */
@Component
public class WeightedPowerMeanFuser {

    @Value("${rag.fusion.weights.web:1.0}")
    private double webWeight = 1.0;

    @Value("${rag.fusion.weights.vector:0.8}")
    private double vectorWeight = 0.8;

    @Value("${rag.fusion.weights.memory:0.6}")
    private double memoryWeight = 0.6;

    /**
     * General weighted power mean.
     *
     * @param scores 0~1 범위의 정규화된 점수 리스트
     * @param p      power 지수 (0 이면 기하 평균)
     * @param weights 각 점수에 대한 가중치 (null 이거나 길이가 맞지 않으면 균등 가중치)
     */
    public double fuse(List<Double> scores, double p, List<Double> weights) {
        if (scores == null || scores.isEmpty()) {
            return 0.0;
        }
        int n = scores.size();
        List<Double> localWeights;
        if (weights == null || weights.size() != n) {
            localWeights = new ArrayList<>(Collections.nCopies(n, 1.0));
        } else {
            localWeights = new ArrayList<>(weights);
        }

        double num = 0.0;
        double den = 0.0;
        for (int i = 0; i < n; i++) {
            Double wObj = localWeights.get(i);
            Double sObj = scores.get(i);
            double w = (wObj == null ? 1.0 : wObj);
            double s = (sObj == null ? 0.0 : sObj);
            den += w;
            if (p == 0.0) {
                // geometric mean: exp( sum_i w_i * log(x_i) / sum_i w_i )
                num += w * Math.log(Math.max(1e-9, s));
            } else {
                num += w * Math.pow(s, p);
            }
        }
        if (den == 0.0) {
            return 0.0;
        }
        if (p == 0.0) {
            return Math.exp(num / den);
        }
        return Math.pow(num / den, 1.0 / p);
    }

    /**
     * Source kind(web/vector/memory 등)에 따라 내부 기본 가중치를 적용하는 헬퍼.
     * <p>
     * 예시: sourceKinds = ["web", "vector", "memory"] 인 경우,
     * 각 항목에 대해 webWeight / vectorWeight / memoryWeight 를 적용한다.
     * </p>
     *
     * @param scores      0~1 범위의 정규화된 점수 리스트
     * @param p           power 지수
     * @param sourceKinds 각 점수가 어떤 소스에서 왔는지 나타내는 문자열("web", "vector", "memory" 등)
     */
    public double fuseWithSourceKinds(List<Double> scores, double p, List<String> sourceKinds) {
        if (scores == null || scores.isEmpty()) {
            return 0.0;
        }
        int n = scores.size();
        List<Double> weights = new ArrayList<>(n);

        if (sourceKinds == null || sourceKinds.size() != n) {
            // 소스 정보가 없으면 균등 가중치 사용
            for (int i = 0; i < n; i++) {
                weights.add(1.0);
            }
        } else {
            for (String kind : sourceKinds) {
                weights.add(resolveWeight(kind));
            }
        }
        return fuse(scores, p, weights);
    }

    private double resolveWeight(String kind) {
        if (kind == null) {
            return 1.0;
        }
        String k = kind.toLowerCase(Locale.ROOT);
        if (k.contains("web")) {
            return webWeight;
        }
        if (k.contains("vector") || k.contains("embed")) {
            return vectorWeight;
        }
        if (k.contains("mem") || k.contains("jammini")) {
            return memoryWeight;
        }
        return 1.0;
    }
}
