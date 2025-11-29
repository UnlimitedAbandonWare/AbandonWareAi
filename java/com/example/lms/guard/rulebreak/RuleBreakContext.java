package com.example.lms.guard.rulebreak;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;



@Data
@Builder
public class RuleBreakContext {
    private boolean active;
    private RuleBreakPolicy policy;
    private String tokenHash;
    private Instant expiresAt;
    private String requestId;
    private String sessionId;

    public boolean isValid() {
        return active && policy != null && expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    public static RuleBreakContext inactive() {
        return RuleBreakContext.builder().active(false).policy(null).expiresAt(Instant.EPOCH).build();
    }
}