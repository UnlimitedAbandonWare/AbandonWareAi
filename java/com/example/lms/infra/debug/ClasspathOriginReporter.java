package com.example.lms.infra.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Runtime classpath tracer for duplicate FQCN situations.
 *
 * <p>
 * Enable with: {@code debug.classpath.origins=true}
 * </p>
 *
 * <p>
 * This prints the actual resource URL for a few key classes so you can verify
 * which jar/source set "wins" at runtime when duplicates exist.
 * </p>
 */
@Component
@ConditionalOnProperty(name = "debug.classpath.origins", havingValue = "true")
public class ClasspathOriginReporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ClasspathOriginReporter.class);

    @Override
    public void run(ApplicationArguments args) {
        logOrigin("com.example.lms.LmsApplication", com.example.lms.LmsApplication.class);
        logOrigin("com.example.lms.config.RerankerConfig", com.example.lms.config.RerankerConfig.class);
        logOrigin("com.example.lms.service.onnx.OnnxRuntimeService", com.example.lms.service.onnx.OnnxRuntimeService.class);
        logOrigin("com.example.lms.service.onnx.OnnxCrossEncoderReranker", com.example.lms.service.onnx.OnnxCrossEncoderReranker.class);

        // Duplicate-FQCN hot spots (:lms-core vs :app(java_clean)).
        // If these show duplicates, whichever "wins" can remove @Component/@Service annotations
        // (bean disappears) or even change ABI (NoSuchMethodError/IncompatibleClassChangeError).
        logOrigin("com.example.lms.service.rag.AnalyzeWebSearchRetriever", com.example.lms.service.rag.AnalyzeWebSearchRetriever.class);
        logOrigin("com.example.lms.service.rag.auth.DomainWhitelist", com.example.lms.service.rag.auth.DomainWhitelist.class);
        logOrigin("com.example.lms.strategy.RetrievalOrderService", com.example.lms.strategy.RetrievalOrderService.class);
        logOrigin("com.example.lms.service.rag.fusion.RerankCanonicalizer", com.example.lms.service.rag.fusion.RerankCanonicalizer.class);
        logOrigin("com.example.lms.service.rag.fusion.WeightedPowerMeanFuser", com.example.lms.service.rag.fusion.WeightedPowerMeanFuser.class);
        logOrigin("com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain", com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain.class);
        logOrigin("com.example.lms.service.rag.handler.KnowledgeGraphHandler", com.example.lms.service.rag.handler.KnowledgeGraphHandler.class);
        logOrigin("com.example.lms.service.rag.overdrive.AngerOverdriveNarrower", com.example.lms.service.rag.overdrive.AngerOverdriveNarrower.class);
        logOrigin("com.example.lms.service.rag.overdrive.OverdriveGuard", com.example.lms.service.rag.overdrive.OverdriveGuard.class);
        logOrigin("com.example.lms.service.rag.rerank.DppDiversityReranker", com.example.lms.service.rag.rerank.DppDiversityReranker.class);
        logOrigin("service.rag.planner.SelfAskPlanner", service.rag.planner.SelfAskPlanner.class);
        logOrigin("trace.TimeBudget", trace.TimeBudget.class);
        logOrigin("com.example.lms.guard.AnswerSanitizer", com.example.lms.guard.AnswerSanitizer.class);
        logOrigin("com.example.lms.trace.TraceContext", com.example.lms.trace.TraceContext.class);

        // If a bean disappears, verify whether the loaded class still carries a Spring stereotype.
        logStereotype("com.example.lms.service.rag.AnalyzeWebSearchRetriever", com.example.lms.service.rag.AnalyzeWebSearchRetriever.class);
        logStereotype("com.example.lms.strategy.RetrievalOrderService", com.example.lms.strategy.RetrievalOrderService.class);
        logStereotype("com.example.lms.service.rag.fusion.WeightedPowerMeanFuser", com.example.lms.service.rag.fusion.WeightedPowerMeanFuser.class);
        logStereotype("com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain", com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain.class);
        logStereotype("com.example.lms.service.rag.handler.KnowledgeGraphHandler", com.example.lms.service.rag.handler.KnowledgeGraphHandler.class);
        logStereotype("com.example.lms.service.rag.overdrive.AngerOverdriveNarrower", com.example.lms.service.rag.overdrive.AngerOverdriveNarrower.class);
        logStereotype("com.example.lms.service.rag.overdrive.OverdriveGuard", com.example.lms.service.rag.overdrive.OverdriveGuard.class);

        logOrigin("com.example.lms.service.rag.retriever.OcrRetriever", com.example.lms.service.rag.retriever.OcrRetriever.class);
        logOrigin("com.example.lms.service.llm.RerankerSelector", com.example.lms.service.llm.RerankerSelector.class);

        // Core orchestration + resilience (common duplicate-FQCN regression hotspots)
        logOrigin("com.example.lms.orchestration.OrchestrationSignals", com.example.lms.orchestration.OrchestrationSignals.class);
        logOrigin("com.example.lms.service.ChatWorkflow", com.example.lms.service.ChatWorkflow.class);
        logOrigin("com.example.lms.infra.resilience.NightmareBreaker", com.example.lms.infra.resilience.NightmareBreaker.class);
        logOrigin("com.example.lms.infra.resilience.AuxBlockTracker", com.example.lms.infra.resilience.AuxBlockTracker.class);
        logOrigin("ai.abandonware.nova.orch.aop.WebFailSoftSearchAspect", ai.abandonware.nova.orch.aop.WebFailSoftSearchAspect.class);
        logOrigin("ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator", ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator.class);

        // Duplicate resource scan (if >1 -> you *will* get heisenbugs).
        logDuplicates("com.example.lms.orchestration.OrchestrationSignals", com.example.lms.orchestration.OrchestrationSignals.class);
        logDuplicates("com.example.lms.service.ChatWorkflow", com.example.lms.service.ChatWorkflow.class);
        logDuplicates("com.example.lms.infra.resilience.NightmareBreaker", com.example.lms.infra.resilience.NightmareBreaker.class);
        logDuplicates("com.example.lms.infra.resilience.AuxBlockTracker", com.example.lms.infra.resilience.AuxBlockTracker.class);
        logDuplicates("ai.abandonware.nova.orch.aop.WebFailSoftSearchAspect", ai.abandonware.nova.orch.aop.WebFailSoftSearchAspect.class);
        logDuplicates("ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator", ai.abandonware.nova.orch.failpattern.FailurePatternOrchestrator.class);

        logDuplicates("com.example.lms.service.onnx.OnnxCrossEncoderReranker", com.example.lms.service.onnx.OnnxCrossEncoderReranker.class);
        logDuplicates("com.example.lms.service.rag.AnalyzeWebSearchRetriever", com.example.lms.service.rag.AnalyzeWebSearchRetriever.class);
        logDuplicates("com.example.lms.service.rag.auth.DomainWhitelist", com.example.lms.service.rag.auth.DomainWhitelist.class);
        logDuplicates("com.example.lms.strategy.RetrievalOrderService", com.example.lms.strategy.RetrievalOrderService.class);
        logDuplicates("com.example.lms.service.rag.fusion.RerankCanonicalizer", com.example.lms.service.rag.fusion.RerankCanonicalizer.class);
        logDuplicates("com.example.lms.service.rag.fusion.WeightedPowerMeanFuser", com.example.lms.service.rag.fusion.WeightedPowerMeanFuser.class);
        logDuplicates("com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain", com.example.lms.service.rag.handler.DynamicRetrievalHandlerChain.class);
        logDuplicates("com.example.lms.service.rag.handler.KnowledgeGraphHandler", com.example.lms.service.rag.handler.KnowledgeGraphHandler.class);
        logDuplicates("com.example.lms.service.rag.overdrive.AngerOverdriveNarrower", com.example.lms.service.rag.overdrive.AngerOverdriveNarrower.class);
        logDuplicates("com.example.lms.service.rag.overdrive.OverdriveGuard", com.example.lms.service.rag.overdrive.OverdriveGuard.class);
        logDuplicates("com.example.lms.service.rag.rerank.DppDiversityReranker", com.example.lms.service.rag.rerank.DppDiversityReranker.class);
        logDuplicates("service.rag.planner.SelfAskPlanner", service.rag.planner.SelfAskPlanner.class);
        logDuplicates("trace.TimeBudget", trace.TimeBudget.class);
        logDuplicates("com.example.lms.guard.AnswerSanitizer", com.example.lms.guard.AnswerSanitizer.class);
        logDuplicates("com.example.lms.trace.TraceContext", com.example.lms.trace.TraceContext.class);
    }

    private void logDuplicates(String label, Class<?> clazz) {
        try {
            String res = clazz.getName().replace('.', '/') + ".class";
            ClassLoader cl = clazz.getClassLoader();
            Enumeration<URL> urls = (cl != null) ? cl.getResources(res) : ClassLoader.getSystemResources(res);
            List<URL> all = Collections.list(urls);
            if (all.size() > 1) {
                log.warn("[ClasspathOrigin] DUPLICATE {} -> {}", label, all);
            } else {
                log.info("[ClasspathOrigin] unique {} -> {}", label, (all.isEmpty() ? "<none>" : all.get(0)));
            }
        } catch (Exception e) {
            log.info("[ClasspathOrigin] {} -> <dup-scan-error: {}>", label, e.toString());
        }
    }

    private void logOrigin(String label, Class<?> clazz) {
        try {
            URL url = clazz.getResource(clazz.getSimpleName() + ".class");
            log.info("[ClasspathOrigin] {} -> {}", label, (url != null ? url : "<null>"));
        } catch (Exception e) {
            log.info("[ClasspathOrigin] {} -> <error: {}>", label, e.toString());
        }
    }

    private void logStereotype(String label, Class<?> clazz) {
        try {
            boolean componentLike = AnnotatedElementUtils.hasAnnotation(clazz, Component.class);
            boolean iface = clazz.isInterface();
            boolean abs = java.lang.reflect.Modifier.isAbstract(clazz.getModifiers());
            log.info("[ClasspathOrigin] stereotype {} -> @Component(meta)={} interface={} abstract={}",
                    label, componentLike, iface, abs);
        } catch (Exception e) {
            log.info("[ClasspathOrigin] stereotype {} -> <error: {}>", label, e.toString());
        }
    }
}
