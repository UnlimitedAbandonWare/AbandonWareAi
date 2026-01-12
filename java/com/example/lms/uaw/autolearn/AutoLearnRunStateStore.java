package com.example.lms.uaw.autolearn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class AutoLearnRunStateStore {

    private static final Logger log = LoggerFactory.getLogger(AutoLearnRunStateStore.class);
    private final ObjectMapper om = new ObjectMapper();

    public AutoLearnRunState load(Path path, String day) {
        try {
            if (path == null || !Files.exists(path)) {
                return AutoLearnRunState.newDefault(day);
            }
            AutoLearnRunState s = om.readValue(Files.readString(path, StandardCharsets.UTF_8), AutoLearnRunState.class);
            if (s.day == null || !s.day.equals(day)) {
                AutoLearnRunState d = AutoLearnRunState.newDefault(day);
                d.lastFinishEpochMs = s.lastFinishEpochMs;
                d.consecutiveFailures = s.consecutiveFailures;
                d.backoffUntilEpochMs = s.backoffUntilEpochMs;
                return d;
            }
            return s;
        } catch (Exception e) {
            log.debug("[UAW] failed to load budget state {}: {}", path, e.toString());
            return AutoLearnRunState.newDefault(day);
        }
    }

    public void save(Path path, AutoLearnRunState state) {
        if (path == null || state == null) return;
        try {
            Files.createDirectories(path.getParent());
            Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp");
            Files.writeString(tmp, om.writeValueAsString(state), StandardCharsets.UTF_8);
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.debug("[UAW] failed to save budget state {}: {}", path, e.toString());
        }
    }
}
