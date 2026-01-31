package com.example.lms.service.rag;

import com.example.lms.image.GroundedImagePromptBuilder;
import com.example.lms.location.LocationService;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.rag.chain.AttachmentContextHandler;
import com.example.lms.service.rag.chain.ImagePromptGroundingHandler;
import com.example.lms.service.rag.chain.LocationInterceptHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;



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
     * uploaded attachments into the prompt context so that subsequent
     * stages can inspect file metadata.  It currently operates as a
     * pass-through when no attachments are associated with the current
     * session.
     */
    @Bean
    public AttachmentContextHandler attachmentContextHandler() {
        return new AttachmentContextHandler(attachmentService);
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
public com.example.lms.service.rag.burst.ExtremeZSystemHandler extremeZSystemHandler(
        com.example.lms.service.rag.energy.ContradictionScorer contradictionScorer,
        com.example.lms.service.rag.SelfAskPlanner selfAskPlanner,
        com.example.lms.service.rag.AnalyzeWebSearchRetriever webRetriever,
        com.example.lms.service.rag.LangChainRAGService ragService,
        com.example.lms.service.rag.fusion.WeightedReciprocalRankFuser rrf,
        com.example.lms.service.rag.auth.AuthorityScorer authorityScorer,
        com.example.lms.service.rag.burst.ExtremeZProperties props
) {
    var trigger = new com.example.lms.service.rag.burst.ExtremeZTrigger(contradictionScorer, props);
    return new com.example.lms.service.rag.burst.ExtremeZSystemHandler(
            trigger, selfAskPlanner, webRetriever, ragService, rrf, authorityScorer, props);
}
}