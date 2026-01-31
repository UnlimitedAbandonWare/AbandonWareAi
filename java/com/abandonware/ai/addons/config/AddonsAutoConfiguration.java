package com.abandonware.ai.addons.config;

import com.abandonware.ai.addons.budget.TimeBudgetFilter;
import com.abandonware.ai.addons.complexity.ComplexityGatingCoordinator;
import com.abandonware.ai.addons.complexity.QueryComplexityClassifier;
import com.abandonware.ai.addons.ocr.OcrRetriever;
import com.abandonware.ai.addons.onnx.OnnxSemaphoreGate;
import com.abandonware.ai.addons.cache.SingleFlightRegistry;
import com.abandonware.ai.addons.flow.FlowJoiner;
import com.abandonware.ai.addons.synthesis.MatrixTransformer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



@Configuration
@EnableConfigurationProperties(AddonsProperties.class)
public class AddonsAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public QueryComplexityClassifier queryComplexityClassifier() {
        return new QueryComplexityClassifier();
    }

    @Bean @ConditionalOnMissingBean
    public ComplexityGatingCoordinator complexityGatingCoordinator(QueryComplexityClassifier c, AddonsProperties p) {
        return new ComplexityGatingCoordinator(c, p);
    }

    @Bean @ConditionalOnMissingBean
    public TimeBudgetFilter timeBudgetFilter(AddonsProperties p) { return new TimeBudgetFilter(p); }

    @Bean @ConditionalOnMissingBean
    public OnnxSemaphoreGate onnxSemaphoreGate(AddonsProperties p) { return new OnnxSemaphoreGate(p); }

    @Bean @ConditionalOnMissingBean
    public SingleFlightRegistry singleFlightRegistry() { return new SingleFlightRegistry(); }

    @Bean @ConditionalOnMissingBean
    public MatrixTransformer matrixTransformer(AddonsProperties p) { return new MatrixTransformer(p); }

    @Bean @ConditionalOnMissingBean
    public FlowJoiner flowJoiner() { return new FlowJoiner(); }

    // OCR Retriever는 외부 Port 구현체가 있을 때 주입
    @Bean @ConditionalOnMissingBean
    public OcrRetriever ocrRetriever(AddonsProperties p) {
        return new OcrRetriever(p, (query, topK) -> java.util.List.of());
    }
}