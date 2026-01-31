package com.abandonware.ai.agent.tool.impl;

import com.abandonware.ai.agent.consent.ConsentService;
import com.abandonware.ai.agent.integrations.KakaoMessageService;
import com.abandonware.ai.agent.tool.AgentTool;
import com.abandonware.ai.agent.tool.ToolScope;
import com.abandonware.ai.agent.tool.annotations.RequiresScopes;
import com.abandonware.ai.agent.tool.request.ToolRequest;
import com.abandonware.ai.agent.tool.response.ToolResponse;
import org.springframework.stereotype.Component;
import java.util.Map;




/**
 * Sends a message to a Kakao channel or user.  This tool simply delegates
 * to {@link KakaoMessageService} and returns a small acknowledgement
 * payload.  A scope of {@code kakao.push} is required.
 */
@Component
@RequiresScopes({ToolScope.KAKAO_PUSH})
public class KakaoPushTool implements AgentTool {
    private final KakaoMessageService kakao;

    public KakaoPushTool(KakaoMessageService kakao) {
        this.kakao = kakao;
    }

    @Override
    public String id() {
        return "kakao.push";
    }

    @Override
    public String description() {
        return "Send a Kakao message to a room/user.";
    }

    @Override
    public ToolResponse execute(ToolRequest request) {
        Map<String, Object> input = request.input();
        String roomId = (String) input.get("roomId");
        String text = (String) input.get("text");
        kakao.send(roomId, text);
        return ToolResponse.ok().put("roomId", roomId).put("sent", true);
    }
}