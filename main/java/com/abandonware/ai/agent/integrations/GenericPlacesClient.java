package com.abandonware.ai.agent.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shim implementation of the Channel Places client. This service is used by
 * the {@code places.search} tool to look up nearby points of interest. In
 * this shim the query always returns an empty list; implementors should
 * replace this with a real HTTP client that calls Channel's Local API.
 */
@Service("agentGenericPlacesClient")
public class GenericPlacesClient {

    private static final Logger log = LoggerFactory.getLogger(GenericPlacesClient.class);

    public List<Map<String, Object>> search(String query, Double x, Double y, Integer radius) {
        log.info("[GenericPlacesClient] accepted=false provider=outbox/noop radius={}", radius);
        return Collections.emptyList();
    }
}
