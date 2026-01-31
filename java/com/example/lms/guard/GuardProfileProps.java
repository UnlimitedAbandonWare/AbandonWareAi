package com.example.lms.guard;

import com.example.lms.rag.model.QueryDomain;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GuardProfileProps {

    @Value("${jammini.guard.profile:PROFILE_MEMORY}")
    private String profile;

    public GuardProfile currentProfile() {
        try {
            return GuardProfile.valueOf(profile.toUpperCase());
        } catch (IllegalArgumentException e) {
            return GuardProfile.PROFILE_MEMORY; // 기본값
        }
    }

    public void setProfile(String profile) {
        if (profile == null || profile.isBlank()) {
            return;
        }
        this.profile = profile;
    }

    /**
     * Map QueryDomain to GuardProfile for EvidenceAwareGuard.
     */
    public GuardProfile profileFor(QueryDomain domain) {
        if (domain == null) {
            return GuardProfile.PROFILE_MEMORY;
        }
        return switch (domain) {
            case SENSITIVE -> GuardProfile.PROFILE_MEMORY;
            case GAME, SUBCULTURE -> GuardProfile.PROFILE_FREE;
            case STUDY, GENERAL -> GuardProfile.PROFILE_HEX;
        };
    }

    /**
     * Convenience setter for current GuardProfile (used by ChatService).
     */
    public void setCurrentProfile(GuardProfile profile) {
        if (profile != null) {
            this.profile = profile.name();
        }
    }

}
