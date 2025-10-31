package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;


// Removed Profile and Component imports since this adapter is no longer a Spring bean

/**
 * Thin adapter bean that exposes the core {@link ModelRouter} under the
 * conventional name "modelRouter".  This adapter delegates all calls
 * directly to the injected core implementation.  It allows for late
 * substitution of routing strategies without impacting call sites.  When
 * the {@code legacy-router} profile is enabled this bean can be replaced
 * with an alternative implementation.
 */
// Adapter bean is disabled to eliminate duplicate ModelRouter beans.  The
// {@link ModelRouterCore} is marked as @Primary and will be injected
// directly wherever a ModelRouter is required.
//
// Note: this adapter is intentionally **not** registered as a Spring bean.  By
// removing the @Component and @Profile annotations the class becomes a
// simple delegate that can be instantiated manually if needed.  This
// prevents the creation of a second ModelRouter bean in the default
// profile, ensuring that only the core router is active.
public class ModelRouterAdapter implements ModelRouter {

    private final ModelRouter delegate;

    public ModelRouterAdapter(@Qualifier("modelRouterCore") ModelRouter delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatModel route(RouteSignal sig) {
        return delegate.route(sig);
    }

    @Override
    public ChatModel route(String intent, String riskLevel, String verbosityHint, Integer targetMaxTokens) {
        return delegate.route(intent, riskLevel, verbosityHint, targetMaxTokens);
    }

    @Override
    public ChatModel escalate(RouteSignal sig) {
        return delegate.escalate(sig);
    }

    @Override
    public String resolveModelName(ChatModel model) {
        return delegate.resolveModelName(model);
    }
}