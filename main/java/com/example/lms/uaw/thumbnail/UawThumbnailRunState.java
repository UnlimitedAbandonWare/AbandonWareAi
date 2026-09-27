package com.example.lms.uaw.thumbnail;

import java.time.LocalDate;

/**
 * UAW Thumbnail 실행 상태(간단한 rate/budget/backoff 관리).
 */
public record UawThumbnailRunState(
        LocalDate day,
        int runsToday,
        long lastStartMillis,
        long lastSuccessMillis,
        long backoffUntilMillis,
        int consecutiveFailures,
        String lastOutcome
) {

    public UawThumbnailRunState {
        lastOutcome = normalizeOutcome(lastOutcome);
    }

    public static UawThumbnailRunState empty() {
        return new UawThumbnailRunState(LocalDate.now(), 0, 0L, 0L, 0L, 0, "none");
    }

    public UawThumbnailRunState resetIfNewDay(LocalDate now) {
        if (day == null || !day.equals(now)) {
            return new UawThumbnailRunState(now, 0, 0L, 0L, 0L, 0, lastOutcome);
        }
        return this;
    }

    public UawThumbnailRunState withLastOutcome(String outcome) {
        return new UawThumbnailRunState(
                day,
                runsToday,
                lastStartMillis,
                lastSuccessMillis,
                backoffUntilMillis,
                consecutiveFailures,
                outcome
        );
    }

    private static String normalizeOutcome(String outcome) {
        if ("succeeded".equals(outcome) || "failed".equals(outcome)) {
            return outcome;
        }
        return "none";
    }
}
