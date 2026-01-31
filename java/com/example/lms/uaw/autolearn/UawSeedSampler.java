package com.example.lms.uaw.autolearn;

import com.example.lms.domain.ChatMessage;
import com.example.lms.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Samples seed questions for UAW autolearn.
 *
 * <p>Primary goal: prefer recent real user questions (role=user) so autolearn is grounded
 * in actual traffic rather than only static seeds.
 */
@Component
public class UawSeedSampler {

    private static final Logger log = LoggerFactory.getLogger(UawSeedSampler.class);

    private final ChatMessageRepository chatMessageRepository;
    private final SecureRandom rng = new SecureRandom();

    public UawSeedSampler(ChatMessageRepository chatMessageRepository) {
        this.chatMessageRepository = chatMessageRepository;
    }

    public List<String> sampleSeeds(UawAutolearnProperties props, int desired, List<String> fallbackSeeds) {
        if (props == null || props.getSeed() == null) {
            return safeFallback(desired, fallbackSeeds);
        }

        UawAutolearnProperties.Seed cfg = props.getSeed();
        if (!cfg.isHistoryEnabled()) {
            return safeFallback(desired, fallbackSeeds);
        }

        int want = Math.max(0, desired);
        int pool = Math.max(cfg.getHistoryPoolSize(), Math.max(want * 3, 30));

        List<ChatMessage> msgs;
        try {
            msgs = chatMessageRepository.findByRoleOrderByIdDesc("user", PageRequest.of(0, pool));
        } catch (Exception e) {
            log.debug("[UAW] seed sampling failed; fallback: {}", e.toString());
            return safeFallback(desired, fallbackSeeds);
        }

        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (ChatMessage m : msgs) {
            if (m == null) continue;
            String seed = normalizeSeed(m.getContent(), cfg.getMinChars(), cfg.getMaxChars());
            if (seed == null) continue;
            uniq.add(seed);
        }

        List<String> poolList = new ArrayList<>(uniq);
        Collections.shuffle(poolList, rng);

        int take = Math.min(want, poolList.size());
        List<String> out = new ArrayList<>(poolList.subList(0, take));

        if (out.size() < want && cfg.isAllowStaticFallback()) {
            // Fill remaining slots from fallback seeds (stable but non-duplicated).
            for (String s : fallbackSeeds) {
                if (out.size() >= want) break;
                String seed = normalizeSeed(s, cfg.getMinChars(), cfg.getMaxChars());
                if (seed == null) continue;
                if (!out.contains(seed)) out.add(seed);
            }
        }

        return out;
    }

    private static List<String> safeFallback(int desired, List<String> fallback) {
        if (fallback == null || fallback.isEmpty()) return List.of();
        int n = Math.min(Math.max(desired, 0), fallback.size());
        return new ArrayList<>(fallback.subList(0, n));
    }

    private static String normalizeSeed(String raw, int minChars, int maxChars) {
        if (raw == null) return null;
        String s = raw.strip();
        if (s.isEmpty()) return null;

        // Basic quality filters (avoid obvious probes / URLs / commands).
        String lower = s.toLowerCase();
        if (lower.contains("curl ") || lower.contains("wget ") || lower.contains("rm -") || lower.contains("powershell")) return null;
        if (lower.contains("http://") || lower.contains("https://")) return null;

        int min = Math.max(minChars, 1);
        int max = Math.max(maxChars, 40);
        if (s.length() < min) return null;
        if (s.length() > max) {
            s = s.substring(0, max);
        }
        return s;
    }
}
