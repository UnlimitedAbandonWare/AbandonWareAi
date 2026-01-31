package com.example.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.Arrays;




/**
 * Lightweight logistic risk model provider driven by configuration.
 * When disabled (risk.model.enabled=false) this provider yields null and NoOpRiskModelProvider remains effective.
 *
 * Configuration:
 *   risk.model.enabled=true
 *   risk.model.bias= -2.0
 *   risk.model.coef= 0.2,0.15,0.4,0.1,0.05
 * Coefficients array length must match the features produced by RiskFeatureExtractor.
 */
@Component
@Primary
public class RealRiskModelProvider implements RiskModelProvider {

    @Value("${risk.model.enabled:false}")
    private boolean enabled;

    @Value("${risk.model.bias:0.0}")
    private double bias;

    @Value("${risk.model.coef:}")
    private String coefCsv;

    private volatile double[] coef = null;

    @Override
    public Classifier get() {
        if (!enabled) return null;
        if (coef == null) {
            coef = parseCsv(coefCsv);
            if (coef == null || coef.length == 0) {
                return null; // no coefficients → keep disabled
            }
        }
        final double[] w = Arrays.copyOf(coef, coef.length);
        final double b = bias;
        return x -> {
            if (x == null || x.length == 0) {
                return new double[]{1.0, 0.0};
            }
            int n = Math.min(x.length, w.length);
            double z = b;
            for (int i = 0; i < n; i++) {
                z += x[i] * w[i];
            }
            double p1 = 1.0 / (1.0 + Math.exp(-z));
            double p0 = 1.0 - p1;
            return new double[]{p0, p1};
        };
    }

    private static double[] parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return null;
        String[] parts = csv.split(",");
        double[] arr = new double[parts.length];
        int idx = 0;
        for (String p : parts) {
            try {
                arr[idx++] = Double.parseDouble(p.trim());
            } catch (NumberFormatException ignore) {
                // skip invalid tokens
            }
        }
        if (idx == 0) return null;
        if (idx < arr.length) {
            return Arrays.copyOf(arr, idx);
        }
        return arr;
    }
}