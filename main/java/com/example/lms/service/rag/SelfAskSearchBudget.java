package com.example.lms.service.rag;

final class SelfAskSearchBudget {
    private int left;

    SelfAskSearchBudget(int max) {
        this.left = Math.max(0, max);
    }

    boolean tryConsume() {
        return left-- > 0;
    }

    int remaining() {
        return Math.max(0, left);
    }
}
