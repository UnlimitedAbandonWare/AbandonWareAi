package com.example.lms.service.rag.fusion;

/**
 * Calibrates raw fusion scores prior to softmax normalisation.  Without
 * calibration the softmax distribution can become biased toward a small
 * subset of high magnitude values which may differ considerably across
 * queries.  Applying a calibration step ensures that scores lie in a
 * comparable range.  The default implementation performs a simple
 * min‑max scaling to the unit interval.  Additional calibration methods
 * (e.g. isotonic regression) may be implemented in the future.
 */
public final class FusionCalibrator {

    private FusionCalibrator() {
        // utility class
    }

    /**
     * Rescales an array of scores into the range [0,1] using min‑max
     * normalisation.  When all values are equal the output will be
     * uniformly 1.0.  The input array is not modified; a new array is
     * returned.
     *
     * @param values the raw scores to rescale
     * @return a new array containing values in [0,1]
     */
    public static double[] minMax(double[] values) {
        if (values == null || values.length == 0) {
            return new double[0];
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (double v : values) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        double range = max - min;
        double[] out = new double[values.length];
        if (range <= 0.0 || Double.isNaN(range)) {
            // All values are equal; output ones to avoid division by zero.
            java.util.Arrays.fill(out, 1.0);
        } else {
            for (int i = 0; i < values.length; i++) {
                out[i] = (values[i] - min) / range;
            }
        }
        return out;
    }

    /**
     * Placeholder for an isotonic regression calibrator.  The current
     * implementation delegates to {@link #minMax(double[])}.  An
     * isotonic calibration would enforce a monotonically increasing
     * relationship between raw scores and calibrated outputs.  When
     * implemented this method should replace the call to minMax.
     *
     * @param values the raw scores to calibrate
     * @return a new calibrated array
     */
    public static double[] isotonic(double[] values) {
        // Placeholder: implement isotonic regression calibration; currently falls back to default behavior.
        // to min-max scaling.
        return minMax(values);
    }
}