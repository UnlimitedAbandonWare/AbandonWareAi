package com.example.lms.orchestration;

import java.util.List;

/**
 * Stable baseline plus bounded emergency gears for dual-gear cognitive routing.
 */
public record GoldenRatioProfile(Gear baseline, List<Gear> gachaGears) {

    public GoldenRatioProfile {
        baseline = baseline == null ? new Gear("golden-baseline", 0.30d, 0.20d, 1.0d) : baseline;
        gachaGears = gachaGears == null || gachaGears.isEmpty()
                ? defaultGears()
                : List.copyOf(gachaGears);
    }

    public static GoldenRatioProfile defaults() {
        return new GoldenRatioProfile(
                new Gear("golden-baseline", 0.30d, 0.20d, 1.0d),
                defaultGears());
    }

    private static List<Gear> defaultGears() {
        return List.of(
                new Gear("gacha-wide", 0.62d, 0.72d, 0.34d),
                new Gear("gacha-tail", 0.76d, 0.86d, 0.33d),
                new Gear("gacha-critic", 0.48d, 0.58d, 0.33d));
    }

    public record Gear(String name, double temperature, double topP, double weight) {
        public Gear {
            name = name == null || name.isBlank() ? "gear" : name.trim();
            temperature = clamp(temperature, 0.0d, 2.0d);
            topP = clamp(topP, 0.0d, 1.0d);
            weight = clamp(weight, 0.0d, 1.0d);
        }
    }

    private static double clamp(double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }
}
