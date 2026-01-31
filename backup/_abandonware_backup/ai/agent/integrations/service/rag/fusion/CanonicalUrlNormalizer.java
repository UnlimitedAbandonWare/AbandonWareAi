package com.abandonware.ai.agent.integrations.service.rag.fusion;


import java.net.*;
import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.service.rag.fusion.CanonicalUrlNormalizer
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.service.rag.fusion.CanonicalUrlNormalizer
role: config
*/
public class CanonicalUrlNormalizer {
    private static final Set<String> DROP = new HashSet<>(Arrays.asList(
        "utm_source","utm_medium","utm_campaign","utm_term","utm_content","gclid","fbclid","session","sid","phpsessid"
    ));
    public String normalize(String url){
        if (url == null || url.isBlank()) return url;
        try {
            URI u = new URI(url);
            String query = u.getQuery();
            if (query == null) return u.toString();
            String[] pairs = query.split("&");
            List<String> kept = new ArrayList<>();
            for (String p: pairs){
                String[] kv = p.split("=", 2);
                String k = URLDecoder.decode(kv[0], "UTF-8").toLowerCase(Locale.ROOT);
                if (DROP.contains(k)) continue;
                kept.add(p);
            }
            Collections.sort(kept);
            String q = kept.isEmpty() ? null : String.join("&", kept);
            URI out = new URI(u.getScheme(), u.getAuthority(), u.getPath(), q, u.getFragment());
            return out.toString();
        } catch (Exception e){
            return url;
        }
    }
}