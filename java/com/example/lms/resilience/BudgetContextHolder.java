
package com.example.lms.resilience;


public class BudgetContextHolder {
    private static final ThreadLocal<BudgetContext> CTX = new ThreadLocal<>();
    public static void set(BudgetContext c) { CTX.set(c); }
    public static BudgetContext get() { return CTX.get(); }
    public static void clear() { CTX.remove(); }
}