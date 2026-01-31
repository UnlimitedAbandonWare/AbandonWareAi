package com.example.lms.file;

import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;




/**
 * In-memory index for storing aggregated plain-text contents of
 * uploaded files keyed by session identifier.
 *
 * <p>When the user uploads attachments via the chat UI the
 * {@link com.example.lms.service.AttachmentService} extracts a textual
 * representation of the file and appends it to this index.  Entries
 * are truncated to a maximum length to bound memory usage.  Callers
 * can retrieve the concatenated text for a given session via
 * {@link #get(String)}.  Note that entries are not automatically
 * expired; the calling code should clear the session via
 * {@link #clear(String)} when appropriate (for example when a chat
 * session ends).</p>
 */
@Component
public class UploadedFileIndex {

    /** The internal map of session id to aggregated text. */
    private final Map<String, StringBuilder> bySession = new ConcurrentHashMap<>();

    /** Maximum number of characters stored per session.  Further input
     * will be ignored once this threshold is reached. */
    private static final int MAX = 20_000;

    /**
     * Append extracted text to the session’s index.  If the session id
     * or text is null or blank the operation is ignored.  When the
     * accumulated text length exceeds {@link #MAX} characters the
     * string is truncated in place and subsequent appends are ignored.
     *
     * @param sessionId unique session identifier (non-null/non-blank)
     * @param text extracted plain text (non-null/non-blank)
     */
    public void append(String sessionId, String text) {
        if (sessionId == null || sessionId.isBlank() || text == null || text.isBlank()) {
            return;
        }
        // Use computeIfAbsent to create a builder lazily
        StringBuilder sb = bySession.computeIfAbsent(sessionId, k -> new StringBuilder());
        if (sb.length() >= MAX) {
            return;
        }
        sb.append('\n').append(text);
        if (sb.length() > MAX) {
            sb.setLength(MAX);
        }
    }

    /**
     * Retrieve the concatenated text for a session.  Returns
     * {@code null} when the session has no indexed content.
     *
     * @param sessionId the session identifier
     * @return aggregated text or null
     */
    public String get(String sessionId) {
        StringBuilder sb = bySession.get(sessionId);
        return (sb == null ? null : sb.toString());
    }

    /**
     * Remove all indexed content for the specified session.  This
     * method should be called when a chat session ends to free up
     * memory.  Missing or blank session identifiers are ignored.
     *
     * @param sessionId the session identifier
     */
    public void clear(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        bySession.remove(sessionId);
    }
}