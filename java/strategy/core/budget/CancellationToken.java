package strategy.core.budget;

public class CancellationToken {
  private final TimeBudget budget;
  public CancellationToken(TimeBudget b) { this.budget = b; }
  public boolean isCancelled() { return budget.remaining() <= 0; }
}
