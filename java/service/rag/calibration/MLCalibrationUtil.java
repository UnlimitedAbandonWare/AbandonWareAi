

package service.rag.calibration;

/**
 * 기계 학습(ML) 모델의 점수를 보정하고 조정하기 위한 수학적 유틸리티 클래스입니다.
 *
 * <p><b>① 시그모이드 정규화 모델 (기존 코드 호환)</b></p>
 * <p>
 * 점수를 0과 1 사이로 정규화하며, 비선형적 특성을 반영합니다.
 * {@link #finalCorrection(double, double, double, double, double, double, double, boolean)} 메서드를 통해 제공됩니다.
 * </p>
 *
 * <p><b>② 다항식 ± 증강항 모델 (비정규화 원시 스코어)</b></p>
 * <p>
 * 정규화 없이 원시 점수를 계산하여 다른 모델의 입력값으로 사용하거나,
 * 점수의 절대적 크기가 중요할 때 유용합니다.
 * {@link #rawFinalCorrection(double, double, double, double, double, double, double, boolean)} 메서드를 통해 제공됩니다.
 * </p>
 */
public final class MLCalibrationUtil {

    private MLCalibrationUtil() {
        // 유틸리티 클래스는 인스턴스화하지 않습니다.
    }

    // ------------------------------------------------------------
    // ① 시그모이드 정규화 모델 (기존 시그니처/동작 유지)
    // ------------------------------------------------------------
    /**
     * 시그모이드 기반 최종 보정값(정규화된 점수, 0.0 ~ 1.0)을 계산합니다.
     * <p>
     * <b>Formula:</b> σ( λ * (α + βx + γ·tanh(x - d₀) - μ) )
     * </p>
     * @param add {@code true}이면 보정된 시그모이드 값을, {@code false}이면 원본값과 시그모이드 값을 50%씩 혼합합니다.
     * @return 0과 1 사이로 클램핑된 최종 점수
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
     * 다항식 모델을 기반으로 비정규화된 원시 보정 점수를 계산합니다.
     * <p>
     * <b>Formula:</b> F(d) = f(d) ± φ(d), where f(d) is the base polynomial and φ(d) is the augmentation term.
     * </p>
     * @param add {@code true}이면 증강항을 더하고({@code f+φ}), {@code false}이면 뺍니다({@code f-φ}).
     * @return 정규화되지 않은 원시 점수
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
    // 재사용 가능한 공용 보조 함수들
    // ------------------------------------------------------------
    /**
     * 2차 다항식 기본 점수를 계산합니다: {@code f(d) = α·d² + β·d + γ}
     */
    public static double baseCorrection(double d, double alpha, double beta, double gamma) {
        return alpha * d * d + beta * d + gamma;
    }

    /**
     * 특정 지점(d₀)에서 멀어질수록 1에 가까워지는 가중치 함수를 계산합니다: {@code W(d) = 1 − exp(−λ·|d − d₀|)}
     * @return 0과 1 사이의 가중치 값
     */
    public static double weightFunction(double d, double d0, double lambda) {
        if (lambda < 0) return 0.0;
        return 1.0 - Math.exp(-lambda * Math.abs(d - d0));
    }

    /**
     * 가중치가 적용된 증강항을 계산합니다: {@code φ(d) = μ·(d − d₀)·W(d)}
     */
    public static double correctionTerm(double d, double d0, double mu, double lambda) {
        double weight = weightFunction(d, d0, lambda);
        return mu * (d - d0) * weight;
    }

    // ------------------------------------------------------------
    // 내부 전용 유틸리티
    // ------------------------------------------------------------
    /**
     * 표준 시그모이드 함수를 계산합니다. 오버플로/언더플로를 방지하기 위해 입력 범위를 제한합니다.
     */
    private static double sigmoid(double value) {
        if (value > 30.0) return 1.0;
        if (value < -30.0) return 0.0;
        return 1.0 / (1.0 + Math.exp(-value));
    }

    /**
     * 주어진 값을 0.0과 1.0 사이로 제한(클램핑)합니다.
     */
    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}