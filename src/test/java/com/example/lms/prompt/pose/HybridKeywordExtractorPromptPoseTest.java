package com.example.lms.prompt.pose;

import com.example.lms.search.TraceStore;
import com.example.lms.search.extract.HybridKeywordExtractor;
import com.example.lms.service.QueryAugmentationService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import com.example.lms.service.subject.SubjectResolver;
import com.example.lms.transform.QueryTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridKeywordExtractorPromptPoseTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void promptPoseQuotedSeedsBecomeCandidatesWithoutLlmCallOrRawTrace() {
        QueryAugmentationService augmentationService = mock(QueryAugmentationService.class);
        QueryTransformer transformer = mock(QueryTransformer.class);
        SubjectResolver subjectResolver = mock(SubjectResolver.class);
        KnowledgeBaseService knowledgeBase = mock(KnowledgeBaseService.class);
        when(augmentationService.augment(anyString())).thenReturn(List.of("root rule"));
        when(subjectResolver.resolve(anyString(), any())).thenReturn(Optional.empty());

        HybridKeywordExtractor extractor = new HybridKeywordExtractor(
                augmentationService, transformer, subjectResolver, knowledgeBase);
        ReflectionTestUtils.setField(extractor, "extractorMode", "RULE");
        String supabaseSeed = "sb_secret_" + "keyword001";

        List<String> out = extractor.extract("root query",
                "PromptPose search seed hints:\n"
                        + "- \"official docs seed\"\n"
                        + "- \"Authorization: Bearer " + "sk-secretsecret\"\n"
                        + "- \"" + supabaseSeed + "\"\n",
                null, "GENERAL", 6, 0.80d);

        assertTrue(out.contains("official docs seed"));
        assertFalse(out.stream().anyMatch(value -> value.contains("Authorization")));
        assertFalse(out.stream().anyMatch(value -> value.contains(supabaseSeed)));
        verify(transformer, never()).transformEnhanced(anyString(), any(), any());
        assertFalse(TraceStore.getAll().values().stream()
                .anyMatch(value -> String.valueOf(value).contains("official docs seed")
                        || String.valueOf(value).contains("sk-secretsecret")));
    }

    @Test
    void extractorModeParserOnlyCatchesIllegalArgumentException() throws Exception {
        String source = Files.readString(Path.of(
                "main/java/com/example/lms/search/extract/HybridKeywordExtractor.java"));
        String parserCall = "configured = QueryExtractionMode.valueOf(";
        int parse = source.indexOf(parserCall);

        assertTrue(parse >= 0, "extractor mode parser should remain visible");
        String window = source.substring(parse, Math.min(source.length(), parse + 260));
        assertFalse(window.contains("catch (Exception"),
                "extractor mode parser must not swallow every Exception");
        assertFalse(window.contains("catch (Throwable"),
                "extractor mode parser must not swallow Throwable");
        assertTrue(window.contains("catch (IllegalArgumentException"),
                "extractor mode parser should only catch IllegalArgumentException");
    }
}
