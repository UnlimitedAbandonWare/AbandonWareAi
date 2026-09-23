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
  void allow_when_score_above_threshold_in_strict_mode() {
    FinalSigmoidGate gate = new FinalSigmoidGate(0.80, "strict");

    double high = gate.score(0.1, 0.1, 0.1);
    double low  = gate.score(1.0, 1.0, 1.0);

    // high risk → score 낮음, low risk → score 높음인지 확인
    assertThat(high).isGreaterThan(0.80);
    assertThat(low).isLessThan(0.80);

    assertThat(gate.allow(high)).isTrue();
    assertThat(gate.allow(low)).isFalse();
  }
}
