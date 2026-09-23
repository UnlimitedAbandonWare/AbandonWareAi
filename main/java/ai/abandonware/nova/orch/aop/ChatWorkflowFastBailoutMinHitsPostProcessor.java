package ai.abandonware.nova.orch.aop;

import com.example.lms.search.TraceStore;
import com.example.lms.service.ChatWorkflow;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compatibility bean kept for existing auto-configuration.
 *
 * <p>The fast-bail policy is now bound directly inside {@link ChatWorkflow};
 * this post-processor no longer mutates private fields.</p>
 */
public class ChatWorkflowFastBailoutMinHitsPostProcessor implements BeanPostProcessor {
    private static final Logger log = LoggerFactory.getLogger(ChatWorkflowFastBailoutMinHitsPostProcessor.class);

    private final Environment env;

    public ChatWorkflowFastBailoutMinHitsPostProcessor(Environment env) {
        this.env = env;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof ChatWorkflow) {
            emitTrace();
        }
        return bean;
    }

    private void emitTrace() {
        try {
            int minHits = env.getProperty(
                    "openai.retry.fast-bailout-min-timeout-hits-with-evidence",
                    Integer.class,
                    1);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("enabled", true);
            data.put("minTimeoutHitsWithEvidence", Math.max(1, minHits));
            data.put("mutationCount", 0);
            data.put("fieldMissingCount", 0);
            data.put("reason", "direct_chat_workflow_binding");
            TraceStore.put("chatWorkflow.fastBailPostProcessor", data);
        } catch (Throwable traceError) {
            traceSuppressed("emitTrace", traceError);
        }
    }

    private static void traceSuppressed(String stage, Throwable error) {
        String safeStage = SafeRedactor.traceLabelOrFallback(stage, "unknown");
        String errorType = error == null ? "unknown" : SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
        log.debug("[ChatWorkflowFastBailoutMinHitsPostProcessor] trace skipped stage={} errorType={}",
                safeStage,
                errorType);
        try {
            TraceStore.put("chatWorkflow.fastBailPostProcessor.suppressed", true);
            TraceStore.put("chatWorkflow.fastBailPostProcessor.suppressed.stage", safeStage);
            TraceStore.put("chatWorkflow.fastBailPostProcessor.suppressed.errorType", errorType);
        } catch (RuntimeException traceStoreError) {
            log.debug("[ChatWorkflowFastBailoutMinHitsPostProcessor] suppressed breadcrumb skipped stage={} errorType={}",
                    safeStage,
                    traceStoreError == null ? "unknown" : traceStoreError.getClass().getSimpleName());
        }
    }
}
