package com.abandonware.ai.agent.integrations.prompt;


import java.util.*;
/**
 * AnswerPromptBuilder: assemble final prompt from web/vector/memory/KG sections.
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