package com.abandonware.ai.service.ai;

import com.abandonware.ai.config.LLMProperties;
import de.kherud.llama.InferenceParameters;
import de.kherud.llama.LlamaModel;
import de.kherud.llama.LlamaModel.LlamaOutput;
import de.kherud.llama.ModelParameters;
import de.kherud.llama.MiroStat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-process llama.cpp engine via java-llama.cpp.
 *
 * - Loads a single {@link LlamaModel} per JVM and reuses it.
 * - Supports threads, GPU layer offload, stop strings, mirostat, and deterministic mode.
 * - Streams are supported internally; we expose a simple complete() API for now.
 */
@Service
@ConditionalOnProperty(prefix = "llm", name = "engine", havingValue = "llamacpp")
public class LlamaCppLocalLLMService implements LocalLLMService {

    private final LLMProperties props;

    @Value("${llm.threads:0}")                       // 0 → auto
    private int threads;

    @Value("${llm.gpu-layers:0}")                    // requires CUDA/Metal build of jllama
    private int gpuLayers;

    @Value("${llm.stop-strings:User:,user:}")
    private String stopStrings;

    @Value("${llm.mirostat.enabled:false}")
    private boolean mirostatEnabled;

    @Value("${llm.mirostat.mode:2}")
    private int mirostatMode;

    @Value("${llm.max-tokens:0}")                    // 0 → use props.maxTokens
    private int maxTokensOverride;

    @Value("${llm.seed:-1}")                         // -1 → random
    private long seed;

    private volatile LlamaModel model;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public LlamaCppLocalLLMService(LLMProperties props) {
        this.props = props;
    }

    private Path resolveModelPath(String modelIdOrPath) {
        Path p = Path.of(modelIdOrPath);
        if (p.isAbsolute() || Files.exists(p)) return p;
        Path modelsDir = Path.of("./models");
        return modelsDir.resolve(modelIdOrPath);
    }

    private void ensureModel() throws Exception {
        if (model != null) return;
        synchronized (this) {
            if (model != null) return;

            String configured = props.modelId();
            Path mpPath = resolveModelPath(configured != null ? configured : "./models/model.gguf");
            File f = mpPath.toFile();
            if (!f.exists()) {
                throw new IllegalStateException("Model file not found: " + f.getAbsolutePath());
            }

            ModelParameters mp = new ModelParameters()
                    .setModel(f.getAbsolutePath());
            if (gpuLayers > 0) {
                mp.setGpuLayers(gpuLayers);
            }
            if (threads > 0) {
                mp.setThreads(threads);
            }

            this.model = new LlamaModel(mp);
            initialized.set(true);
        }
    }

    @Override
    public String generateText(String prompt) throws Exception {
        Objects.requireNonNull(prompt, "prompt");
        ensureModel();

        int maxTokens = maxTokensOverride > 0 ? maxTokensOverride : props.maxTokens();
        float temperature = (float) props.temperature();

        InferenceParameters ip = new InferenceParameters(prompt)
                .setTemperature(temperature)
                .setTokens(maxTokens);

        if (seed >= 0) ip.setSeed(seed);
        if (mirostatEnabled) {
            ip.setMiroStat(mirostatMode == 1 ? MiroStat.V1 : MiroStat.V2);
        }
        if (stopStrings != null && !stopStrings.isBlank()) {
            ip.setStopStrings(stopStrings);
        }
        if (threads > 0) {
            ip.setThreads(threads);
        }

        // Complete (non-streaming) for simplicity
        return model.complete(ip);
    }

    @Override
    public String engineName() { return "llama.cpp"; }

    @PreDestroy
    public void close() {
        LlamaModel m = this.model;
        if (m != null) {
            try { m.close(); } catch (Throwable ignore) {}
            this.model = null;
        }
    }
}
