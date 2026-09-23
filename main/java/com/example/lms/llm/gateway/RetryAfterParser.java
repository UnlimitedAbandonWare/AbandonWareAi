package com.example.lms.llm.gateway;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class RetryAfterParser {

    private RetryAfterParser() {
    }

    public static Optional<Duration> parse(String value) {
        return parse(value, Clock.systemUTC());
    }

    public static Optional<Duration> parse(String value, Clock clock) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String raw = value.trim();
        try {
            long seconds = Long.parseLong(raw);
            return Optional.of(Duration.ofSeconds(Math.max(0L, seconds)));
        } catch (NumberFormatException ignore) {
            traceSuppressed("deltaSeconds", ignore);
            return parseHttpDate(raw, clock);
        }
    }

    private static Optional<Duration> parseHttpDate(String raw, Clock clock) {
        try {
            Instant target = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration duration = Duration.between(clock.instant(), target);
            return Optional.of(duration.isNegative() ? Duration.ZERO : duration);
        } catch (DateTimeParseException ignore) {
            traceSuppressed("httpDate", ignore);
            return Optional.empty();
        }
    }

    private static void traceSuppressed(String stage, Throwable failure) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        TraceStore.put("llm.gateway.retryAfter.suppressed." + safeStage, true);
        TraceStore.put("llm.gateway.retryAfter.suppressed." + safeStage + ".errorType", errorTypeFor(safeStage));
    }

    private static String errorTypeFor(String stage) {
        return "httpDate".equals(stage) ? "invalid_date" : "invalid_number";
    }
}
