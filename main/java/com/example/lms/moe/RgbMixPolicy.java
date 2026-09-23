package com.example.lms.moe;

import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Merge policy used only by offline/training pipeline.
 */
public final class RgbMixPolicy {

    private RgbMixPolicy() {}

    public static List<String> mergeQueries(List<String> q1, List<String> q2) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        addQueries(out, q1);
        addQueries(out, q2);
        List<String> l = new ArrayList<>(out);
        // cheap quality filter
        l.removeIf(s -> s == null || s.isBlank() || s.length() < 2);
        // cap
        if (l.size() > 60) {
            return l.subList(0, 60);
        }
        return l;
    }

    private static void addQueries(LinkedHashSet<String> out, List<String> in) {
        if (in == null) return;
        for (String s : in) {
            if (s == null) continue;
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
    }

    public static List<UnifiedRagOrchestrator.Doc> mergeDocs(List<UnifiedRagOrchestrator.Doc> d1,
                                                            List<UnifiedRagOrchestrator.Doc> d2) {
        Map<String, UnifiedRagOrchestrator.Doc> m = new LinkedHashMap<>();
        putDocs(m, d1);
        putDocs(m, d2);
        return new ArrayList<>(m.values());
    }

    private static void putDocs(Map<String, UnifiedRagOrchestrator.Doc> m, List<UnifiedRagOrchestrator.Doc> docs) {
        if (docs == null) return;
        for (UnifiedRagOrchestrator.Doc d : docs) {
            if (d == null) continue;
            String key = stableKey(d);
            if (key == null) continue;
            m.putIfAbsent(key, d);
        }
    }

    private static String stableKey(UnifiedRagOrchestrator.Doc d) {
        if (d == null) return null;
        if (d.meta != null) {
            Object url = d.meta.get("url");
            if (url != null && !String.valueOf(url).isBlank()) {
                return "url:" + String.valueOf(url);
            }
        }
        if (d.id != null && !d.id.isBlank()) {
            return "id:" + d.id;
        }
        if (d.title != null && !d.title.isBlank()) {
            return "t:" + d.title;
        }
        return null;
    }
}
