package com.example.lms.service.rag.fusion;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;




import static org.junit.jupiter.api.Assertions.*;

/**
 * Simple sanity test ensuring that documents appearing across multiple
 * sources are promoted to the top by weighted reciprocal rank fusion.
 */
public class WeightedReciprocalRankFuserKgTest {

    @Test
    void fuse_promotes_duplicates_across_sources() {
        WeightedReciprocalRankFuser fuser =
                new WeightedReciprocalRankFuser(60, null, "");

        List<Content> web = new ArrayList<>();
        web.add(Content.from(TextSegment.from("Doc A")));
        web.add(Content.from(TextSegment.from("Doc B")));
        web.add(Content.from(TextSegment.from("Doc C")));

        List<Content> sem = new ArrayList<>();
        sem.add(Content.from(TextSegment.from("Doc A")));
        sem.add(Content.from(TextSegment.from("Doc D")));
        sem.add(Content.from(TextSegment.from("Doc E")));

        List<Content> kg = new ArrayList<>();
        kg.add(Content.from(TextSegment.from("Doc B")));
        kg.add(Content.from(TextSegment.from("Doc A")));

        List<List<Content>> lists = new ArrayList<>();
        lists.add(web);
        lists.add(sem);
        lists.add(kg);

        List<Content> fused = fuser.fuse(lists, 10);
        assertNotNull(fused);
        assertFalse(fused.isEmpty());
        String topText = fused.get(0).textSegment() != null
                ? fused.get(0).textSegment().text()
                : fused.get(0).toString();
        assertTrue(topText.contains("Doc A"), "Doc A should be ranked first due to presence in all sources");
    }
}