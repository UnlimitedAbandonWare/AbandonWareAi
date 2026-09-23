package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.integrations.MessageChannelService;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.springframework.stereotype.Component;
import java.util.Map;




/**
 * Sends a message to a Channel channel or user.  This tool simply delegates
 * to {@link MessageChannelService} and returns a small acknowledgement
 * payload.  A scope of {@code message.send} is required.
 */
@Component
@RequiresScopes({ToolScope.MESSAGE_SEND})
public class MessageSendTool implements AgentTool {
    private final MessageChannelService messages;

    public MessageSendTool(MessageChannelService messages) {
        this.messages = messages;
    }

    @Override
    public String id() {
        return "message.send";
    }

    @Override
    public String description() {
        return "Send a Channel message to a room/user.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request.input();
        String roomId = trimToNull(input.get("roomId"));
        String text = textOrNull(input.get("text"));
        boolean accepted = messages.send(roomId, text);
        String targetHash = Integer.toHexString(String.valueOf(roomId).hashCode());
        return ToolResponse.ok()
                .put("accepted", accepted)
                .put("provider", "outbox/noop")
                .put("targetHash", targetHash)
                .put("disabledReason", "message channel provider is not configured");
    }

    private static String trimToNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String textOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
