package com.example.lms.service.rag.guard;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.knowledge.KnowledgeBaseService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemoryAsEvidenceAdapterTest {

    @Test
    void sessionEvidenceUsesTypedChatHistoryMessages() {
        ChatHistoryService history = mock(ChatHistoryService.class);
        ChatSession session = new ChatSession("typed");
        session.setMessages(List.of(
                new ChatMessage(session, "user", "question"),
                new ChatMessage(session, "assistant", "first answer"),
                new ChatMessage(session, "assistant", "second answer")));
        when(history.getSessionWithMessages(42L)).thenReturn(session);

        MemoryAsEvidenceAdapter adapter = new MemoryAsEvidenceAdapter(history);

        assertEquals(List.of("second answer"), adapter.fromSession(42L, 1));
    }

    @Test
    void kbEvidenceUsesKnowledgeBaseContractAndNormalizesSnippets() {
        ChatHistoryService history = mock(ChatHistoryService.class);
        KnowledgeBaseService kb = mock(KnowledgeBaseService.class);
        when(kb.evidenceSnippets("game", "skirk", 3))
                .thenReturn(List.of("  useful evidence  ", "# internal note", "", "useful evidence"));

        MemoryAsEvidenceAdapter adapter = new MemoryAsEvidenceAdapter(history);
        ReflectionTestUtils.setField(adapter, "kb", kb);

        assertEquals(List.of("useful evidence"), adapter.fromKb("game", "skirk", 3));
    }
}
