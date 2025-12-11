package com.example.lms.integrations.n8n;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;



/**
 * Configuration properties for the n8n integration.  Properties are
 * loaded from the {@code integrations.n8n} namespace in application.yml or
 * equivalent configuration sources.  When disabled the associated
 * controllers and notifiers will short-circuit without performing any
 * network requests.
 */
@Component
@ConfigurationProperties(prefix = "integrations.n8n")
@Data
public class N8nProps {
    /** Enable or disable n8n integration globally. */
    private boolean enabled = false;
    /** URL for sending notification payloads back to n8n. */
    private String notifyUrl;
    /** Shared secret used to verify incoming webhook signatures. */
    private String webhookSecret;
}