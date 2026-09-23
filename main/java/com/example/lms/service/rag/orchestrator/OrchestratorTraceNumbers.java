package com.example.lms.service.rag.orchestrator;

final class OrchestratorTraceNumbers {
    private OrchestratorTraceNumbers() {
    }

    static double parseDouble(Object value, double fallback, String stage) {
        if (value instanceof Number number) {
            double parsed = number.doubleValue();
            return finite(parsed, stage) ? parsed : fallback;
        }
        if (value == null) {
            return fallback;
        }
        try {
            double parsed = Double.parseDouble(String.valueOf(value));
            return finite(parsed, stage) ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            throw ignored;
        }
    }

    static int parseNonNegativeInt(Object value, String stage) {
        Integer parsed = parseNullableInt(value, stage);
        return parsed == null ? 0 : Math.max(0, parsed);
    }

    static Integer parseNullableInt(Object value, String stage) {
        if (value instanceof Number number) {
            return finite(number.doubleValue(), stage) ? number.intValue() : null;
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            throw ignored;
        }
    }

    private static boolean finite(double value, String stage) {
        if (Double.isFinite(value)) {
            return true;
        }
        throw new NumberFormatException(stage + ": non-finite");
    }
}
