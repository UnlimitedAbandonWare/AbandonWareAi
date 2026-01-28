package com.abandonware.ai.agent.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Shim implementation of the Kakao Places client. This service is used by
 * the {@code places.search} tool to look up nearby points of interest. In
 * this shim the query always returns an empty list; implementors should
 * replace this with a real HTTP client that calls Kakao's Local API.
 */
@Service
public class KakaoPlacesClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoPlacesClient.class);

    /** Guard: enable external call only when API key is configured. */
    static boolean apiEnabled() {
        String key = System.getenv("KAKAO_REST_KEY");
        return key != null && !key.isEmpty();
    }

    public List<Map<String, Object>> search(String query, Double x, Double y, Integer radius) {
        log.info("[KakaoPlacesClient] search query={} x={} y={} radius={}", query, x, y, radius);
        // Return an empty result set for this shim.
        return Collections.emptyList();
    }
}