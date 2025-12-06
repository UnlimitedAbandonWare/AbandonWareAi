package com.example.lms.service.rag;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import java.util.List;



/**
 * Utility methods for constructing {@link Query} instances in a version-safe manner.
 *
 * <p>LangChain4j 1.0.x removed the {@code Query.builder()} API and changed
 * the signature of {@code Metadata.from(/* ... *&#47;)}, which previously accepted
 * arbitrary maps.  This helper encapsulates those API differences by
 * constructing {@code Metadata} objects using the supported overloads and
 * returning a new {@code Query} instance for the given text.  When a
 * session identifier or chat history is provided, they are encoded into
 * the {@code Metadata} so that downstream components can still access
 * contextual information.</p>
 */
public final class QueryUtils {

    /** Prevent instantiation. */
    private QueryUtils() {
    }

    /**
     * Build a new {@link Query} with the supplied text and optional session/history
     * context.  This method mirrors the behaviour of the removed builder API by
     * populating a {@link Metadata} instance with the user message, session
     * identifier and chat history.  When {@code history} is {@code null}, an
     * empty list is used instead.
     *
     * @param text      the natural language query text
     * @param sessionId an opaque session identifier (may be {@code null})
     * @param history   recent chat history messages; may be {@code null}
     * @return a new {@link Query} ready for use in retrieval
     */
    public static Query buildQuery(String text, Object sessionId, List<ChatMessage> history) {
        Metadata md = Metadata.from(
                UserMessage.from(text),
                sessionId,
                history == null ? List.of() : history
        );
        return new Query(text, md);
    }

    /**
     * Build a new {@link Query} containing only the user text.  This overload
     * delegates to the no-arg constructor of {@link Query}, leaving the
     * metadata unset.
     *
     * @param text the natural language query text
     * @return a new {@link Query}
     */
    public static Query buildQuery(String text) {
        return new Query(text);
    }
}