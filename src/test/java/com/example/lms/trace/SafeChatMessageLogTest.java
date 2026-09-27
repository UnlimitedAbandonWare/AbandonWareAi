package com.example.lms.trace;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.lms.api.PublicRequestBudgetGuard;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.guard.ConversationFrameV1;
import com.example.lms.search.TraceStore;
import com.example.lms.telemetry.MlaBreadcrumb;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SafeChatMessageLogTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void multimodalDraftLogAndTextMetricUseOnlyBoundedSafeSummary() {
        SentinelImage sentinel = sentinelImage();
        List<ChatMessage> messages = List.of(
                SystemMessage.from("system"),
                UserMessage.from(
                        TextContent.from("describe"),
                        ImageContent.from(sentinel.encoded(), "image/png")));

        SafeChatMessageLog.Summary summary = SafeChatMessageLog.summarize(messages);
        assertEquals(List.of("SYSTEM_TEXT", "TEXT", "IMAGE"), summary.contentTypes());
        assertTrue(summary.imagePresent());
        assertEquals(sentinel.decodedBytes(), summary.decodedImageBytes());
        assertEquals(SafeChatMessageLog.ImageMediaType.PNG, summary.imageMediaType());
        assertEquals("system".length() + "describe".length(),
                SafeChatMessageLog.safeTextChars(messages));

        Logger logger = (Logger) LoggerFactory.getLogger("test.multimodal.safe-draft-log");
        Level previousLevel = logger.getLevel();
        boolean previousAdditive = logger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.TRACE);
        logger.setAdditive(false);
        try {
            SafeChatMessageLog.traceDraft(logger, messages);
            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("contentTypes=[SYSTEM_TEXT, TEXT, IMAGE]"));
            assertTrue(logged.contains("imagePresent=true"));
            assertAbsent(logged, sentinel);
            assertAbsent(String.valueOf(summary), sentinel);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            logger.setAdditive(previousAdditive);
        }
    }

    @Test
    void requestTelemetryBreadcrumbAndRejectionNeverRetainBase64Sentinel() {
        SentinelImage sentinel = sentinelImage();
        PublicRequestBudgetGuard guard = new PublicRequestBudgetGuard();
        ChatRequestDto request = ChatRequestDto.builder()
                .message("describe")
                .imageBase64(sentinel.encoded())
                .imageMediaType("image/png")
                .build();

        guard.validateChat(request);
        MlaBreadcrumb.appendConversationFrameTransition(new ConversationFrameV1(
                ConversationFrameV1.Mode.ENFORCE,
                ConversationFrameV1.Stance.STANDARD,
                ConversationFrameV1.LightweightRole.OBSERVE_ONLY,
                true,
                false,
                false,
                ConversationFrameV1.ReasonCode.DEFAULT));

        String retained = String.valueOf(TraceStore.getAll());
        assertAbsent(retained, sentinel);
        PublicRequestBudgetGuard.Rejection rejection = assertThrows(
                PublicRequestBudgetGuard.Rejection.class,
                () -> guard.validateChat(request.toBuilder().imageMediaType("image/jpeg").build()));
        assertEquals("image_signature_mismatch", rejection.reasonCode());
        assertAbsent(rejection.toString(), sentinel);
        assertAbsent(rejection.getMessage(), sentinel);
    }

    @Test
    void activeDraftOwnersDoNotLogMessageObjectsAndWorkflowDoesNotCallMessageToString() throws Exception {
        List<Path> directLogOwners = List.of(
                Path.of("main/java/com/example/lms/service/ChatOrchestrator.java"),
                Path.of("main/java/com/example/lms/service/legacy/ChatServiceLegacy.java"),
                Path.of("main/java/com/example/lms/service/patch/ChatServiceLegacyPatch.java"),
                Path.of("main/java/com/example/lms/service/patch/ChatOrchestratorPatch.java"));
        for (Path path : directLogOwners) {
            String source = Files.readString(path, StandardCharsets.UTF_8);
            assertFalse(source.contains("final messages for draft → {}\", msgs"));
        }
        String workflow = Files.readString(
                Path.of("main/java/com/example/lms/service/ChatWorkflow.java"),
                StandardCharsets.UTF_8);
        assertFalse(workflow.contains("String.valueOf(msg).length()"));
        String tracker = Files.readString(
                Path.of("main/java/com/example/lms/llm/ModelRuntimeHealthTracker.java"),
                StandardCharsets.UTF_8);
        assertFalse(tracker.contains("String.valueOf(message)"));
    }

    private static void assertAbsent(String output, SentinelImage sentinel) {
        assertFalse(output.contains(sentinel.encoded()));
        assertFalse(output.contains(sentinel.fragment()));
        assertFalse(output.contains(sentinel.marker()));
    }

    private static SentinelImage sentinelImage() {
        byte[] marker = ("awx-multimodal-boundary-" + "7f3c91d2")
                .getBytes(StandardCharsets.UTF_8);
        byte[] png = new byte[8 + marker.length];
        byte[] signature = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        System.arraycopy(signature, 0, png, 0, signature.length);
        System.arraycopy(marker, 0, png, signature.length, marker.length);
        String encoded = Base64.getEncoder().encodeToString(png);
        int fragmentStart = Math.max(0, encoded.length() / 3);
        String fragment = encoded.substring(fragmentStart, Math.min(encoded.length(), fragmentStart + 18));
        return new SentinelImage(encoded, fragment, new String(marker, StandardCharsets.UTF_8), png.length);
    }

    private record SentinelImage(String encoded, String fragment, String marker, int decodedBytes) {
    }
}
