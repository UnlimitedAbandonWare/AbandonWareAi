package com.abandonware.ai.agent.integrations;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.HashMap;




/**
 * shim implementation of the Kakao reverse geocoding client.  For the
 * purposes of this exercise the client simply returns a shim
 * address based on the provided coordinates.  A real implementation
 * would call Kakao's address lookup endpoint.
 */
@Service
public class KakaoReverseGeocodingClient {
    private static final Logger log = LoggerFactory.getLogger(KakaoReverseGeocodingClient.class);

    public Map<String, Object> lookup(Double x, Double y) {
        log.info("[KakaoReverseGeocodingClient] lookup x={} y={} ", x, y);
        Map<String, Object> res = new HashMap<>();
        res.put("address", "Unknown address for coordinates (" + x + ", " + y + ")");
        return res;
    }
}