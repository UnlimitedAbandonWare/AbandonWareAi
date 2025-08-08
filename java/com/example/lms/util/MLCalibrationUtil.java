package com.example.lms.util;

/**
 * Utility class implementing a set of machine‑learning inspired
 * calibration functions used to correct, reinforce, refine and augment
 * numerical values.  These methods are based on the optical
 * compensation equations described in the specification.  The
 * definitions provided here mirror the mathematical formulation
 * presented in the narrative: a basic quadratic correction curve is
 * combined with a distance‑dependent weighting function to produce a
 * final, optionally additive or subtractive, correction.
 *
 * <p>The functions are deliberately kept in a standalone utility
 * separate from the core {@code ChatService} so that they can be
 * reused or tested independently.  In a real deployment you might
 * supply the coefficients (α, β, γ, μ, λ, d₀) via external
 * configuration or tune them empirically based on training data.  For
 * brevity no external dependencies are required; the implementation
 * relies solely on {@link Math} from the JDK.</p>
 */
public final class MLCalibrationUtil {

    private MLCalibrationUtil() {
        // Prevent instantiation
    }

    /**
     * Compute the basic quadratic correction curve
     * {@code f(d) = α·d² + β·d + γ}.
     *
     * @param d the distance measure (for example, a position in cm)
     * @param alpha the quadratic coefficient α
     * @param beta the linear coefficient β
     * @param gamma the constant offset γ
     * @return the value of the base correction function f(d)
     */
    public static double baseCorrection(double d, double alpha, double beta, double gamma) {
        return alpha * d * d + beta * d + gamma;
    }

    /**
     * Compute the weighting function {@code W(d) = 1 − exp(−λ·|d − d₀|)}
     * which attenuates or amplifies the correction based on the
     * difference between the current position {@code d} and a
     * reference point {@code d0}.  The parameter {@code lambda}
     * controls how quickly the function approaches one; larger values
     * cause a faster rise and thus more aggressive reinforcement for
     * large distance differences.
     *
     * @param d the distance at which to evaluate the weighting
     * @param d0 the reference distance (for example, the point where the mirror is most misaligned)
     * @param lambda the attenuation constant λ (must be positive)
     * @return the weight W(d) in the range [0, 1)
     */
    public static double weightFunction(double d, double d0, double lambda) {
        return 1.0 - Math.exp(-lambda * Math.abs(d - d0));
    }

    /**
     * Compute the distance dependent augmentation term
     * {@code φ(d) = μ·(d − d₀)·W(d)}.  This term is added to or
     * subtracted from the base correction depending on the desired
     * direction of compensation.  See {@link #finalCorrection(double, double, double, double, double, double, double, boolean)}
     * for details on how this term is combined.
     *
     * @param d the distance at which to evaluate the term
     * @param d0 the reference distance d₀
     * @param mu the scaling factor μ controlling the strength of the augmentation
     * @param lambda the attenuation constant λ used in the weighting function
     * @return the value of φ(d)
     */
    public static double correctionTerm(double d, double d0, double mu, double lambda) {
        double weight = weightFunction(d, d0, lambda);
        return mu * (d - d0) * weight;
    }

    /**
     * Combine the base correction {@code f(d)} and the augmentation term
     * {@code φ(d)} to produce the final corrected value
     * {@code F(d) = f(d) ± φ(d)}.  When {@code add} is {@code true}
     * the augmentation is added to the base; when {@code add} is
     * {@code false} the augmentation is subtracted, e.g. when the
     * existing system tends to overcompensate.
     *
     * @param d the distance or input value
     * @param alpha the quadratic coefficient
     * @param beta the linear coefficient
     * @param gamma the constant offset
     * @param d0 the reference distance
     * @param mu the scaling factor for the augmentation
     * @param lambda the attenuation constant
     * @param add whether to add (true) or subtract (false) the augmentation term
     * @return the final corrected value F(d)
     */
    public static double finalCorrection(
            double d,
            double alpha,
            double beta,
            double gamma,
            double d0,
            double mu,
            double lambda,
            boolean add
    ) {
        double f = baseCorrection(d, alpha, beta, gamma);
        double phi = correctionTerm(d, d0, mu, lambda);
        return add ? f + phi : f - phi;
    }
}