package com.example.lms.scheduler;

import com.example.lms.entity.TranslationMemory;
import com.example.lms.repository.TranslationMemoryRepository;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.guard.EvidenceAwareGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "memory.pending-soak.enabled", havingValue = "true")
public class PendingMemorySoakScheduler {

    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern W_MARKER = Pattern.compile("\\[W\\d+\\]");

    private final TranslationMemoryRepository repo;
    private final VectorStoreService vectorStoreService;

    @Value("${memory.pending-soak.batch-size:25}")
    private int batchSize;

    @Value("${memory.pending-soak.max-age-hours:72}")
    private long maxAgeHours;

    @Value("${memory.pending-soak.min-evidence:1}")
    private int minEvidence;

    @Scheduled(fixedDelayString = "${memory.pending-soak.interval-ms:300000}")
    public void soakPendingMemories() {
        try {
            var page = repo.findByStatusOrderByCreatedAtAsc(TranslationMemory.MemoryStatus.PENDING, PageRequest.of(0, Math.max(1, batchSize)));
            if (page == null || page.isEmpty()) return;

            for (TranslationMemory tm : page.getContent()) {
                if (tm == null) continue;

                LocalDateTime createdAt = tm.getCreatedAt();
                if (createdAt != null && maxAgeHours > 0) {
                    long ageH = Math.max(0, Duration.between(createdAt, LocalDateTime.now()).toHours());
                    if (ageH > maxAgeHours) continue;
                }

                String content = tm.getContent();
                if (content == null || content.isBlank()) continue;

                int evidence = countEvidenceSignals(content);
                if (evidence < Math.max(1, minEvidence)) continue;

                boolean weak = EvidenceAwareGuard.looksWeak(content);
                if (weak) continue;

                // promote
                tm.setStatus(TranslationMemory.MemoryStatus.ACTIVE);
                repo.save(tm);

                // re-index
                try {
                    Map<String, Object> meta = new HashMap<>();
                    meta.put("sourceTag", "PENDING_SOAK");
                    meta.put("evidenceSignals", evidence);
                    vectorStoreService.enqueue(tm.getSessionId(), content, meta);
                } catch (Exception ignore) { }

                log.info("[PENDING_SOAK] promoted memory id={} session={} evidence={}", tm.getId(), tm.getSessionId(), evidence);
            }
        } catch (Exception e) {
            log.warn("[PENDING_SOAK] failed: {}", e.toString());
        }
    }

    private static int countEvidenceSignals(String s) {
        if (s == null || s.isBlank()) return 0;
        int n = 0;
        if (URL.matcher(s).find()) n++;
        var m = W_MARKER.matcher(s);
        while (m.find()) n++;
        return n;
    }
}
