package ai.abandonware.nova.orch;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrchestrationHarmonyContractTest {

    @Test
    void legacyRetrievalOrderServiceStaysPurged() {
        assertFalse(Files.exists(Path.of("main/java/com/abandonware/ai/strategy/RetrievalOrderService.java")),
                "Legacy retrieval-order alias must stay purged");
    }

    @Test
    void legacyExtremeZAliasesStayPurged() {
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/extreme/ExtremeZSystemHandler.java")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/extreme/Narrower.java")));
        assertFalse(Files.exists(Path.of("main/java/com/example/lms/nova/extremez/ExtremeZSystemHandler.java")));
    }

    @Test
    void legacyAngerOverdriveAliasesStayPurgedWhileCanonicalServiceIsUsed() throws Exception {
        String aspect = read("main/java/ai/abandonware/nova/orch/aop/RagCompressionAspect.java");
        String autoConfig = read("main/java/ai/abandonware/nova/autoconfig/NovaOrchestrationAutoConfiguration.java");

        assertFalse(Files.exists(Path.of("main/java/com/example/lms/nova/overdrive/AngerOverdriveNarrower.java")));
        assertFalse(Files.exists(Path.of("main/java/com/nova/protocol/rag/narrow/AngerOverdriveNarrower.java")));
        assertTrue(aspect.contains("import com.example.lms.service.rag.overdrive.AngerOverdriveNarrower;"));
        assertFalse(aspect.contains("import com.example.lms.nova.overdrive.AngerOverdriveNarrower;"));
        assertFalse(aspect.contains("import com.nova.protocol.rag.narrow.AngerOverdriveNarrower;"));
        assertTrue(autoConfig.contains("ObjectProvider<AngerOverdriveNarrower> overdriveNarrowerProvider"));
    }

    @Test
    void legacyDppAliasesStayPurged() throws Exception {
        String canonical = read("main/java/com/example/lms/service/rag/rerank/DppDiversityReranker.java");

        assertFalse(Files.exists(Path.of("main/java/com/abandonware/ai/service/rag/handler/DppDiversityReranker.java")));
        assertFalse(Files.exists(Path.of("main/java/com/abandonware/ai/service/rag/rerank/DppDiversityReranker.java")));
        assertTrue(canonical.contains("@Component"));
    }

    @Test
    void legacyRetrievalOrderAndLocalLlmManagersStayPurgedWhileCanonicalOwnersRemain() throws Exception {
        String canonicalOrder = read("main/java/com/example/lms/strategy/RetrievalOrderService.java");
        String canonicalLocalLlm = read("main/java/com/example/lms/config/LocalLlmProcessManager.java");

        assertFalse(Files.exists(Path.of("main/java/com/abandonware/ai/strategy/RetrievalOrderService.java")),
                "Legacy RetrievalOrderService alias must stay purged");
        assertTrue(canonicalOrder.contains("@Service"));

        assertFalse(Files.exists(Path.of("main/java/com/abandonware/ai/agent/config/LocalLlmProcessManager.java")),
                "Legacy LocalLlmProcessManager alias must stay purged");
        assertTrue(canonicalLocalLlm.contains("@Component"));
    }

    @Test
    void artPlateEvolverRemainsSpringManagedAndOptionalAtSelectorBoundary() throws Exception {
        String evolver = read("main/java/com/example/lms/artplate/ArtPlateEvolver.java");
        String selector = read("main/java/com/example/lms/moe/RgbStrategySelector.java");

        assertTrue(evolver.contains("@Component"));
        assertTrue(selector.contains("ObjectProvider<ArtPlateEvolver> artPlateEvolverProvider"));
        assertTrue(selector.contains("moe.artplate.evolver.available"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    private static boolean hasSpringStereotype(String source) {
        return source.contains("@Component")
                || source.contains("@Service")
                || source.contains("@Configuration")
                || source.contains("@Repository");
    }
}
