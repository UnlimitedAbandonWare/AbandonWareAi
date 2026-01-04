package com.example.lms.service.rag.chain.impl;

import com.example.lms.dto.AttachmentDto;
import com.example.lms.prompt.PromptContext;
import com.example.lms.service.chat.ChatStreamEmitter;
import com.example.lms.service.rag.chain.ChainContext;
import java.util.HashMap;
import java.util.Map;




public class DefaultChainContext implements ChainContext {
    private final String sessionId;
    private final String userId;
    private final String userMessage;
    private final PromptContext promptContext;
    private final ChatStreamEmitter emitter;
    private final Map<String, String> meta = new HashMap<>();

    public DefaultChainContext(String sessionId, String userId, String userMessage, PromptContext promptContext, ChatStreamEmitter emitter) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.userMessage = userMessage;
        this.promptContext = promptContext;
        this.emitter = emitter;
    }
    @Override public String sessionId() { return sessionId; }
    @Override public String userId() { return userId; }
    @Override public String userMessage() { return userMessage; }
    @Override public PromptContext promptContext() { return promptContext; }
    @Override public ChainContext withSystemNote(String note) { /* no-op passthrough; PromptBuilder reads PromptContext */ return this; }
    @Override public ChainContext withAssistantSideNote(String note) { return this; }
    @Override public ChainContext withAttachment(AttachmentDto att) { /* PromptContext assembly occurs in ChatService; this is advisory */ return this; }
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
        }
        return this;
    }
    @Override public void emitAssistant(String text) { if (emitter != null && sessionId != null) { emitter.sendToken(sessionId, text); } }
    public Map<String, String> meta() { return meta; }
}