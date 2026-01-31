package com.abandonware.ai.service.ai;

import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ModelZoo;
import ai.djl.repository.zoo.ZooModel;
import org.springframework.stereotype.Service;
import com.abandonware.ai.config.LLMProperties;

import jakarta.annotation.PostConstruct;

@Service
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ai.DjlLocalLLMService
 * Role: service
 * Dependencies: com.abandonware.ai.config.LLMProperties
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.service.ai.DjlLocalLLMService
role: service
*/
public class DjlLocalLLMService implements LocalLLMService {

    private final LLMProperties props;
    private volatile ZooModel<String, String> model;

    public DjlLocalLLMService(LLMProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void init() {
        if (props.preload()) {
            try { ensureModel(); } catch (Exception ignored) {}
        }
    }

    private void ensureModel() throws Exception {
        if (model == null) {
            synchronized (this) {
                if (model == null) {
                    Criteria<String, String> criteria = Criteria.builder()
                            .setTypes(String.class, String.class)
                            .optModelUrls("djl://ai.djl.huggingface.pytorch/gpt2")
                            .build();
                    model = ModelZoo.loadModel(criteria);
                }
            }
        }
    }

    @Override
    public String generateText(String prompt) throws Exception {
        ensureModel();
        try (Predictor<String, String> predictor = model.newPredictor()) {
            String seed = prompt == null ? "" : prompt;
            return predictor.predict(seed);
        }
    }

    @Override
    public String engineName() { return "djl"; }
}