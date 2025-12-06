package com.abandonware.ai.service.rag.auth;

import org.springframework.stereotype.Service;

import java.util.*;

@Service
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.auth.DomainProfileLoader
 * Role: service
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.auth.DomainProfileLoader
role: service
*/
public class DomainProfileLoader {
    private final Map<String, List<String>> profiles = new HashMap<>();

    public DomainProfileLoader() {
        profiles.put("default", Arrays.asList("go.kr", "ac.kr", "gov", "edu", "reuters.com", "apnews.com", "nature.com"));
        profiles.put("news", Arrays.asList("reuters.com", "apnews.com", "bbc.co.uk", "nytimes.com"));
        profiles.put("scholar", Arrays.asList("ac.kr", "edu", "arxiv.org", "nature.com", "science.org"));
        profiles.put("game", Arrays.asList("playstation.com", "xbox.com", "steamcommunity.com"));
    }

    public List<String> getProfile(String name) {
        return profiles.getOrDefault(name, profiles.get("default"));
    }
}