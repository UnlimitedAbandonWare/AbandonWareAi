package com.abandonware.ai.agent.integrations.prompt;


import java.util.*;
/**
 * [GPT-PRO-AGENT v2] - concise navigation header (no runtime effect).
 * Module: com.abandonware.ai.agent.integrations.prompt.AnswerPromptBuilder
 * Role: config
 * Feature Flags: sse
 * Observability: propagates trace headers if present.
 * Thread-Safety: appears stateless.
 */
/* agent-hint:
id: com.abandonware.ai.agent.integrations.prompt.AnswerPromptBuilder
role: config
flags: [sse]
*/
public class AnswerPromptBuilder {
    public String build(Map<String, List<String>> sections){
        StringBuilder sb = new StringBuilder();
        sb.append("# Answer Prompt\n");
        for (Map.Entry<String, List<String>> e : sections.entrySet()){
            sb.append("## ").append(e.getKey()).append("\n");
            for (String line : e.getValue()) sb.append("- ").append(line).append("\n");
        }
        return sb.toString();
    }
}