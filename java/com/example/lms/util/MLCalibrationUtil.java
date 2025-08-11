package com.example.lms.util;

/**
 * ML 보정 유틸리티 (통합판).
 *
 * <p><b>① 시그모이드 정규화 모델</b> — 기존 코드 호환:</p>
 * finalScore = σ( λ * (α + βx + γ·tanh(x - d0) - μ) )
 * add=true  → s 반환, add=false → x와 s를 0.5씩 혼합 후 [0,1]로 클램프
 *
 * <p><b>② 다항식 ± 증강항 모델</b> — 원시 스코어(비정규화):</p>
 * F(d) = f(d) ± φ(d)
 *   f(d)  = α·d² + β·d + γ
 *   W(d)  = 1 − exp(−λ·|d − d0|)
 *   φ(d)  = μ·(d − d0)·W(d)
 *
 * <p>참고: 기존 호출부 호환을 위해 {@link #finalCorrection(double, double, double, double, double, double, double, boolean)}
 * 는 시그모이드 정규화 버전을 유지합니다. 비정규화 스코어가 필요하면
 * {@link #rawFinalCorrection(double, double, double, double, double, double, double, boolean)} 를 사용하세요.</p>
 */
public final class MLCalibrationUtil {

    private MLCalibrationUtil() {
        // no-op
    }

    // ------------------------------------------------------------
    // ① 시그모이드 정규화 모델 (기존 시그니처/동작 유지)
    // ------------------------------------------------------------
    /**
     * 시그모이드 기반 최종 보정값(정규화)을 계산합니다.
     * finalScore = σ( λ * (α + βx + γ·tanh(x - d0) - μ) )
     * add=true  → s 반환
     * add=false → 0.5*x + 0.5*s 블렌딩 후 [0,1] 클램프
     */
    public static double finalCorrection(double x,
                                         double alpha,
                                         double beta,
                                         double gamma,
                                         double d0,
                                         double mu,
                                         double lambda,
                                         boolean add) {
        double z = alpha + beta * x + gamma * Math.tanh(x - d0);
        double s = sigmoid(lambda * (z - mu));
        return add ? clamp01(s) : clamp01(0.5 * x + 0.5 * s);
    }

    // ------------------------------------------------------------
    // ② 다항식 ± 증강항 모델 (원시 스코어; 필요시 외부에서 정규화)
    // ------------------------------------------------------------
    /**
     * F(d) = f(d) ± φ(d) (비정규화 원시 스코어).
     * add=true면 f+φ, false면 f−φ.
     */
    public static double rawFinalCorrection(double d,
                                            double alpha,
                                            double beta,
                                            double gamma,
                                            double d0,
                                            double mu,
                                            double lambda,
                                            boolean add) {
        double f = baseCorrection(d, alpha, beta, gamma);
        double phi = correctionTerm(d, d0, mu, lambda);
        return add ? f + phi : f - phi;
    }

    // ------------------------------------------------------------
    // 재사용 가능한 보조 함수들
    // ------------------------------------------------------------
    /** f(d) = α·d² + β·d + γ */
    public static double baseCorrection(double d, double alpha, double beta, double gamma) {
        return alpha * d * d + beta * d + gamma;
    }

    /** W(d) = 1 − exp(−λ·|d − d0|),  0 ≤ W(d) < 1 */
    public static double weightFunction(double d, double d0, double lambda) {
        return 1.0 - Math.exp(-lambda * Math.abs(d - d0));
    }

    /** φ(d) = μ·(d − d0)·W(d) */
    public static double correctionTerm(double d, double d0, double mu, double lambda) {
        double weight = weightFunction(d, d0, lambda);
        return mu * (d - d0) * weight;
    }

    // ------------------------------------------------------------
    // 내부 유틸
    // ------------------------------------------------------------
    private static double sigmoid(double v) {
        if (v > 30) return 1.0;
        if (v < -30) return 0.0;
        return 1.0 / (1.0 + Math.exp(-v));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}