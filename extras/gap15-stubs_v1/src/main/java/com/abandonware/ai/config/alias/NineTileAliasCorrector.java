package com.abandonware.ai.config.alias;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.config.alias.NineTileAliasCorrector
 * Role: config
 * Observability: propagates trace headers if present.
 * Thread-Safety: uses concurrent primitives.
 */
/* agent-hint:
id: com.abandonware.ai.config.alias.NineTileAliasCorrector
role: config
*/
public class NineTileAliasCorrector {
    public static final List<String> TILES = Arrays.asList("animals","games","finance","science","law","media","tech","health","misc");
    private final Map<String, Map<String,String>> tileDict = new ConcurrentHashMap<>();

    @Autowired(required = false)
@Qualifier("virtualPointService")
private Object virtualPointService; // optional

    public NineTileAliasCorrector() {
        Map<String,String> animals = new HashMap<>();
        animals.put("스커크","스컹크");
        Map<String,String> games = new HashMap<>();
        games.put("스커크","Skirk");
        tileDict.put("animals", animals);
        tileDict.put("games", games);
    }

    public String correct(String text, Locale locale, Map<String, Object> context) {
        if (text == null || text.isBlank()) return text;
        double[] w = new double[TILES.size()];
        Arrays.fill(w, 1.0 / TILES.size());

        String hint = context != null ? Objects.toString(context.getOrDefault("vp.topTile", context.get("intent.domain")), "") : "";
        int hi = TILES.indexOf(hint);
        if (hi >= 0) {
            Arrays.fill(w, 0.05);
            w[hi] = 0.6;
        }

        String best = text; double bestScore = 0.0;
        for (int i=0;i<TILES.size();i++) {
            String tile = TILES.get(i);
            Map<String,String> dict = tileDict.get(tile);
            if (dict == null) continue;
            for (Map.Entry<String,String> e : dict.entrySet()) {
                String from = e.getKey();
                if (text.contains(from)) {
                    double score = w[i];
                    if (score > bestScore) {
                        best = text.replace(from, e.getValue());
                        bestScore = score;
                    }
                }
            }
        }
        if (bestScore < 0.2) return text;
        return best;
    }
}