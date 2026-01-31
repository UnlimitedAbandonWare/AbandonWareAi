// src/main/java/com/example/lms/service/rag/policy/PairingPolicy.java
package com.example.lms.service.rag.policy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Set;




@Component
public class PairingPolicy {
    @Value("${abandonware.policy.pairing.min-evidence:2}")
    private int minEvidence;

    @Value("${abandonware.policy.pairing.trusted-hosts:namu.wiki,www.hoyolab.com,genshin.hoyoverse.com,hoyolab.com}")
    private String trustedHostsCsv;

    public int minEvidence() { return Math.max(1, minEvidence); }

    public Set<String> trustedHosts() {
        return java.util.Arrays.stream(trustedHostsCsv.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }
}