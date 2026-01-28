package com.example.lms.scheduler;

import com.example.lms.domain.ChatMessage;
import com.example.lms.moe.RgbMoeProperties;
import com.example.lms.moe.RgbResourceProbe;
import com.example.lms.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Determines whether the system is in an idle state suitable for offline auto-evolve.
 */
@Component
public class UserIdleDetector {

    private static final Logger log = LoggerFactory.getLogger(UserIdleDetector.class);

    private final ChatMessageRepository repo;
    private final RgbMoeProperties props;
    private final RgbResourceProbe resourceProbe;

    public UserIdleDetector(ChatMessageRepository repo,
                            RgbMoeProperties props,
                            RgbResourceProbe resourceProbe) {
        this.repo = repo;
        this.props = props;
        this.resourceProbe = resourceProbe;
    }

    public boolean isIdleNow() {
        try {
            // 1) time window gate
            if (!withinWindow(LocalTime.now(), props.getIdleWindowStart(), props.getIdleWindowEnd())) {
                return false;
            }

            // 2) recent chat activity gate
            Optional<ChatMessage> last = repo.findTopByOrderByCreatedAtDesc();
            if (last.isPresent()) {
                LocalDateTime t = last.get().getCreatedAt();
                if (t != null) {
                    long idleMin = Math.max(0, props.getIdleMinMinutes());
                    long minutes = Duration.between(t, LocalDateTime.now()).toMinutes();
                    if (minutes < idleMin) {
                        return false;
                    }
                }
            }

            // 3) low-pressure gate (breaker open keys not too many)
            RgbResourceProbe.Snapshot snap = resourceProbe.snapshot();
            if (snap != null && snap.breakerOpenKeys() != null && snap.breakerOpenKeys().size() >= 3) {
                return false;
            }

            return true;
        } catch (Exception e) {
            log.debug("[AutoEvolve] idle detection failed: {}", e.getMessage());
            return false;
        }
    }

    static boolean withinWindow(LocalTime now, String startHHmm, String endHHmm) {
        LocalTime start = parseHHmm(startHHmm, LocalTime.of(2, 0));
        LocalTime end = parseHHmm(endHHmm, LocalTime.of(6, 0));

        if (start.equals(end)) {
            return true;
        }

        // Handles both normal and cross-midnight windows.
        if (start.isBefore(end)) {
            return !now.isBefore(start) && now.isBefore(end);
        }
        return !now.isBefore(start) || now.isBefore(end);
    }

    static LocalTime parseHHmm(String s, LocalTime def) {
        try {
            if (s == null || s.isBlank()) return def;
            return LocalTime.parse(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
