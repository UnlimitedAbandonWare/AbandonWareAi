package com.abandonware.ai.agent.integrations;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.ScoreCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.ScoreCalibrator
role: config
*/
public interface ScoreCalibrator {
  double calibrate(String source, double raw);
}

@Component
class PlattCalibrator implements ScoreCalibrator {
  @Value("${fusion.calibrator.enabled:true}") boolean enabled;
  @Value("${fusion.calibrator.kind:platt}") String kind;
  @Value("${fusion.calibrator.platt.a:1.0}") double a;
  @Value("${fusion.calibrator.platt.b:0.0}") double b;
  @Value("${fusion.calibrator.minmax.min:0.0}") double min;
  @Value("${fusion.calibrator.minmax.max:1.0}") double max;

  @Override public double calibrate(String source, double s) {
    if (!enabled) return s;
    if ("minmax".equalsIgnoreCase(kind)) {
      return (s - min) / Math.max(1e-9, (max - min));
    }
    double z = a * s + b;
    return 1.0 / (1.0 + Math.exp(-z));
  }
}