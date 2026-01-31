package policy;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class KAllocationPolicyTest {
  @Test void sumsToTotal(){
    KAllocationPolicy p = new KAllocationPolicy();
    Map<KAllocationPolicy.Source,Double> priors = new EnumMap<>(KAllocationPolicy.Source.class);
    priors.put(KAllocationPolicy.Source.WEB, 1.0);
    priors.put(KAllocationPolicy.Source.VECTOR, 0.5);
    priors.put(KAllocationPolicy.Source.KG, 0.3);
    Map<KAllocationPolicy.Source,Integer> out = p.allocate(priors, 32);
    int sum = out.values().stream().reduce(0, Integer::sum);
    assertEquals(32, sum);
  }
}
