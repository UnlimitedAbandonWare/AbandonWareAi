// src/main/java/service/rag/budget/BudgetManager.java
package service.rag.budget;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BudgetManager {
    private static final ThreadLocal<TimeBudget> CTX = new ThreadLocal<>();

    @Value("${features.timebudget.default-ms:6000}")
    private long defaultBudgetMs;

    public BudgetScope open() {
        return open(defaultBudgetMs);
    }
    public BudgetScope open(long budgetMs) {
        long now = System.nanoTime();
        long deadline = now + Math.max(0, budgetMs) * 1_000_000L;
        TimeBudget token = new TimeBudget(now, deadline);
        CTX.set(token);
        return new BudgetScope(token);
    }
    public TimeBudget current() { return CTX.get(); }

    public static final class BudgetScope implements AutoCloseable {
        private final TimeBudget token;
        private BudgetScope(TimeBudget token) { this.token = token; }
        public TimeBudget token() { return token; }
        @Override public void close() { CTX.remove(); }
    }
}