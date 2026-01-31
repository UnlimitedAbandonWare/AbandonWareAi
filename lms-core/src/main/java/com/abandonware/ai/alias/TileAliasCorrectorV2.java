package com.abandonware.ai.alias;

import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.*;

@Component
public class TileAliasCorrectorV2 {
    private final Map<String, Map<String,String>> dictByTile = new HashMap<>();
    private static final List<String> TILES = List.of(
        "animals","games","finance","science","law","media","tech","health","misc"
    );

    public TileAliasCorrectorV2() {
        loadYamlDictionaries("classpath*:aliases/*.yaml");
    }

    public String correct(String text, Locale locale, Map<String,Object> ctx) {
        if (text == null || text.isBlank()) return text;
        String s = normalize(text);
        String hint = Objects.toString(ctx==null?null:ctx.get("intent.domain"), "");
        List<String> order = orderTiles(hint, ctx);
        for (String tile : order) {
            Map<String,String> m = dictByTile.get(tile);
            if (m == null) continue;
            for (Map.Entry<String,String> e : m.entrySet()) {
                if (s.contains(e.getKey())) s = s.replace(e.getKey(), e.getValue());
            }
        }
        return s;
    }

    private List<String> orderTiles(String hint, Map<String,Object> ctx){
        List<String> list = new ArrayList<>(TILES);
        if (TILES.contains(hint)) { list.remove(hint); list.add(0, hint); }
        String vp = Objects.toString(ctx==null?null:ctx.get("vp.topTile"), "");
        if (TILES.contains(vp)) { list.remove(vp); list.add(0, vp); }
        return list;
    }

    private void loadYamlDictionaries(String pattern){
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            var resources = resolver.getResources(pattern);
            var yaml = new Yaml();
            for (var r : resources) try (InputStream in = r.getInputStream()) {
                Map<String,Object> y = yaml.load(in);
                if (y==null) continue;
                String tile = Objects.toString(y.get("tile"), "misc");
                Map<String,String> pairs = (Map<String,String>) y.getOrDefault("aliases", Map.of());
                dictByTile.computeIfAbsent(tile, k->new LinkedHashMap<>()).putAll(pairs);
            }
        } catch (Exception ignore) { }
    }

    private static String normalize(String x){
        String s = Normalizer.normalize(x, Normalizer.Form.NFKC);
        s = s.replace('\u00A0',' ').replaceAll("[\u200B-\u200D\uFEFF]", "");
        s = s.replace("“","\"").replace("”","\"").replace("‘","'").replace("’","'");
        return s.trim();
    }
}
