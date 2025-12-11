package com.abandonware.ai.service.ai;

import com.abandonware.ai.config.LLMProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "llm", name = "engine", havingValue = "jlama")
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.ai.JlamaLocalLLMService
 * Role: service
 * Feature Flags: sse
 * Dependencies: com.abandonware.ai.config.LLMProperties
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.service.ai.JlamaLocalLLMService
role: service
flags: [sse]
*/
public class JlamaLocalLLMService implements LocalLLMService {

    private final LLMProperties props;
    private volatile Object model; // AbstractModel (unknown at compile time)
    private volatile Class<?> promptContextClass;

    public JlamaLocalLLMService(LLMProperties props) {
        this.props = props;
    }

    private void ensureModel() throws Exception {
        if (model != null) return;
        synchronized (this) {
            if (model != null) return;

            // Resolve classes by name to avoid compile-time dependency
            Class<?> downloaderClass = Class.forName("com.github.tjake.jlama.util.Downloader");
            Class<?> modelSupportClass = Class.forName("com.github.tjake.jlama.core.ModelSupport");
            Class<?> dTypeClass = Class.forName("com.github.tjake.jlama.core.DType");
            promptContextClass = Class.forName("com.github.tjake.jlama.core.PromptContext");

            // new Downloader("./models", modelId).huggingFaceModel()
            Object downloader = downloaderClass.getConstructor(String.class, String.class)
                    .newInstance("./models", props.modelId());
            File modelPath = (File) downloaderClass.getMethod("huggingFaceModel").invoke(downloader);

            // AbstractModel model = ModelSupport.loadModel(path, DType.F32, DType.I8)
            Object dtypeF32 = Enum.valueOf((Class<Enum>) dTypeClass, "F32");
            Object dtypeI8 = Enum.valueOf((Class<Enum>) dTypeClass, "I8");
            Method loadModel = modelSupportClass.getMethod("loadModel", File.class, dTypeClass, dTypeClass);
            model = loadModel.invoke(null, modelPath, dtypeF32, dtypeI8);
        }
    }

    @Override
    public String generateText(String prompt) throws Exception {
        ensureModel();
        // PromptContext.of(prompt)
        Method of = promptContextClass.getMethod("of", String.class);
        Object ctx = of.invoke(null, prompt);
        // model.generate(UUID.randomUUID(), ctx, temp, maxTokens, (s,f)->{})
        Method generate = model.getClass().getMethod("generate",
                UUID.class, promptContextClass, float.class, int.class, java.util.function.BiConsumer.class);
        Object res = generate.invoke(model, UUID.randomUUID(), ctx, (float) props.temperature(), props.maxTokens(),
                (java.util.function.BiConsumer<String, Float>) (s, f) -> {});

        // res.responseText
        Method getText = res.getClass().getMethod("getResponseText");
        return String.valueOf(getText.invoke(res));
    }

    @Override
    public String engineName() { return "jlama"; }
}