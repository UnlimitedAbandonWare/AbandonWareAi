package com.abandonwareai.nova.autolearn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;

/**
 * File-backed budget manager (demo-1).
 */
@Component
public class AutoLearnBudgetManager {

    private static final Logger log = LoggerFactory.getLogger(AutoLearnBudgetManager.class);
    private final ObjectMapper om = new ObjectMapper();

    private final Path statePath;
    private final int maxRunsPerDay;
    private final int minIntervalSeconds;

    public AutoLearnBudgetManager(
            @Value("${idle.budget.statePath:data/idle/autolearn_state.json}") String statePath,
            @Value("${idle.budget.maxRunsPerDay:24}") int maxRunsPerDay,
            @Value("${idle.budget.minIntervalSeconds:300}") int minIntervalSeconds) {
        this.statePath = Path.of(statePath);
        this.maxRunsPerDay = maxRunsPerDay;
        this.minIntervalSeconds = minIntervalSeconds;
    }

    public record Lease(long startEpochMs) {}

    private static final class State {
        public String day;
        public int runsToday;
        public long lastStartEpochMs;
        public long lastFinishEpochMs;

        static State fresh(String day) {
            State s = new State();
            s.day = day;
            s.runsToday = 0;
            s.lastStartEpochMs = 0;
            s.lastFinishEpochMs = 0;
            return s;
        }
    }

    public synchronized Lease tryAcquire() {
        long now = System.currentTimeMillis();
        String today = LocalDate.now().toString();
        State s = load(today);

        if (s.runsToday >= maxRunsPerDay) {
            return null;
        }
        long minIntervalMs = Math.max(0, minIntervalSeconds) * 1000L;
        if (s.lastStartEpochMs > 0 && (now - s.lastStartEpochMs) < minIntervalMs) {
            return null;
        }

        s.day = today;
        s.runsToday++;
        s.lastStartEpochMs = now;
        save(s);
        return new Lease(now);
    }

    public synchronized void onFinish(Lease lease) {
        if (lease == null) return;
        String today = LocalDate.now().toString();
        State s = load(today);
        s.lastFinishEpochMs = System.currentTimeMillis();
        save(s);
    }

    private State load(String today) {
        try {
            if (!Files.exists(statePath)) {
                return State.fresh(today);
            }
            State s = om.readValue(Files.readString(statePath, StandardCharsets.UTF_8), State.class);
            if (s.day == null || !s.day.equals(today)) {
                return State.fresh(today);
            }
            return s;
        } catch (Exception e) {
            log.debug("failed to load budget state: {}", e.toString());
            return State.fresh(today);
        }
    }

    private void save(State s) {
        try {
            Files.createDirectories(statePath.getParent());
            Path tmp = statePath.resolveSibling(statePath.getFileName().toString() + ".tmp");
            Files.writeString(tmp, om.writeValueAsString(s), StandardCharsets.UTF_8);
            Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.debug("failed to save budget state: {}", e.toString());
        }
    }
}
