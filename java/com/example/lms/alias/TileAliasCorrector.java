package com.example.lms.alias;

import java.util.*;
import java.util.regex.Pattern;

/**
 * TileAliasCorrector
 * - Context-aware alias correction overlay.
 * - If confidence is low, returns input unchanged (overlay/fail-soft).
 * - Pure Java (no external deps).
 */
public class TileAliasCorrector {

    private final TileDictionaries dictionaries;

    public TileAliasCorrector() {
        this.dictionaries = new TileDictionaries();
    }

    public TileAliasCorrector(TileDictionaries dicts) {
        this.dictionaries = (dicts != null) ? dicts : new TileDictionaries();
    }

    /** Heuristic hint detection for tiles */
    private List<TileDictionaries.Tile> detectTilesByContext(String text) {
        String t = text == null ? "" : text;
        t = t.toLowerCase(Locale.ROOT);

        List<TileDictionaries.Tile> hints = new ArrayList<>();

        // Animal hints
        if (containsAny(t, Arrays.asList("냄새","분비","냄새나","동물","포유류"))) {
            hints.add(TileDictionaries.Tile.ANIMAL);
        }
        // Game hints
        if (containsAny(t, Arrays.asList("대사","캐릭터","퀘스트","게임","npc","스킬"))) {
            hints.add(TileDictionaries.Tile.GAME);
        }
        // Fallback
        if (hints.isEmpty()) hints.add(TileDictionaries.Tile.OTHER);
        return hints;
    }

    private boolean containsAny(String text, List<String> needles) {
        for (String n : needles) {
            if (text.contains(n)) return true;
        }
        return false;
    }

    /** Token-level alias correction with tile weighting */
    public String correct(String text) {
        if (text == null || text.isEmpty()) return text;

        List<TileDictionaries.Tile> tiles = detectTilesByContext(text);
        // Tokenize very simply (keep it conservative to avoid over-correction)
        String[] tokens = text.split("(?<=\\s)|(?=\\s)");
        for (int i=0;i<tokens.length;i++) {
            String tok = tokens[i];
            if (tok.trim().isEmpty()) continue;

            String best = null;
            for (TileDictionaries.Tile tile : tiles) {
                String cand = dictionaries.resolve(tile, tok);
                if (cand != null) { best = cand; break; }
            }
            if (best != null) tokens[i] = best;
        }
        return String.join("", tokens);
    }
}