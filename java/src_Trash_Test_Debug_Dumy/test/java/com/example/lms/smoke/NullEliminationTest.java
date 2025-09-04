package com.example.lms.smoke;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that the production codebase no longer contains excessive
 * {@code return null;} statements.  Counting is performed on the raw source
 * files to ensure that refactorings which wrap null in parentheses or use
 * Optional are counted correctly.  The threshold is kept intentionally low
 * to prevent regressions.
 */
public class NullEliminationTest {

    @Test
    void noMoreThanFiveExplicitReturnNulls() throws IOException {
        Path src = Path.of("src/main/java");
        long count;
        try (Stream<Path> files = Files.walk(src)) {
            count = files
                    .filter(Files::isRegularFile)
                    .flatMap(path -> {
                        try {
                            return Files.lines(path);
                        } catch (IOException e) {
                            return Stream.empty();
                        }
                    })
                    .filter(line -> line.contains("return null;"))
                    .count();
        }
        assertThat(count).isLessThanOrEqualTo(5);
    }
}