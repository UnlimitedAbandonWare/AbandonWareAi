package com.abandonware.ai.agent.integrations;

import com.example.lms.search.TraceStore;
import com.example.lms.trace.SafeRedactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;




/**
 * Simplified notifier that emulates posting JSON payloads to an n8n
 * webhook.  In this implementation the payload is merely logged.  A
 * production version would use WebClient or RestTemplate to perform an
 * outbound HTTP POST to the specified URL.
 */
@Service("agentN8nNotifier")
public class N8nNotifier {
    private static final Logger log = LoggerFactory.getLogger(N8nNotifier.class);

    public void notify(String webhookUrl, Map<String, Object> payload) {
        log.info("[N8nNotifier] POST webhookConfigured={} webhookHost={} webhookHash={} payloadSize={}",
                webhookConfigured(webhookUrl),
                webhookHost(webhookUrl),
                webhookHash(webhookUrl),
                payloadSize(payload));
        // This shim does not perform any real HTTP call.
    }

    public static boolean webhookConfigured(String webhookUrl) {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    public static String webhookHost(String webhookUrl) {
        if (!webhookConfigured(webhookUrl)) {
            return "none";
        }
        try {
            String host = URI.create(webhookUrl).getHost();
            return host == null || host.isBlank() ? "unknown" : host;
        } catch (IllegalArgumentException ex) {
            traceSuppressed("webhookHost.uri", webhookUrl, ex);
            return "invalid-url";
        }
    }

    public static String webhookHash(String webhookUrl) {
        return webhookHash(webhookUrl, "SHA-256");
    }

    static String webhookHash(String webhookUrl, String algorithm) {
        if (!webhookConfigured(webhookUrl)) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance(algorithm)
                    .digest(webhookUrl.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException ex) {
            traceSuppressed("webhookHash.digest", webhookUrl, ex);
            return "sha256-unavailable";
        }
    }

    public static int payloadSize(Map<String, Object> payload) {
        return payload == null ? 0 : payload.size();
    }

    private static void traceSuppressed(String stage, String webhookUrl, Throwable error) {
        TraceStore.put("agent.n8n.webhook.suppressed", true);
        TraceStore.put("agent.n8n.webhook.suppressed.stage",
                SafeRedactor.traceLabelOrFallback(stage, "unknown"));
        TraceStore.put("agent.n8n.webhook.suppressed.errorType", errorType(stage, error));
        TraceStore.put("agent.n8n.webhook.suppressed.webhookHash", SafeRedactor.hashValue(webhookUrl));
        TraceStore.put("agent.n8n.webhook.suppressed.webhookLength",
                webhookUrl == null ? 0 : webhookUrl.length());
    }

    private static String errorType(String stage, Throwable error) {
        if (error == null) {
            return "unknown";
        }
        if ("webhookHost.uri".equals(stage) && error instanceof IllegalArgumentException) {
            return "invalid_url";
        }
        return SafeRedactor.traceLabelOrFallback(error.getClass().getSimpleName(), "unknown");
    }
}
