package com.example.lms.guard;

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
}
