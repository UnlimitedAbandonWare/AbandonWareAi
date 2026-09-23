package com.abandonware.ai.integrations.n8n;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Component
public class SignatureVerifier {
    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);

    @Value("${n8n.hmac.secret:change-me}")
    private String secret;

    public boolean verify(String sig, String body) {
        try {
            if (sig == null) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String calc = Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
            return sig.equals(calc);
        } catch (Exception e) {
            logFailSoft("verify", e);
            return false;
        }
    }

    private static void logFailSoft(String stage, Exception e) {
        if (log.isDebugEnabled()) {
            String errorType = e == null ? "unknown" : e.getClass().getSimpleName();
            log.debug("[AWX][n8n][signature] failSoft stage={} errorType={}", stage, errorType);
        }
    }
}
