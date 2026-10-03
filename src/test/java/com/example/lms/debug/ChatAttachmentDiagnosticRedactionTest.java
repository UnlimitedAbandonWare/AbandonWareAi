package com.example.lms.debug;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChatAttachmentDiagnosticRedactionTest {
    @Test void attachmentCountsFlagsAndFixedReasonsRemainReadableWithoutSourceContent() {
        var store=new DebugEventStore();
        ReflectionTestUtils.setField(store,"enabled",true);
        ReflectionTestUtils.setField(store,"maxSize",20);
        ReflectionTestUtils.setField(store,"windowMs",60000L);
        ReflectionTestUtils.setField(store,"maxPerWindow",20L);
        ReflectionTestUtils.setField(store,"flushIntervalMs",15000L);
        store.emit(DebugProbeType.PROMPT,DebugEventLevel.INFO,"prompt.built",
            "fixed prompt boundary","ChatWorkflow.promptBuild",
            Map.of("attachmentIdCount",1,"attachmentOwnerBound",true,
                "attachment.sessionFilter.allowedCount",0,
                "attachment.extraction.skippedReason","content_digest_mismatch",
                "attachmentId","private-synthetic-id",
                "sourceText","private-synthetic-document-body"),null);
        var event=store.list(1).get(0);
        assertEquals(1,event.data().get("attachmentIdCount"));
        assertEquals(true,event.data().get("attachmentOwnerBound"));
        assertEquals(0,event.data().get("attachment.sessionFilter.allowedCount"));
        assertEquals("content_digest_mismatch",event.data().get("attachment.extraction.skippedReason"));
        assertFalse(event.toString().contains("private-synthetic-id"));
        assertFalse(event.toString().contains("private-synthetic-document-body"));
        com.example.lms.search.TraceStore.clear();
    }
}
