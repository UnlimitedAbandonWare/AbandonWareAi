// src/main/java/com/example/lms/guard/GenshinRecommendationSanitizer.java
package com.example.lms.guard;

import com.example.lms.prompt.PromptContext;
import org.springframework.stereotype.Component;
import java.util.Locale;
import java.util.Map;
import java.util.Set;




@Component
public class GenshinRecommendationSanitizer implements AnswerSanitizer {
    private final GameRecommendationSanitizer delegate = new GameRecommendationSanitizer();
    @Override public String sanitize(String answer, PromptContext ctx){ return delegate.sanitize(answer, ctx); }
}