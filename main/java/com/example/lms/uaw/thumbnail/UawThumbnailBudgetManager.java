package com.example.lms.uaw.thumbnail;

import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Optional;

/**
 * UAW Thumbnail 작업의 실행 빈도/쿨다운/백오프를 관리합니다.
 */
@Component
public class UawThumbnailBudgetManager {

    private static final Logger log = LoggerFactory.getLogger(UawThumbnailBudgetManager.class);

    private final UawThumbnailProperties props;
    private final UawThumbnailRunStateStore store;

    public record Token(long startedAtMillis) {}

    public UawThumbnailBudgetManager(UawThumbnailProperties props, UawThumbnailRunStateStore store) {
        this.props = props;
        this.store = store;
    }

    public Optional<Token> tryAcquire(long nowMillis) {
        Path path = Path.of(props.getStatePath());
        UawThumbnailRunState state = store.load(path).resetIfNewDay(LocalDate.now());

        // daily cap
        if (state.runsToday() >= props.getMaxRunsPerDay()) {
            return Optional.empty();
        }

        // min interval
        if (state.lastStartMillis() > 0
                && (nowMillis - state.lastStartMillis()) < props.getMinIntervalSeconds() * 1000L) {
            return Optional.empty();
        }

        // backoff
        if (state.backoffUntilMillis() > nowMillis) {
            return Optional.empty();
        }

        UawThumbnailRunState next = new UawThumbnailRunState(
                LocalDate.now(),
                state.runsToday() + 1,
                nowMillis,
                state.lastSuccessMillis(),
                state.backoffUntilMillis(),
                state.consecutiveFailures(),
                state.lastOutcome()
        );
        store.save(path, next);

        return Optional.of(new Token(nowMillis));
    }

    public void onSuccess(Token token) {
        if (token == null) return;
        Path path = Path.of(props.getStatePath());
        UawThumbnailRunState state = store.load(path).resetIfNewDay(LocalDate.now());

        UawThumbnailRunState next = new UawThumbnailRunState(
                LocalDate.now(),
                state.runsToday(),
                state.lastStartMillis(),
                System.currentTimeMillis(),
                0L,
                0,
                state.lastOutcome()
        );
        store.save(path, next);
    }

    public void onFailure(Token token, Throwable t) {
        if (token == null) return;
        Path path = Path.of(props.getStatePath());
        UawThumbnailRunState state = store.load(path).resetIfNewDay(LocalDate.now());

        int failures = Math.max(1, state.consecutiveFailures() + 1);
        long backoffSec = Math.min(
                props.getMaxBackoffSeconds(),
                props.getBaseBackoffSeconds() * (1L << Math.min(6, failures - 1))
        );
        long until = System.currentTimeMillis() + backoffSec * 1000L;

        UawThumbnailRunState next = new UawThumbnailRunState(
                LocalDate.now(),
                state.runsToday(),
                state.lastStartMillis(),
                state.lastSuccessMillis(),
                until,
                failures,
                state.lastOutcome()
        );
        store.save(path, next);

        log.warn("[UAW_THUMB] failure -> backoff {}s (failures={}) errorHash={} errorLength={}",
                backoffSec, failures, SafeRedactor.hashValue(messageOf(t)), messageLength(t));
    }

    private static String messageOf(Throwable t) {
        return t == null ? null : t.getMessage();
    }

    private static int messageLength(Throwable t) {
        String msg = messageOf(t);
        return msg == null ? 0 : msg.length();
    }
}
