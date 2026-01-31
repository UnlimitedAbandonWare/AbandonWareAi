package com.example.lms.nova;


public final class NovaAnswerBadges {
    private NovaAnswerBadges() {}
    public static String prependIfRuleBreak(String answer) {
        if (NovaRequestContext.hasRuleBreak()) {
            return "【주의: 확장 탐색 모드 적용됨】\n" + (answer == null ? "" : answer);
        }
        return answer;
    }
}