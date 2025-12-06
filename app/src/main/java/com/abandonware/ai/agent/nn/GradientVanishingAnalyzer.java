package com.abandonware.ai.agent.nn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;



/**
 * Gradient vanishing risk estimator.
 *
 * p_vanish = sigmoid(alpha * (log10(threshold) - log10(norm + eps)) + beta)
 * where norm is L2 norm of the gradient tensor for a layer.
 */
/**
 * [GPT-PRO-AGENT] 파일 수준 주석 - 기능 연결을 돕기 위한 설명
 * 클래스: GradientVanishingAnalyzer
 * 역할(Role): Class
 * 소스 경로: app/src/main/java/com/abandonware/ai/agent/nn/GradientVanishingAnalyzer.java
 *
 * 연결 포인트(Hooks):
 *   - DI/협력 객체는 @Autowired/@Inject/@Bean/@Configuration 스캔으로 파악하세요.
 *   - 트레이싱 헤더: X-Request-Id, X-Session-Id (존재 시 전체 체인에서 전파).
 *
 * 과거 궤적(Trajectory) 추정:
 *   - 본 클래스가 속한 모듈의 변경 이력은 /MERGELOG_*, /PATCH_NOTES_*, /CHANGELOG_* 문서를 참조.
 *   - 동일 기능 계통 클래스: 같은 접미사(Service/Handler/Controller/Config) 및 동일 패키지 내 유사명 검색.
 *
 * 안전 노트: 본 주석 추가는 코드 실행 경로를 변경하지 않습니다(주석 전용).
 */
public final 
// [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
// Module: com.abandonware.ai.agent.nn.GradientVanishingAnalyzer
// Role: config
// Feature Flags: sse
// Observability: propagates trace headers if present.
// Thread-Safety: appears stateless.
// /
/* agent-hint:
id: com.abandonware.ai.agent.nn.GradientVanishingAnalyzer
role: config
flags: [sse]
*/
class GradientVanishingAnalyzer {

    private final double threshold;
    private final double alpha;
    private final double beta;
    private final double eps;

    public record LayerHealth(String layer, double l2norm, double vanishProb, boolean flag) {}

    public GradientVanishingAnalyzer(double threshold, double alpha, double beta, double eps) {
        this.threshold = threshold;
        this.alpha = alpha;
        this.beta = beta;
        this.eps = eps;
    }

    private static double l2(double[] g) {
        if (g == null || g.length == 0) return 0.0;
        double s = 0.0;
        for (double v : g) s += v * v;
        return Math.sqrt(s);
    }

    private double vanishProb(double norm) {
        // sigmoid(alpha * (log10(threshold) - log10(norm + eps)) + beta)
        double z = alpha * (Math.log10(threshold) - Math.log10(norm + eps)) + beta;
        return 1.0 / (1.0 + Math.exp(-z));
    }

    /** Assess from raw gradient arrays; useful for batch diagnostics. */
    public List<LayerHealth> assessRaw(List<String> layers, List<double[]> grads) {
        if (layers == null || grads == null || layers.size() != grads.size()) {
            return Collections.emptyList();
        }
        List<LayerHealth> out = new ArrayList<>(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            double n = l2(grads.get(i));
            double p = vanishProb(n);
            out.add(new LayerHealth(layers.get(i), n, p, p >= 0.5));
        }
        return out;
    }

    /** Assess when L2 norms are already precomputed. */
    public List<LayerHealth> assess(List<String> layers, List<Double> norms) {
        if (layers == null || norms == null || layers.size() != norms.size()) {
            return Collections.emptyList();
        }
        List<LayerHealth> out = new ArrayList<>(layers.size());
        for (int i = 0; i < layers.size(); i++) {
            double n = norms.get(i);
            double p = vanishProb(n);
            out.add(new LayerHealth(layers.get(i), n, p, p >= 0.5));
        }
        return out;
    }
}