package com.example.lms.util;

import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;

public final class SoftmaxUtil {
    private SoftmaxUtil() {
    }

    public static double[] softmax(double[] logits, double temperature) {
        if (logits == null || logits.length == 0) {
            return new double[0];
        }
        double t = Math.max(1e-8, temperature);

        double max = Double.NEGATIVE_INFINITY;
        for (double value : logits) {
            if (value > max) {
                max = value;
            }
        }

        double sum = 0.0;
        double[] out = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            double z = Math.exp((logits[i] - max) / t);
            out[i] = z;
            sum += z;
        }

        if (sum <= 0.0 || Double.isInfinite(sum) || Double.isNaN(sum)) {
            Arrays.fill(out, 1.0 / logits.length);
            return out;
        }

        for (int i = 0; i < out.length; i++) {
            out[i] /= sum;
        }
        return out;
    }

    public static <T> T sample(T[] items, double[] probs, double u) {
        if (items == null || probs == null || items.length == 0 || items.length != probs.length) {
            return null;
        }
        double acc = 0.0;
        for (int i = 0; i < probs.length; i++) {
            acc += probs[i];
            if (u <= acc) {
                return items[i];
            }
        }
        return items[items.length - 1];
    }

    public static <T> T sample(T[] items, double[] probs) {
        return sample(items, probs, ThreadLocalRandom.current().nextDouble());
    }
}
