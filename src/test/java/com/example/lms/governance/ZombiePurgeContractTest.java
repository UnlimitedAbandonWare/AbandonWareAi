package com.example.lms.governance;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZombiePurgeContractTest {

    @Test
    void highConfidenceZombieSourcesStayPurged() {
        for (Path path : List.of(
                Path.of("main/java/com/example/lms/service/rag/genshin/GenshinElement.java"),
                Path.of("main/java/com/example/lms/service/rag/genshin/ElementLexicon.java"),
                Path.of("main/java/com/example/lms/service/rag/genshin/GenshinElementLexicon.java"),
                Path.of("main/java/com/example/lms/flow/FallbackRetrieveTool.java"),
                Path.of("main/java/com/example/lms/flow/OutboxSendTool.java"),
                Path.of("main/java/com/example/lms/cfvm/ToyMatcher.java"),
                Path.of("main/java/com/example/lms/service/rag/selfask/HeuristicSubQuestionGenerator.java"),
                Path.of("main/java/com/example/lms/service/rag/selfask/Branch.java"),
                Path.of("main/java/com/example/lms/service/rag/selfask/SelfAskPlanner.java"),
                Path.of("main/java/com/example/lms/service/rag/selfask/SelfAskPreprocessorHandler.java"),
                Path.of("main/java/com/example/lms/service/rag/selfask/SubQuestion.java"),
                Path.of("main/java/com/example/lms/service/rag/selfask/SubQuestionGenerator.java"),
                Path.of("main/java/com/example/lms/service/rag/selfask/SubQuestionType.java"),
                Path.of("main/java/com/example/lms/service/service/rag/fusion/MultiQueryMergeAdapter.java"),
                Path.of("main/java/com/example/lms/location/Formatters.java"),
                Path.of("main/java/com/example/lms/location/geo/Haversine.java"),
                Path.of("main/java/com/example/lms/location/route/DirectionsClient.java"),
                Path.of("main/java/com/example/lms/location/route/TmapDirectionsClient.java"),
                Path.of("main/java/com/example/lms/config/TmapProperties.java"),
                Path.of("main/java/com/acme/aicore/adapters/search/BingSearchProvider.java"),
                Path.of("main/java/com/example/lms/api/FileUploadController.java"),
                Path.of("main/java/com/acme/aicore/adapters/llm/GroqMiniChatAdapter.java"),
                Path.of("main/java/com/acme/aicore/adapters/memory/InMemoryMemoryAdapter.java"),
                Path.of("main/java/com/acme/aicore/adapters/prompt/DefaultPromptAdapter.java"),
                Path.of("main/java/com/acme/aicore/adapters/llm/SimpleChatModel.java"),
                Path.of("main/java/com/acme/aicore/app/ConversationService.java"),
                Path.of("main/java/com/acme/aicore/app/SessionManager.java"),
                Path.of("main/java/com/acme/aicore/common/RequestContextFilter.java"),
                Path.of("main/java/com/abandonware/ai/agent/config/LocalLlmProcessManager.java"),
                Path.of("main/java/com/abandonware/ai/strategy/RetrievalOrderService.java"),
                Path.of("main/java/com/example/lms/audio/AudioController.java"),
                Path.of("main/java/com/example/lms/audio/OpenAiSpeechService.java"),
                Path.of("main/java/com/example/lms/audio/OpenAiTranscriptionService.java"),
                Path.of("main/java/com/example/lms/service/EnhancedSearchService.java"),
                Path.of("main/java/com/example/lms/service/NotificationService.java"),
                Path.of("main/java/com/nova/protocol/rerank/Dpp.java"),
                Path.of("main/java/com/example/lms/nova/gate/CitationGate.java"),
                Path.of("main/java/com/example/lms/nova/gate/AutorunPreflightGate.java"),
                Path.of("main/java/com/example/lms/nova/overdrive/AngerOverdriveNarrower.java"),
                Path.of("main/java/com/example/lms/nova/extremez/ExtremeZSystemHandler.java"),
                Path.of("main/java/com/example/lms/extreme/ExtremeZSystemHandler.java"),
                Path.of("main/java/com/example/lms/extreme/OverdriveGuard.java"),
                Path.of("main/java/com/example/lms/extreme/Narrower.java"),
                Path.of("main/java/com/nova/protocol/rag/narrow/AngerOverdriveNarrower.java"),
                Path.of("main/java/com/abandonware/ai/service/rag/handler/DppDiversityReranker.java"),
                Path.of("main/java/com/example/lms/risk/rdi/NoOpRiskModelProvider.java"),
                Path.of("main/java/com/nova/protocol/fusion/ScoreCalibrator.java"),
                Path.of("main/java/com/nova/protocol/rag/explore/QueryBurstExpander.java"),
                Path.of("main/java/com/nova/protocol/rag/explore/ExtremeZHandler.java"),
                Path.of("main/java/com/example/lms/flow/FlowJoiner.java"),
                Path.of("main/java/com/example/lms/mpc/MpcPreprocessor.java"),
                Path.of("main/java/com/example/lms/mpc/NoopMpcPreprocessor.java"),
                Path.of("main/java/com/example/lms/service/rag/SimpleReranker.java"),
                Path.of("main/java/com/example/lms/service/rag/retriever/Bm25LocalRetriever.java"),
                Path.of("main/java/com/example/lms/service/rag/zsystem/TimeBudget.java"),
                Path.of("main/java/com/abandonware/ai/service/rag/rerank/DppDiversityReranker.java"),
                Path.of("main/java/com/example/lms/compare/api/ComparatorCalculator.java"),
                Path.of("main/java/com/example/lms/compare/hybrid/HybridComparator.java"),
                Path.of("main/java/com/example/lms/compare/registry/ComparatorRegistry.java"),
                Path.of("main/java/com/example/lms/tools/OrchOfflineReportCli.java"),
                Path.of("main/java/com/example/lms/infra/time/BudgetContext.java"),
                Path.of("main/java/com/example/lms/resilience/BudgetContext.java"),
                Path.of("main/java/com/example/lms/resilience/BudgetContextHolder.java"),
                Path.of("main/java/com/example/lms/resilience/CancellationToken.java"),
                Path.of("main/java/com/abandonware/ai/service/rag/handler/RetrievalStep.java"),
                Path.of("main/java/com/abandonware/ai/service/ocr/OcrHealthIndicator.java"),
                Path.of("main/java/com/nova/protocol/guard/AnswerSanitizerDecorator.java"),
                Path.of("main/java/com/nova/protocol/guard/DomainAccessPolicy.java"),
                Path.of("main/java/com/example/lms/service/rag/pre/DefaultQueryContextPreprocessor.java"),
                Path.of("main/java/com/example/lms/vector/TopicClassifier.java"))) {
            assertFalse(Files.exists(path), "zombie source should stay purged: " + path);
        }
    }

    @Test
    void deprecatedGenshinSanitizerCompatibilityClassStaysOutOfSpringScanning() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/guard/GenshinRecommendationSanitizer.java"));

        assertTrue(source.contains("@Deprecated"));
        assertTrue(source.contains("UniversalDomainSanitizer"));
        assertFalse(source.contains("@Component"),
                "Deprecated Genshin sanitizer wrapper must not stay Spring-managed");
    }

    @Test
    void deprecatedFileUploadRouteStaysPurgedFromSecuritySurfaces() throws Exception {
        String appSecurity = Files.readString(Path.of("main/java/com/example/lms/config/AppSecurityConfig.java"));
        String adminTokenGuard = Files.readString(Path.of("main/java/com/example/lms/security/AdminTokenGuardFilter.java"));

        assertFalse(appSecurity.contains("/deprecated/file-upload-controller"),
                "deprecated file upload route must not stay in Spring Security matchers");
        assertFalse(adminTokenGuard.contains("/deprecated/file-upload-controller"),
                "deprecated file upload route must not stay in the admin-token prefilter");
    }
}
