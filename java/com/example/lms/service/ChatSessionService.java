package com.example.lms.service;

import com.example.lms.domain.ChatSession;

import java.util.List;
import java.util.Optional;

/**
 * Service interface for managing chat sessions and messages.
 * <p>
 * Methods on this interface correspond to the CRUD operations used by
 * {@link com.example.lms.api.ChatApiController}, {@link com.example.lms.service.ChatService}
 * and other components that work with persisted chat sessions.  Implementations
 * should persist messages, retrieve recent history in formatted form and expose
 * convenience methods for reading or mutating session state.
 * </p>
 */
public interface ChatSessionService {

    /**
     * Create a new chat session and save the first user message.
     *
     * @param firstMessage initial user message
     * @param username     owner identifier
     * @return the created session, wrapped in an Optional
     */
    Optional<ChatSession> startNewSession(String firstMessage, String username);

    /**
     * Append a pair of user and assistant messages to an existing session.
     *
     * @param session         the session to append to
     * @param userMessage     user message content
     * @param assistantMessage assistant message content
     */
    void addMessagesToSession(ChatSession session, String userMessage, String assistantMessage);

    /**
     * Append a single message to an existing session.
     *
     * @param sessionId session identifier
     * @param role      role of the message ("user", "assistant" or "system")
     * @param content   message content
     */
    void appendMessage(Long sessionId, String role, String content);

    /**
     * Retrieve all chat sessions for the administrator dashboard.
     *
     * @return list of all sessions
     */
    List<ChatSession> getAllSessionsForAdmin();

    /**
     * Retrieve sessions for a specific user.
     *
     * @param username user identifier
     * @return list of sessions belonging to the user
     */
    List<ChatSession> getSessionsForUser(String username);

    /**
     * Retrieve a session with its messages populated.
     *
     * @param id session identifier
     * @return populated session
     */
    ChatSession getSessionWithMessages(Long id);

    /**
     * Delete a session and its associated messages.
     *
     * @param id session identifier
     */
    void deleteSession(Long id);

    /**
     * Get a formatted view of the recent chat history.  Each element of the
     * returned list is prefixed with the message role (e.g. "user: hello").
     *
     * @param sessionId session identifier
     * @param limit     maximum number of messages to return
     * @return list of formatted messages (never null)
     */
    List<String> getFormattedRecentHistory(Long sessionId, int limit);

    /**
     * Convenience method for retrieving the most recent assistant message in
     * the conversation.  Returns an empty Optional if no such message exists.
     *
     * @param sessionId session identifier
     * @return Optional containing the last assistant message
     */
    Optional<String> getLastAssistantMessage(Long sessionId);
}