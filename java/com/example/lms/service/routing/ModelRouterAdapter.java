package com.example.lms.service.routing;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Thin adapter bean that exposes the core {@link ModelRouter} under the
 * conventional name "modelRouter".  This adapter delegates all calls
 * directly to the injected core implementation.  It allows for late
 * substitution of routing strategies without impacting call sites.  When
 * the {@code legacy-router} profile is enabled this bean can be replaced
 * with an alternative implementation.
 */
@Component("modelRouter")
@Profile("!legacy-router")
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