package com.abandonware.ai.query;

import java.util.*;
import java.util.regex.Pattern;

public class ContextCoreExtractor {
    private static final Set<String> STOP = Set.of("그리고","그러나","하지만","대한","관련","하는","하면","에서","으로","입니다","이라고");
    private static final Pattern KOR = Pattern.compile("[가-힣]{2,}");
    private static final Pattern ENG = Pattern.compile("[A-Za-z]{2,}([- ]?[A-Za-z0-9]{2,})*");

    public List<String> anchors(String text, int topN){
        if (text == null || text.isBlank()) return List.of();
        String s = text.replaceAll("[^\\p{L}\\p{N}\\s-]+"," ").replaceAll("\\s+"," ").trim();
        String[] toks = s.split(" ");
        Map<String,Integer> freq = new LinkedHashMap<>();
        for (int i=0;i<toks.length;i++){
            String t = toks[i];
            if (t.length()<2) continue;
            if (STOP.contains(t)) continue;
            if (!(KOR.matcher(t).find() || ENG.matcher(t).find())) continue;
            freq.merge(t,1,Integer::sum);
            if (i+1<toks.length){
                String bi = (t + " " + toks[i+1]).trim();
                if (bi.length()>=4) freq.merge("\"" + bi + "\""",1,Integer::sum);
            }
        }
        return freq.entrySet().stream()
                .sorted((a,b)->Integer.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, topN))
                .map(Map.Entry::getKey)
                .toList();
    }
}
