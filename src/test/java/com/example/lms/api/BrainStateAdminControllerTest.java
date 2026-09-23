package com.example.lms.api;

import com.example.lms.service.rag.graph.BrainStateService;
import com.example.lms.service.rag.graph.GraphRagChunkingService;
import com.example.lms.service.rag.graph.InferenceResult;
import com.example.lms.file.FileIngestionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class BrainStateAdminControllerTest {

    @Test
    void ingestEndpointDelegatesWithoutEchoingRawText() throws Exception {
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(chunking.ingestText(eq("s1"), eq("raw secret prompt"), eq("MANUAL"), eq("GENERAL")))
                .thenReturn(new GraphRagChunkingService.IngestReport(
                        true, "s1", "indexed", 1, 2, 1, 0, "hash-only", "", Map.of()));
        MockMvc mvc = mvc(chunking, brain);

        String body = mvc.perform(post("/api/admin/vector/brain/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\",\"text\":\"raw secret prompt\",\"domain\":\"GENERAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.chunkCount").value(1))
                .andExpect(jsonPath("$.textHash").value("hash-only"))
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.sourceText").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertFalse(body.contains("\"sessionId\":\"s1\""));

        verify(chunking).ingestText("s1", "raw secret prompt", "MANUAL", "GENERAL");
    }

    @Test
    void statusAndInferExposeBoundedShapes() throws Exception {
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        BrainStateService brain = mock(BrainStateService.class);
        when(brain.status()).thenReturn(Map.of("enabled", true, "chunkCount", 1));
        when(brain.querySparseInference("Alpha?", null)).thenReturn(new InferenceResult(
                true, "123456789abc", "local-brain-state-v1", List.of("Alpha"),
                List.of("Alpha -CO_MENTIONED_WITH-> Beta (1)"), Map.of("available", false),
                "", Instant.parse("2026-01-01T00:00:00Z")));
        when(brain.querySparseInferenceLocalOnly("Alpha?", "GENERAL")).thenReturn(new InferenceResult(
                true, "abcdef123456", "local-brain-state-local-only-v1", List.of("Alpha"),
                List.of("Alpha -CO_MENTIONED_WITH-> Beta (1)"),
                Map.of("available", false, "sparseNode.domain", "GENERAL"),
                "", Instant.parse("2026-01-01T00:00:00Z")));
        MockMvc mvc = mvc(chunking, brain);

        mvc.perform(get("/api/admin/vector/brain/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.chunkCount").value(1));

        mvc.perform(post("/api/admin/vector/brain/infer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Alpha?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryHash").value("123456789abc"))
                .andExpect(jsonPath("$.matchedEntities[0]").value("Alpha"))
                .andExpect(jsonPath("$.query").doesNotExist());

        mvc.perform(post("/api/admin/vector/brain/infer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Alpha?\",\"domain\":\"GENERAL\",\"localOnly\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryHash").value("abcdef123456"))
                .andExpect(jsonPath("$.mode").value("local-brain-state-local-only-v1"))
                .andExpect(jsonPath("$.ragDebug['sparseNode.domain']").value("GENERAL"))
                .andExpect(jsonPath("$.query").doesNotExist());

        verify(brain).querySparseInference("Alpha?", null);
        verify(brain).querySparseInferenceLocalOnly("Alpha?", "GENERAL");
    }

    @Test
    void ingestFileEndpointExtractsTextAndDoesNotEchoFileBytes() throws Exception {
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        BrainStateService brain = mock(BrainStateService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        byte[] bytes = "raw file bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(files.extractText(eq("note.txt"), eq("text/plain"), any(byte[].class))).thenReturn("secret file text");
        when(chunking.ingestText(eq("s1"), eq("secret file text"), eq("MANUAL"), eq("GENERAL")))
                .thenReturn(new GraphRagChunkingService.IngestReport(
                        true, "s1", "indexed", 1, 1, 0, 0, "hash-only", "", Map.of()));
        MockMvc mvc = mvc(chunking, brain, files);

        mvc.perform(multipart("/api/admin/vector/brain/ingest-file")
                        .file(new MockMultipartFile("file", "note.txt", "text/plain", bytes))
                        .param("sessionId", "s1")
                        .param("domain", "GENERAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("indexed"))
                .andExpect(jsonPath("$.textHash").value("hash-only"))
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.sourceText").doesNotExist());

        verify(chunking).ingestText("s1", "secret file text", "MANUAL", "GENERAL");
    }

    @Test
    void ingestFileEndpointRejectsUnsupportedFileWithBoundedReport() throws Exception {
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        BrainStateService brain = mock(BrainStateService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        byte[] bytes = new byte[] {1, 2, 3};
        when(files.extractText(eq("blob.bin"), eq("application/octet-stream"), any(byte[].class))).thenReturn(null);
        MockMvc mvc = mvc(chunking, brain, files);

        mvc.perform(multipart("/api/admin/vector/brain/ingest-file")
                        .file(new MockMultipartFile("file", "blob.bin", "application/octet-stream", bytes))
                        .param("sessionId", "s1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("disabled"))
                .andExpect(jsonPath("$.disabledReason").value("unsupported_or_empty_file"))
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.sourceText").doesNotExist());
    }

    @Test
    void ingestFileEndpointFailureUsesStableDisabledReason() throws Exception {
        GraphRagChunkingService chunking = mock(GraphRagChunkingService.class);
        BrainStateService brain = mock(BrainStateService.class);
        FileIngestionService files = mock(FileIngestionService.class);
        byte[] bytes = "raw private file bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(files.extractText(eq("secret.txt"), eq("text/plain"), any(byte[].class)))
                .thenThrow(new RuntimeException("extract failed ownerToken=fake-token"));
        MockMvc mvc = mvc(chunking, brain, files);

        mvc.perform(multipart("/api/admin/vector/brain/ingest-file")
                        .file(new MockMultipartFile("file", "secret.txt", "text/plain", bytes))
                        .param("sessionId", "s1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("disabled"))
                .andExpect(jsonPath("$.disabledReason").value("file_ingestion_failed"))
                .andExpect(jsonPath("$.disabledReason").value(org.hamcrest.Matchers.not("RuntimeException")))
                .andExpect(jsonPath("$.text").doesNotExist())
                .andExpect(jsonPath("$.sourceText").doesNotExist());
    }

    private static MockMvc mvc(GraphRagChunkingService chunking, BrainStateService brain) {
        return mvc(chunking, brain, null);
    }

    private static MockMvc mvc(GraphRagChunkingService chunking, BrainStateService brain, FileIngestionService files) {
        return standaloneSetup(new BrainStateAdminController(chunking, brain, files))
                .setMessageConverters(new MappingJackson2HttpMessageConverter())
                .build();
    }
}
