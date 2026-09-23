package com.abandonware.ai.agent.service.rag.calib;

import java.util.List;
import com.abandonware.ai.service.rag.model.ContextSlice;
import com.abandonware.ai.agent.service.plan.RetrievalPlan;

public interface ScoreCalibrator {
    List<ContextSlice> apply(List<ContextSlice> in, Object ctx, RetrievalPlan plan);
}