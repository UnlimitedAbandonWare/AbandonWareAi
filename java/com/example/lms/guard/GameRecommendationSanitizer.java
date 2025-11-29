
// src/main/java/com/example/lms/guard/GameRecommendationSanitizer.java
package com.example.lms.guard;

import com.example.lms.prompt.PromptContext;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Primary;
import java.util.*;



@Primary
@Component
public class GameRecommendationSanitizer implements AnswerSanitizer {
    @Override
    public String sanitize(String answer, PromptContext ctx) {
        if (answer == null) return "";
        if (ctx == null) return answer;
        // If UI-derived interaction rules indicate a contradiction, trust UI over text gen.
        Map<String, Set<String>> rules;
        try {
            rules = ctx.interactionRules();
        } catch (Throwable t) {
            try {
                var m = ctx.getClass().getMethod("getInteractionRules");
                @SuppressWarnings("unchecked")
                Map<String, Set<String>> r = (Map<String, Set<String>>) m.invoke(ctx);
                rules = r;
            } catch (Exception ignore) {
                rules = null;
            }
        }
        if (rules != null) {
            boolean pyroDiscouraged = rules.entrySet().stream().anyMatch(e -> {
                String k = e.getKey()==null? "": e.getKey().toUpperCase(Locale.ROOT);
                return (k.contains("DISCOURAGED") || k.contains("AVOID") || k.contains("WEAK") || k.contains("COUNTER"))
                    && e.getValue()!=null && e.getValue().stream().anyMatch(v -> "PYRO".equalsIgnoreCase(v));
            });
            if (pyroDiscouraged) {
                // Optionally annotate or nudge, but do not silently delete - prefer earlier retrieval correction
                return answer; // non-destructive sanitization
            }
        }
        return answer;
    }
}