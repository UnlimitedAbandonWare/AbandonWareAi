package com.example.lms.service.soak;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

class SoakQuickJsonlExporterPathBoundaryTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsConfiguredFileNamesThatEscapeTheExportDirectory() {
        Path exportDir = tempDir.resolve("export");
        Path escapedFile = tempDir.resolve("escaped.jsonl");
        SoakQuickJsonlExporter exporter = exporter(
                exportDir, Path.of("..", escapedFile.getFileName().toString()).toString());

        exporter.append(report());

        assertFalse(Files.exists(escapedFile), escapedFile.toString());
    }

    @Test
    void rejectsAbsoluteConfiguredFileNamesOutsideTheExportDirectory() {
        Path exportDir = tempDir.resolve("export");
        Path escapedFile = tempDir.resolve("absolute-escaped.jsonl").toAbsolutePath();
        SoakQuickJsonlExporter exporter = exporter(exportDir, escapedFile.toString());

        exporter.append(report());

        assertFalse(Files.exists(escapedFile), escapedFile.toString());
    }

    @Test
    void preservesTheExistingInDirectoryJsonlAppendContract() throws Exception {
        Path exportDir = tempDir.resolve("export");
        Path output = exportDir.resolve("seed10.jsonl");
        SoakQuickJsonlExporter exporter = exporter(exportDir, output.getFileName().toString());

        exporter.append(report());

        assertTrue(Files.isRegularFile(output));
        List<String> lines = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"schemaVersion\":\"soak-quick-v1\""), lines.get(0));
    }

    private static SoakQuickJsonlExporter exporter(Path dir, String fileName) {
        SoakQuickJsonlExporter exporter = new SoakQuickJsonlExporter();
        ReflectionTestUtils.setField(exporter, "dir", dir.toString());
        ReflectionTestUtils.setField(exporter, "fileName", fileName);
        return exporter;
    }

    private static SoakQuickReport report() {
        SoakQuickReport report = new SoakQuickReport();
        report.topic = "synthetic-path-boundary";
        return report;
    }
}
