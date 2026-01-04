package com.abandonware.ai.agent.service.rag.calib;

import java.util.List;
import com.abandonware.ai.service.rag.model.ContextSlice;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.service.rag.calib.ScoreCalibrator
 * Role: config
 * Dependencies: com.abandonware.ai.service.rag.model.ContextSlice, com.abandonware.ai.agent.service.plan.RetrievalPlan
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.service.rag.calib.ScoreCalibrator
role: config
*/
public interface ScoreCalibrator {
    List<ContextSlice> apply(List<ContextSlice> in, Object ctx, RetrievalPlan plan);
}