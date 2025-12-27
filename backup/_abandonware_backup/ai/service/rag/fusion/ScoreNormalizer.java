package com.abandonware.ai.service.rag.fusion;

import com.abandonware.ai.service.rag.model.ContextSlice;
import com.abandonware.ai.ml.SoftmaxUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.fusion.ScoreNormalizer
 * Role: config
 * Dependencies: com.abandonware.ai.service.rag.model.ContextSlice, com.abandonware.ai.ml.SoftmaxUtils
 * Observability: propagates trace headers if present.
 * Thread-Safety: unknown.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.ScoreNormalizer
role: config
*/
public class ScoreNormalizer {

    @Value("${rag.quality.softmax.temperature:1.0}")
    private double temperature = 1.0;

    public List<ContextSlice> normalizeBySoftmax(List<ContextSlice> docs){
        double[] logits = new double[docs.size()];
        for (int i=0;i<docs.size();i++) logits[i] = docs.get(i).getScore();
        double[] prob = SoftmaxUtils.stableSoftmax(logits, temperature);
        for (int i=0;i<docs.size();i++) docs.get(i).setScore(prob[i]);
        return docs;
    }
}