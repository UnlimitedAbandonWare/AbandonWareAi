package com.example.lms.uaw.thumbnail;

import com.example.lms.domain.ChatMessage;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.uaw.presence.UserAbsenceGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 유저가 없고, 시스템 여유가 있을 때, 최근 user 질문을 골라 1-line thumbnail을 생성합니다.
 */
@Component
public class UawThumbnailOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(UawThumbnailOrchestrator.class);

    private final UawThumbnailProperties props;
    private final UawThumbnailBudgetManager budget;
    private final UserAbsenceGate absenceGate;
    private final ChatMessageRepository chatMessageRepository;
    private final KnowledgeBaseService knowledgeBase;
    private final UawThumbnailService thumbnailService;

    public UawThumbnailOrchestrator(
            UawThumbnailProperties props,
            UawThumbnailBudgetManager budget,
            UserAbsenceGate absenceGate,
            ChatMessageRepository chatMessageRepository,
            KnowledgeBaseService knowledgeBase,
            UawThumbnailService thumbnailService
    ) {
        this.props = props;
        this.budget = budget;
        this.absenceGate = absenceGate;
        this.chatMessageRepository = chatMessageRepository;
        this.knowledgeBase = knowledgeBase;
        this.thumbnailService = thumbnailService;
    }

    @Scheduled(fixedDelayString = "${uaw.thumbnail.tick-ms:300000}")
    public void tick() {
        if (!props.isEnabled()) return;

        if (!absenceGate.isUserAbsentNow()) {
            log.debug("[UAW_THUMB] skip: user present");
            return;
        }

        double cpu = safeCpuLoad();
        if (cpu >= 0.0 && cpu > props.getIdleCpuThreshold()) {
            log.info("[UAW_THUMB] skip: cpu high load={} threshold={}", cpu, props.getIdleCpuThreshold());
            return;
        }

        Optional<String> topicOpt = pickTopicCandidate();
        if (topicOpt.isEmpty()) {
            log.debug("[UAW_THUMB] no candidate topic");
            return;
        }

        long now = Instant.now().toEpochMilli();
        Optional<UawThumbnailBudgetManager.Token> tokenOpt = budget.tryAcquire(now);
        if (tokenOpt.isEmpty()) {
            log.info("[UAW_THUMB] skip: budget/cooldown/backoff");
            return;
        }

        UawThumbnailBudgetManager.Token token = tokenOpt.get();
        String topic = topicOpt.get();

        try {
            thumbnailService.generateAndPersist(topic);
            budget.onSuccess(token);
        } catch (Exception e) {
            budget.onFailure(token, e);
        }
    }

    private Optional<String> pickTopicCandidate() {
        int lookback = Math.max(10, Math.min(200, props.getCandidateLookback()));
        List<ChatMessage> recent = chatMessageRepository.findByRoleOrderByIdDesc(
                "user",
                PageRequest.of(0, lookback)
        );

        for (ChatMessage m : recent) {
            String content = (m == null) ? null : m.getContent();
            if (content == null) continue;
            String topic = content.trim().replaceAll("\\s+", " ");
            if (topic.isBlank()) continue;

            // basic filters (avoid long dumps/urls)
            String lower = topic.toLowerCase();
            if (lower.startsWith("curl ") || lower.startsWith("wget ")) continue;
            if (lower.contains("http://") || lower.contains("https://")) continue;

            if (topic.length() > props.getMaxTopicChars()) {
                topic = topic.substring(0, props.getMaxTopicChars()).trim();
            }

            String entityName = props.getKnowledgeDomain() + "::" + topic;
            if (knowledgeBase.find(props.getKnowledgeDomain(), entityName).isPresent()) continue;

            return Optional.of(topic);
        }

        return Optional.empty();
    }

    private double safeCpuLoad() {
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean mx) {
                double v = mx.getSystemCpuLoad();
                if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return -1.0;
                if (v > 1.0) v = v / 100.0;
                return v;
            }
        } catch (Exception ignore) {
        }
        return -1.0;
    }
}
