package guard;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class CitationGateTest {
  static class C implements CitationGate.Citation {
    final boolean t; C(boolean t){this.t=t;} public boolean isTrusted(){return t;}
  }
  @Test void rejectWhenBelowThreshold(){
    CitationGate g = new CitationGate(3);
    assertThrows(GateRejected.class, () -> g.check(Arrays.asList(new C(true), new C(false))));
  }
}
