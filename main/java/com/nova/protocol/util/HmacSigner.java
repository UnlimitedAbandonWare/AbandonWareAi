package com.nova.protocol.util;

import com.example.lms.config.ConfigValueGuards;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;



public class HmacSigner {

    public static String sign(Map<String, String> payload, String secret) {
        String safeSecret = requireSecret(secret);
        String data = encode(payload);
        String sig = hmacSha256Base64(data, safeSecret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((data + "." + sig).getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, String> verifyAndDecode(String token, String secret, long ttlSec) {
        String safeSecret = requireSecret(secret);
        byte[] dec = Base64.getUrlDecoder().decode(token);
        String all = new String(dec, StandardCharsets.UTF_8);
        int dot = all.lastIndexOf('.');
        if (dot < 0) throw new IllegalArgumentException("bad token");
        String data = all.substring(0, dot);
        String sig = all.substring(dot + 1);
        String expect = hmacSha256Base64(data, safeSecret);
        if (!constantTimeEquals(expect, sig)) throw new IllegalArgumentException("bad signature");
        Map<String, String> claims = decode(data);
        long exp = Long.parseLong(claims.getOrDefault("exp", "0"));
        long now = Instant.now().getEpochSecond();
        if (now > exp) throw new IllegalArgumentException("expired");
        if (ttlSec > 0 && exp > now + ttlSec) throw new IllegalArgumentException("ttl exceeded");
        return claims;
        }

    private static String hmacSha256Base64(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    private static String encode(Map<String, String> payload) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        Map<String, String> ordered = payload == null ? Map.of() : new TreeMap<>(payload);
        for (Map.Entry<String,String> e : ordered.entrySet()) {
            if (!first) sb.append("&");
            first = false;
            sb.append(formEncode(e.getKey())).append("=").append(formEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static Map<String, String> decode(String data) {
        Map<String,String> m = new HashMap<>();
        for (String kv : data.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            if (i < 0) continue;
            m.put(formDecode(kv.substring(0,i)), formDecode(kv.substring(i+1)));
        }
        return m;
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String formDecode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String requireSecret(String secret) {
        if (ConfigValueGuards.isMissing(secret)) {
            throw new IllegalArgumentException("missing secret");
        }
        return secret.trim();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
