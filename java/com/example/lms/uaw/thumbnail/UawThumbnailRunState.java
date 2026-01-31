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
        int consecutiveFailures
) {

    public static UawThumbnailRunState empty() {
        return new UawThumbnailRunState(LocalDate.now(), 0, 0L, 0L, 0L, 0);
    }

    public UawThumbnailRunState resetIfNewDay(LocalDate now) {
        if (day == null || !day.equals(now)) {
            return new UawThumbnailRunState(now, 0, 0L, 0L, 0L, 0);
        }
        return this;
    }
}
