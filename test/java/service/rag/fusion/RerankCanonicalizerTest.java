package service.rag.fusion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class RerankCanonicalizerTest {
  @Test void stripTrackingParams(){
    RerankCanonicalizer c = new RerankCanonicalizer();
    assertEquals("https://ex.com/", c.canonical("https://ex.com/?utm_source=x&gclid=y"));
  }
}
