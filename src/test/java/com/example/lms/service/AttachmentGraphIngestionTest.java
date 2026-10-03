package com.example.lms.service;

import com.example.lms.service.rag.graph.*;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.service.rag.kg.Neo4jKnowledgeGraphClient;
import com.example.lms.service.rag.QueryUtils;
import com.example.lms.service.vector.DocumentChunkingService;
import com.example.lms.service.rag.knowledge.UniversalContextLexicon;
import com.example.lms.service.ner.NamedEntityExtractor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AttachmentGraphIngestionTest {
    @TempDir Path directory;
    GraphRagChunkingService.IngestReport ingest(GraphRagChunkingService service, GeneralGraphScope scope,
                                               KgChunk.SourceRef ref) throws Exception {
        var method=GraphRagChunkingService.class.getMethod("ingestAttachmentSource",GeneralGraphScope.class,KgChunk.SourceRef.class);
        return (GraphRagChunkingService.IngestReport)method.invoke(service,scope,ref);
    }
    @Test void persistsRulesOnlyIntoExistingPrivateGraphAndRequeriesCurrentOriginal() throws Exception {
        var fixture=new AttachmentGraphAuthorityTest();fixture.directory=directory;
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("graph"))){
            var scope=fixture.scope();long revision=fixture.prepare(db.store,scope);
            var ref=new KgChunk.SourceRef("attachment:"+fixture.id,revision);
            var props=new BrainStateProperties();var writer=mock(Neo4jKgChunkWriter.class);
            when(writer.writeChunks(anyList())).thenReturn(new Neo4jKgChunkWriter.WriteReport(true,"written","",1,0,0,"",null));
            var brain=new BrainStateService(props,writer,null);
            var vectors=mock(VectorStoreService.class);var ner=mock(NamedEntityExtractor.class);
            var graph=new GraphRagChunkingService(props,new DocumentChunkingService(),ner,writer,
                new UniversalContextLexicon(),vectors,brain,fixture.messages);
            ReflectionTestUtils.setField(graph,"sourceAuthority",fixture.authority);
            ReflectionTestUtils.setField(graph,"attachmentSources",db.store);
            var report=ingest(graph,scope,ref);
            assertEquals(1,report.chunkCount());assertEquals("queued",report.captureOutcome());
            assertEquals("QUEUED",db.store.find(fixture.id).orElseThrow().graphState());
            assertEquals("QUEUED",db.store.find(fixture.id).orElseThrow().vectorState());
            assertEquals(List.of(ref),brain.privateSources(scope,"fixture",10));
            verifyNoInteractions(ner,fixture.messages);
            verify(vectors).enqueue(anyString(),eq("42"),contains("fixture original"),argThat(meta->
                ref.sourceId().equals(meta.get("general_graph_source_id")) && "true".equals(meta.get("general_graph_private"))));
            @SuppressWarnings("unchecked") ObjectProvider<BrainStateService> provider=mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(brain);
            var handler=new KnowledgeGraphHandler(null,mock(Neo4jKnowledgeGraphClient.class),null,null,provider);
            ReflectionTestUtils.setField(handler,"sourceAuthority",fixture.authority);
            var query=QueryUtils.buildQuery("fixture",42L,null,Map.of(GeneralGraphScope.METADATA_KEY,scope));
            var found=handler.retrieve(query);assertEquals(1,found.size());
            assertTrue(found.get(0).textSegment().text().contains("fixture original"));
            assertTrue(found.get(0).textSegment().text().contains("DOCUMENT_REPORTED"));
            assertEquals("report.md",found.get(0).textSegment().metadata().getString("displayName"));
            var citations=new com.example.lms.service.rag.RagEvidenceAttributionService(null,null,null)
                .promoteForPrompt("fixture",List.of(),found,List.of(),com.example.lms.rag.model.QueryDomain.GENERAL,false);
            assertEquals(1,citations.size());assertNull(citations.get(0).source());
            assertEquals("report.md",citations.get(0).attachment().filename());
            assertEquals(revision,citations.get(0).attachment().revision());
            assertEquals("L1",citations.get(0).attachment().locator());
            db.store.tombstone(fixture.id);
            assertTrue(handler.retrieve(query).isEmpty(),"a stale derived node cannot authorize deleted bytes");
        }
    }
    @Test void readAccessDoesNotGrantCollectionOrUseSemanticProvider() throws Exception {
        var fixture=new AttachmentGraphAuthorityTest();fixture.directory=directory;
        try(var db=new AttachmentSourceStoreTest.Database(directory.resolve("denied"))){
            var scope=fixture.scope();long revision=fixture.prepare(db.store,scope);
            var renewed=fixture.authority.bindPolicy(fixture.authority.bindPolicy(scope,
                com.example.lms.domain.enums.MemoryProfile.OFF).orElseThrow(),
                com.example.lms.domain.enums.MemoryProfile.LIGHT).orElseThrow();
            var ner=mock(NamedEntityExtractor.class);var vectors=mock(VectorStoreService.class);
            var writer=mock(Neo4jKgChunkWriter.class);var brain=mock(BrainStateService.class);
            var graph=new GraphRagChunkingService(new BrainStateProperties(),new DocumentChunkingService(),ner,writer,
                new UniversalContextLexicon(),vectors,brain,fixture.messages);
            ReflectionTestUtils.setField(graph,"sourceAuthority",fixture.authority);
            ReflectionTestUtils.setField(graph,"attachmentSources",db.store);
            assertFalse(ingest(graph,renewed,new KgChunk.SourceRef("attachment:"+fixture.id,revision)).enabled());
            verifyNoInteractions(ner,vectors,writer,brain);
            assertFalse(fixture.authority.authorizeAttachmentCollection(renewed,fixture.id,revision,false));
        }
    }
}
