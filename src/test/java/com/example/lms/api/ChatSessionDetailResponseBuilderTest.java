package com.example.lms.api;

import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.service.SettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatSessionDetailResponseBuilderTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @Test
    void assistantOwnedPromotedEvidenceSurvivesReloadWithoutTraceExposure() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 10, 0);
        for (boolean exposeTrace : List.of(false, true)) {
            ChatSession session = ChatSession.builder().id(101L).title("synthetic relation")
                    .createdAt(now).messages(List.of(
                            message(10L, "user", "synthetic A", now),
                            message(11L, "assistant", "A answer [W1]", now.plusSeconds(1)),
                            message(12L, "system", evidencePointer(11L, "https://example.test/A?view=1"), now.plusSeconds(2)),
                            message(13L, "user", "synthetic B", now.plusSeconds(3)),
                            message(14L, "assistant", "B answer [W1]", now.plusSeconds(4)),
                            message(15L, "system", evidencePointer(14L, "https://example.test/B?view=2"), now.plusSeconds(5))))
                    .build();
            var detail = ChatSessionDetailResponseBuilder.build(session, "guest", objectMapper,
                    Map.of(), exposeTrace, LoggerFactory.getLogger(getClass())).getBody();
            var assistants = detail.messages().stream().filter(m -> "assistant".equals(m.role())).toList();
            for (int index = 0; index < assistants.size(); index++) {
                var evidence = objectMapper.valueToTree(assistants.get(index)).path("evidence");
                assertEquals(1, evidence.size(), "reload must retain the exact answer-owned promoted source");
                assertEquals("W1", evidence.get(0).path("marker").asText());
                assertEquals(index == 0 ? "https://example.test/A?view=1" : "https://example.test/B?view=2",
                        evidence.get(0).path("source").asText());
                assertFalse(evidence.toString().contains("rawSnippet"));
            }
            if (!exposeTrace) assertTrue(detail.turnTraces().isEmpty());
        }
    }

    private String evidencePointer(long assistantId, String source) throws Exception {
        String json = objectMapper.writeValueAsString(List.of(Map.of("marker", "W1", "kind", "WEB",
                "title", "Synthetic qualified relation", "source", source, "lineStart", 4, "lineEnd", 6)));
        String evidence = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        String projection = "storageMode=durable_fallback\nassistantMessageId=" + assistantId
                + "\nreason=final\nmethod=SSE\npathHash=none\npublicEvidence=" + evidence + "\n";
        return "?TRACESNAP?synthetic-" + assistantId + "|v3|"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(projection.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void conflictingPointersAndMissingAssistantCannotRestoreSourcesOntoAnotherAnswer() throws Exception {
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 10, 0);
        for (boolean missingAssistant : List.of(false, true)) {
            var messages = new java.util.ArrayList<ChatMessage>();
            messages.add(message(11L, "assistant", "Answer [W1]", now));
            messages.add(message(12L, "system", evidencePointer(missingAssistant ? 999L : 11L,
                    "https://example.test/A"), now.plusSeconds(1)));
            if (!missingAssistant) messages.add(message(13L, "system", evidencePointer(11L,
                    "https://example.test/B"), now.plusSeconds(2)));
            var session = ChatSession.builder().id(101L).title("synthetic").createdAt(now).messages(messages).build();
            var detail = ChatSessionDetailResponseBuilder.build(session, "guest", objectMapper, Map.of(), true,
                    LoggerFactory.getLogger(getClass())).getBody();
            var answer = detail.messages().stream().filter(m -> "assistant".equals(m.role())).findFirst().orElseThrow();
            assertEquals(0, objectMapper.valueToTree(answer).path("evidence").size());
            assertTrue(detail.turnTraces().isEmpty());
        }
    }

    @Test
    void publicEvidenceEnvelopeRejectsUnknownFieldsDuplicateMarkersAndNonRecords() throws Exception {
        for (String json : List.of(
                "[{\"marker\":\"W1\",\"kind\":\"WEB\",\"source\":\"https://example.test/\",\"rawSnippet\":\"SYNTHETIC_PRIVATE_BODY\"}]",
                "[{\"marker\":\"W1\",\"kind\":\"WEB\",\"source\":\"https://example.test/A\"},{\"marker\":\"W1\",\"kind\":\"WEB\",\"source\":\"https://example.test/B\"}]",
                "[null]", "{\"marker\":\"W1\"}")) {
            String value = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            String text = "storageMode=durable_fallback\nassistantMessageId=11\nreason=final\nmethod=SSE\npathHash=none\npublicEvidence=" + value + "\n";
            String envelope = "?TRACESNAP?synthetic-11|v3|" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(text.getBytes(StandardCharsets.UTF_8));
            var pointer = ChatTraceMetaMessageRestorer.parseSnapshotPointer(envelope, 12L).orElseThrow();
            assertTrue(pointer.evidence().isEmpty());
            org.junit.jupiter.api.Assertions.assertNull(pointer.assistantMessageId());
        }
    }

    @Test
    void buildsDetailWithoutLeakingModelMetaAndRestoresTraceSnapshotCard() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 15, 0);
        ChatSession session = ChatSession.builder()
                .id(99L)
                .title("saved chat")
                .createdAt(now)
                .sessionMeta("{\"profile\":\"saved-profile\"}")
                .messages(List.of(
                        message(1L, "system", "?MODEL?OpenAiChatModel", now),
                        message(2L, "system", "?TRACESNAP?snap-1", now.plusSeconds(1)),
                        message(3L, "user", "hello", now.plusSeconds(2)),
                        message(4L, "assistant", "answer", now.plusSeconds(3))))
                .build();

        ResponseEntity<ChatApiController.SessionDetail> response = ChatSessionDetailResponseBuilder.build(
                session,
                "guest",
                objectMapper,
                Map.of(SettingsService.KEY_OPENAI_MODEL, "configured-model"),
                true,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class));

        ChatApiController.SessionDetail body = response.getBody();
        assertNotNull(body);
        assertEquals("configured-model", body.modelUsed());
        assertEquals("saved-profile", body.settings().get("profile"));
        assertEquals("configured-model", response.getHeaders().getFirst("X-Model-Used"));
        assertEquals("guest", response.getHeaders().getFirst("X-Session-Owner"));
        assertEquals(3, body.messages().size());
        assertEquals("system", body.messages().get(0).role());
        assertTrue(body.messages().get(0).content().contains("data-trace-snapshot-id=\"snap-1\""));
        assertEquals("user", body.messages().get(1).role());
        assertEquals("assistant", body.messages().get(2).role());
    }

    @Test
    void concreteModelWinsAndLegacyTraceMetaStaysHiddenWhenTraceExposureIsOff() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 15, 5);
        ChatSession session = ChatSession.builder()
                .id(100L)
                .title("saved chat")
                .createdAt(now)
                .messages(List.of(
                        message(1L, "system", "?MODEL?real-model", now),
                        message(2L, "system", "?TRACE?<div>raw</div>", now.plusSeconds(1)),
                        message(3L, "user", "hello", now.plusSeconds(2))))
                .build();

        ResponseEntity<ChatApiController.SessionDetail> response = ChatSessionDetailResponseBuilder.build(
                session,
                null,
                objectMapper,
                Map.of(SettingsService.KEY_OPENAI_MODEL, "configured-model"),
                false,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class));

        ChatApiController.SessionDetail body = response.getBody();
        assertNotNull(body);
        assertEquals("real-model", body.modelUsed());
        assertEquals("anonymousUser", response.getHeaders().getFirst("X-Session-Owner"));
        assertEquals(1, body.messages().size());
        assertEquals("hello", body.messages().get(0).content());
    }

    @Test
    void turnTracesJoinOwningTurnAndModelMetaOnlyWhenTraceExposed() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 15, 10);
        String durable = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "reason=scored\nmethod=rule\nstorageMode=durable_fallback\npathHash=none\nassistantMessageId=11\n"
                        .getBytes(StandardCharsets.UTF_8));
        ChatSession session = ChatSession.builder()
                .id(101L)
                .title("trace session")
                .createdAt(now)
                .messages(List.of(
                        message(10L, "user", "q", now),
                        message(11L, "assistant", "a", now.plusSeconds(1)),
                        message(12L, "system", "?MODEL?gemma4:26b", now.plusSeconds(2)),
                        message(13L, "system", "?TRACESNAP?snap-9|v2|" + durable, now.plusSeconds(3))))
                .build();

        ResponseEntity<ChatApiController.SessionDetail> response = ChatSessionDetailResponseBuilder.build(
                session,
                "guest",
                objectMapper,
                Map.of(SettingsService.KEY_OPENAI_MODEL, "configured-model"),
                true,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class));

        ChatApiController.SessionDetail body = response.getBody();
        assertNotNull(body);
        assertEquals(1, body.turnTraces().size());
        ChatApiController.TurnTraceDto trace = body.turnTraces().get(0);
        assertEquals(11L, trace.turnId());
        assertEquals("snap-9", trace.snapshotId());
        assertEquals("durable_fallback", trace.fields().get("storageMode"));
        assertEquals("gemma4:26b", trace.fields().get("modelUsed"));

        ChatApiController.SessionDetail hidden = ChatSessionDetailResponseBuilder.build(
                session,
                "guest",
                objectMapper,
                Map.of(),
                false,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class)).getBody();
        assertNotNull(hidden);
        assertTrue(hidden.turnTraces().isEmpty());
    }

    @Test
    void legacyPointerJoinsTheUniquePrecedingAssistantButWrongRoleV2DoesNot() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 15, 12);
        String wrongRole = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "reason=scored\nmethod=rule\nstorageMode=durable_fallback\npathHash=none\nassistantMessageId=10\n"
                        .getBytes(StandardCharsets.UTF_8));
        ChatSession session = ChatSession.builder()
                .id(102L)
                .title("unlinked trace")
                .createdAt(now)
                .messages(List.of(
                        message(10L, "user", "q", now),
                        message(11L, "assistant", "a", now.plusSeconds(1)),
                        message(12L, "system", "?TRACESNAP?legacy-snap", now.plusSeconds(2)),
                        message(13L, "system", "?TRACESNAP?wrong-role|v2|" + wrongRole, now.plusSeconds(3))))
                .build();

        ChatApiController.SessionDetail detail = ChatSessionDetailResponseBuilder.build(
                session, "guest", objectMapper, Map.of(), true,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class)).getBody();
        assertNotNull(detail);
        assertEquals(1, detail.turnTraces().size());
        assertEquals(11L, detail.turnTraces().get(0).turnId());
        assertEquals("legacy-snap", detail.turnTraces().get(0).snapshotId());
        assertEquals(4, detail.messages().size(), "operator cards remain available alongside a safe legacy join");
    }

    @Test
    void answerOverviewUsesOnlyValidatedObservedDiagnosticsAndPreservesZero() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 7, 10, 0);
        String projection = "storageMode=durable_fallback\nassistantMessageId=11\nreason=scored\nmethod=rule\npathHash=none\n"
                + "diag.observedModel=s:synthetic-model\ndiag.observedProvider=s:chatgpt_oauth\n"
                + "diag.prompt.citableEvidenceCount=n:0\ndiag.orch.mode=s:STRIKE\n";
        String durable = Base64.getUrlEncoder().withoutPadding().encodeToString(projection.getBytes(StandardCharsets.UTF_8));
        ChatSession session = ChatSession.builder().id(101L).title("synthetic").createdAt(now)
                .messages(List.of(message(11L, "assistant", "answer", now),
                        message(12L, "system", "?TRACESNAP?snap-overview|v3|" + durable, now.plusSeconds(1)))).build();
        var detail = ChatSessionDetailResponseBuilder.build(session, "guest", objectMapper, Map.of(), true,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class)).getBody();
        assertNotNull(detail);
        var fields = detail.turnTraces().get(0).fields();
        assertEquals("synthetic-model", fields.get("observedModel"));
        assertEquals("chatgpt_oauth", fields.get("observedProvider"));
        assertEquals("0", fields.get("prompt.citableEvidenceCount"));
        assertEquals("STRIKE", fields.get("orch.mode"));
        assertFalse(fields.containsKey("web.brave.failureReason"));
        assertFalse(fields.containsKey("web.naver.failureReason"));
    }

    @Test
    void conflictingSnapshotsForOneAssistantDoNotChooseByPointerOrder() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 15, 14);
        String projection = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "reason=scored\nmethod=rule\nstorageMode=durable_fallback\npathHash=none\nassistantMessageId=11\n"
                        .getBytes(StandardCharsets.UTF_8));
        ChatSession session = ChatSession.builder()
                .id(103L)
                .title("ambiguous trace")
                .createdAt(now)
                .messages(List.of(
                        message(10L, "user", "q", now),
                        message(11L, "assistant", "a", now.plusSeconds(1)),
                        message(12L, "system", "?TRACESNAP?snap-a|v2|" + projection, now.plusSeconds(2)),
                        message(13L, "system", "?TRACESNAP?snap-b|v2|" + projection, now.plusSeconds(3))))
                .build();

        ChatApiController.SessionDetail detail = ChatSessionDetailResponseBuilder.build(
                session, "guest", objectMapper, Map.of(), true,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class)).getBody();
        assertNotNull(detail);
        assertTrue(detail.turnTraces().isEmpty());
        assertEquals(4, detail.messages().size());
    }

    @Test
    void legacyV1UsesCreatedAtThenIdAndLeavesMultipleAssistantsUnbound() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 12, 15, 16);
        String legacy = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "reason=scored\nmethod=rule\nstorageMode=durable_fallback\npathHash=none\n"
                        .getBytes(StandardCharsets.UTF_8));
        ChatSession ordered = ChatSession.builder().id(104L).title("legacy ordered")
                .createdAt(now).messages(List.of(
                        message(13L, "system", "?TRACESNAP?snap-v1|v1|" + legacy, now),
                        message(11L, "assistant", "answer", now),
                        message(10L, "user", "question", now)))
                .build();
        ChatApiController.SessionDetail joined = ChatSessionDetailResponseBuilder.build(
                ordered, "guest", objectMapper, Map.of(), true,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class)).getBody();
        assertNotNull(joined);
        assertEquals(1, joined.turnTraces().size());
        assertEquals(11L, joined.turnTraces().get(0).turnId());

        ChatSession ambiguous = ChatSession.builder().id(105L).title("legacy ambiguous")
                .createdAt(now).messages(List.of(
                        message(10L, "user", "question", now),
                        message(11L, "assistant", "first", now.plusSeconds(1)),
                        message(12L, "assistant", "second", now.plusSeconds(2)),
                        message(13L, "system", "?TRACESNAP?snap-v1|v1|" + legacy, now.plusSeconds(3))))
                .build();
        ChatApiController.SessionDetail unbound = ChatSessionDetailResponseBuilder.build(
                ambiguous, "guest", objectMapper, Map.of(), true,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class)).getBody();
        assertNotNull(unbound);
        assertTrue(unbound.turnTraces().isEmpty());

        String invalidV1 = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "reason=scored\nmethod=rule\nstorageMode=durable_fallback\npathHash=none\nassistantMessageId=11\n"
                        .getBytes(StandardCharsets.UTF_8));
        ChatSession malformed = ChatSession.builder().id(106L).title("invalid legacy")
                .createdAt(now).messages(List.of(
                        message(10L, "user", "question", now),
                        message(11L, "assistant", "answer", now.plusSeconds(1)),
                        message(13L, "system", "?TRACESNAP?snap-v1|v1|" + invalidV1, now.plusSeconds(2))))
                .build();
        ChatApiController.SessionDetail rejected = ChatSessionDetailResponseBuilder.build(
                malformed, "guest", objectMapper, Map.of(), true,
                LoggerFactory.getLogger(ChatSessionDetailResponseBuilderTest.class)).getBody();
        assertNotNull(rejected);
        assertTrue(rejected.turnTraces().isEmpty(), "invalid v1 fields must not be treated as a legacy join");
    }

    @Test
    void sessionMetaParseFailureLogsHashAndLengthOnly() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/api/ChatSessionDetailResponseBuilder.java"),
                StandardCharsets.UTF_8);

        assertTrue(source.contains("errorHash={}") && source.contains("errorLength={}"));
        assertTrue(source.contains("SafeRedactor.hashValue(e.getMessage())"));
        assertFalse(source.contains("SafeRedactor.safeMessage(e.getMessage(), 180)"));
    }

    private static ChatMessage message(Long id, String role, String content, LocalDateTime createdAt) {
        return ChatMessage.builder()
                .id(id)
                .role(role)
                .content(content)
                .createdAt(createdAt)
                .build();
    }
}
