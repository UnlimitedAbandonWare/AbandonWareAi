// sedation test
package dev.langchain4j.rag.query;

import java.util.Map;


// test shim comment to verify patch application


/**
 * Compatibility shim for older LangChain4j APIs that expected a
 * {@code dev.langchain4j.rag.query.Metadata} type.  The upstream
 * implementation was removed in version 1.0.x, so this class
 * reintroduces the minimal API surface required by the LMS codebase.
 *
 * <p>The {@code from} factory method accepts three arguments mirroring
 * the signature from LangChain4j 0.x: a user message (unused), a chat
 * memory identifier and an optional chat history.  Only the chat
 * memory identifier is preserved.  When this shim is used the
 * metadata is internally represented as a simple map containing the
 * {@code META_SID} key defined in {@link com.example.lms.service.rag.LangChainRAGService}.
 *
 * <p>The {@link #asMap()} method exposes the internal representation so
 * that downstream code can extract session identifiers without
 * depending on the specific class.  Consumers should treat this type
 * as opaque and avoid depending on its implementation details.</p>
 */
public final class Metadata {

    /** The chat memory/session identifier. */
    private final Object chatMemoryId;

    /**
     * Private constructor used by the {@link #from(Object, Object, Object)} factory.
     *
     * @param chatMemoryId the session identifier to preserve
     */
    private Metadata(Object chatMemoryId) {
        this.chatMemoryId = chatMemoryId;
    }

    /**
     * Factory method that constructs a new {@code Metadata} instance from
     * the provided arguments.  Only the second argument (chatMemoryId)
     * is retained; the user message and history parameters are ignored
     * as they are not required for session isolation.
     *
     * @param userMessage ignored user message
     * @param chatMemoryId session identifier (may be {@code null})
     * @param chatHistory ignored chat history
     * @return a new {@code Metadata} instance
     */
    public static Metadata from(Object userMessage, Object chatMemoryId, Object chatHistory) {
        return new Metadata(chatMemoryId);
    }

    /**
     * Return the chat memory identifier associated with this metadata.
     *
     * @return the chat memory identifier or {@code null}
     */
    public Object chatMemoryId() {
        return chatMemoryId;
    }

    /**
     * Expose this metadata as a map.  When the chat memory identifier
     * is present a singleton map containing the META_SID key and value
     * is returned; otherwise an empty map is returned.
     *
     * @return an immutable map view of this metadata
     */
    public Map<String, Object> asMap() {
        if (chatMemoryId == null) {
            return java.util.Map.of();
        }
        return java.util.Map.of(
                com.example.lms.service.rag.LangChainRAGService.META_SID,
                chatMemoryId
        );
    }
}