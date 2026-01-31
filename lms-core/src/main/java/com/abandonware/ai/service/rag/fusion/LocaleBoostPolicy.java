package com.abandonware.ai.service.rag.fusion;

import com.abandonware.ai.service.rag.model.ContextSlice;

import java.net.URI;
import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.service.rag.fusion.LocaleBoostPolicy
 * Role: config
 * Dependencies: com.abandonware.ai.service.rag.model.ContextSlice
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.service.rag.fusion.LocaleBoostPolicy
role: config
*/
public class LocaleBoostPolicy {
    private final List<String> boostDomains;
    private final double boost;

    public LocaleBoostPolicy(){
        this(Arrays.asList(".go.kr",".gov.kr",".ac.kr",".kr"), 1.05);
    }
    public LocaleBoostPolicy(List<String> domains, double boost){
        this.boostDomains = new ArrayList<>(domains);
        this.boost = boost <= 0 ? 1.0 : boost;
    }

    public double multiplier(ContextSlice d){
        try{
            String id = d.getId();
            if (id == null) return 1.0;
            URI u = URI.create(id);
            String host = u.getHost();
            if (host == null) return 1.0;
            String h = host.toLowerCase(Locale.ROOT);
            for (String suffix : boostDomains){
                if (h.endsWith(suffix)) return boost;
            }
            return 1.0;
        }catch(Exception e){
            return 1.0;
        }
    }
}