package com.example.lms.alias;

import java.util.*;

/**
 * TileDictionaries
 * - 9-tile lightweight alias dictionaries (seed only).
 * - No external storage; safe to extend incrementally.
 */
public class TileDictionaries {

    public enum Tile {
        ANIMAL, GAME, MEDICAL, FINANCE, GOV, ACADEMIC, NEWS, TECH, OTHER
    }

    private final Map<Tile, Map<String, String>> dicts = new EnumMap<>(Tile.class);

    public TileDictionaries() {
        for (Tile t : Tile.values()) dicts.put(t, new HashMap<>());

        // Seed examples (Korean)
        dicts.get(Tile.ANIMAL).put("스커크", "스컹크");
        dicts.get(Tile.GAME).put("스커크", "Skirk");

        // Add more seeds as needed/* ... */
    }

    public Map<Tile, Map<String, String>> getAll() {
        return Collections.unmodifiableMap(dicts);
    }

    public String resolve(Tile tile, String token) {
        Map<String,String> m = dicts.get(tile);
        if (m == null) return null;
        return m.get(token);
    }
}