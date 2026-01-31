package com.example.lms.integrations.n8n;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class SignatureVerifier {
    private final String secret;
    public SignatureVerifier(String secret){ this.secret = secret; }

    public boolean verify(byte[] body, String headerValue) {
        if (headerValue == null || !headerValue.startsWith("sha256=")) return false;
        String expected = "sha256=" + hex(hmacSha256(body, secret));
        return constantTimeEq(expected, headerValue);
    }

    private static byte[] hmacSha256(byte[] data, String secret){
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        }catch(Exception e){ throw new RuntimeException(e); }
    }
    private static String hex(byte[] b){ StringBuilder sb=new StringBuilder(); for(byte x:b) sb.append(String.format("%02x",x)); return sb.toString(); }
    private static boolean constantTimeEq(String a, String b){
        if (a == null || b == null) return false; int r = a.length() ^ b.length();
        for (int i=0;i<Math.min(a.length(), b.length());i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}