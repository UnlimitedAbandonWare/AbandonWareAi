package com.abandonware.ai.addons.calibration;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.addons.calibration.ScoreCalibrator
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.addons.calibration.ScoreCalibrator
role: config
*/
public interface ScoreCalibrator {
    double calibrate(String source, double raw, CalibContext ctx);

    final class CalibContext {
        public final double coverage;
        public CalibContext(double coverage) { this.coverage = coverage; }
    }
}