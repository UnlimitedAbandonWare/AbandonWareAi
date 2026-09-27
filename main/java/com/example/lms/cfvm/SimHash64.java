
package com.example.lms.cfvm;

import java.nio.charset.StandardCharsets;



public class SimHash64 {
    public static long hash(String s) {
        byte[] b = s==null? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        long x=0;
        for (byte v: b) {
            x ^= (x<<5) + (x>>2) + (v & 0xff) + 0x9e3779b97f4a7c15L;
        }
        return x;
    }
}