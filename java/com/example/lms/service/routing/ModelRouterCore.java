package com.example.lms.service.routing;

import com.example.lms.config.ModelProperties;
import com.example.lms.config.MoeRoutingProps;
import com.example.lms.llm.DynamicChatModelFactory;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The default routing implementation responsible for selecting between the
 * default chat model and the high‑tier mixture‑of‑experts (MOE) model.  The
 * router examines a {@link RouteSignal} instance containing various
 * heuristics such as complexity, uncertainty, token budget and evidence scores
 * and compares them against configurable thresholds.  If any of the
 * thresholds are exceeded, or if certain intents or preferences are
 * encountered, the request is escalated to the MOE model.  Otherwise the
 * default model is selected.
 *
 * This bean is only active when the {@code legacy-router} profile is not
 * enabled.  See {@link ModelRouterAdapter} for injection into the
 * application context.
 */
@Component("modelRouterCore")
@Profile("!legacy-router")
public class ModelRouterCore implements ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouterCore.class);

    private final ModelProperties modelProps;
    private final MoeRoutingProps props;
    private final DynamicChatModelFactory factory;

    public ModelRouterCore(ModelProperties modelProps,
                           MoeRoutingProps props,
                           @Qualifier("dynamicChatModelFactory") DynamicChatModelFactory factory) {
        this.modelProps = modelProps;
        this.props = props;
        this.factory = factory;
    }

    /**
     * Perform routing based on the provided {@link RouteSignal}.  The signal
     * contains numerical heuristics such as complexity, uncertainty and a
     * fused evidence score (stored in the theta field).  If any of these
     * exceed their respective thresholds, or if the preferred model is
     * {@code QUALITY}, the high‑tier model configured via
     * {@link ModelProperties#moe()} is selected.  Otherwise the default model
     * is chosen.
     */
    @Override
    public ChatModel route(RouteSignal s) {
        if (s == null) {
            return createModel(modelProps.getaDefault());
        }
        boolean promote =
                (s.maxTokens() >= props.getTokensThreshold()) ||
                (s.complexity() >= props.getComplexityThreshold()) ||
                (s.uncertainty() >= props.getUncertaintyThreshold()) ||
                (s.theta() >= props.getWebEvidenceThreshold()) ||
                // When the preferred mode is QUALITY we always upgrade.
                (s.preferred() != null && "QUALITY".equalsIgnoreCase(s.preferred().name()));

        String modelName = promote ? modelProps.getMoe() : modelProps.getaDefault();
        if (log.isInfoEnabled()) {
            log.info("route: model={}, signal={}", modelName, s.toSignalMap());
        }
        return createModel(modelName);
    }

    /**
     * Overloaded routing method accepting discrete hints.  This method
     * considers risk and verbosity hints in addition to the token budget and
     * intent.  It maps these hints onto the internal {@link RouteSignal}
     * thresholds and defers to the main {@link #route(RouteSignal)} logic.
     */
    @Override
    public ChatModel route(String intent,
                           String riskLevel,
                           String verbosityHint,
                           Integer targetMaxTokens) {
        // Map the verbosity hint onto a notional signal.  Deep or ultra
        // verbosity implies higher complexity.  Risk level HIGH also forces
        // promotion.  A large token budget counts towards the token threshold.
        double complexity = 0.0;
        double uncertainty = 0.0;
        double evidence = 0.0;
        if (verbosityHint != null && !verbosityHint.isBlank()) {
            String vh = verbosityHint.trim().toLowerCase();
            if ("deep".equals(vh) || "ultra".equals(vh)) {
                complexity = props.getComplexityThreshold();
            }
        }
        if ("HIGH".equalsIgnoreCase(riskLevel)) {
            uncertainty = props.getUncertaintyThreshold();
        }
        int tokens = targetMaxTokens != null ? targetMaxTokens : 0;
        RouteSignal sig = new RouteSignal(
                complexity,
                0.0,
                uncertainty,
                evidence,
                parseIntent(intent),
                parseVerbosity(verbosityHint),
                tokens,
                parsePreference(null),
                null
        );
        return route(sig);
    }

    /**
     * Escalation simply reuses the primary routing logic to obtain a
     * high‑tier model.  The input signal may be modified by guards to
     * increase the uncertainty or evidence fields.
     */
    @Override
    public ChatModel escalate(RouteSignal sig) {
        return route(sig);
    }

    /**
     * Attempt to resolve the effective model name from a chat model instance.
     * When a dynamic factory is available, delegate to
     * {@link DynamicChatModelFactory#effectiveModelName(ChatModel)}.  If no
     * factory is present, fall back to the simple class name or return null.
     */
    @Override
    public String resolveModelName(ChatModel model) {
        if (model == null) {
            return null;
        }
        if (factory != null) {
            try {
                return factory.effectiveModelName(model);
            } catch (Throwable t) {
                // ignore
            }
        }
        return model.getClass().getSimpleName();
    }

    /**
     * Create a new chat model using the injected factory.  If the factory is
     * not available, a dynamic proxy is returned that implements the
     * {@link ChatModel} interface with no behaviour.  This allows unit tests
     * to verify routing decisions without requiring network calls.
     */
    private ChatModel createModel(String modelName) {
        if (factory != null) {
            try {
                // Use conservative defaults for temperature and topP.  Let the
                // caller specify a token budget when invoking the model.
                {
                    double requestedTemp = 0.7;
                    double eff = com.example.lms.llm.ModelCapabilities.sanitizeTemperature(modelName, requestedTemp);
                    if (eff != requestedTemp && this.props != null && this.props.isEscalateOnRigidTemp()) {
                        // rigid-temp 모델이 비기본값을 못 받을 때, 설정에 따라 MOE로 자동 승격
                        modelName = (this.modelProps != null && this.modelProps.getMoe() != null)
                                ? this.modelProps.getMoe()
                                : modelName;
                        eff = com.example.lms.llm.ModelCapabilities.sanitizeTemperature(modelName, requestedTemp);
                    }
                    return factory.lc(modelName, eff, 1.0, null);
                }
            } catch (Exception e) {
                // fall through to dummy proxy
            }
        }
        // Fallback: return a no‑op proxy for testing purposes
        return (ChatModel) java.lang.reflect.Proxy.newProxyInstance(
                ChatModel.class.getClassLoader(),
                new Class[]{ChatModel.class},
                (proxy, method, args) -> null
        );
    }

    private RouteSignal.Intent parseIntent(String i) {
        if (i == null) return null;
        try {
            return RouteSignal.Intent.valueOf(i.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private RouteSignal.Verbosity parseVerbosity(String v) {
        if (v == null) return null;
        String vv = v.trim().toUpperCase();
        try {
            return RouteSignal.Verbosity.valueOf(vv);
        } catch (Exception e) {
            return null;
        }
    }

    private RouteSignal.Preference parsePreference(String p) {
        if (p == null) return null;
        try {
            return RouteSignal.Preference.valueOf(p.trim().toUpperCase());
        } catch (Exception e) {
            return null;
        }
    }
}