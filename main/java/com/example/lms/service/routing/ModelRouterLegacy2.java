package com.example.lms.service.routing;

import com.example.lms.config.ModelProperties;
import com.example.lms.llm.DynamicChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;



/**
 * A minimal legacy router that always returns the default model.  This bean
 * exists solely to satisfy the routing interface when the {@code legacy-router}
 * profile is active.  It bypasses all signal heuristics and does not
 * implement any escalation logic.
 */
// Expose this legacy router under a distinct bean name.  Using a unique
// name prevents bean name clashes with the primary {@code ModelRouterCore}
// and allows callers to explicitly request the legacy implementation via
// {@code @Qualifier("modelRouterLegacy")}.
@Component("modelRouterLegacy")
@Profile("legacy-router")
public class ModelRouterLegacy2 implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterLegacy2.class);

    private final ModelProperties modelProps;
    private final DynamicChatModelFactory factory;

    @Value("${llm.chat.temperature:0.3}")
    private double defaultTemperature;

    public ModelRouterLegacy2(ModelProperties modelProps,
                              @Qualifier("dynamicChatModelFactory") DynamicChatModelFactory factory) {
        this.modelProps = modelProps;
        this.factory = factory;
    }

    @Override
    public ChatModel route(RouteSignal sig) {
        return createModel(modelProps.getaDefault());
    }

    @Override
    public ChatModel route(String intent, String riskLevel, String verbosityHint, Integer targetMaxTokens) {
        return createModel(modelProps.getaDefault());
    }

    @Override
    public ChatModel escalate(RouteSignal sig) {
        return createModel(modelProps.getMoe());
    }

    @Override
    public String resolveModelName(ChatModel model) {
        return modelProps.getaDefault();
    }

    private ChatModel createModel(String modelName) {
        try {
            return factory.lc(modelName, defaultTemperature, 1.0, null);
        } catch (Exception e) {
            logFailSoft("createModel", e);
            return (ChatModel) java.lang.reflect.Proxy.newProxyInstance(
                    ChatModel.class.getClassLoader(),
                    new Class[]{ChatModel.class},
                    (proxy, method, args) -> null
            );
        }
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isDebugEnabled()) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.debug("[AWX][llm][legacy-router] failSoft stage={} errorType={}", stage, errorType);
        }
    }
}
