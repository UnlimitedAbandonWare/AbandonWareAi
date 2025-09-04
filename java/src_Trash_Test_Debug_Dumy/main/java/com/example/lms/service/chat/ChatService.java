package com.example.lms.service.chat;

import com.example.risk.RiskScorer;
import org.springframework.beans.factory.annotation.Value;
import com.example.lms.service.rag.support.ContentCompat;
import com.example.lms.dto.ChatRequestDto;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Defines the contract for chat services in the LMS application. Implementations
 * of this interface accept user messages, manage conversational state and
 * produce responses enriched with optional retrieval-augmented generation (RAG)
 * evidence. The {@link ChatResult} nested record standardises the shape of
 * responses across different implementations and provides convenience factory
 * methods.
 */
public interface ChatService {

    /**
     * Standard response DTO returned by chat handlers.
     *
     * <p>This record encapsulates the generated content, the model name used
     * to generate the response, whether retrieval-augmented generation was
     * employed and an optional set of evidence identifiers. The deprecated
     * {@code model()} method is retained for backward compatibility but
     * delegates to {@link #modelUsed()}.</p>
     *
     * @param content   the response text
     * @param modelUsed the identifier of the model used
     * @param ragUsed   whether RAG was used to augment the response
     * @param evidence  a set of evidence identifiers (may be empty)
     */
    record ChatResult(String content, String modelUsed, boolean ragUsed, Set<String> evidence) {
        /**
         * Deprecated alias for {@link #modelUsed()} to preserve signature
         * compatibility with older clients.
         *
         * @return the model used
         */
        @Deprecated
        public String model() {
            return modelUsed();
        }

        /**
         * Creates a new {@code ChatResult} without evidence.
         *
         * @param c the content
         * @param m the model used
         * @param r whether RAG was used
         * @return a new result
         */
        public static ChatResult of(String c, String m, boolean r) {
            return new ChatResult(c, m, r, java.util.Set.of());
        }

        /**
         * Creates a new {@code ChatResult} with evidence. If the provided
         * evidence set is {@code null} it is replaced with an empty set.
         *
         * @param c the content
         * @param m the model used
         * @param r whether RAG was used
         * @param e the evidence set (nullable)
         * @return a new result
         */
        public static ChatResult of(String c, String m, boolean r, Set<String> e) {
            return new ChatResult(c, m, r, e == null ? java.util.Set.of() : e);
        }
    }

    /**
     * Handles a one-off chat request, typically used for WebSocket or short
     * interactions where no session context is maintained.
     *
     * @param userMsg the user input
     * @return the generated response
     */
    ChatResult ask(String userMsg);

    /**
     * Continues a chat session using the supplied request DTO and an
     * external context provider. Implementations may invoke the external
     * context provider to fetch additional context snippets given the
     * intermediate model output.
     *
     * @param req                 the chat request DTO
     * @param externalCtxProvider a function that takes a query string and
     *                            returns a list of context snippets
     * @return the generated response
     */
    ChatResult continueChat(ChatRequestDto req, Function<String, List<String>> externalCtxProvider);

    /**
     * Optionally cancels an active chat session. Implementations that do not
     * manage sessions can ignore this call.
     *
     * @param sessionId the identifier of the session to cancel
     */
    default void cancelSession(Long sessionId) {
        // no-op by default
    }
}