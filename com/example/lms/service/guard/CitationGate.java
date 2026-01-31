package com.example.lms.service.guard;

import java.util.*;

public final class CitationGate {

    private final int minCitations;

    public CitationGate(int min) {
        this.minCitations = min;
    }

    /**
     * 기존 시그니처 유지:
     * - GuardContext 없이도 동작하도록 남겨둔다.
     * - 내부적으로는 trusted citation 수를 기반으로 검사한다.
     */
    public void check(List<Citation> cites) {
        if (!hasEnoughTrusted(cites, null)) {
            long trusted = countTrusted(cites);
            throw new GateRejected("Not enough trusted citations (need >= " + minCitations + ", got " + trusted + ")");
        }
    }

    /**
     * 모드(GuardContext)에 따라 최소 인용 개수를 동적으로 조정하는 신규 시그니처.
     * SAFE  모드  : 기본 minCitations 유지
     * BRAVE 모드  : minCitations - 1 (최소 1)
     * ZERO_BREAK  : 1
     * RULE_BREAK  : 1
     */
    public void check(List<Citation> cites, GuardContext ctx) {
        if (!hasEnoughTrusted(cites, ctx)) {
            long trusted = countTrusted(cites);
            int min = resolveMinCitations(ctx);
            throw new GateRejected("Not enough trusted citations (need >= " + min + ", got " + trusted + ")");
        }
    }

    public boolean hasEnoughTrusted(List<Citation> cites, GuardContext ctx) {
        long trusted = countTrusted(cites);
        int min = resolveMinCitations(ctx);
        return trusted >= min;
    }

    private long countTrusted(List<Citation> cites) {
        if (cites == null) {
            return 0L;
        }
        long trusted = 0L;
        for (Citation c : cites) {
            if (c != null && c.isTrusted()) {
                trusted++;
            }
        }
        return trusted;
    }

    private int resolveMinCitations(GuardContext ctx) {
        // 기본값은 생성자에서 받은 minCitations
        int min = this.minCitations;
        if (ctx == null) {
            return min;
        }
        String mode = Optional.ofNullable(ctx.getMode()).orElse("SAFE");
        return switch (mode) {
            case "BRAVE" -> Math.max(1, min - 1);
            case "ZERO_BREAK", "RULE_BREAK" -> 1;
            default -> min;
        };
    }

    public static interface Citation {
        boolean isTrusted();
    }
}
