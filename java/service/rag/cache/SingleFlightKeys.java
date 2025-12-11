// src/main/java/service/rag/cache/SingleFlightKeys.java
package service.rag.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class SingleFlightKeys {
    private SingleFlightKeys(){}

    public static String web(String q, int k) { return sha("web|" + q + "|" + k); }
    public static String vec(String q, int k) { return sha("vec|" + q + "|" + k); }
    public static String kg (String q, int k) { return sha("kg|"  + q + "|" + k); }

    private static String sha(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(dig);
        } catch (Exception e) { return Integer.toHexString(s.hashCode()); }
    }
}