package com.example.lms.service.rag.chain;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.prompt.PromptContext;
import com.example.lms.service.AttachmentOwnerIdentity;
import dev.langchain4j.data.document.Document;
import java.util.List;



/**
 * Mutable context passed along the chain.  Implementations should
 * encapsulate the current session, user and prompt state as well as
 * provide helper methods for annotating the context with system
 * information, attachments, metadata and emitting assistant messages.
 */
public interface ChainContext {
    /**
     * Logical identifier of the current session.
     */
    String sessionId();

    /**
     * Logical identifier of the user.
     */
    String userId();

    /** Hash-only attachment owner captured at the authorized request boundary. */
    default AttachmentOwnerIdentity attachmentOwnerIdentity() {
        return null;
    }

    /**
     * The raw user message triggering the chain.
     */
    String userMessage();

    /**
     * Prompt context capturing memory, web and other information.
     */
    PromptContext promptContext();

    /**
     * Append a system note to the context.  System notes are not shown to
     * the user directly but may be used by downstream prompt builders.
     */
    ChainContext withSystemNote(String note);

    /**
     * Append a side note that may be visible to the assistant.  Use
     * sparingly to avoid cluttering the response stream.
     */
    ChainContext withAssistantSideNote(String note);

    /**
     * Attach a file descriptor to the context.  Attachments may be
     * summarised or processed by subsequent chain links.
     */
    ChainContext withAttachment(AttachmentDto att);

    /**
     * Merge local attachment documents into the prompt context while preserving
     * PromptBuilder-owned final prompt construction.
     */
    default ChainContext withLocalDocs(List<Document> docs) {
        return this;
    }

    /**
     * Associate an arbitrary key/value pair with the context.  Keys
     * prefixed with "image." are reserved for the image prompt handler.
     */
    ChainContext putMeta(String key, String value);

    /**
     * Emit an assistant message immediately.  When SSE is enabled
     * implementations may push this message directly to the client.
     */
    void emitAssistant(String text);
}
