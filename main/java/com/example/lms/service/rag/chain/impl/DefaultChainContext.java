package com.example.lms.service.rag.chain.impl;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.prompt.PromptContext;
import com.example.lms.service.AttachmentOwnerIdentity;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.service.chat.ChatRunExecutionContext;
import com.example.lms.service.rag.chain.ChainContext;
import dev.langchain4j.data.document.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;




/**
 * Default runtime ChainContext. Prompt-affecting data is carried by
 * PromptContext; withSystemNote/withAttachment remain no-op compatibility hooks
 * so handlers cannot create a second prompt-construction path.
 */
public class DefaultChainContext implements ChainContext {
    private static final Logger log = LoggerFactory.getLogger(DefaultChainContext.class);
    private final String sessionId;
    private final String userId;
    private final String userMessage;
    private PromptContext promptContext;
    private final ChatStreamEmitter emitter;
    private final ChatRunExecutionContext runContext;
    private final AttachmentOwnerIdentity attachmentOwnerIdentity;
    private final Map<String, String> meta = new HashMap<>();

    public DefaultChainContext(String sessionId, String userId, String userMessage, PromptContext promptContext, ChatStreamEmitter emitter) {
        this(sessionId, userId, userMessage, promptContext, emitter, null);
    }

    public DefaultChainContext(
            String sessionId,
            String userId,
            String userMessage,
            PromptContext promptContext,
            ChatStreamEmitter emitter,
            ChatRunExecutionContext runContext) {
        this(sessionId, userId, userMessage, promptContext, emitter, runContext, null);
    }

    public DefaultChainContext(
            String sessionId,
            String userId,
            String userMessage,
            PromptContext promptContext,
            ChatStreamEmitter emitter,
            ChatRunExecutionContext runContext,
            AttachmentOwnerIdentity attachmentOwnerIdentity) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.userMessage = userMessage;
        this.promptContext = promptContext;
        this.emitter = emitter;
        this.runContext = runContext;
        this.attachmentOwnerIdentity = attachmentOwnerIdentity;
    }
    @Override public String sessionId() { return sessionId; }
    @Override public String userId() { return userId; }
    @Override public AttachmentOwnerIdentity attachmentOwnerIdentity() { return attachmentOwnerIdentity; }
    @Override public String userMessage() { return userMessage; }
    @Override public PromptContext promptContext() { return promptContext; }
    @Override public ChainContext withSystemNote(String note) { return this; }
    @Override public ChainContext withAssistantSideNote(String note) { return this; }
    @Override public ChainContext withAttachment(AttachmentDto att) { return this; }
    @Override
    public ChainContext withLocalDocs(List<Document> docs) {
        if (docs == null || docs.isEmpty() || promptContext == null) {
            return this;
        }
        List<Document> merged = new ArrayList<>();
        if (promptContext.localDocs() != null) {
            merged.addAll(promptContext.localDocs());
        }
        merged.addAll(docs);
        promptContext = promptContext.toBuilder().localDocs(List.copyOf(merged)).build();
        return this;
    }
    @Override
    public ChainContext putMeta(String key, String value) {
        // Persist metadata in the local context
        meta.put(key, value);
        // For image.* entries, also propagate to a thread-local holder so
        // that the image service can override its defaults based on the
        // chain’s annotations.  This avoids requiring explicit context
        // parameters on the image service API.
        try {
            if (key != null && key.startsWith("image.")) {
                com.example.lms.image.ImageMetaHolder.put(key, value);
            }
        } catch (Exception ignore) {
            // ignore failures in meta propagation
            log.debug("[DefaultChainContext] fail-soft stage={}", "imageMeta.propagation");
        }
        return this;
    }
    @Override
    public void emitAssistant(String text) {
        if (emitter == null) {
            return;
        }
        if (runContext != null) {
            emitter.sendToken(runContext, text);
        } else if (sessionId != null) {
            emitter.sendToken(sessionId, text);
        }
    }
    public Map<String, String> meta() { return meta; }
}
