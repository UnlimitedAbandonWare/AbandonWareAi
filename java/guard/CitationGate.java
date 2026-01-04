package guard;
import java.util.List;
public class CitationGate {
  private int minCount = 3;
  public void setMinCount(int n){ this.minCount = n; }
  public void verify(List<?> citations){
    if (citations == null || citations.size() < minCount) {
      throw new IllegalStateException("Insufficient citations");
    }
  }
}