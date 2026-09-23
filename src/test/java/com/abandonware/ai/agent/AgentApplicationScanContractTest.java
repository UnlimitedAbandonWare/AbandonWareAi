package com.abandonware.ai.agent;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentApplicationScanContractTest {

    @Test
    void agentScanUsesCanonicalLocalLlmProcessManagerOnly() throws Exception {
        String application = read("main/java/com/abandonware/ai/agent/AgentApplication.java");
        String canonical = read("main/java/com/example/lms/config/LocalLlmProcessManager.java");

        assertTrue(application.contains("\"com.abandonware.ai.agent\""));
        assertTrue(application.contains("\"com.example.lms\""));
        assertTrue(canonical.contains("@Component"));
        assertFalse(Files.exists(Path.of("main/java/com/abandonware/ai/agent/config/LocalLlmProcessManager.java")),
                "Legacy LocalLlmProcessManager alias should stay purged");
    }

    @Test
    void retrievalOrderServiceCanonicalPackageIsOnlySpringManagedOwner() throws Exception {
        String canonical = read("main/java/com/example/lms/strategy/RetrievalOrderService.java");
        String dynamicChain = read("main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java");

        assertTrue(canonical.contains("@Service"));
        assertFalse(Files.exists(Path.of("main/java/com/abandonware/ai/strategy/RetrievalOrderService.java")),
                "Legacy RetrievalOrderService alias should stay purged");
        assertTrue(dynamicChain.contains("import com.example.lms.strategy.RetrievalOrderService;"));
        assertTrue(dynamicChain.contains("decideOrder(q)"),
                "Dynamic retrieval must route through the canonical RetrievalOrderService decision path");
        assertFalse(dynamicChain.contains("import com.abandonware.ai.strategy.RetrievalOrderService;"),
                "Active retrieval chain must not depend on the legacy compatibility RetrievalOrderService alias");
    }

    @Test
    void abandonwareRagCompatibilityAdaptersAreNotSpringManaged() throws Exception {
        for (Path path : List.of(
                Path.of("main/java/com/abandonware/ai/service/rag/AnalyzeWebSearchRetriever.java"),
                Path.of("main/java/com/abandonware/ai/service/rag/BiEncoderReranker.java"),
                Path.of("main/java/com/abandonware/ai/service/rag/RerankOrchestrator.java"),
                Path.of("main/java/com/abandonware/ai/service/rag/handler/DynamicRetrievalHandlerChain.java"),
                Path.of("main/java/com/abandonware/ai/service/rag/handler/KnowledgeGraphHandler.java"))) {
            String source = read(path.toString());
            assertFalse(source.contains("@Service"), path + " must not be a Spring service");
            assertFalse(source.contains("@Component"), path + " must not be a Spring component");
            assertFalse(source.contains("@Primary"), path + " must not declare Spring primary ownership");
        }
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
