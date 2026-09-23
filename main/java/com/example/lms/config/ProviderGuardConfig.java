package com.example.lms.config;

import com.example.lms.llm.LocalLlmGatewaySecurity;
import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Provider guard for local OpenAI-compatible gateways.
 */
@Configuration
public class ProviderGuardConfig {
    private static final System.Logger LOG = System.getLogger(ProviderGuardConfig.class.getName());

    @Value("${llm.provider-guard.require-local:false}")
    private boolean requireLocal;

    @Value("${llm.provider:}")
    private String provider;

    @Value("${llm.base-url:}")
    private String baseUrl;

    @Value("${llm.api-key:${LLM_API_KEY:}}")
    private String apiKey;

    @Value("${llm.owner-token:${LLM_OWNER_TOKEN:}}")
    private String ownerToken;

    @Value("${llm.provider-guard.allow-private-remote:${LLM_PROVIDER_GUARD_ALLOW_PRIVATE_REMOTE:false}}")
    private boolean allowPrivateRemote;

    @Value("${llm.provider-guard.allowed-hosts:${LLM_PROVIDER_GUARD_ALLOWED_HOSTS:}}")
    private String allowedHosts;

    @Value("${llm.provider-guard.require-auth-for-remote:${LLM_PROVIDER_GUARD_REQUIRE_AUTH_FOR_REMOTE:true}}")
    private boolean requireAuthForRemote;

    @Value("${llm.provider-guard.fail-fast:${LLM_PROVIDER_GUARD_FAIL_FAST:true}}")
    private boolean failFast = true;

    private volatile boolean providerDisabled;
    private volatile String disabledReason = "";

    @PostConstruct
    public void validate() {
        providerDisabled = false;
        disabledReason = "";
        try {
            doValidate();
        } catch (IllegalStateException ex) {
            if (failFast) {
                throw ex;
            }
            markProviderDisabled(ex);
        }
    }

    private void doValidate() {
        String p = provider == null ? "" : provider.trim();

        if (requireLocal && !"local".equalsIgnoreCase(p)) {
            throw new IllegalStateException("ProviderGuard: llm.provider must be 'local' providerHash="
                    + SafeRedactor.hashValue(p)
                    + " providerLength=" + p.length());
        }

        LocalLlmGatewaySecurity.assertLocalProviderEndpointAllowed(
                p,
                baseUrl,
                allowPrivateRemote,
                allowedHosts,
                requireAuthForRemote,
                apiKey,
                ownerToken);
    }

    public boolean isProviderDisabled() {
        return providerDisabled;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    private void markProviderDisabled(IllegalStateException failure) {
        String reason = reasonCode(failure);
        String errorType = errorType(failure);
        providerDisabled = true;
        disabledReason = reason;
        TraceStore.put("llm.providerGuard.disabled", true);
        TraceStore.put("llm.providerGuard.failureClass", "provider-disabled");
        TraceStore.put("llm.providerGuard.disabledReason", reason);
        TraceStore.put("llm.providerGuard.errorType", errorType);
        if (LOG.isLoggable(System.Logger.Level.WARNING)) {
            LOG.log(System.Logger.Level.WARNING,
                    "[AWX][providerGuard] provider disabled reason={0} errorType={1}",
                    reason,
                    errorType);
        }
    }

    private static String reasonCode(Throwable failure) {
        String message = failure == null || failure.getMessage() == null
                ? ""
                : failure.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (message.contains("requires")) {
            return "remote_auth_missing";
        }
        if (message.contains("allowlisted")) {
            return "host_not_allowlisted";
        }
        if (message.contains("disabled")) {
            return "private_remote_disabled";
        }
        if (message.contains("provider must be")) {
            return "provider_not_local";
        }
        return "provider_guard_failed";
    }

    private static String errorType(Throwable failure) {
        if (failure == null) {
            return "unknown";
        }
        return SafeRedactor.traceLabelOrFallback(failure.getClass().getSimpleName(), "unknown");
    }
}
