package com.example.lms.service.soak;

import com.example.lms.search.TraceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class DefaultSoakTestServiceTest {

    @AfterEach
    void clearTrace() {
        TraceStore.clear();
    }

    @Test
    void runTreatsNullSearchResultsAsEmpty() {
        DefaultSoakTestService service = new DefaultSoakTestService(
                topic -> List.of("soak query"),
                (query, k) -> null,
                null,
                null);

        SoakReport report = service.run(3, "provider");

        assertEquals(1, report.getRuns());
        assertEquals(0.0, report.getMetrics().nDCG10);
        assertEquals(List.of(), report.getErrors());
    }

    @Test
    void runQuickCancellationUsesOperationalNoteWithoutRawExceptionClass() {
        DefaultSoakTestService service = new DefaultSoakTestService(
                topic -> List.of("soak query"),
                (query, k) -> {
                    throw new CancellationException("cancelled ownerToken=secret");
                },
                null,
                null);

        SoakQuickReport report = service.runQuick(3, "provider");

        assertEquals(1, report.items.size());
        String note = report.items.get(0).note;
        assertEquals("error:cancelled", note);
        assertFalse(note.contains("CancellationException"));
        assertFalse(note.contains("ownerToken"));
        assertEquals(Boolean.TRUE, TraceStore.get("soak.default.suppressed.quickSearch"));
        assertEquals("cancelled", TraceStore.get("soak.default.suppressed.quickSearch.errorType"));
        assertEquals("quickSearch", TraceStore.get("soak.default.suppressed.stage"));
        assertEquals("cancelled", TraceStore.get("soak.default.suppressed.errorType"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("CancellationException"));
        assertFalse(String.valueOf(TraceStore.getAll()).contains("ownerToken"));
    }

    @Test
    void runQuickWrappedCancellationUsesCauseTypeWithoutRawExceptionClass() {
        DefaultSoakTestService service = new DefaultSoakTestService(
                topic -> List.of("soak query"),
                (query, k) -> {
                    throw new RuntimeException(
                            "wrapper ownerToken=secret",
                            new CancellationException("cancelled ownerToken=secret"));
                },
                null,
                null);

        SoakQuickReport report = service.runQuick(3, "provider");

        assertEquals(1, report.items.size());
        String note = report.items.get(0).note;
        assertEquals("error:cancelled", note);
        assertFalse(note.contains("RuntimeException"));
        assertFalse(note.contains("CancellationException"));
        assertFalse(note.contains("ownerToken"));
    }

    @Test
    void defaultSoakTestServiceDoesNotUseExactEmptyCatchBlocks() throws Exception {
        String source = Files.readString(
                Path.of("main/java/com/example/lms/service/soak/DefaultSoakTestService.java"),
                StandardCharsets.UTF_8);

        assertFalse(Pattern.compile("catch\\s*\\([^)]+\\)\\s*\\{\\s*\\}", Pattern.DOTALL)
                .matcher(source)
                .find());
    }
}
