
package com.abandonware.ai.agent.rag.fusion;
import com.abandonware.ai.agent.rag.model.Result;
import java.util.*;

public class RrfFusion {
    private final WeightedRRF weightedRRF = new WeightedRRF();
    public void setWeights(List<Double> w) { weightedRRF.setWeights(w); }

    public List<Result> fuse(List<Result> web, List<Result> vector, List<Result> kg) {
        return weightedRRF.merge(web, vector, kg);
    }
}
