package com.example.lms.guard;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.example.lms.guard.FinalSigmoidGateTest
 * Role: config
 * Feature Flags: sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.example.lms.guard.FinalSigmoidGateTest
role: config
flags: [sse]
*/
public class FinalSigmoidGateTest {
  @Test
  void pass_when_score_high_enough() {
    FinalSigmoidGate gate = new FinalSigmoidGate(8.0, 0.75, 0.90, true);
    // Compose with rrf=0.9, cross=0.9, trust=0.9 -> expect pass
    assertThat(gate.pass(0.9, 0.9, 0.9)).isTrue();
    // Lower score mix -> likely fail
    assertThat(gate.pass(0.6, 0.6, 0.6)).isFalse();
  }
}