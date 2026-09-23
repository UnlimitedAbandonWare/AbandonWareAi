package com.example.lms.service.rag.chain;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.dto.ImageTask;
import com.example.lms.image.GroundedImagePromptBuilder;
import com.example.lms.prompt.PromptContext;
import com.example.lms.search.TraceStore;
import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ImagePromptGroundingHandlerTest {

    @Test
    void builderFailureFallsBackToRawPromptAndLeavesRedactedBreadcrumb() {
        GroundedImagePromptBuilder builder = new GroundedImagePromptBuilder(null) {
            @Override
            public ImageTask buildTask(String rawQuery, PromptContext ctx) {
                throw new IllegalStateException("raw image secret");
            }
        };
        ImagePromptGroundingHandler handler = new ImagePromptGroundingHandler(builder);
        RecordingContext ctx = new RecordingContext("/img draw a lighthouse");

        TraceStore.clear();
        ChainOutcome outcome = handler.handle(ctx, next -> ChainOutcome.SUCCESS_PASS);

        assertEquals(ChainOutcome.SUCCESS_PASS, outcome);
        assertEquals("/img draw a lighthouse", ctx.meta.get("image.prompt"));
        assertEquals(true, TraceStore.get("rag.imagePromptGrounding.suppressed.groundPrompt"));
        assertEquals("IllegalStateException",
                TraceStore.get("rag.imagePromptGrounding.groundPrompt.errorType"));
        assertFalse(TraceStore.getAll().toString().contains("raw image secret"));
    }

    private static final class RecordingContext implements ChainContext {
        private final String message;
        private final Map<String, String> meta = new HashMap<>();

        private RecordingContext(String message) {
            this.message = message;
        }

        @Override
        public String sessionId() {
            return "sid";
        }

        @Override
        public String userId() {
            return "user";
        }

        @Override
        public String userMessage() {
            return message;
        }

        @Override
        public PromptContext promptContext() {
            return null;
        }

        @Override
        public ChainContext withSystemNote(String note) {
            return this;
        }

        @Override
        public ChainContext withAssistantSideNote(String note) {
            return this;
        }

        @Override
        public ChainContext withAttachment(AttachmentDto att) {
            return this;
        }

        @Override
        public ChainContext withLocalDocs(List<Document> docs) {
            return this;
        }

        @Override
        public ChainContext putMeta(String key, String value) {
            meta.put(key, value);
            return this;
        }

        @Override
        public void emitAssistant(String text) {
        }
    }
}
