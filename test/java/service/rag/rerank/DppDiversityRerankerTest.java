package service.rag.rerank;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class DppDiversityRerankerTest {
  @Test void sanity() {
    DppDiversityReranker d = new DppDiversityReranker();
    var a = new DppDiversityReranker.Item("a", 0.9f, new float[]{1,0});
    var b = new DppDiversityReranker.Item("b", 0.85f, new float[]{1,0.01f});
    var c = new DppDiversityReranker.Item("c", 0.7f, new float[]{0,1});
    var out = d.rerank(Arrays.asList(a,b,c), 2, 0.65);
    assertTrue(out.size()>=2);
  }
}
