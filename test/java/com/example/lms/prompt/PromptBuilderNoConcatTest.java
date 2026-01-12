package com.example.lms.prompt;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;



import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures that {@code ChatService} constructs prompts exclusively via
 * {@link com.example.lms.prompt.PromptBuilder#build} rather than manual
 * string concatenation.  The presence of hard-coded hint markers would
 * indicate that the service is not using the prompt builder consistently.
 */
public class PromptBuilderNoConcatTest {

    @Test
    public void chat_service_never_concatenates_prompt_strings() throws Exception {
        // Determine the location of the ChatService source file.  Under this
        // repository layout the Java sources live in src/src/main/java.
        // Locate the ChatService source relative to the repository root.  In this
        // project layout the Java sources live in src/main/java.
        Path source = Path.of("src/main/java/com/example/lms/service/ChatService.java");
        String contents = Files.readString(source);
        // There should be no literal "[HINT]" strings in the source once the
        // prompt builder refactoring has been applied.
        assertThat(contents).doesNotContain("\"[HINT]\"");
        // The service should delegate prompt construction to PromptBuilder.
        assertThat(contents).contains("promptBuilder.build(");
    }
}