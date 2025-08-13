// src/main/java/com/example/lms/guard/AnswerSanitizer.java
package com.example.lms.guard;

import com.example.lms.prompt.PromptContext;

public interface AnswerSanitizer {
    String sanitize(String answer, PromptContext ctx);
}
