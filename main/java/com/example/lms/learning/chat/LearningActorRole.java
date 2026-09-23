package com.example.lms.learning.chat;

import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.Locale;

public enum LearningActorRole {
    STUDENT,
    TEACHER,
    ADMIN,
    ANONYMOUS;

    public String trainingRagLabel() {
        return switch (this) {
            case STUDENT -> "TRAINING_USER";
            case TEACHER -> "TRAINING_SUPPORT";
            case ADMIN -> "RAG_ADMIN";
            case ANONYMOUS -> "ANONYMOUS";
        };
    }

    public static LearningActorRole fromAuthorities(Collection<? extends GrantedAuthority> authorities) {
        if (authorities == null || authorities.isEmpty()) {
            return ANONYMOUS;
        }
        boolean student = false;
        boolean teacher = false;
        for (GrantedAuthority authority : authorities) {
            if (authority == null || authority.getAuthority() == null) {
                continue;
            }
            String role = authority.getAuthority().trim().toUpperCase(Locale.ROOT);
            if ("ROLE_ADMIN".equals(role) || "ROLE_VECTOR_ADMIN".equals(role) || "ADMIN".equals(role)) {
                return ADMIN;
            }
            if ("ROLE_PROF".equals(role) || "ROLE_TEACHER".equals(role) || "ROLE_INSTRUCTOR".equals(role)) {
                teacher = true;
            }
            if ("ROLE_STUDENT".equals(role) || "ROLE_LEARNER".equals(role)) {
                student = true;
            }
        }
        if (teacher) {
            return TEACHER;
        }
        if (student) {
            return STUDENT;
        }
        return ANONYMOUS;
    }
}
