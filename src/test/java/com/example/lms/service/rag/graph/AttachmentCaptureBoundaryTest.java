package com.example.lms.service.rag.graph;

import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.assist.MemoryEvidence;
import com.example.lms.search.TraceStore;
import com.abandonware.ai.addons.budget.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AttachmentCaptureBoundaryTest {
    @Test void currentSelectedAttachmentSurvivesEightTranscriptCandidates() {
        var session=new com.example.lms.domain.ChatSession("synthetic","owner","ANON");session.setId(42L);
        var scope=GeneralGraphScope.authorize(session,null,"owner").orElseThrow().withPolicy(1,true);
        String id="33333333-3333-3333-3333-333333333333";
        var authority=mock(GeneralGraphSourceAuthority.class);
        when(authority.selectedAttachmentIds(scope)).thenReturn(List.of(id));
        var refs=new ArrayList<KgChunk.SourceRef>();
        for(int i=1;i<=8;i++)refs.add(new KgChunk.SourceRef("chat-message:"+i,1));
        refs.add(new KgChunk.SourceRef("attachment:"+id,1));
        when(authority.source(eq(scope),any())).thenAnswer(inv->{
            KgChunk.SourceRef r=inv.getArgument(1);
            return Optional.of(new MemoryEvidence(r.sourceId(),r.sourceId(),1,"owner","USER","USER_REPORTED",
                "original",null,null,null,null,null,null,null));
        });
        when(authority.evidenceContent(any())).thenReturn(dev.langchain4j.rag.content.Content.from("chat"));
        when(authority.attachmentContents(any())).thenReturn(List.of(dev.langchain4j.rag.content.Content.from("report.md rev 1 L1")));
        var brain=mock(BrainStateService.class);
        when(brain.privateSources(eq(scope),anyString(),anyInt())).thenReturn(refs);
        @SuppressWarnings("unchecked") ObjectProvider<BrainStateService> provider=mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(brain);
        var handler=new KnowledgeGraphHandler(null,null,null,null,provider);
        ReflectionTestUtils.setField(handler,"sourceAuthority",authority);
        var query=QueryUtils.buildQuery("report",42L,null,Map.of(GeneralGraphScope.METADATA_KEY,scope));
        var found=handler.retrieve(query);
        assertEquals(8,found.size());
        assertTrue(found.stream().anyMatch(c->c.textSegment().text().contains("report.md")));
        when(authority.source(scope,refs.get(8))).thenReturn(Optional.empty());
        assertTrue(handler.retrieve(query).stream().noneMatch(c->c.textSegment().text().contains("report.md")));
    }

    @Test void cancelledOrFailedAttachmentDoesNotEscapeOrStartAnother() {
        for(RuntimeException failure:List.of(new java.util.concurrent.CancellationException("private cancellation"),
                new IllegalStateException("private failure"))) {
            TraceStore.clear();TraceStore.put("finalAnswer.memorySaveAllowed",true);
            TimeBudgetContext.set(new TimeBudget(10000));
            try {
                var session=new com.example.lms.domain.ChatSession("synthetic","owner","ANON");session.setId(42L);
                var scope=GeneralGraphScope.authorize(session,null,"owner").orElseThrow().withPolicy(1,true);
                var graph=mock(GraphRagChunkingService.class);var authority=mock(GeneralGraphSourceAuthority.class);
                when(authority.authorizeAttachmentCollection(any(),anyString(),anyLong(),eq(true))).thenReturn(true);
                when(graph.ingestAttachmentSource(any(),any())).thenThrow(failure);
                var aspect=new BrainStateChatWorkflowAspect(new BrainStateProperties(),graph,Runnable::run);
                ReflectionTestUtils.setField(aspect,"sourceAuthority",authority);
                var refs=List.of(new KgChunk.SourceRef("attachment:33333333-3333-3333-3333-333333333333",1),
                    new KgChunk.SourceRef("attachment:44444444-4444-4444-4444-444444444444",1));
                assertDoesNotThrow(()->aspect.captureFinalized(scope,1L,2L,refs));
                verify(graph,times(1)).ingestAttachmentSource(any(),any());
                assertEquals(failure instanceof java.util.concurrent.CancellationException?"cancelled":"failed",TraceStore.get("attachment.graph.capture"));
            }finally{TimeBudgetContext.clear();TraceStore.clear();}
        }
    }
}
