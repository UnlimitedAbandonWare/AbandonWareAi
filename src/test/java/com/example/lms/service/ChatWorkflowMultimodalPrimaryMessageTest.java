package com.example.lms.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.example.lms.dto.ChatRequestDto;
import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.service.rag.plan.PlanModelResolver;

import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;

class ChatWorkflowMultimodalPrimaryMessageTest {

    @Test
    void textOnlyAndNonEnforcedImageRequestsKeepTheLegacySingleTextMessage() {
        UserMessage textOnly = ChatWorkflow.primaryUserMessage(
                "question",
                ChatRequestDto.builder().message("question").build(),
                frame(ConversationFrameV1.Mode.ENFORCE, false));
        UserMessage shadowImage = ChatWorkflow.primaryUserMessage(
                "question",
                ChatRequestDto.builder()
                        .message("question")
                        .imageBase64("AA==")
                        .imageMediaType("image/png")
                        .build(),
                frame(ConversationFrameV1.Mode.SHADOW, true));

        assertEquals(1, textOnly.contents().size());
        assertTrue(textOnly.contents().get(0) instanceof TextContent);
        assertEquals("question", ((TextContent) textOnly.contents().get(0)).text());
        assertEquals(1, shadowImage.contents().size());
        assertTrue(shadowImage.contents().get(0) instanceof TextContent);
    }

    @Test
    void enforcedImageRequestBuildsOneTextAndOnePrimaryImageContent() {
        UserMessage message = ChatWorkflow.primaryUserMessage(
                "describe",
                ChatRequestDto.builder()
                        .message("describe")
                        .imageBase64("AA==")
                        .imageMediaType("image/jpeg")
                        .build(),
                frame(ConversationFrameV1.Mode.ENFORCE, true));

        assertEquals(2, message.contents().size());
        assertTrue(message.contents().get(0) instanceof TextContent);
        assertTrue(message.contents().get(1) instanceof ImageContent);
        assertEquals("describe", ((TextContent) message.contents().get(0)).text());
        ImageContent image = (ImageContent) message.contents().get(1);
        assertEquals("AA==", image.image().base64Data());
        assertEquals("image/jpeg", image.image().mimeType());
        assertNull(image.image().url());
    }

    @Test
    void verifiedVisionModelRequiresTheDedicatedConfiguredRoute() {
        MockEnvironment configured = new MockEnvironment()
                .withProperty("llm.vision.model", "qwen3-vl:8b")
                .withProperty("llm.fast.model", "text-fast:8b");
        PlanModelResolver configuredResolver = new PlanModelResolver(configured);

        assertEquals(
                Optional.of("qwen3-vl:8b"),
                ChatWorkflow.verifiedVisionModel(
                        configuredResolver,
                        configured.getProperty("llm.vision.model")));

        MockEnvironment textFallbackOnly = new MockEnvironment()
                .withProperty("llm.fast.model", "text-fast:8b");
        assertTrue(ChatWorkflow.verifiedVisionModel(
                new PlanModelResolver(textFallbackOnly),
                textFallbackOnly.getProperty("llm.vision.model")).isEmpty());
        assertTrue(ChatWorkflow.verifiedVisionModel(configuredResolver, "${LLM_VISION_MODEL}").isEmpty());
        assertTrue(ChatWorkflow.verifiedVisionModel(configuredResolver, "llmrouter.vision").isEmpty());
        assertTrue(ChatWorkflow.verifiedVisionModel(null, "qwen3-vl:8b").isEmpty());
    }

    private static ConversationFrameV1 frame(ConversationFrameV1.Mode mode, boolean imagePresent) {
        return new ConversationFrameV1(
                mode,
                ConversationFrameV1.Stance.STANDARD,
                ConversationFrameV1.LightweightRole.OBSERVE_ONLY,
                imagePresent,
                false,
                false,
                ConversationFrameV1.ReasonCode.DEFAULT);
    }
}
