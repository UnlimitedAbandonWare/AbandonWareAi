package com.example.lms.service.rag.plan;

import com.example.lms.domain.enums.MemoryMode;
import com.example.lms.guard.GuardProfile;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Maps YAML plan strings (e.g. guard-profile, memory-profile) into
 * the concrete runtime policies used by {@link com.example.lms.service.ChatWorkflow}.
 */
@Component
public class PlanPolicyMapper {

    public GuardProfile resolveGuardProfile(String planGuardProfile, GuardProfile fallback) {
        if (!StringUtils.hasText(planGuardProfile)) {
            return fallback;
        }

        String key = planGuardProfile.trim().toUpperCase(Locale.ROOT).replace('-', '_');

        return switch (key) {
            case "SAFE" -> GuardProfile.SAFE;
            case "BRAVE" -> GuardProfile.BRAVE;
            case "STRICT" -> GuardProfile.STRICT;
            case "BALANCED", "NORMAL" -> GuardProfile.NORMAL;
            case "SUBCULTURE" -> GuardProfile.SUBCULTURE;
            case "WILD" -> GuardProfile.WILD;
            case "PROFILE_MEMORY" -> GuardProfile.PROFILE_MEMORY;
            case "PROFILE_FREE" -> GuardProfile.PROFILE_FREE;
            default -> {
                // Last resort: try enum parsing, else fallback
                try {
                    yield GuardProfile.valueOf(key);
                } catch (Exception ignored) {
                    yield fallback;
                }
            }
        };
    }

    /**
     * Memory profile is a plan-level concept; we map it into {@link MemoryMode}.
     *
     * Heuristics:
     * - deep_memory/memory: FULL (read+write)
     * - projection: HYBRID (read, no write)
     * - wild_pro/off/none: EPHEMERAL (no read, no write)
     */
    public MemoryMode resolveMemoryMode(String planMemoryProfile, MemoryMode fallback) {
        if (!StringUtils.hasText(planMemoryProfile)) {
            return fallback;
        }

        String key = planMemoryProfile.trim().toLowerCase(Locale.ROOT);

        return switch (key) {
            case "deep_memory", "memory", "full" -> MemoryMode.FULL;
            case "projection", "hybrid" -> MemoryMode.HYBRID;
            case "wild_pro", "off", "none", "ephemeral" -> MemoryMode.EPHEMERAL;
            default -> fallback;
        };
    }

    public String resolveMemoryProfileLabel(MemoryMode mode) {
        return mode == MemoryMode.EPHEMERAL ? "NONE" : "MEMORY";
    }
}
