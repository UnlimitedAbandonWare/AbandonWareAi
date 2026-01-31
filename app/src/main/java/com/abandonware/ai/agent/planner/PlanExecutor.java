
package com.abandonware.ai.agent.planner;
import com.abandonware.ai.agent.rag.model.Result;
import com.abandonware.ai.agent.rag.fusion.RrfFusion;
import java.util.*;

public class PlanExecutor {
    private final RrfFusion fusion = new RrfFusion();

    public List<Result> execute(List<PlanStep> steps) {
        // minimal demo: returns fused empty lists
        return fusion.fuse(Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }
}
