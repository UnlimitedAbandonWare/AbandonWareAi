package com.example.lms.memory;

import com.example.lms.domain.ChatSession;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.repository.ChatSessionRepository;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistentChatMemoryTest {

    @Test
    void messagesExcludeNavigationPathMetadata() {
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatSession session = new ChatSession("test", "owner", "ANON");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(
                new com.example.lms.domain.ChatMessage(session, "user", "hello"),
                new com.example.lms.domain.ChatMessage(session, "path", "node-a>node-b"),
                new com.example.lms.domain.ChatMessage(session, "assistant", "world")));

        List<ChatMessage> messages =
                new PersistentChatMemory("42", messageRepository, sessionRepository).messages();

        assertEquals(2, messages.size());
        UserMessage user = assertInstanceOf(UserMessage.class, messages.get(0));
        AiMessage assistant = assertInstanceOf(AiMessage.class, messages.get(1));
        assertEquals("hello", user.singleText());
        assertEquals("world", assistant.text());
    }

    @Test
    void pathHistoryAndUnknownLegacyRoleKeepTheirExistingContracts() {
        ChatMessageRepository messageRepository = mock(ChatMessageRepository.class);
        ChatSessionRepository sessionRepository = mock(ChatSessionRepository.class);
        ChatSession session = new ChatSession("test", "owner", "ANON");
        when(messageRepository.findBySessionIdOrderByCreatedAtAsc(42L)).thenReturn(List.of(
                new com.example.lms.domain.ChatMessage(session, "path", "node-a>node-b"),
                new com.example.lms.domain.ChatMessage(session, "legacy", "legacy assistant text")));
        PersistentChatMemory memory = new PersistentChatMemory("42", messageRepository, sessionRepository);

        assertEquals(List.of("node-a", "node-b"), memory.pathHistory());
        List<ChatMessage> messages = memory.messages();
        assertEquals(1, messages.size());
        assertEquals("legacy assistant text", assertInstanceOf(AiMessage.class, messages.get(0)).text());
    }
}
