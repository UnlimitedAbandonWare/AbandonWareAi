package strategy.core.budget;

public class CancellationToken {
  private final TimeBudget budget;

  public CancellationToken(TimeBudget budget) {
    this.budget = budget;
  }

  public boolean isCancelled() {
    return budget == null || budget.remaining() <= 0;
  }
}
