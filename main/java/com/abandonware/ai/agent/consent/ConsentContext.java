package com.abandonware.ai.agent.consent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;



/**
 * Additional contextual information passed to the consent service when
 * verifying scopes.  This can include the roomId, channel metadata or any
 * other properties that may be needed to generate a consent card.  For
 * this minimal implementation the context is unused but is provided for
 * forward compatibility with richer consent strategies.
 */
public final class ConsentContext {
    private final Map<String, Object> attributes;

    public ConsentContext(Map<String, Object> attributes) {
        if (attributes == null) {
            this.attributes = Collections.emptyMap();
        } else {
            this.attributes = Collections.unmodifiableMap(new HashMap<>(attributes));
        }
    }

    public Map<String, Object> attributes() {
        return attributes;
    }
}
