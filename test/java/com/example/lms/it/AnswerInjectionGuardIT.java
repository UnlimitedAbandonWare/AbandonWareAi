package com.example.lms.it;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;



import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against prompt injection patterns that hard-code an "Answer" block
 * directly in the chat service.  The presence of such a block indicates
 * brittle prompt construction that may override the dynamic sections of the
 * prompt.  This test reads the ChatService source file and asserts that it
 * does not contain any literal "### ANSWER" markers.
 */
public class AnswerInjectionGuardIT {

    @Test
    public void chatService_does_not_inject_answer_section() throws Exception {
        Path source = Path.of("src/main/java/com/example/lms/service/ChatService.java");
        String content = Files.readString(source);
        assertThat(content).doesNotContain("### ANSWER");
        assertThat(content).doesNotContain("# ANSWER");
        assertThat(content).doesNotContain("Answer:");
    }
}