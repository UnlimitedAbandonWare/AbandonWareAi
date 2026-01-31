package com.abandonware.ai.config.alias;

import java.util.*;
import org.springframework.stereotype.Component;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.config.alias.NineTileAliasCorrector
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.config.alias.NineTileAliasCorrector
role: config
*/
public class NineTileAliasCorrector {
    private final Map<String,String> dict = new HashMap<>();
    public NineTileAliasCorrector() {
        dict.put("스커크","스컹크");
        dict.put("스털린","스털링");
    }
    public String normalize(String text) {
        if (text == null) return null;
        return dict.getOrDefault(text, text);
    }
}