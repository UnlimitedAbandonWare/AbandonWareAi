package com.nova.protocol.fusion;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.nova.protocol.fusion.CvarAggregatorTest
 * Role: config
 * Feature Flags: sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.nova.protocol.fusion.CvarAggregatorTest
role: config
flags: [sse]
*/
public class CvarAggregatorTest {
  @Test
  void computes_cvar_upper_tail() {
    CvarAggregator c = new CvarAggregator();
    double v = c.cvar(new double[]{0.1,0.2,0.9,0.95}, 0.25);
    assertThat(v).isBetween(0.85, 1.0);
  }
}