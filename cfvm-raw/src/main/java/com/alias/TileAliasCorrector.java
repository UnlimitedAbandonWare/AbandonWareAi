package com.alias;

import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.alias.TileAliasCorrector
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.alias.TileAliasCorrector
role: config
*/
public class TileAliasCorrector {
    private final Map<String, Map<String,String>> tiles = new HashMap<>();
    public TileAliasCorrector() {
        Map<String,String> animals = new HashMap<>();
        animals.put("스커크","스컹크");
        Map<String,String> games = new HashMap<>();
        games.put("스커크","Skirk");
        tiles.put("animals", animals);
        tiles.put("games", games);
    }
    public String correct(String token, String context) {
        if (token == null) return null;
        String domain = (context != null && context.contains("냄새")) ? "animals"
                : ((context != null && context.contains("대사")) ? "games" : "games");
        Map<String,String> dict = tiles.getOrDefault(domain, Map.of());
        return dict.getOrDefault(token, token);
    }
}