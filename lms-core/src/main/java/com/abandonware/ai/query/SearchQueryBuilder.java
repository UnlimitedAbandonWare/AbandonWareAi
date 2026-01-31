package com.abandonware.ai.query;

import java.util.*;

public class SearchQueryBuilder {
    public String build(String normalized, List<String> anchors, List<String> synonyms){
        List<String> terms = new ArrayList<>();
        if (anchors != null) {
            for (String a : anchors) {
                if (a == null || a.isBlank()) continue;
                terms.add(a.startsWith("\"")? a : ("\"" + a + "\""));
            }
        }
        if (synonyms != null && !synonyms.isEmpty()){
            terms.add("(" + String.join(" OR ", synonyms) + ")");
        }
        if (normalized != null && !normalized.isBlank()) {
            terms.add(normalized);
        }
        return String.join(" ", terms);
    }
}
