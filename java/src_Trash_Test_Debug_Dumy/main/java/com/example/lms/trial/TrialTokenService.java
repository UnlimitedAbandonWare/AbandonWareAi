package com.example.lms.trial;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Utility service for signing and verifying trial identifiers.  Cookies sent
 * to anonymous users contain a HMAC‑SHA256 signature followed by the raw
 * trial identifier.  The signature prevents clients from forging higher
 * quotas.  A {@link ThreadLocal} cache is used for the {@link Mac}
 * instance to avoid repeated reinitialisation on every request.
 */
@Service
public class TrialTokenService {
    private final TrialProperties props;

    private final ThreadLocal<Mac> macHolder = new ThreadLocal<>();

    public TrialTokenService(TrialProperties props) {
        this.props = props;
    }

    /**
     * Create a signed token for the given trial ID.  If the secret is
     * undefined or empty the raw ID is returned as the token.
     *
     * @param trialId the opaque trial identifier
     * @return a signed token suitable for a cookie value
     */
    public String sign(String trialId) {
        if (trialId == null || trialId.isEmpty()) {
            return (null);
        }
        String secret = props.getCookieSecret();
        if (secret == null || secret.isEmpty()) {
            return trialId;
        }
        Mac mac = getMac();
        byte[] sig = mac.doFinal(trialId.getBytes(StandardCharsets.UTF_8));
        String encSig = Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        return encSig + "." + trialId;
    }

    /**
     * Verify a signed token and extract the underlying trial ID.  Returns
     * {@code null} if verification fails.  When the secret is undefined
     * or empty the token is treated as a raw ID and returned directly.
     *
     * @param token the signed token from the client
     * @return the extracted trial ID or {@code null} on failure
     */
    public String verify(String token) {
        if (token == null || token.isEmpty()) {
            return (null);
        }
        String secret = props.getCookieSecret();
        if (secret == null || secret.isEmpty()) {
            return token;
        }
        int dot = token.indexOf('.');
        if (dot <= 0) {
            return (null);
        }
        String sigPart = token.substring(0, dot);
        String idPart = token.substring(dot + 1);
        Mac mac = getMac();
        byte[] expected = mac.doFinal(idPart.getBytes(StandardCharsets.UTF_8));
        byte[] provided;
        try {
            provided = Base64.getUrlDecoder().decode(sigPart);
        } catch (IllegalArgumentException e) {
            return (null);
        }
        if (!java.security.MessageDigest.isEqual(expected, provided)) {
            return (null);
        }
        return idPart;
    }

    private Mac getMac() {
        Mac m = macHolder.get();
        if (m == null) {
            try {
                m = Mac.getInstance("HmacSHA256");
                SecretKeySpec key = new SecretKeySpec(props.getCookieSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
                m.init(key);
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException("Unable to initialise HmacSHA256", e);
            }
            macHolder.set(m);
        }
        return m;
    }
}