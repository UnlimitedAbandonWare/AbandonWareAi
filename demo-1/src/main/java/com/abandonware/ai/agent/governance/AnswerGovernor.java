package com.abandonware.ai.agent.governance;

import com.abandonware.ai.agent.model.ChatContext;
import com.abandonware.ai.agent.model.ChatMode;
import org.springframework.stereotype.Component;

@Component
public class AnswerGovernor {
    public String governAnswer(String raw, ChatContext ctx) {
        int citeCount = 0; // stub
        if (ctx.getMode() == ChatMode.BRAVE && citeCount < 3) {
            raw += "\n(note: add more citations)";
        }
        return raw;
    }
}