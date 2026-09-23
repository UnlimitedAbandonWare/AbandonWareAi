package com.example.lms.service.ner;

import com.example.lms.prompt.PromptBuilder;
import com.example.lms.service.correction.DomainTermDictionary;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class LLMNamedEntityExtractorTest {

    @Test
    void llmCanBeDisabledForDeterministicGraphRagSmoke() {
        ChatModel chatModel = mock(ChatModel.class);
        PromptBuilder promptBuilder = mock(PromptBuilder.class);
        DomainTermDictionary dictionary = text -> Set.of("Alpha", "Beta");
        LLMNamedEntityExtractor extractor = new LLMNamedEntityExtractor(chatModel, dictionary, promptBuilder);
        ReflectionTestUtils.setField(extractor, "llmEnabled", false);

        var entities = extractor.extract("Alpha and Beta appear in the smoke fixture.");

        assertTrue(entities.contains("Alpha"));
        assertTrue(entities.contains("Beta"));
        verifyNoInteractions(chatModel, promptBuilder);
    }

    @Test
    void llmNamedEntityPromptCallsiteUsesStageSpecificPromptName() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/ner/LLMNamedEntityExtractor.java"),
                StandardCharsets.UTF_8);

        assertFalse(source.contains("String prompt ="));
        assertFalse(source.contains("UserMessage.from(prompt)"));
        assertTrue(source.contains("String namedEntityExtractionPrompt = promptBuilder.build(ctx);"));
        assertTrue(source.contains("UserMessage.from(namedEntityExtractionPrompt)"));
    }
}
