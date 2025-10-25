package com.example.lms.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;



/**
 * Thin adapter exposing the core {@link com.example.lms.service.routing.ModelRouter}
 * under the legacy package {@code com.example.lms.infrastructure.llm}.  This adapter
 * exists solely to maintain backwards compatibility with existing autowiring and
 * test expectations while delegating all routing logic to the core router.
 */
@Component("modelRouter")
@Profile("legacy-router")
public class ModelRouter {

    private final com.example.lms.service.routing.ModelRouter core;

    public ModelRouter(com.example.lms.service.routing.ModelRouter core) {
        this.core = core;
    }

    /**
     * Delegate the signal-based routing to the core router.
     *
     * @param sig the collected routing signals
     * @return the selected {@link ChatModel}
     */
    public ChatModel route(com.example.lms.service.routing.RouteSignal sig) {
        return core.route(sig);
    }

    /**
     * Delegate the overloaded string-based routing to the core router.
     *
     * @param intent the intent hint
     * @param riskLevel the risk hint
     * @param verbosityHint the verbosity hint
     * @param targetMaxTokens the token budget hint
     * @return the selected {@link ChatModel}
     */
    public ChatModel route(String intent,
                           String riskLevel,
                           String verbosityHint,
                           Integer targetMaxTokens) {
        return core.route(intent, riskLevel, verbosityHint, targetMaxTokens);
    }

    /**
     * Expose the underlying model name for observability.
     *
     * @param model a chat model instance
     * @return the effective model name
     */
    public String resolveModelName(ChatModel model) {
        return core.resolveModelName(model);
    }
}