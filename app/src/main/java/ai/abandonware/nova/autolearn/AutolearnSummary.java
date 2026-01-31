import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
package ai.abandonware.nova.autolearn;

@ConditionalOnProperty(prefix = "autolearn", name = "enabled", havingValue = "true", matchIfMissing = false)
public class AutolearnSummary {
    public final int total;
    public final int passed;
    public final int added;
    public final int indexed;

    public AutolearnSummary(int total, int passed, int added, int indexed) {
        this.total = total;
        this.passed = passed;
        this.added = added;
        this.indexed = indexed;
    }

    @Override
    public String toString() {
        return "total=" + total + ", passed=" + passed + ", added=" + added + ", indexed=" + indexed;
    }
}