package com.example.lms.service.prompt;

import com.example.lms.service.prompt.model.PromptContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;



public class EvidencePromptBuilder {

    public String build(PromptContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("### INSTRUCTIONS\n");
        sb.append("- Synthesize answers from sources (higher authority first). Cite evidence. If insufficient, reply '정보 없음'.\n\n");

        sb.append("### Evidence (pinned)\n");
        int docId = 1;
        for (String s : nonNull(ctx.webSnippets())) {
            sb.append("- [W").append(docId++).append("] ").append(s).append("\n");
        }
        for (String s : nonNull(ctx.vectorSnippets())) {
            sb.append("- [V").append(docId++).append("] ").append(s).append("\n");
        }
        sb.append("\n");

        Set<String> tokens = new LinkedHashSet<>();
        for (String s : nonNull(ctx.webSnippets())) tokens.addAll(extractTokens(s));
        for (String s : nonNull(ctx.vectorSnippets())) tokens.addAll(extractTokens(s));

        sb.append("### Task\n");
        sb.append("Use the evidence above. Retain key proper nouns from the evidence. Provide a concise, direct answer first, then a short rationale.\n");
        sb.append("Never claim '정보 없음' if relevant evidence exists.\n\n");

        sb.append("### Must-include entities (at least 2 if present):\n");
        for (String t : tokens) {
            sb.append("- ").append(t).append("\n");
        }
        return sb.toString();
    }

    private static List<String> nonNull(List<String> in) {
        return in == null ? List.of() : in;
    }

    private static final Pattern TOKEN_SPLIT =
            Pattern.compile("[\\n\\r\\t ,;:/()\\[\\]{}<>|]+");

    private static List<String> extractTokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String t : TOKEN_SPLIT.split(s)) {
            if (t.length() >= 2 && !isNoise(t)) out.add(t);
        }
        return out;
    }

    private static boolean isNoise(String t) {
        String x = t.toLowerCase();
        return x.equals("the") || x.equals("and") || x.equals("or") || x.equals("with") || x.equals("of");
    }
}