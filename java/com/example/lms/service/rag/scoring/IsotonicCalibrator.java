package com.example.lms.service.rag.scoring;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Very small monotonic piecewise-constant calibrator (isotonic regression) loader.
 * File format (tsv): raw\tcalibrated per line. If file not present, identity mapping is used.
 */
public class IsotonicCalibrator {

    private final List<double[]> table = new ArrayList<>();

    public IsotonicCalibrator() {
        // identity mapping
        table.add(new double[]{-1.0, 0.0});
        table.add(new double[]{0.0, 0.5});
        table.add(new double[]{1.0, 1.0});
    }

    public static IsotonicCalibrator load(File f) {
        IsotonicCalibrator c = new IsotonicCalibrator();
        try {
            if (f != null && f.exists() && f.isFile()) {
                c.table.clear();
                try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#")) continue;
                        String[] p = line.split("\\t");
                        if (p.length >= 2) {
                            double x = Double.parseDouble(p[0]);
                            double y = Math.max(0.0, Math.min(1.0, Double.parseDouble(p[1])));
                            c.table.add(new double[]{x, y});
                        }
                    }
                    c.table.sort((a,b) -> Double.compare(a[0], b[0]));
                }
            }
        } catch (Exception ignore) {
            // fallback to defaults
        }
        return c;
    }

    /**
     * Apply calibration to a raw score (arbitrary scale). Output is clipped to [0,1].
     */
    public double apply(double raw) {
        if (table.isEmpty()) return clip01(raw);
        double y = table.get(0)[1];
        for (int i=0;i<table.size();i++) {
            if (raw >= table.get(i)[0]) {
                y = table.get(i)[1];
            } else {
                break;
            }
        }
        return clip01(y);
    }

    private static double clip01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        return v;
    }
}