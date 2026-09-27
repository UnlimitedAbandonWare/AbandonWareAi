package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;




/**
 * shim implementation of the Channel reverse geocoding client.  For the
 * purposes of this exercise the client simply returns a shim
 * address based on the provided coordinates.  A real implementation
 * would call Channel's address lookup endpoint.
 */
@Service("agentGenericReverseGeocodingClient")
public class GenericReverseGeocodingClient {
    private static final Logger log = LoggerFactory.getLogger(GenericReverseGeocodingClient.class);

    public Map<String, Object> lookup(Double x, Double y) {
        log.info("[GenericReverseGeocodingClient] accepted=false provider=outbox/noop coordinatesProvided={} coordinateHash={}",
                coordinatesProvided(x, y), coordinateHash(x, y));
        Map<String, Object> res = new HashMap<>();
        res.put("address", "Unknown address");
        res.put("provider", "outbox/noop");
        res.put("coordinatesProvided", coordinatesProvided(x, y));
        res.put("coordinateHash", coordinateHash(x, y));
        return res;
    }

    public static boolean coordinatesProvided(Double x, Double y) {
        return x != null && y != null;
    }

    public static String coordinateHash(Double x, Double y) {
        return coordinateHash(x, y, "SHA-256");
    }

    static String coordinateHash(Double x, Double y, String algorithm) {
        if (!coordinatesProvided(x, y)) {
            return "none";
        }
        try {
            String raw = x + "," + y;
            byte[] digest = MessageDigest.getInstance(algorithm)
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException ex) {
            traceSuppressed("digest", x, y, ex);
            return "sha256-unavailable";
        }
    }

    private static void traceSuppressed(String stage, Double x, Double y, Throwable error) {
        TraceStore.put("agent.reverseGeocode.coordinateHash.suppressed", true);
        TraceStore.put("agent.reverseGeocode.coordinateHash.suppressed.stage", stage);
        TraceStore.put("agent.reverseGeocode.coordinateHash.suppressed.errorType",
                error == null ? "unknown" : error.getClass().getSimpleName());
        TraceStore.put("agent.reverseGeocode.coordinateHash.suppressed.coordinatesProvided",
                coordinatesProvided(x, y));
    }
}
