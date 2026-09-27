package com.example.lms.service.rag.graph;
import com.example.lms.assist.MemoryEvidence;
import com.example.lms.domain.ChatMessage;
import com.example.lms.domain.ChatSession;
import com.example.lms.repository.ChatMessageRepository;
import com.example.lms.service.vector.DocumentChunkingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class GraphCaptureConsentFocusedTest {
    private final ChatSession session = new ChatSession("synthetic", "owner", "ANON");
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final GeneralGraphSourceAuthority authority = mock(GeneralGraphSourceAuthority.class);
    private final GraphRagChunkingService service = spy(new GraphRagChunkingService(new BrainStateProperties(),
            new DocumentChunkingService(), text -> List.of(), null, null, null, null, messages));
    private GeneralGraphScope scope() {
        session.setId(7L);
        ReflectionTestUtils.setField(service, "sourceAuthority", authority);
        doReturn(GraphRagChunkingService.IngestReport.disabled("7", "synthetic")).when(service).ingestSource(any(), any());
        return GeneralGraphScope.authorize(session, null, "owner").orElseThrow().withPolicy(2, true);
    }
    private MemoryEvidence evidence(long id, String role) {
        return new MemoryEvidence("e"+id, "chat-message:"+id, 1, "synthetic", role,
                role.equals("USER") ? "USER_REPORTED" : "ASSISTANT_GENERATED", "synthetic",
                null, null, null, null, null, null, null);
    }
    @Test void exactPairCapturesCurrentUserOnly() {
        var scope = scope(); var user = evidence(13, "USER"); var answer = evidence(14, "ASSISTANT");
        when(authority.source(scope, 13)).thenReturn(Optional.of(user));
        when(authority.source(scope, 14)).thenReturn(Optional.of(answer));
        service.ingestFinalizedTurn(scope, 13L, 14);
        verify(service).ingestSource(scope, user);
        verify(service).ingestSource(scope, answer);
        verifyNoInteractions(messages);
        verify(authority, never()).source(scope, 11);
    }
    @Test void missingOrRevokedUserSourceDoesNotCaptureEitherRow() {
        var scope = scope(); var answer = evidence(14, "ASSISTANT");
        when(authority.source(scope, 14)).thenReturn(Optional.of(answer));
        when(authority.source(scope, 13)).thenReturn(Optional.empty());
        service.ingestFinalizedTurn(scope, 13L, 14);
        verify(service, never()).ingestSource(any(), any());
    }
    @Test void finalDecisionGatePropagatesPairOnlyWhenAllowed() {
        var scope = scope(); var chunker = mock(GraphRagChunkingService.class);
        var aspect = new BrainStateChatWorkflowAspect(new BrainStateProperties(), chunker, Runnable::run);
        com.example.lms.search.TraceStore.clear();
        aspect.captureFinalized(scope, 13L, 14L);
        verifyNoInteractions(chunker);
        com.example.lms.search.TraceStore.put("finalAnswer.memorySaveAllowed", true);
        aspect.captureFinalized(scope, 13L, 14L);
        verify(chunker).ingestFinalizedTurn(scope, 13L, 14L);
        com.example.lms.search.TraceStore.clear();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"anonymous", "administrator", "direct-owner", "administrator-profile"})
    void sessionCreationCarriesExactSavedMessageId(String route) {
        var sessions = mock(com.example.lms.repository.ChatSessionRepository.class);
        var admins = mock(com.example.lms.repository.AdministratorRepository.class);
        var admin = new com.example.lms.domain.Administrator(); admin.setUsername("synthetic-admin");
        when(admins.findByUsername("synthetic-admin")).thenReturn(Optional.of(admin));
        when(sessions.save(any(ChatSession.class))).thenAnswer(call -> {
            ChatSession saved = call.getArgument(0); saved.setId(7L); return saved;
        });
        when(messages.save(any(ChatMessage.class))).thenAnswer(call -> {
            ChatMessage input = call.getArgument(0);
            ChatMessage saved = new ChatMessage(input.getSession(), input.getRole(), input.getContent());
            saved.setId(99L); return saved;
        });
        var history = new com.example.lms.service.ChatHistoryServiceImpl(sessions, messages, admins,
                new com.fasterxml.jackson.databind.ObjectMapper(), mock(com.example.lms.web.ClientOwnerKeyResolver.class));
        Optional<ChatSession> created = switch (route) {
            case "anonymous" -> history.startNewSession("synthetic", "anonymousUser", null);
            case "administrator" -> history.startNewSession("synthetic", "synthetic-admin", null);
            case "administrator-profile" -> history.startNewSession("synthetic", "synthetic-admin", null, null,
                    com.example.lms.domain.enums.MemoryProfile.LIGHT);
            default -> history.startNewSession("synthetic", "anonymousUser", null, "synthetic-owner",
                    com.example.lms.domain.enums.MemoryProfile.LIGHT);
        };
        assertThat(created.orElseThrow().getInitialUserMessageId()).isEqualTo(99L);
        verify(messages, times(1)).save(any(ChatMessage.class));
    }
    @Test void creationIdentityIsNeitherPersistentNorJsonInput() throws Exception {
        session.setInitialUserMessageId(13L);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(mapper.writeValueAsString(session)).doesNotContain("initialUserMessageId");
        assertThat(mapper.readValue("{\"initialUserMessageId\":999}", ChatSession.class).getInitialUserMessageId()).isNull();
        assertThat(ChatSession.class.getDeclaredField("initialUserMessageId")
                .isAnnotationPresent(jakarta.persistence.Transient.class)).isTrue();
    }
    @Test void assistantOnlyCaptureDoesNotReauthorizeEarlierUserMessages() {
        var scope = scope();
        var prior = new ChatMessage(session, "user", "off-or-denied turn"); prior.setId(11L);
        var user = evidence(11, "USER"); var answer = evidence(14, "ASSISTANT");
        when(messages.findBySession_IdAndIdLessThanEqualOrderByIdDesc(eq(7L), eq(14L), any())).thenReturn(List.of(prior));
        when(authority.source(scope, 14)).thenReturn(Optional.of(answer));
        when(authority.source(scope, 11)).thenReturn(Optional.of(user));
        service.ingestFinalizedTurn(scope, 14);
        verify(service, never()).ingestSource(scope, user);
        verify(service).ingestSource(scope, answer);
    }
}
