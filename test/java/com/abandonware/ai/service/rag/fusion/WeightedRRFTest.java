package com.abandonware.ai.service.rag.fusion;

import com.abandonware.ai.service.rag.model.ContextSlice;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class WeightedRRFTest {

    @Test
    void fuse_dedup_and_weight() {
        var web = List.of(slice("http://ex.com/a","A","web",1.0),
                          slice("http://ex.com/b","B","web",0.9));
        var vec = List.of(slice("http://ex.com/a","A","vector",0.6)); // duplicate A
        WeightedRRF wr = new WeightedRRF();
        MinMaxCalibrator cal = new MinMaxCalibrator();
        var out = wr.fuse(List.of(web, vec), 60, Map.of("web",0.5,"vector",0.4), cal, true);
        assertEquals(2, out.size()); // A deduped
        assertTrue(out.values().iterator().next().getScore() >= 0); // has scores
    }

    private static ContextSlice slice(String id, String title, String source, double score) {
        ContextSlice c = new ContextSlice();
        c.setId(id); c.setTitle(title); c.setSource(source); c.setSnippet("");
        c.setScore(score); c.setRank(0);
        return c;
    }
}