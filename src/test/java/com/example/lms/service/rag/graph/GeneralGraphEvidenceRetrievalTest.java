package com.example.lms.service.rag.graph;

import com.example.lms.domain.ChatSession;
import com.example.lms.assist.MemoryEvidence;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient;
import com.example.lms.service.rag.QueryUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GeneralGraphEvidenceRetrievalTest {
    private GeneralGraphScope scope() {
        var session = new ChatSession("synthetic", "owner", "ANON");
        session.setId(7L);
        return GeneralGraphScope.authorize(session, null, "owner").orElseThrow().withPolicy(1, true);
    }
    @Test void graphCandidatesMustResolveToCurrentOriginalEvidence() {
        var scope = scope();
        var neo = mock(Neo4jKnowledgeGraphClient.class);
        var brain = mock(BrainStateService.class);
        @SuppressWarnings("unchecked") ObjectProvider<BrainStateService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(brain);
        var authority = mock(GeneralGraphSourceAuthority.class);
        var handler = new KnowledgeGraphHandler(null, neo, null, null, provider);
        ReflectionTestUtils.setField(handler, "sourceAuthority", authority);
        var good = new KgChunk.SourceRef("chat-message:11", 3);
        var stale = new KgChunk.SourceRef("chat-message:12", 1);
        when(neo.lookupSources(eq(scope), anyString(), anyInt())).thenReturn(List.of(good, stale));
        when(brain.privateSources(eq(scope), anyString(), anyInt())).thenReturn(List.of(good));
        var evidence = new MemoryEvidence("e", good.sourceId(), 3, scope.ownerNamespace(), "USER",
                "USER_REPORTED", "Spark was considered, not purchased", null, null, null, null, null, null, null);
        when(authority.source(scope, good)).thenReturn(Optional.of(evidence));
        when(authority.source(scope, stale)).thenReturn(Optional.empty());
        var real = new GeneralGraphSourceAuthority(null, null, new ObjectMapper());
        when(authority.evidenceContent(evidence)).thenReturn(real.evidenceContent(evidence));
        Query query = QueryUtils.buildQuery("Spark", 7L, null, Map.of(GeneralGraphScope.METADATA_KEY, scope));
        var results = handler.retrieve(query);
        assertEquals(1, results.size());
        String text = results.get(0).textSegment().text();
        assertTrue(text.contains("not purchased"));
        assertTrue(text.contains("CO_MENTIONED_WITH"));
        assertTrue(text.contains("USER_REPORTED"));
        verify(authority).source(scope, good);
        verify(authority).source(scope, stale);
    }
    @Test void noCapabilityOrChangedSessionNeverReadsPrivateFallback() {
        var neo = mock(Neo4jKnowledgeGraphClient.class);
        var brain = mock(BrainStateService.class);
        @SuppressWarnings("unchecked") ObjectProvider<BrainStateService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(brain);
        var handler = new KnowledgeGraphHandler(null, neo, null, null, provider);
        ReflectionTestUtils.setField(handler, "sourceAuthority", mock(GeneralGraphSourceAuthority.class));
        assertTrue(handler.retrieve(Query.from("Spark")).isEmpty());
        var changed = QueryUtils.buildQuery("Spark", 8L, null,
                Map.of(GeneralGraphScope.METADATA_KEY, scope()));
        assertTrue(handler.retrieve(changed).isEmpty());
        verifyNoInteractions(neo, brain);
    }
    @Test void assistantHypothesisRemainsAnUntrustedAssistantStatement() {
        var authority = new GeneralGraphSourceAuthority(null, null, new ObjectMapper());
        var evidence = new MemoryEvidence("e", "chat-message:11", 1, scope().ownerNamespace(), "ASSISTANT",
                "ASSISTANT_GENERATED", "The power supply might be the cause.", null, null, null, null, null, null, null);
        String text = authority.evidenceContent(evidence).textSegment().text();
        assertTrue(text.contains("ASSISTANT_GENERATED"));
        assertTrue(text.contains("not verified user facts"));
        assertTrue(text.contains("might be"));
        assertFalse(text.contains(scope().ownerNamespace()));
    }
}
