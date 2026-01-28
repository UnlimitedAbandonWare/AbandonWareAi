package com.example.lms.service.rag.handler;

import com.example.lms.service.knowledge.KnowledgeBaseService;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.*;




import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class KnowledgeGraphHandlerScoreTest {

    @Test
    void ranking_prefers_recent_and_confident_entities() throws Exception {
        KnowledgeBaseService kb = Mockito.mock(KnowledgeBaseService.class);
        KnowledgeGraphHandler handler = new KnowledgeGraphHandler(kb);

        // Inject configuration values
        Field half = KnowledgeGraphHandler.class.getDeclaredField("kgHalfLifeDays");
        half.setAccessible(true);
        half.setInt(handler, 60);
        Field weights = KnowledgeGraphHandler.class.getDeclaredField("kgScoreWeights");
        weights.setAccessible(true);
        weights.set(handler, "0.25,0.45,0.20,0.10");
        Method parse = KnowledgeGraphHandler.class.getDeclaredMethod("parseWeights");
        parse.setAccessible(true);
        parse.invoke(handler);

        String text = "Tell me about Alpha and Beta";
        when(kb.inferDomain(text)).thenReturn("GENERAL");
        when(kb.findMentionedEntities("GENERAL", text)).thenReturn(new java.util.LinkedHashSet<>(List.of("Alpha", "Beta")));

        when(kb.getConfidenceScore("GENERAL", "Alpha")).thenReturn(Optional.of(0.9));
        when(kb.getConfidenceScore("GENERAL", "Beta")).thenReturn(Optional.of(0.6));

        Instant now = Instant.now();
        when(kb.getLastAccessedAt("GENERAL", "Alpha")).thenReturn(Optional.of(now.minusSeconds(1 * 24 * 3600))); // 1 day ago
        when(kb.getLastAccessedAt("GENERAL", "Beta")).thenReturn(Optional.of(now.minusSeconds(90 * 24 * 3600))); // 90 days ago

        Map<String, Set<String>> relAlpha = new LinkedHashMap<>();
        relAlpha.put("RELATIONSHIP_IS_A", new LinkedHashSet<>(List.of("Thing")));
        relAlpha.put("RELATIONSHIP_PART_OF", new LinkedHashSet<>(List.of("Universe")));
        relAlpha.put("RELATIONSHIP_ASSOCIATED_WITH", new LinkedHashSet<>(List.of("Gamma")));
        when(kb.getAllRelationships("GENERAL", "Alpha")).thenReturn(relAlpha);

        Map<String, Set<String>> relBeta = new LinkedHashMap<>();
        relBeta.put("RELATIONSHIP_IS_A", new LinkedHashSet<>(List.of("Thing")));
        relBeta.put("RELATIONSHIP_ASSOCIATED_WITH", new LinkedHashSet<>(List.of("Gamma","Delta","Epsilon","Zeta","Eta","Theta","Iota","Kappa")));
        when(kb.getAllRelationships("GENERAL", "Beta")).thenReturn(relBeta);

        List<Content> out = handler.retrieve(new Query(text));
        assertNotNull(out);
        assertTrue(out.size() >= 2, "Should produce at least two scored contents");
        String first = out.get(0).textSegment() != null ? out.get(0).textSegment().text() : out.get(0).toString();
        assertTrue(first.contains("[KG | Alpha"), "Alpha should be ranked first due to higher confidence and recency");
    }
}