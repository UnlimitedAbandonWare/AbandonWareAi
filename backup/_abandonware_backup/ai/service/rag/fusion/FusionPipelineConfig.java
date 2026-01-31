package com.abandonware.ai.service.rag.fusion;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.fusion.FusionPipelineConfig
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.FusionPipelineConfig
role: config
*/
public class FusionPipelineConfig {
  @Bean public WeightedRRF weightedRRF(){ return new WeightedRRF(); }
  @Bean public IsotonicRegressionCalibrator isotonic(){ return new IsotonicRegressionCalibrator(); }
  @Bean public ScoreCalibrator scoreCal(){ return new MinMaxCalibrator(); }
  @Bean public BodeClamp bode(){ return new BodeClamp(); }
  @Bean public MpSpectrumNormalizer mp(){ return new MpSpectrumNormalizer(); }
}