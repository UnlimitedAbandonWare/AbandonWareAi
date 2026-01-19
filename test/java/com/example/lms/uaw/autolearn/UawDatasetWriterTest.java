package com.example.lms.uaw.autolearn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UawDatasetWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void append_excludesFallbackModelUsed() {
        UawDatasetWriter w = new UawDatasetWriter();
        File f = tempDir.resolve("uaw.jsonl").toFile();

        boolean ok = w.append(
                f,
                "ds",
                "q",
                "a",
                "gemma3:27b:fallback:evidence",
                3,
                "s1"
        );

        assertFalse(ok);
        assertFalse(f.exists());
    }

    @Test
    void append_excludesDegradedBannerText() {
        UawDatasetWriter w = new UawDatasetWriter();
        File f = tempDir.resolve("uaw.jsonl").toFile();

        String answer = "※ [DEGRADED MODE] LLM 호출이 실패/차단되어 'Evidence-only(LLM-OFF)' 경로로 답변했습니다.\n실제 답변";

        boolean ok = w.append(
                f,
                "ds",
                "q",
                answer,
                "gpt-4.1",
                3,
                "s1"
        );

        assertFalse(ok);
        assertFalse(f.exists());
    }

    @Test
    void append_writesNormalSample() throws Exception {
        UawDatasetWriter w = new UawDatasetWriter();
        File f = tempDir.resolve("uaw.jsonl").toFile();

        boolean ok = w.append(
                f,
                "ds",
                "q",
                "a",
                "gpt-4.1",
                3,
                "s1"
        );

        assertTrue(ok);
        assertTrue(f.exists());

        String content = Files.readString(f.toPath());
        assertTrue(content.contains("\"question\":\"q\""));
        assertTrue(content.contains("\"answer\":\"a\""));
        assertTrue(content.contains("\"model\":\"gpt-4.1\""));
    }
}
