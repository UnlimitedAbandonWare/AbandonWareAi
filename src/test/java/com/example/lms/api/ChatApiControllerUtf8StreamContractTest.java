package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.search.TraceStore;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.AttachmentService;
import com.example.lms.service.ChatHistoryService;
import com.example.lms.service.ChatResult;
import com.example.lms.service.ChatService;
import com.example.lms.service.SettingsService;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatApiControllerUtf8StreamContractTest {
    @AfterEach
    void clearRequestTrace() {
        TraceStore.clear();
        org.slf4j.MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingSessionPreservesMachineFieldsAndEmitsExactUtf8Display(boolean delete) throws Exception {
        try (Fixture f = new Fixture()) {
            var response = delete ? f.controller.deleteSession(404L, null)
                    : f.controller.getSession(404L, false, null);
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
            Map<?, ?> body = (Map<?, ?>) response.getBody();
            assertEquals(Set.of("action", "error", "message"), body.keySet());
            assertEquals("RESET_SESSION", body.get("action"));
            assertEquals("SESSION_NOT_FOUND", body.get("error"));
            String json = f.mapper.writeValueAsString(body);
            System.out.println("F25_SESSION_PAYLOAD " + json);
            assertEquals("세션이 만료되었습니다.", body.get("message"));
            assertEquals("세션이 만료되었습니다.", f.mapper.readTree(
                    json.getBytes(StandardCharsets.UTF_8)).get("message").asText());
            verify(f.history, never()).deleteSession(anyLong());
            verifyNoInteractions(f.chat, f.attachments);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void restoreProbeStillReturnsOneNonEnumeratingMachineResponse(boolean foreign) {
        try (Fixture f = new Fixture()) {
            if (foreign) {
                ChatSession session = new ChatSession("synthetic", "foreign-owner", "ANON");
                session.setId(404L);
                when(f.history.getSessionWithMessages(404L)).thenReturn(session);
            }
            var response = f.controller.getSession(404L, true, null);
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals(Map.of("found", false, "action", "RESET_SESSION", "error", "SESSION_UNAVAILABLE"),
                    response.getBody());
            verifyNoInteractions(f.chat, f.attachments);
        }
    }

    @ParameterizedTest
    @CsvSource({"0,0", "2,0", "2,1", "2,2"})
    void attachmentFailureSummaryIsCountOnlyAndExactWithoutChangingAnswer(int total, int loaded) {
        try (Fixture f = new Fixture()) {
            var documents = IntStream.range(0, loaded).mapToObj(i -> Document.from("synthetic document")).toList();
            when(f.attachments.asDocumentsForSession(anyList(), eq("42"), any(AttachmentOwnerIdentity.class)))
                    .thenReturn(documents);
            var ids = IntStream.range(0, total).mapToObj(i -> "synthetic-attachment-" + i).toList();
            ChatRequestDto request = ChatRequestDto.builder().message("synthetic neutral question")
                    .sessionId(42L).useRag(false).useWebSearch(false).attachmentIds(ids).build();
            var response = f.controller.chatSync(request, null, new org.springframework.mock.web.MockHttpServletRequest());
            assertEquals(HttpStatus.OK, response.getStatusCode());
            assertEquals("synthetic answer", response.getBody().getContent());
            List<String> systemMessages = mockingDetails(f.history).getInvocations().stream()
                    .filter(i -> i.getMethod().getName().equals("appendMessage") && i.getArguments().length == 3)
                    .filter(i -> "system".equals(i.getArgument(1)))
                    .map(i -> (String) i.getArgument(2)).toList();
            if (total > loaded) {
                assertEquals(List.of("?MODEL?synthetic-model",
                        String.format("첨부 %d개 중 %d개 로드 실패", total, total - loaded)), systemMessages);
            } else {
                assertEquals(List.of("?MODEL?synthetic-model"), systemMessages);
            }
            assertFalse(f.registry.isRunning(42L));
        }
    }

    @Test
    void simultaneousMissingDetailAndDeleteResponsesStayIsolated() throws Exception {
        try (Fixture f = new Fixture()) {
            var executor = Executors.newFixedThreadPool(2);
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            when(f.history.getSessionWithMessages(anyLong())).thenAnswer(i -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            });
            try {
                var detail = executor.submit(() -> f.controller.getSession(404L, false, null));
                var deletion = executor.submit(() -> f.controller.deleteSession(405L, null));
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                release.countDown();
                var a = detail.get(2, TimeUnit.SECONDS);
                var b = deletion.get(2, TimeUnit.SECONDS);
                assertEquals(HttpStatus.NOT_FOUND, a.getStatusCode());
                assertEquals(HttpStatus.NOT_FOUND, b.getStatusCode());
                assertNotSame(a.getBody(), b.getBody());
                assertEquals(a.getBody(), b.getBody());
                assertEquals("세션이 만료되었습니다.", ((Map<?, ?>) a.getBody()).get("message"));
                verify(f.history, never()).deleteSession(anyLong());
            } finally {
                release.countDown();
                executor.shutdown();
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
                }
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ChatHistoryService history = mock(ChatHistoryService.class);
        final ChatService chat = mock(ChatService.class);
        final AttachmentService attachments = mock(AttachmentService.class);
        final ChatRunRegistry registry = new ChatRunRegistry();
        final ObjectMapper mapper = new ObjectMapper();
        final ChatApiController controller;

        Fixture() {
            ReflectionTestUtils.setField(registry, "replayCapacity", 32);
            ReflectionTestUtils.setField(registry, "ttlSeconds", 60);
            SettingsService settings = mock(SettingsService.class);
            ClientOwnerKeyResolver owners = mock(ClientOwnerKeyResolver.class);
            when(settings.getAllSettings()).thenReturn(Map.of());
            when(owners.ownerKey()).thenReturn("synthetic-owner");
            controller = new ChatApiController(history, chat, null, settings, null,
                    null, null, null, null, null, null, null, null, null, attachments, null,
                    mapper, null, registry, owners);
            ChatSession session = new ChatSession("synthetic", "synthetic-owner", "ANON");
            session.setId(42L);
            when(history.getSessionWithMessages(42L)).thenReturn(session);
            when(history.appendMessageReturningId(42L, "assistant", "synthetic answer")).thenReturn(421L);
            when(chat.continueChat(any(ChatRequestDto.class), any()))
                    .thenReturn(ChatResult.of("synthetic answer", "synthetic-model", false));
        }

        @Override public void close() {
            ReflectionTestUtils.invokeMethod(registry, "shutdown");
        }
    }
}
