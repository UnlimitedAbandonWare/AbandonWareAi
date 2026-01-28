package com.abandonware.ai.alias;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.alias.TileAliasCorrector
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.alias.TileAliasCorrector
role: config
*/
public class TileAliasCorrector {
    private final Map<String, Map<String,String>> tiles = new HashMap<>();
    public TileAliasCorrector() {
        tiles.put("animals", Map.of("스커크","스컹크"));
        tiles.put("games", Map.of("스커크","Skirk"));
        // /* ... */ add 7 more domains as needed
    }
    public String correct(String token, String context) {
        // if context contains '냄새' -> animals, if '대사' -> games, etc (very naive)
        String domain = context.contains("냄새") ? "animals" : (context.contains("대사") ? "games" : "games");
        return tiles.getOrDefault(domain, Map.of()).getOrDefault(token, token);
    }
}