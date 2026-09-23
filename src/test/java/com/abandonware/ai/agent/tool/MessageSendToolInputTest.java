package com.abandonware.ai.agent.tool;

import com.abandonware.ai.agent.integrations.MessageChannelService;
import com.abandonware.ai.agent.tool.impl.MessageSendTool;
import com.abandonware.ai.agent.tool.request.ToolContext;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MessageSendToolInputTest {

    @Test
    void executeStringifiesTextAndTrimsRoomIdBeforeSending() {
        MessageChannelService messages = mock(MessageChannelService.class);
        when(messages.send("room-1", "42")).thenReturn(false);
        MessageSendTool tool = new MessageSendTool(messages);

        ToolResponse response = tool.execute(new ToolRequest(
                Map.of("roomId", " room-1 ", "text", 42),
                new ToolContext("session-1", null)));

        assertEquals(false, response.data().get("accepted"));
        assertEquals("message channel provider is not configured", response.data().get("disabledReason"));
        verify(messages).send("room-1", "42");
    }
}
