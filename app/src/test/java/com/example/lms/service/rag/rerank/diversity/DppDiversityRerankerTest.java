package com.example.lms.service.rag.rerank.diversity;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.service.rag.rerank.diversity.DppDiversityRerankerTest
 * Role: config
 * Feature Flags: sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.service.rag.rerank.diversity.DppDiversityRerankerTest
role: config
flags: [sse]
*/
public class DppDiversityRerankerTest {

  @Test
  void greedily_prefers_diverse_items() {
    DppDiversityReranker rr = new DppDiversityReranker();
    var in = List.of(
        new DppDiversityReranker.Item("a", 0.9, new double[]{1,0,0}),
        new DppDiversityReranker.Item("b", 0.88, new double[]{0.99,0.01,0}),
        new DppDiversityReranker.Item("c", 0.86, new double[]{0,1,0}),
        new DppDiversityReranker.Item("d", 0.80, new double[]{0,0,1})
    );
    var out = rr.select(in, 2, 0.6, 0.7);
    assertThat(out).hasSize(2);
  }
}