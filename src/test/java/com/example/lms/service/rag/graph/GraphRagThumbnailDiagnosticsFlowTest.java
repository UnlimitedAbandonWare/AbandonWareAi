package com.example.lms.service.rag.graph;

import com.example.lms.api.BrainStateDiagnosticsController;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.service.VectorStoreService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.rag.knowledge.UniversalContextLexicon;
import com.example.lms.service.rag.langgraph.RagOrchestratorFacade;
import com.example.lms.service.rag.handler.KnowledgeGraphHandler;
import com.example.lms.service.rag.kg.KgTailPowerMeanScorer;
import com.example.lms.service.rag.orchestrator.UnifiedRagOrchestrator;
import com.example.lms.service.vector.DocumentChunkingService;
import com.example.lms.uaw.thumbnail.UawThumbnailPersistedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GraphRagThumbnailDiagnosticsFlowTest {

    @Test
    void thumbnailBridgeFeedsBrainStateSnapshotAndDiagnosticsStayRedacted() throws Exception {
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.disabledReason()).thenReturn("disabled");
        when(writer.status()).thenReturn(Map.of("enabled", false, "disabledReason", "disabled"));
        when(writer.writeChunks(anyList())).thenReturn(Neo4jKgChunkWriter.WriteReport.disabled("disabled"));
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> facadeProvider = mock(ObjectProvider.class);
        when(facadeProvider.getIfAvailable()).thenReturn(null);

        BrainStateService brainStateService = new BrainStateService(props, writer, facadeProvider);
        GraphRagChunkingService chunkingService = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                text -> List.of("Alpha", "Beta", "coastal route"),
                writer,
                new UniversalContextLexicon(),
                mock(VectorStoreService.class),
                brainStateService,
                mock(ChatMessageRepository.class));
        GraphRagThumbnailBridge bridge = new GraphRagThumbnailBridge(chunkingService);

        bridge.ingestNow(new UawThumbnailPersistedEvent(
                "UAW_thumbnail.v1",
                "UAW_THUMB",
                "THUMBNAIL",
                "Alpha and Beta are related in the coastal route context.",
                List.of("Alpha", "Beta", "coastal route"),
                0.82d));

        MockMvc mvc = standaloneSetup(new BrainStateDiagnosticsController(brainStateService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();

        mvc.perform(get("/api/diagnostics/rag/brain-state/snapshot/__UAW_THUMBNAIL__"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(BrainStateText.hash12("__UAW_THUMBNAIL__")))
                .andExpect(jsonPath("$.totalChunks").value(3))
                .andExpect(jsonPath("$.domainSummaries[0].domain").value("UAW_THUMB"))
                .andExpect(jsonPath("$.domainSummaries[0].entityCount").value(3))
                .andExpect(jsonPath("$.sourceText").doesNotExist())
                .andExpect(content().string(not(containsString("__UAW_THUMBNAIL__"))))
                .andExpect(content().string(not(containsString("Alpha and Beta are related"))));

        mvc.perform(get("/api/diagnostics/rag/brain-state/entities/UAW_THUMB").param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.items[0].domain").value("UAW_THUMB"))
                .andExpect(jsonPath("$.items[0].name").exists())
                .andExpect(content().string(not(containsString("coastal route context"))));
    }

    @Test
    void thumbnailWarmupFlowsIntoKgAxisAndPromptContext() {
        com.example.lms.search.TraceStore.clear();
        BrainStateProperties props = new BrainStateProperties();
        Neo4jKgChunkWriter writer = mock(Neo4jKgChunkWriter.class);
        when(writer.disabledReason()).thenReturn("disabled");
        when(writer.status()).thenReturn(Map.of("enabled", false, "disabledReason", "disabled"));
        when(writer.writeChunks(anyList())).thenReturn(Neo4jKgChunkWriter.WriteReport.disabled("disabled"));
        @SuppressWarnings("unchecked")
        ObjectProvider<RagOrchestratorFacade> facadeProvider = mock(ObjectProvider.class);
        when(facadeProvider.getIfAvailable()).thenReturn(null);

        BrainStateService brainStateService = new BrainStateService(props, writer, facadeProvider);
        GraphRagChunkingService chunkingService = new GraphRagChunkingService(
                props,
                new DocumentChunkingService(),
                text -> List.of("Alpha", "Gamma"),
                writer,
                new UniversalContextLexicon(),
                mock(VectorStoreService.class),
                brainStateService,
                mock(ChatMessageRepository.class));
        new GraphRagThumbnailBridge(chunkingService).ingestNow(new UawThumbnailPersistedEvent(
                "UAW_thumbnail.v1",
                "UAW_THUMB",
                "THUMBNAIL",
                "Alpha and Gamma are the core relationship anchors for this thumbnail.",
                List.of("Alpha", "Gamma"),
                0.82d));

        String queryText = "Alpha Gamma relationship sensitive";
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.inferDomain(queryText)).thenReturn("UAW_THUMB");
        when(kb.findMentionedEntities("UAW_THUMB", queryText)).thenReturn(Set.of());
        @SuppressWarnings("unchecked")
        ObjectProvider<BrainStateService> brainStateProvider = mock(ObjectProvider.class);
        when(brainStateProvider.getIfAvailable()).thenReturn(brainStateService);
        KnowledgeGraphHandler kgHandler = new KnowledgeGraphHandler(
                kb,
                null,
                new KgTailPowerMeanScorer(false, 2.0, 0.25),
                new SparseNodeInferenceService(),
                brainStateProvider);
        UnifiedRagOrchestrator orchestrator = new UnifiedRagOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "kgRetriever", kgHandler);

        UnifiedRagOrchestrator.QueryRequest request = new UnifiedRagOrchestrator.QueryRequest();
        request.query = queryText;
        request.topK = 1;
        request.kgTopK = 2;
        request.useWeb = false;
        request.useVector = false;
        request.useKg = true;
        request.useBm25 = false;
        request.enableBiEncoder = false;
        request.enableDiversity = false;
        request.enableOnnx = false;

        UnifiedRagOrchestrator.QueryResponse response = orchestrator.query(request);

        assertEquals(1, response.results.size());
        UnifiedRagOrchestrator.Doc selected = response.results.get(0);
        assertEquals("breadcrumb", selected.meta.get("kg_relation_prompt_context_layer"));
        assertTrue(selected.snippet.startsWith("relationThumbnailContext:"));
        assertTrue(selected.snippet.contains("Alpha"));
        assertTrue(selected.snippet.contains("Gamma"));
        assertFalse(selected.snippet.contains("core relationship anchors"));
        @SuppressWarnings("unchecked")
        Map<String, Object> kgAxis = (Map<String, Object>) response.debug.get("rag.eval.kgAxis");
        assertEquals(2, kgAxis.get("uawRelationThumbnailInputAnchorCount"));
        assertEquals(2, kgAxis.get("uawRelationThumbnailSelectedAnchorCount"));
        assertEquals(1, kgAxis.get("uawRelationThumbnailEmittedPairCount"));
        assertEquals(false, kgAxis.get("uawRelationThumbnailSliced"));
        assertEquals(1, kgAxis.get("relationThumbnailSelectedCount"));
        assertEquals("applied", kgAxis.get("relationThumbnailStatus"));
        assertEquals(1, com.example.lms.search.TraceStore.get("rag.eval.kgAxis.uawRelationThumbnailEmittedPairCount"));
        assertFalse(String.valueOf(response.debug).contains(queryText));
        com.example.lms.search.TraceStore.clear();
    }
}
