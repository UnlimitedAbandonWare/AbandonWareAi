package com.example.lms.api;

import com.example.lms.domain.ChatSession;
import com.example.lms.dto.ChatRequestDto;
import com.example.lms.service.*;
import com.example.lms.service.chat.ChatRunRegistry;
import com.example.lms.service.trace.TraceHtmlBuilder;
import com.example.lms.trace.TraceSnapshotStore;
import com.example.lms.search.TraceStore;
import com.example.lms.web.ClientOwnerKeyResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatLazyTraceContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"sync", "chat", "stream"})
    void normalChatPreservesDurableDiagnosticsWithoutEagerHtml(String route) {
        var history = mock(ChatHistoryService.class);
        var chat = mock(ChatService.class);
        var settings = mock(SettingsService.class);
        var owners = mock(ClientOwnerKeyResolver.class);
        var runs = new ChatRunRegistry();
        var renderer = mock(TraceHtmlBuilder.class);
        when(renderer.buildSplitPanel(any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn("<section>synthetic trace</section>");
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("traceHtmlBuilder", renderer);
        var store = new TraceSnapshotStore(beans.getBeanProvider(TraceHtmlBuilder.class));
        for (var entry : Map.<String, Object>of("enabled", true, "htmlEnabled", true, "maxSize", 5,
                "maxValueLen", 1000, "maxEntries", 100, "maxPerTrace", 10, "captureSample", 1.0d).entrySet())
            ReflectionTestUtils.setField(store, entry.getKey(), entry.getValue());
        ReflectionTestUtils.setField(store, "budgetWindowMs", 600000L);
        for (String name : List.of("allowReasonsCsv", "denyReasonsCsv", "allowKeysCsv", "denyKeysCsv"))
            ReflectionTestUtils.setField(store, name, "");
        ReflectionTestUtils.setField(store, "allowKeysMode", "any");
        ReflectionTestUtils.setField(runs, "replayCapacity", 32);
        ReflectionTestUtils.setField(runs, "ttlSeconds", 60);
        var controller = new ChatApiController(history, chat, null, settings, null,
                null, null, null, null, null, null, null, null, null, null, null,
                new ObjectMapper(), null, runs, owners);
        ReflectionTestUtils.setField(controller, "traceHtmlBuilder", renderer);
        ReflectionTestUtils.setField(controller, "traceSnapshotStore", store);
        var session = new ChatSession("synthetic", "synthetic-owner", "ANON");
        session.setId(42L);
        when(history.getSessionForRequest(42L)).thenReturn(session);
        when(history.getSessionWithMessages(42L, 1)).thenReturn(session);
        when(settings.getAllSettings()).thenReturn(Map.of());
        when(owners.ownerKey()).thenReturn("synthetic-owner");
        when(history.appendMessageReturningId(eq(42L), eq("user"), anyString())).thenReturn(420L);
        when(history.appendMessageReturningId(eq(42L), eq("assistant"), anyString())).thenReturn(421L);
        when(history.appendMessageReturningId(eq(42L), eq("system"), anyString())).thenReturn(422L);
        when(chat.continueChat(any(ChatRequestDto.class), any())).thenAnswer(call -> {
            TraceStore.put("prompt.webCount", 0);
            TraceStore.put("queryTransformer.reason", "retrieval_off_direct");
            TraceStore.put("queryTransformer.bypassed", true);
            return ChatResult.of("synthetic answer", "mock-model", false);
        });
        try {
            var request = ChatRequestDto.builder().message("synthetic question").sessionId(42L)
                    .useRag(false).useWebSearch(false).build();
            var http = new MockHttpServletRequest();
            if ("stream".equals(route)) {
                var events = controller.chatStream(request, false, false, null, http)
                        .collectList().block(Duration.ofSeconds(10));
                assertNotNull(events);
                assertTrue(events.stream().anyMatch(e -> e.data() != null && "final".equals(e.data().type())));
            } else {
                var response = "sync".equals(route) ? controller.chatSync(request, null, http)
                        : controller.chat(request, null, http).block(Duration.ofSeconds(10));
                assertNotNull(response);
                assertEquals("synthetic answer", response.getBody().getContent());
            }
            verifyNoInteractions(renderer);
            var pointerText = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(history).appendMessageReturningId(eq(42L), eq("system"), pointerText.capture());
            var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(pointerText.getValue(), 422L).orElseThrow();
            assertEquals(421L, pointer.assistantMessageId());
            assertEquals(0, ((Number) pointer.diagnostics().get("prompt.webCount")).intValue());
            assertEquals("retrieval_off_direct", pointer.diagnostics().get("queryTransformer.reason"));
            assertNull(store.get(pointer.snapshotId()).orElseThrow().html());
            assertTrue(pointerText.getValue().length() <= 16000);
            var lateHtml = new TraceHtmlBuilder(null).buildSnapshotHtml(pointer.snapshotId(), null, null, null,
                    null, "chat.trace_html.final", "POST", "none", null, null, pointer.diagnostics(), Map.of());
            assertTrue(lateHtml.contains("retrieval_off_direct"));
        } finally {
            ReflectionTestUtils.invokeMethod(runs, "shutdown");
            TraceStore.clear();
            com.example.lms.trace.TraceContext.cleanupCurrentThread();
        }
    }
}
