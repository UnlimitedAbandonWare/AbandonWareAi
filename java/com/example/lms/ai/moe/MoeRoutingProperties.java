package com.example.lms.ai.moe;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;



/**
 * Configuration properties controlling mixture-of-experts (MOE) routing.  These
 * properties live under the {@code ai.moe} prefix in application.yml.  When
 * enabled the router may force all capabilities to use the highest available
 * tier or a specific model ID.  Additionally, per-capability allow lists and
 * tier ordering can be configured to control which candidate models are
 * considered and how they are ranked.
 */
@Data
@ConfigurationProperties(prefix = "ai.moe")
public class MoeRoutingProperties {

    /** Whether MOE routing is enabled.  When disabled the router returns the first candidate. */
    private boolean enabled = true;
    /** When true the router will always choose the highest tier candidate available. */
    private boolean forceHighest = false;
    /** Optional explicit model ID to use for all routes.  When non-blank this overrides all tier logic. */
    private String forceModelId = "";
    /** Per-capability allow lists.  Keys correspond to capabilities such as "chat", "rag", etc. */
    private Map<String, List<String>> allow = new HashMap<>();
    /** Per-capability tier ordering.  Higher ranked tiers appear earlier in the list. */
    private Map<String, List<String>> tierOrder = new HashMap<>();
}