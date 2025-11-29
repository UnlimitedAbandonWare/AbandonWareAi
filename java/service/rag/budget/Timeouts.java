// src/main/java/service/rag/budget/Timeouts.java
package service.rag.budget;

public final class Timeouts {
    private Timeouts() {}
    /**
     * 희망 타임아웃(desiredMs)을 예산(remaining)과 안전여유(safetyMs)로 캡.
     * 예산이 부족하면 0 또는 최소치로 하여 빠른 폴백/스킵이 가능.
     */
    public static int capToBudgetMillis(BudgetManager mgr, int desiredMs, int safetyMs) {
        TimeBudget b = mgr.current();
        if (b == null) return desiredMs;
        long rem = Math.max(0, b.remainingMillis() - Math.max(0, safetyMs));
        return (int)Math.max(0, Math.min(desiredMs, rem));
    }
}