package com.nova.protocol.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;



public class HmacSigner {

    public static String sign(Map<String, String> payload, String secret) {
        String data = encode(payload);
        String sig = hmacSha256Base64(data, secret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((data + "." + sig).getBytes(StandardCharsets.UTF_8));
    }

    public static Map<String, String> verifyAndDecode(String token, String secret, long ttlSec) {
        byte[] dec = Base64.getUrlDecoder().decode(token);
        String all = new String(dec, StandardCharsets.UTF_8);
        int dot = all.lastIndexOf('.');
        if (dot < 0) throw new IllegalArgumentException("bad token");
        String data = all.substring(0, dot);
        String sig = all.substring(dot + 1);
        String expect = hmacSha256Base64(data, secret);
        if (!expect.equals(sig)) throw new IllegalArgumentException("bad signature");
        Map<String, String> claims = decode(data);
        long exp = Long.parseLong(claims.getOrDefault("exp", "0"));
        long now = Instant.now().getEpochSecond();
        if (now > exp) throw new IllegalArgumentException("expired");
        return claims;
        }

    private static String hmacSha256Base64(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static String encode(Map<String, String> payload) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String,String> e : payload.entrySet()) {
            if (!first) sb.append("&");
            first = false;
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }

    private static Map<String, String> decode(String data) {
        Map<String,String> m = new HashMap<>();
        for (String kv : data.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            if (i < 0) continue;
            m.put(kv.substring(0,i), kv.substring(i+1));
        }
        return m;
    }
}