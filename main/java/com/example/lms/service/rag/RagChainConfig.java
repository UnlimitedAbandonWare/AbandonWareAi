package com.example.lms.service.rag;

import ai.abandonware.nova.boot.exec.CancelShieldExecutorService;
import com.example.lms.image.GroundedImagePromptBuilder;
import com.example.lms.location.LocationService;
import com.example.lms.search.TraceStore;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.TrainingService;
import com.example.lms.service.rag.auth.AuthorityScorer;
import com.example.lms.service.rag.burst.ExtremeZProperties;
import com.example.lms.service.rag.burst.ExtremeZSystemHandler;
import com.example.lms.service.rag.burst.ExtremeZTrigger;
import com.example.lms.service.rag.chain.AttachmentContextHandler;
import com.example.lms.service.rag.chain.ImagePromptGroundingHandler;
import com.example.lms.service.rag.chain.LocationInterceptHandler;
import com.example.lms.service.rag.energy.ContradictionScorer;
import com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser;
import com.example.lms.service.rag.rerank.CrossEncoderReranker;
import com.example.lms.service.rag.rerank.DppDiversityReranker;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;



/**
 * Configuration class for registering RAG chain components.  These beans
 * instantiate optional ChainLink handlers that participate in the custom
 * retrieval chain.  The handlers are wired independently of LangChain4j
 * components to avoid introducing version conflicts.  When the requested
 * dependencies (e.g. LocationService) are not available the bean
 * instantiation will fail at startup, so ensure that the corresponding
 * modules are on the classpath.
 */
@Configuration
@org.springframework.boot.context.properties.EnableConfigurationProperties(com.example.lms.service.rag.burst.ExtremeZProperties.class)
@RequiredArgsConstructor
public class RagChainConfig {

    private final LocationService locationService;
    private final AttachmentService attachmentService;
    private final GroundedImagePromptBuilder groundedImagePromptBuilder;

    /**
     * Register the location intercept handler.  This handler short-circuits
     * queries that look like address or location questions and emits an
     * immediate response using the {@link LocationService}.  When no
     * location can be resolved it delegates to the next chain element.
     */
    @Bean
    public LocationInterceptHandler locationInterceptHandler() {
        return new LocationInterceptHandler(locationService);
    }

    /**
     * Register the attachment context handler.  This handler integrates
     * uploaded attachments into the prompt context and runs the optional
     * CIH-RAG refinement stages when attachment documents are available.
     */
    @Bean
    public AttachmentContextHandler attachmentContextHandler(
            @Qualifier("crossEncoderReranker") ObjectProvider<CrossEncoderReranker> biEncoderProvider,
            @Qualifier("onnxCrossEncoderReranker") ObjectProvider<CrossEncoderReranker> onnxRerankerProvider,
            ObjectProvider<DppDiversityReranker> dppRerankerProvider) {
        CrossEncoderReranker biEncoder = biEncoderProvider == null ? null : biEncoderProvider.getIfAvailable();
        CrossEncoderReranker onnx = onnxRerankerProvider == null ? null : onnxRerankerProvider.getIfAvailable();
        DppDiversityReranker dpp = dppRerankerProvider == null ? null : dppRerankerProvider.getIfAvailable();
        return new AttachmentContextHandler(attachmentService, biEncoder, onnx, dpp);
    }

    /**
     * Register the image prompt grounding handler.  This handler detects
     * simple image generation intents and annotates the prompt context
     * with grounded prompts and model parameters.
     */
    @Bean
    public ImagePromptGroundingHandler imagePromptGroundingHandler() {
        return new ImagePromptGroundingHandler(groundedImagePromptBuilder);
    }

    @Bean
    public ExtremeZSystemHandler extremeZSystemHandler(
            ContradictionScorer contradictionScorer,
            SelfAskPlanner selfAskPlanner,
            AnalyzeWebSearchRetriever webRetriever,
            LangChainRAGService ragService,
            WeightedReciprocalRankFuser rrf,
            AuthorityScorer authorityScorer,
            ExtremeZProperties props,
            ObjectProvider<TrainingService> trainingServiceProvider,
            @Qualifier("searchIoExecutor") ObjectProvider<ExecutorService> searchIoExecutorProvider) {
        var trigger = new ExtremeZTrigger(contradictionScorer, props);
        ExecutorService rawExecutor = searchIoExecutorProvider == null ? null : searchIoExecutorProvider.getIfAvailable();
        ExecutorService executor = ensureCancelShield(rawExecutor);
        TrainingService trainingService = trainingServiceProvider == null ? null : trainingServiceProvider.getIfAvailable();
        TraceStore.put("extremeZ.executor.source", rawExecutor == null ? "sequential" : "searchIoExecutor");
        boolean cancelShieldWrapped = executor instanceof CancelShieldExecutorService;
        TraceStore.put("extremez.executor.cancelShieldWrapped", cancelShieldWrapped);
        TraceStore.put("extremeZ.cancelShieldWrapped", cancelShieldWrapped);
        TraceStore.put("extremeZ.cancelShield.skipReason", rawExecutor == null ? "no_executor_sequential" : "");
        return new ExtremeZSystemHandler(
                trigger, selfAskPlanner, webRetriever, ragService, rrf, authorityScorer, props, executor, trainingService);
    }

    private static ExecutorService ensureCancelShield(ExecutorService rawExecutor) {
        if (rawExecutor == null || rawExecutor instanceof CancelShieldExecutorService) {
            return rawExecutor;
        }
        return new CancelShieldExecutorService(rawExecutor, "extremeZSystemHandler");
    }
}
